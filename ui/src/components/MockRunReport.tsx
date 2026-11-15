import type { ExecutionSummary, MockRunView } from '../api'
import { fleetHostNote, mockRunVerdict, routeLabel } from '../labels'

/**
 * 모의 실행 하나의 결과(S3b 스펙 §8). 통과 여부, 실패 이유, 표본 작업 지시와 단위별 표다. 모의 실행은 이상적 현장에서 정의가 끝까지
 * 도는지를 볼 뿐 현장 사실을 보증하지 않는다(S3b 스펙 T8). 플릿 운반 단위는 인프로세스 플릿 미믹으로 돈 것이라, 통과가 «이 실행
 * 호스트에서 돈다» 는 뜻이 아님을 따로 적는다.
 */
export function MockRunReport({ run, fleet: link }: { run: MockRunView; fleet?: ExecutionSummary['fleet'] }) {
  const { result } = run
  const fleet = result.units.some((unit) => unit.route === 'FLEET')
  return (
    <>
      <p>
        모의 실행 {run.mockRunId}: {mockRunVerdict(run)}
        {result.detail !== null && `. ${result.detail}`}
      </p>
      {fleet && <p role="note">{FLEET_MOCK_NOTE} {fleetHostNote(link)}</p>}
      <dl>
        <dt>표본 작업 지시</dt>
        <dd>
          {result.sample === null
            ? '없음'
            : result.sample.equipment && result.sample.slots.length===0 ? `${result.sample.jobOrderId}, 설비 ${result.sample.equipment.map(item=>`${item.equipmentUse}: ${item.id}`).join(', ')}, 요구 근거 ${result.sample.requiredEvidence}` : `${result.sample.jobOrderId}, 슬롯 ${result.sample.slots.join(', ')}, 자재 ${result.sample.material}, 요구 근거 ${result.sample.requiredEvidence}`}
        </dd>
        <dt>물리 상태</dt>
        <dd>{result.physicalState ?? '실행이 서지 않음'}</dd>
        <dt>가상 경과</dt>
        <dd>{result.virtualElapsedSeconds}초</dd>
      </dl>
      {result.units.length > 0 && (
        <div className="table-scroll" tabIndex={0} role="group" aria-label="표 가로 스크롤"><table aria-label={`모의 실행 ${run.mockRunId} 단위`}>
          <thead>
            <tr>
              <th>단위</th>
              <th>경로</th>
              <th>스킬</th>
              <th>상태</th>
              <th>근거</th>
              <th>실패 분류</th>
            </tr>
          </thead>
          <tbody>
            {result.units.map((unit) => (
              <tr key={unit.unitId}>
                <td>{unit.unitId}</td>
                <td>{routeLabel(unit.route)}</td>
                <td>{unit.skillType}</td>
                <td>{unit.state}</td>
                <td>{unit.reached}</td>
                <td>{unit.failureClass ?? '-'}</td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}
    </>
  )
}

/** 플릿 운반 단위가 든 모의 실행의 안내. 통과는 정의가 맞다는 것이지 이 실행 호스트에서 돈다는 것이 아니다. */
export const FLEET_MOCK_NOTE =
  '플릿 운반 단위는 모의 실행 안의 플릿 미믹으로 돌았습니다. 통과는 정의가 끝까지 돈다는 뜻이며 이 실행 호스트에서 돈다는 뜻이 아닙니다.'
