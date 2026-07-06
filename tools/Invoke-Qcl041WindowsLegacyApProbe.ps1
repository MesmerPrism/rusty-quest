param(
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$Adb = $(if ($env:ADB) { $env:ADB } else { "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe" }),
    [string]$RunId = "",
    [string]$OutDir = "",
    [string]$HelperProject = "S:\Work\repos\active\rusty-hostess\tools\connectivity_probe\qcl041_wifi_direct_legacy_ap\qcl041-wifi-direct-legacy-ap.csproj",
    [string]$HelperExe = "",
    [string]$Ssid = "DIRECT-rq-QCL041WIN",
    [string]$Passphrase = "RustyQcl041WinPass",
    [string]$OwnerHost = "192.168.137.1",
    [int]$Port = 19068,
    [int]$SocketBytes = 65536,
    [int]$TimeoutSeconds = 90,
    [int]$JoinWaitSeconds = 45,
    [int]$HoldAfterSocketSeconds = 0,
    [switch]$RunQcl081Lsl,
    [switch]$RunQcl081LslEcho,
    [string]$Qcl081RustManifest = "S:\Work\repos\active\rusty-hostess\tools\connectivity_probe\qcl081_wifi_direct_lsl_rust\Cargo.toml",
    [string]$Qcl081RustExe = "",
    [string]$Cargo = "cargo",
    [string]$Qcl081StreamName = "RustyQCL081WifiDirect",
    [string]$Qcl081StreamType = "rusty.quest.qcl081.wifi_direct",
    [int]$Qcl081SampleCount = 0,
    [int]$Qcl081WarmupMs = 5000,
    [int]$Qcl081IntervalMs = 100,
    [double]$Qcl081TimeoutSeconds = 110.0,
    [string]$Qcl081EchoCommandStreamName = "RustyQCL081WifiDirectCommand",
    [string]$Qcl081EchoCommandStreamType = "rusty.quest.qcl081.wifi_direct.command",
    [string]$Qcl081EchoStreamName = "RustyQCL081WifiDirectEcho",
    [string]$Qcl081EchoStreamType = "rusty.quest.qcl081.wifi_direct.echo",
    [int]$Qcl081EchoSampleCount = 0,
    [int]$Qcl081EchoWarmupMs = 250,
    [int]$Qcl081EchoPreSendDelayMs = 3000,
    [int]$Qcl081EchoOutletHoldAfterMs = 5000,
    [double]$Qcl081EchoTimeoutSeconds = 110.0,
    [double]$Qcl081AnalysisWarmupSeconds = 5.0,
    [double]$Qcl081StabilitySeconds = 60.0,
    [switch]$RunQcl082ProductMedia,
    [string]$HostessCtl = "S:\Work\repos\active\rusty-hostess\tools\hostessctl\hostessctl.py",
    [string]$Qcl082WpfReceiverExe = "S:\Work\repos\active\rusty-hostess\apps\hostess-companion-wpf\bin\Debug\net9.0-windows\HostessCompanion.Wpf.exe",
    [string]$Python = "python",
    [string]$BrokerApk = "S:\Work\repos\active\rusty-quest\target\manifold-broker-android\rusty-manifold-broker.apk",
    [string]$Qcl082TopologyReport = "S:\Work\repos\active\rusty-hostess\target\connectivity-probe\qcl041-windows-legacy-ap-normalized-20260706.json",
    [string]$Qcl082FirewallReport = "S:\Work\repos\active\rusty-hostess\target\connectivity-probe\qcl082-tcp-firewall-verify.json",
    [string]$Qcl082ReceiverHost = "",
    [string]$Qcl082BindHost = "0.0.0.0",
    [int]$Qcl082Port = 9079,
    [int]$Qcl082MaxPackets = 8,
    [double]$Qcl082TimeoutSeconds = 20.0,
    [string]$Qcl082SessionId = "",
    [string]$Qcl082SenderSourceKind = "diagnostic_synthetic_mediacodec_surface",
    [string]$Qcl082SenderSourcePorts = "left:8879",
    [string]$Qcl082SenderMediaProfiles = "left:320x240@15:500000",
    [string]$Qcl082SenderCameraIds = "",
    [string]$Qcl082SenderCameraId = "",
    [string]$Qcl082SenderCameraFacing = "",
    [string]$Qcl082SenderQualityProfile = "",
    [string]$Qcl082CameraPermissionPolicy = "",
    [string]$Qcl082PreviewFfplay = "",
    [string]$QuestLeaseId = "",
    [string]$QuestLeaseResource = "",
    [switch]$ReserveQuestLease,
    [string]$AgentBoard = "C:\Users\tillh\Agent Bureau\scripts\agent-board.ps1",
    [int]$QuestLeaseWaitSeconds = 0,
    [switch]$SkipInstall
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
            observed_at_utc = (Get-Date).ToUniversalTime().ToString("O")
        })
    Write-Host ("[{0}] {1} - {2}" -f $Status, $Name, $Evidence)
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][object]$Value,
        [Parameter(Mandatory = $true)][string]$Path
    )
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $json = ($Value | ConvertTo-Json -Depth 80) + "`n"
    [System.IO.File]::WriteAllText($Path, $json, [System.Text.UTF8Encoding]::new($false))
}

function Read-JsonFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Invoke-AdbCapture {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$Name = "adb"
    )
    $oldErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $Adb -s $Serial @Arguments 2>&1 | Out-String
        return [ordered]@{
            name = $Name
            arguments = ($Arguments -join " ")
            exit_code = $LASTEXITCODE
            output = $output.Trim()
        }
    } finally {
        $ErrorActionPreference = $oldErrorActionPreference
    }
}

function Resolve-HelperExecutable {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectPath,
        [string]$ExplicitExe = ""
    )
    if (-not [string]::IsNullOrWhiteSpace($ExplicitExe)) {
        if (-not (Test-Path -LiteralPath $ExplicitExe)) {
            throw "Windows legacy AP helper executable not found: $ExplicitExe"
        }
        return (Resolve-Path -LiteralPath $ExplicitExe).Path
    }
    [xml]$projectXml = Get-Content -Raw -LiteralPath $ProjectPath
    $projectDir = Split-Path -Parent $ProjectPath
    $assemblyName = [string]$projectXml.Project.PropertyGroup.AssemblyName
    if ([string]::IsNullOrWhiteSpace($assemblyName)) {
        $assemblyName = [System.IO.Path]::GetFileNameWithoutExtension($ProjectPath)
    }
    $targetFramework = [string]$projectXml.Project.PropertyGroup.TargetFramework
    $candidate = Join-Path $projectDir "bin\Debug\$targetFramework\$assemblyName.exe"
    if (Test-Path -LiteralPath $candidate) {
        return (Resolve-Path -LiteralPath $candidate).Path
    }
    throw "Built Windows legacy AP helper executable not found: $candidate"
}

function Resolve-Qcl081RustExecutable {
    param(
        [Parameter(Mandatory = $true)][string]$ManifestPath,
        [string]$ExplicitExe = "",
        [string]$CargoCommand = "cargo"
    )
    if (-not [string]::IsNullOrWhiteSpace($ExplicitExe)) {
        if (-not (Test-Path -LiteralPath $ExplicitExe)) {
            throw "QCL-081 Rust LSL executable not found: $ExplicitExe"
        }
        return (Resolve-Path -LiteralPath $ExplicitExe).Path
    }
    if (-not (Test-Path -LiteralPath $ManifestPath)) {
        throw "QCL-081 Rust LSL Cargo manifest not found: $ManifestPath"
    }
    & $CargoCommand build --manifest-path $ManifestPath | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "cargo build failed for $ManifestPath"
    }
    $manifestDir = Split-Path -Parent (Resolve-Path -LiteralPath $ManifestPath).Path
    $candidate = Join-Path $manifestDir "target\debug\qcl081-wifi-direct-lsl-rust.exe"
    if (-not (Test-Path -LiteralPath $candidate)) {
        throw "Built QCL-081 Rust LSL executable not found: $candidate"
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Get-QuestWifiIpv4FromStatus {
    param([string]$WifiStatus)
    if ([string]::IsNullOrWhiteSpace($WifiStatus)) {
        return ""
    }
    $patterns = @(
        '(?im)\bIPv4\s+address\s*[:=]\s*(\d{1,3}(?:\.\d{1,3}){3})',
        '(?im)\bIP\s+address\s*[:=]\s*(\d{1,3}(?:\.\d{1,3}){3})',
        '(?im)\bipAddress\s*[:=]\s*(\d{1,3}(?:\.\d{1,3}){3})',
        '(?im)\baddress\s*[:=]\s*(\d{1,3}(?:\.\d{1,3}){3})'
    )
    foreach ($pattern in $patterns) {
        $match = [regex]::Match($WifiStatus, $pattern)
        if ($match.Success) {
            return $match.Groups[1].Value
        }
    }
    $fallback = [regex]::Matches($WifiStatus, '\b\d{1,3}(?:\.\d{1,3}){3}\b') |
        Where-Object { $_.Value -notlike "0.0.0.0" -and $_.Value -notlike "255.*" } |
        Select-Object -First 1
    if ($null -ne $fallback) {
        return $fallback.Value
    }
    return ""
}

function Write-Qcl081HostLslApiConfig {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ListenAddress,
        [string]$QuestAddress = ""
    )
    $peers = @()
    if (-not [string]::IsNullOrWhiteSpace($ListenAddress)) {
        $peers += $ListenAddress
    }
    if (-not [string]::IsNullOrWhiteSpace($QuestAddress)) {
        $peers += $QuestAddress
    }
    $lines = @(
        "[ports]",
        "IPv6 = disable",
        "",
        "[multicast]",
        "ResolveScope = link",
        $(if ([string]::IsNullOrWhiteSpace($ListenAddress)) { $null } else { "ListenAddress = $ListenAddress" }),
        "",
        "[lab]",
        $(if ($peers.Count -eq 0) { $null } else { "KnownPeers = {$($peers -join ', ')}" }),
        "SessionID = default",
        "",
        "[log]",
        "level = 0",
        ""
    ) | Where-Object { $null -ne $_ }
    [System.IO.File]::WriteAllText($Path, ($lines -join [Environment]::NewLine), [System.Text.UTF8Encoding]::new($false))
}

