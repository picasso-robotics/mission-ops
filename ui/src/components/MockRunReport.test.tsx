import { render, screen, within } from '@testing-library/react'
import { expect, it } from 'vitest'
import type { MockRunBranch, MockRunView } from '../api'
import { BRANCH_PARTIAL_VERDICT, mockRunVerdict } from '../labels'
import { BRANCH_MOCK_NOTE, MockRunReport } from './MockRunReport'

const armValues = { 'arms[0]': ['thermal'], 'arms[1]': ['visual'] }

function run(branches: MockRunBranch[] | undefined, passed = true): MockRunView {
  return {
    mockRunId: 3, draftId: 1, passed, requestId: 'r', startedAt: 't0', finishedAt: 't1',
    result: {
      passed, failure: passed ? null : 'EXECUTION_FAILED', detail: null, robotId: 'mock-01',
      sample: { jobOrderId: 'MOCK-1', requiredEvidence: 'E0', slots: [], material: '', presentation: '', equipment: [{ id: 'T1', equipmentUse: 'mission_target', properties: {} }] },
      physicalState: 'PHYSICALLY_DONE',
      units: [{ unitId: 'T1', route: 'ROBOT', skillType: 'inspect', state: 'DONE', reached: 'E0', failureClass: null }],
      virtualElapsedSeconds: 10, wallElapsedMillis: 5,
      ...(branches === undefined ? {} : { branches }),
    },
  }
}

it('분기 자리마다 돈 가지와 돌지 않은 가지를 표로 보이고 통과를 고른 가지만의 것으로 적는다', () => {
  render(<MockRunReport run={run([
    { site: 'how[T1]', choiceId: 'how', chosen: 'arms[0]', notRun: ['arms[1]', 'otherwise'], armValues },
    { site: 'how[T2]', choiceId: 'how', chosen: 'whenMissing', notRun: ['arms[0]', 'arms[1]', 'otherwise'], armValues },
  ])} />)
  expect(screen.getByText(`모의 실행 3: 통과(${BRANCH_PARTIAL_VERDICT})`)).toBeVisible()
  expect(screen.getByRole('note')).toHaveTextContent(BRANCH_MOCK_NOTE)
  const rows = within(screen.getByRole('table', { name: '모의 실행 3 분기' })).getAllByRole('row').slice(1)
  expect(rows.map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent))).toEqual([
    ['how[T1]', 'arms[0](thermal)', 'arms[1](visual), 기본 가지(otherwise)'],
    ['how[T2]', '값 없음 접힘(whenMissing)', 'arms[0](thermal), arms[1](visual), 기본 가지(otherwise)'],
  ])
})

it('자리를 열지 않은 분기는 자리 없음으로 보이고 분기 칸이 없는 옛 행과 분기 없는 정의는 분기 표와 안내가 없다', () => {
  const { unmount } = render(<MockRunReport run={run([{ site: 'how', choiceId: 'how', chosen: null, notRun: ['arms[0]', 'arms[1]', 'otherwise'], armValues }])} />)
  expect(within(screen.getByRole('table', { name: '모의 실행 3 분기' })).getByText('자리 없음(표본이 이 분기를 열지 않음)')).toBeVisible()
  unmount()
  for (const branches of [undefined, []]) {
    const view = render(<MockRunReport run={run(branches)} />)
    expect(screen.getByText('모의 실행 3: 통과')).toBeVisible()
    expect(screen.queryByRole('table', { name: '모의 실행 3 분기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
    view.unmount()
  }
  // 모든 가지가 돌았으면(돌지 않은 가지 없음) 판정에 붙이지 않는다. 실패 판정은 그대로다.
  expect(mockRunVerdict(run([{ site: 'how', choiceId: 'how', chosen: 'arms[0]', notRun: [], armValues }]))).toBe('통과')
  expect(mockRunVerdict(run([{ site: 'how', choiceId: 'how', chosen: 'arms[0]', notRun: ['otherwise'], armValues }], false))).toBe('실패(실행 실패)')
})
