package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.JobOrderBench.Companion.HUMANOID
import dev.picasso.ops.service.JobOrderBench.Companion.INSPECT_FORM
import dev.picasso.ops.service.JobOrderBench.Companion.QUADRUPED
import dev.picasso.ops.service.JobOrderBench.Companion.missing
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostJudgmentRefusal
import dev.picasso.ops.service.joborders.EligibilityView
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.joborders.JobOrderOutcome
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.web.JobOrderController
import dev.picasso.ops.service.web.PreRejection
import dev.picasso.registry.PostgresSupport
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.util.LinkedMultiValueMap
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * 작업 지시 API 의 게이트와 사전 거부(S3a 스펙 §8·§10). 컨트롤러를 스프링 없이 바로 부른다. 게이트(`guarded`)과 폼 읽기, 상태 코드의
 * 고름이 컨트롤러 안에 있기 때문이다. `application/json` 제한은 스프링의 몫이라 통합 시험이 본다.
 */
class JobOrderControllerTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = JobOrderBench()
    private val controller = JobOrderController(
        bench.eligibility,
        JobOrderOperations(bench.eligibility, bench.host, bench.host, log, bench.clock, requeryDelay = Duration.ZERO),
        bench.host,
        dev.picasso.ops.service.requests.WorkRequests(JdbcClient.create(dataSource),
            dev.picasso.ops.service.interfaces.MissionInterfaces(JdbcClient.create(dataSource),org.springframework.transaction.support.TransactionTemplate(org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)),log),
            JobOrderOperations(bench.eligibility,bench.host,bench.host,log,bench.clock,requeryDelay=Duration.ZERO),bench.host,
            org.springframework.transaction.support.TransactionTemplate(org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource))),
    )
    private val json = jacksonObjectMapper()

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    private fun submit(mode: String?, form: String = INSPECT_FORM, user: String? = "kim"): ResponseEntity<Any> =
        controller.submit(mode, user, form.toByteArray())

    private fun ResponseEntity<Any>.rejection(): PreRejection = assertIs<PreRejection>(body)

    @Test
    fun `엔지니어 모드의 작업 지시 제출은 403 이고 호스트를 부르지 않고 기록하지 않는다`() {
        val reply = submit("engineer")
        assertEquals(403, reply.statusCode.value())
        assertEquals("MODE_NOT_ALLOWED", reply.rejection().error)
        // 깨진 본문도 게이트가 먼저다.
        assertEquals(403, submit("engineer", form = "작업 지시").statusCode.value())
        assertEquals(emptyList(), bench.judged)
        assertEquals(emptyList(), bench.submitted)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `행위자 헤더가 없거나 틀리면 400 ACTOR_REQUIRED 다`() {
        assertEquals("ACTOR_REQUIRED", submit(null).rejection().error)
        assertEquals(400, submit(null).statusCode.value())
        assertEquals("ACTOR_REQUIRED", submit("operator", user = "김 운영").rejection().error)
        assertEquals(emptyList(), bench.submitted)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `운영자 모드 제출은 200 과 요청 id·작업 지시 id·결과·확인·호스트 응답이다`() {
        val reply = submit("operator")
        assertEquals(200, reply.statusCode.value())
        val outcome = assertIs<JobOrderOutcome>(reply.body)
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertEquals(HUMANOID, outcome.outcome!!.robotId)
        val body = json.valueToTree<JsonNode>(outcome)
        assertEquals(listOf("requestId", "jobOrderId", "result", "confirmation", "outcome"), body.fieldNames().asSequence().toList())
        assertEquals(bench.submitted.single().first["jobOrderId"].asText(), body["jobOrderId"].asText())
        assertEquals(listOf(OperationResult.SUCCEEDED), log.list().map { it.result })
    }

    @Test
    fun `후보가 없으면 400 NO_ELIGIBLE_ROBOT 이고 detail 에 기체별 이유가 있고 기록하지 않는다`() {
        bench.judge = { ids -> HostCall.Ok(ids.map { missing(it, "inspect") }) }
        val reply = submit("operator")
        assertEquals(400, reply.statusCode.value())
        val rejection = reply.rejection()
        assertEquals(JobOrderOperations.NO_ELIGIBLE_ROBOT, rejection.error)
        assertEquals("$HUMANOID: 모자란 스킬: inspect / $QUADRUPED: 모자란 스킬: inspect", rejection.detail)
        assertEquals(emptyList(), bench.submitted)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `호스트가 판정을 FLEET_PORT_ABSENT 로 거부하면 판정 화면에 그 사유가 가고 제출은 400 FLEET_PORT_ABSENT 이며 기록하지 않는다`() {
        bench.judge = { HostCall.Silent("HTTP 400", refusal = HostJudgmentRefusal(JobOrderOperations.FLEET_PORT_ABSENT, "이 실행 호스트에는 플릿 포트가 없어 플릿 운반 단위가 든 작업 지시를 받지 않습니다: ContainerSupply 의 플릿 운반 단위 HU-1")) }
        val reply = submit("operator")
        assertEquals(400, reply.statusCode.value())
        val rejection = reply.rejection()
        assertEquals(listOf(JobOrderOperations.FLEET_PORT_ABSENT, "이 실행 호스트에는 플릿 포트가 없어 플릿 운반 단위가 든 작업 지시를 받지 않습니다: ContainerSupply 의 플릿 운반 단위 HU-1"), listOf(rejection.error, rejection.detail))
        assertEquals(emptyList(), bench.submitted)
        assertEquals(emptyList(), log.list())

        val judged = assertIs<dev.picasso.ops.service.joborders.EligibilityView>(controller.eligibility(INSPECT_FORM.toByteArray()).body)
        assertEquals(dev.picasso.ops.service.joborders.HostState.HOST_REFUSED, judged.host, judged.toString())
        assertEquals(JobOrderOperations.FLEET_PORT_ABSENT, judged.hostRefusal?.error, judged.toString())
    }

    @Test
    fun `단위 id 가 겹치는 폼은 판정과 제출 모두 400 UNIT_ID_CONFLICT 이고 호스트에 닿지 않는다`() {
        val form = """{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"a"},{"id":"T1.travel","location":"b"}]}"""
        listOf(controller.eligibility(form.toByteArray()), submit("operator", form)).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals(JobOrderForm.UNIT_ID_CONFLICT, reply.rejection().error)
        }
        assertEquals("JOB_ORDER_BAD_REQUEST", controller.eligibility(null).rejection().error)
        assertEquals(emptyList(), bench.judged)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `판정은 모드 헤더 없이 되고 판정용 작업 지시 id 로 호스트에 묻는다`() {
        val reply = controller.eligibility(INSPECT_FORM.toByteArray())
        assertEquals(200, reply.statusCode.value())
        assertEquals(listOf(HUMANOID, QUADRUPED), assertIs<EligibilityView>(reply.body).robots!!.filter { it.eligible }.map { it.robotId })
        assertEquals(JobOrderController.DRAFT_ID, bench.judged.single().first["jobOrderId"].asText())
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `실행 목록과 셀은 호스트 본문을 그대로 넘기고 호스트가 안 닿으면 503 과 이유다`() {
        val executions = bench.executionsWith("JO-1")
        bench.executions = executions
        assertSame((executions as HostCall.Ok).value, controller.executions(LinkedMultiValueMap()).body)
        assertSame((bench.cell as HostCall.Ok).value, controller.cell().body)

        bench.executions = HostCall.Silent("응답 없음: ConnectException")
        bench.cell = HostCall.Silent("HTTP 500")
        val down = controller.executions(LinkedMultiValueMap())
        assertEquals(503, down.statusCode.value())
        assertEquals(PreRejection(JobOrderController.HOST_SILENT, "실행 호스트가 답하지 않는다: 응답 없음: ConnectException"), down.body)
        assertEquals(503, controller.cell().statusCode.value())
        assertEquals("실행 호스트가 답하지 않는다: HTTP 500", controller.cell().rejection().detail)
    }

    private fun params(query: String): LinkedMultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
        query.split("&").filter { it.isNotEmpty() }.forEach { part -> add(part.substringBefore("="), part.substringAfter("=", "")) }
    }

    @Test
    fun `화면 실행 페이지와 실행 수는 인자를 검사한 뒤 호스트 본문을 그대로 넘기고 인자 없는 목록과 모르는 인자는 그대로다`() {
        assertSame((bench.recentPage as HostCall.Ok).value, controller.executions(params("recent=20")).body)
        assertSame((bench.beforePage as HostCall.Ok).value, controller.executions(params("before=17&limit=5")).body)
        assertSame((bench.beforePage as HostCall.Ok).value, controller.executions(params("before=17")).body)
        assertSame((bench.summary as HostCall.Ok).value, controller.executionSummary().body)
        assertSame((bench.executions as HostCall.Ok).value, controller.executions(params("jobOrderId=JO-1&instanceId=mw-1&other=1")).body, "모르는 인자는 전처럼 버린다")
        assertEquals(listOf("recent=20", "before=17 limit=5", "before=17 limit=null", "summary"), bench.pageCalls)

        val bad = listOf(
            "recent=0", "recent=201", "recent=x", "recent=", "recent=1&recent=2", "recent=1&before=3", "recent=1&limit=2",
            "limit=2", "before=0", "before=-1", "before=x", "before=", "before=3&before=4", "before=3&limit=0", "before=3&limit=201",
        )
        for (query in bad) {
            val reply = controller.executions(params(query))
            assertEquals(400, reply.statusCode.value(), query)
            assertEquals(JobOrderController.EXECUTIONS_BAD_REQUEST, reply.rejection().error, query)
        }
        assertEquals(4, bench.pageCalls.size, "틀린 인자는 호스트에 닿지 않는다")
        controller.executions(params("recent=200"))
        controller.executions(params("before=${Long.MAX_VALUE}&limit=200"))
        assertEquals(listOf("recent=200", "before=${Long.MAX_VALUE} limit=200"), bench.pageCalls.drop(4))

        bench.beforePage = HostCall.Silent("응답 없음: ConnectException")
        bench.recentPage = HostCall.Silent("HTTP 500")
        bench.summary = HostCall.Silent("응답 없음: ConnectException")
        for (down in listOf(controller.executions(params("before=1")), controller.executions(params("recent=20")), controller.executionSummary())) {
            assertEquals(503, down.statusCode.value())
            assertEquals(JobOrderController.HOST_SILENT, down.rejection().error)
        }
        assertEquals("실행 호스트가 답하지 않는다: HTTP 500", controller.executions(params("recent=20")).rejection().detail)
    }
}
