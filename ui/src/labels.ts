import type {
  CloseBasis,
  CloseResult,
  CommissioningState,
  Connection,
  FaultKind,
  CancelResult,
  HoldDecision,
  IncidentDecision,
  HostSubmitResult,
  JobResponseDisposition,
  MockRunFailure,
  MockRunView,
  Owner,
  ReleaseResult,
  SkillFit,
  SourceMissing,
  SourceUnobserved,
  TestRequestState,
  UnrestoredResult,
} from './api'

/** 화면에 보이는 이름. 값은 운영 서비스의 열거형 그대로 받고, 이름만 여기서 붙인다. */
export const CONNECTION_LABEL: Record<Connection, string> = {
  FRESH: '신선',
  STALE: '오래됨',
  NO_REPORT: '보고 없음',
}

export const OWNER_LABEL: Record<Owner, string> = {
  SITE: '현장',
  OPERATOR: '운영자',
  ENGINEER: '엔지니어',
  NONE: '없음',
}

/** 막힘(스펙 §7.4 상태 막힘 4종)과 조작 거절(대응표)의 이름. 모르는 종류는 값 그대로 보인다. */
export const KIND_LABEL: Record<string, string> = {
  AWAITING_FIRST_REPORT: '첫 보고 대기',
  REPORT_STALE: '보고 오래됨',
  REPORTING_AFTER_RETIREMENT: '퇴역 뒤 보고',
  UNREGISTERED_ROW: '출처 없는 행',
  BAD_REQUEST: '본문 오류',
  WRONG_DOOR: '이미 다른 문으로 들어온 기체',
  RETIRED_ALREADY: '이미 퇴역한 기체',
  RETIRE_BAD_REQUEST: '퇴역 요청 오류',
  UNKNOWN_ROBOT: '모르는 기체',
  ADAPTER_BAD_REQUEST: '제품·빌드 형식 오류',
  UNKNOWN_ADAPTER: '없는 제품',
  VERSION_CONFLICT: '같은 버전에 다른 계약값',
  INSTANCE_BAD_REQUEST: '인스턴스 본문 오류',
  UNBOUND: '바인딩 없음',
  SITE_NAMES_UNREGISTERED: '명칭 기록 없음',
  SITE_NAMES_UNANSWERED: '기체가 명칭에 답하지 않음',
  SITE_NAMES_CONTRADICTED: '기체가 아는 명칭 없음',
  PROFILE_UNREADABLE: '읽을 수 없는 문서',
  REVISION_NOT_MONOTONIC: '리비전 번호가 오르지 않음',
  UNKNOWN_REVISION: '없는 리비전',
  REVISION_NOT_TESTABLE: '시험할 수 없는 상태',
  ACTIVATION_REFUSED: '활성화 조건 미달',
  UNKNOWN_BUILD: '없는 빌드',
  REVISION_NOT_ACTIVE: '활성 리비전이 아님',
  ROBOT_RETIRED: '퇴역한 기체',
  CONTRACT_TOO_OLD: '빌드의 계약이 낮음',
  NO_ACTIVE_BINDING: '활성 바인딩 없음',
  NOTHING_TO_REGISTER: '등록할 명칭 없음',
  UNCLASSIFIED: '분류되지 않은 거부',
  SETTINGS_VERSION_CONFLICT: '현장 설정 버전 충돌',
  JOB_ORDER_BAD_REQUEST: '작업 지시 폼 오류',
  UNKNOWN_WORK_MASTER: '받지 않는 임무',
  UNIT_ID_CONFLICT: '단위 id 겹침',
  NO_ELIGIBLE_ROBOT: '배정 가능한 기체 없음',
  MODE_NOT_ALLOWED: '이 모드에서 할 수 없는 조작',
  ACTOR_REQUIRED: '행위자 없음',
  // 임무 정의 거부 10종(S3b JSON 계약 §3.1). 호스트는 바닥 소유를 쥐지 않아 FLOOR_UNOWNED 를 내지 않지만 이름은 둔다.
  // SOURCE_OBSERVATION_UNUSED 는 picasso `b57964e` 가 더한 열한째 종류다. 서브모듈을 올리기 전에는 호스트가 내지 않는다.
  // DESTINATION_OBSERVATION_UNDECLARED·DESTINATION_OBSERVATION_UNUSED 는 picasso `786a0a2` 가 더한 열두째·열셋째 종류다.
  UNREADABLE: '읽을 수 없는 정의',
  DUPLICATE_NODE_ID: '노드 id 겹침',
  DEADLINE_INVALID: '대기 기한 오류',
  SIGNAL_NOT_IN_SPEC: '신호 사양에 없는 신호',
  // 임무 거부(기대 값이 신호 종류에 맞지 않음)와 현장의 신호 쓰기 거부(BOOLEAN 에 틀린 값)가 같은 이름을 쓴다.
  SIGNAL_VALUE_INVALID: '신호 종류에 맞지 않는 값',
  FLOOR_UNOWNED: '바닥 소유 없음',
  SKILL_NOT_IN_CONTRACT: '계약에 없는 스킬',
  SKILL_NOT_ON_SITE: '현장에 없는 스킬',
  SAFETY_SIGNAL_WAIT: '안전 신호 대기',
  SOURCE_OBSERVATION_UNDECLARED: '출발 관측 선언 없음',
  SOURCE_OBSERVATION_UNUSED: '출발 관측 선언 미사용',
  DESTINATION_OBSERVATION_UNDECLARED: '목적지 관측 선언 없음',
  DESTINATION_OBSERVATION_UNUSED: '목적지 관측 선언 미사용',
  // 임무·신호 조작의 사전 거부와 호스트·현장의 4xx(S3b JSON 계약 §10).
  MISSION_BAD_REQUEST: '임무 요청 오류',
  REASON_REQUIRED: '사유 없음',
  DRAFT_NOT_FOUND: '없는 초안',
  REQUEST_ID_REUSED: '요청 id 재사용',
  HOST_SILENT: '실행 호스트 불통',
  SIGNAL_BAD_REQUEST: '신호 값 형식 오류',
  UNKNOWN_SIGNAL: '모르는 신호',
  SAFETY_SIGNAL_READ_ONLY: '안전 신호는 쓸 수 없음',
  // 장애 주입의 현장 거부와 운영 서비스의 사전 거부(S4a JSON 계약 §1.3·§9.7).
  NO_RUNNING_TASK: '진행 중 태스크 없음',
  UNSUPPORTED_FAULT: '받지 않는 장애 종류',
  FAULT_REFUSED: 'mimic 엔진이 강제를 거부함',
  UNSUPPORTED_MEDIA_TYPE: 'JSON 이 아닌 본문',
  FAULT_BAD_REQUEST: '장애 주입 본문 오류',
  RESOLVE_BAD_REQUEST: '판단 본문 오류',
  // 재기동 뒤 판단(S4b 계약 H5·H7). 운영 서비스의 송신 기록 사전 거부도 둔다.
  INSTANCE_MISMATCH: '실행 호스트가 재기동해 인스턴스가 다름',
  JOB_RESPONSE_BAD_REQUEST: '송신 기록 요청 오류',
  // 운영자 실행 취소와 기체 빈손 확인(P0-b2).
  CANCEL_BAD_REQUEST: '취소 본문 오류',
  CLEAR_BAD_REQUEST: '빈손 확인 본문 오류',
  RESIDUAL_HOLD_NOT_FOUND: '없는 잔여 파지 표시',
  RESIDUAL_HOLD_ALREADY_CLEARED: '이미 해소한 잔여 파지 표시',
  // 빈손 확인과 발신자 관측의 어긋남(B2).
  RESIDUAL_HOLD_OBSERVATION_MISMATCH: '발신자는 아직 든 채라고 말함',
  RESIDUAL_HOLD_CITATION_INVALID: '지목한 관측이 저장된 충돌과 다름',
  // 운영자 결함 감수(S3a JSON 계약 §9.8). 요청 id 재사용(REQUEST_ID_REUSED)과 인스턴스 다름은 위의 이름을 같이 쓴다.
  RELEASE_BAD_REQUEST: '수용 본문 오류',
}

