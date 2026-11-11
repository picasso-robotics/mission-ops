package dev.picasso.ops.service.requests

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.interfaces.InterfaceRejected
import dev.picasso.ops.service.interfaces.MissionInterfaces
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.joborders.JobOrderSubmission
import dev.picasso.ops.service.log.OperationResult
import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.util.UUID

class RequestRejected(val code: String, override val message: String, val status: Int = 400): RuntimeException(message)
data class Receipt(val receiptId: UUID, val sourceSystemId: String, val externalOrderId: String,
 val jobOrderId: String, val state: String, val detail: String?, val payload: JsonNode,
 val normalizedOrder: JsonNode?, val submission: JsonNode?, val receivedAt: String, val updatedAt: String, val lastExecution: JsonNode?, val executionObservedAt: String?, val executionInstanceId: String?, val requiredStationId:String?=null)
data class ReceiptAccepted(val receipt: Receipt, val duplicate: Boolean)
/**
 * The host's REJECTED on a work request. [alternativeLocations] keeps null (no answer, or not a source-missing rejection) apart from
 * an empty list (no other place holds the material); [sourceMissing] tells the two nulls apart. [remedy] is the host's tagged answer as is.
 * [sourceUnobserved] is the host's source-unobserved answer (the cell did not confirm the source); it is never folded into [sourceMissing].
 * [forgottenJobOrder]: the host refused a job order that already finished and was forgotten (picasso FORGOTTEN_JOB_ORDER); the same job
 * order is refused again the same way, so further work goes out as a new work request.
 */
data class WorkRequestRejection(val reason:String?, val remedy:JsonNode?, val remedyWithheld:Boolean,
 val alternativeLocations:List<String>?, val sourceMissing:dev.picasso.ops.service.host.HostSourceMissing?,
 val sourceUnobserved:dev.picasso.ops.service.host.HostSourceUnobserved?=null,val forgottenJobOrder:Boolean=false)
/**
 * A source the host could not observe on a host UNASSIGNED: the source, what should be there, what the cell said, and the robots refused for it.
 * The work request holds until a person records a source confirmation for it ([WorkRequests.confirmSource]).
 */
data class PendingSource(val source:String, val material:String?, val occupied:Boolean?, val robotIds:List<String>)
/** The body of a source confirmation, parsed before any lock so a coordinator can refuse it before claiming its run. */
data class SourceConfirmationRequest(val requestId:UUID, val source:String, val identity:String, val reason:String, val expectedUpdatedAt:String)
/**
 * The host's UNASSIGNED on a work request: its per-robot refusals as the host sent them. A refusal's [alternativeLocations][dev.picasso.ops.service.host.HostRefusal.alternativeLocations]
 * and sourceMissing have the [WorkRequestRejection] shape and are null unless that robot's source was missing; null and an empty list stay apart.
 */
data class WorkRequestUnassignment(val refusals:List<dev.picasso.ops.service.host.HostRefusal>)
/**
 * The other side of an upstream supersession, as read now: the upstream sent a new work request in place of an earlier one of its own
 * that ended without executing. [externalOrderId] is the upstream's own identifier; [state] is that work request's current state.
 */
data class WorkRequestLink(val receiptId:UUID, val externalOrderId:String, val state:String, val linkedAt:String)
/** Detail text for a host rejection: the host's own reason, so the stale pre-dispatch line never stands in for it. */
fun rejectedDetail(outcome:dev.picasso.ops.service.host.HostSubmitOutcome?):String =
 "호스트 제출 거부: ${outcome?.rejectionReason?.takeIf{it.isNotBlank()} ?: "사유 없음"}".take(2000)
/**
 * Detail text for a host UNASSIGNED: per-robot refusals, then robots the host excluded before assign, in the
 * `id: reason; reason / id: …` form of the pre-dispatch no-eligible line.
 */
fun unassignedDetail(outcome:dev.picasso.ops.service.host.HostSubmitOutcome?):String {
 val robots=outcome?.refusals.orEmpty().map{"${it.robotId}: ${it.reason}"}+outcome?.excluded.orEmpty().map{"${it.robotId}: ${it.reasons.joinToString("; ")}"}
 return "호스트 미배정: ${robots.joinToString(" / ").ifBlank{"기체별 사유 없음"}}".take(2000)
}

