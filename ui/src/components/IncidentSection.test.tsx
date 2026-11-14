import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { FaultDetail, HoldResolveOutcome, IncidentResolution, Session } from '../api'
import { EXECUTIONS_RECENT_PATH } from '../api'
import { EXECUTION_CONTROL_TEXT } from '../labels'
import { POLL_MS, SIGNAL_SETTLE_MS } from '../poll'
import { earlierRow, executionsView, holdResolved, incidentDetail, incidentRow, incidentsView, installFakeOps } from '../testing/fakeOps'
import type { FakeOps } from '../testing/fakeOps'
import { OperationsArea } from './OperationsArea'

const engineer: Session = { mode: 'engineer', user: 'lee' }
const operator: Session = { mode: 'operator', user: 'kim' }

const emptyList = { registry: 'OK' as const, checkedAt: 't1', robots: [], robotsAsOf: 't1' }

const RESOLVE = '/api/executions/exec-1/units/rack-arrival/resolve'

/** 재작업 판단(S4a JSON 계약 §3). */
const rework: IncidentResolution = {
  decision: 'REWORK',
  at: 'h20',
  wallClockAt: 'w20',
  decidedBy: { id: 'kim', kind: 'PERSON' },
}

/** 스킬 실패의 결함 원문(S4a JSON 계약 §4 실측 값). */
const grasp: FaultDetail = {
  failureClass: 'GRASP_FAILED',
  errorType: 'SKILL_EXECUTION_FAILED',
  vendorDetail: '',
  errorHint: '',
  references: [
    { key: 'KEY_SKILL_ID', value: 'pick_place' },
    { key: 'KEY_TASK_ID', value: 'JO-1#RACK-204.S01' },
  ],
  canContinueCurrentTask: false,
  canAcceptNewTask: true,
  activeUntilKind: 'KIND_UNTIL_NEW_TASK',
  activeUntilTime: '',
}

/** 실행 전체를 막던 로봇 수준 결함(S4a JSON 계약 §4 실측 값). */
const localization: FaultDetail = {
  ...grasp,
  failureClass: 'LOCALIZATION_LOST',
  errorType: 'LOCALIZATION_LOST',
  errorHint: '기체를 다시 위치시키십시오',
  references: [],
  canAcceptNewTask: false,
  activeUntilKind: 'KIND_UNTIL_CLEARED',
}

/** 줄 셋. 호스트가 준 순서(최신부터)다: 두 번째 보류, 재작업 판단된 첫 보류, 코드 정의의 스킬 실패. */
function threeRows() {
  return incidentsView([
    incidentRow({ incidentId: 'incident-3', at: 'h30' }),
    incidentRow({ incidentId: 'incident-2', at: 'h10', held: false, resolution: rework }),
    incidentRow({
      incidentId: 'incident-1',
      at: 'h5',
      executionId: 'exec-0',
      unitId: 'RACK-204.S01',
      failureClass: 'GRASP_FAILED',
      route: 'ROBOT',
      missionVersion: null,
      siteSettingsVersion: null,
      unresolved: false,
      held: false,
      fault: { failureClass: 'GRASP_FAILED', errorType: 'SKILL_EXECUTION_FAILED', errorHint: '' },
    }),
  ])
}

const calls = (fake: FakeOps, method: string, url: string) =>
  fake.calls.filter((call) => call.method === method && call.url === url)

/** 한 행의 칸 글자. */
const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((cell) => cell.textContent)

/** 칸 이름(dt) 바로 뒤의 값(dd)을 읽는다. */
function field(region: HTMLElement, name: string) {
  const term = within(region).getByText(name, { selector: 'dt' })
  return term.nextElementSibling?.textContent
}

function open(session: Session = operator) {
  return render(<OperationsArea session={session} onChanged={() => undefined} />)
}

async function select(incidentId: string) {
  await userEvent.click(await screen.findByRole('button', { name: `${incidentId} 상세 보기` }))
  return screen.findByRole('region', { name: '인시던트 상세' })
}

