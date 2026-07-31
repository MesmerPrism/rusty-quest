Set-StrictMode -Version Latest

function Assert-PackageUpdaterManifestUrl {
    param(
        [string]$ManifestUrl,
        [string]$ExpectedHttpsOrigin,
        [string]$ExpectedSiteBasePath
    )
    if ($ExpectedHttpsOrigin -notmatch "^https://[a-z0-9.-]+$" -or
        $ExpectedSiteBasePath -ne "rusty-quest" -or
        $ManifestUrl -match "%|\\\\|//package-updates|[?#]" -or
        $ManifestUrl -ne
            "$ExpectedHttpsOrigin/$ExpectedSiteBasePath/package-updates/rusty-kiosk/labs/current.json") {
        throw "Manifest URL must be the canonical Labs pointer under the exact origin."
    }
    try {
        $uri = [Uri]::new($ManifestUrl, [UriKind]::Absolute)
    } catch {
        throw "Manifest URL is not an absolute canonical URI."
    }
    if ($uri.Scheme -ne "https" -or
        -not [string]::IsNullOrEmpty($uri.UserInfo) -or
        -not [string]::IsNullOrEmpty($uri.Query) -or
        -not [string]::IsNullOrEmpty($uri.Fragment) -or
        -not $uri.IsDefaultPort -or
        $uri.AbsolutePath -ne
            "/$ExpectedSiteBasePath/package-updates/rusty-kiosk/labs/current.json" -or
        $uri.AbsolutePath.Contains("..") -or
        $uri.AbsolutePath.Contains("//")) {
        throw "Manifest URL contains an ambiguous or forbidden URI component."
    }
}

function ConvertFrom-PackageUpdaterBadging {
    param([string[]]$Lines)
    $line = @($Lines | Where-Object { $_ -match "^package: " })
    if ($line.Count -ne 1 -or $line[0] -notmatch
            "^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'") {
        throw "APK badging does not contain one exact package identity."
    }
    [ordered]@{
        package_name = $Matches[1]
        version_code = [uint64]$Matches[2]
        version_name = $Matches[3]
    }
}

function ConvertFrom-PackageUpdaterSignerCertificates {
    param([string[]]$Lines)
    $certificateLines = @($Lines | Where-Object {
        $_ -match "^Signer #[0-9]+ certificate SHA-256 digest: "
    })
    if ($certificateLines.Count -ne 1 -or
        $certificateLines[0] -notmatch
            "^Signer #1 certificate SHA-256 digest: ([0-9A-Fa-f]{64})$") {
        throw "Release APK must have exactly one signing certificate."
    }
    "sha256:" + $Matches[1].ToLowerInvariant()
}

function Get-PublicPackageUpdaterBuildTool {
    param(
        [string]$BuildToolsVersion,
        [string]$Aapt2Path,
        [string]$Aapt2Sha256,
        [string]$ApkSignerPath,
        [string]$ApkSignerSha256
    )
    $aapt2Name = Split-Path -Leaf $Aapt2Path
    $apkSignerName = Split-Path -Leaf $ApkSignerPath
    if ($BuildToolsVersion -notmatch "^[0-9]+(?:\.[0-9]+){1,3}$" -or
        $aapt2Name -notmatch "^aapt2(?:\.exe)?$" -or
        $apkSignerName -notmatch "^apksigner(?:\.bat)?$" -or
        $Aapt2Sha256 -notmatch "^sha256:[0-9a-f]{64}$" -or
        $ApkSignerSha256 -notmatch "^sha256:[0-9a-f]{64}$") {
        throw "Android build-tool identity is not public and canonical."
    }
    [ordered]@{
        build_tools_version = $BuildToolsVersion
        aapt2_name = $aapt2Name
        aapt2_sha256 = $Aapt2Sha256
        apksigner_name = $apkSignerName
        apksigner_sha256 = $ApkSignerSha256
    }
}

