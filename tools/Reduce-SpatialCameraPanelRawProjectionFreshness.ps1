[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, [long]::MaxValue)]
    [long]$ExpectedLaunchChallenge,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, [long]::MaxValue)]
    [long]$ExpectedLayerGeneration,

    [Parameter(Mandatory = $true)]
    [ValidateRange(0, [long]::MaxValue)]
    [long]$ExpectedLayerSwitchCount,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, [long]::MaxValue)]
    [long]$ExpectedRunGeneration,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, [long]::MaxValue)]
    [long]$ExpectedSessionGeneration,

    [Parameter(Mandatory = $true)]
    [ValidateSet("vulkan-wsi-queue-present-returned")]
    [string]$ExpectedCadenceAuthority,

    [ValidateRange(300, 1000000)]
    [int]$MinimumPresentSeparation = 300
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ReceiptSchema = "rusty.quest.camera_hwb_projection_freshness_receipt.v1"
$CaptureSchema = "rusty.quest.camera_hwb_projection_freshness_capture.v1"
$CaptureFamilyPrefix = "schema=rusty.quest.camera_hwb_projection_freshness_capture"
$FreshnessFamilyPrefix = "schema=rusty.quest.camera_hwb_projection_freshness_"
$ReceiptAnchor = "schema=$ReceiptSchema"
$CaptureBoundary = "schema=$CaptureSchema captureComplete=true logCount=1"
$ReductionSchema = "rusty.quest.camera_hwb_projection_freshness_reduction.v1"
$MaximumInputBytes = 16MB
$Utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$Invariant = [System.Globalization.CultureInfo]::InvariantCulture

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)
    return ([Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($Bytes))).ToLowerInvariant()
}

function ConvertTo-UnsignedValue {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Fields,
        [Parameter(Mandatory = $true)][string]$Name
    )
    [UInt64]$value = 0
    if (-not [UInt64]::TryParse(
            [string]$Fields[$Name],
            [Globalization.NumberStyles]::None,
            $Invariant,
            [ref]$value)) {
        throw "Freshness receipt field '$Name' is not a canonical unsigned integer."
    }
    if (-not [string]::Equals(
            [string]$Fields[$Name],
            $value.ToString($Invariant),
            [StringComparison]::Ordinal)) {
        throw "Freshness receipt field '$Name' is not a canonical unsigned integer."
    }
    return $value
}

function Assert-ExactFieldSet {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Fields,
        [Parameter(Mandatory = $true)][string[]]$ExpectedNames
    )
    [string[]]$actual = @($Fields.Keys | ForEach-Object { [string]$_ })
    [Array]::Sort($actual, [StringComparer]::Ordinal)
    [string[]]$expected = @($ExpectedNames)
    [Array]::Sort($expected, [StringComparer]::Ordinal)
    $same = $actual.Count -eq $expected.Count
    if ($same) {
        for ($index = 0; $index -lt $actual.Count; $index++) {
            if (-not [string]::Equals(
                    $actual[$index],
                    $expected[$index],
                    [StringComparison]::Ordinal)) {
                $same = $false
                break
            }
        }
    }
    if (-not $same) {
        throw "Freshness receipt properties are incomplete or contain unknown fields."
    }
}

