// Observes the existing app owner. Device transport and authority stay with the caller.
import {mkdir,open,readFile,stat} from 'node:fs/promises';
import {isAbsolute,join} from 'node:path';
import {createHash} from 'node:crypto';

const identityFields=['package','apk_sha256','layer_fingerprint','serial','boot_id','pid','process_birth','instance_id','session_id'];
const moduleFields=['native_generation','native_generation_sha256','native_allocation','native_owner_token','native_session_id'];
const geometryFields=['host_kind','process_id','host_session_id','owner_token','panel_generation','layout_revision',
  'surface_width_pixels','surface_height_pixels','reference_space','center','right','up','width_meters','height_meters',
  'pose_revision','effective_pose_revision','widget'];
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const clone=value=>structuredClone(value);
const fail=message=>{throw Error(message);};
function canonical(value){return JSON.stringify(value,(_,v)=>v&&typeof v==='object'&&!Array.isArray(v)?Object.fromEntries(Object.keys(v).sort().map(k=>[k,v[k]])):v);}
function freeze(value){if(value&&typeof value==='object'){for(const v of Object.values(value))freeze(v);Object.freeze(value);}return value;}
function sameIdentity(expected,actual){for(const field of identityFields)if(expected[field]!==actual?.[field])fail(`Original actor identity differs: ${field}`);}
function fresh(timestamp){const age=Date.now()-Date.parse(timestamp);if(!Number.isFinite(age)||age<0||age>8000)fail('Fresh actual callback observation required');return Date.parse(timestamp);}
function unsigned(value,name){if(!(typeof value==='string'&&/^(0|[1-9][0-9]*)$/.test(value))&&!(Number.isSafeInteger(value)&&value>=0))fail(`Invalid unsigned ${name}`);const n=BigInt(value);if(n>18446744073709551615n)fail(`Overflow ${name}`);return n;}
function signed(value){if(typeof value!=='string'||!/^(-?[1-9][0-9]*|0)$/.test(value))fail('Invalid native value');const n=BigInt(value);if(n< -9223372036854775808n||n>9223372036854775807n)fail('Native value overflow');return value;}
function validateGeometry(g,id){
  const native=g?.host_kind==='native-openxr';
  if(g?.schema!=='rusty.quest.development_input_geometry.v1'||g.status!=='applied'||g.process_id!==id.pid||g.owner_token!==id.instance_id||
    typeof g.host_session_id!=='string'||!g.host_session_id||g.tracking_valid!==true||g.panel_visible!==true||
    !['native-openxr','spatial-sdk'].includes(g.host_kind)||g.evidence!==(native?'native-submitted-frame':'sdk-ecs-panel-transform'))fail('Current original actor geometry/tracking required');
  for(const key of ['panel_generation','layout_revision','surface_width_pixels','surface_height_pixels'])
    if(!Number.isSafeInteger(g[key])||g[key]<1)fail('Current geometry revision/surface required');
  for(const key of ['pose_revision','effective_pose_revision'])if(!Number.isSafeInteger(g[key])||g[key]<0)fail('Nonnegative actual geometry pose revision required');
  if(g.pose_revision!==g.effective_pose_revision||!['LOCAL','STAGE','LOCAL_FLOOR'].includes(g.reference_space)||
    (native&&(!Number.isSafeInteger(g.submitted_frame)||g.submitted_frame<1))||
    (!native&&(g.reference_space!=='LOCAL_FLOOR'||g.source_coordinate_system!=='sdk-left-handed-z-forward'||g.coordinate_conversion!=='reflect-z')))fail('Effective geometry pose/reference space required');
  for(const key of ['center','right','up'])if(!Array.isArray(g[key])||g[key].length!==3||!g[key].every(v=>Number.isFinite(v)&&Math.abs(v)<=1000))fail('Finite geometry axes required');
  if(Math.abs(Math.hypot(...g.right)-1)>.001||Math.abs(Math.hypot(...g.up)-1)>.001||Math.abs(g.right.reduce((sum,v,i)=>sum+v*g.up[i],0))>.001)fail('Unit orthogonal geometry axes required');
  for(const key of ['width_meters','height_meters'])if(!Number.isFinite(g[key])||g[key]<.02||g[key]>10)fail('Finite panel dimensions required');
  const widget=g.widget,b=widget?.bounds_pixels,c=widget?.clipped_bounds_pixels;
  if(widget?.id!=='compute'||widget.enabled!==true||widget.visible!==true||![b,c].every(v=>Array.isArray(v)&&v.length===4&&v.every(Number.isFinite)&&v[2]>v[0]&&v[3]>v[1])||
    c[0]<0||c[1]<0||c[2]>g.surface_width_pixels||c[3]>g.surface_height_pixels||c[0]<b[0]||c[1]<b[1]||c[2]>b[2]||c[3]>b[3])fail('Current visible original widget geometry required');
}
const stableGeometry=g=>canonical(Object.fromEntries(geometryFields.map(k=>[k,g[k]])));
async function bounded(call,ms){let timer;try{return await Promise.race([Promise.resolve().then(call),new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('Callback outcome unknown: deadline exceeded; do not replay')),ms);})]);}finally{clearTimeout(timer);}}

