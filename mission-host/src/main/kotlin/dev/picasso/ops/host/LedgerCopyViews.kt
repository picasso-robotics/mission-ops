package dev.picasso.ops.host

import com.fasterxml.jackson.annotation.JsonRawValue
import dev.picasso.middleware.CopyAckState
import dev.picasso.middleware.CopyLedgerState
import dev.picasso.ops.host.store.RepeatedRemedyRow
import dev.picasso.ops.host.store.SearchCopyRow

/**
 * 두 대장의 사본 확인 상태(picasso §15.327 `Middleware.copyAcks`). 호스트 상태 응답(`GET /host/executions/summary` 의 `ledgerCopy`)이 싣는다.
 *
 * @param declared 사본 선언(`Retention.copyAck`)이 켜져 있는가. 이 호스트는 늘 켠다([MissionHost.RETENTION])
 * @param incidents 인시던트 대장 — 인시던트 id 로 확인한다
 * @param remedySearches 조치 탐색 대장 — 탐색 id 로 확인한다
 * @param revisionRemovals 리비전 제거 사건(picasso §15.329, V18) — 사본 선언의 보호 밖이라 확인 입구가 없고 호스트가 센다
 */
data class LedgerCopyView(
    val declared: Boolean,
    val incidents: CopyLedgerView,
    val remedySearches: CopyLedgerView,
    val revisionRemovals: RemovalCopyView = RemovalCopyView(0, 0, 0),
) {
    companion object {
        fun of(state: CopyAckState, removals: RemovalCopyView) =
            LedgerCopyView(state.declared != null, CopyLedgerView.of(state.incidents), CopyLedgerView.of(state.remedySearches), removals)

        /** 상태를 읽지 않은 자리의 기본값. */
        val UNREAD = LedgerCopyView(false, CopyLedgerView(0, 0, 0, 0), CopyLedgerView(0, 0, 0, 0))
    }
}

/**
 * 리비전 제거 사건 사본의 상태(V18 `mission.revision_removal`). picasso 레지스터(`Middleware.revisionRemovals`)는 사본 선언(`copyAck`)의 보호를 받지
 * 않는다 — 보존 상한(`Retention.revisionRemovals`, 기본 10,000)에 차면 확인과 무관하게 앞 사건을 내린다. 그래서 호스트가 식별자(실행, `toVersion`)로
 * 모으고 셈한다. 셈은 그 인스턴스가 뜬 뒤로다.
 *
 * @param copied 사본 표에 커밋한 사건의 누계
 * @param pending 레지스터에 있고 아직 커밋하지 않은 사건의 수. 받은 리비전은 그 개정판 행과 같은 트랜잭션에 적으므로 보통 0 이다
 * @param evictedBeforeCopy 커밋하기 전에 레지스터가 내린 사건의 누계. 0 이 아니면 그만큼 사본 표와 내보내기에서 빠졌다(경고 로그)
 */
data class RemovalCopyView(val copied: Long, val pending: Int, val evictedBeforeCopy: Long)

/**
 * 한 대장의 사본 확인 상태. 셈은 그 인스턴스가 뜬 뒤로다.
 *
 * @param pending 사본 표 커밋의 확인을 기다리며 보존 상한이 내리지 않는 줄의 수. pump 마다 확인하므로 보통 0 이다 — 늘기만 하면 사본 수집이 멈춘 것이다
 * @param overCap 대장이 보존 상한을 넘은 정도(확인 대기와 구동 중 실행의 몫)
 * @param exposed 확인 대기 한도를 넘어 보호가 풀린 줄의 누계
 * @param evictedBeforeCopy 확인 전에 내린 줄의 누계. 0 이 아니면 그만큼 사본 표에서 빠졌을 수 있다(경고 로그, 운영 지침 «사본 확인 전 내림»)
 */
data class CopyLedgerView(val pending: Int, val overCap: Int, val exposed: Long, val evictedBeforeCopy: Long) {
    companion object {
        fun of(state: CopyLedgerState) = CopyLedgerView(state.pending, state.overCap, state.exposed, state.evictedBeforeCopy)
    }
}

/**
 * `GET /host/remedy-searches` 의 본문 — 조치 탐색 대장(picasso `RemedySearchRecord`). 지금 인스턴스의 것은 메모리에서, 이전 인스턴스의 것은 사본 표
 * (`mission.remedy_search_copy`, V17)에서 읽는다(인시던트 목록의 `earlier` 와 같은 결). 둘 다 최근부터 많아야 limit 개다.
 *
 * @param total 지금 인스턴스 대장에 든 수(보존 상한 안)
 * @param earlierTotal 사본 표의 이전 인스턴스 행 수
 */
data class RemedySearchesView(
    val instanceId: String,
    val total: Int,
    val searches: List<RemedySearchLineView>,
    val earlierTotal: Int,
    val earlier: List<RemedySearchLineView>,
)

/**
 * 탐색 한 줄. [line] 은 picasso `LedgerExport.remedySearchLine` 의 글자 그대로를 JSON 값으로 싣는다 — 담는 쪽이 조립하지 않으므로 `WITHHELD` 줄에는
 * `steps` 키가 없다. [wireVersion] 은 그 줄의 규약 판(`LINE_SCHEMA_VERSION`)이다. 줄에는 인스턴스가 없어 [instanceId] 를 곁에 둔다.
 */
data class RemedySearchLineView(
    val instanceId: String,
    val searchId: String,
    val jobOrderId: String,
    val robotId: String,
    val outcome: String,
    val wireVersion: String,
    @get:JsonRawValue val line: String,
) {
    companion object {
        fun of(row: SearchCopyRow) = RemedySearchLineView(row.instanceId, row.searchId, row.jobOrderId, row.robotId, row.outcome, row.wireVersion, row.line)
    }
}

/**
 * `GET /host/repeated-remedies` 의 본문 — 같은 조치가 거듭 승인된 것. **정본은 이 합산이다**(`mission.remedy_approval`, V17): 인스턴스를 넘어 세고,
 * 승인자 종류로 가르지 않으며, 재시도·실행 복원은 두 번 세지 않는다. picasso `Middleware.repeatedRemedies` 는 그 인스턴스 안의 수라 재기동마다 0 부터다.
 *
 * @param atLeast 이 수 이상 승인된 서명만 싣는다
 */
data class RepeatedRemediesView(val atLeast: Int, val remedies: List<RepeatedRemedyView>)

/** 서명 하나 — (기체, 순서 있는 조치 유형 열). [instances] 는 그 승인이 선 인스턴스의 수다. */
data class RepeatedRemedyView(val robotId: String, val remedyTypes: List<String>, val approvals: Int, val instances: Int) {
    companion object {
        fun of(row: RepeatedRemedyRow) = RepeatedRemedyView(row.robotId, row.remedyTypes, row.approvals, row.instances)
    }
}
