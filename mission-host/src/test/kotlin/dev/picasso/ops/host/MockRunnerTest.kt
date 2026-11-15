package dev.picasso.ops.host

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.EquipmentUse
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.InspectAsset
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.mission.DefinedCapability
import dev.picasso.middleware.mission.MissionDefinition
import dev.picasso.middleware.mission.MissionDefinitionParser
import dev.picasso.middleware.mission.MissionParse
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.mission.MissionTemplates
import dev.picasso.ops.host.mission.MockRunFailure
import dev.picasso.ops.host.mission.MockRunner
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 모의 실행기(S3b 스펙 §6.4, T8). DB·스프링 없이 하네스 mimic 과 미들웨어만 세운다. 표본 작업 지시는 현장 표준 셀 대역
 * 스냅숏으로 만든다.
 */
class MockRunnerTest {

    private companion object {
        const val CONTAINER = "HU-1042"
        const val OUT = "OUT-07"
        const val SEQ_IN = "SEQ-IN-02"
    }

    private val cell = CellBandClient.parse(HostBench.JSON.readTree(HostBench.STANDARD_CELL))

    private fun runner(virtualLimit: Duration = Duration.ofMinutes(10), wallLimit: Duration = MockRunner.WALL_LIMIT) =
        MockRunner(HostBench.MOCK_PROFILE, HostBench.SCHEMA, virtualLimit, wallLimit)

    private fun order(): JobOrder = checkNotNull(MockRunner.sampleOrder(PrepareSequencedRack.WORK_MASTER, 7, cell))

    private fun template(id: String): String = MissionTemplates.of(PrepareSequencedRack.WORK_MASTER).single { it.id == id }.definition

    private fun parsed(text: String): MissionDefinition = when (val p = MissionDefinitionParser.parse(text)) {
        is MissionParse.Parsed -> p.definition
        is MissionParse.Unreadable -> error("정의를 못 읽었다: ${p.problems}")
    }

    @Test
    fun `표본 작업 지시는 스냅숏의 앞 슬롯 둘과 자재를 든 제시 자리로 만들고 자재 수가 슬롯 수와 같다`() {
        val order = order()
        assertEquals("MOCK-7", order.jobOrderId)
        assertEquals(PrepareSequencedRack.WORK_MASTER, order.workMasterId)
        assertEquals(Evidence.E2, order.requiredEvidence)
        assertEquals(listOf("RACK-204.S01", "RACK-204.S02", HostBench.SOURCE), order.equipmentRequirements.map { it.id })
        assertEquals(listOf(EquipmentUse.DESTINATION, EquipmentUse.DESTINATION, EquipmentUse.SOURCE), order.equipmentRequirements.map { it.equipmentUse })
        assertTrue(order.equipmentRequirements.all { it.properties[EquipmentUse.PROP_MATERIAL] == HostBench.MATERIAL })
        assertEquals(mapOf(HostBench.MATERIAL to 2), order.materialRequirements.associate { it.materialDefinitionId to it.quantity })

        assertNull(MockRunner.sampleOrder(PrepareSequencedRack.WORK_MASTER, 7, cell.copy(slots = emptyList())))
        assertNull(MockRunner.sampleOrder(PrepareSequencedRack.WORK_MASTER, 7, cell.copy(presentations = emptyList())))
    }

    @Test
    fun `버전 1 모양은 이상적 현장에서 모든 단위가 E2 로 완료되어 통과한다`() {
        val result = runner().run(parsed(template(MissionTemplates.DATA_V1)), order())
        assertTrue(result.passed, result.toString())
        assertNull(result.failure)
        assertNull(result.detail)
        assertEquals("PHYSICALLY_DONE", result.physicalState)
        assertEquals(listOf("RACK-204.S01", "RACK-204.S02"), result.units.map { it.unitId })
        assertTrue(result.units.all { it.state == "DONE" && it.reached == "E2" && it.skillType == "pick_place" }, result.units.toString())
        assertEquals(MockRunner.ROBOT_ID, result.robotId)
        // pick_place 둘(45초 안팎)을 차례로 돈다.
        assertTrue(result.virtualElapsedSeconds in 80..300, result.toString())
    }

