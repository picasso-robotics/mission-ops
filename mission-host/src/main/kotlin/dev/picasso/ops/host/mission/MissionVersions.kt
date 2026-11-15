package dev.picasso.ops.host.mission

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.mission.DefinedCapability
import dev.picasso.ops.host.HostClock
import dev.picasso.ops.host.MissionHost
import dev.picasso.ops.host.store.DraftRow
import dev.picasso.ops.host.store.MissionStore
import dev.picasso.ops.host.store.MockRunRow
import org.springframework.dao.DuplicateKeyException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 그 초안이 없다. REST 가 404 로 옮긴다. */
class DraftNotFound(val draftId: Long) : RuntimeException("초안이 없다: $draftId")

/** 그 요청 id 가 이미 다른 행에 쓰였다(T9). REST 가 409 로 옮긴다. 요청 id 하나는 운영 서비스의 조작 하나다. */
class RequestIdReused(val requestId: UUID) : RuntimeException("요청 id 가 이미 쓰였다: $requestId")

/** 그 요청 id 의 조작을 아직 처리 중이다(T9). REST 가 409 로 옮긴다. 남은 행이 없다는 응답이 아니다. */
class RequestInProgress(val requestId: UUID) : RuntimeException("그 요청 id 의 조작을 아직 처리 중이다: $requestId")

/**
 * 임무 버전의 저장·검증·모의 실행·활성화(S3b 스펙 §6). REST 가 부르는 한 자리다.
 *
 * ## 잠금(T2)
 *
 * 잠금 순서는 «호스트 잠금 → DB» 한 방향뿐이다.
 *
 * - 초안 저장: 호스트 잠금을 잡지 않는다.
 * - 검증·모의 실행: 검증 입력([SiteInputs])을 읽는 동안만 호스트 잠금을 잡는다. 그동안 DB 연결을 쥐지 않는다. 모의 실행은
 *   잠금을 놓은 뒤 별도 mimic·미들웨어로 돈다(실제 시간 몇 초).
 * - 활성화: 처음부터 끝까지 호스트 잠금 아래다. 판정과 배정 사이에 활성화가 끼면 한 제출 안에서 판정 계획과 실행 계획의
 *   버전이 갈린다. 다시 검증하고, 플릿 경로 정의면 플릿 포트를 보고, 마지막 모의 실행을 보고, `DefinedCapability` 를 먼저 만들고, 버전 행을 넣고, 마지막에
 *   카탈로그를 바꾼다. 행을 넣기 전에 케이퍼빌리티를 만드는 것은 만들다 실패한 정의가 버전 번호를 쓰지 않게 하려는 것이다.
 *
 * ## 처리 중인 요청(T9)
 *
 * 요청 id 를 받는 조작 셋(초안 저장·모의 실행·활성화)은 처음부터 끝까지 그 id 를 처리 중 집합에 둔다. 재조회([byRequest])는
 * 처리 중인 id 에 [RequestInProgress] 를 낸다. 활성화는 호스트 잠금을 기다리고 모의 실행은 실제 시간 상한 밖(하네스 기동,
 * 입력을 읽는 잠금 대기)이 있어, 운영 서비스가 응답 없음 뒤 재조회할 때 아직 행이 없을 수 있다. 그때 «남은 행 없음» 으로 응답하면
 * 운영 서비스가 반영 안 됨으로 확인한 뒤에 행이 남는다.
 *
 * 재조회는 처리 중 집합을 먼저 보고 DB 를 나중에 읽는다. 조작은 행을 남긴 뒤에 집합에서 빠지므로, 집합에 없고 DB 에도 없으면
 * 그 조작은 아직 호스트에 오지 않았거나 행을 남기지 않고 끝난 것이다. 앞의 것은 운영 서비스가 재조회 전에 기다려 줄인다.
 * 같은 요청 id 가 처리 중에 또 오면 [RequestIdReused] 다.
 *
 * @param clock 거부의 확인 시각(`checkedAt`)과 판정 시각. 호스트 시계다(시험은 현장 가상 시계).
 */
