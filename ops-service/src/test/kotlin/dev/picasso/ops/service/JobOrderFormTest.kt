package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.joborders.DeliverContainerDraft
import dev.picasso.ops.service.joborders.FormRejection
import dev.picasso.ops.service.joborders.InspectAssetDraft
import dev.picasso.ops.service.joborders.InspectionTarget
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.joborders.PrepareSequencedRackDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 폼 초안을 읽고 작업 지시 본문을 만든다(S3a 스펙 §7.5). 단위 id 가 겹치는 초안은 막는다. */
class JobOrderFormTest {

    private val json = ObjectMapper()

    private fun read(form: String) = JobOrderForm.read(json.readTree(form))

    private fun rejected(form: String?): FormRejection =
        assertFailsWith<FormRejection> { JobOrderForm.read(form?.let { runCatching { json.readTree(it) }.getOrNull() }) }

    @Test
    fun `InspectAsset 폼은 대상마다 inspection_target 장비 요구가 되고 요구 근거 등급은 E0 이다`() {
        val draft = read(
            """{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"},{"id":"T2","location":"bay-9"}],
               "slots":["무시된다"]}""",
        )
        assertEquals(InspectAssetDraft(listOf(InspectionTarget("T1", "bay-7"), InspectionTarget("T2", "bay-9"))), draft)
        assertEquals(listOf("T1.travel", "T1", "T2.travel", "T2"), draft.unitIds)
        assertEquals(
            json.readTree(
                """{"jobOrderId":"JO-1","workMasterId":"InspectAsset","version":1,"requiredEvidence":"E0","parameters":{},
                   "materialRequirements":[],
                   "equipmentRequirements":[
                     {"id":"T1","equipmentUse":"inspection_target","properties":{"location":"bay-7"}},
                     {"id":"T2","equipmentUse":"inspection_target","properties":{"location":"bay-9"}}]}""",
            ),
            JobOrderForm.jobOrder(draft, "JO-1", json),
        )
    }

    @Test
    fun `DeliverContainer 폼은 용기 속성의 출발 인계점과 도착 인계점이 되고 단위는 용기 하나이며 요구 근거 등급은 E2 다`() {
        val draft = read("""{"workMasterId":"DeliverContainer","container":"HU-1042","source":"OUT-07","destination":"LINE-IN-03"}""")
        assertEquals(DeliverContainerDraft("HU-1042", "OUT-07", "LINE-IN-03"), draft)
        assertEquals(listOf("HU-1042"), draft.unitIds)
        assertEquals(
            json.readTree(
                """{"jobOrderId":"JO-3","workMasterId":"DeliverContainer","version":1,"requiredEvidence":"E2","parameters":{},
                   "materialRequirements":[],
                   "equipmentRequirements":[
                     {"id":"OUT-07","equipmentUse":"source","properties":{"container":"HU-1042"}},
                     {"id":"LINE-IN-03","equipmentUse":"destination","properties":{}}]}""",
            ),
            JobOrderForm.jobOrder(draft, "JO-3", json),
        )
        // 화면 폼·입력 계약의 임무 목록에는 들지 않는다.
        assertEquals(setOf("InspectAsset", "PrepareSequencedRack"), JobOrderForm.WORK_MASTERS)
    }

    @Test
    fun `PrepareSequencedRack 폼은 슬롯마다 destination 과 제시 자리 하나의 source 가 되고 자재 수는 슬롯 수이며 요구 근거 등급은 E2 다`() {
        val draft = read(
            """{"workMasterId":"PrepareSequencedRack","slots":["RACK-204.S01","RACK-204.S02"],"material":"ENGINE-COVER-A",
               "presentation":"SEQ-IN-02.BIN-A"}""",
        )
        assertEquals(PrepareSequencedRackDraft(listOf("RACK-204.S01", "RACK-204.S02"), "ENGINE-COVER-A", "SEQ-IN-02.BIN-A"), draft)
        assertEquals(
            json.readTree(
                """{"jobOrderId":"JO-2","workMasterId":"PrepareSequencedRack","version":1,"requiredEvidence":"E2","parameters":{},
                   "materialRequirements":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":2}],
                   "equipmentRequirements":[
                     {"id":"RACK-204.S01","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},
                     {"id":"RACK-204.S02","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},
                     {"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}]}""",
            ),
            JobOrderForm.jobOrder(draft, "JO-2", json),
        )
    }

