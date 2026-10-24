"""Opt-in composed whole-close test seams; never production runtime code.

Production Native cleanup/custody/retention/close predicates and Java physical
resource/holder/durable Original predicates run. JNI primitives, Android I/O,
codec allocations, Own and renderer hardware are explicit host models.
"""
def models(root,text,item):
    java=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    media=root/'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media'
    host=(java/'EmbeddedDuplexProcessHost.java').read_text()
    resources=(java/'EmbeddedDuplexResources.java').read_text()
    source=(media/'PackedStereoMediaSourceRuntime.java').read_text()
    text=text.replace('import org.json.JSONObject;','import org.json.JSONObject;\nimport io.github.mesmerprism.rustyquest.media.MediaRuntimeSnapshot;')
    text=text.replace(item(text,'boolean productResourcesTerminal(){'),item(resources,'boolean productResourcesTerminal() {'))
    text=text.replace(item(text,'boolean preparedSenderClosed(){'),item(resources,'boolean preparedSenderClosed(){'))
    text=text.replace(item(text,'boolean preparedDecoderClosed(){'),item(resources,'boolean preparedDecoderClosed(){'))
    text=text.replace(item(text,'boolean preparedDeliveryOpened(){'),item(resources,'boolean preparedDeliveryOpened(){'))
    text=text.replace('interface LifecycleHooks {','''
    EmbeddedDuplexReceiver incoming;FixturePipeline pipeline=new FixturePipeline();FixtureOwners outgoing=new FixtureOwners();
    void observeSdk(JSONObject ticket,JSONObject readback)throws Exception {
      String kind=ticket.getString("owner_kind");
      if(readback.getLong("executor_generation")!=generation() || ticket.getLong("executor_generation")!=generation())throw new IllegalStateException("SDK readback generation");
      if(!"sink".equals(kind))outgoing.observe(kind,readback);
      if("source".equals(kind)&&"started".equals(readback.getString("observed_state"))){pipeline.runtime.preparedDeliveryOpen=true;pipeline.runtime.packetCount=1;}
      pipeline.runtime.stopRequested=outgoing.allTerminal();pipeline.runtime.terminal=outgoing.allTerminal();
    }
    interface LifecycleHooks {''')
    text+='''
class FixtureProvider {
 MediaRuntimeSnapshot value=new MediaRuntimeSnapshot(1,0,"unprepared",false,"modeled physical allocation not entered");
 MediaRuntimeSnapshot snapshot(){return value;}
}
class FixtureOwners {
 final java.util.Map<String,FixtureProvider> rows=new java.util.HashMap<>();
 FixtureProvider provider(String kind){return rows.computeIfAbsent(kind,k->new FixtureProvider());}
 void observe(String kind,JSONObject actual)throws Exception {
  String state=actual.getString("observed_state");
  provider(kind).value=new MediaRuntimeSnapshot(actual.getLong("executor_generation"),actual.getLong("provider_state_revision"),state,
    "stopped".equals(state)||"cleaned".equals(state),"actual typed Native SDK executor readback",actual.getString("provider_handle_id"));
 }
 boolean allTerminal(){for(String kind:new String[]{"source","processor","route","socket","codec","cleanup"})if(!provider(kind).snapshot().terminal())return false;return true;}
}
class FixtureRuntime {
 boolean terminal,stopRequested,preparedDeliveryOpen;JSONObject preparedFormat=new JSONObject();long packetCount,headerWriteCount;
 FixtureTransport lanTransport;Thread sourceThread,acceptThread,cameraThread;
 Object serverSocket,socket,output,packetPump,retiredConnection,unresolvedConnection,encoder,encoderSurface,compositor,encoderWorker,leftCamera,rightCamera;
'''+item(source,'boolean isFullyStopped() {')+'\n'+item(source,'boolean resourcesReleased() {')+'''
}
class FixtureTransport {boolean terminal(){return true;}}
class FixturePipeline {
 FixtureRuntime runtime=new FixtureRuntime(),preparedRuntime=runtime;
'''+ '\n'.join(item(source,x).replace('Runtime held=','FixtureRuntime held=')for x in ('public void requireStopped() {','public boolean preparedSenderHeld() {','public boolean preparedDeliveryOpened(){','public boolean preparedDeliveryClosed() {'))+'''
}
'''
    declaration='static JSONObject fixtureArm;'
    methods='''
 EmbeddedDuplexOriginals.Record activeWholeNativeOriginal;
 static String fixtureWhole(Context context,EmbeddedDuplexResources resources,EmbeddedDuplexPreparedStart prepared,
   EmbeddedDuplexOriginals.Record original,JSONObject leaves,String exact)throws Exception {
  fixturePhysical(context,resources,prepared,leaves,null);
  EmbeddedDuplexProcessHost host=instance;host.phase.set(Phase.CLOSING);
  if(EmbeddedDuplexOriginals.capture()!=null||host.activeWholeNativeOriginal!=null)throw new IllegalStateException("whole context leaked");
  try(AutoCloseable scope=EmbeddedDuplexOriginals.enter(original)){
   requireCurrentOriginalStopActor(original);host.activeWholeNativeOriginal=original;
   try{return host.platform.persistFreshOwnerRetirement(exact);}finally{host.activeWholeNativeOriginal=null;}
  }
 }
'''+item(host,'static void requireCurrentWholeNativeClosure(')
    text=text.replace(declaration,declaration+methods)
    return text

