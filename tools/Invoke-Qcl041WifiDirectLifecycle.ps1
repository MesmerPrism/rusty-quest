param(
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$Adb = $(if ($env:ADB) { $env:ADB } else { "adb" }),
    [string]$WindowsHelperProject = "S:\Work\repos\active\rusty-hostess\tools\connectivity_probe\qcl041_wifi_direct_peer_helper\qcl041-wifi-direct-peer-helper.csproj",
    [string]$WindowsHelperExe = "",
    [string]$AgentBoard = "C:\Users\tillh\Agent Bureau\scripts\agent-board.ps1",
    [string]$RunId = "",
    [string]$OutDir = "",
    [int]$ListenPort = 18768,
    [int]$TimeoutSeconds = 45,
    [int]$SocketTimeoutSeconds = 20,
    [int]$GroupOwnerIntent = 0,
    [string]$WindowsPeerNameContains = "",
    [switch]$RunQcl081Lsl,
    [string]$Qcl081ReceiverScript = "S:\Work\repos\active\rusty-hostess\tools\connectivity_probe\qcl081_wifi_direct_lsl_receiver.py",
    [ValidateSet("pylsl")]
    [string]$Qcl081ReceiverBackend = "pylsl",
    [string]$Qcl081StreamName = "RustyQCL081WifiDirect",
    [string]$Qcl081StreamType = "rusty.quest.qcl081.wifi_direct",
    [ValidateSet("liblsl")]
    [string]$Qcl081LslBackend = "liblsl",
    [int]$Qcl081SampleCount = 16,
    [int]$Qcl081WarmupMs = 1200,
    [int]$Qcl081IntervalMs = 10,
    [double]$Qcl081TimeoutSeconds = 25.0,
    [int]$HoldAfterSocketSeconds = 0,
    [switch]$RunQcl082ProductMedia,
    [string]$HostessCtl = "S:\Work\repos\active\rusty-hostess\tools\hostessctl\hostessctl.py",
    [string]$Qcl082WpfReceiverExe = "S:\Work\repos\active\rusty-hostess\apps\hostess-companion-wpf\bin\Debug\net9.0-windows\HostessCompanion.Wpf.exe",
    [switch]$RunQcl082LivePreview,
    [string]$Qcl082Ffplay = "S:\Work\tools\ffmpeg\bin\ffplay.exe",
    [string]$Qcl082TopologyReport = "S:\Work\repos\active\rusty-hostess\target\connectivity-probe\qcl041-live-wifi-direct-lifecycle.json",
    [string]$Qcl082FirewallReport = "S:\Work\repos\active\rusty-hostess\target\connectivity-probe\qcl082-tcp-firewall-verify.json",
    [string]$Qcl082ReceiverHost = "192.168.137.1",
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
    [string]$Python = "python",
    [int]$QuestLeaseWaitSeconds = 0,
    [switch]$Build,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"

function Invoke-Checked {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [Parameter(Mandatory=$true)]
        [string]$File,
        [string[]]$Arguments = @()
    )

    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -Path $Path | ConvertFrom-Json
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory=$true)]
        [object]$Value,
        [Parameter(Mandatory=$true)]
        [string]$Path
    )
    $Value | ConvertTo-Json -Depth 64 | Set-Content -Encoding UTF8 -Path $Path
}

function Test-Qcl082Camera2SourceKind {
    param([string]$SourceKind)
    $normalized = if ($null -eq $SourceKind) { "" } else { $SourceKind.Trim().ToLowerInvariant() }
    return $normalized -in @(
        "camera2",
        "camera2_surface",
        "quest-camera2",
        "android-phone-camera2",
        "camera2_mediacodec_surface"
    )
}

function Get-Qcl082EffectiveCameraPermissionPolicy {
    param(
        [string]$SourceKind,
        [string]$ExplicitPolicy
    )
    if (-not [string]::IsNullOrWhiteSpace($ExplicitPolicy)) {
        return $ExplicitPolicy
    }
    if (Test-Qcl082Camera2SourceKind -SourceKind $SourceKind) {
        return "camera_permission_required"
    }
    return "no_camera_permission_required"
}

function Get-Qcl082RelaySourcePort {
    param([string]$SenderSourcePorts)
    if ([string]::IsNullOrWhiteSpace($SenderSourcePorts)) {
        return 8879
    }
    foreach ($entry in $SenderSourcePorts -split "[,;]") {
        $parts = $entry.Trim() -split ":", 2
        if ($parts.Length -eq 2 -and $parts[0].Trim() -eq "left") {
            $port = 0
            if ([int]::TryParse($parts[1].Trim(), [ref]$port) -and $port -gt 0 -and $port -le 65535) {
                return $port
            }
        }
    }
    return 8879
}

function Invoke-AdbCapture {
    param(
        [Parameter(Mandatory=$true)]
        [string]$AdbPath,
        [Parameter(Mandatory=$true)]
        [string]$DeviceSerial,
        [Parameter(Mandatory=$true)]
        [string[]]$Arguments
    )

    $stdout = & $AdbPath -s $DeviceSerial @Arguments 2>&1 | Out-String
    return [pscustomobject]@{
        exit_code = $LASTEXITCODE
        output = $stdout.Trim()
    }
}

function Test-PermissionDeclaredInDumpsys {
    param(
        [string]$Dumpsys,
        [string]$Permission
    )
    return -not [string]::IsNullOrWhiteSpace($Dumpsys) -and
        $Dumpsys.Contains($Permission)
}

function Get-AndroidPermissionReadback {
    param(
        [Parameter(Mandatory=$true)]
        [string]$AdbPath,
        [Parameter(Mandatory=$true)]
        [string]$DeviceSerial,
        [Parameter(Mandatory=$true)]
        [string]$PackageName,
        [Parameter(Mandatory=$true)]
        [string]$Permission
    )

    $result = Invoke-AdbCapture -AdbPath $AdbPath -DeviceSerial $DeviceSerial -Arguments @(
        "shell",
        "dumpsys",
        "package",
        $PackageName
    )
    $escapedPermission = [regex]::Escape($Permission)
    $grantedMatch = [regex]::Match($result.output, "$escapedPermission\s*:\s*granted=(true|false)")
    $grantStateFound = $grantedMatch.Success
    $granted = $false
    if ($grantStateFound) {
        $granted = $grantedMatch.Groups[1].Value -eq "true"
    }
    return [ordered]@{
        permission = $Permission
        method = "dumpsys package"
        exit_code = $result.exit_code
        declared = (Test-PermissionDeclaredInDumpsys -Dumpsys $result.output -Permission $Permission)
        grant_state_found = $grantStateFound
        granted = $granted
    }
}