    @Test
    fun `대상 id 가 겹치면 UNIT_ID_CONFLICT 다`() {
        val e = rejected("""{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"a"},{"id":"T1","location":"b"}]}""")
        assertEquals(JobOrderForm.UNIT_ID_CONFLICT, e.error)
        assertEquals("단위 id 가 겹친다: T1, T1.travel", e.message)
    }

    @Test
    fun `대상 id 가 다른 대상의 이동 단위 id 와 같으면 UNIT_ID_CONFLICT 다`() {
        val e = rejected(
            """{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"a"},{"id":"T1.travel","location":"b"}]}""",
        )
        assertEquals(JobOrderForm.UNIT_ID_CONFLICT, e.error)
        assertEquals("단위 id 가 겹친다: T1.travel", e.message)
        // 이동 단위 id 꼴이어도 짝이 없으면 겹치지 않는다(단위는 T1.travel.travel 과 T1.travel).
        read("""{"workMasterId":"InspectAsset","targets":[{"id":"T1.travel","location":"b"}]}""")
    }

    @Test
    fun `슬롯이 겹치면 UNIT_ID_CONFLICT 다`() {
        val e = rejected(
            """{"workMasterId":"PrepareSequencedRack","slots":["S1","S2","S1"],"material":"M","presentation":"P"}""",
        )
        assertEquals(JobOrderForm.UNIT_ID_CONFLICT, e.error)
        assertEquals("단위 id 가 겹친다: S1", e.message)
    }

    @Test
    fun `대상 id 가 inspect target 파라미터 한도 64자를 넘으면 JOB_ORDER_BAD_REQUEST 다`() {
        val limit = "T".repeat(JobOrderForm.MAX_TARGET_ID_LENGTH)
        assertEquals(64, limit.length)
        read("""{"workMasterId":"InspectAsset","targets":[{"id":"$limit","location":"a"}]}""")
        val e = rejected("""{"workMasterId":"InspectAsset","targets":[{"id":"${limit}X","location":"a"}]}""")
        assertEquals(JobOrderForm.BAD_REQUEST, e.error)
        assertEquals("대상 id 가 64자를 넘는다: 65자", e.message)
    }

    @Test
    fun `받지 않는 임무는 UNKNOWN_WORK_MASTER 이고 깨진 폼은 JOB_ORDER_BAD_REQUEST 다`() {
        assertEquals(JobOrderForm.UNKNOWN_WORK_MASTER, rejected("""{"workMasterId":"Teleport"}""").error)
        // DeliverContainer 는 받는 임무지만 칸이 비면 깨진 폼이다.
        assertEquals(JobOrderForm.BAD_REQUEST, rejected("""{"workMasterId":"DeliverContainer"}""").error)
        listOf(
            null,
            "작업 지시",
            "[]",
            """{"targets":[{"id":"T1","location":"a"}]}""",
            """{"workMasterId":"InspectAsset"}""",
            """{"workMasterId":"InspectAsset","targets":[]}""",
            """{"workMasterId":"InspectAsset","targets":[{"id":"T1"}]}""",
            """{"workMasterId":"InspectAsset","targets":[{"id":" ","location":"a"}]}""",
            """{"workMasterId":"InspectAsset","targets":["T1"]}""",
            """{"workMasterId":"PrepareSequencedRack","slots":[],"material":"M","presentation":"P"}""",
            """{"workMasterId":"PrepareSequencedRack","slots":["S1",3],"material":"M","presentation":"P"}""",
            """{"workMasterId":"PrepareSequencedRack","slots":["S1"],"presentation":"P"}""",
            """{"workMasterId":"PrepareSequencedRack","slots":["S1"],"material":"M"}""",
        ).forEach { form -> assertEquals(JobOrderForm.BAD_REQUEST, rejected(form).error, form) }
    }
}
