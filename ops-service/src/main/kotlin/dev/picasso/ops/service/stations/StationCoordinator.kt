package dev.picasso.ops.service.stations

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.instance.OpsInstance
import dev.picasso.ops.service.requests.RequestRejected
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * [jobSignal]/[jobEchoSignal]: optional TEXT pair. Ops writes the run token before dispatch and the equipment returns it with its completion.
 * [quarantinedBy]: a dispatched run released on site confirmation at a station without the pair. Its execution may still raise the completion signal.
 */
data class RfStation(val stationId:String,val name:String,val readySignal:String,val completeSignal:String,val timeoutSeconds:Int,val owner:UUID?,val createdBy:String,val createdAt:String,val jobSignal:String?=null,val jobEchoSignal:String?=null,val quarantinedBy:UUID?=null,val quarantinedAt:String?=null)
/** [instanceId]: the ops instance that last wrote the run's state (HA design record §8 stage 0). Null for runs written before V21. */
data class StationRun(val runId:UUID,val stationId:String,val payload:JsonNode,val actor:String,val state:String,val reason:String,val receiptId:UUID?,val attempt:Int,val revision:Int,val createdAt:Instant,val phaseAt:Instant,val dispatchedAt:Instant?,val resultBaseline:Int?=null,val baselineReviewOnly:Boolean=false,val instanceId:String?=null)
/**
 * Durable station arbitration. Database locks cover decisions, never network calls.
 * [instance]: this process (HA design record §8 stage 0). Every state write records its id; a RETRYING/REWORKING claim left by another
 * instance is taken only once that instance's heartbeat expired. One coordinator per instance: the claim-in-flight argument is this monitor.
 */
