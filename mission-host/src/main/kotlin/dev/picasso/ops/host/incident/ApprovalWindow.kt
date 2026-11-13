package dev.picasso.ops.host.incident

import dev.picasso.host.ApprovalHost
import dev.picasso.ops.host.MissionHost
import org.slf4j.LoggerFactory

/**
 * 승인 호스트(picasso `host-kit` 의 [ApprovalHost], `POST /approvals`)를 실행 호스트에 붙인 것. 포트를 주었을 때만 연다.
 *
 * picasso 의 편의 생성자는 `synchronized` 를 쓴다. 이 호스트의 잠금은 `ReentrantLock` 이라 콜백 생성자로 [MissionHost.attemptApproval] 을
 * 부른다. 그 함수가 호스트 잠금을 들고 실행 일지를 적는다.
 *
 * **루프백에만 붙고 신원을 인증하지 않는다**(picasso §15.3). 같은 기계의 incident-recovery 가 부르는 개념 증명 창구다.
 */
class ApprovalWindow private constructor(private val server: ApprovalHost?) : AutoCloseable {

    /** 연 포트. 열지 않았으면 `null` 이다. */
    val port: Int? get() = server?.port

    override fun close() {
        server?.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ApprovalWindow::class.java)

        /** 열지 않은 창구. */
        val CLOSED = ApprovalWindow(null)

        /** [port] 로 연다. 0 이면 빈 포트를 고른다. */
        fun open(host: MissionHost, port: Int): ApprovalWindow {
            require(port in 0..65535) { "승인 호스트 포트(host.incident-loop.approval-port)가 틀리다: $port" }
            val server = ApprovalHost(host.instanceId, port) { host.attemptApproval(it) }
            log.info("승인 호스트를 열었다: http://127.0.0.1:{}{} (인스턴스 {})", server.port, ApprovalHost.PATH, host.instanceId)
            return ApprovalWindow(server)
        }
    }
}
