param(
    [Parameter(Mandatory=$true)][Alias("Path")][string]$CapsulePath,
    [ValidateSet("", "native-renderer-android", "spatial-camera-panel-android")]
    [string]$ExpectedLane = ""
)

$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "lib\SourceComposition.psm1") -Force

function Get-FileSha256 {
    param([Parameter(Mandatory=$true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Run capsule file is missing: $Path" }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-StringSha256 {
    param([Parameter(Mandatory=$true)][string]$Value)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace("-", "").ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Assert-SourceRepository {
    param([Parameter(Mandatory=$true)]$Record, [Parameter(Mandatory=$true)][string]$Label)
    if ([string]$Record.commit -notmatch '^[0-9a-f]{40}$' -or [string]$Record.tree -notmatch '^[0-9a-f]{40}$' -or $Record.tracked_worktree_clean -ne $true) {
        throw "$Label is not an exact clean source record."
    }
    if (-not (Test-Path -LiteralPath ([string]$Record.repository) -PathType Container)) { throw "$Label repository is missing: $($Record.repository)" }
    $observedTree = ([string](& git -C ([string]$Record.repository) rev-parse "$([string]$Record.commit)^{tree}" 2>$null)).Trim().ToLowerInvariant()
    if ($LASTEXITCODE -ne 0 -or $observedTree -ne [string]$Record.tree) { throw "$Label commit/tree binding is invalid." }
}

function Assert-FileRecord {
    param([Parameter(Mandatory=$true)]$Record, [Parameter(Mandatory=$true)][string]$Label)
    if ($null -eq $Record -or [string]::IsNullOrWhiteSpace([string]$Record.path) -or [string]$Record.sha256 -notmatch '^[0-9a-f]{64}$') {
        throw "$Label is not an exact file record."
    }
    $actual = Get-FileSha256 -Path ([string]$Record.path)
    if ($actual -ne [string]$Record.sha256) { throw "$Label hash mismatch: $($Record.path)" }
}

$resolvedCapsule = (Resolve-Path -LiteralPath $CapsulePath).Path
$capsule = Get-Content -LiteralPath $resolvedCapsule -Raw | ConvertFrom-Json
if ([string]$capsule.schema -ne "rusty.quest.apk_run_capsule.v1") { throw "Unsupported APK run capsule schema: $($capsule.schema)" }
if (-not [string]::IsNullOrWhiteSpace($ExpectedLane) -and [string]$capsule.app_lane -ne $ExpectedLane) {
    throw "APK run capsule lane mismatch: expected $ExpectedLane, found $($capsule.app_lane)"
}
foreach ($field in @("capsule_id", "app_id", "app_lane", "source", "build_lock", "build_manifest", "apk", "runtime_profile", "property_manifest", "android", "cleanup")) {
    if ($null -eq $capsule.PSObject.Properties[$field]) { throw "APK run capsule is missing field: $field" }
}
Assert-SourceRepository -Record $capsule.source -Label "APK run capsule primary source"
$hasComposition = $capsule.source.PSObject.Properties.Name -contains "composition_fingerprint"
$hasPackages = $capsule.source.PSObject.Properties.Name -contains "packages"
$hasDependencies = $capsule.source.PSObject.Properties.Name -contains "dependencies"
if ($hasComposition -or $hasPackages -or $hasDependencies) {
    if (-not ($hasComposition -and $hasPackages -and $hasDependencies)) { throw "APK run capsule source composition fields must be complete." }
    if ([string]$capsule.source.composition_fingerprint -notmatch '^[0-9a-f]{64}$' -or @($capsule.source.packages).Count -eq 0) { throw "APK run capsule source composition identity is invalid." }
    $identityRecords = [Collections.Generic.List[object]]::new()
    $identityRecords.Add([pscustomobject][ordered]@{ repository_id = "rusty-quest"; role = "primary"; commit = [string]$capsule.source.commit; tree = [string]$capsule.source.tree }) | Out-Null
    foreach ($dependency in @($capsule.source.dependencies)) {
        Assert-SourceRepository -Record $dependency -Label "APK run capsule dependency '$($dependency.repository_id)'"
        $identityRecords.Add([pscustomobject][ordered]@{ repository_id = [string]$dependency.repository_id; role = [string]$dependency.role; commit = [string]$dependency.commit; tree = [string]$dependency.tree }) | Out-Null
    }
    $canonicalIdentity = Get-QuestBuildSourceCompositionIdentityCanonicalText `
        -PackageName @($capsule.source.packages | ForEach-Object { [string]$_ }) `
        -Repository @($identityRecords.ToArray())
    $observedFingerprint = Get-StringSha256 -Value $canonicalIdentity
    if ($observedFingerprint -ne [string]$capsule.source.composition_fingerprint) { throw "APK run capsule source composition fingerprint mismatch." }
}
Assert-FileRecord -Record $capsule.build_lock -Label "build lock"
Assert-FileRecord -Record $capsule.build_manifest -Label "build manifest"
Assert-FileRecord -Record $capsule.apk -Label "APK"
if ($null -ne $capsule.runtime_profile) { Assert-FileRecord -Record $capsule.runtime_profile -Label "runtime profile" }
if ($null -ne $capsule.property_manifest) {
    Assert-FileRecord -Record $capsule.property_manifest -Label "property manifest"
    if ([string]$capsule.property_manifest.scope -ne "complete-manifest") { throw "APK run capsule property scope must be complete-manifest." }
}
if ([string]$capsule.android.package_name -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$' -or [string]::IsNullOrWhiteSpace([string]$capsule.android.activity)) {
    throw "APK run capsule Android identity is invalid."
}
if ([string]$capsule.cleanup.policy -ne "always-force-stop-and-restore-exact-property-snapshot" -or
    $capsule.cleanup.serial_exclusive_mutex -ne $true -or $capsule.cleanup.restore_on_failure -ne $true) {
    throw "APK run capsule cleanup contract is not fail-closed."
}

[pscustomobject][ordered]@{
    schema = "rusty.quest.apk_run_capsule_validation.v1"
    status = "pass"
    capsule_path = $resolvedCapsule
    capsule_sha256 = Get-FileSha256 -Path $resolvedCapsule
    app_id = [string]$capsule.app_id
    app_lane = [string]$capsule.app_lane
    package_name = [string]$capsule.android.package_name
    activity = [string]$capsule.android.activity
    apk_path = [string]$capsule.apk.path
    runtime_profile_path = if ($null -eq $capsule.runtime_profile) { "" } else { [string]$capsule.runtime_profile.path }
} | ConvertTo-Json -Depth 6
