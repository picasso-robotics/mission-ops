package dev.picasso.ops.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.picasso.mimic.cli.MimicCli
import dev.picasso.mimic.engine.TaskState
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant

/**
 * 이름 있는 신호 값의 종류(S3b 스펙 §5.1). picasso 신호 사양의 종류와 이름이 같다. 현장은 플릿 세계([SiteFleet])를 들려고 picasso
 * `fleet-mimic` 에 의존하고 그것이 picasso 미들웨어 타입을 api 로 내지만, 셀 대역 본문은 mission-ops 안의 약속이라 picasso 의 신호 사양 타입에
 * 묶지 않고 따로 둔다. [BOOLEAN] 이면 값은 `true`·`false` 둘이고 [TEXT] 는 어떤 문자열이든 된다.
 */
enum class SignalKind { BOOLEAN, TEXT }

/**
 * 셀 대역 픽스처가 선언하는 이름 있는 신호 하나(S3b 스펙 §5.1, 결정 2). 신호 사양(이름, 자리, 종류, 안전)과 처음 값을
 * 함께 든다. 실행 호스트는 스냅숏의 신호 목록을 읽어 임무 정의를 검증하므로 신호 사양은 이 선언 하나에서 나온다.
 *
 * @param location 그 신호를 내는 자리. 없으면 `null` 이다.
 * @param safety 안전 신호인가. 안전 신호는 소프트웨어에서 쓸 수 없다(ADR 32). 셀 대역이 쓰기를 거부한다.
 * @param initial 처음 값. 값은 종류와 상관없이 문자열이다.
 */
data class SignalFixture(
    val name: String,
    val kind: SignalKind,
    val location: String? = null,
    val safety: Boolean = false,
    val initial: String,
) {
    init {
        require(name.isNotBlank() && '/' !in name) { "신호 이름이 비었거나 '/' 를 든다: '$name'" }
        require(kind != SignalKind.BOOLEAN || initial in BOOLEAN_VALUES) { "BOOLEAN 신호 $name 의 처음 값이 true·false 가 아니다: $initial" }
    }

    companion object {
        /** BOOLEAN 신호가 받는 값. picasso 검증기의 SIGNAL_VALUE_INVALID 와 같은 둘이다. */
        val BOOLEAN_VALUES: Set<String> = setOf("true", "false")
    }
}

/**
 * 셀 대역의 고정 픽스처(S3a 스펙 §6.3, S3b 스펙 §5.1). 제시 자리는 늘 점유이고 자재가 바뀌지 않는다(공급이 끝나지 않는다).
 * 슬롯은 처음에 비어 있다. 이름 있는 신호는 처음 값으로 시작하고 `POST /cell/signals/{name}` 으로만 바뀐다.
 *
 * @param presentations 제시 자리 id 와 그 자리의 자재.
 * @param slots 슬롯 id. 순서가 `GET /cell` 의 순서다.
 * @param signals 이름 있는 신호의 선언. 순서가 `GET /cell` 의 순서다.
 * @param handovers 플릿 인계 자리 id. 플릿 세계([SiteFleet])가 아는 자리이고 `GET /cell` 의 `handovers` 순서다. 플릿 목적지가 곧 이 셀 자리다.
 * @param containers 플릿 세계의 처음 용기(인계 자리 → 용기). 놓인 시각은 모른다.
 */
