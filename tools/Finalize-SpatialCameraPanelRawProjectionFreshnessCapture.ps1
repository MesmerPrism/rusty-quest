[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$QfmBundlePath,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedSerial,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedPackage,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedApkSha256
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$QfmManifestName = "launch-diagnostic-manifest.json"
$QfmLogName = "logcat-uid-post-fence.txt"
$FinalizedLogName = "camera-hwb-projection-freshness.logcat.txt"
$FinalizationReceiptName = "capture-finalization-receipt.json"
$QfmManifestSchema = "questionable.file_manager.apk_launch_diagnostic_manifest.v1"
$QfmDiagnosticContract = "questionable.file_manager.apk_launch_diagnostic_bundle.v1"
$FinalizationSchema = "rusty.quest.camera_hwb_projection_freshness_capture_finalization.v1"
$CaptureSchema = "rusty.quest.camera_hwb_projection_freshness_capture.v1"
$FreshnessReceiptSchema = "rusty.quest.camera_hwb_projection_freshness_receipt.v1"
$FreshnessFamilyAnchor = "schema=rusty.quest.camera_hwb_projection_freshness"
$CaptureBoundary = "schema=$CaptureSchema captureComplete=true logCount=1"
$MaximumManifestBytes = 1MB
$MaximumLogBytes = 256KB
$Utf8Strict = [Text.UTF8Encoding]::new($false, $true)
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$Invariant = [Globalization.CultureInfo]::InvariantCulture

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][byte[]]$Bytes)
    return ([Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($Bytes))).ToLowerInvariant()
}

function Test-ExactBytes {
    param(
        [AllowEmptyCollection()][byte[]]$Left,
        [AllowEmptyCollection()][byte[]]$Right
    )
    if ($Left.Length -ne $Right.Length) { return $false }
    [int]$difference = 0
    for ($index = 0; $index -lt $Left.Length; $index++) {
        $difference = $difference -bor ($Left[$index] -bxor $Right[$index])
    }
    return $difference -eq 0
}

function Assert-ExactKeys {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string[]]$Expected,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if ($Value -isnot [Collections.IDictionary]) {
        throw "$Label is not a JSON object."
    }
    [string[]]$actual = @($Value.Keys | ForEach-Object { [string]$_ })
    [string[]]$wanted = @($Expected)
    [Array]::Sort($actual, [StringComparer]::Ordinal)
    [Array]::Sort($wanted, [StringComparer]::Ordinal)
    if ($actual.Count -ne $wanted.Count) {
        throw "$Label has missing or unknown properties."
    }
    for ($index = 0; $index -lt $actual.Count; $index++) {
        if (-not [string]::Equals($actual[$index], $wanted[$index], [StringComparison]::Ordinal)) {
            throw "$Label has missing or unknown properties."
        }
    }
}

function Assert-ExactString {
    param($Value, [string]$Expected, [string]$Label)
    if ($Value -isnot [string] -or
        -not [string]::Equals($Value, $Expected, [StringComparison]::Ordinal)) {
        throw "$Label is not the required exact string."
    }
}

function Assert-NonEmptyString {
    param($Value, [string]$Label)
    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value)) {
        throw "$Label is not a non-empty string."
    }
    return [string]$Value
}

function Assert-ExactBoolean {
    param($Value, [bool]$Expected, [string]$Label)
    if ($Value -isnot [bool] -or [bool]$Value -ne $Expected) {
        throw "$Label is not the required boolean."
    }
}

function ConvertTo-BoundedInt64 {
    param($Value, [long]$Minimum, [long]$Maximum, [string]$Label)
    if ($Value -is [bool] -or $Value -isnot [ValueType]) {
        throw "$Label is not an integer."
    }
    $typeCode = [Convert]::GetTypeCode($Value)
    if ($typeCode -notin @(
            [TypeCode]::SByte, [TypeCode]::Byte, [TypeCode]::Int16,
            [TypeCode]::UInt16, [TypeCode]::Int32, [TypeCode]::UInt32,
            [TypeCode]::Int64)) {
        throw "$Label is not an integer."
    }
    [long]$number = $Value
    if ($number -lt $Minimum -or $number -gt $Maximum) {
        throw "$Label is outside its bounded range."
    }
    return $number
}