    @Test
    fun `목적지 관측을 요구한 버전 1 모양은 이상적 현장의 목적지가 작업 전에 비어 게이트를 지나고 모든 단위가 E2 로 완료되어 통과한다`() {
        val required = template(MissionTemplates.DATA_V1)
            .replace("\"destinationObservation\": \"OPTIONAL\"", "\"destinationObservation\": \"REQUIRED\"")
        check(required != template(MissionTemplates.DATA_V1)) { "선언을 바꾸지 못했다" }
        val result = runner().run(parsed(required), order())
        assertTrue(result.passed, result.toString())
        assertEquals("PHYSICALLY_DONE", result.physicalState)
        assertEquals(listOf("RACK-204.S01", "RACK-204.S02"), result.units.map { it.unitId })
        assertTrue(result.units.all { it.state == "DONE" && it.reached == "E2" }, result.units.toString())
    }

    @Test
    fun `대기 노드 버전 2 모양은 대기 단위가 기대 값을 읽어 완료되고 통과한다`() {
        val result = runner().run(parsed(template(MissionTemplates.ARRIVAL_WAIT)), order())
        assertTrue(result.passed, result.toString())
        assertEquals(listOf("rack-arrival", "RACK-204.S01", "RACK-204.S02"), result.units.map { it.unitId })
        val wait = result.units.first()
        assertEquals("SIGNAL", wait.route)
        assertEquals("DONE", wait.state)
        assertEquals("E2", wait.reached)
        // 대기는 첫 pump 에서 풀린다. 기한(120초)을 기다렸다면 경과가 그만큼 늘어난다.
        assertTrue(result.virtualElapsedSeconds < 120 + 90, result.toString())
    }

    @Test
    fun `가상 시간 상한을 줄이면 정착하지 않음으로 실패한다`() {
        val result = runner(virtualLimit = Duration.ofSeconds(30)).run(parsed(template(MissionTemplates.DATA_V1)), order())
        assertEquals(false, result.passed)
        assertEquals(MockRunFailure.NOT_SETTLED, result.failure, result.toString())
        assertNotNull(result.detail)
        assertTrue(result.virtualElapsedSeconds >= 30)
        assertTrue(result.units.any { it.state != "DONE" })
    }

    @Test
    fun `실제 시간 상한을 넘기면 실제 시간 상한으로 실패한다`() {
        val result = runner(wallLimit = Duration.ofMillis(1)).run(parsed(template(MissionTemplates.DATA_V1)), order())
        assertEquals(MockRunFailure.WALL_CLOCK_LIMIT, result.failure, result.toString())
        assertEquals(false, result.passed)
    }

    @Test
    fun `같은 신호를 기대 값이 다른 두 대기 노드가 쓰면 돌리지 않고 정의 실패다`() {
        val two = template(MissionTemplates.ARRIVAL_WAIT).replace(
            "\"onDeadline\": \"ABORTED\"},",
            "\"onDeadline\": \"ABORTED\"},\n{\"kind\": \"wait\", \"id\": \"rack-gone\", \"signal\": \"rack_present\", \"expect\": \"false\", \"deadlineSeconds\": 60, \"onDeadline\": \"ABORTED\"},",
        )
        val definition = parsed(two)
        assertEquals(2, definition.steps.count { it.id.startsWith("rack-") })
        val result = runner().run(definition, order())
        assertEquals(MockRunFailure.DEFINITION, result.failure, result.toString())
        assertTrue("rack_present" in result.detail!! && "rack-arrival=true" in result.detail!! && "rack-gone=false" in result.detail!!, result.detail)
        assertNull(result.physicalState)
        assertEquals(0, result.virtualElapsedSeconds)
    }

    @Test
    fun `표본 작업 지시가 정의의 최고 근거 등급을 넘으면 실행이 서지 않아 SUBMISSION_REJECTED 다`() {
        val e0 = template(MissionTemplates.DATA_V1).replace("\"maxEvidence\": \"E2\"", "\"maxEvidence\": \"E0\"")
        val result = runner().run(parsed(e0), order())
        assertEquals(MockRunFailure.SUBMISSION_REJECTED, result.failure, result.toString())
        assertNull(result.physicalState)
        assertTrue(result.units.isEmpty())
    }

    @Test
    fun `버전 1 템플릿은 코드 PrepareSequencedRack 과 같은 단위를 계획한다`() {
        val order = order()
        val defined = DefinedCapability(parsed(template(MissionTemplates.DATA_V1))).plan(order)
        val coded = PrepareSequencedRack().plan(order)
        assertEquals(coded.map { it.unitId }, defined.map { it.unitId })
        assertEquals(coded.map { it.skillType }, defined.map { it.skillType })
        assertEquals(coded.map { it.parameters }, defined.map { it.parameters })
        assertEquals(coded.map { it.expectedIdentity }, defined.map { it.expectedIdentity })
        assertEquals(coded.map { it.source }, defined.map { it.source })
        assertEquals(coded.map { it.destination }, defined.map { it.destination })
    }

