export type Mode = 'engineer' | 'operator'
export type RegistryState = 'OK' | 'REGISTRY_SILENT' | 'REGISTRY_UNAUTHORIZED'
export type Connection = 'FRESH' | 'STALE' | 'NO_REPORT'
export type Owner = 'SITE' | 'OPERATOR' | 'ENGINEER' | 'NONE'

export interface Robot {
  robotId: string
  siteId: string
  serialNumber: string | null
  displayName: string | null
  status: string
  lastReportedAt: string | null
  retiredAt: string | null
  retiredReason: string | null
  reportingAfterRetirement: boolean
}

/**
 * 막힘이나 거부 한 건. 화면에 내는 칸 5개와 맞춘다(스펙 §7.4). S2 에서 근거 버전을 더했다(S2 스펙 §6.4).
 * 근거 버전은 현장 설정에 기대는 판정(오래됨)에만 있고, 나머지는 없거나 null 이다.
 */
export interface Finding {
  kind: string
  observed: string
  expected: string
  checkedAt: string
  owner: Owner
  inScreen: boolean
  action: string
  target: string | null
  basisVersion?: number | null
}

/**
 * 활성 바인딩 한 줄(registry `/diag/bindings`). 명칭 상태는 registry 의 5값이다. 사람의 기록(`siteNamesRegistered*`)과
 * 기체의 답(`siteNamesReported*`)을 따로 싣는다(P2·S1d 스펙 §9).
 */
export interface Binding {
  robotId: string
  vendor: string
  model: string
  profileRevisionId: number
  revision: number
  adapterName: string
  adapterVersion: string
  conformanceStatus: string
  active: boolean
  siteNames: string
  siteNameKeys: string[]
  adapterVersionId: number
  boundBy: string
  boundAt: string
  siteNamesRegisteredBy: string | null
  siteNamesRegisteredAt: string | null
  siteNamesReportedAt: string | null
  siteNamesCount: number | null
  siteNamesUnsupported: boolean | null
}

export type CommissioningState = 'COMPLETE' | 'INCOMPLETE' | 'RETIRED'

/** 시운전 판정(결정 6). 세 조건을 따로 들고 있어 «시운전» 카드가 체크 목록으로 보인다. */
export interface Commissioning {
  state: CommissioningState
  ledgerConfirmed: boolean
  bound: boolean
  siteNamesReady: boolean
}

/** 소프트웨어 대조(`MATCH`·`MISMATCH`·`UNREPORTED`). 시운전을 막지 않고 보이기만 한다. */
export interface Software {
  robotId: string
  declared: string | null
  reported: string | null
  verdict: string
}

/**
 * 기체 한 대. 원장 상태는 registry 값 그대로, 연결과 막힘은 운영 서비스가 계산한다(스펙 §7.3).
 * 바인딩·시운전·소프트웨어 대조는 S1d 의 칸이다(P2·S1d 스펙 §8.1). 운영 서비스가 싣지 않으면 없다.
 */
export interface RobotView {
  robot: Robot
  connection: Connection
  blockers: Finding[]
  binding?: Binding | null
  commissioning?: Commissioning | null
  software?: Software | null
}

/**
 * 운영 서비스의 `GET /api/robots`. `robots` 가 null 이면 모름, 빈 배열이면 없음이다(스펙 §9).
 * `settingsVersion`·`connectionThresholdSeconds` 는 연결 칸과 막힘을 판정한 현장 설정 버전과 그 기준 시간이다(S2 스펙 §6.4).
 */
export interface RobotListView {
  registry: RegistryState
  checkedAt: string
  robots: RobotView[] | null
  robotsAsOf: string | null
  settingsVersion?: number | null
  connectionThresholdSeconds?: number | null
}

/** 미들웨어 시간값 넷(S3c JSON 계약 공통 규칙). 초 단위 정수다. */
export interface SiteTimingValues {
  evidenceBeforeSeconds: number
  evidenceAfterSeconds: number
  inDoubtGraceSeconds: number
  stallWindowSeconds: number
}

/** 현장 설정의 값 칸 다섯(S3c JSON 계약 §4). 화면은 PUT 에 늘 다섯을 다 싣는다. */
export interface SiteSettingValues extends SiteTimingValues {
  connectionThresholdSeconds: number
}

/** 현장 설정 버전 한 행(S3c JSON 계약 §3). `mode` 는 운영 서비스의 열거 값(`ENGINEER`)이다. */
export interface SiteSettingsRecord extends SiteSettingValues {
  version: number
  mode: string
  user: string
  reason: string
  recordedAt: string
}

/** 허용 범위(초, 양 끝 포함). 평평한 한 객체다(S3c JSON 계약 §3·§5). */
export interface SiteSettingsRange {
  minConnectionThresholdSeconds: number
  maxConnectionThresholdSeconds: number
  minEvidenceBeforeSeconds: number
  maxEvidenceBeforeSeconds: number
  minEvidenceAfterSeconds: number
  maxEvidenceAfterSeconds: number
  minInDoubtGraceSeconds: number
  maxInDoubtGraceSeconds: number
  minStallWindowSeconds: number
  maxStallWindowSeconds: number
}

/**
 * 실행 호스트의 현장 시간값 적용 상태(S3c JSON 계약 §7). 운영 서비스가 호스트 본문을 그대로 넘긴다. `applied` 가 null 이면
 * 미적용이고, `rejected` 는 범위 밖이라 적용하지 않은 버전과 이유다. 시각은 호스트 시계다.
 */
export interface HostTimings {
  applied: ({ version: number } & SiteTimingValues) | null
  appliedAt: string | null
  lastReadAt: string | null
  readError: string | null
  rejected: { version: number; reasons: string[] } | null
}

/**
 * 운영 서비스의 `GET /api/site-settings`(S3c JSON 계약 §3). `history` 는 최신부터다. `hostTimings` 는 호스트가 닿지 않으면
 * null 이다.
 */
export interface SiteSettingsView {
  current: SiteSettingsRecord
  range: SiteSettingsRange
  history: SiteSettingsRecord[]
  hostTimings: HostTimings | null
}

/** 어댑터 빌드 하나. `conformance` 는 registry 값 그대로다(S1 은 `UNTESTED` 만 본다). */
export interface AdapterBuild {
  adapterVersionId: number
  version: string
  contractSemver: string
  conformance: string
  registeredAt: string | null
  registeredBy: string | null
}

/** 어댑터 제품 하나와 그 빌드들. */
export interface Adapter {
  adapterId: number
  vendor: string
  name: string
  versions: AdapterBuild[]
}

/** 이 사이트에 등록된 어댑터 인스턴스. 빌드는 제품 이름(`vendor/name`)과 버전으로만 가리킨다. */
export interface AdapterInstance {
  instanceId: string
  siteId: string
  fleetEndpoint: string | null
  registeredAt: string | null
  registeredBy: string | null
  adapter: string
  version: string
  contractSemver: string
  conformance: string
  discoveredRobots: number
}

/** 운영 서비스의 `GET /api/adapters`. 목록이 null 이면 모름, 빈 배열이면 없음이다(스펙 §9). */
export interface AdapterListView {
  registry: RegistryState
  checkedAt: string
  adapters: Adapter[] | null
  instances: AdapterInstance[] | null
  asOf: string | null
}

/** 계약이 아는 스킬 종류. 카탈로그는 registry 가 기동 때 계약에서 채운다. */
export interface SkillType {
  name: string
  major: number
  introducedInSemver: string
  siteReferenceKeys: string[]
}

export interface Catalog {
  contractSemver: string
  skillTypes: SkillType[]
}

/** 스위트 하나의 최신 실행. `detail` 은 실행기가 낸 JSON 그대로다(`checks`·`failures`). */
export interface SuiteRun {
  result: string
  ranAt: string
  ranBy: string
  detail: { checks?: number; failures?: { check: string; expected: string; observed: string }[] } | null
}

export interface TestRequest {
  requestId: number
  requestedBy: string
  requestedAt: string
  claimedBy: string | null
  claimedAt: string | null
  claimExpiresAt: string | null
  completedAt: string | null
}

export interface Revision {
  profileRevisionId: number
  vendor: string
  model: string
  revision: number
  status: string
  reasons: string[]
  documentHash: string
  createdBy: string | null
  createdAt: string | null
  activatedBy: string | null
  activatedAt: string | null
  suites: Record<string, SuiteRun>
  latestTestRequest: TestRequest | null
}

/** 시험 요청 상태(P2·S1d 스펙 §9). 운영 서비스가 목록을 읽은 시각과 만료 시각으로 정한다. */
export type TestRequestState = 'NONE' | 'WAITING' | 'RUNNING' | 'EXPIRED' | 'DONE'

export interface RevisionView {
  revision: Revision
  testRequest: TestRequestState
}

/** 운영 서비스의 `GET /api/profiles`. 목록이 null 이면 모름이다(스펙 §9). */
export interface ProfileListView {
  registry: RegistryState
  checkedAt: string
  catalog: Catalog | null
  revisions: RevisionView[] | null
  asOf: string | null
}

export interface OperationRecord {
  requestId: string
  mode: 'ENGINEER' | 'OPERATOR'
  user: string
  target: string
  reason: string | null
  result: string
  recordedAt: string
}

