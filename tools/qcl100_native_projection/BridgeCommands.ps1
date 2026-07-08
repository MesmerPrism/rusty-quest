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

function Write-LiveBridgeCommandAttemptReceipt {
    param(
        [string]$Path,
        [object]$Receipt
    )
    try {
        Write-JsonFile -Value $Receipt -Path $Path
    } catch {
        # Attempt receipts are diagnostic breadcrumbs; command execution remains authoritative.
    }
}

function Get-LiveBridgeCommandExecutionIssueSummary {
    param(
        [string]$ExecutionPath
    )
    if (-not (Test-Path -LiteralPath $ExecutionPath)) {
        return ""
    }
    try {
        $execution = Get-Content -Raw -LiteralPath $ExecutionPath | ConvertFrom-Json
    } catch {
        return ""
    }
    $rows = @()
    foreach ($issue in @($execution.issues)) {
        if ($null -eq $issue) { continue }
        $code = [string]$issue.issue_code
        $message = [string]$issue.message
        if (-not [string]::IsNullOrWhiteSpace($code) -or -not [string]::IsNullOrWhiteSpace($message)) {
            $rows += ("{0}: {1}" -f $code, $message).Trim([char[]]": ")
        }
    }
    foreach ($issue in @($execution.command_execution.issues)) {
        if ($null -eq $issue) { continue }
        $code = [string]$issue.issue_code
        $message = [string]$issue.message
        if (-not [string]::IsNullOrWhiteSpace($code) -or -not [string]::IsNullOrWhiteSpace($message)) {
            $rows += ("{0}: {1}" -f $code, $message).Trim([char[]]": ")
        }
    }
    $rows = @($rows | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Select-Object -Unique)
    if ($rows.Count -eq 0) {
        return ""
    }
    return " Issues: " + ($rows -join " | ")
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
    $routePath = Join-Path $MediaDir "$Name-route.json"
    $executionPath = Join-Path $MediaDir "$Name-execution.json"
    $validationPath = Join-Path $MediaDir "$Name-validation.json"
    $logcatPath = Join-Path $MediaDir "$Name.logcat.txt"
    $attemptReceiptPath = Join-Path $MediaDir "$Name-live-command-attempt.json"
    $args = @(
        $HostessCtl,
        "run-bridge-command-live-android",
        "--input", $RequestPath,
        "--out", $routePath,
        "--execution-out", $executionPath,
        "--validation-out", $validationPath,
        "--logcat-out", $logcatPath,
        "--adb", $Adb,
        "--serial", $Serial,
        "--broker-local-port", $BrokerLocalPort.ToString(),
        "--broker-package", $BrokerPackage,
        "--no-launch-makepad",
        "--no-wait-makepad-process",
        "--socket-wait-seconds", "10",
        "--websocket-ready-wait-seconds", "12",
        "--launch-settle-seconds", "1",
        "--runtime-subscriber-retry-count", "3",
        "--runtime-subscriber-retry-wait-seconds", "1",
        "--wait-seconds", "10"
    )
    if ($NoLaunchBroker) {
        $args += @("--no-launch-broker", "--no-wait-broker-process")
    }
    $maxAttempts = [Math]::Max(1, $RetryCount)
    $effectiveTimeoutSeconds = [Math]::Max(0, $TimeoutSeconds)
    $attempts = @()
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        $attemptStartedAt = Get-Date
        $attemptReceipt = [ordered]@{
            schema = "rusty.quest.qcl100_live_bridge_command_attempt.v1"
            name = $Name
            serial = $Serial
            broker_local_port = $BrokerLocalPort
            request_path = $RequestPath
            route_path = $routePath
            execution_path = $executionPath
            validation_path = $validationPath
            logcat_path = $logcatPath
            stdout_path = if ($effectiveTimeoutSeconds -gt 0) { Join-Path $MediaDir "$Name-live-command.stdout.txt" } else { "" }
            stderr_path = if ($effectiveTimeoutSeconds -gt 0) { Join-Path $MediaDir "$Name-live-command.stderr.txt" } else { "" }
            timeout_seconds = $effectiveTimeoutSeconds
            attempt = $attempt
            retry_count = $maxAttempts
            retry_delay_ms = $RetryDelayMs
            no_launch_broker = [bool]$NoLaunchBroker
            allow_failure = [bool]$AllowFailure
            status = "running"
            started_at_utc = $attemptStartedAt.ToUniversalTime().ToString("o")
        }
        Write-LiveBridgeCommandAttemptReceipt -Path $attemptReceiptPath -Receipt $attemptReceipt
        try {
            if ($effectiveTimeoutSeconds -gt 0) {
                $stdoutPath = [string]$attemptReceipt.stdout_path
                $stderrPath = [string]$attemptReceipt.stderr_path
                $process = Start-Process `
                    -FilePath $Python `
                    -ArgumentList $args `
                    -RedirectStandardOutput $stdoutPath `
                    -RedirectStandardError $stderrPath `
                    -WindowStyle Hidden `
                    -PassThru
                if (-not $process.WaitForExit($effectiveTimeoutSeconds * 1000)) {
                    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                    throw "live bridge command $Name timed out after ${effectiveTimeoutSeconds}s"
                }
                $process.Refresh()
                $exitCode = $process.ExitCode
                if ($null -eq $exitCode) {
                    if (Test-Path -LiteralPath $validationPath) {
                        try {
                            $validation = Get-Content -Raw -LiteralPath $validationPath | ConvertFrom-Json
                            if ([string]$validation.status -eq "pass") {
                                $exitCode = 0
                            }
                        } catch {
                            $exitCode = $null
                        }
                    }
                }
                if ($null -eq $exitCode -or $exitCode -ne 0) {
                    $output = ""
                    if (Test-Path -LiteralPath $stdoutPath) {
                        $output += Get-Content -Raw $stdoutPath
                    }
                    if (Test-Path -LiteralPath $stderrPath) {
                        $output += Get-Content -Raw $stderrPath
                    }
                    $output += Get-LiveBridgeCommandExecutionIssueSummary -ExecutionPath $executionPath
                    throw "live bridge command $Name failed with exit code $exitCode. $output"
                }
            } else {
                Invoke-External -Name "live bridge command $Name" -File $Python -Arguments $args | Out-Null
            }
            $attemptEndedAt = Get-Date
            $attempts += [ordered]@{
                attempt = $attempt
                status = "pass"
            }
            $attemptReceipt["status"] = "pass"
            $attemptReceipt["ended_at_utc"] = $attemptEndedAt.ToUniversalTime().ToString("o")
            $attemptReceipt["elapsed_ms"] = [int][Math]::Ceiling(($attemptEndedAt - $attemptStartedAt).TotalMilliseconds)
            $attemptReceipt["execution_present"] = Test-Path -LiteralPath $executionPath
            $attemptReceipt["validation_present"] = Test-Path -LiteralPath $validationPath
            Write-LiveBridgeCommandAttemptReceipt -Path $attemptReceiptPath -Receipt $attemptReceipt
            return [ordered]@{
                name = $Name
                status = "pass"
                allowed_failure = [bool]$AllowFailure
                execution_path = $executionPath
                attempt_receipt_path = $attemptReceiptPath
                timeout_seconds = $effectiveTimeoutSeconds
                attempt_count = $attempt
                retry_count = $maxAttempts
                retry_delay_ms = $RetryDelayMs
                attempts = $attempts
            }
        } catch {
            $attemptEndedAt = Get-Date
            $attempts += [ordered]@{
                attempt = $attempt
                status = "fail"
                error = $_.Exception.Message
            }
            $attemptReceipt["status"] = "fail"
            $attemptReceipt["ended_at_utc"] = $attemptEndedAt.ToUniversalTime().ToString("o")
            $attemptReceipt["elapsed_ms"] = [int][Math]::Ceiling(($attemptEndedAt - $attemptStartedAt).TotalMilliseconds)
            $attemptReceipt["error"] = $_.Exception.Message
            $attemptReceipt["timed_out"] = [bool]($_.Exception.Message -match 'timed out after')
            $attemptReceipt["execution_present"] = Test-Path -LiteralPath $executionPath
            $attemptReceipt["validation_present"] = Test-Path -LiteralPath $validationPath
            Write-LiveBridgeCommandAttemptReceipt -Path $attemptReceiptPath -Receipt $attemptReceipt
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
                execution_path = $executionPath
                attempt_receipt_path = $attemptReceiptPath
                timeout_seconds = $effectiveTimeoutSeconds
                attempt_count = $attempt
                retry_count = $maxAttempts
                retry_delay_ms = $RetryDelayMs
                attempts = $attempts
            }
        }
    }
}
