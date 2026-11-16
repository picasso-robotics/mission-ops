package dev.picasso.ops.host.mission

import dev.picasso.fleetmimic.AmrFleetMimic
import dev.picasso.harness.Harness
import dev.picasso.middleware.CellSignals
import dev.picasso.middleware.ClientRobotPort
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.EquipmentUse
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.ExecutionUnit
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
import dev.picasso.middleware.WaitSpec
import dev.picasso.middleware.mission.ChoiceStep
import dev.picasso.middleware.mission.DefinedCapability
import dev.picasso.middleware.mission.EachStep
import dev.picasso.middleware.mission.MissionDefinition
import dev.picasso.middleware.mission.MissionValidator
import dev.picasso.middleware.mission.UnitStep
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
     * 노드·반복 그룹의 `forEach`, 최상위 분기는 표본이 고른 가지 안 노드)이 있다, 플릿 경로 단위의 용기·출발지·목적지를 표본이 채우지 못했다(표본에
     * 용기 속성이 없다 등), 표본의 계획에 든 대기 단위 둘이 같은 신호를 다른 기대 값으로 기다려 이상적 현장의 신호 값을 하나로 정할 수 없다,
     * 표본이 고른 분기 가지가 모두 비어 돌릴 단위가 없다(picasso §15.328). 어느 것인지는 [MockRunResult.detail] 이 말한다.
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
 * 모의 실행의 분기 자리 하나(picasso §15.328). 분기는 계획 때 표본 작업 지시의 값으로 가지 하나를 고르고 **고른 가지만** 돈다. 그래서
 * 모의 실행의 통과는 [chosen] 가지의 것이고 [notRun] 의 가지는 이 모의 실행으로 검증되지 않았다.
 *
 * @param site 분기 자리 — 최상위 분기는 분기 id, 반복 그룹 안 분기는 `분기 id[설비 id]`(picasso `DefinedCapability.choices`). 표본이 그
 *   분기에 자리를 하나도 열지 않았으면(그룹의 대상이 표본에 없다, 그룹이 조건부 포함으로 접혔다) 분기 id 이고 [chosen] 이 널이다.
 * @param choiceId 분기 노드 id.
 * @param chosen 표본이 고른 가지 — `arms[i]`·`otherwise`·`whenMissing`(값이 없어 접힘). 자리가 없으면 널.
 * @param notRun 표본이 고르지 않아 돌지 않은 가지(`arms[i]`·`otherwise`, 선언 순서). 검증되지 않은 가지다.
 * @param armValues 가지 `arms[i]` 마다 맞는 값(`equals`·`in`). 화면이 가지를 사람이 읽게 적는 데 쓴다.
 */
data class MockBranch(
    val site: String,
    val choiceId: String,
    val chosen: String?,
    val notRun: List<String>,
    val armValues: Map<String, List<String>>,
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
 * @param branches 정의의 분기 자리마다 표본이 고른 가지와 돌지 않은 가지(picasso §15.328). 분기가 없는 정의는 빈 목록이다. 정의 실패로
 *   돌리지 않았어도 표본의 계획이 고른 가지를 싣는다. 이 칸이 없던 옛 행은 빈 목록으로 읽는다.
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
    val branches: List<MockBranch> = emptyList(),
)

