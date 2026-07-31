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
$url = "$origin/package-updates/rusty-kiosk/alpha/current.json"
Assert-PackageUpdaterManifestUrl -ManifestUrl $url -ExpectedHttpsOrigin $origin
foreach ($bad in @(
    "http://updates.example.test/package-updates/rusty-kiosk/alpha/current.json",
    "https://updates.example.test.evil.test/package-updates/rusty-kiosk/alpha/current.json",
    "https://user@updates.example.test/package-updates/rusty-kiosk/alpha/current.json",
    "${url}?x=1",
    "${url}#fragment",
    "$origin/package-updates/rusty-kiosk/alpha/../stable/current.json",
    "$origin/package-updates//rusty-kiosk/alpha/current.json",
    "$origin/package-updates/rusty-kiosk/alpha/%63urrent.json",
    "$origin/package-updates/rusty-kiosk/alpha/envelope.json"
)) {
    Assert-Rejected {
        Assert-PackageUpdaterManifestUrl -ManifestUrl $bad `
            -ExpectedHttpsOrigin $origin
    } "bad manifest URL $bad"
}

$badging = @(
    "package: name='io.github.mesmerprism.rustyquest.packageupdater.alpha' versionCode='1' versionName='0.1.0'"
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
    -ExpectedPackageName "io.github.mesmerprism.rustykiosk" | Out-Null
Assert-PackageUpdaterReleaseArtifact -Badging $badging `
    -Permissions $permissions `
    -ManifestTree (
        $tree -replace
            "io.github.mesmerprism.rustykiosk",
            "io.github.mesmerprism.rustyquest.alphae2efixture"
    ) `
    -ExpectedPackageName "io.github.mesmerprism.rustyquest.alphae2efixture" |
    Out-Null
Assert-Rejected {
    Assert-PackageUpdaterReleaseArtifact -Badging $badging `
        -Permissions ($permissions + "uses-permission: name='android.permission.CAMERA'") `
        -ManifestTree $tree `
        -ExpectedPackageName "io.github.mesmerprism.rustykiosk"
} "permission leak"
Assert-Rejected {
    Assert-PackageUpdaterReleaseArtifact -Badging $badging `
        -Permissions $permissions `
        -ManifestTree ($tree + "E: provider " + "E2ePackageUpdaterCliProvider") `
        -ExpectedPackageName "io.github.mesmerprism.rustykiosk"
} "E2E provider leak"
Assert-Rejected {
    Assert-PackageUpdaterReleaseArtifact -Badging @(
        "package: name='io.github.mesmerprism.rustyquest.packageupdater' versionCode='1' versionName='0.1.0'"
    ) -Permissions $permissions -ManifestTree $tree `
        -ExpectedPackageName "io.github.mesmerprism.rustykiosk"
} "wrong release identity"
Assert-Rejected {
    Assert-PackageUpdaterReleaseArtifact -Badging $badging `
        -Permissions $permissions -ManifestTree $tree `
        -ExpectedPackageName "io.github.mesmerprism.rustyquest.alphae2efixture"
} "wrong query package"

$e2eBadging = @(
    "package: name='io.github.mesmerprism.rustyquest.packageupdater.alpha.e2ecli' versionCode='1' versionName='0.1.0-e2ecli'"
)
Assert-PackageUpdaterE2eArtifact -Badging $e2eBadging -ManifestTree @(
    "E: provider E2ePackageUpdaterCliProvider",
    "E: service E2ePackageUpdateService"
) | Out-Null

Write-Output "Package updater build artifact contract self-test passed."
