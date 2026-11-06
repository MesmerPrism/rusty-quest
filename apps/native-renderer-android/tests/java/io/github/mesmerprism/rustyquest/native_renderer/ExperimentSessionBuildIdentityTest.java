package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
public final class ExperimentSessionBuildIdentityTest {
 public static void main(String[] args)throws Exception {
  String sha=String.join("",java.util.Collections.nCopies(64,"a")),git=sha.substring(0,40);
  JSONObject asset=new JSONObject().put("schema","rusty.quest.android_build_identity.v1").put("source_commit",git).put("source_tree",git).put("source_composition_fingerprint",sha).put("packaging_fingerprint",sha).put("private_path","secret");
  JSONObject out=ExperimentSessionBuildIdentity.project(61,"0.1.61",asset.toString());
  check(out.getLong("version_code")==61&&out.getString("version_name").equals("0.1.61"),"installed versions");
  check(out.getString("source_commit").equals(git)&&out.length()==7&&!out.toString().contains("secret"),"closed public identity");
  asset.put("source_commit","/private/path");check(ExperimentSessionBuildIdentity.project(61,"0.1.61",asset.toString()).isNull("source_commit"),"malformed hash withheld");
  check(ExperimentSessionBuildIdentity.project(-1,"bad/name","invalid").isNull("version_code"),"unknown identity honest");
  System.out.println("Android build identity projection PASS");
 }
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
}