class StationCoordinator(private val jdbc:JdbcClient,private val tx:TransactionTemplate,private val port:StationPort,private val clock:Clock=Clock.systemUTC(),private val instance:OpsInstance=OpsInstance(jdbc)) {
 private val json=ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
 fun stations():List<RfStation> = jdbc.sql("SELECT * FROM ops.rf_station ORDER BY station_id").query(::stationRow).list()
 fun runs():List<StationRun> = jdbc.sql("SELECT * FROM ops.station_run ORDER BY created_at DESC,run_id LIMIT 100").query(::row).list()
 fun get(id:UUID):StationRun = jdbc.sql("SELECT * FROM ops.station_run WHERE run_id=:id").param("id",id).query(::row).optional().orElseThrow{RequestRejected("RUN_NOT_FOUND","스테이션 작업이 없습니다",404)}
 fun events(id:UUID):List<Map<String,Any?>> {get(id);return jdbc.sql("SELECT * FROM ops.station_event WHERE run_id=:id ORDER BY event_id").param("id",id).query {r,_->mapOf("state" to r.getString("state"),"reason" to r.getString("reason"),"actor" to r.getString("actor"),"recordedAt" to r.getString("recorded_at"),"context" to json.readTree(r.getString("context")))}.list()}
 /** Keep the state revision and its event history in one station-locked read. */
 fun snapshot(id:UUID):Map<String,Any> = tx.execute {
  lock(get(id).stationId)
  mapOf("run" to get(id),"events" to events(id))
 }!!
 fun createStation(actor:Actor,p:JsonNode):List<RfStation> {
  if(actor.mode!=Mode.ENGINEER) reject("엔지니어만 자원을 등록합니다",403)
  val required=setOf("stationId","name","readySignal","completeSignal","timeoutSeconds");val fields=p.fieldNames().asSequence().toSet()
  if(!p.isObject || !fields.containsAll(required) || !(required+JOB_FIELDS).containsAll(fields))reject("요청 필드를 확인하세요")
  val id=text(p,"stationId",64);val name=text(p,"name",120);val ready=text(p,"readySignal",120);val complete=text(p,"completeSignal",120)
  if(JOB_FIELDS.count{p[it]?.isNull==false}==1)reject(JOB_PAIR)
  val job=p["jobSignal"]?.takeUnless{it.isNull}?.let{text(p,"jobSignal",120)};val echo=p["jobEchoSignal"]?.takeUnless{it.isNull}?.let{text(p,"jobEchoSignal",120)}
  val timeout=p["timeoutSeconds"]
  if(!id.matches(Regex("[a-z][a-z0-9-]{0,63}")) || ready==complete || job!=null && job==echo || timeout?.isIntegralNumber!=true || !timeout.canConvertToInt() || timeout.asInt() !in 5..3600) reject("식별자·서로 다른 신호·5~3600초 기한이 필요합니다")
  val signals=port.read() ?: reject("현장 신호를 확인할 수 없습니다",503)
  if(ready !in signals.flags || complete !in signals.flags) reject("현재 현장에 선언된 일반 BOOLEAN 신호만 연결할 수 있습니다")
  if(job!=null && (job !in signals.texts || echo !in signals.texts)) reject(TEXT_REQUIRED)
  val n=jdbc.sql("INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by,job_signal,job_echo_signal) VALUES(:id,:name,:ready,:complete,:timeout,:actor,:job,:echo) ON CONFLICT DO NOTHING").param("id",id).param("name",name).param("ready",ready).param("complete",complete).param("timeout",timeout.asInt()).param("actor",actor.header()).param("job",job).param("echo",echo).update()
  if(n==0)reject("이미 등록된 스테이션입니다",409)
  return stations()
 }
 fun enqueue(actor:Actor,p:JsonNode):StationRun {
  if(actor.mode!=Mode.OPERATOR)reject("운영자만 작업을 요청합니다",403)
  exact(p,setOf("requestId","stationId","interfaceId","interfaceVersion","expectedDeploymentVersion","input"))
  val id=uuid(text(p,"requestId",36));val station=text(p,"stationId",64)
  text(p,"interfaceId",64)
  for(key in listOf("interfaceVersion","expectedDeploymentVersion")) if(p[key]?.isIntegralNumber!=true || !p[key].canConvertToInt() || p[key].asInt()<1)reject("양의 정수 계약·배포 버전이 필요합니다")
  if(p["input"]?.isObject!=true)reject("input 객체가 필요합니다")
  // Replays are resolved before live deployment validation, including after publication changes.
  val old=jdbc.sql("SELECT run_id FROM ops.station_run WHERE run_id=:id").param("id",id).query(UUID::class.java).optional().orElse(null)
  if(old!=null)return replay(id,p,actor)
  port.validate(p)
  return tx.execute {
   lock(station)
   val count=jdbc.sql("INSERT INTO ops.station_run(run_id,station_id,payload,actor,state,reason,created_at,phase_at,instance_id) VALUES(:id,:station,CAST(:payload AS JSONB),:actor,'QUEUED','공유 자원 순서를 기다립니다',:at,:at,:instance) ON CONFLICT DO NOTHING").param("id",id).param("station",station).param("payload",p.toString()).param("actor",actor.header()).param("at",java.sql.Timestamp.from(clock.instant())).param("instance",instance.instanceId).update()
   if(count==0) replay(id,p,actor) else {val run=get(id);event(run,"대기열 등록",actor.header());run}
  }!!
 }
 private fun replay(id:UUID,p:JsonNode,actor:Actor):StationRun {val run=get(id);if(run.payload!=p || run.actor!=actor.header())reject("같은 요청 ID에 다른 입력·요청자가 있습니다",409);return run}
 @Synchronized fun tick() {
  // Ops clock time of this read. Only a read taken after a run (re)entered RUNNING may speak for that run.
  val readAt=clock.instant();val signals=runCatching{port.read()}.getOrNull()
  // Read outside every lock, like the signals. A permit that cannot be read is no permit. A read that threw got no answer: host unreachable.
  // Only the exception class: a message that varies per tick would write a new hold event each tick.
  val permit=runCatching{port.permit()}.getOrElse{StationPermit.HostUnreachable(it.javaClass.simpleName)}
  // Every station with its owner run in one unlocked read. A quiet station takes no lock; any other is judged under its lock (HA design record §10).
  val board=board()
  // The execution reads of this tick cover the receipts it observes; a dispatch in it is followed by a new read (HA design record §10).
  port.observing(board.mapNotNull{it.run?.takeIf{run->run.state in OBSERVED}?.receiptId}) { for(entry in board) {
   val station=entry.station;val quiet=quiet(entry)
   val next=if(quiet) null else tx.execute {
    var current=lock(station.stationId)
    if(current.owner==null) {
     val queued=jdbc.sql("SELECT run_id FROM ops.station_run WHERE station_id=:station AND state='QUEUED' ORDER BY created_at,run_id LIMIT 1").param("station",station.stationId).query(UUID::class.java).optional().orElse(null)
     if(queued!=null) {jdbc.sql("UPDATE ops.rf_station SET owner=:id WHERE station_id=:station").param("id",queued).param("station",station.stationId).update();transition(get(queued),"WAITING_READY",if(current.quarantinedBy!=null)QUARANTINED else "자원 확보; 설비 준비와 완료 신호 초기화를 기다립니다");current=current.copy(owner=queued)}
    }
    val run=current.owner?.let(::get) ?: return@execute null
    if(run.state=="WAITING_READY") {
     // Quarantine pauses the readiness deadline: the run waits for the on-site decision, never times out into the next queued run.
     if(current.quarantinedBy!=null) {hold(run,QUARANTINED);return@execute null}
     // Owner decision (ADR 52): the job number is never written, nor the work request sent, while the work permit of the robots this station
     // may send is not PERMITTED. The readiness deadline is paused like a quarantine: a permit block is a wait, never a failure.
     val held=permitHeld(permit)
     if(held!=null) {hold(run,held.first,held.second);return@execute null}
     // Back to PERMITTED: a new phase, the readiness deadline starts again. The next tick judges readiness.
     if(run.reason.startsWith(PERMIT_HELD)) {transition(run,"WAITING_READY",PERMIT_RESUMED,context=permitContext(permit));return@execute null}
     // The host answers again and nothing blocks: the same new phase, named for what came back (the host, not a permit).
     if(run.reason.startsWith(HOST_UNREACHABLE_HELD)) {transition(run,"WAITING_READY",HOST_RESUMED,context=permitContext(permit));return@execute null}
     if(expired(run,current)) {transition(run,"TIMED_OUT","설비 준비 시간 초과; 로봇 전송 전 자원 해제",release=true);return@execute null}
     if(signals?.flags?.get(current.readySignal)?.value==true && signals.flags[current.completeSignal]?.value==false) {
      // The job number is written outside the lock first; DISPATCHING is claimed only after the site holds it.
      if(current.jobSignal!=null)return@execute run
      transition(run,"DISPATCHING","설비 준비 확인; 고정 계약을 제출합니다",dispatch=true,context=snapshot(signals))
      return@execute get(run.runId)
     }
    }
    // observe(), rework() and confirmSource() hold this monitor across their host call, so a claim seen here is not in flight in this instance.
    if(run.state in LEFTOVER_STOPPED.keys) leftover(run)
    // The RUNNING deadline is judged in observe(): the execution read there shows whether a work permit block stops it.
    if(run.state=="WAITING_ROBOT" && expired(run,current)) transition(run,"REVIEW_REQUIRED",RUN_TIMED_OUT)
    null
   }
   val dispatch=if(next?.state=="WAITING_READY") numbered(next,signals) else next
   if(dispatch!=null) dispatch(dispatch)
   // A quiet station wrote nothing this tick, so its run is the one on the board. Any other is read again after its transaction and dispatch.
   val run=if(quiet) entry.run ?: continue else get(station(station.stationId).owner ?: continue)
   // A previous process may have stopped after committing DISPATCHING. Same-key receipt lookup is safe, blind retry is not.
   if(run.state=="DISPATCHING" && dispatch==null) dispatch(run)
   if(run.state in OBSERVED) observe(run,station,signals,readAt)
  }}
 }
 /** A station with its owner run, and for a station without one whether a run queues for it, as one unlocked read saw them. */
 private class Board(val station:RfStation,val run:StationRun?,val queued:Boolean)
 private fun board():List<Board> = jdbc.sql("""SELECT s.*,${RUN_COLUMNS.joinToString(","){"r.$it AS r_$it"}},
   CASE WHEN s.owner IS NULL THEN EXISTS(SELECT 1 FROM ops.station_run q WHERE q.station_id=s.station_id AND q.state='QUEUED') ELSE false END AS queued
   FROM ops.rf_station s LEFT JOIN ops.station_run r ON r.run_id=s.owner ORDER BY s.station_id""")
  .query{r,i->Board(stationRow(r,i),r.getObject("r_run_id",UUID::class.java)?.let{runRow(r,"r_")},r.getBoolean("queued"))}.list()
 /**
  * True when tick's transaction would write nothing and hand nothing on for [entry] (HA design record §10): no owner and nothing queued, an
  * owner run in review, a RUNNING owner run (its every write is observe's), or a WAITING_ROBOT owner run before its deadline. Read without
  * locks: every write that follows judges the run again under the station lock, and a run that changed since the read is the next tick's.
  */
 private fun quiet(entry:Board):Boolean {
  val run=entry.run
  return if(entry.station.owner==null) !entry.queued else run!=null && (run.state in setOf("REVIEW_REQUIRED","RUNNING") || run.state=="WAITING_ROBOT" && !expired(run,entry.station))
 }
 /**
  * A RETRYING/REWORKING claim found by tick (HA design record §7, §8 stage 0). Left by this instance it is a failed cleanup: the claim and
  * its host call run under this monitor. Left by another instance it is a stopped process only once that instance's heartbeat expired or
  * it recorded its stop; a live instance's claim is left alone. Either way review keeps the station and sends nothing.
  */
 private fun leftover(run:StationRun) {
  val reason=when {
   run.instanceId==instance.instanceId -> LEFTOVER_OWN.getValue(run.state)
   instance.isLive(run.instanceId) -> return
   else -> LEFTOVER_STOPPED.getValue(run.state)
  }
  transition(run,"REVIEW_REQUIRED",reason,context=json.createObjectNode().put("claimedBy",run.instanceId))
 }
 /** Writes the run token to the job-number signal outside locks. A failed write keeps WAITING_READY and the next tick writes again. */
 private fun numbered(run:StationRun,signals:StationSignals?):StationRun? {
  val name=station(run.stationId).jobSignal!!;val token=run.runId.toString()
  val written=runCatching{port.write(name,token,actor(run))}.getOrDefault(false)
  return tx.execute {
   val station=lock(run.stationId);val current=get(run.runId)
   if(current.state!="WAITING_READY" || current.revision!=run.revision || station.owner!=run.runId || station.quarantinedBy!=null)return@execute null
   if(!written) {hold(current,WRITE_FAILED,json.createObjectNode().put("jobSignal",name).put("jobToken",token));return@execute null}
   transition(current,"DISPATCHING","설비 준비 확인; 고정 계약을 제출합니다",dispatch=true,context=json.createObjectNode().put("jobSignal",name).put("jobToken",token).set("signals",snapshot(signals)))
   get(run.runId)
  }
 }
 private fun dispatch(run:StationRun) {
  val receipt=runCatching {port.dispatch(run.runId,run.payload,actor(run))}.getOrNull();val unsent=unsent(receipt)
  tx.execute {lock(run.stationId);val current=get(run.runId);if(current.state!="DISPATCHING")return@execute
   if(receipt==null) transition(current,"REVIEW_REQUIRED","호출 결과 불명; 새 요청을 보내지 않고 점유 유지")
   else {jdbc.sql("UPDATE ops.station_run SET receipt_id=:receipt WHERE run_id=:id").param("receipt",receipt.id).param("id",run.runId).update();received(get(run.runId),receipt,unsent)}
  }
 }
 /**
  * True when [receipt] is a REJECTED the port proves never sent ([StationPort.unsent], the host's withdrawal, B3). Asked outside every lock,
  * before the transaction that judges [receipt] in [received]. Anything else, a failed read included, is no proof.
  */
 private fun unsent(receipt:StationReceipt?):StationProof? = if(receipt?.state!="REJECTED") null else
  runCatching{port.proof(receipt.id,"system/station",WITHDRAW_REASON)}.getOrDefault(StationProof.UNPROVEN)
 /** Judges [receipt] for [run]. [unsent]: what the port proved about a REJECTED [receipt] ([unsent]); only a proof of never sent releases it on its own. */
 private fun received(run:StationRun,receipt:StationReceipt,unsent:StationProof?) {when {
  receipt.state=="ACCEPTED" -> transition(run,"RUNNING",RUN_WAITING,context=receipt.snapshot)
  receipt.safeToRetry -> transition(run,"WAITING_ROBOT","전송 전 미배정; 가용 기체를 재평가합니다")
  // Never re-evaluated on a timer: the same cell answers the same way, and a retry loop would hide that a person must confirm the source.
  receipt.sourceUnobserved -> transition(run,"REVIEW_REQUIRED",SOURCE_UNOBSERVED)
  receipt.state=="REJECTED" && unsent==StationProof.NEVER_SENT -> transition(run,"REJECTED",receipt.detail?.takeIf{it.isNotBlank()}?.let{"계약 제출 거부: $it; 자원 해제"} ?: "계약 제출 거부; 자원 해제",release=true)
  // A rejection the host did not confirm withdrawing may still run (an approved remedy, B2/B3): keep the station for a person.
  receipt.state=="REJECTED" && unsent==StationProof.EXECUTION_EXISTS -> transition(run,"REVIEW_REQUIRED",REJECTED_BUT_EXECUTED,context=receipt.snapshot)
  receipt.state=="REJECTED" -> transition(run,"REVIEW_REQUIRED",REJECTED_UNCONFIRMED,context=receipt.snapshot)
  else -> transition(run,"REVIEW_REQUIRED","작업 요청 ${receipt.state}; 실행 여부 확인 전 점유 유지")
 }}
 private fun observe(run:StationRun,station:RfStation,signals:StationSignals?,readAt:Instant) {
  // Null when the receipt could not be read. RUNNING is still judged on its deadline and its signals then.
  val receipt=run.receiptId?.let{runCatching{port.receipt(it)}.getOrNull()}
  if(run.state=="WAITING_ROBOT") {
   receipt ?: return
   if(!receipt.safeToRetry) {val unsent=unsent(receipt);tx.execute{lock(run.stationId);if(get(run.runId).state==run.state)received(get(run.runId),receipt,unsent)};return}
   if(signals?.flags?.get(station.readySignal)?.value!=true || signals.flags[station.completeSignal]?.value!=false)return
   if(run.attempt>=3 || clock.instant().isBefore(run.phaseAt.plusSeconds(5)))return
   // Claim a retry before calling out. Crashes at this boundary require review, never another retry.
   val claimed=tx.execute{lock(run.stationId);val r=get(run.runId);if(r.state!="WAITING_ROBOT" || r.revision!=run.revision)false else {jdbc.sql("UPDATE ops.station_run SET attempt=attempt+1 WHERE run_id=:id").param("id",r.runId).update();transition(get(r.runId),"RETRYING","호스트 전송 전 미배정의 제한 재평가");true}}!!
   if(claimed) {
    val next=runCatching{port.retryForRun(receipt,UUID.nameUUIDFromBytes("${run.runId}/${run.attempt+1}".toByteArray()),actor(run),run.runId)}.getOrNull();val unsent=unsent(next)
    tx.execute{lock(run.stationId);val r=get(run.runId);if(r.state=="RETRYING")if(next==null)transition(r,"REVIEW_REQUIRED","재평가 결과 불명; 점유 유지") else received(r,next,unsent)}
   }
   return
  }
  // Judged first on the run tick read. Only a judgment that writes takes the station lock, and it is judged again there on the locked run.
  if(running(run,station,signals,readAt,receipt)==null)return
  tx.execute {
   lock(run.stationId);val current=get(run.runId);if(current.state!="RUNNING")return@execute
   running(current,station,signals,readAt,receipt)?.invoke()
  }
 }
 /**
  * The write the observation of RUNNING [current] makes, or null when it writes nothing. [receipt] is null when it could not be read.
  * Completion evidence and judged faults come first, then a work permit block (a wait: the deadline does not run), then the deadline, then
  * an unread cell. A read that fails is a transient fault for [SIGNAL_GRACE]: the run is neither completed nor failed on it and the deadline
  * keeps running. A read that succeeds without the station's declared signals is the site's declaration, not a lost read: reviewed at once.
  */
 private fun running(current:StationRun,station:RfStation,signals:StationSignals?,readAt:Instant,receipt:StationReceipt?):(()->Unit)? {
  val echo=station.jobEchoSignal
  val read=signals?.takeIf{it.flags[station.readySignal]!=null && it.flags[station.completeSignal]!=null && (echo==null || it.texts[echo]!=null)}
  if(signals!=null && read==null) return {transition(current,"REVIEW_REQUIRED",SIGNALS_UNOBSERVED)}
  // A result stored before this attempt (re)entered RUNNING is the previous attempt's: keep waiting, the timeout still applies.
  // A resume re-observes the same execution, so a completion stored during review still counts; a stale hold does not.
  val fresh=(receipt?.resultVersion?:0)>(current.resultBaseline?:0);val physical=receipt?.physicalState?.takeIf{fresh}
  if(read!=null) {
   // Every (re)entry to RUNNING required the completion signal off. A true read after that entry is new; one read before it is not judged.
   // A permit resume moves the phase clock without an entry: the signal was off at the entry before it and the station stayed this run's.
   // Both instants are on the ops clock. The signal's own observedAt is the field clock and is not compared.
   val on=read.flags.getValue(station.completeSignal).value && !readAt.isBefore(current.phaseAt)
   val token=current.runId.toString();val echoed=echo?.let{read.texts.getValue(it).value}
   if(on && echo!=null && echoed!=token) return {transition(current,"REVIEW_REQUIRED",ECHO_MISMATCH,context=json.createObjectNode().put("expected",token).put("echoed",echoed).set("signals",snapshot(read)))}
   if(receipt!=null && receipt.physicalState=="PHYSICALLY_DONE" && (fresh || current.baselineReviewOnly) && on)
    return {transition(current,"COMPLETED","로봇 물리 완료와 이번 작업의 설비 완료 신호 확인; 자원 해제",release=true,context=json.createObjectNode().apply{set<JsonNode>("execution",receipt.snapshot?:json.nullNode());set<JsonNode>("signals",snapshot(read))})}
  }
  if(physical in REVIEW_PHYSICAL_STATES) return {transition(current,"REVIEW_REQUIRED","로봇 $physical; 자동 재실행 없이 점유 유지",context=receipt?.snapshot)}
  // Same rule as WAITING_READY on the work permit (ADR 52): a unit held on the permit is a wait, never a failure. The deadline does not run
  // while the execution read shows the block and starts again once a read shows none. An unread execution is no block: the deadline runs.
  val block=receipt?.permitBlock
  if(block!=null) return if(current.reason==RUN_PERMIT_HELD) null else {{hold(current,RUN_PERMIT_HELD,json.createObjectNode().set("permitBlock",block))}}
  if(current.reason==RUN_PERMIT_HELD) return {restart(current,RUN_PERMIT_RESUMED)}
  if(expired(current,station)) return {transition(current,"REVIEW_REQUIRED",RUN_TIMED_OUT)}
  if(read==null) {
   if(current.reason!=SIGNALS_GRACE) return {hold(current,SIGNALS_GRACE)}
   // The grace counts from this run's first unread tick: the event the hold recorded. Kept in the database, so a restart does not reset it.
   val since=graceSince(current) ?: return null
   return if(clock.instant().isBefore(since.plus(SIGNAL_GRACE))) null else {{transition(current,"REVIEW_REQUIRED",SIGNALS_UNOBSERVED,context=json.createObjectNode().put("unreadSince",since.toString()))}}
  }
  if(current.reason==SIGNALS_GRACE) return {hold(current,RUN_WAITING)}
  return null
 }
 /** When [run] last entered its [SIGNALS_GRACE] hold, on the ops clock. */
 private fun graceSince(run:StationRun):Instant? = jdbc.sql("SELECT max(recorded_at) FROM ops.station_event WHERE run_id=:id AND reason=:reason")
  .param("id",run.runId).param("reason",SIGNALS_GRACE).query{r,_->r.getTimestamp(1)?.toInstant()}.single()
 fun decide(id:UUID,actor:Actor,p:JsonNode):StationRun {
  // Signals and the released run's stored result are read before the station lock, judged under it.
  val asked=p.path("decision").asText()
  if(asked=="CONFIRM_CLEAR")return clear(id,actor,p)
  val signals=if(actor.mode==Mode.OPERATOR && asked in SIGNAL_DECISIONS) runCatching{port.read()}.getOrNull() else null
  val released=if(actor.mode==Mode.OPERATOR && asked=="CONFIRM_COMPLETION") runCatching{get(id).receiptId?.let(port::receipt)}.getOrNull() else null
  // Nothing was sent for a source-unobserved run: resuming observation would only wait out the deadline and hide the pending confirmation.
  val unobserved=actor.mode==Mode.OPERATOR && asked=="RESUME_OBSERVATION" && runCatching{get(id).receiptId?.let(port::receipt)?.sourceUnobserved}.getOrNull()==true
  return tx.execute {
  if(actor.mode!=Mode.OPERATOR)reject("운영자만 확인합니다",403)
  exact(p,setOf("expectedRevision","decision","reason"))
  val run=get(id);lock(run.stationId);val current=get(id)
  if(p["expectedRevision"]?.isIntegralNumber!=true || p["expectedRevision"].asLong()!=current.revision.toLong())reject("작업 상태가 바뀌었습니다. 다시 조회하세요",409)
  val decision=text(p,"decision",32);val reason=text(p,"reason",1000)
  when(decision) {
   "CANCEL_QUEUED" -> {if(current.state!="QUEUED")reject("대기열 작업만 취소할 수 있습니다",409);transition(current,"CANCELLED",reason,by=actor.header())}
   "RESUME_OBSERVATION" -> {if(current.state!="REVIEW_REQUIRED" || current.receiptId==null)reject("작업 요청이 있는 확인 필요 작업만 관측을 재개합니다",409)
    if(unobserved)reject(SOURCE_HELD,409)
    // Only completion evidence newer than the resume counts, and a completion signal still on cannot be told apart from this run's.
    val complete=signals?.flags?.get(station(current.stationId).completeSignal) ?: reject("현장 신호를 확인할 수 없어 관측을 재개하지 않습니다",503)
    if(complete.value)reject("설비 완료 신호가 켜져 있습니다. 신호를 초기화한 뒤 관측을 재개하십시오",409)
    transition(current,"RUNNING",reason,dispatch=true,by=actor.header(),reviewOnly=true)}
   // A run reviewed on its deadline or on unseen signals whose normal completion is now proven: the robot evidence of a RUNNING completion
   // and the completion signal on, at a station with the job-number pair this run's number echoed. Its completion signal still on is why
   // RESUME_OBSERVATION refuses. A person's decision because a station without the pair cannot tell whose completion the signal is.
   "CONFIRM_COMPLETION" -> {if(current.state!="REVIEW_REQUIRED" || current.receiptId==null || current.reason !in CONFIRMABLE_REVIEWS)reject(COMPLETION_NOT_REVIEWABLE,409)
    val stored=released ?: reject(COMPLETION_RESULT_UNREAD,503)
    val fresh=(stored.resultVersion?:0)>(current.resultBaseline?:0)
    if(stored.physicalState!="PHYSICALLY_DONE" || !(fresh || current.baselineReviewOnly))reject(COMPLETION_ROBOT_NOT_DONE,409)
    val station=station(current.stationId);val token=current.runId.toString()
    val complete=signals?.flags?.get(station.completeSignal);val echoed=station.jobEchoSignal?.let{signals?.texts?.get(it)}
    if(complete==null || station.jobEchoSignal!=null && echoed==null)reject(COMPLETION_SIGNALS_UNREAD,503)
    if(!complete.value)reject(COMPLETION_SIGNAL_OFF,409)
    if(echoed!=null && echoed.value!=token)reject(COMPLETION_ECHO_MISMATCH,409)
    val context=json.createObjectNode().apply{set<JsonNode>("execution",stored.snapshot?:json.nullNode());set<JsonNode>("signals",snapshot(signals));put("receiptId",current.receiptId.toString())
     put("physicalState",stored.physicalState);put("resultVersion",stored.resultVersion);put("review",current.reason);if(echoed!=null){put("expected",token);put("echoed",echoed.value)}}
    transition(current,"COMPLETED","$COMPLETION_CONFIRMED: $reason",release=true,by=actor.header(),context=context)}
   "CONFIRM_STATION_IDLE" -> {val station=station(current.stationId)
    if(station.quarantinedBy==null || station.owner!=current.runId || current.state!="WAITING_READY")reject("격리된 스테이션을 점유해 설비 준비를 기다리는 작업만 격리를 해제합니다",409)
    val complete=signals?.flags?.get(station.completeSignal) ?: reject("현장 신호를 확인할 수 없어 격리를 해제하지 않습니다",503)
    if(complete.value)reject("설비 완료 신호가 켜져 있습니다. 신호가 꺼진 것을 확인한 뒤 격리를 해제하십시오",409)
    jdbc.sql("UPDATE ops.rf_station SET quarantined_by=NULL,quarantined_at=NULL WHERE station_id=:station").param("station",station.stationId).update()
    // A new phase: the readiness deadline starts again from the on-site decision.
    transition(current,"WAITING_READY","$IDLE_CONFIRMED: $reason",by=actor.header(),context=json.createObjectNode().put("releasedRunId",station.quarantinedBy.toString()).put("quarantinedAt",station.quarantinedAt).set("signals",snapshot(signals)))}
   else -> reject("지원하지 않는 판단입니다")
  };get(id)
 }!!
 }
 /**
  * CONFIRM_CLEAR: an operator releases a run in review after checking the site. The body is judged in full before anything else, so a
  * request refused with 400 never withdraws a job order. The run is then claimed (CLEARING, revision up) in a short station-locked
  * transaction at the revision the person saw: from then on every other decision, a source confirmation and a rework refuse it with 409,
  * here and in another instance. Only after the claim does the port ask the host to withdraw the job order (B3, outside every lock, under
  * this monitor like [rework] and [confirmSource]); the withdrawal is the only proof of never sent. The claim then ends in MANUALLY_CLEARED:
  * quarantined unless proven unsent, as before. A failure after the claim returns the run to the same review (revision up) and rethrows; a
  * claim left by a stopped process is a [leftover], reviewed again.
  */
 @Synchronized fun clear(id:UUID,actor:Actor,p:JsonNode):StationRun {
  if(actor.mode!=Mode.OPERATOR)reject("운영자만 확인합니다",403)
  exact(p,setOf("expectedRevision","decision","reason"))
  if(text(p,"decision",32)!="CONFIRM_CLEAR")reject("지원하지 않는 판단입니다")
  val reason=text(p,"reason",1000)
  if(p["expectedRevision"]?.isIntegralNumber!=true)reject("작업 상태가 바뀌었습니다. 다시 조회하세요",409)
  // Signals and the released run's stored result are read before the station lock, as for every decision.
  val signals=runCatching{port.read()}.getOrNull()
  val released=runCatching{get(id).receiptId?.let(port::receipt)}.getOrNull()
  val claimed=tx.execute {
   val run=get(id);lock(run.stationId);val current=get(id)
   if(p["expectedRevision"].asLong()!=current.revision.toLong())reject("작업 상태가 바뀌었습니다. 다시 조회하세요",409)
   if(current.state!="REVIEW_REQUIRED")reject("확인 필요 작업만 현장 확인 후 해제합니다",409)
   transition(current,"CLEARING",CLEARING,by=actor.header(),baseline=false);get(id)
  }!!
  try {
   // A work request closed unsent proves no robot got it; one not proven proves nothing. Read after the released read, which may record an execution.
   val unsent=claimed.receiptId?.let{receipt->runCatching{port.unsent(receipt,actor.header(),"$CLEAR_WITHDRAW_REASON: $reason")}.getOrDefault(false)} ?: false
   return tx.execute {
    lock(claimed.stationId);val current=get(id)
    if(current.state!="CLEARING" || current.revision!=claimed.revision)reject("작업 상태가 바뀌었습니다. 다시 조회하세요",409)
    // Release never stops the execution. Without the job-number pair a late completion cannot be told apart, so a run that may have reached
    // a robot quarantines the station: dispatched, and its work request not proven unsent.
    val station=station(current.stationId);val quarantine=current.dispatchedAt!=null && station.jobSignal==null && !unsent
    val context=json.createObjectNode().apply{set<JsonNode>("signals",snapshot(signals));put("receiptId",current.receiptId?.toString());put("physicalState",released?.physicalState);put("resultVersion",released?.resultVersion);put("quarantined",quarantine)}
    transition(current,"MANUALLY_CLEARED","현장 확인에 의한 자원 해제: $reason",release=true,by=actor.header(),context=context);cleared(current,reason,unsent)
    if(quarantine) {jdbc.sql("UPDATE ops.rf_station SET quarantined_by=:id,quarantined_at=:at WHERE station_id=:station").param("id",current.runId).param("at",java.sql.Timestamp.from(clock.instant())).param("station",station.stationId).update()
     event(get(id),QUARANTINE,actor.header(),context.deepCopy().put("stationId",station.stationId))}
    get(id)
   }!!
  } catch(e:Exception) {
   runCatching{tx.execute{lock(claimed.stationId);val r=get(id);if(r.state=="CLEARING")transition(r,"REVIEW_REQUIRED",CLEAR_FAILED,by=actor.header(),baseline=false)}}.onFailure(e::addSuppressed)
   throw e
  }
 }
 /**
  * REWORK re-runs a held unit, so the robot moves again. Only the run still owning its station in review may forward it.
  * The claim (REWORKING) blocks other decisions; the host call runs under this monitor, never under a DB lock.
  * [applied]: true applied, false refused, null unknown. Anything but a confirmed rework returns to review.
  */
 @Synchronized fun <T> rework(receiptId:UUID,actor:Actor,context:JsonNode,forward:()->T,applied:(T)->Boolean?):T {
  val claimed=tx.execute {
   val run=jdbc.sql("SELECT * FROM ops.station_run WHERE receipt_id=:id ORDER BY created_at,run_id LIMIT 1").param("id",receiptId).query(::row).optional().orElse(null) ?: reject(REWORK_HELD,409)
   lock(run.stationId);val current=get(run.runId)
   if(station(current.stationId).owner!=current.runId || current.state!="REVIEW_REQUIRED")reject(REWORK_HELD,409)
   transition(current,"REWORKING","재작업 전달 중; 반영 확인 전 다른 판단을 막습니다",by=actor.header(),context=context);get(current.runId)
  }!!
  val outcome=try {forward()} catch(e:Exception) {runCatching{reworked(claimed,null,actor,context)}.onFailure(e::addSuppressed);throw e}
  reworked(claimed,applied(outcome),actor,context);return outcome
 }
 /**
  * A person's source confirmation for a run held on a source-unobserved host answer. The run must still own its station in review and the
  * station must be ready with its completion signal off, as for any dispatch. The claim (RETRYING) blocks other decisions; the host call
  * runs under this monitor, never under a DB lock. A refused confirmation returns the run to the same review.
  */
 @Synchronized fun confirmSource(id:UUID,actor:Actor,payload:JsonNode):StationReceipt {
  if(actor.mode!=Mode.OPERATOR)reject("운영자만 출발 자리 확인을 기록합니다",403)
  port.checkSourceConfirmation(payload)
  val receipt=get(id).receiptId?.let{runCatching{port.receipt(it)}.getOrNull()}
  val signals=runCatching{port.read()}.getOrNull()
  val claimed=tx.execute {
   val run=get(id);lock(run.stationId);val current=get(id);val station=station(current.stationId)
   if(station.owner!=current.runId || current.state!="REVIEW_REQUIRED" || receipt?.sourceUnobserved!=true)reject(SOURCE_HELD,409)
   val ready=signals?.flags?.get(station.readySignal);val complete=signals?.flags?.get(station.completeSignal)
   if(ready==null || complete==null)reject("현장 신호를 확인할 수 없어 다시 제출하지 않습니다",503)
   if(!ready.value || complete.value)reject("설비 준비와 완료 신호 초기화를 확인한 뒤 출발 자리 확인을 기록하십시오",409)
   transition(current,"RETRYING",SOURCE_CONFIRMED,by=actor.header(),context=snapshot(signals));get(id)
  }!!
  val next=try {port.confirmSourceForRun(receipt!!,actor,payload,claimed.runId)} catch(e:Exception) {
   runCatching{tx.execute{lock(claimed.stationId);val r=get(id);if(r.state=="RETRYING")transition(r,"REVIEW_REQUIRED",if(e is RequestRejected)SOURCE_UNOBSERVED else "출발 자리 확인 뒤 재제출 결과 불명; 점유 유지",by=actor.header())}}.onFailure(e::addSuppressed)
   throw e
  }
  val unsent=unsent(next)
  tx.execute{lock(claimed.stationId);val r=get(id);if(r.state=="RETRYING")received(r,next,unsent)}
  return next
 }
 private fun reworked(run:StationRun,applied:Boolean?,actor:Actor,context:JsonNode) {
  val signals=if(applied==true) runCatching{port.signals()}.getOrNull() else null
  tx.execute {
   lock(run.stationId);val current=get(run.runId);if(current.state!="REWORKING")return@execute
   val station=station(run.stationId);val complete=signals?.get(station.completeSignal)
   when {
    applied==false -> transition(current,"REVIEW_REQUIRED","재작업 거부; 점유를 유지하고 현장 확인을 기다립니다",by=actor.header(),context=context)
    applied==null -> transition(current,"REVIEW_REQUIRED","재작업 반영 불명; 실행 관측과 현장 확인 전 점유 유지",by=actor.header(),context=context)
    signals?.get(station.readySignal)==null || complete==null -> transition(current,"REVIEW_REQUIRED","재작업 반영; 현장 신호 관측 불명이라 점유를 유지하고 현장 확인을 기다립니다",by=actor.header(),context=context)
    complete.value -> transition(current,"REVIEW_REQUIRED","재작업 반영; 설비 완료 신호가 켜져 있어 이번 재작업의 완료와 구분할 수 없습니다. 신호 초기화 후 관측을 재개하십시오",by=actor.header(),context=context)
    // Only a robot completion and a completion signal newer than this rework count.
    else -> transition(current,"RUNNING","재작업 반영; 재작업 뒤의 로봇 완료와 새 설비 완료 신호를 기다립니다",dispatch=true,by=actor.header(),context=context)
   }
  }
 }
 /** One station by key: a decision never re-reads the whole list (HA design record §10). */
 private fun station(id:String):RfStation = jdbc.sql("SELECT * FROM ops.rf_station WHERE station_id=:id").param("id",id).query(::stationRow).optional().orElseThrow{NoSuchElementException("스테이션이 없습니다: $id")}
 /** The hold reason and its context when [permit] holds a station, else null. The reason carries no time: a changed reason is a new event. */
 private fun permitHeld(permit:StationPermit):Pair<String,JsonNode>? = when(permit) {
  StationPermit.Undeclared -> null
  is StationPermit.Unreadable -> "$PERMIT_HELD: 작업 허가를 읽지 못했습니다" to json.createObjectNode().put("permitUnreadable",permit.cause)
  is StationPermit.HostUnreachable -> "$HOST_UNREACHABLE_HELD: ${permit.cause}" to json.createObjectNode().put("hostUnreachable",permit.cause)
  is StationPermit.Read -> permit.blocking.takeIf{it.isNotEmpty()}?.let{blocking->
   val robots=blocking.joinToString(", "){r->r.robotId+" "+r.permit+r.causes.filter{it!="REPORTED"}.joinToString(""){" $it"}+(r.subjects.takeIf{it.isNotEmpty()}?.joinToString(", ",prefix="(",postfix=")")?:"")}
   "$PERMIT_HELD: $robots; 풀릴 근거: ${blocking.flatMap{it.resumeBasis}.distinct().joinToString(", ")}" to permitContext(permit)
  }
 }
 private fun permitContext(permit:StationPermit):JsonNode = when(permit) {
  is StationPermit.Read -> json.createObjectNode().set("workPermits",json.valueToTree(permit.robots))
  else -> json.nullNode()
 }
 /** Name → {value, observedAt} for BOOLEAN and TEXT signals of one read. */
 private fun snapshot(signals:StationSignals?):JsonNode=signals?.let{json.valueToTree<ObjectNode>(it.flags).setAll<JsonNode>(json.valueToTree<ObjectNode>(it.texts))} ?: json.nullNode()
 /** Keeps the state and its phase clock (a waiting deadline is not reset), records a changed reason once. */
 private fun hold(run:StationRun,reason:String,context:JsonNode?=null) {
  if(run.reason==reason)return
  jdbc.sql("UPDATE ops.station_run SET reason=:reason,revision=revision+1,instance_id=:instance WHERE run_id=:id").param("reason",reason).param("instance",instance.instanceId).param("id",run.runId).update()
  event(get(run.runId),reason,"system/station",context)
 }
 /** Keeps the state, its result baseline and dispatch time, records [reason] and starts the phase clock again: a paused deadline counts from now. */
 private fun restart(run:StationRun,reason:String) {
  jdbc.sql("UPDATE ops.station_run SET reason=:reason,revision=revision+1,phase_at=:at,instance_id=:instance WHERE run_id=:id").param("reason",reason)
   .param("at",java.sql.Timestamp.from(clock.instant())).param("instance",instance.instanceId).param("id",run.runId).update()
  event(get(run.runId),reason,"system/station")
 }
 private fun expired(run:StationRun,station:RfStation)=!clock.instant().isBefore(run.phaseAt.plusSeconds(station.timeoutSeconds.toLong()))
 /** Every (re)entry to RUNNING records the receipt's latest stored result version: only newer results speak for this attempt. */
 private fun transition(run:StationRun,state:String,reason:String,release:Boolean=false,dispatch:Boolean=false,by:String="system/station",context:JsonNode?=null,baseline:Boolean=state=="RUNNING",reviewOnly:Boolean=false) {
  jdbc.sql("""UPDATE ops.station_run SET state=:state,reason=:reason,revision=revision+1,phase_at=:at,dispatched_at=CASE WHEN :dispatch THEN :at ELSE dispatched_at END,
   result_baseline=CASE WHEN :baseline THEN (SELECT COALESCE(max(v.version),0) FROM ops.work_result v WHERE v.receipt_id=station_run.receipt_id) ELSE result_baseline END,
   baseline_review_only=CASE WHEN :baseline THEN :reviewOnly ELSE baseline_review_only END,instance_id=:instance WHERE run_id=:id""")
   .param("state",state).param("reason",reason).param("at",java.sql.Timestamp.from(clock.instant())).param("dispatch",dispatch).param("baseline",baseline).param("reviewOnly",reviewOnly).param("instance",instance.instanceId).param("id",run.runId).update()
  if(release)jdbc.sql("UPDATE ops.rf_station SET owner=NULL WHERE station_id=:station AND owner=:id").param("station",run.stationId).param("id",run.runId).update()
  if(state in setOf("TIMED_OUT","CANCELLED","REJECTED") && run.receiptId!=null)
   jdbc.sql("UPDATE ops.work_request SET state='REJECTED',detail=:reason,updated_at=clock_timestamp() WHERE receipt_id=:id AND state='WAITING_RESOURCE'").param("id",run.receiptId).param("reason",reason).update()
  event(get(run.runId),reason,by,context)
 }
 /**
  * WAITING_RESOURCE never reached DISPATCHING; DISPATCHING may have been sent. An UNASSIGNED is closed as not to run only when [unsent] (the host
  * withdrew its job order, B3): otherwise an approved remedy may still run it, so it closes without that promise and observation decides.
  */
 private fun cleared(run:StationRun,reason:String,unsent:Boolean) {
  val id=run.receiptId ?: return
  fun close(from:String,to:String,detail:String)=jdbc.sql("UPDATE ops.work_request SET state=:to,detail=:detail,updated_at=clock_timestamp() WHERE receipt_id=:id AND state IN (:from)").param("to",to).param("detail",detail.take(2000)).param("id",id).param("from",from.split(',')).update()
  close(if(unsent)"WAITING_RESOURCE,UNASSIGNED" else "WAITING_RESOURCE","REJECTED","현장 확인으로 자원을 해제했습니다. 이 작업 요청은 실행하지 않습니다: $reason")
  if(!unsent) close("UNASSIGNED","REJECTED","현장 확인으로 자원을 해제했습니다. 실행 호스트가 작업 지시 철회를 확정하지 않아 실행 여부는 실행 관측으로 확인합니다: $reason")
  close("DISPATCHING","IN_DOUBT","현장 확인으로 자원을 해제했습니다. 제출 결과는 실행 관측으로 확인합니다: $reason")
 }
 private fun event(run:StationRun,reason:String,by:String,context:JsonNode?=null) {jdbc.sql("INSERT INTO ops.station_event(run_id,state,reason,actor,context,recorded_at) VALUES(:id,:state,:reason,:actor,CAST(:context AS JSONB),:at)").param("id",run.runId).param("state",run.state).param("reason",reason).param("actor",by).param("context",(context?:json.nullNode()).toString()).param("at",java.sql.Timestamp.from(clock.instant())).update()}
 /** Locks the station row and returns it as locked. */
 private fun lock(station:String):RfStation = jdbc.sql("SELECT * FROM ops.rf_station WHERE station_id=:id FOR UPDATE").param("id",station).query(::stationRow).optional().orElseThrow{RequestRejected("STATION_NOT_FOUND","스테이션이 없습니다",404)}
 private fun stationRow(r:java.sql.ResultSet,@Suppress("UNUSED_PARAMETER") i:Int)=RfStation(r.getString("station_id"),r.getString("name"),r.getString("ready_signal"),r.getString("complete_signal"),r.getInt("timeout_seconds"),r.getObject("owner",UUID::class.java),r.getString("created_by"),r.getString("created_at"),r.getString("job_signal"),r.getString("job_echo_signal"),r.getObject("quarantined_by",UUID::class.java),r.getString("quarantined_at"))
 private fun actor(run:StationRun)=Actor(Mode.OPERATOR,run.actor.substringAfter('/'))
 private fun row(r:java.sql.ResultSet,@Suppress("UNUSED_PARAMETER") i:Int)=runRow(r,"")
 /** A run from columns named [p] followed by each [RUN_COLUMNS] name. */
 private fun runRow(r:java.sql.ResultSet,p:String)=StationRun(r.getObject("${p}run_id",UUID::class.java),r.getString("${p}station_id"),json.readTree(r.getString("${p}payload")),r.getString("${p}actor"),r.getString("${p}state"),r.getString("${p}reason"),r.getObject("${p}receipt_id",UUID::class.java),r.getInt("${p}attempt"),r.getInt("${p}revision"),r.getTimestamp("${p}created_at").toInstant(),r.getTimestamp("${p}phase_at").toInstant(),r.getTimestamp("${p}dispatched_at")?.toInstant(),r.getObject("${p}result_baseline") as Int?,r.getBoolean("${p}baseline_review_only"),r.getString("${p}instance_id"))
 private fun exact(p:JsonNode,fields:Set<String>) {if(!p.isObject || p.fieldNames().asSequence().toSet()!=fields)reject("요청 필드를 확인하세요")}
 private fun text(p:JsonNode,key:String,max:Int)=p[key]?.takeIf{it.isTextual && it.asText().isNotBlank() && it.asText().length<=max}?.asText() ?: reject("$key 문자열이 필요합니다")
 private fun uuid(s:String)=runCatching{UUID.fromString(s)}.getOrNull()?.takeIf{it.toString()==s} ?: reject("정규 UUID가 필요합니다")
 private fun reject(message:String,status:Int=400):Nothing=throw RequestRejected("STATION_REJECTED",message,status)
 companion object {
  /** picasso PhysicalState: settled states other than PHYSICALLY_DONE, plus OPERATOR_HOLD and IN_DOUBT. CANCELING is transient. */
  val REVIEW_PHYSICAL_STATES=setOf("OPERATOR_HOLD","IN_DOUBT","UNVERIFIED","FAILED","ABORTED","PARTIAL")
  /** Run states tick observes through the host execution read. */
  val OBSERVED=setOf("RUNNING","WAITING_ROBOT")
  private val RUN_COLUMNS=listOf("run_id","station_id","payload","actor","state","reason","receipt_id","attempt","revision","created_at","phase_at","dispatched_at","result_baseline","baseline_review_only","instance_id")
  const val REWORK_HELD="자원 관리 작업의 재작업은 스테이션 점유를 유지한 확인 필요 상태에서만 할 수 있습니다"
  val JOB_FIELDS=listOf("jobSignal","jobEchoSignal")
  val SIGNAL_DECISIONS=setOf("RESUME_OBSERVATION","CONFIRM_CLEAR","CONFIRM_STATION_IDLE","CONFIRM_COMPLETION")
  const val JOB_PAIR="작업 번호 송신·반환 신호는 둘 다 연결하거나 둘 다 비워야 합니다."
  const val TEXT_REQUIRED="현장에 선언된 TEXT 신호가 필요합니다."
  const val ECHO_MISMATCH="설비 완료 신호 수신, 작업 번호 불일치; 현장 확인이 필요합니다"
  const val WRITE_FAILED="작업 번호 쓰기 실패; 설비 신호 기록을 기다립니다"
  const val QUARANTINED="스테이션 격리; 현장 확인과 격리 해제 판단을 기다립니다"
  const val QUARANTINE="자원 해제에 따른 스테이션 격리"
  const val IDLE_CONFIRMED="현장 확인 후 격리 해제"
  const val SOURCE_UNOBSERVED="출발 미관측; 작업 요청 상세에서 출발 자리 확인을 기록하기 전에는 다시 제출하지 않고 점유를 유지합니다"
  const val SOURCE_CONFIRMED="출발 자리 확인 기록; 같은 작업 요청을 다시 제출합니다"
  /** A host REJECTED of a station run that the host did not confirm withdrawing (B3): kept for a person, never released on its own. */
  const val REJECTED_UNCONFIRMED="계약 제출 거부; 실행 호스트가 작업 지시 철회를 확정하지 않아 실행 여부 확인 전 점유 유지"
  /** A host REJECTED of a station run whose job order the host reports executed instead of withdrawing (an approved remedy, B2/B3). */
  const val REJECTED_BUT_EXECUTED="계약 제출 거부 뒤 실행 호스트가 그 작업 지시의 실행을 알림; 작업 요청을 수락으로 바꾸고 점유 유지"
  /** The CONFIRM_CLEAR claim (B3): held while the host is asked to withdraw the job order. Every other decision refuses the run meanwhile. */
  const val CLEARING="현장 확인 해제 중; 실행 호스트의 작업 지시 철회 답 전 다른 판단을 막습니다"
  /** A CONFIRM_CLEAR that failed after its claim: back to review, nothing released. */
  const val CLEAR_FAILED="현장 확인 해제 처리 실패; 점유를 유지하고 다시 판단을 기다립니다"
  /** Prefix of the withdrawal reason a person's CONFIRM_CLEAR sends to the host; the person's own reason follows. */
  const val CLEAR_WITHDRAW_REASON="공유 스테이션 현장 확인 해제"
  const val SOURCE_HELD="출발 자리 확인은 출발 미관측으로 점유를 유지한 확인 필요 작업에서만 기록합니다"
  /** Prefix of a WAITING_READY hold on the work permit. Robots, their permit, cause, blocking subject and what lifts it follow. */
  const val PERMIT_HELD="작업 허가 대기; 작업 번호를 쓰지 않고 작업 요청을 내지 않습니다"
  const val PERMIT_RESUMED="작업 허가 복귀; 설비 준비 기한을 다시 셉니다"
  /** Prefix of a WAITING_READY hold while the execution host gives no answer to the work permit read. The cause follows. Not a permit wait. */
  const val HOST_UNREACHABLE_HELD="실행 호스트 응답 없음; 작업 번호를 쓰지 않고 작업 요청을 내지 않습니다"
  const val HOST_RESUMED="실행 호스트 응답 복귀; 설비 준비 기한을 다시 셉니다"
  /** RUNNING reasons: the run's own wait, a run deadline past, and declared signals missing from a read or unread past [SIGNAL_GRACE]. */
  const val RUN_WAITING="로봇 수락; 저장 결과와 새 설비 완료 신호를 기다립니다"
  const val RUN_TIMED_OUT="작업 시간 초과; 물리 상태 확인 전 점유를 유지합니다"
  const val SIGNALS_UNOBSERVED="현장 신호 관측 불명; 점유를 유지하고 현장 확인을 기다립니다"
  /** RUNNING hold while the cell signals cannot be read, within [SIGNAL_GRACE]. */
  const val SIGNALS_GRACE="셀 신호 읽기 실패; 유예 시간 동안 관측을 계속합니다"
  /** RUNNING hold while the run's execution has a work permit block (ADR 52). The run deadline does not run. */
  const val RUN_PERMIT_HELD="작업 허가 차단; 실행 기한을 일시 정지합니다"
  /** The block lifted: the run deadline starts again. */
  const val RUN_PERMIT_RESUMED="작업 허가 복귀; 실행 기한을 다시 계산하며 저장 결과와 설비 완료 신호를 기다립니다"
  /** Prefix of COMPLETED by a person's completion confirmation; the person's reason follows. */
  const val COMPLETION_CONFIRMED="운영자 현장 확인 후 완료 확인"
  /** Review reasons a completion confirmation accepts: the review came from time or sight, never from a judged fault. */
  val CONFIRMABLE_REVIEWS=setOf(RUN_TIMED_OUT,SIGNALS_UNOBSERVED)
  /** Refusals of CONFIRM_COMPLETION. */
  const val COMPLETION_NOT_REVIEWABLE="작업 시간 초과나 현장 신호 관측 불명으로 확인 필요 상태가 된 작업만 완료 확인할 수 있습니다"
  const val COMPLETION_RESULT_UNREAD="로봇 저장 결과를 읽을 수 없습니다. 다시 시도하십시오"
  const val COMPLETION_ROBOT_NOT_DONE="해당 작업의 로봇 물리 완료 저장 결과가 없습니다"
  const val COMPLETION_SIGNALS_UNREAD="셀 신호를 읽을 수 없습니다. 다시 시도하십시오"
  const val COMPLETION_SIGNAL_OFF="설비 완료 신호가 꺼져 있습니다"
  const val COMPLETION_ECHO_MISMATCH="반환 번호가 해당 작업의 작업 번호와 일치하지 않습니다"
  /**
   * How long a RUNNING run waits on unreadable cell signals before review. A read fails on the host read limit
   * (`HostClient.REQUEST_TIMEOUT`, 5 s), for instance while a long submission holds the host lock that `GET /host/cell` takes, or when the
   * host's own site read failed and it reports no cell. One lost tick (default `ops.stations.poll-ms` 2 s) plus its timeout spans about 7 s, so
   * 15 s outlasts two lost reads in a row. It never extends the run deadline, which keeps running through the grace.
   */
  val SIGNAL_GRACE:java.time.Duration=java.time.Duration.ofSeconds(15)
  /** Reasons for a claim left by another instance that stopped or whose heartbeat expired. */
  val LEFTOVER_STOPPED=mapOf("RETRYING" to "재평가 중 프로세스 중단; 실행 관측과 현장 확인 필요","REWORKING" to "재작업 전달 중 프로세스 중단; 반영 여부는 실행 관측과 현장 확인 필요",
   "CLEARING" to "현장 확인 해제 중 프로세스 중단; 작업 지시 철회 여부는 실행 호스트에서 확인 필요")
  /** Reasons for a claim this instance failed to clear after its host call. */
  val LEFTOVER_OWN=mapOf("RETRYING" to "재평가 뒤 상태 정리 실패; 실행 관측과 현장 확인 필요","REWORKING" to "재작업 전달 뒤 상태 정리 실패; 반영 여부는 실행 관측과 현장 확인 필요",
   "CLEARING" to "현장 확인 해제 뒤 상태 정리 실패; 작업 지시 철회 여부는 실행 호스트에서 확인 필요")
 }
}
