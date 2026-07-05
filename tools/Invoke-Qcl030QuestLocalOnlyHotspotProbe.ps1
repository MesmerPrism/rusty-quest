param(
    [string]$Serial = "",
    [string]$RunId = "",
    [string]$OutDir = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [string]$Qcl041Apk = "S:\Work\repos\active\rusty-quest\target\qcl041-wifi-direct-harness-android\rusty-quest-qcl041-wifi-direct-harness.apk",
    [int]$HoldSeconds = 60,
    [int]$Port = 19068,
    [int]$SocketBytes = 65536,
    [int]$SocketTimeoutSeconds = 15,
    [int]$TimeoutSeconds = 90,
    [switch]$RunClientJoinMatrix,
    [string]$OwnerSerial = "",
    [string]$ClientSerial = "",
    [string]$OwnerHost = "192.168.43.1",
    [int]$ClientLaunchDelaySeconds = 0,
    [switch]$LaunchClientViaActivity,
    [ValidateSet("NetworkSpecifier", "ActiveWifi")]
    [string]$ClientJoinMode = "NetworkSpecifier",
    [switch]$RequireActiveWifiSsidMatch,
    [switch]$HostJoinClientWithWifiSuggestion,
    [int]$HostJoinWaitSeconds = 30,
    [switch]$AutoApproveClientNetworkRequest,
    [int]$AutoApprovePollSeconds = 30,
    [switch]$PreflightOnly,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"

$Qcl041Package = "io.github.mesmerprism.rustyquest.qcl041"
$Qcl041Activity = "io.github.mesmerprism.rustyquest.qcl041/.Qcl041WifiDirectHarnessActivity"
$Qcl041Service = "io.github.mesmerprism.rustyquest.qcl041/.Qcl041WifiDirectHarnessService"
$ClientJoinModeToken = if ($ClientJoinMode -eq "ActiveWifi") { "active_wifi" } else { "network_specifier" }
if ($HostJoinClientWithWifiSuggestion -and $ClientJoinModeToken -ne "active_wifi") {
    throw "-HostJoinClientWithWifiSuggestion requires -ClientJoinMode ActiveWifi."
}

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl030-local-only-hotspot-" + (Get-Date -Format "yyyyMMddTHHmmssZ").ToString()
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path "S:\Work\repos\active\rusty-quest\target" $RunId
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Write-Qcl030JsonFile {
    param(
        [Parameter(Mandatory=$true)]
        [object]$Value,
        [Parameter(Mandatory=$true)]
        [string]$Path
    )
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $json = ($Value | ConvertTo-Json -Depth 100) + "`n"
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

function Get-Qcl030UtcNow {
    return (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
}

function Get-Qcl030ArtifactFileName {
    param([Parameter(Mandatory=$true)][string]$Value)
    return ($Value -replace '[^A-Za-z0-9._-]', '_') + ".json"
}

function Resolve-Qcl030Serial {
    param([string]$RequestedSerial)
    if (-not [string]::IsNullOrWhiteSpace($RequestedSerial)) {
        return $RequestedSerial
    }
    $devicesOutput = & $Adb devices 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "adb devices failed with exit code $LASTEXITCODE. $devicesOutput"
    }
    $candidates = @()
    foreach ($line in ($devicesOutput -split "`r?`n")) {
        if ($line -match '^([^\s]+)\s+device$') {
            $candidates += $Matches[1]
        }
    }
    if ($candidates.Count -eq 1) {
        return $candidates[0]
    }
    if ($candidates.Count -gt 1) {
        throw "Multiple adb devices are online; pass -Serial explicitly. Online serials: $($candidates -join ', ')"
    }
    throw "No adb device is online; pass -Serial after connecting a Quest headset."
}

function Invoke-Qcl030AdbProbe {
    param(
        [Parameter(Mandatory=$true)]
        [string[]]$Arguments,
        [string]$Name = "adb probe",
        [string]$TargetSerial = $Serial
    )
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $Adb -s $TargetSerial @Arguments 2>&1 | Out-String
        [ordered]@{
            name = $Name
            serial = $TargetSerial
            arguments = ($Arguments -join " ")
            exit_code = $LASTEXITCODE
            output = $output.Trim()
        }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Invoke-Qcl030AdbText {
    param(
        [Parameter(Mandatory=$true)]
        [string[]]$Arguments,
        [string]$Name = "adb",
        [string]$TargetSerial = $Serial
    )
    $probe = Invoke-Qcl030AdbProbe -Arguments $Arguments -Name $Name -TargetSerial $TargetSerial
    if ($probe.exit_code -ne 0) {
        throw "$Name failed for ${TargetSerial} with exit code $($probe.exit_code). $($probe.output)"
    }
    return [string]$probe.output
}

function Get-Qcl030Preflight {
    param([string]$TargetSerial = $Serial)
    $wifiStatus = Invoke-Qcl030AdbProbe `
        -Arguments @("shell", "cmd", "wifi", "status") `
        -Name "cmd wifi status" `
        -TargetSerial $TargetSerial
    $features = Invoke-Qcl030AdbProbe `
        -Arguments @("shell", "pm", "list", "features") `
        -Name "pm list features" `
        -TargetSerial $TargetSerial
    $ipAddr = Invoke-Qcl030AdbProbe `
        -Arguments @("shell", "ip", "addr", "show") `
        -Name "ip addr show" `
        -TargetSerial $TargetSerial
    $model = Invoke-Qcl030AdbProbe `
        -Arguments @("shell", "getprop", "ro.product.model") `
        -Name "getprop ro.product.model" `
        -TargetSerial $TargetSerial
    [ordered]@{
        wifi_status = $wifiStatus
        pm_list_features = $features
        feature_wifi_present = [bool]([string]$features.output -match 'android\.hardware\.wifi')
        feature_wifi_direct_present = [bool]([string]$features.output -match 'android\.hardware\.wifi\.direct')
        ip_addr = $ipAddr
        model = $model
    }
}

function Test-Qcl030PermissionDeclared {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Dumpsys,
        [Parameter(Mandatory=$true)]
        [string]$Permission
    )
    return [bool]($Dumpsys -match [regex]::Escape($Permission))
}

function Get-Qcl030PermissionReadback {
    param(
        [Parameter(Mandatory=$true)][string]$Permission,
        [string]$TargetSerial = $Serial
    )
    $probe = Invoke-Qcl030AdbProbe `
        -Arguments @("shell", "dumpsys", "package", $Qcl041Package) `
        -Name "dumpsys package permission readback" `
        -TargetSerial $TargetSerial
    $escapedPermission = [regex]::Escape($Permission)
    $grantedMatch = [regex]::Match([string]$probe.output, "$escapedPermission\s*:\s*granted=(true|false)")
    $grantStateFound = $grantedMatch.Success
    $granted = $false
    if ($grantStateFound) {
        $granted = $grantedMatch.Groups[1].Value -eq "true"
    }
    [ordered]@{
        permission = $Permission
        method = "dumpsys package"
        exit_code = $probe.exit_code
        declared = Test-Qcl030PermissionDeclared -Dumpsys ([string]$probe.output) -Permission $Permission
        grant_state_found = $grantStateFound
        granted = $granted
    }
}

function Invoke-Qcl030PermissionPreflight {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [string]$TargetSerial = $Serial
    )
    $sdkProbe = Invoke-Qcl030AdbProbe `
        -Arguments @("shell", "getprop", "ro.build.version.sdk") `
        -Name "getprop ro.build.version.sdk" `
        -TargetSerial $TargetSerial
    $sdk = 0
    [void][int]::TryParse(([string]$sdkProbe.output).Trim(), [ref]$sdk)
    $runtimeWifiPermission = if ($sdk -ge 33) {
        "android.permission.NEARBY_WIFI_DEVICES"
    } else {
        "android.permission.ACCESS_FINE_LOCATION"
    }
    $runtimePermissions = @($runtimeWifiPermission)
    if ($sdk -ge 33) {
        $runtimePermissions += "android.permission.POST_NOTIFICATIONS"
    }
    $requiredPermissions = @(
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.INTERNET",
        $runtimeWifiPermission
    )
    if ($sdk -ge 33) {
        $requiredPermissions += "android.permission.POST_NOTIFICATIONS"
        $requiredPermissions += "android.permission.FOREGROUND_SERVICE"
        $requiredPermissions += "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
    }
    $dumpsys = Invoke-Qcl030AdbProbe `
        -Arguments @("shell", "dumpsys", "package", $Qcl041Package) `
        -Name "dumpsys package QCL-041 shared APK" `
        -TargetSerial $TargetSerial
    $summary = [ordered]@{
        '$schema' = "rusty.quest.qcl030_android_permission_preflight.v1"
        schema_version = 1
        run_id = $RunId
        package = $Qcl041Package
        serial = $TargetSerial
        observed_at_utc = Get-Qcl030UtcNow
        sdk = $sdk
        dumpsys_exit_code = $dumpsys.exit_code
        required_permissions = @()
        runtime_grants = @()
        permission_ready = $true
    }
    foreach ($permission in $requiredPermissions) {
        $declared = Test-Qcl030PermissionDeclared -Dumpsys ([string]$dumpsys.output) -Permission $permission
        $readback = Get-Qcl030PermissionReadback -Permission $permission -TargetSerial $TargetSerial
        $summary.required_permissions += [ordered]@{
            permission = $permission
            declared = $declared
            check_permission = $readback
        }
        if (-not $declared) {
            $summary.permission_ready = $false
        }
    }
    foreach ($permission in $runtimePermissions) {
        $grant = Invoke-Qcl030AdbProbe `
            -Arguments @("shell", "pm", "grant", $Qcl041Package, $permission) `
            -Name "pm grant runtime permission" `
            -TargetSerial $TargetSerial
        $readback = Get-Qcl030PermissionReadback -Permission $permission -TargetSerial $TargetSerial
        $summary.runtime_grants += [ordered]@{
            permission = $permission
            pm_grant_exit_code = $grant.exit_code
            pm_grant_output = $grant.output
            check_permission = $readback
        }
        if ($readback.granted -ne $true) {
            $summary.permission_ready = $false
        }
    }
    Write-Qcl030JsonFile -Value $summary -Path $Path
    return $summary
}

function New-Qcl030Summary {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Status,
        [string]$BlockedReason = "",
        [object]$Preflight = $null,
        [object]$Permission = $null,
        [object]$Launch = $null,
        [object]$Artifact = $null
    )
    $hotspot = [ordered]@{
        hotspot_started = $false
        ssid_present = $false
        passphrase_present = $false
        cleanup_completed = $false
        client_join_and_socket_matrix_pending = $true
    }
    if ($null -ne $Artifact -and $null -ne $Artifact.local_only_hotspot) {
        $hotspot.hotspot_started = [bool]$Artifact.local_only_hotspot.started
        $hotspot.ssid_present = -not [string]::IsNullOrWhiteSpace([string]$Artifact.local_only_hotspot.ssid)
        $hotspot.passphrase_present = -not [string]::IsNullOrWhiteSpace([string]$Artifact.local_only_hotspot.passphrase)
        $hotspot.client_join_and_socket_matrix_pending =
                [bool]$Artifact.local_only_hotspot.client_join_and_socket_matrix_pending
    }
    if ($null -ne $Artifact -and $null -ne $Artifact.cleanup) {
        $hotspot.cleanup_completed = [bool]$Artifact.cleanup.completed
    }
    [ordered]@{
        '$schema' = "rusty.quest.qcl030_local_only_hotspot_probe_run.v1"
        schema_version = 1
        run_id = $RunId
        status = $Status
        blocked_reason = $BlockedReason
        observed_at_utc = Get-Qcl030UtcNow
        host_wrapper = [ordered]@{
            script = "Invoke-Qcl030QuestLocalOnlyHotspotProbe.ps1"
            adb = $Adb
            preflight_only = [bool]$PreflightOnly
            skip_install = [bool]($SkipInstall -or $PreflightOnly)
        }
        device = [ordered]@{
            serial = $Serial
        }
        topology = [ordered]@{
            owner = "quest_local_only_hotspot"
            network_provider = "quest_local_only_hotspot"
            external_wifi_provider_required = $false
            endpoint_direction = "quest_hosted_local_ap"
            requires_android_network_binding = $true
            receiver_observed_bytes_required_before_media = $true
        }
        preflight = $Preflight
        permission_preflight = [ordered]@{
            path = if ($null -eq $Permission) { "" } else { Join-Path $OutDir "qcl030-permission-preflight.json" }
            schema = if ($null -eq $Permission) { "" } else { [string]$Permission.'$schema' }
            permission_ready = if ($null -eq $Permission) { $false } else { [bool]$Permission.permission_ready }
        }
        launch = $Launch
        local_only_hotspot = $hotspot
        artifact = [ordered]@{
            path = if ($null -eq $Artifact) { "" } else { Join-Path $OutDir "qcl030-local-only-hotspot-artifact.json" }
            status = if ($null -eq $Artifact) { "" } else { [string]$Artifact.status }
            blocked_reason = if ($null -eq $Artifact) { "" } else { [string]$Artifact.blocked_reason }
            credential_sensitive = if ($null -eq $Artifact -or $null -eq $Artifact.local_only_hotspot) {
                $true
            } else {
                [bool]$Artifact.local_only_hotspot.credential_sensitive
            }
        }
        pass_condition = "hotspot_started_and_reservation_closed_cleanly"
        next_gate = "client_join_and_socket_matrix_pending"
    }
}

