import type { ReactNode } from 'react'
import type {
  ExecutionSummary,
  Delivered,
  DraftSaved,
  Finding,
  HostActivation,
  HostMockRun,
  InputUnknown,
  MissionOperationOutcome,
  MissionValidationReply,
} from '../api'
import { kindLabel, mockRunVerdict, rejectionLabel } from '../labels'
import { FindingCard } from './FindingCard'
import { MockRunReport } from './MockRunReport'

/** 임무 조작 하나와 그 결과. 조작마다 실행 호스트 본문의 모양이 다르다(S3b JSON 계약 §10.4~§10.7). */
export type MissionSent =
  | { op: 'save'; what: string; sent: Delivered<MissionOperationOutcome<DraftSaved>> }
  | { op: 'validate'; what: string; sent: Delivered<MissionValidationReply> }
  | { op: 'mockRun'; what: string; sent: Delivered<MissionOperationOutcome<HostMockRun>> }
  | { op: 'activate'; what: string; sent: Delivered<MissionOperationOutcome<HostActivation>> }

/**
 * 임무 조작의 결과(S3b 스펙 §8, S3b JSON 계약 §10.1). 운영 서비스의 `REJECTED` 만 보고 거부 카드를 그리지 않고 호스트 본문의
 * `result` 로 보인다. 거부 카드는 `REFUSED` 뿐이고, `INPUT_UNKNOWN` 과 시운전 완료 기체를 모르는 503 은 모름, `MOCK_RUN_REQUIRED`
 * 와 `FLEET_PORT_ABSENT`(플릿 포트 없는 실행 호스트) 는 따로 적는다. 응답 없음은 재조회로 확인되기 전에는 성공으로도 실패로도 보이지 않는다.
 */
export function MissionNotice({ last, fleet }: { last: MissionSent; fleet?: ExecutionSummary['fleet'] }) {
  return (
    <div role="status" aria-label="임무 조작 결과">
      {describe(last, fleet)}
    </div>
  )
}

function describe(last: MissionSent, fleet: ExecutionSummary['fleet'] | undefined): ReactNode {
  const { what } = last
  switch (last.op) {
    case 'save':
      return delivered(what, last.sent, (operation) =>
        operated(what, operation, ({ draft }) => (
          <p>
            {what}: 초안 {draft.draftId} 저장됨
          </p>
        )),
      )
    case 'validate':
      return delivered(what, last.sent, ({ outcome, findings }) => {
        if (outcome == null) return unreadable(what)
        return judged(what, outcome.result, outcome.unknown, findings) ?? <p>{what}: 통과</p>
      })
    case 'mockRun':
      return delivered(what, last.sent, (operation) =>
        operated(what, operation, (outcome, findings) => {
          const judgment = judged(what, outcome.result, outcome.unknown, findings)
          if (judgment !== null || outcome.mockRun === null) return judgment
          return (
            <>
              <p>
                {what}: {mockRunVerdict(outcome.mockRun)}
              </p>
              <MockRunReport run={outcome.mockRun} fleet={fleet} />
            </>
          )
        }),
      )
    case 'activate':
      return delivered(what, last.sent, (operation) =>
        operated(what, operation, (outcome, findings) => {
          const judgment = judged(what, outcome.result, outcome.unknown, findings)
          if (judgment !== null) return judgment
          if (outcome.result === 'FLEET_PORT_ABSENT') {
            return (
              <>
                <p>
                  {what}: 막힘. 이 실행 호스트에는 플릿 포트가 없어 플릿 운반 노드를 든 정의를 활성화하지 않습니다. 모의 실행 통과는
                  정의가 끝까지 돈다는 뜻이며 이 실행 호스트에서 돈다는 뜻이 아닙니다
                </p>
                {outcome.lastMockRun !== null && <p>마지막 모의 실행: {mockRunVerdict(outcome.lastMockRun)}</p>}
              </>
            )
          }
          if (outcome.result === 'MOCK_RUN_REQUIRED') {
            return (
              <>
                <p>{what}: 막힘. 이 초안에 통과한 모의 실행이 없습니다. 모의 실행을 먼저 하십시오</p>
                {outcome.lastMockRun !== null && <p>마지막 모의 실행: {mockRunVerdict(outcome.lastMockRun)}</p>}
              </>
            )
          }
          return (
            <p>
              {what}: 버전 {outcome.version} 활성화됨. 다음 작업 지시부터 이 버전을 씁니다
            </p>
          )
        }),
      )
  }
}