/** «시운전» 칸(P2·S1d 스펙 §8.5). 연결 칸과 합치지 않는다. */
export const COMMISSIONING_LABEL: Record<CommissioningState, string> = {
  COMPLETE: '완료',
  INCOMPLETE: '미완',
  RETIRED: '퇴역',
}

/** 소프트웨어 대조(P2·S1d 스펙 §9). 시운전을 막지 않고 보이기만 한다. 모르는 값은 그대로 보인다. */
export const SOFTWARE_LABEL: Record<string, string> = {
  MATCH: '일치',
  MISMATCH: '불일치',
  UNREPORTED: '보고 없음',
}

/** 시험 요청 상태(P2·S1d 스펙 §9). */
export const TEST_REQUEST_LABEL: Record<TestRequestState, string> = {
  NONE: '요청 없음',
  WAITING: '대기',
  RUNNING: '실행 중',
  EXPIRED: '만료',
  DONE: '끝남',
}

export const kindLabel = (kind: string) => KIND_LABEL[kind] ?? kind

/** 호스트·현장 거부의 이름. 거부 본문을 못 읽어 이름이 없으면(null) 이유 없음이다. */
export const rejectionLabel = (error: string | null) => (error === null ? '이유 없음' : kindLabel(error))

/** 거부 알림 한 줄. 사람이 읽는 문장(detail)이 없으면 이름만 적는다. */
export const rejectionText = (who: string, rejection: { error: string | null; detail: string | null }) =>
  `${who}(${rejectionLabel(rejection.error)})${rejection.detail ? `. ${rejection.detail}` : ''}`

