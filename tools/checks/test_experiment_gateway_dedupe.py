"""Exercise extracted production owner admission/dedupe with inert handler callbacks."""
import argparse
from pathlib import Path
import subprocess
import tempfile


def method(source, anchor):
    start = source.index(anchor)
    opening = source.index("{", start)
    depth = 0
    for end in range(opening, len(source)):
        depth += (source[end] == "{") - (source[end] == "}")
        if not depth:
            return source[start:end + 1]
    raise AssertionError("unclosed production method")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--json-jar", type=Path, required=True)
    args = parser.parse_args()
    package = "io/github/mesmerprism/rustyquest/native_renderer"
    root = args.repo / "apps/native-renderer-android/panel-modules/breath-composition/src/main/java" / package
    source = (root / "ExperimentSessionCommandGateway.java").read_text(encoding="utf-8")
    mode_source = (root / "ExperimentControlMode.java").read_text(encoding="utf-8")
    harness = '''package io.github.mesmerprism.rustyquest.native_renderer;
import java.util.*;import org.json.JSONObject;
final class ExperimentSessionBleServer {static final class CommandRequest{String id;CommandRequest(String i){id=i;}}}
final class ExperimentControlMode {static final int CHOOSE=0,DIRECT=1,REMOTE=2;__MODE_SNAPSHOT__}
final class GatewayDedupeHarness {
 Object protocol=new Object();long identity=1,epoch=7;int dispatches;
 ExperimentSessionCommandJournal journal=new ExperimentSessionCommandJournal();
 class LegacyNativeMode {ExperimentControlMode.Snapshot readSnapshot(){return new ExperimentControlMode.Snapshot(9);}}
 LegacyNativeMode controlMode=new LegacyNativeMode();
 class Carrier {boolean live=true;boolean acceptsNativeOwner(long i,long e){return live&&i==identity&&e==epoch;}
  JSONObject ownerReceipt(){return new JSONObject();}
  void updateReceipt(String id,String state,String detail){try{journal.observe(new JSONObject().put("id",id).put("state",state).put("detail",detail));}catch(Exception e){throw new RuntimeException(e);}}}
 Carrier carrier=new Carrier();
 class Delegate {void dispatch(ExperimentSessionBleServer.CommandRequest r){dispatches++;carrier.updateReceipt(r.id,"pending","real handler callback stand-in");}}
 Delegate delegate=new Delegate();
 static void requireMain(){}
 JSONObject snapshot(){return new JSONObject();}
 JSONObject monitorSnapshot(JSONObject body)throws Exception{return ExperimentSessionGatewayProtocol.monitorQuery(body);}
 ExperimentSessionBleServer.CommandRequest parse(JSONObject body)throws Exception{return new ExperimentSessionBleServer.CommandRequest(body.getString("id"));}
 __OWNER__
 static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
 public static void main(String[]args)throws Exception{
  GatewayDedupeHarness h=new GatewayDedupeHarness();String id="1111111111111111";
  check(h.ownerExchange("breath-monitor",new JSONObject().put("run","").put("after",0)).getString("operation").equals("breath-monitor")&&h.dispatches==0,"read-only monitor cannot dispatch experiment effects");
  JSONObject command=new JSONObject().put("id",id).put("op","save-next");
  JSONObject first=h.ownerExchange("command",command);check(first.getString("state").equals("pending")&&h.dispatches==1,"original pending receipt");
  h.ownerExchange("command",new JSONObject().put("op","save-next").put("id",id));check(h.dispatches==1,"object order cannot replay effects");
  h.carrier.updateReceipt(id,"confirmed","durable owner callback");
  check(h.ownerExchange("command",command).getString("state").equals("confirmed")&&h.dispatches==1,"retry retains original late confirmation");
  check(h.ownerExchange("receipt",new JSONObject().put("id",id)).getString("state").equals("confirmed"),"exact original lookup");
  try{h.ownerExchange("command",new JSONObject().put("id",id).put("op","save-exit"));throw new AssertionError("ID reuse admitted");}catch(SecurityException expected){}
  check(h.dispatches==1,"conflicting ID cannot dispatch");
  for(int i=2;i<=256;i++)h.ownerExchange("command",new JSONObject().put("id",String.format("%016x",i)).put("op","ping"));
  try{h.ownerExchange("command",new JSONObject().put("id","ffffffffffffffff").put("op","ping"));throw new AssertionError("capacity eviction");}catch(SecurityException expected){}
  check(h.ownerExchange("command",command).getString("state").equals("confirmed")&&h.dispatches==256,"bounded capacity preserves original");
  GatewayDedupeHarness mixed=new GatewayDedupeHarness();
  JSONObject ble=new JSONObject().put("v",1).put("id",id).put("op","arm").put("condition","condition-a").put("bias",50).put("nonce","1111111111111111").put("mac","11111111111111111111111111111111");
  check(mixed.journal.reserve(ble),"original authenticated BLE reserves shared ID");mixed.carrier.updateReceipt(id,"pending","BLE app handler original");
  JSONObject network=new JSONObject(ble.toString());network.remove("mac");network.put("nonce","2222222222222222");
  check(mixed.ownerExchange("command",network).getString("state").equals("pending")&&mixed.dispatches==0,"BLE original lost reply recovered over network without redispatch");
  mixed.carrier.updateReceipt(id,"confirmed","late native arm callback");check(mixed.ownerExchange("receipt",new JSONObject().put("id",id)).getString("state").equals("confirmed"),"BLE original completion queryable over network");
  String next="9999999999999999";network.put("id",next);mixed.ownerExchange("command",network);ble.put("id",next);
  check(mixed.journal.known(ble)&&!mixed.journal.reserve(ble)&&mixed.journal.original(next).getString("state").equals("pending")&&mixed.dispatches==1,"network original retry on BLE consumes existing receipt once");
  GatewayDedupeHarness liveBias=new GatewayDedupeHarness();
  JSONObject bias=new JSONObject().put("v",2).put("id",id).put("op","set-bias").put("s","cccccccccccccccccccccccccccccccc").put("g",7).put("n","1111111111111111").put("b",75);
  liveBias.ownerExchange("command",bias);bias.put("n","2222222222222222");
  liveBias.ownerExchange("command",bias);check(liveBias.dispatches==1,"live bias retry cannot replay");
  JSONObject changedBias=new JSONObject(bias.toString()).put("b",76);
  try{liveBias.ownerExchange("command",changedBias);throw new AssertionError("changed bias ID reused");}catch(SecurityException expected){}
  liveBias.carrier.updateReceipt(id,"confirmed","bias saved");check(liveBias.ownerExchange("receipt",new JSONObject().put("id",id)).getString("state").equals("confirmed"),"bias original durable acknowledgement");
  h.carrier.live=false;try{h.ownerExchange("command",command);throw new AssertionError("retired owner receipt accepted");}catch(SecurityException expected){}
  try{h.ownerExchange("breath-monitor",new JSONObject().put("run","").put("after",0));throw new AssertionError("retired owner monitor accepted");}catch(SecurityException expected){}
  System.out.println("PASS production gateway owner admission: once-only dispatch, canonical ID binding, original pending/late confirmation, exact receipt, no capacity eviction, retired owner denial; inert handler callbacks");
 }
}
'''.replace("__OWNER__", method(source, "private JSONObject ownerExchange(")).replace("__MODE_SNAPSHOT__", method(mode_source, "static final class Snapshot"))
    with tempfile.TemporaryDirectory(prefix="rq-gateway-dedupe-") as temporary:
        output = Path(temporary)
        fixture = output / "GatewayDedupeHarness.java"
        fixture.write_text(harness, encoding="utf-8")
        sources = [root / name for name in ("ExperimentSessionGatewayProtocol.java", "ExperimentSessionCommandJournal.java", "ExperimentSessionBleProtocol.java", "ExperimentSessionControlCapability.java")]
        subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-cp", str(args.json_jar), "-d", str(output), *map(str, sources), str(fixture)], check=True)
        import os
        subprocess.run(["java", "-cp", str(output) + os.pathsep + str(args.json_jar), "io.github.mesmerprism.rustyquest.native_renderer.GatewayDedupeHarness"], check=True)


if __name__ == "__main__":
    main()
