[CmdletBinding()]
param([string]$RepoRoot=(Join-Path $PSScriptRoot '..'),[string]$JavaHome=$env:JAVA_HOME,
 [string]$AndroidHome=$env:ANDROID_HOME,[string]$GradlePath=$env:RUSTY_QUEST_GRADLE_PATH,
 [string]$HostJsonJar=$env:RUSTY_QUEST_HOST_JSON_JAR,[string]$PolicyVectorPath=$env:RUSTY_QUEST_ORDINARY_START_POLICY_VECTOR,
 [string]$KotlinToolchainManifest=$env:RUSTY_QUEST_KOTLIN_TOOLCHAIN_MANIFEST,
 [string]$JUnitJar,[string]$HamcrestJar,
 [string]$OutputRoot)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$RepoRoot=(Resolve-Path -LiteralPath $RepoRoot).Path
if(-not $JavaHome -or -not $AndroidHome -or -not $GradlePath -or -not $PolicyVectorPath -or -not $KotlinToolchainManifest){throw 'Select existing JDK/Android/Gradle, retained public policy vector and Kotlin toolchain manifest.'}
if(-not $HostJsonJar){$HostJsonJar=@(Get-ChildItem -LiteralPath "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20240303" -Filter '*.jar' -File -Recurse)[0].FullName}
function Select-PinnedJar([string]$Selected,[string]$Cache,[string]$Name,[string]$Hash){
 if(-not $Selected){$found=@(Get-ChildItem -LiteralPath (Join-Path $HOME ".gradle/caches/modules-2/files-2.1/$Cache") -Filter $Name -File -Recurse);if($found.Count -ne 1){throw "Select exact $Name dependency."};$Selected=$found[0].FullName}
 if((Get-FileHash -LiteralPath $Selected).Hash.ToLowerInvariant() -cne $Hash){throw "Selected $Name hash differs."}
 (Resolve-Path -LiteralPath $Selected).Path
}
$JUnitJar=Select-PinnedJar $JUnitJar 'junit/junit/4.13.2' 'junit-4.13.2.jar' '8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3'
$HamcrestJar=Select-PinnedJar $HamcrestJar 'org.hamcrest/hamcrest-core/1.3' 'hamcrest-core-1.3.jar' '66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9'
$KotlinToolchainManifest=(Resolve-Path -LiteralPath $KotlinToolchainManifest).Path
$toolchain=Get-Content -Raw -LiteralPath $KotlinToolchainManifest|ConvertFrom-Json -Depth 100 -DateKind String
$stdlib=@($toolchain.jvm.compiler_jars|Where-Object{[IO.Path]::GetFileName($_.path) -like 'kotlin-stdlib-*.jar'})
if($stdlib.Count -ne 1 -or (Get-FileHash -LiteralPath $stdlib[0].path).Hash.ToLowerInvariant() -cne $stdlib[0].sha256){throw 'Selected Kotlin standard library differs.'}
if(-not $OutputRoot){$OutputRoot=Join-Path $RepoRoot ('target/app-admission-'+[guid]::NewGuid().ToString('N'))}
$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
if(-not $OutputRoot.StartsWith([IO.Path]::GetFullPath((Join-Path $RepoRoot target))+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $OutputRoot)){throw 'Create-new target output required.'}
[void](New-Item -ItemType Directory -Path $OutputRoot)
function Save($name,$value){$value|ConvertTo-Json -Depth 100|Set-Content "$OutputRoot/$name" -Encoding utf8NoBOM}
function Pin($path){@{path=[IO.Path]::GetFullPath($path);sha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}}
function Sources{foreach($path in @(& git ls-files --cached --others --exclude-standard)|Sort-Object -Unique){if(Test-Path -LiteralPath $path -PathType Leaf){@{path=$path;sha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}}}}
Push-Location $RepoRoot
try {
 $before=@(Sources);Save source-inputs.json $before
 & "$RepoRoot/tools/Test-EmbeddedDuplexOrdinaryStartSource.ps1" -RepoRoot $RepoRoot -JavaHome $JavaHome -AndroidHome $AndroidHome -GradlePath $GradlePath -HostJsonJar $HostJsonJar -PolicyVectorPath $PolicyVectorPath -OutputRoot "$OutputRoot/app-source" *> "$OutputRoot/app-source.log"
 if($LASTEXITCODE -ne 0){throw 'Affected Android source/ordinary command controls failed.'}
 $junitResults=@()
 foreach($mode in @('finite','fresh')){
  $compiled="$OutputRoot/app-source/android-$mode"
  $testClasses="$compiled/app/tmp/kotlin-classes/debugUnitTest"
  $newTest='io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexAppAdmissionTest'
  $testClass=Join-Path $testClasses ($newTest.Replace('.',[IO.Path]::DirectorySeparatorChar)+'.class')
  if(-not(Test-Path -LiteralPath $testClass -PathType Leaf)){throw "Actual compiled admission test absent for $mode."}
  $junitClasspath=@($testClasses,"$compiled/app/intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes",
   "$compiled/app/tmp/kotlin-classes/debug","$compiled/app/intermediates/javac/debug/compileDebugJavaWithJavac/classes",
   $HostJsonJar,$stdlib[0].path,$JUnitJar,$HamcrestJar,"$AndroidHome/platforms/android-34/android.jar") -join [IO.Path]::PathSeparator
  & "$JavaHome/bin/java.exe" -cp $junitClasspath org.junit.runner.JUnitCore $newTest *> "$OutputRoot/app-admission-junit-$mode.log"
  if($LASTEXITCODE -ne 0){throw "Actual compiled admission JUnit failed for $mode."}
  $log=Get-Content -Raw -LiteralPath "$OutputRoot/app-admission-junit-$mode.log"
  if($log -notmatch 'OK \(([1-9][0-9]*) tests?\)'){throw "Admission JUnit did not execute for $mode."}
  $junitResults+=@{selection=$mode;tests=[int]$Matches[1];test_class=Pin $testClass;log=Pin "$OutputRoot/app-admission-junit-$mode.log"}
 }
 Save app-admission-junit.json $junitResults
 Save junit-dependencies.json @((Pin $JUnitJar),(Pin $HamcrestJar),(Pin $stdlib[0].path),(Pin $KotlinToolchainManifest))
 $fresh="$OutputRoot/app-source/android-fresh"
 $support=@("$fresh/app/tmp/kotlin-classes/debug","$fresh/app/intermediates/javac/debug/compileDebugJavaWithJavac/classes","$fresh/media-stream-android/classes/java/main","$fresh/spatial-sdk-shared/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar","$AndroidHome/platforms/android-34/android.jar") -join [IO.Path]::PathSeparator
 python "$RepoRoot/tools/checks/Test-EmbeddedDuplexAppAdmissionHost.py" --root $RepoRoot --output "$OutputRoot/host" --java-home $JavaHome --json-jar $HostJsonJar --support-classpath $support --kotlin-toolchain-manifest $KotlinToolchainManifest *> "$OutputRoot/host.log"
 if($LASTEXITCODE -ne 0){throw 'Actual app admission/callback/Original controls failed.'}
 & git diff --check;if($LASTEXITCODE -ne 0){throw 'Whitespace check failed.'}
 $after=@(Sources);Save source-after.json $after
 if(($before|ConvertTo-Json -Depth 100 -Compress) -cne ($after|ConvertTo-Json -Depth 100 -Compress)){throw 'Source changed during qualification.'}
 Save RESULT.json ([ordered]@{status='passed';head=(& git rev-parse HEAD).Trim();source_inputs=Pin "$OutputRoot/source-inputs.json";app=Pin "$OutputRoot/app-source/RESULT.json";host=Pin "$OutputRoot/host/RESULT.json";admission_junit=Pin "$OutputRoot/app-admission-junit.json";junit_dependencies=Pin "$OutputRoot/junit-dependencies.json";limits=@('Actual affected Java/Kotlin source composition, both-selection admission JUnit and explicit model-leaf host controls.','Native/JNI bytes unchanged; component-bound evidence is supplied separately by the source owner.','No APK/device/SDK adoption, private enrollment or physical cleanup claim.')})
} finally {Pop-Location}
