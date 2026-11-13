package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * 공용 시운전 픽스처(S3a 스펙 §11). `CommissioningTest` 의 단계를 한 함수로 묶어 명부의 두 기체를 시운전 완료로 만든다.
 *
 * 단계: 기체 선언 → 보고 → 어댑터 제품·빌드 등록 → 리비전 제출 → 시험 요청(현장 실행기가 실제 시간으로 돔) → 활성화 → 바인딩 →
 * 명칭 기록. `quadruped-01` 은 명부에서 명칭을 티칭하지 않았으므로 선언 전에 현장에서 티칭한다(`Site.teach`). 그래야 첫 보고에
 * 기체가 아는 명칭이 실려 명칭을 기록하자마자 시운전이 완료된다.
 *
 * 연결 신선은 registry 수신 시각(실제 시각) 기준이고 생존 보고는 현장이 시계를 밀 때만 나간다. 실행기를 실제 시간으로 기다리는
 * 단계가 있으므로 마지막에 시계를 한 번 밀어 생존 보고를 내고 끝낸다. 이 함수는 단계마다의 중간 상태를 단언하지 않는다. 그것은
 * `CommissioningTest` 의 몫이다. 끝 상태(둘 다 시운전 완료, 연결 신선)만 확인하고 아니면 멈춘다.
 */
object Commissioned {

    const val HUMANOID = "humanoid-01"
    const val QUADRUPED = "quadruped-01"

    /** 현장에서 `quadruped-01` 에 티칭하는 명칭. `humanoid-01` 의 명부 값과 같다. */
    val QUADRUPED_SITE_NAMES = listOf("dock-3", "bay-7")

    /** 기체 → 일련번호·기종 프로파일. 명부(`site/robots.json`)와 같다. */
    private val ROBOTS = linkedMapOf(HUMANOID to ("HA-0001" to "humanoid-a"), QUADRUPED to ("QB-0001" to "quadruped-b"))

    /** 시운전에 쓴 식별자. 시험이 더 조작할 때 쓴다. */
    data class Ids(val buildId: Long, val revisions: Map<String, Long>)

    /** 상태 발행이 생존 보고다. 보고 주기(가상 30초)를 넘겨 민다. */
    private val REPORT = Duration.ofSeconds(31)

    /** 실행기를 기다리는 상한(실제 시간). */
    private val RUNNER_WAIT = Duration.ofSeconds(60)

    fun complete(stack: E2eStack): Ids {
        stack.site.teach(QUADRUPED, QUADRUPED_SITE_NAMES)
        ROBOTS.forEach { (robotId, robot) ->
            engineer(stack, "/api/robots", """{"robotId":"$robotId","serialNumber":"${robot.first}"}""")
        }
        stack.site.advance(REPORT)

        engineer(stack, "/api/adapters", """{"vendor":"acme","name":"fleet"}""")
        val adapterId = stack.get("/api/adapters")["adapters"].single()["adapterId"].asLong()
        engineer(stack, "/api/adapters/$adapterId/versions", """{"version":"1.0.0","contractSemver":"0.11.0"}""")
        val buildId = stack.get("/api/adapters")["adapters"].single()["versions"].single()["adapterVersionId"].asLong()

        ROBOTS.values.forEach { (_, model) -> engineer(stack, "/api/profile-revisions", document(model)) }
        val revisions = stack.get("/api/profiles")["revisions"].associate {
            it["revision"]["model"].asText() to it["revision"]["profileRevisionId"].asLong()
        }
        revisions.values.forEach { engineer(stack, "/api/profile-revisions/$it/test-requests") }
        val deadline = Instant.now().plus(RUNNER_WAIT)
        while (!revisions.values.all { status(stack, it) == "TESTED" }) {
            check(Instant.now().isBefore(deadline)) { "실행기가 ${RUNNER_WAIT.seconds}초 안에 TESTED 로 올리지 않았다: ${stack.get("/api/profiles")}" }
            Thread.sleep(500)
        }
        revisions.values.forEach { engineer(stack, "/api/profile-revisions/$it/activation") }

        ROBOTS.forEach { (robotId, robot) ->
            val revision = revisions.getValue(robot.second)
            engineer(stack, "/api/robots/$robotId/binding", """{"adapterVersionId":$buildId,"profileRevisionId":$revision}""")
            engineer(stack, "/api/robots/$robotId/site-names")
        }
        stack.site.advance(REPORT)

        val robots = stack.get("/api/robots")["robots"]
        ROBOTS.keys.forEach { robotId ->
            val robot = robots.single { it["robot"]["robotId"].asText() == robotId }
            check(robot["commissioning"]["state"].asText() == "COMPLETE" && robot["connection"].asText() == "FRESH") {
                "$robotId 가 시운전 완료·연결 신선이 아니다: $robot"
            }
        }
        return Ids(buildId, revisions)
    }

    private fun document(model: String): String =
        Files.readString(E2eStack.root.resolve("picasso/profile/profiles/$model.json")).replace("\r\n", "\n")

    private fun status(stack: E2eStack, revisionId: Long): String =
        stack.get("/api/profiles")["revisions"].single { it["revision"]["profileRevisionId"].asLong() == revisionId }["revision"]["status"].asText()

    /** 엔지니어 모드로 부르고 registry 가 반영했는지(SUCCEEDED) 본다. 아니면 멈춘다. */
    private fun engineer(stack: E2eStack, path: String, body: String? = null): JsonNode {
        val reply = stack.send("POST", path, "engineer", body = body)
        check(reply.status == 200 && reply.body!!["result"].asText() == "SUCCEEDED") { "$path → ${reply.status} ${reply.body}" }
        return reply.body!!
    }
}