/** 실행 호스트의 스킬 적합(S3a 스펙 §9.1). `UNKNOWN` 은 기체 케이퍼빌리티를 못 물어본 것이다. */
export const SKILL_FIT_LABEL: Record<SkillFit, string> = {
  FIT: '적합',
  MISSING: '모자람',
  UNKNOWN: '모름',
}

/** 작업 지시 제출의 호스트 결과(S3a JSON 계약 §4). */
export const SUBMIT_RESULT_LABEL: Record<HostSubmitResult, string> = {
  ACCEPTED: '배정됨',
  IDEMPOTENT: '같은 작업 지시의 기존 실행',
  REJECTED: '거부됨',
  UNASSIGNED: '미배정',
}

/** 모의 실행 실패의 하위 범주(S3b JSON 계약 §4.5). */
export const MOCK_RUN_FAILURE_LABEL: Record<MockRunFailure, string> = {
  DEFINITION: '정의(같은 신호에 다른 기대 값)',
  SUBMISSION_REJECTED: '표본 작업 지시 거부',
  NOT_SETTLED: '종결하지 않음',
  WALL_CLOCK_LIMIT: '실제 시간 상한 초과',
  EXECUTION_FAILED: '실행 실패',
}

/** 모의 실행의 통과 여부 한 줄. 실패면 하위 범주를 붙인다(S3b JSON 계약 §4.5). */
export function mockRunVerdict(run: MockRunView): string {
  if (run.passed) return '통과'
  const { failure } = run.result
  return failure === null ? '실패' : `실패(${MOCK_RUN_FAILURE_LABEL[failure] ?? failure})`
}

/** 시작용 정의의 화면 이름(S3b JSON 계약 §4.2). 모르는 id 는 호스트가 준 제목을 쓴다. */
export const TEMPLATE_LABEL: Record<string, string> = {
  DATA_V1: '데이터 정의 템플릿',
  ARRIVAL_WAIT: '랙 도착 대기 템플릿',
  ARRIVAL_WAIT_HOLD: '운영자 보류 대기 템플릿',
}

/** 장애 주입 종류(S4a JSON 계약 §1.1). */
export const FAULT_KIND_LABEL: Record<FaultKind, string> = {
  SKILL_EXECUTION_FAILED: '스킬 실패',
  CONNECTION: '연결 상태',
}

/** 송신 기록의 처분(S4b 스펙 T6, §8). */
export const DISPOSITION_LABEL: Record<JobResponseDisposition, string> = {
  SENT: '송신',
  RESTART_DUPLICATE: '재기동 중복(송신 안 함)',
}

/** 운영자 판단 둘(S4a 스펙 §8.3). 버튼 이름이기도 하다. */
export const DECISION_LABEL: Record<HoldDecision, string> = {
  CONFIRM_DONE: '완료 확인',
  REWORK: '재작업',
}

/**
 * 인시던트 판단 칸의 값 넷(picasso §15.320). 운영자 판단 둘에 사람의 걸음 둘을 더한다. 회복 조치 승인은 «회복 완료» 가 아니다 — 조치와
 * 원래 단위의 결과는 그 뒤의 인시던트·작업 응답으로 읽는다.
 */
export const RESOLUTION_LABEL: Record<IncidentDecision, string> = {
  ...DECISION_LABEL,
  OPERATOR_CANCEL: '운영자 취소',
  REMEDY_APPLIED: '회복 조치 승인',
}

