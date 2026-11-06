[CmdletBinding()]
param([Parameter(Mandatory)][string]$JsonJar,[Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputRoot){throw 'Create-new output required'}
$null=New-Item -ItemType Directory -Path $OutputRoot
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$pkg='io/github/mesmerprism/rustyquest/native_renderer'
$production=Join-Path $repo "apps/native-renderer-android/panel-modules/breath-composition/src/main/java/$pkg/ExperimentSessionGatewayProtocol.java"
$recordingTest=Join-Path $repo "apps/native-renderer-android/tests/java/$pkg/ExperimentSessionRecordingProtocolTest.java"
$test=Join-Path $repo "apps/native-renderer-android/tests/java/$pkg/ExperimentSessionGatewayProtocolTest.java"
& javac --release 8 -encoding UTF-8 -cp $JsonJar -d $OutputRoot $production $test $recordingTest
if($LASTEXITCODE-ne0){throw 'Gateway protocol compilation failed'}
& java -cp "$OutputRoot$([IO.Path]::PathSeparator)$JsonJar" io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionGatewayProtocolTest
if($LASTEXITCODE-ne0){throw 'Gateway protocol tests failed'}
& java -cp "$OutputRoot$([IO.Path]::PathSeparator)$JsonJar" io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionRecordingProtocolTest
if($LASTEXITCODE-ne0){throw 'Recording protocol route/envelope tests failed'}
& python (Join-Path $PSScriptRoot 'test_experiment_recordings.py') --repo $repo --json-jar $JsonJar
if($LASTEXITCODE-ne0){throw 'Recording export production owner controls failed'}
& python (Join-Path $PSScriptRoot 'test_experiment_gateway_bias.py') --json-jar $JsonJar
if($LASTEXITCODE-ne0){throw 'Gateway closed live-bias parser tests failed'}
& python (Join-Path $PSScriptRoot 'test_experiment_gateway_dedupe.py') --repo $repo --json-jar $JsonJar
if($LASTEXITCODE-ne0){throw 'Gateway original receipt/dedupe tests failed'}
& python (Join-Path $PSScriptRoot 'test_experiment_gateway_ble_journal.py') --repo $repo --json-jar $JsonJar
if($LASTEXITCODE-ne0){throw 'Gateway mixed BLE/network original receipt tests failed'}
& python (Join-Path $PSScriptRoot 'test_experiment_local_enrollment.py') --repo $repo --json-jar $JsonJar
if($LASTEXITCODE-ne0){throw 'Privileged local enrollment tests failed'}
& python (Join-Path $PSScriptRoot 'test_experiment_remote_join.py') --repo $repo --json-jar $JsonJar
if($LASTEXITCODE-ne0){throw 'Remote guest/provisioned identity production host tests failed'}
$jdkRoot=Split-Path -Parent (Split-Path -Parent (Get-Command javac -CommandType Application | Select-Object -First 1).Source)
& python (Join-Path $PSScriptRoot 'test_experiment_discovery.py') --jdk $jdkRoot --json-jar $JsonJar
if($LASTEXITCODE-ne0){throw 'Read-only Remote discovery production callback tests failed'}
& (Join-Path $PSScriptRoot 'Test-ExperimentHeadsetIdentityCli.ps1')
@{passed=$true;scope='Production transport authentication host tests only; Android owner/native/LAN effects require separate evidence'}|ConvertTo-Json|Set-Content (Join-Path $OutputRoot 'RESULT.json')
