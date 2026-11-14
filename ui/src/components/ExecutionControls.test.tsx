import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  Execution,
  ExecutionCancelOutcome,
  ExecutionReleaseOutcome,
  FaultDetail,
  HoldResolveOutcome,
  ResidualHold,
  ReworkableTarget,
  RobotEmptyOutcome,
  Session,
} from '../api'
import { CANCEL_RESULT_LABEL, EXECUTION_CONTROL_TEXT as TEXT, RELEASE_RESULT_LABEL } from '../labels'
import { executionsView, holdResolved, installFakeOps } from '../testing/fakeOps'
import { ResidualHolds } from './ExecutionControls'
import { OperationsArea } from './OperationsArea'

const operator: Session = { mode: 'operator', user: 'kim' }
const engineer: Session = { mode: 'engineer', user: 'lee' }
const emptyList = { registry: 'OK' as const, checkedAt: 't1', robots: [], robotsAsOf: 't1' }
const CANCEL = '/api/executions/exec-1/cancel'
const CLEAR = '/api/robots/humanoid-01/residual-holds/7/clear'
const RELEASE = '/api/executions/exec-2/release'

/** 다음 단위를 막는 로봇 수준 결함(S3a JSON 계약 §5.9 예). */
const localization: FaultDetail = {
  failureClass: 'LOCALIZATION_LOST',
  errorType: 'LOCALIZATION_LOST',
  vendorDetail: 'lidar lost',
  errorHint: '로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.',
  references: [{ key: 'KEY_TASK_ID', value: 'JO-2#T1' }],
  canContinueCurrentTask: false,
  canAcceptNewTask: false,
  activeUntilKind: 'KIND_UNTIL_CLEARED',
  activeUntilTime: '',
}

function execution(partial: Partial<Execution> = {}): Execution {
  return {
    executionId: 'exec-1',
    jobOrderId: 'JO-1',
    workMasterId: 'PrepareSequencedRack',
    missionVersion: null,
    robotId: 'humanoid-01',
    physicalState: 'PARTIAL',
    units: [{ unitId: 'RACK-204.S01', skillType: 'pick_place', state: 'DONE', reached: 'E0' }],
    jobResponse: null,
    restoredFrom: null,
    cancel: null,
    ...partial,
  }
}

function hold(partial: Partial<ResidualHold> = {}): ResidualHold {
  return {
    markerId: 7,
    robotId: 'humanoid-01',
    jobOrderId: 'JO-1',
    executionId: 'exec-1',
    instanceId: 'mw-1',
    unitId: 'RACK-204.S02',
    objectRef: 'C-1',
    recordedAt: '2026-10-13T09:00:00Z',
    ...partial,
  }
}

function canceled(partial: Partial<ExecutionCancelOutcome> = {}): ExecutionCancelOutcome {
  return {
    requestId: '3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10',
    executionId: 'exec-1',
    result: 'SUCCEEDED',
    confirmation: null,
    outcome: 'CANCELED',
    answer: { result: 'CANCELED', executionId: 'exec-1', physicalState: 'PARTIAL', detail: null, requestId: null },
    rejection: null,
    ...partial,
  }
}

function released(partial: Partial<ExecutionReleaseOutcome> = {}): ExecutionReleaseOutcome {
  return {
    requestId: '3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10',
    executionId: 'exec-2',
    result: 'SUCCEEDED',
    confirmation: null,
    outcome: 'RELEASED',
    answer: { result: 'RELEASED', executionId: 'exec-2', physicalState: 'RUNNING', detail: null, requestId: '3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10' },
    rejection: null,
    ...partial,
  }
}

/** 실행 둘: 막힘 없이 도는 exec-1, 결함으로 막힌 exec-2. */
const blockedPair = () => [
  execution({ physicalState: 'RUNNING', blockedFaults: [], releases: [] }),
  execution({ executionId: 'exec-2', jobOrderId: 'JO-2', robotId: 'quadruped-01', physicalState: 'OPERATOR_HOLD', blockedFaults: [localization], releases: [] }),
]

function emptied(partial: Partial<RobotEmptyOutcome> = {}): RobotEmptyOutcome {
  return {
    requestId: '3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10',
    robotId: 'humanoid-01',
    markerId: 7,
    result: 'SUCCEEDED',
    confirmation: null,
    answer: {},
    rejection: null,
    ...partial,
  }
}

const open = (session: Session = operator) => render(<OperationsArea session={session} onChanged={() => undefined} />)
const posts = (fake: ReturnType<typeof installFakeOps>, path: string) => fake.calls.filter((call) => call.method === 'POST' && call.url === path)

