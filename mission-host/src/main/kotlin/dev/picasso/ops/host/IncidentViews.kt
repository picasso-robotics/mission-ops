package dev.picasso.ops.host

import com.fasterxml.jackson.annotation.JsonUnwrapped
import dev.picasso.contracts.v1.FailureClass
import dev.picasso.contracts.v1.Fault
import dev.picasso.middleware.Approver
import dev.picasso.middleware.FaultDetail
import dev.picasso.middleware.IncidentBundle
import dev.picasso.middleware.IncidentDecision
import dev.picasso.middleware.IncidentResolution
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.Verification
import java.time.Duration
import java.time.Instant

// 호스트 인시던트 REST 의 본문 모양(S3c 스펙 §7.2, S4a 스펙 §6). 칸 이름과 순서가 계약이다(S4a JSON 계약 §3).

/** 승인자. [kind] 는 `PERSON`·`AGENT` 이며 호스트가 만드는 판단자는 늘 `PERSON` 이다(ADR 47). */
data class ApproverView(val id: String, val kind: String) {
    companion object {
        fun of(approver: Approver) = ApproverView(approver.id, approver.kind.name)
    }
}

/**
 * 사람이 그 인시던트의 단위에 낸 판단(picasso `IncidentResolution`). [at] 은 미들웨어 시각(호스트 시계)이고 [wallClockAt] 은
 * 실제 시각이다. 운영 서비스의 재조회는 [wallClockAt] 을 요청 시각과 맞댄다. 통합 시험에서는 호스트 시계가 현장 가상 시계라
 * [at] 이 실제 시각보다 앞선다.
 */
data class ResolutionView(val decision: String, val at: Instant, val wallClockAt: Instant, val decidedBy: ApproverView) {
    companion object {
        fun of(resolution: IncidentResolution) =
            ResolutionView(resolution.decision.name, resolution.at, resolution.wallClockAt, ApproverView.of(resolution.decidedBy))
    }
}

/** 이 단위를 실패로 만든 결함의 요약(목록용). 하류가 결함 없이 실패를 알렸으면(설비 대기 기한 포함) 인시던트의 칸이 `null` 이다. */
data class FaultSummaryView(val failureClass: String, val errorType: String, val errorHint: String) {
    companion object {
        fun of(fault: FaultDetail) = FaultSummaryView(fault.failureClass, fault.errorType, fault.errorHint)
    }
}

/** 결함이 지목한 대상 하나. */
data class FaultReferenceView(val key: String, val value: String)

/** 결함 하나의 전부(상세용). picasso `FaultDetail` 의 칸 그대로다. */
data class FaultDetailView(
    val failureClass: String,
    val errorType: String,
    val vendorDetail: String,
    val errorHint: String,
    val references: List<FaultReferenceView>,
    val canContinueCurrentTask: Boolean,
    val canAcceptNewTask: Boolean,
    val activeUntilKind: String,
    val activeUntilTime: String,
) {
    companion object {
        fun of(fault: FaultDetail) = FaultDetailView(
            fault.failureClass, fault.errorType, fault.vendorDetail, fault.errorHint,
            fault.references.map { FaultReferenceView(it.key, it.value) },
            fault.canContinueCurrentTask, fault.canAcceptNewTask, fault.activeUntilKind, fault.activeUntilTime,
        )

        /**
         * 계약 결함을 같은 모양으로(실행의 막는 결함, 결함 감수). picasso 의 결함 투영(`faultDetailOf`)은 모듈 안에서만 보이므로 같은
         * 규칙을 여기서 다시 쓴다. 분류가 없거나 모르는 값이면 [Middleware.UNCLASSIFIED] 이고, 있으면 `FAILURE_CLASS_` 를 뗀 이름이다.
         * 참조 열쇠는 계약 열거형의 이름이다. 같은 결함을 번들이 실은 것과 칸마다 같다.
         */
        fun fromContract(fault: Fault) = FaultDetailView(
            failureClass = fault.failureClass
                .takeIf { it != FailureClass.FAILURE_CLASS_UNSPECIFIED && it != FailureClass.UNRECOGNIZED }
                ?.name?.removePrefix("FAILURE_CLASS_")
                ?: Middleware.UNCLASSIFIED,
            errorType = fault.errorType,
            vendorDetail = fault.vendorDetail,
            errorHint = fault.errorHint,
            references = fault.referencesList.map { FaultReferenceView(it.key.name, it.value) },
            canContinueCurrentTask = fault.canContinueCurrentTask,
            canAcceptNewTask = fault.canAcceptNewTask,
            activeUntilKind = fault.activeUntil.kind.name,
            activeUntilTime = fault.activeUntil.until,
        )
    }
}

