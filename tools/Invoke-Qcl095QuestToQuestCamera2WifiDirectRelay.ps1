param(
    [string]$OwnerSerial = "340YC10G7T0JBW",
    [string]$ClientSerial = "3487C10H3M017Q",
    [string]$OwnerLeaseId = "",
    [string]$ClientLeaseId = "",
    [string]$RunId = "",
    [string]$OutDir = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe",
    [string]$Python = "python",
    [string]$HostessCtl = "S:\Work\repos\active\rusty-hostess\tools\hostessctl\hostessctl.py",
    [int]$DurationSeconds = 30,
    [int]$OwnerBrokerLocalPort = 18765,
    [int]$ClientBrokerLocalPort = 18766,
    [int]$OwnerCaptureLocalPort = 19779,
    [int]$ClientCaptureLocalPort = 19780,
    [string]$OwnerWifiDirectAddress = "192.168.49.1",
    [string]$ClientWifiDirectAddress = "192.168.49.46",
    [int]$ReceiverPort = 8979,
    [int]$TransportPort = 9079,
    [int]$SourcePort = 8879,
    [string]$CameraIds = "left:50",
    [string]$MediaProfiles = "left:320x240@15:500000",
    [int]$RelayTimeoutSeconds = 42,
    [int]$RelayMaxBytes = 32000000,
    [int]$HoldAfterSocketMs = 38000,
    [switch]$SkipCleanup
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "qcl095-q2q-relay-" + (Get-Date -Format "yyyyMMddTHHmmssZ").ToString()
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path "S:\Work\repos\active\rusty-quest\target" $RunId
}

$MediaDir = Join-Path $OutDir "media"
New-Item -ItemType Directory -Force -Path $MediaDir | Out-Null

function Invoke-External {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$File,
        [string[]]$Arguments = @(),
        [string]$LogPath = ""
    )
    $output = & $File @Arguments 2>&1 | Out-String
    if (-not [string]::IsNullOrWhiteSpace($LogPath)) {
        $output | Set-Content -Encoding UTF8 -Path $LogPath
    }
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE. $output"
    }
    return $output
}

function Invoke-AdbBestEffort {
    param([string]$Serial, [string[]]$Arguments)
    & $Adb -s $Serial @Arguments 2>&1 | Out-Null
}

