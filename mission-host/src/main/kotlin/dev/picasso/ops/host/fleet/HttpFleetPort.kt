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
import dev.picasso.ops.host.lease.ControllerConfirmation
import dev.picasso.ops.host.lease.HttpEpoch
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
 * 플릿 면이 읽기(찾기·상태)를 제어자 세대로 거부했다(409 `STALE_CONTROLLER`). 현장은 읽기를 대조하지 않으므로 오면 안 되는 답이다 — 값이나 «없음» 으로 접지
 * 않고 이 예외로 낸다(미들웨어는 읽기가 던진 것으로 다루고 다음 라운드에 다시 본다). 밀려남 판정은 바닥값 헤더로 이미 했다.
 */
class FleetEpochRefused(message: String) : IllegalStateException(message)

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
 * **제어자 세대를 싣는다**([epoch], HA 3단계 PR B1). 모든 요청에 리스 세대 헤더를 싣고, 모든 응답에서 본문을 읽기 전에 바닥값 헤더를 본다 — 실은 세대보다
 * 높으면 밀려남이라 리더를 잃음으로 넘긴다(200 이어도, 이미 받은 답은 그대로 돌려준다). 현장의 세대 거부(409 `STALE_CONTROLLER`)는 맡기기·취소면
 * [CommandNotSent](보내지 않음 — 현장이 받지 않았다), 읽기면 [FleetEpochRefused] 다. 409 상태만으로는 잃음이 아니다.
 *
 * @param baseUrl 현장 셀 대역 서버 주소. `/fleet/...` 를 붙여 부른다.
 * @param timeout 연결 제한과 요청 제한. 미리 읽기의 기한(`PrefetchingFleetPort.maxAge`, 기본 2초)보다 짧게 둔다 — 답 없는 읽기는 기한 전에
 *   실패로 끝나 다음 라운드에 다시 건다(기체 포트·셀 대역 읽기와 같은 1초).
 * @param epoch 실을 제어자 세대와 밀려남 판정. 기본은 세대 0(싣지 않음)이고 밀려남을 보지 않는다(시험).
 */
