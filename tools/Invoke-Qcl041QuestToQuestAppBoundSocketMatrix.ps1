param(
    [string]$OwnerSerial = "340YC10G7T0JBW",
    [string]$ClientSerial = "3487C10H3M017Q",
    [string]$RunId = "",
    [string]$OutDir = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [string]$Qcl041Apk = "S:\Work\repos\active\rusty-quest\target\qcl041-wifi-direct-harness-android\rusty-quest-qcl041-wifi-direct-harness.apk",
    [string]$Qcl041Q2qNetworkName = "DIRECT-rq-QCL100",
    [string]$Qcl041Q2qPassphrase = "RustyQcl100Pass",
    [int]$MatrixPort = 18868,
    [int]$TimeoutSeconds = 115,
    [int]$SocketTimeoutSeconds = 30,
    [int]$HoldAfterSocketSeconds = 5,
    [int]$DelayedUdpDelaySeconds = 45,
    [int]$TcpTunnelStreamSeconds = 15,
    [int]$TcpTunnelStreamBytesPerDirection = 4194304,
    [int]$LaunchDelaySeconds = 4,
    [string]$RouteProbeTarget = "192.168.49.1",
    [string]$OwnerWifiDirectAddress = "192.168.49.1",
    [string]$ClientWifiDirectAddress = "192.168.49.46",
    [int]$ActiveRouteProbeWaitSeconds = 35,
    [switch]$RequireInfrastructureWifiDisconnected,
    [switch]$RequireP2p0Ipv4Cleared,
    [switch]$RequireCandidateWifiDirectRoutesClear,
    [switch]$RequireTcpTunnelStreamPass,
    [switch]$Qcl100ControlTcpGate,
    [switch]$PreflightOnly,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"

$Qcl041Package = "io.github.mesmerprism.rustyquest.qcl041"
$requestedDelayedUdpDelaySeconds = [Math]::Max(0, $DelayedUdpDelaySeconds)
$effectiveDelayedUdpDelaySeconds = if ($Qcl100ControlTcpGate) { 0 } else { $requestedDelayedUdpDelaySeconds }
$effectiveRequireTcpTunnelStreamPass = [bool]($RequireTcpTunnelStreamPass -or $Qcl100ControlTcpGate)
$matrixFocus = if ($Qcl100ControlTcpGate) { "qcl100_control_tcp_gate" } else { "full_app_bound_socket_matrix" }
$delayedUdpRequired = [bool](-not [bool]$Qcl100ControlTcpGate -and $effectiveDelayedUdpDelaySeconds -gt 0)
$wholeMatrixCompletionRequired = [bool](-not [bool]$Qcl100ControlTcpGate)

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl041-q2q-app-bound-socket-matrix-" + (Get-Date -Format "yyyyMMddTHHmmssZ").ToString()
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path "S:\Work\repos\active\rusty-quest\target" $RunId
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Write-Qcl041JsonFile {
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

function Invoke-AdbText {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Serial,
        [Parameter(Mandatory=$true)]
        [string[]]$Arguments,
        [string]$Name = "adb"
    )
    $output = & $Adb -s $Serial @Arguments 2>&1 | Out-String
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "$Name failed for ${Serial} with exit code $exitCode. $output"
    }
    return $output
}

function Invoke-AdbProbe {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Serial,
        [Parameter(Mandatory=$true)]
        [string[]]$Arguments,
        [string]$Name = "adb probe"
    )
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $Adb -s $Serial @Arguments 2>&1 | ForEach-Object { "$_" } | Out-String
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    [ordered]@{
        name = $Name
        arguments = ($Arguments -join " ")
        exit_code = $exitCode
        output = $output.Trim()
    }
}

function Get-Qcl041WifiStatus {
    param([Parameter(Mandatory=$true)][string]$Serial)
    $output = Invoke-AdbText -Serial $Serial -Arguments @("shell", "cmd", "wifi", "status") -Name "wifi status"
    $ssid = ""
    if ($output -match 'Wifi is connected to "([^"]+)"') {
        $ssid = $Matches[1]
    }
    [ordered]@{
        serial = $Serial
        wifi_enabled = [bool]($output -match '(?m)^Wifi is enabled')
        infrastructure_connected = [bool](-not [string]::IsNullOrWhiteSpace($ssid))
        infrastructure_ssid = $ssid
        raw_status = $output.Trim()
    }
}

function Get-Qcl041P2pIpv4Status {
    param([Parameter(Mandatory=$true)][string]$Serial)
    $output = & $Adb -s $Serial shell ip -4 addr show p2p0 2>&1 | Out-String
    $exitCode = $LASTEXITCODE
    $address = ""
    if ($output -match '\binet\s+([0-9.]+)/') {
        $address = $Matches[1]
    }
    [ordered]@{
        serial = $Serial
        interface = "p2p0"
        exit_code = $exitCode
        ipv4_present = [bool](-not [string]::IsNullOrWhiteSpace($address))
        ipv4_address = $address
        raw_status = $output.Trim()
    }
}

function Test-Qcl041RouteUsesP2p0 {
    param($Probe)
    if ($null -eq $Probe) {
        return $false
    }
    $output = [string]$Probe.output
    return [bool]($Probe.exit_code -eq 0 -and $output -match '(^|\s)dev\s+p2p0(\s|$)')
}

function Get-Qcl041RouteProbeDevice {
    param($Probe)
    if ($null -eq $Probe) {
        return ""
    }
    $output = [string]$Probe.output
    if ($output -match '\bdev\s+(\S+)') {
        return $Matches[1]
    }
    return ""
}

function Get-Qcl041RouteProbeSource {
    param($Probe)
    if ($null -eq $Probe) {
        return ""
    }
    $output = [string]$Probe.output
    if ($output -match '\bsrc\s+([0-9.]+)') {
        return $Matches[1]
    }
    return ""
}

function Test-Qcl041RouteProbeUnreachable {
    param($Probe)
    if ($null -eq $Probe) {
        return $true
    }
    $output = [string]$Probe.output
    return [bool](
        $Probe.exit_code -ne 0 -or
        $output -match 'Network is unreachable' -or
        $output -match 'RTNETLINK answers')
}

function Convert-Qcl041RouteProbeSummary {
    param(
        $Probe,
        [Parameter(Mandatory=$true)]
        [string]$Serial,
        [Parameter(Mandatory=$true)]
        [string]$Label,
        [Parameter(Mandatory=$true)]
        [string]$TargetAddress
    )
    $device = Get-Qcl041RouteProbeDevice -Probe $Probe
    $source = Get-Qcl041RouteProbeSource -Probe $Probe
    $unreachable = Test-Qcl041RouteProbeUnreachable -Probe $Probe
    $exitCode = if ($null -eq $Probe) { -1 } else { [int]$Probe.exit_code }
    $rawStatus = if ($null -eq $Probe) { "" } else { [string]$Probe.output }
    [ordered]@{
        serial = $Serial
        label = $Label
        target_address = $TargetAddress
        command = "ip route get $TargetAddress"
        exit_code = $exitCode
        reachable = [bool](-not $unreachable -and -not [string]::IsNullOrWhiteSpace($device))
        route_device = $device
        route_source = $source
        uses_p2p0 = [bool]($device -eq "p2p0")
        uses_wlan0 = [bool]($device -eq "wlan0")
        uses_loopback = [bool]($device -eq "lo")
        local_self_route = [bool]($device -eq "lo" -and $source -eq $TargetAddress)
        unreachable = $unreachable
        raw_status = $rawStatus.Trim()
    }
}

