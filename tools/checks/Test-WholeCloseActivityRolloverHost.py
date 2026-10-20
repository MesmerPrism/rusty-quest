import argparse,pathlib,subprocess,json,hashlib,os,re

def block(text,token):
    start=text.index(token);a=text.index("{",start);depth=1;i=a+1
    while depth:
        if text[i]=="{":depth+=1
        elif text[i]=="}":depth-=1
        i+=1
    return text[start:i]
def main():
    a=argparse.ArgumentParser();a.add_argument("--repo-root",required=True);a.add_argument("--output-root",required=True);a.add_argument("--java-home",required=True);a.add_argument("--json-jar",required=True);v=a.parse_args()
    root=pathlib.Path(v.repo_root).resolve();o=pathlib.Path(v.output_root).resolve();assert o.is_relative_to(root/"target") and not o.exists();o.mkdir(parents=True);(o/"classes").mkdir()
    b=root/"apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex"
    native_path=root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/peer_lifecycle.rs'
    native=native_path.read_text()
    projection=block(native,'fn append_fresh_lifecycle_fields(')
    cursor=block(native,'struct RetiredLifetimes {')
    producer=o/'native-projection';(producer/'src').mkdir(parents=True)
    (producer/'Cargo.toml').write_text('[package]\nname="actual-native-v4-projection-control"\nversion="0.0.0"\nedition="2021"\n[workspace]\n[dependencies]\nserde={version="1",features=["derive"]}\nserde_json="1"\n')
    rust='''use serde::Serialize;use serde_json::{Value,json};
#[derive(Default,Serialize)] CURSOR
struct State {last_failure_cause:Option<Value>,retired_lifetimes:RetiredLifetimes}
PROJECTION
fn main(){let args:Vec<String>=std::env::args().collect();let mut base:Value=serde_json::from_str(&std::fs::read_to_string(&args[1]).unwrap()).unwrap();let state=State{last_failure_cause:None,retired_lifetimes:RetiredLifetimes::default()};append_fresh_lifecycle_fields(&mut base,&state).unwrap();std::fs::write(&args[2],base.to_string()).unwrap();}
'''.replace('CURSOR',cursor).replace('PROJECTION',projection)
    (producer/'src/main.rs').write_text(rust)
    # These are explicitly modeled OLD v3 physical/owner observations. The two
    # v4 additions are emitted by the exact production formatter above.
    raw_base=re.search(r'let mut value = json!\(\{(.*?)\}\);',block(native,'fn receipt('),re.S).group(1)
    base={key:None for key in re.findall(r'"([^"\n]+)"\s*:',raw_base)}
    base.update({"$schema":"rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v3","action":"whole_app_close","config_sha256":"a"*64,"app_process_generation":3,"native_executor_generation":7,"observed_at_ms":100,"peer_physical_cleanup":"terminal","ongoing_media_policy":{"$schema":"rusty.manifold.peer.ongoing_media_policy.v2"},"current_lifetime_renewal_count":0,"current_owner_validation":{"$schema":"rusty.quest.concurrent.current_ongoing_owner_validation.v1","status":"unavailable","validated_at_ms":100,"reason":"current_owner_validation_unavailable"}})
    base.update({key:"terminal" for key in ['native_host_physical_cleanup','java_own_capture_cleanup','java_renderer_cleanup','java_control_cleanup','app_process_fence_cleanup','whole_app_physical_cleanup','status']})
    (o/'modeled-v3-base.json').write_text(json.dumps(base));v4=o/'actual-v4-projection.json'
    command=[os.environ.get('CARGO','cargo'),'run','--offline','--quiet','--manifest-path',str(producer/'Cargo.toml'),'--',str(o/'modeled-v3-base.json'),str(v4)]
    produced=subprocess.run(command,capture_output=True);(o/'native-projection.stdout').write_bytes(produced.stdout);(o/'native-projection.stderr').write_bytes(produced.stderr)
    assert produced.returncode==0,produced.stderr.decode(errors='replace')
    host=(b/"EmbeddedDuplexProcessHost.java").read_text();guard=block(host,"    boolean wholeCloseRolloverCurrent(")
    template=root/"tools/checks/fixtures/WholeCloseActivityRolloverHost.java";source=o/template.name;source.write_text(template.read_text().replace("PRODUCTION_CURRENT_GUARD",guard))
    paths=[b/name for name in ["EmbeddedDuplexWholeCloseRollover.java","EmbeddedDuplexFreshLifecycle.java","EmbeddedDuplexNativeOperationFailure.java","EmbeddedDuplexCanonicalJson.java"]]+[source];pins={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths};java=pathlib.Path(v.java_home)/"bin";jar=pathlib.Path(v.json_jar);results=[{"name":"actual-native-v4-projection","exit":produced.returncode}]
    for name,argv in [("javac",[str(java/"javac.exe"),"-encoding","UTF-8","-source","8","-target","8","-cp",str(jar),"-d",str(o/"classes"),*[str(p) for p in paths]]),("controls",[str(java/"java.exe"),"-cp",str(o/"classes")+";"+str(jar),"io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.WholeCloseActivityRolloverHost",str(v4)])]:
        p=subprocess.run(argv,capture_output=True);(o/(name+".stdout")).write_bytes(p.stdout);(o/(name+".stderr")).write_bytes(p.stderr);results.append({"name":name,"exit":p.returncode});assert p.returncode==0,p.stderr.decode(errors="replace")
    finish=block(host,"    private void finishWholeAppProof(")
    assert finish.count("EmbeddedDuplexWholeCloseRollover.localPeerTerminalProof(proof)")==1
    for token in ['snapshot.length == 160','snapshot[9] == 2L','snapshot[63] == 0L',
        'snapshot[156] == 0L','snapshot[157] == 0L','proof.getLong("app_process_generation") != processFence.generation()',
        'proof.getLong("native_executor_generation")','remoteOwnerClosureVerified(proof, nativeExecutorGeneration)',
        'resources.productResourcesTerminal()','display.detachAfterCleanup(','held.retireProcessCallbacksAsync()']:
        assert finish.index(token)<finish.index("afterVerifiedWholeProductCleanup(")
    producer=block(native,'fn receipt(')
    for token in ['let physically_stopped =','media_stop_effect(state).is_some_and','state.route_cleanup.is_some()',
        '"peer_physical_cleanup":if physically_stopped{"terminal"}else{"pending"}',
        'Some("rusty.manifold.peer.ongoing_media_policy.v2")',
        'value["$schema"] = json!("rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v3")',
        'value["ongoing_media_policy"] = json!(selected_policy)']:
        assert token in producer,token
    assert 'host.authority.selected_fresh_pair_setup_policy()?.is_some()' in producer
    assert 'append_fresh_lifecycle_fields(&mut value,state)?' in producer
    assert '"last_failure_cause"' not in producer and '"retired_lifetimes"' not in producer
    assert finish.index("afterVerifiedWholeProductCleanup(")<finish.index("EmbeddedDuplexWholeCloseRollover.verified(")<finish.index("phase.set(Phase.NEW)")<finish.index("activity.accept(rollover)")
    assert 'wholeCloseRollover = null' in block(host,"    long attachDisplay(EmbeddedDuplexDisplay next,")
    activity=(b.parent/"SpatialCameraPanelActivity.kt").read_text();callback=block(activity,"  private fun onEmbeddedDuplexWholeCloseVerified(")
    for token in ["embeddedDuplexRuntimeFuture !== completed","completed.isCompletedExceptionally","completed.isCancelled","proof.matchesRuntime(","wholeCloseRolloverCurrent(this, proof)"]:
        assert callback.index(token)<callback.index("embeddedDuplexRuntimeFuture = null")
    assert "runOnUiThread" in callback and "proof" not in block(activity,"  override fun onNewIntent(")
    assert pins=={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    result={"schema":"rusty.quest.whole_close_activity_rollover_host.v1","status":"passed","checks":results,"source_pins":pins,"process_host_sha256":hashlib.sha256((b/"EmbeddedDuplexProcessHost.java").read_bytes()).hexdigest(),"activity_sha256":hashlib.sha256((b.parent/"SpatialCameraPanelActivity.kt").read_bytes()).hexdigest(),"native_lifecycle_producer_sha256":hashlib.sha256((root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/peer_lifecycle.rs').read_bytes()).hexdigest(),"device_calls":0,"limits":"Actual production token/local-lifecycle/current-owner predicates with modeled native/platform/fence facts; native producer shape and existing physical/UI callback guard source joins. Actual Android entrypoint compilation/physical cleanup are separate."};(o/"RESULT.json").write_text(json.dumps(result,indent=2));print((o/"controls.stdout").read_text());print("Production callback order/guard source joins PASS")
if __name__=="__main__":main()
