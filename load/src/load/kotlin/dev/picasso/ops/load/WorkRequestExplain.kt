package dev.picasso.ops.load

import dev.picasso.ops.service.requests.WorkRequests

/**
 * 핫스팟 5 — `work_request` 목록·검색. 행을 시드하고 운영 코드 `WorkRequests.list`·`summary` 의 질의를 EXPLAIN (ANALYZE, BUFFERS) 한다.
 * 질의 문자열은 `WorkRequests.kt` 의 것을 옮기고 매개변수를 값으로 펼쳤다(JDBC 는 이름 없는 문장이라 매번 값을 아는 계획이다).
 * 운영 코드 `list` 를 그대로 부른 실제 시간도 같이 잰다.
 *
 * 시드 분포: 상태 ACCEPTED 85%·REJECTED 5%·UNASSIGNED 5%·RECEIVED 3%·IN_DOUBT 2%, source mock-mes 70%·ops:manual 30%,
 * payload·normalized_order 각 약 0.8 KB, 전체의 60%(모두 mock-mes)에 결과 1판(g%10 이 4 인 것만 미ACK, 그래서 전체의 10% 가 미ACK).
 */
object WorkRequestExplain {

    private val PAD = "x".repeat(700)

    data class Q(val name: String, val sql: String)

    /**
     * `WorkRequests.list` 의 WHERE 를 값으로 펼친 것. 요청한 조건만 붙고 조건이 없으면 빈 문자열이다.
     * [before] 는 V24 앞(2026-11-11 main `ad5baf7`)의 검색 문자열(strpos)이다. 같은 회차에서 앞뒤를 견주려고 둔다. 미ACK 조건은 앞뒤가 같다.
     */
    private fun where(
        state: String?,
        search: String,
        unacknowledged: Boolean = false,
        needsConfirmation: Boolean = false,
        before: Boolean = false,
    ): String {
        val filters = buildList {
            if (needsConfirmation) add("state IN ('RECEIVED','DISPATCHING','IN_DOUBT')") else if (state != null) add("state='$state'")
            if (search.isNotEmpty()) {
                if (before) {
                    val s = search.replace("'", "''")
                    add("(strpos(external_order_id,'$s')>0 OR strpos(job_order_id,'$s')>0 OR strpos(source_system_id,'$s')>0)")
                } else {
                    val pattern = WorkRequests.containing(search).replace("'", "''")
                    add("(external_order_id LIKE '$pattern' OR job_order_id LIKE '$pattern' OR source_system_id LIKE '$pattern')")
                }
            }
            if (unacknowledged) add("source_system_id NOT LIKE 'ops:%' AND EXISTS($UNACKNOWLEDGED_LATEST AND u.receipt_id=ops.work_request.receipt_id)")
        }
        return if (filters.isEmpty()) "" else " WHERE " + filters.joinToString(" AND ")
    }

    /** `WorkRequests.UNACKNOWLEDGED_LATEST` 와 같은 문자열. */
    private const val UNACKNOWLEDGED_LATEST = "SELECT 1 FROM ops.work_result u WHERE u.acknowledged_at IS NULL" +
        " AND NOT EXISTS(SELECT 1 FROM ops.work_result n WHERE n.receipt_id=u.receipt_id AND n.version>u.version)"

    private const val PAGE = " ORDER BY received_at DESC,receipt_id LIMIT 50 OFFSET 0"

    /** `WorkRequests.narrowPageRows` 의 기본값. */
    private const val NARROW_PAGE_ROWS = 1000L

    /** 운영 코드의 검색 쪽 문장: 맞는 행이 문턱 이하면 trigram 으로 맞는 행을 읽어 정렬하고, 넘으면 수신 시각 인덱스를 걷는다. */
    private fun page(matches: Long, state: String?, search: String): String =
        if (matches <= NARROW_PAGE_ROWS) "SELECT * FROM (SELECT * FROM ops.work_request${where(state, search)} OFFSET 0) w$PAGE"
        else "SELECT * FROM ops.work_request${where(state, search)}$PAGE"

    private const val SUMMARY_UNACKNOWLEDGED = "SELECT count(*) FROM ops.work_request r WHERE r.source_system_id NOT LIKE 'ops:%'" +
        " AND EXISTS($UNACKNOWLEDGED_LATEST AND u.receipt_id=r.receipt_id)"

    private fun count(stack: LoadStack, where: String): Long =
        stack.query("SELECT count(*) FROM ops.work_request$where") { it.getLong(1) }.single()

