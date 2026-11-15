package dev.picasso.ops.service.missions

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import java.time.Instant

/** 호스트 거부의 해결 담당(S3b JSON 계약 §3.1). picasso `MissionRefusal.owner` 의 이름 그대로다. */
enum class HostRefusalOwner { ENGINEER, OUTSIDE_CONSOLE }

/**
 * 호스트 거부 하나(S3b JSON 계약 §3.1 `RefusalView`). 칸 이름은 호스트 본문 그대로다. 모르는 해결 담당이 오면 본문을 못 읽은
 * 것이다. 어느 쪽에 보일지 모르는 거부를 화면 안의 일로 접지 않는다.
 *
 * @param nodeId 막힌 노드 id. 문서 전체의 문제(`UNREADABLE`)는 널이다
 * @param checkedAt 호스트가 검증한 시각(호스트 시계)
 * @param basisVersion 근거 현장 설정 버전. S3b 에서는 늘 널이다(S3c)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostMissionRefusal(
    val kind: String,
    val nodeId: String?,
    val observed: String,
    val expected: String,
    val checkedAt: Instant,
    val basisVersion: Long?,
    val owner: HostRefusalOwner,
    val nextAction: String,
)

/**
 * 호스트 거부를 화면의 거부 카드([Finding]) 모양으로 옮긴다(S3b 스펙 §7). 두 모양은 칸이 거의 같지만 뜻이 다른 칸이 있다.
 *
 * - [Finding.target] 은 널이다. 화면은 이 칸을 기체 상세 링크로 그리는데, 임무 거부의 대상은 기체가 아니라 노드다.
 * - 노드 id 는 관측값 앞에 `노드 <id>: ` 로 싣는다. 노드 id 가 널이면(문서 전체의 문제) 붙이지 않는다.
 * - 해결 담당은 ENGINEER → ENGINEER(화면 안), OUTSIDE_CONSOLE → SITE(화면 밖)다. OUTSIDE_CONSOLE 는 바닥 소유 선언이라
 *   현장 일이고 엔지니어가 화면에서 풀 수 없다.
 * - [Finding.checkedAt] 은 호스트가 검증한 시각이고, [Finding.basisVersion] 은 호스트 값을 `Long` 그대로 옮긴다.
 *
 * 종류 이름은 picasso `MissionRefusalKind` 17종 그대로다(§15.325 가 `AFTER_INVALID`·`UNIT_ID_SHAPE_COLLISION`·`FLEET_UNIT_INVALID`·`WHEN_MISSING_INVALID` 를 더했다). 화면이 종류 이름표로 한국어 이름을 붙인다.
 */
object MissionFindings {

    fun of(refusal: HostMissionRefusal): Finding = Finding(
        kind = refusal.kind,
        observed = refusal.nodeId?.let { "노드 $it: ${refusal.observed}" } ?: refusal.observed,
        expected = refusal.expected,
        checkedAt = refusal.checkedAt,
        owner = when (refusal.owner) {
            HostRefusalOwner.ENGINEER -> Owner.ENGINEER
            HostRefusalOwner.OUTSIDE_CONSOLE -> Owner.SITE
        },
        inScreen = refusal.owner == HostRefusalOwner.ENGINEER,
        action = refusal.nextAction,
        target = null,
        basisVersion = refusal.basisVersion,
    )
}
