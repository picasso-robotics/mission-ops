import type { MissionProfile } from './api'
/** 시작 템플릿 종류. visit 는 반복 그룹(picasso §15.325, `MissionFixtures.INSPECT_ASSET` 모양): 대상마다 이동 뒤 점검, 점검은 같은 대상의 이동을 선행으로 든다. */
export type MissionTemplateKind='inspect'|'move'|'visit'
export function missionTemplate(kind:MissionTemplateKind,id:string,name:string,description:string):MissionProfile {
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
