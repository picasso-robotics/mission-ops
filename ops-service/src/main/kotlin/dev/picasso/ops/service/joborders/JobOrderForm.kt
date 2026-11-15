package dev.picasso.ops.service.joborders

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** 받지 않는 폼 초안. [error] 가 사전 거부의 `error` 칸이다. 호스트에 닿지 않으므로 조작 기록에 남지 않는다. */
class FormRejection(val error: String, detail: String) : RuntimeException(detail)

/** 화면 작업 지시 폼의 초안(S3a 스펙 §9.1). 임무마다 입력이 다르다. */
sealed interface JobOrderDraft {
    val workMasterId: String

    /** 이 초안이 미들웨어에서 만들 단위 id 들. 겹침 검사가 쓴다(스펙 §7.5). */
    val unitIds: List<String>
}

data class GenericJobOrderDraft(val order:ObjectNode):JobOrderDraft {
 override val workMasterId:String get()=order["workMasterId"].asText()
 override val unitIds:List<String> get()=order["equipmentRequirements"].map{it["id"].asText()}
}

data class DeployedJobOrderDraft(val original:JobOrderDraft,val deployment:JsonNode):JobOrderDraft by original

/**
 * InspectAsset 의 점검 대상 하나. [id] 는 기체 `inspect` 스킬의 `target` 파라미터로 가므로 [JobOrderForm.MAX_TARGET_ID_LENGTH]
 * 자를 넘지 않는다. [location] 은 기체가 아는 명칭이어야 하나 S3a 는 검사하지 않는다(스펙 §12).
 */
data class InspectionTarget(val id: String, val location: String)

/** InspectAsset 초안. 대상마다 이동 단위 `<id>.travel` 과 점검 단위 `<id>` 둘이 생긴다. */
data class InspectAssetDraft(val targets: List<InspectionTarget>) : JobOrderDraft {
    override val workMasterId: String get() = JobOrderForm.INSPECT_ASSET
    override val unitIds: List<String> get() = targets.flatMap { listOf(it.id + JobOrderForm.TRAVEL_SUFFIX, it.id) }
}

/**
 * PrepareSequencedRack 초안. 단위는 슬롯마다 하나이고 단위 id 가 슬롯 id 다.
 *
 * @param presentation 자재를 집을 제시 자리. 화면이 셀 대역의 제시 자리 목록에서 자재를 고르면 정해진다(스펙 §7.5)
 */
data class PrepareSequencedRackDraft(
    val slots: List<String>,
    val material: String,
    val presentation: String,
) : JobOrderDraft {
    override val workMasterId: String get() = JobOrderForm.PREPARE_SEQUENCED_RACK
    override val unitIds: List<String> get() = slots
}

/**
 * DeliverContainer 초안(플릿 운반 하나). 단위는 운반 하나이고 단위 id 가 용기 id 다(picasso `DeliverContainer`). 로봇 단위가 없는 플릿 전용
 * 계획이라 실행 호스트는 요청 후보가 정확히 하나일 때만 받고(`FLEET_ONLY_CANDIDATES`) 그 기체는 묶인 기체(실행 주체 아님)다. 운영 서비스는
 * 배정 가능한 기체를 모두 후보로 싣으므로, 배정 가능한 기체가 둘 이상이면 호스트가 거절한다.
 *
 * @param container WMS 가 할당한 용기. 출발 인계점의 `container` 속성이 된다
 * @param source 출발 인계점(셀 대역의 플릿 인계 자리)
 * @param destination 도착 인계점(셀 대역의 플릿 인계 자리). E2 는 이 자리의 셀 신호(점유·용기 신원)다
 */
data class DeliverContainerDraft(val container: String, val source: String, val destination: String) : JobOrderDraft {
    override val workMasterId: String get() = JobOrderForm.DELIVER_CONTAINER
    override val unitIds: List<String> get() = listOf(container)
}

