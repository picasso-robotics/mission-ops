package dev.picasso.ops.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.wire.FencedCall
import dev.picasso.mimic.cli.MimicCli
import dev.picasso.mimic.engine.AuthorityOutcome
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState

/** 장애 주입의 결과. 거부는 그대로 HTTP 응답이 된다. 받아들임의 [body] 가 `200` 본문이다. */
sealed interface FaultInjection {
    data class Accepted(val body: Map<String, Any?>) : FaultInjection

    data class Refused(val status: Int, val error: String, val detail: String) : FaultInjection
}

/**
 * 기체 장애 주입(S4a 스펙 §5, T1, T2). 셀 대역의 루프백 `HttpServer` 에 붙는 `POST /faults` 하나이며 호출자는 실행 호스트뿐이다.
 * mimic 제어 채널을 열지 않고 같은 프로세스의 엔진 객체를 `MimicServer.exclusive` 아래에서 직접 부른다. 시계 밀기·시계 모드·
 * 시드는 어느 경로로도 내지 않는다.
 *
 * 종류는 넷이다.
 *
 * - 스킬 실패([SKILL_FAULTS]): 그 기체의 진행 중(RUNNING·PAUSED·CANCELLING) 태스크 가운데 그 스킬의 것을 현장이 스스로
 *   찾아 `tasks.forceFault` 로 강제한다. 호출자는 태스크 id 를 모른다. 없으면 409 [NO_RUNNING_TASK] 다.
 * - 연결 상태([CONNECTION]): `events.setConnection` 으로 [CONNECTION_STATES] 가운데 하나로 바꾼다. `HIBERNATING` 은 받지
 *   않는다.
 * - 제어권 상실([CONTROL_AUTHORITY_LOST]): `tasks.forceControlAuthorityLoss` 로 기체 수준 결함 `CONTROL_AUTHORITY_LOST` 를
 *   세운다. 지울 때까지 유지되고 새 태스크를 못 받는 결함이라 실행 호스트가 다음 로봇 단위의 시작 전에 실행을 막는다. 진행 중
 *   태스크는 엔진이 실패로 보내고, 진행 중 태스크가 없어도 결함은 선다. 프로파일이 배타 제어 모델이 아니면
 *   (`exclusive_control_required=false`) 엔진이 거부하고 409 [FAULT_REFUSED] 다.
 * - 제어권 회복([CONTROL_AUTHORITY_REGAINED]): 그 결함을 엔진의 결함 목록에서 지우고 해소 이벤트를 낸다. picasso 에 지우는
 *   호출이 없어 현장이 엔진의 결함 목록과 이벤트 스트림을 직접 부른다. 서 있지 않았으면 지운 것 없이 받아들인다.
 *
 * 둘을 짝으로 두는 까닭은 결함 감수가 그 실행만 풀기 때문이다. 감수한 결함은 그 실행을 다시 막지 않지만 결함은 기체에 그대로
 * 서 있어 같은 기체의 다음 실행이 다시 막힌다. 지우는 종류가 없으면 현장을 재기동해야 그 기체가 풀린다.
 *
 * 그 밖의 지울 때까지 유지되는 결함(PAYLOAD_LOST, LOCALIZATION_LOST)과 전송 장애는 종류 목록에 없으므로 400
 * [UNSUPPORTED_FAULT] 다.
 *
 * 엔진을 부른 뒤 밀거나 정착시키지 않는다. 열린 스트림에는 다음 시계 진행의 정착(런처는 1초 주기, 시험은 시계 밀기)이 민다.
 *
 * `POST /faults` 는 셀 신호 쓰기와 같은 키([SiteHttpFence.CELL])로 요청 헤더의 제어자 세대를 맞댄다. 세대 거부는 409 `STALE_CONTROLLER` 이고 엔진 거부의 409
 * [FAULT_REFUSED] 와 본문 `error` 로 가른다. 모든 응답에 셀 키의 바닥값 헤더를 싣는다.
 */
class SiteFaults(private val mimic: MimicCli.Started, private val fence: SiteHttpFence = SiteHttpFence()) {

    private val json = ObjectMapper()

