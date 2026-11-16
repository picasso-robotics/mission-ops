import type { MissionProfile } from './api'
/**
 * 시작 템플릿 종류. visit 는 반복 그룹(picasso §15.325, `MissionFixtures.INSPECT_ASSET` 모양): 대상마다 이동 뒤 점검, 점검은 같은 대상의 이동을 선행으로 든다.
 * choice 는 그룹 안 접수 시점 분기(picasso §15.328, `MissionFixtures.INSPECT_BY_ITEM` 모양): 대상마다 이동 뒤 대상의 `item` 으로 점검을 고른다 —
 * `thermal` 이면 `<대상>`, `visual` 이면 `<대상>.look`, 다른 값이면 점검 없음, `item` 이 없으면 `<대상>` 하나가 `NO_ITEM_FOR_TARGET` 로 실패한다.
 */
export type MissionTemplateKind='inspect'|'move'|'visit'|'choice'
export function missionTemplate(kind:MissionTemplateKind,id:string,name:string,description:string):MissionProfile {
 if(kind==='choice')return choiceTemplate(id,name,description)
 const property='location'
 const destination={from:'ITEM_PROPERTY',property}
 const steps=kind==='visit'
  ?[{kind:'each',id:'visit',forEach:'mission_target',requires:[destination],whenMissing:{failureClass:'NO_LOCATION_FOR_TARGET',unit:'inspect',parameters:['target']},steps:[
    {kind:'unit',id:'travel',skill:'navigate_to',unitId:{from:'ITEM_ID',suffix:'.travel'},parameters:{[property]:destination},destination},
    {kind:'unit',id:'inspect',skill:'inspect',after:'travel',unitId:{from:'ITEM_ID'},parameters:{target:{from:'ITEM_ID'}},destination}]}]
  :[{kind:'unit',id:kind,skill:kind==='inspect'?'inspect':'navigate_to',forEach:'mission_target',unitId:{from:'ITEM_ID'},parameters:kind==='inspect'?{target:{from:'ITEM_ID'}}:{location:{from:'ITEM_PROPERTY',property}},destination:{from:'ITEM_PROPERTY',property}}]
 return {
  workMasterId:id,name,description,requiredEvidence:'E0',
  inputSchema:{type:'object',properties:{targets:{type:'array',minItems:1,maxItems:20,items:{type:'object',properties:{id:{type:'string',minLength:1,maxLength:64},[property]:{type:'string',minLength:1,maxLength:64}},required:['id',property],additionalProperties:false}}},required:['targets'],additionalProperties:false},
  orderMapping:{equipment:[{input:'targets',equipmentUse:'mission_target',id:'id',properties:{[property]:property}}],materials:[]},
  sample:{targets:[{id:'T1',[property]:'bay-7'}]},
  definition:{schemaVersion:1,workMasterId:id,maxEvidence:'E0',preferredOptionals:{},steps}
 }
}
/** 그룹 안 분기 템플릿. 실행 호스트 시험 자원 `mission-profile-choice.json` 과 칸 전부가 같다(MissionCreator.test 가 맞댄다). */
function choiceTemplate(id:string,name:string,description:string):MissionProfile {
 const location={from:'ITEM_PROPERTY',property:'location'}
 const inspect=(nodeId:string,suffix?:string)=>({kind:'unit',id:nodeId,skill:'inspect',after:'travel',unitId:suffix===undefined?{from:'ITEM_ID'}:{from:'ITEM_ID',suffix},parameters:{target:{from:'ITEM_ID'}},destination:location})
 const text={type:'string',minLength:1,maxLength:64}
 return {
  workMasterId:id,name,description,requiredEvidence:'E0',
  inputSchema:{type:'object',properties:{targets:{type:'array',minItems:1,maxItems:20,items:{type:'object',properties:{id:text,location:text,item:text},required:['id','location','item'],additionalProperties:false}}},required:['targets'],additionalProperties:false},
  orderMapping:{equipment:[{input:'targets',equipmentUse:'mission_target',id:'id',properties:{location:'location',item:'item'}}],materials:[]},
  sample:{targets:[{id:'T1',location:'bay-7',item:'thermal'},{id:'T2',location:'dock-3',item:'visual'}]},
  definition:{schemaVersion:1,workMasterId:id,maxEvidence:'E0',preferredOptionals:{},steps:[{kind:'each',id:'visit',forEach:'mission_target',steps:[
   {kind:'unit',id:'travel',skill:'navigate_to',unitId:{from:'ITEM_ID',suffix:'.travel'},parameters:{location},destination:location},
   {kind:'choice',id:'how',on:{from:'ITEM_PROPERTY',property:'item'},
    arms:[{equals:'thermal',steps:[inspect('thermal')]},{equals:'visual',steps:[inspect('look','.look')]}],
    otherwise:{steps:[]},
    whenMissing:{failureClass:'NO_ITEM_FOR_TARGET',unit:'thermal',parameters:['target']}}]}]}
 }
}
