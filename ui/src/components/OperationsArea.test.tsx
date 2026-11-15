import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type {
  Execution,
  HostEligibility,
  JobOrderOutcome,
  RobotEligibility,
  Session,
  SignalWriteOutcome,
} from '../api'
import { EXECUTIONS_RECENT_PATH } from '../api'
import { ELIGIBILITY_DEBOUNCE_MS, POLL_MS, SIGNAL_SETTLE_MS } from '../poll'
import { FLEET_ONLY_NOTE } from '../labels'
import { cellView, eligibilityView, executionsView, installFakeOps, standardSignals } from '../testing/fakeOps'
import type { FakeOps } from '../testing/fakeOps'
import { OperationsArea } from './OperationsArea'

const engineer: Session = { mode: 'engineer', user: 'local' }
const operator: Session = { mode: 'operator', user: 'kim' }

const emptyList = { registry: 'OK' as const, checkedAt: 't1', robots: [], robotsAsOf: 't1' }

/** 실행 호스트의 판정 행(S3a JSON 계약 §2.2). 기본은 통과다. */
function hostRow(robotId: string, partial: Partial<HostEligibility> = {}): HostEligibility {
  return { robotId, skillFit: 'FIT', missingSkills: [], runningExecutionId: null, passed: true, reasons: [], ...partial }
}

/** 운영 서비스의 기체 판정 행(S3a JSON 계약 §9.2). 기본은 배정 가능이다. */
function robotRow(robotId: string, partial: Partial<RobotEligibility> = {}): RobotEligibility {
  return {
    robotId,
    commissioning: 'COMPLETE',
    connection: 'FRESH',
    settingsVersion: 1,
    host: hostRow(robotId),
    eligible: true,
    reasons: [],
    ...partial,
  }
}

/** 실행 호스트 `GET /host/executions` 의 실행 하나(S3a JSON 계약 §5). 기본은 끝난 InspectAsset 코드 정의 실행이다. */
function execution(partial: Partial<Execution> = {}): Execution {
  return {
    executionId: 'exec-1',
    jobOrderId: 'JO-20261008-aaaaaaaa',
    workMasterId: 'InspectAsset',
    missionVersion: null,
    robotId: 'humanoid-01',
    physicalState: 'PHYSICALLY_DONE',
    units: [
      { unitId: 'T1.travel', skillType: 'navigate_to', state: 'DONE', reached: 'E0' },
      { unitId: 'T1', skillType: 'inspect', state: 'DONE', reached: 'E0' },
    ],
    jobResponse: {
      jobResponseId: 'resp-1',
      version: 1,
      physicalState: 'PHYSICALLY_DONE',
      requiredEvidence: 'E0',
      reachedEvidence: 'E0',
      completedUnits: ['T1.travel', 'T1'],
      unverifiedUnits: [],
      incompleteUnits: {},
      inDoubtUnits: [],
      operatorRequired: false,
      residualHold: 'HOLD_KIND_EMPTY',
      blockedBy: [],
      connection: 'CONNECTION_STATE_ONLINE',
    },
    ...partial,
  }
}

/** 제출 200 본문(S3a JSON 계약 §9.3). 기본은 humanoid-01 에 배정된 것이다. */
function submitted(partial: Partial<JobOrderOutcome> = {}): JobOrderOutcome {
  return {
    requestId: '6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a',
    jobOrderId: 'JO-20261008-6f1c2a9e',
    result: 'SUCCEEDED',
    confirmation: null,
    outcome: {
      result: 'ACCEPTED',
      executionId: 'exec-1',
      robotId: 'humanoid-01',
      rejectionReason: null,
      refusals: [],
      excluded: [],
    },
    ...partial,
  }
}

/** 신호 조작 200 본문(S3b JSON 계약 §10.8). 기본은 rack_present 를 켠 것이다. */
function written(partial: Partial<SignalWriteOutcome> = {}): SignalWriteOutcome {
  return {
    requestId: '6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a',
    name: 'rack_present',
    value: 'true',
    result: 'SUCCEEDED',
    confirmation: null,
    signal: { name: 'rack_present', location: 'RACK-204', kind: 'BOOLEAN', safety: false, value: 'true', observedAt: 't2' },
    rejection: null,
    ...partial,
  }
}

const RACK_PRESENT = '/api/cell/signals/rack_present'

/** 칸 이름(dt) 바로 뒤의 값(dd)을 읽는다. */
function field(region: HTMLElement, name: string) {
  const term = within(region).getByText(name, { selector: 'dt' })
  return term.nextElementSibling?.textContent
}

