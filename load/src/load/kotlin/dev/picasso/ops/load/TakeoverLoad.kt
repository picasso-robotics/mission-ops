package dev.picasso.ops.load

import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant

/**
 * 비정상 종료 뒤 만료 인수의 공백(HA 설계 기록 §4.5·§7, 능동-대기 1단계 기반). 결정적 단언은 mission-host `HostTakeoverTest`·`HostLeaderTest` 에
 * 있고 여기는 시간만 잰다.
 *
 * 한 회차: 기체 N 대에 InspectAsset(E0, 대상 bay-7) 작업 지시를 하나씩 내고 가상 시계를 1초씩 밀어 모든 실행의 로봇 단위가 `RUNNING`(태스크를
 * 낸 상태)이 되게 한다. 첫째 호스트를 멈춤 기록 없이·리스를 내놓지 않고 죽이고([LoadStack.killHost]) 곧바로 둘째를 같은 인자로 띄운다
 * ([LoadStack.replaceHost]). 둘째는 리스가 만료할 때까지 대기이고, 만료 뒤 리스를 얻어 복원한 뒤 관측(`HOLDING`)에 든다. 둘째의 복원 보고가
 * 모두 `RESTORED` 가 될 때까지, 그 뒤 모든 실행의 단위가 다시 `RUNNING` 으로 관측될 때까지 50 ms 마다 읽는다. 운영자 확인(발행 재개)은 사람의
 * 시간이라 재지 않는다.
 *
 * 시각은 두 갈래다. DB 시각(죽인 직후 리스 행의 `expires_at`, `RESTORED` 이벤트의 `recorded_at`, 모두 `clock_timestamp()`)으로 «마지막 갱신 →
 * 리빌드 완료» 를 재고(마지막 갱신 = 그 만료 − 만료 폭), JVM 의 `nanoTime` 으로 «죽임 → 둘째 기동 끝 → 리빌드 관측 → 재부착 관측» 을 잰다. 두
 * 시계를 섞지 않는다(Docker Postgres 의 시계는 이 JVM 시계와 어긋날 수 있다). 태스크 수는 죽이기 전과 재부착 뒤에 mimic 에서 센다.
 */
object TakeoverLoad {

    data class Lease(val period: Duration, val expiry: Duration, val margin: Duration) {
        val label: String get() = "${period.toMillis()} / ${expiry.toMillis()} / ${margin.toMillis()} ms"

        /** 유휴 트랜잭션 제한은 만료보다 짧아야 한다(기본 10초, 짧은 만료면 그 절반). */
        val idle: Duration get() = minOf(Duration.ofSeconds(10), expiry.dividedBy(2))
    }

    fun run(report: Report, leases: List<Lease>, counts: List<Int>) {
        report.line("## 비정상 종료 뒤 만료 인수 — 마지막 리스 갱신에서 리빌드 완료까지")
        report.line()
        report.line("작업 지시: InspectAsset E0, 대상 하나(bay-7), 기체마다 하나. 로봇 단위가 모두 RUNNING 일 때 첫째를 멈춤·리스 반환 없이 죽이고 곧바로 둘째를 띄운다. 읽기 주기 50 ms.")
        report.line()
        val rows = mutableListOf<List<String>>()
        for (lease in leases) for (n in counts) {
            LoadStack(
                robots = n, withOps = false,
                hostArgs = listOf(
                    "--host.lease.period=${lease.period}", "--host.lease.expiry=${lease.expiry}", "--host.lease.margin=${lease.margin}",
                    "--host.lease.idle-in-transaction-timeout=${lease.idle}", "--host.lease.statement-timeout=${lease.expiry.minus(lease.margin)}",
                ),
            ).use { stack ->
                report.floor(stack, "세트(기체 $n, 리스 ${lease.label})")
                rows += once(stack, lease, n)
            }
        }
        report.table(
            listOf(
                "리스 주기 / 만료 / 여유", "실행 수", "죽인 때 마지막 갱신 나이 ms(DB)", "마지막 갱신 → 리빌드 완료 ms(DB)", "리스 만료 → 리빌드 완료 ms(DB)",
                "죽임 → 둘째 기동 끝 ms", "죽임 → 리빌드 관측 ms", "죽임 → 재부착 관측 ms", "둘째 기동 직후 → 인수 뒤 상태", "태스크 수 앞 → 뒤",
            ),
            rows,
        )
        report.line("«마지막 갱신» 은 죽인 직후 리스 행의 `expires_at` − 만료 폭이다. «리스 만료 → 리빌드 완료» 는 마지막 `RESTORED` 이벤트의 `recorded_at` 까지를 그 `expires_at` 에서 잰 것이라 만료 폭을 뺀 몫(만료를 알아채는 획득 주기 + 재대조)이다.")
        report.line()
    }