/** 실행 호스트 취소 결과(P0-b2). */
export const CANCEL_RESULT_LABEL: Record<CancelResult, string> = {
  CANCELED: '취소를 걸었습니다. 실행 호스트의 다음 pump 에서 중단(ABORTED)으로 종결합니다',
  NOT_CANCELABLE: '취소할 수 없는 실행입니다',
  NOT_FOUND: '지금 실행 호스트 인스턴스에 없는 실행입니다',
  REFUSED: '에이전트는 실행을 취소하지 못합니다',
}

/** 실행 호스트 결함 감수 결과(S3a JSON 계약 §5.10). */
export const RELEASE_RESULT_LABEL: Record<ReleaseResult, string> = {
  RELEASED: '수용했습니다. 실행 호스트의 다음 pump 에서 다음 단위를 냅니다',
  NOT_BLOCKED: '막는 결함이 없습니다',
  BLOCK_CHANGED: '본 결함과 지금 막는 결함이 다릅니다. 실행 목록을 다시 읽고 결함을 확인한 뒤 다시 판단하십시오',
  NOT_FOUND: '지금 실행 호스트 인스턴스에 없는 실행입니다',
  REFUSED: '에이전트는 결함을 수용하지 못합니다',
}

/** 복원 못 한 실행의 운영자 종결 결과(결함 14). */
export const CLOSE_RESULT_LABEL: Record<CloseResult, string> = {
  CLOSED: '운영자 종결했습니다. 작업 요청은 미검증(UNVERIFIED)으로 상위 시스템에 전달됩니다',
  ALREADY_CLOSED: '이미 운영자가 종결한 작업 지시입니다',
  NOT_CLOSABLE: '운영자 종결할 수 없는 실행입니다',
  TASK_LIVE: '기체가 해당 작업 지시의 태스크를 아직 실행하고 있어 운영자 종결하지 않았습니다. 정지를 증언해도 받아들이지 않습니다',
  STOP_UNCONFIRMED: '로봇이 멈춘 것을 확인하지 못해 운영자 종결하지 않았습니다. 현장에서 정지를 확인했다면 증언 항목을 체크하고 다시 시도하십시오',
  TARGET_CHANGED: '본 실행이 지금 일지와 다릅니다. 실행 목록을 다시 읽고 판단하십시오',
  NOT_FOUND: '해당 작업 지시의 일지 행이 없습니다',
  REFUSED: '에이전트는 복원 못 한 실행을 종결하지 못합니다',
}

/** 종결 근거의 이름(결함 14). */
export const CLOSE_BASIS_LABEL: Record<CloseBasis, string> = {
  SNAPSHOT: '기체 스냅숏 정지 확인',
  ATTESTED: '운영자 정지 증언',
}

/** 실행 목록의 복원 못 한 실행(결함 14) 글자. */
export const UNRESTORED_TEXT = {
  heading: '복원 못 한 실행',
  intro: '실행 호스트가 재기동 시 리빌드하지 못한 실행입니다. 운영자가 종결하면 작업 요청은 미검증(UNVERIFIED)으로 끝납니다.',
  table: '복원 못 한 실행 목록',
  result: { GAVE_UP: '포기', DEFERRED: '미룸', CLOSED: '증언 종결' } as Record<UnrestoredResult, string>,
  closedBy: (approverId: string, reason: string) => `운영자 종결: ${approverId}, ${reason}`,
  excludedUntilSnapshot: '스냅숏 확인 전까지 배정 제외',
  closeButton: '운영자 종결',
  operatorOnly: '복원 못 한 실행의 종결은 운영자 모드에서 합니다',
  reportClosed: (approverId: string) => `운영자 종결(${approverId})`,
} as const

/** 실행 목록의 «활성 + 최근 N, 이력은 페이지»(HA 설계 기록 §10) 글자. */
export const EXECUTION_LIST_TEXT = {
  scope: (recent: number) => `진행 중이거나 판단이 필요한 실행과 최근 종결한 실행 ${recent}건을 표시합니다. 그보다 오래된 실행은 이전 실행에서 확인합니다.`,
  settledAt: (at: string) => `종결 ${at}`,
  /** 정착 이력 줄의 작업 지시 버전(그 정착을 알린 작업 응답의 버전). 한 작업 지시가 다시 정착하면 줄이 여럿이다. */
  orderVersion: (version: number) => `작업 지시 버전 ${version}`,
  /** 같은 작업 지시가 이 정착 뒤에 다시 정착한 줄. */
  superseded: '이후 다시 종결',
  otherInstance: (instanceId: string) => `이전 인스턴스 ${instanceId}`,
  noSnapshot: '종결 기록에 단위·작업 응답이 없습니다',
  historyHeading: '이전 실행',
  historyTable: '이전 실행 목록',
  olderCount: (count: number) => `이전 실행 ${count}건이 더 있습니다`,
  loadOlder: '이전 실행 더 보기',
  collapse: '이전 실행 접기',
  historyError: (error: string) => `이전 실행을 읽지 못했습니다: ${error}`,
  historyReset: '종결 기록이 크게 바뀌어 이전 실행을 다시 읽어야 합니다',
} as const

