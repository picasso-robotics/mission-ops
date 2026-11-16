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
import dev.picasso.ops.host.mission.MockBranch
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

    // ── 접수 시점 분기(picasso §15.328)

    /**
     * 최상위 분기 — 작업 지시 파라미터 `mode` 로 랙 대기를 고른다. `arrive` 면 랙 도착 대기(`rack_present = true`), `leave` 면 랙 떠남
     * 대기(`rack_present = false`), 다른 값이면 대기 없음, 값이 없으면 대기 대신 슬롯 단위 하나가 접힌다. 배타 가지 둘이 **같은 신호를 다른
     * 기대 값으로** 기다린다 — 한 계획에 함께 나지 않는다. [tail] 은 분기 뒤에 둘 최상위 노드다(기본은 버전 1 템플릿의 슬롯 노드).
     * [armA]·[armB] 는 첫째·둘째 가지의 노드를 바꾼다.
     */
    private fun byMode(tail: String? = PLACE, armA: String = WAIT_ARRIVAL, armB: String = WAIT_GONE): String =
        """{"schemaVersion":1,"workMasterId":"PrepareSequencedRack","maxEvidence":"E2","preferredOptionals":{},"steps":[""" +
            """{"kind":"choice","id":"mode","on":{"from":"ORDER_PARAMETER","parameter":"mode"},"arms":[""" +
            """{"equals":"arrive","steps":[$armA]},{"equals":"leave","steps":[$armB]}],"otherwise":{"steps":[]},""" +
            """"whenMissing":{"failureClass":"NO_MODE","unit":"folded","parameters":[]}}""" +
            (tail?.let { ",$it" } ?: "") + "]}"

    private val WAIT_ARRIVAL =
        """{"kind":"wait","id":"rack-arrival","signal":"rack_present","expect":"true","deadlineSeconds":60,"onDeadline":"ABORTED"}"""
    private val WAIT_GONE =
        """{"kind":"wait","id":"rack-gone","signal":"rack_present","expect":"false","deadlineSeconds":60,"onDeadline":"ABORTED"},""" +
            """{"kind":"unit","id":"folded","once":true,"skill":"pick_place","unitId":{"from":"ORDER_ID","suffix":".folded"},""" +
            """"parameters":{"object_id":{"from":"FIRST_ID","equipmentUse":"source"},"destination":{"from":"FIRST_ID","equipmentUse":"destination"}},""" +
            """"expectedIdentity":{"from":"FIRST_PROPERTY","equipmentUse":"destination","property":"material"},"source":{"from":"FIRST_ID","equipmentUse":"source"},""" +
            """"sourceObservation":"OPTIONAL","destinationObservation":"OPTIONAL","destination":{"from":"FIRST_ID","equipmentUse":"destination"}}"""
    private val PLACE =
        """{"kind":"unit","id":"place","skill":"pick_place","forEach":"destination","pairWith":{"equipmentUse":"source","property":"material"},""" +
            """"whenUnpaired":"NO_SOURCE_FOR_MATERIAL","unitId":{"from":"ITEM_ID"},""" +
            """"parameters":{"object_id":{"from":"PAIRED_ID","otherwise":""},"destination":{"from":"ITEM_ID"}},""" +
            """"expectedIdentity":{"from":"ITEM_PROPERTY","property":"material"},"source":{"from":"PAIRED_ID"},""" +
            """"sourceObservation":"OPTIONAL","destinationObservation":"OPTIONAL","destination":{"from":"ITEM_ID"}}"""

    /**
     * 셀 대역 표본에 파라미터 `mode` 를 단다. 가지 `leave` 의 once 단위가 첫 슬롯에 하나 더 놓아 자재 수가 슬롯 수와 달라지므로 자재 선언을
     * 싣지 않는다(게이트의 정합 검사는 선언이 없으면 보지 않는다).
     */
    private fun mode(value: String?): JobOrder =
        order().copy(parameters = value?.let { mapOf("mode" to it) }.orEmpty(), materialRequirements = emptyList())

    @Test
    fun `배타 가지가 같은 신호를 다른 기대 값으로 기다려도 계획된 대기만 보아 충돌로 오판하지 않고 고른 가지의 대기가 풀려 통과한다`() {
        val definition = parsed(byMode())
        // 결함 주입 대조: 정의의 대기 노드 전부(모든 가지)를 모으면 같은 신호에 기대 값이 둘이다 — 그 방식이면 이 정의는 늘 정의 실패다.
        val all = dev.picasso.middleware.mission.MissionValidator.waitNodes(definition)
        assertEquals(setOf("true", "false"), all.filter { it.signal == "rack_present" }.map { it.expect }.toSet())

        val arrive = runner().run(definition, mode("arrive"))
        assertTrue(arrive.passed, arrive.toString())
        assertEquals(listOf("rack-arrival", "RACK-204.S01", "RACK-204.S02"), arrive.units.map { it.unitId })
        assertEquals(listOf("SIGNAL", "ROBOT", "ROBOT"), arrive.units.map { it.route })
        // 가지 안 대기는 첫 pump 에서 풀린다 — 이상적 신호가 그 대기의 기대 값이다. 기한(60초)을 다 기다렸다면 ABORTED 로 실패한다.
        assertEquals("DONE", arrive.units.first().state)
        assertEquals(
            listOf(MockBranch("mode", "mode", "arms[0]", listOf("arms[1]", "otherwise"), mapOf("arms[0]" to listOf("arrive"), "arms[1]" to listOf("leave")))),
            arrive.branches,
        )

        // 다른 가지를 고른 표본은 그 가지의 대기(기대 false)를 이상적 신호로 삼아 통과한다.
        val leave = runner().run(definition, mode("leave"))
        assertTrue(leave.passed, leave.toString())
        assertEquals(listOf("rack-gone", "MOCK-7.folded", "RACK-204.S01", "RACK-204.S02"), leave.units.map { it.unitId })
        assertEquals(listOf("arms[1]"), leave.branches.map { it.chosen })
        assertEquals(listOf("arms[0]", "otherwise"), leave.branches.single().notRun)
    }

    @Test
    fun `한 계획 안의 대기 단위 둘이 같은 신호를 다른 기대 값으로 기다리면 여전히 정의 실패다`() {
        // 고른 가지(arms[1])의 대기가 분기 뒤 최상위 대기(기대 true)와 같은 신호를 다른 값(false)으로 기다린다 — 한 계획에 함께 난다.
        val definition = parsed(byMode(tail = WAIT_ARRIVAL.replace("rack-arrival", "rack-again")))
        val result = runner().run(definition, mode("leave"))
        assertEquals(MockRunFailure.DEFINITION, result.failure, result.toString())
        assertTrue("rack-gone=false" in result.detail!! && "rack-again=true" in result.detail!!, result.detail)
        // 정의 실패여도 표본이 고른 가지를 싣는다.
        assertEquals(listOf("arms[1]"), result.branches.map { it.chosen })
    }

    @Test
    fun `표본이 고른 가지가 비어 계획이 비면 돌리지 않고 빈 가지 소견의 정의 실패다`() {
        val result = runner().run(parsed(byMode(tail = null)), mode("other"))
        assertEquals(false, result.passed)
        assertEquals(MockRunFailure.DEFINITION, result.failure, result.toString())
        assertEquals("${MockRunner.EMPTY_BRANCH_DETAIL}: mode=otherwise", result.detail)
        assertEquals(listOf("arms[0]", "arms[1]"), result.branches.single().notRun)
        assertTrue(result.units.isEmpty())
    }

    @Test
    fun `값이 없어 접힌 분기는 접힌 단위가 실패라 실행 실패이고 모든 가지가 돌지 않았다고 싣는다`() {
        val result = runner().run(parsed(byMode()), mode(null))
        assertEquals(MockRunFailure.EXECUTION_FAILED, result.failure, result.toString())
        assertEquals("MOCK-7.folded", result.units.first().unitId)
        assertEquals(listOf("FAILED", "NO_MODE"), listOf(result.units.first().state, result.units.first().failureClass))
        val branch = result.branches.single()
        assertEquals("whenMissing", branch.chosen)
        assertEquals(listOf("arms[0]", "arms[1]", "otherwise"), branch.notRun)
    }

    @Test
    fun `가지마다 다른 설비군을 반복하면 고른 가지의 설비군만 든 표본으로 통과하고 고르지 않은 가지는 안 돈 가지로만 싣는다`() {
        // 가지 A 는 목적지 슬롯마다, 가지 B 는 표본에 없는 쓰임(other_rack)마다 놓는다. 분기 밖 노드는 없다.
        val other = PLACE.replace("\"id\":\"place\"", "\"id\":\"elsewhere\"").replace("\"forEach\":\"destination\"", "\"forEach\":\"other_rack\"")
        check(other != PLACE)
        val definition = parsed(byMode(tail = null, armA = "$WAIT_ARRIVAL,$PLACE", armB = "$WAIT_GONE,$other"))
        val result = runner().run(definition, mode("arrive"))
        assertTrue(result.passed, result.toString())
        assertEquals(listOf("rack-arrival", "RACK-204.S01", "RACK-204.S02"), result.units.map { it.unitId })
        assertEquals(listOf("arms[0]" to listOf("arms[1]", "otherwise")), result.branches.map { it.chosen to it.notRun })

        // 고른 가지의 반복 대상이 표본에 없으면 여전히 정의 실패다(표본이 B 를 고르고 other_rack 이 없다).
        val chosenAbsent = runner().run(definition, mode("leave"))
        assertEquals(MockRunFailure.DEFINITION, chosenAbsent.failure, chosenAbsent.toString())
        assertTrue("other_rack" in chosenAbsent.detail!!, chosenAbsent.detail)
        assertEquals(listOf("arms[1]"), chosenAbsent.branches.map { it.chosen })
    }

    @Test
    fun `분기 자리는 정확한 열쇠로 가르므로 최상위 분기 id 가 그룹 안 분기의 자리 모양과 겹쳐도 선택이 섞이지 않는다`() {
        // 그룹 안 분기 how(자리 how[설비 id]) 뒤에 id 가 how[T1] 인 최상위 분기를 둔다. 표본의 대상은 T2 하나다.
        val extra = """{"kind":"unit","id":"extra","once":true,"skill":"inspect","unitId":{"from":"ORDER_ID","suffix":".extra"},"parameters":{"target":{"from":"ORDER_ID"}}}"""
        val top = """{"kind":"choice","id":"how[T1]","on":{"from":"ORDER_PARAMETER","parameter":"mode"},"arms":[{"equals":"x","steps":[$extra]}],""" +
            """"otherwise":{"steps":[]},"whenMissing":{"failureClass":"NO_MODE","unit":"extra","parameters":[]}}"""
        val definition = parsed(byItem().removeSuffix("]}") + ",$top]}")
        val order = items("T2" to "visual").copy(parameters = mapOf("mode" to "zzz"))
        val result = runner().run(definition, order)
        assertTrue(result.passed, result.toString())
        assertEquals(listOf("T2.travel", "T2.look"), result.units.map { it.unitId })
        // 접두사로 고르면 분기 how 가 최상위 분기의 자리 how[T1] 까지 제 것으로 싣는다.
        assertEquals(
            listOf(Triple("how", "how[T2]", "arms[1]"), Triple("how[T1]", "how[T1]", "otherwise")),
            result.branches.map { Triple(it.choiceId, it.site, it.chosen) },
        )
    }

    /** 그룹 안 분기의 시험 자원(화면 «대상마다 이동 뒤 종류별 점검» 템플릿과 같은 글자, picasso `MissionFixtures.INSPECT_BY_ITEM` 모양). */
    private fun byItem(): String = HostBench.JSON.readTree(javaClass.getResourceAsStream("/mission-profile-choice.json"))["definition"].toString()

    private fun items(vararg targets: Pair<String, String?>) = JobOrder(
        jobOrderId = "MOCK-12", workMasterId = "AssetInspectByItem", version = 1, requiredEvidence = Evidence.E0,
        equipmentRequirements = targets.map { (id, item) ->
            EquipmentRequirement(id, "mission_target", mapOf("location" to "bay-$id") + (item?.let { mapOf("item" to it) }.orEmpty()))
        },
    )

    @Test
    fun `그룹 안 분기는 대상마다 고른 가지만 돌리고 자리마다 돈 가지와 안 돈 가지를 싣는다`() {
        val result = runner().run(parsed(byItem()), items("T1" to "thermal", "T2" to "visual", "T3" to "acoustic"))
        assertTrue(result.passed, result.toString())
        assertEquals(listOf("T1.travel", "T1", "T2.travel", "T2.look", "T3.travel"), result.units.map { it.unitId })
        assertEquals(
            listOf("how[T1]" to "arms[0]", "how[T2]" to "arms[1]", "how[T3]" to "otherwise"),
            result.branches.map { it.site to it.chosen },
        )
        assertEquals(listOf(listOf("arms[1]", "otherwise"), listOf("arms[0]", "otherwise"), listOf("arms[0]", "arms[1]")), result.branches.map { it.notRun })
        assertTrue(result.branches.all { it.choiceId == "how" })

        // 대상이 없으면 분기 자리가 하나도 서지 않는다 — 모든 가지가 돌지 않았다고 한 줄 싣는다(계획이 비어 정의 실패).
        val none = runner().run(parsed(byItem()), items())
        assertEquals(MockRunFailure.DEFINITION, none.failure, none.toString())
        assertEquals(listOf(MockBranch("how", "how", null, listOf("arms[0]", "arms[1]", "otherwise"), mapOf("arms[0]" to listOf("thermal"), "arms[1]" to listOf("visual")))), none.branches)
    }

    @Test
    fun `분기가 없는 정의는 분기 표시가 비어 있다`() {
        val result = runner().run(parsed(template(MissionTemplates.ARRIVAL_WAIT)), order())
        assertTrue(result.passed, result.toString())
        assertTrue(result.branches.isEmpty(), result.branches.toString())
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
