package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.CommandNotSent
import dev.picasso.ops.host.lease.HostLeadership
import dev.picasso.ops.host.lease.LeaderState
import dev.picasso.ops.host.lease.SendAuthority
import dev.picasso.ops.host.store.AuthorityLost
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.ops.host.store.NotAuthority
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant

/** 리스 행(DB). 못 읽었으면 칸이 비어 있다. */
data class LeaseView(val holder: String?, val generation: Long?, val expiresAt: Instant?, val live: Boolean?)

/** 만료 인수 뒤 운영자 확인(«옛 프로세스 종료 확인»). */
data class TakeoverConfirmationView(val confirmedBy: String, val reason: String, val confirmedAt: Instant)

/** 이번 보유의 인수. [graceful] 이면 앞 보유자가 멈춤을 적어 곧바로 발행했고, 아니면 만료 인수라 운영자 확인([confirmation]) 뒤에 발행한다. */
data class TakeoverView(val generation: Long, val previousHolder: String?, val graceful: Boolean, val confirmation: TakeoverConfirmationView?)

/**
 * `GET /host/leader`·`GET /host/ready` 의 본문. [ready] 는 발행하는 리더([LeaderState.ISSUING])이고 자기 차단 기한 전일 때만 참이다.
 * [generation] 은 이 인스턴스가 쥔 세대(없으면 `null`), [lease] 는 DB 의 리스 행이다(보유자가 다른 인스턴스일 수 있다).
 */
data class LeaderView(
    val instanceId: String,
    val state: LeaderState,
    val ready: Boolean,
    val generation: Long?,
    val lease: LeaseView,
    val takeover: TakeoverView?,
    val lostReason: String?,
)

/**
 * 발행하는 리더가 아니어서 받지 않은 변경 요청(503). [holder]·[generation] 은 DB 의 리스 행이다. [holder] 는 인스턴스 id(`mw-<UUID>`)이지 주소가
 * 아니다 — 보낼 곳은 각 호스트의 `GET /host/leader` 로 그 id 를 가진 호스트를 찾아 정한다.
 */
data class NotIssuingRejection(
    val error: String,
    val detail: String,
    val state: LeaderState,
    val instanceId: String,
    val holder: String?,
    val generation: Long?,
)

/**
 * `POST /host/leader/takeover-confirmation` 의 본문. 누가 어떤 근거로 옛 프로세스가 끝난 것을 확인했는가와, 확인한 대상 — 운영자가 `GET /host/leader`
 * 에서 본 이 호스트의 인스턴스 id([instanceId])·세대([generation])·앞 보유자([previousHolder], 없으면 `null`). 대상이 지금과 다르면 받지 않는다.
 */
data class TakeoverConfirmationRequest(
    val confirmedBy: String? = null,
    val reason: String? = null,
    val instanceId: String? = null,
    val generation: Long? = null,
    val previousHolder: String? = null,
)

/**
 * 리더 조회와 만료 인수 뒤 운영자 확인(HA 설계 기록 §7). 운영 서비스는 고치지 않는다 — `ops.host.url` 을 사람이 바꾸거나 앞단 프록시가 [ready] 를 본다.
 */
@RestController
class LeaderController(private val leadership: HostLeadership, private val records: HostRecords) {

    @GetMapping("/host/leader")
    fun leader(): LeaderView = view(leadership, records)

    /** 준비 상태. 발행하는 리더만 200 이고 나머지(대기·재대조·관측·잃음)는 503 이다. 본문은 [leader] 와 같다. */
    @GetMapping("/host/ready")
    fun ready(): ResponseEntity<LeaderView> {
        val view = view(leadership, records)
        return ResponseEntity.status(if (view.ready) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE).body(view)
    }

