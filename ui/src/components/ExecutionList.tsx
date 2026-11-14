import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { faultBlocked, fetchSettledBefore, RECENT_EXECUTIONS } from '../api'
import type {
  CancelRecord,
  Delivered,
  Execution,
  ExecutionCloseOutcome,
  ExecutionPage,
  JobResponse,
  ReleaseRecord,
  RestoreRow,
  Session,
  SettledExecution,
  UnrestoredRow,
} from '../api'
import { appendOlder, catchUp, olderCount, olderCursor, sameExecution } from '../executionHistory'
import type { ExecutionHistory } from '../executionHistory'
import { EXECUTION_CONTROL_TEXT, EXECUTION_LIST_TEXT as TEXT, UNRESTORED_TEXT } from '../labels'
import { CancelExecutionForm, CloseResultStatus, CloseUnrestoredForm, ReleaseFaultForm, ReworkSkippedForm } from './ExecutionControls'
import { PermitBlockNotice } from './PermitBlockNotice'

/** 실행 호스트를 거친 읽기 하나. 못 읽으면 직전 값을 지우지 않고 [error] 로 불통을 표시한다. */
export interface HostRead<T> {
  value: T | null
  error: string | null
}

interface Props {
  read: HostRead<ExecutionPage>
  /** 방금 낸 작업 지시 id. 그 실행 행을 눈에 띄게 한다. */
  highlight: string | null
  /** 주면 표 아래에 운영자 실행 취소 폼, 결함 감수 폼과 이전 실행(정착 이력 페이지)을 보인다(P0-b2, HA 설계 기록 §10). */
  session?: Session
  /** 취소·결함 감수 조작이 끝나면 부른다. */
  onCanceled?: () => void
}

/** 남은 실행과 같은 실행인 정착 이력 줄을 뺀다. 남은 실행 쪽만 보인다. */
const withoutLive = (rows: SettledExecution[], page: ExecutionPage) =>
  rows.filter((row) => !page.executions.some((execution) => sameExecution({ instanceId: row.instanceId, executionId: row.execution.executionId }, { instanceId: page.instanceId, executionId: execution.executionId })))

/**
 * 실행 목록(S3a 스펙 §9.2, HA 설계 기록 §10). 머리에 실행 호스트 인스턴스를 보인다. 호스트를 재기동하면 실행이 사라지고 `exec-N` 을 1부터
 * 다시 세므로, 인스턴스가 바뀐 것으로 구별한다. 임무 버전이 null 이면 코드 정의 임무다.
 *
 * 표에는 지금 인스턴스의 남은 실행(활성이거나 정착 기록 전, 최신부터)과 최근 정착 이력(최근 정착부터)을 차례로 보인다. 정착 이력은 실행
 * 호스트가 정착을 적을 때 함께 적은 본문이라 미들웨어 메모리에서 실행이 사라져도 남고, 다른 인스턴스의 것이면 실행 id 옆에 그 인스턴스를
 * 붙인다. 남은 실행과 같은 실행(같은 인스턴스·실행 id)인 정착 이력 줄은 남은 실행 쪽만 보인다. 그보다 오래된 정착 이력은 표 아래 «이전
 * 실행» 에서 페이지로 더 읽는다.
 *
 * 재기동 뒤(S4b 스펙 T10, §8)에는 목록 위에 이번 기동의 복원 보고를 보이고, 다시 지은 실행의 실행 id 옆에 바로 앞 인스턴스의
 * 실행 id 를 «이전 exec-k» 로 붙인다.
 *
 * 운영자 실행 취소(P0-b2): 물리 상태 칸에 이 인스턴스에서 건 취소를 붙이고, 표 아래에 취소 폼을 둔다. 부분 완료(PARTIAL) 실행은
 * 취소해야 그 기체가 풀린다. 취소할 실행은 남은 실행에서만 고른다.
 *
 * 작업 허가 대기(ADR 52): 물리 상태 칸에 막은 기체·막는 주체·풀릴 근거를 따로 붙인다. 운영자 보류(물리 상태)·결함 막음(작업 응답)과
 * 다른 표시이고 운영자 조작이 없다.
 *
 * 결함 막음과 운영자 결함 감수(S3a JSON 계약 §5.9·§5.10): 물리 상태 칸에 지금 막는 결함의 수와 그 작업 지시에 건 감수를 붙이고, 취소
 * 폼 아래에 결함 감수 폼을 둔다. 감수할 실행은 남은 실행 가운데 막는 결함이 있는 것에서만 고른다.
 *
 * 실패 대상 재작업: 결함 수용 폼 아래에 둔다. 재작업할 대상은 남은 실행이 아니라 실행 호스트가 picasso 후보로 싣는 목록(`reworkableTargets`)에서
 * 고른다. 종결해 이력으로만 보이는 `FAILED` 실행의 대상도 보존 기간 안에는 든다.
 */