/**
 * 운영 서비스가 답하지 않았거나 먼저 막은 것. 시운전 완료 기체를 몰라 실행 호스트를 부르지 않은 503 은 거부가 아니라 모름이다(S3b
 * JSON 계약 §10.1). 운영 서비스가 2xx 로 답했으면 [show] 가 그린다.
 */
function delivered<T>(what: string, sent: Delivered<T>, show: (outcome: T) => ReactNode): ReactNode {
  if (sent.kind === 'refused') {
    if (sent.refusal.error === 'COMMISSIONED_ROBOTS_UNKNOWN') {
      return (
        <p>
          {what}: 모름. {sent.refusal.detail}. 판정하지 않았습니다
        </p>
      )
    }
    return (
      <>
        <p>
          {what}: 막힘({kindLabel(sent.refusal.error)})
        </p>
        <p>{sent.refusal.detail}</p>
      </>
    )
  }
  if (sent.kind === 'unknown') {
    return (
      <p>
        {what}: 결과 모름({sent.cause}). 초안·버전 목록을 다시 읽어 확인하십시오
      </p>
    )
  }
  return show(sent.outcome)
}

/** 실행 호스트에 보낸 조작. 응답 없음과 호스트의 4xx 를 먼저 보이고, 호스트 본문이 있으면 [show] 가 그린다. */
function operated<T>(
  what: string,
  operation: MissionOperationOutcome<T>,
  show: (outcome: T, findings: Finding[]) => ReactNode,
): ReactNode {
  if (operation.result === 'NO_RESPONSE') {
    return (
      <p>
        {what}: {noResponse(operation.confirmation)}
      </p>
    )
  }
  if (operation.rejection !== null) {
    return (
      <>
        <p>
          {what}: 실행 호스트가 거부함({rejectionLabel(operation.rejection.error)})
        </p>
        <p>{operation.rejection.detail}</p>
      </>
    )
  }
  if (operation.outcome == null) return unreadable(what)
  return show(operation.outcome, operation.findings)
}

/** 운영 서비스가 2xx 로 답했으나 호스트 본문이 없다. 반영 여부를 모르므로 목록에서 확인하게 한다. */
function unreadable(what: string): ReactNode {
  return <p>{what}: 결과 모름. 초안·버전 목록을 다시 읽어 확인하십시오</p>
}

/** 판정 입력을 몰랐거나(모름) 정의를 거부한 결과. 둘 다 아니면 null 이고 조작마다 나머지 결과를 보인다. */
function judged(what: string, result: string, unknown: InputUnknown | null, findings: Finding[]): ReactNode | null {
  switch (result) {
    case 'INPUT_UNKNOWN':
      return (
        <p>
          {what}: 모름. {unknown?.detail ?? '판정 입력을 읽지 못했습니다'}. 판정하지 않았습니다
        </p>
      )
    case 'REFUSED':
      return (
        <>
          <p>{what}: 거부됨</p>
          {findings.map((finding, index) => (
            <FindingCard key={index} finding={finding} />
          ))}
        </>
      )
    default:
      return null
  }
}

/** 응답 없음 뒤 재조회의 결과(S3b JSON 계약 §10.1). 확인 결과가 없으면 그 재조회도 실패한 것이다. */
function noResponse(confirmation: MissionOperationOutcome<unknown>['confirmation']): string {
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return '응답은 없었으나 다시 읽어 보니 반영됨. 초안·버전 목록에서 보십시오'
    case 'CONFIRMED_NOT_APPLIED':
      return '응답 없음. 다시 읽어 보니 반영 안 됨. 다시 하려면 새로 요청하십시오'
    default:
      return '반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 초안·버전 목록에서 확인하십시오'
  }
}