describe('운영자 실행 취소', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('사유와 현장 확인이 있어야 보내고 승인자 없이 인스턴스와 사유만 싣는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: [execution()] })
    fake.answers.set(CANCEL, { status: 200, body: canceled() })
    open()
    const form = await screen.findByRole('form', { name: TEXT.cancelHeading })
    const button = within(form).getByRole('button', { name: TEXT.cancelButton })
    expect(form).toHaveTextContent(TEXT.partialNote)
    expect(button).toBeDisabled()
    await userEvent.selectOptions(within(form).getByLabelText(TEXT.cancelTarget), 'exec-1')
    await userEvent.type(within(form).getByLabelText(TEXT.cancelReason), ' 둘째 슬롯 결품 ')
    expect(button).toBeDisabled()
    await userEvent.click(within(form).getByLabelText(TEXT.cancelConfirm))
    await userEvent.click(button)

    expect(posts(fake, CANCEL).map((call) => call.body)).toEqual([{ instanceId: 'mw-1', reason: '둘째 슬롯 결품' }])
    expect(await within(form).findByRole('status', { name: '실행 취소 결과' })).toHaveTextContent(
      `exec-1(JO-1) ${TEXT.cancelHeading}: ${CANCEL_RESULT_LABEL.CANCELED}`,
    )
  })

  it('정착했거나 이미 취소를 건 실행은 고를 수 없고 건 취소는 물리 상태 칸에 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({
      executions: [
        execution({ executionId: 'exec-1', physicalState: 'ABORTED' }),
        execution({
          executionId: 'exec-2',
          jobOrderId: 'JO-2',
          physicalState: 'CANCELING',
          cancel: { approverId: 'kim', approverKind: 'PERSON', reason: '결품', physicalState: 'PARTIAL', recordedAt: 't2' },
        }),
        execution({ executionId: 'exec-3', jobOrderId: 'JO-3', physicalState: 'RUNNING' }),
      ],
    })
    open()
    const form = await screen.findByRole('form', { name: TEXT.cancelHeading })
    const options = within(within(form).getByLabelText(TEXT.cancelTarget)).getAllByRole('option').map((option) => option.getAttribute('value'))
    expect(options).toEqual(['', 'exec-3'])
    const table = screen.getByRole('table', { name: '실행 목록' })
    expect(within(table).getByText(/취소 요청: kim, 결품/)).toBeInTheDocument()
  })

  it('실행 호스트의 4xx 거부는 그 이름으로 알린다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: [execution()] })
    fake.answers.set(CANCEL, {
      status: 200,
      body: canceled({ result: 'REJECTED', outcome: null, answer: null, rejection: { status: 409, error: 'INSTANCE_MISMATCH', detail: '인스턴스가 다르다' } }),
    })
    open()
    const form = await screen.findByRole('form', { name: TEXT.cancelHeading })
    await userEvent.selectOptions(within(form).getByLabelText(TEXT.cancelTarget), 'exec-1')
    await userEvent.type(within(form).getByLabelText(TEXT.cancelReason), '닫기')
    await userEvent.click(within(form).getByLabelText(TEXT.cancelConfirm))
    await userEvent.click(within(form).getByRole('button', { name: TEXT.cancelButton }))
    expect(await within(form).findByRole('status', { name: '실행 취소 결과' })).toHaveTextContent('실행 호스트가 재기동해 인스턴스가 다름')
  })

  it('엔지니어 모드에는 취소 폼이 없고 운영자 모드에서 한다는 안내만 있다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: [execution()] })
    open(engineer)
    await screen.findByRole('table', { name: '실행 목록' })
    expect(screen.queryByRole('form', { name: TEXT.cancelHeading })).toBeNull()
    expect(screen.getByText(TEXT.cancelOperatorOnly)).toBeInTheDocument()
  })
})

