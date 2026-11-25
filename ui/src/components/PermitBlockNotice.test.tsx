import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import type { Execution, PermitBlock } from '../api'
import { AUTHORITY_BLOCK_TEXT as AUTHORITY, PERMIT_BLOCK_TEXT as TEXT, RESTART_HOLD_BLOCK_TEXT as RESTART, RETIRED_BLOCK_TEXT as RETIRED } from '../labels'
import { ExecutionList } from './ExecutionList'
import { WorkRequests } from './WorkRequests'

/** 정착 이력이 없는 화면 실행 목록(HA 설계 기록 §10). */
const NO_SETTLED = { settled: [], nextBefore: null, olderTotal: 0 }

afterEach(() => vi.unstubAllGlobals())

const block: PermitBlock = {
  unitId: 'T1.travel',
  withheld: 'START',
  since: '2026-10-14T00:00:00Z',
  robots: [
    {
      robotId: 'humanoid-01',
      permit: 'INHIBITED',
      reasons: [
        {
          source: 'cell/guard_closed',
          cause: 'REPORTED',
          subject: 'guard_closed=false',
          resumeBasis: '안전 계통이 guard_closed 을 true 으로 되돌릴 때',
          observedAt: '2026-10-14T00:00:01Z',
        },
      ],
    },
  ],
}

function execution(partial: Partial<Execution>): Execution {
  return {
    executionId: 'exec-1',
    jobOrderId: 'JO-1',
    workMasterId: 'InspectAsset',
    missionVersion: null,
    robotId: 'humanoid-01',
    physicalState: 'RUNNING',
    units: [{ unitId: 'T1.travel', skillType: 'navigate_to', state: 'PENDING', reached: 'NONE' }],
    jobResponse: null,
    ...partial,
  }
}

it('작업 허가로 막힌 실행은 막은 기체와 주체와 풀릴 근거를 운영자 보류와 다른 표시로 보이고 조작 버튼이 없다', () => {
  const held = execution({ executionId: 'exec-2', jobOrderId: 'JO-2', physicalState: 'OPERATOR_HOLD' })
  render(
    <ExecutionList
      read={{ value: { instanceId: 'mw-1', pumpedAt: 't', executions: [execution({ permitBlock: block }), held], ...NO_SETTLED }, error: null }}
      highlight={null}
    />,
  )
  const notices = screen.getAllByRole('region', { name: TEXT.heading })
  expect(notices).toHaveLength(1)
  const notice = notices[0]
  expect(within(notice).getByText('T1.travel')).toBeVisible()
  expect(within(notice).getByText('단위 출발')).toBeVisible()
  expect(within(notice).getByText('기체 humanoid-01: 차단')).toBeVisible()
  expect(within(notice).getByText(/막는 주체 guard_closed=false/)).toBeVisible()
  expect(within(notice).getByText(/풀릴 근거 안전 계통이 guard_closed 을 true 으로 되돌릴 때/)).toBeVisible()
  expect(within(notice).getByText(/원천 보고/)).toBeVisible()
  expect(within(notice).getByText(TEXT.notOperator)).toBeVisible()
  expect(within(notice).getByText(TEXT.notSafety)).toBeVisible()
  expect(within(notice).queryByRole('button')).not.toBeInTheDocument()
  // 운영자 보류 실행의 행에는 작업 허가 표시가 없다.
  const heldRow = screen.getByRole('row', { name: /exec-2/ })
  expect(within(heldRow).queryByRole('region', { name: TEXT.heading })).not.toBeInTheDocument()
  expect(within(heldRow).getByText('OPERATOR_HOLD')).toBeVisible()
})

