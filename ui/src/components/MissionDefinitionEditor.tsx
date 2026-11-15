import { useEffect, useState } from 'react'
import {
  EDITABLE_WORK_MASTER,
  activateMissionDraft,
  fetchExecutionSummary,
  fetchMission,
  fetchMissionTemplates,
  mockRunMissionDraft,
  saveMissionDraft,
  validateMissionDraft,
} from '../api'
import type { DraftView, ExecutionSummary, MissionOverview, MissionTemplate, MissionTemplates, Session } from '../api'
import { TEMPLATE_LABEL, mockRunVerdict } from '../labels'
import { POLL_MS } from '../poll'
import type { HostRead } from './ExecutionList'
import { MissionNotice } from './MissionNotice'
import type { MissionSent } from './MissionNotice'
import { MockRunReport } from './MockRunReport'

interface Props {
  workMasterId?:string
  session: Session
  /** 조작이 끝나면 부른다. 조작 기록을 다시 읽는다. */
  onChanged: () => void
  onOverview?: (view: MissionOverview | null) => void
  sectionMode?: 'all' | 'definition' | 'versions'
  onEditRequest?: () => void
}

/** 검증·모의 실행·활성화의 대상. 저장한 그 글자이고, 편집기가 그 글자와 다르면 대상이 아니다. */
interface Target {
  draftId: number
  definition: string
}

const message = (error: unknown) => (error instanceof Error ? error.message : String(error))

/**
 * «임무·정책» 영역(S3b 스펙 §8). PrepareSequencedRack 하나의 활성 버전, 버전 이력, 초안과 그 마지막 모의 실행을 보이고, 엔지니어
 * 모드에서 정의 JSON 을 편집해 초안 저장, 검증, 모의 실행, 활성화한다. 초안은 자유롭고 활성화만 게이트다.
 *
 * 임무 개요는 실행 호스트를 거친다. 그래서 App 의 다섯 조회와 따로, 이 영역이 열려 있을 때만 읽는다(S3a 스펙 §9.3). 못 읽으면
 * 직전 값을 지우지 않고 불통을 표시한다(S3b 스펙 §9).
 *
 * 편집기의 처음 내용은 활성 버전의 정의이고, 활성 버전이 코드 정의면 첫 템플릿(데이터 정의)이다. 손대기 전에는 그 값을 따른다.
 * 편집기는 textarea 이고 정의의 옳고 그름은 실행 호스트가 검증한다(S3b 스펙 T4). 임무 개요를 읽기 전에는 편집기의 처음 내용을
 * 모르므로(빈 글자) 초안 저장을 막는다.
 *
 * 활성화 사유는 활성화가 섰을 때(`ACTIVATED`)만 지운다. 거부·모의 실행 없음·모름·응답 없음이면 그대로 두어 고쳐 다시 보낼 수 있게
 * 한다.
 */
