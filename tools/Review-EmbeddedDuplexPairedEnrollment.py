"""Read-only pair selection preflight. Never runs either supplied capsule."""
import argparse,json,pathlib,subprocess,hashlib
KEYS=["role_id","remote_public_key_hex","runtime_host_id","trusted_operator_id","adapter_id","media_revoker_id","admission_authority_id","max_token_ttl_ms"]
def selected(path):
    raw=path.read_bytes()
    if len(raw)>65536: raise ValueError("selected capsule bounds")
    capsule=json.loads(raw);args=capsule["arguments"]
    if not isinstance(args,list) or not all(isinstance(x,str) for x in args) or len(args)>80:
        raise ValueError("typed bounded capsule argv required")
    if args.count("--method")!=1 or args[args.index("--method")+1]!="review":
        raise ValueError("inert selection requires enrollment review capsule")
    values={}
    for i,arg in enumerate(args):
        if arg=="--extra":
            key,kind,value=args[i+1].split(":",2)
            if key not in KEYS or key in values or kind!=("l" if key=="max_token_ttl_ms" else "s"):
                raise ValueError("closed unique paired selection fields required")
            values[key]=value
    if set(values)!=set(KEYS): raise ValueError("complete selected drafts required")
    return [values[key] for key in KEYS],hashlib.sha256(raw).hexdigest()
def main():
    a=argparse.ArgumentParser();a.add_argument("--first-review-capsule",required=True);a.add_argument("--second-review-capsule",required=True)
    a.add_argument("--java-home",required=True);a.add_argument("--output-root",required=True);v=a.parse_args()
    root=pathlib.Path(__file__).resolve().parent.parent;output=pathlib.Path(v.output_root).resolve()
    if not output.is_relative_to(root/"target") or output.exists(): raise ValueError("create-new ignored target output required")
    first,first_sha=selected(pathlib.Path(v.first_review_capsule));second,second_sha=selected(pathlib.Path(v.second_review_capsule))
    output.mkdir(parents=True);classes=output/"classes";classes.mkdir()
    source=root/"apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexEnrollmentDraft.java"
    helper=root/"tools/paired-enrollment/PairedEnrollmentPreflight.java";home=pathlib.Path(v.java_home)
    compile=subprocess.run([str(home/"bin/javac.exe"),"-encoding","UTF-8","-source","8","-target","8","-d",str(classes),str(source),str(helper)],capture_output=True,timeout=30)
    (output/"javac.stdout").write_bytes(compile.stdout);(output/"javac.stderr").write_bytes(compile.stderr)
    if compile.returncode: raise RuntimeError("actual selected pair constructor compile failed")
    check=subprocess.run([str(home/"bin/java.exe"),"-cp",str(classes),"PairedEnrollmentPreflight",*first,*second],capture_output=True,timeout=30)
    (output/"preflight.stdout").write_bytes(check.stdout);(output/"preflight.stderr").write_bytes(check.stderr)
    result={"schema":"rusty.quest.paired_enrollment_selection_preflight.v1","status":"passed" if check.returncode==0 else "rejected","first_capsule_sha256":first_sha,"second_capsule_sha256":second_sha,"source_sha256":hashlib.sha256(source.read_bytes()).hexdigest(),"device_effects":0,"enrollment_authority_claimed":False,"limits":"Selected pair constructor only; actual independent reviews, enrollment confirmation and fresh runtime guards remain mandatory"}
    (output/"RESULT.json").write_text(json.dumps(result,indent=2));print(json.dumps(result));raise SystemExit(check.returncode)
if __name__=="__main__":main()
