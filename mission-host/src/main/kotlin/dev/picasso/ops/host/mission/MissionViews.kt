package dev.picasso.ops.host.mission

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.mission.MissionRefusal
import dev.picasso.middleware.mission.SignalSpec
import dev.picasso.ops.host.store.DraftRow
import dev.picasso.ops.host.store.MockRunRow
import dev.picasso.ops.host.store.VersionRow
import java.time.Instant
import java.util.UUID

// 호스트 임무 REST 의 본문 모양(S3b JSON 계약 §4). 칸 이름과 순서가 계약이다.

/** 거부 하나. picasso `MissionRefusal` 의 칸에 파생 칸 둘(해결 담당, 바로 갈 작업)을 더한다. */
data class RefusalView(
    val kind: String,
    val nodeId: String?,
    val observed: String,
    val expected: String,
    val checkedAt: Instant,
    val basisVersion: Int?,
    val owner: String,
    val nextAction: String,
) {
    companion object {
        fun of(refusal: MissionRefusal) = RefusalView(
            kind = refusal.kind.name,
            nodeId = refusal.nodeId,
            observed = refusal.observed,
            expected = refusal.expected,
            checkedAt = refusal.checkedAt,
            basisVersion = refusal.basisVersion,
            owner = refusal.owner.name,
            nextAction = refusal.nextAction,
        )
    }
}

/** 신호 사양 한 줄. */
data class SignalSpecView(val name: String, val location: String?, val kind: String, val safety: Boolean) {
    companion object {
        fun of(spec: SignalSpec) = SignalSpecView(spec.name, spec.location, spec.kind.name, spec.safety)
    }
}

/**
 * 판정에 쓴 입력. 모르는 입력은 `null` 이다.
 *
 * @param robotIds 요청이 넘긴 기체(겹친 id 는 한 번).
 * @param unknownRobots 케이퍼빌리티를 못 물어본 기체.
 */
data class InputsView(
    val signals: List<SignalSpecView>?,
    val siteSkills: List<String>?,
    val robotIds: List<String>,
    val unknownRobots: List<String>,
) {
    companion object {
        fun of(inputs: SiteInputs) = InputsView(
            signals = inputs.signals?.map(SignalSpecView::of),
            siteSkills = inputs.siteSkills?.sorted(),
            robotIds = inputs.skillsByRobot.keys.toList(),
            unknownRobots = inputs.unknownRobots,
        )
    }
}

/** 판정이 «모름» 일 때 무엇을 몰랐는가(T7). */
data class UnknownView(val inputs: List<String>, val robots: List<String>, val detail: String) {
    companion object {
        fun of(inputs: List<UnknownInput>, robots: List<String>) = UnknownView(
            inputs = inputs.map { it.name },
            robots = robots,
            detail = inputs.joinToString("; ") {
                when (it) {
                    UnknownInput.SIGNAL_SPEC -> "셀 대역 스냅숏이 없어 신호 사양을 못 읽었다"
                    UnknownInput.SITE_SKILLS -> "기체 케이퍼빌리티를 못 물어봐 현장 스킬을 못 읽었다(${robots.joinToString(", ")})"
                    UnknownInput.SAMPLE_ORDER -> "셀 대역 스냅숏에 슬롯이나 자재를 든 제시 자리가 없어 표본 작업 지시를 못 만든다"
                }
            },
        )
    }
}

/** 초안 한 행. [lastMockRun] 은 그 초안의 마지막 모의 실행이며 없으면 `null`. */
data class DraftView(
    val draftId: Long,
    val workMasterId: String,
    val definition: String,
    val savedBy: String,
    val requestId: UUID,
    val savedAt: Instant,
    val lastMockRun: MockRunView?,
) {
    companion object {
        fun of(row: DraftRow, lastMockRun: MockRunView?) =
            DraftView(row.draftId, row.workMasterId, row.definition, row.savedBy, row.requestId, row.savedAt, lastMockRun)
    }
}

/** 모의 실행 한 행. [result] 는 [MockRunResult] 의 JSON 이다. */
data class MockRunView(
    val mockRunId: Long,
    val draftId: Long,
    val passed: Boolean,
    val result: JsonNode,
    val requestId: UUID,
    val startedAt: Instant,
    val finishedAt: Instant,
) {
    companion object {
        fun of(row: MockRunRow, result: JsonNode) =
            MockRunView(row.mockRunId, row.draftId, row.passed, result, row.requestId, row.startedAt, row.finishedAt)
    }
}

