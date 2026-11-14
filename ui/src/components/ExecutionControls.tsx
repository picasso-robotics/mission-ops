import { useEffect, useRef, useState } from 'react'
import { cancelExecution, citeMismatch, closeUnrestored, confirmRobotEmpty, faultBlocked, fetchResidualHolds, HOLD_EVICTED, mismatchOf, releaseFaults, resolveHold } from '../api'
import type {
  Delivered,
  Execution,
  ExecutionCancelOutcome,
  ExecutionCloseOutcome,
  ExecutionReleaseOutcome,
  HoldResolveOutcome,
  ObservationMismatch,
  ResidualHold,
  ResidualHoldsView,
  ReworkableTarget,
  RobotEmptyOutcome,
  Session,
  UnrestoredRow,
} from '../api'
import {
  CANCEL_RESULT_LABEL,
  CLOSE_BASIS_LABEL,
  CLOSE_RESULT_LABEL,
  EXECUTION_CONTROL_TEXT as TEXT,
  kindLabel,
  rejectionText,
  RELEASE_RESULT_LABEL,
  UNRESTORED_TEXT,
} from '../labels'
import type { HostRead } from './ExecutionList'
import { FaultCard } from './FaultCard'

const message = (error: unknown) => (error instanceof Error ? error.message : String(error))

/** 더 돌지 않는 물리 상태(picasso `PhysicalState.pumps` 가 거짓). 취소 중인 실행도 다시 취소하지 않는다. */
const NOT_CANCELABLE = new Set(['PHYSICALLY_DONE', 'UNVERIFIED', 'FAILED', 'ABORTED', 'CANCELING'])

/** 화면이 취소를 권할 실행. 실행 호스트가 다시 판정하므로 이것은 고르기 편의다. */
const cancelable = (execution: Execution) => !NOT_CANCELABLE.has(execution.physicalState) && execution.cancel == null

interface CancelProps {
  instanceId: string
  executions: Execution[]
  session: Session
  /** 조작이 끝나면 부른다. 실행 목록을 지금 한 번, pump 뒤 한 번 더 읽는다. */
  onCanceled: () => void
}

/**
 * 운영자 실행 취소(P0-b2). 운영자 모드에서만 조작한다. 승인자는 운영 서비스가 행위자로 정하므로 화면이 고르지 않는다(ADR 43). 사유와
 * 현장 확인이 있어야 보낸다. 인스턴스는 실행 목록의 것이고, 실행 호스트가 그 사이 재기동했으면 409 로 막힌다.
 */
export function CancelExecutionForm({ instanceId, executions, session, onCanceled }: CancelProps) {
  const [target, setTarget] = useState('')
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Delivered<ExecutionCancelOutcome> } | null>(null)
  const choices = executions.filter(cancelable)
  if (session.mode !== 'operator') return choices.length > 0 ? <p>{TEXT.cancelOperatorOnly}</p> : null

  const chosen = choices.find((execution) => execution.executionId === target) ?? null
  const send = () => {
    if (chosen === null || reason.trim() === '' || !confirmed) return
    const what = `${chosen.executionId}(${chosen.jobOrderId}) ${TEXT.cancelHeading}`
    setBusy(true)
    cancelExecution(session, { instanceId, executionId: chosen.executionId }, reason.trim())
      .then((sent) => setLast({ what, sent }))
      .finally(() => {
        setBusy(false)
        setReason('')
        setConfirmed(false)
        setTarget('')
        onCanceled()
      })
  }

  return (
    <form aria-label={TEXT.cancelHeading} onSubmit={(event) => event.preventDefault()}>
      <h3>{TEXT.cancelHeading}</h3>
      {last !== null && (
        <p role="status" aria-label="실행 취소 결과">
          {last.what}: {describeCancel(last.sent)}
        </p>
      )}
      {choices.length === 0 ? null : (
        <>
          <p>{TEXT.cancelWarning}</p>
          <p>{TEXT.partialNote}</p>
          <label>
            {TEXT.cancelTarget}
            <select value={target} disabled={busy} onChange={(event) => setTarget(event.target.value)}>
              <option value="">{TEXT.cancelChoose}</option>
              {choices.map((execution) => (
                <option key={execution.executionId} value={execution.executionId}>
                  {execution.executionId} · {execution.jobOrderId} · {execution.robotId} · {execution.physicalState}
                </option>
              ))}
            </select>
          </label>{' '}
          <label>
            {TEXT.cancelReason}
            <input maxLength={1000} value={reason} disabled={busy} onChange={(event) => setReason(event.target.value)} />
          </label>{' '}
          <label>
            <input type="checkbox" checked={confirmed} disabled={busy} onChange={(event) => setConfirmed(event.target.checked)} />
            {TEXT.cancelConfirm}
          </label>{' '}
          <button type="button" disabled={busy || chosen === null || reason.trim() === '' || !confirmed} onClick={send}>
            {TEXT.cancelButton}
          </button>
        </>
      )}
    </form>
  )
}

