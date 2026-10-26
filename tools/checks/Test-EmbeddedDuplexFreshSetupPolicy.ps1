param(
    [string]$RepoRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [ValidateSet('PolicyOnly','Full',IgnoreCase=$false)][string]$Mode='Full',
    [string]$OutputRoot='', [string]$JavaHome=$env:JAVA_HOME,[string]$JsonJar='',
    [string]$NativePolicyProjectionPath=''
)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
Import-Module (Join-Path $RepoRoot 'tools/EmbeddedDuplexFreshSetupPolicy.psm1') -Force
if(-not$OutputRoot){$OutputRoot=Join-Path $RepoRoot ('target/fresh-build-policy-'+[guid]::NewGuid().ToString('N'))}
if(Test-Path -LiteralPath $OutputRoot){throw 'Fresh conformance output must be new.'}
New-Item -ItemType Directory -Path $OutputRoot|Out-Null
function Need($Value,[string]$Message){if(-not$Value){throw $Message}}
$script:refusals=0
function Refuse([scriptblock]$Action){try{&$Action;throw 'CONTROL_UNEXPECTED_ACCEPTANCE'}catch{if($_.Exception.Message-ceq'CONTROL_UNEXPECTED_ACCEPTANCE'){throw};$script:refusals++}}
function RawSha([string]$Path){return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function WriteFixture([string]$Path,$Object){[IO.File]::WriteAllText($Path,(ConvertTo-FreshSetupCanonicalJson $Object),[Text.UTF8Encoding]::new($false))}
# Dictionary members are not JSON keys: a real `keys` field must not shadow
# IDictionary.Keys and convert the two credential records into property names.
$canonicalShadow=[ordered]@{Values=@(2L,3L);keys=@([ordered]@{Count=1L});Count=0L}
Need ((ConvertTo-FreshSetupCanonicalJson $canonicalShadow)-ceq'{"Count":0,"Values":[2,3],"keys":[{"Count":1}]}') 'Dictionary member-name fields corrupted canonical bytes.'
$none=[ordered]@{schema='fixture';old=17};$before=ConvertTo-FreshSetupCanonicalJson $none
Set-EmbeddedDuplexFreshSetupPolicyDescriptor $none $null
Need ((ConvertTo-FreshSetupCanonicalJson $none)-ceq$before) 'None changed descriptor bytes.'
foreach($bad in @('','None','SOURCE-BOUND-V2','source-bound-v1','source-bound-v2 ')){Refuse {Get-EmbeddedDuplexFreshSetupPolicy $bad}}
Refuse {Resolve-EmbeddedDuplexFreshSetupBuildSelection none finite $null 'present' ('0'*64)}
$routePath=Join-Path $OutputRoot 'route-configuration.json'
WriteFixture $routePath ([ordered]@{peers=@([ordered]@{peer_id='peer.a'},[ordered]@{peer_id='peer.b'})})
$inputs=[pscustomobject]@{root=$OutputRoot;closure_sha256=('1'*64);manifest_sha256=('2'*64);artifacts=@([pscustomobject]@{path='route-configuration.json';sha256=(RawSha $routePath)})}
if($NativePolicyProjectionPath){
    $native=Get-Content -Raw -LiteralPath $NativePolicyProjectionPath|ConvertFrom-Json -Depth 64 -DateKind String
    Need ($native.'$schema'-ceq'rusty.quest.fresh_setup_build_policy_vector.v1') 'Native vector producer schema differs.'
    $target=$native.config.fresh_setup_enrollment_selection
}else{
    $keys=@();$public=@('d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a','3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c')
    for($i=0;$i-lt2;$i++){
        $peer=@('peer.a','peer.b')[$i];$hash=([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Convert]::FromHexString($public[$i])))).ToLowerInvariant()
        $keys+=,[ordered]@{'$schema'='rusty.manifold.peer.fresh_setup_enrollment_key.v1';peer_id=$peer;credential_id="credential.duplex.$peer.1";trust_domain='trust.morphospace.peer';key_id="ed25519.$hash";key_generation=1L;algorithm='ed25519';public_key_hex=$public[$i];public_key_sha256="sha256:$hash";key_fingerprint="fingerprint.$hash"}
    }
    $target=[ordered]@{'$schema'='rusty.manifold.peer.fresh_setup_enrollment_selection.v1';operator_id='operator.quest.peer-enrollment';keys=$keys}
}
$targetPath=Join-Path $OutputRoot 'target.json';WriteFixture $targetPath $target
$targetSha=RawSha $targetPath
$selected=Resolve-EmbeddedDuplexFreshSetupBuildSelection source-bound-v2 ongoing-v2 $inputs $targetPath $targetSha
$descriptor=[ordered]@{schema='fixture';old=17};Set-EmbeddedDuplexFreshSetupPolicyDescriptor $descriptor $selected
Need ((ConvertTo-FreshSetupCanonicalJson $descriptor)-cne$before) 'Fresh selection did not change artifact identity.'
Need ($selected.policy_sha256-ceq(Get-FreshSetupDomainSha256 'rusty.quest.embedded_duplex.fresh_setup_build_policy.v1' (Get-EmbeddedDuplexFreshSetupPolicy source-bound-v2))) 'Policy canonical binding differs.'
Refuse {Resolve-EmbeddedDuplexFreshSetupBuildSelection source-bound-v2 finite $inputs $targetPath $targetSha}
Refuse {Resolve-EmbeddedDuplexFreshSetupBuildSelection source-bound-v2 ongoing-v2 $null $targetPath $targetSha}
Refuse {Resolve-EmbeddedDuplexFreshSetupBuildSelection source-bound-v2 ongoing-v2 $inputs $targetPath ('0'*64)}
foreach($damage in @('foreign_peer','invalid_operator','hash','extra','duplicate')){
    $value=(Get-Content -LiteralPath $targetPath -Raw|ConvertFrom-Json -Depth 32 -DateKind String)
    switch($damage){
        'foreign_peer'{$value.keys[1].peer_id='peer.foreign'}
        'invalid_operator'{$value.operator_id='operator..foreign'}
        'hash'{$value.keys[0].public_key_sha256='sha256:'+('0'*64)}
        'extra'{$value|Add-Member -NotePropertyName unknown -NotePropertyValue $true}
    }
    $badPath=Join-Path $OutputRoot ($damage+'.json');WriteFixture $badPath $value
    if($damage-ceq'duplicate'){$raw=Get-Content -LiteralPath $badPath -Raw;[IO.File]::WriteAllText($badPath,'{"operator_id":"operator.foreign",'+$raw.Substring(1),[Text.UTF8Encoding]::new($false))}
    Refuse {Resolve-EmbeddedDuplexFreshSetupBuildSelection source-bound-v2 ongoing-v2 $inputs $badPath (RawSha $badPath)}
}
# The operator is selected DATA, not a fixture literal. Actual Java/native
# construction independently rejoins it to trusted enrolled operator ownership.
$manualTarget=Get-Content -LiteralPath $targetPath -Raw|ConvertFrom-Json -Depth 32 -DateKind String
$manualTarget.operator_id='operator.application.manual'
$manualPath=Join-Path $OutputRoot 'manual-operator-target.json';WriteFixture $manualPath $manualTarget
$manual=Resolve-EmbeddedDuplexFreshSetupBuildSelection source-bound-v2 ongoing-v2 $inputs $manualPath (RawSha $manualPath)
Need ($manual.enrollment_selection.operator_id-ceq'operator.application.manual') 'Recorded manual operator changed.'
Need ($manual.enrollment_selection_sha256-cne$selected.enrollment_selection_sha256) 'Operator was not bound in target digest.'
foreach($invalidOperator in @('', 'UPPER.operator', '.operator', 'operator.', 'operator..manual', 'operator.-manual', 'operator.manual ', 'operator/manual')){
    $manualTarget.operator_id=$invalidOperator;WriteFixture $manualPath $manualTarget
    Refuse {Resolve-EmbeddedDuplexFreshSetupBuildSelection source-bound-v2 ongoing-v2 $inputs $manualPath (RawSha $manualPath)}
}
[IO.File]::AppendAllText($routePath,' ',[Text.UTF8Encoding]::new($false))
Refuse {Resolve-EmbeddedDuplexFreshSetupBuildSelection source-bound-v2 ongoing-v2 $inputs $targetPath $targetSha}
# Actual effectful builder is parsed, never invoked by this host conformance.
$tokens=$null;$errors=$null;$builderPath=Join-Path $RepoRoot 'tools/Build-SpatialCameraPanelAndroid.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($builderPath,[ref]$tokens,[ref]$errors)
Need ($errors.Count-eq0) 'Actual builder syntax differs.'
$calls=@($ast.FindAll({param($n)$n-is[Management.Automation.Language.CommandAst]-and$n.GetCommandName()-ceq'Set-EmbeddedDuplexFreshSetupPolicyDescriptor'},$true))
Need ($calls.Count-eq7) 'Actual six artifacts plus canonical APK descriptor projection missing.'
$nativeStatus='not_requested';$javaStatus='not_requested'
if($Mode-ceq'Full'){
    Need ($NativePolicyProjectionPath-and$JavaHome-and$JsonJar) 'Full conformance requires actual owning Rust vector and Java/JSON inputs.'
    Need ($native.projection.policy_sha256-ceq$selected.policy_sha256-and$native.projection.enrollment_selection_sha256-ceq$selected.enrollment_selection_sha256) 'Actual Native typed canonical digests differ.'
    Need ($native.policy_canonical_json-ceq(ConvertTo-FreshSetupCanonicalJson $selected.policy)-and$native.enrollment_selection_canonical_json-ceq$selected.enrollment_selection_json) 'Owning canonical bytes differ across implementations.'
    $nativeStatus='passed'
    $package='io.github.mesmerprism/rustyquest/spatial_camera_panel'.Replace('/','.')
    $pkgDir=Join-Path $OutputRoot $package.Replace('.','/');$embeddedDir=Join-Path $pkgDir 'embedded_duplex';New-Item -ItemType Directory -Force -Path $embeddedDir|Out-Null
    $buildConfig=@'
package io.github.mesmerprism.rustyquest.spatial_camera_panel;
public final class BuildConfig {
 public static String EMBEDDED_DUPLEX_MEDIA_POLICY="ongoing-v2",EMBEDDED_DUPLEX_FRESH_SETUP_POLICY="source-bound-v2",EMBEDDED_DUPLEX_FRESH_POLICY_SHA256="",EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256="",EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256="",EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64="";
 public static long EMBEDDED_DUPLEX_SETUP_HORIZON_MS=300000L;
}
'@
    $own='package io.github.mesmerprism.rustyquest.spatial_camera_panel; public class OwnPackedPoolNative {public static boolean selected=true;public static boolean captureRouteSelected(){return selected;}}'
    $harness=@'
package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import org.json.*;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.OwnPackedPoolNative;
class EmbeddedDuplexEnrollment {
 boolean localFixture=false;String runtimeHostId="host.fixture",trustedOperatorId="operator.quest.peer-enrollment",adapterId="adapter.fixture",mediaRevokerId="operator.revoker",admissionAuthorityId="authority.fixture",remoteKeyId,remotePublicKeyHex;
 long maxTokenTtlMs=120000;Installed installed=new Installed();
 static class Installed{String roleId="peer_a",localPeerId="peer.a",remotePeerId="peer.b",localKeyId,localPublicKeyHex;}
 JSONObject startupJson(String grant)throws Exception{return new JSONObject().put("grant",grant);}
}
class EmbeddedDuplexPackagedInputs{enum InstalledRole{A;static InstalledRole parse(String s){return A;}}}
public class FreshPolicyHarness{
 static int checks;static void need(boolean v){checks++;if(!v)throw new AssertionError("fresh build control "+checks);}
 interface Action{void run()throws Exception;}static void refuses(Action a)throws Exception{try{a.run();throw new AssertionError("accepted damage");}catch(IllegalStateException expected){checks++;}}
 static EmbeddedDuplexSessionInputs create(EmbeddedDuplexEnrollment e)throws Exception{return e.localFixture?EmbeddedDuplexSessionInputs.createLocalDiagnostic(e):EmbeddedDuplexSessionInputs.createRealPeer(e);}
 public static void main(String[]args)throws Exception{
  BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64=args[0];BuildConfig.EMBEDDED_DUPLEX_FRESH_POLICY_SHA256=args[1];BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256=args[2];BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256=args[3];
  JSONObject target=new JSONObject(new String(Base64.getDecoder().decode(args[0]),StandardCharsets.UTF_8));
  JSONObject a=target.getJSONArray("keys").getJSONObject(0),b=target.getJSONArray("keys").getJSONObject(1);
  EmbeddedDuplexEnrollment e=new EmbeddedDuplexEnrollment();e.installed.localKeyId=a.getString("key_id");e.installed.localPublicKeyHex=a.getString("public_key_hex");e.remoteKeyId=b.getString("key_id");e.remotePublicKeyHex=b.getString("public_key_hex");
  JSONObject embedded=create(e).runtimeBindings.getJSONObject("embedded_duplex");need(embedded.getString("fresh_setup_local_peer_id").equals("peer.a"));need(embedded.getJSONArray("trusted_key_fingerprints").length()==2);need(EmbeddedDuplexCanonicalJson.encode(embedded.getJSONObject("fresh_setup_enrollment_selection")).equals(EmbeddedDuplexCanonicalJson.encode(target)));
  BuildConfig.EMBEDDED_DUPLEX_FRESH_POLICY_SHA256="sha256:"+"0".repeat(64);refuses(()->create(e));BuildConfig.EMBEDDED_DUPLEX_FRESH_POLICY_SHA256=args[1];
  BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256="0".repeat(64);refuses(()->create(e));BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256=args[3];
  String saved=e.installed.localPublicKeyHex;e.installed.localPublicKeyHex="0".repeat(64);refuses(()->create(e));e.installed.localPublicKeyHex=saved;
  saved=e.remotePublicKeyHex;e.remotePublicKeyHex="0".repeat(64);refuses(()->create(e));e.remotePublicKeyHex=saved;
  BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64=args[4];BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256=args[5];BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256=args[6];
  e.trustedOperatorId="operator.application.manual";need(create(e).runtimeBindings.getJSONObject("embedded_duplex").getJSONObject("fresh_setup_enrollment_selection").getString("operator_id").equals("operator.application.manual"));
  e.trustedOperatorId="operator.foreign";refuses(()->create(e));
  BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64=args[0];BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256=args[2];BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256=args[3];
  refuses(()->create(e));e.trustedOperatorId="operator.quest.peer-enrollment";
  BuildConfig.EMBEDDED_DUPLEX_MEDIA_POLICY="finite";refuses(()->create(e));BuildConfig.EMBEDDED_DUPLEX_MEDIA_POLICY="ongoing-v2";
  e.localFixture=true;refuses(()->create(e));e.localFixture=false;
  OwnPackedPoolNative.selected=false;refuses(()->create(e));OwnPackedPoolNative.selected=true;
  BuildConfig.EMBEDDED_DUPLEX_FRESH_SETUP_POLICY="none";refuses(()->create(e));
  BuildConfig.EMBEDDED_DUPLEX_FRESH_POLICY_SHA256="";BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256="";BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256="";BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64="";
  need(!create(e).runtimeBindings.getJSONObject("embedded_duplex").has("fresh_setup_local_peer_id"));need(!create(e).runtimeBindings.getJSONObject("embedded_duplex").has("fresh_setup_enrollment_selection"));
  BuildConfig.EMBEDDED_DUPLEX_FRESH_SETUP_POLICY="Source-bound-v2";refuses(()->create(e));
  System.out.println("PASS actual SessionInputs/fresh helper controls="+checks+" physical facts MODELED");
 }
}
'@
    [IO.File]::WriteAllText((Join-Path $pkgDir 'BuildConfig.java'),$buildConfig,[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $pkgDir 'OwnPackedPoolNative.java'),$own,[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $embeddedDir 'FreshPolicyHarness.java'),$harness,[Text.UTF8Encoding]::new($false))
    $base=Join-Path $RepoRoot 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    $sources=@('EmbeddedDuplexSessionInputs.java','EmbeddedDuplexFreshSetupPolicy.java','EmbeddedDuplexMediaPolicy.java','EmbeddedDuplexSetupCredentials.java','EmbeddedDuplexCanonicalJson.java')|ForEach-Object{Join-Path $base $_}
    $sources+=@((Join-Path $pkgDir 'BuildConfig.java'),(Join-Path $pkgDir 'OwnPackedPoolNative.java'),(Join-Path $embeddedDir 'FreshPolicyHarness.java'))
    $classes=Join-Path $OutputRoot 'classes';New-Item -ItemType Directory -Path $classes|Out-Null
    $javac=Join-Path $JavaHome 'bin/javac.exe';$java=Join-Path $JavaHome 'bin/java.exe'
    & $javac --release 17 -encoding UTF-8 -cp $JsonJar -d $classes @sources 1>(Join-Path $OutputRoot 'javac.stdout') 2>(Join-Path $OutputRoot 'javac.stderr')
    Need ($LASTEXITCODE-eq0) 'Actual fresh Java consumer compilation failed.'
    & $java -cp ($classes+[IO.Path]::PathSeparator+$JsonJar) ($package+'.embedded_duplex.FreshPolicyHarness') $selected.enrollment_selection_base64 $selected.policy_sha256 $selected.enrollment_selection_sha256 $selected.enrollment_target_raw_sha256 $manual.enrollment_selection_base64 $manual.enrollment_selection_sha256 $manual.enrollment_target_raw_sha256 1>(Join-Path $OutputRoot 'java.stdout') 2>(Join-Path $OutputRoot 'java.stderr')
    Need ($LASTEXITCODE-eq0) 'Actual fresh Java consumer causal controls failed.'
    $javaStatus='passed'
}
$result=[ordered]@{schema='rusty.quest.fresh_setup_build_policy_conformance.v1';status='passed';coverage=$Mode;none_descriptor_byte_exact=$true;closed_refusals=$refusals;actual_descriptor_projection_sites=$calls.Count;native_typed_projection=$nativeStatus;java_actual_consumer=$javaStatus;device_calls=0;apk_build_performed=$false;limits='Actual policy helper and optionally typed owning Rust vector + actual SessionInputs/Java helper; qualified product/physical/Android facts modeled. APK packaging remains actual owning build evidence.'}
$result|ConvertTo-Json -Depth 16|Set-Content -LiteralPath (Join-Path $OutputRoot 'RESULT.json') -Encoding utf8NoBOM
$result|ConvertTo-Json -Depth 16