it('모름(UNKNOWN)은 까닭과 원천 없음을 그대로 보인다', () => {
  const unknown: PermitBlock = {
    ...block,
    withheld: 'REVISION_UPDATE',
    robots: [{ robotId: 'humanoid-01', permit: 'UNKNOWN', reasons: [{ source: null, cause: 'NOT_COVERED', subject: null, resumeBasis: '이 기체를 덮는 허가 원천이 생길 때', observedAt: null }] }],
  }
  render(<ExecutionList read={{ value: { instanceId: 'mw-1', pumpedAt: 't', executions: [execution({ permitBlock: unknown })], ...NO_SETTLED }, error: null }} highlight={null} />)
  const notice = screen.getByRole('region', { name: TEXT.heading })
  expect(within(notice).getByText('개정 갱신')).toBeVisible()
  expect(within(notice).getByText('기체 humanoid-01: 모름(차단)')).toBeVisible()
  expect(within(notice).getByText(/까닭 허가 원천 없음 · 막는 주체 없음/)).toBeVisible()
})

const retiredReason = {
  source: null,
  cause: 'RETIRED',
  subject: '감속기 교체',
  resumeBasis: '운영자가 이 기체의 퇴역을 취소할 때',
  observedAt: null,
}

it('퇴역만으로 막힌 실행은 퇴역의 제목과 안내를 보이고 안전 신호와 운영자 판단 불가 안내는 보이지 않는다', () => {
  const retired: PermitBlock = { ...block, robots: [{ robotId: 'humanoid-01', permit: 'INHIBITED', reasons: [retiredReason] }] }
  render(<ExecutionList read={{ value: { instanceId: 'mw-1', pumpedAt: 't', executions: [execution({ permitBlock: retired })], ...NO_SETTLED }, error: null }} highlight={null} />)
  expect(screen.queryByRole('region', { name: TEXT.heading })).not.toBeInTheDocument()
  const notice = screen.getByRole('region', { name: RETIRED.heading })
  expect(within(notice).getByText(RETIRED.intro)).toBeVisible()
  expect(within(notice).getByText(/까닭 퇴역 · 막는 주체 감속기 교체/)).toBeVisible()
  expect(within(notice).getByText(RETIRED.resume)).toBeVisible()
  expect(within(notice).getByText(RETIRED.notStop)).toBeVisible()
  expect(within(notice).queryByText(TEXT.notOperator)).not.toBeInTheDocument()
  expect(within(notice).queryByText(TEXT.notSafety)).not.toBeInTheDocument()
})

it('작업 허가와 퇴역이 섞이면 작업 허가의 안내에 퇴역을 풀 수 있다는 줄을 더한다', () => {
  const mixed: PermitBlock = { ...block, robots: [...block.robots, { robotId: 'quadruped-01', permit: 'INHIBITED', reasons: [retiredReason] }] }
  render(<ExecutionList read={{ value: { instanceId: 'mw-1', pumpedAt: 't', executions: [execution({ permitBlock: mixed })], ...NO_SETTLED }, error: null }} highlight={null} />)
  const notice = screen.getByRole('region', { name: TEXT.heading })
  expect(within(notice).getByText(TEXT.notOperator)).toBeVisible()
  expect(within(notice).getByText(RETIRED.mixed)).toBeVisible()
  expect(within(notice).getByText(TEXT.notSafety)).toBeVisible()
})

it('작업 허가만으로 막히면 퇴역 줄이 없다', () => {
  render(<ExecutionList read={{ value: { instanceId: 'mw-1', pumpedAt: 't', executions: [execution({ permitBlock: block })], ...NO_SETTLED }, error: null }} highlight={null} />)
  const notice = screen.getByRole('region', { name: TEXT.heading })
  expect(within(notice).queryByText(RETIRED.mixed)).not.toBeInTheDocument()
})