function Start-Qcl081Process {
    param(
        [Parameter(Mandatory = $true)][string]$Exe,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Stdout,
        [Parameter(Mandatory = $true)][string]$Stderr,
        [Parameter(Mandatory = $true)][string]$LslApiConfig
    )
    $previousLslApiCfg = $env:LSLAPICFG
    try {
        $env:LSLAPICFG = $LslApiConfig
        return Start-Process `
            -FilePath $Exe `
            -ArgumentList $Arguments `
            -PassThru `
            -WindowStyle Hidden `
            -RedirectStandardOutput $Stdout `
            -RedirectStandardError $Stderr
    } finally {
        if ($null -eq $previousLslApiCfg) {
            Remove-Item Env:\LSLAPICFG -ErrorAction SilentlyContinue
        } else {
            $env:LSLAPICFG = $previousLslApiCfg
        }
    }
}

function Get-LeaseId {
    param([string]$ReserveOutput)
    $patterns = @(
        'lease[-_][A-Za-z0-9._:-]+',
        '"lease_id"\s*:\s*"([^"]+)"',
        'Lease ID\s*:\s*([A-Za-z0-9._:-]+)',
        'lease_id\s*[:=]\s*([A-Za-z0-9._:-]+)',
        'id\s*[:=]\s*([A-Za-z0-9._:-]+)'
    )
    foreach ($pattern in $patterns) {
        $match = [regex]::Match($ReserveOutput, $pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if ($match.Success) {
            if ($match.Groups.Count -gt 1 -and -not [string]::IsNullOrWhiteSpace($match.Groups[1].Value)) {
                return $match.Groups[1].Value
            }
            return $match.Value
        }
    }
    throw "Could not parse Agent Board lease id from reserve output: $ReserveOutput"
}

function Assert-AgentBoardReserveSucceeded {
    param(
        [Parameter(Mandatory = $true)][string]$ReserveOutput,
        [Parameter(Mandatory = $true)][string]$Resource
    )
    if ($ReserveOutput -match 'Resource busy') {
        throw "Agent Board resource is busy for ${Resource}; refusing to continue without an owned lease. Output: $ReserveOutput"
    }
    if ($ReserveOutput -notmatch "Reserved\s+$([regex]::Escape($Resource))\s+until") {
        throw "Agent Board reserve did not confirm ownership of ${Resource}. Output: $ReserveOutput"
    }
}

function Test-Qcl082Camera2SourceKind {
    param([string]$SourceKind)
    $normalized = ([string]$SourceKind).Trim().ToLowerInvariant()
    return $normalized -eq "camera2_h264" -or $normalized -eq "camera2_surface_h264"
}

function Get-Qcl082EffectiveCameraPermissionPolicy {
    param(
        [string]$SourceKind,
        [string]$ExplicitPolicy = ""
    )
    if (-not [string]::IsNullOrWhiteSpace($ExplicitPolicy)) {
        return $ExplicitPolicy
    }
    if (Test-Qcl082Camera2SourceKind -SourceKind $SourceKind) {
        return "camera_permission_required"
    }
    return "no_camera_permission_required"
}

function Test-PermissionDeclaredInDumpsys {
    param(
        [string]$Dumpsys,
        [string]$Permission
    )
    return -not [string]::IsNullOrWhiteSpace($Dumpsys) -and $Dumpsys.Contains($Permission)
}

function Get-AndroidPermissionReadback {
    param(
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][string]$Permission
    )
    $result = Invoke-AdbCapture -Arguments @("shell", "dumpsys", "package", $PackageName) -Name "dumpsys package $PackageName"
    $escapedPermission = [regex]::Escape($Permission)
    $grantedMatch = [regex]::Match($result.output, "$escapedPermission\s*:\s*granted=(true|false)")
    $granted = $false
    if ($grantedMatch.Success) {
        $granted = $grantedMatch.Groups[1].Value -eq "true"
    }
    return [ordered]@{
        permission = $Permission
        method = "dumpsys package"
        exit_code = $result.exit_code
        declared = (Test-PermissionDeclaredInDumpsys -Dumpsys $result.output -Permission $Permission)
        grant_state_found = $grantedMatch.Success
        granted = $granted
    }
}

function Invoke-Qcl082BrokerPermissionPreflight {
    param([Parameter(Mandatory = $true)][string]$OutPath)
    $packageName = "io.github.mesmerprism.rustymanifold.broker"
    $requiredPermissions = @(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.CAMERA",
        "horizonos.permission.HEADSET_CAMERA",
        "horizonos.permission.SPATIAL_CAMERA"
    )
    $runtimeGrantPermissions = @(
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.CAMERA",
        "horizonos.permission.HEADSET_CAMERA",
        "horizonos.permission.SPATIAL_CAMERA"
    )
    $dumpsys = Invoke-AdbCapture -Arguments @("shell", "dumpsys", "package", $packageName) -Name "dumpsys package $packageName"
    $summary = [ordered]@{
        schema = "rusty.quest.android_permission_preflight.v1"
        package = $packageName
        serial = $Serial
        required_permissions = @()
        runtime_grants = @()
        note = "QCL-082 Windows legacy AP branch broker permission preflight uses serial-scoped adb before Hostess live-session starts the broker source."
    }
    foreach ($permission in $requiredPermissions) {
        $declared = Test-PermissionDeclaredInDumpsys -Dumpsys $dumpsys.output -Permission $permission
        $readback = Get-AndroidPermissionReadback -PackageName $packageName -Permission $permission
        $summary.required_permissions += [ordered]@{
            permission = $permission
            declared = $declared
            check_permission = $readback
        }
        if (-not $declared) {
            Write-JsonFile -Value $summary -Path $OutPath
            throw "$packageName does not declare required permission $permission"
        }
    }
    foreach ($permission in $runtimeGrantPermissions) {
        $grant = Invoke-AdbCapture -Arguments @("shell", "pm", "grant", $packageName, $permission) -Name "pm grant $packageName $permission"
        $readback = Get-AndroidPermissionReadback -PackageName $packageName -Permission $permission
        $summary.runtime_grants += [ordered]@{
            permission = $permission
            declared = (Test-PermissionDeclaredInDumpsys -Dumpsys $dumpsys.output -Permission $permission)
            pm_grant_exit_code = $grant.exit_code
            pm_grant_output = $grant.output
            check_permission = $readback
        }
    }
    Write-JsonFile -Value $summary -Path $OutPath
    return [pscustomobject]$summary
}

