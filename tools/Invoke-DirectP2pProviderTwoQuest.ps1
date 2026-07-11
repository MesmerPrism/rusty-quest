param(
    [Parameter(Mandatory=$true)] [string]$GroupOwnerSerial,
    [Parameter(Mandatory=$true)] [string]$ClientSerial,
    [string]$ApkPath = "",
    [string]$EvidenceDir = "",
    [int]$Port = 9079,
    [int]$TimeoutSeconds = 60,
    [switch]$KeepInstalled
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$package = "io.github.mesmerprism.rustyquest.directp2p"
$component = "$package/.DirectP2pProviderActivity"
$adb = (Get-Command adb -ErrorAction Stop).Source
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $repo "target\direct-p2p-provider-android\rusty-quest-direct-p2p-provider.apk"
}
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw "APK not found: $ApkPath" }
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) {
    $EvidenceDir = Join-Path "S:\Work\tmp" ("morphospace-net008-direct-p2p-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
}
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$runId = "net008-product-" + (Get-Date -Format "yyyyMMddHHmmss")

function Adb([string]$Serial, [string[]]$Arguments) {
    $output = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb -s $Serial $($Arguments -join ' ') failed: $($output -join ' ')" }
    @($output)
}

function AwaitReceipt([string]$Serial) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $text = (Adb $Serial @("logcat","-d","-s","RustyDirectP2p:V","*:S")) -join "`n"
        $matches = [regex]::Matches($text, 'phase=complete status=pass receipt=(\{.*\})')
        if ($matches.Count -gt 0) { return $matches[$matches.Count - 1].Groups[1].Value }
        if ($text -match 'phase=complete status=fail|FATAL EXCEPTION') { throw "Provider failed on $Serial`n$text" }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for product receipt on $Serial"
}

$serials = @($GroupOwnerSerial,$ClientSerial)
try {
    foreach ($serial in $serials) {
        $state = (Adb $serial @("get-state")) -join ""
        if ($state.Trim() -ne "device") { throw "Quest $serial is not ready: $state" }
        Adb $serial @("install","-r",$ApkPath) | Out-Null
        Adb $serial @("shell","pm","grant",$package,"android.permission.NEARBY_WIFI_DEVICES") | Out-Null
        Adb $serial @("shell","am","force-stop",$package) | Out-Null
        Adb $serial @("logcat","-c") | Out-Null
    }
    Adb $GroupOwnerSerial @("shell","am","start","-n",$component,"--es","role","group_owner","--es","run_id",$runId,"--ei","port",$Port.ToString()) | Out-Null
    Start-Sleep -Seconds 2
    $ownerDump = (Adb $GroupOwnerSerial @("shell","dumpsys","wifip2p")) -join "`n"
    $ownerMatch = [regex]::Match($ownerDump,'mGroup[\s\S]*?GO: Device:[\s\S]*?deviceAddress:\s*([0-9a-f:]{17})')
    if (-not $ownerMatch.Success) { throw "Product group-owner device address was not visible in wifip2p state" }
    $ownerAddress = $ownerMatch.Groups[1].Value
    Adb $ClientSerial @("shell","am","start","-n",$component,"--es","role","client","--es","target_device_address",$ownerAddress,"--es","run_id",$runId,"--ei","port",$Port.ToString()) | Out-Null

    $ownerReceiptText = AwaitReceipt $GroupOwnerSerial
    $clientReceiptText = AwaitReceipt $ClientSerial
    $ownerReceiptPath = Join-Path $EvidenceDir "group-owner-receipt.json"
    $clientReceiptPath = Join-Path $EvidenceDir "client-receipt.json"
    $ownerReceiptText | Set-Content -Encoding UTF8 -LiteralPath $ownerReceiptPath
    $clientReceiptText | Set-Content -Encoding UTF8 -LiteralPath $clientReceiptPath
    foreach ($path in @($ownerReceiptPath,$clientReceiptPath)) {
        & cargo run --quiet -p rusty-quest-device-link --bin validate_product_wifi_direct_run -- $path
        if ($LASTEXITCODE -ne 0) { throw "Product receipt validation failed: $path" }
    }

    $rows = @()
    foreach ($serial in $serials) {
        $log = (Adb $serial @("logcat","-d")) -join "`n"
        $logPath = Join-Path $EvidenceDir ("logcat-" + $serial + ".txt")
        $log | Set-Content -Encoding UTF8 -LiteralPath $logPath
        $p2p = (Adb $serial @("shell","dumpsys","wifip2p")) -join "`n"
        $p2pPath = Join-Path $EvidenceDir ("wifip2p-after-" + $serial + ".txt")
        $p2p | Set-Content -Encoding UTF8 -LiteralPath $p2pPath
        $packageFatalCount = ([regex]::Matches($log,"FATAL EXCEPTION:[\s\S]{0,1200}" + [regex]::Escape($package))).Count
        $systemFatalCount = ([regex]::Matches($log,'FATAL EXCEPTION IN SYSTEM PROCESS|Watchdog.*system_server|Fatal signal.*system_server')).Count
        $inactive = $p2p -match 'mWifiP2pInfo groupFormed: false' -and $p2p -match 'curState=InactiveState'
        $rows += [ordered]@{
            serial = $serial
            receipt_status = "pass"
            p2p_cleanup_inactive = $inactive
            package_fatal_count = $packageFatalCount
            system_fatal_count = $systemFatalCount
            logcat_path = $logPath
            wifip2p_after_path = $p2pPath
        }
    }
    if ($rows.Where({ -not $_.p2p_cleanup_inactive -or $_.package_fatal_count -ne 0 -or $_.system_fatal_count -ne 0 }).Count -ne 0) {
        throw "Device cleanup or fatal gate failed; inspect $EvidenceDir"
    }
    $summary = [ordered]@{
        schema = "rusty.quest.direct_p2p_provider_two_quest_evidence.v1"
        run_id = $runId
        status = "pass"
        group_owner_serial = $GroupOwnerSerial
        client_serial = $ClientSerial
        owner_device_address = $ownerAddress
        media_enabled = $false
        rows = $rows
        receipts = @($ownerReceiptPath,$clientReceiptPath)
    }
    $summaryPath = Join-Path $EvidenceDir "summary.json"
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $summaryPath
    Write-Output $summaryPath
} finally {
    foreach ($serial in $serials) {
        try { Adb $serial @("shell","am","force-stop",$package) | Out-Null } catch {}
        if (-not $KeepInstalled) {
            try { Adb $serial @("uninstall",$package) | Out-Null } catch {}
        }
    }
}
