package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import dev.picasso.ops.service.executions.ExecutionCancelOutcome
import dev.picasso.ops.service.executions.ExecutionCloseOutcome
import dev.picasso.ops.service.executions.ExecutionControls
import dev.picasso.ops.service.executions.ExecutionReleaseOutcome
import dev.picasso.ops.service.executions.RobotEmptyOutcome
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostExecutionControls
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.web.ExecutionControlController
import dev.picasso.ops.service.web.PreRejection
import dev.picasso.registry.PostgresSupport
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 운영자 실행 취소와 기체 빈손 확인 API(P0-b2), 운영자 결함 감수 API(S3a JSON 계약 §9.8). 게이트와 사전 거부, 조작 기록의 결과 매김, 응답 없음 뒤 재조회의 대조를 본다. 컨트롤러를
 * 스프링 없이 바로 부르고 호스트는 대역이다. `application/json` 제한은 스프링의 몫이다.
 */
class ExecutionControlsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val clock = IncidentOperationsTest.MovableClock(T0)
    private val json = ObjectMapper()
    private val host = FakeHost()
    private val controller = ExecutionControlController(ExecutionControls(host, log, requeryDelay = Duration.ZERO, clock = clock))

    /** 호스트 대역. 기본은 취소를 CANCELED 로, 해소를 200 으로 답한다. */
    inner class FakeHost : HostExecutionControls {
        var cancelAnswer: HostWrite = HostWrite.Answered(200, cancelBody("CANCELED"))
        var clearAnswer: HostWrite = HostWrite.Answered(200, CLEARED)
        var releaseAnswer: HostWrite = HostWrite.Answered(200, releaseBody("RELEASED"))
        var closeAnswer: HostWrite = HostWrite.Answered(200, closeBody("CLOSED"))

        /** 종결 요청을 받을 때 종결 조회를 바꾼다. 재조회 본문이 이 요청의 id 를 실어야 할 때 쓴다. */
        var onClose: (UUID) -> Unit = {}
        var closureAnswer: HostCall<JsonNode?> = HostCall.Silent("응답 없음: ConnectException")

        /** 감수 요청을 받을 때 실행 목록을 바꾼다. 재조회 본문이 이 요청의 id 를 실어야 할 때 쓴다. */
        var onRelease: (UUID) -> Unit = {}
        var executions: HostCall<JsonNode> = HostCall.Silent("응답 없음: ConnectException")
        var holds: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"holds":[$HOLD]}"""))
        val calls = mutableListOf<Map<String, Any?>>()

        override fun executions(): HostCall<JsonNode> = executions.also { calls += mapOf("op" to "executions") }

        override fun executionsOf(executionId: String): HostCall<JsonNode> =
            executions.also { calls += mapOf("op" to "executionsOf", "executionId" to executionId) }

        override fun cancelExecution(executionId: String, approverId: String, reason: String, instanceId: String, requestId: UUID): HostWrite {
            calls += mapOf("op" to "cancel", "executionId" to executionId, "approverId" to approverId, "reason" to reason, "instanceId" to instanceId)
            return cancelAnswer
        }

        override fun releaseFaults(
            executionId: String,
            approverId: String,
            reason: String,
            instanceId: String,
            sawFaults: ArrayNode,
            requestId: UUID,
        ): HostWrite {
            calls += mapOf(
                "op" to "release", "executionId" to executionId, "approverId" to approverId, "reason" to reason,
                "instanceId" to instanceId, "sawFaults" to sawFaults, "requestId" to requestId,
            )
            onRelease(requestId)
            return releaseAnswer
        }

        override fun closeUnrestored(
            jobOrderId: String,
            approverId: String,
            reason: String,
            executionInstanceId: String,
            executionId: String,
            attestedStopped: Boolean,
            requestId: UUID,
        ): HostWrite {
            calls += mapOf(
                "op" to "close", "jobOrderId" to jobOrderId, "approverId" to approverId, "reason" to reason,
                "executionInstanceId" to executionInstanceId, "executionId" to executionId, "attestedStopped" to attestedStopped, "requestId" to requestId,
            )
            onClose(requestId)
            return closeAnswer
        }

        override fun closure(jobOrderId: String, requestId: UUID): HostCall<JsonNode?> =
            closureAnswer.also { calls += mapOf("op" to "closure", "jobOrderId" to jobOrderId, "requestId" to requestId) }

        override fun residualHolds(): HostCall<JsonNode> = holds.also { calls += mapOf("op" to "residualHolds") }

        /** 빈손 확인 요청을 받을 때 표시 조회를 바꾼다. 재조회 본문이 이 요청의 id 를 실어야 할 때 쓴다. */
        var onClear: (UUID) -> Unit = {}
        var record: HostCall<JsonNode?> = HostCall.Silent("응답 없음: ConnectException")

        override fun clearResidualHold(robotId: String, markerId: Long, approverId: String, reason: String, requestId: UUID, cited: JsonNode?): HostWrite {
            calls += buildMap {
                put("op", "clear"); put("robotId", robotId); put("markerId", markerId); put("approverId", approverId); put("reason", reason)
                if (cited != null) put("cited", cited)
            }
            onClear(requestId)
            return clearAnswer
        }

        override fun residualHold(robotId: String, markerId: Long): HostCall<JsonNode?> =
            record.also { calls += mapOf("op" to "residualHold", "robotId" to robotId, "markerId" to markerId) }

        fun writes() = calls.filter { it["op"] in setOf("cancel", "clear", "release", "close") }
    }

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    private fun ResponseEntity<Any>.rejection(): PreRejection = assertIs<PreRejection>(body)

    private fun cancel(mode: String?, user: String? = "kim", body: String = """{"instanceId":"mw-1","reason":"부분 완료 닫기"}"""): ResponseEntity<Any> =
        controller.cancel("exec-1", mode, user, body.toByteArray())

    private fun release(mode: String?, user: String? = "kim", body: String = RELEASE_REQUEST): ResponseEntity<Any> =
        controller.release("exec-1", mode, user, body.toByteArray())

    private fun close(mode: String?, user: String? = "kim", body: String = CLOSE_REQUEST): ResponseEntity<Any> =
        controller.close("JO-1", mode, user, body.toByteArray())

    private fun clear(mode: String?, user: String? = "kim", marker: String = "7", body: String = """{"reason":"현장에서 빈손 확인"}"""): ResponseEntity<Any> =
        controller.clear("humanoid-01", marker, mode, user, body.toByteArray())

    @Test
    fun `두 조작은 엔지니어 모드면 403 이고 행위자 헤더가 없으면 400 이며 호스트를 부르지 않고 기록하지 않는다`() {
        listOf(cancel("engineer"), clear("engineer")).forEach { reply ->
            assertEquals(403, reply.statusCode.value())
            assertEquals("MODE_NOT_ALLOWED", reply.rejection().error)
            assertEquals("이 조작은 operator 모드에서 한다", reply.rejection().detail)
        }
        listOf(cancel(null), cancel("operator", user = null), clear(null), clear("operator", user = "김 운영")).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals("ACTOR_REQUIRED", reply.rejection().error)
        }
        assertEquals(emptyList(), host.calls)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `본문이 틀리면 사유보다 먼저 400 이고 사유가 없으면 400 REASON_REQUIRED 이며 호스트를 부르지 않는다`() {
        listOf("취소", """{"reason":"r"}""", """{"instanceId":" ","reason":"r"}""", """{"instanceId":3,"reason":"r"}""").forEach { body ->
            assertEquals(ExecutionControlController.CANCEL_BAD_REQUEST, cancel("operator", body = body).rejection().error, body)
        }
        listOf("x", "0", "-3", "1.5").forEach { marker ->
            assertEquals(ExecutionControlController.CLEAR_BAD_REQUEST, clear("operator", marker = marker).rejection().error, marker)
        }
        listOf(
            cancel("operator", body = """{"instanceId":"mw-1"}"""),
            cancel("operator", body = """{"instanceId":"mw-1","reason":"  "}"""),
            clear("operator", body = """{}"""),
            clear("operator", body = """{"reason":7}"""),
        ).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals(ExecutionControlController.REASON_REQUIRED, reply.rejection().error)
        }
        assertEquals(emptyList(), host.calls)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `취소는 승인자가 행위자이고 CANCELED 는 성공 그 밖의 결과와 호스트 4xx 는 거부로 남는다`() {
        val canceled = assertIs<ExecutionCancelOutcome>(cancel("operator", body = """{"instanceId":"mw-1","reason":" 부분 완료 닫기 ","approverId":"mallory"}""").body)
        assertEquals(listOf(OperationResult.SUCCEEDED, "CANCELED"), listOf(canceled.result, canceled.outcome))
        assertEquals(
            mapOf("op" to "cancel", "executionId" to "exec-1", "approverId" to "kim", "reason" to "부분 완료 닫기", "instanceId" to "mw-1"),
            host.writes().single(),
        )
        val row = log.list().single()
        assertEquals(listOf("kim", "exec-1", "부분 완료 닫기"), listOf(row.user, row.target, row.reason))
        assertEquals(
            json.readTree("""{"op":"CANCEL_EXECUTION","executionId":"exec-1","approverId":"kim","instanceId":"mw-1"}"""),
            json.readTree(row.request),
        )

        listOf("NOT_CANCELABLE", "NOT_FOUND", "REFUSED").forEach { result ->
            host.cancelAnswer = HostWrite.Answered(200, cancelBody(result))
            val refused = assertIs<ExecutionCancelOutcome>(cancel("operator").body)
            assertEquals(listOf(OperationResult.REJECTED, result), listOf(refused.result, refused.outcome))
        }
        host.cancelAnswer = HostWrite.Answered(409, """{"error":"INSTANCE_MISMATCH","detail":"x"}""")
        val mismatch = assertIs<ExecutionCancelOutcome>(cancel("operator").body)
        assertEquals(OperationResult.REJECTED, mismatch.result)
        assertEquals("INSTANCE_MISMATCH", mismatch.rejection!!.error)
        assertNull(mismatch.outcome)
        assertEquals(5, log.list().size)
    }

    @Test
    fun `취소 응답이 없으면 실행 목록의 cancel 로 대조하고 인스턴스가 바뀌었으면 확인하지 못한다`() {
        host.cancelAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        host.executions = executions("mw-1", """{"approverId":"kim","approverKind":"PERSON","reason":"r","physicalState":"PARTIAL","recordedAt":"${T0.plusSeconds(1)}"}""")
        val applied = assertIs<ExecutionCancelOutcome>(cancel("operator").body)
        assertEquals(listOf(OperationResult.NO_RESPONSE, OperationResult.CONFIRMED_APPLIED), listOf(applied.result, applied.confirmation))

        // 앞선 사람의 취소(보내기 직전보다 이른 기록)는 이 요청의 반영이 아니다.
        host.executions = executions("mw-1", """{"approverId":"kim","approverKind":"PERSON","reason":"r","physicalState":"PARTIAL","recordedAt":"${T0.minusSeconds(60)}"}""")
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, assertIs<ExecutionCancelOutcome>(cancel("operator").body).confirmation)
        host.executions = executions("mw-1", "null")
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, assertIs<ExecutionCancelOutcome>(cancel("operator").body).confirmation)

        host.executions = executions("mw-2", """{"approverId":"kim","approverKind":"PERSON","reason":"r","physicalState":"PARTIAL","recordedAt":"${T0.plusSeconds(1)}"}""")
        val restarted = assertIs<ExecutionCancelOutcome>(cancel("operator").body)
        assertEquals(OperationResult.NO_RESPONSE, restarted.result)
        assertNull(restarted.confirmation)
        // 재조회는 취소한 실행 하나로 좁혀 읽고 전체 목록을 읽지 않는다.
        val reads = host.calls.filter { it["op"].toString().startsWith("executions") }
        assertEquals(List(4) { mapOf("op" to "executionsOf", "executionId" to "exec-1") }, reads)
    }

    @Test
    fun `빈손 확인은 승인자가 행위자이고 해소는 성공 404 409 는 거부이며 응답이 없으면 그 표시의 해소 행 요청 id 로 대조한다`() {
        val cleared = assertIs<RobotEmptyOutcome>(clear("operator").body)
        assertEquals(OperationResult.SUCCEEDED, cleared.result)
        assertEquals(
            mapOf("op" to "clear", "robotId" to "humanoid-01", "markerId" to 7L, "approverId" to "kim", "reason" to "현장에서 빈손 확인"),
            host.writes().single(),
        )
        val row = log.list().single()
        assertEquals(listOf("kim", "humanoid-01/7", "현장에서 빈손 확인"), listOf(row.user, row.target, row.reason))
        assertEquals(
            json.readTree("""{"op":"CONFIRM_ROBOT_EMPTY","robotId":"humanoid-01","markerId":7,"approverId":"kim"}"""),
            json.readTree(row.request),
        )

        listOf(404 to "RESIDUAL_HOLD_NOT_FOUND", 409 to "RESIDUAL_HOLD_ALREADY_CLEARED", 400 to "RESIDUAL_HOLD_CITATION_INVALID").forEach { (status, error) ->
            host.clearAnswer = HostWrite.Answered(status, """{"error":"$error","detail":"x"}""")
            val refused = assertIs<RobotEmptyOutcome>(clear("operator").body)
            assertEquals(OperationResult.REJECTED, refused.result)
            assertEquals(error, refused.rejection!!.error)
            assertNull(refused.rejection!!.fields)
        }

        // 응답 없음 뒤 재조회는 그 표시 하나의 조회만 읽고 다시 보내지 않는다. 해소 행의 요청 id 가 이 요청의 것일 때만 반영됨이다.
        host.clearAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        host.calls.clear()
        host.record = HostCall.Ok(json.readTree("""{"hold":$HOLD,"clearing":null}"""))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, assertIs<RobotEmptyOutcome>(clear("operator").body).confirmation)
        host.record = HostCall.Ok(json.readTree("""{"hold":$HOLD,"clearing":${clearing(UUID.randomUUID())}}"""))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, assertIs<RobotEmptyOutcome>(clear("operator").body).confirmation, "다른 요청의 해소")
        host.record = HostCall.Ok(null)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, assertIs<RobotEmptyOutcome>(clear("operator").body).confirmation, "표시 없음")
        host.onClear = { requestId -> host.record = HostCall.Ok(json.readTree("""{"hold":$HOLD,"clearing":${clearing(requestId)}}""")) }
        assertEquals(OperationResult.CONFIRMED_APPLIED, assertIs<RobotEmptyOutcome>(clear("operator").body).confirmation)
        host.onClear = { host.record = HostCall.Silent("응답 없음") }
        assertNull(assertIs<RobotEmptyOutcome>(clear("operator").body).confirmation)
        assertEquals(5, host.calls.count { it["op"] == "clear" }, "재조회가 다시 보냈다")
        assertEquals(List(5) { mapOf("op" to "residualHold", "robotId" to "humanoid-01", "markerId" to 7L) }, host.calls.filter { it["op"] != "clear" })
    }

    /**
     * B2: 실행 호스트가 발신자의 HOLDING 으로 409 어긋남을 답하면 조작 기록은 거부이고 응답 칸에 호스트 본문 전체가 남으며, 호출 결과의 거부에 지목할
     * 관측이 실린다. 다시 확인하는 요청의 `cited` 는 요청 본문의 것만 호스트에 가고 요청 칸에도 남는다. 모양이 틀린 지목은 호스트에 닿지 않는 400 이다.
     */
    @Test
    fun `빈손 확인의 어긋남은 거부로 남고 관측이 화면에 닿으며 지목은 요청 본문에서만 호스트로 간다`() {
        host.clearAnswer = HostWrite.Answered(409, MISMATCH)
        val refused = assertIs<RobotEmptyOutcome>(clear("operator").body)
        assertEquals(OperationResult.REJECTED, refused.result)
        val rejection = refused.rejection!!
        assertEquals(409 to "RESIDUAL_HOLD_OBSERVATION_MISMATCH", rejection.status to rejection.error)
        val fields = rejection.fields!!
        assertEquals(listOf("robotId", "markerId", "conflictRequestId", "observation"), fields.fieldNames().asSequence().toList())
        assertEquals(CONFLICT, fields["conflictRequestId"].asText())
        assertEquals("HOLDING", fields["observation"]["observedKind"].asText())
        val first = log.list().single()
        assertEquals(OperationResult.REJECTED, first.result)
        assertEquals(json.readTree("""{"status":409,"body":$MISMATCH}"""), json.readTree(first.targetResponse))
        assertEquals(null, host.writes().single()["cited"])

        // 다시 확인: 지목은 본문 그대로(정해진 다섯 칸만) 호스트와 조작 기록에 간다.
        host.clearAnswer = HostWrite.Answered(200, CLEARED)
        val cited = """{"conflictRequestId":"$CONFLICT","kind":"HOLDING","objectRef":"C-1","session":"s-1","sequence":"9007199254740993","extra":"버린다"}"""
        val again = assertIs<RobotEmptyOutcome>(clear("operator", body = """{"reason":"현장에서 빈손 확인","cited":$cited}""").body)
        assertEquals(OperationResult.SUCCEEDED, again.result)
        val sent = json.readTree("""{"conflictRequestId":"$CONFLICT","kind":"HOLDING","objectRef":"C-1","session":"s-1","sequence":"9007199254740993"}""")
        assertEquals(sent, json.readTree(host.writes().last()["cited"].toString()))
        assertEquals(sent, json.readTree(log.list().single { it.result == OperationResult.SUCCEEDED }.request)["cited"])

        listOf(
            """"x"""",
            """{"kind":"HOLDING"}""",
            """{"conflictRequestId":"not-a-uuid","kind":"HOLDING"}""",
            """{"conflictRequestId":"$CONFLICT","kind":" "}""",
            """{"conflictRequestId":"$CONFLICT","kind":"HOLDING","sequence":"12x"}""",
            """{"conflictRequestId":"$CONFLICT","kind":"HOLDING","sequence":1.5}""",
            """{"conflictRequestId":"$CONFLICT","kind":"HOLDING","session":7}""",
        ).forEach { bad ->
            val reply = clear("operator", body = """{"reason":"r","cited":$bad}""")
            assertEquals(400, reply.statusCode.value(), bad)
            assertEquals(ExecutionControlController.CLEAR_BAD_REQUEST, reply.rejection().error, bad)
        }
        assertEquals(2, host.writes().size)
    }

    @Test
    fun `미해소 표시 읽기는 모드와 관계없이 호스트 본문 그대로이고 호스트가 안 닿으면 503 HOST_SILENT 다`() {
        val read = controller.residualHolds()
        assertEquals(200, read.statusCode.value())
        assertEquals(json.readTree("""{"holds":[$HOLD]}"""), read.body)
        host.holds = HostCall.Silent("응답 없음: ConnectException")
        val silent = controller.residualHolds()
        assertEquals(503, silent.statusCode.value())
        assertEquals("HOST_SILENT", silent.rejection().error)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `결함 감수는 운영자 모드만 하고 본문이 틀리면 사유보다 먼저 400 RELEASE_BAD_REQUEST 이며 사유가 없으면 400 REASON_REQUIRED 다`() {
        val engineer = release("engineer")
        assertEquals(403, engineer.statusCode.value())
        assertEquals("MODE_NOT_ALLOWED", engineer.rejection().error)
        listOf(release(null), release("operator", user = null)).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals("ACTOR_REQUIRED", reply.rejection().error)
        }
        listOf(
            "감수",
            """{"reason":"r","sawFaults":$SAW}""",
            """{"instanceId":" ","reason":"r","sawFaults":$SAW}""",
            """{"instanceId":"mw-1"}""",
            """{"instanceId":"mw-1","reason":"r","sawFaults":[]}""",
            """{"instanceId":"mw-1","reason":"r","sawFaults":{}}""",
            """{"instanceId":"mw-1","reason":"r","sawFaults":["LOCALIZATION_LOST"]}""",
            """{"instanceId":"mw-1","reason":"r","sawFaults":[{"errorType":"","references":[]}]}""",
            """{"instanceId":"mw-1","reason":"r","sawFaults":[{"errorType":"LOCALIZATION_LOST"}]}""",
            """{"instanceId":"mw-1","reason":"r","sawFaults":[{"errorType":"LOCALIZATION_LOST","references":[{"key":"KEY_TASK_ID"}]}]}""",
            """{"instanceId":"mw-1","reason":"r","sawFaults":[{"errorType":"LOCALIZATION_LOST","references":[{"key":" ","value":"t"}]}]}""",
        ).forEach { body ->
            val reply = release("operator", body = body)
            assertEquals(400, reply.statusCode.value(), body)
            assertEquals(ExecutionControlController.RELEASE_BAD_REQUEST, reply.rejection().error, body)
        }
        listOf(
            """{"instanceId":"mw-1","sawFaults":$SAW}""",
            """{"instanceId":"mw-1","reason":"  ","sawFaults":$SAW}""",
        ).forEach { body ->
            val reply = release("operator", body = body)
            assertEquals(400, reply.statusCode.value(), body)
            assertEquals(ExecutionControlController.REASON_REQUIRED, reply.rejection().error, body)
        }
        assertEquals(emptyList(), host.calls)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `결함 감수는 승인자가 행위자이고 본 결함의 종류와 참조만 옮겨 보내며 RELEASED 는 성공 그 밖의 결과와 호스트 4xx 는 거부로 남는다`() {
        val body = """{"instanceId":"mw-1","reason":" 현장에서 기체 상태 확인 ","approverId":"mallory","sawFaults":[""" +
            """{"failureClass":"LOCALIZATION_LOST","errorType":"LOCALIZATION_LOST","errorHint":"재측위","references":[{"key":"KEY_TASK_ID","value":"t-1","extra":1}]}]}"""
        val released = assertIs<ExecutionReleaseOutcome>(release("operator", body = body).body)
        assertEquals(listOf(OperationResult.SUCCEEDED, "RELEASED"), listOf(released.result, released.outcome))
        val sent = host.writes().single()
        assertEquals(
            listOf("release", "exec-1", "kim", "현장에서 기체 상태 확인", "mw-1", released.requestId),
            listOf("op", "executionId", "approverId", "reason", "instanceId", "requestId").map { sent[it] },
        )
        assertEquals(json.readTree("""[{"errorType":"LOCALIZATION_LOST","references":[{"key":"KEY_TASK_ID","value":"t-1"}]}]"""), sent["sawFaults"])
        val row = log.list().single()
        assertEquals(listOf("kim", "exec-1", "현장에서 기체 상태 확인"), listOf(row.user, row.target, row.reason))
        assertEquals(
            json.readTree(
                """{"op":"RELEASE_FAULT_BLOCK","executionId":"exec-1","approverId":"kim","instanceId":"mw-1",""" +
                    """"sawFaults":[{"errorType":"LOCALIZATION_LOST","references":[{"key":"KEY_TASK_ID","value":"t-1"}]}]}""",
            ),
            json.readTree(row.request),
        )

        listOf("NOT_BLOCKED", "BLOCK_CHANGED", "NOT_FOUND", "REFUSED").forEach { result ->
            host.releaseAnswer = HostWrite.Answered(200, releaseBody(result))
            val refused = assertIs<ExecutionReleaseOutcome>(release("operator").body)
            assertEquals(listOf(OperationResult.REJECTED, result), listOf(refused.result, refused.outcome))
            assertEquals(result, refused.answer!!["result"].asText())
        }
        listOf(409 to "INSTANCE_MISMATCH", 409 to "REQUEST_ID_REUSED", 400 to "BAD_REQUEST").forEach { (status, error) ->
            host.releaseAnswer = HostWrite.Answered(status, """{"error":"$error","detail":"x"}""")
            val rejected = assertIs<ExecutionReleaseOutcome>(release("operator").body)
            assertEquals(listOf(OperationResult.REJECTED, status, error), listOf(rejected.result, rejected.rejection!!.status, rejected.rejection!!.error))
            assertNull(rejected.outcome)
        }
        assertEquals(8, log.list().size)
        assertEquals(mapOf(OperationResult.SUCCEEDED to 1, OperationResult.REJECTED to 7), log.list().groupingBy { it.result }.eachCount())
    }

    @Test
    fun `결함 감수 응답이 없으면 그 실행의 releases 에 이 요청 id 가 있는지로 대조하고 인스턴스가 바뀌었거나 못 읽으면 확인하지 못한다`() {
        host.releaseAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        // 재조회 본문은 이 요청의 id 를 알아야 지을 수 있다. 호스트 대역이 감수 요청을 받을 때 실행 목록을 바꾼다.
        fun answer(instanceId: String, release: (UUID) -> String) {
            host.onRelease = { id -> host.executions = releasedExecutions(instanceId, release(id)) }
        }
        val mine = { id: UUID -> """[{"requestId":"$id","approverId":"kim","reason":"r","faults":[],"recordedAt":"t"}]""" }

        answer("mw-1", mine)
        val applied = assertIs<ExecutionReleaseOutcome>(release("operator").body)
        assertEquals(listOf(OperationResult.NO_RESPONSE, OperationResult.CONFIRMED_APPLIED), listOf(applied.result, applied.confirmation))
        val observed = log.list().single { it.result == OperationResult.CONFIRMED_APPLIED }
        assertEquals(listOf(applied.requestId, "exec-1"), listOf(observed.requestId, observed.target))
        assertEquals((host.executions as HostCall.Ok).value["executions"][0], json.readTree(observed.targetResponse)["observed"])

        // 앞선 감수(다른 요청 id)만 있거나 감수가 없으면 이 요청의 반영이 아니다.
        answer("mw-1") { """[{"requestId":"${UUID.randomUUID()}","approverId":"kim","reason":"r","faults":[],"recordedAt":"t"}]""" }
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, assertIs<ExecutionReleaseOutcome>(release("operator").body).confirmation)
        answer("mw-1") { "[]" }
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, assertIs<ExecutionReleaseOutcome>(release("operator").body).confirmation)

        // 호스트가 그 사이 재기동했으면 같은 실행 id 가 다른 실행일 수 있다. 못 읽어도 확인하지 못한다.
        answer("mw-2", mine)
        val restarted = assertIs<ExecutionReleaseOutcome>(release("operator").body)
        assertEquals(listOf(OperationResult.NO_RESPONSE, null), listOf(restarted.result, restarted.confirmation))
        host.onRelease = { host.executions = HostCall.Silent("응답 없음: ConnectException") }
        assertNull(assertIs<ExecutionReleaseOutcome>(release("operator").body).confirmation)

        // 재조회는 감수한 실행 하나로 좁혀 읽는다. 확인 행은 반영됨·반영 안 됨에만 붙는다.
        val reads = host.calls.filter { it["op"].toString().startsWith("executions") }
        assertEquals(List(5) { mapOf("op" to "executionsOf", "executionId" to "exec-1") }, reads)
        assertEquals(
            listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.CONFIRMED_NOT_APPLIED, OperationResult.CONFIRMED_NOT_APPLIED),
            log.list().map { it.result }.filter { it != OperationResult.NO_RESPONSE }.sortedBy { it.name },
        )
        assertEquals(5, log.list().count { it.result == OperationResult.NO_RESPONSE })
    }

    @Test
    fun `복원 못 한 실행 종결은 운영자 모드만 하고 본문이 틀리면 CLOSE_BAD_REQUEST 사유가 없으면 REASON_REQUIRED 이며 호스트를 부르지 않는다`() {
        val engineer = close("engineer")
        assertEquals(403, engineer.statusCode.value())
        assertEquals("MODE_NOT_ALLOWED", engineer.rejection().error)
        listOf(close(null), close("operator", user = null)).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals("ACTOR_REQUIRED", reply.rejection().error)
        }
        listOf(
            "종결",
            """{"executionId":"exec-7","reason":"r","attestedStopped":false}""",
            """{"executionInstanceId":" ","executionId":"exec-7","reason":"r","attestedStopped":false}""",
            """{"executionInstanceId":"mw-0","reason":"r","attestedStopped":false}""",
            """{"executionInstanceId":"mw-0","executionId":7,"reason":"r","attestedStopped":false}""",
            """{"executionInstanceId":"mw-0","executionId":"exec-7","reason":"r"}""",
            """{"executionInstanceId":"mw-0","executionId":"exec-7","reason":"r","attestedStopped":"true"}""",
            """{"executionInstanceId":"mw-0","executionId":"exec-7","reason":"r","attestedStopped":1}""",
            """{"executionInstanceId":"mw-0","executionId":"exec-7","reason":"r","attestedStopped":null}""",
        ).forEach { body ->
            val reply = close("operator", body = body)
            assertEquals(400, reply.statusCode.value(), body)
            assertEquals(ExecutionControlController.CLOSE_BAD_REQUEST, reply.rejection().error, body)
        }
        listOf(
            """{"executionInstanceId":"mw-0","executionId":"exec-7","attestedStopped":false}""",
            """{"executionInstanceId":"mw-0","executionId":"exec-7","reason":"  ","attestedStopped":true}""",
        ).forEach { body ->
            val reply = close("operator", body = body)
            assertEquals(400, reply.statusCode.value(), body)
            assertEquals(ExecutionControlController.REASON_REQUIRED, reply.rejection().error, body)
        }
        assertEquals(emptyList(), host.calls)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `종결은 승인자가 행위자이고 CLOSED 는 성공 그 밖의 결과와 호스트 4xx 는 거부이며 응답이 없으면 closure 의 requestId 로 대조하고 못 읽으면 확인하지 못한다`() {
        val closed = assertIs<ExecutionCloseOutcome>(
            close("operator", body = """{"executionInstanceId":"mw-0","executionId":"exec-7","reason":" 현장에서 정지 확인 ","approverId":"mallory","attestedStopped":true}""").body,
        )
        assertEquals(listOf(OperationResult.SUCCEEDED, "CLOSED"), listOf(closed.result, closed.outcome))
        assertEquals(
            mapOf(
                "op" to "close", "jobOrderId" to "JO-1", "approverId" to "kim", "reason" to "현장에서 정지 확인",
                "executionInstanceId" to "mw-0", "executionId" to "exec-7", "attestedStopped" to true, "requestId" to closed.requestId,
            ),
            host.writes().single(),
        )
        val row = log.list().single()
        assertEquals(listOf("kim", "JO-1", "현장에서 정지 확인"), listOf(row.user, row.target, row.reason))
        assertEquals(
            json.readTree("""{"op":"CLOSE_UNRESTORED","jobOrderId":"JO-1","approverId":"kim","executionInstanceId":"mw-0","executionId":"exec-7","attestedStopped":true}"""),
            json.readTree(row.request),
        )

        listOf("ALREADY_CLOSED", "NOT_CLOSABLE", "TASK_LIVE", "STOP_UNCONFIRMED", "TARGET_CHANGED", "NOT_FOUND", "REFUSED").forEach { result ->
            host.closeAnswer = HostWrite.Answered(200, closeBody(result))
            val refused = assertIs<ExecutionCloseOutcome>(close("operator").body)
            assertEquals(listOf(OperationResult.REJECTED, result), listOf(refused.result, refused.outcome))
            assertEquals(result, refused.answer!!["result"].asText())
        }
        listOf(409 to "REQUEST_ID_REUSED", 400 to "BAD_REQUEST").forEach { (status, error) ->
            host.closeAnswer = HostWrite.Answered(status, """{"error":"$error","detail":"x"}""")
            val rejected = assertIs<ExecutionCloseOutcome>(close("operator").body)
            assertEquals(listOf(OperationResult.REJECTED, status, error), listOf(rejected.result, rejected.rejection!!.status, rejected.rejection!!.error))
            assertNull(rejected.outcome)
        }
        assertEquals(10, log.list().size)
        assertEquals(mapOf(OperationResult.SUCCEEDED to 1, OperationResult.REJECTED to 9), log.list().groupingBy { it.result }.eachCount())

        // 응답이 없으면 종결 조회의 requestId 가 이 요청의 것인지로 대조한다.
        host.closeAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        host.onClose = { id -> host.closureAnswer = HostCall.Ok(json.readTree(closureBody(id.toString()))) }
        val applied = assertIs<ExecutionCloseOutcome>(close("operator").body)
        assertEquals(listOf(OperationResult.NO_RESPONSE, OperationResult.CONFIRMED_APPLIED), listOf(applied.result, applied.confirmation))
        val observed = log.list().single { it.result == OperationResult.CONFIRMED_APPLIED }
        assertEquals(listOf(applied.requestId, "JO-1"), listOf(observed.requestId, observed.target))
        assertEquals(json.readTree(closureBody(applied.requestId.toString())), json.readTree(observed.targetResponse)["observed"])

        // 앞선 종결(다른 요청 id)만 있거나 종결이 없으면 이 요청의 반영이 아니다.
        host.onClose = { host.closureAnswer = HostCall.Ok(json.readTree(closureBody(UUID.randomUUID().toString()))) }
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, assertIs<ExecutionCloseOutcome>(close("operator").body).confirmation)
        host.onClose = { host.closureAnswer = HostCall.Ok(null) }
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, assertIs<ExecutionCloseOutcome>(close("operator").body).confirmation)

        // 못 읽으면 확인하지 못한다. 확인 행은 붙지 않는다.
        host.onClose = { host.closureAnswer = HostCall.Silent("응답 없음: ConnectException") }
        val unknown = assertIs<ExecutionCloseOutcome>(close("operator").body)
        assertEquals(listOf(OperationResult.NO_RESPONSE, null), listOf(unknown.result, unknown.confirmation))
        // 재조회는 그 종결 요청의 id 를 싣는다(호스트가 그 요청을 처리 중이면 모름으로 답하게).
        val rechecks = host.calls.filter { it["op"] == "closure" }
        assertEquals(4, rechecks.size)
        assertEquals(listOf(applied.requestId, unknown.requestId), listOf(rechecks.first()["requestId"], rechecks.last()["requestId"]))
        assertTrue(rechecks.all { it["jobOrderId"] == "JO-1" })
        assertEquals(
            listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.CONFIRMED_NOT_APPLIED, OperationResult.CONFIRMED_NOT_APPLIED),
            log.list().map { it.result }.filter { it != OperationResult.NO_RESPONSE && it != OperationResult.SUCCEEDED && it != OperationResult.REJECTED }.sortedBy { it.name },
        )
        assertEquals(4, log.list().count { it.result == OperationResult.NO_RESPONSE })

        // 호스트가 그 요청을 아직 처리 중이면(재조회가 모름으로 답한다) 반영 안 됨으로 확인하지 않는다.
        host.onClose = { host.closureAnswer = HostCall.Silent("호스트가 그 요청을 아직 처리 중이다") }
        val inProgress = assertIs<ExecutionCloseOutcome>(close("operator").body)
        assertEquals(listOf(OperationResult.NO_RESPONSE, null), listOf(inProgress.result, inProgress.confirmation))
        assertEquals(0, log.list().count { it.result == OperationResult.CONFIRMED_NOT_APPLIED && it.requestId == inProgress.requestId })
    }

    private fun releasedExecutions(instanceId: String, releases: String): HostCall<JsonNode> = HostCall.Ok(
        json.readTree(
            """{"instanceId":"$instanceId","pumpedAt":null,"executions":[{"executionId":"exec-1","jobOrderId":"JO-1","robotId":"quadruped-01",""" +
                """"physicalState":"RUNNING","blockedFaults":[],"releases":$releases}],"restore":null,"journalBlockedExecutionIds":[]}""",
        ),
    )

    private fun executions(instanceId: String, cancel: String): HostCall<JsonNode> = HostCall.Ok(
        json.readTree(
            """{"instanceId":"$instanceId","pumpedAt":null,"executions":[{"executionId":"exec-1","jobOrderId":"JO-1","robotId":"humanoid-01",""" +
                """"physicalState":"CANCELING","cancel":$cancel}],"restore":null,"journalBlockedExecutionIds":[]}""",
        ),
    )

    private companion object {
        val T0: Instant = Instant.parse("2026-10-13T09:00:00Z")

        const val HOLD =
            """{"markerId":7,"robotId":"humanoid-01","jobOrderId":"JO-1","executionId":"exec-1","instanceId":"mw-1","unitId":"RACK-204.S02","objectRef":"C-1","recordedAt":"2026-10-13T08:59:00Z"}"""

        const val CONFLICT = "8c5d3f0e-2b1a-4c7d-9e6f-0a1b2c3d4e5f"

        const val OBSERVATION =
            """{"observedKind":"HOLDING","observedObjectRef":"C-1","observedDetail":null,"snapshotSession":"s-1","snapshotSequence":"12",""" +
                """"snapshotOccurredAt":"2026-10-13T09:00:00Z","hostReadStartedAt":"2026-10-13T09:00:00Z","hostReadReceivedAt":"2026-10-13T09:00:00.100Z"}"""

        fun clearing(requestId: UUID) =
            """{"approverId":"kim","approverKind":"PERSON","reason":"r","clearedAt":"2026-10-13T09:00:01Z","requestId":"$requestId",""" +
                """"observation":$OBSERVATION,"conflictRequestId":null,"acknowledgedMismatch":false}"""

        val CLEARED = """{"hold":$HOLD,"clearing":${clearing(UUID.fromString(CONFLICT))},"requestId":"$CONFLICT"}"""

        const val MISMATCH =
            """{"error":"RESIDUAL_HOLD_OBSERVATION_MISMATCH","detail":"발신자는 지금 든 채라고 말한다","robotId":"humanoid-01","markerId":7,""" +
                """"conflictRequestId":"$CONFLICT","observation":$OBSERVATION}"""

        const val SAW = """[{"errorType":"LOCALIZATION_LOST","references":[]}]"""

        const val CLOSE_REQUEST = """{"executionInstanceId":"mw-0","executionId":"exec-7","reason":"현장에서 정지 확인","attestedStopped":false}"""

        fun closeBody(result: String) =
            """{"result":"$result","jobOrderId":"JO-1","basis":${if (result == "CLOSED") "\"SNAPSHOT\"" else "null"},"responseId":null,"detail":null,"requestId":"3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10"}"""

        fun closureBody(requestId: String) =
            """{"requestId":"$requestId","approverId":"kim","reason":"r","basis":"SNAPSHOT","attestedStopped":false,"closedInstanceId":"mw-0","closedExecutionId":"exec-7",""" +
                """"restoreResult":"GAVE_UP","restoreReason":"x","responseId":"close-1","recordedAt":"t"}"""

        const val RELEASE_REQUEST = """{"instanceId":"mw-1","reason":"현장에서 기체 상태 확인","sawFaults":$SAW}"""

        fun releaseBody(result: String) =
            """{"result":"$result","executionId":"exec-1","physicalState":"RUNNING","detail":null,"requestId":null}"""

        fun cancelBody(result: String) =
            """{"result":"$result","executionId":"exec-1","physicalState":"PARTIAL","detail":null,"requestId":null}"""
    }
}