describe('운영자 결함 감수', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  /** 감수 폼에서 exec-2 를 고르고 사유와 현장 확인을 넣어 보낸다. */
  async function release(form: HTMLElement, reason = '재측위 뒤 계속') {
    await userEvent.selectOptions(within(form).getByLabelText(TEXT.releaseTarget), 'exec-2')
    await userEvent.type(within(form).getByLabelText(TEXT.releaseReason), reason)
    await userEvent.click(within(form).getByLabelText(TEXT.releaseConfirm))
    await userEvent.click(within(form).getByRole('button', { name: TEXT.releaseButton }))
  }

  it('막는 결함이 있는 실행만 고르고 고른 실행의 결함을 카드로 보이며 사유와 현장 확인이 있어야 본 결함을 실어 보낸다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: blockedPair() })
    fake.answers.set(RELEASE, { status: 200, body: released() })
    open()
    const form = await screen.findByRole('form', { name: TEXT.releaseHeading })
    expect(form).toHaveTextContent(TEXT.releaseIntro)
    expect(form).toHaveTextContent(TEXT.releasePermitNote)
    const options = within(within(form).getByLabelText(TEXT.releaseTarget)).getAllByRole('option').map((option) => option.textContent)
    expect(options).toEqual([TEXT.releaseChoose, 'exec-2 · JO-2 · quadruped-01'])
    const button = within(form).getByRole('button', { name: TEXT.releaseButton })
    expect(button).toBeDisabled()
    expect(within(form).queryByRole('group', { name: TEXT.releaseFaults })).toBeNull()

    await userEvent.selectOptions(within(form).getByLabelText(TEXT.releaseTarget), 'exec-2')
    const card = within(within(form).getByRole('group', { name: TEXT.releaseFaults })).getByLabelText(`${TEXT.releaseFaults} 1`)
    expect(card).toHaveTextContent('LOCALIZATION_LOST(LOCALIZATION_LOST)')
    expect(card).toHaveTextContent('KEY_TASK_ID=JO-2#T1')
    expect(card).toHaveTextContent('lidar lost')
    await userEvent.type(within(form).getByLabelText(TEXT.releaseReason), ' 재측위 뒤 계속 ')
    expect(button).toBeDisabled()
    await userEvent.click(within(form).getByLabelText(TEXT.releaseConfirm))
    await userEvent.click(button)

    expect(posts(fake, RELEASE).map((call) => call.body)).toEqual([
      { instanceId: 'mw-1', reason: '재측위 뒤 계속', sawFaults: [{ errorType: 'LOCALIZATION_LOST', references: [{ key: 'KEY_TASK_ID', value: 'JO-2#T1' }] }] },
    ])
    expect(await within(form).findByRole('status', { name: '결함 수용 결과' })).toHaveTextContent(
      `exec-2(JO-2) ${TEXT.releaseHeading}: ${RELEASE_RESULT_LABEL.RELEASED}`,
    )
    expect(within(form).getByLabelText(TEXT.releaseTarget)).toHaveValue('')
  })

  it('실행 호스트 결과마다 그 풀이를 보이고 작업 허가 대기와 바뀐 결함의 사실 문장을 붙인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: blockedPair() })
    open()
    const form = await screen.findByRole('form', { name: TEXT.releaseHeading })
    const status = () => within(form).getByRole('status', { name: '결함 수용 결과' })
    const permit = '작업 허가 대기 중이다. 수용으로 풀리지 않는다: T2 (START)'
    const changed = '본 결함과 지금 막는 결함이 다르다. 다시 읽고 판단한다: PAYLOAD_LOST[]'
    const base = released().answer!
    const cases: [ExecutionReleaseOutcome, string][] = [
      [released({ answer: { ...base, detail: permit } }), `${RELEASE_RESULT_LABEL.RELEASED}. ${permit}`],
      [released({ result: 'REJECTED', outcome: 'NOT_BLOCKED', answer: { ...base, result: 'NOT_BLOCKED', detail: permit } }), `${RELEASE_RESULT_LABEL.NOT_BLOCKED}. ${permit}`],
      [released({ result: 'REJECTED', outcome: 'BLOCK_CHANGED', answer: { ...base, result: 'BLOCK_CHANGED', detail: changed } }), `${RELEASE_RESULT_LABEL.BLOCK_CHANGED}. ${changed}`],
      [released({ result: 'REJECTED', outcome: 'NOT_FOUND', answer: { ...base, result: 'NOT_FOUND' } }), RELEASE_RESULT_LABEL.NOT_FOUND],
      [released({ result: 'REJECTED', outcome: 'REFUSED', answer: { ...base, result: 'REFUSED' } }), RELEASE_RESULT_LABEL.REFUSED],
      [
        released({ result: 'REJECTED', outcome: null, answer: null, rejection: { status: 409, error: 'REQUEST_ID_REUSED', detail: '결함 수용의 요청 id 가 이미 쓰였다' } }),
        '실행 호스트가 거부함(요청 id 재사용). 결함 수용의 요청 id 가 이미 쓰였다',
      ],
    ]
    for (const [answer, text] of cases) {
      fake.answers.set(RELEASE, { status: 200, body: answer })
      await release(form)
      await waitFor(() => expect(status()).toHaveTextContent(`exec-2(JO-2) ${TEXT.releaseHeading}: ${text}`))
    }
    fake.answers.set(RELEASE, { status: 400, body: { error: 'RELEASE_BAD_REQUEST', detail: 'sawFaults 가 틀리다' } })
    await release(form)
    await waitFor(() => expect(status()).toHaveTextContent('보내지 않음(수용 본문 오류). sawFaults 가 틀리다'))
  })

  it('응답이 없으면 다시 읽은 결과 셋을 가르고 운영 서비스가 답하지 않으면 결과 모름이다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: blockedPair() })
    open()
    const form = await screen.findByRole('form', { name: TEXT.releaseHeading })
    const status = () => within(form).getByRole('status', { name: '결함 수용 결과' })
    const silent = { result: 'NO_RESPONSE' as const, outcome: null, answer: null }
    const cases: [ExecutionReleaseOutcome, string][] = [
      [released({ ...silent, confirmation: 'CONFIRMED_APPLIED' }), TEXT.releaseApplied],
      [released({ ...silent, confirmation: 'CONFIRMED_NOT_APPLIED' }), TEXT.releaseNotApplied],
      [released({ ...silent, confirmation: null }), TEXT.releaseUnconfirmed],
    ]
    for (const [answer, text] of cases) {
      fake.answers.set(RELEASE, { status: 200, body: answer })
      await release(form)
      await waitFor(() => expect(status()).toHaveTextContent(`exec-2(JO-2) ${TEXT.releaseHeading}: ${text}`))
    }
    fake.answers.set(RELEASE, { status: 502, body: {} })
    await release(form)
    await waitFor(() => expect(status()).toHaveTextContent('결과 모름(운영 서비스 응답 502). 실행 목록에서 확인하십시오'))
  })

  it('엔지니어 모드에는 감수 폼이 없고 막힌 실행이 있을 때만 운영자 모드에서 한다는 안내가 있으며 막힌 실행이 없으면 운영자 모드에도 폼이 없다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: blockedPair() })
    const blockedView = open(engineer)
    await screen.findByRole('table', { name: '실행 목록' })
    expect(screen.queryByRole('form', { name: TEXT.releaseHeading })).toBeNull()
    expect(screen.getByText(TEXT.releaseOperatorOnly)).toBeInTheDocument()
    blockedView.unmount()

    fake.executions = executionsView({ executions: [execution({ physicalState: 'RUNNING', blockedFaults: [] })] })
    const engineerView = open(engineer)
    await screen.findByRole('table', { name: '실행 목록' })
    expect(screen.queryByText(TEXT.releaseOperatorOnly)).toBeNull()
    engineerView.unmount()
    open()
    await screen.findByRole('form', { name: TEXT.cancelHeading })
    expect(screen.queryByRole('form', { name: TEXT.releaseHeading })).toBeNull()
  })

  it('물리 상태 칸에 지금 막는 결함의 수와 그 작업 지시에 건 감수를 보이고 작업 허가 대기 표시와 섞지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    const earlier = { requestId: null, approverId: 'park', reason: '첫 재측위', faults: [localization], recordedAt: '2026-10-17T01:00:00Z' }
    const later = { requestId: 'r-2', approverId: 'kim', reason: '둘째 재측위', faults: [localization], recordedAt: '2026-10-17T02:00:00Z' }
    fake.executions = executionsView({
      executions: [
        execution({ physicalState: 'RUNNING', blockedFaults: [], releases: [earlier] }),
        execution({
          executionId: 'exec-2',
          jobOrderId: 'JO-2',
          physicalState: 'OPERATOR_HOLD',
          blockedFaults: [localization, { ...localization, errorType: 'PAYLOAD_LOST' }],
          releases: [earlier, later],
        }),
      ],
    })
    open()
    const table = await screen.findByRole('table', { name: '실행 목록' })
    const [, blocked, running] = within(table).getAllByRole('row')
    const state = (row: HTMLElement) => within(row).getAllByRole('cell')[5]
    expect(state(blocked).textContent).toBe(`OPERATOR_HOLD${TEXT.blockedCount(2)} (${TEXT.releasedBy}: park, 첫 재측위) (${TEXT.releasedBy}: kim, 둘째 재측위)`)
    expect(state(running).textContent).toBe(`RUNNING (${TEXT.releasedBy}: park, 첫 재측위)`)
    expect(within(table).queryByRole('region', { name: '작업 허가 대기' })).toBeNull()
  })
})

