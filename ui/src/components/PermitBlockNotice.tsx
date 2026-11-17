import type { PermitBlock } from '../api'
import {
  AUTHORITY_BLOCK_TEXT as AUTHORITY,
  PERMIT_BLOCK_TEXT as TEXT,
  permitCauseLabel,
  RETIRED_BLOCK_TEXT as RETIRED,
  WITHHELD_ISSUE_LABEL,
  WORK_PERMIT_LABEL,
} from '../labels'

/**
 * 작업 허가로 막힌 새 로봇 명령(ADR 52, 실행의 `permitBlock`). 어느 기체가, 무엇 때문에, 무엇이 바뀌면 풀리는지를 보인다. 읽기만 한다.
 *
 * 운영자 보류·결함 막음과 다른 표시다. 작업 허가는 안전 신호를 비춘 정보이고, 이 화면은 그것으로 picasso 가 새 명령을 내지 않고 있다는
 * 것만 말한다. 기체를 세우는 것은 안전 계통이다.
 *
 * 같은 발행 게이트가 퇴역한 기체(까닭 `RETIRED`, picasso §15.249)도 막는다. 까닭이 모두 퇴역이면 퇴역의 제목과 안내를 보이고, 작업 허가와
 * 섞였으면 작업 허가의 안내에 퇴역은 운영자가 풀 수 있다는 한 줄을 더한다.
 *
 * 권위(까닭 `AUTHORITY_NOT_HELD`, picasso ADR 54)도 같다. 단 «인수 확인 대기» 는 실행 호스트가 관측 중 아직 열지 않은 기체의 까닭(막는 주체가
 * `인수 확인 대기` 로 시작)일 때만이다 — 같은 까닭이 밀려남·권위 포트 읽기 실패에도 실리고 그때 «자동으로 풀림» 안내는 틀리다. 까닭이 모두 인수 확인
 * 대기면 그 제목과 안내를 보이고(막는 기체와 까닭은 막는 주체에 실린다), 다른 까닭과 섞였으면 그 안내에 한 줄을 더한다. 그 밖의 권위 까닭은 일반
 * 문구로 보인다.
 */
export function PermitBlockNotice({ block }: { block: PermitBlock }) {
  const reasons = block.robots.flatMap((robot) => robot.reasons)
  const causes = reasons.map((reason) => reason.cause)
  const retiredCount = causes.filter((cause) => cause === 'RETIRED').length
  const retiredOnly = causes.length > 0 && retiredCount === causes.length
  const authorityCount = reasons.filter(
    (reason) => reason.cause === 'AUTHORITY_NOT_HELD' && (reason.subject ?? '').startsWith(AUTHORITY.subjectPrefix),
  ).length
  const authorityOnly = causes.length > 0 && authorityCount === causes.length
  const heading = authorityOnly ? AUTHORITY.heading : retiredOnly ? RETIRED.heading : TEXT.heading
  return (
    <section className="permit-block" aria-label={heading}>
      <h4>{heading}</h4>
      <p>{authorityOnly ? AUTHORITY.intro : retiredOnly ? RETIRED.intro : TEXT.intro}</p>
      <dl>
        <dt>{TEXT.unit}</dt>
        <dd>{block.unitId}</dd>
        <dt>{TEXT.withheld}</dt>
        <dd>{WITHHELD_ISSUE_LABEL[block.withheld] ?? block.withheld}</dd>
        <dt>{TEXT.since}</dt>
        <dd>{block.since}</dd>
      </dl>
      <ul aria-label="허가를 기다리는 기체">
        {block.robots.map((robot) => (
          <li key={robot.robotId}>
            <strong>
              {TEXT.robot} {robot.robotId}: {WORK_PERMIT_LABEL[robot.permit] ?? robot.permit}
            </strong>
            <ul>
              {robot.reasons.map((reason, index) => (
                <li key={index}>
                  {TEXT.cause} {permitCauseLabel(reason)} · {TEXT.subject} {reason.subject ?? TEXT.none} ·{' '}
                  {TEXT.resumeBasis} {reason.resumeBasis}
                  <small>
                    {' '}
                    ({TEXT.source} {reason.source ?? TEXT.none}, {TEXT.observedAt} {reason.observedAt ?? TEXT.none})
                  </small>
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
      {authorityOnly ? (
        <>
          <p>{AUTHORITY.resume}</p>
          <p>{AUTHORITY.notStop}</p>
        </>
      ) : retiredOnly ? (
        <>
          <p>{RETIRED.resume}</p>
          <p>{RETIRED.notStop}</p>
        </>
      ) : (
        <>
          <p>{TEXT.notOperator}</p>
          {retiredCount > 0 && <p>{RETIRED.mixed}</p>}
          {authorityCount > 0 && <p>{AUTHORITY.mixed}</p>}
          <p>{TEXT.notSafety}</p>
        </>
      )}
    </section>
  )
}