const calls = (fake: FakeOps, method: string, url: string) =>
  fake.calls.filter((call) => call.method === method && call.url === url)

function open(session: Session = operator) {
  return render(<OperationsArea session={session} onChanged={() => undefined} />)
}

async function fillTarget(index: number, id: string, location: string) {
  const form = screen.getByRole('form', { name: '작업 지시 폼' })
  await userEvent.type(within(form).getByLabelText(`대상 ${index} id`), id)
  await userEvent.type(within(form).getByLabelText(`대상 ${index} 장소`), location)
}

async function submit() {
  await userEvent.click(screen.getByRole('button', { name: '작업 요청 내기' }))
  return screen.findByRole('status', { name: '제출 결과' })
}

/** 한 행의 칸 글자. */
const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((cell) => cell.textContent)

describe('운영 영역', () => {
  afterEach(() => {
    sessionStorage.clear()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('InspectAsset 폼은 대상을 더하고 빼며 앞뒤 공백을 뗀 폼 초안을 운영자 모드로 낸다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answer = { status: 200, body: submitted() }
    open()
    await fillTarget(1, ' T1 ', 'bay-7')
    await userEvent.click(screen.getByRole('button', { name: '대상 더하기' }))
    await fillTarget(2, 'T2', 'bay-9')
    await userEvent.click(screen.getByRole('button', { name: '대상 더하기' }))
    await fillTarget(3, 'T3', 'bay-3')
    await userEvent.click(screen.getByRole('button', { name: '대상 2 빼기' }))
    await submit()
    const post = calls(fake, 'POST', '/api/work-requests/manual').at(-1)!
    expect((post.body as {form: unknown}).form).toEqual({
      workMasterId: 'InspectAsset',
      targets: [
        { id: 'T1', location: 'bay-7' },
        { id: 'T3', location: 'bay-3' },
      ],
    })
    expect(post.headers['X-Ops-Mode']).toBe('operator')
    expect(post.headers['X-Ops-User']).toBe('kim')
    expect(post.headers['Content-Type']).toBe('application/json')
    // 배정 가능은 같은 폼 초안으로 묻는다. 폼이 멈춘 뒤 디바운스를 지나 묻는다.
    await waitFor(() => expect(calls(fake, 'POST', '/api/job-orders/eligibility').at(-1)?.body).toEqual((post.body as {form: unknown}).form))
  })

  it('PrepareSequencedRack 폼은 셀 대역 슬롯을 셀 순서로 담고 자재에서 정한 제시 자리로 낸다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answer = { status: 200, body: submitted() }
    open()
    const form = screen.getByRole('form', { name: '작업 지시 폼' })
    await userEvent.selectOptions(within(form).getByLabelText('임무'), 'PrepareSequencedRack')
    await userEvent.click(await within(form).findByLabelText('RACK-204.S03'))
    await userEvent.click(within(form).getByLabelText('RACK-204.S01'))
    await userEvent.selectOptions(within(form).getByLabelText('자재'), 'ENGINE-COVER-A')
    expect(within(form).getByText('제시 자리 SEQ-IN-02.BIN-A')).toBeInTheDocument()
    await submit()
    expect((calls(fake, 'POST', '/api/work-requests/manual').at(-1)!.body as {form: unknown}).form).toEqual({
      workMasterId: 'PrepareSequencedRack',
      slots: ['RACK-204.S01', 'RACK-204.S03'],
      material: 'ENGINE-COVER-A',
      presentation: 'SEQ-IN-02.BIN-A',
    })
  })

  it('엔지니어 모드에서는 제출 대신 운영자 모드에서 낸다고 보이고 폼과 배정 가능은 그대로다', async () => {
    const fake = installFakeOps(emptyList)
    fake.eligibility = eligibilityView({ robots: [robotRow('humanoid-01')] })
    open(engineer)
    await fillTarget(1, 'T1', 'bay-7')
    expect(screen.queryByRole('button', { name: '작업 요청 내기' })).not.toBeInTheDocument()
    expect(screen.getByText('작업 요청은 운영자 모드에서 합니다.')).toBeInTheDocument()
    expect(await screen.findByRole('table', { name: '기체별 배정 가능' })).toBeInTheDocument()
  })

  it('덜 채운 폼은 배정 가능을 묻지 않고 내지도 않는다', async () => {
    const fake = installFakeOps(emptyList)
    open()
    await userEvent.type(screen.getByLabelText('대상 1 id'), 'T1')
    expect(screen.getByText('폼을 채우면 기체별 배정 가능을 봅니다')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '작업 요청 내기' }))
    expect(screen.getByRole('alert')).toHaveTextContent('점검 대상마다 대상 id 와 장소 이름을 넣으십시오')
    await userEvent.click(screen.getByRole('button', { name: '대상 1 빼기' }))
    await userEvent.click(screen.getByRole('button', { name: '작업 요청 내기' }))
    expect(screen.getByRole('alert')).toHaveTextContent('점검 대상을 하나 이상 넣으십시오')
    expect(fake.calls.filter((call) => call.method === 'POST')).toEqual([])
  })

  it('배정은 배정된 기체·실행 id·작업 지시 id 를 보이고 실행 목록의 그 행을 강조한다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answer = { status: 200, body: submitted() }
    open()
    await fillTarget(1, 'T1', 'bay-7')
    fake.executions = executionsView({ executions: [execution({ jobOrderId: 'JO-20261008-6f1c2a9e' })] })
    const notice = await submit()
    expect(notice).toHaveTextContent('InspectAsset 작업 지시: 배정됨')
    expect(field(notice, '작업 지시 id')).toBe('JO-20261008-6f1c2a9e')
    expect(field(notice, '실행 id')).toBe('exec-1')
    expect(field(notice, '배정된 기체')).toBe('humanoid-01')
    const row = await within(screen.getByRole('region', {name:'실행'})).findByRole('row', { name: /JO-20261008-6f1c2a9e/ })
    await waitFor(() => expect(row).toHaveClass('selected'))
  })

  it('거부는 거부 사유를, 미배정은 기체별 미배정 사유와 호스트가 뺀 기체를 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answer = {
      status: 200,
      body: submitted({
        result: 'REJECTED',
        outcome: {
          result: 'REJECTED',
          executionId: null,
          robotId: null,
          rejectionReason: '요구 근거 등급이 임무의 최고 근거보다 높다',
          refusals: [],
          excluded: [],
        },
      }),
    }
    open()
    await fillTarget(1, 'T1', 'bay-7')
    let notice = await submit()
    expect(notice).toHaveTextContent('InspectAsset 작업 지시: 거부됨')
    expect(field(notice, '거부 사유')).toBe('요구 근거 등급이 임무의 최고 근거보다 높다')
    expect(within(notice).queryByText('실행 id')).not.toBeInTheDocument()

    fake.answer = {
      status: 200,
      body: submitted({
        result: 'REJECTED',
        outcome: {
          result: 'UNASSIGNED',
          executionId: null,
          robotId: null,
          rejectionReason: null,
          refusals: [{ robotId: 'humanoid-01', reason: '작업 구역 점유' }],
          excluded: [
            hostRow('quadruped-01', {
              skillFit: 'MISSING',
              missingSkills: ['inspect'],
              passed: false,
              reasons: ['모자란 스킬: inspect'],
            }),
          ],
        },
      }),
    }
    await userEvent.click(screen.getByRole('button', { name: '작업 요청 내기' }))
    await waitFor(() => expect(screen.getByRole('status', { name: '제출 결과' })).toHaveTextContent('미배정'))
    notice = screen.getByRole('status', { name: '제출 결과' })
    expect(
      within(within(notice).getByRole('list', { name: '기체별 미배정 사유' })).getAllByRole('listitem')[0],
    ).toHaveTextContent('humanoid-01: 작업 구역 점유')
    expect(within(notice).getByRole('list', { name: '실행 호스트가 후보에서 뺀 기체' })).toHaveTextContent(
      'quadruped-01: 모자란 스킬: inspect',
    )
  })

  it('운영 서비스가 먼저 막은 400·403 은 막힘이고 기체별 이유를 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answer = {
      status: 400,
      body: {
        error: 'NO_ELIGIBLE_ROBOT',
        detail: 'humanoid-01: 연결이 오래됐다(기준 90초, 현장 설정 버전 1) / quadruped-01: 모자란 스킬: inspect',
      },
    }
    open()
    await fillTarget(1, 'T1', 'bay-7')
    let notice = await submit()
    expect(notice).toHaveTextContent('InspectAsset 작업 지시: 막힘(배정 가능한 기체 없음)')
    expect(notice).toHaveTextContent('humanoid-01: 연결이 오래됐다(기준 90초, 현장 설정 버전 1) / quadruped-01')

    fake.answer = { status: 403, body: { error: 'MODE_NOT_ALLOWED', detail: '운영자 모드만 할 수 있다' } }
    await userEvent.click(screen.getByRole('button', { name: '작업 요청 내기' }))
    await waitFor(() =>
      expect(screen.getByRole('status', { name: '제출 결과' })).toHaveTextContent('막힘(이 모드에서 할 수 없는 조작)'),
    )
    notice = screen.getByRole('status', { name: '제출 결과' })
    expect(notice).toHaveTextContent('운영자 모드만 할 수 있다')
  })

  it.each([
    ['CONFIRMED_APPLIED' as const, '응답은 없었으나 다시 읽어 보니 실행이 있음'],
    ['CONFIRMED_NOT_APPLIED' as const, '응답 없음. 현재 실행이 보이지 않습니다. 기존 요청 ID로 작업 요청을 확인하세요'],
    [null, '반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다'],
  ])('호스트 응답 없음은 확인 결과 %s 를 따로 보인다', async (confirmation, text) => {
    const fake = installFakeOps(emptyList)
    fake.answer = { status: 200, body: submitted({ result: 'NO_RESPONSE', confirmation, outcome: null }) }
    open()
    await fillTarget(1, 'T1', 'bay-7')
    const notice = await submit()
    expect(notice).toHaveTextContent(`InspectAsset 작업 지시: ${text}`)
    expect(notice).not.toHaveTextContent('배정됨')
    expect(field(notice, '작업 지시 id')).toBe('JO-20261008-6f1c2a9e')
  })

  it('운영 서비스의 그 밖의 비정상은 결과 모름이다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answer = { status: 500, body: {} }
    open()
    await fillTarget(1, 'T1', 'bay-7')
    const notice = await submit()
    expect(notice).toHaveTextContent('InspectAsset 작업 지시: 결과 모름(운영 서비스 응답 500)')
  })

  it('실행 목록은 호스트 인스턴스를 머리에 두고 최신 실행부터, 임무 버전 null 은 코드 정의로 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({
      instanceId: 'mw-3f0c',
      executions: [
        execution(),
        execution({
          executionId: 'exec-2',
          jobOrderId: 'JO-20261008-bbbbbbbb',
          workMasterId: 'PrepareSequencedRack',
          missionVersion: 3,
          physicalState: 'RUNNING',
          units: [
            { unitId: 'RACK-204.S01', skillType: 'pick_place', state: 'RUNNING', reached: 'E0', route: 'ROBOT' },
            { unitId: 'HU-1042', skillType: 'transport', state: 'PENDING', reached: 'E0', route: 'FLEET' },
          ],
          jobResponse: null,
        }),
      ],
    })
    open()
    const region = screen.getByRole('region', { name: '실행' })
    const table = await within(region).findByRole('table', { name: '실행 목록' })
    expect(field(region, '실행 호스트 인스턴스')).toBe('mw-3f0c')
    const rows = within(table).getAllByRole('row').slice(1)
    expect(cells(rows[0])).toEqual([
      'exec-2',
      'JO-20261008-bbbbbbbb',
      'PrepareSequencedRack',
      '버전 3',
      'humanoid-01',
      'RUNNING',
      // 플릿 운반 단위를 «기체» 로 찍지 않는다.
      'RACK-204.S01 pick_place: RUNNING, 근거 E0, 경로 기체HU-1042 transport: PENDING, 근거 E0, 경로 플릿 운반',
      '없음',
    ])
    expect(cells(rows[1])).toEqual([
      'exec-1',
      'JO-20261008-aaaaaaaa',
      'InspectAsset',
      '코드 정의',
      'humanoid-01',
      'PHYSICALLY_DONE',
      // 경로 칸이 생기기 전의 본문은 «기록 없음» 이다(추정하지 않는다).
      'T1.travel navigate_to: DONE, 근거 E0, 경로 기록 없음T1 inspect: DONE, 근거 E0, 경로 기록 없음',
      'resp-1: PHYSICALLY_DONE, 근거 E0(요구 E0)',
    ])
  })

  it('실행 호스트가 판정을 FLEET_PORT_ABSENT 로 거부하면 판정 화면과 제출 결과에 그 사유를 이름표로 보인다', async () => {
    const fake = installFakeOps(emptyList)
    const detail = '이 실행 호스트에는 플릿 포트가 없어 플릿 운반 단위가 든 작업 지시를 받지 않습니다: ContainerSupply 의 플릿 운반 단위 HU-1'
    fake.eligibility = eligibilityView({
      host: 'HOST_REFUSED',
      hostRefusal: { error: 'FLEET_PORT_ABSENT', detail },
      robots: [robotRow('humanoid-01', { host: null, eligible: false, reasons: [`실행 호스트가 거부했다(FLEET_PORT_ABSENT): ${detail}`] })],
    })
    fake.answer = { status: 400, body: { error: 'FLEET_PORT_ABSENT', detail } }
    open()
    await fillTarget(1, 'T1', 'bay-7')
    const alert = await screen.findByText(/실행 호스트가 거부함\(/)
    expect(alert).toHaveTextContent(`실행 호스트가 거부함(플릿 포트 없음(이 실행 호스트는 플릿 운반을 받지 않음)): ${detail}`)
    const notice = await submit()
    expect(notice).toHaveTextContent('InspectAsset 작업 지시: 막힘(플릿 포트 없음(이 실행 호스트는 플릿 운반을 받지 않음))')
    expect(notice).toHaveTextContent(detail)
    expect(notice).not.toHaveTextContent('배정 가능한 기체 없음')
  })

  it('배정 가능 표는 플릿 전용 계획의 스킬 판정을 해당 없음(묶인 기체)으로 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.eligibility = eligibilityView({
      registry: 'OK',
      host: 'OK',
      robots: [robotRow('humanoid-01', { host: hostRow('humanoid-01', { skillFit: 'NOT_APPLICABLE', requiredSkills: [], availableSkills: null, boundRobot: true }) })],
      fleetOnly: true,
    })
    open()
    await fillTarget(1, 'T1', 'bay-7')
    const table = await screen.findByRole('table', { name: '기체별 배정 가능' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows.map(cells)).toEqual([['humanoid-01', '완료', '신선(현장 설정 버전 1)', '없음', '없음', '모름', '해당 없음(묶인 기체)', '가능', '']])
    expect(screen.getByRole('note')).toHaveTextContent(FLEET_ONLY_NOTE)
  })

  it('배정 가능 표는 못 물어본 칸을 모름으로 보이고 배정 불가로 둔다', async () => {
    const fake = installFakeOps(emptyList)
    fake.eligibility = eligibilityView({
      registry: 'REGISTRY_SILENT',
      host: 'OK',
      robots: [
        robotRow('humanoid-01', {
          commissioning: null,
          connection: null,
          settingsVersion: null,
          host: hostRow('humanoid-01', { skillFit: 'UNKNOWN', passed: false, reasons: ['기체 케이퍼빌리티를 못 물어봤다'] }),
          eligible: false,
          reasons: ['registry 가 답하지 않아 시운전·연결을 모른다', '기체 케이퍼빌리티를 못 물어봤다'],
        }),
        robotRow('quadruped-01', {
          host: null,
          eligible: false,
          reasons: ['실행 호스트가 이 기체를 판정하지 않았다'],
        }),
        robotRow('humanoid-02', {
          host: hostRow('humanoid-02', {
            skillFit: 'MISSING',
            missingSkills: ['pick_place'],
            requiredSkills: ['pick_place'], availableSkills: ['inspect'],
            runningExecutionId: 'exec-4',
            passed: false,
            reasons: ['모자란 스킬: pick_place', '진행 중인 실행이 있다: exec-4'],
          }),
          eligible: false,
          reasons: ['모자란 스킬: pick_place', '진행 중인 실행이 있다: exec-4'],
        }),
        robotRow('humanoid-03'),
      ],
    })
    open()
    await fillTarget(1, 'T1', 'bay-7')
    const table = await screen.findByRole('table', { name: '기체별 배정 가능' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows.map(cells)).toEqual([
      [
        'humanoid-01',
        '모름',
        '모름',
        '없음',
        '모름', '모름',
        '모름',
        '불가',
        'registry 가 답하지 않아 시운전·연결을 모른다; 기체 케이퍼빌리티를 못 물어봤다',
      ],
      ['quadruped-01', '완료', '신선(현장 설정 버전 1)', '모름', '모름', '모름', '모름', '불가', '실행 호스트가 이 기체를 판정하지 않았다'],
      [
        'humanoid-02',
        '완료',
        '신선(현장 설정 버전 1)',
        'exec-4',
        'pick_place', 'inspect',
        '모자람: pick_place',
        '불가',
        '모자란 스킬: pick_place; 진행 중인 실행이 있다: exec-4',
      ],
      ['humanoid-03', '완료', '신선(현장 설정 버전 1)', '없음', '모름', '모름', '적합', '가능', ''],
    ])
  })

  it('운영 서비스가 폼을 막으면 배정 가능 대신 그 사유를 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.eligibilityStatus = 400
    fake.eligibility = { error: 'JOB_ORDER_BAD_REQUEST', detail: '대상 id 가 64자를 넘는다: 65자' }
    open()
    await fillTarget(1, 'T'.repeat(65), 'bay-7')
    expect(await screen.findByRole('alert')).toHaveTextContent('작업 지시 폼 오류: 대상 id 가 64자를 넘는다: 65자')
    expect(screen.queryByRole('table', { name: '기체별 배정 가능' })).not.toBeInTheDocument()
  })

  it('셀 대역을 표로 보이고 호스트가 셀 대역을 못 읽으면 모름이며 슬롯을 고를 수 없다', async () => {
    const fake = installFakeOps(emptyList)
    const { rerender } = open()
    const region = screen.getByRole('region', { name: '셀 대역' })
    const table = await within(region).findByRole('table', { name: '셀 대역 자리' })
    expect(within(table).getAllByRole('row').slice(1).map(cells).slice(0, 2)).toEqual([
      ['SEQ-IN-02.BIN-A', '제시 자리', '점유', 'ENGINE-COVER-A', '-'],
      ['RACK-204.S01', '슬롯', '비어 있음', '-', '-'],
    ])

    fake.cell = { cell: null }
    // 세션이 바뀌면 다시 읽는다. 주기(5초)를 기다리지 않는다.
    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
    expect(await within(region).findByText('모름: 실행 호스트가 셀 대역을 읽지 못했습니다')).toBeInTheDocument()
    expect(within(region).queryByRole('table')).not.toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('임무'), 'PrepareSequencedRack')
    expect(screen.getByText('모름: 셀 대역을 읽지 못해 슬롯과 자재를 고를 수 없습니다')).toBeInTheDocument()
  })

  it('셀 대역 신호는 이름·값·관측 시각을 보이고 안전이 아닌 BOOLEAN 신호만 켜기·끄기가 있다', async () => {
    const fake = installFakeOps(emptyList)
    const signals = standardSignals()
    signals[0] = { ...signals[0], value: 'true', observedAt: 't2' }
    fake.cell = { cell: { ...cellView().cell!, signals } }
    open()
    const region = screen.getByRole('region', { name: '셀 대역' })
    const table = await within(region).findByRole('table', { name: '셀 대역 신호' })
    expect(within(table).getAllByRole('row').slice(1).map(cells)).toEqual([
      ['rack_present', 'BOOLEAN', 'true', 't2', '켜기끄기'],
      ['guard_closed', 'BOOLEAN', 'true', '-', '안전 신호(값만 봅니다)'],
      ['lot_code', 'TEXT', 'LOT-0001', '-', '-'],
    ])
    expect(within(region).getAllByRole('button').map((button) => button.getAttribute('aria-label'))).toEqual([
      'rack_present 켜기',
      'rack_present 끄기',
    ])
  })

  it('신호 켜기·끄기는 두 모드 모두 그 이름과 문자열 값으로 보내고 결과를 보인 뒤 셀 대역을 다시 읽는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answers.set(RACK_PRESENT, { status: 200, body: written() })
    const { rerender } = open(operator)
    const region = screen.getByRole('region', { name: '셀 대역' })
    await userEvent.click(await within(region).findByRole('button', { name: 'rack_present 켜기' }))
    expect(await within(region).findByRole('status', { name: '신호 조작 결과' })).toHaveTextContent(
      'rack_present 켜기: 반영됨(값 true)',
    )
    let post = calls(fake, 'POST', RACK_PRESENT).at(-1)!
    expect(post.body).toEqual({ value: 'true' })
    expect(post.headers['X-Ops-Mode']).toBe('operator')
    expect(post.headers['Content-Type']).toBe('application/json')
    const reads = calls(fake, 'GET', '/api/cell').length
    await waitFor(() => expect(calls(fake, 'GET', '/api/cell').length).toBeGreaterThanOrEqual(reads + 1))

    fake.answers.set(RACK_PRESENT, {
      status: 200,
      body: written({ value: 'false', signal: { ...written().signal!, value: 'false' } }),
    })
    rerender(<OperationsArea session={engineer} onChanged={() => undefined} />)
    await userEvent.click(within(region).getByRole('button', { name: 'rack_present 끄기' }))
    await waitFor(() =>
      expect(within(region).getByRole('status', { name: '신호 조작 결과' })).toHaveTextContent(
        'rack_present 끄기: 반영됨(값 false)',
      ),
    )
    post = calls(fake, 'POST', RACK_PRESENT).at(-1)!
    expect(post.body).toEqual({ value: 'false' })
    expect(post.headers['X-Ops-Mode']).toBe('engineer')
  })

  it.each([
    [
      written({
        result: 'REJECTED',
        signal: null,
        rejection: { status: 400, error: 'SIGNAL_VALUE_INVALID', detail: 'BOOLEAN 신호 값은 true 나 false 다' },
      }),
      'rack_present 켜기: 현장이 거부함(신호 종류에 맞지 않는 값). BOOLEAN 신호 값은 true 나 false 다',
    ],
    [
      written({ result: 'NO_RESPONSE', confirmation: 'CONFIRMED_APPLIED', signal: null }),
      'rack_present 켜기: 응답은 없었으나 다시 읽어 보니 그 값임',
    ],
    [
      written({ result: 'NO_RESPONSE', confirmation: 'CONFIRMED_NOT_APPLIED', signal: null }),
      'rack_present 켜기: 응답 없음. 다시 읽어 보니 그 값이 아님',
    ],
    [
      written({ result: 'NO_RESPONSE', confirmation: null, signal: null }),
      'rack_present 켜기: 반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다',
    ],
  ])('현장의 거부와 응답 없음은 반영됨으로 보이지 않는다(%#)', async (body, text) => {
    const fake = installFakeOps(emptyList)
    fake.answers.set(RACK_PRESENT, { status: 200, body })
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'rack_present 켜기' }))
    const shown = await screen.findByRole('status', { name: '신호 조작 결과' })
    expect(shown).toHaveTextContent(text)
    expect(shown).not.toHaveTextContent('반영됨')
  })

  it('신호 조작 뒤 다시 읽기 타이머는 영역을 닫으면 치우고 닫은 뒤에 끝난 조작은 걸지 않는다', async () => {
    // 시계는 손으로만 민다. 대역의 fetch 는 타이머를 쓰지 않으므로 act 로 약속만 흘려보낸다. 닫힌 영역의 타이머는 다시 읽기를
    // 부르지 못하므로 읽기 수가 아니라 남은 타이머 수로 본다.
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const fake = installFakeOps(emptyList)
    fake.answers.set(RACK_PRESENT, { status: 200, body: written() })
    const reads = () => calls(fake, 'GET', '/api/cell').length
    const flush = async () => {
      for (let round = 0; round < 5; round++) await act(async () => undefined)
    }
    const toggle = () => fireEvent.click(screen.getByRole('button', { name: 'rack_present 켜기' }))

    // 열려 있으면 pump 가 돈 뒤 한 번 더 읽는다.
    const first = open()
    await flush()
    toggle()
    await flush()
    expect(screen.getByRole('status', { name: '신호 조작 결과' })).toHaveTextContent('rack_present 켜기: 반영됨(값 true)')
    const before = reads()
    await act(async () => vi.advanceTimersByTime(SIGNAL_SETTLE_MS))
    expect(reads()).toBe(before + 1)

    // 다시 읽기가 걸린 뒤 닫으면 치운다.
    toggle()
    await flush()
    first.unmount()
    expect(vi.getTimerCount()).toBe(0)

    // 조작이 끝나기 전에 닫으면 끝난 뒤에도 걸지 않는다.
    const second = open()
    await flush()
    toggle()
    second.unmount()
    await flush()
    expect(vi.getTimerCount()).toBe(0)
    expect(calls(fake, 'POST', RACK_PRESENT)).toHaveLength(3)
  })

  it('셀 대역 스냅숏이 없거나 신호 목록이 없으면 신호를 모름으로 보이고 켜기·끄기가 없다', async () => {
    const fake = installFakeOps(emptyList)
    fake.cell = { cell: null }
    const { rerender } = open()
    const region = screen.getByRole('region', { name: '셀 대역' })
    expect(await within(region).findByText('모름: 신호 값을 읽지 못했습니다')).toBeInTheDocument()
    expect(within(region).queryByRole('table', { name: '셀 대역 신호' })).not.toBeInTheDocument()
    expect(within(region).queryByRole('button')).not.toBeInTheDocument()

    fake.cell = { cell: { ...cellView().cell!, signals: null } }
    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
    expect(await within(region).findByText('모름: 셀 대역이 신호 목록을 싣지 않았습니다')).toBeInTheDocument()
    expect(within(region).queryByText('셀 대역에 신호가 없습니다')).not.toBeInTheDocument()
    expect(within(region).queryByRole('button')).not.toBeInTheDocument()
  })

  it('실행 호스트가 503 이면 실행 목록과 셀 대역은 직전 값과 불통을 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: [execution()] })
    const { rerender } = open()
    const region = screen.getByRole('region', { name: '실행' })
    await within(region).findByRole('table', { name: '실행 목록' })
    fake.failing.add('/api/executions')
    fake.failing.add('/api/cell')
    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
    expect(
      await within(region).findByText('직전 값입니다. 실행 호스트 불통: 실행 호스트가 답하지 않는다: 응답 없음: ConnectException'),
    ).toBeInTheDocument()
    expect(within(region).getByRole('row', { name: /exec-1/ })).toBeInTheDocument()
    expect(
      within(screen.getByRole('region', { name: '셀 대역' })).getByText(/직전 값입니다. 실행 호스트 불통/),
    ).toBeInTheDocument()
  })

  it('주기마다 실행 목록·셀 대역을, 폼이 채워졌을 때만 배정 가능을 다시 읽는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const fake = installFakeOps(emptyList)
    open()
    await user.type(screen.getByLabelText('대상 1 id'), 'T1')
    await user.type(screen.getByLabelText('대상 1 장소'), 'bay-7')
    await waitFor(() => expect(calls(fake, 'POST', '/api/job-orders/eligibility').length).toBeGreaterThan(0))
    const count = () => ({
      executions: calls(fake, 'GET', EXECUTIONS_RECENT_PATH).length,
      cell: calls(fake, 'GET', '/api/cell').length,
      eligibility: calls(fake, 'POST', '/api/job-orders/eligibility').length,
    })
    let before = count()
    await act(async () => vi.advanceTimersByTime(POLL_MS))
    await waitFor(() =>
      expect(count()).toEqual({
        executions: before.executions + 1,
        cell: before.cell + 1,
        eligibility: before.eligibility + 1,
      }),
    )

    await user.clear(screen.getByLabelText('대상 1 장소'))
    before = count()
    await act(async () => vi.advanceTimersByTime(POLL_MS))
    await waitFor(() => expect(count().executions).toBe(before.executions + 1))
    expect(count().eligibility).toBe(before.eligibility)
  })

  it('폼을 바꾸면 키 입력마다 묻지 않고 멈춘 뒤 디바운스가 지나야 한 번 묻는다', async () => {
    // 시계는 손으로만 민다. userEvent 는 진짜 setTimeout 을 기다리므로 키 입력을 fireEvent 로 한 글자씩 낸다.
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const fake = installFakeOps(emptyList)
    open()
    const eligibility = () => calls(fake, 'POST', '/api/job-orders/eligibility')
    const type = (label: string, text: string) => {
      for (let end = 1; end <= text.length; end++) {
        fireEvent.change(screen.getByLabelText(label), { target: { value: text.slice(0, end) } })
      }
    }
    type('대상 1 id', 'T1')
    // 장소 첫 글자부터 폼이 다 찬다. 그 뒤 글자마다 폼이 바뀐다.
    type('대상 1 장소', 'bay-7')
    await act(async () => vi.advanceTimersByTime(ELIGIBILITY_DEBOUNCE_MS - 1))
    expect(eligibility()).toEqual([])

    await act(async () => vi.advanceTimersByTime(1))
    expect(eligibility().map((call) => call.body)).toEqual([
      { workMasterId: 'InspectAsset', targets: [{ id: 'T1', location: 'bay-7' }] },
    ])

    // 주기의 다시 읽기는 디바운스를 기다리지 않는다.
    await act(async () => vi.advanceTimersByTime(POLL_MS - ELIGIBILITY_DEBOUNCE_MS))
    expect(eligibility()).toHaveLength(2)
  })

  it('운영 영역을 열기 전에는 실행 호스트를 읽지 않고 호스트 불통이 기존 다섯 읽기를 직전 값으로 만들지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.failing.add('/api/executions')
    fake.failing.add('/api/cell')
    render(<App />)
    expect(await screen.findByText('선언된 기체가 없습니다')).toBeInTheDocument()
    expect(fake.calls.filter((call) => call.url.startsWith('/api/executions') || call.url === '/api/cell')).toEqual([])

    await userEvent.click(screen.getByRole('button', { name: '운영' }))
    expect(
      await screen.findByText('모름: 실행 목록을 아직 읽지 못했습니다 (실행 호스트가 답하지 않는다: 응답 없음: ConnectException)'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/운영 서비스에 닿지 않습니다/)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '로봇' }))
    expect(screen.getByText('선언된 기체가 없습니다')).toBeInTheDocument()
    expect(screen.queryByText(/직전 값입니다/)).not.toBeInTheDocument()
    expect(screen.getByText(/registry 응답 확인/)).toBeInTheDocument()
  })
})
