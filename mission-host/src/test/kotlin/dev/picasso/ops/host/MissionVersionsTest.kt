package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.host.HostBench.Companion.GHOST
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.registry.PostgresSupport
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 실행 호스트의 임무 버전: 저장·카탈로그·검증·모의 실행·활성화 REST(S3b 스펙 §6, §10 의 호스트 행).
 *
 * 정의는 템플릿 엔드포인트에서 받아 쓴다. 틀린 변경은 그 글자를 바꿔 만든다.
 */
class MissionVersionsTest {

    private val robots = listOf(HUMANOID, QUADRUPED)

    private fun HostBench.templates(): Map<String, String> =
        get("/host/missions/templates/$PSR")["templates"].associate { it["id"].asText() to it["definition"].asText() }

    private fun HostBench.dataV1(): String = templates().getValue("DATA_V1")

    private fun HostBench.arrivalWait(): String = templates().getValue("ARRIVAL_WAIT")

    private fun HostBench.saveDraft(definition: String, requestId: UUID = UUID.randomUUID(), workMaster: String = PSR): HostBench.Reply =
        post(
            "/host/missions/$workMaster/drafts",
            JSON.writeValueAsString(mapOf("definition" to definition, "actor" to "lee", "requestId" to requestId.toString())),
        )

    private fun HostBench.draft(definition: String): Long {
        val saved = saveDraft(definition)
        assertEquals(200, saved.status, saved.body.toString())
        return saved.body!!["draft"]["draftId"].asLong()
    }

    private fun HostBench.validate(draftId: Long, ids: List<String> = robots): JsonNode {
        val reply = post("/host/missions/drafts/$draftId/validate", JSON.writeValueAsString(mapOf("robotIds" to ids)))
        assertEquals(200, reply.status, reply.body.toString())
        return reply.body!!
    }

    private fun HostBench.mockRun(draftId: Long, ids: List<String> = robots, requestId: UUID = UUID.randomUUID()): JsonNode {
        val reply = post(
            "/host/missions/drafts/$draftId/mock-run",
            JSON.writeValueAsString(mapOf("robotIds" to ids, "requestId" to requestId.toString())),
        )
        assertEquals(200, reply.status, reply.body.toString())
        return reply.body!!
    }

    private fun HostBench.activate(draftId: Long, ids: List<String> = robots, requestId: UUID = UUID.randomUUID()): HostBench.Reply =
        post(
            "/host/missions/drafts/$draftId/activate",
            JSON.writeValueAsString(mapOf("actor" to "lee", "reason" to "랙 도착 대기 도입", "robotIds" to ids, "requestId" to requestId.toString())),
        )

    /** 초안 저장 → 모의 실행 통과 → 활성화. 선 버전 번호를 돌려준다. */
    private fun HostBench.activated(definition: String): Int {
        val draftId = draft(definition)
        val mock = mockRun(draftId)
        assertEquals("PASSED", mock["result"].asText(), mock.toString())
        val activation = activate(draftId)
        assertEquals(200, activation.status, activation.body.toString())
        assertEquals("ACTIVATED", activation.body!!["result"].asText(), activation.body.toString())
        return activation.body["version"].asInt()
    }

    private fun HostBench.activeVersion(): JsonNode = get("/host/missions/$PSR")["active"]["version"]

    @Test fun `새 미션은 영속 샘플로 검증 모의 실행 활성화하고 숫자 버전으로 실제 제출된다`() {
        HostBench().use {bench ->
            val profile=JSON.readTree(javaClass.getResourceAsStream("/mission-profile-inspection.json"))
            val wm=profile["workMasterId"].asText()
            val order=JSON.readTree("""{"jobOrderId":"NEW-MISSION-1","workMasterId":"ManufacturingInspection","version":1,"requiredEvidence":"E0","parameters":{},"materialRequirements":[],"equipmentRequirements":[{"id":"T1","equipmentUse":"mission_target","properties":{"location":"bay-7"}}]}""") as com.fasterxml.jackson.databind.node.ObjectNode
            assertEquals("UNPUBLISHED",bench.get("/host/missions/$wm")["active"]["source"].asText())
            assertEquals(400,bench.post("/host/job-orders",request(order.toString(),"candidates",HUMANOID)).status)
            val saved=bench.post("/host/missions/$wm/drafts",JSON.writeValueAsString(mapOf("definition" to profile["definition"].toString(),"sampleOrder" to order,"actor" to "lee","requestId" to UUID.randomUUID().toString())))
            assertEquals(200,saved.status,saved.body.toString());val id=saved.body!!["draft"]["draftId"].asLong()
            assertEquals("PASSED",bench.validate(id)["result"].asText())
            val mock=bench.mockRun(id);assertEquals("PASSED",mock["result"].asText(),mock.toString())
            assertEquals("ACTIVATED",bench.activate(id).body!!["result"].asText())
            order.put("missionVersion","1")
            val run=bench.post("/host/job-orders",request(order.toString(),"candidates",HUMANOID));assertEquals("ACCEPTED",run.body!!["result"].asText(),run.body.toString())
            val execution=bench.execution(run.body["executionId"].asText())!!
            assertEquals(wm,execution["workMasterId"].asText());assertEquals(1,execution["missionVersion"].asInt())
            assertEquals("inspect",execution["units"][0]["skillType"].asText())
        }
    }