function ConvertFrom-FreshnessMarker {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Line)

    $anchor = "schema=$ReceiptSchema"
    $offset = $Line.IndexOf($anchor, [StringComparison]::Ordinal)
    if ($offset -lt 0) {
        return $null
    }
    $marker = $Line.Substring($offset).Trim()
    $tokens = @($marker.Split(' ', [StringSplitOptions]::RemoveEmptyEntries))
    $fields = @{}
    foreach ($token in $tokens) {
        $separator = $token.IndexOf('=')
        if ($separator -le 0 -or $separator -eq ($token.Length - 1)) {
            throw "Freshness receipt contains a malformed token."
        }
        $name = $token.Substring(0, $separator)
        $value = $token.Substring($separator + 1)
        if ($name -cnotmatch '^[A-Za-z][A-Za-z0-9]*$' -or
            $value -cnotmatch '^[A-Za-z0-9._:-]+$' -or
            $fields.ContainsKey($name)) {
            throw "Freshness receipt contains an invalid or duplicate field."
        }
        $fields.Add($name, $value)
    }

    [string[]]$expectedFields = @(
        "schema", "launchChallenge", "layerGeneration", "layerSwitchCount",
        "layerState", "launchFenceAuthority", "runGeneration",
        "runGenerationAuthority", "sessionGeneration", "sessionGenerationAuthority",
        "cadenceAuthority", "cadenceAvailable", "previousCadenceOrdinal",
        "currentCadenceOrdinal", "presentOrdinalAuthority", "previousPresentOrdinal",
        "currentPresentOrdinal", "previousLeftFrameIndex", "currentLeftFrameIndex",
        "previousRightFrameIndex", "currentRightFrameIndex", "previousLeftTimestampNs",
        "currentLeftTimestampNs", "previousRightTimestampNs", "currentRightTimestampNs",
        "previousLeftHwbImportSequence", "currentLeftHwbImportSequence",
        "previousRightHwbImportSequence", "currentRightHwbImportSequence",
        "currentLeftHardwareBufferId", "currentRightHardwareBufferId",
        "rawProjectionSelected", "continuousRawProjection", "cameraProjectionVisible",
        "cameraProjectionMovingWitness", "movingWitnessAuthority", "visibilityScope",
        "intervalPolicy"
    )
    Assert-ExactFieldSet -Fields $fields -ExpectedNames $expectedFields

    $expectedConstants = [ordered]@{
        schema = $ReceiptSchema
        layerState = "raw-scene-quad-active"
        launchFenceAuthority = "app-raw-carrier-live-jni-fence"
        runGenerationAuthority = "camera-import-stream-generation"
        cadenceAvailable = "true"
        rawProjectionSelected = "true"
        continuousRawProjection = "true"
        cameraProjectionVisible = "true"
        cameraProjectionMovingWitness = "true"
        movingWitnessAuthority = "app-owned-command-buffer-camera-draw"
        visibilityScope = "app-command-buffer-not-wearer-visible"
        intervalPolicy = "first-moving-then-periodic-300-present-ordinals"
    }
    foreach ($entry in $expectedConstants.GetEnumerator()) {
        if (-not [string]::Equals(
                [string]$fields[$entry.Key],
                [string]$entry.Value,
                [StringComparison]::Ordinal)) {
            throw "Freshness receipt field '$($entry.Key)' is not the required owner value."
        }
    }

    $authority = [string]$fields.cadenceAuthority
    if ($authority -cne "vulkan-wsi-queue-present-returned") {
        throw "Freshness receipt cadence authority is unsupported."
    }
    $expectedSessionAuthority = "app-vulkan-wsi-run"
    $expectedPresentAuthority = "vulkan-wsi-queue-present-returned"
    if ($fields.sessionGenerationAuthority -cne $expectedSessionAuthority -or
        $fields.presentOrdinalAuthority -cne $expectedPresentAuthority) {
        throw "Freshness receipt cadence authority tuple is inconsistent."
    }

    $numericNames = @(
        "launchChallenge", "layerGeneration", "layerSwitchCount", "runGeneration",
        "sessionGeneration", "previousCadenceOrdinal", "currentCadenceOrdinal",
        "previousPresentOrdinal", "currentPresentOrdinal", "previousLeftFrameIndex",
        "currentLeftFrameIndex", "previousRightFrameIndex", "currentRightFrameIndex",
        "previousLeftTimestampNs", "currentLeftTimestampNs", "previousRightTimestampNs",
        "currentRightTimestampNs", "previousLeftHwbImportSequence",
        "currentLeftHwbImportSequence", "previousRightHwbImportSequence",
        "currentRightHwbImportSequence", "currentLeftHardwareBufferId",
        "currentRightHardwareBufferId"
    )
    $numbers = @{}
    foreach ($name in $numericNames) {
        $numbers[$name] = ConvertTo-UnsignedValue -Fields $fields -Name $name
    }

    foreach ($pair in @(
            @("previousCadenceOrdinal", "currentCadenceOrdinal"),
            @("previousPresentOrdinal", "currentPresentOrdinal"),
            @("previousLeftFrameIndex", "currentLeftFrameIndex"),
            @("previousRightFrameIndex", "currentRightFrameIndex"),
            @("previousLeftTimestampNs", "currentLeftTimestampNs"),
            @("previousRightTimestampNs", "currentRightTimestampNs"),
            @("previousLeftHwbImportSequence", "currentLeftHwbImportSequence"),
            @("previousRightHwbImportSequence", "currentRightHwbImportSequence")
        )) {
        if ([UInt64]$numbers[$pair[1]] -le [UInt64]$numbers[$pair[0]]) {
            throw "Freshness receipt field '$($pair[1])' is not monotonic."
        }
    }
    foreach ($requiredPositive in @(
            "launchChallenge", "layerGeneration", "runGeneration", "sessionGeneration",
            "previousCadenceOrdinal", "currentCadenceOrdinal", "previousPresentOrdinal",
            "currentPresentOrdinal", "previousLeftFrameIndex", "currentLeftFrameIndex",
            "previousRightFrameIndex", "currentRightFrameIndex", "previousLeftTimestampNs",
            "currentLeftTimestampNs", "previousRightTimestampNs", "currentRightTimestampNs",
            "previousLeftHwbImportSequence", "currentLeftHwbImportSequence",
            "previousRightHwbImportSequence", "currentRightHwbImportSequence",
            "currentLeftHardwareBufferId", "currentRightHardwareBufferId"
        )) {
        if ([UInt64]$numbers[$requiredPositive] -eq 0) {
            throw "Freshness receipt field '$requiredPositive' is missing."
        }
    }

    return [pscustomobject]@{
        fields = $fields
        numbers = $numbers
        cadence_authority = $authority
    }
}

