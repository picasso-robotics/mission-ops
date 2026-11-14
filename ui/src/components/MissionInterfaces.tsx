import { MissionResourceNotice } from './MissionResourceSettings'
import { EligibilityTable } from './EligibilityTable'
import { InterfaceDeployment } from './InterfaceDeployment'
import { useEffect, useRef, useState } from 'react'
import { checkInterfaceEligibility, changeMissionInterfaceState, createMissionInterface, fetchMissionInterfaces, invokeMissionInterface, previewMissionInterface } from '../api'
import type { InterfaceEligibility, InterfaceInvocation, MissionInterfaceSpec, MissionInterfaceView, Session, JobContract } from '../api'

type WorkMaster = MissionInterfaceSpec['workMasterId']
const defaults = (wm: WorkMaster,contracts:JobContract[]=[]) => {
 const profile=contracts.find(c=>c.workMasterId===wm)?.profile
 if(profile)return {mapping:Object.fromEntries(Object.keys(profile.inputSchema.properties).map(key=>[key,`/${key}`])),sample:profile.sample}
 return wm === 'InspectAsset'
  ? { mapping: { targets: '/assets' } as Record<string, string>, sample: { assets: [{ id: 'T1', location: 'bay-7' }] } }
  : { mapping: { slots: '/rackSlots', material: '/materialCode', presentation: '/source' }, sample: { rackSlots: ['RACK-204.S01'], materialCode: 'ENGINE-COVER-A', source: 'SEQ-IN-02.BIN-A' } }
}
const stateLabel = { DRAFT: '초안', ENABLED: '공개', DISABLED: '공개 중지' }
const errorText = (e: unknown) => e instanceof Error ? e.message : String(e)

