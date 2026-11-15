import { useState } from 'react'
import { createMissionProfile } from '../api'
import type { MissionProfile,Session } from '../api'
import { missionTemplate } from '../missionTemplates'
import type { MissionTemplateKind } from '../missionTemplates'
export function MissionCreator({session,onCreated,onClose}:{session:Session;onCreated:(row:MissionProfile)=>void;onClose:()=>void}) {
 const storageKey=`picasso.mission-create.v1.${session.user}`
 const [restored]=useState(()=>{try{const text=sessionStorage.getItem(storageKey);return {text,value:text?JSON.parse(text) as MissionProfile:null,error:null}}catch{return {text:null,value:null,error:'보관된 임무 등록 입력을 읽지 못했습니다. 임무 목록을 확인하세요.'}}})
 const [id,setId]=useState(restored.value?.workMasterId??'ManufacturingInspection');const [name,setName]=useState(restored.value?.name??'공정 자산 점검');const [description,setDescription]=useState(restored.value?.description??'제조 현장의 대상 자산을 점검합니다.')
 const [kind,setKind]=useState<MissionTemplateKind>('inspect');const [edited,setEdited]=useState<string|null>(restored.text)
 const [busy,setBusy]=useState(false);const [error,setError]=useState<string|null>(restored.error)
 const profile=missionTemplate(kind,id,name,description)
 const text=edited??JSON.stringify(profile,null,2)
 async function create() {
  if(restored.error)return
  setError(null);setBusy(true)
  try{const body=JSON.parse(text) as MissionProfile;sessionStorage.setItem(storageKey,text);const result=await createMissionProfile(session,body);sessionStorage.removeItem(storageKey);onCreated(result)}
  catch(e:unknown){setError(`${e instanceof Error?e.message:String(e)} · 응답이 불명인 경우 같은 임무 ID·정의로 확인하세요. 새로운 ID로 반복 생성하지 마세요.`)}
  finally{setBusy(false)}
 }
 return <section className="interface-editor" aria-label="새 임무 생성"><div className="mission-section-heading"><div><h2>템플릿으로 새 임무 생성</h2><p>입력 계약과 변환 규칙을 등록한 뒤 실행 정의를 검증·모의 실행하고 버전을 확정합니다. 등록만으로 로봇이 실행되거나 API가 공개되지는 않습니다.</p></div><button disabled={busy} onClick={onClose}>생성 화면 닫기</button></div>
 <fieldset disabled={busy || session.mode!=='engineer'}><div className="interface-fields"><label>새 임무 ID<input value={id} disabled={edited!==null} onChange={e=>setId(e.target.value)} /></label><label>새 임무 이름<input value={name} disabled={edited!==null} onChange={e=>setName(e.target.value)} /></label><label>임무 설명<input value={description} disabled={edited!==null} onChange={e=>setDescription(e.target.value)} /></label><label>시작 템플릿<select value={kind} disabled={edited!==null} onChange={e=>setKind(e.target.value as MissionTemplateKind)}><option value="inspect">대상 점검 · E0</option><option value="move">지정 위치 이동 · E0</option><option value="visit">대상마다 이동 뒤 점검 · E0</option></select></label></div>
 <details><summary>입력 스키마·단계 연결·샘플 편집</summary><p>입력 스키마와 orderMapping은 등록 후 불변입니다. 변경하려면 새 임무 ID를 사용합니다. 실행 단계는 등록 후 초안·버전으로 개정할 수 있습니다. 현재 엔진은 순차 스킬, 대상마다 도는 반복 그룹, 설비 대기를 지원합니다.</p><label className="editor">임무 등록 JSON<textarea rows={22} value={text} spellCheck={false} onChange={e=>setEdited(e.target.value)} /></label>{edited!==null && <button onClick={()=>setEdited(null)}>템플릿 입력으로 되돌리기</button>}</details>
 <button className="primary" onClick={create}>{busy?'등록 중…':'임무 등록 후 실행 정의 열기'}</button></fieldset>{error && <p role="alert">{error}</p>}</section>
}
