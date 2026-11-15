import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Finding, MissionOperationOutcome, Session } from '../api'
import {
  ARRIVAL_WAIT,
  ARRIVAL_WAIT_HOLD,
  DATA_V1,
  MISSION_PATH,
  draftRow,
  installFakeOps,
  missionOverview,
  mockRunRow,
  versionRow,
} from '../testing/fakeOps'
import type { FakeOps } from '../testing/fakeOps'
import { mockRunVerdict } from '../labels'
import { MissionDefinitionEditor as MissionsArea } from './MissionDefinitionEditor'
import { FLEET_MOCK_NOTE } from './MockRunReport'
import { FLEET_HOST_NOTE } from '../labels'

const engineer: Session = { mode: 'engineer', user: 'lee' }
const operator: Session = { mode: 'operator', user: 'kim' }

const emptyList = { registry: 'OK' as const, checkedAt: 't1', robots: [], robotsAsOf: 't1' }

const DRAFTS = `${MISSION_PATH}/drafts`
const draftPath = (draftId: number, action: string) => `${MISSION_PATH}/drafts/${draftId}/${action}`

/** 운영 서비스의 조작 200 본문(S3b JSON 계약 §10.4). 기본은 호스트가 받은 것이다. */
function operation<T>(outcome: T, partial: Partial<MissionOperationOutcome<T>> = {}): MissionOperationOutcome<T> {
  return {
    requestId: '6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a',
    workMasterId: 'PrepareSequencedRack',
    result: 'SUCCEEDED',
    confirmation: null,
    outcome,
    findings: [],
    rejection: null,
    ...partial,
  }
}

/** 실행 호스트 판정의 공통 칸(S3b JSON 계약 §4.4). */
function judgment<R extends string>(result: R, partial: object = {}) {
  return {
    result,
    draftId: 7,
    workMasterId: 'PrepareSequencedRack',
    checkedAt: 't2',
    refusals: [],
    unknown: null,
    inputs: null,
    ...partial,
  }
}

/** 운영 서비스가 호스트 거부를 옮긴 거부 카드(S3b JSON 계약 §10.2). */
const notInSpec: Finding = {
  kind: 'SIGNAL_NOT_IN_SPEC',
  observed: '노드 rack-arrival: rack_ready',
  expected: '신호 사양의 이름 중 하나(guard_closed, lot_code, rack_present)',
  checkedAt: 't2',
  owner: 'ENGINEER',
  inScreen: true,
  action: '신호 이름을 고치거나 신호 사양에 더한다',
  target: null,
  basisVersion: null,
}

const signalSpecUnknown = {
  inputs: ['SIGNAL_SPEC'],
  robots: [],
  detail: '셀 대역 스냅숏이 없어 신호 사양을 못 읽었다',
}

const posts = (fake: FakeOps, url: string) => fake.calls.filter((call) => call.method === 'POST' && call.url === url)

/** 한 행의 칸 글자. */
const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((cell) => cell.textContent)

/** 칸 이름(dt) 바로 뒤의 값(dd)을 읽는다. */
function field(region: HTMLElement, name: string) {
  const term = within(region).getByText(name, { selector: 'dt' })
  return term.nextElementSibling?.textContent
}

function open(session: Session = engineer) {
  return render(<MissionsArea session={session} onChanged={() => undefined} />)
}

const editor = () => screen.getByRole('textbox', { name: '임무 정의 JSON' }) as HTMLTextAreaElement
const notice = () => screen.findByRole('status', { name: '임무 조작 결과' })
const click = (name: string) => userEvent.click(screen.getByRole('button', { name }))

/** 초안 목록의 초안 7 을 열어 검증·모의 실행·활성화의 대상으로 둔다. */
async function openDraft(fake: FakeOps) {
  fake.mission = missionOverview({ drafts: [draftRow()] })
  open()
  await userEvent.click(await screen.findByRole('button', { name: '초안 7 열기' }))
  expect(editor().value).toBe(ARRIVAL_WAIT)
}

