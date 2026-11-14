package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import dev.picasso.ops.e2e.Commissioned.QUADRUPED
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
 * S4a 완료 판정의 통합 쪽(S4a 스펙 §3·§10). 두 기체를 시운전 완료로 만든 뒤 운영 서비스 REST 만으로 장애를 넣고, 인시던트를 읽고,
 * 운영자 보류를 판단한다. 장애 주입은 운영 서비스, 실행 호스트, 현장 `POST /faults` 를 거쳐 mimic 엔진에 닿는다.
 *
 * 순서가 있고 새 스택에서 돈다. 셀 대역은 슬롯을 비우지 않으므로 스킬 실패(단계 1)는 아직 채운 적 없는 슬롯 `S01` 에서 먼저 한다.
 * 그래야 근거가 없어 단위가 FAILED 가 된다. 작업 지시마다 슬롯 하나를 쓰고 S01~S04 를 차례로 쓴다.
 *
 * ## 실패 모드
 *
 * `pick_place` 의 실패 모드는 기체별 시드 0 으로 추첨된다. 강제한 태스크는 완주 추첨을 하지 않으므로, 첫 `pick_place` 를 강제하면
 * 자연 실패는 13번째 `pick_place` 로 밀린다(S4a JSON 계약 §8). 이 시험의 `pick_place` 는 넷이다(S01 강제, S02, S03, S04).
 *
 * ## 시계
 *
 * 시간은 현장 가상 시계를 밀어서 간다([ExecutionDriver]). 예외가 둘이다. 단계 2a 는 시계를 밀지 않는다. 밀면 OFFLINE 동안에도
 * mimic 태스크가 계속 돌아 끝난다. 단계 2b 의 오래됨은 운영 서비스가 registry 의 실제 수신 시각으로 판정하므로 실제 시간 60초
 * 넘게 기다리고, 그동안 [REPORT] 씩 거듭 밀어 quadruped-01 의 생존 보고가 이어지게 한다.
 *
 * ## 보류
 *
 * 단위 수준 보류는 `ARRIVAL_WAIT_HOLD` 버전(기한 20초, 기한 뒤 운영자 보류)으로 만든다. 셀 대역 신호는 스스로 돌아가지 않으므로
 * 단계 5 는 `rack_present` 를 먼저 false 로 되돌린다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FaultIncidentTest {

    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver
        private val json = ObjectMapper()

        private const val WORK_MASTER = "PrepareSequencedRack"
        private const val MISSIONS = "/api/missions/$WORK_MASTER"
        private const val MATERIAL = "ENGINE-COVER-A"
        private const val PRESENTATION = "SEQ-IN-02.BIN-A"
        private const val WAIT_UNIT = "rack-arrival"

        /** `ARRIVAL_WAIT_HOLD` 의 대기 기한. */
        private val DEADLINE: Duration = Duration.ofSeconds(20)

        /** 생존 보고 주기(가상 30초)를 넘겨 미는 폭. 공용 시운전 픽스처와 같다. */
        private val REPORT: Duration = Duration.ofSeconds(31)

        /** 단계 2b 에서 밀기 사이에 실제 시간으로 쉬는 폭. 연결 기준 60초보다 훨씬 짧아 quadruped-01 이 신선하게 남는다. */
        private val STALE_POLL: Duration = Duration.ofSeconds(5)

        /** 단계 2b 의 오래됨을 기다리는 상한(실제 시간). 기준 60초에 읽기 주기와 여유를 더한다. */
        private val STALE_WAIT: Duration = Duration.ofSeconds(120)

        /** 시계를 밀지 않고 미들웨어가 연결 변화를 보기를 기다리는 상한(실제 시간). pump 는 250ms 다. */
        private val LINK_WAIT: Duration = Duration.ofSeconds(5)

        /** 단계 사이에 넘기는 값. */
        private var graspIncident = ""
        private var firstHold = ""
        private var holdExecution = ""

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

        private fun rack(slot: String): String =
            """{"workMasterId":"$WORK_MASTER","slots":["$slot"],"material":"$MATERIAL","presentation":"$PRESENTATION"}"""
    }

    @Test
    @Order(1)
    fun `단계 1 데이터 정의 버전으로 도는 pick_place 에 스킬 실패를 넣으면 단위가 FAILED 이고 GRASP_FAILED 인시던트가 두 버전과 함께 보인다`() {
        activate("DATA_V1", "데이터 정의로 옮김", 1)
        val (jobOrderId, executionId) = submit(rack("RACK-204.S01"))

        // 작업 지시 직후의 태스크는 ACCEPTED 라 한 번 밀어야 RUNNING 이다(S4a JSON 계약 §8).
        driver.push(ExecutionDriver.STEP)
        assertEquals("RUNNING", unit(executionId, "RACK-204.S01")["state"].asText(), "${driver.execution(executionId)}")

        val injected = engineer("/api/faults", """{"robotId":"$HUMANOID","kind":"SKILL_EXECUTION_FAILED","reason":"스킬 실패 시연"}""")
        assertEquals("SUCCEEDED", injected["result"].asText(), "$injected")
        val fault = injected["fault"]
        assertEquals("$jobOrderId#RACK-204.S01", fault["taskId"].asText(), "$fault")
        assertEquals("RETRIABLE" to true, fault["taskState"].asText() to fault["raised"].asBoolean(), "$fault")

        // 현장은 엔진을 부른 뒤 밀지 않는다. 다음 시계 진행에서 미들웨어가 실패를 보고, 슬롯에 근거가 없어 단위가 FAILED 다.
        val settled = driver.drive(executionId)
        assertEquals("FAILED", settled["physicalState"].asText(), "$settled")
        assertEquals("FAILED", unit(executionId, "RACK-204.S01")["state"].asText(), "$settled")

        val list = incidents()
        val row = list["incidents"].single { it["executionId"].asText() == executionId }
        assertEquals(
            listOf(jobOrderId, HUMANOID, "RACK-204.S01", "GRASP_FAILED", "ROBOT"),
            listOf("jobOrderId", "robotId", "unitId", "failureClass", "route").map { row[it].asText() },
            "$row",
        )
        assertEquals(1 to 1L, row["missionVersion"].asInt() to row["siteSettingsVersion"].asLong(), "$row")
        assertEquals(listOf(false, false, false), listOf(row["unresolved"], row["held"], row["confirmedWithoutEvidence"]).map { it.asBoolean() }, "$row")
        assertTrue(row["resolution"].isNull, "$row")
        assertEquals("GRASP_FAILED" to "SKILL_EXECUTION_FAILED", row["fault"]["failureClass"].asText() to row["fault"]["errorType"].asText(), "$row")
        graspIncident = row["incidentId"].asText()

        val detail = stack.get("/api/incidents/$graspIncident")
        assertEquals("FAILED", detail["unitState"].asText(), "$detail")
        // 설비 슬롯은 실제로 읽었지만 확인 결과는 NOT_REQUESTED 로 남는다(스펙 §3 단계 1).
        assertEquals("NOT_REQUESTED", detail["verification"].asText(), "$detail")
        assertEquals(1 to 1L, detail["intent"]["missionVersion"].asInt() to detail["intent"]["siteSettingsVersion"].asLong(), "$detail")
        val references = detail["fault"]["references"].associate { it["key"].asText() to it["value"].asText() }
        assertEquals(mapOf("KEY_SKILL_ID" to "pick_place", "KEY_TASK_ID" to fault["taskId"].asText()), references, "$detail")
        assertTrue(detail["evidenceWindow"].any { it["kind"].asText() == "FAULT_RAISED" }, "$detail")
        assertEquals(0, detail["blockedBy"].size(), "$detail")

        val logged = operations("INJECT_FAULT").single()
        assertEquals(
            listOf("ENGINEER", "kim", HUMANOID, "SUCCEEDED", "스킬 실패 시연"),
            listOf("mode", "user", "target", "result", "reason").map { logged[it].asText() },
            "$logged",
        )
        val request = json.readTree(logged["request"].asText())
        assertEquals("SKILL_EXECUTION_FAILED" to true, request["kind"].asText() to request["state"].isNull, "$logged")

        // 잔여 파지(P0-b2). pick_place 가 든 채로 FAILED 에 정착했으므로 실행 호스트가 표시를 세운다. 표시만 남은 기체는 일반 배정에서 빠지고
        // «관문 확인용» 으로만 후보가 된다(조치 제안만 얻고 실행은 세우지 않는다, picasso §15.317). 표시 사유는 호스트 행에 있다.
        val hold = awaitResidualHold()
        assertEquals(
            listOf(HUMANOID, jobOrderId, executionId, "RACK-204.S01"),
            listOf("robotId", "jobOrderId", "executionId", "unitId").map { hold[it].asText() },
            "$hold",
        )
        val excluded = humanoidEligibility()
        assertEquals(true to true, excluded["eligible"].asBoolean() to excluded["gateTrial"].asBoolean(), "$excluded")
        assertTrue(excluded["host"]["reasons"].any { it.asText() == "잔여 파지 확인 필요: $jobOrderId RACK-204.S01" }, "$excluded")

        // 운영자가 현장에서 빈손을 확인해 표시를 푼다. 그 뒤 단계의 작업 지시가 humanoid-01 에 배정된다.
        val markerId = hold["markerId"].asLong()
        val cleared = stack.send("POST", "/api/robots/$HUMANOID/residual-holds/$markerId/clear", "operator", body = """{"reason":"현장 빈손 확인"}""")
        assertEquals(200 to "SUCCEEDED", cleared.status to cleared.body!!["result"].asText(), "${cleared.body}")
        assertEquals(0, stack.get("/api/robots/residual-holds")["holds"].size())
        val clearedRow = operations("CONFIRM_ROBOT_EMPTY").single()
        assertEquals(
            listOf("OPERATOR", "kim", "$HUMANOID/$markerId", "SUCCEEDED", "현장 빈손 확인"),
            listOf("mode", "user", "target", "result", "reason").map { clearedRow[it].asText() },
            "$clearedRow",
        )
        val assignable = humanoidEligibility()
        assertEquals(true to false, assignable["eligible"].asBoolean() to assignable["gateTrial"].asBoolean(), "$assignable")
    }

    @Test
    @Order(2)
    fun `단계 2a 로봇 단위가 도는 중 연결을 OFFLINE 으로 바꾸면 시계를 밀지 않아도 실행이 IN_DOUBT 이고 ONLINE 이면 RUNNING 으로 돌아온다`() {
        val (_, executionId) = submit(rack("RACK-204.S02"))
        driver.push(ExecutionDriver.STEP)
        assertEquals("RUNNING", driver.execution(executionId)["physicalState"].asText(), "${driver.execution(executionId)}")
        assertEquals("RUNNING", unit(executionId, "RACK-204.S02")["state"].asText())

        val clock = stack.site.now()
        assertEquals("SUCCEEDED", connection(HUMANOID, "OFFLINE", "단절 시연")["result"].asText())
        val doubt = awaitPhysical(executionId, "IN_DOUBT")
        // 단위는 도는 그대로이고 인시던트도 없다. 실행만 결과를 확인하지 못한 상태다.
        assertEquals("RUNNING", doubt["units"].single()["state"].asText(), "$doubt")
        assertEquals(clock, stack.site.now(), "단계 2a 는 시계를 밀지 않는다")

        assertEquals("SUCCEEDED", connection(HUMANOID, "ONLINE", "단절 복구")["result"].asText())
        awaitPhysical(executionId, "RUNNING")
        assertEquals(1, incidents()["total"].asInt(), "연결 단절은 인시던트를 남기지 않는다: ${incidents()}")

        val done = driver.drive(executionId)
        assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), "$done")
        assertEquals(listOf(Triple("RACK-204.S02", "DONE", "E2")), units(done), "$done")
    }

    @Test
    @Order(3)
    fun `단계 2b 유휴 humanoid-01 을 OFFLINE 으로 두고 연결 기준을 60초로 줄이면 실제 시간으로 오래됨 막힘이 서고 ONLINE 이면 신선으로 돌아온다`() {
        val injectedAt = Instant.now()
        assertEquals("SUCCEEDED", connection(HUMANOID, "OFFLINE", "묵은 값 시연")["result"].asText())
        val changed = stack.send(
            "PUT", "/api/site-settings", "engineer",
            body = """{"baseVersion":1,"connectionThresholdSeconds":60,"reason":"묵은 값 시연"}""",
        )
        assertEquals("SUCCEEDED", changed.body!!["result"].asText(), "${changed.body}")

        // 가상 시계를 거듭 밀어 quadruped-01 의 보고를 잇는다. humanoid-01 은 OFFLINE 이라 보고가 멈춘다.
        val deadline = Instant.now().plus(STALE_WAIT)
        var robots = stack.get("/api/robots")
        while (robot(robots, HUMANOID)["connection"].asText() != "STALE") {
            check(Instant.now().isBefore(deadline)) { "${STALE_WAIT.seconds}초 안에 오래됨이 되지 않았다: ${robot(robots, HUMANOID)}" }
            assertEquals("FRESH", robot(robots, QUADRUPED)["connection"].asText(), "${robot(robots, QUADRUPED)}")
            driver.push(REPORT)
            Thread.sleep(STALE_POLL.toMillis())
            robots = stack.get("/api/robots")
        }
        assertTrue(Duration.between(injectedAt, Instant.now()) > Duration.ofSeconds(60), "오래됨은 실제 시간 60초 뒤에만 선다")
        val stale = robot(robots, HUMANOID)["blockers"].single { it["kind"].asText() == "REPORT_STALE" }
        assertEquals("60초 안의 보고" to 2L, stale["expected"].asText() to stale["basisVersion"].asLong(), "$stale")
        val quadruped = robot(robots, QUADRUPED)
        assertEquals("FRESH", quadruped["connection"].asText(), "$quadruped")
        assertTrue(quadruped["blockers"].none { it["kind"].asText() == "REPORT_STALE" }, "$quadruped")

        // 오래된 기체는 배정 불가다.
        val eligibility = stack.send("POST", "/api/job-orders/eligibility", mode = null, body = rack("RACK-204.S03")).body!!
        val humanoid = eligibility["robots"].single { it["robotId"].asText() == HUMANOID }
        assertEquals(false, humanoid["eligible"].asBoolean(), "$humanoid")
        assertTrue(humanoid["reasons"].any { it.asText() == "연결이 오래됐다(기준 60초, 현장 설정 버전 2)" }, "$humanoid")

        // ONLINE 으로 바꾸는 순간 연결 메시지가 registry 로 가서 마지막 보고 시각이 새로 선다.
        assertEquals("SUCCEEDED", connection(HUMANOID, "ONLINE", "묵은 값 복구")["result"].asText())
        val until = Instant.now().plus(LINK_WAIT)
        while (robot(stack.get("/api/robots"), HUMANOID)["connection"].asText() != "FRESH") {
            check(Instant.now().isBefore(until)) { "ONLINE 뒤 신선으로 돌아오지 않았다: ${robot(stack.get("/api/robots"), HUMANOID)}" }
            Thread.sleep(200)
        }
        assertTrue(robot(stack.get("/api/robots"), HUMANOID)["blockers"].none { it["kind"].asText() == "REPORT_STALE" })
    }

    @Test
    @Order(4)
    fun `단계 3 보류 버전으로 낸 작업 지시가 신호 없이 기한 20초를 넘기면 운영자 보류이고 인시던트가 미해결이며 보류 중이다`() {
        activate("ARRIVAL_WAIT_HOLD", "운영자 보류 대기 도입", 2)
        val (jobOrderId, executionId) = submit(rack("RACK-204.S03"))
        holdExecution = executionId
        val waitFrom = stack.site.now()
        driver.push(ExecutionDriver.STEP)
        assertEquals(
            listOf(Triple(WAIT_UNIT, "equipment_wait", "RUNNING"), Triple("RACK-204.S03", "pick_place", "PENDING")),
            driver.execution(executionId)["units"].map { Triple(it["unitId"].asText(), it["skillType"].asText(), it["state"].asText()) },
        )

        val held = pushUntilHeld(executionId)
        assertTrue(Duration.between(waitFrom, stack.site.now()) >= DEADLINE, "기한 전에 보류가 섰다: ${stack.site.now()}")
        assertEquals("OPERATOR_HOLD", held["physicalState"].asText(), "$held")

        val row = incidents()["incidents"].first()
        assertEquals(
            listOf(executionId, jobOrderId, WAIT_UNIT, "SIGNAL_DEADLINE", "SIGNAL"),
            listOf("executionId", "jobOrderId", "unitId", "failureClass", "route").map { row[it].asText() },
            "$row",
        )
        assertEquals(2 to 2L, row["missionVersion"].asInt() to row["siteSettingsVersion"].asLong(), "$row")
        assertEquals(true to true, row["unresolved"].asBoolean() to row["held"].asBoolean(), "$row")
        assertTrue(row["resolution"].isNull && row["fault"].isNull, "$row")
        firstHold = row["incidentId"].asText()

        val detail = stack.get("/api/incidents/$firstHold")
        assertEquals("OPERATOR_HOLD", detail["unitState"].asText(), "$detail")
        assertEquals(listOf("E2", "E0", "NOT_REQUESTED"), listOf("requiredEvidence", "reachedEvidence", "verification").map { detail[it].asText() })
        assertEquals("20" to "OPERATOR_HOLD", detail["intent"]["unitParameters"]["deadlineSeconds"].asText() to detail["intent"]["unitParameters"]["onDeadline"].asText())
        assertEquals(2 to 2L, detail["intent"]["missionVersion"].asInt() to detail["intent"]["siteSettingsVersion"].asLong(), "$detail")
        assertTrue(detail["evidenceWindow"].any { it["kind"].asText() == "CELL_SIGNAL" }, "$detail")

        // 아무도 판단하지 않으면 시계를 밀어도 보류 그대로다.
        driver.push(ExecutionDriver.STEP)
        assertEquals("OPERATOR_HOLD", driver.execution(executionId)["physicalState"].asText())
    }

    @Test
    @Order(5)
    fun `단계 4 운영자가 사유를 적어 재작업을 판단하면 대기가 새로 시작되고 신호를 켜면 끝나며 판단자와 사유가 남는다`() {
        val reply = resolve(holdExecution, WAIT_UNIT, "REWORK", "랙 재배치 뒤 재작업", instanceId = detailInstance(firstHold))
        assertEquals(200, reply.status, "${reply.body}")
        val body = reply.body!!
        assertEquals(listOf("SUCCEEDED", "Resolved", "REWORK"), listOf(body["result"], body["outcome"], body["decision"]).map { it.asText() }, "$body")
        assertEquals(firstHold, body["answer"]["incidentId"].asText(), "$body")

        // 재작업은 단위를 새 정체성으로 다시 계획한다. 실행은 곧바로 RUNNING 이고 대기가 다시 돈다.
        val after = driver.execution(holdExecution)
        assertEquals("RUNNING", after["physicalState"].asText(), "$after")
        assertTrue(unit(holdExecution, WAIT_UNIT)["state"].asText() in setOf("PENDING", "RUNNING"), "$after")
        val row = incidents()["incidents"].single { it["incidentId"].asText() == firstHold }
        assertEquals(true to false, row["unresolved"].asBoolean() to row["held"].asBoolean(), "$row")
        val resolution = row["resolution"]
        assertEquals(listOf("REWORK", "kim", "PERSON"), listOf(resolution["decision"], resolution["decidedBy"]["id"], resolution["decidedBy"]["kind"]).map { it.asText() }, "$row")

        val logged = operations("RESOLVE_OPERATOR_HOLD").single()
        assertEquals(
            listOf("OPERATOR", "kim", "$holdExecution/$WAIT_UNIT", "SUCCEEDED", "랙 재배치 뒤 재작업"),
            listOf("mode", "user", "target", "result", "reason").map { logged[it].asText() },
            "$logged",
        )
        val request = json.readTree(logged["request"].asText())
        assertEquals("REWORK" to "kim", request["decision"].asText() to request["approverId"].asText(), "$logged")
        assertEquals("Resolved", json.readTree(logged["targetResponse"].asText())["body"]["result"].asText(), "$logged")

        // 대기가 다시 도는 동안 신호를 켠다. 시계를 밀기 전이라 새 기한을 넘기지 않는다.
        signal("true")
        val done = driver.drive(holdExecution)
        assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), "$done")
        assertEquals(listOf(Triple(WAIT_UNIT, "DONE", "E2"), Triple("RACK-204.S03", "DONE", "E2")), units(done), "$done")
        assertEquals(1, incidents()["incidents"].count { it["executionId"].asText() == holdExecution }, "재작업 뒤 두 번째 보류가 서지 않았다")
    }

    @Test
    @Order(6)
    fun `단계 5 신호를 되돌린 뒤 또 하나의 보류에서 완료 확인을 판단하면 단위가 근거 없이 완료되고 그 표시가 선다`() {
        signal("false")
        val (_, executionId) = submit(rack("RACK-204.S04"))
        driver.push(ExecutionDriver.STEP)
        pushUntilHeld(executionId)
        val rows = incidents()["incidents"]
        val hold = rows.first()
        assertEquals(executionId to true, hold["executionId"].asText() to hold["held"].asBoolean(), "$rows")
        // 앞 보류는 판단을 든 채 보류가 아니다.
        assertEquals(false, rows.single { it["incidentId"].asText() == firstHold }["held"].asBoolean(), "$rows")

        val reply = resolve(executionId, WAIT_UNIT, "CONFIRM_DONE", "현장 육안으로 랙 도착 확인", instanceId = detailInstance(hold["incidentId"].asText()))
        assertEquals("SUCCEEDED" to "Resolved", reply.body!!["result"].asText() to reply.body["outcome"].asText(), "${reply.body}")
        assertEquals("DONE", unit(executionId, WAIT_UNIT)["state"].asText(), "${driver.execution(executionId)}")

        val done = driver.drive(executionId)
        assertEquals(listOf(Triple(WAIT_UNIT, "DONE", "E0"), Triple("RACK-204.S04", "DONE", "E2")), units(done), "$done")

        val row = incidents()["incidents"].single { it["incidentId"].asText() == hold["incidentId"].asText() }
        assertEquals("CONFIRM_DONE" to true, row["resolution"]["decision"].asText() to row["confirmedWithoutEvidence"].asBoolean(), "$row")
        assertEquals(false, row["held"].asBoolean(), "$row")
        val detail = stack.get("/api/incidents/${hold["incidentId"].asText()}")
        assertNotEquals("MATCHED", detail["verification"].asText(), "$detail")
        assertEquals(true, detail["confirmedWithoutEvidence"].asBoolean(), "$detail")
        // 재작업의 판단 기록은 근거 없는 완료 확인이 아니다.
        assertEquals(false, incidents()["incidents"].single { it["incidentId"].asText() == firstHold }["confirmedWithoutEvidence"].asBoolean())
    }

    @Test
    @Order(7)
    fun `단계 6 모드가 틀리거나 사유가 없으면 사전 거부이고 보류가 아닌 단위의 판단과 도는 태스크 없는 스킬 실패는 거부로 남는다`() {
        val before = stack.get("/api/operations").size()

        val engineerResolve = resolve(holdExecution, WAIT_UNIT, "REWORK", "모드 확인", mode = "engineer")
        assertEquals(403 to "MODE_NOT_ALLOWED", engineerResolve.status to engineerResolve.body!!["error"].asText(), "${engineerResolve.body}")
        val noReason = stack.send("POST", "/api/executions/$holdExecution/units/$WAIT_UNIT/resolve", "operator", body = """{"decision":"REWORK","instanceId":"${incidents()["instanceId"].asText()}"}""")
        assertEquals(400 to "REASON_REQUIRED", noReason.status to noReason.body!!["error"].asText(), "${noReason.body}")
        val operatorFault = stack.send("POST", "/api/faults", "operator", body = """{"robotId":"$HUMANOID","kind":"SKILL_EXECUTION_FAILED","reason":"모드 확인"}""")
        assertEquals(403 to "MODE_NOT_ALLOWED", operatorFault.status to operatorFault.body!!["error"].asText(), "${operatorFault.body}")
        // 사전 거부는 실행 호스트에 닿지 않았으므로 기록하지 않는다.
        assertEquals(before, stack.get("/api/operations").size())

        // 끝난 실행의 대기 단위는 보류가 아니다.
        val notHeld = resolve(holdExecution, WAIT_UNIT, "CONFIRM_DONE", "보류 아님 확인")
        assertEquals(200, notHeld.status, "${notHeld.body}")
        assertEquals("REJECTED" to "NotHeld", notHeld.body!!["result"].asText() to notHeld.body["outcome"].asText(), "${notHeld.body}")
        val resolveRow = operations("RESOLVE_OPERATOR_HOLD").first()
        assertEquals("REJECTED" to "보류 아님 확인", resolveRow["result"].asText() to resolveRow["reason"].asText(), "$resolveRow")
        assertEquals("NotHeld", json.readTree(resolveRow["targetResponse"].asText())["body"]["result"].asText(), "$resolveRow")

        // 유휴 기체에는 진행 중 태스크가 없다.
        val idle = engineer("/api/faults", """{"robotId":"$HUMANOID","kind":"SKILL_EXECUTION_FAILED","reason":"유휴 기체 확인"}""")
        assertEquals("REJECTED", idle["result"].asText(), "$idle")
        assertEquals(409 to "NO_RUNNING_TASK", idle["rejection"]["status"].asInt() to idle["rejection"]["error"].asText(), "$idle")
        val faultRow = operations("INJECT_FAULT").first()
        assertEquals("REJECTED" to "유휴 기체 확인", faultRow["result"].asText() to faultRow["reason"].asText(), "$faultRow")
        assertEquals("NO_RUNNING_TASK", json.readTree(faultRow["targetResponse"].asText())["body"]["error"].asText(), "$faultRow")

        assertEquals(before + 2, stack.get("/api/operations").size())
        // 거부된 판단은 인시던트를 바꾸지 않는다.
        assertEquals(graspIncident, incidents()["incidents"].last()["incidentId"].asText())
    }

    /** 실행의 단위를 (단위 id, 상태, 근거 등급)으로 낸다. */
    private fun units(execution: JsonNode): List<Triple<String, String, String>> =
        execution["units"].map { Triple(it["unitId"].asText(), it["state"].asText(), it["reached"].asText()) }

    private fun unit(executionId: String, unitId: String): JsonNode =
        driver.execution(executionId)["units"].single { it["unitId"].asText() == unitId }

    private fun incidents(): JsonNode = stack.get("/api/incidents")

    private fun robot(view: JsonNode, robotId: String): JsonNode =
        view["robots"].single { it["robot"]["robotId"].asText() == robotId }

    /** 조작 기록에서 [op] 의 행만 최신부터 낸다. */
    private fun operations(op: String): List<JsonNode> =
        stack.get("/api/operations").filter { json.readTree(it["request"].asText())["op"].asText() == op }

    /** 시계를 밀지 않고 실제 시간으로 실행 상태가 [state] 가 되기를 기다린다. */
    private fun awaitPhysical(executionId: String, state: String): JsonNode {
        val until = Instant.now().plus(LINK_WAIT)
        var seen = driver.execution(executionId)
        while (seen["physicalState"].asText() != state) {
            check(Instant.now().isBefore(until)) { "${LINK_WAIT.seconds}초 안에 $state 가 되지 않았다: $seen" }
            Thread.sleep(100)
            seen = driver.execution(executionId)
        }
        return seen
    }

    /** 미해소 잔여 파지 표시가 하나 설 때까지 실제 시간으로 기다린다. 표시는 정착을 적는 트랜잭션이 커밋된 뒤에 보인다. */
    private fun awaitResidualHold(): JsonNode {
        val until = Instant.now().plus(LINK_WAIT)
        var holds = stack.get("/api/robots/residual-holds")["holds"]
        while (holds.size() == 0) {
            check(Instant.now().isBefore(until)) { "${LINK_WAIT.seconds}초 안에 잔여 파지 표시가 서지 않았다" }
            Thread.sleep(100)
            holds = stack.get("/api/robots/residual-holds")["holds"]
        }
        return holds.single()
    }

    /** 다음 슬롯(S02) 작업 지시에 대한 humanoid-01 의 배정 가능 행. */
    private fun humanoidEligibility(): JsonNode =
        stack.send("POST", "/api/job-orders/eligibility", mode = null, body = rack("RACK-204.S02")).body!!["robots"]
            .single { it["robotId"].asText() == HUMANOID }

    /** 대기 단위가 운영자 보류가 될 때까지 [ExecutionDriver.STEP] 씩 민다. 기한 20초면 다섯 번 안이다. */
    private fun pushUntilHeld(executionId: String): JsonNode {
        repeat(8) {
            val seen = driver.execution(executionId)
            if (seen["units"].single { it["unitId"].asText() == WAIT_UNIT }["state"].asText() == "OPERATOR_HOLD") return seen
            driver.push(ExecutionDriver.STEP)
        }
        error("대기 단위가 운영자 보류가 되지 않았다: ${driver.execution(executionId)}")
    }

    private fun connection(robotId: String, state: String, reason: String): JsonNode =
        engineer("/api/faults", """{"robotId":"$robotId","kind":"CONNECTION","state":"$state","reason":"$reason"}""")

    /**
     * 판단 요청. 본문의 `instanceId` 는 화면처럼 인시던트 상세의 값이다(S4b JSON 계약 O3). 보류가 아닌 단위를 판단하는 경우처럼
     * 고른 인시던트가 없으면 목록의 지금 인스턴스다.
     */
    private fun resolve(
        executionId: String,
        unitId: String,
        decision: String,
        reason: String,
        mode: String = "operator",
        instanceId: String = incidents()["instanceId"].asText(),
    ): E2eStack.Reply = stack.send(
        "POST", "/api/executions/$executionId/units/$unitId/resolve", mode,
        body = """{"decision":"$decision","instanceId":"$instanceId","reason":"$reason"}""",
    )

    private fun detailInstance(incidentId: String): String = stack.get("/api/incidents/$incidentId")["instanceId"].asText()

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

    /** 운영자 모드로 작업 지시를 내고 작업 지시 id 와 실행 id 를 낸다. 배정 기체는 `pick_place` 를 가진 humanoid-01 하나다. */
    private fun submit(form: String): Pair<String, String> {
        val reply = stack.send("POST", "/api/job-orders", "operator", body = form)
        assertEquals(200, reply.status, "${reply.body}")
        val outcome = reply.body!!["outcome"]
        assertEquals("ACCEPTED" to HUMANOID, outcome["result"].asText() to outcome["robotId"].asText(), "${reply.body}")
        return reply.body["jobOrderId"].asText() to outcome["executionId"].asText()
    }
}