/** 조작 한 번의 답. registry 의 판단은 여기에 있다(스펙 §7.4·§9). */
export interface OperationOutcome {
  requestId: string
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  rejection: Finding | null
  unauthorized: boolean
  registryStatus: number | null
}

/** 운영 서비스가 registry 에 보내기 전에 막은 요청의 답. */
export interface PreRejection {
  error: string
  detail: string
}

/**
 * 요청을 보낸 결과. 운영 서비스가 2xx 로 답했으면 `outcome`, 운영 서비스가 먼저 막았으면(400·403) `refused` 다.
 * 그 밖의 실패(연결 끊김, 프록시 오류, 운영 서비스 500)는 `unknown` 이다. 요청이 상대(registry, 실행 호스트)까지 갔는지
 * 모르므로 보내지 못했다고 단정하지 않는다(스펙 §9).
 */
export type Delivered<T> =
  | { kind: 'outcome'; outcome: T }
  | { kind: 'refused'; refusal: PreRejection }
  | { kind: 'unknown'; cause: string }

/** registry 쓰기 조작을 보낸 결과. registry 에 닿았으면 `outcome` 이다. */
export type Sent = Delivered<OperationOutcome>

/** 사용자 이름 규칙. 운영 서비스의 `Actor` 와 같다. 헤더는 ASCII 만 실을 수 있고 `/` 는 모드와 사용자를 가르는 자리다. */
export const USER_PATTERN = /^[A-Za-z0-9._-]{1,64}$/

/** 인증 없이 화면이 싣는 모드와 사용자(스펙 §7.1·§8). */
export interface Session {
  mode: Mode
  user: string
}

const actorHeaders = (session: Session) => ({ 'X-Ops-Mode': session.mode, 'X-Ops-User': session.user })

async function getJson<T>(path: string, session: Session): Promise<T> {
  const response = await fetch(path, { headers: actorHeaders(session) })
  if (!response.ok) throw new Error(`운영 서비스 응답 ${response.status}`)
  return (await response.json()) as T
}

