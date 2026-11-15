package dev.picasso.ops.host.mission

import dev.picasso.harness.Harness
import dev.picasso.middleware.CellSignals
import dev.picasso.middleware.ClientRobotPort
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.EquipmentUse
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.MaterialRequirement
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.MissionCatalog
import dev.picasso.middleware.NamedSignal
import dev.picasso.middleware.PhysicalState
import dev.picasso.middleware.Route
import dev.picasso.middleware.SlotSignal
import dev.picasso.middleware.UnitState
import dev.picasso.middleware.Unassigned
import dev.picasso.middleware.mission.DefinedCapability
import dev.picasso.middleware.mission.EachStep
import dev.picasso.middleware.mission.MissionDefinition
import dev.picasso.middleware.mission.MissionValidator
import dev.picasso.middleware.mission.UnitStep
import dev.picasso.middleware.mission.WaitStep
import dev.picasso.ops.host.cell.CellSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * 모의 실행 실패의 하위 범주(S3b 스펙 §6.4). 화면이 이유를 이 이름으로 가른다.
 */
enum class MockRunFailure {
    /**
     * 정의: 돌리기 전 검사에서 걸렸다. 표본으로 세운 계획이 비었거나 단위 id 가 겹친다, 표본 작업 지시에 없는 반복 대상(최상위 단위
     * 노드·반복 그룹의 `forEach`)이 있다, 같은 신호를 기대 값이 다른 대기 노드 둘이 써 이상적 현장의 신호 값을 하나로 정할 수 없다.
     * 어느 것인지는 [MockRunResult.detail] 이 말한다.
     */
    DEFINITION,

    /** 표본 작업 지시를 미들웨어가 받지 않았다(근거 등급 초과, 게이트 거부). 실행이 서지 않았다. */
    SUBMISSION_REJECTED,

    /** 가상 시간 상한 안에 실행이 정착하지 않았다. */
    NOT_SETTLED,

    /** 실제 시간 상한을 넘겼다. 요청 안에서 동기로 도므로 운영 서비스의 요청 제한보다 짧게 둔다(T10). */
    WALL_CLOCK_LIMIT,

    /** 실행이 정착했으나 모든 단위 완료가 아니다(단위가 실패, 중단, 미확인으로 정착). */
    EXECUTION_FAILED,

    /**
     * 정의: 플릿 경로(`route: FLEET`) 노드가 있다. 모의 실행 하네스에는 플릿 포트가 없어(picasso 의 플릿 미믹은 시험 소스에만 있다)
     * 그 노드는 돌 곳이 없으므로 돌리지 않는다. 실패라서 활성화는 그 초안을 받지 않는다(`MOCK_RUN_REQUIRED`) — 실행 호스트의
     * 미들웨어에도 플릿 포트가 없어(`AmrFleetPort.None`) 활성화해도 운반이 맡겨지지 않는다(picasso §15.325).
     */
    FLEET_ROUTE,
}

/** 모의 실행의 단위 하나. [reached] 는 근거 등급이고 [failureClass] 는 실패면 그 분류다. */
data class MockUnit(
    val unitId: String,
    val route: String,
    val skillType: String,
    val state: String,
    val reached: String,
    val failureClass: String?,
)

/** 모의 실행에 쓴 표본 작업 지시의 요점. 슬롯은 셀 대역 스냅숏에서 앞에서부터 고른다. */
data class MockSample(
    val jobOrderId: String,
    val requiredEvidence: String,
    val slots: List<String>,
    val material: String,
    val presentation: String,
    val equipment:List<EquipmentRequirement> = emptyList(),
)