it('작업 요청 상세는 현재 실행의 작업 허가 대기를 보인다', async () => {
  const receipt = { receiptId: 'r1', sourceSystemId: 'ops:manual', externalOrderId: 'M-1', jobOrderId: 'JO-1', state: 'ACCEPTED', detail: null, payload: {}, normalizedOrder: null, submission: null, receivedAt: 't1', updatedAt: 't1' }
  const detail = { receipt, hostRead: 'OK', execution: { executionId: 'exec-1', physicalState: 'RUNNING', robotId: 'humanoid-01', permitBlock: block }, instanceId: 'mw-1', journalBlocked: false, observedAt: 't2' }
  vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(url.startsWith('/api/work-requests?') ? { total: 1, requests: [receipt], offset: 0, limit: 20 } : detail), { status: 200 })))
  render(<WorkRequests session={{ mode: 'operator', user: 'kim' }} />)
  await userEvent.click(await screen.findByRole('button', { name: 'M-1 작업 요청 보기' }))
  const article = await screen.findByRole('article', { name: '작업 요청 상세' })
  const notice = await within(article).findByRole('region', { name: TEXT.heading })
  expect(within(notice).getByText('기체 humanoid-01: 차단')).toBeVisible()
  expect(within(notice).getByText(/막는 주체 guard_closed=false/)).toBeVisible()
})

const authorityReason = {
  source: null,
  cause: 'AUTHORITY_NOT_HELD',
  subject: '인수 확인 대기 — 실행 exec-1(작업 지시 JO-1)의 기체 quadruped-01: 수신자 대조 안 함(바닥값 0)',
  resumeBasis: '이 인스턴스가 그 기체의 권위를 쥘 때',
  observedAt: null,
}

it('권위만으로 막힌 실행은 인수 확인 대기의 제목과 안내, 막는 기체와 까닭을 보이고 안전 신호 안내는 보이지 않는다', () => {
  const held: PermitBlock = { ...block, robots: [{ robotId: 'humanoid-01', permit: 'INHIBITED', reasons: [authorityReason] }] }
  render(<ExecutionList read={{ value: { instanceId: 'mw-1', pumpedAt: 't', executions: [execution({ permitBlock: held })], ...NO_SETTLED }, error: null }} highlight={null} />)
  expect(screen.queryByRole('region', { name: TEXT.heading })).not.toBeInTheDocument()
  const notice = screen.getByRole('region', { name: AUTHORITY.heading })
  expect(within(notice).getByText(AUTHORITY.intro)).toBeVisible()
  expect(within(notice).getByText(/까닭 인수 확인 대기 · 막는 주체 인수 확인 대기 — 실행 exec-1\(작업 지시 JO-1\)의 기체 quadruped-01: 수신자 대조 안 함\(바닥값 0\)/)).toBeVisible()
  expect(within(notice).getByText(AUTHORITY.resume)).toBeVisible()
  expect(within(notice).getByText(AUTHORITY.notStop)).toBeVisible()
  expect(within(notice).queryByText(TEXT.notSafety)).not.toBeInTheDocument()
  expect(within(notice).queryByText(RETIRED.resume)).not.toBeInTheDocument()
  expect(within(notice).queryByRole('button')).not.toBeInTheDocument()
})

it('작업 허가와 권위가 섞이면 작업 허가의 안내에 인수 확인으로 풀린다는 줄을 더한다', () => {
  const mixed: PermitBlock = { ...block, robots: [...block.robots, { robotId: 'quadruped-01', permit: 'INHIBITED', reasons: [authorityReason] }] }
  render(<ExecutionList read={{ value: { instanceId: 'mw-1', pumpedAt: 't', executions: [execution({ permitBlock: mixed })], ...NO_SETTLED }, error: null }} highlight={null} />)
  const notice = screen.getByRole('region', { name: TEXT.heading })
  expect(within(notice).getByText(AUTHORITY.mixed)).toBeVisible()
  expect(within(notice).queryByText(RETIRED.mixed)).not.toBeInTheDocument()
})