data class CellFixture(
    val presentations: Map<String, String>,
    val slots: List<String>,
    val signals: List<SignalFixture> = emptyList(),
    val handovers: List<String> = emptyList(),
    val containers: Map<String, String> = emptyMap(),
) {
    init {
        require(slots.distinct().size == slots.size) { "슬롯 id 가 겹친다: $slots" }
        require(presentations.keys.none { it in slots }) { "제시 자리와 슬롯이 같은 id 를 쓴다" }
        require(signals.map { it.name }.distinct().size == signals.size) { "신호 이름이 겹친다: ${signals.map { it.name }}" }
        require(handovers.distinct().size == handovers.size) { "인계 자리 id 가 겹친다: $handovers" }
        require(handovers.none { it in slots || it in presentations }) { "인계 자리가 제시 자리·슬롯과 같은 id 를 쓴다" }
        require(containers.keys.all { it in handovers }) { "처음 용기가 인계 자리 밖에 있다: ${containers.keys - handovers.toSet()}" }
    }

    companion object {
        /**
         * 런처와 시험이 쓰는 세트. 슬롯 넷이 PrepareSequencedRack 작업 지시 하나의 단위 수 상한이다.
         *
         * 기본 신호 셋은 picasso 시험 픽스처 `MissionFixtures.SIGNALS` 와 같은 사양이다. `rack_present` 는 랙 자리 `RACK-204` 가
         * 내고 처음에는 랙이 없다(`false`). `guard_closed` 는 안전 신호이고 처음에 닫혀 있다(`true`). `lot_code` 는 텍스트 신호다. inspection_ready·inspection_complete는 공유 검사 스테이션의 일반 공정 신호다.
         * inspection_job·inspection_job_done 은 그 스테이션의 작업 번호 송신·반환 텍스트 신호이고 처음에 비어 있다. 운영 서비스가 전송 전에
         * 번호를 쓰고 설비가 완료와 함께 반환한다.
         *
         * 플릿 인계 자리는 `OUT-07`(출고 인계점)과 `LINE-IN-03`(라인 투입 인계점) 둘이고 처음에 `OUT-07` 에 용기 `HU-1042` 가 있다.
         */
        val STANDARD = CellFixture(
            presentations = linkedMapOf("SEQ-IN-02.BIN-A" to "ENGINE-COVER-A"),
            slots = listOf("RACK-204.S01", "RACK-204.S02", "RACK-204.S03", "RACK-204.S04"),
            signals = listOf(
                SignalFixture("inspection_ready", SignalKind.BOOLEAN, initial = "true"),
                SignalFixture("inspection_complete", SignalKind.BOOLEAN, initial = "false"),
                SignalFixture("inspection_job", SignalKind.TEXT, initial = ""),
                SignalFixture("inspection_job_done", SignalKind.TEXT, initial = ""),
                SignalFixture("rack_present", SignalKind.BOOLEAN, location = "RACK-204", initial = "false"),
                SignalFixture("guard_closed", SignalKind.BOOLEAN, safety = true, initial = "true"),
                SignalFixture("lot_code", SignalKind.TEXT, initial = "LOT-0001"),
            ),
            handovers = listOf("OUT-07", "LINE-IN-03"),
            containers = linkedMapOf("OUT-07" to "HU-1042"),
        )
    }
}

/**
 * 셀 대역의 자리 하나. [observedAt] 이 `null` 이면 시각을 주지 않는 신호이고 읽은 순간이 그 시각이다(제시 자리).
 * 슬롯은 채운 가상 시각을 든다.
 */
data class CellPlace(val id: String, val occupied: Boolean, val material: String?, val observedAt: Instant?)

/**
 * 이름 있는 신호 하나의 지금 값(S3b 스펙 §5.2). 사양 칸 넷([name]·[location]·[kind]·[safety])은 픽스처 그대로이고
 * [value]·[observedAt] 이 바뀐다. [observedAt] 이 `null` 이면 아직 쓴 적이 없는 처음 값이고 읽은 순간이 그 시각이다.
 */
data class CellSignal(
    val name: String,
    val location: String?,
    val kind: SignalKind,
    val safety: Boolean,
    val value: String,
    val observedAt: Instant?,
)

/**
 * 셀 대역의 한 순간. 불변이며 훑기와 신호 쓰기마다 통째로 갈아 끼운다. `GET /cell` 의 본문 모양이다. [handovers] 는 플릿 세계를 셀 신호로
 * 올린 인계 자리다(점유, 자재 = 용기 신원, 관측 시각 = 놓인 시각).
 */
data class CellSnapshot(
    val presentations: List<CellPlace>,
    val slots: List<CellPlace>,
    val signals: List<CellSignal> = emptyList(),
    val handovers: List<CellPlace> = emptyList(),
)

/** 신호 쓰기의 결과. 거부는 그대로 HTTP 응답이 된다. */
sealed interface SignalWrite {
    data class Written(val signal: CellSignal) : SignalWrite

    data class Refused(val status: Int, val error: String, val detail: String) : SignalWrite
}