function Invoke-Qcl082ProductMediaLiveSession {
    param(
        [Parameter(Mandatory = $true)][string]$ReceiverHost,
        [string]$QuestWifiIpv4 = ""
    )
    if (-not (Test-Path -LiteralPath $HostessCtl)) {
        throw "Hostessctl not found for QCL-082 live session: $HostessCtl"
    }
    if (-not (Test-Path -LiteralPath $Qcl082TopologyReport)) {
        throw "QCL-082 topology report not found: $Qcl082TopologyReport"
    }
    if (-not (Test-Path -LiteralPath $Qcl082FirewallReport)) {
        throw "QCL-082 firewall report not found: $Qcl082FirewallReport"
    }
    if (-not (Test-Path -LiteralPath $Qcl082WpfReceiverExe)) {
        throw "QCL-082 WPF receiver executable not found: $Qcl082WpfReceiverExe"
    }

    $paramsPath = Join-Path $OutDir "qcl082-start-source.params.json"
    $requestOut = Join-Path $OutDir "qcl082-media-stream-start-source.request.json"
    $bridgeEvidenceOut = Join-Path $OutDir "qcl082-media-stream-start-source.bridge-evidence.json"
    $executionOut = Join-Path $OutDir "qcl082-media-stream-start-source.live-android-execution.json"
    $validationOut = Join-Path $OutDir "qcl082-media-stream-start-source.validation-report.json"
    $logcatOut = Join-Path $OutDir "qcl082-media-stream-start-source.logcat.txt"
    $captureOut = Join-Path $OutDir "qcl082-media-stream.rmanvid1"
    $sidecarOut = Join-Path $OutDir "qcl082-media-stream-receiver-sidecar.json"
    $resultOut = Join-Path $OutDir "qcl082-rmanvid1-receiver-result.json"
    $reportOut = Join-Path $OutDir "qcl082-product-media-live-qcl082.json"
    $receiverReadyOut = Join-Path $OutDir "qcl082-wpf-rmanvid1-receiver.ready.json"
    $receiverStdout = Join-Path $OutDir "qcl082-wpf-rmanvid1-receiver.stdout.txt"
    $receiverStderr = Join-Path $OutDir "qcl082-wpf-rmanvid1-receiver.stderr.txt"
    $remoteEndpoint = "${ReceiverHost}:$Qcl082Port"
    $timeoutText = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0}", $Qcl082TimeoutSeconds)
    $effectiveCameraPermissionPolicy = Get-Qcl082EffectiveCameraPermissionPolicy `
        -SourceKind $Qcl082SenderSourceKind `
        -ExplicitPolicy $Qcl082CameraPermissionPolicy
    $params = [ordered]@{
        session_id = $Qcl082SessionId
        sender_source_kind = $Qcl082SenderSourceKind
        sender_source_host = "127.0.0.1"
        sender_source_ports = $Qcl082SenderSourcePorts
        sender_media_profiles = $Qcl082SenderMediaProfiles
        camera_permission_policy = $effectiveCameraPermissionPolicy
        transport_routes = "left|left|direct_tcp_connect|$ReceiverHost|$Qcl082Port"
        transport_owner = "qcl041_windows_legacy_ap_active_wifi"
        receiver_host = $ReceiverHost
        receiver_port = $Qcl082Port
    }
    if (-not [string]::IsNullOrWhiteSpace($QuestWifiIpv4)) {
        $params.transport_bind_local_address = $QuestWifiIpv4
    }
    if (-not [string]::IsNullOrWhiteSpace($Qcl082SenderCameraIds)) {
        $params.sender_camera_ids = $Qcl082SenderCameraIds
    }
    if (-not [string]::IsNullOrWhiteSpace($Qcl082SenderCameraId)) {
        $params.sender_camera_id = $Qcl082SenderCameraId
    }
    if (-not [string]::IsNullOrWhiteSpace($Qcl082SenderCameraFacing)) {
        $params.sender_camera_facing = $Qcl082SenderCameraFacing
    }
    if (-not [string]::IsNullOrWhiteSpace($Qcl082SenderQualityProfile)) {
        $params.sender_quality_profile = $Qcl082SenderQualityProfile
    }
    Write-JsonFile -Value $params -Path $paramsPath

    $receiverArgs = @(
        "--qcl082-rmanvid1-receiver",
        "--out", $resultOut,
        "--capture-out", $captureOut,
        "--sidecar-out", $sidecarOut,
        "--bind-host", $Qcl082BindHost,
        "--port", $Qcl082Port.ToString(),
        "--timeout-seconds", $timeoutText,
        "--max-packets", $Qcl082MaxPackets.ToString(),
        "--runtime-status", $executionOut,
        "--topology-report", $Qcl082TopologyReport,
        "--firewall-report", $Qcl082FirewallReport,
        "--source-remote-endpoint", $remoteEndpoint,
        "--command-id", "command.media_stream.start_source",
        "--session-id", $Qcl082SessionId,
        "--quest-lease-id", $QuestLeaseId,
        "--quest-lease-resource", $QuestLeaseResource,
        "--quest-lease-reserved-before-live-steps",
        "--ready-out", $receiverReadyOut
    )
    if (-not [string]::IsNullOrWhiteSpace($Qcl082PreviewFfplay)) {
        $receiverArgs += @(
            "--preview-ffplay", $Qcl082PreviewFfplay,
            "--preview-window-title", "Rusty_QCL082_Windows_Legacy_AP_RMANVID1_Preview"
        )
    }
    if (Test-Path -LiteralPath $receiverReadyOut) {
        Remove-Item -LiteralPath $receiverReadyOut -Force
    }
    Write-Host "Starting QCL-082 WPF product receiver on ${Qcl082BindHost}:$Qcl082Port"
    $receiverProcess = Start-Process `
        -FilePath $Qcl082WpfReceiverExe `
        -ArgumentList $receiverArgs `
        -PassThru `
        -WindowStyle Hidden `
        -RedirectStandardOutput $receiverStdout `
        -RedirectStandardError $receiverStderr

    $receiverReady = $false
    try {
        $readyDeadline = (Get-Date).AddSeconds(12)
        while ((Get-Date) -lt $readyDeadline) {
            if ($receiverProcess.HasExited) {
                throw "QCL-082 WPF receiver exited before ready with code $($receiverProcess.ExitCode)."
            }
            if (Test-Path -LiteralPath $receiverReadyOut) {
                $ready = Read-JsonFile -Path $receiverReadyOut
                if ($ready.status -eq "ready") {
                    $receiverReady = $true
                    break
                }
            }
            Start-Sleep -Milliseconds 250
        }
        if (-not $receiverReady) {
            throw "QCL-082 WPF receiver did not write ready artifact before timeout: $receiverReadyOut"
        }

        & $Python @(
            $HostessCtl,
            "emit-bridge-command-request",
            "--bridge-command", "command.media_stream.start_source",
            "--request-id", "request.hostess.qcl082.windows_legacy_ap.$RunId",
            "--evidence-id", "evidence.hostess.qcl082.windows_legacy_ap.$RunId",
            "--route-id", "bridge_route.command.websocket.applied",
            "--required-stage", "sent",
            "--required-stage", "transport_ok",
            "--required-stage", "authority_accepted",
            "--params-json-file", $paramsPath,
            "--out", $requestOut
        ) | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw "QCL-082 start_source request generation failed with exit code $LASTEXITCODE"
        }

        $liveArgs = @(
        $HostessCtl,
        "run-bridge-command-live-android",
        "--input", $requestOut,
        "--out", $bridgeEvidenceOut,
        "--execution-out", $executionOut,
        "--validation-out", $validationOut,
        "--logcat-out", $logcatOut,
        "--adb", $Adb,
        "--serial", $Serial,
        "--no-runtime-receipt-subscribe",
        "--no-launch-makepad",
        "--no-wait-makepad-process"
        )
        Write-Host "Starting QCL-082 broker media source over Windows legacy AP receiver endpoint $remoteEndpoint"
        & $Python @liveArgs | Out-Host
        $liveExitCode = $LASTEXITCODE

        $receiverWaitMs = [int](($Qcl082TimeoutSeconds + 10.0) * 1000.0)
        if (-not $receiverProcess.WaitForExit($receiverWaitMs)) {
            Stop-Process -Id $receiverProcess.Id -Force
            if (-not (Test-Path -LiteralPath $resultOut)) {
                throw "QCL-082 WPF receiver did not exit before timeout and did not write $resultOut."
            }
        } elseif ($receiverProcess.ExitCode -ne 0 -and -not (Test-Path -LiteralPath $resultOut)) {
            throw "QCL-082 WPF receiver failed with exit code $($receiverProcess.ExitCode) and did not write $resultOut."
        }
    } finally {
        if ($receiverProcess -and -not $receiverProcess.HasExited) {
            Stop-Process -Id $receiverProcess.Id -Force
        }
    }

    if (-not (Test-Path -LiteralPath $resultOut)) {
        throw "QCL-082 product media live session did not write receiver result $resultOut"
    }

    & $Python @(
        $HostessCtl,
        "connectivity-probe",
        "run",
        "--mode", "fixture",
        "--probe-id", "QCL-082",
        "--media-stream-receiver-result", $resultOut,
        "--out", $reportOut
    ) | Out-Host
    $normalizeExitCode = $LASTEXITCODE
    if (-not (Test-Path -LiteralPath $reportOut)) {
        throw "QCL-082 product media normalization did not write report $reportOut"
    }
    return [ordered]@{
        live_exit_code = $liveExitCode
        normalize_exit_code = $normalizeExitCode
        params_path = $paramsPath
        request_path = $requestOut
        bridge_evidence_path = $bridgeEvidenceOut
        execution_path = $executionOut
        validation_path = $validationOut
        logcat_path = $logcatOut
        capture_path = $captureOut
        sidecar_path = $sidecarOut
        receiver_result_path = $resultOut
        qcl082_report_path = $reportOut
        receiver_ready_path = $receiverReadyOut
        receiver_stdout_path = $receiverStdout
        receiver_stderr_path = $receiverStderr
        receiver_host = $ReceiverHost
        receiver_port = $Qcl082Port
        quest_wifi_ipv4 = $QuestWifiIpv4
    }
}

