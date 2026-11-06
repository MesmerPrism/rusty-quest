import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,readdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';

const api=await import('./development_host_owner.mjs').catch(error=>{
  if(error.code==='ERR_MODULE_NOT_FOUND'&&error.url?.endsWith('/development_host_owner.mjs'))return {};
  throw error;
});
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const identity={package:'io.example.operator',apk_sha256:'a'.repeat(64),layer_fingerprint:'b'.repeat(64),
  serial:'HOST-TEST',boot_id:'boot-original',pid:71,process_birth:'1234',instance_id:'owner-original',session_id:'9'};
const geometry={schema:'rusty.quest.development_input_geometry.v1',status:'applied',evidence:'native-submitted-frame',
  host_kind:'native-openxr',process_id:71,host_session_id:'host-session',owner_token:'owner-original',
  panel_generation:1,layout_revision:1,surface_width_pixels:1024,surface_height_pixels:768,reference_space:'LOCAL',
  center:[0,1,-1],right:[1,0,0],up:[0,1,0],width_meters:1,height_meters:.75,tracking_valid:true,panel_visible:true,
  submitted_frame:8,pose_revision:1,effective_pose_revision:1,
  widget:{id:'compute',enabled:true,visible:true,bounds_pixels:[200,100,400,200],clipped_bounds_pixels:[200,100,400,200]}};
const baseline={native_state:'ready',native_session_id:'host-session',native_owner_token:'owner-original',
  native_generation:'2',native_generation_sha256:'c'.repeat(64),native_allocation:'1',native_completed_unsigned:'4',native_value:'20',
  original_ui_queued_request_id:'ui-compute-4',original_ui_completed_request_id:'ui-compute-4',original_ui_completed_count:'4'};
const context=phase=>({identity:structuredClone(identity),plan:{geometry:structuredClone(geometry)},correlation_id:'attempt-one',phase});
const transport='TRANSPORT-TEST';
// Sanitized real apk_stop_result.v1 shape. This host fixture is not device evidence.
function qfmStop(id){return {
  schema:'questionable.file_manager.apk_stop_result.v1',succeeded:true,
  mutation:{OperationId:'host-stop-test',CommandKind:'stopPackage',Target:transport,
    DesiredState:`exact package ${id.package} quiescent`,Stage:'confirmed',ObservedState:'host fixture only',HeadsetReadback:true,
    Transitions:[{Stage:'sent',At:'2026-01-01T00:00:00Z',Message:'host fixture'},
      {Stage:'pending',At:'2026-01-01T00:00:00Z',Message:'host fixture'},
      {Stage:'confirmed',At:'2026-01-01T00:00:00Z',Message:'host fixture'}],IsTerminal:true},
  result:{Serial:transport,PackageName:id.package,PackagePresentBeforeDispatch:true,PackagePresentAfterDispatch:true,
    StopCommand:{FileName:'adb',Arguments:['-s',transport,'shell','am','force-stop','--user','current',id.package],
      ExitCode:0,StandardOutput:'',StandardError:'',Duration:'00:00:00.1',Succeeded:true,CondensedOutput:'Command exited with code 0.'},
    Quiescence:{ProcessIds:[],ForegroundComponents:[],TopResumedComponents:[],IsQuiescent:true}},failure:null
};}