function Assert-Null {
    param($Value, [string]$Label)
    if ($null -ne $Value) {
        throw "$Label must be null."
    }
}

function Assert-LowerSha256 {
    param($Value, [string]$Label)
    if ($Value -isnot [string] -or $Value -cnotmatch '^[0-9a-f]{64}$') {
        throw "$Label is not a canonical lowercase SHA-256."
    }
    return [string]$Value
}

function Assert-ExactStringArray {
    param($Value, [string[]]$Expected, [string]$Label)
    if ($Value -is [string] -or $Value -isnot [Collections.IEnumerable]) {
        throw "$Label is not an array."
    }
    [object[]]$actual = @($Value)
    if ($actual.Count -ne $Expected.Count) {
        throw "$Label is not the required exact array."
    }
    for ($index = 0; $index -lt $actual.Count; $index++) {
        Assert-ExactString -Value $actual[$index] -Expected $Expected[$index] -Label "$Label item $index"
    }
}

function ConvertFrom-StrictJsonBytes {
    param([byte[]]$Bytes, [string]$Label)
    if ($Bytes.Length -eq 0 -or $Bytes.Length -gt $MaximumManifestBytes) {
        throw "$Label is empty or exceeds the bounded size."
    }
    if ($Bytes.Length -ge 3 -and $Bytes[0] -eq 0xEF -and $Bytes[1] -eq 0xBB -and $Bytes[2] -eq 0xBF) {
        throw "$Label contains a UTF-8 BOM."
    }
    $text = $Utf8Strict.GetString($Bytes)
    if ($text.Contains([char]0)) {
        throw "$Label contains a NUL byte."
    }
    $jsonOptions = [Text.Json.JsonDocumentOptions]::new()
    $jsonOptions.AllowTrailingCommas = $false
    $jsonOptions.CommentHandling = [Text.Json.JsonCommentHandling]::Disallow
    $jsonOptions.MaxDepth = 64
    $jsonStream = [IO.MemoryStream]::new($Bytes, $false)
    try {
        $document = [Text.Json.JsonDocument]::Parse($jsonStream, $jsonOptions)
        try {
            Assert-NoDuplicateJsonProperties -Element $document.RootElement -Path '$'
        } finally {
            $document.Dispose()
        }
    } catch {
        throw "$Label is not strict JSON: $($_.Exception.Message)"
    } finally {
        $jsonStream.Dispose()
    }
    return $text | ConvertFrom-Json -AsHashtable -Depth 64 -DateKind String
}

function Assert-NoDuplicateJsonProperties {
    param(
        [Parameter(Mandatory = $true)][Text.Json.JsonElement]$Element,
        [Parameter(Mandatory = $true)][string]$Path
    )
    if ($Element.ValueKind -eq [Text.Json.JsonValueKind]::Object) {
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($property in $Element.EnumerateObject()) {
            if (-not $seen.Add($property.Name)) {
                throw "JSON object '$Path' contains duplicate property '$($property.Name)'."
            }
            Assert-NoDuplicateJsonProperties -Element $property.Value -Path "$Path.$($property.Name)"
        }
    } elseif ($Element.ValueKind -eq [Text.Json.JsonValueKind]::Array) {
        $index = 0
        foreach ($item in $Element.EnumerateArray()) {
            Assert-NoDuplicateJsonProperties -Element $item -Path "$Path[$index]"
            $index++
        }
    }
}

