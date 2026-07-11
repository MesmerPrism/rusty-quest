param(
    [Parameter(Mandatory = $true)][string[]]$Serial,
    [string]$BrokerApk = "",
    [string]$ClientBuildManifest = "",
    [string]$EvidenceDir = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$utf8 = [Text.UTF8Encoding]::new($false)
if (@($Serial | Select-Object -Unique).Count -ne 2) { throw "Exactly two distinct Quest serials are required." }
if (-not $BrokerApk) { $BrokerApk = Join-Path $repo "target\manifold-broker-android\rusty-manifold-broker.apk" }
if (-not $ClientBuildManifest) { $ClientBuildManifest = Join-Path $repo "target\broker-admission-clients\build-manifest.json" }
if (-not $EvidenceDir) { $EvidenceDir = Join-Path "S:\Work\tmp" ("morphospace-rel001-admission-death-" + (Get-Date -Format "yyyyMMdd-HHmmss")) }
foreach ($path in @($Adb, $BrokerApk, $ClientBuildManifest)) { if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing suite input: $path" } }
$clientBuild = Get-Content -LiteralPath $ClientBuildManifest -Raw | ConvertFrom-Json
$authorizedApk = [string]$clientBuild.authorized.apk_path
$unauthorizedApk = [string]$clientBuild.unauthorized.apk_path
foreach ($path in @($authorizedApk, $unauthorizedApk)) { if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing admission client APK: $path" } }
New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null

$broker = "io.github.mesmerprism.rustymanifold.broker"
$authorized = "io.github.mesmerprism.rustymanifold.admission.client"
$unauthorized = "io.github.mesmerprism.rustymanifold.admission.untrusted"
$activity = "io.github.mesmerprism.rustymanifold.admission_client.AdmissionClientActivity"
$acceptedMarker = "RUSTY_QUEST_BROKER_ADMISSION_CLIENT status=accepted variant=authorized issueApplied=true useApplied=true replayReason=replayed_request revokeApplied=true postRevokeReason=token_revoked"
$rejectedMarker = "RUSTY_QUEST_BROKER_ADMISSION_CLIENT status=unauthorized-rejected variant=unauthorized reason=signature-permission"

function Write-Text([string]$Path, [string]$Text) { [IO.File]::WriteAllText($Path, $Text, $utf8) }
function Invoke-Adb([string]$Device, [string[]]$Arguments, [switch]$AllowFailure) {
    $previous = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try { $output = @(& $Adb -s $Device @Arguments 2>&1); $code = $LASTEXITCODE } finally { $ErrorActionPreference = $previous }
    if ($code -ne 0 -and -not $AllowFailure) { throw "adb -s $Device $($Arguments -join ' ') failed: $($output -join ' ')" }
    @($output)
}
function Wait-Marker([string]$Device, [string]$Marker, [int]$Seconds = 20) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        $log = (Invoke-Adb $Device @("logcat", "-d", "-v", "threadtime")) -join "`n"
        if ($log -match [regex]::Escape($Marker)) { return $log }
        if ($log -match "FATAL EXCEPTION") { throw "Admission client fatal on $Device`n$log" }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for admission marker on $Device"
}
function Start-Admission([string]$Device, [string]$Package, [string]$Marker, [long]$ExpectedRevision = 1) {
    Invoke-Adb $Device @("shell", "am", "start", "-n", "$Package/$activity", "--el", "expected_authority_revision", $ExpectedRevision.ToString()) | Out-Null
    Wait-Marker $Device $Marker
}
function Wait-SignatureGrant([string]$Device, [int]$Seconds = 15) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        $dump = (Invoke-Adb $Device @("shell", "dumpsys", "package", $authorized)) -join "`n"
        if ($dump -match 'io\.github\.mesmerprism\.rustymanifold\.permission\.BROKER_ADMISSION:\s+granted=true') { return }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Authorized client signature permission did not settle on $Device"
}

$rows = @()
foreach ($device in $Serial) {
    $deviceDir = Join-Path $EvidenceDir $device
    New-Item -ItemType Directory -Path $deviceDir -Force | Out-Null
    $issues = New-Object System.Collections.Generic.List[string]
    $cleanup = $false
    try {
        if (((Invoke-Adb $device @("get-state")) -join "").Trim() -ne "device") { throw "Quest $device is not ready." }
        foreach ($package in @($authorized, $unauthorized, $broker)) { Invoke-Adb $device @("uninstall", $package) -AllowFailure | Out-Null }
        foreach ($apk in @($BrokerApk, $authorizedApk, $unauthorizedApk)) { Invoke-Adb $device @("install", "-r", $apk) | Out-Null }
        Wait-SignatureGrant $device

        Invoke-Adb $device @("logcat", "-c") | Out-Null
        $epochA = Start-Admission $device $authorized $acceptedMarker 1
        Start-Admission $device $unauthorized $rejectedMarker | Out-Null
        Write-Text (Join-Path $deviceDir "epoch-a.log") ((Invoke-Adb $device @("logcat", "-d")) -join "`n")

        Invoke-Adb $device @("shell", "am", "force-stop", $authorized) | Out-Null
        Invoke-Adb $device @("logcat", "-c") | Out-Null
        $clientRebind = Start-Admission $device $authorized $acceptedMarker 4
        Write-Text (Join-Path $deviceDir "epoch-a-client-rebind.log") $clientRebind

        Invoke-Adb $device @("shell", "am", "force-stop", $authorized) -AllowFailure | Out-Null
        Invoke-Adb $device @("shell", "am", "force-stop", $unauthorized) -AllowFailure | Out-Null
        Invoke-Adb $device @("shell", "am", "force-stop", $broker) | Out-Null
        Start-Sleep -Milliseconds 750
        if (-not [string]::IsNullOrWhiteSpace(((Invoke-Adb $device @("shell", "pidof", $broker) -AllowFailure) -join ""))) { $issues.Add("broker_process_survived_force_stop") }

        Invoke-Adb $device @("logcat", "-c") | Out-Null
        Invoke-Adb $device @("shell", "am", "start", "-n", "$broker/.BrokerStartActivity") | Out-Null
        Start-Sleep -Milliseconds 750
        $rebuiltAuthorized = Start-Admission $device $authorized $acceptedMarker 1
        $rebuiltUnauthorized = Start-Admission $device $unauthorized $rejectedMarker
        $rebuiltLog = (Invoke-Adb $device @("logcat", "-d")) -join "`n"
        Write-Text (Join-Path $deviceDir "epoch-b-rebuilt.log") $rebuiltLog
        if ($rebuiltAuthorized -notmatch [regex]::Escape($acceptedMarker)) { $issues.Add("rebuilt_authorized_lifecycle_missing") }
        if ($rebuiltUnauthorized -notmatch [regex]::Escape($rejectedMarker)) { $issues.Add("rebuilt_unauthorized_rejection_missing") }
        $packageFatals = ([regex]::Matches($rebuiltLog, 'FATAL EXCEPTION:[\s\S]{0,1200}(rustymanifold|admission_client)')).Count
        $systemFatals = ([regex]::Matches($rebuiltLog, 'FATAL EXCEPTION IN SYSTEM PROCESS|Watchdog.*system_server|Fatal signal.*system_server')).Count
        if ($packageFatals -ne 0) { $issues.Add("package_fatal_present") }
        if ($systemFatals -ne 0) { $issues.Add("system_fatal_present") }
    } finally {
        foreach ($package in @($authorized, $unauthorized, $broker)) {
            Invoke-Adb $device @("shell", "am", "force-stop", $package) -AllowFailure | Out-Null
            Invoke-Adb $device @("uninstall", $package) -AllowFailure | Out-Null
        }
        $packages = (Invoke-Adb $device @("shell", "pm", "list", "packages")) -join "`n"
        $cleanup = @(@($authorized, $unauthorized, $broker) | Where-Object { $packages -match [regex]::Escape($_) }).Count -eq 0
        if (-not $cleanup) { $issues.Add("cleanup_packages_remain") }
    }
    $rows += [pscustomobject][ordered]@{
        serial = $device; status = if ($issues.Count -eq 0) { "pass" } else { "fail" }
        authorized_issue_use_replay_revoke = -not $issues.Contains("rebuilt_authorized_lifecycle_missing")
        unauthorized_signature_rejection = -not $issues.Contains("rebuilt_unauthorized_rejection_missing")
        client_death_rebind = $issues.Count -eq 0; provider_death_fresh_epoch_rebuild = $issues.Count -eq 0
        package_fatal_count = @($issues | Where-Object { $_ -eq "package_fatal_present" }).Count
        system_fatal_count = @($issues | Where-Object { $_ -eq "system_fatal_present" }).Count
        cleanup_complete = $cleanup; issues = @($issues.ToArray()); evidence_dir = $deviceDir
    }
}
$summary = [ordered]@{
    schema = "rusty.quest.broker_admission_death_recovery_two_quest_evidence.v1"
    status = if (@($rows | Where-Object { $_.status -ne "pass" }).Count -eq 0) { "pass" } else { "fail" }
    coordination_mode = "user_authorized_serial_scoped"; device_count = 2
    authority_recovery = "explicit_fresh_epoch_rebuild_not_in_memory_persistence"
    phases = @("authorized-lifecycle", "unauthorized-signature-rejection", "client-death-rebind", "provider-death", "fresh-epoch-rebuild", "cleanup")
    rows = $rows
}
$summaryPath = Join-Path $EvidenceDir "summary.json"
Write-Text $summaryPath ($summary | ConvertTo-Json -Depth 16)
Write-Output $summaryPath
if ($summary.status -ne "pass") { exit 1 }
