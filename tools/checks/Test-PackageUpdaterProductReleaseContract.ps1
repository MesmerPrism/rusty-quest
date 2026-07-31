[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "..\package_updater\ProductReleaseContract.ps1")

function Reject([scriptblock]$Action, [string]$Name) {
    try { & $Action; throw "Case '$Name' unexpectedly passed." }
    catch { if ($_.Exception.Message -like "Case * unexpectedly passed.") { throw } }
}

$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) (
    "package-updater-product-release-" + [guid]::NewGuid().ToString("N")
)
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    $apk = Join-Path $temporaryRoot "rusty-quest-package-updater.apk"
    [System.IO.File]::WriteAllBytes($apk, [byte[]](1, 2, 3, 4))
    $apkHash = "sha256:" + (
        Get-FileHash $apk -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    $build = [ordered]@{
        schema = "rusty.quest.package_updater_android.build_manifest.v1"
        source_revision = "1" * 40
        package_name = "io.github.mesmerprism.rustyquest.packageupdater.labs"
        version_code = 7
        version_name = "0.1.0-alpha.7"
        manifest_url = "https://updates.example.test/rusty-quest/package-updates/rusty-kiosk/labs/current.json"
        trusted_key_id = "release-a"
        expected_https_origin = "https://updates.example.test"
        expected_site_base_path = "rusty-quest"
        expected_package_name = "io.github.mesmerprism.rustykiosk.labs"
        expected_rollout_ring = "labs"
        expected_signer_sha256 = "sha256:" + ("2" * 64)
        expected_updater_signer_sha256 = "sha256:" + ("3" * 64)
        updater_signer_sha256 = "sha256:" + ("3" * 64)
        native_verifier_sha256 = "sha256:" + ("4" * 64)
        apk_sha256 = $apkHash
        apk_size_bytes = 4
        artifact_inspection = [ordered]@{
            tool = [ordered]@{
                build_tools_version = "35.0.1"
                aapt2_name = "aapt2.exe"
                aapt2_sha256 = "sha256:" + ("5" * 64)
                apksigner_name = "apksigner.bat"
                apksigner_sha256 = "sha256:" + ("6" * 64)
            }
            release_permissions = @(
                "android.permission.INTERNET",
                "android.permission.REQUEST_INSTALL_PACKAGES"
            )
            release_components = @(
                "PackageUpdaterActivity", "PackageInstallCallbackReceiver"
            )
            e2e_inspected = $false
        }
    }
    $buildPath = Join-Path $temporaryRoot "build.json"
    $build | ConvertTo-Json -Depth 6 | Set-Content $buildPath
    $validated = Read-PackageUpdaterBuildManifest $buildPath $apk
    $tag = "package-updater-v0.1.0-alpha.7"
    $tree = "7" * 40
    $metadata = New-PackageUpdaterProductReleaseMetadata $validated $tag $tree
    Assert-PackageUpdaterProductReleaseMetadata `
        ([pscustomobject]$metadata) $validated $tag $tree
    if ($metadata.release_version -cne "0.1.0-alpha.7") {
        throw "Product tag did not derive the exact release version."
    }

    foreach ($badTag in @(
        "package-updater-v0.1.0-alpha.0", "v0.1.0-alpha.7",
        "package-updater-v0.1.1-alpha.7", "package-updater-v0.1.0-alpha.07",
        "package-updater-v0.1.0-alpha.2147483648"
    )) {
        Reject { New-PackageUpdaterProductReleaseMetadata $validated $badTag $tree } `
            "invalid tag $badTag"
    }
    Set-Content -LiteralPath (Join-Path $temporaryRoot "invalid-build.json") `
        -Value '{"schema":' -Encoding utf8
    Reject {
        Read-PackageUpdaterBuildManifest `
            (Join-Path $temporaryRoot "invalid-build.json") $apk
    } "invalid build manifest JSON"
    $buildMutations = @(
        @("missing field", {
            param($value)
            $value.PSObject.Properties.Remove("updater_signer_sha256")
        }),
        @("missing expected updater signer", {
            param($value)
            $value.PSObject.Properties.Remove("expected_updater_signer_sha256")
        }),
        @("expanded top-level", {
            param($value)
            Add-Member -InputObject $value -NotePropertyName sdk_path `
                -NotePropertyValue "D:\private\sdk"
        }),
        @("wrong schema", {
            param($value) $value.schema = "wrong.build.v1"
        }),
        @("noncanonical source revision", {
            param($value) $value.source_revision = "z" * 40
        }),
        @("wrong package identity", {
            param($value) $value.package_name = "io.example.wrong"
        }),
        @("invalid APK version code", {
            param($value) $value.version_code = 0
        }),
        @("wrong APK version name", {
            param($value) $value.version_name = "0.1.1"
        }),
        @("wrong APK hash", {
            param($value) $value.apk_sha256 = "sha256:" + ("9" * 64)
        }),
        @("updater signer differs from pin", {
            param($value)
            $value.updater_signer_sha256 = "sha256:" + ("9" * 64)
        }),
        @("wrong APK byte count", {
            param($value) $value.apk_size_bytes = 5
        }),
        @("expanded tool identity", {
            param($value)
            Add-Member -InputObject $value.artifact_inspection.tool `
                -NotePropertyName aapt2_path -NotePropertyValue "D:\private\sdk"
        }),
        @("missing tool identity", {
            param($value)
            $value.artifact_inspection.tool.PSObject.Properties.Remove(
                "apksigner_sha256"
            )
        })
    )
    foreach ($case in $buildMutations) {
        $damaged = $build | ConvertTo-Json -Depth 6 | ConvertFrom-Json
        & $case[1] $damaged
        $path = Join-Path $temporaryRoot (
            "damaged-build-" + [guid]::NewGuid().ToString("N") + ".json"
        )
        $damaged | ConvertTo-Json -Depth 6 | Set-Content $path
        Reject { Read-PackageUpdaterBuildManifest $path $apk } `
            "$($case[0]) build manifest"
    }
    $metadataMutations = @(
        @("missing source tree", {
            param($value) $value.PSObject.Properties.Remove("source_tree")
        }),
        @("expanded top-level", {
            param($value)
            Add-Member -InputObject $value -NotePropertyName keystore_path `
                -NotePropertyValue "D:\private\release.jks"
        }),
        @("wrong schema", {
            param($value) $value.schema = "wrong.release.v1"
        }),
        @("wrong product", {
            param($value) $value.product = "rusty-kiosk"
        }),
        @("wrong tag", {
            param($value)
            $value.release_tag = "package-updater-v0.1.0-alpha.8"
        }),
        @("wrong release version", {
            param($value) $value.release_version = "0.1.0-alpha.8"
        }),
        @("wrong source revision", {
            param($value) $value.source_revision = "8" * 40
        }),
        @("wrong source tree", {
            param($value) $value.source_tree = "8" * 40
        }),
        @("wrong product channel", {
            param($value) $value.product_channel = "stable"
        }),
        @("wrong maturity", {
            param($value) $value.maturity = "released"
        }),
        @("wrong distribution track", {
            param($value) $value.distribution_track = "github-release"
        }),
        @("wrong installation identity", {
            param($value) $value.installation_identity = "io.example.wrong"
        }),
        @("wrong APK version name", {
            param($value) $value.apk_version_name = "0.1.1"
        }),
        @("wrong APK version code", {
            param($value) $value.apk_version_code = 2
        }),
        @("wrong updater signer", {
            param($value)
            $value.updater_signer_sha256 = "sha256:" + ("8" * 64)
        }),
        @("wrong APK name", {
            param($value) $value.primary_apk.name = "wrong.apk"
        }),
        @("wrong APK hash", {
            param($value)
            $value.primary_apk.sha256 = "sha256:" + ("8" * 64)
        }),
        @("wrong APK bytes", {
            param($value) $value.primary_apk.bytes = 5
        }),
        @("expanded primary APK", {
            param($value)
            Add-Member -InputObject $value.primary_apk `
                -NotePropertyName path -NotePropertyValue "D:\private\app.apk"
        }),
        @("missing primary APK hash", {
            param($value)
            $value.primary_apk.PSObject.Properties.Remove("sha256")
        })
    )
    foreach ($case in $metadataMutations) {
        $damaged = $metadata | ConvertTo-Json -Depth 5 | ConvertFrom-Json
        & $case[1] $damaged
        Reject {
            Assert-PackageUpdaterProductReleaseMetadata `
                $damaged $validated $tag $tree
        } "$($case[0]) release metadata"
    }
    [System.IO.File]::WriteAllBytes($apk, [byte[]](1, 2, 3, 5))
    Reject { Read-PackageUpdaterBuildManifest $buildPath $apk } "changed APK"
} finally {
    if ([System.IO.Directory]::Exists($temporaryRoot)) {
        [System.IO.Directory]::Delete($temporaryRoot, $true)
    }
}
Write-Output "Package Updater product release contract self-test passed."