    @Test fun `배포 고정은 활성 버전 변경 뒤에도 원래 미션으로 실행하고 글로벌 활성 버전을 바꾸지 않는다`() {
        HostBench().use {bench ->
            assertEquals(1,bench.activated(bench.dataV1()))
            assertEquals(2,bench.activated(bench.arrivalWait()))
            val order=JSON.readTree(rack("PINNED-1","RACK-204.S01")) as com.fasterxml.jackson.databind.node.ObjectNode
            order.put("missionVersion","1")
            val submitted=bench.post("/host/job-orders",request(order.toString(),"candidates",HUMANOID))
            assertEquals("ACCEPTED",submitted.body!!["result"].asText(),submitted.body.toString())
            val execution=bench.execution(submitted.body["executionId"].asText())!!
            assertEquals(1,execution["missionVersion"].asInt())
            assertTrue(execution["units"].none{it["unitId"].asText()=="rack-arrival"})
            assertEquals(2,bench.activeVersion().asInt())
            order.put("jobOrderId","PINNED-BAD").put("missionVersion","99999")
            assertEquals(400,bench.post("/host/job-orders",request(order.toString(),"candidates",QUADRUPED)).status)
            assertEquals(2,bench.activeVersion().asInt())
        }
    }
    @Test
    fun `버전이 없으면 개요는 코드 정의를 내고 템플릿 셋은 버전 1 모양과 ABORTED 대기와 운영자 보류 대기다`() {
        HostBench().use { bench ->
            val overview = bench.get("/host/missions/$PSR")
            assertEquals(PSR, overview["workMasterId"].asText())
            assertTrue(overview["active"]["version"].isNull, overview.toString())
            assertEquals("CODE", overview["active"]["source"].asText())
            assertTrue(overview["active"]["detail"].isNull)
            assertEquals(0, overview["versions"].size())
            assertEquals(0, overview["drafts"].size())

            val templates = bench.get("/host/missions/templates/$PSR")["templates"]
            assertEquals(listOf("DATA_V1", "ARRIVAL_WAIT", "ARRIVAL_WAIT_HOLD"), templates.map { it["id"].asText() })
            val wait = JSON.readTree(templates[1]["definition"].asText())["steps"][0]
            assertEquals("rack-arrival", wait["id"].asText())
            assertEquals("rack_present", wait["signal"].asText())
            assertEquals("true", wait["expect"].asText())
            assertEquals(120, wait["deadlineSeconds"].asInt())
            assertEquals("ABORTED", wait["onDeadline"].asText())
            assertEquals(1, JSON.readTree(templates[0]["definition"].asText())["steps"].size())

            // 운영자 보류 대기는 기한과 기한 뒤 동작만 다르다(S4a 스펙 T3).
            val hold = JSON.readTree(templates[2]["definition"].asText())
            assertEquals(20, hold["steps"][0]["deadlineSeconds"].asInt())
            assertEquals("OPERATOR_HOLD", hold["steps"][0]["onDeadline"].asText())
            (hold["steps"][0] as com.fasterxml.jackson.databind.node.ObjectNode).put("deadlineSeconds", 120).put("onDeadline", "ABORTED")
            assertEquals(JSON.readTree(templates[1]["definition"].asText()), hold)
        }
    }

    @Test
    fun `초안은 읽을 수 없는 문서도 저장되고 검증은 UNREADABLE 과 JSON 경로를 낸다`() {
        HostBench().use { bench ->
            val broken = bench.dataV1().replace("\"skill\": \"pick_place\",", "").replace("\"maxEvidence\": \"E2\"", "\"maxEvidence\": 2")
            val requestId = UUID.randomUUID()
            val saved = bench.saveDraft(broken, requestId)
            assertEquals(200, saved.status, saved.body.toString())
            val draft = saved.body!!["draft"]
            assertEquals(broken, draft["definition"].asText())
            assertEquals("lee", draft["savedBy"].asText())
            assertEquals(requestId.toString(), draft["requestId"].asText())
            assertTrue(draft["lastMockRun"].isNull)

            val validated = bench.validate(draft["draftId"].asLong())
            assertEquals("REFUSED", validated["result"].asText(), validated.toString())
            val refusals = validated["refusals"]
            assertTrue(refusals.all { it["kind"].asText() == "UNREADABLE" && it["nodeId"].isNull }, refusals.toString())
            val observed = refusals.map { it["observed"].asText() }
            assertTrue(observed.any { it.startsWith("$.maxEvidence") }, observed.toString())
            assertTrue(observed.any { it.startsWith("$.steps[0].skill") }, observed.toString())
            assertEquals("ENGINEER", refusals[0]["owner"].asText())
            assertEquals("정의 JSON 의 틀린 칸을 고친다", refusals[0]["nextAction"].asText())

            // 읽을 수 없는 초안은 모의 실행도 활성화도 거부이고 모의 실행 표에 남지 않는다.
            val mock = bench.mockRun(draft["draftId"].asLong())
            assertEquals("REFUSED", mock["result"].asText())
            assertTrue(mock["mockRun"].isNull)
            assertEquals("REFUSED", bench.activate(draft["draftId"].asLong()).body!!["result"].asText())
            assertTrue(bench.get("/host/missions/$PSR")["drafts"].single()["lastMockRun"].isNull)
        }
    }

