package dev.picasso.ops.host.lease

import dev.picasso.contracts.v1.FenceStatus
import dev.picasso.middleware.RobotSnapshot
import java.time.Instant

/**
 * 기체 하나의 준비(HA 3단계, 수신 측 세대 대조 소비). 만료 인수 뒤 관측([LeaderState.HOLDING]) 안에서만 뜻이 있다.
 *
 * - [Ready]: pump 가 **지금 읽은**(미리 읽은 값이 아닌) 스냅숏의 응답 머리말 바닥값이 이 인스턴스의 세대와 같고 `fence_status` 가
 *   `DURABLE_CONFIRMED` 다 — 수신자가 이 세대로 바닥값을 내구 저장했고, 그보다 낮은 세대로 통과한 명령이 다 닫혔다고 답했다. 그 뒤로는
 *   래치다 — 뒤의 응답이 후퇴([retreats]: 바닥값이 내 세대보다 낮음(0 포함), 또는 바닥값은 같은데 `VOLATILE`·확인 답 없음)를 보일 때만 푼다.
 *   바닥값이 같고 `DURABLE_PENDING` 인 답은 풀지 않는다(래치 뒤 낮은 세대로 통과한 명령은 없으므로 그 미확정은 이 세대 자신의 것이다).
 *   근거는 처음 연 때 `mission.host_readiness_evidence` 에 남긴다.
 * - [Waiting]: 그 밖. 까닭은 [WaitReason].
 */
sealed interface Readiness {
    val robotId: String

    /**
     * [floor] 는 응답 머리말의 바닥값, [fenceStatus] 는 확인 답, [observedAt] 은 그 읽기를 받은 호스트 시각, [seq] 는 그 요청의 순번이다
     * ([dev.picasso.ops.host.HostRobotPort] 가 요청을 보내기 직전에 매기는, 엄격히 증가하는 번호 — 이보다 작은 순번의 응답은 이 준비를 풀지 않는다).
     */
    data class Ready(
        override val robotId: String,
        val floor: Long,
        val fenceStatus: FenceStatus,
        val observedAt: Instant,
        val seq: Long = 0,
    ) : Readiness

    /** [floor]·[fenceStatus] 는 마지막으로 본 응답(미리 읽기 포함)의 값이다. 읽지 못했으면 `null`. */
    data class Waiting(override val robotId: String, val reason: WaitReason, val floor: Long? = null, val fenceStatus: FenceStatus? = null) : Readiness
}

/** 기체가 아직 준비되지 않은 까닭. 화면과 `GET /host/leader` 가 [text] 를 싣는다. */
enum class WaitReason(val text: String) {
    /** 이 세대로 지금 읽은 적이 없다(읽기 실패 포함), 또는 확인 답이 아니다(`UNSPECIFIED`). */
    UNREAD("아직 확인 안 함"),

    /** 수신자가 세대를 대조하지 않는다(응답 바닥값 0 — `UNFENCED` 거나 이 칸을 모르는 옛 수신자). */
    UNFENCED("수신자 대조 안 함(바닥값 0)"),

    /** 수신자의 바닥값 저장이 메모리다 — 다시 뜨면 0 부터라 자동 재개의 자격이 없다. */
    VOLATILE("수신자 바닥값 휘발(VOLATILE)"),

    /** 앞 세대로 통과한 명령이 기한 안에 다 닫히지 않았거나 결과를 모르는 변경 호출이 남았다. */
    DURABLE_PENDING("앞 세대 명령 정리 미확정(DURABLE_PENDING)"),

    /** 응답 바닥값이 0 이 아닌데 이 세대와 다르다(더 높으면 밀려남으로 잃음에 간다). */
    FLOOR_MISMATCH("바닥값 불일치"),
}

/**
 * 실행 하나가 새 발행에 요구하는 대상 — 기체 단위를 맡은 기체 전부와, 플릿 단위가 있으면 플릿. «실행 → 필수 기체·플릿» 표의 한 줄이다. 실행 호스트가 호스트
 * 잠금 아래에서 지어 [HostLeadership.requirements] 로 넘긴다. [jobOrderId] 는 운반 참조(태스크 id `작업지시#단위`)로 실행을 되짚는 열쇠다.
 *
 * [subjects] 는 미들웨어 발행 관문이 권위를 묻는 기체(실행의 기체 전부 — 플릿 단위도 실행의 기체로 관문을 지난다)다. 기체 단위가 없는 플릿 전용
 * 실행은 [robots] 가 비고 [subjects] 에 묶인 기체가 든다 — 그 실행은 플릿 준비만 요구한다(묶인 기체는 명령을 받지 않는다).
 */
data class Requirement(
    val executionId: String,
    val jobOrderId: String,
    val robots: Set<String>,
    val fleet: Boolean,
    val subjects: Set<String> = robots,
)