def platform(root,item):
    path=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexPlatform.java'
    source=path.read_text()
    return '''
 String incomingPreparedRemote,incomingPreparedLocal;
 EmbeddedDuplexProcessFence.CallbackLease enterProcessCallback(){return new EmbeddedDuplexProcessFence.CallbackLease();}
 void requireProcessCallback(){if(EmbeddedDuplexOriginals.capture()==null)throw new IllegalStateException("actual Original callback absent");}
 boolean preparedIncomingTasksJoined(){return true;} // synchronous RPC exchanges are joined; no network workers modeled
'''+item(source,'void requirePreparedWholeClosure(')+'\n'+item(source,'public String persistFreshOwnerRetirement(')

def kotlin(root,text):
    # Preserve whole holder/receiver classes. Replace only the old Resources
    # fixture's terminal/dormant shortcuts with the production resource methods.
    start=text.index('  private class Resources(');end=text.index('  private class Media(',start)
    block=text[start:end]
    for line in ['    override fun preparedSenderClosed() = !preparedDeliveryOpened()\n',
                 '    override fun preparedDecoderClosed() = !preparedDeliveryOpened()\n',
                 '    override fun preparedDeliveryOpened() = media.receiver.preparedDeliveryOpened()\n',
                 '    override fun productResourcesTerminal() = media.terminal()\n']:
        assert line in block;block=block.replace(line,'')
    block=block.replace('    fun rendererSnapshot()', '''    var terminalReads=0
    public override fun productResourcesTerminal():Boolean { terminalReads++;return super.productResourcesTerminal() }
    init { incoming=media.receiver }
    fun rendererSnapshot()''')
    text=text[:start]+block+text[end:]
    text=text.replace('    var profileConfigured = false','    var ownTerminal = false\n    val wholeControls=org.json.JSONArray()\n    var profileConfigured = false')
    text=text.replace('    var receiverStopped = false','    var forceSinkLive = false\n    var receiverStopped = false')
    text=text.replace('state == "stopped", "modeled codec resource"','state == "stopped" && !forceSinkLive, "modeled codec resource"')
    text=text.replace('        prepared[originalId] = Prepared(holder, resources)', '''
        check(!resources.productResourcesTerminal())
        check(holder.status(holder.id()).getString("state")!="terminal")
        wholeControls.put("actual unstarted SDK holder not terminal")
        prepared[originalId] = Prepared(holder, resources)''')
    text=text.replace('        complete(operation(payload), receipt)','''
        if(step=="sdk_complete") {
          val held=prepared(payload);val status=held.holder.status(held.holder.id())
          check(!held.resources.productResourcesTerminal()&&status.getString("state")=="decoder_ready"&&status.getBoolean("delivery_gate_closed"))
          wholeControls.put("actual dormant held preparation remains ready/closed, not terminal")
        }
        complete(operation(payload), receipt)''',1)
    handlers='''
      "sdk_observe" -> {
        keys(payload,"ticket","readback")
        (physicalResources as Resources).observeSdk(payload.getJSONObject("ticket"),payload.getJSONObject("readback"))
        JSONObject().put("observed",true)
      }
      "whole_probe" -> {
        val held=prepared(payload);held.resources.terminalReads=0;val status=held.holder.status(held.holder.id());val reads=held.resources.terminalReads
        JSONObject().put("product_terminal",held.resources.productResourcesTerminal()).put("registry",status)
          .put("opened_history",held.resources.preparedDeliveryOpened()).put("status_terminal_reads",reads).put("controls",wholeControls)
      }
      "whole_negative_controls" -> {
        val held=prepared(payload);val resources=held.resources
        check(resources.productResourcesTerminal())
        for(kind in arrayOf("source","processor","route","socket","codec","cleanup")) {
          val provider=resources.outgoing.provider(kind);val prior=provider.value
          provider.value=MediaRuntimeSnapshot(prior.generation(),prior.revision(),"modeled_still_live",false,"negative physical leaf",prior.providerHandleId())
          try {check(!resources.productResourcesTerminal());check(held.holder.status(held.holder.id()).getString("state")!="terminal")}
          finally {provider.value=prior}
          wholeControls.put("one nonterminal SDK owner refuses: $kind")
        }
        resources.pipeline.runtime.encoder=Any()
        try {check(!resources.productResourcesTerminal());check(held.holder.status(held.holder.id()).getString("state")!="terminal")}
        finally {resources.pipeline.runtime.encoder=null}
        wholeControls.put("unreleased pipeline resource refuses")
        media.forceSinkLive=true
        try {check(!resources.productResourcesTerminal());check(held.holder.status(held.holder.id()).getString("state")!="terminal")}
        finally {media.forceSinkLive=false}
        wholeControls.put("nonterminal sink refuses")
        check(resources.productResourcesTerminal());JSONObject().put("controls",wholeControls)
      }
      "whole_own_terminal" -> { keys(payload);ownTerminal=true;JSONObject().put("set",true) }
      "whole_post_native" -> {
        keys(payload,"receipt");val proof=payload.getJSONObject("receipt")
        check(proof.getString("whole_app_physical_cleanup")=="pending"&&proof.getString("status")=="pending")
        check(EmbeddedDuplexWholeCloseRollover.localPeerTerminalProof(proof))
        var refusal:String?=null
        try {
          EmbeddedDuplexWholeCloseRollover.verified(proof,"app-process.${actor.getLong("app_process_generation")}.modeled-fence",
            actor.getLong("app_process_generation"),actor.getLong("native_executor_generation"),1L,
            actor.getString("config_sha256"),"e".repeat(64))
        } catch(failure:Exception) {refusal=failure.javaClass.name+":"+failure.message}
        check(refusal!=null) {"Native graph release must not mint Activity rollover"}
        val wrapped=RenewalLifecycleBoundary.whole(proof.toString(),actor)
        check(wrapped.getString("action")=="whole_app_close"&&same(wrapped.getJSONObject("peer_lifecycle"),proof))
        JSONObject().put("local_peer_terminal_proof",true).put("rollover_refused",true).put("refusal",refusal)
          .put("actual_Concurrent_serializer_preserves_pending",true).put("wrapper",wrapped)
          .put("identity_renderer_ARM_primitives_modeled",true).put("whole_Original_completed_or_ACKed",false).put("Local_selected",false)
      }
      "cleanup_primitive" -> {
        val method=payload.getString("method")
        when(method){
          "nativeProductResourcesTerminal" -> JSONObject().put("value",physicalResources.productResourcesTerminal())
          "currentForApplication" -> JSONObject().put("value","modeled-owned-Own-instance")
          "physicalCleanupState" -> JSONObject().put("value",if(ownTerminal)"terminal" else "pending")
          "nativeReadConcurrentStereoQualification" -> {
            val words=LongArray(160);words[0]=1;words[1]=160
            words[9]=if(media.rendererReleased)2 else 1;words[63]=if(media.rendererReleased)0 else 1
            JSONObject().put("value",org.json.JSONArray(words))
          }
          else -> error("unconfigured JNI cleanup primitive $method")
        }
      }
      "whole_persist" -> {
        keys(payload,"original_id","exact_body");val operation=operation(payload)
        check(operation.operation=="whole_app_close")
        JSONObject(EmbeddedDuplexProcessHost.fixtureWhole(context,physicalResources,physicalHolder,
          operation.record,physicalLeaves(),payload.getString("exact_body")))
      }
'''
    text=text.replace('      "sdk_status" -> {',handlers+'      "sdk_status" -> {')
    return text