    @Test
    fun `정의의 WorkMaster 가 경로와 다르면 UNREADABLE 이고 경로의 WorkMaster 가 PrepareSequencedRack 이 아니면 400 이다`() {
        HostBench().use { bench ->
            val other = bench.dataV1().replace("\"workMasterId\": \"PrepareSequencedRack\"", "\"workMasterId\": \"InspectAsset\"")
            val draftId = bench.draft(other)
            val validated = bench.validate(draftId)
            assertEquals("REFUSED", validated["result"].asText(), validated.toString())
            val refusal = validated["refusals"].single()
            assertEquals("UNREADABLE", refusal["kind"].asText())
            assertTrue(refusal["observed"].asText().startsWith("$.workMasterId"), refusal.toString())
            assertEquals(PSR, refusal["expected"].asText())

            val activation = bench.activate(draftId).body!!
            assertEquals("REFUSED", activation["result"].asText())
            assertTrue(activation["version"].isNull)

            listOf("InspectAsset", "DeliverContainer").forEach { workMaster ->
                val saved = bench.saveDraft(bench.dataV1(), workMaster = workMaster)
                assertEquals(400, saved.status, workMaster)
                assertEquals("UNKNOWN_WORK_MASTER", saved.body!!["error"].asText())
                assertEquals(400, bench.fetch("/host/missions/$workMaster").status)
                assertEquals(400, bench.fetch("/host/missions/templates/$workMaster").status)
            }
        }
    }

    @Test
    fun `신호 사양에 없는 신호는 검증과 활성화에서 SIGNAL_NOT_IN_SPEC 이고 해결 담당과 바로 갈 작업을 낸다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val draftId = bench.draft(bench.arrivalWait().replace("\"signal\": \"rack_present\"", "\"signal\": \"rack_ready\""))
            val validated = bench.validate(draftId)
            assertEquals("REFUSED", validated["result"].asText(), validated.toString())
            val refusal = validated["refusals"].single()
            assertEquals("SIGNAL_NOT_IN_SPEC", refusal["kind"].asText())
            assertEquals("rack-arrival", refusal["nodeId"].asText())
            assertEquals("rack_ready", refusal["observed"].asText())
            assertEquals("신호 사양의 이름 중 하나(guard_closed, lot_code, rack_present)", refusal["expected"].asText())
            assertEquals("ENGINEER", refusal["owner"].asText())
            assertEquals("신호 이름을 고치거나 신호 사양에 더한다", refusal["nextAction"].asText())
            assertTrue(refusal["basisVersion"].isNull)
            assertEquals(validated["checkedAt"].asText(), refusal["checkedAt"].asText())

            val activation = bench.activate(draftId).body!!
            assertEquals("REFUSED", activation["result"].asText(), activation.toString())
            assertEquals("SIGNAL_NOT_IN_SPEC", activation["refusals"].single()["kind"].asText())
            assertTrue(bench.activeVersion().isNull, "거부된 활성화가 버전을 세웠다")
        }
    }

    @Test
    fun `대기 노드 버전 2 모양은 셀 대역의 신호 사양으로 검증을 지나고 안전 신호 대기는 거부된다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val passed = bench.validate(bench.draft(bench.arrivalWait()))
            assertEquals("PASSED", passed["result"].asText(), passed.toString())
            assertEquals(0, passed["refusals"].size())
            assertTrue(passed["unknown"].isNull)
            val inputs = passed["inputs"]
            assertEquals(listOf("rack_present", "guard_closed", "lot_code"), inputs["signals"].map { it["name"].asText() })
            assertEquals("RACK-204", inputs["signals"][0]["location"].asText())
            assertEquals(true, inputs["signals"][1]["safety"].asBoolean())
            assertEquals(listOf("inspect", "navigate_to", "pick_place"), inputs["siteSkills"].map { it.asText() })
            assertEquals(robots, inputs["robotIds"].map { it.asText() })

            val safety = bench.validate(bench.draft(bench.arrivalWait().replace("\"signal\": \"rack_present\"", "\"signal\": \"guard_closed\"")))
            assertEquals("REFUSED", safety["result"].asText(), safety.toString())
            assertEquals(listOf("SAFETY_SIGNAL_WAIT"), safety["refusals"].map { it["kind"].asText() })

