package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.JobOrderBench.Companion.HUMANOID
import dev.picasso.ops.service.JobOrderBench.Companion.QUADRUPED
import dev.picasso.ops.service.JobOrderBench.Companion.SETTINGS_VERSION
import dev.picasso.ops.service.JobOrderBench.Companion.binding
import dev.picasso.ops.service.JobOrderBench.Companion.missing
import dev.picasso.ops.service.JobOrderBench.Companion.pass
import dev.picasso.ops.service.JobOrderBench.Companion.robot
import dev.picasso.ops.service.JobOrderBench.Companion.running
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.joborders.EligibilityView
import dev.picasso.ops.service.joborders.HostState
import dev.picasso.ops.service.joborders.InspectAssetDraft
import dev.picasso.ops.service.joborders.InspectionTarget
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.joborders.RobotEligibility
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.robots.CommissioningState
import dev.picasso.ops.service.robots.Connection
import dev.picasso.ops.service.robots.RegistryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 배정 가능 네 칸의 합성(S3a 스펙 §8, T3). 시운전·연결은 운영 서비스, 스킬 적합·도는 실행은 호스트가 판정한다. */
class JobOrderEligibilityTest {

    private val bench = JobOrderBench()
    private val order = JobOrderForm.jobOrder(InspectAssetDraft(listOf(InspectionTarget("T1", "bay-7"))), "JO-DRAFT", ObjectMapper())

    private fun judge(): EligibilityView = bench.eligibility.judge(order)

    private fun EligibilityView.row(id: String): RobotEligibility = robots!!.single { it.robotId == id }

    @Test
    fun `네 칸이 다 통과한 기체만 배정 가능이고 나머지는 칸마다 이유를 낸다`() {
        bench.robots = RegistryCall.Ok(
            listOf(
                robot(HUMANOID),
                robot(QUADRUPED),
                robot("unbound-01"),
                robot("stale-01", lastReportedAt = "2026-10-07T23:58:00Z"),
                robot("busy-01"),
                robot("retired-01", status = "RETIRED"),
            ),
        )
        bench.bindings = RegistryCall.Ok(
            listOf(binding(HUMANOID), binding(QUADRUPED), binding("stale-01"), binding("busy-01"), binding("retired-01")),
        )
        bench.judge = { ids ->
            HostCall.Ok(
                ids.map {
                    when (it) {
                        QUADRUPED -> missing(it, "inspect", "navigate_to")
                        "busy-01" -> running(it, "exec-4")
                        else -> pass(it)
                    }
                },
            )
        }
        val view = judge()
        assertEquals(RegistryState.OK, view.registry)
        assertEquals(HostState.OK, view.host)
        assertEquals(bench.at, view.checkedAt)
        assertEquals(listOf(HUMANOID), view.robots!!.filter { it.eligible }.map { it.robotId })

        val humanoid = view.row(HUMANOID)
        assertEquals(CommissioningState.COMPLETE, humanoid.commissioning)
        assertEquals(Connection.FRESH, humanoid.connection)
        assertEquals(SETTINGS_VERSION, humanoid.settingsVersion)
        assertEquals(pass(HUMANOID), humanoid.host)
        assertEquals(emptyList(), humanoid.reasons)

        assertEquals(listOf("모자란 스킬: inspect, navigate_to"), view.row(QUADRUPED).reasons)
        assertEquals(listOf("inspect", "navigate_to"), view.row(QUADRUPED).host!!.missingSkills)
        assertEquals(CommissioningState.INCOMPLETE, view.row("unbound-01").commissioning)
        assertEquals(listOf("시운전이 끝나지 않았다"), view.row("unbound-01").reasons)
        assertEquals(Connection.STALE, view.row("stale-01").connection)
        assertEquals(listOf("연결이 오래됐다(기준 90초, 현장 설정 버전 3)"), view.row("stale-01").reasons)
        assertEquals("exec-4", view.row("busy-01").host!!.runningExecutionId)
        assertEquals(listOf("진행 중인 실행이 있다: exec-4"), view.row("busy-01").reasons)
        assertEquals(CommissioningState.RETIRED, view.row("retired-01").commissioning)
        assertEquals(listOf("퇴역한 기체다"), view.row("retired-01").reasons)
    }

