import { CollectorStatus } from './CollectorStatus'
import { useEffect, useState } from 'react'
import { fetchExecutionSummary, fetchIncidents } from '../api'
import type { ExecutionSummary, IncidentsView, Session } from '../api'
import type { WorkReceipt } from './WorkRequests'
import { POLL_MS } from '../poll'

interface Summary { total: number; states: Record<string,number>; unacknowledgedResults: number; latest: WorkReceipt[]; observedAt: string }
interface Observation<T> { value: T | null; error: string | null; at: string | null }
const empty = <T,>(): Observation<T> => ({ value: null, error: null, at: null })
const message = (error: unknown) => error instanceof Error ? error.message : String(error)
export function OverviewArea({ session }: { session: Session }) {
  const [summary,setSummary] = useState(empty<Summary>)
  const [host,setHost] = useState(empty<ExecutionSummary>)
  const [incidents,setIncidents] = useState(empty<IncidentsView>)
  const [tick,setTick] = useState(0)
  useEffect(() => { const timer=setInterval(()=>setTick(n=>n+1),POLL_MS); return ()=>clearInterval(timer) },[])
  useEffect(() => {
    let alive=true
    async function load<T>(read:()=>Promise<T>,write:React.Dispatch<React.SetStateAction<Observation<T>>>) {
      try { const value=await read(); if(alive)write({value,error:null,at:new Date().toISOString()}) }
      catch(e:unknown) { if(alive)write(old=>({...old,error:message(e)})) }
    }
    void load(async()=>{
      const response=await fetch('/api/work-requests/summary',{headers:{'X-Ops-Mode':session.mode,'X-Ops-User':session.user}})
      if(!response.ok)throw new Error(`작업 요청 집계 HTTP ${response.status}`)
      const value=await response.json() as Summary
      if(typeof value.total!=='number' || !value.states || !Array.isArray(value.latest) || typeof value.unacknowledgedResults!=='number')throw new Error('작업 요청 집계 응답 형식 오류')
      return value
    },setSummary)
    void load(()=>fetchExecutionSummary(session),setHost)
    void load(()=>fetchIncidents(session),setIncidents)
    return ()=>{alive=false}
  },[session,tick])
  const states=summary.value?.states
  const metrics = [
    {label:'총 작업 요청',value:summary.value?.total,href:'#requests'},
    {label:'미배정',value:states ? states.UNASSIGNED??0 : undefined,href:'#requests?state=UNASSIGNED'},
    {label:'제출 확인 필요',value:states ? (states.RECEIVED??0)+(states.DISPATCHING??0)+(states.IN_DOUBT??0) : undefined,href:'#requests?state=NEEDS_CONFIRMATION'},
    {label:'작업 요청 거부',value:states ? states.REJECTED??0 : undefined,href:'#requests?state=REJECTED'},
    {label:'외부 결과 수신 미확인',value:summary.value?.unacknowledgedResults,href:'#requests?result=unacknowledged'},
  ]
  return <section className="overview" aria-label="운영 개요">
    <div className="mission-section-heading"><div><h2>외부 주문에서 로봇 실행까지</h2><p>작업 요청과 현재 실행 관측을 구분합니다. 작업 요청 수락은 물리 완료나 품질 승인을 뜻하지 않습니다.</p></div><button onClick={()=>setTick(n=>n+1)}>개요 다시 조회</button></div>
    {summary.error && <p role="alert">작업 요청 집계: {summary.error} · {summary.value?'직전 조회 값입니다.':'모름'}</p>}
    <div className={`overview-metrics ${summary.error?'stale':''}`}>{metrics.map(metric=><a className="overview-card" aria-label={`${metric.label} ${metric.value??'모름'}${summary.error?' · 직전 값':''}`} href={metric.href} key={metric.label}><span>{metric.label}</span><strong>{metric.value??'모름'}</strong><small>{summary.error?'직전 값 · 재확인 필요':'작업 요청 기록 기준'}</small></a>)}</div>
    <p>작업 요청 집계 시각: {summary.value?.observedAt??'아직 조회하지 못함'}</p>
    <div className="overview-live"><section><h3>호스트 실행 관측</h3>{host.error && <p role="alert">호스트: {host.error} · {host.value?'직전 관측입니다.':'모름'}</p>}{host.value ? <><dl><dt>실행 중</dt><dd>{host.value.states.RUNNING??0}</dd><dt>판단·확인 대기</dt><dd>{(host.value.states.OPERATOR_HOLD??0)+(host.value.states.IN_DOUBT??0)}</dd><dt>일지 저장 차단</dt><dd>{host.value.journalBlockedExecutionIds?.length??0}</dd><dt>인스턴스</dt><dd>{host.value.instanceId}</dd><dt>플릿 연결</dt><dd>{host.value.fleet === undefined ? '모름' : host.value.fleet.connected ? (host.value.fleet.url ?? '연결(주소 없음)') : '없음'}</dd><dt>조회 시각</dt><dd>{host.at}</dd></dl><p>호스트가 보유한 실행 기준입니다. 다른 인스턴스의 과거 작업 요청을 포함한 누적 집계가 아닙니다.</p></> : <p>실행 상태 모름 · 관측을 기다립니다.</p>}<a href="#operations">실행·예외 대응 열기</a></section>
    <section><h3>운영자 판단</h3>{incidents.error && <p role="alert">인시던트: {incidents.error} · {incidents.value?'직전 관측입니다.':'모름'}</p>}{incidents.value ? <><p>미해결 인시던트 <strong>{incidents.value.incidents.filter(row=>row.unresolved).length}</strong>건</p><p>인스턴스 {incidents.value.instanceId} · 조회 {incidents.at}</p></> : <p>판단 대기 상태 모름</p>}<a href="#operations">인시던트 확인·판단</a></section></div>
    <CollectorStatus session={session} />
    <section><div className="mission-section-heading"><h3>최근 작업 요청</h3><a href="#requests">전체 작업 요청</a></div>{summary.value ? <div className="table-scroll" role="group" aria-label="최근 작업 요청 표 스크롤" tabIndex={0}><table aria-label="최근 작업 요청"><thead><tr><th>요청</th><th>출처</th><th>작업 요청 상태</th><th>시각</th></tr></thead><tbody>{summary.value.latest.map(row=><tr key={row.receiptId}><td><a href={`#requests?receipt=${encodeURIComponent(row.receiptId)}`}>{row.externalOrderId}</a><small>{row.jobOrderId}</small></td><td>{row.sourceSystemId}</td><td>{row.state}</td><td>{row.receivedAt}</td></tr>)}</tbody></table>{summary.value.latest.length===0 && <p>저장된 작업 요청이 없습니다.</p>}</div> : <p>최근 작업 요청 모름</p>}</section>
  </section>
}