def serializer():
    return '''
 @JvmStatic fun whole(raw:String,actor:JSONObject):JSONObject {
  validate(raw,actor) // Establish declared modeled ARM/native snapshot inputs and execute real v4 parser.
  return JSONObject(lifecycle("whole_app_close",challenge!!,processEpoch!!,runtimeConfig!!,featureLock!!,installedApk!!,raw))
 }
'''

def native(out,item):
    path=out/'embedded_duplex/runtime_host.rs';text=path.read_text()
    old=item(text,"fn prepared_route() ->")
    text=text.replace(old,'''fn prepared_route()->&'static Mutex<Option<(String,PackagedDuplexRoute)>>{
 static ROUTES:OnceLock<[Mutex<Option<(String,PackagedDuplexRoute)>>;2]>=OnceLock::new();
 &ROUTES.get_or_init(||[Mutex::new(None),Mutex::new(None)])[fixture::active()]
}''')
    old=item(text,'pub(crate) fn capability()')
    text=text.replace(old,'''static CONSTRUCTION_CAPS:OnceLock<[Mutex<Option<Arc<NativeCapability>>>;2]>=OnceLock::new();
pub(crate) fn capability()->Result<Arc<NativeCapability>,String>{
 if let Some(h)=process().lock().map_err(|_|"fixture capability gate")?.host.as_ref(){return Ok(h.capability.clone())}
 CONSTRUCTION_CAPS.get_or_init(||[Mutex::new(None),Mutex::new(None)])[active()].lock().map_err(|_|"fixture construction capability")?.clone().ok_or_else(||"fixture native capability absent".into())
}''')
    old='let (host, initialized) = on(peer,||fixture_build_host(config,bootstrap,route,capability,callbacks))?;'
    new='''let (host, initialized) = on(peer,||{
 *CONSTRUCTION_CAPS.get_or_init(||[Mutex::new(None),Mutex::new(None)])[peer].lock().unwrap()=Some(capability.clone());
 stage_packaged_route(sha.clone(),route.clone())?;
 fixture_build_host(config,bootstrap,route,capability,callbacks)
})?;'''
    assert old in text;text=text.replace(old,new)
    old='on(peer,||process().lock().unwrap().host=Some(host));'
    assert old in text;text=text.replace(old,old+'\n        *CONSTRUCTION_CAPS.get().unwrap()[peer].lock().unwrap()=None;')
    old='"start"|"renew"|"stop"|"status"=>{'
    assert old in text;text=text.replace(old,'"start"|"renew"|"stop"|"status"|"whole"|"complete_whole"=>{')
    old='peer_lifecycle::operate(&mut env,match operation{"start"=>1,"renew"=>2,"stop"=>3,_=>5})?'
    replacement='''if operation=="complete_whole"{
 let h=host_for_retained_whole_cleanup()?;
 let expected=h.config_sha256.clone();let executor=h.capability.generation;let app=h.capability.binding.generation;drop(h);
 peer_lifecycle::fixture_complete_whole(&mut env,&expected,executor as i64,app as i64)?.ok_or("whole completion pending")?
}else{peer_lifecycle::operate(&mut env,match operation{"start"=>1,"renew"=>2,"stop"=>3,"whole"=>6,_=>5})?}'''
    assert old in text;text=text.replace(old,replacement)
    text=text.replace('if operation=="snapshot"{','''if operation=="whole_state"{
 let p=process().lock().map_err(|_|"whole observed state")?;
 return Ok(json!({"host_present":p.host.is_some(),"prepared_present":p.prepared_peer_start.is_some(),"closing":p.closing,"route_present":prepared_route().lock().unwrap().is_some()}))
}
if operation=="snapshot"{''')
    needle='    let readback_json=serde_json::to_string(&readback).map_err(safe_decode)?;'
    assert needle in text;text=text.replace(needle,'    java(peer,"sdk_observe",json!({"ticket":ticket,"readback":readback}))?;\n'+needle)
    # Production terminal_for joins the retained renderer registration, not a
    # new preparation admission lookup (which correctly rejects closing).
    old=item(text,'pub(crate) fn renderer(')
    new=old.replace('    let identity=prepared_renderer_identity(id)?;','''    static IDENTITIES:OnceLock<Mutex<BTreeMap<String,Value>>>=OnceLock::new();
    let identity=if terminal {
      IDENTITIES.get_or_init(Default::default).lock().map_err(|_|"fixture renderer identity store")?.get(id).cloned().ok_or("fixture actual renderer registration absent")?
    }else{
      let i=prepared_renderer_identity(id)?;
      let v=json!({"app_process_generation":i.app_process_generation,"native_executor_generation":i.native_executor_generation,
        "app_record_sha256":i.app_record_sha256,"config_sha256":i.config_sha256,"request_sha256":i.request_sha256,"resource_target_sha256":i.resource_target_sha256});
      IDENTITIES.get_or_init(Default::default).lock().map_err(|_|"fixture renderer identity store")?.insert(id.into(),v.clone());v
    };''')
    for field in ('app_process_generation','native_executor_generation','app_record_sha256','config_sha256','request_sha256','resource_target_sha256'):
        new=new.replace('identity.'+field,'identity["'+field+'"]')
    text=text.replace(old,new)
    path.write_text(text,encoding='utf8')
    path=out/'embedded_duplex/peer_lifecycle.rs';text=path.read_text();old=item(text,'fn actual_java_cleanup(')
    # Only JNI types/calls are rebound. The production cleanup predicate body,
    # including SDK/Own/160-word gates, remains literal after these substitutions.
    adapted=old.replace('use jni::objects::JLongArray;','use crate::fixture_cleanup::{JLongArray,JString};\n    let mut env=crate::fixture_cleanup::Env;')
    text=text.replace(old,adapted)+'''
pub(super) fn fixture_complete_whole(env:&mut JNIEnv<'_>,expected:&str,executor:i64,app:i64)->Result<Option<String>,String>{complete_retained_whole_close(env,expected,executor,app)}
'''
    path.write_text(text,encoding='utf8')