/** Commit the unique receipt before any host call. Replays never dispatch, including uncertain receipts. */
class WorkRequests(private val jdbc: JdbcClient, private val interfaces: MissionInterfaces,
 private val operations: JobOrderOperations, private val host: HostReads,
 private val transactions: org.springframework.transaction.support.TransactionTemplate,
 private val durableResults:Boolean=false,
 private val delivery:ResultDeliveryQueue?=null) {
 private val json = ObjectMapper()
 private val kotlinJson = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
 val resources=dev.picasso.ops.service.stations.MissionResources(jdbc,transactions)
 fun receive(source: String, payload: JsonNode): ReceiptAccepted {
  val external = payload["externalOrderId"]?.takeIf { it.isTextual }?.asText()
   ?.takeIf { it.isNotBlank() && it.length <= 160 } ?: throw RequestRejected("BAD_ORDER_ID", "externalOrderId는 1~160자 문자열입니다")
  val envelope=setOf("externalOrderId","interfaceId","interfaceVersion","input")
  val fields=if(payload.isObject) payload.fieldNames().asSequence().toSet() else emptySet()
  if (!payload.isObject || (fields != envelope && fields != envelope+REPLACES) ||
      payload["interfaceId"]?.isTextual != true || payload["interfaceId"].asText().length !in 1..64 ||
      payload["interfaceVersion"]?.isIntegralNumber != true || !payload["interfaceVersion"].canConvertToInt() || payload["interfaceVersion"].asInt() < 1 ||
      payload["input"]?.isObject != true) throw RequestRejected("BAD_ENVELOPE", "계약 식별자·양의 정수 버전·input 객체가 필요합니다")
  // The upstream names its own earlier order this one replaces. Same source only: the caller's identity scopes the lookup.
  val replaces=payload[REPLACES]?.let { node ->
   node.takeIf{it.isTextual}?.asText()?.takeIf{it.isNotBlank() && it.length<=160 && it!=external}
    ?: throw RequestRejected("BAD_REPLACES","$REPLACES 는 이 요청과 다른 1~160자 외부 주문 ID입니다")
  }
  val interfaceId=payload["interfaceId"].asText(); val version=payload["interfaceVersion"].asInt()
  return receivePrepared(source,external,payload,Actor(Mode.OPERATOR,source),"$interfaceId/v$version",payload["input"],replaces=replaces) {
   interfaces.draft(interfaceId,version,payload["input"],true)
  }
 }
 fun receiveManual(actor:Actor, payload:JsonNode):ReceiptAccepted {
  if(actor.mode!=Mode.OPERATOR) throw RequestRejected("MODE_NOT_ALLOWED","운영자 모드만 수동 작업 요청을 받습니다",403)
  if(!payload.isObject || payload.fieldNames().asSequence().toSet()!=setOf("requestId","form") ||
     payload["requestId"]?.isTextual!=true || payload["form"]?.isObject!=true)
   throw RequestRejected("BAD_MANUAL_REQUEST","requestId UUID와 form 객체가 필요합니다")
  val raw=payload["requestId"].asText()
  val key=runCatching { UUID.fromString(raw) }.getOrNull()?.takeIf { it.toString().equals(raw,ignoreCase=true) }
   ?: throw RequestRejected("BAD_MANUAL_REQUEST","정규 UUID 요청 ID가 필요합니다")
  val stored=payload.deepCopy<ObjectNode>().apply {put("requestId",key.toString());put("requestedBy",actor.header())}
  // ':' is excluded by the external identity configuration: upstream callers cannot impersonate local requests.
  return receivePrepared("ops:manual",key.toString(),stored,actor,null,null) { JobOrderForm.read(payload["form"]) }
 }
 fun receiveInterface(actor:Actor,id:String,version:Int,payload:JsonNode,stationRunId:UUID?=null):ReceiptAccepted {
  if(actor.mode!=Mode.OPERATOR) throw RequestRejected("MODE_NOT_ALLOWED","운영자 모드만 작업 요청을 받습니다",403)
  val fields = if(payload.isObject) payload.fieldNames().asSequence().toSet() else emptySet()
  val expected = payload["expectedDeploymentVersion"]
  if(!payload.isObject || fields !in setOf(setOf("requestId","input"), setOf("requestId","input","expectedDeploymentVersion")) ||
     (expected != null && (!expected.isIntegralNumber || !expected.canConvertToInt() || expected.asInt() < 1)) ||
     payload["requestId"]?.isTextual!=true || payload["input"]?.isObject!=true)
   throw RequestRejected("BAD_INTERFACE_REQUEST","requestId UUID와 input 객체가 필요합니다")
  val raw=payload["requestId"].asText()
  val key=runCatching { UUID.fromString(raw) }.getOrNull()?.takeIf { it.toString().equals(raw,ignoreCase=true) }
   ?: throw RequestRejected("BAD_INTERFACE_REQUEST","정규 UUID 요청 ID가 필요합니다")
  val stored=payload.deepCopy<ObjectNode>().apply {
   put("requestId",key.toString());put("requestedBy",actor.header());put("interfaceId",id);put("interfaceVersion",version)
  }
  return receivePrepared("ops:interface",key.toString(),stored,actor,"$id/v$version",payload["input"],stationRunId) {
   val draft = interfaces.draft(id,version,payload["input"],true)
   if(expected != null && (draft as? dev.picasso.ops.service.joborders.DeployedJobOrderDraft)?.deployment?.get("deploymentVersion")?.asInt() != expected.asInt())
    throw InterfaceRejected("STALE_DEPLOYMENT", "배포가 변경되었습니다. 배정 가능 여부를 다시 확인하세요", 409)
   draft
  }
 }
 private fun receivePrepared(source:String, external:String, payload:JsonNode, actor:Actor,
  interfaceRef:String?, interfaceInput:JsonNode?, stationRunId:UUID?=null, replaces:String?=null, draftSupplier:()->dev.picasso.ops.service.joborders.JobOrderDraft):ReceiptAccepted {
  check(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
   "작업 요청은 호스트 호출 전에 독립 커밋되어야 합니다"
  }
  val hash = MessageDigest.getInstance("SHA-256").digest(canonical(payload).toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  val id = UUID.randomUUID()
  val jobId = "JO-$id"
  val insert = { jdbc.sql("""INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state)
   VALUES(:id,:source,:external,:hash,CAST(:payload AS JSONB),:job,'RECEIVED') ON CONFLICT(source_system_id,external_order_id) DO NOTHING""")
   .param("id",id).param("source",source).param("external",external).param("hash",hash).param("payload",payload.toString()).param("job",jobId).update() }
  val inserted = if(replaces==null) insert() else supersede(source,external,replaces,id,insert)
  if (inserted == 0) {
   val old = jdbc.sql("SELECT receipt_id,payload_hash FROM ops.work_request WHERE source_system_id=:source AND external_order_id=:external")
    .param("source",source).param("external",external).query { rs, _ -> rs.getObject(1,UUID::class.java) to rs.getString(2) }.single()
   if (old.second != hash) throw RequestRejected("ORDER_CONFLICT", "동일 요청 ID로 다른 입력 또는 요청자의 작업 요청이 기록되어 있습니다",409)
   return ReceiptAccepted(get(old.first,source),true)
  }
  // RECEIVED is provably pre-dispatch: DISPATCHING is written before any host call. Close it instead of leaving an orphan.
  val (draft,queued)=try {
   val draft = try { draftSupplier() }
    catch(e: Exception) {
     if(e !is InterfaceRejected && e !is dev.picasso.ops.service.joborders.FormRejection) throw e
     update(id,"REJECTED",e.message ?: "입력 검증 거부"); return ReceiptAccepted(get(id),false)
    }
   val order=JobOrderForm.jobOrder(draft,jobId,json)
   if(jdbc.sql("UPDATE ops.work_request SET normalized_order=CAST(:order AS JSONB),updated_at=clock_timestamp() WHERE receipt_id=:id AND state='RECEIVED'")
    .param("order",order.toString()).param("id",id).update()==0) return ReceiptAccepted(get(id),false)
   draft to resources.admit(id,actor,stationRunId)
  } catch(e: Exception) {
   // A business refusal (e.g. admission's 409) keeps its own words; anything else is an internal failure.
   val detail=(e as? RequestRejected)?.message ?: "작업 요청 처리 중 내부 오류; 호스트에 보내지 않았습니다. 새 요청 ID로 다시 보내십시오"
   runCatching {jdbc.sql("UPDATE ops.work_request SET state='REJECTED',detail=:detail,updated_at=clock_timestamp() WHERE receipt_id=:id AND state='RECEIVED'")
    .param("detail",detail.take(2000)).param("id",id).update()}.onFailure(e::addSuppressed)
   throw e
  }
  if(!queued && jdbc.sql("UPDATE ops.work_request SET state='DISPATCHING',detail='공통 작업 요청 검증 완료',updated_at=clock_timestamp() WHERE receipt_id=:id AND state='RECEIVED'").param("id",id).update()==1)
   dispatch(id,actor,draft,interfaceRef,interfaceInput,jobId)
  return ReceiptAccepted(get(id),false)
 }
 /**
  * Commits the new receipt and its supersession of the upstream's earlier order [replaces] together, under that order's row lock.
  * The earlier order must be the same source's, and either closed without executing (REJECTED or UNASSIGNED, no execution observed,
  * no result version) or closed by an operator (ACCEPTED, latest result version carries a closure of [CLOSED_KINDS]; the link then
  * records that version). In both cases no station run may still be open. Each side is linked at most once. A replay of the same new order returns 0 and is answered
  * by the caller's duplicate check, never judged again. A refusal records nothing, so the upstream can resend with the same id.
  * The earlier order's state, detail and submission are never written here.
  */
 private fun supersede(source:String,external:String,replaces:String,id:UUID,insert:()->Int):Int = transactions.execute {
  val original=jdbc.sql("SELECT receipt_id FROM ops.work_request WHERE source_system_id=:source AND external_order_id=:replaces FOR UPDATE")
   .param("source",source).param("replaces",replaces).query(UUID::class.java).optional().orElse(null)
  if(jdbc.sql("SELECT count(*) FROM ops.work_request WHERE source_system_id=:source AND external_order_id=:external")
   .param("source",source).param("external",external).query(Long::class.java).single()>0) return@execute 0
  original ?: throw RequestRejected("ORIGINAL_NOT_FOUND","대체할 앞선 작업 요청을 이 출처에서 찾을 수 없습니다",409)
  val receipt=get(original)
  val ran=receipt.lastExecution!=null || jdbc.sql("SELECT count(*) FROM ops.work_result WHERE receipt_id=:id").param("id",original).query(Long::class.java).single()>0
  val station=jdbc.sql("SELECT count(*) FROM ops.station_run WHERE receipt_id=:id AND state NOT IN ('COMPLETED','TIMED_OUT','REJECTED','MANUALLY_CLEARED','CANCELLED')")
   .param("id",original).query(Long::class.java).single()>0
  // An operator close of an execution the host could not restore ends in an UNVERIFIED result marked with a closure (defect 14).
  val latest=storedResult(original,null)
  val closed=receipt.state=="ACCEPTED" && ((latest?.get("snapshot") as? JsonNode)?.path("closure")?.path("kind")?.asText() in CLOSED_KINDS)
  if(!((receipt.state in setOf("REJECTED","UNASSIGNED") && !ran) || closed) || station)
   throw RequestRejected("ORIGINAL_NOT_REPLACEABLE","실행 없이 끝났거나 운영자가 종결한 앞선 작업 요청만 대체할 수 있습니다",409)
  if(link(original,"original_receipt_id","replacement_receipt_id")!=null)
   throw RequestRejected("ORIGINAL_ALREADY_REPLACED","앞선 작업 요청은 이미 다른 작업 요청으로 대체되었습니다",409)
  val inserted=insert()
  if(inserted==1) jdbc.sql("""INSERT INTO ops.work_request_supersession(original_receipt_id,replacement_receipt_id,source_system_id,original_state,original_result_version)
   VALUES(:original,:id,:source,:state,:version)""").param("original",original).param("id",id).param("source",source).param("state",receipt.state)
   .param("version",if(closed) latest!!["version"] as Int else null,java.sql.Types.INTEGER).update()
  inserted
 }!!
 /** The other side of this receipt's supersession: [self] names this receipt's column, [other] the column read back. */
 private fun link(id:UUID,self:String,other:String):WorkRequestLink? =
  jdbc.sql("""SELECT r.receipt_id,r.external_order_id,r.state,s.created_at FROM ops.work_request_supersession s
   JOIN ops.work_request r ON r.receipt_id=s.$other WHERE s.$self=:id""").param("id",id)
   .query{rs,_ -> WorkRequestLink(rs.getObject(1,UUID::class.java),rs.getString(2),rs.getString(3),rs.getString(4))}.optional().orElse(null)
 /** The upstream's later work request that replaces this one, or null. */
 fun supersededBy(id:UUID):WorkRequestLink? = link(id,"original_receipt_id","replacement_receipt_id")
 /** The upstream's earlier work request this one replaces, or null. */
 fun replaces(id:UUID):WorkRequestLink? = link(id,"replacement_receipt_id","original_receipt_id")
 /** A superseded work request is never submitted again by ops: the upstream already chose what replaces it. */
 private fun refuseIfSuperseded(id:UUID) {
  if(supersededBy(id)!=null) throw RequestRejected("ORIGINAL_REPLACED","상위 시스템이 새 작업 요청으로 대체했습니다. 이 작업 요청은 다시 제출하지 않습니다",409)
 }
 /** Close receipts a stopped process left behind. WAITING_RESOURCE belongs to station coordination. */
 fun sweepStale(olderThan:java.time.Duration):Int {
  fun close(from:String,to:String,detail:String)=jdbc.sql("UPDATE ops.work_request SET state=:to,detail=:detail,updated_at=clock_timestamp() WHERE state=:from AND updated_at<clock_timestamp()-interval '1 millisecond'*CAST(:ms AS double precision)")
   .param("from",from).param("to",to).param("detail",detail).param("ms",olderThan.toMillis()).update()
  return close("RECEIVED","REJECTED","작업 요청 처리 중단; 호스트에 보내지 않았습니다. 새 요청 ID로 다시 보내십시오")+
   close("DISPATCHING","IN_DOUBT","제출 도중 중단; 실행 관측과 현장 확인이 필요합니다")
 }
 private fun dispatch(id:UUID,actor:Actor,draft:dev.picasso.ops.service.joborders.JobOrderDraft,interfaceRef:String?,interfaceInput:JsonNode?,jobId:String) {
  // Authorize runs before any host call, so its refusal is provably unsent. Close only this claim; keep the caller's error.
  try {resources.authorize(id,draft.workMasterId)} catch(e:RequestRejected) {
   runCatching {jdbc.sql("UPDATE ops.work_request SET state='REJECTED',detail=:detail,updated_at=clock_timestamp() WHERE receipt_id=:id AND state='DISPATCHING'")
    .param("detail","자원 요구가 바뀌어 호스트에 보내지 않았습니다. 새 요청 ID로 다시 보내십시오: ${e.message}".take(2000)).param("id",id).update()}.onFailure(e::addSuppressed)
   throw e
  }
  // A crash after this point remains DISPATCHING; recovery is observation, never blind resubmission.
  try {
   when(val sent=operations.submit(actor,draft,interfaceRef,interfaceInput,id,jobId,sourceConfirmations(id))) {
    is JobOrderSubmission.NoEligibleRobot -> finish(id,"UNASSIGNED",sent.detail,null,closeRound=true)
    is JobOrderSubmission.Submitted -> {
     val value=sent.outcome
     val state=when {
      value.outcome?.result==dev.picasso.ops.service.host.HostSubmitResult.UNASSIGNED -> "UNASSIGNED"
      value.result==OperationResult.SUCCEEDED || value.confirmation==OperationResult.CONFIRMED_APPLIED -> "ACCEPTED"
      value.result==OperationResult.REJECTED -> "REJECTED"
      else -> "IN_DOUBT"
     }
     // A rejection or an unassignment replaces the pre-dispatch detail with the host's own reasons; the structured outcome stays in submission.
     val detail=when(state){"REJECTED"->rejectedDetail(value.outcome);"UNASSIGNED"->unassignedDetail(value.outcome);else->null}
     // Only a host UNASSIGNED that asks a person at a source keeps the source confirmation round open (V22).
     val open=state=="UNASSIGNED" && value.outcome?.refusals.orEmpty().any{it.sourceUnobserved!=null}
     finish(id,state,detail,ObjectMapper().findAndRegisterModules().writeValueAsString(value),closeRound=!open)
    }
   }
  } catch(e: Exception) { org.slf4j.LoggerFactory.getLogger(WorkRequests::class.java).warn("접수 {} 제출 결과 불명: {}",id,e.javaClass.simpleName); finish(id,"IN_DOUBT","제출 결과를 확정할 수 없습니다. 실행 이력을 확인하세요",null,closeRound=true) }
 }
 fun dispatchQueued(id:UUID,actor:Actor):ReceiptAccepted {
  val claimed=transactions.execute {
   jdbc.sql("SELECT receipt_id FROM ops.work_request WHERE receipt_id=:id FOR UPDATE").param("id",id).query(UUID::class.java).single()
   val r=get(id)
   if(r.state!="WAITING_RESOURCE")return@execute false
   resources.authorize(id,r.normalizedOrder!!["workMasterId"].asText())
   update(id,"DISPATCHING","필수 자원 확보 및 설비 준비 확인")
   true
  }!!
  if(claimed) {
   val r=get(id)
   val draft=dev.picasso.ops.service.joborders.GenericJobOrderDraft(r.normalizedOrder!!.deepCopy<ObjectNode>())
   dispatch(id,actor,draft,null,null,r.jobOrderId)
  }
  return ReceiptAccepted(get(id),!claimed)
 }
 fun recover(id:UUID,actor:Actor,payload:JsonNode,stationRunId:UUID?=null):ReceiptAccepted {
  if(actor.mode!=Mode.OPERATOR) throw RequestRejected("MODE_NOT_ALLOWED","운영자만 미배정을 재평가합니다",403)
  val raw=payload["requestId"]?.takeIf{it.isTextual}?.asText()
  val attempt=raw?.let{runCatching{UUID.fromString(it)}.getOrNull()}?.takeIf{it.toString().equals(raw,ignoreCase=true)}
  val reason=payload["reason"]?.takeIf{it.isTextual}?.asText()?.trim()
  val expected=payload["expectedUpdatedAt"]?.takeIf{it.isTextual}?.asText()
  if(!payload.isObject || payload.fieldNames().asSequence().toSet()!=setOf("requestId","reason","expectedUpdatedAt") ||
   attempt==null || reason.isNullOrBlank() || reason.length>1000 || expected.isNullOrBlank())
   throw RequestRejected("BAD_RECOVERY","요청 UUID·판단 사유·조회한 updatedAt이 필요합니다")
  var draft:dev.picasso.ops.service.joborders.JobOrderDraft?=null
  var ref:String?=null;var input:JsonNode?=null
  val claimed=transactions.execute {
   // Mission lock before the row lock, held to the DISPATCHING commit: a requirement cannot land between authorize and commit.
   // Order mission -> row; admit takes mission -> station -> row and create takes no row lock, so no cycle.
   val wm=get(id).normalizedOrder?.get("workMasterId")?.asText()?.also(resources::lockMission)
   jdbc.sql("SELECT receipt_id FROM ops.work_request WHERE receipt_id=:id FOR UPDATE").param("id",id).query(UUID::class.java).single()
   val old=jdbc.sql("SELECT receipt_id,actor,reason,expected_updated_at FROM ops.work_request_recovery WHERE attempt_id=:attempt")
    .param("attempt",attempt).query {rs,_ -> listOf(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4))}.optional().orElse(null)
   if(old!=null) {
    if(old!=listOf(id.toString(),actor.header(),reason,expected)) throw RequestRejected("RECOVERY_CONFLICT","재평가 요청 ID가 다른 판단에 사용되었습니다",409)
    return@execute false
   }
   refuseIfSuperseded(id)
   val receipt=get(id)
   if(receipt.requiredStationId!=null) {
    if(stationRunId==null)throw RequestRejected("RESOURCE_MANAGED","자원 관리 작업은 공유 자원 화면에서 관측·복구합니다",409)
    val context=resources.context(id)
    if(context?.get("runId")!=stationRunId)throw RequestRejected("RESOURCE_MANAGED","다른 자원 작업의 재평가입니다",409)
   }
   // Only pre-dispatch eligibility failures are proven not sent. Absence in a host read is not such proof.
   if(receipt.state!="UNASSIGNED" || receipt.submission!=null || receipt.lastExecution!=null || receipt.normalizedOrder==null)
    throw RequestRejected("RECOVERY_NOT_SAFE","호스트 전송 전 미배정만 재평가할 수 있습니다. 처리 중·결과 불명은 실행 관측과 현장 확인이 필요합니다",409)
   // Refuse before claiming DISPATCHING, including a requirement registered after this receipt.
   resources.authorize(id,receipt.normalizedOrder["workMasterId"].asText())
   if(receipt.updatedAt!=expected || receipt.normalizedOrder["workMasterId"].asText()!=wm) throw RequestRejected("STALE_RECOVERY","작업 요청 상태가 바뀌었습니다. 다시 조회하세요",409)
   val again=redraft(receipt);draft=again.draft;ref=again.ref;input=again.input
   jdbc.sql("INSERT INTO ops.work_request_recovery(attempt_id,receipt_id,actor,reason,expected_updated_at) VALUES(:attempt,:id,:actor,:reason,:expected)")
    .param("attempt",attempt).param("id",id).param("actor",actor.header()).param("reason",reason).param("expected",expected).update()
   jdbc.sql("UPDATE ops.work_request SET state='DISPATCHING',detail=:detail,updated_at=clock_timestamp() WHERE receipt_id=:id")
    .param("id",id).param("detail","운영자 미배정 재평가: $reason").update()
   true
  }!!
  if(claimed) dispatch(id,actor,draft!!,ref,input,get(id).jobOrderId)
  return ReceiptAccepted(get(id),!claimed)
 }
 private class Redraft(val draft:dev.picasso.ops.service.joborders.JobOrderDraft,val ref:String?,val input:JsonNode?)
 /** The receipt's original input converted again under its pinned deployment. Refuses when it no longer yields the stored job order. */
 private fun redraft(receipt:Receipt):Redraft {
  val original=receipt.payload;val normalized=receipt.normalizedOrder!!
  var ref:String?=null;var input:JsonNode?=null
  var draft=try {
   if(receipt.sourceSystemId=="ops:manual") JobOrderForm.read(original["form"]) else {
    val interfaceId=original["interfaceId"].asText();val version=original["interfaceVersion"].asInt()
    ref="$interfaceId/v$version";input=original["input"]
    interfaces.draft(interfaceId,version,input!!,true)
   }
  } catch(e:InterfaceRejected) {throw RequestRejected(e.code,e.message,e.status)}
  normalized["deployment"]?.let { saved ->
   val originalDraft=(draft as? dev.picasso.ops.service.joborders.DeployedJobOrderDraft)?.original ?: draft
   draft=dev.picasso.ops.service.joborders.DeployedJobOrderDraft(originalDraft,saved)
  }
  if(canonical(JobOrderForm.jobOrder(draft,receipt.jobOrderId,json))!=canonical(normalized))
   throw RequestRejected("RECOVERY_INPUT_CHANGED","원래 변환 입력과 달라 재평가를 차단했습니다",409)
  return Redraft(draft,ref,input)
 }
 /** Parses a source confirmation body. Exact fields; source and identity up to 160 characters, reason up to 1000. */
 fun sourceConfirmationRequest(payload:JsonNode):SourceConfirmationRequest {
  val raw=payload["requestId"]?.takeIf{it.isTextual}?.asText()
  val requestId=raw?.let{runCatching{UUID.fromString(it)}.getOrNull()}?.takeIf{it.toString().equals(raw,ignoreCase=true)}
  fun text(key:String,max:Int)=payload[key]?.takeIf{it.isTextual}?.asText()?.trim()?.takeIf{it.isNotEmpty() && it.length<=max}
  val source=text("source",160);val identity=text("identity",160);val reason=text("reason",1000);val expected=text("expectedUpdatedAt",64)
  if(!payload.isObject || payload.fieldNames().asSequence().toSet()!=setOf("requestId","source","identity","reason","expectedUpdatedAt") ||
   requestId==null || source==null || identity==null || reason==null || expected==null)
   throw RequestRejected("BAD_SOURCE_CONFIRMATION","확인 UUID·출발 자리·확인한 신원·판단 근거·조회한 updatedAt이 필요합니다")
  return SourceConfirmationRequest(requestId,source,identity,reason,expected)
 }
 /**
  * A person records what they confirmed at a source the host could not observe (picasso SourceUnobserved), then the same job order is
  * submitted again. Only a source still pending on the host's own UNASSIGNED is accepted. The row is append-only. The dispatches of this work
  * request carry it to the host as the cell observation of that source only while the host keeps answering source-unobserved, so another
  * source of a chain can be confirmed in turn; any other end closes the round ([sourceConfirmations]). A resource-managed receipt goes
  * through its station run.
  */
 fun confirmSource(id:UUID,actor:Actor,payload:JsonNode,stationRunId:UUID?=null):ReceiptAccepted {
  if(actor.mode!=Mode.OPERATOR) throw RequestRejected("MODE_NOT_ALLOWED","운영자만 출발 자리 확인을 기록합니다",403)
  val asked=sourceConfirmationRequest(payload)
  var again:Redraft?=null
  val claimed=transactions.execute {
   val wm=get(id).normalizedOrder?.get("workMasterId")?.asText()?.also(resources::lockMission)
   jdbc.sql("SELECT receipt_id FROM ops.work_request WHERE receipt_id=:id FOR UPDATE").param("id",id).query(UUID::class.java).single()
   val old=jdbc.sql("SELECT receipt_id,actor,source,identity,reason,expected_updated_at FROM ops.work_request_source_confirmation WHERE request_id=:request")
    .param("request",asked.requestId).query {rs,_ -> (1..6).map{rs.getString(it)}}.optional().orElse(null)
   if(old!=null) {
    if(old!=listOf(id.toString(),actor.header(),asked.source,asked.identity,asked.reason,asked.expectedUpdatedAt))
     throw RequestRejected("SOURCE_CONFIRMATION_CONFLICT","확인 요청 ID가 다른 확인에 사용되었습니다",409)
    return@execute false
   }
   refuseIfSuperseded(id)
   val receipt=get(id)
   if(receipt.requiredStationId!=null) {
    if(stationRunId==null)throw RequestRejected("RESOURCE_MANAGED","자원 관리 작업은 공유 자원 작업으로 출발 자리 확인을 기록합니다",409)
    if(resources.context(id)?.get("runId")!=stationRunId)throw RequestRejected("RESOURCE_MANAGED","다른 자원 작업의 출발 자리 확인입니다",409)
   }
   if(receipt.updatedAt!=asked.expectedUpdatedAt || receipt.normalizedOrder?.get("workMasterId")?.asText()!=wm)
    throw RequestRejected("STALE_SOURCE_CONFIRMATION","작업 요청 상태가 바뀌었습니다. 다시 조회하세요",409)
   val pending=pendingSources(receipt).firstOrNull{it.source==asked.source}
    ?: throw RequestRejected("SOURCE_NOT_PENDING","출발 미관측으로 확인을 기다리는 출발 자리가 아닙니다",409)
   resources.authorize(id,wm!!)
   again=redraft(receipt)
   jdbc.sql("""INSERT INTO ops.work_request_source_confirmation(request_id,receipt_id,source,identity,actor,confirmer_kind,reason,expected_updated_at,context)
    VALUES(:request,:id,:source,:identity,:actor,'PERSON',:reason,:expected,CAST(:context AS JSONB))""")
    .param("request",asked.requestId).param("id",id).param("source",asked.source).param("identity",asked.identity).param("actor",actor.header())
    .param("reason",asked.reason).param("expected",asked.expectedUpdatedAt).param("context",kotlinJson.writeValueAsString(pending)).update()
   // The host's UNASSIGNED proved nothing was sent; its answer now lives in the confirmation's context. Clearing it keeps a later
   // pre-dispatch UNASSIGNED (no eligible robot) a plain pre-dispatch one, never a stale pending source.
   jdbc.sql("UPDATE ops.work_request SET state='DISPATCHING',submission=NULL,detail=:detail,updated_at=clock_timestamp() WHERE receipt_id=:id")
    .param("id",id).param("detail","출발 자리 확인 후 다시 제출: ${asked.source} = ${asked.identity} (${actor.header()}): ${asked.reason}".take(2000)).update()
   true
  }!!
  if(claimed) again!!.let{dispatch(id,actor,it.draft,it.ref,it.input,get(id).jobOrderId)}
  return ReceiptAccepted(get(id),!claimed)
 }
 /**
  * Sources the host's own UNASSIGNED left unobserved, one per source in refusal order. Empty for any other state and for a pre-dispatch
  * UNASSIGNED, which has no host answer.
  */
 fun pendingSources(receipt:Receipt):List<PendingSource> =
  unassignment(receipt)?.refusals.orEmpty().filter{it.sourceUnobserved!=null}.groupBy{it.sourceUnobserved!!.source}
   .map{(source,refusals)->val first=refusals.first().sourceUnobserved!!;PendingSource(source,first.material,first.occupied,refusals.map{it.robotId})}
 /**
  * Latest confirmation per source of this work request's open round, as the host's `sourceConfirmations`. A round closes when a dispatch
  * ends other than a host UNASSIGNED asking a person at a source (V22); confirmations recorded before that close are never carried again.
  */
 private fun sourceConfirmations(id:UUID):List<dev.picasso.ops.service.host.HostSourceConfirmation> =
  jdbc.sql("""SELECT DISTINCT ON (c.source) c.source,c.identity,c.actor,c.created_at FROM ops.work_request_source_confirmation c
   JOIN ops.work_request r ON r.receipt_id=c.receipt_id
   WHERE c.receipt_id=:id AND (r.source_confirmations_closed_at IS NULL OR c.created_at>r.source_confirmations_closed_at)
   ORDER BY c.source,c.created_at DESC,c.request_id""").param("id",id).query {rs,_ ->
   dev.picasso.ops.service.host.HostSourceConfirmation(rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant().toString())
  }.list()
 fun review(id:UUID,actor:Actor,payload:JsonNode):Map<String,Any?> = transactions.execute {
  if(actor.mode!=Mode.OPERATOR)throw RequestRejected("MODE_NOT_ALLOWED","운영자만 현장 확인을 기록합니다",403)
  val raw=payload.path("requestId").asText();val requestId=runCatching{UUID.fromString(raw)}.getOrNull()?.takeIf{it.toString()==raw}
  val observation=payload.path("observation").asText();val reason=payload.path("reason").takeIf{it.isTextual}?.asText()?.trim()
  val expected=payload.path("expectedUpdatedAt").takeIf{it.isTextual}?.asText()
  if(!payload.isObject || payload.fieldNames().asSequence().toSet()!=setOf("requestId","observation","reason","expectedUpdatedAt") || requestId==null ||
   observation !in setOf("NOT_CONFIRMED","NOT_STARTED","IN_PROGRESS","COMPLETED") || reason.isNullOrBlank() || reason.length>1000 || expected.isNullOrBlank())
   throw RequestRejected("BAD_REVIEW","확인 UUID·현장 관측·판단 근거·조회 버전이 필요합니다")
  get(id)
  jdbc.sql("SELECT receipt_id FROM ops.work_request WHERE receipt_id=:id FOR UPDATE").param("id",id).query(UUID::class.java).single()
  val old=jdbc.sql("SELECT receipt_id,actor,observation,reason,expected_updated_at FROM ops.work_request_review WHERE request_id=:request")
   .param("request",requestId).query{rs,_ -> (1..5).map{rs.getString(it)}}.optional().orElse(null)
  if(old!=null) {
   if(old!=listOf(id.toString(),actor.header(),observation,reason,expected))throw RequestRejected("REVIEW_CONFLICT","같은 확인 ID에 다른 기록입니다",409)
   return@execute mapOf("requestId" to requestId,"duplicate" to true)
  }
  val receipt=get(id)
  if(receipt.updatedAt!=expected)throw RequestRejected("STALE_REVIEW","관측한 작업 요청 상태가 바뀌었습니다. 다시 조회하세요",409)
  val context=json.createObjectNode().put("receiptState",receipt.state).put("observedAt",receipt.executionObservedAt).put("instanceId",receipt.executionInstanceId)
  context.set<JsonNode>("lastExecution",receipt.lastExecution?:json.nullNode())
  context.set<JsonNode>("latestResult",json.valueToTree(storedResult(id,null)))
  jdbc.sql("INSERT INTO ops.work_request_review(request_id,receipt_id,actor,observation,reason,expected_updated_at,context) VALUES(:request,:id,:actor,:observation,:reason,:expected,CAST(:context AS JSONB))")
   .param("request",requestId).param("id",id).param("actor",actor.header()).param("observation",observation).param("reason",reason).param("expected",expected).param("context",context.toString()).update()
  mapOf("requestId" to requestId,"duplicate" to false)
 }!!
 private fun update(id:UUID,state:String,detail:String,closeRound:Boolean=false) {
  jdbc.sql("UPDATE ops.work_request SET state=:state,detail=:detail,${if(closeRound)"$CLOSE_ROUND," else ""}updated_at=clock_timestamp() WHERE receipt_id=:id")
   .param("state",state).param("detail",detail.take(2000)).param("id",id).update()
 }
 /**
  * Writes the end of a dispatch. The submission, the round close and updated_at are always written: they are the host's answer.
  * State and detail move only from the states [leavesTo] allows, so a late answer never overwrites what observation or a person
  * already decided while the host call was in flight. A kept state is logged with the discarded target.
  */
 private fun finish(id:UUID,target:String,detail:String?,submission:String?,closeRound:Boolean) {
  val allowed=leavesTo(target)
  var statement=jdbc.sql("""UPDATE ops.work_request SET state=CASE WHEN $allowed THEN :state ELSE state END,
   ${if(detail!=null)"detail=CASE WHEN $allowed THEN :detail ELSE detail END," else ""}${if(submission!=null)"submission=CAST(:submission AS JSONB)," else ""}${if(closeRound)"$CLOSE_ROUND," else ""}
   updated_at=clock_timestamp() WHERE receipt_id=:id RETURNING state""").param("state",target).param("id",id)
  if(detail!=null) statement=statement.param("detail",detail.take(2000))
  if(submission!=null) statement=statement.param("submission",submission)
  val now=statement.query(String::class.java).optional().orElse(null)
  if(now!=null && now!=target) org.slf4j.LoggerFactory.getLogger(WorkRequests::class.java).warn("접수 {} 상태 {} 유지, 제출 결과 {} 는 반영하지 않았다",id,now,target)
 }
 /** The SQL condition on the current row under which a dispatch outcome [target] may set the state. */
 private fun leavesTo(target:String):String = when(target) {
  "IN_DOUBT" -> "state='DISPATCHING'"
  "ACCEPTED" -> "state IN ('DISPATCHING','IN_DOUBT')"
  "REJECTED","UNASSIGNED" -> "state IN ('DISPATCHING','IN_DOUBT') AND last_execution IS NULL"
  else -> error("no dispatch outcome $target")
 }
 fun get(id:UUID,source:String?=null):Receipt = jdbc.sql("SELECT * FROM ops.work_request WHERE receipt_id=:id AND (:source IS NULL OR source_system_id=:source)")
  .param("id",id).param("source",source,java.sql.Types.VARCHAR).query(::row).optional()
  .orElseThrow { RequestRejected("RECEIPT_NOT_FOUND","작업 요청을 찾을 수 없습니다",404) }
 /** Up to this many rows matching a search, the list page reads and sorts the matches instead of walking the received-at index. Tests lower it. */
 internal var narrowPageRows=1000L
 fun list(search:String, state:String?, offset:Int, limit:Int, result:String?=null):Map<String,Any> {
  if(search.length>160 || offset<0 || limit !in 1..100 || state!=null && state !in STATES && state!="NEEDS_CONFIRMATION" || result!=null && result!="unacknowledged") throw RequestRejected("BAD_FILTER","조회 조건이 잘못되었습니다")
  // Only the filters asked for: an empty search matches every row, and a fixed state predicate can use the state index.
  val filters=buildList {
   if(state=="NEEDS_CONFIRMATION") add("state IN ('RECEIVED','DISPATCHING','IN_DOUBT')") else if(state!=null) add("state=:state")
   // A substring match through the trigram indexes (V24). LIKE with the search escaped matches the rows strpos matched.
   if(search.isNotEmpty()) add("(external_order_id LIKE :search OR job_order_id LIKE :search OR source_system_id LIKE :search)")
   if(result=="unacknowledged") add("source_system_id NOT LIKE 'ops:%' AND EXISTS($UNACKNOWLEDGED_LATEST AND u.receipt_id=ops.work_request.receipt_id)")
  }
  val where=if(filters.isEmpty()) "" else " WHERE "+filters.joinToString(" AND ")
  fun query(sql:String):JdbcClient.StatementSpec {
   var statement=jdbc.sql(sql)
   if(search.isNotEmpty()) statement=statement.param("search",containing(search))
   if(state!=null && state!="NEEDS_CONFIRMATION") statement=statement.param("state",state)
   return statement
  }
  val count=query("SELECT count(*) FROM ops.work_request$where").query(Long::class.java).single()
  // The page plan for a search follows the exact count, not the planner's estimate of a substring match, which can be far off either
  // way. Few matches: read them through the trigram indexes and sort them (OFFSET 0 keeps the planner from walking the received-at
  // index instead). Many: walk the received-at index, which reaches a page of matches early.
  val page=if(search.isNotEmpty() && count<=narrowPageRows) "SELECT * FROM (SELECT * FROM ops.work_request$where OFFSET 0) w"
   else "SELECT * FROM ops.work_request$where"
  val rows=query("$page ORDER BY received_at DESC,receipt_id LIMIT :limit OFFSET :offset")
   .param("limit",limit).param("offset",offset).query(::row).list()
  return mapOf("total" to count,"requests" to rows,"offset" to offset,"limit" to limit)
 }
 fun summary():Map<String,Any> {
  val counts=jdbc.sql("SELECT state,count(*) AS n FROM ops.work_request GROUP BY state").query { rs,_ -> rs.getString("state") to rs.getLong("n") }.list().toMap()
  // At most one latest version per work request, so counting latest unacknowledged versions counts work requests.
  val pendingResults=jdbc.sql("""SELECT count(*) FROM ops.work_request r WHERE r.source_system_id NOT LIKE 'ops:%'
   AND EXISTS($UNACKNOWLEDGED_LATEST AND u.receipt_id=r.receipt_id)""").query(Long::class.java).single()
  return mapOf("states" to counts,"total" to counts.values.sum(),"unacknowledgedResults" to pendingResults,
   "latest" to jdbc.sql("SELECT * FROM ops.work_request ORDER BY received_at DESC,receipt_id LIMIT 5").query(::row).list(),
   "observedAt" to java.time.Instant.now().toString())
 }
 fun collectResults():Int {
  if(durableResults)return collectEvents()
  val observedAt=java.time.Instant.now()
  val call=host.executions()
  val body=(call as? HostCall.Ok)?.value?.takeIf{it["instanceId"]?.isTextual==true && it["executions"]?.isArray==true}
   ?: throw IllegalStateException("호스트 결과 관측 실패")
  val jobs=body["executions"].mapNotNull{it["jobOrderId"]?.takeIf{it.isTextual}?.asText()}.distinct()
  if(jobs.isEmpty())return 0
  val ids=jdbc.sql("SELECT receipt_id FROM ops.work_request WHERE job_order_id IN (:jobs)").param("jobs",jobs).query(UUID::class.java).list()
  ids.forEach{observe(it,null,call,observedAt)}
  return ids.size
 }
 fun cursorStatus():Map<String,Any?> = jdbc.sql("SELECT producer_id,sequence,high_watermark,updated_at FROM ops.result_cursor WHERE singleton")
  .query {rs,_ -> mapOf("producerId" to rs.getString(1),"sequence" to rs.getLong(2),"highWatermark" to rs.getLong(3),"updatedAt" to rs.getString(4),"mode" to if(durableResults) "DURABLE_OUTBOX" else "LEGACY_POLLING")}.single()
 private fun collectEvents():Int {
  val before=cursorStatus();val after=(before["sequence"] as Long)
  val feed=(host.resultEvents(after,100) as? HostCall.Ok)?.value ?: error("result feed unavailable")
  val producer=feed.path("producerId").takeIf{it.isTextual}?.asText()?.let{UUID.fromString(it)} ?: error("invalid producer")
  val watermark=feed.path("highWatermark").takeIf{it.isIntegralNumber && it.canConvertToLong()}?.asLong() ?: error("invalid watermark")
  val events=feed.path("events").takeIf{it.isArray && it.size()<=100} ?: error("invalid events")
  check(watermark>=after)
  var expected=after
  events.forEach {event ->
   val sequence=event.path("sequence");check(sequence.isIntegralNumber && sequence.canConvertToLong() && sequence.asLong()==++expected && expected<=watermark) {"result gap"}
   validateEvent(event.path("snapshot"))
  }
  check(events.size()==minOf(100L,watermark-after).toInt()) {"incomplete event page"}
  return transactions.execute {
   val current=jdbc.sql("SELECT producer_id,sequence FROM ops.result_cursor WHERE singleton FOR UPDATE")
    .query {rs,_ -> rs.getString(1) to rs.getLong(2)}.single()
   if(current.second!=after)return@execute 0 // Another collector committed this page; read again next tick.
   check(current.first==null || current.first==producer.toString()) {"producer changed; reconciliation required"}
   var matched=0
   events.forEach {event ->
    val snapshot=event["snapshot"]
    val receiptId=jdbc.sql("SELECT receipt_id FROM ops.work_request WHERE job_order_id=:job")
     .param("job",snapshot["jobOrderId"].asText()).query(UUID::class.java).optional().orElse(null)
    if(receiptId!=null) {
     val receipt=get(receiptId)
     check(receipt.normalizedOrder?.path("workMasterId")?.asText()==snapshot["workMasterId"].asText()) {"result mission mismatch"}
     captureResult(receipt,snapshot["instanceId"].asText(),snapshot)
     jdbc.sql("UPDATE ops.work_request SET state='ACCEPTED',updated_at=clock_timestamp() WHERE receipt_id=:id AND state IN ('RECEIVED','DISPATCHING','IN_DOUBT')")
      .param("id",receiptId).update()
     matched++
    }
   }
   jdbc.sql("UPDATE ops.result_cursor SET producer_id=:producer,sequence=:sequence,high_watermark=:watermark,updated_at=clock_timestamp() WHERE singleton")
    .param("producer",producer).param("sequence",expected).param("watermark",watermark).update()
   matched
  }!!
 }
 private fun validateEvent(snapshot:JsonNode) {
  check(snapshot.isObject)
  for(key in listOf("instanceId","executionId","jobOrderId","robotId","workMasterId","physicalState"))check(snapshot.path(key).isTextual && snapshot[key].asText().isNotBlank()) {"invalid $key"}
  val response=snapshot.path("jobResponse")
  check(response.isObject && response.path("jobResponseId").isTextual && response["jobResponseId"].asText().isNotBlank())
  check(response.path("version").isIntegralNumber && response["version"].canConvertToInt() && response["version"].asInt()>0)
  check(response.path("physicalState")==snapshot["physicalState"])
  check(snapshot.has("missionVersion") && (snapshot["missionVersion"].isNull || snapshot["missionVersion"].isIntegralNumber && snapshot["missionVersion"].canConvertToInt() && snapshot["missionVersion"].asInt()>0))
  for(key in listOf("requiredEvidence","reachedEvidence"))check(response.path(key).isTextual && response[key].asText() in setOf("E0","E1","E2","E3"))
  for(key in listOf("completedUnits","unverifiedUnits","inDoubtUnits","blockedBy"))check(response.path(key).isArray && response[key].all{it.isTextual})
  check(response.path("incompleteUnits").isObject && response["incompleteUnits"].all{it.isTextual})
  check(response.path("operatorRequired").isBoolean && response.path("residualHold").isTextual)
  // An operator close (defect 14) marks its UNVERIFIED result with a closure; anything else in that field fails the page.
  snapshot.get("closure")?.let{closure->
   check(closure.isObject && closure.path("kind").isTextual && closure["kind"].asText() in CLOSED_KINDS &&
    closure.path("basis").isTextual && closure["basis"].asText() in setOf("SNAPSHOT","ATTESTED") && snapshot["physicalState"].asText()=="UNVERIFIED") {"invalid closure"}
  }
 }
 /**
  * The receipt behind a host execution, through the same execution read as observation. Null: no receipt (direct job order), or
  * [instanceId] is not the host's current instance, which the host itself refuses (instance ids never repeat). Unreadable or
  * unlisted executions fail closed: their resource management cannot be known.
  */
 fun executionReceipt(instanceId:String,executionId:String):Receipt? {
  val body=(host.executionsOf(executionIds=listOf(executionId)) as? HostCall.Ok)?.value?.takeIf { it["instanceId"]?.isTextual == true && it["executions"]?.isArray == true }
   ?: throw RequestRejected("HOST_SILENT","실행 목록을 읽지 못해 자원 관리 작업인지 확인할 수 없습니다",503)
  if(body["instanceId"].asText()!=instanceId)return null
  val job=body["executions"].firstOrNull { it["executionId"]?.asText()==executionId }?.get("jobOrderId")?.takeIf { it.isTextual }?.asText()
   ?: throw RequestRejected("EXECUTION_NOT_FOUND","실행을 찾지 못해 자원 관리 작업인지 확인할 수 없습니다",409)
  return jdbc.sql("SELECT * FROM ops.work_request WHERE job_order_id=:job").param("job",job).query(::row).optional().orElse(null)
 }
 fun detail(id:UUID,source:String?=null):Map<String,Any?> {
  val receipt=get(id,source) // Authorize before any host read.
  val observedAt=java.time.Instant.now()
  // A work request's job order never changes, so the read narrowed to it holds the execution observe looks for (HA design record §10).
  return observe(id,source,host.executionsOf(jobOrderIds=listOf(receipt.jobOrderId)),observedAt)
 }
 private fun observe(id:UUID,source:String?,call:HostCall<JsonNode>,observedAt:java.time.Instant):Map<String,Any?> = transactions.execute {
  get(id,source)
  jdbc.sql("SELECT receipt_id FROM ops.work_request WHERE receipt_id=:id FOR UPDATE").param("id",id).query(UUID::class.java).single()
  var receipt=get(id,source)
  val body=executionsBody(call)
  val execution=body?.get("executions")?.firstOrNull { it["jobOrderId"]?.asText()==receipt.jobOrderId }
  val blocked=journalBlocked(body,execution)
  if(execution!=null && !blocked) {
   record(receipt,body!!,execution,observedAt)
   receipt=get(id,source)
  }
  mapOf("resource" to resources.context(id),"receipt" to receipt,"hostRead" to if(body==null) "UNAVAILABLE" else "OK", "instanceId" to body?.get("instanceId"),
   "execution" to execution,"journalBlocked" to blocked,"observedAt" to observedAt.toString(),
   "latestResult" to storedResult(id,null),"rejection" to rejection(receipt),"unassigned" to unassignment(receipt),
   "deliveries" to jdbc.sql("SELECT version,state,attempts,last_error,next_attempt_at,delivered_at FROM ops.work_result_delivery WHERE receipt_id=:id ORDER BY version DESC")
    .param("id",id).query {rs,_ -> mapOf("version" to rs.getInt(1),"state" to rs.getString(2),"attempts" to rs.getInt(3),"lastError" to rs.getString(4),"nextAttemptAt" to rs.getString(5),"deliveredAt" to rs.getString(6))}.list(),
   "reviews" to jdbc.sql("SELECT request_id,actor,observation,reason,created_at,context FROM ops.work_request_review WHERE receipt_id=:id ORDER BY created_at,request_id")
    .param("id",id).query {rs,_ -> mapOf("requestId" to rs.getString(1),"actor" to rs.getString(2),"observation" to rs.getString(3),"reason" to rs.getString(4),"createdAt" to rs.getString(5),"context" to json.readTree(rs.getString(6)))}.list(),
   "recoveries" to jdbc.sql("SELECT attempt_id,actor,reason,created_at FROM ops.work_request_recovery WHERE receipt_id=:id ORDER BY created_at")
    .param("id",id).query {rs,_ -> mapOf("requestId" to rs.getString(1),"actor" to rs.getString(2),"reason" to rs.getString(3),"createdAt" to rs.getString(4))}.list(),
   "replaces" to replaces(id),"supersededBy" to supersededBy(id),
   "pendingSources" to pendingSources(receipt),
   "sourceConfirmations" to jdbc.sql("SELECT request_id,source,identity,actor,reason,created_at,context FROM ops.work_request_source_confirmation WHERE receipt_id=:id ORDER BY created_at,request_id")
    .param("id",id).query {rs,_ -> mapOf("requestId" to rs.getString(1),"source" to rs.getString(2),"identity" to rs.getString(3),"actor" to rs.getString(4),"reason" to rs.getString(5),"createdAt" to rs.getString(6),"context" to json.readTree(rs.getString(7)))}.list())
 }!!
 private fun executionsBody(call:HostCall<JsonNode>):JsonNode? = (call as? HostCall.Ok)?.value?.takeIf { it["instanceId"]?.isTextual == true && it["executions"]?.isArray == true }
 private fun journalBlocked(body:JsonNode?,execution:JsonNode?):Boolean = body?.get("journalBlockedExecutionIds")?.any { it.asText()==execution?.get("executionId")?.asText() } ?: false
 /** Records [execution] on the locked [receipt]: the observation, ACCEPTED for an uncertain submission, and under legacy polling its result. */
 private fun record(receipt:Receipt,body:JsonNode,execution:JsonNode,observedAt:java.time.Instant) {
  val instance=body.get("instanceId").asText()
  // updated_at is the "still current" basis of review, recovery and confirmation: it moves when the observed state or evidence changed, not on a refresh.
  val changed=receipt.lastExecution==null || receipt.executionInstanceId!=instance || evidence(receipt.lastExecution)!=evidence(execution)
  val observed=jdbc.sql("""UPDATE ops.work_request SET last_execution=CAST(:execution AS JSONB),execution_observed_at=:at,execution_instance_id=:instance,
    state=CASE WHEN state IN ('RECEIVED','DISPATCHING','IN_DOUBT') THEN 'ACCEPTED' ELSE state END,
    updated_at=CASE WHEN :changed OR state IN ('RECEIVED','DISPATCHING','IN_DOUBT') THEN clock_timestamp() ELSE updated_at END
    WHERE receipt_id=:id AND (execution_observed_at IS NULL OR execution_observed_at < :at)""")
   .param("execution",execution.toString()).param("at",java.time.OffsetDateTime.ofInstant(observedAt,java.time.ZoneOffset.UTC))
   .param("instance",instance).param("changed",changed).param("id",receipt.receiptId).update()
  if(observed>0 && !durableResults) captureResult(receipt,body.get("instanceId").asText(),execution)
 }
 /**
  * The execution as a person judges it changed: all of it except the source's own observation time on a permit reason, which the
  * source renews while the reason stays the same (cause, subject and resume basis still compare).
  */
 private fun evidence(execution:JsonNode):JsonNode = execution.deepCopy<JsonNode>().also {copy->
  copy.path("permitBlock").path("robots").forEach {robot->robot.path("reasons").forEach {(it as? ObjectNode)?.remove("observedAt")}}
 }
 /** One `GET /host/executions` read and the instant taken before it, as [detail] takes them. A station tick shares them (HA design record §10). */
 class ExecutionsRead(val call:HostCall<JsonNode>,val observedAt:java.time.Instant)
 /** `GET /host/executions` narrowed to the executions of [jobOrderIds] (at least one). */
 fun readExecutions(jobOrderIds:Collection<String>):ExecutionsRead {val observedAt=java.time.Instant.now();return ExecutionsRead(host.executionsOf(jobOrderIds=jobOrderIds),observedAt)}
 /** The job orders of [receipts], in job order id order. */
 fun jobOrderIds(receipts:Collection<UUID>):List<String> = if(receipts.isEmpty()) emptyList() else
  jdbc.sql("SELECT job_order_id FROM ops.work_request WHERE receipt_id IN (:ids) ORDER BY job_order_id").param("ids",receipts).query(String::class.java).list()
 /** The receipt [id] as [get] reads it and its latest stored result as [stationResult] returns it, in one statement (HA design record §10). */
 fun stationRead(id:UUID):Pair<Receipt,Map<String,Any?>?> = jdbc.sql("""SELECT w.*,v.version AS v_version,v.result_hash AS v_result_hash,v.snapshot AS v_snapshot,
   v.recorded_at AS v_recorded_at,v.acknowledged_at AS v_acknowledged_at FROM ops.work_request w
   LEFT JOIN LATERAL (SELECT * FROM ops.work_result r WHERE r.receipt_id=w.receipt_id ORDER BY r.version DESC LIMIT 1) v ON true WHERE w.receipt_id=:id""")
  .param("id",id).query { rs,i -> row(rs,i) to rs.getObject("v_version")?.let{result(rs,"v_")} }.optional()
  .orElseThrow { RequestRejected("RECEIPT_NOT_FOUND","작업 요청을 찾을 수 없습니다",404) }
 /**
  * What a station judges a receipt by: records [receipt]'s execution seen in [read] as [detail] does, then returns the latest stored
  * result, or null. Without an execution to record nothing is written and [latest], read with the receipt by [stationRead], is that result;
  * after a record it is read again. Reads no host and none of the detail fields (HA design record §10). The receipt lock is taken only when
  * there is an execution to record; [detail] writes nothing either without one.
  */
 fun stationResult(receipt:Receipt,latest:Map<String,Any?>?,read:ExecutionsRead):Map<String,Any?>? {
  val body=executionsBody(read.call)
  val execution=body?.get("executions")?.firstOrNull { it["jobOrderId"]?.asText()==receipt.jobOrderId }
  if(body==null || execution==null || journalBlocked(body,execution))return latest
  transactions.executeWithoutResult {
   jdbc.sql("SELECT receipt_id FROM ops.work_request WHERE receipt_id=:id FOR UPDATE").param("id",receipt.receiptId).query(UUID::class.java).single()
   record(get(receipt.receiptId),body,execution,read.observedAt)
  }
  return storedResult(receipt.receiptId,null)
 }
 /** The host's own REJECTED, typed from the stored submission so the screen never parses the raw record. Other closes have no host rejection. */
 fun rejection(receipt:Receipt):WorkRequestRejection? {
  if(receipt.state!="REJECTED")return null
  val outcome=receipt.submission?.get("outcome")?.takeIf{it.isObject} ?: return null
  val host=runCatching{kotlinJson.treeToValue(outcome,dev.picasso.ops.service.host.HostSubmitOutcome::class.java)}.getOrNull()
   ?.takeIf{it.result==dev.picasso.ops.service.host.HostSubmitResult.REJECTED} ?: return null
  return WorkRequestRejection(host.rejectionReason,host.remedy?.takeUnless{it.isNull},host.remedyWithheld,host.alternativeLocations,host.sourceMissing,host.sourceUnobserved,host.forgottenJobOrder)
 }
 /** The host's own UNASSIGNED, typed from the stored submission like [rejection]. A pre-dispatch UNASSIGNED has no host answer and is null. */
 fun unassignment(receipt:Receipt):WorkRequestUnassignment? {
  if(receipt.state!="UNASSIGNED")return null
  val outcome=receipt.submission?.get("outcome")?.takeIf{it.isObject} ?: return null
  val host=runCatching{kotlinJson.treeToValue(outcome,dev.picasso.ops.service.host.HostSubmitOutcome::class.java)}.getOrNull()
   ?.takeIf{it.result==dev.picasso.ops.service.host.HostSubmitResult.UNASSIGNED} ?: return null
  return WorkRequestUnassignment(host.refusals)
 }
 /** Capture host response versions, never infer success from a physical state alone. */
 private fun captureResult(receipt:Receipt, instance:String, execution:JsonNode) {
  val response=execution["jobResponse"]?.takeIf { it.isObject } ?: return
  val responseVersion=response["version"]?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt() ?: return
  val executionId=execution["executionId"]?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText() ?: return
  if(responseVersion<1 || response["jobResponseId"]?.isTextual!=true ||
     response["physicalState"]?.asText()!=execution["physicalState"]?.asText() ||
     response["requiredEvidence"]?.isTextual!=true || response["reachedEvidence"]?.isTextual!=true) return
  val snapshot=json.createObjectNode().apply {
   put("receiptId",receipt.receiptId.toString());put("sourceSystemId",receipt.sourceSystemId)
   put("externalOrderId",receipt.externalOrderId);put("jobOrderId",receipt.jobOrderId)
   put("instanceId",instance);put("executionId",executionId)
   set<JsonNode>("robotId",execution["robotId"]);set<JsonNode>("workMasterId",execution["workMasterId"])
   set<JsonNode>("missionVersion",execution["missionVersion"]);set<JsonNode>("jobResponse",response)
   set<JsonNode>("deployment",receipt.normalizedOrder?.get("deployment")?:json.nullNode())
   execution["closure"]?.takeIf{it.isObject}?.let{set<JsonNode>("closure",it)}
  }
  val hash=MessageDigest.getInstance("SHA-256").digest(canonical(snapshot).toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  transactions.executeWithoutResult {
   // All result insertion and ACK for one receipt share this durable row lock.
   jdbc.sql("SELECT receipt_id FROM ops.work_request WHERE receipt_id=:id FOR UPDATE").param("id",receipt.receiptId).query(UUID::class.java).single()
   val seen=jdbc.sql("SELECT COALESCE(max(response_version),0) FROM ops.work_result WHERE receipt_id=:id AND instance_id=:instance AND execution_id=:execution")
    .param("id",receipt.receiptId).param("instance",instance).param("execution",executionId).query(Int::class.java).single()
   val exists=jdbc.sql("SELECT count(*) FROM ops.work_result WHERE receipt_id=:id AND instance_id=:instance AND execution_id=:execution AND response_id=:responseId")
    .param("id",receipt.receiptId).param("instance",instance).param("execution",executionId).param("responseId",response["jobResponseId"].asText()).query(Long::class.java).single()>0
   if((durableResults || responseVersion>=seen) && !exists) {
    val version=jdbc.sql("""INSERT INTO ops.work_result(receipt_id,version,instance_id,execution_id,response_id,response_version,snapshot,result_hash)
      SELECT :id,COALESCE(max(version),0)+1,:instance,:execution,:responseId,:response,CAST(:snapshot AS JSONB),:hash FROM ops.work_result WHERE receipt_id=:id
      RETURNING version""")
     .param("id",receipt.receiptId).param("instance",instance).param("execution",executionId).param("response",responseVersion)
     .param("responseId",response["jobResponseId"].asText()).param("snapshot",snapshot.toString()).param("hash",hash).query(Int::class.java).single()
    // Queued with the version it delivers: a rolled-back result leaves no delivery row behind.
    delivery?.enqueue(jdbc,receipt.receiptId,version,receipt.sourceSystemId)
   }
  }
 }
 private fun storedResult(id:UUID,version:Int?):Map<String,Any?>? = jdbc.sql("""SELECT * FROM ops.work_result
   WHERE receipt_id=:id AND (:version IS NULL OR version=:version) ORDER BY version DESC LIMIT 1""")
  .param("id",id).param("version",version,java.sql.Types.INTEGER).query { rs,_ -> result(rs,"") }.optional().orElse(null)
 /** A stored result from columns named [p] followed by the `work_result` column name. */
 private fun result(rs:java.sql.ResultSet,p:String):Map<String,Any?> =
  mapOf("version" to rs.getInt("${p}version"),"resultHash" to rs.getString("${p}result_hash"),
   "snapshot" to json.readTree(rs.getString("${p}snapshot")),"recordedAt" to rs.getString("${p}recorded_at"),
   "acknowledgedAt" to rs.getString("${p}acknowledged_at"))
 fun result(id:UUID,source:String,version:Int?=null):Map<String,Any?> {
  get(id,source)
  if(version!=null && version<1) throw RequestRejected("BAD_VERSION","양의 결과 버전이 필요합니다")
  if(version==null) detail(id,source) // Observation may capture a new host response; saved versions survive host outage.
  return storedResult(id,version) ?: throw RequestRejected("RESULT_NOT_READY","저장된 결과 버전이 없습니다",404)
 }
 fun acknowledge(id:UUID,source:String,version:Int,hash:String):Map<String,Any?> = transactions.execute {
  get(id,source) // Cross-source access never reaches ACK or the host.
  jdbc.sql("SELECT receipt_id FROM ops.work_request WHERE receipt_id=:id FOR UPDATE").param("id",id).query(UUID::class.java).single()
  val result=storedResult(id,version) ?: throw RequestRejected("RESULT_NOT_FOUND","해당 결과 버전을 찾을 수 없습니다",404)
  if(result["resultHash"]!=hash) throw RequestRejected("RESULT_CONFLICT","수신한 결과 해시가 일치하지 않습니다",409)
  jdbc.sql("UPDATE ops.work_result SET acknowledged_at=COALESCE(acknowledged_at,clock_timestamp()) WHERE receipt_id=:id AND version=:version")
   .param("id",id).param("version",version).update()
  storedResult(id,version)!!
 }!!
 private fun row(rs:java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") index:Int)=Receipt(rs.getObject("receipt_id",UUID::class.java),rs.getString("source_system_id"),rs.getString("external_order_id"),rs.getString("job_order_id"),rs.getString("state"),rs.getString("detail"),json.readTree(rs.getString("payload")),rs.getString("normalized_order")?.let(json::readTree),rs.getString("submission")?.let(json::readTree),rs.getString("received_at"),rs.getString("updated_at"),rs.getString("last_execution")?.let(json::readTree),rs.getString("execution_observed_at"),rs.getString("execution_instance_id"),rs.getString("required_station_id"))
 private fun canonical(node:JsonNode):JsonNode = when {
  node.isObject -> json.createObjectNode().apply { node.fieldNames().asSequence().sorted().forEach { set<JsonNode>(it,canonical(node[it])) } }
  node.isArray -> json.createArrayNode().apply { node.forEach { add(canonical(it)) } }
  else -> node
 }
 companion object {
  /** Optional inbound field: the upstream's own earlier externalOrderId this order replaces. */
  const val REPLACES="replacesExternalOrderId"
  /** Closure kinds an operator close can write on a result. Supersede reads this set, not one literal, so another kind extends it. */
  val CLOSED_KINDS=setOf("UNRESTORED_CLOSED")
  /** The SET item closing a work request's source confirmation round (V22); written in the statement that writes the dispatch's state. */
  private const val CLOSE_ROUND="source_confirmations_closed_at=clock_timestamp()"
  /**
   * A work request's latest result version that is not acknowledged, as `u`; the caller binds `u.receipt_id`. Reads only
   * unacknowledged versions through the partial index `work_result_unacknowledged` (V20) instead of every work request's latest.
   */
  private const val UNACKNOWLEDGED_LATEST="SELECT 1 FROM ops.work_result u WHERE u.acknowledged_at IS NULL"+
   " AND NOT EXISTS(SELECT 1 FROM ops.work_result n WHERE n.receipt_id=u.receipt_id AND n.version>u.version)"
  /** A LIKE pattern matching [search] anywhere, with the LIKE wildcards and the escape character (backslash) taken literally. */
  fun containing(search:String):String = "%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
  val STATES=setOf("WAITING_RESOURCE","RECEIVED","DISPATCHING","ACCEPTED","REJECTED","UNASSIGNED","IN_DOUBT") }
}