class HttpFleetPort(
    baseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(1),
    private val epoch: HttpEpoch = HttpEpoch("플릿"),
) : AmrFleetPort {

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
        val mine = epoch.forRequest(false, "탐침")
        send(get("/fleet/world", mine), mine).statusCode()
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
        val mine = epoch.forRequest(true, "맡기기 ${order.reference}")
        val response = try {
            send(post("/fleet/transports", body, mine), mine)
        } catch (e: HttpConnectTimeoutException) {
            throw CommandNotSent("플릿 면에 연결하지 못했다(${order.reference}): ${e.message}")
        } catch (e: ConnectException) {
            throw CommandNotSent("플릿 면에 연결하지 못했다(${order.reference}): ${e.message}")
        } catch (e: IOException) {
            throw FleetUnreachable("맡기기(${order.reference})를 보냈으나 답을 못 받았다: ${e.message}", e)
        }
        // 바닥값은 머리말을 받은 순간 이미 봤다(send) — 밀려났으면 잃음으로 넘겼고, 받은 답(성공 포함)은 그대로 다룬다.
        // 바닥값 저장 실패(503)는 현장이 «변경 없음» 을 보장하므로 보내지 않음이다.
        if (storeFailed(response)) throw CommandNotSent("플릿 면이 맡기기(${order.reference})를 바닥값 저장 실패로 받지 않았다: ${response.body().decode()}")
        if (response.statusCode() in 400..499) throw CommandNotSent("플릿 면이 맡기기(${order.reference})를 받지 않았다: HTTP ${response.statusCode()} ${response.body().decode()}")
        return handleOf(answered(response, "맡기기 ${order.reference}"))
    }

    override fun find(reference: String): TransportHandle? =
        handleOf(read("/fleet/transports?reference=${URLEncoder.encode(reference, StandardCharsets.UTF_8)}", "찾기 $reference"))

    override fun status(handle: TransportHandle): TransportStatus {
        val node = read("/fleet/transports/${segment(handle.id)}", "상태 ${handle.id}")
        val state = node.get("state")?.takeIf { it.isTextual }?.asText()
            ?.let { name -> TransportState.entries.firstOrNull { it.name == name } }
            ?: throw FleetUnreachable("플릿 면의 상태 답에 알 수 있는 state 가 없다: $node")
        val holding = node.get("holding")?.takeIf { it.isBoolean }?.asBoolean() ?: throw FleetUnreachable("플릿 면의 상태 답에 holding 이 없다: $node")
        return TransportStatus(state, node.text("observedContainer"), holding, node.text("detail"))
    }

    /**
     * 취소. 현장의 세대 거부(409 `STALE_CONTROLLER`)와 바닥값 저장 실패(503 `FENCE_STORE_FAILED`)는 [CommandNotSent] 다 — 현장이 받지 않았다(미들웨어는
     * `CANCEL_UNSENT` 로 다시 보낸다).
     */
    override fun cancel(handle: TransportHandle): Boolean {
        val what = "취소 ${handle.id}"
        val mine = epoch.forRequest(true, "운반 $what")
        val response = try {
            send(post("/fleet/transports/${segment(handle.id)}/cancel", "{}".toByteArray(), mine), mine)
        } catch (e: IOException) {
            throw FleetUnreachable("플릿 면이 답하지 않았다($what): ${e.message}", e)
        }
        if (stale(response)) throw CommandNotSent("플릿 면이 $what 을 제어자 세대로 거부했다(보내지 않음과 같음): ${response.body().decode()}")
        if (storeFailed(response)) throw CommandNotSent("플릿 면이 $what 을 현장 바닥값 저장 실패로 받지 않았다(보내지 않음과 같음): ${response.body().decode()}")
        val node = answered(response, what)
        return node.get("accepted")?.takeIf { it.isBoolean }?.asBoolean() ?: throw FleetUnreachable("플릿 면의 취소 답에 accepted 가 없다: $node")
    }

    /**
     * 플릿 면 인수 확인 `POST /fleet/controller/confirm`(본문 `{}`, 세대는 헤더). 리스를 쥔 동안 호스트 잠금 밖의 전용 스레드
     * ([dev.picasso.ops.host.cell.SiteFenceConfirmer])가 불러 현장 플릿 바닥값을 이 세대로 올린다 — 플릿 준비(관측 중 자동 재개)에는 쓰지 않는다.
     * 현장이 안 닿으면 `null` 이다. 붙잡은 세대가 바뀌었으면(잃음) 보내지 않고 [CommandNotSent] 를 던진다.
     */
    fun confirmController(): ControllerConfirmation? {
        val mine = epoch.forRequest(true, "플릿 인수 확인")
        val response = try {
            send(post(CONFIRM_PATH, "{}".toByteArray(), mine, HttpEpoch.CONFIRM_TIMEOUT), mine)
        } catch (e: IOException) {
            return null
        } catch (e: FleetUnreachable) {
            return null
        }
        return ControllerConfirmation.read(response.statusCode(), response.body(), json)
    }

    private fun get(path: String, mine: Long): HttpRequest =
        HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).header(HttpEpoch.EPOCH_HEADER, mine.toString()).GET().build()

    private fun post(path: String, body: ByteArray, mine: Long, limit: Duration = timeout): HttpRequest = HttpRequest.newBuilder(URI.create(base + path))
        .timeout(limit)
        .header("Content-Type", "application/json")
        .header(HttpEpoch.EPOCH_HEADER, mine.toString())
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build()

    /** 보낸다. 바닥값은 머리말을 받은 순간(본문을 받기 전) 본다([HttpEpoch.observing]) — 본문 수신이 끊겨도 밀려남은 놓치지 않는다. */
    private fun send(request: HttpRequest, mine: Long): HttpResponse<ByteArray> = try {
        http.send(request, epoch.observing(mine))
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw FleetUnreachable("플릿 면 호출이 끊겼다: ${request.uri()}", e)
    }

    /** 읽기 한 번. 실패를 가르지 않는다. 세대 거부는 [FleetEpochRefused] 다. */
    private fun read(path: String, what: String): JsonNode {
        val mine = epoch.forRequest(false, what)
        val response = try {
            send(get(path, mine), mine)
        } catch (e: IOException) {
            throw FleetUnreachable("플릿 면이 답하지 않았다($what): ${e.message}", e)
        }
        if (stale(response)) throw FleetEpochRefused("플릿 면이 $what 을 제어자 세대로 거부했다: ${response.body().decode()}")
        return answered(response, what)
    }

    /** 현장의 세대 거부(409, 본문 `error` = `STALE_CONTROLLER`)인가. 같은 409 라도 다른 거부는 아니다. */
    private fun stale(response: HttpResponse<ByteArray>): Boolean = response.statusCode() == 409 && errorOf(response) == HttpEpoch.STALE_CONTROLLER

    /** 현장이 바닥값을 저장하지 못해 변경 없이 받지 않았는가(503, 본문 `error` = `FENCE_STORE_FAILED`). */
    private fun storeFailed(response: HttpResponse<ByteArray>): Boolean = response.statusCode() == 503 && errorOf(response) == HttpEpoch.FENCE_STORE_FAILED

    private fun errorOf(response: HttpResponse<ByteArray>): String? =
        runCatching { json.readTree(response.body()) }.getOrNull()?.get("error")?.takeIf { it.isTextual }?.asText()

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

    companion object {
        /** 플릿 면 인수 확인 경로. */
        const val CONFIRM_PATH = "/fleet/controller/confirm"
    }
}
