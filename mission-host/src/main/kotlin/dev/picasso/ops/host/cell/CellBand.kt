package dev.picasso.ops.host.cell

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.CellSignals
import dev.picasso.middleware.NamedSignal
import dev.picasso.middleware.SlotSignal
import dev.picasso.middleware.mission.SignalKind
import dev.picasso.middleware.mission.SignalSpec
import dev.picasso.ops.host.lease.ControllerConfirmation
import dev.picasso.ops.host.lease.HttpEpoch
import dev.picasso.ops.host.lease.SendAuthority
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * 셀 대역의 자리 하나(현장 `GET /cell` 의 원소). [observedAt] 이 `null` 이면 시각을 주지 않는 신호이고 읽은 순간이 그
 * 시각이다(제시 자리). 슬롯은 채운 가상 시각을 든다.
 */
data class CellPlace(val id: String, val occupied: Boolean, val material: String?, val observedAt: Instant?)

/**
 * 이름 있는 신호 하나(현장 `GET /cell` 의 `signals` 원소, S3b 스펙 §5.2). 사양 칸 넷과 지금 값, 관측 시각이다. 값은 종류와
 * 상관없이 문자열이다. [observedAt] 이 `null` 이면 현장이 시각을 주지 않은 처음 값이고 읽은 순간이 그 시각이다.
 */
data class CellSignal(
    val name: String,
    val location: String?,
    val kind: SignalKind,
    val safety: Boolean,
    val value: String,
    val observedAt: Instant?,
) {
    /** 신호 사양의 한 줄. 검증기가 이것을 받는다(T7). 본문 칸이 아니므로 속성이 아니라 함수다. */
    fun spec(): SignalSpec = SignalSpec(name, location, kind, safety)
}

/**
 * 셀 대역의 한 순간(현장 `GET /cell` 의 본문). 제시 자리와 슬롯을 가른다.
 *
 * [signals] 가 `null` 이면 현장 본문에 `signals` 칸이 없었다(신호를 선언하지 않는 셀 대역). 빈 목록과 다르다. 빈 목록은
 * «신호가 하나도 없다» 는 사양이고 `null` 은 «신호 사양을 모른다» 다. 검증은 `null` 을 «못 읽음» 으로 다룬다(T7).
 *
 * [handovers] 는 현장 플릿 세계를 셀 신호로 올린 인계 자리다(점유, 자재 = 용기 신원, 관측 시각 = 놓인 시각). 플릿 단위의 목적지·출발지는 이
 * 자리로 관측한다(같은 세계). 칸이 없는 현장 본문은 빈 목록이다 — 그 자리는 «말이 없다» 로 읽힌다.
 */
data class CellSnapshot(
    val presentations: List<CellPlace>,
    val slots: List<CellPlace>,
    val signals: List<CellSignal>? = null,
    val handovers: List<CellPlace> = emptyList(),
) {
    /** 신호 사양. 모르면 `null` 이다. 본문 칸이 아니므로 속성이 아니라 함수다(`GET /host/cell` 이 이 모양을 그대로 낸다). */
    fun signalSpecs(): List<SignalSpec>? = signals?.map { it.spec() }
}

/** 신호 조작이나 장애 주입을 현장에 넘긴 결과. 현장이 답하면 그 상태 코드와 본문 그대로다. */
data class SignalRelay(val status: Int, val contentType: String?, val body: ByteArray)