    private fun once(stack: LoadStack, lease: Lease, n: Int): List<String> {
        stack.robotIds.forEachIndexed { i, robot ->
            val reply = stack.hostPost(
                "/host/job-orders",
                """{"jobOrder":{"jobOrderId":"JO-${i + 1}","workMasterId":"InspectAsset","version":1,"requiredEvidence":"E0","parameters":{},
                   "materialRequirements":[],"equipmentRequirements":[{"id":"T1","equipmentUse":"inspection_target","properties":{"location":"bay-7"}}]},
                   "candidates":["$robot"]}""",
            )
            check(reply["result"].asText() == "ACCEPTED") { "제출 거부: $reply" }
        }
        var rounds = 0
        while (!allRunning(stack.hostGet("/host/executions").first, n)) {
            check(++rounds <= 60) { "60초 밀어도 로봇 단위가 모두 RUNNING 이 되지 않았다" }
            stack.mimic.server.advance(Duration.ofSeconds(1))
            Thread.sleep(600)
        }
        // 첫째가 낼 명령을 다 낸 뒤에 죽인다(가상 시계는 더 밀지 않는다).
        Thread.sleep(1_000)
        val tasksBefore = tasks(stack)
        val first = stack.missionHost.instanceId

        val killed = System.nanoTime()
        stack.killHost()
        val (expires, beforeAge) = stack.query(
            "SELECT t.expires_at, extract(epoch FROM clock_timestamp() - (t.expires_at - make_interval(secs => ${lease.expiry.toMillis() / 1000.0}))) * 1000, l.holder " +
                "FROM mission.host_lease l JOIN mission.host_lease_term t USING (role)",
        ) { check(it.getString(3) == first) { "죽인 직후 리스 보유자가 첫째가 아니다: ${it.getString(3)}" }; it.getTimestamp(1).toInstant() to it.getDouble(2) }.single()
        stack.replaceHost()
        val up = System.nanoTime()
        val stateAtStart = stack.hostGet("/host/leader").first["state"].asText()
        val restoredSeen = poll(lease) { stack.hostGet("/host/executions").first["restore"].let { r -> !r.isNull && r["rows"].size() == n && r["rows"].all { it["result"].asText() == "RESTORED" } } }
        val attachedSeen = poll(lease) { allRunning(stack.hostGet("/host/executions").first, n) }
        Thread.sleep(1_000)
        val tasksAfter = tasks(stack)
        check(tasksAfter == tasksBefore) { "재부착 뒤 태스크가 바뀌었다(중복 출발?): $tasksBefore → $tasksAfter" }
        val stateAfter = stack.hostGet("/host/leader").first["state"].asText()
        check(stack.query("SELECT stopped_at FROM mission.host_instance WHERE instance_id = '$first'") { it.getTimestamp(1) }.single() == null) { "죽인 인스턴스가 멈춤을 적었다" }

        val restoredAt = stack.query(
            "SELECT max(recorded_at) FROM mission.execution_journal_event WHERE kind = 'RESTORED'",
        ) { it.getTimestamp(1).toInstant() }.single()
        fun ms(from: Long, to: Long) = f((to - from) / 1e6)
        fun ms(from: Instant, to: Instant) = f(Duration.between(from, to).toNanos() / 1e6)
        return listOf(
            lease.label, "$n", f(beforeAge), ms(expires.minus(lease.expiry), restoredAt), ms(expires, restoredAt),
            ms(killed, up), ms(killed, restoredSeen), ms(killed, attachedSeen), "$stateAtStart → $stateAfter", "${tasksBefore.values.sumOf { it.size }} → ${tasksAfter.values.sumOf { it.size }}",
        )
    }

