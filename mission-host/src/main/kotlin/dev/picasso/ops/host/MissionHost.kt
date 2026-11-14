package dev.picasso.ops.host

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.picasso.capability.PreconditionViolation
import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.TaskState
import dev.picasso.middleware.ActiveMission
import dev.picasso.middleware.ApprovalAttempt
import dev.picasso.middleware.ApprovalOutcome
import dev.picasso.middleware.ApprovalRefusal
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.Entitlements
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.ExecutionUnit
import dev.picasso.middleware.ForgetOutcome
import dev.picasso.middleware.IncidentBundle
import dev.picasso.middleware.IncidentDecision
import dev.picasso.middleware.InspectAsset
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.JobResponse
import dev.picasso.middleware.JudgmentRecordWire
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.OperatorDecision
import dev.picasso.middleware.PhysicalState
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.ProposalTarget
import dev.picasso.middleware.RemedyOutcome
import dev.picasso.middleware.RemedySearchRecord
import dev.picasso.middleware.Route
import dev.picasso.middleware.ResolveOutcome
import dev.picasso.middleware.RetirementPort
import dev.picasso.middleware.RobotPort
import dev.picasso.middleware.SiteTimingsSource
import dev.picasso.middleware.SourceUnobserved
import dev.picasso.middleware.Unassigned
import dev.picasso.middleware.UnitState
import dev.picasso.middleware.Verification
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.cell.CellBandSignals
import dev.picasso.ops.host.cell.CellSnapshot
import dev.picasso.ops.host.cell.SourceConfirmation
import dev.picasso.ops.host.incident.IncidentExport
import dev.picasso.ops.host.mission.SiteInputs
import dev.picasso.ops.host.mission.StoredMissionCatalog
import dev.picasso.ops.host.permit.CellWorkPermits
import dev.picasso.ops.host.permit.PermitBlockView
import dev.picasso.ops.host.permit.PermitMappingView
import dev.picasso.ops.host.permit.RobotPermitView
import dev.picasso.ops.host.permit.WorkPermitsView
import dev.picasso.ops.host.web.BadRequest
import dev.picasso.ops.host.web.HostRequests
import dev.picasso.ops.host.store.CancelRow
import dev.picasso.ops.host.store.CloseRow
import dev.picasso.ops.host.store.CopyResolutionRow
import dev.picasso.ops.host.store.FaultReleaseRow
import dev.picasso.ops.host.store.HoldObservation
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.ops.host.store.IncidentCopyRow
import dev.picasso.ops.host.store.JournalEvent
import dev.picasso.ops.host.store.JournalRow
import dev.picasso.ops.host.store.JournalEventKind
import dev.picasso.ops.host.store.MissionStore
import dev.picasso.ops.host.store.ResponseContent
import dev.picasso.ops.host.store.ResponseDisposition
import dev.picasso.ops.host.store.ResponseLogRow
import dev.picasso.ops.host.store.ResidualHoldConflictRow
import dev.picasso.ops.host.store.ResidualHoldRow
import dev.picasso.ops.host.store.SettledRow
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 출발 관문 앞에 설 수 있는 단위 상태(`MissionHost.startingRobots`). */
private val STARTING_UNIT_STATES = setOf(UnitState.PENDING, UnitState.IN_DOUBT, UnitState.OPERATOR_HOLD)

/** 스킬 적합 판정(S3a 스펙 §7.6). [UNKNOWN] 은 기체 케이퍼빌리티를 못 물어봤다는 뜻이고 적합이 아니다. */
enum class SkillFit { FIT, MISSING, UNKNOWN }

/**
 * 기체 하나의 호스트 판정(T3). 운영 서비스가 시운전·연결 판정과 합쳐 배정 가능을 낸다.
 *
 * @param missingSkills [skillFit] 이 [SkillFit.MISSING] 일 때 모자란 스킬. 그 밖에는 비어 있다.
 * @param runningExecutionId 이 기체를 쥔 실행(picasso `PhysicalState.occupiesRobot`). 정착하지 않은 실행과, 리비전으로 다시 돌 수 있는
 *   부분 완료(`PARTIAL`) 실행이다. 없으면 `null`.
 * @param passed 스킬 적합이고 도는 실행이 없으며 판정 이유가 하나도 없다(미해소 잔여 파지 표시 포함, P0-b2). [gateTrial] 이면 예외로 참이다.
 * @param reasons 통과하지 못한 이유. 화면에 그대로 보인다. [gateTrial] 이면 잔여 파지 표시 사유 하나가 남아 있다.
 * @param gateTrial **관문 확인용**으로만 넘긴다. 남은 이유가 잔여 파지 표시뿐인 새 접수의 기체다. picasso `assign` 의 `trialOnly` 로 넘겨, 관문이
 *   사슬 위반으로 거절하면 조치 제안을 얻고([Refusal.remedy]) 통과하면 실행 없이 표시 사유로 뺀다([submit]). 이 기체에 일반 배정은 서지 않는다.
 */
data class HostEligibility(
    val robotId: String,
    val skillFit: SkillFit,
    val missingSkills: List<String>,
    val runningExecutionId: String?,
    val passed: Boolean,
    val reasons: List<String>,
    val requiredSkills: List<String>? = null,
    val availableSkills: List<String>? = null,
    val gateTrial: Boolean = false,
)

/** 미들웨어 `Submission` 넷의 이름(S3a 스펙 §7.6). */
enum class SubmitResult { ACCEPTED, IDEMPOTENT, REJECTED, UNASSIGNED }

/**
 * `assign` 이 기체마다 낸 미배정 사유.
 *
 * 가운데 두 칸은 출발 결품으로 떨어진 기체에만 값이 있다(picasso `Unassigned.sourceMissing`). 다른 사유로 떨어진 기체는 둘 다 `null` 이다.
 * 모양은 REJECTED 의 같은 이름 칸([SubmitOutcome.alternativeLocations]·[SubmitOutcome.sourceMissing])과 같다.
 *
 * 마지막 칸은 출발 미관측으로 떨어진 기체에만 값이 있다(picasso `Unassigned.sourceUnobserved`). 출발 결품과 접지 않는다. 한 기체는 둘 중
 * 많아야 하나를 든다(게이트가 첫 거절에서 멈춘다).
 *
 * @param alternativeLocations 그 자재를 든 다른 자리. 빈 목록은 «든 자리가 없다» 는 응답이고 `null` 은 셀이 응답하지 않았거나 출발 결품이
 *   아니다. 둘을 접지 않는다. 어느 `null` 인지는 [sourceMissing] 으로 가른다. 출발 미관측이면 대체 자리를 계산하지 않으므로 `null` 이다.
 * @param sourceMissing 이 기체가 출발 결품으로 떨어졌으면 그 자리와 관측. 아니면 `null` 이다.
 * @param sourceUnobserved 이 기체가 출발 미관측으로 떨어졌으면 그 자리와 설비가 말한 것. 아니면 `null` 이다.
 * @param remedy 이 기체에서 이번 제출이 조치 탐색 기록에 적은 응답([SubmitOutcome.remedy] 와 같은 모양). 제안은 (기체, 작업 지시)마다 서므로
 *   후보마다 따로 싣는다. 탐색하지 않았거나 보류했으면 `null` 이다.
 * @param remedyWithheld 이 기체의 조치 시퀀스를 계산했으나 사람이 먼저 진단하도록 보류했다.
 * @param residualHoldMarkers 관문 확인용([HostEligibility.gateTrial])으로 넘긴 기체면 그때 열려 있던 잔여 파지 표시 id. 표시는 이 거절로 풀리지
 *   않는다. 그 밖의 기체는 비어 있다.
 */
data class Refusal(
    val robotId: String,
    val reason: String,
    val alternativeLocations: List<String>? = null,
    val sourceMissing: SourceMissingView? = null,
    val sourceUnobserved: SourceUnobservedView? = null,
    val remedy: RemedyView? = null,
    val remedyWithheld: Boolean = false,
    val residualHoldMarkers: List<Long> = emptyList(),
) {
    companion object {
        /**
         * [missing] 은 이 기체의 출발 결품 답이고 [unobserved] 는 출발 미관측 답이다. 대체 자리 목록은 결품 답의 값을 그대로 쓴다.
         */
        fun of(robotId: String, reason: String, missing: RemedyOutcome.SourceMissing?, unobserved: SourceUnobserved? = null) = Refusal(
            robotId, reason,
            alternativeLocations = missing?.alternatives,
            sourceMissing = missing?.let { SourceMissingView(it.material, it.source, it.observed) },
            sourceUnobserved = unobserved?.let(SourceUnobservedView::of),
        )
    }
}

/**
 * 제출의 결과.
 *
 * 뒤 다섯 칸([remedy]·[remedyWithheld]·[alternativeLocations]·[sourceMissing]·[sourceUnobserved])은 미들웨어 `Submission.Rejected` 의 나머지 칸이다.
 * 미들웨어가 거부한 REJECTED 일 때만 값이 있고 그 밖에는 `null`·`false` 다. 앞 여섯 칸의 모양은 그대로다.
 *
 * @param executionId·robotId ACCEPTED·IDEMPOTENT 일 때만 있다.
 * @param rejectionReason REJECTED 일 때만 있다.
 * @param refusals UNASSIGNED 일 때 `assign` 이 기체마다 낸 사유. 호스트가 먼저 뺀 기체는 여기 없고 [excluded] 에 있다. 출발 결품·출발
 *   미관측으로 떨어진 기체의 대체 자리와 출발 자리는 [Refusal] 의 칸에 있고, UNASSIGNED 의 [alternativeLocations]·[sourceMissing]·
 *   [sourceUnobserved] 는 `null` 이다.
 * @param excluded 호스트가 판정에서 빼 `assign` 에 넘기지 않은 기체와 그 판정.
 * @param remedy 깨진 전제를 채우는 조치 탐색의 응답. `FOUND` 는 단계가 있고 `NONE` 은 못 찾은 종류와 못 채운 조건이 있다.
 *   탐색하지 않았으면 `null` 이다. 보류했으면 [remedyWithheld] 가 참이고 이것은 `null` 이다(보류한 단계는 싣지 않는다).
 * @param remedyWithheld 조치 시퀀스를 계산했으나 사람이 먼저 진단하도록 보류했다. «대안 없음» 과 다르다.
 * @param alternativeLocations 출발 자리가 비었을 때 그 자재를 든 다른 자리. 빈 목록은 «든 자리가 없다» 는 응답이고 `null` 은 셀이
 *   응답하지 않았거나 출발 결품 거부가 아니다. 미들웨어가 둘을 가르지 않으므로 [sourceMissing] 으로 가른다.
 * @param sourceMissing 이 거부가 출발 결품이면 그 자리와 관측. 이번 제출로 미들웨어 조치 탐색 기록에 적힌 `SOURCE_MISSING` 에서
 *   옮긴다. 아니면 `null` 이다.
 * @param sourceUnobserved 이 거부가 출발 미관측이면 그 자리와 설비가 말한 것(picasso `Submission.Rejected.sourceUnobserved`). 아니면
 *   `null` 이다. 조치 탐색 기록에는 적히지 않으므로 거부 값에서 바로 옮긴다. 출발 결품이 아니므로 그때 [alternativeLocations] 는 `null` 이다.
 * @param forgottenJobOrder 이미 끝나 이빅션한 작업 지시라 받지 않은 REJECTED 다. 사유가 picasso `Middleware.FORGOTTEN_JOB_ORDER` 로 시작한다.
 *   이 인스턴스가 picasso 메모리에서 놓은 실행이면 picasso 가, 재기동 뒤라 picasso 가 모르는 정착한 작업 지시면 실행 일지가 거부한다
 *   ([MissionHost.submit]). 다시 해도 같다 — 다시 할 일은 새 작업 지시 id 로 낸다. 그 밖에는 거짓이다
 */
data class SubmitOutcome(
    val result: SubmitResult,
    val executionId: String?,
    val robotId: String?,
    val rejectionReason: String?,
    val refusals: List<Refusal>,
    val excluded: List<HostEligibility>,
    val remedy: RemedyView? = null,
    val remedyWithheld: Boolean = false,
    val alternativeLocations: List<String>? = null,
    val sourceMissing: SourceMissingView? = null,
    val sourceUnobserved: SourceUnobservedView? = null,
    val forgottenJobOrder: Boolean = false,
) {
    companion object {
        /**
         * 미들웨어 거부를 옮긴다. [noted] 는 이 제출로 조치 탐색 기록에 새로 적힌 응답이다. 거기서는 출발 결품 하위 범주만 읽는다.
         * 대체 자리 목록은 [rejected] 의 값을 그대로 쓴다. `null` 과 빈 목록을 접지 않는다.
         */
        fun rejected(rejected: Middleware.Submission.Rejected, noted: RemedyOutcome?, excluded: List<HostEligibility>) = SubmitOutcome(
            SubmitResult.REJECTED, null, null, rejected.reason, emptyList(), excluded,
            remedy = rejected.remedy?.let(RemedyView::of),
            remedyWithheld = rejected.remedyWithheld,
            alternativeLocations = rejected.alternativeLocations,
            sourceMissing = (noted as? RemedyOutcome.SourceMissing)?.let { SourceMissingView(it.material, it.source, it.observed) },
            sourceUnobserved = rejected.sourceUnobserved?.let(SourceUnobservedView::of),
            forgottenJobOrder = rejected.reason.startsWith(Middleware.FORGOTTEN_JOB_ORDER),
        )
    }
}

/**
 * 조치 탐색의 응답(picasso `Remedy`). `kind` 가 하위 범주이고 나머지는 그 하위 범주의 칸이다. 단계와 조건의 칸 이름은 picasso 조치
 * 탐색 기록 내보내기(`FOUND`·`NONE` 줄)와 같다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(JsonSubTypes.Type(RemedyView.Found::class, name = "FOUND"), JsonSubTypes.Type(RemedyView.None::class, name = "NONE"))
sealed interface RemedyView {
    /** 이 단계들을 하면 그 스킬을 보낼 수 있다. 승인은 미들웨어의 제안 승인으로만 한다. */
    data class Found(val steps: List<RemedyStepView>) : RemedyView

    /** 못 찾았다. [cause] 는 `NO_CAPABILITY`(이 기체로는 없다)·`DEPTH_LIMIT`(상한 안에서 못 찾았다)다. */
    data class None(val cause: String, val unmet: List<UnmetView>) : RemedyView

    companion object {
        fun of(remedy: Remedy): RemedyView = when (remedy) {
            is Remedy.Found -> Found(remedy.steps.map(RemedyStepView::of))
            is Remedy.None -> None(remedy.cause.name, remedy.unmet.map(UnmetView::of))
        }
    }
}

/** 조치 시퀀스의 단계 하나. 파지 값은 계약 `HoldKind` 의 이름이다. */
data class RemedyStepView(val skillType: String, val requires: List<RequiredHoldView>, val expectedHold: String, val onFailureHold: String) {
    companion object {
        fun of(step: RemedyStep) = RemedyStepView(
            step.skillType, step.requires.map { RequiredHoldView(it.subject.name, it.requires.name) }, step.expectedHold.name, step.onFailureHold.name,
        )
    }
}

/** 단계의 전제 하나(계약 `Precondition`). */
data class RequiredHoldView(val subject: String, val requires: String)

/** 끝내 못 채운 조건 하나(picasso `PreconditionViolation`). */
data class UnmetView(val subject: String, val required: String, val observed: String, val detail: String) {
    companion object {
        fun of(v: PreconditionViolation) = UnmetView(v.subject.name, v.required.name, v.observed.name, v.detail)
    }
}

/**
 * 출발 결품 거부의 자리(picasso `RemedyOutcome.SourceMissing` 의 앞 세 칸). [observed] 가 `null` 이면 빈 자리이고 아니면 거기 있던
 * 다른 식별 정보다. [material] 은 단위가 자재를 지정하지 않았으면 `null` 이다. 대체 자리는 REJECTED 이면 [SubmitOutcome.alternativeLocations],
 * UNASSIGNED 이면 기체마다 [Refusal.alternativeLocations] 에 있다.
 */
data class SourceMissingView(val material: String?, val source: String, val observed: String?)

/**
 * 출발 미관측 거부의 자리(picasso `SourceUnobserved`). 관측을 요구한 단위의 출발 자리를 설비가 확인해 주지 않았다. **결품이 아니다** —
 * 다음 행동은 주문을 고치는 것이 아니라 사람의 확인이고, 그 확인은 다음 제출의 [SourceConfirmation] 으로 셀 관측이 되어 들어온다.
 *
 * @param material 그 자리에 있어야 할 것. 단위가 자재를 지정하지 않았으면 `null` 이다
 * @param occupied 설비가 말한 것. `null` 이면 말이 없었고, 참이면 «있다» 고만 하고 무엇인지 말하지 않았다. 둘을 접지 않는다
 */
data class SourceUnobservedView(val material: String?, val source: String, val occupied: Boolean?) {
    companion object {
        fun of(unobserved: SourceUnobserved) = SourceUnobservedView(unobserved.material, unobserved.source, unobserved.occupied)
    }
}

/** 실행의 단위 하나. [reached] 는 단위가 이른 근거 등급이다. */
data class UnitView(val unitId: String, val skillType: String, val state: String, val reached: String)

/** 작업 응답 하나. 미들웨어 `JobResponse` 의 칸을 옮긴다. [residualHold] 는 남은 파지 상태의 종류 이름이다. */
data class JobResponseView(
    val jobResponseId: String,
    val version: Int,
    val physicalState: String,
    val requiredEvidence: String,
    val reachedEvidence: String,
    val completedUnits: List<String>,
    val unverifiedUnits: List<String>,
    val incompleteUnits: Map<String, String>,
    val inDoubtUnits: List<String>,
    val operatorRequired: Boolean,
    val residualHold: String,
    val blockedBy: List<String>,
    val connection: String,
)

/**
 * 실행 하나. 실행은 가변 객체라 호스트 잠금 안에서 이 모양으로 복사한다.
 *
 * @param missionVersion 임무 버전. `null` 이면 코드 정의다.
 * @param jobResponse 그 실행의 마지막 작업 응답. 아직 없으면 `null`.
 * @param cancel 이 인스턴스에서 그 실행에 건 운영자 취소(P0-b2). 기동 복원이 다시 지은 실행이면 그 작업 지시에 앞서 건 처음 취소다(다시
 *   건 것, [MissionHost.carryCancel]). 없으면 `null`.
 * @param permitBlock 이 실행의 새 로봇 명령을 지금 막고 있는 작업 허가(ADR 52). 막힌 것이 없거나 현장이 허가 대응을 선언하지 않았으면
 *   `null` 이다. 운영자 보류·결함 막음과 다른 칸이다([PermitBlockView]).
 * @param blockedFaults 지금 다음 단위를 막고 있는 기체 결함 전부(picasso `Execution.blockedBy`). 없으면 빈 목록이다. 작업 응답의
 *   `blockedBy` 는 분류 이름뿐이고 picasso 가 새 작업 응답을 낼 때만 바뀐다. 이것은 지금의 값이다. 운영자 결함 감수가 이것과 대조한다
 * @param releases 그 작업 지시에 건 운영자 결함 감수(V11, 적은 순서). 작업 지시 id 로 읽어 재기동 전 인스턴스의 것도 든다
 */
data class ExecutionView(
    val executionId: String,
    val jobOrderId: String,
    val workMasterId: String,
    val missionVersion: Int?,
    val robotId: String,
    val physicalState: String,
    val units: List<UnitView>,
    val jobResponse: JobResponseView?,
    val restoredFrom: RestoredFromView?,
    val cancel: CancelRecordView? = null,
    val permitBlock: PermitBlockView? = null,
    val blockedFaults: List<FaultDetailView> = emptyList(),
    val releases: List<FaultReleaseView> = emptyList(),
)

/** 다시 지은 실행의 이전 실행(S4b 스펙 §6.3). 바로 앞 인스턴스에서 그 작업 지시를 들었던 실행이다. */
data class RestoredFromView(val instanceId: String, val executionId: String)

/**
 * `GET /host/executions` 의 본문. [instanceId] 는 미들웨어가 뜬 한 번을 가리킨다. 재기동하면 바뀌고 `exec-N` 은 1부터
 * 다시 센다. [pumpedAt] 은 마지막 pump 가 셀 대역을 읽고 잠금을 잡은 뒤의 호스트 시계 값이며 아직 한 번도 안 돌았으면 `null` 이다.
 */
data class ExecutionsView(
    val instanceId: String,
    val pumpedAt: Instant?,
    val executions: List<ExecutionView>,
    val restore: RestoreView?,
    val journalBlockedExecutionIds: List<String> = emptyList(),
)

/** 복원의 결과(S4b 스펙 T3). DEFERRED 는 pump 마다 다시 시도해 RESTORED 나 GAVE_UP 이 된다. */
enum class RestoreResult { RESTORED, DEFERRED, GAVE_UP }

/**
 * 복원 보고의 한 행(S4b 스펙 §6.3).
 *
 * @param previousInstanceId·previousExecutionId 바로 앞 인스턴스에서 그 작업 지시를 들었던 실행. 앞서 다시 지었으면 그 실행이다
 * @param executionId RESTORED 일 때 새 실행 id. 그 밖에는 `null`
 * @param reason DEFERRED·GAVE_UP 의 사유. RESTORED 면 `null`
 * @param close 이번 기동 뒤 운영자가 이 행을 종결했으면 그 종결(결함 14). [result] 는 그대로이고(종결 전의 복원 결과) 이 칸이 «종결했다» 를 알린다. 아니면 `null`
 */
data class RestoreRowView(
    val jobOrderId: String,
    val robotId: String,
    val previousInstanceId: String,
    val previousExecutionId: String,
    val result: String,
    val executionId: String?,
    val reason: String?,
    val close: CloseRecordView? = null,
)

/** 이번 기동의 복원 보고. [at] 은 복원한 호스트 시각이고 [rows] 는 일지의 받은 순서다. 다시 지을 것이 없었으면 [rows] 가 비어 있다. */
data class RestoreView(val at: Instant, val rows: List<RestoreRowView>)

/**
 * `GET /host/job-responses` 의 행 하나(S4b 스펙 T6). 내용 키의 칸과 처분이다. 단위 id 목록은 정렬돼 있고 [incompleteUnits] 는
 * 사유 없이 단위 id 만 든다.
 *
 * @param disposition `SENT` 또는 `RESTART_DUPLICATE`(적었으나 송신하지 않음)
 * @param recordedAt 적은 DB 시각(실제 시각)
 */
data class JobResponseLogView(
    val instanceId: String,
    val jobResponseId: String,
    val jobOrderId: String,
    val executionId: String,
    val version: Int,
    val physicalState: String,
    val requiredEvidence: String,
    val reachedEvidence: String,
    val completedUnits: List<String>,
    val unverifiedUnits: List<String>,
    val incompleteUnits: List<String>,
    val inDoubtUnits: List<String>,
    val operatorRequired: Boolean,
    val residualHold: String,
    val blockedBy: List<String>,
    val disposition: String,
    val recordedAt: Instant,
) {
    companion object {
        fun of(row: ResponseLogRow) = row.content.let {
            JobResponseLogView(
                row.instanceId, row.jobResponseId, it.jobOrderId, row.executionId, it.version, it.physicalState, it.requiredEvidence,
                it.reachedEvidence, it.completedUnits, it.unverifiedUnits, it.incompleteUnits, it.inDoubtUnits, it.operatorRequired,
                it.residualHold, it.blockedBy, row.disposition.name, row.recordedAt,
            )
        }
    }
}

/** `GET /host/job-responses` 의 본문. [responses] 는 최근부터 많아야 limit 개이고 [total] 은 자르기 전의 수다. */
data class JobResponsesView(val instanceId: String, val total: Int, val responses: List<JobResponseLogView>)

/** 결함 감수의 요청 id 가 이미 감수 행에 있다(V11). 아무것도 바꾸지 않았다. REST 는 409 `REQUEST_ID_REUSED` 다. */
class ReleaseRequestReused(val requestId: String) : RuntimeException("결함 수용의 요청 id 가 이미 쓰였다: $requestId")