async function fixture(change={}){
  assert.equal(typeof api.createDevelopmentHostOwner,'function','finite development host owner API is missing');
  const root=await mkdtemp(join(tmpdir(),'quest-owner-')),calls=[];
  const state=structuredClone(baseline),g=structuredClone(geometry),actor={identity:structuredClone(identity),present:true,installed_bytes_verified:true,transport_selector:transport};
  let sequence=0;
  async function envelope(value,kind){
    const raw=JSON.stringify(value),path=join(root,`${++sequence}-${kind}.json`);await writeFile(path,raw,{flag:'wx'});
    return {value:structuredClone(value),evidence_ref:path,evidence_sha256:sha(raw),observed_at_utc:new Date().toISOString()};
  }
  const callbacks={
    readActor:async c=>{calls.push(['actor',c.phase]);return envelope(actor,'actor');},
    readStatus:async c=>{calls.push(['status',c.phase]);return envelope({schema:'rusty.quest.hotload_observation.v1',request_id:'status-one',host_kind:g.host_kind,status:'pass',error:null,state,scene:{operator_layer_loaded:true,input_geometry:g}},'status');},
    readGrant:async c=>{calls.push(['grant',c.phase]);return envelope({identity:structuredClone(identity),exclusive_input_grant:true,binding_valid:true},'grant');},
    stopOriginal:async c=>{
      calls.push(['stop',c.phase]);const stop=await envelope(qfmStop(c.identity),'qfm-stop');
      return envelope({identity:c.identity,status:'retired',operation:'qfm-package-stop',same_boot:true,original_process_absent:true,
        original_layer_lifetime:'process-owned',replacement_followed:false,transport_selector:transport,stop_receipt_ref:stop.evidence_ref,stop_receipt_sha256:stop.evidence_sha256},'terminal');
    }
  };
  change.setup?.({callbacks,state,g,actor,envelope,calls});
  const owner=await api.createDevelopmentHostOwner({identity:structuredClone(identity),geometry:structuredClone(g),baseline:structuredClone(state),callbacks,evidenceDirectory:join(root,'owner'),route:'public.original-handler'});
  return {owner,root,state,g,actor,calls,envelope,callbacks};
}
function complete(f){Object.assign(f.state,{native_completed_unsigned:'5',native_value:'25',original_ui_queued_request_id:'ui-compute-5',original_ui_completed_request_id:'ui-compute-5',original_ui_completed_count:'5'});}