function describeCancel(sent: Delivered<ExecutionCancelOutcome>): string {
  if (sent.kind === 'refused') return `보내지 않음(${kindLabel(sent.refusal.error)}). ${sent.refusal.detail}`
  if (sent.kind === 'unknown') return `결과 모름(${sent.cause}). 실행 목록에서 확인하십시오`
  const { outcome, answer, rejection, confirmation } = sent.outcome
  if (outcome !== null) {
    const label = CANCEL_RESULT_LABEL[outcome] ?? outcome
    return outcome !== 'CANCELED' && answer?.detail ? `${label}. ${answer.detail}` : label
  }
  if (rejection !== null) return rejectionText('실행 호스트가 거부함', rejection)
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return '응답은 없었으나 다시 읽어 보니 취소가 걸림'
    case 'CONFIRMED_NOT_APPLIED':
      return '응답 없음. 다시 읽어 보니 취소가 걸리지 않음. 실행 목록에서 확인하십시오'
    default:
      return '응답 없음. 취소가 걸렸을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 실행 목록에서 확인하십시오'
  }
}

interface CloseProps {
  /** 종결할 복원 못 한 실행의 행(실행 목록 `unrestored`). 종결할 수 있는 행만 준다. */
  row: UnrestoredRow
  session: Session
  /** 조작이 끝나면 부른다. 실행 목록을 지금 한 번, pump 뒤 한 번 더 읽는다. */
  onClosed: () => void
  /**
   * 보낸 결과를 부른다. 결과 줄은 목록을 다시 읽어 이 행이 사라져도 남아야 하므로 폼이 아니라 부른 쪽이 그린다([CloseResultStatus]).
   */
  onResult: (sent: Delivered<ExecutionCloseOutcome>) => void
}

/**
 * 복원 못 한 실행의 운영자 종결(결함 14). 운영자 모드에서만 조작하고 승인자는 운영 서비스가 행위자로 정한다(ADR 43). 사유가 있어야 보낸다.
 * 종결할 실행은 행의 `previousInstanceId`·`previousExecutionId` 이고 지금 일지와 다르면 실행 호스트가 막는다. «로봇이 현장에서 멈춘 것을
 * 확인했다» 증언은 체크한 채 보낼 때만 `attestedStopped` 로 간다. 실행 호스트가 스냅숏으로 정지를 보지 못하고 증언도 없으면 증언을 청한다.
 * 종결은 로봇을 멈추지 않고 성공을 추정하지 않는다. 종결되지 않은 답은 사유와 증언 칸을 지우지 않는다(증언만 더해 다시 보낼 수 있다).
 */
