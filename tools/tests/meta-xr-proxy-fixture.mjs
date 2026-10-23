// Independent stdio MCP peer. No network, headset, SDK or vendor binary.
import {createInterface} from 'node:readline';
import {readFileSync} from 'node:fs';
const scenario=process.argv[2];
const reviewed=process.argv[3]?JSON.parse(readFileSync(process.argv[3],'utf8')).result.tools:null;
let clock=100, generation=0, pinch=false,released=true,sessionReads=0,poseSet=false,opens=0,finalOpenPolls=0;
const names=scenario.startsWith('hand-target')?['openxr_get_instance_info','openxr_get_session_info','openxr_get_frame_info',
  'openxr_get_hand_state','openxr_get_controller_pose','openxr_hand_gesture','openxr_convert_pose_between_spaces']:
  ['openxr_get_instance_info','openxr_get_session_info','openxr_get_frame_info','openxr_set_controller_input',
  'openxr_set_controller_pose','openxr_hand_gesture','openxr_get_hand_state','openxr_release_input_devices','openxr_get_controller_pose'];
const wrap = value => ({isError:false,content:[{type:'text',text:JSON.stringify(value)}]});
const lines=createInterface({input:process.stdin});
lines.on('close',()=>{if(scenario==='slow-exit'||scenario==='hand-target-slow-exit')setTimeout(()=>{},1400);});
lines.on('line',line=>{
  const request=JSON.parse(line);if(!request.id)return;
  let result={};
  if(request.method==='initialize') result={protocolVersion:'2024-11-05',capabilities:{tools:{}}};
  if(request.method==='tools/list') result={tools:names.map(name=>({name,description:scenario==='offline'?'[OFFLINE]':'live',inputSchema:scenario==='schema'?{type:'string'}:(reviewed?.find(x=>x.name===name).inputSchema??{type:'object'})}))};
  if(request.method==='tools/call') {
    const {name,arguments:a}=request.params;
    if(scenario==='hand-target-lost-press'&&name==='openxr_hand_gesture'&&a.gesture==='pinch')return;
    if(scenario==='lost-frame' && name==='openxr_get_frame_info') return;
    if(scenario==='lost-press' && ((name==='openxr_set_controller_input' && a.value===1) || (name==='openxr_hand_gesture' && a.gesture==='pinch'))) return;
    if(name==='openxr_get_instance_info') result=wrap({application:{name:'Neutral native owner'}});
    if(name==='openxr_get_session_info') {
      sessionReads++;
      const changed=scenario==='new-session' || (scenario==='session-after-ready' && sessionReads>3);
      const unfocused=scenario==='unfocused' || (scenario==='focus-after-ready' && sessionReads>3) ||
        (scenario==='focus-after-pose' && poseSet) || (scenario==='focus-after-hand-open' && generation>0);
      result=wrap({session:{handle:changed?'4':'3'},state:{name:unfocused?'XR_SESSION_STATE_VISIBLE':'XR_SESSION_STATE_FOCUSED'}});
    }
    if(name==='openxr_get_frame_info') result=wrap({session:{handle:'3'},frame_state:{should_render:true,predicted_display_time:{XrTime:scenario==='stalled'||scenario==='hand-target-stalled'?100:++clock}}});
    if(name==='openxr_convert_pose_between_spaces')result=wrap({flags:{position_valid:true,orientation_valid:true},output_pose:{position:a.position,orientation:a.orientation}});
    if(name==='openxr_set_controller_input' || name==='openxr_set_controller_pose'){released=false;result=wrap({success:true,auto_release:a.auto_release});}
    if(name==='openxr_set_controller_pose')poseSet=true;
    if(name==='openxr_get_controller_pose') result=wrap({simulated:scenario==='occupied-controller'||!released,is_active:true,flags:{position_valid:true,orientation_valid:true}});
    if(name==='openxr_hand_gesture'){released=false;pinch=a.gesture==='pinch';if(a.gesture==='open')opens++;result=wrap({success:true,auto_release:a.auto_release,edge_produced:true,generation:++generation,wrist_orientation:[0,0,0,1]});
      if(scenario==='hand-target-vendor-aim'&&a.gesture==='aim')result=wrap({success:true,hand:a.hand,gesture:'aim',base_space:a.base_space,wrist_position:a.wrist_position,wrist_orientation:[0,0,0,1],aim_ray:{origin:[0,1,-.3],direction:[0,0,-1],base_space:'stage'},result:'completed'});
      if(scenario==='hand-target-blocking-release'&&a.gesture==='pinch'&&a.auto_release===true)pinch=false;
    }
    if(name==='openxr_get_hand_state') {
      const handState={synthetic_hand_override:scenario==='occupied-hand'||!released,delivered_generation:scenario==='undelivered'?0:generation,pinch:{index_pinching:pinch}};
      if(scenario.startsWith('hand-target'))Object.assign(handState,{synthetic_hand_override:scenario==='hand-target-occupied'||!released,
        published_generation:generation,delivered_generation:scenario==='hand-target-undelivered'?0:generation,
        aim_ray:{base_space:'stage',origin:[0,1,-.3],direction:scenario==='hand-target-miss'?[1,0,0]:[0,0,-1]}});
      if(scenario==='hand-target-delayed-final-open'&&opens>1&&finalOpenPolls++<2)Object.assign(handState,{delivered_generation:generation-1,pinch:{index_pinching:true}});
      result=wrap({conformance_automation_mode:released?'physical':'simulated',hands:a.hand==='both'?{left:handState,right:handState}:{[a.hand]:handState}});
    }
    if(name==='openxr_release_input_devices'){released=true;result=wrap({success:true});}
  }
  const reply=JSON.stringify({jsonrpc:'2.0',id:request.id,result})+'\n';
  if(scenario==='late-press' && request.params?.name==='openxr_set_controller_input' && request.params.arguments.value===1)
    setTimeout(()=>process.stdout.write(reply),6000);
  else process.stdout.write(reply);
});