describe('건너뛴 대상 재작업', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  const REWORK_PARTIAL = '/api/executions/exec-1/units/T1/resolve'
  const REWORK_FAILED = '/api/executions/exec-2/units/T3/resolve'

  /** 호스트가 싣는 재작업 대상 둘: 기체를 쥔 PARTIAL 의 T1, 보존 기간이 있는 FAILED 의 T3. */
  const targets = (): ReworkableTarget[] => [
    { executionId: 'exec-1', jobOrderId: 'JO-1', robotId: 'humanoid-01', unitId: 'T1', physicalState: 'PARTIAL', retainedUntil: null },
    { executionId: 'exec-2', jobOrderId: 'JO-2', robotId: 'quadruped-01', unitId: 'T3', physicalState: 'FAILED', retainedUntil: '2026-10-19T09:05:00Z' },
  ]

  /** 대상이 있는 호스트. 남은 실행에는 대상이 아닌 도는 실행도 있다. */
  function withTargets() {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({
      executions: [execution(), execution({ executionId: 'exec-3', jobOrderId: 'JO-3', physicalState: 'RUNNING' })],
    })
    fake.recent = { settled: [], nextBefore: null, olderTotal: 0, reworkableTargets: targets() }
    return fake
  }

  const resolved = (partial: Partial<HoldResolveOutcome> = {}) => holdResolved({ unitId: 'T1', ...partial })

  /** 폼에서 [option] 을 골라 사유와 현장 확인을 넣어 보낸다. */
  async function rework(form: HTMLElement, option = 'exec-1/T1', reason = '확인') {
    await userEvent.selectOptions(within(form).getByLabelText(TEXT.reworkTarget), option)
    await userEvent.type(within(form).getByLabelText(TEXT.reworkReason), reason)
    await userEvent.click(within(form).getByLabelText(TEXT.reworkConfirm))
    await userEvent.click(within(form).getByRole('button', { name: TEXT.reworkButton }))
  }

  it('호스트가 싣는 대상만 고르게 하고 사유와 현장 확인이 있어야 건너뛴 점검 id 와 인스턴스로 REWORK 를 보낸다', async () => {
    const fake = withTargets()
    fake.answers.set(REWORK_PARTIAL, { status: 200, body: resolved() })
    open()
    const form = await screen.findByRole('form', { name: TEXT.reworkHeading })
    expect(form).toHaveTextContent(TEXT.reworkIntro)
    expect(form).toHaveTextContent(TEXT.reworkSiteNote)
    // 남은 실행(도는 exec-3, PARTIAL exec-1)이 아니라 호스트 목록의 대상만 고른다. 남은 실행에 없는 FAILED 대상도 든다.
    const options = within(within(form).getByLabelText(TEXT.reworkTarget)).getAllByRole('option').map((option) => option.textContent)
    expect(options).toEqual([
      TEXT.reworkChoose,
      TEXT.reworkOption('exec-1', 'JO-1', 'humanoid-01', 'T1'),
      TEXT.reworkOption('exec-2', 'JO-2', 'quadruped-01', 'T3'),
    ])
    const button = within(form).getByRole('button', { name: TEXT.reworkButton })
    expect(button).toBeDisabled()

    await userEvent.selectOptions(within(form).getByLabelText(TEXT.reworkTarget), 'exec-1/T1')
    await userEvent.type(within(form).getByLabelText(TEXT.reworkReason), ' 경로 막힘 해소 확인 ')
    expect(button).toBeDisabled()
    await userEvent.click(within(form).getByLabelText(TEXT.reworkConfirm))
    expect(button).toBeEnabled()
    await userEvent.click(button)

    // 승인자 없이 결정·인스턴스·사유만 싣고, 사유 앞뒤 공백은 뗀다. 인시던트 판단과 같은 길이라 단위는 건너뛴 점검 id 다.
    expect(posts(fake, REWORK_PARTIAL).map((call) => call.body)).toEqual([{ decision: 'REWORK', instanceId: 'mw-1', reason: '경로 막힘 해소 확인' }])
    expect(await within(form).findByRole('status', { name: `${TEXT.reworkHeading} 결과` })).toHaveTextContent(
      `exec-1(JO-1) ${TEXT.reworkHeading}: ${TEXT.reworkResolved('incident-1')}`,
    )
    expect(within(form).getByLabelText(TEXT.reworkTarget)).toHaveValue('')
  })

  it('사유가 비어 있으면 현장 확인을 해도 보내지 않는다', async () => {
    const fake = withTargets()
    open()
    const form = await screen.findByRole('form', { name: TEXT.reworkHeading })
    await userEvent.selectOptions(within(form).getByLabelText(TEXT.reworkTarget), 'exec-1/T1')
    await userEvent.type(within(form).getByLabelText(TEXT.reworkReason), '   ')
    await userEvent.click(within(form).getByLabelText(TEXT.reworkConfirm))
    const button = within(form).getByRole('button', { name: TEXT.reworkButton })
    expect(button).toBeDisabled()
    await userEvent.click(button)
    expect(fake.calls.filter((call) => call.method === 'POST')).toEqual([])
  })

  it('FAILED 대상은 보존 만료 시각을, PARTIAL 대상은 만료 없음을 고른 뒤에 보인다', async () => {
    withTargets()
    open()
    const form = await screen.findByRole('form', { name: TEXT.reworkHeading })
    expect(form).not.toHaveTextContent(TEXT.reworkExpiry('2026-10-19T09:05:00Z'))
    await userEvent.selectOptions(within(form).getByLabelText(TEXT.reworkTarget), 'exec-2/T3')
    expect(form).toHaveTextContent(TEXT.reworkExpiry('2026-10-19T09:05:00Z'))
    await userEvent.selectOptions(within(form).getByLabelText(TEXT.reworkTarget), 'exec-1/T1')
    expect(form).toHaveTextContent(TEXT.reworkNoExpiry)
    expect(form).not.toHaveTextContent('2026-10-19T09:05:00Z')
  })

  it('결과마다 그 풀이를 보이고 거부 사유 앞에 에이전트 문구를 붙이지 않으며 이빅션과 그 밖의 NotHeld 를 가른다', async () => {
    const fake = withTargets()
    open()
    const form = await screen.findByRole('form', { name: TEXT.reworkHeading })
    const status = () => within(form).getByRole('status', { name: `${TEXT.reworkHeading} 결과` })
    const base = { executionId: 'exec-1', unitId: 'T1', requestId: null, incidentId: null }
    const silent = { result: 'NO_RESPONSE' as const, outcome: null, answer: null }
    const reason = '같은 기체에 다른 활성 실행이 있다 — 그 실행이 끝난 뒤 재작업한다: exec-9'
    const cases: [HoldResolveOutcome, string][] = [
      [resolved({ answer: { ...base, result: 'Resolved', detail: null, incidentId: 'incident-7' } }), TEXT.reworkResolved('incident-7')],
      [resolved({ result: 'REJECTED', outcome: 'Refused', answer: { ...base, result: 'Refused', detail: reason } }), TEXT.reworkRefused(reason)],
      [resolved({ result: 'REJECTED', outcome: 'NotHeld', answer: { ...base, result: 'NotHeld', detail: 'EVICTED' } }), TEXT.reworkEvicted],
      [resolved({ result: 'REJECTED', outcome: 'NotHeld', answer: { ...base, result: 'NotHeld', detail: null } }), TEXT.reworkNotSkipped],
      [
        resolved({ result: 'REJECTED', outcome: null, answer: null, rejection: { status: 409, error: 'INSTANCE_MISMATCH', detail: '인스턴스가 다르다' } }),
        '실행 호스트가 거부함(실행 호스트가 재기동해 인스턴스가 다름). 인스턴스가 다르다',
      ],
      [resolved({ ...silent, confirmation: 'CONFIRMED_APPLIED' }), TEXT.reworkApplied],
      [resolved({ ...silent, confirmation: 'CONFIRMED_NOT_APPLIED' }), TEXT.reworkNotApplied],
      [resolved({ ...silent, confirmation: null }), TEXT.reworkUnconfirmed],
    ]
    for (const [answer, text] of cases) {
      fake.answers.set(REWORK_PARTIAL, { status: 200, body: answer })
      await rework(form)
      await waitFor(() => expect(status()).toHaveTextContent(`exec-1(JO-1) ${TEXT.reworkHeading}: ${text}`))
    }
    // 에이전트 문구는 어느 결과에도 없다.
    expect(TEXT.reworkRefused('x')).not.toContain('에이전트')
    fake.answers.set(REWORK_PARTIAL, { status: 502, body: {} })
    await rework(form)
    await waitFor(() => expect(status()).toHaveTextContent('결과 모름(운영 서비스 응답 502). 실행 목록에서 확인하십시오'))
    fake.answers.set(REWORK_FAILED, { status: 403, body: { error: 'MODE_NOT_ALLOWED', detail: '이 조작은 operator 모드에서 한다' } })
    await rework(form, 'exec-2/T3')
    await waitFor(() => expect(status()).toHaveTextContent('exec-2(JO-2)'))
    expect(status()).toHaveTextContent('보내지 않음(이 모드에서 할 수 없는 조작). 이 조작은 operator 모드에서 한다')
    // 결과를 열 번 보내므로 부하가 있는 실행에서도 기본 5초를 넘기지 않게 넉넉히 둔다.
  }, 30_000)

  it('엔지니어 모드에는 폼이 없고 대상이 있을 때만 운영자 모드에서 한다는 안내가 있으며 대상이 없으면 운영자 모드 폼이 없음을 알린다', async () => {
    const fake = withTargets()
    const withView = open(engineer)
    await screen.findByRole('table', { name: '실행 목록' })
    expect(screen.queryByRole('form', { name: TEXT.reworkHeading })).toBeNull()
    expect(screen.getByText(TEXT.reworkOperatorOnly)).toBeInTheDocument()
    withView.unmount()

    fake.recent = { settled: [], nextBefore: null, olderTotal: 0, reworkableTargets: [] }
    const engineerView = open(engineer)
    await screen.findByRole('table', { name: '실행 목록' })
    expect(screen.queryByText(TEXT.reworkOperatorOnly)).toBeNull()
    expect(screen.queryByRole('form', { name: TEXT.reworkHeading })).toBeNull()
    engineerView.unmount()
    open()
    const form = await screen.findByRole('form', { name: TEXT.reworkHeading })
    expect(form).toHaveTextContent(TEXT.reworkEmpty)
    expect(within(form).queryByLabelText(TEXT.reworkTarget)).toBeNull()
  })

  it('옛 호스트 본문처럼 대상 칸이 없으면 대상이 없는 것으로 읽는다', async () => {
    installFakeOps(emptyList)
    open()
    const form = await screen.findByRole('form', { name: TEXT.reworkHeading })
    expect(form).toHaveTextContent(TEXT.reworkEmpty)
  })
})

