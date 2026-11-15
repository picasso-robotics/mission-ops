import { vi } from 'vitest'
import type {
  AdapterListView,
  Binding,
  CellSignal,
  CellView,
  CloseRecord,
  CloseResult,
  DraftView,
  EarlierIncidentRow,
  EligibilityView,
  Execution,
  ExecutionCloseOutcome,
  ExecutionPage,
  ExecutionsView,
  ExecutionSummary,
  FaultInjectionOutcome,
  Finding,
  HoldResolveOutcome,
  HostTimings,
  IncidentDetail,
  IncidentRow,
  IncidentsView,
  JobContract,
  JobResponseLogRow,
  JobResponsesView,
  MissionInterfaceView,
  MissionOverview,
  MissionTemplates,
  MockRunView,
  OperationOutcome,
  PreRejection,
  ProfileListView,
  ResidualHoldsView,
  RestoreRow,
  Revision,
  SiteSettingsView,
  RevisionView,
  RobotListView,
  SettledExecution,
  SettledPage,
  RobotView,
  TestRequestState,
  UnrestoredRow,
  VersionView,
} from '../api'

/** 운영 서비스 대역이 받은 요청 한 건. */
export interface Call {
  method: string
  url: string
  headers: Record<string, string>
  body: unknown
}

/** 시험이 고칠 수 있는 대역의 상태. 조작마다 [answer] 를 돌려준다. */
export interface FakeOps {
  /** 실행 수 본문의 플릿 연결. 비우면 칸을 싣지 않는다(옛 호스트). */
  fleet?: ExecutionSummary['fleet']
  interfaces: MissionInterfaceView[]
  contracts: JobContract[]
  calls: Call[]
  view: RobotListView
  adapters: AdapterListView
  profiles: ProfileListView
  settings: SiteSettingsView
  /** 실행 호스트를 거치는 읽기(S3a). 운영 서비스가 호스트 본문을 그대로 넘기는 모양이다. */
  executions: ExecutionsView
  /**
   * 화면 실행 목록(`GET /api/executions?recent=`)의 정착 이력 쪽(HA 설계 기록 §10). 답은 [executions] 에 이것을 더한 것이다. 대역은 남은 실행을
   * 고르지 않는다(실행 호스트의 몫). 기본은 종결 이력이 없다. `reworkableTargets`·`unrestored` 도 호스트가 계산한 값을 그대로 싣는다(기본은 칸 없음).
   */
  recent: SettledPage & Pick<ExecutionPage, 'reworkableTargets' | 'unrestored'>
  /** 이전 실행 페이지(`GET /api/executions?before=`). 커서마다의 답이고 없으면 빈 페이지다. */
  older: Map<number, SettledPage>
  cell: CellView
  /** 임무 개요와 템플릿(S3b). 운영 서비스가 호스트 본문을 그대로 넘기는 모양이다. */
  mission: MissionOverview
  templates: MissionTemplates
  /**
   * 인시던트 목록과 상세(S4a·S4b). 상세의 `instanceId` 쿼리가 목록의 인스턴스면 [incidentDetails](id 키)에서, 다른
   * 인스턴스면 [earlierDetails](`<instanceId>/<incidentId>` 키)에서 찾는다. 없으면 404 `INCIDENT_NOT_FOUND` 다.
   */
  incidents: IncidentsView
  incidentDetails: Map<string, IncidentDetail>
  earlierDetails: Map<string, IncidentDetail>
  /** 송신 기록(S4b). `jobOrderId` 쿼리가 있으면 실행 호스트처럼 그 작업 지시 행만 돌려준다. */
  jobResponses: JobResponsesView
  /** 미해소 잔여 파지 표시(P0-b2). 운영 서비스가 호스트 본문을 그대로 넘기는 모양이다. 기본은 비어 있다. */
  residualHolds: ResidualHoldsView
  /** `POST /api/job-orders/eligibility` 의 답. 폼 거부(400)를 만들려면 [eligibilityStatus] 와 본문을 바꾼다. */
  eligibility: EligibilityView | PreRejection
  eligibilityStatus: number
  /**
   * 여기 든 경로(쿼리를 뺀 것)의 GET 은 503 이다. 운영 서비스의 일부 읽기만 실패하는 경우를 만든다. 실행 호스트를 거치는 경로는 운영
   * 서비스처럼 `HOST_SILENT` 본문을 싣는다. 배정 가능 판정은 POST 지만 읽기이므로 여기 들면 503 이다.
   */
  failing: Set<string>
  /** 조작마다의 응답. 여기 든 경로의 POST 는 이 응답이고, 없으면 [answer] 다. */
  answers: Map<string, { status: number; body: unknown }>
  answer: { status: number; body: unknown }
}

