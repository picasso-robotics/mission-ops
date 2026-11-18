package dev.picasso.ops.site

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import dev.picasso.contracts.wire.ControllerFence
import dev.picasso.contracts.wire.FenceMode
import dev.picasso.contracts.wire.FencedCall
import java.time.Duration

/**
 * 현장 HTTP 면(플릿 `/fleet`·셀 대역 `/cell`·결함 주입 `/faults`)의 **수신 측 제어자 세대 대조**(HA 3단계 PR B1). 기체 gRPC 의 수신 측 대조(picasso
 * ADR 54)와 같은 판정을 picasso [ControllerFence] 로 한다 — 대상(기체 id 자리)은 키 둘이다: [FLEET](맡기기·취소)와 [CELL](셀 신호 쓰기·`/faults`).
 *
 * ## 헤더
 *
 * 요청 [EPOCH_HEADER] 는 발신자의 세대(10진)다. 없으면 0(«싣지 않음»)이다. 형식이 틀리거나(10진 숫자 아님) 두 번 실렸거나 범위(0..2^63-1) 밖이면 400
 * 이다. 응답에는 늘 [FLOOR_HEADER] 에 그 키의 바닥값을 싣는다(대조 안 함이면 0). 받는 쪽(실행 호스트)은 이 값이 자기 세대보다 높으면 밀려남으로 읽는다.
 *
 * ## 대조하는 것
 *
 * 변경 넷(맡기기·취소·셀 신호 쓰기·`POST /faults`)만 [Scope.mutation] 으로 맞댄다. 모든 읽기(GET)와 현장 운영의 세계 채우기(`/fleet/world/...`)는
 * 맞대지 않는다 — 낮은 세대도 통과하고 바닥값을 올리지도 않는다(헤더는 싣는다). `FENCED` 에서 세대 0·바닥값보다 낮은 세대의 변경은 409
 * `STALE_CONTROLLER` 이고 세계·장부·셀 투영을 건드리지 않는다. 바닥값보다 높은 세대는 저장한 **뒤에** 올리고 처리한다. 저장이 실패하면 변경 없이
 * 503 [FENCE_STORE_FAILED] 다.
 *
 * ## 통과 표지
 *
 * 통과한 변경은 그 요청의 처리(세계 변경·장부·셀 투영)가 끝날 때까지 «통과한 것» 으로 남는다([Scope.close]). 인수 확인([confirm])은 그보다 낮은
 * 세대로 통과한 변경이 다 닫히기를 [confirmWait] 만큼 기다리고, 그 안에 안 닫혔으면 `DURABLE_PENDING` 이다. 현장 서버의 실행기가 직렬인지에 기대지
 * 않는다.
 *
 * @param fence 대조. 기본은 대조 안 함(`UNFENCED`, `SITE_HTTP_FENCE=off`) — 응답 바닥값 0, 확인은 `UNSPECIFIED`.
 * @param confirmWait 인수 확인이 앞서 통과한 낮은 세대 변경을 기다리는 상한. 실행 호스트의 확인 요청 제한보다 짧게 둔다.
 */
