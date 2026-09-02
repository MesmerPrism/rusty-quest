[CmdletBinding()]
param([string]$RepoRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
} else {
    $RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
}
$Reducer = Join-Path $RepoRoot "tools\Reduce-SpatialCameraPanelRawProjectionFreshness.ps1"
$Schema = Join-Path $RepoRoot "schemas\rusty.quest.camera_hwb_projection_freshness_reduction.v1.schema.json"
$Utf8NoBom = [Text.UTF8Encoding]::new($false)

function Write-NewText {
    param([string]$Path, [string]$Text)
    $bytes = $Utf8NoBom.GetBytes($Text)
    $stream = [IO.FileStream]::new(
        $Path,
        [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write,
        [IO.FileShare]::None)
    try {
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
    } finally {
        $stream.Dispose()
    }
}

function New-Marker {
    param(
        [UInt64]$PreviousPresent,
        [UInt64]$CurrentPresent,
        [UInt64]$PreviousFrame,
        [UInt64]$CurrentFrame,
        [UInt64]$PreviousTimestamp,
        [UInt64]$CurrentTimestamp,
        [UInt64]$PreviousImport,
        [UInt64]$CurrentImport
    )
    return "schema=rusty.quest.camera_hwb_projection_freshness_receipt.v1 " +
        "launchChallenge=701 layerGeneration=1 layerSwitchCount=0 " +
        "layerState=raw-scene-quad-active launchFenceAuthority=app-raw-carrier-live-jni-fence " +
        "runGeneration=17 runGenerationAuthority=camera-import-stream-generation " +
        "sessionGeneration=31 sessionGenerationAuthority=app-vulkan-wsi-run " +
        "cadenceAuthority=vulkan-wsi-queue-present-returned cadenceAvailable=true " +
        "previousCadenceOrdinal=$PreviousPresent currentCadenceOrdinal=$CurrentPresent " +
        "presentOrdinalAuthority=vulkan-wsi-queue-present-returned " +
        "previousPresentOrdinal=$PreviousPresent currentPresentOrdinal=$CurrentPresent " +
        "previousLeftFrameIndex=$PreviousFrame currentLeftFrameIndex=$CurrentFrame " +
        "previousRightFrameIndex=$PreviousFrame currentRightFrameIndex=$CurrentFrame " +
        "previousLeftTimestampNs=$PreviousTimestamp currentLeftTimestampNs=$CurrentTimestamp " +
        "previousRightTimestampNs=$PreviousTimestamp currentRightTimestampNs=$CurrentTimestamp " +
        "previousLeftHwbImportSequence=$PreviousImport currentLeftHwbImportSequence=$CurrentImport " +
        "previousRightHwbImportSequence=$PreviousImport currentRightHwbImportSequence=$CurrentImport " +
        "currentLeftHardwareBufferId=9001 currentRightHardwareBufferId=9002 " +
        "rawProjectionSelected=true continuousRawProjection=true cameraProjectionVisible=true " +
        "cameraProjectionMovingWitness=true movingWitnessAuthority=app-owned-command-buffer-camera-draw " +
        "visibilityScope=app-command-buffer-not-wearer-visible " +
        "intervalPolicy=first-moving-then-periodic-300-present-ordinals"
}

function Invoke-Reducer {
    param([string]$InputFile, [string]$OutputFile)
    & $Reducer `
        -InputPath $InputFile `
        -OutputPath $OutputFile `
        -ExpectedLaunchChallenge 701 `
        -ExpectedLayerGeneration 1 `
        -ExpectedLayerSwitchCount 0 `
        -ExpectedRunGeneration 17 `
        -ExpectedSessionGeneration 31 `
        -ExpectedCadenceAuthority vulkan-wsi-queue-present-returned `
        -MinimumPresentSeparation 300 | Out-Null
}

function Assert-Rejected {
    param([string]$Label, [string]$Text)
    $input = Join-Path $script:Root "$Label.log"
    $output = Join-Path $script:Root "$Label.json"
    Write-NewText -Path $input -Text $Text
    try {
        Invoke-Reducer -InputFile $input -OutputFile $output
        throw "Damage '$Label' was accepted."
    } catch {
        if ($_.Exception.Message -eq "Damage '$Label' was accepted.") {
            throw
        }
    }
    if (Test-Path -LiteralPath $output) {
        throw "Damage '$Label' created a reduction output."
    }
}

$null = Get-Content -Raw -LiteralPath $Schema | ConvertFrom-Json
$script:Root = Join-Path ([IO.Path]::GetTempPath()) "rq-raw-freshness-reducer-$([Guid]::NewGuid().ToString('N'))"
[IO.Directory]::CreateDirectory($script:Root) | Out-Null
try {
    $first = New-Marker 1 2 10 11 1000 1100 20 21
    $second = New-Marker 2 302 11 311 1100 31100 21 321
    $captureBoundary = "schema=rusty.quest.camera_hwb_projection_freshness_capture.v1 captureComplete=true logCount=1"
    $validReceiptText =
        "09-02 00:00:00.000 I/RustyQuest: status=camera-projection-freshness-receipt runtimeCrash=false $first`n" +
        "09-02 00:00:03.000 I/RustyQuest: status=camera-projection-freshness-receipt runtimeCrash=false $second`n"
    $validReceiptCrLf = $validReceiptText.Replace("`n", "`r`n")
    $validText = "$validReceiptText$captureBoundary`n"
    $input = Join-Path $script:Root "valid.log"
    $output = Join-Path $script:Root "valid.json"
    Write-NewText -Path $input -Text $validText
    Invoke-Reducer -InputFile $input -OutputFile $output
    $result = Get-Content -Raw -LiteralPath $output | ConvertFrom-Json
    if ($result.schema -cne "rusty.quest.camera_hwb_projection_freshness_reduction.v1" -or
        $result.result -cne "pass" -or $result.receipt_count -ne 2 -or
        $result.present_separation -ne 300 -or -not $result.chain_contiguous -or
        $result.wearer_visible_claim -ne $false -or
        $result.visibility_scope -cne "app-command-buffer-not-wearer-visible") {
        throw "Positive freshness reduction did not retain the closed evidence boundary."
    }

    Assert-Rejected "one-receipt" "$first`n$captureBoundary`n"
    Assert-Rejected "wrong-challenge" (($validText -replace 'launchChallenge=701', 'launchChallenge=702'))
    Assert-Rejected "wrong-run" (($validText -replace 'runGeneration=17', 'runGeneration=18'))
    Assert-Rejected "wrong-session" (($validText -replace 'sessionGeneration=31', 'sessionGeneration=32'))
    Assert-Rejected "layer-switch" (($validText -replace 'layerSwitchCount=0', 'layerSwitchCount=1'))
    Assert-Rejected "raw-deselected" (($validText -replace 'rawProjectionSelected=true', 'rawProjectionSelected=false'))
    Assert-Rejected "visibility-missing" (($validText -replace 'cameraProjectionVisible=true', 'cameraProjectionVisible=false'))
    Assert-Rejected "wearer-overclaim" (($validText -replace 'visibilityScope=app-command-buffer-not-wearer-visible', 'visibilityScope=wearer-visible'))
    Assert-Rejected "detached-chain" (($validText -replace 'previousPresentOrdinal=2 currentPresentOrdinal=302', 'previousPresentOrdinal=3 currentPresentOrdinal=302'))
    Assert-Rejected "detached-cadence" (($validText -replace 'previousCadenceOrdinal=2 currentCadenceOrdinal=302', 'previousCadenceOrdinal=3 currentCadenceOrdinal=302'))
    Assert-Rejected "frame-not-monotonic" (($validText -replace 'previousLeftFrameIndex=10 currentLeftFrameIndex=11', 'previousLeftFrameIndex=11 currentLeftFrameIndex=11'))
    Assert-Rejected "timestamp-not-monotonic" (($validText -replace 'previousRightTimestampNs=1000 currentRightTimestampNs=1100', 'previousRightTimestampNs=1100 currentRightTimestampNs=1100'))
    Assert-Rejected "import-not-monotonic" (($validText -replace 'previousLeftHwbImportSequence=20 currentLeftHwbImportSequence=21', 'previousLeftHwbImportSequence=21 currentLeftHwbImportSequence=21'))
    Assert-Rejected "retired-cadence-authority" (($validText -replace 'cadenceAuthority=vulkan-wsi-queue-present-returned', 'cadenceAuthority=spatial-sdk-broker-retired-fence-complete'))
    Assert-Rejected "retired-session-authority" (($validText -replace 'sessionGenerationAuthority=app-vulkan-wsi-run', 'sessionGenerationAuthority=spatial-sdk-device-binding'))
    Assert-Rejected "retired-present-authority" (($validText -replace 'presentOrdinalAuthority=vulkan-wsi-queue-present-returned', 'presentOrdinalAuthority=app-submit-ordinal-retired-by-sdk-broker'))
    $shortSecond = New-Marker 2 301 11 310 1100 31000 21 320
    Assert-Rejected "short-period" (
        "status=camera-projection-freshness-receipt runtimeCrash=false $first`n" +
        "status=camera-projection-freshness-receipt runtimeCrash=false $shortSecond`n" +
        "$captureBoundary`n")
    Assert-Rejected "duplicate-field" (($validText -replace 'launchChallenge=701', 'launchChallenge=701 launchChallenge=701'))
    Assert-Rejected "unknown-field" (($validText -replace 'layerGeneration=1', 'layerGeneration=1 extraField=1'))
    Assert-Rejected "envelope-suffix-after-receipt" (($validText -replace 'intervalPolicy=first-moving-then-periodic-300-present-ordinals', 'intervalPolicy=first-moving-then-periodic-300-present-ordinals runtimeCrash=false'))
    Assert-Rejected "runtime-crash-envelope" (($validText -replace 'runtimeCrash=false', 'runtimeCrash=true'))
    Assert-Rejected "leading-zero" (($validText -replace 'launchChallenge=701', 'launchChallenge=000701'))
    Assert-Rejected "missing-capture-boundary" $validReceiptText
    Assert-Rejected "partial-receipt-family" ("$validReceiptText" + "schema=rusty.quest.camera_hwb_projection_freshness_receipt.v`n$captureBoundary`n")
    Assert-Rejected "partial-family-word-before-receipt" ("$validReceiptText" + "schema=rusty.quest.camera_hwb_projection_freshness_recei`n$captureBoundary`n")
    Assert-Rejected "multiple-log-boundary" (($validText -replace 'logCount=1', 'logCount=2'))
    Assert-Rejected "duplicate-capture-boundary" ("$validText$captureBoundary`n")
    Assert-Rejected "trailing-after-capture" ("$validText" + "trailing-data`n")
    Assert-Rejected "freshness-denial" ("$validReceiptText" + "status=camera-projection-freshness-rejected reason=stale-run-generation`n$captureBoundary`n")
    Assert-Rejected "live-fence-start-mismatch" ("$validReceiptText" + "status=start-receipt startStatus=raw-projection-live-layer-fence-mismatch failClosed=true`n$captureBoundary`n")
    Assert-Rejected "live-fence-removal" ("$validReceiptText" + "channel=camera-hwb-spatial-probe status=raw-projection-layer-fence-updated reason=removed-activity-stop updateMask=1`n$captureBoundary`n")
    Assert-Rejected "live-fence-removal-reversed" ("$validReceiptText" + "reason=removed-activity-stop updateMask=1 status=raw-projection-layer-fence-updated channel=camera-hwb-spatial-probe`n$captureBoundary`n")
    Assert-Rejected "live-fence-removal-crlf" ("$validReceiptCrLf" + "channel=camera-hwb-spatial-probe status=raw-projection-layer-fence-updated reason=removed-activity-stop updateMask=1`r`n$captureBoundary`r`n")
    Assert-Rejected "live-fence-removal-reversed-crlf" ("$validReceiptCrLf" + "reason=removed-activity-stop updateMask=1 status=raw-projection-layer-fence-updated channel=camera-hwb-spatial-probe`r`n$captureBoundary`r`n")
    Assert-Rejected "live-fence-update-failed" ("$validReceiptText" + "channel=camera-hwb-spatial-probe status=raw-projection-layer-fence-updated reason=created updateMask=0`n$captureBoundary`n")
    Assert-Rejected "live-fence-update-failed-reversed" ("$validReceiptText" + "updateMask=0 reason=created status=raw-projection-layer-fence-updated channel=camera-hwb-spatial-probe`n$captureBoundary`n")
    Assert-Rejected "live-fence-update-failed-crlf" ("$validReceiptCrLf" + "channel=camera-hwb-spatial-probe status=raw-projection-layer-fence-updated reason=created updateMask=0`r`n$captureBoundary`r`n")
    Assert-Rejected "live-fence-update-failed-reversed-crlf" ("$validReceiptCrLf" + "updateMask=0 reason=created status=raw-projection-layer-fence-updated channel=camera-hwb-spatial-probe`r`n$captureBoundary`r`n")
    Assert-Rejected "camera-failure" ("$validReceiptText" + "channel=camera-hwb-spatial-probe status=layer-create-failed`n$captureBoundary`n")
    Assert-Rejected "camera-failure-reversed-crlf" ("$validReceiptCrLf" + "status=layer-create-failed channel=camera-hwb-spatial-probe`r`n$captureBoundary`r`n")
    Assert-Rejected "fatal" ("$validReceiptText" + "FATAL EXCEPTION: synthetic`n$captureBoundary`n")
    Assert-Rejected "session-loop" ("$validReceiptText" + "status=session-exit-loop`n$captureBoundary`n")

    $collision = Join-Path $script:Root "collision.json"
    Write-NewText -Path $collision -Text "preserve-me"
    try {
        Invoke-Reducer -InputFile $input -OutputFile $collision
        throw "CreateNew collision was accepted."
    } catch {
        if ($_.Exception.Message -eq "CreateNew collision was accepted.") {
            throw
        }
    }
    if ((Get-Content -Raw -LiteralPath $collision) -cne "preserve-me") {
        throw "CreateNew collision changed existing bytes."
    }

    [pscustomobject]@{
        schema = "rusty.quest.camera_hwb_projection_freshness_reducer_self_test.v1"
        result = "pass"
        positive_receipts = 2
        minimum_present_separation = 300
        damage_case_count = 42
        wearer_visible_claim = $false
        product_or_device_used = $false
    } | ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $script:Root) {
        Remove-Item -LiteralPath $script:Root -Recurse -Force
    }
}