/** 이미 끝나 이빅션한 작업 지시를 실행 호스트가 거부했을 때의 안내(picasso `FORGOTTEN_JOB_ORDER`). */
export const FORGOTTEN_JOB_ORDER_TEXT = '이미 끝나 이빅션한 작업 지시입니다. 같은 작업 지시는 다시 받지 않으므로 다시 할 일은 새 작업 지시로 냅니다.'

/** 운영자 실행 취소와 잔여 파지 확인 문구(P0-b2). */
export const EXECUTION_CONTROL_TEXT = {
  cancelHeading: '실행 취소',
  cancelTarget: '취소할 실행',
  cancelChoose: '실행 선택',
  cancelWarning:
    '취소하면 남은 단위를 더 돌리지 않고 이 실행을 중단(ABORTED)으로 끝냅니다. 로봇이 든 물체와 설비 상태를 현장에서 확인한 뒤 결정하세요.',
  partialNote: '부분 완료(PARTIAL) 실행은 취소하기 전까지 기체를 쥐고 있어 그 기체에 새 작업이 배정되지 않습니다.',
  cancelReason: '취소 사유',
  cancelConfirm: '기체·설비 상태를 확인했고 이 실행을 끝냅니다',
  cancelButton: '현장 확인 후 실행 취소',
  cancelOperatorOnly: '실행 취소는 운영자 모드에서 합니다',
  canceledBy: '취소 요청',
  holdsHeading: '잔여 파지 확인',
  holdsIntro:
    '실행이 끝날 때 로봇이 물체를 든 채로 관측된 기체입니다. 사람이 현장에서 빈손을 확인하기 전까지 그 기체는 배정에서 빠집니다.',
  holdsEmpty: '미해소 잔여 파지 표시가 없습니다',
  holdsUnknown: '모름: 잔여 파지 표시를 아직 읽지 못했습니다',
  objectUnnamed: '이름 없음',
  emptyButton: '기체 빈손 확인',
  emptyWarning:
    '이 확인은 물리 확인입니다. 로봇 손과 그리퍼에 물체가 남지 않았는지 현장에서 직접 보고 확인하세요. 화면이나 로봇 보고만으로 확인하지 마세요.',
  emptyReason: '빈손 확인 근거',
  emptyConfirm: '기체 손에 남은 물체가 없음을 현장에서 직접 확인했습니다',
  emptyOperatorOnly: '기체 빈손 확인은 운영자 모드에서 합니다',
  emptyDone: '빈손 확인이 기록되었습니다. 그 기체는 다음 배정 판정부터 다시 판정됩니다',
  // 빈손 확인과 발신자 관측의 어긋남(B2). 지목 값은 그 어긋남 응답에서만 온다.
  mismatchHeading: '발신자 관측과 어긋남',
  mismatchIntro:
    '실행 호스트가 확인 순간 읽은 기체 스냅숏은 아직 든 채입니다. 해소하지 않았습니다. 현장에서 빈손을 직접 확인했을 때만 이 관측을 지목해 다시 확인하세요. 사람이 손을 비울 수단이 없는 기종은 조치 승인 경로로 내려놓는 것이 정상입니다.',
  mismatchAcknowledge: (held: string) => `발신자는 지금 ${held} 를 들고 있다고 말한다. 현장에서 빈손을 확인했다`,
  reconfirmButton: '관측을 지목해 다시 확인',
  mismatchResult: '발신자가 기체가 아직 든 채라고 말해 해소하지 않음. 현장에서 빈손을 확인했으면 아래 관측을 지목해 다시 확인하십시오',
  citationInvalidResult: '지목한 관측이 실행 호스트에 저장된 충돌과 달라 해소하지 않음. «기체 빈손 확인» 부터 다시 하십시오',
  // 운영자 결함 수용(S3a JSON 계약 §5.10·§9.8).
  releaseHeading: '결함 수용',
  releaseIntro: '수용은 결함을 지우지 않으며, 이 결함들이 다음 단위를 막지 않게 하고 다음 로봇 단위를 냅니다. 기체 상태를 현장에서 확인한 뒤 결정하세요.',
  releaseRepeat: '같은 결함은 다시 막지 않고 새 결함은 다시 막습니다.',
  releasePermitNote: '작업 허가 대기는 수용으로 풀리지 않습니다.',
  releaseTarget: '수용할 실행',
  releaseChoose: '실행 선택',
  releaseFaults: '수용할 결함',
  releaseReason: '수용 사유',
  releaseConfirm: '기체 상태를 현장에서 확인했고 이 결함을 안고 계속합니다',
  releaseButton: '현장 확인 후 결함 수용',
  releaseOperatorOnly: '결함 수용은 운영자 모드에서 합니다',
  blockedCount: (count: number) => `결함 차단 ${count}건`,
  releasedBy: '결함 수용',
  incidentPointer: '이 실행은 지금도 결함으로 막혀 있습니다. 수용은 운영 영역의 실행 구역에서 합니다.',
  releaseApplied: '응답은 없었으나 다시 읽어 보니 수용이 걸림',
  releaseNotApplied: '응답 없음. 다시 읽어 보니 수용이 걸리지 않음. 실행 목록에서 확인하십시오',
  releaseUnconfirmed: '응답 없음. 수용이 걸렸을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 실행 목록에서 확인하십시오',
  // 건너뛴 대상 재작업. 순회가 종결한 뒤 이동에 실패해 점검을 하지 않은 대상을 한 번에 하나씩 새 시도로 다시 보낸다.
  reworkHeading: '건너뛴 대상 재작업',
  reworkIntro: '순회가 종결된 뒤 이동에 실패해 건너뛴 대상을 한 번에 하나씩 재작업할 수 있습니다. 같은 실행과 작업 지시에서 해당 대상의 이동과 점검을 다시 보냅니다.',
  reworkSiteNote: '경로 차단 등 이동 실패 원인이 사라졌는지 현장에서 먼저 확인한 뒤 재작업하세요.',
  reworkTarget: '재작업할 건너뛴 대상',
  reworkChoose: '건너뛴 대상 선택',
  reworkOption: (executionId: string, jobOrderId: string, robotId: string, unitId: string) =>
    `${executionId} · ${jobOrderId} · ${robotId} · ${unitId}`,
  reworkExpiry: (time: string) => `보존 기간 만료: ${time}`,
  reworkNoExpiry: '보존 만료 없음',
  reworkEmpty: '재작업할 건너뛴 대상이 없습니다',
  reworkReason: '재작업 사유',
  reworkConfirm: '기체 상태를 현장에서 확인했고 이동 실패 원인이 해소되어 재작업을 진행합니다',
  reworkButton: '현장 확인 후 재작업',
  reworkOperatorOnly: '재작업은 운영자 모드에서 합니다',
  // 판단이 붙은 인시던트 id 를 모르면(이동 인시던트가 없는 실행) 괄호를 뺀다.
  reworkResolved: (incidentId: string | null) =>
    incidentId ? `선행 이동 인시던트에 판단이 섰습니다(${incidentId})` : '선행 이동 인시던트에 판단이 섰습니다',
  reworkRefused: (detail: string | null) => (detail ? `실행 호스트가 재작업을 거부했습니다. ${detail}` : '실행 호스트가 재작업을 거부했습니다.'),
  reworkEvicted: '보존 기간이 지나 이빅션되었습니다. 새 작업 지시를 내리십시오',
  reworkNotSkipped: '이미 재작업되었거나 더는 건너뛴 대상이 아닙니다. 실행 목록에서 다시 확인하십시오',
  reworkApplied: '응답은 없었으나 다시 읽어 보니 재작업이 걸림',
  reworkNotApplied: '응답 없음. 다시 읽어 보니 재작업이 걸리지 않음. 실행 목록에서 확인하십시오',
  reworkUnconfirmed: '응답 없음. 재작업이 걸렸을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 실행 목록에서 확인하십시오',
  // 복원 못 한 실행의 운영자 종결(결함 14).
  closeHeading: '복원 못 한 실행 운영자 종결',
  closeWarning:
    '운영자 종결하면 이 작업 요청은 미검증(UNVERIFIED)으로 끝나고 성공으로 추정하지 않습니다. 상위 시스템은 그 결과를 받습니다. 로봇을 멈추지는 않습니다.',
  closeDeferredNote: '기체 스냅숏을 읽지 못해 미룬 실행입니다. 로봇이 현장에서 멈춘 것을 확인했다고 증언해야 운영자 종결할 수 있습니다.',
  closeReason: '종결 사유',
  closeAttest: '로봇이 현장에서 멈춘 것을 확인했습니다',
  closeButton: '현장 확인 후 운영자 종결',
  closeResultAria: '운영자 종결 결과',
  closeApplied: '응답은 없었으나 다시 읽어 보니 운영자 종결이 걸림',
  closeNotApplied: '응답 없음. 다시 읽어 보니 운영자 종결이 걸리지 않음. 실행 목록에서 확인하십시오',
  closeUnconfirmed: '응답 없음. 운영자 종결이 걸렸을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 실행 목록에서 확인하십시오',
  // 인시던트 판단 폼의 Refused. 건너뛴 대상 재작업의 거부도 이 폼의 인시던트 상세로 올 수 있어 승인자 종류를 가리지 않는다.
  incidentRefused: (detail: string | null) => (detail ? `실행 호스트가 판단을 거부했습니다. ${detail}` : '실행 호스트가 판단을 거부했습니다.'),
} as const