class SiteHttpFence(
    private val fence: ControllerFence = ControllerFence(),
    private val confirmWait: Duration = CONFIRM_WAIT,
) {

    private val json = ObjectMapper()

    /** 이 면이 세대를 대조하는가(`FENCED`). */
    val fenced: Boolean get() = fence.mode == FenceMode.FENCED

    /** [key] 의 바닥값. 대조 안 함이면 0. */
    fun floor(key: String): Long = fence.floor(key)

    private val refusalCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    /** [key] 에서 세대로 거절한(409 `STALE_CONTROLLER`) 변경·인수 확인 수(측정·진단). */
    fun refusals(key: String): Long = refusalCounts[key]?.get() ?: 0L

    private fun countRefusal(key: String) {
        refusalCounts.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
    }

    /** 요청 하나의 대조 자리를 연다. 헤더를 읽고, 닫으면 통과 표지를 닫는다. */
    fun scope(exchange: HttpExchange, key: String): Scope = Scope(exchange, key)

    /**
     * 인수 확인 하나. [epoch] 는 요청 헤더의 세대, [bootId] 는 현장 기동 ID 다. 상태 코드와 본문(JSON 객체)을 돌려준다.
     *
     * - 대조 안 함: 200 `{controllerEpochFloor: "0", fenceStatus: "UNSPECIFIED"}`.
     * - 세대 0·바닥값보다 낮음(기다리는 동안 더 높은 세대가 올린 것 포함): 409 `STALE_CONTROLLER`.
     * - 그 밖: 바닥값을 (저장한 뒤) 이 세대로 올리고 낮은 세대로 통과한 변경을 기다린 뒤 200 — 다 닫혔으면 `DURABLE_CONFIRMED`, 아니면
     *   `DURABLE_PENDING`(메모리 저장이면 `VOLATILE`).
     * - 저장 실패: 503 [FENCE_STORE_FAILED].
     */
    fun confirm(key: String, epoch: Long, bootId: String): Pair<Int, Map<String, Any?>> {
        if (!fenced) return 200 to confirmed(0, UNSPECIFIED, bootId)
        if (epoch <= 0L || epoch < fence.floor(key)) return 409 to stale(epoch, fence.floor(key)).also { countRefusal(key) }
        val confirmation = try {
            fence.confirm(key, epoch, confirmWait)
        } catch (e: Exception) {
            return 503 to storeFailed(key, e)
        } ?: return 409 to stale(epoch, fence.floor(key)).also { countRefusal(key) }
        val status = confirmation.status(fence.durability, inDoubt = false).name.removePrefix(STATUS_PREFIX)
        return 200 to confirmed(epoch, status, bootId)
    }

    /** [confirm] 을 HTTP 본문으로. */
    fun confirmReply(key: String, epoch: Long, bootId: String): Pair<Int, ByteArray> =
        confirm(key, epoch, bootId).let { (status, body) -> status to json.writeValueAsBytes(body) }

    private fun confirmed(floor: Long, status: String, bootId: String): Map<String, Any?> = linkedMapOf(
        "controllerEpochFloor" to floor.toString(),
        "fenceStatus" to status,
        "receiverBootId" to bootId,
    )

    private fun stale(requested: Long, floor: Long): Map<String, Any?> {
        val refusal = ControllerFence.Admission.Stale(requested, floor)
        return linkedMapOf(
            "error" to STALE_CONTROLLER,
            "reason" to refusal.reason.name,
            "requestedControllerEpoch" to requested.toString(),
            "controllerEpochFloor" to floor.toString(),
            "detail" to refusal.detail,
        )
    }

    private fun storeFailed(key: String, e: Exception): Map<String, Any?> =
        linkedMapOf("error" to FENCE_STORE_FAILED, "detail" to "$key 바닥값을 저장하지 못해 받지 않았다: ${e.message}")

    /**
     * 요청 하나의 대조 자리. [requested] 는 헤더의 세대(없으면 0), [malformed] 는 헤더가 틀렸을 때의 설명(그때 [requested] 는 0 이고 부른 쪽은 400 으로
     * 답한다). 변경은 [mutation] 으로 감싸고, 응답을 보내기 전에 [stamp] 로 바닥값 헤더를 싣는다. 처리가 끝나면 [close] 로 통과 표지를 닫는다.
     */
    inner class Scope internal constructor(private val exchange: HttpExchange, val key: String) : AutoCloseable {
        val malformed: String?
        val requested: Long

        init {
            val values = exchange.requestHeaders[EPOCH_HEADER].orEmpty()
            var problem: String? = null
            var epoch = 0L
            when {
                values.isEmpty() -> Unit
                values.size > 1 -> problem = "$EPOCH_HEADER 가 두 번 이상 실렸다: $values"
                else -> {
                    val text = values.single().trim()
                    if (!DECIMAL.matches(text)) {
                        problem = "$EPOCH_HEADER 가 10진 정수가 아니다: '$text'"
                    } else {
                        epoch = text.toLongOrNull() ?: 0L.also { problem = "$EPOCH_HEADER 가 받는 범위(0..${Long.MAX_VALUE}) 밖이다: $text" }
                    }
                }
            }
            malformed = problem
            requested = epoch
        }

        private var admitted: ControllerFence.Admission.Admitted? = null

        /** 대조가 이 변경을 받지 않았다(409·503). 그 요청은 세계도 셀 투영도 건드리지 않았다. */
        var refused: Boolean = false
            private set

        /** 이 요청이 세계를 바꿨을 수 있는가 — 헤더가 맞고 대조가 막지 않았다. 부른 쪽은 거짓이면 투영을 다시 하지 않는다. */
        val mayHaveChanged: Boolean get() = malformed == null && !refused

        /**
         * 변경 하나를 맞댄다. 통과면 [apply] 를 부르고 그 답을 돌려준다(통과 표지는 [close] 까지 열려 있다). 거부면 409 `STALE_CONTROLLER`, 저장
         * 실패면 503 이고 [apply] 를 부르지 않는다.
         */
        fun mutation(call: FencedCall, apply: () -> Pair<Int, ByteArray>): Pair<Int, ByteArray> {
            check(malformed == null) { "헤더가 틀린 요청은 400 으로 답한다" }
            check(admitted == null) { "요청 하나에 변경 하나다" }
            val admission = try {
                fence.command(key, requested, call)
            } catch (e: Exception) {
                refused = true
                return 503 to json.writeValueAsBytes(storeFailed(key, e))
            }
            return when (admission) {
                is ControllerFence.Admission.Stale -> {
                    refused = true
                    countRefusal(key)
                    409 to json.writeValueAsBytes(stale(admission.requested, admission.floor))
                }
                is ControllerFence.Admission.Admitted -> {
                    admitted = admission
                    apply()
                }
            }
        }

        /** 응답 헤더에 지금 바닥값을 싣는다. 보내기 직전에 부른다(통과한 변경이 올린 바닥값이 실린다). */
        fun stamp() {
            exchange.responseHeaders.set(FLOOR_HEADER, floor(key).toString())
        }

        /** 통과 표지를 닫는다. 세계 변경·장부·셀 투영이 끝난 뒤에 부른다. 겹쳐 불러도 된다. */
        override fun close() {
            admitted?.close()
        }
    }

    companion object {
        /** 요청 헤더 — 발신자의 제어자 세대(10진). */
        const val EPOCH_HEADER = "X-Picasso-Controller-Epoch"

        /** 응답 헤더 — 그 면의 바닥값(10진). 대조 안 함이면 0. */
        const val FLOOR_HEADER = "X-Picasso-Controller-Epoch-Floor"

        /** 플릿 면(맡기기·취소)의 대조 키. */
        const val FLEET = "fleet"

        /** 셀 면(셀 신호 쓰기·`/faults`)의 대조 키. */
        const val CELL = "cell"

        /** 세대 거부. 기체 gRPC 의 `REJECTION_CODE_STALE_CONTROLLER` 와 같은 뜻이다. */
        const val STALE_CONTROLLER = "STALE_CONTROLLER"

        /** 바닥값을 저장하지 못해 받지 않았다. */
        const val FENCE_STORE_FAILED = "FENCE_STORE_FAILED"

        /** 대조하지 않는 면의 확인 답. */
        const val UNSPECIFIED = "UNSPECIFIED"

        /** 인수 확인이 앞서 통과한 변경을 기다리는 기본 상한. 실행 호스트의 셀 확인 요청 제한(4초)보다 짧다. */
        val CONFIRM_WAIT: Duration = Duration.ofSeconds(1)

        private const val STATUS_PREFIX = "FENCE_STATUS_"

        private val DECIMAL = Regex("[0-9]+")
    }
}
