param(
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$Adb = $(if ($env:ADB) { $env:ADB } else { "adb" }),
    [string]$RunId = "",
    [string]$OutDir = "",
    [string]$Ssid = "DIRECT-rq-QCL041PC",
    [string]$Passphrase = "RustyQcl041PcPass",
    [int]$ListenPort = 18768,
    [int]$TimeoutSeconds = 90,
    [int]$SocketTimeoutSeconds = 45,
    [switch]$SkipInstall,
    [switch]$KeepWindowsProfile
)

$ErrorActionPreference = "Stop"

function Add-Step {
    param(
        [Parameter(Mandatory = $true)][System.Collections.IList]$Steps,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Status,
        [Parameter(Mandatory = $true)][string]$Evidence
    )

    [void]$Steps.Add([ordered]@{
            name = $Name
            status = $Status
            evidence = $Evidence
            observed_at = (Get-Date).ToString("o")
        })
    Write-Host ("[{0}] {1} - {2}" -f $Status, $Name, $Evidence)
}

function Save-Summary {
    param(
        [Parameter(Mandatory = $true)][System.Collections.Specialized.OrderedDictionary]$Summary,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Status
    )

    $Summary.status = $Status
    $Summary.ended_at = (Get-Date).ToString("o")
    $Summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Get-ConnectedSsidFromText {
    param([string]$Text)

    $line = ($Text -split "`r?`n" | Where-Object { $_ -match "^\s*SSID\s*:" } | Select-Object -First 1)
    if ($line -and $line -match "^\s*SSID\s*:\s*(.+?)\s*$") {
        return $Matches[1].Trim()
    }
    return ""
}

function Pull-Qcl041Artifact {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$RemoteRun,
        [Parameter(Mandatory = $true)][string]$RemoteLatest,
        [Parameter(Mandatory = $true)][string]$RawArtifact
    )

    $oldErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Adb -s $Serial pull $RemoteRun $RawArtifact *> $null
    } catch {
    }
    if (-not (Test-Path -LiteralPath $RawArtifact)) {
        try {
            & $Adb -s $Serial pull $RemoteLatest $RawArtifact *> $null
        } catch {
        }
    }
    $ErrorActionPreference = $oldErrorActionPreference
}

