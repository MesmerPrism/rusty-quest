// Optional developer input transport. The consumer supplies its existing owner
// readback route; this module never creates device/port/session authority.
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {readFile, mkdir, open} from 'node:fs/promises';
import {createHash, randomUUID} from 'node:crypto';
import {isAbsolute, join} from 'node:path';

const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const clone = value => structuredClone(value);
const fields = ['package','apk_sha256','layer_fingerprint','serial','boot_id','pid','process_birth','instance_id','session_id'];
const names = ['openxr_get_instance_info','openxr_get_session_info','openxr_get_frame_info',
  'openxr_set_controller_input','openxr_set_controller_pose','openxr_hand_gesture',
  'openxr_get_hand_state','openxr_release_input_devices','openxr_get_controller_pose'];
const fail = message => { throw Error(message); };
function same(expected, actual) {
  for (const key of fields) if (actual?.[key] !== expected[key]) fail(`Owner identity differs: ${key}`);
}
function keys(value, expected) {
  if (!value || Object.keys(value).sort().join() !== [...expected].sort().join()) fail('Closed object fields differ');
}
function vector(value, count) {
  if (!Array.isArray(value) || value.length !== count || !value.every(x => Number.isFinite(x) && Math.abs(x) <= 1000)) fail('Finite bounded pose required');
}
export function validateCapsule(input) {
  const c = clone(input);
  keys(c, ['schema','enabled','selection','proxy','origin','identity','plan','schema_pins','owner_route']);
  if (c.schema !== 'rusty.quest.meta_xr_input.capsule.v1' || c.enabled !== true) fail('Explicit input selection required');
  keys(c.proxy, ['path','sha256']);
  keys(c.selection, ['package','apk_sha256','layer_fingerprint','feature_lock_sha256','consumer','source_commit']);
  for (const v of [c.proxy.sha256,c.selection.apk_sha256,c.selection.layer_fingerprint,c.selection.feature_lock_sha256])
    if (!/^[0-9a-f]{64}$/.test(v)) fail('Exact SHA256 required');
  if (!isAbsolute(c.proxy.path) || !/^[0-9a-f]{40}$/.test(c.selection.source_commit) ||
      !['NativeOpenXr','SpatialSdk'].includes(c.selection.consumer)) fail('Pinned source/proxy/consumer required');
  const u = new URL(c.origin);
  if (u.protocol !== 'http:' || !['127.0.0.1','localhost','[::1]'].includes(u.hostname) ||
      !u.port || u.pathname !== '/' || u.search || u.hash || u.username || u.password) fail('Explicit loopback bare origin required');
  keys(c.identity, fields);
  for (const key of fields) if ((key!=='pid' && typeof c.identity[key]!=='string') || !/^[A-Za-z0-9_.:-]{1,180}$/.test(String(c.identity[key]))) fail(`Missing exact identity: ${key}`);
  if(!/^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$/.test(c.identity.package)) fail('Exact Android package required');
  if (!Number.isSafeInteger(c.identity.pid) || c.identity.pid <= 0 ||
      !/^[1-9][0-9]*$/.test(c.identity.session_id) || !Number.isSafeInteger(Number(c.identity.session_id))) fail('Exact supported numeric session/pid required');
  for (const key of ['package','apk_sha256','layer_fingerprint']) if (c.selection[key] !== c.identity[key]) fail('Selection/runtime mismatch');
  if (!/^[A-Za-z0-9_.:-]{1,180}$/.test(c.owner_route)) fail('Selected owner readback route required');
  keys(c.schema_pins, names);
  for (const pin of Object.values(c.schema_pins)) if (!/^[0-9a-f]{64}$/.test(pin)) fail('Reviewed live tool schemas required');
  keys(c.plan, ['kind','hand','base_space','position','orientation','hold_ms']);
  if (!['controller_select','hand_pinch'].includes(c.plan.kind) || !['left','right'].includes(c.plan.hand) ||
      !['local','stage','local_floor'].includes(c.plan.base_space) || !Number.isInteger(c.plan.hold_ms) || c.plan.hold_ms < 100 || c.plan.hold_ms > 500) fail('Closed finite input plan required');
  vector(c.plan.position,3); vector(c.plan.orientation,4);
  if (Math.abs(Math.hypot(...c.plan.orientation)-1) > 0.01) fail('Unit orientation required');
  return c;
}
// Canonical order is independent of tool registry serialization order.
export function schemaHash(value) {
  const canonical = v => Array.isArray(v) ? v.map(canonical) : v && typeof v === 'object'
    ? Object.fromEntries(Object.keys(v).sort().map(k => [k,canonical(v[k])])) : v;
  return hash(JSON.stringify(canonical(value)));
}
export const requiredTools = Object.freeze([...names]);