function Get-Qcl041CandidateWifiDirectRouteSnapshot {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Serial,
        [Parameter(Mandatory=$true)]
        [string]$Label,
        [Parameter(Mandatory=$true)]
        [string]$TargetAddress
    )
    if ($TargetAddress -notmatch '^[0-9.]+$') {
        throw "QCL041 candidate route snapshot target must be an IPv4 address: $TargetAddress"
    }
    $routeGet = Invoke-AdbProbe `
        -Serial $Serial `
        -Arguments @("shell", "ip", "route", "get", $TargetAddress) `
        -Name "ip route get candidate Wi-Fi Direct target"
    Convert-Qcl041RouteProbeSummary `
        -Probe $routeGet `
        -Serial $Serial `
        -Label $Label `
        -TargetAddress $TargetAddress
}

function Get-Qcl041CandidateWifiDirectRoutes {
    param([Parameter(Mandatory=$true)][string]$Phase)
    [ordered]@{
        phase = $Phase
        owner_wifi_direct_address = $OwnerWifiDirectAddress
        client_wifi_direct_address = $ClientWifiDirectAddress
        owner_to_owner_wifi_direct_address = Get-Qcl041CandidateWifiDirectRouteSnapshot -Serial $OwnerSerial -Label "owner-to-owner-wifi-direct-address" -TargetAddress $OwnerWifiDirectAddress
        owner_to_client_wifi_direct_address = Get-Qcl041CandidateWifiDirectRouteSnapshot -Serial $OwnerSerial -Label "owner-to-client-wifi-direct-address" -TargetAddress $ClientWifiDirectAddress
        client_to_owner_wifi_direct_address = Get-Qcl041CandidateWifiDirectRouteSnapshot -Serial $ClientSerial -Label "client-to-owner-wifi-direct-address" -TargetAddress $OwnerWifiDirectAddress
        client_to_client_wifi_direct_address = Get-Qcl041CandidateWifiDirectRouteSnapshot -Serial $ClientSerial -Label "client-to-client-wifi-direct-address" -TargetAddress $ClientWifiDirectAddress
    }
}

function Get-Qcl041CandidateWifiDirectRouteSnapshots {
    param($CandidateRoutes)
    if ($null -eq $CandidateRoutes) {
        return @()
    }
    @(
        $CandidateRoutes.owner_to_owner_wifi_direct_address
        $CandidateRoutes.owner_to_client_wifi_direct_address
        $CandidateRoutes.client_to_owner_wifi_direct_address
        $CandidateRoutes.client_to_client_wifi_direct_address
    ) | Where-Object { $null -ne $_ }
}

function Add-Qcl041CandidateWifiDirectRouteCounts {
    param(
        [Parameter(Mandatory=$true)]
        [System.Collections.IDictionary]$Preflight
    )
    $candidateRoutes = @(Get-Qcl041CandidateWifiDirectRouteSnapshots -CandidateRoutes $Preflight.candidate_wifi_direct_shell_routes)
    $Preflight["candidate_wifi_direct_route_count"] = $candidateRoutes.Count
    $Preflight["candidate_wifi_direct_routes_using_wlan0"] = @($candidateRoutes | Where-Object { $_.uses_wlan0 }).Count
    $Preflight["candidate_wifi_direct_routes_using_p2p0"] = @($candidateRoutes | Where-Object { $_.uses_p2p0 }).Count
    $Preflight["candidate_wifi_direct_routes_using_loopback"] = @($candidateRoutes | Where-Object { $_.uses_loopback }).Count
    $Preflight["candidate_wifi_direct_local_self_routes"] = @($candidateRoutes | Where-Object { $_.local_self_route }).Count
    $Preflight["candidate_wifi_direct_routes_unreachable"] = @($candidateRoutes | Where-Object { $_.unreachable }).Count
    $Preflight["candidate_wifi_direct_routes_reachable"] = @($candidateRoutes | Where-Object { $_.reachable }).Count
    $Preflight["candidate_wifi_direct_prelaunch_routes_clear"] = [bool](
        $Preflight.candidate_wifi_direct_routes_using_wlan0 -eq 0 -and
        $Preflight.candidate_wifi_direct_routes_using_p2p0 -eq 0 -and
        $Preflight.candidate_wifi_direct_local_self_routes -eq 0)
}

function Get-Qcl041ShellRouteSnapshot {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Serial,
        [Parameter(Mandatory=$true)]
        [string]$Phase,
        [Parameter(Mandatory=$true)]
        [string]$TargetAddress,
        [string]$SourceAddress = ""
    )
    $routeGet = Invoke-AdbProbe `
        -Serial $Serial `
        -Arguments @("shell", "ip", "route", "get", $TargetAddress) `
        -Name "ip route get target"
    $routeGetFrom = $null
    if (-not [string]::IsNullOrWhiteSpace($SourceAddress)) {
        $routeGetFrom = Invoke-AdbProbe `
            -Serial $Serial `
            -Arguments @("shell", "ip", "route", "get", $TargetAddress, "from", $SourceAddress) `
            -Name "ip route get target from p2p source"
    }
    $routeGetSummary = Convert-Qcl041RouteProbeSummary `
        -Probe $routeGet `
        -Serial $Serial `
        -Label "$Phase-route-get" `
        -TargetAddress $TargetAddress
    $routeGetFromSummary = if ($null -ne $routeGetFrom) {
        Convert-Qcl041RouteProbeSummary `
            -Probe $routeGetFrom `
            -Serial $Serial `
            -Label "$Phase-route-get-from-p2p-source" `
            -TargetAddress $TargetAddress
    } else {
        $null
    }
    $ipRule = Invoke-AdbProbe `
        -Serial $Serial `
        -Arguments @("shell", "ip", "rule", "show") `
        -Name "ip rule show"
    $routeTableAll = Invoke-AdbProbe `
        -Serial $Serial `
        -Arguments @("shell", "ip", "route", "show", "table", "all") `
        -Name "ip route show table all"
    $wifi = Get-Qcl041WifiStatus -Serial $Serial
    $p2p0 = Get-Qcl041P2pIpv4Status -Serial $Serial
    [ordered]@{
        serial = $Serial
        phase = $Phase
        target_address = $TargetAddress
        source_address = $SourceAddress
        wifi = $wifi
        p2p0 = $p2p0
        route_get = $routeGet
        route_get_summary = $routeGetSummary
        route_get_uses_p2p0 = [bool]$routeGetSummary.uses_p2p0
        route_get_uses_wlan0 = [bool]$routeGetSummary.uses_wlan0
        route_get_uses_loopback = [bool]$routeGetSummary.uses_loopback
        route_get_local_self_route = [bool]$routeGetSummary.local_self_route
        route_get_unreachable = [bool]$routeGetSummary.unreachable
        route_get_from_p2p_source = $routeGetFrom
        route_get_from_p2p_source_summary = $routeGetFromSummary
        route_get_from_p2p_source_uses_p2p0 = if ($null -ne $routeGetFromSummary) { [bool]$routeGetFromSummary.uses_p2p0 } else { $false }
        route_get_from_p2p_source_uses_wlan0 = if ($null -ne $routeGetFromSummary) { [bool]$routeGetFromSummary.uses_wlan0 } else { $false }
        route_get_from_p2p_source_uses_loopback = if ($null -ne $routeGetFromSummary) { [bool]$routeGetFromSummary.uses_loopback } else { $false }
        route_get_from_p2p_source_local_self_route = if ($null -ne $routeGetFromSummary) { [bool]$routeGetFromSummary.local_self_route } else { $false }
        route_get_from_p2p_source_unreachable = if ($null -ne $routeGetFromSummary) { [bool]$routeGetFromSummary.unreachable } else { $false }
        ip_rule_show = $ipRule
        ip_route_show_table_all = $routeTableAll
    }
}

function Get-Qcl041ShellRouteSnapshots {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Phase,
        [Parameter(Mandatory=$true)]
        [string]$TargetAddress
    )
    $ownerP2p0 = Get-Qcl041P2pIpv4Status -Serial $OwnerSerial
    $clientP2p0 = Get-Qcl041P2pIpv4Status -Serial $ClientSerial
    $owner = Get-Qcl041ShellRouteSnapshot `
        -Serial $OwnerSerial `
        -Phase $Phase `
        -TargetAddress $TargetAddress `
        -SourceAddress $ownerP2p0.ipv4_address
    $client = Get-Qcl041ShellRouteSnapshot `
        -Serial $ClientSerial `
        -Phase $Phase `
        -TargetAddress $TargetAddress `
        -SourceAddress $clientP2p0.ipv4_address
    [ordered]@{
        phase = $Phase
        target_address = $TargetAddress
        owner = $owner
        client = $client
        owner_route_get_uses_p2p0 = [bool]$owner.route_get_uses_p2p0
        owner_route_get_from_p2p_source_uses_p2p0 = [bool]$owner.route_get_from_p2p_source_uses_p2p0
        client_route_get_uses_p2p0 = [bool]$client.route_get_uses_p2p0
        client_route_get_from_p2p_source_uses_p2p0 = [bool]$client.route_get_from_p2p_source_uses_p2p0
        infrastructure_wifi_connected = [bool](
            [bool]$owner.wifi.infrastructure_connected -or
            [bool]$client.wifi.infrastructure_connected)
        both_p2p0_ipv4_present = [bool](
            [bool]$owner.p2p0.ipv4_present -and
            [bool]$client.p2p0.ipv4_present)
    }
}

function Wait-Qcl041ActiveShellRouteSnapshot {
    param(
        [Parameter(Mandatory=$true)]
        [string]$TargetAddress,
        [int]$WaitSeconds
    )
    $deadline = (Get-Date).AddSeconds([Math]::Max(0, $WaitSeconds))
    $ownerP2p0 = Get-Qcl041P2pIpv4Status -Serial $OwnerSerial
    $clientP2p0 = Get-Qcl041P2pIpv4Status -Serial $ClientSerial
    $attempts = 1
    while ((Get-Date) -lt $deadline -and -not (
        [bool]$ownerP2p0.ipv4_present -and [bool]$clientP2p0.ipv4_present)) {
        Start-Sleep -Seconds 2
        $attempts++
        $ownerP2p0 = Get-Qcl041P2pIpv4Status -Serial $OwnerSerial
        $clientP2p0 = Get-Qcl041P2pIpv4Status -Serial $ClientSerial
    }
    [ordered]@{
        phase = "active_group"
        wait_seconds = [Math]::Max(0, $WaitSeconds)
        wait_attempts = $attempts
        owner_p2p0_ipv4_present_before_snapshot = [bool]$ownerP2p0.ipv4_present
        client_p2p0_ipv4_present_before_snapshot = [bool]$clientP2p0.ipv4_present
        snapshot = Get-Qcl041ShellRouteSnapshots -Phase "active_group" -TargetAddress $TargetAddress
    }
}

function Install-Qcl041Apk {
    param([Parameter(Mandatory=$true)][string]$Serial)
    if (-not (Test-Path -LiteralPath $Qcl041Apk)) {
        throw "QCL041 APK not found: $Qcl041Apk"
    }
    Invoke-AdbText -Serial $Serial -Arguments @("install", "-r", "-g", $Qcl041Apk) -Name "install qcl041" | Out-Null
}

function Start-Qcl041MatrixService {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Serial,
        [Parameter(Mandatory=$true)]
        [ValidateSet("group_owner", "client")]
        [string]$Role
    )
    $intentArgs = @(
        "shell", "am", "start-foreground-service",
        "-n", "$Qcl041Package/.Qcl041WifiDirectHarnessService",
        "--es", "qcl041.run_id", $RunId,
        "--es", "qcl041.device_serial", $Serial,
        "--es", "qcl041.device_model", "Quest_3S",
        "--es", "qcl041.lease_id", "unleased",
        "--es", "qcl041.lease_resource", "quest:$Serial",
        "--ez", "qcl041.lease_reserved_before_live_steps", "false",
        "--es", "qcl041.peer_class", "quest",
        "--es", "qcl041.q2q_role", $Role,
        "--ez", "qcl041.q2q_preclear_stale_group", $(if ($Role -eq "group_owner") { "true" } else { "false" }),
        "--es", "qcl041.q2q_network_name", $Qcl041Q2qNetworkName,
        "--es", "qcl041.q2q_passphrase", $Qcl041Q2qPassphrase,
        "--es", "qcl041.peer_name_contains", "Quest",
        "--es", "qcl041.host_toolchain_profile", "qcl041_quest_to_quest_app_bound_socket_matrix",
        "--ei", "qcl041.timeout_seconds", $TimeoutSeconds.ToString(),
        "--ei", "qcl041.socket_timeout_seconds", $SocketTimeoutSeconds.ToString(),
        "--ei", "qcl041.hold_after_socket_ms", ([Math]::Max(0, $HoldAfterSocketSeconds) * 1000).ToString(),
        "--ez", "qcl041.q2q_app_bound_socket_matrix_enabled", "true",
        "--ei", "qcl041.q2q_app_bound_socket_matrix_port", $MatrixPort.ToString(),
        "--ei", "qcl041.q2q_app_bound_socket_matrix_delayed_udp_delay_ms", ($effectiveDelayedUdpDelaySeconds * 1000).ToString(),
        "--ei", "qcl041.q2q_app_bound_socket_matrix_tcp_tunnel_stream_seconds", ([Math]::Max(0, $TcpTunnelStreamSeconds)).ToString(),
        "--ei", "qcl041.q2q_app_bound_socket_matrix_tcp_tunnel_stream_bytes_per_direction", ([Math]::Max(0, $TcpTunnelStreamBytesPerDirection)).ToString(),
        "--ez", "qcl041.qcl082_relay_enabled", "false",
        "--ez", "qcl041.qcl082_receive_proxy_enabled", "false",
        "--ez", "qcl041.qcl082_ack_pacing_enabled", "false"
    )
    $output = Invoke-AdbText -Serial $Serial -Arguments $intentArgs -Name "start qcl041 matrix"
    $output | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "$Role-launch.txt")
}

function Read-Qcl041Artifact {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Serial,
        [Parameter(Mandatory=$true)]
        [string]$Path
    )
    $content = & $Adb -s $Serial exec-out run-as $Qcl041Package cat "files/qcl041/$RunId.json" 2>&1 | Out-String
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -or [string]::IsNullOrWhiteSpace($content)) {
        return $null
    }
    try {
        $json = $content | ConvertFrom-Json
        $content | Set-Content -Encoding UTF8 -Path $Path
        return $json
    } catch {
        return $null
    }
}

function Wait-Qcl041Artifact {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Serial,
        [Parameter(Mandatory=$true)]
        [string]$Path,
        [Parameter(Mandatory=$true)]
        [string]$Role,
        [switch]$ControlTcpGateReady
    )
    $deadline = (Get-Date).AddSeconds(
        $TimeoutSeconds +
        $SocketTimeoutSeconds +
        [Math]::Max(0, $HoldAfterSocketSeconds) +
        $effectiveDelayedUdpDelaySeconds +
        [Math]::Max(0, $TcpTunnelStreamSeconds) +
        25)
    $latestArtifact = $null
    while ((Get-Date) -lt $deadline) {
        $artifact = Read-Qcl041Artifact -Serial $Serial -Path $Path
        if ($null -ne $artifact -and $artifact.run_id -eq $RunId) {
            $latestArtifact = $artifact
            $matrix = if ($null -ne $artifact.diagnostics) {
                $artifact.diagnostics.q2q_app_bound_socket_matrix
            } else {
                $null
            }
            if ($null -ne $matrix) {
                if ($ControlTcpGateReady -and (Test-Qcl041ControlTcpGateArtifactReady -Matrix $matrix -Role $Role)) {
                    return $artifact
                }
                if (-not $ControlTcpGateReady -and (Test-Qcl041MatrixArtifactComplete -Matrix $matrix)) {
                    return $artifact
                }
            }
        }
        Start-Sleep -Seconds 2
    }
    return $latestArtifact
}

function Test-Qcl041ControlTcpGateArtifactReady {
    param($Matrix, [string]$Role)
    if ($null -eq $Matrix) {
        return $false
    }
    if ($Role -eq "group_owner") {
        return [bool](
            (Get-LongValue (Get-MatrixValue -Matrix $Matrix -Name "tcp_tunnel_stream_socket_client_to_owner_rx_bytes")) -gt 0 -and
            (Get-LongValue (Get-MatrixValue -Matrix $Matrix -Name "tcp_tunnel_stream_socket_owner_to_client_tx_bytes")) -gt 0 -and
            [bool](Get-MatrixValue -Matrix $Matrix -Name "tcp_tunnel_stream_socket_client_to_owner_crc32_match"))
    }
    if ($Role -eq "client") {
        return [bool](
            (Get-LongValue (Get-MatrixValue -Matrix $Matrix -Name "tcp_tunnel_stream_socket_client_to_owner_tx_bytes")) -gt 0 -and
            (Get-LongValue (Get-MatrixValue -Matrix $Matrix -Name "tcp_tunnel_stream_socket_owner_to_client_rx_bytes")) -gt 0 -and
            [bool](Get-MatrixValue -Matrix $Matrix -Name "tcp_tunnel_stream_socket_owner_to_client_crc32_match"))
    }
    return $false
}

function Get-MatrixValue {
    param($Matrix, [string]$Name)
    if ($null -eq $Matrix -or [string]::IsNullOrWhiteSpace($Name)) {
        return $null
    }
    $property = $Matrix.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Get-LongValue {
    param($Value)
    if ($null -eq $Value) {
        return 0L
    }
    try {
        return [long]$Value
    } catch {
        return 0L
    }
}

function Get-BoolValue {
    param($Value)
    if ($null -eq $Value) {
        return $false
    }
    if ($Value -is [bool]) {
        return [bool]$Value
    }
    $text = [string]$Value
    if ($text.Equals("true", [System.StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    if ($text.Equals("false", [System.StringComparison]::OrdinalIgnoreCase)) {
        return $false
    }
    return [bool]$Value
}

function Test-Qcl041MatrixArtifactComplete {
    param($Matrix)
    if ($null -eq $Matrix) {
        return $false
    }
    $role = [string](Get-MatrixValue -Matrix $Matrix -Name "role")
    if ($role -eq "client_sender") {
        return Get-BoolValue (Get-MatrixValue -Matrix $Matrix -Name "client_sender_completed")
    }
    if ($role -eq "group_owner_receiver") {
        return Get-BoolValue (Get-MatrixValue -Matrix $Matrix -Name "group_owner_receiver_completed")
    }
    return $true
}

function Get-Qcl041AppNetworkVisibility {
    param(
        $OwnerArtifact,
        $ClientArtifact,
        $ActiveShellRoutes
    )
    $ownerMatrix = if ($null -ne $OwnerArtifact -and $null -ne $OwnerArtifact.diagnostics) {
        $OwnerArtifact.diagnostics.q2q_app_bound_socket_matrix
    } else {
        $null
    }
    $clientMatrix = if ($null -ne $ClientArtifact -and $null -ne $ClientArtifact.diagnostics) {
        $ClientArtifact.diagnostics.q2q_app_bound_socket_matrix
    } else {
        $null
    }
    $ownerVisible = Get-BoolValue (Get-MatrixValue -Matrix $ownerMatrix -Name "initial_network_available")
    $clientVisible = Get-BoolValue (Get-MatrixValue -Matrix $clientMatrix -Name "initial_network_available")
    $ownerRequestVisible = Get-BoolValue (Get-MatrixValue -Matrix $ownerMatrix -Name "initial_wifi_p2p_request_network_found")
    $clientRequestVisible = Get-BoolValue (Get-MatrixValue -Matrix $clientMatrix -Name "initial_wifi_p2p_request_network_found")
    $clientAnyAppVisible = [bool]($clientVisible -or $clientRequestVisible)
    $clientShellDefaultUsesP2p0 = $false
    $clientShellSourceUsesP2p0 = $false
    if ($null -ne $ActiveShellRoutes -and $null -ne $ActiveShellRoutes.snapshot) {
        $clientShellDefaultUsesP2p0 = [bool]$ActiveShellRoutes.snapshot.client_route_get_uses_p2p0
        $clientShellSourceUsesP2p0 = [bool]$ActiveShellRoutes.snapshot.client_route_get_from_p2p_source_uses_p2p0
    }
    $decision = if ($clientAnyAppVisible -and -not $clientShellSourceUsesP2p0) {
        "qcl041_sees_p2p_network_shell_route_not_p2p0"
    } elseif (-not $clientAnyAppVisible) {
        "qcl041_client_p2p_network_not_visible"
    } elseif ($clientShellSourceUsesP2p0) {
        "qcl041_and_shell_source_route_use_p2p0"
    } else {
        "inconclusive"
    }
    [ordered]@{
        owner_qcl041_p2p_network_visible = $ownerVisible
        owner_initial_network = Get-MatrixValue -Matrix $ownerMatrix -Name "initial_network"
        owner_initial_network_handle = Get-MatrixValue -Matrix $ownerMatrix -Name "initial_network_handle"
        owner_initial_link_properties_found = Get-BoolValue (Get-MatrixValue -Matrix $ownerMatrix -Name "initial_link_properties_found")
        owner_initial_interface = Get-MatrixValue -Matrix $ownerMatrix -Name "initial_interface"
        owner_initial_link_addresses = Get-MatrixValue -Matrix $ownerMatrix -Name "initial_link_addresses"
        owner_initial_routes = Get-MatrixValue -Matrix $ownerMatrix -Name "initial_routes"
        owner_initial_capabilities = Get-MatrixValue -Matrix $ownerMatrix -Name "initial_capabilities"
        owner_wifi_p2p_network_request_visible = $ownerRequestVisible
        owner_wifi_p2p_network_request_callback_observed = Get-BoolValue (Get-MatrixValue -Matrix $ownerMatrix -Name "initial_wifi_p2p_request_callback_observed")
        owner_wifi_p2p_network_request_first_callback = Get-MatrixValue -Matrix $ownerMatrix -Name "initial_wifi_p2p_request_first_callback"
        owner_wifi_p2p_network_request_network = Get-MatrixValue -Matrix $ownerMatrix -Name "initial_wifi_p2p_request_network"
        owner_wifi_p2p_network_request_network_handle = Get-MatrixValue -Matrix $ownerMatrix -Name "initial_wifi_p2p_request_network_handle"
        owner_wifi_p2p_network_request_matches_selected_network = Get-BoolValue (Get-MatrixValue -Matrix $ownerMatrix -Name "initial_wifi_p2p_request_matches_selected_network")
        owner_wifi_p2p_network_request_link_properties_found = Get-BoolValue (Get-MatrixValue -Matrix $ownerMatrix -Name "initial_wifi_p2p_request_link_properties_found")
        owner_wifi_p2p_network_request_interface = Get-MatrixValue -Matrix $ownerMatrix -Name "initial_wifi_p2p_request_interface"
        owner_wifi_p2p_network_request_link_addresses = Get-MatrixValue -Matrix $ownerMatrix -Name "initial_wifi_p2p_request_link_addresses"
        owner_wifi_p2p_network_request_routes = Get-MatrixValue -Matrix $ownerMatrix -Name "initial_wifi_p2p_request_routes"
        owner_wifi_p2p_network_request_capabilities = Get-MatrixValue -Matrix $ownerMatrix -Name "initial_wifi_p2p_request_capabilities"
        client_qcl041_p2p_network_visible = $clientVisible
        client_initial_network = Get-MatrixValue -Matrix $clientMatrix -Name "initial_network"
        client_initial_network_handle = Get-MatrixValue -Matrix $clientMatrix -Name "initial_network_handle"
        client_initial_link_properties_found = Get-BoolValue (Get-MatrixValue -Matrix $clientMatrix -Name "initial_link_properties_found")
        client_initial_interface = Get-MatrixValue -Matrix $clientMatrix -Name "initial_interface"
        client_initial_link_addresses = Get-MatrixValue -Matrix $clientMatrix -Name "initial_link_addresses"
        client_initial_routes = Get-MatrixValue -Matrix $clientMatrix -Name "initial_routes"
        client_initial_capabilities = Get-MatrixValue -Matrix $clientMatrix -Name "initial_capabilities"
        client_wifi_p2p_network_request_visible = $clientRequestVisible
        client_wifi_p2p_network_request_callback_observed = Get-BoolValue (Get-MatrixValue -Matrix $clientMatrix -Name "initial_wifi_p2p_request_callback_observed")
        client_wifi_p2p_network_request_first_callback = Get-MatrixValue -Matrix $clientMatrix -Name "initial_wifi_p2p_request_first_callback"
        client_wifi_p2p_network_request_network = Get-MatrixValue -Matrix $clientMatrix -Name "initial_wifi_p2p_request_network"
        client_wifi_p2p_network_request_network_handle = Get-MatrixValue -Matrix $clientMatrix -Name "initial_wifi_p2p_request_network_handle"
        client_wifi_p2p_network_request_matches_selected_network = Get-BoolValue (Get-MatrixValue -Matrix $clientMatrix -Name "initial_wifi_p2p_request_matches_selected_network")
        client_wifi_p2p_network_request_link_properties_found = Get-BoolValue (Get-MatrixValue -Matrix $clientMatrix -Name "initial_wifi_p2p_request_link_properties_found")
        client_wifi_p2p_network_request_interface = Get-MatrixValue -Matrix $clientMatrix -Name "initial_wifi_p2p_request_interface"
        client_wifi_p2p_network_request_link_addresses = Get-MatrixValue -Matrix $clientMatrix -Name "initial_wifi_p2p_request_link_addresses"
        client_wifi_p2p_network_request_routes = Get-MatrixValue -Matrix $clientMatrix -Name "initial_wifi_p2p_request_routes"
        client_wifi_p2p_network_request_capabilities = Get-MatrixValue -Matrix $clientMatrix -Name "initial_wifi_p2p_request_capabilities"
        shell_client_route_get_uses_p2p0 = $clientShellDefaultUsesP2p0
        shell_client_route_get_from_p2p_source_uses_p2p0 = $clientShellSourceUsesP2p0
        decision = $decision
    }
}

function Summarize-Qcl041Matrix {
    param(
        $OwnerArtifact,
        $ClientArtifact,
        [long]$TcpTunnelStreamBytesPerDirection = 0L
    )
    $tcpTunnelStreamMinimumBytes = if ($TcpTunnelStreamBytesPerDirection -gt 0L) {
        [long]$TcpTunnelStreamBytesPerDirection
    } else {
        1L
    }
    $ownerMatrix = if ($null -ne $OwnerArtifact -and $null -ne $OwnerArtifact.diagnostics) {
        $OwnerArtifact.diagnostics.q2q_app_bound_socket_matrix
    } else {
        $null
    }
    $clientMatrix = if ($null -ne $ClientArtifact -and $null -ne $ClientArtifact.diagnostics) {
        $ClientArtifact.diagnostics.q2q_app_bound_socket_matrix
    } else {
        $null
    }
    $udpModes = @(
        "udp_wildcard_unbound",
        "udp_source_bound",
        "udp_network_bound",
        "udp_source_and_network_bound",
        "udp_native_fd_network_bound",
        "udp_process_bound",
        "early_bound_delayed_udp_network_bound",
        "early_bound_delayed_udp_source_and_network_bound",
        "delayed_udp_network_bound",
        "delayed_udp_source_and_network_bound",
        "delayed_udp_native_fd_network_bound",
        "delayed_udp_process_bound"
    )
    $tcpModes = @(
        "tcp_tunnel_control_socket",
        "tcp_tunnel_stream_socket",
        "tcp_source_bound",
        "tcp_network_bind_socket",
        "tcp_network_factory",
        "tcp_native_fd_network_bound",
        "tcp_process_bound"
    )
    $udpRows = @($udpModes | ForEach-Object {
        $mode = $_
        [ordered]@{
            mode = $mode
            receiver_packets = Get-LongValue (Get-MatrixValue -Matrix $ownerMatrix -Name "${mode}_rx_packets")
            receiver_last_source = Get-MatrixValue -Matrix $ownerMatrix -Name "${mode}_last_source"
            receiver_last_source_port = Get-MatrixValue -Matrix $ownerMatrix -Name "${mode}_last_source_port"
            sender_tx_packets = Get-LongValue (Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_tx_packets")
            sender_error = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_send_error"
            sender_prepare_error = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_prepare_error"
            sender_skipped = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_skipped"
            native_status = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_native_status"
            native_setsocknetwork_result = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_setsocknetwork_result"
            native_setsocknetwork_errno = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_setsocknetwork_errno"
            process_bound = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_process_bound_to_wifi_direct_network"
            process_bind_restored = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_process_bind_restored"
        }
    })
    $tcpRows = @($tcpModes | ForEach-Object {
        $mode = $_
        [ordered]@{
            mode = $mode
            receiver_accepts = Get-LongValue (Get-MatrixValue -Matrix $ownerMatrix -Name "${mode}_accepts")
            receiver_accepted_source = Get-MatrixValue -Matrix $ownerMatrix -Name "${mode}_accepted_source"
            connected = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_connected"
            connect_error = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_connect_error"
            receiver_client_to_owner_bytes = Get-LongValue (Get-MatrixValue -Matrix $ownerMatrix -Name "${mode}_client_to_owner_rx_bytes")
            receiver_client_to_owner_crc32_match = Get-MatrixValue -Matrix $ownerMatrix -Name "${mode}_client_to_owner_crc32_match"
            receiver_owner_to_client_tx_bytes = Get-LongValue (Get-MatrixValue -Matrix $ownerMatrix -Name "${mode}_owner_to_client_tx_bytes")
            sender_client_to_owner_tx_bytes = Get-LongValue (Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_client_to_owner_tx_bytes")
            sender_owner_to_client_rx_bytes = Get-LongValue (Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_owner_to_client_rx_bytes")
            sender_owner_to_client_crc32_match = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_owner_to_client_crc32_match"
            bidirectional_client_observed = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_bidirectional_client_observed"
            native_status = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_native_status"
            native_setsocknetwork_result = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_setsocknetwork_result"
            native_setsocknetwork_errno = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_setsocknetwork_errno"
            process_bound = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_process_bound_to_wifi_direct_network"
            process_bind_restored = Get-MatrixValue -Matrix $clientMatrix -Name "${mode}_process_bind_restored"
        }
    })
    $udpPassRows = @($udpRows | Where-Object { $_.receiver_packets -gt 0 })
    $tcpPassRows = @($tcpRows | Where-Object { $_.receiver_accepts -gt 0 })
    $tcpTunnelRows = @($tcpRows | Where-Object {
        $_.mode -eq "tcp_tunnel_control_socket" -and
        $_.receiver_client_to_owner_bytes -gt 0 -and
        $_.sender_owner_to_client_rx_bytes -gt 0 -and
        [bool]$_.receiver_client_to_owner_crc32_match -and
        [bool]$_.sender_owner_to_client_crc32_match
    })
    $tcpTunnelStreamRows = @($tcpRows | Where-Object {
        $_.mode -eq "tcp_tunnel_stream_socket" -and
        $_.receiver_client_to_owner_bytes -ge $tcpTunnelStreamMinimumBytes -and
        $_.sender_owner_to_client_rx_bytes -ge $tcpTunnelStreamMinimumBytes -and
        [bool]$_.receiver_client_to_owner_crc32_match -and
        [bool]$_.sender_owner_to_client_crc32_match
    })
    $clientToOwnerAppBoundUdpSocketRows = @($udpPassRows | Where-Object {
        $_.mode -in @("udp_network_bound", "udp_source_and_network_bound")
    })
    $clientToOwnerWifiDirectUdpRows = @($udpPassRows | Where-Object {
        $_.mode -in @(
            "udp_network_bound",
            "udp_source_and_network_bound",
            "udp_native_fd_network_bound",
            "udp_process_bound",
            "early_bound_delayed_udp_network_bound",
            "early_bound_delayed_udp_source_and_network_bound",
            "delayed_udp_network_bound",
            "delayed_udp_source_and_network_bound",
            "delayed_udp_native_fd_network_bound",
            "delayed_udp_process_bound"
        )
    })
    [ordered]@{
        owner_matrix_present = [bool]($null -ne $ownerMatrix)
        client_matrix_present = [bool]($null -ne $clientMatrix)
        owner_matrix_complete = Test-Qcl041MatrixArtifactComplete -Matrix $ownerMatrix
        client_matrix_complete = Test-Qcl041MatrixArtifactComplete -Matrix $clientMatrix
        owner_matrix_last_checkpoint = Get-MatrixValue -Matrix $ownerMatrix -Name "last_checkpoint"
        client_matrix_last_checkpoint = Get-MatrixValue -Matrix $clientMatrix -Name "last_checkpoint"
        owner_udp_rx_total_packets = Get-LongValue (Get-MatrixValue -Matrix $ownerMatrix -Name "udp_rx_total_packets")
        owner_udp_rx_total_bytes = Get-LongValue (Get-MatrixValue -Matrix $ownerMatrix -Name "udp_rx_total_bytes")
        owner_tcp_accept_total = Get-LongValue (Get-MatrixValue -Matrix $ownerMatrix -Name "tcp_accept_total")
        tcp_tunnel_stream_configured_bytes_per_direction = [long]$TcpTunnelStreamBytesPerDirection
        tcp_tunnel_stream_required_bytes_per_direction = $tcpTunnelStreamMinimumBytes
        udp_rows = $udpRows
        tcp_rows = $tcpRows
        receiver_observed_udp_modes = @($udpPassRows | ForEach-Object { $_.mode })
        receiver_observed_tcp_modes = @($tcpPassRows | ForEach-Object { $_.mode })
        receiver_observed_bytes = [bool]($udpPassRows.Count -gt 0 -or $tcpPassRows.Count -gt 0)
        client_to_owner_udp_evidence_scope = "client_sender_to_group_owner_receiver"
        client_to_owner_wifi_direct_udp_matrix_mode_pass = [bool]($clientToOwnerWifiDirectUdpRows.Count -gt 0)
        client_to_owner_wifi_direct_udp_receiver_observed_modes = @($clientToOwnerWifiDirectUdpRows | ForEach-Object { $_.mode })
        client_to_owner_app_bound_udp_socket_pass = [bool]($clientToOwnerAppBoundUdpSocketRows.Count -gt 0)
        client_to_owner_app_bound_udp_receiver_observed_modes = @($clientToOwnerAppBoundUdpSocketRows | ForEach-Object { $_.mode })
        same_group_udp_duplex_media_proven_by_matrix = $false
        same_group_udp_duplex_media_proof_required = "qcl100_same_epoch_final_window_media_and_renderer_freshness"
        app_bound_udp_socket_pass = [bool](@($udpPassRows | Where-Object { $_.mode -in @("udp_network_bound", "udp_source_and_network_bound") }).Count -gt 0)
        native_udp_fd_pass = [bool](@($udpPassRows | Where-Object { $_.mode -eq "udp_native_fd_network_bound" }).Count -gt 0)
        native_tcp_fd_pass = [bool](@($tcpPassRows | Where-Object { $_.mode -eq "tcp_native_fd_network_bound" }).Count -gt 0)
        process_bound_udp_pass = [bool](@($udpPassRows | Where-Object { $_.mode -eq "udp_process_bound" }).Count -gt 0)
        process_bound_tcp_pass = [bool](@($tcpPassRows | Where-Object { $_.mode -eq "tcp_process_bound" }).Count -gt 0)
        delayed_app_bound_udp_socket_pass = [bool](@($udpPassRows | Where-Object { $_.mode -in @("delayed_udp_network_bound", "delayed_udp_source_and_network_bound") }).Count -gt 0)
        delayed_native_udp_fd_pass = [bool](@($udpPassRows | Where-Object { $_.mode -eq "delayed_udp_native_fd_network_bound" }).Count -gt 0)
        delayed_process_bound_udp_pass = [bool](@($udpPassRows | Where-Object { $_.mode -eq "delayed_udp_process_bound" }).Count -gt 0)
        early_bound_delayed_app_bound_udp_socket_pass = [bool](@($udpPassRows | Where-Object { $_.mode -in @("early_bound_delayed_udp_network_bound", "early_bound_delayed_udp_source_and_network_bound") }).Count -gt 0)
        tcp_tunnel_bidirectional_bytes_pass = [bool]($tcpTunnelRows.Count -gt 0)
        tcp_tunnel_stream_bidirectional_bytes_pass = [bool]($tcpTunnelStreamRows.Count -gt 0)
    }
}

$preflight = [ordered]@{
    owner_wifi = Get-Qcl041WifiStatus -Serial $OwnerSerial
    client_wifi = Get-Qcl041WifiStatus -Serial $ClientSerial
    owner_p2p0 = Get-Qcl041P2pIpv4Status -Serial $OwnerSerial
    client_p2p0 = Get-Qcl041P2pIpv4Status -Serial $ClientSerial
}
$preflight["shell_routes"] = Get-Qcl041ShellRouteSnapshots -Phase "preflight" -TargetAddress $RouteProbeTarget
$preflight["candidate_wifi_direct_shell_routes"] = Get-Qcl041CandidateWifiDirectRoutes -Phase "preflight"
$preflight["infrastructure_wifi_disconnected"] = [bool](
    -not [bool]$preflight.owner_wifi.infrastructure_connected -and
    -not [bool]$preflight.client_wifi.infrastructure_connected)
$preflight["p2p0_ipv4_cleared"] = [bool](
    -not [bool]$preflight.owner_p2p0.ipv4_present -and
    -not [bool]$preflight.client_p2p0.ipv4_present)
Add-Qcl041CandidateWifiDirectRouteCounts -Preflight $preflight
$preflightPath = Join-Path $OutDir "airgap-preflight.json"
Write-Qcl041JsonFile -Value $preflight -Path $preflightPath
$preflightRoutesPath = Join-Path $OutDir "preflight-shell-routes.json"
Write-Qcl041JsonFile -Value $preflight.shell_routes -Path $preflightRoutesPath
$preflightCandidateRoutesPath = Join-Path $OutDir "preflight-candidate-wifi-direct-routes.json"
Write-Qcl041JsonFile -Value $preflight.candidate_wifi_direct_shell_routes -Path $preflightCandidateRoutesPath

if ($RequireInfrastructureWifiDisconnected -and -not [bool]$preflight.infrastructure_wifi_disconnected) {
    $summary = [ordered]@{
        schema = "rusty.quest.qcl041_q2q_app_bound_socket_matrix_run.v1"
        run_id = $RunId
        status = "blocked_preflight"
        blocked_reason = "infrastructure_wifi_connected"
        matrix_focus = $matrixFocus
        qcl100_control_tcp_gate = [bool]$Qcl100ControlTcpGate
        delayed_udp_required = $delayedUdpRequired
        whole_matrix_completion_required = $wholeMatrixCompletionRequired
        requested_delayed_udp_delay_seconds = $requestedDelayedUdpDelaySeconds
        delayed_udp_delay_seconds = $effectiveDelayedUdpDelaySeconds
        tcp_tunnel_stream_seconds = [Math]::Max(0, $TcpTunnelStreamSeconds)
        tcp_tunnel_stream_bytes_per_direction = [Math]::Max(0, $TcpTunnelStreamBytesPerDirection)
        route_probe_target = $RouteProbeTarget
        active_route_probe_wait_seconds = [Math]::Max(0, $ActiveRouteProbeWaitSeconds)
        preflight = $preflight
        preflight_shell_routes_artifact = $preflightRoutesPath
        preflight_candidate_wifi_direct_routes_artifact = $preflightCandidateRoutesPath
        require_infrastructure_wifi_disconnected = [bool]$RequireInfrastructureWifiDisconnected
        require_p2p0_ipv4_cleared = [bool]$RequireP2p0Ipv4Cleared
        require_candidate_wifi_direct_routes_clear = [bool]$RequireCandidateWifiDirectRoutesClear
        requested_require_tcp_tunnel_stream_pass = [bool]$RequireTcpTunnelStreamPass
        require_tcp_tunnel_stream_pass = $effectiveRequireTcpTunnelStreamPass
        launched = $false
        evidence_dir = $OutDir
    }
    $summaryPath = Join-Path $OutDir "summary.json"
    Write-Qcl041JsonFile -Value $summary -Path $summaryPath
    Get-Content -Raw $summaryPath
    exit 2
}

if ($RequireP2p0Ipv4Cleared -and -not [bool]$preflight.p2p0_ipv4_cleared) {
    $summary = [ordered]@{
        schema = "rusty.quest.qcl041_q2q_app_bound_socket_matrix_run.v1"
        run_id = $RunId
        status = "blocked_preflight"
        blocked_reason = "p2p0_ipv4_present"
        matrix_focus = $matrixFocus
        qcl100_control_tcp_gate = [bool]$Qcl100ControlTcpGate
        delayed_udp_required = $delayedUdpRequired
        whole_matrix_completion_required = $wholeMatrixCompletionRequired
        requested_delayed_udp_delay_seconds = $requestedDelayedUdpDelaySeconds
        delayed_udp_delay_seconds = $effectiveDelayedUdpDelaySeconds
        tcp_tunnel_stream_seconds = [Math]::Max(0, $TcpTunnelStreamSeconds)
        tcp_tunnel_stream_bytes_per_direction = [Math]::Max(0, $TcpTunnelStreamBytesPerDirection)
        route_probe_target = $RouteProbeTarget
        active_route_probe_wait_seconds = [Math]::Max(0, $ActiveRouteProbeWaitSeconds)
        preflight = $preflight
        preflight_shell_routes_artifact = $preflightRoutesPath
        preflight_candidate_wifi_direct_routes_artifact = $preflightCandidateRoutesPath
        require_infrastructure_wifi_disconnected = [bool]$RequireInfrastructureWifiDisconnected
        require_p2p0_ipv4_cleared = [bool]$RequireP2p0Ipv4Cleared
        require_candidate_wifi_direct_routes_clear = [bool]$RequireCandidateWifiDirectRoutesClear
        requested_require_tcp_tunnel_stream_pass = [bool]$RequireTcpTunnelStreamPass
        require_tcp_tunnel_stream_pass = $effectiveRequireTcpTunnelStreamPass
        launched = $false
        evidence_dir = $OutDir
    }
    $summaryPath = Join-Path $OutDir "summary.json"
    Write-Qcl041JsonFile -Value $summary -Path $summaryPath
    Get-Content -Raw $summaryPath
    exit 2
}

if ($RequireCandidateWifiDirectRoutesClear -and -not [bool]$preflight.candidate_wifi_direct_prelaunch_routes_clear) {
    $summary = [ordered]@{
        schema = "rusty.quest.qcl041_q2q_app_bound_socket_matrix_run.v1"
        run_id = $RunId
        status = "blocked_preflight"
        blocked_stage = "wifi_direct_candidate_route_preflight"
        blocked_reason = "candidate_wifi_direct_routes_not_clear"
        matrix_focus = $matrixFocus
        qcl100_control_tcp_gate = [bool]$Qcl100ControlTcpGate
        delayed_udp_required = $delayedUdpRequired
        whole_matrix_completion_required = $wholeMatrixCompletionRequired
        requested_delayed_udp_delay_seconds = $requestedDelayedUdpDelaySeconds
        delayed_udp_delay_seconds = $effectiveDelayedUdpDelaySeconds
        tcp_tunnel_stream_seconds = [Math]::Max(0, $TcpTunnelStreamSeconds)
        tcp_tunnel_stream_bytes_per_direction = [Math]::Max(0, $TcpTunnelStreamBytesPerDirection)
        route_probe_target = $RouteProbeTarget
        owner_wifi_direct_address = $OwnerWifiDirectAddress
        client_wifi_direct_address = $ClientWifiDirectAddress
        active_route_probe_wait_seconds = [Math]::Max(0, $ActiveRouteProbeWaitSeconds)
        preflight = $preflight
        preflight_shell_routes_artifact = $preflightRoutesPath
        preflight_candidate_wifi_direct_routes_artifact = $preflightCandidateRoutesPath
        require_infrastructure_wifi_disconnected = [bool]$RequireInfrastructureWifiDisconnected
        require_p2p0_ipv4_cleared = [bool]$RequireP2p0Ipv4Cleared
        require_candidate_wifi_direct_routes_clear = $true
        requested_require_tcp_tunnel_stream_pass = [bool]$RequireTcpTunnelStreamPass
        require_tcp_tunnel_stream_pass = $effectiveRequireTcpTunnelStreamPass
        launched = $false
        evidence_dir = $OutDir
    }
    $summaryPath = Join-Path $OutDir "summary.json"
    Write-Qcl041JsonFile -Value $summary -Path $summaryPath
    Get-Content -Raw $summaryPath
    exit 2
}

if ($PreflightOnly) {
    $summary = [ordered]@{
        schema = "rusty.quest.qcl041_q2q_app_bound_socket_matrix_run.v1"
        run_id = $RunId
        status = "preflight_only"
        matrix_focus = $matrixFocus
        qcl100_control_tcp_gate = [bool]$Qcl100ControlTcpGate
        delayed_udp_required = $delayedUdpRequired
        whole_matrix_completion_required = $wholeMatrixCompletionRequired
        requested_delayed_udp_delay_seconds = $requestedDelayedUdpDelaySeconds
        delayed_udp_delay_seconds = $effectiveDelayedUdpDelaySeconds
        tcp_tunnel_stream_seconds = [Math]::Max(0, $TcpTunnelStreamSeconds)
        tcp_tunnel_stream_bytes_per_direction = [Math]::Max(0, $TcpTunnelStreamBytesPerDirection)
        route_probe_target = $RouteProbeTarget
        owner_wifi_direct_address = $OwnerWifiDirectAddress
        client_wifi_direct_address = $ClientWifiDirectAddress
        active_route_probe_wait_seconds = [Math]::Max(0, $ActiveRouteProbeWaitSeconds)
        preflight = $preflight
        preflight_shell_routes_artifact = $preflightRoutesPath
        preflight_candidate_wifi_direct_routes_artifact = $preflightCandidateRoutesPath
        require_infrastructure_wifi_disconnected = [bool]$RequireInfrastructureWifiDisconnected
        require_p2p0_ipv4_cleared = [bool]$RequireP2p0Ipv4Cleared
        require_candidate_wifi_direct_routes_clear = [bool]$RequireCandidateWifiDirectRoutesClear
        requested_require_tcp_tunnel_stream_pass = [bool]$RequireTcpTunnelStreamPass
        require_tcp_tunnel_stream_pass = $effectiveRequireTcpTunnelStreamPass
        launched = $false
        evidence_dir = $OutDir
    }
    $summaryPath = Join-Path $OutDir "summary.json"
    Write-Qcl041JsonFile -Value $summary -Path $summaryPath
    Get-Content -Raw $summaryPath
    exit 0
}

if (-not $SkipInstall) {
    Install-Qcl041Apk -Serial $OwnerSerial
    Install-Qcl041Apk -Serial $ClientSerial
}

Start-Qcl041MatrixService -Serial $OwnerSerial -Role "group_owner"
Start-Sleep -Seconds ([Math]::Max(1, $LaunchDelaySeconds))
Start-Qcl041MatrixService -Serial $ClientSerial -Role "client"

$activeGroupShellRoutes = Wait-Qcl041ActiveShellRouteSnapshot `
    -TargetAddress $RouteProbeTarget `
    -WaitSeconds $ActiveRouteProbeWaitSeconds
$activeGroupShellRoutesPath = Join-Path $OutDir "active-group-shell-routes.json"
Write-Qcl041JsonFile -Value $activeGroupShellRoutes -Path $activeGroupShellRoutesPath

$ownerArtifactPath = Join-Path $OutDir "owner-qcl041.json"
$clientArtifactPath = Join-Path $OutDir "client-qcl041.json"
$ownerArtifact = Wait-Qcl041Artifact -Serial $OwnerSerial -Path $ownerArtifactPath -Role "group_owner" -ControlTcpGateReady:$Qcl100ControlTcpGate
$clientArtifact = Wait-Qcl041Artifact -Serial $ClientSerial -Path $clientArtifactPath -Role "client" -ControlTcpGateReady:$Qcl100ControlTcpGate
$matrix = Summarize-Qcl041Matrix `
    -OwnerArtifact $ownerArtifact `
    -ClientArtifact $clientArtifact `
    -TcpTunnelStreamBytesPerDirection ([Math]::Max(0, $TcpTunnelStreamBytesPerDirection))
$appNetworkVisibility = Get-Qcl041AppNetworkVisibility `
    -OwnerArtifact $ownerArtifact `
    -ClientArtifact $clientArtifact `
    -ActiveShellRoutes $activeGroupShellRoutes
$appNetworkVisibilityPath = Join-Path $OutDir "app-network-visibility-summary.json"
Write-Qcl041JsonFile -Value $appNetworkVisibility -Path $appNetworkVisibilityPath
$postRunNetwork = [ordered]@{
    owner_p2p0 = Get-Qcl041P2pIpv4Status -Serial $OwnerSerial
    client_p2p0 = Get-Qcl041P2pIpv4Status -Serial $ClientSerial
}
$postRunNetwork["p2p0_ipv4_cleared"] = [bool](
    -not [bool]$postRunNetwork.owner_p2p0.ipv4_present -and
    -not [bool]$postRunNetwork.client_p2p0.ipv4_present)
$postRunShellRoutes = Get-Qcl041ShellRouteSnapshots -Phase "post_run" -TargetAddress $RouteProbeTarget
$postRunShellRoutesPath = Join-Path $OutDir "post-run-shell-routes.json"
Write-Qcl041JsonFile -Value $postRunShellRoutes -Path $postRunShellRoutesPath

$matrixBlockedReason = if (-not [bool]$matrix.owner_matrix_present) {
    "owner_matrix_missing"
} elseif (-not [bool]$matrix.client_matrix_present) {
    "client_matrix_missing"
} elseif ($wholeMatrixCompletionRequired -and -not [bool]$matrix.owner_matrix_complete) {
    "owner_matrix_incomplete"
} elseif ($wholeMatrixCompletionRequired -and -not [bool]$matrix.client_matrix_complete) {
    "client_matrix_incomplete"
} else {
    ""
}
$summaryStatus = if (-not [string]::IsNullOrWhiteSpace($matrixBlockedReason)) {
    "blocked"
} elseif ($effectiveRequireTcpTunnelStreamPass -and -not [bool]$matrix.tcp_tunnel_stream_bidirectional_bytes_pass) {
    "blocked"
} elseif ([bool]$matrix.receiver_observed_bytes) {
    "pass"
} else {
    "blocked"
}
$blockedReason = if (-not [string]::IsNullOrWhiteSpace($matrixBlockedReason)) {
    $matrixBlockedReason
} elseif ($effectiveRequireTcpTunnelStreamPass -and -not [bool]$matrix.tcp_tunnel_stream_bidirectional_bytes_pass) {
    "tcp_tunnel_stream_not_bidirectional"
} elseif (-not [bool]$matrix.receiver_observed_bytes) {
    "receiver_observed_bytes_absent"
} else {
    ""
}

$summary = [ordered]@{
    schema = "rusty.quest.qcl041_q2q_app_bound_socket_matrix_run.v1"
    run_id = $RunId
    status = $summaryStatus
    blocked_reason = $blockedReason
    owner_serial = $OwnerSerial
    client_serial = $ClientSerial
    qcl041_q2q_network_name = $Qcl041Q2qNetworkName
    matrix_port = $MatrixPort
    matrix_focus = $matrixFocus
    qcl100_control_tcp_gate = [bool]$Qcl100ControlTcpGate
    delayed_udp_required = $delayedUdpRequired
    whole_matrix_completion_required = $wholeMatrixCompletionRequired
    requested_delayed_udp_delay_seconds = $requestedDelayedUdpDelaySeconds
    delayed_udp_delay_seconds = $effectiveDelayedUdpDelaySeconds
    tcp_tunnel_stream_seconds = [Math]::Max(0, $TcpTunnelStreamSeconds)
    tcp_tunnel_stream_bytes_per_direction = [Math]::Max(0, $TcpTunnelStreamBytesPerDirection)
    route_probe_target = $RouteProbeTarget
    owner_wifi_direct_address = $OwnerWifiDirectAddress
    client_wifi_direct_address = $ClientWifiDirectAddress
    active_route_probe_wait_seconds = [Math]::Max(0, $ActiveRouteProbeWaitSeconds)
    require_infrastructure_wifi_disconnected = [bool]$RequireInfrastructureWifiDisconnected
    require_p2p0_ipv4_cleared = [bool]$RequireP2p0Ipv4Cleared
    require_candidate_wifi_direct_routes_clear = [bool]$RequireCandidateWifiDirectRoutesClear
    requested_require_tcp_tunnel_stream_pass = [bool]$RequireTcpTunnelStreamPass
    require_tcp_tunnel_stream_pass = $effectiveRequireTcpTunnelStreamPass
    preflight = $preflight
    preflight_shell_routes_artifact = $preflightRoutesPath
    preflight_candidate_wifi_direct_routes_artifact = $preflightCandidateRoutesPath
    launched = $true
    skip_install = [bool]$SkipInstall
    owner_artifact_present = [bool]($null -ne $ownerArtifact)
    client_artifact_present = [bool]($null -ne $clientArtifact)
    matrix = $matrix
    active_group_shell_routes = $activeGroupShellRoutes
    active_group_shell_routes_artifact = $activeGroupShellRoutesPath
    app_network_visibility = $appNetworkVisibility
    app_network_visibility_artifact = $appNetworkVisibilityPath
    post_run_network = $postRunNetwork
    post_run_shell_routes = $postRunShellRoutes
    post_run_shell_routes_artifact = $postRunShellRoutesPath
    evidence_dir = $OutDir
}
$summaryPath = Join-Path $OutDir "summary.json"
Write-Qcl041JsonFile -Value $summary -Path $summaryPath
Get-Content -Raw $summaryPath

if ($summary.status -ne "pass") {
    exit 2
}
