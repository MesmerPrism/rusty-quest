# Dot-sourced helper functions for Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1.
# Keep these functions side-effect free until called by the runner facade.

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
        [switch]$NoLaunchBroker,
        [switch]$AllowFailure,
        [int]$TimeoutSeconds = 0,
        [int]$RetryCount = 1,
        [int]$RetryDelayMs = 1000
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
        "--broker-package", $BrokerPackage,
        "--no-launch-makepad",
        "--no-wait-makepad-process",
        "--socket-wait-seconds", "10",
        "--wait-seconds", "10"
    )
    if ($NoLaunchBroker) {
        $args += @("--no-launch-broker", "--no-wait-broker-process")
    }
    $maxAttempts = [Math]::Max(1, $RetryCount)
    $attempts = @()
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        try {
            if ($TimeoutSeconds -gt 0) {
                $stdoutPath = Join-Path $MediaDir "$Name-live-command.stdout.txt"
                $stderrPath = Join-Path $MediaDir "$Name-live-command.stderr.txt"
                $process = Start-Process `
                    -FilePath $Python `
                    -ArgumentList $args `
                    -RedirectStandardOutput $stdoutPath `
                    -RedirectStandardError $stderrPath `
                    -WindowStyle Hidden `
                    -PassThru
                if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
                    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                    throw "live bridge command $Name timed out after ${TimeoutSeconds}s"
                }
                if ($process.ExitCode -ne 0) {
                    $output = ""
                    if (Test-Path -LiteralPath $stdoutPath) {
                        $output += Get-Content -Raw $stdoutPath
                    }
                    if (Test-Path -LiteralPath $stderrPath) {
                        $output += Get-Content -Raw $stderrPath
                    }
                    throw "live bridge command $Name failed with exit code $($process.ExitCode). $output"
                }
            } else {
                Invoke-External -Name "live bridge command $Name" -File $Python -Arguments $args | Out-Null
            }
            $attempts += [ordered]@{
                attempt = $attempt
                status = "pass"
            }
            return [ordered]@{
                name = $Name
                status = "pass"
                allowed_failure = [bool]$AllowFailure
                execution_path = Join-Path $MediaDir "$Name-execution.json"
                timeout_seconds = $TimeoutSeconds
                attempt_count = $attempt
                retry_count = $maxAttempts
                retry_delay_ms = $RetryDelayMs
                attempts = $attempts
            }
        } catch {
            $attempts += [ordered]@{
                attempt = $attempt
                status = "fail"
                error = $_.Exception.Message
            }
            if ($attempt -lt $maxAttempts) {
                Start-Sleep -Milliseconds $RetryDelayMs
                continue
            }
            if (-not $AllowFailure) {
                throw
            }
            return [ordered]@{
                name = $Name
                status = "fail"
                allowed_failure = $true
                error = $_.Exception.Message
                execution_path = Join-Path $MediaDir "$Name-execution.json"
                timeout_seconds = $TimeoutSeconds
                attempt_count = $attempt
                retry_count = $maxAttempts
                retry_delay_ms = $RetryDelayMs
                attempts = $attempts
            }
        }
    }
}