/**
 * 모의 실행기(S3b 스펙 §6.4, 결정 1, T8). 호스트 프로세스 안에 별도 mimic(harness `Harness`, 가상 시계, 기체 하나)과
 * 별도 플릿 미믹(picasso `fleet-mimic` 의 `AmrFleetMimic`, 요청마다 새것)과 별도 미들웨어를 세워 초안 정의로 표본 작업 지시 하나를
 * 끝까지 돌린다. 현장 기체는 건드리지 않는다.
 *
 * - **격리**: mimic 엔진 잠금은 인스턴스마다 따로다. 현장 mimic(다른 프로세스)·호스트 미들웨어·호스트 잠금과 섞이지 않는다.
 *   하네스에 적재 지점과 핸드셰이크 보고를 붙이지 않아 registry 에 아무것도 닿지 않는다.
 * - **시계**: 미들웨어의 `now` 는 하네스의 가상 시계다. 어긋나면 E2 시간 윈도우가 갈린다.
 * - **이상적 현장**(T8): 목적지 슬롯은 작업 지시를 받기 전에는 비었고 받은 뒤에는 점유이며 기대 자재를 내고 관측 시각은
 *   `null`(읽은 순간)이다. 출발 자리는 점유이고 그 자리의 자재를 낸다. 그 밖의 자리는 `null`(침묵)이다. 이름 있는 신호는
 *   표본의 계획에 든 대기 단위의 기대 값을 낸다(고르지 않은 분기 가지의 대기 노드는 보지 않는다). 그래서 대기 단위는 첫 pump 에서 완료된다. 기체 프로파일은 현장 humanoid 프로파일에서 실패
 *   모드를 뺀 사본이다.
 * - **플릿 경로**: 계획의 플릿 단위마다 그 출발지에 기대 용기를 둔다(표본의 출발 설비 속성, 정의의 `FIRST_PROPERTY source container`
 *   등이 채운 값). 플릿 단위의 출발지·목적지는 이상적 현장이 플릿 세계를 그대로 본다([AmrFleetMimic.observe]) — 목적지에 용기가
 *   놓이면 놓인 시각과 함께 그 용기를 말하므로 플릿의 완료(E1)에 인계 설비의 근거(E2)가 선다. 표본이 용기·출발지·목적지를 채우지
 *   못한 플릿 단위가 있으면 돌리지 않고 [MockRunFailure.DEFINITION] 이다.
 * - **분기**(picasso §15.328): 표본의 계획이 고른 가지만 돈다. 결과의 [MockRunResult.branches] 가 분기 자리마다 돈 가지와 돌지 않은 가지를
 *   싣는다 — 통과는 돈 가지의 것이고 돌지 않은 가지는 검증되지 않았다. 표본 검사도 고른 가지의 반복 대상만 요구한다. 활성화 관문은
 *   가지별 표본을 요구하지 않는다.
 * - **진행 루프**: pump → 정착했으면 끝 → 상한 확인 → 가상 시계를 [STEP] 민다 → 플릿 미믹을 한 걸음 민다 → 스트림 갱신이 닿을
 *   틈([PAUSE]) → 다시 pump.
 *
 * 모의 실행은 정의가 끝까지 도는지를 보며 현장 사실(신호가 실제로 오는가, 자재가 맞는가)을 보증하지 않는다. **이 실행 호스트에서
 * 돈다는 것도 보증하지 않는다** — 실행 호스트의 미들웨어에는 플릿 포트가 없어(`AmrFleetPort.None`) 플릿 경로 정의의 통과는
 * 활성화로 이어지지 않는다([MissionVersions.FLEET_PORT_ABSENT]).
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

        // 계획은 한 번만 짓는다 — 표본 검사·이상적 신호·신호 충돌·분기 표시가 모두 이 계획 하나를 본다(picasso §15.328). 분기는 계획 때
        // 가지 하나를 고르므로 정의의 노드가 아니라 계획된 단위가 이 표본으로 도는 것이다.
        val capability = DefinedCapability(definition)
        val planned = capability.plan(order)
        val choices = capability.choices(order)
        val branches = branches(definition, choices, order)
        fun refused(detail: String) = MockRunResult(
            passed = false, failure = MockRunFailure.DEFINITION, detail = detail, robotId = ROBOT_ID, sample = sample, physicalState = null,
            units = emptyList(), virtualElapsedSeconds = 0, wallElapsedMillis = wall().toMillis(), branches = branches,
        )
        val missing = repeatedUses(definition, choices).filter { use -> order.equipmentRequirements.none { it.equipmentUse == use } }
        if (planned.isEmpty() && branches.any { it.chosen != null } && missing.isEmpty()) {
            return refused("$EMPTY_BRANCH_DETAIL: ${branches.filter { it.chosen != null }.joinToString(", ") { "${it.site}=${it.chosen}" }}")
        }
        if (planned.isEmpty() || planned.map { it.unitId }.distinct().size != planned.size || missing.isNotEmpty()) {
            return refused("샘플 입력에서 실행 단계가 비거나 단위 ID가 겹칩니다" + if (missing.isEmpty()) "" else ": 표본에 없는 반복 대상 ${missing.joinToString(", ")}")
        }
        val fleetUnits = planned.filter { it.route == Route.FLEET }
        val unfilled = fleetUnits.filter { it.expectedIdentity == null || it.source == null || it.destination == null }
        if (unfilled.isNotEmpty()) return refused("$FLEET_SAMPLE_DETAIL: ${unfilled.joinToString(", ", transform = ::unfilledOf)}")
        val waits = plannedWaits(planned)
        val ideal = idealSignals(waits) ?: return refused(conflict(waits))

        Harness(mapOf(ROBOT_ID to profile), schema = schema).use { harness ->
            // 요청마다 새 플릿 세계다. 수동 진행이라 진행 루프가 가상 시계를 민 뒤에만 걷는다 — 놓인 시각이 그 가상 시각이다.
            val fleet = AmrFleetMimic(now = { harness.clock.now() })
            fleetUnits.forEach { fleet.place(it.source!!, it.expectedIdentity!!) }
            val robotUnits = planned.filter { it.route == Route.ROBOT }
            val cell = IdealCell(
                order, ideal, fleet,
                fleetLocations = fleetUnits.flatMap { listOf(it.source!!, it.destination!!) }.toSet(),
                robotSources = robotUnits.mapNotNull { it.source }.toSet(),
                robotLocations = robotUnits.flatMap { listOfNotNull(it.source, it.destination) }.toSet(),
            )
            val middleware = Middleware(
                robots = ClientRobotPort(harness.client(CLIENT_ID)),
                cell = cell,
                fleet = fleet,
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
                branches = branches,
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
                fleet.tick()
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
     * 표본 작업 지시에 있어야 하는 반복 대상(설비 쓰임) — **이 표본으로 도는 노드**의 것만이다. 분기 밖 최상위 단위 노드의 `forEach`,
     * 반복 그룹(`EachStep`)의 `forEach`, 그리고 최상위 분기는 표본이 고른 가지([choices], picasso `DefinedCapability.choices`) 안 단위 노드의
     * `forEach` 다. 고르지 않은 가지의 반복 대상은 요구하지 않는다 — 가지마다 다른 설비군을 반복하는 정의는 한 가지만 든 표본이 유효하고,
     * 고르지 않은 가지는 [MockBranch.notRun](돌지 않은 가지, 검증 안 됨)으로만 보인다(picasso §15.328). 값이 없어 접힌 분기(`whenMissing`)도
     * 가지를 고르지 않아 요구하지 않는다. `once` 노드와 그룹 안쪽 노드(그룹 안 분기의 가지 포함)는 `forEach` 가 없어(널) 빠진다(picasso §15.325).
     */
    private fun repeatedUses(definition: MissionDefinition, choices: Map<String, String>): List<String> =
        definition.steps.flatMap { step ->
            when (step) {
                is UnitStep -> listOf(step)
                is ChoiceStep -> choices[step.id]?.let(step::steps).orEmpty().filterIsInstance<UnitStep>()
                else -> emptyList()
            }.filter { !it.once }.mapNotNull(UnitStep::forEach) + listOfNotNull((step as? EachStep)?.forEach)
        }.distinct()

    /**
     * 정의의 분기마다 표본이 고른 가지와 돌지 않은 가지([MockBranch]). [choices] 는 같은 표본의 `DefinedCapability.choices` 다(자리 →
     * 가지). 분기는 최상위와 반복 그룹 안에만 온다(중첩 없음). 자리 열쇠는 picasso 의 형식대로 **정확히** 짓는다 — 최상위 분기는 분기 id
     * 그대로, 그룹 안 분기는 그 그룹이 반복하는 표본 설비마다 `분기 id[설비 id]`. 접두사로 고르면 분기 id 가 `how` 와 `how[x]` 처럼 겹칠 때
     * 다른 분기의 선택이 섞인다. 자리를 하나도 열지 않은 분기는 모든 가지가 돌지 않은 것으로 한 줄 싣는다.
     */
    private fun branches(definition: MissionDefinition, choices: Map<String, String>, order: JobOrder): List<MockBranch> {
        val nodes: List<Pair<ChoiceStep, List<String>>> = definition.steps.flatMap { step ->
            when (step) {
                is ChoiceStep -> listOf(step to listOf(step.id))
                is EachStep -> {
                    val items = order.equipmentRequirements.filter { it.equipmentUse == step.forEach }.map { it.id }.distinct()
                    step.steps.filterIsInstance<ChoiceStep>().map { choice -> choice to items.map { "${choice.id}[$it]" } }
                }
                else -> emptyList()
            }
        }
        return nodes.flatMap { (choice, keys) ->
            val names = choice.arms.indices.map { "arms[$it]" } + ChoiceStep.OTHERWISE
            val values = choice.arms.withIndex().associate { (index, arm) -> "arms[$index]" to arm.values }
            val sites = keys.mapNotNull { key -> choices[key]?.let { key to it } }
            if (sites.isEmpty()) {
                listOf(MockBranch(choice.id, choice.id, null, names, values))
            } else {
                sites.map { (site, chosen) -> MockBranch(site, choice.id, chosen, names - chosen, values) }
            }
        }
    }

    /** 계획에 든 설비 대기 단위(`route == SIGNAL`)와 그 대기 사양. 고르지 않은 분기 가지의 대기 노드는 계획에 없어 들지 않는다. */
    private fun plannedWaits(planned: List<ExecutionUnit>): List<Pair<String, WaitSpec>> =
        planned.filter { it.route == Route.SIGNAL }.mapNotNull { unit -> unit.wait?.let { unit.unitId to it } }

    /** 표본이 채우지 못한 플릿 단위 하나의 소견 — 단위 id 와 비어 있는 칸. */
    private fun unfilledOf(unit: ExecutionUnit): String =
        unit.unitId + "(" + listOfNotNull(
            "용기 없음".takeIf { unit.expectedIdentity == null },
            "출발지 없음".takeIf { unit.source == null },
            "목적지 없음".takeIf { unit.destination == null },
        ).joinToString(", ") + ")"

    /**
     * 신호마다 이상적 값. **표본의 계획에 든 대기 단위만** 본다([plannedWaits]) — 정의의 대기 노드 전부(`MissionValidator.waitNodes`)를
     * 모으면 배타 가지가 같은 신호를 다른 기대 값으로 기다릴 때(한 계획에 함께 나지 않는다) 충돌로 오판한다(picasso §15.328). 계획에
     * 든 대기 단위 둘이 같은 신호를 다른 기대 값으로 기다리면 하나로 정할 수 없어 `null` 이다.
     */
    private fun idealSignals(waits: List<Pair<String, WaitSpec>>): Map<String, String>? {
        val expects = waits.groupBy({ it.second.signal }, { it.second.expect })
        if (expects.values.any { it.distinct().size > 1 }) return null
        return expects.mapValues { (_, values) -> values.first() }
    }

    private fun conflict(waits: List<Pair<String, WaitSpec>>): String =
        waits.groupBy { it.second.signal }
            .filterValues { same -> same.map { it.second.expect }.distinct().size > 1 }
            .entries.joinToString("; ") { (signal, same) ->
                "신호 $signal 을 기대 값이 다른 대기 노드가 쓴다: ${same.joinToString(", ") { (unitId, wait) -> "$unitId=${wait.expect}" }}"
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
     * `null`(침묵)이다. 이름 있는 신호는 계획된 대기 단위의 기대 값이다.
     *
     * 플릿 단위의 출발지·목적지([fleetLocations])는 플릿 세계를 본다. 용기가 있으면 점유이고 그 용기와 놓인 시각을 내며(처음 둔 출발지는
     * 놓인 시각을 모르므로 `null`), 없으면 비었다고 말한다. 플릿 단위에는 관측 선언이 없어 게이트가 이 자리를 묻지 않으므로, 플릿이 인계한
     * 뒤의 목적지 근거만 이것으로 선다.
     *
     * **로봇 단위와 자리를 나누면 자리마다 맞는 세계를 답한다.** 로봇 단위의 출발 자리([robotSources])는 늘 위의 로봇 규칙이다(로봇이 집을
     * 자재를 낸다 — 플릿이 같은 자리에서 용기를 가져가도 이상적 현장에서는 집을 것이 남는다). 로봇 단위의 목적지([robotLocations])이기도 한
     * 플릿 목적지는 플릿이 무엇을 놓기 전에는 로봇 규칙이고 놓은 뒤에는 그 용기다. 그래서 같은 슬롯에 로봇이 먼저 놓고 플릿이 뒤에 인계하는
     * 순서는 둘 다 근거가 서고, 플릿이 먼저 인계한 슬롯에 로봇이 뒤에 놓는 순서는 로봇의 목적지 근거가 그 용기를 보아 어긋난다(한 자리에
     * 둘을 둘 수 없다 — 정의·표본의 모순이다).
     */
    private class IdealCell(
        order: JobOrder,
        private val ideal: Map<String, String>,
        private val fleet: AmrFleetMimic,
        private val fleetLocations: Set<String>,
        private val robotSources: Set<String>,
        private val robotLocations: Set<String>,
    ) : CellSignals {
        /** 표본 작업 지시를 미들웨어가 받았다. 그 전에는 목적지가 비었다. */
        @Volatile
        var accepted = false

        private val destinations: Map<String, String?> = order.equipmentRequirements
            .filter { it.equipmentUse == EquipmentUse.DESTINATION }
            .associate { it.id to it.properties[EquipmentUse.PROP_MATERIAL] }

        private val sources: Map<String, String?> = order.equipmentRequirements
            .filter { it.equipmentUse == EquipmentUse.SOURCE }
            .associate { it.id to it.properties[EquipmentUse.PROP_MATERIAL] }

        override fun observe(location: String): SlotSignal? {
            if (location in fleetLocations && location !in robotSources) {
                val placed = fleet.observe(location)
                if (placed != null) return SlotSignal(true, placed.containerId, placed.placedAt)
                if (location !in robotLocations) return SlotSignal(false, null, null)
            }
            return when (location) {
                in destinations -> if (accepted) SlotSignal(true, destinations[location], null) else SlotSignal(false, null, null)
                in sources -> SlotSignal(true, sources[location], null)
                else -> null
            }
        }

        override fun signal(name: String): NamedSignal? = ideal[name]?.let { NamedSignal(it, null) }
    }

    companion object {
        /** 모의 실행의 기체 id. 현장 기체 id 와 겹치지 않게 둔다. */
        const val ROBOT_ID = "mock-01"

        /** 표본이 플릿 단위의 용기·출발지·목적지를 채우지 못한 소견([MockRunFailure.DEFINITION]). 뒤에 그 단위와 빈 칸이 붙는다. */
        const val FLEET_SAMPLE_DETAIL = "표본이 플릿 운반 단위를 채우지 못했습니다"

        /**
         * 표본이 고른 분기 가지가 모두 비어(`otherwise: []` 등) 계획에 돌릴 단위가 없다는 소견([MockRunFailure.DEFINITION], picasso §15.328).
         * 뒤에 자리마다 고른 가지가 붙는다. 정의가 틀렸다는 뜻이 아니라 이 표본으로는 아무 가지도 검증하지 못한다는 뜻이다 — 단위를 내는
         * 가지를 고르는 표본으로 다시 돌린다.
         */
        const val EMPTY_BRANCH_DETAIL = "표본이 고른 분기 가지가 비어 돌릴 단위가 없습니다(단위를 내는 가지를 고르는 표본으로 다시 돌리십시오)"

        /** 셀 대역 스냅숏으로 만든 표본이 플릿 경로 정의일 때 출발 자리에 싣는 용기 id 의 앞머리. 뒤에 초안 id 가 붙는다. */
        const val SAMPLE_CONTAINER_PREFIX = "MOCK-CONTAINER-"

        /** [definition] 에 플릿 경로(`route: FLEET`) 단위 노드가 있는가. */
        fun hasFleet(definition: MissionDefinition): Boolean = MissionValidator.unitNodes(definition).any { it.route == Route.FLEET }

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
         *
         * [fleet] 이면(정의에 플릿 경로 노드가 있다, [hasFleet]) 출발 자리에 용기 속성(`container` = [SAMPLE_CONTAINER_PREFIX] + 초안 id)을
         * 더하고 `materialRequirements` 를 싣지 않는다 — 운반(시나리오 ①)의 작업 지시는 자재 선언을 싣지 않고, 실으면 플릿 단위의 기대
         * 용기가 자재 수에 섞여 정합 검사가 어긋난다.
         */
        fun sampleOrder(workMasterId: String, draftId: Long, cell: CellSnapshot, fleet: Boolean = false): JobOrder? {
            val slots = cell.slots.take(SAMPLE_SLOTS).map { it.id }
            val source = cell.presentations.firstOrNull { it.material != null } ?: return null
            if (slots.isEmpty()) return null
            val material = source.material!!
            val sourceProperties = mapOf(EquipmentUse.PROP_MATERIAL to material) +
                if (fleet) mapOf(EquipmentUse.PROP_CONTAINER to "$SAMPLE_CONTAINER_PREFIX$draftId") else emptyMap()
            return JobOrder(
                jobOrderId = "MOCK-$draftId",
                workMasterId = workMasterId,
                version = 1,
                requiredEvidence = Evidence.E2,
                materialRequirements = if (fleet) emptyList() else listOf(MaterialRequirement(material, slots.size)),
                equipmentRequirements = slots.map {
                    EquipmentRequirement(it, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to material))
                } + EquipmentRequirement(source.id, EquipmentUse.SOURCE, sourceProperties),
            )
        }
    }
}
