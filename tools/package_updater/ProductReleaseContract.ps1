Set-StrictMode -Version Latest

function Assert-ExactJsonFields {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string[]]$Expected,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if ($null -eq $Value -or
        ($Value -isnot [pscustomobject] -and
            $Value -isnot [System.Collections.IDictionary])) {
        throw "$Label must be one JSON object."
    }
    $actual = if ($Value -is [System.Collections.IDictionary]) {
        @($Value.Keys | Sort-Object)
    } else {
        @($Value.PSObject.Properties.Name | Sort-Object)
    }
    $wanted = @($Expected | Sort-Object)
    if (@(Compare-Object $actual $wanted -SyncWindow 0).Count -ne 0) {
        throw "$Label fields differ from the exact contract."
    }
}

function Read-PackageUpdaterBuildManifest {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ApkPath
    )
    $manifest = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    Assert-ExactJsonFields $manifest @(
        "schema", "source_revision", "package_name", "version_code",
        "version_name", "manifest_url", "trusted_key_id",
        "expected_https_origin", "expected_package_name",
        "expected_rollout_ring", "expected_signer_sha256",
        "expected_updater_signer_sha256", "updater_signer_sha256",
        "native_verifier_sha256", "apk_sha256",
        "apk_size_bytes", "artifact_inspection"
    ) "Package Updater build manifest"
    Assert-ExactJsonFields $manifest.artifact_inspection @(
        "tool", "release_permissions", "release_components", "e2e_inspected"
    ) "Package Updater artifact inspection"
    Assert-ExactJsonFields $manifest.artifact_inspection.tool @(
        "build_tools_version", "aapt2_name", "aapt2_sha256",
        "apksigner_name", "apksigner_sha256"
    ) "Package Updater public build-tool identity"
    if ($manifest.schema -ne
            "rusty.quest.package_updater_android.build_manifest.v1" -or
        $manifest.source_revision -notmatch "^[0-9a-f]{40}$" -or
        $manifest.package_name -ne
            "io.github.mesmerprism.rustyquest.packageupdater.alpha" -or
        [uint64]$manifest.version_code -lt 1 -or
        [uint64]$manifest.version_code -gt 2147483647 -or
        $manifest.version_name -notmatch
            "^0\.1\.0(?:-alpha\.[1-9][0-9]*)?$" -or
        $manifest.manifest_url -ne
            "$($manifest.expected_https_origin)/package-updates/rusty-kiosk/alpha/current.json" -or
        $manifest.trusted_key_id -notmatch "^[A-Za-z0-9._-]{1,96}$" -or
        $manifest.expected_https_origin -notmatch
            "^https://[a-z0-9.-]+(?::[1-9][0-9]{0,4})?$" -or
        $manifest.expected_package_name -notmatch
            "^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$" -or
        $manifest.expected_rollout_ring -ne "alpha" -or
        $manifest.expected_signer_sha256 -notmatch "^sha256:[0-9a-f]{64}$" -or
        $manifest.expected_updater_signer_sha256 -notmatch
            "^sha256:[0-9a-f]{64}$" -or
        $manifest.updater_signer_sha256 -notmatch "^sha256:[0-9a-f]{64}$" -or
        $manifest.updater_signer_sha256 -cne
            $manifest.expected_updater_signer_sha256 -or
        $manifest.native_verifier_sha256 -notmatch "^sha256:[0-9a-f]{64}$" -or
        $manifest.apk_sha256 -notmatch "^sha256:[0-9a-f]{64}$" -or
        [uint64]$manifest.apk_size_bytes -lt 1 -or
        $manifest.artifact_inspection.e2e_inspected -ne $false -or
        $manifest.artifact_inspection.tool.build_tools_version -notmatch
            "^[0-9]+(?:\.[0-9]+){1,3}$" -or
        $manifest.artifact_inspection.tool.aapt2_name -ne "aapt2.exe" -or
        $manifest.artifact_inspection.tool.apksigner_name -ne "apksigner.bat" -or
        $manifest.artifact_inspection.tool.aapt2_sha256 -notmatch
            "^sha256:[0-9a-f]{64}$" -or
        $manifest.artifact_inspection.tool.apksigner_sha256 -notmatch
            "^sha256:[0-9a-f]{64}$") {
        throw "Package Updater build manifest is not an alpha release artifact."
    }
    $permissions = @($manifest.artifact_inspection.release_permissions)
    $components = @($manifest.artifact_inspection.release_components)
    if (@(Compare-Object $permissions @(
                "android.permission.INTERNET",
                "android.permission.REQUEST_INSTALL_PACKAGES"
            ) -SyncWindow 0).Count -ne 0 -or
        @(Compare-Object $components @(
                "PackageUpdaterActivity", "PackageInstallCallbackReceiver"
            ) -SyncWindow 0).Count -ne 0) {
        throw "Package Updater build manifest closure is not exact."
    }
    $actualHash = "sha256:" + (
        Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    $actualBytes = (Get-Item -LiteralPath $ApkPath).Length
    if ($actualHash -ne $manifest.apk_sha256 -or
        $actualBytes -ne [uint64]$manifest.apk_size_bytes) {
        throw "Actual Package Updater APK differs from its build manifest."
    }
    $manifest
}