it('권위 까닭이라도 인수 확인 대기가 아니면(밀려남 따위) 인수 확인 대기 제목과 자동으로 풀린다는 안내를 보이지 않는다', () => {
  const superseded = {
    source: null,
    cause: 'AUTHORITY_NOT_HELD',
    subject: '수신자가 답한 세대 거절: robot=humanoid-01 BELOW_FLOOR 요청 세대=1 바닥값=2',
    resumeBasis: '이 인스턴스가 더 높은 세대로 다시 설 때(새 프로세스)',
    observedAt: null,
  }
  const held: PermitBlock = { ...block, robots: [{ robotId: 'humanoid-01', permit: 'INHIBITED', reasons: [superseded] }] }
  render(<ExecutionList read={{ value: { instanceId: 'mw-1', pumpedAt: 't', executions: [execution({ permitBlock: held })], ...NO_SETTLED }, error: null }} highlight={null} />)
  expect(screen.queryByRole('region', { name: AUTHORITY.heading })).not.toBeInTheDocument()
  const notice = screen.getByRole('region', { name: TEXT.heading })
  expect(within(notice).queryByText(AUTHORITY.resume)).not.toBeInTheDocument()
  expect(within(notice).queryByText(AUTHORITY.mixed)).not.toBeInTheDocument()
  expect(within(notice).getByText(/막는 주체 수신자가 답한 세대 거절/)).toBeVisible()
  // 행의 까닭 라벨도 «인수 확인 대기» 가 아니라 일반 라벨이다.
  expect(within(notice).getByText(/까닭 권위 없음 · 막는 주체 수신자가 답한 세대 거절/)).toBeVisible()
  expect(within(notice).queryByText(/까닭 인수 확인 대기/)).not.toBeInTheDocument()
})

const restartReason = {
  source: null,
  cause: 'AUTHORITY_NOT_HELD',
  subject: '수신자 재시작 의심 — 기체 quadruped-01: 의심 #3 단위 T2.travel(태스크 JO-1#T2.travel) — 운영자 단위 해제 필요',
  resumeBasis: '이 인스턴스가 그 기체의 권위를 쥘 때',
  observedAt: null,
}

it('수신자 재시작 의심만으로 막힌 실행은 그 제목과 운영자 단위 해제 안내를 보이고 인수 확인 안내는 보이지 않는다', () => {
  const held: PermitBlock = { ...block, robots: [{ robotId: 'quadruped-01', permit: 'INHIBITED', reasons: [restartReason] }] }
  render(<ExecutionList read={{ value: { instanceId: 'mw-1', pumpedAt: 't', executions: [execution({ permitBlock: held })], ...NO_SETTLED }, error: null }} highlight={null} />)
  expect(screen.queryByRole('region', { name: AUTHORITY.heading })).not.toBeInTheDocument()
  const notice = screen.getByRole('region', { name: RESTART.heading })
  expect(within(notice).getByText(RESTART.intro)).toBeVisible()
  expect(within(notice).getByText(/까닭 수신자 재시작 의심 · 막는 주체 수신자 재시작 의심 — 기체 quadruped-01: 의심 #3/)).toBeVisible()
  expect(within(notice).getByText(RESTART.resume)).toBeVisible()
  expect(within(notice).getByText(RESTART.notStop)).toBeVisible()
  expect(within(notice).queryByText(AUTHORITY.resume)).not.toBeInTheDocument()
  expect(within(notice).queryByRole('button')).not.toBeInTheDocument()
})

it('작업 허가와 수신자 재시작 의심이 섞이면 운영자 단위 해제로만 풀린다는 줄을 더한다', () => {
  const mixed: PermitBlock = { ...block, robots: [...block.robots, { robotId: 'quadruped-01', permit: 'INHIBITED', reasons: [restartReason] }] }
  render(<ExecutionList read={{ value: { instanceId: 'mw-1', pumpedAt: 't', executions: [execution({ permitBlock: mixed })], ...NO_SETTLED }, error: null }} highlight={null} />)
  const notice = screen.getByRole('region', { name: TEXT.heading })
  expect(within(notice).getByText(RESTART.mixed)).toBeVisible()
  expect(within(notice).queryByText(AUTHORITY.mixed)).not.toBeInTheDocument()
})
