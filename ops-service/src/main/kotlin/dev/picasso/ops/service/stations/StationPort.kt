package dev.picasso.ops.service.stations

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.cell.CellSignalOperations
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.interfaces.MissionInterfaces
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.requests.Receipt
import dev.picasso.ops.service.requests.WorkRequests
import java.time.Instant
import java.util.UUID

data class StationSignal(val value: Boolean, val observedAt: Instant?)
/** A declared non-safety TEXT signal: the job-number command and its echo. */
data class StationText(val value: String, val observedAt: Instant?)
/** One cell read. A completion and its job-number echo are judged from the same snapshot. */
data class StationSignals(val flags: Map<String, StationSignal>, val texts: Map<String, StationText> = emptyMap())
/**
 * [detail] is the work request's own line; a rejected run carries it so the station history names the actual reason.
 * [sourceUnobserved]: the host's own UNASSIGNED left a source unobserved. Only a person's source confirmation sends it again.
 * [permitBlock]: the work permit block of the receipt's execution in this read (`permitBlock` of `GET /host/executions`, ADR 52): a unit
 * held PENDING because a robot command is withheld. Null when none, and when the executions could not be read: an unread block is no block.
 */
/**
 * One robot's work permit as the host judged it (ADR 52; same rule as the issuing gate). [permit] is PERMITTED, INHIBITED or UNKNOWN.
 * [causes] are picasso PermitCause names, [subjects] what blocks, [resumeBasis] what lifts it.
 */
data class StationRobotPermit(val robotId: String, val permit: String, val causes: List<String>, val subjects: List<String>, val resumeBasis: List<String>)
/**
 * The work permit a station reads before its equipment is told to start. An information projection of the safety system, never a safety function.
 * [Undeclared]: the site declared no permit mapping, stations behave as before. [Read]: every robot the mapping governs; a station may send any
 * of them, so one robot not PERMITTED holds it. [Unreadable]: the host answered but the permit is not readable, closed, holds.
 * [HostUnreachable]: the host gave no answer at all (transport failure, time-out, a thrown read), closed, holds. Not a statement about the permit.
 */
sealed interface StationPermit {
 data object Undeclared : StationPermit
 data class Read(val robots: List<StationRobotPermit>) : StationPermit { val blocking: List<StationRobotPermit> get() = robots.filter { it.permit != "PERMITTED" } }
 data class Unreadable(val cause: String) : StationPermit
 data class HostUnreachable(val cause: String) : StationPermit
}
/** What a station port proved about a work request closed without executing (B3, [StationPort.proof]). */
enum class StationProof { NEVER_SENT, EXECUTION_EXISTS, UNPROVEN }
data class StationReceipt(val id: UUID, val state: String, val safeToRetry: Boolean, val updatedAt: String, val physicalState: String?, val snapshot: JsonNode?, val resultVersion: Int? = null, val detail: String? = null, val sourceUnobserved: Boolean = false, val permitBlock: JsonNode? = null)
interface StationPort {
 fun validate(payload: JsonNode)
 fun signals(): Map<String, StationSignal>?
 fun read(): StationSignals? = signals()?.let{StationSignals(it)}
 /** The host's work permit (ADR 52). Ports without a host keep today's behaviour. */
 fun permit(): StationPermit = StationPermit.Undeclared
 /** Writes a TEXT command signal on the site. True only when the site holds the value. */
 fun write(name: String, value: String, actor: Actor): Boolean
 fun dispatch(id: UUID, payload: JsonNode, actor: Actor): StationReceipt
 fun receipt(id: UUID): StationReceipt
 /**
  * True when work request [receipt] closed without executing: a pre-dispatch or host UNASSIGNED, or a REJECTED other than a forgotten job
  * order, with no execution observed and no stored result, and the execution host confirmed withdrawing its job order (B3). The ops
  * database alone is no proof: a no-value approval on the host can start the job order without passing through ops. After the withdrawal
  * no robot can run it. Read fresh, so an execution a [receipt] call just recorded counts. May call the host: never under a station lock.
  * Ports that cannot tell keep false: a run they sent stays one that may have reached a robot.
  */
 fun unsent(receipt: UUID): Boolean = false
 /** [unsent] with who asks for the withdrawal and why ([requestedBy], [reason]: a person's site release names that person and their reason). */
 fun unsent(receipt: UUID, requestedBy: String, reason: String): Boolean = proof(receipt, requestedBy, reason) == StationProof.NEVER_SENT
 /**
  * What [unsent] found, kept apart: [StationProof.NEVER_SENT] (the host withdrew the job order), [StationProof.EXECUTION_EXISTS] (the host
  * reports an execution of it instead; the port moved the receipt to ACCEPTED) or [StationProof.UNPROVEN] (anything else, no answer included).
  * Ports that cannot tell the host's answer apart derive it from [unsent].
  */
 fun proof(receipt: UUID, requestedBy: String, reason: String): StationProof = if (unsent(receipt)) StationProof.NEVER_SENT else StationProof.UNPROVEN
 /**
  * Runs one tick's observations of [receipts]. A port may share host reads covering them across the [receipt] calls inside [block]; a write
  * it sends there is followed by a new read (HA design record §10). Calls outside [block] read the host themselves.
  */
 fun <T> observing(receipts: Collection<UUID>, block: () -> T): T = block()
 fun retryForRun(receipt:StationReceipt,attempt:UUID,actor:Actor,runId:UUID):StationReceipt=retry(receipt,attempt,actor)
 fun retry(receipt: StationReceipt, attempt: UUID, actor: Actor): StationReceipt
 /** Reads a source confirmation body before the run is claimed; throws the work request's refusal. */
 fun checkSourceConfirmation(payload: JsonNode) {}
 /** Records a person's source confirmation on the run's receipt and submits it again. */
 fun confirmSourceForRun(receipt: StationReceipt, actor: Actor, payload: JsonNode, runId: UUID): StationReceipt = error("출발 자리 확인을 받지 않는 포트입니다")
}
/**
 * Uses durable receipts; an uncertain submission is never sent again.
 * [chunk]: job orders per narrowed `GET /host/executions` of a tick, so the request line stays short.
 */