export function ExecutionList({ read, highlight, session, onCanceled }: Props) {
  const { value, error } = read
  // 마지막 운영자 종결 결과. 종결하면 목록을 다시 읽어 그 행과 종결 폼이 내려가므로 결과 줄은 여기서 쥔다.
  const [lastClose, setLastClose] = useState<{ jobOrderId: string; sent: Delivered<ExecutionCloseOutcome> } | null>(null)
  if (value === null) {
    return <p>모름: 실행 목록을 아직 읽지 못했습니다{error !== null && ` (${error})`}</p>
  }
  const live = [...value.executions].reverse()
  const settled = withoutLive(value.settled, value)
  return (
    <>
      {error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {error}</p>}
      {!!value.journalBlockedExecutionIds?.length && <p role="alert">실행 일지 저장 실패로 호스트의 명령 진행과 신규 배정이 차단되었습니다: {value.journalBlockedExecutionIds.join(', ')}</p>}
      <dl>
        <dt>실행 호스트 인스턴스</dt>
        <dd>{value.instanceId}</dd>
        <dt>마지막 pump</dt>
        <dd>{value.pumpedAt ?? '아직 없음'}</dd>
      </dl>
      {value.restore != null && value.restore.rows.length > 0 && (
        <RestoreReport at={value.restore.at} rows={value.restore.rows} />
      )}
      {!!value.unrestored?.length && (
        <UnrestoredExecutions
          rows={value.unrestored}
          session={session}
          onClosed={onCanceled ?? (() => undefined)}
          onResult={(jobOrderId, sent) => setLastClose({ jobOrderId, sent })}
        />
      )}
      {lastClose !== null && <CloseResultStatus jobOrderId={lastClose.jobOrderId} sent={lastClose.sent} />}
      {live.length === 0 && settled.length === 0 ? (
        <p>실행이 없습니다</p>
      ) : (
        <>
          <p>{TEXT.scope(RECENT_EXECUTIONS)}</p>
          <ExecutionTable label="실행 목록">
            {live.map((execution) => (
              <ExecutionRow key={`live/${execution.executionId}`} execution={execution} selected={execution.jobOrderId === highlight} />
            ))}
            {settled.map((row) => (
              <ExecutionRow
                key={`settled/${row.settleSeq}`}
                execution={row.execution}
                settled={row}
                currentInstance={value.instanceId}
                selected={row.execution.jobOrderId === highlight}
              />
            ))}
          </ExecutionTable>
        </>
      )}
      {session !== undefined && (
        <CancelExecutionForm
          instanceId={value.instanceId}
          executions={value.executions}
          session={session}
          onCanceled={onCanceled ?? (() => undefined)}
        />
      )}
      {session !== undefined && (
        <ReleaseFaultForm
          instanceId={value.instanceId}
          executions={value.executions}
          session={session}
          onReleased={onCanceled ?? (() => undefined)}
        />
      )}
      {session !== undefined && (
        <ReworkSkippedForm
          instanceId={value.instanceId}
          targets={value.reworkableTargets ?? []}
          session={session}
          onReworked={onCanceled ?? (() => undefined)}
        />
      )}
      {session !== undefined && <OlderExecutions page={value} session={session} />}
    </>
  )
}

function ExecutionTable({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="table-scroll" tabIndex={0} role="group" aria-label="표 가로 스크롤"><table aria-label={label}>
      <thead>
        <tr>
          <th>실행 id</th>
          <th>작업 지시 id</th>
          <th>임무</th>
          <th>임무 버전</th>
          <th>기체</th>
          <th>물리 상태</th>
          <th>단위</th>
          <th>작업 응답</th>
        </tr>
      </thead>
      <tbody>{children}</tbody>
    </table></div>
  )
}

interface RowProps {
  execution: Execution
  selected?: boolean
  /** 정착 이력 줄이면 그 행. 정착 시각과 다른 인스턴스, 본문 없는 기록을 붙인다. */
  settled?: SettledExecution
  currentInstance?: string
}