    /**
     * 반복 그룹 정의(picasso §15.325, `MissionFixtures.INSPECT_ASSET` 모양) — 화면 «대상마다 이동 뒤 점검» 템플릿과 같은 글자의
     * 시험 자원 `mission-profile-visit.json` 의 정의다. [use] 로 반복 대상(설비 쓰임)을 바꾼다.
     */
    private fun visit(use: String = "mission_target"): String {
        val profile = HostBench.JSON.readTree(javaClass.getResourceAsStream("/mission-profile-visit.json"))
        return profile["definition"].toString().replace("\"forEach\":\"mission_target\"", "\"forEach\":\"$use\"")
            .also { check(use == "mission_target" || it.contains(use)) { "반복 대상을 바꾸지 못했다" } }
    }

    /** 그룹 정의 끝에 작업 지시마다 한 번 도는 노드(`once`)를 더한다. 단위 id 는 작업 지시 id, 첫 대상의 위치로 간다. */
    private fun withOnce(definition: String): String = definition.removeSuffix("]}") +
        """,{"kind":"unit","id":"park","once":true,"skill":"navigate_to","unitId":{"from":"ORDER_ID"},""" +
        """"parameters":{"location":{"from":"FIRST_PROPERTY","equipmentUse":"mission_target","property":"location"}},""" +
        """"destination":{"from":"FIRST_PROPERTY","equipmentUse":"mission_target","property":"location"}}]}"""

    private fun targets(use: String = "mission_target") = JobOrder(
        jobOrderId = "MOCK-9", workMasterId = "AssetVisitInspection", version = 1, requiredEvidence = Evidence.E0,
        equipmentRequirements = listOf(
            EquipmentRequirement("T1", use, mapOf("location" to "bay-7")),
            EquipmentRequirement("T2", use, mapOf("location" to "dock-3")),
        ),
    )

    @Test
    fun `반복 그룹 정의는 대상마다 이동 뒤 점검을 차례로 돌려 통과하고 코드 InspectAsset 과 같은 단위를 계획한다`() {
        val result = runner().run(parsed(visit()), targets())
        assertTrue(result.passed, result.toString())
        assertEquals(listOf("T1.travel", "T1", "T2.travel", "T2"), result.units.map { it.unitId })
        assertEquals(listOf("navigate_to", "inspect", "navigate_to", "inspect"), result.units.map { it.skillType })
        assertTrue(result.units.all { it.state == "DONE" }, result.units.toString())

        // 반복 대상을 코드 InspectAsset 의 쓰임으로 바꾸면 계획이 칸마다 같다(선행 after 는 picasso 내부 칸이라 picasso 시험이 댄다).
        val order = targets(EquipmentUse.INSPECTION_TARGET)
        val defined = DefinedCapability(parsed(visit(EquipmentUse.INSPECTION_TARGET))).plan(order)
        val coded = InspectAsset().plan(order)
        assertEquals(coded.map { it.unitId }, defined.map { it.unitId })
        assertEquals(coded.map { it.skillType }, defined.map { it.skillType })
        assertEquals(coded.map { it.parameters }, defined.map { it.parameters })
        assertEquals(coded.map { it.destination }, defined.map { it.destination })
    }

    @Test
    fun `once 노드는 표본 검사에서 빠져 통과하고 그룹의 반복 대상이 표본에 없으면 돌리지 않고 정의 실패다`() {
        val once = runner().run(parsed(withOnce(visit())), targets())
        assertTrue(once.passed, once.toString())
        assertEquals(listOf("T1.travel", "T1", "T2.travel", "T2", "MOCK-9"), once.units.map { it.unitId })

        // 그룹의 반복 대상이 표본에 없으면 once 단위 하나만 계획되지만 정의 실패다.
        val absent = runner().run(parsed(withOnce(visit("absent_target"))), targets())
        assertEquals(MockRunFailure.DEFINITION, absent.failure, absent.toString())
        assertTrue("absent_target" in absent.detail!!, absent.detail)
        assertNull(absent.physicalState)
        assertTrue(absent.units.isEmpty())
    }

