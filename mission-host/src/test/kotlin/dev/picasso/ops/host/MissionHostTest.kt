package dev.picasso.ops.host

import dev.picasso.middleware.DeliverContainer
import dev.picasso.ops.host.HostBench.Companion.GHOST
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.web.HostRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실행 호스트의 판정·제출·실행 목록(S3a 스펙 §7.6, §11). mimic 은 실제 Netty 포트에 뜨고 호스트는 그 포트에 gRPC 로 붙는다.
 */
class MissionHostTest {

    @Test
    fun `스킬이 모자란 기체와 케이퍼빌리티를 못 물은 기체를 가르고 이유를 낸다`() {
        HostBench().use { bench ->
            val rack = bench.post("/host/eligibility", request(rack("JO-1", "RACK-204.S01"), "robotIds", HUMANOID, QUADRUPED, GHOST))
            assertEquals(200, rack.status, rack.body.toString())
            val rows = rack.body!!["robots"].associateBy { it["robotId"].asText() }
            assertEquals(listOf(HUMANOID, QUADRUPED, GHOST), rack.body["robots"].map { it["robotId"].asText() })

            val humanoid = rows.getValue(HUMANOID)
            assertEquals("FIT", humanoid["skillFit"].asText())
            assertEquals(0, humanoid["missingSkills"].size())
            assertTrue(humanoid["runningExecutionId"].isNull)
            assertEquals(true, humanoid["passed"].asBoolean())
            assertEquals(0, humanoid["reasons"].size())

            // quadruped-01 은 pick_place 를 선언하지 않는다.
            val quadruped = rows.getValue(QUADRUPED)
            assertEquals("MISSING", quadruped["skillFit"].asText())
            assertEquals(listOf("pick_place"), quadruped["missingSkills"].map { it.asText() })
            assertEquals(false, quadruped["passed"].asBoolean())
            assertEquals(listOf("모자란 스킬: pick_place"), quadruped["reasons"].map { it.asText() })

            // mimic 이 모르는 기체는 케이퍼빌리티를 못 물어본다. 모자람이 아니라 모름이다.
            val ghost = rows.getValue(GHOST)
            assertEquals("UNKNOWN", ghost["skillFit"].asText())
            assertEquals(0, ghost["missingSkills"].size())
            assertEquals(false, ghost["passed"].asBoolean())

            // InspectAsset 은 둘 다 든다.
            val inspection = bench.post("/host/eligibility", request(inspect("JO-2", "T1" to "bay-7"), "robotIds", HUMANOID, QUADRUPED))
            assertEquals(listOf("FIT", "FIT"), inspection.body!!["robots"].map { it["skillFit"].asText() })
        }
    }

    @Test
    fun `제출은 판정을 다시 해 통과한 기체만 assign 에 넘긴다`() {
        HostBench().use { bench ->
            // 통과한 기체가 없으면 assign 의 사유는 비고 뺀 기체가 이유와 함께 나온다.
            val none = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01"), "candidates", QUADRUPED))
            assertEquals(200, none.status, none.body.toString())
            assertEquals("UNASSIGNED", none.body!!["result"].asText())
            assertTrue(none.body["executionId"].isNull)
            assertEquals(0, none.body["refusals"].size())
            assertEquals(listOf(QUADRUPED), none.body["excluded"].map { it["robotId"].asText() })
            assertEquals(listOf("pick_place"), none.body["excluded"][0]["missingSkills"].map { it.asText() })

            // 후보 순서가 quadruped-01 먼저여도 배정은 통과한 humanoid-01 이다.
            val accepted = bench.post(
                "/host/job-orders",
                request(rack("JO-2", "RACK-204.S01", "RACK-204.S02"), "candidates", QUADRUPED, HUMANOID),
            )
            assertEquals("ACCEPTED", accepted.body!!["result"].asText(), accepted.body.toString())
            assertEquals(HUMANOID, accepted.body["robotId"].asText())
            assertEquals("exec-1", accepted.body["executionId"].asText())
            assertTrue(accepted.body["rejectionReason"].isNull)
            assertEquals(listOf(QUADRUPED), accepted.body["excluded"].map { it["robotId"].asText() })

            val execution = bench.execution("exec-1")!!
            assertEquals("JO-2", execution["jobOrderId"].asText())
            assertEquals("PrepareSequencedRack", execution["workMasterId"].asText())
            assertEquals(HUMANOID, execution["robotId"].asText())
            // 코드 정의 임무는 버전이 없다. 칸은 있고 값이 null 이다.
            assertTrue(execution.has("missionVersion") && execution["missionVersion"].isNull, execution.toString())
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02"), execution["units"].map { it["unitId"].asText() })
            assertTrue(execution["units"].all { it["skillType"].asText() == "pick_place" && it.has("state") && it.has("reached") })
        }
    }