export const MISSION_PATH = '/api/missions/PrepareSequencedRack'
export const TEMPLATES_PATH = '/api/missions/templates/PrepareSequencedRack'

/** 실행 호스트를 거치는 경로. 503 일 때 운영 서비스가 `HOST_SILENT` 를 싣는다(S3a JSON 계약 §9.4, S3b JSON 계약 §10.3). */
const HOST_PATHS = new Set(['/api/executions', '/api/executions/summary', '/api/cell', '/api/incidents', '/api/job-responses', '/api/robots/residual-holds', MISSION_PATH, TEMPLATES_PATH])
const INCIDENT_PREFIX = '/api/incidents/'
const ELIGIBILITY_PATH = '/api/job-orders/eligibility'

/** 실행 목록. 기본은 실행이 없는 호스트 인스턴스 하나다. */
export function executionsView(partial: Partial<ExecutionsView> = {}): ExecutionsView {
  return { instanceId: 'mw-1', pumpedAt: 't1', executions: [], ...partial }
}

/** 실행 수(`GET /api/executions/summary`). 대역은 [executionsView] 의 실행을 모두 남은 실행으로 센다. */
export function executionSummary(view: ExecutionsView): ExecutionSummary {
  const states: Record<string, number> = {}
  view.executions.forEach((execution) => {
    states[execution.physicalState] = (states[execution.physicalState] ?? 0) + 1
  })
  return {
    instanceId: view.instanceId,
    pumpedAt: view.pumpedAt,
    live: view.executions.length,
    states,
    journalBlockedExecutionIds: view.journalBlockedExecutionIds ?? [],
  }
}

/** 정착 이력 한 줄. 기본은 지금 인스턴스(mw-1)에서 정착 본문과 함께 적힌 것이다. */
export function settledRow(execution: Execution, settleSeq: number, partial: Partial<SettledExecution> = {}): SettledExecution {
  return { settleSeq, instanceId: 'mw-1', settledAt: `s${settleSeq}`, snapshot: true, execution, ...partial }
}

/** 셀 대역 신호 셋. 고정 픽스처의 처음 값이다(S3b JSON 계약 §1). */
export function standardSignals(): CellSignal[] {
  return [
    { name: 'rack_present', location: 'RACK-204', kind: 'BOOLEAN', safety: false, value: 'false', observedAt: null },
    { name: 'guard_closed', location: null, kind: 'BOOLEAN', safety: true, value: 'true', observedAt: null },
    { name: 'lot_code', location: null, kind: 'TEXT', safety: false, value: 'LOT-0001', observedAt: null },
  ]
}

/** 셀 대역. 기본은 고정 픽스처(제시 자리 하나, 빈 슬롯 넷, 신호 셋)다(S3a JSON 계약 §1, S3b JSON 계약 §1). */
export function cellView(): CellView {
  const slot = (id: string) => ({ id, occupied: false, material: null, observedAt: null })
  return {
    cell: {
      presentations: [{ id: 'SEQ-IN-02.BIN-A', occupied: true, material: 'ENGINE-COVER-A', observedAt: null }],
      slots: ['RACK-204.S01', 'RACK-204.S02', 'RACK-204.S03', 'RACK-204.S04'].map(slot),
      signals: standardSignals(),
    },
  }
}

/** 템플릿 세 정의의 글자. 시험은 글자 그대로 오가는지만 본다. */
export const DATA_V1 = '{"schemaVersion": 1, "workMasterId": "PrepareSequencedRack", "nodes": ["place"]}'
export const ARRIVAL_WAIT =
  '{"schemaVersion": 1, "workMasterId": "PrepareSequencedRack", "nodes": ["rack-arrival", "place"]}'
export const ARRIVAL_WAIT_HOLD =
  '{"schemaVersion": 1, "workMasterId": "PrepareSequencedRack", "nodes": ["rack-arrival(hold)", "place"]}'

/** 템플릿 셋(S3b JSON 계약 §4.2, S4a JSON 계약 §6). */
export function missionTemplates(): MissionTemplates {
  return {
    workMasterId: 'PrepareSequencedRack',
    templates: [
      { id: 'DATA_V1', title: '코드 PrepareSequencedRack 을 옮긴 데이터 정의', definition: DATA_V1 },
      {
        id: 'ARRIVAL_WAIT',
        title: '랙 도착 대기(rack_present = true, 기한 120초, 기한 뒤 ABORTED)',
        definition: ARRIVAL_WAIT,
      },
      {
        id: 'ARRIVAL_WAIT_HOLD',
        title: '랙 도착 대기(rack_present = true, 기한 20초, 기한 뒤 운영자 보류)',
        definition: ARRIVAL_WAIT_HOLD,
      },
    ],
  }
}

