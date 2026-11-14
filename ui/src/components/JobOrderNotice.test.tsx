import { render, screen, within } from '@testing-library/react'
import { expect, it } from 'vitest'
import type { UnassignedRefusal } from '../api'
import { FORGOTTEN_JOB_ORDER_TEXT } from '../labels'
import { JobOrderNotice } from './JobOrderNotice'

const missing = { material: 'ENGINE-COVER-A', source: 'SEQ-IN-02.BIN-A', observed: null }
function show(refusals: UnassignedRefusal[]) {
  render(<JobOrderNotice what="PrepareSequencedRack 작업 지시" sent={{ kind: 'outcome', outcome: {
    requestId: 'q1', jobOrderId: 'JO-1', result: 'REJECTED', confirmation: null,
    outcome: { result: 'UNASSIGNED', executionId: null, robotId: null, rejectionReason: null, refusals, excluded: [] },
  } }} />)
  return within(screen.getByRole('list', { name: '기체별 미배정 사유' })).getAllByRole('listitem')
}

it('미배정의 기체별 사유에 출발 자리와 대체 위치를 붙이고 출발 결품이 아닌 기체에는 붙이지 않는다', () => {
  const rows = show([
    { robotId: 'humanoid-01', reason: '출발 자리 비었다', alternativeLocations: ['SEQ-IN-03.BIN-B'], sourceMissing: missing },
    { robotId: 'humanoid-02', reason: '출발 자리 비었다', alternativeLocations: [], sourceMissing: missing },
    { robotId: 'humanoid-03', reason: '자재 선언과 배정이 어긋난다', alternativeLocations: null, sourceMissing: null },
  ])
  expect(within(rows[0]).getByText('출발 자리 SEQ-IN-02.BIN-A · 비어 있음')).toBeVisible()
  expect(within(rows[0]).getByText('대체 위치: SEQ-IN-03.BIN-B')).toBeVisible()
  expect(within(rows[1]).getByText('대체 위치 없음')).toBeVisible()
  expect(rows[2]).toHaveTextContent('humanoid-03: 자재 선언과 배정이 어긋난다')
  expect(within(rows[2]).queryByText(/출발 자리|대체 위치/)).not.toBeInTheDocument()
})

it('미배정의 기체별 대체 위치 조회 불가를 대체 위치 없음으로 접지 않고 칸이 없던 호스트 본문도 읽는다', () => {
  const rows = show([
    { robotId: 'humanoid-01', reason: '출발 자리 비었다', alternativeLocations: null, sourceMissing: missing },
    { robotId: 'humanoid-02', reason: '작업 구역 점유' },
  ])
  expect(within(rows[0]).getByText('대체 위치 조회 불가')).toBeVisible()
  expect(within(rows[0]).queryByText('대체 위치 없음')).not.toBeInTheDocument()
  expect(rows[1]).toHaveTextContent('humanoid-02: 작업 구역 점유')
  expect(within(rows[1]).queryByText(/대체 위치/)).not.toBeInTheDocument()
})

it('미배정의 기체별 출발 미관측은 결품 줄과 대체 위치 없이 출발 미관측 줄을 붙인다', () => {
  const rows = show([
    { robotId: 'humanoid-01', reason: '출발 자리를 확인하지 못했다', alternativeLocations: null, sourceMissing: null, sourceUnobserved: { material: 'ENGINE-COVER-A', source: 'SEQ-IN-02.BIN-A', occupied: null } },
    { robotId: 'humanoid-02', reason: '출발 자리 비었다', alternativeLocations: [], sourceMissing: missing, sourceUnobserved: null },
  ])
  expect(within(rows[0]).getByText('출발 미관측 · 출발 자리 SEQ-IN-02.BIN-A · 설비 응답 없음 · 확인할 자재 ENGINE-COVER-A')).toBeVisible()
  expect(within(rows[0]).queryByText(/대체 위치|비어 있음/)).not.toBeInTheDocument()
  expect(within(rows[1]).queryByText(/출발 미관측/)).not.toBeInTheDocument()
  expect(within(rows[1]).getByText('대체 위치 없음')).toBeVisible()
})

it('출발 미관측 거부의 제출 결과는 거부 사유 아래에 출발 미관측 줄을 보인다', () => {
  render(<JobOrderNotice what="PrepareSequencedRack 작업 지시" sent={{ kind: 'outcome', outcome: {
    requestId: 'q1', jobOrderId: 'JO-1', result: 'REJECTED', confirmation: null,
    outcome: { result: 'REJECTED', executionId: null, robotId: null, rejectionReason: '출발 자리를 확인하지 못했다', refusals: [], excluded: [],
      sourceUnobserved: { material: 'ENGINE-COVER-A', source: 'SEQ-IN-02.BIN-A', occupied: true } },
  } }} />)
  expect(screen.getByText('출발 미관측 · 출발 자리 SEQ-IN-02.BIN-A · 있음 · 신원 모름 · 확인할 자재 ENGINE-COVER-A')).toBeVisible()
})

it('이미 끝나 놓은 작업 지시라는 거부는 거부 사유와 함께 새 작업 지시로 내라는 안내를 보이고 다른 거부는 보이지 않는다', () => {
  const reason = '이미 끝나 이빅션한 작업 지시라 다시 받지 않는다: JO-1 · exec-1 · 판 1 · PHYSICALLY_DONE'
  const { unmount } = render(<JobOrderNotice what="InspectAsset 작업 지시" sent={{ kind: 'outcome', outcome: {
    requestId: 'q1', jobOrderId: 'JO-1', result: 'REJECTED', confirmation: null,
    outcome: { result: 'REJECTED', executionId: null, robotId: null, rejectionReason: reason, refusals: [], excluded: [], forgottenJobOrder: true },
  } }} />)
  const notice = screen.getByRole('status', { name: '제출 결과' })
  expect(notice).toHaveTextContent(reason)
  expect(within(notice).getByText(FORGOTTEN_JOB_ORDER_TEXT)).toBeVisible()
  unmount()
  render(<JobOrderNotice what="InspectAsset 작업 지시" sent={{ kind: 'outcome', outcome: {
    requestId: 'q2', jobOrderId: 'JO-2', result: 'REJECTED', confirmation: null,
    outcome: { result: 'REJECTED', executionId: null, robotId: null, rejectionReason: '요구 근거 등급이 넘는다', refusals: [], excluded: [] },
  } }} />)
  expect(screen.queryByText(FORGOTTEN_JOB_ORDER_TEXT)).not.toBeInTheDocument()
})

it('미배정의 기체별 조치 제안을 보이고 관문 확인으로 얻은 제안에는 표시가 남는다는 안내를 붙인다', () => {
  show([
    { robotId: 'humanoid-01', reason: '기체 스냅숏의 파지', remedy: { kind: 'FOUND', steps: [{ skillType: 'pick_place' }] }, residualHoldMarkers: [7] },
    { robotId: 'quadruped-01', reason: '자리가 찼다' },
  ])
  const list = screen.getByRole('region', { name: '기체별 조치 제안' })
  expect(within(list).getByText('humanoid-01')).toBeTruthy()
  expect(within(list).getByText('조치 제안 1단계: pick_place')).toBeTruthy()
  expect(within(list).getByText(/잔여 파지 표시 7 · 관문 확인으로 얻은 제안/)).toBeTruthy()
  expect(within(list).queryByText('quadruped-01')).toBeNull()
})