/** 조치 탐색이 대안을 못 찾은 종류(picasso `Remedy.None.Cause`). 「없다」와 「덜 봤다」를 가른다. */
export const REMEDY_CAUSE_LABEL: Record<string, string> = {
  NO_CAPABILITY: '케이퍼빌리티 없음',
  DEPTH_LIMIT: '탐색 상한 도달',
}

export const reviewLabels:Record<string,string>={NOT_CONFIRMED:'현장 상태 미확인',NOT_STARTED:'현장 미착수 확인',IN_PROGRESS:'현장 진행 중 확인',COMPLETED:'현장 완료 관측'}

/** 공유 스테이션의 작업 번호 검증과 격리 문구. 서버 사유와 같은 낱말을 쓴다. */
export const STATION_TEXT={
  jobSignal:'작업 번호 송신 신호',
  jobEchoSignal:'작업 번호 반환 신호',
  jobHelp:'작업 번호 일치를 검증하는 신호로, 설비는 시작 시 받은 번호를 유지했다가 완료 신호와 함께 반환해야 합니다. 연결하지 않으면 현장 확인 후 작업 해제 시 스테이션이 격리됩니다.',
  echoing:'작업번호 검증',
  quarantined:'격리 상태',
  idleButton:'현장 확인 후 격리 해제',
  idleWarning:'설비가 비어 있고 이전 작업 로봇이 멈췄는지 현장에서 확인하세요. 설비 완료 신호가 꺼진 것을 확인한 뒤 판단하세요.',
  idleConfirm:'설비·로봇 상태와 격리 해제 가능 여부를 확인했습니다',
  clearNote:'자원을 해제해도 이전 작업은 멈추지 않으며 해당 스테이션은 격리됩니다.',
  // 기한 초과·신호 관측 불명으로 확인 필요가 된 작업의 운영자 완료 확인(CONFIRM_COMPLETION).
  completionNote:'완료 확인 시 스테이션 작업을 완료하고 격리 없이 스테이션 점유를 해제합니다. 작업 번호 쌍이 없는 스테이션은 설비 완료 신호가 해당 작업의 것인지 현장 확인해야 합니다.',
  completionConfirm:'로봇 물리 완료와 설비 완료 신호를 현장에서 확인했습니다',
  completionButton:'현장 확인 후 완료 확인',
} as const

