[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "..\package_updater\BuildArtifactContract.ps1")

function Assert-Rejected([scriptblock]$Action, [string]$Name) {
    try {
        & $Action
        throw "Case '$Name' unexpectedly passed."
    } catch {
        if ($_.Exception.Message -like "Case * unexpectedly passed.") { throw }
    }
}

$origin = "https://updates.example.test"
$siteBasePath = "rusty-quest"
$url = "$origin/$siteBasePath/package-updates/rusty-kiosk/labs/current.json"
Assert-PackageUpdaterManifestUrl -ManifestUrl $url `
    -ExpectedHttpsOrigin $origin -ExpectedSiteBasePath $siteBasePath
foreach ($bad in @(
    "http://updates.example.test/rusty-quest/package-updates/rusty-kiosk/labs/current.json",
    "https://updates.example.test.evil.test/rusty-quest/package-updates/rusty-kiosk/labs/current.json",
    "https://user@updates.example.test/rusty-quest/package-updates/rusty-kiosk/labs/current.json",
    "https://updates.example.test:4443/rusty-quest/package-updates/rusty-kiosk/labs/current.json",
    "${url}?x=1",
    "${url}#fragment",
    "$origin/rusty-quest/package-updates/rusty-kiosk/labs/../stable/current.json",
    "$origin/rusty-quest/package-updates//rusty-kiosk/labs/current.json",
    "$origin/rusty-quest/package-updates/rusty-kiosk/labs/%63urrent.json",
    "$origin/rusty-quest/package-updates/rusty-kiosk/labs/envelope.json",
    "$origin/package-updates/rusty-kiosk/labs/current.json",
    "$origin/rusty-quest-evil/package-updates/rusty-kiosk/labs/current.json",
    "$origin/rusty-quest/rusty-quest/package-updates/rusty-kiosk/labs/current.json"
)) {
    Assert-Rejected {
        Assert-PackageUpdaterManifestUrl -ManifestUrl $bad `
            -ExpectedHttpsOrigin $origin -ExpectedSiteBasePath $siteBasePath
    } "bad manifest URL $bad"
}