function Wait-Qcl030Artifact {
    param(
        [int]$WaitSeconds,
        [string]$TargetSerial = $Serial
    )
    $artifactName = Get-Qcl030ArtifactFileName -Value $RunId
    $remotePath = "files/qcl030/$artifactName"
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $probe = Invoke-Qcl030AdbProbe `
            -Arguments @("shell", "run-as", $Qcl041Package, "cat", $remotePath) `
            -Name "run-as cat files/qcl030" `
            -TargetSerial $TargetSerial
        if ($probe.exit_code -eq 0 -and -not [string]::IsNullOrWhiteSpace($probe.output)) {
            try {
                $artifact = $probe.output | ConvertFrom-Json
                if ($artifact.status -eq "pass" -or $artifact.status -eq "blocked") {
                    return $artifact
                }
            } catch {
            }
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $null
}

function Wait-Qcl030OwnerCredentialArtifact {
    param(
        [int]$WaitSeconds,
        [string]$TargetSerial = $Serial
    )
    $artifactName = Get-Qcl030ArtifactFileName -Value $RunId
    $remotePath = "files/qcl030/$artifactName"
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $probe = Invoke-Qcl030AdbProbe `
            -Arguments @("shell", "run-as", $Qcl041Package, "cat", $remotePath) `
            -Name "run-as cat files/qcl030 owner credential artifact" `
            -TargetSerial $TargetSerial
        if ($probe.exit_code -eq 0 -and -not [string]::IsNullOrWhiteSpace($probe.output)) {
            try {
                $artifact = $probe.output | ConvertFrom-Json
                if ($artifact.status -eq "blocked") {
                    return $artifact
                }
                if ($artifact.local_only_hotspot.started -eq $true `
                        -and -not [string]::IsNullOrWhiteSpace([string]$artifact.local_only_hotspot.ssid) `
                        -and -not [string]::IsNullOrWhiteSpace([string]$artifact.local_only_hotspot.passphrase)) {
                    return $artifact
                }
            } catch {
            }
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $null
}

function Get-Qcl030ObjectLong {
    param(
        [object]$Object,
        [string]$Name
    )
    if ($null -eq $Object) {
        return 0
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        return 0
    }
    try {
        return [int64]$property.Value
    } catch {
        return 0
    }
}

function Redact-Qcl030Text {
    param(
        [string]$Value,
        [string[]]$SensitiveValues
    )
    $result = if ($null -eq $Value) { "" } else { $Value }
    foreach ($sensitive in $SensitiveValues) {
        if (-not [string]::IsNullOrWhiteSpace($sensitive)) {
            $result = $result.Replace($sensitive, "[redacted]")
        }
    }
    return $result
}

function Copy-Qcl030RedactedProbe {
    param(
        [object]$Probe,
        [string[]]$SensitiveValues = @()
    )
    if ($null -eq $Probe) {
        return $null
    }
    [ordered]@{
        name = [string]$Probe.name
        serial = [string]$Probe.serial
        arguments = Redact-Qcl030Text -Value ([string]$Probe.arguments) -SensitiveValues $SensitiveValues
        exit_code = [int]$Probe.exit_code
        output = Redact-Qcl030Text -Value ([string]$Probe.output) -SensitiveValues $SensitiveValues
    }
}

function Invoke-Qcl030ClientWifiSuggestionJoin {
    param(
        [Parameter(Mandatory=$true)]
        [string]$TargetSerial,
        [Parameter(Mandatory=$true)]
        [string]$Ssid,
        [Parameter(Mandatory=$true)]
        [string]$Passphrase,
        [int]$WaitSeconds
    )
    $sensitiveValues = @($Ssid, $Passphrase)
    $boundedWaitSeconds = [Math]::Max(1, [Math]::Min($WaitSeconds, 180))
    $removeBefore = Invoke-Qcl030AdbProbe `
        -Arguments @("shell", "cmd", "wifi", "remove-suggestion", $Ssid) `
        -Name "cmd wifi remove-suggestion QCL-030 client before host-mediated join" `
        -TargetSerial $TargetSerial
    $addSuggestion = Invoke-Qcl030AdbProbe `
        -Arguments @("shell", "cmd", "wifi", "add-suggestion", $Ssid, "wpa2", $Passphrase, "-s") `
        -Name "cmd wifi add-suggestion QCL-030 client host-mediated join" `
        -TargetSerial $TargetSerial
    $approveSuggestion = Invoke-Qcl030AdbProbe `
        -Arguments @("shell", "cmd", "wifi", "network-suggestions-set-user-approved", "com.android.shell", "yes") `
        -Name "cmd wifi network-suggestions-set-user-approved shell" `
        -TargetSerial $TargetSerial
    $startScan = Invoke-Qcl030AdbProbe `
        -Arguments @("shell", "cmd", "wifi", "start-scan") `
        -Name "cmd wifi start-scan after QCL-030 suggestion" `
        -TargetSerial $TargetSerial
    $deadline = (Get-Date).AddSeconds($boundedWaitSeconds)
    $pollCount = 0
    $connectedToTarget = $false
    $lastStatus = $null
    do {
        $lastStatus = Invoke-Qcl030AdbProbe `
            -Arguments @("shell", "cmd", "wifi", "status") `
            -Name "cmd wifi status after QCL-030 suggestion" `
            -TargetSerial $TargetSerial
        $pollCount++
        if (-not [string]::IsNullOrWhiteSpace($Ssid) -and ([string]$lastStatus.output).Contains($Ssid)) {
            $connectedToTarget = $true
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    [ordered]@{
        attempted = $true
        wait_seconds = $boundedWaitSeconds
        poll_count = $pollCount
        connected_to_target_ssid = $connectedToTarget
        remove_before = Copy-Qcl030RedactedProbe -Probe $removeBefore -SensitiveValues $sensitiveValues
        add_suggestion = Copy-Qcl030RedactedProbe -Probe $addSuggestion -SensitiveValues $sensitiveValues
        approve_shell = Copy-Qcl030RedactedProbe -Probe $approveSuggestion -SensitiveValues $sensitiveValues
        start_scan = Copy-Qcl030RedactedProbe -Probe $startScan -SensitiveValues $sensitiveValues
        last_status = Copy-Qcl030RedactedProbe -Probe $lastStatus -SensitiveValues $sensitiveValues
        credential_sensitive_redacted = $true
    }
}

function Invoke-Qcl030ClientWifiSuggestionCleanup {
    param(
        [Parameter(Mandatory=$true)]
        [string]$TargetSerial,
        [Parameter(Mandatory=$true)]
        [string]$Ssid,
        [string[]]$SensitiveValues = @()
    )
    $probe = Invoke-Qcl030AdbProbe `
        -Arguments @("shell", "cmd", "wifi", "remove-suggestion", $Ssid) `
        -Name "cmd wifi remove-suggestion QCL-030 client cleanup" `
        -TargetSerial $TargetSerial
    Copy-Qcl030RedactedProbe -Probe $probe -SensitiveValues $SensitiveValues
}

function Stop-Qcl030Package {
    param([string]$TargetSerial)
    if ([string]::IsNullOrWhiteSpace($TargetSerial)) {
        return
    }
    $null = Invoke-Qcl030AdbProbe `
        -Arguments @("shell", "am", "force-stop", $Qcl041Package) `
        -Name "force-stop QCL-030 shared APK" `
        -TargetSerial $TargetSerial
}

function Get-Qcl030ApprovalTapTarget {
    param([string]$XmlText)
    if ([string]::IsNullOrWhiteSpace($XmlText)) {
        return $null
    }
    try {
        [xml]$xml = $XmlText
        $nodes = $xml.SelectNodes("//node[@clickable='true']")
        foreach ($node in $nodes) {
            $label = ([string]$node.text + " " + [string]$node.'content-desc').Trim()
            if ($label -match '(?i)\b(connect|allow|ok|join|yes|verbinden|zulassen|ja)\b') {
                if ([string]$node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                    return [ordered]@{
                        x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
                        y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
                        method = "labeled_button"
                    }
                }
            }
        }
        if ($XmlText -match 'NetworkRequestDialogActivity' -or $XmlText -match 'com.android.settings') {
            return [ordered]@{
                x = 420
                y = 740
                method = "dialog_bottom_right_fallback"
            }
        }
    } catch {
    }
    return $null
}

function Invoke-Qcl030ClientNetworkRequestApproval {
    param(
        [string]$TargetSerial,
        [int]$WaitSeconds
    )
    $deadline = (Get-Date).AddSeconds([Math]::Max(1, $WaitSeconds))
    $pollCount = 0
    $tapCount = 0
    $dialogObserved = $false
    $lastTap = $null
    do {
        $pollCount++
        $dump = Invoke-Qcl030AdbProbe `
            -Arguments @("shell", "uiautomator", "dump", "/sdcard/qcl030-network-request-window.xml") `
            -Name "uiautomator dump network request dialog" `
            -TargetSerial $TargetSerial
        $cat = Invoke-Qcl030AdbProbe `
            -Arguments @("exec-out", "cat", "/sdcard/qcl030-network-request-window.xml") `
            -Name "cat network request dialog dump" `
            -TargetSerial $TargetSerial
        $xmlText = [string]$cat.output
        if ($xmlText -match 'NetworkRequestDialogActivity' -or $xmlText -match 'com.android.settings') {
            $dialogObserved = $true
        }
        $tapTarget = Get-Qcl030ApprovalTapTarget -XmlText $xmlText
        if ($null -ne $tapTarget) {
            $tap = Invoke-Qcl030AdbProbe `
                -Arguments @("shell", "input", "tap", "$($tapTarget.x)", "$($tapTarget.y)") `
                -Name "tap network request approval" `
                -TargetSerial $TargetSerial
            $tapCount++
            $lastTap = [ordered]@{
                x = $tapTarget.x
                y = $tapTarget.y
                method = $tapTarget.method
                exit_code = $tap.exit_code
            }
            Start-Sleep -Seconds 2
            continue
        }
        if ($tapCount -gt 0) {
            return [ordered]@{
                attempted = $true
                approved = $true
                dialog_observed = $dialogObserved
                poll_count = $pollCount
                tap_count = $tapCount
                last_tap = $lastTap
                credential_sensitive_redacted = $true
            }
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    [ordered]@{
        attempted = $true
        approved = $tapCount -gt 0
        dialog_observed = $dialogObserved
        poll_count = $pollCount
        tap_count = $tapCount
        last_tap = $lastTap
        credential_sensitive_redacted = $true
    }
}

function New-Qcl030ClientJoinMatrixSummary {
    param(
        [Parameter(Mandatory=$true)][string]$Status,
        [string]$BlockedReason = "",
        [object]$OwnerPreflight = $null,
        [object]$ClientPreflight = $null,
        [object]$OwnerPermission = $null,
        [object]$ClientPermission = $null,
        [object]$OwnerLaunch = $null,
        [object]$ClientLaunch = $null,
        [object]$ClientWifiSuggestionJoin = $null,
        [object]$ClientWifiSuggestionCleanup = $null,
        [object]$NetworkRequestApproval = $null,
        [object]$OwnerCredentialArtifact = $null,
        [object]$OwnerArtifact = $null,
        [object]$ClientArtifact = $null,
        [string]$OwnerArtifactPath = "",
        [string]$ClientArtifactPath = ""
    )
    $ownerSocket = if ($null -eq $OwnerArtifact) { $null } else { $OwnerArtifact.socket_matrix }
    $clientSocket = if ($null -eq $ClientArtifact) { $null } else { $ClientArtifact.socket_matrix }
    $ownerUdpBytes = Get-Qcl030ObjectLong -Object $ownerSocket -Name "owner_udp_bytes"
    $ownerTcpBytes = Get-Qcl030ObjectLong -Object $ownerSocket -Name "owner_tcp_bytes"
    $clientUdpBytes = Get-Qcl030ObjectLong -Object $clientSocket -Name "client_udp_sent_bytes"
    $clientTcpBytes = Get-Qcl030ObjectLong -Object $clientSocket -Name "client_tcp_sent_bytes"
    $ssidPresent = $false
    $passphrasePresent = $false
    if ($null -ne $OwnerCredentialArtifact -and $null -ne $OwnerCredentialArtifact.local_only_hotspot) {
        $ssidPresent = -not [string]::IsNullOrWhiteSpace([string]$OwnerCredentialArtifact.local_only_hotspot.ssid)
        $passphrasePresent = -not [string]::IsNullOrWhiteSpace([string]$OwnerCredentialArtifact.local_only_hotspot.passphrase)
    }
    $sensitiveValues = @()
    if ($ssidPresent) {
        $sensitiveValues += [string]$OwnerCredentialArtifact.local_only_hotspot.ssid
    }
    if ($passphrasePresent) {
        $sensitiveValues += [string]$OwnerCredentialArtifact.local_only_hotspot.passphrase
    }
    [ordered]@{
        '$schema' = "rusty.quest.qcl030_local_only_hotspot_client_join_matrix_run.v1"
        schema_version = 1
        run_id = $RunId
        status = $Status
        blocked_reason = $BlockedReason
        observed_at_utc = Get-Qcl030UtcNow
        host_wrapper = [ordered]@{
            script = "Invoke-Qcl030QuestLocalOnlyHotspotProbe.ps1"
            adb = $Adb
            run_client_join_matrix = $true
            skip_install = [bool]$SkipInstall
            client_launch_surface = if ($LaunchClientViaActivity) { "foreground_activity" } else { "foreground_service" }
            client_join_mode = $ClientJoinModeToken
            require_active_wifi_ssid_match = [bool]$RequireActiveWifiSsidMatch
            host_join_client_with_wifi_suggestion = [bool]$HostJoinClientWithWifiSuggestion
            host_join_wait_seconds = $HostJoinWaitSeconds
            auto_approve_client_network_request = [bool]$AutoApproveClientNetworkRequest
            auto_approve_poll_seconds = $AutoApprovePollSeconds
            credential_sensitive_redacted = $true
        }
        devices = [ordered]@{
            owner_serial = $OwnerSerial
            client_serial = $ClientSerial
        }
        topology = [ordered]@{
            owner = "quest_local_only_hotspot"
            network_provider = "quest_local_only_hotspot"
            external_wifi_provider_required = $false
            endpoint_direction = "quest_hosted_local_ap_to_quest_client_joined_local_ap"
            requires_android_network_binding = $true
            receiver_observed_bytes_required_before_media = $true
        }
        preflight = [ordered]@{
            owner = $OwnerPreflight
            client = $ClientPreflight
        }
        permission_preflight = [ordered]@{
            owner_path = Join-Path $OutDir "qcl030-owner-permission-preflight.json"
            client_path = Join-Path $OutDir "qcl030-client-permission-preflight.json"
            owner_ready = if ($null -eq $OwnerPermission) { $false } else { [bool]$OwnerPermission.permission_ready }
            client_ready = if ($null -eq $ClientPermission) { $false } else { [bool]$ClientPermission.permission_ready }
        }
        launch = [ordered]@{
            owner = Copy-Qcl030RedactedProbe -Probe $OwnerLaunch
            client = Copy-Qcl030RedactedProbe `
                -Probe $ClientLaunch `
                -SensitiveValues $sensitiveValues
        }
        network_request_approval = if ($null -eq $NetworkRequestApproval) {
            [ordered]@{
                attempted = $false
                approved = $false
                dialog_observed = $false
                credential_sensitive_redacted = $true
            }
        } else {
            $NetworkRequestApproval
        }
        client_wifi_suggestion_join = if ($null -eq $ClientWifiSuggestionJoin) {
            [ordered]@{
                attempted = $false
                credential_sensitive_redacted = $true
            }
        } else {
            $ClientWifiSuggestionJoin
        }
        client_wifi_suggestion_cleanup = if ($null -eq $ClientWifiSuggestionCleanup) {
            [ordered]@{
                attempted = $false
                credential_sensitive_redacted = $true
            }
        } else {
            $ClientWifiSuggestionCleanup
        }
        local_only_hotspot = [ordered]@{
            owner_hotspot_started = if ($null -eq $OwnerCredentialArtifact -or $null -eq $OwnerCredentialArtifact.local_only_hotspot) { $false } else { [bool]$OwnerCredentialArtifact.local_only_hotspot.started }
            owner_ssid_present = $ssidPresent
            owner_passphrase_present = $passphrasePresent
            credential_sensitive_redacted = $true
            owner_artifact_path = $OwnerArtifactPath
            client_artifact_path = $ClientArtifactPath
        }
        socket_matrix = [ordered]@{
            udp_port = $Port
            tcp_port = $Port + 1
            owner_host = $OwnerHost
            socket_bytes = $SocketBytes
            owner_udp_receiver_started = if ($null -eq $ownerSocket) { $false } else { [bool]$ownerSocket.owner_udp_receiver_started }
            owner_tcp_receiver_started = if ($null -eq $ownerSocket) { $false } else { [bool]$ownerSocket.owner_tcp_receiver_started }
            owner_receiver_observed_udp_bytes = $ownerUdpBytes
            owner_receiver_observed_tcp_bytes = $ownerTcpBytes
            owner_receiver_observed_bytes = [bool](($ownerUdpBytes + $ownerTcpBytes) -gt 0)
            client_join_mode = if ($null -eq $clientSocket) { $ClientJoinModeToken } else { [string]$clientSocket.client_join_mode }
            require_active_wifi_ssid_match = if ($null -eq $clientSocket) { [bool]$RequireActiveWifiSsidMatch } else { [bool]$clientSocket.require_active_wifi_ssid_match }
            client_network_available = if ($null -eq $clientSocket) { $false } else { [bool]$clientSocket.client_network_available }
            client_network_bound = if ($null -eq $clientSocket) { $false } else { [bool]$clientSocket.client_network_bound }
            client_active_wifi_ssid_present = if ($null -eq $clientSocket) { $false } else { [bool]$clientSocket.client_active_wifi_ssid_present }
            client_active_wifi_ssid_match_status = if ($null -eq $clientSocket) { "" } else { [string]$clientSocket.client_active_wifi_ssid_match_status }
            client_socket_attempted = if ($null -eq $clientSocket) { $false } else { [bool]$clientSocket.client_socket_attempted }
            client_udp_sent_bytes = $clientUdpBytes
            client_tcp_sent_bytes = $clientTcpBytes
            client_tcp_ack_bytes = Get-Qcl030ObjectLong -Object $clientSocket -Name "client_tcp_ack_bytes"
        }
        artifact_status = [ordered]@{
            owner = if ($null -eq $OwnerArtifact) { "" } else { [string]$OwnerArtifact.status }
            owner_blocked_reason = if ($null -eq $OwnerArtifact) { "" } else { [string]$OwnerArtifact.blocked_reason }
            client = if ($null -eq $ClientArtifact) { "" } else { [string]$ClientArtifact.status }
            client_blocked_reason = if ($null -eq $ClientArtifact) { "" } else { [string]$ClientArtifact.blocked_reason }
        }
        pass_condition = "owner_pass_and_client_pass_and_owner_receiver_observed_bytes"
        next_gate = "qcl100_alternate_topology_media_stream_render_mapping_pending"
    }
}

$summaryPath = Join-Path $OutDir "summary.json"

if ($RunClientJoinMatrix) {
    if ([string]::IsNullOrWhiteSpace($OwnerSerial) -and -not [string]::IsNullOrWhiteSpace($Serial)) {
        $OwnerSerial = $Serial
    }
    if ([string]::IsNullOrWhiteSpace($OwnerSerial)) {
        throw "RunClientJoinMatrix requires -OwnerSerial or -Serial for the LocalOnlyHotspot owner Quest."
    }
    if ([string]::IsNullOrWhiteSpace($ClientSerial)) {
        throw "RunClientJoinMatrix requires -ClientSerial for the joining Quest."
    }
    if ($OwnerSerial -eq $ClientSerial) {
        throw "RunClientJoinMatrix requires distinct owner and client serials."
    }
    if ($PreflightOnly) {
        $ownerPreflight = Get-Qcl030Preflight -TargetSerial $OwnerSerial
        $clientPreflight = Get-Qcl030Preflight -TargetSerial $ClientSerial
        $summary = New-Qcl030ClientJoinMatrixSummary `
            -Status "preflight_only" `
            -OwnerPreflight $ownerPreflight `
            -ClientPreflight $clientPreflight
        Write-Qcl030JsonFile -Value $summary -Path $summaryPath
        Write-Output "QCL-030 LocalOnlyHotspot client-join matrix preflight summary: $summaryPath"
        exit 0
    }
    if (-not $SkipInstall) {
        if (-not (Test-Path -LiteralPath $Qcl041Apk)) {
            throw "QCL-041 shared APK not found: $Qcl041Apk"
        }
        Invoke-Qcl030AdbText `
            -Arguments @("install", "-r", $Qcl041Apk) `
            -Name "adb install QCL-041 shared APK owner" `
            -TargetSerial $OwnerSerial | Out-Null
        Invoke-Qcl030AdbText `
            -Arguments @("install", "-r", $Qcl041Apk) `
            -Name "adb install QCL-041 shared APK client" `
            -TargetSerial $ClientSerial | Out-Null
    }

    $ownerPermissionOutPath = Join-Path $OutDir "qcl030-owner-permission-preflight.json"
    $clientPermissionOutPath = Join-Path $OutDir "qcl030-client-permission-preflight.json"
    $ownerArtifactOutPath = Join-Path $OutDir "qcl030-owner-local-only-hotspot-artifact.json"
    $clientArtifactOutPath = Join-Path $OutDir "qcl030-client-local-only-hotspot-artifact.json"
    $ownerPreflight = Get-Qcl030Preflight -TargetSerial $OwnerSerial
    $clientPreflight = Get-Qcl030Preflight -TargetSerial $ClientSerial
    $ownerPermission = Invoke-Qcl030PermissionPreflight `
        -Path $ownerPermissionOutPath `
        -TargetSerial $OwnerSerial
    $clientPermission = Invoke-Qcl030PermissionPreflight `
        -Path $clientPermissionOutPath `
        -TargetSerial $ClientSerial
    if ($ownerPermission.permission_ready -ne $true -or $clientPermission.permission_ready -ne $true) {
        $summary = New-Qcl030ClientJoinMatrixSummary `
            -Status "blocked_permission_preflight" `
            -BlockedReason "runtime_permission_not_ready" `
            -OwnerPreflight $ownerPreflight `
            -ClientPreflight $clientPreflight `
            -OwnerPermission $ownerPermission `
            -ClientPermission $clientPermission
        Write-Qcl030JsonFile -Value $summary -Path $summaryPath
        Write-Output "QCL-030 LocalOnlyHotspot client-join matrix permission blocked summary: $summaryPath"
        exit 2
    }

    $holdMs = [Math]::Max(1, $HoldSeconds) * 1000
    $socketTimeoutMs = [Math]::Max(1, $SocketTimeoutSeconds) * 1000
    $ownerLaunchArguments = @(
        "shell", "am", "start-foreground-service",
        "-n", $Qcl041Service,
        "--es", "qcl041.run_id", $RunId,
        "--es", "qcl041.device_serial", $OwnerSerial,
        "--es", "qcl041.device_model", "Quest",
        "--es", "qcl041.lease_id", "unleased",
        "--es", "qcl041.lease_resource", "quest:$OwnerSerial",
        "--es", "qcl041.host_toolchain_profile", "qcl030_quest_local_only_hotspot_client_join_matrix",
        "--ez", "qcl041.lease_reserved_before_live_steps", "false",
        "--ez", "qcl041.lease_released_after_live_steps", "false",
        "--ez", "qcl041.qcl030_local_only_hotspot_enabled", "true",
        "--es", "qcl041.qcl030_local_only_hotspot_role", "hotspot_owner",
        "--ei", "qcl041.qcl030_local_only_hotspot_hold_ms", "$holdMs",
        "--ei", "qcl041.qcl030_local_only_hotspot_port", "$Port",
        "--ei", "qcl041.qcl030_local_only_hotspot_socket_bytes", "$SocketBytes",
        "--ei", "qcl041.qcl030_local_only_hotspot_socket_timeout_ms", "$socketTimeoutMs"
    )
    $ownerLaunch = Invoke-Qcl030AdbProbe `
        -Arguments $ownerLaunchArguments `
        -Name "start-foreground-service QCL-030 LocalOnlyHotspot owner" `
        -TargetSerial $OwnerSerial
    if ($ownerLaunch.exit_code -ne 0) {
        $summary = New-Qcl030ClientJoinMatrixSummary `
            -Status "blocked_owner_launch" `
            -BlockedReason "owner_start_foreground_service_failed" `
            -OwnerPreflight $ownerPreflight `
            -ClientPreflight $clientPreflight `
            -OwnerPermission $ownerPermission `
            -ClientPermission $clientPermission `
            -OwnerLaunch $ownerLaunch
        Write-Qcl030JsonFile -Value $summary -Path $summaryPath
        Write-Output "QCL-030 LocalOnlyHotspot client-join matrix owner launch failed summary: $summaryPath"
        Stop-Qcl030Package -TargetSerial $OwnerSerial
        exit 2
    }

    $credentialWaitSeconds = [Math]::Min([Math]::Max(15, $TimeoutSeconds), 60)
    $ownerCredentialArtifact = Wait-Qcl030OwnerCredentialArtifact `
        -WaitSeconds $credentialWaitSeconds `
        -TargetSerial $OwnerSerial
    if ($null -eq $ownerCredentialArtifact -or $ownerCredentialArtifact.status -eq "blocked") {
        if ($null -ne $ownerCredentialArtifact) {
            Write-Qcl030JsonFile -Value $ownerCredentialArtifact -Path $ownerArtifactOutPath
        }
        $summary = New-Qcl030ClientJoinMatrixSummary `
            -Status "blocked_owner_hotspot_credentials" `
            -BlockedReason "owner_hotspot_credentials_unavailable" `
            -OwnerPreflight $ownerPreflight `
            -ClientPreflight $clientPreflight `
            -OwnerPermission $ownerPermission `
            -ClientPermission $clientPermission `
            -OwnerLaunch $ownerLaunch `
            -OwnerCredentialArtifact $ownerCredentialArtifact `
            -OwnerArtifact $ownerCredentialArtifact `
            -OwnerArtifactPath $ownerArtifactOutPath
        Write-Qcl030JsonFile -Value $summary -Path $summaryPath
        Write-Output "QCL-030 LocalOnlyHotspot client-join matrix owner credential blocked summary: $summaryPath"
        Stop-Qcl030Package -TargetSerial $OwnerSerial
        exit 2
    }

    if ($ClientLaunchDelaySeconds -gt 0) {
        Start-Sleep -Seconds $ClientLaunchDelaySeconds
    }
    $ownerSsid = [string]$ownerCredentialArtifact.local_only_hotspot.ssid
    $ownerPassphrase = [string]$ownerCredentialArtifact.local_only_hotspot.passphrase
    $sensitiveOwnerValues = @($ownerSsid, $ownerPassphrase)
    $clientWifiSuggestionJoin = $null
    $clientWifiSuggestionCleanup = $null
    if ($HostJoinClientWithWifiSuggestion) {
        $clientWifiSuggestionJoin = Invoke-Qcl030ClientWifiSuggestionJoin `
            -TargetSerial $ClientSerial `
            -Ssid $ownerSsid `
            -Passphrase $ownerPassphrase `
            -WaitSeconds $HostJoinWaitSeconds
    }
    $clientLaunchCommand = if ($LaunchClientViaActivity) { "start" } else { "start-foreground-service" }
    $clientLaunchComponent = if ($LaunchClientViaActivity) { $Qcl041Activity } else { $Qcl041Service }
    $clientLaunchName = if ($LaunchClientViaActivity) {
        "start Activity QCL-030 LocalOnlyHotspot client"
    } else {
        "start-foreground-service QCL-030 LocalOnlyHotspot client"
    }
    $clientLaunchBlockedReason = if ($LaunchClientViaActivity) {
        "client_start_activity_failed"
    } else {
        "client_start_foreground_service_failed"
    }
    $clientLaunchArguments = @(
        "shell", "am", $clientLaunchCommand,
        "-n", $clientLaunchComponent,
        "--es", "qcl041.run_id", $RunId,
        "--es", "qcl041.device_serial", $ClientSerial,
        "--es", "qcl041.device_model", "Quest",
        "--es", "qcl041.lease_id", "unleased",
        "--es", "qcl041.lease_resource", "quest:$ClientSerial",
        "--es", "qcl041.host_toolchain_profile", "qcl030_quest_local_only_hotspot_client_join_matrix",
        "--ez", "qcl041.lease_reserved_before_live_steps", "false",
        "--ez", "qcl041.lease_released_after_live_steps", "false",
        "--ez", "qcl041.qcl030_local_only_hotspot_enabled", "true",
        "--es", "qcl041.qcl030_local_only_hotspot_role", "hotspot_client",
        "--es", "qcl041.qcl030_local_only_hotspot_ssid", $ownerSsid,
        "--es", "qcl041.qcl030_local_only_hotspot_passphrase", $ownerPassphrase,
        "--es", "qcl041.qcl030_local_only_hotspot_owner_host", $OwnerHost,
        "--es", "qcl041.qcl030_local_only_hotspot_client_join_mode", $ClientJoinModeToken,
        "--ez", "qcl041.qcl030_local_only_hotspot_require_ssid_match", ([bool]$RequireActiveWifiSsidMatch).ToString().ToLowerInvariant(),
        "--ei", "qcl041.qcl030_local_only_hotspot_port", "$Port",
        "--ei", "qcl041.qcl030_local_only_hotspot_socket_bytes", "$SocketBytes",
        "--ei", "qcl041.qcl030_local_only_hotspot_socket_timeout_ms", "$socketTimeoutMs"
    )
    $clientLaunch = Invoke-Qcl030AdbProbe `
        -Arguments $clientLaunchArguments `
        -Name $clientLaunchName `
        -TargetSerial $ClientSerial
    if ($clientLaunch.exit_code -ne 0) {
        if ($HostJoinClientWithWifiSuggestion) {
            $clientWifiSuggestionCleanup = Invoke-Qcl030ClientWifiSuggestionCleanup `
                -TargetSerial $ClientSerial `
                -Ssid $ownerSsid `
                -SensitiveValues $sensitiveOwnerValues
        }
        $summary = New-Qcl030ClientJoinMatrixSummary `
            -Status "blocked_client_launch" `
            -BlockedReason $clientLaunchBlockedReason `
            -OwnerPreflight $ownerPreflight `
            -ClientPreflight $clientPreflight `
            -OwnerPermission $ownerPermission `
            -ClientPermission $clientPermission `
            -OwnerLaunch $ownerLaunch `
            -ClientLaunch $clientLaunch `
            -ClientWifiSuggestionJoin $clientWifiSuggestionJoin `
            -ClientWifiSuggestionCleanup $clientWifiSuggestionCleanup `
            -OwnerCredentialArtifact $ownerCredentialArtifact
        Write-Qcl030JsonFile -Value $summary -Path $summaryPath
        Write-Output "QCL-030 LocalOnlyHotspot client-join matrix client launch failed summary: $summaryPath"
        Stop-Qcl030Package -TargetSerial $OwnerSerial
        Stop-Qcl030Package -TargetSerial $ClientSerial
        exit 2
    }

    $networkRequestApproval = $null
    if ($AutoApproveClientNetworkRequest -and $ClientJoinModeToken -eq "network_specifier") {
        $networkRequestApproval = Invoke-Qcl030ClientNetworkRequestApproval `
            -TargetSerial $ClientSerial `
            -WaitSeconds $AutoApprovePollSeconds
    }

    $clientArtifact = Wait-Qcl030Artifact `
        -WaitSeconds $TimeoutSeconds `
        -TargetSerial $ClientSerial
    if ($null -ne $clientArtifact) {
        Write-Qcl030JsonFile -Value $clientArtifact -Path $clientArtifactOutPath
    }
    $ownerFinalWaitSeconds = [Math]::Max($TimeoutSeconds, $HoldSeconds + 20)
    $ownerArtifact = Wait-Qcl030Artifact `
        -WaitSeconds $ownerFinalWaitSeconds `
        -TargetSerial $OwnerSerial
    if ($null -ne $ownerArtifact) {
        Write-Qcl030JsonFile -Value $ownerArtifact -Path $ownerArtifactOutPath
    }
    if ($HostJoinClientWithWifiSuggestion) {
        $clientWifiSuggestionCleanup = Invoke-Qcl030ClientWifiSuggestionCleanup `
            -TargetSerial $ClientSerial `
            -Ssid $ownerSsid `
            -SensitiveValues $sensitiveOwnerValues
    }

    $ownerSocket = if ($null -eq $ownerArtifact) { $null } else { $ownerArtifact.socket_matrix }
    $ownerObservedBytes = (Get-Qcl030ObjectLong -Object $ownerSocket -Name "owner_udp_bytes") `
        + (Get-Qcl030ObjectLong -Object $ownerSocket -Name "owner_tcp_bytes")
    $matrixPass = $null -ne $ownerArtifact `
        -and $null -ne $clientArtifact `
        -and $ownerArtifact.status -eq "pass" `
        -and $clientArtifact.status -eq "pass" `
        -and $ownerObservedBytes -gt 0
    $matrixStatus = if ($matrixPass) { "pass" } else { "blocked_live" }
    $matrixBlockedReason = ""
    if (-not $matrixPass) {
        if ($null -eq $clientArtifact) {
            $matrixBlockedReason = "client_artifact_wait_timeout"
        } elseif ($null -eq $ownerArtifact) {
            $matrixBlockedReason = "owner_artifact_wait_timeout"
        } elseif ($ownerArtifact.status -ne "pass") {
            $matrixBlockedReason = "owner_" + [string]$ownerArtifact.blocked_reason
        } elseif ($clientArtifact.status -ne "pass") {
            $matrixBlockedReason = "client_" + [string]$clientArtifact.blocked_reason
        } elseif ($ownerObservedBytes -le 0) {
            $matrixBlockedReason = "owner_receiver_observed_zero_bytes"
        } else {
            $matrixBlockedReason = "unknown_matrix_block"
        }
    }
    $summary = New-Qcl030ClientJoinMatrixSummary `
        -Status $matrixStatus `
        -BlockedReason $matrixBlockedReason `
        -OwnerPreflight $ownerPreflight `
        -ClientPreflight $clientPreflight `
        -OwnerPermission $ownerPermission `
        -ClientPermission $clientPermission `
        -OwnerLaunch $ownerLaunch `
        -ClientLaunch $clientLaunch `
        -ClientWifiSuggestionJoin $clientWifiSuggestionJoin `
        -ClientWifiSuggestionCleanup $clientWifiSuggestionCleanup `
        -NetworkRequestApproval $networkRequestApproval `
        -OwnerCredentialArtifact $ownerCredentialArtifact `
        -OwnerArtifact $ownerArtifact `
        -ClientArtifact $clientArtifact `
        -OwnerArtifactPath $ownerArtifactOutPath `
        -ClientArtifactPath $clientArtifactOutPath
    Write-Qcl030JsonFile -Value $summary -Path $summaryPath
    Write-Output "QCL-030 LocalOnlyHotspot client-join matrix summary: $summaryPath"
    Stop-Qcl030Package -TargetSerial $OwnerSerial
    Stop-Qcl030Package -TargetSerial $ClientSerial
    if ($matrixPass) {
        exit 0
    }
    exit 2
}

$Serial = Resolve-Qcl030Serial -RequestedSerial $Serial
$artifactOutPath = Join-Path $OutDir "qcl030-local-only-hotspot-artifact.json"
$permissionOutPath = Join-Path $OutDir "qcl030-permission-preflight.json"
$preflight = Get-Qcl030Preflight

if ($PreflightOnly) {
    $summary = New-Qcl030Summary -Status "preflight_only" -Preflight $preflight
    Write-Qcl030JsonFile -Value $summary -Path $summaryPath
    Write-Output "QCL-030 LocalOnlyHotspot preflight summary: $summaryPath"
    exit 0
}

if (-not $SkipInstall) {
    if (-not (Test-Path -LiteralPath $Qcl041Apk)) {
        throw "QCL-041 shared APK not found: $Qcl041Apk"
    }
    Invoke-Qcl030AdbText -Arguments @("install", "-r", $Qcl041Apk) -Name "adb install QCL-041 shared APK" | Out-Null
}

$permission = Invoke-Qcl030PermissionPreflight -Path $permissionOutPath
if ($permission.permission_ready -ne $true) {
    $summary = New-Qcl030Summary `
        -Status "blocked_permission_preflight" `
        -BlockedReason "runtime_permission_not_ready" `
        -Preflight $preflight `
        -Permission $permission
    Write-Qcl030JsonFile -Value $summary -Path $summaryPath
    Write-Output "QCL-030 LocalOnlyHotspot permission preflight blocked summary: $summaryPath"
    exit 2
}

$holdMs = [Math]::Max(1, $HoldSeconds) * 1000
$launchArguments = @(
    "shell", "am", "start-foreground-service",
    "-n", $Qcl041Service,
    "--es", "qcl041.run_id", $RunId,
    "--es", "qcl041.device_serial", $Serial,
    "--es", "qcl041.device_model", "Quest",
    "--es", "qcl041.lease_id", "unleased",
    "--es", "qcl041.lease_resource", "quest:$Serial",
    "--es", "qcl041.host_toolchain_profile", "qcl030_quest_local_only_hotspot_probe",
    "--ez", "qcl041.lease_reserved_before_live_steps", "false",
    "--ez", "qcl041.lease_released_after_live_steps", "false",
    "--ez", "qcl041.qcl030_local_only_hotspot_enabled", "true",
    "--es", "qcl041.qcl030_local_only_hotspot_role", "hotspot_owner",
    "--ei", "qcl041.qcl030_local_only_hotspot_hold_ms", "$holdMs",
    "--ei", "qcl041.qcl030_local_only_hotspot_port", "$Port",
    "--ei", "qcl041.qcl030_local_only_hotspot_socket_bytes", "$SocketBytes",
    "--ei", "qcl041.qcl030_local_only_hotspot_socket_timeout_ms", "$([Math]::Max(1, $SocketTimeoutSeconds) * 1000)"
)
$launch = Invoke-Qcl030AdbProbe -Arguments $launchArguments -Name "start-foreground-service QCL-030 LocalOnlyHotspot"
if ($launch.exit_code -ne 0) {
    $summary = New-Qcl030Summary `
        -Status "blocked_launch" `
        -BlockedReason "start_foreground_service_failed" `
        -Preflight $preflight `
        -Permission $permission `
        -Launch $launch
    Write-Qcl030JsonFile -Value $summary -Path $summaryPath
    Write-Output "QCL-030 LocalOnlyHotspot launch failed summary: $summaryPath"
    exit 2
}

$artifact = Wait-Qcl030Artifact -WaitSeconds $TimeoutSeconds
if ($null -eq $artifact) {
    $summary = New-Qcl030Summary `
        -Status "artifact_timeout" `
        -BlockedReason "qcl030_artifact_wait_timeout" `
        -Preflight $preflight `
        -Permission $permission `
        -Launch $launch
    Write-Qcl030JsonFile -Value $summary -Path $summaryPath
    Write-Output "QCL-030 LocalOnlyHotspot artifact timeout summary: $summaryPath"
    exit 2
}

Write-Qcl030JsonFile -Value $artifact -Path $artifactOutPath
$status = if ($artifact.status -eq "pass") { "pass" } else { "blocked_live" }
$blockedReason = if ($artifact.status -eq "pass") { "" } else { [string]$artifact.blocked_reason }
$summary = New-Qcl030Summary `
    -Status $status `
    -BlockedReason $blockedReason `
    -Preflight $preflight `
    -Permission $permission `
    -Launch $launch `
    -Artifact $artifact
Write-Qcl030JsonFile -Value $summary -Path $summaryPath
Write-Output "QCL-030 LocalOnlyHotspot summary: $summaryPath"

if ($status -eq "pass") {
    exit 0
}
exit 2