    @Test
    fun `도는 실행이 있는 기체는 판정과 제출에서 빠진다`() {
        HostBench().use { bench ->
            val first = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID))
            assertEquals("ACCEPTED", first.body!!["result"].asText(), first.body.toString())
            val executionId = first.body["executionId"].asText()

            val judged = bench.post("/host/eligibility", request(inspect("JO-2", "T2" to "dock-3"), "robotIds", HUMANOID))
                .body!!["robots"].single()
            assertEquals("FIT", judged["skillFit"].asText())
            assertEquals(executionId, judged["runningExecutionId"].asText())
            assertEquals(false, judged["passed"].asBoolean())
            assertEquals(listOf("진행 중인 실행이 있다: $executionId"), judged["reasons"].map { it.asText() })

            val second = bench.post("/host/job-orders", request(inspect("JO-2", "T2" to "dock-3"), "candidates", HUMANOID))
            assertEquals("UNASSIGNED", second.body!!["result"].asText(), second.body.toString())
            assertEquals(executionId, second.body["excluded"].single()["runningExecutionId"].asText())
            assertEquals(1, bench.get("/host/executions")["executions"].size())
        }
    }

    /**
     * 부분 완료(`PARTIAL`)는 리비전으로 남은 단위가 다시 돌 수 있어 그 기체를 쥔다(picasso `PhysicalState.occupiesRobot`). 둘째 슬롯의
     * 자재(B형)에 출발 자리가 없어 그 단위는 계획 때 실패하고, 첫 슬롯만 끝나 실행이 부분 완료가 된다. 근거 등급 E0 이라 셀 확인 없이 끝난다.
     * 정착한 실행(`ABORTED`)의 기체가 다시 배정되는 것은 MissionVersionsTest 의 대기 기한 시험이 본다.
     */
    @Test
    fun `부분 완료 실행의 기체는 도는 실행으로 판정과 제출에서 빠진다`() {
        HostBench().use { bench ->
            val partial = """
                {"jobOrderId":"JO-1","workMasterId":"PrepareSequencedRack","version":1,"requiredEvidence":"E0","parameters":{},
                 "materialRequirements":[{"materialDefinitionId":"${HostBench.MATERIAL}","quantity":1},{"materialDefinitionId":"ENGINE-COVER-B","quantity":1}],
                 "equipmentRequirements":[{"id":"RACK-204.S01","equipmentUse":"destination","properties":{"material":"${HostBench.MATERIAL}"}},
                  {"id":"RACK-204.S02","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-B"}},
                  {"id":"${HostBench.SOURCE}","equipmentUse":"source","properties":{"material":"${HostBench.MATERIAL}"}}]}
            """.trimIndent()
            val first = bench.post("/host/job-orders", request(partial, "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", first["result"].asText(), first.toString())
            val executionId = first["executionId"].asText()
            val settled = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertEquals("PARTIAL", settled["physicalState"].asText(), settled.toString())

            val judged = bench.post("/host/eligibility", request(rack("JO-2", "RACK-204.S03"), "robotIds", HUMANOID)).body!!["robots"].single()
            assertEquals(executionId, judged["runningExecutionId"].asText(), judged.toString())
            assertEquals(false, judged["passed"].asBoolean())
            assertEquals(listOf("진행 중인 실행이 있다: $executionId"), judged["reasons"].map { it.asText() })

            val second = bench.post("/host/job-orders", request(rack("JO-2", "RACK-204.S03"), "candidates", HUMANOID)).body!!
            assertEquals("UNASSIGNED", second["result"].asText(), second.toString())
            assertEquals(executionId, second["excluded"].single()["runningExecutionId"].asText())
            assertEquals(1, bench.get("/host/executions")["executions"].size())
        }
    }

    @Test
    fun `끝난 InspectAsset 를 같은 id 로 다시 내면 놓은 작업 지시로 REJECTED 이고 실행에 마지막 작업 응답이 붙는다`() {
        HostBench().use { bench ->
            val order = request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)
            val accepted = bench.post("/host/job-orders", order).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val executionId = accepted["executionId"].asText()

            val done = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            assertEquals(listOf("T1.travel" to "DONE", "T1" to "DONE"), done["units"].map { it["unitId"].asText() to it["state"].asText() })
            assertTrue(done["units"].all { it["reached"].asText() == "E0" })

            val response = done["jobResponse"]
            assertEquals("PHYSICALLY_DONE", response["physicalState"].asText(), response.toString())
            assertEquals("E0", response["reachedEvidence"].asText())
            assertEquals(listOf("T1.travel", "T1"), response["completedUnits"].map { it.asText() })

            // 정착을 적은 뒤 picasso 메모리에서 놓았다(Middleware.forget). 같은 작업 지시 id 는 판과 무관하게 받지 않는다.
            val again = bench.post("/host/job-orders", order).body!!
            assertEquals("REJECTED", again["result"].asText(), again.toString())
            assertTrue(again["forgottenJobOrder"].asBoolean(), again.toString())
            assertTrue(again["rejectionReason"].asText().startsWith(dev.picasso.middleware.Middleware.FORGOTTEN_JOB_ORDER), again.toString())
            assertTrue(again["executionId"].isNull && again["robotId"].isNull, again.toString())
            assertEquals(1, bench.get("/host/executions")["executions"].size())
        }
    }

    @Test
    fun `요구 근거 등급이 임무의 최고 등급을 넘으면 REJECTED 와 사유를 낸다`() {
        HostBench().use { bench ->
            val rejected = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7", evidence = "E2"), "candidates", HUMANOID))
            assertEquals(200, rejected.status)
            assertEquals("REJECTED", rejected.body!!["result"].asText(), rejected.body.toString())
            assertTrue(rejected.body["executionId"].isNull)
            assertTrue(rejected.body["robotId"].isNull)
            assertTrue("E2" in rejected.body["rejectionReason"].asText(), rejected.body.toString())
            // 근거 등급 거부는 조치 탐색도 출발 결품도 아니다. 칸은 늘 있고 값이 비었다.
            assertTrue(rejected.body["remedy"].isNull, rejected.body.toString())
            assertEquals(false, rejected.body["remedyWithheld"].asBoolean())
            assertTrue(rejected.body.has("alternativeLocations") && rejected.body["alternativeLocations"].isNull, rejected.body.toString())
            assertTrue(rejected.body.has("sourceMissing") && rejected.body["sourceMissing"].isNull, rejected.body.toString())
            assertEquals(0, bench.get("/host/executions")["executions"].size())
        }
    }

    /**
     * 실행 호스트는 `assign` 으로 배정하므로 출발 결품은 REJECTED 가 아니라 UNASSIGNED 다. picasso `Unassigned.sourceMissing` 이 기체마다
     * 출발 결품의 답을 실어 주므로 대체 자리와 출발 자리는 사유 문장이 아니라 그 기체의 미배정 사유 칸에 구조로 온다. 맨 위의
     * `alternativeLocations`·`sourceMissing` 은 REJECTED 의 칸이라 여기서는 칸만 있고 `null` 이다.
     */
    @Test
    fun `출발 자리가 비면 UNASSIGNED 이고 기체별 미배정 사유에 대체 자리와 출발 자리가 구조로 실린다`() {
        HostBench().use { bench ->
            bench.cellBody = HostBench.STANDARD_CELL.replace(
                """{"id":"${HostBench.SOURCE}","occupied":true,"material":"${HostBench.MATERIAL}","observedAt":null}""",
                """{"id":"${HostBench.SOURCE}","occupied":false,"material":null,"observedAt":null},""" +
                    """{"id":"SEQ-IN-03.BIN-B","occupied":true,"material":"${HostBench.MATERIAL}","observedAt":null}""",
            )
            bench.eventually("빈 출발 자리를 읽는다") {
                bench.get("/host/cell")["cell"]?.get("presentations")?.any { it["id"].asText() == HostBench.SOURCE && !it["occupied"].asBoolean() } == true
            }
            val outcome = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01"), "candidates", HUMANOID)).body!!
            assertEquals("UNASSIGNED", outcome["result"].asText(), outcome.toString())
            val refusal = outcome["refusals"].single()
            assertEquals(HUMANOID, refusal["robotId"].asText())
            assertTrue(refusal["reason"].asText().startsWith("출발 자리 ${HostBench.SOURCE} 에 ${HostBench.MATERIAL} 이 없다"), refusal.toString())
            assertEquals(listOf("SEQ-IN-03.BIN-B"), refusal["alternativeLocations"].map { it.asText() }, refusal.toString())
            val missing = refusal["sourceMissing"]
            assertEquals(HostBench.SOURCE, missing["source"].asText(), refusal.toString())
            assertEquals(HostBench.MATERIAL, missing["material"].asText())
            assertTrue(missing.has("observed") && missing["observed"].isNull, missing.toString())
            // 맨 위 두 칸은 REJECTED 의 것이다. 칸은 늘 있고 UNASSIGNED 에서는 값이 비었다.
            assertTrue(outcome.has("alternativeLocations") && outcome["alternativeLocations"].isNull, outcome.toString())
            assertTrue(outcome.has("sourceMissing") && outcome["sourceMissing"].isNull, outcome.toString())
            assertEquals(0, bench.get("/host/executions")["executions"].size())
        }
    }

    /**
     * 빈 목록은 «그 자재를 든 다른 자리가 없다» 는 셀의 답이다. 호스트의 셀 대역은 스냅숏이 있으면 늘 답하므로 HTTP 로는 빈 목록만
     * 나온다. 셀이 답하지 않은 `null` 이 빈 목록으로 접히지 않는 것은 SubmitOutcomeTest 가 picasso 값을 직접 세워 본다.
     */
    @Test
    fun `든 자리가 없으면 기체별 대체 자리는 null 이 아니라 빈 목록이다`() {
        HostBench().use { bench ->
            bench.cellBody = HostBench.STANDARD_CELL.replace(
                """{"id":"${HostBench.SOURCE}","occupied":true,"material":"${HostBench.MATERIAL}","observedAt":null}""",
                """{"id":"${HostBench.SOURCE}","occupied":false,"material":null,"observedAt":null}""",
            )
            bench.eventually("빈 출발 자리를 읽는다") {
                bench.get("/host/cell")["cell"]?.get("presentations")?.any { it["id"].asText() == HostBench.SOURCE && !it["occupied"].asBoolean() } == true
            }
            val outcome = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01"), "candidates", HUMANOID)).body!!
            assertEquals("UNASSIGNED", outcome["result"].asText(), outcome.toString())
            val refusal = outcome["refusals"].single()
            assertTrue(refusal["alternativeLocations"].let { it.isArray && it.isEmpty }, refusal.toString())
            assertEquals(HostBench.SOURCE, refusal["sourceMissing"]["source"].asText(), refusal.toString())
        }
    }

    /**
     * 상위 시스템이 대체 위치로 고쳐 보낸 새 작업 지시도 제출 때 셀을 다시 본다. 미리 받은 대체 위치는 그 순간의 답일 뿐이라, 그 자리가
     * 그사이 비었으면 새 작업 지시가 다시 출발 결품이 되고 대체 위치도 지금 셀로 다시 센다. 아직 있으면 지난다.
     */
    @Test
    fun `대체 위치로 고쳐 낸 작업 지시도 제출 때 셀을 다시 보아 그 자리가 비었으면 새 대체 위치와 함께 다시 출발 결품이다`() {
        val alternative = "SEQ-IN-03.BIN-B"
        fun place(id: String, held: Boolean) =
            """{"id":"$id","occupied":$held,"material":${if (held) "\"${HostBench.MATERIAL}\"" else "null"},"observedAt":null}"""
        fun cell(source: Boolean, other: Boolean) = HostBench.STANDARD_CELL.replace(
            """{"id":"${HostBench.SOURCE}","occupied":true,"material":"${HostBench.MATERIAL}","observedAt":null}""",
            place(HostBench.SOURCE, source) + "," + place(alternative, other),
        )
        fun from(jobOrderId: String, source: String) =
            rack(jobOrderId, "RACK-204.S01").replace("""{"id":"${HostBench.SOURCE}","equipmentUse":"source"""", """{"id":"$source","equipmentUse":"source"""")
        HostBench().use { bench ->
            fun read(source: Boolean, other: Boolean) {
                bench.cellBody = cell(source, other)
                bench.eventually("셀을 다시 읽는다") {
                    bench.get("/host/cell")["cell"]?.get("presentations")?.associate { it["id"].asText() to it["occupied"].asBoolean() } ==
                        mapOf(HostBench.SOURCE to source, alternative to other)
                }
            }
            read(source = false, other = true)
            val first = bench.post("/host/job-orders", request(from("JO-1", HostBench.SOURCE), "candidates", HUMANOID)).body!!["refusals"].single()
            assertEquals(listOf(alternative), first["alternativeLocations"].map { it.asText() }, first.toString())
            // 그사이 대체 위치가 비고 처음 자리가 다시 찼다.
            read(source = true, other = false)
            val second = bench.post("/host/job-orders", request(from("JO-2", alternative), "candidates", HUMANOID)).body!!
            assertEquals("UNASSIGNED", second["result"].asText(), second.toString())
            val refusal = second["refusals"].single()
            assertEquals(alternative, refusal["sourceMissing"]["source"].asText(), refusal.toString())
            assertEquals(listOf(HostBench.SOURCE), refusal["alternativeLocations"].map { it.asText() }, refusal.toString())
            assertEquals(0, bench.get("/host/executions")["executions"].size())
            // 대체 위치에 자재가 아직 있으면 같은 게이트를 지난다.
            read(source = false, other = true)
            val third = bench.post("/host/job-orders", request(from("JO-3", alternative), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", third["result"].asText(), third.toString())
        }
    }

    /** 출발 결품이 아닌 게이트 사유(여기서는 자재 선언과 배정이 어긋난 주문)는 대체 자리도 출발 자리도 싣지 않는다. 빈 목록으로 채우면 «든 자리 없음» 으로 읽힌다. */
    @Test
    fun `출발 결품이 아닌 기체별 미배정 사유는 대체 자리와 출발 자리가 둘 다 null 이다`() {
        HostBench().use { bench ->
            val mismatched = rack("JO-1", "RACK-204.S01").replace("\"quantity\":1", "\"quantity\":2")
            val outcome = bench.post("/host/job-orders", request(mismatched, "candidates", HUMANOID)).body!!
            assertEquals("UNASSIGNED", outcome["result"].asText(), outcome.toString())
            val refusal = outcome["refusals"].single()
            assertTrue(refusal["reason"].asText().startsWith("자재 선언과 배정이 어긋난다"), refusal.toString())
            assertTrue(refusal.has("alternativeLocations") && refusal["alternativeLocations"].isNull, refusal.toString())
            assertTrue(refusal.has("sourceMissing") && refusal["sourceMissing"].isNull, refusal.toString())
            assertTrue(outcome["alternativeLocations"].isNull && outcome["sourceMissing"].isNull, outcome.toString())
        }
    }

    @Test
    fun `받지 않는 WorkMaster 와 못 읽는 본문은 400 이고 JSON 이 아니면 415 다`() {
        HostBench().use { bench ->
            val malformed = inspect("JO-1", "T1" to "bay-7").replace("InspectAsset", "deliverContainer")
            listOf("/host/eligibility" to "robotIds", "/host/job-orders" to "candidates").forEach { (path, field) ->
                val unknown = bench.post(path, request(malformed, field, HUMANOID))
                assertEquals(400, unknown.status, "$path ${unknown.body}")
                assertEquals("UNKNOWN_WORK_MASTER", unknown.body!!["error"].asText())
                assertTrue(unknown.body["detail"].asText().startsWith("WorkMaster 이름이 아니다"), unknown.body.toString())

                val broken = bench.post(path, "{not json")
                assertEquals(400, broken.status, path)
                assertEquals("BAD_REQUEST", broken.body!!["error"].asText())

                val noId = bench.post(path, request(inspect("JO-1", "T1" to "bay-7").replace("\"jobOrderId\":\"JO-1\",", ""), field, HUMANOID))
                assertEquals(400, noId.status, path)
                assertEquals("BAD_REQUEST", noId.body!!["error"].asText())

                val noRobots = bench.post(path, """{"jobOrder":${inspect("JO-1", "T1" to "bay-7")}}""")
                assertEquals(400, noRobots.status, path)
                assertTrue(field in noRobots.body!!["detail"].asText(), noRobots.body.toString())

                val text = bench.post(path, request(inspect("JO-1", "T1" to "bay-7"), field, HUMANOID), contentType = "text/plain")
                assertEquals(415, text.status, path)
            }
            assertEquals(0, bench.get("/host/executions")["executions"].size())
        }
    }

    /**
     * 플릿 포트가 없는 호스트(지금 전부)는 플릿 단위가 든 계획을 판정·접수하지 않는다. 코드 임무 `DeliverContainer` 는 요청 파서가 받고
     * (예전에는 UNKNOWN_WORK_MASTER 로 먼저 막았다) 계획이 플릿 운반 단위라 같은 사유 FLEET_PORT_ABSENT 로 막힌다. 실행도 기체 명령도 남지 않는다.
     */
    @Test
    fun `플릿 운반 단위가 든 작업 지시는 판정과 접수 모두 400 FLEET_PORT_ABSENT 이고 실행이 서지 않는다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            assertEquals(false, bench.host.hasFleetPort)
            val deliver = """{"jobOrderId":"WT-781","workMasterId":"${DeliverContainer.WORK_MASTER}","version":1,"requiredEvidence":"E2",""" +
                """"parameters":{},"materialRequirements":[],"equipmentRequirements":[""" +
                """{"id":"OUT-07","equipmentUse":"source","properties":{"container":"HU-1042"}},""" +
                """{"id":"SEQ-IN-02","equipmentUse":"destination","properties":{}}]}"""
            // 고정 버전 CODE 도 같은 길이다. 용기가 빠진 주문도 계획이 플릿 단위(INCOMPLETE_ORDER)라 같다.
            val pinned = deliver.removeSuffix("}") + ""","missionVersion":"CODE"}"""
            val incomplete = deliver.replace(""""properties":{"container":"HU-1042"}""", """"properties":{}""")
            listOf("/host/eligibility" to "robotIds", "/host/job-orders" to "candidates").forEach { (path, field) ->
                listOf(deliver, pinned, incomplete).forEach { order ->
                    val refused = bench.post(path, request(order, field, HUMANOID))
                    assertEquals(400, refused.status, "$path ${refused.body}")
                    assertEquals(HostRequests.FLEET_PORT_ABSENT, refused.body!!["error"].asText(), refused.body.toString())
                    assertTrue(refused.body["detail"].asText().startsWith(HostRequests.FLEET_PORT_ABSENT_DETAIL), refused.body.toString())
                    assertTrue(DeliverContainer.WORK_MASTER in refused.body["detail"].asText(), refused.body.toString())
                }
            }
            assertTrue("HU-1042" in bench.post("/host/job-orders", request(deliver, "candidates", HUMANOID)).body!!["detail"].asText())
            assertEquals(0, bench.get("/host/executions")["executions"].size())
            assertEquals(0, bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.size })

            // 로봇 경로 작업 지시는 그대로 받는다 — 거절은 계획에 플릿 단위가 있을 때뿐이다.
            val rack = bench.post("/host/job-orders", request(rack("JO-2", "RACK-204.S01"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", rack["result"].asText(), rack.toString())
        }
    }

    @Test
    fun `실행 목록은 미들웨어 인스턴스와 마지막 pump 시각을 낸다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val view = bench.get("/host/executions")
            assertTrue(view["instanceId"].asText().startsWith("mw-"), view.toString())
            assertEquals(bench.now().toString(), view["pumpedAt"].asText())
            assertEquals(0, view["executions"].size())
        }
    }

    @Test
    fun `셀 대역 스냅숏을 pump 마다 읽어 내고 못 읽으면 null 이다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val cell = bench.get("/host/cell")["cell"]
            assertEquals(listOf(HostBench.SOURCE), cell["presentations"].map { it["id"].asText() })
            assertEquals(HostBench.MATERIAL, cell["presentations"][0]["material"].asText())
            assertTrue(cell["presentations"][0]["observedAt"].isNull)
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02", "RACK-204.S03", "RACK-204.S04"), cell["slots"].map { it["id"].asText() })

            bench.stopCell()
            // 지금 대기 중인 pump 가 앞선 스냅숏을 들고 끝날 수 있어 두 번을 지나 본다.
            bench.mimic.server.advance(java.time.Duration.ofSeconds(1))
            bench.awaitPump()
            bench.mimic.server.advance(java.time.Duration.ofSeconds(1))
            bench.awaitPump()
            assertTrue(bench.get("/host/cell")["cell"].isNull)
        }
    }
}
