param(
    [Parameter(Mandatory=$true)][string]$GroupOwnerSerial,
    [Parameter(Mandatory=$true)][string]$ClientSerial,
    [Parameter(Mandatory=$true)][string]$DecisionBundlePath,
    [string]$ApkPath = "",
    [string]$EvidenceDir = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [int]$Port = 9089,
    [int]$TimeoutSeconds = 60,
    [switch]$KeepInstalled
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$package = "io.github.mesmerprism.rustyquest.directp2p"
$component = "$package/.DirectP2pProviderActivity"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
if ($GroupOwnerSerial -eq $ClientSerial) { throw "Two distinct Quest serials are required." }
if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) { throw "ADB not found: $Adb" }
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $repo "target\direct-p2p-provider-android\rusty-quest-direct-p2p-provider.apk"
}
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw "APK not found: $ApkPath" }
if (-not (Test-Path -LiteralPath $DecisionBundlePath -PathType Leaf)) { throw "Decision bundle not found: $DecisionBundlePath" }
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) {
    $EvidenceDir = Join-Path "S:\Work\tmp" ("morphospace-net009-peer-session-gate-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
}
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$runId = "net009-gate-" + (Get-Date -Format "yyyyMMddHHmmss")
$bundle = Get-Content -Raw -LiteralPath $DecisionBundlePath | ConvertFrom-Json

function Write-Text([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
}
function Invoke-Adb([string]$Serial, [string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb -s $Serial $($Arguments -join ' ') failed: $($output -join ' ')" }
    @($output)
}
function ConvertTo-ReceiptBase64($Receipt) {
    $json = $Receipt | ConvertTo-Json -Depth 16 -Compress
    [Convert]::ToBase64String($utf8NoBom.GetBytes($json))
}
function Get-P2pState([string]$Serial) {
    (Invoke-Adb $Serial @("shell","dumpsys","wifip2p")) -join "`n"
}
function Assert-Inactive([string]$Serial, [string]$Label) {
    $state = Get-P2pState $Serial
    Write-Text (Join-Path $EvidenceDir ("wifip2p-" + $Label + "-" + $Serial + ".txt")) $state
    if ($state -notmatch 'mWifiP2pInfo groupFormed: false' -or $state -notmatch 'curState=(?:InactiveState|P2pDisabledState)') {
        throw "$Label did not preserve inactive Wi-Fi Direct state on $Serial"
    }
}
function Invoke-BlockedGate($Receipt, [long]$ExpectedRevision, [string]$ExpectedReason, [string]$Label) {
    Invoke-Adb $GroupOwnerSerial @("shell","am","force-stop",$package) | Out-Null
    Invoke-Adb $GroupOwnerSerial @("logcat","-c") | Out-Null
    $encoded = ConvertTo-ReceiptBase64 $Receipt
    Invoke-Adb $GroupOwnerSerial @(
        "shell","am","start","-n",$component,
        "--es","role","group_owner","--es","run_id","$runId-$Label","--ei","port",$Port.ToString(),
        "--ez","require_peer_session_authorization","true",
        "--es","authorization_receipt_base64",$encoded,
        "--es","local_peer_id","peer.alpha",
        "--el","peer_session_authority_revision",$ExpectedRevision.ToString()) | Out-Null
    Start-Sleep -Milliseconds 900
    $log = (Invoke-Adb $GroupOwnerSerial @("logcat","-d","-s","RustyDirectP2p:V","AndroidRuntime:E","*:S")) -join "`n"
    Write-Text (Join-Path $EvidenceDir ("gate-$Label.txt")) $log
    if ($log -notmatch "phase=topology_gate status=blocked reason=$ExpectedReason") {
        throw "$Label did not emit the expected blocked gate reason $ExpectedReason"
    }
    if ($log -match 'phase=topology_request') { throw "$Label reached topology mutation after a blocked decision." }
    Assert-Inactive $GroupOwnerSerial $Label
}
function Await-Receipt([string]$Serial) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $text = (Invoke-Adb $Serial @("logcat","-d","-s","RustyDirectP2p:V","AndroidRuntime:E","*:S")) -join "`n"
        $matches = [regex]::Matches($text, 'phase=complete status=pass receipt=(\{.*\})')
        if ($matches.Count -gt 0) { return $matches[$matches.Count - 1].Groups[1].Value }
        if ($text -match 'phase=complete status=fail|FATAL EXCEPTION') { throw "Provider failed on $Serial`n$text" }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for product receipt on $Serial"
}

$serials = @($GroupOwnerSerial, $ClientSerial)
try {
    foreach ($serial in $serials) {
        if (((Invoke-Adb $serial @("get-state")) -join "").Trim() -ne "device") { throw "Quest $serial is not ready." }
        Invoke-Adb $serial @("install","-r",$ApkPath) | Out-Null
        Invoke-Adb $serial @("shell","pm","grant",$package,"android.permission.NEARBY_WIFI_DEVICES") | Out-Null
        Invoke-Adb $serial @("shell","am","force-stop",$package) | Out-Null
        Assert-Inactive $serial "preflight"
    }

    Invoke-BlockedGate $bundle.unauthenticated_authorization 1 "decision_not_authorized" "unauthenticated"
    Invoke-BlockedGate $bundle.accepted_authorization 3 "stale_authority_revision" "stale-after-revocation"
    Invoke-BlockedGate $bundle.revoked_authorization 3 "decision_not_authorized" "revoked"

    foreach ($serial in $serials) {
        Invoke-Adb $serial @("shell","am","force-stop",$package) | Out-Null
        Invoke-Adb $serial @("logcat","-c") | Out-Null
    }
    $accepted = ConvertTo-ReceiptBase64 $bundle.accepted_authorization
    Invoke-Adb $GroupOwnerSerial @(
        "shell","am","start","-n",$component,
        "--es","role","group_owner","--es","run_id","$runId-accepted","--ei","port",$Port.ToString(),
        "--ez","require_peer_session_authorization","true","--es","authorization_receipt_base64",$accepted,
        "--es","local_peer_id","peer.alpha","--el","peer_session_authority_revision","2") | Out-Null
    Start-Sleep -Seconds 2
    $ownerDump = Get-P2pState $GroupOwnerSerial
    $ownerMatch = [regex]::Match($ownerDump,'mGroup[\s\S]*?GO: Device:[\s\S]*?deviceAddress:\s*([0-9a-f:]{17})')
    if (-not $ownerMatch.Success) { throw "Accepted decision did not expose a product group-owner address." }
    $ownerAddress = $ownerMatch.Groups[1].Value
    Invoke-Adb $ClientSerial @(
        "shell","am","start","-n",$component,
        "--es","role","client","--es","target_device_address",$ownerAddress,
        "--es","run_id","$runId-accepted","--ei","port",$Port.ToString(),
        "--ez","require_peer_session_authorization","true","--es","authorization_receipt_base64",$accepted,
        "--es","local_peer_id","peer.beta","--el","peer_session_authority_revision","2") | Out-Null

    $receiptPaths = @()
    foreach ($entry in @(@{ serial=$GroupOwnerSerial; name="group-owner" }, @{ serial=$ClientSerial; name="client" })) {
        $receiptText = Await-Receipt $entry.serial
        $receiptPath = Join-Path $EvidenceDir ($entry.name + "-receipt.json")
        Write-Text $receiptPath $receiptText
        & cargo run --quiet -p rusty-quest-device-link --bin validate_product_wifi_direct_run -- $receiptPath
        if ($LASTEXITCODE -ne 0) { throw "Product receipt validation failed: $receiptPath" }
        $receiptPaths += $receiptPath
    }

    $rows = @()
    foreach ($serial in $serials) {
        $log = (Invoke-Adb $serial @("logcat","-d")) -join "`n"
        $logPath = Join-Path $EvidenceDir ("logcat-" + $serial + ".txt")
        Write-Text $logPath $log
        if ($log -notmatch 'phase=topology_gate status=accepted') { throw "Accepted topology gate marker missing on $serial" }
        Start-Sleep -Milliseconds 500
        $p2p = Get-P2pState $serial
        $inactive = $p2p -match 'mWifiP2pInfo groupFormed: false' -and $p2p -match 'curState=(?:InactiveState|P2pDisabledState)'
        $packageFatalCount = ([regex]::Matches($log,"FATAL EXCEPTION:[\s\S]{0,1200}" + [regex]::Escape($package))).Count
        $systemFatalCount = ([regex]::Matches($log,'FATAL EXCEPTION IN SYSTEM PROCESS|Watchdog.*system_server|Fatal signal.*system_server')).Count
        $rows += [ordered]@{ serial=$serial; topology_gate="accepted"; receipt_status="pass"; p2p_cleanup_inactive=$inactive; package_fatal_count=$packageFatalCount; system_fatal_count=$systemFatalCount }
    }
    if ($rows.Where({ -not $_.p2p_cleanup_inactive -or $_.package_fatal_count -ne 0 -or $_.system_fatal_count -ne 0 }).Count -ne 0) {
        throw "Accepted run cleanup or fatal gate failed; inspect $EvidenceDir"
    }
    $summary = [ordered]@{
        schema="rusty.quest.peer_session_decision_gate_two_quest_evidence.v1"; run_id=$runId; status="pass"
        coordination_mode="user_authorized_serial_scoped"; group_owner_serial=$GroupOwnerSerial; client_serial=$ClientSerial
        ble_pair_run_id=$bundle.accepted_decision.accepted_state.sessions[0].proposal.authentication.evidence_digest
        rejected_phases=@("unauthenticated","stale-after-revocation","revoked")
        accepted_authority_revision=2; media_enabled=$false; rows=$rows; receipts=$receiptPaths
    }
    $summaryPath = Join-Path $EvidenceDir "summary.json"
    Write-Text $summaryPath ($summary | ConvertTo-Json -Depth 10)
    Write-Output $summaryPath
} finally {
    foreach ($serial in $serials) {
        try { Invoke-Adb $serial @("shell","am","force-stop",$package) | Out-Null } catch {}
        if (-not $KeepInstalled) { try { Invoke-Adb $serial @("uninstall",$package) | Out-Null } catch {} }
    }
}
