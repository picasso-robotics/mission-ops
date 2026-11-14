package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.requests.RequestRejected
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.incidents.HoldResolutions
import dev.picasso.ops.service.interfaces.MissionInterfaces
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.requests.WorkRequests
import dev.picasso.ops.service.stations.*
import dev.picasso.ops.service.instance.OpsInstance
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.*
import java.util.UUID
import kotlin.test.*

class StationCoordinatorTest {
 private val ds=DriverManagerDataSource(PostgresSupport.jdbcUrl,PostgresSupport.username,PostgresSupport.password)
 private val jdbc=JdbcClient.create(ds)
 private val tx=TransactionTemplate(DataSourceTransactionManager(ds))
 private val json=ObjectMapper()
 private val engineer=Actor(Mode.ENGINEER,"lee")
 private val operator=Actor(Mode.OPERATOR,"kim")
 private var now=Instant.parse("2026-10-12T00:00:00Z")
 private val clock=object:Clock(){override fun getZone()=ZoneOffset.UTC;override fun withZone(zone:ZoneId)=this;override fun instant()=now}
 private val port=FakePort()
 private fun coordinator()=StationCoordinator(jdbc,tx,port,clock)
 private val service get()=coordinator()
 inner class FakePort:StationPort {
  var signals:Map<String,StationSignal>?=mapOf("ready" to StationSignal(true,now),"complete" to StationSignal(false,now))
  var receiptState="ACCEPTED";var physical:String?=null;var unknown=false;var valid=true;var sends=0;var retries=0
  /** A result already stored for the receipt when it is sent. */
  var preResult:String?=null
  val receipts=mutableMapOf<UUID,StationReceipt>()
  private val written=mutableMapOf<UUID,String>()
  var texts:Map<String,StationText> = mapOf("job" to StationText("",now),"echo" to StationText("",now),"lot" to StationText("LOT-1",now))
  var writeFails=false;val writes=mutableListOf<Pair<String,String>>()
  /** Runs once after a read has been taken, before its caller judges it. */
  var afterRead:(()->Unit)?=null
  /** Runs once when a receipt is read, after tick has read its stations and runs. */
  var onReceipt:(()->Unit)?=null
  /** The host's work permit (ADR 52). Undeclared keeps today's behaviour. */
  var permit:StationPermit=StationPermit.Undeclared;var permitThrows=false
  /** The work permit block of the receipt's execution (`permitBlock` of the host execution read). */
  var permitBlock:JsonNode?=null
  override fun permit():StationPermit {if(permitThrows)error("host down");return permit}
  override fun validate(payload:JsonNode){require(valid)}
  override fun signals()=signals
  override fun read():StationSignals? {val taken=signals?.let{StationSignals(it,texts)};afterRead?.let{afterRead=null;it()};return taken}
  override fun write(name:String,value:String,actor:Actor):Boolean {writes+=name to value;if(writeFails)return false;texts=texts+(name to StationText(value,now));return true}
  @Synchronized override fun dispatch(id:UUID,payload:JsonNode,actor:Actor):StationReceipt {
   receipts[id]?.let{return it}
   sends++
   jdbc.sql("INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state) VALUES(:id,'ops:interface',:key,'hash','{}',:key,:state)").param("id",id).param("key",id.toString()).param("state",receiptState).update()
   preResult?.let{result(id,it)}
   val r=StationReceipt(id,receiptState,receiptState=="UNASSIGNED","v1",physical,null);receipts[id]=r
   if(unknown)error("response lost")
   return r
  }
  /** A changed [physical] is a new stored result version; the latest stored result is what the port reports. */
  /** The receipt read fails, as when the host execution read and the store are both down. */
  var receiptFails=false
  override fun receipt(id:UUID):StationReceipt {
   onReceipt?.let{onReceipt=null;it()}
   if(receiptFails)error("host down")
   physical?.let{if(written[id]!=it){result(id,it);written[id]=it}}
   val latest=jdbc.sql("SELECT version,snapshot->'jobResponse'->>'physicalState' FROM ops.work_result WHERE receipt_id=:id ORDER BY version DESC LIMIT 1").param("id",id).query{r,_->r.getInt(1) to r.getString(2)}.optional().orElse(null)
   return receipts.getValue(id).copy(physicalState=latest?.second,resultVersion=latest?.first,permitBlock=permitBlock)
  }
  /** Closed without executing, as the ops port judges it: UNASSIGNED or REJECTED with no stored result. [unsentFails]: the read fails. */
  var unsentFails=false
  override fun unsent(receipt:UUID):Boolean {
   if(unsentFails)error("store down")
   val state=receipts[receipt]?.state ?: return false
   return state in setOf("UNASSIGNED","REJECTED") && jdbc.sql("SELECT count(*) FROM ops.work_result WHERE receipt_id=:id").param("id",receipt).query(Long::class.java).single()==0L
  }
  /** A retry whose answer is lost: the receipt is left in this state and the call throws. */
  var retryLost:String?=null
  override fun retry(receipt:StationReceipt,attempt:UUID,actor:Actor):StationReceipt {
   retries++
   retryLost?.let{state->receipts[receipt.id]=receipt.copy(state=state,safeToRetry=false);jdbc.sql("UPDATE ops.work_request SET state=:state WHERE receipt_id=:id").param("state",state).param("id",receipt.id).update();error("response lost")}
   return receipt.copy(state="ACCEPTED",safeToRetry=false).also{receipts[receipt.id]=it}
  }
 }
 private fun result(id:UUID,state:String) {jdbc.sql("""INSERT INTO ops.work_result(receipt_id,version,instance_id,execution_id,response_id,response_version,snapshot,result_hash)
  SELECT :id,COALESCE(max(version),0)+1,'mw-1','exec-1','r-'||(COALESCE(max(version),0)+1),COALESCE(max(version),0)+1,CAST(:snapshot AS JSONB),'hash' FROM ops.work_result WHERE receipt_id=:id""")
  .param("id",id).param("snapshot","""{"jobResponse":{"physicalState":"$state"}}""").update()}
 @BeforeTest fun fresh(){OpsSchema.flyway(ds,cleanable=true).apply{clean();migrate()};service.createStation(engineer,json.readTree("""{"stationId":"inspection-1","name":"공유 검사","readySignal":"ready","completeSignal":"complete","timeoutSeconds":10}"""))}
 private fun enqueue(id:UUID=UUID.randomUUID())=service.enqueue(operator,json.readTree("""{"requestId":"$id","stationId":"inspection-1","interfaceId":"inspect","interfaceVersion":1,"expectedDeploymentVersion":1,"input":{"assets":[]}}"""))
 private fun signal(ready:Boolean=true,complete:Boolean=false,at:Instant=now){port.signals=mapOf("ready" to StationSignal(ready,at),"complete" to StationSignal(complete,at))}
 private fun decide(id:UUID,decision:String,revision:Int=service.get(id).revision)=service.decide(id,operator,json.readTree("""{"expectedRevision":$revision,"decision":"$decision","reason":"현장 상태 확인"}"""))
 /** The previous run was released on site confirmation and quarantined the station: confirm it idle on this run and dispatch. */
 private fun idle(run:StationRun){if(service.stations().single().quarantinedBy!=null){assertEquals("WAITING_READY",service.get(run.runId).state);decide(run.runId,"CONFIRM_STATION_IDLE");service.tick()}}
 @Test fun `자원 점유는 직렬화되고 로봇 완료와 새로운 설비 완료 근거가 모두 필요하다`() {
  val a=enqueue();now=now.plusMillis(1);val b=enqueue();service.tick()
  assertEquals("RUNNING",service.get(a.runId).state);assertEquals("QUEUED",service.get(b.runId).state);assertEquals(1,port.sends)
  port.physical="PHYSICALLY_DONE";service.tick();assertEquals(a.runId,service.stations().single().owner)
  now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(a.runId).state);assertNull(service.stations().single().owner)
  service.tick();assertEquals("WAITING_READY",service.get(b.runId).state);assertEquals(1,port.sends)
  signal();service.tick();assertEquals("RUNNING",service.get(b.runId).state);assertEquals(2,port.sends)
 }
 @Test fun `이전 완료 true와 로봇 수락만으로 점유를 풀지 않는다`() {
  signal(complete=true);val run=enqueue();service.tick();assertEquals("WAITING_READY",service.get(run.runId).state);assertEquals(0,port.sends)
  signal();service.tick();assertEquals("RUNNING",service.get(run.runId).state);now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("RUNNING",service.get(run.runId).state)
 }
 @Test fun `설비 지연은 로봇 전송 없이 기다리다가 준비 후 진행한다`() {
  signal(ready=false);val run=enqueue();service.tick();assertEquals(0,port.sends)
  now=now.plusSeconds(3);signal();service.tick();assertEquals("RUNNING",service.get(run.runId).state)
 }
 @Test fun `준비 시간 초과는 전송 전 자원을 해제한다`() {
  port.signals=null;val run=enqueue();service.tick();now=now.plusSeconds(11);service.tick()
  assertEquals("TIMED_OUT",service.get(run.runId).state);assertEquals(0,port.sends);assertNull(service.stations().single().owner)
 }
 @Test fun `실행 시간 초과는 점유를 유지하고 자동 재실행하지 않는다`() {
  val run=enqueue();service.tick();now=now.plusSeconds(11);service.tick();service.tick()
  assertEquals("REVIEW_REQUIRED",service.get(run.runId).state);assertEquals(run.runId,service.stations().single().owner);assertEquals(1,port.sends)
 }
 @Test fun `응답 불명은 운영자 확인 전 해제나 재전송하지 않는다`() {
  port.unknown=true;val run=enqueue();service.tick();coordinator().tick()
  assertEquals("REVIEW_REQUIRED",service.get(run.runId).state);assertEquals(1,port.sends)
  decide(run.runId,"CONFIRM_CLEAR");assertEquals("MANUALLY_CLEARED",service.get(run.runId).state);assertNull(service.stations().single().owner)
  assertEquals("operator/kim",service.events(run.runId).last()["actor"])
 }
 @Test fun `전송 전 미배정만 제한 재평가하고 같은 접수를 유지한다`() {
  port.receiptState="UNASSIGNED";val run=enqueue();service.tick();assertEquals("WAITING_ROBOT",service.get(run.runId).state)
  now=now.plusSeconds(5);service.tick();assertEquals("RUNNING",service.get(run.runId).state);assertEquals(run.runId,service.get(run.runId).receiptId);assertEquals(1,port.sends);assertEquals(1,port.retries)
 }
 @Test fun `운영자 보류는 재할당하지 않고 관측 재개로 완료한다`() {
  val run=enqueue();service.tick();port.physical="OPERATOR_HOLD";service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state)
  decide(run.runId,"RESUME_OBSERVATION");now=now.plusSeconds(1);port.physical="PHYSICALLY_DONE";signal(complete=true);service.tick()
  assertEquals("COMPLETED",service.get(run.runId).state);assertEquals(1,port.sends)
 }
 @Test fun `정착한 비성공 물리 상태는 시간 초과 전에 확인 필요로 넘기고 점유를 유지하며 취소 중은 기다린다`() {
  for(state in listOf("UNVERIFIED","PARTIAL","FAILED","ABORTED","IN_DOUBT","OPERATOR_HOLD")) {
   port.physical=null;val run=enqueue();service.tick();idle(run);assertEquals("RUNNING",service.get(run.runId).state,state)
   port.physical=state;service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state,state);assertEquals(run.runId,service.stations().single().owner,state)
   decide(run.runId,"CONFIRM_CLEAR")
  }
  port.physical=null;val run=enqueue();service.tick();idle(run);port.physical="CANCELING";service.tick();service.tick()
  assertEquals("RUNNING",service.get(run.runId).state);assertEquals(run.runId,service.stations().single().owner)
 }
 @Test fun `같은 요청 재접수는 현재 계약이 바뀌어도 원래 작업을 반환한다`() {
  val run=enqueue();port.valid=false;assertEquals(run,enqueue(run.runId));assertEquals(1,service.runs().size)
  assertFailsWith<RequestRejected>{service.enqueue(Actor(Mode.OPERATOR,"other"),run.payload)}
 }
 @Test fun `오래된 판단 버전과 비운영자 조작은 거절한다`() {
  val run=enqueue();service.tick();now=now.plusSeconds(11);service.tick()
  assertFailsWith<RequestRejected>{decide(run.runId,"CONFIRM_CLEAR",1)}
  assertFailsWith<RequestRejected>{service.decide(run.runId,engineer,json.readTree("{}"))}
  assertEquals(run.runId,service.stations().single().owner)
 }
 @Test fun `재기동은 점유와 대기열과 이벤트를 보존한다`() {
  val a=enqueue();now=now.plusMillis(1);val b=enqueue();service.tick();coordinator().tick()
  assertEquals(a.runId,coordinator().stations().single().owner);assertEquals("QUEUED",coordinator().get(b.runId).state);assertEquals(1,port.sends);assertTrue(coordinator().events(a.runId).size>=3)
 }
 @Test fun `전송 기록 직후 재기동은 동일 키를 확인하고 추가 실행하지 않는다`() {
  val run=enqueue();service.tick();jdbc.sql("UPDATE ops.station_run SET state='DISPATCHING' WHERE run_id=:id").param("id",run.runId).update()
  coordinator().tick();assertEquals(1,port.sends);assertEquals("RUNNING",service.get(run.runId).state)
 }
 /** 셀 신호 읽기 실패가 유예 시간 넘게 이어지면 확인 필요로 넘기고, 그 뒤 증명된 정상 완료는 운영자 완료 확인으로 받는다. */
 @Test fun `실행 중 신호 단절은 확인 필요로 전환하고 점유를 유지한다`() {
  plain(60);val run=enqueue();service.tick();port.signals=null;service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  now=now.plus(StationCoordinator.SIGNAL_GRACE).minusMillis(1);service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  now=now.plusMillis(1);service.tick()
  val review=service.get(run.runId);assertEquals(listOf("REVIEW_REQUIRED",StationCoordinator.SIGNALS_UNOBSERVED),listOf(review.state,review.reason));assertEquals(run.runId,service.stations().single().owner);assertEquals(1,port.sends)
  now=now.plusSeconds(1);result(run.runId,"PHYSICALLY_DONE");signal(complete=true)
  assertEquals(409,assertFailsWith<RequestRejected>{decide(run.runId,"RESUME_OBSERVATION")}.status)
  assertEquals("COMPLETED",decide(run.runId,"CONFIRM_COMPLETION").state);assertNull(service.stations().single().owner)
 }
 /** 셀 신호 읽기 실패는 일시 장애로 본다. 유예 동안 그 작업을 판정하지 않고, 다시 읽히면 그대로 관측한다. */
 @Test fun `셀 신호를 한 번 못 읽어도 유예 안에서는 확인 필요로 넘기지 않고 다시 읽히면 그대로 관측해 완료한다`() {
  val run=enqueue();service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  now=now.plusSeconds(1);port.signals=null;service.tick();now=now.plusSeconds(5);service.tick()
  val held=service.get(run.runId);assertEquals(listOf("RUNNING",StationCoordinator.SIGNALS_GRACE),listOf(held.state,held.reason));assertEquals(run.runId,service.stations().single().owner)
  assertEquals(1,service.events(run.runId).count{it["reason"]==StationCoordinator.SIGNALS_GRACE})
  now=now.plusSeconds(1);signal();service.tick();assertEquals(listOf("RUNNING",StationCoordinator.RUN_WAITING),service.get(run.runId).let{listOf(it.state,it.reason)})
  now=now.plusSeconds(1);result(run.runId,"PHYSICALLY_DONE");signal(complete=true);service.tick()
  assertEquals("COMPLETED",service.get(run.runId).state);assertEquals(1,port.sends)
 }
 @Test fun `재평가 중 재기동은 자동 재전송 없이 현장 확인으로 넘긴다`() {
  val run=enqueue();service.tick();jdbc.sql("UPDATE ops.station_run SET state='RETRYING' WHERE run_id=:id").param("id",run.runId).update();coordinator().tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state);assertEquals(1,port.sends)
 }
 /** A started instance that does not beat again within a test: its row expires only when the test moves expires_at. */
 private fun instance()=OpsInstance(jdbc,Duration.ofHours(1),Duration.ofHours(3)).start()
 private fun expire(i:OpsInstance){jdbc.sql("UPDATE ops.service_instance SET expires_at=clock_timestamp()-interval '1 second' WHERE instance_id=:id").param("id",i.instanceId).update()}
 /** The claim another process committed before its host call: the state and the instance that wrote it. */
 private fun claim(run:StationRun,state:String,by:OpsInstance){jdbc.sql("UPDATE ops.station_run SET state=:state,instance_id=:by WHERE run_id=:id").param("state",state).param("by",by.instanceId).param("id",run.runId).update()}
 private fun lastSeen(i:OpsInstance)=jdbc.sql("SELECT last_seen_at,expires_at,stopped_at FROM ops.service_instance WHERE instance_id=:id").param("id",i.instanceId).query{r,_->Triple(r.getTimestamp(1).toInstant(),r.getTimestamp(2).toInstant(),r.getTimestamp(3)?.toInstant())}.single()
 /** [state] claimed by a live instance stays; once [end] makes that instance not live, another instance's tick takes it for review. */
 private fun leftOver(state:String,end:(OpsInstance)->Unit) {
  val a=instance();val b=instance()
  try {
   val ca=StationCoordinator(jdbc,tx,port,clock,a);val cb=StationCoordinator(jdbc,tx,port,clock,b)
   val run=ca.enqueue(operator,json.readTree("""{"requestId":"${UUID.randomUUID()}","stationId":"inspection-1","interfaceId":"inspect","interfaceVersion":1,"expectedDeploymentVersion":1,"input":{"assets":[]}}"""))
   assertEquals(a.instanceId,run.instanceId)
   ca.tick();assertEquals("RUNNING" to a.instanceId,ca.get(run.runId).let{it.state to it.instanceId})
   claim(run,state,a)
   cb.tick();cb.tick()
   assertEquals(state to a.instanceId,cb.get(run.runId).let{it.state to it.instanceId},"살아 있는 다른 인스턴스의 점유를 넘겼다")
   end(a);cb.tick()
   val after=cb.get(run.runId)
   assertEquals(listOf("REVIEW_REQUIRED",StationCoordinator.LEFTOVER_STOPPED.getValue(state),b.instanceId),listOf(after.state,after.reason,after.instanceId))
   assertEquals(a.instanceId,(cb.events(run.runId).last()["context"] as JsonNode)["claimedBy"].asText())
   assertEquals(run.runId,cb.stations().single().owner);assertEquals(1,port.sends)
  } finally {a.close();b.close()}
 }
 @Test fun `재평가 점유를 남긴 다른 인스턴스가 살아 있으면 넘기지 않고 그 심장박동이 만료되면 확인 필요로 넘긴다`()=leftOver("RETRYING",::expire)
 @Test fun `재작업 점유를 남긴 다른 인스턴스가 살아 있으면 넘기지 않고 그 인스턴스가 멈춤을 적으면 확인 필요로 넘긴다`()=leftOver("REWORKING"){it.close()}
 @Test fun `이 인스턴스가 남긴 재평가 점유는 진행 중일 수 없으므로 정리 실패로 확인 필요로 넘긴다`() {
  val a=instance()
  try {
   val c=StationCoordinator(jdbc,tx,port,clock,a);val run=enqueue();c.tick();claim(run,"RETRYING",a);c.tick()
   val after=c.get(run.runId)
   assertEquals(listOf("REVIEW_REQUIRED",StationCoordinator.LEFTOVER_OWN.getValue("RETRYING")),listOf(after.state,after.reason));assertEquals(1,port.sends)
  } finally {a.close()}
 }
 @Test fun `심장박동은 기동 때 행을 넣고 주기마다 DB 시각으로 고쳐 만료 폭이 지나도 살아 있으며 닫으면 멈춤을 적는다`() {
  val a=OpsInstance(jdbc,Duration.ofMillis(100),Duration.ofMillis(300)).start();val b=instance()
  try {
   assertTrue(a.isLive(a.instanceId));assertTrue(b.isLive(a.instanceId))
   val (seen,expires,_)=lastSeen(a);assertEquals(Duration.ofMillis(300),Duration.between(seen,expires))
   val run=enqueue();coordinator().tick();claim(run,"RETRYING",a)
   // Three expiry widths: only the heartbeat keeps the claim held.
   Thread.sleep(900)
   assertTrue(lastSeen(a).first.isAfter(seen),"심장박동이 행을 고치지 않았다")
   StationCoordinator(jdbc,tx,port,clock,b).tick();assertEquals("RETRYING",service.get(run.runId).state)
  } finally {a.close();b.close()}
  val stopped=lastSeen(a);assertNotNull(stopped.third);assertFalse(a.isLive(a.instanceId))
  Thread.sleep(300);assertEquals(stopped,lastSeen(a),"멈춘 인스턴스의 행을 되살렸다")
  assertFalse(a.isLive(null));assertFalse(a.isLive("ops-unknown"))
  assertFailsWith<IllegalArgumentException>{OpsInstance(jdbc,Duration.ofSeconds(5),Duration.ofSeconds(14))}
  assertFailsWith<IllegalArgumentException>{OpsInstance(jdbc,Duration.ZERO,Duration.ofSeconds(30))}
 }
 @Test fun `확인 이벤트는 DB에서 수정 삭제 통째 비우기를 금지한다`() {
  val run=enqueue();assertFails{jdbc.sql("UPDATE ops.station_event SET reason='changed' WHERE run_id=:id").param("id",run.runId).update()};assertFails{jdbc.sql("DELETE FROM ops.station_event").update()};assertFails{jdbc.sql("TRUNCATE ops.station_event").update()}
 }
 @Test fun `동시 접수와 워커는 한 자원에서 추가 실행을 만들지 않는다`() {
  val id=UUID.randomUUID();val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
  try {val accepted=(1..2).map{executor.submit<StationRun>{enqueue(id)}}.map{it.get()};assertEquals(1,accepted.map{it.runId}.distinct().size)
   val ticks=(1..2).map{executor.submit{coordinator().tick()}};ticks.forEach{it.get()};assertEquals(1,port.sends);assertEquals(id,service.stations().single().owner)
  }finally{executor.shutdownNow()}
 }
 @Test fun `안전 신호 또는 누락 신호는 자원 준비 조건으로 등록하지 않는다`() {
  assertFailsWith<RequestRejected>{service.createStation(engineer,json.readTree("""{"stationId":"unsafe","name":"거절","readySignal":"safety","completeSignal":"missing","timeoutSeconds":10}"""))};assertEquals(1,service.stations().size)
 }
 private fun receipt(id:UUID)=jdbc.sql("SELECT state,detail FROM ops.work_request WHERE receipt_id=:id").param("id",id).query{r,_->r.getString(1) to r.getString(2)}.single()
 @Test fun `현장 확인 해제는 묶인 접수를 미전송 거절 또는 결과 불명으로 닫는다`() {
  port.receiptState="IN_DOUBT"
  val expected=mapOf("WAITING_RESOURCE" to "REJECTED","UNASSIGNED" to "REJECTED","DISPATCHING" to "IN_DOUBT","ACCEPTED" to "ACCEPTED","IN_DOUBT" to "IN_DOUBT","REJECTED" to "REJECTED")
  for((before,after) in expected) {
   val run=enqueue();service.tick();idle(run);assertEquals("REVIEW_REQUIRED",service.get(run.runId).state);assertEquals(run.runId,service.get(run.runId).receiptId)
   jdbc.sql("UPDATE ops.work_request SET state=:state,detail='before' WHERE receipt_id=:id").param("state",before).param("id",run.runId).update()
   decide(run.runId,"CONFIRM_CLEAR");assertNull(service.stations().single().owner)
   val (state,detail)=receipt(run.runId);assertEquals(after,state,before)
   when {
    before==after -> assertEquals("before",detail,before)
    // 이 포트는 보낸 답(IN_DOUBT)을 기억해 미전송을 증명하지 못한다(B3: 실행 호스트의 철회 확정이 없다). 미배정은 «실행하지 않습니다» 로 닫지 않는다.
    before=="UNASSIGNED" -> assertEquals("현장 확인으로 자원을 해제했습니다. 실행 호스트가 작업 지시 철회를 확정하지 않아 실행 여부는 실행 관측으로 확인합니다: 현장 상태 확인",detail)
    after=="REJECTED" -> assertEquals("현장 확인으로 자원을 해제했습니다. 이 작업 요청은 실행하지 않습니다: 현장 상태 확인",detail)
    else -> assertEquals("현장 확인으로 자원을 해제했습니다. 제출 결과는 실행 관측으로 확인합니다: 현장 상태 확인",detail)
   }
  }
  assertEquals(expected.size,port.sends)
 }
 // Hold resolution through the station rework guard. The host's execution list maps exec-1 to the run's receipt.
 private val hostBench=JobOrderBench();private val incidents=IncidentBench();private val opsLog=OperationLog(jdbc)
 private val requests=WorkRequests(jdbc,MissionInterfaces(jdbc,tx,opsLog),JobOrderOperations(hostBench.eligibility,hostBench.host,hostBench.host,opsLog,hostBench.clock),hostBench.host,tx)
 private class StopProcess:Error("프로세스 중단 모사")
 private fun listed(job:String,instance:String="i-1"){hostBench.executions=HostCall.Ok(json.readTree("""{"instanceId":"$instance","executions":[{"executionId":"exec-1","jobOrderId":"$job"}]}"""))}
 private fun resolutions(c:StationCoordinator)=HoldResolutions(incidents.host,opsLog,requeryDelay=Duration.ZERO,clock=clock,rework=StationReworkGuard(requests,c))
 private fun rework(c:StationCoordinator)=resolutions(c).resolve(operator,"exec-1","rack-arrival","REWORK","i-1","재작업")
 /** A resource-managed run whose robot holds: REVIEW_REQUIRED and still owning the station. */
 private fun held(c:StationCoordinator):StationRun {
  port.physical=null;val run=enqueue();c.tick();assertEquals("RUNNING",c.get(run.runId).state)
  jdbc.sql("UPDATE ops.work_request SET required_station_id='inspection-1' WHERE receipt_id=:id").param("id",run.runId).update()
  port.physical="OPERATOR_HOLD";c.tick();assertEquals("REVIEW_REQUIRED",c.get(run.runId).state);assertEquals(run.runId,c.stations().single().owner)
  listed(run.runId.toString());return c.get(run.runId)
 }
 @Test fun `해제 뒤 다른 작업이 점유한 스테이션에서는 이전 작업의 재작업을 호스트에 넘기지 않는다`() {
  val c=coordinator();val r=held(c);decide(r.runId,"CONFIRM_CLEAR");port.physical=null
  val r2=enqueue();c.tick();idle(r2);assertEquals("RUNNING",c.get(r2.runId).state);assertEquals(r2.runId,c.stations().single().owner)
  val ex=assertFailsWith<RequestRejected>{rework(c)};assertEquals(409,ex.status);assertEquals(StationCoordinator.REWORK_HELD,ex.message)
  assertEquals(emptyList(),incidents.writes());assertEquals("MANUALLY_CLEARED",c.get(r.runId).state)
  assertEquals("RUNNING",c.get(r2.runId).state);assertEquals(r2.runId,c.stations().single().owner)
  // Confirming a hold moves nothing, so it is not gated.
  assertEquals("Resolved",resolutions(c).resolve(operator,"exec-1","rack-arrival","CONFIRM_DONE","i-1","현장 확인").outcome);assertEquals("CONFIRM_DONE",incidents.writes().single().decision)
 }
 @Test fun `점유 중이어도 확인 필요 상태가 아니면 재작업을 넘기지 않는다`() {
  val c=coordinator();port.physical=null;val run=enqueue();c.tick();assertEquals("RUNNING",c.get(run.runId).state)
  jdbc.sql("UPDATE ops.work_request SET required_station_id='inspection-1' WHERE receipt_id=:id").param("id",run.runId).update();listed(run.runId.toString())
  val ex=assertFailsWith<RequestRejected>{rework(c)};assertEquals(409,ex.status);assertEquals(StationCoordinator.REWORK_HELD,ex.message)
  assertEquals(emptyList(),incidents.writes());assertEquals("RUNNING",c.get(run.runId).state);assertEquals(run.runId,c.stations().single().owner)
 }
 @Test fun `확인 필요 상태여도 스테이션 점유가 없으면 재작업을 넘기지 않는다`() {
  val c=coordinator();val r=held(c)
  // Decisions never reach this (review keeps the owner); it isolates the owner check.
  jdbc.sql("UPDATE ops.rf_station SET owner=NULL WHERE station_id='inspection-1'").update()
  assertEquals(409,assertFailsWith<RequestRejected>{rework(c)}.status);assertEquals(emptyList(),incidents.writes());assertEquals("REVIEW_REQUIRED",c.get(r.runId).state)
 }
 @Test fun `점유를 유지한 확인 필요 작업의 재작업은 한 번 넘기고 재작업 뒤의 완료 근거만 인정한다`() {
  val c=coordinator();val r=held(c);val before=r.dispatchedAt!!
  now=now.plusSeconds(3);signal()
  var during:String?=null;var clear:Int?=null
  incidents.onResolve={during=service.get(r.runId).state;clear=runCatching{decide(r.runId,"CONFIRM_CLEAR")}.exceptionOrNull()?.let{(it as RequestRejected).status}}
  assertEquals("Resolved",rework(c).outcome);assertEquals(1,incidents.writes().size)
  assertEquals("REWORKING",during);assertEquals(409,clear)
  val after=c.get(r.runId);assertEquals("RUNNING",after.state);assertEquals(now,after.dispatchedAt);assertTrue(after.dispatchedAt!!.isAfter(before));assertEquals(r.runId,c.stations().single().owner)
  assertEquals(listOf("REVIEW_REQUIRED","REWORKING","RUNNING"),c.events(r.runId).takeLast(3).map{it["state"]})
  assertEquals(listOf("operator/kim","operator/kim"),c.events(r.runId).takeLast(2).map{it["actor"]})
  // The rework entered RUNNING with the completion signal off, so a later true is new whatever the field clock stamps on it.
  port.physical="PHYSICALLY_DONE";c.tick();assertEquals("RUNNING",c.get(r.runId).state)
  now=now.plusSeconds(1);signal(complete=true,at=now.minusSeconds(60));c.tick();assertEquals("COMPLETED",c.get(r.runId).state)
 }
 @Test fun `호스트가 재작업을 거절하거나 결과를 모르면 확인 필요로 되돌리고 점유를 유지한다`() {
  val c=coordinator();val r=held(c)
  incidents.resolveAnswer=HostWrite.Answered(200,IncidentBench.resolved(null,"NotHeld"))
  assertEquals(OperationResult.REJECTED,rework(c).result);assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertEquals("재작업 거부; 점유를 유지하고 현장 확인을 기다립니다",c.get(r.runId).reason)
  incidents.resolveAnswer=HostWrite.NoResponse("timeout");incidents.incidents=HostCall.Silent("down")
  assertNull(rework(c).confirmation);assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertEquals("재작업 반영 불명; 실행 관측과 현장 확인 전 점유 유지",c.get(r.runId).reason)
  incidents.onResolve={error("connection reset")};assertFailsWith<IllegalStateException>{rework(c)};assertEquals("REVIEW_REQUIRED",c.get(r.runId).state)
  assertEquals(3,incidents.writes().size);assertEquals(r.runId,c.stations().single().owner);assertEquals(r.dispatchedAt,c.get(r.runId).dispatchedAt)
 }
 @Test fun `건너뛴 대상 재작업도 같은 게이트를 지나고 호스트의 Refused 와 이빅션 NotHeld 는 확인 필요로 되돌리며 반영되면 다시 진행한다`() {
  val c=coordinator();val r=held(c);now=now.plusSeconds(3);signal()
  listOf("Refused" to "순회가 종결한 뒤 한 대상씩 재작업한다 — 아직 낼 단위나 도는 단위가 있다","NotHeld" to "EVICTED").forEach{(name,detail)->
   incidents.resolveAnswer=HostWrite.Answered(200,IncidentBench.resolved(null,name,detail))
   val outcome=resolutions(c).resolve(operator,"exec-1","T1","REWORK","i-1","건너뛴 대상 재작업")
   assertEquals(OperationResult.REJECTED,outcome.result);assertEquals(detail,outcome.answer!!["detail"].asText())
   assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertEquals("재작업 거부; 점유를 유지하고 현장 확인을 기다립니다",c.get(r.runId).reason);assertEquals(r.runId,c.stations().single().owner)
  }
  assertEquals(listOf("T1","T1"),incidents.writes().map{it.unitId})
  incidents.resolveAnswer=HostWrite.Answered(200,IncidentBench.resolved("incident-1"))
  assertEquals("Resolved",resolutions(c).resolve(operator,"exec-1","T1","REWORK","i-1","건너뛴 대상 재작업").outcome)
  assertEquals("RUNNING",c.get(r.runId).state);assertEquals(r.runId,c.stations().single().owner)
 }
 @Test fun `재작업이 반영돼도 설비 완료 신호가 켜져 있거나 신호를 모르면 확인 필요로 남긴다`() {
  val c=coordinator();val r=held(c)
  signal(complete=true);rework(c);assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertTrue(c.get(r.runId).reason.startsWith("재작업 반영; 설비 완료 신호가 켜져"))
  port.signals=null;rework(c);assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertTrue(c.get(r.runId).reason.startsWith("재작업 반영; 현장 신호 관측 불명"))
  assertEquals(r.dispatchedAt,c.get(r.runId).dispatchedAt);assertEquals(2,incidents.writes().size)
 }
 @Test fun `자원 관리가 아닌 실행과 접수 없는 실행과 다른 인스턴스의 재작업은 그대로 넘긴다`() {
  val c=coordinator()
  jdbc.sql("INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state) VALUES(:id,'mes','plain','hash','{}','JO-plain','ACCEPTED')").param("id",UUID.randomUUID()).update()
  listed("JO-plain");assertEquals("Resolved",rework(c).outcome)
  listed("JO-direct");assertEquals("Resolved",rework(c).outcome)
  // The host refuses a request naming another instance itself; instance ids never repeat.
  val r=held(c);listed(r.runId.toString(),instance="i-2");incidents.resolveAnswer=HostWrite.Answered(409,"""{"error":"INSTANCE_MISMATCH","detail":"다른 인스턴스"}""")
  assertEquals(OperationResult.REJECTED,rework(c).result);assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertEquals(r.revision,c.get(r.runId).revision)
  assertEquals(3,incidents.writes().size)
 }
 @Test fun `실행 목록을 못 읽거나 실행이 없으면 재작업을 넘기지 않는다`() {
  val c=coordinator();val r=held(c)
  hostBench.executions=HostCall.Silent("offline");assertEquals(503,assertFailsWith<RequestRejected>{rework(c)}.status)
  hostBench.executions=HostCall.Ok(json.readTree("""{"instanceId":"i-1","executions":[]}"""));assertEquals(409,assertFailsWith<RequestRejected>{rework(c)}.status)
  assertEquals(emptyList(),incidents.writes());assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertEquals(r.revision,c.get(r.runId).revision)
 }
 @Test fun `재작업 전달 중 프로세스가 멈추면 재기동 뒤 확인 필요로 넘긴다`() {
  val c=coordinator();val r=held(c)
  incidents.onResolve={throw StopProcess()}
  assertFailsWith<StopProcess>{rework(c)};assertEquals("REWORKING",c.get(r.runId).state)
  assertEquals(409,assertFailsWith<RequestRejected>{decide(r.runId,"CONFIRM_CLEAR")}.status)
  coordinator().tick();assertEquals("REVIEW_REQUIRED",c.get(r.runId).state);assertEquals(r.runId,c.stations().single().owner);assertEquals(r.dispatchedAt,c.get(r.runId).dispatchedAt)
  assertEquals(1,incidents.writes().size)
 }
 @Test fun `관측 재개는 재개 뒤의 완료 근거만 인정하고 설비 완료 신호가 켜져 있거나 모르면 거절한다`() {
  val run=enqueue();service.tick();port.physical="OPERATOR_HOLD";service.tick();val before=service.get(run.runId).dispatchedAt!!
  now=now.plusSeconds(3)
  signal(complete=true);assertEquals(409,assertFailsWith<RequestRejected>{decide(run.runId,"RESUME_OBSERVATION")}.status)
  port.signals=null;assertEquals(503,assertFailsWith<RequestRejected>{decide(run.runId,"RESUME_OBSERVATION")}.status)
  assertEquals("REVIEW_REQUIRED",service.get(run.runId).state)
  signal();decide(run.runId,"RESUME_OBSERVATION");assertEquals("RUNNING",service.get(run.runId).state);assertEquals(now,service.get(run.runId).dispatchedAt);assertTrue(now.isAfter(before))
  port.physical="PHYSICALLY_DONE";service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  now=now.plusSeconds(1);signal(complete=true,at=now.minusSeconds(60));service.tick();assertEquals("COMPLETED",service.get(run.runId).state)
 }

 @Test fun `재작업 뒤 이전 보류 결과로는 확인 필요로 되돌리지 않고 새 결과만 판단에 쓴다`() {
  val c=coordinator();val r=held(c);now=now.plusSeconds(3);signal();rework(c);assertEquals("RUNNING",c.get(r.runId).state)
  c.tick();c.tick();assertEquals("RUNNING",c.get(r.runId).state)
  result(r.runId,"OPERATOR_HOLD");c.tick();assertEquals("REVIEW_REQUIRED",c.get(r.runId).state)
  now=now.plusSeconds(1);rework(c);assertEquals("RUNNING",c.get(r.runId).state);c.tick();assertEquals("RUNNING",c.get(r.runId).state)
  result(r.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);signal(complete=true);c.tick();assertEquals("COMPLETED",c.get(r.runId).state)
 }
 @Test fun `관측 재개 뒤 이전 보류 결과로는 확인 필요로 되돌리지 않고 새 결과만 판단에 쓴다`() {
  val run=enqueue();service.tick();port.physical="OPERATOR_HOLD";service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state)
  now=now.plusSeconds(3);decide(run.runId,"RESUME_OBSERVATION");service.tick();service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  result(run.runId,"OPERATOR_HOLD");service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state)
  now=now.plusSeconds(1);decide(run.runId,"RESUME_OBSERVATION");service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  result(run.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(run.runId).state)
 }
 @Test fun `처음 실행은 전송 전에 저장된 결과를 판단에 쓰지 않는다`() {
  port.preResult="OPERATOR_HOLD";val held=enqueue();service.tick();service.tick();assertEquals("RUNNING",service.get(held.runId).state)
  result(held.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(held.runId).state)
  port.preResult="PHYSICALLY_DONE";signal();val done=enqueue();service.tick();assertEquals("RUNNING",service.get(done.runId).state)
  now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("RUNNING",service.get(done.runId).state)
  result(done.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(done.runId).state)
 }

 @Test fun `관측 재개는 같은 실행을 다시 보므로 확인 대기 중 저장된 물리 완료를 새 완료 신호와 함께 인정한다`() {
  val run=enqueue();service.tick();port.physical="OPERATOR_HOLD";service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state)
  result(run.runId,"PHYSICALLY_DONE");now=now.plusSeconds(3);decide(run.runId,"RESUME_OBSERVATION");service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  now=now.plusSeconds(1);signal(complete=true,at=now.minusSeconds(60));service.tick();assertEquals("COMPLETED",service.get(run.runId).state)
 }
 @Test fun `재작업은 재작업 전에 저장된 물리 완료를 완료 근거로 쓰지 않는다`() {
  val c=coordinator();val r=held(c);result(r.runId,"PHYSICALLY_DONE");now=now.plusSeconds(3);signal();rework(c);assertEquals("RUNNING",c.get(r.runId).state)
  now=now.plusSeconds(1);signal(complete=true);c.tick();assertEquals("RUNNING",c.get(r.runId).state)
  result(r.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);signal(complete=true);c.tick();assertEquals("COMPLETED",c.get(r.runId).state)
 }

 // 이전 작업의 늦은 설비 완료 신호(외부 검토 실험 A1). 현장 확인 해제는 실행을 멈추지 않아 X 쪽 설비가 Y 점유 중에 완료를 쓸 수 있다.
 // 작업 번호 송신·반환 신호가 있는 스테이션은 반환 번호로 가르고, 없는 스테이션은 보낸 작업을 해제하면 격리한다.
 private val completedReason="로봇 물리 완료와 이번 작업의 설비 완료 신호 확인; 자원 해제"
 private val clearedReason="현장 확인에 의한 자원 해제: 현장 상태 확인"
 private fun station()=service.stations().single()
 private fun echoing(){jdbc.sql("DELETE FROM ops.rf_station").update();service.createStation(engineer,json.readTree("""{"stationId":"inspection-1","name":"번호 검사","readySignal":"ready","completeSignal":"complete","timeoutSeconds":10,"jobSignal":"job","jobEchoSignal":"echo"}"""))}
 /** The station again with a run deadline of [timeout] seconds. */
 private fun plain(timeout:Int){jdbc.sql("DELETE FROM ops.rf_station").update();service.createStation(engineer,json.readTree("""{"stationId":"inspection-1","name":"공유 검사","readySignal":"ready","completeSignal":"complete","timeoutSeconds":$timeout}"""))}
 private fun echo(value:String){port.texts=port.texts+("echo" to StationText(value,now))}
 private fun context(id:UUID,state:String,reason:String?=null)=service.events(id).last{it["state"]==state && (reason==null || it["reason"]==reason)}["context"] as JsonNode
 private fun decideIdle(id:UUID,revision:Int=service.get(id).revision)=decide(id,"CONFIRM_STATION_IDLE",revision)
 /** X 를 보낸 뒤 실행 시간 초과 → 확인 필요 → 현장 확인 해제까지. */
 private fun clearedAfterTimeout():StationRun {
  val x=enqueue();service.tick();assertEquals("RUNNING",service.get(x.runId).state)
  now=now.plusSeconds(11);service.tick();assertEquals("REVIEW_REQUIRED",service.get(x.runId).state)
  decide(x.runId,"CONFIRM_CLEAR");assertEquals("MANUALLY_CLEARED",service.get(x.runId).state);assertNull(station().owner)
  return service.get(x.runId)
 }
 @Test fun `작업 번호 송신·반환 신호는 둘 다 연결하거나 둘 다 비우고 현장에 선언된 TEXT 신호여야 한다`() {
  jdbc.sql("DELETE FROM ops.rf_station").update()
  fun create(extra:String)=service.createStation(engineer,json.readTree("""{"stationId":"inspection-1","name":"번호 검사","readySignal":"ready","completeSignal":"complete","timeoutSeconds":10$extra}"""))
  fun refused(extra:String)=assertFailsWith<RequestRejected>{create(extra)}
  val plain=create(""","jobSignal":null,"jobEchoSignal":null""").single();assertNull(plain.jobSignal);assertNull(plain.jobEchoSignal);jdbc.sql("DELETE FROM ops.rf_station").update()
  for(extra in listOf(""","jobSignal":"job"""",""","jobEchoSignal":"echo"""",""","jobSignal":"job","jobEchoSignal":null""")) {val e=refused(extra);assertEquals(400,e.status,extra);assertEquals(StationCoordinator.JOB_PAIR,e.message,extra)}
  for(extra in listOf(""","jobSignal":"ready","jobEchoSignal":"echo"""",""","jobSignal":"job","jobEchoSignal":"missing"""")) {val e=refused(extra);assertEquals(400,e.status,extra);assertEquals(StationCoordinator.TEXT_REQUIRED,e.message,extra)}
  assertEquals(400,refused(""","jobSignal":"job","jobEchoSignal":"job"""").status);assertEquals(400,refused(""","jobSignal":"job","jobEchoSignal":"echo","extra":1""").status)
  assertEquals(emptyList(),service.stations())
  val st=create(""","jobSignal":"job","jobEchoSignal":"echo"""").single();assertEquals("job" to "echo",st.jobSignal to st.jobEchoSignal);assertNull(st.quarantinedBy)
 }
 @Test fun `작업 번호 반환 스테이션은 해제한 이전 작업의 늦은 완료 신호를 다음 작업의 완료로 채택하지 않고 확인 필요로 넘긴다`() {
  echoing();val x=clearedAfterTimeout();assertNull(station().quarantinedBy);assertEquals(false,context(x.runId,"MANUALLY_CLEARED")["quarantined"].asBoolean())
  now=now.plusSeconds(1);signal();val y=enqueue();service.tick()
  assertEquals("RUNNING",service.get(y.runId).state);assertEquals(2,port.sends);assertEquals(listOf("job" to x.runId.toString(),"job" to y.runId.toString()),port.writes)
  val dispatching=context(y.runId,"DISPATCHING");assertEquals(y.runId.toString(),dispatching["jobToken"].asText());assertEquals("job",dispatching["jobSignal"].asText())
  // X 쪽 설비가 늦게 X 의 번호와 완료를 쓴다. Y 의 로봇은 아직이다.
  now=now.plusSeconds(2);echo(x.runId.toString());signal(complete=true);service.tick()
  val review=service.get(y.runId);assertEquals("REVIEW_REQUIRED",review.state);assertEquals(StationCoordinator.ECHO_MISMATCH,review.reason);assertEquals(y.runId,station().owner)
  val seen=context(y.runId,"REVIEW_REQUIRED");assertEquals(y.runId.toString(),seen["expected"].asText());assertEquals(x.runId.toString(),seen["echoed"].asText())
  assertEquals(true,seen["signals"]["complete"]["value"].asBoolean());assertEquals(x.runId.toString(),seen["signals"]["echo"]["value"].asText())
  now=now.plusSeconds(1);result(y.runId,"PHYSICALLY_DONE");service.tick();assertEquals("REVIEW_REQUIRED",service.get(y.runId).state);assertEquals(y.runId,station().owner)
 }
 @Test fun `작업 번호 반환 스테이션은 반환 번호가 이전 값이면 로봇 완료 뒤에 온 완료 신호도 채택하지 않는다`() {
  echoing()
  val x=enqueue();service.tick();result(x.runId,"OPERATOR_HOLD");service.tick();assertEquals("REVIEW_REQUIRED",service.get(x.runId).state)
  decide(x.runId,"CONFIRM_CLEAR");assertNull(station().quarantinedBy)
  now=now.plusSeconds(1);val y=enqueue();service.tick();assertEquals("RUNNING",service.get(y.runId).state)
  now=now.plusSeconds(2);result(y.runId,"PHYSICALLY_DONE");service.tick();assertEquals("RUNNING",service.get(y.runId).state)
  now=now.plusSeconds(2);signal(complete=true);service.tick()
  assertEquals("REVIEW_REQUIRED",service.get(y.runId).state);assertEquals("",context(y.runId,"REVIEW_REQUIRED")["echoed"].asText())
 }
 @Test fun `작업 번호 반환 스테이션은 이번 작업 번호와 완료 신호와 로봇 완료가 모두 있어야 완료한다`() {
  echoing();val run=enqueue();service.tick();assertEquals("RUNNING",service.get(run.runId).state);assertEquals(run.runId.toString(),port.texts.getValue("job").value)
  // 번호만 돌아오고 완료 신호가 없거나, 번호와 완료 신호가 맞아도 로봇 완료 전이면 기다린다.
  now=now.plusSeconds(1);echo(run.runId.toString());service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  result(run.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);service.tick()
  val done=service.get(run.runId);assertEquals("COMPLETED",done.state);assertEquals(completedReason,done.reason);assertNull(station().owner)
  assertEquals(run.runId.toString(),context(run.runId,"COMPLETED")["signals"]["echo"]["value"].asText())
 }
 @Test fun `작업 번호 불일치로 확인 필요가 된 작업은 신호 초기화 뒤 관측을 재개하고 이번 번호로 완료한다`() {
  echoing();val x=clearedAfterTimeout();now=now.plusSeconds(1);signal();val y=enqueue();service.tick()
  now=now.plusSeconds(1);echo(x.runId.toString());signal(complete=true);service.tick();assertEquals("REVIEW_REQUIRED",service.get(y.runId).state)
  now=now.plusSeconds(1);signal();decide(y.runId,"RESUME_OBSERVATION");assertEquals("RUNNING",service.get(y.runId).state)
  result(y.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);echo(y.runId.toString());signal(complete=true);service.tick()
  assertEquals("COMPLETED",service.get(y.runId).state);assertEquals(2,port.sends)
 }
 @Test fun `정상 완료한 이전 작업의 완료 신호를 설비가 다시 써도 반환 번호가 다르면 다음 작업의 완료로 채택하지 않는다`() {
  echoing();val x=enqueue();service.tick();result(x.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);echo(x.runId.toString());signal(complete=true);service.tick()
  assertEquals("COMPLETED",service.get(x.runId).state);assertNull(station().quarantinedBy)
  now=now.plusSeconds(1);val y=enqueue();service.tick();service.tick();assertEquals("WAITING_READY",service.get(y.runId).state);assertEquals(1,port.sends)
  now=now.plusSeconds(1);signal();service.tick();assertEquals("RUNNING",service.get(y.runId).state)
  now=now.plusSeconds(1);signal(complete=true);result(y.runId,"PHYSICALLY_DONE");service.tick()
  assertEquals("REVIEW_REQUIRED",service.get(y.runId).state);assertEquals(x.runId.toString(),context(y.runId,"REVIEW_REQUIRED")["echoed"].asText())
 }
 @Test fun `작업 번호 쓰기에 실패하면 로봇을 보내지 않고 설비 준비 대기에 남아 다음 처리에서 다시 쓴다`() {
  echoing();port.writeFails=true;val run=enqueue();service.tick();service.tick()
  val waiting=service.get(run.runId);assertEquals("WAITING_READY",waiting.state);assertEquals(StationCoordinator.WRITE_FAILED,waiting.reason);assertEquals(0,port.sends);assertEquals(2,port.writes.size)
  assertEquals(1,service.events(run.runId).count{it["reason"]==StationCoordinator.WRITE_FAILED});assertEquals(run.runId.toString(),context(run.runId,"WAITING_READY",StationCoordinator.WRITE_FAILED)["jobToken"].asText())
  now=now.plusSeconds(3);port.writeFails=false;service.tick()
  assertEquals("RUNNING",service.get(run.runId).state);assertEquals(1,port.sends);assertEquals(run.runId.toString(),port.texts.getValue("job").value)
 }
 private fun robotPermit(robot:String,permit:String,cause:String="REPORTED",subject:String?="guard_closed=false",basis:String="안전 계통이 guard_closed 을 true 으로 되돌릴 때")=
  StationRobotPermit(robot,permit,listOf(cause),listOfNotNull(subject),listOf(basis))
 private val permitted=StationPermit.Read(listOf(robotPermit("humanoid-01","PERMITTED",subject="guard_closed=true"),robotPermit("quadruped-01","PERMITTED",subject="guard_closed=true")))
 @Test fun `작업 허가가 INHIBITED 면 작업 번호를 쓰지 않고 설비 준비 대기에 막은 기체와 주체와 풀릴 근거를 남기며 PERMITTED 가 되면 그때 쓴다`() {
  echoing();port.permit=StationPermit.Read(listOf(robotPermit("humanoid-01","INHIBITED"),robotPermit("quadruped-01","PERMITTED",subject="guard_closed=true")))
  val run=enqueue();service.tick();service.tick()
  val waiting=service.get(run.runId);assertEquals("WAITING_READY",waiting.state)
  assertEquals("${StationCoordinator.PERMIT_HELD}: humanoid-01 INHIBITED(guard_closed=false); 풀릴 근거: 안전 계통이 guard_closed 을 true 으로 되돌릴 때",waiting.reason)
  assertTrue(port.writes.isEmpty());assertEquals(0,port.sends)
  assertEquals(1,service.events(run.runId).count{it["reason"]==waiting.reason})
  assertEquals(listOf("humanoid-01","quadruped-01"),context(run.runId,"WAITING_READY",waiting.reason)["workPermits"].map{it["robotId"].asText()})
  // A permit block is a wait: the readiness deadline does not run out while it holds.
  now=now.plusSeconds(30);service.tick();assertEquals("WAITING_READY",service.get(run.runId).state);assertTrue(port.writes.isEmpty())
  port.permit=permitted;service.tick()
  val resumed=service.get(run.runId);assertEquals("WAITING_READY",resumed.state);assertEquals(StationCoordinator.PERMIT_RESUMED,resumed.reason);assertEquals(now,resumed.phaseAt)
  service.tick()
  assertEquals("RUNNING",service.get(run.runId).state);assertEquals(listOf("job" to run.runId.toString()),port.writes);assertEquals(1,port.sends)
 }
 @Test fun `작업 허가가 UNKNOWN 이거나 읽히지 않아도 작업 번호를 쓰지 않고 까닭을 남긴다`() {
  echoing();port.permit=StationPermit.Read(listOf(robotPermit("humanoid-01","UNKNOWN",cause="EXPIRED",subject="guard_closed=true",basis="원천 cell/guard_closed 가 유효한 관측을 다시 낼 때")))
  val run=enqueue();service.tick();service.tick()
  assertEquals("${StationCoordinator.PERMIT_HELD}: humanoid-01 UNKNOWN EXPIRED(guard_closed=true); 풀릴 근거: 원천 cell/guard_closed 가 유효한 관측을 다시 낼 때",service.get(run.runId).reason)
  port.permit=StationPermit.Read(listOf(robotPermit("humanoid-01","UNKNOWN",cause="NOT_COVERED",subject=null,basis="이 기체를 덮는 허가 원천이 생길 때")))
  service.tick();assertEquals("${StationCoordinator.PERMIT_HELD}: humanoid-01 UNKNOWN NOT_COVERED; 풀릴 근거: 이 기체를 덮는 허가 원천이 생길 때",service.get(run.runId).reason)
  // A host that answered but whose permit body cannot be read is a permit that is not known. A host that gave no answer is its own wait.
  port.permit=StationPermit.Unreadable("body");service.tick()
  assertEquals("${StationCoordinator.PERMIT_HELD}: 작업 허가를 읽지 못했습니다",service.get(run.runId).reason);assertEquals("WAITING_READY",service.get(run.runId).state)
  assertTrue(port.writes.isEmpty());assertEquals(0,port.sends)
 }
 @Test fun `호스트가 응답하지 않으면 허가를 대기한다고 하지 않고 호스트 응답 없음으로 작업 번호를 쓰지 않고 설비 준비 기한을 멈춘다`() {
  echoing();port.permitThrows=true
  val run=enqueue();service.tick();service.tick()
  val waiting=service.get(run.runId);assertEquals("WAITING_READY",waiting.state)
  assertEquals("${StationCoordinator.HOST_UNREACHABLE_HELD}: IllegalStateException",waiting.reason)
  assertFalse(waiting.reason.startsWith(StationCoordinator.PERMIT_HELD))
  assertTrue(port.writes.isEmpty());assertEquals(0,port.sends)
  assertEquals(1,service.events(run.runId).count{it["reason"]==waiting.reason})
  assertEquals("IllegalStateException",context(run.runId,"WAITING_READY",waiting.reason)["hostUnreachable"].asText())
  // The readiness deadline does not run out while the host is silent, and a host that is silent from the port itself reads the same.
  now=now.plusSeconds(30);port.permitThrows=false;port.permit=StationPermit.HostUnreachable("응답 없음: ConnectException");service.tick()
  assertEquals("WAITING_READY",service.get(run.runId).state);assertEquals("${StationCoordinator.HOST_UNREACHABLE_HELD}: 응답 없음: ConnectException",service.get(run.runId).reason)
  assertTrue(port.writes.isEmpty());assertEquals(0,port.sends)
 }
 @Test fun `호스트가 다시 응답하면 호스트 응답 복귀로 설비 준비 기한을 다시 세고 준비되면 작업 번호를 쓴다`() {
  echoing();port.permitThrows=true
  val run=enqueue();service.tick();service.tick()
  assertTrue(service.get(run.runId).reason.startsWith(StationCoordinator.HOST_UNREACHABLE_HELD))
  now=now.plusSeconds(30);port.permitThrows=false;service.tick()
  val resumed=service.get(run.runId);assertEquals("WAITING_READY",resumed.state);assertEquals(StationCoordinator.HOST_RESUMED,resumed.reason);assertEquals(now,resumed.phaseAt)
  assertEquals(1,service.events(run.runId).count{it["reason"]==StationCoordinator.HOST_RESUMED});assertTrue(port.writes.isEmpty())
  service.tick()
  assertEquals("RUNNING",service.get(run.runId).state);assertEquals(listOf("job" to run.runId.toString()),port.writes);assertEquals(1,port.sends)
 }
 @Test fun `호스트가 응답하지 않다 허가가 막는다고 돌아오면 사유만 바꿔 다시 대기하고 호스트가 허가와 함께 돌아오면 풀린다`() {
  echoing();port.permitThrows=true
  val run=enqueue();service.tick();service.tick()
  port.permitThrows=false;port.permit=StationPermit.Read(listOf(robotPermit("humanoid-01","INHIBITED"),robotPermit("quadruped-01","PERMITTED",subject="guard_closed=true")))
  val phaseAt=service.get(run.runId).phaseAt;now=now.plusSeconds(5);service.tick()
  val held=service.get(run.runId);assertEquals("WAITING_READY",held.state);assertTrue(held.reason.startsWith(StationCoordinator.PERMIT_HELD));assertEquals(phaseAt,held.phaseAt)
  port.permit=StationPermit.HostUnreachable("응답 없음: HttpTimeoutException");service.tick()
  assertTrue(service.get(run.runId).reason.startsWith(StationCoordinator.HOST_UNREACHABLE_HELD))
  port.permit=permitted;now=now.plusSeconds(5);service.tick()
  assertEquals(StationCoordinator.HOST_RESUMED,service.get(run.runId).reason)
  assertTrue(port.writes.isEmpty());assertEquals(0,port.sends)
 }
 @Test fun `작업 번호 신호가 없는 스테이션도 작업 허가가 막으면 작업 요청을 내지 않고 허가가 돌아오면 낸다`() {
  port.permit=StationPermit.Read(listOf(robotPermit("humanoid-01","INHIBITED")))
  val run=enqueue();service.tick();service.tick()
  assertEquals("WAITING_READY",service.get(run.runId).state);assertTrue(service.get(run.runId).reason.startsWith(StationCoordinator.PERMIT_HELD));assertEquals(0,port.sends)
  port.permit=permitted;service.tick();service.tick()
  assertEquals("RUNNING",service.get(run.runId).state);assertEquals(1,port.sends)
 }
 @Test fun `작업 허가 본문은 선언 없음과 기체마다의 판정과 못 읽음을 가른다`() {
  val read={body:String->permitOf(json.readTree(body))}
  assertEquals(StationPermit.Undeclared,read("""{"declared":false,"robots":[]}"""))
  val judged=read("""{"declared":true,"robots":[{"robotId":"humanoid-01","permit":"INHIBITED","reasons":[{"source":"cell/guard_closed","cause":"REPORTED","subject":"guard_closed=false","resumeBasis":"b","observedAt":"t"}]},
   {"robotId":"quadruped-01","permit":"PERMITTED","reasons":[{"source":"cell/guard_closed","cause":"REPORTED","subject":"guard_closed=true","resumeBasis":"c","observedAt":"t"}]}]}""") as StationPermit.Read
  assertEquals(listOf(StationRobotPermit("humanoid-01","INHIBITED",listOf("REPORTED"),listOf("guard_closed=false"),listOf("b"))),judged.blocking)
  for(bad in listOf("{}","""{"declared":true}""","""{"declared":true,"robots":[{"permit":"PERMITTED"}]}"""))assertIs<StationPermit.Unreadable>(read(bad),bad)
  assertIs<StationPermit.Unreadable>(permitOf(null))
 }
 @Test fun `작업 번호 쓰기 실패가 이어져도 설비 준비 기한은 그대로라 전송 전에 시간 초과로 해제한다`() {
  echoing();port.writeFails=true;val run=enqueue();service.tick();now=now.plusSeconds(6);service.tick();now=now.plusSeconds(5);service.tick()
  assertEquals("TIMED_OUT",service.get(run.runId).state);assertEquals(0,port.sends);assertNull(station().owner);assertNull(station().quarantinedBy)
 }
 @Test fun `작업 번호 반환이 없는 스테이션은 보낸 작업을 해제하면 격리하고 현장 확인 후 격리 해제 전에는 다음 작업을 보내지 않는다`() {
  val x=clearedAfterTimeout();assertEquals(x.runId,station().quarantinedBy);assertNotNull(station().quarantinedAt)
  val events=service.events(x.runId).takeLast(2);assertEquals(listOf(clearedReason,StationCoordinator.QUARANTINE),events.map{it["reason"]});assertEquals(listOf("operator/kim","operator/kim"),events.map{it["actor"]})
  val cleared=events[0]["context"] as JsonNode
  assertEquals(true,cleared["quarantined"].asBoolean());assertEquals(false,cleared["signals"]["complete"]["value"].asBoolean());assertTrue(cleared.has("physicalState"));assertTrue(cleared["physicalState"].isNull)
  assertEquals("inspection-1",(events[1]["context"] as JsonNode)["stationId"].asText())
  now=now.plusSeconds(1);val y=enqueue();service.tick();service.tick()
  val waiting=service.get(y.runId);assertEquals("WAITING_READY",waiting.state);assertEquals(StationCoordinator.QUARANTINED,waiting.reason);assertEquals(y.runId,station().owner);assertEquals(1,port.sends)
  assertEquals(1,service.events(y.runId).count{it["reason"]==StationCoordinator.QUARANTINED})
  // X 쪽 설비가 늦게 완료를 쓴다. Y 는 보내지 않았으므로 채택될 자리가 없다.
  now=now.plusSeconds(2);signal(complete=true);service.tick();assertEquals("WAITING_READY",service.get(y.runId).state);assertEquals(1,port.sends)
  assertEquals(409,assertFailsWith<RequestRejected>{decideIdle(y.runId)}.status)
  port.signals=null;assertEquals(503,assertFailsWith<RequestRejected>{decideIdle(y.runId)}.status);assertEquals(x.runId,station().quarantinedBy)
  now=now.plusSeconds(1);signal();decideIdle(y.runId)
  val idle=service.events(y.runId).last();assertEquals("현장 확인 후 격리 해제: 현장 상태 확인",idle["reason"]);assertEquals("operator/kim",idle["actor"])
  assertEquals(x.runId.toString(),(idle["context"] as JsonNode)["releasedRunId"].asText());assertEquals(false,(idle["context"] as JsonNode)["signals"]["complete"]["value"].asBoolean())
  assertNull(station().quarantinedBy);assertNull(station().quarantinedAt)
  service.tick();assertEquals("RUNNING",service.get(y.runId).state);assertEquals(2,port.sends)
  now=now.plusSeconds(1);result(y.runId,"PHYSICALLY_DONE");signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(y.runId).state);assertEquals(completedReason,service.get(y.runId).reason)
 }
 @Test fun `격리 중 설비 준비 기한은 멈추고 격리 해제 판단부터 다시 센다`() {
  val x=enqueue();service.tick();result(x.runId,"OPERATOR_HOLD");service.tick();decide(x.runId,"CONFIRM_CLEAR")
  assertEquals("OPERATOR_HOLD",context(x.runId,"MANUALLY_CLEARED",clearedReason)["physicalState"].asText());assertEquals(x.runId,station().quarantinedBy)
  now=now.plusSeconds(1);val y=enqueue();service.tick();now=now.plusSeconds(60);service.tick()
  assertEquals("WAITING_READY",service.get(y.runId).state);assertEquals(y.runId,station().owner);assertEquals(1,port.sends)
  signal(ready=false);decideIdle(y.runId);now=now.plusSeconds(9);service.tick();assertEquals("WAITING_READY",service.get(y.runId).state)
  now=now.plusSeconds(1);service.tick();assertEquals("TIMED_OUT",service.get(y.runId).state);assertEquals(1,port.sends);assertNull(station().owner);assertNull(station().quarantinedBy)
 }
 @Test fun `응답 불명으로 해제한 작업도 호스트에 닿았을 수 있어 스테이션을 격리한다`() {
  port.unknown=true;val x=enqueue();service.tick();assertEquals("REVIEW_REQUIRED",service.get(x.runId).state);assertNotNull(service.get(x.runId).dispatchedAt)
  decide(x.runId,"CONFIRM_CLEAR");port.unknown=false;assertEquals(x.runId,station().quarantinedBy)
  now=now.plusSeconds(1);val y=enqueue();service.tick();assertEquals("WAITING_READY",service.get(y.runId).state);assertEquals(StationCoordinator.QUARANTINED,service.get(y.runId).reason);assertEquals(1,port.sends)
 }
 @Test fun `보내지 않은 작업의 종료와 계약 거부는 스테이션을 격리하지 않는다`() {
  val cancelled=enqueue();decide(cancelled.runId,"CANCEL_QUEUED");assertEquals("CANCELLED",service.get(cancelled.runId).state)
  signal(ready=false);val timed=enqueue();service.tick();now=now.plusSeconds(11);service.tick();assertEquals("TIMED_OUT",service.get(timed.runId).state)
  signal();port.receiptState="REJECTED";val rejected=enqueue();service.tick();assertEquals("REJECTED",service.get(rejected.runId).state)
  assertNull(station().quarantinedBy);assertNull(station().owner)
  port.receiptState="ACCEPTED";val next=enqueue();service.tick();assertEquals("RUNNING",service.get(next.runId).state)
 }
 @Test fun `전송 전 미배정으로 가용 기체를 기다리다 기한이 지나 해제한 작업은 스테이션을 격리하지 않는다`() {
  // 가용 기체가 없어 호스트에 보내지 않았다. 설비 완료 신호가 켜져 있어 재평가도 하지 않은 채 기한이 지난다.
  port.receiptState="UNASSIGNED";val x=enqueue();service.tick();assertEquals("WAITING_ROBOT",service.get(x.runId).state)
  signal(complete=true);now=now.plusSeconds(11);service.tick();assertEquals("REVIEW_REQUIRED",service.get(x.runId).state);assertEquals(0,port.retries)
  decide(x.runId,"CONFIRM_CLEAR");assertEquals("MANUALLY_CLEARED",service.get(x.runId).state);assertNull(station().owner)
  assertNull(station().quarantinedBy);assertEquals(false,context(x.runId,"MANUALLY_CLEARED")["quarantined"].asBoolean())
  assertEquals(0,service.events(x.runId).count{it["reason"]==StationCoordinator.QUARANTINE})
  now=now.plusSeconds(1);signal();port.receiptState="ACCEPTED";val y=enqueue();service.tick();assertEquals("RUNNING",service.get(y.runId).state);assertEquals(2,port.sends)
 }
 @Test fun `재평가 결과가 불명이어도 작업 요청이 보내지 않은 거부로 끝났으면 해제가 스테이션을 격리하지 않는다`() {
  port.receiptState="UNASSIGNED";val x=enqueue();service.tick();assertEquals("WAITING_ROBOT",service.get(x.runId).state)
  // 재평가가 호스트를 부르기 전에 작업 요청이 거부로 닫히고 호출은 실패한다(예: 자원 요구가 바뀌어 보내지 않음).
  port.retryLost="REJECTED";now=now.plusSeconds(5);service.tick()
  val held=service.get(x.runId);assertEquals("REVIEW_REQUIRED",held.state);assertEquals("재평가 결과 불명; 점유 유지",held.reason);assertEquals(1,port.retries)
  decide(x.runId,"CONFIRM_CLEAR");assertEquals("MANUALLY_CLEARED",service.get(x.runId).state);assertNull(station().quarantinedBy)
 }
 @Test fun `해제 때 작업 요청을 읽지 못하면 보내지 않았다고 단정하지 않고 스테이션을 격리한다`() {
  port.receiptState="UNASSIGNED";val x=enqueue();service.tick();signal(complete=true);now=now.plusSeconds(11);service.tick()
  assertEquals("REVIEW_REQUIRED",service.get(x.runId).state)
  port.unsentFails=true;decide(x.runId,"CONFIRM_CLEAR");assertEquals(x.runId,station().quarantinedBy)
  assertEquals(true,context(x.runId,"MANUALLY_CLEARED")["quarantined"].asBoolean())
 }
 @Test fun `격리 해제 판단은 격리된 스테이션을 점유한 설비 준비 대기 작업에서 운영자가 조회 버전으로 한다`() {
  signal(ready=false);val run=enqueue();assertEquals(409,assertFailsWith<RequestRejected>{decideIdle(run.runId)}.status)
  service.tick();assertEquals("WAITING_READY",service.get(run.runId).state);assertEquals(409,assertFailsWith<RequestRejected>{decideIdle(run.runId)}.status)
  signal();service.tick();now=now.plusSeconds(11);service.tick();decide(run.runId,"CONFIRM_CLEAR");assertEquals(run.runId,station().quarantinedBy)
  val y=enqueue();service.tick();val revision=service.get(y.runId).revision
  assertEquals(409,assertFailsWith<RequestRejected>{decideIdle(y.runId,revision-1)}.status)
  assertEquals(403,assertFailsWith<RequestRejected>{service.decide(y.runId,engineer,json.readTree("""{"expectedRevision":$revision,"decision":"CONFIRM_STATION_IDLE","reason":"확인"}"""))}.status)
  assertEquals(run.runId,station().quarantinedBy);assertEquals("WAITING_READY",service.get(y.runId).state)
 }
 @Test fun `정상 완료는 격리하지 않고 이전 신호가 초기화되기 전에는 다음 작업을 보내지 않는다`() {
  val x=enqueue();service.tick();result(x.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(x.runId).state);assertNull(station().quarantinedBy)
  now=now.plusSeconds(1);val y=enqueue();service.tick();service.tick();assertEquals("WAITING_READY",service.get(y.runId).state);assertEquals(1,port.sends)
  now=now.plusSeconds(1);signal();service.tick();assertEquals("RUNNING",service.get(y.runId).state);assertEquals(2,port.sends)
  // 한계: 반환 번호가 없으면 정상 완료한 X 의 설비가 완료를 다시 쓰는 것과 Y 의 완료를 가르지 못한다(반환 스테이션은 위 시험이 가른다).
  now=now.plusSeconds(1);signal(complete=true);now=now.plusSeconds(1);result(y.runId,"PHYSICALLY_DONE");service.tick()
  assertEquals("COMPLETED",service.get(y.runId).state)
 }
 @Test fun `현장 시계가 운영 시계보다 늦어도 전송 뒤 새로 켜진 완료 신호로 완료한다`() {
  val run=enqueue();service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  // 현장 시계가 50초 늦어 신호의 observedAt 이 전송 시각보다 앞선다. 전송은 완료 false 에서만 했으므로 이 true 는 새 신호다.
  now=now.plusSeconds(2);result(run.runId,"PHYSICALLY_DONE");signal(complete=true,at=now.minusSeconds(50));service.tick()
  assertEquals("COMPLETED",service.get(run.runId).state)
  assertTrue(Instant.parse(context(run.runId,"COMPLETED")["signals"]["complete"]["observedAt"].asText()).isBefore(service.get(run.runId).dispatchedAt))
 }
 @Test fun `실행 진입 전에 읽은 신호는 그 실행의 완료 근거로 쓰지 않는다`() {
  val run=enqueue();service.tick();port.physical="OPERATOR_HOLD";service.tick();assertEquals("REVIEW_REQUIRED",service.get(run.runId).state);result(run.runId,"PHYSICALLY_DONE")
  // 처리 주기가 완료 true 를 읽은 직후 운영자가 신호를 내리고 관측을 재개한다. 그 주기의 읽기는 재개보다 앞선다(운영 시계).
  now=now.plusSeconds(1);signal(complete=true)
  port.afterRead={now=now.plusSeconds(1);signal();decide(run.runId,"RESUME_OBSERVATION")}
  service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(run.runId).state)
 }
 @Test fun `전송 전 미배정 재평가도 설비 완료 신호가 꺼져 있어야 실행으로 들어간다`() {
  port.receiptState="UNASSIGNED";val run=enqueue();service.tick();assertEquals("WAITING_ROBOT",service.get(run.runId).state)
  now=now.plusSeconds(5);signal(complete=true);service.tick();service.tick();assertEquals("WAITING_ROBOT",service.get(run.runId).state);assertEquals(0,port.retries)
  signal();service.tick();assertEquals("RUNNING",service.get(run.runId).state);assertEquals(1,port.retries)
 }
 /** tick 은 잠금 없이 읽은 작업으로 먼저 판단한다. 그 뒤에 바뀐 작업은 쓰기 전에 잠금 아래에서 다시 읽어 그 작업으로 판단한다(HA 설계 기록 §10). */
 @Test fun `tick 이 읽은 뒤 다른 쪽이 바꾼 작업은 완료를 쓰기 전에 잠금 아래에서 다시 판단해 바뀐 상태를 덮지 않는다`() {
  val run=enqueue();service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  port.physical="PHYSICALLY_DONE";now=now.plusSeconds(1);signal(complete=true)
  // 다른 인스턴스가 tick 의 읽기 뒤에 확인 필요로 넘긴다.
  port.onReceipt={jdbc.sql("UPDATE ops.station_run SET state='REVIEW_REQUIRED',reason='다른 쪽 판단',revision=revision+1 WHERE run_id=:id").param("id",run.runId).update()}
  service.tick()
  val after=service.get(run.runId)
  assertEquals(listOf("REVIEW_REQUIRED","다른 쪽 판단"),listOf(after.state,after.reason));assertEquals(run.runId,service.stations().single().owner)
  assertEquals(0L,jdbc.sql("SELECT count(*) FROM ops.station_event WHERE run_id=:id AND state='COMPLETED'").param("id",run.runId).query(Long::class.java).single())
 }
 /** 잠그고 쓴 스테이션(대기열 점유, 제출)은 트랜잭션과 제출 뒤 작업을 다시 읽어, 같은 tick 안에서 새 상태로 관측한다. */
 @Test fun `제출한 tick 안에서 다시 읽은 작업을 관측해 정착한 비성공 결과를 그 tick 에 확인 필요로 넘긴다`() {
  port.physical="FAILED";val run=enqueue();service.tick()
  val after=service.get(run.runId)
  assertEquals(listOf("REVIEW_REQUIRED","로봇 FAILED; 자동 재실행 없이 점유 유지"),listOf(after.state,after.reason));assertEquals(1,port.sends)
 }

 // 작업 허가 차단(ADR 52)은 기다림이다. 실행의 단위가 막힌 동안 실행 기한은 멈추고, 풀리면 그때부터 다시 센다(설비 준비 대기와 같은 규칙).
 private val block get()=json.readTree("""{"unitId":"T1.travel","withheld":"START","robots":[{"robotId":"humanoid-01","permit":"INHIBITED","reasons":[]}],"since":"2026-10-12T00:00:02Z"}""")
 @Test fun `실행 단위가 작업 허가로 막힌 동안 실행 기한은 멈추고 허가가 돌아오면 그때부터 다시 센다`() {
  val run=enqueue();service.tick();val entered=service.get(run.runId).phaseAt
  now=now.plusSeconds(2);port.permitBlock=block;service.tick()
  val held=service.get(run.runId);assertEquals(listOf("RUNNING",StationCoordinator.RUN_PERMIT_HELD),listOf(held.state,held.reason));assertEquals(entered,held.phaseAt)
  assertEquals(block,context(run.runId,"RUNNING",StationCoordinator.RUN_PERMIT_HELD)["permitBlock"])
  now=now.plusSeconds(30);service.tick();service.tick()
  assertEquals("RUNNING",service.get(run.runId).state);assertEquals(1,service.events(run.runId).count{it["reason"]==StationCoordinator.RUN_PERMIT_HELD})
  port.permitBlock=null;service.tick()
  val resumed=service.get(run.runId);assertEquals(listOf("RUNNING",StationCoordinator.RUN_PERMIT_RESUMED),listOf(resumed.state,resumed.reason));assertEquals(now,resumed.phaseAt)
  now=now.plusSeconds(9);service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  now=now.plusSeconds(1);service.tick();assertEquals(listOf("REVIEW_REQUIRED",StationCoordinator.RUN_TIMED_OUT),service.get(run.runId).let{listOf(it.state,it.reason)})
 }
 @Test fun `실행을 읽지 못해도 실행 기한은 흘러 확인 필요로 넘긴다`() {
  val run=enqueue();service.tick();port.receiptFails=true;now=now.plusSeconds(9);service.tick();assertEquals("RUNNING",service.get(run.runId).state)
  now=now.plusSeconds(1);service.tick();assertEquals(listOf("REVIEW_REQUIRED",StationCoordinator.RUN_TIMED_OUT),service.get(run.runId).let{listOf(it.state,it.reason)})
 }
 @Test fun `기한이 지난 처리 주기에 로봇 완료와 새 완료 신호가 함께 보이면 확인 필요가 아니라 완료로 판정한다`() {
  val run=enqueue();service.tick();now=now.plusSeconds(10);result(run.runId,"PHYSICALLY_DONE");signal(complete=true);service.tick()
  assertEquals("COMPLETED",service.get(run.runId).state);assertNull(station().owner)
 }
 @Test fun `작업 허가 차단이 풀려 기한을 다시 세도 차단 중 저장된 로봇 완료는 이번 실행의 완료 근거로 남는다`() {
  val run=enqueue();service.tick();now=now.plusSeconds(1);port.permitBlock=block;service.tick()
  result(run.runId,"PHYSICALLY_DONE");service.tick();assertEquals(StationCoordinator.RUN_PERMIT_HELD,service.get(run.runId).reason)
  now=now.plusSeconds(1);port.permitBlock=null;service.tick();assertEquals(StationCoordinator.RUN_PERMIT_RESUMED,service.get(run.runId).reason)
  now=now.plusSeconds(1);signal(complete=true);service.tick();assertEquals("COMPLETED",service.get(run.runId).state)
 }
 // 기한 초과나 신호 관측 불명으로 확인 필요가 된 작업의 정상 완료. 설비 완료 신호가 켜져 있으면 관측 재개는 409 라, 증명된 완료를 운영자가 받는 판단이다.
 @Test fun `기한 초과로 확인 필요가 된 작업은 로봇 물리 완료와 켜진 설비 완료 신호가 있으면 운영자 완료 확인으로 완료한다`() {
  val run=enqueue();service.tick();now=now.plusSeconds(11);service.tick()
  assertEquals(listOf("REVIEW_REQUIRED",StationCoordinator.RUN_TIMED_OUT),service.get(run.runId).let{listOf(it.state,it.reason)})
  fun refused(status:Int,message:String)=assertFailsWith<RequestRejected>{decide(run.runId,"CONFIRM_COMPLETION")}.let{assertEquals(status to message,it.status to it.message)}
  now=now.plusSeconds(1);signal(complete=true);refused(409,StationCoordinator.COMPLETION_ROBOT_NOT_DONE)
  result(run.runId,"PHYSICALLY_DONE");assertEquals(409,assertFailsWith<RequestRejected>{decide(run.runId,"RESUME_OBSERVATION")}.status)
  signal();refused(409,StationCoordinator.COMPLETION_SIGNAL_OFF)
  port.signals=null;refused(503,StationCoordinator.COMPLETION_SIGNALS_UNREAD)
  assertEquals("REVIEW_REQUIRED",service.get(run.runId).state)
  signal(complete=true);val done=decide(run.runId,"CONFIRM_COMPLETION")
  assertEquals(listOf("COMPLETED","${StationCoordinator.COMPLETION_CONFIRMED}: 현장 상태 확인"),listOf(done.state,done.reason));assertNull(station().owner);assertNull(station().quarantinedBy)
  val seen=context(run.runId,"COMPLETED");assertEquals("PHYSICALLY_DONE",seen["physicalState"].asText());assertEquals(true,seen["signals"]["complete"]["value"].asBoolean())
  assertEquals("operator/kim",service.events(run.runId).last()["actor"]);assertEquals(1,port.sends)
 }
 @Test fun `작업 번호 반환 스테이션의 완료 확인은 반환 번호가 이번 번호여야 하고 판단한 결함으로 확인 필요가 된 작업은 받지 않는다`() {
  echoing();val x=enqueue();service.tick();now=now.plusSeconds(11);service.tick();assertEquals("REVIEW_REQUIRED",service.get(x.runId).state)
  result(x.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);signal(complete=true);echo("other")
  val mismatch=assertFailsWith<RequestRejected>{decide(x.runId,"CONFIRM_COMPLETION")};assertEquals(409 to StationCoordinator.COMPLETION_ECHO_MISMATCH,mismatch.status to mismatch.message)
  echo(x.runId.toString());assertEquals("COMPLETED",decide(x.runId,"CONFIRM_COMPLETION").state)
  assertEquals(x.runId.toString(),context(x.runId,"COMPLETED")["echoed"].asText())
  now=now.plusSeconds(1);signal();val y=enqueue();service.tick();assertEquals("RUNNING",service.get(y.runId).state)
  result(y.runId,"OPERATOR_HOLD");service.tick();assertEquals("REVIEW_REQUIRED",service.get(y.runId).state)
  result(y.runId,"PHYSICALLY_DONE");now=now.plusSeconds(1);echo(y.runId.toString());signal(complete=true)
  val held=assertFailsWith<RequestRejected>{decide(y.runId,"CONFIRM_COMPLETION")};assertEquals(409 to StationCoordinator.COMPLETION_NOT_REVIEWABLE,held.status to held.message)
  assertEquals("REVIEW_REQUIRED",service.get(y.runId).state);assertEquals(y.runId,station().owner)
 }
}