describe('임무·정책 영역', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('운영자 모드는 활성 버전과 버전 이력을 보이고 편집기와 조작 버튼 없이 엔지니어 모드에서 한다고 보인다', async () => {
    const fake = installFakeOps(emptyList)
    const v2 = versionRow({ version: 2, draftId: 7, definition: ARRIVAL_WAIT, reason: '랙 도착 대기 도입' })
    fake.mission = missionOverview({
      active: { version: 2, source: 'DATA', detail: v2 },
      versions: [v2, versionRow()],
      drafts: [draftRow({ lastMockRun: mockRunRow() })],
    })
    open(operator)
    const head = screen.getByRole('region', { name: '임무 PrepareSequencedRack' })
    await waitFor(() => expect(field(head, '활성 버전')).toBe('버전 2'))
    expect(field(head, '활성화')).toBe('lee t1: 랙 도착 대기 도입')
    expect(screen.getByText('임무 편집은 엔지니어 모드에서 합니다')).toBeInTheDocument()
    expect(screen.getByLabelText('활성 버전 정의')).toHaveTextContent(ARRIVAL_WAIT)
    for (const name of ['초안 저장', '검증', '모의 실행', '활성화', '초안 7 열기']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
    expect(screen.queryByRole('textbox', { name: '임무 정의 JSON' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('활성화 사유')).not.toBeInTheDocument()
    const versions = screen.getByRole('table', { name: '임무 버전 이력' })
    expect(within(versions).getAllByRole('row').slice(1).map(cells)).toEqual([
      ['버전 2 (활성)', '초안 7', 'lee', '랙 도착 대기 도입', 't1'],
      ['버전 1', '초안 1', 'lee', '데이터 정의로 전환', 't1'],
    ])
    const drafts = screen.getByRole('table', { name: '임무 초안 목록' })
    expect(cells(within(drafts).getAllByRole('row')[1]).slice(0, 3)).toEqual(['초안 7', 'local', 't1'])
    expect(within(drafts).getByText('통과 t3')).toBeInTheDocument()
  })

  it('활성 버전이 없으면 코드 정의이고 편집기는 데이터 정의 템플릿으로 시작하며 템플릿을 불러오면 내용이 바뀐다', async () => {
    installFakeOps(emptyList)
    open()
    const head = screen.getByRole('region', { name: '임무 PrepareSequencedRack' })
    await waitFor(() => expect(field(head, '활성 버전')).toBe('코드 정의'))
    await waitFor(() => expect(editor().value).toBe(DATA_V1))
    expect(screen.getByText('코드 정의는 정의 JSON 이 없습니다')).toBeInTheDocument()
    expect(screen.getByText('활성화한 버전이 없습니다. 코드 정의로 돕니다')).toBeInTheDocument()
    expect(
      screen.getByText('저장한 초안이 없습니다. 초안을 저장하면 검증·모의 실행·활성화할 수 있습니다'),
    ).toBeInTheDocument()
    for (const name of ['검증', '모의 실행', '활성화']) {
      expect(screen.getByRole('button', { name })).toBeDisabled()
    }

    await click('랙 도착 대기 템플릿 불러오기')
    expect(editor().value).toBe(ARRIVAL_WAIT)
    await click('운영자 보류 대기 템플릿 불러오기')
    expect(editor().value).toBe(ARRIVAL_WAIT_HOLD)
    await click('데이터 정의 템플릿 불러오기')
    expect(editor().value).toBe(DATA_V1)
  })

  it('활성 버전이 있으면 편집기는 그 정의로 시작하고 활성 버전 정의와 나란히 같은지 보인다', async () => {
    const fake = installFakeOps(emptyList)
    const v1 = versionRow({ definition: '{"active": 1}' })
    fake.mission = missionOverview({ active: { version: 1, source: 'DATA', detail: v1 }, versions: [v1] })
    open()
    await waitFor(() => expect(editor().value).toBe('{"active": 1}'))
    expect(screen.getByLabelText('활성 버전 정의')).toHaveTextContent('{"active": 1}')
    expect(screen.getByText('활성: 버전 1, 편집기와 같음')).toBeInTheDocument()
    await userEvent.type(editor(), ' ')
    expect(screen.getByText('활성: 버전 1, 편집기와 다름')).toBeInTheDocument()
  })

  it('초안 저장 뒤 검증 거부는 거부 카드로, 모의 실행은 단위별 표로 보이고 활성화하면 버전 이력에 새 버전이 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answers.set(DRAFTS, { status: 200, body: operation({ draft: draftRow() }) })
    fake.answers.set(draftPath(7, 'validate'), {
      status: 200,
      body: { workMasterId: 'PrepareSequencedRack', outcome: judgment('REFUSED'), findings: [notInSpec] },
    })
    fake.answers.set(draftPath(7, 'mock-run'), {
      status: 200,
      body: operation({ ...judgment('PASSED'), mockRun: mockRunRow() }),
    })
    const v3 = versionRow({ version: 3, draftId: 7, definition: ARRIVAL_WAIT, reason: '랙 도착 대기 도입' })
    fake.answers.set(draftPath(7, 'activate'), {
      status: 200,
      body: operation({ ...judgment('ACTIVATED'), version: 3, lastMockRun: mockRunRow(), activated: v3 }),
    })
    open()
    await userEvent.click(await screen.findByRole('button', { name: '랙 도착 대기 템플릿 불러오기' }))

    await click('초안 저장')
    expect(await notice()).toHaveTextContent('초안 저장: 초안 7 저장됨')
    const saved = posts(fake, DRAFTS)
    expect(saved).toHaveLength(1)
    expect(saved[0].body).toEqual({ definition: ARRIVAL_WAIT })
    expect(saved[0].headers['X-Ops-Mode']).toBe('engineer')
    expect(saved[0].headers['X-Ops-User']).toBe('lee')
    expect(saved[0].headers['Content-Type']).toBe('application/json')
    expect(screen.getByText('대상: 초안 7, 마지막 모의 실행 없음')).toBeInTheDocument()

    await click('검증')
    await waitFor(async () => expect(await notice()).toHaveTextContent('초안 7 검증: 거부됨'))
    expect(posts(fake, draftPath(7, 'validate'))[0].body).toEqual({})
    const refused = await notice()
    expect(field(refused, '종류')).toBe('신호 사양에 없는 신호')
    expect(field(refused, '관측값과 기대값')).toBe(
      '노드 rack-arrival: rack_ready / 기대: 신호 사양의 이름 중 하나(guard_closed, lot_code, rack_present)',
    )
    expect(field(refused, '해결 담당')).toBe('엔지니어(화면 안): 신호 이름을 고치거나 신호 사양에 더한다')
    expect(within(refused).queryByText('바로 가기')).not.toBeInTheDocument()

    await click('모의 실행')
    await waitFor(async () => expect(await notice()).toHaveTextContent('초안 7 모의 실행: 통과'))
    const units = within(await notice()).getByRole('table', { name: '모의 실행 5 단위' })
    expect(within(units).getAllByRole('row').slice(1).map(cells)).toEqual([
      ['rack-arrival', '설비 대기', 'equipment_wait', 'DONE', 'E2', '-'],
      ['RACK-204.S01', '기체', 'pick_place', 'DONE', 'E2', '-'],
    ])
    expect(field(await notice(), '표본 작업 지시')).toBe(
      'MOCK-7, 슬롯 RACK-204.S01, RACK-204.S02, 자재 ENGINE-COVER-A, 요구 근거 E2',
    )

    fake.mission = missionOverview({
      active: { version: 3, source: 'DATA', detail: v3 },
      versions: [v3],
      drafts: [draftRow({ lastMockRun: mockRunRow() })],
    })
    await userEvent.type(screen.getByLabelText('활성화 사유'), ' 랙 도착 대기 도입 ')
    await click('활성화')
    await waitFor(async () =>
      expect(await notice()).toHaveTextContent('초안 7 활성화: 버전 3 활성화됨. 다음 작업 지시부터 이 버전을 씁니다'),
    )
    expect(posts(fake, draftPath(7, 'activate'))[0].body).toEqual({ reason: '랙 도착 대기 도입' })
    const versions = await screen.findByRole('table', { name: '임무 버전 이력' })
    expect(cells(within(versions).getAllByRole('row')[1])).toEqual([
      '버전 3 (활성)',
      '초안 7',
      'lee',
      '랙 도착 대기 도입',
      't1',
    ])
  })

  it('활성화 사유가 없거나 공백뿐이면 보내지 않고 알린다', async () => {
    const fake = installFakeOps(emptyList)
    await openDraft(fake)
    await click('활성화')
    expect(screen.getByRole('alert')).toHaveTextContent('활성화 사유를 넣으십시오')
    await userEvent.type(screen.getByLabelText('활성화 사유'), '   ')
    await click('활성화')
    expect(screen.getByRole('alert')).toHaveTextContent('활성화 사유를 넣으십시오')
    expect(posts(fake, draftPath(7, 'activate'))).toEqual([])
  })

  it('활성화 사유는 활성화가 섰을 때만 지우고 막힘이나 응답 없음이면 남긴다', async () => {
    const fake = installFakeOps(emptyList)
    const answer = (body: unknown) => fake.answers.set(draftPath(7, 'activate'), { status: 200, body })
    const reason = () => screen.getByLabelText('활성화 사유') as HTMLInputElement
    answer(
      operation(
        { ...judgment('MOCK_RUN_REQUIRED'), version: null, lastMockRun: null, activated: null },
        { result: 'REJECTED' },
      ),
    )
    await openDraft(fake)
    await userEvent.type(reason(), '랙 도착 대기 도입')
    await click('활성화')
    await waitFor(async () => expect(await notice()).toHaveTextContent('초안 7 활성화: 막힘'))
    expect(reason().value).toBe('랙 도착 대기 도입')

    answer(operation(null, { result: 'NO_RESPONSE' }))
    await click('활성화')
    await waitFor(async () => expect(await notice()).toHaveTextContent('반영되었을 수 있음'))
    expect(reason().value).toBe('랙 도착 대기 도입')

    const v1 = versionRow({ version: 1, draftId: 7, reason: '랙 도착 대기 도입' })
    answer(operation({ ...judgment('ACTIVATED'), version: 1, lastMockRun: mockRunRow(), activated: v1 }))
    await click('활성화')
    await waitFor(async () => expect(await notice()).toHaveTextContent('초안 7 활성화: 버전 1 활성화됨'))
    expect(reason().value).toBe('')
    expect(posts(fake, draftPath(7, 'activate')).map((call) => call.body)).toEqual(
      Array(3).fill({ reason: '랙 도착 대기 도입' }),
    )
  })

  it('임무 개요를 읽기 전에는 편집기의 처음 내용을 몰라 초안 저장을 막는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.failing.add(MISSION_PATH)
    const { rerender } = open()
    expect(await screen.findByText(/^모름: 임무 버전을 아직 읽지 못했습니다 \(/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '초안 저장' })).toBeDisabled()
    await userEvent.type(editor(), '{{}')
    expect(screen.getByRole('button', { name: '초안 저장' })).toBeDisabled()

    fake.failing.delete(MISSION_PATH)
    // 세션이 바뀌면 다시 읽는다. 주기(5초)를 기다리지 않는다.
    rerender(<MissionsArea session={{ ...engineer }} onChanged={() => undefined} />)
    await waitFor(() => expect(screen.getByRole('button', { name: '초안 저장' })).toBeEnabled())
    expect(posts(fake, DRAFTS)).toEqual([])
  })

  it('INPUT_UNKNOWN 과 시운전 완료 기체를 모르는 503 은 거부 카드가 아니라 모름이다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answers.set(draftPath(7, 'validate'), {
      status: 200,
      body: {
        workMasterId: 'PrepareSequencedRack',
        outcome: judgment('INPUT_UNKNOWN', { unknown: signalSpecUnknown }),
        findings: [],
      },
    })
    fake.answers.set(draftPath(7, 'activate'), {
      status: 200,
      body: operation(
        { ...judgment('INPUT_UNKNOWN', { unknown: signalSpecUnknown }), version: null, lastMockRun: null, activated: null },
        { result: 'REJECTED' },
      ),
    })
    fake.answers.set(draftPath(7, 'mock-run'), {
      status: 503,
      body: { error: 'COMMISSIONED_ROBOTS_UNKNOWN', detail: 'registry 가 답하지 않아 시운전 완료 기체를 모른다' },
    })
    await openDraft(fake)

    await click('검증')
    let shown = await notice()
    expect(shown).toHaveTextContent(
      '초안 7 검증: 모름. 셀 대역 스냅숏이 없어 신호 사양을 못 읽었다. 판정하지 않았습니다',
    )
    expect(shown).not.toHaveTextContent('거부')
    expect(within(shown).queryByText('관측값과 기대값')).not.toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('활성화 사유'), '전환')
    await click('활성화')
    await waitFor(async () => expect(await notice()).toHaveTextContent('초안 7 활성화: 모름'))
    shown = await notice()
    expect(shown).toHaveTextContent('셀 대역 스냅숏이 없어 신호 사양을 못 읽었다')
    expect(shown).not.toHaveTextContent('거부')
    expect(shown).not.toHaveTextContent('활성화됨')

    await click('모의 실행')
    await waitFor(async () =>
      expect(await notice()).toHaveTextContent(
        '초안 7 모의 실행: 모름. registry 가 답하지 않아 시운전 완료 기체를 모른다. 판정하지 않았습니다',
      ),
    )
    expect(await notice()).not.toHaveTextContent('막힘')
  })

  it('통과한 모의 실행 없이 활성화하면 따로 적고 마지막 모의 실행의 실패를 붙인다', async () => {
    const fake = installFakeOps(emptyList)
    const failed = mockRunRow({
      passed: false,
      result: { ...mockRunRow().result, passed: false, failure: 'NOT_SETTLED', detail: '종결하지 않았다' },
    })
    fake.answers.set(draftPath(7, 'activate'), {
      status: 200,
      body: operation(
        { ...judgment('MOCK_RUN_REQUIRED'), version: null, lastMockRun: failed, activated: null },
        { result: 'REJECTED' },
      ),
    })
    await openDraft(fake)
    await userEvent.type(screen.getByLabelText('활성화 사유'), '전환')
    await click('활성화')
    const shown = await notice()
    expect(shown).toHaveTextContent(
      '초안 7 활성화: 막힘. 이 초안에 통과한 모의 실행이 없습니다. 모의 실행을 먼저 하십시오',
    )
    expect(shown).toHaveTextContent('마지막 모의 실행: 실패(종결하지 않음)')
    expect(shown).not.toHaveTextContent('거부')
    expect(shown).not.toHaveTextContent('활성화됨')
  })

  it('플릿 운반 단위가 든 모의 실행은 경로를 «플릿 운반» 으로 보이고 통과가 이 호스트에서 돈다는 뜻이 아님을 적는다', async () => {
    const fake = installFakeOps(emptyList)
    const fleet = mockRunRow({
      mockRunId: 8,
      result: {
        ...mockRunRow().result,
        units: [{ unitId: 'MOCK-CONTAINER-7', route: 'FLEET', skillType: 'transport', state: 'DONE', reached: 'E2', failureClass: null }],
      },
    })
    fake.answers.set(draftPath(7, 'mock-run'), { status: 200, body: operation({ ...judgment('PASSED'), mockRun: fleet }) })
    await openDraft(fake)
    await click('모의 실행')
    await waitFor(async () => expect(await notice()).toHaveTextContent('초안 7 모의 실행: 통과'))
    const shown = await notice()
    expect(cells(within(within(shown).getByRole('table', { name: '모의 실행 8 단위' })).getAllByRole('row')[1])).toEqual([
      'MOCK-CONTAINER-7',
      '플릿 운반',
      'transport',
      'DONE',
      'E2',
      '-',
    ])
    expect(within(shown).getByRole('note')).toHaveTextContent(FLEET_MOCK_NOTE)
    // 호스트의 플릿 연결을 모르면(옛 호스트 본문) 활성화 여부를 단정하지 않는다.
    expect(within(shown).getByRole('note')).toHaveTextContent(FLEET_HOST_NOTE.unknown)
  })

  it.each([
    [{ connected: true, url: 'http://127.0.0.1:8784' }, FLEET_HOST_NOTE.present, FLEET_HOST_NOTE.absent],
    [{ connected: false, url: null }, FLEET_HOST_NOTE.absent, FLEET_HOST_NOTE.present],
  ])('플릿 운반 모의 실행 안내는 호스트의 플릿 연결 %o 에 따라 활성화 가능 여부를 가른다', async (link, shownText, hiddenText) => {
    const fake = installFakeOps(emptyList)
    fake.fleet = link
    const fleet = mockRunRow({
      mockRunId: 8,
      result: {
        ...mockRunRow().result,
        units: [{ unitId: 'MOCK-CONTAINER-7', route: 'FLEET', skillType: 'transport', state: 'DONE', reached: 'E2', failureClass: null }],
      },
    })
    fake.answers.set(draftPath(7, 'mock-run'), { status: 200, body: operation({ ...judgment('PASSED'), mockRun: fleet }) })
    await openDraft(fake)
    await waitFor(() => expect(fake.calls.some((call) => call.url === '/api/executions/summary')).toBe(true))
    await click('모의 실행')
    await waitFor(async () => expect(within(await notice()).getByRole('note')).toHaveTextContent(shownText))
    expect(within(await notice()).getByRole('note')).not.toHaveTextContent(hiddenText)
  })

  it('플릿 포트 없는 실행 호스트의 활성화 막힘은 따로 적고 마지막 모의 실행의 통과를 붙인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answers.set(draftPath(7, 'activate'), {
      status: 200,
      body: operation(
        { ...judgment('FLEET_PORT_ABSENT'), version: null, lastMockRun: mockRunRow(), activated: null },
        { result: 'REJECTED' },
      ),
    })
    await openDraft(fake)
    await userEvent.type(screen.getByLabelText('활성화 사유'), '용기 공급 도입')
    await click('활성화')
    const shown = await notice()
    expect(shown).toHaveTextContent(
      '초안 7 활성화: 막힘. 이 실행 호스트에는 플릿 포트가 없어 플릿 운반 노드를 든 정의를 활성화하지 않습니다',
    )
    expect(shown).toHaveTextContent('마지막 모의 실행: 통과')
    expect(shown).not.toHaveTextContent('통과한 모의 실행이 없습니다')
    expect(shown).not.toHaveTextContent('활성화됨')
  })

  it('옛 모의 실행 행의 FLEET_ROUTE 실패는 옛 기록 이름표로 보인다', async () => {
    const legacy = mockRunRow({ passed: false, result: { ...mockRunRow().result, passed: false, failure: 'FLEET_ROUTE', units: [] } })
    expect(mockRunVerdict(legacy)).toBe('실패(플릿 경로 노드(옛 기록, 그때는 모의 실행 불가))')
  })

  it('모의 실행 실패는 하위 범주와 이유를 보이고 초안 목록에 그 초안의 마지막 모의 실행으로 남는다', async () => {
    const fake = installFakeOps(emptyList)
    const failed = mockRunRow({
      mockRunId: 6,
      passed: false,
      result: {
        ...mockRunRow().result,
        passed: false,
        failure: 'NOT_SETTLED',
        detail: '가상 시간 상한 PT10M 안에 종결하지 않았다',
        physicalState: 'RUNNING',
        units: [
          { unitId: 'rack-arrival', route: 'SIGNAL', skillType: 'equipment_wait', state: 'RUNNING', reached: 'E0', failureClass: null },
        ],
      },
    })
    fake.answers.set(draftPath(7, 'mock-run'), {
      status: 200,
      body: operation({ ...judgment('FAILED'), mockRun: failed }),
    })
    await openDraft(fake)
    fake.mission = missionOverview({ drafts: [draftRow({ lastMockRun: failed })] })
    await click('모의 실행')
    const shown = await notice()
    expect(shown).toHaveTextContent('초안 7 모의 실행: 실패(종결하지 않음)')
    expect(shown).toHaveTextContent('모의 실행 6: 실패(종결하지 않음). 가상 시간 상한 PT10M 안에 종결하지 않았다')
    expect(cells(within(within(shown).getByRole('table', { name: '모의 실행 6 단위' })).getAllByRole('row')[1])).toEqual([
      'rack-arrival',
      '설비 대기',
      'equipment_wait',
      'RUNNING',
      'E0',
      '-',
    ])
    const drafts = screen.getByRole('table', { name: '임무 초안 목록' })
    expect(await within(drafts).findByText('실패(종결하지 않음) t3')).toBeInTheDocument()
    expect(screen.getByText('대상: 초안 7, 마지막 모의 실행 실패(종결하지 않음)')).toBeInTheDocument()
  })

  it.each([
    ['CONFIRMED_APPLIED' as const, '응답은 없었으나 다시 읽어 보니 반영됨. 초안·버전 목록에서 보십시오'],
    ['CONFIRMED_NOT_APPLIED' as const, '응답 없음. 다시 읽어 보니 반영 안 됨. 다시 하려면 새로 요청하십시오'],
    [null, '반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다'],
  ])('초안 저장의 응답 없음은 확인 결과 %s 를 따로 보이고 대상 초안을 정하지 않는다', async (confirmation, text) => {
    const fake = installFakeOps(emptyList)
    fake.answers.set(DRAFTS, {
      status: 200,
      body: operation(null, { result: 'NO_RESPONSE', confirmation }),
    })
    open()
    await waitFor(() => expect(editor().value).toBe(DATA_V1))
    await click('초안 저장')
    const shown = await notice()
    expect(shown).toHaveTextContent(`초안 저장: ${text}`)
    expect(shown).not.toHaveTextContent('저장됨')
    expect(screen.getByRole('button', { name: '검증' })).toBeDisabled()
  })

  it('실행 호스트가 거부한 조작은 오류 이름과 사유를, 운영 서비스의 그 밖의 비정상은 결과 모름을 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answers.set(draftPath(7, 'mock-run'), {
      status: 200,
      body: operation(null, {
        result: 'REJECTED',
        rejection: { status: 404, error: 'DRAFT_NOT_FOUND', detail: '초안이 없다: 7' },
      }),
    })
    fake.answers.set(draftPath(7, 'validate'), { status: 500, body: {} })
    await openDraft(fake)
    await click('모의 실행')
    expect(await notice()).toHaveTextContent('초안 7 모의 실행: 실행 호스트가 거부함(없는 초안)초안이 없다: 7')
    await click('검증')
    await waitFor(async () =>
      expect(await notice()).toHaveTextContent('초안 7 검증: 결과 모름(운영 서비스 응답 500)'),
    )
  })

  it('편집기를 고치면 저장한 초안이 대상이 아니고 다시 저장해야 검증·모의 실행·활성화한다', async () => {
    const fake = installFakeOps(emptyList)
    await openDraft(fake)
    expect(screen.getByRole('button', { name: '검증' })).toBeEnabled()
    await userEvent.type(editor(), 'x')
    expect(
      screen.getByText('편집기 내용이 초안 7 의 내용과 다릅니다. 검증하려면 초안을 다시 저장하십시오'),
    ).toBeInTheDocument()
    for (const name of ['검증', '모의 실행', '활성화']) {
      expect(screen.getByRole('button', { name })).toBeDisabled()
    }
  })

  it('실행 호스트가 503 이면 임무 버전은 직전 값과 불통을 보인다', async () => {
    const fake = installFakeOps(emptyList)
    const v1 = versionRow()
    fake.mission = missionOverview({ active: { version: 1, source: 'DATA', detail: v1 }, versions: [v1] })
    const { rerender } = open()
    const head = screen.getByRole('region', { name: '임무 PrepareSequencedRack' })
    await waitFor(() => expect(field(head, '활성 버전')).toBe('버전 1'))
    fake.failing.add(MISSION_PATH)
    // 세션이 바뀌면 다시 읽는다. 주기(5초)를 기다리지 않는다.
    rerender(<MissionsArea session={{ ...engineer }} onChanged={() => undefined} />)
    expect(
      await within(head).findByText(
        '직전 값입니다. 실행 호스트 불통: 실행 호스트가 답하지 않는다: 응답 없음: ConnectException',
      ),
    ).toBeInTheDocument()
    expect(field(head, '활성 버전')).toBe('버전 1')
  })
})