export function MissionDefinitionEditor({ session, onChanged, onOverview, sectionMode = 'all', onEditRequest,workMasterId=EDITABLE_WORK_MASTER }: Props) {
  const [tick, setTick] = useState(0)
  const [overview, setOverview] = useState<HostRead<MissionOverview>>({ value: null, error: null })
  const [templates, setTemplates] = useState<HostRead<MissionTemplates>>({ value: null, error: null })
  const [edited, setEdited] = useState<string | null>(null)
  const [target, setTarget] = useState<Target | null>(null)
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<MissionSent | null>(null)
  // 플릿 운반 모의 실행 안내가 이 호스트의 플릿 연결을 말하려고 읽는다. 못 읽으면 모름(단정하지 않음).
  const [fleet, setFleet] = useState<ExecutionSummary['fleet']>(undefined)
  useEffect(() => {
    let alive = true
    fetchExecutionSummary(session).then((value) => { if (alive) setFleet(value.fleet) }).catch(() => { if (alive) setFleet(undefined) })
    return () => { alive = false }
  }, [session, tick])

  useEffect(() => {
    const timer = setInterval(() => setTick((value) => value + 1), POLL_MS)
    return () => clearInterval(timer)
  }, [])

  useEffect(() => {
    let alive = true
    fetchMission(session, workMasterId)
      .then((value) => {
        if (alive) {
          setOverview({ value, error: null })
          onOverview?.(value)
        }
      })
      .catch((error: unknown) => {
        if (alive) {
          setOverview((previous) => ({ ...previous, error: message(error) }))
          onOverview?.(null)
        }
      })
    return () => {
      alive = false
    }
  }, [session, tick, workMasterId, onOverview])

  // 템플릿은 바뀌지 않는다. 한 번 읽으면 다시 읽지 않고, 못 읽었으면 주기마다 다시 묻는다.
  const templatesKnown = templates.value !== null
  useEffect(() => {
    if (templatesKnown) return
    let alive = true
    fetchMissionTemplates(session, workMasterId)
      .then((value) => {
        if (alive) setTemplates({ value, error: null })
      })
      .catch((error: unknown) => {
        if (alive) setTemplates((previous) => ({ ...previous, error: message(error) }))
      })
    return () => {
      alive = false
    }
  }, [session, tick, workMasterId, templatesKnown])

  // 운영 서비스가 다른 모양을 주면(예: 시험 대역의 빈 배열) 칸이 없다. 없는 칸도 모름이다.
  const view = overview.value
  const known =
    view !== null && view.active != null && Array.isArray(view.versions) && Array.isArray(view.drafts) ? view : null
  const templateList = Array.isArray(templates.value?.templates) ? templates.value.templates : null
  const activeDefinition = known?.active.detail?.definition ?? null
  const initial = known === null ? null : (activeDefinition ?? templateList?.[0]?.definition ?? null)
  const text = edited ?? initial ?? ''
  const ready = target !== null && target.definition === text
  const engineer = session.mode === 'engineer'

  const run = (sent: Promise<MissionSent>) => {
    setBusy(true)
    setProblem(null)
    sent
      .then((next) => {
        setLast(next)
        if (next.op === 'save' && next.sent.kind === 'outcome') {
          const draft = next.sent.outcome.outcome?.draft
          if (draft != null) setTarget({ draftId: draft.draftId, definition: draft.definition })
        }
        if (next.op === 'activate' && next.sent.kind === 'outcome' && next.sent.outcome.outcome?.result === 'ACTIVATED') {
          setReason('')
        }
      })
      .catch((error: unknown) => setProblem(message(error)))
      .finally(() => {
        setBusy(false)
        setTick((value) => value + 1)
        onChanged()
      })
  }

  const save = () =>
    run(
      saveMissionDraft(session, workMasterId, text).then((sent) => ({ op: 'save' as const, what: '초안 저장', sent })),
    )

  const validate = (draftId: number) =>
    run(
      validateMissionDraft(session, workMasterId, draftId).then((sent) => ({
        op: 'validate' as const,
        what: `초안 ${draftId} 검증`,
        sent,
      })),
    )

  const mockRun = (draftId: number) =>
    run(
      mockRunMissionDraft(session, workMasterId, draftId).then((sent) => ({
        op: 'mockRun' as const,
        what: `초안 ${draftId} 모의 실행`,
        sent,
      })),
    )

  const activate = (draftId: number) => {
    if (reason.trim() === '') {
      setProblem('활성화 사유를 넣으십시오')
      return
    }
    run(
      activateMissionDraft(session, workMasterId, draftId, reason.trim()).then((sent) => ({
        op: 'activate' as const,
        what: `초안 ${draftId} 활성화`,
        sent,
      })),
    )
  }

  const open = (draft: DraftView) => {
    setEdited(draft.definition)
    setTarget({ draftId: draft.draftId, definition: draft.definition })
    setProblem(null)
    onEditRequest?.()
  }

  const targetDraft = ready ? known?.drafts.find((draft) => draft.draftId === target.draftId) : undefined

  return (
    <>
      <section aria-label={`임무 ${workMasterId}`}>
        <h2>{workMasterId}</h2>
        {known === null ? (
          <p>모름: 임무 버전을 아직 읽지 못했습니다{overview.error !== null && ` (${overview.error})`}</p>
        ) : (
          <>
            {overview.error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {overview.error}</p>}
            <dl>
              <dt>활성 버전</dt>
              <dd>{known.active.version === null ? '코드 정의' : `버전 ${known.active.version}`}</dd>
              {known.active.detail !== null && (
                <>
                  <dt>활성화</dt>
                  <dd>
                    {known.active.detail.activatedBy} {known.active.detail.activatedAt}: {known.active.detail.reason}
                  </dd>
                </>
              )}
            </dl>
          </>
        )}
      </section>
      <div hidden={sectionMode === 'versions'}>
      {engineer ? (
        <section aria-label="임무 편집">
          <h2>편집</h2>
          {last !== null && <MissionNotice last={last} fleet={fleet} />}
          <Templates
            templates={templateList}
            error={templates.error}
            busy={busy}
            onLoad={(definition) => {
              setEdited(definition)
              setProblem(null)
            }}
          />
          <div className="split">
            <label className="editor">
              임무 정의 JSON
              <textarea
                rows={24}
                spellCheck={false}
                value={text}
                onChange={(event) => setEdited(event.target.value)}
              />
            </label>
            <ActiveDefinition known={known} definition={activeDefinition} text={text} />
          </div>
          <p>
            {target === null
              ? '저장한 초안이 없습니다. 초안을 저장하면 검증·모의 실행·활성화할 수 있습니다'
              : ready
                ? `대상: 초안 ${target.draftId}, 마지막 모의 실행 ${
                    targetDraft?.lastMockRun != null ? mockRunVerdict(targetDraft.lastMockRun) : '없음'
                  }`
                : `편집기 내용이 초안 ${target.draftId} 의 내용과 다릅니다. 검증하려면 초안을 다시 저장하십시오`}
          </p>
          <div className="actions">
            <button type="button" disabled={busy || known === null || text.trim() === ''} onClick={save}>
              초안 저장
            </button>
            <button type="button" disabled={busy || !ready} onClick={() => target && validate(target.draftId)}>
              검증
            </button>
            <button type="button" disabled={busy || !ready} onClick={() => target && mockRun(target.draftId)}>
              모의 실행
            </button>
            <label>
              활성화 사유
              <input value={reason} onChange={(event) => setReason(event.target.value)} />
            </label>
            <button type="button" disabled={busy || !ready} onClick={() => target && activate(target.draftId)}>
              활성화
            </button>
          </div>
          {problem !== null && <p role="alert">{problem}</p>}
        </section>
      ) : (
        <section aria-label="임무 편집">
          <p>임무 편집은 엔지니어 모드에서 합니다</p>
          <ActiveDefinition known={known} definition={activeDefinition} text={null} />
        </section>
      )}
      </div>
      <div hidden={sectionMode === 'definition'}>
      {known !== null && (
        <>
          <section aria-label="버전 이력">
            <h2>버전 이력</h2>
            {known.versions.length === 0 ? (
              <p>활성화한 버전이 없습니다. 코드 정의로 돕니다</p>
            ) : (
              <div className="table-scroll" tabIndex={0} role="group" aria-label="표 가로 스크롤"><table aria-label="임무 버전 이력">
                <thead>
                  <tr>
                    <th>버전</th>
                    <th>초안</th>
                    <th>활성화한 사람</th>
                    <th>사유</th>
                    <th>활성화 시각</th>
                  </tr>
                </thead>
                <tbody>
                  {known.versions.map((version) => (
                    <tr
                      key={version.version}
                      className={version.version === known.active.version ? 'selected' : undefined}
                    >
                      <td>
                        버전 {version.version}
                        {version.version === known.active.version && ' (활성)'}
                      </td>
                      <td>초안 {version.draftId}</td>
                      <td>{version.activatedBy}</td>
                      <td>{version.reason}</td>
                      <td>{version.activatedAt}</td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
            )}
          </section>
          <section aria-label="초안">
            <h2>초안</h2>
            {known.drafts.length === 0 ? (
              <p>저장한 초안이 없습니다</p>
            ) : (
              <div className="table-scroll" tabIndex={0} role="group" aria-label="표 가로 스크롤"><table aria-label="임무 초안 목록">
                <thead>
                  <tr>
                    <th>초안</th>
                    <th>저장한 사람</th>
                    <th>저장 시각</th>
                    <th>마지막 모의 실행</th>
                    {engineer && <th>조작</th>}
                  </tr>
                </thead>
                <tbody>
                  {known.drafts.map((draft) => (
                    <tr key={draft.draftId} className={ready && target.draftId === draft.draftId ? 'selected' : undefined}>
                      <td>초안 {draft.draftId}</td>
                      <td>{draft.savedBy}</td>
                      <td>{draft.savedAt}</td>
                      <td>
                        {draft.lastMockRun === null ? (
                          '없음'
                        ) : (
                          <details>
                            <summary>
                              {mockRunVerdict(draft.lastMockRun)} {draft.lastMockRun.finishedAt}
                            </summary>
                            <MockRunReport run={draft.lastMockRun} fleet={fleet} />
                          </details>
                        )}
                      </td>
                      {engineer && (
                        <td>
                          <button type="button" disabled={busy} onClick={() => open(draft)}>
                            초안 {draft.draftId} 열기
                          </button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table></div>
            )}
          </section>
        </>
      )}
      </div>
    </>
  )
}

/** 시작용 정의 둘을 편집기에 불러오는 버튼(S3b 스펙 §8). 불러오면 편집기 내용을 바꾸고 저장하지 않는다. */
function Templates({
  templates,
  error,
  busy,
  onLoad,
}: {
  templates: MissionTemplate[] | null
  error: string | null
  busy: boolean
  onLoad: (definition: string) => void
}) {
  if (templates === null) {
    return <p>모름: 템플릿을 아직 읽지 못했습니다{error !== null && ` (${error})`}</p>
  }
  return (
    <ul aria-label="템플릿">
      {templates.map((template) => (
        <li key={template.id}>
          <button type="button" disabled={busy} onClick={() => onLoad(template.definition)}>
            {TEMPLATE_LABEL[template.id] ?? template.title} 불러오기
          </button>{' '}
          {template.title}
        </li>
      ))}
    </ul>
  )
}

/**
 * 활성 버전의 정의. 편집기 옆에 두어 나란히 비교한다(S3b 스펙 T4). 코드 정의는 정의 JSON 이 없다. [text] 가 있으면 편집기 내용이
 * 활성 버전과 같은지 적는다.
 */
function ActiveDefinition({
  known,
  definition,
  text,
}: {
  known: MissionOverview | null
  definition: string | null
  text: string | null
}) {
  if (known === null) return <p>모름: 활성 버전을 아직 읽지 못했습니다</p>
  return (
    <div>
      <p>
        {known.active.source==='UNPUBLISHED' ? '활성 버전 없음 · 검증 후 활성화하세요' : known.active.version === null ? '활성: 코드 정의' : `활성: 버전 ${known.active.version}`}
        {text !== null && definition !== null && (text === definition ? ', 편집기와 같음' : ', 편집기와 다름')}
      </p>
      {definition === null ? (
        <p>{known.active.source==='UNPUBLISHED'?'등록된 템플릿으로 첫 초안을 작성하세요.':'코드 정의는 정의 JSON 이 없습니다'}</p>
      ) : (
        <pre aria-label="활성 버전 정의">{definition}</pre>
      )}
    </div>
  )
}
