"""Exact production handoff/cold-link parsers with warmed host crates; no Cargo graph/device."""
import argparse, hashlib, json, pathlib, subprocess, sys
from importlib.machinery import SourceFileLoader

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--root',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parents[2])
    ap.add_argument('--output',type=pathlib.Path,required=True)
    ap.add_argument('--dependency-dir',type=pathlib.Path,required=True)
    ap.add_argument('--captured-java-handoff',type=pathlib.Path)
    ap.add_argument('--native-owner-record',type=pathlib.Path,help='Read-only actual native owner evidence to pin and verify canonical bytes')
    a=ap.parse_args();root=a.root.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
    extractor=SourceFileLoader('original_host',str(root/'tools/checks/Test-EmbeddedDuplexOriginalsHost.py')).load_module().extract_method
    native=root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex';path=native/'process_fence.rs';source=path.read_text()
    signatures=['fn canonical_handoff_json(', 'pub(super) fn validate_operational_run_handoff(', 'fn validate_no_media_handoff(', 'impl AppBinding', 'fn value_fields(', 'fn parse_owner_record(', 'fn validate_cold_link(', 'pub(super) fn cold_pending_retirement_receipt(', 'fn verify_dead_native_owner(']
    bodies=[extractor(source,sig) for sig in signatures]
    structure=source[source.index('#[derive(serde::Deserialize)]'):source.index('/// Same integral, ASCII-keyed')]
    app=source[source.index('#[derive(Clone, Debug, Eq, PartialEq)]'):source.index('impl AppBinding')]
    constants='\n'.join(line for line in source.splitlines() if line.startswith('const ') and ('SCHEMA:' in line))
    prefix=r"""use std::fs;
fn digest(raw:&str)->String{use sha2::{Digest,Sha256};format!("{:x}",Sha256::digest(raw.as_bytes()))}
thread_local!{static CURRENT:std::cell::RefCell<Option<serde_json::Value>>=const{std::cell::RefCell::new(None)};}
fn current_native_process_identity()->Result<serde_json::Value,String>{Ok(CURRENT.with(|v|v.borrow().clone()).unwrap_or(serde_json::json!({"boot_id":"22222222-2222-2222-2222-222222222222","pid":100,"uid":10001,"start_ticks":9})))}
mod gate {use super::*;
"""
    main=r"""
fn main(){use std::io::Read;let mut raw=String::new();std::io::stdin().read_to_string(&mut raw).unwrap();let v:serde_json::Value=serde_json::from_str(&raw).unwrap();
let result=(||->Result<serde_json::Value,String>{let record=v["record"].as_str().ok_or("record")?;let counter=v["counter"].as_u64().ok_or("counter")?;
if v["kind"]=="handoff"{let (binding,pending)=gate::AppBinding::inspect(record)?;return Ok(serde_json::json!({"accepted_sha256":gate::validate_operational_run_handoff(v["handoff"].as_str().ok_or("handoff")?,&binding,pending,counter)?}));}
let link=v["link"].as_str().ok_or("link")?;let value:serde_json::Value=serde_json::from_str(link).map_err(|_|"link JSON")?;CURRENT.with(|c|*c.borrow_mut()=Some(value["current_process_identity"].clone()));
// The commit leaf remains modeled; validate the exact production owner-link predicate first.
let native_record=if value["native_owner"].as_object().is_some_and(|o|o.len()==3||o.contains_key("retirement")){let body=format!("{}\n{}\n",gate::NATIVE_OWNER_SCHEMA,gate::canonical_handoff_json(&value["native_owner"])?);format!("{body}{}\n",digest(&body))}else{return Err("native owner absent".into());};
// Legacy v1 owner links can describe a counter-only record; select its exact digest.
let legacy_body=format!("{}\n{counter}\n",gate::NATIVE_SCHEMA);let legacy=format!("{legacy_body}{}\n",digest(&legacy_body));
let subject=if value["native_record_sha256"]==digest(&native_record){native_record}else{legacy};
let (binding,_)=gate::AppBinding::inspect(record)?;gate::validate_cold_link(link,&binding,&subject,counter)?;
gate::cold_pending_retirement_receipt(record,link,counter)})();
match result{Ok(v)=>println!("{v}"),Err(e)=>{eprintln!("{e}");std::process::exit(1);}}}
"""
    # Keep all production visibility unchanged within a child module. Only expose to its parent harness.
    gate='\n'.join([constants,structure,app]+bodies)
    for name in ['NATIVE_OWNER_SCHEMA','NATIVE_SCHEMA']:
        gate=gate.replace('const '+name+':','pub(super) const '+name+':')
    for name in ['canonical_handoff_json','validate_cold_link']:
        gate=gate.replace('fn '+name+'(', 'pub(super) fn '+name+'(')
    tests=native/'process_fence_handoff_tests.rs'
    body=prefix+gate+'\n#[cfg(test)] mod focused {\n'+tests.read_text()+'\n}\n}\n'+main
    generated=out/'probe.rs';generated.write_text(body)
    dep=a.dependency_dir.resolve();extern=[]
    for name in ['serde','serde_json','sha2']:
        matches=list(dep.glob('lib'+name+'-*.rlib'))
        if len(matches)!=1:raise ValueError('Exact warmed '+name+' rlib required')
        extern+=['--extern',name+'='+str(matches[0])]
    dependency_pins={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for name in ['serde','serde_json','sha2'] for p in dep.glob('lib'+name+'-*.rlib')}
    common=['rustc','--edition','2024',str(generated),'-L','dependency='+str(dep)]+extern
    def run(label,cmd,env=None):
        with (out/(label+'.log')).open('wb') as f:subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,check=True,timeout=60,env=env)
    run('compile-tests',common+['--test','-o',str(out/'controls.exe')])
    import os
    env=os.environ.copy()
    if a.captured_java_handoff:env['CAPTURED_JAVA_HANDOFF']=str(a.captured_java_handoff.resolve())
    run('controls',[str(out/'controls.exe'),'--nocapture'],env)
    run('compile-abi',common+['-o',str(out/'probe.exe')])
    pins={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [path,tests,native/'runtime_host.rs',native/'native_fence_jni.rs']}
    result={'status':'passed','abi_binary':str(out/'probe.exe'),'native_pins':{k:v for k,v in pins.items() if k!='process_fence_handoff_tests.rs'},'focused_pins':pins,'generated_sha256':hashlib.sha256(generated.read_bytes()).hexdigest(),'scope':'Exact production serde DTO/JSON/canonical hashing/operational handoff/cold-link/owner parsers and receipt projector. serde, serde_json and sha2 from existing host cache. No Cargo graph or device execution.','modeled_leaves':['SHA256 provider uses real sha2 over the same UTF8 bytes','current kernel identity supplied from explicit Java host fixture','Windows /proc absence is host-only; no physical dead-process claim','Native commit, writer lock/counter mutation/JNI/Android leaves are not executed']}
    result['dependency_pins']=dependency_pins
    result['extracted_functions']=[{'signature':sig,'sha256':hashlib.sha256(body.encode()).hexdigest()} for sig,body in zip(signatures,bodies)]
    result['rustc']=subprocess.check_output(['rustc','--version']).decode().strip()
    if a.captured_java_handoff:result['captured_java_handoff']={'path':str(a.captured_java_handoff.resolve()),'sha256':hashlib.sha256(a.captured_java_handoff.read_bytes()).hexdigest()}
    if a.native_owner_record:
        raw=a.native_owner_record.read_bytes();fields=raw.decode().splitlines();owner=json.loads(fields[1]);body=fields[0]+'\n'+json.dumps(owner,sort_keys=True,separators=(',',':'))+'\n';expected=(body+hashlib.sha256(body.encode()).hexdigest()+'\n').encode()
        if expected!=raw:raise ValueError('Actual Native owner canonical reconstruction differs')
        result['actual_native_owner']={'path':str(a.native_owner_record.resolve()),'sha256':hashlib.sha256(raw).hexdigest(),'canonical_roundtrip_byte_exact':True,'counter':owner['counter']}
    if path.read_text()!=source:raise ValueError('Production source changed during host control')
    (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':main()