/**
 * 폼 초안을 읽고 작업 지시 본문(JobOrder JSON)을 만든다(S3a 스펙 §7.5). 운영 서비스는 picasso 타입을 쓰지 않으므로 칸 이름만
 * picasso `JobOrder` 와 맞춘다.
 *
 * 폼 모양:
 * - InspectAsset: `{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"}]}`
 * - PrepareSequencedRack: `{"workMasterId":"PrepareSequencedRack","slots":["RACK-204.S01"],"material":"ENGINE-COVER-A",
 *   "presentation":"SEQ-IN-02.BIN-A"}`
 * - DeliverContainer: `{"workMasterId":"DeliverContainer","container":"HU-1042","source":"OUT-07","destination":"LINE-IN-03"}`
 *
 * 그 임무가 쓰지 않는 칸은 읽지 않는다. 단위 id 가 겹치는 초안은 막는다. 겹치면 미들웨어의 태스크 id(`작업 지시 id#단위 id`)가
 * 같아져 두 단위가 한 핸들로 접힌다(스펙 §4). 장소 이름과 슬롯이 기체가 아는 명칭인지는 보지 않는다(스펙 §12).
 */
object JobOrderForm {

    const val INSPECT_ASSET = "InspectAsset"
    const val PREPARE_SEQUENCED_RACK = "PrepareSequencedRack"
    const val DELIVER_CONTAINER = "DeliverContainer"

    /** 화면에 내는 임무(입력 계약·자원 요구도 이 둘이다). DeliverContainer 는 화면 폼에 내지 않는다([FLEET_WORK_MASTERS]). */
    val WORK_MASTERS: Set<String> = setOf(INSPECT_ASSET, PREPARE_SEQUENCED_RACK)

    /**
     * 폼 API 만 받는 플릿 운반 임무. 실행 호스트에 플릿 포트(`host.fleet.url`)가 없으면 호스트가 판정에서 `FLEET_PORT_ABSENT` 로 거절하고, 플릿 전용
     * 계획이라 요청 후보가 정확히 하나가 아니면 `FLEET_ONLY_CANDIDATES` 로 거절한다. 화면 폼·입력 계약 목록에는 내지 않는다.
     */
    val FLEET_WORK_MASTERS: Set<String> = setOf(DELIVER_CONTAINER)

    /** 미들웨어가 InspectAsset 의 이동 단위 id 에 붙이는 꼬리(picasso `InspectAsset.TRAVEL_SUFFIX`). */
    const val TRAVEL_SUFFIX = ".travel"

    /**
     * 점검 대상 id 의 최대 길이. 대상 id 가 기체 `inspect` 스킬의 `target` 파라미터가 되고, 기체 프로파일이 그 파라미터를 64자로
     * 묶는다(`max_length`). 넘으면 미들웨어가 배정한 뒤 기체가 StartTask 를 거부하므로 폼에서 먼저 막는다. 길이는 mimic 의
     * 파라미터 검사처럼 UTF-16 문자 수로 센다.
     */
    const val MAX_TARGET_ID_LENGTH = 64

    const val BAD_REQUEST = "JOB_ORDER_BAD_REQUEST"
    const val UNKNOWN_WORK_MASTER = "UNKNOWN_WORK_MASTER"
    const val UNIT_ID_CONFLICT = "UNIT_ID_CONFLICT"

    /** 임무마다 고정한 요구 근거 등급(스펙 §7.5). InspectAsset 의 최고 근거는 E0 이다. */
    private val REQUIRED_EVIDENCE = mapOf(INSPECT_ASSET to "E0", PREPARE_SEQUENCED_RACK to "E2", DELIVER_CONTAINER to "E2")

    /** 못 읽으면 [FormRejection] 을 던진다. 판정 순서: 본문 객체 → WorkMaster → 그 임무의 칸 → 단위 id 겹침. */
    fun read(body: JsonNode?): JobOrderDraft {
        if (body == null || !body.isObject) throw FormRejection(BAD_REQUEST, "본문이 JSON 객체가 아니다")
        val workMasterId = text(body, "workMasterId")
        val draft = when (workMasterId) {
            INSPECT_ASSET -> InspectAssetDraft(
                objects(body, "targets").map { InspectionTarget(targetId(it), text(it, "location")) },
            )
            PREPARE_SEQUENCED_RACK -> PrepareSequencedRackDraft(
                strings(body, "slots"), text(body, "material"), text(body, "presentation"),
            )
            DELIVER_CONTAINER -> DeliverContainerDraft(text(body, "container"), text(body, "source"), text(body, "destination"))
            else -> throw FormRejection(UNKNOWN_WORK_MASTER, "받지 않는 임무다: $workMasterId (받는 것: ${(WORK_MASTERS + FLEET_WORK_MASTERS).sorted()})")
        }
        val conflicts = draft.unitIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        if (conflicts.isNotEmpty()) {
            throw FormRejection(UNIT_ID_CONFLICT, "단위 id 가 겹친다: ${conflicts.joinToString(", ")}")
        }
        return draft
    }

