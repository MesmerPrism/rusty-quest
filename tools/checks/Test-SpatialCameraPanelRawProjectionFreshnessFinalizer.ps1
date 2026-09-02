param(
    [string]$RepoRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$finalizer = Join-Path $repo "tools\Finalize-SpatialCameraPanelRawProjectionFreshnessCapture.ps1"
$schemaPath = Join-Path $repo "schemas\rusty.quest.camera_hwb_projection_freshness_capture_finalization.v1.schema.json"
$utf8 = [Text.UTF8Encoding]::new($false, $true)
$serial = "QUEST123"
$package = "io.github.example.morphovision"
$apkSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
$signerSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
$logName = "logcat-uid-post-fence.txt"
$manifestName = "launch-diagnostic-manifest.json"
$finalLogName = "camera-hwb-projection-freshness.logcat.txt"
$receiptName = "capture-finalization-receipt.json"
$boundary = "schema=rusty.quest.camera_hwb_projection_freshness_capture.v1 captureComplete=true logCount=1"
$assertions = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

function Get-Sha256Hex {
    param([AllowEmptyCollection()][byte[]]$Bytes)
    return ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes))).ToLowerInvariant()
}

function Test-ExactBytes {
    param(
        [AllowEmptyCollection()][byte[]]$Left,
        [AllowEmptyCollection()][byte[]]$Right
    )
    if ($Left.Length -ne $Right.Length) { return $false }
    for ($index = 0; $index -lt $Left.Length; $index++) {
        if ($Left[$index] -ne $Right[$index]) { return $false }
    }
    return $true
}

function New-Artifact {
    return [ordered]@{
        path = "C:\private\morphovision.apk"
        sizeBytes = 123456
        sha256 = $apkSha
        identity = [ordered]@{
            packageName = $package
            versionCode = 7
            versionName = "0.7"
            signerSha256 = $signerSha
            splitName = $null
        }
    }
}

function New-Installed {
    return [ordered]@{
        serial = $serial
        identity = (New-Artifact).identity
        apkPaths = @("/data/app/example/base.apk")
        baseApkSha256 = $apkSha
        baseApkSizeBytes = 123456
    }
}

function New-Manifest {
    param([byte[]]$LogBytes)
    $logSha = Get-Sha256Hex $LogBytes
    $component = "$package/.MainActivity"
    return [ordered]@{
        schema = "questionable.file_manager.apk_launch_diagnostic_manifest.v1"
        diagnosticContract = "questionable.file_manager.apk_launch_diagnostic_bundle.v1"
        hostFence = [ordered]@{
            id = "0123456789abcdef0123456789abcdef"
            createdAt = "2026-09-02T01:02:03.0000000+00:00"
        }
        deviceFence = [ordered]@{
            epoch = "1788310923.123456789"
            source = "fixed serial-scoped device UTC epoch readback"
            logSelection = "fixed epoch output at or after fence, filtered to derived current-user UID"
        }
        artifact = New-Artifact
        installedBeforeDispatch = New-Installed
        installedAfterCapture = New-Installed
        currentUserUidBeforeDispatch = 10123
        currentUserUidAfterCapture = 10123
        launch = [ordered]@{
            dispatchAttempted = $true
            launch = [ordered]@{
                artifact = New-Artifact
                installed = New-Installed
                component = $component
                commandResult = [ordered]@{
                    fileName = "C:\tools\adb.exe"
                    arguments = @("-s", $serial, "shell", "am", "start", "-n", $component)
                    exitCode = 0
                    standardOutput = "Starting: Intent"
                    standardError = ""
                    duration = "00:00:00.1000000"
                    succeeded = $true
                    condensedOutput = "Starting: Intent"
                }
                componentObservedResumed = $true
                launcherIsActivityAlias = $false
                launcherTargetActivity = $null
            }
            failureCode = $null
            failureMessage = $null
            currentPackageProcessIds = @(1234, 5678)
        }
        capture = [ordered]@{
            relativePath = $logName
            sizeBytes = $LogBytes.LongLength
            sha256 = $logSha
            postActionWindowElapsed = $true
            outputLimitReached = $false
            captureExitedEarly = $false
            processTreeCleanupSucceeded = $true
            captureExitCode = -1
        }
        effectDisposition = "completed"
        effectDispositionDetail = "Exact installed bytes and bounded logs were retained."
        postReadbackFailure = $null
        limitations = [ordered]@{
            applicationReadiness = "unknown"
            openXrReadiness = "unknown"
            wearerVisibility = "unknown"
            screenshotOrRecording = $false
            genericLogFilter = $false
            retryPerformed = $false
        }
    }
}

