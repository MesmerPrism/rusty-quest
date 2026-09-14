param([string]$RepoRoot = ".")

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$buildPath = Join-Path $repo "tools\Build-ManifoldBrokerAndroid.ps1"
$staticPath = Join-Path $repo "tools\checks\Test-ManifoldBrokerCompatibilityDiagnosticStatic.ps1"
$devicePath = Join-Path $repo "tools\Invoke-ManifoldBrokerCompatibilityDiagnostic.ps1"
$clientPath = Join-Path $repo "fixtures\broker-clients\spatial-camera-panel.client.json"
$lifecyclePath = Join-Path $repo "fixtures\broker-clients\spatial-camera-panel.media-lifecycle.json"
$bindingPaths = @(
    (Join-Path $repo "fixtures\media-runtime-products\camera2-surface.binding.json"),
    (Join-Path $repo "fixtures\media-runtime-products\spatial-camera-panel-display.binding.json"))
foreach ($path in @($buildPath, $staticPath, $devicePath, $clientPath, $lifecyclePath) +
        $bindingPaths) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Supplier specialization source is missing: $path"
    }
}

$build = Get-Content -Raw -LiteralPath $buildPath
function Require-Build([string]$Pattern, [string]$Message) {
    if ($build -cnotmatch $Pattern) { throw $Message }
}
foreach ($requirement in @(
    @('\$SpatialCameraPanelPackageName\s*=\s*""',
      "Build script omits the optional Spatial package parameter."),
    @('New-SpecializedSpatialCameraPanelClientInput',
      "Build script omits the isolated client-lock specialization."),
    @('Spatial Camera Panel package specialization requires the legacy compatibility product, remote-camera debug operator, and shared signer gate\.',
      "Specialization is not closed over product, debug-provider, and signer gates."),
    @('Legacy compatibility package builds require an explicit clean -ManifoldSourceRoot\.',
      "Legacy package builds do not require an explicit Manifold source root."),
    @('status --porcelain --untracked-files=all',
      "Explicit Manifold source is not checked for tracked cleanliness."),
    @('camera2-surface\.binding\.json',
      "Camera2 media binding is not fixed in the supplier closure."),
    @('spatial-camera-panel-display\.binding\.json',
      "Spatial display media binding is not fixed in the supplier closure."),
    @('spatial_camera_panel_package_specialized',
      "Build manifest omits specialization status."),
    @('spatial_camera_panel_client_lock_sha256',
      "Build manifest omits the specialized client-lock digest."),
    @('spatial_camera_panel_media_lifecycle_sha256',
      "Build manifest omits the specialized lifecycle digest."),
    @('inspected-same-version-replace-with-installed-byte-readback',
      "Build manifest omits the fixed inspected-install policy."),
    @('same-version-same-signer-rollback-reinstall',
      "Build manifest omits the fixed rollback policy."))) {
    Require-Build $requirement[0] $requirement[1]
}

$client = Get-Content -Raw -LiteralPath $clientPath | ConvertFrom-Json
$lifecycle = Get-Content -Raw -LiteralPath $lifecyclePath | ConvertFrom-Json
if ([string]$client.schema -cne "rusty.quest.broker_client_spec.v1" -or
    [string]$client.client_id -cne "client.quest.spatial-camera-panel" -or
    [string]$client.package_name -cne
        "io.github.mesmerprism.rustyquest.spatial_camera_panel" -or
    [string]$lifecycle.client_id -cne [string]$client.client_id -or
    [string]$lifecycle.package_name -cne [string]$client.package_name -or
    [string]$lifecycle.media_binding_path -cne
        "fixtures/media-runtime-products/spatial-camera-panel-display.binding.json") {
    throw "Spatial Camera Panel baseline client/lifecycle fixtures drifted."
}
foreach ($bindingPath in $bindingPaths) {
    $binding = Get-Content -Raw -LiteralPath $bindingPath | ConvertFrom-Json
    if ([string]$binding.manifold.'$schema' -cne
            "rusty.manifold.media.session_product_binding.v1" -or
        [string]$binding.quest.'$schema' -cne
            "rusty.quest.media_stream_runtime_product_binding.v1" -or
        [string]$binding.manifold.descriptor.payload_plane -cne "binary-media" -or
        $binding.manifold.descriptor.inline_media_payloads_allowed -isnot [bool] -or
        $binding.manifold.descriptor.inline_media_payloads_allowed) {
        throw "Supplier media binding is not an exact low-rate/binary-media boundary: $bindingPath"
    }
}

foreach ($scriptPath in @($buildPath, $staticPath, $devicePath)) {
    $tokens = $null
    $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile(
        $scriptPath, [ref]$tokens, [ref]$errors)
    if ($errors.Count -ne 0) {
        throw "PowerShell syntax is invalid: $scriptPath"
    }
}

