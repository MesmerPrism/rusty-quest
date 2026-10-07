param([Parameter(Mandatory)][string]$Capsule, [Parameter(Mandatory)][string]$SupplierRoot, [string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$output=[IO.Path]::GetFullPath($Capsule)
$artifact=Join-Path $output 'quest-ble-control.jar'
$receipt=Get-Content -LiteralPath (Join-Path $output 'artifact.json') -Raw | ConvertFrom-Json
if((Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant() -cne $receipt.sha256){throw 'Carrier artifact does not match receipt.'}
$relative='apps/connection-hub-ble-bridge-android/tests/java/io/github/mesmerprism/rustyquest/connection_hub_ble_bridge/HubBleFramesTest.java'
$supplier=Join-Path $SupplierRoot $relative
$hash=(Get-FileHash -LiteralPath $supplier -Algorithm SHA256).Hash.ToLowerInvariant()
$text=Get-Content -LiteralPath $supplier -Raw
$declaration='package io.github.mesmerprism.rustyquest.connection_hub_ble_bridge;'
if(-not $text.StartsWith($declaration)){throw 'Supplier test namespace differs.'}
$text=$text.Replace($declaration,"$declaration`nimport io.github.mesmerprism.rustyquest.ble_control.HubBleFrames;")
$tests=Join-Path $output 'supplier-test-classes'
if(Test-Path -LiteralPath $tests){throw 'Supplier tests require a fresh capsule.'}
[void][IO.Directory]::CreateDirectory($tests)
$projection=Join-Path $tests 'HubBleFramesTest.java'
$text | Set-Content -LiteralPath $projection -Encoding utf8NoBOM
$javac=if($JavaHome){Join-Path $JavaHome 'bin/javac.exe'}else{(Get-Command javac -ErrorAction Stop).Source}
$java=if($JavaHome){Join-Path $JavaHome 'bin/java.exe'}else{(Get-Command java -ErrorAction Stop).Source}
& $javac --release 8 -encoding UTF-8 -cp $artifact -d $tests $projection
if($LASTEXITCODE -ne 0){throw 'Supplier conformance compilation failed.'}
& $java -cp "$artifact$([IO.Path]::PathSeparator)$tests" io.github.mesmerprism.rustyquest.connection_hub_ble_bridge.HubBleFramesTest
if($LASTEXITCODE -ne 0){throw 'Supplier conformance failed.'}
if((Get-FileHash -LiteralPath $supplier -Algorithm SHA256).Hash.ToLowerInvariant() -cne $hash){throw 'Supplier test changed during conformance.'}
[ordered]@{schema='rusty.quest.ble_carrier.supplier_conformance.v1';supplier_test=$relative;supplier_test_sha256=$hash;artifact_sha256=$receipt.sha256;projection='added canonical class import only';consumer_adoption=$false;device_proof=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'supplier-conformance.json') -Encoding utf8NoBOM
