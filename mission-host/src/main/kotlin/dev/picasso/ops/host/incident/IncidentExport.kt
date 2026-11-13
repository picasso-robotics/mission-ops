package dev.picasso.ops.host.incident

import dev.picasso.host.BundleWriter
import dev.picasso.middleware.IncidentBundle
import dev.picasso.middleware.JobResponse
import dev.picasso.middleware.RemedySearchRecord
import java.nio.file.Path
import java.time.Instant

/**
 * 인시던트 고리로 내는 파일(picasso `host-kit` 의 [BundleWriter], picasso 한계 §15.176). 인시던트 대장, 조치 탐색 대장, 안내, 작업
 * 응답을 [dir] 한 곳에 둔다. incident-recovery·incident-narrator 가 이 파일을 읽는다.
 *
 * ## 사본은 덧붙이기만 한다
 *
 * 미들웨어의 목록은 줄어든다. 이 호스트가 정착한 실행을 놓으면([dev.picasso.middleware.Middleware.forget]) 그 실행의 작업 응답이
 * 아웃박스에서 빠지고, 인시던트와 탐색 기록은 보존 상한([dev.picasso.middleware.Retention])을 넘으면 오래된 것부터 내린다. 그 목록을
 * 그대로 넘기면 [BundleWriter] 가 받는 파일이 줄어 읽는 쪽이 이력을 잃는다. `host-kit` 의 약속(작업 응답은 인스턴스 처음부터의
 * 전체 이력)을 지키려고 여기서 id 마다 한 줄을 들고 그 사본을 넘긴다. 인시던트는 나중에 판단(`resolution`)이 붙으므로 미들웨어에 아직
 * 있는 동안은 마지막 본 것으로 바꾼다. 줄의 순서는 처음 본 순서다. [BundleWriter] 는 줄 수가 늘 때만 다시 쓰므로 판단만 붙은 변경은
 * 다음 새 줄과 함께 나간다(picasso 참조 실행기 `ScenarioHost` 와 같다).
 *
 * 사본은 이 프로세스 메모리에만 있고 상한이 없다. 재기동하면 새 인스턴스의 것부터 다시 쌓인다(작업 응답 줄은 인스턴스 id 를 든다).
 * 개념 증명 범위다.
 *
 * 호스트 잠금 아래에서만 부른다. [BundleWriter] 는 부르는 쪽이 직렬화한다.
 */
class IncidentExport(dir: Path, private val instanceId: String) {
    private val writer = BundleWriter(dir)
    private val incidents = LinkedHashMap<String, IncidentBundle>()
    private val searches = LinkedHashMap<String, RemedySearchRecord>()
    private val responses = LinkedHashMap<String, JobResponse>()

    /** 새로 본 줄을 사본에 더하고, 늘었으면 파일을 다시 쓴다. [virtualNow] 는 안내에 싣는 시각이다. */
    fun observe(
        incidents: List<IncidentBundle>,
        searches: List<RemedySearchRecord>,
        responses: List<JobResponse>,
        virtualNow: Instant,
    ) {
        incidents.forEach { this.incidents[it.incidentId] = it }
        searches.forEach { this.searches.putIfAbsent(it.searchId, it) }
        responses.forEach { this.responses.putIfAbsent(it.jobResponseId, it) }
        writer.snapshot(this.incidents.values.toList(), this.searches.values.toList(), virtualNow)
        writer.carry(this.responses.values.toList(), instanceId)
    }
}
