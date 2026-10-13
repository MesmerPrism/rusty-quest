"""Actual Java issuance/input/status controls; enrollment and Android platform facts are modeled."""
import pathlib, subprocess, tempfile, shutil, unittest, os
ROOT = pathlib.Path(__file__).resolve().parents[4]
PACKAGE = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
MAIN = ROOT / "apps/spatial-camera-panel-android/app/src/main/java" / pathlib.Path(*PACKAGE.split("."))

class SetupCredentials(unittest.TestCase):
    def test_actual_inputs_and_readback(self):
        self._run(False)

    @unittest.skipUnless(os.environ.get("QUESTMS_SETUP_PRODUCT_INPUT_ROOT"), "QUESTMS_SETUP_PRODUCT_INPUT_ROOT required for retained asset transfer")
    def test_actual_packaged_transfer(self):
        self._run(True)

    def _run(self, transfer):
        fixture = pathlib.Path(os.environ["QUESTMS_SETUP_PRODUCT_INPUT_ROOT"]).resolve(strict=True) if transfer else None
        output = pathlib.Path(os.environ["QUESTMS_SETUP_REQUEST_OUTPUT"]).resolve(strict=True) if transfer and os.environ.get("QUESTMS_SETUP_REQUEST_OUTPUT") else None
        javac = shutil.which("javac")
        self.assertIsNotNone(javac, "Java17 compiler required")
        jars = sorted((pathlib.Path(os.environ["USERPROFILE"]) / ".gradle/caches/modules-2/files-2.1/org.json/json").rglob("json-*.jar"))
        self.assertTrue(jars, "Cached owner org.json dependency required")
        with tempfile.TemporaryDirectory() as folder:
            out = pathlib.Path(folder)
            sources = []
            def stub(name, package, text):
                f = out / name
                f.write_text("package " + package + ";\n" + text, encoding="utf-8")
                sources.append(str(f))
            stub("BuildConfig.java", PACKAGE, "public final class BuildConfig { public static final long EMBEDDED_DUPLEX_SETUP_HORIZON_MS=Long.getLong(\"fixture.setup.horizon\",1800000L); }")
            stub("OwnPackedPoolNative.java", PACKAGE, "public final class OwnPackedPoolNative { public static boolean selected; public static boolean captureRouteSelected(){return selected;} }")
            sub = PACKAGE + ".embedded_duplex"
            stub("EmbeddedDuplexEnrollment.java", sub, """import org.json.JSONObject;
final class EmbeddedDuplexEnrollment {
 boolean localFixture; long maxTokenTtlMs=240000;
 String runtimeHostId="host.fixture",trustedOperatorId="operator.fixture",remoteKeyId="key.fixture",adapterId="adapter.fixture",mediaRevokerId="revoker.fixture",admissionAuthorityId="authority.fixture";
 final Installed installed=new Installed(); static final class Installed {String roleId="peer_a";}
 JSONObject startupJson(String id)throws Exception{return new JSONObject().put("route_grant_id",id);}
}""")
            stub("Context.java", "android.content", "public class Context { public android.content.pm.PackageManager getPackageManager(){throw new UnsupportedOperationException();} public String getPackageName(){throw new UnsupportedOperationException();} public android.content.res.AssetManager getAssets(){throw new UnsupportedOperationException();} }")
            stub("AssetManager.java", "android.content.res", "public class AssetManager { public java.io.InputStream open(String path)throws Exception{throw new UnsupportedOperationException();} public String[] list(String path)throws Exception{throw new UnsupportedOperationException();} }")
            stub("PackageManager.java", "android.content.pm", "public class PackageManager {public static final int GET_SIGNING_CERTIFICATES=1;public PackageInfo getPackageInfo(String name,int flags){throw new UnsupportedOperationException();}}")
            stub("PackageInfo.java", "android.content.pm", "public class PackageInfo {public SigningInfo signingInfo;}")
            stub("SigningInfo.java", "android.content.pm", "public class SigningInfo {public Signature[] getApkContentsSigners(){throw new UnsupportedOperationException();}}")
            stub("Signature.java", "android.content.pm", "public class Signature {public byte[] toByteArray(){throw new UnsupportedOperationException();}}")
            stub("EmbeddedDuplexBootstrap.java", sub, "final class EmbeddedDuplexBootstrap {static final class Failure {String stage,code;}}")
            stub("PackedStereoCaptureOwner.java", "io.github.mesmerprism.rustyquest.media", "public final class PackedStereoCaptureOwner {public static final class CleanupStatus{}}")
            stub("SetupHost.java", sub, """import org.json.JSONObject;
public final class SetupHost {
 static void yes(boolean v){if(!v)throw new AssertionError();}
 static void deny(Runnable r){try{r.run();throw new AssertionError();}catch(IllegalArgumentException expected){}}
 public static void main(String[] args)throws Exception{
   for(long horizon:new long[]{300000,1800000,3600000}) {
     EmbeddedDuplexSetupCredentials p=EmbeddedDuplexSetupCredentials.issue(false,horizon,100);
     yes(p.expiresAtMs==100+horizon && p.horizonMs==horizon && p.issuedAtMs==100);
   }
   for(long horizon:new long[]{0,299999,300001,3600001,Long.MAX_VALUE})deny(()->EmbeddedDuplexSetupCredentials.issue(false,horizon,100));
   deny(()->EmbeddedDuplexSetupCredentials.issue(false,300000,0));
   deny(()->EmbeddedDuplexSetupCredentials.issue(false,300000,Long.MAX_VALUE-299999));
   deny(()->EmbeddedDuplexSetupCredentials.issue(true,1800000,100));
   long expected=io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig.EMBEDDED_DUPLEX_SETUP_HORIZON_MS;
   EmbeddedDuplexEnrollment e=new EmbeddedDuplexEnrollment();
   EmbeddedDuplexSessionInputs a=EmbeddedDuplexSessionInputs.createRealPeer(e);
   yes(a.runtimeBindings.getLong("setup_credential_horizon_ms")==expected && a.setupCredentials.horizonMs==expected && a.runtimeBindings.getLong("grant_expires_at_ms")==a.setupCredentials.expiresAtMs);
   yes(a.runtimeBindings.getLong("lease_expires_at_ms")==a.setupCredentials.expiresAtMs && a.runtimeBindings.getLong("max_token_ttl_ms")==60000);
   io.github.mesmerprism.rustyquest.spatial_camera_panel.OwnPackedPoolNative.selected=true;
   EmbeddedDuplexSessionInputs b=EmbeddedDuplexSessionInputs.createRealPeer(e);
   yes(b.runtimeBindings.getLong("max_token_ttl_ms")==120000);
   e.localFixture=true;
   EmbeddedDuplexSessionInputs f=EmbeddedDuplexSessionInputs.createLocalDiagnostic(e);
   yes(f.setupCredentials.horizonMs==300000 && f.runtimeBindings.getLong("max_token_ttl_ms")==60000);
   try{EmbeddedDuplexSessionInputs.createRealPeer(e);throw new AssertionError();}catch(IllegalStateException rejected){}
   e.localFixture=false;
   try{EmbeddedDuplexSessionInputs.createLocalDiagnostic(e);throw new AssertionError();}catch(IllegalStateException rejected){}
   EmbeddedDuplexRuntimeStatus status=new EmbeddedDuplexRuntimeStatus("bootstrapped_route_unverified",true,"a".repeat(64),"b".repeat(64),null,a.setupCredentials);
   yes(new EmbeddedDuplexRuntimeStatus(status,"Live").setupCredentials==a.setupCredentials);
   yes(new EmbeddedDuplexRuntimeStatus("uninitialized",false,null,null,null,a.setupCredentials).setupCredentials==null);
   if(args.length>0) {
     java.nio.file.Path root=java.nio.file.Path.of(args[0]);
     String manifest=java.nio.file.Files.readString(root.resolve("product-input-manifest.json"));
     JSONObject identity=new JSONObject(manifest).getJSONObject("package");
     String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
     EmbeddedDuplexPackagedInputs packaged=EmbeddedDuplexPackagedInputs.load(new EmbeddedDuplexPackagedInputs.AssetReader(){
       public java.io.InputStream open(String name)throws Exception{return java.nio.file.Files.newInputStream(root.resolve(name));}
       public String[] list()throws Exception{try(var files=java.nio.file.Files.list(root)){return files.map(v->v.getFileName().toString()).toArray(String[]::new);}}
     },sha,identity.getString("application_id"),identity.getString("signing_certificate_sha256"),EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A);
     JSONObject actual=packaged.packagedConfigRequest(a.runtimeBindings);
     yes(actual.getLong("setup_credential_horizon_ms")==expected && actual.getLong("grant_expires_at_ms")==a.setupCredentials.expiresAtMs
         && actual.getLong("lease_expires_at_ms")==a.setupCredentials.expiresAtMs && actual.getLong("max_token_ttl_ms")==a.runtimeBindings.getLong("max_token_ttl_ms"));
     if(args.length>1) java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]).resolve("SESSION_"+expected+".request.json"),actual.toString(),java.nio.file.StandardOpenOption.CREATE_NEW);
     if(expected==300000) {
       JSONObject legacy=new JSONObject(a.runtimeBindings.toString());legacy.remove("setup_credential_horizon_ms");
       JSONObject old=packaged.packagedConfigRequest(legacy);yes(!old.has("setup_credential_horizon_ms"));
       if(args.length>1)java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]).resolve("LEGACY_300000.request.json"),old.toString(),java.nio.file.StandardOpenOption.CREATE_NEW);
     }
     for(Object bad:new Object[]{"1800000",1800000.0,300001L,3600001L,JSONObject.NULL}) {
       JSONObject edited=new JSONObject(a.runtimeBindings.toString()).put("setup_credential_horizon_ms",bad);
       boolean denied=false;try{packaged.packagedConfigRequest(edited);}catch(IllegalArgumentException ex){denied=true;}yes(denied);
     }
     JSONObject extra=new JSONObject(a.runtimeBindings.toString()).put("unknown_policy",1800000);
     boolean denied=false;try{packaged.packagedConfigRequest(extra);}catch(IllegalArgumentException ex){denied=true;}yes(denied);
     System.out.println("Actual SessionInputs -> verified retained asset loader -> packagedConfigRequest transfer PASS horizon="+expected+"; no native authority invoked");
   }
   System.out.println("Actual finite issuance, input token caps, fixture closure and status readback PASS; Android facts modeled");
 }
}""")
            sources += [str(MAIN / "embedded_duplex" / f) for f in ["EmbeddedDuplexSetupCredentials.java", "EmbeddedDuplexSessionInputs.java", "EmbeddedDuplexRuntimeStatus.java", "EmbeddedDuplexPackagedInputs.java"]]
            subprocess.run([javac,"--release","17","-encoding","UTF-8","-cp",str(jars[-1]),"-d",folder,*sources],check=True)
            java = str(pathlib.Path(javac).with_name("java.exe"))
            fixture_args = [str(fixture)] + ([str(output)] if output else []) if transfer else []
            subprocess.run([java,"-cp",folder+os.pathsep+str(jars[-1]),sub+".SetupHost",*fixture_args],check=True)
            if transfer:
                subprocess.run([java,"-Dfixture.setup.horizon=300000","-cp",folder+os.pathsep+str(jars[-1]),sub+".SetupHost",*fixture_args],check=True)
            # Bind the production public build parameter without executing any build body.
            ps = out / "parameter-controls.ps1"
            ps.write_text("""param([string]$Builder)
$ErrorActionPreference='Stop'
$errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($Builder,[ref]$null,[ref]$errors)
if($errors.Count){throw 'Builder parse failed'}
$p=@($ast.ParamBlock.Parameters|Where-Object{$_.Name.VariablePath.UserPath -ceq 'EmbeddedDuplexSetupHorizonMs'})
if($p.Count-ne1){throw 'Missing exact setup horizon parameter'}
$bind=[scriptblock]::Create('param('+$p[0].Extent.Text+')'+[Environment]::NewLine+'$EmbeddedDuplexSetupHorizonMs')
if((& $bind)-ne300000){throw 'Default policy changed'}
foreach($v in @(300000,1800000,3600000)){if((& $bind $v)-ne$v){throw 'Selected policy differs'}}
foreach($v in @(299999,300001,3600001)){try{& $bind $v;throw 'Invalid horizon admitted'}catch {if($_.FullyQualifiedErrorId -notlike "ParameterArgumentValidationError*"){throw}}}
Write-Output 'Actual public build argument bounds/default PASS; no build invoked'
""", encoding="utf-8")
            subprocess.run(["pwsh","-NoProfile","-File",str(ps),"-Builder",str(ROOT / "tools/Build-SpatialCameraPanelAndroid.ps1")],check=True)


if __name__ == "__main__": unittest.main()