export function CloseUnrestoredForm({ row, session, onClosed, onResult }: CloseProps) {
  const [reason, setReason] = useState('')
  const [attested, setAttested] = useState(false)
  const [busy, setBusy] = useState(false)
  if (session.mode !== 'operator') return <p>{UNRESTORED_TEXT.operatorOnly}</p>

  const send = () => {
    if (reason.trim() === '') return
    setBusy(true)
    closeUnrestored(
      session,
      { jobOrderId: row.jobOrderId, executionInstanceId: row.previousInstanceId, executionId: row.previousExecutionId },
      reason.trim(),
      attested,
    )
      .then((sent) => {
        onResult(sent)
        if (sent.kind === 'outcome' && sent.outcome.outcome === 'CLOSED') {
          setReason('')
          setAttested(false)
        }
      })
      .finally(() => {
        setBusy(false)
        onClosed()
      })
  }

  return (
    <form aria-label={TEXT.closeHeading} onSubmit={(event) => event.preventDefault()}>
      <h3>{TEXT.closeHeading}</h3>
      <p>
        {row.jobOrderId}({row.robotId}, 이전 {row.previousExecutionId}): {row.reason ?? '사유 없음'}
      </p>
      <p>{TEXT.closeWarning}</p>
      {row.result === 'DEFERRED' && <p>{TEXT.closeDeferredNote}</p>}
      <label>
        {TEXT.closeReason}
        <textarea maxLength={1000} value={reason} disabled={busy} onChange={(event) => setReason(event.target.value)} />
      </label>{' '}
      <label>
        <input type="checkbox" checked={attested} disabled={busy} onChange={(event) => setAttested(event.target.checked)} />
        {TEXT.closeAttest}
      </label>{' '}
      <button type="button" disabled={busy || reason.trim() === ''} onClick={send}>
        {TEXT.closeButton}
      </button>
    </form>
  )
}

/**
 * 마지막 운영자 종결 결과 한 줄. 종결하면 목록을 다시 읽어 그 행이 사라지거나 종결할 수 없는 행이 되고 폼이 내려가므로, 폼 밖에서 그린다.
 */
export function CloseResultStatus({ jobOrderId, sent }: { jobOrderId: string; sent: Delivered<ExecutionCloseOutcome> }) {
  return (
    <p role="status" aria-label={TEXT.closeResultAria}>
      {jobOrderId}: {describeClose(sent)}
    </p>
  )
}

function describeClose(sent: Delivered<ExecutionCloseOutcome>): string {
  if (sent.kind === 'refused') return `보내지 않음(${kindLabel(sent.refusal.error)}). ${sent.refusal.detail}`
  if (sent.kind === 'unknown') return `결과 모름(${sent.cause}). 실행 목록에서 확인하십시오`
  const { outcome, answer, rejection, confirmation } = sent.outcome
  if (outcome !== null) {
    const label = CLOSE_RESULT_LABEL[outcome] ?? outcome
    if (outcome === 'CLOSED') return answer?.basis ? `${label}(${CLOSE_BASIS_LABEL[answer.basis]})` : label
    return answer?.detail ? `${label}. ${answer.detail}` : label
  }
  if (rejection !== null) return rejectionText('실행 호스트가 거부함', rejection)
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return TEXT.closeApplied
    case 'CONFIRMED_NOT_APPLIED':
      return TEXT.closeNotApplied
    default:
      return TEXT.closeUnconfirmed
  }
}

interface ReleaseProps {
  instanceId: string
  executions: Execution[]
  session: Session
  /** 조작이 끝나면 부른다. 실행 목록을 지금 한 번, pump 뒤 한 번 더 읽는다. */
  onReleased: () => void
}

/**
 * 운영자 결함 감수(S3a JSON 계약 §5.10·§9.8). 운영자 모드에서만 조작하고 승인자는 운영 서비스가 행위자로 정한다(ADR 43). 고른 실행의
 * 막는 결함을 카드로 보이고, 보낼 때 그 결함을 본 결함으로 싣는다. 실행 호스트는 지금 막는 결함과 다르면 감수하지 않는다. 사유와 현장
 * 확인이 있어야 보낸다. 고를 실행도 마지막 결과도 없으면 아무것도 그리지 않는다(막힌 실행은 드물다).
 */
