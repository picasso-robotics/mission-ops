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
import dev.picasso.middleware.AmrFleetPort
import dev.picasso.middleware.ApprovalAttempt
import dev.picasso.middleware.ApprovalOutcome
import dev.picasso.middleware.ApprovalRefusal
import dev.picasso.middleware.Approver
import dev.picasso.middleware.DeliverContainer
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.CopyAck
import dev.picasso.middleware.CopyAckState
import dev.picasso.middleware.Entitlements
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.ExecutionUnit
import dev.picasso.middleware.ForgetOutcome
import dev.picasso.middleware.ForgottenOrdersState
import dev.picasso.middleware.ForgottenOverflow
import dev.picasso.middleware.IncidentBundle
import dev.picasso.middleware.IncidentDecision
import dev.picasso.middleware.InspectAsset
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.JobResponse
import dev.picasso.middleware.CommandNotSent
import dev.picasso.middleware.JudgmentRecord
import dev.picasso.middleware.JudgmentRecordWire
import dev.picasso.middleware.RobotSnapshot
import dev.picasso.middleware.LedgerExport
import dev.picasso.middleware.LogicalCapability
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.OperatorDecision
import dev.picasso.middleware.PhysicalState
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.ProposalTarget
import dev.picasso.middleware.RemedyOutcome
import dev.picasso.middleware.RemedySearchRecord
import dev.picasso.middleware.Route
import dev.picasso.middleware.ResolveOutcome
import dev.picasso.middleware.Retention
import dev.picasso.middleware.RemovedUnit
import dev.picasso.middleware.RevisionRemoval
import dev.picasso.middleware.RetentionEvictions
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
import dev.picasso.ops.host.lease.Acquired
import dev.picasso.ops.host.lease.GatedFleetPort
import dev.picasso.ops.host.lease.GatedRobotPort
import dev.picasso.ops.host.lease.HostLeadership
import dev.picasso.ops.host.lease.LeaderState
import dev.picasso.ops.host.lease.LeadershipListener
import dev.picasso.ops.host.lease.ObservingRobotPort
import dev.picasso.ops.host.lease.RestartHolds
import dev.picasso.ops.host.lease.Requirement
import dev.picasso.ops.host.lease.SendAuthority
import dev.picasso.ops.host.mission.MockRunner
import dev.picasso.middleware.mission.MissionDefinitionParser
import dev.picasso.middleware.mission.MissionParse
import dev.picasso.ops.host.mission.SiteInputs
import dev.picasso.ops.host.mission.StoredMissionCatalog
import dev.picasso.ops.host.permit.CellWorkPermits
import dev.picasso.ops.host.permit.PermitBlockView
import dev.picasso.ops.host.permit.PermitMappingView
import dev.picasso.ops.host.permit.RobotPermitView
import dev.picasso.ops.host.permit.WorkPermitsView
import dev.picasso.ops.host.web.BadRequest
import dev.picasso.ops.host.web.HostRequests
import dev.picasso.ops.host.store.RestartSuspicionWrite
import dev.picasso.ops.host.store.CancelRow
import dev.picasso.ops.host.store.CloseRow
import dev.picasso.ops.host.store.ApprovalFactWrite
import dev.picasso.ops.host.store.CopyResolutionRow
import dev.picasso.ops.host.store.FaultReleaseRow
import dev.picasso.ops.host.store.HoldObservation
import dev.picasso.ops.host.store.AuthorityLost
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.ops.host.store.NotAuthority
import dev.picasso.ops.host.store.IncidentCopyRow
import dev.picasso.ops.host.store.IncidentRevisionWrite
import dev.picasso.ops.host.store.SearchCopyWrite
import dev.picasso.ops.host.store.RemovalCopyRow
import dev.picasso.ops.host.store.RemovalCopyWrite
import dev.picasso.ops.host.store.RemovedUnitRow
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
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 출발 관문 앞에 설 수 있는 단위 상태(`MissionHost.startingRobots`). */
private val STARTING_UNIT_STATES = setOf(UnitState.PENDING, UnitState.IN_DOUBT, UnitState.OPERATOR_HOLD)

/**
 * 스킬 적합 판정(S3a 스펙 §7.6). [UNKNOWN] 은 기체 케이퍼빌리티를 못 물어봤다는 뜻이고 적합이 아니다. [NOT_APPLICABLE] 은 플릿 전용 계획
 * (로봇 단위 없음, [MissionHost.fleetOnly])이라 기체가 할 스킬이 없다는 뜻이다 — 적합이 아니고(«아무 기체» 를 고르는 근거가 되지 않는다) 거절
 * 사유도 아니다. 그 기체는 실행 주체가 아니라 picasso 접수가 요구해 묶인 기체다(picasso 한계 «§15.326 · 기체 묶임»).
 */
enum class SkillFit { FIT, MISSING, UNKNOWN, NOT_APPLICABLE }

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
 *   플릿 전용 계획에는 쓰지 않는다(로봇 단위가 없어 사슬 검사가 없다).
 * @param boundRobot 플릿 전용 계획이다 — 이 기체는 **묶인 기체(실행 주체 아님)** 다. picasso 접수가 기체 하나를 요구해 실행·인시던트의
 *   `robotId` 에 실릴 뿐 운반은 플릿이 한다(picasso 한계 «§15.326 · 기체 묶임»). 그때 [skillFit] 은 [SkillFit.NOT_APPLICABLE] 이다.
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
    val boundRobot: Boolean = false,
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
 * @param revisionRemoval 이 제출이 받은 리비전이 작업 지시에서 항목을 빼 손대지 않은 단위를 지웠으면 그 사건(picasso §15.329). 개정판 행·판단
 *   기록과 같은 트랜잭션으로 커밋한 뒤에 싣는다. 빼는 것이 없었거나 리비전이 아니면 `null` 이다. 손댄 단위를 빼는 리비전은 REJECTED 이고 사유가
 *   [rejectionReason] 에 picasso 문장 그대로 있다 — 아무것도 저장하지 않는다
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
    val boundRobot: Boolean = false,
    val revisionRemoval: RevisionRemovalView? = null,
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

/**
 * 실행의 단위 하나. [reached] 는 단위가 이른 근거 등급이다.
 *
 * @param attempt 단위의 지금 시도(picasso `ExecutionUnit.attempt`). 이 칸이 생기기 전에 적은 정착 본문은 `null` 이다
 * @param route 단위의 경로(picasso `Route` 이름: `ROBOT`·`FLEET`·`SIGNAL`). 이 칸이 생기기 전에 적은 정착 본문에는 없어 화면이 «기록 없음» 으로
 *   보인다 — 스킬 이름 따위로 추정하지 않는다
 * @param decision 그 단위의 판단 기록 판단(picasso `JudgmentRecord` 의 단위 `decision`, 가장 최근 하나). 운영 서비스의 판단 재조회가 대상·시도·판단으로
 *   대조하는 자리다(§15.323). 판단이 없거나 이 칸이 생기기 전의 본문이면 `null` 이다
 */
data class UnitView(
    val unitId: String,
    val skillType: String,
    val state: String,
    val reached: String,
    val attempt: Int? = null,
    val decision: UnitDecisionView? = null,
    val route: String? = null,
)

/**
 * 판단 기록의 단위 판단 하나(picasso `RecordedDecision`). [at] 은 미들웨어 시각(호스트 시계)이고 [attempt] 는 판단이 선 시도다 — 재작업이면 단위의
 * 시도는 그보다 하나 크다. 재작업 근거(`reworkBasis`)와 사건 정체성(`incident`)은 싣지 않는다(인시던트 판단 칸이 근거를 든다).
 */
data class UnitDecisionView(val decision: String, val approverId: String, val approverKind: String, val at: Instant, val attempt: Int)

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
 * @param boundRobot 로봇 단위 없이 플릿 운반만 든 실행이다 — [robotId] 는 **묶인 기체(실행 주체 아님)** 다(picasso 한계 «§15.326 · 기체 묶임»).
 *   이 칸이 생기기 전에 적은 정착 본문에는 없다
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
    val boundRobot: Boolean = false,
    val revisionRemovals: List<RevisionRemovalView> = emptyList(),
)

/** 리비전이 뺀 단위 하나와 뺄 때의 상태(picasso `RemovedUnit`). 상태는 `PENDING` 이거나 태스크 없는 계획 실패 `FAILED` 다. */
data class RemovedUnitView(val unitId: String, val state: String)

/**
 * 리비전 제거 사건 하나(picasso §15.329 `RevisionRemoval`, V18 `mission.revision_removal`). 리비전 [toVersion] 이 작업 지시에서 항목을 빼며 손대지 않은
 * 단위([removed])를 실행에서 지웠다. **실패·취소·완료가 아니다** — 그 단위는 발행되지 않고 작업 응답의 완료·미완료 목록에서 빠진다(«리비전 N 이
 * 범위에서 뺌»). [instanceId]·[executionId] 는 그 리비전을 받은 인스턴스와 실행이다 — 재기동 뒤 다시 지은 실행에는 앞 인스턴스의 사건이 그대로
 * 실린다. [at] 은 미들웨어 시각(호스트 시계), [wallClockAt] 은 실제 시각이다. [fromVersion] 은 지우기 전 작업 지시 버전이다(건너뛰면 차가 1 이 아니다).
 */