function New-PackageUpdaterProductReleaseMetadata {
    param(
        [Parameter(Mandatory = $true)]$BuildManifest,
        [Parameter(Mandatory = $true)][string]$Tag,
        [Parameter(Mandatory = $true)][string]$SourceTree
    )
    $tagMatch = [regex]::Match(
        $Tag,
        "^package-updater-v(?<version>0\.1\.0-alpha\.(?<sequence>[1-9][0-9]*))$"
    )
    $sequence = 0
    if (-not $tagMatch.Success -or
        -not [int]::TryParse(
            $tagMatch.Groups["sequence"].Value, [ref]$sequence
        ) -or
        $sequence -lt 1 -or
        $SourceTree -notmatch "^[0-9a-f]{40}$") {
        throw "Package Updater product tag or source tree is not canonical."
    }
    $version = $tagMatch.Groups["version"].Value
    if ([uint64]$BuildManifest.version_code -ne $sequence -or
        $BuildManifest.version_name -cne $version) {
        throw "Package Updater APK version is not derived from its alpha tag."
    }
    [ordered]@{
        schema = "rusty.quest.package_updater_product_release.v1"
        product = "rusty-quest-package-updater"
        release_tag = $Tag
        release_version = $version
        source_revision = $BuildManifest.source_revision
        source_tree = $SourceTree
        channel = "alpha"
        installation_identity =
            "io.github.mesmerprism.rustyquest.packageupdater.alpha"
        apk_version_name = $BuildManifest.version_name
        apk_version_code = [uint64]$BuildManifest.version_code
        updater_signer_sha256 =
            $BuildManifest.expected_updater_signer_sha256
        primary_apk = [ordered]@{
            name = "rusty-quest-package-updater.apk"
            sha256 = $BuildManifest.apk_sha256
            bytes = [uint64]$BuildManifest.apk_size_bytes
        }
    }
}

function Assert-PackageUpdaterProductReleaseMetadata {
    param(
        [Parameter(Mandatory = $true)]$Metadata,
        [Parameter(Mandatory = $true)]$BuildManifest,
        [Parameter(Mandatory = $true)][string]$ExpectedTag,
        [Parameter(Mandatory = $true)][string]$ExpectedSourceTree
    )
    Assert-ExactJsonFields $Metadata @(
        "schema", "product", "release_tag", "release_version",
        "source_revision", "source_tree", "channel", "installation_identity",
        "apk_version_name", "apk_version_code", "updater_signer_sha256",
        "primary_apk"
    ) "Package Updater product release metadata"
    Assert-ExactJsonFields $Metadata.primary_apk @(
        "name", "sha256", "bytes"
    ) "Package Updater primary APK metadata"
    $expected = New-PackageUpdaterProductReleaseMetadata `
        -BuildManifest $BuildManifest -Tag $ExpectedTag `
        -SourceTree $ExpectedSourceTree
    if (($Metadata | ConvertTo-Json -Depth 5 -Compress) -ne
        ($expected | ConvertTo-Json -Depth 5 -Compress)) {
        throw "Package Updater product release metadata differs from owner derivation."
    }
}
