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
function Adb([string[]]$AdbArguments){& adb.exe -s $Serial @AdbArguments;if($LASTEXITCODE-ne0){throw "adb failed: $($AdbArguments -join ' ')"}}
if((Get-FileHash -Algorithm SHA256 $apk).Hash.ToLowerInvariant()-ne$capsule.apk_sha256){throw "APK hash mismatch"}
if((Get-FileHash -Algorithm SHA256 $HostRunner).Hash.ToLowerInvariant()-ne$capsule.host_runner_sha256){throw "Host runner hash mismatch"}
$forwardBefore=(& adb.exe -s $Serial forward --list|Out-String)
$reverseBefore=(& adb.exe -s $Serial reverse --list|Out-String)
$propertyBefore=@()
$started=(Get-Date).ToUniversalTime().ToString("yyyy-MM-dd HH:mm:ss.fff")
$receipt=[ordered]@{schema="rusty.quest.p70.device_transaction.v1";phase="sent";package=$package;property_manifest=@();staging_inputs=@();capsule_sha256=(Get-FileHash -Algorithm SHA256 $RunCapsule).Hash.ToLowerInvariant();cleanup="pending"}
$receipt|ConvertTo-Json -Depth 8|Set-Content -Encoding UTF8 (Join-Path $OutDir "transaction-sent.json")
$receipt.phase="pending";$receipt|ConvertTo-Json -Depth 8|Set-Content -Encoding UTF8 (Join-Path $OutDir "transaction-pending.json")
$lifecycleCompleted=$false
try {
    Adb @("install","-r",$apk)
    $packagePath=(& adb.exe -s $Serial shell pm path $package|Out-String).Trim()
    if(-not$packagePath){throw "Package readback failed"}
    Adb @("shell","am","force-stop",$package)
    Adb @("shell","am","start","-W","-n",$component)
    $ready=$false
    for($i=0;$i-lt40;$i++){Start-Sleep -Milliseconds 500;$logs=(& adb.exe -s $Serial logcat -d -v threadtime -T $started|Out-String);if($logs.Contains("RLSLP70_RUST")-and$logs.Contains("READY schema=rusty.lsl.p70.quest_outlet_ready.v2 self_probe=true stage=responder-ready")){$ready=$true;break}}
    if(-not$ready){throw "App-authored readiness marker not observed"}
    $route=(& adb.exe -s $Serial shell ip -4 route get 192.0.2.1|Out-String)
    $match=[regex]::Match($route,'\bsrc\s+([0-9.]+)\b');if(-not$match.Success){throw "Quest LAN address readback failed"}
    & $HostRunner "$($match.Groups[1].Value):17670"|Set-Content -Encoding UTF8 (Join-Path $OutDir "host-result.json")
    if($LASTEXITCODE-ne0){throw "Host runner failed: $LASTEXITCODE"}
    Start-Sleep -Milliseconds 500
    & adb.exe -s $Serial exec-out run-as $package cat files/result.json|Set-Content -Encoding UTF8 (Join-Path $OutDir "quest-result.json")
    & adb.exe -s $Serial logcat -d -v threadtime -T $started|Set-Content -Encoding UTF8 (Join-Path $OutDir "logcat.txt")
    $hostResult=Get-Content -Raw (Join-Path $OutDir "host-result.json")|ConvertFrom-Json
    $quest=Get-Content -Raw (Join-Path $OutDir "quest-result.json")|ConvertFrom-Json
    $logs=Get-Content -Raw (Join-Path $OutDir "logcat.txt")
    if($hostResult.result-ne"pass"-or$quest.result-ne"pass"-or$quest.native_result-ne1){throw "Lifecycle result failed"}
    $fatals=[regex]::Matches($logs,"FATAL EXCEPTION|Fatal signal|AndroidRuntime.*FATAL").Count
    if($fatals-ne0){throw "Scoped fatal count is $fatals"}
    $receipt.phase="result";$receipt.result="pass";$receipt.package_readback=$true;$receipt.host_result_sha256=(Get-FileHash -Algorithm SHA256 (Join-Path $OutDir "host-result.json")).Hash.ToLowerInvariant();$receipt.quest_result_sha256=(Get-FileHash -Algorithm SHA256 (Join-Path $OutDir "quest-result.json")).Hash.ToLowerInvariant();$receipt.bounded_fatal_count=0
    $lifecycleCompleted=$true
} finally {
    try {
        $responderMarker="RESPONDER schema=rusty.lsl.p70.quest_responder_result.v1"
        $responderMarkerObserved=$false
        $responderWaitElapsedMilliseconds=0
        if(-not$lifecycleCompleted){
            $responderWaitStopwatch=[Diagnostics.Stopwatch]::StartNew()
            $responderWaitDeadline=[DateTime]::UtcNow.AddSeconds(12)
            while([DateTime]::UtcNow-lt$responderWaitDeadline){
                $markerLogs=(& adb.exe -s $Serial logcat -d -v threadtime -T $started|Out-String)
                if($markerLogs.Contains($responderMarker)){$responderMarkerObserved=$true;break}
                Start-Sleep -Milliseconds 250
            }
            $responderWaitStopwatch.Stop()
            $responderWaitElapsedMilliseconds=$responderWaitStopwatch.ElapsedMilliseconds
        }
        $logcatPath=Join-Path $OutDir "logcat.txt"
        & adb.exe -s $Serial logcat -d -v threadtime -T $started|Set-Content -Encoding UTF8 $logcatPath
        if(Test-Path $logcatPath){
            $boundedLogs=Get-Content -Raw $logcatPath
            $responderPattern='RESPONDER schema=rusty\.lsl\.p70\.quest_responder_result\.v1 result=(pass|fail) requests=([0-9]+) termination=(cancelled|deadline|request-limit|error)'
            $responderMatches=[regex]::Matches($boundedLogs,$responderPattern)
            $responderMarkerObserved=($responderMatches.Count-eq1)
            $receipt.responder_marker_observed=$responderMarkerObserved
            $receipt.responder_marker_wait_budget_seconds=if($lifecycleCompleted){0}else{12}
            $receipt.responder_marker_wait_elapsed_milliseconds=if($lifecycleCompleted){0}else{$responderWaitElapsedMilliseconds}
            if($responderMarkerObserved){
                $receipt.responder_result=$responderMatches[0].Groups[1].Value
                $receipt.responder_requests=[System.UInt64]$responderMatches[0].Groups[2].Value
                $receipt.responder_termination=$responderMatches[0].Groups[3].Value
            }
            $responderDetailPattern='RESPONDER_DETAIL schema=rusty\.lsl\.p70\.quest_responder_detail\.v1 outcome=(run-ok|responder-error|thread-panic) error=(none|non-loopback-interface|non-concrete-interface|bind|join-multicast|local-address|receive-timeout|receive|send|datagram-limit|invalid-query|response|allocation|probe-length-overflow|partial-send)'
            $responderDetailMatches=[regex]::Matches($boundedLogs,$responderDetailPattern)
            $receipt.responder_detail_observed=($responderDetailMatches.Count-eq1)
            if($receipt.responder_detail_observed){
                $receipt.responder_outcome=$responderDetailMatches[0].Groups[1].Value
                $receipt.responder_error_kind=$responderDetailMatches[0].Groups[2].Value
            }
            $receipt.bounded_logcat_sha256=(Get-FileHash -Algorithm SHA256 $logcatPath).Hash.ToLowerInvariant()
            $receipt.bounded_fatal_count=[regex]::Matches($boundedLogs,"FATAL EXCEPTION|Fatal signal|AndroidRuntime.*FATAL").Count
            $receipt.failure_path_evidence_preserved=$true
        }
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
}
$responderAccepted=$receipt.responder_marker_observed-and
    $receipt.responder_detail_observed-and
    $receipt.responder_result-eq"pass"-and
    $receipt.responder_requests-eq[System.UInt64]2-and
    $receipt.responder_termination-eq"request-limit"-and
    $receipt.responder_outcome-eq"run-ok"-and
    $receipt.responder_error_kind-eq"none"
if(-not$lifecycleCompleted-or-not$responderAccepted){throw "Typed responder lifecycle validation failed"}
$receipt.phase="confirmed"
$receipt.confirmed=$true
$receipt|ConvertTo-Json -Depth 8|Set-Content -Encoding UTF8 (Join-Path $OutDir "private-device-receipt.json")
Get-Content -Raw (Join-Path $OutDir "private-device-receipt.json")
