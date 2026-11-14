import type { Delivered, JobOrderOutcome } from '../api'
import { FORGOTTEN_JOB_ORDER_TEXT, SUBMIT_RESULT_LABEL, alternativesLabel, kindLabel, sourceMissingLabel, sourceUnobservedLabel } from '../labels'
import { UnassignedRemedies } from './RejectionSummary'

interface Props {
  /** 어느 임무의 작업 지시인지. 예: `InspectAsset 작업 지시` */
  what: string
  sent: Delivered<JobOrderOutcome>
}

/**
 * 작업 지시 제출의 결과(S3a 스펙 §8·§9.1). 기존 조작 결과([OutcomeNotice])와 모양이 달라 따로 읽는다. 실행 호스트의 판단은
 * 200 본문의 `outcome` 에 있으므로 상태 코드로 결과를 구분하지 않는다. 운영 서비스가 먼저 막은 것(400·403)은 «막힘», 그 밖의
 * 비정상은 요청이 호스트까지 갔는지 모르므로 «결과 모름» 이다. 응답 없음은 재조회로 확인되기 전에는 성공으로도 실패로도
 * 보이지 않는다.
 */
export function JobOrderNotice({ what, sent }: Props) {
  if (sent.kind === 'refused') {
    return (
      <div role="status" aria-label="제출 결과">
        <p>
          {what}: 막힘({kindLabel(sent.refusal.error)})
        </p>
        <p>{sent.refusal.detail}</p>
      </div>
    )
  }
  if (sent.kind === 'unknown') {
    return (
      <div role="status" aria-label="제출 결과">
        <p>
          {what}: 결과 모름({sent.cause}). 실행 목록을 다시 읽어 확인하십시오
        </p>
      </div>
    )
  }
  const { jobOrderId, confirmation, outcome } = sent.outcome
  if (outcome === null) {
    return (
      <div role="status" aria-label="제출 결과">
        <p>
          {what}: {noResponse(confirmation)}
        </p>
        <dl>
          <dt>작업 지시 id</dt>
          <dd>{jobOrderId}</dd>
        </dl>
      </div>
    )
  }
  return (
    <div role="status" aria-label="제출 결과">
      <p>
        {what}: {SUBMIT_RESULT_LABEL[outcome.result]}
      </p>
      <dl>
        <dt>작업 지시 id</dt>
        <dd>{jobOrderId}</dd>
        {outcome.executionId !== null && (
          <>
            <dt>실행 id</dt>
            <dd>{outcome.executionId}</dd>
          </>
        )}
        {outcome.robotId !== null && (
          <>
            <dt>배정된 기체</dt>
            <dd>{outcome.robotId}</dd>
          </>
        )}
        {outcome.rejectionReason !== null && (
          <>
            <dt>거부 사유</dt>
            <dd>{outcome.rejectionReason}</dd>
          </>
        )}
      </dl>
      {outcome.forgottenJobOrder === true && <p>{FORGOTTEN_JOB_ORDER_TEXT}</p>}
      {outcome.sourceUnobserved && <p>{sourceUnobservedLabel(outcome.sourceUnobserved)}</p>}
      {outcome.refusals.length > 0 && (
        <>
          <p>기체별 미배정 사유</p>
          <ul aria-label="기체별 미배정 사유">
            {outcome.refusals.map((refusal) => (
              <li key={refusal.robotId}>
                {refusal.robotId}: {refusal.reason}
                {refusal.sourceMissing && (
                  <>
                    <p>{sourceMissingLabel(refusal.sourceMissing)}</p>
                    <p>{alternativesLabel(refusal)}</p>
                  </>
                )}
                {refusal.sourceUnobserved && <p>{sourceUnobservedLabel(refusal.sourceUnobserved)}</p>}
              </li>
            ))}
          </ul>
          <UnassignedRemedies refusals={outcome.refusals} />
        </>
      )}
      {outcome.excluded.length > 0 && (
        <>
          <p>실행 호스트가 후보에서 뺀 기체</p>
          <ul aria-label="실행 호스트가 후보에서 뺀 기체">
            {outcome.excluded.map((row) => (
              <li key={row.robotId}>
                {row.robotId}: {row.reasons.join('; ')}
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

/** 응답 없음 뒤 재조회의 결과. 확인 결과가 없으면 그 재조회도 실패한 것이다. */
function noResponse(confirmation: JobOrderOutcome['confirmation']): string {
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return '응답은 없었으나 다시 읽어 보니 실행이 있음. 실행 목록에서 보십시오'
    case 'CONFIRMED_NOT_APPLIED':
      return '응답 없음. 현재 실행이 보이지 않습니다. 기존 요청 ID로 작업 요청을 확인하세요'
    default:
      return '반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 실행 목록에서 확인하십시오'
  }
}