/** 임무 개요. 기본은 버전도 초안도 없는 코드 정의다(S3b JSON 계약 §4.1). */
export function missionOverview(partial: Partial<MissionOverview> = {}): MissionOverview {
  return {
    workMasterId: 'PrepareSequencedRack',
    active: { version: null, source: 'CODE', detail: null },
    versions: [],
    drafts: [],
    ...partial,
  }
}

/** 버전 한 행. 기본은 데이터 정의를 버전 1 로 올린 것이다. */
export function versionRow(partial: Partial<VersionView> = {}): VersionView {
  return {
    workMasterId: 'PrepareSequencedRack',
    version: 1,
    draftId: 1,
    definition: DATA_V1,
    activatedBy: 'lee',
    reason: '데이터 정의로 전환',
    requestId: 'r-v1',
    activatedAt: 't1',
    ...partial,
  }
}

/** 모의 실행 한 행. 기본은 단위 둘이 E2 로 완료되어 통과한 것이다. */
export function mockRunRow(partial: Partial<MockRunView> = {}): MockRunView {
  return {
    mockRunId: 5,
    draftId: 7,
    passed: true,
    result: {
      passed: true,
      failure: null,
      detail: null,
      robotId: 'mock-01',
      sample: {
        jobOrderId: 'MOCK-7',
        requiredEvidence: 'E2',
        slots: ['RACK-204.S01', 'RACK-204.S02'],
        material: 'ENGINE-COVER-A',
        presentation: 'SEQ-IN-02.BIN-A',
      },
      physicalState: 'PHYSICALLY_DONE',
      units: [
        { unitId: 'rack-arrival', route: 'SIGNAL', skillType: 'equipment_wait', state: 'DONE', reached: 'E2', failureClass: null },
        { unitId: 'RACK-204.S01', route: 'ROBOT', skillType: 'pick_place', state: 'DONE', reached: 'E2', failureClass: null },
      ],
      virtualElapsedSeconds: 105,
      wallElapsedMillis: 812,
    },
    requestId: 'r-m5',
    startedAt: 't2',
    finishedAt: 't3',
    ...partial,
  }
}

/** 초안 한 행. 기본은 모의 실행이 없는 초안 7 이다. */
export function draftRow(partial: Partial<DraftView> = {}): DraftView {
  return {
    draftId: 7,
    workMasterId: 'PrepareSequencedRack',
    definition: ARRIVAL_WAIT,
    savedBy: 'local',
    requestId: 'r-d7',
    savedAt: 't1',
    lastMockRun: null,
    ...partial,
  }
}

/** 배정 가능 판정. 기본은 기체가 없는 사이트다. */
export function eligibilityView(partial: Partial<EligibilityView> = {}): EligibilityView {
  return { checkedAt: 't1', registry: 'OK', robotsAsOf: 't1', host: 'OK', robots: [], ...partial }
}

/** 어댑터 목록. 기본은 제품도 인스턴스도 없는 «없음» 이다. */
export function adapterView(partial: Partial<AdapterListView> = {}): AdapterListView {
  return { registry: 'OK', checkedAt: 't1', adapters: [], instances: [], asOf: 't1', ...partial }
}

/** 프로파일 목록. 기본은 카탈로그만 있고 개정판이 없는 «없음» 이다. */
export function profileView(partial: Partial<ProfileListView> = {}): ProfileListView {
  return {
    registry: 'OK',
    checkedAt: 't1',
    catalog: { contractSemver: '0.9.0', skillTypes: [] },
    revisions: [],
    asOf: 't1',
    ...partial,
  }
}

/**
 * 현장 설정. 기본은 마이그레이션이 넣는 버전 1 하나(90초와 picasso 기본 시간값, S3c JSON 계약 §1.1)이고, 실행 호스트가 그
 * 버전을 적용한 상태다.
 */
export function settingsView(partial: Partial<SiteSettingsView> = {}): SiteSettingsView {
  const first = {
    version: 1,
    connectionThresholdSeconds: 90,
    evidenceBeforeSeconds: 30,
    evidenceAfterSeconds: 15,
    inDoubtGraceSeconds: 60,
    stallWindowSeconds: 300,
    mode: 'ENGINEER',
    user: 'system',
    reason: 'S1 설정값 이전',
    recordedAt: 't0',
  }
  return {
    current: first,
    range: {
      minConnectionThresholdSeconds: 60,
      maxConnectionThresholdSeconds: 3600,
      minEvidenceBeforeSeconds: 5,
      maxEvidenceBeforeSeconds: 120,
      minEvidenceAfterSeconds: 5,
      maxEvidenceAfterSeconds: 120,
      minInDoubtGraceSeconds: 10,
      maxInDoubtGraceSeconds: 600,
      minStallWindowSeconds: 30,
      maxStallWindowSeconds: 3600,
    },
    history: [first],
    hostTimings: hostTimings(),
    ...partial,
  }
}

