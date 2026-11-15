package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.TransportHandle
import dev.picasso.middleware.TransportState
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import dev.picasso.ops.site.FleetWorldWrite
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 플릿 운반 2단계의 완료 판정(통합). 실행 호스트의 플릿 포트를 현장 플릿 세계 면(`/fleet`)에 붙이고(`host.fleet.url`), 운영 서비스 REST 로
 * DeliverContainer 를 낸다: 운영 서비스 → 실행 호스트 → 현장 플릿 → 셀 대역 신호(인계 자리) → E2 → 결과 한 줄. 플릿 거절(출발지 불일치), 운반 중
 * 운영자 취소, **실행 호스트만** 재기동한 뒤 같은 운반 추적·중복 운반 없음(현장의 맡긴 횟수 하나)을 본다.
 *
 * 플릿 전용 계획은 요청 후보가 정확히 하나여야 한다(D2(a)). 운영 서비스는 배정 가능한 기체를 모두 후보로 싣으므로 humanoid-01 하나만 시운전한다.
 * 그 기체는 묶인 기체(실행 주체 아님)이고 기체에는 아무 태스크도 나가지 않는다.
 *
 * 순서가 있다. 묶인 기체는 실행이 정착할 때까지 점유되므로 앞 실행이 끝나야 다음 작업 지시가 선다. 현장 플릿 세계는 시험마다 출발 인계점에 새 용기를
 * 두고 도착 인계점을 비워 시작한다. 플릿은 현장 가상 시계로 5초마다 한 걸음이다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FleetTransportTest {

    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver

        private const val OUT = "OUT-07"
        private const val IN = "LINE-IN-03"

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start(fleet = true)
            driver = ExecutionDriver(stack)
            Commissioned.complete(stack, only = listOf(HUMANOID))
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }
    }

    private fun form(container: String) =
        """{"workMasterId":"DeliverContainer","container":"$container","source":"$OUT","destination":"$IN"}"""

    /** 출발 인계점에 [container] 를 두고 도착 인계점을 비운다. */
    private fun stage(container: String?) {
        val fleet = stack.site.fleet
        assertEquals(FleetWorldWrite.Written, fleet.write("clear", IN, null))
        assertEquals(FleetWorldWrite.Written, if (container == null) fleet.write("clear", OUT, null) else fleet.write("place", OUT, container))
        stack.site.projectFleet()
    }

    /** 낸 뒤 현장 플릿이 그 운반을 맡을 때까지 기다린다(맡기기는 호스트 잠금 밖에서 비동기로 나간다). 그래야 다음 밀기가 정확히 한 걸음이다. */
    private fun submit(container: String): JsonNode {
        val reply = stack.send("POST", "/api/job-orders", "operator", body = form(container))
        assertEquals(200, reply.status, "${reply.body}")
        val body = reply.body!!
        if (body["outcome"]?.get("result")?.asText() == "ACCEPTED") {
            val reference = "${body["jobOrderId"].asText()}#$container"
            val deadline = Instant.now().plusSeconds(5)
            while (stack.site.fleet.world.find(reference) == null) {
                check(Instant.now().isBefore(deadline)) { "현장 플릿이 5초 안에 $reference 를 맡지 않았다" }
                Thread.sleep(50)
            }
        }
        return body
    }

    private fun byJobOrder(jobOrderId: String): JsonNode =
        checkNotNull(driver.executions()["executions"].firstOrNull { it["jobOrderId"].asText() == jobOrderId }) { "실행이 없다: $jobOrderId" }

    private fun handover(id: String): JsonNode = stack.get("/api/cell")["cell"]["handovers"].single { it["id"].asText() == id }

    private fun noRobotTasks(jobOrderId: String) =
        assertTrue(stack.site.taskHistory(HUMANOID).keys.none { it.startsWith("$jobOrderId#") }, "묶인 기체에 태스크가 나갔다: ${stack.site.taskHistory(HUMANOID)}")

    @Test
    @Order(1)
    fun `운영 서비스의 DeliverContainer 가 실행 호스트를 거쳐 현장 플릿에 맡겨지고 인계가 셀 신호로 올라 E2 결과 한 줄로 끝난다`() {
        stage("HU-1042")
        val before = stack.site.fleet.world.dispatches
        val submitted = submit("HU-1042")
        val outcome = submitted["outcome"]
        assertEquals("SUCCEEDED" to "ACCEPTED", submitted["result"].asText() to outcome["result"].asText(), "$submitted")
        assertEquals(HUMANOID to true, outcome["robotId"].asText() to outcome["boundRobot"].asBoolean(), "$outcome")

        val execution = driver.drive(outcome["executionId"].asText())
        assertEquals("PHYSICALLY_DONE", execution["physicalState"].asText(), "$execution")
        assertEquals(true, execution["boundRobot"].asBoolean())
        assertEquals(
            listOf(listOf("HU-1042", "transport", "FLEET", "DONE", "E2")),
            execution["units"].map { listOf(it["unitId"].asText(), it["skillType"].asText(), it["route"].asText(), it["state"].asText(), it["reached"].asText()) },
            "$execution",
        )
        // 결과 한 줄 — 작업 응답이 E2 이고 송신 기록에 한 줄이다.
        assertEquals("E2" to listOf("HU-1042"), execution["jobResponse"]["reachedEvidence"].asText() to execution["jobResponse"]["completedUnits"].map { it.asText() })
        val sent = stack.get("/api/job-responses?jobOrderId=${submitted["jobOrderId"].asText()}")["responses"]
        assertEquals(listOf("PHYSICALLY_DONE" to "E2"), sent.map { it["physicalState"].asText() to it["reachedEvidence"].asText() }, "$sent")

        // 같은 세계: 플릿 목적지가 셀 대역의 인계 자리이고 그 용기가 거기 있다.
        val place = handover(IN)
        assertEquals(true to "HU-1042", place["occupied"].asBoolean() to place["material"].asText(), "$place")
        assertEquals(false, handover(OUT)["occupied"].asBoolean())
        assertEquals(1, stack.site.fleet.world.dispatches - before)
        noRobotTasks(submitted["jobOrderId"].asText())
    }

    @Test
    @Order(2)
    fun `출발 인계점의 용기가 요청과 다르면 플릿이 인수하지 않고 실행은 실패로 정착한다`() {
        stage("HU-2000")
        val submitted = submit("HU-2999")
        val outcome = submitted["outcome"]
        assertEquals("ACCEPTED", outcome["result"].asText(), "$submitted")
        val execution = driver.drive(outcome["executionId"].asText())
        assertEquals("FAILED", execution["physicalState"].asText(), "$execution")
        assertEquals(listOf("FLEET" to "FAILED"), execution["units"].map { it["route"].asText() to it["state"].asText() }, "$execution")
        assertTrue("HU-2999" in execution["jobResponse"]["incompleteUnits"].fieldNames().asSequence().toList(), "$execution")
        // 실패 분류는 출발지 불일치이고, 플릿이 관측한 용기(거기 있던 HU-2000)를 싣는다.
        val incident = stack.get("/api/incidents")["incidents"].single { it["executionId"].asText() == outcome["executionId"].asText() }
        assertEquals(listOf("HU-2999", "SOURCE_CONTAINER_MISMATCH", "FLEET"), listOf("unitId", "failureClass", "route").map { incident[it].asText() }, "$incident")
        val handle = assertNotNull(stack.site.fleet.world.find("${submitted["jobOrderId"].asText()}#HU-2999"))
        val seen = stack.site.fleet.world.status(handle)
        assertEquals(TransportState.REJECTED_AT_SOURCE to "HU-2000", seen.state to seen.observedContainer)
        // 출발지의 용기는 그대로이고 도착지에는 아무것도 없다.
        assertEquals("HU-2000", handover(OUT)["material"].asText())
        assertEquals(false, handover(IN)["occupied"].asBoolean())
        noRobotTasks(submitted["jobOrderId"].asText())
    }

    @Test
    @Order(3)
    fun `운반 중 운영자 취소는 플릿 운반을 거두고 용기가 출발 인계점으로 돌아오며 실행은 중단으로 정착한다`() {
        stage("HU-3000")
        val submitted = submit("HU-3000")
        val executionId = submitted["outcome"]["executionId"].asText()
        // 한 걸음 — 플릿이 용기를 인수했다(싣고 있다).
        driver.push(Duration.ofSeconds(5))
        val handle = assertNotNull(stack.site.fleet.world.find("${submitted["jobOrderId"].asText()}#HU-3000"))
        assertEquals(TransportState.PICKED_UP, stack.site.fleet.world.status(handle).state)
        assertEquals(false, handover(OUT)["occupied"].asBoolean())

        val instanceId = driver.executions()["instanceId"].asText()
        val cancel = stack.send("POST", "/api/executions/$executionId/cancel", "operator", body = """{"instanceId":"$instanceId","reason":"운반 경로 점검"}""")
        assertEquals(200, cancel.status, "${cancel.body}")
        val execution = driver.drive(executionId)
        assertEquals("ABORTED", execution["physicalState"].asText(), "$execution")
        assertEquals(listOf("FLEET" to "ABORTED"), execution["units"].map { it["route"].asText() to it["state"].asText() }, "$execution")
        assertEquals(TransportState.CANCELLED, stack.site.fleet.world.status(handle).state)
        // 미믹의 약속(실 플릿의 보장 아님): 싣고 있던 용기를 출발지로 되돌린다.
        assertEquals("HU-3000", handover(OUT)["material"].asText())
        assertEquals(false, handover(IN)["occupied"].asBoolean())
    }

    @Test
    @Order(4)
    fun `실행 호스트만 재기동하면 같은 운반에 다시 붙어 끝까지 추적하고 현장의 맡긴 횟수는 하나다`() {
        stage("HU-4000")
        val before = stack.site.fleet.world.dispatches
        val submitted = submit("HU-4000")
        val jobOrderId = submitted["jobOrderId"].asText()
        val first = submitted["outcome"]["executionId"].asText()
        driver.push(Duration.ofSeconds(5))
        val handle: TransportHandle = assertNotNull(stack.site.fleet.world.find("$jobOrderId#HU-4000"))
        assertEquals(TransportState.PICKED_UP, stack.site.fleet.world.status(handle).state)
        val instance = driver.executions()["instanceId"].asText()

        stack.restartHost()
        val deadline = Instant.now().plusSeconds(10)
        while (driver.executions()["instanceId"].asText() == instance) {
            check(Instant.now().isBefore(deadline)) { "재기동한 호스트가 답하지 않는다" }
            Thread.sleep(100)
        }
        val restored = driver.executions()["restore"]["rows"].single { it["jobOrderId"].asText() == jobOrderId }
        assertEquals("RESTORED" to first, restored["result"].asText() to restored["previousExecutionId"].asText(), "$restored")

        val execution = driver.drive(byJobOrder(jobOrderId)["executionId"].asText())
        assertEquals("PHYSICALLY_DONE", execution["physicalState"].asText(), "$execution")
        assertEquals(listOf("FLEET" to "E2"), execution["units"].map { it["route"].asText() to it["reached"].asText() }, "$execution")
        assertEquals(1, stack.site.fleet.world.dispatches - before, "재기동이 새 운반을 맡기지 않았다")
        assertEquals(handle, stack.site.fleet.world.find("$jobOrderId#HU-4000"))
        assertEquals(TransportState.DELIVERED, stack.site.fleet.world.status(handle).state)
        assertEquals("HU-4000", handover(IN)["material"].asText())
        noRobotTasks(jobOrderId)
    }
}