/** 근거 윈도우 안의 관측 하나. [local] 이 참이면 미들웨어가 적은 관측(셀 신호, 연결 등)이고 [sequence] 는 커서 값을 빌린 것이다. */
data class ObservedEventView(val sequence: Long, val occurredAt: String, val kind: String, val detail: String, val local: Boolean)

/** 몇 걸음 가운데 어디서 났는가. [at] 은 1 부터이고 계획에 없으면 0 이다. */
data class StepView(val at: Int, val plan: List<String>, val completed: List<String>)

data class MaterialView(val materialDefinitionId: String, val quantity: Int)

data class EquipmentView(val id: String, val equipmentUse: String, val properties: Map<String, String>)

/**
 * 무엇을 하려던 일이었나(picasso `Intent`). 시간값은 초 단위 정수다. [missionVersion] 이 `null` 이면 코드 정의이고
 * [siteSettingsVersion] 이 `null` 이면 현장 시간값 없이 봉인했다(그때 [inDoubtGraceSeconds]·[stallWindowSeconds] 도 `null`).
 */
data class IntentView(
    val workMasterId: String,
    val orderVersion: Int,
    val orderParameters: Map<String, String>,
    val materials: List<MaterialView>,
    val equipment: List<EquipmentView>,
    val capabilityMaxEvidence: String,
    val evidenceBeforeSeconds: Long,
    val evidenceAfterSeconds: Long,
    val skillType: String,
    val unitParameters: Map<String, String>,
    val source: String?,
    val destination: String?,
    val expectedIdentity: String?,
    val missionVersion: Int?,
    val siteSettingsVersion: Long?,
    val inDoubtGraceSeconds: Long?,
    val stallWindowSeconds: Long?,
)

/**
 * 인시던트 목록의 한 줄(S3c 스펙 §7.2, S4a 스펙 §6). 앞 14칸은 S3c 그대로이고 그 뒤 다섯 칸이 S4a 에서, 끝 네 칸(정체성)이 picasso
 * `2711660` 반입 때 더해졌다(S4a JSON 계약 §3 의 20~23).
 *
 * @param at 봉인 라운드의 미들웨어 시각(호스트 시계)
 * @param missionVersion 임무 버전. `null` 이면 코드 정의다
 * @param siteSettingsVersion 봉인 라운드의 현장 설정 버전. 현장 시간값 없이 봉인했으면 `null` 이다
 * @param evidenceBeforeSeconds·evidenceAfterSeconds 봉인 라운드의 근거 윈도우 앞·뒤 폭. 늘 있다
 * @param inDoubtGraceSeconds·stallWindowSeconds 봉인 라운드의 값. 현장 시간값 없이 봉인했으면 `null` 이다
 * @param unresolved 봉인할 때 판정을 보류했는가. 봉인 때 한 번 정해지고 판단 뒤에도 그대로다
 * @param resolution 사람이 이 인시던트의 단위에 낸 판단. 없으면 `null` 이다
 * @param fault 이 단위를 실패로 만든 결함의 요약. 없으면 `null` 이다
 * @param held 그 단위가 지금 운영자 보류이고 이 인시던트가 그 단위의 가장 최근 미해결(`unresolved` 이고 판단 없음) 인시던트다.
 *   재작업 뒤 다시 보류가 서면 새 인시던트만 참이다
 * @param confirmedWithoutEvidence 판단이 완료 확인이고 봉인 때 확인 결과가 MATCHED 가 아니다(설비 근거 없이 완료 확인)
 * @param incidentKey·firstOpenedAt·openingKind·attempt 재기동을 넘는 정체성(picasso §15.322, [IncidentIdentity])
 */