export function ReleaseFaultForm({ instanceId, executions, session, onReleased }: ReleaseProps) {
  const [target, setTarget] = useState('')
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Delivered<ExecutionReleaseOutcome> } | null>(null)
  const choices = executions.filter(faultBlocked)
  if (session.mode !== 'operator') return choices.length > 0 ? <p>{TEXT.releaseOperatorOnly}</p> : null
  if (choices.length === 0 && last === null) return null

  const chosen = choices.find((execution) => execution.executionId === target) ?? null
  const faults = chosen?.blockedFaults ?? []
  const send = () => {
    if (chosen === null || faults.length === 0 || reason.trim() === '' || !confirmed) return
    const what = `${chosen.executionId}(${chosen.jobOrderId}) ${TEXT.releaseHeading}`
    setBusy(true)
    releaseFaults(session, { instanceId, executionId: chosen.executionId }, faults, reason.trim())
      .then((sent) => setLast({ what, sent }))
      .finally(() => {
        setBusy(false)
        setReason('')
        setConfirmed(false)
        setTarget('')
        onReleased()
      })
  }

  return (
    <form aria-label={TEXT.releaseHeading} onSubmit={(event) => event.preventDefault()}>
      <h3>{TEXT.releaseHeading}</h3>
      {last !== null && (
        <p role="status" aria-label="결함 수용 결과">
          {last.what}: {describeRelease(last.sent)}
        </p>
      )}
      {choices.length === 0 ? null : (
        <>
          <p>{TEXT.releaseIntro}</p>
          <p>
            {TEXT.releaseRepeat} {TEXT.releasePermitNote}
          </p>
          <label>
            {TEXT.releaseTarget}
            <select value={target} disabled={busy} onChange={(event) => setTarget(event.target.value)}>
              <option value="">{TEXT.releaseChoose}</option>
              {choices.map((execution) => (
                <option key={execution.executionId} value={execution.executionId}>
                  {execution.executionId} · {execution.jobOrderId} · {execution.robotId}
                </option>
              ))}
            </select>
          </label>
          {chosen !== null && (
            <div className="release-faults" role="group" aria-label={TEXT.releaseFaults}>
              {faults.map((fault, index) => (
                <FaultCard key={index} fault={fault} label={`${TEXT.releaseFaults} ${index + 1}`} />
              ))}
            </div>
          )}{' '}
          <label>
            {TEXT.releaseReason}
            <input maxLength={1000} value={reason} disabled={busy} onChange={(event) => setReason(event.target.value)} />
          </label>{' '}
          <label>
            <input type="checkbox" checked={confirmed} disabled={busy} onChange={(event) => setConfirmed(event.target.checked)} />
            {TEXT.releaseConfirm}
          </label>{' '}
          <button type="button" disabled={busy || chosen === null || reason.trim() === '' || !confirmed} onClick={send}>
            {TEXT.releaseButton}
          </button>
        </>
      )}
    </form>
  )
}

function describeRelease(sent: Delivered<ExecutionReleaseOutcome>): string {
  if (sent.kind === 'refused') return `보내지 않음(${kindLabel(sent.refusal.error)}). ${sent.refusal.detail}`
  if (sent.kind === 'unknown') return `결과 모름(${sent.cause}). 실행 목록에서 확인하십시오`
  const { outcome, answer, rejection, confirmation } = sent.outcome
  if (outcome !== null) {
    const label = RELEASE_RESULT_LABEL[outcome] ?? outcome
    // 감수했어도 작업 허가 대기가 남았으면 실행 호스트가 그 사실을 detail 에 싣는다.
    return answer?.detail ? `${label}. ${answer.detail}` : label
  }
  if (rejection !== null) return rejectionText('실행 호스트가 거부함', rejection)
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return TEXT.releaseApplied
    case 'CONFIRMED_NOT_APPLIED':
      return TEXT.releaseNotApplied
    default:
      return TEXT.releaseUnconfirmed
  }
}

interface ReworkProps {
  instanceId: string
  /** 실행 호스트가 계산한 재작업할 수 있는 건너뛴 대상. 남은 실행이 아니다. 칸이 없던 호스트는 빈 목록이다. */
  targets: ReworkableTarget[]
  session: Session
  /** 조작이 끝나면 부른다. 실행 목록을 지금 한 번, pump 뒤 한 번 더 읽는다. */
  onReworked: () => void
}