data class RevisionRemovalView(
    val instanceId: String,
    val executionId: String,
    val fromVersion: Int,
    val toVersion: Int,
    val removed: List<RemovedUnitView>,
    val at: Instant,
    val wallClockAt: Instant,
) {
    companion object {
        fun of(row: RemovalCopyRow) = RevisionRemovalView(
            row.instanceId, row.executionId, row.fromVersion, row.toVersion, row.removed.map { RemovedUnitView(it.unitId, it.state) }, row.at, row.wallClockAt,
        )

        /** 미들웨어의 사건에서. 시각은 사본 표(`timestamptz`)와 같은 마이크로초로 자른다 — 제출 결과와 실행 본문이 같은 값을 싣는다. */
        fun of(instanceId: String, removal: RevisionRemoval) = RevisionRemovalView(
            instanceId, removal.executionId, removal.fromVersion, removal.toVersion, removal.removed.map { RemovedUnitView(it.unitId, it.state.name) },
            removal.at.truncatedTo(ChronoUnit.MICROS), removal.wallClockAt.truncatedTo(ChronoUnit.MICROS),
        )
    }
}

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
 * 놓은 작업 지시 표시가 상한(`Retention.forgottenOrders`, picasso 기본 100,000)에 차면 오래된 표시부터 내리라고 선언한다
 * ([RETENTION], `ForgottenOverflow.EVICT`, picasso §15.324). 선언하지 않으면 picasso 기본(`REFUSE`)이 그 뒤의 놓기를 모두 거부해
 * [forgetSettled] 가 pump 마다 거절 경고를 쌓고 picasso 실행 표와 호스트 쪽 맵이 상한을 넘어 계속 는다. 이 선언은 약속이다 — 내린 표시의
 * 작업 지시 id 는 picasso 가 더는 거부하지 않으므로, 같은 id 의 접수([submit])와 값 없는 승인([attemptApproval])을 picasso 를 부르기 전에
 * 실행 일지([journalRefusal])가 막는다(재기동 전후 같은 확인). 다른 상한은 picasso 기본 그대로다.
 * picasso `Middleware.releaseForgotten` 은 부르지 않는다 — 남은 최근 표시가 일지 다음의 둘째 방어선이라 놓기마다 지우면 그 방어선을 버린다.
 *
 * ## 판단 기록(picasso 한계 §15.221 · 판단 기록 영속)
 *
 * 미들웨어의 판단 기록(`Middleware.judgmentRecord`)을 `mission.execution_judgment_record` 에 덧붙인다. 받는 때는 수락 직후, pump 뒤
 * 기록(판정·검증 결과·정착, 송신 기록과 한 트랜잭션), 운영자 판단 직후다. 직전에 적은 글자와 같으면 적지 않는다. 기동 복원은 그
 * 작업 지시의 가장 최근 기록을 `resume` 에 넘긴다. 못 읽는 기록과 picasso 가 거부한 기록(`RESUME_RECORD_MISMATCH`)은 GAVE_UP 으로
 * 보고하고 기록 없이 다시 짓지 않는다. 결함 감수([release])는 감수 직후 기록을 적어 `acknowledgedFaults` 칸이 재기동을 넘는다. 이
 * 호스트는 사람이 값을 넣는 조치 승인(`approveRemedy`)을 부르지 않으므로 `approval` 칸은 값 없는 승인([attemptApproval])으로 선 실행에만
 * 있고, 그 기록은 실행 일지·같은 조치 승인 사실과 한 트랜잭션으로 적는다([journalApproval]). 기록이 들고 오면 그대로 넘긴다.
 *
 * ## 두 대장의 사본(picasso §15.166·§15.327, V17)
 *
 * 인시던트 대장과 조치 탐색 대장의 영속 소유자는 이 호스트다 — 보관·조회이고 picasso 로 되돌려 넣는 복원 입구는 없다. [RETENTION] 이 사본을
 * 선언하고, pump 뒤 기록([recordAfterPump])이 줄을 picasso `LedgerExport` 글자 그대로 사본 표에 적어 커밋한 뒤 확인한다. 같은 조치의 승인 횟수의
 * 정본은 승인 사실 표의 합산이다([repeatedRemedies]).
 *
 * ## 리스와 리더 상태(HA 설계 기록 §4·§7, 능동-대기 1단계 기반)
 *
 * [leadership] 이 `mission.host_lease` 를 쥔다. 기동은 심장박동(`mission.host_instance`, 진단용)과 리스 획득 시도만 하고, 복원과 pump 는 리스를
 * 얻은 뒤에 시작한다([acquired]): 카탈로그·일지·잔여 파지를 DB 에서 다시 읽고 기존 복원을 그대로 다시 쓴다. 복원 권한의 조건은 리스 보유다 — 리스를
 * 쥔 호스트는 앞 인스턴스의 심장박동과 관계없이 도는 일지 행을 다시 짓는다(살아 있는 인스턴스의 행을 미루던 단계 0 의 DEFERRED 는 더 생기지
 * 않는다). 앞 세대가 발행을 열었고(리스 기간 행의 발행 표시) 앞 보유자가 정상 종료(멈춤)를 적었으면 곧바로 발행하고([LeaderState.ISSUING]),
 * 그 밖(만료 인수, 관측 중에 정상 종료한 보유자, 조회로 복구한 획득, 다른 인스턴스가 살아 있는 첫 획득)은 다시 붙어 관측만 하며 운영자 확인 뒤에
 * 신규 발행을 연다([LeaderState.HOLDING], [Acquired.graceful]).
 *
 * 기체·플릿 명령은 미들웨어에 넘기는 포트에서 송신 직전에 리더를 확인한다([GatedRobotPort]·[GatedFleetPort]). DB 쓰기는 기록 저장소의 리스 가드가
 * 막는다.
 *
 * ## 수신 측 세대 대조 소비(HA 3단계, picasso ADR 54)
 *
 * 관측([LeaderState.HOLDING]) 안에서 기체마다 준비를 본다([HostLeadership.observeFresh]). 미리 읽기(잠금 밖)가 준비를 보인 미준비 기체만 pump 가
 * 그 라운드에 **지금** 읽게 하고([HostRobotPort.readNowFor]·[HostRobotPort.onFreshRead], 대조 안 함·휘발 기체는 이번 관측의 첫 관측과 그 뒤 기체별 간격(`host.readiness.unread-retry`)마다 한 번 말고는 잠금 아래에서 읽지 않음 — [HostRobotPort.unreadFallback]), 그 스냅숏의 응답 바닥값이 이 세대와 같고 `DURABLE_CONFIRMED` 면 그 라운드의
 * 재대조(같은 스냅숏으로 동기화한 `Middleware.pump`)가 끝난 뒤 자동 확인 근거(`mission.host_readiness_evidence`, 리스 가드 안)를 적고 그 기체를
 * 연다([publishReadiness], 이 세대 안 래치). 미들웨어 발행 관문의 권위 입력([HostLeadership.authorityPort])은 «실행 → 필수 기체·플릿» 표
 * ([refreshRequirements])로 기체 R 이 든 실행들의 요구가 모두 준비일 때만 쥐었다고 답한다 — 준비되지 않은 기체의 단위는 `AUTHORITY_NOT_HELD`
 * 로 막히고 까닭이 화면에 보인다. 전역 발행·발행 표시·변경 HTTP·값 없는 승인·셀 쓰기는 그대로 운영자 확인 뒤다. 리스를 잃으면 pump 와 심장박동을 먼저 멈추고 자기 차단을 (제한 시간 안에) 적는다([lost]). 닫을 때는 리더를 정상 종료 중으로 바꾸고
 * pump 와 송신 중인 명령이 끝난 것을 본 뒤에만 멈춤을 적으며, 그것을 적었을 때만 리스를 내놓는다([close]).
 *
 * @param robots 하위 포트. 판정의 케이퍼빌리티도 이것으로 묻는다(`PicassoClient` 가 세대별로 캐시한다). 그 캐시는 잠금 밖에서
 *   안전하지 않으므로 케이퍼빌리티는 늘 [lock] 아래에서 묻는다.
 * @param catalog 임무 카탈로그(S3b 스펙 T1). 미들웨어에 넘긴 것과 같은 참조로 스킬 적합을 판정한다. 기동 때 DB 의 활성 버전으로
 *   세운 것을 받는다.
 * @param siteTimings 현장 시간값(S3c 스펙 §7.1). 미들웨어에 그대로 넘긴다. `null` 을 주는 동안은 미적용이라 작업 지시를 받지 않는다.
 *   모의 실행의 별도 미들웨어에는 주지 않는다(T10).
 * @param revisableRetention `FAILED`·`UNVERIFIED` 로 정착한 실행을 정착 기록 뒤 picasso 메모리에 두는 폭(호스트 시계,
 *   `host.settled.revisable-retention`). 그 안에서는 같은 작업 지시 id 의 개정판을 받는다. 지나면 놓고 그 작업 지시는 다시 받지 않는다
 * @param retention 미들웨어에 넘기는 보존 상한(picasso `Retention`). 기본은 [RETENTION] 이다. 운영 설정으로 열지 않는다 — 시험이 작은
 *   상한을 넣는 칸이다([MissionHostApplication.builder]). 넘침 선언을 바꾸면 위 «놓기» 의 약속이 깨진다.
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
    private val leadership: HostLeadership,
    private val revisableRetention: Duration = DEFAULT_REVISABLE_RETENTION,
    private val retirements: RetirementPort? = null,
    private val entitlements: Entitlements = Entitlements.None,
    export: Path? = null,
    retention: Retention = RETENTION,
    fleet: AmrFleetPort = AmrFleetPort.None,
) : AutoCloseable, LeadershipListener {
    init {
        require(!revisableRetention.isNegative) { "개정판을 받을 실행을 두는 폭은 음수가 아니어야 한다: $revisableRetention" }
    }

    private val lock = ReentrantLock(true)
    private val signals = CellBandSignals()

    /**
     * 이 호스트의 플릿 포트(`host.fleet.url`). 비우면 `AmrFleetPort.None` 이고 플릿 경로 정의의 활성화
     * ([dev.picasso.ops.host.mission.MissionVersions.FLEET_PORT_ABSENT])와 플릿 단위가 든 계획의 새 접수([refuseFleetPlan])를 명시 사유로
     * 거절한다. 주소가 있으면 현장 플릿 세계 면을 부르는 HTTP 포트를 [dev.picasso.ops.host.fleet.HostFleetPort] 로 감싼 것이고 그 차단은 풀린다.
     * 모의 실행은 이것과 무관하게 인프로세스 플릿 미믹으로 돈다.
     */
    private val fleetPort: AmrFleetPort = fleet

    /** 이 호스트에 플릿 포트가 있는가. 없으면 플릿 운반을 맡길 곳이 없다. */
    val hasFleetPort: Boolean get() = fleetPort !== AmrFleetPort.None

    /** 플릿 연결(호스트 상태 응답·개요 화면). 없으면 `connected` 가 거짓이고 주소가 널이다. */
    val fleetLink: FleetLinkView
        get() = FleetLinkView(hasFleetPort, (fleetPort as? dev.picasso.ops.host.fleet.HostFleetPort)?.url)

    /**
     * `instanceId` 는 리스 보유자 id 와 같은 값이다([HostLeadership.instanceId], picasso 기본값과 같은 `mw-<UUID>` 모양으로 프로세스마다 새 값). 그 값이 인시던트 열쇠(`incidentKey`)의 유일성을
     * 진다(picasso §15.322): 인시던트 id 는 재기동마다 `incident-1` 부터 다시 세므로, 고정하면 다른 사건이 같은 열쇠를 얻어 읽는 쪽이
     * 서로 다른 보류를 한 사건으로 접는다. 실행 일지·사본·송신 기록의 인스턴스 칸도 이 값이다.
     */
    /**
     * 기체 포트가 [HostRobotPort] 가 아니면(요청 순번이 없는 시험 배선) 미들웨어가 읽은 스냅숏을 수신자 재시작 의심 판별에 넘기는 포트. [HostRobotPort] 면
     * 그 지금 읽기·미리 읽기 갈고리가 요청 순번과 함께 넘긴다(init).
     */
    private val observing: ObservingRobotPort? =
        if (robots is HostRobotPort) null else ObservingRobotPort(robots) { robot, snapshot, seq -> observeRestartHolds(robot, snapshot, seq) }

    private val middleware = Middleware(
        robots = GatedRobotPort(observing ?: robots, leadership), cell = signals,
        fleet = if (fleetPort === AmrFleetPort.None) fleetPort else GatedFleetPort(fleetPort, leadership),
        now = clock::now, missions = catalog, siteTimings = siteTimings, permits = permits,
        retirements = retirements, entitlements = entitlements, retention = retention, instanceId = leadership.instanceId,
        authority = leadership.authorityPort,
    )

    init {
        // 지금 읽은 스냅숏만 준비 판정에 간다(미리 읽은 값은 넘기지 않는다). 포트가 [HostRobotPort] 가 아니면 관측 중 자동으로 열리는 기체가 없다.
        // 수신자 재시작 의심 판별도 같은 갈고리로 응답을 요청 순번과 함께 받는다 — 소비한 미리 읽기와 그것을 덮은 지금 읽기 둘 다다.
        (robots as? HostRobotPort)?.onFreshRead = { robot, snapshot, seq, at ->
            leadership.observeFresh(robot, snapshot, seq, at)
            observeRestartHolds(robot, snapshot, seq)
        }
        // 미리 읽은 값은 열지 않고 지금 읽기 대상을 거르고 래치 뒤 후퇴를 보는 데만 쓴다.
        (robots as? HostRobotPort)?.onOtherRead = { robot, snapshot, seq ->
            leadership.observePrefetched(robot, snapshot, seq)
            observeRestartHolds(robot, snapshot, seq)
        }
        // 관측 중 미리 읽은 값이 없는 미준비 기체는 첫 관측 뒤로는 기체별 간격마다 한 번만 잠금 아래에서 동기로 읽는다.
        (robots as? HostRobotPort)?.unreadFallback = leadership::allowUnreadFallback
        // 플릿 인수 확인의 답(호스트 잠금 밖의 확인 스레드)과 플릿 읽기 응답의 바닥값을 준비 판정에 넘긴다(HA 3단계 PR B2).
        val fleetHost = fleetPort as? dev.picasso.ops.host.fleet.HostFleetPort
        leadership.fleetConfirmable(fleetHost?.confirm != null)
        fleetHost?.onConfirm = { seq, answer ->
            leadership.observeFleetConfirm(seq, answer, fleetHost.handleBoots(), clock.now())
            answer?.takeIf { it.status == 200 }?.receiverBootId?.let { boot ->
                // 더 뒤의 확인이 본 기동만 남긴다(확인 순번 단조).
                lastFleetBoot.accumulateAndGet(ObservedBoot(boot, seq)) { prev, next -> if (prev == null || next!!.seq > prev.seq) next else prev }
            }
            persistFleetRestart(fleetHost.url)
            noteFleetBoot(fleetHost.url, lastFleetBoot.get())
        }
        // 발행에 든 순간 그 시점의 확인 기동을 곧바로 기준으로 적는다(다음 확인을 기다리는 창을 두지 않는다). 그 기동을 본 확인의 순번을 싣는다.
        if (fleetHost != null) leadership.onIssuing = { noteFleetBoot(fleetHost.url, lastFleetBoot.get()) }
        fleetHost?.onReadFloor = leadership::observeFleetFloor
        // 출발을 실제로 보내기 직전(송신 스레드) 송신 의도 한 줄을 커밋한다 — 커밋하지 못하면 보내지 않는다(수신자 재시작 의심 판별의 흔적).
        leadership.startIntent = ::recordStartIntent
        // 출발을 큐에 넣는 자리(호스트 잠금 아래)에서 그 출발을 낸 실행·단위를 적어 둔다 — 송신 스레드가 의도 행에 그대로 싣는다.
        leadership.issueNote = ::noteIssueContext
    }

    // ── 수신자 재시작 의심 보류(HA 후속 D1 완화, picasso 한계 대장 §15.330 의 실행 호스트 쪽 완화)

    /**
     * 이번 보유가 앞 세대 송신 후보를 판별하는가 — 획득 결과가 정상 종료 인수([Acquired.graceful])가 아닐 때다. 그 범위: 만료 인수(다른 인스턴스가
     * 넘겨받음), 비정상 종료한 단일 프로세스의 재기동(리스 만료 뒤 같은 DB 로 다시 뜸), 관측(`HOLDING`) 중에 정상 종료한 보유자의 후임(앞 세대가 발행을
     * 열지 않았으므로 정상 종료 인수가 아님), 획득 결과를 조회로 복구한 획득([Acquired.recovered]), 다른 인스턴스가 살아 있는 첫 획득. 곧바로 발행하는
     * 정상 종료 인수의 신규 판별은 이번 범위 밖이고(알려진 한계, 다음 과제) 저장된 의심은 그 경로에서도 관문에 적용된다.
     */
    @Volatile
    private var discriminate = false

    /** 출발을 낸 실행·단위(송신 의도 행의 문맥). */
    private data class IssueContext(val jobOrderId: String, val executionId: String, val unitId: String, val attempt: Int)

    /** 태스크 id → 그 출발을 큐에 넣을 때 본 문맥. 큐 입구(호스트 잠금 아래)에서 적고 송신 스레드가 읽는다. 최근 [ISSUE_CONTEXTS] 개만 둔다. */
    private val issueContexts: MutableMap<String, IssueContext> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, IssueContext>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IssueContext>?): Boolean = size > ISSUE_CONTEXTS
        },
    )

    /** 그 태스크를 지금 시도로 든 실행의 단위를 찾는다. 호스트 잠금 아래에서만 부른다. */
    private fun resolveIssueContext(taskId: String): IssueContext? {
        for (execution in middleware.executions()) {
            val unit = execution.units.firstOrNull { restartTaskId(execution.order.jobOrderId, it.unitId, it.attempt) == taskId } ?: continue
            return IssueContext(execution.order.jobOrderId, execution.executionId, unit.unitId, unit.attempt)
        }
        return null
    }

    /** 출발을 큐에 넣는다(호스트 잠금 아래) — 그 출발의 실행·단위를 적어 둔다. */
    private fun noteIssueContext(robotId: String, taskId: String, revision: Int) {
        if (lock.isHeldByCurrentThread) resolveIssueContext(taskId)?.let { issueContexts[taskId] = it }
    }

    /**
     * 송신 의도 한 줄(V22 `host_issue_intent`, 리스 가드 안)을 커밋한다. 송신 스레드(또는 동기 출발이면 호스트 잠금 아래)에서 불린다. 그 출발의 실행·단위는
     * 큐 입구에서 적어 둔 문맥에서 받고, 없으면(호스트 잠금 아래의 동기 출발) 지금 찾는다 — 그래도 없으면 보내지 않는다. 실패하면 [CommandNotSent] 다.
     * 그 태스크의 붙음·후보 보류는 커밋 뒤 위임 직전 재확인을 지난 뒤에 닫는다([HostLeadership.started] — 이 세대가 낸 출발이 되었다, 그 뒤의 수신자
     * 재시작은 같은 세대 한계).
     */
    private fun recordStartIntent(robotId: String, taskId: String, revision: Int) {
        val claim = leadership.claim() ?: throw CommandNotSent("${SendAuthority.NOT_ISSUING}: 송신 의도를 적을 리스가 없다(robot=$robotId task=$taskId)")
        val context = issueContexts[taskId] ?: (if (lock.isHeldByCurrentThread) resolveIssueContext(taskId) else null)
            ?: throw CommandNotSent("송신 문맥(실행·단위)을 몰라 송신 의도를 적지 않고 보내지 않았다: robot=$robotId task=$taskId")
        try {
            records.recordIssueIntent(
                claim.generation, instanceId, robotId, context.jobOrderId, context.executionId, context.unitId, context.attempt, taskId, revision,
                leadership.restartHolds.session(robotId),
            )
        } catch (e: Exception) {
            throw CommandNotSent("송신 의도를 적지 못해 보내지 않았다: robot=$robotId task=$taskId 리비전 $revision — $e")
        }
    }

    /** 스냅숏 응답 하나(요청 순번 [seq], 모르면 `null`)를 보류 판별에 넘긴다. 호스트 잠금 아래에서 불린다. */
    private fun observeRestartHolds(robotId: String, snapshot: RobotSnapshot?, seq: Long?) {
        if (leadership.restartHolds.observe(robotId, snapshot, seq, leadership.controllerEpoch(), leadership.mayIssue())) {
            log.warn("기체 {} 에서 수신자 재시작 의심을 확정했다 — 앞 세대가 보냈을 수 있는 단위가 수신자 장부에 없다: {}", robotId, leadership.restartHolds.describe(robotId))
        }
    }

    /** 지금까지 보낸 스냅숏 요청의 가장 큰 순번 — 후보는 이보다 뒤에 보낸 요청의 응답으로만 판정한다. */
    private fun currentSeq(): Long = (robots as? HostRobotPort)?.currentSeq() ?: observing?.current() ?: 0L

    /** 저장된 열린 의심을 관문에 올린다. 리스 획득 때 복원보다 먼저 부른다 — 읽지 못하면 예외가 나가 재대조가 실패한다(잃음, 복원 이벤트 없음). */
    private fun loadRestartHolds() {
        val rows = records.openRestartSuspicions()
        leadership.restartHolds.load(
            rows.map {
                RestartHolds.Hold(
                    it.robotId, it.jobOrderId, it.executionId, it.unitId, it.attempt, it.taskId, it.revision, it.basisIntentId, it.basisRecordId,
                    state = RestartHolds.State.SUSPECT, basis = runCatching { RestartHolds.Basis.valueOf(it.basisKind) }.getOrNull(),
                    suspicionId = it.suspicionId, generation = it.generation, observedFloor = it.observedFloor, observedStatus = it.observedStatus,
                    afterSeq = currentSeq(),
                )
            },
        )
        if (rows.isNotEmpty()) log.warn("열린 수신자 재시작 의심 {} 건을 올렸다 — 그 기체로 가는 출발은 해제까지 막는다", rows.size)
        // 시스템 해제된 의심 가운데 그 뒤 송신 의도도 단위 판정도 없는 것은 다시 대조할 후보로 올린다(복원 대상에서 빠진 종결·포기 행이어도).
        val rejudged = (
            try {
                records.systemReleasedSuspicions()
            } catch (e: Exception) {
                // 송신 의도를 못 읽으면 그 뒤 출발을 냈는지 모른다 — 시스템 해제된 의심 전부를 다시 대조한다(막는 쪽).
                log.warn("시스템 해제 뒤 송신 의도를 읽지 못해 시스템 해제된 의심 전부를 다시 대조한다: {}", e.toString())
                records.systemReleasedSuspicions(intents = false)
            }
            ).filter { row -> !verdictedSince(row) }
        leadership.restartHolds.loadRejudged(
            rejudged.map {
                RestartHolds.Hold(
                    it.robotId, it.jobOrderId, null, it.unitId, it.attempt, it.taskId, it.revision, it.basisIntentId, it.basisRecordId,
                    afterSeq = currentSeq(),
                )
            },
        )
        if (rejudged.isNotEmpty()) log.warn("시스템 해제된 의심 {} 건을 수신자 장부와 다시 대조할 후보로 올렸다", rejudged.size)
    }

    /** 그 의심의 단위가 그 시도에서 판정을 얻었는가(최근 판단 기록). 못 읽으면 거짓(다시 대조). */
    private fun verdictedSince(row: dev.picasso.ops.host.store.RestartSuspicionRow): Boolean =
        runCatching {
            records.latestJudgmentRecord(row.jobOrderId)?.let { JudgmentRecordWire.decode(it.record) }?.units
                ?.any { it.unitId == row.unitId && it.attempt == row.attempt && it.verdict != null } == true
        }.getOrDefault(false)

    /** 복원 전에 읽어 둔 그 작업 지시의 송신 흔적(송신 의도 요약·해제가 덮는 순번). 다시 짓기 전에 읽어, 읽지 못하면 다시 짓지 않는다. */
    private data class IssueTrace(val intents: Map<String, dev.picasso.ops.host.store.IntentBasis>, val covered: Map<String, Long>)

    private fun issueTrace(jobOrderId: String): IssueTrace? =
        if (!discriminate) null else IssueTrace(records.intentBases(jobOrderId), records.restartReleaseCoverage(jobOrderId))

    /**
     * 다시 지은 실행의 앞 세대 송신 후보를 막는다(판별하는 획득만). 후보는 기체 단위 가운데 판단 기록의 같은 시도에 판정이 없고, 앞 세대 요청 흔적
     * (`requestedAt`)이나 그 시도 태스크의 송신 의도 행이 있는 것이다. 해제가 그 태스크의 가장 큰 송신 의도 순번까지 덮었거나 이미 보류가 있으면 빠진다.
     * DB 를 읽지 않는다 — 흔적은 다시 짓기 전에 읽었다([issueTrace]).
     */
    private fun holdCandidates(execution: Middleware.Execution, record: JudgmentRecord?, recordId: Long?, trace: IssueTrace?) {
        if (trace == null) return
        val jobOrderId = execution.order.jobOrderId
        val units = execution.units.map { RestartUnit(it.unitId, it.attempt, execution.robotOf(it), it.route == Route.ROBOT) }
        val candidates = restartCandidates(
            jobOrderId, execution.executionId, units, record, recordId, trace.intents, trace.covered, leadership.restartHolds::has,
        ).map { it.copy(afterSeq = currentSeq()) }
        if (candidates.isEmpty()) return
        leadership.restartHolds.hold(candidates)
        log.info("앞 세대가 보냈을 수 있는 단위 {} 개를 수신자 장부와 대조할 때까지 막는다: {}", candidates.size, candidates.joinToString { it.taskId })
    }

    /**
     * 복원을 미룬 실행의 기체를 다시 지을 때까지 막는다(판별하는 획득만). 그 작업 지시에 송신 의도 행이 있거나 최근 판단 기록에 판정 없는 요청 흔적이
     * 있을 때만이다. 막는 기체는 일지 행의 기체와 송신 의도 행의 기체 전부다. 읽지 못하면 다시 읽을 때까지 모든 기체를 막는다.
     */
    private fun deferRestartHold(row: JournalRow) {
        if (!discriminate) return
        val robotsOf = try {
            val intents = records.intentBases(row.jobOrderId)
            val traced = records.latestJudgmentRecord(row.jobOrderId)?.let { stored ->
                JudgmentRecordWire.decode(stored.record).units.any { it.requestedAt != null && it.verdict == null }
            } == true
            if (intents.isEmpty() && !traced) null else intents.values.mapTo(linkedSetOf(row.robotId)) { it.robotId }
        } catch (e: Exception) {
            // 흔적을 못 읽으면 그 작업 지시의 단위가 어느 기체에 갔는지 모른다 — 다시 읽을 때까지 모든 기체를 막는다.
            setOf(RestartHolds.ALL_ROBOTS)
        }
        robotsOf?.let { leadership.restartHolds.deferHold(row.jobOrderId, it) }
    }

    /** 의심으로 넘기지 못한 복원 못 한 행(다음 pump 에 다시 넘긴다 — 그동안 미룬 복원의 보류가 그 기체를 막는다). */
    private val abandonPending = LinkedHashMap<String, JournalRow>()

    /**
     * 복원하지 못한 행(포기 `GAVE_UP`·미룬 채 운영자 종결)의 판정 없는 송신 흔적을 해제 가능한 의심([RestartHolds.Basis.RESTORE_ABANDONED])으로 넘긴 뒤
     * 미룬 복원의 보류를 걷는다(판별하는 획득만). 흔적은 최근 판단 기록의 판정 없는 요청 흔적과, 판정 없는 단위의 송신 의도 행이다 — 해제가 덮은 것과
     * 이미 보류가 있는 것은 빠진다. 읽지 못하면 미룬 복원의 보류를 남기고 다음 pump 에 다시 한다. 호스트 잠금 아래에서 부른다.
     */
    private fun abandonRestartHolds(row: JournalRow) {
        if (!discriminate) {
            leadership.restartHolds.clearDeferred(row.jobOrderId)
            return
        }
        val jobOrderId = row.jobOrderId
        try {
            val holds = abandonedHolds(row)
            leadership.restartHolds.hold(holds)
            abandonPending.remove(jobOrderId)
            leadership.restartHolds.clearDeferred(jobOrderId)
            if (holds.isNotEmpty()) log.warn("복원하지 못한 작업 지시 {} 의 송신 흔적 {} 개를 해제 가능한 의심으로 넘겼다", jobOrderId, holds.size)
        } catch (e: Exception) {
            abandonPending[jobOrderId] = row
            // 넘기지 못한 동안 그 기체를 막는다(처음부터 포기한 행이라 미룬 복원의 보류가 없을 수 있다) — 다음 pump 의 미들웨어 pump 앞에서 다시 넘긴다.
            // 흔적을 못 읽었으니 그 작업 지시의 단위가 어느 기체에 갔는지 모른다 — 다시 넘길 때까지 모든 기체를 막는다.
            leadership.restartHolds.deferHold(jobOrderId, setOf(RestartHolds.ALL_ROBOTS))
            log.warn("복원하지 못한 작업 지시 {} 의 송신 흔적을 의심으로 넘기지 못했다(그 기체를 막은 채 다시 한다): {}", jobOrderId, e.toString())
        }
    }

    /**
     * 복원하지 못한 행의 판정 없는 송신 흔적 — 최근 판단 기록의 판정 없는 요청 흔적과 판정 없는 단위의 송신 의도 행 — 을 해제 가능한 의심
     * ([RestartHolds.Basis.RESTORE_ABANDONED])으로 짓는다. 해제(운영자)가 덮은 것과 이미 보류가 있는 것은 빠진다. DB 를 읽고, 읽지 못하면 던진다.
     */
    private fun abandonedHolds(row: JournalRow): List<RestartHolds.Hold> {
        val jobOrderId = row.jobOrderId
        val record = records.latestJudgmentRecord(jobOrderId)?.let { stored ->
            runCatching { JudgmentRecordWire.decode(stored.record) }.getOrNull()?.let { it to stored.recordId }
        }
        val intents = records.intentBases(jobOrderId)
        val covered = records.restartReleaseCoverage(jobOrderId)
        val units = record?.first?.units.orEmpty()
        val verdicted = units.filter { it.verdict != null }.mapTo(HashSet()) { restartTaskId(jobOrderId, it.unitId, it.attempt) }
        val traced = units.filter { it.requestedAt != null && it.verdict == null }
            .associateBy { restartTaskId(jobOrderId, it.unitId, it.attempt) }
        val tasks = (traced.keys + intents.keys.filter { it !in verdicted }).distinct()
        return tasks.mapNotNull { taskId ->
            val intent = intents[taskId]
            val basis = intent?.intentId ?: 0L
            if ((covered[taskId] ?: -1L) >= basis || leadership.restartHolds.has(taskId)) return@mapNotNull null
            val trace = traced[taskId]
            RestartHolds.Hold(
                intent?.robotId ?: row.robotId, jobOrderId, null, trace?.unitId ?: intent!!.unitId, trace?.attempt ?: intent!!.attempt, taskId,
                intent?.revision, basis, record?.second, state = RestartHolds.State.SUSPECT, basis = RestartHolds.Basis.RESTORE_ABANDONED,
                afterSeq = currentSeq(),
            )
        }
    }

    /** 의심으로 넘기지 못한 복원 못 한 행을 다시 넘긴다 — pump 가 미들웨어 pump 앞에서 부른다(그동안 그 기체는 막혀 있다). */
    private fun retryAbandoned() {
        abandonPending.values.toList().forEach(::abandonRestartHolds)
    }

    /**
     * pump 마다 보류를 손본다(호스트 잠금 아래): 판정을 얻은 단위의 붙음·후보를 닫고, 구동이 끝난 실행의 후보를 의심으로 확정한다. 새 의심의 저장은
     * pump 뒤 기록의 트랜잭션이 한다([recordAfterPump]).
     */
    private fun tendRestartHolds() {
        val holds = leadership.restartHolds
        val live = middleware.executions().filter { it.physicalState.pumps }
        holds.closeVerdicts(
            live.flatMapTo(HashSet()) { e ->
                e.units.filter { it.state in VERDICT_STATES }.map { restartTaskId(e.order.jobOrderId, it.unitId, it.attempt) }
            },
        )
        holds.confirmOrphans(live.mapTo(HashSet()) { it.executionId })
    }

    /**
     * 붙음을 본 의심의 시스템 해제(`system:attached`)를 의심마다 따로 적는다(리스 가드 안). 커밋하면 그 보류를 붙음으로 되돌린다 — 막지 않되 이 세대가
     * 그 출발을 내거나 단위가 판정을 얻을 때까지 다시 본다([RestartHolds.systemReleased]). 실패하면 그 의심을 막은 채 다음 pump 에 다시 한다. 호스트 잠금
     * 아래에서 부른다.
     */
    private fun commitSystemReleases(generation: Long) {
        leadership.restartHolds.systemReleases().forEach { h ->
            val id = checkNotNull(h.suspicionId)
            try {
                val outcome = records.releaseRestartSuspicion(
                    id, h.robotId, h.taskId, generation, instanceId, SYSTEM_RELEASER, "수신자 장부에 그 태스크가 같은 리비전 이상으로 붙어 보임(재전송 멱등)",
                )
                when {
                    outcome is dev.picasso.ops.host.store.RestartReleaseOutcome.Released ||
                        outcome is dev.picasso.ops.host.store.RestartReleaseOutcome.AlreadyReleased && outcome.suspicion.releasedBy == SYSTEM_RELEASER -> {
                        leadership.restartHolds.systemReleased(id)
                        log.info("수신자 재시작 의심 #{} 을 닫았다 — 뒤에 수신자 장부에 붙어 보였다(시스템 해제, 붙음으로 계속 봄)", id)
                    }
                    // 운영자가 먼저 해제했다 — 운영자 해제가 이긴다(보류를 닫는다).
                    outcome is dev.picasso.ops.host.store.RestartReleaseOutcome.AlreadyReleased -> leadership.restartHolds.release(id)
                }
            } catch (e: Exception) {
                log.warn("수신자 재시작 의심 #{} 의 시스템 해제를 적지 못했다(막은 채 다음 pump 에 다시 한다): {}", id, e.toString())
            }
        }
    }

    /** 저장할 의심 한 줄. */
    private fun suspicionWrite(h: RestartHolds.Hold) = RestartSuspicionWrite(
        h.robotId, h.jobOrderId, h.executionId, h.unitId, h.attempt, h.taskId, h.revision, (h.basis ?: RestartHolds.Basis.ABSENT).name, h.basisIntentId,
        h.basisRecordId, h.observedFloor, h.observedStatus,
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

    /**
     * 사본에 줄을 적은 인시던트 — 인시던트 id 에서 마지막으로 적은 번들·개정 번호·줄(V17, picasso §15.327). **개수가 아니라 식별자로 고른다** — 사본
     * 선언 아래에서는 확인한 줄을 상한이 앞에서부터 내리므로 목록의 길이가 상한에 머물고, 개수로 세면 그 뒤 새 인시던트를 영영 적지 않는다. 대장에 남은
     * id 만 든다(커밋 뒤 대장에 없는 id 를 뺀다 — 내린 인시던트는 다시 바뀌지 않는다). [CopiedLine.bundle] 은 같은 객체면 줄이 같다는 빠른 판정이다
     * (picasso 는 바뀐 번들을 새 객체로 갈아 끼운다).
     */
    private val copiedLines = mutableMapOf<String, CopiedLine>()

    private data class CopiedLine(val bundle: IncidentBundle, val revision: Int, val line: String)

    /** 사본에 적은 탐색 id. 대장에 남은 id 만 든다. 탐색 기록은 적힌 뒤 바뀌지 않는다. */
    private val copiedSearches = mutableSetOf<String>()

    /**
     * 사본 표(V18)에 커밋한 리비전 제거 사건의 열쇠(실행 id, `toVersion`) — 한 사본은 (인스턴스, 실행, `toVersion`)이 가리킨다(picasso §15.329). 레지스터에
     * 남은 열쇠만 든다. **개수가 아니라 식별자로 고른다** — 레지스터는 보존 상한(`Retention.revisionRemovals`)에 차면 수가 그대로인 채 앞 사건을 내리고
     * 새 사건을 받는다. 이 레지스터는 사본 선언(`copyAck`)의 보호 밖이라 확인 입구가 없다 — 대신 [removalsCopied]·[removalsLost] 로 누락을 센다.
     */
    private val copiedRemovals = mutableSetOf<Pair<String, Int>>()

    /** 이 인스턴스가 사본 표에 커밋한 리비전 제거 사건의 누계. */
    private var removalsCopied = 0L

    /**
     * 사본 표에 적기 전에 레지스터가 내린 리비전 제거 사건의 누계([watchRemovals]). 레지스터가 받은 수(내린 수 + 남은 수)에서 커밋한 수와 남은 미커밋
     * 수를 뺀 것이다. 0 이 아니면 그만큼 사본 표와 내보내기에서 빠졌다.
     */
    private var removalsLost = 0L

    /** 내보내기에 넘긴 마지막 리비전 제거 사본 행 번호(V18 `removal_id`). 게시는 그 뒤의 커밋된 행만 읽는다. */
    private var publishedRemovalId = 0L

    /**
     * 값 없는 승인으로 섰는데 실행 일지·판단 기록·승인 사실을 적지 못한 실행 id([attemptApproval] 일반 경로). 일지에 없으므로 [journalBlocked] 에 들어
     * pump·제출·승인을 막고, 다음 pump 가 그것들 앞에서 한 트랜잭션으로 다시 적는다([saveApprovals]). 프로세스 안에만 있다 — 죽으면 그 실행은 넘어오지
     * 않지만 그 사이 pump 도 돌지 않아 로봇 명령은 나가지 않았다.
     */
    private val unjournaledApprovals = linkedSetOf<String>()

    /** 마지막으로 본 «확인 전 내림» 누계(인시던트, 탐색). 오르면 경고를 남긴다([watchCopyAcks]). */
    private var evictedBeforeCopy = 0L to 0L

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
     * 실행을 세우지 못한 마지막 제출의 작업 지시(작업 지시 id → 작업 지시). 값 없는 승인([attemptApproval])이 승인으로 설 계획을 미리 세워 플릿
     * 차단([fleetPlanRefusal])을 보려고 든다 — picasso 는 제안의 작업 지시를 밖에 내주지 않는다. 제안처럼 메모리에만 있고 [proposedOrdersLimit] 개를
     * 넘으면 오래된 것부터 버린다. 상한은 미들웨어에 넘긴 picasso 제안 보존 상한(`Retention.proposals`)과 같다 — 이 인스턴스의 picasso 가 든
     * 제안이면 호스트도 그 작업 지시를 든다. 이 인스턴스가 받은 제출이 아니면 승인이 그 계획을 미리 보지 못한다(그런 제안은 플릿 차단을 지난
     * 제출에서만 서므로 같은 임무면 계획에 플릿 단위가 없다). 호스트 잠금 아래에서만 읽고 쓴다.
     */
    private val proposedOrders = object : LinkedHashMap<String, JobOrder>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JobOrder>?) = size > proposedOrdersLimit
    }

    /** [proposedOrders] 의 상한. 미들웨어에 넘긴 picasso 제안 보존 상한(`Retention.proposals`) 그대로다. */
    internal val proposedOrdersLimit: Int = retention.proposals

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

    /**
     * 운영자 판단(`resolve` 의 `Resolved`) 뒤 판단 기록을 아직 적지 못한 실행 id(picasso §15.323 의 판단 사본 저장 틈). 재작업은 picasso `resolve` 안에서
     * 판단을 적고 시도를 올리므로, 적기 전에 pump 가 새 시도를 내고 죽으면 다시 지은 실행은 재작업을 모른 채 앞 실패로 서고 기체에는 새 시도가 나가
     * 있다. 그래서 여기 든 실행은 [journalBlocked] 에 들어 pump 와 신규 제출을 막고, 다음 pump 가 먼저 다시 적는다([saveDecisions]). 승인 적용의
     * 저장 계약(§15.320)을 판단에도 넓힌 것이다. 프로세스 안에만 있다 — 죽으면 그 판단은 넘어오지 않지만 그 사이 pump 도 돌지 않았다.
     */
    private val unsavedDecisions = linkedSetOf<String>()

    /** 처리 중인 운영자 종결의 요청 id. 프로세스 안에만 있다. [closeUnrestored] 가 잠금을 기다리기 전에 넣고 끝나면 뺀다. */
    private val closing: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** 종결하지 않은 포기 행. [previous] 는 포기한 실행(앞 인스턴스의 것), [reason]·[since] 는 마지막 GAVE_UP 이벤트의 사유와 DB 시각이다. */
    private class Unclosed(val row: JournalRow, val previous: RestoredFromView, val reason: String, val since: Instant)

    /** 증언으로 종결한 행과 그 종결 행. */
    private class Attested(val row: JournalRow, val close: CloseRow)

    /** 미룬 행. [order]·[mission] 은 [prepare] 가 한 번 읽은 것이고 다시 시도할 때 그대로 쓴다. */
    private class Deferred(val row: JournalRow, val index: Int) {
        var order: JobOrder? = null
        var mission: ActiveMission? = null
    }

    private val pumper = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-pump").apply { isDaemon = true } }

    /** 심장박동은 pump 와 다른 스레드다. 호스트 잠금을 잡지 않으므로 pump 나 제출이 오래 걸려도 늦지 않는다. */
    private val beater = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-heartbeat").apply { isDaemon = true } }

    val instanceId: String = middleware.instanceId

    /** 리더 상태(HA 설계 기록 §7). */
    val leaderState: LeaderState get() = leadership.state

    private var pumpPeriod: Duration = PUMP_PERIOD

    /**
     * 인스턴스 행을 넣고 심장박동을 시작한 뒤 리스를 얻어 본다([HostLeadership.begin]). [MissionHostApplication] 이 빈을 만들 때 부른다. 곧바로
     * 얻으면 이 안에서 복원까지 끝나므로 웹 서버가 열리기 전에 판정이 도는 실행을 안다(S4b 스펙 T3). 못 얻으면 대기로 뜨고, 뒤에 얻으면 pump
     * 스레드에서 복원한다. 인스턴스 행이나 복원에서 DB 가 실패하면 예외가 나가 기동이 멈춘다.
     */
    fun start(period: Duration = PUMP_PERIOD): MissionHost = apply {
        pumpPeriod = period
        records.beat(instanceId, leadership.timing.expiry)
        val beat = leadership.timing.period.toMillis()
        beater.scheduleWithFixedDelay(::beatOnce, beat, beat, TimeUnit.MILLISECONDS)
        leadership.begin(this) { pumper.execute(it) }
    }

    /**
     * 리스를 얻었다([LeaderState.RECONCILING]). 카탈로그(활성 임무 버전)를 DB 에서 다시 읽고, 기존 복원([restore])으로 일지·잔여 파지·포기 행을
     * 다시 읽어 실행을 다시 짓는다. 그 뒤 리더가 발행([LeaderState.ISSUING]) 또는 관측([LeaderState.HOLDING])에 들고 pump 가 시작한다.
     */
    override fun acquired(acquired: Acquired) {
        // 앞 근거의 현장 플릿 기동 ID 를 기준으로 둔다 — 이번 확인의 기동 ID 가 다르면 현장이 그 사이 다시 떴다(운영자 확인).
        readFleetBaseline()
        discriminate = discriminates(acquired)
        // 저장된 열린 의심을 복원보다 먼저 관문에 올린다 — 복원·재대조 사이에 그 기체로 출발이 나가지 않게(정상 종료 인수 포함).
        lock.withLock { loadRestartHolds() }
        lock.withLock { catalog.restore(store.activeVersions()) }
        restore()
        leadership.reconciled()
        pumper.scheduleWithFixedDelay(::pumpOnce, 0, pumpPeriod.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * 리스를 잃었다. 송신 확인과 DB 가드는 이미 막혀 있다. pump 와 심장박동을 먼저 멈추고, 자기 차단(가드 밖의 인스턴스 행)은 [FENCE_TIMEOUT] 안에서만
     * 기다린다 — DB 가 답하지 않아도 종료 훅으로 넘어간다(LOST 인 채 프로세스가 남지 않게).
     */
    override fun lost(reason: String) {
        pumper.shutdownNow()
        beater.shutdownNow()
        val fence = Thread({
            try {
                records.fence(instanceId, reason)
            } catch (e: Exception) {
                log.warn("자기 차단을 적지 못했다: {}", e.toString())
            }
        }, "mission-host-fence").apply { isDaemon = true }
        fence.start()
        fence.join(FENCE_TIMEOUT.toMillis())
        if (fence.isAlive) log.warn("자기 차단 기록이 {} 안에 끝나지 않아 기록 없이 종료로 간다", FENCE_TIMEOUT)
    }

    /** 심장박동 한 번. 실패는 삼키고 다음 주기에 다시 한다. 만료 폭 안에 다시 적지 못하면 다른 인스턴스가 이 인스턴스를 끊긴 것으로 읽는다. */
    private fun beatOnce() {
        leadership.stall.pass(HostLeadership.Stall.Part.BEAT)
        try {
            records.beat(instanceId, leadership.timing.expiry)
        } catch (e: Exception) {
            log.warn("심장박동 실패: {}", e.toString())
        }
    }

    /**
     * 기동 복원(S4b 스펙 T3, §6.3). 도는 일지 행(정착·다시 돎·포기가 없거나 마지막이 다시 돎, [HostRecords.openJournal])을 받은 순서대로
     * 다시 짓는다. 결과는 일지 이벤트로 남고 복원 보고에 실린다. 그 뒤 포기한 채인 행(이전 기동의 것 포함)을 판정 제외 목록으로, 미해소
     * 잔여 파지 표시를 판정이 읽을 사본으로 읽는다.
     *
     * 리스를 쥔 뒤에만 부른다([acquired]). 행을 든 앞 인스턴스의 심장박동은 보지 않는다 — 권한은 리스 보유다(HA 설계 기록 §7).
     */
    private fun restore() = lock.withLock {
        restoredAt = clock.now()
        records.openJournal().forEach { row -> restoreRow(row) }
        // 이번 기동에서 포기한 행은 attempt 가 이미 넣었고 그 GAVE_UP 이벤트도 일지에 있다. 작업 지시마다 한 번만 든다.
        val abandoned = records.gaveUpJournal()
        // 포기한 채인 행(이전 기동의 것 포함)의 판정 없는 송신 흔적도 해제 가능한 의심으로 넘긴다(판별하는 획득만, 이미 넘긴 것은 해제·보류가 거른다).
        abandoned.forEach(::abandonRestartHolds)
        gaveUp += abandoned.filter { row -> gaveUp.none { it.jobOrderId == row.jobOrderId } }
        abandoned.filter { it.jobOrderId !in unclosed }.forEach { row ->
            val event = records.events(row.jobOrderId).last { it.kind == JournalEventKind.GAVE_UP }
            unclosed[row.jobOrderId] = Unclosed(row, previousOf(row), event.detail.orEmpty(), event.recordedAt)
        }
        records.attestedClosures().forEach { row -> attested += Attested(row, checkNotNull(records.closeOf(row.jobOrderId))) }
        openHolds += records.openResidualHolds()
        refreshRequirements()
    }

    /**
     * «실행 → 필수 기체·플릿» 표를 다시 짓는다(HA 3단계). 구동이 도는 실행마다 계획의 기체 전부와 플릿 단위가 있는가. 발행 관문의 권위 입력과
     * 송신 직전 확인([GatedRobotPort]·[GatedFleetPort])이 같은 표를 읽는다. 호스트 잠금 아래에서 부른다.
     */
    private fun refreshRequirements() {
        leadership.requirements = middleware.executions().filter { it.physicalState.pumps }.map { e ->
            // 요구 기체는 기체 단위를 맡은 기체뿐이다 — 플릿 전용 실행은 플릿 준비만 요구한다. 발행 관문은 실행의 기체 전부로 묻는다(subjects).
            Requirement(
                e.executionId, e.order.jobOrderId,
                e.units.filter { it.route != Route.FLEET }.mapTo(LinkedHashSet()) { e.robotOf(it) },
                e.units.any { it.route == Route.FLEET },
                subjects = e.robots.toSet(),
            )
        }
        // 관측 중 플릿 준비의 재대조가 다시 읽을 운반 참조(끝나지 않은 플릿 단위). 형식은 picasso 의 태스크 id(`작업지시#단위`, 재작업이면 `@r<시도>`)다.
        (fleetPort as? dev.picasso.ops.host.fleet.HostFleetPort)?.references = middleware.executions().filter { it.physicalState.pumps }.flatMapTo(LinkedHashSet()) { e ->
            e.units.filter { it.route == Route.FLEET && it.state in OPEN_FLEET_UNIT }
                .map { "${e.order.jobOrderId}#${it.unitId}" + if (it.attempt > 0) "@r${it.attempt}" else "" }
        }
    }

    /**
     * 이번 라운드의 지금 읽기로 준비를 본 기체를 연다. `Middleware.pump` 가 그 스냅숏으로 재대조를 마친 뒤에 부른다. 근거를 리스 가드 안에서 적은
     * 뒤에만 래치한다 — 적지 못하면(리스 불일치면 잃음으로 감) 그 후보를 버리고 다음 라운드가 다시 지금 읽는다. 호스트 잠금 아래에서 부른다.
     */
    private fun publishReadiness() {
        val generation = leadership.lease.generation ?: return
        leadership.pendingReady().forEach { ready ->
            // 수신자 재시작 의심 보류가 있는 기체는 확인 답이 DURABLE_CONFIRMED 여도 열지 않고 근거를 적지 않는다.
            if (leadership.restartHolds.blocks(ready.robotId)) {
                leadership.holdBack(ready)
                return@forEach
            }
            try {
                records.recordReadiness(generation, instanceId, ready.robotId, ready.floor, ready.fenceStatus.name, ready.observedAt)
                leadership.latch(ready)
            } catch (e: Exception) {
                leadership.dropCandidate(ready.robotId)
                log.warn("기체 {} 의 자동 확인 근거를 적지 못해 열지 않는다(다음 라운드에 다시 읽는다): {}", ready.robotId, e.toString())
            }
        }
    }

    /**
     * 확인 뒤에 보낸 플릿 읽기로 한 pump 의 재대조를 마친 플릿 후보를 연다(HA 3단계 PR B2). 그 pump 에서 미들웨어가 물은 플릿 읽기가 모두 그 라운드에서
     * 다시 읽은 성공한 답이었고(빠진 것·실패 없음), 그 라운드가 확인 경계보다 뒤에 열렸고 쓴 답의 순번이 모두 경계보다 클 때만 근거(리스 가드 안)를 적고
     * 래치한다. 적지 못하면 후보를 그대로 두고 다음 pump 가 다시 읽는다. 시간으로 기다리지 않는다. 호스트 잠금 아래에서 부른다.
     */
    private fun publishFleetReadiness(result: dev.picasso.ops.host.fleet.HostFleetPort.RoundResult?) {
        val candidate = leadership.fleetCandidate() ?: return
        // 기동 기준을 읽기 전에는 열지 않는다(인수 직전 재시작을 못 가른다).
        if (!fleetBaselineRead) return
        if (result == null || !result.complete || result.opened <= candidate.boundary) return
        if (result.minSeq != null && result.minSeq <= candidate.boundary) return
        val fleetHost = fleetPort as? dev.picasso.ops.host.fleet.HostFleetPort ?: return
        // 래치 직전에 아는 운반 핸들(이번 pump 가 물은 것 포함)의 기동과 다시 견준다 — 확인이 핸들을 알기 전에 왔어도 재시작을 잡는다.
        if (!leadership.fleetBootsAgree(candidate, fleetHost.handleBoots())) {
            persistFleetRestart(fleetHost.url)
            return
        }
        try {
            records.recordFleetReadiness(
                candidate.generation, instanceId, fleetHost.url, candidate.bootId, candidate.floor, candidate.fenceStatus, candidate.boundary,
                candidate.observedAt,
            )
            // 근거 행은 «래치 시도» 다 — 적은 뒤 래치가 거짓이면(그 사이 후보가 바뀌거나 잃음·재시작) 행은 그대로 남고(덧붙이기 전용) 열리지 않는다.
            if (!leadership.latchFleet(candidate)) {
                log.warn("플릿 자동 확인 근거(확인 순번 {})를 적었으나 그 사이 후보가 바뀌어 열지 않았다 — 근거 행은 래치 시도로 남는다", candidate.boundary)
            }
        } catch (e: Exception) {
            log.warn("플릿 자동 확인 근거를 적지 못해 열지 않는다(다음 pump 에 다시 재대조한다): {}", e.toString())
        }
    }

    /**
     * 기동 ID 기준을 읽었는가. 그 전에는 확인 답을 기록하지 않고(이번 확인이 기준을 덮어 인수 직전 재시작이 지워지지 않게) 플릿을 래치하지 않는다. 획득 때
     * 읽고, 실패하면 pump 마다 다시 읽는다 — 읽기가 계속 실패하면 이번 보유의 플릿은 운영자 확인으로만 열린다.
     */
    @Volatile
    private var fleetBaselineRead = false

    private fun readFleetBaseline() {
        if (fleetBaselineRead) return
        try {
            leadership.fleetBootBaseline(records.latestFleetBoot())
            fleetBaselineRead = true
        } catch (e: Exception) {
            log.warn("앞 보유자들이 본 현장 플릿 기동 ID 를 읽지 못했다(다음 pump 에 다시 읽는다, 그때까지 플릿을 열지 않는다): {}", e.toString())
        }
    }

    /** 확인이 본 현장 플릿 기동 ID 와 그 확인의 요청 순번. */
    private data class ObservedBoot(val bootId: String, val seq: Long)

    /** 확인 스레드가 마지막으로(가장 큰 확인 순번으로) 본 현장 플릿 기동(발행에 드는 순간 곧바로 적는다). */
    private val lastFleetBoot = java.util.concurrent.atomic.AtomicReference<ObservedBoot?>(null)

    /** 시험 갈고리: 발행 중 기동 기록을 쓰기 직전(값을 정한 뒤)에 부른다. */
    @Volatile
    internal var beforeFleetBootWrite: ((String) -> Unit)? = null

    /** 재시작 판정의 기준 기동을 적었는가. */
    @Volatile
    private var fleetRestartPersisted = false

    /**
     * 이번 보유가 현장 재시작을 판정했으면 그 판정의 기준 기동(앞 장부의 기동)을 기동 기준 표에 적는다(관측 중에도, 한 번). 이 보유자가 운영자 확인 전에 죽고
     * 다음 보유자가 옛 핸들을 찾지 못해도 기준이 그 옛 기동이라 같은 재시작을 본다. 막는 쪽으로만 작용한다. 실패하면 다음 확인·pump 에 다시 적는다.
     */
    private fun persistFleetRestart(fleetId: String) {
        if (fleetRestartPersisted || !fleetBaselineRead) return
        // 발행 중(운영자 확인 뒤)에는 적지 않는다 — 그때는 지금 기동이 기준이다(발행 중 기록이 같은 세대에서 늘 앞선다).
        if (leadership.state == dev.picasso.ops.host.lease.LeaderState.ISSUING) return
        val basis = leadership.fleetRestartBasis() ?: return
        val generation = leadership.claim()?.generation ?: return
        try {
            records.recordFleetBoot(generation, instanceId, fleetId, basis, observedSeq = 0, restartBasis = true)
            fleetRestartPersisted = true
            log.info("현장 플릿 재시작 판정의 기준 기동 {} 을 적었다(다음 보유자도 재시작을 본다)", basis)
        } catch (e: Exception) {
            log.warn("재시작 판정의 기준 기동을 적지 못했다(다시 적는다): {}", e.toString())
        }
    }

    /** 이 프로세스가 적은 현장 플릿 기동 가운데 확인 순번이 가장 큰 것. */
    private val fleetBootNoted = java.util.concurrent.atomic.AtomicReference<ObservedBoot?>(null)

    /**
     * 확인 스레드가 본 현장 플릿 기동 ID 를 적는다(V21 `host_fleet_receiver_boot`, 리스 가드 안, 바뀔 때만). 발행 중(`ISSUING`)에도 적어 «앞 보유자들이 본
     * 마지막 기동» 이 최신으로 남는다 — 정상 운영 중의 현장 재시작이 다음 인수의 재시작 판정을 오염시키지 않고, 인수 직전(마지막 기록 뒤)의 재시작은 잡힌다.
     * 실패는 다음 확인에서 다시 적는다. 그 기동을 본 확인의 순번을 함께 적는다 — 발행에 든 순간의 기록과 확인 스레드의 기록이 경쟁해 옛 기동이 늦게 들어가도
     * 다음 보유자는 순번이 큰(더 뒤에 본) 기동을 고른다(행 순서에 기대지 않는다).
     */
    private fun noteFleetBoot(fleetId: String, observed: ObservedBoot?) {
        val bootId = observed?.bootId
        if (bootId == null || !fleetBaselineRead || bootId == fleetBootNoted.get()?.bootId) return
        // 발행 중(정상 종료 인수·운영자 확인 뒤)에만 적는다 — 관측 중의 확인 기동은 재대조가 뒤늦게 재시작을 잡을 수 있어 다음 보유자의 기준으로 확정하지
        // 않는다(관측 중 자동으로 연 기동은 근거 행이 담는다).
        if (leadership.state != dev.picasso.ops.host.lease.LeaderState.ISSUING) return
        val generation = leadership.claim()?.generation ?: return
        try {
            beforeFleetBootWrite?.invoke(bootId)
            records.recordFleetBoot(generation, instanceId, fleetId, bootId, observed.seq)
            // 늦게 들어간 옛 관측이 «마지막으로 적은 기동» 을 되돌리지 않는다.
            fleetBootNoted.accumulateAndGet(observed) { prev, next -> if (prev == null || next!!.seq > prev.seq) next else prev }
        } catch (e: Exception) {
            log.warn("현장 플릿 기동 ID 를 적지 못했다(다음 확인에 다시 적는다): {}", e.toString())
        }
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
        attempt(Deferred(row, index), previous, first = true)
    }

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
     */
    private fun retryDeferred() {
        // 한 pump 에 다시 시도하는 미룬 행은 [RESTORE_RETRIES] 개다(복원의 스냅숏 읽기가 호스트 잠금 아래에서 쌓이지 않게). 시도한 행은 뒤로 돌려 차례를 돌린다.
        val batch = deferred.take(RESTORE_RETRIES)
        deferred.removeAll(batch)
        deferred.addAll(batch)
        batch.forEach { waiting ->
            val previous = restoreRows[waiting.index].let { RestoredFromView(it.previousInstanceId, it.previousExecutionId) }
            try {
                // 다른 인스턴스가 그 사이 종결했으면 이 인스턴스의 메모리가 든 채로 리빌드하지 않는다. 일지는 DB 가 원본이다.
                records.closeOf(waiting.row.jobOrderId)?.let { closed ->
                    forgetClosed(waiting.row, closed)
                    return@forEach
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
            deferRestartHold(waiting.row)
            if (first) {
                deferred += waiting
                settleRestore(waiting.row, waiting.index, previous, result, null, reason)
            } else if (restoreRows[waiting.index].reason != reason) {
                // 미룬 까닭이 바뀌었다. 이벤트는 미룬 기동마다 한 번이라 보고 행만 고친다.
                restoreRows[waiting.index] = restoreRows[waiting.index].copy(reason = reason)
            }
            return
        }
        val event = settleRestore(waiting.row, waiting.index, previous, result, executionId, reason, waiting.order?.version)
        deferred.remove(waiting)
        // 다시 지었으면 후보가 섰다 — 미룬 복원의 보류를 걷는다. 포기면 판정 없는 송신 흔적을 해제 가능한 의심으로 넘긴 뒤에 걷는다.
        if (result == RestoreResult.GAVE_UP) abandonRestartHolds(waiting.row) else leadership.restartHolds.clearDeferred(waiting.row.jobOrderId)
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
        // 다시 지을 계획에 플릿 단위가 있는데 플릿 포트가 없으면 다시 짓지 않고 미룬다(사유 `FLEET_PORT_ABSENT`). 운반을 맡길 곳 없이 실행을 세우면
        // 실패 운반으로 조용히 정착한다. 포기(GAVE_UP)로 적지 않는다 — 포기한 행은 다시 시도되지 않고 플릿 단위가 있어 운영자 종결도 받지 않으므로
        // 출구가 없다. 미룬 행은 pump 마다 다시 시도하고 재기동에서도 다시 읽혀, 플릿 포트를 붙인 기동에서 같은 참조로 그 운반에 다시 붙는다.
        // 그동안 그 기체는 판정에서 빠진다(미룬 행). 운영자 종결은 받지 않는다(`RESUME_SNAPSHOT_UNREADABLE` 미룸이 아님).
        fleetPlanRefusal(checkNotNull(waiting.order), checkNotNull(waiting.mission).capability)?.let {
            return Triple(RestoreResult.DEFERRED, null, "${HostRequests.FLEET_PORT_ABSENT}: $it")
        }
        val cancel = records.cancelOf(waiting.row.jobOrderId)
        val stored = records.latestJudgmentRecord(waiting.row.jobOrderId)
        val record = stored?.let {
            try {
                JudgmentRecordWire.decode(it.record)
            } catch (e: Exception) {
                return Triple(RestoreResult.GAVE_UP, null, "$RECORD_UNREADABLE: record=${it.recordId}, ${e.message}")
            }
        }
        // 송신 흔적은 다시 짓기 전에 읽는다 — 읽지 못하면 예외가 나가 이번에는 다시 짓지 않는다(다시 지은 뒤에 못 읽어 후보 없이 복원을 마치는 일이 없게).
        val trace = issueTrace(waiting.row.jobOrderId)
        return when (val submission = middleware.resume(checkNotNull(waiting.order), waiting.row.robotId, checkNotNull(waiting.mission), record)) {
            is Middleware.Submission.Accepted -> {
                stored?.let { recorded[waiting.row.jobOrderId] = it.record }
                holdCandidates(submission.execution, record, stored?.recordId, trace)
                cancel?.let { carryCancel(it, submission.execution) }
                Triple(RestoreResult.RESTORED, submission.execution.executionId, null)
            }
            is Middleware.Submission.Idempotent -> {
                // 앞 시도가 다시 지은 뒤 이벤트를 못 적은 경우다 — 같은 흔적으로 후보를 다시 세운다(이미 있는 보류는 그대로).
                holdCandidates(submission.execution, record, stored?.recordId, trace)
                Triple(RestoreResult.RESTORED, submission.execution.executionId, null)
            }
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

    /**
     * pump 한 번. 예외를 삼키고 다음 주기에 다시 돈다. 스케줄러는 작업이 예외를 던지면 다음 실행을 멈춘다. 리더가 관측([LeaderState.HOLDING])이나
     * 발행([LeaderState.ISSUING])일 때만 돈다 — 관측 중의 출발·취소는 송신 직전 확인이 보내지 않는다(`CommandNotSent`, 단위는 그대로 기다린다).
     */
    fun pumpOnce() {
        leadership.stall.pass(HostLeadership.Stall.Part.PUMP)
        val state = leadership.state
        if (state != LeaderState.HOLDING && state != LeaderState.ISSUING) return
        try {
            val cell = cellBand.fetch()
            // 관측 중 플릿 후보가 있으면 앞 pump 가 물은 플릿 읽기를 지금(잠금 밖) 다시 읽는다 — 확인 경계보다 뒤에 보낸 답만 그 pump 의 재대조에 쓴다.
            val fleetHost = fleetPort as? dev.picasso.ops.host.fleet.HostFleetPort
            if (fleetHost != null && !fleetBaselineRead && leadership.claim() != null) readFleetBaseline()
            if (fleetHost != null) persistFleetRestart(fleetHost.url)
            val fresh = if (fleetHost != null && leadership.fleetCandidate() != null && fleetHost.roundDue()) fleetHost.readFresh() else null
            // 작업 허가 줄의 관측 시각은 이 층이 셀 대역을 읽은 시각이다. 잠금을 기다린 시간은 관측의 나이에 든다.
            val fetchedAt = clock.now()
            lock.withLock {
                signals.snapshot = cell
                latestCell = cell
                permits?.observe(cell, fetchedAt)
                val at = clock.now()
                retryDeferred()
                // 의심으로 넘기지 못한 복원 못 한 행을 미들웨어 pump 앞에서 다시 넘긴다(그 기체는 그동안 막혀 있다).
                retryAbandoned()
                // 값 없는 승인으로 섰는데 적지 못한 실행도 pump 전에 다시 적는다(일지 없이 그 실행의 로봇 명령이 나가지 않게).
                saveApprovals()
                // 다시 돈 실행의 기록이 커밋되기 전에는 미들웨어 pump 를 돌리지 않는다(그 실행의 로봇 명령이 pump 에서 나간다).
                recordReruns()
                // 판단 뒤 적지 못한 판단 기록도 pump 전에 다시 적는다(재작업의 새 시도가 그 기록보다 먼저 나가지 않게).
                saveDecisions()
                if (journalBlocked().isNotEmpty()) return@withLock
                refreshRequirements()
                // 이 라운드의 후보는 이 라운드의 지금 읽기에서만 선다(앞 라운드의 잔류 후보를 재대조 없이 래치하지 않는다).
                leadership.clearCandidates()
                // 보류 기체 회복 읽기의 pump 당 상한을 새로 센다.
                leadership.beginRound()
                // 관측 중이면 미리 읽기가 준비를 보인 미준비 기체를 지금 읽는다(그 읽기만 준비 판정에 간다).
                // 관측 중에는 출발을 앞둔 기체도 열렸거나 미리 읽기가 준비를 보인 것만 지금 읽는다(준비가 설 수 없는 기체는 어차피 출발하지 못한다).
                // 보류 기체는 가장 오래 회복 읽기를 못 한 기체 하나가 그 pump 의 회복 몫을 받는다(관측·발행 모두, 출발을 앞두지 않았어도).
                (robots as? HostRobotPort)?.readNowFor(
                    startingRobots().filterTo(HashSet(), leadership::mayReadNow) + leadership.freshTargets() + leadership.heldRecoveryTargets(),
                )
                fleetHost?.beginPump(fresh)
                middleware.pump()
                val reconciled = fleetHost?.endPump()
                tendRestartHolds()
                publishReadiness()
                publishFleetReadiness(reconciled)
                pumpedAt = at
                // 기록이 커밋된 뒤 사본을 확인하고 파일을 게시하며, 그 뒤에 정착한 실행을 놓는다([recordAfterPump]).
                recordAfterPump()
                watchCopyAcks()
                watchRemovals()
            }
        } catch (e: Exception) {
            log.warn("pump 실패: {}", e.toString())
        }
    }

    /**
     * 인시던트 고리로 파일을 낸다. [recordAfterPump] 가 **DB 커밋 뒤, 놓기([forgetSettled]) 앞**에 부른다 — 커밋이 실패한 pump 의 줄은 게시하지 않는다
     * (파일이 DB 보다 앞서지 않는다). 자료([incidents]·[searches]·[responses])는 기록이 커밋한 것과 같은 것으로 놓기 전에 모은다. 실패는 pump 를 멈추지
     * 않는다 — [IncidentExport] 가 본 줄을 들고 있어 다음 게시가 같은 사본을 다시 쓴다. 커밋이 실패하면 놓지도 확인하지도 않으므로 그 자료는
     * 미들웨어에 남아 다음 pump 가 다시 모은다. 호스트 잠금 아래에서 부른다.
     */
    private fun publish(incidents: List<IncidentBundle>, searches: List<RemedySearchRecord>, responses: List<JobResponse>) {
        val export = exported ?: return
        try {
            // 리비전 제거 사건(내보내기 버전 10)은 picasso 레지스터가 아니라 **커밋된 사본 행**(이 인스턴스)에서 모은다 — 파일이 DB 보다 앞서지 않고,
            // 게시가 늦는 동안 레지스터가 보존 상한으로 사건을 내려도 이미 커밋한 사건은 파일에서 빠지지 않는다. 앞서 모은 행 뒤의 것만 읽는다.
            val rows = records.revisionRemovalsOf(instanceId, publishedRemovalId)
            export.observe(incidents, searches, responses, clock.now(), rows.map(::removalOf))
            rows.lastOrNull()?.let { publishedRemovalId = it.removalId }
        } catch (e: Exception) {
            log.warn("인시던트 고리 내보내기 실패, 다음 pump 에 다시 쓴다: {}", e.toString())
        }
    }

    /**
     * 사본 확인 상태를 읽어 «확인 전 내림»(picasso `CopyLedgerState.evictedBeforeCopy`)이 올랐으면 경고를 남긴다. 그만큼 사본 표에서 빠졌을 수 있다
     * (확인 대기 한도를 넘었다 — 사본 수집이 멈췄거나 한도가 작다). pump 마다 잠금 아래에서 부른다.
     */
    private fun watchCopyAcks() {
        val state = middleware.copyAcks()
        val now = state.incidents.evictedBeforeCopy to state.remedySearches.evictedBeforeCopy
        if (now.first > evictedBeforeCopy.first || now.second > evictedBeforeCopy.second) {
            log.warn(
                "사본 확인 전 내림이 올랐다 — 사본 표에서 빠졌을 수 있다: 인시던트 {}→{} (대기 {}), 탐색 {}→{} (대기 {})",
                evictedBeforeCopy.first, now.first, state.incidents.pending, evictedBeforeCopy.second, now.second, state.remedySearches.pending,
            )
        }
        evictedBeforeCopy = now
    }

    /** 아직 사본 표에 적지 않은 리비전 제거 사건(식별자로 고른다)과 그 사본 행. 줄은 picasso `LedgerExport.revisionRemovalLine` 글자 그대로다. */
    private fun freshRemovals(): List<Pair<RevisionRemoval, RemovalCopyWrite>> =
        middleware.revisionRemovals().filter { (it.executionId to it.toVersion) !in copiedRemovals }.map { r ->
            r to RemovalCopyWrite(
                r.executionId, r.jobOrderId, r.robotId, r.fromVersion, r.toVersion, r.removed.map { RemovedUnitRow(it.unitId, it.state.name) },
                // 칸의 시각은 마이크로초로 잘라 적는다(Postgres 는 반올림한다) — 줄은 picasso 글자 그대로라 나노초가 남을 수 있다.
                r.at.truncatedTo(ChronoUnit.MICROS), r.wallClockAt.truncatedTo(ChronoUnit.MICROS), LedgerExport.revisionRemovalLine(r),
            )
        }

    /** [freshRemovals] 의 사본 행을 지금 트랜잭션에 적는다. 트랜잭션 안에서 부른다. */
    private fun writeRemovals(fresh: List<Pair<RevisionRemoval, RemovalCopyWrite>>) =
        records.copyRevisionRemovals(instanceId, LedgerExport.LINE_SCHEMA_VERSION, fresh.map { it.second })

    /** 커밋한 뒤에만 부른다 — 커밋한 사건을 든다([copiedRemovals]). 레지스터에 남은 열쇠만 남긴다. */
    private fun removalsCommitted(fresh: List<Pair<RevisionRemoval, RemovalCopyWrite>>) {
        fresh.forEach { (r, _) -> if (copiedRemovals.add(r.executionId to r.toVersion)) removalsCopied += 1 }
        val present = middleware.revisionRemovals().mapTo(HashSet()) { it.executionId to it.toVersion }
        copiedRemovals.retainAll(present)
    }

    /**
     * 리비전 제거 사건의 누락을 센다. 이 레지스터는 사본 선언(`copyAck`)의 보호 밖이라 picasso 가 «확인 전 내림» 을 세지 않는다 — 받은 수(내린 수 + 남은
     * 수)에서 커밋한 수와 남은 미커밋 수를 빼 사본 표에 적기 전에 내린 수를 구하고, 오르면 경고를 남긴다. 받은 리비전은 그 실행의 개정판 행과 같은
     * 트랜잭션에 적고 그 트랜잭션이 실패하면 pump·제출이 막히므로 보통 0 이다. pump 마다 잠금 아래에서 부른다.
     */
    private fun watchRemovals() {
        val present = middleware.revisionRemovals()
        val produced = middleware.retentionEvictions().revisionRemovals + present.size
        val uncopied = present.count { (it.executionId to it.toVersion) !in copiedRemovals }
        val lost = (produced - removalsCopied - uncopied).coerceAtLeast(0)
        if (lost > removalsLost) {
            log.warn("리비전 제거 사건을 사본 표에 적기 전에 레지스터가 내렸다 — 사본 표와 내보내기에서 빠졌다: {}→{} (미커밋 {})", removalsLost, lost, uncopied)
        }
        removalsLost = lost
    }

    /**
     * 사본 행을 picasso 사건으로 되돌린다(내보내기 게시용). 두 시각은 칸(마이크로초)이 아니라 picasso 글자 그대로인 줄에서 읽는다 — 다시 인코딩한 줄이
     * 사본 행의 `line` 과 같다.
     */
    private fun removalOf(row: RemovalCopyRow): RevisionRemoval {
        val line = json.readTree(row.line)
        return RevisionRemoval(
            row.executionId, row.jobOrderId, row.robotId, row.fromVersion, row.toVersion,
            row.removed.map { RemovedUnit(it.unitId, UnitState.valueOf(it.state)) },
            Instant.parse(line.path("at").asText()), Instant.parse(line.path("wallClockAt").asText()),
        )
    }

    /** 리비전 제거 사건 사본의 지금 상태. 호스트 상태 응답이 싣는다. 호스트 잠금 아래에서 부른다. */
    private fun removalCopyView(): RemovalCopyView =
        RemovalCopyView(removalsCopied, middleware.revisionRemovals().count { (it.executionId to it.toVersion) !in copiedRemovals }, removalsLost)

    /**
     * 줄 글자가 같은 새 번들([recordAfterPump] 의 `unchanged`)을 든 것으로 바꾸고 그 id 를 다시 확인한다. 그 줄은 앞 트랜잭션에서 이미 커밋됐다 —
     * 확인하지 않으면 picasso 가 다시 올린 확인 대기(`pending`)가 영영 남아 상한이 그 번들을 내리지 못한다. 기록 트랜잭션이 실패한 pump 에서는 부르지
     * 않고 다음 pump 가 다시 견준다. 호스트 잠금 아래에서 부른다.
     */
    private fun reackUnchanged(unchanged: List<CopiedLine>) {
        if (unchanged.isEmpty()) return
        unchanged.forEach { copiedLines[it.bundle.incidentId] = it }
        middleware.ackCopiedIncidents(unchanged.map { it.bundle.incidentId })
    }

    /**
     * picasso `LedgerExport.incidentLine` 을 부르는 자리. 시험이 줄에 싣지 않는 변경(같은 글자 재검토)을 만들려고 바꾼다. 호스트 잠금 아래에서 부른다.
     */
    @Volatile
    internal var incidentLine: (IncidentBundle) -> String = LedgerExport::incidentLine

    /**
     * 인시던트 검토(picasso `Middleware.reviewIncident`). REST 로 열지 않았다 — 시험이 검토로 줄이 바뀌는(또는 같은 글자로 다시 확인 대기에 오르는) 장면을
     * 만드는 자리다. 호스트 잠금 아래에서 부른다.
     */
    internal fun reviewIncident(incidentId: String, verdict: dev.picasso.middleware.ReviewVerdict, cause: String): Boolean =
        lock.withLock { middleware.reviewIncident(incidentId, verdict, cause) }

    /** 사본 선언의 지금 상태(picasso `Middleware.copyAcks`, §15.327). 호스트 상태 응답이 싣는다. */
    internal fun copyAcks(): CopyAckState = lock.withLock { middleware.copyAcks() }

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
        // 승인은 실행을 세우고 로봇 명령으로 이어진다. 발행하는 리더만 받는다(HTTP 의 변경 요청과 같은 규칙, 승인 호스트는 HTTP 필터 밖이다).
        if (!leadership.mayIssue()) {
            return@withLock ApprovalOutcome.Refused(ApprovalRefusal.REFUSED_BY_GATE, "${SendAuthority.NOT_ISSUING}: ${leadership.state}")
        }
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
        // 승인으로 설 계획에 플릿 단위가 있으면 미들웨어를 부르지 않는다(제안은 지우지 않는다 — 관문 거절과 같다). 계획은 제출 때 받은 작업 지시를
        // 실행이 있으면 그 실행의 케이퍼빌리티로, 없으면 지금 활성 임무로 세운다(picasso 승인 접수가 그렇게 계획한다).
        proposedOrders[attempt.jobOrderId]?.let { order ->
            fleetPlanRefusal(order, existing?.capability ?: catalog.active(order.workMasterId)?.capability)?.let {
                return@withLock ApprovalOutcome.Refused(ApprovalRefusal.REFUSED_BY_GATE, "${HostRequests.FLEET_PORT_ABSENT}: $it")
            }
        }
        val outcome = approve(middleware, attempt)
        if (outcome is ApprovalOutcome.Approved) {
            proposedOrders.remove(attempt.jobOrderId)
            if (!existed && excepted) {
                // 표시 기체의 회복 실행은 일지·판단 기록·표시 연결을 한 트랜잭션에 적는다 — 승인된 조치 열이 복원 기록에서 빠지지 않게.
                val execution = middleware.executions().first { it.executionId == outcome.executionId }
                journalRecovery(execution, markers, attempt.approver.id)
                trialProposals.remove(attempt.robotId to attempt.jobOrderId)
            } else if (!existed) {
                // 일반 승인 경로도 일지·판단 기록·승인 사실을 한 트랜잭션에 적는다. 실패하면 [unjournaledApprovals] 에 두어 pump·제출·승인을 막고 다음 pump
                // 앞에서 다시 적는다 — 판단 기록만 빠진 채 일지가 커밋되면 그 사이 죽을 때 승인된 조치 열과 소모가 복원 기록에서 빠진다.
                val execution = middleware.executions().first { it.executionId == outcome.executionId }
                try {
                    journalApproval(execution)
                } catch (e: Exception) {
                    unjournaledApprovals += execution.executionId
                    throw journalFailure(execution.executionId, e)
                }
            } else {
                recordReruns()?.let { throw journalFailure(outcome.executionId, it) }
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
        if(version=="CODE"){if(workMaster !in CODE_WORK_MASTERS)throw BadRequest(HostRequests.BAD_REQUEST,"새 임무는 숫자 버전이 필요합니다");return catalog.mission(workMaster,null)}
        val number=version.toIntOrNull()?.takeIf{it>0 && it.toString()==version}
            ?: throw BadRequest(HostRequests.BAD_REQUEST,"고정 임무 버전이 잘못되었습니다")
        val row=store.version(workMaster,number)
            ?: throw BadRequest(HostRequests.BAD_REQUEST,"고정 임무 버전을 찾을 수 없습니다: $workMaster/v$number")
        return catalog.mission(workMaster,row)
    }
    fun eligibility(order:JobOrder,robotIds:List<String>,missionVersion:String?=null):List<HostEligibility> = lock.withLock {
        catalog.withMission(order.workMasterId,selectedMission(order.workMasterId,missionVersion)) {
            refuseFleetPlan(order)
            eligibilityCurrent(order,robotIds)
        }
    }

    /**
     * **플릿 차단의 한 자리.** 플릿 포트가 없는 호스트([hasFleetPort])에서 [capability] 로 [order] 를 계획해 플릿 경로 단위가 하나라도 있으면
     * 그 거절 문구(`FLEET_PORT_ABSENT` 의 detail)를, 아니면 `null` 을 낸다. 실행을 세우는 입구가 모두 이것을 부른다 — 판정·새 접수와
     * 개정([refuseFleetPlan]), 값 없는 승인([attemptApproval]), 기동 복원([resumed]). 받으면 운반이 맡겨지지 않은 채 실행이 선다.
     */
    private fun fleetPlanRefusal(order: JobOrder, capability: LogicalCapability?): String? {
        if (hasFleetPort || capability == null) return null
        val fleet = capability.plan(order).filter { it.route == Route.FLEET }
        if (fleet.isEmpty()) return null
        return "${HostRequests.FLEET_PORT_ABSENT_DETAIL}: ${order.workMasterId} 의 플릿 운반 단위 ${fleet.joinToString(", ") { it.unitId }}"
    }

    /**
     * [fleetPlanRefusal] 을 400 `FLEET_PORT_ABSENT`([HostRequests.FLEET_PORT_ABSENT])로 던진다. [capability] 의 기본은 고른 임무다(고른 임무
     * 아래, [StoredMissionCatalog.withMission] 에서 부른다). 코드 임무 `DeliverContainer` 도 이 길로 거절한다(계획이 늘 플릿 단위다).
     */
    private fun refuseFleetPlan(order: JobOrder, capability: LogicalCapability? = catalog.active(order.workMasterId)?.capability) {
        fleetPlanRefusal(order, capability)?.let { throw BadRequest(HostRequests.FLEET_PORT_ABSENT, it) }
    }

    /**
     * **플릿 전용 계획의 기체 규칙(D2(a)).** 고른 임무로 [order] 를 계획해 로봇 단위가 없고 플릿 단위가 있으면([fleetOnly]) 요청 후보가 **정확히 하나**
     * 여야 한다 — 여럿이거나 비면 400 [HostRequests.FLEET_ONLY_CANDIDATES] 로 거절한다. 호스트가 그중 아무 기체를 고르지 않는다: 스킬 판정이
     * «해당 없음» 이라 비용 순위로 고르면 근거 없는 고르기가 된다. 그 하나는 picasso 접수가 요구해 묶일 뿐 실행 주체가 아니다(picasso 한계
     * «§15.326 · 기체 묶임»). 등록·점유·기존 제한은 그 하나에 그대로 건다([judge]). 새 접수에만 부른다 — 실행이 있는 작업 지시는 그 실행의 기체다.
     */
    private fun refuseFleetOnlyCandidates(order: JobOrder, candidates: List<String>) {
        val capability = catalog.active(order.workMasterId)?.capability ?: return
        if (!fleetOnly(capability.plan(order))) return
        val distinct = candidates.distinct()
        if (distinct.size == 1) return
        throw BadRequest(
            HostRequests.FLEET_ONLY_CANDIDATES,
            "${HostRequests.FLEET_ONLY_CANDIDATES_DETAIL}: ${order.workMasterId} 의 요청 후보 ${distinct.size}개" +
                (if (distinct.isEmpty()) "" else "(${distinct.joinToString(", ")})"),
        )
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
        catalog.withMission(order.workMasterId,selectedMission(order.workMasterId,missionVersion)) {
            // 새 접수는 고른 임무로, 개정은 그 실행이 쥔 케이퍼빌리티로 계획해 거른다. 개정이 아닌 재전송(같은 판 IDEMPOTENT, 낮은 판 거부 등)은
            // 계획을 새로 세우지 않으므로 거르지 않는다.
            val existing = middleware.executions().firstOrNull { it.order.jobOrderId == order.jobOrderId }
            when {
                existing == null -> {
                    refuseFleetPlan(order)
                    refuseFleetOnlyCandidates(order, candidates)
                }
                revises(existing, order) -> refuseFleetPlan(order, existing.capability)
            }
            submitCurrent(order,candidates,confirmations)
        }
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
        // 실행을 세우지 못한 제출의 작업 지시는 승인이 미리 계획해 보도록 든다([proposedOrders]). 실행이 서면 지운다.
        if (assigned is Middleware.Submission.Rejected || assigned is Unassigned) proposedOrders[order.jobOrderId] = order
        else proposedOrders.remove(order.jobOrderId)
        when (val submission = assigned) {
            is Middleware.Submission.Accepted -> {
                if (!existed) {
                    journal(submission.execution)
                    persistRecord(submission.execution.executionId)
                } else {
                    // 받은 리비전의 개정판 행·판단 기록·리비전 제거 사건을 한 트랜잭션에 적는다([recordReruns]).
                    recordReruns()?.let { throw journalFailure(submission.execution.executionId, it) }
                }
                val removal = if (!existed) null else middleware.revisionRemovals().lastOrNull {
                    it.executionId == submission.execution.executionId && it.toVersion == submission.execution.order.version
                }
                SubmitOutcome(
                    SubmitResult.ACCEPTED, submission.execution.executionId, submission.execution.robotId, null, emptyList(), excluded,
                    boundRobot = fleetOnly(submission.execution.units),
                    revisionRemoval = removal?.let { RevisionRemovalView.of(instanceId, it) },
                )
            }
            is Middleware.Submission.Idempotent -> SubmitOutcome(
                SubmitResult.IDEMPOTENT, submission.execution.executionId, submission.execution.robotId, null, emptyList(), excluded,
                boundRobot = fleetOnly(submission.execution.units),
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
            throw journalFailure(execution.executionId, e)
        }
        journaled[order.jobOrderId] = order.version
    }

    /**
     * 일지 쓰기 실패를 요청에 돌려줄 예외로. 리스를 쥐지 않았거나 잃은 쓰기([NotAuthority]·[AuthorityLost])는 감싸지 않는다 — REST 가 500
     * `JOURNAL_WRITE_FAILED` 가 아니라 503 `NOT_ISSUING` 과 리스 보유자·세대로 답한다.
     */
    private fun journalFailure(executionId: String, e: Throwable): RuntimeException =
        if (e is AuthorityLost || e is NotAuthority) e as RuntimeException else JournalWriteFailed(executionId, e)

    /**
     * 표시 기체의 회복 실행(값 없는 승인의 표시 예외, [attemptApproval])을 적는다. 실행 일지, 판단 기록, 표시와 실행의 연결을 **한 트랜잭션**에 둔다.
     * 판단 기록을 따로 적으면 그 실패가 경고로 끝나 다음 pump 전에 죽을 때 승인된 조치 열이 복원 기록에서 빠진다. 실패는 [JournalWriteFailed] 다.
     */
    private fun journalRecovery(execution: Middleware.Execution, markers: List<Long>, approverId: String) {
        try {
            journalApproval(execution, markers, approverId)
        } catch (e: Exception) {
            throw journalFailure(execution.executionId, e)
        }
    }

    /**
     * 승인으로 선 새 실행의 실행 일지, 판단 기록(승인 칸에 조치 열과 소모가 든다), 같은 조치 승인 사실(V17), [markers] 가 있으면 표시와 실행의 연결을
     * **한 트랜잭션**에 적는다. 판단 기록은 일지보다 먼저 정해지므로 [changedRecords](일지에 있는 실행만 본다)가 아니라 미들웨어에서 바로 읽는다.
     * 커밋한 뒤에만 [journaled]·[recorded] 를 고친다. 실패하면 예외가 나가고 아무것도 남지 않는다. 호스트 잠금 아래에서 부른다.
     */
    private fun journalApproval(execution: Middleware.Execution, markers: List<Long> = emptyList(), approverId: String? = null) {
        val order = execution.order
        val text = middleware.judgmentRecord(execution.executionId)?.let(JudgmentRecordWire::encode)
        val fact = approvalFact(execution)
        records.inTransaction {
            records.journal(
                order.jobOrderId, execution.robotId, orderJson(order), order.workMasterId, execution.missionVersion,
                middleware.instanceId, execution.executionId,
            )
            text?.let { writeRecord(execution, it) }
            fact?.let { records.approvalFact(instanceId, it) }
            markers.forEach { records.residualHoldRecovery(it, execution.executionId, order.jobOrderId, checkNotNull(approverId)) }
        }
        journaled[order.jobOrderId] = order.version
        text?.let { recorded[order.jobOrderId] = it }
    }

    /**
     * 그 실행의 판단 기록이 든 승인(`RecordedApproval`)을 같은 조치 승인 사실로 옮긴다. 승인으로 서지 않은 실행이면 `null` 이다. 서명은 (기체, 승인된
     * 걸음의 조치 유형 열)이고 승인자 종류로 가르지 않는다(picasso `RemedyDesk.settle` 과 같은 규칙).
     */
    private fun approvalFact(execution: Middleware.Execution): ApprovalFactWrite? =
        middleware.judgmentRecord(execution.executionId)?.approval?.let { a ->
            ApprovalFactWrite(
                execution.executionId, execution.order.jobOrderId, a.robotId, a.steps.map { it.skillType }, a.approver.id, a.approver.kind.name,
                a.at, a.wallClockAt,
            )
        }

    /**
     * [unjournaledApprovals] 를 다시 적는다([journalApproval]). 적었거나 더 적을 것이 없으면(실행이 메모리에 없음, 이미 일지에 있음) 뺀다. 실패하면 남겨
     * pump 를 막고 다음 pump 가 다시 한다. pump 가 막혀 있었으므로 그 실행은 승인 때 그대로이고 판단 기록도 같은 글자다. 호스트 잠금 아래에서 부른다.
     */
    private fun saveApprovals() {
        if (unjournaledApprovals.isEmpty()) return
        val live = middleware.executions().associateBy { it.executionId }
        unjournaledApprovals.toList().forEach { executionId ->
            val execution = live[executionId]
            if (execution == null || execution.order.jobOrderId in journaled) {
                unjournaledApprovals -= executionId
                return@forEach
            }
            try {
                journalApproval(execution)
                unjournaledApprovals -= executionId
            } catch (e: Exception) {
                log.warn("승인으로 선 실행을 적지 못했다, 적을 때까지 pump·제출·승인을 막는다: {} {}", executionId, e.toString())
            }
        }
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
            fleet = fleetLink,
            ledgerCopy = LedgerCopyView.of(middleware.copyAcks(), removalCopyView()),
        )
    }

    /**
     * 재작업을 받을 모양인 묶음(picasso `Middleware.reworkCandidates`, §15.323). 모양·종결·같은 기체의 다른 활성 실행·묶음 규칙은 picasso 의 답
     * 그대로다 — 건너뛴 단위의 술어(`PREDECESSOR_NOT_DONE` ∧ 태스크 빔)를 호스트가 사본으로 들면 picasso 의 묶음 규칙과 갈린다. 호스트는 보존 만료
     * 시각만 덧붙인다.
     *
     * 플릿 단위는 묶음에 들지 않는다 — picasso 의 묶음은 로봇 단위만 뿌리로 삼고, 선행으로 딸려 오는 단위는 같은 반복 그룹 안인데 그룹 안에는
     * 플릿 단위가 올 수 없다(picasso 규칙에 기댄다). 호스트는 따로 거르지 않는다.
     *
     * `FAILED` 는 지금의 종결 기록을 적은 실행만 싣고([settleRecorded]) 보존 만료 시각을 그 기록 시각에서 정한다. 기록을 적기 전이면
     * 만료 시각을 모르므로 싣지 않는다. 한 pump 안에 지난다. `PARTIAL` 은 만료가 없다. picasso 후보는 이 둘 밖의 상태를 내지 않지만, 내면
     * 싣지 않는다(만료를 정할 수 없다). [latest] 는 [latestResponses] 다. 호스트 잠금 아래에서 부른다.
     */
    private fun reworkableTargets(latest: Map<String, JobResponse>): List<ReworkableTargetView> {
        val byId = middleware.executions().associateBy { it.executionId }
        return middleware.reworkCandidates().mapNotNull { candidate ->
            val execution = byId[candidate.executionId] ?: return@mapNotNull null
            val state = execution.physicalState
            val retainedUntil = when {
                state == PhysicalState.PARTIAL -> null
                state == PhysicalState.FAILED && settleRecorded(execution, latest) ->
                    settleMarks.getValue(execution.executionId).at.plus(revisableRetention)
                else -> return@mapNotNull null
            }
            ReworkableTargetView(
                execution.executionId, execution.order.jobOrderId, execution.robotId, candidate.unitId, state.name, retainedUntil,
                candidate.attempt, candidate.bundle, candidate.kind.name, candidate.incidentUnitId,
                execution.units.any { it.unitId in candidate.bundle && it.state == UnitState.FAILED && it.failureClass == REWORK_RECHECK },
            )
        }
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
        // 리비전 제거 사건은 사본 표(V18)에서 읽는다 — 재기동 뒤 다시 지은 실행에도 앞 인스턴스가 받은 제거가 실린다.
        val removed = records.revisionRemovals(chosen.map { it.order.jobOrderId }).groupBy { it.jobOrderId }
        return ExecutionsView(
            instanceId = middleware.instanceId,
            pumpedAt = pumpedAt,
            executions = chosen.map { execution ->
                val decisions = middleware.judgmentRecord(execution.executionId)?.units.orEmpty().associate { it.unitId to it.decision }
                ExecutionView(
                    executionId = execution.executionId,
                    jobOrderId = execution.order.jobOrderId,
                    workMasterId = execution.order.workMasterId,
                    missionVersion = execution.missionVersion,
                    robotId = execution.robotId,
                    physicalState = execution.physicalState.name,
                    units = execution.units.map { unit ->
                        val decision = decisions[unit.unitId]?.let {
                            UnitDecisionView(it.decision.name, it.approver.id, it.approver.kind.name, it.at, it.attempt)
                        }
                        UnitView(unit.unitId, unit.skillType, unit.state.name, unit.reached.name, unit.attempt, decision, unit.route.name)
                    },
                    jobResponse = responses[execution.executionId]?.let(::view),
                    restoredFrom = restoredFrom[execution.executionId],
                    cancel = cancels[execution.executionId]?.let(CancelRecordView::of),
                    permitBlock = execution.permitBlock?.let(PermitBlockView::of),
                    blockedFaults = execution.blockedBy.map(FaultDetailView::fromContract),
                    releases = released[execution.order.jobOrderId].orEmpty().map(::releaseView),
                    boundRobot = fleetOnly(execution.units),
                    revisionRemovals = removed[execution.order.jobOrderId].orEmpty().map(RevisionRemovalView::of),
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

    /**
     * 조치 탐색 대장(V17). 지금 인스턴스의 것은 호스트 잠금 아래에서 미들웨어 대장을 picasso 줄로 옮기고, 이전 인스턴스의 것은 잠금 밖에서 사본 표로
     * 읽는다. 둘 다 최근부터 많아야 [limit] 개다. 줄은 picasso 글자 그대로라 `WITHHELD` 줄에 `steps` 가 없다.
     */
    fun remedySearches(limit: Int): RemedySearchesView {
        val (total, live) = lock.withLock {
            val all = remedySearches(middleware)
            all.size to all.asReversed().take(limit).map { record ->
                val line = LedgerExport.remedySearchLine(record)
                RemedySearchLineView(
                    instanceId, record.searchId, record.jobOrderId, record.robotId, json.readTree(line).path("outcome").asText(),
                    LedgerExport.LINE_SCHEMA_VERSION, line,
                )
            }
        }
        return RemedySearchesView(
            instanceId = instanceId,
            total = total,
            searches = live,
            earlierTotal = records.earlierSearchCount(instanceId),
            earlier = records.earlierSearches(instanceId, limit).map(RemedySearchLineView::of),
        )
    }

    /**
     * 같은 조치가 [atLeast] 번 이상 승인된 것 — 승인 사실 표의 합산(인스턴스를 넘어, V17)이다. picasso `repeatedRemedies` 는 이 인스턴스 안의 수라
     * 쓰지 않는다. DB 만 읽고 호스트 잠금을 잡지 않는다.
     */
    fun repeatedRemedies(atLeast: Int): RepeatedRemediesView =
        RepeatedRemediesView(atLeast, records.repeatedRemedies(atLeast).map(RepeatedRemedyView::of))

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
     *
     * 두 대장의 사본(V17, picasso §15.327): 같은 트랜잭션에 새 인시던트의 첫 줄, 줄이 바뀐 인시던트(검토·판단)의 개정 줄, 새 탐색의 줄을 picasso
     * `LedgerExport` 의 글자 그대로 적는다. 고르는 것은 개수가 아니라 식별자다([copiedLines]·[copiedSearches]). **커밋한 뒤에** 그 id 로
     * `ackCopiedIncidents`·`ackCopiedSearches` 를 부른다 — 커밋 전에 부르면 그 사이 죽을 때 상한이 줄을 내려 사본에서 빠진다. 트랜잭션이 실패하면
     * 확인하지 않으므로 줄이 picasso 에 남아 다음 pump 가 다시 적는다(유일 열쇠로 멱등). 파일 게시([publish])는 커밋 뒤, 놓기 앞이다.
     */
    private fun recordAfterPump() {
        val pending = middleware.pending()
        val incidents = middleware.incidents()
        val searches = remedySearches(middleware)
        val responses = middleware.responses()
        val fresh = incidents.filter { it.incidentId !in copiedLines }
        // 줄이 같은 새 객체 — 적을 것은 없지만 picasso 가 그 id 를 다시 확인 대기로 올렸을 수 있다(`revised`). 이미 커밋된 줄이므로 확인만 다시 보낸다.
        val unchanged = mutableListOf<CopiedLine>()
        val revised = incidents.mapNotNull { bundle ->
            val copied = copiedLines[bundle.incidentId] ?: return@mapNotNull null
            if (copied.bundle === bundle) return@mapNotNull null
            val line = incidentLine(bundle)
            if (line == copied.line) {
                unchanged += copied.copy(bundle = bundle)
                null
            } else {
                CopiedLine(bundle, copied.revision + 1, line)
            }
        }
        val freshSearches = searches.filter { it.searchId !in copiedSearches }
        val resolved = incidents.filter { it.resolution != null && it.incidentId !in copiedResolutions }
        val latest = latestResponses(responses)
        val settled = middleware.executions().filter {
            !it.physicalState.pumps && it.order.jobOrderId in journaled && !settleRecorded(it, latest)
        }
        val judgments = changedRecords(latest)
        // 받은 리비전의 제거 사건은 보통 [recordReruns] 가 개정판 행과 함께 적었다. 남은 것(그 트랜잭션 밖에서 선 것)이 있으면 여기서 적는다.
        val freshRemovals = freshRemovals()
        // 확정했으나 못 적은 수신자 재시작 의심과 붙음을 본 의심의 시스템 해제도 이 트랜잭션에 넣는다 — 정착·판정만 커밋되고 의심을 잃는 일이 없게.
        val generation = leadership.claim()?.generation
        val suspicions = if (generation == null) emptyList() else leadership.restartHolds.unpersisted()
        // 붙음을 본 의심의 시스템 해제는 따로 적는다 — 실패해도 정착·판단 기록·응답을 롤백하지 않고 다음 pump 에 다시 한다(그동안 그 기체는 막혀 있다).
        if (generation != null) commitSystemReleases(generation)
        if (
            pending.isEmpty() && fresh.isEmpty() && revised.isEmpty() && freshSearches.isEmpty() && resolved.isEmpty() &&
            settled.isEmpty() && judgments.isEmpty() && freshRemovals.isEmpty() && suspicions.isEmpty()
        ) {
            reackUnchanged(unchanged)
            publish(incidents, searches, responses)
            forgetSettled(latest)
            return
        }

        val held = heldIncidents(incidents)
        // 정착 이력(HA 설계 기록 §10): 정착 이벤트에 그때의 실행 본문을 같이 적는다. 화면은 정착 실행을 메모리가 아니라 이것에서 읽는다.
        val snapshots = if (settled.isEmpty()) emptyList() else view(settled).executions.map(json::writeValueAsString)
        val copies = fresh.map { it to json.writeValueAsString(IncidentViews.detail(instanceId, it, it.incidentId in held, unitStateOf(it))) }
        val freshLines = fresh.associate { it.incidentId to incidentLine(it) }
        val searchRows = freshSearches.map { record ->
            // 줄은 picasso 글자 그대로다 — 가림(`WITHHELD`)의 걸음을 제안 표에서 꺼내 채우지 않는다. outcome 칸은 그 줄에서 읽는다.
            val line = LedgerExport.remedySearchLine(record)
            SearchCopyWrite(record.searchId, record.jobOrderId, record.robotId, json.readTree(line).path("outcome").asText(), line)
        }
        val marked = mutableListOf<ResidualHoldRow>()
        val suspicionIds = mutableMapOf<String, Long>()
        try {
            records.inTransaction {
                marked.clear()
                suspicionIds.clear()
                suspicions.forEach { h -> suspicionIds[h.taskId] = records.recordRestartSuspicion(checkNotNull(generation), instanceId, suspicionWrite(h)) }
                // 판단 기록을 송신 기록과 같이 커밋한다. 상위가 받은 응답의 판정은 기록에도 있다. 이 pump 의 것을 한 문장으로 적는다.
                records.judgmentRecords(
                    instanceId, JudgmentRecordWire.SCHEMA_VERSION,
                    judgments.map { (execution, text) -> HostRecords.JudgmentRecordWrite(execution.order.jobOrderId, execution.executionId, text) },
                )
                pending.forEach(::logResponse)
                copies.forEach { (bundle, detail) ->
                    records.copyIncident(
                        instanceId, bundle.incidentId, bundle.executionId, bundle.jobOrderId, bundle.unitId, detail,
                        LedgerExport.LINE_SCHEMA_VERSION, freshLines.getValue(bundle.incidentId),
                    )
                }
                records.copyIncidentRevisions(
                    instanceId, LedgerExport.LINE_SCHEMA_VERSION,
                    revised.map { IncidentRevisionWrite(it.bundle.incidentId, it.revision, it.line) },
                )
                records.copySearches(instanceId, LedgerExport.LINE_SCHEMA_VERSION, searchRows)
                writeRemovals(freshRemovals)
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
        suspicionIds.forEach { (taskId, id) ->
            leadership.restartHolds.persisted(taskId, id, checkNotNull(generation))
            log.warn("수신자 재시작 의심 #{} 을 적었다: 태스크 {}", id, taskId)
        }
        fresh.forEach { copiedLines[it.incidentId] = CopiedLine(it, 1, freshLines.getValue(it.incidentId)) }
        revised.forEach { copiedLines[it.bundle.incidentId] = it }
        copiedSearches += freshSearches.map { it.searchId }
        removalsCommitted(freshRemovals)
        judgments.forEach { (execution, text) -> recorded[execution.order.jobOrderId] = text }
        copiedResolutions += resolved.map { it.incidentId }
        // 대장에서 내린 id 는 다시 오지 않는다(인스턴스 안의 셈) — 남은 id 만 든다.
        val present = incidents.mapTo(HashSet()) { it.incidentId }
        copiedLines.keys.retainAll(present)
        copiedResolutions.retainAll(present)
        copiedSearches.retainAll(searches.mapTo(HashSet()) { it.searchId })
        // 커밋 뒤 확인 — pump 와 같은 스레드·잠금이다. 확인한 줄은 picasso 의 다음 정리에서 상한대로 내린다.
        (fresh.map { it.incidentId } + revised.map { it.bundle.incidentId }).takeIf { it.isNotEmpty() }?.let(middleware::ackCopiedIncidents)
        reackUnchanged(unchanged.filter { it.bundle.incidentId in present })
        freshSearches.takeIf { it.isNotEmpty() }?.let { middleware.ackCopiedSearches(it.map(RemedySearchRecord::searchId)) }
        val committedAt = clock.now()
        settled.forEach { settleMarks[it.executionId] = SettleMark(latest[it.executionId]?.jobResponseId, committedAt) }
        openHolds += marked
        pending.forEach { middleware.ack(it.jobResponseId) }
        publish(incidents, searches, responses)
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

    /**
     * picasso `Middleware.remedySearches` 를 부르는 자리. 이 호스트는 가림(`withholdEvery`)을 켜지 않으므로 `WITHHELD` 탐색이 서지 않는다 — 시험이 그
     * 줄을 사본 경로에 주입하려고 바꾼다. 호스트 잠금 아래에서 부른다.
     */
    @Volatile
    internal var remedySearches: (Middleware) -> List<RemedySearchRecord> = { it.remedySearches() }

    /** picasso `Middleware.forget` 를 부르는 자리. 시험이 거절과 모름을 주입하려고 바꾼다. 호스트 잠금 아래에서 부른다. */
    @Volatile
    internal var forget: (String) -> ForgetOutcome = { middleware.forget(it) }

    /** 놓은 실행의 호스트 쪽 맵을 지운다. 재기동에 넘길 것은 모두 DB 에 있다(일지·정착 본문·판단 기록·취소·잔여 파지 표시). */
    private fun dropForgotten(execution: Middleware.Execution) {
        val jobOrderId = execution.order.jobOrderId
        settleMarks.remove(execution.executionId)
        unsavedDecisions.remove(execution.executionId)
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
     * 판단 기록 하나를 지금 적는다(수락 직후, 결함 수용 직후). 못 적으면 경고만 남긴다 — 그 판정은 이미 미들웨어에 섰고, 다음 pump 뒤
     * 기록이 같은 글자를 다시 적는다(그 pump 가 성공하기 전에 프로세스가 죽으면 그 판정은 넘어오지 않는다). 운영자 판단은 이것이 아니라
     * [saveDecisions] 로 적는다 — 못 적으면 pump 를 막는다.
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

    /**
     * [unsavedDecisions] 의 판단 기록을 적는다. 적었거나 더 적을 것이 없으면(같은 글자를 이미 적음, 실행이 메모리에 없음) 뺀다. 실패하면 남겨 pump 를
     * 막고 다음 pump 가 다시 한다. 호스트 잠금 아래에서 부른다.
     */
    private fun saveDecisions() {
        if (unsavedDecisions.isEmpty()) return
        val live = middleware.executions().mapTo(HashSet()) { it.executionId }
        unsavedDecisions.retainAll(live)
        val pending = changedRecords().filter { it.first.executionId in unsavedDecisions }.associateBy { it.first.executionId }
        unsavedDecisions.toList().forEach { executionId ->
            val changed = pending[executionId]
            if (changed == null) {
                unsavedDecisions -= executionId
                return@forEach
            }
            try {
                writeRecord(changed.first, changed.second)
                recorded[changed.first.order.jobOrderId] = changed.second
                unsavedDecisions -= executionId
            } catch (e: Exception) {
                log.warn("판단 뒤 판단 기록을 적지 못했다, 적을 때까지 pump 를 막는다: {} {}", executionId, e.toString())
            }
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
     * 대상은 보류 단위이거나, 보류가 아닌 재작업 묶음의 단위다(picasso §15.323 — 건너뛴 대상, 시도 뒤 실패한 점검·이동·`pick_place` 계열, 그것을
     * 기다려 건너뛴 단위, 그것이 기다린 파지 효과 없는 선행). 묶음은 `REWORK` 만 받고 묶음의 단위를 함께 새 시도로 되돌린다.
     *
     * Resolved 이면 판단이 붙은 인시던트 id 를 같이 낸다. 어느 인시던트에 붙는지는 picasso 가 정한다(`IncidentLog.noteResolution`). 보류
     * 단위면 그 단위의 판단 없는 가장 최근 인시던트이고, 묶음이면 묶음 안의 판단을 기다리던 인시던트마다 붙는다(건너뛴 쌍이면 선행 이동의 것 —
     * 건너뛴 점검은 인시던트를 열지 않았다). 그래서 요청한 단위 id 로 고르지 않고, 부르기 전 그 실행의 판단 없는 인시던트 가운데 부른 뒤 판단이
     * 새로 붙은 것 가운데 마지막을 고른다. 호스트 잠금 아래라 그 사이에 다른 판단이 끼지 않는다. 판단이 아무 인시던트에도 안 붙었으면 `null` 이다.
     *
     * Refused 는 에이전트 승인자일 때와 보류가 아닌 재작업을 지금 받지 않을 때 난다. 앞의 것은 REST 가 늘 `PERSON` 승인자를 만들므로 이
     * 메서드를 직접 부를 때만 난다. 뒤의 것은 확인(`CONFIRM_DONE`) 요청, 취소한 실행, 실행이 아직 종결하지 않음, 같은 기체의 다른 활성 실행,
     * 발행 관문, 물리 관문(손·목적지·출발·예약·구역, 스냅숏을 아직 못 읽음 포함), 묶음 규칙, `UNVERIFIED`·조치 단위이며 REST 로도 난다. `detail` 은
     * picasso 사유 문장 그대로이고 호출한 쪽이 이 문장을 뜯어 분기해서는 안 된다.
     * NotHeld 는 실행이 없거나 그 단위가 보류도 묶음의 모양도 아닐 때 난다. 그 실행이 picasso 메모리에 없고 이 인스턴스의 종결 기록이
     * 있으면, 곧 이 인스턴스가 이빅션한 실행이면 `detail` 에 [EVICTED] 를 싣는다. 그 밖의 NotHeld 의 `detail` 은 `null` 이다.
     *
     * picasso `resolve` 는 판단으로 실행의 보류가 걷히면 그 자리에서 작업 응답 하나(`physicalState` `RUNNING`)를 낸다. 다른 단위가 아직
     * 보류이면 실행은 보류 그대로이고 내지 않는다. 다음 pump 가 그 응답을 송신 기록과 결과 outbox 에 한 번 적는다([release] 와 같다).
     * 묶음 재작업은 종료한 실행(`PARTIAL`·`FAILED`)을 `RUNNING` 으로 되돌리고 작업 응답 하나를 낸다. `FAILED` 는 종결을 적은 실행의
     * 재실행이라 [recordReruns] 가 재실행 이벤트를 적고, `PARTIAL` 은 종결로 적은 적이 없어 적지 않는다. 어느 쪽이든 판단 기록은 이 호출 안에서
     * 적고, 못 적으면 적을 때까지 pump 와 신규 제출을 막는다([unsavedDecisions]).
     */
    fun resolve(executionId: String, unitId: String, decision: OperatorDecision, approver: Approver, requestId: String?): ResolveView =
        lock.withLock {
            val open = middleware.incidents().filter { it.executionId == executionId && it.resolution == null }.map { it.incidentId }.toSet()
            when (val outcome = middleware.resolve(executionId, unitId, decision, approver)) {
                ResolveOutcome.Resolved -> {
                    val target = middleware.incidents().lastOrNull { it.incidentId in open && it.resolution != null }?.incidentId
                    // 종결을 적은 실행이 판단으로 다시 돌면 재실행과 판단 기록을 같이 적는다. 못 적으면 pump 가 다시 적을 때까지 pump 를 막는다.
                    recordReruns()
                    // 판단 기록을 이 호출 안에서(다음 pump 전에) 적는다. 못 적으면 적을 때까지 pump 를 막는다([unsavedDecisions]).
                    unsavedDecisions += executionId
                    saveDecisions()
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

    /** picasso 보존 상한으로 내린 수(`Middleware.retentionEvictions`, 인스턴스 수명). 시험이 놓은 작업 지시 표시가 실제로 내려갔는지 본다. */
    internal fun retentionEvictions(): RetentionEvictions = lock.withLock { middleware.retentionEvictions() }

    /** 놓은 작업 지시 표시의 지금 상태(`Middleware.forgottenOrders`, picasso §15.324). [RETENTION] 아래에서 `refusedForgets` 는 늘 0 이다. */
    internal fun forgottenOrders(): ForgottenOrdersState = lock.withLock { middleware.forgottenOrders() }

    /** 이 (기체, 작업 지시)에 서 있는 제안의 대상(`Middleware.proposalTarget`). 시험이 승인 전에 제안이 아직 서 있음을 고정하려고 읽는다. */
    internal fun proposalTarget(robotId: String, jobOrderId: String): ProposalTarget? = lock.withLock { middleware.proposalTarget(robotId, jobOrderId) }

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
        // 끝나지 않은 플릿 단위가 있는 실행의 표시는 해소하지 않는다. 빈손 확인은 기체의 손만 보므로 플릿이 멈췄다는 증거가 못 된다.
        openFleetUnits(hold.jobOrderId).takeIf { it.isNotEmpty() }?.let { open ->
            return@withLock ClearOutcome.FleetUnitOpen("$FLEET_UNIT_OPEN: ${hold.jobOrderId} 의 끝나지 않은 플릿 운반 단위 ${open.joinToString(", ")}")
        }
        // 다시 짓지 못한 행(미룬·포기)은 메모리에 실행이 없어 단위 상태를 모른다 — 운영자 종결과 같은 일지 판정을 건다(대칭).
        unrestoredFleetRisk(hold.jobOrderId)?.let { risk ->
            return@withLock ClearOutcome.FleetUnitOpen("$FLEET_UNIT_OPEN: ${hold.jobOrderId} 는 다시 짓지 못한 실행이고 $risk")
        }
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
        among.filter { it.order.jobOrderId !in journaled || rerunOf(it) != null || it.executionId in unsavedDecisions }.map { it.executionId }

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
        // 값 없는 승인이 이미 선 실행에 조치 열을 실으면 개정판으로 다시 돈다. 그 승인 사실도 같은 트랜잭션에 적는다(같은 승인은 열쇠가 한 번만 받는다).
        val facts = reruns.filter { it.revised }.mapNotNull { approvalFact(it.execution) }
        // 받은 리비전이 손대지 않은 단위를 지웠으면 그 사건(picasso §15.329)도 같은 트랜잭션에 적는다 — 판단 기록은 지운 사실을 싣지 않는다.
        val removals = freshRemovals()
        try {
            records.inTransaction {
                facts.forEach { records.approvalFact(instanceId, it) }
                reruns.forEach { rerun ->
                    val execution = rerun.execution
                    val jobOrderId = execution.order.jobOrderId
                    if (rerun.revised) records.revision(jobOrderId, execution.order.version, orderJson(execution.order), instanceId, execution.executionId)
                    if (rerun.reopened) records.event(jobOrderId, JournalEventKind.REOPENED, instanceId, execution.executionId, execution.physicalState.name)
                }
                judgments.forEach { (execution, text) -> writeRecord(execution, text) }
                writeRemovals(removals)
            }
        } catch (e: Exception) {
            log.warn("다시 돈 실행을 적지 못했다, 적을 때까지 pump 를 막는다: {} {}", ids, e.toString())
            return e
        }
        removalsCommitted(removals)
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
        val planned = active.capability.plan(order)
        if (fleetOnly(planned)) return judgeBound(robotId, applied, revising)
        val needed = planned.filter { it.route == Route.ROBOT }.map { it.skillType }.toSortedSet()
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
                // 로봇 계획의 판정은 이 값을 내지 않는다(플릿 전용 계획은 [judgeBound]).
                SkillFit.FIT, SkillFit.NOT_APPLICABLE -> Unit
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

    /**
     * 플릿 전용 계획의 기체 판정(D2(a)). 그 기체는 **묶인 기체(실행 주체 아님)** 다. 스킬 판정은 [SkillFit.NOT_APPLICABLE] 이고 선언을 못 읽은 것은
     * 거절 사유가 아니다(할 스킬이 없다). 등록과 기존 제한([robotRestrictions] — 점유·복원 못 한 행·잔여 파지 표시·현장 시간값 미적용·일지 막힘)은
     * 그대로 건다. 등록은 그 기체의 스냅숏을 지금 한 번 읽어 본다 — 못 읽으면 이 현장의 기체인지 모르므로 [UNREGISTERED_REASON] 으로 뺀다. 관문
     * 확인용([HostEligibility.gateTrial])은 쓰지 않는다(로봇 단위가 없어 사슬 검사가 없고 표시가 막는다).
     */
    private fun judgeBound(robotId: String, applied: Boolean, revising: String?): HostEligibility {
        val declared = robots.capabilities(robotId)?.skillsList?.map { it.skillType }?.toSet()
        val registered = runCatching { robots.snapshot(robotId) }.getOrNull() != null
        val reasons = buildList {
            if (!registered) add(UNREGISTERED_REASON)
            addAll(robotRestrictions(robotId, applied, revising))
        }
        return HostEligibility(
            robotId, SkillFit.NOT_APPLICABLE, emptyList(), runningOn(robotId, revising), passed = reasons.isEmpty(), reasons = reasons,
            requiredSkills = emptyList(), availableSkills = declared?.sorted(), gateTrial = false, boundRobot = true,
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
     * 6. 대상. 종결하지 않은 포기 행([unclosed])이거나, 기체 스냅숏을 못 읽어 미룬 행(`RESUME_SNAPSHOT_UNREADABLE`). 그 밖의 행은
     *    [CloseResult.NOT_CLOSABLE].
     * 7. [seen] 이 지금 일지의 (인스턴스, 실행)과 다르면 [CloseResult.TARGET_CHANGED].
     * 8. 정지 확인. 그 기체의 스냅숏에서 그 작업 지시의 종료하지 않은 태스크를 본다.
     *    - 읽히고 그런 태스크가 없다: 포기 행은 근거 `SNAPSHOT`. 미룬 행은 다음 pump 가 리빌드할 행이라 [CloseResult.NOT_CLOSABLE] 이다
     *      (닫으면 복원할 수 있는 실행을 버린다. 미룬 행은 스냅숏을 못 읽을 때 증언으로만 종결한다).
     *    - `RETRIABLE`·`NEEDS_INTERVENTION` 밖의 태스크가 있다: [CloseResult.TASK_LIVE]. 기체가 움직이거나 명령이 걸려 있으니 증언도 받지 않는다.
     *    - 스냅숏을 못 읽었거나 남은 태스크가 모두 사람을 기다리는 상태다: [attestedStopped] 가 거짓이면 [CloseResult.STOP_UNCONFIRMED], 참이면
     *      근거 `ATTESTED`.
     *
     * 이 호스트는 리스를 쥐었으므로(종결은 변경 요청이라 발행하는 리더만 받는다) 행을 든 앞 인스턴스의 심장박동은 보지 않는다. 마지막 차례는 메모리가
     * 아니라 DB 에서 읽고, 다른 인스턴스가 종결한 행은 이 인스턴스의 메모리에서도 뺀다([forgetClosed]).
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
        } else if (waiting != null) {
            val row = restoreRows[waiting.index]
            restoreReason = row.reason.orEmpty()
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
        // 플릿 운반 단위가 든 행은 종결하지 않는다. 다시 짓지 못한 실행이라 그 운반이 끝났는지 모르고, 아래 정지 확인은 기체의 태스크만 본다 —
        // 플릿이 멈췄다는 증거가 못 된다. 해소는 플릿 포트를 붙인 호스트의 복원(같은 참조로 그 운반에 다시 붙음)이다.
        journalFleetRisk(journal)?.let { risk ->
            return answer(CloseResult.NOT_CLOSABLE, "$FLEET_UNIT_OPEN: $risk. 기체의 정지 확인은 플릿 정지의 증거가 아니다")
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
        // 종결과 그 행의 판정 없는 송신 흔적의 의심을 한 트랜잭션에 적는다 — 종결만 커밋되고 의심을 잃으면 다음 보유자는 종결한 행을 복원·포기 목록에서
        // 읽지 않아 그 기체를 막을 근거가 없다. 미룬 행이면 흔적을 지금 읽어 의심으로 짓고(못 읽으면 종결하지 않는다), 포기한 행의 아직 못 적은 의심도 함께 적는다.
        val generation = leadership.claim()?.generation
        val abandonedNow = if (discriminate && leadership.restartHolds.isDeferred(jobOrderId)) {
            try {
                abandonedHolds(journal)
            } catch (e: Exception) {
                return answer(CloseResult.NOT_CLOSABLE, "그 작업 지시의 송신 흔적을 읽지 못해 종결하지 않았다(다시 시도): $e")
            }
        } else {
            emptyList()
        }
        val pendingSuspicions = abandonedNow + leadership.restartHolds.unpersisted().filter { it.jobOrderId == jobOrderId }
        val suspicionIds = mutableMapOf<String, Long>()
        val closed = records.inTransaction {
            suspicionIds.clear()
            pendingSuspicions.forEach { h -> suspicionIds[h.taskId] = records.recordRestartSuspicion(checkNotNull(generation), instanceId, suspicionWrite(h)) }
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
        leadership.restartHolds.hold(abandonedNow)
        suspicionIds.forEach { (taskId, id) -> leadership.restartHolds.persisted(taskId, id, checkNotNull(generation)) }
        leadership.restartHolds.clearDeferred(jobOrderId)
        forgetClosed(journal, closed)
        return CloseView(CloseResult.CLOSED, jobOrderId, basis, closed.responseId, null, requestId)
    }

    /**
     * 종결 행([closed])이 있는 작업 지시를 이 인스턴스의 메모리에서 뺀다. 이 인스턴스가 종결했을 때와 다른 인스턴스가 종결한 것을 본 때 둘 다 쓴다.
     * 종결한 행은 리빌드하지 않는다. `ATTESTED` 는 스냅숏이 동의할 때까지 그 기체를 판정에서 뺀다([attested]). 호스트 잠금 아래에서 부른다.
     */
    private fun forgetClosed(journal: JournalRow, closed: CloseRow) {
        val jobOrderId = journal.jobOrderId
        // 미룬 채 종결한 행은 판정 없는 송신 흔적을 해제 가능한 의심으로 넘긴 뒤 미룬 복원의 보류를 걷는다(의심 ID 없는 영구 보류가 남지 않게).
        if (leadership.restartHolds.isDeferred(jobOrderId)) abandonRestartHolds(journal)
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
     * 실행 목록 페이지의 `unrestored`(결함 14). 행은 메모리에서 읽는다. 포기 행은 재기동을 몇 번 넘어도 남고, 미룬 행과 증언으로 종결했으나 스냅숏이 아직 동의하지 않은
     * 행([attested])이 뒤따른다. 호스트 잠금 아래에서 부른다.
     */
    private fun unrestoredRows(): List<UnrestoredRowView> {
        val excluded = gaveUp.mapTo(HashSet()) { it.jobOrderId }
        val abandoned = unclosed.values.map {
            UnrestoredRowView(
                it.row.jobOrderId, it.row.robotId, it.previous.instanceId, it.previous.executionId, RestoreResult.GAVE_UP.name, it.reason, it.since,
                closable = true, notClosableReason = null, close = null, robotExcluded = it.row.jobOrderId in excluded,
            )
        }
        val waiting = deferred.map {
            val row = restoreRows[it.index]
            // 플릿 포트가 없어 미룬 행은 닫을 수 없다([closeLocked]). 플릿 포트를 붙인 기동에서 다시 지어진다.
            val held = row.reason?.startsWith(HostRequests.FLEET_PORT_ABSENT) == true
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

    /**
     * 일지 행([journal])의 실행이 플릿 운반을 했을 수 있는가. 아니라고 확인되면 `null`, 그럴 수 있으면 그 까닭(문장)이다. 판정은 그 행의 **대상 임무
     * 버전**(일지의 `missionVersion`, 없으면 코드 정의)으로만 한다 — 같은 WorkMaster 의 다른 버전은 보지 않는다.
     *
     * - 대상 버전을 모르거나(버전 행 유실, 코드 정의가 없는 WorkMaster) 그 정의를 못 읽으면 «모름» 이고 막는다.
     * - 대상 버전이 로봇 전용(정의에 플릿 노드가 없음, 코드 정의면 `DeliverContainer` 가 아님)으로 확인되면 작업 지시를 못 읽어도 막지 않는다.
     * - 대상 버전이 플릿 노드를 들면 마지막으로 받은 판(개정판이 있으면 그것)을 계획해 플릿 단위가 있으면 막고, 계획을 못 지으면 «모름» 으로 막는다.
     */
    private fun journalFleetRisk(journal: JournalRow): String? {
        val where = journal.missionVersion?.let { "${journal.workMasterId} 버전 $it" } ?: "${journal.workMasterId} 코드 정의"
        val target = runCatching {
            val row = journal.missionVersion?.let { store.version(journal.workMasterId, it) ?: error("임무 버전 행이 없다") }
            val capability = catalog.mission(journal.workMasterId, row).capability
            val fleet = if (row == null) {
                journal.workMasterId == DeliverContainer.WORK_MASTER
            } else {
                when (val parsed = MissionDefinitionParser.parse(row.definition)) {
                    is MissionParse.Parsed -> MockRunner.hasFleet(parsed.definition)
                    is MissionParse.Unreadable -> error("정의를 읽지 못했다")
                }
            }
            capability to fleet
        }.getOrNull() ?: return "대상 임무($where)를 모르거나 그 정의를 읽지 못해 플릿 운반 단위를 모른다(모름은 플릿으로 본다)"
        val (capability, fleet) = target
        if (!fleet) return null
        val planned = runCatching {
            val revision = records.latestRevision(journal.jobOrderId)
            capability.plan(HostRequests.jobOrder(json.readTree(revision?.jobOrder ?: journal.jobOrder)))
        }.getOrNull() ?: return "대상 임무($where)가 플릿 운반을 쓰는데 작업 지시를 계획하지 못해 단위를 모른다(모름은 플릿으로 본다)"
        val units = planned.filter { it.route == Route.FLEET }.map { it.unitId }.distinct()
        return if (units.isEmpty()) null else "플릿 운반 단위 ${units.joinToString(", ")} 가 든 실행이다"
    }

    /** 그 작업 지시가 다시 짓지 못한 행(미룬·포기)이면 [journalFleetRisk], 아니면 `null`. 호스트 잠금 아래에서 부른다. */
    private fun unrestoredFleetRisk(jobOrderId: String): String? {
        if (middleware.executions().any { it.order.jobOrderId == jobOrderId }) return null
        if (deferred.none { it.row.jobOrderId == jobOrderId } && jobOrderId !in unclosed) return null
        return records.journalRow(jobOrderId)?.let(::journalFleetRisk)
    }

    /** 그 작업 지시의 메모리 실행에서 끝나지 않은 플릿 경로 단위 id([FLEET_ENDED_UNIT_STATES] 밖). 실행이 없으면 빈 목록이다. 호스트 잠금 아래에서 부른다. */
    private fun openFleetUnits(jobOrderId: String): List<String> = middleware.executions()
        .filter { it.order.jobOrderId == jobOrderId }
        .flatMap { e -> e.units.filter { it.route == Route.FLEET && it.state !in FLEET_ENDED_UNIT_STATES }.map { it.unitId } }

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
     * 닫는다(정상 종료). 멈춤(`stopped_at`)은 «이 프로세스는 더 보내지 않는다» 의 증거여야 한다 — 다음 보유자가 그것(과 이 세대가 발행을 열었다는
     * 표시)을 보고 곧바로 발행하기 때문이다. 그래서 순서는:
     * 1. 리더를 [LeaderState.STOPPING] 으로 바꿔 송신 직전 확인·DB 쓰기 가드·변경 HTTP 를 닫는다.
     * 2. pump·심장박동을 멈추고 pump 가 멈춘 것을 본다.
     * 3. 송신 직전 확인을 지난 기체·플릿·셀 송신(picasso `IssuingRobotPort`·`IssuingFleetPort` 의 송신 스레드 포함)이 다 끝나기를 기다린다
     *    ([HostLeadership.awaitQuiet]). 그 포트 빈은 이 빈보다 뒤에 닫히므로 송신 스레드는 아직 살아 있고, 1 뒤에는 새 송신이 확인을 지나지 못한다.
     * 4. 멈춤을 적고, 적었을 때만 리스를 내놓는다.
     * 2·3 을 제한 시간 안에 확인하지 못하면 멈춤도 리스 반환도 하지 않는다 — 다음 보유자는 만료를 기다려 관측(HOLDING)으로 든다. 리스를 잃은 뒤에는
     * 멈춤도 내놓기도 하지 않는다(자기 차단 기록이 이미 있다). 대기(리스 없음)면 멈춤만 적는다.
     */
    override fun close() {
        val held = leadership.stopping()
        pumper.shutdownNow()
        beater.shutdownNow()
        val pumpStopped = pumper.awaitTermination(2, TimeUnit.SECONDS)
        beater.awaitTermination(2, TimeUnit.SECONDS)
        if (leadership.state == LeaderState.LOST) return
        val quiet = !held || leadership.awaitQuiet(SEND_QUIET_TIMEOUT.toMillis())
        if (!quiet) log.warn("송신 중인 명령이 {} 안에 끝나지 않아 멈춤을 적지 않는다(다음 보유자는 만료 인수)", SEND_QUIET_TIMEOUT)
        val stopped = pumpStopped && quiet
        val recorded = stopped && try {
            records.stopInstance(instanceId)
            true
        } catch (e: Exception) {
            log.warn("인스턴스 멈춤을 적지 못했다: {}", e.toString())
            false
        }
        if (recorded) leadership.release()
    }

    companion object {
        private val log = LoggerFactory.getLogger(MissionHost::class.java)

        /** 정상 종료가 송신 중인 명령이 끝나기를 기다리는 한도([close]). 넘기면 멈춤을 적지 않는다. */
        val SEND_QUIET_TIMEOUT: Duration = Duration.ofSeconds(5)

        /** 리스를 잃은 뒤 자기 차단 기록을 기다리는 한도([lost]). 넘기면 기록 없이 종료로 간다. */
        val FENCE_TIMEOUT: Duration = Duration.ofSeconds(2)

        /** pump 주기(T5). 셀 대역이 채운 슬롯을 E2 마감(15초)보다 훨씬 짧게 다시 읽는다. */
        val PUMP_PERIOD: Duration = Duration.ofMillis(250)

        /** 끝나지 않은 플릿 단위의 상태(관측 중 플릿 준비의 재대조가 그 운반을 다시 찾는다). */
        private val OPEN_FLEET_UNIT: Set<dev.picasso.middleware.UnitState> = setOf(
            dev.picasso.middleware.UnitState.PENDING, dev.picasso.middleware.UnitState.IN_DOUBT, dev.picasso.middleware.UnitState.RUNNING,
            dev.picasso.middleware.UnitState.VERIFYING, dev.picasso.middleware.UnitState.OPERATOR_HOLD,
        )

        /**
         * `FAILED`·`UNVERIFIED` 로 정착한 실행을 놓기 전에 두는 폭의 기본값(`host.settled.revisable-retention`). 그 안에서는 같은 작업 지시 id
         * 의 개정판을 받는다. 운영 서비스는 작업 지시 id 를 다시 쓰지 않으므로 개정판은 호스트 REST 를 직접 부르는 쪽만 낸다.
         */
        val DEFAULT_REVISABLE_RETENTION: Duration = Duration.ofMinutes(5)

        /**
         * 미들웨어에 넘기는 보존 상한(picasso `Retention`). 상한은 모두 picasso 기본이고 두 가지를 선언한다.
         * - 놓은 작업 지시 표시의 넘침 `EVICT`(picasso §15.324) — 실행 일지가 같은 작업 지시 id 의 접수와 승인을 picasso 를 부르기 전에 거부하므로
         *   ([journalRefusal]) 그 약속을 진다.
         * - 두 대장의 사본 `CopyAck()`(picasso §15.327) — 인시던트 줄·탐색 줄은 pump 뒤 기록이 사본 표에 커밋하고 확인할 때까지 상한이 내리지 않는다
         *   ([recordAfterPump]). 확인 대기 한도는 picasso 기본(대장 상한과 같음)이다 — 확인은 pump 마다라 한 주기에 쌓이는 줄(개정 줄 포함)은 한도보다 훨씬 적다.
         */
        val RETENTION: Retention = Retention(forgottenOverflow = ForgottenOverflow.EVICT, copyAck = CopyAck())

        /**
         * 받은 재작업 시도를 처음 내기 직전에 다시 읽은 손·자리가 물리 관문을 못 지나 태스크 없이 `FAILED` 로 닫힌 시도의 실패 분류(picasso §15.323
         * `MiddlewareCodes.REWORK_RECHECK`, 라이브러리 안쪽 상수라 글자로 든다). 실패 인시던트를 열고 운영자 보류가 아니다. 완료 확인은 받지 않고
         * 사람의 새 `REWORK` 만 받는다. 분류 이름(코드 값)이지 사유 문장이 아니다.
         */
        const val REWORK_RECHECK = "REWORK_RECHECK"

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
        /**
         * 앞 세대 송신 후보(순수 함수). 기체 경로 단위([RestartUnit.robot]) 가운데 판단 기록([record])의 같은 시도에 판정이 없고, 앞 세대 요청 흔적
         * (`requestedAt`)이나 그 시도 태스크의 송신 의도([intents])가 있는 것이다. 해제가 그 태스크의 가장 큰 송신 의도 순번까지 덮었거나([covered], 의도가
         * 없으면 0) 이미 보류가 있으면([held]) 빠진다. 기록이 다른 시도를 들고 있으면(재작업이 넘김) 그 시도의 태스크가 아니다.
         */
        internal fun restartCandidates(
            jobOrderId: String,
            executionId: String?,
            units: List<RestartUnit>,
            record: JudgmentRecord?,
            recordId: Long?,
            intents: Map<String, dev.picasso.ops.host.store.IntentBasis>,
            covered: Map<String, Long>,
            held: (String) -> Boolean,
        ): List<RestartHolds.Hold> {
            val traces = record?.units.orEmpty().associateBy { it.unitId }
            return units.filter { it.robot }.mapNotNull { unit ->
                val trace = traces[unit.unitId]
                if (trace != null && (trace.verdict != null || trace.attempt != unit.attempt)) return@mapNotNull null
                val taskId = restartTaskId(jobOrderId, unit.unitId, unit.attempt)
                val intent = intents[taskId]
                if (trace?.requestedAt == null && intent == null) return@mapNotNull null
                val basis = intent?.intentId ?: 0L
                if ((covered[taskId] ?: -1L) >= basis) return@mapNotNull null
                if (held(taskId)) return@mapNotNull null
                RestartHolds.Hold(unit.robotId, jobOrderId, executionId, unit.unitId, unit.attempt, taskId, intent?.revision, basis, recordId)
            }
        }

        /** 후보 판정에 쓰는 단위 한 줄: 단위·시도·맡은 기체·기체 경로인가(플릿 운반이면 거짓). */
        internal data class RestartUnit(val unitId: String, val attempt: Int, val robotId: String, val robot: Boolean)

        /** 그 획득이 앞 세대 송신 후보를 판별하는가 — 정상 종료 인수([Acquired.graceful])가 아니면 참이다([discriminate] 의 범위). */
        internal fun discriminates(acquired: Acquired): Boolean = !acquired.graceful

        /** 시스템 해제(의심 뒤 붙음)의 해제한 사람 칸. */
        const val SYSTEM_RELEASER = "system:attached"

        /** 한 pump 에 다시 시도하는 미룬 복원 행 수. */
        internal const val RESTORE_RETRIES = 2

        /** 송신 문맥 표의 상한. */
        private const val ISSUE_CONTEXTS = 10_000

        /** 판정 상태(picasso `UnitVerdict.STATES`) — 그 단위의 보류는 판정을 얻으면 닫는다. */
        private val VERDICT_STATES = dev.picasso.middleware.UnitVerdict.STATES

        /** picasso 가 단위의 그 시도에 쓰는 태스크 id(`작업지시#단위`, 재작업 시도면 `@r<시도>`). 플릿 운반 참조와 같은 식이다. */
        internal fun restartTaskId(jobOrderId: String, unitId: String, attempt: Int): String =
            "$jobOrderId#$unitId" + if (attempt > 0) "@r$attempt" else ""

        internal fun creationOrder(executionId: String): Long = executionId.substringAfterLast('-').toLongOrNull() ?: Long.MAX_VALUE

        /** 현장 시간값 미적용 동안 판정이 기체마다 더하는 이유(S3c 스펙 T8). 화면에 그대로 보인다. */
        const val UNAPPLIED_REASON = "현장 시간값 미적용: 실행 호스트가 현장 설정을 아직 읽지 못했다"

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

        /**
         * 끝나지 않은 플릿 운반 단위가 있어 운영자 종결(`NOT_CLOSABLE` 의 detail 앞머리)이나 빈손 확인(409)을 받지 않는다는 고정 코드. 두 조작은
         * 기체의 태스크·손만 확인하므로 플릿이 멈췄다는 증거가 못 된다.
         */
        const val FLEET_UNIT_OPEN = "FLEET_UNIT_OPEN"

        /** 끝난 플릿 단위 상태. 그 밖(대기·미확정·진행·확인 중·운영자 보류)은 끝나지 않은 것이다. */
        private val FLEET_ENDED_UNIT_STATES = setOf(UnitState.DONE, UnitState.UNVERIFIED, UnitState.FAILED, UnitState.ABORTED)

        /** 플릿 전용 계획의 묶인 기체를 등록 확인으로 뺀 사유(D2(a)). 스냅숏을 못 읽어 이 현장의 기체인지 모른다. */
        const val UNREGISTERED_REASON = "등록 확인 안 됨: 이 현장의 기체인지 모른다(기체 스냅숏을 못 읽었다)"

        /**
         * 플릿 전용 계획인가 — 로봇 단위가 없고 플릿 단위가 있다. 그 실행의 기체는 **묶인 기체(실행 주체 아님)** 다(picasso 한계 «§15.326 · 기체
         * 묶임»). 설비 대기 단위만 든 계획은 여기 들지 않는다(플릿이 없다).
         */
        fun fleetOnly(units: List<ExecutionUnit>): Boolean = units.any { it.route == Route.FLEET } && units.none { it.route == Route.ROBOT }

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
         * 종결 뒤 이빅션한 실행이라는 뜻이다. 보류가 아닌 재작업은 이빅션 뒤에는 받지 않으므로 화면이 «보존 기간이 지났다» 와 «재작업할 수
         * 없는 대상» 을 이 코드로 가른다. 화면에 그대로 보이는 문구가 아니라 코드이며, 글자를 바꾸면 그것을 읽는 쪽이 같이 바뀌어야 한다.
         */
        const val EVICTED = "EVICTED"

        /** 받는 로봇 경로 코드 WorkMaster. 작업 지시 폼이 내는 둘이다. */
        val WORK_MASTERS: Set<String> = setOf(InspectAsset.WORK_MASTER, PrepareSequencedRack.WORK_MASTER)

        /**
         * 코드 정의가 있는 WorkMaster. [WORK_MASTERS] 에 플릿 운반 코드 임무 `DeliverContainer` 를 더한 것이다 — 요청 파서는 그것을 받고,
         * 플릿 포트가 없는 이 호스트는 계획에서 `FLEET_PORT_ABSENT` 로 거절한다([refuseFleetPlan]).
         */
        val CODE_WORK_MASTERS: Set<String> = WORK_MASTERS + DeliverContainer.WORK_MASTER
    }
}
