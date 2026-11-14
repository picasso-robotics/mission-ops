package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.PhysicalState
import dev.picasso.middleware.UnitState
import java.time.Instant

/**
 * 정착 이력 한 줄(HA 설계 기록 §10). 실행 호스트가 정착을 적을 때 함께 적은 실행 본문이다. 미들웨어 메모리에서 실행이 사라져도
 * 남는다.
 *
 * @param settleSeq 정착 차례. 커서 `before` 에 그대로 싣는다. 크면 나중에 정착했다. 정착 차례 밖의 뜻은 없다(연속이 아니다)
 * @param instanceId 정착을 적은 인스턴스. `exec-N` 은 인스턴스마다 다시 세므로 실행 id 와 짝짓는다
 * @param settledAt 정착을 적은 DB 시각
 * @param snapshot [execution] 이 정착 때 적은 본문이면 참. 이력 기록(V8) 전에 정착한 실행은 거짓이고, 그때 [execution] 은 일지 행으로
 *   지은 것이라 단위가 비고 작업 응답·취소가 `null` 이며 기체는 받은 때의 것이다
 * @param execution `GET /host/executions` 의 실행 한 줄과 같은 모양
 * @param superseded 같은 작업 지시가 이 정착 뒤에 다시 정착했다(개정판이나 운영자 판단으로 다시 돌았다). 그 작업 지시의 마지막 정착은 거짓이다.
 *   실행 호스트는 정착할 때마다 한 줄을 적으므로 한 작업 지시가 여러 줄일 수 있다
 */
data class SettledExecutionView(
    val settleSeq: Long,
    val instanceId: String,
    val settledAt: Instant,
    val snapshot: Boolean,
    val execution: JsonNode,
    val superseded: Boolean = false,
)

/**
 * 화면의 실행 목록(HA 설계 기록 §10). `GET /host/executions?recent=` 의 본문이다.
 *
 * 앞 다섯 칸은 [ExecutionsView] 와 같은 이름·같은 뜻이고 [executions] 는 지금 인스턴스의 남은 실행([ExecutionPages.live])만 담는다(생성 순).
 *
 * @param reworkableTargets 지금 재작업을 받을 모양인 묶음([ReworkableTargetView], picasso `reworkCandidates` 순서 그대로). 남은 실행뿐 아니라
 *   종결 기록을 적고 아직 picasso 메모리에 있는 `FAILED` 실행의 것도 든다
 * @param settled 최근 정착 이력(최근부터). 인스턴스를 가리지 않는다
 * @param nextBefore 더 오래된 정착 이력을 읽을 커서. 더 없으면 `null`
 * @param olderTotal [nextBefore] 보다 오래된 정착 이력 수. [nextBefore] 가 `null` 이면 0
 * @param unrestored 복원 못 한 실행의 행([UnrestoredRowView], 결함 14). 재기동 보고(`restore`)는 이번 기동의 것뿐이지만 이 목록은 앞선 기동에서
 *   포기한 행도 든다 — 운영자가 종결할 수 있는 행이 재기동으로 사라지지 않는다. 메모리만 읽는다
 */
data class ExecutionPageView(
    val instanceId: String,
    val pumpedAt: Instant?,
    val executions: List<ExecutionView>,
    val restore: RestoreView?,
    val journalBlockedExecutionIds: List<String>,
    val reworkableTargets: List<ReworkableTargetView>,
    val settled: List<SettledExecutionView>,
    val nextBefore: Long?,
    val olderTotal: Int,
    val unrestored: List<UnrestoredRowView> = emptyList(),
)

/**
 * 복원 못 한 실행 하나(결함 14). 일지 행이 GAVE_UP 이거나, 기체 스냅숏을 못 읽어 미룬 DEFERRED 이거나, 정지 증언으로 종결했으나 그 기체의 스냅숏이 아직
 * 동의하지 않은 CLOSED 다.
 *
 * @param previousInstanceId·previousExecutionId 종결할 실행(앞 인스턴스에서 그 작업 지시를 들었던 실행). 운영자가 본 값을 종결 요청에 싣는다
 * @param result `GAVE_UP`·`DEFERRED`·`CLOSED`(증언으로 종결한 행)
 * @param reason 복원 사유. 종결한 행이면 종결 전의 사유다
 * @param since 포기·미룸을 적은 DB 시각(미룬 행은 이번 기동의 복원 시각). 종결한 행이면 종결을 적은 DB 시각이다
 * @param closable 지금 종결 조치를 받을 수 있다(살아 있는 다른 인스턴스가 든 미룬 행과 종결한 행은 거짓이다)
 * @param notClosableReason [closable] 이 거짓인 까닭. 거짓이 아니면 `null`
 * @param close CLOSED 일 때의 종결. 그 밖에는 `null`
 * @param robotExcluded 이 행이 지금 그 기체를 배정에서 빼고 있다. 포기 행은 스냅숏이 동의하면 거짓이 되지만 행은 종결할 수 있는 채로 남는다
 */
data class UnrestoredRowView(
    val jobOrderId: String,
    val robotId: String,
    val previousInstanceId: String,
    val previousExecutionId: String,
    val result: String,
    val reason: String?,
    val since: Instant,
    val closable: Boolean,
    val notClosableReason: String?,
    val close: CloseRecordView?,
    val robotExcluded: Boolean,
)

