package dev.picasso.ops.service.joborders

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostEligibility
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.robots.CommissioningState
import dev.picasso.ops.service.robots.Connection
import dev.picasso.ops.service.robots.RegistryState
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.robots.RobotView
import java.time.Clock
import java.time.Instant

/** 실행 호스트에 닿았는가. 기체별이 아니라 판정 한 번 전체의 상태다. */
enum class HostState { OK, HOST_SILENT }

/**
 * 기체 하나의 배정 가능 판정(S3a 스펙 §8, T3). 네 칸 중 시운전·연결은 운영 서비스가, 스킬 적합·도는 실행은 호스트가 판정한다.
 *
 * 널은 «모름» 이다. registry 가 답하지 않으면 [commissioning]·[connection] 이 널이고, 호스트가 답하지 않으면 [host] 가 널이다.
 * 직전 목록의 값을 대신 쓰지 않는다. 직전 목록의 연결 칸은 그 목록을 읽은 시각으로 판정한 것이라, 지금은 낡았어도 신선으로 보인다.
 *
 * @param settingsVersion [connection] 을 판정한 현장 설정 버전(근거 버전). [connection] 이 널이면 널
 * @param host 호스트 판정 행. 스킬 적합(모자란 스킬)과 도는 실행이 여기 있다
 * @param eligible 네 칸이 다 알려졌고 다 통과했다. «모름» 이 하나라도 있으면 거짓이다
 * @param reasons 배정 가능이 아닌 이유(화면 표시용). 시운전, 연결, 호스트 판정 순서다. 배정 가능이면 비어 있다
 * @param gateTrial 배정 가능이지만 **관문 확인용**이다(호스트 [HostEligibility.gateTrial]). 남은 이유가 잔여 파지 표시뿐이라 일반 배정은 서지
 *   않고 조치 제안만 얻는다. 그 표시 사유는 [reasons] 가 아니라 호스트 행에 있다
 */
data class RobotEligibility(
    val robotId: String,
    val commissioning: CommissioningState?,
    val connection: Connection?,
    val settingsVersion: Long?,
    val host: HostEligibility?,
    val eligible: Boolean,
    val reasons: List<String>,
    val gateTrial: Boolean = false,
)

/**
 * `POST /api/job-orders/eligibility` 의 답.
 *
 * @param registry 기체 목록을 읽을 때의 registry 상태. `OK` 가 아니면 시운전·연결 칸이 모두 «모름» 이다
 * @param robotsAsOf 기체 목록을 registry 에서 읽은 시각. [checkedAt] 과 다르면 직전 목록이다
 * @param host 호스트 상태. `HOST_SILENT` 면 호스트 칸이 모두 «모름» 이다
 * @param robots 널이면 기체 목록을 한 번도 읽지 못한 것(«모름»), 빈 목록은 기체가 없는 것이다
 */
data class EligibilityView(
    val checkedAt: Instant,
    val registry: RegistryState,
    val robotsAsOf: Instant?,
    val host: HostState,
    val robots: List<RobotEligibility>?,
)

/**
 * 배정 가능 판정(S3a 스펙 §8). 기체 목록은 [robots] 의 기존 판정(S1·S2 의 시운전과 연결)을 그대로 쓰고, 호스트 판정을 기체마다
 * 붙인다. 기체는 그 사이트의 목록 전부다. 퇴역 기체도 행이 있고 시운전 칸이 `RETIRED` 다.
 *
 * registry 가 답하지 않으면 직전 목록의 기체 id 로 호스트에 묻는다. 호스트 칸은 registry 와 따로 알 수 있기 때문이다.
 */
class JobOrderEligibility(
    private val robots: RobotListService,
    private val host: HostReads,
    private val clock: Clock,
) {

    fun judge(jobOrder: ObjectNode): EligibilityView {
        val list = robots.read()
        val known = list.registry == RegistryState.OK
        val ids = list.robots.orEmpty().map { it.robot.robotId }
        val hostCall = if (ids.isEmpty()) HostCall.Ok(emptyList()) else host.eligibility(jobOrder, ids)
        val hostRows = (hostCall as? HostCall.Ok)?.value?.associateBy { it.robotId }
        return EligibilityView(
            checkedAt = clock.instant(),
            registry = list.registry,
            robotsAsOf = list.robotsAsOf,
            host = if (hostRows != null) HostState.OK else HostState.HOST_SILENT,
            robots = list.robots?.map { view ->
                row(view, list.registry, known, list.settingsVersion, list.connectionThresholdSeconds, hostRows)
            },
        )
    }

    private fun row(
        view: RobotView,
        registry: RegistryState,
        known: Boolean,
        settingsVersion: Long?,
        thresholdSeconds: Long?,
        hostRows: Map<String, HostEligibility>?,
    ): RobotEligibility {
        val commissioning = if (known) view.commissioning?.state else null
        val connection = if (known) view.connection else null
        val hostRow = hostRows?.get(view.robot.robotId)
        val reasons = buildList {
            if (!known) {
                add(
                    if (registry == RegistryState.REGISTRY_UNAUTHORIZED) {
                        "운영자 토큰이 registry 와 맞지 않아 시운전·연결을 모른다"
                    } else {
                        "registry 가 답하지 않아 시운전·연결을 모른다"
                    },
                )
            } else {
                when (commissioning) {
                    CommissioningState.COMPLETE -> Unit
                    CommissioningState.INCOMPLETE -> add("시운전이 끝나지 않았다")
                    CommissioningState.RETIRED -> add("퇴역한 기체다")
                    null -> add("시운전을 모른다")
                }
                when (connection) {
                    Connection.FRESH, null -> Unit
                    Connection.STALE -> add("연결이 오래됐다(기준 ${thresholdSeconds}초, 현장 설정 버전 $settingsVersion)")
                    Connection.NO_REPORT -> add("생존 보고가 없다")
                }
            }
            when {
                hostRows == null -> add("실행 호스트가 답하지 않아 스킬 적합·진행 중인 실행을 모른다")
                hostRow == null -> add("실행 호스트가 이 기체를 판정하지 않았다")
                hostRow.gateTrial -> Unit
                else -> addAll(hostRow.reasons)
            }
        }
        val eligible = commissioning == CommissioningState.COMPLETE &&
            connection == Connection.FRESH &&
            hostRow != null && hostRow.passed
        return RobotEligibility(
            robotId = view.robot.robotId,
            commissioning = commissioning,
            connection = connection,
            settingsVersion = if (connection != null) settingsVersion else null,
            host = hostRow,
            eligible = eligible,
            reasons = reasons,
            gateTrial = eligible && hostRow?.gateTrial == true,
        )
    }
}