/** 종결의 요청 id 가 이미 종결 행에 있다(V12). 아무것도 바꾸지 않았다. REST 는 409 `REQUEST_ID_REUSED` 다. */
class CloseRequestReused(val requestId: String) : RuntimeException("운영자 종결의 요청 id 가 이미 쓰였다: $requestId")

/**
 * 그 요청 id 의 종결을 아직 처리 중이다(호스트 잠금을 기다리거나 기체 스냅숏을 읽는 중). 종결 조회가 «종결 없음» 이라고 답하면 안 되는 때다. REST 는
 * 409 `REQUEST_IN_PROGRESS` 다.
 */
class CloseInProgress(val requestId: String) : RuntimeException("그 요청 id 의 운영자 종결을 아직 처리 중이다: $requestId")

/** 일지 쓰기가 실패했다(S4b 스펙 §9). 실행은 미들웨어에 남는다. 제출은 500 이다. */
class JournalWriteFailed(val executionId: String, cause: Throwable) : RuntimeException("실행 일지를 적지 못했다: $executionId", cause)

/**
 * 호스트 메모리의 크기([MissionHost.footprint]). [executions]·[responses] 는 미들웨어의 실행 표와 아웃박스이고, 나머지는 실행이나 작업 지시마다
 * 드는 호스트 쪽 맵이다.
 */
data class HostFootprint(
    val executions: Int,
    val responses: Int,
    val settleMarks: Int,
    val journaled: Int,
    val recorded: Int,
    val restoredFrom: Int,
    val cancels: Int,
)

/**
 * 미들웨어 실행 호스트(S3a 스펙 §7).
 *
 * ## 잠금 하나
 *
 * 미들웨어에는 스레드도 잠금도 없다. pump, 판정, 제출, 조회, 운영자 판단을 모두 [lock] 하나 아래에서 돈다. 잠금 순서는 호스트 잠금에서
 * mimic 엔진 잠금으로 한 방향뿐이다(gRPC 호출이 잠금 아래에서 나간다).
 *
 * 임무 버전 활성화도 이 잠금 아래에서 한다([exclusive], S3b 스펙 T2). 판정과 배정 사이에 활성화가 끼면 한 제출 안에서 판정
 * 계획과 실행 계획의 버전이 갈린다. DB 는 잠금 안에서 부를 수 있으나 그 반대(DB 연결을 쥔 채 이 잠금을 기다림)는 없다.
 *
 * 공정 잠금이다. 제출이 mimic 을 기다리느라 운영 서비스의 요청 제한을 넘기면 운영 서비스가 실행 목록으로 재조회하는데,
 * 먼저 기다린 제출이 먼저 잡아야 재조회가 제출보다 앞서 «반영 안 됨» 을 남기지 않는다. `synchronized` 는 순서를 보장하지
 * 않고, 가상 스레드가 그것을 기다리면 캐리어 스레드를 붙잡는다.
 *
 * ## pump
 *
 * 단일 스레드가 [PUMP_PERIOD] 마다 셀 대역 스냅숏을 **잠금 밖에서** 한 번 읽고, 잠금 아래에서 그 스냅숏을 셀 신호로 끼운 뒤
 * `pump()` 를 부른다. 셀 HTTP 가 늦어도 REST 가 그 뒤에 줄 서지 않는다.
 *
 * ## 판정(T3)
 *
 * 호스트는 스킬 적합과 도는 실행 없음만 본다. 시운전 완료와 연결 신선은 운영 서비스가 본다. 미들웨어의 배정 게이트는 이
 * 넷을 보지 않으므로 제출 때 호스트가 다시 판정해 통과한 기체만 `assign` 에 넘긴다. 미해소 잔여 파지 표시가 있는 기체도 뺀다
 * (P0-b2, [cancel]·[clearResidualHold]). 이미 실행이 있는 작업 지시의 개정판은 후보 대신 그 실행의 기체 하나를 판정한다([submit]).
 *
 * ## 작업 응답과 재기동(S4b 스펙)
 *
 * 상위 시스템이 없어 송신 기록(`mission.job_response_log`)이 전선 자리를 대신한다. pump 뒤 같은 잠금 안에서 응답을 적고 `ack`
 * 한다. 받은 작업 지시는 실행 일지에 적고 기동 때 [start] 가 다시 짓는다. 인시던트와 판단은 사본으로 남는다.
 *
 * 정착 이벤트는 정착마다 하나다. 개정판이나 운영자 판단으로 다시 돈 실행이 다시 정착하면 그 정착을 또 적는다. 정착 하나는 그 정착을
 * 알린 작업 응답(그 실행의 마지막 작업 응답)으로 가린다 — picasso 는 정착하지 않은 상태에서 정착한 상태로 옮길 때마다 작업 응답을 낸다.
 * 같은 판에서도 다시 정착할 수 있으므로(정착한 실행의 보류 단위를 운영자가 판단) 판으로 가리지 않는다.
 *
 * 다시 돎([recordReruns], V10): 이미 있던 실행이 받은 개정판은 개정판 행으로, 정착을 적은 실행이 다시 도는 것은 `REOPENED` 이벤트로, 그때의
 * 판단 기록과 한 트랜잭션에 적는다. 받은 제출과 운영자 판단 안에서 적고, 못 적었으면 pump 가 미들웨어 pump 전에 다시 적으며 적을 때까지
 * pump 와 신규 제출을 막는다([journalBlocked]). 그래서 다시 돈 실행의 로봇 명령은 그 기록이 커밋된 뒤에만 나간다. 기동 복원은 마지막이
 * `REOPENED` 인 일지 행을 가장 높은 받은 판으로 다시 짓는다.
 *
 * ## 놓기(picasso `Middleware.forget`, 한계 §15.224 · 놓기 소비자)
 *
 * pump 뒤 기록이 커밋되고 응답을 `ack` 한 뒤, 지금의 정착을 적었고 활성이 아닌([ExecutionPages.active]) 실행을 picasso 메모리에서 놓는다.
 * `PHYSICALLY_DONE`·`ABORTED` 는 곧 놓고, 개정판을 받을 수 있는 `FAILED`·`UNVERIFIED` 는 [revisableRetention] 동안 두었다가 놓는다 —
 * 놓은 작업 지시는 picasso 가 판과 무관하게 받지 않는다(`FORGOTTEN_JOB_ORDER`). 놓으면 그 실행의 호스트 쪽 맵도 지운다. 실행과
 * 아웃박스는 활성 실행과 두는 실행만큼만 남는다. 놓은 실행은 인자 없는 목록과 좁힌 목록이 정착 이벤트의 본문으로 싣는다([executions]).
 * 재기동 뒤에는 picasso 가 놓은 작업 지시를 모르므로 실행 일지가 같은 작업 지시 id 의 새 실행을 막는다([submit]).
 *
 * ## 판단 기록(picasso 한계 §15.221 · 판단 기록 영속)
 *
 * 미들웨어의 판단 기록(`Middleware.judgmentRecord`)을 `mission.execution_judgment_record` 에 덧붙인다. 받는 때는 수락 직후, pump 뒤
 * 기록(판정·검증 결과·정착, 송신 기록과 한 트랜잭션), 운영자 판단 직후다. 직전에 적은 글자와 같으면 적지 않는다. 기동 복원은 그
 * 작업 지시의 가장 최근 기록을 `resume` 에 넘긴다. 못 읽는 기록과 picasso 가 거부한 기록(`RESUME_RECORD_MISMATCH`)은 GAVE_UP 으로
 * 보고하고 기록 없이 다시 짓지 않는다. 결함 감수([release])는 감수 직후 기록을 적어 `acknowledgedFaults` 칸이 재기동을 넘는다. 이
 * 호스트는 조치 승인(`approveRemedy`)을 부르지 않으므로 `approval` 칸은 이 호스트가 지은 실행에서 늘 비어 있다. 기록이 들고 오면 그대로
 * 넘긴다.
 *
 * ## 인스턴스와 심장박동(HA 설계 기록 §8 단계 0)
 *
 * [heartbeat] 주기마다 `mission.host_instance` 의 자기 행을 DB 시각으로 고치고, 닫을 때 멈춤을 적는다. 기동 복원은 그 행을 든
 * 인스턴스(바로 앞 RESTORED 이벤트의 인스턴스, 없으면 받은 인스턴스)가 이 인스턴스이거나 심장박동이 끊긴 행만 다시 짓는다. 살아 있는
 * 다른 인스턴스가 든 행은 DEFERRED([HELD_BY_LIVE_INSTANCE])로 미루고 그 기체를 판정에서 빼며, pump 마다 다시 보아 만료되면 다시 짓는다.
 *
 * @param robots 하위 포트. 판정의 케이퍼빌리티도 이것으로 묻는다(`PicassoClient` 가 세대별로 캐시한다). 그 캐시는 잠금 밖에서
 *   안전하지 않으므로 케이퍼빌리티는 늘 [lock] 아래에서 묻는다.
 * @param catalog 임무 카탈로그(S3b 스펙 T1). 미들웨어에 넘긴 것과 같은 참조로 스킬 적합을 판정한다. 기동 때 DB 의 활성 버전으로
 *   세운 것을 받는다.
 * @param siteTimings 현장 시간값(S3c 스펙 §7.1). 미들웨어에 그대로 넘긴다. `null` 을 주는 동안은 미적용이라 작업 지시를 받지 않는다.
 *   모의 실행의 별도 미들웨어에는 주지 않는다(T10).
 * @param revisableRetention `FAILED`·`UNVERIFIED` 로 정착한 실행을 정착 기록 뒤 picasso 메모리에 두는 폭(호스트 시계,
 *   `host.settled.revisable-retention`). 그 안에서는 같은 작업 지시 id 의 개정판을 받는다. 지나면 놓고 그 작업 지시는 다시 받지 않는다
 *
 * ## 미적용(S3c 스펙 T8)
 *
 * 첫 읽기가 성공하기 전에는 판정이 기체마다 [UNAPPLIED_REASON] 을 더해 통과시키지 않는다. 그래서 제출은 `assign` 에 기체를 하나도
 * 넘기지 않고, 미들웨어는 기체 없는 채택을 사유 없는 UNASSIGNED 로 돌려준다(이유는 [SubmitOutcome.excluded], S3a JSON 계약 그대로).
 * 기본값으로 대신하지 않는다. 나머지 경로는 시간값을 쓰지 않으므로 그대로 동작한다.
 *
 * ## 작업 허가(ADR 52)
 *
 * [permits] 는 현장이 작업 허가 대응을 선언했을 때만 있다. 있으면 미들웨어의 허가 포트로 붙이고, pump 마다 잠금 밖에서 읽은 셀 대역
 * 스냅숏을 잠금 아래에서 넘긴다([CellWorkPermits.observe]). 포트의 읽기는 그 스냅숏으로 지어 둔 판독이라 잠금 아래에서 기다리지 않는다.
 * 없으면 포트를 붙이지 않아 발행 거동이 오늘 그대로다. 허가는 안전 계통 상태를 비춘 정보이고 이 호스트는 그것으로 새 로봇 명령만 내지
 * 않는다.
 *
 * ## 기체 퇴역(picasso §15.249)
 *
 * [retirements] 는 registry 주소를 설정했을 때만 있다. 있으면 미들웨어의 퇴역 포트로 붙인다. 포트의 읽기는 읽기 주기가 잠금 밖에서 읽어
 * 둔 스냅숏이라 잠금 아래에서 기다리지 않는다. 미들웨어는 퇴역한 기체로 새 로봇 명령을 내지 않고(`Execution.permitBlock`, 까닭 `RETIRED`)
 * 재할당 후보에서도 뺀다. 도는 단위는 끊지 않는다. 없으면 붙이지 않아 발행 동작이 오늘 그대로다.
 *
 * ## 인시던트 고리(picasso H1 `host-kit`, 한계 §15.176)
 *
 * [entitlements] 는 자동 승인 자격 선언이다. 미들웨어에 그대로 넘긴다. 기본값은 선언 없음이라 값 없는 승인([attemptApproval])은 모두
 * `NOT_DECLARED` 로 거부된다. [export] 를 주면 pump 마다 잠금 아래에서 인시던트 대장·조치 탐색 대장·안내·작업 응답을 그 디렉터리에
 * 쓴다([IncidentExport]). 둘 다 기본값이면 오늘의 동작 그대로다. 승인 호스트(`POST /approvals`)는 이 클래스 밖에서 열고
 * [attemptApproval] 을 부른다.
 *
 * ## 작업 지시 철회(B3)
 *
 * [withdraw] 는 실행이 없는 작업 지시를 영구히 막는다(`job_order_withdrawal`, V16). 운영 서비스의 대체 접수와 스테이션 현장 해제가 «그 작업 지시는
 * 보내지 않았다» 를 증명하는 유일한 길이다 — 값 없는 승인은 운영 서비스를 거치지 않고 실행을 세우므로 운영 DB 만으로는 증명이 안 된다. 판정과 기록이
 * 호스트 잠금 하나 아래라 승인과 철회 가운데 먼저 잠금을 잡은 쪽이 이긴다. 철회된 작업 지시는 값 없는 승인([attemptApproval])이 `REMEDY_NOT_APPLIED`
 * 로, 새 접수([submit])가 [WITHDRAWN_JOB_ORDER] 로 거부한다. 사람이 값을 넣는 조치 승인(`approveRemedy`) 경로를 이 호스트에 열면 그 경로도 같은
 * 검사를 지나야 한다. 실행 취소([cancel])의 `NOT_FOUND` 는 미실행 증거로 쓰지 않는다 — 미실행 작업 지시를 폐기하는 취소를 만들면 같은 철회를 거친다.
 */
