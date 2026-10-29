"""Actual public lifetime/review callers with explicit Android/store/Native leaves.

No APK, device, private store implementation or physical cleanup is exercised.
The separate Gradle producer proof must use the actual source block and record
its inputs. This runner validates the Java consumers and serialized review path.
"""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys

sys.dont_write_bytecode = True


def item(source, token):
    start = source.index(token)
    opening = source.index("{", start)
    depth = 1
    index = opening + 1
    while depth:
        depth += (source[index] == "{") - (source[index] == "}")
        index += 1
    return source[start:index]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--java-home", required=True)
    parser.add_argument("--json-jar", required=True)
    parser.add_argument("--manifold-root", required=True)
    parser.add_argument("--gradle-home", default="")
    parser.add_argument("--android-jar", default="")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    out = pathlib.Path(args.output).resolve()
    out.mkdir(parents=True, exist_ok=False)
    generated = out / "generated"
    package = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
    embedded = package + ".embedded_duplex"
    base = root / "apps/spatial-camera-panel-android/app/src/main/java" / package.replace(".", "/")
    source = base / "embedded_duplex"
    contract_path = pathlib.Path(args.manifold_root) / "crates/rusty-manifold-admission/contracts/development-lifetime-policy.v1.json"
    policy = json.loads(contract_path.read_text())
    contract = json.dumps(policy, separators=(",", ":"))
    files = []

    def write(name, text):
        path = generated / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")
        files.append(path)

    def java(name, text, owner=embedded):
        write(owner.replace(".", "/") + "/" + name + ".java", "package " + owner + ";\n" + text)

    java("BuildConfig", "public class BuildConfig {public static String EMBEDDED_DUPLEX_DEVELOPMENT_LIFETIME_JSON=" + json.dumps(contract) + ";public static long EMBEDDED_DUPLEX_SETUP_HORIZON_MS=" + str(policy["delegated_authority_horizon_ms"]) + "L;}", package)
    write("android/content/Context.java", "package android.content;public class Context {public Context getApplicationContext(){return this;}}")
    write("android/os/SystemClock.java", "package android.os;public class SystemClock {public static long now=1000;public static long elapsedRealtime(){return now;}}")
    java("EmbeddedDuplexEnrollmentRequest", '''public class EmbeddedDuplexEnrollmentRequest {
 public String roleId="peer_a",packageId="test.application",signerSha256="a".repeat(64),manifestSha256="b".repeat(64),routeSha256="c".repeat(64),localKeyId="ed25519.local",localPublicKeyHex="11".repeat(32),localDeviceId="device.a",localPeerId="peer.a",remoteDeviceId="device.b",remotePeerId="peer.b";
 public static boolean identityPresent=true; public static int ordinaryIdentityReads;
 public static final EmbeddedDuplexEnrollmentRequest installed=new EmbeddedDuplexEnrollmentRequest();
 public static EmbeddedDuplexEnrollmentRequest localSnapshot(android.content.Context c,String role){ordinaryIdentityReads++;if(!role.equals(installed.roleId))throw new IllegalStateException();return installed;}
 public static EmbeddedDuplexEnrollmentRequest existingSnapshot(android.content.Context c,String role){if(!identityPresent||!role.equals(installed.roleId))throw new IllegalStateException("existing identity required");return installed;}
 public boolean sameFacts(EmbeddedDuplexEnrollmentRequest other){return this==other;}
}''')
    host_source = (source / "EmbeddedDuplexProcessHost.java").read_text()
    methods = [item(host_source, token) for token in [
        "CompletableFuture<EmbeddedDuplexEnrollmentReview> reviewEnrollment(\n",
        "CompletableFuture<EmbeddedDuplexEnrollmentReview> reviewEnrollmentPolicyUpdate()",
        "private CompletableFuture<EmbeddedDuplexEnrollmentReview> reviewEnrollment(\n",
        "CompletableFuture<EmbeddedDuplexEnrollment> confirmEnrollment(\n",
    ]]
    java("EmbeddedDuplexProcessHost", '''import java.util.concurrent.*;import java.util.concurrent.atomic.*;
class EmbeddedDuplexProcessHost {
 static final EmbeddedDuplexProcessHost instance=new EmbeddedDuplexProcessHost();
 static EmbeddedDuplexProcessHost forApplication(android.content.Context c){return instance;}
 final android.content.Context applicationContext=new android.content.Context();
 final Object attachmentGate=new Object();Object platform,resources;String runtimeConfigSha256;
 boolean displayDetaching,closeInFlight;EmbeddedDuplexEnrollmentReview pendingReview;
 enum Phase {NEW,PROVISIONING} final AtomicReference<Phase> phase=new AtomicReference<>(Phase.NEW);
 void requireFreshProcess(){if(!EmbeddedDuplexRecoveryCoordinator.fresh)throw new IllegalStateException("pending recovery");}
 <T>CompletableFuture<T> submit(Callable<T> action){try{return CompletableFuture.completedFuture(action.call());}catch(Throwable t){return CompletableFuture.failedFuture(t);}}
 <T>CompletableFuture<T> failed(Throwable t){return CompletableFuture.failedFuture(t);}
 CompletableFuture<EmbeddedDuplexEnrollmentStatus> enrollmentStatus(String role){return failed(new UnsupportedOperationException());}
''' + "\n".join(methods) + "\n}")
    java("EmbeddedDuplexRecoveryCoordinator", '''class EmbeddedDuplexRecoveryCoordinator {
 static boolean fresh=true;EmbeddedDuplexRecoveryCoordinator(android.content.Context c){}
 void requireFreshBootstrap(){if(!fresh)throw new IllegalStateException("pending recovery");}}
class EmbeddedDuplexNative {static boolean idle=true;static boolean processIdleForEnrollment(){return idle;}}''')
    java("SpatialPrivateFeatureRegistry", '''import android.content.Context;import ''' + embedded + '''.*;
public class SpatialPrivateFeatureRegistry {
 public static EmbeddedDuplexEnrollment current;public static boolean drift;
 public static String readEmbeddedDuplexRole(Context c){return "peer_a";}
 public static Object resolveEmbeddedDuplexEnrollment(Context c,Object installed){return current;}
 public static Object reviewEmbeddedDuplexEnrollment(Context c,Object installed,Object draft){
  return new EmbeddedDuplexEnrollmentFence(current.revision+(drift?1:0),current.recordSha256,current.remoteKeyId);}
 public static Object replaceReviewedEmbeddedDuplexEnrollment(Context c,Object installed,Object value,Object expected)throws Exception{
  EmbeddedDuplexEnrollmentDraft d=(EmbeddedDuplexEnrollmentDraft)value;EmbeddedDuplexEnrollmentFence f=(EmbeddedDuplexEnrollmentFence)expected;
  if(f.revision!=current.revision||!f.recordSha256.equals(current.recordSha256))throw new IllegalStateException("store CAS");
  return current=new EmbeddedDuplexEnrollment((EmbeddedDuplexEnrollmentRequest)installed,current.remoteKeyId,d.remotePublicKeyHex,d.runtimeHostId,d.trustedOperatorId,d.adapterId,d.mediaRevokerId,d.admissionAuthorityId,d.maxTokenTtlMs,current.revision+1,"ef".repeat(32),false);
 }
}''', package)
    java("DevelopmentLifetimeHarness", '''import java.util.concurrent.*;import java.security.*;import org.json.*;
import ''' + package + '''.BuildConfig;import ''' + package + '''.SpatialPrivateFeatureRegistry;
public class DevelopmentLifetimeHarness {
 public static EmbeddedDuplexEnrollmentReview sample;
 static int checks;interface Action {void run()throws Exception;}
 static void need(boolean ok){checks++;if(!ok)throw new AssertionError("control "+checks);}
 static void refuses(Action a)throws Exception{try{a.run();throw new AssertionError("unexpected acceptance");}catch(Exception expected){checks++;}}
 static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02x",v));return s.toString();}
 public static void main(String[] ignored)throws Exception {
  long h=BuildConfig.EMBEDDED_DUPLEX_SETUP_HORIZON_MS;String selected=BuildConfig.EMBEDDED_DUPLEX_DEVELOPMENT_LIFETIME_JSON;
  need(h>Integer.MAX_VALUE);need(EmbeddedDuplexSetupCredentials.selectedHorizon(300000)==h);
  need(EmbeddedDuplexSetupCredentials.issueSelected(false,h,1000).expiresAtMs==h+1000);
  refuses(()->EmbeddedDuplexSetupCredentials.issue(false,h,1000));
  refuses(()->EmbeddedDuplexSetupCredentials.issueSelected(false,h,Long.MAX_VALUE-1));
  String remote="22".repeat(32),key="ed25519."+hex(MessageDigest.getInstance("SHA-256").digest(java.util.HexFormat.of().parseHex(remote)));
  EmbeddedDuplexEnrollment old=new EmbeddedDuplexEnrollment(EmbeddedDuplexEnrollmentRequest.installed,key,remote,"host.current","operator.current","adapter.current","revoker.current","authority.current",120000,7,"ab".repeat(32),false);
  SpatialPrivateFeatureRegistry.current=old;
  android.content.Context context=new android.content.Context();
  EmbeddedDuplexEnrollmentReview review=EmbeddedDuplexEnrollmentService.reviewPolicyUpdate(context).get();
  sample=review;
  need(review.priorRevision==7&&review.priorRecordSha256.equals(old.recordSha256));
  need(review.draft.maxTokenTtlMs==h&&review.draft.remotePublicKeyHex.equals(remote));
  need(review.draft.runtimeHostId.equals(old.runtimeHostId)&&review.draft.trustedOperatorId.equals(old.trustedOperatorId)&&review.draft.adapterId.equals(old.adapterId)&&review.draft.mediaRevokerId.equals(old.mediaRevokerId)&&review.draft.admissionAuthorityId.equals(old.admissionAuthorityId));
  need(SpatialPrivateFeatureRegistry.current==old&&old.maxTokenTtlMs==120000);
  need(EmbeddedDuplexEnrollmentRequest.ordinaryIdentityReads==0);
  final EmbeddedDuplexEnrollmentReview missingIdentityReview=review;
  EmbeddedDuplexEnrollmentRequest.identityPresent=false;
  refuses(()->EmbeddedDuplexEnrollmentService.confirm(context,missingIdentityReview).get());
  need(SpatialPrivateFeatureRegistry.current==old&&EmbeddedDuplexEnrollmentRequest.ordinaryIdentityReads==0);
  refuses(()->EmbeddedDuplexEnrollmentService.reviewPolicyUpdate(context).get());
  need(SpatialPrivateFeatureRegistry.current==old&&EmbeddedDuplexEnrollmentRequest.ordinaryIdentityReads==0);
  EmbeddedDuplexEnrollmentRequest.identityPresent=true;
  final EmbeddedDuplexEnrollmentReview confirmedReview=EmbeddedDuplexEnrollmentService.reviewPolicyUpdate(context).get();sample=confirmedReview;
  android.os.SystemClock.now+=3600000;need(confirmedReview.waitWarning()&&!confirmedReview.expired());
  EmbeddedDuplexEnrollment updated=EmbeddedDuplexEnrollmentService.confirm(context,confirmedReview).get();
  need(updated.revision==8&&updated.maxTokenTtlMs==h&&updated.remoteKeyId.equals(old.remoteKeyId)&&updated.remotePublicKeyHex.equals(old.remotePublicKeyHex));
  refuses(()->EmbeddedDuplexEnrollmentService.confirm(context,review).get());
  refuses(()->EmbeddedDuplexEnrollmentService.reviewPolicyUpdate(context).get());
  SpatialPrivateFeatureRegistry.current=old;SpatialPrivateFeatureRegistry.drift=true;
  refuses(()->EmbeddedDuplexEnrollmentService.reviewPolicyUpdate(context).get());SpatialPrivateFeatureRegistry.drift=false;
  EmbeddedDuplexNative.idle=false;refuses(()->EmbeddedDuplexEnrollmentService.reviewPolicyUpdate(context).get());EmbeddedDuplexNative.idle=true;
  EmbeddedDuplexRecoveryCoordinator.fresh=false;refuses(()->EmbeddedDuplexEnrollmentService.reviewPolicyUpdate(context).get());EmbeddedDuplexRecoveryCoordinator.fresh=true;
  EmbeddedDuplexProcessHost.instance.resources=new Object();refuses(()->EmbeddedDuplexEnrollmentService.reviewPolicyUpdate(context).get());EmbeddedDuplexProcessHost.instance.resources=null;
  BuildConfig.EMBEDDED_DUPLEX_DEVELOPMENT_LIFETIME_JSON="";BuildConfig.EMBEDDED_DUPLEX_SETUP_HORIZON_MS=300000;
  need(EmbeddedDuplexSetupCredentials.selectedHorizon(300000)==300000);refuses(()->EmbeddedDuplexEnrollmentService.reviewPolicyUpdate(context).get());
  refuses(()->new EmbeddedDuplexEnrollmentDraft("peer_a",remote,"host.current","operator.current","adapter.current","revoker.current","authority.current",h,false));
  BuildConfig.EMBEDDED_DUPLEX_DEVELOPMENT_LIFETIME_JSON=selected;BuildConfig.EMBEDDED_DUPLEX_SETUP_HORIZON_MS=h-1;
  refuses(()->EmbeddedDuplexSetupCredentials.developmentPolicy());
  BuildConfig.EMBEDDED_DUPLEX_DEVELOPMENT_LIFETIME_JSON=selected;BuildConfig.EMBEDDED_DUPLEX_SETUP_HORIZON_MS=h;
  System.out.println("PASS actual public caller controls="+checks+"; Android/store/Native leaves modeled");
 }
}''')
    names = ["SetupCredentials", "Enrollment", "EnrollmentDraft", "EnrollmentFence", "EnrollmentStatus", "EnrollmentResolver", "EnrollmentReview", "EnrollmentService", "CanonicalJson"]
    actual = [source / ("EmbeddedDuplex" + name + ".java") for name in names]
    checks = []

    def ref(path):
        return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def run(name, argv):
        result = subprocess.run(argv, capture_output=True)
        (out / (name + ".stdout")).write_bytes(result.stdout)
        (out / (name + ".stderr")).write_bytes(result.stderr)
        checks.append({"name": name, "argv": argv, "exit_code": result.returncode})
        if result.returncode:
            raise RuntimeError(name + ": " + result.stderr.decode(errors="replace")[-5000:])

    classes = out / "classes"
    classes.mkdir()
    java_bin = pathlib.Path(args.java_home) / "bin"
    run("javac", [str(java_bin / "javac.exe"), "--release", "17", "-cp", args.json_jar, "-d", str(classes), *map(str, actual + files)])
    run("controls", [str(java_bin / "java.exe"), "-cp", os.pathsep.join([str(classes), args.json_jar]), embedded + ".DevelopmentLifetimeHarness"])
    if args.gradle_home:
        assert args.android_jar, "Kotlin source proof requires an Android API JAR"
        lib = pathlib.Path(args.gradle_home) / "lib"
        stdlib, = lib.glob("kotlin-stdlib-[0-9]*.jar")
        contract_source = root / "apps/spatial-camera-panel-android/app/src/debug/java" / package.replace(".", "/") / "EmbeddedDuplexOperatorContract.kt"
        controls_source = base / "StereoBankControls.kt"
        harness = generated / "DevelopmentPolicyWireHarness.kt"
        harness.write_text('''package ''' + package + '''
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.DevelopmentLifetimeHarness
import org.json.JSONObject
fun main() {
  DevelopmentLifetimeHarness.main(emptyArray())
  val challenge="a".repeat(32)
  var checks=0
  fun need(value:Boolean){checks++;check(value)}
  fun refuses(action:()->Unit){try{action();error("unexpected acceptance")}catch(expected:IllegalArgumentException){checks++}}
  val request=EmbeddedDuplexOperatorContract.parseFields("review_policy_update",challenge,emptyMap())
  need(request.route==EmbeddedDuplexOperatorContract.Route.REVIEW_POLICY_UPDATE && request.draft==null)
  for(fields in listOf(mapOf("max_token_ttl_ms" to 2592000000L),mapOf("role_id" to "peer_a"),mapOf("run_id" to "b".repeat(32)),mapOf("original_id" to "b".repeat(32))))
    refuses { EmbeddedDuplexOperatorContract.parseFields("review_policy_update",challenge,fields) }
  refuses { EmbeddedDuplexOperatorContract.parseFields("review_policy_update",null,emptyMap()) }
  val review=DevelopmentLifetimeHarness.sample
  val slot=EmbeddedDuplexOperatorReviewSlot<Any>()
  slot.install(challenge,review.reviewSha256,review)
  refuses {slot.consume("b".repeat(32),review.reviewSha256)}
  need(slot.consume(challenge,review.reviewSha256)===review)
  refuses {slot.consume(challenge,review.reviewSha256)}
  val confirm=EmbeddedDuplexOperatorContract.parseFields("confirm",challenge,mapOf("review_sha256" to review.reviewSha256))
  need(confirm.route==EmbeddedDuplexOperatorContract.Route.CONFIRM)
  val raw=EmbeddedDuplexOperatorContract.policyReviewReceipt(review)
  val dto=JSONObject(raw)
  need(dto.getLong("prior_revision")==7L && dto.getLong("max_token_ttl_ms")>Int.MAX_VALUE)
  need(dto.getString("prior_record_sha256")=="ab".repeat(32) && dto.getString("review_sha256")==review.reviewSha256)
  need(dto.getJSONObject("policy").getLong("delegated_authority_horizon_ms")==dto.getLong("max_token_ttl_ms"))
  println("ACTUAL_POLICY_REVIEW_RECEIPT="+raw)
  println("PASS actual closed Kotlin wire controls="+checks+"; Android/store/Native leaves modeled")
}
''', encoding="utf-8")
        cp = os.pathsep.join([str(classes), args.json_jar, args.android_jar, str(stdlib)])
        run("kotlinc", [str(java_bin / "java.exe"), "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp, "-d", str(classes), str(contract_source), str(controls_source), str(harness)])
        run("wire-controls", [str(java_bin / "java.exe"), "-cp", cp, package + ".DevelopmentPolicyWireHarnessKt"])
        sample = next(line.split("=", 1)[1] for line in (out / "wire-controls.stdout").read_text().splitlines() if line.startswith("ACTUAL_POLICY_REVIEW_RECEIPT="))
        (out / "modeled-caller-actual-policy-review.json").write_text(sample + "\n", encoding="utf-8")
        actual += [contract_source, controls_source]
    result = {"status": "passed", "checks": checks, "contract": ref(contract_path), "source_pins": list(map(ref, actual + [source / "EmbeddedDuplexProcessHost.java"])), "json_jar": ref(pathlib.Path(args.json_jar)), "limits": __doc__, "device_calls": 0, "apk_build": False}
    (out / "RESULT.json").write_text(json.dumps(result, indent=2) + "\n")
    print((out / "controls.stdout").read_text().strip())


if __name__ == "__main__":
    main()
