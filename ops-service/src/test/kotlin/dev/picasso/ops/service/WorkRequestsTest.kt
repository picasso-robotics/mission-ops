package dev.picasso.ops.service
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.interfaces.InterfaceMapping
import dev.picasso.ops.service.interfaces.MissionInterfaces
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.requests.*
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.web.WorkRequestController
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
class WorkRequestsTest {
 private val ds=DriverManagerDataSource(PostgresSupport.jdbcUrl,PostgresSupport.username,PostgresSupport.password)
 private val jdbc=JdbcClient.create(ds);private val log=OperationLog(jdbc);private val json=ObjectMapper()
 private val bench=JobOrderBench()
 private val interfaces=MissionInterfaces(jdbc,TransactionTemplate(DataSourceTransactionManager(ds)),log)
 private val operations=JobOrderOperations(bench.eligibility,bench.host,bench.host,log,bench.clock,requeryDelay=Duration.ZERO)
 private fun service()=WorkRequests(jdbc,interfaces,operations,bench.host,TransactionTemplate(DataSourceTransactionManager(ds)))
 private val service=service()
 private val body=json.readTree("""{"externalOrderId":"ORDER-1","interfaceId":"asset-check","interfaceVersion":1,"input":{"assets":[{"id":"T1","location":"bay-7"}]}}""")
 @BeforeTest fun fresh() {
  OpsSchema.flyway(ds,cleanable=true).apply{clean();migrate()}
  val spec=InterfaceMapping.spec(json.readTree("""{"interfaceId":"asset-check","version":1,"name":"점검","workMasterId":"InspectAsset","mapping":{"targets":"/assets"},"sample":{"assets":[{"id":"T1","location":"bay-7"}]}}"""))
  interfaces.create(spec,Actor(Mode.ENGINEER,"lee"));interfaces.state("asset-check",1,"DRAFT","ENABLED","검증",Actor(Mode.ENGINEER,"lee"))
 }
 @Test fun `같은 주문은 동일 접수와 내부 ID를 반환하고 저장소를 다시 만들어도 재전송하지 않는다`() {
  val first=service.receive("mes",body);assertFalse(first.duplicate);assertEquals("ACCEPTED",first.receipt.state)
  val replay=service().receive("mes",body);assertTrue(replay.duplicate);assertEquals(first.receipt,replay.receipt)
  assertEquals(first.receipt.jobOrderId,bench.submitted.single().first["jobOrderId"].asText())
  assertTrue(log.list().any { it.requestId == first.receipt.receiptId })
 }
 @Test fun `JSON 키 순서는 무관하고 다른 입력은 409이며 출처별 키는 독립적이다`() {
  service.receive("mes",body)
  val reordered=json.readTree("""{"input":{"assets":[{"location":"bay-7","id":"T1"}]},"interfaceVersion":1,"interfaceId":"asset-check","externalOrderId":"ORDER-1"}""")
  assertTrue(service.receive("mes",reordered).duplicate)
  val changed=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>();changed.put("interfaceVersion",2)
  assertEquals(409,assertFailsWith<RequestRejected>{service.receive("mes",changed)}.status)
  assertFalse(service.receive("wms",body).duplicate)
  assertEquals(2,bench.submitted.size)
 }
 @Test fun `동시 중복 접수는 호스트를 한 번만 부른다`() {
  val gate=CountDownLatch(1)
  Executors.newFixedThreadPool(6).use { pool ->
   val jobs=(1..6).map{pool.submit<ReceiptAccepted>{gate.await();service.receive("mes",body)}}
   gate.countDown();val answers=jobs.map{it.get(20,TimeUnit.SECONDS)}
   assertEquals(1,answers.count{!it.duplicate});assertEquals(1,answers.map{it.receipt.receiptId}.distinct().size)
  }
  assertEquals(1,bench.submitted.size)
 }
 @Test fun `업무 검증 거절과 미배정도 영속 접수로 남고 같은 주문은 재평가하지 않는다`() {
  val invalid=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>();invalid.set<com.fasterxml.jackson.databind.JsonNode>("input",json.readTree("{}"))
  assertEquals("REJECTED",service.receive("mes",invalid).receipt.state);assertTrue(bench.submitted.isEmpty())
  bench.judge={HostCall.Ok(it.map{id->JobOrderBench.running(id,"busy")})}
  val row=service.receive("wms",body).receipt;assertEquals("UNASSIGNED",row.state)
  assertTrue(service().receive("wms",body).duplicate);assertTrue(bench.submitted.isEmpty())
  assertEquals(2L,service.list("",null,0,20)["total"])
 }
 @Test fun `응답 불명과 제출 중단 상태는 재접수로 다시 실행하지 않는다`() {
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")
  val row=service.receive("mes",body).receipt;assertEquals("IN_DOUBT",row.state)
  assertTrue(service.receive("mes",body).duplicate);assertEquals(1,bench.submitted.size)
  jdbc.sql("UPDATE ops.work_request SET state='DISPATCHING' WHERE receipt_id=:id").param("id",row.receiptId).update()
  assertEquals("DISPATCHING",service().receive("mes",body).receipt.state);assertEquals(1,bench.submitted.size)
 }
 @Test fun `전송 불명은 실행 관측으로 확정하고 호스트 단절 후에도 직전 관측을 보존한다`() {
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")
  val row=service.receive("mes",body).receipt
  assertEquals("IN_DOUBT",row.state)
  bench.executions=bench.executionsWith(row.jobOrderId)
  val seen=service().detail(row.receiptId)
  val confirmed=seen["receipt"] as Receipt
  assertEquals("ACCEPTED",confirmed.state);assertNotNull(confirmed.lastExecution)
  bench.executions=HostCall.Silent("offline")
  val stale=service().detail(row.receiptId)
  assertEquals("UNAVAILABLE",stale["hostRead"]);assertNull(stale["execution"])
  assertEquals(confirmed.lastExecution,(stale["receipt"] as Receipt).lastExecution)
  assertEquals(1,bench.submitted.size)
 }
 @Test fun `작업 허가로 막힌 실행은 작업 요청 상세의 실행에 막은 기체와 주체와 풀릴 근거를 그대로 싣고 작업 요청 상태를 바꾸지 않는다`() {
  val row=service.receive("mes",body).receipt
  val block=json.readTree("""{"unitId":"T1.travel","withheld":"START","robots":[{"robotId":"humanoid-01","permit":"INHIBITED","reasons":[{"source":"cell/guard_closed","cause":"REPORTED",
   "subject":"guard_closed=false","resumeBasis":"안전 계통이 guard_closed 을 true 으로 되돌릴 때","observedAt":"2026-10-14T00:00:01Z"}]}],"since":"2026-10-14T00:00:00Z"}""")
  bench.executions=HostCall.Ok(json.readTree("""{"instanceId":"mw-1","executions":[{"executionId":"exec-1","jobOrderId":"${row.jobOrderId}","workMasterId":"InspectAsset","missionVersion":null,
   "robotId":"humanoid-01","physicalState":"RUNNING","units":[{"unitId":"T1.travel","skillType":"navigate","state":"PENDING","reached":"NONE"}],"jobResponse":null,"permitBlock":$block}]}"""))
  val detail=service().detail(row.receiptId)
  assertEquals(block,(detail["execution"] as com.fasterxml.jackson.databind.JsonNode)["permitBlock"])
  assertEquals(row.state,(detail["receipt"] as Receipt).state)
 }
 @Test fun `스테이션 포트는 실행의 작업 허가 차단을 작업 요청에 싣고 차단이 없거나 실행 목록을 못 읽으면 싣지 않는다`() {
  val row=service.receive("mes",body).receipt
  val port=dev.picasso.ops.service.stations.OpsStationPort(service,interfaces,bench.host)
  val block=json.readTree("""{"unitId":"T1.travel","withheld":"START","robots":[],"since":"2026-10-14T00:00:00Z"}""")
  fun executions(extra:String)=HostCall.Ok(json.readTree("""{"instanceId":"mw-1","executions":[${execution("exec-1",row.jobOrderId,extra)}]}"""))
  bench.executions=executions(""","permitBlock":$block""");assertEquals(block,port.receipt(row.receiptId).permitBlock)
  bench.executions=executions(""","permitBlock":null""");assertNull(port.receipt(row.receiptId).permitBlock)
  bench.executions=HostCall.Silent("offline");assertNull(port.receipt(row.receiptId).permitBlock)
 }
 @Test fun `스테이션 포트는 호스트의 작업 허가를 읽고 선언 없음과 못 읽음을 가른다`() {
  val port=dev.picasso.ops.service.stations.OpsStationPort(service,interfaces,bench.host)
  assertEquals(dev.picasso.ops.service.stations.StationPermit.Undeclared,port.permit())
  bench.workPermits=HostCall.Ok(json.readTree("""{"declared":true,"robots":[{"robotId":"humanoid-01","permit":"INHIBITED","reasons":[{"source":"cell/guard_closed","cause":"REPORTED","subject":"guard_closed=false","resumeBasis":"b","observedAt":"t"}]}]}"""))
  val read=assertIs<dev.picasso.ops.service.stations.StationPermit.Read>(port.permit())
  assertEquals(listOf("humanoid-01"),read.blocking.map{it.robotId})
  // A host that answered is reachable: an error status or a body that is not a permit is an unreadable permit, never an unreachable host.
  bench.workPermits=HostCall.Silent("HTTP 503")
  assertIs<dev.picasso.ops.service.stations.StationPermit.Unreadable>(port.permit())
  bench.workPermits=HostCall.Ok(json.readTree("""{"declared":"yes"}"""))
  assertIs<dev.picasso.ops.service.stations.StationPermit.Unreadable>(port.permit())
  // No answer at all is its own case.
  bench.workPermits=HostCall.Silent("응답 없음: ConnectException",unreached=true)
  assertEquals(dev.picasso.ops.service.stations.StationPermit.HostUnreachable("응답 없음: ConnectException"),port.permit())
 }
 @Test fun `접수 저장 실패 시 호스트는 호출하지 않는다`() {
  PostgresSupport.execute("ALTER TABLE ops.work_request ADD CONSTRAINT deny_receipt CHECK (false) NOT VALID")
  assertFails {service.receive("mes",body)};assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `인증은 서버 출처에 묶이고 다른 출처 접수는 404이며 빈 토큰은 접근을 막는다`() {
  val api=WorkRequestController(service,"secret-test-token","mock-mes")
  assertEquals(401,api.receive(null,body.toString().toByteArray()).statusCode.value())
  assertEquals(401,WorkRequestController(service,"","mock-mes").receive("Bearer x",body.toString().toByteArray()).statusCode.value())
  assertEquals(202,api.receive("Bearer secret-test-token",body.toString().toByteArray()).statusCode.value())
  assertEquals(200,api.receive("Bearer secret-test-token",body.toString().toByteArray()).statusCode.value())
  val other=service.receive("other-mes",body).receipt
  assertEquals(404,api.external("Bearer secret-test-token",other.receiptId).statusCode.value())
  assertEquals(400,api.receive("Bearer secret-test-token",ByteArray(65537)).statusCode.value())
 }
 @Test fun `호스트 조회 실패를 실행 없음과 구분하며 검색과 페이지 범위를 제한한다`() {
  val row=service.receive("mes",body).receipt
  bench.executions=HostCall.Silent("offline")
  assertEquals("UNAVAILABLE",service.detail(row.receiptId)["hostRead"])
  bench.executions=HostCall.Ok(json.readTree("{}"))
  assertEquals("UNAVAILABLE",service.detail(row.receiptId)["hostRead"])
  assertEquals(1L,service.list("ORDER-1","ACCEPTED",0,20)["total"])
  assertEquals(0L,service.list("missing",null,0,20)["total"])
  assertFailsWith<RequestRejected>{service.list("",null,0,101)}
  assertEquals(404,assertFailsWith<RequestRejected>{service.get(row.receiptId,"other")}.status)
 }

 private fun observeResult(job:String,version:Int=1,state:String="PHYSICALLY_DONE",blocked:Boolean=false,missionVersion:Int?=null) {
  bench.executions=HostCall.Ok(json.readTree("""{"instanceId":"mw-1","journalBlockedExecutionIds":${if(blocked) "[\"exec-1\"]" else "[]"},"executions":[{
   "executionId":"exec-1","jobOrderId":"$job","workMasterId":"InspectAsset","missionVersion":$missionVersion,"robotId":"humanoid-01","physicalState":"$state",
   "jobResponse":{"jobResponseId":"response-$version","version":$version,"physicalState":"$state","requiredEvidence":"E2","reachedEvidence":"E2",
   "completedUnits":["T1"],"unverifiedUnits":[],"incompleteUnits":{},"inDoubtUnits":[],"operatorRequired":false,"residualHold":"EMPTY","blockedBy":[],"connection":"ONLINE"}}]}"""))
 }
 @Test fun `결과 재조회는 같은 불변 버전이며 호스트 단절과 저장소 재생성 후에도 조회한다`() {
  val row=service.receive("mes",body).receipt
  assertEquals(404,assertFailsWith<RequestRejected>{service.result(row.receiptId,"mes")}.status)
  observeResult(row.jobOrderId)
  val result=service.result(row.receiptId,"mes");assertEquals(1,result["version"])
  assertNull(result["acknowledgedAt"])
  assertEquals(result,service.result(row.receiptId,"mes"))
  bench.executions=HostCall.Silent("offline")
  assertEquals(result,service().result(row.receiptId,"mes",1))
  assertEquals(result,service().result(row.receiptId,"mes"))
  assertEquals(1,bench.submitted.size)
 }
 @Test fun `ACK는 버전과 해시에 묶이고 재작업의 최신 결과는 별도로 확인한다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId,1,"PARTIAL")
  val first=service.result(row.receiptId,"mes")
  assertEquals(409,assertFailsWith<RequestRejected>{service.acknowledge(row.receiptId,"mes",1,"0".repeat(64))}.status)
  val ack=service.acknowledge(row.receiptId,"mes",1,first["resultHash"] as String)
  assertNotNull(ack["acknowledgedAt"])
  assertEquals(ack,service().acknowledge(row.receiptId,"mes",1,first["resultHash"] as String))
  observeResult(row.jobOrderId,2)
  val second=service.result(row.receiptId,"mes");assertEquals(2,second["version"]);assertNull(second["acknowledgedAt"])
  service.acknowledge(row.receiptId,"mes",1,first["resultHash"] as String)
  assertNull(service.result(row.receiptId,"mes",2)["acknowledgedAt"])
  assertEquals(first["snapshot"],service.result(row.receiptId,"mes",1)["snapshot"])
  assertEquals(404,assertFailsWith<RequestRejected>{service.acknowledge(row.receiptId,"other",2,second["resultHash"] as String)}.status)
  assertEquals(404,assertFailsWith<RequestRejected>{service.acknowledge(row.receiptId,"mes",3,second["resultHash"] as String)}.status)
  assertEquals(1,bench.submitted.size)
 }
 @Test fun `같은 작업 버전의 새 결과 ID도 별도 결과로 보존한다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId,1,"PARTIAL")
  val first=service.result(row.receiptId,"mes");assertEquals(1,first["version"])
  observeResult(row.jobOrderId,1)
  val host=(bench.executions as HostCall.Ok).value.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
  (host["executions"][0]["jobResponse"] as com.fasterxml.jackson.databind.node.ObjectNode).put("jobResponseId","response-next")
  bench.executions=HostCall.Ok(host)
  val second=service.result(row.receiptId,"mes");assertEquals(2,second["version"])
  assertEquals("PHYSICALLY_DONE",(second["snapshot"] as com.fasterxml.jackson.databind.JsonNode)["jobResponse"]["physicalState"].asText())
  assertEquals(first["snapshot"],service.result(row.receiptId,"mes",1)["snapshot"])
 }
 @Test fun `동시 결과 관측은 한 버전을 만들며 늦은 이전 응답은 최신 결과를 되돌리지 않는다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId,2)
  Executors.newFixedThreadPool(6).use { pool ->
   val answers=(1..6).map{pool.submit<Map<String,Any?>>{service().result(row.receiptId,"mes")}}.map{it.get(20,TimeUnit.SECONDS)}
   assertEquals(setOf(1),answers.map{it["version"]}.toSet())
  }
  observeResult(row.jobOrderId,1,"PARTIAL")
  assertEquals(1,service.result(row.receiptId,"mes")["version"])
  val response=(service.result(row.receiptId,"mes")["snapshot"] as com.fasterxml.jackson.databind.JsonNode)["jobResponse"]
  assertEquals(2,response["version"].asInt())
 }
 @Test fun `일지 차단과 재작업 중 이전 응답은 결과를 발행하지 않는다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId,blocked=true)
  assertEquals(404,assertFailsWith<RequestRejected>{service.result(row.receiptId,"mes")}.status)
  observeResult(row.jobOrderId)
  val host=(bench.executions as HostCall.Ok).value.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
  (host["executions"][0] as com.fasterxml.jackson.databind.node.ObjectNode).put("physicalState","RUNNING")
  bench.executions=HostCall.Ok(host)
  assertEquals(404,assertFailsWith<RequestRejected>{service.result(row.receiptId,"mes")}.status)
 }
 @Test fun `외부 결과 API는 인증과 정확한 ACK 본문을 요구한다`() {
  val api=WorkRequestController(service,"secret","mes")
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId)
  assertEquals(401,api.latestResult(null,row.receiptId).statusCode.value())
  assertEquals(200,api.latestResult("Bearer secret",row.receiptId).statusCode.value())
  assertEquals(400,api.acknowledge("Bearer secret",row.receiptId,1,"{}".toByteArray()).statusCode.value())
  val result=service.result(row.receiptId,"mes")
  val ack=json.writeValueAsBytes(mapOf("resultHash" to result["resultHash"]))
  assertEquals(200,api.acknowledge("Bearer secret",row.receiptId,1,ack).statusCode.value())
  assertEquals(200,api.result("Bearer secret",row.receiptId,1).statusCode.value())
 }

 private val manualActor=Actor(Mode.OPERATOR,"kim")
 private fun manualBody(id:java.util.UUID=java.util.UUID.randomUUID(),form:String=JobOrderBench.INSPECT_FORM)=json.readTree("""{"requestId":"$id","form":$form}""")
 @Test fun `수동 폼은 영속 접수와 요청자를 기록하고 같은 요청 ID는 재기동 후에도 재실행하지 않는다`() {
  val input=manualBody();val first=service.receiveManual(manualActor,input)
  assertEquals("ops:manual",first.receipt.sourceSystemId);assertEquals("operator/kim",first.receipt.payload["requestedBy"].asText())
  assertEquals("ACCEPTED",first.receipt.state);assertFalse(first.duplicate)
  val replay=service().receiveManual(manualActor,input)
  assertTrue(replay.duplicate);assertEquals(first.receipt,replay.receipt);assertEquals(1,bench.submitted.size)
  assertEquals("kim",log.list().single{it.requestId==first.receipt.receiptId}.user)
  assertEquals(1L,service.list("ops:manual",null,0,20)["total"])
  observeResult(first.receipt.jobOrderId);assertNotNull(service.detail(first.receipt.receiptId)["latestResult"])
  assertEquals(404,assertFailsWith<RequestRejected>{service.result(first.receipt.receiptId,"mock-mes")}.status)
 }
 @Test fun `수동 요청은 입력과 요청자 충돌을 거절하고 동시 중복을 한 번만 제출한다`() {
  val input=manualBody()
  Executors.newFixedThreadPool(6).use { pool ->
   val gate=CountDownLatch(1)
   val jobs=(1..6).map{pool.submit<ReceiptAccepted>{gate.await();service().receiveManual(manualActor,input)}}
   gate.countDown();assertEquals(1,jobs.map{it.get(20,TimeUnit.SECONDS)}.count{!it.duplicate})
  }
  assertEquals(1,bench.submitted.size)
  val changed=input.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
  (changed["form"]["targets"][0] as com.fasterxml.jackson.databind.node.ObjectNode).put("location","bay-9")
  assertEquals(409,assertFailsWith<RequestRejected>{service.receiveManual(manualActor,changed)}.status)
  assertEquals(409,assertFailsWith<RequestRejected>{service.receiveManual(Actor(Mode.OPERATOR,"lee"),input)}.status)
  assertEquals(1,bench.submitted.size)
 }
 @Test fun `수동 업무 검증 거절과 미배정도 접수 목록에 남고 자동 재배정하지 않는다`() {
  val bad=service.receiveManual(manualActor,manualBody(form="{}"))
  assertEquals("REJECTED",bad.receipt.state);assertNotNull(bad.receipt.detail)
  bench.judge={HostCall.Ok(it.map{id->JobOrderBench.running(id,"busy")})}
  val input=manualBody();val unassigned=service.receiveManual(manualActor,input)
  assertEquals("UNASSIGNED",unassigned.receipt.state)
  bench.judge={HostCall.Ok(it.map(JobOrderBench::pass))}
  assertTrue(service.receiveManual(manualActor,input).duplicate);assertTrue(bench.submitted.isEmpty())
  assertEquals(2L,service.list("ops:manual",null,0,20)["total"])
 }
 @Test fun `수동 전송 불명과 중단 상태는 동일 요청으로 재제출하지 않는다`() {
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")
  val input=manualBody();val row=service.receiveManual(manualActor,input).receipt
  assertEquals("IN_DOUBT",row.state)
  assertTrue(service().receiveManual(manualActor,input).duplicate)
  jdbc.sql("UPDATE ops.work_request SET state='DISPATCHING' WHERE receipt_id=:id").param("id",row.receiptId).update()
  assertEquals("DISPATCHING",service().receiveManual(manualActor,input).receipt.state);assertEquals(1,bench.submitted.size)
 }
 @Test fun `수동 접수 API는 운영자 게이트와 본문 크기 및 UUID 형식을 확인한다`() {
  val api=WorkRequestController(service,"secret","mock-mes");val input=manualBody().toString().toByteArray()
  assertEquals(403,api.manual("engineer","kim",null).statusCode.value())
  assertEquals(400,api.manual(null,"kim",input).statusCode.value())
  assertEquals(400,api.manual("operator","kim",ByteArray(65537)).statusCode.value())
  assertEquals(400,api.manual("operator","kim","{\"requestId\":\"1-1-1-1-1\",\"form\":{}}".toByteArray()).statusCode.value())
  assertEquals(0L,service.list("",null,0,20)["total"])
  val first=api.manual("operator","kim",input);assertEquals(202,first.statusCode.value())
  assertNotNull(first.headers.location)
  assertEquals(200,api.manual("operator","kim",input).statusCode.value())
  assertEquals(1,bench.submitted.size)
 }
 @Test fun `수동 접수 DB 실패 시 호스트에 제출하지 않는다`() {
  PostgresSupport.execute("ALTER TABLE ops.work_request ADD CONSTRAINT deny_manual CHECK (false) NOT VALID")
  assertFails{service.receiveManual(manualActor,manualBody())};assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `결과 수집은 조회 없이 저장하며 호스트 장애에도 저장 결과를 보존한다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId)
  assertEquals(1,service.collectResults())
  assertEquals(1,service().result(row.receiptId,"mes",1)["version"])
  assertEquals(1,service.collectResults());assertEquals(1,service.result(row.receiptId,"mes",1)["version"])
  bench.executions=HostCall.Silent("offline")
  assertFailsWith<IllegalStateException>{service.collectResults()}
  assertEquals(1,service.result(row.receiptId,"mes",1)["version"])
 }
 @Test fun `개요 집계와 수신 미확인 필터는 로컬 결과를 제외하고 최신 ACK를 따른다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId);service.collectResults()
  assertEquals(1L,service.summary()["unacknowledgedResults"])
  assertEquals(1L,service.list("",null,0,20,"unacknowledged")["total"])
  val result=service.result(row.receiptId,"mes",1)
  service.acknowledge(row.receiptId,"mes",1,result["resultHash"] as String)
  assertEquals(0L,service.summary()["unacknowledgedResults"])
  assertEquals(0L,service.list("",null,0,20,"unacknowledged")["total"])
  jdbc.sql("UPDATE ops.work_request SET state='DISPATCHING' WHERE receipt_id=:id").param("id",row.receiptId).update()
  assertEquals(1L,service.list("","NEEDS_CONFIRMATION",0,20)["total"])
 }
 @Test fun `전송 전 미배정 재평가는 같은 작업 ID로 한 번 진행하고 원래 접수 재요청은 재실행하지 않는다`() {
  bench.judge={HostCall.Ok(it.map{id->JobOrderBench.running(id,"busy")})}
  val row=service.receive("mes",body).receipt;assertEquals("UNASSIGNED",row.state);assertTrue(bench.submitted.isEmpty())
  val request=json.createObjectNode().put("requestId",java.util.UUID.randomUUID().toString()).put("reason","기체 준비 완료").put("expectedUpdatedAt",row.updatedAt)
  bench.judge={ids->HostCall.Ok(ids.map(JobOrderBench::pass))}
  val recovered=service.recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),request)
  assertEquals("ACCEPTED",recovered.receipt.state);assertFalse(recovered.duplicate)
  assertTrue(service().recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),request).duplicate)
  assertTrue(service.receive("mes",body).duplicate)
  assertEquals(row.jobOrderId,bench.submitted.single().first["jobOrderId"].asText())
  assertEquals(1,(service.detail(row.receiptId)["recoveries"] as List<*>).size)
  assertEquals(409,assertFailsWith<RequestRejected>{service.recover(row.receiptId,Actor(Mode.OPERATOR,"lee"),request)}.status)
 }
 @Test fun `불명 접수와 오래된 상태는 재평가할 수 없다`() {
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")
  val row=service.receive("mes",body).receipt
  val request=json.createObjectNode().put("requestId",java.util.UUID.randomUUID().toString()).put("reason","재개").put("expectedUpdatedAt",row.updatedAt)
  assertEquals(409,assertFailsWith<RequestRejected>{service.recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),request)}.status)
  assertEquals(403,assertFailsWith<RequestRejected>{service.recover(row.receiptId,Actor(Mode.ENGINEER,"kim"),request)}.status)
  assertEquals(1,bench.submitted.size)
  jdbc.sql("UPDATE ops.work_request SET state='UNASSIGNED',submission=NULL,updated_at=clock_timestamp() WHERE receipt_id=:id").param("id",row.receiptId).update()
  assertEquals(409,assertFailsWith<RequestRejected>{service.recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),request)}.status)
 }

 @Test fun `로컬 callback은 실패를 재전송하고 같은 버전 키와 내용을 유지하며 ACK와 분리한다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId);service.collectResults()
  val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
  val seen=java.util.Collections.synchronizedList(mutableListOf<Pair<String,String>>())
  server.createContext("/results") { exchange ->
   seen+=exchange.requestHeaders.getFirst("Idempotency-Key") to exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
   exchange.sendResponseHeaders(if(seen.size==1) 503 else 204,-1);exchange.close()
  };server.start()
  try {
   val url="http://127.0.0.1:${server.address.port}/results"
   WorkResultDelivery(jdbc,service,url,"","mes").deliver()
   assertEquals("RETRY",jdbc.sql("SELECT state FROM ops.work_result_delivery").query(String::class.java).single())
   assertEquals(1,seen.size)
   jdbc.sql("UPDATE ops.work_result_delivery SET next_attempt_at=clock_timestamp()-interval '1 second'").update()
   WorkResultDelivery(jdbc,service(),url,"","mes").deliver()
   assertEquals("DELIVERED",jdbc.sql("SELECT state FROM ops.work_result_delivery").query(String::class.java).single())
   assertEquals(2,seen.size);assertEquals(seen[0],seen[1]);assertEquals("${row.receiptId}/v1",seen[0].first)
   assertNull(service.result(row.receiptId,"mes",1)["acknowledgedAt"])
   WorkResultDelivery(jdbc,service,url,"","mes").deliver();assertEquals(2,seen.size)
  } finally {server.stop(0)}
 }
 @Test fun `로컬 callback은 외부 주소를 거부하며 비활성 시 큐를 만들지 않는다`() {
  assertFailsWith<IllegalArgumentException>{WorkResultDelivery(jdbc,service,"https://example.com/results","","mes")}
  assertFailsWith<IllegalArgumentException>{WorkResultDelivery(jdbc,service,"http://127.0.0.1.evil.test/results","","mes")}
  WorkResultDelivery(jdbc,service,"","","mes").deliver()
  assertEquals(0L,jdbc.sql("SELECT count(*) FROM ops.work_result_delivery").query(Long::class.java).single())
 }
 // 결과 버전을 적는 자리에서 전달 행을 넣는다(부하 기준선 2026-10-14 핫스팟 2). 주기는 기동 뒤 한 번만 결과 전체를 훑는다.
 private val closedUrl="http://127.0.0.1:9/results"
 private fun queued(url:String=closedUrl,source:String="mes")=WorkRequests(jdbc,interfaces,operations,bench.host,TransactionTemplate(DataSourceTransactionManager(ds)),delivery=ResultDeliveryQueue(url,source))
 private fun deliveries()=jdbc.sql("SELECT receipt_id,version,target_url,state FROM ops.work_result_delivery ORDER BY receipt_id,version")
  .query{rs,_ -> listOf(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4))}.list()
 private fun resultCount()=jdbc.sql("SELECT count(*) FROM ops.work_result").query(Long::class.java).single()
 @Test fun `결과 버전을 적을 때 같은 자리에서 버전마다 전달 행 하나를 넣고 다른 출처와 꺼진 전달은 넣지 않는다`() {
  val svc=queued()
  val row=svc.receive("mes",body).receipt
  observeResult(row.jobOrderId);svc.detail(row.receiptId);svc.detail(row.receiptId)
  assertEquals(listOf(listOf(row.receiptId.toString(),"1",closedUrl,"PENDING")),deliveries())
  observeResult(row.jobOrderId,2);svc.detail(row.receiptId)
  assertEquals(listOf("1","2"),deliveries().map{it[1]})
  val other=svc.receive("other-mes",body).receipt
  observeResult(other.jobOrderId);svc.detail(other.receiptId)
  assertEquals(1,svc.result(other.receiptId,"other-mes",1)["version"])
  val off=WorkRequests(jdbc,interfaces,operations,bench.host,TransactionTemplate(DataSourceTransactionManager(ds)),delivery=ResultDeliveryQueue("","mes"))
  val quiet=off.receive("mes",body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-OFF")).receipt
  observeResult(quiet.jobOrderId);off.detail(quiet.receiptId)
  assertEquals(4L,resultCount())
  assertEquals(2,deliveries().size)
  assertFailsWith<IllegalArgumentException>{ResultDeliveryQueue("https://example.com/results","mes")}
 }
 @Test fun `결과 버전과 전달 행은 한 트랜잭션이라 어느 쪽이 실패해도 둘 다 남지 않는다`() {
  val row=service.receive("mes",body).receipt;val page=feed(row.jobOrderId)
  val worker=WorkRequests(jdbc,interfaces,operations,object:dev.picasso.ops.service.host.HostReads by bench.host {override fun resultEvents(after:Long,limit:Int)=HostCall.Ok(page)},
   TransactionTemplate(DataSourceTransactionManager(ds)),durableResults=true,delivery=ResultDeliveryQueue(closedUrl,"mes"))
  // 둘째 결과 버전이 실패하면 첫 버전의 전달 행도 되돌아간다.
  jdbc.sql("ALTER TABLE ops.work_result ADD CONSTRAINT refuse_second CHECK(response_id<>'r2')").update()
  assertFailsWith<org.springframework.dao.DataAccessException>{worker.collectResults()}
  assertEquals(0L,resultCount());assertEquals(emptyList(),deliveries())
  jdbc.sql("ALTER TABLE ops.work_result DROP CONSTRAINT refuse_second").update()
  // 둘째 전달 행이 실패하면 두 결과 버전도 되돌아간다.
  jdbc.sql("ALTER TABLE ops.work_result_delivery ADD CONSTRAINT refuse_second CHECK(version<>2)").update()
  assertFailsWith<org.springframework.dao.DataAccessException>{worker.collectResults()}
  assertEquals(0L,resultCount());assertEquals(emptyList(),deliveries());assertEquals(0L,worker.cursorStatus()["sequence"])
  jdbc.sql("ALTER TABLE ops.work_result_delivery DROP CONSTRAINT refuse_second").update()
  assertEquals(2,worker.collectResults())
  assertEquals(listOf("1","2"),deliveries().map{it[1]});assertEquals(2L,resultCount())
 }
 @Test fun `운영 서비스 빈은 전달 설정과 같은 callback 주소와 출처로 결과 버전을 적을 때 전달 행을 넣는다`() {
  val row=service.receive("mes",body).receipt;val page=feed(row.jobOrderId)
  val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
  server.createContext("/host/result-events") { exchange ->
   val bytes=page.toString().toByteArray();exchange.responseHeaders.add("Content-Type","application/json")
   exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.write(bytes);exchange.close()
  };server.start()
  try {
   dev.picasso.ops.service.host.HostClient("http://127.0.0.1:${server.address.port}").use { host ->
    val off=OpsApplication().workRequests(jdbc,interfaces,operations,host,ds,"","mes")
    assertEquals(2,off.collectResults());assertEquals(emptyList(),deliveries())
    jdbc.sql("DELETE FROM ops.work_result").update();jdbc.sql("UPDATE ops.result_cursor SET producer_id=NULL,sequence=0,high_watermark=0").update()
    val bean=OpsApplication().workRequests(jdbc,interfaces,operations,host,ds,closedUrl,"mes")
    assertEquals(2,bean.collectResults())
    assertEquals(listOf(listOf(row.receiptId.toString(),"1",closedUrl,"PENDING"),listOf(row.receiptId.toString(),"2",closedUrl,"PENDING")),deliveries())
   }
  } finally {server.stop(0)}
 }
 @Test fun `전달 주기는 기동 뒤 첫 회차에만 전달 행 없는 예전 결과를 넣고 그 뒤 회차는 결과를 다시 훑지 않는다`() {
  // 쓸 때 넣지 않은 결과: 예전 판에서 적었거나 전달이 꺼져 있을 때 적은 것.
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId);service.detail(row.receiptId)
  val other=service.receive("other-mes",body).receipt;observeResult(other.jobOrderId);service.detail(other.receiptId)
  assertEquals(2L,resultCount());assertEquals(emptyList(),deliveries())
  val delivery=WorkResultDelivery(jdbc,service,closedUrl,"","mes")
  delivery.deliver()
  assertEquals(listOf(listOf(row.receiptId.toString(),"1",closedUrl,"RETRY")),deliveries())
  observeResult(row.jobOrderId,2);service.detail(row.receiptId)
  delivery.deliver()
  assertEquals(listOf("1"),deliveries().map{it[1]})
  val restarted=WorkResultDelivery(jdbc,service,closedUrl,"","mes")
  restarted.deliver()
  assertEquals(listOf("1","2"),deliveries().map{it[1]})
  assertEquals(0,restarted.catchUp())
 }
 @Test fun `쓸 때 넣은 전달 행은 넣은 순서로 한 회차에 정한 수만큼 보내고 같은 키로 보내며 ACK와 분리한다`() {
  val svc=queued()
  val rows=(1..3).map{n->svc.receive("mes",body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-Q$n")).receipt
   .also{observeResult(it.jobOrderId);svc.detail(it.receiptId)}}
  assertEquals(3,deliveries().size)
  val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
  val seen=java.util.Collections.synchronizedList(mutableListOf<String>())
  server.createContext("/results") { exchange ->
   seen+=exchange.requestHeaders.getFirst("Idempotency-Key");exchange.requestBody.readAllBytes()
   exchange.sendResponseHeaders(204,-1);exchange.close()
  };server.start()
  try {
   val url="http://127.0.0.1:${server.address.port}/results"
   jdbc.sql("UPDATE ops.work_result_delivery SET target_url=:url").param("url",url).update()
   assertFailsWith<IllegalArgumentException>{WorkResultDelivery(jdbc,svc,url,"","mes",0)}
   val delivery=WorkResultDelivery(jdbc,svc,url,"","mes",2)
   delivery.deliver()
   assertEquals(rows.take(2).map{"${it.receiptId}/v1"},seen.toList())
   delivery.deliver()
   assertEquals(rows.map{"${it.receiptId}/v1"},seen.toList())
   delivery.deliver();assertEquals(3,seen.size)
   assertEquals(listOf("DELIVERED"),deliveries().map{it[3]}.distinct())
   rows.forEach{assertNull(svc.result(it.receiptId,"mes",1)["acknowledgedAt"])}
  } finally {server.stop(0)}
 }
 @Test fun `작업 요청 목록과 개요 집계는 빈 검색과 수신 미확인 필터에서도 바꾸기 전 질의와 같은 행과 수를 낸다`() {
  val states=listOf("RECEIVED","WAITING_RESOURCE","DISPATCHING","ACCEPTED","REJECTED","UNASSIGNED","IN_DOUBT")
  val sources=listOf("mes","ops:manual","other-mes")
  // 결과 모양: 0 없음 · 1 v1 미확인 · 2 v1 확인 · 3 v1 확인+v2 미확인 · 4 v1 미확인+v2 확인 · 5 v1·v2 확인
  for(g in 1..84) {
   val id=UUID.nameUUIDFromBytes("eq-$g".toByteArray())
   jdbc.sql("""INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state,received_at,updated_at)
     VALUES(:id,:source,:ext,'h','{}'::jsonb,:job,:state,timestamptz '2026-10-14 00:00:00+00'+(:g/2)*interval '1 second',now())""")
    .param("id",id).param("source",sources[(g/6)%3]).param("ext","EXT-$g").param("job","JO-$g").param("state",states[g%7]).param("g",g).update()
   val shape=g%6
   fun result(v:Int,acked:Boolean)=jdbc.sql("""INSERT INTO ops.work_result(receipt_id,version,instance_id,execution_id,response_id,response_version,snapshot,result_hash,acknowledged_at)
     VALUES(:id,:v,'i','e','r'||:v,1,'{}'::jsonb,'h',CASE WHEN :acked THEN now() END)""").param("id",id).param("v",v).param("acked",acked).update()
   if(shape in 1..5) result(1,shape==2 || shape==3 || shape==5)
   if(shape>=3) result(2,shape!=3)
  }
  // 출처는 결과 모양과 따로 돈다: 모양마다 상위 출처 행이 있어야 «최신이 확인된 이전 미확인 버전» 이 필터에 닿는다.
  assertEquals(setOf(0,1,2,3,4,5),(1..84).filter{sources[(it/6)%3]!="ops:manual"}.map{it%6}.toSet())
  // LIKE wildcards and the escape character in ids: the search must take them literally, as strpos did.
  for((n,ext) in listOf("EXT_%A","EXT\\B","EXT%C_").withIndex()) jdbc.sql("""INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state,received_at,updated_at)
    VALUES(:id,'mock-mes',:ext,'h','{}'::jsonb,:job,'ACCEPTED',timestamptz '2026-10-15 00:00:00+00'+:n*interval '1 second',now())""")
   .param("id",UUID.nameUUIDFromBytes("wild-$n".toByteArray())).param("ext",ext).param("job","JO-W$n").param("n",n).update()
  fun before(search:String,state:String?,offset:Int,limit:Int,result:String?):Pair<Long,List<String>> {
   val stateFilter=if(state=="NEEDS_CONFIRMATION") "state IN ('RECEIVED','DISPATCHING','IN_DOUBT')" else "(:state IS NULL OR state=:state)"
   val resultFilter=if(result=="unacknowledged") " AND source_system_id NOT LIKE 'ops:%' AND (SELECT acknowledged_at IS NULL FROM ops.work_result WHERE receipt_id=ops.work_request.receipt_id ORDER BY version DESC LIMIT 1)" else ""
   val where="$stateFilter AND (strpos(external_order_id,:search)>0 OR strpos(job_order_id,:search)>0 OR strpos(source_system_id,:search)>0)$resultFilter"
   fun q(sql:String)=jdbc.sql(sql).param("search",search).let{if(state=="NEEDS_CONFIRMATION") it else it.param("state",state,java.sql.Types.VARCHAR)}
   return q("SELECT count(*) FROM ops.work_request WHERE $where").query(Long::class.java).single() to
    q("SELECT receipt_id FROM ops.work_request WHERE $where ORDER BY received_at DESC,receipt_id LIMIT :limit OFFSET :offset").param("limit",limit).param("offset",offset).query(String::class.java).list()
  }
  var compared=0
  val searches=listOf("","EXT-1","JO-7","ops:","mes","none","%","_","\\","T_%","T%C","EXT%","X_B","T\\B")
  // Both page plans: reading and sorting the narrowed matches (default threshold) and walking the received-at index (threshold 0).
  for(threshold in listOf(service.narrowPageRows,0L)) for(search in searches) for(state in listOf(null,"ACCEPTED","IN_DOUBT","WAITING_RESOURCE","NEEDS_CONFIRMATION")) for(result in listOf(null,"unacknowledged")) for((offset,limit) in listOf(0 to 100,5 to 7,30 to 20)) {
   service.narrowPageRows=threshold
   val now=service.list(search,state,offset,limit,result)
   @Suppress("UNCHECKED_CAST") val ids=(now["requests"] as List<Receipt>).map{it.receiptId.toString()}
   assertEquals(before(search,state,offset,limit,result),now["total"] as Long to ids,"threshold=$threshold search=$search state=$state result=$result offset=$offset")
   compared++
  }
  service.narrowPageRows=1000L
  assertEquals(2*searches.size*5*2*3,compared)
  assertEquals(3L,service.list("EXT",null,0,100)["total"].let{it as Long}-service.list("EXT-",null,0,100)["total"].let{it as Long})
  assertTrue(before("",null,0,100,"unacknowledged").first>0)
  val pendingBefore=jdbc.sql("""SELECT count(*) FROM ops.work_request r JOIN LATERAL
   (SELECT acknowledged_at FROM ops.work_result WHERE receipt_id=r.receipt_id ORDER BY version DESC LIMIT 1) v ON true
   WHERE r.source_system_id NOT LIKE 'ops:%' AND v.acknowledged_at IS NULL""").query(Long::class.java).single()
  assertEquals(pendingBefore,service.summary()["unacknowledgedResults"])
  assertEquals(before("",null,0,100,"unacknowledged").first,pendingBefore)
 }

 @Test fun `늦게 끝난 이전 호스트 조회는 최신 관측과 같은 작업 버전의 새 결과를 되돌리지 않는다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId,1,"PARTIAL")
  val old=(bench.executions as HostCall.Ok).value
  val started=CountDownLatch(1);val finish=CountDownLatch(1)
  val slow=object:dev.picasso.ops.service.host.HostReads {
   override fun eligibility(jobOrder:com.fasterxml.jackson.databind.node.ObjectNode,robotIds:List<String>)=bench.host.eligibility(jobOrder,robotIds)
   override fun cell()=bench.host.cell()
   override fun executions():HostCall<com.fasterxml.jackson.databind.JsonNode> {started.countDown();check(finish.await(20,TimeUnit.SECONDS));return HostCall.Ok(old)}
  }
  val delayed=WorkRequests(jdbc,interfaces,operations,slow,TransactionTemplate(DataSourceTransactionManager(ds)))
  Executors.newSingleThreadExecutor().use {pool ->
   val pending=pool.submit<Map<String,Any?>>{delayed.detail(row.receiptId)}
   assertTrue(started.await(20,TimeUnit.SECONDS))
   observeResult(row.jobOrderId,1)
   val current=(bench.executions as HostCall.Ok).value
   (current["executions"][0]["jobResponse"] as com.fasterxml.jackson.databind.node.ObjectNode).put("jobResponseId","new-response-same-job-version")
   try {service.collectResults()}finally{finish.countDown()}
   pending.get(20,TimeUnit.SECONDS)
  }
  assertEquals("PHYSICALLY_DONE",service.get(row.receiptId).lastExecution!!["physicalState"].asText())
  assertEquals("PHYSICALLY_DONE",(service.result(row.receiptId,"mes",1)["snapshot"] as com.fasterxml.jackson.databind.JsonNode)["jobResponse"]["physicalState"].asText())
 }
 @Test fun `동시 재평가는 한 번만 제출한다`() {
  bench.judge={ids->HostCall.Ok(ids.map{id->JobOrderBench.running(id,"busy")})}
  val row=service.receive("mes",body).receipt
  val request=json.createObjectNode().put("requestId",java.util.UUID.randomUUID().toString()).put("reason","동시 재평가").put("expectedUpdatedAt",row.updatedAt)
  bench.judge={ids->HostCall.Ok(ids.map(JobOrderBench::pass))}
  val gate=CountDownLatch(1)
  Executors.newFixedThreadPool(6).use{pool ->
   val jobs=(1..6).map{pool.submit<ReceiptAccepted>{gate.await();service().recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),request)}}
   gate.countDown();val replies=jobs.map{it.get(20,TimeUnit.SECONDS)}
   assertEquals(1,replies.count{!it.duplicate})
  }
  assertEquals(1,bench.submitted.size)
 }
 private fun waiting(locktype:String)=jdbc.sql("SELECT count(*) FROM pg_locks WHERE locktype=:type AND NOT granted").param("type",locktype).query(Long::class.java).single()>0
 private fun await(what:String,done:()->Boolean) {val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!done()) {check(System.nanoTime()<until){"$what 대기 시간 초과"};Thread.sleep(20)}}
 @Test fun `재평가 승인과 커밋 사이의 자원 요구 등록은 재평가 커밋을 기다리고 접수를 제출 중에 남기지 않는다`() {
  jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES('inspection-1','공유 검사','ready','complete',30,'engineer/lee')").update()
  bench.judge={ids->HostCall.Ok(ids.map{id->JobOrderBench.running(id,"busy")})}
  val row=service.receive("mes",body).receipt;assertEquals("UNASSIGNED",row.state)
  val attempt=UUID.randomUUID()
  val request=json.createObjectNode().put("requestId",attempt.toString()).put("reason","경합 재평가").put("expectedUpdatedAt",row.updatedAt)
  bench.judge={ids->HostCall.Ok(ids.map(JobOrderBench::pass))}
  // Holds recover after its authorize, before its commit: an uncommitted row with the same attempt key makes its INSERT wait.
  // The row points at another receipt, so recover's own row lock and the mission's in-flight count are untouched.
  val blocker=PostgresSupport.connection().apply{autoCommit=false}
  try {
   blocker.prepareStatement("INSERT INTO ops.work_request_recovery(attempt_id,receipt_id,actor,reason,expected_updated_at) VALUES(?,?,'test','hold','hold')").use{
    it.setObject(1,attempt);it.setObject(2,raw("REJECTED","OtherMission"));it.executeUpdate()}
   Executors.newFixedThreadPool(2).use{pool ->
    val recovered=pool.submit<ReceiptAccepted>{service().recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),request)}
    await("재평가 정지"){waiting("transactionid")}
    val created=pool.submit<Map<String,Any?>>{service().resources.create(Actor(Mode.ENGINEER,"lee"),"InspectAsset","inspection-1","재평가 중 등록")}
    await("등록 대기"){created.isDone || waiting("advisory")}
    val waited=!created.isDone
    blocker.rollback()
    val reply=recovered.get(20,TimeUnit.SECONDS)
    val refused=assertFailsWith<java.util.concurrent.ExecutionException>{created.get(20,TimeUnit.SECONDS)}.cause as RequestRejected
    assertTrue(waited,"자원 요구 등록이 재평가 커밋을 기다리지 않았습니다")
    assertEquals("RESOURCE_REQUIREMENT",refused.code);assertEquals(409,refused.status)
    assertFalse(reply.duplicate);assertEquals("ACCEPTED",reply.receipt.state)
   }
  } finally {blocker.close()}
  assertNull(service.resources.required("InspectAsset"));assertEquals(1,bench.submitted.size)
  assertEquals("ACCEPTED",service.get(row.receiptId).state)
 }
 /** Commits a requirement together with the DISPATCHING claim, past recover's own check: only dispatch()'s authorize sees it. */
 private fun requirementOnClaim(sweep:Boolean) {
  PostgresSupport.execute("""CREATE FUNCTION ops.test_requirement() RETURNS trigger AS $$ BEGIN
   IF NEW.state='DISPATCHING' AND OLD.state<>'DISPATCHING' THEN
    INSERT INTO ops.mission_resource_requirement(work_master_id,station_id,created_by,reason) VALUES(NEW.normalized_order->>'workMasterId','inspection-1','engineer/lee','제출 직전 등록');
    ${if(sweep) "NEW.state:='IN_DOUBT';NEW.detail:='swept';" else ""}
   END IF; RETURN NEW; END $$ LANGUAGE plpgsql""")
  PostgresSupport.execute("CREATE TRIGGER test_requirement BEFORE UPDATE ON ops.work_request FOR EACH ROW EXECUTE FUNCTION ops.test_requirement()")
 }
 private fun unassignedBeforeRequirement(sweep:Boolean):Receipt {
  jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES('inspection-1','공유 검사','ready','complete',30,'engineer/lee')").update()
  bench.judge={ids->HostCall.Ok(ids.map{id->JobOrderBench.running(id,"busy")})}
  val row=service.receive("mes",body).receipt;assertEquals("UNASSIGNED",row.state)
  requirementOnClaim(sweep);bench.judge={ids->HostCall.Ok(ids.map(JobOrderBench::pass))}
  val ex=assertFailsWith<RequestRejected>{service.recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),json.readTree("""{"requestId":"${UUID.randomUUID()}","reason":"제출 직전 변경","expectedUpdatedAt":"${row.updatedAt}"}"""))}
  assertEquals("RESOURCE_REQUIREMENT",ex.code);assertEquals("필수 자원 대기열을 거치지 않은 실행입니다.",ex.message);assertTrue(bench.submitted.isEmpty())
  return service.get(row.receiptId)
 }
 @Test fun `제출 직전 자원 요구가 바뀌면 호스트에 보내지 않고 거절로 닫으며 오류를 알린다`() {
  val after=unassignedBeforeRequirement(sweep=false)
  assertEquals("REJECTED",after.state);assertEquals("자원 요구가 바뀌어 호스트에 보내지 않았습니다. 새 요청 ID로 다시 보내십시오: 필수 자원 대기열을 거치지 않은 실행입니다.",after.detail)
  assertNull(after.submission)
 }
 @Test fun `제출 직전 자원 거절은 이미 다른 판단으로 닫힌 접수를 덮지 않는다`() {
  val after=unassignedBeforeRequirement(sweep=true)
  assertEquals("IN_DOUBT",after.state);assertEquals("swept",after.detail)
 }

 /**
  * A service whose host call is still in flight when observation promotes the same receipt to ACCEPTED: [answer] runs after that
  * promotion, with the host silent again, and its outcome is what the dispatch then writes.
  */
 private fun promotedBeforeAnswer(answer:()->HostWrite):WorkRequests {
  lateinit var late:WorkRequests
  val writes=dev.picasso.ops.service.host.HostWrites {order,_ ->
   val job=order["jobOrderId"].asText()
   val id=jdbc.sql("SELECT receipt_id FROM ops.work_request WHERE job_order_id=:job").param("job",job).query(UUID::class.java).single()
   bench.executions=bench.executionsWith(job)
   assertEquals("ACCEPTED",(late.detail(id)["receipt"] as Receipt).state)
   bench.executions=HostCall.Silent("offline")
   answer()
  }
  late=WorkRequests(jdbc,interfaces,JobOrderOperations(bench.eligibility,writes,bench.host,log,bench.clock,requeryDelay=Duration.ZERO),bench.host,TransactionTemplate(DataSourceTransactionManager(ds)))
  return late
 }
 @Test fun `관측이 먼저 수락으로 올린 접수는 늦게 온 응답 없음이 결과 불명으로 덮지 않고 제출 기록은 남긴다`() {
  val logger=org.slf4j.LoggerFactory.getLogger(WorkRequests::class.java) as ch.qos.logback.classic.Logger
  val lines=ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply{start()}
  logger.addAppender(lines)
  val row=try {promotedBeforeAnswer{HostWrite.NoResponse("timeout")}.receive("mes",body).receipt} finally {logger.detachAppender(lines)}
  assertEquals("ACCEPTED",row.state);assertNotNull(row.lastExecution)
  assertEquals("NO_RESPONSE",row.submission!!["result"].asText())
  val kept=lines.list.single{it.level==ch.qos.logback.classic.Level.WARN}.formattedMessage
  assertContains(kept,row.receiptId.toString());assertContains(kept,"ACCEPTED");assertContains(kept,"IN_DOUBT")
 }
 @Test fun `관측이 먼저 수락으로 올린 접수는 늦게 온 호스트 거부로 닫지 않고 거부 사유 대신 제출 기록만 남긴다`() {
  fun order(n:Int)=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-L$n")
  val refused=promotedBeforeAnswer{HostWrite.Answered(200,rejectedBy("늦은 거부"))}.receive("mes",order(1)).receipt
  assertEquals("ACCEPTED",refused.state);assertFalse(refused.detail.orEmpty().contains("늦은 거부"))
  assertEquals("REJECTED",refused.submission!!["outcome"]["result"].asText())
  val http=promotedBeforeAnswer{HostWrite.Answered(409,"""{"error":"BUSY","detail":"늦은 4xx"}""")}.receive("mes",order(2)).receipt
  assertEquals("ACCEPTED",http.state);assertEquals("REJECTED",http.submission!!["outcome"]["result"].asText())
  val unassigned=promotedBeforeAnswer{HostWrite.Answered(200,unassignedBy(refusal("humanoid-01")))}.receive("mes",order(3)).receipt
  assertEquals("ACCEPTED",unassigned.state);assertEquals("UNASSIGNED",unassigned.submission!!["outcome"]["result"].asText())
 }
 @Test fun `관측이 먼저 수락으로 올린 접수는 제출 호출이 늦게 예외로 끝나도 결과 불명으로 덮지 않는다`() {
  val row=promotedBeforeAnswer{throw IllegalStateException("socket closed")}.receive("mes",body).receipt
  assertEquals("ACCEPTED",row.state);assertNotNull(row.lastExecution)
  assertNull(row.submission);assertFalse(row.detail.orEmpty().contains("확정할 수 없습니다"))
 }
 @Test fun `배정 가능한 기체가 없다는 판정이 나오는 사이 관측이 수락으로 올린 접수는 미배정으로 덮지 않는다`() {
  var promoted=false
  bench.judge={ids->
   jdbc.sql("SELECT receipt_id,job_order_id FROM ops.work_request WHERE state='DISPATCHING'").query{rs,_->rs.getObject(1,UUID::class.java) to rs.getString(2)}.optional().orElse(null)?.let{(id,job)->
    promoted=true;bench.executions=bench.executionsWith(job);service.detail(id);bench.executions=HostCall.Silent("offline")}
   HostCall.Ok(ids.map{JobOrderBench.running(it,"busy")})
  }
  val row=service.receive("mes",body).receipt
  assertTrue(promoted);assertEquals("ACCEPTED",row.state);assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `실행 관측이 남은 접수는 제출이 거부되어도 거부로 닫지 않는다`() {
  val writes=dev.picasso.ops.service.host.HostWrites {order,_ ->
   jdbc.sql("UPDATE ops.work_request SET last_execution='{}'::jsonb WHERE job_order_id=:job").param("job",order["jobOrderId"].asText()).update()
   HostWrite.Answered(200,rejectedBy("관측 뒤 거부"))
  }
  val late=WorkRequests(jdbc,interfaces,JobOrderOperations(bench.eligibility,writes,bench.host,log,bench.clock,requeryDelay=Duration.ZERO),bench.host,TransactionTemplate(DataSourceTransactionManager(ds)))
  val row=late.receive("mes",body).receipt
  assertEquals("DISPATCHING",row.state);assertEquals("REJECTED",row.submission!!["outcome"]["result"].asText())
 }
 @Test fun `관측이 없었으면 제출 예외는 결과 불명으로 거부는 거부로 닫는다`() {
  val thrown=dev.picasso.ops.service.host.HostWrites {_,_ -> throw IllegalStateException("socket closed")}
  val row=WorkRequests(jdbc,interfaces,JobOrderOperations(bench.eligibility,thrown,bench.host,log,bench.clock,requeryDelay=Duration.ZERO),bench.host,TransactionTemplate(DataSourceTransactionManager(ds))).receive("mes",body).receipt
  assertEquals("IN_DOUBT",row.state);assertEquals("제출 결과를 확정할 수 없습니다. 실행 이력을 확인하세요",row.detail)
  bench.answer=HostWrite.Answered(200,rejectedBy("정상 거부"))
  val refused=service.receive("mes",body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-2")).receipt
  assertEquals("REJECTED",refused.state);assertContains(refused.detail!!,"정상 거부")
 }
 @Test fun `스테이션 작업은 관측이 먼저 수락으로 올렸으면 늦은 응답 없음에도 실행 중으로 가고 점유를 유지한다`() {
  resource();ready(true)
  val c=dev.picasso.ops.service.stations.StationCoordinator(jdbc,TransactionTemplate(DataSourceTransactionManager(ds)),
   dev.picasso.ops.service.stations.OpsStationPort(promotedBeforeAnswer{HostWrite.NoResponse("timeout")},interfaces,bench.host))
  val a=manual().receipt;c.tick()
  assertEquals("ACCEPTED",service.get(a.receiptId).state);assertEquals("RUNNING",c.get(a.receiptId).state)
  assertNotNull(c.stations().single().owner);assertNull(c.stations().single().quarantinedBy)
 }
 @Test fun `수집 주기가 먼저 수락으로 올린 접수도 늦게 온 호스트 거부로 닫지 않고 그 뒤 첫 실행 관측은 갱신 시각을 올린다`() {
  lateinit var promoted:Receipt
  val writes=dev.picasso.ops.service.host.HostWrites {order,_ ->
   val job=order["jobOrderId"].asText()
   val collector=durable{HostCall.Ok(feed(job))}
   assertEquals(2,collector.collectResults())
   bench.executions=HostCall.Silent("offline")
   promoted=service.get(UUID.fromString(job.removePrefix("JO-")))
   HostWrite.Answered(200,rejectedBy("늦은 거부"))
  }
  val late=WorkRequests(jdbc,interfaces,JobOrderOperations(bench.eligibility,writes,bench.host,log,bench.clock,requeryDelay=Duration.ZERO),bench.host,TransactionTemplate(DataSourceTransactionManager(ds)))
  val row=late.receive("mes",body).receipt
  assertEquals("ACCEPTED",promoted.state);assertNull(promoted.lastExecution)
  assertEquals("ACCEPTED",row.state);assertEquals("REJECTED",row.submission!!["outcome"]["result"].asText())
  observeResult(row.jobOrderId,state="RUNNING");service.detail(row.receiptId)
  assertNotEquals(row.updatedAt,service.get(row.receiptId).updatedAt)
 }
 @Test fun `다른 판단으로 이미 닫힌 접수는 늦게 온 수락 응답이 다시 열지 않는다`() {
  val writes=dev.picasso.ops.service.host.HostWrites {order,_ ->
   jdbc.sql("UPDATE ops.work_request SET state='REJECTED',detail='다른 판단' WHERE job_order_id=:job").param("job",order["jobOrderId"].asText()).update()
   HostWrite.Answered(200,JobOrderBench.accepted("humanoid-01"))
  }
  val late=WorkRequests(jdbc,interfaces,JobOrderOperations(bench.eligibility,writes,bench.host,log,bench.clock,requeryDelay=Duration.ZERO),bench.host,TransactionTemplate(DataSourceTransactionManager(ds)))
  val row=late.receive("mes",body).receipt
  assertEquals("REJECTED",row.state);assertEquals("다른 판단",row.detail);assertEquals("ACCEPTED",row.submission!!["outcome"]["result"].asText())
 }
 /** A running work request observed once, so its execution and ACCEPTED are recorded. */
 private fun observedOnce():Receipt {
  val row=service.receive("mes",body).receipt
  observeResult(row.jobOrderId,state="RUNNING");service.detail(row.receiptId)
  return service.get(row.receiptId)
 }
 private fun review(id:UUID,expected:String)=service.review(id,Actor(Mode.OPERATOR,"kim"),json.valueToTree(mapOf("requestId" to UUID.randomUUID().toString(),"observation" to "IN_PROGRESS","reason" to "현장 확인","expectedUpdatedAt" to expected)))
 @Test fun `같은 실행을 다시 관측해도 바뀐 것이 없으면 갱신 시각은 그대로이고 먼저 읽은 갱신 시각으로 현장 확인할 수 있다`() {
  val first=observedOnce();assertNotNull(first.executionObservedAt)
  val polled=first.updatedAt
  observeResult(first.jobOrderId,state="RUNNING");service.detail(first.receiptId)
  val again=service.get(first.receiptId)
  assertNotEquals(first.executionObservedAt,again.executionObservedAt)
  assertEquals(polled,again.updatedAt);assertEquals(first.lastExecution,again.lastExecution)
  assertEquals(false,review(first.receiptId,polled)["duplicate"])
 }
 @Test fun `상태를 올리는 관측은 실행이 그대로여도 갱신 시각을 올린다`() {
  val first=observedOnce()
  jdbc.sql("UPDATE ops.work_request SET state='IN_DOUBT' WHERE receipt_id=:id").param("id",first.receiptId).update()
  observeResult(first.jobOrderId,state="RUNNING");service.detail(first.receiptId)
  val after=service.get(first.receiptId)
  assertEquals("ACCEPTED",after.state);assertNotEquals(first.updatedAt,after.updatedAt);assertEquals(first.lastExecution,after.lastExecution)
 }
 @Test fun `관측이 바꾼 상태와 작업 응답과 인스턴스는 갱신 시각을 올려 먼저 읽은 값으로는 현장 확인이 낡았다고 막힌다`() {
  val row=observedOnce()
  fun changes(how:()->Unit) {
   val before=service.get(row.receiptId).updatedAt;how();service.detail(row.receiptId)
   assertNotEquals(before,service.get(row.receiptId).updatedAt)
   assertEquals("STALE_REVIEW",assertFailsWith<RequestRejected>{review(row.receiptId,before)}.code)
  }
  changes{observeResult(row.jobOrderId,version=2,state="RUNNING")}
  changes{observeResult(row.jobOrderId,version=2,state="PHYSICALLY_DONE")}
  changes{bench.executions=HostCall.Ok(json.readTree((bench.executions as HostCall.Ok).value.toString().replace("\"mw-1\"","\"mw-2\"")))}
 }
 @Test fun `허가 차단은 근거 관측 시각만 새로워지면 그대로이고 막는 까닭이 바뀌면 갱신 시각이 올라간다`() {
  val row=service.receive("mes",body).receipt
  fun block(observedAt:String,subject:String)=""","permitBlock":{"unitId":"T1.travel","withheld":"START","robots":[{"robotId":"humanoid-01","permit":"INHIBITED","reasons":[{"source":"cell/guard_closed","cause":"REPORTED","subject":"$subject","resumeBasis":"b","observedAt":"$observedAt"}]}],"since":"2026-10-14T00:00:00Z"}"""
  fun observe(extra:String){bench.executions=HostCall.Ok(json.readTree("""{"instanceId":"mw-1","executions":[${execution("exec-1",row.jobOrderId,extra)}]}"""));service.detail(row.receiptId)}
  observe(block("2026-10-14T00:00:01Z","guard_closed=false"));val first=service.get(row.receiptId)
  observe(block("2026-10-14T00:00:09Z","guard_closed=false"));val refreshed=service.get(row.receiptId)
  assertEquals(first.updatedAt,refreshed.updatedAt);assertNotEquals(first.executionObservedAt,refreshed.executionObservedAt)
  assertEquals("2026-10-14T00:00:09Z",refreshed.lastExecution!!["permitBlock"]["robots"][0]["reasons"][0]["observedAt"].asText())
  observe(block("2026-10-14T00:00:10Z","door_open=true"))
  assertNotEquals(refreshed.updatedAt,service.get(row.receiptId).updatedAt)
 }

 private fun durable(feed:()->HostCall<com.fasterxml.jackson.databind.JsonNode>)=WorkRequests(jdbc,interfaces,operations,
  object:dev.picasso.ops.service.host.HostReads by bench.host {override fun resultEvents(after:Long,limit:Int)=feed()},TransactionTemplate(DataSourceTransactionManager(ds)),durableResults=true)
 private fun feed(job:String,producer:String=PRODUCER,count:Int=2):com.fasterxml.jackson.databind.JsonNode {
  observeResult(job)
  val snapshot=(bench.executions as HostCall.Ok).value["executions"][0].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("instanceId","mw-1")
  val events=(1..count).map{n->val copy=snapshot.deepCopy();(copy["jobResponse"] as com.fasterxml.jackson.databind.node.ObjectNode).put("jobResponseId","r$n");mapOf("sequence" to n,"snapshot" to copy)}
  return json.valueToTree(mapOf("producerId" to producer,"highWatermark" to count,"events" to events))
 }
 @Test fun `durable cursor resumes after restart and never lets detail reorder backlog`() {
  val row=service.receive("mes",body).receipt
  val page=feed(row.jobOrderId)
  var call:HostCall<com.fasterxml.jackson.databind.JsonNode> = HostCall.Ok(page)
  val worker=durable{call}
  worker.detail(row.receiptId)
  assertEquals(0L,jdbc.sql("SELECT count(*) FROM ops.work_result").query(Long::class.java).single())
  assertEquals(2,worker.collectResults());assertEquals(2,worker.result(row.receiptId,"mes")["version"])
  call=HostCall.Silent("offline")
  assertFailsWith<IllegalStateException>{worker.collectResults()}
  assertEquals(2L,worker.cursorStatus()["sequence"])
  val empty=page.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>();empty.putArray("events");call=HostCall.Ok(empty)
  val restarted=durable{call};assertEquals(0,restarted.collectResults())
  assertEquals(2L,jdbc.sql("SELECT count(*) FROM ops.work_result").query(Long::class.java).single())
  empty.put("producerId",java.util.UUID.randomUUID().toString())
  assertFailsWith<IllegalStateException>{restarted.collectResults()}
  assertEquals(PRODUCER,restarted.cursorStatus()["producerId"])
 }
 @Test fun `invalid event and database failure roll back the whole page and cursor`() {
  val row=service.receive("mes",body).receipt;val page=feed(row.jobOrderId)
  val worker=durable{HostCall.Ok(page)}
  (page["events"][1]["snapshot"]["jobResponse"] as com.fasterxml.jackson.databind.node.ObjectNode).put("requiredEvidence","bad")
  assertFailsWith<IllegalStateException>{worker.collectResults()};assertEquals(0L,worker.cursorStatus()["sequence"])
  (page["events"][1]["snapshot"]["jobResponse"] as com.fasterxml.jackson.databind.node.ObjectNode).put("requiredEvidence","E2")
  jdbc.sql("ALTER TABLE ops.work_result ADD CONSTRAINT refuse_second CHECK(response_id<>'r2')").update()
  assertFailsWith<org.springframework.dao.DataAccessException>{worker.collectResults()}
  assertEquals(0L,worker.cursorStatus()["sequence"])
  assertEquals(0L,jdbc.sql("SELECT count(*) FROM ops.work_result").query(Long::class.java).single())
  jdbc.sql("ALTER TABLE ops.work_result DROP CONSTRAINT refuse_second").update()
  assertEquals(2,worker.collectResults())
 }
 /**
  * B2: a host UNASSIGNED or REJECTED whose job order the host later runs (a remedy approved on a gate-trial robot) moves to ACCEPTED
  * when the host reports the execution, through the execution read and the result feed alike. The earlier answer stays in submission,
  * detail becomes the observed fact, and observing it again adds no result version.
  */
 @Test fun `실행 읽기가 미배정과 거부로 끝난 작업 요청의 실행을 보면 수락으로 올리고 제출 기록은 남기며 다시 봐도 결과를 더하지 않는다`() {
  fun order(n:Int)=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-B2-$n")
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01")))
  val unassigned=service.receive("mes",order(1)).receipt;assertEquals("UNASSIGNED",unassigned.state)
  bench.answer=HostWrite.Answered(200,rejectedBy("관문이 막았다"))
  val rejected=service.receive("mes",order(2)).receipt;assertEquals("REJECTED",rejected.state)
  for((row,answer) in listOf(unassigned to "미배정",rejected to "거부")) {
   observeResult(row.jobOrderId,state="RUNNING");service.detail(row.receiptId)
   val after=service.get(row.receiptId)
   assertEquals("ACCEPTED",after.state)
   assertEquals(row.submission,after.submission)
   assertEquals("실행 호스트가 이 작업 지시의 실행 exec-1(humanoid-01)을 알려 수락으로 바꿨습니다. 앞선 $answer 답은 제출 기록에 남습니다",after.detail)
   service.detail(row.receiptId)
   assertEquals(after.detail,service.get(row.receiptId).detail)
   assertEquals(1L,jdbc.sql("SELECT count(*) FROM ops.work_result WHERE receipt_id=:id").param("id",row.receiptId).query(Long::class.java).single())
  }
 }
 @Test fun `결과 피드가 미배정으로 끝난 작업 요청의 실행을 알리면 수락으로 올리고 제출 기록은 남긴다`() {
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01")))
  val row=service.receive("mes",body).receipt;assertEquals("UNASSIGNED",row.state)
  val page=feed(row.jobOrderId)
  bench.executions=HostCall.Silent("offline")
  assertEquals(2,durable{HostCall.Ok(page)}.collectResults())
  val after=service.get(row.receiptId)
  assertEquals("ACCEPTED",after.state);assertEquals(row.submission,after.submission)
  assertEquals("실행 호스트가 이 작업 지시의 실행 exec-1(humanoid-01)을 알려 수락으로 바꿨습니다. 앞선 미배정 답은 제출 기록에 남습니다",after.detail)
 }
 @Test fun `concurrent durable collectors commit one set of versions`() {
  val row=service.receive("mes",body).receipt;val page=feed(row.jobOrderId)
  val gate=CountDownLatch(2)
  val worker=durable{gate.countDown();gate.await(10,TimeUnit.SECONDS);HostCall.Ok(page)}
  Executors.newFixedThreadPool(2).use{pool->(1..2).map{pool.submit<Int>{worker.collectResults()}}.forEach{it.get(20,TimeUnit.SECONDS)}}
  assertEquals(2L,worker.cursorStatus()["sequence"])
  assertEquals(2L,jdbc.sql("SELECT count(*) FROM ops.work_result").query(Long::class.java).single())
 }

 @Test fun `operator review is immutable idempotent fenced and never submits or completes work`() {
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")
  val row=service.receive("mes",body).receipt;val actor=Actor(Mode.OPERATOR,"kim")
  val review=json.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(mapOf("requestId" to UUID.randomUUID().toString(),"observation" to "NOT_STARTED","reason" to "현장 담당자 김 확인","expectedUpdatedAt" to row.updatedAt))
  assertEquals(false,service.review(row.receiptId,actor,review)["duplicate"])
  assertEquals(true,service().review(row.receiptId,actor,review)["duplicate"])
  assertEquals("IN_DOUBT",service.get(row.receiptId).state);assertEquals(1,bench.submitted.size)
  assertEquals(1,(service.detail(row.receiptId)["reviews"] as List<*>).size)
  review.put("reason","different")
  assertEquals("REVIEW_CONFLICT",assertFailsWith<RequestRejected>{service.review(row.receiptId,actor,review)}.code)
  review.put("requestId",UUID.randomUUID().toString()).put("expectedUpdatedAt","old")
  assertEquals("STALE_REVIEW",assertFailsWith<RequestRejected>{service.review(row.receiptId,actor,review)}.code)
  assertEquals(403,assertFailsWith<RequestRejected>{service.review(row.receiptId,Actor(Mode.ENGINEER,"kim"),review)}.status)
 }
 private companion object {const val PRODUCER="43b09c24-1e61-4975-a56f-e789271f2080"}
 private fun resource() {
  jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES('inspection-1','공유 검사','ready','complete',30,'engineer/lee')").update()
  service.resources.create(Actor(Mode.ENGINEER,"lee"),"InspectAsset","inspection-1","공통 검사 자원")
 }
 private fun ready(value:Boolean,complete:Boolean=false) {bench.cell=HostCall.Ok(json.readTree("""{"cell":{"signals":[{"name":"ready","kind":"BOOLEAN","safety":false,"value":"$value","observedAt":"${java.time.Instant.now().plusSeconds(1)}"},{"name":"complete","kind":"BOOLEAN","safety":false,"value":"$complete","observedAt":"${java.time.Instant.now().plusSeconds(1)}"}]}}"""))}
 private fun coordinator()=dev.picasso.ops.service.stations.StationCoordinator(jdbc,TransactionTemplate(DataSourceTransactionManager(ds)),dev.picasso.ops.service.stations.OpsStationPort(service,interfaces,bench.host))
 private fun manual(id:UUID=UUID.randomUUID())=service.receiveManual(Actor(Mode.OPERATOR,"kim"),json.readTree("""{"requestId":"$id","form":${JobOrderBench.INSPECT_FORM}}"""))
 private fun rejectedBy(reason:String,extra:String="")="""{"result":"REJECTED","executionId":null,"robotId":null,"rejectionReason":"$reason","refusals":[],"excluded":[]$extra}"""
 @Test fun `호스트 거부는 상세를 실제 사유로 바꾸고 구조화된 거부를 제출 기록과 상세 칸에 보존한다`() {
  fun order(n:Int)=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-R$n")
  val reason="출발 자리 SEQ-IN-02.BIN-A 에 ENGINE-COVER-A 이 없다 — 비었다 (설비 관측, 보내지 않았다)"
  val missing=""","sourceMissing":{"material":"ENGINE-COVER-A","source":"SEQ-IN-02.BIN-A","observed":null}"""
  // 빈 목록: 그 자재를 든 다른 자리가 없다.
  bench.answer=HostWrite.Answered(200,rejectedBy(reason,""","remedy":null,"remedyWithheld":false,"alternativeLocations":[]$missing"""))
  val empty=service.receive("mes",order(1)).receipt
  assertEquals("REJECTED",empty.state);assertEquals("호스트 제출 거부: $reason",empty.detail)
  assertTrue(empty.submission!!["outcome"]["alternativeLocations"].let{it.isArray && it.isEmpty},empty.submission.toString())
  val none=service.detail(empty.receiptId)["rejection"] as WorkRequestRejection
  assertEquals(reason,none.reason);assertEquals(emptyList(),none.alternativeLocations);assertEquals("SEQ-IN-02.BIN-A",none.sourceMissing!!.source);assertNull(none.sourceMissing.observed)
  // 널: 셀이 대체 자리 질문에 답하지 않았다. 빈 목록으로 접지 않는다.
  bench.answer=HostWrite.Answered(200,rejectedBy(reason,""","remedy":null,"remedyWithheld":false,"alternativeLocations":null$missing"""))
  val silent=service.detail(service.receive("mes",order(2)).receipt.receiptId)
  assertTrue((silent["receipt"] as Receipt).submission!!["outcome"].let{it.has("alternativeLocations") && it["alternativeLocations"].isNull})
  val unknown=silent["rejection"] as WorkRequestRejection
  assertNull(unknown.alternativeLocations);assertNotNull(unknown.sourceMissing)
  // 대체 자리와 조치 탐색 응답은 그대로 옮긴다.
  bench.answer=HostWrite.Answered(200,rejectedBy("사전 조건이 어긋난다",""","remedy":{"kind":"NONE","cause":"DEPTH_LIMIT","unmet":[]},"remedyWithheld":true,"alternativeLocations":["SEQ-IN-03.BIN-B"]"""))
  val listed=service.detail(service.receive("mes",order(3)).receipt.receiptId)["rejection"] as WorkRequestRejection
  assertEquals(listOf("SEQ-IN-03.BIN-B"),listed.alternativeLocations);assertEquals("NONE",listed.remedy!!["kind"].asText());assertTrue(listed.remedyWithheld);assertNull(listed.sourceMissing)
  // 새 칸이 없던 호스트 본문도 읽고, 거부가 아닌 접수에는 거부 칸이 없다.
  bench.answer=HostWrite.Answered(200,rejectedBy("요구 근거 등급 E2"))
  val old=service.receive("mes",order(4)).receipt;assertEquals("호스트 제출 거부: 요구 근거 등급 E2",old.detail)
  val plain=service.detail(old.receiptId)["rejection"] as WorkRequestRejection
  assertNull(plain.alternativeLocations);assertNull(plain.sourceMissing);assertNull(plain.remedy);assertFalse(plain.remedyWithheld)
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  val accepted=service.receive("mes",order(5)).receipt;assertEquals("ACCEPTED",accepted.state);assertNull(service.detail(accepted.receiptId)["rejection"])
  assertEquals("공통 작업 요청 검증 완료",accepted.detail)
 }
 @Test fun `호스트 미배정은 상세를 기체별 사유로 바꾸고 제출 기록을 보존한다`() {
  fun order(n:Int)=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-U$n")
  bench.answer=HostWrite.Answered(200,"""{"result":"UNASSIGNED","executionId":null,"robotId":null,"rejectionReason":null,
   "refusals":[{"robotId":"humanoid-01","reason":"출발 자리 SEQ-IN-02.BIN-A 에 ENGINE-COVER-A 이 없다 — 비었다"}],
   "excluded":[{"robotId":"quadruped-01","skillFit":"FIT","missingSkills":[],"runningExecutionId":"exec-3","passed":false,"reasons":["진행 중인 실행이 있다: exec-3","실행 일지 저장 실패: 호스트 복구 전 배정 차단"]}]}""")
  val row=service.receive("mes",order(1)).receipt
  assertEquals("UNASSIGNED",row.state)
  assertEquals("호스트 미배정: humanoid-01: 출발 자리 SEQ-IN-02.BIN-A 에 ENGINE-COVER-A 이 없다 — 비었다 / quadruped-01: 진행 중인 실행이 있다: exec-3; 실행 일지 저장 실패: 호스트 복구 전 배정 차단",row.detail)
  assertEquals("UNASSIGNED",row.submission!!["outcome"]["result"].asText());assertEquals(row.detail,(service.detail(row.receiptId)["receipt"] as Receipt).detail)
  assertNull(service.detail(row.receiptId)["rejection"])
  bench.answer=HostWrite.Answered(200,"""{"result":"UNASSIGNED","executionId":null,"robotId":null,"rejectionReason":null,"refusals":[],"excluded":[]}""")
  assertEquals("호스트 미배정: 기체별 사유 없음",service.receive("mes",order(2)).receipt.detail)
 }
 @Test fun `호스트 미배정은 기체별 출발 결품의 대체 자리를 제출 기록과 미배정 칸에 보존하고 널과 빈 목록을 접지 않는다`() {
  fun order(n:Int)=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-S$n")
  val missing=""","sourceMissing":{"material":"ENGINE-COVER-A","source":"SEQ-IN-02.BIN-A","observed":null}"""
  val reason="출발 자리 SEQ-IN-02.BIN-A 에 ENGINE-COVER-A 이 없다 — 비었다 (설비 관측, 보내지 않았다)"
  bench.answer=HostWrite.Answered(200,"""{"result":"UNASSIGNED","executionId":null,"robotId":null,"rejectionReason":null,"refusals":[
   {"robotId":"humanoid-01","reason":"$reason","alternativeLocations":["SEQ-IN-03.BIN-B"]$missing},
   {"robotId":"humanoid-02","reason":"$reason","alternativeLocations":[]$missing},
   {"robotId":"humanoid-03","reason":"$reason","alternativeLocations":null$missing},
   {"robotId":"humanoid-04","reason":"자재 선언과 배정이 어긋난다"}],
   "excluded":[],"remedy":null,"remedyWithheld":false,"alternativeLocations":null,"sourceMissing":null}""")
  val row=service.receive("mes",order(1)).receipt
  assertEquals("UNASSIGNED",row.state)
  // 상세 문장은 그대로 기체별 사유만 잇는다.
  assertEquals("호스트 미배정: humanoid-01: $reason / humanoid-02: $reason / humanoid-03: $reason / humanoid-04: 자재 선언과 배정이 어긋난다",row.detail)
  val stored=row.submission!!["outcome"]["refusals"]
  assertEquals(listOf("SEQ-IN-03.BIN-B"),stored[0]["alternativeLocations"].map{it.asText()},stored.toString())
  assertTrue(stored[1]["alternativeLocations"].let{it.isArray && it.isEmpty},stored.toString())
  assertTrue(stored[2].has("alternativeLocations") && stored[2]["alternativeLocations"].isNull,stored.toString())
  val detail=service.detail(row.receiptId)
  assertNull(detail["rejection"])
  val refusals=(detail["unassigned"] as WorkRequestUnassignment).refusals
  assertEquals(listOf("humanoid-01","humanoid-02","humanoid-03","humanoid-04"),refusals.map{it.robotId})
  assertEquals(listOf("SEQ-IN-03.BIN-B"),refusals[0].alternativeLocations);assertEquals("SEQ-IN-02.BIN-A",refusals[0].sourceMissing!!.source)
  assertEquals("ENGINE-COVER-A",refusals[0].sourceMissing!!.material);assertNull(refusals[0].sourceMissing!!.observed)
  // 빈 목록: 그 자재를 든 다른 자리가 없다. 널: 셀이 답하지 않았다. 둘을 접지 않는다.
  assertEquals(emptyList(),refusals[1].alternativeLocations);assertNotNull(refusals[1].sourceMissing)
  assertNull(refusals[2].alternativeLocations);assertNotNull(refusals[2].sourceMissing)
  // 출발 결품이 아닌 사유와 새 칸이 없던 호스트 본문은 둘 다 널이다.
  assertNull(refusals[3].alternativeLocations);assertNull(refusals[3].sourceMissing)
  // 거부·수락·전송 전 미배정에는 미배정 칸이 없다.
  bench.answer=HostWrite.Answered(200,rejectedBy(reason,""","alternativeLocations":[]$missing"""))
  val rejected=service.receive("mes",order(2)).receipt;assertEquals("REJECTED",rejected.state);assertNull(service.detail(rejected.receiptId)["unassigned"])
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  val accepted=service.receive("mes",order(3)).receipt;assertEquals("ACCEPTED",accepted.state);assertNull(service.detail(accepted.receiptId)["unassigned"])
  bench.judge={ids->HostCall.Ok(ids.map{JobOrderBench.missing(it,"inspect")})}
  val early=service.receive("mes",order(4)).receipt;assertEquals("UNASSIGNED",early.state);assertNull(early.submission)
  assertNull(service.detail(early.receiptId)["unassigned"])
 }
 private val sourceA="SEQ-IN-02.BIN-A"
 private fun unobserved(source:String=sourceA,occupied:String="null",material:String="\"ENGINE-COVER-A\"")=""","sourceUnobserved":{"material":$material,"source":"$source","occupied":$occupied}"""
 private fun unassignedBy(vararg refusals:String)="""{"result":"UNASSIGNED","executionId":null,"robotId":null,"rejectionReason":null,"refusals":[${refusals.joinToString(",")}],"excluded":[],"remedy":null,"remedyWithheld":false,"alternativeLocations":null,"sourceMissing":null,"sourceUnobserved":null}"""
 private fun refusal(robot:String,extra:String="")="""{"robotId":"$robot","reason":"출발 자리 $sourceA 의 ENGINE-COVER-A 를 확인하지 못했다 — 설비가 말이 없다"$extra}"""
 private fun confirmation(receipt:Receipt,source:String=sourceA,identity:String="ENGINE-COVER-A",id:UUID=UUID.randomUUID(),expected:String=receipt.updatedAt)=
  json.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(mapOf("requestId" to id.toString(),"source" to source,"identity" to identity,"reason" to "현장 담당자 김이 출발 자리 바코드 확인","expectedUpdatedAt" to expected))
 @Test fun `호스트 미배정의 출발 미관측은 결품과 따로 제출 기록과 미배정 칸에 남고 확인을 기다리는 출발 자리로 보인다`() {
  fun order(n:Int)=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-O$n")
  val missing=""","sourceMissing":{"material":"ENGINE-COVER-A","source":"SEQ-IN-09.BIN-Z","observed":null},"alternativeLocations":[]"""
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved()),refusal("humanoid-02",unobserved(occupied="true")),
   refusal("humanoid-03",missing),refusal("humanoid-04")))
  val row=service.receive("mes",order(1)).receipt
  assertEquals("UNASSIGNED",row.state)
  val stored=row.submission!!["outcome"]["refusals"]
  assertTrue(stored[0]["sourceUnobserved"]["occupied"].isNull,stored.toString());assertTrue(stored[1]["sourceUnobserved"]["occupied"].asBoolean())
  val detail=service.detail(row.receiptId)
  val refusals=(detail["unassigned"] as WorkRequestUnassignment).refusals
  assertEquals(dev.picasso.ops.service.host.HostSourceUnobserved("ENGINE-COVER-A",sourceA,null),refusals[0].sourceUnobserved)
  assertNull(refusals[0].sourceMissing);assertNull(refusals[0].alternativeLocations)
  assertEquals(true,refusals[1].sourceUnobserved!!.occupied)
  // 결품은 결품 칸에만 있고 출발 미관측 칸은 비었다. 둘을 접지 않는다.
  assertNull(refusals[2].sourceUnobserved);assertEquals("SEQ-IN-09.BIN-Z",refusals[2].sourceMissing!!.source)
  assertNull(refusals[3].sourceUnobserved);assertNull(refusals[3].sourceMissing)
  // 확인을 기다리는 출발 자리: 자리마다 한 줄, 그 자리로 떨어진 기체들. 결품 자리는 없다.
  assertEquals(listOf(PendingSource(sourceA,"ENGINE-COVER-A",null,listOf("humanoid-01","humanoid-02"))),detail["pendingSources"])
  assertEquals(emptyList<Any>(),detail["sourceConfirmations"])
  // REJECTED 의 출발 미관측은 거부 칸에 따로 실리고 확인 대기로 보이지 않는다.
  bench.answer=HostWrite.Answered(200,rejectedBy("출발 자리를 확인하지 못했다",unobserved()))
  val rejected=service.detail(service.receive("mes",order(2)).receipt.receiptId)
  val rejection=rejected["rejection"] as WorkRequestRejection
  assertEquals(sourceA,rejection.sourceUnobserved!!.source);assertNull(rejection.sourceMissing);assertNull(rejection.alternativeLocations)
  assertEquals(emptyList<Any>(),rejected["pendingSources"])
  // 결품만 있는 미배정과 수락은 확인을 기다리지 않는다.
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",missing)))
  assertEquals(emptyList<Any>(),service.detail(service.receive("mes",order(3)).receipt.receiptId)["pendingSources"])
 }
 @Test fun `이미 끝나 놓은 작업 지시라는 호스트 거부는 거부 칸에 그 표시로 남고 다른 거부는 표시가 없다`() {
  fun order(n:Int)=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-F$n")
  bench.answer=HostWrite.Answered(200,rejectedBy("이미 끝나 이빅션한 작업 지시라 다시 받지 않는다: JO-1 · exec-1 · 판 1 · PHYSICALLY_DONE",""","forgottenJobOrder":true"""))
  val forgotten=service.receive("mes",order(1)).receipt
  assertEquals("REJECTED",forgotten.state)
  assertTrue(forgotten.submission!!["outcome"]["forgottenJobOrder"].asBoolean(),forgotten.submission.toString())
  val rejection=service.detail(forgotten.receiptId)["rejection"] as WorkRequestRejection
  assertTrue(rejection.forgottenJobOrder,rejection.toString())
  assertTrue(rejection.reason!!.startsWith("이미 끝나 이빅션한 작업 지시"),rejection.toString())
  // 칸이 없던 호스트 본문과 다른 거부는 거짓이다.
  bench.answer=HostWrite.Answered(200,rejectedBy("요구 근거 등급이 넘는다"))
  assertEquals(false,(service.detail(service.receive("mes",order(2)).receipt.receiptId)["rejection"] as WorkRequestRejection).forgottenJobOrder)
 }
 @Test fun `출발 자리 확인은 사람과 시각과 신원을 덧붙이기로 남기고 같은 작업 지시를 확인과 함께 다시 제출한다`() {
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
  val row=service.receive("mes",body).receipt;assertEquals("UNASSIGNED",row.state)
  assertEquals(listOf(emptyList()),bench.confirmed)
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  val payload=confirmation(row);val kim=Actor(Mode.OPERATOR,"kim")
  val sent=service.confirmSource(row.receiptId,kim,payload)
  assertFalse(sent.duplicate);assertEquals("ACCEPTED",sent.receipt.state)
  assertEquals(2,bench.submitted.size);assertEquals(row.jobOrderId,bench.submitted[1].first["jobOrderId"].asText())
  val carried=bench.confirmed[1].single()
  assertEquals(sourceA,carried.source);assertEquals("ENGINE-COVER-A",carried.identity);assertEquals("operator/kim",carried.confirmedBy)
  java.time.Instant.parse(carried.confirmedAt)
  // 조작 기록의 요청 칸에도 실린다.
  assertTrue(log.list().filter{it.requestId==row.receiptId}.any{json.readTree(it.request).path("sourceConfirmations").size()==1})
  val recorded=(service.detail(row.receiptId)["sourceConfirmations"] as List<*>).single() as Map<*,*>
  assertEquals("operator/kim",recorded["actor"]);assertEquals("ENGINE-COVER-A",recorded["identity"]);assertEquals(sourceA,recorded["source"])
  assertEquals(sourceA,(recorded["context"] as com.fasterxml.jackson.databind.JsonNode)["source"].asText())
  // 같은 요청은 다시 보내지 않고, 같은 ID 의 다른 확인은 충돌이다.
  assertTrue(service().confirmSource(row.receiptId,kim,payload).duplicate);assertEquals(2,bench.submitted.size)
  assertEquals("SOURCE_CONFIRMATION_CONFLICT",assertFailsWith<RequestRejected>{service.confirmSource(row.receiptId,kim,payload.deepCopy().put("identity","ENGINE-COVER-B"))}.code)
  // 덧붙이기만 한다.
  assertFails{jdbc.sql("UPDATE ops.work_request_source_confirmation SET identity='x'").update()}
  assertFails{jdbc.sql("DELETE FROM ops.work_request_source_confirmation").update()}
 }
 @Test fun `출발 미관측이 아닌 자리와 낡은 조회와 운영자가 아닌 행위자의 확인은 기록하지 않고 다시 제출하지 않는다`() {
  fun order(n:Int)=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-N$n")
  val kim=Actor(Mode.OPERATOR,"kim")
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
  val row=service.receive("mes",order(1)).receipt
  assertEquals("SOURCE_NOT_PENDING",assertFailsWith<RequestRejected>{service.confirmSource(row.receiptId,kim,confirmation(row,source="SEQ-IN-03.BIN-B"))}.code)
  assertEquals("STALE_SOURCE_CONFIRMATION",assertFailsWith<RequestRejected>{service.confirmSource(row.receiptId,kim,confirmation(row,expected="old"))}.code)
  assertEquals(403,assertFailsWith<RequestRejected>{service.confirmSource(row.receiptId,Actor(Mode.ENGINEER,"lee"),confirmation(row))}.status)
  assertEquals("BAD_SOURCE_CONFIRMATION",assertFailsWith<RequestRejected>{service.confirmSource(row.receiptId,kim,confirmation(row).put("identity",""))}.code)
  assertEquals("BAD_SOURCE_CONFIRMATION",assertFailsWith<RequestRejected>{service.confirmSource(row.receiptId,kim,confirmation(row).put("extra",1))}.code)
  // 결품(출발 자리가 비었다)은 사람의 확인으로 넘기지 않는다.
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",""","sourceMissing":{"material":"ENGINE-COVER-A","source":"$sourceA","observed":null}""")))
  val short=service.receive("mes",order(2)).receipt
  assertEquals("SOURCE_NOT_PENDING",assertFailsWith<RequestRejected>{service.confirmSource(short.receiptId,kim,confirmation(short))}.code)
  // 전송 전 미배정은 호스트의 답이 없다.
  bench.judge={ids->HostCall.Ok(ids.map{JobOrderBench.missing(it,"inspect")})}
  val early=service.receive("mes",order(3)).receipt;assertEquals("UNASSIGNED",early.state);assertNull(early.submission)
  assertEquals("SOURCE_NOT_PENDING",assertFailsWith<RequestRejected>{service.confirmSource(early.receiptId,kim,confirmation(early))}.code)
  assertEquals(2,bench.submitted.size)
  assertEquals(0L,jdbc.sql("SELECT count(*) FROM ops.work_request_source_confirmation").query(Long::class.java).single())
  assertEquals("UNASSIGNED",service.get(row.receiptId).state)
 }
 private fun recovery(receipt:Receipt)=json.readTree("""{"requestId":"${UUID.randomUUID()}","reason":"기체 복귀","expectedUpdatedAt":"${receipt.updatedAt}"}""")
 @Test fun `확인 뒤 배정 가능한 기체가 없으면 전송 전 미배정이 되어 회차가 닫히고 재평가는 앞 확인을 싣지 않으며 다시 기록한 확인만 싣는다`() {
  val kim=Actor(Mode.OPERATOR,"kim");val lee=Actor(Mode.OPERATOR,"lee")
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
  val row=service.receive("mes",body).receipt
  bench.judge={ids->HostCall.Ok(ids.map{JobOrderBench.running(it,"busy")})}
  val early=service.confirmSource(row.receiptId,kim,confirmation(row)).receipt
  assertEquals("UNASSIGNED",early.state);assertNull(early.submission);assertEquals(1,bench.submitted.size)
  assertEquals(emptyList<Any>(),service.detail(row.receiptId)["pendingSources"])
  // 기체가 돌아와 재평가한다. 앞 확인은 싣지 않으므로 호스트가 다시 출발 미관측으로 답한다.
  bench.judge={ids->HostCall.Ok(ids.map(JobOrderBench::pass))}
  val again=service.recover(row.receiptId,kim,recovery(early)).receipt
  assertEquals(listOf(emptyList(),emptyList()),bench.confirmed)
  assertEquals("UNASSIGNED",again.state)
  // 사람이 그 자리에서 다시 확인하면 그 확인만 실린다.
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  assertEquals("ACCEPTED",service.confirmSource(row.receiptId,lee,confirmation(again)).receipt.state)
  assertEquals(listOf(sourceA to "operator/lee"),bench.confirmed.last().map{it.source to it.confirmedBy})
 }
 @Test fun `여러 출발 자리 연쇄가 전송 전 미배정으로 닫히면 다음 연쇄는 앞 연쇄의 다른 자리 확인을 싣지 않는다`() {
  val kim=Actor(Mode.OPERATOR,"kim");val lee=Actor(Mode.OPERATOR,"lee");val sourceB="SEQ-IN-03.BIN-B"
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
  val row=service.receive("mes",body).receipt
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved(source=sourceB))))
  val next=service.confirmSource(row.receiptId,kim,confirmation(row)).receipt
  bench.judge={ids->HostCall.Ok(ids.map{JobOrderBench.running(it,"busy")})}
  val early=service.confirmSource(row.receiptId,kim,confirmation(next,source=sourceB,identity="ENGINE-COVER-B")).receipt
  assertEquals("UNASSIGNED",early.state);assertNull(early.submission)
  bench.judge={ids->HostCall.Ok(ids.map(JobOrderBench::pass))}
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
  val again=service.recover(row.receiptId,kim,recovery(early)).receipt
  assertEquals(emptyList(),bench.confirmed.last())
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  assertEquals("ACCEPTED",service.confirmSource(row.receiptId,lee,confirmation(again)).receipt.state)
  assertEquals(listOf(sourceA to "operator/lee"),bench.confirmed.last().map{it.source to it.confirmedBy})
 }
 /** 이 작업 요청의 마지막 확인이 다음 제출에 실릴 회차 안에 있는가. */
 private fun roundOpen(id:UUID)=jdbc.sql("""SELECT r.source_confirmations_closed_at IS NULL OR max(c.created_at)>r.source_confirmations_closed_at
  FROM ops.work_request r JOIN ops.work_request_source_confirmation c ON c.receipt_id=r.receipt_id WHERE r.receipt_id=:id GROUP BY r.source_confirmations_closed_at""")
  .param("id",id).query(Boolean::class.java).single()
 @Test fun `확인 뒤 호스트가 출발 미관측이 아닌 답을 하거나 제출 결과가 불명이면 회차를 닫고 출발 미관측으로 답하면 회차를 열어 둔다`() {
  fun order(n:Int)=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-R$n")
  val kim=Actor(Mode.OPERATOR,"kim");val judge=bench.judge;val executions=bench.executions
  val missing=""","sourceMissing":{"material":"ENGINE-COVER-A","source":"SEQ-IN-09.BIN-Z","observed":null},"alternativeLocations":[]"""
  val answers=listOf<Pair<String,()->Unit>>(
   "수락" to {bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))},
   "거부" to {bench.answer=HostWrite.Answered(200,rejectedBy("요구 근거 등급 E2"))},
   "결품만" to {bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",missing)))},
   "응답 없음" to {bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")},
   "제출 중 예외" to {bench.judge={throw IllegalStateException("판정 중단")}},
   "다른 자리 미관측" to {bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved(source="SEQ-IN-03.BIN-B"))))})
  val seen=answers.mapIndexed{n,(name,answer)->
   bench.judge=judge;bench.executions=executions;bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
   val row=service.receive("mes",order(n)).receipt
   answer()
   val after=service.confirmSource(row.receiptId,kim,confirmation(row)).receipt
   name to "${after.state} ${if(roundOpen(row.receiptId)) "열림" else "닫힘"}"
  }.toMap()
  assertEquals(mapOf("수락" to "ACCEPTED 닫힘","거부" to "REJECTED 닫힘","결품만" to "UNASSIGNED 닫힘","응답 없음" to "IN_DOUBT 닫힘",
   "제출 중 예외" to "IN_DOUBT 닫힘","다른 자리 미관측" to "UNASSIGNED 열림"),seen)
 }
 @Test fun `확인 뒤 다른 출발 자리가 남으면 다시 확인 대기이고 다음 제출은 쌓인 확인을 자리마다 하나씩 싣는다`() {
  val kim=Actor(Mode.OPERATOR,"kim");val sourceB="SEQ-IN-03.BIN-B"
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
  val row=service.receive("mes",body).receipt
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved(source=sourceB))))
  val next=service.confirmSource(row.receiptId,kim,confirmation(row)).receipt
  assertEquals("UNASSIGNED",next.state)
  assertEquals(listOf(sourceB),(service.detail(row.receiptId)["pendingSources"] as List<*>).map{(it as PendingSource).source})
  assertEquals("SOURCE_NOT_PENDING",assertFailsWith<RequestRejected>{service.confirmSource(row.receiptId,kim,confirmation(next))}.code)
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  assertEquals("ACCEPTED",service.confirmSource(row.receiptId,kim,confirmation(next,source=sourceB,identity="ENGINE-COVER-B")).receipt.state)
  assertEquals(listOf(sourceA to "ENGINE-COVER-A",sourceB to "ENGINE-COVER-B"),bench.confirmed.last().map{it.source to it.identity}.sortedBy{it.first})
  assertEquals(3,bench.submitted.size)
 }
 @Test fun `자원 관리 작업의 출발 미관측은 자동 재평가 없이 점유를 유지하고 출발 자리 확인으로만 다시 제출한다`() {
  resource();ready(true)
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
  val a=manual().receipt;val c=coordinator();c.tick()
  val held=c.get(a.receiptId)
  assertEquals("REVIEW_REQUIRED",held.state);assertEquals(dev.picasso.ops.service.stations.StationCoordinator.SOURCE_UNOBSERVED,held.reason)
  assertEquals(a.receiptId,c.stations().single().owner);assertEquals(1,bench.submitted.size)
  // 시간이 지나도 다시 내지 않는다.
  jdbc.sql("UPDATE ops.station_run SET phase_at=phase_at-interval '1 hour' WHERE run_id=:id").param("id",a.receiptId).update()
  c.tick();c.tick()
  assertEquals("REVIEW_REQUIRED",c.get(a.receiptId).state);assertEquals(0,c.get(a.receiptId).attempt);assertEquals(1,bench.submitted.size)
  // 관측 재개로 확인을 비켜 가지 않는다.
  val revision=c.get(a.receiptId).revision
  assertEquals(409,assertFailsWith<RequestRejected>{c.decide(a.receiptId,Actor(Mode.OPERATOR,"kim"),json.readTree("""{"expectedRevision":$revision,"decision":"RESUME_OBSERVATION","reason":"관측 재개"}"""))}.status)
  // 일반 작업 요청 경로로는 확인하지 못한다.
  val current=service.get(a.receiptId)
  assertEquals("RESOURCE_MANAGED",assertFailsWith<RequestRejected>{service.confirmSource(a.receiptId,Actor(Mode.OPERATOR,"kim"),confirmation(current))}.code)
  // 설비 완료 신호가 켜져 있으면 다시 내지 않고 상태도 그대로다.
  ready(true,complete=true)
  assertEquals(409,assertFailsWith<RequestRejected>{c.confirmSource(a.receiptId,Actor(Mode.OPERATOR,"kim"),confirmation(current))}.status)
  assertEquals(revision,c.get(a.receiptId).revision)
  // 낡은 조회는 확인을 기록하지 않고 같은 확인 필요로 돌아간다.
  ready(true)
  assertEquals("STALE_SOURCE_CONFIRMATION",assertFailsWith<RequestRejected>{c.confirmSource(a.receiptId,Actor(Mode.OPERATOR,"kim"),confirmation(current,expected="old"))}.code)
  assertEquals("REVIEW_REQUIRED",c.get(a.receiptId).state);assertEquals(dev.picasso.ops.service.stations.StationCoordinator.SOURCE_UNOBSERVED,c.get(a.receiptId).reason)
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  val next=c.confirmSource(a.receiptId,Actor(Mode.OPERATOR,"kim"),confirmation(service.get(a.receiptId)))
  assertEquals("ACCEPTED",next.state);assertEquals("RUNNING",c.get(a.receiptId).state)
  assertEquals(2,bench.submitted.size);assertEquals(sourceA,bench.confirmed[1].single().source)
  assertEquals(a.receiptId,c.stations().single().owner)
  assertTrue(c.events(a.receiptId).any{it["reason"]==dev.picasso.ops.service.stations.StationCoordinator.SOURCE_CONFIRMED && it["actor"]=="operator/kim"})
 }
 @Test fun `자원 관리 작업도 출발 자리 확인 뒤 전송 전 미배정이면 회차가 닫혀 자동 재평가가 앞 확인을 싣지 않고 다시 확인을 기다린다`() {
  resource();ready(true);val kim=Actor(Mode.OPERATOR,"kim")
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
  val a=manual().receipt;val c=coordinator();c.tick()
  assertEquals(dev.picasso.ops.service.stations.StationCoordinator.SOURCE_UNOBSERVED,c.get(a.receiptId).reason)
  bench.judge={ids->HostCall.Ok(ids.map{JobOrderBench.running(it,"busy")})}
  assertEquals("UNASSIGNED",c.confirmSource(a.receiptId,kim,confirmation(service.get(a.receiptId))).state)
  assertEquals("WAITING_ROBOT",c.get(a.receiptId).state)
  bench.judge={ids->HostCall.Ok(ids.map(JobOrderBench::pass))}
  jdbc.sql("UPDATE ops.station_run SET phase_at=phase_at-interval '6 seconds' WHERE run_id=:id").param("id",a.receiptId).update()
  c.tick()
  assertEquals(2,bench.submitted.size);assertEquals(emptyList(),bench.confirmed.last())
  val held=c.get(a.receiptId)
  assertEquals("REVIEW_REQUIRED",held.state);assertEquals(dev.picasso.ops.service.stations.StationCoordinator.SOURCE_UNOBSERVED,held.reason)
 }
 @Test fun `스테이션 포트는 실행 관측과 저장 결과 없이 끝난 미배정과 거부만 보내지 않은 작업 요청으로 본다`() {
  fun order(n:Int)=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-N$n")
  val port=dev.picasso.ops.service.stations.OpsStationPort(service,interfaces,bench.host)
  val judge=bench.judge;bench.judge={ids->HostCall.Ok(ids.map{JobOrderBench.missing(it,"inspect")})}
  val early=service.receive("mes",order(1)).receipt;assertEquals("UNASSIGNED",early.state);assertNull(early.submission)
  bench.judge=judge
  fun answered(n:Int,answer:String)=run{bench.answer=HostWrite.Answered(200,answer);service.receive("mes",order(n)).receipt}
  val unassigned=answered(2,unassignedBy(refusal("humanoid-01")));val rejected=answered(3,rejectedBy("요구 근거 등급 E2"))
  val forgotten=answered(4,rejectedBy("이미 끝나 이빅션한 작업 지시라 다시 받지 않는다",""","forgottenJobOrder":true"""));val accepted=answered(5,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  val observed=answered(6,unassignedBy(refusal("humanoid-01")));val stored=answered(7,unassignedBy(refusal("humanoid-01")))
  jdbc.sql("UPDATE ops.work_request SET last_execution=CAST('{\"executionId\":\"exec-1\"}' AS JSONB) WHERE receipt_id=:id").param("id",observed.receiptId).update()
  jdbc.sql("""INSERT INTO ops.work_result(receipt_id,version,instance_id,execution_id,response_id,response_version,snapshot,result_hash)
   VALUES(:id,1,'mw-1','exec-1','r-1',1,CAST('{"jobResponse":{"physicalState":"FAILED"}}' AS JSONB),'hash')""").param("id",stored.receiptId).update()
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")
  val doubt=service.receive("mes",order(8)).receipt;assertEquals("IN_DOUBT",doubt.state)
  val judged=listOf(early,unassigned,rejected,forgotten,accepted,observed,stored,doubt).map{port.unsent(it.receiptId)}
  assertEquals(listOf(true,true,true,false,false,false,false,false),judged)
  // 운영 DB 조건만으로는 미전송이 아니다(B3). 그 조건을 채운 셋만 실행 호스트에 철회를 물었고 호스트가 확정했다. 다시 물어도 같은 요청 id 다.
  assertEquals(listOf(early,unassigned,rejected).map{it.jobOrderId},bench.withdrawCalls.map{it.first})
  assertTrue(port.unsent(early.receiptId));assertEquals(bench.withdrawCalls[0],bench.withdrawCalls.last())
  // 호스트가 그 작업 지시의 실행을 알리면 미전송이 아니고 작업 요청은 수락이 된다. 답이 없으면 미전송이 아니고 아무것도 바꾸지 않는다.
  bench.withdraw={job,_->if(job==unassigned.jobOrderId) HostWrite.Answered(200,JobOrderBench.executionExistsBody(job,"exec-5")) else HostWrite.NoResponse("timeout")}
  assertEquals(listOf(false,false),listOf(unassigned,rejected).map{port.unsent(it.receiptId)})
  assertEquals("ACCEPTED",service.get(unassigned.receiptId).state);assertEquals("exec-5",service.get(unassigned.receiptId).lastExecution!!["executionId"].asText())
  assertEquals("REJECTED",service.get(rejected.receiptId).state);assertNull(service.get(rejected.receiptId).lastExecution)
 }
 /** B3: 자원 관리 작업의 현장 확인 해제는 실행 호스트의 철회 답 셋을 가른다. 확정만 미전송이고, 실행이 있거나 답이 없으면 격리하며 «실행하지 않습니다» 로 닫지 않는다. */
 @Test fun `현장 확인 해제는 실행 호스트가 철회를 확정할 때만 미전송으로 닫고 실행이 있거나 답이 없으면 스테이션을 격리한다`() {
  resource();ready(true);val kim=Actor(Mode.OPERATOR,"kim");val c=coordinator()
  fun clear(id:UUID)=c.decide(id,kim,json.readTree("""{"expectedRevision":${c.get(id).revision},"decision":"CONFIRM_CLEAR","reason":"현장 확인"}"""))
  fun held():UUID {
   jdbc.sql("UPDATE ops.rf_station SET quarantined_by=NULL,quarantined_at=NULL").update()
   bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
   val id=manual().receipt.receiptId;c.tick()
   assertEquals(dev.picasso.ops.service.stations.StationCoordinator.SOURCE_UNOBSERVED,c.get(id).reason);assertNotNull(c.get(id).dispatchedAt)
   return id
  }
  // 낡은 판(다른 revision)의 해제는 거절되고 철회를 부르지 않는다 — 그 작업 요청은 아직 다시 제출될 수 있다.
  val stale=held()
  assertFailsWith<RequestRejected>{c.decide(stale,kim,json.readTree("""{"expectedRevision":${c.get(stale).revision+1},"decision":"CONFIRM_CLEAR","reason":"현장 확인"}"""))}
  assertTrue(bench.withdrawCalls.isEmpty(),bench.withdrawCalls.toString())
  // WITHDRAWN: 미전송 확정 — 격리 없이 풀고 «실행하지 않습니다» 로 닫는다.
  assertEquals("MANUALLY_CLEARED",clear(stale).state);assertNull(c.stations().single().quarantinedBy);assertNull(c.stations().single().owner)
  assertEquals("REJECTED",service.get(stale).state);assertTrue("이 작업 요청은 실행하지 않습니다" in service.get(stale).detail!!,service.get(stale).detail)
  assertEquals(listOf(service.get(stale).jobOrderId),bench.withdrawCalls.map{it.first})
  // EXECUTION_EXISTS: 값 없는 승인이 실행을 세웠다 — 격리하고 작업 요청은 수락이 된다.
  bench.withdraw={job,_->HostWrite.Answered(200,JobOrderBench.executionExistsBody(job,"exec-6"))}
  val ran=held()
  assertEquals("MANUALLY_CLEARED",clear(ran).state);assertEquals(ran,c.stations().single().quarantinedBy)
  assertEquals("ACCEPTED",service.get(ran).state);assertEquals("exec-6",service.get(ran).lastExecution!!["executionId"].asText())
  // 답 없음: 미전송이 아니다 — 격리하고, 거부로 닫되 «실행하지 않습니다» 라고 하지 않는다.
  bench.withdraw={_,_->HostWrite.NoResponse("timeout")}
  val silent=held()
  assertEquals("MANUALLY_CLEARED",clear(silent).state);assertEquals(silent,c.stations().single().quarantinedBy)
  val closed=service.get(silent)
  assertEquals("REJECTED",closed.state);assertFalse("이 작업 요청은 실행하지 않습니다" in closed.detail!!,closed.detail);assertTrue("철회를 확정하지 않아" in closed.detail!!,closed.detail)
 }
 /**
  * B3: 현장 확인 해제는 본문을 다 본 뒤에만 그 스테이션 작업을 선점(CLEARING)하고 그 뒤에만 철회를 부른다. 400 으로 거절될 요청은 작업 지시를 철회하지 않는다.
  * 선점 동안 다른 판단·출발 자리 확인은 409 이고, 선점 뒤 처리가 실패하면 같은 확인 필요로 되돌린다. 철회는 해제한 운영자와 그 사유로 나간다.
  */
 @Test fun `현장 확인 해제는 본문 검증과 선점 뒤에만 철회를 부르고 선점 중 다른 판단은 409 이며 실패하면 확인 필요로 되돌린다`() {
  resource();ready(true);val kim=Actor(Mode.OPERATOR,"kim");val c=coordinator()
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
  val id=manual().receipt.receiptId;c.tick()
  fun body(extra:String="",reason:String?="\"현장 확인\"")="""{"expectedRevision":${c.get(id).revision},"decision":"CONFIRM_CLEAR"${reason?.let{",\"reason\":$it"} ?: ""}$extra}"""
  // 사유 없음, 사유 초과, 남는 칸, 모르는 판단: 400 이고 실행 호스트에 철회를 묻지 않는다.
  listOf(body(reason=null),body(reason="\"${"x".repeat(1001)}\""),body(reason="\"  \""),body(extra=""","more":1""")).forEach{bad->
   assertEquals(400,assertFailsWith<RequestRejected>(bad){c.decide(id,kim,json.readTree(bad))}.status,bad)
  }
  assertTrue(bench.withdrawCalls.isEmpty(),bench.withdrawCalls.toString());assertEquals("REVIEW_REQUIRED",c.get(id).state)
  // 선점 중: 철회 답을 기다리는 사이 다른 판단과 출발 자리 확인은 409 다(다른 인스턴스도 같은 선점 상태를 본다).
  val during=mutableListOf<Int>()
  bench.withdraw={job,request->
   assertEquals("CLEARING",c.get(id).state)
   during+=assertFailsWith<RequestRejected>{c.decide(id,kim,json.readTree("""{"expectedRevision":${c.get(id).revision},"decision":"RESUME_OBSERVATION","reason":"r"}"""))}.status
   during+=assertFailsWith<RequestRejected>{c.confirmSource(id,kim,confirmation(service.get(id)))}.status
   // 선점 뒤 마무리 쓰기가 실패한다: 해제 상태로 못 가게 막는다.
   jdbc.sql("ALTER TABLE ops.station_run ADD CONSTRAINT b3_no_clear CHECK (state<>'MANUALLY_CLEARED') NOT VALID").update()
   HostWrite.Answered(200,JobOrderBench.withdrawnBody(job,request))
  }
  assertFails{c.decide(id,kim,json.readTree(body(reason="\"현장에서 기체 정지 확인\"")))}
  assertEquals(listOf(409,409),during)
  // 원복: 같은 확인 필요로 돌아가고 점유는 그대로이며 작업 요청은 닫지 않았다.
  assertEquals("REVIEW_REQUIRED",c.get(id).state);assertEquals(dev.picasso.ops.service.stations.StationCoordinator.CLEAR_FAILED,c.get(id).reason)
  assertEquals(id,c.stations().single().owner);assertEquals("UNASSIGNED",service.get(id).state)
  // 철회는 해제한 운영자와 그 사유로 나갔다.
  assertEquals("operator/kim",bench.withdrawAsked.single().first);assertTrue("현장에서 기체 정지 확인" in bench.withdrawAsked.single().second,bench.withdrawAsked.toString())
  jdbc.sql("ALTER TABLE ops.station_run DROP CONSTRAINT b3_no_clear").update()
  // 다시 해제하면 같은 작업 지시의 철회를 다시 묻고(작업 지시 기준 멱등) 해제한다.
  bench.withdraw=null
  assertEquals("MANUALLY_CLEARED",c.decide(id,kim,json.readTree(body())).state);assertNull(c.stations().single().quarantinedBy)
  assertEquals(2,bench.withdrawCalls.size);assertEquals(setOf(service.get(id).jobOrderId),bench.withdrawCalls.map{it.first}.toSet())
 }
 /** B3: 호스트 REJECTED 의 자동 해제도 같은 철회가 유일한 미전송 증거다. 확정이 없으면 점유를 유지하고 사람에게 넘긴다. */
 @Test fun `호스트가 거부한 스테이션 작업은 실행 호스트가 철회를 확정하지 않으면 자원을 풀지 않고 실행이 있으면 작업 요청을 수락으로 올린다`() {
  resource();ready(true);val c=coordinator()
  bench.answer=HostWrite.Answered(200,rejectedBy("요구 근거 등급 E2 은 InspectAsset 의 최고 등급 E0 를 넘는다"))
  fun run(answer:(String)->HostWrite):UUID {
   jdbc.sql("UPDATE ops.rf_station SET owner=NULL").update()
   bench.withdraw={job,_->answer(job)}
   val run=UUID.randomUUID()
   c.enqueue(Actor(Mode.OPERATOR,"kim"),json.readTree("""{"requestId":"$run","stationId":"inspection-1","interfaceId":"asset-check","interfaceVersion":1,"expectedDeploymentVersion":1,"input":${body["input"]}}"""))
   c.tick();c.tick()
   return run
  }
  val silent=run{HostWrite.NoResponse("timeout")}
  assertEquals("REVIEW_REQUIRED",c.get(silent).state);assertEquals(dev.picasso.ops.service.stations.StationCoordinator.REJECTED_UNCONFIRMED,c.get(silent).reason)
  assertEquals(silent,c.stations().single().owner)
  val receipt=checkNotNull(c.get(silent).receiptId);assertEquals("REJECTED",service.get(receipt).state)
  val ran=run{job->HostWrite.Answered(200,JobOrderBench.executionExistsBody(job,"exec-7"))}
  assertEquals("REVIEW_REQUIRED",c.get(ran).state);assertEquals(ran,c.stations().single().owner)
  assertEquals("ACCEPTED",service.get(checkNotNull(c.get(ran).receiptId)).state)
 }
 @Test fun `자원 관리 작업은 호스트가 미배정으로 답했으면 현장 확인 해제가 스테이션을 격리하지 않고 제출 결과가 불명이면 격리한다`() {
  resource();ready(true);val kim=Actor(Mode.OPERATOR,"kim");val c=coordinator()
  fun clear(id:UUID)=c.decide(id,kim,json.readTree("""{"expectedRevision":${c.get(id).revision},"decision":"CONFIRM_CLEAR","reason":"현장 확인"}"""))
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
  val a=manual().receipt;c.tick()
  assertEquals(dev.picasso.ops.service.stations.StationCoordinator.SOURCE_UNOBSERVED,c.get(a.receiptId).reason);assertNotNull(c.get(a.receiptId).dispatchedAt)
  assertEquals("MANUALLY_CLEARED",clear(a.receiptId).state);assertNull(c.stations().single().quarantinedBy);assertNull(c.stations().single().owner)
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")
  val b=manual().receipt;c.tick()
  assertEquals("IN_DOUBT",service.get(b.receiptId).state);assertEquals("REVIEW_REQUIRED",c.get(b.receiptId).state)
  assertEquals("MANUALLY_CLEARED",clear(b.receiptId).state);assertEquals(b.receiptId,c.stations().single().quarantinedBy)
 }
 @Test fun `호스트가 거부한 스테이션 작업은 실제 거부 사유와 자원 해제를 함께 남긴다`() {
  resource();ready(true)
  bench.answer=HostWrite.Answered(200,rejectedBy("요구 근거 등급 E2 은 InspectAsset 의 최고 등급 E0 를 넘는다"))
  val run=UUID.randomUUID();val c=coordinator()
  c.enqueue(Actor(Mode.OPERATOR,"kim"),json.readTree("""{"requestId":"$run","stationId":"inspection-1","interfaceId":"asset-check","interfaceVersion":1,"expectedDeploymentVersion":1,"input":${body["input"]}}"""))
  c.tick();c.tick()
  val closed=c.get(run);assertEquals("REJECTED",closed.state,closed.reason)
  assertEquals("계약 제출 거부: 호스트 제출 거부: 요구 근거 등급 E2 은 InspectAsset 의 최고 등급 E0 를 넘는다; 자원 해제",closed.reason)
  assertNull(c.stations().single().owner);assertEquals(1,bench.submitted.size)
 }
 @Test fun `상위 주문 계약 호출 수동 직접 제출은 하나의 필수 자원 대기열을 사용한다`() {
  resource();ready(false)
  val mes=service.receive("mes",body)
  val invoke=service.receiveInterface(Actor(Mode.OPERATOR,"kim"),"asset-check",1,json.readTree("""{"requestId":"${UUID.randomUUID()}","input":${body["input"]}}"""))
  val local=manual()
  val controller=dev.picasso.ops.service.web.JobOrderController(bench.eligibility,operations,bench.host,service)
  val legacy=controller.submit("operator","kim",JobOrderBench.INSPECT_FORM.toByteArray())
  assertEquals(202,legacy.statusCode.value())
  for(r in listOf(mes,invoke,local)) {assertEquals("WAITING_RESOURCE",r.receipt.state);assertEquals("inspection-1",r.receipt.requiredStationId)}
  assertEquals(4,coordinator().runs().size);assertTrue(bench.submitted.isEmpty())
  coordinator().tick();assertEquals(1,coordinator().runs().count{it.state=="WAITING_READY"})
  assertTrue(service.receive("mes",body).duplicate);assertEquals(4,coordinator().runs().size)
 }
 @Test fun `자원 준비 후 원래 접수와 고정 변환으로 한 번만 실행한다`() {
  resource();ready(false);val receipt=service.receive("mes",body).receipt;val second=manual().receipt
  val c=coordinator();c.tick();assertTrue(bench.submitted.isEmpty())
  interfaces.state("asset-check",1,"ENABLED","DISABLED","대기 중 공개 중단",Actor(Mode.ENGINEER,"lee"))
  interfaces.deploy("asset-check",1,"7",1,"대기 중 배포 변경",Actor(Mode.ENGINEER,"lee"))
  ready(true);c.tick();coordinator().tick()
  assertEquals(1,bench.submitted.size);assertEquals(receipt.jobOrderId,bench.submitted.single().first["jobOrderId"].asText())
  assertEquals("ACCEPTED",service.get(receipt.receiptId).state);assertEquals("WAITING_RESOURCE",service.get(second.receiptId).state)
  assertEquals(receipt.receiptId,c.stations().single().owner)
  assertEquals(1,bench.submitted.single().first["deployment"]["deploymentVersion"].asInt());assertEquals("CODE",bench.submitted.single().first["missionVersion"].asText())
  val saved=service.get(receipt.receiptId).normalizedOrder
  assertEquals(saved,bench.submitted.single().first)
 }
 @Test fun `공통 자원 접수는 재시작 후에도 같은 대기열과 순서를 유지한다`() {
  resource();ready(false);val a=manual().receipt;val b=service.receive("mes",body).receipt
  coordinator().tick();coordinator().tick()
  assertEquals(a.receiptId,coordinator().stations().single().owner)
  assertEquals("QUEUED",coordinator().get(b.receiptId).state)
  assertEquals(0L,service.resources.context(a.receiptId)!!["queuedAhead"])
  assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `필수 자원 미배정은 일반 재평가 API로 우회하지 못한다`() {
  resource();ready(true);bench.judge={ids->HostCall.Ok(ids.map{JobOrderBench.missing(it,"inspect")})}
  val a=manual().receipt;coordinator().tick();val current=service.get(a.receiptId)
  assertEquals("UNASSIGNED",current.state)
  val ex=assertFailsWith<RequestRejected>{service.recover(a.receiptId,Actor(Mode.OPERATOR,"kim"),json.readTree("""{"requestId":"${UUID.randomUUID()}","reason":"우회 시도","expectedUpdatedAt":"${current.updatedAt}"}"""))}
  assertEquals("RESOURCE_MANAGED",ex.code);assertTrue(bench.submitted.isEmpty())
 }
 /** 후보가 전부 빠진 전송 전 미배정은 제한 재평가 때 판정을 다시 한다. 그사이 배정 가능해진 기체가 그 재평가에서 뽑히고, 재평가 상한·간격은 그대로다. */
 @Test fun `전송 전 미배정의 제한 재평가는 나중에 배정 가능해진 기체를 고르고 재평가 횟수와 간격은 그대로다`() {
  val late="humanoid-02"
  resource();ready(true)
  bench.judge={ids->HostCall.Ok(ids.map{if(it==JobOrderBench.HUMANOID)JobOrderBench.running(it,"exec-9") else JobOrderBench.missing(it,"inspect")})}
  val a=manual().receipt;val c=coordinator();c.tick()
  assertEquals("UNASSIGNED",service.get(a.receiptId).state);assertEquals("WAITING_ROBOT",c.get(a.receiptId).state);assertEquals(0,c.get(a.receiptId).attempt)
  assertTrue(bench.submitted.isEmpty())
  // 대역 상태에 배정 가능한 기체 B 가 나타난다. 앞의 둘은 여전히 빠진다.
  bench.robots=dev.picasso.ops.service.registry.RegistryCall.Ok(listOf(JobOrderBench.robot(JobOrderBench.HUMANOID),JobOrderBench.robot(JobOrderBench.QUADRUPED),JobOrderBench.robot(late)))
  bench.bindings=dev.picasso.ops.service.registry.RegistryCall.Ok(listOf(JobOrderBench.binding(JobOrderBench.HUMANOID),JobOrderBench.binding(JobOrderBench.QUADRUPED),JobOrderBench.binding(late)))
  val judgeBefore=bench.judge
  bench.judge={ids->HostCall.Ok(ids.map{if(it==late)JobOrderBench.pass(it) else (judgeBefore(listOf(it)) as HostCall.Ok).value.single()})}
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(late))
  // 재평가 간격(5초) 안에서는 다시 판정하지 않는다.
  c.tick();assertEquals("WAITING_ROBOT",c.get(a.receiptId).state);assertEquals(0,c.get(a.receiptId).attempt);assertTrue(bench.submitted.isEmpty())
  jdbc.sql("UPDATE ops.station_run SET phase_at=phase_at-interval '6 seconds' WHERE run_id=:id").param("id",a.receiptId).update()
  c.tick()
  val run=c.get(a.receiptId);assertEquals("RUNNING",run.state,run.reason);assertEquals(1,run.attempt)
  assertEquals(listOf(late),bench.submitted.single().second)
  assertEquals(a.jobOrderId,bench.submitted.single().first["jobOrderId"].asText())
  val receipt=service.get(a.receiptId);assertEquals("ACCEPTED",receipt.state);assertEquals(late,receipt.submission!!["outcome"]["robotId"].asText())
  assertEquals(a.receiptId,c.stations().single().owner)
 }
 @Test fun `준비 시간 초과와 대기 취소는 같은 영속 접수에 거절 사유를 남긴다`() {
  resource();ready(false);val a=manual().receipt;val b=manual().receipt;val c=coordinator();c.tick()
  c.decide(b.receiptId,Actor(Mode.OPERATOR,"kim"),json.readTree("""{"expectedRevision":1,"decision":"CANCEL_QUEUED","reason":"요청 취소"}"""))
  assertEquals("REJECTED",service.get(b.receiptId).state)
  jdbc.sql("UPDATE ops.station_run SET phase_at=clock_timestamp()-interval '60 seconds' WHERE run_id=:id").param("id",a.receiptId).update()
  c.tick();assertEquals("TIMED_OUT",c.get(a.receiptId).state);assertEquals("REJECTED",service.get(a.receiptId).state);assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `자원 요구는 운영자가 바꾸지 못하고 기존 불명 작업 중에는 등록하지 못한다`() {
  jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES('inspection-1','검사','ready','complete',30,'engineer/lee')").update()
  assertEquals(403,assertFailsWith<RequestRejected>{service.resources.create(Actor(Mode.OPERATOR,"kim"),"InspectAsset","inspection-1","등록")}.status)
  manual()
  assertEquals(409,assertFailsWith<RequestRejected>{service.resources.create(Actor(Mode.ENGINEER,"lee"),"InspectAsset","inspection-1","등록")}.status)
  assertNull(service.resources.required("InspectAsset"))
 }
 @Test fun `서로 다른 계약과 예전 배포도 같은 미션 자원 요구를 생략하지 못한다`() {
  resource()
  val view=interfaces.get("asset-check",1);assertEquals("inspection-1",view.requiredStationId)
  val payload=json.readTree("""{"requestId":"${UUID.randomUUID()}","input":${body["input"]},"expectedDeploymentVersion":1}""")
  assertEquals("WAITING_RESOURCE",service.receiveInterface(Actor(Mode.OPERATOR,"kim"),"asset-check",1,payload).receipt.state)
  assertFailsWith<IllegalArgumentException>{dev.picasso.ops.service.stations.OpsStationPort(service,interfaces,bench.host).validate(json.readTree("""{"interfaceId":"asset-check","interfaceVersion":1,"stationId":"other","expectedDeploymentVersion":1,"input":${body["input"]}}"""))}
  assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `미션 자원 요구는 변경 삭제가 금지되고 조회된다`() {
  resource();assertEquals(1,service.resources.list().size)
  assertFailsWith<org.springframework.dao.DataAccessException>{jdbc.sql("UPDATE ops.mission_resource_requirement SET station_id=station_id").update()}
  assertFailsWith<org.springframework.dao.DataAccessException>{jdbc.sql("DELETE FROM ops.mission_resource_requirement").update()}
  assertEquals(409,assertFailsWith<RequestRejected>{service.resources.create(Actor(Mode.ENGINEER,"lee"),"InspectAsset","inspection-1","중복")}.status)
 }

 @Test fun `공통 접수도 로봇 완료와 새 설비 완료를 모두 확인한 뒤 다음 요청을 허용한다`() {
  resource();ready(true);val a=manual().receipt;val b=manual().receipt;val c=coordinator();c.tick()
  jdbc.sql("""INSERT INTO ops.work_result(receipt_id,version,instance_id,execution_id,response_id,response_version,snapshot,result_hash)
   VALUES(:id,1,'mw-1','exec-1','response-1',1,'{"jobResponse":{"physicalState":"PHYSICALLY_DONE"}}','hash')""").param("id",a.receiptId).update()
  c.tick();assertEquals("RUNNING",c.get(a.receiptId).state);assertEquals(1,bench.submitted.size)
  ready(true,complete=true);c.tick();assertEquals("COMPLETED",c.get(a.receiptId).state)
  c.tick();assertEquals("WAITING_READY",c.get(b.receiptId).state);assertEquals(1,bench.submitted.size)
  ready(true);c.tick();assertEquals("RUNNING",c.get(b.receiptId).state);assertEquals(2,bench.submitted.size)
 }
 @Test fun `조정기 둘이 동시에 공통 접수를 처리해도 호스트 전송은 한 번이다`() {
  resource();ready(true);val a=manual().receipt;val b=manual().receipt
  val pool=Executors.newFixedThreadPool(2);val start=CountDownLatch(1)
  try {
   val work=(1..2).map{pool.submit {start.await();coordinator().tick()}}
   start.countDown();work.forEach{it.get(20,TimeUnit.SECONDS)}
   assertEquals(1,bench.submitted.size);assertEquals(a.receiptId,coordinator().stations().single().owner)
   assertEquals("QUEUED",coordinator().get(b.receiptId).state)
   assertTrue(coordinator().get(a.receiptId).state in setOf("RUNNING","REVIEW_REQUIRED"))
  } finally {pool.shutdownNow()}
 }

 @Test fun `접수 저장 뒤 예기치 않은 오류는 미전송 거절로 닫고 오류를 그대로 알린다`() {
  PostgresSupport.execute("ALTER TABLE ops.work_request ADD CONSTRAINT deny_normalized CHECK (normalized_order IS NULL) NOT VALID")
  assertFailsWith<org.springframework.dao.DataAccessException>{service.receive("mes",body)}
  val row=service.list("",null,0,20)["requests"].let{it as List<*>}.single() as Receipt
  assertEquals("REJECTED",row.state);assertEquals("작업 요청 처리 중 내부 오류; 호스트에 보내지 않았습니다. 새 요청 ID로 다시 보내십시오",row.detail)
  val replay=service().receive("mes",body);assertTrue(replay.duplicate);assertEquals("REJECTED",replay.receipt.state);assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `접수 저장 뒤 업무 거절은 자기 사유로 닫고 그대로 알린다`() {
  // A station run that neither owns its station nor is DISPATCHING: admission refuses with 409.
  jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES('inspection-1','공유 검사','ready','complete',30,'engineer/lee')").update()
  val run=UUID.randomUUID()
  jdbc.sql("INSERT INTO ops.station_run(run_id,station_id,payload,actor,state,reason,created_at,phase_at) VALUES(:id,'inspection-1','{}','operator/kim','QUEUED','대기',clock_timestamp(),clock_timestamp())").param("id",run).update()
  val ex=assertFailsWith<RequestRejected>{service.receiveInterface(Actor(Mode.OPERATOR,"kim"),"asset-check",1,json.readTree("""{"requestId":"${UUID.randomUUID()}","input":${body["input"]}}"""),run)}
  assertEquals(409,ex.status)
  val row=service.list("",null,0,20)["requests"].let{it as List<*>}.single() as Receipt
  assertEquals("REJECTED",row.state);assertEquals("스테이션 점유와 제출 상태를 확인할 수 없습니다",row.detail);assertEquals(ex.message,row.detail);assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `정리가 먼저 닫은 접수는 이어서 제출하거나 대기열에 넣지 않는다`() {
  // Simulates a sweep committing right after the insert ('early') or between normalization and the DISPATCHING claim.
  PostgresSupport.execute("""CREATE FUNCTION ops.test_swept() RETURNS trigger AS $$ BEGIN
   IF TG_OP='INSERT' AND NEW.source_system_id='early' OR TG_OP='UPDATE' AND NEW.source_system_id<>'early' AND OLD.normalized_order IS NULL AND NEW.normalized_order IS NOT NULL THEN NEW.state:='REJECTED';NEW.detail:='swept'; END IF;
   RETURN NEW; END $$ LANGUAGE plpgsql""")
  PostgresSupport.execute("CREATE TRIGGER test_swept BEFORE INSERT OR UPDATE ON ops.work_request FOR EACH ROW EXECUTE FUNCTION ops.test_swept()")
  val early=service.receive("early",body);assertEquals("REJECTED",early.receipt.state);assertEquals("swept",early.receipt.detail)
  val direct=service.receive("mes",body);assertEquals("REJECTED",direct.receipt.state);assertTrue(bench.submitted.isEmpty())
  resource()
  assertEquals(409,assertFailsWith<RequestRejected>{manual()}.status)
  assertEquals(0,coordinator().runs().size);assertTrue(bench.submitted.isEmpty())
  assertEquals(setOf("REJECTED"),(service.list("",null,0,20)["requests"] as List<*>).map{(it as Receipt).state}.toSet())
 }
 private fun raw(state:String,wm:String?="InspectAsset"):UUID {
  val id=UUID.randomUUID()
  jdbc.sql("INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state,normalized_order) VALUES(:id,'mes',:key,'hash','{}',:key,:state,CAST(:order AS JSONB))")
   .param("id",id).param("key",id.toString()).param("state",state).param("order",wm?.let{"""{"workMasterId":"$it"}"""},java.sql.Types.VARCHAR).update()
  return id
 }
 private fun age(id:UUID,seconds:Int) {jdbc.sql("UPDATE ops.work_request SET updated_at=clock_timestamp()-make_interval(secs=>:s) WHERE receipt_id=:id").param("s",seconds).param("id",id).update()}
 @Test fun `오래 멈춘 접수 전 단계와 제출 중 단계만 정리하고 자원 대기는 두지 않는다`() {
  val received=raw("RECEIVED",null);val dispatching=raw("DISPATCHING");val waiting=raw("WAITING_RESOURCE");val fresh=raw("RECEIVED");val freshDispatch=raw("DISPATCHING")
  for(id in listOf(received,dispatching,waiting))age(id,600)
  assertEquals(2,service.sweepStale(Duration.ofMinutes(5)))
  assertEquals("REJECTED",service.get(received).state);assertEquals("작업 요청 처리 중단; 호스트에 보내지 않았습니다. 새 요청 ID로 다시 보내십시오",service.get(received).detail)
  assertEquals("IN_DOUBT",service.get(dispatching).state);assertEquals("제출 도중 중단; 실행 관측과 현장 확인이 필요합니다",service.get(dispatching).detail)
  assertEquals("WAITING_RESOURCE",service.get(waiting).state);assertEquals("RECEIVED",service.get(fresh).state);assertEquals("DISPATCHING",service.get(freshDispatch).state)
  assertEquals(0,service.sweepStale(Duration.ofMinutes(5)));assertTrue(bench.submitted.isEmpty())
 }
 @Test fun `결과 수집 주기는 정리 실패와 무관하게 결과를 모은다`() {
  val row=service.receive("mes",body).receipt;observeResult(row.jobOrderId)
  val stale=raw("RECEIVED");age(stale,600)
  PostgresSupport.execute("ALTER TABLE ops.work_request ADD CONSTRAINT deny_sweep CHECK (detail IS NULL OR detail NOT LIKE '작업 요청 처리 중단%') NOT VALID")
  val collector=WorkResultCollector(service,300);collector.collect()
  assertEquals("OK",collector.status()["state"]);assertEquals(1,service.result(row.receiptId,"mes",1)["version"]);assertEquals("RECEIVED",service.get(stale).state)
  PostgresSupport.execute("ALTER TABLE ops.work_request DROP CONSTRAINT deny_sweep")
  collector.collect();assertEquals("REJECTED",service.get(stale).state)
 }
 private fun register(wm:String)=runCatching{service.resources.create(Actor(Mode.ENGINEER,"lee"),wm,"inspection-1","등록")}.exceptionOrNull()?.let{assertEquals(409,(it as RequestRejected).status);true} ?: false
 private fun reviewed(id:UUID,observation:String) {service.review(id,Actor(Mode.OPERATOR,"kim"),json.valueToTree(mapOf("requestId" to UUID.randomUUID().toString(),"observation" to observation,"reason" to "현장 확인","expectedUpdatedAt" to service.get(id).updatedAt)))}
 private fun settled(id:UUID,state:String,version:Int=1) {jdbc.sql("""INSERT INTO ops.work_result(receipt_id,version,instance_id,execution_id,response_id,response_version,snapshot,result_hash)
   VALUES(:id,:v,'mw-1','exec-1',:r,1,CAST(:snapshot AS JSONB),'hash')""").param("id",id).param("v",version).param("r","response-$version").param("snapshot","""{"jobResponse":{"physicalState":"$state"}}""").update()}
 /** Each case uses its own mission so a successful registration never leaks into the next one. */
 private fun blocks(state:String,setup:(UUID)->Unit={}):Boolean {val wm="M-${UUID.randomUUID()}";val id=raw(state,wm);setup(id);val blocked=register(wm);jdbc.sql("UPDATE ops.work_request SET state='REJECTED' WHERE receipt_id=:id").param("id",id).update();return blocked}
 @Test fun `자원 요구 등록은 이 미션에서 아직 진행 중이거나 완료 근거가 없는 접수만 막는다`() {
  jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES('inspection-1','검사','ready','complete',30,'engineer/lee')").update()
  val other=raw("RECEIVED","OtherMission");assertFalse(register("InspectAsset"));jdbc.sql("UPDATE ops.work_request SET state='REJECTED' WHERE receipt_id=:id").param("id",other).update()
  for(state in listOf("RECEIVED","WAITING_RESOURCE","DISPATCHING","IN_DOUBT","ACCEPTED"))assertTrue(blocks(state),state)
  val unknown=raw("RECEIVED",null);assertTrue(register("M-${UUID.randomUUID()}"));jdbc.sql("UPDATE ops.work_request SET state='REJECTED' WHERE receipt_id=:id").param("id",unknown).update()
  for(state in listOf("UNASSIGNED","REJECTED"))assertFalse(blocks(state),state)
  for(result in listOf("PHYSICALLY_DONE","UNVERIFIED","FAILED","ABORTED","PARTIAL"))assertFalse(blocks("ACCEPTED"){settled(it,result)},result)
  assertTrue(blocks("ACCEPTED"){settled(it,"FAILED");settled(it,"RUNNING",2)})
  assertTrue(blocks("ACCEPTED"){settled(it,"OPERATOR_HOLD")})
  assertFalse(blocks("IN_DOUBT"){reviewed(it,"NOT_STARTED")});assertFalse(blocks("IN_DOUBT"){reviewed(it,"COMPLETED")});assertFalse(blocks("ACCEPTED"){reviewed(it,"COMPLETED")})
  assertTrue(blocks("IN_DOUBT"){reviewed(it,"NOT_CONFIRMED")});assertTrue(blocks("IN_DOUBT"){reviewed(it,"IN_PROGRESS")})
  assertTrue(blocks("IN_DOUBT"){reviewed(it,"NOT_STARTED");reviewed(it,"NOT_CONFIRMED")})
  assertTrue(blocks("IN_DOUBT"){reviewed(it,"NOT_STARTED");jdbc.sql("UPDATE ops.work_request SET updated_at=clock_timestamp() WHERE receipt_id=:id").param("id",it).update()})
 }
 @Test fun `등록 전 미배정 접수는 등록 뒤 일반 재평가로 자원 요구를 우회하지 못한다`() {
  bench.judge={ids->HostCall.Ok(ids.map{id->JobOrderBench.running(id,"busy")})}
  val row=manual().receipt;assertEquals("UNASSIGNED",row.state)
  resource();bench.judge={ids->HostCall.Ok(ids.map(JobOrderBench::pass))}
  val ex=assertFailsWith<RequestRejected>{service.recover(row.receiptId,Actor(Mode.OPERATOR,"kim"),json.readTree("""{"requestId":"${UUID.randomUUID()}","reason":"등록 뒤 재평가","expectedUpdatedAt":"${row.updatedAt}"}"""))}
  assertEquals("RESOURCE_REQUIREMENT",ex.code);assertEquals("UNASSIGNED",service.get(row.receiptId).state);assertEquals(row.updatedAt,service.get(row.receiptId).updatedAt);assertTrue(bench.submitted.isEmpty())
 }

 private fun pinned(order:com.fasterxml.jackson.databind.JsonNode?)=order!!.let{it["deployment"]["deploymentVersion"].asInt() to it["missionVersion"].asText()}
 private fun invocation(expected:Int)=json.readTree("""{"requestId":"${UUID.randomUUID()}","input":${body["input"]},"expectedDeploymentVersion":$expected}""")
 @Test fun `배포를 바꾸고 되돌려도 먼저 받은 작업 요청은 받은 때의 계약 배포 임무 버전을 유지하고 이력에 남긴다`() {
  val engineer=Actor(Mode.ENGINEER,"lee");val operator=Actor(Mode.OPERATOR,"kim")
  interfaces.deploy("asset-check",1,"7",1,"v7 배포",engineer)
  val firstCall=invocation(2);val r1=service.receiveInterface(operator,"asset-check",1,firstCall).receipt
  assertEquals("ACCEPTED",r1.state);assertEquals(2 to "7",pinned(r1.normalizedOrder))
  interfaces.deploy("asset-check",1,"8",2,"v8 배포",engineer)
  val r2=service.receiveInterface(operator,"asset-check",1,invocation(3)).receipt;assertEquals(3 to "8",pinned(r2.normalizedOrder))
  // v7 로 받은 실행이 v8 배포 뒤에 실패로 끝난다.
  observeResult(r1.jobOrderId,1,"FAILED",missionVersion=7);service().detail(r1.receiptId)
  val failed=service.result(r1.receiptId,"ops:interface",1);val snapshot=failed["snapshot"] as com.fasterxml.jackson.databind.JsonNode
  assertEquals(2,snapshot["deployment"]["deploymentVersion"].asInt());assertEquals(7,snapshot["missionVersion"].asInt())
  assertEquals("FAILED",snapshot["jobResponse"]["physicalState"].asText())
  val sent=bench.submitted.size
  val replay=service().receiveInterface(operator,"asset-check",1,firstCall)
  assertTrue(replay.duplicate);assertEquals(r1.receiptId,replay.receipt.receiptId);assertEquals(r1.jobOrderId,replay.receipt.jobOrderId)
  assertEquals(2 to "7",pinned(replay.receipt.normalizedOrder));assertEquals(sent,bench.submitted.size)
  interfaces.deploy("asset-check",1,"7",3,"v7 로 되돌림",engineer)
  val r3=service.receiveInterface(operator,"asset-check",1,invocation(4)).receipt;assertEquals(4 to "7",pinned(r3.normalizedOrder))
  assertEquals(r1.normalizedOrder,service().get(r1.receiptId).normalizedOrder);assertEquals(r2.normalizedOrder,service().get(r2.receiptId).normalizedOrder)
  assertEquals(failed,service().result(r1.receiptId,"ops:interface",1))
  assertEquals(listOf(2 to "7",3 to "8",4 to "7"),bench.submitted.map{pinned(it.first)})
  val records=log.list()
  for(r in listOf(r1,r2,r3)) {
   val submit=records.single{it.target==r.jobOrderId};val order=json.readTree(submit.request)["jobOrder"]
   assertEquals(r.receiptId,submit.requestId)
   assertEquals(r.normalizedOrder!!["deployment"],order["deployment"]);assertEquals(r.normalizedOrder!!["missionVersion"],order["missionVersion"])
  }
  assertEquals(listOf("v7 배포","v8 배포","v7 로 되돌림"),records.filter{it.target=="interface:asset-check/v1" && json.readTree(it.request)["op"].asText()=="INTERFACE_DEPLOY"}.map{it.reason}.reversed())
  assertEquals(listOf(4 to "7",3 to "8",2 to "7",1 to "CODE"),interfaces.deployments("asset-check",1).map{it["deploymentVersion"].asInt() to it["missionVersion"].asText()})
 }
 @Test fun `배포가 바뀐 뒤 미배정 재평가는 원래 배포로 제출하고 변환이 달라지면 RECOVERY_INPUT_CHANGED 로 막는다`() {
  val operator=Actor(Mode.OPERATOR,"kim")
  bench.judge={ids->HostCall.Ok(ids.map{id->JobOrderBench.running(id,"busy")})}
  val (r1,r2,r3)=(1..3).map{service.receiveInterface(operator,"asset-check",1,invocation(1)).receipt}
  for(r in listOf(r1,r2,r3))assertEquals("UNASSIGNED",r.state)
  interfaces.deploy("asset-check",1,"7",1,"v7 배포",Actor(Mode.ENGINEER,"lee"))
  bench.judge={ids->HostCall.Ok(ids.map(JobOrderBench::pass))}
  fun recover(r:Receipt)=service().recover(r.receiptId,operator,json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("reason","기체 준비 완료").put("expectedUpdatedAt",service.get(r.receiptId).updatedAt))
  assertEquals("ACCEPTED",recover(r1).receipt.state)
  val order=bench.submitted.single().first
  assertEquals(r1.jobOrderId,order["jobOrderId"].asText());assertEquals(1 to "CODE",pinned(order))
  // 변환 코드가 바뀐 업그레이드: 같은 원래 입력이 저장본과 다른 작업 지시가 된다.
  jdbc.sql("UPDATE ops.work_request SET normalized_order=jsonb_set(normalized_order,'{version}','2') WHERE receipt_id=:id").param("id",r2.receiptId).update()
  val before=service.get(r2.receiptId)
  val changed=assertFailsWith<RequestRejected>{recover(r2)}
  assertEquals("RECOVERY_INPUT_CHANGED",changed.code);assertEquals(409,changed.status)
  val after=service.get(r2.receiptId)
  assertEquals("UNASSIGNED",after.state);assertEquals(before.updatedAt,after.updatedAt)
  assertTrue((service.detail(r2.receiptId)["recoveries"] as List<*>).isEmpty());assertEquals(1,bench.submitted.size)
  interfaces.state("asset-check",1,"ENABLED","DISABLED","공개 중단",Actor(Mode.ENGINEER,"lee"))
  val disabled=assertFailsWith<RequestRejected>{recover(r3)}
  assertEquals("INTERFACE_NOT_ENABLED",disabled.code);assertEquals(409,disabled.status)
  assertEquals("UNASSIGNED",service.get(r3.receiptId).state);assertEquals(1,bench.submitted.size)
 }
 @Test fun `배포가 바뀌면 대기 중인 스테이션 작업은 새 배포로 옮기지 않고 거부로 닫고 점유를 푼다`() {
  resource();ready(false)
  val run=UUID.randomUUID();val c=coordinator()
  c.enqueue(Actor(Mode.OPERATOR,"kim"),json.readTree("""{"requestId":"$run","stationId":"inspection-1","interfaceId":"asset-check","interfaceVersion":1,"expectedDeploymentVersion":1,"input":${body["input"]}}"""))
  c.tick();assertEquals("WAITING_READY",c.get(run).state);assertEquals(run,c.stations().single().owner)
  interfaces.deploy("asset-check",1,"7",1,"v7 배포",Actor(Mode.ENGINEER,"lee"))
  ready(true);coordinator().tick()
  val closed=coordinator().get(run);assertEquals("REJECTED",closed.state)
  val receipt=service.get(closed.receiptId!!)
  assertEquals("REJECTED",receipt.state);assertContains(receipt.detail!!,"배포가 변경");assertNull(receipt.normalizedOrder)
  assertEquals("계약 제출 거부: ${receipt.detail}; 자원 해제",closed.reason)
  val station=coordinator().stations().single();assertNull(station.owner);assertNull(station.quarantinedBy)
  assertTrue(bench.submitted.isEmpty())
 }

 private fun execution(id:String,job:String,extra:String="")="""{"executionId":"$id","jobOrderId":"$job","workMasterId":"InspectAsset","missionVersion":null,"robotId":"humanoid-01","physicalState":"RUNNING","units":[],"jobResponse":null$extra}"""
 @Test fun `결과 불명 작업 요청은 재기동한 호스트에서 다시 지은 실행이 보일 때만 수락이 되고 일지 없는 실행은 사라져도 결과 불명으로 남는다`() {
  fun order(n:Int)=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-$n")
  // R1: 제출 응답 없음, 실행 목록 불통.
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")
  val r1=service.receive("mes",order(1)).receipt;assertEquals("IN_DOUBT",r1.state)
  // R2: 일지 쓰기 실패 500, 재조회도 불통.
  bench.answer=HostWrite.Answered(500,"""{"error":"JOURNAL_WRITE_FAILED","detail":"실행 일지 저장 실패"}""")
  val r2=service.receive("mes",order(2)).receipt;assertEquals("IN_DOUBT",r2.state)
  // R3: 응답 없음, 재조회는 읽혔고 실행이 없다.
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=bench.executionsWith()
  val r3=service.receive("mes",order(3)).receipt;assertEquals("IN_DOUBT",r3.state)
  assertEquals(listOf(dev.picasso.ops.service.log.OperationResult.NO_RESPONSE,dev.picasso.ops.service.log.OperationResult.CONFIRMED_NOT_APPLIED),log.list().filter{it.target==r3.jobOrderId}.map{it.result}.reversed())
  // 재기동 전: R2 실행은 일지 없이 메모리에만 있다.
  bench.executions=HostCall.Ok(json.readTree("""{"instanceId":"mw-1","pumpedAt":null,"journalBlockedExecutionIds":["exec-2"],"executions":[${execution("exec-1",r1.jobOrderId)},${execution("exec-2",r2.jobOrderId)}]}"""))
  val blocked=service.detail(r2.receiptId)
  assertEquals(true,blocked["journalBlocked"]);assertEquals("IN_DOUBT",(blocked["receipt"] as Receipt).state)
  // 실행 호스트 재기동: 일지에 있던 R1 실행만 새 인스턴스에서 다시 선다.
  val restoredFrom=""","restoredFrom":{"instanceId":"mw-1","executionId":"exec-1"}"""
  bench.executions=HostCall.Ok(json.readTree("""{"instanceId":"mw-2","pumpedAt":null,"journalBlockedExecutionIds":[],"executions":[${execution("exec-1",r1.jobOrderId,restoredFrom)}]}"""))
  val restarted=service()
  val resumed=restarted.detail(r1.receiptId)["receipt"] as Receipt
  assertEquals("ACCEPTED",resumed.state);assertEquals("mw-2",resumed.executionInstanceId);assertEquals("mw-1",resumed.lastExecution!!["restoredFrom"]["instanceId"].asText())
  val gone=restarted.detail(r2.receiptId)
  assertEquals("OK",gone["hostRead"]);assertNull(gone["execution"]);assertEquals(false,gone["journalBlocked"]);assertEquals("IN_DOUBT",(gone["receipt"] as Receipt).state)
  fun refused(r:Receipt)=assertFailsWith<RequestRejected>{restarted.recover(r.receiptId,Actor(Mode.OPERATOR,"kim"),json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("reason","재기동 뒤 재평가").put("expectedUpdatedAt",restarted.get(r.receiptId).updatedAt))}.code
  assertEquals("RECOVERY_NOT_SAFE",refused(r2))
  reviewed(r2.receiptId,"NOT_STARTED")
  assertEquals("IN_DOUBT",(restarted.detail(r2.receiptId)["receipt"] as Receipt).state)
  assertEquals("IN_DOUBT",(restarted.detail(r3.receiptId)["receipt"] as Receipt).state);assertEquals("RECOVERY_NOT_SAFE",refused(r3))
  assertEquals(3,bench.submitted.size)
 }
 @Test fun `전달 중 멈춘 결과와 닫힌 수신처는 임대가 끝난 뒤 같은 키로 버전마다 한 번 다시 보낸다`() {
  val row=service.receive("mes",body).receipt
  observeResult(row.jobOrderId,1,"PARTIAL");assertEquals(1,service.result(row.receiptId,"mes")["version"])
  observeResult(row.jobOrderId,2);assertEquals(2,service.result(row.receiptId,"mes")["version"])
  val port=java.net.ServerSocket(0,0,java.net.InetAddress.getByName("127.0.0.1")).use{it.localPort}
  val url="http://127.0.0.1:$port/results"
  fun rows()=jdbc.sql("SELECT version,state,last_error FROM ops.work_result_delivery ORDER BY version").query{rs,_ -> listOf(rs.getString(1),rs.getString(2),rs.getString(3))}.list()
  // 수신처가 닫혀 있다.
  WorkResultDelivery(jdbc,service,url,"","mes").deliver()
  assertEquals(listOf(listOf("1","RETRY","로컬 연결 실패 또는 응답 불명"),listOf("2","RETRY","로컬 연결 실패 또는 응답 불명")),rows())
  // v1 은 보내던 프로세스가 멈춰 IN_FLIGHT 로 남았고 임대가 끝났다. v2 는 재시도 시각이 지났다.
  jdbc.sql("UPDATE ops.work_result_delivery SET state='IN_FLIGHT',claim_token=:claim,next_attempt_at=clock_timestamp()-interval '1 second' WHERE version=1").param("claim",UUID.randomUUID()).update()
  jdbc.sql("UPDATE ops.work_result_delivery SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE version=2").update()
  val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",port),0)
  val seen=java.util.Collections.synchronizedList(mutableListOf<Pair<String,Int>>())
  server.createContext("/results") { exchange ->
   seen+=exchange.requestHeaders.getFirst("Idempotency-Key") to json.readTree(exchange.requestBody.readAllBytes())["version"].asInt()
   exchange.sendResponseHeaders(204,-1);exchange.close()
  };server.start()
  try {
   WorkResultDelivery(jdbc,service(),url,"","mes").deliver()
   assertEquals(listOf("DELIVERED","DELIVERED"),rows().map{it[1]})
   WorkResultDelivery(jdbc,service(),url,"","mes").deliver()
   assertEquals(listOf("${row.receiptId}/v1" to 1,"${row.receiptId}/v2" to 2),seen.sortedBy{it.second})
  } finally {server.stop(0)}
  assertNull(service.result(row.receiptId,"mes",1)["acknowledgedAt"]);assertNull(service.result(row.receiptId,"mes",2)["acknowledgedAt"])
 }
 // 상위 시스템(Mock MES)이 보는 것: 외부 주문 API 의 본문을 스프링 기본 설정처럼 JSON 으로 옮긴다(널 칸을 빼지 않는다).
 private val upstream get()=WorkRequestController(service,"secret","mes")
 private val wire=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
 private fun order(external:String,replaces:Any?=null,location:String="bay-7"):com.fasterxml.jackson.databind.node.ObjectNode =
  (json.readTree("""{"externalOrderId":"$external","interfaceId":"asset-check","interfaceVersion":1,"input":{"assets":[{"id":"T1","location":"$location"}]}}""") as com.fasterxml.jackson.databind.node.ObjectNode)
   .also{if(replaces!=null)it.set<com.fasterxml.jackson.databind.JsonNode>(WorkRequests.REPLACES,json.valueToTree(replaces))}
 private fun post(order:com.fasterxml.jackson.databind.JsonNode):Pair<Int,com.fasterxml.jackson.databind.JsonNode> =
  upstream.receive("Bearer secret",order.toString().toByteArray()).let{it.statusCode.value() to wire.valueToTree(it.body)}
 private fun posted(order:com.fasterxml.jackson.databind.JsonNode,state:String):UUID {
  val (code,body)=post(order);assertEquals(202,code,body.toString());assertEquals(state,body["receipt"]["state"].asText(),body.toString())
  return UUID.fromString(body["receipt"]["receiptId"].asText())
 }
 private fun status(id:UUID):com.fasterxml.jackson.databind.JsonNode =
  upstream.external("Bearer secret",id).let{assertEquals(200,it.statusCode.value());wire.valueToTree(it.body)}
 private fun refused(order:com.fasterxml.jackson.databind.JsonNode):Pair<Int,String> = post(order).let{(code,body)->code to body["error"].asText()}
 private fun shortage(robot:String,alternatives:String,source:String=sourceA)=
  """{"robotId":"$robot","reason":"출발 자리 $source 에 ENGINE-COVER-A 이 없다 — 비었다","alternativeLocations":$alternatives,"sourceMissing":{"material":"ENGINE-COVER-A","source":"$source","observed":null}}"""
 private fun rejectedShortage(alternatives:String)=rejectedBy("출발 자리 $sourceA 에 ENGINE-COVER-A 이 없다",
  ""","remedy":null,"remedyWithheld":false,"alternativeLocations":$alternatives,"sourceMissing":{"material":"ENGINE-COVER-A","source":"$sourceA","observed":null}""")
 private fun rows(external:String)=jdbc.sql("SELECT count(*) FROM ops.work_request WHERE external_order_id=:e").param("e",external).query(Long::class.java).single()
 private fun links()=jdbc.sql("SELECT count(*) FROM ops.work_request_supersession").query(Long::class.java).single()

 @Test fun `상위 시스템의 상태 조회는 출발 결품 거부의 대체 위치를 맨 위에 미배정은 기체마다 싣고 널과 빈 목록을 접지 않는다`() {
  fun rejection(external:String,alternatives:String):com.fasterxml.jackson.databind.JsonNode {
   bench.answer=HostWrite.Answered(200,rejectedShortage(alternatives))
   val seen=status(posted(order(external),"REJECTED"))
   assertTrue(seen.has("unassigned") && seen["unassigned"].isNull,seen.toString())
   return seen["rejection"]
  }
  val listed=rejection("ORDER-S1","[\"SEQ-IN-03.BIN-B\"]")
  assertEquals(listOf("SEQ-IN-03.BIN-B"),listed["alternativeLocations"].map{it.asText()},listed.toString())
  assertEquals(listOf(sourceA,"ENGINE-COVER-A"),listOf(listed["sourceMissing"]["source"].asText(),listed["sourceMissing"]["material"].asText()))
  assertTrue(listed["sourceMissing"].has("observed") && listed["sourceMissing"]["observed"].isNull,listed.toString())
  // 빈 목록: 그 자재를 든 다른 자리가 없다. 널: 셀이 답하지 않았다. 상위 시스템에도 둘이 따로 간다.
  val none=rejection("ORDER-S2","[]")
  assertTrue(none["alternativeLocations"].let{it.isArray && it.isEmpty},none.toString())
  val silent=rejection("ORDER-S3","null")
  assertTrue(silent.has("alternativeLocations") && silent["alternativeLocations"].isNull,silent.toString());assertTrue(silent["sourceMissing"].isObject,silent.toString())
  // 미배정은 기체마다 싣고 맨 위 거부 칸은 비었다.
  bench.answer=HostWrite.Answered(200,unassignedBy(shortage("humanoid-01","[\"SEQ-IN-03.BIN-B\"]"),shortage("humanoid-02","[]"),shortage("humanoid-03","null"),
   """{"robotId":"humanoid-04","reason":"자재 선언과 배정이 어긋난다"}"""))
  val id=posted(order("ORDER-S4"),"UNASSIGNED");val seen=status(id)
  assertTrue(seen.has("rejection") && seen["rejection"].isNull,seen.toString())
  val refusals=seen["unassigned"]["refusals"]
  assertEquals(listOf("humanoid-01","humanoid-02","humanoid-03","humanoid-04"),refusals.map{it["robotId"].asText()})
  assertEquals(listOf("SEQ-IN-03.BIN-B"),refusals[0]["alternativeLocations"].map{it.asText()});assertEquals(sourceA,refusals[0]["sourceMissing"]["source"].asText())
  assertTrue(refusals[1]["alternativeLocations"].let{it.isArray && it.isEmpty},refusals.toString())
  assertTrue(refusals[2].has("alternativeLocations") && refusals[2]["alternativeLocations"].isNull && refusals[2]["sourceMissing"].isObject,refusals.toString())
  assertTrue(refusals[3]["alternativeLocations"].isNull && refusals[3]["sourceMissing"].isNull,refusals.toString())
  // 연결 칸은 늘 있고 대체가 없으면 널이다.
  assertTrue(seen.has("replaces") && seen["replaces"].isNull && seen.has("supersededBy") && seen["supersededBy"].isNull,seen.toString())
  // 실행하지 않은 작업 요청에는 결과 버전이 없다. 결과 API 는 404 이고 대체 위치는 상태 조회에서 읽는다.
  val latest=upstream.latestResult("Bearer secret",id)
  assertEquals(404,latest.statusCode.value());assertEquals("RESULT_NOT_READY",(latest.body as dev.picasso.ops.service.web.PreRejection).error)
 }
 @Test fun `상위 시스템이 앞선 작업 요청 대신 보낸 새 작업 요청은 받아 양쪽에 연결을 보이고 앞선 작업 요청의 상태와 기록은 그대로다`() {
  bench.answer=HostWrite.Answered(200,unassignedBy(shortage("humanoid-01","[\"SEQ-IN-03.BIN-B\"]")))
  val a=posted(order("ORDER-A"),"UNASSIGNED")
  fun stored()=jdbc.sql("SELECT state,detail,submission::text,updated_at::text,payload::text,normalized_order::text FROM ops.work_request WHERE receipt_id=:id")
   .param("id",a).query{rs,_->(1..6).map{rs.getString(it)}}.single()
  val before=stored()
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  val b=posted(order("ORDER-B","ORDER-A","SEQ-IN-03.BIN-B"),"ACCEPTED")
  assertEquals(2,bench.submitted.size);assertEquals(service.get(b).jobOrderId,bench.submitted[1].first["jobOrderId"].asText())
  val original=status(a);val replacement=status(b)
  assertEquals(listOf(b.toString(),"ORDER-B","ACCEPTED"),listOf("receiptId","externalOrderId","state").map{original["supersededBy"][it].asText()},original.toString())
  assertTrue(original["replaces"].isNull,original.toString())
  assertEquals(listOf(a.toString(),"ORDER-A","UNASSIGNED"),listOf("receiptId","externalOrderId","state").map{replacement["replaces"][it].asText()},replacement.toString())
  assertTrue(replacement["supersededBy"].isNull,replacement.toString())
  assertEquals(original["supersededBy"]["linkedAt"],replacement["replaces"]["linkedAt"])
  // 앞선 작업 요청은 고치지 않는다: 상태, 문장, 제출 기록(대체 위치 포함), 입력이 그대로다.
  assertEquals(before,stored())
  assertEquals(listOf("SEQ-IN-03.BIN-B"),original["unassigned"]["refusals"][0]["alternativeLocations"].map{it.asText()})
  // 운영 화면 상세도 같은 칸이다.
  assertEquals(b,(service.detail(a)["supersededBy"] as WorkRequestLink).receiptId);assertEquals(a,(service.detail(b)["replaces"] as WorkRequestLink).receiptId)
  // 같은 새 작업 요청을 다시 보내면 기존 것을 돌려주고 다시 내지 않으며, 연결을 뺀 같은 ID 는 다른 본문이다.
  val (again,replay)=post(order("ORDER-B","ORDER-A","SEQ-IN-03.BIN-B"))
  assertEquals(200,again);assertTrue(replay["duplicate"].asBoolean());assertEquals(b.toString(),replay["receipt"]["receiptId"].asText())
  assertEquals(409 to "ORDER_CONFLICT",refused(order("ORDER-B",location="SEQ-IN-03.BIN-B")))
  assertEquals(2,bench.submitted.size);assertEquals(1L,links())
  // 접수 전에 실행 호스트가 앞선 작업 지시를 철회했다(B3). 연결은 그 철회 요청 id 를 든다. 재전송은 다시 부르지 않는다.
  assertEquals(listOf(service.get(a).jobOrderId to b),bench.withdrawCalls.toList())
  assertEquals(b,jdbc.sql("SELECT withdrawal_request_id FROM ops.work_request_supersession WHERE original_receipt_id=:id").param("id",a).query(UUID::class.java).single())
  // 덧붙이기만 한다.
  assertFails{jdbc.sql("UPDATE ops.work_request_supersession SET original_state='REJECTED'").update()}
  assertFails{jdbc.sql("DELETE FROM ops.work_request_supersession").update()}
 }
 @Test fun `실행 호스트가 앞선 작업 지시의 실행을 알리면 대체를 409 로 거절하고 앞선 작업 요청을 수락으로 올려 커밋한다`() {
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
  val a=posted(order("ORDER-A"),"UNASSIGNED");val job=service.get(a).jobOrderId;val submission=service.get(a).submission
  // 값 없는 승인이 운영 서비스를 거치지 않고 실행을 세웠다 — 첫 작업 응답 전이라 결과 피드도 실행 관측도 아직 없다.
  bench.withdraw={jobOrderId,_->HostWrite.Answered(200,JobOrderBench.executionExistsBody(jobOrderId,"exec-4"))}
  val (code,body)=post(order("ORDER-B","ORDER-A"))
  assertEquals(409 to "ORIGINAL_NOT_REPLACEABLE",code to body["error"].asText(),body.toString())
  assertTrue("exec-4" in body["detail"].asText() && JobOrderBench.HUMANOID in body["detail"].asText(),body.toString())
  assertEquals(0L,rows("ORDER-B"));assertEquals(0L,links());assertEquals(1,bench.submitted.size)
  // 승격은 거절과 별도로 커밋됐다. 제출 기록은 남고 작업 응답·결과 버전은 지어내지 않는다.
  val original=service.get(a)
  assertEquals("ACCEPTED",original.state);assertEquals(submission,original.submission)
  assertEquals(listOf("exec-4",job,"mw-1"),listOf(original.lastExecution!!["executionId"].asText(),original.lastExecution["jobOrderId"].asText(),original.executionInstanceId))
  assertTrue(original.detail!!.contains("exec-4"),original.detail)
  assertEquals(0L,resultCount())
  // 다시 보내도 앞선 작업 요청이 수락이라 같은 거절이고 호스트를 다시 부르지 않는다.
  val calls=bench.withdrawCalls.size
  assertEquals(409 to "ORIGINAL_NOT_REPLACEABLE",refused(order("ORDER-B","ORDER-A")));assertEquals(calls,bench.withdrawCalls.size)
 }
 @Test fun `철회에 답이 없거나 일지가 막혔으면 대체를 503 으로 접수하지 않고 아무것도 적지 않으며 복구 뒤 같은 요청은 통과한다`() {
  bench.answer=HostWrite.Answered(200,rejectedShortage("[]"));val a=posted(order("ORDER-A"),"REJECTED")
  fun stored()=jdbc.sql("SELECT state,detail,submission::text,updated_at::text FROM ops.work_request WHERE receipt_id=:id").param("id",a).query{rs,_->(1..4).map{rs.getString(it)}}.single()
  val before=stored()
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  listOf(HostWrite.NoResponse("응답 없음: HttpTimeoutException"),HostWrite.Answered(500,"""{"error":"JOURNAL_WRITE_FAILED","detail":"x"}"""),
   HostWrite.Answered(200,"""{"result":"MAYBE","jobOrderId":"${service.get(a).jobOrderId}"}"""),HostWrite.Answered(200,"""{"result":"WITHDRAWN","jobOrderId":"JO-OTHER","withdrawal":{"requestId":"${UUID.randomUUID()}"}}""")).forEach{answer->
   bench.withdraw={_,_->answer}
   assertEquals(503 to "WITHDRAWAL_UNCONFIRMED",refused(order("ORDER-B","ORDER-A")),answer.toString())
   assertEquals(0L,rows("ORDER-B"));assertEquals(0L,links());assertEquals(before,stored());assertEquals(1,bench.submitted.size)
  }
  // 복구: 같은 새 요청을 다시 보내면 호스트가 철회를 확정하고 접수한다.
  bench.withdraw=null
  val b=posted(order("ORDER-B","ORDER-A"),"ACCEPTED")
  assertEquals(b,jdbc.sql("SELECT withdrawal_request_id FROM ops.work_request_supersession WHERE original_receipt_id=:id").param("id",a).query(UUID::class.java).single())
  assertEquals(before,stored())
 }
 @Test fun `제출 기록이 없는 미배정과 출발 자리 확인이 제출 기록을 비운 미배정도 대체 전에 철회를 부르고 운영자 종결로 닫힌 앞선 작업 요청은 부르지 않는다`() {
  val judge=bench.judge;bench.judge={ids->HostCall.Ok(ids.map{JobOrderBench.missing(it,"inspect")})}
  val early=posted(order("ORDER-E"),"UNASSIGNED");assertNull(service.get(early).submission)
  bench.judge=judge
  // 출발 미관측 → 사람이 확인 → 다시 제출했으나 배정 가능한 기체가 없어 전송 전 미배정: 제출 기록이 비었다(V22). 그 사이 호스트가 답한 적이 있다.
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
  val held=posted(order("ORDER-H"),"UNASSIGNED")
  bench.judge={ids->HostCall.Ok(ids.map{JobOrderBench.missing(it,"inspect")})}
  service.confirmSource(held,Actor(Mode.OPERATOR,"kim"),confirmation(service.get(held)))
  bench.judge=judge
  assertEquals("UNASSIGNED",service.get(held).state);assertNull(service.get(held).submission)
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  posted(order("ORDER-E2","ORDER-E"),"ACCEPTED");posted(order("ORDER-H2","ORDER-H"),"ACCEPTED")
  assertEquals(listOf(service.get(early).jobOrderId,service.get(held).jobOrderId),bench.withdrawCalls.map{it.first})
 }
 @Test fun `잘못되거나 없거나 다른 출처이거나 실행 없이 끝나지 않은 앞선 작업 요청을 잇는 새 작업 요청은 접수하지 않고 오류 코드로 거절한다`() {
  val quiet=bench.executions
  bench.answer=HostWrite.Answered(200,rejectedShortage("[\"SEQ-IN-03.BIN-B\"]"))
  posted(order("ORDER-A"),"REJECTED")
  // 형식: 자기 자신, 빈 값, 161자, 문자열이 아닌 값. 모르는 칸은 그대로 봉투 오류다.
  assertEquals(400 to "BAD_REPLACES",refused(order("ORDER-X","ORDER-X")))
  assertEquals(400 to "BAD_REPLACES",refused(order("ORDER-X"," ")))
  assertEquals(400 to "BAD_REPLACES",refused(order("ORDER-X","R".repeat(161))))
  assertEquals(400 to "BAD_REPLACES",refused(order("ORDER-X",5)))
  assertEquals(400 to "BAD_ENVELOPE",refused(order("ORDER-X").put("replaces","ORDER-A")))
  // 없는 주문과 다른 출처의 주문은 같은 답이다. 다른 출처가 그 ID 를 가졌는지 드러내지 않는다.
  assertEquals(409 to "ORIGINAL_NOT_FOUND",refused(order("ORDER-X","NOPE")))
  assertEquals("REJECTED",service.receive("other-mes",order("ORDER-OTHER")).receipt.state)
  assertEquals(409 to "ORIGINAL_NOT_FOUND",refused(order("ORDER-X","ORDER-OTHER")))
  // 실행 없이 끝나지 않은 앞선 작업 요청: 수락, 결과 불명, 실행이 관측된 미배정, 결과 버전이 있는 거부.
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID));posted(order("ORDER-ACC"),"ACCEPTED")
  assertEquals(409 to "ORIGINAL_NOT_REPLACEABLE",refused(order("ORDER-X","ORDER-ACC")))
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline");posted(order("ORDER-DOUBT"),"IN_DOUBT");bench.executions=quiet
  assertEquals(409 to "ORIGINAL_NOT_REPLACEABLE",refused(order("ORDER-X","ORDER-DOUBT")))
  bench.answer=HostWrite.Answered(200,unassignedBy(shortage("humanoid-01","[]")));val ran=posted(order("ORDER-RAN"),"UNASSIGNED")
  jdbc.sql("""UPDATE ops.work_request SET last_execution='{"executionId":"exec-1"}' WHERE receipt_id=:id""").param("id",ran).update()
  assertEquals(409 to "ORIGINAL_NOT_REPLACEABLE",refused(order("ORDER-X","ORDER-RAN")))
  bench.answer=HostWrite.Answered(200,rejectedShortage("[]"));val result=posted(order("ORDER-RESULT"),"REJECTED");settled(result,"PHYSICALLY_DONE")
  assertEquals(409 to "ORIGINAL_NOT_REPLACEABLE",refused(order("ORDER-X","ORDER-RESULT")))
  // 이미 대체된 앞선 작업 요청은 다시 대체하지 않는다.
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  posted(order("ORDER-B","ORDER-A"),"ACCEPTED")
  assertEquals(409 to "ORIGINAL_ALREADY_REPLACED",refused(order("ORDER-C","ORDER-A")))
  // 거절은 아무것도 남기지 않으므로 같은 새 ID 를 고쳐 다시 보낼 수 있다.
  assertEquals(0L,rows("ORDER-X"));assertEquals(0L,rows("ORDER-C"));assertEquals(1L,links())
  val submitted=bench.submitted.size
  bench.answer=HostWrite.Answered(200,rejectedShortage("[]"));posted(order("ORDER-D"),"REJECTED")
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  posted(order("ORDER-X","ORDER-D"),"ACCEPTED");assertEquals(2L,links());assertEquals(submitted+2,bench.submitted.size)
 }
 @Test fun `상위 시스템이 대체한 앞선 작업 요청은 미배정 재평가와 출발 자리 확인으로 다시 제출하지 않는다`() {
  val kim=Actor(Mode.OPERATOR,"kim");val judge=bench.judge
  bench.judge={ids->HostCall.Ok(ids.map{JobOrderBench.missing(it,"inspect")})}
  val early=posted(order("ORDER-E"),"UNASSIGNED");assertNull(service.get(early).submission)
  bench.judge=judge
  bench.answer=HostWrite.Answered(200,unassignedBy(refusal("humanoid-01",unobserved())))
  val held=posted(order("ORDER-H"),"UNASSIGNED");assertEquals(1,service.pendingSources(service.get(held)).size)
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  posted(order("ORDER-E2","ORDER-E"),"ACCEPTED");posted(order("ORDER-H2","ORDER-H"),"ACCEPTED")
  val submitted=bench.submitted.size
  val recovery=json.readTree("""{"requestId":"${UUID.randomUUID()}","reason":"기체 복귀","expectedUpdatedAt":"${service.get(early).updatedAt}"}""")
  assertEquals("ORIGINAL_REPLACED",assertFailsWith<RequestRejected>{service.recover(early,kim,recovery)}.code)
  assertEquals("ORIGINAL_REPLACED",assertFailsWith<RequestRejected>{service.confirmSource(held,kim,confirmation(service.get(held)))}.code)
  assertEquals(submitted,bench.submitted.size)
  assertEquals("UNASSIGNED",service.get(early).state);assertEquals("UNASSIGNED",service.get(held).state)
  assertEquals(0L,jdbc.sql("SELECT count(*) FROM ops.work_request_recovery").query(Long::class.java).single())
  assertEquals(0L,jdbc.sql("SELECT count(*) FROM ops.work_request_source_confirmation").query(Long::class.java).single())
 }
 @Test fun `대체 위치로 다시 보낸 새 작업 요청도 제출 때 다시 게이트를 지나 그 자리마저 비었으면 새 대체 위치와 함께 출발 결품을 받고 그것도 다시 대체할 수 있다`() {
  bench.answer=HostWrite.Answered(200,unassignedBy(shortage("humanoid-01","[\"SEQ-IN-03.BIN-B\"]")))
  val a=posted(order("ORDER-A"),"UNASSIGNED")
  // 그사이 대체 위치가 비었다. 운영 서비스는 미리 거르지 않고 실행 호스트의 제출 게이트가 다시 본다.
  bench.answer=HostWrite.Answered(200,unassignedBy(shortage("humanoid-01","[\"SEQ-IN-04.BIN-C\"]","SEQ-IN-03.BIN-B")))
  val b=posted(order("ORDER-B","ORDER-A","SEQ-IN-03.BIN-B"),"UNASSIGNED")
  assertEquals(2,bench.submitted.size)
  val second=status(b)
  assertEquals("SEQ-IN-03.BIN-B",second["unassigned"]["refusals"][0]["sourceMissing"]["source"].asText())
  assertEquals(listOf("SEQ-IN-04.BIN-C"),second["unassigned"]["refusals"][0]["alternativeLocations"].map{it.asText()})
  assertEquals("ORDER-A",second["replaces"]["externalOrderId"].asText())
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  val c=posted(order("ORDER-C","ORDER-B","SEQ-IN-04.BIN-C"),"ACCEPTED")
  val middle=status(b)
  assertEquals(listOf("ORDER-A","ORDER-C"),listOf(middle["replaces"]["externalOrderId"].asText(),middle["supersededBy"]["externalOrderId"].asText()))
  assertEquals(c.toString(),middle["supersededBy"]["receiptId"].asText());assertEquals("ORDER-B",status(a)["supersededBy"]["externalOrderId"].asText())
 }
 @Test fun `같은 앞선 작업 요청을 잇는 동시 새 작업 요청은 하나만 받는다`() {
  bench.answer=HostWrite.Answered(200,rejectedShortage("[\"SEQ-IN-03.BIN-B\"]"));posted(order("ORDER-A"),"REJECTED")
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  val gate=CountDownLatch(1)
  val answers=Executors.newFixedThreadPool(6).use { pool ->
   val jobs=(1..6).map{n->pool.submit<Pair<Int,String>>{gate.await();post(order("ORDER-N$n","ORDER-A")).let{(code,body)->code to (body["error"]?.asText() ?: "")}}}
   gate.countDown();jobs.map{it.get(20,TimeUnit.SECONDS)}
  }
  assertEquals(1,answers.count{it.first==202},answers.toString())
  assertEquals(5,answers.count{it==(409 to "ORIGINAL_ALREADY_REPLACED")},answers.toString())
  assertEquals(2,bench.submitted.size);assertEquals(1L,links())
  assertEquals(1L,jdbc.sql("SELECT count(*) FROM ops.work_request WHERE external_order_id LIKE 'ORDER-N%'").query(Long::class.java).single())
 }
 @Test fun `공유 자원 작업이 끝나지 않은 앞선 작업 요청은 대체하지 않는다`() {
  resource();ready(true);bench.judge={ids->HostCall.Ok(ids.map{JobOrderBench.missing(it,"inspect")})}
  val a=posted(order("ORDER-A"),"WAITING_RESOURCE");coordinator().tick()
  assertEquals("UNASSIGNED",service.get(a).state);assertEquals("WAITING_ROBOT",coordinator().get(a).state)
  assertEquals(409 to "ORIGINAL_NOT_REPLACEABLE",refused(order("ORDER-B","ORDER-A")))
  assertEquals(0L,rows("ORDER-B"));assertEquals(0L,links())
 }
 /**
  * The host's terminal response when an operator closed an execution it could not restore (defect 14): UNVERIFIED, nothing completed, and a top-level
  * `closure` marker. [closure] null leaves the marker out. The event is the [seq]th of the feed; each page holds one event after the cursor.
  */
 private fun closeFeed(job:String,seq:Int=1,closure:String?="""{"kind":"UNRESTORED_CLOSED","basis":"SNAPSHOT"}""",state:String="UNVERIFIED"):com.fasterxml.jackson.databind.JsonNode {
  val marker=if(closure==null) "" else ""","closure":$closure"""
  return json.readTree("""{"producerId":"$PRODUCER","highWatermark":$seq,"events":[{"sequence":$seq,"snapshot":{"instanceId":"mw-before","executionId":"exec-7","jobOrderId":"$job",
   "robotId":"humanoid-01","workMasterId":"InspectAsset","missionVersion":null,"physicalState":"$state",
   "jobResponse":{"jobResponseId":"close-$seq","version":1,"physicalState":"$state","requiredEvidence":"E0","reachedEvidence":"E0","completedUnits":[],"unverifiedUnits":[],
   "incompleteUnits":{"T1":"UNRESTORED_CLOSED"},"inDoubtUnits":[],"operatorRequired":true,"residualHold":"HOLD_KIND_UNSPECIFIED","blockedBy":[],"connection":"CONNECTION_STATE_UNSPECIFIED"}$marker}}]}""")
 }
 @Test fun `종결 결과 이벤트는 closure 를 결과 버전에 싣고 결과 불명 작업 요청을 수락으로 올리며 틀린 closure 는 쪽 전체를 되돌린다`() {
  bench.answer=HostWrite.NoResponse("timeout");bench.executions=HostCall.Silent("offline")
  val row=service.receive("mes",body).receipt;assertEquals("IN_DOUBT",row.state)
  // A malformed closure, or a closure on a result that is not UNVERIFIED, fails the whole page: no version, no cursor move, no state change.
  listOf(
   closeFeed(row.jobOrderId,closure="""{"kind":"OTHER","basis":"SNAPSHOT"}"""),
   closeFeed(row.jobOrderId,closure="""{"kind":"UNRESTORED_CLOSED","basis":"WITNESS"}"""),
   closeFeed(row.jobOrderId,closure="""{"kind":"UNRESTORED_CLOSED"}"""),
   closeFeed(row.jobOrderId,closure="\"UNRESTORED_CLOSED\""),
   closeFeed(row.jobOrderId,state="PHYSICALLY_DONE"),
  ).forEachIndexed{index,page->
   val worker=durable{HostCall.Ok(page)}
   assertFailsWith<IllegalStateException>("page $index"){worker.collectResults()}
   assertEquals(0L,worker.cursorStatus()["sequence"],"page $index");assertEquals(0L,resultCount(),"page $index");assertEquals("IN_DOUBT",service.get(row.receiptId).state,"page $index")
  }
  // The same kind of event without a closure is an ordinary UNVERIFIED result and carries none.
  val plain=service.receive("mes",body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("externalOrderId","ORDER-PLAIN")).receipt
  assertEquals(1,durable{HostCall.Ok(closeFeed(plain.jobOrderId,closure=null))}.collectResults())
  assertFalse((service.result(plain.receiptId,"mes",1)["snapshot"] as com.fasterxml.jackson.databind.JsonNode).has("closure"))
  // A valid closure rides on its own result version and lifts an IN_DOUBT request to ACCEPTED (the host answered about it).
  val worker=durable{HostCall.Ok(closeFeed(row.jobOrderId,seq=2,closure="""{"kind":"UNRESTORED_CLOSED","basis":"ATTESTED"}"""))}
  assertEquals(1,worker.collectResults())
  assertEquals("ACCEPTED",service.get(row.receiptId).state)
  val stored=service.result(row.receiptId,"mes",1)
  val snapshot=stored["snapshot"] as com.fasterxml.jackson.databind.JsonNode
  assertEquals(json.readTree("""{"kind":"UNRESTORED_CLOSED","basis":"ATTESTED"}"""),snapshot["closure"],snapshot.toString())
  assertEquals(listOf("mw-before","exec-7","UNVERIFIED","close-2"),listOf(snapshot["instanceId"].asText(),snapshot["executionId"].asText(),snapshot["jobResponse"]["physicalState"].asText(),snapshot["jobResponse"]["jobResponseId"].asText()))
  assertTrue(snapshot["jobResponse"]["completedUnits"].isEmpty)
  assertEquals(2L,worker.cursorStatus()["sequence"])
 }
 @Test fun `운영자가 종결한 수락 작업 요청은 대체할 수 있고 연결에 그 결과 버전을 적으며 closure 없는 UNVERIFIED 와 열린 스테이션 작업은 대체하지 않는다`() {
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID))
  val closed=posted(order("ORDER-A"),"ACCEPTED");val plain=posted(order("ORDER-P"),"ACCEPTED");val busy=posted(order("ORDER-S"),"ACCEPTED")
  var page:com.fasterxml.jackson.databind.JsonNode=json.readTree("""{"producerId":"$PRODUCER","highWatermark":0,"events":[]}""")
  val worker=durable{HostCall.Ok(page)}
  var seq=0
  fun collect(id:UUID,closure:String?="""{"kind":"UNRESTORED_CLOSED","basis":"SNAPSHOT"}"""){page=closeFeed(service.get(id).jobOrderId,++seq,closure);assertEquals(1,worker.collectResults())}
  collect(closed);collect(plain,closure=null);collect(busy)
  jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES('inspection-1','공유 검사','ready','complete',30,'engineer/lee')").update()
  jdbc.sql("INSERT INTO ops.station_run(run_id,station_id,payload,actor,state,reason,receipt_id,created_at,phase_at) VALUES(:id,'inspection-1','{}','operator/kim','RUNNING','실행',:receipt,clock_timestamp(),clock_timestamp())")
   .param("id",UUID.randomUUID()).param("receipt",busy).update()
  // Not replaceable: UNVERIFIED without the closure marker, and a closed request whose station run is still open.
  assertEquals(409 to "ORIGINAL_NOT_REPLACEABLE",refused(order("ORDER-Q","ORDER-P")))
  assertEquals(409 to "ORIGINAL_NOT_REPLACEABLE",refused(order("ORDER-T","ORDER-S")))
  assertEquals(0L,rows("ORDER-Q"));assertEquals(0L,rows("ORDER-T"));assertEquals(0L,links())
  // Replaceable: the closed request. The link names the result version that closed it.
  val replacement=posted(order("ORDER-B","ORDER-A"),"ACCEPTED")
  val link=jdbc.sql("SELECT original_state,original_result_version FROM ops.work_request_supersession WHERE original_receipt_id=:id").param("id",closed)
   .query{rs,_->rs.getString(1) to rs.getObject(2) as Int?}.single()
  assertEquals("ACCEPTED" to 1,link)
  // 운영자 종결로 닫힌 앞선 작업 요청은 실행한 뒤 닫힌 것이라 철회를 부르지 않고 연결에 철회 요청 id 가 없다(B3).
  assertTrue(bench.withdrawCalls.none{it.first==service.get(closed).jobOrderId},bench.withdrawCalls.toString())
  assertNull(jdbc.sql("SELECT withdrawal_request_id FROM ops.work_request_supersession WHERE original_receipt_id=:id").param("id",closed).query{rs,_->rs.getObject(1,UUID::class.java)}.list().single())
  assertEquals(replacement.toString(),status(closed)["supersededBy"]["receiptId"].asText());assertEquals("ORDER-A",status(replacement)["replaces"]["externalOrderId"].asText())
  // Each side is linked at most once.
  assertEquals(409 to "ORIGINAL_ALREADY_REPLACED",refused(order("ORDER-C","ORDER-A")))
  // A request that ended without executing is still replaceable and records no result version.
  bench.answer=HostWrite.Answered(200,rejectedShortage("[]"));val rejected=posted(order("ORDER-R"),"REJECTED")
  bench.answer=HostWrite.Answered(200,JobOrderBench.accepted(JobOrderBench.HUMANOID));posted(order("ORDER-R2","ORDER-R"),"ACCEPTED")
  assertEquals("REJECTED" to null,jdbc.sql("SELECT original_state,original_result_version FROM ops.work_request_supersession WHERE original_receipt_id=:id").param("id",rejected)
   .query{rs,_->rs.getString(1) to rs.getObject(2) as Int?}.single())
  // The table only holds the two valid shapes.
  val a=raw("ACCEPTED");val b=raw("ACCEPTED");val c=raw("ACCEPTED");val d=raw("ACCEPTED")
  fun link(original:UUID,replacement:UUID,state:String,version:Int?)=jdbc.sql("INSERT INTO ops.work_request_supersession(original_receipt_id,replacement_receipt_id,source_system_id,original_state,original_result_version) VALUES(:o,:r,'mes',:s,:v)")
   .param("o",original).param("r",replacement).param("s",state).param("v",version,java.sql.Types.INTEGER).update()
  assertFails{link(a,b,"ACCEPTED",null)};assertFails{link(a,b,"ACCEPTED",0)};assertFails{link(a,b,"REJECTED",1)};assertFails{link(a,b,"IN_DOUBT",1)}
  assertEquals(1,link(a,b,"ACCEPTED",2));assertEquals(1,link(c,d,"UNASSIGNED",null))
 }
}
