param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$QuestLeaseId,
    [ValidateSet("server", "client")][string]$Mode = "server",
    [string]$RunId = "",
    [string]$SessionTag = "session-lab-001",
    [string]$PeerTag = "peer-local-001",
    [string]$SharedSecret = $env:RUSTY_QUEST_BLE_RENDEZVOUS_SECRET,
    [int]$DurationSeconds = 8,
    [ValidateSet("group_owner", "client", "either")][string]$RolePreference = "either",
    [ValidateSet("idle", "discovering", "grouped", "ready", "failed")][string]$WifiState = "idle",
    [string]$P2pIpv4 = "",
    [int]$BrokerPort = 0,
    [int]$Epoch = 1,
    [int]$TtlMs = 30000,
    [int]$Capabilities = 15,
    [string]$ApkPath = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [string]$OutDir = "",
    [switch]$SkipInstall,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$package = "io.github.mesmerprism.rustyquest.peer_rendezvous"
$activity = "$package/.PeerRendezvousActivity"
$action = "$package.START"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $modeTag = if ($Mode -eq "server") { "s" } else { "c" }
    $RunId = "ble-$modeTag-" + (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
}
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $repoRoot "target\peer-rendezvous-android\rusty-quest-peer-rendezvous.apk"
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $repoRoot "target\peer-rendezvous-runs\$RunId"
}
if ([string]::IsNullOrWhiteSpace($QuestLeaseId)) {
    throw "QuestLeaseId is required for live headset mutation."
}
function Test-SafeTag([string]$Value) {
    return $Value.Length -ge 4 -and $Value.Length -le 32 -and $Value -match '^[A-Za-z0-9._-]+$'
}
foreach ($tag in ([ordered]@{
    RunId = $RunId
    SessionTag = $SessionTag
    PeerTag = $PeerTag
}).GetEnumerator()) {
    if (-not (Test-SafeTag $tag.Value)) {
        throw "$($tag.Key) must be a 4..=32 character ephemeral safe token."
    }
}
if ([string]::IsNullOrWhiteSpace($SharedSecret) -or $SharedSecret.Length -lt 16) {
    throw "SharedSecret or RUSTY_QUEST_BLE_RENDEZVOUS_SECRET must contain at least 16 characters."
}
if ($DurationSeconds -lt 3 -or $DurationSeconds -gt 120) {
    throw "DurationSeconds must be 3..=120."
}
if (-not (Test-Path -LiteralPath $Adb)) {
    throw "ADB not found: $Adb"
}
if (-not $SkipInstall -and -not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK not found: $ApkPath"
}
if ($WifiState -in @("idle", "discovering", "failed") -and ($P2pIpv4 -or $BrokerPort -ne 0)) {
    throw "Non-grouped Wi-Fi state must not carry P2P endpoint hints."
}
if ($WifiState -eq "grouped" -and (-not $P2pIpv4 -or $BrokerPort -ne 0)) {
    throw "Grouped Wi-Fi state requires only P2pIpv4."
}
if ($WifiState -eq "ready" -and (-not $P2pIpv4 -or $BrokerPort -le 0)) {
    throw "Ready Wi-Fi state requires P2pIpv4 and BrokerPort."
}

$summary = [ordered]@{
    schema = "rusty.quest.peer_rendezvous_android_smoke.v1"
    run_id = $RunId
    serial = $Serial
    quest_lease_id = $QuestLeaseId
    mode = $Mode
    dry_run = [bool]$DryRun
    wifi_mutation_requested = $false
    raw_bluetooth_addresses_redacted = $true
    shared_secret_recorded = $false
    launch_contract = [ordered]@{
        duration_ms = $DurationSeconds * 1000
        epoch = $Epoch
        ttl_ms = $TtlMs
        role_preference = $RolePreference
        capabilities = $Capabilities
        wifi_state = $WifiState
        p2p_endpoint_hint_present = -not [string]::IsNullOrWhiteSpace($P2pIpv4)
        broker_port_hint_present = $BrokerPort -gt 0
    }
    receipt = $null
    receipt_validation = $null
    status = "planned"
}
if ($DryRun) {
    $summary.status = "dry_run"
    $summary | ConvertTo-Json -Depth 10
    return
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$receiptPath = Join-Path $OutDir "ble-rendezvous-receipt.json"
$validationPath = Join-Path $OutDir "ble-rendezvous-validation.json"
$summaryPath = Join-Path $OutDir "summary.json"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
}

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $output = & $Adb -s $Serial @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        $displayArguments = ($Arguments -join " ").Replace($SharedSecret, "<redacted>")
        $displayOutput = ($output -join [Environment]::NewLine).Replace($SharedSecret, "<redacted>")
        throw "adb -s $Serial $displayArguments failed: $displayOutput"
    }
    return [pscustomobject]@{ exit_code = $exitCode; output = @($output) }
}