/** 실행 한 줄. */
function ExecutionRow({ execution, selected, settled, currentInstance }: RowProps) {
  return (
    <tr className={selected ? 'selected' : undefined}>
      <td>
        {execution.executionId}
        {execution.restoredFrom != null && (
          <span className="restored" title={`이전 인스턴스 ${execution.restoredFrom.instanceId}`}>
            {' '}
            (이전 {execution.restoredFrom.executionId})
          </span>
        )}
        {settled !== undefined && settled.instanceId !== currentInstance && (
          <span className="restored"> ({TEXT.otherInstance(settled.instanceId)})</span>
        )}
      </td>
      <td>{execution.jobOrderId}</td>
      <td>{execution.workMasterId}</td>
      <td>{execution.missionVersion === null ? '코드 정의' : `버전 ${execution.missionVersion}`}</td>
      <td>{execution.robotId}</td>
      <td>
        {execution.physicalState}
        {settled !== undefined && <span className="restored"> ({TEXT.settledAt(settled.settledAt)})</span>}
        {settled !== undefined && execution.jobResponse != null && (
          <span className="restored"> ({TEXT.orderVersion(execution.jobResponse.version)})</span>
        )}
        {settled?.superseded === true && <span className="restored"> ({TEXT.superseded})</span>}
        {execution.cancel != null && <CancelCell cancel={execution.cancel} />}
        {faultBlocked(execution) && <strong className="fault-blocked">{EXECUTION_CONTROL_TEXT.blockedCount(execution.blockedFaults!.length)}</strong>}
        {(execution.releases ?? []).map((release, index) => (
          <ReleaseCell key={index} release={release} />
        ))}
        {execution.permitBlock != null && <PermitBlockNotice block={execution.permitBlock} />}
      </td>
      <td>
        {settled !== undefined && !settled.snapshot ? (
          TEXT.noSnapshot
        ) : (
          <ul>
            {execution.units.map((unit) => (
              <li key={unit.unitId}>
                {unit.unitId} {unit.skillType}: {unit.state}, 근거 {unit.reached}
              </li>
            ))}
          </ul>
        )}
      </td>
      <td>{execution.jobResponse === null ? '없음' : <JobResponseCell response={execution.jobResponse} />}</td>
    </tr>
  )
}

const message = (error: unknown) => (error instanceof Error ? error.message : String(error))

/**
 * 이전 실행(HA 설계 기록 §10). 주기 읽기의 최근 정착 이력보다 오래된 정착 이력을 «이전 실행 더 보기» 로 한 페이지씩 더 읽는다. 더 읽은 뒤
 * 새 정착으로 최근 정착 이력이 밀리면 밀려난 이력을 채워 넣어(따라잡기) 주기 읽기와 맞닿게 한다. 다 못 채우면 더 읽은 것을 버리고 알린다.
 * 접으면 더 읽은 것을 버린다.
 */
function OlderExecutions({ page, session }: { page: ExecutionPage; session: Session }) {
  const [history, setHistory] = useState<ExecutionHistory | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [reset, setReset] = useState(false)
  // 접거나 버린 뒤 늦게 온 응답을 버린다.
  const epoch = useRef(0)
  const live = page.nextBefore

  useEffect(() => {
    if (history === null || live === history.anchor) return
    const mine = epoch.current
    let alive = true
    catchUp(history, live, (before) => fetchSettledBefore(session, before))
      .then((next) => {
        if (!alive || mine !== epoch.current) return
        if (next === null) {
          epoch.current += 1
          setReset(true)
        }
        setHistory(next)
      })
      .catch((e: unknown) => {
        if (alive && mine === epoch.current) setError(message(e))
      })
    return () => {
      alive = false
    }
  }, [history, live, session])

  const cursor = olderCursor(history, live)
  const more = () => {
    if (cursor === null || live === null || busy) return
    const mine = epoch.current
    setBusy(true)
    fetchSettledBefore(session, cursor)
      .then((older) => {
        if (mine !== epoch.current) return
        setHistory((previous) => appendOlder(previous, live, older))
        setError(null)
        setReset(false)
      })
      .catch((e: unknown) => {
        if (mine === epoch.current) setError(message(e))
      })
      .finally(() => setBusy(false))
  }
  const collapse = () => {
    epoch.current += 1
    setHistory(null)
    setError(null)
    setReset(false)
  }
  if (cursor === null && history === null && !reset) return null
  const rows = withoutLive(history?.items ?? [], page)
  return (
    <section aria-label={TEXT.historyHeading}>
      <h3>{TEXT.historyHeading}</h3>
      {reset && <p role="status">{TEXT.historyReset}</p>}
      {error !== null && <p role="alert">{TEXT.historyError(error)}</p>}
      {rows.length > 0 && (
        <ExecutionTable label={TEXT.historyTable}>
          {rows.map((row) => (
            <ExecutionRow key={row.settleSeq} execution={row.execution} settled={row} currentInstance={page.instanceId} />
          ))}
        </ExecutionTable>
      )}
      {cursor !== null && <p>{TEXT.olderCount(olderCount(history, page.olderTotal))}</p>}
      <div className="actions">
        {cursor !== null && (
          <button type="button" disabled={busy} onClick={more}>
            {TEXT.loadOlder}
          </button>
        )}
        {history !== null && (
          <button type="button" onClick={collapse}>
            {TEXT.collapse}
          </button>
        )}
      </div>
    </section>
  )
}