    /**
     * picasso `MissionFixtures.DELIVER_CONTAINER` 모양의 플릿 once 노드 — 첫 출발 설비의 [property] 속성이 용기이고 첫 목적지로 운반한다.
     * picasso 시험 소스는 이 저장소가 쓸 수 없어 같은 칸을 옮겨 적는다.
     */
    private fun deliverNode(property: String = EquipmentUse.PROP_CONTAINER): String {
        val first = """{"from":"FIRST_ID","equipmentUse":"source"}"""
        val destination = """{"from":"FIRST_ID","equipmentUse":"destination"}"""
        val container = """{"from":"FIRST_PROPERTY","equipmentUse":"source","property":"$property"}"""
        return """{"kind":"unit","id":"deliver","once":true,"route":"FLEET","skill":"transport","requires":[$first,$destination,$container],""" +
            """"whenMissing":{"failureClass":"INCOMPLETE_ORDER","parameters":[]},""" +
            """"unitId":{"from":"FIRST_PROPERTY","equipmentUse":"source","property":"$property","else":{"from":"ORDER_ID"}},""" +
            """"parameters":{"container":$container,"source":$first,"destination":$destination},""" +
            """"expectedIdentity":$container,"source":$first,"destination":$destination}"""
    }

    private fun deliver(workMaster: String = "ContainerSupply"): String =
        """{"schemaVersion":1,"workMasterId":"$workMaster","maxEvidence":"E2","preferredOptionals":{},"steps":[${deliverNode()}]}"""

    /** 시나리오 ① 의 작업 지시 모양 — 출발 인계점의 `container` 가 할당된 용기다. [container] 가 널이면 그 속성을 싣지 않는다. */
    private fun supply(container: String? = CONTAINER, required: Evidence = Evidence.E2) = JobOrder(
        jobOrderId = "MOCK-11", workMasterId = "ContainerSupply", version = 1, requiredEvidence = required,
        equipmentRequirements = listOf(
            EquipmentRequirement(OUT, EquipmentUse.SOURCE, container?.let { mapOf(EquipmentUse.PROP_CONTAINER to it) }.orEmpty()),
            EquipmentRequirement(SEQ_IN, EquipmentUse.DESTINATION),
        ),
    )

    @Test
    fun `플릿 정의는 인프로세스 플릿 미믹이 출발지의 용기를 목적지로 옮기고 이상적 현장이 그 도착을 보아 E2 로 통과한다`() {
        val result = runner().run(parsed(deliver()), supply())
        assertTrue(result.passed, result.toString())
        assertNull(result.failure)
        assertEquals("PHYSICALLY_DONE", result.physicalState)
        val unit = result.units.single()
        assertEquals(listOf(CONTAINER, "FLEET", "transport", "DONE", "E2"), listOf(unit.unitId, unit.route, unit.skillType, unit.state, unit.reached))
        assertNull(unit.failureClass)
        // 접수 → 인수 → 이동 → 인계가 진행 루프의 걸음마다 한 단계다(STEP 5초 × 셋 안팎).
        assertTrue(result.virtualElapsedSeconds in 10..60, result.toString())
    }

    @Test
    fun `요구 근거가 E1 인 플릿 정의는 플릿의 완료로 통과한다`() {
        val result = runner().run(parsed(deliver()), supply(required = Evidence.E1))
        assertTrue(result.passed, result.toString())
        assertEquals("E1", result.units.single().reached)
    }

    @Test
    fun `로봇 반복 그룹과 플릿 once 를 섞은 정의는 대상마다 이동 뒤 점검과 운반 하나를 함께 돌려 통과한다`() {
        val mixed = visit().replace("\"workMasterId\":\"AssetVisitInspection\"", "\"workMasterId\":\"ContainerSupply\"")
            .removeSuffix("]}") + ",${deliverNode()}]}"
        val definition = parsed(mixed)
        val order = targets().copy(
            workMasterId = "ContainerSupply",
            equipmentRequirements = targets().equipmentRequirements + supply().equipmentRequirements,
        )
        val result = runner().run(definition, order)
        assertTrue(result.passed, result.toString())
        assertEquals(listOf("T1.travel", "T1", "T2.travel", "T2", CONTAINER), result.units.map { it.unitId })
        assertEquals(listOf("ROBOT", "ROBOT", "ROBOT", "ROBOT", "FLEET"), result.units.map { it.route })
        assertTrue(result.units.all { it.state == "DONE" }, result.units.toString())
    }