/**
 * 현장의 셀 대역을 루프백 HTTP 로 읽는다(S3a 스펙 §7.3). 실패하면 `null`(스냅숏 없음)이다. 못 읽은 것을 빈 셀로 접으면
 * 미들웨어가 «말이 없다» 를 «비었다» 로 읽는다.
 *
 * 신호 조작([writeSignal])도 이것으로 현장에 넘긴다(S3b 스펙 §6.6). 판정은 현장이 하고 호스트는 응답을 그대로 돌려준다.
 *
 * @param baseUrl 현장 셀 대역의 주소. 경로 `/cell` 을 붙여 부른다.
 * @param timeout 연결 제한과 셀 대역 읽기의 요청 제한
 * @param writeTimeout 신호 조작의 요청 제한. [SIGNAL_WRITE_TIMEOUT] 의 까닭을 본다
 * @param authority 송신 직전 로컬 권위 확인(HA 설계 기록 §4.4 의 3). 신호 쓰기와 결함 주입 중계는 보내기 직전에 이것을 확인하고, 발행하는 리더가
 *   아니면 보내지 않고 `CommandNotSent` 를 던진다(REST 는 503). 읽기([fetch])는 막지 않는다.
 * @param epoch 실을 제어자 세대와 밀려남 판정(HA 3단계 PR B1). 모든 요청에 세대 헤더를 싣고, 모든 응답에서 본문을 넘기기 전에 바닥값 헤더를 본다 —
 *   실은 세대보다 높으면 밀려남이라 리더를 잃음으로 넘긴다. 신호 쓰기·결함 주입의 현장 응답(409 `STALE_CONTROLLER` 포함)은 그 판단 뒤 상태 코드와
 *   본문 그대로 돌려준다 — «현장이 안 닿음» 으로 접지 않는다. 기본은 세대 0 이고 밀려남을 보지 않는다(시험).
 */