    private fun queries(stack: LoadStack, n: Int): List<Q> = buildList {
        val w = where(null, "")
        add(Q("list 기본 count", "SELECT count(*) FROM ops.work_request$w"))
        add(Q("list 기본 1쪽(50)", "SELECT * FROM ops.work_request$w$PAGE"))
        add(Q("list 깊은 쪽(OFFSET ${n / 2})", "SELECT * FROM ops.work_request$w ORDER BY received_at DESC,receipt_id LIMIT 50 OFFSET ${n / 2}"))
        for ((label, search) in listOf("좁은 검색 'EXT-${n / 3}'" to "EXT-${n / 3}", "넓은 검색 'mes'" to "mes")) {
            val matches = count(stack, where(null, search))
            add(Q("(전) list $label count", "SELECT count(*) FROM ops.work_request${where(null, search, before = true)}"))
            add(Q("list $label count", "SELECT count(*) FROM ops.work_request${where(null, search)}"))
            add(Q("(전) list $label 1쪽", "SELECT * FROM ops.work_request${where(null, search, before = true)}$PAGE"))
            add(Q("list $label 1쪽 (맞는 행 $matches)", page(matches, null, search)))
        }
        val wr = where("IN_DOUBT", "")
        add(Q("list 상태 IN_DOUBT count", "SELECT count(*) FROM ops.work_request$wr"))
        add(Q("list 상태 IN_DOUBT 1쪽", "SELECT * FROM ops.work_request$wr$PAGE"))
        val wn = where(null, "", needsConfirmation = true)
        add(Q("list NEEDS_CONFIRMATION 1쪽", "SELECT * FROM ops.work_request$wn$PAGE"))
        addAll(unacknowledgedQueries(""))
        add(Q("summary 상태별", "SELECT state,count(*) AS n FROM ops.work_request GROUP BY state"))
        add(Q("summary 최근 5", "SELECT * FROM ops.work_request ORDER BY received_at DESC,receipt_id LIMIT 5"))
        add(Q("sweepStale(RECEIVED)", "SELECT receipt_id FROM ops.work_request WHERE state='RECEIVED' AND updated_at<clock_timestamp()-interval '1 millisecond'*CAST(300000 AS double precision)"))
        add(Q("결과 수집 job_order_id 조회", "SELECT receipt_id FROM ops.work_request WHERE job_order_id='JO-${n / 4}'"))
    }

    private fun unacknowledgedQueries(tag: String): List<Q> = listOf(
        Q("list 미ACK count$tag", "SELECT count(*) FROM ops.work_request${where(null, "", unacknowledged = true)}"),
        Q("list 미ACK 1쪽$tag", "SELECT * FROM ops.work_request${where(null, "", unacknowledged = true)}$PAGE"),
        Q("summary 미ACK 결과$tag", SUMMARY_UNACKNOWLEDGED),
    )