describe('인시던트', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('목록은 호스트가 준 최신부터의 순서로 시각·기체·실행·단위·실패 종류·경로·버전·판단 칸을 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = { ...threeRows(), total: 7 }
    open()
    const region = screen.getByRole('region', { name: '인시던트' })
    const table = await within(region).findByRole('table', { name: '인시던트 목록' })
    expect(within(region).getByText('실행 호스트 인스턴스 mw-1, 인시던트 7건 가운데 최신 3건')).toBeInTheDocument()
    expect(within(table).getAllByRole('row').slice(1).map(cells)).toEqual([
      ['incident-3', 'h30', '-', 'humanoid-01', 'exec-1', 'rack-arrival', 'SIGNAL_DEADLINE', 'SIGNAL', '버전 2', '버전 3', '미해결', '보류 중'],
      ['incident-2', 'h10', '-', 'humanoid-01', 'exec-1', 'rack-arrival', 'SIGNAL_DEADLINE', 'SIGNAL', '버전 2', '버전 3', '판단됨', '-'],
      ['incident-1', 'h5', '-', 'humanoid-01', 'exec-0', 'RACK-204.S01', 'GRASP_FAILED', 'ROBOT', '모름', '코드 정의', '판단 대상 아님', '-'],
    ])
  })

  it('보류 중 강조는 held 인 줄에만 있고 미해결이어도 held 가 아니면 강조하지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([
      incidentRow({ incidentId: 'incident-5', held: false }),
      incidentRow({ incidentId: 'incident-4', held: true }),
    ])
    open()
    const table = await screen.findByRole('table', { name: '인시던트 목록' })
    const [, newer, older] = within(table).getAllByRole('row')
    expect(cells(newer).at(-2)).toBe('미해결')
    expect(cells(newer).at(-1)).toBe('-')
    expect(newer).not.toHaveClass('held')
    expect(cells(older).at(-1)).toBe('보류 중')
    expect(older).toHaveClass('held')
    expect(within(table).getAllByText('보류 중')).toHaveLength(1)
  })

  it('줄을 고르면 상세에 설정 버전과 시간값, 임무 버전, 단계 위치, 필요·도달 근거, 확인 결과 코드 이름, 근거 윈도우를 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([incidentRow()])
    fake.incidentDetails.set('incident-1', incidentDetail())
    open()
    const detail = await select('incident-1')
    const observed = await within(detail).findByRole('region', { name: '관측' })
    expect(field(observed, '현장 설정 버전')).toBe('버전 2')
    expect(field(observed, '시간값')).toBe('근거 윈도우 앞 폭 30초, 뒤 폭 15초, inDoubtGrace 60초, stallWindow 300초')
    expect(field(observed, '임무 버전')).toBe('PrepareSequencedRack 버전 3')
    expect(field(observed, '단계 위치')).toBe('1/2. 계획 rack-arrival → RACK-204.S01. 끝난 단위 없음')
    expect(field(observed, '필요 근거 등급')).toBe('E2')
    expect(field(observed, '도달 근거 등급')).toBe('E0')
    expect(field(observed, '확인 결과(코드 이름)')).toBe('NOT_REQUESTED')
    expect(field(observed, '단위의 지금 상태')).toBe('OPERATOR_HOLD')
    expect(detail).not.toHaveTextContent('확인 안 함')
    expect(within(observed).getByText('없음(결함 없이 실패를 알림)')).toBeInTheDocument()
    const window = within(observed).getByRole('table', { name: '근거 윈도우' })
    expect(within(window).getAllByRole('row').slice(1).map(cells)).toEqual([
      ['41', 'h9', 'CELL_SIGNAL', 'signal rack_present at deadline: false', '미들웨어 기록'],
    ])
    expect(within(observed).getByText('근거 윈도우(앞 30초, 뒤 15초)')).toBeInTheDocument()
    expect(calls(fake, 'GET', '/api/incidents/incident-1?instanceId=mw-1').length).toBeGreaterThan(0)
  })

  it('상세는 이 단위의 결함과 실행을 막던 결함(blockedBy) 원문을 보이고 코드 정의 임무는 코드 정의다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = threeRows()
    fake.incidentDetails.set(
      'incident-1',
      incidentDetail({
        incidentId: 'incident-1',
        unitId: 'RACK-204.S01',
        failureClass: 'GRASP_FAILED',
        route: 'ROBOT',
        unresolved: false,
        held: false,
        unitState: 'FAILED',
        fault: grasp,
        blockedBy: [localization],
        windowTruncated: true,
        intent: { ...incidentDetail().intent, missionVersion: null, siteSettingsVersion: null },
      }),
    )
    open()
    const detail = await select('incident-1')
    const own = await within(detail).findByLabelText('이 단위의 결함')
    expect(field(own, '분류')).toBe('GRASP_FAILED(SKILL_EXECUTION_FAILED)')
    expect(field(own, '참조')).toBe('KEY_SKILL_ID=pick_place, KEY_TASK_ID=JO-1#RACK-204.S01')
    expect(field(own, '조치 힌트')).toBe('없음')
    expect(field(own, '유지')).toBe('KIND_UNTIL_NEW_TASK')
    const blocked = within(detail).getByLabelText('실행을 막던 결함 1')
    expect(field(blocked, '분류')).toBe('LOCALIZATION_LOST(LOCALIZATION_LOST)')
    expect(field(blocked, '새 태스크 받기')).toBe('불가')
    expect(field(blocked, '유지')).toBe('KIND_UNTIL_CLEARED')
    expect(within(detail).getByText('윈도우 밖이라 버린 관측이 있습니다')).toBeInTheDocument()
    const observed = within(detail).getByRole('region', { name: '관측' })
    expect(field(observed, '임무 버전')).toBe('PrepareSequencedRack 코드 정의')
    expect(field(observed, '현장 설정 버전')).toBe('모름')
  })

  it('지금 인스턴스의 그 실행이 아직 결함으로 막혀 있을 때만 상세에 감수는 실행 구역에서 한다는 안내를 붙인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = threeRows()
    fake.incidentDetails.set('incident-1', incidentDetail({ incidentId: 'incident-1', executionId: 'exec-0', blockedBy: [localization] }))
    const run = (blockedFaults: FaultDetail[], instanceId = 'mw-1') =>
      executionsView({
        instanceId,
        executions: [
          {
            executionId: 'exec-0',
            jobOrderId: 'JO-0',
            workMasterId: 'PrepareSequencedRack',
            missionVersion: null,
            robotId: 'humanoid-01',
            physicalState: blockedFaults.length > 0 ? 'OPERATOR_HOLD' : 'RUNNING',
            units: [],
            jobResponse: null,
            blockedFaults,
          },
        ],
      })
    fake.executions = run([localization])
    const blocked = open()
    const detail = await select('incident-1')
    expect(await within(detail).findByText(EXECUTION_CONTROL_TEXT.incidentPointer)).toHaveAttribute('role', 'note')
    blocked.unmount()

    // 감수한 뒤(막는 결함 없음)나 실행 목록이 다른 인스턴스이면 안내가 없다.
    for (const page of [run([]), run([localization], 'mw-2')]) {
      fake.executions = page
      const view = open()
      const shown = await select('incident-1')
      await within(shown).findByLabelText('실행을 막던 결함 1')
      await screen.findByRole('table', { name: '실행 목록' })
      expect(within(shown).queryByText(EXECUTION_CONTROL_TEXT.incidentPointer)).toBeNull()
      view.unmount()
    }
  })

  it('판단 없는 인시던트는 관측만 보이고 판단된 인시던트는 판단자와 실제 시각, 결정을 관측과 다른 구역에 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = threeRows()
    fake.incidentDetails.set('incident-3', incidentDetail({ incidentId: 'incident-3' }))
    fake.incidentDetails.set(
      'incident-2',
      incidentDetail({ incidentId: 'incident-2', held: false, unitState: 'RUNNING', resolution: rework }),
    )
    open()
    let detail = await select('incident-3')
    expect(await within(detail).findByText('사람의 판단 없음. 아래 값은 모두 관측입니다')).toBeInTheDocument()
    expect(within(detail).queryByRole('region', { name: '사람의 판단' })).not.toBeInTheDocument()
    expect(detail).not.toHaveTextContent('사람이 판단함')

    detail = await select('incident-2')
    const asserted = await within(detail).findByRole('region', { name: '사람의 판단' })
    expect(asserted).toHaveTextContent('사람이 판단함: kim, w20')
    expect(asserted).toHaveTextContent('결정 재작업(REWORK), 호스트 시각 h20')
    expect(asserted).toHaveClass('asserted')
    const observed = within(detail).getByRole('region', { name: '관측' })
    expect(observed).not.toHaveTextContent('사람이 판단함')
    expect(detail).not.toHaveTextContent('설비 근거 없이 완료 확인')
  })

  it('사람의 걸음(운영자 취소·회복 조치 승인)도 판단 구역에 이름과 원래 값으로 보인다', async () => {
    const fake = installFakeOps(emptyList)
    const canceled: IncidentResolution = { ...rework, decision: 'OPERATOR_CANCEL' }
    const applied: IncidentResolution = { ...rework, decision: 'REMEDY_APPLIED' }
    fake.incidents = incidentsView([
      incidentRow({ incidentId: 'incident-2', held: false, resolution: canceled }),
      incidentRow({ incidentId: 'incident-1', held: false, resolution: applied }),
    ])
    fake.incidentDetails.set('incident-2', incidentDetail({ incidentId: 'incident-2', held: false, resolution: canceled }))
    fake.incidentDetails.set('incident-1', incidentDetail({ incidentId: 'incident-1', held: false, resolution: applied }))
    open()
    let detail = await select('incident-2')
    expect(await within(detail).findByRole('region', { name: '사람의 판단' })).toHaveTextContent(
      '결정 운영자 취소(OPERATOR_CANCEL), 호스트 시각 h20',
    )
    detail = await select('incident-1')
    await waitFor(() => expect(within(detail).getByRole('heading', { name: 'incident-1 상세' })).toBeInTheDocument())
    expect(await within(detail).findByRole('region', { name: '사람의 판단' })).toHaveTextContent(
      '결정 회복 조치 승인(REMEDY_APPLIED), 호스트 시각 h20',
    )
  })

  it('설비 근거 없이 완료 확인은 호스트가 준 표시로만 보이고 화면이 다시 계산하지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    const confirm: IncidentResolution = { ...rework, decision: 'CONFIRM_DONE' }
    fake.incidents = incidentsView([
      incidentRow({ incidentId: 'incident-2', held: false, resolution: confirm, confirmedWithoutEvidence: true }),
      incidentRow({ incidentId: 'incident-1', held: false, resolution: confirm, confirmedWithoutEvidence: false }),
    ])
    fake.incidentDetails.set(
      'incident-2',
      incidentDetail({ incidentId: 'incident-2', held: false, resolution: confirm, confirmedWithoutEvidence: true }),
    )
    // 확인 결과가 MATCHED 가 아니어도 호스트가 거짓이라고 했으면 그대로 따른다.
    fake.incidentDetails.set(
      'incident-1',
      incidentDetail({ incidentId: 'incident-1', held: false, resolution: confirm, confirmedWithoutEvidence: false }),
    )
    open()
    const table = await screen.findByRole('table', { name: '인시던트 목록' })
    expect(within(table).getAllByRole('row').slice(1).map((row) => cells(row).at(-2))).toEqual([
      '판단됨(설비 근거 없이 완료 확인)',
      '판단됨',
    ])
    let detail = await select('incident-2')
    const asserted = await within(detail).findByRole('region', { name: '사람의 판단' })
    expect(asserted).toHaveTextContent('결정 완료 확인(CONFIRM_DONE)')
    expect(within(asserted).getByText('설비 근거 없이 완료 확인')).toBeInTheDocument()

    detail = await select('incident-1')
    await waitFor(() => expect(within(detail).getByRole('heading', { name: 'incident-1 상세' })).toBeInTheDocument())
    expect(await within(detail).findByRole('region', { name: '사람의 판단' })).not.toHaveTextContent(
      '설비 근거 없이 완료 확인',
    )
  })

  it('판단 버튼 둘은 운영자 모드에서 보류 중인 상세에만 있고 엔지니어 모드와 보류가 아닌 상세에는 없다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = threeRows()
    fake.incidentDetails.set('incident-3', incidentDetail({ incidentId: 'incident-3' }))
    fake.incidentDetails.set('incident-2', incidentDetail({ incidentId: 'incident-2', held: false, resolution: rework }))
    const { rerender } = open(operator)
    let detail = await select('incident-3')
    const form = await within(detail).findByRole('form', { name: '운영자 판단' })
    expect(within(form).getAllByRole('button').map((button) => button.textContent)).toEqual(['완료 확인', '재작업'])
    expect(form).toHaveTextContent('exec-1/rack-arrival 보류 해소 방식(완료 확인 또는 재작업)을 결정합니다. 결정자는 kim입니다.')

    rerender(<OperationsArea session={engineer} onChanged={() => undefined} />)
    expect(await within(detail).findByText('보류 중입니다. 운영자 판단은 운영자 모드에서 합니다')).toBeInTheDocument()
    expect(within(detail).queryByRole('form', { name: '운영자 판단' })).not.toBeInTheDocument()
    expect(within(detail).queryByRole('button', { name: '재작업' })).not.toBeInTheDocument()

    rerender(<OperationsArea session={operator} onChanged={() => undefined} />)
    detail = await select('incident-2')
    await within(detail).findByRole('region', { name: '사람의 판단' })
    expect(within(detail).queryByRole('button', { name: '완료 확인' })).not.toBeInTheDocument()
    expect(within(detail).queryByRole('button', { name: '재작업' })).not.toBeInTheDocument()
  })

  it('다른 보류 인시던트를 고르면 판단 사유 입력이 비어 있다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([
      incidentRow({ incidentId: 'incident-5', executionId: 'exec-2' }),
      incidentRow({ incidentId: 'incident-3' }),
    ])
    fake.incidentDetails.set('incident-5', incidentDetail({ incidentId: 'incident-5', executionId: 'exec-2' }))
    fake.incidentDetails.set('incident-3', incidentDetail({ incidentId: 'incident-3' }))
    open()
    let detail = await select('incident-3')
    await userEvent.type(await within(detail).findByLabelText('판단 사유'), 'exec-1 랙 확인')
    detail = await select('incident-5')
    const form = await within(detail).findByRole('form', { name: '운영자 판단' })
    expect(form).toHaveTextContent('exec-2/rack-arrival 보류 해소 방식(완료 확인 또는 재작업)을 결정합니다')
    expect(within(form).getByLabelText('판단 사유')).toHaveValue('')
  })

  it('판단 사유가 비었거나 공백뿐이면 보내지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([incidentRow()])
    fake.incidentDetails.set('incident-1', incidentDetail())
    open()
    const detail = await select('incident-1')
    await userEvent.click(await within(detail).findByRole('button', { name: '재작업' }))
    expect(within(detail).getByRole('alert')).toHaveTextContent('판단 사유를 넣으십시오')
    await userEvent.type(within(detail).getByLabelText('판단 사유'), '   ')
    await userEvent.click(within(detail).getByRole('button', { name: '완료 확인' }))
    expect(within(detail).getByRole('alert')).toHaveTextContent('판단 사유를 넣으십시오')
    expect(fake.calls.filter((call) => call.method === 'POST' && call.url.endsWith('/resolve'))).toEqual([])
  })

  it('판단은 실행과 단위 경로로 결정과 사유만 운영자 모드로 보내고 Resolved 를 보인 뒤 곧바로와 pump 뒤 다시 읽는다', async () => {
    // 시계는 손으로만 민다. 대역의 fetch 는 타이머를 쓰지 않으므로 act 로 약속만 흘려보낸다.
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([incidentRow({ executionId: 'exec 1', unitId: 'rack/arrival' })])
    fake.incidentDetails.set('incident-1', incidentDetail({ executionId: 'exec 1', unitId: 'rack/arrival' }))
    const path = '/api/executions/exec%201/units/rack%2Farrival/resolve'
    fake.answers.set(path, { status: 200, body: holdResolved({ executionId: 'exec 1', unitId: 'rack/arrival' }) })
    const flush = async () => {
      for (let round = 0; round < 5; round++) await act(async () => undefined)
    }
    open()
    await flush()
    fireEvent.click(screen.getByRole('button', { name: 'incident-1 상세 보기' }))
    await flush()
    const detail = screen.getByRole('region', { name: '인시던트 상세' })
    fireEvent.change(within(detail).getByLabelText('판단 사유'), { target: { value: '  랙 재배치 뒤 재작업 ' } })
    const reads = () => ({
      incidents: calls(fake, 'GET', '/api/incidents').length,
      executions: calls(fake, 'GET', EXECUTIONS_RECENT_PATH).length,
      detail: calls(fake, 'GET', '/api/incidents/incident-1?instanceId=mw-1').length,
    })
    const before = reads()
    fireEvent.click(within(detail).getByRole('button', { name: '재작업' }))
    await flush()

    expect(screen.getByRole('status', { name: '판단 결과' })).toHaveTextContent(
      'exec 1/rack/arrival 재작업: 판단이 섰습니다(incident-1)',
    )
    const post = calls(fake, 'POST', path).at(-1)!
    expect(post.body).toEqual({ decision: 'REWORK', instanceId: 'mw-1', reason: '랙 재배치 뒤 재작업' })
    expect(post.headers['X-Ops-Mode']).toBe('operator')
    expect(post.headers['X-Ops-User']).toBe('kim')
    const now = reads()
    expect(now).toEqual({
      incidents: before.incidents + 1,
      executions: before.executions + 1,
      detail: before.detail + 1,
    })
    await act(async () => vi.advanceTimersByTime(SIGNAL_SETTLE_MS))
    await flush()
    expect(reads()).toEqual({ incidents: now.incidents + 1, executions: now.executions + 1, detail: now.detail + 1 })
    expect(within(detail).getByLabelText('판단 사유')).toHaveValue('')
  })

  it.each<[HoldResolveOutcome | { status: number; body: unknown }, string]>([
    [
      holdResolved({
        result: 'REJECTED',
        outcome: 'NotHeld',
        answer: { result: 'NotHeld', detail: null, incidentId: null, requestId: null },
      }),
      'exec-1/rack-arrival 재작업: 보류 단위가 아닙니다',
    ],
    [
      holdResolved({
        result: 'REJECTED',
        outcome: 'Refused',
        answer: { result: 'Refused', detail: '에이전트는 운영자 판단을 내지 못한다', incidentId: null, requestId: null },
      }),
      'exec-1/rack-arrival 재작업: 실행 호스트가 판단을 거부했습니다. 에이전트는 운영자 판단을 내지 못한다',
    ],
    [
      holdResolved({ result: 'NO_RESPONSE', outcome: null, answer: null, confirmation: 'CONFIRMED_APPLIED' }),
      'exec-1/rack-arrival 재작업: 응답은 없었으나 다시 읽어 보니 판단이 붙음',
    ],
    [
      holdResolved({ result: 'NO_RESPONSE', outcome: null, answer: null, confirmation: 'CONFIRMED_NOT_APPLIED' }),
      'exec-1/rack-arrival 재작업: 응답 없음. 다시 읽어 보니 판단이 붙지 않음',
    ],
    [
      holdResolved({ result: 'NO_RESPONSE', outcome: null, answer: null, confirmation: null }),
      'exec-1/rack-arrival 재작업: 응답 없음. 판단이 붙었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다',
    ],
    [
      // 거부 본문을 읽지 못한 호스트 4xx 는 이름과 문장이 없다.
      holdResolved({ result: 'REJECTED', outcome: null, answer: null, rejection: { status: 415, error: null, detail: null } }),
      'exec-1/rack-arrival 재작업: 실행 호스트가 거부함(이유 없음)',
    ],
    [
      { status: 403, body: { error: 'MODE_NOT_ALLOWED', detail: '이 조작은 operator 모드에서 한다' } },
      'exec-1/rack-arrival 재작업: 보내지 않음(이 모드에서 할 수 없는 조작). 이 조작은 operator 모드에서 한다',
    ],
  ])('판단 결과는 outcome 으로 가르고 NotHeld·Refused·응답 없음·사전 거부는 판단이 섰다고 보이지 않는다(%#)', async (answer, text) => {
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([incidentRow()])
    fake.incidentDetails.set('incident-1', incidentDetail())
    fake.answers.set(RESOLVE, 'status' in answer ? answer : { status: 200, body: answer })
    open()
    const detail = await select('incident-1')
    await userEvent.type(await within(detail).findByLabelText('판단 사유'), '확인')
    await userEvent.click(within(detail).getByRole('button', { name: '재작업' }))
    const shown = await screen.findByRole('status', { name: '판단 결과' })
    expect(shown).toHaveTextContent(text)
    expect(shown).not.toHaveTextContent('판단이 섰습니다')
    expect(shown).not.toHaveTextContent('null')
  })

  it('주기마다 인시던트 목록과 고른 상세를 다시 읽는다', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([incidentRow()])
    fake.incidentDetails.set('incident-1', incidentDetail())
    const flush = async () => {
      for (let round = 0; round < 5; round++) await act(async () => undefined)
    }
    open()
    await flush()
    fireEvent.click(screen.getByRole('button', { name: 'incident-1 상세 보기' }))
    await flush()
    const list = calls(fake, 'GET', '/api/incidents').length
    const detail = calls(fake, 'GET', '/api/incidents/incident-1?instanceId=mw-1').length
    expect(detail).toBe(1)
    await act(async () => vi.advanceTimersByTime(POLL_MS))
    await flush()
    expect(calls(fake, 'GET', '/api/incidents')).toHaveLength(list + 1)
    expect(calls(fake, 'GET', '/api/incidents/incident-1?instanceId=mw-1')).toHaveLength(detail + 1)

    // 다시 읽은 상세가 판단을 실으면 그대로 바뀐다.
    fake.incidentDetails.set('incident-1', incidentDetail({ held: false, resolution: rework }))
    await act(async () => vi.advanceTimersByTime(POLL_MS))
    await flush()
    expect(screen.getByRole('region', { name: '사람의 판단' })).toHaveTextContent('사람이 판단함: kim, w20')
  })

  it('운영 영역을 열기 전에는 인시던트를 읽지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    render(<App />)
    expect(await screen.findByText('선언된 기체가 없습니다')).toBeInTheDocument()
    expect(fake.calls.filter((call) => call.url.startsWith('/api/incidents'))).toEqual([])
    await userEvent.click(screen.getByRole('button', { name: '운영' }))
    expect(await screen.findByText('인시던트가 없습니다')).toBeInTheDocument()
    expect(calls(fake, 'GET', '/api/incidents').length).toBeGreaterThan(0)
  })

  it('실행 호스트가 503 이면 목록은 직전 값과 불통을 보이고 호스트에 없는 인시던트의 상세는 없음이다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([incidentRow()])
    const { rerender } = open()
    const region = screen.getByRole('region', { name: '인시던트' })
    await within(region).findByRole('table', { name: '인시던트 목록' })
    const detail = await select('incident-1')
    expect(
      await within(detail).findByText('실행 호스트에 이 인시던트가 없습니다. 실행 호스트를 재기동했을 수 있습니다'),
    ).toBeInTheDocument()

    fake.failing.add('/api/incidents')
    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
    expect(
      await within(region).findByText(
        '직전 값입니다. 실행 호스트 불통: 실행 호스트가 답하지 않는다: 응답 없음: ConnectException',
      ),
    ).toBeInTheDocument()
    expect(within(region).getByRole('row', { name: /incident-1/ })).toBeInTheDocument()
  })

  it('인시던트 목록을 한 번도 못 읽으면 모름이다', async () => {
    const fake = installFakeOps(emptyList)
    fake.failing.add('/api/incidents')
    open()
    expect(
      await screen.findByText(
        '모름: 인시던트 목록을 아직 읽지 못했습니다 (실행 호스트가 답하지 않는다: 응답 없음: ConnectException)',
      ),
    ).toBeInTheDocument()
  })
})

