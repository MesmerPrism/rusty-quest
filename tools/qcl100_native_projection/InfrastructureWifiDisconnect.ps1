# Dot-sourced helper functions for guarded QCL100 infrastructure Wi-Fi disconnects.
# This uses the documented Quest UIAutomation scenario and never targets Forget.

function Write-Qcl100InfrastructureWifiDisconnectJsonFile {
    param([object]$Value, [string]$Path)
    $json = ($Value | ConvertTo-Json -Depth 24) + "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

function Get-Qcl100InfrastructureWifiStatusInfo {
    param([string]$RawStatus)

    $ssid = ""
    $connected = $false
    $match = [regex]::Match($RawStatus, 'Wifi is connected to "([^"]+)"')
    if ($match.Success) {
        $connected = $true
        $ssid = $match.Groups[1].Value
    }

    return [ordered]@{
        infrastructure_connected = $connected
        infrastructure_ssid = $ssid
        raw_status = $RawStatus.TrimEnd()
    }
}

function Invoke-Qcl100InfrastructureWifiDisconnect {
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$OutDir,
        [Parameter(Mandatory=$true)][string]$RunId,
        [Parameter(Mandatory=$true)][string]$Ssid,
        [int]$PostDisconnectClickWaitMs = 2500
    )

    if ([string]::IsNullOrWhiteSpace($Ssid)) {
        throw "Invoke-Qcl100InfrastructureWifiDisconnect requires an explicit SSID."
    }
    if ($PostDisconnectClickWaitMs -lt 500) {
        throw "PostDisconnectClickWaitMs must be at least 500."
    }

    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

    $beforeStatusPath = Join-Path $OutDir "$Label-infrastructure-wifi-disconnect-before-status.txt"
    $afterStatusPath = Join-Path $OutDir "$Label-infrastructure-wifi-disconnect-after-status.txt"
    $dryRunPath = Join-Path $OutDir "$Label-infrastructure-wifi-disconnect-dry-run.txt"
    $mutationPath = Join-Path $OutDir "$Label-infrastructure-wifi-disconnect-mutation.txt"
    $receiptPath = Join-Path $OutDir "$Label-infrastructure-wifi-disconnect.json"

    $beforeStatusResult = Invoke-Qcl100SettingsFenceAdbText `
        -Adb $Adb `
        -Serial $Serial `
        -Arguments @("shell", "cmd", "wifi", "status") `
        -Path $beforeStatusPath
    $beforeWifi = Get-Qcl100InfrastructureWifiStatusInfo -RawStatus ([string]$beforeStatusResult.output)

    $targetMatches = [bool]([string]$beforeWifi.infrastructure_ssid -eq $Ssid)
    if (-not [bool]$beforeWifi.infrastructure_connected) {
        $afterStatusResult = Invoke-Qcl100SettingsFenceAdbText `
            -Adb $Adb `
            -Serial $Serial `
            -Arguments @("shell", "cmd", "wifi", "status") `
            -Path $afterStatusPath
        $afterWifi = Get-Qcl100InfrastructureWifiStatusInfo -RawStatus ([string]$afterStatusResult.output)
        $receipt = [ordered]@{
            schema = "rusty.quest.qcl100_infrastructure_wifi_disconnect.v1"
            run_id = $RunId
            label = $Label
            serial = $Serial
            status = "pass_already_disconnected"
            passed = $true
            adb_scope = "device-scoped-adb"
            target_ssid = $Ssid
            before_status_artifact = $beforeStatusPath
            before_wifi = $beforeWifi
            dry_probe_performed = $false
            dry_probe_result = $null
            mutation_performed = $false
            mutation_result = $null
            after_status_artifact = $afterStatusPath
            after_wifi = $afterWifi
            settings_ui_used = $false
            no_forget_targeted = $true
            wifi_radio_mutated = $false
            media_started = $false
            qcl041_started = $false
            receipt_path = $receiptPath
        }
        Write-Qcl100InfrastructureWifiDisconnectJsonFile -Value $receipt -Path $receiptPath
        return $receipt
    }

    if (-not $targetMatches) {
        $receipt = [ordered]@{
            schema = "rusty.quest.qcl100_infrastructure_wifi_disconnect.v1"
            run_id = $RunId
            label = $Label
            serial = $Serial
            status = "blocked_unexpected_ssid"
            passed = $false
            adb_scope = "device-scoped-adb"
            target_ssid = $Ssid
            before_status_artifact = $beforeStatusPath
            before_wifi = $beforeWifi
            dry_probe_performed = $false
            dry_probe_result = $null
            mutation_performed = $false
            mutation_result = $null
            after_status_artifact = ""
            after_wifi = $null
            settings_ui_used = $false
            no_forget_targeted = $true
            wifi_radio_mutated = $false
            media_started = $false
            qcl041_started = $false
            receipt_path = $receiptPath
        }
        Write-Qcl100InfrastructureWifiDisconnectJsonFile -Value $receipt -Path $receiptPath
        return $receipt
    }

    $instrumentation = "io.github.mesmerprism.questquestionnaire.questuiautomation.test/androidx.test.runner.AndroidJUnitRunner"
    $commonArgs = @(
        "shell", "am", "instrument", "-w",
        "-e", "scenario", "settingsWifiDisconnectProbe",
        "-e", "ssid", $Ssid,
        "-e", "postDisconnectClickWaitMs", ([string]$PostDisconnectClickWaitMs)
    )
    $dryRunArgs = @($commonArgs + @(
        "-e", "allowDisconnect", "false",
        $instrumentation
    ))
    $mutationArgs = @($commonArgs + @(
        "-e", "allowDisconnect", "true",
        "-e", "networkClickMode", "uiObject2",
        "-e", "disconnectClickMode", "uiObject2",
        $instrumentation
    ))

    $dryRunResult = Invoke-Qcl100SettingsFenceAdbText `
        -Adb $Adb `
        -Serial $Serial `
        -Arguments $dryRunArgs `
        -Path $dryRunPath
    $mutationResult = $null
    if ([int]$dryRunResult.exit_code -eq 0) {
        $mutationResult = Invoke-Qcl100SettingsFenceAdbText `
            -Adb $Adb `
            -Serial $Serial `
            -Arguments $mutationArgs `
            -Path $mutationPath
    }

    $afterStatusResult = Invoke-Qcl100SettingsFenceAdbText `
        -Adb $Adb `
        -Serial $Serial `
        -Arguments @("shell", "cmd", "wifi", "status") `
        -Path $afterStatusPath
    $afterWifi = Get-Qcl100InfrastructureWifiStatusInfo -RawStatus ([string]$afterStatusResult.output)

    $passed = [bool](
        [int]$dryRunResult.exit_code -eq 0 -and
        $null -ne $mutationResult -and
        [int]$mutationResult.exit_code -eq 0 -and
        -not [bool]$afterWifi.infrastructure_connected
    )
    $status = "pass"
    if ([int]$dryRunResult.exit_code -ne 0) {
        $status = "failed_dry_probe"
    } elseif ($null -eq $mutationResult -or [int]$mutationResult.exit_code -ne 0) {
        $status = "failed_mutation_probe"
    } elseif ([bool]$afterWifi.infrastructure_connected) {
        $status = "failed_still_connected"
    }

    $receipt = [ordered]@{
        schema = "rusty.quest.qcl100_infrastructure_wifi_disconnect.v1"
        run_id = $RunId
        label = $Label
        serial = $Serial
        status = $status
        passed = $passed
        adb_scope = "device-scoped-adb"
        target_ssid = $Ssid
        before_status_artifact = $beforeStatusPath
        before_wifi = $beforeWifi
        dry_probe_performed = $true
        dry_probe_artifact = $dryRunPath
        dry_probe_result = $dryRunResult
        mutation_performed = [bool]($null -ne $mutationResult)
        mutation_artifact = if ($null -eq $mutationResult) { "" } else { $mutationPath }
        mutation_result = $mutationResult
        after_status_artifact = $afterStatusPath
        after_wifi = $afterWifi
        settings_ui_used = $true
        no_forget_targeted = $true
        wifi_radio_mutated = $false
        media_started = $false
        qcl041_started = $false
        receipt_path = $receiptPath
    }
    Write-Qcl100InfrastructureWifiDisconnectJsonFile -Value $receipt -Path $receiptPath
    return $receipt
}