class CellBandClient(
    baseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(1),
    private val writeTimeout: Duration = SIGNAL_WRITE_TIMEOUT,
    private val authority: SendAuthority = SendAuthority.ALWAYS,
    private val epoch: HttpEpoch = HttpEpoch("셀"),
) {

    private val base = baseUrl.trimEnd('/')
    private val uri = URI.create("$base/cell")
    private val http = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val json = ObjectMapper()

    fun fetch(): CellSnapshot? {
        val mine = epoch.forRequest(false, "셀 대역 읽기")
        val response = try {
            http.send(
                HttpRequest.newBuilder(uri).timeout(timeout).header(HttpEpoch.EPOCH_HEADER, mine.toString()).GET().build(),
                // 머리말을 받은 순간 바닥값을 본다(읽기 응답만으로도, 본문을 받다 끊겨도 밀려남이다).
                epoch.observing(mine),
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: Exception) {
            return null
        }
        return try {
            if (response.statusCode() == 200) parse(json.readTree(response.body())) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `POST /cell/signals/{name}` 을 현장에 그대로 넘긴다. 현장이 안 닿으면(연결 실패, 시간 초과) `null` 이다. 이름은 경로
     * 조각으로 인코딩한다. 판정(404·400·403)은 현장의 몫이다.
     */
    fun writeSignal(name: String, body: ByteArray): SignalRelay? = authority.sending("셀 신호 쓰기 $name") {
        relay("$base/cell/signals/${URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")}", body, "셀 신호 쓰기 $name")
    }

    /**
     * 기체 장애 주입 `POST /faults` 를 현장에 그대로 넘긴다(S4a 스펙 §6). 현장이 안 닿으면 `null` 이다. 종류의 해석과 판정은
     * 현장의 몫이다. 현장의 주입도 mimic 엔진 잠금을 기다리므로 신호 조작과 같은 요청 제한을 쓴다.
     */
    fun injectFault(body: ByteArray): SignalRelay? = authority.sending("결함 주입 중계") { relay("$base/faults", body, "결함 주입 중계") }

    /**
     * 셀 면 인수 확인 `POST /cell/controller/confirm`(본문 `{}`, 세대는 헤더). 리스를 쥔 동안 호스트 잠금 밖의 전용 스레드([SiteFenceConfirmer])가 부른다 —
     * 실행 준비의 조건이 아니다. 현장이 안 닿으면 `null` 이다. 붙잡은 세대가 바뀌었으면(잃음) 보내지 않고 `CommandNotSent` 를 던진다.
     */
    fun confirmController(): ControllerConfirmation? {
        val mine = epoch.forRequest(true, "셀 인수 확인")
        val response = try {
            http.send(
                HttpRequest.newBuilder(URI.create("$base$CONFIRM_PATH"))
                    .timeout(HttpEpoch.CONFIRM_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header(HttpEpoch.EPOCH_HEADER, mine.toString())
                    .POST(HttpRequest.BodyPublishers.ofByteArray("{}".toByteArray()))
                    .build(),
                epoch.observing(mine),
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: Exception) {
            return null
        }
        return ControllerConfirmation.read(response.statusCode(), response.body(), json)
    }

    /**
     * 변경 하나를 현장에 넘긴다. 연결 실패·시간 초과·본문 수신 실패만 `null`(현장이 안 닿음)이다. 바닥값은 머리말을 받은 순간에 본다(밀려남이면 잃음 —
     * 그 뒤 본문 수신이 실패해 `null` 이어도 잃음은 이미 판단됐다). 응답을 받으면 상태 코드와 본문 그대로 돌려준다(세대 거부 409 가 «안 닿음» 으로 접히지 않게).
     */
    private fun relay(url: String, body: ByteArray, what: String): SignalRelay? {
        val mine = epoch.forRequest(true, what)
        val response = try {
            http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(writeTimeout)
                    .header("Content-Type", "application/json")
                    .header(HttpEpoch.EPOCH_HEADER, mine.toString())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build(),
                epoch.observing(mine),
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: Exception) {
            return null
        }
        return SignalRelay(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null), response.body())
    }

    companion object {
        /**
         * 신호 조작의 요청 제한. 현장의 신호 쓰기는 mimic 엔진 잠금을 기다리고, 엔진은 그 잠금 아래에서 registry 로 태스크
         * 관측을 적재할 수 있다(요청 제한 3초). 1초에서 끊으면 현장이 곧 반영할 쓰기를 «현장이 안 닿음»(503)으로 돌려주게
         * 된다. 운영 서비스의 호스트 요청 제한(5초)보다는 짧아야 호스트의 503 이 운영 서비스에 닿는다.
         */
        val SIGNAL_WRITE_TIMEOUT: Duration = Duration.ofSeconds(4)

        /** 셀 면 인수 확인 경로. */
        const val CONFIRM_PATH = "/cell/controller/confirm"

        /** 본문 모양이 어긋나면 예외다. [fetch] 가 그것을 «스냅숏 없음» 으로 접는다. `signals` 칸이 없으면 신호 사양을 모른다. */
        fun parse(node: JsonNode): CellSnapshot =
            CellSnapshot(
                places(node, "presentations"), places(node, "slots"), node.get("signals")?.let(::signals),
                node.get("handovers")?.let { places(node, "handovers") }.orEmpty(),
            )

        private fun signals(node: JsonNode): List<CellSignal> {
            require(node.isArray) { "signals 가 배열이 아니다" }
            return node.map { signal ->
                CellSignal(
                    name = requireNotNull(signal.get("name")?.takeIf { it.isTextual && it.asText().isNotEmpty() }) { "신호 이름이 없다" }.asText(),
                    location = signal.get("location")?.takeIf { it.isTextual }?.asText(),
                    kind = requireNotNull(signal.get("kind")?.takeIf { it.isTextual }) { "신호 종류가 없다" }.asText()
                        .let { kind -> SignalKind.entries.firstOrNull { it.name == kind } ?: throw IllegalArgumentException("모르는 신호 종류다: $kind") },
                    safety = requireNotNull(signal.get("safety")?.takeIf { it.isBoolean }) { "safety 가 없다" }.asBoolean(),
                    value = requireNotNull(signal.get("value")?.takeIf { it.isTextual }) { "신호 값이 문자열이 아니다" }.asText(),
                    observedAt = signal.get("observedAt")?.takeIf { it.isTextual }?.asText()?.let(Instant::parse),
                )
            }
        }

        private fun places(node: JsonNode, field: String): List<CellPlace> {
            val array = requireNotNull(node.get(field)?.takeIf { it.isArray }) { "$field 가 배열이 아니다" }
            return array.map { place ->
                CellPlace(
                    id = requireNotNull(place.get("id")?.takeIf { it.isTextual }) { "자리 id 가 없다" }.asText(),
                    occupied = requireNotNull(place.get("occupied")?.takeIf { it.isBoolean }) { "occupied 가 없다" }.asBoolean(),
                    material = place.get("material")?.takeIf { it.isTextual }?.asText(),
                    observedAt = place.get("observedAt")?.takeIf { it.isTextual }?.asText()?.let(Instant::parse),
                )
            }
        }
    }
}

/**
 * 사람이 기록한 출발 자리 확인 한 건. 운영 서비스가 작업 요청마다 덧붙이기로 적고, 호스트가 출발 미관측으로 답하는 연쇄 안에서 그 작업 요청을 다시 낼 때 제출 본문의
 * `sourceConfirmations` 로 싣는다. 호스트는 저장하지 않는다 — 그 제출 한 번 동안만 셀 포트의 관측이 된다([CellBandSignals.confirmations]).
 *
 * picasso `SourceUnobserved` 가 말하는 «기록된 수동 확인» 이다. 미들웨어의 덮어쓰기 칸이 아니라 셀 포트의 답으로 들어간다.
 *
 * @param identity 사람이 그 자리에서 확인한 신원. 요구한 자재와 다르면 게이트가 출발 결품으로 판정한다
 * @param confirmedBy 확인한 사람(운영 서비스 행위자 헤더)
 * @param confirmedAt 확인을 기록한 시각. 셀 관측의 시각이 된다
 */
data class SourceConfirmation(val source: String, val identity: String, val confirmedBy: String, val confirmedAt: Instant)

/**
 * 셀 대역 스냅숏 위의 [CellSignals](S3a 스펙 §7.4). 호스트가 pump 직전에 [snapshot] 을 갈아 끼우고, 미들웨어는 같은 잠금
 * 아래에서 읽는다.
 *
 * - [observe]: 스냅숏에서 자리 id 로 답한다(제시 자리·슬롯·플릿 인계 자리). 스냅숏에 없는 자리는 `null`(말이 없다)이다. 그 자리에 사람의 확인([confirmations])이
 *   있고 설비가 말이 없거나 «있다» 고만 했으면 확인한 신원으로 답한다. 설비가 비었다·다른 것이 있다·무엇인지까지 말했으면 설비의 답이
 *   이긴다 — 확인은 관측이 모자란 자리를 채울 뿐 관측을 뒤집지 않는다.
 * - [holding]: 그 자재를 든 **제시 자리**만 낸다. 채운 슬롯을 대안 자리로 내면 미들웨어가 막 놓은 자재를 다시 집으러 보낸다.
 * - [signal]: 스냅숏의 신호에서 이름으로 값과 관측 시각을 낸다(S3b 스펙 §6.6). 모르는 이름은 `null`(못 읽었다)이고
 *   설비 대기는 그때 기한까지 기다린다.
 *
 * 스냅숏이 없으면 셋 모두 `null`(못 물어봄)이다.
 */
class CellBandSignals : CellSignals {

    var snapshot: CellSnapshot? = null

    /** 출발 자리 id → 사람의 확인. 호스트가 제출 한 번 동안만 채우고 끝나면 비운다. 비어 있으면 설비의 답 그대로다. */
    var confirmations: Map<String, SourceConfirmation> = emptyMap()

    override fun observe(location: String): SlotSignal? {
        val seen = snapshot?.let { current ->
            (current.presentations.asSequence() + current.slots.asSequence() + current.handovers.asSequence()).firstOrNull { it.id == location }
        }?.let { SlotSignal(it.occupied, it.material, it.observedAt) }
        val confirmed = confirmations[location] ?: return seen
        if (seen != null && !(seen.occupied && seen.identity == null)) return seen
        return SlotSignal(occupied = true, identity = confirmed.identity, observedAt = confirmed.confirmedAt)
    }

    override fun holding(material: String): List<String>? =
        snapshot?.presentations?.filter { it.occupied && it.material == material }?.map { it.id }

    override fun signal(name: String): NamedSignal? =
        snapshot?.signals?.firstOrNull { it.name == name }?.let { NamedSignal(it.value, it.observedAt) }
}