/**
 * All callbacks return {value,evidence_ref,evidence_sha256,observed_at_utc}.
 * evidence_ref is an absolute immutable JSON file containing value, or {value,...}.
 * readStatus.value is rusty.quest.hotload_observation.v1. readActor.value binds
 * {identity,present,installed_bytes_verified}; readGrant.value binds
 * {identity,exclusive_input_grant,binding_valid}. Only stopOriginal has effects.
 * Retirement also requires an explicit transport_selector on readActor.value
 * or stopOriginal.value, independently of the canonical identity.serial.
 */
export async function createDevelopmentHostOwner({identity,geometry,baseline,callbacks,evidenceDirectory,route}){
  const expected=freeze(clone(identity)),target=freeze(clone(geometry)),initial=freeze(clone(baseline));
  if(!expected||Object.keys(expected).sort().join()!==[...identityFields].sort().join()||!Number.isSafeInteger(expected.pid)||expected.pid<1||
    !identityFields.filter(k=>k!=='pid').every(k=>typeof expected[k]==='string'&&/^[A-Za-z0-9_.:-]{1,180}$/.test(expected[k]))||
    ![expected.apk_sha256,expected.layer_fingerprint].every(v=>/^[0-9a-f]{64}$/.test(v)))fail('Exact expected actor identity required');
  if(!isAbsolute(evidenceDirectory)||typeof route!=='string'||!route||route.length>180)fail('Explicit evidence directory and route required');
  validateGeometry(target,expected);
  if(initial?.native_state!=='ready'||(target.host_kind==='native-openxr'&&initial.native_owner_token!==expected.instance_id)||moduleFields.some(k=>typeof initial[k]!=='string'||!initial[k])||
    !/^[0-9a-f]{64}$/.test(initial.native_generation_sha256))fail('Initialized exact native module baseline required');
  const completed=unsigned(initial.native_completed_unsigned,'native completed');signed(initial.native_value);
  if(completed===18446744073709551615n)fail('Native completed counter cannot advance');
  const uiFields=['original_ui_queued_request_id','original_ui_completed_request_id','original_ui_completed_count'];
  const uiAvailable=uiFields.every(k=>Object.hasOwn(initial,k));
  if(uiFields.some(k=>Object.hasOwn(initial,k))&&!uiAvailable)fail('Partial original UI baseline');
  const uiCompleted=uiAvailable?unsigned(initial.original_ui_completed_count,'UI completed'):null;
  const cb={};for(const name of ['readActor','readStatus','readGrant','stopOriginal']){if(typeof callbacks?.[name]!=='function')fail(`Typed ${name} callback required`);cb[name]=callbacks[name];}freeze(cb);
  await mkdir(evidenceDirectory); // create-new: an old attempt is never overwritten or resumed.
  let fileSequence=0,callbackCount=0,rawBytes=0,correlation,retirementStarted=false,confirmedEffect,queue=Promise.resolve();
  async function persistBytes(kind,bytes){
    const path=join(evidenceDirectory,`${String(++fileSequence).padStart(4,'0')}-${kind}.json`);
    const file=await open(path,'wx');try{await file.writeFile(bytes);await file.sync();}finally{await file.close();}
    return {evidence_ref:path,evidence_sha256:hash(bytes)};
  }
  const persist=(kind,value)=>persistBytes(kind,Buffer.from(JSON.stringify(value,null,2)));
  function bind(context,phase){
    sameIdentity(expected,context?.identity);
    if(context.phase!==phase||typeof context.correlation_id!=='string'||!/^[A-Za-z0-9_.:-]{1,180}$/.test(context.correlation_id))fail('Exact owner phase/correlation required');
    if(correlation&&correlation!==context.correlation_id)fail('Owner belongs to one input attempt');correlation=context.correlation_id;
    if(!context.plan?.geometry||stableGeometry(context.plan.geometry)!==stableGeometry(target))fail('Selected original geometry changed');
    return freeze({...clone(context),identity:clone(expected),owner_route:route});
  }
  async function call(name,context){
    if(++callbackCount>128)fail('Finite callback budget exhausted');
    await persist(`${name}-intent`,{context});
    try{
      const envelope=await bounded(()=>cb[name](clone(context)),name==='stopOriginal'?7000:2000);
      if(!envelope||!isAbsolute(envelope.evidence_ref??'')||!/^[0-9a-f]{64}$/.test(envelope.evidence_sha256??''))fail('Actual raw callback evidence required');
      fresh(envelope.observed_at_utc);
      const metadata=await stat(envelope.evidence_ref);if(!metadata.isFile()||metadata.size>1024*1024)fail('Finite callback evidence file required');
      const bytes=await readFile(envelope.evidence_ref);rawBytes+=bytes.length;if(bytes.length>1024*1024||rawBytes>16*1024*1024)fail('Finite callback evidence budget exhausted');
      const rawCopy=await persistBytes(`${name}-raw`,bytes),raw=JSON.parse(bytes.toString('utf8'));
      const copy=await persist(`${name}-receipt`,{source_ref:envelope.evidence_ref,source_sha256:hash(bytes),source_observed_at_utc:envelope.observed_at_utc,raw:rawCopy});
      if(hash(bytes)!==envelope.evidence_sha256||canonical(raw?.value??raw)!==canonical(envelope.value))fail('Raw callback hash/value mismatch');
      return {value:clone(envelope.value),observed_at_utc:envelope.observed_at_utc,copy};
    }catch(error){await persist(`${name}-error`,{context,error:String(error),outcome:name==='stopOriginal'?'unknown':'refused'});throw error;}
  }
  function actor(receipt){sameIdentity(expected,receipt.value?.identity);if(receipt.value.present!==true||receipt.value.installed_bytes_verified!==true)fail('Fresh present original actor and installed APK required');}
  function currentOwner(v){
    if(v?.schema!=='rusty.quest.hotload_observation.v1'||v.host_kind!==target.host_kind||v.status!=='pass'||v.error!==null||v.scene?.operator_layer_loaded!==true)fail('Successful actual app status and loaded Operator layer required');
    const g=v.scene.input_geometry;
    if(g?.schema!=='rusty.quest.development_input_geometry.v1'||g.host_kind!==target.host_kind||g.process_id!==expected.pid||
      g.owner_token!==target.owner_token||g.host_session_id!==target.host_session_id)fail('Current app owner/host session differs from original actor');
    if(Object.hasOwn(v.state??{},'host_session_id')&&v.state.host_session_id!==target.host_session_id)fail('Status host session differs from original geometry');
  }
  function stableModule(state){for(const k of moduleFields)if(state?.[k]!==initial[k])fail(`Original module/allocation/owner differs: ${k}`);signed(state.native_value);}
  function baselineState(state){stableModule(state);if(unsigned(state.native_completed_unsigned,'native completed')!==completed||state.native_value!==initial.native_value)fail('Native baseline changed before input');
    if(uiAvailable&&uiFields.some(k=>String(state[k])!==String(initial[k])))fail('Original UI baseline changed before input');}
  function effect(state,afterOpen){
    stableModule(state);const count=unsigned(state.native_completed_unsigned,'native completed');
    if(count===completed&&!afterOpen){if(state.native_value!==initial.native_value)fail('Pending native value changed');return 'pending';}
    if(count!==completed+1n)fail('Expected exactly one native completed operation');
    if(!uiAvailable||uiFields.some(k=>!Object.hasOwn(state,k))||unsigned(state.original_ui_completed_count,'UI completed')!==uiCompleted+1n)fail('Exactly one original UI completion trace required');
    const request=state.original_ui_queued_request_id,pattern=target.host_kind==='native-openxr'?/^ui-compute-[1-9][0-9]*$/:/^ui-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
    if(typeof request!=='string'||!pattern.test(request)||request!==state.original_ui_completed_request_id||request===initial.original_ui_completed_request_id||request===initial.original_ui_queued_request_id)fail('New matching original UI request/completion required');
    const next={native_value:state.native_value,request};
    if(afterOpen&&(!confirmedEffect||canonical(next)!==canonical(confirmedEffect)))fail('After-open original effect/value changed or was not confirmed');
    if(confirmedEffect&&canonical(next)!==canonical(confirmedEffect))fail('Original effect changed');confirmedEffect=freeze(next);return 'confirmed';
  }
  async function receipt(kind,context,payload,sources){
    const observed_at_utc=new Date(Math.min(...sources.map(r=>fresh(r.observed_at_utc)))).toISOString();
    const result={identity:clone(expected),correlation_id:correlation,owner_route:route,observed_at_utc,...payload,capture_confirmed:false,rendered_hands_confirmed:false};
    const evidence=await persist(kind,{schema:'rusty.quest.development_host_owner.receipt.v1',phase:context.phase,...result,sources:sources.map(r=>r.copy)});
    fresh(observed_at_utc);return {...result,...evidence};
  }
  function serial(operation){const running=queue.then(operation);queue=running.catch(()=>{});return running;}
  return Object.freeze({route,
    observe(context){return serial(async()=>{
      if(!['guard','release_guard','original_effect','after_open_effect'].includes(context?.phase))fail('Closed read-only observer phase required');
      const c=bind(context,context.phase),before=await call('readActor',c);actor(before);
      const status=await call('readStatus',c),v=status.value;
      currentOwner(v);
      const sources=[before,status];
      let disposition='ready',grant=false;
      if(c.phase==='guard'){
        validateGeometry(v.scene.input_geometry,expected);
        if(stableGeometry(v.scene.input_geometry)!==stableGeometry(target)||(target.host_kind==='native-openxr'&&v.scene.input_geometry.submitted_frame<target.submitted_frame))fail('Current geometry changed');
        baselineState(v.state);const currentGrant=await call('readGrant',c);sources.push(currentGrant);sameIdentity(expected,currentGrant.value?.identity);
        if(currentGrant.value.exclusive_input_grant!==true||currentGrant.value.binding_valid!==true)fail('Fresh exclusive input grant unavailable');grant=true;
      }
      const after=await call('readActor',c);actor(after);sources.push(after);
      if(['original_effect','after_open_effect'].includes(c.phase))disposition=effect(v.state,c.phase==='after_open_effect');
      return receipt('observation',c,{status:disposition,installed_bytes_verified:true,layer_loaded:true,exclusive_input_grant:grant,
        geometry:clone(v.scene.input_geometry),native_value:v.state?.native_value,state:clone(v.state)},sources);
    });},
    retireOriginal(context){return serial(async()=>{
      const c=bind(context,'retire_original_actor');if(retirementStarted)fail('Original retirement may be attempted only once');
      const before=await call('readActor',c);actor(before);
      const status=await call('readStatus',c);currentOwner(status.value);
      const after=await call('readActor',c);actor(after);
      const actorTransports=[before.value.transport_selector,after.value.transport_selector].filter(v=>v!==undefined);
      if(actorTransports.length===2&&actorTransports[0]!==actorTransports[1])fail('Current original actor transport changed before stop');
      retirementStarted=true; // lost/unknown stop can never be replayed by this adapter.
      const stopped=await call('stopOriginal',c),v=stopped.value;sameIdentity(expected,v?.identity);
      if(v.operation!=='qfm-package-stop'||v.status!=='retired'||v.same_boot!==true||v.original_process_absent!==true||v.original_layer_lifetime!=='process-owned'||v.replacement_followed!==false||
        !isAbsolute(v.stop_receipt_ref??'')||!/^[0-9a-f]{64}$/.test(v.stop_receipt_sha256??''))fail('Actual typed original stop and terminal evidence required');
      const metadata=await stat(v.stop_receipt_ref);if(!metadata.isFile()||metadata.size>1024*1024)fail('Finite actual QFM stop receipt required');
      const stopBytes=await readFile(v.stop_receipt_ref);if(stopBytes.length>1024*1024||hash(stopBytes)!==v.stop_receipt_sha256)fail('Actual QFM stop receipt hash mismatch');
      const stop=await persistBytes('qfm-stop-receipt',stopBytes);
      const selectors=[...actorTransports,v.transport_selector].filter(value=>value!==undefined),selector=selectors[0];
      if(typeof selector!=='string'||!selector||selector.length>256||/[\s\0]/.test(selector)||selectors.some(value=>value!==selector))fail('Explicit consistent actual stop transport required');
      const qfm=JSON.parse(stopBytes.toString('utf8')),mutation=qfm?.mutation,result=qfm?.result,q=result?.Quiescence;
      if(qfm.schema!=='questionable.file_manager.apk_stop_result.v1'||qfm.succeeded!==true||qfm.failure!==null||
        mutation?.CommandKind!=='stopPackage'||mutation.Stage!=='confirmed'||mutation.HeadsetReadback!==true||mutation.IsTerminal!==true||mutation.Target!==selector||
        result?.PackageName!==expected.package||result.Serial!==selector||result.StopCommand?.Succeeded!==true||q?.IsQuiescent!==true||
        !['ProcessIds','ForegroundComponents','TopResumedComponents'].every(key=>Array.isArray(q?.[key])&&q[key].length===0))fail('Confirmed successful exact-package quiescent QFM stop receipt required');
      return receipt('retirement',c,{status:'retired',scope:'original_actor_retirement',same_boot:true,original_process_absent:true,
        original_layer_lifetime:'process-owned',replacement_followed:false,stop_receipt:stop},[before,status,after,stopped]);
    });}
  });
}
