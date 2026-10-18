import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,readdir,writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {requiredTools,schemaHash,validateCapsule,runOperatorInput} from '../lib/meta-xr-operator-backend.mjs';
import {bindOperatorCapsule} from '../Bind-MetaXrInputCapsule.mjs';
const root=await mkdtemp(join(tmpdir(),'quest-operator-'));
const proxyHash=createHash('sha256').update(await readFile(process.execPath)).digest('hex');
const identity={package:'io.example.native',apk_sha256:'a'.repeat(64),layer_fingerprint:'b'.repeat(64),
  serial:'TEST-ONLY',boot_id:'boot-test',pid:123,process_birth:'birth-test',instance_id:'owner-instance-1',session_id:'3'};
function capsule(kind='controller_select') {
  return {schema:'rusty.quest.meta_xr_input.capsule.v1',enabled:true,
    selection:{package:identity.package,apk_sha256:identity.apk_sha256,layer_fingerprint:identity.layer_fingerprint,
      feature_lock_sha256:'c'.repeat(64),consumer:'NativeOpenXr',source_commit:'d'.repeat(40)},
    proxy:{path:process.execPath,sha256:proxyHash},origin:'http://127.0.0.1:18720',identity:structuredClone(identity),owner_route:'neutral.original',
    schema_pins:Object.fromEntries(requiredTools.map(n=>[n,schemaHash({type:'object'})])),
    plan:{kind,hand:'right',base_space:'local',position:[0,0,-1],orientation:[0,0,0,1],hold_ms:100}};
}
let number=0;
async function attempt(scenario='normal',options={}) {
  const c=options.capsule??capsule(options.kind),dir=join(root,String(++number));let launches=0;const phases=[];
  const owner={route:'neutral.original',observe:async context=>{
    phases.push(context.phase);
    const receipt={...context,owner_route:'neutral.original',observed_at_utc:new Date().toISOString(),evidence_ref:'neutral-owner-receipt',evidence_sha256:'e'.repeat(64),
      status:'ready',installed_bytes_verified:true,layer_loaded:true,focused:true,exclusive_input_grant:true};
    if(context.phase==='original_effect') receipt.status=options.effect??'confirmed';
    if(context.phase==='release') Object.assign(receipt,{status:'released',buttons_down:false,overrides_active:options.stuck??false});
    if(context.phase==='terminal') Object.assign(receipt,{status:'confirmed',scope:'input_operation',app_running:true});
    options.change?.(receipt,phases);
    return receipt;
  }};
  const invoke=()=>runOperatorInput({capsule:c,owner,evidenceDirectory:dir,timeoutMs:5000,closeWaitMs:options.closeWaitMs??3000,testOnlyJournalWrite:options.journal,
    testOnlyLaunch:()=>{launches++;return spawn(process.execPath,[join(dirname(fileURLToPath(import.meta.url)),'meta-xr-proxy-fixture.mjs'),scenario],{windowsHide:true,stdio:['pipe','pipe','pipe']});}});
  const result=await invoke();
  const records=await Promise.all((await readdir(dir)).sort().map(async name=>JSON.parse(await readFile(join(dir,name)))));
  const calls=records.filter(x=>x.kind==='request-intent' && x.payload.method==='tools/call').map(x=>x.payload.params);
  return {result,records,calls,phases,launches,dir,invoke};
}
test('native controller: actual stdio protocol, journal before effect, original effect, effective release, app retained',async()=>{
  const a=await attempt();assert.equal(a.result.completed,true);assert.equal(a.result.dispatch_state,'acknowledged');
  assert.equal(a.result.evidence_tier,'host-protocol');assert.equal(a.result.capture_confirmed,false);
  assert.equal(a.result.receipts.terminal.app_running,true);
  assert.equal(a.calls.filter(c=>c.name==='openxr_set_controller_input' && c.arguments.value===1).length,1);
  const press=a.calls.find(c=>c.name==='openxr_set_controller_input' && c.arguments.value===1);
  assert.equal(press.arguments.auto_release,true);assert.equal(press.arguments.hold_duration,.1);
  assert(a.calls.some(x=>x.name==='openxr_release_input_devices'));
  assert(a.records.findIndex(x=>x.kind==='attempt')<a.records.findIndex(x=>x.kind==='request-intent'));
  await assert.rejects(a.invoke,/exist/i);
});
test('independent hand consumer: generation delivered, no controller injection',async()=>{
  const c=capsule('hand_pinch');c.selection.consumer='SpatialSdk';const a=await attempt('normal',{capsule:c});
  assert.equal(a.result.completed,true);assert.equal(a.result.dispatch_state,'delivered');
  assert(!a.calls.some(x=>x.name==='openxr_set_controller_input'));
});
for(const scenario of ['stalled','unfocused','new-session','offline','schema','lost-frame','occupied-hand','occupied-controller']) test(`readiness rejects ${scenario} without input`,async()=>{
  const a=await attempt(scenario);assert.equal(a.result.completed,false);assert.equal(a.result.dispatch_state,'not_sent');
  assert(!a.calls.some(x=>x.name.startsWith('openxr_set_') || x.name==='openxr_hand_gesture'));
  if(scenario==='lost-frame') assert.equal(a.calls.filter(x=>x.name==='openxr_get_frame_info').length,1);
});
test('lost press is never replayed; explicit original release and terminal remain',async()=>{
  const a=await attempt('lost-press');assert.equal(a.result.dispatch_state,'outcome_unknown');assert.equal(a.result.completed,false);
  assert.equal(a.result.release_state,'confirmed');assert.equal(a.result.terminal_state,'confirmed');
  assert.equal(a.result.original_effect_state,'confirmed'); // Reconciled separately; dispatch remains unknown.
  assert.equal(a.calls.filter(x=>x.name==='openxr_set_controller_input' && x.arguments.value===1).length,1);
});
test('reporting disk failure after press preserves ACK and cannot block original compensation',async()=>{
  let pressed=false;
  const a=await attempt('normal',{journal:(kind,payload)=>{
    if(kind==='request-intent' && payload.params?.name==='openxr_set_controller_input' && payload.params.arguments.value===1){pressed=true;return;}
    if(pressed)throw Error('Simulated disk full after durable press intent');
  }});
  assert.equal(a.result.dispatch_state,'acknowledged');assert.equal(a.result.release_state,'confirmed');
  assert.equal(a.result.evidence_persisted,false);assert(a.result.reporting_errors.length>0);
  const calls=a.result.rpc_records.filter(x=>x.request.method==='tools/call');
  assert.equal(calls.filter(x=>x.request.params.name==='openxr_set_controller_input' && x.request.params.arguments.value===1).length,1);
  assert(calls.some(x=>x.request.params.name==='openxr_release_input_devices' && x.state==='answered'));
});
test('missing durable select intent blocks new press while original setup can still release',async()=>{
  const a=await attempt('normal',{journal:(kind,payload)=>{
    if(kind==='request-intent' && payload.params?.name==='openxr_set_controller_input' && payload.params.arguments.value===1)throw Error('No durable select intent');
  }});
  assert.equal(a.result.completed,false);assert.equal(a.result.release_state,'confirmed');
  assert(!a.result.rpc_records.some(x=>x.request.params?.name==='openxr_set_controller_input' && x.request.params.arguments.value===1));
});
test('first mutation intent failure before stdin has no possible input and no compensation effects',async()=>{
  const a=await attempt('normal',{journal:(kind,payload)=>{
    if(kind==='request-intent' && payload.params?.name==='openxr_set_controller_input')throw Error('First input intent unavailable');
  }});
  assert.equal(a.result.dispatch_state,'not_sent');assert.equal(a.result.release_state,'not_required');
  assert(!a.result.rpc_records.some(x=>x.request.params?.name?.startsWith('openxr_set_') || x.request.params?.name==='openxr_release_input_devices'));
});
test('focus and new-input grant loss cannot block same-original-identity compensation',async()=>{
  const a=await attempt('normal',{change:r=>{if(r.phase==='release_guard'){r.focused=false;r.exclusive_input_grant=false;r.status='unfocused';}}});
  assert.equal(a.result.release_state,'confirmed');assert.equal(a.result.completed,true);
  assert(a.calls.some(x=>x.name==='openxr_release_input_devices'));
});
for(const scenario of ['focus-after-ready','session-after-ready']) test(`live ${scenario} defeats stale focused owner before first mutation`,async()=>{
  const a=await attempt(scenario);
  assert.equal(a.result.dispatch_state,'not_sent');assert.equal(a.result.release_state,'not_required');
  assert(!a.calls.some(x=>x.name.startsWith('openxr_set_') || x.name==='openxr_hand_gesture' || x.name==='openxr_release_input_devices'));
  assert(a.result.errors.some(x=>x.includes('before new input')));
});
for(const [scenario,kind] of [['focus-after-pose','controller_select'],['focus-after-hand-open','hand_pinch']]) test(`live ${scenario} blocks select but preserves original compensation`,async()=>{
  const a=await attempt(scenario,{kind});
  assert.equal(a.result.completed,false);assert.equal(a.result.release_state,'confirmed');
  assert(!a.calls.some(x=>(x.name==='openxr_set_controller_input' && x.arguments.value===1) || (x.name==='openxr_hand_gesture' && x.arguments.gesture==='pinch')));
  assert(a.calls.some(x=>x.name==='openxr_release_input_devices' && x.arguments.session_handle===3));
  assert(a.result.errors.some(x=>x.includes('before new input')));
});
test('late original reply retains its request ID and response without select replay',async()=>{
  const a=await attempt('late-press');assert.equal(a.result.dispatch_state,'outcome_unknown');
  const press=a.result.rpc_records.find(x=>x.request.params?.name==='openxr_set_controller_input' && x.request.params.arguments.value===1);
  assert.equal(press.state,'late_answered');assert.equal(press.reply.id,press.request.id);
  assert(a.records.some(x=>x.kind==='late-response' && x.payload.id===press.request.id));
});
test('slow healthy proxy retires through actual EOF instead of fixed one-second kill',async()=>{
  const start=Date.now();const a=await attempt('slow-exit');assert.equal(a.result.proxy_exit.code,0);assert.equal(a.result.completed,true);assert(Date.now()-start>=1400);
});
test('retirement timeout returns explicit pending custody and can later await exact owned child',async()=>{
  const a=await attempt('slow-exit',{closeWaitMs:50});assert.equal(a.result.proxy_exit.pending,true);assert.equal(a.result.completed,false);
  assert.equal((await a.result.waitForProxyRetirement(3000)).code,0);
  assert.equal(a.result.completed,true);
  const names=await readdir(a.dir);const final=names.find(x=>x.includes('result-after-retirement'));
  assert.equal(JSON.parse(await readFile(join(a.dir,final))).payload.completed,true);
});
test('delivered hand is distinct from original SDK widget effect',async()=>{
  const a=await attempt('normal',{kind:'hand_pinch',effect:'pending'});
  assert.equal(a.result.dispatch_state,'delivered');assert.equal(a.result.original_effect_state,'pending');assert.equal(a.result.completed,false);
});
test('published hand without delivered generation cannot complete',async()=>{
  const a=await attempt('undelivered',{kind:'hand_pinch',effect:'pending'});assert.equal(a.result.dispatch_state,'acknowledged');assert.equal(a.result.release_state,'confirmed');assert.equal(a.result.completed,false);
});
test('open/ack alone cannot clear unknown effective override release',async()=>{
  const a=await attempt('normal',{kind:'hand_pinch',stuck:true});assert.equal(a.result.release_state,'outcome_unknown');assert.equal(a.result.completed,false);
});
test('fresh identity mismatch after uncertain press never retargets release to new process',async()=>{
  const a=await attempt('lost-press',{change:r=>{if(r.phase==='release_guard')r.identity.session_id='4';}});
  assert.equal(a.result.completed,false);assert.equal(a.result.release_state,'outcome_unknown');
  assert(!a.calls.some(x=>x.arguments.session_handle===4));
});
test('stale guard cannot even start proxy',async()=>{
  const a=await attempt('normal',{change:r=>{r.observed_at_utc='2000-01-01T00:00:00Z';}});assert.equal(a.launches,0);assert.equal(a.result.dispatch_state,'not_sent');
});
test('invalid capsule rejects before any injected observer or process launch',async()=>{
  const c=capsule();c.origin+='/sse';let callbacks=0;
  await assert.rejects(()=>runOperatorInput({capsule:c,evidenceDirectory:join(root,'invalid'),
    owner:{route:'neutral.original',observe:()=>{callbacks++;}},testOnlyLaunch:()=>{callbacks++;}}));
  assert.equal(callbacks,0);
});
for(const [label,change] of [
  ['endpoint suffix',c=>c.origin+='/sse'],['package mismatch',c=>c.identity.package='io.other'],
  ['disabled',c=>c.enabled=false],['non-unit quaternion',c=>c.plan.orientation=[0,0,0,0]],
  ['unbounded hold',c=>c.plan.hold_ms=5000],['arbitrary action',c=>c.plan.kind='shell'],['extra field',c=>c.plan.auto_release=true]
]) test(`capsule rejects ${label}`,()=>{const c=capsule();change(c);assert.throws(()=>validateCapsule(c));});
test('inert bind checks actual proxy and reviewed registry; no spawn',async()=>{
  const registryPath=join(root,'registry.json');const bytes=JSON.stringify({result:{tools:requiredTools.map(name=>({name,inputSchema:{type:'object'}}))}});await writeFile(registryPath,bytes);
  const c=capsule();const prepared={schema:'rusty.quest.meta_xr_input.prepared.v1',enabled:true,runtime_bound:false,selection:c.selection,proxy:c.proxy,
    registry:{path:registryPath,sha256:createHash('sha256').update(bytes).digest('hex')}};
  const binding={identity:c.identity,origin:c.origin,plan:c.plan,owner_route:c.owner_route};
  assert.deepEqual(await bindOperatorCapsule(prepared,binding),c);
  await assert.rejects(()=>bindOperatorCapsule(prepared,{...binding,selection:c.selection}),/Only runtime/);
  prepared.proxy={...c.proxy,sha256:'0'.repeat(64)};await assert.rejects(()=>bindOperatorCapsule(prepared,binding),/Proxy changed/);
});