    @Test
    fun `호스트에 작업 지시 본문과 목록의 기체 id 를 넘긴다`() {
        judge()
        assertEquals(listOf(order to listOf(HUMANOID, QUADRUPED)), bench.judged)
    }

    @Test
    fun `registry 가 답하지 않으면 직전 목록이 신선이어도 시운전·연결은 모름이고 배정 가능이 아니다`() {
        assertTrue(judge().row(HUMANOID).eligible)
        bench.robots = RegistryCall.Silent("응답 없음")
        val view = judge()
        assertEquals(RegistryState.REGISTRY_SILENT, view.registry)
        // 직전 목록의 기체는 그대로 보이고, 호스트 칸은 그 기체 id 로 따로 물어 알 수 있다.
        val humanoid = view.row(HUMANOID)
        assertNull(humanoid.commissioning)
        assertNull(humanoid.connection)
        assertNull(humanoid.settingsVersion)
        assertEquals(pass(HUMANOID), humanoid.host)
        assertEquals(false, humanoid.eligible)
        assertEquals(listOf("registry 가 답하지 않아 시운전·연결을 모른다"), humanoid.reasons)
        assertEquals(listOf(HUMANOID, QUADRUPED), bench.judged.last().second)
    }

    @Test
    fun `호스트가 답하지 않으면 호스트 칸이 모름이고 배정 가능이 아니다`() {
        bench.judge = { HostCall.Silent("응답 없음: ConnectException") }
        val view = judge()
        assertEquals(HostState.HOST_SILENT, view.host)
        val humanoid = view.row(HUMANOID)
        assertEquals(CommissioningState.COMPLETE, humanoid.commissioning)
        assertEquals(Connection.FRESH, humanoid.connection)
        assertNull(humanoid.host)
        assertEquals(false, humanoid.eligible)
        assertEquals(listOf("실행 호스트가 답하지 않아 스킬 적합·진행 중인 실행을 모른다"), humanoid.reasons)
    }

    @Test
    fun `호스트가 어떤 기체를 판정하지 않으면 그 기체의 호스트 칸은 모름이다`() {
        bench.judge = { HostCall.Ok(listOf(pass(HUMANOID))) }
        val quadruped = judge().row(QUADRUPED)
        assertNull(quadruped.host)
        assertEquals(false, quadruped.eligible)
        assertEquals(listOf("실행 호스트가 이 기체를 판정하지 않았다"), quadruped.reasons)
    }

    @Test
    fun `기체 목록을 한 번도 못 읽었으면 기체 행이 모름이고 호스트를 부르지 않는다`() {
        bench.robots = RegistryCall.Silent("응답 없음")
        val view = judge()
        assertNull(view.robots)
        assertNull(view.robotsAsOf)
        assertEquals(HostState.OK, view.host)
        assertEquals(emptyList(), bench.judged)
    }

    @Test
    fun `관문 확인용 기체는 배정 가능이되 표시 사유를 이유 칸에 싣지 않고 관문 확인용으로 낸다`() {
        bench.robots = RegistryCall.Ok(listOf(robot(HUMANOID)))
        bench.bindings = RegistryCall.Ok(listOf(binding(HUMANOID)))
        bench.judge = { ids -> HostCall.Ok(ids.map { pass(it).copy(reasons = listOf("잔여 파지 확인 필요: JO-R rack"), gateTrial = true) }) }
        val row = judge().row(HUMANOID)
        assertTrue(row.eligible)
        assertTrue(row.gateTrial)
        assertEquals(emptyList(), row.reasons)
        assertEquals(listOf("잔여 파지 확인 필요: JO-R rack"), row.host!!.reasons)
    }
}
