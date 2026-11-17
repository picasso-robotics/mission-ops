package dev.picasso.ops.host.lease

import dev.picasso.middleware.CommandNotSent
import java.net.http.HttpHeaders
import java.net.http.HttpResponse

/**
 * 현장 HTTP 면(플릿 `/fleet`·셀 대역 `/cell`·결함 주입 `/faults`)에 싣는 제어자 세대와, 응답 바닥값의 밀려남 판정(HA 3단계 PR B1). 기체 gRPC 의
 * picasso `PicassoClient`·`ClientRobotPort` 와 같은 규칙이다.
 *
 * - **처음 본 0 아닌 세대를 붙잡는다.** 요청마다 [forRequest] 가 실을 세대를 정한다 — 붙잡은 값, 아직 없으면 공급자([supply], 리스 세대 — 대기·잃음이면
 *   0)의 지금 값. 붙잡은 뒤 공급자가 다른 값을 내면(잃어서 0) 변경은 보내지 않고 [CommandNotSent] 로 던진다. 읽기는 붙잡은 값을 싣고 보낸다.
 * - **응답마다 본문을 읽기 전에 바닥값을 본다**([observing] — 응답 머리말을 받은 순간, 본문 구독 전에 [observe]). 본문을 받다 끊겨도 머리말의 밀려남은
 *   놓치지 않는다. 그 요청에 실은 세대 `mine` 이 0 보다 크고 응답 바닥값이 그보다 높으면 밀려남이다 —
 *   [superseded] 를 부른다(운영 배선은 [HostLeadership.superseded] → 잃음). 상태 코드는 보지 않는다(200 도 밀려남이고, 409 만으로는 밀려남이 아니다).
 *
 * @param receiver 밀려남 기록에 실을 수신자 이름(`플릿`·`셀`).
 */
class HttpEpoch(
    private val receiver: String,
    private val supply: () -> Long = { 0L },
    private val superseded: (String, Long, Long) -> Unit = { _, _, _ -> },
) {

    @Volatile
    private var pinned = 0L

    /** 붙잡은 세대. 아직 0 아닌 값을 못 봤으면 공급자의 지금 값. */
    val epoch: Long get() = pinned.takeIf { it != 0L } ?: supply()

    /**
     * 이 요청에 실을 세대. 처음 본 0 아닌 값을 붙잡는다. [mutation] 이면 붙잡은 뒤 공급자가 다른 값을 낼 때 보내지 않고 [CommandNotSent] 를 던진다 —
     * 문구는 [SendAuthority.NOT_ISSUING] 으로 시작한다(REST 는 503 `NOT_ISSUING`).
     */
    fun forRequest(mutation: Boolean, what: String): Long = synchronized(this) {
        val supplied = supply()
        if (pinned == 0L) pinned = supplied
        if (mutation && supplied != pinned) {
            throw CommandNotSent("${SendAuthority.NOT_ISSUING}: 제어자 세대가 바뀌었다(붙잡은 값 $pinned, 지금 값 $supplied) — $what 을(를) 보내지 않았다")
        }
        pinned
    }

    /**
     * 응답 머리말의 바닥값을 본다. [mine] 은 그 요청에 실은 세대다. 바닥값 헤더가 없거나 읽을 수 없으면 0(대조 안 함)으로 본다. 밀려났으면 [superseded] 를
     * 부르고 참이다.
     */
    fun observe(mine: Long, headers: HttpHeaders): Boolean {
        val floor = floorOf(headers)
        if (mine <= 0 || floor <= mine) return false
        superseded(receiver, mine, floor)
        return true
    }

    /**
     * 본문을 바이트로 받는 응답 처리기. 머리말을 받은 순간(본문을 구독하기 전) [observe] 로 바닥값을 본다 — 본문 수신이 실패해도 밀려남은 이미 판단됐다.
     * [mine] 은 그 요청에 실은 세대다.
     */
    fun observing(mine: Long): HttpResponse.BodyHandler<ByteArray> = HttpResponse.BodyHandler { info ->
        observe(mine, info.headers())
        HttpResponse.BodySubscribers.ofByteArray()
    }

    companion object {
        /** 요청 헤더 — 발신자의 제어자 세대(10진). 현장 `SiteHttpFence.EPOCH_HEADER` 와 같은 이름이다. */
        const val EPOCH_HEADER = "X-Picasso-Controller-Epoch"

        /** 응답 헤더 — 그 면의 바닥값(10진). 현장 `SiteHttpFence.FLOOR_HEADER` 와 같은 이름이다. */
        const val FLOOR_HEADER = "X-Picasso-Controller-Epoch-Floor"

        /** 세대 거부의 `error` 값(현장 `SiteHttpFence.STALE_CONTROLLER`). */
        const val STALE_CONTROLLER = "STALE_CONTROLLER"

        /**
         * 인수 확인 요청의 제한. 현장은 앞 세대로 통과한 변경을 1초(`SiteHttpFence.CONFIRM_WAIT`)까지 기다린 뒤 `DURABLE_PENDING` 으로 답하므로, 그보다
         * 넉넉히(대기 + 2초) 둔다 — 같으면 그 답 대신 시간 초과(안 닿음)가 된다. 플릿·셀 확인이 같은 값을 쓴다.
         */
        val CONFIRM_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(3)

        /** 현장이 바닥값을 저장하지 못해 변경 없이 받지 않았다(`SiteHttpFence.FENCE_STORE_FAILED`, 503). */
        const val FENCE_STORE_FAILED = "FENCE_STORE_FAILED"

        /** 응답 머리말의 바닥값. 없거나 10진 정수가 아니면 0. */
        fun floorOf(headers: HttpHeaders): Long =
            headers.firstValue(FLOOR_HEADER).orElse(null)?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: 0L
    }
}

/**
 * 현장 HTTP 면 인수 확인(`POST /fleet/controller/confirm`·`/cell/controller/confirm`)의 답. [status] 는 HTTP 상태 코드이고, 200 이면 [floor]·[fenceStatus]·
 * [receiverBootId] 가 본문 그대로다(바닥값은 10진 문자열을 읽은 값). 그 밖(409 세대 거부, 옛 현장의 404·405 따위)은 본문을 [body] 에만 든다.
 */
data class ControllerConfirmation(val status: Int, val floor: Long?, val fenceStatus: String?, val receiverBootId: String?, val body: String) {
    companion object {
        fun read(status: Int, body: ByteArray, json: com.fasterxml.jackson.databind.ObjectMapper): ControllerConfirmation {
            val text = body.toString(Charsets.UTF_8)
            val node = if (status == 200) runCatching { json.readTree(text) }.getOrNull() else null
            return ControllerConfirmation(
                status,
                node?.get("controllerEpochFloor")?.takeIf { it.isTextual }?.asText()?.toLongOrNull(),
                node?.get("fenceStatus")?.takeIf { it.isTextual }?.asText(),
                node?.get("receiverBootId")?.takeIf { it.isTextual }?.asText(),
                text.take(500),
            )
        }
    }
}