    /**
     * 작업 지시 본문. 버전은 1, 파라미터는 비어 있고, 요구 근거 등급은 임무마다 고정이다.
     *
     * PrepareSequencedRack 의 자재 요구는 슬롯 수와 같아야 미들웨어 게이트의 정합 검사를 지난다(스펙 §4). 제시 자리는 하나이며
     * 미들웨어가 슬롯의 `material` 속성으로 그 자리를 찾는다.
     */
    fun jobOrder(draft: JobOrderDraft, jobOrderId: String, json: ObjectMapper): ObjectNode {
        if(draft is DeployedJobOrderDraft) return jobOrder(draft.original,jobOrderId,json).apply {
            put("missionVersion",draft.deployment["missionVersion"].asText())
            set<JsonNode>("deployment",draft.deployment)
        }
        if(draft is GenericJobOrderDraft)return draft.order.deepCopy().put("jobOrderId",jobOrderId)
        val order = json.createObjectNode()
            .put("jobOrderId", jobOrderId)
            .put("workMasterId", draft.workMasterId)
            .put("version", 1)
            .put("requiredEvidence", REQUIRED_EVIDENCE.getValue(draft.workMasterId))
        order.putObject("parameters")
        val materials = order.putArray("materialRequirements")
        val equipment = order.putArray("equipmentRequirements")
        fun requirement(id: String, use: String, property: String, value: String) {
            equipment.addObject().put("id", id).put("equipmentUse", use).putObject("properties").put(property, value)
        }
        when (draft) {
            is GenericJobOrderDraft -> error("범용 초안은 먼저 변환해야 합니다")
            is DeployedJobOrderDraft -> error("배포 초안은 먼저 변환해야 합니다")
            is InspectAssetDraft -> draft.targets.forEach { requirement(it.id, "inspection_target", "location", it.location) }
            is PrepareSequencedRackDraft -> {
                materials.addObject().put("materialDefinitionId", draft.material).put("quantity", draft.slots.size)
                draft.slots.forEach { requirement(it, "destination", "material", draft.material) }
                requirement(draft.presentation, "source", "material", draft.material)
            }
            is DeliverContainerDraft -> {
                requirement(draft.source, "source", "container", draft.container)
                equipment.addObject().put("id", draft.destination).put("equipmentUse", "destination").putObject("properties")
            }
        }
        return order
    }

    /** 비어 있지 않은 문자열 칸. */
    private fun text(node: JsonNode, field: String): String =
        node.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
            ?: throw FormRejection(BAD_REQUEST, "$field 가 비어 있지 않은 문자열이 아니다")

    /** 점검 대상 id. 비어 있지 않고 [MAX_TARGET_ID_LENGTH] 자 이하다. */
    private fun targetId(node: JsonNode): String {
        val id = text(node, "id")
        if (id.length > MAX_TARGET_ID_LENGTH) {
            throw FormRejection(BAD_REQUEST, "대상 id 가 ${MAX_TARGET_ID_LENGTH}자를 넘는다: ${id.length}자")
        }
        return id
    }

    /** 비어 있지 않은 객체 배열. */
    private fun objects(node: JsonNode, field: String): List<JsonNode> {
        val array = node.get(field)
        if (array == null || !array.isArray || array.isEmpty || array.any { !it.isObject }) {
            throw FormRejection(BAD_REQUEST, "$field 가 비어 있지 않은 객체의 배열이 아니다")
        }
        return array.toList()
    }

    /** 비어 있지 않은 문자열의 비어 있지 않은 배열. */
    private fun strings(node: JsonNode, field: String): List<String> {
        val array = node.get(field)
        if (array == null || !array.isArray || array.isEmpty || array.any { !it.isTextual || it.asText().isBlank() }) {
            throw FormRejection(BAD_REQUEST, "$field 가 비어 있지 않은 문자열의 배열이 아니다")
        }
        return array.map { it.asText() }
    }
}
