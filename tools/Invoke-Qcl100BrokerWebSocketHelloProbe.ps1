param(
    [string]$OwnerSerial = "340YC10G7T0JBW",
    [string]$ClientSerial = "3487C10H3M017Q",
    [string]$OwnerLeaseId = "",
    [string]$ClientLeaseId = "",
    [string]$RunId = "",
    [string]$OutDir = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [string]$BrokerPackage = "io.github.mesmerprism.rustymanifold.broker",
    [string]$BrokerActivity = "io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity",
    [int]$BrokerPort = 8765,
    [int]$OwnerBrokerLocalPort = 18765,
    [int]$ClientBrokerLocalPort = 18766,
    [string]$WebSocketPath = "/manifold/v1/events",
    [int]$WaitSeconds = 10,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl100-broker-websocket-hello-" + (Get-Date -Format "yyyyMMddTHHmmssZ").ToString()
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path "S:\Work\repos\active\rusty-quest\target" $RunId
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$MediaDir = Join-Path $OutDir "media"
New-Item -ItemType Directory -Force -Path $MediaDir | Out-Null

$helperRoot = Join-Path $PSScriptRoot "qcl100_native_projection"
. (Join-Path $helperRoot "Common.ps1")

function Invoke-Qcl100BrokerHelloAdb {
    param(
        [string]$Serial,
        [string[]]$Arguments,
        [string]$Path,
        [bool]$Required = $true
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $previousNativeCommandUseErrorActionPreference = $PSNativeCommandUseErrorActionPreference
    $ErrorActionPreference = "Continue"
    $PSNativeCommandUseErrorActionPreference = $false
    try {
        $output = & $Adb -s $Serial @Arguments 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
        $PSNativeCommandUseErrorActionPreference = $previousNativeCommandUseErrorActionPreference
    }
    if (-not [string]::IsNullOrWhiteSpace($Path)) {
        $output | Set-Content -Encoding UTF8 -Path $Path
    }
    $result = [ordered]@{
        command = (@($Adb, "-s", $Serial) + $Arguments) -join " "
        exit_code = $exitCode
        output_path = $Path
        output = $output.Trim()
        required = $Required
        status = if ($exitCode -eq 0) { "pass" } elseif ($Required) { "fail" } else { "warn" }
    }
    if ($Required -and $exitCode -ne 0) {
        throw "ADB command failed for $Serial with exit code ${exitCode}: $($Arguments -join ' ') $output"
    }
    return $result
}

function Wait-Qcl100BrokerHelloPid {
    param(
        [string]$Serial,
        [string]$Label,
        [int]$TimeoutSeconds
    )

    $started = Get-Date
    $attempts = @()
    while (((Get-Date) - $started).TotalSeconds -le $TimeoutSeconds) {
        $path = Join-Path $MediaDir "$Label-broker-pidof-$($attempts.Count + 1).txt"
        $result = Invoke-Qcl100BrokerHelloAdb -Serial $Serial -Arguments @("shell", "pidof", $BrokerPackage) -Path $path -Required:$false
        $brokerPid = ([string]$result.output).Trim()
        $attempts += [ordered]@{
            attempt = $attempts.Count + 1
            exit_code = $result.exit_code
            pid = $brokerPid
        }
        if ($result.exit_code -eq 0 -and -not [string]::IsNullOrWhiteSpace($brokerPid)) {
            return [ordered]@{
                action = "wait-manifold-broker-process"
                status = "pass"
                serial = $Serial
                package = $BrokerPackage
                pid = $brokerPid
                attempt_count = $attempts.Count
                attempts = $attempts
            }
        }
        Start-Sleep -Milliseconds 250
    }
    return [ordered]@{
        action = "wait-manifold-broker-process"
        status = "fail"
        serial = $Serial
        package = $BrokerPackage
        timeout_seconds = $TimeoutSeconds
        attempt_count = $attempts.Count
        attempts = $attempts
    }
}

function Wait-Qcl100BrokerHelloTcp {
    param(
        [string]$HostName,
        [int]$Port,
        [int]$TimeoutSeconds
    )

    $started = Get-Date
    $attempt = 0
    while (((Get-Date) - $started).TotalSeconds -le $TimeoutSeconds) {
        $attempt++
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $async = $client.BeginConnect($HostName, $Port, $null, $null)
            if ($async.AsyncWaitHandle.WaitOne(250)) {
                $client.EndConnect($async)
                return [ordered]@{
                    action = "wait-broker-forwarded-socket"
                    status = "pass"
                    host = $HostName
                    port = $Port
                    attempt_count = $attempt
                }
            }
        } catch {
        } finally {
            try { $client.Close() } catch {}
        }
        Start-Sleep -Milliseconds 250
    }
    return [ordered]@{
        action = "wait-broker-forwarded-socket"
        status = "fail"
        host = $HostName
        port = $Port
        timeout_seconds = $TimeoutSeconds
        attempt_count = $attempt
    }
}

function Invoke-Qcl100BrokerHelloWebSocket {
    param(
        [string]$Label,
        [string]$HostName,
        [int]$Port,
        [string]$Path,
        [int]$TimeoutSeconds
    )

    $uri = [Uri]("ws://${HostName}:${Port}${Path}")
    $client = [System.Net.WebSockets.ClientWebSocket]::new()
    $cts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds([Math]::Max(1, $TimeoutSeconds)))
    $requestId = "request.qcl100.$RunId.$Label.websocket_hello"
    $started = Get-Date
    try {
        $null = $client.ConnectAsync($uri, $cts.Token).GetAwaiter().GetResult()
        $hello = [ordered]@{
            type = "hello"
            schema = "rusty.manifold.broker.hello.v1"
            request_id = $requestId
            client_id = "rusty.quest.qcl100_broker_websocket_hello_probe"
        }
        $helloJson = $hello | ConvertTo-Json -Depth 8 -Compress
        $sendBytes = [System.Text.Encoding]::UTF8.GetBytes($helloJson)
        $null = $client.SendAsync(
            [System.ArraySegment[byte]]::new($sendBytes),
            [System.Net.WebSockets.WebSocketMessageType]::Text,
            $true,
            $cts.Token
        ).GetAwaiter().GetResult()

        $buffer = New-Object byte[] 8192
        $stream = [System.IO.MemoryStream]::new()
        do {
            $segment = [System.ArraySegment[byte]]::new($buffer)
            $result = $client.ReceiveAsync($segment, $cts.Token).GetAwaiter().GetResult()
            if ($result.Count -gt 0) {
                $stream.Write($buffer, 0, $result.Count)
            }
        } while (-not $result.EndOfMessage)

        $replyJson = [System.Text.Encoding]::UTF8.GetString($stream.ToArray())
        $reply = $null
        if (-not [string]::IsNullOrWhiteSpace($replyJson)) {
            $reply = $replyJson | ConvertFrom-Json
        }
        $helloAck = [bool](
            $null -ne $reply -and
            [string]$reply.type -eq "hello_ack" -and
            [bool]$reply.accepted
        )
        return [ordered]@{
            action = "wait-broker-websocket-ready"
            status = if ($helloAck) { "pass" } else { "fail" }
            host = $HostName
            port = $Port
            path = $Path
            request_id = $requestId
            handshake_complete = [bool]($client.State -eq [System.Net.WebSockets.WebSocketState]::Open)
            hello_ack = $helloAck
            reply_type = if ($null -ne $reply) { [string]$reply.type } else { "" }
            authority = if ($null -ne $reply) { [string]$reply.authority } else { "" }
            server_id = if ($null -ne $reply) { [string]$reply.server_id } else { "" }
            reply_json = $replyJson
            elapsed_ms = [int][Math]::Ceiling(((Get-Date) - $started).TotalMilliseconds)
        }
    } catch {
        return [ordered]@{
            action = "wait-broker-websocket-ready"
            status = "fail"
            host = $HostName
            port = $Port
            path = $Path
            request_id = $requestId
            handshake_complete = $false
            hello_ack = $false
            error = $_.Exception.Message
            elapsed_ms = [int][Math]::Ceiling(((Get-Date) - $started).TotalMilliseconds)
        }
    } finally {
        try {
            if ($client.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
                $null = $client.CloseAsync(
                    [System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
                    "qcl100 hello probe complete",
                    [System.Threading.CancellationToken]::None
                ).GetAwaiter().GetResult()
            }
        } catch {
        }
        $client.Dispose()
        $cts.Dispose()
    }
}

function Invoke-Qcl100BrokerHelloDevice {
    param(
        [string]$Serial,
        [string]$Label,
        [int]$LocalPort
    )

    $actions = @()
    $issues = @()
    try {
        $actions += Invoke-Qcl100BrokerHelloAdb -Serial $Serial -Arguments @("shell", "am", "start", "-n", $BrokerActivity) -Path (Join-Path $MediaDir "$Label-broker-launch.txt")
        $pidAction = Wait-Qcl100BrokerHelloPid -Serial $Serial -Label $Label -TimeoutSeconds $WaitSeconds
        $actions += $pidAction
        if ([string]$pidAction.status -ne "pass") {
            $issues += "broker process did not become visible"
        }

        $actions += Invoke-Qcl100BrokerHelloAdb -Serial $Serial -Arguments @("forward", "--remove", "tcp:$LocalPort") -Path (Join-Path $MediaDir "$Label-forward-remove-before.txt") -Required:$false
        $actions += Invoke-Qcl100BrokerHelloAdb -Serial $Serial -Arguments @("forward", "tcp:$LocalPort", "tcp:$BrokerPort") -Path (Join-Path $MediaDir "$Label-forward-add.txt")
        $actions += Invoke-Qcl100BrokerHelloAdb -Serial $Serial -Arguments @("forward", "--list") -Path (Join-Path $MediaDir "$Label-forward-list.txt")

        $socket = Wait-Qcl100BrokerHelloTcp -HostName "127.0.0.1" -Port $LocalPort -TimeoutSeconds $WaitSeconds
        $actions += $socket
        if ([string]$socket.status -ne "pass") {
            $issues += "forwarded broker socket did not open"
        }

        $websocket = Invoke-Qcl100BrokerHelloWebSocket -Label $Label -HostName "127.0.0.1" -Port $LocalPort -Path $WebSocketPath -TimeoutSeconds $WaitSeconds
        $actions += $websocket
        if (-not [bool]$websocket.hello_ack) {
            $issues += "broker websocket hello_ack missing"
        }
    } catch {
        $issues += $_.Exception.Message
    } finally {
        $actions += Invoke-Qcl100BrokerHelloAdb -Serial $Serial -Arguments @("forward", "--remove", "tcp:$LocalPort") -Path (Join-Path $MediaDir "$Label-forward-remove-after.txt") -Required:$false
        $actions += Invoke-Qcl100BrokerHelloAdb -Serial $Serial -Arguments @("shell", "am", "force-stop", $BrokerPackage) -Path (Join-Path $MediaDir "$Label-broker-force-stop.txt") -Required:$false
    }

    [ordered]@{
        label = $Label
        serial = $Serial
        broker_local_port = $LocalPort
        broker_port = $BrokerPort
        status = if ($issues.Count -eq 0) { "pass" } else { "fail" }
        hello_ack = [bool](@($actions | Where-Object { [string]$_.action -eq "wait-broker-websocket-ready" -and [bool]$_.hello_ack }).Count -gt 0)
        actions = $actions
        issues = $issues
    }
}

if (-not (Test-Path -LiteralPath $Adb)) {
    throw "ADB not found: $Adb"
}
if ($WaitSeconds -lt 1 -or $WaitSeconds -gt 60) {
    throw "WaitSeconds must be between 1 and 60."
}

$startedAt = Get-Date
$preflightBeforePath = Join-Path $OutDir "broker-websocket-hello-preflight-before.json"
$preflightAfterPath = Join-Path $OutDir "broker-websocket-hello-preflight-after.json"

if ($DryRun) {
    $summary = [ordered]@{
        schema = "rusty.quest.qcl100_broker_websocket_hello_probe.v1"
        run_id = $RunId
        status = "dry_run_planned"
        mode = "broker_websocket_hello_only"
        out_dir = $OutDir
        owner_serial = $OwnerSerial
        client_serial = $ClientSerial
        owner_lease_id = $OwnerLeaseId
        client_lease_id = $ClientLeaseId
        lease_ids_supplied = [bool](-not [string]::IsNullOrWhiteSpace($OwnerLeaseId) -and -not [string]::IsNullOrWhiteSpace($ClientLeaseId))
        hardware_touched = $false
        planned_actions = @(
            "launch broker activity only",
            "wait for broker process",
            "create serial-scoped adb forward to broker port",
            "open WebSocket and send hello",
            "require hello_ack",
            "remove adb forward and force-stop broker"
        )
        forbidden_actions = @(
            "QCL041 launch",
            "native renderer launch",
            "command.remote_camera.start_receiver",
            "command.remote_camera.start_sender",
            "command.remote_camera.get_status",
            "media projection",
            "QCL099 Makepad projection",
            "same-group duplex claim",
            "promotion claim"
        )
    }
    $summaryPath = Join-Path $OutDir "qcl100-broker-websocket-hello-summary.json"
    Write-JsonFile -Value $summary -Path $summaryPath
    Get-Content -Raw -LiteralPath $summaryPath
    return
}

if ([string]::IsNullOrWhiteSpace($OwnerLeaseId) -or [string]::IsNullOrWhiteSpace($ClientLeaseId)) {
    $summary = [ordered]@{
        schema = "rusty.quest.qcl100_broker_websocket_hello_probe.v1"
        run_id = $RunId
        status = "blocked_missing_lease_ids"
        mode = "broker_websocket_hello_only"
        out_dir = $OutDir
        owner_serial = $OwnerSerial
        client_serial = $ClientSerial
        owner_lease_id = $OwnerLeaseId
        client_lease_id = $ClientLeaseId
        lease_ids_supplied = $false
        hardware_touched = $false
        issue = "QCL100 broker WebSocket hello probe requires both OwnerLeaseId and ClientLeaseId before live ADB work."
    }
    $summaryPath = Join-Path $OutDir "qcl100-broker-websocket-hello-summary.json"
    Write-JsonFile -Value $summary -Path $summaryPath
    Get-Content -Raw -LiteralPath $summaryPath
    exit 2
}

$preflightBefore = New-Qcl100AirgapPreflight -OwnerSerial $OwnerSerial -ClientSerial $ClientSerial -OwnerWifiDirectAddress "192.168.49.1" -ClientWifiDirectAddress "192.168.49.46" -MediaDir $MediaDir -PathPrefix "before"
Write-JsonFile -Value $preflightBefore -Path $preflightBeforePath

if (-not [bool]$preflightBefore.infrastructure_wifi_disconnected -or
        -not [bool]$preflightBefore.p2p0_ipv4_cleared -or
        -not [bool]$preflightBefore.candidate_wifi_direct_prelaunch_routes_clear) {
    $summary = [ordered]@{
        schema = "rusty.quest.qcl100_broker_websocket_hello_probe.v1"
        run_id = $RunId
        status = "blocked_preflight"
        mode = "broker_websocket_hello_only"
        out_dir = $OutDir
        owner_serial = $OwnerSerial
        client_serial = $ClientSerial
        owner_lease_id = $OwnerLeaseId
        client_lease_id = $ClientLeaseId
        lease_ids_supplied = $true
        hardware_touched = $false
        blocked_stage = "strict_route_clear_preflight"
        preflight_before = $preflightBefore
        preflight_before_artifact = $preflightBeforePath
        live_actions = [ordered]@{
            broker_activity_launched = $false
            broker_websocket_hello_sent = $false
            remote_camera_command_sent = $false
            qcl041_started = $false
            native_renderer_launched = $false
            media_projection_started = $false
            promotion_claimed = $false
        }
    }
    $summaryPath = Join-Path $OutDir "qcl100-broker-websocket-hello-summary.json"
    Write-JsonFile -Value $summary -Path $summaryPath
    Get-Content -Raw -LiteralPath $summaryPath
    exit 2
}

$owner = Invoke-Qcl100BrokerHelloDevice -Serial $OwnerSerial -Label "owner" -LocalPort $OwnerBrokerLocalPort
$client = Invoke-Qcl100BrokerHelloDevice -Serial $ClientSerial -Label "client" -LocalPort $ClientBrokerLocalPort
$preflightAfter = New-Qcl100AirgapPreflight -OwnerSerial $OwnerSerial -ClientSerial $ClientSerial -OwnerWifiDirectAddress "192.168.49.1" -ClientWifiDirectAddress "192.168.49.46" -MediaDir $MediaDir -PathPrefix "after"
Write-JsonFile -Value $preflightAfter -Path $preflightAfterPath

$passed = [bool](
    [string]$owner.status -eq "pass" -and
    [string]$client.status -eq "pass" -and
    [bool]$owner.hello_ack -and
    [bool]$client.hello_ack -and
    [bool]$preflightBefore.infrastructure_wifi_disconnected -and
    [bool]$preflightBefore.p2p0_ipv4_cleared -and
    [bool]$preflightBefore.candidate_wifi_direct_prelaunch_routes_clear -and
    [bool]$preflightAfter.infrastructure_wifi_disconnected -and
    [bool]$preflightAfter.p2p0_ipv4_cleared -and
    [bool]$preflightAfter.candidate_wifi_direct_prelaunch_routes_clear
)

$summary = [ordered]@{
    schema = "rusty.quest.qcl100_broker_websocket_hello_probe.v1"
    run_id = $RunId
    status = if ($passed) { "pass" } else { "blocked" }
    mode = "broker_websocket_hello_only"
    started_at = $startedAt.ToString("o")
    ended_at = (Get-Date).ToString("o")
    elapsed_seconds = [int][Math]::Ceiling(((Get-Date) - $startedAt).TotalSeconds)
    out_dir = $OutDir
    owner_serial = $OwnerSerial
    client_serial = $ClientSerial
    owner_lease_id = $OwnerLeaseId
    client_lease_id = $ClientLeaseId
    lease_ids_supplied = [bool](-not [string]::IsNullOrWhiteSpace($OwnerLeaseId) -and -not [string]::IsNullOrWhiteSpace($ClientLeaseId))
    broker_package = $BrokerPackage
    broker_activity = $BrokerActivity
    websocket_path = $WebSocketPath
    preflight_before = $preflightBefore
    preflight_after = $preflightAfter
    preflight_before_artifact = $preflightBeforePath
    preflight_after_artifact = $preflightAfterPath
    devices = [ordered]@{
        owner = $owner
        client = $client
    }
    live_actions = [ordered]@{
        hardware_touched = $true
        broker_activity_launched = $true
        broker_websocket_hello_sent = $true
        remote_camera_command_sent = $false
        qcl041_started = $false
        native_renderer_launched = $false
        qcl099_makepad_projection_launched = $false
        media_projection_started = $false
        same_group_duplex_claimed = $false
        promotion_claimed = $false
        cleanup_force_stopped_broker = $true
        cleanup_removed_adb_forwards = $true
    }
    freshness_acceptance = [ordered]@{
        required = "broker WebSocket hello_ack only; no remote_camera commands, QCL041, native renderer, media, duplex, or promotion"
        passed = $passed
    }
}

$summaryPath = Join-Path $OutDir "qcl100-broker-websocket-hello-summary.json"
Write-JsonFile -Value $summary -Path $summaryPath
Get-Content -Raw -LiteralPath $summaryPath
if (-not $passed) {
    exit 2
}
