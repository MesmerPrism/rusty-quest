[CmdletBinding()]
param(
 [Parameter(Mandatory)][string]$RepoRoot,
 [Parameter(Mandatory)][string]$JdkBin,
 [Parameter(Mandatory)][string]$JsonJar,
 [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedJsonSha256,
 [Parameter(Mandatory)][string]$OutDirectory
)
$ErrorActionPreference='Stop'
# Caller owns the serialized compiler reservation. This helper does not acquire or extend it.
if((Get-FileHash -LiteralPath $JsonJar -Algorithm SHA256).Hash -ine $ExpectedJsonSha256){throw 'org.json input hash mismatch'}
if(Test-Path -LiteralPath $OutDirectory){throw 'Use a fresh owned output directory'}
[IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($OutDirectory)) | Out-Null
$module=Join-Path $RepoRoot 'apps/native-renderer-android/panel-modules/experiment-session-owner'
$canonical=Join-Path $RepoRoot 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
$tests=Join-Path $RepoRoot 'apps/native-renderer-android/tests/java/io/github/mesmerprism/rustyquest/native_renderer'
$sources=@(
 (Join-Path $module 'src/main/java/io/github/mesmerprism/rustyquest/native_renderer/NativeLocalControlController.java'),
 (Join-Path $module 'tests/NativeLocalControlControllerTest.java'),
 (Join-Path $canonical 'ExperimentSessionBleProtocol.java'),
 (Join-Path $canonical 'ExperimentSessionControlCapability.java'),
 (Join-Path $canonical 'ExperimentSessionUiControlHandoff.java'),
 (Join-Path $tests 'ExperimentSessionControlCapabilityTest.java'),
 (Join-Path $tests 'ExperimentSessionUiControlHandoffTest.java'),
 (Join-Path $canonical 'ExperimentSessionPanelCoordinator.java'),
 (Join-Path $canonical 'ExperimentSessionPanelState.java'),
 (Join-Path $canonical 'ExperimentSessionAndroidShell.java'),
 (Join-Path $RepoRoot 'apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/NativeRendererWriterAcknowledgedExitPolicy.java'),
 (Join-Path $tests 'ExperimentSessionAndroidShellTest.java')
)
& (Join-Path $JdkBin 'javac.exe') --release 8 -encoding UTF-8 -cp $JsonJar -d $OutDirectory @sources
if($LASTEXITCODE -ne 0){throw "Production controller/transfer fixtures compile failed: $LASTEXITCODE"}
foreach($test in @('NativeLocalControlControllerTest','ExperimentSessionControlCapabilityTest','ExperimentSessionUiControlHandoffTest','ExperimentSessionAndroidShellTest')){
 & (Join-Path $JdkBin 'java.exe') -cp ($OutDirectory+[IO.Path]::PathSeparator+$JsonJar) ('io.github.mesmerprism.rustyquest.native_renderer.'+$test)
 if($LASTEXITCODE -ne 0){throw "$test failed: $LASTEXITCODE"}
}
Write-Output 'Pure production controller/transfer fixtures PASS. Android widget/JNI/device behavior remains unverified.'