/** 이 인스턴스에서 그 실행에 건 운영자 취소(P0-b2). */
function CancelCell({ cancel }: { cancel: CancelRecord }) {
  return (
    <span className="restored" title={`${cancel.recordedAt}, 취소 때 ${cancel.physicalState}`}>
      {' '}
      ({EXECUTION_CONTROL_TEXT.canceledBy}: {cancel.approverId}, {cancel.reason})
    </span>
  )
}

/** 그 작업 지시에 건 운영자 결함 감수 한 건(S3a JSON 계약 §5.9). */
function ReleaseCell({ release }: { release: ReleaseRecord }) {
  return (
    <span className="restored" title={`${release.recordedAt}, ${release.faults.map((fault) => fault.errorType).join(', ')}`}>
      {' '}
      ({EXECUTION_CONTROL_TEXT.releasedBy}: {release.approverId}, {release.reason})
    </span>
  )
}

/**
 * 이번 기동의 복원 보고(S4b 계약 H1). 다시 지은 것, 기체 스냅숏을 못 읽어 미룬 것(실행 호스트가 pump 마다 다시 시도),
 * 포기한 것을 나누고, 미룬 것과 포기한 것은 실행 호스트의 사유를 그대로 보인다. 포기한 실행의 기체는 고아 태스크가 끝날
 * 때까지 배정 가능에서 빠진다.
 */