/** 임무 버전 한 행. */
data class VersionView(
    val workMasterId: String,
    val version: Int,
    val draftId: Long,
    val definition: String,
    val activatedBy: String,
    val reason: String,
    val requestId: UUID,
    val activatedAt: Instant,
) {
    companion object {
        fun of(row: VersionRow) =
            VersionView(row.workMasterId, row.version, row.draftId, row.definition, row.activatedBy, row.reason, row.requestId, row.activatedAt)
    }
}

/**
 * 지금 활성인 정의. [version] 이 `null` 이면 코드 정의이고([source] 가 `CODE`) [detail] 도 `null` 이다(코드라 버전 행이
 * 없다). 데이터 버전이면([source] 가 `DATA`) [detail] 에 그 버전 행이 실리고, 정의 JSON 은 그 행의 `definition` 이다.
 */
data class ActiveView(val version: Int?, val source: String, val detail: VersionView?)

/** `GET /host/missions/{workMasterId}` 의 본문. 버전은 높은 번호부터, 초안은 최근 것부터 [MissionVersions.DRAFT_LIMIT] 개. */
data class MissionOverview(
    val workMasterId: String,
    val active: ActiveView,
    val versions: List<VersionView>,
    val drafts: List<DraftView>,
)

/** `POST /host/missions/{workMasterId}/drafts` 의 본문. */
data class DraftSavedView(val draft: DraftView)

/** 검증 결과(S3b 스펙 §6.3). [result] 는 `PASSED`·`REFUSED`·`INPUT_UNKNOWN`. */
data class ValidationView(
    val result: String,
    val draftId: Long,
    val workMasterId: String,
    val checkedAt: Instant,
    val refusals: List<RefusalView>,
    val unknown: UnknownView?,
    val inputs: InputsView,
)

/**
 * 모의 실행 결과(S3b 스펙 §6.4). [result] 는 `PASSED`·`FAILED`·`REFUSED`·`INPUT_UNKNOWN`. 앞의 둘만 모의 실행 표에 남고
 * [mockRun] 이 그 행이다. 뒤의 둘은 돌지 않았으므로 [mockRun] 이 `null` 이다.
 */
data class MockRunOutcomeView(
    val result: String,
    val draftId: Long,
    val workMasterId: String,
    val checkedAt: Instant,
    val refusals: List<RefusalView>,
    val unknown: UnknownView?,
    val inputs: InputsView,
    val mockRun: MockRunView?,
)

/**
 * 활성화 결과(S3b 스펙 §6.5). [result] 는 `ACTIVATED`·`REFUSED`·`FLEET_PORT_ABSENT`·`MOCK_RUN_REQUIRED`·`INPUT_UNKNOWN` 다섯이다.
 * `FLEET_PORT_ABSENT` 는 정의에 플릿 경로 노드가 있는데 이 실행 호스트에 플릿 포트가 없어 받지 않은 것이다(모의 실행 통과와 무관).
 *
 * @param version ACTIVATED 일 때 선 버전. 그 밖에는 `null`.
 * @param activated ACTIVATED 일 때 그 버전 행. 그 밖에는 `null`.
 * @param lastMockRun 판정에 쓴 그 초안의 마지막 모의 실행. 없거나 검증에서 멈췄으면 `null`.
 */
data class ActivationView(
    val result: String,
    val draftId: Long,
    val workMasterId: String,
    val checkedAt: Instant,
    val version: Int?,
    val refusals: List<RefusalView>,
    val unknown: UnknownView?,
    val inputs: InputsView,
    val lastMockRun: MockRunView?,
    val activated: VersionView?,
)

/** `GET /host/missions/requests/{requestId}` 의 본문(T9). 셋 중 남은 것만 `null` 이 아니다. */
data class RequestView(
    val requestId: UUID,
    val draft: DraftView?,
    val mockRun: MockRunView?,
    val version: VersionView?,
)

/** `GET /host/missions/templates/{workMasterId}` 의 본문. */
data class TemplatesView(val workMasterId: String, val templates: List<MissionTemplate>)
