package dev.picasso.ops.host

import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.middleware.RobotPort

/**
 * 기체마다 다른 수신자로 보내는 시험용 포트. 수신자 둘(대조하는 mimic 과 안 하는 mimic, 또는 mimic 과 실제 수신자 `adapter-host`)을 한 실행 호스트에 붙일 때 쓴다(시험 고정물이라 `load` 의 측정 변형도 쓴다) — 운영 배선은 같은 기계의
 * mimic 하나에 붙는다. 기체마다 [routes] 의 포트로 그대로 넘긴다. 없는 기체는 `IllegalArgumentException` 이다.
 */
class RoutedRobotPort(private val routes: Map<String, RobotPort>) : RobotPort {
    private fun of(robotId: String): RobotPort = requireNotNull(routes[robotId]) { "수신자가 없는 기체: $robotId" }

    override fun capabilities(robotId: String) = of(robotId).capabilities(robotId)
    override fun capabilitiesPending(robotId: String) = of(robotId).capabilitiesPending(robotId)
    override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>) =
        of(robotId).start(robotId, taskId, revision, skillType, parameters)
    override fun issue(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>) =
        of(robotId).issue(robotId, taskId, revision, skillType, parameters)
    override fun watch(robotId: String, handle: TaskHandle) = of(robotId).watch(robotId, handle)
    override fun release(robotId: String, handle: TaskHandle) = of(robotId).release(robotId, handle)
    override fun unfollowable(robotId: String, handle: TaskHandle) = of(robotId).unfollowable(robotId, handle)
    override fun cancel(robotId: String, handle: TaskHandle) = of(robotId).cancel(robotId, handle)
    override fun issueCancel(robotId: String, handle: TaskHandle) = of(robotId).issueCancel(robotId, handle)
    override fun snapshot(robotId: String) = of(robotId).snapshot(robotId)
    override fun replay(robotId: String, from: Long) = of(robotId).replay(robotId, from)
    override fun prefetched(robotId: String) = of(robotId).prefetched(robotId)
    override fun controllerRefusal(robotId: String) = of(robotId).controllerRefusal(robotId)
}