/**
 * 모의 실행 결과(S3b 스펙 §6.4). 모의 실행 표의 `result` 칸에 이 모양 그대로 JSON 으로 남는다.
 *
 * @param passed 실행이 모든 단위 완료로 정착했다.
 * @param failure 통과가 아니면 하위 범주. 통과면 `null`.
 * @param detail 실패의 사람용 설명. 통과면 `null`.
 * @param physicalState 실행의 마지막 물리 상태. 실행이 서지 않았으면 `null`.
 * @param virtualElapsedSeconds 표본 작업 지시를 낸 뒤 흐른 가상 시간(초).
 * @param wallElapsedMillis 실제로 걸린 시간(밀리초).
 */
data class MockRunResult(
    val passed: Boolean,
    val failure: MockRunFailure?,
    val detail: String?,
    val robotId: String,
    val sample: MockSample,
    val physicalState: String?,
    val units: List<MockUnit>,
    val virtualElapsedSeconds: Long,
    val wallElapsedMillis: Long,
)

/**
 * 모의 실행기(S3b 스펙 §6.4, 결정 1, T8). 호스트 프로세스 안에 별도 mimic(harness `Harness`, 가상 시계, 기체 하나)과
 * 별도 미들웨어를 세워 초안 정의로 표본 작업 지시 하나를 끝까지 돌린다. 현장 기체는 건드리지 않는다.
 *
 * - **격리**: mimic 엔진 잠금은 인스턴스마다 따로다. 현장 mimic(다른 프로세스)·호스트 미들웨어·호스트 잠금과 섞이지 않는다.
 *   하네스에 적재 지점과 핸드셰이크 보고를 붙이지 않아 registry 에 아무것도 닿지 않는다.
 * - **시계**: 미들웨어의 `now` 는 하네스의 가상 시계다. 어긋나면 E2 시간 윈도우가 갈린다.
 * - **이상적 현장**(T8): 목적지 슬롯은 작업 지시를 받기 전에는 비었고 받은 뒤에는 점유이며 기대 자재를 내고 관측 시각은
 *   `null`(읽은 순간)이다. 출발 자리는 점유이고 그 자리의 자재를 낸다. 그 밖의 자리는 `null`(침묵)이다. 이름 있는 신호는
 *   대기 노드의 기대 값을 낸다. 그래서 대기 노드는 첫 pump 에서 완료된다. 기체 프로파일은 현장 humanoid 프로파일에서 실패
 *   모드를 뺀 사본이다.
 * - **진행 루프**: pump → 정착했으면 끝 → 상한 확인 → 가상 시계를 [STEP] 민다 → 스트림 갱신이 닿을 틈([PAUSE]) → 다시 pump.
 *
 * 모의 실행은 정의가 끝까지 도는지를 보며 현장 사실(신호가 실제로 오는가, 자재가 맞는가)을 보증하지 않는다.
 *
 * @param profile 모의 실행용 기체 프로파일. 저장소 루트 기준 경로를 기동 설정이 풀어 준다.
 * @param schema 프로파일 스키마. 하네스가 기동 때 프로파일을 이것으로 검증한다.
 * @param virtualLimit 가상 시간 상한. 이 안에 정착하지 않으면 [MockRunFailure.NOT_SETTLED].
 * @param wallLimit 실제 시간 상한. 넘기면 [MockRunFailure.WALL_CLOCK_LIMIT].
 */
