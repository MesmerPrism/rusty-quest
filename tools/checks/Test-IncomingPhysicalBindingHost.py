#!/usr/bin/env python3
"""Actual Java physical producer/phase owner -> actual Rust incoming/v2 guards.

Android/SDK/endpoint leaves and final require_uncommitted are modeled. No JNI,
socket, device, authority adoption or media execution is claimed.
"""
import sys
sys.dont_write_bytecode=True
import argparse,pathlib,json,hashlib,subprocess,os,shutil,importlib.util,re

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True);p.add_argument('--json-jar',required=True);p.add_argument('--android-jar',required=True);p.add_argument('--support-classes',required=True);p.add_argument('--java',default='java');p.add_argument('--javac',default='javac');a=p.parse_args()
 root=pathlib.Path(__file__).resolve().parents[2];out=pathlib.Path(a.output).resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
 app=root/'apps/spatial-camera-panel-android';native=app/'native-receipt/src/embedded_duplex';java=app/'app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
 spec=importlib.util.spec_from_file_location('owner_extraction',root/'tools/checks/Test-PreparedSdkHost.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);item=m.item
 template=app/'host-tests/embedded_duplex/IncomingPhysicalBindingHost.java';platform=(java/'EmbeddedDuplexPlatform.java').read_text();host=(java/'EmbeddedDuplexProcessHost.java').read_text();holder=(java/'EmbeddedDuplexPreparedStart.java').read_text();phase=(java/'EmbeddedDuplexIncomingFreshPhases.java').read_text();setup=(native/'fresh_pair_setup.rs').read_text();prepared=(native/'prepared_start.rs').read_text()
 names=['public String freshSetupPhysicalEligibilityJson(','private void requireIncomingPhysicalObservation(','public String freshSetupInitialPhysicalEligibilityJson(','public String freshSetupPhysicalEligibilityForIncomingJson(','public String freshSetupPreparationPhysicalEligibilityJson(','public void assertIncomingFreshSetupInitialAllowed(','public void stageIncomingCompletedFreshSetupSuccessor(','private boolean initialSuccessorAllowed(','public void bindIncomingFreshSetupPhase(','void requestIncomingFreshStop(','JSONObject preparedPeerPhysicalStatus(']
 source=template.read_text().replace('PRODUCTION_CALLBACKS','\n'.join(item(platform,n) for n in names)).replace('PRODUCTION_HOST',item(host,'static JSONObject freshPhysical(')).replace('PRODUCTION_STATUS',item(holder,'synchronized JSONObject status(')).replace('PRODUCTION_PHASES',item(phase,'final class EmbeddedDuplexIncomingFreshPhases'))
 source=source.replace('PRODUCTION_ACTOR','\n'.join(item(host,n) for n in ['static void requireIncomingFreshActor(','static JSONObject freshClosureIdentity(']))
 source=source.replace('PRODUCTION_EPOCH_FIELDS',item(platform,'private static final class IncomingFreshSuccessor')+'\nprivate IncomingFreshSuccessor incomingFreshSuccessor;')
 canonical=(java/'EmbeddedDuplexCanonicalJson.java').read_text()
 canonical='\n'.join(line for line in canonical.splitlines() if not line.startswith(('package ','import '))).replace('public final class','final class',1)
 source=source.replace('PRODUCTION_CANONICAL',canonical)
 package='package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;\n'
 # Keep the exact binary owner name and SourceFile for callback descriptor
 # witnesses. The wrapper's fields/platform leaves remain explicit host seams.
 platform_body=item(source,'class EmbeddedDuplexPlatform {')
 callback_file=out/'EmbeddedDuplexPlatform.java';callback_file.write_text(package+'import org.json.*;\n'+platform_body,encoding='utf-8')
 source=source.replace(platform_body,'')
 generated=out/'IncomingPhysicalBindingHost.java';generated.write_text(package+source,encoding='utf-8');checks=[]
 def ref(p):
  b=p.read_bytes();return dict(path=str(p),bytes=len(b),sha256=hashlib.sha256(b).hexdigest())
 def run(name,argv):
  with(out/(name+'.stdout')).open('xb') as x,(out/(name+'.stderr')).open('xb') as y:c=subprocess.run(argv,stdout=x,stderr=y)
  checks.append(dict(name=name,argv=argv,exit=c.returncode,stdout=ref(out/(name+'.stdout')),stderr=ref(out/(name+'.stderr'))));assert c.returncode==0,name
 classes=out/'classes';classes.mkdir();android=out/'android-classes';android.mkdir()
 run('real-android-api',[a.javac,'--release','17','-encoding','UTF-8','-cp',a.android_jar,'-d',str(android),str(generated),str(callback_file)])
 run('host-javac',[a.javac,'--release','17','-encoding','UTF-8','-cp',a.json_jar,'-d',str(classes),str(generated),str(callback_file)])
 cp=str(classes)+os.pathsep+a.json_jar
 binary='io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.'
 run('java-scope-controls',[a.java,'-cp',cp,binary+'IncomingPhysicalBindingHost'])
 # Whole production metadata owner against the actual media endpoint class and
 # real Android API; the small archive-store leaf is a production key validator.
 api=out/'api';api.mkdir();api_classes=api/'classes';api_classes.mkdir()
 store=api/'EmbeddedDuplexFreshHandoffStore.java'
 store.write_text(package+'import org.json.JSONObject; class EmbeddedDuplexFreshHandoffStore{'+item((java/'EmbeddedDuplexFreshHandoffStore.java').read_text(),'static void keys(')+'}')
 run('whole-phase-owner-android-api',[a.javac,'--release','17','-cp',a.android_jar+os.pathsep+a.support_classes,'-d',str(api_classes),str(java/'EmbeddedDuplexIncomingFreshPhases.java'),str(java/'EmbeddedDuplexCanonicalJson.java'),str(store)])
 javap=str(pathlib.Path(a.javac).with_name('javap.exe' if os.name=='nt' else 'javap'))
 run('phase-owner-javap',[javap,'-p','-s','-v','-classpath',str(api_classes),'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexIncomingFreshPhases'])
 run('callback-body-javap',[javap,'-p','-s','-v','-classpath',str(android),binary+'EmbeddedDuplexPlatform'])
 for mode in ['operator','null','initial','registered']:run('java-'+mode,[a.java,'-cp',cp,binary+'IncomingPhysicalBindingHost',mode])
 # Actual native bodies, including exact binding check and unchanged SDK v2 object/actor gate.
 rust=out/'rust';(rust/'src').mkdir(parents=True)
 (rust/'Cargo.toml').write_text('[package]\nname="incoming-physical-production-host"\nversion="0.0.0"\nedition="2021"\n[workspace]\n[dependencies]\nserde_json="1"\n')
 extracted='\n'.join(item(setup,n) for n in ['enum IncomingPhysicalScope','fn physical_observation(']);validator=item(prepared,'pub(super) fn validate_physical(')
 code=r'''use serde_json::{Value,json};use std::sync::{Mutex,atomic::{AtomicUsize,Ordering}};
struct Binding{generation:u64,record_sha256:String}struct Capability{generation:u64,binding:Binding}
struct Host{local_peer_id:String,remote_peer_id:String,capability:Capability,config_sha256:String,callbacks:Callbacks}
struct Callbacks{value:Value,calls:Mutex<Vec<String>>}
impl Callbacks{fn got(&self,name:&str)->Result<Value,String>{self.calls.lock().unwrap().push(name.into());Ok(self.value.clone())}
fn fresh_setup_physical_eligibility(&self)->Result<Value,String>{self.got("operator")}
fn fresh_setup_initial_physical(&self,_:&str,_:&str)->Result<Value,String>{self.got("initial")}
fn fresh_setup_registered_physical(&self,_:&str,_:&str)->Result<Value,String>{self.got("registered")}
fn fresh_setup_preparation_physical(&self,_:&str,_:Option<&str>,_:&str)->Result<Value,String>{self.got("preparation")}}
static SDK:AtomicUsize=AtomicUsize::new(0);
PRODUCTION
mod prepared{use super::*;fn require_uncommitted(_:&Host,_:&Value)->Result<(),String>{SDK.fetch_add(1,Ordering::SeqCst);Ok(())}
VALIDATOR
}
fn host(v:Value,operator:bool)->Host{Host{local_peer_id:if operator{"peer.a"}else{"peer.b"}.into(),remote_peer_id:if operator{"peer.b"}else{"peer.a"}.into(),capability:Capability{generation:9,binding:Binding{generation:7,record_sha256:"a".repeat(64)}},config_sha256:"b".repeat(64),callbacks:Callbacks{value:v,calls:Mutex::new(vec![])}}}
fn main(){let a:Vec<String>=std::env::args().collect();let values:Vec<Value>=a[1..].iter().map(|p|serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap()).collect();let native=json!({"local":{"state":"never_started"}});
let incoming=host(values[1].clone(),false);assert!(physical_observation(&incoming,"ceremony.first","ceremony.first",IncomingPhysicalScope::Initial).is_err());assert_eq!(SDK.load(Ordering::SeqCst),0);
for(index,scope,name)in[(2,IncomingPhysicalScope::Initial,"initial"),(3,IncomingPhysicalScope::Registered,"registered"),(3,IncomingPhysicalScope::Preparation{prior:None},"preparation")]{let h=host(values[index].clone(),false);let v=physical_observation(&h,"ceremony.first","ceremony.first",scope).unwrap();prepared::validate_physical(&h,&native,&v).unwrap();assert_eq!(&h.callbacks.calls.lock().unwrap()[..],&[name]);}
let h=host(values[0].clone(),true);let v=physical_observation(&h,"unchanged","unused",IncomingPhysicalScope::Registered).unwrap();prepared::validate_physical(&h,&native,&v).unwrap();assert_eq!(&h.callbacks.calls.lock().unwrap()[..],&["operator"]);
for change in ["native","kind","extra","null"]{let mut v=values[2].clone();match change{"native"=>v["original_binding"]["native_request_id"]=json!("ceremony.foreign"),"kind"=>v["original_binding"]["kind"]=json!("operator"),"extra"=>v["original_binding"]["EXTRA"]=json!(true),_=>v["original_binding"]=Value::Null};let h=host(v,false);assert!(physical_observation(&h,"ceremony.first","ceremony.first",IncomingPhysicalScope::Initial).is_err());}
let mut stale=values[2].clone();stale["native_executor_generation"]=json!(8);let h=host(stale,false);let v=physical_observation(&h,"ceremony.first","ceremony.first",IncomingPhysicalScope::Initial).unwrap();assert!(prepared::validate_physical(&h,&native,&v).is_err());assert_eq!(SDK.load(Ordering::SeqCst),4);println!("4 actual binding/role/native-v2 guard groups passed; final SDK leaf modeled");}
'''.replace('PRODUCTION',extracted).replace('VALIDATOR',validator)
 (rust/'src/main.rs').write_text(code,encoding='utf-8')
 run('native-binding-controls',['cargo','run','--offline','--quiet','--manifest-path',str(rust/'Cargo.toml'),'--target-dir',str(out/'cargo'),'--']+[str(out/('java-'+mode+'.stdout')) for mode in ['operator','null','initial','registered']])
 # Typecheck the exact new JNI adapter bodies against the released jni API.
 # No JVM is started and the capability leaf is modeled; Android JNI execution
 # remains an artifact/runtime check rather than a host claim.
 bridge=(native/'java_bridge.rs').read_text();jni=out/'jni';(jni/'src').mkdir(parents=True)
 (jni/'Cargo.toml').write_text('[package]\nname="incoming-physical-jni-typecheck"\nversion="0.0.0"\nedition="2021"\n[workspace]\n[dependencies]\njni="=0.21.1"\nserde_json="1"\n')
 methods='\n'.join(item(bridge,n) for n in ['pub(crate) fn stage_incoming_completed_fresh_setup_successor(','pub(crate) fn fresh_setup_initial_physical(','pub(crate) fn fresh_setup_registered_physical(','pub(crate) fn fresh_setup_preparation_physical(','fn incoming_physical(','fn attached(','fn checked_call<'])
 standalone=item(bridge[bridge.rindex('\nfn checked_call<'):],'fn checked_call<')
 jni_source='mod operation_diagnostics{pub fn throwable_detail(_: &mut jni::JNIEnv<\'_>,_: &jni::objects::JObject<\'_>)->Option<String>{None}pub fn failure(_: &str,_: &[u8]){}}\nmod bridge{use jni::{JNIEnv,objects::{JObject,JString,JValue,GlobalRef}};\nstruct Capability;impl Capability{fn require_live(&self)->Result<(),String>{Ok(())}}\nstruct JavaOwnerCallbacks{vm:jni::JavaVM,callback:GlobalRef,capability:Capability}\nimpl JavaOwnerCallbacks{'+methods+'}\n'+standalone+'}\n'
 (jni/'src/lib.rs').write_text(jni_source)
 run('jni-api-typecheck',['cargo','check','--offline','--quiet','--manifest-path',str(jni/'Cargo.toml'),'--target-dir',str(out/'jni-cargo')])
 # Execute the actual completed-successor state transition, including genuine
 # completed/distinct predecessor selection, callback order and reservation joins.
 successor=out/'successor';(successor/'src').mkdir(parents=True)
 (successor/'Cargo.toml').write_text('[package]\nname="fresh-pair-stop-epoch-native-host"\nversion="0.0.0"\nedition="2021"\n[workspace]\n[dependencies]\nserde_json="1"\n')
 successor_fixture=app/'host-tests/embedded_duplex/fresh_pair_stop_epoch.rs'
 code=successor_fixture.read_text().replace('PRODUCTION_STAGE',item(setup,'fn stage_completed_successor(')).replace('PRODUCTION_PHYSICAL_VALIDATOR',validator)
 (successor/'src/main.rs').write_text(code)
 run('native-completed-successor',['cargo','run','--offline','--quiet','--manifest-path',str(successor/'Cargo.toml'),'--target-dir',str(out/'cargo'),'--',str(out/'java-initial.stdout')])
 bridge_method=item(bridge,'pub(crate) fn stage_incoming_completed_fresh_setup_successor(')
 method=re.search(r'"(stageIncomingCompletedFreshSetupSuccessor)",\s*"([^"]+)"',bridge_method)
 assert method and method.group(2)=='(Ljava/lang/String;Ljava/lang/String;)V'
 witness=(out/'callback-body-javap.stdout').read_text();assert 'public void '+method.group(1)+'(java.lang.String, java.lang.String)' in witness and 'descriptor: '+method.group(2) in witness
 # Scope/identity parameters must be retained at every real-family callsite.
 prep=(native/'fresh_pair_preparation.rs').read_text();cont=(native/'fresh_pair_continuation.rs').read_text()
 assert setup.count('host.callbacks.fresh_setup_physical_eligibility()')==1
 assert 'fresh_setup_physical_eligibility()' not in prep+cont
 compact=lambda v:re.sub(r'\s+','',v)
 assert 'token.request,&a.phase_id,IncomingPhysicalScope::Registered' in compact(setup)
 assert 'id,&a.phase_id,IncomingPhysicalScope::Registered' in compact(setup)
 assert compact(setup).count('id,id,IncomingPhysicalScope::Initial')==2
 assert 'prior:prior.as_deref()' in compact(prep) and 'physical_phase,IncomingPhysicalScope::Registered' in compact(cont)
 refs=[template,successor_fixture,java/'EmbeddedDuplexPlatform.java',java/'EmbeddedDuplexProcessHost.java',java/'EmbeddedDuplexIncomingFreshPhases.java',java/'EmbeddedDuplexCanonicalJson.java',java/'EmbeddedDuplexFreshHandoffStore.java',java/'EmbeddedDuplexPreparedStart.java',native/'fresh_pair_setup.rs',native/'fresh_pair_preparation.rs',native/'fresh_pair_continuation.rs',native/'java_bridge.rs',native/'prepared_start.rs',pathlib.Path(__file__).resolve()]
 endpoint=pathlib.Path(a.support_classes)/'io/github/mesmerprism/rustyquest/media/OwnerDispatchTcpEndpoint'
 dependencies=[pathlib.Path(a.android_jar),pathlib.Path(a.json_jar),pathlib.Path(a.java),pathlib.Path(a.javac),pathlib.Path(str(endpoint)+'.class'),pathlib.Path(str(endpoint)+'$RequestSpan.class')]
 result=dict(passed=True,checks=checks,source_pins=[ref(p) for p in refs],dependency_pins=[ref(p) for p in dependencies],java_groups=10,native_groups=4,scope_callsite_groups=1,generated=[ref(generated),ref(callback_file),ref(rust/'src/main.rs'),ref(jni/'src/lib.rs')],compiled_api_witnesses=[ref(android/pathlib.Path(*binary.rstrip('.').split('.'))/'EmbeddedDuplexPlatform.class'),ref(api_classes/pathlib.Path(*binary.rstrip('.').split('.'))/'EmbeddedDuplexIncomingFreshPhases.class')],callback_witness_scope='Exact production method bodies in modeled host wrapper; whole production phase owner uses real media endpoint class. Both compile against real Android API. No whole Platform/Android JNI runtime proof.',limits=__doc__)
 (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(ref(out/'RESULT.json')))
if __name__=='__main__':main()