function Write-Bytes {
    param([string]$Path, [byte[]]$Bytes)
    [IO.File]::WriteAllBytes($Path, $Bytes)
}

function New-Bundle {
    param(
        [string]$Parent,
        [string]$Name,
        [byte[]]$LogBytes,
        [scriptblock]$MutateManifest = {},
        [scriptblock]$TransformManifestText = { param($text) $text },
        [switch]$ManifestBom,
        [switch]$ExtraFile
    )
    $bundle = Join-Path $Parent $Name
    [void][IO.Directory]::CreateDirectory($bundle)
    $manifest = New-Manifest $LogBytes
    & $MutateManifest $manifest
    $manifestText = $manifest | ConvertTo-Json -Depth 32
    $manifestText = & $TransformManifestText $manifestText
    $manifestBytes = $utf8.GetBytes($manifestText)
    if ($ManifestBom) {
        $manifestBytes = [byte[]]@(0xEF, 0xBB, 0xBF) + $manifestBytes
    }
    Write-Bytes (Join-Path $bundle $logName) $LogBytes
    Write-Bytes (Join-Path $bundle $manifestName) $manifestBytes
    if ($ExtraFile) {
        Write-Bytes (Join-Path $bundle "extra.txt") $utf8.GetBytes("extra")
    }
    return $bundle
}

function Get-Binding {
    param([string]$Path)
    $bytes = [IO.File]::ReadAllBytes($Path)
    return [pscustomobject]@{ bytes=$bytes.LongLength; sha256=Get-Sha256Hex $bytes }
}

function Invoke-Finalizer {
    param(
        [string]$Bundle,
        [string]$Output,
        [string]$Serial = $serial,
        [string]$Package = $package,
        [string]$ApkSha = $apkSha
    )
    & $finalizer `
        -QfmBundlePath $Bundle `
        -OutputDirectory $Output `
        -ExpectedSerial $Serial `
        -ExpectedPackage $Package `
        -ExpectedApkSha256 $ApkSha | Out-Null
}

function Assert-Rejected {
    param(
        [string]$Name,
        [scriptblock]$MutateManifest = {},
        [string]$LogText = "ordinary app log`n",
        [string]$ExpectedSerial = $serial,
        [string]$ExpectedPackage = $package,
        [string]$ExpectedApkSha = $apkSha,
        [scriptblock]$TransformManifestText = { param($text) $text },
        [switch]$ManifestBom,
        [switch]$ExtraFile,
        [switch]$OutputCollision
    )
    $caseRoot = Join-Path $script:tempRoot $Name
    [void][IO.Directory]::CreateDirectory($caseRoot)
    $bundle = New-Bundle `
        -Parent $caseRoot `
        -Name "qfm" `
        -LogBytes $utf8.GetBytes($LogText) `
        -MutateManifest $MutateManifest `
        -TransformManifestText $TransformManifestText `
        -ManifestBom:$ManifestBom `
        -ExtraFile:$ExtraFile
    $manifestBefore = Get-Binding (Join-Path $bundle $manifestName)
    $logBefore = Get-Binding (Join-Path $bundle $logName)
    $output = Join-Path $caseRoot "final"
    if ($OutputCollision) {
        [void][IO.Directory]::CreateDirectory($output)
        Write-Bytes (Join-Path $output "sentinel.txt") $utf8.GetBytes("preserve")
    }
    $threw = $false
    try {
        Invoke-Finalizer $bundle $output $ExpectedSerial $ExpectedPackage $ExpectedApkSha
    } catch {
        $threw = $true
    }
    Assert-True $threw "$Name was not rejected."
    if ($OutputCollision) {
        Assert-True (([IO.File]::ReadAllText((Join-Path $output "sentinel.txt"), $utf8)) -ceq "preserve") `
            "$Name altered the colliding output."
    } else {
        Assert-True (-not [IO.Directory]::Exists($output)) "$Name left a published output directory."
    }
    $manifestAfter = Get-Binding (Join-Path $bundle $manifestName)
    $logAfter = Get-Binding (Join-Path $bundle $logName)
    Assert-True ($manifestBefore.bytes -eq $manifestAfter.bytes -and $manifestBefore.sha256 -ceq $manifestAfter.sha256) `
        "$Name altered the QFM manifest."
    Assert-True ($logBefore.bytes -eq $logAfter.bytes -and $logBefore.sha256 -ceq $logAfter.sha256) `
        "$Name altered the QFM log."
}