    /**
     * 장애 하나를 넣는다. 판정 순서는 종류(400) → 기체(404) → 진행 중 태스크나 엔진 거부(409)다. 엔진 판정과 호출은 한 잠금
     * 아래에서 한다.
     *
     * @param state 연결 상태 종류일 때의 목표 상태. 다른 종류에는 쓰지 않는다.
     */
    fun inject(robotId: String, kind: String, state: String?): FaultInjection {
        val skill = SKILL_FAULTS[kind]
        val connection = state?.let { CONNECTION_STATES[it] }
        if (skill == null && kind != CONNECTION && kind !in AUTHORITY_KINDS) {
            return FaultInjection.Refused(400, UNSUPPORTED_FAULT, "받지 않는 장애 종류다: $kind (받는 것: ${KINDS.joinToString(", ")})")
        }
        if (kind == CONNECTION && connection == null) {
            return FaultInjection.Refused(
                400, UNSUPPORTED_FAULT, "받지 않는 연결 상태다: $state (받는 것: ${CONNECTION_STATES.keys.joinToString(", ")})",
            )
        }
        return mimic.server.exclusive {
            val instance = mimic.instance(robotId)
                ?: return@exclusive FaultInjection.Refused(404, UNKNOWN_ROBOT, "이 현장에 없는 기체다: $robotId")
            if (connection != null) {
                val changed = instance.events.setConnection(connection)
                FaultInjection.Accepted(linkedMapOf("robotId" to robotId, "kind" to kind, "state" to state, "changed" to changed))
            } else if (kind == CONTROL_AUTHORITY_LOST) {
                when (val outcome = instance.tasks.forceControlAuthorityLoss()) {
                    is AuthorityOutcome.Lost -> FaultInjection.Accepted(
                        linkedMapOf("robotId" to robotId, "kind" to kind, "raised" to outcome.raised, "terminated" to outcome.terminated),
                    )
                    is AuthorityOutcome.Rejected -> FaultInjection.Refused(409, FAULT_REFUSED, outcome.detail)
                }
            } else if (kind == CONTROL_AUTHORITY_REGAINED) {
                // 엔진이 세운 결함은 참조가 없는 기체 수준 결함이다. 실제로 지운 것만 해소 이벤트로 낸다(없던 것을 알리면 유령 해소다).
                val cleared = instance.faults.clear(CONTROL_AUTHORITY_LOST)
                cleared.forEach { instance.events.onFault(it, cleared = true) }
                FaultInjection.Accepted(linkedMapOf("robotId" to robotId, "kind" to kind, "cleared" to cleared.isNotEmpty()))
            } else {
                val task = instance.tasks.all.lastOrNull { it.skillType == skill && it.machine.state in RUNNING_STATES }
                    ?: return@exclusive FaultInjection.Refused(
                        409, NO_RUNNING_TASK, "$robotId 에 진행 중인 $skill 태스크가 없다(진행 중: ${RUNNING_STATES.joinToString(", ")})",
                    )
                when (val outcome = instance.tasks.forceFault(kind, task.taskId)) {
                    is ForceOutcome.Raised -> FaultInjection.Accepted(
                        linkedMapOf(
                            "robotId" to robotId,
                            "kind" to kind,
                            "taskId" to task.taskId,
                            "taskState" to outcome.taskState?.name,
                            "raised" to outcome.raised,
                        ),
                    )
                    // 잠금 아래에서 진행 중 태스크를 골랐으므로 오지 않는다. 엔진이 거부하면 그 이유를 그대로 낸다.
                    is ForceOutcome.NotFound -> FaultInjection.Refused(409, FAULT_REFUSED, "엔진이 태스크를 찾지 못했다: ${outcome.taskId}")
                    is ForceOutcome.Rejected -> FaultInjection.Refused(409, FAULT_REFUSED, outcome.detail)
                }
            }
        }
    }

    /** `POST /faults`. 셀 대역의 `HttpServer` 가 이 경로를 넘긴다. */
    fun handle(exchange: HttpExchange) {
        try {
            respondTo(exchange)
        } finally {
            exchange.close()
        }
    }

    private fun respondTo(exchange: HttpExchange) {
        val scope = fence.scope(exchange, SiteHttpFence.CELL)
        val (status, body) = scope.use {
            when {
                scope.malformed != null -> 400 to rejection(BAD_REQUEST, scope.malformed)
                exchange.requestURI.path != PATH -> 404 to ByteArray(0)
                exchange.requestMethod != "POST" -> {
                    exchange.responseHeaders.add("Allow", "POST")
                    405 to ByteArray(0)
                }
                else -> respond(exchange, scope)
            }
        }
        scope.stamp()
        if (body.isNotEmpty()) exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.write(body)
    }