export function MissionInterfaces({ session, onChanged,contracts=[],initialWorkMaster }: { session: Session; onChanged: () => void;contracts?:JobContract[];initialWorkMaster?:string }) {
  const [rows, setRows] = useState<MissionInterfaceView[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [reload, setReload] = useState(0)
  const [creating, setCreating] = useState(false)
  const [editorOpened, setEditorOpened] = useState(false)
  const [seed, setSeed] = useState<MissionInterfaceSpec | null>(null)
  const [editorRevision, setEditorRevision] = useState(0)
  const [selection, setSelection] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [reason, setReason] = useState('')
  const selected = rows?.find(row => key(row) === selection)
  useEffect(() => {
    let alive = true
    fetchMissionInterfaces(session).then(value => {
      if (!Array.isArray(value.interfaces)) throw new Error('호출 계약 목록 응답을 읽을 수 없습니다.')
      if (alive) { setRows(value.interfaces); setError(null) }
    }).catch((e: unknown) => { if (alive) setError(errorText(e)) })
    return () => { alive = false }
  }, [session, reload])
  function accept(row: MissionInterfaceView) {
    setRows(previous => [row, ...(previous ?? []).filter(old => key(old) !== key(row))])
    setSelection(key(row)); onChanged()
  }
  async function state(row: MissionInterfaceView, next: 'ENABLED' | 'DISABLED') {
    setBusy(true); setError(null); setNotice(null)
    try {
      const updated = await changeMissionInterfaceState(session, row, next, reason.trim())
      accept(updated); setReason(''); setNotice(`${row.interfaceId} v${row.version}: ${stateLabel[updated.status]}`)
    } catch (e: unknown) { setError(`${errorText(e)} · 응답 불명 시 상태를 다시 조회하세요.`) }
    finally { setBusy(false) }
  }
  return <section className="managed-interfaces" aria-label="버전별 호출 계약">
    <div className="mission-section-heading"><div><h2>버전별 호출 계약</h2><p>외부 필드를 임무 입력에 연결하고 저장·공개합니다. 현재 호출 경로는 로컬 Ops PoC용입니다.</p></div><div className="actions">
      <button type="button" disabled={busy} onClick={() => setReload(n => n + 1)}>호출 계약 다시 조회</button>
      {session.mode === 'engineer' && <button type="button" className="primary" onClick={() => { setEditorOpened(true); setCreating(value => !value) }}>{creating ? '작성 폼 접기' : '호출 계약 만들기'}</button>}
    </div></div>
    {error && <p role="alert">{error}</p>}{notice && <p role="status" aria-label="호출 계약 처리 결과">{notice}</p>}
    {editorOpened && <div hidden={!creating}><InterfaceEditor key={editorRevision} seed={seed} contracts={contracts} initialWorkMaster={initialWorkMaster} session={session} onSaved={row => { accept(row); setNotice(`${row.interfaceId} v${row.version}: 초안 저장됨`) }} /></div>}
    {rows === null ? <p>호출 계약 목록을 아직 읽지 못했습니다.</p> : rows.length === 0 ? <p>저장된 호출 계약이 없습니다. 입력 매핑을 검증하고 첫 버전을 만들어 보세요.</p> :
      <div className="table-scroll" tabIndex={0} role="group" aria-label="호출 계약 표 스크롤"><table aria-label="저장된 호출 계약"><thead><tr><th>임무</th><th>식별자·버전</th><th>대상 임무</th><th>상태</th><th>등록자</th><th>선택</th></tr></thead><tbody>{rows.map(row => <tr key={key(row)} className={key(row) === selection ? 'selected' : undefined}><td>{row.name}</td><td><code>{row.interfaceId}/v{row.version}</code></td><td>{row.workMasterId}</td><td>{stateLabel[row.status]}</td><td>{row.createdBy}</td><td><button type="button" disabled={busy} onClick={() => { setSelection(key(row)); setReason(''); setNotice(null) }}>{row.interfaceId} v{row.version} 보기</button></td></tr>)}</tbody></table></div>}
    {selected && <div className="saved-interface" role="region" aria-label="선택한 호출 계약">
      <h3>{selected.name} · v{selected.version}</h3><p>대상 임무: <code>{selected.workMasterId}</code></p>
      {session.mode === 'engineer' && !creating && <button type="button" onClick={() => {
        const next = Math.max(...(rows ?? []).filter(row => row.interfaceId === selected.interfaceId).map(row => row.version)) + 1
        setSeed({ ...selected, version: next }); setEditorRevision(n => n + 1); setEditorOpened(true); setCreating(true)
      }}>새 버전으로 복제</button>}<p>매핑 정의는 버전별로 고정됩니다. 변경은 새 버전으로 저장하세요. 실행 정의는 <strong>{selected.deployment ? `배포 v${selected.deployment.deploymentVersion} · 임무 ${selected.deployment.missionVersion}` : (!['InspectAsset','PrepareSequencedRack'].includes(selected.workMasterId) ? '배포 전 · 숫자 임무 버전을 연결하세요' : '배포 정보 다시 조회 필요')}</strong>에 고정됩니다.</p>
      <pre aria-label="저장된 매핑">{JSON.stringify(selected.mapping, null, 2)}</pre>
      {session.mode === 'engineer' && <div className="actions"><label>공개 변경 사유<input value={reason} disabled={busy} onChange={e => setReason(e.target.value)} /></label><button type="button" disabled={busy || reason.trim() === ''} onClick={() => state(selected, selected.status === 'ENABLED' ? 'DISABLED' : 'ENABLED')}>{selected.status === 'ENABLED' ? '공개 중지' : '호출 계약 공개'}</button></div>}
      {(selected.deployment || !['InspectAsset','PrepareSequencedRack'].includes(selected.workMasterId)) && <InterfaceDeployment row={selected} session={session} onSaved={accept} />}
      <InterfaceCaller key={`${key(selected)}/${session.mode}/${session.user}`} row={selected} session={session} />
    </div>}
  </section>
}
const key = (row: MissionInterfaceView) => `${row.interfaceId}/${row.version}`

function InterfaceEditor({ session, onSaved, seed,contracts=[],initialWorkMaster }: { seed: MissionInterfaceSpec | null; session: Session; onSaved: (row: MissionInterfaceView) => void;contracts?:JobContract[];initialWorkMaster?:string }) {
  const [wm, setWm] = useState<WorkMaster>(seed?.workMasterId ?? initialWorkMaster ?? 'PrepareSequencedRack')
  const [id, setId] = useState(seed?.interfaceId ?? (wm==='PrepareSequencedRack'?'prepare-rack':wm.replace(/[A-Z]/g,(c,index)=>(index?'-':'')+c.toLowerCase())))
  const [version, setVersion] = useState(String(seed?.version ?? 1))
  const [name, setName] = useState(seed?.name ?? (contracts.find(c=>c.workMasterId===wm)?.name??'서열 랙 준비 요청'))
  const [mapping, setMapping] = useState(seed?.mapping ?? defaults(wm,contracts).mapping)
  const [sample, setSample] = useState(JSON.stringify(seed?.sample ?? defaults(wm,contracts).sample, null, 2))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [preview, setPreview] = useState<{ signature: string; value: unknown } | null>(null)
  const [savedSignature, setSavedSignature] = useState<string | null>(null)
  const signature = JSON.stringify({ id, version, name, wm, mapping, sample })
  async function run(save: boolean) {
    setError(null); setBusy(true); setPreview(null)
    try {
      if (!Number.isInteger(Number(version)) || Number(version) < 1) throw new Error('버전은 양의 정수여야 합니다.')
      const spec: MissionInterfaceSpec = { interfaceId: id, version: Number(version), name, workMasterId: wm, mapping, sample: JSON.parse(sample) }
      if (save) { onSaved(await createMissionInterface(session, spec)); setSavedSignature(signature) }
      else setPreview({ signature, value: await previewMissionInterface(session, spec) })
    } catch (e: unknown) { setError(errorText(e)) }
    finally { setBusy(false) }
  }
  return <div className="interface-editor" role="region" aria-label="호출 계약 작성"><h3>입력 매핑과 새 계약 버전</h3><p>JSON Pointer로 원본 입력 위치를 지정합니다. 예: /order/material. 배열은 전체 목록을 연결합니다. 저장 시 샘플을 실제 임무 검증기로 검사합니다.</p>
    <fieldset disabled={busy || session.mode !== 'engineer'}><legend>계약 정의</legend><div className="interface-fields">
      <label>호출 식별자<input value={id} onChange={e => setId(e.target.value)} /></label><label>계약 버전<input type="number" min="1" value={version} onChange={e => setVersion(e.target.value)} /></label><label>계약 이름<input value={name} onChange={e => setName(e.target.value)} /></label>
      <label>대상 임무<select aria-label="대상 임무" value={wm} onChange={e => { const next = e.target.value as WorkMaster; setWm(next); setMapping(defaults(next,contracts).mapping); setSample(JSON.stringify(defaults(next,contracts).sample, null, 2)); setPreview(null) }}><option value="PrepareSequencedRack">서열 랙 준비</option><option value="InspectAsset">자산 점검</option>{contracts.filter(c=>c.profile).map(c=><option key={c.workMasterId} value={c.workMasterId}>{c.name}</option>)}</select></label>
      {Object.entries(mapping).map(([target, source]) => <label key={target}>{target} 원본 경로<input value={source} onChange={e => setMapping(previous => ({ ...previous, [target]: e.target.value }))} /></label>)}
    </div><label className="editor">호출 계약 샘플<textarea rows={10} value={sample} spellCheck={false} onChange={e => setSample(e.target.value)} /></label><div className="actions"><button type="button" onClick={() => run(false)}>매핑 검증·미리보기</button><button type="button" className="primary" disabled={signature === savedSignature} onClick={() => run(true)}>계약 초안 저장</button></div></fieldset>
    {error && <p role="alert">{error}</p>}{savedSignature === signature && <p>이 정의는 저장되었습니다. 변경하려면 새 버전 번호를 입력하세요.</p>}
    {preview && (preview.signature === signature ? <pre aria-label="계약 매핑 미리보기">{JSON.stringify(preview.value, null, 2)}</pre> : <p role="status">입력이 변경되었습니다. 매핑을 다시 검증하세요.</p>)}
  </div>
}

interface PendingInvocation { requestId: string; input: unknown; canStartNew: boolean; receiptId?: string; expectedDeploymentVersion?: number }
function InterfaceCaller({ row, session }: { row: MissionInterfaceView; session: Session }) {
  const storageKey = `picasso.interface-request.v1.${session.mode}.${session.user}.${key(row)}`
  const [restored] = useState(() => {
    try {
      const raw = sessionStorage.getItem(storageKey)
      if(raw === null) return { pending: null, error: null }
      const pending = JSON.parse(raw) as PendingInvocation
      if(!pending || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(pending.requestId) ||
        !pending.input || typeof pending.input !== 'object' || Array.isArray(pending.input) || typeof pending.canStartNew !== 'boolean' || (pending.expectedDeploymentVersion !== undefined && (!Number.isInteger(pending.expectedDeploymentVersion) || pending.expectedDeploymentVersion < 1))) throw new Error('보관된 요청 형식 오류')
      return { pending, error: null }
    } catch { return { pending: null, error: '보관된 요청을 읽을 수 없습니다. 재제출 전에 통합 작업 요청에서 기존 실행을 확인하세요.' } }
  })
  const [pending, setPending] = useState<PendingInvocation | null>(restored.pending)
  const [input, setInput] = useState(JSON.stringify(restored.pending?.input ?? row.sample, null, 2))
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<InterfaceInvocation | null>(null)
  const [error, setError] = useState<string | null>(restored.error)
  const [assessment, setAssessment] = useState<{ signature: string; value: InterfaceEligibility } | null>(null)
  const [assessmentError, setAssessmentError] = useState<string | null>(null)
  const [assessmentDirty, setAssessmentDirty] = useState(false)
  const deploymentSignature = JSON.stringify([row.status, row.deployment?.deploymentVersion])
  const previousDeployment = useRef(deploymentSignature)
  useEffect(() => {
    if (previousDeployment.current !== deploymentSignature) {
      previousDeployment.current = deploymentSignature; setAssessment(null); setAssessmentDirty(true); setConfirmed(false)
    }
  }, [deploymentSignature])
  const signature = JSON.stringify([input, row.status, row.deployment?.deploymentVersion, session.mode, session.user])
  const currentAssessment = assessment?.signature === signature ? assessment.value : null
  const ready = currentAssessment?.eligibility.registry === 'OK' && currentAssessment.eligibility.host === 'OK' && currentAssessment.eligibility.robots?.some(robot => robot.eligible === true && robot.gateTrial !== true) === true
  const canRequest = !!currentAssessment && (ready || !!row.requiredStationId)
  async function assess() {
    if (sending.current) return
    setAssessment(null); setAssessmentError(null); setAssessmentDirty(false); setConfirmed(false)
    sending.current = true; setBusy(true)
    try {
      const value: unknown = JSON.parse(input)
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('호출 입력은 JSON 객체여야 합니다.')
      const answer = await checkInterfaceEligibility(session, row, value)
      if (answer.interfaceId !== row.interfaceId || answer.interfaceVersion !== row.version || !answer.eligibility || answer.jobOrder?.workMasterId !== row.workMasterId ||
        (answer.eligibility.robots !== null && !Array.isArray(answer.eligibility.robots)) ||
        !Number.isInteger(answer.jobOrder?.deployment?.deploymentVersion) || answer.jobOrder.deployment.deploymentVersion < 1) throw new Error('배정 판정 응답 형식을 확인할 수 없습니다.')
      if (alive.current) setAssessment({ signature, value: answer })
    } catch (cause) { if (alive.current) setAssessmentError(errorText(cause)) }
    finally { sending.current = false; if (alive.current) setBusy(false) }
  }
  const sending = useRef(false)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  async function invoke(replay = false) {
    if(sending.current || session.mode !== 'operator' || restored.error) return
    if (!replay && (pending || !canRequest || !currentAssessment)) { setError('현재 입력의 배정 가능 여부를 확인한 뒤 새 작업을 요청하세요.'); return }
    let body: unknown
    try { body = replay && pending ? pending.input : JSON.parse(input) }
    catch { setError('호출 입력은 올바른 JSON이어야 합니다.'); return }
    if(!body || typeof body !== 'object' || Array.isArray(body)) { setError('호출 입력은 JSON 객체여야 합니다.'); return }
    if(pending && JSON.stringify(body) !== JSON.stringify(pending.input)) { setError('기존 요청과 입력이 다릅니다. 기존 작업 요청을 확인한 뒤 새 요청을 작성하세요.'); return }
    const request = pending ?? { requestId: crypto.randomUUID(), input: body, canStartNew: false, expectedDeploymentVersion: currentAssessment!.jobOrder.deployment.deploymentVersion }
    try {
      const saved = sessionStorage.getItem(storageKey)
      if(saved && JSON.parse(saved).requestId !== request.requestId) throw new Error('다른 요청이 보관되어 있습니다. 화면을 다시 열어 기존 요청부터 확인하세요.')
      sessionStorage.setItem(storageKey, JSON.stringify(request))
    } catch(e: unknown) { setError(`요청 보관 실패: ${errorText(e)}. 작업을 전송하지 않았습니다.`); return }
    sending.current = true; setBusy(true); setError(null); setPending(request)
    try {
      const value = await invokeMissionInterface(session, row, request.input, request.requestId, request.expectedDeploymentVersion)
      if(!value?.receipt || typeof value.receipt.receiptId !== 'string' || typeof value.receipt.jobOrderId !== 'string' ||
        value.receipt.externalOrderId !== request.requestId || value.receipt.sourceSystemId !== 'ops:interface' || typeof value.duplicate !== 'boolean') throw new Error('작업 요청 응답 형식을 확인할 수 없습니다')
      const updated = { ...request, receiptId: value.receipt.receiptId, canStartNew: ['WAITING_RESOURCE','ACCEPTED','REJECTED','UNASSIGNED'].includes(value.receipt.state) }
      sessionStorage.setItem(storageKey, JSON.stringify(updated))
      if(alive.current) { setResult(value); setPending(updated); setConfirmed(false) }
    } catch(e: unknown) { if(alive.current) setError(`${errorText(e)} · 기존 요청 ID로 작업 요청을 확인하세요. 새 ID로 반복 제출하지 마세요.`) }
    finally { sending.current = false; if(alive.current) setBusy(false) }
  }
  function startNew() {
    if(!pending?.canStartNew || busy) return
    try { sessionStorage.removeItem(storageKey); setPending(null); setResult(null); setConfirmed(false); setError(null); setAssessment(null); setAssessmentError(null); setAssessmentDirty(false) }
    catch { setError('요청 보관 해제에 실패했습니다. 새 작업을 전송하지 않았습니다.') }
  }
  return <details className="interface-caller"><summary>공개된 계약으로 작업 요청</summary><p><code>POST /api/mission-interfaces/{row.interfaceId}/{row.version}/invoke</code></p><p>운영자 호출도 작업 요청으로 영속 기록합니다. 같은 요청 ID는 기존 작업 요청을 반환하며 추가 실행하지 않습니다. 브라우저 보관은 같은 탭의 새로고침까지 유지됩니다.</p>
    <MissionResourceNotice session={session} workMasterId={row.workMasterId} /><label className="editor">계약 호출 입력<textarea rows={8} value={input} disabled={busy} onChange={e => { setInput(e.target.value); setConfirmed(false); setAssessment(null); setAssessmentDirty(true); setAssessmentError(null) }} /></label>
    <section aria-label="계약 입력의 배정 가능 미리보기"><h4>실행 전 배정 확인</h4><p>실제 입력과 서버에 고정된 배포로 판정합니다. 기체를 예약하지 않으며 제출 시 현재 조건을 다시 평가합니다. 스킬 적합은 스킬 이름 기준이며 전체 실행·안전·품질 검증을 대신하지 않습니다.</p>
      <button type="button" disabled={busy || row.status !== 'ENABLED' || !!pending} onClick={assess}>이 입력으로 배정 가능 확인</button>
      {assessmentError && <p role="alert">배정 판정 실패: {assessmentError} · 새 작업 전 다시 확인하세요.</p>}
      {(assessmentDirty || (assessment && !currentAssessment)) && <p role="status">입력 또는 배포 상태가 변경되었습니다. 배정 가능 여부를 다시 확인하세요.</p>}
      {currentAssessment && <><p>판정한 임무 {currentAssessment.jobOrder.workMasterId} · 임무 버전 {currentAssessment.jobOrder.missionVersion} · 배포 v{currentAssessment.jobOrder.deployment.deploymentVersion} · 요구 근거 {currentAssessment.jobOrder.requiredEvidence}</p>
        <EligibilityTable read={{ view: currentAssessment.eligibility, refusal: null, error: null, formKey: signature }} formKey={signature} />
        <p role="status">{ready ? '현재 배정 가능한 기체가 있습니다. 이 입력으로 작업을 요청할 수 있습니다.' : row.requiredStationId ? '검증된 입력을 작업 요청으로 자원 대기열에 넣을 수 있습니다. 기체 배정은 자원과 설비 준비 후 다시 확인합니다.' : '배정 가능한 기체를 확인하지 못했습니다. 불가·모름 사유를 확인한 뒤 다시 판정하세요.'}</p>
        <details><summary>판정한 정규화 작업</summary><pre aria-label="계약 배정 정규화 작업">{JSON.stringify(currentAssessment.jobOrder, null, 2)}</pre></details></>}
    </section>
    <label><input type="checkbox" checked={confirmed} disabled={busy} onChange={e => setConfirmed(e.target.checked)} />이 입력으로 실제 작업을 지시합니다</label>
    <button type="button" disabled={busy || !!pending || !canRequest || !!restored.error || !confirmed || row.status !== 'ENABLED' || session.mode !== 'operator'} onClick={() => invoke()}>호출 계약으로 작업 요청</button>
    {pending && <section className="manual-receipt" aria-label="계약 작업 요청 추적"><h4>계약 작업 요청</h4><p>요청 ID: <code>{pending.requestId}</code></p><p>처리 중·응답 불명인 요청은 새 ID로 재제출하지 마세요.</p><div className="actions"><button type="button" disabled={busy || session.mode !== 'operator'} onClick={() => invoke(true)}>동일 계약 작업 요청 확인</button><button type="button" disabled={busy || !pending.canStartNew} onClick={startNew}>새 계약 요청 작성</button>{pending.receiptId && <a href={`#requests?receipt=${encodeURIComponent(pending.receiptId)}`}>작업 요청·실행 결과 보기</a>}</div><details><summary>보관된 계약 요청 입력</summary><pre>{JSON.stringify(pending.input,null,2)}</pre></details></section>}
    {session.mode !== 'operator' && <p>실제 호출은 운영자 모드에서 가능합니다.</p>}{row.status !== 'ENABLED' && <p>이 계약은 현재 공개되지 않았습니다. 기존 작업 요청 확인은 가능합니다.</p>}
    {error && <p role="alert">{error}</p>}{result && <div role="status" aria-label="계약 호출 결과"><p>작업 지시 {result.receipt.jobOrderId}: {result.receipt.state} · {result.duplicate ? '기존 작업 요청 확인 · 추가 실행 없음' : '작업 요청 저장됨'}</p>{result.receipt.detail && <p>{result.receipt.detail}</p>}<pre aria-label="계약 호출 응답 JSON">{JSON.stringify(result, null, 2)}</pre></div>}
  </details>
}