class Journal {
  constructor(directory,beforeWrite) { this.directory=directory; this.index=0; this.beforeWrite=beforeWrite; }
  async write(kind, payload) {
    this.beforeWrite?.(kind,payload);
    const file = join(this.directory, `${String(++this.index).padStart(4,'0')}-${kind}.json`);
    const handle = await open(file,'wx');
    try { await handle.writeFile(JSON.stringify({at_utc:new Date().toISOString(),kind,payload},null,2)); await handle.sync(); }
    finally { await handle.close(); }
    return file;
  }
}
class Proxy {
  constructor(child,journal,timeoutMs) {
    this.child=child; this.journal=journal; this.timeoutMs=timeoutMs; this.next=0; this.pending=new Map(); this.closed=false;
    this.records=[];this.writes=[];this.reportingErrors=[];
    this.exit = new Promise(resolve => child.once('close', (code,signal) => { this.closed=true; this.rejectAll(Error('Proxy closed; retain unknown requests')); resolve({code,signal}); }));
    child.once('error', error => this.rejectAll(error));
    this.stderr=''; child.stderr.on('data', chunk => {this.stderr=(this.stderr+chunk.toString()).slice(-16384);});
    let bytes=0;
    const lines=createInterface({input:child.stdout});
    lines.on('line', line => {
      bytes+=Buffer.byteLength(line);
      if (line.length>1024*1024 || bytes>16*1024*1024) { this.rejectAll(Error('Proxy output bound exceeded')); child.kill(); return; }
      let reply; try { reply=JSON.parse(line); } catch { this.rejectAll(Error('Invalid proxy JSON')); return; }
      const p=this.pending.get(reply.id);
      if (p) {
        clearTimeout(p.timer);p.record.reply=reply;
        if(p.record.state==='timed_out') {
          p.record.state='late_answered';
          this.writes.push(this.persist('late-response',reply));
        } else {p.record.state='answered';p.resolve(reply);}
      }
    });
  }
  rejectAll(error) { for(const p of this.pending.values()){clearTimeout(p.timer);if(p.record.state==='pending'){p.record.state='outcome_unknown';p.reject(error);}} }
  async persist(kind,payload,required=false) {
    try {await this.journal.write(kind,payload);}
    catch(error){this.reportingErrors.push({kind,message:String(error)});if(required)throw error;}
  }
  async rpc(method,params,compensation=false,onDispatch) {
    if(this.closed) fail('Proxy closed');
    const id=++this.next, request={jsonrpc:'2.0',id,method,params};
    // New effects require durable intent. A disk/reporting fault must never
    // block already-authorized original-target compensation.
    await this.persist('request-intent',request,!compensation);
    const record={request,state:'pending',reply:null};this.records.push(record);
    const response=new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{record.state='timed_out';reject(Error(`RPC ${id} timed out; retain ID; never replay`));},this.timeoutMs);
      this.pending.set(id,{resolve,reject,timer,record});
    });
    onDispatch?.(); // Intent is durable; bytes can now reach the original backend.
    this.child.stdin.write(JSON.stringify(request)+'\n', error=>{if(error)this.rejectAll(error);});
    const reply=await response;
    await this.persist('response',reply); // ACK stays known even if reporting fails.
    if(reply.jsonrpc!=='2.0' || reply.error || !reply.result) fail('RPC failed; original outcome retained');
    return reply.result;
  }
  async tool(name,args={},compensation=false,onDispatch) {
    if(!names.includes(name)) fail('Tool outside closed input surface');
    const result=await this.rpc('tools/call',{name,arguments:{...args,reason:'Bounded owner-selected input test; preserve original app handlers',sentiment:'neutral'}},compensation,onDispatch);
    if(result.isError || result.content?.length!==1 || result.content[0].type!=='text') fail('Tool rejected or ambiguous result');
    return JSON.parse(result.content[0].text);
  }
  async waitForClose(waitMs) {
    let timer;
    const result=await Promise.race([this.exit,new Promise(resolve=>{timer=setTimeout(()=>resolve(null),waitMs);})]);
    clearTimeout(timer);await Promise.all(this.writes);
    return result??{pending:true,pid:this.child.pid,state:'stdin-closed-awaiting-owned-child'};
  }
  async close(waitMs) {
    this.child.stdin.end();
    return this.waitForClose(waitMs);
  }
}
const bounded = async (operation,ms=5000) => {
  let timer; try { return await Promise.race([operation(),new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('Owner observation timed out')),ms);})]); }
  finally {clearTimeout(timer);}
};
const pause = ms => new Promise(resolve=>setTimeout(resolve,ms));