function Test-FreshnessDenialLine {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Line)

    $fields = @{}
    foreach ($token in @($Line.Split(
                [char[]]@(' ', "`t"),
                [StringSplitOptions]::RemoveEmptyEntries))) {
        $separator = $token.IndexOf('=')
        if ($separator -le 0 -or $separator -eq ($token.Length - 1)) {
            continue
        }
        $name = $token.Substring(0, $separator)
        $value = $token.Substring($separator + 1)
        if (-not $fields.ContainsKey($name)) {
            $fields[$name] = [Collections.Generic.List[string]]::new()
        }
        $fields[$name].Add($value)
    }

    $statusValues = if ($fields.ContainsKey('status')) { @($fields.status) } else { @() }
    $startStatusValues = if ($fields.ContainsKey('startStatus')) { @($fields.startStatus) } else { @() }
    $reasonValues = if ($fields.ContainsKey('reason')) { @($fields.reason) } else { @() }
    $updateMaskValues = if ($fields.ContainsKey('updateMask')) { @($fields.updateMask) } else { @() }
    $channelValues = if ($fields.ContainsKey('channel')) { @($fields.channel) } else { @() }
    $runtimeCrashValues = if ($fields.ContainsKey('runtimeCrash')) { @($fields.runtimeCrash) } else { @() }

    if ($statusValues -ccontains 'camera-projection-freshness-rejected' -or
        $startStatusValues -ccontains 'raw-projection-launch-fence-rejected' -or
        $startStatusValues -ccontains 'raw-projection-live-layer-fence-mismatch' -or
        $statusValues -ccontains 'camera-hwb-failed' -or
        $statusValues -ccontains 'camera-hwb-acquire-failed' -or
        $statusValues -ccontains 'session-exit-loop' -or
        $Line.Contains('session-exit-loop', [StringComparison]::Ordinal) -or
        $runtimeCrashValues -ccontains 'true') {
        return $true
    }
    if ($statusValues -ccontains 'raw-projection-layer-fence-updated') {
        if (@($reasonValues | Where-Object { $_.StartsWith('removed-', [StringComparison]::Ordinal) }).Count -ne 0 -or
            $updateMaskValues -ccontains '0') {
            return $true
        }
    }
    if ($channelValues -ccontains 'camera-hwb-spatial-probe') {
        foreach ($status in $statusValues) {
            if ($status -cmatch '^[A-Za-z0-9-]*(?:failed|rejected)$') {
                return $true
            }
        }
    }
    return $false
}

