import type { EligibilityView, PreRejection, RobotEligibility } from '../api'
import { COMMISSIONING_LABEL, CONNECTION_LABEL, SKILL_FIT_LABEL, kindLabel } from '../labels'

/** 배정 가능을 읽은 결과. 못 읽으면 직전 판정을 지우지 않고 [error] 로 직전 값임을 표시한다. */
export interface EligibilityRead {
  view: EligibilityView | null
  /** 운영 서비스가 폼을 막았다(400). 이때 판정이 없다. */
  refusal: PreRejection | null
  error: string | null
  /** 이 판정을 낸 폼. 지금 폼과 다르면 다시 판정하는 중이다. */
  formKey: string | null
}

interface Props {
  read: EligibilityRead
  /** 지금 폼. null 이면 덜 채운 폼이라 판정을 묻지 않는다. */
  formKey: string | null
}

/**
 * 기체별 배정 가능 표(S3a 스펙 §9.1). 시운전·연결은 운영 서비스가, 도는 실행·스킬 적합은 실행 호스트가 판정한다(T3).
 * 못 물어본 칸은 «모름» 이고 «없음» 이나 «신선» 으로 접지 않는다. 모름이 하나라도 있으면 배정 불가다.
 */
export function EligibilityTable({ read, formKey }: Props) {
  if (formKey === null) return <p>폼을 채우면 기체별 배정 가능을 봅니다</p>
  if (read.refusal !== null && read.formKey === formKey) {
    return (
      <p role="alert">
        {kindLabel(read.refusal.error)}: {read.refusal.detail}
      </p>
    )
  }
  const { view } = read
  if (view === null) {
    return <p>{read.error !== null ? `모름: 배정 가능을 읽지 못했습니다 (${read.error})` : '판정 중'}</p>
  }
  return (
    <>
      {read.error !== null && <p className="stale">직전 값입니다 ({read.error})</p>}
      {read.formKey !== formKey && <p className="stale">폼이 바뀌어 다시 판정하는 중입니다</p>}
      <p>판정 시각 {view.checkedAt}</p>
      {view.robots === null ? (
        <p>모름: 기체 목록을 아직 읽지 못했습니다</p>
      ) : view.robots.length === 0 ? (
        <p>이 사이트에 기체가 없습니다</p>
      ) : (
        <div className="table-scroll" tabIndex={0} role="group" aria-label="표 가로 스크롤"><table aria-label="기체별 배정 가능">
          <thead>
            <tr>
              <th>기체</th>
              <th>시운전</th>
              <th>연결</th>
              <th>진행 중인 실행</th>
              <th>요구 스킬</th><th>기체 제공 스킬</th><th>스킬 적합</th>
              <th>배정 가능</th>
              <th>이유</th>
            </tr>
          </thead>
          <tbody>
            {view.robots.map((row) => (
              <tr key={row.robotId}>
                <td>{row.robotId}</td>
                <td>{row.commissioning === null ? '모름' : COMMISSIONING_LABEL[row.commissioning]}</td>
                <td>{connection(row)}</td>
                <td>{row.host === null ? '모름' : (row.host.runningExecutionId ?? '없음')}</td>
                <td>{skills(row.host?.requiredSkills)}</td><td>{skills(row.host?.availableSkills)}</td><td>{skillFit(row)}</td>
                <td>{row.eligible ? (row.gateTrial === true ? '관문 확인용' : '가능') : '불가'}</td>
                <td>{row.gateTrial === true ? `${(row.host?.reasons ?? []).join('; ')} · 조치 제안만 얻고 실행은 세우지 않음` : row.reasons.join('; ')}</td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}
    </>
  )
}

/** 연결과 그 판정의 근거 현장 설정 버전. */
function connection(row: RobotEligibility): string {
  if (row.connection === null) return '모름'
  const label = CONNECTION_LABEL[row.connection]
  return row.settingsVersion !== null ? `${label}(현장 설정 버전 ${row.settingsVersion})` : label
}

function skillFit(row: RobotEligibility): string {
  if (row.host === null) return '모름'
  if (row.host.skillFit === 'MISSING') return `${SKILL_FIT_LABEL.MISSING}: ${row.host.missingSkills.join(', ')}`
  return SKILL_FIT_LABEL[row.host.skillFit]
}

function skills(value: string[] | null | undefined): string { return value == null ? '모름' : value.length === 0 ? '없음' : value.join(', ') }