/** 고르는 대상 하나의 키. 실행 하나에 건너뛴 대상이 여럿일 수 있다. */
const reworkKey = (target: ReworkableTarget) => `${target.executionId}/${target.unitId}`

/**
 * 운영자 건너뛴 대상 재작업. 운영자 모드에서만 조작하고 승인자는 운영 서비스가 행위자로 정한다(ADR 43). 순회가 종결한 뒤 선행 이동이
 * 실패해 점검을 하지 않은 대상을 실행 호스트 목록에서 하나 골라 `REWORK` 판단으로 보낸다. 인시던트 판단과 같은 호출이고 단위 id 가 건너뛴
 * 점검의 것이다. 확인(`CONFIRM_DONE`)은 보내지 않는다. 사유와 현장 확인이 있어야 보낸다. 인스턴스는 실행 목록의 것이고 호스트가 그 사이
 * 재기동했으면 409 로 막힌다. 목록이 호스트 판정이므로 화면은 남은 실행에서 대상을 추리지 않는다. 호스트가 같은 기체의 다른 활성 실행 등을
 * 거부하면 그 사유를 그대로 보인다.
 */
export function ReworkSkippedForm({ instanceId, targets, session, onReworked }: ReworkProps) {
  const [chosenKey, setChosenKey] = useState('')
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Delivered<HoldResolveOutcome> } | null>(null)
  if (session.mode !== 'operator') return targets.length > 0 ? <p>{TEXT.reworkOperatorOnly}</p> : null

  const chosen = targets.find((target) => reworkKey(target) === chosenKey) ?? null
  const send = () => {
    if (chosen === null || reason.trim() === '' || !confirmed) return
    const what = `${chosen.executionId}(${chosen.jobOrderId}) ${TEXT.reworkHeading}`
    setBusy(true)
    resolveHold(session, { instanceId, executionId: chosen.executionId, unitId: chosen.unitId }, 'REWORK', reason.trim())
      .then((sent) => setLast({ what, sent }))
      .finally(() => {
        setBusy(false)
        setReason('')
        setConfirmed(false)
        setChosenKey('')
        onReworked()
      })
  }

  return (
    <form aria-label={TEXT.reworkHeading} onSubmit={(event) => event.preventDefault()}>
      <h3>{TEXT.reworkHeading}</h3>
      {last !== null && (
        <p role="status" aria-label={`${TEXT.reworkHeading} 결과`}>
          {last.what}: {describeRework(last.sent)}
        </p>
      )}
      {targets.length === 0 ? (
        <p>{TEXT.reworkEmpty}</p>
      ) : (
        <>
          <p>{TEXT.reworkIntro}</p>
          <p>{TEXT.reworkSiteNote}</p>
          <label>
            {TEXT.reworkTarget}
            <select value={chosenKey} disabled={busy} onChange={(event) => setChosenKey(event.target.value)}>
              <option value="">{TEXT.reworkChoose}</option>
              {targets.map((target) => (
                <option key={reworkKey(target)} value={reworkKey(target)}>
                  {TEXT.reworkOption(target.executionId, target.jobOrderId, target.robotId, target.unitId)}
                </option>
              ))}
            </select>
          </label>
          {chosen !== null && <p>{chosen.retainedUntil === null ? TEXT.reworkNoExpiry : TEXT.reworkExpiry(chosen.retainedUntil)}</p>}{' '}
          <label>
            {TEXT.reworkReason}
            <input maxLength={1000} value={reason} disabled={busy} onChange={(event) => setReason(event.target.value)} />
          </label>{' '}
          <label>
            <input type="checkbox" checked={confirmed} disabled={busy} onChange={(event) => setConfirmed(event.target.checked)} />
            {TEXT.reworkConfirm}
          </label>{' '}
          <button type="button" disabled={busy || chosen === null || reason.trim() === '' || !confirmed} onClick={send}>
            {TEXT.reworkButton}
          </button>
        </>
      )}
    </form>
  )
}