    /**
     * 대조 변형(HA 3단계, **미믹 기준**). mimic 이 제어자 세대를 대조하고 바닥값을 파일에 둔다(`FENCED`·`FileFenceStore`). 작업 지시는 대상 둘
     * (bay-7, dock-3)이고 첫 대상의 점검(`T1`)이 RUNNING 일 때 첫째를 죽인다. 둘째를 띄운 뒤 리스가 만료하기 전에 가상 시계를 밀어 기체 쪽에서 `T1` 을
     * 끝내 둔다 — 다시 지은 실행은 다음 단위(`T2.travel`)를 곧바로 낼 차례로 서고, 둘째는 관측(`HOLDING`) 중 수신자가 새 세대를 확인한 기체부터 연다.
     * 그래서 «첫 명령 수락» 은 측정 구간 안에서 가상 시계를 밀지 않고 잰다. 기체 열림은 자동 확인 근거(`host_readiness_evidence.recorded_at`, DB 시각),
     * 명령 수락은 mimic 이 새 태스크를 든 것을 50 ms 마다 읽어 본 JVM 시각이다. 실제 수신자(`adapter-host`)의 인수 확인은 정리 대기(`fenceWait`)를 더할
     * 수 있다 — 미믹은 그 대기가 0 이다.
     */
    fun runFenced(report: Report, leases: List<Lease>, counts: List<Int>) {
        report.line("## 대조하는 수신자(미믹 기준)에서 비정상 종료 뒤 만료 인수 — 리스 만료에서 첫 기체 열림·첫 명령 수락까지")
        report.line()
        report.line("mimic FENCED + 파일 바닥값. 작업 지시: InspectAsset E0, 대상 둘(bay-7, dock-3), 기체마다 하나. T1 이 RUNNING 일 때 첫째를 멈춤·리스 반환 없이 죽이고 곧바로 둘째를 띄운 뒤, 리스 만료 전에 가상 시계를 밀어 기체 쪽 T1 을 끝내 둔다. 읽기 주기 50 ms.")
        report.line()
        val rows = mutableListOf<List<String>>()
        for (lease in leases) for (n in counts) {
            LoadStack(
                robots = n, withOps = false, fenced = true,
                hostArgs = listOf(
                    "--host.lease.period=${lease.period}", "--host.lease.expiry=${lease.expiry}", "--host.lease.margin=${lease.margin}",
                    "--host.lease.idle-in-transaction-timeout=${lease.idle}", "--host.lease.statement-timeout=${lease.expiry.minus(lease.margin)}",
                ),
            ).use { stack ->
                report.floor(stack, "세트(기체 $n, 리스 ${lease.label}, FENCED)")
                rows += fencedOnce(stack, lease, n)
            }
        }
        report.table(
            listOf(
                "리스 주기 / 만료 / 여유", "실행 수", "리스 만료 → 리빌드 완료 ms(DB)", "리스 만료 → 첫 기체 열림 ms(DB)", "리스 만료 → 모든 기체 열림 ms(DB)",
                "죽임 → 첫 기체 열림 관측 ms", "죽임 → 첫 명령 수락 관측 ms", "첫 열림 관측 → 첫 수락 관측 ms", "죽임 → 모든 기체 수락 관측 ms", "인수 뒤 상태 · 발행 표시",
            ),
            rows,
        )
        report.line("«기체 열림» 은 둘째가 그 기체의 자동 확인 근거를 적은 DB 시각이다(지금 읽은 스냅숏의 바닥값 = 둘째의 세대 ∧ DURABLE_CONFIRMED, 그 라운드의 재대조 뒤). «명령 수락» 은 mimic 이 둘째의 `T2.travel` 출발을 접수해 태스크를 든 것을 하네스가 본 때다. 둘 다 운영자 확인 없이 관측(HOLDING) 중에 일어난다 — 전역 발행 표시는 서지 않는다.")
        report.line()
    }

