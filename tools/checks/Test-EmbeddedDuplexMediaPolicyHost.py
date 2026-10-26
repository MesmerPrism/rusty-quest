"""Actual SessionInputs/policy/credentials; modeled enrollment/Android JSON/capture route.
No device, JNI or APK qualification. Uses real clocks and fresh SecureRandom.
"""
import argparse,json,subprocess,shutil,hashlib
from pathlib import Path
PACKAGE='io.github.mesmerprism.rustyquest.spatial_camera_panel'
JAVA=r"""
package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import org.json.JSONObject;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.OwnPackedPoolNative;
class EmbeddedDuplexEnrollment {
 boolean localFixture=false; String runtimeHostId="host",trustedOperatorId="operator",remoteKeyId="key",adapterId="adapter",mediaRevokerId="revoker",admissionAuthorityId="admission";
 long maxTokenTtlMs=120000; Installed installed=new Installed();
 static class Installed{String roleId="left";}
 JSONObject startupJson(String grant){return new JSONObject().put("grant",grant);}
}
class EmbeddedDuplexPackagedInputs { enum InstalledRole { LEFT; static InstalledRole parse(String s){if(!s.equals("left"))throw new IllegalStateException();return LEFT;}} }
public class PolicyHarness {
 static int checks=0;
 static void need(boolean value){checks++;if(!value)throw new AssertionError("control "+checks);}
 static void refuse(String selection,boolean fixture,boolean own)throws Exception{
  BuildConfig.EMBEDDED_DUPLEX_MEDIA_POLICY=selection;OwnPackedPoolNative.selected=own;
  EmbeddedDuplexEnrollment e=new EmbeddedDuplexEnrollment();e.localFixture=fixture;
  try{if(fixture)EmbeddedDuplexSessionInputs.createLocalDiagnostic(e);else EmbeddedDuplexSessionInputs.createRealPeer(e);throw new AssertionError("unexpected acceptance");}catch(IllegalStateException expected){checks++;}
 }
 public static void main(String[]args)throws Exception{
  EmbeddedDuplexEnrollment e=new EmbeddedDuplexEnrollment();
  BuildConfig.EMBEDDED_DUPLEX_MEDIA_POLICY="finite";OwnPackedPoolNative.selected=true;
  EmbeddedDuplexSessionInputs finite=EmbeddedDuplexSessionInputs.createRealPeer(e);
  need(!finite.runtimeBindings.object("embedded_duplex").has("ongoing_media_policy"));
  need(finite.setupCredentials.policyId.equals("finite_real_peer_setup_v1"));
  need(finite.runtimeBindings.number("grant_expires_at_ms")==finite.setupCredentials.expiresAtMs);
  need(finite.setupCredentials.expiresAtMs-finite.setupCredentials.issuedAtMs==300000);
  BuildConfig.EMBEDDED_DUPLEX_MEDIA_POLICY="ongoing-v2";
  EmbeddedDuplexSessionInputs ongoing=EmbeddedDuplexSessionInputs.createRealPeer(e);
  JSONObject policy=ongoing.runtimeBindings.object("embedded_duplex").object("ongoing_media_policy");
  need(policy.size()==1&&policy.string("$schema").equals("rusty.manifold.peer.ongoing_media_policy.v2"));
  need(ongoing.runtimeBindings.number("max_token_ttl_ms")==finite.runtimeBindings.number("max_token_ttl_ms"));
  need(ongoing.setupCredentials.horizonMs==finite.setupCredentials.horizonMs);
  need(ongoing.setupCredentials.policyId.equals(finite.setupCredentials.policyId));
  need(ongoing.runtimeBindings.number("lease_expires_at_ms")==ongoing.setupCredentials.expiresAtMs);
  need(!ongoing.runtimeBindings.string("validation_epoch_entropy_hex").equals(finite.runtimeBindings.string("validation_epoch_entropy_hex")));
  need(!ongoing.runtimeBindings.string("grant_id").equals(finite.runtimeBindings.string("grant_id")));
  need(ongoing.runtimeBindings.string("validation_epoch_entropy_hex").length()==64);
  e.localFixture=true;
  EmbeddedDuplexSessionInputs fixture=EmbeddedDuplexSessionInputs.createLocalDiagnostic(e);
  need(!fixture.runtimeBindings.object("embedded_duplex").has("ongoing_media_policy"));
  need(fixture.setupCredentials.horizonMs==300000&&fixture.setupCredentials.policyId.equals("local_diagnostic_five_minutes_v1"));
  for(String bad:new String[]{null,"","Finite","ongoing-v1","ongoing-v2 ","rusty.manifold.peer.ongoing_media_policy.v2"}){refuse(bad,false,true);refuse(bad,true,true);}
  refuse("ongoing-v2",false,false);
  BuildConfig.EMBEDDED_DUPLEX_MEDIA_POLICY="finite";OwnPackedPoolNative.selected=false;e.localFixture=false;
  need(!EmbeddedDuplexSessionInputs.createRealPeer(e).runtimeBindings.object("embedded_duplex").has("ongoing_media_policy"));
  System.out.println("PASS actual SessionInputs/credentials/policy controls="+checks);
 }
}
"""
def main():
 a=argparse.ArgumentParser();a.add_argument('--root',type=Path,required=True);a.add_argument('--output',type=Path,required=True);x=a.parse_args();root=x.root.resolve();out=x.output.resolve();out.mkdir(parents=True,exist_ok=False)
 base=root/'apps/spatial-camera-panel-android/app/src/main/java'/PACKAGE.replace('.','/')/'embedded_duplex';sources=[base/n for n in ['EmbeddedDuplexSessionInputs.java','EmbeddedDuplexMediaPolicy.java','EmbeddedDuplexSetupCredentials.java']]
 stubs={
 'org/json/JSONObject.java':'package org.json; import java.util.*; public class JSONObject {private final Map<String,Object> map=new LinkedHashMap<>(); public JSONObject put(String k,Object v){map.put(k,v);return this;} public JSONObject object(String k){return (JSONObject)map.get(k);} public String string(String k){return (String)map.get(k);} public long number(String k){return ((Number)map.get(k)).longValue();} public boolean has(String k){return map.containsKey(k);} public int size(){return map.size();}}',
 'org/json/JSONArray.java':'package org.json;public class JSONArray {public JSONArray put(Object v){return this;}}',
 PACKAGE.replace('.','/')+'/BuildConfig.java':'package '+PACKAGE+';public class BuildConfig {public static String EMBEDDED_DUPLEX_MEDIA_POLICY="finite";public static long EMBEDDED_DUPLEX_SETUP_HORIZON_MS=300000;}',
 PACKAGE.replace('.','/')+'/OwnPackedPoolNative.java':'package '+PACKAGE+';public class OwnPackedPoolNative {public static boolean selected=true;public static boolean captureRouteSelected(){return selected;}}',
 PACKAGE.replace('.','/')+'/embedded_duplex/PolicyHarness.java':JAVA}
 for name,text in stubs.items():p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 for label,argv in [('javac',[shutil.which('javac'),'--release','17','-d',str(out),*[str(p) for p in sources],*[str(out/n) for n in stubs]]),('java',[shutil.which('java'),'-cp',str(out),PACKAGE+'.embedded_duplex.PolicyHarness'])]:
  r=subprocess.run(argv,capture_output=True,timeout=60);(out/(label+'.stdout')).write_bytes(r.stdout);(out/(label+'.stderr')).write_bytes(r.stderr)
  if r.returncode:raise RuntimeError(label+' failed; exact streams retained')
 result=dict(status='PASS',production_session_inputs=True,modeled_enrollment_json_capture=True,device_effects=0,android_qualified=False,source_pins=[dict(path=str(p),sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in sources]);(out/'RESULT.json').write_text(json.dumps(result,indent=2));print((out/'java.stdout').read_text())
if __name__=='__main__':main()