function describeRework(sent: Delivered<HoldResolveOutcome>): string {
  if (sent.kind === 'refused') return `보내지 않음(${kindLabel(sent.refusal.error)}). ${sent.refusal.detail}`
  if (sent.kind === 'unknown') return `결과 모름(${sent.cause}). 실행 목록에서 확인하십시오`
  const { outcome, answer, rejection, confirmation } = sent.outcome
  switch (outcome) {
    case 'Resolved':
      return TEXT.reworkResolved(answer?.incidentId ?? null)
    case 'Refused':
      return TEXT.reworkRefused(answer?.detail ?? null)
    case 'NotHeld':
      // 보존 기간이 지나 호스트가 이빅션한 실행과 그 밖의 재작업할 수 없는 대상을 호스트의 고정 코드로 가른다.
      return answer?.detail === HOLD_EVICTED ? TEXT.reworkEvicted : TEXT.reworkNotSkipped
  }
  if (rejection !== null) return rejectionText('실행 호스트가 거부함', rejection)
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return TEXT.reworkApplied
    case 'CONFIRMED_NOT_APPLIED':
      return TEXT.reworkNotApplied
    default:
      return TEXT.reworkUnconfirmed
  }
}

interface HoldsProps {
  session: Session
  /** 주기와 조작마다 올라간다. 오르면 다시 읽는다. */
  tick: number
  /** 빈손 확인이 끝나면 부른다. 배정 가능과 조작 기록을 다시 읽게 한다. */
  onConfirmed: () => void
}

/**
 * 미해소 잔여 파지 표시와 기체 빈손 확인(P0-b2). 표시가 있는 기체는 실행 호스트 판정이 배정에서 뺀다(배정 가능 표의 이유 칸에도
 * 보인다). 확인은 물리 확인이라 사유와 현장 확인 체크가 있어야 보낸다. 운영자 모드에서만 조작한다. 못 읽으면 직전 값을 지우지 않는다.
 *
 * 어긋남(B2): 실행 호스트가 확인 순간 읽은 스냅숏이 아직 든 채면 409 `RESIDUAL_HOLD_OBSERVATION_MISMATCH` 로 해소하지 않는다. 그때는 폼을 닫지 않고
 * 그 관측을 보이며, 기본 꺼진 체크(«발신자는 지금 X 를 들고 있다고 말한다. 현장에서 빈손을 확인했다»)를 켜야 재확인이 산다. 재확인은 그 어긋남 응답의
 * 값으로 만든 지목(`cited`)을 싣고 운영 서비스가 새 요청 id 로 보낸다. 지목 값은 그 응답에서만 오고 폼을 닫거나 그 표시가 목록에서 사라지면 버린다. 또
 * 어긋남이 오면 새 관측으로 바꾸고 체크를 끈다.
 */