try {
    Invoke-Adb @("get-state") | Out-Null
    if (-not $SkipInstall) {
        Invoke-Adb @("install", "-r", $ApkPath) | Out-Null
    }
    foreach ($permission in @(
        "android.permission.BLUETOOTH_CONNECT",
        $(if ($Mode -eq "server") { "android.permission.BLUETOOTH_ADVERTISE" } else { "android.permission.BLUETOOTH_SCAN" }),
        "android.permission.POST_NOTIFICATIONS")) {
        Invoke-Adb @("shell", "pm", "grant", $package, $permission) -AllowFailure | Out-Null
    }
    Invoke-Adb @("shell", "am", "force-stop", $package) -AllowFailure | Out-Null
    Invoke-Adb @("shell", "run-as", $package, "rm", "-f", "files/ble-rendezvous-receipt.json") -AllowFailure | Out-Null
    $launchArgs = @(
        "shell", "am", "start", "-W",
        "-n", $activity,
        "-a", $action,
        "--ez", "enabled", "true",
        "--es", "mode", $Mode,
        "--es", "run_id", $RunId,
        "--es", "session_tag", $SessionTag,
        "--es", "peer_tag", $PeerTag,
        "--es", "shared_secret", $SharedSecret,
        "--ei", "duration_ms", ($DurationSeconds * 1000).ToString(),
        "--ei", "epoch", $Epoch.ToString(),
        "--ei", "ttl_ms", $TtlMs.ToString(),
        "--es", "role_preference", $RolePreference,
        "--ei", "capabilities", $Capabilities.ToString(),
        "--es", "wifi_state", $WifiState)
    if (-not [string]::IsNullOrWhiteSpace($P2pIpv4)) {
        $launchArgs += @("--es", "p2p_ipv4", $P2pIpv4)
    }
    if ($BrokerPort -gt 0) {
        $launchArgs += @("--ei", "broker_port", $BrokerPort.ToString())
    }
    Invoke-Adb $launchArgs | Out-Null
    $receiptText = ""
    $receiptDeadline = [DateTime]::UtcNow.AddSeconds($DurationSeconds + 15)
    do {
        Start-Sleep -Milliseconds 750
        $receiptRead = Invoke-Adb @("exec-out", "run-as", $package, "cat", "files/ble-rendezvous-receipt.json") -AllowFailure
        $candidateText = ($receiptRead.output -join "`n").Trim()
        if ($candidateText.StartsWith("{")) {
            try {
                $candidate = $candidateText | ConvertFrom-Json
                if ([string]$candidate.run_id -eq $RunId) {
                    $receiptText = $candidateText
                    break
                }
            } catch {
            }
        }
    } while ([DateTime]::UtcNow -lt $receiptDeadline)
    if ([string]::IsNullOrWhiteSpace($receiptText) -or -not $receiptText.StartsWith("{")) {
        throw "BLE rendezvous app-private receipt for run $RunId did not appear before the bounded deadline."
    }
    Write-Utf8NoBom $receiptPath $receiptText
    $receipt = $receiptText | ConvertFrom-Json
    $summary.receipt = $receipt

    $validatorOutput = & cargo run --quiet -p rusty-quest-device-link --bin validate_ble_rendezvous -- receipt $receiptPath 2>&1
    $validatorExit = $LASTEXITCODE
    $validatorText = ($validatorOutput -join "`n").Trim()
    Write-Utf8NoBom $validationPath $validatorText
    if ($validatorExit -ne 0) {
        throw "BLE rendezvous receipt validation failed: $validatorText"
    }
    $summary.receipt_validation = $validatorText | ConvertFrom-Json
    $summary.status = [string]$receipt.status
} catch {
    $summary.status = "fail"
    $summary.error = $_.Exception.Message
    throw
} finally {
    Invoke-Adb @("shell", "am", "force-stop", $package) -AllowFailure | Out-Null
    Write-Utf8NoBom $summaryPath ($summary | ConvertTo-Json -Depth 20)
}

$summary | ConvertTo-Json -Depth 20
