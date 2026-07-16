$ErrorActionPreference="Stop"
$root=(Resolve-Path(Join-Path $PSScriptRoot "..")).Path;$app=Join-Path $root "apps\lsl-multicast-conformance-android"
$manifest=Get-Content -Raw(Join-Path $app "AndroidManifest.xml");$source=Get-Content -Raw(Join-Path $app "src\main\java\io\github\mesmerprism\rustyquest\lslmulticastconformance\MulticastConformanceActivity.java")
foreach($needle in @("239.255.172.215","16571","joinGroup","leaveGroup","setSoTimeout","CHANGE_WIFI_MULTICAST_STATE","RLSL004G","cleanup_socket_closed","cleanup_multicast_lock_released")){if(-not($manifest.Contains($needle)-or$source.Contains($needle))){throw "Missing bounded harness token: $needle"}}
foreach($forbidden in @("Makepad","MANAGE_EXTERNAL_STORAGE","android.permission.CAMERA","android.permission.RECORD_AUDIO","ManifoldRuntime")){if($manifest.Contains($forbidden)-or$source.Contains($forbidden)){throw "Forbidden harness token: $forbidden"}}
"LSLC-004G Rusty Quest harness static validation passed."
