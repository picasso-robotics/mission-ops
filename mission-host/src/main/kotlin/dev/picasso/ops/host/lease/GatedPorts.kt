package dev.picasso.ops.host.lease

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.middleware.AmrFleetPort
import dev.picasso.middleware.CancelIssue
import dev.picasso.middleware.CommandNotSent
import dev.picasso.middleware.DispatchIssue
import dev.picasso.middleware.FleetCancelIssue
import dev.picasso.middleware.Issue
import dev.picasso.middleware.RobotPort
import dev.picasso.middleware.TransportHandle
import dev.picasso.middleware.TransportOrder

/**
 * 송신 직전 로컬 권위 확인을 거는 기체 포트(HA 설계 기록 §4.4 의 3). 출발·취소(`start`·`cancel`)는 [authority] 가 **그 기체에** 발행을 허락할
 * 때만([SendAuthority.mayIssue] `robotId`, [SendAuthority.sending]) 위임 포트로 보내고, 아니면 보내기 전에 `CommandNotSent` 를 던진다. 잠금 밖
 * 송신 입구(`issue`·`issueCancel`)도 허락하지 않으면 위임하지 않고 «보내지 않음» 으로 답한다. 읽기(`watch`·`snapshot`·`capabilities` 따위)는
 * 그대로 위임한다. 기체 단위라 만료 인수 뒤 관측 중에도 수신자가 이 세대를 확인한 기체의 실행은 보낸다([HostLeadership.mayIssue]).
 *
 * 운영 배선은 이것을 picasso `IssuingRobotPort` 의 **위임 포트**로 넣는다 — 그래야 확인이 잠금 밖 송신 스레드에서 실제 gRPC 호출 바로 앞에
 * 돈다(줄에서 기다린 시간이 확인 뒤로 가지 않는다). 실행 호스트는 미들웨어에 넘기는 포트 바깥에도 한 겹 더 건다 — 시험이 넣는 포트(잠금 밖 입구가
 * 있든 없든)도 막는다.
 */
class GatedRobotPort(private val delegate: RobotPort, private val authority: SendAuthority) : RobotPort by delegate {
    override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse =
        authority.sending("출발 robot=$robotId task=$taskId 리비전 $revision", { authority.mayIssue(robotId) }) {
            delegate.start(robotId, taskId, revision, skillType, parameters)
        }

    override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse =
        authority.sending("취소 robot=$robotId task=${handle.taskId}", { authority.mayIssue(robotId) }) { delegate.cancel(robotId, handle) }

    override fun issue(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): Issue? =
        if (authority.mayIssue(robotId)) delegate.issue(robotId, taskId, revision, skillType, parameters)
        else Issue.NotSent("${SendAuthority.NOT_ISSUING}: 출발 robot=$robotId task=$taskId 리비전 $revision")

    override fun issueCancel(robotId: String, handle: TaskHandle): CancelIssue? =
        if (authority.mayIssue(robotId)) delegate.issueCancel(robotId, handle)
        else CancelIssue.Failed(CommandNotSent("${SendAuthority.NOT_ISSUING}: 취소 robot=$robotId task=${handle.taskId}"))
}

/**
 * 송신 직전 로컬 권위 확인을 거는 플릿 포트. 맡기기·취소(잠금 밖 입구 포함)만 막고 찾기·상태 읽기는 그대로다. [GatedRobotPort] 와 같은 자리에 건다.
 * 운반 참조(태스크 id)가 있는 입구는 그 참조로 묻는다([SendAuthority.mayDispatch] — 관측 중이면 그 실행의 «플릿 준비 ∧ 기체 전부 준비»). 참조가
 * 없는 취소(`cancel(handle)`)는 전역 발행만 본다.
 */
class GatedFleetPort(private val delegate: AmrFleetPort, private val authority: SendAuthority) : AmrFleetPort by delegate {
    override fun dispatch(order: TransportOrder): TransportHandle? =
        authority.sending("맡기기 ${order.reference}", { authority.mayDispatch(order.reference) }) { delegate.dispatch(order) }

    override fun cancel(handle: TransportHandle): Boolean = authority.sending("운반 취소 $handle") { delegate.cancel(handle) }

    override fun issueDispatch(order: TransportOrder): DispatchIssue? =
        if (authority.mayDispatch(order.reference)) delegate.issueDispatch(order)
        else DispatchIssue.NotSent("${SendAuthority.NOT_ISSUING}: 맡기기 ${order.reference}")

    override fun issueCancel(reference: String, handle: TransportHandle): FleetCancelIssue? =
        if (authority.mayDispatch(reference)) delegate.issueCancel(reference, handle)
        else FleetCancelIssue.Failed(CommandNotSent("${SendAuthority.NOT_ISSUING}: 운반 취소 $reference"))
}
