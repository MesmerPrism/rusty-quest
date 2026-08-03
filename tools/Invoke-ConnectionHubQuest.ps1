[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet(
        "Build", "Inspect", "Install", "Start", "Status", "Stop", "Forget",
        "LaunchProviders", "StopProviders", "HostessStatus", "HostessPair",
        "HostessList", "HostessWatch", "HostessCommand", "HostessReconnect",
        "HostessRevoke", "Logs", "Cleanup", "E2E", "SimulateE2E")]
    [string]$Action,

    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{3,63}$')]
    [string]$Serial,

    [string]$EvidenceRoot = "",
    [string]$FileManagerCli = "",
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$FileManagerSha256 = "",
    [string]$Gradle = "",
    [string]$Keystore = "",
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$ExpectedSignerSha256 = "",
    [string]$HubManifoldSourceRoot = "",
    [string]$SpatialManifoldSourceRoot = "",
    [string]$HubApk = "",
    [string]$SpatialProviderApk = "",
    [string]$SampleProviderApk = "",
    [string]$HostessCli = "",
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$HostessCliSha256 = "",
    [string]$Python = "python",
    [string]$Origin = "",
    [string]$SessionFile = "",
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$ControllerIdentitySha256 = "",
    [switch]$PairingCodeStdin,
    [int]$PairingCodeFd = -1,
    [string]$SurfaceId = "",
    [string]$CommandId = "",
    [ValidateRange(1,300)]
    [int]$WatchSeconds = 10,
    [ValidateRange(100,5000)]
    [int]$LogcatLines = 2000,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$HubPackage = "io.github.mesmerprism.rustymanifold.broker"
$HubActivity = "$HubPackage/.ConnectionHubStartActivity"
$HubDebugAuthority = "$HubPackage.debug-connection-hub-control"
$SpatialPackage = "io.github.mesmerprism.rustyquest.spatial_video_control_example"
$SpatialActivity = "$SpatialPackage/io.github.mesmerprism.rustyquest.spatial_video_control.SpatialVideoControlActivity"
$SamplePackage = "io.github.mesmerprism.rustyquest.connection_hub_sample"
$SampleActivity = "$SamplePackage/.ConnectionHubSampleActivity"
$QfmLaunchGap = "qfm-69b02f1.launch-export-parser"
$QfmServiceGap = "qfm-missing-typed-connection-hub-service-action-v1"
$QfmStopGap = "qfm-missing-typed-package-stop-v1"
$QfmLogGap = "qfm-missing-bounded-logcat-v1"
$ReceiptSchema = "rusty.quest.connection_hub.operator_receipt.v1"
$ManifestSchema = "rusty.quest.connection_hub.operator_evidence_manifest.v1"
$script:Receipts = [System.Collections.Generic.List[string]]::new()
$script:ProviderLocks = [System.Collections.Generic.List[System.IDisposable]]::new()
$script:HubStarted = $false
$script:ProvidersLaunched = $false
$script:HostessPaired = $false

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-ExactFile([string]$Path, [string]$ExpectedSha256, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label path is required and must name one file."
    }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $actual = Get-Sha256 $resolved
    if ($actual -ne $ExpectedSha256) {
        throw "$Label SHA-256 mismatch: expected $ExpectedSha256, observed $actual"
    }
    return $resolved
}

function Lock-ExactProvider([string]$Path, [string]$ExpectedSha256, [string]$Label) {
    $resolved = Assert-ExactFile $Path $ExpectedSha256 $Label
    $lock = [System.IO.File]::Open(
        $resolved,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read)
    [void]$script:ProviderLocks.Add($lock)
    return $resolved
}

