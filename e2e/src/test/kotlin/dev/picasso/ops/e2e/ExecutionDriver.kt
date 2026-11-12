package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.host.MissionHost
import java.time.Duration
import java.time.Instant

/**
 * 가상 시계를 밀어 실행을 정착시키는 공용 진행기(S3a 스펙 §11). `JobOrderTest` 와 `MissionVersionTest` 가 쓴다.
 *
 * 호스트 시계는 현장 시계다(`E2eStack`). [push] 로 가상 시계를 밀 때마다 호스트의 마지막 pump 시각(`pumpedAt`)이 민 뒤의 가상
 * 시각 이상이 될 때까지 기다리고, [SETTLE_PUMPS] 주기를 더 기다린 뒤 상태를 읽는다. mimic 스트림 갱신은 gRPC 스레드로 비동기로 와서
 * `pumpedAt` 만으로는 그 pump 가 방금 민 전이를 봤다는 보장이 없기 때문이다. 실행 호스트는 기체 상태를 미리 읽으므로(`HostRobotPort`)
 * 민 직전에 보낸 읽기가 민 뒤에 받은 값으로 한 라운드 쓰일 수 있다. 그래서 한 주기가 아니라 [SETTLE_PUMPS] 주기를 기다린다. E2 마감(`doneAt + 15s`)은 단위를 끝낸 밀기
 * 뒤의 가상 시각부터 센다. 그래서 확인 중(VERIFYING)인 단위가 보이면 시계를 더 밀지 않고 실제 시간으로만 기다린다([drive]).
 */
class ExecutionDriver(private val stack: E2eStack) {

    fun executions(): JsonNode = stack.get("/api/executions")

    fun execution(executionId: String): JsonNode =
        checkNotNull(executions()["executions"].firstOrNull { it["executionId"].asText() == executionId }) { "실행이 없다: $executionId" }

    /** 가상 시계를 밀고, 호스트가 민 뒤의 시각에 pump 를 시작할 때까지 기다린 뒤 [SETTLE_PUMPS] 주기 더 기다린다. */
    fun push(by: Duration) {
        stack.site.advance(by)
        val target = stack.site.now()
        val deadline = Instant.now().plusSeconds(5)
        while (true) {
            val at = executions()["pumpedAt"]
            if (!at.isNull && !Instant.parse(at.asText()).isBefore(target)) break
            check(Instant.now().isBefore(deadline)) { "호스트 pump 가 $target 에 이르지 않았다: $at" }
            Thread.sleep(50)
        }
        Thread.sleep(MissionHost.PUMP_PERIOD.toMillis() * SETTLE_PUMPS)
    }

    /**
     * 실행의 물리 상태가 정착할 때까지 [STEP] 씩 민다. 민 뒤 확인 중(VERIFYING)인 단위가 있으면 더 밀지 않고 [VERIFY_WAIT] 동안
     * 실제 시간으로 기다린다. 그 안에 정착하지 않으면(셀 신호가 없음) 다시 밀어 마감으로 간다.
     */
    fun drive(executionId: String): JsonNode {
        repeat(ROUNDS) {
            push(STEP)
            var seen = execution(executionId)
            val until = Instant.now().plus(VERIFY_WAIT)
            while (seen["units"].any { it["state"].asText() == "VERIFYING" } && Instant.now().isBefore(until)) {
                Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
                seen = execution(executionId)
            }
            if (seen["physicalState"].asText() in SETTLED) return seen
        }
        error("실행 $executionId 가 ${ROUNDS}번 밀어도 정착하지 않았다: ${execution(executionId)}")
    }

    companion object {
        /** 민 뒤 상태를 읽기 전에 기다리는 pump 주기 수. 미리 읽은 기체 상태가 한 라운드 늦을 수 있어 하나가 아니라 셋이다. */
        const val SETTLE_PUMPS = 3L

        /** 한 번에 미는 가상 시간. E2 마감 15초보다 짧아 확인 전에 마감을 넘기지 않는다. */
        val STEP: Duration = Duration.ofSeconds(5)

        /** 밀기 상한. 가장 긴 것이 `pick_place` 둘(45초 ±10%)이고, 설비 대기 기한(120초)을 넘겨 마감까지 갈 수 있어야 한다. */
        const val ROUNDS = 60

        /** 확인 중인 단위를 실제 시간으로 기다리는 상한. 셀 대역은 다음 pump(250ms) 에 읽힌다. */
        val VERIFY_WAIT: Duration = Duration.ofSeconds(3)

        /** 정착한 물리 상태(S3a JSON 계약 §5). */
        val SETTLED = setOf("PHYSICALLY_DONE", "UNVERIFIED", "FAILED", "ABORTED", "PARTIAL")
    }
}
