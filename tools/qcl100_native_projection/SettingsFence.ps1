# Dot-sourced helper functions for QCL100 runner preflight fencing.
# Keep these functions side-effect free until called by a runner facade.

function Write-Qcl100SettingsFenceJsonFile {
    param([object]$Value, [string]$Path)
    $json = ($Value | ConvertTo-Json -Depth 16) + "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

function Invoke-Qcl100SettingsFenceAdbText {
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [string]$Path = ""
    )

    $output = & $Adb -s $Serial @Arguments 2>&1 | Out-String
    $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    if (-not [string]::IsNullOrWhiteSpace($Path)) {
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($Path, $output, $utf8NoBom)
    }

    return [ordered]@{
        exit_code = $exitCode
        output = $output.TrimEnd()
    }
}

function Get-Qcl100SettingsFenceCurrentFocus {
    param([string]$WindowDump)

    $focusMatch = [regex]::Match($WindowDump, "mCurrentFocus=([^\r\n]+)")
    if ($focusMatch.Success) {
        return $focusMatch.Groups[1].Value.Trim()
    }

    $focusedAppMatch = [regex]::Match($WindowDump, "mFocusedApp=([^\r\n]+)")
    if ($focusedAppMatch.Success) {
        return $focusedAppMatch.Groups[1].Value.Trim()
    }

    return ""
}

function Invoke-Qcl100SettingsFence {
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$OutDir,
        [Parameter(Mandatory=$true)][string]$RunId,
        [switch]$ClearLogcat,
        [switch]$RequireForegroundNotSettings
    )

    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

    $packages = @(
        "com.oculus.panelapp.settings",
        "com.android.settings"
    )
    $packageResults = @()
    foreach ($package in $packages) {
        $packageResults += [ordered]@{
            package = $package
            command = "am force-stop $package"
            result = Invoke-Qcl100SettingsFenceAdbText `
                -Adb $Adb `
                -Serial $Serial `
                -Arguments @("shell", "am", "force-stop", $package)
        }
    }

    $homeResult = Invoke-Qcl100SettingsFenceAdbText `
        -Adb $Adb `
        -Serial $Serial `
        -Arguments @("shell", "input", "keyevent", "HOME")

    Start-Sleep -Milliseconds 500

    $windowPath = Join-Path $OutDir "$Label-settings-fence-window.txt"
    $windowResult = Invoke-Qcl100SettingsFenceAdbText `
        -Adb $Adb `
        -Serial $Serial `
        -Arguments @("shell", "dumpsys", "window") `
        -Path $windowPath

    $currentFocus = Get-Qcl100SettingsFenceCurrentFocus -WindowDump ([string]$windowResult.output)
    $settingsSurfaceActive = [bool]($currentFocus -match "com\.oculus\.panelapp\.settings|com\.android\.settings")
    $foregroundNotSettings = [bool](-not $settingsSurfaceActive -and -not [string]::IsNullOrWhiteSpace($currentFocus))

    $logcatClearResult = $null
    if ($ClearLogcat) {
        $logcatClearResult = Invoke-Qcl100SettingsFenceAdbText `
            -Adb $Adb `
            -Serial $Serial `
            -Arguments @("logcat", "-c")
    }

    $adbCommandFailed = [bool](
        @($packageResults | Where-Object { [int]$_.result.exit_code -ne 0 }).Count -gt 0 -or
        [int]$homeResult.exit_code -ne 0 -or
        [int]$windowResult.exit_code -ne 0 -or
        ($ClearLogcat -and $null -ne $logcatClearResult -and [int]$logcatClearResult.exit_code -ne 0)
    )
    $passed = [bool](
        -not $adbCommandFailed -and
        ((-not $RequireForegroundNotSettings) -or $foregroundNotSettings)
    )
    $status = "pass"
    if ($adbCommandFailed) {
        $status = "failed_adb"
    } elseif (-not $passed) {
        $status = "blocked_foreground_settings"
    }

    $receiptPath = Join-Path $OutDir "$Label-settings-fence.json"
    $receipt = [ordered]@{
        schema = "rusty.quest.qcl100_settings_fence.v1"
        run_id = $RunId
        label = $Label
        serial = $Serial
        status = $status
        passed = $passed
        adb_scope = "device-scoped-adb"
        package_force_stops = $packageResults
        home_keyevent_sent = [bool]([int]$homeResult.exit_code -eq 0)
        home_keyevent_result = $homeResult
        window_dump_result = [ordered]@{
            exit_code = $windowResult.exit_code
            artifact = $windowPath
        }
        current_focus = $currentFocus
        settings_surface_active = $settingsSurfaceActive
        foreground_not_settings = $foregroundNotSettings
        require_foreground_not_settings = [bool]$RequireForegroundNotSettings
        logcat_cleared_after_fence = [bool]$ClearLogcat
        logcat_clear_result = $logcatClearResult
        no_wifi_mutation = $true
        media_started = $false
        qcl041_started = $false
        ready_for_qcl041_group_formation = $passed
        receipt_path = $receiptPath
    }
    Write-Qcl100SettingsFenceJsonFile -Value $receipt -Path $receiptPath
    return $receipt
}
