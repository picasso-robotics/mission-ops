package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * S4b 완료 판정의 통합 쪽(S4b 스펙 §3·§10). 두 기체를 시운전 완료로 만든 뒤 운영 서비스 REST 로 작업 지시를 내고, 같은 DB·현장을
 * 둔 채 실행 호스트만 다시 띄운다([E2eStack.restartHost]). 다시 지은 실행, 새 명령 없음, 송신 기록의 재기동 중복, 이전 인스턴스
 * 인시던트를 운영 서비스 REST 와 현장 mimic 의 태스크 로그로 본다.
 *
 * 단계와 스펙 §3 의 여섯: 단계 1 = 1, 단계 2 = 2, 단계 3 = 5, 단계 4 = 3, 단계 5 = 4, 단계 6 = 6. 단계 4~6 은 같은 보류 실행 하나를
 * 이어 쓴다.
 *
 * ## 판단 기록(HA 단계 0)
 *
 * 실행 호스트가 판단 기록(picasso `JudgmentRecord`)을 영속하고 다시 지을 때 `resume` 에 넘긴다(picasso 한계 §15.221 · 판단 기록 영속).
 * 그래서 앞선 단위의 판정과 근거 등급, 운영자 보류, 설비 대기의 기한 시작이 재기동을 넘어온다. S4b 스펙 T8 의 «새 인스턴스의 값» 은
 * 이 기록이 없을 때의 거동이고, 단계 1·4·5 가 그 차이를 본다. 넘어온 운영자 보류는 새 인스턴스에서 인시던트로 다시 열린다(picasso
 * `resume` 의 8). 다시 연 것은 인시던트의 `openingKind`(`REOPENED`)로 읽고, 원래 보류와는 같은 `incidentKey` 로 잇는다(picasso §15.322,
 * 판단 기록 판 5). 화면과 같이 그 인시던트 상세의 `instanceId` 로 판단해 푼다.
 *
 * 순서가 있고 새 스택에서 돈다. `pick_place` 를 가진 기체가 humanoid-01 하나라 앞 실행이 끝나야 다음 작업 지시가 배정된다. 셀 대역은
 * 슬롯을 비우지 않으므로 작업 지시마다 슬롯을 따로 쓴다(단계 1 이 S01·S02, 단계 3 이 S03, 단계 4 가 S04).
 *
 * ## 임무 버전
 *
 * 버전 1 은 `ARRIVAL_WAIT`(기한 120초, ABORTED), 버전 2 는 `DATA_V1`(대기 없음), 버전 3 은 `ARRIVAL_WAIT_HOLD`(기한 20초, 운영자
 * 보류)다. 단계 3 은 버전 1 로 받은 뒤 버전 2 를 활성화하고 다시 띄워 대기 단위가 있는 버전 1 로 다시 서는 것을 본다.
 *
 * ## 시계
 *
 * 가상 시계만 민다([ExecutionDriver]). 다시 지은 실행의 단위는 첫 pump 들이 기체를 다시 관측한 뒤 끝난 대로·도는 대로 서므로
 * 시계를 밀지 않고 실제 시간으로 상태를 폴링한다([awaitUnits], S4b JSON 계약 H9). 설비 대기의 기한도 호스트 시계(현장 가상 시계)라
 * 실제 시간으로 기다리지 않는다. 연결 오래됨처럼 실제 수신 시각으로 판정하는 단계는 없다.
 *
 * ## 실패 모드
 *
 * humanoid-01 의 `pick_place` 는 넷(S01~S04)이라 시드 0 의 자연 실패(열두 번째)에 이르지 않는다. 다시 보낸 같은 태스크 id 의
 * `StartTask` 는 기존 태스크를 돌려주므로 추첨을 더 하지 않는다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RestartRecoveryTest {

    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver
        private val json = ObjectMapper()

        private const val WORK_MASTER = "PrepareSequencedRack"
        private const val MISSIONS = "/api/missions/$WORK_MASTER"
        private const val MATERIAL = "ENGINE-COVER-A"
        private const val PRESENTATION = "SEQ-IN-02.BIN-A"
        private const val WAIT_UNIT = "rack-arrival"
        private const val S01 = "RACK-204.S01"
        private const val S02 = "RACK-204.S02"
        private const val S03 = "RACK-204.S03"
        private const val S04 = "RACK-204.S04"

        /** `ARRIVAL_WAIT_HOLD` 의 대기 기한. */
        private val DEADLINE: Duration = Duration.ofSeconds(20)

        /** 다시 지은 실행의 단위가 기체를 다시 관측해 서기를 기다리는 상한(실제 시간). 호스트 시험에서는 5초 안이었다. */
        private val REOBSERVE_WAIT: Duration = Duration.ofSeconds(10)

        /** 단계 4~6 이 이어 쓰는 보류 실행. 실행 id 는 인스턴스마다 바뀌므로 작업 지시 id 로 찾는다. */
        private var heldOrder = ""

        /** 단계 5 가 재작업을 판단한 인스턴스와 그 인시던트. 단계 6 이 이전 인스턴스로 쓴다. */
        private var reworkInstance = ""
        private var reworkIncident = ""

        /** 단계 4 의 세 인스턴스(받은 것, 첫 재기동, 둘째 재기동). 단계 5 가 earlier 의 주인을 댄다. */
        private var holdInstances = listOf<String>()

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start()
            driver = ExecutionDriver(stack)
            Commissioned.complete(stack)
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }

        private fun rack(vararg slots: String): String =
            """{"workMasterId":"$WORK_MASTER","slots":[${slots.joinToString(",") { "\"$it\"" }}],""" +
                """"material":"$MATERIAL","presentation":"$PRESENTATION"}"""
    }

    @Test
    @Order(1)
    fun `단계 1 도는 실행이 있을 때 다시 띄우면 같은 작업 지시가 새 인스턴스에서 끝난 단위는 끝난 대로 도는 단위는 도는 대로 다시 서고 판단 기록으로 앞선 단위의 근거 등급이 넘어와 도달 근거 등급이 E2 이다`() {
        activate("ARRIVAL_WAIT", "랙 도착 대기 도입", 1)
        signal("true")
        val (jobOrderId, executionId) = submit(rack(S01, S02))
        pushUntil(executionId, ExecutionDriver.STEP) { it[S01] == "DONE" && it[S02] == "RUNNING" }
        val before = driver.execution(executionId)
        assertEquals(listOf(Triple(WAIT_UNIT, "DONE", "E2"), Triple(S01, "DONE", "E2"), Triple(S02, "RUNNING", "E0")), units(before), "$before")
        val previous = driver.executions()["instanceId"].asText()

        val instance = restart(previous)
        val view = driver.executions()
        val row = view["restore"]["rows"].single()
        assertEquals(
            listOf(jobOrderId, HUMANOID, previous, executionId, "RESTORED"),
            listOf("jobOrderId", "robotId", "previousInstanceId", "previousExecutionId", "result").map { row[it].asText() },
            "$row",
        )
        assertTrue(row["reason"].isNull, "$row")
        val restored = view["executions"].single()
        assertEquals(jobOrderId to row["executionId"].asText(), restored["jobOrderId"].asText() to restored["executionId"].asText(), "$view")
        assertEquals(json.readTree("""{"instanceId":"$previous","executionId":"$executionId"}"""), restored["restoredFrom"], "$restored")
        assertEquals(1, restored["missionVersion"].asInt(), "$restored")
        val newExecution = restored["executionId"].asText()

        // 앞서 통과한 대기와 마지막 로봇 단위 앞의 로봇 단위는 이 인스턴스가 관측하지 않았으나, 판단 기록의 판정(E2)을 다시 판정하지
        // 않고 그대로 옮긴다. 기록이 없으면 E0 로 선다(S4b 스펙 T1).
        val reobserved = awaitUnits(newExecution, listOf(WAIT_UNIT to "DONE", S01 to "DONE", S02 to "RUNNING"))
        assertEquals(listOf(Triple(WAIT_UNIT, "DONE", "E2"), Triple(S01, "DONE", "E2"), Triple(S02, "RUNNING", "E0")), units(reobserved))

        val done = driver.drive(newExecution)
        assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), "$done")
        assertEquals(listOf(Triple(WAIT_UNIT, "DONE", "E2"), Triple(S01, "DONE", "E2"), Triple(S02, "DONE", "E2")), units(done), "$done")
        assertEquals("E2" to "E2", done["jobResponse"]["reachedEvidence"].asText() to done["jobResponse"]["requiredEvidence"].asText(), "$done")

        // 재기동 전에는 응답이 없었으므로 다시 지은 실행의 완료 응답이 그 작업 지시의 첫 송신이다.
        val log = responses(jobOrderId)
        assertEquals(listOf(Triple(instance, "PHYSICALLY_DONE", "SENT")), log.map { Triple(it["instanceId"].asText(), it["physicalState"].asText(), it["disposition"].asText()) }, "$log")
        assertEquals("E2", log.single()["reachedEvidence"].asText(), "$log")
    }

    @Test
    @Order(2)
    fun `단계 2 도는 InspectAsset 을 다시 띄워도 시계를 밀기 전 기체의 태스크 로그가 그대로이고 끝까지 가도 태스크마다 ACCEPTED 가 한 번뿐이다`() {
        val (jobOrderId, executionId) = submit("""{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"},{"id":"T2","location":"dock-3"}]}""")
        pushUntil(executionId, Duration.ofSeconds(1)) { it["T1.travel"] == "DONE" && it["T1"] == "RUNNING" }
        val tasksBefore = stack.site.taskHistory(HUMANOID).filterKeys { it.startsWith("$jobOrderId#") }
        assertEquals(setOf("$jobOrderId#T1.travel", "$jobOrderId#T1"), tasksBefore.keys, "$tasksBefore")
        val clock = stack.site.now()

        restart(driver.executions()["instanceId"].asText())
        val restored = driver.executions()["executions"].single()
        val reobserved = awaitUnits(
            restored["executionId"].asText(),
            listOf("T1.travel" to "DONE", "T1" to "RUNNING", "T2.travel" to "PENDING", "T2" to "PENDING"),
        )
        // 다시 지은 실행은 같은 태스크 id·리비전으로 StartTask 를 다시 보냈다. 기체는 기존 태스크를 돌려주고 새 태스크를 만들지 않는다.
        // mimic 은 RPC 마다 접수한 태스크를 집어 들므로(ACCEPTED → RUNNING) 시계를 밀지 않아도 로그가 이어질 수는 있다. 그래서
        // 태스크 집합이 같고 재기동 전 로그가 그대로 앞에 있으며 ACCEPTED 가 다시 서지 않았음을 본다.
        assertEquals(clock, stack.site.now(), "시계를 밀지 않았다")
        val tasksRestored = stack.site.taskHistory(HUMANOID).filterKeys { it.startsWith("$jobOrderId#") }
        assertEquals(tasksBefore.keys, tasksRestored.keys, "$reobserved")
        tasksBefore.forEach { (taskId, history) ->
            val now = tasksRestored.getValue(taskId)
            assertEquals(history, now.take(history.size), "$taskId: $now")
            assertEquals(1, now.count { it == "ACCEPTED" }, "$taskId: $now")
        }

        val done = driver.drive(restored["executionId"].asText())
        assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), "$done")
        val tasksAfter = stack.site.taskHistory(HUMANOID)
        assertEquals(
            listOf("T1.travel", "T1", "T2.travel", "T2").map { "$jobOrderId#$it" }.toSet(),
            tasksAfter.keys.filter { it.startsWith("$jobOrderId#") }.toSet(),
            "$tasksAfter",
        )
        // 단계 1 의 태스크까지 이 기체의 모든 태스크가 ACCEPTED 를 한 번만 지났다.
        tasksAfter.forEach { (taskId, history) -> assertEquals(1, history.count { it == "ACCEPTED" }, "$taskId: $history") }
        assertTrue(tasksAfter.keys.none { "@r" in it }, "$tasksAfter")
    }

    @Test
    @Order(3)
    fun `단계 3 작업 지시를 받은 뒤 다른 임무 버전을 활성화하고 다시 띄우면 받은 때의 임무 버전으로 다시 서고 그 버전으로 끝난다`() {
        val (jobOrderId, executionId) = submit(rack(S03))
        assertEquals(1, driver.execution(executionId)["missionVersion"].asInt())
        activate("DATA_V1", "대기 없는 정의로 되돌림", 2)
        assertEquals("DATA" to 2, stack.get(MISSIONS)["active"].let { it["source"].asText() to it["version"].asInt() })

        restart(driver.executions()["instanceId"].asText())
        val view = driver.executions()
        assertEquals(jobOrderId to "RESTORED", view["restore"]["rows"].single().let { it["jobOrderId"].asText() to it["result"].asText() }, "$view")
        val restored = view["executions"].single()
        // 지금 활성은 대기 없는 버전 2 지만 다시 지은 실행은 일지의 버전 1 이라 대기 단위가 있다.
        assertEquals(1, restored["missionVersion"].asInt(), "$restored")
        assertEquals(listOf(WAIT_UNIT, S03), restored["units"].map { it["unitId"].asText() }, "$restored")

        val done = driver.drive(restored["executionId"].asText())
        assertEquals("PHYSICALLY_DONE" to 1, done["physicalState"].asText() to done["missionVersion"].asInt(), "$done")
        assertEquals(listOf(WAIT_UNIT, S03).map { it to "DONE" }, done["units"].map { it["unitId"].asText() to it["state"].asText() }, "$done")
    }

    @Test
    @Order(4)
    fun `단계 4 운영자 보류에 선 실행을 두 번 다시 띄우면 기한을 다시 기다리지 않고 보류 그대로 서고 새 인스턴스가 다시 연 인시던트로 판단하며 그 보류 응답이 매번 재기동 중복으로 적히고 송신은 한 번뿐이며 같은 인스턴스에서 재작업 뒤 다시 선 보류는 송신이다`() {
        activate("ARRIVAL_WAIT_HOLD", "운영자 보류 대기 도입", 3)
        signal("false")
        val (jobOrderId, executionId) = submit(rack(S04))
        heldOrder = jobOrderId
        driver.push(ExecutionDriver.STEP)
        pushUntilHeld(executionId)
        val first = driver.executions()["instanceId"].asText()
        assertEquals(listOf(Triple(first, "OPERATOR_HOLD", "SENT")), dispositions(jobOrderId))

        val second = restart(first)
        assertEquals(jobOrderId to "RESTORED", driver.executions()["restore"]["rows"].single().let { it["jobOrderId"].asText() to it["result"].asText() })
        rehold(second)
        assertEquals(
            listOf(Triple(second, "OPERATOR_HOLD", "RESTART_DUPLICATE"), Triple(first, "OPERATOR_HOLD", "SENT")),
            dispositions(jobOrderId),
        )

        val third = restart(second)
        holdInstances = listOf(first, second, third)
        val row = driver.executions()["restore"]["rows"].single()
        assertEquals(listOf(second, "RESTORED"), listOf(row["previousInstanceId"].asText(), row["result"].asText()), "$row")
        rehold(third)
        val log = dispositions(jobOrderId)
        assertEquals(
            listOf(Triple(third, "OPERATOR_HOLD", "RESTART_DUPLICATE"), Triple(second, "OPERATOR_HOLD", "RESTART_DUPLICATE"), Triple(first, "OPERATOR_HOLD", "SENT")),
            log,
        )
        assertEquals(1, log.count { it.third == "SENT" }, "$log")
        // 재기동 중복은 송신한 행과 내용 키가 같다.
        val rows = responses(jobOrderId)
        val keys = listOf("version", "physicalState", "requiredEvidence", "reachedEvidence", "completedUnits", "unverifiedUnits", "incompleteUnits", "inDoubtUnits", "operatorRequired", "residualHold", "blockedBy")
        assertEquals(1, rows.map { r -> keys.map { r[it] } }.distinct().size, "$rows")

        // 같은 인스턴스에서 재작업 뒤 다시 선 보류는 내용이 같아도 새로 일어난 일이라 송신한다. 단계 5 가 이 보류를 재작업하고 다시
        // 띄운다. 넘어온 보류는 이 인스턴스가 다시 연 인시던트로 화면과 같이(상세의 instanceId·실행·단위) 판단한다.
        val reopened = reopenedHold(third)
        val reworked = resolve(reopened["executionId"].asText(), "REWORK", "랙 위치 확인 뒤 재작업", reopened["instanceId"].asText())
        assertEquals("SUCCEEDED" to "Resolved", reworked.body!!["result"].asText() to reworked.body["outcome"].asText(), "${reworked.body}")
        // 판단은 다시 연 인시던트에 붙는다. 그 인시던트는 더 보류 중이 아니다.
        assertEquals(reopened["incidentId"].asText(), reworked.body["answer"]["incidentId"].asText(), "${reworked.body}")
        val decided = stack.get("/api/incidents/${reopened["incidentId"].asText()}")
        assertEquals("REWORK" to false, decided["resolution"]["decision"].asText() to decided["held"].asBoolean(), "$decided")
        // 판단으로 보류가 걷히면 그 자리에서 응답 하나가 나간다(picasso `resolve`). 시계를 밀기 전이라 대기는 기한 전이다.
        val cleared = waitLogged(third, 2)
        assertEquals(Triple(third, "RUNNING", "SENT"), cleared.first(), "$cleared")
        assertEquals(false, responses(jobOrderId).first()["operatorRequired"].asBoolean(), "${responses(jobOrderId)}")
        pushUntilHeld(heldExecution())
        val again = waitLogged(third, 3)
        assertEquals(listOf(Triple(third, "OPERATOR_HOLD", "SENT"), Triple(third, "RUNNING", "SENT")), again.take(2), "$again")
        assertEquals(3, again.count { it.third == "SENT" }, "$again")
    }

    @Test
    @Order(5)
    fun `단계 5 설비 대기 보류를 재작업으로 판단한 뒤 다시 띄우면 대기가 재작업 때 시작한 기한을 이어 재고 재작업 판단 뒤 응답과 내용이 다른 그 보류 응답은 송신이며 옛 인시던트와 그 판단은 earlier 에만 있다`() {
        val executionId = executionOf(heldOrder)
        val hold = incidents()["incidents"].single { it["executionId"].asText() == executionId && it["held"].asBoolean() }
        reworkIncident = hold["incidentId"].asText()
        reworkInstance = stack.get("/api/incidents/$reworkIncident")["instanceId"].asText()
        val reply = resolve(executionId, "REWORK", "랙 재배치 뒤 재작업", reworkInstance)
        assertEquals(listOf("SUCCEEDED", "Resolved"), listOf(reply.body!!["result"].asText(), reply.body["outcome"].asText()), "${reply.body}")
        // 시계를 밀기 전에 재작업한 대기가 다시 기다리기 시작하게 둔다. 그 시각이 판단 기록의 대기 시작이다.
        awaitUnits(executionId, listOf(WAIT_UNIT to "RUNNING", S04 to "PENDING"))
        // 판단으로 보류가 걷힌 응답을 이 인스턴스가 송신한다. 상위가 재기동 전에 마지막으로 받는 것이 이것이다.
        assertEquals(Triple(reworkInstance, "RUNNING", "SENT"), waitLogged(reworkInstance, 4).first())
        // 재작업으로 다시 선 대기가 도는 중에 기한 전까지만 민다. 이 pump 들이 판단 행을 사본에 적는다.
        driver.push(Duration.ofSeconds(10))
        assertEquals("RUNNING", unitState(heldOrder, WAIT_UNIT), "${driver.executions()}")

        val instance = restart(reworkInstance)
        val restartedAt = stack.site.now()
        awaitUnits(executionOf(heldOrder), listOf(WAIT_UNIT to "RUNNING", S04 to "PENDING"))
        // 재작업 뒤 15초, 재기동 뒤 5초. 기한은 판단 기록의 대기 시작 시각(재작업 때)부터 이어 잰다.
        driver.push(Duration.ofSeconds(5))
        assertEquals("RUNNING", unitState(heldOrder, WAIT_UNIT), "${driver.executions()}")
        val held = pushUntilHeld(executionOf(heldOrder))
        assertTrue(Duration.between(restartedAt, stack.site.now()) < DEADLINE, "기한을 재기동 때부터 다시 쟀다: ${stack.site.now()}")
        assertEquals("OPERATOR_HOLD", held["physicalState"].asText(), "$held")
        // 상위가 마지막으로 받은 것은 재작업 판단 뒤 응답(`RUNNING`)이다. 내용 키가 달라 새 인스턴스의 첫 보류 응답이라도 송신한다.
        assertEquals(
            listOf(Triple(instance, "OPERATOR_HOLD", "SENT"), Triple(reworkInstance, "RUNNING", "SENT")),
            waitLogged(instance, 1).take(2),
        )

        val list = incidents()
        assertEquals(instance, list["instanceId"].asText(), "$list")
        // 지금 인스턴스에는 새 보류 하나뿐이고 판단 든 인시던트가 없다. 재기동 때 대기는 돌고 있었으므로(판정 없음) 다시 연 인시던트가
        // 아니라 기한이 다시 지나 새로 선 보류다.
        val live = list["incidents"].filter { it["jobOrderId"].asText() == heldOrder }
        assertEquals(1, live.size, "$list")
        assertEquals(listOf(true, true), listOf(live.single()["unresolved"].asBoolean(), live.single()["held"].asBoolean()), "$list")
        assertTrue(list["incidents"].all { it["resolution"].isNull }, "$list")
        val fresh = stack.get("/api/incidents/${live.single()["incidentId"].asText()}")
        assertTrue(!reopened(fresh), "새 보류가 다시 연 것으로 보인다: $fresh")
        // 앞 세 인스턴스의 인시던트 넷이 earlier 에 최근 것부터 있다: 셋째의 재작업 뒤 기한 보류(재작업 판단), 셋째가 다시 연 보류(단계 4 의
        // 재작업 판단), 둘째가 다시 연 보류(판단 없음), 첫째의 기한 보류(판단 없음). 다시 연 둘은 REOPENED 이고 첫째의 기한 보류와 같은
        // 열쇠·처음 선 시각이다. 셋째의 재작업 뒤 보류와 지금의 새 보류는 각각 새 사건이라 새 열쇠다.
        val (first, second, third) = holdInstances
        val earlier = list["earlier"].filter { it["jobOrderId"].asText() == heldOrder }
        assertEquals(listOf(third, third, second, first), earlier.map { it["instanceId"].asText() }, "$list")
        assertEquals(reworkInstance to reworkIncident, earlier.first()["instanceId"].asText() to earlier.first()["incidentId"].asText(), "$list")
        val resolution = earlier.first()["resolution"]
        assertEquals(listOf("REWORK", "kim", "PERSON"), listOf(resolution["decision"], resolution["decidedBy"]["id"], resolution["decidedBy"]["kind"]).map { it.asText() }, "$list")
        assertEquals(listOf("REWORK", "REWORK", null, null), earlier.map { it["resolution"].takeIf { r -> !r.isNull }?.get("decision")?.asText() }, "$list")
        assertTrue(earlier.all { !it["held"].asBoolean() && it["failureClass"].asText() == "SIGNAL_DEADLINE" }, "$list")
        val details = earlier.map { row -> stack.get("/api/incidents/${row["incidentId"].asText()}?instanceId=${row["instanceId"].asText()}") }
        assertEquals(listOf(false, true, true, false), details.map(::reopened), "$list")
        assertEquals(listOf("NEW", "REOPENED", "REOPENED", "NEW"), earlier.map { it["openingKind"].asText() }, "$list")
        val keys = earlier.map { it["incidentKey"].asText() }
        assertEquals(List(3) { keys[3] }, keys.drop(1), "$list")
        assertEquals(List(3) { earlier[3]["firstOpenedAt"].asText() }, earlier.drop(1).map { it["firstOpenedAt"].asText() }, "$list")
        assertEquals(3, (keys.toSet() + fresh["incidentKey"].asText()).size, "$list $fresh")
        assertTrue(list["earlier"].none { it["instanceId"].asText() == instance }, "$list")
    }

    @Test
    @Order(6)
    fun `단계 6 재기동 뒤 이전 인스턴스의 인시던트와 판단이 보이고 이전 인스턴스를 실은 판단은 INSTANCE_MISMATCH 로 거부된다`() {
        val copy = stack.get("/api/incidents/$reworkIncident?instanceId=$reworkInstance")
        assertEquals(reworkInstance to heldOrder, copy["instanceId"].asText() to copy["jobOrderId"].asText(), "$copy")
        assertEquals("REWORK" to false, copy["resolution"]["decision"].asText() to copy["held"].asBoolean(), "$copy")
        assertTrue(copy["unitState"].isNull, "$copy")

        val executionId = executionOf(heldOrder)
        val current = incidents()["instanceId"].asText()
        assertNotEquals(reworkInstance, current)
        val stale = resolve(executionId, "CONFIRM_DONE", "이전 상세로 판단", reworkInstance)
        assertEquals(200, stale.status, "${stale.body}")
        val body = stale.body!!
        assertEquals("REJECTED", body["result"].asText(), "$body")
        assertTrue(body["outcome"].isNull && body["answer"].isNull && body["confirmation"].isNull, "$body")
        assertEquals(409 to "INSTANCE_MISMATCH", body["rejection"]["status"].asInt() to body["rejection"]["error"].asText(), "$body")
        assertTrue(reworkInstance in body["rejection"]["detail"].asText() && current in body["rejection"]["detail"].asText(), "$body")
        val logged = operations("RESOLVE_OPERATOR_HOLD").first()
        assertEquals("REJECTED" to "이전 상세로 판단", logged["result"].asText() to logged["reason"].asText(), "$logged")
        assertEquals(reworkInstance, json.readTree(logged["request"].asText())["instanceId"].asText(), "$logged")
        val target = json.readTree(logged["targetResponse"].asText())
        assertEquals(409 to "INSTANCE_MISMATCH", target["status"].asInt() to target["body"]["error"].asText(), "$logged")
        // 거부된 판단은 보류를 바꾸지 않는다.
        assertEquals("OPERATOR_HOLD", unitState(heldOrder, WAIT_UNIT))

        // 지금 인스턴스로 판단하면 받고 끝까지 간다.
        val confirmed = resolve(executionId, "CONFIRM_DONE", "현장 육안으로 랙 도착 확인", current)
        assertEquals("SUCCEEDED" to "Resolved", confirmed.body!!["result"].asText() to confirmed.body["outcome"].asText(), "${confirmed.body}")
        val done = driver.drive(executionId)
        assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), "$done")
        assertEquals(listOf(Triple(WAIT_UNIT, "DONE", "E0"), Triple(S04, "DONE", "E2")), units(done), "$done")
    }

    /** 실행 호스트를 다시 띄우고 운영 서비스가 새 인스턴스를 보는지 확인한 뒤 새 인스턴스 id 를 낸다. */
    private fun restart(previous: String): String {
        stack.restartHost()
        val instance = driver.executions()["instanceId"].asText()
        assertNotEquals(previous, instance, "재기동 뒤 인스턴스가 같다")
        return instance
    }

    /**
     * 다시 지은 보류 실행이 시계를 밀지 않고 보류 그대로 서고(판단 기록의 운영자 보류 판정, 기한을 다시 기다리지 않음), 그 보류 응답이
     * [instance] 의 행으로 적히기를 본다.
     */
    private fun rehold(instance: String) {
        val executionId = executionOf(heldOrder)
        awaitUnits(executionId, listOf(WAIT_UNIT to "OPERATOR_HOLD", S04 to "PENDING"))
        val held = Instant.now().plus(REOBSERVE_WAIT)
        while (driver.execution(executionId)["physicalState"].asText() != "OPERATOR_HOLD") {
            check(Instant.now().isBefore(held)) { "넘어온 보류가 실행의 보류로 서지 않았다: ${driver.execution(executionId)}" }
            Thread.sleep(100)
        }
        // 넘어온 보류는 이 인스턴스의 인시던트로 다시 열려 화면의 판단 대상(보류 중)이다.
        reopenedHold(instance)
        // 송신 기록은 응답이 난 pump 와 같은 잠금 안에서 적힌다(S4b JSON 계약 H9).
        val until = Instant.now().plus(REOBSERVE_WAIT)
        while (responses(heldOrder).none { it["instanceId"].asText() == instance }) {
            check(Instant.now().isBefore(until)) { "$instance 의 보류 응답이 송신 기록에 없다: ${responses(heldOrder)}" }
            Thread.sleep(100)
        }
    }

    /**
     * [instance] 가 다시 연 보류 인시던트의 상세. 화면이 읽는 그대로 지금 인스턴스의 목록에서 보류 중인 그 작업 지시의 인시던트를 고르고,
     * 재기동이 다시 연 것인지(기한이 다시 지나 새로 선 보류가 아님) 본다.
     */
    private fun reopenedHold(instance: String): JsonNode {
        val list = incidents()
        assertEquals(instance, list["instanceId"].asText(), "$list")
        val row = list["incidents"].single { it["jobOrderId"].asText() == heldOrder && it["held"].asBoolean() }
        val detail = stack.get("/api/incidents/${row["incidentId"].asText()}")
        assertEquals(instance to WAIT_UNIT, detail["instanceId"].asText() to detail["unitId"].asText(), "$detail")
        assertTrue(reopened(detail), "다시 연 표시가 없다: $detail")
        return detail
    }

    /**
     * 이 인시던트가 재기동이 다시 연 보류인가. 재개 경로가 직접 적는 `openingKind` 로 읽는다(picasso §15.322). 근거 윈도우의
     * `RESUME_HOLD_REOPENED` 줄로는 가르지 못한다 — 다시 연 뒤 근거 윈도우(앞 30초) 안에 기한으로 새로 선 보류의 윈도우에도 그 줄이 실린다.
     */
    private fun reopened(detail: JsonNode): Boolean = detail["openingKind"].asText() == "REOPENED"

    /** 시계를 밀지 않고 실제 시간으로 실행의 단위가 [expected] (단위 id, 상태) 가 되기를 기다린다. */
    private fun awaitUnits(executionId: String, expected: List<Pair<String, String>>): JsonNode {
        val clock = stack.site.now()
        val until = Instant.now().plus(REOBSERVE_WAIT)
        var seen = driver.execution(executionId)
        while (seen["units"].map { it["unitId"].asText() to it["state"].asText() } != expected) {
            check(Instant.now().isBefore(until)) { "${REOBSERVE_WAIT.seconds}초 안에 $expected 가 되지 않았다: $seen" }
            Thread.sleep(100)
            seen = driver.execution(executionId)
        }
        assertEquals(clock, stack.site.now(), "다시 관측을 기다리는 동안 시계를 밀지 않는다")
        return seen
    }

    /**
     * [done] 이 단위 상태 맵에 참이 될 때까지 [step] 씩 민다. 민 뒤 확인 중(VERIFYING)인 단위가 있으면 실제 시간으로 기다린다
     * ([ExecutionDriver.drive] 와 같은 규칙).
     */
    private fun pushUntil(executionId: String, step: Duration, done: (Map<String, String>) -> Boolean) {
        repeat(ExecutionDriver.ROUNDS * 2) {
            var seen = driver.execution(executionId)
            val until = Instant.now().plus(ExecutionDriver.VERIFY_WAIT)
            while (seen["units"].any { it["state"].asText() == "VERIFYING" } && Instant.now().isBefore(until)) {
                Thread.sleep(100)
                seen = driver.execution(executionId)
            }
            if (done(seen["units"].associate { it["unitId"].asText() to it["state"].asText() })) return
            driver.push(step)
        }
        error("단위가 바라는 상태에 이르지 않았다: ${driver.execution(executionId)}")
    }

    /** 대기 단위가 운영자 보류가 될 때까지 [ExecutionDriver.STEP] 씩 민다. */
    private fun pushUntilHeld(executionId: String): JsonNode {
        repeat(8) {
            val seen = driver.execution(executionId)
            if (seen["units"].single { it["unitId"].asText() == WAIT_UNIT }["state"].asText() == "OPERATOR_HOLD") return seen
            driver.push(ExecutionDriver.STEP)
        }
        error("대기 단위가 운영자 보류가 되지 않았다: ${driver.execution(executionId)}")
    }

    /** 단계 4~6 의 보류 실행의 지금 실행 id. */
    private fun heldExecution(): String = executionOf(heldOrder)

    /** 보류 실행의 송신 기록에 [instance] 의 행이 [count] 개 적히기를 기다리고 (인스턴스, 물리 상태, 처분)을 최근부터 낸다. */
    private fun waitLogged(instance: String, count: Int): List<Triple<String, String, String>> {
        val until = Instant.now().plus(REOBSERVE_WAIT)
        while (dispositions(heldOrder).count { it.first == instance } < count) {
            check(Instant.now().isBefore(until)) { "$instance 의 행이 $count 개가 아니다: ${dispositions(heldOrder)}" }
            Thread.sleep(100)
        }
        return dispositions(heldOrder)
    }

    /** 지금 인스턴스에서 그 작업 지시를 든 실행 id. 다시 지으면 인스턴스의 셈으로 바뀐다. */
    private fun executionOf(jobOrderId: String): String =
        driver.executions()["executions"].single { it["jobOrderId"].asText() == jobOrderId }["executionId"].asText()

    private fun unitState(jobOrderId: String, unitId: String): String =
        driver.execution(executionOf(jobOrderId))["units"].single { it["unitId"].asText() == unitId }["state"].asText()

    /** 실행의 단위를 (단위 id, 상태, 근거 등급)으로 낸다. */
    private fun units(execution: JsonNode): List<Triple<String, String, String>> =
        execution["units"].map { Triple(it["unitId"].asText(), it["state"].asText(), it["reached"].asText()) }

    /** 그 작업 지시의 송신 기록. 최근에 적은 것부터다. */
    private fun responses(jobOrderId: String): List<JsonNode> = stack.get("/api/job-responses?jobOrderId=$jobOrderId")["responses"].toList()

    /** 송신 기록을 (인스턴스, 물리 상태, 처분)으로 낸다. 최근에 적은 것부터다. */
    private fun dispositions(jobOrderId: String): List<Triple<String, String, String>> =
        responses(jobOrderId).map { Triple(it["instanceId"].asText(), it["physicalState"].asText(), it["disposition"].asText()) }

    private fun incidents(): JsonNode = stack.get("/api/incidents")

    /** 조작 기록에서 [op] 의 행만 최신부터 낸다. */
    private fun operations(op: String): List<JsonNode> =
        stack.get("/api/operations").filter { json.readTree(it["request"].asText())["op"].asText() == op }

    /** 운영자 모드로 대기 단위의 보류를 판단한다. 본문의 `instanceId` 는 화면처럼 인시던트 상세의 값이다(S4b JSON 계약 O3). */
    private fun resolve(executionId: String, decision: String, reason: String, instanceId: String): E2eStack.Reply = stack.send(
        "POST", "/api/executions/$executionId/units/$WAIT_UNIT/resolve", "operator",
        body = """{"decision":"$decision","instanceId":"$instanceId","reason":"$reason"}""",
    )

    private fun signal(value: String) {
        val written = stack.send("POST", "/api/cell/signals/rack_present", "operator", body = """{"value":"$value"}""")
        assertEquals("SUCCEEDED", written.body!!["result"].asText(), "${written.body}")
    }

    /** S3b 흐름으로 템플릿을 초안 저장, 모의 실행, 활성화한다. */
    private fun activate(template: String, reason: String, version: Int) {
        val definition = stack.get("/api/missions/templates/$WORK_MASTER")["templates"].single { it["id"].asText() == template }["definition"].asText()
        val saved = engineer("$MISSIONS/drafts", json.createObjectNode().put("definition", definition).toString())
        val draftId = saved["outcome"]["draft"]["draftId"].asLong()
        val mocked = engineer("$MISSIONS/drafts/$draftId/mock-run", "{}")
        assertEquals("PASSED", mocked["outcome"]["result"].asText(), "$mocked")
        val activated = engineer("$MISSIONS/drafts/$draftId/activate", """{"reason":"$reason"}""")
        assertEquals("ACTIVATED" to version, activated["outcome"]["result"].asText() to activated["outcome"]["version"].asInt(), "$activated")
    }

    private fun engineer(path: String, body: String): JsonNode {
        val reply = stack.send("POST", path, "engineer", body = body)
        assertEquals(200, reply.status, "$path → ${reply.body}")
        return reply.body!!
    }

    /** 운영자 모드로 작업 지시를 내고 작업 지시 id 와 실행 id 를 낸다. 두 작업 지시 모두 humanoid-01 이 받는다. */
    private fun submit(form: String): Pair<String, String> {
        val reply = stack.send("POST", "/api/job-orders", "operator", body = form)
        assertEquals(200, reply.status, "${reply.body}")
        val outcome = reply.body!!["outcome"]
        assertEquals("ACCEPTED" to HUMANOID, outcome["result"].asText() to outcome["robotId"].asText(), "${reply.body}")
        return reply.body["jobOrderId"].asText() to outcome["executionId"].asText()
    }
}