async function send(method: string, path: string, session: Session, body?: unknown): Promise<Sent> {
  return deliver(path, {
    method,
    headers:
      body === undefined
        ? actorHeaders(session)
        : { ...actorHeaders(session), 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

/**
 * 프로파일 문서를 고른 파일의 글자 그대로 보낸다. 다시 직렬화하면 registry 가 매기는 문서 해시가 달라져, 같은 문서의
 * 재제출이 다른 문서로 보인다(P2·S1d 스펙 §8.2).
 */
function sendDocument(path: string, session: Session, text: string): Promise<Sent> {
  return deliver(path, {
    method: 'POST',
    headers: { ...actorHeaders(session), 'Content-Type': 'application/json' },
    body: text,
  })
}

/** 운영 서비스가 registry·실행 호스트에 보내기 전에 막는 상태 코드(스펙 §9). */
const preRejected = (status: number) => status === 400 || status === 403

async function deliver<T = OperationOutcome>(
  path: string,
  init: RequestInit,
  refused: (status: number) => boolean = preRejected,
): Promise<Delivered<T>> {
  try {
    const response = await fetch(path, init)
    if (response.ok) return { kind: 'outcome', outcome: (await response.json()) as T }
    if (refused(response.status)) {
      // 스프링이 직접 막은 400 의 본문에는 detail 이 없다. 그때도 사유 칸을 비우지 않는다.
      const refusal = (await response.json()) as Partial<PreRejection>
      const detail =
        typeof refusal.detail === 'string' && refusal.detail !== ''
          ? refusal.detail
          : `운영 서비스가 요청을 거부했습니다(HTTP ${response.status})`
      return { kind: 'refused', refusal: { error: refusal.error ?? '', detail } }
    }
    return { kind: 'unknown', cause: `운영 서비스 응답 ${response.status}` }
  } catch (error: unknown) {
    return { kind: 'unknown', cause: error instanceof Error ? error.message : String(error) }
  }
}

const retirementPath = (robotId: string) => `/api/robots/${encodeURIComponent(robotId)}/retirement`

export const fetchRobots = (session: Session) => getJson<RobotListView>('/api/robots', session)
export const fetchSiteSettings = (session: Session) => getJson<SiteSettingsView>('/api/site-settings', session)
export const changeSiteSettings = (session: Session, baseVersion: number, values: SiteSettingValues, reason: string) =>
  send('PUT', '/api/site-settings', session, { baseVersion, ...values, reason })
export const fetchOperations = (session: Session) =>
  getJson<OperationRecord[]>('/api/operations', session)
export const declareRobot = (
  session: Session,
  robotId: string,
  serialNumber: string,
  displayName: string | null,
) => send('POST', '/api/robots', session, { robotId, serialNumber, displayName })
export const retireRobot = (session: Session, robotId: string, reason: string) =>
  send('POST', retirementPath(robotId), session, { reason })
export const reinstateRobot = (session: Session, robotId: string) =>
  send('DELETE', retirementPath(robotId), session)

export const fetchAdapters = (session: Session) => getJson<AdapterListView>('/api/adapters', session)
export const declareAdapter = (session: Session, vendor: string, name: string) =>
  send('POST', '/api/adapters', session, { vendor, name })
export const declareBuild = (session: Session, adapterId: number, version: string, contractSemver: string) =>
  send('POST', `/api/adapters/${adapterId}/versions`, session, { version, contractSemver })
export const registerInstance = (
  session: Session,
  instanceId: string,
  adapterVersionId: number,
  fleetEndpoint: string | null,
) => send('POST', '/api/adapter-instances', session, { instanceId, adapterVersionId, fleetEndpoint })

export const fetchProfiles = (session: Session) => getJson<ProfileListView>('/api/profiles', session)
export const submitRevision = (session: Session, document: string) =>
  sendDocument('/api/profile-revisions', session, document)
export const requestTest = (session: Session, profileRevisionId: number) =>
  send('POST', `/api/profile-revisions/${profileRevisionId}/test-requests`, session)
export const activateRevision = (session: Session, profileRevisionId: number) =>
  send('POST', `/api/profile-revisions/${profileRevisionId}/activation`, session)
export const bindRobot = (session: Session, robotId: string, adapterVersionId: number, profileRevisionId: number) =>
  send('POST', `/api/robots/${encodeURIComponent(robotId)}/binding`, session, { adapterVersionId, profileRevisionId })
export const recordSiteNames = (session: Session, robotId: string) =>
  send('POST', `/api/robots/${encodeURIComponent(robotId)}/site-names`, session)

/** 작업 지시 폼이 내는 임무(S3a 스펙 §9.1). DeliverContainer 는 플릿 포트 구현이 없어 내지 않는다. */
export type WorkMasterId = 'InspectAsset' | 'PrepareSequencedRack'

/** InspectAsset 의 점검 대상 하나. `location` 은 기체가 아는 명칭이어야 하나 화면이 검사하지 않는다. */
export interface InspectionTarget {
  id: string
  location: string
}

/**
 * 작업 지시 폼 초안(S3a JSON 계약 §9.1). 판정과 제출이 같은 본문을 쓴다. 작업 지시 본문(작업 지시 id, 요구 근거 등급, 장비 요구)은
 * 운영 서비스가 만든다.
 */
export type JobOrderForm =
  | { workMasterId: 'InspectAsset'; targets: InspectionTarget[] }
  | { workMasterId: 'PrepareSequencedRack'; slots: string[]; material: string; presentation: string }

export type SkillFit = 'FIT' | 'MISSING' | 'UNKNOWN'

/** 실행 호스트의 기체 판정(S3a JSON 계약 §2.2). `UNKNOWN` 은 호스트가 기체 케이퍼빌리티를 못 물어본 것이고 모름이다. */
export interface HostEligibility {
  robotId: string
  skillFit: SkillFit
  requiredSkills?: string[] | null
  availableSkills?: string[] | null
  missingSkills: string[]
  runningExecutionId: string | null
  passed: boolean
  reasons: string[]
  /** 관문 확인용으로만 넘기는 기체(남은 이유가 잔여 파지 표시뿐). 칸이 없던 본문은 undefined 다. */
  gateTrial?: boolean
}

/**
 * 기체 한 대의 배정 가능 판정(S3a JSON 계약 §9.2). 시운전·연결은 운영 서비스가, 스킬 적합·도는 실행은 실행 호스트가 판정한다.
 * null 은 모름이고, 모름이 하나라도 있으면 배정 가능이 아니다.
 */
export interface RobotEligibility {
  robotId: string
  commissioning: CommissioningState | null
  connection: Connection | null
  settingsVersion: number | null
  host: HostEligibility | null
  eligible: boolean
  reasons: string[]
  /** 배정 가능이지만 관문 확인용이다 — 일반 배정은 서지 않고 조치 제안만 얻는다. 칸이 없던 본문은 undefined 다. */
  gateTrial?: boolean
}

export type HostState = 'OK' | 'HOST_SILENT'

/** 운영 서비스의 `POST /api/job-orders/eligibility`. `robots` 가 null 이면 기체 목록을 한 번도 못 읽은 모름이다. */
export interface EligibilityView {
  checkedAt: string
  registry: RegistryState
  robotsAsOf: string | null
  host: HostState
  robots: RobotEligibility[] | null
}

export type HostSubmitResult = 'ACCEPTED' | 'IDEMPOTENT' | 'REJECTED' | 'UNASSIGNED'

/** 출발 결품의 자리(S3a JSON 계약 §4). `observed` 가 null 이면 빈 자리이고 아니면 거기 있던 다른 식별 정보다. */
export interface SourceMissing { material: string | null; source: string; observed: string | null }

/**
 * 출발 미관측의 자리(S3a JSON 계약 §4). 관측을 요구한 단위의 출발 자리를 설비가 확인해 주지 않았다. 결품이 아니다.
 * `occupied` 가 null 이면 설비가 말이 없었고 true 면 «있다» 고만 했다.
 */
export interface SourceUnobserved { material: string | null; source: string; occupied: boolean | null }

/**
 * 미배정의 기체별 사유. 가운데 두 칸은 REJECTED 의 같은 이름 칸과 모양이 같고, 그 기체가 출발 결품으로 떨어졌을 때만 값이 있다.
 * `sourceUnobserved` 는 출발 미관측으로 떨어진 기체에만 있다.
 */
export interface UnassignedRefusal {
  robotId: string; reason: string; alternativeLocations?: string[] | null; sourceMissing?: SourceMissing | null; sourceUnobserved?: SourceUnobserved | null
  /** 이 기체의 조치 탐색 응답. 칸이 없던 본문은 undefined 다. */
  remedy?: { kind: 'FOUND'; steps: { skillType: string }[] } | { kind: 'NONE'; cause: string } | null
  remedyWithheld?: boolean
  /** 관문 확인용으로 넘긴 표시 기체면 그때 열려 있던 잔여 파지 표시 id. */
  residualHoldMarkers?: number[]
}

/**
 * 실행 호스트의 제출 결과(S3a JSON 계약 §4). `refusals` 는 미배정일 때 기체별 게이트 사유, `excluded` 는 호스트가 판정에서 뺀 기체다.
 * 기체별 사유의 `alternativeLocations`·`sourceMissing` 은 그 기체가 출발 결품으로 떨어졌을 때만 있다. 빈 목록(든 자리 없음)과 null(답 없음)을 접지 않는다.
 */
export interface HostSubmitOutcome {
  result: HostSubmitResult
  executionId: string | null
  robotId: string | null
  rejectionReason: string | null
  refusals: UnassignedRefusal[]
  excluded: HostEligibility[]
  /** 출발 미관측 REJECTED 일 때만 있다. 칸이 없던 호스트 본문은 undefined 다. */
  sourceUnobserved?: SourceUnobserved | null
  /** 이미 끝나 이빅션한 작업 지시라 받지 않은 REJECTED 면 참이다. 칸이 없던 호스트 본문은 undefined 다. */
  forgottenJobOrder?: boolean
}

/**
 * 작업 지시 제출의 200 응답(S3a JSON 계약 §9.3). 기존 [OperationOutcome] 과 모양이 달라 따로 읽는다(S3a 스펙 §8).
 * `outcome` 은 실행 호스트의 응답이고, 호스트가 안 닿았으면 null 이다.
 */
export interface JobOrderOutcome {
  requestId: string
  jobOrderId: string
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  outcome: HostSubmitOutcome | null
}

/** 실행의 단위 하나(S3a JSON 계약 §5). `reached` 는 그 단위가 얻은 근거 등급이다. */
export interface ExecutionUnit {
  unitId: string
  skillType: string
  state: string
  reached: string
}

/** 실행의 마지막 작업 응답(S3a JSON 계약 §5). 상위 시스템이 없어 실행 호스트의 아웃박스에 남는다. */
export interface JobResponse {
  jobResponseId: string
  version: number
  physicalState: string
  requiredEvidence: string
  reachedEvidence: string
  completedUnits: string[]
  unverifiedUnits: string[]
  incompleteUnits: Record<string, string>
  inDoubtUnits: string[]
  operatorRequired: boolean
  residualHold: string
  blockedBy: string[]
  connection: string
}

/**
 * 실행 하나(S3a JSON 계약 §5, S4b 계약 H1). `missionVersion` 이 null 이면 코드 정의 임무다. `restoredFrom` 은 이번 기동이
 * 다시 지은 실행의 바로 앞 인스턴스 실행이고, 새로 받은 실행이면 null 이다. 옛 호스트는 칸을 싣지 않는다.
 */
export interface Execution {
  executionId: string
  jobOrderId: string
  workMasterId: string
  missionVersion: number | null
  robotId: string
  physicalState: string
  units: ExecutionUnit[]
  jobResponse: JobResponse | null
  restoredFrom?: { instanceId: string; executionId: string } | null
  /** 이 인스턴스에서 그 실행에 건 운영자 취소(P0-b2). 없거나 옛 호스트면 null 이거나 칸이 없다. */
  cancel?: CancelRecord | null
  /**
   * 이 실행의 새 로봇 명령을 지금 막고 있는 작업 허가(ADR 52). 막힌 것이 없거나 현장이 허가 대응을 선언하지 않았으면 null 이거나 칸이 없다.
   * 운영자 보류(물리 상태 OPERATOR_HOLD)·결함 막음(작업 응답 blockedBy)과 다른 칸이다.
   */
  permitBlock?: PermitBlock | null
  /**
   * 지금 다음 단위를 막고 있는 기체 결함 전부(S3a JSON 계약 §5.9). 인시던트 상세 `blockedBy` 와 같은 결함 객체이고 없으면 빈 배열이다.
   * 작업 응답의 `blockedBy`(분류 이름)는 다음 작업 응답까지 옛 값일 수 있지만 이것은 지금 값이다. 옛 호스트는 칸을 싣지 않는다.
   */
  blockedFaults?: FaultDetail[]
  /** 그 작업 지시에 건 운영자 결함 감수(S3a JSON 계약 §5.9). 적은 순서이고 재기동 전 인스턴스의 것도 싣는다. 옛 호스트는 칸을 싣지 않는다. */
  releases?: ReleaseRecord[]
}

/** 운영자 결함 감수 한 건(S3a JSON 계약 §5.9). `faults` 는 감수할 때의 `blockedFaults`, `recordedAt` 은 실행 호스트가 적은 시각이다. */
export interface ReleaseRecord {
  requestId: string | null
  approverId: string
  reason: string
  faults: FaultDetail[]
  recordedAt: string
}

/** 지금 결함으로 막혀 있는 실행. 실행 호스트가 본 결함과 다시 대조하므로 화면에서는 고르기·표시 편의다. */
export const faultBlocked = (execution: Execution) => (execution.blockedFaults?.length ?? 0) > 0

/** 작업 허가 값(ADR 52). UNKNOWN 은 막음으로 읽는다. */
export type WorkPermitValue = 'PERMITTED' | 'INHIBITED' | 'UNKNOWN'

/** 기체 하나에 대한 판단 하나. source·observedAt 은 실행 호스트가 정한 까닭(NOT_COVERED·PORT_FAILED)이면 null 이다. */
export interface PermitReason {
  source: string | null
  cause: string
  subject: string | null
  resumeBasis: string
  observedAt: string | null
}

/** 기체 하나의 허가와 그 까닭. */
export interface RobotPermit {
  robotId: string
  permit: WorkPermitValue
  reasons: PermitReason[]
}

/** 지금 막혀 있는 새 로봇 명령 하나. withheld 는 START·REVISION_UPDATE·IN_DOUBT_LOOKUP, since 는 처음 막힌 시각이다. */
export interface PermitBlock {
  unitId: string
  withheld: string
  robots: RobotPermit[]
  since: string
}

/** 실행에 건 운영자 취소(P0-b2). `physicalState` 는 취소를 건 때의 물리 상태, `recordedAt` 은 실행 호스트가 적은 시각이다. */
export interface CancelRecord {
  approverId: string
  approverKind: string
  reason: string
  physicalState: string
  recordedAt: string
}

/** 다시 짓기 결과 셋(S4b 스펙 T3). */
export type RestoreResult = 'RESTORED' | 'DEFERRED' | 'GAVE_UP'

/** 복원 못 한 실행의 종결 근거(결함 14). 기체 스냅숏이 정지를 보였거나(SNAPSHOT) 운영자가 로봇이 멈춘 것을 증언했다(ATTESTED). */
export type CloseBasis = 'SNAPSHOT' | 'ATTESTED'

/**
 * 복원 못 한 실행의 운영자 종결 한 건(결함 14). 실행 호스트가 일지에 적은 것이다. `closedInstanceId`·`closedExecutionId` 가 종결한 실행이고
 * `restoreResult`·`restoreReason` 이 종결 전의 복원 결과다. `responseId` 는 상위로 낸 UNVERIFIED 종결 응답의 id 다.
 */
export interface CloseRecord {
  requestId: string
  approverId: string
  reason: string
  basis: CloseBasis
  attestedStopped: boolean
  closedInstanceId: string
  closedExecutionId: string
  restoreResult: 'GAVE_UP' | 'DEFERRED'
  restoreReason: string
  responseId: string
  recordedAt: string
}

/** 복원 보고의 행 하나(S4b 계약 H1, 7칸 + 결함 14 의 `close`). `executionId` 는 RESTORED 일 때만, `reason` 은 그 밖일 때만 있다. */
export interface RestoreRow {
  jobOrderId: string
  robotId: string
  previousInstanceId: string
  previousExecutionId: string
  result: RestoreResult
  executionId: string | null
  reason: string | null
  /** 이번 기동 뒤 운영자가 이 행을 종결했으면 그 종결. `result` 는 종결 전의 복원 결과 그대로다. 칸이 없던 호스트 본문은 undefined 다. */
  close?: CloseRecord | null
}

/** 복원 못 한 실행 행의 결과(결함 14). CLOSED 는 정지 증언으로 종결했으나 그 기체의 스냅숏이 아직 동의하지 않은 행이다. */
export type UnrestoredResult = 'GAVE_UP' | 'DEFERRED' | 'CLOSED'

/**
 * 복원 못 한 실행 한 행(결함 14, 실행 목록의 `unrestored`). 이번 기동의 복원 보고와 달리 앞선 기동에서 포기한 행도 든다. `previousInstanceId`·
 * `previousExecutionId` 가 종결할 실행이고 종결 요청에 그대로 싣는다. `closable` 이 거짓이면 `notClosableReason` 이 까닭이다(살아 있는
 * 다른 인스턴스가 든 행 등). `robotExcluded` 는 이 행이 지금 그 기체를 배정에서 빼고 있다는 뜻이다.
 */
export interface UnrestoredRow {
  jobOrderId: string
  robotId: string
  previousInstanceId: string
  previousExecutionId: string
  result: UnrestoredResult
  reason: string | null
  since: string
  closable: boolean
  notClosableReason: string | null
  close: CloseRecord | null
  robotExcluded: boolean
}

/**
 * 운영 서비스의 `GET /api/executions`(실행 호스트 본문 그대로). `instanceId` 가 바뀌면 호스트가 재기동한 것이다. `restore` 는
 * 이번 기동의 복원 보고다(S4b 계약 H1). 다시 지을 것이 없었으면 `rows` 가 비어 있다.
 */
export interface ExecutionsView {
  journalBlockedExecutionIds?: string[]
  instanceId: string
  pumpedAt: string | null
  executions: Execution[]
  restore?: { at: string; rows: RestoreRow[] } | null
}

/**
 * 정착 이력 한 줄(HA 설계 기록 §10). 실행 호스트가 정착을 적을 때 함께 적은 실행 본문이다. `settleSeq` 가 정착 차례이고 커서다.
 * `instanceId` 는 정착을 적은 인스턴스다(재기동 전의 것일 수 있다). `snapshot` 이 거짓이면 이력 기록 전에 정착한 실행이라 단위·작업 응답이 없다.
 * 실행 호스트는 정착할 때마다 한 줄을 적으므로 다시 돈 작업 지시는 줄이 여럿이고, `superseded` 가 참이면 그 뒤에 다시 정착한 줄이다
 * (칸이 없던 호스트 본문은 undefined 다).
 */
export interface SettledExecution {
  settleSeq: number
  instanceId: string
  settledAt: string
  snapshot: boolean
  execution: Execution
  superseded?: boolean
}

/** 정착 이력 한 페이지(`GET /api/executions?before=`). `nextBefore` 가 null 이면 더 오래된 이력이 없다. */
export interface SettledPage {
  settled: SettledExecution[]
  nextBefore: number | null
  olderTotal: number
}

/**
 * 재작업할 수 있는 건너뛴 대상 하나(실행 호스트가 계산한다). 순회가 종결한 뒤 선행 이동이 실패해 점검을 내지 않은 단위다. `unitId` 가
 * 건너뛴 점검의 단위 id이고 `REWORK` 판단에 그대로 싣는다. `physicalState` 는 `PARTIAL` 이나 `FAILED` 다. `retainedUntil` 은 `FAILED`
 * 일 때만 있고, 이 시각(호스트 시계) 뒤에는 실행 호스트가 그 실행을 이빅션해 재작업할 수 없다. `PARTIAL` 은 null 이다.
 */
export interface ReworkableTarget {
  executionId: string
  jobOrderId: string
  robotId: string
  unitId: string
  physicalState: string
  retainedUntil: string | null
}

/**
 * 화면의 실행 목록(`GET /api/executions?recent=`, HA 설계 기록 §10). `executions` 는 지금 인스턴스의 남은 실행(활성이거나 정착 기록 전)이고,
 * `settled` 는 최근 종결 이력이다(최근부터). `reworkableTargets` 는 재작업할 수 있는 건너뛴 대상이고 종결 기록을 적고 아직 호스트 메모리에
 * 있는 `FAILED` 실행의 것도 든다(칸이 없던 호스트 본문은 undefined 다).
 */
export interface ExecutionPage extends ExecutionsView, SettledPage {
  reworkableTargets?: ReworkableTarget[]
  /** 복원 못 한 실행(결함 14). 칸이 없던 호스트 본문은 undefined 다. */
  unrestored?: UnrestoredRow[]
}

/** 운영 개요의 실행 수(`GET /api/executions/summary`). `states` 는 남은 실행의 물리 상태별 수다. */
export interface ExecutionSummary {
  instanceId: string
  pumpedAt: string | null
  live: number
  states: Record<string, number>
  journalBlockedExecutionIds: string[]
}

/** 셀 대역의 자리 하나(S3a JSON 계약 §1). 제시 자리의 `observedAt` 은 늘 null 이다. */
export interface CellPlace {
  id: string
  occupied: boolean
  material: string | null
  observedAt: string | null
}

export type SignalKind = 'BOOLEAN' | 'TEXT'

/**
 * 셀 대역의 이름 있는 신호 하나(S3b JSON 계약 §1). 값은 종류와 상관없이 늘 문자열이다(`"true"`). 안전 신호는 현장이 쓰기를
 * 거부한다(ADR 32). `observedAt` 이 null 이면 현장이 시각을 주지 않은 처음 값이다.
 */
export interface CellSignal {
  name: string
  location: string | null
  kind: SignalKind
  safety: boolean
  value: string
  observedAt: string | null
}

/**
 * 운영 서비스의 `GET /api/cell`. `cell` 이 null 이면 실행 호스트가 셀 대역을 못 읽은 모름이다. `signals` 가 null 이면 셀 대역이
 * 신호 목록을 싣지 않은 것이고 이것도 모름이다(S3b JSON 계약 §6).
 */
export interface CellView {
  cell: { presentations: CellPlace[]; slots: CellPlace[]; signals: CellSignal[] | null } | null
}

/**
 * 실행 호스트를 거치는 읽기. 운영 서비스가 호스트에 닿지 못하면 503 과 `{error, detail}` 이므로, 그 detail 을 오류 문구로 쓴다.
 * 기존 다섯 읽기와 따로 실패한다(S3a 스펙 §9.3).
 */
async function getHostJson<T>(path: string, session: Session): Promise<T> {
  const response = await fetch(path, { headers: actorHeaders(session) })
  if (response.ok) return (await response.json()) as T
  const body = (await response.json().catch(() => null)) as Partial<PreRejection> | null
  throw new Error(
    typeof body?.detail === 'string' && body.detail !== '' ? body.detail : `운영 서비스 응답 ${response.status}`,
  )
}

const postJobOrderForm = <T>(path: string, session: Session, form: JobOrderForm) =>
  deliver<T>(path, {
    method: 'POST',
    headers: { ...actorHeaders(session), 'Content-Type': 'application/json' },
    body: JSON.stringify(form),
  })

/** 운영 화면이 주기마다 받는 최근 정착 이력 수와 이력 한 페이지의 크기(HA 설계 기록 §10). */
export const RECENT_EXECUTIONS = 20
export const EXECUTION_HISTORY_PAGE = 20
export const EXECUTIONS_RECENT_PATH = `/api/executions?recent=${RECENT_EXECUTIONS}`
export const EXECUTION_SUMMARY_PATH = '/api/executions/summary'
export const settledBeforePath = (before: number) => `/api/executions?before=${before}&limit=${EXECUTION_HISTORY_PAGE}`

export const fetchExecutionPage = (session: Session) => getHostJson<ExecutionPage>(EXECUTIONS_RECENT_PATH, session)
export const fetchSettledBefore = (session: Session, before: number) => getHostJson<SettledPage>(settledBeforePath(before), session)
export const fetchExecutionSummary = (session: Session) => getHostJson<ExecutionSummary>(EXECUTION_SUMMARY_PATH, session)
export const fetchCell = (session: Session) => getHostJson<CellView>('/api/cell', session)
export const checkEligibility = (session: Session, form: JobOrderForm) =>
  postJobOrderForm<EligibilityView>('/api/job-orders/eligibility', session, form)
export const submitJobOrder = (session: Session, form: JobOrderForm) =>
  postJobOrderForm<JobOrderOutcome>('/api/job-orders', session, form)

export interface ManualReceipt {
  receiptId: string; sourceSystemId: string; externalOrderId: string; jobOrderId: string;
  state: string; detail: string | null; submission: JobOrderOutcome | null;
}
export interface ManualReceiptAccepted { receipt: ManualReceipt; duplicate: boolean }
export async function submitManualJobOrder(session: Session, form: JobOrderForm, requestId: string): Promise<Delivered<ManualReceiptAccepted>> {
  const sent = await deliver<ManualReceiptAccepted>('/api/work-requests/manual', {
    method: 'POST', headers: { ...actorHeaders(session), 'Content-Type': 'application/json' },
    body: JSON.stringify({ requestId, form }),
  })
  if(sent.kind === 'outcome' && (typeof sent.outcome?.receipt?.receiptId !== 'string' || typeof sent.outcome?.receipt?.jobOrderId !== 'string' || typeof sent.outcome?.duplicate !== 'boolean'))
    return { kind: 'unknown', cause: '작업 요청 응답 형식을 확인할 수 없습니다' }
  return sent
}
/** Reuse the existing execution notice without confusing a durable receipt with physical completion. */
export function manualJobOutcome(receipt: ManualReceipt): JobOrderOutcome {
  if(receipt.submission) return receipt.submission
  const rejected = receipt.state === 'REJECTED' || receipt.state === 'UNASSIGNED'
  return { requestId: receipt.receiptId, jobOrderId: receipt.jobOrderId, result: rejected ? 'REJECTED' : 'NO_RESPONSE', confirmation: null,
    outcome: rejected ? { result: receipt.state === 'UNASSIGNED' ? 'UNASSIGNED' : 'REJECTED', executionId: null, robotId: null, rejectionReason: receipt.detail, refusals: [], excluded: [] } : null }
}

/** 화면이 편집하는 임무. 작업 지시 폼과 실행 호스트가 두 임무로 고정이라 하나만 편집한다(S3b 스펙 T6). */
export const EDITABLE_WORK_MASTER = 'PrepareSequencedRack'

/** 모의 실행의 단위 하나(S3b JSON 계약 §4.5). `route` 가 `SIGNAL` 이면 설비 대기 단위다. */
export interface MockRunUnit {
  unitId: string
  route: 'ROBOT' | 'SIGNAL'
  skillType: string
  state: string
  reached: string
  failureClass: string | null
}

/** 모의 실행 실패의 하위 범주(S3b JSON 계약 §4.5). 통과면 null 이다. */
export type MockRunFailure = 'DEFINITION' | 'SUBMISSION_REJECTED' | 'NOT_SETTLED' | 'WALL_CLOCK_LIMIT' | 'EXECUTION_FAILED'

/**
 * 모의 실행 결과(S3b JSON 계약 §4.5). 가상 기체 하나와 이상적 현장으로 표본 작업 지시 하나를 끝까지 돌린 것이다. 실행이 서지
 * 않았으면(`DEFINITION`·`SUBMISSION_REJECTED`) `physicalState` 가 null 이고 `units` 가 비었다.
 */
export interface MockRunResult {
  passed: boolean
  failure: MockRunFailure | null
  detail: string | null
  robotId: string
  sample: { jobOrderId: string; requiredEvidence: string; slots: string[]; material: string; presentation: string; equipment?:{id:string;equipmentUse:string;properties:Record<string,string>}[] } | null
  physicalState: string | null
  units: MockRunUnit[]
  virtualElapsedSeconds: number
  wallElapsedMillis: number
}

/** 모의 실행 한 행(S3b JSON 계약 §3.3). 시각은 DB 시각이다. */
export interface MockRunView {
  mockRunId: number
  draftId: number
  passed: boolean
  result: MockRunResult
  requestId: string
  startedAt: string
  finishedAt: string
}

/** 초안 한 행(S3b JSON 계약 §3.3). `definition` 은 저장한 글자 그대로이고 읽을 수 없는 문서일 수도 있다. */
export interface DraftView {
  draftId: number
  workMasterId: string
  definition: string
  savedBy: string
  requestId: string
  savedAt: string
  lastMockRun: MockRunView | null
}

/** 임무 버전 한 행(S3b JSON 계약 §3.3). 번호는 WorkMaster 마다 1부터다. */
export interface VersionView {
  workMasterId: string
  version: number
  draftId: number
  definition: string
  activatedBy: string
  reason: string
  requestId: string
  activatedAt: string
}

/**
 * 운영 서비스의 `GET /api/missions/{workMasterId}`(S3b JSON 계약 §4.1). 활성 버전이 없으면 `source` 가 `CODE` 이고 정의 JSON 이
 * 없다(코드 정의). `versions` 는 높은 번호부터, `drafts` 는 최근 것부터다.
 */
export interface MissionOverview {
  workMasterId: string
  active: { version: number | null; source: 'CODE' | 'DATA' | 'UNPUBLISHED'; detail: VersionView | null }
  versions: VersionView[]
  drafts: DraftView[]
}

/** 시작용 정의 하나(S3b JSON 계약 §4.2). */
export interface MissionTemplate {
  id: string
  title: string
  definition: string
}

export interface MissionTemplates {
  workMasterId: string
  templates: MissionTemplate[]
}

/**
 * 판정 입력을 몰라 판정하지 않은 것(S3b JSON 계약 §3.2). 거부가 아니라 모름이다. `inputs` 는 `SIGNAL_SPEC`·`SITE_SKILLS`·
 * `SAMPLE_ORDER` 이고 `detail` 은 화면용 한국어다.
 */
export interface InputUnknown {
  inputs: string[]
  robots: string[]
  detail: string
}

/** 실행 호스트 판정의 공통 칸(S3b JSON 계약 §4.4~§4.6). 결과는 `result` 로 가린다. 거부 목록은 운영 서비스가 `findings` 로 옮긴다. */
interface HostJudgment<R extends string> {
  result: R
  draftId: number
  workMasterId: string
  checkedAt: string
  unknown: InputUnknown | null
}

export type HostValidation = HostJudgment<'PASSED' | 'REFUSED' | 'INPUT_UNKNOWN'>

export interface HostMockRun extends HostJudgment<'PASSED' | 'FAILED' | 'REFUSED' | 'INPUT_UNKNOWN'> {
  mockRun: MockRunView | null
}

export interface HostActivation extends HostJudgment<'ACTIVATED' | 'REFUSED' | 'MOCK_RUN_REQUIRED' | 'INPUT_UNKNOWN'> {
  version: number | null
  lastMockRun: MockRunView | null
  activated: VersionView | null
}

/** 초안 저장의 호스트 본문(S3b JSON 계약 §4.3). */
export interface DraftSaved {
  draft: DraftView
}

/**
 * 호스트가 4xx 로 막은 쓰기(S3b JSON 계약 §10.1). 200 본문에 실린다. 4xx 본문을 `{error, detail}` 로 읽지 못하면(스프링 기본
 * 415 등) 두 칸이 null 이다(S4a JSON 계약 §9.1).
 */
export interface HostRejection {
  status: number
  error: string | null
  detail: string | null
}

/**
 * 초안 저장·모의 실행·활성화의 200 응답(S3b JSON 계약 §10.4). `result` 는 조작 기록의 결과이고 호스트의 판단은 `outcome` 에
 * 있다. 거부 카드는 호스트 결과가 `REFUSED` 일 때만 `findings` 에 온다.
 */
export interface MissionOperationOutcome<T> {
  requestId: string
  workMasterId: string
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  outcome: T | null
  findings: Finding[]
  rejection: HostRejection | null
}

/** 검증의 200 응답(S3b JSON 계약 §10.5). 검증은 조작이 아니라 요청 id·결과·확인이 없다. */
export interface MissionValidationReply {
  workMasterId: string
  outcome: HostValidation
  findings: Finding[]
}

/** 신호 조작의 200 응답(S3b JSON 계약 §10.8). 현장의 거부(안전 신호, 틀린 값)는 `rejection` 에 온다. */
export interface SignalWriteOutcome {
  requestId: string
  name: string
  value: string
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  signal: CellSignal | null
  rejection: HostRejection | null
}

/**
 * 임무·신호 조작의 사전 거부(S3b JSON 계약 §10.1). 400·403 에 더해, 시운전 완료 기체를 몰라 실행 호스트를 부르지 않은 503
 * (`COMMISSIONED_ROBOTS_UNKNOWN`)과 검증이 넘기는 호스트 4xx·`HOST_SILENT` 도 `{error, detail}` 본문의 사전 거부다.
 */
const missionPreRejected = (status: number) => (status >= 400 && status < 500) || status === 503

const postMission = <T>(path: string, session: Session, body: unknown) =>
  deliver<T>(
    path,
    {
      method: 'POST',
      headers: { ...actorHeaders(session), 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    },
    missionPreRejected,
  )

const missionPath = (workMasterId: string) => `/api/missions/${encodeURIComponent(workMasterId)}`
const draftPath = (workMasterId: string, draftId: number) => `${missionPath(workMasterId)}/drafts/${draftId}`

export const fetchMission = (session: Session, workMasterId: string) =>
  getHostJson<MissionOverview>(missionPath(workMasterId), session)
export const fetchMissionTemplates = (session: Session, workMasterId: string) =>
  getHostJson<MissionTemplates>(`/api/missions/templates/${encodeURIComponent(workMasterId)}`, session)
/** 편집기의 글자 그대로 보낸다. 초안은 자유롭다. 읽을 수 없는 문서도 저장되고 검증이 거부한다(S3b 스펙 §9). */
export const saveMissionDraft = (session: Session, workMasterId: string, definition: string) =>
  postMission<MissionOperationOutcome<DraftSaved>>(`${missionPath(workMasterId)}/drafts`, session, { definition })
export const validateMissionDraft = (session: Session, workMasterId: string, draftId: number) =>
  postMission<MissionValidationReply>(`${draftPath(workMasterId, draftId)}/validate`, session, {})
export const mockRunMissionDraft = (session: Session, workMasterId: string, draftId: number) =>
  postMission<MissionOperationOutcome<HostMockRun>>(`${draftPath(workMasterId, draftId)}/mock-run`, session, {})
export const activateMissionDraft = (session: Session, workMasterId: string, draftId: number, reason: string) =>
  postMission<MissionOperationOutcome<HostActivation>>(`${draftPath(workMasterId, draftId)}/activate`, session, {
    reason,
  })
export const writeCellSignal = (session: Session, name: string, value: string) =>
  postMission<SignalWriteOutcome>(`/api/cell/signals/${encodeURIComponent(name)}`, session, { value })

/** 사람이 인시던트의 단위에 낸 판단(S4a JSON 계약 §3). `at` 은 호스트 시계, `wallClockAt` 은 실제 시각이다. */
export interface IncidentResolution {
  decision: HoldDecision
  at: string
  wallClockAt: string
  decidedBy: { id: string; kind: string }
}

/** 인시던트 줄의 결함 요약(S4a JSON 계약 §3). `errorHint` 는 빈 문자열일 수 있다. */
export interface FaultSummary {
  failureClass: string
  errorType: string
  errorHint: string
}

/**
 * 인시던트 한 줄(S4a JSON 계약 §3, 19칸). `unresolved` 는 봉인 때 한 번 정해지고 판단 뒤에도 그대로다. «미해결» 은
 * `unresolved` 이고 `resolution` 이 없을 때이고, «보류 중» 강조는 `held` 만 본다. `missionVersion` 이 null 이면 코드 정의다.
 */
export interface IncidentRow {
  incidentId: string
  executionId: string
  jobOrderId: string
  robotId: string
  unitId: string
  at: string
  failureClass: string | null
  route: string
  missionVersion: number | null
  siteSettingsVersion: number | null
  evidenceBeforeSeconds: number
  evidenceAfterSeconds: number
  inDoubtGraceSeconds: number | null
  stallWindowSeconds: number | null
  unresolved: boolean
  resolution: IncidentResolution | null
  fault: FaultSummary | null
  held: boolean
  confirmedWithoutEvidence: boolean
}

/** 이전 인스턴스의 인시던트 사본 한 줄(S4b 계약 H3, 20칸). 그 사본의 `instanceId` 를 앞에 둔다. 늘 보류가 아니다. */
export interface EarlierIncidentRow extends IncidentRow {
  instanceId: string
}

/**
 * 운영 서비스의 `GET /api/incidents`(S4a JSON 계약 §9.2, S4b 계약 H3). `incidents` 는 지금 인스턴스의 것이고 최신부터이며
 * `total` 은 자르기 전의 수다. `earlier` 는 재기동 앞 인스턴스들의 사본이고 최근에 적은 것부터다.
 */
export interface IncidentsView {
  instanceId: string
  total: number
  incidents: IncidentRow[]
  earlierTotal?: number
  earlier?: EarlierIncidentRow[]
}

/** 결함 원문(S4a JSON 계약 §4, 9칸). `vendorDetail`·`errorHint`·`activeUntilTime` 은 빈 문자열일 수 있다. */
export interface FaultDetail {
  failureClass: string
  errorType: string
  vendorDetail: string
  errorHint: string
  references: { key: string; value: string }[]
  canContinueCurrentTask: boolean
  canAcceptNewTask: boolean
  activeUntilKind: string
  activeUntilTime: string
}

/** 근거 윈도우 안의 관측 하나(S4a JSON 계약 §4). `local` 이면 미들웨어가 적은 관측이다. */
export interface EvidenceEvent {
  sequence: number
  occurredAt: string
  kind: string
  detail: string
  local: boolean
}

/** 인시던트의 의도 전체(S4a JSON 계약 §4, 17칸). 버전과 시간값은 목록 줄과 같은 뜻이다. */
export interface IncidentIntent {
  workMasterId: string
  orderVersion: number
  orderParameters: Record<string, string>
  materials: { materialDefinitionId: string; quantity: number }[]
  equipment: { id: string; equipmentUse: string; properties: Record<string, string> }[]
  capabilityMaxEvidence: string
  evidenceBeforeSeconds: number
  evidenceAfterSeconds: number
  skillType: string
  unitParameters: Record<string, string>
  source: string | null
  destination: string | null
  expectedIdentity: string | null
  missionVersion: number | null
  siteSettingsVersion: number | null
  inDoubtGraceSeconds: number | null
  stallWindowSeconds: number | null
}

/**
 * 운영 서비스의 `GET /api/incidents/{incidentId}`(S4a JSON 계약 §4, 29칸). `unitState` 는 그 단위의 지금 상태이고 나머지 근거
 * 칸은 봉인 때의 관측이다.
 */
export interface IncidentDetail {
  instanceId: string
  incidentId: string
  executionId: string
  jobOrderId: string
  robotId: string
  unitId: string
  at: string
  wallClockAt: string
  failureClass: string | null
  route: string
  unresolved: boolean
  resolution: IncidentResolution | null
  held: boolean
  confirmedWithoutEvidence: boolean
  unitState: string | null
  fault: FaultDetail | null
  blockedBy: FaultDetail[]
  requiredEvidence: string
  reachedEvidence: string
  verification: string
  step: { at: number; plan: string[]; completed: string[] }
  evidenceWindow: EvidenceEvent[]
  windowTruncated: boolean
  preconditionSubjects: string[]
  expectedHold: string | null
  observedHold: string
  effectMismatch: string | null
  linkBroken: boolean
  intent: IncidentIntent
}

/** 상세 읽기의 결과. 실행 호스트가 그 id 를 모르면(재기동으로 사라진 id 포함) `missing` 이다(S4a JSON 계약 §9.3). */
export type IncidentLookup = { kind: 'found'; detail: IncidentDetail } | { kind: 'missing'; detail: string }

export const fetchIncidents = (session: Session) => getHostJson<IncidentsView>('/api/incidents', session)

/**
 * 404 `INCIDENT_NOT_FOUND` 만 없음이다. 그 밖의 실패(503 `HOST_SILENT` 포함)는 못 읽음이라 던진다. 인스턴스를 늘 싣는다
 * (S4b 계약 H4). 재기동 뒤 `incident-N` 을 다시 세므로, 인스턴스 없이 읽으면 같은 id 의 다른 인시던트를 읽는다.
 */
export async function fetchIncident(session: Session, instanceId: string, incidentId: string): Promise<IncidentLookup> {
  const response = await fetch(
    `/api/incidents/${encodeURIComponent(incidentId)}?instanceId=${encodeURIComponent(instanceId)}`,
    { headers: actorHeaders(session) },
  )
  if (response.ok) return { kind: 'found', detail: (await response.json()) as IncidentDetail }
  const body = (await response.json().catch(() => null)) as Partial<PreRejection> | null
  const detail = typeof body?.detail === 'string' && body.detail !== '' ? body.detail : `운영 서비스 응답 ${response.status}`
  if (response.status === 404 && body?.error === 'INCIDENT_NOT_FOUND') return { kind: 'missing', detail }
  throw new Error(detail)
}

/**
 * 화면이 고르게 하는 장애 종류 둘과 연결 상태 셋(S4a JSON 계약 §1.1). 현장은 제어권 상실과 제어권 회복도 받지만 화면 양식에는
 * 두지 않는다. 그 밖의 값은 현장이 거부한다.
 */
export type FaultKind = 'SKILL_EXECUTION_FAILED' | 'CONNECTION'
export type ConnectionFaultState = 'OFFLINE' | 'CONNECTION_BROKEN' | 'ONLINE'

/** 현장이 받아들인 장애 주입의 본문(S4a JSON 계약 §1.2). */
export type SiteFaultAnswer =
  | { robotId: string; kind: 'SKILL_EXECUTION_FAILED'; taskId: string; taskState: string; raised: boolean }
  | { robotId: string; kind: 'CONNECTION'; state: string; changed: boolean }

/**
 * 장애 주입의 200 응답(S4a JSON 계약 §9.4). 재조회하지 않으므로 `confirmation` 은 늘 null 이다. 현장의 거부는 `rejection`
 * 이고, 현장 불통과 호스트 불통은 둘 다 `NO_RESPONSE` 다.
 */
export interface FaultInjectionOutcome {
  requestId: string
  robotId: string
  kind: string
  state: string | null
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: null
  fault: SiteFaultAnswer | null
  rejection: HostRejection | null
}

export type HoldDecision = 'CONFIRM_DONE' | 'REWORK'

/** 판단 결과 `NotHeld` 의 `detail` 이 이 값이면 그 실행을 호스트가 보존 기간이 지나 이빅션했다. 호스트 `MissionHost.EVICTED` 와 같은 글자다. */
export const HOLD_EVICTED = 'EVICTED'

/** 실행 호스트 판단의 200 본문(S4a JSON 계약 §5.2). */
export interface HoldResolveAnswer {
  result: string
  detail: string | null
  incidentId: string | null
  requestId: string | null
}

/**
 * 운영자 판단의 200 응답(S4a JSON 계약 §9.5). 화면은 `result` 가 아니라 `outcome`(picasso 이름 그대로)으로 가른다. `outcome`
 * 은 호스트가 200 으로 답했을 때만 있다.
 */
export interface HoldResolveOutcome {
  requestId: string
  executionId: string
  unitId: string
  decision: HoldDecision
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  outcome: 'Resolved' | 'NotHeld' | 'Refused' | null
  answer: HoldResolveAnswer | null
  rejection: HostRejection | null
}

/** 사전 거부(400 `REASON_REQUIRED`·403 `MODE_NOT_ALLOWED` 등)는 `refused` 다(S4a JSON 계약 §9.1). */
const postOps = <T>(path: string, session: Session, body: unknown) =>
  deliver<T>(path, {
    method: 'POST',
    headers: { ...actorHeaders(session), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

/** 연결 상태일 때만 `state` 를 싣는다. 사유는 앞뒤 공백을 뗀 값이다. */
export const injectFault = (
  session: Session,
  robotId: string,
  kind: FaultKind,
  state: ConnectionFaultState | null,
  reason: string,
) =>
  postOps<FaultInjectionOutcome>(
    '/api/faults',
    session,
    kind === 'CONNECTION' ? { robotId, kind, state, reason } : { robotId, kind, reason },
  )

/**
 * 승인자는 싣지 않는다. 운영 서비스가 `X-Ops-User` 로 정한다(S4a JSON 계약 §9.5, ADR 43). 인스턴스는 상세의 `instanceId`
 * 이고, 실행 호스트가 그 사이 재기동했으면 409 `INSTANCE_MISMATCH` 로 막는다(S4b 스펙 T8).
 */
export const resolveHold = (
  session: Session,
  target: { instanceId: string; executionId: string; unitId: string },
  decision: HoldDecision,
  reason: string,
) =>
  postOps<HoldResolveOutcome>(
    `/api/executions/${encodeURIComponent(target.executionId)}/units/${encodeURIComponent(target.unitId)}/resolve`,
    session,
    { decision, instanceId: target.instanceId, reason },
  )

/** 실행 호스트 취소 결과 이름(P0-b2). */
export type CancelResult = 'CANCELED' | 'NOT_CANCELABLE' | 'NOT_FOUND' | 'REFUSED'

/** 실행 호스트 취소의 200 본문(P0-b2). */
export interface CancelAnswer {
  result: CancelResult
  executionId: string
  physicalState: string | null
  detail: string | null
  requestId: string | null
}

/** 운영자 실행 취소의 200 응답(P0-b2). 화면은 `outcome`(실행 호스트 결과 이름 그대로)으로 가른다. */
export interface ExecutionCancelOutcome {
  requestId: string
  executionId: string
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  outcome: CancelResult | null
  answer: CancelAnswer | null
  rejection: HostRejection | null
}

/**
 * 승인자는 싣지 않는다. 운영 서비스가 `X-Ops-User` 로 정한다(ADR 43). 인스턴스는 실행 목록의 `instanceId` 이고, 실행 호스트가 그
 * 사이 재기동했으면 409 `INSTANCE_MISMATCH` 로 막는다.
 */
export const cancelExecution = (session: Session, target: { instanceId: string; executionId: string }, reason: string) =>
  postOps<ExecutionCancelOutcome>(`/api/executions/${encodeURIComponent(target.executionId)}/cancel`, session, {
    instanceId: target.instanceId,
    reason,
  })

/** 복원 못 한 실행의 운영자 종결 결과 이름(결함 14). CLOSED 밖의 일곱은 아무것도 적지 않는다. */
export type CloseResult =
  | 'CLOSED'
  | 'ALREADY_CLOSED'
  | 'NOT_CLOSABLE'
  | 'TASK_LIVE'
  | 'STOP_UNCONFIRMED'
  | 'TARGET_CHANGED'
  | 'NOT_FOUND'
  | 'REFUSED'

/** 실행 호스트 종결의 200 본문(결함 14). `detail` 은 CLOSED 가 아닐 때의 사유, `closure` 는 ALREADY_CLOSED 일 때 앞선 종결이다. */
export interface CloseAnswer {
  result: CloseResult
  jobOrderId: string
  basis: CloseBasis | null
  responseId: string | null
  detail: string | null
  requestId: string
  closure?: CloseRecord | null
}

/** 복원 못 한 실행의 운영자 종결 200 응답(결함 14). 화면은 `outcome`(실행 호스트 결과 이름 그대로)으로 가른다. */
export interface ExecutionCloseOutcome {
  requestId: string
  jobOrderId: string
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  outcome: CloseResult | null
  answer: CloseAnswer | null
  rejection: HostRejection | null
}

/**
 * 승인자는 싣지 않는다. 운영 서비스가 `X-Ops-User` 로 정한다(ADR 43). 종결할 실행은 실행 목록 `unrestored` 행의 `previousInstanceId`·
 * `previousExecutionId` 다(운영자가 본 값, 지금 일지와 다르면 실행 호스트가 `TARGET_CHANGED` 로 막는다). `attestedStopped` 는 운영자가
 * 로봇이 현장에서 멈춘 것을 확인했다는 증언이다.
 */
export const closeUnrestored = (
  session: Session,
  target: { jobOrderId: string; executionInstanceId: string; executionId: string },
  reason: string,
  attestedStopped: boolean,
) =>
  postOps<ExecutionCloseOutcome>(`/api/job-orders/${encodeURIComponent(target.jobOrderId)}/close`, session, {
    executionInstanceId: target.executionInstanceId,
    executionId: target.executionId,
    reason,
    attestedStopped,
  })

/** 실행 호스트 결함 감수 결과 이름(S3a JSON 계약 §5.10). */
export type ReleaseResult = 'RELEASED' | 'NOT_BLOCKED' | 'BLOCK_CHANGED' | 'NOT_FOUND' | 'REFUSED'

/** 실행 호스트 결함 감수의 200 본문(S3a JSON 계약 §5.10). `detail` 은 작업 허가 대기·정착·바뀐 결함 같은 사실 문장이다. */
export interface ReleaseAnswer {
  result: ReleaseResult
  executionId: string
  physicalState: string | null
  detail: string | null
  requestId: string | null
}

/** 운영자 결함 감수의 200 응답(S3a JSON 계약 §9.8). 화면은 `outcome`(실행 호스트 결과 이름 그대로)으로 가른다. */
export interface ExecutionReleaseOutcome {
  requestId: string
  executionId: string
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  outcome: ReleaseResult | null
  answer: ReleaseAnswer | null
  rejection: HostRejection | null
}

/**
 * 승인자는 싣지 않는다(ADR 43). `sawFaults` 는 운영자가 화면에서 본 막는 결함이다. 실행 호스트가 지금 막는 결함과 대조하고 다르면
 * `BLOCK_CHANGED` 로 감수하지 않는다. 인스턴스 규칙은 취소와 같다.
 */
export const releaseFaults = (
  session: Session,
  target: { instanceId: string; executionId: string },
  sawFaults: FaultDetail[],
  reason: string,
) =>
  postOps<ExecutionReleaseOutcome>(`/api/executions/${encodeURIComponent(target.executionId)}/release`, session, {
    instanceId: target.instanceId,
    reason,
    sawFaults: sawFaults.map((fault) => ({ errorType: fault.errorType, references: fault.references.map(({ key, value }) => ({ key, value })) })),
  })

/**
 * 미해소 잔여 파지 표시 하나(P0-b2). 실행이 정착할 때 로봇 단위의 마지막 파지 관측이 «든 채» 였다. 사람이 빈손을 확인할 때까지 그
 * 기체는 배정에서 빠진다. `objectRef` 는 관측이 이름을 싣지 않았으면 null 이다.
 */
export interface ResidualHold {
  markerId: number
  robotId: string
  jobOrderId: string
  executionId: string
  instanceId: string
  unitId: string
  objectRef: string | null
  recordedAt: string
}

/** 운영 서비스의 `GET /api/robots/residual-holds`(실행 호스트 본문 그대로). */
export interface ResidualHoldsView {
  holds: ResidualHold[]
}

export const fetchResidualHolds = (session: Session) => getHostJson<ResidualHoldsView>('/api/robots/residual-holds', session)

/** 기체 빈손 확인의 200 응답(P0-b2). 실행 호스트가 해소하면 `SUCCEEDED`, 없거나 이미 해소한 표시는 `REJECTED` 와 그 거부다. */
export interface RobotEmptyOutcome {
  requestId: string
  robotId: string
  markerId: number
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  answer: unknown
  rejection: HostRejection | null
}

/** 승인자는 싣지 않는다(ADR 43). 사유는 앞뒤 공백을 뗀 값이다. */
export const confirmRobotEmpty = (session: Session, hold: { robotId: string; markerId: number }, reason: string) =>
  postOps<RobotEmptyOutcome>(
    `/api/robots/${encodeURIComponent(hold.robotId)}/residual-holds/${hold.markerId}/clear`,
    session,
    { reason },
  )

/** 송신 기록 행의 처분(S4b 스펙 T6). 재기동 중복은 송신하지 않은 것이다. */
export type JobResponseDisposition = 'SENT' | 'RESTART_DUPLICATE'

/** 작업 응답 송신 기록 한 행(S4b 계약 H6, 17칸). 단위 배열은 정렬됐고 `incompleteUnits` 는 사유 없는 id 다. */
export interface JobResponseLogRow {
  instanceId: string
  jobResponseId: string
  jobOrderId: string
  executionId: string
  version: number
  physicalState: string
  requiredEvidence: string
  reachedEvidence: string
  completedUnits: string[]
  unverifiedUnits: string[]
  inDoubtUnits: string[]
  incompleteUnits: string[]
  operatorRequired: boolean
  residualHold: string
  blockedBy: string[]
  disposition: JobResponseDisposition
  recordedAt: string
}

/** 운영 서비스의 `GET /api/job-responses`(S4b 계약 H6). `responses` 는 최근에 적은 것부터이고 `total` 은 자르기 전의 수다. */
export interface JobResponsesView {
  instanceId: string
  total: number
  responses: JobResponseLogRow[]
}

/** 작업 지시를 고르지 않으면(null) 전체다. 건수는 실행 호스트 기본(50)이다. */
export const fetchJobResponses = (session: Session, jobOrderId: string | null) =>
  getHostJson<JobResponsesView>(
    jobOrderId === null ? '/api/job-responses' : `/api/job-responses?jobOrderId=${encodeURIComponent(jobOrderId)}`,
    session,
  )

/** 현재 Ops가 지원하는 코드 기반 업무 입력 계약. 외부 공개 계약과 구분한다. */
export interface JobContract {
  workMasterId: string
  name: string
  description: string
  editable: boolean
  requiredEvidence: string
  fields: { path: string; constraint: string; target: string }[]
  sample: unknown
  profile?: MissionProfile | null
}
export async function fetchJobContracts(session: Session): Promise<{ contracts: JobContract[] }> {
  const value = await getHostJson<{ contracts: JobContract[] }>('/api/job-contracts', session)
  if (!Array.isArray(value.contracts)) throw new Error('지원 임무 계약 응답을 읽을 수 없습니다.')
  return value
}
export async function previewJobOrder(session: Session, sample: unknown): Promise<unknown> {
  const response = await fetch('/api/job-orders/preview', { method: 'POST', headers: { ...actorHeaders(session), 'Content-Type': 'application/json' }, body: JSON.stringify(sample) })
  const body = await response.json()
  if (!response.ok) throw new Error(typeof body?.detail === 'string' ? body.detail : `입력 검증 실패 (HTTP ${response.status})`)
  return body
}

export interface MissionInterfaceSpec {
  interfaceId: string
  version: number
  name: string
  workMasterId: string
  mapping: Record<string, string>
  sample: unknown
}
export interface MissionInterfaceView extends MissionInterfaceSpec {
  requiredStationId?:string|null
  status: 'DRAFT' | 'ENABLED' | 'DISABLED'
  createdBy: string
  createdAt: string
  missionVersionPolicy: 'ACTIVE_AT_SUBMISSION' | 'PINNED_DEPLOYMENT'
  deployment?: InterfaceDeploymentView | null
}
export type InterfaceInvocation = ManualReceiptAccepted
export const fetchMissionInterfaces = (session: Session) => getHostJson<{ interfaces: MissionInterfaceView[] }>('/api/mission-interfaces', session)
export class InterfaceApiError extends Error {
  readonly status: number
  constructor(message: string, status: number) { super(message); this.status = status }
}
async function interfaceRequest<T>(session: Session, path: string, body: unknown): Promise<T> {
  const response = await fetch(path, { method: 'POST', headers: { ...actorHeaders(session), 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  const value = await response.json().catch(() => null)
  if (!response.ok) throw new InterfaceApiError(typeof value?.detail === 'string' ? value.detail : `호출 계약 요청 실패 (HTTP ${response.status})`, response.status)
  if (value === null) throw new Error('응답 내용을 확인할 수 없습니다. 목록과 실행 이력을 다시 조회하세요.')
  return value as T
}
export const createMissionInterface = (session: Session, spec: MissionInterfaceSpec) => interfaceRequest<MissionInterfaceView>(session, '/api/mission-interfaces', spec)
export const previewMissionInterface = (session: Session, spec: MissionInterfaceSpec) => interfaceRequest<unknown>(session, '/api/mission-interfaces/preview', spec)
const interfacePath = (row: MissionInterfaceView) => `/api/mission-interfaces/${encodeURIComponent(row.interfaceId)}/${row.version}`
export const changeMissionInterfaceState = (session: Session, row: MissionInterfaceView, status: 'ENABLED' | 'DISABLED', reason: string) => interfaceRequest<MissionInterfaceView>(session, `${interfacePath(row)}/state`, { expectedStatus: row.status, status, reason })
export interface InterfaceEligibility {
  interfaceId: string
  interfaceVersion: number
  jobOrder: { workMasterId: string; missionVersion: string; requiredEvidence: string; deployment: InterfaceDeploymentView; [key: string]: unknown }
  eligibility: EligibilityView
}
export const checkInterfaceEligibility = (session: Session, row: MissionInterfaceView, input: unknown) => interfaceRequest<InterfaceEligibility>(session, `${interfacePath(row)}/eligibility`, { input })
export const invokeMissionInterface = (session: Session, row: MissionInterfaceView, input: unknown, requestId: string, expectedDeploymentVersion?: number) => interfaceRequest<InterfaceInvocation>(session, `${interfacePath(row)}/invoke`, { requestId, input, ...(expectedDeploymentVersion === undefined ? {} : { expectedDeploymentVersion }) })

export interface InterfaceDeploymentView { interfaceId:string; interfaceVersion:number; mappingVersion:number; deploymentVersion:number; missionVersion:string; createdBy:string; reason:string; createdAt:string }
export const fetchInterfaceDeployments = (session:Session,row:MissionInterfaceView) => getHostJson<{deployments:InterfaceDeploymentView[]}>(`${interfacePath(row)}/deployments`,session)
export const deployMissionInterface = (session:Session,row:MissionInterfaceView,missionVersion:string,reason:string) => interfaceRequest<MissionInterfaceView>(session,`${interfacePath(row)}/deploy`,{missionVersion,reason,expectedDeploymentVersion:row.deployment?.deploymentVersion??0})

export interface MissionProfile {
 workMasterId:string; name:string; description:string; requiredEvidence:string
 inputSchema:{type:string;properties:Record<string,unknown>;required:string[];additionalProperties:boolean}
 orderMapping:unknown; sample:Record<string,unknown>; definition:Record<string,unknown>
}
export async function createMissionProfile(session:Session,profile:MissionProfile):Promise<MissionProfile> {
 const response=await fetch('/api/mission-profiles',{method:'POST',headers:{...actorHeaders(session),'Content-Type':'application/json'},body:JSON.stringify(profile)})
 const body=await response.json()
 if(!response.ok)throw new Error(body.detail??`임무 생성 HTTP ${response.status}`)
 if(body.workMasterId!==profile.workMasterId)throw new Error('생성된 임무 ID를 확인할 수 없습니다')
 return body
}

/** jobSignal·jobEchoSignal: 작업 번호 송신·반환 TEXT 신호(둘 다 있거나 둘 다 없음). quarantinedBy: 현장 확인 해제로 격리를 건 작업. */
export interface RfStation { stationId:string; name:string; readySignal:string; completeSignal:string; timeoutSeconds:number; owner:string|null; jobSignal?:string|null; jobEchoSignal?:string|null; quarantinedBy?:string|null; quarantinedAt?:string|null }
export interface StationRun { runId:string; stationId:string; payload:{receiptId?:string;workMasterId?:string;interfaceId?:string;interfaceVersion?:number;expectedDeploymentVersion?:number;input?:unknown}; actor:string; state:string; reason:string; receiptId:string|null; attempt:number; revision:number; createdAt:string; phaseAt:string; dispatchedAt:string|null }
export interface StationEvent { state:string;reason:string;actor:string;recordedAt:string;context:unknown }
export const fetchStations = (session:Session) => getHostJson<{stations:RfStation[];runs:StationRun[]}>('/api/stations',session)
export const fetchStationRun = (session:Session,id:string) => getHostJson<{run:StationRun;events:StationEvent[]}>(`/api/stations/runs/${encodeURIComponent(id)}`,session)
export const createStation = (session:Session,body:unknown) => interfaceRequest<RfStation[]>(session,'/api/stations',body)
export const enqueueStationRun = (session:Session,body:unknown) => interfaceRequest<StationRun>(session,'/api/stations/runs',body)
export const decideStationRun = (session:Session,run:StationRun,decision:string,reason:string) => interfaceRequest<StationRun>(session,`/api/stations/runs/${encodeURIComponent(run.runId)}/decision`,{expectedRevision:run.revision,decision,reason})

export interface MissionResourceRequirement { workMasterId:string;stationId:string;revision:number;createdBy:string;reason:string;createdAt:string }
export const fetchMissionResources=(session:Session)=>getHostJson<{requirements:MissionResourceRequirement[]}>('/api/mission-resources',session)
export const createMissionResource=(session:Session,body:{workMasterId:string;stationId:string;reason:string})=>interfaceRequest<MissionResourceRequirement>(session,'/api/mission-resources',body)