function Get-LegacyApOwnerHostCandidate {
    $wifiDirectAliases = @(Get-NetAdapter -IncludeHidden -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -like "*Wi-Fi Direct*" -or $_.InterfaceDescription -like "*Wi-Fi Direct*"
        } |
        Select-Object -ExpandProperty Name)
    $candidates = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike "169.254.*" -and
            $wifiDirectAliases -contains $_.InterfaceAlias
        } |
        Sort-Object InterfaceAlias, IPAddress)
    if ($candidates.Count -gt 0) {
        return [string]$candidates[0].IPAddress
    }
    return ""
}

function Wait-LegacyApOwnerHost {
    param([string]$PreferredOwnerHost)
    if (-not [string]::IsNullOrWhiteSpace($PreferredOwnerHost)) {
        return $PreferredOwnerHost
    }
    $deadline = (Get-Date).AddSeconds(20)
    do {
        $candidate = Get-LegacyApOwnerHostCandidate
        if (-not [string]::IsNullOrWhiteSpace($candidate)) {
            return $candidate
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return "192.168.137.1"
}

function ConvertTo-AndroidShellQuotedArgument {
    param([Parameter(Mandatory = $true)][string]$Value)
    return '"' + $Value.Replace('"', '\"') + '"'
}

function Remove-QuestWifiNetwork {
    param([Parameter(Mandatory = $true)][string]$NetworkSsid)
    $list = Invoke-AdbCapture -Arguments @("shell", "cmd", "wifi", "list-networks") -Name "cmd wifi list-networks"
    $removed = @()
    foreach ($line in ([string]$list.output -split "`r?`n")) {
        if ($line -match "^\s*(\d+)\s+" -and $line.Contains($NetworkSsid)) {
            $networkId = $Matches[1]
            $forget = Invoke-AdbCapture -Arguments @("shell", "cmd", "wifi", "forget-network", $networkId) -Name "cmd wifi forget-network"
            $removed += [ordered]@{
                network_id = $networkId
                exit_code = $forget.exit_code
                output = $forget.output
            }
        }
    }
    return [ordered]@{
        list_exit_code = $list.exit_code
        removed = $removed
    }
}

function Wait-Qcl030ClientArtifact {
    param([int]$WaitSeconds)
    $artifactName = ($RunId -replace '[^A-Za-z0-9._-]', '_') + ".json"
    $remotePath = "files/qcl030/$artifactName"
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $probe = Invoke-AdbCapture `
            -Arguments @("shell", "run-as", $PackageName, "cat", $remotePath) `
            -Name "run-as cat qcl030 client artifact"
        if ($probe.exit_code -eq 0 -and -not [string]::IsNullOrWhiteSpace([string]$probe.output)) {
            try {
                $artifact = [string]$probe.output | ConvertFrom-Json
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

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "Serial is required. Pass -Serial or set RUSTY_QUEST_SERIAL."
}
if (-not (Test-Path -LiteralPath $Adb)) {
    throw "ADB not found: $Adb"
}
if (-not (Test-Path -LiteralPath $HelperProject)) {
    throw "Windows legacy AP helper project not found: $HelperProject"
}

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl041-windows-legacy-ap-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}
if ($RunQcl082ProductMedia) {
    if ([string]::IsNullOrWhiteSpace($QuestLeaseResource)) {
        $QuestLeaseResource = "quest:$Serial"
    }
    if ([string]::IsNullOrWhiteSpace($Qcl082SessionId)) {
        $Qcl082SessionId = "session.qcl082.$RunId"
    }
    if (-not $ReserveQuestLease -and [string]::IsNullOrWhiteSpace($QuestLeaseId)) {
        throw "QCL-082 product media requires -QuestLeaseId for an already reserved $QuestLeaseResource lease, or explicit -ReserveQuestLease."
    }
    if ($ReserveQuestLease -and -not (Test-Path -LiteralPath $AgentBoard)) {
        throw "Agent Board script not found for explicit QCL-082 lease reservation: $AgentBoard"
    }
    if (-not (Test-Path -LiteralPath $HostessCtl)) {
        throw "Hostessctl not found: $HostessCtl"
    }
    if (-not (Test-Path -LiteralPath $Qcl082TopologyReport)) {
        throw "QCL-082 topology report not found: $Qcl082TopologyReport"
    }
    if (-not (Test-Path -LiteralPath $Qcl082FirewallReport)) {
        throw "QCL-082 firewall report not found: $Qcl082FirewallReport"
    }
    if (-not $SkipInstall -and -not (Test-Path -LiteralPath $BrokerApk)) {
        throw "QCL-082 broker APK not found: $BrokerApk"
    }
}
$qcl081ComputedSampleCount = [int][Math]::Ceiling(((($Qcl081StabilitySeconds + $Qcl081AnalysisWarmupSeconds) * 1000.0) / [Math]::Max(1, $Qcl081IntervalMs)))
if ($Qcl081SampleCount -le 0) {
    $Qcl081SampleCount = $qcl081ComputedSampleCount
}
if ($Qcl081EchoSampleCount -le 0) {
    $Qcl081EchoSampleCount = $qcl081ComputedSampleCount
}
if (($RunQcl081Lsl -or $RunQcl081LslEcho) -and $HoldAfterSocketSeconds -le 0) {
    $HoldAfterSocketSeconds = [int][Math]::Ceiling([Math]::Max($Qcl081TimeoutSeconds, $Qcl081EchoTimeoutSeconds) + 20.0)
}
if ($RunQcl082ProductMedia -and $HoldAfterSocketSeconds -le 0) {
    $HoldAfterSocketSeconds = [int][Math]::Ceiling($Qcl082TimeoutSeconds + 45.0)
}
if ($RunQcl081Lsl -or $RunQcl081LslEcho) {
    $TimeoutSeconds = [Math]::Max($TimeoutSeconds, $HoldAfterSocketSeconds + 60)
}
if ($RunQcl082ProductMedia) {
    $TimeoutSeconds = [Math]::Max($TimeoutSeconds, $HoldAfterSocketSeconds + 60)
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $RepoRoot "target\qcl041-wifi-direct-lifecycle\$RunId"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$PackageName = "io.github.mesmerprism.rustyquest.qcl041"
$Activity = "$PackageName/.Qcl041WifiDirectHarnessActivity"
$ApkPath = Join-Path $RepoRoot "target\qcl041-wifi-direct-harness-android\rusty-quest-qcl041-wifi-direct-harness.apk"
$SummaryPath = Join-Path $OutDir "windows-legacy-ap-summary.json"
$HelperReadyPath = Join-Path $OutDir "windows-legacy-ap-ready.json"
$HelperReportPath = Join-Path $OutDir "windows-legacy-ap-helper.json"
$ClientArtifactPath = Join-Path $OutDir "quest-active-wifi-client-artifact.json"
$PrestatePath = Join-Path $OutDir "prestate.json"
$Qcl081HostLslApiConfigPath = Join-Path $OutDir "qcl081-rust-lsl-host-lsl_api.cfg"
$Qcl081ReceiverReportPath = Join-Path $OutDir "qcl081-wifi-direct-rust-lsl-receiver.json"
$Qcl081ReceiverStdoutPath = Join-Path $OutDir "qcl081-wifi-direct-rust-lsl-receiver.stdout.txt"
$Qcl081ReceiverStderrPath = Join-Path $OutDir "qcl081-wifi-direct-rust-lsl-receiver.stderr.txt"
$Qcl081EchoReportPath = Join-Path $OutDir "qcl081-wifi-direct-rust-lsl-echo-roundtrip.json"
$Qcl081EchoStdoutPath = Join-Path $OutDir "qcl081-wifi-direct-rust-lsl-echo-roundtrip.stdout.txt"
$Qcl081EchoStderrPath = Join-Path $OutDir "qcl081-wifi-direct-rust-lsl-echo-roundtrip.stderr.txt"
$Qcl081SourceId = "rusty-quest-qcl081-wifi-direct-$RunId"
$Qcl081EchoCommandSourceId = "rusty-host-qcl081-wifi-direct-command-$RunId"
$Qcl081EchoSourceId = "rusty-quest-qcl081-wifi-direct-echo-$RunId"
$Qcl082BrokerPermissionPreflightPath = Join-Path $OutDir "qcl082-broker-permission-preflight.json"
$Qcl082ParamsPath = Join-Path $OutDir "qcl082-start-source.params.json"
$Qcl082RequestPath = Join-Path $OutDir "qcl082-media-stream-start-source.request.json"
$Qcl082BridgeEvidencePath = Join-Path $OutDir "qcl082-media-stream-start-source.bridge-evidence.json"
$Qcl082ExecutionPath = Join-Path $OutDir "qcl082-media-stream-start-source.live-android-execution.json"
$Qcl082ValidationPath = Join-Path $OutDir "qcl082-media-stream-start-source.validation-report.json"
$Qcl082LogcatPath = Join-Path $OutDir "qcl082-media-stream-start-source.logcat.txt"
$Qcl082CapturePath = Join-Path $OutDir "qcl082-media-stream.rmanvid1"
$Qcl082SidecarPath = Join-Path $OutDir "qcl082-media-stream-receiver-sidecar.json"
$Qcl082ReceiverResultPath = Join-Path $OutDir "qcl082-rmanvid1-receiver-result.json"
$Qcl082ReportPath = Join-Path $OutDir "qcl082-product-media-live-qcl082.json"

$steps = [System.Collections.ArrayList]::new()
$summary = [ordered]@{
    schema = "rusty.quest.qcl041.windows_legacy_ap_probe.v1"
    run_id = $RunId
    status = "blocked"
    started_at_utc = (Get-Date).ToUniversalTime().ToString("O")
    out_dir = $OutDir
    ssid = $Ssid
    credential_sensitive_redacted = $true
    adb = $Adb
    serial = $Serial
    owner_host = ""
    udp_port = $Port
    tcp_port = $Port + 1
    socket_bytes = $SocketBytes
    steps = $steps
    results = [ordered]@{}
    artifacts = [ordered]@{
        summary = $SummaryPath
        prestate = $PrestatePath
        helper_ready = $HelperReadyPath
        helper_report = $HelperReportPath
        client_artifact = $ClientArtifactPath
        qcl081_host_lsl_api_config = $Qcl081HostLslApiConfigPath
        qcl081_rust_receiver_report = $Qcl081ReceiverReportPath
        qcl081_rust_receiver_stdout = $Qcl081ReceiverStdoutPath
        qcl081_rust_receiver_stderr = $Qcl081ReceiverStderrPath
        qcl081_rust_echo_report = $Qcl081EchoReportPath
        qcl081_rust_echo_stdout = $Qcl081EchoStdoutPath
        qcl081_rust_echo_stderr = $Qcl081EchoStderrPath
        qcl082_broker_permission_preflight = $Qcl082BrokerPermissionPreflightPath
        qcl082_start_source_params = $Qcl082ParamsPath
        qcl082_start_source_request = $Qcl082RequestPath
        qcl082_bridge_evidence = $Qcl082BridgeEvidencePath
        qcl082_live_android_execution = $Qcl082ExecutionPath
        qcl082_validation = $Qcl082ValidationPath
        qcl082_logcat = $Qcl082LogcatPath
        qcl082_capture = $Qcl082CapturePath
        qcl082_sidecar = $Qcl082SidecarPath
        qcl082_receiver_result = $Qcl082ReceiverResultPath
        qcl082_report = $Qcl082ReportPath
    }
    qcl082_product_media = [ordered]@{
        requested = [bool]$RunQcl082ProductMedia
        topology_report = $Qcl082TopologyReport
        firewall_report = $Qcl082FirewallReport
        receiver_host = $Qcl082ReceiverHost
        bind_host = $Qcl082BindHost
        port = $Qcl082Port
        session_id = $Qcl082SessionId
        sender_source_kind = $Qcl082SenderSourceKind
        quest_lease_resource = $QuestLeaseResource
        quest_lease_id = if ([string]::IsNullOrWhiteSpace($QuestLeaseId)) { "" } else { "[redacted-present]" }
        lease_reserved_by_wrapper = [bool]$ReserveQuestLease
    }
    cleanup = [ordered]@{}
}

$helperProcess = $null
$qcl081RustExePath = ""
$qcl081ReceiverProcess = $null
$qcl081EchoProcess = $null
$qcl082LeaseReservedByWrapper = $false
try {
    $prestate = [ordered]@{
        model = ((Invoke-AdbCapture -Arguments @("shell", "getprop", "ro.product.model") -Name "getprop ro.product.model").output)
        sdk = ((Invoke-AdbCapture -Arguments @("shell", "getprop", "ro.build.version.sdk") -Name "getprop ro.build.version.sdk").output)
        location_mode = ((Invoke-AdbCapture -Arguments @("shell", "settings", "get", "secure", "location_mode") -Name "settings get secure location_mode").output)
        stay_on_while_plugged_in = ((Invoke-AdbCapture -Arguments @("shell", "settings", "get", "global", "stay_on_while_plugged_in") -Name "settings get global stay_on_while_plugged_in").output)
        wifi_status_before = ((Invoke-AdbCapture -Arguments @("shell", "cmd", "wifi", "status") -Name "cmd wifi status before").output)
        windows_legacy_ap_candidates_before = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.InterfaceAlias -like "*Wi-Fi Direct*" -or $_.InterfaceAlias -like "Local Area Connection*" } |
            Select-Object InterfaceAlias, IPAddress)
    }
    Write-JsonFile -Value $prestate -Path $PrestatePath
    Add-Step -Steps $steps -Name "prestate" -Status "pass" -Evidence "model=$($prestate.model); location_mode=$($prestate.location_mode); stay_on=$($prestate.stay_on_while_plugged_in)"

    if ($RunQcl082ProductMedia -and $ReserveQuestLease) {
        $reserveArgs = @(
            "reserve",
            $QuestLeaseResource,
            "--duration",
            "45m",
            "--task",
            "QCL-082 Windows legacy AP product media",
            "--reason",
            "Branch-matched RMANVID1 receiver capture over Windows legacy AP"
        )
        if ($QuestLeaseWaitSeconds -gt 0) {
            $reserveArgs += @(
                "--wait",
                "--timeout",
                $QuestLeaseWaitSeconds.ToString(),
                "--poll",
                "5"
            )
        }
        Write-Host "Checking Agent Board status before reserving $QuestLeaseResource"
        & $AgentBoard status | Out-Host
        $reserveOutput = & $AgentBoard @reserveArgs | Out-String
        Write-Host $reserveOutput
        Assert-AgentBoardReserveSucceeded -ReserveOutput $reserveOutput -Resource $QuestLeaseResource
        $QuestLeaseId = Get-LeaseId $reserveOutput
        $qcl082LeaseReservedByWrapper = $true
        $summary.qcl082_product_media.quest_lease_id = "[reserved-by-wrapper]"
        $summary.qcl082_product_media.lease_reserved_by_wrapper = $true
        Add-Step -Steps $steps -Name "qcl082_quest_lease_reserve" -Status "pass" -Evidence "reserved $QuestLeaseResource before live product-media steps"
    } elseif ($RunQcl082ProductMedia) {
        Add-Step -Steps $steps -Name "qcl082_quest_lease_reserve" -Status "pass" -Evidence "using caller-supplied $QuestLeaseResource lease id before live product-media steps"
    }

    if (-not (Test-Path -LiteralPath $ApkPath)) {
        throw "QCL041 shared APK missing: $ApkPath"
    }
    if (-not $SkipInstall) {
        $install = Invoke-AdbCapture -Arguments @("install", "-r", $ApkPath) -Name "adb install QCL041 shared APK"
        if ($install.exit_code -ne 0) {
            throw "APK install failed: $($install.output)"
        }
        Add-Step -Steps $steps -Name "apk_install" -Status "pass" -Evidence "installed QCL041 shared APK"
        if ($RunQcl082ProductMedia) {
            $brokerInstall = Invoke-AdbCapture -Arguments @("install", "-r", $BrokerApk) -Name "adb install QCL082 Manifold broker APK"
            if ($brokerInstall.exit_code -ne 0) {
                throw "QCL-082 broker APK install failed: $($brokerInstall.output)"
            }
            Add-Step -Steps $steps -Name "qcl082_broker_apk_install" -Status "pass" -Evidence "installed Manifold broker APK"
        }
    } else {
        Add-Step -Steps $steps -Name "apk_install" -Status "skipped" -Evidence "SkipInstall set"
        if ($RunQcl082ProductMedia) {
            Add-Step -Steps $steps -Name "qcl082_broker_apk_install" -Status "skipped" -Evidence "SkipInstall set; assuming Manifold broker APK is already installed"
        }
    }

    foreach ($permission in @("android.permission.NEARBY_WIFI_DEVICES", "android.permission.POST_NOTIFICATIONS")) {
        $grant = Invoke-AdbCapture -Arguments @("shell", "pm", "grant", $PackageName, $permission) -Name "pm grant $permission"
        $summary.results["grant_$($permission.Split('.')[-1])"] = [ordered]@{
            exit_code = $grant.exit_code
            output = $grant.output
        }
    }
    Add-Step -Steps $steps -Name "permission_grants" -Status "pass" -Evidence "attempted runtime grants for active-Wi-Fi client evidence"
    if ($RunQcl082ProductMedia) {
        $brokerPermission = Invoke-Qcl082BrokerPermissionPreflight -OutPath $Qcl082BrokerPermissionPreflightPath
        $summary.results.qcl082_broker_permission_preflight = [ordered]@{
            artifact = $Qcl082BrokerPermissionPreflightPath
            required_permission_count = @($brokerPermission.required_permissions).Count
            runtime_grant_count = @($brokerPermission.runtime_grants).Count
        }
        Add-Step -Steps $steps -Name "qcl082_broker_permission_preflight" -Status "pass" -Evidence $Qcl082BrokerPermissionPreflightPath
    }

    dotnet build $HelperProject | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "dotnet build failed for $HelperProject"
    }
    $helperExePath = Resolve-HelperExecutable -ProjectPath $HelperProject -ExplicitExe $HelperExe
    Add-Step -Steps $steps -Name "helper_build" -Status "pass" -Evidence $helperExePath
    if ($RunQcl081Lsl -or $RunQcl081LslEcho) {
        $qcl081RustExePath = Resolve-Qcl081RustExecutable `
            -ManifestPath $Qcl081RustManifest `
            -ExplicitExe $Qcl081RustExe `
            -CargoCommand $Cargo
        $summary.results.qcl081_rust_wrapper = [ordered]@{
            crate = "lsl"
            crate_version = "0.1.1"
            license = "MIT"
            executable = $qcl081RustExePath
            manifest = $Qcl081RustManifest
            echo_samples = $Qcl081EchoSampleCount
            interval_ms = $Qcl081IntervalMs
            analysis_warmup_seconds = $Qcl081AnalysisWarmupSeconds
            stability_seconds = $Qcl081StabilitySeconds
        }
        Add-Step -Steps $steps -Name "qcl081_rust_lsl_build" -Status "pass" -Evidence $qcl081RustExePath
    }

    $helperArgs = @(
        "--run-id", $RunId,
        "--out", $HelperReportPath,
        "--ready-out", $HelperReadyPath,
        "--ssid", $Ssid,
        "--passphrase", $Passphrase,
        "--udp-port", $Port.ToString(),
        "--tcp-port", ($Port + 1).ToString(),
        "--timeout-seconds", $TimeoutSeconds.ToString(),
        "--hold-after-socket-seconds", $HoldAfterSocketSeconds.ToString(),
        "--expected-bytes", $SocketBytes.ToString()
    )
    if (-not [string]::IsNullOrWhiteSpace($OwnerHost)) {
        $helperArgs += @("--owner-host", $OwnerHost)
    }
    $helperProcess = Start-Process -FilePath $helperExePath -ArgumentList $helperArgs -PassThru -WindowStyle Hidden
    Add-Step -Steps $steps -Name "windows_legacy_ap_start" -Status "pass" -Evidence "started Windows Wi-Fi Direct LegacySettings AP helper"

    $readyDeadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $readyDeadline -and -not (Test-Path -LiteralPath $HelperReadyPath)) {
        Start-Sleep -Milliseconds 500
    }
    if (Test-Path -LiteralPath $HelperReadyPath) {
        $helperReady = Read-JsonFile -Path $HelperReadyPath
        $summary.results.helper_ready_status = $helperReady.status
        $summary.results.helper_ready_selected_owner_host = $helperReady.selected_owner_host
    } else {
        $summary.results.helper_ready_status = "missing"
    }
    $resolvedOwnerHost = Wait-LegacyApOwnerHost -PreferredOwnerHost $OwnerHost
    $summary.owner_host = $resolvedOwnerHost
    Add-Step -Steps $steps -Name "windows_owner_host" -Status $(if ($resolvedOwnerHost -eq "192.168.137.1" -and [string]::IsNullOrWhiteSpace((Get-LegacyApOwnerHostCandidate))) { "warn" } else { "pass" }) -Evidence "owner_host=$resolvedOwnerHost"

    $forgetBefore = Remove-QuestWifiNetwork -NetworkSsid $Ssid
    $summary.results.quest_forget_before = $forgetBefore
    $remoteSsid = ConvertTo-AndroidShellQuotedArgument -Value $Ssid
    $remotePassphrase = ConvertTo-AndroidShellQuotedArgument -Value $Passphrase
    $connect = Invoke-AdbCapture -Arguments @("shell", "cmd", "wifi", "connect-network", $remoteSsid, "wpa2", $remotePassphrase) -Name "cmd wifi connect-network Windows legacy AP"
    $summary.results.quest_connect_network = [ordered]@{
        exit_code = $connect.exit_code
        output = $connect.output.Replace($Passphrase, "[redacted]")
    }
    Add-Step -Steps $steps -Name "quest_connect_network" -Status $(if ($connect.exit_code -eq 0) { "pass" } else { "warn" }) -Evidence "cmd wifi connect-network exit=$($connect.exit_code)"

    $connected = $false
    $lastWifiStatus = ""
    $joinDeadline = (Get-Date).AddSeconds([Math]::Max(5, $JoinWaitSeconds))
    do {
        Start-Sleep -Seconds 2
        $statusProbe = Invoke-AdbCapture -Arguments @("shell", "cmd", "wifi", "status") -Name "cmd wifi status after connect"
        $lastWifiStatus = [string]$statusProbe.output
        if ($lastWifiStatus.Contains($Ssid)) {
            $connected = $true
            break
        }
    } while ((Get-Date) -lt $joinDeadline)
    $summary.results.quest_connected_to_windows_legacy_ap = $connected
    $summary.results.quest_wifi_status_after_connect = $lastWifiStatus.Replace($Passphrase, "[redacted]").Replace($Ssid, "[target-ssid]")
    Add-Step -Steps $steps -Name "quest_join_windows_legacy_ap" -Status $(if ($connected) { "pass" } else { "blocked" }) -Evidence $(if ($connected) { "Quest active Wi-Fi status contains target SSID" } else { "Quest did not report target SSID before timeout" })

    if (-not $connected) {
        $summary.status = "blocked"
        throw "QCL041_BLOCKED:quest_join_windows_legacy_ap"
    }

    $questWifiIpv4 = Get-QuestWifiIpv4FromStatus -WifiStatus $lastWifiStatus
    $summary.results.quest_wifi_ipv4 = $questWifiIpv4
    if ($RunQcl081Lsl -or $RunQcl081LslEcho) {
        Write-Qcl081HostLslApiConfig `
            -Path $Qcl081HostLslApiConfigPath `
            -ListenAddress $resolvedOwnerHost `
            -QuestAddress $questWifiIpv4
        Add-Step -Steps $steps -Name "qcl081_host_lsl_api_config" -Status "pass" -Evidence "listen=$resolvedOwnerHost; quest=$questWifiIpv4"
    }
    if ($RunQcl081Lsl) {
        foreach ($path in @($Qcl081ReceiverReportPath, $Qcl081ReceiverStdoutPath, $Qcl081ReceiverStderrPath)) {
            if (Test-Path -LiteralPath $path) {
                Remove-Item -LiteralPath $path -Force
            }
        }
        $receiverArgs = @(
            "receiver",
            "--run-id", $RunId,
            "--out", $Qcl081ReceiverReportPath,
            "--stream-name", $Qcl081StreamName,
            "--stream-type", $Qcl081StreamType,
            "--source-id", $Qcl081SourceId,
            "--sample-count", $Qcl081SampleCount.ToString(),
            "--timeout-seconds", ([string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0}", $Qcl081TimeoutSeconds)),
            "--topology-report", $SummaryPath
        )
        $qcl081ReceiverProcess = Start-Qcl081Process `
            -Exe $qcl081RustExePath `
            -Arguments $receiverArgs `
            -Stdout $Qcl081ReceiverStdoutPath `
            -Stderr $Qcl081ReceiverStderrPath `
            -LslApiConfig $Qcl081HostLslApiConfigPath
        Add-Step -Steps $steps -Name "qcl081_rust_lsl_receiver_start" -Status "pass" -Evidence "source_id=$Qcl081SourceId"
        Start-Sleep -Milliseconds 500
    }
    if ($RunQcl081LslEcho) {
        foreach ($path in @($Qcl081EchoReportPath, $Qcl081EchoStdoutPath, $Qcl081EchoStderrPath)) {
            if (Test-Path -LiteralPath $path) {
                Remove-Item -LiteralPath $path -Force
            }
        }
        $echoArgs = @(
            "echo-roundtrip",
            "--run-id", $RunId,
            "--out", $Qcl081EchoReportPath,
            "--command-stream-name", $Qcl081EchoCommandStreamName,
            "--command-stream-type", $Qcl081EchoCommandStreamType,
            "--command-source-id", $Qcl081EchoCommandSourceId,
            "--echo-stream-name", $Qcl081EchoStreamName,
            "--echo-stream-type", $Qcl081EchoStreamType,
            "--echo-source-id", $Qcl081EchoSourceId,
            "--sample-count", $Qcl081EchoSampleCount.ToString(),
            "--interval-ms", $Qcl081IntervalMs.ToString(),
            "--pre-send-delay-ms", $Qcl081EchoPreSendDelayMs.ToString(),
            "--timeout-seconds", ([string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0}", $Qcl081EchoTimeoutSeconds)),
            "--analysis-warmup-seconds", ([string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0}", $Qcl081AnalysisWarmupSeconds)),
            "--topology-report", $SummaryPath
        )
        $qcl081EchoProcess = Start-Qcl081Process `
            -Exe $qcl081RustExePath `
            -Arguments $echoArgs `
            -Stdout $Qcl081EchoStdoutPath `
            -Stderr $Qcl081EchoStderrPath `
            -LslApiConfig $Qcl081HostLslApiConfigPath
        Add-Step -Steps $steps -Name "qcl081_rust_lsl_echo_start" -Status "pass" -Evidence "samples=$Qcl081EchoSampleCount interval_ms=$Qcl081IntervalMs warmup_excluded_seconds=$Qcl081AnalysisWarmupSeconds"
        Start-Sleep -Milliseconds 500
    }

    $forceStop = Invoke-AdbCapture -Arguments @("shell", "am", "force-stop", $PackageName) -Name "force-stop QCL041 before client"
    $summary.results.force_stop_before_client = $forceStop
    $artifactName = ($RunId -replace '[^A-Za-z0-9._-]', '_') + ".json"
    Invoke-AdbCapture -Arguments @("shell", "run-as", $PackageName, "rm", "-f", "files/qcl030/$artifactName", "files/qcl030/latest.json") -Name "remove stale qcl030 artifacts" | Out-Null
    $socketTimeoutMs = [Math]::Max(1, [Math]::Min($TimeoutSeconds, 120)) * 1000
    $clientArgs = @(
        "shell", "am", "start",
        "-n", $Activity,
        "--es", "qcl041.run_id", $RunId,
        "--es", "qcl041.device_serial", $Serial,
        "--es", "qcl041.device_model", "Quest",
        "--es", "qcl041.lease_id", "manual-no-lease",
        "--es", "qcl041.lease_resource", "quest:$Serial",
        "--es", "qcl041.host_toolchain_profile", "qcl041_windows_legacy_ap_active_wifi_client",
        "--ez", "qcl041.lease_reserved_before_live_steps", "false",
        "--ez", "qcl041.lease_released_after_live_steps", "false",
        "--ez", "qcl041.qcl030_local_only_hotspot_enabled", "true",
        "--es", "qcl041.qcl030_local_only_hotspot_role", "hotspot_client",
        "--es", "qcl041.qcl030_local_only_hotspot_ssid", $Ssid,
        "--es", "qcl041.qcl030_local_only_hotspot_passphrase", $Passphrase,
        "--es", "qcl041.qcl030_local_only_hotspot_owner_host", $resolvedOwnerHost,
        "--es", "qcl041.qcl030_local_only_hotspot_client_join_mode", "active_wifi",
        "--ez", "qcl041.qcl030_local_only_hotspot_require_ssid_match", "true",
        "--ei", "qcl041.qcl030_local_only_hotspot_port", $Port.ToString(),
        "--ei", "qcl041.qcl030_local_only_hotspot_socket_bytes", $SocketBytes.ToString(),
        "--ei", "qcl041.qcl030_local_only_hotspot_socket_timeout_ms", $socketTimeoutMs.ToString()
    )
    if ($RunQcl081Lsl) {
        $clientArgs += @(
            "--ez", "qcl041.qcl081_lsl_enabled", "true",
            "--es", "qcl041.qcl081_lsl_backend", "liblsl",
            "--es", "qcl041.qcl081_lsl_stream_name", $Qcl081StreamName,
            "--es", "qcl041.qcl081_lsl_stream_type", $Qcl081StreamType,
            "--es", "qcl041.qcl081_lsl_source_id", $Qcl081SourceId,
            "--ei", "qcl041.qcl081_lsl_sample_count", $Qcl081SampleCount.ToString(),
            "--ei", "qcl041.qcl081_lsl_warmup_ms", $Qcl081WarmupMs.ToString(),
            "--ei", "qcl041.qcl081_lsl_interval_ms", $Qcl081IntervalMs.ToString()
        )
    }
    if ($RunQcl081LslEcho) {
        $clientArgs += @(
            "--ez", "qcl041.qcl081_lsl_echo_enabled", "true",
            "--es", "qcl041.qcl081_lsl_echo_command_stream_name", $Qcl081EchoCommandStreamName,
            "--es", "qcl041.qcl081_lsl_echo_command_stream_type", $Qcl081EchoCommandStreamType,
            "--es", "qcl041.qcl081_lsl_echo_command_source_id", $Qcl081EchoCommandSourceId,
            "--es", "qcl041.qcl081_lsl_echo_stream_name", $Qcl081EchoStreamName,
            "--es", "qcl041.qcl081_lsl_echo_stream_type", $Qcl081EchoStreamType,
            "--es", "qcl041.qcl081_lsl_echo_source_id", $Qcl081EchoSourceId,
            "--ei", "qcl041.qcl081_lsl_echo_sample_count", $Qcl081EchoSampleCount.ToString(),
            "--ei", "qcl041.qcl081_lsl_echo_warmup_ms", $Qcl081EchoWarmupMs.ToString(),
            "--ei", "qcl041.qcl081_lsl_echo_outlet_hold_after_ms", $Qcl081EchoOutletHoldAfterMs.ToString(),
            "--ei", "qcl041.qcl081_lsl_echo_timeout_seconds", ([int][Math]::Ceiling($Qcl081EchoTimeoutSeconds)).ToString()
        )
    }
    $clientLaunch = Invoke-AdbCapture -Arguments $clientArgs -Name "launch QCL030 active-Wi-Fi client"
    $summary.results.client_launch = [ordered]@{
        exit_code = $clientLaunch.exit_code
        output = $clientLaunch.output.Replace($Passphrase, "[redacted]").Replace($Ssid, "[target-ssid]")
    }
    if ($clientLaunch.exit_code -ne 0) {
        Add-Step -Steps $steps -Name "quest_active_wifi_client_launch" -Status "blocked" -Evidence "am start exit=$($clientLaunch.exit_code)"
        $summary.status = "blocked"
        throw "QCL041_BLOCKED:quest_active_wifi_client_launch"
    }
    Add-Step -Steps $steps -Name "quest_active_wifi_client_launch" -Status "pass" -Evidence "launched QCL030 active-Wi-Fi client against Windows owner host"

    $clientArtifact = Wait-Qcl030ClientArtifact -WaitSeconds ([Math]::Max($TimeoutSeconds, 30))
    if ($null -ne $clientArtifact) {
        Write-JsonFile -Value $clientArtifact -Path $ClientArtifactPath
    }
    $summary.results.client_artifact_status = if ($null -eq $clientArtifact) { "missing" } else { [string]$clientArtifact.status }
    $summary.results.client_blocked_reason = if ($null -eq $clientArtifact) { "artifact_wait_timeout" } else { [string]$clientArtifact.blocked_reason }
    if ($null -eq $clientArtifact -or $clientArtifact.status -ne "pass") {
        Add-Step -Steps $steps -Name "quest_active_wifi_client_artifact" -Status "blocked" -Evidence $summary.results.client_blocked_reason
    } else {
        Add-Step -Steps $steps -Name "quest_active_wifi_client_artifact" -Status "pass" -Evidence "client sent UDP/TCP bytes from active Wi-Fi network"
    }

    if ($RunQcl082ProductMedia -and $null -ne $clientArtifact -and $clientArtifact.status -eq "pass") {
        $qcl082ReceiverHost = if ([string]::IsNullOrWhiteSpace($Qcl082ReceiverHost)) { $resolvedOwnerHost } else { $Qcl082ReceiverHost }
        $summary.qcl082_product_media.receiver_host = $qcl082ReceiverHost
        $summary.qcl082_product_media.quest_wifi_ipv4 = $questWifiIpv4
        $qcl082Live = Invoke-Qcl082ProductMediaLiveSession `
            -ReceiverHost $qcl082ReceiverHost `
            -QuestWifiIpv4 $questWifiIpv4
        $summary.results.qcl082_live_session = $qcl082Live
        if (Test-Path -LiteralPath $Qcl082ReceiverResultPath) {
            $qcl082ReceiverResult = Read-JsonFile -Path $Qcl082ReceiverResultPath
            $summary.results.qcl082_receiver_result_status = [string]$qcl082ReceiverResult.status
            $summary.results.qcl082_receiver_result_close_reason = [string]$qcl082ReceiverResult.close_reason
            $summary.results.qcl082_receiver_result_issue_codes = @($qcl082ReceiverResult.issue_codes)
            Add-Step -Steps $steps -Name "qcl082_product_media_receiver_result" -Status $(if ($qcl082ReceiverResult.status -eq "pass") { "pass" } else { "blocked" }) -Evidence "status=$($qcl082ReceiverResult.status); close_reason=$($qcl082ReceiverResult.close_reason)"
        } else {
            $summary.results.qcl082_receiver_result_status = "missing"
            Add-Step -Steps $steps -Name "qcl082_product_media_receiver_result" -Status "blocked" -Evidence "missing $Qcl082ReceiverResultPath"
        }
        if (Test-Path -LiteralPath $Qcl082ReportPath) {
            $qcl082Report = Read-JsonFile -Path $Qcl082ReportPath
            $summary.results.qcl082_report_status = [string]$qcl082Report.status
            $summary.results.qcl082_promotion_allowed = $qcl082Report.promotion.allowed -eq $true
            $summary.results.qcl082_media_product_topology_ready = $qcl082Report.measurements.media_product_topology_ready -eq $true
            $summary.results.qcl082_product_listener_firewall_verified = $qcl082Report.measurements.media_product_listener_firewall_verified -eq $true
            Add-Step -Steps $steps -Name "qcl082_product_media_report" -Status $(if ($summary.results.qcl082_promotion_allowed) { "pass" } else { "blocked" }) -Evidence "status=$($qcl082Report.status); promotion_allowed=$($summary.results.qcl082_promotion_allowed)"
        } else {
            $summary.results.qcl082_report_status = "missing"
            Add-Step -Steps $steps -Name "qcl082_product_media_report" -Status "blocked" -Evidence "missing $Qcl082ReportPath"
        }
    } elseif ($RunQcl082ProductMedia) {
        $summary.results.qcl082_receiver_result_status = "skipped"
        $summary.results.qcl082_report_status = "skipped"
        Add-Step -Steps $steps -Name "qcl082_product_media_live_session" -Status "blocked" -Evidence "active-Wi-Fi client proof did not pass"
    }

    if ($qcl081ReceiverProcess -and -not $qcl081ReceiverProcess.HasExited) {
        $qcl081ReceiverProcess.WaitForExit([int](($Qcl081TimeoutSeconds + 10.0) * 1000.0)) | Out-Null
    }
    if ($qcl081EchoProcess -and -not $qcl081EchoProcess.HasExited) {
        $qcl081EchoProcess.WaitForExit([int](($Qcl081EchoTimeoutSeconds + 10.0) * 1000.0)) | Out-Null
    }
    if ($RunQcl081Lsl) {
        if (Test-Path -LiteralPath $Qcl081ReceiverReportPath) {
            $qcl081ReceiverReport = Read-JsonFile -Path $Qcl081ReceiverReportPath
            $summary.results.qcl081_receiver_status = [string]$qcl081ReceiverReport.status
            $summary.results.qcl081_receiver_samples_received = $qcl081ReceiverReport.samples_received
            $summary.results.qcl081_receiver_loss_percent = $qcl081ReceiverReport.loss_percent
            $summary.results.qcl081_receiver_issue_codes = @($qcl081ReceiverReport.issue_codes)
            Add-Step -Steps $steps -Name "qcl081_rust_lsl_receiver_report" -Status $summary.results.qcl081_receiver_status -Evidence "samples=$($qcl081ReceiverReport.samples_received)/$($qcl081ReceiverReport.samples_requested); loss=$($qcl081ReceiverReport.loss_percent)%"
        } else {
            $summary.results.qcl081_receiver_status = "missing"
            Add-Step -Steps $steps -Name "qcl081_rust_lsl_receiver_report" -Status "blocked" -Evidence "missing report $Qcl081ReceiverReportPath"
        }
    }
    if ($RunQcl081LslEcho) {
        if (Test-Path -LiteralPath $Qcl081EchoReportPath) {
            $qcl081EchoReport = Read-JsonFile -Path $Qcl081EchoReportPath
            $summary.results.qcl081_echo_status = [string]$qcl081EchoReport.status
            $summary.results.qcl081_echo_samples_matched = $qcl081EchoReport.samples_matched
            $summary.results.qcl081_echo_loss_percent = $qcl081EchoReport.loss_percent
            $summary.results.qcl081_echo_analysis_window = $qcl081EchoReport.analysis_window
            $summary.results.qcl081_echo_latency_after_warmup = $qcl081EchoReport.latency_ms_summary_after_warmup
            $summary.results.qcl081_echo_stability_after_warmup = $qcl081EchoReport.stability_after_warmup
            $summary.results.qcl081_echo_issue_codes = @($qcl081EchoReport.issue_codes)
            $rtt = $qcl081EchoReport.latency_ms_summary_after_warmup.round_trip
            Add-Step -Steps $steps -Name "qcl081_rust_lsl_echo_report" -Status $summary.results.qcl081_echo_status -Evidence "matched=$($qcl081EchoReport.samples_matched)/$($qcl081EchoReport.samples_requested); rtt_median_ms=$($rtt.median); rtt_p95_ms=$($rtt.p95)"
        } else {
            $summary.results.qcl081_echo_status = "missing"
            Add-Step -Steps $steps -Name "qcl081_rust_lsl_echo_report" -Status "blocked" -Evidence "missing report $Qcl081EchoReportPath"
        }
    }

    if ($helperProcess -and -not $helperProcess.HasExited) {
        $helperProcess.WaitForExit(([Math]::Max(10, $TimeoutSeconds) + 10) * 1000) | Out-Null
    }
    if (Test-Path -LiteralPath $HelperReportPath) {
        $helperReport = Read-JsonFile -Path $HelperReportPath
        $summary.results.helper_status = $helperReport.status
        $summary.results.helper_udp_bytes = $helperReport.measurements.udp_bytes
        $summary.results.helper_tcp_bytes = $helperReport.measurements.tcp_bytes
        $summary.results.helper_tcp_ack_bytes = $helperReport.measurements.tcp_ack_bytes
        $summary.results.helper_publisher_status = $helperReport.measurements.publisher_status
    } else {
        $summary.results.helper_status = "missing"
    }
    $pass = $connected `
        -and $null -ne $clientArtifact `
        -and $clientArtifact.status -eq "pass" `
        -and $summary.results.helper_status -eq "pass" `
        -and (-not $RunQcl081Lsl -or $summary.results.qcl081_receiver_status -eq "pass") `
        -and (-not $RunQcl081LslEcho -or $summary.results.qcl081_echo_status -eq "pass") `
        -and (-not $RunQcl082ProductMedia -or $summary.results.qcl082_promotion_allowed -eq $true)
    $summary.status = if ($pass) { "pass" } else { "blocked" }
    Add-Step -Steps $steps -Name "legacy_ap_probe_final" -Status $summary.status -Evidence "helper=$($summary.results.helper_status); client=$($summary.results.client_artifact_status)"
} catch {
    if ($_.Exception.Message.StartsWith("QCL041_BLOCKED:", [System.StringComparison]::Ordinal)) {
        if ($summary.status -ne "blocked") {
            $summary.status = "blocked"
        }
        $summary.results.blocked_exception = $_.Exception.Message
    } else {
        $summary.status = "fail"
        $summary.results.exception = $_.Exception.ToString()
        Add-Step -Steps $steps -Name "probe_exception" -Status "fail" -Evidence $_.Exception.Message
        throw
    }
} finally {
    if ($qcl081ReceiverProcess -and -not $qcl081ReceiverProcess.HasExited) {
        Stop-Process -Id $qcl081ReceiverProcess.Id -Force
        $summary.cleanup.qcl081_receiver_process_stopped = $true
    }
    if ($qcl081EchoProcess -and -not $qcl081EchoProcess.HasExited) {
        Stop-Process -Id $qcl081EchoProcess.Id -Force
        $summary.cleanup.qcl081_echo_process_stopped = $true
    }
    if ($helperProcess -and -not $helperProcess.HasExited) {
        Stop-Process -Id $helperProcess.Id -Force
        $summary.cleanup.helper_process_stopped = $true
    }
    try {
        $summary.cleanup.quest_wifi_forget = Remove-QuestWifiNetwork -NetworkSsid $Ssid
    } catch {
        $summary.cleanup.quest_wifi_forget_error = $_.Exception.Message
    }
    try {
        $summary.cleanup.quest_force_stop = Invoke-AdbCapture -Arguments @("shell", "am", "force-stop", $PackageName) -Name "force-stop QCL041 cleanup"
    } catch {
        $summary.cleanup.quest_force_stop_error = $_.Exception.Message
    }
    try {
        $summary.cleanup.wifi_status_after_cleanup = ((Invoke-AdbCapture -Arguments @("shell", "cmd", "wifi", "status") -Name "cmd wifi status cleanup").output).Replace($Passphrase, "[redacted]").Replace($Ssid, "[target-ssid]")
    } catch {
        $summary.cleanup.wifi_status_after_cleanup_error = $_.Exception.Message
    }
    if ($qcl082LeaseReservedByWrapper -and -not [string]::IsNullOrWhiteSpace($QuestLeaseId)) {
        try {
            $releaseOutput = & $AgentBoard release $QuestLeaseId --result done | Out-String
            $summary.cleanup.qcl082_quest_lease_release = [ordered]@{
                lease_id = $QuestLeaseId
                resource = $QuestLeaseResource
                output = $releaseOutput.Trim()
            }
        } catch {
            $summary.cleanup.qcl082_quest_lease_release_error = $_.Exception.Message
        }
    }
    $summary.ended_at_utc = (Get-Date).ToUniversalTime().ToString("O")
    Write-JsonFile -Value $summary -Path $SummaryPath
    Write-Host "summary=$SummaryPath"
}

if ($summary.status -eq "pass") {
    exit 0
}
if ($summary.status -eq "fail") {
    exit 2
}
exit 3
