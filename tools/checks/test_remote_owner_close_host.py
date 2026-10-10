"""Focused current-receiver replay and actual Java whole-close barrier controls.

Uses production Rust and extracted production Java methods. Platform effects
are modeled; this host check does not qualify Android cleanup or a device.
"""
import argparse
import json
from pathlib import Path
import subprocess


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--repo-root', type=Path, required=True)
    p.add_argument('--supplier-quest-root', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--cargo', required=True)
    p.add_argument('--javac', required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--json-jar', required=True)
    a = p.parse_args()
    root, out = a.repo_root.resolve(), a.output.resolve()
    out.mkdir(parents=True, exist_ok=False)

    def run(label, argv):
        result = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        (out / (label + '.stdout')).write_bytes(result.stdout)
        (out / (label + '.stderr')).write_bytes(result.stderr)
        (out / (label + '.result.json')).write_text(json.dumps({'argv': argv, 'exit_code': result.returncode}), encoding='utf-8')
        if result.returncode:
            raise RuntimeError(label + ' failed; retained stdout/stderr/result')

    rust = out / 'rust'
    (rust / 'src').mkdir(parents=True)
    module = root / 'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/remote_owner_close.rs'
    (rust / 'src/lib.rs').write_text('#[path=' + json.dumps(module.as_posix()) + ']\nmod remote_owner_close;\n', encoding='utf-8')
    dependencies = '\n'.join(name + '={path=' + json.dumps((a.supplier_quest_root.resolve() / 'crates' / name).as_posix()) + '}' for name in ['rusty-quest-media-stream-android', 'rusty-quest-media-stream', 'rusty-quest-broker-authority'])
    (rust / 'Cargo.toml').write_text('[package]\nname="remote-owner-close-host"\nversion="0.0.0"\nedition="2021"\n[workspace]\n[dependencies]\ned25519-dalek={version="=2.1.1",default-features=false,features=["std","zeroize"]}\nserde_json="1"\n' + dependencies + '\n', encoding='utf-8')
    run('rust', [a.cargo, 'test', '--offline', '--manifest-path', str(rust / 'Cargo.toml'), '--target-dir', str(out / 'cargo'), '--', '--test-threads=1'])

    source = (root / 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexProcessHost.java').read_text(encoding='utf-8')
    start = source.index('    private void finishWholeAppProof(')
    end = source.index('    private String checkpointSnapshot()', start)
    actual = source[start:end]
    java = out / 'java'
    java.mkdir()
    stereo = java / 'StereoBankControls.java'
    stereo.write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel;
public final class StereoBankControls {
 public static final StereoBankControls INSTANCE=new StereoBankControls();
 public long[] value=new long[160]; public long[] concurrentQualification(){return value;}
}
''', encoding='utf-8')
    harness = java / 'RemoteCloseHarness.java'
    harness.write_text(HARNESS_PREFIX + actual + HARNESS_SUFFIX, encoding='utf-8')
    run('javac', [a.javac, '--release', '17', '-encoding', 'UTF-8', '-cp', a.json_jar, '-d', str(java), str(stereo), str(harness)])
    import os
    run('java', [a.java, '-cp', str(java) + os.pathsep + a.json_jar, 'RemoteCloseHarness'])
    (out / 'RESULT.json').write_text(json.dumps({'status': 'pass', 'rust_controls': 8, 'java_controls': 13, 'android_cleanup_qualified': False, 'device_effects': False}), encoding='utf-8')
    print('Remote receiver replay 8 and actual Java completion 13 controls PASS; host model only.')


HARNESS_PREFIX = '''import org.json.*;
import java.util.concurrent.atomic.AtomicReference;
public final class RemoteCloseHarness {
 static final class EmbeddedDuplexPlatform { boolean retired,closed; void closeControlAfterProductCleanup(){closed=true;} void retireProcessCallbacks(){retired=true;} }
 static final class Resources { boolean terminal=true; boolean productResourcesTerminal(){return terminal;} }
 static final class Display { void detachAfterCleanup(long generation,Runnable action){action.run();} }
 static final class EmbeddedDuplexProcessFence { boolean cleared; long generation(){return 3;} static String digest(String text){return "fixture";} void afterVerifiedWholeProductCleanup(String c,String e,String d){cleared=true;} }
 static final class EmbeddedDuplexStartJournal { EmbeddedDuplexStartJournal(Object context){} String read(){return null;} void persist(String text){} }
 static final class Intent { boolean cleared; void afterVerifiedCleanup(){cleared=true;} }
 enum Phase {CLOSING,NEW}
 final Object applicationContext=new Object(), attachmentGate=new Object();
 EmbeddedDuplexPlatform platform=new EmbeddedDuplexPlatform(); Resources resources=new Resources(); Display display=new Display();
 EmbeddedDuplexProcessFence processFence=new EmbeddedDuplexProcessFence(); Intent startIntent=new Intent();
 long nativeExecutorGeneration=7,ownNoMediaExecutorGeneration=7,attachmentGeneration=2; boolean displayDetaching=true;
 String runtimeConfigSha256="config",nativeAppRecordSha256="record",enrollmentRecordSha256="enrollment"; Object ownNoMediaStopTarget=new Object();
 AtomicReference<Phase> phase=new AtomicReference<>(Phase.CLOSING);
 void noMediaWholeProof(String sha){throw new AssertionError("wrong route");}
 String checkpointSnapshot(){return null;} String evidenceSnapshot(){return null;}
'''

HARNESS_SUFFIX = '''
 static JSONObject proof(){return new JSONObject().put("$schema","rusty.quest.embedded_duplex.remote_owner_cleanup.v1")
 .put("action","whole_app_close").put("config_sha256","config").put("app_process_generation",3).put("native_executor_generation",7)
 .put("java_own_capture_cleanup","terminal").put("native_host_physical_cleanup","terminal").put("peer_physical_cleanup","not_locally_started")
 .put("remote_receiver_terminal_history",new JSONObject().put("$schema","rusty.quest.embedded_duplex.remote_receiver_terminal_history.v1")
 .put("executor_generation",7).put("physical_cleanup_claimed",false).put("verified_response_count",2).put("receiver_stop_receipt_ids",new JSONArray().put("receipt.stop")));}
 static void renderer(){long[] v=new long[160];v[0]=1;v[1]=160;v[9]=2;io.github.mesmerprism.rustyquest.spatial_camera_panel.StereoBankControls.INSTANCE.value=v;}
 interface Mutate {void change(RemoteCloseHarness h,JSONObject p);}
 static void denied(String label,Mutate m)throws Exception {renderer();RemoteCloseHarness h=new RemoteCloseHarness();JSONObject p=proof();m.change(h,p);
 try{h.finishWholeAppProof(p,"config",false);}catch(Exception expected){}
 if(h.processFence.cleared||h.startIntent.cleared||h.phase.get()!=Phase.CLOSING||h.platform==null)throw new AssertionError(label+" falsely cleared owner");}
 public static void main(String[] args)throws Exception {
 renderer();RemoteCloseHarness h=new RemoteCloseHarness();JSONObject p=proof();EmbeddedDuplexPlatform original=h.platform;
 h.finishWholeAppProof(p,"config",false);
 if(!h.processFence.cleared||!h.startIntent.cleared||!original.closed||!original.retired||h.phase.get()!=Phase.NEW||!"terminal".equals(p.getString("whole_app_physical_cleanup")))throw new AssertionError("complete barriers");
 denied("Own pending",(x,y)->y.put("java_own_capture_cleanup","pending"));
 denied("native pending",(x,y)->y.put("native_host_physical_cleanup","pending"));
 denied("renderer pending",(x,y)->io.github.mesmerprism.rustyquest.spatial_camera_panel.StereoBankControls.INSTANCE.value[9]=1);
 denied("resources pending",(x,y)->x.resources.terminal=false);
 denied("config drift",(x,y)->y.put("config_sha256","other"));
 denied("app generation drift",(x,y)->y.put("app_process_generation",4));
 denied("native generation drift",(x,y)->y.put("native_executor_generation",8));
 denied("history generation drift",(x,y)->y.getJSONObject("remote_receiver_terminal_history").put("executor_generation",8));
 denied("physical claim",(x,y)->y.getJSONObject("remote_receiver_terminal_history").put("physical_cleanup_claimed",true));
 denied("duplicate receipts",(x,y)->y.getJSONObject("remote_receiver_terminal_history").put("verified_response_count",4).getJSONArray("receiver_stop_receipt_ids").put("receipt.stop"));
 denied("missing history",(x,y)->y.remove("remote_receiver_terminal_history"));
 denied("local proof promotion",(x,y)->y.put("peer_physical_cleanup","terminal"));
 System.out.println("Actual finishWholeAppProof: 13 controls PASS; platform model, no Android effects.");
 }
}
'''

if __name__ == '__main__':
    main()
