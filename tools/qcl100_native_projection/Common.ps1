# Dot-sourced helper functions for Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1.
# Keep these functions side-effect free until called by the runner facade.

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

function Invoke-AdbChecked {
    param([string]$Serial, [string[]]$Arguments, [string]$Name = "adb")
    Invoke-External -Name "$Name $Serial" -File $Adb -Arguments (@("-s", $Serial) + $Arguments) | Out-Null
}

function Invoke-AdbCheckedText {
    param([string]$Serial, [string[]]$Arguments, [string]$Name = "adb", [string]$Path = "")
    return Invoke-External -Name "$Name $Serial" -File $Adb -Arguments (@("-s", $Serial) + $Arguments) -LogPath $Path
}

function Read-AdbText {
    param([string]$Serial, [string[]]$Arguments, [string]$Path = "")
    $output = & $Adb -s $Serial @Arguments 2>&1 | Out-String
    if (-not [string]::IsNullOrWhiteSpace($Path)) {
        $output | Set-Content -Encoding UTF8 -Path $Path
    }
    return $output
}

function Start-Qcl100NativeRendererLogcatCapture {
    param([string]$Serial, [string]$Label, [string]$Path)

    $stderrPath = $Path -replace "\.txt$", ".stderr.txt"
    if ($stderrPath -eq $Path) {
        $stderrPath = "$Path.stderr.txt"
    }
    foreach ($existingPath in @($Path, $stderrPath)) {
        if (Test-Path -LiteralPath $existingPath) {
            Remove-Item -LiteralPath $existingPath -Force
        }
    }

    $filters = @("RQNativeRenderer:I", "AndroidRuntime:E", "*:S")
    $process = Start-Process `
        -FilePath $Adb `
        -ArgumentList (@("-s", $Serial, "logcat", "-v", "threadtime") + $filters) `
        -RedirectStandardOutput $Path `
        -RedirectStandardError $stderrPath `
        -WindowStyle Hidden `
        -PassThru

    return [ordered]@{
        schema = "rusty.quest.qcl100_native_renderer_logcat_capture.v1"
        label = $Label
        serial = $Serial
        capture_mode = "live_filtered_logcat"
        path = $Path
        stderr_path = $stderrPath
        pid = $process.Id
        filters = $filters
        started = $true
    }
}

function Stop-Qcl100NativeRendererLogcatCapture {
    param($Capture)

    if ($null -eq $Capture -or -not [bool]$Capture.started) {
        return [ordered]@{
            schema = "rusty.quest.qcl100_native_renderer_logcat_capture_stop.v1"
            started = $false
            stopped = $false
            path = ""
            line_count = 0
            bytes = 0
        }
    }

    $process = Get-Process -Id $Capture.pid -ErrorAction SilentlyContinue
    if ($null -ne $process) {
        Stop-Process -Id $Capture.pid -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 200
    }

    $lineCount = 0
    $bytes = 0
    if (Test-Path -LiteralPath $Capture.path) {
        $item = Get-Item -LiteralPath $Capture.path
        $bytes = $item.Length
        $lineCount = (Get-Content -LiteralPath $Capture.path | Measure-Object -Line).Lines
    }

    return [ordered]@{
        schema = "rusty.quest.qcl100_native_renderer_logcat_capture_stop.v1"
        label = $Capture.label
        serial = $Capture.serial
        capture_mode = $Capture.capture_mode
        path = $Capture.path
        stderr_path = $Capture.stderr_path
        pid = $Capture.pid
        filters = $Capture.filters
        started = $true
        stopped = $true
        process_was_running = [bool]($null -ne $process)
        line_count = $lineCount
        bytes = $bytes
    }
}