describe('잔여 파지 확인', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('표시를 보이고 물리 확인 체크와 사유가 있어야 기체 빈손 확인을 보낸다', async () => {
    const fake = installFakeOps(emptyList)
    fake.residualHolds = { holds: [hold()] }
    fake.answers.set(CLEAR, { status: 200, body: emptied() })
    open()
    const region = await screen.findByRole('region', { name: TEXT.holdsHeading })
    const table = await within(region).findByRole('table', { name: '미해소 잔여 파지' })
    const row = within(table).getAllByRole('row')[1]
    expect(within(row).getAllByRole('cell').map((cell) => cell.textContent)).toEqual([
      'humanoid-01', 'JO-1', 'exec-1', 'RACK-204.S02', 'C-1', '2026-10-13T09:00:00Z', TEXT.emptyButton,
    ])
    await userEvent.click(within(row).getByRole('button', { name: TEXT.emptyButton }))
    const form = within(region).getByRole('form', { name: TEXT.emptyButton })
    expect(within(form).getByRole('note')).toHaveTextContent(TEXT.emptyWarning)
    const send = within(form).getByRole('button', { name: TEXT.emptyButton })
    await userEvent.type(within(form).getByLabelText(TEXT.emptyReason), '그리퍼 비어 있음 육안 확인')
    expect(send).toBeDisabled()
    await userEvent.click(within(form).getByLabelText(TEXT.emptyConfirm))
    fake.residualHolds = { holds: [] }
    await userEvent.click(send)

    expect(posts(fake, CLEAR).map((call) => call.body)).toEqual([{ reason: '그리퍼 비어 있음 육안 확인' }])
    expect(await within(region).findByRole('status', { name: '빈손 확인 결과' })).toHaveTextContent(TEXT.emptyDone)
    expect(await within(region).findByText(TEXT.holdsEmpty)).toBeInTheDocument()
  })

  it('이미 해소한 표시는 그 이름으로 알리고 엔지니어 모드에는 확인 버튼이 없다', async () => {
    const fake = installFakeOps(emptyList)
    fake.residualHolds = { holds: [hold({ objectRef: null })] }
    fake.answers.set(CLEAR, {
      status: 200,
      body: emptied({ result: 'REJECTED', rejection: { status: 409, error: 'RESIDUAL_HOLD_ALREADY_CLEARED', detail: '이미 해소했다' } }),
    })
    const { unmount } = open()
    const region = await screen.findByRole('region', { name: TEXT.holdsHeading })
    expect(await within(region).findByText(TEXT.objectUnnamed)).toBeInTheDocument()
    await userEvent.click(within(region).getByRole('button', { name: TEXT.emptyButton }))
    const form = within(region).getByRole('form', { name: TEXT.emptyButton })
    await userEvent.type(within(form).getByLabelText(TEXT.emptyReason), '확인')
    await userEvent.click(within(form).getByLabelText(TEXT.emptyConfirm))
    await userEvent.click(within(form).getByRole('button', { name: TEXT.emptyButton }))
    expect(await within(region).findByRole('status', { name: '빈손 확인 결과' })).toHaveTextContent('이미 해소한 잔여 파지 표시')
    unmount()

    open(engineer)
    const read = await screen.findByRole('region', { name: TEXT.holdsHeading })
    await within(read).findByRole('table', { name: '미해소 잔여 파지' })
    expect(within(read).queryByRole('button', { name: TEXT.emptyButton })).toBeNull()
    expect(within(read).getByText(TEXT.emptyOperatorOnly)).toBeInTheDocument()
  })

  it('어긋남이면 폼을 열어 둔 채 관측을 보이고 체크를 켜야 그 응답의 지목으로 다시 확인하며 또 어긋나면 새 관측으로 바꾸고 체크를 끈다', async () => {
    const observation = (sequence: string, objectRef: string | null) => ({
      observedKind: 'HOLDING', observedObjectRef: objectRef, observedDetail: null, snapshotSession: 's-1', snapshotSequence: sequence,
      snapshotOccurredAt: '2026-10-13T09:00:00Z', hostReadStartedAt: '2026-10-13T09:00:00Z', hostReadReceivedAt: '2026-10-13T09:00:00.100Z',
    })
    const mismatched = (conflictRequestId: string, sequence: string, objectRef: string | null) =>
      emptied({
        result: 'REJECTED',
        rejection: {
          status: 409, error: 'RESIDUAL_HOLD_OBSERVATION_MISMATCH', detail: '발신자는 지금 든 채라고 말한다',
          fields: { robotId: 'humanoid-01', markerId: 7, conflictRequestId, observation: observation(sequence, objectRef) },
        },
      })
    const fake = installFakeOps(emptyList)
    fake.residualHolds = { holds: [hold()] }
    fake.answers.set(CLEAR, { status: 200, body: mismatched('c-1', '9007199254740993', 'C-1') })
    open()
    const region = await screen.findByRole('region', { name: TEXT.holdsHeading })
    await userEvent.click(await within(region).findByRole('button', { name: TEXT.emptyButton }))
    const form = within(region).getByRole('form', { name: TEXT.emptyButton })
    await userEvent.type(within(form).getByLabelText(TEXT.emptyReason), '그리퍼 비어 있음 육안 확인')
    await userEvent.click(within(form).getByLabelText(TEXT.emptyConfirm))
    await userEvent.click(within(form).getByRole('button', { name: TEXT.emptyButton }))

    expect(await within(region).findByRole('status', { name: '빈손 확인 결과' })).toHaveTextContent(TEXT.mismatchResult)
    const box = await within(form).findByRole('group', { name: TEXT.mismatchHeading })
    expect(box).toHaveTextContent('HOLDING')
    expect(box).toHaveTextContent('s-1')
    expect(box).toHaveTextContent('9007199254740993')
    expect(box).toHaveTextContent('2026-10-13T09:00:00.100Z')
    const acknowledge = within(box).getByLabelText(TEXT.mismatchAcknowledge('C-1'))
    const again = within(box).getByRole('button', { name: TEXT.reconfirmButton })
    expect(acknowledge).not.toBeChecked()
    expect(again).toBeDisabled()
    await userEvent.click(acknowledge)
    expect(again).toBeEnabled()

    // 또 어긋남: 새 관측으로 바꾸고 체크를 끈다.
    fake.answers.set(CLEAR, { status: 200, body: mismatched('c-2', '15', null) })
    await userEvent.click(again)
    const renewed = await within(form).findByLabelText(TEXT.mismatchAcknowledge(TEXT.objectUnnamed))
    expect(renewed).not.toBeChecked()
    expect(within(form).getByRole('button', { name: TEXT.reconfirmButton })).toBeDisabled()

    await userEvent.click(renewed)
    fake.answers.set(CLEAR, { status: 200, body: emptied() })
    fake.residualHolds = { holds: [] }
    await userEvent.click(within(form).getByRole('button', { name: TEXT.reconfirmButton }))
    expect(await within(region).findByText(TEXT.holdsEmpty)).toBeInTheDocument()
    expect(within(region).getByRole('status', { name: '빈손 확인 결과' })).toHaveTextContent(TEXT.emptyDone)

    expect(posts(fake, CLEAR).map((call) => call.body)).toEqual([
      { reason: '그리퍼 비어 있음 육안 확인' },
      { reason: '그리퍼 비어 있음 육안 확인', cited: { conflictRequestId: 'c-1', kind: 'HOLDING', objectRef: 'C-1', session: 's-1', sequence: '9007199254740993' } },
      { reason: '그리퍼 비어 있음 육안 확인', cited: { conflictRequestId: 'c-2', kind: 'HOLDING', objectRef: null, session: 's-1', sequence: '15' } },
    ])
  })

  it('폼을 닫으면 지목을 버려 다음 확인은 지목 없이 보내고 지목 무효는 그 이름으로 알린다', async () => {
    const fake = installFakeOps(emptyList)
    fake.residualHolds = { holds: [hold()] }
    fake.answers.set(CLEAR, {
      status: 200,
      body: emptied({
        result: 'REJECTED',
        rejection: {
          status: 409, error: 'RESIDUAL_HOLD_OBSERVATION_MISMATCH', detail: 'x',
          fields: {
            conflictRequestId: 'c-1',
            observation: {
              observedKind: 'HOLDING', observedObjectRef: 'C-1', observedDetail: null, snapshotSession: null, snapshotSequence: '3',
              snapshotOccurredAt: null, hostReadStartedAt: 't', hostReadReceivedAt: 't',
            },
          },
        },
      }),
    })
    open()
    const region = await screen.findByRole('region', { name: TEXT.holdsHeading })
    const fill = async () => {
      const form = within(region).getByRole('form', { name: TEXT.emptyButton })
      await userEvent.type(within(form).getByLabelText(TEXT.emptyReason), '확인')
      await userEvent.click(within(form).getByLabelText(TEXT.emptyConfirm))
      return form
    }
    await userEvent.click(await within(region).findByRole('button', { name: TEXT.emptyButton }))
    const form = await fill()
    await userEvent.click(within(form).getByRole('button', { name: TEXT.emptyButton }))
    const box = await within(form).findByRole('group', { name: TEXT.mismatchHeading })
    await userEvent.click(within(box).getByRole('button', { name: '닫기' }))
    expect(within(region).queryByRole('form', { name: TEXT.emptyButton })).toBeNull()

    fake.answers.set(CLEAR, {
      status: 200,
      body: emptied({ result: 'REJECTED', rejection: { status: 400, error: 'RESIDUAL_HOLD_CITATION_INVALID', detail: '저장값과 다르다' } }),
    })
    await userEvent.click(within(region).getByRole('button', { name: TEXT.emptyButton }))
    const reopened = await fill()
    expect(within(reopened).queryByRole('group', { name: TEXT.mismatchHeading })).toBeNull()
    await userEvent.click(within(reopened).getByRole('button', { name: TEXT.emptyButton }))
    expect(await within(region).findByRole('status', { name: '빈손 확인 결과' })).toHaveTextContent(TEXT.citationInvalidResult)
    expect(posts(fake, CLEAR).map((call) => call.body)).toEqual([{ reason: '확인' }, { reason: '확인' }])
  })

  it('고른 표시가 목록에서 사라지면 지목과 체크와 선택을 버려 다시 보여도 살아나지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.residualHolds = { holds: [hold()] }
    fake.answers.set(CLEAR, {
      status: 200,
      body: emptied({
        result: 'REJECTED',
        rejection: {
          status: 409, error: 'RESIDUAL_HOLD_OBSERVATION_MISMATCH', detail: 'x',
          fields: {
            conflictRequestId: 'c-1',
            observation: {
              observedKind: 'HOLDING', observedObjectRef: 'C-1', observedDetail: null, snapshotSession: 's-1', snapshotSequence: '3',
              snapshotOccurredAt: null, hostReadStartedAt: 't', hostReadReceivedAt: 't',
            },
          },
        },
      }),
    })
    const holdsAt = (tick: number) => <ResidualHolds session={operator} tick={tick} onConfirmed={() => undefined} />
    const view = render(holdsAt(0))
    const region = await screen.findByRole('region', { name: TEXT.holdsHeading })
    await userEvent.click(await within(region).findByRole('button', { name: TEXT.emptyButton }))
    const form = within(region).getByRole('form', { name: TEXT.emptyButton })
    await userEvent.type(within(form).getByLabelText(TEXT.emptyReason), '확인')
    await userEvent.click(within(form).getByLabelText(TEXT.emptyConfirm))
    await userEvent.click(within(form).getByRole('button', { name: TEXT.emptyButton }))
    await userEvent.click(await within(form).findByLabelText(TEXT.mismatchAcknowledge('C-1')))

    // 다른 손으로 해소되어 목록에서 사라진다(주기 재조회). 그 뒤 같은 표시가 다시 보인다.
    fake.residualHolds = { holds: [] }
    view.rerender(holdsAt(1))
    expect(await within(region).findByText(TEXT.holdsEmpty)).toBeInTheDocument()
    fake.residualHolds = { holds: [hold()] }
    view.rerender(holdsAt(2))
    const back = region
    await within(back).findByRole('table', { name: '미해소 잔여 파지' })
    expect(within(back).queryByRole('form', { name: TEXT.emptyButton })).toBeNull()
    await userEvent.click(within(back).getByRole('button', { name: TEXT.emptyButton }))
    const reopened = within(back).getByRole('form', { name: TEXT.emptyButton })
    expect(within(reopened).queryByRole('group', { name: TEXT.mismatchHeading })).toBeNull()
    expect(within(reopened).getByLabelText(TEXT.emptyConfirm)).not.toBeChecked()
    expect(within(reopened).getByLabelText(TEXT.emptyReason)).toHaveValue('')
  })

  it('표시가 없으면 없다고 보인다', async () => {
    installFakeOps(emptyList)
    open()
    const region = await screen.findByRole('region', { name: TEXT.holdsHeading })
    expect(await within(region).findByText(TEXT.holdsEmpty)).toBeInTheDocument()
  })

  it('표시를 못 읽으면 모름이고 실행 호스트 불통 사유를 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.failing.add('/api/robots/residual-holds')
    open()
    const region = await screen.findByRole('region', { name: TEXT.holdsHeading })
    expect(await within(region).findByText(/^모름: 잔여 파지 표시를 아직 읽지 못했습니다 \(실행 호스트가 답하지 않는다/)).toBeInTheDocument()
  })
})
