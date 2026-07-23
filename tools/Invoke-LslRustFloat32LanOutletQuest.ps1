param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$RunCapsule,
    [Parameter(Mandatory=$true)][string]$HostRunner,
    [Parameter(Mandatory=$true)][string]$OutDir
)
$ErrorActionPreference="Stop"
$package="io.github.mesmerprism.rustyquest.lslrustfloat32lanoutlet"
$component="$package/.Float32LanOutletActivity"
$capsule=Get-Content -Raw $RunCapsule|ConvertFrom-Json
$root=Split-Path $RunCapsule
$apk=Join-Path $root "rusty-lsl-float32-lan-outlet.apk"
if(Test-Path $OutDir){throw "OutDir already exists: $OutDir"}
New-Item -ItemType Directory $OutDir|Out-Null
function Adb([string[]]$Args){& adb.exe -s $Serial @Args;if($LASTEXITCODE-ne0){throw "adb failed: $($Args -join ' ')"}}
if((Get-FileHash -Algorithm SHA256 $apk).Hash.ToLowerInvariant()-ne$capsule.apk_sha256){throw "APK hash mismatch"}
if((Get-FileHash -Algorithm SHA256 $HostRunner).Hash.ToLowerInvariant()-ne$capsule.host_runner_sha256){throw "Host runner hash mismatch"}
$forwardBefore=(& adb.exe -s $Serial forward --list|Out-String)
$reverseBefore=(& adb.exe -s $Serial reverse --list|Out-String)
$propertyBefore=@()
$started=(Get-Date).ToUniversalTime().ToString("yyyy-MM-dd HH:mm:ss.fff")
$receipt=[ordered]@{schema="rusty.quest.p70.device_transaction.v1";phase="sent";package=$package;property_manifest=@();staging_inputs=@();capsule_sha256=(Get-FileHash -Algorithm SHA256 $RunCapsule).Hash.ToLowerInvariant();cleanup="pending"}
$receipt|ConvertTo-Json -Depth 8|Set-Content -Encoding UTF8 (Join-Path $OutDir "transaction-sent.json")
$receipt.phase="pending";$receipt|ConvertTo-Json -Depth 8|Set-Content -Encoding UTF8 (Join-Path $OutDir "transaction-pending.json")
try {
    Adb @("install","-r",$apk)
    $packagePath=(& adb.exe -s $Serial shell pm path $package|Out-String).Trim()
    if(-not$packagePath){throw "Package readback failed"}
    Adb @("shell","am","force-stop",$package)
    Adb @("shell","am","start","-W","-n",$component)
    $ready=$false
    for($i=0;$i-lt40;$i++){Start-Sleep -Milliseconds 500;$logs=(& adb.exe -s $Serial logcat -d -v threadtime -T $started|Out-String);if($logs.Contains("RLSLP70_RUST")-and$logs.Contains("READY schema=rusty.lsl.p70.quest_outlet_ready.v1")){$ready=$true;break}}
    if(-not$ready){throw "App-authored readiness marker not observed"}
    $route=(& adb.exe -s $Serial shell ip -4 route get 192.0.2.1|Out-String)
    $match=[regex]::Match($route,'\bsrc\s+([0-9.]+)\b');if(-not$match.Success){throw "Quest LAN address readback failed"}
    & $HostRunner "$($match.Groups[1].Value):17670"|Set-Content -Encoding UTF8 (Join-Path $OutDir "host-result.json")
    if($LASTEXITCODE-ne0){throw "Host runner failed: $LASTEXITCODE"}
    Start-Sleep -Milliseconds 500
    & adb.exe -s $Serial exec-out run-as $package cat files/result.json|Set-Content -Encoding UTF8 (Join-Path $OutDir "quest-result.json")
    & adb.exe -s $Serial logcat -d -v threadtime -T $started|Set-Content -Encoding UTF8 (Join-Path $OutDir "logcat.txt")
    $host=Get-Content -Raw (Join-Path $OutDir "host-result.json")|ConvertFrom-Json
    $quest=Get-Content -Raw (Join-Path $OutDir "quest-result.json")|ConvertFrom-Json
    $logs=Get-Content -Raw (Join-Path $OutDir "logcat.txt")
    if($host.result-ne"pass"-or$quest.result-ne"pass"-or$quest.native_result-ne1){throw "Lifecycle result failed"}
    $fatals=[regex]::Matches($logs,"FATAL EXCEPTION|Fatal signal|AndroidRuntime.*FATAL").Count
    if($fatals-ne0){throw "Scoped fatal count is $fatals"}
    $receipt.phase="result";$receipt.result="pass";$receipt.package_readback=$true;$receipt.host_result_sha256=(Get-FileHash -Algorithm SHA256 (Join-Path $OutDir "host-result.json")).Hash.ToLowerInvariant();$receipt.quest_result_sha256=(Get-FileHash -Algorithm SHA256 (Join-Path $OutDir "quest-result.json")).Hash.ToLowerInvariant();$receipt.bounded_fatal_count=0
} finally {
    & adb.exe -s $Serial shell am force-stop $package|Out-Null
    $pidAfter=(& adb.exe -s $Serial shell pidof $package 2>&1|Out-String).Trim()
    $packageAfter=(& adb.exe -s $Serial shell pm path $package 2>&1|Out-String).Trim()
    $forwardAfter=(& adb.exe -s $Serial forward --list|Out-String);$reverseAfter=(& adb.exe -s $Serial reverse --list|Out-String)
    if($pidAfter-or-not$packageAfter-or$forwardAfter-ne$forwardBefore-or$reverseAfter-ne$reverseBefore){throw "Cleanup verification failed"}
    $receipt.cleanup="complete-target-force-stop-package-retained-sockets-closed-empty-property-manifest"
    $receipt.package_retained=$true;$receipt.property_manifest_restored=($propertyBefore.Count-eq0)
    $receipt|ConvertTo-Json -Depth 8|Set-Content -Encoding UTF8 (Join-Path $OutDir "private-device-receipt.json")
}
Get-Content -Raw (Join-Path $OutDir "private-device-receipt.json")