    /**
     * 만료 인수([LeaderState.HOLDING]) 뒤 운영자가 옛 프로세스가 끝난 것을 확인했다. 누가·왜·언제를 `mission.host_takeover_confirmation` 에 적고
     * (리스 가드 안) 발행([LeaderState.ISSUING])을 연다. 한 번에 하나씩 처리한다.
     * - 칸이 비면 400 `BAD_REQUEST`.
     * - [LeaderState.HOLDING] 이 아니면 409 `NOT_HOLDING`.
     * - 본문의 인스턴스·세대·앞 보유자가 지금과 다르면 409 `TAKEOVER_TARGET_CHANGED`(본 뒤에 호스트가 바뀌었거나 다른 인수가 났다).
     * - 앞 보유자가 살아 있다는 직접 증거(심장박동 만료 전이고 멈춤·자기 차단 없음)가 있으면 409 `PREVIOUS_HOLDER_LIVE`.
     * - 그 세대의 확인이 이미 있으면: 아직 관측 중이고 그 확인의 대상(인스턴스·앞 보유자)이 같으면 못 마친 발행 전이를 이어 간다(앞 요청이 확인 행만
     *   적고 발행 표시에서 실패한 경우). 그 밖은 409 `ALREADY_CONFIRMED`(이미 발행 중이면 위의 `NOT_HOLDING`).
     */
    @PostMapping(CONFIRM_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Synchronized
    fun confirm(@RequestBody(required = false) body: TakeoverConfirmationRequest?): ResponseEntity<Any> {
        val by = body?.confirmedBy?.trim().orEmpty()
        val reason = body?.reason?.trim().orEmpty()
        if (by.isEmpty() || reason.isEmpty() || body?.instanceId.isNullOrBlank() || body?.generation == null) {
            return ResponseEntity.badRequest().body(HostRejection(HostRequests.BAD_REQUEST, "confirmedBy·reason·instanceId·generation 이 비어 있다"))
        }
        val target = checkNotNull(body)
        val acquired = leadership.acquired
        val generation = leadership.lease.generation
        if (leadership.state != LeaderState.HOLDING || acquired == null || generation == null) {
            return conflict(NOT_HOLDING, "만료 인수 뒤 관측 중인 리더가 아니다: ${leadership.state}")
        }
        if (target.instanceId != leadership.instanceId || target.generation != generation || target.previousHolder != acquired.previousHolder) {
            return conflict(
                TAKEOVER_TARGET_CHANGED,
                "확인한 대상(${target.instanceId}, 세대 ${target.generation}, 앞 보유자 ${target.previousHolder})이 지금" +
                    "(${leadership.instanceId}, 세대 $generation, 앞 보유자 ${acquired.previousHolder})과 다르다",
            )
        }
        acquired.previousHolder?.takeIf { records.instanceAlive(it) }?.let {
            return conflict(PREVIOUS_HOLDER_LIVE, "앞 보유자 $it 의 심장박동이 살아 있고 멈춤·자기 차단을 적지 않았다")
        }
        try {
            records.confirmTakeover(generation, leadership.instanceId, acquired.previousHolder, by, reason)
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            // 앞 확인이 행만 적고 발행 표시·전이를 못 마쳤으면(연결 장애 따위) 같은 대상의 재요청이 그 전이를 이어 간다(멱등). 다른 대상이면 409.
            val existing = records.takeoverConfirmation(generation)
            if (existing == null || existing.instanceId != leadership.instanceId || existing.previousHolder != acquired.previousHolder) {
                return conflict(ALREADY_CONFIRMED, "세대 $generation 의 확인이 이미 있다")
            }
        }
        if (!leadership.confirmed()) return conflict(NOT_HOLDING, "발행을 열지 못했다: ${leadership.state}")
        return ResponseEntity.ok(view(leadership, records))
    }

    private fun conflict(error: String, detail: String): ResponseEntity<Any> = ResponseEntity.status(HttpStatus.CONFLICT).body(HostRejection(error, detail))

    companion object {
        const val CONFIRM_PATH = "/host/leader/takeover-confirmation"
        const val NOT_HOLDING = "NOT_HOLDING"
        const val TAKEOVER_TARGET_CHANGED = "TAKEOVER_TARGET_CHANGED"
        const val PREVIOUS_HOLDER_LIVE = "PREVIOUS_HOLDER_LIVE"
        const val ALREADY_CONFIRMED = "ALREADY_CONFIRMED"
        const val NOT_ISSUING = "NOT_ISSUING"

        fun view(leadership: HostLeadership, records: HostRecords): LeaderView {
            val row = runCatching { records.leaseRow() }.getOrNull()
            val acquired = leadership.acquired
            val generation = leadership.lease.generation
            return LeaderView(
                instanceId = leadership.instanceId,
                state = leadership.state,
                ready = leadership.mayIssue(),
                generation = generation,
                lease = LeaseView(row?.holder, row?.generation, row?.expiresAt, row?.live),
                takeover = acquired?.let { a ->
                    TakeoverView(
                        a.generation, a.previousHolder, a.graceful,
                        runCatching { records.takeoverConfirmation(a.generation) }.getOrNull()?.let { TakeoverConfirmationView(it.confirmedBy, it.reason, it.confirmedAt) },
                    )
                },
                lostReason = leadership.lostReason,
            )
        }

        fun rejection(leadership: HostLeadership, records: HostRecords, detail: String): NotIssuingRejection {
            val row = runCatching { records.leaseRow() }.getOrNull()
            return NotIssuingRejection(NOT_ISSUING, detail, leadership.state, leadership.instanceId, row?.holder, row?.generation)
        }
    }
}

/**
 * 변경 요청 관문. 발행하는 리더([LeaderState.ISSUING]·자기 차단 기한 전, `GET /host/ready` 와 같은 기준)가 아니면 읽기(GET·HEAD·OPTIONS) 밖의
 * 요청을 503 `NOT_ISSUING` 과 리스 보유자·세대로 돌려준다. 예외: 관측([LeaderState.HOLDING]) 중의 운영자 확인([LeaderController.CONFIRM_PATH])과
 * 판정 조회(`POST /host/eligibility`, 쓰지 않는다).
 */
@Component
class LeaderGate(private val leadership: HostLeadership, private val records: HostRecords, private val json: ObjectMapper) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val state = leadership.state
        val path = request.requestURI
        val passes = request.method in READS || leadership.mayIssue() ||
            state == LeaderState.HOLDING && (path == LeaderController.CONFIRM_PATH || path == ELIGIBILITY)
        if (passes) {
            chain.doFilter(request, response)
            return
        }
        response.status = HttpStatus.SERVICE_UNAVAILABLE.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        json.writeValue(response.outputStream, LeaderController.rejection(leadership, records, "발행하는 리더가 아니어서 변경 요청을 받지 않는다: $state"))
    }

    private companion object {
        val READS = setOf("GET", "HEAD", "OPTIONS")
        const val ELIGIBILITY = "/host/eligibility"
    }
}

/** 요청을 처리하는 도중 리더가 아니게 된 경우(DB 가드, 송신 직전 확인)도 503 `NOT_ISSUING` 이다. */
@RestControllerAdvice
class LeaderAdvice(private val leadership: HostLeadership, private val records: HostRecords) {
    @ExceptionHandler(NotAuthority::class, AuthorityLost::class)
    fun lost(e: RuntimeException): ResponseEntity<NotIssuingRejection> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(LeaderController.rejection(leadership, records, e.message ?: e.toString()))

    @ExceptionHandler(CommandNotSent::class)
    fun notSent(e: CommandNotSent): ResponseEntity<Any> =
        if (e.message?.startsWith(SendAuthority.NOT_ISSUING) == true) {
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(LeaderController.rejection(leadership, records, e.message ?: ""))
        } else {
            throw e
        }
}