function Assert-PackageUpdaterReleaseArtifact {
    param(
        [string[]]$Badging,
        [string[]]$Permissions,
        [string[]]$ManifestTree,
        [Parameter(Mandatory = $true)]
        [string]$ExpectedPackageName,
        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 2147483647)]
        [int]$ExpectedVersionCode,
        [Parameter(Mandatory = $true)]
        [ValidatePattern("^0\.1\.0(?:-alpha\.[1-9][0-9]*)?$")]
        [string]$ExpectedVersionName
    )
    $identity = ConvertFrom-PackageUpdaterBadging $Badging
    if ($identity.package_name -ne
            "io.github.mesmerprism.rustyquest.packageupdater.labs" -or
        $identity.version_code -ne $ExpectedVersionCode -or
        $identity.version_name -cne $ExpectedVersionName) {
        throw "Release APK identity is not the exact Labs updater identity."
    }
    $permissionNames = @($Permissions | ForEach-Object {
        if ($_ -match "name='([^']+)'") { $Matches[1] }
    } | Where-Object { $_ })
    $expectedPermissions = @(
        "android.permission.INTERNET",
        "android.permission.REQUEST_INSTALL_PACKAGES"
    )
    if (@(Compare-Object $permissionNames $expectedPermissions -SyncWindow 0).
            Count -ne 0) {
        throw "Release APK permission closure differs from the exact allowlist."
    }
    $tree = $ManifestTree -join "`n"
    foreach ($forbidden in @(
        "E2ePackageUpdaterCliProvider",
        "E2ePackageUpdateService",
        "android.app.Service",
        "foregroundServiceType",
        "debuggable",
        "testOnly"
    )) {
        if ($tree.Contains($forbidden)) {
            throw "Release APK contains forbidden debug/E2E surface: $forbidden"
        }
    }
    $queries = [regex]::Match(
        $tree,
        "(?ms)^\s*E: queries[^\r\n]*\r?\n(?<body>.*?)(?=^\s*E: application\b)"
    )
    $queryPackageNames = @(
        [regex]::Matches(
            $queries.Groups["body"].Value,
            '(?m)^\s*A: .*android:name(?:\([^)]*\))?="([^"]+)"'
        ) | ForEach-Object { $_.Groups[1].Value }
    )
    $activity = [regex]::Match(
        $tree,
        "(?ms)^\s*E: activity[^\r\n]*\r?\n(?<body>.*?)(?=" +
            "^\s*E: (?:receiver|provider|service)\b|\z)"
    )
    $receiver = [regex]::Match(
        $tree,
        "(?ms)^\s*E: receiver[^\r\n]*\r?\n(?<body>.*?)(?=" +
            "^\s*E: (?:activity|provider|service)\b|\z)"
    )
    if ([regex]::Matches($tree, "E: provider ").Count -ne 0 -or
        [regex]::Matches($tree, "E: service ").Count -ne 0 -or
        [regex]::Matches($tree, "E: activity").Count -ne 1 -or
        [regex]::Matches($tree, "E: receiver").Count -ne 1 -or
        -not $queries.Success -or
        [regex]::Matches(
            $queries.Groups["body"].Value,
            "(?m)^\s*E: package "
        ).Count -ne 1 -or
        $queryPackageNames.Count -ne 1 -or
        $queryPackageNames[0] -ne $ExpectedPackageName -or
        -not $activity.Success -or
        -not $activity.Groups["body"].Value.Contains(
            "io.github.mesmerprism.rustyquest.packageupdater.PackageUpdaterActivity") -or
        $activity.Groups["body"].Value -notmatch
            "(?m)^\s*A: .*android:exported\([^)]*\)=true\s*$" -or
        -not $receiver.Success -or
        -not $receiver.Groups["body"].Value.Contains(
            "io.github.mesmerprism.rustyquest.packageupdater.PackageInstallCallbackReceiver") -or
        $receiver.Groups["body"].Value -notmatch
            "(?m)^\s*A: .*android:exported\([^)]*\)=false\s*$") {
        throw "Release APK component/query closure differs from the exact manifest."
    }
    $identity
}

function Assert-PackageUpdaterE2eArtifact {
    param([string[]]$Badging, [string[]]$ManifestTree)
    $identity = ConvertFrom-PackageUpdaterBadging $Badging
    $tree = $ManifestTree -join "`n"
    if ($identity.package_name -ne
            "io.github.mesmerprism.rustyquest.packageupdater.labs.e2ecli" -or
        -not $tree.Contains("E2ePackageUpdaterCliProvider") -or
        -not $tree.Contains("E2ePackageUpdateService")) {
        throw "E2E APK does not retain its distinct test-only identity."
    }
    $identity
}