function Write-JsonFile {
    param([object]$Value, [string]$Path)
    $json = ($Value | ConvertTo-Json -Depth 16) + "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

function Get-Qcl100WifiStatus {
    param([string]$Serial, [string]$Label, [string]$Path = "")
    $output = Invoke-AdbCheckedText `
        -Serial $Serial `
        -Arguments @("shell", "cmd", "wifi", "status") `
        -Name "$Label wifi status" `
        -Path $Path
    $ssid = ""
    if ($output -match 'Wifi is connected to "([^"]+)"') {
        $ssid = $Matches[1]
    }
    [ordered]@{
        serial = $Serial
        label = $Label
        wifi_enabled = [bool]($output -match '(?m)^Wifi is enabled')
        infrastructure_connected = [bool](-not [string]::IsNullOrWhiteSpace($ssid))
        infrastructure_ssid = $ssid
        raw_status = $output.Trim()
    }
}

function Get-Qcl100P2pIpv4Status {
    param([string]$Serial, [string]$Label, [string]$Path = "")
    $output = Read-AdbText `
        -Serial $Serial `
        -Arguments @("shell", "ip", "-4", "addr", "show", "p2p0") `
        -Path $Path
    $address = ""
    if ($output -match '\binet\s+([0-9.]+)/') {
        $address = $Matches[1]
    }
    [ordered]@{
        serial = $Serial
        label = $Label
        interface = "p2p0"
        ipv4_present = [bool](-not [string]::IsNullOrWhiteSpace($address))
        ipv4_address = $address
        raw_status = $output.Trim()
    }
}

function Get-Qcl100RouteSnapshot {
    param([string]$Serial, [string]$Label, [string]$TargetAddress, [string]$Path = "")
    if ($TargetAddress -notmatch '^[0-9.]+$') {
        throw "QCL100 route snapshot target must be an IPv4 address: $TargetAddress"
    }
    $output = Read-AdbText `
        -Serial $Serial `
        -Arguments @("shell", "ip route get $TargetAddress 2>&1 || true") `
        -Path $Path
    $trimmed = $output.Trim()
    $device = ""
    if ($trimmed -match '\bdev\s+(\S+)') {
        $device = $Matches[1]
    }
    $source = ""
    if ($trimmed -match '\bsrc\s+([0-9.]+)') {
        $source = $Matches[1]
    }
    $unreachable = [bool]($trimmed -match 'Network is unreachable' -or $trimmed -match 'RTNETLINK answers')
    [ordered]@{
        serial = $Serial
        label = $Label
        target_address = $TargetAddress
        command = "ip route get $TargetAddress"
        reachable = [bool](-not $unreachable -and -not [string]::IsNullOrWhiteSpace($device))
        route_device = $device
        route_source = $source
        uses_p2p0 = [bool]($device -eq "p2p0")
        uses_wlan0 = [bool]($device -eq "wlan0")
        uses_loopback = [bool]($device -eq "lo")
        local_self_route = [bool]($device -eq "lo" -and $source -eq $TargetAddress)
        unreachable = $unreachable
        raw_status = $trimmed
    }
}

function New-Qcl100AirgapPreflight {
    param(
        [string]$OwnerSerial,
        [string]$ClientSerial,
        [string]$OwnerWifiDirectAddress,
        [string]$ClientWifiDirectAddress,
        [string]$MediaDir,
        [string]$PathPrefix = ""
    )

    $prefix = ""
    if (-not [string]::IsNullOrWhiteSpace($PathPrefix)) {
        $prefix = "$PathPrefix-"
    }

    $preflight = [ordered]@{
        schema = "rusty.quest.qcl100_infrastructure_wifi_airgap_preflight.v1"
        owner_wifi = Get-Qcl100WifiStatus -Serial $OwnerSerial -Label "owner" -Path (Join-Path $MediaDir "${prefix}owner-wifi-status.txt")
        client_wifi = Get-Qcl100WifiStatus -Serial $ClientSerial -Label "client" -Path (Join-Path $MediaDir "${prefix}client-wifi-status.txt")
        owner_p2p0 = Get-Qcl100P2pIpv4Status -Serial $OwnerSerial -Label "owner" -Path (Join-Path $MediaDir "${prefix}owner-p2p0-ipv4.txt")
        client_p2p0 = Get-Qcl100P2pIpv4Status -Serial $ClientSerial -Label "client" -Path (Join-Path $MediaDir "${prefix}client-p2p0-ipv4.txt")
        shell_routes = [ordered]@{
            owner_to_owner_wifi_direct_address = Get-Qcl100RouteSnapshot -Serial $OwnerSerial -Label "owner-to-owner-wifi-direct-address" -TargetAddress $OwnerWifiDirectAddress -Path (Join-Path $MediaDir "${prefix}owner-route-to-owner-wifi-direct-address.txt")
            owner_to_client_wifi_direct_address = Get-Qcl100RouteSnapshot -Serial $OwnerSerial -Label "owner-to-client-wifi-direct-address" -TargetAddress $ClientWifiDirectAddress -Path (Join-Path $MediaDir "${prefix}owner-route-to-client-wifi-direct-address.txt")
            client_to_owner_wifi_direct_address = Get-Qcl100RouteSnapshot -Serial $ClientSerial -Label "client-to-owner-wifi-direct-address" -TargetAddress $OwnerWifiDirectAddress -Path (Join-Path $MediaDir "${prefix}client-route-to-owner-wifi-direct-address.txt")
            client_to_client_wifi_direct_address = Get-Qcl100RouteSnapshot -Serial $ClientSerial -Label "client-to-client-wifi-direct-address" -TargetAddress $ClientWifiDirectAddress -Path (Join-Path $MediaDir "${prefix}client-route-to-client-wifi-direct-address.txt")
        }
    }
    $preflight["infrastructure_wifi_disconnected"] = [bool](
        -not [bool]$preflight.owner_wifi.infrastructure_connected -and
        -not [bool]$preflight.client_wifi.infrastructure_connected)
    $preflight["p2p0_ipv4_cleared"] = [bool](
        -not [bool]$preflight.owner_p2p0.ipv4_present -and
        -not [bool]$preflight.client_p2p0.ipv4_present)
    $routeSnapshots = @($preflight.shell_routes.Values)
    $preflight["candidate_wifi_direct_route_count"] = $routeSnapshots.Count
    $preflight["candidate_wifi_direct_routes_using_wlan0"] = @($routeSnapshots | Where-Object { $_.uses_wlan0 }).Count
    $preflight["candidate_wifi_direct_routes_using_p2p0"] = @($routeSnapshots | Where-Object { $_.uses_p2p0 }).Count
    $preflight["candidate_wifi_direct_routes_using_loopback"] = @($routeSnapshots | Where-Object { $_.uses_loopback }).Count
    $preflight["candidate_wifi_direct_local_self_routes"] = @($routeSnapshots | Where-Object { $_.local_self_route }).Count
    $preflight["candidate_wifi_direct_routes_unreachable"] = @($routeSnapshots | Where-Object { $_.unreachable }).Count
    $preflight["candidate_wifi_direct_routes_reachable"] = @($routeSnapshots | Where-Object { $_.reachable }).Count
    $preflight["candidate_wifi_direct_prelaunch_routes_clear"] = [bool](
        $preflight.candidate_wifi_direct_routes_using_wlan0 -eq 0 -and
        $preflight.candidate_wifi_direct_routes_using_p2p0 -eq 0 -and
        $preflight.candidate_wifi_direct_local_self_routes -eq 0)

    return $preflight
}

function Resolve-Aapt2FromAdb {
    param([Parameter(Mandatory=$true)][string]$AdbPath)

    $sdkRoots = @()
    if (Test-Path -LiteralPath $AdbPath) {
        $adbFullPath = (Resolve-Path -LiteralPath $AdbPath).Path
        $sdkRoots += Split-Path -Parent (Split-Path -Parent $adbFullPath)
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $sdkRoots += $env:ANDROID_HOME
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        $sdkRoots += $env:ANDROID_SDK_ROOT
    }

    foreach ($sdkRoot in @($sdkRoots | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)) {
        $buildToolsRoot = Join-Path $sdkRoot "build-tools"
        if (-not (Test-Path -LiteralPath $buildToolsRoot)) {
            continue
        }
        $buildToolDirs = @(Get-ChildItem -LiteralPath $buildToolsRoot -Directory | Sort-Object Name -Descending)
        foreach ($buildToolDir in $buildToolDirs) {
            $candidate = Join-Path $buildToolDir.FullName "aapt2.exe"
            if (Test-Path -LiteralPath $candidate) {
                return $candidate
            }
        }
    }

    $command = Get-Command "aapt2.exe" -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }
    $command = Get-Command "aapt2" -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    throw "Unable to resolve aapt2 from adb path, ANDROID_HOME, ANDROID_SDK_ROOT, or PATH."
}