/**
 * 셀 대역(S3a 스펙 §6.3, 결정 6, T4). 기체가 보고한 배치에서 슬롯을 채우는 **대역**이며 독립 설비 확인이 아니다.
 *
 * ## 훑기
 *
 * [scan] 은 시계를 민 직후마다 mimic 엔진 잠금(`MimicServer.exclusive`) 아래에서 기체들의 태스크를 훑는다. 새로
 * `SUCCEEDED` 가 된 `pick_place` 를 찾으면 그 `destination` 슬롯을 «점유, 자재 = `object_id` 제시 자리의 자재, 관측 시각
 * = 지금 가상 시각» 으로 채운다. 같은 태스크는 한 번만 처리한다. 슬롯을 비우지 않는다(스펙 §12).
 *
 * 발행 경로를 감싸지 않는 이유: 감싸면 mimic 의 전송 장애 주입(S4)에 셀 대역이 같이 걸린다. 시계를 미는 쪽이 현장이므로
 * 민 직후에 엔진을 직접 읽는 것이 장애와 무관하다.
 *
 * `object_id` 가 제시 자리가 아니면 자재를 모르는 채 점유로 채운다(기체가 무언가 놓았다는 보고는 맞으므로).
 * `destination` 이 픽스처의 슬롯이 아니면 셀 밖이라 무시한다.
 *
 * ## 이름 있는 신호(S3b 스펙 §5)
 *
 * 사람이 PLC 역할을 하는 정상 조작이다(결정 3). [write] 가 같은 엔진 잠금 아래에서 값을 바꾸고 관측 시각을 지금 가상
 * 시각으로 둔다. 잠금이 훑기와 쓰기의 순서를 정하므로 한쪽이 다른 쪽의 스냅숏 교체를 덮지 않는다. 안전 신호는 쓰기를
 * 거부한다. 실제 안전 PLC 를 소프트웨어에서 쓸 수 없는 것과 같게 현장이 집행한다(ADR 32).
 *
 * ## 내는 곳
 *
 * 루프백 JDK `HttpServer` 의 `GET /cell` 과 `POST /cell/signals/{name}` 이다. `GET` 처리 스레드는 [snapshot] 만 읽고
 * 엔진에 닿지 않는다. 본문 모양과 오류 이름은 S3b JSON 계약 §1·§2 다. 같은 서버에 기체 장애 주입 `POST /faults`([SiteFaults],
 * S4a 스펙 §5)와 플릿 세계 면 `/fleet`([SiteFleet])가 붙는다.
 *
 * ## 플릿 인계(같은 세계)
 *
 * 플릿 세계([fleet])의 인계 자리를 [CellSnapshot.handovers] 로 올린다 — 용기가 있으면 점유, 자재 = 용기 신원, 관측 시각 = 미믹이 놓은 시각.
 * 실행 호스트는 플릿 단위의 목적지를 이 자리로 관측해 E2 를 세운다. 투영은 훑기([scan])와 `/fleet` 의 쓰기·명령 뒤에 엔진 잠금 아래에서
 * 갈아 끼운다. 플릿 걸음은 시계로 가므로(시계 구동) 훑기가 세계를 읽는 순간 지난 걸음이 따라잡힌다.
 *
 * @param port 0 이면 무작위(시험).
 */
