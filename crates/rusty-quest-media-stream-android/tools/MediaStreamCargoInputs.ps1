# Shared exact native input materialization for Android media consumers.
function New-IsolatedBrokerCargoMaterialization {
    param(
        [Parameter(Mandatory=$true)][string]$RepoRoot,
        [Parameter(Mandatory=$true)][string]$ManifoldRoot,
        [Parameter(Mandatory=$true)][string]$OutputRoot,
        [switch]$IncludeConformance
    )

    $resolvedManifold = (Resolve-Path -LiteralPath $ManifoldRoot).Path
    if ((& git -C $resolvedManifold rev-parse HEAD) -cne 'ae3effb502e5b3bf565dc628b3ac74235397145d' -or
        (& git -C $resolvedManifold rev-parse 'HEAD^{tree}') -cne '4a148035b8692be171833a7ba235a391403c8256' -or
        @(& git -C $resolvedManifold status --porcelain --untracked-files=all).Count) {
        throw 'Media native materialization requires the exact clean admitted Manifold source.'
    }
    $ManifoldRoot = $resolvedManifold
    $materializedRoot = Join-Path $OutputRoot "isolated-cargo"
    if (Test-Path -LiteralPath $materializedRoot) { throw "Native materialization must use a new output capsule." }
    New-Item -ItemType Directory -Force -Path (
        Join-Path $materializedRoot "crates"), (
        Join-Path $materializedRoot "apps\manifold-broker-android") | Out-Null
    $questCrates = @(
        "rusty-quest-broker-product", "rusty-quest-broker-authority",
        "rusty-quest-broker-admission", "rusty-quest-broker-contracts",
        "rusty-quest-media-stream", "rusty-quest-media-stream-android", "rusty-quest-device-link")
    foreach ($crate in $questCrates) {
        Copy-Item -LiteralPath (Join-Path $RepoRoot "crates\$crate") `
            -Destination (Join-Path $materializedRoot "crates\$crate") -Recurse
    }
    foreach ($nativeCrate in @("native", "connection-hub-native")) {
        Copy-Item -LiteralPath (
            Join-Path $RepoRoot "apps\manifold-broker-android\$nativeCrate") `
            -Destination (
                Join-Path $materializedRoot "apps\manifold-broker-android\$nativeCrate") `
            -Recurse
    }
    Copy-Item -LiteralPath (Join-Path $RepoRoot "fixtures") `
        -Destination (Join-Path $materializedRoot "fixtures") -Recurse
    Copy-Item -LiteralPath (Join-Path $RepoRoot "Cargo.lock") `
        -Destination (Join-Path $materializedRoot "Cargo.lock")
    # The existing native admission regression consumes this exact app lock.
    # Retain its bytes in the isolated test graph without copying the app runtime.
    $appLockRelative = 'apps/spatial-camera-panel-android/legacy-workspaces/mixed-integration-v1/conformance-locks/broker-media-client.feature.lock.json'
    $appLockDestination = Join-Path $materializedRoot $appLockRelative
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $appLockDestination))
    Copy-Item -LiteralPath (Join-Path $RepoRoot $appLockRelative) -Destination $appLockDestination

    $workspaceManifest = @'
[workspace]
members = [
  "crates/rusty-quest-broker-product",
  "crates/rusty-quest-broker-authority",
  "crates/rusty-quest-broker-admission",
  "crates/rusty-quest-broker-contracts",
  "crates/rusty-quest-media-stream",
  "crates/rusty-quest-media-stream-android",
  "crates/rusty-quest-device-link",
  "apps/manifold-broker-android/native",
]
resolver = "2"

[workspace.package]
version = "0.1.0"
edition = "2021"
authors = ["Till Holzapfel"]
license = "AGPL-3.0-or-later"
rust-version = "1.80"

[workspace.lints.rust]
unsafe_code = "forbid"
missing_docs = "warn"

[workspace.lints.clippy]
all = "warn"
pedantic = "warn"
'@
    if ($IncludeConformance) {
        $hostRoot = Join-Path $materializedRoot 'apps/media-stream-conformance-android'
        [void][IO.Directory]::CreateDirectory($hostRoot)
        Copy-Item -LiteralPath (Join-Path $RepoRoot 'apps/media-stream-conformance-android/native') -Destination (Join-Path $hostRoot 'native') -Recurse
        $workspaceManifest = $workspaceManifest.Replace('  "apps/manifold-broker-android/native",', '  "apps/manifold-broker-android/native",' + "`n" + '  "apps/media-stream-conformance-android/native",')
    }
    [IO.File]::WriteAllText((Join-Path $materializedRoot "Cargo.toml"),
        $workspaceManifest, [Text.UTF8Encoding]::new($false))

    $manifoldTomlRoot = $ManifoldRoot.Replace("\", "/")
    foreach ($manifest in Get-ChildItem -LiteralPath $materializedRoot -Recurse `
            -Filter Cargo.toml -File) {
        $text = [IO.File]::ReadAllText($manifest.FullName)
        $text = $text.Replace('../../../../rusty-manifold/crates/',
            ($manifoldTomlRoot + '/crates/'))
        $text = $text.Replace('../../../rusty-manifold/crates/',
            ($manifoldTomlRoot + '/crates/'))
        [IO.File]::WriteAllText($manifest.FullName, $text,
            [Text.UTF8Encoding]::new($false))
        $manifestDirectory = Split-Path -Parent $manifest.FullName
        $approvedCrateRoot = [IO.Path]::GetFullPath(
            (Join-Path $ManifoldRoot "crates")).TrimEnd("\", "/") + "\"
        foreach ($pathMatch in [regex]::Matches(
                $text, '(?m)\bpath\s*=\s*"(?<path>[^"]+)"')) {
            $dependencyPath = [string]$pathMatch.Groups['path'].Value
            $resolvedDependencyPath = [IO.Path]::GetFullPath($(if (
                [IO.Path]::IsPathRooted($dependencyPath)) {
                    $dependencyPath
                } else {
                    Join-Path $manifestDirectory $dependencyPath
                })).TrimEnd("\", "/")
            if (-not (Test-Path -LiteralPath $resolvedDependencyPath -PathType Container)) {
                throw "Isolated Cargo dependency path does not resolve: $resolvedDependencyPath"
            }
            if ((Split-Path -Leaf $resolvedDependencyPath).StartsWith(
                    "rusty-manifold-", [StringComparison]::Ordinal) -and
                -not ($resolvedDependencyPath + "\").StartsWith(
                    $approvedCrateRoot, [StringComparison]::OrdinalIgnoreCase)) {
                throw "Isolated Cargo dependency resolved outside the supplied Manifold root: $resolvedDependencyPath"
            }
        }
    }

    # The isolated workspace is an admitted subset of the owner workspace. Start
    # from the owner's lock and let Cargo prune only no-longer-member entries
    # offline, then require the resulting materialized lock for every real use.
    $lockMaterialization = @(& cargo metadata --offline --format-version 1 `
        --manifest-path (Join-Path $materializedRoot "Cargo.toml") 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Isolated Cargo lock materialization failed: $($lockMaterialization -join [Environment]::NewLine)"
    }
    $metadataText = @(& cargo metadata --locked --offline --format-version 1 `
        --manifest-path (Join-Path $materializedRoot "Cargo.toml") 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Isolated Cargo metadata resolution failed: $($metadataText -join [Environment]::NewLine)"
    }
    $metadata = ($metadataText -join "`n") | ConvertFrom-Json
    $manifoldPrefix = $ManifoldRoot.TrimEnd("\") + "\"
    $manifoldPackages = @($metadata.packages | Where-Object {
        ([string]$_.name).StartsWith("rusty-manifold-", [StringComparison]::Ordinal)
    })
    if ($manifoldPackages.Count -lt 7 -or @($manifoldPackages | Where-Object {
            -not ([IO.Path]::GetFullPath([string]$_.manifest_path)).StartsWith(
                $manifoldPrefix, [StringComparison]::OrdinalIgnoreCase)
        }).Count -ne 0) {
        throw "Cargo metadata did not bind every resolved Manifold dependency to the supplied root."
    }
    return [pscustomobject]@{
        root = $materializedRoot
        manifest = Join-Path $materializedRoot "Cargo.toml"
        native_manifest = Join-Path $materializedRoot `
            "apps\manifold-broker-android\native\Cargo.toml"
        connection_hub_native_manifest = Join-Path $materializedRoot `
            "apps\manifold-broker-android\connection-hub-native\Cargo.toml"
        manifold_packages = @($manifoldPackages.name | Sort-Object -Unique)
    }
}