function Assert-ApkUsesPermission {
    param(
        [Parameter(Mandatory=$true)][string]$ApkPath,
        [Parameter(Mandatory=$true)][string]$Permission,
        [Parameter(Mandatory=$true)][string]$Label,
        [string]$DumpPath = ""
    )

    if (-not (Test-Path -LiteralPath $ApkPath)) {
        throw "$Label APK not found for permission assertion: $ApkPath"
    }

    $aapt2 = Resolve-Aapt2FromAdb -AdbPath $Adb
    $dumpLines = & $aapt2 dump permissions $ApkPath 2>&1
    $exitCode = $LASTEXITCODE
    $dumpText = $dumpLines | Out-String
    if (-not [string]::IsNullOrWhiteSpace($DumpPath)) {
        $dumpText | Set-Content -Encoding UTF8 -Path $DumpPath
    }
    if ($exitCode -ne 0) {
        throw "$Label APK permission dump failed with exit code $exitCode. $dumpText"
    }

    $needle = "uses-permission: name='$Permission'"
    if (-not $dumpText.Contains($needle)) {
        throw "$Label APK is missing required Android permission $Permission. Rebuild the QCL100 native renderer artifact with the generic manifest or a video-projection app-build lock before running live projection."
    }

    [ordered]@{
        label = $Label
        apk_path = (Get-Item -LiteralPath $ApkPath).FullName
        permission = $Permission
        declared = $true
        aapt2 = $aapt2
        dump_path = $DumpPath
    }
}

function Get-ArtifactEvidence {
    param([string]$Path)
    $item = Get-Item -LiteralPath $Path
    $hashValue = $null
    if (Get-Command Get-FileHash -ErrorAction SilentlyContinue) {
        $hashValue = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    } else {
        $stream = [System.IO.File]::OpenRead($item.FullName)
        try {
            $sha256 = [System.Security.Cryptography.SHA256]::Create()
            try {
                $hashValue = -join ($sha256.ComputeHash($stream) | ForEach-Object { $_.ToString("x2") })
            } finally {
                $sha256.Dispose()
            }
        } finally {
            $stream.Dispose()
        }
    }
    [ordered]@{
        path = $item.FullName
        bytes = $item.Length
        sha256 = $hashValue
    }
}