function New-WlanProfileXml {
    param(
        [Parameter(Mandatory = $true)][string]$Ssid,
        [Parameter(Mandatory = $true)][string]$Passphrase
    )

    $hex = ($Ssid.ToCharArray() | ForEach-Object { "{0:X2}" -f [int][char]$_ }) -join ""
    return @"
<?xml version="1.0"?>
<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
  <name>$Ssid</name>
  <SSIDConfig>
    <SSID>
      <hex>$hex</hex>
      <name>$Ssid</name>
    </SSID>
  </SSIDConfig>
  <connectionType>ESS</connectionType>
  <connectionMode>manual</connectionMode>
  <MSM>
    <security>
      <authEncryption>
        <authentication>WPA2PSK</authentication>
        <encryption>AES</encryption>
        <useOneX>false</useOneX>
      </authEncryption>
      <sharedKey>
        <keyType>passPhrase</keyType>
        <protected>false</protected>
        <keyMaterial>$Passphrase</keyMaterial>
      </sharedKey>
    </security>
  </MSM>
</WLANProfile>
"@
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "Serial is required. Pass -Serial or set RUSTY_QUEST_SERIAL."
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl041-quest-hosted-windows-join-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $repoRoot "target\qcl041-wifi-direct-lifecycle\$RunId"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$packageName = "io.github.mesmerprism.rustyquest.qcl041"
$activity = "$packageName/.Qcl041WifiDirectHarnessActivity"
$apkPath = Join-Path $repoRoot "target\qcl041-wifi-direct-harness-android\rusty-quest-qcl041-wifi-direct-harness.apk"
$remoteLatest = "/sdcard/Android/data/$packageName/files/qcl041/latest.json"
$remoteRun = "/sdcard/Android/data/$packageName/files/qcl041/$RunId.json"
$summaryPath = Join-Path $OutDir "quest-hosted-windows-join-summary.json"
$rawArtifact = Join-Path $OutDir "quest-artifact-raw.json"
$prestatePath = Join-Path $OutDir "prestate.json"
$netshBeforePath = Join-Path $OutDir "netsh-wlan-interfaces-before.txt"
$netshScanPath = Join-Path $OutDir "netsh-wlan-networks-scan.txt"
$netshAfterPath = Join-Path $OutDir "netsh-wlan-interfaces-after.txt"
$profilePath = Join-Path $OutDir "qcl041-quest-hosted-wlan-profile.xml"

$steps = [System.Collections.ArrayList]::new()
$summary = [ordered]@{
    schema = "rusty.quest.qcl041.quest_hosted_windows_join_probe.v1"
    run_id = $RunId
    status = "blocked"
    started_at = (Get-Date).ToString("o")
    out_dir = $OutDir
    ssid = $Ssid
    credential_sensitive_redacted = $true
    adb = $Adb
    serial = $Serial
    listen_port = $ListenPort
    steps = $steps
    results = [ordered]@{}
    artifacts = [ordered]@{
        summary = $summaryPath
        prestate = $prestatePath
        netsh_before = $netshBeforePath
        netsh_scan = $netshScanPath
        netsh_after = $netshAfterPath
        quest_artifact_raw = $rawArtifact
    }
    cleanup = [ordered]@{}
}

$beforeText = (& netsh wlan show interfaces | Out-String)
$beforeText | Set-Content -LiteralPath $netshBeforePath -Encoding UTF8
$previousSsid = Get-ConnectedSsidFromText -Text $beforeText
$prestate = [ordered]@{
    model = (& $Adb -s $Serial shell getprop ro.product.model | Out-String).Trim()
    location_mode = (& $Adb -s $Serial shell settings get secure location_mode | Out-String).Trim()
    device_name = (& $Adb -s $Serial shell settings get global device_name | Out-String).Trim()
    wifi_p2p_device_name = (& $Adb -s $Serial shell settings get global wifi_p2p_device_name | Out-String).Trim()
    windows_connected_ssid_before = $previousSsid
}
$prestate | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $prestatePath -Encoding UTF8
Add-Step -Steps $steps -Name "prestate" -Status "pass" -Evidence "model=$($prestate.model); location_mode=$($prestate.location_mode); previous_windows_ssid=$previousSsid"

try {
    if (-not (Test-Path -LiteralPath $apkPath)) {
        throw "QCL041 APK missing: $apkPath"
    }
    Add-Step -Steps $steps -Name "apk_present" -Status "pass" -Evidence $apkPath

    if (-not $SkipInstall) {
        & $Adb -s $Serial install -r $apkPath | Out-Null
        Add-Step -Steps $steps -Name "apk_install" -Status "pass" -Evidence "installed QCL041 harness"
    }

    & $Adb -s $Serial shell am force-stop $packageName | Out-Null
    & $Adb -s $Serial shell rm -f $remoteLatest $remoteRun | Out-Null
    Add-Step -Steps $steps -Name "harness_reset" -Status "pass" -Evidence "force-stopped package and removed prior remote artifacts"

    $intentArgs = @(
        "-s", $Serial,
        "shell", "am", "start",
        "-n", $activity,
        "--es", "qcl041.run_id", $RunId,
        "--es", "qcl041.device_serial", $Serial,
        "--es", "qcl041.lease_id", "manual-no-lease",
        "--es", "qcl041.lease_resource", "quest:$Serial",
        "--ez", "qcl041.lease_reserved_before_live_steps", "false",
        "--ez", "qcl041.windows_api_observed", "false",
        "--es", "qcl041.peer_class", "quest",
        "--es", "qcl041.q2q_role", "group_owner",
        "--ez", "qcl041.q2q_preclear_stale_group", "true",
        "--es", "qcl041.q2q_network_name", $Ssid,
        "--es", "qcl041.q2q_passphrase", $Passphrase,
        "--ei", "qcl041.listen_port", $ListenPort.ToString(),
        "--ei", "qcl041.timeout_seconds", $TimeoutSeconds.ToString(),
        "--ei", "qcl041.socket_timeout_seconds", $SocketTimeoutSeconds.ToString(),
        "--ei", "qcl041.hold_after_socket_ms", "5000"
    )
    & $Adb @intentArgs | Out-Null
    Add-Step -Steps $steps -Name "quest_group_owner_launch" -Status "pass" -Evidence "launched QCL041 as Quest Wi-Fi Direct group owner"

    $groupReady = $false
    $groupOwnerHost = "192.168.49.1"
    $deadline = (Get-Date).AddSeconds([Math]::Min([Math]::Max(30, $TimeoutSeconds), 90))
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        Pull-Qcl041Artifact -Adb $Adb -Serial $Serial -RemoteRun $remoteRun -RemoteLatest $remoteLatest -RawArtifact $rawArtifact
        if (Test-Path -LiteralPath $rawArtifact) {
            try {
                $artifact = Get-Content -Raw -LiteralPath $rawArtifact | ConvertFrom-Json
                $summary.results.quest_artifact_status = $artifact.lifecycle.socket_exchange.status
                $summary.results.quest_this_device_name = $artifact.diagnostics.lifecycle.this_device_name
                $summary.results.quest_group_formed = $artifact.lifecycle.group_formation.status -eq "pass"
                $summary.results.quest_is_group_owner = $artifact.lifecycle.group_formation.local_role -eq "group_owner"
                $summary.results.quest_group_owner_host = [string]$artifact.diagnostics.lifecycle.wifi_direct_local_address
                if ($artifact.diagnostics.lifecycle.wifi_direct_local_address) {
                    $groupOwnerHost = [string]$artifact.diagnostics.lifecycle.wifi_direct_local_address
                }
                if (($artifact.lifecycle.group_formation.status -eq "pass") -and ($artifact.lifecycle.group_formation.local_role -eq "group_owner")) {
                    $groupReady = $true
                    break
                }
            } catch {
            }
        }
    }
    if (-not $groupReady) {
        Add-Step -Steps $steps -Name "quest_group_owner_ready" -Status "blocked" -Evidence "Quest group owner did not report formed group before timeout"
        Save-Summary -Summary $summary -Path $summaryPath -Status "blocked"
        return
    }
    Add-Step -Steps $steps -Name "quest_group_owner_ready" -Status "pass" -Evidence "Quest reports group owner host $groupOwnerHost"

    $scanHit = $false
    $scanDeadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $scanDeadline) {
        $scanText = (& netsh wlan show networks mode=bssid | Out-String)
        $scanText | Set-Content -LiteralPath $netshScanPath -Encoding UTF8
        if ($scanText -match [Regex]::Escape($Ssid)) {
            $scanHit = $true
            break
        }
        Start-Sleep -Seconds 3
    }
    $summary.results.windows_scan_saw_ssid = $scanHit
    if ($scanHit) {
        Add-Step -Steps $steps -Name "windows_scan_for_quest_go" -Status "pass" -Evidence "SSID visible to Windows WLAN scan"
    } else {
        Add-Step -Steps $steps -Name "windows_scan_for_quest_go" -Status "warn" -Evidence "SSID not visible before connect attempt; trying profile connect anyway"
    }

    New-WlanProfileXml -Ssid $Ssid -Passphrase $Passphrase |
        Set-Content -LiteralPath $profilePath -Encoding UTF8
    & netsh wlan add profile filename="$profilePath" user=current | Out-Null
    Add-Step -Steps $steps -Name "windows_profile_add" -Status "pass" -Evidence "temporary WLAN profile added for redacted Quest-hosted DIRECT-* SSID"
    & netsh wlan connect name="$Ssid" ssid="$Ssid" | Out-Null
    Add-Step -Steps $steps -Name "windows_connect_invoked" -Status "pass" -Evidence "netsh wlan connect invoked"

    $connected = $false
    $windowsIp = ""
    $connectDeadline = (Get-Date).AddSeconds(35)
    while ((Get-Date) -lt $connectDeadline) {
        Start-Sleep -Seconds 2
        $ifaceText = (& netsh wlan show interfaces | Out-String)
        $ifaceText | Set-Content -LiteralPath $netshAfterPath -Encoding UTF8
        $currentSsid = Get-ConnectedSsidFromText -Text $ifaceText
        if ($currentSsid -eq $Ssid) {
            $ip = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
                Where-Object { $_.IPAddress -like "192.168.49.*" } |
                Select-Object -First 1 -ExpandProperty IPAddress
            if ($ip) {
                $windowsIp = $ip
                $connected = $true
                break
            }
        }
    }
    $summary.results.windows_connected_to_quest_go = $connected
    $summary.results.windows_wifi_direct_ipv4 = $windowsIp
    if (-not $connected) {
        Add-Step -Steps $steps -Name "windows_join_quest_go" -Status "blocked" -Evidence "Windows did not associate to the Quest-hosted DIRECT-* WLAN with a 192.168.49.x address"
        Save-Summary -Summary $summary -Path $summaryPath -Status "blocked"
        return
    }
    Add-Step -Steps $steps -Name "windows_join_quest_go" -Status "pass" -Evidence "Windows joined Quest-hosted group with IPv4 $windowsIp"

    $client = [System.Net.Sockets.TcpClient]::new()
    $connectTask = $client.ConnectAsync($groupOwnerHost, $ListenPort)
    if (-not $connectTask.Wait(10000)) {
        throw "TCP connect timeout to ${groupOwnerHost}:$ListenPort"
    }
    $stream = $client.GetStream()
    $stream.ReadTimeout = 10000
    $stream.WriteTimeout = 10000
    $payload = [Text.Encoding]::UTF8.GetBytes("RMANVID1;qcl=QCL-041;run_id=$RunId;route=quest_hosted_windows_join`n")
    $stream.Write($payload, 0, $payload.Length)
    $buffer = New-Object byte[] 1024
    $read = $stream.Read($buffer, 0, $buffer.Length)
    $response = [Text.Encoding]::UTF8.GetString($buffer, 0, $read)
    $client.Close()
    $summary.results.tcp_response_preview = $response
    $summary.results.tcp_response_bytes = $read
    Add-Step -Steps $steps -Name "tcp_exchange" -Status $(if ($read -gt 0) { "pass" } else { "blocked" }) -Evidence "response_bytes=$read"

    $finalDeadline = (Get-Date).AddSeconds(25)
    while ((Get-Date) -lt $finalDeadline) {
        Start-Sleep -Seconds 2
        Pull-Qcl041Artifact -Adb $Adb -Serial $Serial -RemoteRun $remoteRun -RemoteLatest $remoteLatest -RawArtifact $rawArtifact
        if (Test-Path -LiteralPath $rawArtifact) {
            try {
                $artifact = Get-Content -Raw -LiteralPath $rawArtifact | ConvertFrom-Json
                $summary.results.final_quest_status = $artifact.lifecycle.socket_exchange.status
                $summary.results.final_socket_exchange_pass = $artifact.lifecycle.socket_exchange.status -eq "pass"
                $summary.results.final_cleanup_pass = $artifact.lifecycle.cleanup.status -eq "pass"
                if ($summary.results.final_socket_exchange_pass -and $summary.results.final_cleanup_pass) {
                    break
                }
            } catch {
            }
        }
    }
    if ($summary.results.final_socket_exchange_pass -and $summary.results.final_cleanup_pass) {
        Add-Step -Steps $steps -Name "quest_cleanup" -Status "pass" -Evidence "QCL041 artifact reports socket exchange and cleanup pass"
    } elseif ($summary.results.final_socket_exchange_pass) {
        Add-Step -Steps $steps -Name "quest_cleanup" -Status "blocked" -Evidence "TCP exchange passed but QCL041 cleanup did not report pass before timeout"
    }
    Save-Summary -Summary $summary -Path $summaryPath -Status $(if ($summary.results.final_socket_exchange_pass -and $summary.results.final_cleanup_pass) { "pass" } else { "blocked" })
} catch {
    $summary.results.exception = $_.Exception.ToString()
    Add-Step -Steps $steps -Name "probe_exception" -Status "fail" -Evidence $_.Exception.Message
    Save-Summary -Summary $summary -Path $summaryPath -Status "fail"
    throw
} finally {
    $oldErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & netsh wlan disconnect | Out-Null
        $summary.cleanup.windows_disconnect_invoked = $true
    } catch {
        $summary.cleanup.windows_disconnect_error = $_.Exception.Message
    }
    if (-not $KeepWindowsProfile) {
        try {
            & netsh wlan delete profile name="$Ssid" | Out-Null
            $summary.cleanup.windows_profile_deleted = $true
        } catch {
            $summary.cleanup.windows_profile_delete_error = $_.Exception.Message
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($previousSsid) -and $previousSsid -ne $Ssid) {
        try {
            & netsh wlan connect name="$previousSsid" ssid="$previousSsid" | Out-Null
            $summary.cleanup.previous_ssid_reconnect_invoked = $previousSsid
        } catch {
            $summary.cleanup.previous_ssid_reconnect_error = $_.Exception.Message
        }
    }
    try {
        & $Adb -s $Serial shell am force-stop $packageName | Out-Null
        $summary.cleanup.quest_force_stop = $true
    } catch {
        $summary.cleanup.quest_force_stop_error = $_.Exception.Message
    }
    Pull-Qcl041Artifact -Adb $Adb -Serial $Serial -RemoteRun $remoteRun -RemoteLatest $remoteLatest -RawArtifact $rawArtifact
    $ErrorActionPreference = $oldErrorActionPreference
    if (Test-Path -LiteralPath $summaryPath) {
        try {
            $existing = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
            $summary.status = $existing.status
        } catch {
        }
    }
    Save-Summary -Summary $summary -Path $summaryPath -Status $summary.status
    Write-Host "summary=$summaryPath"
}
