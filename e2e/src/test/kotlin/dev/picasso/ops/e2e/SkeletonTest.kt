package dev.picasso.ops.e2e

import dev.picasso.registry.PostgresSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * S1a 완료 판정(스펙 §3). 전체를 한 JVM 에 띄우고 운영 서비스의 기체 목록이 빈 목록을 돌려준다.
 * registry 를 멈추면 화면 전체 상태가 «모름» 이 된다. 순서가 있다: 마지막 시험이 registry 를 멈춘다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkeletonTest {

    companion object {
        private lateinit var stack: E2eStack

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

    @Test
    @Order(1)
    fun `운영 서비스의 기체 목록 조회가 빈 목록을 돌려준다`() {
        // 선언 전 mimic 의 보고는 registry 가 거절하고 남기지 않는다(스펙 §6). 보고가 흘러도 목록은 비어 있다.
        repeat(2) { stack.site.advance(Duration.ofSeconds(31)) }
        val view = stack.get("/api/robots")
        assertEquals("OK", view["registry"].asText())
        assertEquals(0, view["robots"].size(), view.toString())
        assertFalse(view["robots"].isNull)
        assertFalse(view["robotsAsOf"].isNull)
    }

    @Test
    @Order(2)
    fun `ops 스키마는 운영 서비스가 올리고 조작 기록은 비어 있다`() {
        // Flyway 가 스키마를 만들 때 남기는 표식 행은 version 이 널이라 뺀다.
        val versions = PostgresSupport.queryAll(
            "SELECT version FROM ops.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
        ) { it.getString(1) }
        assertEquals((1..24).map(Int::toString), versions)
        assertEquals(0, stack.get("/api/operations").size())
    }

    @Test
    @Order(3)
    fun `registry 를 멈추면 전체 상태가 모름이고 직전 목록을 지우지 않는다`() {
        val declared = stack.send("POST", "/api/robots", "engineer", body = """{"robotId":"e2e-held-01","serialNumber":"E2E-0001"}""")
        assertEquals("SUCCEEDED", declared.body!!["result"].asText(), declared.toString())
        val before = stack.get("/api/robots")
        assertEquals(listOf("e2e-held-01"), before["robots"].map { it["robot"]["robotId"].asText() })

        stack.site.stopRegistry()

        val after = stack.get("/api/robots")
        assertEquals("REGISTRY_SILENT", after["registry"].asText())
        assertEquals(listOf("e2e-held-01"), after["robots"].map { it["robot"]["robotId"].asText() })
        assertEquals(before["robotsAsOf"].asText(), after["robotsAsOf"].asText())
    }
}