class MissionVersions(
    private val host: MissionHost,
    private val store: MissionStore,
    private val catalog: StoredMissionCatalog,
    private val runner: MockRunner,
    private val clock: HostClock,
    private val json: ObjectMapper,
) {

    /** 처리 중인 요청 id. 프로세스 안에만 있다. 재기동하면 처리 중이던 조작도 끝난 것이다. */
    private val inFlight: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun overview(workMasterId: String): MissionOverview {
        val versions = store.versions(workMasterId).map(VersionView::of)
        val activeVersion = catalog.active(workMasterId)?.missionVersion
        val active = if (activeVersion == null) {
            ActiveView(null, if(catalog.active(workMasterId)==null) "UNPUBLISHED" else SOURCE_CODE, null)
        } else {
            ActiveView(activeVersion, SOURCE_DATA, versions.firstOrNull { it.version == activeVersion })
        }
        val drafts = store.drafts(workMasterId, DRAFT_LIMIT).map { DraftView.of(it, store.lastMockRun(it.draftId)?.let(::view)) }
        return MissionOverview(workMasterId, active, versions, drafts)
    }

    /** 초안은 자유롭다. 읽을 수 없는 문서도 저장한다(운영 관리 화면 설계 제안 §8.1). */
    fun saveDraft(workMasterId: String, definition: String, actor: String, requestId: UUID,sampleOrder:String?=null): DraftSavedView = handling(requestId) {
        if (store.requestIdUsed(requestId)) throw RequestIdReused(requestId)
        val row = try {
            store.saveDraft(workMasterId, definition, actor, requestId,sampleOrder)
        } catch (_: DuplicateKeyException) {
            throw RequestIdReused(requestId)
        }
        DraftSavedView(DraftView.of(row, null))
    }

    fun validate(draftId: Long, robotIds: List<String>): ValidationView {
        val draft = draft(draftId)
        val inputs = host.siteInputs(robotIds)
        val at = clock.now()
        val judgment = MissionJudgment.judge(draft.workMasterId, draft.definition, inputs, at)
        return ValidationView(
            result = when (judgment) {
                is Judgment.Passed -> PASSED
                is Judgment.Refused -> REFUSED
                is Judgment.Unknown -> INPUT_UNKNOWN
            },
            draftId = draftId,
            workMasterId = draft.workMasterId,
            checkedAt = at,
            refusals = (judgment as? Judgment.Refused)?.refusals.orEmpty().map(RefusalView::of),
            unknown = (judgment as? Judgment.Unknown)?.let { UnknownView.of(it.inputs, it.robots) },
            inputs = InputsView.of(inputs),
        )
    }

    /**
     * 모의 실행(결정 1). 검증을 지난 초안만 돌린다. 지나지 못하면 거부 목록이나 «모름» 을 그대로 돌려주고 남기지 않는다.
     * 돈 것은 통과든 실패든 모의 실행 표에 남는다. 요청 안에서 동기로 돈다(T10).
     */
    fun mockRun(draftId: Long, robotIds: List<String>, requestId: UUID): MockRunOutcomeView =
        handling(requestId) { mockRunHandling(draftId, robotIds, requestId) }

    private fun mockRunHandling(draftId: Long, robotIds: List<String>, requestId: UUID): MockRunOutcomeView {
        val draft = draft(draftId)
        if (store.requestIdUsed(requestId)) throw RequestIdReused(requestId)
        val inputs = host.siteInputs(robotIds)
        val at = clock.now()
        fun outcome(result: String, judgment: Judgment?, row: MockRunView?) = MockRunOutcomeView(
            result = result,
            draftId = draftId,
            workMasterId = draft.workMasterId,
            checkedAt = at,
            refusals = (judgment as? Judgment.Refused)?.refusals.orEmpty().map(RefusalView::of),
            unknown = (judgment as? Judgment.Unknown)?.let { UnknownView.of(it.inputs, it.robots) },
            inputs = InputsView.of(inputs),
            mockRun = row,
        )

        val definition = when (val judgment = MissionJudgment.judge(draft.workMasterId, draft.definition, inputs, at)) {
            is Judgment.Passed -> judgment.definition
            is Judgment.Refused -> return outcome(REFUSED, judgment, null)
            is Judgment.Unknown -> return outcome(INPUT_UNKNOWN, judgment, null)
        }
        // 검증이 지났으면 스냅숏이 있다(신호 사양을 읽었다).
        val order = draft.sampleOrder?.let{dev.picasso.ops.host.web.HostRequests.jobOrder(json.readTree(it)).copy(jobOrderId="MOCK-$draftId")} ?: MockRunner.sampleOrder(draft.workMasterId, draftId, inputs.cell!!, fleet = MockRunner.hasFleet(definition))
            ?: return outcome(INPUT_UNKNOWN, Judgment.Unknown(listOf(UnknownInput.SAMPLE_ORDER), emptyList()), null)

        val startedAt = store.now()
        val result = runner.run(definition, order)
        val row = try {
            store.saveMockRun(draftId, result.passed, json.writeValueAsString(result), requestId, startedAt)
        } catch (_: DuplicateKeyException) {
            throw RequestIdReused(requestId)
        }
        return outcome(if (result.passed) PASSED else FAILED, null, view(row))
    }

    /** 활성화(S3b 스펙 §6.5). 호스트 잠금 아래에서 처음부터 끝까지 한다. 결과 넷은 [ActivationView] 에 있다. */
    fun activate(draftId: Long, actor: String, reason: String, robotIds: List<String>, requestId: UUID): ActivationView =
        handling(requestId) {
            val draft = draft(draftId)
            if (store.requestIdUsed(requestId)) throw RequestIdReused(requestId)
            host.exclusive { activateLocked(draft, actor, reason, robotIds, requestId) }
        }

    private fun activateLocked(draft: DraftRow, actor: String, reason: String, robotIds: List<String>, requestId: UUID): ActivationView {
        val inputs = host.siteInputsLocked(robotIds)
        val at = clock.now()
        fun outcome(result: String, judgment: Judgment?, last: MockRunView?, activated: VersionView?) = ActivationView(
            result = result,
            draftId = draft.draftId,
            workMasterId = draft.workMasterId,
            checkedAt = at,
            version = activated?.version,
            refusals = (judgment as? Judgment.Refused)?.refusals.orEmpty().map(RefusalView::of),
            unknown = (judgment as? Judgment.Unknown)?.let { UnknownView.of(it.inputs, it.robots) },
            inputs = InputsView.of(inputs),
            lastMockRun = last,
            activated = activated,
        )

        // 1. 지금의 신호 사양과 현장 스킬로 다시 검증한다.
        val definition = when (val judgment = MissionJudgment.judge(draft.workMasterId, draft.definition, inputs, at)) {
            is Judgment.Passed -> judgment.definition
            is Judgment.Refused -> return outcome(REFUSED, judgment, null, null)
            is Judgment.Unknown -> return outcome(INPUT_UNKNOWN, judgment, null, null)
        }
        val last = store.lastMockRun(draft.draftId)
        val lastView = last?.let(::view)
        // 2. 플릿 경로 노드를 든 정의는 이 호스트에 플릿 포트가 있어야 받는다. 모의 실행 통과(인프로세스 플릿 미믹)는 «정의가 끝까지
        //    돈다» 일 뿐 «이 호스트에서 돈다» 가 아니다 — 활성화하면 운반을 맡길 곳이 없는 버전이 접수 경로에 선다.
        if (!host.hasFleetPort && MockRunner.hasFleet(definition)) return outcome(FLEET_PORT_ABSENT, null, lastView, null)
        // 3. 그 초안의 마지막 모의 실행이 통과여야 한다. 앞 초안들의 모의 실행은 보지 않는다(S3b 스펙 §1).
        if (last?.passed != true) return outcome(MOCK_RUN_REQUIRED, null, lastView, null)

        // 4. 케이퍼빌리티 → 버전 행(번호 = 최대 + 1) → 카탈로그.
        // 버전 행의 키 충돌은 요청 id 재사용으로 옮기지 않는다. 기본 키(WorkMaster, 버전) 충돌은 호스트 잠금 아래에서 번호를
        // 매기므로 닿지 않고, 요청 id 충돌은 위에서 이미 보았고 같은 요청 id 는 처리 중 집합이 동시에 들이지 않아 닿지 않는다.
        // 그래도 나면 호스트의 결함이라 500 으로 올린다.
        val capability = DefinedCapability(definition)
        val row = store.insertVersion(draft.workMasterId, draft.draftId, draft.definition, actor, reason, requestId)
        catalog.install(row.workMasterId, capability, row.version)
        return outcome(ACTIVATED, null, lastView, VersionView.of(row))
    }

    /** 그 요청 id 로 남은 행(T9). 없으면 `null`. 그 요청을 아직 처리 중이면 [RequestInProgress] 다. */
    fun byRequest(requestId: UUID): RequestView? {
        if (requestId in inFlight) throw RequestInProgress(requestId)
        val rows = store.byRequest(requestId)
        if (!rows.found) return null
        return RequestView(
            requestId = requestId,
            draft = rows.draft?.let { DraftView.of(it, store.lastMockRun(it.draftId)?.let(::view)) },
            mockRun = rows.mockRun?.let(::view),
            version = rows.version?.let(VersionView::of),
        )
    }

    /** [action] 동안 [requestId] 를 처리 중으로 둔다. 같은 id 가 이미 처리 중이면 [RequestIdReused] 다. */
    private fun <T> handling(requestId: UUID, action: () -> T): T {
        if (!inFlight.add(requestId)) throw RequestIdReused(requestId)
        try {
            return action()
        } finally {
            inFlight.remove(requestId)
        }
    }

    fun draftView(draftId: Long): DraftView = draft(draftId).let { DraftView.of(it, store.lastMockRun(it.draftId)?.let(::view)) }

    private fun draft(draftId: Long): DraftRow = store.draft(draftId) ?: throw DraftNotFound(draftId)

    private fun view(row: MockRunRow) = MockRunView.of(row, json.readTree(row.result))

    companion object {
        const val PASSED = "PASSED"
        const val FAILED = "FAILED"
        const val REFUSED = "REFUSED"
        const val INPUT_UNKNOWN = "INPUT_UNKNOWN"
        const val ACTIVATED = "ACTIVATED"
        const val MOCK_RUN_REQUIRED = "MOCK_RUN_REQUIRED"

        /** 플릿 경로 노드를 든 정의인데 이 실행 호스트에 플릿 포트가 없다([MissionHost.hasFleetPort]). 활성화하지 않는다. */
        const val FLEET_PORT_ABSENT = dev.picasso.ops.host.web.HostRequests.FLEET_PORT_ABSENT

        const val SOURCE_CODE = "CODE"
        const val SOURCE_DATA = "DATA"

        /** 개요에 싣는 최근 초안 수. */
        const val DRAFT_LIMIT = 20
    }
}