            val text = bench.validate(bench.draft(bench.arrivalWait().replace("\"signal\": \"rack_present\", \"expect\": \"true\"", "\"signal\": \"lot_code\", \"expect\": \"LOT-0002\"")))
            assertEquals("PASSED", text["result"].asText(), text.toString())
        }
    }

    @Test
    fun `신호 사양이나 기체 케이퍼빌리티를 모르면 거부가 아니라 INPUT_UNKNOWN 이고 아는 기체에 스킬이 없으면 거부다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val draftId = bench.draft(bench.dataV1())

            // mimic 이 모르는 기체 하나. 모름을 없음으로 접으면 SKILL_NOT_ON_SITE 가 난다.
            val ghost = bench.validate(draftId, listOf(QUADRUPED, GHOST))
            assertEquals("INPUT_UNKNOWN", ghost["result"].asText(), ghost.toString())
            assertEquals(listOf("SITE_SKILLS"), ghost["unknown"]["inputs"].map { it.asText() })
            assertEquals(listOf(GHOST), ghost["unknown"]["robots"].map { it.asText() })
            assertEquals(0, ghost["refusals"].size())
            assertTrue(ghost["inputs"]["siteSkills"].isNull)
            assertEquals("INPUT_UNKNOWN", bench.activate(draftId, listOf(HUMANOID, GHOST)).body!!["result"].asText())
            val unknownMock = bench.mockRun(draftId, listOf(HUMANOID, GHOST))
            assertEquals("INPUT_UNKNOWN", unknownMock["result"].asText())
            assertTrue(unknownMock["mockRun"].isNull)

            // 아는 기체에 pick_place 가 없으면 그것은 모름이 아니라 거부다.
            val quadruped = bench.validate(draftId, listOf(QUADRUPED))
            assertEquals("REFUSED", quadruped["result"].asText(), quadruped.toString())
            assertEquals(listOf("SKILL_NOT_ON_SITE"), quadruped["refusals"].map { it["kind"].asText() })

            // 셀 대역 본문에 신호 목록이 없다. 빈 사양이 아니라 모르는 사양이다.
            bench.cellBody = HostBench.STANDARD_CELL.substringBefore(",\n \"signals\"") + "}"
            // 지금 대기 중인 pump 가 앞선 본문을 들고 끝날 수 있어 두 번을 지나 본다.
            bench.mimic.server.advance(java.time.Duration.ofSeconds(1))
            bench.awaitPump()
            bench.mimic.server.advance(java.time.Duration.ofSeconds(1))
            bench.awaitPump()
            assertTrue(bench.get("/host/cell")["cell"]["signals"].isNull)
            val noSignals = bench.validate(draftId)
            assertEquals("INPUT_UNKNOWN", noSignals["result"].asText(), noSignals.toString())
            assertEquals(listOf("SIGNAL_SPEC"), noSignals["unknown"]["inputs"].map { it.asText() })

            // 셀 대역이 안 닿는다(스냅숏 없음).
            bench.cellBody = HostBench.STANDARD_CELL
            bench.stopCell()
            bench.mimic.server.advance(java.time.Duration.ofSeconds(1))
            bench.awaitPump()
            bench.mimic.server.advance(java.time.Duration.ofSeconds(1))
            bench.awaitPump()
            assertTrue(bench.get("/host/cell")["cell"].isNull)
            val silent = bench.validate(draftId)
            assertEquals("INPUT_UNKNOWN", silent["result"].asText(), silent.toString())
            assertEquals(listOf("SIGNAL_SPEC"), silent["unknown"]["inputs"].map { it.asText() })
            assertEquals("INPUT_UNKNOWN", bench.activate(draftId).body!!["result"].asText())
        }
    }

    @Test
    fun `통과한 모의 실행 없이 활성화하면 MOCK_RUN_REQUIRED 이고 그 초안의 마지막 모의 실행만 본다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val draftId = bench.draft(bench.dataV1())
            val none = bench.activate(draftId).body!!
            assertEquals("MOCK_RUN_REQUIRED", none["result"].asText(), none.toString())
            assertTrue(none["version"].isNull)
            assertTrue(none["lastMockRun"].isNull)

            // 다른 초안의 통과한 모의 실행은 이 초안의 게이트를 열지 않는다.
            val otherDraft = bench.draft(bench.dataV1())
            assertEquals("PASSED", bench.mockRun(otherDraft)["result"].asText())
            assertEquals("MOCK_RUN_REQUIRED", bench.activate(draftId).body!!["result"].asText())
            assertTrue(bench.activeVersion().isNull)

            val mock = bench.mockRun(draftId)
            assertEquals("PASSED", mock["result"].asText(), mock.toString())
            val activation = bench.activate(draftId).body!!
            assertEquals("ACTIVATED", activation["result"].asText(), activation.toString())
            assertEquals(1, activation["version"].asInt())
            assertEquals(mock["mockRun"]["mockRunId"].asLong(), activation["lastMockRun"]["mockRunId"].asLong())
            assertEquals(1, bench.activeVersion().asInt())
        }
    }

    @Test
    fun `마지막 모의 실행이 실패면 앞서 통과한 모의 실행이 있어도 MOCK_RUN_REQUIRED 다`() {
        HostBench(mockVirtualLimit = java.time.Duration.ofSeconds(30)).use { bench ->
            bench.awaitPump()
            val draftId = bench.draft(bench.dataV1())
            // 가상 시간 상한 30초 안에 pick_place(45초)가 끝나지 않는다.
            val failed = bench.mockRun(draftId)
            assertEquals("FAILED", failed["result"].asText(), failed.toString())
            val result = failed["mockRun"]["result"]
            assertEquals(false, failed["mockRun"]["passed"].asBoolean())
            assertEquals("NOT_SETTLED", result["failure"].asText(), result.toString())
            val activation = bench.activate(draftId).body!!
            assertEquals("MOCK_RUN_REQUIRED", activation["result"].asText())
            assertEquals(false, activation["lastMockRun"]["passed"].asBoolean())
            assertEquals(failed["mockRun"]["mockRunId"].asLong(), bench.get("/host/missions/$PSR")["drafts"].single()["lastMockRun"]["mockRunId"].asLong())
        }
    }

    @Test
    fun `모의 실행은 결과를 그 초안에 붙여 남기고 단위별 상태와 근거 등급을 낸다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val draftId = bench.draft(bench.arrivalWait())
            val requestId = UUID.randomUUID()
            val mock = bench.mockRun(draftId, requestId = requestId)
            assertEquals("PASSED", mock["result"].asText(), mock.toString())
            val row = mock["mockRun"]
            assertEquals(draftId, row["draftId"].asLong())
            assertEquals(true, row["passed"].asBoolean())
            assertEquals(requestId.toString(), row["requestId"].asText())
            assertFalse(java.time.Instant.parse(row["finishedAt"].asText()).isBefore(java.time.Instant.parse(row["startedAt"].asText())))
            val result = row["result"]
            assertTrue(result["failure"].isNull)
            assertEquals("PHYSICALLY_DONE", result["physicalState"].asText())
            assertEquals(listOf("rack-arrival", "RACK-204.S01", "RACK-204.S02"), result["units"].map { it["unitId"].asText() })
            assertTrue(result["units"].all { it["state"].asText() == "DONE" && it["reached"].asText() == "E2" }, result.toString())
            assertEquals("SIGNAL", result["units"][0]["route"].asText())
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02"), result["sample"]["slots"].map { it.asText() })
            assertEquals("SEQ-IN-02.BIN-A", result["sample"]["presentation"].asText())
            assertTrue(result["virtualElapsedSeconds"].asLong() > 0)

            val draft = bench.get("/host/missions/$PSR")["drafts"].single()
            assertEquals(row["mockRunId"].asLong(), draft["lastMockRun"]["mockRunId"].asLong())
            // 현장 기체는 건드리지 않는다.
            assertEquals(0, bench.get("/host/executions")["executions"].size())
            assertEquals(0, bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.size })
        }
    }

    @Test
    fun `버전 1 을 활성화하면 새 제출부터 버전 1 이고 도는 실행은 코드 정의로 끝난다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val running = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", running["result"].asText(), running.toString())
            val first = running["executionId"].asText()

            assertEquals(1, bench.activated(bench.dataV1()))
            val overview = bench.get("/host/missions/$PSR")
            assertEquals(1, overview["active"]["version"].asInt())
            assertEquals("DATA", overview["active"]["source"].asText())
            assertEquals("lee", overview["active"]["detail"]["activatedBy"].asText())
            assertEquals("랙 도착 대기 도입", overview["active"]["detail"]["reason"].asText())
            assertEquals(listOf(1), overview["versions"].map { it["version"].asInt() })

            // 도는 실행은 쥔 정의(코드)로 끝난다.
            assertTrue(bench.execution(first)!!["missionVersion"].isNull)
            bench.driveUntil(first, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertTrue(bench.execution(first)!!["missionVersion"].isNull)

            val next = bench.post("/host/job-orders", request(rack("JO-2", "RACK-204.S03"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", next["result"].asText(), next.toString())
            assertEquals(1, bench.execution(next["executionId"].asText())!!["missionVersion"].asInt())
        }
    }

    /**
     * 셀 대역 대역의 본문. 슬롯 넷은 처음부터 기대 자재로 점유(관측 시각 없음, 읽은 순간)라 E2 를 얻는다. 대역은 슬롯을
     * 채우지 않으므로 이렇게 둔다. [rackPresent] 가 랙 도착 신호의 값이다.
     */
    private fun filledCell(rackPresent: String): String = HostBench.STANDARD_CELL
        .replace("\"occupied\":false,\"material\":null", "\"occupied\":true,\"material\":\"${HostBench.MATERIAL}\"")
        .replace("\"name\":\"rack_present\",\"location\":\"RACK-204\",\"kind\":\"BOOLEAN\",\"safety\":false,\"value\":\"false\"",
            "\"name\":\"rack_present\",\"location\":\"RACK-204\",\"kind\":\"BOOLEAN\",\"safety\":false,\"value\":\"$rackPresent\"")

    private fun HostBench.nextPump() {
        mimic.server.advance(java.time.Duration.ofSeconds(1))
        awaitPump()
        mimic.server.advance(java.time.Duration.ofSeconds(1))
        awaitPump()
    }

    @Test
    fun `버전 1 로 도는 실행 중 버전 2 를 활성화하면 옛 실행은 대기 없이 끝나고 새 작업 지시는 rack_present 를 기다렸다 진행한다`() {
        HostBench().use { bench ->
            bench.cellBody = filledCell(rackPresent = "false")
            bench.nextPump()
            assertEquals(1, bench.activated(bench.dataV1()))
            val first = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01", "RACK-204.S02"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", first["result"].asText(), first.toString())
            val oldRun = first["executionId"].asText()
            assertEquals(1, bench.execution(oldRun)!!["missionVersion"].asInt())

            assertEquals(2, bench.activated(bench.arrivalWait()))
            val settled = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")
            val old = bench.driveUntil(oldRun, settled)
            assertEquals("PHYSICALLY_DONE", old["physicalState"].asText(), old.toString())
            assertEquals(1, old["missionVersion"].asInt())
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02"), old["units"].map { it["unitId"].asText() })

            val second = bench.post("/host/job-orders", request(rack("JO-2", "RACK-204.S03", "RACK-204.S04"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", second["result"].asText(), second.toString())
            val newRun = second["executionId"].asText()
            // 신호를 켜기 전에는 기한(120초) 안에서만 민다. 대기 단위가 풀리지 않고 기체도 움직이지 않는다.
            repeat(2) {
                bench.mimic.server.advance(java.time.Duration.ofSeconds(5))
                bench.awaitPump()
            }
            val waiting = bench.execution(newRun)!!
            assertEquals(2, waiting["missionVersion"].asInt())
            assertEquals("rack-arrival", waiting["units"][0]["unitId"].asText())
            assertTrue(waiting["units"][0]["state"].asText() != "DONE", waiting.toString())
            assertEquals(2, bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.size })

            bench.cellBody = filledCell(rackPresent = "true")
            val done = bench.driveUntil(newRun, settled)
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            assertEquals(listOf("rack-arrival", "RACK-204.S03", "RACK-204.S04"), done["jobResponse"]["completedUnits"].map { it.asText() })
            assertEquals("E2", done["units"][0]["reached"].asText())

            // 시드 0 에서 humanoid-01 의 pick_place 넷이 실패 모드 없이 끝난다(S3b 스펙 §3 의 통합 시나리오가 기대는 사실).
            val tasks = bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.map { it.skillType to it.machine.state.name } }
            assertEquals(List(4) { "pick_place" to "SUCCEEDED" }, tasks)
        }
    }

    @Test
    fun `버전 2 의 대기가 기한을 넘기면 실행은 ABORTED 이고 작업 응답에 SIGNAL_DEADLINE 이 남으며 기체는 다시 배정할 수 있다`() {
        HostBench().use { bench ->
            bench.cellBody = filledCell(rackPresent = "false")
            bench.nextPump()
            assertEquals(1, bench.activated(bench.arrivalWait()))
            val submitted = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01"), "candidates", HUMANOID)).body!!
            val executionId = submitted["executionId"].asText()
            val aborted = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertEquals("ABORTED", aborted["physicalState"].asText(), aborted.toString())
            assertEquals("SIGNAL_DEADLINE", aborted["jobResponse"]["incompleteUnits"]["rack-arrival"].asText(), aborted.toString())
            assertEquals(0, bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.size })

            val judged = bench.post("/host/eligibility", request(rack("JO-2", "RACK-204.S02"), "robotIds", HUMANOID)).body!!["robots"].single()
            assertEquals(true, judged["passed"].asBoolean(), judged.toString())
        }
    }

    @Test
    fun `기한을 넘긴 대기의 인시던트가 봉인 라운드의 설정 버전과 시간값을 싣고 최신부터 나온다`() {
        HostBench(timings = listOf(7, 40, 20, 90, 600)).use { bench ->
            assertTrue(bench.get("/host/incidents")["incidents"].isEmpty)
            bench.cellBody = filledCell(rackPresent = "false")
            bench.nextPump()
            assertEquals(1, bench.activated(bench.arrivalWait()))
            val first = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01"), "candidates", HUMANOID)).body!!["executionId"].asText()
            bench.driveUntil(first, setOf("ABORTED"))

            // 둘째 작업 지시가 대기 중일 때 버전 8 을 적용하면, 그 대기의 인시던트는 봉인 라운드의 버전 8 을 든다.
            val second = bench.post("/host/job-orders", request(rack("JO-2", "RACK-204.S02"), "candidates", HUMANOID)).body!!["executionId"].asText()
            bench.timingsView(listOf(8, 45, 25, 120, 900))
            bench.awaitApplied(8)
            bench.driveUntil(second, setOf("ABORTED"))

            // limit 을 생략하면 기본 50 개까지라 둘 다 나온다.
            val view = bench.get("/host/incidents")
            assertTrue(view["instanceId"].asText().startsWith("mw-"), view.toString())
            assertEquals(2, view["total"].asInt(), view.toString())
            assertEquals(2, view["incidents"].size(), view.toString())
            val (newest, oldest) = view["incidents"].toList()
            assertEquals(
                listOf(second, "JO-2", 8L, listOf(45L, 25L, 120L, 900L)),
                listOf(newest["executionId"].asText(), newest["jobOrderId"].asText(), newest["siteSettingsVersion"].asLong(), seconds(newest)),
            )
            assertEquals(
                listOf(first, "JO-1", 7L, listOf(40L, 20L, 90L, 600L)),
                listOf(oldest["executionId"].asText(), oldest["jobOrderId"].asText(), oldest["siteSettingsVersion"].asLong(), seconds(oldest)),
            )
            listOf(newest, oldest).forEach { incident ->
                assertEquals("SIGNAL_DEADLINE", incident["failureClass"].asText(), incident.toString())
                assertEquals("rack-arrival", incident["unitId"].asText())
                assertEquals(HUMANOID, incident["robotId"].asText())
                assertEquals("SIGNAL", incident["route"].asText())
                assertEquals(1, incident["missionVersion"].asInt())
                assertTrue(incident["incidentId"].asText().startsWith("incident-"), incident.toString())
                Instant.parse(incident["at"].asText())
            }

            assertEquals(listOf(second), bench.get("/host/incidents?limit=1")["incidents"].map { it["executionId"].asText() })
            assertEquals(2, bench.get("/host/incidents?limit=1")["total"].asInt())
            listOf("0", "501", "x").forEach { limit ->
                val bad = bench.fetch("/host/incidents?limit=$limit")
                assertEquals(400, bad.status, limit)
                assertEquals("BAD_REQUEST", bad.body!!["error"].asText())
            }
        }
    }

    /** 인시던트의 시간값 넷(앞 폭, 뒤 폭, inDoubtGrace, stallWindow). */
    private fun seconds(incident: JsonNode): List<Long> =
        listOf("evidenceBeforeSeconds", "evidenceAfterSeconds", "inDoubtGraceSeconds", "stallWindowSeconds").map { incident[it].asLong() }

    @Test
    fun `재기동해도 활성 버전 번호가 같고 다음 활성화는 가장 큰 번호 더하기 1 이다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            assertEquals(1, bench.activated(bench.dataV1()))
            assertEquals(2, bench.activated(bench.arrivalWait()))

            bench.restartHost()
            bench.awaitPump()
            assertEquals(2, bench.activeVersion().asInt())
            val submitted = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
            val execution = bench.execution(submitted["executionId"].asText())!!
            assertEquals(2, execution["missionVersion"].asInt())
            assertEquals("rack-arrival", execution["units"][0]["unitId"].asText())

            // 재기동 뒤에도 번호는 DB 의 최대 + 1 이다. 메모리에서 1부터 세지 않는다.
            assertEquals(3, bench.activated(bench.dataV1()))
            assertEquals(listOf(3, 2, 1), bench.get("/host/missions/$PSR")["versions"].map { it["version"].asInt() })
        }
    }

    @Test
    fun `저장된 정의를 파싱하지 못하면 호스트 기동이 멈추고 이유를 남긴다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            assertEquals(1, bench.activated(bench.dataV1()))
            // 트리거는 INSERT 를 막지 않는다. 읽을 수 없는 정의를 버전 2 로 직접 넣는다.
            Fenced.execute(
                "INSERT INTO mission.mission_version (work_master_id, version, draft_id, definition, activated_by, reason, request_id) " +
                    "SELECT 'PrepareSequencedRack', 2, draft_id, '{\"schemaVersion\": 1}', 'x', 'x', '${UUID.randomUUID()}' FROM mission.draft LIMIT 1",
            )
            val failure = assertFailsWith<Exception> { bench.restartHost() }
            val message = generateSequence<Throwable>(failure) { it.cause }.joinToString(" / ") { it.message.orEmpty() }
            assertTrue("PrepareSequencedRack 버전 2" in message && "읽지 못했다" in message, message)
        }
    }

    @Test
    fun `요청 id 로 초안 모의 실행 버전을 다시 찾고 없으면 404 이며 이미 쓴 요청 id 는 409 다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val draftRequest = UUID.randomUUID()
            val draftId = bench.saveDraft(bench.dataV1(), draftRequest).body!!["draft"]["draftId"].asLong()
            val mockRequest = UUID.randomUUID()
            val mockRunId = bench.mockRun(draftId, requestId = mockRequest)["mockRun"]["mockRunId"].asLong()
            val versionRequest = UUID.randomUUID()
            assertEquals("ACTIVATED", bench.activate(draftId, requestId = versionRequest).body!!["result"].asText())

            val draft = bench.get("/host/missions/requests/$draftRequest")
            assertEquals(draftId, draft["draft"]["draftId"].asLong())
            assertTrue(draft["mockRun"].isNull && draft["version"].isNull, draft.toString())
            val mock = bench.get("/host/missions/requests/$mockRequest")
            assertEquals(mockRunId, mock["mockRun"]["mockRunId"].asLong())
            assertTrue(mock["draft"].isNull)
            val version = bench.get("/host/missions/requests/$versionRequest")
            assertEquals(1, version["version"]["version"].asInt())
            assertEquals(PSR, version["version"]["workMasterId"].asText())

            val missing = bench.fetch("/host/missions/requests/${UUID.randomUUID()}")
            assertEquals(404, missing.status)
            assertEquals("REQUEST_NOT_FOUND", missing.body!!["error"].asText())
            assertEquals(400, bench.fetch("/host/missions/requests/not-a-uuid").status)

            // 요청 id 하나는 조작 하나다. 다른 표의 행이라도 다시 쓰면 409 이고 아무것도 남지 않는다.
            listOf(draftRequest, mockRequest, versionRequest).forEach { used ->
                assertEquals(409, bench.saveDraft(bench.dataV1(), used).status)
                val reused = bench.activate(draftId, requestId = used)
                assertEquals(409, reused.status)
                assertEquals("REQUEST_ID_REUSED", reused.body!!["error"].asText())
            }
            assertEquals(1, bench.get("/host/missions/$PSR")["drafts"].size())
            assertEquals(listOf(1), bench.get("/host/missions/$PSR")["versions"].map { it["version"].asInt() })
        }
    }

    @Test
    fun `활성화는 호스트 잠금을 쥔 동안 기다리고 놓으면 선다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val draftId = bench.draft(bench.dataV1())
            assertEquals("PASSED", bench.mockRun(draftId)["result"].asText())

            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread { bench.host.exclusive { held.countDown(); release.await() } }.apply { start() }
            held.await()
            val activation = CompletableFuture.supplyAsync { bench.activate(draftId) }
            try {
                Thread.sleep(500)
                assertFalse(activation.isDone, "호스트 잠금을 쥔 동안 활성화가 끝났다: ${if (activation.isDone) activation.get() else ""}")
                assertTrue(bench.activeVersion().isNull)
            } finally {
                release.countDown()
                holder.join()
            }
            val done = activation.get(30, TimeUnit.SECONDS)
            assertEquals("ACTIVATED", done.body!!["result"].asText(), done.body.toString())
            assertEquals(1, bench.activeVersion().asInt())
        }
    }

    @Test
    fun `활성화가 호스트 잠금을 기다리는 동안 그 요청 id 를 재조회하면 409 REQUEST_IN_PROGRESS 이고 끝나면 버전 행이다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val draftId = bench.draft(bench.dataV1())
            assertEquals("PASSED", bench.mockRun(draftId)["result"].asText())

            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread { bench.host.exclusive { held.countDown(); release.await() } }.apply { start() }
            held.await()
            val requestId = UUID.randomUUID()
            val activation = CompletableFuture.supplyAsync { bench.activate(draftId, requestId = requestId) }
            try {
                // 활성화 요청이 호스트에 닿기 전에는 404 다. 닿은 뒤로는 잠금을 기다리는 동안 내내 처리 중이어야 한다.
                val deadline = Instant.now().plusSeconds(10)
                var requery = bench.fetch("/host/missions/requests/$requestId")
                while (requery.status == 404 && Instant.now().isBefore(deadline)) {
                    Thread.sleep(50)
                    requery = bench.fetch("/host/missions/requests/$requestId")
                }
                assertEquals(409, requery.status, requery.body.toString())
                assertEquals("REQUEST_IN_PROGRESS", requery.body!!["error"].asText())
                assertFalse(activation.isDone, "호스트 잠금을 쥔 동안 활성화가 끝났다")
                // 처리 중인 요청 id 를 다른 조작이 쓰면 재사용이다. 행이 아직 없어도 남기지 않는다.
                val reused = bench.saveDraft(bench.dataV1(), requestId)
                assertEquals(409, reused.status)
                assertEquals("REQUEST_ID_REUSED", reused.body!!["error"].asText())
            } finally {
                release.countDown()
                holder.join()
            }
            val done = activation.get(30, TimeUnit.SECONDS)
            assertEquals("ACTIVATED", done.body!!["result"].asText(), done.body.toString())
            assertEquals(1, bench.get("/host/missions/requests/$requestId")["version"]["version"].asInt())
            assertEquals(1, bench.get("/host/missions/$PSR")["drafts"].size())
        }
    }

    @Test
    fun `요청 본문이 틀리면 400 이고 없는 초안은 404 이며 JSON 이 아니면 415 다`() {
        HostBench().use { bench ->
            val draftId = bench.draft(bench.dataV1())
            val bad = mapOf(
                "/host/missions/$PSR/drafts" to listOf(
                    """{"actor":"lee","requestId":"${UUID.randomUUID()}"}""",
                    """{"definition":{},"actor":"lee","requestId":"${UUID.randomUUID()}"}""",
                    """{"definition":"x","actor":" ","requestId":"${UUID.randomUUID()}"}""",
                    """{"definition":"x","actor":"lee","requestId":"nope"}""",
                    "{not json",
                ),
                "/host/missions/drafts/$draftId/validate" to listOf("""{}""", """{"robotIds":[""]}""", """{"robotIds":"humanoid-01"}"""),
                "/host/missions/drafts/$draftId/mock-run" to listOf("""{"robotIds":[]}""", """{"requestId":"${UUID.randomUUID()}"}"""),
                "/host/missions/drafts/$draftId/activate" to listOf(
                    """{"actor":"lee","reason":"","robotIds":[],"requestId":"${UUID.randomUUID()}"}""",
                    """{"actor":"lee","robotIds":[],"requestId":"${UUID.randomUUID()}"}""",
                    """{"reason":"r","robotIds":[],"requestId":"${UUID.randomUUID()}"}""",
                ),
            )
            bad.forEach { (path, bodies) ->
                bodies.forEach { body ->
                    val reply = bench.post(path, body)
                    assertEquals(400, reply.status, "$path $body ${reply.body}")
                    assertEquals("BAD_REQUEST", reply.body!!["error"].asText(), "$path $body")
                }
                assertEquals(415, bench.post(path, bodies.first(), contentType = "text/plain").status, path)
            }
            assertEquals(400, bench.post("/host/missions/drafts/x/validate", """{"robotIds":[]}""").status)
            val missing = bench.post("/host/missions/drafts/999999/validate", """{"robotIds":[]}""")
            assertEquals(404, missing.status)
            assertEquals("DRAFT_NOT_FOUND", missing.body!!["error"].asText())
            assertEquals(1, bench.get("/host/missions/$PSR")["drafts"].size())
        }
    }

    @Test
    fun `신호 조작은 현장에 그대로 넘기고 현장의 응답을 그대로 돌려주며 현장이 안 닿으면 503 이다`() {
        HostBench().use { bench ->
            val ok = bench.post("/host/cell/signals/rack_present", """{"value":"true"}""")
            assertEquals(200, ok.status)
            assertEquals("true", ok.body!!["value"].asText())
            assertEquals(Triple("rack_present", "application/json", """{"value":"true"}"""), bench.signalWrites.single())

            listOf(
                403 to """{"error":"SAFETY_SIGNAL_READ_ONLY","detail":"x"}""",
                404 to """{"error":"UNKNOWN_SIGNAL","detail":"x"}""",
                400 to """{"error":"SIGNAL_VALUE_INVALID","detail":"x"}""",
            ).forEach { (status, body) ->
                bench.signalReply = status to body
                val relayed = bench.post("/host/cell/signals/guard_closed", """{"value":"false"}""")
                assertEquals(status, relayed.status)
                assertEquals(JSON.readTree(body), relayed.body)
            }
            assertEquals(415, bench.post("/host/cell/signals/rack_present", """{"value":"true"}""", contentType = "text/plain").status)

            bench.stopCell()
            val silent = bench.post("/host/cell/signals/rack_present", """{"value":"true"}""")
            assertEquals(503, silent.status)
            assertEquals("CELL_SILENT", silent.body!!["error"].asText())
        }
    }

    @Test
    fun `GET host cell 에 신호가 실리고 미들웨어의 셀 신호는 그 값을 낸다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val signals = bench.get("/host/cell")["cell"]["signals"]
            assertEquals(listOf("rack_present", "guard_closed", "lot_code"), signals.map { it["name"].asText() })
            assertEquals(listOf("false", "true", "LOT-0001"), signals.map { it["value"].asText() })
            assertTrue(signals.all { it["value"].isTextual })
            assertEquals("BOOLEAN", signals[0]["kind"].asText())
            assertEquals(listOf("presentations", "slots", "signals", "handovers"), bench.get("/host/cell")["cell"].fieldNames().asSequence().toList())
            assertEquals(listOf("name", "location", "kind", "safety", "value", "observedAt"), signals[0].fieldNames().asSequence().toList())
            assertNotNull(bench.host.cell()?.signals)
        }
    }

    private companion object {
        const val PSR = "PrepareSequencedRack"
    }
}