$deviceSource = Get-Content -Raw -LiteralPath $devicePath
foreach ($token in @(
    'StaticGateReceipt', 'FeatureLockSha256', 'FeatureLockRevision',
    'FeatureLockFingerprint', 'FileManagerSha256', 'AdbSha256',
    'apk", "inspect"', 'apk", "install"', 'apk", "observe"',
    'authority-status', 'exact_prior_broker_bytes_restored')) {
    if ($deviceSource -cnotmatch [regex]::Escape($token)) {
        throw "Compatibility diagnostic omits required token: $token"
    }
}
$tokens = $null
$errors = $null
$deviceAst = [Management.Automation.Language.Parser]::ParseFile(
    $devicePath, [ref]$tokens, [ref]$errors)
$forbidden = '(?i)(\buninstall\b|\bpm\s+clear\b|\blogcat\b[^\r\n]*\s-c\b|--downgrade\b|\s-d\b|\bkill-server\b|\bstart-server\b|\breconnect\b|\btcpip\b|\bdisconnect\b)'
$unsafe = @($deviceAst.FindAll({
    param($node)
    $node -is [Management.Automation.Language.CommandAst] -and
        ($node.Extent.Text -match $forbidden -or
         ($node.Extent.Text -match '(?i)\bforce-stop\b' -and
          $node.Extent.Text -cnotmatch '\$ExpectedPackageName'))
}, $true))
if ($unsafe.Count -ne 0) {
    throw "Compatibility diagnostic contains a prohibited device operation."
}