/** 실행 호스트의 적용 상태(S3c JSON 계약 §7). 기본은 버전 1 을 적용하고 읽기 실패와 적용하지 않은 버전이 없다. */
export function hostTimings(partial: Partial<HostTimings> = {}): HostTimings {
  return {
    applied: { version: 1, evidenceBeforeSeconds: 30, evidenceAfterSeconds: 15, inDoubtGraceSeconds: 60, stallWindowSeconds: 300 },
    appliedAt: 'h1',
    lastReadAt: 'h2',
    readError: null,
    rejected: null,
    ...partial,
  }
}

export function revisionView(partial: Partial<Revision> = {}, testRequest: TestRequestState = 'NONE'): RevisionView {
  return {
    revision: {
      profileRevisionId: 5,
      vendor: 'picasso-ref',
      model: 'humanoid-a',
      revision: 2,
      status: 'VALIDATED',
      reasons: [],
      documentHash: 'h',
      createdBy: 'engineer/kim',
      createdAt: 't0',
      activatedBy: null,
      activatedAt: null,
      suites: {},
      latestTestRequest: null,
      ...partial,
    },
    testRequest,
  }
}

/** 활성 바인딩 한 줄. 기본은 명칭이 확인된 바인딩이다. */
export function binding(partial: Partial<Binding> = {}): Binding {
  return {
    robotId: 'humanoid-01',
    vendor: 'picasso-ref',
    model: 'humanoid-a',
    profileRevisionId: 5,
    revision: 2,
    adapterName: 'acme/fleet',
    adapterVersion: '1.0.0',
    conformanceStatus: 'UNTESTED',
    active: true,
    siteNames: 'CONFIRMED',
    siteNameKeys: ['destination', 'location'],
    adapterVersionId: 10,
    boundBy: 'engineer/kim',
    boundAt: 't0',
    siteNamesRegisteredBy: 'engineer/kim',
    siteNamesRegisteredAt: 't0',
    siteNamesReportedAt: 't1',
    siteNamesCount: 2,
    siteNamesUnsupported: false,
    ...partial,
  }
}

export function robotView(robotId: string, status: string, blockers: Finding[] = []): RobotView {
  return {
    robot: {
      robotId,
      siteId: 'site-01',
      serialNumber: 'SN',
      displayName: null,
      status,
      lastReportedAt: status === 'CLAIMED' ? null : 't0',
      retiredAt: status === 'RETIRED' ? 't0' : null,
      retiredReason: status === 'RETIRED' ? '정비' : null,
      reportingAfterRetirement: false,
    },
    connection: status === 'CLAIMED' ? 'NO_REPORT' : 'FRESH',
    blockers,
  }
}

export function outcome(partial: Partial<OperationOutcome>): OperationOutcome {
  return {
    requestId: 'r-1',
    result: 'SUCCEEDED',
    confirmation: null,
    rejection: null,
    unauthorized: false,
    registryStatus: 200,
    ...partial,
  }
}

/** 인시던트 목록(S4a JSON 계약 §3). 기본은 인시던트가 없는 호스트 인스턴스 하나다. */
export function incidentsView(incidents: IncidentRow[] = []): IncidentsView {
  return { instanceId: 'mw-1', total: incidents.length, incidents }
}

/**
 * 인시던트 한 줄(S4a JSON 계약 §3). 기본은 보류 버전 1 의 대기 기한 보류 직후다(미해결, 보류 중, 판단 없음, 결함 없음).
 */
export function incidentRow(partial: Partial<IncidentRow> = {}): IncidentRow {
  return {
    incidentId: 'incident-1',
    executionId: 'exec-1',
    jobOrderId: 'JO-20261009-aaaaaaaa',
    robotId: 'humanoid-01',
    unitId: 'rack-arrival',
    at: 'h10',
    failureClass: 'SIGNAL_DEADLINE',
    route: 'SIGNAL',
    missionVersion: 3,
    siteSettingsVersion: 2,
    evidenceBeforeSeconds: 30,
    evidenceAfterSeconds: 15,
    inDoubtGraceSeconds: 60,
    stallWindowSeconds: 300,
    unresolved: true,
    resolution: null,
    fault: null,
    held: true,
    confirmedWithoutEvidence: false,
    ...partial,
  }
}