$resolvedInput = [IO.Path]::GetFullPath($InputPath)
$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
if (-not [IO.File]::Exists($resolvedInput)) {
    throw "Freshness evidence input does not exist."
}
if ([string]::Equals($resolvedInput, $resolvedOutput, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Freshness evidence input and reduction output must be distinct."
}
$inputBytes = [IO.File]::ReadAllBytes($resolvedInput)
if ($inputBytes.Length -eq 0 -or $inputBytes.Length -gt $MaximumInputBytes) {
    throw "Freshness evidence input is empty or exceeds the bounded size."
}
$text = $Utf8Strict.GetString($inputBytes)
if ($text.Contains([char]0)) {
    throw "Freshness evidence input contains a NUL byte."
}
$canonicalLfBoundary = "$CaptureBoundary`n"
$canonicalCrLfBoundary = "$CaptureBoundary`r`n"
if (-not ($text.EndsWith($canonicalLfBoundary, [StringComparison]::Ordinal) -or
        $text.EndsWith($canonicalCrLfBoundary, [StringComparison]::Ordinal))) {
    throw "Freshness evidence is missing its canonical final single-log capture boundary."
}
$lines = @([regex]::Split($text, '\r?\n'))
$captureCount = @($lines | Where-Object {
        [string]::Equals($_, $CaptureBoundary, [StringComparison]::Ordinal)
    }).Count
if ($captureCount -ne 1) {
    throw "Freshness evidence must contain exactly one canonical capture boundary."
}
foreach ($line in $lines) {
    if ($line.Contains($CaptureFamilyPrefix, [StringComparison]::Ordinal) -and
        -not [string]::Equals($line, $CaptureBoundary, [StringComparison]::Ordinal)) {
        throw "Freshness evidence contains a malformed or multiple-log capture boundary."
    }
    $familyOffset = $line.IndexOf($FreshnessFamilyPrefix, [StringComparison]::Ordinal)
    if ($familyOffset -ge 0) {
        $familyMarker = $line.Substring($familyOffset)
        $supportedReceipt = $familyMarker -ceq $ReceiptAnchor -or
            $familyMarker.StartsWith("$ReceiptAnchor ", [StringComparison]::Ordinal)
        $supportedCapture = [string]::Equals(
            $line,
            $CaptureBoundary,
            [StringComparison]::Ordinal)
        if (-not $supportedReceipt -and -not $supportedCapture) {
            throw "Freshness evidence contains a partial or unsupported receipt-family marker."
        }
        if ($familyMarker.IndexOf(
                $FreshnessFamilyPrefix,
                $FreshnessFamilyPrefix.Length,
                [StringComparison]::Ordinal) -ge 0) {
            throw "Freshness evidence contains a partial or unsupported receipt-family marker."
        }
    }
}

$deniedCount = 0
foreach ($line in $lines) {
    if (Test-FreshnessDenialLine -Line $line) {
        $deniedCount++
    }
}
$fatalCount = [regex]::Matches(
    $text,
    '(?im)FATAL EXCEPTION|Fatal signal\s+\d+|\bANR in\b').Count
if ($deniedCount -ne 0 -or $fatalCount -ne 0) {
    throw "Freshness evidence contains an app-owned denial, session-loop, or fatal marker."
}

$receipts = [Collections.Generic.List[object]]::new()
foreach ($line in $lines) {
    $parsed = ConvertFrom-FreshnessMarker -Line $line
    if ($null -ne $parsed) {
        $receipts.Add($parsed)
    }
}
if ($receipts.Count -lt 2) {
    throw "At least two app-owned freshness receipts are required."
}

[UInt64]$expectedChallenge = [UInt64]$ExpectedLaunchChallenge
[UInt64]$expectedLayerGenerationValue = [UInt64]$ExpectedLayerGeneration
[UInt64]$expectedLayerSwitchValue = [UInt64]$ExpectedLayerSwitchCount
[UInt64]$expectedRun = [UInt64]$ExpectedRunGeneration
[UInt64]$expectedSession = [UInt64]$ExpectedSessionGeneration
$previousReceipt = $null
foreach ($receipt in $receipts) {
    $n = $receipt.numbers
    if ([UInt64]$n.launchChallenge -ne $expectedChallenge -or
        [UInt64]$n.layerGeneration -ne $expectedLayerGenerationValue -or
        [UInt64]$n.layerSwitchCount -ne $expectedLayerSwitchValue -or
        [UInt64]$n.layerSwitchCount -ne 0 -or
        [UInt64]$n.runGeneration -ne $expectedRun -or
        [UInt64]$n.sessionGeneration -ne $expectedSession -or
        $receipt.cadence_authority -cne $ExpectedCadenceAuthority) {
        throw "Freshness receipt does not match the exact launch, layer, run, session, or cadence authority."
    }
    if ($null -ne $previousReceipt) {
        $previous = $previousReceipt.numbers
        foreach ($link in @(
                @("currentCadenceOrdinal", "previousCadenceOrdinal"),
                @("currentPresentOrdinal", "previousPresentOrdinal"),
                @("currentLeftFrameIndex", "previousLeftFrameIndex"),
                @("currentRightFrameIndex", "previousRightFrameIndex"),
                @("currentLeftTimestampNs", "previousLeftTimestampNs"),
                @("currentRightTimestampNs", "previousRightTimestampNs"),
                @("currentLeftHwbImportSequence", "previousLeftHwbImportSequence"),
                @("currentRightHwbImportSequence", "previousRightHwbImportSequence")
            )) {
            if ([UInt64]$previous[$link[0]] -ne [UInt64]$n[$link[1]]) {
                throw "Freshness receipt chain is detached at '$($link[1])'."
            }
        }
        if ([UInt64]$n.currentPresentOrdinal -lt
            ([UInt64]$previous.currentPresentOrdinal + [UInt64]$MinimumPresentSeparation)) {
            throw "Periodic freshness receipts are not separated by the required present interval."
        }
    }
    $previousReceipt = $receipt
}

$first = $receipts[0].numbers
$last = $receipts[$receipts.Count - 1].numbers
if ([UInt64]$last.currentPresentOrdinal -lt
    ([UInt64]$first.currentPresentOrdinal + [UInt64]$MinimumPresentSeparation)) {
    throw "Freshness receipt series is too short."
}

$toolBytes = [IO.File]::ReadAllBytes($PSCommandPath)
$reduction = [ordered]@{
    schema = $ReductionSchema
    result = "pass"
    input_bytes = [UInt64]$inputBytes.LongLength
    input_sha256 = Get-Sha256Hex -Bytes $inputBytes
    reducer_sha256 = Get-Sha256Hex -Bytes $toolBytes
    capture_schema = $CaptureSchema
    capture_complete = $true
    input_log_count = 1
    receipt_count = $receipts.Count
    launch_challenge = $expectedChallenge
    layer_generation = $expectedLayerGenerationValue
    layer_switch_count = $expectedLayerSwitchValue
    run_generation = $expectedRun
    session_generation = $expectedSession
    cadence_authority = $ExpectedCadenceAuthority
    first_current_present_ordinal = [UInt64]$first.currentPresentOrdinal
    last_current_present_ordinal = [UInt64]$last.currentPresentOrdinal
    present_separation = [UInt64]$last.currentPresentOrdinal - [UInt64]$first.currentPresentOrdinal
    minimum_present_separation = $MinimumPresentSeparation
    chain_contiguous = $true
    monotonic_fields_confirmed = $true
    continuous_raw_projection = $true
    camera_projection_visible = $true
    camera_projection_moving_witness = $true
    moving_witness_authority = "app-owned-command-buffer-camera-draw"
    visibility_scope = "app-command-buffer-not-wearer-visible"
    wearer_visible_claim = $false
    denied_marker_count = 0
    fatal_marker_count = 0
}
$json = ($reduction | ConvertTo-Json -Depth 5 -Compress) + "`n"
$outputBytes = $Utf8NoBom.GetBytes($json)
$outputParent = [IO.Path]::GetDirectoryName($resolvedOutput)
if ([string]::IsNullOrWhiteSpace($outputParent) -or -not [IO.Directory]::Exists($outputParent)) {
    throw "Freshness reduction output parent does not exist."
}
$stream = [IO.FileStream]::new(
    $resolvedOutput,
    [IO.FileMode]::CreateNew,
    [IO.FileAccess]::Write,
    [IO.FileShare]::None,
    4096,
    [IO.FileOptions]::WriteThrough)
try {
    $stream.Write($outputBytes, 0, $outputBytes.Length)
    $stream.Flush($true)
} finally {
    $stream.Dispose()
}

$reduction