    private fun fencedOnce(stack: LoadStack, lease: Lease, n: Int): List<String> {
        stack.robotIds.forEachIndexed { i, robot ->
            val reply = stack.hostPost(
                "/host/job-orders",
                """{"jobOrder":{"jobOrderId":"JO-${i + 1}","workMasterId":"InspectAsset","version":1,"requiredEvidence":"E0","parameters":{},
                   "materialRequirements":[],"equipmentRequirements":[{"id":"T1","equipmentUse":"inspection_target","properties":{"location":"bay-7"}},
                   {"id":"T2","equipmentUse":"inspection_target","properties":{"location":"dock-3"}}]},
                   "candidates":["$robot"]}""",
            )
            check(reply["result"].asText() == "ACCEPTED") { "제출 거부: $reply" }
        }
        var rounds = 0
        while (!allRunning(stack.hostGet("/host/executions").first, n)) {
            check(++rounds <= 60) { "60초 밀어도 로봇 단위가 모두 RUNNING 이 되지 않았다" }
            stack.mimic.server.advance(Duration.ofSeconds(1))
            Thread.sleep(600)
        }
        Thread.sleep(1_000)
        val tasksBefore = tasks(stack)

        val killed = System.nanoTime()
        stack.killHost()
        val expires = stack.query("SELECT t.expires_at FROM mission.host_lease_term t") { it.getTimestamp(1).toInstant() }.single()
        stack.replaceHost()
        // 리스가 살아 있는 동안(둘째는 대기) 기체 쪽 T1 을 끝내 둔다. 둘째의 다시 짓기는 T1 이 끝난 스냅숏으로 선다.
        repeat(12) { stack.mimic.server.advance(Duration.ofSeconds(10)) }
        val opened = poll(lease) { stack.query("SELECT count(*) FROM mission.host_readiness_evidence") { it.getInt(1) }.single() > 0 }
        val accepted = poll(lease) { grown(tasks(stack), tasksBefore) > 0 }
        val allAccepted = poll(lease) { grown(tasks(stack), tasksBefore) == n }
        val allOpenAt = poll(lease) { stack.query("SELECT count(*) FROM mission.host_readiness_evidence") { it.getInt(1) }.single() == n }
            .let { stack.query("SELECT max(recorded_at) FROM mission.host_readiness_evidence") { it.getTimestamp(1).toInstant() }.single() }
        val firstOpenAt = stack.query("SELECT min(recorded_at) FROM mission.host_readiness_evidence") { it.getTimestamp(1).toInstant() }.single()
        val restoredAt = stack.query("SELECT max(recorded_at) FROM mission.execution_journal_event WHERE kind = 'RESTORED'") { it.getTimestamp(1).toInstant() }.single()
        val leader = stack.hostGet("/host/leader").first
        val issuing = stack.query("SELECT issuing FROM mission.host_lease_term") { it.getBoolean(1) }.single()
        check(leader["state"].asText() == "HOLDING" && !issuing) { "관측 중 부분 발행이 아니다: ${leader["state"]} 발행 표시 $issuing" }
        fun ms(from: Long, to: Long) = f((to - from) / 1e6)
        fun ms(from: Instant, to: Instant) = f(Duration.between(from, to).toNanos() / 1e6)
        return listOf(
            lease.label, "$n", ms(expires, restoredAt), ms(expires, firstOpenAt), ms(expires, allOpenAt),
            ms(killed, opened), ms(killed, accepted), ms(opened, accepted), ms(killed, allAccepted), "${leader["state"].asText()} · $issuing",
        )
    }

    /** 죽이기 전보다 태스크가 늘어난 기체 수. 앞 태스크가 빠졌으면 멈춘다(다시 짓기가 태스크를 잃었다). */
    private fun grown(now: Map<String, List<String>>, before: Map<String, List<String>>): Int = before.keys.count { robot ->
        val was = before.getValue(robot)
        val is_ = now.getValue(robot)
        check(is_.containsAll(was)) { "앞 태스크가 빠졌다: $robot $was → $is_" }
        is_.size > was.size
    }

    /** 실행이 [n] 개이고 모두 로봇 단위(`T1`)가 RUNNING 인가. */
    private fun allRunning(view: JsonNode, n: Int): Boolean {
        val executions = view["executions"]
        return executions.size() == n && executions.all { e -> e["units"].any { it["unitId"].asText() == "T1" && it["state"].asText() == "RUNNING" } }
    }

    /** [done] 이 참이 될 때까지 50 ms 마다 읽고 그때의 `nanoTime` 을 돌려준다. 만료 폭 + 60초 상한. */
    private fun poll(lease: Lease, done: () -> Boolean): Long {
        val deadline = System.nanoTime() + lease.expiry.plusSeconds(60).toNanos()
        while (true) {
            if (done()) return System.nanoTime()
            check(System.nanoTime() < deadline) { "만료 폭 + 60초 안에 이르지 않았다" }
            Thread.sleep(50)
        }
    }

    /** 기체마다 mimic 이 든 태스크 id. */
    private fun tasks(stack: LoadStack): Map<String, List<String>> = stack.robotIds.associateWith { robot ->
        stack.mimic.server.exclusive { stack.mimic.instance(robot)!!.tasks.all.map { it.taskId }.sorted() }
    }
}