function Write-JsonFile([string]$Path, $Value) {
    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $temporary = "$Path.tmp"
    [System.IO.File]::WriteAllText(
        $temporary,
        ($Value | ConvertTo-Json -Depth 30),
        (New-Object System.Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Save-Receipt([string]$Name, $Value) {
    $path = Join-Path $script:RunDir "$Name.json"
    Write-JsonFile $path $Value
    [void]$script:Receipts.Add($path)
    return $Value
}

function New-Receipt([string]$Operation, [string]$Provider, [string]$Status, $Details) {
    return [ordered]@{
        '$schema' = $ReceiptSchema
        operation = $Operation
        provider = $Provider
        serial = $Serial
        status = $Status
        observed_at_utc = [DateTime]::UtcNow.ToString("o")
        secrets_in_receipt = $false
        details = $Details
    }
}

function Invoke-Captured([string]$File, [string[]]$Arguments, [string]$Label) {
    $stderrPath = Join-Path ([System.IO.Path]::GetTempPath()) ("hub-cli-stderr-" + [Guid]::NewGuid().ToString("N"))
    try {
        $stdout = @(& $File @Arguments 2> $stderrPath)
        $exitCode = $LASTEXITCODE
        $stdoutText = $stdout -join "`n"
        $stderrText = if (Test-Path -LiteralPath $stderrPath) { [System.IO.File]::ReadAllText($stderrPath) } else { "" }
        return [ordered]@{
            label = $Label
            exit_code = $exitCode
            output = $stdoutText
            stderr = $stderrText
            combined = (($stdoutText, $stderrText) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join "`n"
        }
    } finally {
        if (Test-Path -LiteralPath $stderrPath) { [System.IO.File]::Delete($stderrPath) }
    }
}

function Invoke-Qfm([string[]]$Arguments, [string]$Label, [switch]$AllowFailure) {
    if ((Get-Sha256 $script:Qfm) -ne $FileManagerSha256) {
        throw "File Manager changed after the run lock was acquired."
    }
    $result = Invoke-Captured $script:Qfm $Arguments $Label
    if ($result.exit_code -ne 0 -and -not $AllowFailure) {
        throw "$Label failed with exit code $($result.exit_code): $($result.combined)"
    }
    $parsed = $null
    if (-not [string]::IsNullOrWhiteSpace($result.output)) {
        try { $parsed = $result.output | ConvertFrom-Json } catch { }
    }
    $result["json"] = $parsed
    return $result
}

function Invoke-Adb([string[]]$Arguments, [string]$GapId, [string]$Goal) {
    $adb = (Get-Command adb -ErrorAction Stop).Source
    $all = @("-s", $Serial) + $Arguments
    $result = Invoke-Captured $adb $all "serial-scoped ADB fallback"
    if ($result.exit_code -ne 0) {
        throw "ADB fallback failed for $Goal with exit code $($result.exit_code)."
    }
    return [ordered]@{
        provider = "raw-adb-fallback"
        provider_gap = $GapId
        goal = $Goal
        stop_condition = "one fixed action and fresh readback"
        cleanup = "target-package-only"
        command_shape = @("adb", "-s", "<explicit-serial>") + $Arguments
        exit_code = 0
        output = $result.output
        owner_acceptance_claimed = $false
    }
}

function Get-DebugPairingSecret {
    # This is deliberately separate from Invoke-Adb and Save-Receipt. The
    # one-use wearer code exists only in zeroed process buffers and is never
    # written to argv, a temporary file, a receipt, a log, or the manifest.
    $adb = (Get-Command adb -ErrorAction Stop).Source
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adb
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @(
        "-s", $Serial, "shell", "content", "call", "--uri",
        "content://$HubDebugAuthority", "--method", "pair-code")) {
        [void]$start.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    [char[]]$stdout = New-Object char[] 4096
    [char[]]$stderr = New-Object char[] 1024
    [byte[]]$decoded = $null
    try {
        if (-not $process.Start()) { throw "Unable to start the dedicated pairing-secret transport." }
        $stdoutCount = $process.StandardOutput.ReadBlock($stdout, 0, $stdout.Length)
        $stderrCount = $process.StandardError.ReadBlock($stderr, 0, $stderr.Length)
        $process.WaitForExit(12000)
        if (-not $process.HasExited) { $process.Kill($true); throw "Pairing-secret transport timed out." }
        if ($process.ExitCode -ne 0 -or $stderrCount -gt 0) { throw "Pairing-secret transport failed closed." }
        [char[]]$prefix = "secret_b64=".ToCharArray()
        $startIndex = -1
        for ($i = 0; $i -le $stdoutCount - $prefix.Length; $i++) {
            $equal = $true
            for ($j = 0; $j -lt $prefix.Length; $j++) {
                if ($stdout[$i + $j] -ne $prefix[$j]) { $equal = $false; break }
            }
            if ($equal) { $startIndex = $i + $prefix.Length; break }
        }
        if ($startIndex -lt 0) { throw "Pairing-secret field is missing." }
        $length = 0
        while ($startIndex + $length -lt $stdoutCount -and
                $stdout[$startIndex + $length] -match '[A-Za-z0-9+/=]') { $length++ }
        if ($length -lt 8 -or $length -gt 16) { throw "Pairing-secret encoding is out of bounds." }
        $decoded = [Convert]::FromBase64CharArray($stdout, $startIndex, $length)
        if ($decoded.Length -ne 6) { throw "Pairing-secret length is invalid." }
        [char[]]$secret = New-Object char[] 6
        for ($i = 0; $i -lt 6; $i++) {
            if ($decoded[$i] -lt 48 -or $decoded[$i] -gt 57) { throw "Pairing-secret alphabet is invalid." }
            $secret[$i] = [char]$decoded[$i]
        }
        return $secret
    } finally {
        if ($null -ne $decoded) { [Array]::Clear($decoded, 0, $decoded.Length) }
        [Array]::Clear($stdout, 0, $stdout.Length)
        [Array]::Clear($stderr, 0, $stderr.Length)
        $process.Dispose()
    }
}

function Stage-Apk([string]$Path, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label APK is missing: $Path" }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $sha = Get-Sha256 $resolved
    $target = Join-Path $script:RunDir "artifacts\$sha.apk"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
        Copy-Item -LiteralPath $resolved -Destination $target
    }
    if ((Get-Sha256 $target) -ne $sha) { throw "$Label staged APK digest mismatch." }
    (Get-Item -LiteralPath $target).IsReadOnly = $true
    return [ordered]@{ label = $Label; path = $target; sha256 = $sha; size = (Get-Item $target).Length }
}

function Resolve-Apks {
    $hub = if ($HubApk) { $HubApk } else { Join-Path $RepoRoot "target\connection-hub-debug\rusty-manifold-broker.apk" }
    $spatial = if ($SpatialProviderApk) { $SpatialProviderApk } else { Join-Path $RepoRoot "apps\spatial-video-control-example-android\app\build\outputs\apk\debug\app-debug.apk" }
    $sample = if ($SampleProviderApk) { $SampleProviderApk } else { Join-Path $RepoRoot "apps\spatial-video-control-example-android\hub-sample-provider\build\outputs\apk\debug\hub-sample-provider-debug.apk" }
    return @(
        Stage-Apk $hub "hub",
        Stage-Apk $spatial "spatial-provider",
        Stage-Apk $sample "sample-provider")
}

function Build-All {
    if (-not (Test-Path -LiteralPath $HubManifoldSourceRoot -PathType Container)) {
        throw "-HubManifoldSourceRoot must identify the exact clean Hub Manifold source."
    }
    if (-not (Test-Path -LiteralPath $SpatialManifoldSourceRoot -PathType Container)) {
        throw "-SpatialManifoldSourceRoot must identify the exact clean spatial-control Manifold source."
    }
    if (-not (Test-Path -LiteralPath $Gradle -PathType Leaf)) { throw "-Gradle is required." }
    if (-not (Test-Path -LiteralPath $Keystore -PathType Leaf)) {
        throw "-Keystore must identify the one explicit signing keystore shared by all three APKs."
    }
    $resolvedKeystore = (Resolve-Path -LiteralPath $Keystore).Path
    $version = Invoke-Captured $Gradle @("--version") "Gradle version"
    if ($version.exit_code -ne 0 -or $version.output -notmatch 'Gradle 8\.13') {
        throw "Connection Hub build requires exact Gradle 8.13."
    }
    $hubRoot = (Resolve-Path -LiteralPath $HubManifoldSourceRoot).Path
    $spec = Join-Path $hubRoot "fixtures\broker-product\connection-hub-standalone.json"
    $lock = Join-Path $hubRoot "fixtures\broker-product\connection-hub-standalone.lock.json"
    & (Join-Path $RepoRoot "tools\Build-ManifoldBrokerAndroid.ps1") `
        -OutDir (Join-Path $RepoRoot "target\connection-hub-debug") `
        -ProductSpecPath $spec `
        -ProductLockPath $lock `
        -ManifoldSourceRoot $hubRoot `
        -Keystore $resolvedKeystore `
        -EnableConnectionHubDebugOperator
    if ($LASTEXITCODE -ne 0) { throw "Hub APK build failed." }
    $previousManifold = $env:RUSTY_MANIFOLD_SOURCE_ROOT
    $previousConnectionHubKeystore = $env:RUSTY_CONNECTION_HUB_KEYSTORE
    try {
        $env:RUSTY_MANIFOLD_SOURCE_ROOT = (Resolve-Path -LiteralPath $SpatialManifoldSourceRoot).Path
        $env:RUSTY_CONNECTION_HUB_KEYSTORE = $resolvedKeystore
        Push-Location (Join-Path $RepoRoot "apps\spatial-video-control-example-android")
        try {
            & $Gradle :app:assembleDebug :hub-sample-provider:assembleDebug --no-daemon
            if ($LASTEXITCODE -ne 0) { throw "Provider APK build failed." }
        } finally { Pop-Location }
    } finally {
        $env:RUSTY_MANIFOLD_SOURCE_ROOT = $previousManifold
        $env:RUSTY_CONNECTION_HUB_KEYSTORE = $previousConnectionHubKeystore
    }
    $manifest = Get-Content -Raw (Join-Path $RepoRoot "target\connection-hub-debug\build-manifest.json") | ConvertFrom-Json
    if ($manifest.connection_hub_debug_operator -ne $true) { throw "Debug Hub build omitted its shell operator route." }
    return Save-Receipt "build" (New-Receipt "build" "project-build" "passed" ([ordered]@{
        hub_build_manifest_sha256 = Get-Sha256 (Join-Path $RepoRoot "target\connection-hub-debug\build-manifest.json")
        gradle_version = "8.13"
        keystore_sha256 = Get-Sha256 $resolvedKeystore
        device_touched = $false
    }))
}

function Inspect-All($Artifacts) {
    $rows = @()
    $signers = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($artifact in $Artifacts) {
        $response = Invoke-Qfm @("apk", "inspect", "--file", $artifact.path, "--json") "inspect $($artifact.label)"
        if ($null -eq $response.json -or ([string]$response.json.Sha256).ToLowerInvariant() -ne $artifact.sha256) {
            throw "File Manager inspection did not bind $($artifact.label) to its staged SHA-256."
        }
        $signer = ([string]$response.json.Identity.SignerSha256).ToLowerInvariant()
        if ($signer -notmatch '^[0-9a-f]{64}$') { throw "File Manager returned an invalid signer for $($artifact.label)." }
        [void]$signers.Add($signer)
        $rows += [ordered]@{ label=$artifact.label; sha256=$artifact.sha256; signer_sha256=$signer; identity=$response.json.Identity }
    }
    if ($signers.Count -ne 1) { throw "Signature Binder permission would fail: the three APK signers differ." }
    $sharedSigner = @($signers)[0]
    if (-not [string]::IsNullOrWhiteSpace($ExpectedSignerSha256) -and $sharedSigner -ne $ExpectedSignerSha256) {
        throw "Shared APK signer does not match -ExpectedSignerSha256."
    }
    return Save-Receipt "inspect" (New-Receipt "inspect" "questionable-file-manager" "passed" $rows)
}

function Install-All($Artifacts) {
    $rows = @()
    foreach ($artifact in $Artifacts) {
        $install = Invoke-Qfm @("apk", "install", "--serial", $Serial, "--file", $artifact.path, "--downgrade", "--grant-runtime-permissions", "--json") "install $($artifact.label)"
        $observe = Invoke-Qfm @("apk", "observe", "--serial", $Serial, "--file", $artifact.path, "--json") "observe $($artifact.label)"
        if ($observe.output.ToLowerInvariant().IndexOf($artifact.sha256) -lt 0) {
            throw "Installed-byte readback did not repeat $($artifact.label) SHA-256."
        }
        $rows += [ordered]@{ label=$artifact.label; sha256=$artifact.sha256; install=$install.json; observe=$observe.json }
    }
    return Save-Receipt "install" (New-Receipt "install" "questionable-file-manager" "passed" $rows)
}

function Launch-Apk($Artifact, [string]$Component) {
    $launch = Invoke-Qfm @("apk", "launch", "--serial", $Serial, "--file", $Artifact.path, "--json") "launch $($Artifact.label)" -AllowFailure
    if ($launch.exit_code -eq 0) {
        return [ordered]@{ label=$Artifact.label; provider="questionable-file-manager"; receipt=$launch.json }
    }
    if ($launch.combined -notmatch 'resolved launcher activity was not proven exported') {
        throw "File Manager launch failed outside the reviewed export-parser gap: $($launch.combined)"
    }
    $fallback = Invoke-Adb @("shell", "am", "start", "-W", "-n", $Component) $QfmLaunchGap "launch one fixed reviewed component"
    return [ordered]@{ label=$Artifact.label; provider="raw-adb-fallback"; fallback=$fallback }
}

function Invoke-DebugOperator([string]$Method) {
    $fallback = Invoke-Adb @(
        "shell", "content", "call", "--uri", "content://$HubDebugAuthority", "--method", $Method) `
        $QfmServiceGap "invoke one DUMP-protected debug Hub method"
    $match = [regex]::Match($fallback.output, 'receipt_b64=([A-Za-z0-9+/=]+)')
    if (-not $match.Success) { throw "Hub debug operator receipt is missing." }
    $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($match.Groups[1].Value)) | ConvertFrom-Json
    if ([string]$json.'$schema' -ne "rusty.quest.connection_hub.debug_operator_receipt.v1" -or $json.pairing_secret_in_receipt -ne $false) {
        throw "Hub debug operator receipt failed schema or secret-redaction validation."
    }
    return [ordered]@{ provider_gap=$QfmServiceGap; owner_receipt=$json }
}

function Hub-Action([string]$Method, $HubArtifact = $null) {
    if ($Method -eq "start" -and $null -ne $HubArtifact) { [void](Launch-Apk $HubArtifact $HubActivity) }
    $result = Invoke-DebugOperator $Method
    if ($Method -eq "start") {
        $deadline = [DateTime]::UtcNow.AddSeconds(12)
        while ($result.owner_receipt.listener_running -ne $true -and [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 400
            $result = Invoke-DebugOperator "status"
        }
        if ($result.owner_receipt.listener_running -ne $true) { throw "Hub listener did not confirm running." }
    } elseif ($Method -eq "stop" -and $result.owner_receipt.listener_running -ne $false) {
        throw "Hub listener stop was not confirmed."
    } elseif ($Method -eq "forget" -and $result.owner_receipt.applied -ne $true) {
        throw "Hub forget was not applied by Manifold."
    }
    if ($Method -eq "start") { $script:HubStarted = $true }
    if ($Method -eq "stop") { $script:HubStarted = $false }
    return Save-Receipt "hub-$Method" (New-Receipt "hub-$Method" "debug-shell-provider-gap" "passed" $result)
}

function Launch-Providers($Artifacts) {
    $rows = @(
        Launch-Apk $Artifacts[1] $SpatialActivity,
        Launch-Apk $Artifacts[2] $SampleActivity)
    $script:ProvidersLaunched = $true
    return Save-Receipt "launch-providers" (New-Receipt "launch-providers" "qfm-with-reviewed-fallback" "passed" $rows)
}

function Stop-Providers {
    $rows = @()
    foreach ($package in @($SpatialPackage, $SamplePackage)) {
        $rows += Invoke-Adb @("shell", "am", "force-stop", $package) $QfmStopGap "stop one fixed provider package"
    }
    $script:ProvidersLaunched = $false
    return Save-Receipt "stop-providers" (New-Receipt "stop-providers" "raw-adb-fallback" "passed" $rows)
}

function Invoke-Hostess([string]$Verb, [string[]]$Arguments, [string]$ReceiptName) {
    if ((Get-Sha256 $script:Hostess) -ne $HostessCliSha256) { throw "Hostess CLI changed after run lock." }
    $result = Invoke-Captured $Python (@($script:Hostess, $Verb) + $Arguments) "Hostess $Verb"
    if ($result.exit_code -ne 0) { throw "Hostess $Verb failed: $($result.combined)" }
    $json = $result.output | ConvertFrom-Json
    if ($result.output -match '(?i)pairing_code|bearer_token' -and $result.output -notmatch 'secrets_in_receipt') {
        throw "Hostess output may contain an unredacted secret."
    }
    return Save-Receipt $ReceiptName (New-Receipt "hostess-$Verb" "rusty-hostess" "passed" $json)
}

function Invoke-HostessPairWithSecret([char[]]$Secret) {
    if ((Get-Sha256 $script:Hostess) -ne $HostessCliSha256) { throw "Hostess CLI changed after run lock." }
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Python
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @(
        $script:Hostess, "pair", "--origin", $Origin,
        "--transport-classification", "trusted_lan_experimental",
        "--allow-insecure-trusted-lan", "--pairing-code-stdin",
        "--controller-identity-sha256", $ControllerIdentitySha256,
        "--session-file", $SessionFile)) {
        [void]$start.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw "Unable to start Hostess pairing." }
        foreach ($character in $Secret) { $process.StandardInput.Write($character) }
        $process.StandardInput.WriteLine()
        $process.StandardInput.Close()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit(30000)
        if (-not $process.HasExited) { $process.Kill($true); throw "Hostess pairing timed out." }
        if ($process.ExitCode -ne 0) { throw "Hostess pair failed: $stderr" }
        if ($stdout -match '"pairing_code"\s*:' -or $stdout -match '"bearer_token"\s*:') {
            throw "Hostess pair emitted a forbidden secret field."
        }
        $json = $stdout | ConvertFrom-Json
        if ($json.session_redacted -ne $true -or $null -ne $json.server_receipt.session) {
            throw "Hostess pair did not attest a redacted session receipt."
        }
        $script:HostessPaired = $true
        return Save-Receipt "hostess-pair" (New-Receipt "hostess-pair" "rusty-hostess" "passed" $json)
    } finally {
        [Array]::Clear($Secret, 0, $Secret.Length)
        $process.Dispose()
    }
}

function Hostess-Action([string]$Kind) {
    if ($Kind -eq "status") {
        return Invoke-Hostess "status" @("--origin", $Origin, "--transport-classification", "trusted_lan_experimental", "--allow-insecure-trusted-lan") "hostess-status"
    }
    if ($Kind -eq "pair") {
        $args = @("--origin", $Origin, "--transport-classification", "trusted_lan_experimental", "--allow-insecure-trusted-lan", "--controller-identity-sha256", $ControllerIdentitySha256, "--session-file", $SessionFile)
        if ($PairingCodeStdin) { $args += "--pairing-code-stdin" }
        elseif ($PairingCodeFd -ge 0) { $args += @("--pairing-code-fd", [string]$PairingCodeFd) }
        $receipt = Invoke-Hostess "pair" $args "hostess-pair"
        $script:HostessPaired = $true
        return $receipt
    }
    if ($Kind -eq "list") { return Invoke-Hostess "list-surfaces" @("--session-file", $SessionFile) "hostess-list" }
    if ($Kind -eq "watch") { return Invoke-Hostess "connect-watch" @("--session-file", $SessionFile, "--seconds", [string]$WatchSeconds, "--max-events", "128") "hostess-watch" }
    if ($Kind -eq "reconnect") { return Invoke-Hostess "list-surfaces" @("--session-file", $SessionFile) "hostess-reconnect" }
    if ($Kind -eq "revoke") {
        $receipt = Invoke-Hostess "revoke" @("--session-file", $SessionFile) "hostess-revoke"
        $script:HostessPaired = $false
        return $receipt
    }
    if ($Kind -eq "command") {
        $allowed = @{
            "surface.spatial_video_control.media" = @("command.spatial_video_control.pause", "command.spatial_video_control.play", "command.spatial_video_control.select_next", "command.spatial_video_control.select_previous")
            "surface.connection_hub_sample.toggle" = @("command.connection_hub_sample.toggle")
        }
        if (-not $allowed.ContainsKey($SurfaceId) -or $allowed[$SurfaceId] -notcontains $CommandId) {
            throw "The requested surface/command pair is not in the fixed Connection Hub registry."
        }
        return Invoke-Hostess "invoke-surface-command" @("--session-file", $SessionFile, "--surface-id", $SurfaceId, "--command", $CommandId, "--args-json", "{}") "hostess-command"
    }
    throw "Unsupported Hostess action."
}

function Capture-Logs {
    $fallback = Invoke-Adb @("logcat", "-d", "-v", "threadtime", "-t", [string]$LogcatLines) $QfmLogGap "bounded logcat and fatal scan"
    $path = Join-Path $script:RunDir "logcat.txt"
    [System.IO.File]::WriteAllText($path, $fallback.output, (New-Object System.Text.UTF8Encoding($false)))
    $patterns = @("FATAL EXCEPTION", "AndroidRuntime E", "UnsatisfiedLinkError")
    $hits = @($patterns | Where-Object { $fallback.output.Contains($_, [StringComparison]::OrdinalIgnoreCase) })
    $receipt = New-Receipt "logs" "raw-adb-fallback" $(if($hits.Count -eq 0){"passed"}else{"failed"}) ([ordered]@{
        provider_gap=$QfmLogGap; bounded_lines=$LogcatLines; log_sha256=Get-Sha256 $path; fatal_patterns=$hits })
    [void](Save-Receipt "logs" $receipt)
    if ($hits.Count -ne 0) { throw "Bounded fatal scan found: $($hits -join ', ')" }
    return $receipt
}

function Write-EvidenceManifest([string]$Result) {
    $entries = @($script:Receipts | Sort-Object | ForEach-Object {
        [ordered]@{ name=(Split-Path -Leaf $_); sha256=Get-Sha256 $_; size=(Get-Item $_).Length }
    })
    $manifest = [ordered]@{
        '$schema' = $ManifestSchema
        action = $Action
        serial = $Serial
        result = $Result
        generated_at_utc = [DateTime]::UtcNow.ToString("o")
        receipts = $entries
        cleanup = [ordered]@{ target_packages_only=$true; uninstall_performed=$false; adb_transport_changed=$false }
        secrets_in_manifest = $false
    }
    $path = Join-Path $script:RunDir "evidence-manifest.json"
    Write-JsonFile $path $manifest
    return $path
}

function New-DryRunPlan {
    return [ordered]@{
        '$schema' = "rusty.quest.connection_hub.operator_plan.v1"
        action = $Action
        serial = $Serial
        mutates_device = $Action -notin @("Build", "Inspect", "HostessStatus", "SimulateE2E")
        qfm_first = $true
        qfm_exact_sha256_required = $true
        all_apk_signers_must_match_before_install = $true
        reviewed_fallbacks = @($QfmLaunchGap, $QfmServiceGap, $QfmStopGap, $QfmLogGap)
        hostess_secret_input = @("debug-shell-to-stdin-memory-only", "hidden-prompt", "stdin", "inherited-fd", "DPAPI-CurrentUser-session")
        e2e_sequence = @("build", "inspect", "install+installed-byte-readback", "start-hub", "launch-two-providers", "pair", "list", "two-typed-commands", "reconnect", "watch", "bounded-fatal-scan", "revoke", "target-only-cleanup")
        secrets_in_plan = $false
    }
}

if ($DryRun) {
    New-DryRunPlan | ConvertTo-Json -Depth 10
    exit 0
}

if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) { throw "-EvidenceRoot is required outside dry-run mode." }
$resolvedEvidenceRoot = [System.IO.Path]::GetFullPath($EvidenceRoot).TrimEnd('\')
if ($resolvedEvidenceRoot.StartsWith($RepoRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "EvidenceRoot must be outside the source checkout."
}
New-Item -ItemType Directory -Force -Path $resolvedEvidenceRoot | Out-Null
$runName = "connection-hub-{0}-{1}" -f $Action.ToLowerInvariant(), ([DateTime]::UtcNow.ToString("yyyyMMddTHHmmssfffZ"))
$script:RunDir = Join-Path $resolvedEvidenceRoot $runName
New-Item -ItemType Directory -Path $script:RunDir | Out-Null
if ([string]::IsNullOrWhiteSpace($SessionFile)) { $SessionFile = Join-Path $script:RunDir "hostess-session.json" }

$needsQfm = $Action -in @("Inspect", "Install", "Start", "Status", "LaunchProviders", "E2E")
$needsHostess = $Action -like "Hostess*" -or $Action -eq "E2E"
if ($needsQfm) { $script:Qfm = Lock-ExactProvider $FileManagerCli $FileManagerSha256 "File Manager CLI" }
if ($needsHostess) { $script:Hostess = Lock-ExactProvider $HostessCli $HostessCliSha256 "Hostess CLI" }

$finalResult = "failed"
try {
    if ($Action -eq "SimulateE2E") {
        foreach ($name in @("build", "inspect", "install", "hub-start", "providers", "pair", "list", "commands", "reconnect", "watch", "logs", "revoke", "cleanup")) {
            [void](Save-Receipt "simulated-$name" (New-Receipt $name "deterministic-simulation" "passed" ([ordered]@{device_touched=$false})))
        }
    } elseif ($Action -eq "Build") { [void](Build-All)
    } elseif ($Action -eq "Inspect") { $a=Resolve-Apks; [void](Inspect-All $a)
    } elseif ($Action -eq "Install") { $a=Resolve-Apks; [void](Inspect-All $a); [void](Install-All $a)
    } elseif ($Action -eq "Start") { $a=Resolve-Apks; [void](Hub-Action "start" $a[0])
    } elseif ($Action -eq "Status") { $a=Resolve-Apks; [void](Invoke-Qfm @("apk","observe","--serial",$Serial,"--file",$a[0].path,"--json") "observe hub"); [void](Hub-Action "status")
    } elseif ($Action -eq "Stop") { [void](Hub-Action "stop")
    } elseif ($Action -eq "Forget") { [void](Hub-Action "forget")
    } elseif ($Action -eq "LaunchProviders") { $a=Resolve-Apks; [void](Launch-Providers $a)
    } elseif ($Action -eq "StopProviders") { [void](Stop-Providers)
    } elseif ($Action -eq "HostessStatus") { [void](Hostess-Action "status")
    } elseif ($Action -eq "HostessPair") { [void](Hostess-Action "pair")
    } elseif ($Action -eq "HostessList") { [void](Hostess-Action "list")
    } elseif ($Action -eq "HostessWatch") { [void](Hostess-Action "watch")
    } elseif ($Action -eq "HostessCommand") { [void](Hostess-Action "command")
    } elseif ($Action -eq "HostessReconnect") { [void](Hostess-Action "reconnect")
    } elseif ($Action -eq "HostessRevoke") { [void](Hostess-Action "revoke")
    } elseif ($Action -eq "Logs") { [void](Capture-Logs)
    } elseif ($Action -eq "Cleanup") { [void](Stop-Providers); [void](Hub-Action "stop")
    } elseif ($Action -eq "E2E") {
        [void](Build-All)
        $a=Resolve-Apks
        [void](Inspect-All $a); [void](Install-All $a); [void](Hub-Action "start" $a[0]); [void](Launch-Providers $a)
        if ([string]::IsNullOrWhiteSpace($Origin)) { $Origin = [string](Invoke-DebugOperator "status").owner_receipt.origin }
        [void](Hostess-Action "status")
        [char[]]$pairingSecret = Get-DebugPairingSecret
        try { [void](Invoke-HostessPairWithSecret $pairingSecret) }
        finally { [Array]::Clear($pairingSecret, 0, $pairingSecret.Length); $pairingSecret = $null }
        [void](Hostess-Action "list")
        $SurfaceId="surface.spatial_video_control.media"; $CommandId="command.spatial_video_control.play"; [void](Hostess-Action "command")
        $SurfaceId="surface.connection_hub_sample.toggle"; $CommandId="command.connection_hub_sample.toggle"; [void](Hostess-Action "command")
        [void](Hostess-Action "reconnect"); [void](Hostess-Action "watch"); [void](Capture-Logs); [void](Hostess-Action "revoke")
        [void](Stop-Providers); [void](Hub-Action "stop")
    }
    $finalResult = "passed"
} finally {
    if ($Action -eq "E2E" -and $finalResult -ne "passed") {
        $cleanupErrors = [System.Collections.Generic.List[string]]::new()
        if ($script:HostessPaired -and (Test-Path -LiteralPath $SessionFile -PathType Leaf)) {
            try { [void](Hostess-Action "revoke") } catch { [void]$cleanupErrors.Add("hostess_revoke_failed") }
        }
        if ($script:ProvidersLaunched) {
            try { [void](Stop-Providers) } catch { [void]$cleanupErrors.Add("provider_stop_failed") }
        }
        if ($script:HubStarted) {
            try { [void](Hub-Action "stop") } catch { [void]$cleanupErrors.Add("hub_stop_failed") }
        }
        [void](Save-Receipt "failure-cleanup" (New-Receipt "failure-cleanup" "operator-wrapper" $(if($cleanupErrors.Count -eq 0){"passed"}else{"partial"}) ([ordered]@{
            attempted = $true
            errors = @($cleanupErrors)
            target_packages_only = $true
        })))
    }
    $manifestPath = Write-EvidenceManifest $finalResult
    foreach ($lock in $script:ProviderLocks) { $lock.Dispose() }
    [ordered]@{
        '$schema' = "rusty.quest.connection_hub.operator_run.v1"
        action = $Action
        serial = $Serial
        result = $finalResult
        evidence_manifest = $manifestPath
        secrets_in_output = $false
    } | ConvertTo-Json -Depth 8
}
