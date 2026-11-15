import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Execution, Session } from '../api'
import { EXECUTION_SUMMARY_PATH, EXECUTIONS_RECENT_PATH, settledBeforePath } from '../api'
import { BOUND_ROBOT_LABEL, EXECUTION_LIST_TEXT as TEXT } from '../labels'
import { executionsView, installFakeOps, settledRow } from '../testing/fakeOps'
import type { FakeOps } from '../testing/fakeOps'
import { OperationsArea } from './OperationsArea'
import { OverviewArea } from './OverviewArea'

const operator: Session = { mode: 'operator', user: 'kim' }
const emptyList = { registry: 'OK' as const, checkedAt: 't1', robots: [], robotsAsOf: 't1' }

/** 실행 한 줄(S3a JSON 계약 §5). 기본은 정착한 InspectAsset 실행이다. */
function execution(id: number, partial: Partial<Execution> = {}): Execution {
  return {
    executionId: `exec-${id}`,
    jobOrderId: `JO-${id}`,
    workMasterId: 'InspectAsset',
    missionVersion: null,
    robotId: 'humanoid-01',
    physicalState: 'PHYSICALLY_DONE',
    units: [{ unitId: 'T1', skillType: 'inspect', state: 'DONE', reached: 'E0' }],
    jobResponse: null,
    restoredFrom: null,
    ...partial,
  }
}

/** 정착 차례 [seq] 에 정착한 실행 [id] 의 이력 줄. */
const settled = (seq: number, id = seq) => settledRow(execution(id), seq)

const gets = (fake: FakeOps) => fake.calls.filter((call) => call.method === 'GET').map((call) => call.url)

/** 표의 줄마다 실행 id 칸 글자. 머리 줄은 뺀다. */
const firstCells = (table: HTMLElement) =>
  within(table).getAllByRole('row').slice(1).map((row) => within(row).getAllByRole('cell')[0].textContent)

function open() {
  return render(<OperationsArea session={operator} onChanged={() => undefined} />)
}