class OpsStationPort(private val requests: WorkRequests, private val interfaces: MissionInterfaces, private val host: HostReads, private val cell: CellSignalOperations? = null, private val chunk: Int = READ_CHUNK): StationPort {
 private val json=ObjectMapper()
 override fun validate(payload:JsonNode) {
  val wm=interfaces.get(payload["interfaceId"].asText(),payload["interfaceVersion"].asInt()).workMasterId
  val required=requests.resources.required(wm)
  require(required==null || payload["stationId"]?.asText()==required) { "임무가 요구하는 스테이션을 선택하세요" }
  val draft=interfaces.draft(payload["interfaceId"].asText(),payload["interfaceVersion"].asInt(),payload["input"],true)
  val deployment=(draft as dev.picasso.ops.service.joborders.DeployedJobOrderDraft).deployment
  require(deployment["deploymentVersion"].asInt()==payload["expectedDeploymentVersion"].asInt()) { "조회한 계약 배포가 변경되었습니다" }
 }
 override fun signals()=read()?.flags
 override fun read():StationSignals? {
  val list=(host.cell() as? HostCall.Ok)?.value?.path("cell")?.path("signals")?.takeIf{it.isArray} ?: return null
  val plain=list.filter{it.path("safety").isBoolean && !it.path("safety").asBoolean() && it.path("value").isTextual}
  fun at(node:JsonNode)=node.path("observedAt").takeIf{it.isTextual}?.asText()?.let(Instant::parse)
  return StationSignals(plain.filter{it.path("kind").asText()=="BOOLEAN" && it.path("value").asText() in setOf("true","false")}.associate{it.path("name").asText() to StationSignal(it.path("value").asText()=="true",at(it))},
   plain.filter{it.path("kind").asText()=="TEXT"}.associate{it.path("name").asText() to StationText(it.path("value").asText(),at(it))})
 }
 /** No answer from the host is [StationPermit.HostUnreachable]. Any answer that is not a readable permit (error status, odd body) is [StationPermit.Unreadable]. */
 override fun permit():StationPermit = when(val call=host.workPermits()) {
  is HostCall.Ok -> permitOf(call.value)
  is HostCall.Silent -> if(call.unreached) StationPermit.HostUnreachable(call.cause) else permitOf(null)
 }
 /** Same path as an operator's signal write (host → site), logged; a lost response counts only when the requery shows the value. */
 override fun write(name:String,value:String,actor:Actor):Boolean {
  val outcome=(cell ?: return false).write(actor,name,value)
  return outcome.result==OperationResult.SUCCEEDED || outcome.confirmation==OperationResult.CONFIRMED_APPLIED
 }
 /**
  * One tick's execution reads (HA design record §10): the job orders of the tick's receipts in chunks, each read at the first receipt that
  * needs it, and job order → the read covering it. Per thread: decisions run on request threads.
  */
 private class Round(val receipts:Collection<UUID>) {var chunks:List<List<String>>?=null;val reads=HashMap<String,WorkRequests.ExecutionsRead>()}
 private val round=ThreadLocal<Round?>()
 override fun <T> observing(receipts:Collection<UUID>,block:()->T):T {
  round.set(Round(receipts.toList()))
  try {return block()} finally {round.remove()}
 }
 /** The execution read covering [receipt]'s job order: its chunk of this tick, else a read of that job order alone. Never the whole list. */
 private fun executions(receipt:Receipt):WorkRequests.ExecutionsRead {
  val shared=round.get() ?: return requests.readExecutions(listOf(receipt.jobOrderId))
  shared.reads[receipt.jobOrderId]?.let{return it}
  val chunks=shared.chunks ?: requests.jobOrderIds(shared.receipts).chunked(chunk).also{shared.chunks=it}
  val ids=chunks.firstOrNull{receipt.jobOrderId in it} ?: listOf(receipt.jobOrderId)
  return requests.readExecutions(ids).also{read->ids.forEach{shared.reads[it]=read}}
 }
 /** A host write changes the executions, sent or not: the next receipt in this tick reads them again. */
 private fun sent(write:()->UUID):StationReceipt {
  val id=try {write()} finally {round.get()?.reads?.clear()}
  return mapped(id)
 }
 override fun dispatch(id:UUID,payload:JsonNode,actor:Actor):StationReceipt {
  if(payload["receiptId"]!=null)return sent{requests.dispatchQueued(UUID.fromString(payload["receiptId"].asText()),actor).receipt.receiptId}
  val body=json.createObjectNode().put("requestId",id.toString()).put("expectedDeploymentVersion",payload["expectedDeploymentVersion"].asInt())
  body.set<JsonNode>("input",payload["input"])
  return sent{requests.receiveInterface(actor,payload["interfaceId"].asText(),payload["interfaceVersion"].asInt(),body,id).receipt.receiptId}
 }
 override fun receipt(id:UUID)=mapped(id)
 override fun unsent(receipt:UUID):Boolean = proof(receipt,"system/station",WITHDRAW_REASON)==StationProof.NEVER_SENT
 override fun proof(receipt:UUID,requestedBy:String,reason:String):StationProof {
  val (row,latest)=requests.stationRead(receipt)
  if(!(row.state in setOf("UNASSIGNED","REJECTED") && row.lastExecution==null && latest==null && requests.rejection(row)?.forgottenJobOrder!=true))return StationProof.UNPROVEN
  // The only proof of never sent is the host's withdrawal. An execution it reports instead promotes the receipt (observation); no answer proves nothing.
  return when(requests.withdrawUnsent(row,reason,requestedBy)) {
   is dev.picasso.ops.service.requests.Withdrawal.Withdrawn -> StationProof.NEVER_SENT
   is dev.picasso.ops.service.requests.Withdrawal.Executed -> StationProof.EXECUTION_EXISTS
   is dev.picasso.ops.service.requests.Withdrawal.Unknown -> StationProof.UNPROVEN
  }
 }
 private fun mapped(id:UUID):StationReceipt {
  val (receipt,latest)=requests.stationRead(id)
  // Use stored result evidence. A missing current host execution never means the work did not start.
  val read=runCatching{executions(receipt)}.getOrNull()
  val stored=read?.let{runCatching{requests.stationResult(receipt,latest,it)}.getOrNull()};val saved=stored?.get("snapshot") as? JsonNode
  // The block of this read's execution of the job order, as the host reports it now (never the stored last execution).
  val block=(read?.call as? HostCall.Ok)?.value?.path("executions")?.firstOrNull{it.path("jobOrderId").asText()==receipt.jobOrderId}?.path("permitBlock")?.takeIf{it.isObject}
  return StationReceipt(id,receipt.state,receipt.state=="UNASSIGNED" && receipt.submission==null && receipt.lastExecution==null,receipt.updatedAt,saved?.path("jobResponse")?.path("physicalState")?.takeIf{it.isTextual}?.asText(),saved,stored?.get("version") as? Int,receipt.detail,
   requests.pendingSources(receipt).isNotEmpty(),block)
 }
 override fun checkSourceConfirmation(payload:JsonNode) {requests.sourceConfirmationRequest(payload)}
 override fun confirmSourceForRun(receipt:StationReceipt,actor:Actor,payload:JsonNode,runId:UUID):StationReceipt {
  return sent{requests.confirmSource(receipt.id,actor,payload,runId);receipt.id}
 }
 override fun retry(receipt:StationReceipt,attempt:UUID,actor:Actor):StationReceipt=error("자원 작업 ID가 필요합니다")
 override fun retryForRun(receipt:StationReceipt,attempt:UUID,actor:Actor,runId:UUID):StationReceipt {
  val body=json.createObjectNode().put("requestId",attempt.toString()).put("expectedUpdatedAt",receipt.updatedAt).put("reason","공유 스테이션의 전송 전 미배정 재평가")
  return sent{requests.recover(receipt.id,actor,body,runId);receipt.id}
 }
}
/** Why a station asks the host to withdraw a job order it found closed without executing (B3). */
const val WITHDRAW_REASON="공유 스테이션 자원 해제: 실행 없이 끝난 작업 요청의 작업 지시 철회"
/** Job orders per narrowed execution read: 50 `JO-<uuid>` keep the request line near 2.6 KB, under the host's 8 KB header limit. */
const val READ_CHUNK=50
/** Reads `GET /host/work-permits`. Anything unreadable is [StationPermit.Unreadable]: a permit not known is not a permit. */
internal fun permitOf(body:JsonNode?):StationPermit {
 val declared=body?.path("declared")?.takeIf{it.isBoolean} ?: return StationPermit.Unreadable("작업 허가를 읽지 못했다")
 if(!declared.asBoolean())return StationPermit.Undeclared
 val robots=body.path("robots").takeIf{it.isArray} ?: return StationPermit.Unreadable("작업 허가 본문에 robots 가 없다")
 fun texts(node:JsonNode,key:String)=node.path("reasons").filter{it.path(key).isTextual}.map{it.path(key).asText()}.distinct()
 return StationPermit.Read(robots.map{node->
  val id=node.path("robotId").takeIf{it.isTextual}?.asText();val permit=node.path("permit").takeIf{it.isTextual}?.asText()
  if(id==null || permit==null)return StationPermit.Unreadable("작업 허가 본문의 기체 줄을 읽지 못했다")
  StationRobotPermit(id,permit,texts(node,"cause"),texts(node,"subject"),texts(node,"resumeBasis"))
 })
}
/** REWORK of a resource-managed receipt goes through station coordination. Executions without one are forwarded unchanged. */
class StationReworkGuard(private val requests:WorkRequests,private val stations:StationCoordinator):dev.picasso.ops.service.incidents.ReworkGuard {
 private val json=ObjectMapper()
 override fun guard(actor:Actor,executionId:String,instanceId:String,forward:()->dev.picasso.ops.service.incidents.HoldResolveOutcome):dev.picasso.ops.service.incidents.HoldResolveOutcome {
  val receipt=requests.executionReceipt(instanceId,executionId)
  if(receipt?.requiredStationId==null)return forward()
  val context=json.createObjectNode().put("executionId",executionId).put("instanceId",instanceId).put("receiptId",receipt.receiptId.toString())
  return stations.rework(receipt.receiptId,actor,context,forward) {when {
   it.result==OperationResult.SUCCEEDED || it.confirmation==OperationResult.CONFIRMED_APPLIED -> true
   it.result==OperationResult.REJECTED || it.confirmation==OperationResult.CONFIRMED_NOT_APPLIED -> false
   else -> null
  }}
 }
}