function Invoke-AndroidPermissionPreflight {
    param(
        [Parameter(Mandatory=$true)]
        [string]$AdbPath,
        [Parameter(Mandatory=$true)]
        [string]$DeviceSerial,
        [Parameter(Mandatory=$true)]
        [string]$PackageName,
        [Parameter(Mandatory=$true)]
        [string[]]$RequiredPermissions,
        [string[]]$RuntimeGrantPermissions = @(),
        [Parameter(Mandatory=$true)]
        [string]$OutPath,
        [string]$Note = ""
    )

    $dumpsys = Invoke-AdbCapture -AdbPath $AdbPath -DeviceSerial $DeviceSerial -Arguments @(
        "shell",
        "dumpsys",
        "package",
        $PackageName
    )
    $summary = [ordered]@{
        schema = "rusty.quest.android_permission_preflight.v1"
        package = $PackageName
        serial = $DeviceSerial
        required_permissions = @()
        runtime_grants = @()
        note = $Note
    }
    foreach ($permission in $RequiredPermissions) {
        $readback = Get-AndroidPermissionReadback `
            -AdbPath $AdbPath `
            -DeviceSerial $DeviceSerial `
            -PackageName $PackageName `
            -Permission $permission
        $declared = Test-PermissionDeclaredInDumpsys -Dumpsys $dumpsys.output -Permission $permission
        $summary.required_permissions += [ordered]@{
            permission = $permission
            declared = $declared
            check_permission = $readback
        }
        if (-not $declared) {
            Write-JsonFile -Value $summary -Path $OutPath
            throw "$PackageName does not declare required permission $permission"
        }
    }
    foreach ($permission in $RuntimeGrantPermissions) {
        $grant = Invoke-AdbCapture -AdbPath $AdbPath -DeviceSerial $DeviceSerial -Arguments @(
            "shell",
            "pm",
            "grant",
            $PackageName,
            $permission
        )
        $readback = Get-AndroidPermissionReadback `
            -AdbPath $AdbPath `
            -DeviceSerial $DeviceSerial `
            -PackageName $PackageName `
            -Permission $permission
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

function Invoke-UiautomatorPermissionGrantIfNeeded {
    param(
        [Parameter(Mandatory=$true)]
        [string]$AdbPath,
        [Parameter(Mandatory=$true)]
        [string]$DeviceSerial,
        [Parameter(Mandatory=$true)]
        [string]$PackageName,
        [Parameter(Mandatory=$true)]
        [string]$Permission,
        [Parameter(Mandatory=$true)]
        [string]$OutPath
    )

    $before = Get-AndroidPermissionReadback `
        -AdbPath $AdbPath `
        -DeviceSerial $DeviceSerial `
        -PackageName $PackageName `
        -Permission $Permission
    $summary = [ordered]@{
        schema = "rusty.quest.android_runtime_permission_uiautomator_fallback.v1"
        package = $PackageName
        serial = $DeviceSerial
        permission = $Permission
        check_permission_before = $before
        uiautomator_dump_exit_code = $null
        uiautomator_dump_output = ""
        uiautomator_tap_attempted = $false
        uiautomator_tap_bounds = ""
        uiautomator_tap_exit_code = $null
        check_permission_after = $null
    }
    if ($before.granted -eq $true) {
        $summary.check_permission_after = $before
        Write-JsonFile -Value $summary -Path $OutPath
        return [pscustomobject]$summary
    }

    Start-Sleep -Milliseconds 800
    $remoteDump = "/sdcard/qcl-runtime-permission-window.xml"
    $dump = Invoke-AdbCapture -AdbPath $AdbPath -DeviceSerial $DeviceSerial -Arguments @(
        "shell",
        "uiautomator",
        "dump",
        $remoteDump
    )
    $summary.uiautomator_dump_exit_code = $dump.exit_code
    $summary.uiautomator_dump_output = $dump.output
    $xml = (Invoke-AdbCapture -AdbPath $AdbPath -DeviceSerial $DeviceSerial -Arguments @(
        "shell",
        "cat",
        $remoteDump
    )).output
    $match = [regex]::Match(
        $xml,
        'text="(?:Allow|ALLOW|While using the app|WHILE USING THE APP)[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    )
    if ($match.Success) {
        $x1 = [int]$match.Groups[1].Value
        $y1 = [int]$match.Groups[2].Value
        $x2 = [int]$match.Groups[3].Value
        $y2 = [int]$match.Groups[4].Value
        $tapX = [int](($x1 + $x2) / 2)
        $tapY = [int](($y1 + $y2) / 2)
        $tap = Invoke-AdbCapture -AdbPath $AdbPath -DeviceSerial $DeviceSerial -Arguments @(
            "shell",
            "input",
            "tap",
            $tapX.ToString(),
            $tapY.ToString()
        )
        $summary.uiautomator_tap_attempted = $true
        $summary.uiautomator_tap_bounds = $match.Value
        $summary.uiautomator_tap_exit_code = $tap.exit_code
        Start-Sleep -Milliseconds 800
    }
    $summary.check_permission_after = Get-AndroidPermissionReadback `
        -AdbPath $AdbPath `
        -DeviceSerial $DeviceSerial `
        -PackageName $PackageName `
        -Permission $Permission
    Write-JsonFile -Value $summary -Path $OutPath
    return [pscustomobject]$summary
}

function Invoke-BrokerCameraPermissionUiFallbackIfNeeded {
    param(
        [Parameter(Mandatory=$true)]
        [string]$AdbPath,
        [Parameter(Mandatory=$true)]
        [string]$DeviceSerial,
        [Parameter(Mandatory=$true)]
        [string]$OutPath
    )

    $packageName = "io.github.mesmerprism.rustymanifold.broker"
    $permission = "android.permission.CAMERA"
    $before = Get-AndroidPermissionReadback `
        -AdbPath $AdbPath `
        -DeviceSerial $DeviceSerial `
        -PackageName $packageName `
        -Permission $permission
    if ($before.granted -ne $true) {
        Write-Host "Broker CAMERA permission still not granted after pm grant; launching broker Activity for permission dialog fallback."
        Invoke-AdbCapture -AdbPath $AdbPath -DeviceSerial $DeviceSerial -Arguments @(
            "shell",
            "am",
            "start",
            "-n",
            "$packageName/.BrokerStartActivity"
        ) | Out-Null
        Invoke-UiautomatorPermissionGrantIfNeeded `
            -AdbPath $AdbPath `
            -DeviceSerial $DeviceSerial `
            -PackageName $packageName `
            -Permission $permission `
            -OutPath $OutPath | Out-Null
        return
    }

    Write-JsonFile -Value ([ordered]@{
        schema = "rusty.quest.android_runtime_permission_uiautomator_fallback.v1"
        package = $packageName
        serial = $DeviceSerial
        permission = $permission
        check_permission_before = $before
        uiautomator_tap_attempted = $false
        check_permission_after = $before
    }) -Path $OutPath
}

function Resolve-WindowsHelperExecutable {
    param(
        [Parameter(Mandatory=$true)]
        [string]$ProjectPath,
        [string]$ExplicitExe = ""
    )

    if (-not [string]::IsNullOrWhiteSpace($ExplicitExe)) {
        if (-not (Test-Path $ExplicitExe)) {
            throw "Windows QCL-041 helper executable not found: $ExplicitExe"
        }
        return (Resolve-Path $ExplicitExe).Path
    }

    $projectDir = Split-Path -Parent $ProjectPath
    [xml]$projectXml = Get-Content -Raw -Path $ProjectPath
    $assemblyName = ""
    foreach ($propertyGroup in @($projectXml.Project.PropertyGroup)) {
        if (-not [string]::IsNullOrWhiteSpace([string]$propertyGroup.AssemblyName)) {
            $assemblyName = [string]$propertyGroup.AssemblyName
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($assemblyName)) {
        $assemblyName = [System.IO.Path]::GetFileNameWithoutExtension($ProjectPath)
    }

    $targetFramework = ""
    foreach ($propertyGroup in @($projectXml.Project.PropertyGroup)) {
        if (-not [string]::IsNullOrWhiteSpace([string]$propertyGroup.TargetFramework)) {
            $targetFramework = [string]$propertyGroup.TargetFramework
            break
        }
    }

    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($targetFramework)) {
        $candidates += (Join-Path $projectDir "bin\Debug\$targetFramework\$assemblyName.exe")
    }
    $binDir = Join-Path $projectDir "bin\Debug"
    if (Test-Path $binDir) {
        $candidates += @(Get-ChildItem -Path $binDir -Recurse -Filter "$assemblyName.exe" -File | Select-Object -ExpandProperty FullName)
    }

    foreach ($candidate in $candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw "Built Windows QCL-041 helper executable '$assemblyName.exe' not found under $binDir"
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
        [Parameter(Mandatory=$true)]
        [string]$ReserveOutput,
        [Parameter(Mandatory=$true)]
        [string]$Resource
    )

    if ($ReserveOutput -match 'Resource busy') {
        throw "Agent Board resource is busy for ${Resource}; refusing to continue without an owned lease. Output: $ReserveOutput"
    }
    if ($ReserveOutput -notmatch "Reserved\s+$([regex]::Escape($Resource))\s+until") {
        throw "Agent Board reserve did not confirm ownership of ${Resource}. Output: $ReserveOutput"
    }
}

function Update-ArtifactFromWindowsHelper {
    param(
        [object]$Artifact,
        [object]$Helper
    )
    $measurements = $Helper.measurements
    $apiReady = $false
    if ($null -ne $measurements) {
        $apiReady = [bool]$measurements.advertisement_started -and [bool]$measurements.connection_listener_ready
    }
    $status = if ($apiReady) { "pass" } else { "blocked" }
    $evidence = "Windows helper status=$($Helper.status); publisher=$($measurements.publisher_status); listener_ready=$($measurements.connection_listener_ready); peer_requested=$($measurements.peer_connection_requested); group_formed=$($measurements.group_formed)."
    $Artifact.lifecycle.windows_wifi_direct_api.status = $status
    $Artifact.lifecycle.windows_wifi_direct_api.evidence = $evidence
    $Artifact.host.toolchain_profile = "rusty_quest_qcl041_wifi_direct_windows_helper"
    if ($null -eq $Artifact.diagnostics) {
        $Artifact | Add-Member -MemberType NoteProperty -Name diagnostics -Value ([pscustomobject]@{})
    }
    if ($null -eq $Artifact.diagnostics.windows_peer) {
        $Artifact.diagnostics | Add-Member -MemberType NoteProperty -Name windows_peer -Value ([pscustomobject]@{})
    }
    $Artifact.diagnostics.windows_peer | Add-Member -Force -MemberType NoteProperty -Name helper_schema -Value $Helper.schema
    $Artifact.diagnostics.windows_peer | Add-Member -Force -MemberType NoteProperty -Name helper_status -Value $Helper.status
    $Artifact.diagnostics.windows_peer | Add-Member -Force -MemberType NoteProperty -Name helper_report_consumed -Value $true
}

function Test-Qcl041SocketExchangeReady {
    param(
        [object]$Artifact,
        [string]$ExpectedRunId
    )
    if ($null -eq $Artifact -or $Artifact.run_id -ne $ExpectedRunId) {
        return $false
    }
    $socket = $Artifact.lifecycle.socket_exchange
    if ($null -eq $socket) {
        return $false
    }
    return $socket.status -eq "pass" -and [int]$socket.messages_sent -gt 0 -and [int]$socket.messages_received -gt 0
}

function Test-Qcl041ActiveGroupHoldReady {
    param(
        [object]$Artifact,
        [string]$ExpectedRunId
    )
    if ($null -eq $Artifact -or $Artifact.run_id -ne $ExpectedRunId) {
        return $false
    }
    if ($Artifact.lifecycle.group_formation.status -ne "pass") {
        return $false
    }
    if ($Artifact.diagnostics.dependent_live_steps.hold_started_before_cleanup -ne $true) {
        return $false
    }
    if ($Artifact.diagnostics.lifecycle.group_owner_address_present -ne $true) {
        return $false
    }
    if ($Artifact.diagnostics.lifecycle.wifi_direct_local_address_same_subnet -ne $true) {
        return $false
    }
    return $true
}

function Invoke-Qcl082ProductMediaLiveSession {
    param(
        [Parameter(Mandatory=$true)]
        [string]$RunId,
        [Parameter(Mandatory=$true)]
        [string]$OutDir,
        [Parameter(Mandatory=$true)]
        [string]$HostessCtl,
        [Parameter(Mandatory=$true)]
        [string]$WpfReceiverExe,
        [Parameter(Mandatory=$true)]
        [string]$Python,
        [Parameter(Mandatory=$true)]
        [string]$AdbPath,
        [Parameter(Mandatory=$true)]
        [string]$DeviceSerial,
        [Parameter(Mandatory=$true)]
        [string]$LeaseId,
        [Parameter(Mandatory=$true)]
        [string]$LeaseResource,
        [Parameter(Mandatory=$true)]
        [string]$TopologyReport,
        [Parameter(Mandatory=$true)]
        [string]$FirewallReport,
        [Parameter(Mandatory=$true)]
        [string]$ReceiverHost,
        [string]$LocalBindHost = "",
        [Parameter(Mandatory=$true)]
        [string]$BindHost,
        [Parameter(Mandatory=$true)]
        [int]$Port,
        [Parameter(Mandatory=$true)]
        [int]$MaxPackets,
        [Parameter(Mandatory=$true)]
        [double]$TimeoutSeconds,
        [Parameter(Mandatory=$true)]
        [string]$SessionId,
        [Parameter(Mandatory=$true)]
        [string]$SenderSourceKind,
        [Parameter(Mandatory=$true)]
        [string]$SenderSourcePorts,
        [Parameter(Mandatory=$true)]
        [string]$SenderMediaProfiles,
        [string]$SenderCameraIds = "",
        [string]$SenderCameraId = "",
        [string]$SenderCameraFacing = "",
        [string]$SenderQualityProfile = "",
        [string]$CameraPermissionPolicy = "",
        [switch]$PrestartedBroker,
        [System.Diagnostics.Process]$PrestartedReceiverProcess = $null
    )

    if (-not (Test-Path $HostessCtl)) {
        throw "Hostessctl not found for QCL-082 live session: $HostessCtl"
    }
    if (-not (Test-Path $WpfReceiverExe)) {
        throw "Hostess WPF receiver executable not found for QCL-082 live session: $WpfReceiverExe"
    }
    if (-not (Test-Path $TopologyReport)) {
        throw "QCL-082 topology report not found: $TopologyReport"
    }
    if (-not (Test-Path $FirewallReport)) {
        throw "QCL-082 firewall report not found: $FirewallReport"
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
    $brokerPermissionOut = Join-Path $OutDir "qcl082-broker-permission-preflight.json"
    $brokerCameraPermissionOut = Join-Path $OutDir "qcl082-broker-camera-permission-uiautomator.json"

    if ($PrestartedReceiverProcess -and $PrestartedReceiverProcess.HasExited -and (Test-Path $resultOut)) {
        Write-Host "Prestarted QCL-082 WPF receiver already exited; using existing receiver artifact $resultOut"
        return $resultOut
    }

    $transportRoutes = "left|left|direct_tcp_connect|$ReceiverHost|$Port"
    $effectiveCameraPermissionPolicy = Get-Qcl082EffectiveCameraPermissionPolicy `
        -SourceKind $SenderSourceKind `
        -ExplicitPolicy $CameraPermissionPolicy
    $params = [ordered]@{
        session_id = $SessionId
        sender_source_kind = $SenderSourceKind
        sender_source_host = "127.0.0.1"
        sender_source_ports = $SenderSourcePorts
        sender_media_profiles = $SenderMediaProfiles
        camera_permission_policy = $effectiveCameraPermissionPolicy
    }
    if (-not [string]::IsNullOrWhiteSpace($SenderCameraIds)) {
        $params.sender_camera_ids = $SenderCameraIds
    }
    if (-not [string]::IsNullOrWhiteSpace($SenderCameraId)) {
        $params.sender_camera_id = $SenderCameraId
    }
    if (-not [string]::IsNullOrWhiteSpace($SenderCameraFacing)) {
        $params.sender_camera_facing = $SenderCameraFacing
    }
    if (-not [string]::IsNullOrWhiteSpace($SenderQualityProfile)) {
        $params.sender_quality_profile = $SenderQualityProfile
    }
    if (-not [string]::IsNullOrWhiteSpace($LocalBindHost)) {
        $params.transport_bind_local_address = $LocalBindHost
        $params.transport_owner = "qcl041_wifi_direct_relay"
        $params.receiver_host = $ReceiverHost
        $params.receiver_port = $Port
    } else {
        $params.transport_routes = $transportRoutes
    }
    Write-JsonFile -Value $params -Path $paramsPath

    $requestId = "request.hostess.qcl082.media_stream.start_source.$RunId"
    $evidenceId = "evidence.hostess.qcl082.media_stream.start_source.$RunId"
    $remoteEndpoint = "${ReceiverHost}:$Port"
    $timeoutText = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0}", $TimeoutSeconds)
    Invoke-AndroidPermissionPreflight `
        -AdbPath $AdbPath `
        -DeviceSerial $DeviceSerial `
        -PackageName "io.github.mesmerprism.rustymanifold.broker" `
        -RequiredPermissions @(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.CAMERA",
            "horizonos.permission.HEADSET_CAMERA",
            "horizonos.permission.SPATIAL_CAMERA"
        ) `
        -RuntimeGrantPermissions @(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.CAMERA",
            "horizonos.permission.HEADSET_CAMERA",
            "horizonos.permission.SPATIAL_CAMERA"
        ) `
        -OutPath $brokerPermissionOut `
        -Note "QCL-082 broker preflight records network permissions and camera permissions required by the selected RMANVID1 sender source." | Out-Null
    if (Test-Qcl082Camera2SourceKind -SourceKind $SenderSourceKind) {
        Invoke-BrokerCameraPermissionUiFallbackIfNeeded `
            -AdbPath $AdbPath `
            -DeviceSerial $DeviceSerial `
            -OutPath $brokerCameraPermissionOut
    } else {
        Write-JsonFile -Value ([ordered]@{
            schema = "rusty.quest.android_runtime_permission_uiautomator_fallback.v1"
            package = "io.github.mesmerprism.rustymanifold.broker"
            serial = $DeviceSerial
            permission = "android.permission.CAMERA"
            source_kind = $SenderSourceKind
            uiautomator_tap_attempted = $false
            skipped = "selected_source_does_not_require_camera2"
        }) -Path $brokerCameraPermissionOut
    }
    if ($PrestartedBroker) {
        Write-Host "Using prestarted QCL-082 Manifold broker process."
    } else {
        Invoke-Checked "QCL-082 broker force-stop before live session" $AdbPath @(
            "-s",
            $DeviceSerial,
            "shell",
            "am",
            "force-stop",
            "io.github.mesmerprism.rustymanifold.broker"
        )
    }
    $receiverReadyOut = Join-Path $OutDir "qcl082-wpf-rmanvid1-receiver.ready.json"
    $receiverStdout = Join-Path $OutDir "qcl082-wpf-rmanvid1-receiver.stdout.txt"
    $receiverStderr = Join-Path $OutDir "qcl082-wpf-rmanvid1-receiver.stderr.txt"
    Invoke-Checked "QCL-082 start_source request" $Python @(
        $HostessCtl,
        "emit-bridge-command-request",
        "--bridge-command", "command.media_stream.start_source",
        "--request-id", $requestId,
        "--evidence-id", $evidenceId,
        "--route-id", "bridge_route.command.websocket.applied",
        "--required-stage", "sent",
        "--required-stage", "transport_ok",
        "--required-stage", "authority_accepted",
        "--params-json-file", $paramsPath,
        "--out", $requestOut
    )

    $receiverArgs = @(
        "--qcl082-rmanvid1-receiver",
        "--out", $resultOut,
        "--capture-out", $captureOut,
        "--sidecar-out", $sidecarOut,
        "--bind-host", $BindHost,
        "--port", $Port.ToString(),
        "--timeout-seconds", $timeoutText,
        "--max-packets", $MaxPackets.ToString(),
        "--runtime-status", $executionOut,
        "--topology-report", $TopologyReport,
        "--firewall-report", $FirewallReport,
        "--source-remote-endpoint", $remoteEndpoint,
        "--command-id", "command.media_stream.start_source",
        "--session-id", $SessionId,
        "--quest-lease-id", $LeaseId,
        "--quest-lease-resource", $LeaseResource,
        "--quest-lease-reserved-before-live-steps",
        "--ready-out", $receiverReadyOut
    )

    if ($PrestartedReceiverProcess) {
        $receiverProcess = $PrestartedReceiverProcess
        if ($receiverProcess.HasExited) {
            $resultDeadline = (Get-Date).AddSeconds(5)
            while ((Get-Date) -lt $resultDeadline -and -not (Test-Path $resultOut)) {
                Start-Sleep -Milliseconds 100
            }
            if (Test-Path $resultOut) {
                Write-Host "Prestarted QCL-082 WPF receiver exited before the broker command branch; using existing receiver artifact $resultOut"
                return $resultOut
            }
            throw "Prestarted QCL-082 WPF receiver exited before the broker command with code $($receiverProcess.ExitCode)."
        }
        Write-Host "Using prestarted QCL-082 WPF product receiver on ${BindHost}:$Port"
    } else {
        if (Test-Path $receiverReadyOut) {
            Remove-Item -LiteralPath $receiverReadyOut -Force
        }
        Write-Host "Starting QCL-082 WPF product receiver on ${BindHost}:$Port"
        $receiverProcess = Start-Process `
            -FilePath $WpfReceiverExe `
            -ArgumentList $receiverArgs `
            -PassThru `
            -WindowStyle Hidden `
            -RedirectStandardOutput $receiverStdout `
            -RedirectStandardError $receiverStderr
    }
    try {
        if (-not $PrestartedReceiverProcess) {
            $readyDeadline = (Get-Date).AddSeconds(12)
            $receiverReady = $false
            while ((Get-Date) -lt $readyDeadline) {
                if ($receiverProcess.HasExited) {
                    throw "QCL-082 WPF receiver exited before it became ready with code $($receiverProcess.ExitCode)."
                }
                if (Test-Path $receiverReadyOut) {
                    $ready = Read-JsonFile $receiverReadyOut
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
        }

        Write-Host "Starting QCL-082 product media live command against $remoteEndpoint"
        $liveAndroidArgs = @(
            $HostessCtl,
            "run-bridge-command-live-android",
            "--input", $requestOut,
            "--out", $bridgeEvidenceOut,
            "--execution-out", $executionOut,
            "--validation-out", $validationOut,
            "--logcat-out", $logcatOut,
            "--adb", $AdbPath,
            "--serial", $DeviceSerial,
            "--no-runtime-receipt-subscribe",
            "--no-launch-makepad",
            "--no-wait-makepad-process"
        )
        if ($PrestartedBroker) {
            $liveAndroidArgs += "--no-launch-broker"
        }
        Invoke-Checked "QCL-082 start_source live Android command" $Python $liveAndroidArgs

        $waitMs = [int](($TimeoutSeconds + 10.0) * 1000.0)
        if (-not $receiverProcess.WaitForExit($waitMs)) {
            Stop-Process -Id $receiverProcess.Id -Force
            throw "QCL-082 WPF receiver did not exit before timeout."
        }
        if ($receiverProcess.ExitCode -ne 0) {
            Write-Host "QCL-082 WPF receiver exited with code $($receiverProcess.ExitCode); preserving receiver artifact for normalization."
            if (-not (Test-Path $resultOut)) {
                throw "QCL-082 WPF receiver failed with exit code $($receiverProcess.ExitCode) and did not write $resultOut."
            }
        }
    } finally {
        if ($receiverProcess -and -not $receiverProcess.HasExited) {
            Stop-Process -Id $receiverProcess.Id -Force
        }
    }
    return $resultOut
}

function Start-Qcl082ProductMediaSourceBeforeHarness {
    param(
        [Parameter(Mandatory=$true)]
        [string]$RunId,
        [Parameter(Mandatory=$true)]
        [string]$OutDir,
        [Parameter(Mandatory=$true)]
        [string]$HostessCtl,
        [Parameter(Mandatory=$true)]
        [string]$Python,
        [Parameter(Mandatory=$true)]
        [string]$AdbPath,
        [Parameter(Mandatory=$true)]
        [string]$DeviceSerial,
        [Parameter(Mandatory=$true)]
        [string]$SessionId,
        [Parameter(Mandatory=$true)]
        [string]$SenderSourceKind,
        [Parameter(Mandatory=$true)]
        [string]$SenderSourcePorts,
        [Parameter(Mandatory=$true)]
        [string]$SenderMediaProfiles,
        [string]$SenderCameraIds = "",
        [string]$SenderCameraId = "",
        [string]$SenderCameraFacing = "",
        [string]$SenderQualityProfile = "",
        [string]$CameraPermissionPolicy = "",
        [switch]$PrestartedBroker
    )

    $paramsPath = Join-Path $OutDir "qcl082-start-source.params.json"
    $requestOut = Join-Path $OutDir "qcl082-media-stream-start-source.request.json"
    $bridgeEvidenceOut = Join-Path $OutDir "qcl082-media-stream-start-source.bridge-evidence.json"
    $executionOut = Join-Path $OutDir "qcl082-media-stream-start-source.live-android-execution.json"
    $validationOut = Join-Path $OutDir "qcl082-media-stream-start-source.validation-report.json"
    $logcatOut = Join-Path $OutDir "qcl082-media-stream-start-source.logcat.txt"
    $brokerCameraPermissionOut = Join-Path $OutDir "qcl082-broker-camera-permission-uiautomator.json"
    $effectiveCameraPermissionPolicy = Get-Qcl082EffectiveCameraPermissionPolicy `
        -SourceKind $SenderSourceKind `
        -ExplicitPolicy $CameraPermissionPolicy
    $params = [ordered]@{
        session_id = $SessionId
        sender_source_kind = $SenderSourceKind
        sender_source_host = "127.0.0.1"
        sender_source_ports = $SenderSourcePorts
        sender_media_profiles = $SenderMediaProfiles
        camera_permission_policy = $effectiveCameraPermissionPolicy
        transport_owner = "qcl041_wifi_direct_relay_prestarted_source"
    }
    if (-not [string]::IsNullOrWhiteSpace($SenderCameraIds)) {
        $params.sender_camera_ids = $SenderCameraIds
    }
    if (-not [string]::IsNullOrWhiteSpace($SenderCameraId)) {
        $params.sender_camera_id = $SenderCameraId
    }
    if (-not [string]::IsNullOrWhiteSpace($SenderCameraFacing)) {
        $params.sender_camera_facing = $SenderCameraFacing
    }
    if (-not [string]::IsNullOrWhiteSpace($SenderQualityProfile)) {
        $params.sender_quality_profile = $SenderQualityProfile
    }
    Write-JsonFile -Value $params -Path $paramsPath

    if (Test-Qcl082Camera2SourceKind -SourceKind $SenderSourceKind) {
        Invoke-BrokerCameraPermissionUiFallbackIfNeeded `
            -AdbPath $AdbPath `
            -DeviceSerial $DeviceSerial `
            -OutPath $brokerCameraPermissionOut
    } elseif (-not (Test-Path $brokerCameraPermissionOut)) {
        Write-JsonFile -Value ([ordered]@{
            schema = "rusty.quest.android_runtime_permission_uiautomator_fallback.v1"
            package = "io.github.mesmerprism.rustymanifold.broker"
            serial = $DeviceSerial
            permission = "android.permission.CAMERA"
            source_kind = $SenderSourceKind
            uiautomator_tap_attempted = $false
            skipped = "selected_source_does_not_require_camera2"
        }) -Path $brokerCameraPermissionOut
    }

    $requestId = "request.hostess.qcl082.media_stream.start_source.$RunId"
    $evidenceId = "evidence.hostess.qcl082.media_stream.start_source.$RunId"
    Invoke-Checked "QCL-082 pre-harness start_source request" $Python @(
        $HostessCtl,
        "emit-bridge-command-request",
        "--bridge-command", "command.media_stream.start_source",
        "--request-id", $requestId,
        "--evidence-id", $evidenceId,
        "--route-id", "bridge_route.command.websocket.applied",
        "--required-stage", "sent",
        "--required-stage", "transport_ok",
        "--required-stage", "authority_accepted",
        "--params-json-file", $paramsPath,
        "--out", $requestOut
    )

    $liveAndroidArgs = @(
        $HostessCtl,
        "run-bridge-command-live-android",
        "--input", $requestOut,
        "--out", $bridgeEvidenceOut,
        "--execution-out", $executionOut,
        "--validation-out", $validationOut,
        "--logcat-out", $logcatOut,
        "--adb", $AdbPath,
        "--serial", $DeviceSerial,
        "--no-runtime-receipt-subscribe",
        "--no-launch-makepad",
        "--no-wait-makepad-process"
    )
    if ($PrestartedBroker) {
        $liveAndroidArgs += "--no-launch-broker"
    }
    Write-Host "Prestarting QCL-082 broker media source before QCL-041 relay launch."
    Invoke-Checked "QCL-082 pre-harness start_source live Android command" $Python $liveAndroidArgs
    return $executionOut
}

function Grant-WifiDirectPermission {
    param(
        [string]$AdbPath,
        [string]$DeviceSerial,
        [string]$OutPath = ""
    )
    $sdkText = (& $AdbPath -s $DeviceSerial shell getprop ro.build.version.sdk | Out-String).Trim()
    $sdk = 0
    [void][int]::TryParse($sdkText, [ref]$sdk)
    $permission = if ($sdk -ge 33) { "android.permission.NEARBY_WIFI_DEVICES" } else { "android.permission.ACCESS_FINE_LOCATION" }
    $requiredPermissions = @(
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.INTERNET",
        $permission
    )
    $runtimeGrantPermissions = @($permission)
    if ($sdk -ge 33) {
        $requiredPermissions += "android.permission.POST_NOTIFICATIONS"
        $runtimeGrantPermissions += "android.permission.POST_NOTIFICATIONS"
    }
    $summary = Invoke-AndroidPermissionPreflight `
        -AdbPath $AdbPath `
        -DeviceSerial $DeviceSerial `
        -PackageName "io.github.mesmerprism.rustyquest.qcl041" `
        -RequiredPermissions $requiredPermissions `
        -RuntimeGrantPermissions $runtimeGrantPermissions `
        -OutPath $OutPath `
        -Note "QCL-041 Wi-Fi Direct pregrant follows the Meta Quest workflow: serial-scoped adb, manifest declaration check, pm grant when runtime-grantable, notification permission for foreground-service launch, and readback before launch."
    foreach ($runtimePermission in $runtimeGrantPermissions) {
        $grant = @($summary.runtime_grants | Where-Object { $_.permission -eq $runtimePermission } | Select-Object -First 1)
        if ($grant.Count -eq 0 -or $grant[0].pm_grant_exit_code -ne 0) {
            Write-Host "Runtime permission pregrant failed for $runtimePermission; launch-time readback and UIAutomator fallback will record the remaining state."
        } else {
            Write-Host "Runtime permission pregranted: $runtimePermission"
        }
    }
    $locationMode = (& $AdbPath -s $DeviceSerial shell settings get secure location_mode | Out-String).Trim()
    Write-Host "Quest secure location_mode=$locationMode; Android Wi-Fi Direct peer discovery requires Location Mode enabled."
    return $summary
}

function Wait-AndroidPackagePid {
    param(
        [Parameter(Mandatory=$true)]
        [string]$AdbPath,
        [Parameter(Mandatory=$true)]
        [string]$DeviceSerial,
        [Parameter(Mandatory=$true)]
        [string]$PackageName,
        [double]$TimeoutSeconds = 8.0
    )
    $deadline = (Get-Date).AddSeconds([Math]::Max(0.5, $TimeoutSeconds))
    while ((Get-Date) -lt $deadline) {
        $oldErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $packagePid = (& $AdbPath -s $DeviceSerial shell pidof $PackageName 2>$null | Out-String).Trim()
        } finally {
            $ErrorActionPreference = $oldErrorActionPreference
        }
        if (-not [string]::IsNullOrWhiteSpace($packagePid)) {
            return $packagePid
        }
        Start-Sleep -Milliseconds 250
    }
    return ""
}

function Start-Qcl082BrokerBeforeHarness {
    param(
        [Parameter(Mandatory=$true)]
        [string]$AdbPath,
        [Parameter(Mandatory=$true)]
        [string]$DeviceSerial,
        [Parameter(Mandatory=$true)]
        [string]$PermissionOutPath,
        [Parameter(Mandatory=$true)]
        [string]$PrestartOutPath
    )
    $packageName = "io.github.mesmerprism.rustymanifold.broker"
    $activityName = "$packageName/.BrokerStartActivity"
    $serviceName = "$packageName/.BrokerStartService"
    $permission = $null
    $permissionError = $null
    try {
        $permission = Invoke-AndroidPermissionPreflight `
            -AdbPath $AdbPath `
            -DeviceSerial $DeviceSerial `
            -PackageName $packageName `
            -RequiredPermissions @(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.NEARBY_WIFI_DEVICES",
                "android.permission.CAMERA",
                "horizonos.permission.HEADSET_CAMERA",
                "horizonos.permission.SPATIAL_CAMERA"
            ) `
            -RuntimeGrantPermissions @(
                "android.permission.NEARBY_WIFI_DEVICES",
                "android.permission.CAMERA",
                "horizonos.permission.HEADSET_CAMERA",
                "horizonos.permission.SPATIAL_CAMERA"
            ) `
            -OutPath $PermissionOutPath `
            -Note "QCL-082 broker preflight runs before the QCL-041 harness takes foreground so the broker can stay alive during the direct-Wi-Fi group."
    } catch {
        $permissionError = $_.Exception.Message
    }

    $forceStop = Invoke-AdbCapture -AdbPath $AdbPath -DeviceSerial $DeviceSerial -Arguments @(
        "shell",
        "am",
        "force-stop",
        $packageName
    )
    $activityStart = $null
    $serviceStart = Invoke-AdbCapture -AdbPath $AdbPath -DeviceSerial $DeviceSerial -Arguments @(
        "shell",
        "am",
        "start-foreground-service",
        "-n",
        $serviceName
    )
    $startMethod = "foreground_service"
    $effectiveStart = $serviceStart
    if ($serviceStart.exit_code -ne 0) {
        $startMethod = "foreground_service_failed"
    }
    $brokerPid = ""
    if ($null -eq $permissionError -and $forceStop.exit_code -eq 0 -and $effectiveStart.exit_code -eq 0) {
        $brokerPid = Wait-AndroidPackagePid `
            -AdbPath $AdbPath `
            -DeviceSerial $DeviceSerial `
            -PackageName $packageName `
            -TimeoutSeconds 8.0
    }
    $launchEvidencePath = "/sdcard/Android/data/$packageName/files/manifold-broker/latest.json"
    $launchEvidence = Invoke-AdbCapture -AdbPath $AdbPath -DeviceSerial $DeviceSerial -Arguments @(
        "shell",
        "cat",
        $launchEvidencePath
    )
    $issues = @()
    if ($null -ne $permissionError) {
        $issues += "broker_permission_preflight_failed"
    }
    if ($forceStop.exit_code -ne 0) {
        $issues += "broker_force_stop_failed"
    }
    if ($null -ne $activityStart -and $activityStart.exit_code -ne 0) {
        $issues += "broker_activity_start_failed"
    }
    if ($serviceStart.exit_code -ne 0) {
        $issues += "broker_foreground_service_start_failed"
    }
    if ($effectiveStart.exit_code -ne 0) {
        $issues += "broker_am_start_failed"
    }
    if ([string]::IsNullOrWhiteSpace($brokerPid)) {
        $issues += "broker_pid_missing_after_prestart"
    }
    $report = [ordered]@{
        schema = "rusty.quest.qcl082.broker_prestart.v1"
        status = if ($issues.Count -eq 0) { "pass" } else { "blocked" }
        package = $packageName
        activity = $activityName
        service = $serviceName
        serial = $DeviceSerial
        permission_preflight_artifact = $PermissionOutPath
        permission_preflight_schema = if ($null -eq $permission) { $null } else { $permission.schema }
        permission_preflight_error = $permissionError
        force_stop_exit_code = $forceStop.exit_code
        force_stop_output = $forceStop.output
        start_method = $startMethod
        service_start_exit_code = $serviceStart.exit_code
        service_start_output = $serviceStart.output
        activity_start_exit_code = if ($null -eq $activityStart) { $null } else { $activityStart.exit_code }
        activity_start_output = if ($null -eq $activityStart) { $null } else { $activityStart.output }
        activity_start_skipped_reason = "avoid_horizon_reprojected_os_dialog_before_qcl041_launch"
        am_start_exit_code = $effectiveStart.exit_code
        am_start_output = $effectiveStart.output
        pid = $brokerPid
        launch_evidence_path = $launchEvidencePath
        launch_evidence_exit_code = $launchEvidence.exit_code
        launch_evidence_output = $launchEvidence.output
        started_before_qcl041_harness = $true
        issues = $issues
        rationale = "Start the broker service before the QCL-041 VR harness owns foreground; later live command reuses it with --no-launch-broker."
    }
    Write-JsonFile -Value $report -Path $PrestartOutPath
    if ($issues.Count -gt 0) {
        throw "QCL-082 broker did not stay running after prestart. See $PrestartOutPath"
    }
    Write-Host "Prestarted QCL-082 Manifold broker service pid=$brokerPid"
    return $report
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "-Serial or RUSTY_QUEST_SERIAL is required."
}
if (-not (Test-Path $AgentBoard)) {
    throw "Agent Board script not found: $AgentBoard"
}
if (-not (Test-Path $WindowsHelperProject)) {
    throw "Windows QCL-041 helper project not found: $WindowsHelperProject"
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl041-live-windows-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}
if ($RunQcl082ProductMedia -and $HoldAfterSocketSeconds -le 0) {
    $HoldAfterSocketSeconds = 90
}
if ($RunQcl082ProductMedia -and [string]::IsNullOrWhiteSpace($Qcl082SessionId)) {
    $Qcl082SessionId = "session.qcl082.$RunId"
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $repoRoot "target\qcl041-wifi-direct-lifecycle\$RunId"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$apkPath = Join-Path $repoRoot "target\qcl041-wifi-direct-harness-android\rusty-quest-qcl041-wifi-direct-harness.apk"
$brokerApkPath = Join-Path $repoRoot "target\manifold-broker-android\rusty-manifold-broker.apk"
if ($Build -or -not (Test-Path $apkPath)) {
    $apkPath = & (Join-Path $PSScriptRoot "Build-Qcl041WifiDirectHarnessAndroid.ps1") | Select-Object -Last 1
}
if ($RunQcl082ProductMedia -and ($Build -or -not (Test-Path $brokerApkPath))) {
    $brokerApkPath = & (Join-Path $PSScriptRoot "Build-ManifoldBrokerAndroid.ps1") | Select-Object -Last 1
}

$leaseResource = "quest:$Serial"
$reserveArgs = @(
    "reserve",
    $leaseResource,
    "--duration",
    "45m",
    "--task",
    "QCL-041 direct Wi-Fi lifecycle evidence",
    "--reason",
    "Quest Wi-Fi Direct lifecycle validation"
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
$reserveCommand = "& '$AgentBoard' " + ($reserveArgs -join " ")
$releaseCommand = ""
$reserveIntentToken = ""
$releaseIntentToken = ""
$leaseId = ""
$helperReport = Join-Path $OutDir "windows-helper.json"
$qcl081ReceiverSlug = "lsl"
$qcl081ReceiverReport = Join-Path $OutDir ("qcl081-wifi-direct-{0}-receiver.json" -f $qcl081ReceiverSlug)
$qcl081ReceiverStdout = Join-Path $OutDir ("qcl081-wifi-direct-{0}-receiver.stdout.txt" -f $qcl081ReceiverSlug)
$qcl081ReceiverStderr = Join-Path $OutDir ("qcl081-wifi-direct-{0}-receiver.stderr.txt" -f $qcl081ReceiverSlug)
$qcl081SourceId = "rusty-quest-qcl081-wifi-direct-$RunId"
$rawArtifact = Join-Path $OutDir "quest-artifact-raw.json"
$finalArtifact = Join-Path $OutDir "wifi-direct-lifecycle-qcl041-windows.live.json"
$qcl082ReceiverResult = Join-Path $OutDir "qcl082-rmanvid1-receiver-result.json"
$qcl082Report = Join-Path $OutDir "qcl082-product-media-live-qcl082.json"
$wifiDirectPermissionPregrantPath = Join-Path $OutDir "qcl041-wifi-direct-permission-pregrant.json"
$wifiDirectPermissionUiautomatorPath = Join-Path $OutDir "qcl041-wifi-direct-permission-uiautomator.json"
$qcl041HarnessLaunchPath = Join-Path $OutDir "qcl041-harness-launch.json"
$qcl082BrokerPermissionPreflightPath = Join-Path $OutDir "qcl082-broker-permission-preflight.json"
$qcl082BrokerPrestartPath = Join-Path $OutDir "qcl082-broker-prestart.json"
$helperProcess = $null
$qcl081ReceiverProcess = $null
$qcl082ReceiverProcess = $null
$qcl082Started = $false
$qcl082BrokerPrestarted = $false
$wifiDirectPermissionPregrant = $null

try {
    Write-Host "Checking Agent Board status before reserving $leaseResource"
    & $AgentBoard status | Out-Host
    $reserveOutput = & $AgentBoard @reserveArgs | Out-String
    Write-Host $reserveOutput
    Assert-AgentBoardReserveSucceeded -ReserveOutput $reserveOutput -Resource $leaseResource
    $leaseId = Get-LeaseId $reserveOutput
    $releaseCommand = "& '$AgentBoard' release $leaseId --result done"
    $reserveIntentToken = "agent-board-reserve-$leaseId-$leaseResource"
    $releaseIntentToken = "agent-board-release-$leaseId"

    if (-not $SkipInstall) {
        Invoke-Checked "QCL-041 harness APK install" $Adb @("-s", $Serial, "install", "-r", $apkPath)
        if ($RunQcl082ProductMedia) {
            if (-not (Test-Path $brokerApkPath)) {
                throw "QCL-082 broker APK not found: $brokerApkPath"
            }
            Invoke-Checked "QCL-082 broker APK install" $Adb @("-s", $Serial, "install", "-r", $brokerApkPath)
        }
        $wifiDirectPermissionPregrant = Grant-WifiDirectPermission `
            -AdbPath $Adb `
            -DeviceSerial $Serial `
            -OutPath $wifiDirectPermissionPregrantPath
    } elseif (Test-Path $wifiDirectPermissionPregrantPath) {
        $wifiDirectPermissionPregrant = Read-JsonFile $wifiDirectPermissionPregrantPath
    }
    Invoke-Checked "QCL-041 harness force-stop" $Adb @("-s", $Serial, "shell", "am", "force-stop", "io.github.mesmerprism.rustyquest.qcl041")
    $remoteArtifact = "/sdcard/Android/data/io.github.mesmerprism.rustyquest.qcl041/files/qcl041/latest.json"
    $remoteRunArtifact = "/sdcard/Android/data/io.github.mesmerprism.rustyquest.qcl041/files/qcl041/$RunId.json"
    $oldErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Adb -s $Serial shell rm -f $remoteArtifact $remoteRunArtifact *> $null
    } finally {
        $ErrorActionPreference = $oldErrorActionPreference
    }

    Write-Host "Verify Windows Mobile Hotspot is disabled before this run; Windows cannot run Mobile Hotspot and Wi-Fi Direct peer advertising at the same time."
    Invoke-Checked "QCL-041 Windows helper build" "dotnet" @("build", $WindowsHelperProject)
    $helperExe = Resolve-WindowsHelperExecutable -ProjectPath $WindowsHelperProject -ExplicitExe $WindowsHelperExe
    Write-Host "Using Windows QCL-041 helper executable: $helperExe"
    $helperArgs = @(
        "--run-id", $RunId,
        "--out", $helperReport,
        "--listen-port", $ListenPort.ToString(),
        "--timeout-seconds", $TimeoutSeconds.ToString(),
        "--socket-timeout-seconds", $SocketTimeoutSeconds.ToString(),
        "--autonomous-group-owner", "true"
    )
    $helperProcess = Start-Process -FilePath $helperExe -ArgumentList $helperArgs -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 3
    if ($RunQcl082ProductMedia) {
        if (-not (Test-Path $Qcl082WpfReceiverExe)) {
            throw "Hostess WPF receiver executable not found for QCL-082 live session: $Qcl082WpfReceiverExe"
        }
        if ($RunQcl082LivePreview -and -not (Test-Path $Qcl082Ffplay)) {
            throw "QCL-082 live preview ffplay executable not found: $Qcl082Ffplay"
        }
        if (-not (Test-Path $Qcl082TopologyReport)) {
            throw "QCL-082 topology report not found: $Qcl082TopologyReport"
        }
        if (-not (Test-Path $Qcl082FirewallReport)) {
            throw "QCL-082 firewall report not found: $Qcl082FirewallReport"
        }
        $qcl082ReceiverReadyOut = Join-Path $OutDir "qcl082-wpf-rmanvid1-receiver.ready.json"
        $qcl082ReceiverStdout = Join-Path $OutDir "qcl082-wpf-rmanvid1-receiver.stdout.txt"
        $qcl082ReceiverStderr = Join-Path $OutDir "qcl082-wpf-rmanvid1-receiver.stderr.txt"
        $qcl082CaptureOut = Join-Path $OutDir "qcl082-media-stream.rmanvid1"
        $qcl082SidecarOut = Join-Path $OutDir "qcl082-media-stream-receiver-sidecar.json"
        $qcl082ExecutionOut = Join-Path $OutDir "qcl082-media-stream-start-source.live-android-execution.json"
        $qcl082TimeoutText = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0}", $Qcl082TimeoutSeconds)
        $qcl082RemoteEndpoint = "${Qcl082ReceiverHost}:$Qcl082Port"
        $qcl082ReceiverArgs = @(
            "--qcl082-rmanvid1-receiver",
            "--out", $qcl082ReceiverResult,
            "--capture-out", $qcl082CaptureOut,
            "--sidecar-out", $qcl082SidecarOut,
            "--bind-host", $Qcl082BindHost,
            "--port", $Qcl082Port.ToString(),
            "--timeout-seconds", $qcl082TimeoutText,
            "--max-packets", $Qcl082MaxPackets.ToString(),
            "--runtime-status", $qcl082ExecutionOut,
            "--topology-report", $Qcl082TopologyReport,
            "--firewall-report", $Qcl082FirewallReport,
            "--source-remote-endpoint", $qcl082RemoteEndpoint,
            "--command-id", "command.media_stream.start_source",
            "--session-id", $Qcl082SessionId,
            "--quest-lease-id", $leaseId,
            "--quest-lease-resource", $leaseResource,
            "--quest-lease-reserved-before-live-steps",
            "--ready-out", $qcl082ReceiverReadyOut
        )
        if ($RunQcl082LivePreview) {
            $qcl082ReceiverArgs += @(
                "--preview-ffplay", $Qcl082Ffplay,
                "--preview-window-title", "Rusty_QCL082_Direct_WiFi_RMANVID1_Preview"
            )
        }
        if (Test-Path $qcl082ReceiverReadyOut) {
            Remove-Item -LiteralPath $qcl082ReceiverReadyOut -Force
        }
        if ($RunQcl082LivePreview) {
            Write-Host "Prestarting QCL-082 WPF product receiver with ffplay preview on ${Qcl082BindHost}:$Qcl082Port"
            $qcl082ReceiverProcess = Start-Process `
                -FilePath $Qcl082WpfReceiverExe `
                -ArgumentList $qcl082ReceiverArgs `
                -PassThru `
                -RedirectStandardOutput $qcl082ReceiverStdout `
                -RedirectStandardError $qcl082ReceiverStderr
        } else {
            Write-Host "Prestarting QCL-082 WPF product receiver on ${Qcl082BindHost}:$Qcl082Port"
            $qcl082ReceiverProcess = Start-Process `
                -FilePath $Qcl082WpfReceiverExe `
                -ArgumentList $qcl082ReceiverArgs `
                -PassThru `
                -WindowStyle Hidden `
                -RedirectStandardOutput $qcl082ReceiverStdout `
                -RedirectStandardError $qcl082ReceiverStderr
        }
        $qcl082ReadyDeadline = (Get-Date).AddSeconds(12)
        $qcl082ReceiverReady = $false
        while ((Get-Date) -lt $qcl082ReadyDeadline) {
            if ($qcl082ReceiverProcess.HasExited) {
                throw "Prestarted QCL-082 WPF receiver exited before it became ready with code $($qcl082ReceiverProcess.ExitCode)."
            }
            if (Test-Path $qcl082ReceiverReadyOut) {
                $qcl082Ready = Read-JsonFile $qcl082ReceiverReadyOut
                if ($qcl082Ready.status -eq "ready") {
                    $qcl082ReceiverReady = $true
                    break
                }
            }
            Start-Sleep -Milliseconds 250
        }
        if (-not $qcl082ReceiverReady) {
            throw "Prestarted QCL-082 WPF receiver did not write ready artifact before timeout: $qcl082ReceiverReadyOut"
        }
        Start-Qcl082BrokerBeforeHarness `
            -AdbPath $Adb `
            -DeviceSerial $Serial `
            -PermissionOutPath $qcl082BrokerPermissionPreflightPath `
            -PrestartOutPath $qcl082BrokerPrestartPath | Out-Null
        $qcl082BrokerPrestarted = $true
        Start-Qcl082ProductMediaSourceBeforeHarness `
            -RunId $RunId `
            -OutDir $OutDir `
            -HostessCtl $HostessCtl `
            -Python $Python `
            -AdbPath $Adb `
            -DeviceSerial $Serial `
            -SessionId $Qcl082SessionId `
            -SenderSourceKind $Qcl082SenderSourceKind `
            -SenderSourcePorts $Qcl082SenderSourcePorts `
            -SenderMediaProfiles $Qcl082SenderMediaProfiles `
            -SenderCameraIds $Qcl082SenderCameraIds `
            -SenderCameraId $Qcl082SenderCameraId `
            -SenderCameraFacing $Qcl082SenderCameraFacing `
            -SenderQualityProfile $Qcl082SenderQualityProfile `
            -CameraPermissionPolicy $Qcl082CameraPermissionPolicy `
            -PrestartedBroker:$qcl082BrokerPrestarted | Out-Null
    }
    if ($RunQcl081Lsl) {
        if (-not (Test-Path $Qcl081ReceiverScript)) {
            throw "QCL-081 Wi-Fi Direct LSL receiver script not found: $Qcl081ReceiverScript"
        }
        $receiverFile = "python"
        $receiverArgs = @(
            $Qcl081ReceiverScript,
            "--run-id", $RunId,
            "--out", $qcl081ReceiverReport,
            "--stream-name", $Qcl081StreamName,
            "--stream-type", $Qcl081StreamType,
            "--source-id", $qcl081SourceId,
            "--sample-count", $Qcl081SampleCount.ToString(),
            "--timeout-seconds", ([string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0}", $Qcl081TimeoutSeconds))
        )
        if (Test-Path $qcl081ReceiverReport) {
            Remove-Item -LiteralPath $qcl081ReceiverReport -Force
        }
        Write-Host "Starting QCL-081 Wi-Fi Direct LSL receiver backend=$Qcl081ReceiverBackend source_id=$qcl081SourceId"
        $qcl081ReceiverProcess = Start-Process -FilePath $receiverFile -ArgumentList $receiverArgs -PassThru -WindowStyle Hidden -RedirectStandardOutput $qcl081ReceiverStdout -RedirectStandardError $qcl081ReceiverStderr
        Start-Sleep -Milliseconds 500
    }

    $activity = "io.github.mesmerprism.rustyquest.qcl041/.Qcl041WifiDirectHarnessActivity"
    $service = "io.github.mesmerprism.rustyquest.qcl041/.Qcl041WifiDirectHarnessService"
    $qcl041LaunchSurface = if ($RunQcl082ProductMedia) { "foreground_service" } else { "activity" }
    $qcl041Component = if ($RunQcl082ProductMedia) { $service } else { $activity }
    $qcl041AmCommand = if ($RunQcl082ProductMedia) { "start-foreground-service" } else { "start" }
    $intentArgs = @(
        "-s", $Serial,
        "shell", "am", $qcl041AmCommand,
        "-n", $qcl041Component,
        "--es", "qcl041.run_id", $RunId,
        "--es", "qcl041.device_serial", $Serial,
        "--es", "qcl041.lease_id", $leaseId,
        "--es", "qcl041.lease_resource", $leaseResource,
        "--es", "qcl041.reserve_command", $reserveIntentToken,
        "--es", "qcl041.release_command", $releaseIntentToken,
        "--ez", "qcl041.lease_reserved_before_live_steps", "true",
        "--ez", "qcl041.lease_released_after_live_steps", "false",
        "--ez", "qcl041.windows_api_observed", "true",
        "--es", "qcl041.windows_api_evidence", "windows-helper-launched-final-report-merged-after-cleanup",
        "--ei", "qcl041.listen_port", $ListenPort.ToString(),
        "--ei", "qcl041.timeout_seconds", $TimeoutSeconds.ToString(),
        "--ei", "qcl041.socket_timeout_seconds", $SocketTimeoutSeconds.ToString(),
        "--ei", "qcl041.group_owner_intent", $GroupOwnerIntent.ToString(),
        "--ei", "qcl041.hold_after_socket_ms", ([Math]::Max(0, $HoldAfterSocketSeconds) * 1000).ToString()
    )
    if ($RunQcl082ProductMedia) {
        $qcl082RelaySourcePort = Get-Qcl082RelaySourcePort -SenderSourcePorts $Qcl082SenderSourcePorts
        $intentArgs += @(
            "--ez", "qcl041.qcl082_relay_enabled", "true",
            "--es", "qcl041.qcl082_relay_source_host", "127.0.0.1",
            "--ei", "qcl041.qcl082_relay_source_port", $qcl082RelaySourcePort.ToString(),
            "--es", "qcl041.qcl082_relay_receiver_host", $Qcl082ReceiverHost,
            "--ei", "qcl041.qcl082_relay_receiver_port", $Qcl082Port.ToString(),
            "--ei", "qcl041.qcl082_relay_timeout_seconds", ([int][Math]::Ceiling($Qcl082TimeoutSeconds)).ToString(),
            "--ei", "qcl041.qcl082_relay_max_bytes", "8388608",
            "--ei", "qcl041.qcl082_relay_start_delay_ms", "0"
        )
    }
    if ($RunQcl081Lsl) {
        $intentArgs += @(
            "--ez", "qcl041.qcl081_lsl_enabled", "true",
            "--es", "qcl041.qcl081_lsl_backend", $Qcl081LslBackend,
            "--es", "qcl041.qcl081_lsl_stream_name", $Qcl081StreamName,
            "--es", "qcl041.qcl081_lsl_stream_type", $Qcl081StreamType,
            "--es", "qcl041.qcl081_lsl_source_id", $qcl081SourceId,
            "--ei", "qcl041.qcl081_lsl_sample_count", $Qcl081SampleCount.ToString(),
            "--ei", "qcl041.qcl081_lsl_warmup_ms", $Qcl081WarmupMs.ToString(),
            "--ei", "qcl041.qcl081_lsl_interval_ms", $Qcl081IntervalMs.ToString()
        )
    }
    if (-not [string]::IsNullOrWhiteSpace($WindowsPeerNameContains)) {
        $intentArgs += @("--es", "qcl041.windows_peer_name_contains", $WindowsPeerNameContains)
    }
    Invoke-Checked "QCL-041 harness launch" $Adb $intentArgs
    $qcl041PackageName = "io.github.mesmerprism.rustyquest.qcl041"
    $qcl041Pid = Wait-AndroidPackagePid `
        -AdbPath $Adb `
        -DeviceSerial $Serial `
        -PackageName $qcl041PackageName `
        -TimeoutSeconds 10.0
    $activityTopAfterLaunch = Invoke-AdbCapture `
        -AdbPath $Adb `
        -DeviceSerial $Serial `
        -Arguments @("shell", "dumpsys", "activity", "top")
    Write-JsonFile -Value ([ordered]@{
        schema = "rusty.quest.qcl041.harness_launch.v1"
        status = if ([string]::IsNullOrWhiteSpace($qcl041Pid)) { "blocked" } else { "pass" }
        package = $qcl041PackageName
        activity = $activity
        service = $service
        component = $qcl041Component
        launch_surface = $qcl041LaunchSurface
        am_command = $qcl041AmCommand
        serial = $Serial
        pid = $qcl041Pid
        am_start_invoked = $true
        activity_top_exit_code = $activityTopAfterLaunch.exit_code
        activity_top_excerpt = (($activityTopAfterLaunch.output -split "`r?`n") | Select-Object -First 80) -join "`n"
    }) -Path $qcl041HarnessLaunchPath
    if ([string]::IsNullOrWhiteSpace($qcl041Pid)) {
        throw "QCL-041 harness $qcl041LaunchSurface launch did not leave a running package pid; see $qcl041HarnessLaunchPath"
    }

    $sdkTextAfterLaunch = (& $Adb -s $Serial shell getprop ro.build.version.sdk | Out-String).Trim()
    $sdkAfterLaunch = 0
    [void][int]::TryParse($sdkTextAfterLaunch, [ref]$sdkAfterLaunch)
    $wifiDirectRuntimePermission = if ($sdkAfterLaunch -ge 33) { "android.permission.NEARBY_WIFI_DEVICES" } else { "android.permission.ACCESS_FINE_LOCATION" }
    $permissionReadback = Get-AndroidPermissionReadback `
        -AdbPath $Adb `
        -DeviceSerial $Serial `
        -PackageName "io.github.mesmerprism.rustyquest.qcl041" `
        -Permission $wifiDirectRuntimePermission
    if ($permissionReadback.granted -ne $true) {
        Write-Host "QCL-041 runtime permission still not granted after launch; attempting UIAutomator permission dialog fallback."
        Invoke-UiautomatorPermissionGrantIfNeeded `
            -AdbPath $Adb `
            -DeviceSerial $Serial `
            -PackageName "io.github.mesmerprism.rustyquest.qcl041" `
            -Permission $wifiDirectRuntimePermission `
            -OutPath $wifiDirectPermissionUiautomatorPath | Out-Null
    } else {
        Write-JsonFile -Value ([ordered]@{
            schema = "rusty.quest.android_runtime_permission_uiautomator_fallback.v1"
            package = "io.github.mesmerprism.rustyquest.qcl041"
            serial = $Serial
            permission = $wifiDirectRuntimePermission
            check_permission_before = $permissionReadback
            uiautomator_tap_attempted = $false
            check_permission_after = $permissionReadback
        }) -Path $wifiDirectPermissionUiautomatorPath
    }

    if (Test-Path $rawArtifact) {
        Remove-Item -LiteralPath $rawArtifact -Force
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds + $SocketTimeoutSeconds + [Math]::Max(0, $HoldAfterSocketSeconds) + 30)
    $artifactReady = $false
    while ((Get-Date) -lt $deadline) {
        $oldErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $Adb -s $Serial pull $remoteArtifact $rawArtifact *> $null
            $pullExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $oldErrorActionPreference
        }
        if ($pullExitCode -ne 0) {
            Start-Sleep -Seconds 2
            continue
        }
        if (Test-Path $rawArtifact) {
            $content = Get-Content -Raw -Path $rawArtifact
            if (-not [string]::IsNullOrWhiteSpace($content)) {
                $candidate = $null
                try {
                    $candidate = $content | ConvertFrom-Json
                } catch {
                    Write-Host "Pulled artifact is not complete JSON yet; waiting."
                    Start-Sleep -Seconds 2
                    continue
                }
                if ($RunQcl082ProductMedia -and -not $qcl082Started -and (
                        (Test-Qcl041SocketExchangeReady -Artifact $candidate -ExpectedRunId $RunId) -or
                        (Test-Qcl041ActiveGroupHoldReady -Artifact $candidate -ExpectedRunId $RunId))) {
                    $qcl082LocalBindHost = ""
                    if ($null -ne $candidate.diagnostics.lifecycle) {
                        $qcl082LocalBindHost = [string]$candidate.diagnostics.lifecycle.wifi_direct_local_address
                    }
                    $qcl082ReceiverResult = Invoke-Qcl082ProductMediaLiveSession `
                        -RunId $RunId `
                            -OutDir $OutDir `
                            -HostessCtl $HostessCtl `
                            -WpfReceiverExe $Qcl082WpfReceiverExe `
                            -Python $Python `
                        -AdbPath $Adb `
                        -DeviceSerial $Serial `
                        -LeaseId $leaseId `
                        -LeaseResource $leaseResource `
                        -TopologyReport $Qcl082TopologyReport `
                        -FirewallReport $Qcl082FirewallReport `
                        -ReceiverHost $Qcl082ReceiverHost `
                        -LocalBindHost $qcl082LocalBindHost `
                        -BindHost $Qcl082BindHost `
                        -Port $Qcl082Port `
                        -MaxPackets $Qcl082MaxPackets `
                        -TimeoutSeconds $Qcl082TimeoutSeconds `
                        -SessionId $Qcl082SessionId `
                        -SenderSourceKind $Qcl082SenderSourceKind `
                        -SenderSourcePorts $Qcl082SenderSourcePorts `
                        -SenderMediaProfiles $Qcl082SenderMediaProfiles `
                        -SenderCameraIds $Qcl082SenderCameraIds `
                        -SenderCameraId $Qcl082SenderCameraId `
                        -SenderCameraFacing $Qcl082SenderCameraFacing `
                        -SenderQualityProfile $Qcl082SenderQualityProfile `
                        -CameraPermissionPolicy $Qcl082CameraPermissionPolicy `
                        -PrestartedBroker:$qcl082BrokerPrestarted `
                        -PrestartedReceiverProcess $qcl082ReceiverProcess
                    $qcl082Started = $true
                    $qcl082ReceiverProcess = $null
                }
                if ($candidate.run_id -eq $RunId -and $candidate.lifecycle.cleanup.completed -eq $true) {
                    $artifactReady = $true
                    break
                }
                if ($candidate.run_id -ne $RunId) {
                    Write-Host "Ignoring stale QCL-041 artifact run_id=$($candidate.run_id); waiting for $RunId."
                }
            }
        }
        Start-Sleep -Seconds 2
    }
    if (-not $artifactReady) {
        throw "Quest QCL-041 harness did not produce a cleanup-complete $remoteArtifact before timeout."
    }
    if ($RunQcl082ProductMedia -and -not $qcl082Started) {
        throw "QCL-082 product media live session was requested, but QCL-041 did not expose a held active group before cleanup."
    }

    if ($helperProcess -and -not $helperProcess.HasExited) {
        $helperProcess.WaitForExit(($TimeoutSeconds + $SocketTimeoutSeconds + 10) * 1000) | Out-Null
    }
    if ($qcl081ReceiverProcess -and -not $qcl081ReceiverProcess.HasExited) {
        $qcl081ReceiverProcess.WaitForExit([int](($Qcl081TimeoutSeconds + 5.0) * 1000.0)) | Out-Null
    }
} finally {
    if ($qcl081ReceiverProcess -and -not $qcl081ReceiverProcess.HasExited) {
        Stop-Process -Id $qcl081ReceiverProcess.Id -Force
    }
    if ($qcl082ReceiverProcess -and -not $qcl082ReceiverProcess.HasExited) {
        Stop-Process -Id $qcl082ReceiverProcess.Id -Force
    }
    if ($RunQcl082ProductMedia) {
        $oldErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $Adb -s $Serial shell am force-stop io.github.mesmerprism.rustyquest.qcl041 *> $null
        } finally {
            $ErrorActionPreference = $oldErrorActionPreference
        }
    }
    if ($qcl082BrokerPrestarted) {
        $oldErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $Adb -s $Serial shell am force-stop io.github.mesmerprism.rustymanifold.broker *> $null
        } finally {
            $ErrorActionPreference = $oldErrorActionPreference
        }
    }
    if ($helperProcess -and -not $helperProcess.HasExited) {
        Stop-Process -Id $helperProcess.Id -Force
    }
    if (-not [string]::IsNullOrWhiteSpace($leaseId)) {
        & $AgentBoard release $leaseId --result done | Out-Host
    }
}

if (-not (Test-Path $helperReport)) {
    throw "Windows QCL-041 helper did not produce $helperReport"
}
if ($RunQcl081Lsl -and -not (Test-Path $qcl081ReceiverReport)) {
    throw "QCL-081 Wi-Fi Direct LSL receiver did not produce $qcl081ReceiverReport"
}
if ($RunQcl082ProductMedia -and -not (Test-Path $qcl082ReceiverResult)) {
    throw "QCL-082 product media live session did not produce $qcl082ReceiverResult"
}

$artifact = Read-JsonFile $rawArtifact
$helper = Read-JsonFile $helperReport
$artifact.lease.reserve_command = $reserveCommand
$artifact.lease.released_after_live_steps = $true
$artifact.lease.release_command = $releaseCommand
Update-ArtifactFromWindowsHelper -Artifact $artifact -Helper $helper
if ($null -eq $artifact.diagnostics) {
    $artifact | Add-Member -MemberType NoteProperty -Name diagnostics -Value ([pscustomobject]@{})
}
if ($null -eq $artifact.diagnostics.permissions) {
    $artifact.diagnostics | Add-Member -MemberType NoteProperty -Name permissions -Value ([pscustomobject]@{})
}
if (Test-Path $wifiDirectPermissionPregrantPath) {
    $pregrant = Read-JsonFile $wifiDirectPermissionPregrantPath
    $artifact.diagnostics.permissions | Add-Member -Force -MemberType NoteProperty -Name adb_pregrant_artifact -Value $wifiDirectPermissionPregrantPath
    $artifact.diagnostics.permissions | Add-Member -Force -MemberType NoteProperty -Name adb_pregrant_schema -Value $pregrant.schema
}
if (Test-Path $wifiDirectPermissionUiautomatorPath) {
    $uiautomatorGrant = Read-JsonFile $wifiDirectPermissionUiautomatorPath
    $artifact.diagnostics.permissions | Add-Member -Force -MemberType NoteProperty -Name uiautomator_fallback_artifact -Value $wifiDirectPermissionUiautomatorPath
    $artifact.diagnostics.permissions | Add-Member -Force -MemberType NoteProperty -Name uiautomator_tap_attempted -Value $uiautomatorGrant.uiautomator_tap_attempted
}
if ($RunQcl082ProductMedia) {
    if ($null -eq $artifact.diagnostics.qcl082_product_media) {
        $artifact.diagnostics | Add-Member -MemberType NoteProperty -Name qcl082_product_media -Value ([pscustomobject]@{})
    }
    if (Test-Path $qcl082BrokerPermissionPreflightPath) {
        $brokerPermission = Read-JsonFile $qcl082BrokerPermissionPreflightPath
        $artifact.diagnostics.qcl082_product_media | Add-Member -Force -MemberType NoteProperty -Name broker_permission_preflight_artifact -Value $qcl082BrokerPermissionPreflightPath
        $artifact.diagnostics.qcl082_product_media | Add-Member -Force -MemberType NoteProperty -Name broker_permission_preflight_schema -Value $brokerPermission.schema
    }
    if (Test-Path $qcl082BrokerPrestartPath) {
        $brokerPrestart = Read-JsonFile $qcl082BrokerPrestartPath
        $artifact.diagnostics.qcl082_product_media | Add-Member -Force -MemberType NoteProperty -Name broker_prestart_artifact -Value $qcl082BrokerPrestartPath
        $artifact.diagnostics.qcl082_product_media | Add-Member -Force -MemberType NoteProperty -Name broker_prestart_status -Value $brokerPrestart.status
        $artifact.diagnostics.qcl082_product_media | Add-Member -Force -MemberType NoteProperty -Name broker_prestart_pid -Value $brokerPrestart.pid
    }
}
Write-JsonFile -Value $artifact -Path $finalArtifact
if ($RunQcl081Lsl -and (Test-Path $qcl081ReceiverReport)) {
    $qcl081Report = Read-JsonFile $qcl081ReceiverReport
    if ($null -eq $qcl081Report.topology) {
        $qcl081Report | Add-Member -MemberType NoteProperty -Name topology -Value ([pscustomobject]@{})
    }
    $hostEndpoint = $artifact.diagnostics.qcl081_lsl.windows_group_owner_address
    if ([string]::IsNullOrWhiteSpace($hostEndpoint)) {
        $hostEndpoint = "192.168.137.1"
    }
    $deviceEndpoint = $artifact.diagnostics.lifecycle.wifi_direct_local_address
    $qcl081Report | Add-Member -Force -MemberType NoteProperty -Name producer_backend -Value $Qcl081LslBackend
    $qcl081Report | Add-Member -Force -MemberType NoteProperty -Name receiver_backend -Value $Qcl081ReceiverBackend
    $qcl081Report | Add-Member -Force -MemberType NoteProperty -Name host_endpoint -Value $hostEndpoint
    $qcl081Report | Add-Member -Force -MemberType NoteProperty -Name device_endpoint -Value $deviceEndpoint
    $qcl081Report.topology | Add-Member -Force -MemberType NoteProperty -Name topology_report_path -Value $finalArtifact
    $qcl081Report.topology | Add-Member -Force -MemberType NoteProperty -Name local_endpoint -Value $hostEndpoint
    $qcl081Report.topology | Add-Member -Force -MemberType NoteProperty -Name remote_endpoint -Value $deviceEndpoint
    $qcl081Report.topology | Add-Member -Force -MemberType NoteProperty -Name paired_topology_status -Value "pass"
    $qcl081Report.topology | Add-Member -Force -MemberType NoteProperty -Name paired_topology_promotion_allowed -Value $true
    Write-JsonFile -Value $qcl081Report -Path $qcl081ReceiverReport
}
if ($RunQcl082ProductMedia -and (Test-Path $qcl082ReceiverResult)) {
    Invoke-Checked "Hostess QCL-082 product media normalization" $Python @(
        $HostessCtl,
        "connectivity-probe",
        "run",
        "--probe-id",
        "QCL-082",
        "--media-stream-receiver-result",
        $qcl082ReceiverResult,
        "--out",
        $qcl082Report,
        "--fail-on-error"
    )
}

Write-Host "Final QCL-041 lifecycle artifact: $finalArtifact"
if ($RunQcl081Lsl) {
    Write-Host "QCL-081 Wi-Fi Direct LSL receiver artifact: $qcl081ReceiverReport"
    Write-Host "Hostess QCL-081 promotion command:"
    Write-Host "python S:\Work\repos\active\rusty-hostess\tools\hostessctl\hostessctl.py connectivity-probe run --mode live --probe-id QCL-081 --lsl-source quest-runtime --lsl-quest-runtime-report `"$qcl081ReceiverReport`" --serial $Serial --adb `"$Adb`" --out target\connectivity-probe\qcl081-live-wifi-direct-lsl.json --fail-on-error"
}
if ($RunQcl082ProductMedia) {
    Write-Host "QCL-082 product media receiver artifact: $qcl082ReceiverResult"
    Write-Host "QCL-082 product media report: $qcl082Report"
}
Write-Host "Hostess promotion command:"
Write-Host "python S:\Work\repos\active\rusty-hostess\tools\hostessctl\hostessctl.py connectivity-probe run --mode fixture --probe-id QCL-041 --wifi-direct-lifecycle-report `"$finalArtifact`" --out target\connectivity-probe\qcl041-live-wifi-direct-lifecycle.json --fail-on-error"
Write-Output $finalArtifact