export function ResidualHolds({ session, tick, onConfirmed }: HoldsProps) {
  const [read, setRead] = useState<HostRead<ResidualHoldsView>>({ value: null, error: null })
  const [selected, setSelected] = useState<number | null>(null)
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Delivered<RobotEmptyOutcome> } | null>(null)
  const [own, setOwn] = useState(0)
  /** 지금 폼의 표시가 받은 어긋남. 지목 값의 유일한 출처다. */
  const [mismatch, setMismatch] = useState<{ markerId: number; value: ObservationMismatch } | null>(null)
  const [acknowledged, setAcknowledged] = useState(false)
  /** 지금 고른 표시. 목록을 읽은 콜백이 표시 소멸을 볼 때 쓴다. */
  const chosenId = useRef<number | null>(null)

  const choose = (markerId: number | null) => {
    chosenId.current = markerId
    setSelected(markerId)
    setReason('')
    setConfirmed(false)
    setMismatch(null)
    setAcknowledged(false)
  }

  useEffect(() => {
    let alive = true
    fetchResidualHolds(session)
      .then((value) => {
        if (!alive) return
        setRead({ value, error: null })
        // 고른 표시가 목록에서 사라졌으면 선택·사유·체크·지목을 버린다. 같은 표시가 다시 보여도 앞 지목과 체크가 살아나지 않는다.
        const listed = Array.isArray(value.holds) ? value.holds : null
        if (listed !== null && chosenId.current !== null && !listed.some((hold) => hold.markerId === chosenId.current)) choose(null)
      })
      .catch((error: unknown) => {
        if (alive) setRead((previous) => ({ ...previous, error: message(error) }))
      })
    return () => {
      alive = false
    }
  }, [session, tick, own])

  // 운영 서비스가 다른 모양을 주면 목록이 없다. 없는 목록도 모름이다.
  const holds = read.value !== null && Array.isArray(read.value.holds) ? read.value.holds : null
  const chosen = holds?.find((hold) => hold.markerId === selected) ?? null
  // 지목은 지금 고른 표시의 어긋남일 때만 쓴다. 폼이 닫혔거나 표시가 목록에서 사라졌으면 버린 것과 같다.
  const pending = mismatch !== null && chosen !== null && mismatch.markerId === chosen.markerId ? mismatch.value : null

  const send = (hold: ResidualHold) => {
    if (reason.trim() === '' || !confirmed || (pending !== null && !acknowledged)) return
    const what = `${hold.robotId} ${TEXT.emptyButton}(${hold.jobOrderId} ${hold.unitId})`
    setBusy(true)
    confirmRobotEmpty(session, hold, reason.trim(), pending === null ? undefined : citeMismatch(pending))
      .then((sent) => {
        setLast({ what, sent })
        const next = sent.kind === 'outcome' ? mismatchOf(sent.outcome) : null
        if (next !== null) {
          // 폼을 열어 둔다. 새 관측으로 바꾸고 체크를 끈다. 목록은 그대로라 다시 읽지 않는다.
          setMismatch({ markerId: hold.markerId, value: next })
          setAcknowledged(false)
          setBusy(false)
          return
        }
        choose(null)
        setOwn((value) => value + 1)
        onConfirmed()
        setBusy(false)
      })
      .catch(() => {
        choose(null)
        setBusy(false)
      })
  }

  return (
    <section aria-label={TEXT.holdsHeading}>
      <h3>{TEXT.holdsHeading}</h3>
      <p>{TEXT.holdsIntro}</p>
      {last !== null && (
        <p role="status" aria-label="빈손 확인 결과">
          {last.what}: {describeEmpty(last.sent)}
        </p>
      )}
      {holds === null ? (
        <p>
          {TEXT.holdsUnknown}
          {read.error !== null && ` (${read.error})`}
        </p>
      ) : (
        <>
          {read.error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {read.error}</p>}
          {holds.length === 0 ? (
            <p>{TEXT.holdsEmpty}</p>
          ) : (
            <div className="table-scroll" tabIndex={0} role="group" aria-label="표 가로 스크롤">
              <table aria-label="미해소 잔여 파지">
                <thead>
                  <tr>
                    <th>기체</th>
                    <th>작업 지시</th>
                    <th>실행</th>
                    <th>단위</th>
                    <th>든 것</th>
                    <th>기록 시각</th>
                    <th>확인</th>
                  </tr>
                </thead>
                <tbody>
                  {holds.map((hold) => (
                    <tr key={hold.markerId} className={hold.markerId === selected ? 'selected' : undefined}>
                      <td>{hold.robotId}</td>
                      <td>{hold.jobOrderId}</td>
                      <td title={`인스턴스 ${hold.instanceId}`}>{hold.executionId}</td>
                      <td>{hold.unitId}</td>
                      <td>{hold.objectRef ?? TEXT.objectUnnamed}</td>
                      <td>{hold.recordedAt}</td>
                      <td>
                        {session.mode === 'operator' ? (
                          <button type="button" disabled={busy} onClick={() => choose(hold.markerId)}>
                            {TEXT.emptyButton}
                          </button>
                        ) : (
                          '운영자 모드'
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {session.mode !== 'operator' && holds.length > 0 && <p>{TEXT.emptyOperatorOnly}</p>}
          {session.mode === 'operator' && chosen !== null && (
            <form aria-label={TEXT.emptyButton} onSubmit={(event) => event.preventDefault()}>
              <p>
                {chosen.robotId}: {chosen.jobOrderId} {chosen.unitId}({chosen.objectRef ?? TEXT.objectUnnamed}). 확인자는 {session.user}입니다.
              </p>
              <p role="note">{TEXT.emptyWarning}</p>
              <label>
                {TEXT.emptyReason}
                <input maxLength={1000} value={reason} disabled={busy} onChange={(event) => setReason(event.target.value)} />
              </label>{' '}
              <label>
                <input type="checkbox" checked={confirmed} disabled={busy} onChange={(event) => setConfirmed(event.target.checked)} />
                {TEXT.emptyConfirm}
              </label>{' '}
              {pending === null ? (
                <button type="button" disabled={busy || reason.trim() === '' || !confirmed} onClick={() => send(chosen)}>
                  {TEXT.emptyButton}
                </button>
              ) : (
                <fieldset aria-label={TEXT.mismatchHeading}>
                  <legend>{TEXT.mismatchHeading}</legend>
                  <p>{TEXT.mismatchIntro}</p>
                  <dl aria-label="발신자 관측">
                    <dt>손</dt>
                    <dd>{pending.observation.observedKind}</dd>
                    <dt>든 것</dt>
                    <dd>{pending.observation.observedObjectRef ?? TEXT.objectUnnamed}</dd>
                    <dt>세션</dt>
                    <dd>{pending.observation.snapshotSession ?? '미상'}</dd>
                    <dt>순번</dt>
                    <dd>{pending.observation.snapshotSequence ?? '미상'}</dd>
                    <dt>호스트가 읽은 시각</dt>
                    <dd>{pending.observation.hostReadReceivedAt ?? '미상'}</dd>
                  </dl>
                  <label>
                    <input type="checkbox" checked={acknowledged} disabled={busy} onChange={(event) => setAcknowledged(event.target.checked)} />
                    {TEXT.mismatchAcknowledge(pending.observation.observedObjectRef ?? TEXT.objectUnnamed)}
                  </label>{' '}
                  <button type="button" disabled={busy || reason.trim() === '' || !confirmed || !acknowledged} onClick={() => send(chosen)}>
                    {TEXT.reconfirmButton}
                  </button>{' '}
                  <button type="button" disabled={busy} onClick={() => choose(null)}>
                    닫기
                  </button>
                </fieldset>
              )}
            </form>
          )}
        </>
      )}
    </section>
  )
}

function describeEmpty(sent: Delivered<RobotEmptyOutcome>): string {
  if (sent.kind === 'refused') return `보내지 않음(${kindLabel(sent.refusal.error)}). ${sent.refusal.detail}`
  if (sent.kind === 'unknown') return `결과 모름(${sent.cause}). 잔여 파지 표에서 확인하십시오`
  const { result, rejection, confirmation } = sent.outcome
  if (result === 'SUCCEEDED') return TEXT.emptyDone
  if (mismatchOf(sent.outcome) !== null) return TEXT.mismatchResult
  if (rejection?.error === 'RESIDUAL_HOLD_CITATION_INVALID') return `${TEXT.citationInvalidResult}${rejection.detail ? `. ${rejection.detail}` : ''}`
  if (rejection !== null) return rejectionText('실행 호스트가 거부함', rejection)
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return '응답은 없었으나 다시 읽어 보니 이 요청으로 표시가 해소됨'
    case 'CONFIRMED_NOT_APPLIED':
      return '응답 없음. 다시 읽어 보니 이 요청의 해소가 없음. 잔여 파지 표에서 확인하십시오'
    default:
      return '응답 없음. 해소됐을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 잔여 파지 표에서 확인하십시오'
  }
}