/** 출발 결품의 답 하나. REJECTED 의 거부와 UNASSIGNED 의 기체별 사유가 같은 모양이다. */
type SourceAnswer = { alternativeLocations?: string[] | null; sourceMissing?: SourceMissing | null }

/** 대체 위치 한 줄. 출발 결품일 때만 다른 자리를 물으므로 null 목록은 거기서만 «조회 불가» 이고 그 밖에는 말하지 않는다. 빈 목록과 접지 않는다. */
export function alternativesLabel(answer: SourceAnswer): string | null {
  const listed = answer.alternativeLocations ?? null
  if (listed === null) return answer.sourceMissing ? '대체 위치 조회 불가' : null
  return listed.length === 0 ? '대체 위치 없음' : `대체 위치: ${listed.join(', ')}`
}

/** 출발 자리 한 줄. `observed` 가 null 이면 빈 자리다. */
export const sourceMissingLabel = (missing: SourceMissing) =>
  `출발 자리 ${missing.source} · ${missing.observed === null ? '비어 있음' : `${missing.observed} 있음`}`

/** 출발 미관측 한 줄. 결품 줄과 다른 말로 쓴다 — 설비가 말이 없었는지 «있다» 고만 했는지를 접지 않는다. */
export const sourceUnobservedLabel = (unobserved: SourceUnobserved) =>
  `출발 미관측 · 출발 자리 ${unobserved.source} · ${unobserved.occupied === null ? '설비 응답 없음' : '있음 · 신원 모름'}${unobserved.material === null ? '' : ` · 확인할 자재 ${unobserved.material}`}`

