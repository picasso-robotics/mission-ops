package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.site.Site
import dev.picasso.registry.PostgresSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S1d 완료 판정(P2·S1d 스펙 §3). 운영 서비스 API 로 제출 → 시험 요청 → 실행기 3종 PASS(`TESTED`) → 활성화 → 바인딩 →
 * 명칭 기록 → 시운전 완료(`humanoid-01`). `quadruped-01` 은 명칭을 티칭하지 않아 `SITE_NAMES_CONTRADICTED` 로 막히고,
 * 현장에서 다시 티칭하면 풀린다. 거절은 대응표(§8.3)대로 보인다.
 *
 * 순서가 있다. 앞 시험이 만든 개정판·빌드·바인딩을 뒤 시험이 쓴다. 실행기는 실제 시간으로 폴링하므로 시험 결과는 실제
 * 시간으로 기다린다(상한 60초).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CommissioningTest {

    companion object {
        private const val HUMANOID = "humanoid-01"
        private const val QUADRUPED = "quadruped-01"
        private lateinit var stack: E2eStack
        private var build = 0L
        private val revisions = mutableMapOf<String, Long>()

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start()
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }
    }

    private fun document(model: String): String =
        Files.readString(E2eStack.root.resolve("picasso/profile/profiles/$model.json")).replace("\r\n", "\n")

    private fun engineer(path: String, body: String? = null): JsonNode {
        val reply = stack.send("POST", path, "engineer", body = body)
        assertEquals(200, reply.status, "$path ${reply.body}")
        return reply.body!!
    }

    private fun rejection(path: String, body: String? = null, status: Int): JsonNode {
        val outcome = engineer(path, body)
        assertEquals("REJECTED" to status, outcome["result"].asText() to outcome["registryStatus"].asInt(), "$outcome")
        return outcome["rejection"]
    }

    private fun robot(id: String): JsonNode = stack.get("/api/robots")["robots"].single { it["robot"]["robotId"].asText() == id }

    private fun kinds(id: String): List<String> = robot(id)["blockers"].map { it["kind"].asText() }

    private fun revision(id: Long): JsonNode =
        stack.get("/api/profiles")["revisions"].single { it["revision"]["profileRevisionId"].asLong() == id }

    @Test
    @Order(1)
    fun `기체를 선언하면 바인딩이 없어 시운전 미완이고 UNBOUND 로 막힌다`() {
        listOf(HUMANOID to "HA-0001", QUADRUPED to "QB-0001").forEach { (id, serial) ->
            assertEquals(201, engineer("/api/robots", """{"robotId":"$id","serialNumber":"$serial"}""")["registryStatus"].asInt())
        }
        stack.site.advance(Duration.ofSeconds(31))

        val humanoid = robot(HUMANOID)
        assertEquals("CONFIRMED", humanoid["robot"]["status"].asText())
        assertEquals("INCOMPLETE", humanoid["commissioning"]["state"].asText())
        assertEquals(listOf("UNBOUND"), kinds(HUMANOID))
        assertEquals("ENGINEER", humanoid["blockers"].single()["owner"].asText())
        assertTrue(humanoid["binding"].isNull, "$humanoid")
    }

    @Test
    @Order(2)
    fun `제출하면 VALIDATED 로 보이고 같은 문서를 다시 내면 같은 개정판이다`() {
        build = engineer("/api/adapters", """{"vendor":"acme","name":"fleet"}""").let {
            val adapterId = stack.get("/api/adapters")["adapters"].single()["adapterId"].asLong()
            engineer("/api/adapters/$adapterId/versions", """{"version":"1.0.0","contractSemver":"0.10.0"}""")
            stack.get("/api/adapters")["adapters"].single()["versions"].single()["adapterVersionId"].asLong()
        }

        listOf("humanoid-a", "quadruped-b").forEach { model ->
            val first = engineer("/api/profile-revisions", document(model))
            assertEquals("SUCCEEDED" to 201, first["result"].asText() to first["registryStatus"].asInt(), "$first")
            val again = engineer("/api/profile-revisions", document(model))
            assertEquals("SUCCEEDED" to 200, again["result"].asText() to again["registryStatus"].asInt(), "$again")
        }

        val profiles = stack.get("/api/profiles")
        assertEquals("OK", profiles["registry"].asText())
        assertEquals("0.10.0", profiles["catalog"]["contractSemver"].asText())
        assertTrue(profiles["catalog"]["skillTypes"].size() > 0, "$profiles")
        profiles["revisions"].forEach { row ->
            assertEquals("VALIDATED", row["revision"]["status"].asText(), "$row")
            assertEquals("NONE", row["testRequest"].asText())
            revisions[row["revision"]["model"].asText()] = row["revision"]["profileRevisionId"].asLong()
        }
        assertEquals(setOf("humanoid-a", "quadruped-b"), revisions.keys)
    }

    @Test
    @Order(3)
    fun `시험 전 활성화는 ACTIVATION_REFUSED 이고 시험을 요청하면 현장 실행기가 TESTED 로 올린다`() {
        val refused = rejection("/api/profile-revisions/${revisions["humanoid-a"]}/activation", status = 409)
        assertEquals("ACTIVATION_REFUSED", refused["kind"].asText())
        assertTrue("status=VALIDATED" in refused["observed"].asText(), "$refused")

        revisions.values.forEach { id ->
            val requested = engineer("/api/profile-revisions/$id/test-requests")
            assertEquals("SUCCEEDED" to 201, requested["result"].asText() to requested["registryStatus"].asInt(), "$requested")
        }
        val deadline = Instant.now().plusSeconds(60)
        while (revisions.values.any { revision(it)["revision"]["status"].asText() != "TESTED" } && Instant.now().isBefore(deadline)) {
            Thread.sleep(500)
        }
        revisions.values.forEach { id ->
            val row = revision(id)
            assertEquals("TESTED", row["revision"]["status"].asText(), "$row")
            assertEquals("DONE", row["testRequest"].asText())
            listOf("CONTRACT", "NEGATIVE", "DETERMINISM").forEach { suite ->
                assertEquals("PASS" to Site.RUNNER_NAME, row["revision"]["suites"][suite].let { it["result"].asText() to it["ranBy"].asText() })
            }
        }
    }

    @Test
    @Order(4)
    fun `활성화와 바인딩 뒤에는 명칭 기록이 빠져 SITE_NAMES_UNREGISTERED 로 막힌다`() {
        revisions.values.forEach { id ->
            assertEquals("SUCCEEDED", engineer("/api/profile-revisions/$id/activation")["result"].asText())
            assertEquals("ACTIVE", revision(id)["revision"]["status"].asText())
        }
        mapOf(HUMANOID to "humanoid-a", QUADRUPED to "quadruped-b").forEach { (robotId, model) ->
            val bound = engineer("/api/robots/$robotId/binding", """{"adapterVersionId":$build,"profileRevisionId":${revisions[model]}}""")
            assertEquals("SUCCEEDED" to 201, bound["result"].asText() to bound["registryStatus"].asInt(), "$bound")
        }

        val humanoid = robot(HUMANOID)
        assertEquals(build, humanoid["binding"]["adapterVersionId"].asLong())
        assertEquals(revisions["humanoid-a"], humanoid["binding"]["profileRevisionId"].asLong())
        assertEquals("engineer/kim", humanoid["binding"]["boundBy"].asText())
        assertEquals(listOf("SITE_NAMES_UNREGISTERED"), kinds(HUMANOID))
        assertEquals(listOf("destination", "location"), humanoid["binding"]["siteNameKeys"].map { it.asText() })
    }

    @Test
    @Order(5)
    fun `명칭을 기록하면 티칭한 기체는 시운전 완료가 되고 티칭 안 한 기체는 CONTRADICTED 로 막힌다`() {
        // 두 기체 다 Order 1 의 보고로 이미 답했다(humanoid 2개, quadruped 0개). 그래서 기록하자마자 판정이 선다.
        listOf(HUMANOID, QUADRUPED).forEach { id ->
            assertEquals("SUCCEEDED", engineer("/api/robots/$id/site-names")["result"].asText())
        }

        val humanoid = robot(HUMANOID)
        assertEquals("COMPLETE", humanoid["commissioning"]["state"].asText(), "$humanoid")
        assertEquals(emptyList(), kinds(HUMANOID))
        assertEquals(2, humanoid["binding"]["siteNamesCount"].asInt())

        val quadruped = robot(QUADRUPED)
        assertEquals("INCOMPLETE", quadruped["commissioning"]["state"].asText())
        val blocker = quadruped["blockers"].single()
        assertEquals("SITE_NAMES_CONTRADICTED" to "SITE", blocker["kind"].asText() to blocker["owner"].asText())
        assertEquals(false, blocker["inScreen"].asBoolean())
    }

    @Test
    @Order(6)
    fun `현장에서 다시 티칭하면 기록은 그대로 둔 채 다음 보고로 풀린다`() {
        stack.site.teach(QUADRUPED, listOf("dock-3"))
        stack.site.advance(Duration.ofSeconds(31))

        val quadruped = robot(QUADRUPED)
        assertEquals("COMPLETE", quadruped["commissioning"]["state"].asText(), "$quadruped")
        assertEquals(emptyList(), kinds(QUADRUPED))
    }

    @Test
    @Order(7)
    fun `개정판 거절이 대응표대로 보인다`() {
        assertEquals("PROFILE_UNREADABLE", rejection("/api/profile-revisions", "{not json", 400)["kind"].asText())

        // 같은 번호의 다른 문서. 공백 하나라도 문서 해시가 다르다.
        val changed = document("humanoid-a") + "\n"
        val notMonotonic = rejection("/api/profile-revisions", changed, 409)
        assertEquals("REVISION_NOT_MONOTONIC", notMonotonic["kind"].asText())
        assertTrue("highest=2" in notMonotonic["observed"].asText(), "$notMonotonic")

        listOf("test-requests", "activation").forEach { op ->
            assertEquals("UNKNOWN_REVISION", rejection("/api/profile-revisions/999999/$op", status = 404)["kind"].asText())
        }

        // 읽히지만 검증에 실패한 문서는 DRAFT 로 저장된다. 거절이 아니다.
        val draft = document("humanoid-a").replace("\"revision\": 2,", "\"revision\": 3,").replace("\"PAYLOAD_LOST\"", "\"NOT_A_REGISTERED_ERROR\"")
        val saved = engineer("/api/profile-revisions", draft)
        assertEquals("SUCCEEDED" to 201, saved["result"].asText() to saved["registryStatus"].asInt(), "$saved")
        val draftId = stack.get("/api/profiles")["revisions"].single { it["revision"]["revision"].asInt() == 3 }
        assertEquals("DRAFT", draftId["revision"]["status"].asText())
        assertTrue(draftId["revision"]["reasons"].size() > 0)
        val id = draftId["revision"]["profileRevisionId"].asLong()
        assertEquals("REVISION_NOT_TESTABLE", rejection("/api/profile-revisions/$id/test-requests", status = 409)["kind"].asText())
        revisions["draft"] = id
    }

    @Test
    @Order(8)
    fun `바인딩·명칭 거절이 본문의 reason 으로 갈리고 그 기체로 바로 간다`() {
        val active = revisions["humanoid-a"]!!
        fun bind(robotId: String, buildId: Long, revisionId: Long) =
            """{"adapterVersionId":$buildId,"profileRevisionId":$revisionId}""".let { rejectionOf("/api/robots/$robotId/binding", it) }

        assertEquals("UNKNOWN_ROBOT" to "ghost", bind("ghost", build, active).let { it["kind"].asText() to it["target"].asText() })
        assertEquals("UNKNOWN_REVISION", bind(HUMANOID, build, 999_999)["kind"].asText())
        assertEquals("UNKNOWN_BUILD", bind(HUMANOID, 999_999, active)["kind"].asText())
        assertEquals("REVISION_NOT_ACTIVE", bind(HUMANOID, build, revisions["draft"]!!)["kind"].asText())

        val adapterId = stack.get("/api/adapters")["adapters"].single()["adapterId"].asLong()
        engineer("/api/adapters/$adapterId/versions", """{"version":"0.0.1","contractSemver":"0.0.1"}""")
        val old = stack.get("/api/adapters")["adapters"].single()["versions"].single { it["version"].asText() == "0.0.1" }["adapterVersionId"].asLong()
        assertEquals("CONTRACT_TOO_OLD", bind(HUMANOID, old, active)["kind"].asText())

        assertEquals("NO_ACTIVE_BINDING" to "ghost", rejectionOf("/api/robots/ghost/site-names", null).let { it["kind"].asText() to it["target"].asText() })

        val retired = stack.send("POST", "/api/robots/$QUADRUPED/retirement", "operator", body = """{"reason":"정비"}""")
        assertEquals(200, retired.status)
        val refused = bind(QUADRUPED, build, revisions["quadruped-b"]!!)
        assertEquals("ROBOT_RETIRED" to "OPERATOR", refused["kind"].asText() to refused["owner"].asText())
        assertEquals("RETIRED", robot(QUADRUPED)["commissioning"]["state"].asText())
        assertEquals(emptyList(), kinds(QUADRUPED).filter { it.startsWith("SITE_NAMES") || it == "UNBOUND" })
    }

    private fun rejectionOf(path: String, body: String?): JsonNode {
        val outcome = engineer(path, body)
        assertEquals("REJECTED", outcome["result"].asText(), "$outcome")
        return outcome["rejection"]
    }

    @Test
    @Order(9)
    fun `사전 거절은 registry 에 닿지 않고 조작 기록에 남지 않는다`() {
        val before = stack.get("/api/operations").size()

        val noTarget = stack.send("POST", "/api/robots/$HUMANOID/binding", "engineer", body = """{"adapterVersionId":$build}""")
        assertEquals(400 to "BINDING_TARGET_REQUIRED", noTarget.status to noTarget.body!!["error"].asText())
        listOf(
            "/api/profile-revisions/${revisions["humanoid-a"]}/activation" to null,
            "/api/profile-revisions/${revisions["humanoid-a"]}/test-requests" to null,
            "/api/robots/$HUMANOID/site-names" to null,
            "/api/robots/$HUMANOID/binding" to """{"adapterVersionId":$build,"profileRevisionId":${revisions["humanoid-a"]}}""",
            "/api/profile-revisions" to document("humanoid-a"),
        ).forEach { (path, body) -> assertEquals(403, stack.send("POST", path, "operator", body = body).status, path) }
        assertEquals(415, stack.send("POST", "/api/profile-revisions", "engineer", body = document("humanoid-a"), contentType = "text/plain").status)
        val empty = stack.send("POST", "/api/profile-revisions", "engineer", body = "")
        assertEquals(400, empty.status, "${empty.body}")

        assertEquals(before, stack.get("/api/operations").size())
    }

    @Test
    @Order(10)
    fun `조작 기록과 registry 감사 기록의 행위자가 같다`() {
        val targets = stack.get("/api/operations").map { it["target"].asText() }.toSet()
        assertTrue("profile picasso-ref/humanoid-a#2" in targets, "$targets")
        assertTrue("revision ${revisions["humanoid-a"]}" in targets, "$targets")
        assertTrue("robot $HUMANOID" in targets, "$targets")

        listOf("PROFILE_REVISION_SUBMIT", "TEST_REQUEST", "PROFILE_REVISION_ACTIVATE", "ROBOT_BIND", "SITE_NAMES_REGISTERED").forEach { op ->
            val actors = PostgresSupport.queryAll("SELECT DISTINCT actor FROM audit_log WHERE operation = '$op'") { it.getString(1) }
            assertEquals(listOf("engineer/kim"), actors, op)
        }
        assertNull(stack.get("/api/profiles")["revisions"].firstOrNull { it["revision"]["createdBy"].asText() != "engineer/kim" })
    }
}
