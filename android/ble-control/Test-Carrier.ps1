param([Parameter(Mandatory)][string]$Capsule, [string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$output=[IO.Path]::GetFullPath($Capsule)
$artifact=Join-Path $output 'quest-ble-control.jar'
$receipt=Get-Content -LiteralPath (Join-Path $output 'artifact.json') -Raw | ConvertFrom-Json
if((Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant() -cne $receipt.sha256){throw 'Carrier artifact does not match receipt.'}
$javac=if($JavaHome){Join-Path $JavaHome 'bin/javac.exe'}else{(Get-Command javac -ErrorAction Stop).Source}
$java=if($JavaHome){Join-Path $JavaHome 'bin/java.exe'}else{(Get-Command java -ErrorAction Stop).Source}
$tests=Join-Path $output 'test-classes'
if(Test-Path -LiteralPath $tests){throw 'Tests require a fresh capsule.'}
[void][IO.Directory]::CreateDirectory($tests)
$sources=@('HubBleFramesTest.java','GattPeerTest.java'|ForEach-Object {Join-Path $PSScriptRoot ('tests/java/io/github/mesmerprism/rustyquest/ble_control/'+$_)})
& $javac --release 8 -encoding UTF-8 -cp $artifact -d $tests @sources
if($LASTEXITCODE -ne 0){throw 'Packaged carrier conformance compilation failed.'}
& $java -cp "$artifact$([IO.Path]::PathSeparator)$tests" io.github.mesmerprism.rustyquest.ble_control.HubBleFramesTest
if($LASTEXITCODE -ne 0){throw 'Packaged carrier conformance failed.'}

& $java -cp "$artifact$([IO.Path]::PathSeparator)$tests" io.github.mesmerprism.rustyquest.ble_control.GattPeerTest
if($LASTEXITCODE -ne 0){throw 'Packaged peer lifetime conformance failed.'}