$badging = @(
    "package: name='io.github.mesmerprism.rustyquest.packageupdater.labs' versionCode='7' versionName='0.1.0-alpha.7'"
)
$permissions = @(
    "uses-permission: name='android.permission.INTERNET'",
    "uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'"
)
$tree = @(
    "E: manifest",
    "E: queries",
    "E: package ",
    "A: android:name(0x01010003)=`"io.github.mesmerprism.rustykiosk`"",
    "E: application",
    "E: activity",
    "A: android:name=`"io.github.mesmerprism.rustyquest.packageupdater.PackageUpdaterActivity`"",
    "A: android:exported(0x01010010)=true",
    "E: receiver",
    "A: android:name=`"io.github.mesmerprism.rustyquest.packageupdater.PackageInstallCallbackReceiver`"",
    "A: android:exported(0x01010010)=false"
)
Assert-PackageUpdaterReleaseArtifact -Badging $badging `
    -Permissions $permissions -ManifestTree $tree `
    -ExpectedPackageName "io.github.mesmerprism.rustykiosk" `
    -ExpectedVersionCode 7 -ExpectedVersionName "0.1.0-alpha.7" | Out-Null
Assert-PackageUpdaterReleaseArtifact -Badging $badging `
    -Permissions $permissions `
    -ManifestTree (
        $tree -replace
            "io.github.mesmerprism.rustykiosk",
            "io.github.mesmerprism.rustyquest.alphae2efixture"
    ) `
    -ExpectedPackageName "io.github.mesmerprism.rustyquest.alphae2efixture" `
    -ExpectedVersionCode 7 -ExpectedVersionName "0.1.0-alpha.7" |
    Out-Null
Assert-Rejected {
    Assert-PackageUpdaterReleaseArtifact -Badging $badging `
        -Permissions ($permissions + "uses-permission: name='android.permission.CAMERA'") `
        -ManifestTree $tree `
        -ExpectedPackageName "io.github.mesmerprism.rustykiosk" `
        -ExpectedVersionCode 7 -ExpectedVersionName "0.1.0-alpha.7"
} "permission leak"
Assert-Rejected {
    Assert-PackageUpdaterReleaseArtifact -Badging $badging `
        -Permissions $permissions `
        -ManifestTree ($tree + "E: provider " + "E2ePackageUpdaterCliProvider") `
        -ExpectedPackageName "io.github.mesmerprism.rustykiosk" `
        -ExpectedVersionCode 7 -ExpectedVersionName "0.1.0-alpha.7"
} "E2E provider leak"
Assert-Rejected {
    Assert-PackageUpdaterReleaseArtifact -Badging @(
        "package: name='io.github.mesmerprism.rustyquest.packageupdater' versionCode='7' versionName='0.1.0-alpha.7'"
    ) -Permissions $permissions -ManifestTree $tree `
        -ExpectedPackageName "io.github.mesmerprism.rustykiosk" `
        -ExpectedVersionCode 7 -ExpectedVersionName "0.1.0-alpha.7"
} "wrong release identity"
Assert-Rejected {
    Assert-PackageUpdaterReleaseArtifact -Badging @(
        "package: name='io.github.mesmerprism.rustyquest.packageupdater.labs' versionCode='1' versionName='0.1.0'"
    ) -Permissions $permissions -ManifestTree $tree `
        -ExpectedPackageName "io.github.mesmerprism.rustykiosk" `
        -ExpectedVersionCode 7 -ExpectedVersionName "0.1.0-alpha.7"
} "stale hardcoded updater version"
Assert-Rejected {
    Assert-PackageUpdaterReleaseArtifact -Badging $badging `
        -Permissions $permissions -ManifestTree $tree `
        -ExpectedPackageName "io.github.mesmerprism.rustyquest.alphae2efixture" `
        -ExpectedVersionCode 7 -ExpectedVersionName "0.1.0-alpha.7"
} "wrong query package"

$signer = ConvertFrom-PackageUpdaterSignerCertificates @(
    "Signer #1 certificate SHA-256 digest: " + ("AB" * 32)
)
if ($signer -ne "sha256:" + ("ab" * 32)) {
    throw "Release signer digest was not canonicalized."
}
Assert-Rejected {
    ConvertFrom-PackageUpdaterSignerCertificates @(
        "Signer #1 certificate SHA-256 digest: " + ("ab" * 32),
        "Signer #2 certificate SHA-256 digest: " + ("cd" * 32)
    )
} "multiple release signers"

$publicTool = Get-PublicPackageUpdaterBuildTool `
    -BuildToolsVersion "35.0.1" `
    -Aapt2Path "D:\private\sdk\35.0.1\aapt2.exe" `
    -Aapt2Sha256 ("sha256:" + ("1" * 64)) `
    -ApkSignerPath "D:\private\sdk\35.0.1\apksigner.bat" `
    -ApkSignerSha256 ("sha256:" + ("2" * 64))
$publicToolJson = $publicTool | ConvertTo-Json -Compress
if ($publicToolJson.Contains("D:\private") -or
    $publicTool.aapt2_name -ne "aapt2.exe" -or
    $publicTool.apksigner_name -ne "apksigner.bat") {
    throw "Public build-tool receipt leaked a local path."
}

$e2eBadging = @(
    "package: name='io.github.mesmerprism.rustyquest.packageupdater.labs.e2ecli' versionCode='1' versionName='0.1.0-e2ecli'"
)
Assert-PackageUpdaterE2eArtifact -Badging $e2eBadging -ManifestTree @(
    "E: provider E2ePackageUpdaterCliProvider",
    "E: service E2ePackageUpdateService"
) | Out-Null

Write-Output "Package updater build artifact contract self-test passed."
