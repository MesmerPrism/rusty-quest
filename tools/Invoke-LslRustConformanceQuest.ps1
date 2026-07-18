param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$BuildManifest,[string]$OutDir="")
$ErrorActionPreference="Stop";$package="io.github.mesmerprism.rustyquest.lslrustconformance";$component="$package/.RustConformanceActivity"
$manifest=Get-Content -Raw $BuildManifest|ConvertFrom-Json;$apk=Join-Path(Split-Path $BuildManifest)"rusty-lsl-rust-conformance.apk"
if((Get-FileHash -Algorithm SHA256 $apk).Hash.ToLowerInvariant()-ne$manifest.apk_sha256){throw "APK hash mismatch"}
if([string]::IsNullOrWhiteSpace($OutDir)){$OutDir=Join-Path(Split-Path $BuildManifest)("device-"+(Get-Date -Format "yyyyMMdd-HHmmss"))};New-Item -ItemType Directory -Force $OutDir|Out-Null
function Invoke-ScopedAdb([string[]]$Arguments){& adb.exe -s $Serial @Arguments;if($LASTEXITCODE-ne0){throw "adb failed: $($Arguments -join ' ')"}}
$priorPackage=(& adb -s $Serial shell pm path $package 2>&1|Out-String).Trim();$forwardBefore=(& adb -s $Serial forward --list|Out-String);$reverseBefore=(& adb -s $Serial reverse --list|Out-String);$started=(Get-Date).ToUniversalTime().ToString("yyyy-MM-dd HH:mm:ss.fff")
try{
    if(-not[string]::IsNullOrWhiteSpace($priorPackage)){throw "Distinct test package already installed; refusing to overwrite prior state."}
    Invoke-ScopedAdb @("install","-r","-d","-g",$apk);Invoke-ScopedAdb @("shell","am","start","-W","-n",$component);Start-Sleep -Seconds 3
    & adb -s $Serial exec-out run-as $package cat files/result.json | Set-Content -Encoding UTF8(Join-Path $OutDir "result.json")
    & adb -s $Serial logcat -d -v threadtime -T $started | Set-Content -Encoding UTF8(Join-Path $OutDir "logcat.txt")
    $result=Get-Content -Raw(Join-Path $OutDir "result.json")|ConvertFrom-Json;$logs=Get-Content -Raw(Join-Path $OutDir "logcat.txt")
    if($result.result-ne"pass"-or$result.native_result-ne1){throw "Native contract result failed"};if(-not$logs.Contains("RLSL005H_RUST")-or-not$logs.Contains('rusty.lsl.rust_on_quest_core_contract.v1')){throw "Rust effective marker missing"}
    $fatals=[regex]::Matches($logs,"FATAL EXCEPTION|Fatal signal|AndroidRuntime.*FATAL").Count;if($fatals-ne0){throw "Bounded fatal evidence is nonzero: $fatals"}
    [ordered]@{schema="rusty.quest.lsl_rust_conformance_device.v1";result="pass";serial=$Serial;build_manifest_sha256=(Get-FileHash -Algorithm SHA256 $BuildManifest).Hash.ToLowerInvariant();rust_effective_marker=$true;java_lifecycle_marker=$true;bounded_fatal_count=0;prior_package_present=$false;cleanup="pending"}|ConvertTo-Json|Set-Content -Encoding UTF8(Join-Path $OutDir "private-device-receipt.json")
}finally{
    & adb -s $Serial shell am force-stop $package|Out-Null;& adb -s $Serial uninstall $package|Out-Null
    $packageAfter=(& adb -s $Serial shell pm path $package 2>&1|Out-String).Trim();$pidAfter=(& adb -s $Serial shell pidof $package 2>&1|Out-String).Trim();$forwardAfter=(& adb -s $Serial forward --list|Out-String);$reverseAfter=(& adb -s $Serial reverse --list|Out-String)
    if($packageAfter-or$pidAfter-or$forwardAfter-ne$forwardBefore-or$reverseAfter-ne$reverseBefore){throw "Run-owned cleanup verification failed"}
    $receiptPath=Join-Path $OutDir "private-device-receipt.json";if(Test-Path $receiptPath){$receipt=Get-Content -Raw $receiptPath|ConvertFrom-Json;$receipt.cleanup="complete-package-process-forward-reverse-property-staging";$receipt|ConvertTo-Json|Set-Content -Encoding UTF8 $receiptPath}
}
Get-Content -Raw(Join-Path $OutDir "private-device-receipt.json")