describe('실행 목록 — 활성과 최근 정착, 이력은 페이지', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('운영 화면은 남은 실행과 최근 정착 이력을 한 표에 보이고 전체 실행 목록을 읽지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: [execution(5, { physicalState: 'RUNNING' }), execution(6, { physicalState: 'OPERATOR_HOLD' })] })
    fake.recent = {
      settled: [
        settled(14, 4),
        // 정착 기록과 같은 순간에 아직 남은 실행으로도 읽힌 실행은 남은 실행 쪽만 보인다.
        settledRow(execution(6), 13),
        settledRow(execution(2, { units: [] }), 9, { instanceId: 'mw-0', snapshot: false }),
      ],
      nextBefore: 9,
      olderTotal: 2,
    }
    open()
    const region = screen.getByRole('region', { name: '실행' })
    const table = await within(region).findByRole('table', { name: '실행 목록' })
    expect(firstCells(table)).toEqual(['exec-6', 'exec-5', 'exec-4', 'exec-2 (이전 인스턴스 mw-0)'])
    const rows = within(table).getAllByRole('row')
    expect(rows[3]).toHaveTextContent(`PHYSICALLY_DONE (${TEXT.settledAt('s14')})`)
    expect(rows[4]).toHaveTextContent(TEXT.noSnapshot)
    expect(rows[1]).not.toHaveTextContent('종결')
    expect(within(region).getByText(TEXT.scope(20))).toBeInTheDocument()
    expect(within(region).getByText(TEXT.olderCount(2))).toBeInTheDocument()
    expect(gets(fake).filter((url) => url.startsWith('/api/executions'))).toEqual([EXECUTIONS_RECENT_PATH])
    // 취소할 실행은 남은 실행에서만 고른다.
    const choices = within(within(region).getByRole('form', { name: '실행 취소' })).getAllByRole('option').map((option) => option.textContent)
    expect(choices).toEqual(['실행 선택', 'exec-5 · JO-5 · humanoid-01 · RUNNING', 'exec-6 · JO-6 · humanoid-01 · OPERATOR_HOLD'])
  })

  it('이전 실행 더 보기는 주기 읽기의 커서에서 한 페이지씩 더 읽고 끝나면 단추를 감추며 접으면 더 읽은 것을 버린다', async () => {
    const fake = installFakeOps(emptyList)
    fake.recent = { settled: [settled(10), settled(9)], nextBefore: 9, olderTotal: 3 }
    fake.older.set(9, { settled: [settled(7), settled(5)], nextBefore: 5, olderTotal: 1 })
    fake.older.set(5, { settled: [settled(4)], nextBefore: null, olderTotal: 0 })
    open()
    const older = await screen.findByRole('region', { name: TEXT.historyHeading })
    expect(within(older).getByText(TEXT.olderCount(3))).toBeInTheDocument()
    expect(within(older).queryByRole('table')).not.toBeInTheDocument()

    await userEvent.click(within(older).getByRole('button', { name: TEXT.loadOlder }))
    const table = await within(older).findByRole('table', { name: TEXT.historyTable })
    expect(firstCells(table)).toEqual(['exec-7', 'exec-5'])
    expect(within(older).getByText(TEXT.olderCount(1))).toBeInTheDocument()

    await userEvent.click(within(older).getByRole('button', { name: TEXT.loadOlder }))
    await waitFor(() => expect(firstCells(within(older).getByRole('table', { name: TEXT.historyTable }))).toEqual(['exec-7', 'exec-5', 'exec-4']))
    expect(within(older).queryByRole('button', { name: TEXT.loadOlder })).not.toBeInTheDocument()
    expect(gets(fake).filter((url) => url.includes('before='))).toEqual([settledBeforePath(9), settledBeforePath(5)])

    await userEvent.click(within(older).getByRole('button', { name: TEXT.collapse }))
    expect(within(older).queryByRole('table')).not.toBeInTheDocument()
    expect(within(older).getByText(TEXT.olderCount(3))).toBeInTheDocument()
  })

  it('더 읽은 뒤 새 정착으로 최근 정착 이력이 밀리면 밀려난 이력만 채워 두 표가 정착 차례대로 맞닿는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.recent = { settled: [settled(10), settled(9)], nextBefore: 9, olderTotal: 3 }
    fake.older.set(9, { settled: [settled(7), settled(5), settled(4)], nextBefore: null, olderTotal: 0 })
    const { rerender } = open()
    const older = await screen.findByRole('region', { name: TEXT.historyHeading })
    await userEvent.click(within(older).getByRole('button', { name: TEXT.loadOlder }))
    await within(older).findByRole('table', { name: TEXT.historyTable })

    // 두 실행이 새로 정착했다. 주기 읽기의 최근 둘은 12·11 이고 10·9 가 밀려났다.
    fake.recent = { settled: [settled(12), settled(11)], nextBefore: 11, olderTotal: 5 }
    // 대역의 페이지 크기는 셋이다(앞 페이지와 같게).
    fake.older.set(11, { settled: [settled(10), settled(9), settled(7)], nextBefore: 7, olderTotal: 2 })
    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
    await waitFor(() =>
      expect(firstCells(within(older).getByRole('table', { name: TEXT.historyTable }))).toEqual(['exec-10', 'exec-9', 'exec-7', 'exec-5', 'exec-4']),
    )
    expect(firstCells(screen.getByRole('table', { name: '실행 목록' }))).toEqual(['exec-12', 'exec-11'])
    // 따라잡기는 새 커서에서 한 페이지만 읽는다(더 읽은 이력과 겹치는 데서 멈춘다).
    expect(gets(fake).filter((url) => url.includes('before='))).toEqual([settledBeforePath(9), settledBeforePath(11)])
  })

  it('따라잡을 이력이 상한보다 많으면 더 읽은 이력을 버리고 알린다', async () => {
    const fake = installFakeOps(emptyList)
    fake.recent = { settled: [settled(100)], nextBefore: 100, olderTotal: 1 }
    fake.older.set(100, { settled: [settled(1)], nextBefore: null, olderTotal: 0 })
    const { rerender } = open()
    const older = await screen.findByRole('region', { name: TEXT.historyHeading })
    await userEvent.click(within(older).getByRole('button', { name: TEXT.loadOlder }))
    await within(older).findByRole('table', { name: TEXT.historyTable })
    // 새 커서 앞에는 끝없이 새 이력이 있다(페이지마다 하나, 다음 커서가 늘 있다).
    for (let seq = 200; seq > 150; seq--) fake.older.set(seq, { settled: [settled(seq - 1)], nextBefore: seq - 1, olderTotal: seq })
    fake.recent = { settled: [settled(200)], nextBefore: 200, olderTotal: 99 }
    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
    expect(await within(older).findByRole('status')).toHaveTextContent(TEXT.historyReset)
    expect(within(older).queryByRole('table')).not.toBeInTheDocument()
    expect(within(older).getByText(TEXT.olderCount(99))).toBeInTheDocument()
  })

  it('이전 실행을 못 읽으면 알리고 주기 읽기의 표는 그대로다', async () => {
    const fake = installFakeOps(emptyList)
    fake.recent = { settled: [settled(3)], nextBefore: 3, olderTotal: 1 }
    open()
    const older = await screen.findByRole('region', { name: TEXT.historyHeading })
    fake.failing.add('/api/executions')
    await userEvent.click(within(older).getByRole('button', { name: TEXT.loadOlder }))
    expect(await within(older).findByRole('alert')).toHaveTextContent(TEXT.historyError('실행 호스트가 답하지 않는다: 응답 없음: ConnectException'))
    expect(firstCells(screen.getByRole('table', { name: '실행 목록' }))).toEqual(['exec-3'])
  })

  it('다시 정착한 작업 지시의 정착 이력은 줄마다 작업 지시 버전을 보이고 앞 줄에 이후 다시 정착 표시를 붙인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: [] })
    const response = (version: number) => ({
      jobResponseId: `resp-${version}`, version, physicalState: 'FAILED', requiredEvidence: 'E0', reachedEvidence: 'E0', completedUnits: [],
      unverifiedUnits: [], incompleteUnits: { T1: 'SKILL_EXECUTION_FAILED' }, inDoubtUnits: [], operatorRequired: false,
      residualHold: 'HOLD_KIND_EMPTY', blockedBy: [], connection: 'CONNECTION_STATE_ONLINE',
    })
    fake.recent = {
      settled: [
        settledRow(execution(7, { physicalState: 'FAILED', jobResponse: response(2) }), 21),
        settledRow(execution(7, { physicalState: 'FAILED', jobResponse: response(1) }), 20, { superseded: true }),
        settledRow(execution(3, { units: [] }), 9, { snapshot: false }),
      ],
      nextBefore: null,
      olderTotal: 0,
    }
    open()
    const table = await within(screen.getByRole('region', { name: '실행' })).findByRole('table', { name: '실행 목록' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(firstCells(table)).toEqual(['exec-7', 'exec-7', 'exec-3'])
    expect(rows[0]).toHaveTextContent(`FAILED (${TEXT.settledAt('s21')}) (${TEXT.orderVersion(2)})`)
    expect(rows[0]).not.toHaveTextContent(TEXT.superseded)
    expect(rows[1]).toHaveTextContent(`FAILED (${TEXT.settledAt('s20')}) (${TEXT.orderVersion(1)}) (${TEXT.superseded})`)
    // 작업 응답이 없는 줄(본문 없는 정착 기록)은 버전을 말하지 않는다.
    expect(rows[2]).not.toHaveTextContent('작업 지시 버전')
  })

  it('운영 개요는 실행 수만 읽고 실행 목록을 읽지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({
      executions: [
        execution(1, { physicalState: 'RUNNING' }), execution(2, { physicalState: 'RUNNING' }), execution(3, { physicalState: 'OPERATOR_HOLD' }),
        execution(4, { physicalState: 'IN_DOUBT' }), execution(5, { physicalState: 'CANCELING' }),
      ],
      journalBlockedExecutionIds: ['exec-5'],
    })
    render(<OverviewArea session={operator} />)
    await waitFor(() => expect(screen.getByText('실행 중').nextElementSibling).toHaveTextContent('2'))
    expect(screen.getByText('판단·확인 대기').nextElementSibling).toHaveTextContent('2')
    expect(screen.getByText('일지 저장 차단').nextElementSibling).toHaveTextContent('1')
    expect(screen.getByText('인스턴스').nextElementSibling).toHaveTextContent('mw-1')
    // 칸이 없는 옛 호스트 본문의 플릿 연결은 모름이다.
    expect(screen.getByText('플릿 연결').nextElementSibling).toHaveTextContent('모름')
    expect(gets(fake).filter((url) => url.startsWith('/api/executions'))).toEqual([EXECUTION_SUMMARY_PATH])
  })

  it('운영 개요는 호스트의 플릿 연결을 없음 또는 주소로 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.fleet = { connected: false, url: null }
    const { unmount } = render(<OverviewArea session={operator} />)
    await waitFor(() => expect(screen.getByText('플릿 연결').nextElementSibling).toHaveTextContent('없음'))
    unmount()
    fake.fleet = { connected: true, url: 'http://127.0.0.1:8083' }
    render(<OverviewArea session={operator} />)
    await waitFor(() => expect(screen.getByText('플릿 연결').nextElementSibling).toHaveTextContent('http://127.0.0.1:8083'))
  })

  it('플릿 전용 실행의 기체는 묶인 기체(실행 주체 아님)로 표기하고 다른 실행은 기체 id 만 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({
      executions: [
        execution(7, { physicalState: 'RUNNING', workMasterId: 'DeliverContainer', boundRobot: true, units: [{ unitId: 'HU-1042', skillType: 'transport', state: 'RUNNING', reached: 'E0', route: 'FLEET' }] }),
        execution(8, { physicalState: 'RUNNING' }),
      ],
    })
    open()
    const table = await within(screen.getByRole('region', { name: '실행' })).findByRole('table', { name: '실행 목록' })
    const rows = within(table).getAllByRole('row').slice(1)
    const bound = rows.find((row) => row.textContent?.includes('exec-7'))!
    const robot = rows.find((row) => row.textContent?.includes('exec-8'))!
    expect(within(bound).getAllByRole('cell')[4]).toHaveTextContent(`humanoid-01 (${BOUND_ROBOT_LABEL})`)
    expect(within(robot).getAllByRole('cell')[4]).toHaveTextContent(/^humanoid-01$/)
  })
})