    fun run(report: Report, scales: List<Int>) {
        report.line("## 핫스팟 5 — `work_request` 목록·검색 (`WorkRequests.list`·`summary`)")
        report.line()
        report.line(
            "인덱스: V6 PK(receipt_id), UNIQUE(job_order_id), UNIQUE(source_system_id,external_order_id), work_request_received(received_at DESC,receipt_id) · " +
                "V20 work_request_state_received(state,received_at DESC,receipt_id), work_result_unacknowledged(receipt_id,version) WHERE acknowledged_at IS NULL · " +
                "V24 trigram GIN external_order_id·job_order_id·source_system_id. VACUUM ANALYZE 뒤 같은 질의를 3번 돌려 마지막 것을 적는다. " +
                "«(전)» 은 V24 앞(`ad5baf7`)의 검색 문자열(strpos)을 같은 회차·같은 데이터에 돌린 것이다(인덱스는 V24 뒤, strpos 는 그것을 못 쓴다).",
        )
        report.line()
        report.line(
            "미ACK 분포 둘: 시드 그대로(전체의 10% 가 최신 결과 미ACK, 상위가 받지 못하는 상황)와 «미ACK 0.1%»(평소). " +
                "0.1% 회차는 미ACK 결과를 그 수만 남기고 잠시 ACK 로 바꿨다가 되돌린다.",
        )
        report.line()
        val summary = mutableListOf<List<String>>()
        val plans = mutableListOf<Triple<Int, String, List<String>>>()
        fun measure(stack: LoadStack, n: Int, qs: List<Q>) = qs.forEach { q ->
            lateinit var plan: List<String>
            repeat(3) { plan = explain(stack, q.sql) }
            val exec = plan.lastOrNull { it.startsWith("Execution Time") }?.substringAfter(": ")?.substringBefore(" ms") ?: "-"
            val top = plan.firstOrNull { "Scan" in it }?.trim()?.substringBefore("  (")?.removePrefix("->")?.trim()
                ?: plan.first().trim().substringBefore("  (")
            val buffers = plan.firstOrNull { it.trim().startsWith("Buffers:") }?.trim()?.removePrefix("Buffers: ") ?: "-"
            summary += listOf("$n", q.name, exec, top, buffers)
            plans += Triple(n, q.name, plan)
        }
        fun timed(n: Int, calls: List<Pair<String, () -> Any>>) = calls.forEach { (label, call) ->
            repeat(3) { call() }
            val t = Timings()
            repeat(10) { t.time(call) }
            summary += listOf("$n", "실측 $label (10회)", "p50 ${f(t.p50)} / p95 ${f(t.p95)} / max ${f(t.max)}", "-", "-")
        }
        LoadStack(robots = 1).use { stack ->
            report.floor(stack, "세트")
            var seeded = 0
            for (n in scales) {
                seed(stack, seeded + 1, n)
                seeded = n
                stack.sql("VACUUM ANALYZE ops.work_request")
                stack.sql("VACUUM ANALYZE ops.work_result")
                measure(stack, n, queries(stack, n))
                // 운영 코드 그대로
                val requests = stack.opsBean(WorkRequests::class.java)
                timed(
                    n,
                    listOf(
                        "WorkRequests.list(\"\", null, 0, 50)" to { requests.list("", null, 0, 50) },
                        "WorkRequests.list(\"EXT-…\", null, 0, 50)" to { requests.list("EXT-${n / 3}", null, 0, 50) },
                        "WorkRequests.list(\"mes\", null, 0, 50)" to { requests.list("mes", null, 0, 50) },
                        "WorkRequests.list(\"\", null, 0, 50, unacknowledged)" to { requests.list("", null, 0, 50, "unacknowledged") },
                        "WorkRequests.summary()" to { requests.summary() },
                    ),
                )
                // 미ACK 0.1%: 미ACK 결과를 n/1000 개만 남기고 표시 시각으로 ACK 했다가 되돌린다.
                stack.sql(
                    "UPDATE ops.work_result SET acknowledged_at=timestamptz '1999-01-01 00:00:00+00' WHERE acknowledged_at IS NULL" +
                        " AND receipt_id NOT IN (SELECT receipt_id FROM ops.work_result WHERE acknowledged_at IS NULL ORDER BY receipt_id LIMIT ${n / 1000})",
                )
                stack.sql("VACUUM ANALYZE ops.work_result")
                measure(stack, n, unacknowledgedQueries(" · 미ACK 0.1%"))
                timed(
                    n,
                    listOf(
                        "WorkRequests.list(\"\", null, 0, 50, unacknowledged) · 미ACK 0.1%" to { requests.list("", null, 0, 50, "unacknowledged") },
                        "WorkRequests.summary() · 미ACK 0.1%" to { requests.summary() },
                    ),
                )
                stack.sql("UPDATE ops.work_result SET acknowledged_at=NULL WHERE acknowledged_at=timestamptz '1999-01-01 00:00:00+00'")
                stack.sql("VACUUM ANALYZE ops.work_result")
            }
        }
        report.table(listOf("행 수", "질의", "Execution Time ms", "첫 스캔 노드", "최상위 Buffers"), summary)
        report.line("### 계획 원문")
        report.line()
        plans.forEach { (n, name, plan) ->
            report.line("$n 행 — $name")
            report.line()
            report.line("```")
            plan.forEach(report::line)
            report.line("```")
            report.line()
        }
    }

    private fun seed(stack: LoadStack, from: Int, to: Int) {
        stack.sql(
            """INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state,normalized_order,received_at,updated_at)
               SELECT md5('w'||g)::uuid,
                 CASE WHEN g%10<7 THEN 'mock-mes' ELSE 'ops:manual' END,
                 'EXT-'||g,'h','{"pad":"$PAD"}'::jsonb,'JO-'||g,
                 CASE WHEN g%100<85 THEN 'ACCEPTED' WHEN g%100<90 THEN 'REJECTED' WHEN g%100<95 THEN 'UNASSIGNED' WHEN g%100<98 THEN 'RECEIVED' ELSE 'IN_DOUBT' END,
                 '{"workMasterId":"InspectAsset","pad":"$PAD"}'::jsonb,
                 now()-g*interval '1 second', now()-g*interval '1 second'
               FROM generate_series($from,$to) g""",
        )
        stack.sql(
            """INSERT INTO ops.work_result(receipt_id,version,instance_id,execution_id,response_id,response_version,snapshot,result_hash,acknowledged_at)
               SELECT md5('w'||g)::uuid,1,'inst','exec-'||g,'resp-'||g,1,'{"jobOrderId":"x"}'::jsonb,'hash',CASE WHEN g%5<4 THEN now() END
               FROM generate_series($from,$to) g WHERE g%10<6""",
        )
    }

    private fun explain(stack: LoadStack, sql: String): List<String> =
        stack.query("EXPLAIN (ANALYZE, BUFFERS) $sql") { it.getString(1) }
}