data class IncidentView(
    val incidentId: String,
    val executionId: String,
    val jobOrderId: String,
    val robotId: String,
    val unitId: String,
    val at: Instant,
    val failureClass: String?,
    val route: String,
    val missionVersion: Int?,
    val siteSettingsVersion: Long?,
    val evidenceBeforeSeconds: Long,
    val evidenceAfterSeconds: Long,
    val inDoubtGraceSeconds: Long?,
    val stallWindowSeconds: Long?,
    val unresolved: Boolean,
    val resolution: ResolutionView?,
    val fault: FaultSummaryView?,
    val held: Boolean,
    val confirmedWithoutEvidence: Boolean,
    val incidentKey: String? = null,
    val firstOpenedAt: Instant? = null,
    val openingKind: String? = null,
    val attempt: Int? = null,
)

/**
 * 재기동을 넘는 인시던트 정체성(picasso §15.322 의 `IncidentBundle.origin`·`openingKind`·`attempt`). 목록 줄과 상세의 끝 네 칸이다.
 *
 * - `incidentKey`: **불투명한** 열쇠. 최초 봉인마다 하나 서고 같으면 같은 사건이다 — 재기동이 다시 연 보류는 원래 보류의 열쇠를 잇는다.
 *   뜯어 읽지 않는다. 다시 연 인시던트의 열쇠가 `null` 이면 옛 판(1~4) 판단 기록으로 다시 연 것이라 **연결 불명**이며, 실행·단위·분류로
 *   추측해 잇지 않는다. 연결 불명은 이후의 재기동에서도 `null` 로 이어진다.
 * - `firstOpenedAt`: 최초 봉인의 가상 시계. 다시 연 인시던트의 `at` 은 다시 연 시각이고 이것은 처음 선 시각이다. 열쇠와 함께 있거나 함께 없다.
 * - `openingKind`: `NEW`(이 인스턴스가 관측으로 연 것)·`REOPENED`(재기동이 판단 기록의 운영자 보류로 다시 연 것).
 * - `attempt`: 봉인 때 그 단위의 시도 번호. 재발 판정의 재료이고 같은 보류를 잇는 데 쓰지 않는다.
 *
 * 넷 다 `null` 이면 이 칸들이 생기기 전(picasso `2711660` 반입 전)에 적은 사본이다 — 열림의 종류도 모른다. 이 호스트가 지금 내는 줄은
 * `openingKind`·`attempt` 가 늘 있다. 사본(`mission.incident_copy.detail`)은 상세 본문 글자를 그대로 적으므로 이 넷도 함께 남는다(구조 변경 없음).
 */
internal data class IncidentIdentity(val incidentKey: String?, val firstOpenedAt: Instant?, val openingKind: String, val attempt: Int) {
    companion object {
        fun of(bundle: IncidentBundle) =
            IncidentIdentity(bundle.origin?.key, bundle.origin?.firstOpenedAt, bundle.openingKind.name, bundle.attempt)
    }
}

/**
 * `GET /host/incidents` 의 본문. [incidents] 는 최신부터 많아야 limit 개이고 [total] 은 자르기 전의 수다.
 *
 * @param earlier 이전 인스턴스의 인시던트 사본(S4b 스펙 T7). 적은 순서의 역순으로 많아야 limit 개다. 보류 중이 아니다
 * @param earlierTotal 자르기 전의 사본 수
 */
data class IncidentsView(
    val instanceId: String,
    val total: Int,
    val incidents: List<IncidentView>,
    val earlierTotal: Int,
    val earlier: List<EarlierIncidentView>,
)

/** 이전 인스턴스의 인시던트 한 줄. 목록 줄의 19칸 앞에 그 인스턴스를 둔다. 상세는 이 [instanceId] 를 질의로 실어 읽는다. */
data class EarlierIncidentView(
    val instanceId: String,
    @get:JsonUnwrapped val incident: IncidentView,
)

