import { useEffect, useRef, useState } from 'react'
import { InterfaceApiError, createStation, decideStationRun, enqueueStationRun, fetchMissionInterfaces, fetchStationRun, fetchStations } from '../api'
import type { MissionInterfaceView, RfStation, Session, StationEvent, StationRun } from '../api'
import { STATION_TEXT } from '../labels'
import { POLL_MS } from '../poll'
import { MissionResourceSettings } from './MissionResourceSettings'

const labels:Record<string,string>={QUEUED:'자원 대기',WAITING_READY:'설비 준비 대기',DISPATCHING:'제출 중',RUNNING:'실행·설비 완료 대기',WAITING_ROBOT:'가용 기체 대기',RETRYING:'미배정 재평가 중',REWORKING:'재작업 전달 중',CLEARING:'현장 확인 해제 중',REVIEW_REQUIRED:'현장 확인 필요',COMPLETED:'완료',TIMED_OUT:'준비 시간 초과',REJECTED:'제출 거부',MANUALLY_CLEARED:'현장 확인 후 해제',CANCELLED:'대기 취소'}
const message=(e:unknown)=>e instanceof Error?e.message:String(e)
interface Pending { requestId:string;stationId:string;interfaceId:string;interfaceVersion:number;expectedDeploymentVersion:number;input:unknown }
export function StationWorkspace({session}:{session:Session}) {
 const [stations,setStations]=useState<RfStation[]>([])
 const [runs,setRuns]=useState<StationRun[]>([])
 const [contracts,setContracts]=useState<MissionInterfaceView[]>([])
 const [selected,setSelected]=useState('')
 const [station,setStation]=useState('')
 const [input,setInput]=useState('{}')
 const [tick,setTick]=useState(0)
 const [error,setError]=useState<string|null>(null)
 const [readError,setReadError]=useState<string|null>(null)
 const [busy,setBusy]=useState(false)
 const [confirmed,setConfirmed]=useState(false)
 const [detail,setDetail]=useState<{run:StationRun;events:StationEvent[]}|null>(null)
 const [reason,setReason]=useState('')
 const [clearConfirmed,setClearConfirmed]=useState(false)
 const [idleConfirmed,setIdleConfirmed]=useState(false)
 const [completionConfirmed,setCompletionConfirmed]=useState(false)
 const [registration,setRegistration]=useState({stationId:'',name:'',readySignal:'inspection_ready',completeSignal:'inspection_complete',timeoutSeconds:120,jobSignal:'',jobEchoSignal:''})
 const storageKey=`picasso.station/${session.mode}/${session.user}`
 const [restored]=useState<{pending:Pending|null;error:string|null}>(()=>{try{const raw=sessionStorage.getItem(storageKey);if(!raw)return{pending:null,error:null};const value=JSON.parse(raw) as Pending;if(!value || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value.requestId) || typeof value.stationId!=='string' || typeof value.interfaceId!=='string' || !Number.isInteger(value.interfaceVersion) || !Number.isInteger(value.expectedDeploymentVersion) || !value.input || typeof value.input!=='object' || Array.isArray(value.input))throw new Error('보관 형식 오류');return{pending:value,error:null}}catch{return{pending:null,error:'보관 요청을 읽을 수 없습니다. 새 작업 요청을 보내기 전에 기존 스테이션 작업을 확인하세요.'}}})
 const [pending,setPending]=useState<Pending|null>(restored.pending)
 const sending=useRef(false)
 useEffect(()=>{let alive=true;Promise.all([fetchStations(session),fetchMissionInterfaces(session)]).then(([value,interfaces])=>{if(!Array.isArray(value.stations)||!Array.isArray(value.runs))throw new Error('자원 조회 형식 오류');if(alive){setStations(value.stations);setRuns(value.runs);setContracts(interfaces.interfaces);setReadError(null)}}).catch(e=>{if(alive)setReadError(message(e))});return()=>{alive=false}},[session,tick])
 useEffect(()=>{const timer=setInterval(()=>setTick(n=>n+1),POLL_MS);return()=>clearInterval(timer)},[])
 const contract=contracts.find(row=>`${row.interfaceId}/${row.version}`===selected)
 const detailStation=detail?stations.find(row=>row.stationId===detail.run.stationId):undefined
 const quarantinedWait=detail?.run.state==='WAITING_READY'&&!!detailStation?.quarantinedBy
 // 작업 번호 신호는 비우면 보내지 않는다. 한쪽만 채운 등록은 서버가 거절한다.
 const registrationBody=()=>{const {jobSignal,jobEchoSignal,...rest}=registration;return{...rest,...(jobSignal.trim()?{jobSignal:jobSignal.trim()}:{}),...(jobEchoSignal.trim()?{jobEchoSignal:jobEchoSignal.trim()}:{})}}
 async function perform(action:()=>Promise<unknown>){if(sending.current)return;sending.current=true;setBusy(true);setError(null);try{await action();setTick(n=>n+1)}catch(e){setError(message(e))}finally{sending.current=false;setBusy(false)}}
 async function send(replay:boolean){await perform(async()=>{
  let request=pending
  if(!replay){if(!contract?.deployment||!confirmed)throw new Error('호출 계약·배포와 실제 작업 지시를 확인하세요.');const value:unknown=JSON.parse(input);if(!value||typeof value!=='object'||Array.isArray(value))throw new Error('입력은 JSON 객체입니다.');request={requestId:crypto.randomUUID(),stationId:station,interfaceId:contract.interfaceId,interfaceVersion:contract.version,expectedDeploymentVersion:contract.deployment.deploymentVersion,input:value};sessionStorage.setItem(storageKey,JSON.stringify(request));setPending(request)}
  if(!request)throw new Error('재확인할 요청이 없습니다.')
  let result:StationRun
  try { result=await enqueueStationRun(session,request) } catch(cause) { if(cause instanceof InterfaceApiError && [400,404].includes(cause.status)){sessionStorage.removeItem(storageKey);setPending(null)} throw cause }
  if(result.runId!==request.requestId)throw new Error('작업 요청 ID가 일치하지 않습니다. 동일 요청으로 재확인하세요.')
  sessionStorage.removeItem(storageKey);setPending(null);setConfirmed(false);setDetail(await fetchStationRun(session,result.runId))
 })}
 return <section aria-label="공유 스테이션 운영" className="station-workspace">
  <div className="mission-section-heading"><div><h2>공유 자원과 작업 흐름</h2><p>스테이션을 확보하고 설비 준비를 확인한 뒤 임무를 호출합니다. 로봇 완료와 이번 작업의 설비 완료 근거를 확인해야 자원을 해제합니다.</p></div></div>
  <ol className="validation-journey"><li><strong>자원 확보</strong><p>스테이션별 작업 요청 순서로 한 작업만 점유합니다.</p></li><li><strong>설비 준비</strong><p>준비 true·완료 false를 확인합니다.</p></li><li><strong>임무 수행</strong><p>고정 배포를 호출하고 작업 요청을 추적합니다.</p></li><li><strong>완료 확인</strong><p>로봇 물리 완료와 새 설비 완료 신호가 필요합니다.</p></li></ol>
  <p>임무에 필수 자원을 등록하면 외부 주문·운영 화면·직접 제출도 같은 대기열을 거칩니다. 외부 장비의 독립 제어를 잠그는 현장 인터록은 아닙니다. 설비 신호는 일반 공정 신호이며 안전 제어를 대체하지 않습니다.</p>
  {readError&&<p role="alert">자원 조회 실패: {readError} · 표는 직전 값입니다. 조작 전 다시 조회하세요.</p>}
  {error&&<p role="alert">{error}</p>}{restored.error&&<p role="alert">{restored.error}</p>}
  <button type="button" onClick={()=>setTick(n=>n+1)}>자원 상태 새로고침</button>
  <div className="table-scroll" tabIndex={0} role="group" aria-label="자원 표 가로 스크롤"><table aria-label="스테이션 점유"><thead><tr><th>스테이션</th><th>준비 / 완료 신호</th><th>단계 기한</th><th>점유 작업</th></tr></thead><tbody>{stations.map(row=><tr key={row.stationId}><td>{row.name}<br/><code>{row.stationId}</code>{row.quarantinedBy&&<><br/><strong>{STATION_TEXT.quarantined}</strong></>}</td><td>{row.readySignal} / {row.completeSignal}{row.jobSignal&&<><br/><strong>{STATION_TEXT.echoing}</strong> {row.jobSignal} / {row.jobEchoSignal}</>}</td><td>{row.timeoutSeconds}초</td><td>{row.owner?<button type="button" onClick={()=>perform(async()=>{setDetail(await fetchStationRun(session,row.owner!))})}>점유 작업 보기</button>:'비어 있음'}</td></tr>)}</tbody></table></div>
  {stations.length===0&&!readError&&<p>등록된 공유 스테이션이 없습니다. 엔지니어 모드에서 현장 신호를 연결하세요.</p>}
  {session.mode==='engineer'&&<details><summary>공유 스테이션 등록</summary><form className="station-form" onSubmit={e=>{e.preventDefault();void perform(async()=>{await createStation(session,registrationBody())})}}>
   {(['stationId','name','readySignal','completeSignal'] as const).map(key=><label key={key}>{{stationId:'스테이션 ID',name:'스테이션 이름',readySignal:'준비 신호 이름',completeSignal:'완료 신호 이름'}[key]}<input required maxLength={key==='stationId'?64:120} value={registration[key]} onChange={e=>setRegistration({...registration,[key]:e.target.value})}/></label>)}
   {(['jobSignal','jobEchoSignal'] as const).map(key=><label key={key}>{STATION_TEXT[key]}<input maxLength={120} aria-describedby="station-job-help" value={registration[key]} onChange={e=>setRegistration({...registration,[key]:e.target.value})}/></label>)}
   <p id="station-job-help">{STATION_TEXT.jobHelp}</p>
   <label>단계 기한 초<input required type="number" min={5} max={3600} value={registration.timeoutSeconds} onChange={e=>setRegistration({...registration,timeoutSeconds:Number(e.target.value)})}/></label><p>준비·완료 신호는 안전 신호가 아닌 BOOLEAN 신호, 선택 항목인 작업 번호 송신·반환 신호는 TEXT 신호만 연결할 수 있습니다. 등록 후 설정은 변경할 수 없습니다.</p><button disabled={busy||!!readError}>스테이션 등록</button></form></details>}
  {session.mode==='operator'&&<details open><summary>공유 스테이션 작업 요청</summary><form className="station-form" onSubmit={e=>{e.preventDefault();void send(false)}}>
   <label>사용할 스테이션<select required disabled={busy||!!pending} value={station} onChange={e=>{setStation(e.target.value);setConfirmed(false)}}><option value="">스테이션 선택</option>{stations.map(row=><option key={row.stationId} value={row.stationId}>{row.name}</option>)}</select></label>
   <label>대상 호출 계약<select required disabled={busy||!!pending} value={selected} onChange={e=>{setSelected(e.target.value);const row=contracts.find(r=>`${r.interfaceId}/${r.version}`===e.target.value);setInput(JSON.stringify(row?.sample??{},null,2));setConfirmed(false)}}><option value="">계약 선택</option>{contracts.filter(row=>row.status==='ENABLED'&&row.deployment).map(row=><option key={`${row.interfaceId}/${row.version}`} value={`${row.interfaceId}/${row.version}`}>{row.name} · v{row.version}</option>)}</select></label>
   {contract?.deployment&&<p>고정 배포 v{contract.deployment.deploymentVersion} · 임무 {contract.deployment.missionVersion} · 필수 자원 {contract.requiredStationId??'미지정'}</p>}
   <label className="editor">스테이션 계약 입력<textarea rows={7} disabled={busy||!!pending} value={input} onChange={e=>{setInput(e.target.value);setConfirmed(false)}}/></label>
   <label><input type="checkbox" disabled={busy||!!pending} checked={confirmed} onChange={e=>setConfirmed(e.target.checked)}/>준비 조건이 충족되면 실제 로봇 작업을 지시합니다</label><button disabled={busy||!!pending||!confirmed||!!readError||!!restored.error||!station||!contract?.deployment||!!(contract.requiredStationId&&contract.requiredStationId!==station)}>스테이션 작업 요청</button>
   {pending&&<div role="status"><p>요청 {pending.requestId}가 보관되어 있습니다. 응답이 불명확하면 새 ID로 보내지 마세요.</p><button type="button" disabled={busy} onClick={()=>send(true)}>동일 스테이션 요청 확인</button></div>}
  </form></details>}
  <MissionResourceSettings session={session} stations={stations} contracts={contracts} onSaved={()=>setTick(n=>n+1)} />
  <h3>최근 작업과 대기 사유</h3><p>최근 100건입니다. 시작 후 불명확해진 작업은 자동 재할당하지 않습니다. 전송 전 미배정만 5초 간격으로 최대 3회 재평가합니다.</p>
  <div className="table-scroll" tabIndex={0} role="group" aria-label="스테이션 작업 표 가로 스크롤"><table aria-label="스테이션 작업"><thead><tr><th>작업</th><th>스테이션</th><th>상태</th><th>이유</th><th>작업 요청</th></tr></thead><tbody>{runs.map(run=><tr key={run.runId}><td><button onClick={()=>perform(async()=>{setDetail(await fetchStationRun(session,run.runId));setReason('');setClearConfirmed(false);setIdleConfirmed(false);setCompletionConfirmed(false)})}>{run.runId.slice(0,8)}</button></td><td>{run.stationId}</td><td>{labels[run.state]??run.state}</td><td>{run.reason}</td><td>{run.receiptId?<a href={`#requests?receipt=${encodeURIComponent(run.receiptId)}`}>작업 요청·실행 결과</a>:'아직 연결되지 않음'}</td></tr>)}</tbody></table></div>
  {detail&&<article aria-label="스테이션 작업 근거"><h3>작업 {detail.run.runId.slice(0,8)}</h3><p>아래는 선택 작업을 조회한 시점의 상태와 근거입니다. 판단 전 선택 작업을 다시 조회하세요.</p><p role="status">{labels[detail.run.state]??detail.run.state} · {detail.run.reason}</p><p>조회 버전 {detail.run.revision} · 재평가 {detail.run.attempt}/3 · 호출 {detail.run.payload.interfaceId??detail.run.payload.workMasterId??'직접 임무'} {detail.run.payload.interfaceVersion?`v${detail.run.payload.interfaceVersion}`:''} · 배포 {detail.run.payload.expectedDeploymentVersion?`v${detail.run.payload.expectedDeploymentVersion}`:'코드 정의'}</p><button onClick={()=>perform(async()=>{setDetail(await fetchStationRun(session,detail.run.runId));setClearConfirmed(false);setIdleConfirmed(false);setCompletionConfirmed(false)})}>선택 작업 다시 조회</button><a href={`/api/stations/runs/${encodeURIComponent(detail.run.runId)}/evidence`} target="_blank" rel="noreferrer">판정 근거 JSON</a>
   <ol className="station-timeline">{detail.events.map((event,index)=><li key={index}><strong>{labels[event.state]??event.state}</strong><p>{event.reason}</p><small>{event.actor} · {event.recordedAt}</small><details><summary>기록된 근거</summary><pre>{JSON.stringify(event.context,null,2)}</pre></details></li>)}</ol>
   {session.mode==='operator'&&quarantinedWait&&<form onSubmit={e=>e.preventDefault()}><p role="note">{STATION_TEXT.idleWarning}</p><label>운영자 판단 근거<input required maxLength={1000} value={reason} onChange={e=>setReason(e.target.value)}/></label><label><input type="checkbox" checked={idleConfirmed} onChange={e=>setIdleConfirmed(e.target.checked)}/>{STATION_TEXT.idleConfirm}</label><button disabled={busy||!reason.trim()||!idleConfirmed||!!readError} onClick={()=>perform(async()=>{await decideStationRun(session,detail.run,'CONFIRM_STATION_IDLE',reason);setDetail(await fetchStationRun(session,detail.run.runId));setReason('');setIdleConfirmed(false)})}>{STATION_TEXT.idleButton}</button></form>}
   {session.mode==='operator'&&['QUEUED','REVIEW_REQUIRED'].includes(detail.run.state)&&<form onSubmit={e=>e.preventDefault()}><label>운영자 판단 근거<input required maxLength={1000} value={reason} onChange={e=>setReason(e.target.value)}/></label>
    {detail.run.state==='QUEUED'?<button disabled={busy||!reason.trim()||!!readError} onClick={()=>perform(async()=>{await decideStationRun(session,detail.run,'CANCEL_QUEUED',reason);setDetail(await fetchStationRun(session,detail.run.runId));setReason('')})}>대기 작업 취소</button>:<><p>해제는 물리 완료 판정이 아닙니다. 로봇 작업·잔류 물체·설비 상태를 현장에서 확인한 뒤 판단하세요.</p><label><input type="checkbox" checked={clearConfirmed} onChange={e=>setClearConfirmed(e.target.checked)}/>현장 작업과 자원 해제 가능 여부를 확인했습니다</label><button disabled={busy||!reason.trim()||!clearConfirmed||!!readError} onClick={()=>perform(async()=>{await decideStationRun(session,detail.run,'CONFIRM_CLEAR',reason);setDetail(await fetchStationRun(session,detail.run.runId));setReason('');setClearConfirmed(false)})}>현장 확인 후 자원 해제</button>{!detailStation?.jobSignal&&<p>{STATION_TEXT.clearNote}</p>}<button disabled={busy||!reason.trim()||!detail.run.receiptId||!!readError} onClick={()=>perform(async()=>{await decideStationRun(session,detail.run,'RESUME_OBSERVATION',reason);setDetail(await fetchStationRun(session,detail.run.runId));setReason('')})}>재실행 없이 관측 재개</button><p>{STATION_TEXT.completionNote}</p><label><input type="checkbox" checked={completionConfirmed} onChange={e=>setCompletionConfirmed(e.target.checked)}/>{STATION_TEXT.completionConfirm}</label><button disabled={busy||!reason.trim()||!completionConfirmed||!detail.run.receiptId||!!readError} onClick={()=>perform(async()=>{await decideStationRun(session,detail.run,'CONFIRM_COMPLETION',reason);setDetail(await fetchStationRun(session,detail.run.runId));setReason('');setCompletionConfirmed(false)})}>{STATION_TEXT.completionButton}</button></>}
   </form>}
  </article>}
 </section>
}
