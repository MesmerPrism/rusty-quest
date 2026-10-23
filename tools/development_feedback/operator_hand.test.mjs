import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,readdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import * as backend from '../lib/meta-xr-operator-backend.mjs';
import {bindOperatorCapsule} from '../Bind-MetaXrInputCapsule.mjs';
const root=await mkdtemp(join(tmpdir(),'quest-hand-target-'));
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const handTools=['openxr_get_instance_info','openxr_get_session_info','openxr_get_frame_info',
  'openxr_get_hand_state','openxr_get_controller_pose','openxr_hand_gesture','openxr_convert_pose_between_spaces'];
const identity={package:'io.example.native',apk_sha256:'a'.repeat(64),layer_fingerprint:'b'.repeat(64),serial:'TEST-ONLY',
  boot_id:'boot-test',pid:123,process_birth:'birth-test',instance_id:'owner-instance-1',session_id:'3'};
function capsule(){return {schema:'rusty.quest.meta_xr_input.hand_target_capsule.v1',enabled:true,
  selection:{package:identity.package,apk_sha256:identity.apk_sha256,layer_fingerprint:identity.layer_fingerprint,
    feature_lock_sha256:'c'.repeat(64),consumer:'NativeOpenXr',source_commit:'d'.repeat(40)},
  proxy:{path:process.execPath,sha256:sha(awaitProxy)},origin:'http://127.0.0.1:18720',identity:structuredClone(identity),
  schema_pins:Object.fromEntries(handTools.map(n=>[n,backend.schemaHash({type:'object'})])),owner_route:'neutral.original',
  plan:{kind:'hand_target',hand:'right',hold_ms:100,cleanup_policy:'retire_original_actor',geometry:{
    schema:'rusty.quest.development_input_geometry.v1',status:'applied',evidence:'native-submitted-frame',host_kind:'native-openxr',
    process_id:123,host_session_id:'host-session-1',owner_token:'owner-instance-1',panel_generation:1,layout_revision:1,
    surface_width_pixels:1024,surface_height_pixels:768,reference_space:'LOCAL',center:[0,1,-1],right:[1,0,0],up:[0,1,0],
    width_meters:1,height_meters:.75,tracking_valid:true,panel_visible:true,submitted_frame:10,pose_revision:1,effective_pose_revision:1,
    widget:{id:'compute',enabled:true,visible:true,bounds_pixels:[400,300,624,468],clipped_bounds_pixels:[400,300,624,468]}}}};}