/**
 * `GET /host/incidents/{incidentId}` 의 본문(S4a 스펙 §6). 목록 줄의 칸에 근거 윈도우, 단계 위치, 필요·도달 근거 등급, 확인 결과,
 * 결함 전부, `blockedBy`, 의도 전체를 더한다.
 *
 * @param unitState 그 단위의 지금 상태. 실행이 없으면 `null` 이다
 * @param verification 봉인 때의 설비 확인 결과. `NOT_REQUESTED` 는 확인 결과를 묻지 않았다는 뜻이지 설비 확인을 안 했다는 뜻이 아니다
 * @param windowTruncated 근거 윈도우 밖이라 버린 관측이 있는가
 * @param incidentKey·firstOpenedAt·openingKind·attempt 재기동을 넘는 정체성(picasso §15.322, [IncidentIdentity]). 목록 줄과 같다
 */
data class IncidentDetailView(
    val instanceId: String,
    val incidentId: String,
    val executionId: String,
    val jobOrderId: String,
    val robotId: String,
    val unitId: String,
    val at: Instant,
    val wallClockAt: Instant,
    val failureClass: String?,
    val route: String,
    val unresolved: Boolean,
    val resolution: ResolutionView?,
    val held: Boolean,
    val confirmedWithoutEvidence: Boolean,
    val unitState: String?,
    val fault: FaultDetailView?,
    val blockedBy: List<FaultDetailView>,
    val requiredEvidence: String,
    val reachedEvidence: String,
    val verification: String,
    val step: StepView,
    val evidenceWindow: List<ObservedEventView>,
    val windowTruncated: Boolean,
    val preconditionSubjects: List<String>,
    val expectedHold: String?,
    val observedHold: String,
    val effectMismatch: String?,
    val linkBroken: Boolean,
    val intent: IntentView,
    val incidentKey: String? = null,
    val firstOpenedAt: Instant? = null,
    val openingKind: String? = null,
    val attempt: Int? = null,
)

/**
 * `POST /host/executions/{executionId}/units/{unitId}/resolve` 의 본문(S4a 스펙 T6). [result] 는 picasso 의 이름 그대로
 * `Resolved`·`NotHeld`·`Refused` 다.
 *
 * @param detail Refused 의 이유. 그 밖에는 `null` 이다
 * @param incidentId Resolved 일 때 판단이 붙은 인시던트. 그 단위에 판단 없는 인시던트가 없었거나 Resolved 가 아니면 `null` 이다
 * @param requestId 요청이 실은 요청 id 를 그대로 돌려준다. 호스트는 저장하지 않는다
 */
data class ResolveView(val result: String, val detail: String?, val incidentId: String?, val requestId: String?)

/** 번들을 본문으로 옮긴다. picasso 의 ISO-8601 기간 문자열은 초로 되돌린다(60초는 `PT1M` 으로 접혀 온다). */
internal object IncidentViews {

    private fun seconds(iso: String): Long = Duration.parse(iso).seconds

    fun confirmedWithoutEvidence(bundle: IncidentBundle): Boolean =
        bundle.resolution?.decision == IncidentDecision.CONFIRM_DONE && bundle.verification != Verification.MATCHED

    fun item(bundle: IncidentBundle, held: Boolean): IncidentView {
        val intent = bundle.intent
        val identity = IncidentIdentity.of(bundle)
        return IncidentView(
            incidentId = bundle.incidentId,
            executionId = bundle.executionId,
            jobOrderId = bundle.jobOrderId,
            robotId = bundle.robotId,
            unitId = bundle.unitId,
            at = bundle.at,
            failureClass = bundle.failureClass,
            route = bundle.route,
            missionVersion = intent.missionVersion,
            siteSettingsVersion = intent.siteSettingsVersion,
            evidenceBeforeSeconds = seconds(intent.evidenceWindowBefore),
            evidenceAfterSeconds = seconds(intent.evidenceWindowAfter),
            inDoubtGraceSeconds = intent.inDoubtGrace?.let(::seconds),
            stallWindowSeconds = intent.stallWindow?.let(::seconds),
            unresolved = bundle.unresolved,
            resolution = bundle.resolution?.let(ResolutionView::of),
            fault = bundle.fault?.let(FaultSummaryView::of),
            held = held,
            confirmedWithoutEvidence = confirmedWithoutEvidence(bundle),
            incidentKey = identity.incidentKey,
            firstOpenedAt = identity.firstOpenedAt,
            openingKind = identity.openingKind,
            attempt = identity.attempt,
        )
    }