/** 인시던트 상세(S4a JSON 계약 §4). 기본은 [incidentRow] 기본과 같은 대기 기한 보류의 실측 값이다. */
export function incidentDetail(partial: Partial<IncidentDetail> = {}): IncidentDetail {
  return {
    instanceId: 'mw-1',
    incidentId: 'incident-1',
    executionId: 'exec-1',
    jobOrderId: 'JO-20261009-aaaaaaaa',
    robotId: 'humanoid-01',
    unitId: 'rack-arrival',
    at: 'h10',
    wallClockAt: 'w10',
    failureClass: 'SIGNAL_DEADLINE',
    route: 'SIGNAL',
    unresolved: true,
    resolution: null,
    held: true,
    confirmedWithoutEvidence: false,
    unitState: 'OPERATOR_HOLD',
    fault: null,
    blockedBy: [],
    requiredEvidence: 'E2',
    reachedEvidence: 'E0',
    verification: 'NOT_REQUESTED',
    step: { at: 1, plan: ['rack-arrival', 'RACK-204.S01'], completed: [] },
    evidenceWindow: [
      { sequence: 41, occurredAt: 'h9', kind: 'CELL_SIGNAL', detail: 'signal rack_present at deadline: false', local: true },
    ],
    windowTruncated: false,
    preconditionSubjects: [],
    expectedHold: null,
    observedHold: 'HOLD_KIND_UNSPECIFIED',
    effectMismatch: null,
    linkBroken: false,
    intent: {
      workMasterId: 'PrepareSequencedRack',
      orderVersion: 1,
      orderParameters: {},
      materials: [],
      equipment: [],
      capabilityMaxEvidence: 'E2',
      evidenceBeforeSeconds: 30,
      evidenceAfterSeconds: 15,
      skillType: 'equipment_wait',
      unitParameters: { signal: 'rack_present', expect: 'true', deadlineSeconds: '20', onDeadline: 'OPERATOR_HOLD' },
      source: null,
      destination: null,
      expectedIdentity: null,
      missionVersion: 3,
      siteSettingsVersion: 2,
      inDoubtGraceSeconds: 60,
      stallWindowSeconds: 300,
    },
    ...partial,
  }
}

/** 이전 인스턴스 사본 한 줄(S4b 계약 H3). 기본은 [incidentRow] 기본을 인스턴스 mw-0 에서 재작업 판단한 것이다. */
export function earlierRow(partial: Partial<EarlierIncidentRow> = {}): EarlierIncidentRow {
  return {
    instanceId: 'mw-0',
    ...incidentRow({
      held: false,
      resolution: { decision: 'REWORK', at: 'h20', wallClockAt: 'w20', decidedBy: { id: 'kim', kind: 'PERSON' } },
    }),
    ...partial,
  }
}

/** 복원 보고 한 행(S4b 계약 H1). 기본은 mw-0 의 exec-3 을 exec-1 로 다시 지은 것이다. */
export function restoreRow(partial: Partial<RestoreRow> = {}): RestoreRow {
  return {
    jobOrderId: 'JO-20261009-aaaaaaaa',
    robotId: 'humanoid-01',
    previousInstanceId: 'mw-0',
    previousExecutionId: 'exec-3',
    result: 'RESTORED',
    executionId: 'exec-1',
    reason: null,
    ...partial,
  }
}

/** 복원 못 한 실행 한 행(결함 14). 기본은 앞선 기동에서 포기한 행이고 종결할 수 있으며 그 기체를 배정에서 빼고 있다. */
export function unrestoredRow(partial: Partial<UnrestoredRow> = {}): UnrestoredRow {
  return {
    jobOrderId: 'JO-20261009-cccccccc',
    robotId: 'humanoid-01',
    previousInstanceId: 'mw-0',
    previousExecutionId: 'exec-7',
    result: 'GAVE_UP',
    reason: '판단 기록을 읽지 못해 리빌드하지 않는다: record=3, 형식 오류',
    since: 'u1',
    closable: true,
    notClosableReason: null,
    close: null,
    robotExcluded: true,
    ...partial,
  }
}

/** 종결 한 건(결함 14). 기본은 kim 이 기체 스냅숏을 근거로 종결한 것이다. */
export function closeRecord(partial: Partial<CloseRecord> = {}): CloseRecord {
  return {
    requestId: '7d1b6f0a-5b1c-4a52-9e0f-1c2d3e4f5a6b',
    approverId: 'kim',
    reason: '현장에서 정지를 확인했다',
    basis: 'SNAPSHOT',
    attestedStopped: false,
    closedInstanceId: 'mw-0',
    closedExecutionId: 'exec-7',
    restoreResult: 'GAVE_UP',
    restoreReason: '판단 기록을 읽지 못해 리빌드하지 않는다: record=3, 형식 오류',
    responseId: 'close-12',
    recordedAt: 'c1',
    ...partial,
  }
}

