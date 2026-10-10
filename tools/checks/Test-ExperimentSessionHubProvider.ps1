[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputRoot,
      [Parameter(Mandatory)][string]$AndroidJar,
      [Parameter(Mandatory)][string]$JsonJar)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputRoot){throw 'Create-new output required'}
$null=New-Item -ItemType Directory $OutputRoot
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$pkg='io/github/mesmerprism/rustyquest/native_renderer'
$panel=Join-Path $repo "apps/native-renderer-android/panel-modules/breath-composition/src/main/java/$pkg"
$sources=@('ExperimentSessionPanelState','ExperimentSessionPanelCoordinator','ExperimentSessionPanelViewPolicy','ExperimentSessionStatusObservation','ExperimentSessionHubProvider','ExperimentSessionHubLifetime')|ForEach-Object{Join-Path $panel "$_.java"}
$sources+=Join-Path $repo 'crates/rusty-quest-broker-admission/android/io/github/mesmerprism/rustyquest/broker_admission/ConnectionHubAdmissionSessionReducer.java'
function RunHost($name,$exe,[string[]]$arguments){
 & $exe @arguments 1> (Join-Path $OutputRoot "$name.stdout") 2> (Join-Path $OutputRoot "$name.stderr")
 $code=$LASTEXITCODE
 [IO.File]::WriteAllText((Join-Path $OutputRoot "$name.exit"),[string]$code)
 if($code-ne0){throw "Host control failed: $name ($code)"}
 Get-Content (Join-Path $OutputRoot "$name.stdout")
}
$hostOut=Join-Path $OutputRoot 'host';$androidOut=Join-Path $OutputRoot 'android'
$null=New-Item -ItemType Directory $hostOut,$androidOut
$test=Join-Path $repo "apps/native-renderer-android/tests/java/$pkg/ExperimentSessionHubProviderTest.java"
$hubRoot=Join-Path $repo 'apps/manifold-broker-android/src/main/java/io/github/mesmerprism/rustymanifold/broker'
$hubSources=@('ConnectionHubProtocol','HubProviderIdentity','HubSurfaceDescriptor')|ForEach-Object{Join-Path $hubRoot "$_.java"}
RunHost 'host-compile' 'javac' (@('--release','8','-cp',$JsonJar,'-d',$hostOut)+$sources+$hubSources+@($test,(Join-Path $repo "apps/native-renderer-android/tests/java/$pkg/ExperimentSessionHubLifetimeTest.java")))
RunHost 'host-tests' 'java' @('-cp',"$hostOut$([IO.Path]::PathSeparator)$JsonJar",'io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionHubProviderTest')
RunHost 'lifetime-tests' 'java' @('-cp',"$hostOut$([IO.Path]::PathSeparator)$JsonJar",'io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionHubLifetimeTest')
RunHost 'android-compile'  'javac' (@('--release','8','-cp',$AndroidJar,'-d',$androidOut)+$sources+@(Join-Path $panel 'ExperimentSessionHubSurfaceClient.java'))
@{passed=$true;scope='Actual reducer/core driver host callbacks and real Android API typecheck; no Binder/device/grant/network/packaging proof';sources=@($sources)+$hubSources+@($test,(Join-Path $panel 'ExperimentSessionHubSurfaceClient.java'));android_jar_sha256=(Get-FileHash $AndroidJar).Hash.ToLowerInvariant();json_jar_sha256=(Get-FileHash $JsonJar).Hash.ToLowerInvariant()}|ConvertTo-Json -Depth 5|Set-Content (Join-Path $OutputRoot 'RESULT.json')