    /**
     * 본문은 `{"robotId": "<기체>", "kind": "<종류>", "state": "<목표 상태>"}` 이다. `state` 는 [CONNECTION] 일 때 필수이고 그
     * 밖에는 없어야 한다. 칸이 문자열이 아니거나 빠지면 400 [BAD_REQUEST] 다.
     *
     * `application/json` 만 받는다. 브라우저의 단순 요청(폼·`text/plain`)은 사전 요청 없이 다른 출처에서 올 수 있다.
     */
    private fun respond(exchange: HttpExchange, scope: SiteHttpFence.Scope): Pair<Int, ByteArray> {
        val contentType = exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()
        if (!contentType.equals("application/json", ignoreCase = true)) {
            return 415 to rejection(UNSUPPORTED_MEDIA_TYPE, "Content-Type 이 application/json 이 아니다: ${contentType ?: "없음"}")
        }
        val node: JsonNode? = runCatching { json.readTree(exchange.requestBody.readAllBytes()) }.getOrNull()?.takeIf { it.isObject }
        val robotId = node?.text("robotId")
        val kind = node?.text("kind")
        val stateNode = node?.get("state")?.takeUnless { it.isNull }
        val state = stateNode?.takeIf { it.isTextual }?.asText()
        val shaped = robotId != null && kind != null && (stateNode == null || state != null) &&
            ((kind == CONNECTION) == (state != null))
        if (!shaped) {
            return 400 to rejection(
                BAD_REQUEST,
                "본문이 {\"robotId\": \"<기체>\", \"kind\": \"<종류>\"} 모양이 아니다. state 는 $CONNECTION 일 때만 문자열로 싣는다",
            )
        }
        return scope.mutation(FencedCall.START) {
            when (val outcome = inject(robotId!!, kind!!, state)) {
                is FaultInjection.Accepted -> 200 to json.writeValueAsBytes(outcome.body)
                is FaultInjection.Refused -> outcome.status to rejection(outcome.error, outcome.detail)
            }
        }
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

    private fun rejection(error: String, detail: String): ByteArray =
        json.writeValueAsBytes(linkedMapOf("error" to error, "detail" to detail))

    companion object {
        const val PATH = "/faults"

        /** 연결 상태 종류. 목표 상태를 `state` 에 싣는다. */
        const val CONNECTION = "CONNECTION"

        /** 제어권 상실 종류. 이름은 엔진이 세우는 결함의 `error_type` 이다. */
        const val CONTROL_AUTHORITY_LOST = "CONTROL_AUTHORITY_LOST"

        /** 제어권 회복 종류. [CONTROL_AUTHORITY_LOST] 결함을 지운다. */
        const val CONTROL_AUTHORITY_REGAINED = "CONTROL_AUTHORITY_REGAINED"

        /** 제어권 종류 둘. `state` 를 싣지 않는다. */
        val AUTHORITY_KINDS: List<String> = listOf(CONTROL_AUTHORITY_LOST, CONTROL_AUTHORITY_REGAINED)

        /**
         * 스킬 실패 종류와 그 스킬. 종류 이름은 picasso 프로파일의 `error_type` 이다. 스스로 재시도 가능하고 새 태스크까지만
         * 유지되는 것만 둔다(T2).
         */
        val SKILL_FAULTS: Map<String, String> = mapOf("SKILL_EXECUTION_FAILED" to "pick_place")

        /** 받는 연결 상태. 바깥 이름은 접두사 없는 이름이다. */
        val CONNECTION_STATES: Map<String, ConnectionState> = linkedMapOf(
            "OFFLINE" to ConnectionState.CONNECTION_STATE_OFFLINE,
            "CONNECTION_BROKEN" to ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN,
            "ONLINE" to ConnectionState.CONNECTION_STATE_ONLINE,
        )

        /** 받는 종류 전부. 오류 설명에 쓴다. */
        val KINDS: List<String> get() = SKILL_FAULTS.keys.toList() + CONNECTION + AUTHORITY_KINDS

        /** 진행 중 태스크 상태. mimic 이 결함을 받는 상태(`TaskHost.FAULTABLE`, 비공개)와 같은 셋이다. */
        val RUNNING_STATES: Set<TaskState> = setOf(TaskState.RUNNING, TaskState.PAUSED, TaskState.CANCELLING)

        /** 오류 이름(S4a JSON 계약 §1). 실행 호스트가 같은 이름을 그대로 넘긴다. */
        const val BAD_REQUEST = SiteCell.BAD_REQUEST
        const val UNSUPPORTED_MEDIA_TYPE = SiteCell.UNSUPPORTED_MEDIA_TYPE
        const val UNSUPPORTED_FAULT = "UNSUPPORTED_FAULT"
        const val UNKNOWN_ROBOT = "UNKNOWN_ROBOT"
        const val NO_RUNNING_TASK = "NO_RUNNING_TASK"
        const val FAULT_REFUSED = "FAULT_REFUSED"
    }
}