/** 운영자 종결 200 응답(결함 14). 기본은 종결해서 성공한 것이다. `answer` 를 주면 호스트 본문을 그것으로 바꾼다. */
export function closeOutcome(partial: Partial<ExecutionCloseOutcome> = {}): ExecutionCloseOutcome {
  return {
    requestId: '0b8a3c1e-2f64-4d70-8c1d-6a5e4d3c2b1a',
    jobOrderId: 'JO-20261009-cccccccc',
    result: 'SUCCEEDED',
    confirmation: null,
    outcome: 'CLOSED',
    answer: {
      result: 'CLOSED',
      jobOrderId: 'JO-20261009-cccccccc',
      basis: 'SNAPSHOT',
      responseId: 'close-12',
      detail: null,
      requestId: '0b8a3c1e-2f64-4d70-8c1d-6a5e4d3c2b1a',
    },
    rejection: null,
    ...partial,
  }
}

/** 호스트가 종결하지 않은 답(결함 14). 조작 기록은 거부로 남는다. */
export function closeRefused(result: Exclude<CloseResult, 'CLOSED'>, detail: string): ExecutionCloseOutcome {
  return closeOutcome({
    result: 'REJECTED',
    outcome: result,
    answer: { result, jobOrderId: 'JO-20261009-cccccccc', basis: null, responseId: null, detail, requestId: '0b8a3c1e-2f64-4d70-8c1d-6a5e4d3c2b1a' },
  })
}

/** 송신 기록(S4b 계약 H6). 기본은 행이 없는 지금 인스턴스 mw-1 이다. */
export function jobResponsesView(responses: JobResponseLogRow[] = []): JobResponsesView {
  return { instanceId: 'mw-1', total: responses.length, responses }
}

/** 송신 기록 한 행(S4b 계약 H6). 기본은 운영자 보류에 선 PARTIAL 응답을 보낸 것이다. */
export function jobResponseRow(partial: Partial<JobResponseLogRow> = {}): JobResponseLogRow {
  return {
    instanceId: 'mw-1',
    jobResponseId: 'resp-1',
    jobOrderId: 'JO-20261009-aaaaaaaa',
    executionId: 'exec-1',
    version: 1,
    physicalState: 'PARTIAL',
    requiredEvidence: 'E2',
    reachedEvidence: 'E0',
    completedUnits: [],
    unverifiedUnits: [],
    inDoubtUnits: [],
    incompleteUnits: ['rack-arrival'],
    operatorRequired: true,
    residualHold: 'HOLD_KIND_UNSPECIFIED',
    blockedBy: [],
    disposition: 'SENT',
    recordedAt: 'w30',
    ...partial,
  }
}

/** 장애 주입 200 본문(S4a JSON 계약 §9.4). 기본은 humanoid-01 의 스킬 실패를 현장이 받아들인 것이다. */
export function faultInjected(partial: Partial<FaultInjectionOutcome> = {}): FaultInjectionOutcome {
  return {
    requestId: '6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a',
    robotId: 'humanoid-01',
    kind: 'SKILL_EXECUTION_FAILED',
    state: null,
    result: 'SUCCEEDED',
    confirmation: null,
    fault: {
      robotId: 'humanoid-01',
      kind: 'SKILL_EXECUTION_FAILED',
      taskId: 'JO-1#RACK-204.S01',
      taskState: 'RETRIABLE',
      raised: true,
    },
    rejection: null,
    ...partial,
  }
}

/** 운영자 판단 200 본문(S4a JSON 계약 §9.5). 기본은 exec-1 의 rack-arrival 재작업이 선 것이다. */
export function holdResolved(partial: Partial<HoldResolveOutcome> = {}): HoldResolveOutcome {
  return {
    requestId: '3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10',
    executionId: 'exec-1',
    unitId: 'rack-arrival',
    decision: 'REWORK',
    result: 'SUCCEEDED',
    confirmation: null,
    outcome: 'Resolved',
    answer: {
      result: 'Resolved',
      detail: null,
      incidentId: 'incident-1',
      requestId: '3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10',
    },
    rejection: null,
    ...partial,
  }
}

