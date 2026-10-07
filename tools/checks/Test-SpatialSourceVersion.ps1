param([Parameter(Mandatory)][string]$OutDir,[string]$JavaHome,[string]$KotlinCompilerClasspath)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if(Test-Path $OutDir){throw 'Create-new evidence required'};$null=New-Item -ItemType Directory $OutDir
Import-Module (Join-Path $root 'tools/lib/SpatialSourceVersion.psm1') -Force
$fp='a'*64;$other='b'*64;$package='io.example.receiver'
$default=New-SpatialSourceVersionMetadata $fp
$enabled=New-SpatialSourceVersionMetadata $fp -Enabled
if($default.version_name-cne'0.1.0'-or$enabled.version_name-cne('0.1.0+src.'+$fp)-or$enabled.version_code-ne1){throw 'Default/derived version contract'}
function Badging($name,$version,$code='1'){"package: name='$name' versionCode='$code' versionName='$version' platformBuildVersionName='14'`nsdkVersion:'34'"}
$null=Assert-SpatialSourceVersionBadging (Badging $package $default.version_name) $package $default
$null=Assert-SpatialSourceVersionBadging (Badging $package $enabled.version_name) $package $enabled
$controls=@()
function Reject($name,[scriptblock]$action){try{& $action;throw 'Unexpected acceptance'}catch{if($_.Exception.Message-ceq'Unexpected acceptance'){throw};$script:controls+=@{name=$name;error=$_.Exception.Message}}}
foreach($bad in @(('a'*63),('A'*64),(('a'*64)+"`n"),('a'*65),'opaque-label')){Reject ('invalid fingerprint '+$bad.Length) {New-SpatialSourceVersionMetadata $bad -Enabled}}
Reject 'different package' {Assert-SpatialSourceVersionBadging (Badging 'io.example.other' $enabled.version_name) $package $enabled}
Reject 'static version for enabled build' {Assert-SpatialSourceVersionBadging (Badging $package '0.1.0') $package $enabled}
Reject 'different resolved source' {Assert-SpatialSourceVersionBadging (Badging $package ('0.1.0+src.'+$other)) $package $enabled}
Reject 'different version code' {Assert-SpatialSourceVersionBadging (Badging $package $enabled.version_name '2') $package $enabled}
Reject 'duplicate package rows' {Assert-SpatialSourceVersionBadging ((Badging $package $enabled.version_name)+"`n"+(Badging $package $enabled.version_name)) $package $enabled}
foreach($duplicate in @("name='io.example.other'","versionCode='2'","versionName=malformed")){
 Reject ('duplicate identity key '+$duplicate) {Assert-SpatialSourceVersionBadging ((Badging $package $enabled.version_name)-replace " platformBuildVersionName=",(" $duplicate platformBuildVersionName=")) $package $enabled}
}
Reject 'absent package row' {Assert-SpatialSourceVersionBadging "sdkVersion:'34'" $package $enabled}
$altered=[ordered]@{};foreach($key in $enabled.Keys){$altered[$key]=$enabled[$key]};$altered.version_name='opaque-label'
Reject 'altered derived metadata' {Assert-SpatialSourceVersionBadging (Badging $package 'opaque-label') $package $altered}
$altered.enabled='false'
Reject 'nonboolean metadata selection' {Assert-SpatialSourceVersionBadging (Badging $package 'opaque-label') $package $altered}
$tokens=$null;$errors=$null;$builder=Join-Path $root 'tools/Build-SpatialCameraPanelAndroid.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($builder,[ref]$tokens,[ref]$errors);if($errors.Count){throw 'Builder parse errors'}
$conditions=@($ast.FindAll({param($node)$node-is[Management.Automation.Language.IfStatementAst]-and$node.Extent.Text-cmatch'^if\(\$UseSourceCompositionVersionName\)\{\$shellIdentityDescriptor'},$true))
if($conditions.Count-ne1){throw 'Exactly one enabled shell-cache binding required'}
$hashNode=@($ast.FindAll({param($node)$node-is[Management.Automation.Language.AssignmentStatementAst]-and$node.Left.Extent.Text-ceq'$shellFingerprint'},$true))
if($hashNode.Count-ne1-or$conditions[0].Extent.EndOffset-ge$hashNode[0].Extent.StartOffset){throw 'Metadata must bind before shell-cache fingerprint'}
$cache=@()
foreach($choice in @($false,$true)){
 $UseSourceCompositionVersionName=$choice;$sourceVersionMetadata=$enabled;$shellIdentityDescriptor=[ordered]@{schema='cache.fixture';source_sha256='c'*64}
 & ([scriptblock]::Create($conditions[0].Extent.Text))
 if($choice-ne$shellIdentityDescriptor.Contains('developer_source_version')){throw 'Actual builder cache conditional differs'}
 $cache+=@{enabled=$choice;json=($shellIdentityDescriptor|ConvertTo-Json -Depth 20 -Compress)}
}
if($cache[0].json-ceq$cache[1].json){throw 'Enabled/default cache collision'}
$native=@($ast.FindAll({param($node)$node-is[Management.Automation.Language.AssignmentStatementAst]-and$node.Left.Extent.Text-ceq'$nativeIdentityDescriptor'},$true))
if($native.Count-ne1-or$native[0].Extent.Text.Contains('sourceVersion')-or$native[0].Extent.Text.Contains('UseSourceCompositionVersionName')){throw 'Metadata option must not change native cache identity'}
$property=@($ast.FindAll({param($node)$node-is[Management.Automation.Language.AssignmentStatementAst]-and$node.Left.Extent.Text-ceq'$versionProperty'},$true))
$arguments=@($ast.FindAll({param($node)$node-is[Management.Automation.Language.AssignmentStatementAst]-and$node.Extent.Text.Contains('"-PrqDeveloperSourceCompositionFingerprint=$versionProperty"')},$true))
if($property.Count-ne1-or$arguments.Count-ne1){throw 'Exactly one explicit Gradle property producer required'}
foreach($choice in @($false,$true)){
 $UseSourceCompositionVersionName=$choice;$sourceVersionMetadata=$enabled;$gradleArguments=@('--no-daemon')
 . ([scriptblock]::Create($property[0].Extent.Text));. ([scriptblock]::Create($arguments[0].Extent.Text))
 $expected='-PrqDeveloperSourceCompositionFingerprint='+$(if($choice){$fp}else{''})
 if($gradleArguments[0]-cne$expected){throw 'Exact enabled/empty-default Gradle property differs'}
}
$source=Get-Content $builder -Raw
foreach($required in @('developer_source_version','Assert-SpatialSourceVersionBadging','-PrqDeveloperSourceCompositionFingerprint=')){if(-not$source.Contains($required)){throw 'Source version producer seam missing'}}
$gradle=Get-Content (Join-Path $root 'apps/spatial-camera-panel-android/app/build.gradle.kts') -Raw
if(-not$gradle.Contains('versionName = spatialSourceVersionName')-or-not$gradle.Contains('providers.gradleProperty("rqDeveloperSourceCompositionFingerprint").orNull')){throw 'Actual Gradle metadata consumer missing'}
$kotlinControl='not_run';$toolPins=@()
if($JavaHome-or$KotlinCompilerClasspath){
 if(-not$JavaHome-or-not$KotlinCompilerClasspath){throw 'Both explicit JDK and compiler classpath required'}
 $function=[regex]::Match($gradle,'(?s)fun sourceCompositionVersionName\(.*?\n}\n')
 if(-not$function.Success){throw 'Actual Gradle function extraction failed'}
 $test=@'
fun main() {
  val fp = "a".repeat(64)
  check(sourceCompositionVersionName(null) == "0.1.0")
  check(sourceCompositionVersionName("") == "0.1.0")
  check(sourceCompositionVersionName(fp) == "0.1.0+src.$fp")
  for (bad in listOf("a".repeat(63), "A".repeat(64), fp + "\n", "a".repeat(65), "opaque-label")) {
    var rejected = false
    try { sourceCompositionVersionName(bad) } catch (failure: IllegalArgumentException) { rejected = true }
    check(rejected)
  }
  println("actual Gradle source-version function PASS")
}
'@
 $file=Join-Path $OutDir 'ActualGradleSourceVersion.kt';[IO.File]::WriteAllText($file,$function.Value+$test,[Text.UTF8Encoding]::new($false))
 $java=Join-Path $JavaHome 'bin/java.exe';$jars=@($KotlinCompilerClasspath-split';');$stdlib=@($jars|Where-Object {[IO.Path]::GetFileName($_)-cmatch'^kotlin-stdlib-[0-9].*\.jar$'})
 if($stdlib.Count-ne1){throw 'Exactly one explicit Kotlin stdlib required'}
 & $java -cp $KotlinCompilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -Werror -classpath $stdlib[0] -d (Join-Path $OutDir 'kotlin') $file *> (Join-Path $OutDir 'kotlin-compile.log');if($LASTEXITCODE){throw 'Actual Gradle function compile failed'}
 & $java -cp ((Join-Path $OutDir 'kotlin')+';'+$stdlib[0]) ActualGradleSourceVersionKt *> (Join-Path $OutDir 'kotlin-controls.log');if($LASTEXITCODE){throw 'Actual Gradle function controls failed'}
 $toolPins=@(@($java)+$jars|ForEach-Object {@{path=$_;sha256=(Get-FileHash $_).Hash.ToLowerInvariant()}});$kotlinControl='passed'
}
@{status='passed';positive_controls=2;negative_controls=$controls;actual_builder_cache_condition=$conditions[0].Extent.Text;default_cache_shape_preserved=$true;enabled_cache_distinct=$true;native_cache_option_independent=$true;actual_gradle_function_controls=$kotlinControl;full_gradle_execution=$false;tool_pins=$toolPins;apk_build=$false;device_calls=0}|ConvertTo-Json -Depth 30|Set-Content (Join-Path $OutDir 'RESULT.json') -NoNewline