class MissionHost(
    private val robots: RobotPort,
    private val cellBand: CellBandClient,
    private val clock: HostClock,
    private val catalog: StoredMissionCatalog = StoredMissionCatalog(),
    private val siteTimings: SiteTimingsSource,
    private val records: HostRecords,
    private val store: MissionStore,
    private val json: ObjectMapper,
    private val permits: CellWorkPermits? = null,
    private val heartbeat: HostHeartbeat = HostHeartbeat.DEFAULT,
    private val revisableRetention: Duration = DEFAULT_REVISABLE_RETENTION,
    private val retirements: RetirementPort? = null,
    private val entitlements: Entitlements = Entitlements.None,
    export: Path? = null,
) : AutoCloseable {
    init {
        require(!revisableRetention.isNegative) { "개정판을 받을 실행을 두는 폭은 음수가 아니어야 한다: $revisableRetention" }
    }

    private val lock = ReentrantLock(true)
    private val signals = CellBandSignals()

    private val middleware = Middleware(
        robots = robots, cell = signals, now = clock::now, missions = catalog, siteTimings = siteTimings, permits = permits,
        retirements = retirements, entitlements = entitlements,
    )

    /** 인시던트 고리로 내는 파일. [export] 를 주었을 때만 있다. 호스트 잠금 아래에서만 부른다. */
    private val exported: IncidentExport? = export?.let { IncidentExport(it, middleware.instanceId) }

    private var pumpedAt: Instant? = null
    private var latestCell: CellSnapshot? = null

    // ── S4b 기록. 모두 호스트 잠금 아래에서만 읽고 쓴다.

    /**
     * 이 인스턴스에서 일지 행이 있는 실행의 작업 지시 id 에서 일지에 적은 판으로(제출로 적었거나 다시 지은 것, 받은 개정판을 적으면 그 판).
     * 정착 이벤트는 이것만 적는다. 실행의 판이 이보다 높으면 개정판을 아직 적지 않은 것이다([rerunOf]). 놓은 실행의 것은 지운다(같은 작업
     * 지시 id 의 새 실행은 picasso 와 실행 일지가 막는다).
     */
    private val journaled = mutableMapOf<String, Int>()

    /**
     * 이 인스턴스가 실행마다 마지막으로 적은 정착(실행 id 에서). [SettleMark.responseId] 가 그 실행의 마지막 작업 응답과 같고 실행이 정착해
     * 있으면 지금의 정착을 적은 것이다([settleRecorded]). 다시 돌아 다시 정착하면 마지막 작업 응답이 바뀌어 또 적는다. 놓으면 지운다.
     */
    private val settleMarks = mutableMapOf<String, SettleMark>()

    /**
     * 적은 정착 하나. [at] 은 기록을 커밋한 뒤의 호스트 시각이고 개정판을 받을 실행을 두는 폭을 여기서 잰다. [reopened] 는 그 정착 뒤
     * 다시 돎(`REOPENED`)을 적었는가다. 다시 정착하면 새 표시로 바뀐다.
     */
    private data class SettleMark(val responseId: String?, val at: Instant, val reopened: Boolean = false)

    /** 사본을 적은 인시던트 수. 미들웨어의 인시던트 목록은 덧붙이기만 하므로 이 색인 뒤가 새로 봉인된 것이다. */
    private var copiedIncidents = 0

    /** 판단 행을 적은 인시던트 id. */
    private val copiedResolutions = mutableSetOf<String>()

    /** 이번 기동의 복원 시각. [start] 전에는 `null` 이다. */
    private var restoredAt: Instant? = null

    /** 이번 기동의 복원 보고 행. 일지의 받은 순서이고, DEFERRED 행은 결과가 바뀌면 그 자리에서 갈린다. */
    private val restoreRows = mutableListOf<RestoreRowView>()

    /** 다시 지은 실행 id 에서 바로 앞 인스턴스의 실행으로. 놓은 실행의 것은 지운다(정착 이벤트의 본문이 든다). */
    private val restoredFrom = mutableMapOf<String, RestoredFromView>()

    /** 기체 스냅숏을 못 읽어 미룬 일지 행(T3). pump 마다 다시 시도하고 그동안 그 기체를 판정에서 뺀다(T4). */
    private val deferred = mutableListOf<Deferred>()

    /**
     * 포기했고 정착하지 않은 일지 행(T4). 그 기체의 스냅숏에 그 작업 지시의 종료하지 않은 태스크가 없어질 때까지 그 기체를 판정에서 뺀다.
     * 이전 기동에서 포기한 행도 든다. 풀린 행은 이 인스턴스에서 다시 빼지 않는다.
     */
    private val gaveUp = mutableListOf<JournalRow>()

    /**
     * 포기했고 아직 운영자가 종결하지 않은 일지 행(결함 14). [gaveUp] 과 달리 스냅숏이 동의해도 빼지 않는다 — 판정 제외가 풀린 뒤에도 사람은
     * 그 행을 종결할 수 있어야 한다. 이전 기동에서 포기한 행도 기동 때 DB 에서 다시 읽어 든다. 종결하면 뺀다.
     */
    private val unclosed = LinkedHashMap<String, Unclosed>()

    /**
     * 정지 증언(`ATTESTED`)으로 종결했고 그 기체의 스냅숏이 아직 동의하지 않은 일지 행. 포기한 행([gaveUp])과 같은 규칙으로 판정이 그 기체를
     * 빼고, 스냅숏이 그 작업 지시의 종료하지 않은 태스크가 없다고 보이면 푼다. 기동 때 DB 에서 다시 읽는다.
     */
    private val attested = mutableListOf<Attested>()

    /**
     * 미해소 잔여 파지 표시(P0-b2). 기동 때 DB 에서 다시 읽고, 정착 기록 트랜잭션이 커밋된 뒤와 해소를 적은 뒤에 고친다. 판정이 이것을
     * 읽어 그 기체를 뺀다. 원본은 DB 이고 이것은 그 사본이다.
     */
    private val openHolds = mutableListOf<ResidualHoldRow>()

    /**
     * 관문 확인용으로 넘긴 표시 기체에 이번 인스턴스가 세운 조치 제안과 그때 열려 있던 표시 id. (기체, 작업 지시)에서. 값 없는 승인의 표시 예외는
     * 여기 있는 제안에만 준다([attemptApproval]) — 표시가 서기 전에 선 묵은 제안이나, 뒤 시도에서 관문이 통과시킨 기체의 남은 제안은 받지 않는다.
     * 메모리에만 둔다. picasso 의 제안 장부도 메모리라 재기동 뒤에는 같이 없다.
     */
    private val trialProposals = mutableMapOf<Pair<String, String>, Set<Long>>()

    /**
     * 이 인스턴스에서 건 운영자 취소. 실행 id 에서. 이전 인스턴스의 취소는 그 작업 지시를 다시 지은 실행에 다시 건 것만 싣는다([carryCancel]).
     * 놓은 실행의 것은 지운다(정착 이벤트의 본문과 `execution_cancel` 표에 남는다).
     */
    private val cancels = mutableMapOf<String, CancelRow>()

    /**
     * 이 인스턴스가 작업 지시마다 마지막으로 적은(또는 복원에 넘긴) 판단 기록 글자. 같은 글자는 다시 적지 않는다. 적기가 실패하면
     * 갱신하지 않으므로 다음 pump 가 다시 적는다. 놓은 실행의 것은 지운다.
     */
    private val recorded = mutableMapOf<String, String>()

    /** 처리 중인 운영자 종결의 요청 id. 프로세스 안에만 있다. [closeUnrestored] 가 잠금을 기다리기 전에 넣고 끝나면 뺀다. */
    private val closing: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** 종결하지 않은 포기 행. [previous] 는 포기한 실행(앞 인스턴스의 것), [reason]·[since] 는 마지막 GAVE_UP 이벤트의 사유와 DB 시각이다. */
    private class Unclosed(val row: JournalRow, val previous: RestoredFromView, val reason: String, val since: Instant)

    /** 증언으로 종결한 행과 그 종결 행. */
    private class Attested(val row: JournalRow, val close: CloseRow)

    /**
     * 미룬 행. [order]·[mission] 은 [prepare] 가 한 번 읽은 것이고 다시 시도할 때 그대로 쓴다. 행을 든 다른 인스턴스가 살아 있어 미룬 행은
     * 아직 읽지 않았다(`null`) — 읽지 못하는 행도 그 인스턴스의 것이라 GAVE_UP 으로 적지 않는다. [heldBy] 는 그 동안 그 인스턴스다. 그 심장박동이
     * 끊긴 것을 보면 `null` 로 두고 보통의 다시 시도로 넘어간다.
     */
    private class Deferred(val row: JournalRow, val index: Int, var heldBy: String? = null) {
        var order: JobOrder? = null
        var mission: ActiveMission? = null
    }

    private val pumper = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-pump").apply { isDaemon = true } }

    /** 심장박동은 pump 와 다른 스레드다. 호스트 잠금을 잡지 않으므로 pump 나 제출이 오래 걸려도 늦지 않는다. */
    private val beater = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-heartbeat").apply { isDaemon = true } }

    val instanceId: String = middleware.instanceId

    /**
     * 인스턴스 행을 넣고, 실행을 복원하고, pump 와 심장박동을 시작한다. [MissionHostApplication] 이 빈을 만들 때 부른다. 복원이 빈 생성
     * 안에서 끝나므로 웹 서버가 열리기 전에 판정이 도는 실행을 안다(S4b 스펙 T3). 인스턴스 행이나 복원에서 DB 가 실패하면 예외가 나가
     * 기동이 멈춘다.
     */
    fun start(period: Duration = PUMP_PERIOD): MissionHost = apply {
        records.beat(instanceId, heartbeat.expiry)
        restore()
        beater.scheduleWithFixedDelay(::beatOnce, heartbeat.period.toMillis(), heartbeat.period.toMillis(), TimeUnit.MILLISECONDS)
        pumper.scheduleWithFixedDelay(::pumpOnce, 0, period.toMillis(), TimeUnit.MILLISECONDS)
    }

    /** 심장박동 한 번. 실패는 삼키고 다음 주기에 다시 한다. 만료 폭 안에 다시 적지 못하면 다른 인스턴스가 이 인스턴스를 끊긴 것으로 읽는다. */
    private fun beatOnce() {
        try {
            records.beat(instanceId, heartbeat.expiry)
        } catch (e: Exception) {
            log.warn("심장박동 실패: {}", e.toString())
        }
    }

    /**
     * 기동 복원(S4b 스펙 T3, §6.3). 도는 일지 행(정착·다시 돎·포기가 없거나 마지막이 다시 돎, [HostRecords.openJournal])을 받은 순서대로
     * 다시 짓는다. 결과는 일지 이벤트로 남고 복원 보고에 실린다. 그 뒤 포기한 채인 행(이전 기동의 것 포함)을 판정 제외 목록으로, 미해소
     * 잔여 파지 표시를 판정이 읽을 사본으로 읽는다.
     *
     * 단계 0 범위(HA 설계 기록 §7): 행을 든 인스턴스가 이 인스턴스이거나 심장박동이 끊긴 행만 지금 다시 짓는다. 살아 있는 다른
     * 인스턴스가 든 행은 DEFERRED 로 미룬다([restoreRow]).
     */
    private fun restore() = lock.withLock {
        restoredAt = clock.now()
        records.openJournal().forEach { row -> restoreRow(row) }
        // 이번 기동에서 포기한 행은 attempt 가 이미 넣었고 그 GAVE_UP 이벤트도 일지에 있다. 작업 지시마다 한 번만 든다.
        val abandoned = records.gaveUpJournal()
        gaveUp += abandoned.filter { row -> gaveUp.none { it.jobOrderId == row.jobOrderId } }
        abandoned.filter { it.jobOrderId !in unclosed }.forEach { row ->
            val event = records.events(row.jobOrderId).last { it.kind == JournalEventKind.GAVE_UP }
            unclosed[row.jobOrderId] = Unclosed(row, previousOf(row), event.detail.orEmpty(), event.recordedAt)
        }
        records.attestedClosures().forEach { row -> attested += Attested(row, checkNotNull(records.closeOf(row.jobOrderId))) }
        openHolds += records.openResidualHolds()
    }

    /** 이 일지 행을 든 바로 앞 인스턴스와 실행. 마지막 RESTORED 이벤트의 것이고 없으면 받은 인스턴스와 실행이다. */
    private fun previousOf(row: JournalRow): RestoredFromView =
        records.events(row.jobOrderId).lastOrNull { it.kind == JournalEventKind.RESTORED }
            ?.let { RestoredFromView(it.instanceId, it.executionId!!) }
            ?: RestoredFromView(row.instanceId, row.executionId)

    private fun restoreRow(row: JournalRow) {
        val previous = previousOf(row)
        val index = restoreRows.size
        restoreRows += RestoreRowView(row.jobOrderId, row.robotId, previous.instanceId, previous.executionId, RestoreResult.GAVE_UP.name, null, null)
        // 행을 든 인스턴스는 바로 앞 RESTORED 의 인스턴스, 없으면 받은 인스턴스다([previous]). 살아 있으면 가져가지 않는다. 작업 지시를 읽기 전에 본다 —
        // 읽지 못하는 행을 먼저 GAVE_UP 으로 적으면 살아 있는 인스턴스의 행이 운영자 종결 대상이 된다.
        val holder = liveHolder(previous)
        if (holder != null) {
            val waiting = Deferred(row, index, heldBy = holder)
            deferred += waiting
            settleRestore(row, index, previous, RestoreResult.DEFERRED, null, "$HELD_BY_LIVE_INSTANCE: instance=$holder")
            return
        }
        attempt(Deferred(row, index), previous, first = true)
    }

    /** 이 일지 행을 든 인스턴스가 이 인스턴스가 아니고 지금 살아 있으면 그 id 다. */
    private fun liveHolder(previous: RestoredFromView): String? =
        previous.instanceId.takeIf { it != instanceId && records.instanceLive(it) }

    /**
     * 미룬 행의 작업 지시와 임무 버전을 읽어 [Deferred] 에 둔다. 이미 읽었으면 다시 읽지 않는다. 읽지 못하면 GAVE_UP 사유를 돌려준다.
     * 받은 개정판이 있으면 가장 높은 판이다(V10). picasso `resume` 은 이전 인스턴스가 받은 마지막 판을 받아야 한다.
     */
    private fun prepare(waiting: Deferred): String? {
        if (waiting.order != null) return null
        val row = waiting.row
        return try {
            val order = HostRequests.jobOrder(json.readTree(records.latestRevision(row.jobOrderId)?.jobOrder ?: row.jobOrder))
            val version = row.missionVersion?.let {
                store.version(row.workMasterId, it) ?: throw IllegalStateException("임무 버전 행이 없다: ${row.workMasterId} 버전 $it")
            }
            waiting.mission = catalog.mission(row.workMasterId, version)
            waiting.order = order
            null
        } catch (e: BadRequest) {
            "일지의 작업 지시를 읽지 못했다: ${e.message}"
        } catch (e: IllegalStateException) {
            e.message ?: e.toString()
        }
    }

    /**
     * 미룬 행을 다시 시도한다(T3). pump 가 미들웨어 pump 전에 잠금 아래에서 부른다. 이벤트를 못 적으면 다음 pump 에 다시 한다.
     * 다른 인스턴스가 들어 미룬 행은 그 인스턴스의 심장박동이 끊길 때까지 시도하지 않는다.
     */
    private fun retryDeferred() {
        deferred.toList().forEach { waiting ->
            val previous = restoreRows[waiting.index].let { RestoredFromView(it.previousInstanceId, it.previousExecutionId) }
            try {
                // 다른 인스턴스가 그 사이 종결했으면 이 인스턴스의 메모리가 든 채로 리빌드하지 않는다. 일지는 DB 가 원본이다.
                records.closeOf(waiting.row.jobOrderId)?.let { closed ->
                    forgetClosed(waiting.row, closed)
                    return@forEach
                }
                val holder = waiting.heldBy
                if (holder != null) {
                    if (records.instanceLive(holder)) return@forEach
                    waiting.heldBy = null
                }
                attempt(waiting, previous, first = false)
            } catch (e: Exception) {
                log.warn("미룬 복원 다시 시도 실패: {} {}", waiting.row.jobOrderId, e.toString())
            }
        }
    }

    private fun attempt(waiting: Deferred, previous: RestoredFromView, first: Boolean) {
        val (result, executionId, reason) = prepare(waiting)?.let { Triple(RestoreResult.GAVE_UP, null, it) } ?: resumed(waiting)
        if (result == RestoreResult.DEFERRED) {
            if (first) {
                deferred += waiting
                settleRestore(waiting.row, waiting.index, previous, result, null, reason)
            } else if (restoreRows[waiting.index].reason != reason) {
                // 미룬 까닭이 바뀌었다(다른 인스턴스의 만료 → 스냅숏). 이벤트는 미룬 기동마다 한 번이라 보고 행만 고친다.
                restoreRows[waiting.index] = restoreRows[waiting.index].copy(reason = reason)
            }
            return
        }
        val event = settleRestore(waiting.row, waiting.index, previous, result, executionId, reason, waiting.order?.version)
        deferred.remove(waiting)
        if (result == RestoreResult.GAVE_UP) {
            gaveUp += waiting.row
            unclosed[waiting.row.jobOrderId] = Unclosed(waiting.row, previous, reason.orEmpty(), event.recordedAt)
        }
    }

    /**
     * 결과를 일지 이벤트로 적고 보고 행을 갈아 끼운다. 이벤트를 먼저 적는다. 못 적으면 예외가 나가고 보고는 그대로다. [version] 은 다시 지은
     * 작업 지시의 판이다(RESTORED 일 때). 적은 이벤트를 돌려준다.
     */
    private fun settleRestore(
        row: JournalRow, index: Int, previous: RestoredFromView, result: RestoreResult, executionId: String?, reason: String?, version: Int? = null,
    ): JournalEvent {
        val kind = when (result) {
            RestoreResult.RESTORED -> JournalEventKind.RESTORED
            RestoreResult.DEFERRED -> JournalEventKind.DEFERRED
            RestoreResult.GAVE_UP -> JournalEventKind.GAVE_UP
        }
        val event = records.event(row.jobOrderId, kind, instanceId, executionId, reason)
        restoreRows[index] = RestoreRowView(row.jobOrderId, row.robotId, previous.instanceId, previous.executionId, result.name, executionId, reason)
        if (executionId != null) {
            restoredFrom[executionId] = previous
            journaled[row.jobOrderId] = checkNotNull(version) { "다시 지은 작업 지시의 판이 없다: ${row.jobOrderId}" }
        }
        return event
    }

    /**
     * 미들웨어에 다시 넣는다(picasso `Middleware.resume`, S4b 스펙 T1). 기체 스냅숏을 못 읽은 거부는 DEFERRED, 그 밖의 거부는
     * GAVE_UP 이다. Idempotent 는 이번 기동에서 이미 다시 지은 행을 또 부른 경우(앞선 이벤트 쓰기가 실패한 다시 시도)뿐이고
     * 그 실행으로 RESTORED 다.
     *
     * 그 작업 지시의 가장 최근 판단 기록을 시도마다 읽어 넘긴다(미루는 동안 앞 인스턴스가 더 적었을 수 있다). 기록을 못 읽으면
     * `resume` 을 부르지 않고 GAVE_UP([RECORD_UNREADABLE]) 이다. 기록 없이 다시 지으면 읽지 못한 판단이 조용히 «없었다» 가 된다.
     * picasso 가 기록을 거부하면(`RESUME_RECORD_MISMATCH`) 그 문장 뒤에 기록 행 번호를 붙여 GAVE_UP 이다.
     *
     * 그 작업 지시에 운영자 취소가 적혀 있으면 새로 지은 실행에 다시 건다([carryCancel]). 취소 행도 `resume` 전에 읽는다 — 읽기가 실패해
     * 다시 시도하면 Idempotent 라 다시 걸 자리가 없다.
     *
     * @return 결과, RESTORED 의 실행 id, DEFERRED·GAVE_UP 의 사유
     */
    private fun resumed(waiting: Deferred): Triple<RestoreResult, String?, String?> {
        val cancel = records.cancelOf(waiting.row.jobOrderId)
        val stored = records.latestJudgmentRecord(waiting.row.jobOrderId)
        val record = stored?.let {
            try {
                JudgmentRecordWire.decode(it.record)
            } catch (e: Exception) {
                return Triple(RestoreResult.GAVE_UP, null, "$RECORD_UNREADABLE: record=${it.recordId}, ${e.message}")
            }
        }
        return when (val submission = middleware.resume(checkNotNull(waiting.order), waiting.row.robotId, checkNotNull(waiting.mission), record)) {
            is Middleware.Submission.Accepted -> {
                stored?.let { recorded[waiting.row.jobOrderId] = it.record }
                cancel?.let { carryCancel(it, submission.execution) }
                Triple(RestoreResult.RESTORED, submission.execution.executionId, null)
            }
            is Middleware.Submission.Idempotent -> Triple(RestoreResult.RESTORED, submission.execution.executionId, null)
            is Middleware.Submission.Rejected ->
                if (submission.reason.startsWith(Middleware.RESUME_SNAPSHOT_UNREADABLE)) {
                    Triple(RestoreResult.DEFERRED, null, submission.reason)
                } else if (stored != null && submission.reason.startsWith(Middleware.RESUME_RECORD_MISMATCH)) {
                    Triple(RestoreResult.GAVE_UP, null, "${submission.reason} (record=${stored.recordId})")
                } else {
                    Triple(RestoreResult.GAVE_UP, null, submission.reason)
                }
            is Unassigned -> Triple(RestoreResult.GAVE_UP, null, "배정되지 않았다: ${submission.refusals}")
        }
    }

    /**
     * 기동 복원이 다시 지은 실행에 운영자 취소([cancel])를 다시 건다. 취소 행은 새로 적지 않는다 — 취소는 사람이 한 번 건 것이고 picasso 의
     * 취소 요청은 그 실행에 붙어 다시 돌아도 풀리지 않는다. 그래서 다시 지은 실행은 그 작업 지시의 처음 취소 행을 싣고([ExecutionView.cancel])
     * 운영자 취소는 이미 건 실행으로 받지 않는다.
     *
     * 다시 지은 직후, 첫 pump 전에 건다. 기체에 그 작업 지시의 끝나지 않은 태스크가 있으면 첫 pump 가 `StartTask` 없이 그 태스크에 붙어
     * 취소를 보내고 그 종료를 본 뒤 정착한다. 이미 끝난 태스크면 취소를 보내지 않고 그 종료로 판정하며, 스냅숏을 못 읽으면 정착하지 않고
     * 기다린다(picasso `Middleware.resume`·`attachForCancel`, §15.231). 첫 pump 가 붙은 뒤로 미루면 첫 pump 가 그 태스크에 `StartTask` 를 다시
     * 보낸다 — 그 사이 끝난 태스크를 들지 않는 하위에서는 새 접수가 되어 기체가 다시 움직인다.
     *
     * picasso 가 붙는 것은 로봇 단위의 태스크뿐이다. 플릿 운반이 돌던 실행은 첫 pump 가 플릿에 취소를 보내지 않고 정착한다. 기체에 끝나지
     * 않은 태스크가 없는 경우라 앞 방식(기체에 끝나지 않은 태스크가 있을 때만 미룸)에서도 곧바로 걸었고 거동이 같다.
     *
     * 운영자 취소 입구(`cancelByOperator`)가 아니라 `cancel` 이다 — 사람의 걸음은 앞 인스턴스에서 이미 났고 그 인시던트의 판단 칸에 실렸다. 다시
     * 지은 실행이 다시 연 보류의 인시던트에 지금 시각으로 다시 실으면 사람이 다시 닫은 것처럼 읽힌다.
     */
    private fun carryCancel(cancel: CancelRow, execution: Middleware.Execution) {
        cancels[execution.executionId] = cancel
        middleware.cancel(execution.executionId)
    }

    private fun restoreView(): RestoreView? = restoredAt?.let { RestoreView(it, restoreRows.toList()) }

    /** pump 한 번. 예외를 삼키고 다음 주기에 다시 돈다. 스케줄러는 작업이 예외를 던지면 다음 실행을 멈춘다. */
    fun pumpOnce() {
        try {
            val cell = cellBand.fetch()
            // 작업 허가 줄의 관측 시각은 이 층이 셀 대역을 읽은 시각이다. 잠금을 기다린 시간은 관측의 나이에 든다.
            val fetchedAt = clock.now()
            lock.withLock {
                signals.snapshot = cell
                latestCell = cell
                permits?.observe(cell, fetchedAt)
                val at = clock.now()
                retryDeferred()
                // 다시 돈 실행의 기록이 커밋되기 전에는 미들웨어 pump 를 돌리지 않는다(그 실행의 로봇 명령이 pump 에서 나간다).
                recordReruns()
                if (journalBlocked().isNotEmpty()) return@withLock
                (robots as? HostRobotPort)?.readNowFor(startingRobots())
                middleware.pump()
                pumpedAt = at
                // 기록 뒤에는 정착한 실행을 놓아 그 작업 응답이 아웃박스에서 빠질 수 있다. 그 전에 사본에 담는다.
                exportIncidents()
                recordAfterPump()
            }
        } catch (e: Exception) {
            log.warn("pump 실패: {}", e.toString())
        }
    }

    /**
     * 인시던트 고리로 파일을 낸다. pump 직후, 기록과 놓기([recordAfterPump]) 전에 부른다. 실패는 pump 를 멈추지 않는다 — 다음 pump 가 같은
     * 사본을 다시 쓴다. 호스트 잠금 아래에서 부른다.
     */
    private fun exportIncidents() {
        val export = exported ?: return
        try {
            export.observe(middleware.incidents(), middleware.remedySearches(), middleware.responses(), clock.now())
        } catch (e: Exception) {
            log.warn("인시던트 고리 내보내기 실패: {}", e.toString())
        }
    }

    /**
     * 값 없는 승인 시도(picasso `Middleware.attemptApproval`, ADR 44). 승인 호스트가 부른다. 호스트 잠금 아래에서 판정하고, 승인으로 새 실행이
     * 서면 제출과 같이 실행 일지를 적는다 — 적지 않으면 일지에 없는 실행이 생겨 pump 와 제출이 막힌다([journalBlocked]). 일지 쓰기가
     * 실패하면 [JournalWriteFailed] 가 나가고 제출과 같이 호스트 복구 전까지 막힌다.
     *
     * 실행이 없는 작업 지시가 실행 일지에 이미 있으면(정착했거나 포기한 작업 지시) 미들웨어에 넘기지 않고 `REMEDY_NOT_APPLIED` 로 거부한다.
     * 제출의 [journalRefusal] 과 같은 확인이고, 제안을 소모하지 않는다. 철회된 작업 지시([withdraw])도 같은 자리에서 `REMEDY_NOT_APPLIED` 로
     * 거부한다 — 잔여 파지 표시 예외보다 앞이라 관문 확인으로 선 제안도 받지 않는다. 사람이 값을 넣는 조치 승인 경로를 이 호스트에 열면 그 경로도
     * 이 검사를 지나야 한다.
     *
     * **판정이 막은 기체에는 승인으로도 실행을 세우지 않는다.** picasso 0.11.0 부터 도는 단위 없는 기체에도 제안이 서므로(§15.316) 이 경로가 실제로
     * 닿는다. 승인 접수는 미들웨어 관문을 다시 지나지만 관문은 이 호스트의 제한(진행 중인 실행, 복원 못 한 행, 증언 종결 확인 전, 잔여 파지 표시,
     * 현장 시간값 미적용, 실행 일지 막힘)을 모르므로 [robotRestrictions] 로 먼저 보고 `REFUSED_BY_GATE` 로 거부한다. 제안은 지우지 않는다.
     *
     * **발신자 거부 뒤 회복 제안(picasso §15.320)은 맨 앞에서 미들웨어에 그대로 넘긴다.** 그 제안은 사람만 승인하고 값 없는 문은 늘
     * `HUMAN_APPROVAL_REQUIRED`(대상이 낡았으면 `REMEDY_NOT_APPLIED`)를 받으므로 이 문으로는 실행도 조치도 서지 않는다. 호스트 판정이 먼저
     * `REFUSED_BY_GATE` 로 답하면 부르는 쪽이 «나중에 다시» 로 읽고 사람에게 넘기지 않는다. 이 호스트는 사람의 회복 입구(`approveRecovery`)를
     * 아직 열지 않았다 — 그 보류는 운영자 판단(재작업·완료 확인)이나 운영자 취소로 닫는다.
     */
    fun attemptApproval(attempt: ApprovalAttempt): ApprovalOutcome = lock.withLock {
        if (middleware.proposalTarget(attempt.robotId, attempt.jobOrderId) is ProposalTarget.Hold) {
            return@withLock approve(middleware, attempt).also {
                check(it !is ApprovalOutcome.Approved) { "값 없는 문이 회복 제안을 승인했다: ${attempt.robotId} ${attempt.jobOrderId}" }
            }
        }
        if (journalBlocked().isNotEmpty()) {
            return@withLock ApprovalOutcome.Refused(ApprovalRefusal.REFUSED_BY_GATE, "실행 일지 저장 실패: 호스트 복구 전 승인 차단")
        }
        val existing = middleware.executions().firstOrNull { it.order.jobOrderId == attempt.jobOrderId }
        val existed = existing != null
        if (!existed) {
            // 철회는 잔여 파지 표시 예외보다 앞이다 — 대체 접수가 이긴 작업 지시의 낡은 제안은 관문 확인으로 섰어도 실행을 세우지 않는다.
            withdrawalRefusal(attempt.jobOrderId)?.let { return@withLock ApprovalOutcome.Refused(ApprovalRefusal.REMEDY_NOT_APPLIED, it) }
            journalRefusal(attempt.jobOrderId)?.let { return@withLock ApprovalOutcome.Refused(ApprovalRefusal.REMEDY_NOT_APPLIED, it) }
        }
        // **승인도 판정이 막은 기체에는 실행을 세우지 않는다.** 미들웨어 관문은 이 호스트의 제한을 모른다. 제안은 지우지 않는다(관문 거절과 같다).
        // 잔여 파지 표시만은 예외가 있다: 관문 확인으로 이번 인스턴스가 세운 제안이고 지금 열린 표시가 모두 그때 열려 있던 것이면 표시 사유를 뺀다.
        val markers = openHolds.filter { it.robotId == attempt.robotId }.map { it.markerId }
        val excepted = !existed && markers.isNotEmpty() && trialProposals[attempt.robotId to attempt.jobOrderId]?.containsAll(markers) == true
        robotRestrictions(attempt.robotId, siteTimings.current() != null, revising = existing?.executionId, holds = !excepted).takeIf { it.isNotEmpty() }?.let { reasons ->
            return@withLock ApprovalOutcome.Refused(ApprovalRefusal.REFUSED_BY_GATE, "실행 호스트 판정: ${reasons.joinToString("; ")}")
        }
        val outcome = approve(middleware, attempt)
        if (outcome is ApprovalOutcome.Approved) {
            if (!existed && excepted) {
                // 표시 기체의 회복 실행은 일지·판단 기록·표시 연결을 한 트랜잭션에 적는다 — 승인된 조치 열이 복원 기록에서 빠지지 않게.
                val execution = middleware.executions().first { it.executionId == outcome.executionId }
                journalRecovery(execution, markers, attempt.approver.id)
                trialProposals.remove(attempt.robotId to attempt.jobOrderId)
            } else if (!existed) {
                val execution = middleware.executions().first { it.executionId == outcome.executionId }
                journal(execution)
                persistRecord(execution.executionId)
            } else {
                recordReruns()?.let { throw JournalWriteFailed(outcome.executionId, it) }
            }
        }
        outcome
    }

    /**
     * 출발 관문을 지날 수 있는 기체: 정착하지 않은 실행에서 아직 내지 않았거나(`PENDING`), 미확정이라 다시 조회하거나(`IN_DOUBT`),
     * 운영자 보류에서 풀리면 나갈(`OPERATOR_HOLD`) 로봇 단위의 기체. [HostRobotPort] 는 이 기체를 미리 읽은 값 대신 지금 읽는다.
     */
    private fun startingRobots(): Set<String> =
        middleware.executions().asSequence()
            .filter { !it.physicalState.isSettled }
            .flatMap { e -> e.units.asSequence().filter { it.route == Route.ROBOT && it.state in STARTING_UNIT_STATES }.map { e.robotOf(it) } }
            .toSet()

    /** 기체마다 호스트 판정을 낸다. [order] 의 WorkMaster 는 부르는 쪽이 [WORK_MASTERS] 로 거른다. */
    private fun selectedMission(workMaster:String,version:String?):dev.picasso.middleware.ActiveMission? {
        if(version==null){if(catalog.active(workMaster)==null)throw BadRequest(HostRequests.UNKNOWN_WORK_MASTER,"활성화하지 않은 임무입니다: $workMaster");return null}
        if(version=="CODE"){if(workMaster !in WORK_MASTERS)throw BadRequest(HostRequests.BAD_REQUEST,"새 임무는 숫자 버전이 필요합니다");return catalog.mission(workMaster,null)}
        val number=version.toIntOrNull()?.takeIf{it>0 && it.toString()==version}
            ?: throw BadRequest(HostRequests.BAD_REQUEST,"고정 임무 버전이 잘못되었습니다")
        val row=store.version(workMaster,number)
            ?: throw BadRequest(HostRequests.BAD_REQUEST,"고정 임무 버전을 찾을 수 없습니다: $workMaster/v$number")
        return catalog.mission(workMaster,row)
    }
    fun eligibility(order:JobOrder,robotIds:List<String>,missionVersion:String?=null):List<HostEligibility> = lock.withLock {
        catalog.withMission(order.workMasterId,selectedMission(order.workMasterId,missionVersion)) { eligibilityCurrent(order,robotIds) }
    }
    private fun eligibilityCurrent(order: JobOrder, robotIds: List<String>): List<HostEligibility> = lock.withLock {
        val applied = siteTimings.current() != null
        robotIds.distinct().map { judge(order, it, applied, trialable = true) }
    }

    /**
     * 판정을 다시 해 통과한 기체만 `assign` 에 넘긴다. 통과한 기체가 없어도 `assign` 을 부른다. 그때 결과는 사유 없는
     * UNASSIGNED 이고 이유는 [SubmitOutcome.excluded] 에 있다.
     *
     * 미들웨어에 그 작업 지시의 실행이 이미 있으면 후보 목록을 판정하지 않는다. picasso 는 그 작업 지시를 후보와 무관하게 그 실행의
     * 기체로 보낸다(같은 버전이면 IDEMPOTENT, 낮으면 거부, 높으면 개정). picasso 가 실제로 개정할 제출([revises])이면 그 기체 하나를
     * 판정하고, 그 실행 자신은 그 기체를 쥐어도 도는 실행으로 세지 않는다. 통과하면 그 기체만 `assign` 에 넘기고 [SubmitOutcome.excluded]
     * 는 비어 있다. 빠지면(잔여 파지 표시, 다른 실행의 점유, 현장 시간값 미적용 등) `assign` 을 부르지 않고 사유 없는 UNASSIGNED 이며
     * [SubmitOutcome.excluded] 에 그 기체의 판정 하나가 있다. 개정이 아닌 제출(같은 판, 낮은 판, 종료한 실행, WorkMaster 바꿈, 근거 등급
     * 초과)은 판정 없이 그 기체로 `assign` 에 넘겨 picasso 의 IDEMPOTENT·거부를 그대로 낸다. 운영 서비스는 작업 지시 id 를 다시 쓰지 않는다.
     *
     * 미적용 판단은 한 번만 읽어 모든 기체의 판정에 같은 값을 쓴다. 미적용이면 통과한 기체가 없어 UNASSIGNED 다. 미들웨어의 채택은
     * 기체마다 게이트를 걸므로 기체가 없으면 요구 근거 등급 검사도 하지 않는다.
     *
     * 새 실행으로 ACCEPTED 이면 같은 잠금 안에서 응답하기 전에 실행 일지를 적는다(S4b 스펙 T2). 이미 있던 실행의 `revise` 가 낸
     * Accepted 는 받은 개정판(그 실행이 정착을 적은 뒤면 다시 돎도)과 판단 기록을 적는다([recordReruns]). IDEMPOTENT 는 적지 않는다.
     * pump 도 이 잠금을 잡으므로 일지에 없는 실행은 로봇 명령을 낸 적이 없다. 일지 쓰기가 실패하면 [JournalWriteFailed] 이고 실행은
     * 메모리에 남지만 다음 pump와 신규 제출을 차단한다. 개정판 쓰기의 실패는 pump 가 다시 적으면 풀린다.
     *
     * [confirmations] 는 사람이 기록한 출발 자리 확인이다. 이 `assign` 한 번 동안만 셀 포트의 관측이 되고([CellBandSignals.observe])
     * 끝나면 비운다. 호스트는 저장하지 않는다 — 다음 제출이나 재배정은 확인을 다시 싣지 않으면 설비의 답만 본다.
     *
     * 미들웨어에 그 작업 지시의 실행이 없는데 실행 일지에 그 작업 지시가 있으면 `assign` 을 부르지 않고 REJECTED 다([journalRefusal]).
     * 일지의 키가 작업 지시 id 라 새 실행을 받으면 일지 쓰기가 실패해 호스트 전체가 막히고, 정착한 작업 지시면 이미 끝난 일이 또 돈다.
     * picasso 의 놓은 작업 지시 표시는 메모리라 재기동 뒤에는 없으므로 이 확인이 재기동 전후를 같게 한다.
     *
     * 철회된 작업 지시([withdraw])도 `assign` 을 부르지 않고 REJECTED 다(사유가 [WITHDRAWN_JOB_ORDER] 로 시작한다). 운영 서비스의 재제출(미배정 재평가,
     * 출발 자리 확인)이 관문 확인으로 제안을 다시 세우지 않게 한다. 철회 행은 DB 에 있어 재기동 뒤에도 같다.
     */
    fun submit(order:JobOrder,candidates:List<String>,missionVersion:String?=null,confirmations:List<SourceConfirmation> = emptyList()):SubmitOutcome = lock.withLock {
        catalog.withMission(order.workMasterId,selectedMission(order.workMasterId,missionVersion)) { submitCurrent(order,candidates,confirmations) }
    }
    private fun submitCurrent(order: JobOrder, candidates: List<String>, confirmations: List<SourceConfirmation>): SubmitOutcome = lock.withLock {
        if (journalBlocked().isNotEmpty()) return@withLock SubmitOutcome(SubmitResult.REJECTED, null, null, "실행 일지 저장 실패: 호스트 복구 전 제출 차단", emptyList(), emptyList())
        val applied = siteTimings.current() != null
        // picasso 메모리의 실행은 작업 지시마다 많아야 하나다(`submit`·`adopt`·`resume` 이 같은 작업 지시 id 를 개정으로 보낸다).
        val existing = middleware.executions().firstOrNull { it.order.jobOrderId == order.jobOrderId }
        val existed = existing != null
        // 이미 실행이 있는 작업 지시는 후보를 판정하지 않는다. picasso PR #106 부터 `adopt` 는 그런 작업 지시의 후보 목록을 읽지 않고 곧바로
        // 개정 경로로 보내 그 실행의 기체에서 개정한다. 후보로 판정하면 통과한 다른 후보가 호스트가 뺀 그 기체(잔여 파지 표시, 다른 실행의
        // 점유, 현장 시간값 미적용 등)로 개정을 들이고, 그 실행 자신의 점유가 정상 개정을 막는다. 그래서 picasso 가 실제로 개정할 제출
        // ([revises])만 그 기체 하나로 판정하고(그 실행 자신은 도는 실행으로 세지 않는다) 빠지면 `assign` 을 부르지 않는다. 개정이 아닌 제출은
        // 판정 없이 그 기체로 넘겨 picasso 의 IDEMPOTENT·거부를 그대로 낸다.
        val revising = existing?.takeIf { revises(it, order) }
        val judged = when {
            existing == null -> candidates.distinct().map { judge(order, it, applied, trialable = true) }
            revising != null -> listOf(judge(order, revising.robotId, applied, revising = revising.executionId))
            else -> emptyList()
        }
        val excluded = judged.filter { !it.passed }
        if (!existed) {
            withdrawalRefusal(order.jobOrderId)?.let { reason ->
                return@withLock SubmitOutcome(SubmitResult.REJECTED, null, null, reason, emptyList(), excluded)
            }
            journalRefusal(order.jobOrderId)?.let { reason ->
                return@withLock SubmitOutcome(
                    SubmitResult.REJECTED, null, null, reason, emptyList(), excluded,
                    forgottenJobOrder = reason.startsWith(Middleware.FORGOTTEN_JOB_ORDER),
                )
            }
        }
        val passed = if (existing != null && revising == null) listOf(existing.robotId) else judged.filter { it.passed }.map { it.robotId }
        // 개정할 기존 실행의 기체가 빠졌다. 개정은 그 기체에서만 일어나므로 `assign` 을 부르지 않는다.
        if (existed && passed.isEmpty()) return@withLock SubmitOutcome(SubmitResult.UNASSIGNED, null, null, null, emptyList(), excluded)
        val searched = middleware.remedySearches().size
        // 관문 확인용 기체(새 접수만, [HostEligibility.gateTrial]). picasso 가 통과시켜도 채택하지 않는다 — 판정과 기록이 한 호출이라 틈이 없다(§15.317).
        val trial = judged.filter { it.gateTrial }.associate { e -> e.robotId to openHolds.filter { it.robotId == e.robotId }.map { it.markerId } }
        signals.confirmations = confirmations.associateBy { it.source }
        val assigned = try {
            middleware.assign(order, passed, trialOnly = trial.keys)
        } finally {
            signals.confirmations = emptyMap()
        }
        when (val submission = assigned) {
            is Middleware.Submission.Accepted -> {
                if (!existed) {
                    journal(submission.execution)
                    persistRecord(submission.execution.executionId)
                } else {
                    recordReruns()?.let { throw JournalWriteFailed(submission.execution.executionId, it) }
                }
                SubmitOutcome(
                    SubmitResult.ACCEPTED, submission.execution.executionId, submission.execution.robotId, null, emptyList(), excluded,
                )
            }
            is Middleware.Submission.Idempotent -> SubmitOutcome(
                SubmitResult.IDEMPOTENT, submission.execution.executionId, submission.execution.robotId, null, emptyList(), excluded,
            )
            // 같은 잠금 안이라 이번 `assign` 뒤에 조치 탐색 기록에 붙은 줄은 이 제출의 것이며, 거절된 작업 수락(`submit` 또는 리비전)은 많아야
            // 한 줄을 남긴다. picasso PR #109부터 UNASSIGNED로 끝난 `assign`은 후보당 한 줄씩 덧붙일 수 있지만, UNASSIGNED 분기는 조치 탐색 기록을 읽지 않는다.
            is Middleware.Submission.Rejected -> SubmitOutcome.rejected(
                submission,
                middleware.remedySearches().drop(searched).lastOrNull { it.jobOrderId == order.jobOrderId }?.outcome,
                excluded,
            )
            is Unassigned -> unassigned(order, submission, judged, excluded, trial, middleware.remedySearches().drop(searched))
        }
    }

    /**
     * UNASSIGNED 를 옮긴다. 관문 확인용 기체([trial], 기체에서 그때 열린 표시 id)는 **사슬 위반으로 거절됐을 때만** 거절과 조치로 싣는다. 사슬 위반은
     * 이번 제출이 조치 탐색 기록에 그 (기체, 작업 지시)의 줄을 남겼거나(찾음·못 찾음) 제안이 보류된 것이다. 열쇠로 서 있는 제안을 읽지 않는다 —
     * 앞선 시도의 제안이 이번 답으로 보인다. 관문이 통과시켰거나(`trialPassed`) 다른 사유(자리·구역·결품)로 거절한 확인용 기체는 표시 사유로 뺀
     * 판정을 [SubmitOutcome.excluded] 에 둔다 — 표시가 막았다는 것이 그 기체의 답이다.
     */
    private fun unassigned(
        order: JobOrder,
        submission: Unassigned,
        judged: List<HostEligibility>,
        excluded: List<HostEligibility>,
        trial: Map<String, List<Long>>,
        noted: List<RemedySearchRecord>,
    ): SubmitOutcome {
        val refusals = mutableListOf<Refusal>()
        val held = mutableListOf<HostEligibility>()
        submission.refusals.forEach { (robotId, reason) ->
            val base = Refusal.of(robotId, reason, submission.sourceMissing[robotId], submission.sourceUnobserved[robotId])
            val line = noted.lastOrNull { it.robotId == robotId && it.jobOrderId == order.jobOrderId }?.outcome
            val remedy = when (line) {
                is RemedyOutcome.Found -> RemedyView.Found(line.steps.map(RemedyStepView::of))
                is RemedyOutcome.None -> RemedyView.None(line.cause.name, line.unmet.map(UnmetView::of))
                else -> null
            }
            val withheld = line == RemedyOutcome.Withheld
            val markers = trial[robotId]
            when {
                markers == null -> refusals += base.copy(remedy = remedy, remedyWithheld = withheld)
                remedy != null || withheld -> {
                    refusals += base.copy(remedy = remedy, remedyWithheld = withheld, residualHoldMarkers = markers)
                    // 승인할 수 있는 제안(찾음·보류)만 표시 예외의 근거다. 못 찾음은 제안이 없다.
                    if (remedy is RemedyView.Found || withheld) trialProposals[robotId to order.jobOrderId] = markers.toSet()
                    else trialProposals.remove(robotId to order.jobOrderId)
                }
                else -> {
                    held += heldBack(judged, robotId)
                    trialProposals.remove(robotId to order.jobOrderId)
                }
            }
        }
        submission.trialPassed.forEach { held += heldBack(judged, it) }
        (trial.keys - submission.refusals.keys).forEach { trialProposals.remove(it to order.jobOrderId) }
        return SubmitOutcome(SubmitResult.UNASSIGNED, null, null, null, refusals, excluded + held)
    }

    /** 관문 확인용으로 넘겼으나 표시가 막은 기체의 판정 — 통과 표시를 거둔다. 사유는 판정이 이미 든 표시 사유다. */
    private fun heldBack(judged: List<HostEligibility>, robotId: String): HostEligibility =
        judged.first { it.robotId == robotId }.copy(passed = false, gateTrial = false)

    /**
     * picasso 가 [order] 로 [execution] 을 실제로 개정하는가. picasso `Middleware.revise` 가 개정 전에 돌려보내는 조건을 그 순서대로 옮긴
     * 것이다: WorkMaster 를 바꾸는 리비전 거부(«리비전이 WorkMaster 를 바꾼다»), `evidenceRefusal`(요구 근거 등급이 실행이 쥔
     * 케이퍼빌리티의 `maxEvidence` 를 넘음), 같은 판 `Submission.Idempotent`, 낮은 판 거부(«이미 지난 버전이다»), 종료 거부
     * (`PhysicalState.ABORTED`·`PHYSICALLY_DONE`, «종료한 실행에는 새 버전이 붙지 않는다»). 이 거부들은 실행을 바꾸지 않고 로봇 명령도
     * 내지 않는다. 그 뒤의 단위 id 중복(`duplicateUnitIdRefusal`)과 사슬 검사(`gate.chainRefusal`)는 계획을 지어야 알 수 있어 여기서는
     * 개정으로 센다 — 그 기체가 빠졌으면 그 거부 대신 UNASSIGNED 다.
     */
    private fun revises(execution: Middleware.Execution, order: JobOrder): Boolean =
        order.workMasterId == execution.order.workMasterId &&
            order.requiredEvidence <= execution.capability.maxEvidence &&
            order.version > execution.order.version &&
            execution.physicalState != PhysicalState.ABORTED && execution.physicalState != PhysicalState.PHYSICALLY_DONE

    /**
     * 미들웨어에 실행이 없는 작업 지시가 실행 일지에 이미 있으면 거부 사유, 없으면 `null`. 마지막으로 정착을 적은 작업 지시(이 인스턴스가
     * 놓았거나 재기동 전에 정착한 것)는 picasso 의 [Middleware.FORGOTTEN_JOB_ORDER] 로 시작해 picasso 의 거부와 같은 뜻으로 읽힌다. 그 밖의
     * 일지 행(미뤘거나 포기한 것, 정착 뒤 다시 돌아 미뤘거나 포기한 것)은 [JOURNALED_JOB_ORDER] 로 시작한다.
     *
     * 운영자가 종결한 작업 지시(결함 14, 마지막이 CLOSED)도 [Middleware.FORGOTTEN_JOB_ORDER] 로 시작한다 — 끝난 작업 지시라 같은 id 를 다시 받지
     * 않는다([SubmitOutcome.forgottenJobOrder]). 다시 할 일은 새 작업 지시 id 로 낸다.
     */
    private fun journalRefusal(jobOrderId: String): String? {
        val row = records.journalRow(jobOrderId) ?: return null
        // 정착을 적은 뒤 다시 돌았으면(REOPENED) 끝난 작업 지시가 아니다. 마지막 정착·다시 돎·포기로 가른다.
        // 마지막이 운영자 종결(CLOSED)이면 끝난 작업 지시다.
        val last = records.events(jobOrderId).lastOrNull { it.kind in TURNS }
        if (last?.kind == JournalEventKind.CLOSED) {
            val close = checkNotNull(records.closeOf(jobOrderId)) { "종결 이벤트에 종결 행이 없다: $jobOrderId" }
            return "${Middleware.FORGOTTEN_JOB_ORDER}: $jobOrderId · ${close.closedInstanceId} ${close.closedExecutionId} · UNVERIFIED " +
                "(운영자 종결, 실행 일지). 다시 할 일은 새 작업 지시 ID로 낸다"
        }
        val settled = last?.takeIf { it.kind == JournalEventKind.SETTLED }
            ?: return "$JOURNALED_JOB_ORDER: $jobOrderId · ${row.instanceId} ${row.executionId}"
        return "${Middleware.FORGOTTEN_JOB_ORDER}: $jobOrderId · ${settled.instanceId} ${settled.executionId} · ${settled.detail} " +
            "(실행 일지). 다시 할 일은 새 작업 지시 ID로 낸다"
    }

    /** 철회된 작업 지시면 거부 사유([WITHDRAWN_JOB_ORDER] 로 시작), 아니면 `null`. 호스트 잠금 아래에서 부른다. */
    private fun withdrawalRefusal(jobOrderId: String): String? = records.withdrawal(jobOrderId)?.let {
        "$WITHDRAWN_JOB_ORDER: $jobOrderId · ${it.requestedBy} ${it.withdrawnAt} (${it.reason})"
    }

    /**
     * 작업 지시 철회(B3). 호스트 잠금 아래에서 판정한다 — 값 없는 승인([attemptApproval])과 새 접수([submit])도 같은 잠금이라 둘 가운데 먼저 잡은 쪽이
     * 이긴다. 순서:
     * 1. 실행 일지가 막혀 있으면([journalBlocked]) [WithdrawJournalBlocked] — 일지에 없는 실행이 미들웨어에 있을 수 있어 어느 답도 주지 않는다.
     * 2. 그 작업 지시의 실행이 미들웨어에 있으면 [WithdrawResult.EXECUTION_EXISTS](지금 인스턴스의 실행과 물리 상태). 적지 않는다.
     * 3. 실행 일지에 그 작업 지시의 행이 있으면 [WithdrawResult.EXECUTION_EXISTS](일지의 마지막 실행과 마지막 정착 상태). 적지 않는다.
     * 4. 철회 행이 이미 있으면 [WithdrawResult.WITHDRAWN] 과 그 행. 요청 id 가 달라도 같다(작업 지시 기준 멱등).
     * 5. 그 요청 id 를 다른 작업 지시의 철회에 썼으면 [WithdrawRequestReused].
     * 6. 철회 행을 적고 [WithdrawResult.WITHDRAWN]. 적지 못하면 `DataAccessException` 이고 아무것도 바뀌지 않는다.
     *
     * WITHDRAWN 이면 그 작업 지시의 관문 확인 제안([trialProposals])을 지운다. picasso 의 제안 장부는 지우지 않는다 — 승인과 접수를 이 호스트가 막으므로
     * 그 제안으로는 실행이 서지 않는다. 풀어 주는 호출은 없다(영구).
     */
    fun withdraw(jobOrderId: String, requestId: UUID, reason: String, requestedBy: String): WithdrawView = lock.withLock {
        fun executed(instance: String, executionId: String, robotId: String, state: String?) =
            WithdrawView(WithdrawResult.EXECUTION_EXISTS, jobOrderId, instance, executionId, robotId, state, null, requestId.toString())
        journalBlocked().takeIf { it.isNotEmpty() }?.let { throw WithdrawJournalBlocked(it) }
        middleware.executions().firstOrNull { it.order.jobOrderId == jobOrderId }?.let {
            return@withLock executed(instanceId, it.executionId, it.robotId, it.physicalState.name)
        }
        records.journalRow(jobOrderId)?.let { row ->
            val events = records.events(jobOrderId)
            // 받은 행 뒤에 다시 짓거나 정착한 실행이 있으면 그 실행이 마지막이다. 물리 상태는 마지막 차례가 정착(또는 다시 돎)일 때의 것이다.
            val ran = events.lastOrNull { it.executionId != null }
            val last = events.lastOrNull { it.kind in TURNS }
            val state = when (last?.kind) {
                JournalEventKind.SETTLED, JournalEventKind.REOPENED -> last.detail
                JournalEventKind.CLOSED -> PhysicalState.UNVERIFIED.name
                else -> null
            }
            return@withLock executed(ran?.instanceId ?: row.instanceId, ran?.executionId ?: row.executionId, row.robotId, state)
        }
        val row = records.withdrawal(jobOrderId) ?: run {
            records.withdrawalByRequest(requestId)?.let { throw WithdrawRequestReused(requestId.toString(), it.jobOrderId) }
            records.withdraw(jobOrderId, requestId, requestedBy, reason)
        }
        trialProposals.keys.removeIf { it.second == jobOrderId }
        WithdrawView(WithdrawResult.WITHDRAWN, jobOrderId, null, null, null, null, WithdrawalRecordView.of(row), requestId.toString())
    }

    /** 새 실행의 일지 행을 적는다. 작업 지시는 picasso `JobOrder` 의 칸 그대로의 JSON 이다. */
    private fun journal(execution: Middleware.Execution) {
        val order = execution.order
        try {
            records.journal(
                order.jobOrderId, execution.robotId, orderJson(order), order.workMasterId, execution.missionVersion,
                middleware.instanceId, execution.executionId,
            )
        } catch (e: Exception) {
            throw JournalWriteFailed(execution.executionId, e)
        }
        journaled[order.jobOrderId] = order.version
    }

    /**
     * 표시 기체의 회복 실행(값 없는 승인의 표시 예외, [attemptApproval])을 적는다. 실행 일지, 판단 기록, 표시와 실행의 연결을 **한 트랜잭션**에 둔다.
     * 판단 기록을 따로 적으면 그 실패가 경고로 끝나 다음 pump 전에 죽을 때 승인된 조치 열이 복원 기록에서 빠진다. 실패는 [JournalWriteFailed] 다.
     */
    private fun journalRecovery(execution: Middleware.Execution, markers: List<Long>, approverId: String) {
        val order = execution.order
        val text = changedRecords().firstOrNull { it.first.executionId == execution.executionId }?.second
        try {
            records.inTransaction {
                records.journal(
                    order.jobOrderId, execution.robotId, orderJson(order), order.workMasterId, execution.missionVersion,
                    middleware.instanceId, execution.executionId,
                )
                text?.let { writeRecord(execution, it) }
                markers.forEach { records.residualHoldRecovery(it, execution.executionId, order.jobOrderId, approverId) }
            }
        } catch (e: Exception) {
            throw JournalWriteFailed(execution.executionId, e)
        }
        journaled[order.jobOrderId] = order.version
        text?.let { recorded[order.jobOrderId] = it }
    }

    private fun orderJson(order: JobOrder): String = json.writeValueAsString(
        linkedMapOf(
            "jobOrderId" to order.jobOrderId,
            "workMasterId" to order.workMasterId,
            "version" to order.version,
            "requiredEvidence" to order.requiredEvidence.name,
            "parameters" to order.parameters,
            "materialRequirements" to order.materialRequirements.map {
                linkedMapOf("materialDefinitionId" to it.materialDefinitionId, "quantity" to it.quantity)
            },
            "equipmentRequirements" to order.equipmentRequirements.map {
                linkedMapOf("id" to it.id, "equipmentUse" to it.equipmentUse, "properties" to it.properties)
            },
        ),
    )

    /**
     * 이 인스턴스의 실행 전부(생성 순). 미들웨어 메모리에 있는 실행은 메모리에서, 이 인스턴스가 놓은 실행은 그 실행의 가장 최근 정착 이벤트
     * 본문에서 싣는다([withForgotten]). 놓기 전의 목록과 같은 줄이다.
     */
    fun executions(): ExecutionsView = withForgotten(lock.withLock { view(middleware.executions()) }, null, null)

    /**
     * [executions] 를 작업 지시 id 가 [jobOrderIds] 에 들거나 실행 id 가 [executionIds] 에 드는 실행으로 좁힌 것(HA 설계 기록 §10).
     * 모양과 칸은 같고 실행과 일지 막힘 목록만 고른 실행으로 줄어든다. 같은 순간의 전체 목록에서 그 실행만 남긴 것과 같다.
     * 응답은 고른 실행 수만큼이다. 놓은 실행은 고른 것만 정착 이벤트에서 읽는다.
     */
    fun executions(jobOrderIds: Set<String>, executionIds: Set<String>): ExecutionsView = withForgotten(
        lock.withLock { view(middleware.executions().filter { it.order.jobOrderId in jobOrderIds || it.executionId in executionIds }) },
        jobOrderIds, executionIds,
    )

    /**
     * [live] 에 이 인스턴스가 놓은 실행을 더한다. [live] 를 호스트 잠금 아래에서 먼저 읽고 DB 를 잠금 밖에서 뒤에 읽는다. 놓기는 정착
     * 기록이 커밋된 뒤라, 메모리에서 빠진 실행은 DB 에 있다. 두 쪽에 다 있으면 메모리의 것이다. 놓은 실행의 줄은 정착할 때 적은 본문
     * 그대로다(놓은 뒤에는 그 실행이 바뀌지 않는다). 일지 막힘은 메모리의 실행만이다(놓은 실행은 일지에 있다).
     */
    private fun withForgotten(live: ExecutionsView, jobOrderIds: Set<String>?, executionIds: Set<String>?): ExecutionsView {
        val present = live.executions.mapTo(HashSet()) { it.executionId }
        val gone = records.settledSnapshots(instanceId, jobOrderIds, executionIds)
            .filter { it.executionId !in present }
            .map { json.readValue<ExecutionView>(it.snapshot) }
        if (gone.isEmpty()) return live
        return live.copy(executions = (live.executions + gone).sortedBy { creationOrder(it.executionId) })
    }

    /**
     * 화면의 실행 목록(HA 설계 기록 §10). 지금 인스턴스의 남은 실행([ExecutionPages.live])을 메모리에서, 최근 정착 이력 [recent] 개를
     * 정착 기록(DB)에서 읽는다. 정착한 실행이 미들웨어 메모리에서 사라져도 이력은 그대로다.
     *
     * 메모리를 먼저(호스트 잠금 아래) 읽고 DB 를 잠금 밖에서 뒤에 읽는다. 남은 실행이 아닌 실행은 정착 기록이 커밋된 뒤라 DB 에 있으므로 어느
     * 쪽에서도 빠지지 않는다. 그 사이 정착을 적은 실행은 두 쪽에 다 나올 수 있다(화면이 실행 id 와 인스턴스로 하나만 보인다).
     */
    fun executionsRecent(recent: Int): ExecutionPageView {
        require(recent in 1..ExecutionPages.MAX) { "recent 는 1~${ExecutionPages.MAX} 다: $recent" }
        val (live, reworkable, unrestored) = lock.withLock {
            val latest = latestResponses(middleware.responses())
            Triple(view(middleware.executions().filter { live(it, latest) }), reworkableTargets(latest), unrestoredRows())
        }
        val page = settledPage(null, recent)
        return ExecutionPageView(
            instanceId = live.instanceId,
            pumpedAt = live.pumpedAt,
            executions = live.executions,
            restore = live.restore,
            journalBlockedExecutionIds = live.journalBlockedExecutionIds,
            reworkableTargets = reworkable,
            settled = page.settled,
            nextBefore = page.nextBefore,
            olderTotal = page.olderTotal,
            unrestored = unrestored,
        )
    }

    /** 정착 차례가 [before] 보다 앞인 정착 이력 가운데 최근부터 [limit] 개(HA 설계 기록 §10). DB 만 읽고 호스트 잠금을 잡지 않는다. */
    fun executionsBefore(before: Long, limit: Int): SettledPageView {
        require(limit in 1..ExecutionPages.MAX) { "limit 은 1~${ExecutionPages.MAX} 다: $limit" }
        return settledPage(before, limit)
    }

    /** 남은 실행의 수만(HA 설계 기록 §10). 실행 본문과 작업 응답을 옮기지 않는다. */
    fun executionSummary(): ExecutionSummaryView = lock.withLock {
        val latest = latestResponses(middleware.responses())
        val live = middleware.executions().filter { live(it, latest) }
        val states = PhysicalState.entries.associateTo(linkedMapOf()) { it.name to 0 }
        live.forEach { states[it.physicalState.name] = states.getValue(it.physicalState.name) + 1 }
        ExecutionSummaryView(
            instanceId = middleware.instanceId,
            pumpedAt = pumpedAt,
            live = live.size,
            states = states,
            journalBlockedExecutionIds = journalBlocked(),
        )
    }

    /**
     * 재작업을 받을 수 있는 건너뛴 대상(picasso `Middleware.resolve` 의 건너뛴 대상 경로). 미들웨어 메모리의 실행 가운데 물리 상태가
     * `PARTIAL`·`FAILED` 이고 열린 단위(낼 것, 도는 것, 미확정, 보류)가 없으며, 단위가 `FAILED` 이고 분류가
     * [Middleware.PREDECESSOR_NOT_DONE] 이며 태스크를 쓴 적이 없는 것이다. 선행이 시도된 실패인지와 같은 기체의 다른 활성 실행은
     * 호스트가 못 보거나(`ExecutionUnit.after` 는 picasso 안쪽) picasso 와 규칙이 두 벌이 되므로 picasso 의 `NotHeld`·`Refused` 에 맡긴다.
     *
     * `FAILED` 는 지금의 종결 기록을 적은 실행만 싣고([settleRecorded]) 보존 만료 시각을 그 기록 시각에서 정한다. 기록을 적기 전이면
     * 만료 시각을 모르므로 싣지 않는다. 한 pump 안에 지난다. `PARTIAL` 은 만료가 없다. [latest] 는 [latestResponses] 다. 호스트 잠금
     * 아래에서 부른다.
     */
    private fun reworkableTargets(latest: Map<String, JobResponse>): List<ReworkableTargetView> =
        middleware.executions().flatMap { execution ->
            val state = execution.physicalState
            if (state != PhysicalState.PARTIAL && state != PhysicalState.FAILED) return@flatMap emptyList()
            if (execution.units.any { it.state in OPEN_UNIT_STATES }) return@flatMap emptyList()
            val retainedUntil = when {
                state == PhysicalState.PARTIAL -> null
                settleRecorded(execution, latest) -> settleMarks.getValue(execution.executionId).at.plus(revisableRetention)
                else -> return@flatMap emptyList()
            }
            execution.units
                .filter { it.state == UnitState.FAILED && it.failureClass == Middleware.PREDECESSOR_NOT_DONE && it.taskId.isEmpty() }
                .map { ReworkableTargetView(execution.executionId, execution.order.jobOrderId, execution.robotId, it.unitId, state.name, retainedUntil) }
        }

    /** [ExecutionPages.live]. [latest] 는 [latestResponses] 다. 호스트 잠금 아래에서 부른다. */
    private fun live(execution: Middleware.Execution, latest: Map<String, JobResponse>): Boolean =
        ExecutionPages.live(active(execution), settleRecorded(execution, latest))

    /** [ExecutionPages.active]. 호스트 잠금 아래에서 부른다. */
    private fun active(execution: Middleware.Execution): Boolean =
        ExecutionPages.active(execution.physicalState, execution.units.map { it.state }, execution.order.jobOrderId !in journaled)

    /**
     * 이 실행의 지금의 정착을 적었는가. 실행이 정착해 있고(`PARTIAL` 은 아니다) 마지막으로 적은 정착의 작업 응답이 그 실행의 마지막 작업
     * 응답이다. 다시 돌면 정착하지 않은 상태라 거짓이고, 다시 정착하면 새 작업 응답이 나 또 거짓이다. [latest] 는 [latestResponses] 다.
     */
    private fun settleRecorded(execution: Middleware.Execution, latest: Map<String, JobResponse>): Boolean {
        if (execution.physicalState.pumps) return false
        val mark = settleMarks[execution.executionId] ?: return false
        return mark.responseId == latest[execution.executionId]?.jobResponseId
    }

    private fun settledPage(before: Long?, limit: Int): SettledPageView =
        records.settledHistory(before, limit).let { SettledPageView(it.rows.map(::settledView), it.nextBefore, it.olderTotal) }

    /** 정착 이력 한 줄. 본문을 적지 않은 행(V8 전)은 일지 행으로 짓는다. */
    private fun settledView(row: SettledRow): SettledExecutionView = SettledExecutionView(
        settleSeq = row.eventId,
        instanceId = row.instanceId,
        settledAt = row.settledAt,
        snapshot = row.snapshot != null,
        execution = row.snapshot?.let(json::readTree) ?: json.valueToTree(
            ExecutionView(
                row.executionId, row.jobOrderId, row.workMasterId, row.missionVersion, row.robotId, row.physicalState,
                units = emptyList(), jobResponse = null, restoredFrom = null,
            ),
        ),
        superseded = row.superseded,
    )

    /** [chosen] 실행의 목록 본문. 호스트 잠금 아래에서 부른다. */
    private fun view(chosen: List<Middleware.Execution>): ExecutionsView {
        val responses = latestResponses(middleware.responses())
        val released = records.releases(chosen.map { it.order.jobOrderId }).groupBy { it.jobOrderId }
        return ExecutionsView(
            instanceId = middleware.instanceId,
            pumpedAt = pumpedAt,
            executions = chosen.map { execution ->
                ExecutionView(
                    executionId = execution.executionId,
                    jobOrderId = execution.order.jobOrderId,
                    workMasterId = execution.order.workMasterId,
                    missionVersion = execution.missionVersion,
                    robotId = execution.robotId,
                    physicalState = execution.physicalState.name,
                    units = execution.units.map { UnitView(it.unitId, it.skillType, it.state.name, it.reached.name) },
                    jobResponse = responses[execution.executionId]?.let(::view),
                    restoredFrom = restoredFrom[execution.executionId],
                    cancel = cancels[execution.executionId]?.let(CancelRecordView::of),
                    permitBlock = execution.permitBlock?.let(PermitBlockView::of),
                    blockedFaults = execution.blockedBy.map(FaultDetailView::fromContract),
                    releases = released[execution.order.jobOrderId].orEmpty().map(::releaseView),
                )
            },
            restore = restoreView(),
            journalBlockedExecutionIds = journalBlocked(chosen),
        )
    }

    private fun releaseView(row: FaultReleaseRow) =
        FaultReleaseView(row.requestId?.toString(), row.approverId, row.reason, json.readValue(row.faults), row.recordedAt)

    /**
     * 봉인된 인시던트(S3c 스펙 §7.2, S4a 스펙 §6). 최신부터 많아야 [limit] 개다. 번들은 미들웨어 안의 값이라 호스트 잠금 아래에서
     * 옮긴다. 보류 중 여부는 자르기 전의 전부로 정한다.
     *
     * 이전 인스턴스의 사본은 [IncidentsView.earlier] 에 따로 싣는다(S4b 스펙 T7). 적은 순서의 역순으로 많아야 [limit] 개다. 사본은
     * 잠금 밖에서 DB 로 읽는다.
     */
    fun incidents(limit: Int): IncidentsView {
        val (total, live) = lock.withLock {
            val all = middleware.incidents()
            val held = heldIncidents(all)
            all.size to all.asReversed().take(limit).map { IncidentViews.item(it, it.incidentId in held) }
        }
        return IncidentsView(
            instanceId = instanceId,
            total = total,
            incidents = live,
            earlierTotal = records.earlierCopyCount(instanceId),
            earlier = records.earlierCopies(instanceId, limit).map { row ->
                EarlierIncidentView(row.instanceId, IncidentViews.item(copyDetail(row)))
            },
        )
    }

    /**
     * 인시던트 하나의 상세(S4a 스펙 §6). 없으면 `null` 이다. [ofInstance] 가 없거나 지금 인스턴스면 호스트 잠금 아래에서 미들웨어의
     * 번들을 읽는다. 이전 인스턴스면 그 인스턴스의 사본을 돌려준다(S4b 스펙 T7).
     */
    fun incident(incidentId: String, ofInstance: String? = null): IncidentDetailView? {
        if (ofInstance != null && ofInstance != instanceId) return records.incidentCopy(ofInstance, incidentId)?.let(::copyDetail)
        return lock.withLock { liveDetail(incidentId) }
    }

    private fun liveDetail(incidentId: String): IncidentDetailView? {
        val bundle = middleware.incident(incidentId) ?: return null
        val unitState = unitStateOf(bundle) ?: forgottenUnitState(bundle)
        return IncidentViews.detail(middleware.instanceId, bundle, incidentId in heldIncidents(middleware.incidents()), unitState)
    }

    /** 이 인스턴스가 놓은 실행의 인시던트면 그 단위의 마지막 정착 때 상태(놓은 뒤에는 바뀌지 않는다). 없으면 `null` 이다. */
    private fun forgottenUnitState(bundle: IncidentBundle): String? =
        records.settledSnapshots(instanceId, executionIds = setOf(bundle.executionId)).singleOrNull()
            ?.let { json.readValue<ExecutionView>(it.snapshot) }
            ?.units?.firstOrNull { it.unitId == bundle.unitId }?.state

    private fun unitStateOf(bundle: IncidentBundle): String? =
        middleware.executions().firstOrNull { it.executionId == bundle.executionId }
            ?.units?.firstOrNull { it.unitId == bundle.unitId }?.state?.name

    /**
     * 사본의 상세. 판단은 판단 행으로 다시 세우고 근거 없는 완료 확인도 그것으로 다시 계산한다. 이전 인스턴스의 인시던트는 보류 중이
     * 아니고, 그 실행은 이 인스턴스에 없으므로 단위의 지금 상태는 `null` 이다.
     */
    private fun copyDetail(row: IncidentCopyRow): IncidentDetailView {
        val stored = json.readValue<IncidentDetailView>(row.detail)
        val resolution = row.resolution?.let { ResolutionView(it.decision, it.at, it.wallClockAt, ApproverView(it.decidedById, it.decidedByKind)) }
        return stored.copy(
            resolution = resolution,
            held = false,
            confirmedWithoutEvidence = resolution?.decision == IncidentDecision.CONFIRM_DONE.name &&
                stored.verification != Verification.MATCHED.name,
            unitState = null,
        )
    }

    /** 송신 기록(S4b 스펙 T6). 최근부터 많아야 [limit] 개다. DB 만 읽고 호스트 잠금을 잡지 않는다. */
    fun jobResponses(jobOrderId: String?, limit: Int): JobResponsesView = JobResponsesView(
        instanceId = instanceId,
        total = records.responseLogCount(jobOrderId),
        responses = records.responseLog(jobOrderId, limit).map(JobResponseLogView::of),
    )

    /**
     * pump 뒤 기록(S4b 스펙 §6.2, T6·T7). 같은 잠금 안에서 한 트랜잭션으로 송신 기록, 새로 봉인된 인시던트의 사본, 아직 판단 행이
     * 없는 판단, 지금의 정착을 아직 적지 않은 정착한 실행([settleRecorded])을 적는다. 트랜잭션이 실패하면 아무것도 `ack` 하지 않고
     * 놓지도 않으며 다음 pump 에 다시 한다. 커밋하고 `ack` 한 뒤 놓을 실행을 놓는다([forgetSettled]).
     *
     * 송신 기록: 새 인스턴스가 그 작업 지시에 대해 처음 내는 응답이 그 작업 지시의 가장 최근 송신 행(인스턴스 무관)과 내용 키가
     * 같으면 재기동 중복으로 적고 송신하지 않는다. 그 밖에는 송신으로 적는다. 어느 쪽이든 적은 뒤 `ack` 한다.
     *
     * 정착은 pump 가 더 돌리지 않는 상태다(`PARTIAL` 은 미들웨어가 계속 돌리므로 정착으로 적지 않는다). 다시 돌아 다시 정착한 실행은
     * 그 정착을 또 적는다(정착 이벤트가 정착마다 하나다).
     *
     * 잔여 파지 표시(P0-b2): 정착 이벤트를 적는 실행 가운데 [residualHoldUnit] 이 단위를 내는 것은 같은 트랜잭션에서 표시를 적는다.
     * 작업 지시마다 하나라 이미 있으면 적지 않는다. 판정이 읽는 사본은 커밋된 뒤에만 늘린다.
     */
    private fun recordAfterPump() {
        val pending = middleware.pending()
        val incidents = middleware.incidents()
        val fresh = incidents.drop(copiedIncidents)
        val resolved = incidents.filter { it.resolution != null && it.incidentId !in copiedResolutions }
        val latest = latestResponses(middleware.responses())
        val settled = middleware.executions().filter {
            !it.physicalState.pumps && it.order.jobOrderId in journaled && !settleRecorded(it, latest)
        }
        val judgments = changedRecords(latest)
        if (pending.isEmpty() && fresh.isEmpty() && resolved.isEmpty() && settled.isEmpty() && judgments.isEmpty()) {
            forgetSettled(latest)
            return
        }

        val held = heldIncidents(incidents)
        // 정착 이력(HA 설계 기록 §10): 정착 이벤트에 그때의 실행 본문을 같이 적는다. 화면은 정착 실행을 메모리가 아니라 이것에서 읽는다.
        val snapshots = if (settled.isEmpty()) emptyList() else view(settled).executions.map(json::writeValueAsString)
        val copies = fresh.map { it to json.writeValueAsString(IncidentViews.detail(instanceId, it, it.incidentId in held, unitStateOf(it))) }
        val marked = mutableListOf<ResidualHoldRow>()
        try {
            records.inTransaction {
                marked.clear()
                // 판단 기록을 송신 기록과 같이 커밋한다. 상위가 받은 응답의 판정은 기록에도 있다. 이 pump 의 것을 한 문장으로 적는다.
                records.judgmentRecords(
                    instanceId, JudgmentRecordWire.SCHEMA_VERSION,
                    judgments.map { (execution, text) -> HostRecords.JudgmentRecordWrite(execution.order.jobOrderId, execution.executionId, text) },
                )
                pending.forEach(::logResponse)
                copies.forEach { (bundle, detail) ->
                    records.copyIncident(instanceId, bundle.incidentId, bundle.executionId, bundle.jobOrderId, bundle.unitId, detail)
                }
                resolved.forEach { bundle ->
                    val resolution = bundle.resolution!!
                    records.copyResolution(
                        instanceId, bundle.incidentId,
                        CopyResolutionRow(
                            resolution.decision.name, resolution.at, resolution.wallClockAt, resolution.decidedBy.id, resolution.decidedBy.kind.name,
                        ),
                    )
                }
                settled.zip(snapshots).forEach { (it, snapshot) ->
                    records.settled(it.order.jobOrderId, instanceId, it.executionId, it.physicalState.name, snapshot)
                    residualHoldUnit(it.units)?.let { unit ->
                        records.markResidualHold(it.robotId, it.order.jobOrderId, it.executionId, instanceId, unit.unitId, unit.hold.objectRef)
                            ?.let(marked::add)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("pump 뒤 기록 실패, 다음 pump 에 다시 한다: {}", e.toString())
            return
        }
        copiedIncidents = incidents.size
        judgments.forEach { (execution, text) -> recorded[execution.order.jobOrderId] = text }
        copiedResolutions += resolved.map { it.incidentId }
        val committedAt = clock.now()
        settled.forEach { settleMarks[it.executionId] = SettleMark(latest[it.executionId]?.jobResponseId, committedAt) }
        openHolds += marked
        pending.forEach { middleware.ack(it.jobResponseId) }
        forgetSettled(latest)
    }

    /**
     * 놓을 실행을 picasso 메모리에서 놓는다(`Middleware.forget`). pump 뒤 기록이 커밋되고 응답을 `ack` 한 뒤에만 부른다. 놓을 실행은 지금의
     * 정착을 적었고([settleRecorded], 그 정착의 본문·판단 기록·송신 기록이 같은 트랜잭션으로 커밋됐다) 활성이 아니며([active]: 보류·불확실
     * 단위가 남은 실행은 사람의 판단을 기다린다), `FAILED`·`UNVERIFIED` 면 정착을 적은 지 [revisableRetention] 이 지난 실행이다.
     *
     * - `Forgotten`: 그 실행의 호스트 쪽 맵을 지운다([dropForgotten]).
     * - `Refused`: 남기고 다음 pump 에 다시 한다(확인되지 않은 통보 따위).
     * - `Unknown`: 이미 없다. 아무것도 안 한다.
     *
     * [latest] 는 이번 pump 뒤 기록이 읽은 [latestResponses] 다. 그 뒤 놓기 전까지 새 작업 응답은 나지 않는다(같은 잠금 안이다).
     */
    private fun forgetSettled(latest: Map<String, JobResponse>) {
        val now = clock.now()
        middleware.executions().filter { forgettable(it, latest, now) }.forEach { execution ->
            when (val outcome = forget(execution.executionId)) {
                ForgetOutcome.Forgotten -> dropForgotten(execution)
                is ForgetOutcome.Refused -> log.warn("실행을 놓지 못했다, 다음 pump 에 다시 한다: {} {}", execution.executionId, outcome.reason)
                ForgetOutcome.Unknown -> Unit
            }
        }
    }

    private fun forgettable(execution: Middleware.Execution, latest: Map<String, JobResponse>, now: Instant): Boolean {
        if (!settleRecorded(execution, latest) || active(execution)) return false
        return forgetDue(execution.physicalState, settleMarks.getValue(execution.executionId).at, now, revisableRetention)
    }

    /** picasso `Middleware.attemptApproval` 을 부르는 자리. 시험이 승인으로 선 실행을 주입하려고 바꾼다. 호스트 잠금 아래에서 부른다. */
    @Volatile
    internal var approve: (Middleware, ApprovalAttempt) -> ApprovalOutcome = { m, attempt -> m.attemptApproval(attempt) }

    /** picasso `Middleware.forget` 를 부르는 자리. 시험이 거절과 모름을 주입하려고 바꾼다. 호스트 잠금 아래에서 부른다. */
    @Volatile
    internal var forget: (String) -> ForgetOutcome = { middleware.forget(it) }

    /** 놓은 실행의 호스트 쪽 맵을 지운다. 재기동에 넘길 것은 모두 DB 에 있다(일지·정착 본문·판단 기록·취소·잔여 파지 표시). */
    private fun dropForgotten(execution: Middleware.Execution) {
        val jobOrderId = execution.order.jobOrderId
        settleMarks.remove(execution.executionId)
        restoredFrom.remove(execution.executionId)
        cancels.remove(execution.executionId)
        recorded.remove(jobOrderId)
        journaled.remove(jobOrderId)
    }

    /**
     * 적을 판단 기록. 일지에 있고 지금의 정착을 아직 적지 않은 실행 가운데 글자가 이 인스턴스가 마지막으로 적은 것과 다른 것이다.
     * 정착하는 pump 의 실행도 든다(마지막 판정이 그 pump 에서 섰다). 다시 돈 실행은 다시 든다. [latest] 는 [latestResponses] 다.
     */
    private fun changedRecords(latest: Map<String, JobResponse> = latestResponses(middleware.responses())): List<Pair<Middleware.Execution, String>> =
        middleware.executions()
            .filter { it.order.jobOrderId in journaled && !settleRecorded(it, latest) }
            .mapNotNull { execution ->
                middleware.judgmentRecord(execution.executionId)?.let(JudgmentRecordWire::encode)
                    ?.takeIf { it != recorded[execution.order.jobOrderId] }
                    ?.let { execution to it }
            }

    private fun writeRecord(execution: Middleware.Execution, text: String) {
        records.judgmentRecord(execution.order.jobOrderId, instanceId, execution.executionId, JudgmentRecordWire.SCHEMA_VERSION, text)
    }

    /**
     * 판단 기록 하나를 지금 적는다(수락 직후, 운영자 판단 직후). 못 적으면 경고만 남긴다 — 그 판단은 이미 미들웨어에 섰고, 다음 pump 뒤
     * 기록이 같은 글자를 다시 적는다(그 pump 가 성공하기 전에 프로세스가 죽으면 그 판단은 넘어오지 않는다).
     */
    private fun persistRecord(executionId: String) {
        val changed = changedRecords().firstOrNull { it.first.executionId == executionId } ?: return
        try {
            writeRecord(changed.first, changed.second)
            recorded[changed.first.order.jobOrderId] = changed.second
        } catch (e: Exception) {
            log.warn("판단 기록을 적지 못했다, 다음 pump 에 다시 한다: {} {}", executionId, e.toString())
        }
    }

    private fun logResponse(response: JobResponse) {
        val content = contentOf(response)
        val duplicate = records.firstResponseBasis(instanceId, response.jobOrderId)?.content == content
        records.logResponse(
            instanceId, response.jobResponseId, response.executionId, content,
            if (duplicate) ResponseDisposition.RESTART_DUPLICATE else ResponseDisposition.SENT,
        )
        if (!duplicate) {
            val journal = checkNotNull(records.journalRow(response.jobOrderId))
            val snapshot = json.createObjectNode().apply {
                put("instanceId",instanceId); put("executionId",response.executionId)
                put("jobOrderId",response.jobOrderId); put("robotId",journal.robotId)
                put("workMasterId",journal.workMasterId)
                if(journal.missionVersion == null) putNull("missionVersion") else put("missionVersion",journal.missionVersion)
                put("physicalState",response.physicalState.name)
                set<com.fasterxml.jackson.databind.JsonNode>("jobResponse",json.valueToTree(view(response)))
            }
            records.appendResult(instanceId,response.jobResponseId,snapshot)
        }
    }

    /**
     * 운영자 판단(S4a 스펙 T4, T6). 호스트 잠금 아래에서 `Middleware.resolve` 를 부르고 결과 이름을 picasso 그대로 돌려준다.
     * 대상은 보류 단위이거나, 선행 이동이 시도 뒤 실패해 내지 않은 점검인 건너뛴 대상이다(picasso ADR 53). 건너뛴 대상은 `REWORK` 만 받고
     * 그 점검과 선행 이동을 한 쌍으로 새 시도로 되돌린다.
     *
     * Resolved 이면 판단이 붙은 인시던트 id 를 같이 낸다. 어느 인시던트에 붙는지는 picasso 가 정한다(`IncidentLog.noteResolution`). 보류
     * 단위면 그 단위의 판단 없는 가장 최근 인시던트이고, 건너뛴 대상이면 선행 이동의 것이다(건너뛴 점검은 인시던트를 열지 않았다). 그래서
     * 요청한 단위 id 로 고르지 않고, 부르기 전 그 실행의 판단 없는 인시던트 가운데 부른 뒤 판단이 새로 붙은 것을 고른다. 호스트 잠금 아래라
     * 그 사이에 다른 판단이 끼지 않는다. 판단이 아무 인시던트에도 안 붙었으면 `null` 이다.
     *
     * Refused 는 에이전트 승인자일 때와 건너뛴 대상 재작업을 지금 받지 않을 때 난다. 앞의 것은 REST 가 늘 `PERSON` 승인자를 만들므로 이
     * 메서드를 직접 부를 때만 난다. 뒤의 것은 확인(`CONFIRM_DONE`) 요청, 취소한 실행, 순회가 아직 종결하지 않은 실행, 같은 기체의 다른 활성
     * 실행이며 REST 로도 난다. `detail` 은 picasso 사유 문장 그대로이고 호출한 쪽이 이 문장으로 분기해서는 안 된다.
     * NotHeld 는 실행이 없거나 그 단위가 보류도 건너뛴 점검 모양도 아닐 때 난다. 그 실행이 picasso 메모리에 없고 이 인스턴스의 종결 기록이
     * 있으면, 곧 이 인스턴스가 이빅션한 실행이면 `detail` 에 [EVICTED] 를 싣는다. 그 밖의 NotHeld 의 `detail` 은 `null` 이다.
     *
     * picasso `resolve` 는 판단으로 실행의 보류가 걷히면 그 자리에서 작업 응답 하나(`physicalState` `RUNNING`)를 낸다. 다른 단위가 아직
     * 보류이면 실행은 보류 그대로이고 내지 않는다. 다음 pump 가 그 응답을 송신 기록과 결과 outbox 에 한 번 적는다([release] 와 같다).
     * 건너뛴 대상 재작업은 종료한 실행(`PARTIAL`·`FAILED`)을 `RUNNING` 으로 되돌리고 작업 응답 하나를 낸다. `FAILED` 는 종결을 적은 실행의
     * 재실행이라 [recordReruns] 가 재실행 이벤트를 적고, `PARTIAL` 은 종결로 적은 적이 없어 적지 않는다. 어느 쪽이든 판단 기록은 곧바로 적는다.
     */
    fun resolve(executionId: String, unitId: String, decision: OperatorDecision, approver: Approver, requestId: String?): ResolveView =
        lock.withLock {
            val open = middleware.incidents().filter { it.executionId == executionId && it.resolution == null }.map { it.incidentId }.toSet()
            when (val outcome = middleware.resolve(executionId, unitId, decision, approver)) {
                ResolveOutcome.Resolved -> {
                    val target = middleware.incidents().lastOrNull { it.incidentId in open && it.resolution != null }?.incidentId
                    // 종결을 적은 실행이 판단으로 다시 돌면 재실행과 판단 기록을 같이 적는다. 못 적으면 pump 가 다시 적을 때까지 pump 를 막는다.
                    recordReruns()
                    persistRecord(executionId)
                    ResolveView(RESOLVED, null, target, requestId)
                }
                ResolveOutcome.NotHeld -> ResolveView(NOT_HELD, EVICTED.takeIf { evicted(executionId) }, null, requestId)
                is ResolveOutcome.Refused -> ResolveView(REFUSED, outcome.reason, null, requestId)
            }
        }

    /** 그 실행이 picasso 메모리에 없고 이 인스턴스의 종결 기록이 있는가. 호스트 잠금 아래에서 부른다. */
    private fun evicted(executionId: String): Boolean =
        middleware.executions().none { it.executionId == executionId } &&
            records.settledSnapshots(instanceId, executionIds = setOf(executionId)).isNotEmpty()

    /**
     * 운영자 실행 취소(P0-b2). 호스트 잠금 아래에서 판정하고, 취소를 DB 에 적은 뒤 미들웨어 운영자 취소 입구(`cancelByOperator`, picasso
     * §15.320)를 승인자와 함께 부른다. 적지 못하면 예외가 나가고 취소하지 않는다. 그 입구는 그 실행의 운영자 보류·미확정 단위의 인시던트 판단
     * 칸에 `OPERATOR_CANCEL` 을 승인자와 함께 싣는다 — «인시던트 → 사람이 닫음 → 탐색» 이 인시던트 데이터로 남는다. 상류의 취소가 아니므로
     * 판단 칸 없는 `cancel` 을 쓰지 않는다. 실제 중단은 다음 pump 가 하류를 읽고 확정하며, 그 뒤는 보통의 정착 기록(정착 이벤트, `ABORTED` 작업
     * 응답, 잔여 파지 표시)이다.
     *
     * 받는 실행은 물리 상태가 `pumps` 인 것(정착하지 않았거나 부분 완료 `PARTIAL`)뿐이다. 이미 취소를 건 실행(`CANCELING`, 또는 이
     * 인스턴스의 취소 행이 있음)과 실행 일지에 없는 실행은 NOT_CANCELABLE 이다. 이 인스턴스가 정착을 적고 놓은 실행도 정착한 실행이라
     * NOT_CANCELABLE 이다(상태는 마지막 정착 이벤트의 것). 승인자는 사람이어야 한다(ADR 47 과 같은 규칙). picasso 의 입구도 에이전트를
     * 거절하지만 호스트가 먼저 판정해 취소 행을 적지 않는다.
     *
     * `NOT_FOUND` 는 «그 실행이 지금 인스턴스에 없다» 일 뿐 그 작업 지시를 보내지 않았다는 증거가 아니다(값 없는 승인이 뒤에 실행을 세울 수 있다).
     * 미실행 작업 지시를 폐기하는 취소를 만들면 같은 철회([withdraw])를 거친다.
     */
    fun cancel(executionId: String, approver: Approver, reason: String, requestId: String?): CancelView = lock.withLock {
        if (approver.kind != ApproverKind.PERSON) {
            return@withLock CancelView(CancelResult.REFUSED, executionId, null, "$AGENT_CANCEL_REFUSED: ${approver.id}", requestId)
        }
        val execution = middleware.executions().firstOrNull { it.executionId == executionId }
            ?: return@withLock records.settledSnapshots(instanceId, executionIds = setOf(executionId)).singleOrNull()
                ?.let { CancelView(CancelResult.NOT_CANCELABLE, executionId, it.physicalState, "종결한 실행은 취소하지 않는다: ${it.physicalState}", requestId) }
                ?: CancelView(CancelResult.NOT_FOUND, executionId, null, "그 실행이 지금 인스턴스에 없다", requestId)
        val state = execution.physicalState
        val refusal = when {
            !state.pumps -> "종결한 실행은 취소하지 않는다: ${state.name}"
            state == PhysicalState.CANCELING || executionId in cancels -> "이미 취소를 건 실행이다"
            execution.order.jobOrderId !in journaled -> "실행 일지에 없는 실행이다"
            else -> null
        }
        if (refusal != null) return@withLock CancelView(CancelResult.NOT_CANCELABLE, executionId, state.name, refusal, requestId)
        val row = records.cancel(instanceId, executionId, execution.order.jobOrderId, state.name, approver.id, approver.kind.name, reason)
        cancels[executionId] = row
        val outcome = middleware.cancelByOperator(executionId, approver)
        check(outcome == ResolveOutcome.Resolved) { "미들웨어가 운영자 취소를 받지 않았다: $executionId ${state.name} $outcome" }
        CancelView(CancelResult.CANCELED, executionId, state.name, null, requestId)
    }

    /**
     * 운영자 결함 감수(결함 감수·조치 승인 노출 설계 제안 §2.1). picasso `release` 는 승인자와 사유를 받지 않고 막는 결함 전부를 감수하므로,
     * 사람 승인자 판정과 «본 결함» 대조를 이 호스트가 한다. 모두 호스트 잠금 아래다 — 대조와 감수 사이에 막는 결함이 바뀔 틈이 없다.
     *
     * 순서: 사람 승인자 → 요청 id 재사용([ReleaseRequestReused]) → 실행 찾기 → 막는 결함 유무 → [saw] 와 지금 막는 결함 대조(오류 종류와
     * 참조 집합, 순서 무관, 같은 결함 둘이면 둘) → 감수 행 적기 → `Middleware.release` → 판단 기록([persistRecord]). 감수 행을 못 적으면
     * 예외가 나가고 picasso 를 부르지 않는다(아무것도 바뀌지 않는다). 요청 id 의 UNIQUE 는 DB 의 둘째 담이다 — 잠금 안에서 먼저 묻고 적으므로
     * 이 인스턴스에서는 거기 걸리지 않고, 걸리면 쓰기 실패와 같게 나간다. 판단 기록을 못 적으면 경고만 남기고 다음 pump 가 같은 글자를 다시
     * 적는다(운영자 판단과 같다).
     *
     * 실행이 picasso 메모리에 없으면 이 인스턴스가 놓은 실행인지 정착 본문으로 가른다. 놓은 실행은 정착한 실행이라 NOT_BLOCKED 이고 그
     * 정착 상태를 싣는다. 둘 다 없으면 NOT_FOUND 다. 작업 허가만 막고 있으면 NOT_BLOCKED 이고 사유가 [PERMIT_WAIT] 로 시작한다 — 허가는
     * 감수할 값이 아니다(ADR 52, picasso `release` 가 걷지 않는다).
     *
     * picasso `release`는 true를 반환할 때 `notify`를 한 번 부르고 새 작업 응답(`physicalState` `RUNNING`, `blockedBy` `[]`)을 낸다.
     * [release] 직후 실행 목록의 마지막 작업 응답(`jobResponse`)과 `physicalState`·`blockedFaults`는 곧바로 바뀐다. 다음 pump가 이를
     * 송신 기록과 결과 outbox에 한 번 기록한다.
     */
    fun release(executionId: String, approver: Approver, reason: String, saw: List<FaultSighting>, requestId: String?): ReleaseView = lock.withLock {
        if (approver.kind != ApproverKind.PERSON) {
            return@withLock ReleaseView(ReleaseResult.REFUSED, executionId, null, "$AGENT_RELEASE_REFUSED: ${approver.id}", requestId)
        }
        val requestKey = requestId?.let(UUID::fromString)
        if (requestKey != null && records.releaseByRequest(requestKey) != null) throw ReleaseRequestReused(requestId)
        val execution = middleware.executions().firstOrNull { it.executionId == executionId }
            ?: return@withLock records.settledSnapshots(instanceId, executionIds = setOf(executionId)).singleOrNull()
                ?.let { ReleaseView(ReleaseResult.NOT_BLOCKED, executionId, it.physicalState, "$SETTLED_NOT_BLOCKED: ${it.physicalState}", requestId) }
                ?: ReleaseView(ReleaseResult.NOT_FOUND, executionId, null, "그 실행이 지금 인스턴스에 없다", requestId)
        val blocking = execution.blockedBy
        if (blocking.isEmpty()) {
            val detail = permitWait(execution)
                ?: if (execution.physicalState.pumps) NO_BLOCKING_FAULT else "$SETTLED_NOT_BLOCKED: ${execution.physicalState.name}"
            return@withLock ReleaseView(ReleaseResult.NOT_BLOCKED, executionId, execution.physicalState.name, detail, requestId)
        }
        if (sightings(saw) != blocking.map(::sighting).sorted()) {
            val now = blocking.joinToString(", ") { fault -> fault.errorType + fault.referencesList.joinToString(",", "[", "]") { "${it.key.name}=${it.value}" } }
            return@withLock ReleaseView(ReleaseResult.BLOCK_CHANGED, executionId, execution.physicalState.name, "$BLOCK_CHANGED_DETAIL: $now", requestId)
        }
        records.release(
            instanceId, executionId, execution.order.jobOrderId, json.writeValueAsString(blocking.map(FaultDetailView::fromContract)),
            approver.id, approver.kind.name, reason, requestKey,
        )
        check(middleware.release(executionId)) { "미들웨어가 감수를 받지 않았다: $executionId" }
        persistRecord(executionId)
        ReleaseView(ReleaseResult.RELEASED, executionId, execution.physicalState.name, permitWait(execution), requestId)
    }

    /** 작업 허가가 이 실행의 새 로봇 명령을 막고 있으면 그 사실, 아니면 `null`. */
    private fun permitWait(execution: Middleware.Execution): String? =
        execution.permitBlock?.let { "$PERMIT_WAIT: ${it.unitId} (${it.withheld.name})" }

    /** 운영자가 본 결함을 대조할 꼴로. 참조는 순서 없이 본다. */
    private fun sightings(saw: List<FaultSighting>): List<String> =
        saw.map { sighting(it.errorType, it.references.map { r -> r.key to r.value }) }.sorted()

    private fun sighting(fault: Fault): String = sighting(fault.errorType, fault.referencesList.map { it.key.name to it.value })

    private fun sighting(errorType: String, references: List<Pair<String, String>>): String =
        json.writeValueAsString(listOf(errorType, references.sortedWith(compareBy({ it.first }, { it.second })).map { listOf(it.first, it.second) }))

    /**
     * 호스트 메모리의 크기(놓기, 한계 §15.224 · 놓기 소비자). 미들웨어의 실행·아웃박스와 실행마다 드는 호스트 쪽 맵의 크기다. 놓기가
     * 돌면 활성 실행과 개정판을 받으려고 두는 실행만큼이다. 시험과 부하 하네스가 읽는다.
     */
    fun footprint(): HostFootprint = lock.withLock {
        HostFootprint(
            executions = middleware.executions().size,
            responses = middleware.responses().size,
            settleMarks = settleMarks.size,
            journaled = journaled.size,
            recorded = recorded.size,
            restoredFrom = restoredFrom.size,
            cancels = cancels.size,
        )
    }

    /** 미해소 잔여 파지 표시(P0-b2). 적은 순서다. */
    fun residualHolds(): ResidualHoldsView = lock.withLock { ResidualHoldsView(openHolds.map(ResidualHoldView::of)) }

    /**
     * 잔여 파지 표시 해소(P0-b2, B2). 사람이 그 기체가 빈손임을 현장에서 확인했다는 기록을 적고 판정이 읽는 사본에서 뺀다. 원본은 DB 라
     * 사본에 없더라도 DB 로 판정한다. 그 기체의 표시가 아니면 NotFound, 이미 해소했으면 AlreadyCleared 다. 승인자는 사람이어야 한다.
     *
     * 해소하기 전에 그 기체의 스냅숏을 지금 한 번 읽는다([HostRobotPort.snapshot] 은 미리 읽은 값을 쓰지 않는다). 발신자가 지금 든 채(HOLDING)라고
     * 말하면 해소하지 않는다 — 그 관측을 충돌 행으로 적고(V15) [ClearOutcome.ObservationMismatch] 다. 사람이 현장에서 빈손을 확인했으면 그 저장된 충돌을
     * [cited] 로 지목해 새 요청 id 로 다시 확인한다. 순서는 이렇다.
     *
     * 1. 같은 [requestId] 의 충돌 행이 있으면 그 저장된 충돌을 그대로 돌려준다(재전송·재기동 뒤에도 같다. 새 행 없음). 다른 표시의 충돌이면 RequestReused 다. 다른 표시의 해소 행에 쓴 요청 id 도 RequestReused 다.
     * 2. [cited] 가 있으면 저장된 충돌과 대조한다: 있고, 같은 표시이고, 같은 승인자이고, 관측 키(종류·물품·세션·순번, 널끼리 같음)가 같아야 한다.
     *    하나라도 어긋나면 CitationInvalid 이고 아무것도 적지 않는다.
     * 3. 스냅숏을 읽는다. 못 읽으면 `UNREADABLE`, 발신자가 손을 말하지 않으면 `UNSPECIFIED`, 관측 불가면 `NOT_OBSERVABLE` 이다.
     * 4. 지금 HOLDING 이 아니면 해소한다(어긋남 인지 없음). 지목이 있었으면 그 충돌을 해소 행에 잇는다.
     *    지금 HOLDING 이고 지목한 충돌과 같은 손이면(물품이 같고, 세션이 둘 다 미상이거나 같은 세션에서 순번이 뒤로 가지 않았으면) 어긋남을 알고 해소한다.
     *    지금 HOLDING 이고 지목이 없거나 손이 다르면 이번 요청 id 로 새 충돌 행을 적고 ObservationMismatch 다. 표시는 그대로다.
     *
     * [requestId] 가 없으면 호스트가 만든다(해소 행과 충돌 행은 요청 id 가 필수다).
     */
    fun clearResidualHold(
        robotId: String,
        markerId: Long,
        approver: Approver,
        reason: String,
        requestId: String?,
        cited: CitedConflict? = null,
    ): ClearOutcome = lock.withLock {
        if (approver.kind != ApproverKind.PERSON) return@withLock ClearOutcome.Refused("$AGENT_CLEAR_REFUSED: ${approver.id}")
        val (hold, clearing) = records.residualHold(markerId)?.takeIf { it.first.robotId == robotId } ?: return@withLock ClearOutcome.NotFound
        if (clearing != null) return@withLock ClearOutcome.AlreadyCleared(ResidualHoldClearingView.of(clearing))
        val request = requestId?.let(UUID::fromString) ?: UUID.randomUUID()
        records.residualHoldConflict(request)?.let { stored ->
            if (stored.markerId != markerId) {
                return@withLock ClearOutcome.RequestReused("요청 id $request 는 잔여 파지 표시 ${stored.markerId} 의 충돌에 이미 썼다")
            }
            return@withLock mismatch(stored, robotId)
        }
        records.residualHoldClearingMarker(request)?.let { other ->
            return@withLock ClearOutcome.RequestReused("요청 id $request 는 잔여 파지 표시 $other 의 해소에 이미 썼다")
        }
        val conflict = cited?.let { key ->
            val stored = records.residualHoldConflict(key.conflictRequestId)
                ?: return@withLock ClearOutcome.CitationInvalid("지목한 충돌 ${key.conflictRequestId} 가 없다")
            citationProblem(stored, key, markerId, approver.id)?.let { return@withLock ClearOutcome.CitationInvalid(it) }
            stored
        }
        val observation = observeHand(robotId)
        val holding = observation.observedKind == HAND_HOLDING
        if (holding && (cited == null || !sameHand(cited, observation))) {
            return@withLock mismatch(records.recordResidualHoldConflict(request, markerId, approver.id, approver.kind.name, reason, observation), robotId)
        }
        val cleared = records.clearResidualHold(markerId, approver.id, approver.kind.name, reason, request, observation, conflict?.requestId, holding)
        openHolds.removeAll { it.markerId == markerId }
        ClearOutcome.Cleared(ResidualHoldClearedView(ResidualHoldView.of(hold), ResidualHoldClearingView.of(cleared), request.toString()))
    }

    /**
     * 표시 하나와 그 해소 행(B2). 호스트 잠금 아래에서 읽는다 — 해소는 잠금 안에서 스냅숏을 읽느라 늦을 수 있고, 잠금 없이 읽으면 응답 없음 뒤 재조회가
     * 처리 중인 해소를 «해소 행 없음» 으로 확정한다. 그 기체의 표시가 아니면 `null` 이다.
     */
    fun residualHoldRecord(robotId: String, markerId: Long): ResidualHoldRecordView? = lock.withLock {
        records.residualHold(markerId)?.takeIf { it.first.robotId == robotId }
            ?.let { (hold, clearing) -> ResidualHoldRecordView(ResidualHoldView.of(hold), clearing?.let(ResidualHoldClearingView::of)) }
    }

    private fun mismatch(stored: ResidualHoldConflictRow, robotId: String) =
        ClearOutcome.ObservationMismatch(stored.requestId.toString(), stored.observation, stored.markerId, robotId)

    /** 지목이 저장된 충돌과 어긋나는 까닭. 맞으면 `null` 이다. */
    private fun citationProblem(stored: ResidualHoldConflictRow, cited: CitedConflict, markerId: Long, approverId: String): String? {
        val seen = stored.observation
        return when {
            stored.markerId != markerId -> "지목한 충돌 ${stored.requestId} 는 잔여 파지 표시 ${stored.markerId} 의 것이다"
            stored.approverId != approverId -> "지목한 충돌 ${stored.requestId} 는 다른 승인자(${stored.approverId})의 확인이다. 다시 확인하는 사람이 그 충돌을 받은 사람이어야 한다"
            seen.observedKind != cited.kind || seen.observedObjectRef != cited.objectRef ||
                seen.snapshotSession != cited.session || seen.snapshotSequence != cited.sequence ->
                "지목한 관측이 저장된 충돌 ${stored.requestId} 와 다르다(저장값: ${seen.observedKind} ${seen.observedObjectRef} ${seen.snapshotSession} ${seen.snapshotSequence})"
            else -> null
        }
    }

    /**
     * 지금 HOLDING 인 손이 지목한 충돌의 손과 같은가. 물품 이름은 널끼리 같고 한쪽만 널이면 다르다. 세션은 둘 다 미상이면 막지 않고(순번 역행 검사 생략)
     * 한쪽만 미상이면 다르며, 둘 다 있으면 같아야 하고 지금 순번이 지목한 순번보다 뒤로 가지 않아야 한다.
     */
    private fun sameHand(cited: CitedConflict, now: HoldObservation): Boolean {
        if (cited.kind != HAND_HOLDING || now.observedKind != HAND_HOLDING || cited.objectRef != now.observedObjectRef) return false
        val session = cited.session
        val current = now.snapshotSession
        if (session == null || current == null) return session == null && current == null
        val from = cited.sequence ?: return false
        val at = now.snapshotSequence ?: return false
        return session == current && at >= from
    }

    /** 그 기체의 스냅숏을 지금 한 번 읽어 손을 관측 칸 여덟로 옮긴다. 읽기 시작과 수신은 호스트 시계다. */
    private fun observeHand(robotId: String): HoldObservation {
        val startedAt = clock.now()
        fun unreadable(detail: String) = HoldObservation(HAND_UNREADABLE, null, detail, null, null, null, startedAt, clock.now())
        val snapshot = try {
            robots.snapshot(robotId)
        } catch (e: RuntimeException) {
            return unreadable("스냅숏 읽기 실패: ${e.message ?: e.javaClass.simpleName}")
        } ?: return unreadable("스냅숏을 읽지 못했다")
        val receivedAt = clock.now()
        val hold = snapshot.hold
        val kind = when (hold?.kind) {
            HoldKind.HOLD_KIND_HOLDING -> HAND_HOLDING
            HoldKind.HOLD_KIND_EMPTY -> "EMPTY"
            HoldKind.HOLD_KIND_NOT_OBSERVABLE -> "NOT_OBSERVABLE"
            else -> "UNSPECIFIED"
        }
        return HoldObservation(
            observedKind = kind,
            observedObjectRef = hold?.objectRef?.takeIf { kind == HAND_HOLDING && it.isNotEmpty() },
            observedDetail = hold?.reason?.takeIf { kind == "NOT_OBSERVABLE" && it.isNotEmpty() },
            snapshotSession = snapshot.session.takeIf { it.isNotEmpty() },
            snapshotSequence = snapshot.sequence,
            snapshotOccurredAt = snapshot.occurredAt,
            hostReadStartedAt = startedAt,
            hostReadReceivedAt = receivedAt,
        )
    }

    /**
     * 보류 중인 인시던트. 그 단위가 지금 운영자 보류이고, 그 단위의 인시던트 가운데 `unresolved` 이고 판단이 없는 가장 최근의
     * 것이다. 재작업 뒤 두 번째 보류가 서면 앞 인시던트는 판단이 붙어 빠지므로 한 단위에 많아야 하나다.
     */
    private fun heldIncidents(all: List<IncidentBundle>): Set<String> {
        val holding = middleware.executions().flatMap { execution ->
            execution.units.filter { it.state == UnitState.OPERATOR_HOLD }.map { execution.executionId to it.unitId }
        }.toSet()
        return all.filter { (it.executionId to it.unitId) in holding && it.unresolved && it.resolution == null }
            .groupBy { it.executionId to it.unitId }
            .values.map { it.last().incidentId }
            .toSet()
    }

    /**
     * 작업 허가의 지금(ADR 52). 현장이 대응을 선언하지 않았으면 `declared` 가 거짓이고 나머지가 비어 있다. 판정은 발행 게이트와 같은 규칙이다
     * ([CellWorkPermits.evaluate]). 지어 둔 판독만 읽으므로 호스트 잠금을 잡지 않는다.
     */
    fun workPermits(): WorkPermitsView {
        val at = clock.now()
        val source = permits ?: return WorkPermitsView(false, at, null, null, emptyList(), emptyList())
        return WorkPermitsView(
            declared = true,
            evaluatedAt = at,
            session = source.currentSession,
            validFor = source.map.validFor.toString(),
            mappings = source.map.signals.map { PermitMappingView(it.signal, it.permittedWhen, it.robots.toList()) },
            robots = source.evaluate(at).map(RobotPermitView::of),
        )
    }

    /** 마지막 pump 가 읽은 셀 대역 스냅숏. 못 읽었으면 `null` 이다. */
    fun cell(): CellSnapshot? = lock.withLock { latestCell }

    /** [action] 을 호스트 잠금 아래에서 돈다. 임무 버전 활성화가 쓴다(S3b 스펙 T2). 잠금은 재진입된다. */
    fun <T> exclusive(action: () -> T): T = lock.withLock(action)

    /** 검증 입력을 잠금 아래에서 한 번에 읽는다(S3b 스펙 T7). 검증과 모의 실행이 쓴다. 그동안 DB 연결을 쥐지 않는다. */
    fun siteInputs(robotIds: List<String>): SiteInputs = lock.withLock { siteInputsLocked(robotIds) }

    /**
     * 검증 입력. 신호 사양은 마지막 pump 가 읽은 셀 대역 스냅숏에서, 현장 스킬은 [robotIds] 마다 기체가 선언한 스킬에서
     * 온다. 케이퍼빌리티를 못 물어본 기체는 `null` 이다. 이미 잠금을 쥔 쪽(활성화)만 부른다.
     */
    fun siteInputsLocked(robotIds: List<String>): SiteInputs {
        check(lock.isHeldByCurrentThread) { "검증 입력은 호스트 잠금 아래에서 읽는다" }
        return SiteInputs(
            cell = latestCell,
            skillsByRobot = robotIds.distinct().associateWith { id -> robots.capabilities(id)?.skillsList?.map { it.skillType }?.toSet() },
        )
    }

    /**
     * 스킬 적합은 지금 활성 정의로 작업 지시를 계획해, 경로가 로봇인 단위의 스킬이 기체가 선언한 스킬에 다 있는가다.
     * 도는 실행은 그 기체를 쥔 실행이다(picasso `PhysicalState.occupiesRobot`). 정착하지 않은 실행과 부분 완료(`PARTIAL`) 실행이고,
     * 운영자 보류에 선 실행도 도는 실행이다(스펙 §12). 부분 완료는 리비전으로 남은 단위가 다시 돌 수 있어 그 기체가 아직 비지 않았다.
     * 부분 완료 실행은 운영자 취소([cancel])로 닫는다. 같은 작업 지시 id 의 개정판은 그 실행 자신을 빼고 판정한다([submit]).
     */
    private fun journalBlocked(among: List<Middleware.Execution> = middleware.executions()): List<String> =
        among.filter { it.order.jobOrderId !in journaled || rerunOf(it) != null }.map { it.executionId }

    /**
     * 다시 돎 하나. [revised] 는 일지에 적은 판보다 높은 판을 받았고, [reopened] 는 이 인스턴스가 정착을 적은 실행이 다시 돌고 그것을 아직
     * 적지 않았다.
     */
    private class Rerun(val execution: Middleware.Execution, val revised: Boolean, val reopened: Boolean)

    /** [execution] 의 아직 적지 않은 다시 돎. 없으면 `null` 이다. 일지에 없는 실행은 [journalBlocked] 가 따로 본다. */
    private fun rerunOf(execution: Middleware.Execution): Rerun? {
        val written = journaled[execution.order.jobOrderId] ?: return null
        val revised = execution.order.version > written
        val reopened = execution.physicalState.pumps && settleMarks[execution.executionId]?.reopened == false
        return if (revised || reopened) Rerun(execution, revised, reopened) else null
    }

    /**
     * 아직 적지 않은 다시 돎을 한 트랜잭션으로 적는다(V10). 받은 개정판 행, 정착 뒤 다시 돎(`REOPENED`, 상세는 지금의 물리 상태), 그 실행의
     * 바뀐 판단 기록이다. 커밋한 뒤에만 [journaled]·[settleMarks]·[recorded] 를 고친다. 실패하면 그 예외를 돌려주고 아무것도 고치지 않는다 —
     * 그 실행은 [journalBlocked] 에 남아 pump 와 신규 제출을 막고 다음 pump 가 다시 적는다.
     */
    private fun recordReruns(): Exception? {
        val reruns = middleware.executions().mapNotNull(::rerunOf)
        if (reruns.isEmpty()) return null
        val ids = reruns.mapTo(HashSet()) { it.execution.executionId }
        val judgments = changedRecords().filter { it.first.executionId in ids }
        try {
            records.inTransaction {
                reruns.forEach { rerun ->
                    val execution = rerun.execution
                    val jobOrderId = execution.order.jobOrderId
                    if (rerun.revised) records.revision(jobOrderId, execution.order.version, orderJson(execution.order), instanceId, execution.executionId)
                    if (rerun.reopened) records.event(jobOrderId, JournalEventKind.REOPENED, instanceId, execution.executionId, execution.physicalState.name)
                }
                judgments.forEach { (execution, text) -> writeRecord(execution, text) }
            }
        } catch (e: Exception) {
            log.warn("다시 돈 실행을 적지 못했다, 적을 때까지 pump 를 막는다: {} {}", ids, e.toString())
            return e
        }
        reruns.forEach { rerun ->
            val execution = rerun.execution
            journaled[execution.order.jobOrderId] = execution.order.version
            if (rerun.reopened) settleMarks[execution.executionId] = settleMarks.getValue(execution.executionId).copy(reopened = true)
        }
        judgments.forEach { (execution, text) -> recorded[execution.order.jobOrderId] = text }
        return null
    }

    /** [revising] 은 개정할 기존 실행이다. 그 실행 자신은 그 기체를 쥐어도 도는 실행으로 세지 않는다([submit]). */
    private fun judge(order: JobOrder, robotId: String, applied: Boolean, revising: String? = null, trialable: Boolean = false): HostEligibility {
        val active = requireNotNull(catalog.active(order.workMasterId)) { "카탈로그에 없는 WorkMaster 다: ${order.workMasterId}" }
        val needed = active.capability.plan(order).filter { it.route == Route.ROBOT }.map { it.skillType }.toSortedSet()
        val declared = robots.capabilities(robotId)?.skillsList?.map { it.skillType }?.toSet()
        val missing = declared?.let { (needed - it).toList() } ?: emptyList()
        val fit = when {
            declared == null -> SkillFit.UNKNOWN
            missing.isEmpty() -> SkillFit.FIT
            else -> SkillFit.MISSING
        }
        val running = runningOn(robotId, revising)
        val reasons = buildList {
            when (fit) {
                SkillFit.FIT -> Unit
                SkillFit.MISSING -> add("모자란 스킬: ${missing.joinToString(", ")}")
                SkillFit.UNKNOWN -> add("기체 케이퍼빌리티를 못 물어봤다")
            }
            addAll(robotRestrictions(robotId, applied, revising))
        }
        // 남은 이유가 잔여 파지 표시 하나뿐인 새 접수면 관문 확인용으로 넘긴다(§15.317). 개정([revising])에는 쓰지 않는다 — picasso 도 막지만 이 호스트가 먼저 막는다.
        val trial = trialable && revising == null && reasons.size == 1 && reasons.single().startsWith(RESIDUAL_HOLD_REASON)
        return HostEligibility(
            robotId, fit, missing, running, passed = reasons.isEmpty() || trial, reasons = reasons,
            requiredSkills = needed.toList(), availableSkills = declared?.sorted(), gateTrial = trial,
        )
    }

    /** 이 기체를 쥔 실행(개정할 [revising] 자신은 빼고). */
    private fun runningOn(robotId: String, revising: String?): String? = middleware.executions()
        .firstOrNull { it.robotId == robotId && it.physicalState.occupiesRobot && it.executionId != revising }?.executionId

    /**
     * 작업 지시와 무관한 **기체 단위의 호스트 제한** — 진행 중인 실행, 복원 못 한 행, 증언 종결 확인 전, 잔여 파지 표시, 현장 시간값 미적용, 실행
     * 일지 막힘. 판정([judge])과 값 없는 승인([attemptApproval])이 같은 함수를 쓴다: 승인 접수는 미들웨어 관문을 다시 지나지만 관문은 이 호스트의
     * 제한을 모르므로, 여기서 따로 보지 않으면 승인이 판정이 막은 기체에 실행을 세운다. 호스트 잠금 아래에서 부른다.
     */
    private fun robotRestrictions(robotId: String, applied: Boolean, revising: String? = null, holds: Boolean = true): List<String> = buildList {
        runningOn(robotId, revising)?.let { add("진행 중인 실행이 있다: $it") }
        val (unrestored, closedUnconfirmed) = unrestoredOn(robotId)
        unrestored.takeIf { it.isNotEmpty() }?.let { add("$UNRESTORED_REASON: ${it.joinToString(", ")}") }
        closedUnconfirmed.takeIf { it.isNotEmpty() }?.let { add("$CLOSED_UNCONFIRMED_REASON: ${it.joinToString(", ")}") }
        openHolds.filter { holds && it.robotId == robotId }.takeIf { it.isNotEmpty() }
            ?.let { holds -> add("$RESIDUAL_HOLD_REASON: ${holds.joinToString(", ") { "${it.jobOrderId} ${it.unitId}" }}") }
        if (!applied) add(UNAPPLIED_REASON)
        if (journalBlocked().isNotEmpty()) add("실행 일지 저장 실패: 호스트 복구 전 배정 차단")
    }

    /**
     * 이 기체에서 다시 짓지 못한 작업 지시(T4)와, 정지 증언으로 종결했으나 스냅숏이 아직 동의하지 않은 작업 지시(결함 14). 앞의 것은 미룬 행이
     * 늘 들고, 포기한 행은 그 기체의 스냅숏을 이번에 한 번 읽어 그 작업 지시의 종료하지 않은 태스크(id 가 `jobOrderId#` 로 시작, `@rN` 이
     * 붙은 재작업 태스크 포함)가 없으면 풀고 더 빼지 않는다. 뒤의 것도 같은 읽기와 같은 규칙이다. 스냅숏을 못 읽으면 둘 다 계속 뺀다.
     *
     * @return (복원 못 한 작업 지시, 증언으로 종결했으나 확인 전인 작업 지시)
     */
    private fun unrestoredOn(robotId: String): Pair<List<String>, List<String>> {
        val waiting = deferred.filter { it.row.robotId == robotId }.map { it.row.jobOrderId }
        val abandoned = gaveUp.filter { it.robotId == robotId }
        val closed = attested.filter { it.row.robotId == robotId }
        if (abandoned.isEmpty() && closed.isEmpty()) return waiting to emptyList()
        val snapshot = robots.snapshot(robotId) ?: return (waiting + abandoned.map { it.jobOrderId }) to closed.map { it.row.jobOrderId }
        fun holding(jobOrderId: String) =
            snapshot.tasks.any { (taskId, state) -> taskId.startsWith("$jobOrderId#") && state !in TERMINAL_TASK_STATES }
        val still = abandoned.filter { holding(it.jobOrderId) }
        gaveUp.removeAll((abandoned - still.toSet()).toSet())
        val stillClosed = closed.filter { holding(it.row.jobOrderId) }
        // 스냅숏이 동의한 증언 종결은 확인 행을 적은 뒤에만 메모리에서 뺀다(재기동이 다시 올리지 않는다). 못 적으면 로그만 남기고 메모리에 둔다 —
        // 그 기체는 계속 빠지고 판정은 깨지지 않으며 다음 판정이 다시 적는다.
        val unconfirmed = (closed - stillClosed.toSet()).filterNot { confirmed(it) }
        attested.removeAll((closed - stillClosed.toSet() - unconfirmed.toSet()).toSet())
        return (waiting + still.map { it.jobOrderId }) to (stillClosed + unconfirmed).sortedBy { closed.indexOf(it) }.map { it.row.jobOrderId }
    }

    /** 증언 종결의 확인 행을 적는다. 적었으면 `true`, 못 적었으면 `false` 다(이 판정을 깨지 않는다). 호스트 잠금 아래에서 부른다. */
    private fun confirmed(entry: Attested): Boolean = try {
        records.confirmClose(entry.close.closeId, instanceId)
        true
    } catch (e: Exception) {
        log.warn("증언 종결의 확인 행을 적지 못했다, 다음 판정에 다시 한다: {} {}", entry.row.jobOrderId, e.toString())
        false
    }

    /**
     * 복원 못 한 실행의 운영자 종결(결함 14). 호스트 잠금 아래에서 판정하고, 일지 이벤트(CLOSED)·종결 행·상위로 가는 UNVERIFIED 결과(송신 기록과
     * 결과 아웃박스)를 한 트랜잭션으로 적은 뒤에만 메모리를 고친다. 적지 못하면 예외(`DataAccessException`)가 나가고 아무것도 바뀌지 않는다.
     * 종결은 그 실행을 멈추지 않고 성공을 추정하지 않는다 — 결과는 `UNVERIFIED`·운영자 필요·도달 근거 `E0`·완료 단위 없음이다.
     *
     * 순서(앞 판정이 이기고 적기 전까지는 아무것도 적지 않는다):
     * 1. 승인자가 사람이 아니면 [CloseResult.REFUSED]. REST 는 늘 사람 승인자를 만들므로 이 메서드를 직접 부를 때만 난다.
     * 2. 같은 요청 id 의 종결 행이 있으면 [CloseRequestReused].
     * 3. 일지 행이 없으면 [CloseResult.NOT_FOUND].
     * 4. 마지막이 종결이면 [CloseResult.ALREADY_CLOSED](앞선 종결을 싣는다).
     * 5. picasso 메모리에 그 작업 지시의 실행이 있거나(도는 실행) 마지막이 SETTLED 이면 [CloseResult.NOT_CLOSABLE].
     * 6. 대상. 종결하지 않은 포기 행([unclosed])이거나, 기체 스냅숏을 못 읽어 미룬 행(`RESUME_SNAPSHOT_UNREADABLE`). 살아 있는 다른 인스턴스가 든
     *    행([HELD_BY_LIVE_INSTANCE])과 그 밖의 행은 [CloseResult.NOT_CLOSABLE].
     * 7. [seen] 이 지금 일지의 (인스턴스, 실행)과 다르면 [CloseResult.TARGET_CHANGED].
     * 8. 정지 확인. 그 기체의 스냅숏에서 그 작업 지시의 종료하지 않은 태스크를 본다.
     *    - 읽히고 그런 태스크가 없다: 포기 행은 근거 `SNAPSHOT`. 미룬 행은 다음 pump 가 리빌드할 행이라 [CloseResult.NOT_CLOSABLE] 이다
     *      (닫으면 복원할 수 있는 실행을 버린다. 미룬 행은 스냅숏을 못 읽을 때 증언으로만 종결한다).
     *    - `RETRIABLE`·`NEEDS_INTERVENTION` 밖의 태스크가 있다: [CloseResult.TASK_LIVE]. 기체가 움직이거나 명령이 걸려 있으니 증언도 받지 않는다.
     *    - 스냅숏을 못 읽었거나 남은 태스크가 모두 사람을 기다리는 상태다: [attestedStopped] 가 거짓이면 [CloseResult.STOP_UNCONFIRMED], 참이면
     *      근거 `ATTESTED`.
     *
     * 포기 행([unclosed])도 행을 든 인스턴스(`previous.instanceId`, 이 인스턴스가 아님)가 살아 있으면 [CloseResult.NOT_CLOSABLE] 이다
     * ([HELD_BY_LIVE_INSTANCE]). 마지막 차례는 메모리가 아니라 DB 에서 읽고, 다른 인스턴스가 종결한 행은 이 인스턴스의 메모리에서도 뺀다([forgetClosed]).
     *
     * 이 요청 id 는 잠금을 기다리는 동안과 스냅숏을 읽는 동안에도 처리 중이다([CloseInProgress]). 미룬 행의 종결은 기체 스냅숏을 읽는 동안 호스트 잠금을
     * 쥔다 — 스냅숏 읽기가 오래 걸리면 그 동안 pump 와 다른 조작이 기다린다.
     *
     * 종결한 포기 행은 [gaveUp]·[unclosed] 에서, 미룬 행은 [deferred] 에서 뺀다(리빌드하지 않는다). `ATTESTED` 는 [attested] 에 넣어 스냅숏이 동의할 때까지
     * 그 기체를 판정에서 뺀다. 응답의 (인스턴스, 실행)은 종결한 실행(복원 행의 앞 인스턴스와 실행)이다 — `exec-N` 은 인스턴스마다 다시 세므로 지금
     * 인스턴스에 붙이면 지금의 다른 실행을 가리킬 수 있다.
     */
    fun closeUnrestored(
        jobOrderId: String, approver: Approver, reason: String, requestId: String, seen: Pair<String, String>, attestedStopped: Boolean,
    ): CloseView {
        if (approver.kind != ApproverKind.PERSON) {
            return CloseView(CloseResult.REFUSED, jobOrderId, null, null, "$AGENT_CLOSE_REFUSED: ${approver.id}", requestId, null)
        }
        val requestKey = UUID.fromString(requestId)
        // 잠금을 기다리는 동안과 스냅숏을 읽는 동안에도 이 요청 id 를 처리 중으로 둔다. 종결 조회가 «종결 없음» 이라고 답한 뒤에 종결이 커밋되면 안 된다.
        if (!closing.add(requestKey)) throw CloseRequestReused(requestId)
        try {
            return lock.withLock { closeLocked(jobOrderId, approver, reason, requestId, requestKey, seen, attestedStopped) }
        } finally {
            closing.remove(requestKey)
        }
    }

    private fun closeLocked(
        jobOrderId: String, approver: Approver, reason: String, requestId: String, requestKey: UUID, seen: Pair<String, String>, attestedStopped: Boolean,
    ): CloseView {
        fun answer(result: CloseResult, detail: String, closure: CloseRecordView? = null) =
            CloseView(result, jobOrderId, null, null, detail, requestId, closure)
        if (records.closeByRequest(requestKey) != null) throw CloseRequestReused(requestId)
        val journal = records.journalRow(jobOrderId) ?: return answer(CloseResult.NOT_FOUND, "해당 작업 지시의 일지 행이 없다")
        // 마지막 차례는 메모리가 아니라 DB 에서 읽는다. 다른 인스턴스가 종결했으면 이 인스턴스의 메모리는 그 행을 아직 미룬·포기 행으로 든다.
        val last = records.events(jobOrderId).lastOrNull { it.kind in TURNS }
        if (last?.kind == JournalEventKind.CLOSED) {
            val closed = records.closeOf(jobOrderId)
            closed?.let { forgetClosed(journal, it) }
            val closure = closed?.let(CloseRecordView::of)
            return answer(CloseResult.ALREADY_CLOSED, "이미 운영자 종결한 작업 지시다: ${closure?.approverId} ${closure?.recordedAt}", closure)
        }
        middleware.executions().firstOrNull { it.order.jobOrderId == jobOrderId }?.let {
            return answer(CloseResult.NOT_CLOSABLE, "진행 중인 실행이다: ${it.executionId}, 실행 취소를 사용한다")
        }
        if (last?.kind == JournalEventKind.SETTLED) return answer(CloseResult.NOT_CLOSABLE, "이미 끝난 작업 지시다")
        val waiting = deferred.firstOrNull { it.row.jobOrderId == jobOrderId }
        val abandoned = unclosed[jobOrderId]
        val restoreReason: String
        val previous: RestoredFromView
        if (abandoned != null) {
            restoreReason = abandoned.reason
            previous = abandoned.previous
            // 이전 기동이 읽지 못해 포기한 행도 살아 있는 다른 인스턴스가 든 채일 수 있다.
            liveHolder(previous)?.let { return answer(CloseResult.NOT_CLOSABLE, "$HELD_BY_LIVE_INSTANCE: instance=$it") }
        } else if (waiting != null) {
            val row = restoreRows[waiting.index]
            restoreReason = row.reason.orEmpty()
            if (waiting.heldBy != null || restoreReason.startsWith(HELD_BY_LIVE_INSTANCE)) return answer(CloseResult.NOT_CLOSABLE, restoreReason)
            if (!restoreReason.startsWith(Middleware.RESUME_SNAPSHOT_UNREADABLE)) {
                return answer(CloseResult.NOT_CLOSABLE, "운영자 종결할 수 없는 미룬 행이다: $restoreReason")
            }
            previous = RestoredFromView(row.previousInstanceId, row.previousExecutionId)
        } else {
            return answer(CloseResult.NOT_CLOSABLE, "복원 못 한 실행이 아니다")
        }
        if (seen.first != previous.instanceId || seen.second != previous.executionId) {
            return answer(CloseResult.TARGET_CHANGED, "본 실행이 지금 일지와 다르다: 지금은 ${previous.instanceId} ${previous.executionId}")
        }
        val snapshot = robots.snapshot(journal.robotId)
        val live = snapshot?.tasks.orEmpty().filter { (taskId, state) -> taskId.startsWith("$jobOrderId#") && state !in TERMINAL_TASK_STATES }
        val basis = when {
            snapshot != null && abandoned == null -> return answer(CloseResult.NOT_CLOSABLE, "스냅숏이 읽힌다. 다음 pump 가 리빌드한다")
            snapshot != null && live.isEmpty() -> CLOSE_BASIS_SNAPSHOT
            else -> {
                val moving = live.filter { it.value !in WAITING_TASK_STATES }
                if (moving.isNotEmpty()) {
                    return answer(CloseResult.TASK_LIVE, "기체가 해당 작업 지시의 태스크를 실행하고 있다: ${describe(moving)}")
                }
                if (!attestedStopped) {
                    val why = if (snapshot == null) "기체 스냅숏을 읽지 못했다" else "사람을 기다리는 태스크가 남았다: ${describe(live)}"
                    return answer(CloseResult.STOP_UNCONFIRMED, "$why. 로봇이 멈춘 것을 현장에서 확인해 증언해야 운영자 종결한다")
                }
                CLOSE_BASIS_ATTESTED
            }
        }
        val inputs = closeInputs(journal)
        val closed = records.inTransaction {
            val row = records.closeUnrestored(
                jobOrderId, instanceId, previous.instanceId, previous.executionId, journal.robotId,
                if (abandoned != null) RestoreResult.GAVE_UP.name else RestoreResult.DEFERRED.name, restoreReason, basis,
                approver.id, approver.kind.name, reason, requestKey,
            )
            val response = closeResponse(row, inputs)
            records.logResponse(previous.instanceId, row.responseId, previous.executionId, closeContent(journal, response), ResponseDisposition.SENT)
            records.appendResult(previous.instanceId, row.responseId, closeSnapshot(row, journal, response))
            row
        }
        // 커밋한 뒤에만 메모리를 고친다.
        forgetClosed(journal, closed)
        return CloseView(CloseResult.CLOSED, jobOrderId, basis, closed.responseId, null, requestId)
    }

    /**
     * 종결 행([closed])이 있는 작업 지시를 이 인스턴스의 메모리에서 뺀다. 이 인스턴스가 종결했을 때와 다른 인스턴스가 종결한 것을 본 때 둘 다 쓴다.
     * 종결한 행은 리빌드하지 않는다. `ATTESTED` 는 스냅숏이 동의할 때까지 그 기체를 판정에서 뺀다([attested]). 호스트 잠금 아래에서 부른다.
     */
    private fun forgetClosed(journal: JournalRow, closed: CloseRow) {
        val jobOrderId = journal.jobOrderId
        unclosed.remove(jobOrderId)
        gaveUp.removeAll { it.jobOrderId == jobOrderId }
        deferred.removeAll { it.row.jobOrderId == jobOrderId }
        if (closed.basis == CLOSE_BASIS_ATTESTED && attested.none { it.row.jobOrderId == jobOrderId }) attested += Attested(journal, closed)
        val index = restoreRows.indexOfFirst { it.jobOrderId == jobOrderId }
        if (index >= 0) restoreRows[index] = restoreRows[index].copy(close = CloseRecordView.of(closed))
    }

    /**
     * 그 작업 지시의 운영자 종결. 없으면 `null` 이다. DB 만 읽고 호스트 잠금을 잡지 않는다. [requestId] 를 주면 그 요청 id 의 종결을 아직 처리 중일 때
     * [CloseInProgress] 를 낸다. 처리 중 집합을 먼저 보고 DB 를 나중에 읽는다. 종결은 행을 커밋한 뒤에 집합에서 빠지므로 집합에 없고 DB 에도 없으면
     * 그 요청은 아직 오지 않았거나 행을 남기지 않고 끝난 것이다.
     */
    fun closure(jobOrderId: String, requestId: UUID? = null): CloseRecordView? {
        if (requestId != null && requestId in closing) throw CloseInProgress(requestId.toString())
        return records.closeOf(jobOrderId)?.let(CloseRecordView::of)
    }

    private fun describe(tasks: Map<String, TaskState>): String = tasks.entries.joinToString(", ") { "${it.key}=${it.value.name}" }

    /**
     * 실행 목록 페이지의 `unrestored`(결함 14). 행은 메모리에서 읽는다. 다만 종결하지 않은 포기 행([unclosed])은 행을 든 인스턴스의 심장박동을 행마다
     * DB 에서 읽는다(살아 있으면 `closable=false`). 포기 행은 재기동을 몇 번 넘어도 남고, 미룬 행과 증언으로 종결했으나 스냅숏이 아직 동의하지 않은
     * 행([attested])이 뒤따른다. 호스트 잠금 아래에서 부른다.
     */
    private fun unrestoredRows(): List<UnrestoredRowView> {
        val excluded = gaveUp.mapTo(HashSet()) { it.jobOrderId }
        val abandoned = unclosed.values.map {
            // 행을 든 인스턴스가 살아 있으면 닫을 수 없다([closeUnrestored]). 심장박동은 DB 에서 읽는다.
            val holder = liveHolder(it.previous)
            UnrestoredRowView(
                it.row.jobOrderId, it.row.robotId, it.previous.instanceId, it.previous.executionId, RestoreResult.GAVE_UP.name, it.reason, it.since,
                closable = holder == null, notClosableReason = holder?.let { id -> "$HELD_BY_LIVE_INSTANCE: instance=$id" }, close = null,
                robotExcluded = it.row.jobOrderId in excluded,
            )
        }
        val waiting = deferred.map {
            val row = restoreRows[it.index]
            val held = it.heldBy != null || row.reason?.startsWith(HELD_BY_LIVE_INSTANCE) == true
            UnrestoredRowView(
                row.jobOrderId, row.robotId, row.previousInstanceId, row.previousExecutionId, RestoreResult.DEFERRED.name, row.reason,
                restoredAt ?: clock.now(), closable = !held, notClosableReason = row.reason.takeIf { held }, close = null, robotExcluded = true,
            )
        }
        val closed = attested.map {
            UnrestoredRowView(
                it.row.jobOrderId, it.row.robotId, it.close.closedInstanceId, it.close.closedExecutionId, JournalEventKind.CLOSED.name, it.close.restoreReason,
                it.close.recordedAt, closable = false, notClosableReason = null, close = CloseRecordView.of(it.close), robotExcluded = true,
            )
        }
        return abandoned + waiting + closed
    }

    /** 종결 응답이 싣는 판·근거 등급·단위 id([closeInputs]). */
    private class CloseInputs(val version: Int, val requiredEvidence: String, val units: List<String>)

    /**
     * 종결 응답이 싣는 판·근거 등급·단위 id. 작업 지시의 JSON 글자는 `HostRequests.jobOrder` 를 거치지 않고 칸을 바로 읽는다 — 그 읽기가 실패해서
     * 포기한 행도 종결할 수 있어야 한다.
     *
     * 판: 받은 개정판의 가장 높은 판, 없으면 일지 행의 작업 지시 판, 없으면 마지막 송신 행의 판, 없으면 1. 요구 근거 등급도 같은 차례다(마지막은 `E0`).
     * 단위 id: 마지막 송신 행의 네 단위 목록의 합집합(상위가 이미 가진 것), 없으면 일지의 작업 지시를 읽을 수 있을 때 임무 계획의 단위, 그것도 못
     * 읽으면 비어 있다(검증이 빈 미완 단위를 받는다).
     */
    private fun closeInputs(journal: JournalRow): CloseInputs {
        val revision = records.latestRevision(journal.jobOrderId)
        val node = runCatching { json.readTree(revision?.jobOrder ?: journal.jobOrder) }.getOrNull()
        val last = records.lastResponse(journal.jobOrderId)
        val version = revision?.version
            ?: node?.get("version")?.takeIf { it.isIntegralNumber && it.canConvertToInt() && it.asInt() > 0 }?.asInt()
            ?: last?.content?.version
            ?: 1
        val evidence = node?.get("requiredEvidence")?.takeIf { it.isTextual && Evidence.entries.any { e -> e.name == it.asText() } }?.asText()
            ?: last?.content?.requiredEvidence
            ?: Evidence.E0.name
        val sent = last?.content?.let { (it.completedUnits + it.unverifiedUnits + it.incompleteUnits + it.inDoubtUnits).distinct().sorted() }.orEmpty()
        val units = sent.ifEmpty {
            runCatching {
                val order = HostRequests.jobOrder(checkNotNull(node))
                val mission = catalog.mission(journal.workMasterId, journal.missionVersion?.let { store.version(journal.workMasterId, it) ?: error("임무 버전 행이 없다") })
                mission.capability.plan(order).map { it.unitId }.distinct().sorted()
            }.getOrDefault(emptyList())
        }
        return CloseInputs(version, evidence, units)
    }

    /** 종결 응답. 성공을 추정하지 않는다: 완료·미확정·불확실 단위가 없고 모든 단위가 [UNRESTORED_CLOSED] 로 미완이며 도달 근거는 `E0` 이다. */
    private fun closeResponse(row: CloseRow, inputs: CloseInputs) = JobResponseView(
        jobResponseId = row.responseId,
        version = inputs.version,
        physicalState = PhysicalState.UNVERIFIED.name,
        requiredEvidence = inputs.requiredEvidence,
        reachedEvidence = Evidence.E0.name,
        completedUnits = emptyList(),
        unverifiedUnits = emptyList(),
        incompleteUnits = inputs.units.associateWith { UNRESTORED_CLOSED },
        inDoubtUnits = emptyList(),
        operatorRequired = true,
        residualHold = HoldKind.HOLD_KIND_UNSPECIFIED.name,
        blockedBy = emptyList(),
        connection = "CONNECTION_STATE_UNSPECIFIED",
    )

    /** 송신 기록에 적는 내용 키. [contentOf] 가 짓는 것과 같은 모양이다(단위 id 는 정렬돼 있다). */
    private fun closeContent(journal: JournalRow, response: JobResponseView) = ResponseContent(
        jobOrderId = journal.jobOrderId,
        version = response.version,
        physicalState = response.physicalState,
        requiredEvidence = response.requiredEvidence,
        reachedEvidence = response.reachedEvidence,
        completedUnits = emptyList(),
        unverifiedUnits = emptyList(),
        incompleteUnits = response.incompleteUnits.keys.sorted(),
        inDoubtUnits = emptyList(),
        operatorRequired = response.operatorRequired,
        residualHold = response.residualHold,
        blockedBy = emptyList(),
    )

    /**
     * 결과 아웃박스에 넣는 본문. [logResponse] 가 짓는 것과 같은 모양이고 `instanceId`·`executionId` 는 종결한 쌍(앞 인스턴스의 것)이다. 최상위 `closure`
     * 는 운영 서비스가 이 결과를 «운영자가 종결한 실행» 으로 알아보는 표시이고 근거만 싣는다(승인자와 사유는 종결 행과 운영 기록에 있다).
     */
    private fun closeSnapshot(row: CloseRow, journal: JournalRow, response: JobResponseView) = json.createObjectNode().apply {
        put("instanceId", row.closedInstanceId); put("executionId", row.closedExecutionId)
        put("jobOrderId", journal.jobOrderId); put("robotId", journal.robotId)
        put("workMasterId", journal.workMasterId)
        if (journal.missionVersion == null) putNull("missionVersion") else put("missionVersion", journal.missionVersion)
        put("physicalState", response.physicalState)
        set<com.fasterxml.jackson.databind.JsonNode>("jobResponse", json.valueToTree(response))
        set<com.fasterxml.jackson.databind.JsonNode>("closure", json.createObjectNode().put("kind", UNRESTORED_CLOSED).put("basis", row.basis))
    }

    private fun contentOf(response: JobResponse) = ResponseContent(
        jobOrderId = response.jobOrderId,
        version = response.version,
        physicalState = response.physicalState.name,
        requiredEvidence = response.requiredEvidence.name,
        reachedEvidence = response.reachedEvidence.name,
        completedUnits = response.completedUnits.sorted(),
        unverifiedUnits = response.unverifiedUnits.sorted(),
        incompleteUnits = response.incompleteUnits.keys.sorted(),
        inDoubtUnits = response.inDoubtUnits.sorted(),
        operatorRequired = response.operatorRequired,
        residualHold = response.residualHold.kind.name,
        blockedBy = response.blockedBy.sorted(),
    )

    private fun view(response: JobResponse) = JobResponseView(
        jobResponseId = response.jobResponseId,
        version = response.version,
        physicalState = response.physicalState.name,
        requiredEvidence = response.requiredEvidence.name,
        reachedEvidence = response.reachedEvidence.name,
        completedUnits = response.completedUnits,
        unverifiedUnits = response.unverifiedUnits,
        incompleteUnits = response.incompleteUnits,
        inDoubtUnits = response.inDoubtUnits,
        operatorRequired = response.operatorRequired,
        residualHold = response.residualHold.kind.name,
        blockedBy = response.blockedBy,
        connection = response.connection,
    )

    /**
     * pump 와 심장박동을 멈추고 멈춤을 적는다. 다음 기동은 이 인스턴스의 행을 만료를 기다리지 않고 가져간다. 멈춤을 못 적으면(DB
     * 실패) 다음 기동은 만료 폭만큼 그 행을 미룬다.
     */
    override fun close() {
        pumper.shutdownNow()
        beater.shutdownNow()
        pumper.awaitTermination(2, TimeUnit.SECONDS)
        beater.awaitTermination(2, TimeUnit.SECONDS)
        try {
            records.stopInstance(instanceId)
        } catch (e: Exception) {
            log.warn("인스턴스 멈춤을 적지 못했다: {}", e.toString())
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MissionHost::class.java)

        /** pump 주기(T5). 셀 대역이 채운 슬롯을 E2 마감(15초)보다 훨씬 짧게 다시 읽는다. */
        val PUMP_PERIOD: Duration = Duration.ofMillis(250)

        /**
         * `FAILED`·`UNVERIFIED` 로 정착한 실행을 놓기 전에 두는 폭의 기본값(`host.settled.revisable-retention`). 그 안에서는 같은 작업 지시 id
         * 의 개정판을 받는다. 운영 서비스는 작업 지시 id 를 다시 쓰지 않으므로 개정판은 호스트 REST 를 직접 부르는 쪽만 낸다.
         */
        val DEFAULT_REVISABLE_RETENTION: Duration = Duration.ofMinutes(5)

        /** 아직 낼 것이 있거나 도는 중이거나 사람을 기다리는 단위 상태. 하나라도 있으면 순회는 종결한 것이 아니다(picasso 와 같은 집합). */
        private val OPEN_UNIT_STATES =
            setOf(UnitState.PENDING, UnitState.RUNNING, UnitState.VERIFYING, UnitState.IN_DOUBT, UnitState.OPERATOR_HOLD)

        /** 개정판으로 다시 돌 수 있어 [DEFAULT_REVISABLE_RETENTION] 동안 두는 정착 상태. `PARTIAL` 은 구동이 계속 돌려 놓지 않는다. */
        private val REVISABLE = setOf(PhysicalState.FAILED, PhysicalState.UNVERIFIED)

        /**
         * 미들웨어에 실행이 없는데 실행 일지에 정착 없이 있는 작업 지시를 다시 낸 REJECTED 사유의 앞부분(미뤘거나 포기한 일지 행).
         * 뒤에 `: 작업 지시 id · 받은 인스턴스 실행 id` 가 붙는다.
         */
        const val JOURNALED_JOB_ORDER = "실행 일지에 이미 있는 작업 지시라 새 실행을 짓지 않는다"

        /** 철회된 작업 지시([withdraw], B3)의 제출·값 없는 승인 거부 사유의 앞부분. 작업 지시 id, 철회한 쪽과 시각, 까닭이 뒤따른다. 다시 할 일은 새 작업 지시 id 로 낸다. */
        const val WITHDRAWN_JOB_ORDER = "철회된 작업 지시다. 실행을 짓지 않는다"

        /**
         * 정착을 [recordedAt] 에 적은 [state] 의 실행을 [now] 에 놓을 때가 되었는가. `PHYSICALLY_DONE`·`ABORTED` 는 곧이고, `FAILED`·`UNVERIFIED` 는
         * [retention] 이 지난 뒤다. 구동이 아직 돌리는 상태(`PARTIAL` 포함)는 놓지 않는다.
         */
        internal fun forgetDue(state: PhysicalState, recordedAt: Instant, now: Instant, retention: Duration): Boolean = when {
            state.pumps -> false
            state in REVISABLE -> !now.isBefore(recordedAt.plus(retention))
            else -> true
        }

        /** 실행 id 의 생성 차례. picasso 는 `exec-N` 의 N 을 인스턴스 안에서 늘리기만 하고 다시 쓰지 않는다(`Middleware.forget`). */
        internal fun creationOrder(executionId: String): Long = executionId.substringAfterLast('-').toLongOrNull() ?: Long.MAX_VALUE

        /** 현장 시간값 미적용 동안 판정이 기체마다 더하는 이유(S3c 스펙 T8). 화면에 그대로 보인다. */
        const val UNAPPLIED_REASON = "현장 시간값 미적용: 실행 호스트가 현장 설정을 아직 읽지 못했다"

        /**
         * 살아 있는 다른 인스턴스가 든 일지 행을 미룬 DEFERRED 사유의 앞부분(HA 설계 기록 §7 단계 0). 뒤에 `: instance=<id>` 가 붙는다.
         * 그 인스턴스의 심장박동이 끊기면 pump 가 다시 짓는다.
         */
        const val HELD_BY_LIVE_INSTANCE = "살아 있는 다른 인스턴스가 든 실행이라 리빌드하지 않는다"

        /** 판단 기록을 못 읽은 GAVE_UP 사유의 앞부분. 뒤에 `: record=<기록 행 번호>, <picasso 의 읽기 거부 문장>` 이 붙는다. */
        const val RECORD_UNREADABLE = "판단 기록을 읽지 못해 리빌드하지 않는다"

        /** 다시 짓지 못한 실행이 있는 기체에 판정이 더하는 이유의 앞부분(S4b 스펙 T4). 뒤에 작업 지시 id 가 붙는다. */
        const val UNRESTORED_REASON = "복원 못 한 실행이 있다"

        /**
         * 정지 증언으로 종결한 실행(`ATTESTED`)이 있는 기체에 판정이 더하는 이유의 앞부분. 뒤에 작업 지시 id 가 `, ` 로 붙는다. 그 기체의 스냅숏이
         * 그 작업 지시의 종료하지 않은 태스크가 없다고 동의할 때까지 이 이유가 붙는다.
         */
        const val CLOSED_UNCONFIRMED_REASON = "정지 증언으로 종결하고 스냅숏 확인 전인 실행이 있다"

        /**
         * 미해소 잔여 파지 표시가 있는 기체에 판정이 더하는 이유의 앞부분(P0-b2). 뒤에 `작업 지시 id 단위 id` 가 표시마다 `, ` 로 붙는다.
         * 복원 못 한 실행 다음, 미적용 앞자리다.
         */
        const val RESIDUAL_HOLD_REASON = "잔여 파지 확인 필요"

        /** 에이전트 승인자의 취소·해소 거부 사유의 앞부분. 뒤에 승인자 id 가 붙는다. */
        const val AGENT_CANCEL_REFUSED = "에이전트는 실행을 취소하지 못한다. 실행 중단은 사람의 것이다"
        /** 빈손 확인 관측의 종류 이름(V15 `observed_kind`). */
        const val HAND_HOLDING = "HOLDING"
        const val HAND_UNREADABLE = "UNREADABLE"

        const val AGENT_CLEAR_REFUSED = "에이전트는 잔여 파지 표시를 해소하지 못한다. 기체가 빈손인지는 사람이 현장에서 확인한다"
        const val AGENT_RELEASE_REFUSED = "에이전트는 결함을 수용하지 못한다. 결함을 안고 계속할지는 사람이 현장에서 정한다"

        /** 결함 감수 NOT_BLOCKED·RELEASED 의 작업 허가 사유 앞부분. 뒤에 `: 막힌 단위 (막은 명령)` 이 붙는다. 허가는 감수로 풀리지 않는다. */
        const val PERMIT_WAIT = "작업 허가 대기 중이다. 수용으로 풀리지 않는다"

        /** 결함 감수 NOT_BLOCKED 의 정착 사유 앞부분. 뒤에 정착 상태가 붙는다. */
        const val SETTLED_NOT_BLOCKED = "종결한 실행이다"

        /** 결함 감수 NOT_BLOCKED 의 사유. 도는 실행에 막는 결함도 작업 허가 막음도 없다. */
        const val NO_BLOCKING_FAULT = "막는 결함이 없다"

        /** 결함 감수 BLOCK_CHANGED 의 사유 앞부분. 뒤에 지금 막는 결함(`오류 종류[참조]`)이 `, ` 로 붙는다. */
        const val BLOCK_CHANGED_DETAIL = "본 결함과 지금 막는 결함이 다르다. 다시 읽고 판단한다"

        /** 기동 복원의 대상과 판정 제외를 정하는 일지 이벤트([HostRecords.openJournal]). 그 가운데 마지막이 정한다. */
        private val TURNS = setOf(JournalEventKind.SETTLED, JournalEventKind.REOPENED, JournalEventKind.GAVE_UP, JournalEventKind.CLOSED)

        /** 정지 확인에서 사람을 기다리는 태스크 상태. 기체가 움직이지 않는다. 이 밖의 종료하지 않은 태스크는 기체가 움직이거나 명령이 걸린 것이다(`TASK_LIVE`). */
        private val WAITING_TASK_STATES = setOf(TaskState.TASK_STATE_RETRIABLE, TaskState.TASK_STATE_NEEDS_INTERVENTION)

        /** 종결 행의 근거. 스냅숏이 정지를 보였거나(`SNAPSHOT`) 운영자가 로봇이 멈춘 것을 증언했다(`ATTESTED`). */
        const val CLOSE_BASIS_SNAPSHOT = "SNAPSHOT"
        const val CLOSE_BASIS_ATTESTED = "ATTESTED"

        /**
         * 종결 응답이 미완 단위마다 싣는 사유 코드. picasso 실패 분류와 같은 모양의 코드이고 화면에 보이는 문구가 아니다. 글자를 바꾸면 그것을 읽는
         * 쪽이 같이 바뀌어야 한다.
         */
        const val UNRESTORED_CLOSED = "UNRESTORED_CLOSED"

        /** 에이전트 승인자의 종결 거부 사유의 앞부분. 뒤에 승인자 id 가 붙는다. */
        const val AGENT_CLOSE_REFUSED = "에이전트는 복원 못 한 실행을 종결하지 못한다. 로봇이 멈췄는지는 사람이 현장에서 확인한다"

        /** 종료 상태(용어집 태스크 수명주기). 사람을 기다리는 RETRIABLE·NEEDS_INTERVENTION 은 종료하지 않은 상태라 기체를 계속 뺀다. */
        private val TERMINAL_TASK_STATES = setOf(
            TaskState.TASK_STATE_SUCCEEDED, TaskState.TASK_STATE_FAILED, TaskState.TASK_STATE_CANCELLED,
            TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED,
        )

        /**
         * 정착한 실행의 잔여 파지 단위(P0-b2). 경로가 로봇인 단위 가운데 파지 관측이 있는 마지막 단위(계획 순서)의 관측이 HOLDING 이면
         * 그 단위다. 플릿 단위의 HOLDING 은 플릿 AMR 이 용기를 든 것이라 보지 않는다. picasso 는 정착한 실행의 파지를 정보로만 두고 게이트에
         * 쓰지 않는다(§15.146). 이 표시는 실행 호스트의 운영 정책이다(ADR 41).
         */
        internal fun residualHoldUnit(units: List<ExecutionUnit>): ExecutionUnit? =
            units.filter { it.route == Route.ROBOT }
                .lastOrNull { it.hold.kind != HoldKind.HOLD_KIND_UNSPECIFIED }
                ?.takeIf { it.hold.kind == HoldKind.HOLD_KIND_HOLDING }

        /**
         * 실행 id → 아웃박스에서 그 실행의 마지막 응답. `GET /host/executions` 가 실행마다 아웃박스를 다시 훑지 않게 한 번에 모은다
         * (HA 설계 기록 §10). 같은 실행의 응답이 여럿이면 뒤에 적힌 것이 남는다.
         */
        internal fun latestResponses(responses: List<JobResponse>): Map<String, JobResponse> = responses.associateBy { it.executionId }

        /** 운영자 판단의 결과 이름. picasso `ResolveOutcome` 의 이름 그대로다(S4a 스펙 T6). */
        const val RESOLVED = "Resolved"
        const val NOT_HELD = "NotHeld"
        const val REFUSED = "Refused"

        /**
         * 판단 결과 NotHeld 의 `detail` 에 싣는 고정 코드. 그 실행이 picasso 메모리에 없고 이 인스턴스의 종결 기록이 있다, 곧 이 인스턴스가
         * 종결 뒤 이빅션한 실행이라는 뜻이다. 건너뛴 대상 재작업은 이빅션 뒤에는 받지 않으므로 화면이 «보존 기간이 지났다» 와 «재작업할 수
         * 없는 대상» 을 이 코드로 가른다. 화면에 그대로 보이는 문구가 아니라 코드이며, 글자를 바꾸면 그것을 읽는 쪽이 같이 바뀌어야 한다.
         */
        const val EVICTED = "EVICTED"

        /** 받는 WorkMaster. DeliverContainer 는 플릿 포트 구현이 없어 받지 않는다(스펙 §1). */
        val WORK_MASTERS: Set<String> = setOf(InspectAsset.WORK_MASTER, PrepareSequencedRack.WORK_MASTER)
    }
}