function Write-JsonFile {
    param([object]$Value, [string]$Path)
    $json = ($Value | ConvertTo-Json -Depth 96) + "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

function New-CaptureScript {
    param([string]$Path)
    @'
import json, socket, struct, sys, time
from pathlib import Path

host=sys.argv[1]
port=int(sys.argv[2])
capture_path=Path(sys.argv[3])
summary_path=Path(sys.argv[4])
duration=float(sys.argv[5])
label=sys.argv[6]
started=time.time()
connected=False
error=""
data=bytearray()
s=None
try:
    deadline=started+20.0
    last=None
    while time.time()<deadline:
        try:
            s=socket.create_connection((host,port),timeout=1.0)
            connected=True
            break
        except OSError as exc:
            last=exc
            time.sleep(0.25)
    if not connected:
        raise RuntimeError(f"connect timeout: {last}")
    s.settimeout(1.0)
    end=time.time()+duration
    while time.time()<end:
        try:
            chunk=s.recv(65536)
            if not chunk:
                break
            data.extend(chunk)
        except socket.timeout:
            continue
except Exception as exc:
    error=repr(exc)
finally:
    if s is not None:
        try:
            s.close()
        except OSError:
            pass
capture_path.write_bytes(data)
summary={
    "schema":"rusty.quest.qcl095_forwarded_rmanvid1_capture.v1",
    "label":label,
    "connect_host":host,
    "connect_port":port,
    "duration_seconds":duration,
    "connected":connected,
    "bytes":len(data),
    "magic":"",
    "schema_version":None,
    "codec":None,
    "codec_name":"unknown",
    "width":None,
    "height":None,
    "metadata_len":None,
    "metadata":None,
    "packet_count":0,
    "video_packet_count":0,
    "codec_config_packet_count":0,
    "first_payload_bytes":None,
    "error":error,
}
try:
    if len(data)>=32:
        summary["magic"]=bytes(data[0:8]).decode("ascii", errors="replace")
        summary["schema_version"]=struct.unpack(">I", data[8:12])[0]
        summary["codec"]=struct.unpack(">I", data[12:16])[0]
        summary["codec_name"]="h264" if summary["codec"]==1 else "unknown"
        summary["width"]=struct.unpack(">I", data[16:20])[0]
        summary["height"]=struct.unpack(">I", data[20:24])[0]
        summary["metadata_len"]=struct.unpack(">I", data[28:32])[0]
        pos=32
        mlen=int(summary["metadata_len"] or 0)
        if mlen and len(data)>=pos+mlen:
            raw=bytes(data[pos:pos+mlen])
            try:
                summary["metadata"]=json.loads(raw.decode("utf-8"))
            except Exception:
                summary["metadata"]={"decode_error":raw[:200].decode("utf-8", errors="replace")}
        pos += mlen
        while len(data) >= pos + 32:
            ph=data[pos:pos+32]
            flags=struct.unpack(">I", ph[8:12])[0]
            payload_len=struct.unpack(">I", ph[12:16])[0]
            if payload_len < 0 or len(data) < pos + 32 + payload_len:
                break
            summary["packet_count"] += 1
            if flags & 2:
                summary["codec_config_packet_count"] += 1
            else:
                summary["video_packet_count"] += 1
            if summary["first_payload_bytes"] is None:
                summary["first_payload_bytes"] = payload_len
            pos += 32 + payload_len
except Exception as exc:
    summary["error"]=repr(exc)
summary["ended_at_unix_ms"]=int(time.time()*1000)
summary_path.write_text(json.dumps(summary, indent=2, sort_keys=True)+"\n", encoding="utf-8")
'@ | Set-Content -Encoding UTF8 -Path $Path
}

function New-BridgeRequest {
    param(
        [string]$Name,
        [string]$Command,
        [object]$Params,
        [string]$RequestId,
        [string]$EvidenceId
    )
    $paramsPath = Join-Path $MediaDir "$Name-params.json"
    $requestPath = Join-Path $MediaDir "$Name-request.json"
    Write-JsonFile -Value $Params -Path $paramsPath
    Invoke-External `
        -Name "emit $Name" `
        -File $Python `
        -Arguments @(
            $HostessCtl,
            "emit-bridge-command-request",
            "--bridge-command", $Command,
            "--out", $requestPath,
            "--request-id", $RequestId,
            "--evidence-id", $EvidenceId,
            "--required-stage", "sent",
            "--required-stage", "authority_accepted",
            "--params-json-file", $paramsPath
        ) | Out-Null
    return $requestPath
}

function Invoke-LiveBridgeCommand {
    param(
        [string]$Name,
        [string]$Serial,
        [int]$BrokerLocalPort,
        [string]$RequestPath,
        [switch]$NoLaunchBroker
    )
    $args = @(
        $HostessCtl,
        "run-bridge-command-live-android",
        "--input", $RequestPath,
        "--out", (Join-Path $MediaDir "$Name-route.json"),
        "--execution-out", (Join-Path $MediaDir "$Name-execution.json"),
        "--validation-out", (Join-Path $MediaDir "$Name-validation.json"),
        "--logcat-out", (Join-Path $MediaDir "$Name.logcat.txt"),
        "--adb", $Adb,
        "--serial", $Serial,
        "--broker-local-port", $BrokerLocalPort.ToString(),
        "--broker-package", "io.github.mesmerprism.rustymanifold.broker",
        "--no-launch-makepad",
        "--no-wait-makepad-process",
        "--socket-wait-seconds", "10",
        "--wait-seconds", "10"
    )
    if ($NoLaunchBroker) {
        $args += @("--no-launch-broker", "--no-wait-broker-process")
    }
    Invoke-External -Name "live bridge command $Name" -File $Python -Arguments $args | Out-Null
}

function Start-Qcl041Relay {
    param(
        [string]$Serial,
        [string]$Role,
        [string]$ReceiverHost,
        [string]$LeaseId,
        [string]$LogName
    )
    $intentArgs = @(
        "-s", $Serial,
        "shell", "am", "start-foreground-service",
        "-n", "io.github.mesmerprism.rustyquest.qcl041/.Qcl041WifiDirectHarnessService",
        "--es", "qcl041.run_id", $RunId,
        "--es", "qcl041.device_serial", $Serial,
        "--es", "qcl041.device_model", "Quest_3S",
        "--es", "qcl041.lease_id", $LeaseId,
        "--es", "qcl041.lease_resource", "quest:$Serial",
        "--ez", "qcl041.lease_reserved_before_live_steps", "true",
        "--es", "qcl041.peer_class", "quest",
        "--es", "qcl041.q2q_role", $Role,
        "--es", "qcl041.peer_name_contains", "Quest",
        "--es", "qcl041.host_toolchain_profile", "qcl095_quest_to_quest_camera2_wifi_direct_relay",
        "--ei", "qcl041.timeout_seconds", "75",
        "--ei", "qcl041.socket_timeout_seconds", "30",
        "--ei", "qcl041.hold_after_socket_ms", $HoldAfterSocketMs.ToString(),
        "--ez", "qcl041.qcl082_relay_enabled", "true",
        "--es", "qcl041.qcl082_relay_source_host", "127.0.0.1",
        "--ei", "qcl041.qcl082_relay_source_port", $SourcePort.ToString(),
        "--es", "qcl041.qcl082_relay_receiver_host", $ReceiverHost,
        "--ei", "qcl041.qcl082_relay_receiver_port", $TransportPort.ToString(),
        "--ei", "qcl041.qcl082_relay_timeout_seconds", $RelayTimeoutSeconds.ToString(),
        "--ei", "qcl041.qcl082_relay_max_bytes", $RelayMaxBytes.ToString(),
        "--ei", "qcl041.qcl082_relay_start_delay_ms", "1000"
    )
    Invoke-External `
        -Name "QCL041 relay launch $Serial" `
        -File $Adb `
        -Arguments $intentArgs `
        -LogPath (Join-Path $MediaDir $LogName) | Out-Null
}

function Read-Qcl041Artifact {
    param([string]$Serial, [string]$OutPath)
    $content = & $Adb -s $Serial exec-out run-as io.github.mesmerprism.rustyquest.qcl041 cat "files/qcl041/$RunId.json" 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($content)) {
        throw "Could not read QCL041 artifact from $Serial. $content"
    }
    $content | Set-Content -Encoding UTF8 -Path $OutPath
}

foreach ($serial in @($OwnerSerial, $ClientSerial)) {
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustyquest.qcl041")
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustymanifold.broker")
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", "com.example.rustyxr.broker")
    Invoke-AdbBestEffort -Serial $serial -Arguments @("forward", "--remove-all")
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "pm", "grant", "io.github.mesmerprism.rustyquest.qcl041", "android.permission.NEARBY_WIFI_DEVICES")
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "pm", "grant", "io.github.mesmerprism.rustymanifold.broker", "android.permission.CAMERA")
    Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "pm", "grant", "io.github.mesmerprism.rustymanifold.broker", "horizonos.permission.HEADSET_CAMERA")
}

$receiverParams = [ordered]@{
    session_id = $RunId
    receiver_bind_host = "127.0.0.1"
    receiver_ports = "left:$ReceiverPort"
    transport_bind_host = "0.0.0.0"
    transport_receive_ports = "left:$TransportPort"
}
$senderParams = [ordered]@{
    session_id = $RunId
    sender_source_host = "127.0.0.1"
    sender_source_ports = "left:$SourcePort"
    sender_source_kind = "camera2_mediacodec_surface"
    sender_media_profiles = $MediaProfiles
    sender_camera_ids = $CameraIds
    sender_camera_id = "none"
    sender_camera_facing = "none"
    sender_quality_profile = "qcl095-lowrate"
    camera_permission_policy = "camera_permission_required"
    transport_routes = "none"
}

$ownerRecv = New-BridgeRequest "owner-start-receiver" "command.remote_camera.start_receiver" $receiverParams "request.qcl095.$RunId.owner.receiver" "evidence.qcl095.$RunId.owner.receiver"
$clientRecv = New-BridgeRequest "client-start-receiver" "command.remote_camera.start_receiver" $receiverParams "request.qcl095.$RunId.client.receiver" "evidence.qcl095.$RunId.client.receiver"
$ownerSender = New-BridgeRequest "owner-start-source-only" "command.remote_camera.start_sender" $senderParams "request.qcl095.$RunId.owner.source_only" "evidence.qcl095.$RunId.owner.source_only"
$clientSender = New-BridgeRequest "client-start-source-only" "command.remote_camera.start_sender" $senderParams "request.qcl095.$RunId.client.source_only" "evidence.qcl095.$RunId.client.source_only"

Invoke-LiveBridgeCommand "owner-start-receiver" $OwnerSerial $OwnerBrokerLocalPort $ownerRecv
Invoke-LiveBridgeCommand "client-start-receiver" $ClientSerial $ClientBrokerLocalPort $clientRecv
Invoke-LiveBridgeCommand "owner-start-source-only" $OwnerSerial $OwnerBrokerLocalPort $ownerSender
Invoke-LiveBridgeCommand "client-start-source-only" $ClientSerial $ClientBrokerLocalPort $clientSender

Invoke-External -Name "owner receiver forward" -File $Adb -Arguments @("-s", $OwnerSerial, "forward", "tcp:$OwnerCaptureLocalPort", "tcp:$ReceiverPort") | Out-Null
Invoke-External -Name "client receiver forward" -File $Adb -Arguments @("-s", $ClientSerial, "forward", "tcp:$ClientCaptureLocalPort", "tcp:$ReceiverPort") | Out-Null

$captureScript = Join-Path $MediaDir "capture_rmanvid1.py"
New-CaptureScript -Path $captureScript
$ownerCapture = Start-Process `
    -FilePath $Python `
    -ArgumentList @(
        $captureScript,
        "127.0.0.1",
        $OwnerCaptureLocalPort.ToString(),
        (Join-Path $MediaDir "owner-receives-client-camera.rmanvid1"),
        (Join-Path $MediaDir "owner-receives-client-camera.summary.json"),
        $DurationSeconds.ToString(),
        "owner-receives-client-camera"
    ) `
    -WorkingDirectory $MediaDir `
    -PassThru `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $MediaDir "owner-capture.stdout.txt") `
    -RedirectStandardError (Join-Path $MediaDir "owner-capture.stderr.txt")
$clientCapture = Start-Process `
    -FilePath $Python `
    -ArgumentList @(
        $captureScript,
        "127.0.0.1",
        $ClientCaptureLocalPort.ToString(),
        (Join-Path $MediaDir "client-receives-owner-camera.rmanvid1"),
        (Join-Path $MediaDir "client-receives-owner-camera.summary.json"),
        $DurationSeconds.ToString(),
        "client-receives-owner-camera"
    ) `
    -WorkingDirectory $MediaDir `
    -PassThru `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $MediaDir "client-capture.stdout.txt") `
    -RedirectStandardError (Join-Path $MediaDir "client-capture.stderr.txt")

Start-Qcl041Relay $OwnerSerial "group_owner" $ClientWifiDirectAddress $OwnerLeaseId "owner-qcl041-launch.txt"
Start-Sleep -Seconds 2
Start-Qcl041Relay $ClientSerial "client" $OwnerWifiDirectAddress $ClientLeaseId "client-qcl041-launch.txt"

$captureWaitMs = [Math]::Max(60000, ($DurationSeconds + 45) * 1000)
if (-not $ownerCapture.WaitForExit($captureWaitMs)) {
    try { $ownerCapture.Kill() } catch {}
    throw "owner capture timed out"
}
if (-not $clientCapture.WaitForExit($captureWaitMs)) {
    try { $clientCapture.Kill() } catch {}
    throw "client capture timed out"
}

Start-Sleep -Seconds 15
Read-Qcl041Artifact -Serial $OwnerSerial -OutPath (Join-Path $OutDir "owner-qcl041.json")
Read-Qcl041Artifact -Serial $ClientSerial -OutPath (Join-Path $OutDir "client-qcl041.json")

$statusParams = [ordered]@{ session_id = $RunId }
$ownerStatus = New-BridgeRequest "owner-final-status" "command.remote_camera.get_status" $statusParams "request.qcl095.$RunId.owner.final_status" "evidence.qcl095.$RunId.owner.final_status"
$clientStatus = New-BridgeRequest "client-final-status" "command.remote_camera.get_status" $statusParams "request.qcl095.$RunId.client.final_status" "evidence.qcl095.$RunId.client.final_status"
$ownerStop = New-BridgeRequest "owner-stop" "command.remote_camera.stop" $statusParams "request.qcl095.$RunId.owner.stop" "evidence.qcl095.$RunId.owner.stop"
$clientStop = New-BridgeRequest "client-stop" "command.remote_camera.stop" $statusParams "request.qcl095.$RunId.client.stop" "evidence.qcl095.$RunId.client.stop"
Invoke-LiveBridgeCommand "owner-final-status" $OwnerSerial $OwnerBrokerLocalPort $ownerStatus -NoLaunchBroker
Invoke-LiveBridgeCommand "client-final-status" $ClientSerial $ClientBrokerLocalPort $clientStatus -NoLaunchBroker
Invoke-LiveBridgeCommand "owner-stop" $OwnerSerial $OwnerBrokerLocalPort $ownerStop -NoLaunchBroker
Invoke-LiveBridgeCommand "client-stop" $ClientSerial $ClientBrokerLocalPort $clientStop -NoLaunchBroker

$ownerCaptureSummary = Get-Content -Raw (Join-Path $MediaDir "owner-receives-client-camera.summary.json") | ConvertFrom-Json
$clientCaptureSummary = Get-Content -Raw (Join-Path $MediaDir "client-receives-owner-camera.summary.json") | ConvertFrom-Json
$ownerQcl041 = Get-Content -Raw (Join-Path $OutDir "owner-qcl041.json") | ConvertFrom-Json
$clientQcl041 = Get-Content -Raw (Join-Path $OutDir "client-qcl041.json") | ConvertFrom-Json
$ownerStatusExecution = Get-Content -Raw (Join-Path $MediaDir "owner-final-status-execution.json") | ConvertFrom-Json
$clientStatusExecution = Get-Content -Raw (Join-Path $MediaDir "client-final-status-execution.json") | ConvertFrom-Json

$summary = [ordered]@{
    schema = "rusty.quest.qcl095_quest_to_quest_camera2_wifi_direct_relay_run.v1"
    run_id = $RunId
    owner_serial = $OwnerSerial
    client_serial = $ClientSerial
    duration_seconds = $DurationSeconds
    owner_receives_client_camera = $ownerCaptureSummary
    client_receives_owner_camera = $clientCaptureSummary
    owner_relay_status = $ownerQcl041.diagnostics.qcl082_relay.status
    client_relay_status = $clientQcl041.diagnostics.qcl082_relay.status
    owner_relay_bytes = $ownerQcl041.diagnostics.qcl082_relay.bytes_copied
    client_relay_bytes = $clientQcl041.diagnostics.qcl082_relay.bytes_copied
    owner_group_formation_ms = $ownerQcl041.measurements.group_formation_ms
    client_group_formation_ms = $clientQcl041.measurements.group_formation_ms
    owner_broker_status = $ownerStatusExecution.command_execution.broker_messages[0].remote_camera_runtime
    client_broker_status = $clientStatusExecution.command_execution.broker_messages[0].remote_camera_runtime
    evidence_dir = $OutDir
}
Write-JsonFile -Value $summary -Path (Join-Path $OutDir "relay-capture-summary.json")

if (-not $SkipCleanup) {
    foreach ($serial in @($OwnerSerial, $ClientSerial)) {
        Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustyquest.qcl041")
        Invoke-AdbBestEffort -Serial $serial -Arguments @("shell", "am", "force-stop", "io.github.mesmerprism.rustymanifold.broker")
        Invoke-AdbBestEffort -Serial $serial -Arguments @("forward", "--remove-all")
    }
}

Get-Content -Raw (Join-Path $OutDir "relay-capture-summary.json")