/**
 * 작업 허가 막음(ADR 52)의 문구. 작업 허가는 현장 안전 신호를 정보로 비춘 값이다. picasso 는 이 값으로 새 로봇 명령만 내지 않고 기체를
 * 세우지 않는다 — 기체를 세우는 것은 안전 계통이다. 운영자 보류·결함 막음과 다르게 운영자 판단이나 해제로 풀리지 않는다.
 */
export const PERMIT_BLOCK_TEXT = {
  heading: '작업 허가 대기',
  intro: '현장 안전 신호가 허가를 내지 않아 이 실행의 새 로봇 명령을 내지 않고 기다립니다. 실패나 운영자 보류가 아닙니다.',
  notSafety:
    '작업 허가는 안전 신호를 비춘 정보이며 기체를 세우는 것은 안전 계통입니다. 이미 나간 명령과 도는 단위의 취소는 이 대기와 상관없이 그대로입니다. 다만 미확정 단위의 취소는 재조회가 필요하여 허가가 돌아온 뒤에 나갑니다.',
  notOperator: '운영자 판단이나 결함 해제로 풀리지 않습니다. 아래 풀릴 근거가 채워지면 다음 pump 에 명령을 냅니다.',
  unit: '막힌 단위',
  withheld: '내지 않은 명령',
  since: '처음 막힌 시각',
  robot: '기체',
  permit: '허가',
  cause: '까닭',
  subject: '막는 주체',
  resumeBasis: '풀릴 근거',
  source: '원천',
  observedAt: '관측 시각',
  none: '없음',
} as const

/** 작업 허가 값. */
/**
 * 퇴역으로 막힌 새 로봇 명령(picasso §15.249, 까닭 `RETIRED`). 표시는 작업 허가 대기와 같은 자리지만 원천이 안전 신호가 아니라 기체 목록의
 * 운영자 판단이라 안내가 다르다. 퇴역만으로 막혔으면 [PERMIT_BLOCK_TEXT] 의 제목과 안내 대신 이것을 쓰고, 작업 허가와 섞였으면 [mixed] 를 더한다.
 */
export const RETIRED_BLOCK_TEXT = {
  heading: '퇴역 기체 대기',
  intro: '퇴역한 기체라 이 실행의 새 로봇 명령을 내지 않고 기다립니다. 실패나 운영자 보류가 아닙니다.',
  notStop:
    '퇴역은 기체 목록의 운영자 판단이며 기체를 세우지 않습니다. 이미 나간 명령과 도는 단위의 취소는 이 대기와 상관없이 그대로입니다. 다만 미확정 단위의 취소는 재조회가 필요하여 퇴역을 취소한 뒤에 나갑니다.',
  resume: '기체 화면에서 이 기체의 퇴역을 취소하면 다음 pump 에 명령을 냅니다.',
  mixed: '까닭이 퇴역인 기체는 기체 화면에서 퇴역을 취소해야 풀립니다.',
}

export const WORK_PERMIT_LABEL: Record<string, string> = {
  PERMITTED: '허가',
  INHIBITED: '차단',
  UNKNOWN: '모름(차단)',
}

/** 막힌 명령의 종류(picasso WithheldIssue). */
export const WITHHELD_ISSUE_LABEL: Record<string, string> = {
  START: '단위 출발',
  REVISION_UPDATE: '개정 갱신',
  IN_DOUBT_LOOKUP: '미확정 조회',
}

/** 허가 판정의 까닭(picasso PermitCause). */
export const PERMIT_CAUSE_LABEL: Record<string, string> = {
  REPORTED: '원천 보고',
  EXPIRED: '관측 만료',
  SESSION_CHANGED: '원천 세션 바뀜',
  SCOPE_UNKNOWN: '대상 기체 모름',
  NOT_COVERED: '허가 원천 없음',
  PORT_FAILED: '허가를 읽지 못함',
  RETIRED: '퇴역',
}