/** 준비 판정(순수 함수). [mine] 은 이 인스턴스의 세대, [snapshot] 은 그 세대로 **지금** 읽은 스냅숏(못 읽었으면 `null`)이다. */
fun judgeReadiness(robotId: String, mine: Long, snapshot: RobotSnapshot?, at: Instant): Readiness {
    if (snapshot == null) return Readiness.Waiting(robotId, WaitReason.UNREAD)
    val floor = snapshot.controllerFloor
    val status = snapshot.fenceStatus
    fun waiting(reason: WaitReason) = Readiness.Waiting(robotId, reason, floor, status)
    return when {
        floor == 0L -> waiting(WaitReason.UNFENCED)
        floor != mine -> waiting(WaitReason.FLOOR_MISMATCH)
        status == FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED -> Readiness.Ready(robotId, floor, status, at)
        status == FenceStatus.FENCE_STATUS_VOLATILE -> waiting(WaitReason.VOLATILE)
        status == FenceStatus.FENCE_STATUS_DURABLE_PENDING -> waiting(WaitReason.DURABLE_PENDING)
        else -> waiting(WaitReason.UNREAD)
    }
}

/**
 * 래치된 준비를 풀 응답인가 — 바닥값이 내 세대보다 낮다(0 포함: 수신자가 바닥값을 잃었거나 대조를 껐다), 또는 바닥값은 같은데 `VOLATILE`·확인 답
 * 없음(`UNSPECIFIED`)이다. 바닥값이 같고 `DURABLE_PENDING` 이면 풀지 않는다(이 세대 자신의 미확정 — 평소 미확정 처리 경로가 맡는다). 바닥값이 더
 * 높으면 밀려남이라 잃음으로 간다(여기서 다루지 않음).
 */
fun retreats(mine: Long, snapshot: RobotSnapshot): Boolean {
    val floor = snapshot.controllerFloor
    return floor < mine ||
        floor == mine && (snapshot.fenceStatus == FenceStatus.FENCE_STATUS_VOLATILE || snapshot.fenceStatus == FenceStatus.FENCE_STATUS_UNSPECIFIED)
}

/**
 * 플릿이 준비되지 않은 까닭(HA 3단계 PR B2). 화면과 `GET /host/leader` 의 `readiness.fleet` 가 [text] 를 싣는다. 만료 인수 뒤 관측
 * ([LeaderState.HOLDING]) 안에서만 뜻이 있다.
 */
enum class FleetWaitReason(val text: String) {
    /** 이 세대로 플릿 인수 확인 답을 아직 못 받았다(안 닿음·5xx 포함). */
    UNREAD("플릿 아직 확인 안 함"),

    /** 플릿 포트가 현장 플릿 면이 아니라 인수 확인이 없다(프로세스 안 플릿 등) — 운영자 확인으로만 연다. */
    NO_CONFIRM("플릿 인수 확인 없음(운영자 확인 필요)"),

    /** 현장이 플릿 인수 확인을 모른다(옛 현장, 404·405 등 4xx) — 자동으로 열지 않는다. */
    UNSUPPORTED("현장이 플릿 인수 확인을 모름(옛 현장, 운영자 확인 필요)"),

    /** 현장이 플릿 세대를 대조하지 않는다(`SITE_HTTP_FENCE=off`·바닥값 0·`UNSPECIFIED`). */
    UNFENCED("플릿 수신자 대조 안 함(바닥값 0)"),

    /** 현장의 플릿 바닥값 저장이 메모리다. */
    VOLATILE("플릿 바닥값 휘발(VOLATILE)"),

    /** 앞 세대로 통과한 플릿 변경이 기한 안에 다 닫히지 않았다. */
    DURABLE_PENDING("앞 세대 플릿 변경 정리 미확정(DURABLE_PENDING)"),

    /** 응답 바닥값이 0 이 아닌데 이 세대보다 낮다(후퇴). */
    FLOOR_MISMATCH("플릿 바닥값 불일치"),

    /** 현장 기동 ID 가 바뀌었다 — 플릿 장부가 메모리라 재시작은 운반 상태의 복구가 아니다. 재확인 성공으로는 지우지 않는다(운영자 확인). */
    RECEIVER_RESTARTED("현장 재시작(플릿 장부 유실 가능, 운영자 확인 필요)"),

    /** 확인됐다 — 확인 뒤에 보낸 플릿 읽기로 재대조하고 근거를 적기를 기다린다. */
    RECONCILING("플릿 확인됨 — 재대조 대기"),
}

/**
 * 플릿의 준비(HA 3단계 PR B2). [Ready] 는 래치된 준비 — 확인 응답(바닥값 = 이 세대 ∧ `DURABLE_CONFIRMED`, 기동 ID [bootId])을 받은 뒤 그 확인
 * 순번([boundary])보다 뒤에 보낸 플릿 읽기로 한 pump 의 재대조를 마치고 근거를 적었다. [Waiting] 은 그 밖.
 */
sealed interface FleetReadiness {
    data class Ready(val floor: Long, val fenceStatus: String, val bootId: String, val boundary: Long, val observedAt: java.time.Instant) : FleetReadiness

    data class Waiting(val reason: FleetWaitReason, val floor: Long? = null, val fenceStatus: String? = null, val bootId: String? = null) : FleetReadiness
}

/** 확인됐으나 아직 재대조·근거를 기다리는 플릿 후보. [boundary] 는 그 확인 요청의 순번이다(이보다 뒤에 보낸 읽기만 재대조에 쓴다). */
data class FleetCandidate(
    val generation: Long,
    val boundary: Long,
    val floor: Long,
    val fenceStatus: String,
    val bootId: String,
    val observedAt: java.time.Instant,
)