// owner.observe is an existing READ-ONLY owner route, never a semantic widget
// action or generic shell. Its receipts bind actual installed/process/layer state.
export async function runOperatorInput({capsule,owner,evidenceDirectory,timeoutMs=5000,ownerTimeoutMs=10000,closeWaitMs=10000,testOnlyLaunch,testOnlyJournalWrite}) {
  const c=validateCapsule(capsule);
  if(!owner || typeof owner.observe!=='function' || owner.route!==c.owner_route) fail('Exact owner observer required');
  if(!isAbsolute(evidenceDirectory) || !Number.isInteger(timeoutMs) || timeoutMs<50 || timeoutMs>10000 ||
     !Number.isInteger(ownerTimeoutMs) || ownerTimeoutMs<50 || ownerTimeoutMs>15000 ||
     !Number.isInteger(closeWaitMs) || closeWaitMs<50 || closeWaitMs>30000) fail('Bounded runtime parameters required');
  const proxyBytes=await readFile(c.proxy.path);
  if(hash(proxyBytes)!==c.proxy.sha256) fail('Selected proxy bytes changed');
  const result={schema:'rusty.quest.meta_xr_input.attempt.v1',evidence_tier:testOnlyLaunch?'host-protocol':'operator-protocol',
    correlation_id:randomUUID(),identity:clone(c.identity),
    dispatch_state:'not_sent',original_effect_state:'unknown',release_state:'not_required',terminal_state:'unknown',
    completed:false,evidence_persisted:true,rendered_hands_confirmed:false,capture_confirmed:false,errors:[],reporting_errors:[],receipts:{}};
  await mkdir(evidenceDirectory); // Fresh attempt, never overwrite/replay.
  const journal=new Journal(evidenceDirectory,testOnlyJournalWrite);
  async function report(kind,payload,required=false) {
    try {await journal.write(kind,payload);}
    catch(error){result.evidence_persisted=false;result.reporting_errors.push({kind,message:String(error)});if(required)throw error;}
  }
  await journal.write('attempt', {capsule:c,correlation_id:result.correlation_id,node:process.version,exec_path:process.execPath});
  async function observe(phase, extra={}) {
    const receipt=await bounded(()=>owner.observe({phase,identity:clone(c.identity),plan:clone(c.plan),correlation_id:result.correlation_id,...clone(extra)}),ownerTimeoutMs);
    same(c.identity,receipt?.identity);
    const age=Date.now()-Date.parse(receipt.observed_at_utc);
    if(receipt.correlation_id!==result.correlation_id || receipt.owner_route!==c.owner_route || !receipt.evidence_ref ||
       !/^[0-9a-f]{64}$/.test(receipt.evidence_sha256) || !Number.isFinite(age) || age<0 || age>8000) fail('Owner receipt lacks fresh exact evidence join');
    await report(`owner-${phase}`,receipt,!attempted); return receipt;
  }
  let proxy, attempted=false;
  const session_handle=Number(c.identity.session_id), hand=c.plan.hand;
  const args={session_handle,hand,base_space:c.plan.base_space};
  async function guard(phase='guard') {
    const receipt=await observe(phase);
    if(receipt.installed_bytes_verified!==true || receipt.layer_loaded!==true) fail('Original app/layer guard unavailable');
    // Compensation retains the original acquired input scope. It needs the
    // same actor/session, not renewed focus or a fresh new-input grant.
    if(phase!=='release_guard' && (receipt.status!=='ready' || receipt.focused!==true || receipt.exclusive_input_grant!==true)) fail('Fresh owner runtime/input grant unavailable');
    return receipt;
  }
  async function mutation(name,parameters) {
    await guard();
    const value=await proxy.tool(name,parameters,false,()=>{
      attempted=true;result.release_state='pending';result.dispatch_state='outcome_unknown';
    });
    if(value.success!==true) fail('Mutation acknowledgement absent');
    return value;
  }
  try {
    await guard('before_proxy');
    const child=testOnlyLaunch ? testOnlyLaunch(c.origin)
      : spawn(c.proxy.path,[c.origin],{windowsHide:true,shell:false,stdio:['pipe','pipe','pipe']});
    proxy=new Proxy(child,journal,timeoutMs);
    await proxy.rpc('initialize',{protocolVersion:'2024-11-05',capabilities:{},clientInfo:{name:'rusty-quest-finite-input',version:'1'}});
    child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n');
    const registry=await proxy.rpc('tools/list',{});
    for(const name of names) {
      const entries=registry.tools?.filter(t=>t.name===name)??[];
      if(entries.length!==1 || /\[OFFLINE\]/.test(entries[0].description??'') || schemaHash(entries[0].inputSchema)!==c.schema_pins[name]) fail(`Live reviewed tool schema differs: ${name}`);
    }
    const instance=await proxy.tool('openxr_get_instance_info',{});
    if(!instance.application?.name) fail('Live instance metadata unavailable');
    // Metadata has no authoritative instance handle; the owner binds it above.
    let first,last;
    for(let i=0;i<3;i++) {
      await guard();
      const session=await proxy.tool('openxr_get_session_info',{});
      const frame=await proxy.tool('openxr_get_frame_info',{});
      if(session.session?.handle!==c.identity.session_id || session.state?.name!=='XR_SESSION_STATE_FOCUSED' ||
         frame.session?.handle!==c.identity.session_id || frame.frame_state?.should_render!==true) fail('Original focused session unavailable');
      const time=frame.frame_state.predicted_display_time?.XrTime;
      if(!Number.isSafeInteger(time) || time<0 || (last!==undefined && time<last)) fail('XR frame clock invalid or regressed');
      first??=time;last=time;
      if(i<2) await pause(30);
    }
    if(last<=first) fail('Finite successful observations did not advance');
    result.receipts.readiness={first_xr_time:first,last_xr_time:last,clock_domain:'xr'};
    const initialHands=await proxy.tool('openxr_get_hand_state',{session_handle,hand:'both'});
    if(initialHands.conformance_automation_mode!=='physical' || !['left','right'].every(side=>initialHands.hands?.[side]?.synthetic_hand_override===false))
      fail('An existing synthetic input owner must be preserved');
    for(const side of ['left','right']) {
      const pose=await proxy.tool('openxr_get_controller_pose',{session_handle,hand:side,pose_type:'aim',base_space:'local'});
      if(pose.simulated!==false) fail('Existing controller simulation must be preserved');
    }
    if(c.plan.kind==='controller_select') {
      await mutation('openxr_set_controller_input',{session_handle,hand,component:'Trigger',value:0,auto_release:false});
      await mutation('openxr_set_controller_pose',{...args,pose_type:'aim',position:c.plan.position,orientation:c.plan.orientation,duration_seconds:0});
      const pose=await proxy.tool('openxr_get_controller_pose',{...args,pose_type:'aim'});
      if(pose.simulated!==true || pose.is_active!==true || pose.flags?.position_valid!==true || pose.flags?.orientation_valid!==true) fail('Controller pose delivery unavailable');
      const press=await mutation('openxr_set_controller_input',{session_handle,hand,component:'Trigger',value:1,auto_release:true,hold_duration:c.plan.hold_ms/1000});
      if(press.auto_release!==true) fail('Finite controller auto-release not acknowledged');
      result.dispatch_state='acknowledged'; // Only original app readback proves use.
    } else {
      const pose={...args,wrist_position:c.plan.position,wrist_orientation:c.plan.orientation,transition_seconds:0.1,auto_release:false};
      await mutation('openxr_hand_gesture',{...pose,gesture:'open'});
      const press=await mutation('openxr_hand_gesture',{...pose,gesture:'pinch',auto_release:true,hold_duration:c.plan.hold_ms/1000});
      if(press.edge_produced!==true || press.auto_release!==true || !Number.isSafeInteger(press.generation)) fail('Fresh finite pinch edge not confirmed');
      result.dispatch_state='acknowledged';
      const state=await proxy.tool('openxr_get_hand_state',{session_handle,hand},true);
      const observed=state.hands?.[hand];
      result.dispatch_state=observed?.delivered_generation===press.generation && observed.pinch?.index_pinching===true?'delivered':'acknowledged';
    }
    await pause(c.plan.hold_ms);
    const effect=await observe('original_effect');
    if(!['confirmed','pending','rejected'].includes(effect.status)) fail('Invalid original effect state');
    result.original_effect_state=effect.status;result.receipts.original_effect=effect;
  } catch(error) {result.errors.push(String(error));}
  finally {
    if(attempted && proxy) {
      // No replay of the select. Each cleanup operation targets ONLY original
      // session after fresh owner identity proof, even after an unknown press.
      let clean=true;
      const release= c.plan.kind==='controller_select'
        ? [['openxr_set_controller_input',{session_handle,hand,component:'Trigger',value:0,auto_release:false}]]
        : [['openxr_hand_gesture',{...args,gesture:'open',transition_seconds:0.1,auto_release:false}]];
      release.push(['openxr_release_input_devices',{session_handle}]);
      for(const [name,parameters] of release) {
        try {await guard('release_guard'); await report('release-intent',{name,parameters}); await proxy.tool(name,parameters,true);}
        catch(error){clean=false;result.errors.push(`release: ${error}`);}
      }
      try {
        await guard('release_guard');
        const release_readback={hands:await proxy.tool('openxr_get_hand_state',{session_handle,hand:'both'},true),controllers:{}};
        for(const side of ['left','right']) release_readback.controllers[side]=await proxy.tool('openxr_get_controller_pose',{session_handle,hand:side,pose_type:'aim',base_space:'local'},true);
        await report('release-readback',release_readback);
        const receipt=await observe('release',{release_readback});result.receipts.release=receipt;
        if(clean && receipt.status==='released' && receipt.buttons_down===false && receipt.overrides_active===false) result.release_state='confirmed';
        else result.release_state='outcome_unknown';
      } catch(error){result.release_state='outcome_unknown';result.errors.push(`release readback: ${error}`);}
      if(result.original_effect_state==='unknown') {
        try {
          const receipt=await observe('original_effect');
          if(!['confirmed','pending','rejected'].includes(receipt.status))fail('Invalid original effect reconciliation');
          result.original_effect_state=receipt.status;result.receipts.original_effect=receipt;
        } catch(error){result.errors.push(`original effect reconciliation: ${error}`);}
      }
      try {
        const receipt=await observe('terminal');result.receipts.terminal=receipt;
        if(!['confirmed','pending','unknown'].includes(receipt.status) || receipt.scope!=='input_operation') fail('Invalid finite input terminal state');
        result.terminal_state=receipt.status;
      } catch(error){result.errors.push(`terminal: ${error}`);}
    }
    if(proxy) {
      result.proxy_exit=await proxy.close(closeWaitMs);
      await report('proxy-close',{...result.proxy_exit,stderr:proxy.stderr});
      result.rpc_records=clone(proxy.records);result.reporting_errors.push(...proxy.reportingErrors);
      if(proxy.reportingErrors.length)result.evidence_persisted=false;
      if(result.proxy_exit.pending) Object.defineProperty(result,'waitForProxyRetirement',{enumerable:false,value:async(waitMs=10000)=>{
        if(!Number.isInteger(waitMs)||waitMs<50||waitMs>30000)fail('Bounded retirement wait required');
        const exit=await proxy.waitForClose(waitMs);
        result.proxy_exit=exit;result.rpc_records=clone(proxy.records);
        if(proxy.reportingErrors.length){result.evidence_persisted=false;result.reporting_errors.push(...proxy.reportingErrors);}
        await report('proxy-retirement',{exit,rpc_records:result.rpc_records});
        updateCompletion();
        await report('result-after-retirement',result);
        return exit;
      }});
    }
  }
  function updateCompletion() {
    result.completed=['acknowledged','delivered'].includes(result.dispatch_state) && result.original_effect_state==='confirmed' &&
      result.release_state==='confirmed' && result.terminal_state==='confirmed' && result.proxy_exit?.code===0 && result.errors.length===0;
  }
  updateCompletion();
  await report('result',result);
  return result;
}