function RestoreReport({ at, rows }: { at: string; rows: RestoreRow[] }) {
  const restored = rows.filter((row) => row.result === 'RESTORED')
  const deferred = rows.filter((row) => row.result === 'DEFERRED')
  const gaveUp = rows.filter((row) => row.result === 'GAVE_UP')
  const unsettled = (row: RestoreRow) =>
    `${row.jobOrderId}(${row.robotId}, 이전 ${row.previousExecutionId}): ${row.reason ?? '사유 없음'}${
      row.close != null ? ` · ${UNRESTORED_TEXT.reportClosed(row.close.approverId)}` : ''
    }`
  return (
    <section aria-label="재기동 복원 보고" className="restore">
      <p>
        <strong>재기동: 이전 인스턴스의 실행 {restored.length}건을 리빌드했습니다</strong>(복원 시각 {at})
      </p>
      {restored.length > 0 && (
        <ul aria-label="리빌드한 실행">
          {restored.map((row) => (
            <li key={row.jobOrderId}>
              {row.jobOrderId}({row.robotId}): 이전 {row.previousExecutionId} → {row.executionId}
            </li>
          ))}
        </ul>
      )}
      {deferred.length > 0 && (
        <>
          <p>미룬 실행 {deferred.length}건. 실행 호스트가 다시 시도하며 그동안 그 기체는 배정에서 빠집니다</p>
          <ul aria-label="미룬 실행">
            {deferred.map((row) => (
              <li key={row.jobOrderId}>{unsettled(row)}</li>
            ))}
          </ul>
        </>
      )}
      {gaveUp.length > 0 && (
        <>
          <p>포기한 실행 {gaveUp.length}건. 기체가 정지했음을 확인한 후 아래 «복원 못 한 실행»에서 종결하십시오.</p>
          <ul aria-label="포기한 실행">
            {gaveUp.map((row) => (
              <li key={row.jobOrderId}>{unsettled(row)}</li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}

/**
 * 복원 못 한 실행(결함 14). 이번 기동의 복원 보고와 달리 앞선 기동에서 포기한 행도 든다. 종결할 수 있는 행은 운영자 모드에서 «종결하기» 로 고르고
 * 종결 폼을 연다. 종결할 수 없는 행(살아 있는 다른 인스턴스가 든 행)은 까닭을 보인다. 정지 증언으로 종결했으나 스냅숏이 아직 동의하지 않은 행은
 * 승인자와 사유를 보이고 그 기체가 배정에서 빠져 있다고 알린다.
 */
function UnrestoredExecutions({
  rows,
  session,
  onClosed,
  onResult,
}: {
  rows: UnrestoredRow[]
  session?: Session
  onClosed: () => void
  onResult: (jobOrderId: string, sent: Delivered<ExecutionCloseOutcome>) => void
}) {
  const [chosen, setChosen] = useState<string | null>(null)
  const operator = session?.mode === 'operator'
  const row = rows.find((candidate) => candidate.jobOrderId === chosen && candidate.closable) ?? null
  return (
    <section aria-label={UNRESTORED_TEXT.heading} className="restore">
      <h3>{UNRESTORED_TEXT.heading}</h3>
      <p>{UNRESTORED_TEXT.intro}</p>
      <div className="table-scroll" tabIndex={0} role="group" aria-label="표 가로 스크롤">
        <table aria-label={UNRESTORED_TEXT.table}>
          <thead>
            <tr>
              <th>작업 지시</th>
              <th>기체</th>
              <th>이전 실행</th>
              <th>결과</th>
              <th>사유</th>
              <th>시각</th>
              <th>조치</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((unrestored) => (
              <tr key={unrestored.jobOrderId} className={unrestored.jobOrderId === row?.jobOrderId ? 'selected' : undefined}>
                <td>{unrestored.jobOrderId}</td>
                <td>{unrestored.robotId}</td>
                <td title={`인스턴스 ${unrestored.previousInstanceId}`}>{unrestored.previousExecutionId}</td>
                <td>
                  {UNRESTORED_TEXT.result[unrestored.result] ?? unrestored.result}
                  {unrestored.close !== null && (
                    <>
                      {' '}
                      ({UNRESTORED_TEXT.closedBy(unrestored.close.approverId, unrestored.close.reason)}). {UNRESTORED_TEXT.excludedUntilSnapshot}
                    </>
                  )}
                </td>
                <td>{unrestored.reason ?? '사유 없음'}</td>
                <td>{unrestored.since}</td>
                <td>
                  {unrestored.closable ? (
                    operator ? (
                      <button type="button" onClick={() => setChosen(unrestored.jobOrderId)}>
                        {UNRESTORED_TEXT.closeButton}
                      </button>
                    ) : null
                  ) : (
                    unrestored.notClosableReason
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {session !== undefined && !operator && rows.some((unrestored) => unrestored.closable) && <p>{UNRESTORED_TEXT.operatorOnly}</p>}
      {session !== undefined && operator && row !== null && (
        <CloseUnrestoredForm key={row.jobOrderId} row={row} session={session} onClosed={onClosed} onResult={(sent) => onResult(row.jobOrderId, sent)} />
      )}
    </section>
  )
}

/** 그 실행의 마지막 작업 응답. 상위 시스템이 없어 실행 호스트의 아웃박스에 남은 것이다(S3a 스펙 §7.7). */
function JobResponseCell({ response }: { response: JobResponse }) {
  const incomplete = Object.entries(response.incompleteUnits)
  return (
    <ul>
      <li>
        {response.jobResponseId}: {response.physicalState}, 근거 {response.reachedEvidence}(요구{' '}
        {response.requiredEvidence})
      </li>
      {response.unverifiedUnits.length > 0 && <li>미확인 단위 {response.unverifiedUnits.join(', ')}</li>}
      {incomplete.length > 0 && (
        <li>미완 단위 {incomplete.map(([unitId, reason]) => `${unitId}(${reason})`).join(', ')}</li>
      )}
      {response.inDoubtUnits.length > 0 && <li>불확실 단위 {response.inDoubtUnits.join(', ')}</li>}
      {response.operatorRequired && <li>운영자 개입 필요</li>}
    </ul>
  )
}
