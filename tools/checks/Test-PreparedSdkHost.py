#!/usr/bin/env python3
"""Production SDK coordinator/handler/codec/signer with paired in-memory callbacks.

Android resources, clock, process singleton and source-snapshot producer are
explicit host fixtures. No JNI, real SDK allocation, device or media authority.
"""
import sys
sys.dont_write_bytecode = True
import argparse, hashlib, json, pathlib, subprocess, os, shutil

def module_layout(root,out):
    native=root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex'
    text=(native/'runtime_host.rs').read_text()
    declaration=text[text.index('mod preparation_sdk_observation;'):][:len('mod preparation_sdk_observation;')]
    prefix='#[path = "preparation_sdk_observation.rs"]\n'
    if prefix+declaration in text:declaration=prefix+declaration
    for name,body in [('current',declaration),('missing-path',declaration.replace(prefix,''))]:
        case=out/name;folder=case/'embedded_duplex';folder.mkdir(parents=True,exist_ok=False)
        (folder/'runtime_host.rs').write_text(body,encoding='utf-8');(folder/'preparation_sdk_observation.rs').write_bytes((native/'preparation_sdk_observation.rs').read_bytes())
        (folder/'mod.rs').write_text('mod runtime_host;',encoding='utf-8')
        source=case/'main.rs';source.write_text('mod embedded_duplex; fn main(){}',encoding='utf-8')
        result=subprocess.run(['rustc','--edition','2021',str(source),'-o',str(case/'layout.exe')],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        (case/'compile.stdout').write_bytes(result.stdout);(case/'compile.stderr').write_bytes(result.stderr)
        if name=='current':assert result.returncode==0,'Actual runtime_host child-module layout failed'
        else:assert result.returncode!=0 and b'E0583' in result.stderr,'Missing sibling path must refuse'
    (out/'RESULT.json').write_text(json.dumps({'passed':True,'actual_module_declaration':declaration,'missing_path_refused':True}),encoding='utf-8')

def item(text, signature):
    start=text.index(signature); brace=text.index('{',start); depth=0; quoted=False; escape=False
    for end in range(brace,len(text)):
        char=text[end]
        if quoted:
            if escape: escape=False
            elif char=='\\': escape=True
            elif char=='"': quoted=False
        elif char=='"': quoted=True
        elif char=='{': depth+=1
        elif char=='}':
            depth-=1
            if depth==0:return text[start:end+1]
    raise AssertionError('Incomplete production item '+signature)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True);p.add_argument('--json-jar',required=True);p.add_argument('--baseline-archive',action='store_true');a=p.parse_args()
    root=pathlib.Path(__file__).resolve().parents[2];out=pathlib.Path(a.output).resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
    module_layout(root,out/'module-layout')
    native=root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex';codec=root/'crates/rusty-quest-media-stream-android/src/prepared_start.rs'
    fixture=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/prepared_sdk_integration.rs'
    sources=[native/'prepared_start.rs',native/'preparation_sdk_observation.rs',native/'identity_jni.rs',native/'peer_lifecycle.rs',native.parent/'prepared_peer_renderer.rs',native.parent/'prepared_renderer_gate.rs',codec,codec.with_name('prepared_start_tests.rs'),fixture,root/'Cargo.lock',pathlib.Path(__file__).resolve()]
    def pins():return [{'path':str(x),'sha256':hashlib.sha256(x.read_bytes()).hexdigest()} for x in sources]
    before=pins();text=(native/'prepared_start.rs').read_text();signer=(native/'identity_jni.rs').read_text()
    java=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    policy=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/PreparedIngressPolicyHost.java'
    sources.extend([policy,java/'EmbeddedDuplexPlatform.java',java/'EmbeddedDuplexPreparedStart.java']);before=pins()
    platform=(java/'EmbeddedDuplexPlatform.java').read_text();holder=(java/'EmbeddedDuplexPreparedStart.java').read_text()
    java_source=policy.read_text().replace('PRODUCTION_PLATFORM_METHODS','\n'.join(item(platform,name) for name in ['void requestIncomingPreparedStop(','public long incomingPreparedStartStopSequence(','public boolean incomingPreparedStartStopRequestedFor(','boolean preparedIncomingTasksJoined(','public String archiveCompletedPreparedPeerStart(']))
    java_source=java_source.replace('PRODUCTION_HOLDER_METHODS','\n'.join(item(holder,name) for name in ['synchronized boolean terminalForSuccessorIngress(','synchronized void retireArchivedMetadata(']))
    source=out/'PreparedIngressPolicyHost.java';source.write_text(java_source,encoding='utf-8');classes=out/'java-classes';classes.mkdir();java_cp=os.pathsep.join([str(classes),a.json_jar])
    for name,argv in [('java-policy-compile',['javac','--release','17','-cp',a.json_jar,'-d',str(classes),str(source)]),('java-policy-controls',['java','-cp',java_cp,'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.PreparedIngressPolicyHost'])]:
        r=subprocess.run(argv,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE);(out/(name+'.stdout')).write_bytes(r.stdout);(out/(name+'.stderr')).write_bytes(r.stderr);assert r.returncode==0,name
    names=['pub(super) struct Arena','pub(crate) struct PreparedRendererIdentity','pub(crate) fn renderer_identity(','fn current(','fn check_source(','fn hex<','fn signing(','fn shape(','fn encode(','fn decode(','fn view_hash(','fn remote_view(','fn exchange(','fn sdk_prepare(','pub(super) fn incoming_host(','pub(super) fn handle_frame(','pub(super) fn handle(','pub(super) fn status(','pub(super) fn held(','fn diagnostic_actor(','fn sdk_lane(','fn sdk_snapshot(','pub(super) fn diagnostic(','fn archive_prior(']
    extracted='\n\n'.join(item(text,name) for name in names)
    if a.baseline_archive:
        baseline=subprocess.check_output(['git','-C',str(root),'show','HEAD:'+str((native/'prepared_start.rs').relative_to(root)).replace('\\','/')]).decode('utf-8')
        original=item(baseline,'fn archive_prior(');extracted=extracted.replace(item(text,'fn archive_prior('),original)
        (out/'baseline-archive.rs').write_text(original,encoding='utf-8')
    (out/'production-items.rs').write_text(extracted,encoding='utf-8')
    (out/'identity.rs').write_text(signer+'\npub fn host_sign(seed:&[u8;32],b:&[u8])->Result<[u8;64],String>{sign_checked(seed,b).ok_or_else(||"host signer rejected".to_string())}\npub fn host_public(seed:&[u8;32])->[u8;32]{public_from_seed(seed)}\n',encoding='utf-8')
    (out/'Cargo.toml').write_text('''[package]
name="prepared-sdk-production-host"
version="0.0.0"
edition="2021"
[workspace]
[dependencies]
ed25519-dalek={version="=2.1.1",default-features=false,features=["std","zeroize"]}
serde={version="1",features=["derive"]}
serde_json="1"
sha2="0.10"
[lib]
path="lib.rs"
''',encoding='utf-8')
    lib='extern crate self as rusty_quest_media_stream_android;\n'
    lib+='extern crate self as rusty_quest_broker_authority;\npub fn packaged_json_sha256(raw:&str)->String{use sha2::Digest;format!("{:x}",sha2::Sha256::digest(raw.as_bytes()))}\n'
    lib+=f'#[path={json.dumps(codec.as_posix())}] mod codec; pub use codec::*;\n'
    lib+='#[path="identity.rs"] mod identity;\n'
    lib+=f'#[path={json.dumps((native/"preparation_sdk_observation.rs").as_posix())}] mod preparation_sdk_observation;\n'
    for name in ['preparation_native_guard','preparation_diagnostic','owner_transport_attempt_read']:
        lib+=f'#[path={json.dumps((native/(name+".rs")).as_posix())}] pub(crate) mod {name};\n'
    lib+=f'#[path={json.dumps((native/"pair_lifetime_policy.rs").as_posix())}] pub(crate) mod pair_lifetime_policy;\n'
    lib+=f'#[path={json.dumps((native.parent/"prepared_renderer_gate.rs").as_posix())}] mod prepared_renderer_gate;\n'
    lib+='mod embedded_duplex { pub(crate) use crate::{preparation_native_guard,pair_lifetime_policy}; pub(crate) use crate::prepared::renderer_identity as prepared_renderer_identity; }\n'
    renderer=(native.parent/'prepared_peer_renderer.rs').read_text()
    registry='\n'.join(item(renderer,name) for name in ['struct Registration','fn release_source(','pub(super) fn begin(','pub(super) fn terminal_for('])
    lib+=fixture.read_text().replace('PRODUCTION_ITEMS',extracted).replace('PRODUCTION_RENDERER',registry).replace('PRODUCTION_LIFETIME',item((native/'peer_lifecycle.rs').read_text(),'pub(super) fn prepared_terminal_lifetime(')).replace('JAVA_EXE',json.dumps(pathlib.Path(shutil.which('java')).as_posix())).replace('JAVA_POLICY_CP',json.dumps(java_cp))
    (out/'lib.rs').write_text(lib,encoding='utf-8')
    argv=['cargo','test','--offline','--manifest-path',str(out/'Cargo.toml'),'--target-dir',str(out/'cargo')]
    if a.baseline_archive:argv+=['exact_terminal_renderer_and_durable_ack_precede_native_metadata_replacement']
    result=subprocess.run(argv+['--','--test-threads=1'],cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    (out/'rust.stdout').write_bytes(result.stdout);(out/'rust.stderr').write_bytes(result.stderr)
    report={'passed':result.returncode==0,'exit':result.returncode,'source_pins':before,'source_unchanged':before==pins(),'production_items':names,'limits':__doc__}
    (out/'RESULT.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(result.stdout.decode(errors='replace'));print(json.dumps({'passed':report['passed'],'exit':result.returncode}));return result.returncode

if __name__=='__main__':sys.exit(main())
