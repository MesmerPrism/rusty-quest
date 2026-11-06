"""Exercise the production closed network parser; Android and transport effects are inert."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from test_experiment_gateway_dedupe import method


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--json-jar", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2] / "apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer"
    production = (root / "ExperimentSessionCommandGateway.java").read_text(encoding="utf-8")
    source = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
final class SystemClock {static long elapsedRealtime(){return 1100;}}
final class ExperimentSessionBleServer {static class CommandRequest {
 final int bias; final ExperimentSessionControlCapability.Fence sessionFence;JSONObject setupRequest;
 CommandRequest(String i,String o,String c,int b){this(i,o,c,b,null);}
 CommandRequest(String i,String o,String c,int b,ExperimentSessionControlCapability.Fence f){bias=b;sessionFence=f;}
 CommandRequest(String i,String o,String c,int b,ExperimentSessionControlCapability.Fence f,JSONObject p,JSONObject n){this(i,o,c,b,f);}
}}
public class GatewayBiasTest {
 long identity=11,epoch=7;ExperimentSessionControlCapability session=new ExperimentSessionControlCapability();PolarStopBleCapability polar=new PolarStopBleCapability();
 class Carrier{long currentGeneration(){return 9;}} Carrier carrier=new Carrier();
 __PARSE__
 static void check(boolean v){if(!v)throw new AssertionError("production gateway bias invariant");}
 void deny(JSONObject b)throws Exception{try{parse(b);}catch(SecurityException|IllegalArgumentException e){return;}throw new AssertionError("invalid bias command admitted");}
 public static void main(String[]a)throws Exception {
  GatewayBiasTest h=new GatewayBiasTest();
  JSONObject n=new JSONObject().put("phase","active").put("control_state","running").put("profile_identity_bound",true).put("audio_identity_bound",true).put("generation",7).put("runtime_epoch",7).put("session_key","utc-ns-00000000000000000042").put("condition_id","condition-a").put("non_audio_profile_sha256",String.join("",java.util.Collections.nCopies(64,"a"))).put("activation_binding_sha256",String.join("",java.util.Collections.nCopies(64,"b"))).put("breath_guidance",new JSONObject().put("identity_bound",true).put("bias_percent",50));
  JSONObject status=new JSONObject().put("g",7).put("p","RUNNING").put("pending",false).put("b",new org.json.JSONArray().put("pattern-ready").put("no-pattern"));
  JSONObject offer=h.session.offer(status,n,9,11,7,1000);
  JSONObject b=new JSONObject().put("v",2).put("id","aaaaaaaaaaaaaaaa").put("op","set-bias").put("s",offer.getString("s")).put("g",7).put("n","bbbbbbbbbbbbbbbb").put("b",100);
  ExperimentSessionBleServer.CommandRequest accepted=h.parse(b);check(accepted.bias==100&&accepted.sessionFence.operation.equals("set-bias"));
  for(Object bad:new Object[]{-1,101,1.5,true,"50"})h.deny(new JSONObject(b.toString()).put("b",bad));
  h.deny(new JSONObject(b.toString()).put("extra",1));h.deny(new JSONObject(b.toString()).put("mac","bad"));h.deny(new JSONObject(b.toString()).put("g",8));h.deny(new JSONObject(b.toString()).put("s","cccccccccccccccccccccccccccccccc"));
  JSONObject absent=new JSONObject(b.toString());absent.remove("b");h.deny(absent);
  h.deny(new JSONObject(b.toString()).put("op","pause"));
  System.out.println("PASS production gateway live-bias parser: exact typed percent/full native fence and missing/extra/MAC/range/type/generation/reference negatives; shared Wi-Fi/direct/relay payload");
 }
}
'''.replace("__PARSE__", method(production, "private ExperimentSessionBleServer.CommandRequest parse("))
    with tempfile.TemporaryDirectory(prefix="rq-gateway-bias-") as directory:
        out=Path(directory); fixture=out/"GatewayBiasTest.java";fixture.write_text(source,encoding="utf-8")
        sources=[root/name for name in ("ExperimentSessionBleProtocol.java","ExperimentSessionControlCapability.java","ExperimentSessionGatewayProtocol.java")]
        subprocess.run(["javac","--release","8","-encoding","UTF-8","-cp",str(args.json_jar),"-d",str(out),*map(str,sources),str(fixture)],check=True)
        subprocess.run(["java","-cp",str(out)+os.pathsep+str(args.json_jar),"io.github.mesmerprism.rustyquest.native_renderer.GatewayBiasTest"],check=True)

if __name__ == "__main__":
    main()
