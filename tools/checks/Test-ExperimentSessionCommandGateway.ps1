[CmdletBinding()]
param([Parameter(Mandatory)][string]$JsonJar,[Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputRoot){throw 'Create-new output required'}
$null=New-Item -ItemType Directory -Path $OutputRoot
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$pkg='io/github/mesmerprism/rustyquest/native_renderer'
$production=Join-Path $repo "apps/native-renderer-android/panel-modules/breath-composition/src/main/java/$pkg/ExperimentSessionGatewayProtocol.java"
$test=Join-Path $repo "apps/native-renderer-android/tests/java/$pkg/ExperimentSessionGatewayProtocolTest.java"
& javac --release 8 -encoding UTF-8 -cp $JsonJar -d $OutputRoot $production $test
if($LASTEXITCODE-ne0){throw 'Gateway protocol compilation failed'}
& java -cp "$OutputRoot$([IO.Path]::PathSeparator)$JsonJar" io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionGatewayProtocolTest
if($LASTEXITCODE-ne0){throw 'Gateway protocol tests failed'}
& python (Join-Path $PSScriptRoot 'test_experiment_gateway_dedupe.py') --repo $repo --json-jar $JsonJar
if($LASTEXITCODE-ne0){throw 'Gateway original receipt/dedupe tests failed'}
& python (Join-Path $PSScriptRoot 'test_experiment_gateway_ble_journal.py') --repo $repo --json-jar $JsonJar
if($LASTEXITCODE-ne0){throw 'Gateway mixed BLE/network original receipt tests failed'}
& python (Join-Path $PSScriptRoot 'test_experiment_local_enrollment.py') --repo $repo --json-jar $JsonJar
if($LASTEXITCODE-ne0){throw 'Privileged local enrollment tests failed'}
@{passed=$true;scope='Production transport authentication host tests only; Android owner/native/LAN effects require separate evidence'}|ConvertTo-Json|Set-Content (Join-Path $OutputRoot 'RESULT.json')
