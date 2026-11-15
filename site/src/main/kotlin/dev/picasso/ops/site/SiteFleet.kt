package dev.picasso.ops.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import dev.picasso.fleetmimic.AmrFleetMimic
import dev.picasso.middleware.TransportHandle
import dev.picasso.middleware.TransportOrder
import dev.picasso.middleware.TransportState
import dev.picasso.middleware.TransportStatus
import dev.picasso.mimic.cli.MimicCli
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/** 세계 채우기의 결과. 거부는 그대로 HTTP 응답이 된다. */
sealed interface FleetWorldWrite {
    data object Written : FleetWorldWrite

    data class Refused(val status: Int, val error: String, val detail: String) : FleetWorldWrite
}

/**
 * 현장의 플릿 세계(picasso `fleet-mimic` 의 `AmrFleetMimic` 하나). 운반을 D 수준으로 위임받는 플릿의 **대역**이며 실 플릿도 벤더 API 의 재현도
 * 아니다(picasso §15.326). 셀 대역([SiteCell])이 이것을 들고 같은 루프백 서버에 `/fleet` 면을 붙인다.
 *
 * ## 세계와 자리
 *
 * 플릿이 아는 자리는 셀 대역 픽스처의 인계 자리([CellFixture.handovers]) 뿐이다 — 플릿 목적지가 곧 셀 자리다(같은 세계). 그 밖의 자리를 출발지나
 * 목적지로 든 운반은 맡지 않는다(핸들 없음, 계약의 «플릿이 거절»). 세계 채우기도 인계 자리에만 받는다(404 [UNKNOWN_PLACE]). 처음 세계는 픽스처의
 * 처음 용기([CellFixture.containers])이고 놓인 시각은 모른다.
 *
 * ## 시계
 *
 * 미믹은 **시계 구동**이다([STEP] 마다 한 걸음). 시계는 현장 가상 시계이고 `MimicServer.exclusive` 아래에서 읽는다(가시성). 미믹은 시계를 자기
 * 자물쇠 밖에서 읽으므로 엔진 잠금과 미믹 자물쇠의 순서가 늘 «엔진 → 미믹» 이라 교착하지 않는다. 미믹은 스레드를 띄우지 않고 공개 호출마다 지난
 * 걸음을 따라잡는다 — 셀 대역의 훑기([SiteCell.scan])가 시계를 민 직후 세계를 읽으므로 아무도 묻지 않아도 세계가 시계를 따라간다.
 *
 * ## HTTP 면(mission-ops 안의 약속, picasso 계약 아님)
 *
 * [AmrFleetPort][dev.picasso.middleware.AmrFleetPort] 를 대는 최소 면과 현장 운영 면이다. 본문은 JSON 이고 `POST` 는 `application/json` 만 받는다
 * (셀 대역의 신호 쓰기와 같은 까닭). 오류 본문은 `{error, detail}` 이다.
 *
 * - `POST /fleet/transports` `{reference, containerId, source, destination}` → `{handle}`(맡지 않았으면 널). 같은 참조는 같은 핸들이다.
 * - `GET /fleet/transports?reference=` → `{handle}`. 찾기만 하고 새로 맡지 않는다.
 * - `GET /fleet/transports/{handle}` → `{state, observedContainer, holding, detail}`. 모르는 핸들은 `FAILED`·«모르는 운반»(던지지 않는다).
 *
 * **핸들은 이 프로세스의 기동 id 를 앞에 단다**(`<기동 id>:tr-N`, [boot]). 미믹은 기동마다 `tr-1` 부터 세므로, 접두가 없으면 현장 재기동 뒤 옛 핸들이
 * 새 세계의 남의 운반을 가리킬 수 있다. 접두가 다른 핸들은 미믹에 묻지 않고 «모르는 운반»(상태 `FAILED`, 취소 `false`)이다.
 * - `POST /fleet/transports/{handle}/cancel` → `{accepted}`.
 * - `GET /fleet/world` → `{places, blocked, dispatches}`. `places` 는 인계 자리 순서의 `{location, containerId, placedAt}` 이다.
 * - `POST /fleet/world/{place|clear|block|unblock}` `{location[, containerId]}` → `GET /fleet/world` 와 같은 본문.
 *
 * 세계는 프로세스 메모리에만 있다. 현장을 재기동하면 장부가 비고 호스트가 든 핸들은 «모르는 운반» 이 된다(보장 아님, `docs/guarantees.md`).
 */