/**
 * 재작업할 수 있는 대상 하나(picasso `Middleware.reworkCandidates`, §15.323). 보류가 아닌 단위 가운데 재작업을 받을 모양인 **묶음** 하나에 하나다 —
 * 건너뛴 대상(선행 이동이 시도 뒤 실패해 점검을 내지 않은 단위, ADR 53), 시도 뒤 실패한 점검·이동, 시도 뒤 실패한 `pick_place` 계열 단위(첫 발행
 * 직전 재검사를 못 지난 `REWORK_RECHECK` 시도 포함). 묶음 규칙·모양·종결·같은 기체의 다른 활성 실행은 picasso 가 메모리 조건으로 가르고 호스트는
 * 보존 만료 시각만 덧붙인다 — 술어를 두 벌로 두지 않는다. 발행 관문·물리 관문(손·자리)은 포트를 읽어야 하므로 판단 때에만 답하고, 목록에
 * 올라도 `Refused` 일 수 있다(그 사유 문장이 답이다).
 *
 * @param unitId 판단을 낼 대상(picasso 후보의 `unitId`). 판단에 싣는 단위 id 다. 건너뛴 쌍이면 건너뛴 점검이다
 * @param physicalState `PARTIAL` 이나 `FAILED`
 * @param retainedUntil `FAILED` 면 이 시각(호스트 시계) 뒤에 picasso 메모리에서 이빅션되어 재작업할 수 없다. 종결 기록 시각에
 *   [MissionHost.DEFAULT_REVISABLE_RETENTION] 에 해당하는 보존 기간을 더한 값이다. `PARTIAL` 은 기체를 쥔 채 기다리므로 `null` 이다
 * @param attempt 대상의 지금 시도. 받으면 묶음의 단위마다 하나 오른다
 * @param bundle 함께 되돌릴 단위 id, 계획 순서
 * @param kind `SKIPPED`·`ATTEMPTED_INSPECTION`·`ATTEMPTED_HANDLING`(picasso `ReworkKind` 이름 그대로). `ATTEMPTED_HANDLING` 은 받을 때 손·자리를 본다
 * @param incidentUnitId 판단이 붙을 인시던트의 단위. 붙을 인시던트가 없으면 `null` 이다
 * @param recheckFailed 묶음에 첫 발행 직전 재검사를 못 지나 태스크 없이 실패로 닫힌 단위(`FAILED` ∧ 실패 분류 [MissionHost.REWORK_RECHECK])가 있다.
 *   앞서 받은 재작업이 «내기 직전에 손·자리가 달라 내지 않았다» 는 뜻이고 운영자 보류가 아니다. 같은 대상을 다시 재작업할 수 있다
 */
data class ReworkableTargetView(
    val executionId: String,
    val jobOrderId: String,
    val robotId: String,
    val unitId: String,
    val physicalState: String,
    val retainedUntil: Instant?,
    val attempt: Int,
    val bundle: List<String>,
    val kind: String,
    val incidentUnitId: String?,
    val recheckFailed: Boolean,
)

/** `GET /host/executions?before=` 의 본문. 칸 뜻은 [ExecutionPageView] 의 같은 이름 칸과 같다. */
data class SettledPageView(
    val settled: List<SettledExecutionView>,
    val nextBefore: Long?,
    val olderTotal: Int,
)

/**
 * `GET /host/executions/summary` 의 본문. 실행 본문 없이 수만 싣는다(HA 설계 기록 §10).
 *
 * @param live 지금 인스턴스의 남은 실행([ExecutionPages.live]) 수. 화면 목록의 [ExecutionPageView.executions] 수와 같다
 * @param states `PhysicalState` 이름마다 남은 실행 가운데 그 상태인 수. 이름 열한 개가 0 이어도 다 든다
 * @param journalBlockedExecutionIds 일지에 막힌 실행 id. `GET /host/executions` 의 같은 칸과 같다
 */
data class ExecutionSummaryView(
    val instanceId: String,
    val pumpedAt: Instant?,
    val live: Int,
    val states: Map<String, Int>,
    val journalBlockedExecutionIds: List<String>,
)

/** 화면 실행 목록의 판정(HA 설계 기록 §10). */
object ExecutionPages {

    /** 화면이 한 번에 받는 최근 정착 이력·페이지의 상한. */
    const val MAX = 200

    /** `before` 에 `limit` 이 없을 때의 페이지 크기. */
    const val DEFAULT_LIMIT = 20

    /**
     * 활성: 운영자가 지금 볼 실행. 셋 중 하나면 활성이다.
     * - 실행이 더 돌 수 있다(`PhysicalState.pumps`: 정착 전이거나 `PARTIAL`). `IN_DOUBT`·`OPERATOR_HOLD`·`CANCELING` 이 여기 든다
     * - 단위 하나라도 `OPERATOR_HOLD`·`IN_DOUBT` 다. 정착한 실행에도 판단을 기다리는 보류 단위가 남을 수 있다(보류 중 인시던트의 근거)
     * - 일지에 막혔다(호스트의 명령 진행과 신규 배정이 막힌 실행)
     *
     * 그 밖(`PHYSICALLY_DONE`·`UNVERIFIED`·`FAILED`·`ABORTED` 이고 보류 단위가 없고 일지에 든 실행)은 정착 실행이다.
     */
    fun active(state: PhysicalState, units: List<UnitState>, journalBlocked: Boolean): Boolean =
        state.pumps || units.any { it == UnitState.OPERATOR_HOLD || it == UnitState.IN_DOUBT } || journalBlocked

    /**
     * 남은 실행: 화면이 미들웨어 메모리에서 읽는 실행. 활성이거나, 정착했으나 지금의 정착 기록([settledRecorded] 가 거짓)이 아직 커밋되지
     * 않은 실행이다. 다시 돌아 다시 정착한 실행은 그 정착을 적기 전까지 남은 실행이다. 정착을 적은 실행은 정착 이력에서 읽으므로 메모리에서
     * 사라져도(picasso `Middleware.forget`) 화면에서 빠지지 않는다.
     */
    fun live(active: Boolean, settledRecorded: Boolean): Boolean = active || !settledRecorded
}