describe('재기동 뒤 인시던트', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('이전 인스턴스 부분은 earlier 사본을 인스턴스 열과 함께 읽기 전용으로 보이고 보류 열이 없다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = {
      ...incidentsView([incidentRow()]),
      earlierTotal: 4,
      earlier: [
        earlierRow(),
        earlierRow({ instanceId: 'mw-00', incidentId: 'incident-2', at: 'h3', resolution: null, held: false }),
      ],
    }
    open()
    const region = screen.getByRole('region', { name: '인시던트' })
    const earlier = await within(region).findByRole('region', { name: '이전 인스턴스' })
    expect(earlier).toHaveTextContent('재기동 앞 인스턴스의 인시던트 4건 가운데 최신 2건. 읽기 전용이며 여기서 판단하지 않습니다')
    const table = within(earlier).getByRole('table', { name: '이전 인스턴스 인시던트 목록' })
    expect(within(table).getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
      '인스턴스',
      '인시던트',
      '발생 시각',
      '열림',
      '기체',
      '실행 id',
      '단위',
      '실패 종류',
      '경로',
      '현장 설정 버전',
      '임무 버전',
      '판단',
    ])
    expect(within(table).getAllByRole('row').slice(1).map(cells)).toEqual([
      ['mw-0', 'incident-1', 'h10', '-', 'humanoid-01', 'exec-1', 'rack-arrival', 'SIGNAL_DEADLINE', 'SIGNAL', '버전 2', '버전 3', '판단됨'],
      ['mw-00', 'incident-2', 'h3', '-', 'humanoid-01', 'exec-1', 'rack-arrival', 'SIGNAL_DEADLINE', 'SIGNAL', '버전 2', '버전 3', '미해결'],
    ])
    expect(within(table).queryByText('보류 중')).toBeNull()
    // 지금 인스턴스의 같은 id 줄은 지금 표에 따로 있다.
    const current = within(region).getByRole('table', { name: '인시던트 목록' })
    expect(within(current).getAllByRole('row')).toHaveLength(2)
    expect(within(current).getByText('보류 중')).toBeInTheDocument()
  })

  it('이전 인스턴스 사본이 없으면 없음을 보이고 earlier 를 싣지 않는 목록에는 그 부분이 없다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = { ...incidentsView(), earlierTotal: 0, earlier: [] }
    const { rerender } = open()
    const earlier = await screen.findByRole('region', { name: '이전 인스턴스' })
    expect(earlier).toHaveTextContent('이전 인스턴스의 인시던트가 없습니다')
    fake.incidents = incidentsView([incidentRow()])
    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
    await screen.findByRole('table', { name: '인시던트 목록' })
    expect(screen.queryByRole('region', { name: '이전 인스턴스' })).toBeNull()
  })

  it('이전 인스턴스 줄의 상세는 그 인스턴스를 실어 읽고 보류여도 판단 폼이 없으며 지금 줄의 상세와 섞이지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = { ...incidentsView([incidentRow()]), earlierTotal: 1, earlier: [earlierRow()] }
    fake.incidentDetails.set('incident-1', incidentDetail())
    // 사본의 held 는 늘 거짓이지만 화면은 그것에 기대지 않는다.
    fake.earlierDetails.set('mw-0/incident-1', incidentDetail({ instanceId: 'mw-0', unitState: null, resolution: rework }))
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'mw-0 incident-1 상세 보기' }))
    const detail = await screen.findByRole('region', { name: '인시던트 상세' })
    expect(within(detail).getByRole('heading', { level: 3 })).toHaveTextContent('incident-1 상세(이전 인스턴스 mw-0)')
    expect(within(detail).getByText('이전 인스턴스의 사본입니다. 읽기 전용이며 판단하지 않습니다')).toBeInTheDocument()
    expect(await within(detail).findByRole('region', { name: '사람의 판단' })).toHaveTextContent('사람이 판단함: kim, w20')
    expect(field(within(detail).getByRole('region', { name: '관측' }), '단위의 지금 상태')).toBe('모름(실행 없음)')
    expect(within(detail).queryByRole('form', { name: '운영자 판단' })).toBeNull()
    expect(within(detail).queryByRole('button', { name: '재작업' })).toBeNull()
    expect(calls(fake, 'GET', '/api/incidents/incident-1?instanceId=mw-0')).toHaveLength(1)

    await userEvent.click(screen.getByRole('button', { name: 'incident-1 상세 보기' }))
    expect(await within(detail).findByRole('form', { name: '운영자 판단' })).toBeInTheDocument()
    expect(within(detail).getByRole('heading', { level: 3 })).toHaveTextContent(/^incident-1 상세$/)
    expect(calls(fake, 'GET', '/api/incidents/incident-1?instanceId=mw-1').length).toBeGreaterThan(0)
  })

  it('고른 뒤 실행 호스트가 재기동하면 같은 id 의 새 인시던트가 아니라 고른 인스턴스의 사본을 읽고 판단 폼이 내려간다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([incidentRow()])
    fake.incidentDetails.set('incident-1', incidentDetail())
    const { rerender } = open()
    const detail = await select('incident-1')
    expect(await within(detail).findByRole('form', { name: '운영자 판단' })).toBeInTheDocument()

    // 재기동: 새 인스턴스 mw-2 의 incident-1 은 다른 단위의 보류다. 앞 인스턴스의 것은 사본으로 남는다.
    fake.incidents = {
      ...incidentsView([incidentRow({ unitId: 'RACK-204.S01' })]),
      instanceId: 'mw-2',
      earlierTotal: 1,
      earlier: [earlierRow({ instanceId: 'mw-1', resolution: null })],
    }
    fake.incidentDetails.set('incident-1', incidentDetail({ instanceId: 'mw-2', unitId: 'RACK-204.S01' }))
    fake.earlierDetails.set('mw-1/incident-1', incidentDetail({ held: false, unitState: null }))
    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
    expect(await within(detail).findByText('이전 인스턴스의 사본입니다. 읽기 전용이며 판단하지 않습니다')).toBeInTheDocument()
    expect(within(detail).getByRole('heading', { level: 3 })).toHaveTextContent('incident-1 상세(이전 인스턴스 mw-1)')
    expect(field(within(detail).getByRole('region', { name: '관측' }), '단위')).toBe('rack-arrival')
    expect(within(detail).queryByRole('form', { name: '운영자 판단' })).toBeNull()
    const earlier = screen.getByRole('table', { name: '이전 인스턴스 인시던트 목록' })
    expect(within(earlier).getAllByRole('row')[1]).toHaveClass('selected')
    expect(within(screen.getByRole('table', { name: '인시던트 목록' })).getAllByRole('row')[1]).not.toHaveClass('selected')
  })

  it('재기동이 다시 연 보류는 열림 칸에 보이고 상세가 같은 사건 열쇠의 이전 인스턴스 사본만 같은 사건으로 잇는다', async () => {
    const fake = installFakeOps(emptyList)
    const origin = { incidentKey: 'mw-0/incident-1', firstOpenedAt: 'h5', attempt: 1 }
    fake.incidents = {
      ...incidentsView([incidentRow({ ...origin, openingKind: 'REOPENED' })]),
      earlierTotal: 2,
      earlier: [
        earlierRow({ ...origin, openingKind: 'NEW', at: 'h5', resolution: null }),
        earlierRow({ instanceId: 'mw-00', incidentId: 'incident-2', incidentKey: 'mw-00/incident-2', firstOpenedAt: 'h3', openingKind: 'NEW', attempt: 1 }),
      ],
    }
    fake.incidentDetails.set('incident-1', incidentDetail({ ...origin, openingKind: 'REOPENED' }))
    fake.earlierDetails.set(
      'mw-0/incident-1',
      incidentDetail({ ...origin, instanceId: 'mw-0', at: 'h5', openingKind: 'NEW', unitState: null }),
    )
    open()
    const table = await screen.findByRole('table', { name: '인시던트 목록' })
    expect(cells(within(table).getAllByRole('row')[1])[2]).toBe('재기동이 다시 엶')
    const earlier = screen.getByRole('table', { name: '이전 인스턴스 인시던트 목록' })
    expect(within(earlier).getAllByRole('row').slice(1).map((row) => cells(row)[3])).toEqual(['새로 섬', '새로 섬'])

    const detail = await select('incident-1')
    const identity = await within(detail).findByRole('region', { name: '사건 정체성' })
    expect(within(identity).getByRole('note')).toHaveTextContent(
      '재기동이 다시 연 보류입니다. 새 사건이 아니라 h5에 처음 선 보류와 같은 사건입니다',
    )
    expect(field(identity, '사건 열쇠')).toBe('mw-0/incident-1')
    expect(field(identity, '처음 선 시각')).toBe('h5')
    expect(field(identity, '시도')).toBe('1')
    const linked = within(identity).getByRole('list', { name: '같은 사건의 다른 기록' })
    expect(within(linked).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['mw-0 incident-1 새로 섬, 발생 h5'])

    await userEvent.click(within(linked).getByRole('button', { name: 'mw-0 incident-1 같은 사건 보기' }))
    expect(await screen.findByRole('heading', { name: 'incident-1 상세(이전 인스턴스 mw-0)' })).toBeInTheDocument()
    const back = await screen.findByRole('list', { name: '같은 사건의 다른 기록' })
    expect(within(back).getByRole('button', { name: 'mw-1 incident-1 같은 사건 보기' })).toBeInTheDocument()
  })

  it('열쇠 없이 다시 연 보류는 연결 불명으로 보이고 열쇠 없는 다른 줄과 잇지 않으며 정체성 칸 없는 사본은 모름이다', async () => {
    const fake = installFakeOps(emptyList)
    const unlinked = { incidentKey: null, firstOpenedAt: null, openingKind: 'REOPENED' as const, attempt: 1 }
    fake.incidents = {
      ...incidentsView([incidentRow(unlinked)]),
      earlierTotal: 2,
      earlier: [earlierRow(unlinked), earlierRow({ instanceId: 'mw-00' })],
    }
    fake.incidentDetails.set('incident-1', incidentDetail(unlinked))
    fake.earlierDetails.set('mw-00/incident-1', incidentDetail({ instanceId: 'mw-00', unitState: null, resolution: rework }))
    open()
    const table = await screen.findByRole('table', { name: '인시던트 목록' })
    expect(cells(within(table).getAllByRole('row')[1])[2]).toBe('재기동이 다시 엶(연결 불명)')
    const detail = await select('incident-1')
    const identity = await within(detail).findByRole('region', { name: '사건 정체성' })
    expect(within(identity).getByRole('note')).toHaveTextContent('원래 보류와의 연결을 모릅니다(연결 불명). 추측해 잇지 않습니다')
    expect(field(identity, '사건 열쇠')).toBe('없음(연결 불명)')
    expect(within(identity).queryByRole('list', { name: '같은 사건의 다른 기록' })).toBeNull()
    expect(identity).not.toHaveTextContent('같은 사건의 다른 기록')

    await userEvent.click(screen.getByRole('button', { name: 'mw-00 incident-1 상세 보기' }))
    expect(await screen.findByRole('region', { name: '사건 정체성' })).toHaveTextContent(
      '사건 정체성 모름: 정체성 칸이 생기기 전에 적은 기록입니다',
    )
  })

  it('판단자의 종류를 원문 id 와 함께 보인다', async () => {
    const fake = installFakeOps(emptyList)
    const byAgent: IncidentResolution = { ...rework, decidedBy: { id: 'agent-7', kind: 'AGENT' } }
    fake.incidents = incidentsView([incidentRow({ held: false, resolution: byAgent })])
    fake.incidentDetails.set('incident-1', incidentDetail({ held: false, resolution: byAgent }))
    open()
    const detail = await select('incident-1')
    const asserted = await within(detail).findByRole('region', { name: '사람의 판단' })
    expect(asserted).toHaveTextContent('에이전트가 판단함: agent-7, w20')
    expect(asserted).not.toHaveTextContent('사람이 판단함')
  })

  it('판단은 상세의 instanceId 를 싣고 실행 호스트가 409 INSTANCE_MISMATCH 로 막으면 그 이름을 풀어 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([incidentRow()])
    fake.incidentDetails.set('incident-1', incidentDetail())
    fake.answers.set(RESOLVE, {
      status: 200,
      body: holdResolved({
        result: 'REJECTED',
        outcome: null,
        answer: null,
        rejection: { status: 409, error: 'INSTANCE_MISMATCH', detail: '판단 요청의 인스턴스(mw-1)가 지금 인스턴스(mw-2)가 아니다' },
      }),
    })
    open()
    const detail = await select('incident-1')
    await userEvent.type(await within(detail).findByLabelText('판단 사유'), '재작업')
    await userEvent.click(within(detail).getByRole('button', { name: '재작업' }))
    expect(await screen.findByRole('status', { name: '판단 결과' })).toHaveTextContent(
      'exec-1/rack-arrival 재작업: 실행 호스트가 거부함(실행 호스트가 재기동해 인스턴스가 다름). 판단 요청의 인스턴스(mw-1)가 지금 인스턴스(mw-2)가 아니다',
    )
    expect(calls(fake, 'POST', RESOLVE).at(-1)!.body).toEqual({ decision: 'REWORK', instanceId: 'mw-1', reason: '재작업' })
  })
})