JNI_PRIMITIVES=r'''
mod fixture_cleanup {
 use serde_json::Value;
 #[derive(Clone)]pub struct Object(Value);impl Object{pub fn is_null(&self)->bool{self.0.is_null()}}
 pub struct JLongArray(Object);impl From<Object> for JLongArray{fn from(v:Object)->Self{Self(v)}}
 pub struct JString(Object);impl From<Object> for JString{fn from(v:Object)->Self{Self(v)}}
 pub struct Returned(Value);impl Returned{pub fn z(self)->Result<bool,()>{self.0.as_bool().ok_or(())}pub fn l(self)->Result<Object,()>{Ok(Object(self.0))}}
 pub struct Env;
 impl Env {
  fn invoke(&mut self,name:&str)->Result<Returned,()>{let result=crate::embedded_duplex::runtime_host::fixture::java(crate::embedded_duplex::runtime_host::fixture::active(),"cleanup_primitive",serde_json::json!({"method":name})).map_err(|_|())?;Ok(Returned(result["value"].clone()))}
  pub fn call_static_method(&mut self,_class:&str,name:&str,_signature:&str,_args:&[()])->Result<Returned,()>{self.invoke(name)}
  pub fn call_method(&mut self,_object:&Object,name:&str,_signature:&str,_args:&[()])->Result<Returned,()>{self.invoke(name)}
  pub fn get_string(&mut self,value:&JString)->Result<String,()>{value.0.0.as_str().map(str::to_owned).ok_or(())}
  pub fn get_array_length(&mut self,value:&JLongArray)->Result<i32,()>{value.0.0.as_array().map(|v|v.len()as i32).ok_or(())}
  pub fn get_long_array_region(&mut self,value:&JLongArray,start:i32,output:&mut[i64])->Result<(),()>{let a=value.0.0.as_array().ok_or(())?;for(i,x)in output.iter_mut().enumerate(){*x=a[start as usize+i].as_i64().ok_or(())?}Ok(())}
 }
}
'''