if (-not [IO.File]::Exists($finalizer) -or -not [IO.File]::Exists($schemaPath)) {
    throw "Freshness capture finalizer or schema is missing."
}
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]@('\', '/'))
$tempRoot = Join-Path $tempBase "rq-freshness-finalizer-$([Guid]::NewGuid().ToString('N'))"
[void][IO.Directory]::CreateDirectory($tempRoot)
try {
    $happyRoot = Join-Path $tempRoot "happy"
    [void][IO.Directory]::CreateDirectory($happyRoot)
    $rawLog = $utf8.GetBytes("first raw line`r`nsecond raw line")
    $bundle = New-Bundle $happyRoot "qfm" $rawLog
    $manifestBefore = Get-Binding (Join-Path $bundle $manifestName)
    $logBefore = Get-Binding (Join-Path $bundle $logName)
    $output = Join-Path $happyRoot "final"
    Invoke-Finalizer $bundle $output

    $entries = @(Get-ChildItem -LiteralPath $output -Force)
    Assert-True ($entries.Count -eq 2) "Finalized output did not contain exactly two files."
    [string[]]$names = @($entries.Name)
    [Array]::Sort($names, [StringComparer]::Ordinal)
    Assert-True (($names -join '|') -ceq "$finalLogName|$receiptName") "Finalized output file set drifted."
    $expectedFinal = [byte[]]::new($rawLog.Length + 1 + $utf8.GetByteCount("$boundary`n"))
    [Array]::Copy($rawLog, 0, $expectedFinal, 0, $rawLog.Length)
    $expectedFinal[$rawLog.Length] = 0x0A
    $boundaryBytes = $utf8.GetBytes("$boundary`n")
    [Array]::Copy($boundaryBytes, 0, $expectedFinal, $rawLog.Length + 1, $boundaryBytes.Length)
    $actualFinal = [IO.File]::ReadAllBytes((Join-Path $output $finalLogName))
    Assert-True (Test-ExactBytes $expectedFinal $actualFinal) `
        "Finalizer did not preserve raw bytes and append only the necessary LF plus boundary."

    $receiptBytes = [IO.File]::ReadAllBytes((Join-Path $output $receiptName))
    Assert-True ($receiptBytes[$receiptBytes.Length - 1] -eq 0x0A) "Finalization receipt is not LF-terminated."
    Assert-True (-not ($receiptBytes.Length -ge 3 -and $receiptBytes[0] -eq 0xEF -and $receiptBytes[1] -eq 0xBB -and $receiptBytes[2] -eq 0xBF)) `
        "Finalization receipt contains a BOM."
    $receipt = $utf8.GetString($receiptBytes) | ConvertFrom-Json -AsHashtable -Depth 20 -DateKind String
    $schema = Get-Content -Raw -LiteralPath $schemaPath | ConvertFrom-Json -AsHashtable -Depth 20 -DateKind String
    [string[]]$receiptKeys = @($receipt.Keys | ForEach-Object { [string]$_ })
    [string[]]$requiredKeys = @($schema.required | ForEach-Object { [string]$_ })
    [Array]::Sort($receiptKeys, [StringComparer]::Ordinal)
    [Array]::Sort($requiredKeys, [StringComparer]::Ordinal)
    Assert-True (($receiptKeys -join '|') -ceq ($requiredKeys -join '|')) "Receipt does not match the closed schema field set."
    Assert-True ($receipt.schema -ceq "rusty.quest.camera_hwb_projection_freshness_capture_finalization.v1") `
        "Receipt schema drifted."
    Assert-True ($receipt.qfm_manifest_sha256 -ceq $manifestBefore.sha256) "Receipt did not bind QFM manifest bytes."
    Assert-True ($receipt.qfm_log_sha256 -ceq $logBefore.sha256) "Receipt did not bind QFM log bytes."
    Assert-True ($receipt.capture_boundary_count -eq 1 -and $receipt.output_file_count -eq 2) `
        "Receipt did not bind the one-log/two-file closure."
    Assert-True (-not $receipt.semantic_freshness_adjudicated -and -not $receipt.wearer_visible_claim) `
        "Finalizer overclaimed semantic freshness or wearer visibility."
    $manifestAfter = Get-Binding (Join-Path $bundle $manifestName)
    $logAfter = Get-Binding (Join-Path $bundle $logName)
    Assert-True ($manifestBefore.sha256 -ceq $manifestAfter.sha256 -and $logBefore.sha256 -ceq $logAfter.sha256) `
        "Happy-path finalization altered the QFM bundle."

    $lfRoot = Join-Path $tempRoot "already-lf"
    [void][IO.Directory]::CreateDirectory($lfRoot)
    $lfRaw = $utf8.GetBytes("already terminated`n")
    $lfBundle = New-Bundle $lfRoot "qfm" $lfRaw
    $lfOutput = Join-Path $lfRoot "final"
    Invoke-Finalizer $lfBundle $lfOutput
    $lfFinal = [IO.File]::ReadAllBytes((Join-Path $lfOutput $finalLogName))
    $lfExpected = $lfRaw + $utf8.GetBytes("$boundary`n")
    Assert-True (Test-ExactBytes $lfExpected $lfFinal) `
        "Finalizer inserted an extra LF after an already terminated raw log."

    $emptyRoot = Join-Path $tempRoot "empty-log"
    [void][IO.Directory]::CreateDirectory($emptyRoot)
    $emptyRaw = [byte[]]@()
    $emptyBundle = New-Bundle $emptyRoot "qfm" $emptyRaw
    $emptyOutput = Join-Path $emptyRoot "final"
    Invoke-Finalizer $emptyBundle $emptyOutput
    $emptyFinal = [IO.File]::ReadAllBytes((Join-Path $emptyOutput $finalLogName))
    Assert-True (Test-ExactBytes ($utf8.GetBytes("$boundary`n")) $emptyFinal) `
        "Finalizer inserted an unnecessary leading LF for an empty bounded raw log."

    Assert-Rejected "unknown-top-property" { param($m) $m.unexpected = $true }
    Assert-Rejected "unknown-capture-property" { param($m) $m.capture.unexpected = $true }
    Assert-Rejected "duplicate-root-property" -TransformManifestText {
        param($text)
        return $text -replace '^\{', ('{' + "`n  `"schema`": `"shadow`",")
    }
    Assert-Rejected "duplicate-nested-authority-property" -TransformManifestText {
        param($text)
        return $text.Replace(
            '"capture": {',
            '"capture": {' + "`n    `"relativePath`": `"shadow.txt`",")
    }
    Assert-Rejected "wrong-manifest-schema" { param($m) $m.schema = "questionable.file_manager.apk_launch_diagnostic_manifest.v2" }
    Assert-Rejected "wrong-diagnostic-contract" { param($m) $m.diagnosticContract = "questionable.file_manager.apk_launch_diagnostic_bundle.v2" }
    Assert-Rejected "manifest-bom" -ManifestBom
    Assert-Rejected "fixed-log-path-drift" { param($m) $m.capture.relativePath = "alternate.txt" }
    Assert-Rejected "effect-not-completed" { param($m) $m.effectDisposition = "launchPending" }
    Assert-Rejected "dispatch-not-attempted" { param($m) $m.launch.dispatchAttempted = $false }
    Assert-Rejected "resumed-not-observed" { param($m) $m.launch.launch.componentObservedResumed = $false }
    Assert-Rejected "post-readback-failure" { param($m) $m.postReadbackFailure = "readback failed" }
    Assert-Rejected "installed-version-drift" { param($m) $m.installedAfterCapture.identity.versionCode = 8 }
    Assert-Rejected "installed-sha-drift" { param($m) $m.installedAfterCapture.baseApkSha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc" }
    Assert-Rejected "uid-drift" { param($m) $m.currentUserUidAfterCapture = 10124 }
    Assert-Rejected "pid-duplicate" { param($m) $m.launch.currentPackageProcessIds = @(1234, 1234) }
    Assert-Rejected "launch-command-drift" { param($m) $m.launch.launch.commandResult.arguments[4] = "force-stop" }
    Assert-Rejected "capture-window-incomplete" { param($m) $m.capture.postActionWindowElapsed = $false }
    Assert-Rejected "capture-output-limit" { param($m) $m.capture.outputLimitReached = $true }
    Assert-Rejected "capture-exited-early" { param($m) $m.capture.captureExitedEarly = $true }
    Assert-Rejected "capture-cleanup-failed" { param($m) $m.capture.processTreeCleanupSucceeded = $false }
    Assert-Rejected "capture-size-drift" { param($m) $m.capture.sizeBytes = ([long]$m.capture.sizeBytes + 1) }
    Assert-Rejected "capture-hash-drift" { param($m) $m.capture.sha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc" }
    Assert-Rejected "caller-serial-drift" -ExpectedSerial "QUEST999"
    Assert-Rejected "caller-package-drift" -ExpectedPackage "io.github.example.other"
    Assert-Rejected "caller-apk-drift" -ExpectedApkSha "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    Assert-Rejected "existing-capture-boundary" -LogText "ordinary`n$boundary`n"
    Assert-Rejected "partial-capture-family" -LogText "schema=rusty.quest.camera_hwb_projection_freshness_capt`n"
    Assert-Rejected "unsupported-freshness-family" -LogText "schema=rusty.quest.camera_hwb_projection_freshness_capture.v2`n"
    Assert-Rejected "bundle-extra-file" -ExtraFile
    Assert-Rejected "output-collision" -OutputCollision

    [ordered]@{
        schema = "rusty.quest.camera_hwb_projection_freshness_capture_finalizer_self_test.v1"
        result = "pass"
        assertion_count = $assertions
        damage_case_count = 31
        qfm_bundle_unchanged = $true
        output_no_overwrite = $true
        semantic_freshness_adjudicated = $false
        wearer_visible_claim = $false
    } | ConvertTo-Json -Compress
} finally {
    $resolvedTempRoot = [IO.Path]::GetFullPath($tempRoot)
    $expectedPrefix = $tempBase + [IO.Path]::DirectorySeparatorChar + "rq-freshness-finalizer-"
    if (-not $resolvedTempRoot.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean an unexpected finalizer self-test root."
    }
    if ([IO.Directory]::Exists($resolvedTempRoot)) {
        [IO.Directory]::Delete($resolvedTempRoot, $true)
    }
}