function Assert-ApkIdentity {
    param($Value, [string]$Label)
    Assert-ExactKeys $Value @(
        "packageName", "versionCode", "versionName", "signerSha256", "splitName") $Label
    Assert-ExactString $Value.packageName $ExpectedPackage "$Label packageName"
    [void](ConvertTo-BoundedInt64 $Value.versionCode 1 ([long]::MaxValue) "$Label versionCode")
    if ($null -ne $Value.versionName) {
        [void](Assert-NonEmptyString $Value.versionName "$Label versionName")
    }
    [void](Assert-LowerSha256 $Value.signerSha256 "$Label signerSha256")
    Assert-Null $Value.splitName "$Label splitName"
    return [pscustomobject]@{
        package = [string]$Value.packageName
        version_code = [long]$Value.versionCode
        version_name = $Value.versionName
        signer_sha256 = [string]$Value.signerSha256
    }
}

function Assert-Artifact {
    param($Value, [string]$Label)
    Assert-ExactKeys $Value @("path", "sizeBytes", "sha256", "identity") $Label
    $path = Assert-NonEmptyString $Value.path "$Label path"
    if (-not $path.EndsWith(".apk", [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label path is not an APK path."
    }
    $size = ConvertTo-BoundedInt64 $Value.sizeBytes 1 ([long]::MaxValue) "$Label sizeBytes"
    $sha = Assert-LowerSha256 $Value.sha256 "$Label sha256"
    Assert-ExactString $sha $ExpectedApkSha256 "$Label sha256"
    $identity = Assert-ApkIdentity $Value.identity "$Label identity"
    return [pscustomobject]@{ path=$path; size=$size; sha=$sha; identity=$identity }
}

function Assert-SameArtifact {
    param($Actual, $Expected, [string]$Label)
    foreach ($name in @("path", "sha")) {
        Assert-ExactString $Actual.$name $Expected.$name "$Label $name"
    }
    if ($Actual.size -ne $Expected.size -or
        $Actual.identity.version_code -ne $Expected.identity.version_code -or
        $Actual.identity.version_name -cne $Expected.identity.version_name) {
        throw "$Label does not match the admitted artifact."
    }
    foreach ($name in @("package", "signer_sha256")) {
        Assert-ExactString $Actual.identity.$name $Expected.identity.$name "$Label identity $name"
    }
}

function Assert-InstalledIdentity {
    param($Value, $Artifact, [string]$Label)
    Assert-ExactKeys $Value @(
        "serial", "identity", "apkPaths", "baseApkSha256", "baseApkSizeBytes") $Label
    Assert-ExactString $Value.serial $ExpectedSerial "$Label serial"
    $identity = Assert-ApkIdentity $Value.identity "$Label identity"
    if ($Value.apkPaths -is [string] -or $Value.apkPaths -isnot [Collections.IEnumerable]) {
        throw "$Label apkPaths is not an array."
    }
    [object[]]$paths = @($Value.apkPaths)
    if ($paths.Count -ne 1) {
        throw "$Label must bind one standalone installed APK path."
    }
    $apkPath = Assert-NonEmptyString $paths[0] "$Label apkPaths[0]"
    $sha = Assert-LowerSha256 $Value.baseApkSha256 "$Label baseApkSha256"
    Assert-ExactString $sha $ExpectedApkSha256 "$Label baseApkSha256"
    $size = ConvertTo-BoundedInt64 $Value.baseApkSizeBytes 1 ([long]::MaxValue) "$Label baseApkSizeBytes"
    if ($size -ne $Artifact.size -or
        $identity.version_code -ne $Artifact.identity.version_code -or
        $identity.version_name -cne $Artifact.identity.version_name) {
        throw "$Label does not match the exact artifact identity."
    }
    foreach ($name in @("package", "signer_sha256")) {
        Assert-ExactString $identity.$name $Artifact.identity.$name "$Label identity $name"
    }
    return [pscustomobject]@{
        serial=[string]$Value.serial; apk_path=$apkPath; size=$size; sha=$sha; identity=$identity
    }
}

function Assert-SameInstalledIdentity {
    param($Actual, $Expected, [string]$Label)
    foreach ($name in @("serial", "apk_path", "sha")) {
        Assert-ExactString $Actual.$name $Expected.$name "$Label $name"
    }
    if ($Actual.size -ne $Expected.size -or
        $Actual.identity.version_code -ne $Expected.identity.version_code -or
        $Actual.identity.version_name -cne $Expected.identity.version_name) {
        throw "$Label does not preserve installed identity."
    }
    foreach ($name in @("package", "signer_sha256")) {
        Assert-ExactString $Actual.identity.$name $Expected.identity.$name "$Label identity $name"
    }
}

function Assert-CommandResult {
    param($Value, [string]$Component)
    Assert-ExactKeys $Value @(
        "fileName", "arguments", "exitCode", "standardOutput", "standardError",
        "duration", "succeeded", "condensedOutput") "QFM launch command result"
    $fileName = Assert-NonEmptyString $Value.fileName "QFM launch command fileName"
    if ([IO.Path]::GetFileName($fileName) -cnotmatch '^adb(?:\.exe)?$') {
        throw "QFM launch command is not the fixed ADB executable."
    }
    Assert-ExactStringArray $Value.arguments @(
        "-s", $ExpectedSerial, "shell", "am", "start", "-n", $Component) "QFM launch command arguments"
    if ((ConvertTo-BoundedInt64 $Value.exitCode 0 0 "QFM launch command exitCode") -ne 0) {
        throw "QFM launch command did not succeed."
    }
    if ($Value.standardOutput -isnot [string]) { throw "QFM launch command standardOutput is not a string." }
    if ($Value.standardError -isnot [string]) { throw "QFM launch command standardError is not a string." }
    [void](Assert-NonEmptyString $Value.duration "QFM launch command duration")
    Assert-ExactBoolean $Value.succeeded $true "QFM launch command succeeded"
    [void](Assert-NonEmptyString $Value.condensedOutput "QFM launch command condensedOutput")
}

function Assert-QfmBundleInventory {
    param([string]$Bundle)
    $entries = @(Get-ChildItem -LiteralPath $Bundle -Force)
    if ($entries.Count -ne 2) {
        throw "QFM launch-diagnostic bundle must contain exactly two files."
    }
    [string[]]$names = @()
    foreach ($entry in $entries) {
        if ($entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "QFM launch-diagnostic bundle contains a directory or reparse point."
        }
        $names += $entry.Name
    }
    [Array]::Sort($names, [StringComparer]::Ordinal)
    if ($names[0] -cne $QfmManifestName -or $names[1] -cne $QfmLogName) {
        throw "QFM launch-diagnostic bundle file set is not the fixed v1 set."
    }
}

function Assert-NoCaptureFamilyMarker {
    param([string]$Text)
    $markerMatches = [regex]::Matches(
        $Text,
        'schema=rusty\.quest\.camera_hwb_projection_freshness[^\s]*',
        [Text.RegularExpressions.RegexOptions]::CultureInvariant)
    foreach ($markerMatch in $markerMatches) {
        if (-not [string]::Equals(
                $markerMatch.Value,
                "schema=$FreshnessReceiptSchema",
                [StringComparison]::Ordinal)) {
            throw "QFM log contains an existing, partial, or unsupported capture-family marker."
        }
    }
}

function Write-CreateNewBytes {
    param([string]$Path, [byte[]]$Bytes)
    $stream = [IO.FileStream]::new(
        $Path,
        [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write,
        [IO.FileShare]::None,
        65536,
        [IO.FileOptions]::WriteThrough)
    try {
        $stream.Write($Bytes, 0, $Bytes.Length)
        $stream.Flush($true)
    } finally {
        $stream.Dispose()
    }
}

if ($ExpectedSerial -cnotmatch '^[A-Za-z0-9._:-]{1,128}$') {
    throw "ExpectedSerial is not a canonical bounded serial."
}
if ($ExpectedPackage -cnotmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$' -or
    $ExpectedPackage.Length -gt 255) {
    throw "ExpectedPackage is not a canonical Android package."
}
[void](Assert-LowerSha256 $ExpectedApkSha256 "ExpectedApkSha256")

$bundle = [IO.Path]::GetFullPath($QfmBundlePath).TrimEnd([char[]]@('\', '/'))
$output = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd([char[]]@('\', '/'))
if (-not [IO.Directory]::Exists($bundle)) {
    throw "QFM launch-diagnostic bundle does not exist."
}
if (([IO.DirectoryInfo]::new($bundle).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw "QFM launch-diagnostic bundle must not be a reparse point."
}
$outputParent = [IO.Path]::GetDirectoryName($output)
$outputLeaf = [IO.Path]::GetFileName($output)
if ([string]::IsNullOrWhiteSpace($outputParent) -or [string]::IsNullOrWhiteSpace($outputLeaf) -or
    -not [IO.Directory]::Exists($outputParent)) {
    throw "Capture finalization output must name a new directory under an existing parent."
}
if ([IO.Directory]::Exists($output) -or [IO.File]::Exists($output)) {
    throw "Capture finalization output already exists; evidence never overwrites."
}
$bundlePrefix = $bundle + [IO.Path]::DirectorySeparatorChar
$outputPrefix = $output + [IO.Path]::DirectorySeparatorChar
if ([string]::Equals($bundle, $output, [StringComparison]::OrdinalIgnoreCase) -or
    $output.StartsWith($bundlePrefix, [StringComparison]::OrdinalIgnoreCase) -or
    $bundle.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "QFM input and finalized output directory trees must be distinct."
}

Assert-QfmBundleInventory $bundle
$manifestPath = Join-Path $bundle $QfmManifestName
$logPath = Join-Path $bundle $QfmLogName
$manifestBytes = [IO.File]::ReadAllBytes($manifestPath)
$logBytes = [IO.File]::ReadAllBytes($logPath)
if ($logBytes.Length -gt $MaximumLogBytes) {
    throw "QFM UID log exceeds the fixed launch-diagnostic capture bound."
}
if ($logBytes.Length -ge 3 -and $logBytes[0] -eq 0xEF -and $logBytes[1] -eq 0xBB -and $logBytes[2] -eq 0xBF) {
    throw "QFM UID log contains a UTF-8 BOM."
}
$logText = $Utf8Strict.GetString($logBytes)
if ($logText.Contains([char]0)) {
    throw "QFM UID log contains a NUL byte."
}
Assert-NoCaptureFamilyMarker $logText

$manifest = ConvertFrom-StrictJsonBytes $manifestBytes "QFM launch-diagnostic manifest"
Assert-ExactKeys $manifest @(
    "schema", "diagnosticContract", "hostFence", "deviceFence", "artifact",
    "installedBeforeDispatch", "installedAfterCapture", "currentUserUidBeforeDispatch",
    "currentUserUidAfterCapture", "launch", "capture", "effectDisposition",
    "effectDispositionDetail", "postReadbackFailure", "limitations") "QFM launch-diagnostic manifest"
Assert-ExactString $manifest.schema $QfmManifestSchema "QFM manifest schema"
Assert-ExactString $manifest.diagnosticContract $QfmDiagnosticContract "QFM diagnostic contract"

Assert-ExactKeys $manifest.hostFence @("id", "createdAt") "QFM host fence"
if ($manifest.hostFence.id -isnot [string] -or $manifest.hostFence.id -cnotmatch '^[0-9a-f]{32}$') {
    throw "QFM host fence ID is not canonical."
}
$hostFenceAt = Assert-NonEmptyString $manifest.hostFence.createdAt "QFM host fence createdAt"
[DateTimeOffset]$parsedHostFenceAt = [DateTimeOffset]::MinValue
if (-not [DateTimeOffset]::TryParseExact(
        $hostFenceAt,
        "O",
        $Invariant,
        [Globalization.DateTimeStyles]::RoundtripKind,
        [ref]$parsedHostFenceAt) -or
    $parsedHostFenceAt.Offset -ne [TimeSpan]::Zero -or
    $parsedHostFenceAt.ToString("O", $Invariant) -cne $hostFenceAt) {
    throw "QFM host fence timestamp is not canonical UTC round-trip form."
}

Assert-ExactKeys $manifest.deviceFence @("epoch", "source", "logSelection") "QFM device fence"
if ($manifest.deviceFence.epoch -isnot [string] -or
    $manifest.deviceFence.epoch -cnotmatch '^[0-9]{10,}\.[0-9]{1,9}$') {
    throw "QFM device fence epoch is not canonical."
}
Assert-ExactString $manifest.deviceFence.source "fixed serial-scoped device UTC epoch readback" "QFM device fence source"
Assert-ExactString $manifest.deviceFence.logSelection "fixed epoch output at or after fence, filtered to derived current-user UID" "QFM device fence logSelection"

$artifact = Assert-Artifact $manifest.artifact "QFM artifact"
$installedBefore = Assert-InstalledIdentity $manifest.installedBeforeDispatch $artifact "QFM installed-before identity"
if ($null -eq $manifest.installedAfterCapture) {
    throw "QFM completed manifest is missing installed-after identity."
}
$installedAfter = Assert-InstalledIdentity $manifest.installedAfterCapture $artifact "QFM installed-after identity"
Assert-SameInstalledIdentity $installedAfter $installedBefore "QFM post-capture installed identity"
$uidBefore = ConvertTo-BoundedInt64 $manifest.currentUserUidBeforeDispatch 10000 ([int]::MaxValue) "QFM UID before dispatch"
$uidAfter = ConvertTo-BoundedInt64 $manifest.currentUserUidAfterCapture 10000 ([int]::MaxValue) "QFM UID after capture"
if ($uidBefore -ne $uidAfter) {
    throw "QFM current-user UID changed during capture."
}

Assert-ExactKeys $manifest.launch @(
    "dispatchAttempted", "launch", "failureCode", "failureMessage",
    "currentPackageProcessIds") "QFM launch attempt"
Assert-ExactBoolean $manifest.launch.dispatchAttempted $true "QFM dispatchAttempted"
Assert-Null $manifest.launch.failureCode "QFM launch failureCode"
Assert-Null $manifest.launch.failureMessage "QFM launch failureMessage"
if ($null -eq $manifest.launch.launch) {
    throw "QFM completed manifest is missing the resolved launch result."
}
$launch = $manifest.launch.launch
Assert-ExactKeys $launch @(
    "artifact", "installed", "component", "commandResult", "componentObservedResumed",
    "launcherIsActivityAlias", "launcherTargetActivity") "QFM resolved launch"
$launchArtifact = Assert-Artifact $launch.artifact "QFM launch artifact"
Assert-SameArtifact $launchArtifact $artifact "QFM launch artifact"
$launchInstalled = Assert-InstalledIdentity $launch.installed $artifact "QFM launch installed identity"
Assert-SameInstalledIdentity $launchInstalled $installedBefore "QFM launch installed identity"
$component = Assert-NonEmptyString $launch.component "QFM launch component"
if (-not $component.StartsWith("$ExpectedPackage/", [StringComparison]::Ordinal)) {
    throw "QFM launch component does not belong to the expected package."
}
Assert-CommandResult $launch.commandResult $component
Assert-ExactBoolean $launch.componentObservedResumed $true "QFM componentObservedResumed"
if ($launch.launcherIsActivityAlias -isnot [bool]) {
    throw "QFM launcherIsActivityAlias is not boolean."
}
if ($launch.launcherIsActivityAlias) {
    [void](Assert-NonEmptyString $launch.launcherTargetActivity "QFM launcherTargetActivity")
} else {
    Assert-Null $launch.launcherTargetActivity "QFM launcherTargetActivity"
}

if ($manifest.launch.currentPackageProcessIds -is [string] -or
    $manifest.launch.currentPackageProcessIds -isnot [Collections.IEnumerable]) {
    throw "QFM current package PID set is not an array."
}
[object[]]$rawPids = @($manifest.launch.currentPackageProcessIds)
if ($rawPids.Count -eq 0) { throw "QFM completed launch has no current package PID." }
$pids = [Collections.Generic.List[long]]::new()
[long]$previousPid = 0
foreach ($rawPid in $rawPids) {
    $androidPid = ConvertTo-BoundedInt64 $rawPid 1 ([int]::MaxValue) "QFM current package PID"
    if ($androidPid -le $previousPid) {
        throw "QFM current package PID set is not strictly increasing and unique."
    }
    $pids.Add($androidPid)
    $previousPid = $androidPid
}

Assert-ExactKeys $manifest.capture @(
    "relativePath", "sizeBytes", "sha256", "postActionWindowElapsed",
    "outputLimitReached", "captureExitedEarly", "processTreeCleanupSucceeded",
    "captureExitCode") "QFM capture"
Assert-ExactString $manifest.capture.relativePath $QfmLogName "QFM capture relativePath"
$declaredLogBytes = ConvertTo-BoundedInt64 $manifest.capture.sizeBytes 0 $MaximumLogBytes "QFM capture sizeBytes"
if ($declaredLogBytes -ne $logBytes.LongLength) {
    throw "QFM capture byte count does not match the retained UID log."
}
$declaredLogSha = Assert-LowerSha256 $manifest.capture.sha256 "QFM capture sha256"
$logSha = Get-Sha256Hex $logBytes
Assert-ExactString $declaredLogSha $logSha "QFM capture sha256"
Assert-ExactBoolean $manifest.capture.postActionWindowElapsed $true "QFM capture postActionWindowElapsed"
Assert-ExactBoolean $manifest.capture.outputLimitReached $false "QFM capture outputLimitReached"
Assert-ExactBoolean $manifest.capture.captureExitedEarly $false "QFM capture captureExitedEarly"
Assert-ExactBoolean $manifest.capture.processTreeCleanupSucceeded $true "QFM capture processTreeCleanupSucceeded"
[void](ConvertTo-BoundedInt64 $manifest.capture.captureExitCode ([int]::MinValue) ([int]::MaxValue) "QFM capture exitCode")

Assert-ExactString $manifest.effectDisposition "completed" "QFM effectDisposition"
[void](Assert-NonEmptyString $manifest.effectDispositionDetail "QFM effectDispositionDetail")
Assert-Null $manifest.postReadbackFailure "QFM postReadbackFailure"
Assert-ExactKeys $manifest.limitations @(
    "applicationReadiness", "openXrReadiness", "wearerVisibility",
    "screenshotOrRecording", "genericLogFilter", "retryPerformed") "QFM limitations"
Assert-ExactString $manifest.limitations.applicationReadiness "unknown" "QFM applicationReadiness limitation"
Assert-ExactString $manifest.limitations.openXrReadiness "unknown" "QFM openXrReadiness limitation"
Assert-ExactString $manifest.limitations.wearerVisibility "unknown" "QFM wearerVisibility limitation"
Assert-ExactBoolean $manifest.limitations.screenshotOrRecording $false "QFM screenshotOrRecording limitation"
Assert-ExactBoolean $manifest.limitations.genericLogFilter $false "QFM genericLogFilter limitation"
Assert-ExactBoolean $manifest.limitations.retryPerformed $false "QFM retryPerformed limitation"

[byte[]]$separatorBytes = @()
if ($logBytes.Length -gt 0 -and $logBytes[$logBytes.Length - 1] -ne 0x0A) {
    $separatorBytes = [byte[]]@(0x0A)
}
$boundaryBytes = $Utf8NoBom.GetBytes("$CaptureBoundary`n")
$finalizedBytes = [byte[]]::new($logBytes.Length + $separatorBytes.Length + $boundaryBytes.Length)
if ($logBytes.Length -gt 0) { [Array]::Copy($logBytes, 0, $finalizedBytes, 0, $logBytes.Length) }
if ($separatorBytes.Length -gt 0) { [Array]::Copy($separatorBytes, 0, $finalizedBytes, $logBytes.Length, $separatorBytes.Length) }
[Array]::Copy($boundaryBytes, 0, $finalizedBytes, $logBytes.Length + $separatorBytes.Length, $boundaryBytes.Length)

$finalizerBytes = [IO.File]::ReadAllBytes($PSCommandPath)
$manifestSha = Get-Sha256Hex $manifestBytes
$receipt = [ordered]@{
    schema = $FinalizationSchema
    result = "pass"
    qfm_manifest_schema = $QfmManifestSchema
    qfm_diagnostic_contract = $QfmDiagnosticContract
    qfm_manifest_relative_path = $QfmManifestName
    qfm_manifest_bytes = [UInt64]$manifestBytes.LongLength
    qfm_manifest_sha256 = $manifestSha
    qfm_log_relative_path = $QfmLogName
    qfm_log_bytes = [UInt64]$logBytes.LongLength
    qfm_log_sha256 = $logSha
    finalized_log_relative_path = $FinalizedLogName
    finalized_log_bytes = [UInt64]$finalizedBytes.LongLength
    finalized_log_sha256 = Get-Sha256Hex $finalizedBytes
    finalizer_sha256 = Get-Sha256Hex $finalizerBytes
    capture_schema = $CaptureSchema
    capture_complete = $true
    log_count = 1
    capture_boundary = $CaptureBoundary
    capture_boundary_count = 1
    output_file_count = 2
    expected_serial = $ExpectedSerial
    expected_package = $ExpectedPackage
    expected_apk_sha256 = $ExpectedApkSha256
    current_user_uid = $uidBefore
    current_package_process_ids = @($pids)
    host_launch_fence = [string]$manifest.hostFence.id
    host_fence_created_at = $hostFenceAt
    device_launch_fence_epoch = [string]$manifest.deviceFence.epoch
    effect_disposition = "completed"
    dispatch_attempted = $true
    component_observed_resumed = $true
    installed_identity_continuous = $true
    uid_continuous = $true
    post_action_window_elapsed = $true
    output_limit_reached = $false
    capture_exited_early = $false
    process_tree_cleanup_succeeded = $true
    input_bundle_unchanged = $true
    semantic_freshness_adjudicated = $false
    wearer_visible_claim = $false
}
$receiptBytes = $Utf8NoBom.GetBytes(($receipt | ConvertTo-Json -Depth 8 -Compress) + "`n")

$stage = Join-Path $outputParent ".$outputLeaf.capture-finalization-$([Guid]::NewGuid().ToString('N')).pending"
if ([IO.Directory]::Exists($stage) -or [IO.File]::Exists($stage)) {
    throw "Capture finalization private stage unexpectedly exists."
}
$published = $false
try {
    [void][IO.Directory]::CreateDirectory($stage)
    Write-CreateNewBytes (Join-Path $stage $FinalizedLogName) $finalizedBytes
    Write-CreateNewBytes (Join-Path $stage $FinalizationReceiptName) $receiptBytes

    Assert-QfmBundleInventory $bundle
    $manifestReadback = [IO.File]::ReadAllBytes($manifestPath)
    $logReadback = [IO.File]::ReadAllBytes($logPath)
    if (-not (Test-ExactBytes $manifestBytes $manifestReadback) -or
        -not (Test-ExactBytes $logBytes $logReadback)) {
        throw "QFM launch-diagnostic bundle changed during capture finalization."
    }
    $finalizerReadback = [IO.File]::ReadAllBytes($PSCommandPath)
    if (-not (Test-ExactBytes $finalizerBytes $finalizerReadback)) {
        throw "Capture finalizer source changed during execution."
    }
    if ([IO.Directory]::Exists($output) -or [IO.File]::Exists($output)) {
        throw "Capture finalization output collided before publication."
    }
    [IO.Directory]::Move($stage, $output)
    $published = $true
} finally {
    if (-not $published -and [IO.Directory]::Exists($stage)) {
        [IO.Directory]::Delete($stage, $true)
    }
}

$receipt