    /**
     * 로봇 집기·놓기(버전 1 템플릿, 슬롯마다 pick_place)와 플릿 once 가 자리를 나눈다 — 셀 대역 표본에서 플릿의 첫 출발지는 로봇이 집는 제시
     * 자리이고 첫 목적지는 로봇이 놓는 첫 슬롯이다. 이상적 현장은 로봇의 출발 자리에 로봇 규칙을, 플릿이 인계하기 전의 공유 슬롯에 로봇 규칙을,
     * 인계 뒤에는 그 용기를 답한다(플릿 노드가 로봇 노드 뒤라 로봇이 먼저 놓고 근거를 세운다).
     */
    @Test
    fun `로봇 pick_place 와 플릿 once 가 자리를 나누는 정의는 셀 대역 표본으로 모든 단위가 E2 로 통과한다`() {
        val template = HostBench.JSON.readTree(template(MissionTemplates.DATA_V1)) as ObjectNode
        (template["steps"] as com.fasterxml.jackson.databind.node.ArrayNode).add(HostBench.JSON.readTree(deliverNode()))
        val definition = parsed(template.toString())
        val order = checkNotNull(MockRunner.sampleOrder(PrepareSequencedRack.WORK_MASTER, 7, cell, fleet = MockRunner.hasFleet(definition)))
        val result = runner().run(definition, order)
        assertTrue(result.passed, result.toString())
        assertEquals(listOf("RACK-204.S01", "RACK-204.S02", "${MockRunner.SAMPLE_CONTAINER_PREFIX}7"), result.units.map { it.unitId })
        assertEquals(listOf("ROBOT", "ROBOT", "FLEET"), result.units.map { it.route })
        assertTrue(result.units.all { it.state == "DONE" && it.reached == "E2" }, result.units.toString())
    }

    @Test
    fun `표본의 출발 설비에 용기가 없으면 플릿 정의를 돌리지 않고 그 단위와 빈 칸을 소견으로 낸 정의 실패다`() {
        val result = runner().run(parsed(deliver()), supply(container = null))
        assertEquals(false, result.passed)
        assertEquals(MockRunFailure.DEFINITION, result.failure, result.toString())
        // 용기가 없으면 단위 id 는 작업 지시 id 로 떨어지고(else) 용기가 비었다.
        assertEquals("${MockRunner.FLEET_SAMPLE_DETAIL}: MOCK-11(용기 없음)", result.detail)
        assertNull(result.physicalState)
        assertTrue(result.units.isEmpty())
        assertEquals(0, result.virtualElapsedSeconds)
    }

    @Test
    fun `플릿 정의의 표본은 출발 자리에 용기 속성을 싣고 자재 선언을 싣지 않는다`() {
        val order = checkNotNull(MockRunner.sampleOrder("ContainerSupply", 7, cell, fleet = true))
        val source = order.equipmentRequirements.single { it.equipmentUse == EquipmentUse.SOURCE }
        assertEquals("${MockRunner.SAMPLE_CONTAINER_PREFIX}7", source.properties[EquipmentUse.PROP_CONTAINER])
        assertEquals(HostBench.MATERIAL, source.properties[EquipmentUse.PROP_MATERIAL])
        assertTrue(order.materialRequirements.isEmpty(), order.toString())
        assertTrue(MockRunner.hasFleet(parsed(deliver())))
        assertEquals(false, MockRunner.hasFleet(parsed(template(MissionTemplates.DATA_V1))))

        // 그 표본으로 플릿 정의가 통과한다 — 용기를 제시 자리에서 첫 슬롯으로 옮긴다.
        val result = runner().run(parsed(deliver()), order)
        assertTrue(result.passed, result.toString())
        assertEquals("${MockRunner.SAMPLE_CONTAINER_PREFIX}7", result.units.single().unitId)
    }

    @Test
    fun `모의 실행 프로파일은 현장 humanoid 프로파일에서 실패 모드만 뺀 사본이다`() {
        val site = HostBench.JSON.readTree(Files.readString(HostBench.ROOT.resolve("picasso/profile/profiles/humanoid-a.json"))) as ObjectNode
        val mock = HostBench.JSON.readTree(Files.readString(HostBench.MOCK_PROFILE)) as ObjectNode
        assertTrue(site["failure_modes"].size() > 0, "현장 프로파일에 실패 모드가 없다. 사본을 둘 까닭을 다시 볼 것")
        assertEquals(0, mock["failure_modes"].size())
        site.remove("failure_modes")
        mock.remove("failure_modes")
        assertEquals(site, mock)
    }
}