class SiteCell(
    private val mimic: MimicCli.Started,
    private val fixture: CellFixture = CellFixture.STANDARD,
    port: Int = 0,
) : AutoCloseable {

    /** 이미 처리한 태스크. 기체마다 태스크 id 공간이 따로이므로 쌍으로 든다. */
    private val seen = mutableSetOf<Pair<String, String>>()

    /** 기체들이 함께 보는 가상 시계. `MimicCli` 가 시계 하나를 만들어 모든 기체에 넘긴다. */
    private val clock = requireNotNull(mimic.robotIds.minOrNull()?.let { mimic.instance(it) }) { "기체가 없는 현장이다" }.clock

    /** 플릿 세계(picasso `fleet-mimic`). 같은 루프백 서버의 `/fleet` 가 이것을 부른다. */
    val fleet = SiteFleet(mimic, fixture.handovers, fixture.containers)

    @Volatile
    var snapshot: CellSnapshot = CellSnapshot(
        presentations = fixture.presentations.map { (id, material) -> CellPlace(id, true, material, null) },
        slots = fixture.slots.map { CellPlace(it, false, null, null) },
        signals = fixture.signals.map { CellSignal(it.name, it.location, it.kind, it.safety, it.initial, null) },
        handovers = fleet.cellPlaces(),
    )
        private set

    private val json = ObjectMapper()

    /** 기체 장애 주입(S4a 스펙 §5). 같은 루프백 서버의 `POST /faults` 가 이것을 부른다. */
    val faults = SiteFaults(mimic)

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0).apply {
        createContext("/cell", ::handle)
        createContext(SiteFaults.PATH, faults::handle)
        createContext(SiteFleet.PATH) { exchange -> fleet.handle(exchange, ::project) }
        start()
    }

    /** 플릿 세계를 셀 자리로 다시 올린다. 엔진 잠금 아래에서 스냅숏을 갈아 끼운다(훑기·신호 쓰기와 순서가 정해진다). */
    fun project() = mimic.server.exclusive {
        val handovers = fleet.cellPlaces()
        if (handovers != snapshot.handovers) snapshot = snapshot.copy(handovers = handovers)
    }

    /** 열린 포트. 시험이 0 을 주면 여기서 읽는다. */
    val port: Int get() = server.address.port

    /** 시계를 민 직후에 부른다. 엔진 잠금은 재진입되므로 이미 잠금 안에서 불러도 된다. */
    fun scan() = mimic.server.exclusive {
        val filled = snapshot.slots.associateBy { it.id }.toMutableMap()
        var changed = false
        for (robotId in mimic.robotIds.sorted()) {
            val instance = mimic.instance(robotId) ?: continue
            val now = instance.clock.now()
            for (task in instance.tasks.all) {
                if (task.skillType != PICK_PLACE || task.machine.state != TaskState.SUCCEEDED) continue
                if (!seen.add(robotId to task.taskId)) continue
                val parameters = task.machine.parameters.associate { it.key to it.stringValue }
                val destination = parameters[P_DESTINATION] ?: continue
                if (destination !in filled) continue
                val material = parameters[P_OBJECT]?.let { fixture.presentations[it] }
                filled[destination] = CellPlace(destination, true, material, now)
                changed = true
            }
        }
        if (changed) {
            snapshot = snapshot.copy(slots = fixture.slots.map { filled.getValue(it) })
        }
        project()
    }

    /**
     * 이름 있는 신호 하나를 쓴다(S3b 스펙 §5.3). 판정 순서는 이름(404) → 안전(403) → 값(400)이다. 안전 신호는 값이 맞아도
     * 거부한다. 같은 값으로 다시 써도 관측 시각은 지금 가상 시각으로 바뀐다(PLC 가 다시 읽은 것과 같다).
     */
    fun write(name: String, value: String): SignalWrite = mimic.server.exclusive { writeLocked(name, value) }

    /**
     * **현장 안전 계통의 동작 대역**(ADR 32·52) — 셀 문을 열거나 닫는 것처럼 안전 계통이 안전 신호의 값을 바꾼 것을 흉내 낸다. 소프트웨어
     * 쓰기([write], `POST /cell/signals/{name}`)는 안전 신호를 여전히 거부한다. 이 함수는 HTTP 로 열지 않고 이 프로세스 안의 시험과
     * 현장 대역만 부른다.
     *
     * 안전 신호가 아니면 거부한다(일반 신호는 [write] 의 길이다). 판정 순서는 이름(404) → 안전 아님(400) → 값(400)이다. 관측 시각은 지금
     * 가상 시각이다.
     */
    fun actuateSafety(name: String, value: String): SignalWrite = mimic.server.exclusive {
        val current = snapshot.signals.firstOrNull { it.name == name }
            ?: return@exclusive SignalWrite.Refused(404, UNKNOWN_SIGNAL, "셀 대역에 없는 신호다: $name")
        if (!current.safety) return@exclusive SignalWrite.Refused(400, NOT_SAFETY_SIGNAL, "$name 은 안전 신호가 아니다. 일반 신호는 신호 쓰기로 바꾼다")
        if (current.kind == SignalKind.BOOLEAN && value !in SignalFixture.BOOLEAN_VALUES) {
            return@exclusive SignalWrite.Refused(400, SIGNAL_VALUE_INVALID, "BOOLEAN 신호 $name 은 true·false 만 받는다: '$value'")
        }
        replace(current.copy(value = value, observedAt = clock.now()))
    }

    private fun replace(written: CellSignal): SignalWrite {
        snapshot = snapshot.copy(signals = snapshot.signals.map { if (it.name == written.name) written else it })
        return SignalWrite.Written(written)
    }

    private fun writeLocked(name: String, value: String): SignalWrite {
        val current = snapshot.signals.firstOrNull { it.name == name }
            ?: return SignalWrite.Refused(404, UNKNOWN_SIGNAL, "셀 대역에 없는 신호다: $name")
        if (current.safety) {
            return SignalWrite.Refused(403, SAFETY_SIGNAL_READ_ONLY, "안전 신호 $name 은 소프트웨어에서 쓸 수 없다(ADR 32)")
        }
        if (current.kind == SignalKind.BOOLEAN && value !in SignalFixture.BOOLEAN_VALUES) {
            return SignalWrite.Refused(400, SIGNAL_VALUE_INVALID, "BOOLEAN 신호 $name 은 true·false 만 받는다: '$value'")
        }
        return replace(current.copy(value = value, observedAt = clock.now()))
    }

    private fun handle(exchange: HttpExchange) = try {
        respond(exchange)
    } finally {
        exchange.close()
    }

    private fun respond(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val (status, body) = when {
            path == "/cell" -> if (exchange.requestMethod == "GET") {
                200 to json.writeValueAsBytes(wire(snapshot))
            } else {
                exchange.responseHeaders.add("Allow", "GET")
                405 to ByteArray(0)
            }
            path.startsWith(SIGNALS_PREFIX) && path.length > SIGNALS_PREFIX.length && '/' !in path.substring(SIGNALS_PREFIX.length) ->
                if (exchange.requestMethod == "POST") {
                    signal(exchange, path.substring(SIGNALS_PREFIX.length))
                } else {
                    exchange.responseHeaders.add("Allow", "POST")
                    405 to ByteArray(0)
                }
            else -> 404 to ByteArray(0)
        }
        if (body.isNotEmpty()) exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.write(body)
    }

    /**
     * `POST /cell/signals/{name}` 하나. 본문은 `{"value": "<문자열>"}` 이다. 값이 문자열이 아니면(BOOLEAN 신호에 JSON
     * 참거짓을 준 것 포함) 400 이다. 신호 값은 종류와 상관없이 늘 문자열이다.
     *
     * `application/json` 만 받는다. 브라우저의 단순 요청(폼·`text/plain`)은 사전 요청 없이 다른 출처에서 올 수 있다.
     */
    private fun signal(exchange: HttpExchange, name: String): Pair<Int, ByteArray> {
        val contentType = exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()
        if (!contentType.equals("application/json", ignoreCase = true)) {
            return 415 to rejection(UNSUPPORTED_MEDIA_TYPE, "Content-Type 이 application/json 이 아니다: ${contentType ?: "없음"}")
        }
        val node: JsonNode? = runCatching { json.readTree(exchange.requestBody.readAllBytes()) }.getOrNull()
        val value = node?.takeIf { it.isObject }?.get("value")?.takeIf { it.isTextual }?.asText()
            ?: return 400 to rejection(BAD_REQUEST, "본문이 {\"value\": \"<문자열>\"} 모양이 아니다")
        return when (val written = write(name, value)) {
            is SignalWrite.Written -> 200 to json.writeValueAsBytes(wireSignal(written.signal))
            is SignalWrite.Refused -> written.status to rejection(written.error, written.detail)
        }
    }

    private fun rejection(error: String, detail: String): ByteArray =
        json.writeValueAsBytes(linkedMapOf("error" to error, "detail" to detail))

    override fun close() = server.stop(0)

    /**
     * 본문 모양. 시각은 ISO-8601 문자열이다. Jackson 의 날짜 모듈에 기대지 않고 여기서 적는다. 모듈이 클래스패스에서
     * 빠지면 시각이 숫자로 바뀌어 읽는 쪽(실행 호스트)이 조용히 못 읽는다.
     */
    private fun wire(snapshot: CellSnapshot): Map<String, Any> = linkedMapOf(
        "presentations" to snapshot.presentations.map(::wirePlace),
        "slots" to snapshot.slots.map(::wirePlace),
        "signals" to snapshot.signals.map(::wireSignal),
        "handovers" to snapshot.handovers.map(::wirePlace),
    )

    private fun wirePlace(place: CellPlace): Map<String, Any?> = linkedMapOf(
        "id" to place.id,
        "occupied" to place.occupied,
        "material" to place.material,
        "observedAt" to place.observedAt?.toString(),
    )

    private fun wireSignal(signal: CellSignal): Map<String, Any?> = linkedMapOf(
        "name" to signal.name,
        "location" to signal.location,
        "kind" to signal.kind.name,
        "safety" to signal.safety,
        "value" to signal.value,
        "observedAt" to signal.observedAt?.toString(),
    )

    companion object {
        /** 오류 이름(S3b JSON 계약 §2). 실행 호스트가 같은 이름을 그대로 넘긴다. */
        const val UNKNOWN_SIGNAL = "UNKNOWN_SIGNAL"
        const val SAFETY_SIGNAL_READ_ONLY = "SAFETY_SIGNAL_READ_ONLY"
        const val SIGNAL_VALUE_INVALID = "SIGNAL_VALUE_INVALID"
        const val BAD_REQUEST = "BAD_REQUEST"
        const val UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE"

        /** [actuateSafety] 에 안전 신호가 아닌 이름을 준 것. HTTP 로 나가지 않는다. */
        const val NOT_SAFETY_SIGNAL = "NOT_SAFETY_SIGNAL"

        private const val SIGNALS_PREFIX = "/cell/signals/"

        /** 계약 카탈로그의 스킬 이름과 파라미터 키. */
        private const val PICK_PLACE = "pick_place"
        private const val P_OBJECT = "object_id"
        private const val P_DESTINATION = "destination"
    }
}
