package dev.picasso.ops.host.fleet

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.AmrFleetPort
import dev.picasso.middleware.CommandNotSent
import dev.picasso.middleware.ExecutionLookup
import dev.picasso.middleware.TransportHandle
import dev.picasso.middleware.TransportOrder
import dev.picasso.middleware.TransportState
import dev.picasso.middleware.TransportStatus
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** 플릿 면이 답하지 않았거나 모양이 틀린 답을 줬다. 미들웨어는 읽기가 던진 것으로 다루고 다음 라운드에 다시 본다. */
class FleetUnreachable(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * 현장 플릿 세계 면(`/fleet`, mission-ops 안의 약속이며 picasso 계약이 아님)을 루프백 HTTP 로 부르는 [AmrFleetPort]. 실행 호스트는 이것을
 * [HostFleetPort] 로 감싸 미들웨어에 넘긴다(`host.fleet.url`).
 *
 * **실패를 가른다.** 맡기기는 [IssuingFleetPort][dev.picasso.middleware.IssuingFleetPort] 가 [CommandNotSent] 를 «보내지 않았다» 로, 그 밖의
 * 예외를 «맡겨졌는지 모름»(미확정)으로 읽는다. 그래서 연결이 서지 않았으면(연결 거부·연결 시간 초과) [CommandNotSent], 현장이 4xx 로 요청을
 * 받지 않았으면 [CommandNotSent], 요청을 보낸 뒤 답을 못 받았거나(요청 시간 초과·끊김) 5xx 면 [FleetUnreachable](미확정)다. 읽기(찾기·상태)와
 * 취소는 실패를 가르지 않고 [FleetUnreachable] 다.
 *
 * **모르는 핸들은 현장이 말한다.** 현장 미믹은 모르는 핸들의 상태를 `FAILED`·«모르는 운반» 으로 준다 — 이 포트는 그 답을 그대로 옮기고 지어내지
 * 않는다(현장 장부가 비었는지 원래 없던 핸들인지는 가르지 못한다, `docs/guarantees.md` «보장 아님»).
 *
 * @param baseUrl 현장 셀 대역 서버 주소. `/fleet/...` 를 붙여 부른다.
 * @param timeout 연결 제한과 요청 제한. 미리 읽기의 기한(`PrefetchingFleetPort.maxAge`, 기본 2초)보다 짧게 둔다 — 답 없는 읽기는 기한 전에
 *   실패로 끝나 다음 라운드에 다시 건다(기체 포트·셀 대역 읽기와 같은 1초).
 */
class HttpFleetPort(baseUrl: String, private val timeout: Duration = Duration.ofSeconds(1)) : AmrFleetPort {

    private val base = baseUrl.trim().trimEnd('/')

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) { "플릿 주소(host.fleet.url)가 http(s) URL 이 아니다: $baseUrl" }
        // 서버 주소만 받는다. `/fleet` 를 붙이면 모든 요청이 404 가 되고, 4xx 맡기기는 «보내지 않음» 이라 운반이 영영 대기에 선다.
        val path = URI.create(base).path.orEmpty()
        require(path.isEmpty()) { "플릿 주소(host.fleet.url)는 서버 주소만 준다(`/fleet` 같은 경로를 붙이지 않음): $baseUrl" }
    }

    /**
     * 기동 탐침 — `GET /fleet/world` 의 상태 코드. 안 닿으면 `null` 이다(현장이 아직 안 떴을 수 있어 기동을 막지 않는다). 닿았는데 200 이 아니면 그
     * 주소가 플릿 세계 면이 아니다([dev.picasso.ops.host.MissionHostApplication.fleetPortOf] 가 기동을 멈춘다).
     */
    fun probe(): Int? = try {
        send(get("/fleet/world")).statusCode()
    } catch (e: IOException) {
        null
    } catch (e: FleetUnreachable) {
        null
    }

    /** 표시용 주소(끝 `/` 없음). */
    val url: String get() = base

    private val http = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val json = ObjectMapper()

    override val executionLookup: ExecutionLookup get() = ExecutionLookup.CLIENT_REFERENCE

    override fun dispatch(order: TransportOrder): TransportHandle? {
        val body = json.writeValueAsBytes(
            linkedMapOf("reference" to order.reference, "containerId" to order.containerId, "source" to order.source, "destination" to order.destination),
        )
        val response = try {
            send(post("/fleet/transports", body))
        } catch (e: HttpConnectTimeoutException) {
            throw CommandNotSent("플릿 면에 연결하지 못했다(${order.reference}): ${e.message}")
        } catch (e: ConnectException) {
            throw CommandNotSent("플릿 면에 연결하지 못했다(${order.reference}): ${e.message}")
        } catch (e: IOException) {
            throw FleetUnreachable("맡기기(${order.reference})를 보냈으나 답을 못 받았다: ${e.message}", e)
        }
        if (response.statusCode() in 400..499) throw CommandNotSent("플릿 면이 맡기기(${order.reference})를 받지 않았다: HTTP ${response.statusCode()} ${response.body().decode()}")
        return handleOf(answered(response, "맡기기 ${order.reference}"))
    }

    override fun find(reference: String): TransportHandle? =
        handleOf(read(get("/fleet/transports?reference=${URLEncoder.encode(reference, StandardCharsets.UTF_8)}"), "찾기 $reference"))

    override fun status(handle: TransportHandle): TransportStatus {
        val node = read(get("/fleet/transports/${segment(handle.id)}"), "상태 ${handle.id}")
        val state = node.get("state")?.takeIf { it.isTextual }?.asText()
            ?.let { name -> TransportState.entries.firstOrNull { it.name == name } }
            ?: throw FleetUnreachable("플릿 면의 상태 답에 알 수 있는 state 가 없다: $node")
        val holding = node.get("holding")?.takeIf { it.isBoolean }?.asBoolean() ?: throw FleetUnreachable("플릿 면의 상태 답에 holding 이 없다: $node")
        return TransportStatus(state, node.text("observedContainer"), holding, node.text("detail"))
    }

    override fun cancel(handle: TransportHandle): Boolean {
        val node = read(post("/fleet/transports/${segment(handle.id)}/cancel", "{}".toByteArray()), "취소 ${handle.id}")
        return node.get("accepted")?.takeIf { it.isBoolean }?.asBoolean() ?: throw FleetUnreachable("플릿 면의 취소 답에 accepted 가 없다: $node")
    }

    private fun get(path: String): HttpRequest = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).GET().build()

    private fun post(path: String, body: ByteArray): HttpRequest = HttpRequest.newBuilder(URI.create(base + path))
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build()

    private fun send(request: HttpRequest): HttpResponse<ByteArray> = try {
        http.send(request, HttpResponse.BodyHandlers.ofByteArray())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw FleetUnreachable("플릿 면 호출이 끊겼다: ${request.uri()}", e)
    }

    /** 읽기·취소 한 번. 실패를 가르지 않는다. */
    private fun read(request: HttpRequest, what: String): JsonNode {
        val response = try {
            send(request)
        } catch (e: IOException) {
            throw FleetUnreachable("플릿 면이 답하지 않았다($what): ${e.message}", e)
        }
        return answered(response, what)
    }

    private fun answered(response: HttpResponse<ByteArray>, what: String): JsonNode {
        if (response.statusCode() != 200) throw FleetUnreachable("플릿 면이 $what 에 HTTP ${response.statusCode()} 로 답했다: ${response.body().decode()}")
        return runCatching { json.readTree(response.body()) }.getOrNull()?.takeIf { it.isObject }
            ?: throw FleetUnreachable("플릿 면의 $what 답이 JSON 객체가 아니다")
    }

    private fun handleOf(node: JsonNode): TransportHandle? {
        val handle = node.get("handle") ?: throw FleetUnreachable("플릿 면의 답에 handle 칸이 없다: $node")
        if (handle.isNull) return null
        return handle.takeIf { it.isTextual && it.asText().isNotBlank() }?.let { TransportHandle(it.asText()) }
            ?: throw FleetUnreachable("플릿 면의 handle 이 문자열이 아니다: $node")
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()

    private fun segment(id: String): String = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20")

    private fun ByteArray.decode(): String = toString(Charsets.UTF_8).take(500)
}
