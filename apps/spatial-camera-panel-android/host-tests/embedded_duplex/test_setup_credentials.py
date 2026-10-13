"""Actual Java issuance/input/status controls; enrollment and Android platform facts are modeled."""
import pathlib, subprocess, tempfile, shutil, unittest, os
ROOT = pathlib.Path(__file__).resolve().parents[4]
PACKAGE = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
MAIN = ROOT / "apps/spatial-camera-panel-android/app/src/main/java" / pathlib.Path(*PACKAGE.split("."))

class SetupCredentials(unittest.TestCase):
    def test_actual_inputs_and_readback(self):
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
            stub("BuildConfig.java", PACKAGE, "public final class BuildConfig { public static final long EMBEDDED_DUPLEX_SETUP_HORIZON_MS=1800000L; }")
            stub("OwnPackedPoolNative.java", PACKAGE, "public final class OwnPackedPoolNative { public static boolean selected; public static boolean captureRouteSelected(){return selected;} }")
            sub = PACKAGE + ".embedded_duplex"
            stub("EmbeddedDuplexEnrollment.java", sub, """import org.json.JSONObject;
final class EmbeddedDuplexEnrollment {
 boolean localFixture; long maxTokenTtlMs=240000;
 String runtimeHostId="host.fixture",trustedOperatorId="operator.fixture",remoteKeyId="key.fixture",adapterId="adapter.fixture",mediaRevokerId="revoker.fixture",admissionAuthorityId="authority.fixture";
 final Installed installed=new Installed(); static final class Installed {String roleId="peer_a";}
 JSONObject startupJson(String id)throws Exception{return new JSONObject().put("route_grant_id",id);}
}""")
            stub("EmbeddedDuplexPackagedInputs.java", sub, "final class EmbeddedDuplexPackagedInputs { enum InstalledRole {PEER_A; static InstalledRole parse(String id){if(!id.equals(\"peer_a\"))throw new IllegalArgumentException();return PEER_A;}} }")
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
   for(long horizon:new long[]{0,299999,3600001,Long.MAX_VALUE})deny(()->EmbeddedDuplexSetupCredentials.issue(false,horizon,100));
   deny(()->EmbeddedDuplexSetupCredentials.issue(false,300000,0));
   deny(()->EmbeddedDuplexSetupCredentials.issue(false,300000,Long.MAX_VALUE-299999));
   deny(()->EmbeddedDuplexSetupCredentials.issue(true,1800000,100));
   EmbeddedDuplexEnrollment e=new EmbeddedDuplexEnrollment();
   EmbeddedDuplexSessionInputs a=EmbeddedDuplexSessionInputs.createRealPeer(e);
   yes(a.setupCredentials.horizonMs==1800000 && a.runtimeBindings.getLong("grant_expires_at_ms")==a.setupCredentials.expiresAtMs);
   yes(a.runtimeBindings.getLong("lease_expires_at_ms")==a.setupCredentials.expiresAtMs && a.runtimeBindings.getLong("max_token_ttl_ms")==60000);
   io.github.mesmerprism.rustyquest.spatial_camera_panel.OwnPackedPoolNative.selected=true;
   EmbeddedDuplexSessionInputs b=EmbeddedDuplexSessionInputs.createRealPeer(e);
   yes(b.runtimeBindings.getLong("max_token_ttl_ms")==120000);
   e.localFixture=true;
   EmbeddedDuplexSessionInputs f=EmbeddedDuplexSessionInputs.createLocalDiagnostic(e);
   yes(f.setupCredentials.horizonMs==300000 && f.runtimeBindings.getLong("max_token_ttl_ms")==60000);
   try{EmbeddedDuplexSessionInputs.createRealPeer(e);throw new AssertionError();}catch(IllegalStateException expected){}
   e.localFixture=false;
   try{EmbeddedDuplexSessionInputs.createLocalDiagnostic(e);throw new AssertionError();}catch(IllegalStateException expected){}
   EmbeddedDuplexRuntimeStatus status=new EmbeddedDuplexRuntimeStatus("bootstrapped_route_unverified",true,"a".repeat(64),"b".repeat(64),null,a.setupCredentials);
   yes(new EmbeddedDuplexRuntimeStatus(status,"Live").setupCredentials==a.setupCredentials);
   yes(new EmbeddedDuplexRuntimeStatus("uninitialized",false,null,null,null,a.setupCredentials).setupCredentials==null);
   System.out.println("Actual finite issuance, input token caps, fixture closure and status readback PASS; Android facts modeled");
 }
}""")
            sources += [str(MAIN / "embedded_duplex" / f) for f in ["EmbeddedDuplexSetupCredentials.java", "EmbeddedDuplexSessionInputs.java", "EmbeddedDuplexRuntimeStatus.java"]]
            subprocess.run([javac,"--release","17","-encoding","UTF-8","-cp",str(jars[-1]),"-d",folder,*sources],check=True)
            subprocess.run([str(pathlib.Path(javac).with_name("java.exe")),"-cp",folder+os.pathsep+str(jars[-1]),sub+".SetupHost"],check=True)
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
foreach($v in @(299999,3600001)){try{& $bind $v;throw 'Invalid horizon admitted'}catch {if($_.FullyQualifiedErrorId -notlike "ParameterArgumentValidationError*"){throw}}}
Write-Output 'Actual public build argument bounds/default PASS; no build invoked'
""", encoding="utf-8")
            subprocess.run(["pwsh","-NoProfile","-File",str(ps),"-Builder",str(ROOT / "tools/Build-SpatialCameraPanelAndroid.ps1")],check=True)


if __name__ == "__main__": unittest.main()