class SiteFleet(
    mimic: MimicCli.Started,
    val places: List<String>,
    containers: Map<String, String> = emptyMap(),
    stepEvery: Duration = STEP,
) {
    init {
        require(places.distinct().size == places.size) { "인계 자리 id 가 겹친다: $places" }
        require(containers.keys.all { it in places }) { "처음 용기가 인계 자리 밖에 있다: ${containers.keys - places.toSet()}" }
    }

    private val clock = requireNotNull(mimic.robotIds.minOrNull()?.let { mimic.instance(it) }) { "기체가 없는 현장이다" }.clock

    /** 현장 가상 시각. 엔진 잠금 아래에서 읽는다(가시성). */
    private val now: () -> Instant = { mimic.server.exclusive { clock.now() } }

    /** 이 프로세스의 기동 id. 바깥 핸들의 접두다(현장 재기동 뒤 옛 핸들이 새 운반을 가리키지 않게). */
    val boot: String = java.util.UUID.randomUUID().toString().substring(0, 8)

    /** 세계. 시험이 직접 관측할 때 쓴다(맡긴 횟수 등). 이 객체의 핸들에는 [boot] 접두가 없다. */
    val world = AmrFleetMimic(now = now, stepEvery = stepEvery)

    private val initial = containers

    init {
        initial.forEach { (location, containerId) -> world.place(location, containerId, now()) }
    }

    /**
     * 처음 용기를 지금 현장 가상 시각으로 놓는다(놓인 시각을 모르는 채로 두면 셀 관측이 «읽은 순간» 이 되어 근거 창을 늘 지난다). 그 자리에 아직
     * 처음 용기가 있을 때만 다시 찍는다 — 런처가 가상 시계를 실제 시각까지 민 뒤 한 번 더 부른다([Site.start]).
     */
    fun stampInitial() {
        initial.forEach { (location, containerId) ->
            if (world.containerAt(location) == containerId) world.place(location, containerId, now())
        }
    }

    /** 바깥(HTTP) 핸들. */
    fun external(handle: TransportHandle): String = "$boot:${handle.id}"

    /** 바깥 핸들을 이 기동의 미믹 핸들로. 접두가 다르면(다른 기동의 핸들) `null` 이다. */
    fun internal(handle: String): TransportHandle? =
        handle.takeIf { it.startsWith("$boot:") }?.removePrefix("$boot:")?.takeIf { it.isNotEmpty() }?.let(::TransportHandle)

    private val json = ObjectMapper()

    /** 맡긴다. 출발지·목적지가 인계 자리가 아니면 맡지 않는다(`null`). */
    fun dispatch(order: TransportOrder): TransportHandle? =
        if (order.source !in places || order.destination !in places) null else world.dispatch(order)

    /** 세계를 인계 자리 순서의 셀 자리로 읽는다. 용기가 있으면 점유, 자재 = 용기 신원, 관측 시각 = 놓인 시각. */
    fun cellPlaces(): List<CellPlace> {
        val seen = world.snapshot()
        return places.map { id -> seen[id]?.let { CellPlace(id, true, it.containerId, it.placedAt) } ?: CellPlace(id, false, null, null) }
    }

    /** 현장 운영의 세계 채우기 하나. [kind] 는 `place`·`clear`·`block`·`unblock` 이다. */
    fun write(kind: String, location: String, containerId: String?): FleetWorldWrite {
        if (location !in places) return FleetWorldWrite.Refused(404, UNKNOWN_PLACE, "플릿 세계에 없는 인계 자리다: $location (자리: ${places.joinToString(", ")})")
        when (kind) {
            // 놓인 시각은 지금 현장 가상 시각이다 — 시각 없이 두면 셀 관측이 «읽은 순간» 이라 근거 창을 늘 지난다.
            PLACE -> world.place(location, containerId ?: return FleetWorldWrite.Refused(400, BAD_REQUEST, "place 는 containerId 가 필요하다"), now())
            CLEAR -> world.clear(location)
            BLOCK -> world.block(location)
            UNBLOCK -> world.unblock(location)
            else -> return FleetWorldWrite.Refused(404, UNKNOWN_OPERATION, "없는 세계 조작이다: $kind")
        }
        return FleetWorldWrite.Written
    }

    /** `/fleet` 아래 요청 하나. 셀 대역의 서버가 넘긴다. 세계를 바꿀 수 있는 요청이면 [changed] 를 부른다(셀 투영 갱신). */
    fun handle(exchange: HttpExchange, changed: () -> Unit) {
        try {
            val (status, body) = route(exchange)
            if (exchange.requestMethod != "GET") changed()
            if (body.isNotEmpty()) exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.write(body)
        } finally {
            exchange.close()
        }
    }

    private fun route(exchange: HttpExchange): Pair<Int, ByteArray> {
        val path = exchange.requestURI.rawPath
        val method = exchange.requestMethod
        return when {
            path == TRANSPORTS -> when (method) {
                "POST" -> posted(exchange) { node -> dispatched(node) }
                "GET" -> found(exchange.requestURI.rawQuery)
                else -> notAllowed(exchange, "GET, POST")
            }
            path.startsWith("$TRANSPORTS/") && path.endsWith(CANCEL_SUFFIX) -> {
                val handle = decode(path.removePrefix("$TRANSPORTS/").removeSuffix(CANCEL_SUFFIX))
                if (handle.isEmpty() || '/' in handle) return 404 to ByteArray(0)
                if (method != "POST") return notAllowed(exchange, "POST")
                posted(exchange) { _ -> 200 to write(linkedMapOf("accepted" to (internal(handle)?.let(world::cancel) ?: false))) }
            }
            path.startsWith("$TRANSPORTS/") -> {
                val handle = decode(path.removePrefix("$TRANSPORTS/"))
                if (handle.isEmpty() || '/' in handle) return 404 to ByteArray(0)
                if (method != "GET") return notAllowed(exchange, "GET")
                200 to write(wireStatus(internal(handle)?.let(world::status) ?: TransportStatus(TransportState.FAILED, null, false, "모르는 운반: $handle")))
            }
            path == WORLD -> if (method == "GET") 200 to write(wireWorld()) else notAllowed(exchange, "GET")
            path.startsWith("$WORLD/") -> {
                val kind = path.removePrefix("$WORLD/")
                if (kind !in WORLD_OPERATIONS) return 404 to ByteArray(0)
                if (method != "POST") return notAllowed(exchange, "POST")
                posted(exchange) { node ->
                    val location = node.text("location")
                        ?: return@posted 400 to rejection(BAD_REQUEST, "본문이 {\"location\": \"<자리>\"} 모양이 아니다")
                    when (val written = write(kind, location, node.text("containerId"))) {
                        FleetWorldWrite.Written -> 200 to write(wireWorld())
                        is FleetWorldWrite.Refused -> written.status to rejection(written.error, written.detail)
                    }
                }
            }
            else -> 404 to ByteArray(0)
        }
    }

    private fun dispatched(node: JsonNode): Pair<Int, ByteArray> {
        val reference = node.text("reference")
        val containerId = node.text("containerId")
        val source = node.text("source")
        val destination = node.text("destination")
        if (reference == null || containerId == null || source == null || destination == null) {
            return 400 to rejection(BAD_REQUEST, "본문이 {reference, containerId, source, destination} 문자열 넷이 아니다")
        }
        return 200 to write(linkedMapOf("handle" to dispatch(TransportOrder(reference, containerId, source, destination))?.let(::external)))
    }

    private fun found(query: String?): Pair<Int, ByteArray> {
        val pairs = query.orEmpty().split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') to decode(it.substringAfter('=', "")) }
        val reference = pairs.singleOrNull()?.takeIf { it.first == "reference" && it.second.isNotBlank() }?.second
            ?: return 400 to rejection(BAD_REQUEST, "찾기는 reference 하나만 받는다")
        return 200 to write(linkedMapOf("handle" to world.find(reference)?.let(::external)))
    }

    /** `application/json` 본문을 객체로 읽어 [action] 에 넘긴다. 아니면 415·400 이다. */
    private fun posted(exchange: HttpExchange, action: (JsonNode) -> Pair<Int, ByteArray>): Pair<Int, ByteArray> {
        val contentType = exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()
        if (!contentType.equals("application/json", ignoreCase = true)) {
            return 415 to rejection(UNSUPPORTED_MEDIA_TYPE, "Content-Type 이 application/json 이 아니다: ${contentType ?: "없음"}")
        }
        val node = runCatching { json.readTree(exchange.requestBody.readAllBytes()) }.getOrNull()?.takeIf { it.isObject }
            ?: return 400 to rejection(BAD_REQUEST, "본문이 JSON 객체가 아니다")
        return action(node)
    }

    private fun notAllowed(exchange: HttpExchange, allow: String): Pair<Int, ByteArray> {
        exchange.responseHeaders.add("Allow", allow)
        return 405 to ByteArray(0)
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

    private fun decode(raw: String): String = URLDecoder.decode(raw, StandardCharsets.UTF_8)

    private fun write(body: Any): ByteArray = json.writeValueAsBytes(body)

    private fun rejection(error: String, detail: String): ByteArray = write(linkedMapOf("error" to error, "detail" to detail))

    /** 시각은 ISO-8601 문자열로 여기서 적는다(셀 대역 본문과 같은 까닭). */
    private fun wireWorld(): Map<String, Any?> {
        val seen = world.snapshot()
        return linkedMapOf(
            "places" to places.map { id ->
                linkedMapOf("location" to id, "containerId" to seen[id]?.containerId, "placedAt" to seen[id]?.placedAt?.let(Instant::toString))
            },
            "blocked" to world.blocked.filter { it in places }.sorted(),
            "dispatches" to world.dispatches,
        )
    }

    private fun wireStatus(status: TransportStatus): Map<String, Any?> = linkedMapOf(
        "state" to status.state.name,
        "observedContainer" to status.observedContainer,
        "holding" to status.holding,
        "detail" to status.detail,
    )

    companion object {
        const val PATH = "/fleet"
        const val TRANSPORTS = "/fleet/transports"
        const val WORLD = "/fleet/world"
        private const val CANCEL_SUFFIX = "/cancel"

        const val PLACE = "place"
        const val CLEAR = "clear"
        const val BLOCK = "block"
        const val UNBLOCK = "unblock"
        val WORLD_OPERATIONS: Set<String> = setOf(PLACE, CLEAR, BLOCK, UNBLOCK)

        /** 플릿 걸음 간격(현장 가상 시간). 접수 → 인수 → 이동 → 인계가 세 걸음이다. 런처는 1초마다 시계를 민다. */
        val STEP: Duration = Duration.ofSeconds(5)

        const val BAD_REQUEST = SiteCell.BAD_REQUEST
        const val UNSUPPORTED_MEDIA_TYPE = SiteCell.UNSUPPORTED_MEDIA_TYPE
        const val UNKNOWN_PLACE = "UNKNOWN_PLACE"
        const val UNKNOWN_OPERATION = "UNKNOWN_OPERATION"
    }
}
