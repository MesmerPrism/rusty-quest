import argparse, pathlib, subprocess, json, hashlib

def pin(p): return {"path":str(p),"sha256":hashlib.sha256(p.read_bytes()).hexdigest()}
def main():
    a=argparse.ArgumentParser();a.add_argument("--repo-root",required=True);a.add_argument("--output-root",required=True);a.add_argument("--java-home",required=True);v=a.parse_args()
    root=pathlib.Path(v.repo_root).resolve();output=pathlib.Path(v.output_root).resolve()
    assert output.is_relative_to(root/"target") and not output.exists(),"create-new target output required"
    output.mkdir(parents=True);classes=output/"classes";classes.mkdir()
    media=root/"crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media"
    paths=[media/"OwnerDispatchTcpEndpoint.java",media/"OwnerTransportAttempt.java",root/"tools/checks/fixtures/OwnerTransportDiagnosticHost.java",root/"apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexEnrollmentDraft.java"]
    pins=[pin(p) for p in paths];results=[]
    for name,argv in [("javac",[str(pathlib.Path(v.java_home)/"bin/javac.exe"),"-encoding","UTF-8","-source","8","-target","8","-d",str(classes),*[str(p) for p in paths]]),("controls",[str(pathlib.Path(v.java_home)/"bin/java.exe"),"-cp",str(classes),"OwnerTransportDiagnosticHost"])]:
        completed=subprocess.run(argv,capture_output=True,timeout=55);out=output/(name+".stdout");err=output/(name+".stderr");out.write_bytes(completed.stdout);err.write_bytes(completed.stderr)
        results.append({"name":name,"exit":completed.returncode,"stdout":pin(out),"stderr":pin(err)});assert completed.returncode==0,completed.stderr.decode(errors="replace")
    assert pins==[pin(p) for p in paths];print((output/"controls.stdout").read_text())
    (output/"RESULT.json").write_text(json.dumps({"schema":"rusty.quest.owner_transport_diagnostic_host.v1","status":"passed","source_pins":pins,"checks":results,"device_calls":0,"limits":["Real production Java endpoint on ephemeral loopback TCP, actual unchanged30s deadline","No Android/JNI or authenticated remote provider execution; EOF from a rejected handler remains indistinguishable from no reply"]},indent=2))
if __name__=="__main__":main()
