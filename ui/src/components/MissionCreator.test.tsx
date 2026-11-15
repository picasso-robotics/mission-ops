/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { render,screen,within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach,expect,it,vi } from 'vitest'
import { MissionCreator } from './MissionCreator'
import { InterfaceDeployment } from './InterfaceDeployment'
import { missionTemplate } from '../missionTemplates'
import type { MissionInterfaceView,MissionProfile } from '../api'
// 실행 호스트 시험 자원. MissionDefinitionGroupsHostTest 가 이 글자를 검증·모의 실행·활성화한다. ui 밖이라 Vite 가져오기가 아니라 파일로 읽는다(작업 디렉터리 ui 기준).
const visitProfileText=readFileSync(resolve(process.cwd(),'../mission-host/src/test/resources/mission-profile-visit.json'),'utf-8')
afterEach(()=>vi.unstubAllGlobals())
it('새 미션 ID와 입력 스키마 및 실행 단계가 함께 등록된다',async()=>{
 const created=vi.fn();const requests:unknown[]=[]
 vi.stubGlobal('fetch',vi.fn(async(_url:string,init:RequestInit)=>{const body=JSON.parse(init.body as string);requests.push(body);return new Response(JSON.stringify(body),{status:200})}))
 render(<MissionCreator session={{mode:'engineer',user:'lee'}} onCreated={created} onClose={()=>undefined}/> )
 await userEvent.clear(screen.getByLabelText('새 임무 ID'));await userEvent.type(screen.getByLabelText('새 임무 ID'),'LineAssetCheck')
 await userEvent.click(screen.getByRole('button',{name:'임무 등록 후 실행 정의 열기'}))
 expect(created).toHaveBeenCalledWith(expect.objectContaining({workMasterId:'LineAssetCheck'}))
 expect(requests[0]).toMatchObject({inputSchema:{required:['targets']},definition:{workMasterId:'LineAssetCheck',steps:[{skill:'inspect',forEach:'mission_target'}]}})
})
it('숫자 버전이 배포되지 않은 신규 미션에는 CODE 배포를 제공하지 않는다',async()=>{
 vi.stubGlobal('fetch',vi.fn(async(url:string)=>new Response(JSON.stringify(url.endsWith('/deployments')?{deployments:[]}:{versions:[{version:1}]}),{status:200})))
 const row:MissionInterfaceView={interfaceId:'line-check',version:1,name:'점검',workMasterId:'LineAssetCheck',mapping:{targets:'/assets'},sample:{},status:'DRAFT',createdBy:'lee',createdAt:'t',missionVersionPolicy:'PINNED_DEPLOYMENT',deployment:null}
 render(<InterfaceDeployment row={row} session={{mode:'engineer',user:'lee'}} onSaved={()=>undefined}/> )
 const select=screen.getByLabelText('배포할 임무 버전')
 expect(await within(select).findByRole('option',{name:'임무 v1'})).toBeVisible()
 expect(within(select).queryByRole('option',{name:'코드 정의 고정'})).not.toBeInTheDocument()
 expect(screen.getByRole('button',{name:'새 실행 배포 적용'})).toBeDisabled()
})
it('이동 템플릿은 입력 location를 이동 스킬 파라미터에 연결한다',()=>{
 const profile=missionTemplate('move','LineDelivery','라인 이동','설명')
 expect(profile.definition.steps).toEqual([expect.objectContaining({skill:'navigate_to',parameters:{location:{from:'ITEM_PROPERTY',property:'location'}}})])
})

it('대상마다 이동 뒤 점검 템플릿은 반복 그룹 하나에 이동과 이동을 선행으로 든 점검을 두고 화면에서 고를 수 있다',async()=>{
 const resource=JSON.parse(visitProfileText) as MissionProfile
 // 템플릿과 시험 자원이 갈리면 호스트가 검증한 정의와 화면이 내는 정의가 달라진다. 칸 전부를 맞댄다.
 expect(missionTemplate('visit',resource.workMasterId,resource.name,resource.description)).toEqual(resource)
 const profile=missionTemplate('visit','AssetVisitInspection','대상마다 이동 뒤 점검','설명')
 const location={from:'ITEM_PROPERTY',property:'location'}
 expect(profile.definition.steps).toEqual([{kind:'each',id:'visit',forEach:'mission_target',requires:[location],whenMissing:{failureClass:'NO_LOCATION_FOR_TARGET',unit:'inspect',parameters:['target']},steps:[
  {kind:'unit',id:'travel',skill:'navigate_to',unitId:{from:'ITEM_ID',suffix:'.travel'},parameters:{location},destination:location},
  {kind:'unit',id:'inspect',skill:'inspect',after:'travel',unitId:{from:'ITEM_ID'},parameters:{target:{from:'ITEM_ID'}},destination:location}]}])
 expect(profile.orderMapping).toEqual(missionTemplate('inspect','AssetVisitInspection','x','y').orderMapping)
 const requests:unknown[]=[]
 vi.stubGlobal('fetch',vi.fn(async(_url:string,init:RequestInit)=>{const body=JSON.parse(init.body as string);requests.push(body);return new Response(JSON.stringify(body),{status:200})}))
 render(<MissionCreator session={{mode:'engineer',user:'lee'}} onCreated={()=>undefined} onClose={()=>undefined}/> )
 await userEvent.selectOptions(screen.getByLabelText('시작 템플릿'),'대상마다 이동 뒤 점검 · E0')
 await userEvent.click(screen.getByRole('button',{name:'임무 등록 후 실행 정의 열기'}))
 expect(requests[0]).toMatchObject({definition:{steps:[{kind:'each',forEach:'mission_target',steps:[{id:'travel'},{id:'inspect',after:'travel'}]}]}})
})

it('등록 응답 유실 뒤 화면을 다시 열어도 같은 미션 ID와 원본 입력으로 확인한다',async()=>{
 const calls:unknown[]=[];let fail=true
 vi.stubGlobal('fetch',vi.fn(async(_url:string,init:RequestInit)=>{const body=JSON.parse(init.body as string);calls.push(body);if(fail)throw new Error('응답 유실');return new Response(JSON.stringify(body),{status:200})}))
 const props={session:{mode:'engineer' as const,user:'lee'},onCreated:vi.fn(),onClose:()=>undefined}
 const first=render(<MissionCreator {...props}/> )
 await userEvent.clear(screen.getByLabelText('새 임무 ID'));await userEvent.type(screen.getByLabelText('새 임무 ID'),'RecoveredMission')
 await userEvent.click(screen.getByRole('button',{name:'임무 등록 후 실행 정의 열기'}))
 expect(await screen.findByRole('alert')).toHaveTextContent('응답 유실')
 first.unmount();fail=false;render(<MissionCreator {...props}/> )
 expect(screen.getByLabelText('새 임무 ID')).toHaveValue('RecoveredMission')
 await userEvent.click(screen.getByRole('button',{name:'임무 등록 후 실행 정의 열기'}))
 expect(calls[1]).toEqual(calls[0]);expect(props.onCreated).toHaveBeenCalledTimes(1)
})