    /** 상세에서 목록 줄을 다시 세운다. 사본이 쓴다. 칸의 값은 [item] 과 같은 출처(봉인 때의 번들)다. */
    fun item(detail: IncidentDetailView): IncidentView = IncidentView(
        incidentId = detail.incidentId,
        executionId = detail.executionId,
        jobOrderId = detail.jobOrderId,
        robotId = detail.robotId,
        unitId = detail.unitId,
        at = detail.at,
        failureClass = detail.failureClass,
        route = detail.route,
        missionVersion = detail.intent.missionVersion,
        siteSettingsVersion = detail.intent.siteSettingsVersion,
        evidenceBeforeSeconds = detail.intent.evidenceBeforeSeconds,
        evidenceAfterSeconds = detail.intent.evidenceAfterSeconds,
        inDoubtGraceSeconds = detail.intent.inDoubtGraceSeconds,
        stallWindowSeconds = detail.intent.stallWindowSeconds,
        unresolved = detail.unresolved,
        resolution = detail.resolution,
        fault = detail.fault?.let { FaultSummaryView(it.failureClass, it.errorType, it.errorHint) },
        held = detail.held,
        confirmedWithoutEvidence = detail.confirmedWithoutEvidence,
        incidentKey = detail.incidentKey,
        firstOpenedAt = detail.firstOpenedAt,
        openingKind = detail.openingKind,
        attempt = detail.attempt,
    )

    fun detail(instanceId: String, bundle: IncidentBundle, held: Boolean, unitState: String?): IncidentDetailView {
        val intent = bundle.intent
        val identity = IncidentIdentity.of(bundle)
        return IncidentDetailView(
            instanceId = instanceId,
            incidentId = bundle.incidentId,
            executionId = bundle.executionId,
            jobOrderId = bundle.jobOrderId,
            robotId = bundle.robotId,
            unitId = bundle.unitId,
            at = bundle.at,
            wallClockAt = bundle.wallClockAt,
            failureClass = bundle.failureClass,
            route = bundle.route,
            unresolved = bundle.unresolved,
            resolution = bundle.resolution?.let(ResolutionView::of),
            held = held,
            confirmedWithoutEvidence = confirmedWithoutEvidence(bundle),
            unitState = unitState,
            fault = bundle.fault?.let(FaultDetailView::of),
            blockedBy = bundle.blockedBy.map(FaultDetailView::of),
            requiredEvidence = bundle.requiredEvidence.name,
            reachedEvidence = bundle.reachedEvidence.name,
            verification = bundle.verification.name,
            step = StepView(bundle.step.at, bundle.step.plan, bundle.step.completed),
            evidenceWindow = bundle.evidenceWindow.map { ObservedEventView(it.sequence, it.occurredAt, it.kind, it.detail, it.local) },
            windowTruncated = bundle.windowTruncated,
            preconditionSubjects = bundle.preconditionSubjects,
            expectedHold = bundle.expectedHold?.name,
            observedHold = bundle.observedHold.name,
            effectMismatch = bundle.effectMismatch,
            linkBroken = bundle.observation.linkBroken,
            intent = IntentView(
                workMasterId = intent.workMasterId,
                orderVersion = intent.orderVersion,
                orderParameters = intent.orderParameters,
                materials = intent.materials.map { MaterialView(it.materialDefinitionId, it.quantity) },
                equipment = intent.equipment.map { EquipmentView(it.id, it.equipmentUse, it.properties) },
                capabilityMaxEvidence = intent.capabilityMaxEvidence.name,
                evidenceBeforeSeconds = seconds(intent.evidenceWindowBefore),
                evidenceAfterSeconds = seconds(intent.evidenceWindowAfter),
                skillType = intent.skillType,
                unitParameters = intent.unitParameters,
                source = intent.source,
                destination = intent.destination,
                expectedIdentity = intent.expectedIdentity,
                missionVersion = intent.missionVersion,
                siteSettingsVersion = intent.siteSettingsVersion,
                inDoubtGraceSeconds = intent.inDoubtGrace?.let(::seconds),
                stallWindowSeconds = intent.stallWindow?.let(::seconds),
            ),
            incidentKey = identity.incidentKey,
            firstOpenedAt = identity.firstOpenedAt,
            openingKind = identity.openingKind,
            attempt = identity.attempt,
        )
    }
}
