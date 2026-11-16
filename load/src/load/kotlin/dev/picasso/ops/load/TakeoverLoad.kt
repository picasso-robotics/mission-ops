package dev.picasso.ops.load

import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant

/**
 * 비정상 종료 뒤 순차 인수의 공백(HA 설계 기록 §4.5·§7, 보장 문서 «비정상 종료 뒤 재기동»). 결정적 단언은 mission-host
 * `HostTakeoverTest` 에 있고 여기는 시간만 잰다.
 *
 * 한 회차: 기체 N 대에 InspectAsset(E0, 대상 bay-7) 작업 지시를 하나씩 내고 가상 시계를 1초씩 밀어 모든 실행의 로봇 단위가 `RUNNING`(태스크를
 * 낸 상태)이 되게 한다. 첫째 호스트를 멈춤 기록 없이 죽이고([LoadStack.killHost]) 곧바로 둘째를 같은 인자로 띄운다([LoadStack.replaceHost]).
 * 둘째의 복원 보고가 모두 `RESTORED` 가 될 때까지, 그 뒤 모든 실행의 단위가 다시 `RUNNING` 으로 관측될 때까지 50 ms 마다 읽는다.
 *
 * 시각은 두 갈래다. DB 시각(첫째 인스턴스 행의 `last_seen_at`·`expires_at`, `RESTORED` 이벤트의 `recorded_at`, 모두 `clock_timestamp()`)으로
 * «마지막 심장박동 → 리빌드 완료» 를 재고, JVM 의 `nanoTime` 으로 «죽임 → 둘째 기동 끝 → 리빌드 관측 → 재부착 관측» 을 잰다. 두 시계를 섞지
 * 않는다(Docker Postgres 의 시계는 이 JVM 시계와 어긋날 수 있다). 태스크 수는 죽이기 전과 재부착 뒤에 mimic 에서 센다.
 */
object TakeoverLoad {

    data class Beat(val period: Duration, val expiry: Duration) {
        val label: String get() = "${period.toMillis()} ms / ${expiry.toMillis()} ms"
    }

    fun run(report: Report, beats: List<Beat>, counts: List<Int>) {
        report.line("## 비정상 종료 뒤 순차 인수 — 마지막 심장박동에서 리빌드 완료까지")
        report.line()
        report.line("작업 지시: InspectAsset E0, 대상 하나(bay-7), 기체마다 하나. 로봇 단위가 모두 RUNNING 일 때 첫째를 멈춤 없이 죽이고 곧바로 둘째를 띄운다. 읽기 주기 50 ms.")
        report.line()
        val rows = mutableListOf<List<String>>()
        for (beat in beats) for (n in counts) {
            LoadStack(
                robots = n, withOps = false,
                hostArgs = listOf("--host.instance.heartbeat-period=${beat.period}", "--host.instance.heartbeat-expiry=${beat.expiry}"),
            ).use { stack ->
                report.floor(stack, "세트(기체 $n, 심장박동 ${beat.label})")
                rows += once(stack, beat, n)
            }
        }
        report.table(
            listOf(
                "심장박동 주기 / 만료", "실행 수", "죽인 때 마지막 심장박동 나이 ms(DB)", "마지막 심장박동 → 리빌드 완료 ms(DB)", "만료 → 리빌드 완료 ms(DB)",
                "죽임 → 둘째 기동 끝 ms", "죽임 → 리빌드 관측 ms", "죽임 → 재부착 관측 ms", "둘째 기동 직후 DEFERRED", "태스크 수 앞 → 뒤",
            ),
            rows,
        )
        report.line("«마지막 심장박동 → 리빌드 완료» 는 첫째 인스턴스 행의 `last_seen_at` 에서 마지막 `RESTORED` 이벤트의 `recorded_at` 까지다. «만료 → 리빌드 완료» 는 같은 이벤트까지를 `expires_at` 에서 잰 것이라 만료 폭을 뺀 몫(만료를 알아채는 pump 주기 + 리빌드)이다.")
        report.line()
    }

    private fun once(stack: LoadStack, beat: Beat, n: Int): List<String> {
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
        val beforeAge = stack.query(
            "SELECT extract(epoch FROM clock_timestamp() - last_seen_at) * 1000 FROM mission.host_instance WHERE instance_id = '$first'",
        ) { it.getDouble(1) }.single()
        stack.replaceHost()
        val up = System.nanoTime()
        val deferredAtStart = stack.hostGet("/host/executions").first["restore"]["rows"].count { it["result"].asText() == "DEFERRED" }
        val restoredSeen = poll(beat) { stack.hostGet("/host/executions").first["restore"]["rows"].let { rows -> rows.size() == n && rows.all { it["result"].asText() == "RESTORED" } } }
        val attachedSeen = poll(beat) { allRunning(stack.hostGet("/host/executions").first, n) }
        Thread.sleep(1_000)
        val tasksAfter = tasks(stack)
        check(tasksAfter == tasksBefore) { "재부착 뒤 태스크가 바뀌었다(중복 출발?): $tasksBefore → $tasksAfter" }

        val (lastSeen, expires) = stack.query(
            "SELECT last_seen_at, expires_at, stopped_at FROM mission.host_instance WHERE instance_id = '$first'",
        ) { check(it.getTimestamp(3) == null) { "죽인 인스턴스가 멈춤을 적었다" }; it.getTimestamp(1).toInstant() to it.getTimestamp(2).toInstant() }.single()
        val restoredAt = stack.query(
            "SELECT max(recorded_at) FROM mission.execution_journal_event WHERE kind = 'RESTORED'",
        ) { it.getTimestamp(1).toInstant() }.single()
        fun ms(from: Long, to: Long) = f((to - from) / 1e6)
        fun ms(from: Instant, to: Instant) = f(Duration.between(from, to).toNanos() / 1e6)
        return listOf(
            beat.label, "$n", f(beforeAge), ms(lastSeen, restoredAt), ms(expires, restoredAt),
            ms(killed, up), ms(killed, restoredSeen), ms(killed, attachedSeen), "$deferredAtStart / $n", "${tasksBefore.values.sumOf { it.size }} → ${tasksAfter.values.sumOf { it.size }}",
        )
    }

    /** 실행이 [n] 개이고 모두 로봇 단위(`T1`)가 RUNNING 인가. */
    private fun allRunning(view: JsonNode, n: Int): Boolean {
        val executions = view["executions"]
        return executions.size() == n && executions.all { e -> e["units"].any { it["unitId"].asText() == "T1" && it["state"].asText() == "RUNNING" } }
    }

    /** [done] 이 참이 될 때까지 50 ms 마다 읽고 그때의 `nanoTime` 을 돌려준다. 만료 폭 + 60초 상한. */
    private fun poll(beat: Beat, done: () -> Boolean): Long {
        val deadline = System.nanoTime() + beat.expiry.plusSeconds(60).toNanos()
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