class MockRunner(
    private val profile: Path,
    private val schema: Path,
    private val virtualLimit: Duration,
    private val wallLimit: Duration = WALL_LIMIT,
) {
    init {
        require(Files.isRegularFile(profile)) { "모의 실행 프로파일이 없다: $profile" }
        require(Files.isRegularFile(schema)) { "프로파일 스키마가 없다: $schema" }
        require(!virtualLimit.isNegative && !virtualLimit.isZero) { "가상 시간 상한이 양수가 아니다: $virtualLimit" }
    }

    /** 검증을 지난 [definition] 으로 [order] 를 돌린다. */
    fun run(definition: MissionDefinition, order: JobOrder): MockRunResult {
        val sample = sample(order)
        val wallStart = System.nanoTime()
        fun wall(): Duration = Duration.ofNanos(System.nanoTime() - wallStart)

        val fleet = MissionValidator.unitNodes(definition).filter { it.route == Route.FLEET }
        if (fleet.isNotEmpty()) {
            return MockRunResult(
                passed = false, failure = MockRunFailure.FLEET_ROUTE, detail = "$FLEET_ROUTE_DETAIL: ${fleet.joinToString(", ") { it.id }}",
                robotId = ROBOT_ID, sample = sample, physicalState = null, units = emptyList(), virtualElapsedSeconds = 0,
                wallElapsedMillis = wall().toMillis(),
            )
        }
        val planned=DefinedCapability(definition).plan(order)
        val missing = repeatedUses(definition).filter { use -> order.equipmentRequirements.none { it.equipmentUse == use } }
        if(planned.isEmpty() || planned.map{it.unitId}.distinct().size!=planned.size || missing.isNotEmpty())
            return MockRunResult(false,MockRunFailure.DEFINITION,"샘플 입력에서 실행 단계가 비거나 단위 ID가 겹칩니다"+(if(missing.isEmpty()) "" else ": 표본에 없는 반복 대상 ${missing.joinToString(", ")}"),ROBOT_ID,sample,null,emptyList(),0,wall().toMillis())
        val ideal = idealSignals(definition)
            ?: return MockRunResult(
                passed = false, failure = MockRunFailure.DEFINITION, detail = conflict(definition), robotId = ROBOT_ID,
                sample = sample, physicalState = null, units = emptyList(), virtualElapsedSeconds = 0, wallElapsedMillis = wall().toMillis(),
            )

        Harness(mapOf(ROBOT_ID to profile), schema = schema).use { harness ->
            val cell = IdealCell(order, ideal)
            val middleware = Middleware(
                robots = ClientRobotPort(harness.client(CLIENT_ID)),
                cell = cell,
                now = { harness.clock.now() },
                missions = MissionCatalog.of(listOf(DefinedCapability(definition))),
            )
            val start = harness.clock.now()
            fun virtual(): Duration = Duration.between(start, harness.clock.now())
            fun result(failure: MockRunFailure?, detail: String?, execution: Middleware.Execution?) = MockRunResult(
                passed = failure == null,
                failure = failure,
                detail = detail,
                robotId = ROBOT_ID,
                sample = sample,
                physicalState = execution?.physicalState?.name,
                units = execution?.units.orEmpty().map {
                    MockUnit(it.unitId, it.route.name, it.skillType, it.state.name, it.reached.name, it.failureClass)
                },
                virtualElapsedSeconds = virtual().seconds,
                wallElapsedMillis = wall().toMillis(),
            )

            val execution = when (val submission = middleware.submit(order, ROBOT_ID)) {
                is Middleware.Submission.Accepted -> submission.execution
                is Middleware.Submission.Idempotent -> submission.execution
                is Middleware.Submission.Rejected ->
                    return result(MockRunFailure.SUBMISSION_REJECTED, "표본 작업 지시를 받지 않았다: ${submission.reason}", null)
                is Unassigned ->
                    return result(MockRunFailure.SUBMISSION_REJECTED, "표본 작업 지시를 배정하지 못했다: ${submission.refusals}", null)
            }
            cell.accepted = true

            while (true) {
                middleware.pump()
                if (execution.physicalState.isSettled) break
                if (virtual() >= virtualLimit) {
                    return result(MockRunFailure.NOT_SETTLED, "가상 시간 ${virtualLimit.seconds}초 안에 종결하지 않았다", execution)
                }
                if (wall() >= wallLimit) {
                    return result(MockRunFailure.WALL_CLOCK_LIMIT, "실제 시간 ${wallLimit.toMillis()}밀리초 안에 끝나지 않았다", execution)
                }
                harness.advance(STEP)
                Thread.sleep(PAUSE.toMillis())
            }

            val done = execution.physicalState == PhysicalState.PHYSICALLY_DONE && execution.units.all { it.state == UnitState.DONE }
            return if (done) {
                result(null, null, execution)
            } else {
                val stuck = execution.units.filter { it.state != UnitState.DONE }
                    .joinToString(", ") { "${it.unitId}=${it.state.name}${it.failureClass?.let { c -> "($c)" } ?: ""}" }
                result(MockRunFailure.EXECUTION_FAILED, "실행이 ${execution.physicalState.name} 로 종결했다: $stuck", execution)
            }
        }
    }

    /**
     * 표본 작업 지시에 있어야 하는 반복 대상(설비 쓰임). 최상위 단위 노드의 `forEach` 와 반복 그룹(`EachStep`)의 `forEach` 다.
     * `once` 노드와 그룹 안쪽 노드는 `forEach` 가 없어(널) 빠진다(picasso §15.325, `MissionValidator.unitNodes`).
     */
    private fun repeatedUses(definition: MissionDefinition): List<String> =
        (MissionValidator.unitNodes(definition).filter { !it.once }.mapNotNull(UnitStep::forEach) +
            definition.steps.filterIsInstance<EachStep>().map { it.forEach }).distinct()

    /**
     * 신호마다 이상적 값. 같은 신호를 기대 값이 다른 대기 노드가 쓰면 하나로 정할 수 없어 `null` 이다.
     */
    private fun idealSignals(definition: MissionDefinition): Map<String, String>? {
        val expects = definition.steps.filterIsInstance<WaitStep>().groupBy({ it.signal }, { it.expect })
        if (expects.values.any { it.distinct().size > 1 }) return null
        return expects.mapValues { (_, values) -> values.first() }
    }

    private fun conflict(definition: MissionDefinition): String =
        definition.steps.filterIsInstance<WaitStep>().groupBy { it.signal }
            .filterValues { waits -> waits.map { it.expect }.distinct().size > 1 }
            .entries.joinToString("; ") { (signal, waits) ->
                "신호 $signal 을 기대 값이 다른 대기 노드가 쓴다: ${waits.joinToString(", ") { "${it.id}=${it.expect}" }}"
            }

    private fun sample(order: JobOrder): MockSample {
        val source = order.equipmentRequirements.firstOrNull { it.equipmentUse == EquipmentUse.SOURCE }
        return MockSample(
            jobOrderId = order.jobOrderId,
            requiredEvidence = order.requiredEvidence.name,
            slots = order.equipmentRequirements.filter { it.equipmentUse == EquipmentUse.DESTINATION }.map { it.id },
            material = source?.properties?.get(EquipmentUse.PROP_MATERIAL)?:"",
            presentation = source?.id?:"",
            equipment=order.equipmentRequirements,
        )
    }

    /**
     * 이상적 현장(T8). 표본 작업 지시의 목적지 슬롯은 작업 지시를 받기 전([accepted] 가 거짓)에는 비었다고 말하고, 받은 뒤에는 점유이며
     * 그 슬롯의 기대 자재를 낸다. 관측 시각은 `null` 이라 읽은 순간이 시각이므로 E2 시간 윈도우 안에 든다. 미들웨어가 목적지를 묻는
     * 때는 배정 게이트(받기 전)과 태스크를 낸 뒤의 근거 확인뿐이라, 받기 전 비움·받은 뒤 점유가 곧 작업 전 비움·완료 뒤 점유다. 그래서
     * 목적지 관측을 요구한 단위(`REQUIRED`)도 게이트를 지나고 근거는 오늘처럼 선다. 출발 자리(`source`)는 점유이고 그 자리의 자재를
     * 낸다 — 이상적 현장에서는 설비가 출발 자리의 신원을 말하므로 출발 관측을 요구한 단위(`REQUIRED`)도 게이트를 지난다. 그 밖의 자리는
     * `null`(침묵)이다. 이름 있는 신호는 대기 노드의 기대 값이다.
     */
    private class IdealCell(order: JobOrder, private val ideal: Map<String, String>) : CellSignals {
        /** 표본 작업 지시를 미들웨어가 받았다. 그 전에는 목적지가 비었다. */
        @Volatile
        var accepted = false

        private val destinations: Map<String, String?> = order.equipmentRequirements
            .filter { it.equipmentUse == EquipmentUse.DESTINATION }
            .associate { it.id to it.properties[EquipmentUse.PROP_MATERIAL] }

        private val sources: Map<String, String?> = order.equipmentRequirements
            .filter { it.equipmentUse == EquipmentUse.SOURCE }
            .associate { it.id to it.properties[EquipmentUse.PROP_MATERIAL] }

        override fun observe(location: String): SlotSignal? = when (location) {
            in destinations -> if (accepted) SlotSignal(true, destinations[location], null) else SlotSignal(false, null, null)
            in sources -> SlotSignal(true, sources[location], null)
            else -> null
        }

        override fun signal(name: String): NamedSignal? = ideal[name]?.let { NamedSignal(it, null) }
    }

    companion object {
        /** 모의 실행의 기체 id. 현장 기체 id 와 겹치지 않게 둔다. */
        const val ROBOT_ID = "mock-01"

        /** 플릿 경로 노드를 든 정의의 소견([MockRunFailure.FLEET_ROUTE]). 뒤에 그 노드 id 가 붙는다. */
        const val FLEET_ROUTE_DETAIL = "플릿 경로 노드는 모의 실행할 수 없음(모의 실행 하네스에 플릿 포트가 없다)"

        /** 하네스 mimic 에 싣는 클라이언트 id. */
        const val CLIENT_ID = "mission-host-mock-run"

        /** 표본 작업 지시의 슬롯 수. 통합 시나리오의 작업 지시 하나와 같은 둘이다. */
        const val SAMPLE_SLOTS = 2

        /** 실제 시간 상한(T10). 운영 서비스의 모의 실행 요청 제한(60초)보다 짧다. */
        val WALL_LIMIT: Duration = Duration.ofSeconds(30)

        /** 진행 루프가 한 번에 미는 가상 시간. */
        val STEP: Duration = Duration.ofSeconds(5)

        /** 민 뒤 스트림 갱신이 클라이언트에 닿을 틈. picasso 시험의 진행 루프와 같은 까닭이다. */
        val PAUSE: Duration = Duration.ofMillis(10)

        /**
         * 마지막 셀 대역 스냅숏으로 만든 표본 작업 지시(S3b 스펙 §6.4). 호스트는 현장 모듈의 픽스처를 모르므로 스냅숏의
         * 슬롯을 앞에서부터 [SAMPLE_SLOTS] 개, 자재를 든 첫 제시 자리를 쓴다. 요구 근거는 E2 이고 `materialRequirements` 는
         * 자재별 슬롯 수라 게이트의 정합 검사를 지난다. 슬롯이나 자재를 든 제시 자리가 없으면 `null` 이다.
         */
        fun sampleOrder(workMasterId: String, draftId: Long, cell: CellSnapshot): JobOrder? {
            val slots = cell.slots.take(SAMPLE_SLOTS).map { it.id }
            val source = cell.presentations.firstOrNull { it.material != null } ?: return null
            if (slots.isEmpty()) return null
            val material = source.material!!
            return JobOrder(
                jobOrderId = "MOCK-$draftId",
                workMasterId = workMasterId,
                version = 1,
                requiredEvidence = Evidence.E2,
                materialRequirements = listOf(MaterialRequirement(material, slots.size)),
                equipmentRequirements = slots.map {
                    EquipmentRequirement(it, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to material))
                } + EquipmentRequirement(source.id, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to material)),
            )
        }
    }
}