/** 브라우저처럼 ISO-8859-1 밖의 문자가 헤더에 있으면 보내기 전에 던지는 fetch 대역을 끼운다. */
export function installFakeOps(
  view: RobotListView,
  adapters: AdapterListView = adapterView(),
  profiles: ProfileListView = profileView(),
  settings: SiteSettingsView = settingsView(),
): FakeOps {
  const fake: FakeOps = {
    interfaces: [],
    contracts: jobContracts(),
    calls: [],
    view,
    adapters,
    profiles,
    settings,
    executions: executionsView(),
    recent: { settled: [], nextBefore: null, olderTotal: 0 },
    older: new Map(),
    cell: cellView(),
    mission: missionOverview(),
    templates: missionTemplates(),
    incidents: incidentsView(),
    incidentDetails: new Map(),
    earlierDetails: new Map(),
    jobResponses: jobResponsesView(),
    residualHolds: { holds: [] },
    eligibility: eligibilityView(),
    eligibilityStatus: 200,
    failing: new Set(),
    answers: new Map(),
    answer: { status: 200, body: outcome({}) },
  }
  const manualReceipts = new Map<string, Record<string, unknown>>()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const headers = (init?.headers ?? {}) as Record<string, string>
      for (const value of Object.values(headers)) {
        if ([...value].some((ch) => ch.charCodeAt(0) > 0xff)) {
          throw new TypeError("Failed to execute 'fetch': String contains non ISO-8859-1 code point.")
        }
      }
      const method = init?.method ?? 'GET'
      fake.calls.push({ method, url, headers, body: init?.body ? JSON.parse(init.body as string) : undefined })
      const [path, query] = url.split('?')
      const params = new URLSearchParams(query ?? '')
      if (fake.failing.has(path) && (method === 'GET' || url === ELIGIBILITY_PATH)) {
        const body = HOST_PATHS.has(path) || path.startsWith(INCIDENT_PREFIX)
          ? JSON.stringify({ error: 'HOST_SILENT', detail: '실행 호스트가 답하지 않는다: 응답 없음: ConnectException' })
          : ''
        return new Response(body, { status: 503 })
      }
      if (method === 'GET' && path === '/api/mission-resources') { const answer=fake.answers.get(path)??{status:200,body:{requirements:[]}};return new Response(JSON.stringify(answer.body),{status:answer.status}) }
      if (method === 'GET' && path.startsWith('/api/stations')) {
        const answer = fake.answers.get(url) ?? { status: 200, body: { stations: [], runs: [] } }
        return new Response(JSON.stringify(answer.body), { status: answer.status })
      }
      if (method === 'GET' && path === '/api/mission-interfaces') return new Response(JSON.stringify({ interfaces: fake.interfaces }), { status: 200 })
      if (method === 'GET' && path === '/api/work-requests') return new Response(JSON.stringify({ total: manualReceipts.size, requests: [...manualReceipts.values()], offset: 0, limit: 20 }), { status: 200 })
      if (method === 'GET' && path.startsWith('/api/work-requests/')) {
        const receipt = [...manualReceipts.values()].find(row => row.receiptId === path.split('/').at(-1))
        return new Response(JSON.stringify({ receipt, hostRead: 'OK', execution: null, instanceId: 'mw-test', journalBlocked: false, observedAt: 't2', latestResult: null }), { status: 200 })
      }
      if (method === 'GET' && path === '/api/job-contracts') return new Response(JSON.stringify({ contracts: fake.contracts }), { status: 200 })
      if (method === 'GET' && path.startsWith(INCIDENT_PREFIX)) {
        const incidentId = decodeURIComponent(path.slice(INCIDENT_PREFIX.length))
        const instanceId = params.get('instanceId')
        const found =
          instanceId === null || instanceId === fake.incidents.instanceId
            ? fake.incidentDetails.get(incidentId)
            : fake.earlierDetails.get(`${instanceId}/${incidentId}`)
        return found === undefined
          ? new Response(JSON.stringify({ error: 'INCIDENT_NOT_FOUND', detail: '인시던트가 없다' }), { status: 404 })
          : new Response(JSON.stringify(found), { status: 200 })
      }
      if (method === 'GET' && path === '/api/executions' && query !== undefined) {
        const before = params.get('before')
        const body = before !== null ? (fake.older.get(Number(before)) ?? { settled: [], nextBefore: null, olderTotal: 0 }) : { ...fake.executions, ...fake.recent }
        return new Response(JSON.stringify(body), { status: 200 })
      }
      if (method === 'GET' && path === '/api/executions/summary') {
        return new Response(JSON.stringify({ ...executionSummary(fake.executions), ...(fake.fleet === undefined ? {} : { fleet: fake.fleet }) }), { status: 200 })
      }
      if (method === 'GET' && path === '/api/job-responses') {
        const jobOrderId = params.get('jobOrderId')
        const rows = fake.jobResponses.responses.filter((row) => jobOrderId === null || row.jobOrderId === jobOrderId)
        const body = jobOrderId === null ? fake.jobResponses : { ...fake.jobResponses, total: rows.length, responses: rows }
        return new Response(JSON.stringify(body), { status: 200 })
      }
      if (method === 'GET') {
        const body =
          url === '/api/robots'
            ? fake.view
            : url === '/api/adapters'
              ? fake.adapters
              : url === '/api/profiles'
                ? fake.profiles
                : url === '/api/site-settings'
                  ? fake.settings
                  : url === '/api/executions'
                    ? fake.executions
                    : url === '/api/cell'
                      ? fake.cell
                      : url === MISSION_PATH
                        ? fake.mission
                        : url === TEMPLATES_PATH
                          ? fake.templates
                          : url === '/api/incidents'
                            ? fake.incidents
                            : url === '/api/robots/residual-holds'
                              ? fake.residualHolds
                              : []
        return new Response(JSON.stringify(body), { status: 200 })
      }
      if (url === ELIGIBILITY_PATH) {
        return new Response(JSON.stringify(fake.eligibility), { status: fake.eligibilityStatus })
      }
      if (path.startsWith('/api/mission-interfaces/') && path.endsWith('/eligibility') && !fake.answers.has(url)) {
        const id = decodeURIComponent(path.split('/')[3]); const version = Number(path.split('/')[4])
        const row = fake.interfaces.find(item => item.interfaceId === id && item.version === version)
        return new Response(JSON.stringify({ interfaceId: id, interfaceVersion: version, jobOrder: { workMasterId: row?.workMasterId, missionVersion: row?.deployment?.missionVersion ?? 'CODE', requiredEvidence: 'E0', deployment: row?.deployment ?? { deploymentVersion: 1 } }, eligibility: fake.eligibility }), { status: 200 })
      }
      const answer = fake.answers.get(url) ?? fake.answer
      if(url === '/api/work-requests/manual' && answer.status === 200 && answer.body && typeof answer.body === 'object' && 'jobOrderId' in answer.body) {
        const payload = JSON.parse(init!.body as string) as { requestId: string; form: unknown }
        const outcome = answer.body as { result: string; jobOrderId: string }
        const duplicate = manualReceipts.has(payload.requestId)
        const receipt = { receiptId: `receipt-${payload.requestId}`, sourceSystemId: 'ops:manual', externalOrderId: payload.requestId,
          jobOrderId: outcome.jobOrderId, state: outcome.result === 'SUCCEEDED' ? 'ACCEPTED' : outcome.result === 'REJECTED' ? 'REJECTED' : 'IN_DOUBT',
          detail: null, payload: { ...payload, requestedBy: `operator/${headers['X-Ops-User']}` }, normalizedOrder: null,
          submission: answer.body, receivedAt: 't1', updatedAt: 't1' }
        manualReceipts.set(payload.requestId, receipt)
        return new Response(JSON.stringify({ receipt, duplicate }), { status: duplicate ? 200 : 202 })
      }
      return new Response(JSON.stringify(answer.body), { status: answer.status })
    }),
  )
  return fake
}