# Execute the production try/catch/finally with host fakes at its device boundary.
# This exercises the actual install-attempt guard and failure retention, rather
# than a second policy implementation that the device runner never calls.
$tokens = $null
$parseErrors = $null
$diagnosticAst = [Management.Automation.Language.Parser]::ParseFile(
    $devicePath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Compatibility diagnostic does not parse.' }
$mainTries = @($diagnosticAst.FindAll({ param($node)
    $node -is [Management.Automation.Language.TryStatementAst] -and
    $null -ne $node.Finally -and
    $node.Finally.Extent.Text.Contains('$candidateInstallAttempted')
}, $true))
if ($mainTries.Count -ne 1) { throw 'Expected one production install/rollback boundary.' }
$fakeCommands = @('Invoke-Adb', 'Invoke-QfmWithArtifactLock', 'Assert-QfmObservation',
    'Assert-QfmInstallMutation', 'Get-DeviceSnapshot', 'Assert-SnapshotPreserved',
    'Wait-DevicePropertyValue', 'ConvertFrom-Json')
foreach ($command in @($mainTries[0].FindAll({ param($node)
    $node -is [Management.Automation.Language.CommandAst]
}, $true))) {
    if ($fakeCommands -cnotcontains $command.GetCommandName()) {
        throw 'Production rollback test encountered a command outside its host-only boundary.'
    }
}
$productionTry = $mainTries[0].Extent.Text
function Test-ProductionBrokerRollback([hashtable]$Case, [string]$Source) {
    $calls = [Collections.Generic.List[string]]::new()
    $candidateInstallAttempted = $false
    $candidateBytesConfirmed = $false
    $rollbackRestored = $false
    $brokerProcessRestorationAction = 'not-needed'
    $before = $null; $after = $null; $failure = $null
    $candidateObserve = $null; $rollbackObserve = $null
    $candidateApk = 'host-test-candidate.apk'; $rollbackApk = 'host-test-prior.apk'
    $rollbackApkSize = 1; $ExpectedVersionName = 'test'
    $ExpectedPackageName = 'host.test.broker'; $ProviderAuthority = 'host.test.provider'
    $Serial = 'HOST-ONLY'; $script:Adb = 'host-fake'
    $build = @{apk_sha256=('a' * 64); apk_size=1}
    $rollback = @{rollback_apk=@{sha256=('b' * 64); actual_version_name='test'}}
    function Invoke-Adb([string[]]$Arguments, [string]$Label) {
        $calls.Add($Label)
        if ($Label -ceq 'exact device discovery') {
            if ($Case.phase -ceq 'before-install') { throw 'discovery-failed' }
            return @{output='device'}
        }
        if ($Label -ceq 'exact broker-only inactive-process cleanup' -and
            $Case.rollback -ceq 'process') { throw 'rollback-process-failed' }
        if ($Label -ceq 'bounded remote-camera authority status') {
            $receipt = @{'$schema'='rusty.quest.remote_camera.debug_operator_receipt.v1';
                action='authority-status'; applied=$true;
                authority_status=@{provider_epoch_id='host.test.epoch'}} | ConvertTo-Json -Compress
            return @{output=('receipt_b64=' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($receipt)))}
        }
        return @{output='ok'}
    }
    function Invoke-QfmWithArtifactLock([string[]]$Arguments, [string]$Label,
        [string]$ArtifactPath, [string]$ArtifactSha256, [switch]$AllowFailure) {
        $calls.Add($Label)
        if ($Label -ceq 'inspected same-version candidate install' -and
            $Case.phase -ceq 'candidate-install') { throw 'candidate-install-uncertain' }
        $valid = -not (
            ($Label -ceq 'candidate installed-byte readback' -and $Case.phase -ceq 'candidate-readback') -or
            ($Label -ceq 'same-version rollback restore' -and $Case.rollback -ceq 'install') -or
            ($Label -ceq 'rollback installed-byte readback' -and $Case.rollback -ceq 'readback'))
        return @{valid=$valid; label=$Label}
    }
    function Assert-QfmObservation($Result, $Hash, $Size, $Version, $Label) {
        if (-not $Result.valid) { throw "$Label failed" }
    }
    function Assert-QfmInstallMutation($Result, $Label) {
        if (-not $Result.valid) { throw "$Label failed" }
    }
    function Get-DeviceSnapshot($Label) {
        $calls.Add("snapshot-$Label")
        return @{broker_process_observed=[bool]$Case.active; package_effect_artd_state='running'}
    }
    function Wait-DevicePropertyValue($Name, $Value, $Label) { }
    function Assert-SnapshotPreserved($Before, $After) { }
    . ([scriptblock]::Create($Source))
    $expectedAttempt = $Case.phase -cne 'before-install'
    if ($candidateInstallAttempted -ne $expectedAttempt -or
        @($calls | Where-Object { $_ -ceq 'same-version rollback restore' }).Count -ne [int]$expectedAttempt) {
        throw "Production rollback was skipped or duplicated: $($Case.name)"
    }
    if ($rollbackRestored -ne [bool]$Case.restored -or
        $brokerProcessRestorationAction -cne $Case.action) {
        throw "Production rollback restoration claim is wrong: $($Case.name)"
    }
    $processCalls = @($calls | Where-Object { $_ -in @(
        'restore prior broker process through normal activity', 'exact broker-only inactive-process cleanup') })
    if (-not $Case.restored -and $processCalls.Count) {
        throw "Process mutation preceded verified rollback: $($Case.name)"
    }
    if ($expectedAttempt -and $calls.IndexOf('rollback installed-byte readback') -lt
        $calls.IndexOf('same-version rollback restore')) { throw 'Rollback readback is out of order.' }
    $expectedFailure = $Case.phase -ne '' -or $Case.rollback -ne ''
    if (($null -ne $failure) -ne $expectedFailure) { throw "Failure evidence is wrong: $($Case.name)" }
    if ($expectedFailure) {
        if ($failure -isnot [Management.Automation.ErrorRecord] -or
            [string]::IsNullOrWhiteSpace([string]$failure.Exception.Message)) {
            throw "Failure message cannot be persisted: $($Case.name)"
        }
        if ($Case.phase -ceq 'candidate-readback' -and
            -not $failure.Exception.Message.Contains('candidate installed-byte readback failed')) {
            throw 'The original candidate ambiguity was lost.'
        }
        if ($Case.rollback -ne '' -and
            -not $failure.Exception.Message.Contains('Rollback restore also failed:')) {
            throw 'The additional rollback failure was lost.'
        }
    }
}
$rollbackCases = @(
    @{name='active-success'; phase=''; rollback=''; active=$true; restored=$true; action='normal-activity-start'},
    @{name='inactive-success'; phase=''; rollback=''; active=$false; restored=$true; action='exact-broker-force-stop'},
    @{name='candidate-install-uncertain'; phase='candidate-install'; rollback=''; active=$false; restored=$true; action='exact-broker-force-stop'},
    @{name='candidate-readback-uncertain'; phase='candidate-readback'; rollback=''; active=$false; restored=$true; action='exact-broker-force-stop'},
    @{name='rollback-install-uncertain'; phase='candidate-readback'; rollback='install'; active=$false; restored=$false; action='not-needed'},
    @{name='rollback-readback-failed'; phase='candidate-readback'; rollback='readback'; active=$false; restored=$false; action='not-needed'},
    @{name='rollback-process-failed'; phase='candidate-readback'; rollback='process'; active=$false; restored=$true; action='not-needed'},
    @{name='no-install'; phase='before-install'; rollback=''; active=$false; restored=$false; action='not-needed'}
)
foreach ($case in $rollbackCases) { Test-ProductionBrokerRollback $case $productionTry }
# Prove that this regression catches the original ambiguous-readback bug.
$oldGuard = $productionTry.Replace('if ($candidateInstallAttempted)',
    'if ($candidateInstallAttempted -and $candidateBytesConfirmed)')
if ($oldGuard -ceq $productionTry) { throw 'Rollback guard regression control could not be constructed.' }
$oldGuardRejected = $false
try { Test-ProductionBrokerRollback $rollbackCases[3] $oldGuard }
catch { $oldGuardRejected = $_.Exception.Message -like 'Production rollback was skipped or duplicated:*' }
if (-not $oldGuardRejected) { throw 'Rollback regression did not reject the original unsafe guard.' }
Write-Output 'Production rollback host regression passed: eight cases; original guard rejected.'
& pwsh -NoProfile -ExecutionPolicy Bypass -File (
    Join-Path $repo "tools\checks\Test-ManifoldBrokerSharedSignerGate.ps1") -RepoRoot $repo
if ($LASTEXITCODE -ne 0) {
    throw "Shared signer child gate failed with exit code $LASTEXITCODE."
}
Write-Host "Manifold broker supplier client specialization static gate: PASS"