const awaitProxy=await readFile(process.execPath);
const validate=c=>(backend.validateHandTargetCapsule??backend.validateCapsule)(c);
test('hand target admits only explicit hand schemas and actor retirement; full v1 remains strict',()=>{
  const c=capsule();assert.doesNotThrow(()=>validate(c));assert.throws(()=>backend.validateCapsule(c));
  const missing=capsule();delete missing.schema_pins.openxr_hand_gesture;assert.throws(()=>validate(missing));
});
for(const [label,change]of [
  ['hidden',g=>g.widget.visible=false],['clipped away',g=>g.widget.clipped_bounds_pixels=[0,0,0,0]],
  ['stale layout',g=>g.status='stale'],['tracking invalid',g=>g.tracking_valid=false],
  ['unsubmitted native',g=>g.submitted_frame=0],['wrong actor',g=>g.process_id=124],
  ['pose revision',g=>g.effective_pose_revision=0],['non-unit axes',g=>g.right=[2,0,0]],
  ['out of widget clip',g=>g.widget.clipped_bounds_pixels=[1,1,100,100]]
])test(`hand target refuses ${label}`,()=>{const c=capsule();change(c.plan.geometry);assert.throws(()=>validate(c));});
test('SDK ECS geometry is separately labeled LOCAL_FLOOR with explicit RH conversion',()=>{
  const c=capsule();c.selection.consumer='SpatialSdk';const g=c.plan.geometry;g.host_kind='spatial-sdk';g.evidence='sdk-ecs-panel-transform';
  g.reference_space='LOCAL_FLOOR';g.submitted_frame=0;g.source_coordinate_system='sdk-left-handed-z-forward';g.coordinate_conversion='reflect-z';
  assert.doesNotThrow(()=>validate(c));delete g.coordinate_conversion;assert.throws(()=>validate(c));
});
test('measured larger surfaces use pixel bounds rather than metric pose limits',()=>{
  const c=capsule(),g=c.plan.geometry;g.surface_width_pixels=1920;g.surface_height_pixels=1080;
  g.widget.bounds_pixels=[1400,800,1800,1040];g.widget.clipped_bounds_pixels=[1400,800,1800,1040];
  assert.doesNotThrow(()=>validate(c));
});
test('the actual initial zero pose revision is coherent and valid; negative is refused',()=>{
  const c=capsule();c.plan.geometry.pose_revision=0;c.plan.geometry.effective_pose_revision=0;
  assert.doesNotThrow(()=>validate(c));c.plan.geometry.pose_revision=-1;c.plan.geometry.effective_pose_revision=-1;
  assert.throws(()=>validate(c));
});
test('inert binder pins the actual hand subset without inventing missing controller/release tools',async()=>{
  const c=capsule(),path=join(root,'hand-registry.json'),bytes=JSON.stringify({result:{tools:handTools.map(name=>({name,inputSchema:{type:'object'}}))}});
  await writeFile(path,bytes);const prepared={schema:'rusty.quest.meta_xr_input.prepared.v1',enabled:true,runtime_bound:false,
    selection:c.selection,proxy:c.proxy,registry:{path,sha256:sha(bytes)}};
  assert.deepEqual(await bindOperatorCapsule(prepared,{identity:c.identity,origin:c.origin,plan:c.plan,owner_route:c.owner_route}),c);
});
let number=0;
async function attempt(scenario='hand-target',options={}){
  const c=capsule(),dir=join(root,String(++number)),phases=[];let retired=0,opens=0,finalOpenRequest,finalOpenGeneration,finalOpenDelivered=false;
  const owner={route:'neutral.original',observe:async context=>{
    phases.push(context.phase);const r={...context,owner_route:'neutral.original',observed_at_utc:new Date().toISOString(),
      evidence_ref:'neutral-owner-receipt',evidence_sha256:'e'.repeat(64),status:'ready',installed_bytes_verified:true,layer_loaded:true,
      exclusive_input_grant:true,geometry:structuredClone(c.plan.geometry)};
    if(context.phase==='original_effect'||context.phase==='after_open_effect')r.status=(context.phase==='after_open_effect'?options.afterOpenEffect:options.effect)??'confirmed';
    if(context.phase==='after_open_effect'&&options.requireOpenDelivery&&!finalOpenDelivered)r.status='rejected';
    if(options.change)options.change(r,phases);return r;
  },retireOriginal:async context=>{retired++;return {...context,owner_route:'neutral.original',observed_at_utc:new Date().toISOString(),
    evidence_ref:'original-actor-retirement',evidence_sha256:'f'.repeat(64),status:'retired',scope:'original_actor_retirement',
    same_boot:true,original_process_absent:!options.stillAlive,original_layer_lifetime:'process-owned',replacement_followed:false};}};
  const invoke=()=> (backend.runOperatorHandTarget??backend.runOperatorInput)({capsule:c,owner,evidenceDirectory:dir,timeoutMs:options.timeoutMs??500,
    closeWaitMs:options.closeWaitMs??1000,testOnlyJournalWrite:(kind,payload)=>{
      if(kind==='request-intent'&&payload.params?.name==='openxr_hand_gesture'&&payload.params.arguments.gesture==='open'&&++opens>1)finalOpenRequest=payload.id;
      if(kind==='response'&&finalOpenRequest){const text=payload.result?.content?.[0]?.text;if(!text)return;const value=JSON.parse(text);
        if(payload.id===finalOpenRequest)finalOpenGeneration=value.generation;
        const h=value.hands?.right;if(finalOpenGeneration&&h?.delivered_generation>=finalOpenGeneration&&h.pinch?.index_pinching===false)finalOpenDelivered=true;
      }
    },testOnlyLaunch:()=>spawn(process.execPath,[join(dirname(fileURLToPath(import.meta.url)),'../tests/meta-xr-proxy-fixture.mjs'),scenario],
      {windowsHide:true,stdio:['pipe','pipe','pipe']})});
  const result=await invoke(),records=await Promise.all((await readdir(dir)).sort().map(async n=>JSON.parse(await readFile(join(dir,n)))));
  return {result,retired,phases,invoke,calls:records.filter(r=>r.kind==='request-intent'&&r.payload.method==='tools/call').map(r=>r.payload.params)};
}
test('hand target actual stdio: open aim delivered ray one pinch open and original actor retirement',async()=>{
  const a=await attempt();assert.equal(a.result.completed,true);assert.equal(a.result.dispatch_state,'delivered');
  assert.equal(a.result.release_state,'retired_original_actor');assert.equal(a.retired,1);
  assert.deepEqual(a.calls.filter(x=>x.name==='openxr_hand_gesture').map(x=>x.arguments.gesture),['open','aim','pinch','open']);
  assert(!a.calls.some(x=>x.name==='openxr_release_input_devices'||x.name.startsWith('openxr_set_controller')));
  assert.equal(a.result.capture_confirmed,false);await assert.rejects(a.invoke,/exist/i);
});
for(const scenario of ['hand-target-miss','hand-target-undelivered','hand-target-occupied','hand-target-stalled'])
test(`${scenario} produces no pinch and keeps failures explicit`,async()=>{
  const a=await attempt(scenario);assert.equal(a.result.completed,false);
  assert(!a.calls.some(x=>x.name==='openxr_hand_gesture'&&x.arguments.gesture==='pinch'));
});
test('changed geometry stops new effects; compensation targets original actor',async()=>{
  const a=await attempt('hand-target',{change:r=>{if(r.phase==='guard')r.geometry.layout_revision++;}});
  assert.equal(a.result.completed,false);assert(!a.calls.some(x=>x.name==='openxr_hand_gesture'));
});
test('unknown pinch is not replayed; exact original actor retirement still runs',async()=>{
  const a=await attempt('hand-target-lost-press');assert.equal(a.result.completed,false);assert.equal(a.result.dispatch_state,'outcome_unknown');
  assert.equal(a.retired,1);assert.equal(a.calls.filter(x=>x.name==='openxr_hand_gesture'&&x.arguments.gesture==='pinch').length,1);
});
test('open hand alone and ambiguous actor retirement cannot complete',async()=>{
  const a=await attempt('hand-target',{stillAlive:true});assert.equal(a.result.completed,false);assert.notEqual(a.result.release_state,'retired_original_actor');
});
test('a second handler completion during release prevents a passing interaction',async()=>{
  const a=await attempt('hand-target',{afterOpenEffect:'rejected'});assert.equal(a.result.completed,false);assert.equal(a.retired,1);
});
test('final open must actually be delivered before unchanged-handler readback',async()=>{
  const a=await attempt('hand-target-delayed-final-open',{requireOpenDelivery:true});assert.equal(a.result.completed,true);
  assert(a.calls.filter(x=>x.name==='openxr_get_hand_state').length>=6);
});
test('owned slow proxy stays pending until explicit original child retirement wait',async()=>{
  const a=await attempt('hand-target-slow-exit',{closeWaitMs:50});assert.equal(a.result.completed,false);assert.equal(a.result.proxy_exit.pending,true);
  assert.equal(typeof a.result.waitForProxyRetirement,'function');const exit=await a.result.waitForProxyRetirement(3000);
  assert.equal(exit.code,0);assert.equal(a.result.completed,true);
});