/** Ops 서버의 코드 기반 계약 목록을 나타내는 시험 픽스처. */
export function jobContracts(): JobContract[] {
  return [
    { workMasterId: 'InspectAsset', name: '자산 점검', description: '대상 위치로 이동하고 자산을 점검합니다.', editable: false, requiredEvidence: 'E0', fields: [{ path: 'targets[].id', constraint: '필수 문자열 · 중복 불가', target: 'equipmentRequirements[].id' }, { path: 'targets[].location', constraint: '필수 문자열', target: 'equipmentRequirements[].properties.location' }], sample: { workMasterId: 'InspectAsset', targets: [{ id: 'T1', location: 'bay-7' }] } },
    { workMasterId: 'PrepareSequencedRack', name: '서열 랙 준비', description: '제시된 자재를 지정 슬롯에 준비합니다.', editable: true, requiredEvidence: 'E2', fields: [{ path: 'slots[]', constraint: '필수 문자열 목록 · 중복 불가', target: 'equipmentRequirements[destination].id' }, { path: 'material', constraint: '필수 문자열', target: 'materialRequirements[].materialDefinitionId' }, { path: 'presentation', constraint: '필수 문자열', target: 'equipmentRequirements[source].id' }], sample: { workMasterId: 'PrepareSequencedRack', slots: ['RACK-204.S01'], material: 'ENGINE-COVER-A', presentation: 'SEQ-IN-02.BIN-A' } },
  ]
}