test('fresh guard retains actual immutable callback evidence and issues no input/stop effects',async()=>{
  const f=await fixture(),r=await f.owner.observe(context('guard'));
  assert.equal(r.status,'ready');assert.equal(r.exclusive_input_grant,true);assert.equal(r.installed_bytes_verified,true);assert.equal(r.layer_loaded,true);
  assert.deepEqual(r.identity,identity);assert.equal(r.owner_route,'public.original-handler');assert.equal(r.correlation_id,'attempt-one');
  assert.equal(sha(await readFile(r.evidence_ref)),r.evidence_sha256);assert(!f.calls.some(c=>c[0]==='stop'));
  const persisted=await readdir(join(f.root,'owner'));assert(persisted.length>=4);assert.equal(r.capture_confirmed,false);
  await assert.rejects(api.createDevelopmentHostOwner({identity,geometry,baseline,callbacks:f.callbacks,evidenceDirectory:join(f.root,'owner'),route:'public.original-handler'}),/exist/i);
});
test('counter and original UI completion prove one handler effect and stay unchanged after final open',async()=>{
  const f=await fixture();await f.owner.observe(context('guard'));complete(f);
  const effect=await f.owner.observe(context('original_effect'));assert.equal(effect.status,'confirmed');assert.equal(effect.native_value,'25');
  const release=await f.owner.observe(context('release_guard'));assert.equal(release.exclusive_input_grant,false);
  const after=await f.owner.observe(context('after_open_effect'));assert.equal(after.status,'confirmed');
  assert.equal(f.calls.filter(c=>c[0]==='grant').length,1);
});
test('SDK original UUID request ID and numeric UI count are admitted without changing its handler',async()=>{
  const f=await fixture({setup:({g,state})=>{Object.assign(g,{host_kind:'spatial-sdk',evidence:'sdk-ecs-panel-transform',reference_space:'LOCAL_FLOOR',submitted_frame:0,source_coordinate_system:'sdk-left-handed-z-forward',coordinate_conversion:'reflect-z'});state.original_ui_completed_count=4;}});
  const c=context('original_effect');c.plan.geometry=structuredClone(f.g);complete(f);f.state.original_ui_completed_count=5;
  f.state.original_ui_queued_request_id=f.state.original_ui_completed_request_id='ui-12345678-1234-1234-1234-123456789abc';
  assert.equal((await f.owner.observe(c)).status,'confirmed');
});
test('SDK Activity owner and resident JNI owner are distinct, both retained across the effect',async()=>{
  const f=await fixture({setup:({g,state})=>{
    Object.assign(g,{host_kind:'spatial-sdk',evidence:'sdk-ecs-panel-transform',reference_space:'LOCAL_FLOOR',submitted_frame:null,source_coordinate_system:'sdk-left-handed-z-forward',coordinate_conversion:'reflect-z'});
    state.native_owner_token='resident-jni-owner';state.native_session_id='resident-jni-session';state.host_session_id=g.host_session_id;
  }});
  const c=context('guard');c.plan.geometry=structuredClone(f.g);assert.equal((await f.owner.observe(c)).status,'ready');
  complete(f);f.state.original_ui_queued_request_id=f.state.original_ui_completed_request_id='ui-12345678-1234-1234-1234-123456789abc';c.phase='original_effect';
  const effect=await f.owner.observe(c);assert.equal(effect.status,'confirmed');assert.equal(effect.state.native_owner_token,'resident-jni-owner');
});
test('successful Compute may change layout, bounds and tracking without changing the original owner',async()=>{
  const f=await fixture();complete(f);f.g.layout_revision++;f.g.widget.bounds_pixels=[200,100,500,220];f.g.widget.clipped_bounds_pixels=[200,100,500,220];
  f.g.tracking_valid=false;f.g.status='pending';
  assert.equal((await f.owner.observe(context('original_effect'))).status,'confirmed');
  f.g.layout_revision++;
  assert.equal((await f.owner.observe(context('after_open_effect'))).status,'confirmed');
});
for(const phase of ['original_effect','after_open_effect','release_guard','retire_original_actor'])
for(const field of ['owner_token','host_session_id'])
test(`${phase} refuses same-PID replacement of app ${field}`,async()=>{
  const f=await fixture();complete(f);
  if(phase==='after_open_effect')await f.owner.observe(context('original_effect'));
  f.g[field]='replacement-activity';
  if(phase==='retire_original_actor')await assert.rejects(f.owner.retireOriginal(context(phase)),/owner|session|actor/i);
  else await assert.rejects(f.owner.observe(context(phase)),/owner|session|actor/i);
  assert(!f.calls.some(c=>c[0]==='stop'));
});
test('current status host session must match its geometry even during cleanup',async()=>{
  const f=await fixture();f.state.host_session_id='replacement-host-session';
  await assert.rejects(f.owner.observe(context('release_guard')),/session/i);
});
test('unchanged computation is pending, never confirmed',async()=>{
  const f=await fixture();assert.equal((await f.owner.observe(context('original_effect'))).status,'pending');
});
test('coherent initial pose revision zero is admitted without relabeling renderer tokens',async()=>{
  const f=await fixture({setup:({g})=>{g.pose_revision=0;g.effective_pose_revision=0;}});
  const c=context('guard');c.plan.geometry=structuredClone(f.g);assert.equal((await f.owner.observe(c)).status,'ready');
});
for(const [label,change]of [['negative',g=>{g.pose_revision=-1;g.effective_pose_revision=-1;}],['missing',g=>delete g.pose_revision],['mismatched',g=>g.effective_pose_revision=0]])
test(`initial pose revision ${label} is refused`,async()=>{
  await assert.rejects(fixture({setup:({g})=>change(g)}),/geometry|pose/i);
});
for(const [label,mutate]of [
  ['counter skip',f=>f.state.native_completed_unsigned='6'],['wrong module',f=>f.state.native_generation='3'],
  ['wrong image',f=>f.state.native_generation_sha256='d'.repeat(64)],['wrong allocation',f=>f.state.native_allocation='2'],
  ['wrong owner',f=>f.state.native_owner_token='replacement'],['no UI completion',f=>delete f.state.original_ui_completed_count],
  ['wrong UI handler',f=>f.state.original_ui_queued_request_id=f.state.original_ui_completed_request_id='provider-compute-5'],
  ['uncompleted UI',f=>f.state.original_ui_completed_request_id='ui-compute-4']
])test(`effect refuses ${label}`,async()=>{const f=await fixture();complete(f);mutate(f);await assert.rejects(f.owner.observe(context('original_effect')));});
test('after-open detects an extra computation or a changed resulting value',async()=>{
  const f=await fixture();complete(f);await f.owner.observe(context('original_effect'));f.state.native_value='30';
  await assert.rejects(f.owner.observe(context('after_open_effect')),/value|effect/i);
});
for(const [label,setup]of [
 ['stale actor',({callbacks,envelope,actor})=>callbacks.readActor=async()=>({...await envelope(actor,'old-actor'),observed_at_utc:new Date(Date.now()-9000).toISOString()})],
 ['replacement actor',({actor})=>actor.identity.process_birth='5678'],
 ['unverified installed APK',({actor})=>actor.installed_bytes_verified=false],
 ['no input grant',({callbacks,envelope})=>callbacks.readGrant=async()=>envelope({identity,exclusive_input_grant:false,binding_valid:true},'denied-grant')],
 ['raw hash mismatch',({callbacks,envelope,actor})=>callbacks.readActor=async()=>({...await envelope(actor,'bad-sha'),evidence_sha256:'0'.repeat(64)})],
 ['raw value mismatch',({callbacks,envelope,actor})=>callbacks.readActor=async()=>({...await envelope(actor,'bad-value'),value:{...actor,present:false}})]
])test(`new input refuses ${label} without invoking stop`,async()=>{
  const f=await fixture({setup});await assert.rejects(f.owner.observe(context('guard')));assert(!f.calls.some(c=>c[0]==='stop'));
});
test('current geometry change/tracking loss refuses new input; same-actor cleanup needs no grant or geometry renewal',async()=>{
  const f=await fixture();f.g.layout_revision++;f.g.tracking_valid=false;
  await assert.rejects(f.owner.observe(context('guard')),/geometry|tracking/i);
  assert.equal((await f.owner.observe(context('release_guard'))).layer_loaded,true);assert(!f.calls.some(c=>c[0]==='grant'));
});
test('actor replacement between status and return is refused',async()=>{
  const f=await fixture({setup:({callbacks,actor,envelope})=>callbacks.readStatus=async()=>{actor.identity.pid=72;return envelope({schema:'rusty.quest.hotload_observation.v1',request_id:'s',host_kind:'native-openxr',status:'pass',error:null,state:baseline,scene:{operator_layer_loaded:true,input_geometry:geometry}},'racing-status');}});
  await assert.rejects(f.owner.observe(context('guard')),/identity|actor/i);
});
test('actor replacement during grant read is refused before returning a ready receipt',async()=>{
  const f=await fixture({setup:({callbacks,actor,envelope})=>callbacks.readGrant=async()=>{actor.identity.pid=72;return envelope({identity,exclusive_input_grant:true,binding_valid:true},'racing-grant');}});
  await assert.rejects(f.owner.observe(context('guard')),/identity|actor/i);
});
test('immutable raw callback copy preserves exact source JSON bytes',async()=>{
  const f=await fixture();await f.owner.observe(context('guard'));
  const original=await readFile(join(f.root,'1-actor.json'));
  const files=await readdir(join(f.root,'owner')),raw=files.find(n=>n.endsWith('-readActor-raw.json'));
  assert(raw);assert.deepEqual(await readFile(join(f.root,'owner',raw)),original);
});
test('unresolved read is finite and never invokes mutation callbacks',async()=>{
  const f=await fixture({setup:({callbacks})=>callbacks.readStatus=()=>new Promise(()=>{})});
  await assert.rejects(f.owner.observe(context('guard')),/deadline|unknown/i);assert(!f.calls.some(c=>c[0]==='stop'));
});
test('a finite multi-call status read can complete beyond two seconds without granting mutation authority',async()=>{
  const f=await fixture({setup:({callbacks})=>{const read=callbacks.readStatus;callbacks.readStatus=async c=>{await new Promise(r=>setTimeout(r,2200));return read(c);};}});
  const receipt=await f.owner.observe(context('guard'));assert.equal(receipt.status,'ready');
  assert(!f.calls.some(c=>c[0]==='stop'));
});
test('retirement requires actual typed stop and terminal proof; observer never stops',async()=>{
  const f=await fixture();await f.owner.observe(context('release_guard'));assert(!f.calls.some(c=>c[0]==='stop'));
  const r=await f.owner.retireOriginal(context('retire_original_actor'));assert.equal(r.status,'retired');assert.equal(r.scope,'original_actor_retirement');
  assert.equal(r.original_layer_lifetime,'process-owned');assert.equal(r.replacement_followed,false);assert.equal(sha(await readFile(r.evidence_ref)),r.evidence_sha256);
  await assert.rejects(f.owner.retireOriginal(context('retire_original_actor')),/once|already|attempt/i);assert.equal(f.calls.filter(c=>c[0]==='stop').length,1);
});
test('replacement before retirement prevents package stop and is never followed',async()=>{
  const f=await fixture();f.actor.identity.session_id='10';await assert.rejects(f.owner.retireOriginal(context('retire_original_actor')));
  assert(!f.calls.some(c=>c[0]==='stop'));
});
test('lost stop remains unknown and is not replayed',async()=>{
  const f=await fixture({setup:({callbacks,calls})=>callbacks.stopOriginal=async()=>{calls.push(['stop','lost']);throw Error('QFM stop outcome unknown');}});
  await assert.rejects(f.owner.retireOriginal(context('retire_original_actor')),/unknown/);await assert.rejects(f.owner.retireOriginal(context('retire_original_actor')));
  assert.equal(f.calls.filter(c=>c[0]==='stop').length,1);
});
for(const [label,mutate]of [
  ['wrong provider schema',v=>v.schema='other.stop.v1'],['failed result',v=>v.succeeded=false],
  ['wrong command kind',v=>v.mutation.CommandKind='startPackage'],['pending stage',v=>v.mutation.Stage='pending'],
  ['no actual readback',v=>v.mutation.HeadsetReadback=false],['nonterminal',v=>v.mutation.IsTerminal=false],
  ['wrong package',v=>v.result.PackageName='io.example.other'],['wrong transport',v=>v.result.Serial='OTHER-TRANSPORT'],
  ['wrong mutation target',v=>v.mutation.Target='OTHER-TRANSPORT'],['stop command failure',v=>v.result.StopCommand.Succeeded=false],
  ['not quiescent',v=>v.result.Quiescence.IsQuiescent=false],['remaining process',v=>v.result.Quiescence.ProcessIds=[71]],
  ['foreground activity',v=>v.result.Quiescence.ForegroundComponents=['io.example.operator/.Activity']],
  ['top resumed activity',v=>v.result.Quiescence.TopResumedComponents=['io.example.operator/.Activity']],
  ['missing process evidence',v=>delete v.result.Quiescence.ProcessIds],['failure payload',v=>v.failure={message:'unknown'}]
])test(`actual QFM retirement refuses ${label} despite matching raw hashes`,async()=>{
  const f=await fixture({setup:({callbacks,envelope})=>{
    const stop=callbacks.stopOriginal;callbacks.stopOriginal=async c=>{
      const r=await stop(c),qfm=JSON.parse(await readFile(r.value.stop_receipt_ref,'utf8'));mutate(qfm);
      const raw=await envelope(qfm,'mutated-qfm');r.value.stop_receipt_ref=raw.evidence_ref;r.value.stop_receipt_sha256=raw.evidence_sha256;
      return envelope(r.value,'terminal-with-mutated-qfm');
    };
  }});
  await assert.rejects(f.owner.retireOriginal(context('retire_original_actor')));
});
test('QFM transport can bind the fresh actor selector without conflating canonical serial',async()=>{
  const f=await fixture({setup:({callbacks,envelope})=>{const stop=callbacks.stopOriginal;callbacks.stopOriginal=async c=>{const r=await stop(c);delete r.value.transport_selector;return envelope(r.value,'actor-transport-terminal');};}});
  assert.equal((await f.owner.retireOriginal(context('retire_original_actor'))).status,'retired');assert.notEqual(transport,identity.serial);
});
test('QFM transport can bind the explicit typed stop selector',async()=>{
  const f=await fixture({setup:({actor})=>delete actor.transport_selector});
  assert.equal((await f.owner.retireOriginal(context('retire_original_actor'))).status,'retired');
});
for(const mode of ['absent','conflicting'])test(`QFM retirement refuses ${mode} transport binding`,async()=>{
  const f=await fixture({setup:({callbacks,actor,envelope})=>{
    if(mode==='absent')delete actor.transport_selector;
    const stop=callbacks.stopOriginal;callbacks.stopOriginal=async c=>{const r=await stop(c);if(mode==='absent')delete r.value.transport_selector;else r.value.transport_selector='OTHER-TRANSPORT';return envelope(r.value,'bad-transport-terminal');};
  }});
  await assert.rejects(f.owner.retireOriginal(context('retire_original_actor')));
});
for(const [label,change]of [['missing stop receipt',v=>delete v.stop_receipt_ref],['process alive',v=>v.original_process_absent=false],['new boot',v=>v.same_boot=false],['replacement followed',v=>v.replacement_followed=true]])
test(`retirement refuses ${label}`,async()=>{
  const f=await fixture({setup:({callbacks,envelope})=>{const stop=callbacks.stopOriginal;callbacks.stopOriginal=async c=>{const r=await stop(c);change(r.value);return envelope(r.value,'changed-terminal');};}});
  await assert.rejects(f.owner.retireOriginal(context('retire_original_actor')));
});
