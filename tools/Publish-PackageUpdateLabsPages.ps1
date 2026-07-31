[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$FeedRoot,
    [Parameter(Mandatory = $true)][string]$TargetPath,
    [Parameter(Mandatory = $true)][string]$AndroidBuildToolsDirectory,
    [Parameter(Mandatory = $true)][string]$TrustedKeyId,
    [Parameter(Mandatory = $true)][string]$TrustedPublicKeyBase64Url,
    [string]$HttpsOrigin = "https://mesmerprism.github.io",
    [string]$SiteBasePath = "rusty-quest",
    [switch]$LibraryOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot "package_updater\PublicationContract.ps1")

function Assert-ExactProperties($Value, [string[]]$Names, [string]$Label) {
    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $expected = @($Names | Sort-Object)
    if (@(Compare-Object $actual $expected -SyncWindow 0).Count -ne 0) {
        throw "$Label fields differ from the exact contract."
    }
}

function Assert-PositiveJsonInteger($Value, [string]$Label) {
    if (($Value -isnot [int] -and $Value -isnot [long]) -or
        [int64]$Value -lt 1) {
        throw "$Label must be a positive JSON integer."
    }
}

function Assert-ExactCanonicalHttpsUrl(
    [string]$Value,
    [string]$Expected,
    [string]$Label
) {
    $uri = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -cne "https" -or -not $uri.IsDefaultPort -or
        -not [string]::IsNullOrEmpty($uri.UserInfo) -or
        -not [string]::IsNullOrEmpty($uri.Query) -or
        -not [string]::IsNullOrEmpty($uri.Fragment) -or
        $Value -cne $Expected) {
        throw "$Label is not the exact canonical HTTPS route."
    }
}

function Assert-LabsTargetContract($Target) {
    Assert-ExactProperties $Target @(
        "schema", "repository", "release_id", "release_api_url",
        "release_html_url", "release_tag", "source_revision", "source_tree",
        "package_name", "version_code", "version_name", "signer_sha256",
        "apk", "owner_metadata", "bundle_manifest"
    ) "Labs feed target"
    foreach ($assetName in @("apk", "owner_metadata", "bundle_manifest")) {
        Assert-ExactProperties $Target.$assetName @(
            "id", "name", "browser_download_url", "sha256", "bytes"
        ) "Labs feed target $assetName"
    }
    $releaseMatch = [regex]::Match(
        [string]$Target.release_tag,
        "^v(?<version>(?<major>0|[1-9][0-9]{0,3})\." +
            "(?<minor>0|[1-9][0-9]?)\." +
            "(?<patch>0|[1-9][0-9]?)-alpha\." +
            "(?<iteration>[1-9]|[1-8][0-9]|9[0-8]))$"
    )
    $expectedVersionCode = if ($releaseMatch.Success) {
        $major = [int64]$releaseMatch.Groups["major"].Value
        $minor = [int64]$releaseMatch.Groups["minor"].Value
        $patch = [int64]$releaseMatch.Groups["patch"].Value
        if ($major -gt 2099) {
            0L
        } else {
            $major * 1000000L + $minor * 10000L + $patch * 100L +
                [int64]$releaseMatch.Groups["iteration"].Value
        }
    } else {
        0L
    }
    Assert-PositiveJsonInteger $Target.release_id "Labs feed target release_id"
    Assert-PositiveJsonInteger $Target.version_code "Labs feed target version_code"
    if ([int64]$Target.version_code -gt [int]::MaxValue) {
        throw "Labs feed target version_code exceeds the safe Android integer bound."
    }
    if ($Target.schema -cne "rusty.quest.package_update_labs_target.v2" -or
        $Target.repository -cne "MesmerPrism/Rusty-Kiosk" -or
        -not $releaseMatch.Success -or
        $Target.version_name -cne $releaseMatch.Groups["version"].Value -or
        $Target.source_revision -cnotmatch "^[0-9a-f]{40}$" -or
        $Target.source_tree -cnotmatch "^[0-9a-f]{40}$" -or
        $Target.package_name -cne "io.github.mesmerprism.rustykiosk.labs" -or
        [int64]$Target.version_code -ne $expectedVersionCode -or
        $Target.signer_sha256 -cnotmatch "^sha256:[0-9a-f]{64}$" -or
        $Target.apk.name -cne "rusty-kiosk.apk" -or
        $Target.owner_metadata.name -cne "rusty-kiosk-labs-owner-release.json" -or
        $Target.bundle_manifest.name -cne "bundle-manifest.json") {
        throw "Labs feed target is not the exact Kiosk Labs publication tuple."
    }
    $releaseApiUrl =
        "https://api.github.com/repos/$($Target.repository)/releases/$($Target.release_id)"
    $releaseHtmlUrl =
        "https://github.com/$($Target.repository)/releases/tag/$($Target.release_tag)"
    Assert-ExactCanonicalHttpsUrl $Target.release_api_url $releaseApiUrl `
        "Labs feed target release_api_url"
    Assert-ExactCanonicalHttpsUrl $Target.release_html_url $releaseHtmlUrl `
        "Labs feed target release_html_url"
    $targetAssetIds = [Collections.Generic.HashSet[long]]::new()
    foreach ($assetName in @("apk", "owner_metadata", "bundle_manifest")) {
        $asset = $Target.$assetName
        Assert-PositiveJsonInteger $asset.id "Labs feed target $assetName id"
        if (-not $targetAssetIds.Add([int64]$asset.id)) {
            throw "Labs feed target selected asset IDs must be unique."
        }
        $expectedDownloadUrl =
            "https://github.com/$($Target.repository)/releases/download/" +
            "$($Target.release_tag)/$($asset.name)"
        Assert-ExactCanonicalHttpsUrl $asset.browser_download_url `
            $expectedDownloadUrl "Labs feed target $assetName browser_download_url"
        if ($asset.sha256 -cnotmatch "^sha256:[0-9a-f]{64}$" -or
            ($asset.bytes -isnot [int] -and $asset.bytes -isnot [long]) -or
            [int64]$asset.bytes -lt 1) {
            throw "Labs feed target $assetName identity is not canonical."
        }
    }
    $releaseMatch
}

function Assert-PinnedReleaseContract($Release, $Target) {
    Assert-PositiveJsonInteger $Release.id "Pinned Kiosk Labs release id"
    $expectedReleaseApi =
        "https://api.github.com/repos/$($Target.repository)/releases/$($Target.release_id)"
    $expectedReleaseHtml =
        "https://github.com/$($Target.repository)/releases/tag/$($Target.release_tag)"
    Assert-ExactCanonicalHttpsUrl ([string]$Release.url) $expectedReleaseApi `
        "Pinned Kiosk Labs release API URL"
    Assert-ExactCanonicalHttpsUrl ([string]$Release.html_url) $expectedReleaseHtml `
        "Pinned Kiosk Labs release HTML URL"
    $expectedNames = @(
        "RUSTY-KIOSK-LICENSE.txt",
        "RUSTY-KIOSK-SOURCE.txt",
        "bundle-manifest.json",
        "rusty-kiosk-labs-owner-release.json",
        "rusty-kiosk-setup-helper.apk",
        "rusty-kiosk.apk"
    ) | Sort-Object
    $actualNames = @($Release.assets.name | Sort-Object)
    if ([int64]$Release.id -ne [int64]$Target.release_id -or
        $Release.draft -isnot [bool] -or $Release.prerelease -isnot [bool] -or
        $Release.draft -ne $false -or $Release.prerelease -ne $true -or
        $Release.tag_name -cne $Target.release_tag -or
        $Release.target_commitish -cne $Target.source_revision -or
        ($expectedNames -join "`n") -cne ($actualNames -join "`n")) {
        throw "Pinned Kiosk Labs release identity, source, or six-asset closure differs."
    }
    $assetIds = [Collections.Generic.HashSet[long]]::new()
    foreach ($name in @("apk", "owner_metadata", "bundle_manifest")) {
        $expected = $Target.$name
        $remote = @($Release.assets | Where-Object name -CEQ $expected.name)
        if ($remote.Count -ne 1) {
            throw "Pinned Kiosk Labs release is missing exact asset $($expected.name)."
        }
        Assert-PinnedAsset $remote[0] $expected $expected.name
        if (-not $assetIds.Add([int64]$remote[0].id)) {
            throw "Pinned Kiosk Labs selected asset IDs are not unique."
        }
    }
}

function Assert-PinnedOwnerMetadata($Owner, $Target) {
    Assert-ExactProperties $Owner @(
        "schema", "repository", "product", "product_channel", "maturity",
        "distribution_track", "prerelease", "tag", "version",
        "source_revision", "source_tree", "installation_identity",
        "coinstallable_lineage", "bundle_manifest", "primary_artifact"
    ) "Kiosk Labs owner metadata"
    Assert-ExactProperties $Owner.coinstallable_lineage @(
        "identity_mode", "package_name", "signer_sha256", "version_name",
        "version_code", "exit_policy"
    ) "Kiosk Labs owner metadata coinstallable_lineage"
    Assert-ExactProperties $Owner.bundle_manifest @(
        "schema", "name", "sha256", "bytes"
    ) "Kiosk Labs owner metadata bundle_manifest"
    Assert-ExactProperties $Owner.primary_artifact @(
        "role", "name", "sha256", "bytes"
    ) "Kiosk Labs owner metadata primary_artifact"
    Assert-PositiveJsonInteger $Owner.coinstallable_lineage.version_code `
        "Kiosk Labs owner metadata coinstallable_lineage.version_code"
    Assert-PositiveJsonInteger $Owner.bundle_manifest.bytes `
        "Kiosk Labs owner metadata bundle_manifest.bytes"
    Assert-PositiveJsonInteger $Owner.primary_artifact.bytes `
        "Kiosk Labs owner metadata primary_artifact.bytes"
    if ($Owner.schema -cne "rusty.kiosk.labs_release_owner_metadata.v2" -or
        $Owner.repository -cne "MesmerPrism/Rusty-Kiosk" -or
        $Owner.product -cne "rusty-kiosk-labs" -or
        $Owner.product_channel -cne "labs" -or $Owner.maturity -cne "alpha" -or
        $Owner.distribution_track -cne "github-prerelease" -or
        $Owner.prerelease -isnot [bool] -or $Owner.prerelease -ne $true -or
        $Owner.tag -cne $Target.release_tag -or
        $Owner.version -cne $Target.version_name -or
        $Owner.source_revision -cne $Target.source_revision -or
        $Owner.source_tree -cne $Target.source_tree -or
        $Owner.installation_identity -cne $Target.package_name -or
        $Owner.coinstallable_lineage.identity_mode -cne
            "separate-coinstallable" -or
        $Owner.coinstallable_lineage.package_name -cne $Target.package_name -or
        [uint64]$Owner.coinstallable_lineage.version_code -ne
            [uint64]$Target.version_code -or
        $Owner.coinstallable_lineage.version_name -cne $Target.version_name -or
        $Owner.coinstallable_lineage.exit_policy -cne
            "uninstall-labs-without-changing-stable" -or
        ("sha256:" + $Owner.coinstallable_lineage.signer_sha256) -cne
            $Target.signer_sha256 -or
        $Owner.coinstallable_lineage.signer_sha256 -cnotmatch
            "^[0-9a-f]{64}$" -or
        $Owner.primary_artifact.role -cne "complete-product" -or
        $Owner.primary_artifact.name -cne $Target.apk.name -or
        $Owner.primary_artifact.sha256 -cnotmatch "^[0-9a-f]{64}$" -or
        ("sha256:" + $Owner.primary_artifact.sha256) -cne $Target.apk.sha256 -or
        [int64]$Owner.primary_artifact.bytes -ne [int64]$Target.apk.bytes -or
        $Owner.bundle_manifest.schema -cne
            "meta.quest.file_manager.rusty_kiosk_bundle.v2" -or
        $Owner.bundle_manifest.name -cne $Target.bundle_manifest.name -or
        $Owner.bundle_manifest.sha256 -cnotmatch "^[0-9a-f]{64}$" -or
        ("sha256:" + $Owner.bundle_manifest.sha256) -cne
            $Target.bundle_manifest.sha256 -or
        [int64]$Owner.bundle_manifest.bytes -ne
            [int64]$Target.bundle_manifest.bytes) {
        throw "Kiosk Labs owner metadata differs from the pinned target."
    }
}

function Read-StrictJson([string]$Path, [string]$Label) {
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -eq 0 -or $bytes.Length -gt 131072) {
        throw "$Label has an invalid size."
    }
    try {
        $strictUtf8 = [System.Text.UTF8Encoding]::new($false, $true)
        $jsonText = $strictUtf8.GetString($bytes)
        $document = [System.Text.Json.JsonDocument]::Parse($jsonText)
        try {
            function Assert-NoDuplicateJsonProperties(
                [System.Text.Json.JsonElement]$Element,
                [string]$Location
            ) {
                if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) {
                    $names = [Collections.Generic.HashSet[string]]::new(
                        [StringComparer]::Ordinal
                    )
                    foreach ($property in $Element.EnumerateObject()) {
                        if (-not $names.Add($property.Name)) {
                            throw "$Label contains duplicate property '$($property.Name)' at $Location."
                        }
                        Assert-NoDuplicateJsonProperties `
                            -Element $property.Value `
                            -Location "$Location.$($property.Name)"
                    }
                } elseif ($Element.ValueKind -eq
                    [System.Text.Json.JsonValueKind]::Array) {
                    $index = 0
                    foreach ($item in $Element.EnumerateArray()) {
                        Assert-NoDuplicateJsonProperties `
                            -Element $item -Location "$Location[$index]"
                        $index++
                    }
                }
            }
            Assert-NoDuplicateJsonProperties -Element $document.RootElement -Location '$'
        } finally {
            $document.Dispose()
        }
        $jsonText | ConvertFrom-Json -NoEnumerate
    } catch {
        throw "$Label is malformed."
    }
}

function Assert-NoReparseAncestor([string]$Path) {
    $current = [System.IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrEmpty($current)) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Labs feed path contains a reparse-point ancestor."
            }
        }
        $parent = Split-Path -Parent $current
        if ([string]::IsNullOrEmpty($parent) -or $parent -eq $current) {
            break
        }
        $current = $parent
    }
}

function Test-AllowedFeedFile([string]$Path) {
    if ($Path -ceq ".nojekyll" -or
        $Path -ceq "package-updates/rusty-kiosk/labs/current.json") {
        return $true
    }
    if ($Path -match (
        "^package-updates/rusty-kiosk/labs/generations/" +
        "s[1-9][0-9]{0,19}-v[1-9][0-9]{0,19}-" +
        "[0-9a-f]{16}-[0-9a-f]{16}/" +
        "(?:envelope\.json|publication-receipt\.json)$"
    )) {
        return $true
    }
    $version = "(?:0|[1-9][0-9]{0,3})\." +
        "(?:0|[1-9][0-9]?)\.(?:0|[1-9][0-9]?)-alpha\." +
        "(?:[1-9]|[1-8][0-9]|9[0-8])"
    $Path -match (
        "^package-updates/rusty-kiosk/labs/artifacts/sha256/" +
        "[0-9a-f]{64}/rusty-kiosk-$version\.apk$"
    )
}

function Assert-FeedWorktreePreflight([string]$Root) {
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        throw "Labs feed root must be an existing Git worktree."
    }
    Assert-NoReparseAncestor $Root
    $inside = (& git -C $Root rev-parse --is-inside-work-tree).Trim()
    if ($LASTEXITCODE -ne 0 -or $inside -cne "true") {
        throw "Labs feed root is not an exact Git worktree."
    }
    $dirty = @(& git -C $Root status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0 -or $dirty.Count -ne 0) {
        throw "Labs feed worktree must be clean before publication."
    }
    $topNames = @(
        Get-ChildItem -LiteralPath $Root -Force | ForEach-Object Name | Sort-Object
    )
    foreach ($name in $topNames) {
        if ($name -cnotin @(".git", ".nojekyll", "package-updates")) {
            throw "Labs feed worktree contains an unexpected top-level path."
        }
    }
    foreach ($item in Get-ChildItem -LiteralPath $Root -Recurse -Force |
        Where-Object { -not $_.FullName.StartsWith(
                (Join-Path $Root ".git"),
                [StringComparison]::OrdinalIgnoreCase
            ) }) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Labs feed content contains a reparse point."
        }
    }
    $tracked = @(& git -C $Root ls-files --stage)
    if ($LASTEXITCODE -ne 0 -or $tracked.Count -lt 1) {
        throw "Labs feed tracked inventory is absent."
    }
    foreach ($entry in $tracked) {
        $match = [regex]::Match(
            [string]$entry,
            "^(?<mode>[0-9]{6}) (?<oid>[0-9a-f]{40}) 0`t(?<path>.+)$"
        )
        if (-not $match.Success -or $match.Groups["mode"].Value -cne "100644" -or
            -not (Test-AllowedFeedFile $match.Groups["path"].Value)) {
            throw "Labs feed contains an unexpected tracked path or Git mode."
        }
    }
}

function Get-FeedFileInventory([string]$Root) {
    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd(
        [IO.Path]::DirectorySeparatorChar
    )
    $rootPrefix = $rootFull + [IO.Path]::DirectorySeparatorChar
    $inventory = [Collections.Generic.Dictionary[string, object]]::new(
        [StringComparer]::Ordinal
    )
    foreach ($file in Get-ChildItem -LiteralPath $rootFull -Recurse -Force -File) {
        if ($file.FullName.StartsWith(
                (Join-Path $rootFull ".git"),
                [StringComparison]::OrdinalIgnoreCase
            )) {
            continue
        }
        if (-not $file.FullName.StartsWith(
                $rootPrefix, [StringComparison]::OrdinalIgnoreCase
            ) -or
            ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Feed inventory escaped its exact non-reparse root."
        }
        $relative = $file.FullName.Substring($rootPrefix.Length).Replace("\", "/")
        if (-not (Test-AllowedFeedFile $relative) -or
            -not $inventory.TryAdd($relative, [pscustomobject]@{
                    bytes = [int64]$file.Length
                    sha256 = (Get-FileHash -LiteralPath $file.FullName `
                        -Algorithm SHA256).Hash.ToLowerInvariant()
                })) {
            throw "Feed inventory contains an unexpected or duplicate file."
        }
    }
    $inventory
}

function Copy-FeedToTransaction([string]$SourceRoot, [string]$TransactionRoot) {
    New-Item -ItemType Directory -Path $TransactionRoot | Out-Null
    foreach ($name in @(".nojekyll", "package-updates")) {
        $source = Join-Path $SourceRoot $name
        if (Test-Path -LiteralPath $source) {
            Copy-Item -LiteralPath $source -Destination (
                Join-Path $TransactionRoot $name
            ) -Recurse
        }
    }
    $sourceInventory = Get-FeedFileInventory $SourceRoot
    $transactionInventory = Get-FeedFileInventory $TransactionRoot
    if ((($sourceInventory.Keys | Sort-Object) -join "`n") -cne
        (($transactionInventory.Keys | Sort-Object) -join "`n")) {
        throw "Transactional feed snapshot inventory differs from the clean feed."
    }
    foreach ($path in $sourceInventory.Keys) {
        if ($sourceInventory[$path].bytes -ne $transactionInventory[$path].bytes -or
            $sourceInventory[$path].sha256 -cne
                $transactionInventory[$path].sha256) {
            throw "Transactional feed snapshot bytes differ from the clean feed."
        }
    }
}

function Install-FeedTransaction(
    [string]$CandidateRoot,
    [string]$DestinationRoot,
    $Receipt
) {
    $before = Get-FeedFileInventory $DestinationRoot
    $candidate = Get-FeedFileInventory $CandidateRoot
    $pointerRelative = "package-updates/rusty-kiosk/labs/current.json"
    $generationPrefix =
        "package-updates/rusty-kiosk/labs/generations/$($Receipt.generation)"
    $artifactHash = ([string]$Receipt.apk_sha256).Substring(7)
    $artifactRelative =
        "package-updates/rusty-kiosk/labs/artifacts/sha256/$artifactHash/" +
        "rusty-kiosk-$($Receipt.version_name).apk"
    $expectedNew = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal
    )
    $null = $expectedNew.Add("$generationPrefix/envelope.json")
    $null = $expectedNew.Add("$generationPrefix/publication-receipt.json")
    if (-not $before.ContainsKey($artifactRelative)) {
        $null = $expectedNew.Add($artifactRelative)
    }
    foreach ($path in $before.Keys) {
        if (-not $candidate.ContainsKey($path)) {
            throw "Transactional publication removed existing feed content."
        }
        if ($path -cne $pointerRelative -and
            ($before[$path].bytes -ne $candidate[$path].bytes -or
                $before[$path].sha256 -cne $candidate[$path].sha256)) {
            throw "Transactional publication rewrote immutable feed content."
        }
    }
    $actualNew = @(
        $candidate.Keys | Where-Object {
            -not $before.ContainsKey($_) -and $_ -cne $pointerRelative
        } | Sort-Object
    )
    if (($actualNew -join "`n") -cne
        (($expectedNew | Sort-Object) -join "`n") -or
        -not $candidate.ContainsKey($pointerRelative) -or
        ($before.ContainsKey($pointerRelative) -and
            $before[$pointerRelative].sha256 -ceq
                $candidate[$pointerRelative].sha256)) {
        throw "Transactional publication delta is not one exact new generation."
    }

    $destinationFull = [IO.Path]::GetFullPath($DestinationRoot).TrimEnd(
        [IO.Path]::DirectorySeparatorChar
    )
    $destinationPrefix = $destinationFull + [IO.Path]::DirectorySeparatorChar
    $createdFiles = [Collections.Generic.List[string]]::new()
    $createdDirectories = [Collections.Generic.List[string]]::new()
    $pointerPath = Join-Path $destinationFull $pointerRelative
    $pointerExisted = [IO.File]::Exists($pointerPath)
    $priorPointerBytes = if ($pointerExisted) {
        [IO.File]::ReadAllBytes($pointerPath)
    } else {
        $null
    }
    $pointerChanged = $false
    $temporaryDestination = $null
    try {
        foreach ($relative in $actualNew) {
            $source = Join-Path $CandidateRoot $relative
            $destination = [IO.Path]::GetFullPath(
                (Join-Path $destinationFull $relative)
            )
            if (-not $destination.StartsWith(
                    $destinationPrefix, [StringComparison]::OrdinalIgnoreCase
                ) -or [IO.File]::Exists($destination)) {
                throw "Transactional publication destination is unsafe or occupied."
            }
            $missingDirectories = [Collections.Generic.List[string]]::new()
            $parent = Split-Path -Parent $destination
            while (-not [IO.Directory]::Exists($parent)) {
                if (-not $parent.StartsWith(
                        $destinationPrefix,
                        [StringComparison]::OrdinalIgnoreCase
                    )) {
                    throw "Transactional publication directory escaped the feed root."
                }
                $missingDirectories.Add($parent)
                $parent = Split-Path -Parent $parent
            }
            for ($index = $missingDirectories.Count - 1; $index -ge 0; $index--) {
                [IO.Directory]::CreateDirectory($missingDirectories[$index]) | Out-Null
                $createdDirectories.Add($missingDirectories[$index])
            }
            $temporaryDestination = Join-Path (Split-Path -Parent $destination) (
                ".publish-$([guid]::NewGuid().ToString('N')).tmp"
            )
            [IO.File]::Copy($source, $temporaryDestination, $false)
            [IO.File]::Move($temporaryDestination, $destination)
            $temporaryDestination = $null
            $createdFiles.Add($destination)
        }
        $pointerParent = Split-Path -Parent $pointerPath
        if (-not [IO.Directory]::Exists($pointerParent)) {
            throw "Transactional pointer parent was not prepared by immutable content."
        }
        $temporaryDestination = Join-Path $pointerParent (
            ".current-$([guid]::NewGuid().ToString('N')).tmp"
        )
        [IO.File]::Copy(
            (Join-Path $CandidateRoot $pointerRelative),
            $temporaryDestination,
            $false
        )
        [IO.File]::Move($temporaryDestination, $pointerPath, $true)
        $temporaryDestination = $null
        $pointerChanged = $true

        $installed = Get-FeedFileInventory $DestinationRoot
        if ((($installed.Keys | Sort-Object) -join "`n") -cne
            (($candidate.Keys | Sort-Object) -join "`n")) {
            throw "Installed transactional feed inventory differs."
        }
        foreach ($path in $candidate.Keys) {
            if ($installed[$path].bytes -ne $candidate[$path].bytes -or
                $installed[$path].sha256 -cne $candidate[$path].sha256) {
                throw "Installed transactional feed bytes differ."
            }
        }
    } catch {
        $failure = $_
        try {
            if ($null -ne $temporaryDestination -and
                [IO.File]::Exists($temporaryDestination)) {
                [IO.File]::Delete($temporaryDestination)
            }
            if ($pointerChanged) {
                if ($pointerExisted) {
                    [IO.File]::WriteAllBytes($pointerPath, $priorPointerBytes)
                } elseif ([IO.File]::Exists($pointerPath)) {
                    [IO.File]::Delete($pointerPath)
                }
            }
            for ($index = $createdFiles.Count - 1; $index -ge 0; $index--) {
                if ([IO.File]::Exists($createdFiles[$index])) {
                    [IO.File]::Delete($createdFiles[$index])
                }
            }
            for ($index = $createdDirectories.Count - 1; $index -ge 0; $index--) {
                if ([IO.Directory]::Exists($createdDirectories[$index])) {
                    [IO.Directory]::Delete($createdDirectories[$index], $false)
                }
            }
        } catch {
            throw "Transactional feed install failed and exact rollback also failed."
        }
        throw $failure
    }
}

function Assert-PinnedAsset($ReleaseAsset, $Expected, [string]$Label) {
    if ($null -eq $ReleaseAsset) {
        throw "$Label remote identity differs from the pinned target."
    }
    Assert-PositiveJsonInteger $ReleaseAsset.id "$Label remote asset id"
    $expectedApiUrl =
        "https://api.github.com/repos/MesmerPrism/Rusty-Kiosk/releases/assets/" +
        "$($Expected.id)"
    Assert-ExactCanonicalHttpsUrl ([string]$ReleaseAsset.url) $expectedApiUrl `
        "$Label remote asset API URL"
    Assert-ExactCanonicalHttpsUrl ([string]$ReleaseAsset.browser_download_url) `
        $Expected.browser_download_url "$Label remote asset download URL"
    if ([int64]$ReleaseAsset.id -ne [int64]$Expected.id -or
        $ReleaseAsset.name -cne $Expected.name -or
        $ReleaseAsset.state -cne "uploaded" -or
        [int64]$ReleaseAsset.size -ne [int64]$Expected.bytes -or
        $ReleaseAsset.digest -cne $Expected.sha256) {
        throw "$Label remote identity differs from the pinned target."
    }
}

function Get-PinnedGitHubTagSource(
    [string]$Repository,
    [string]$Tag,
    [string]$ExpectedRevision,
    [string]$ExpectedTree
) {
    $tagRef = & gh api "repos/$Repository/git/ref/tags/$Tag" |
        ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or $null -eq $tagRef.object) {
        throw "Pinned Kiosk Labs tag ref readback failed."
    }
    $expectedRefUrl =
        "https://api.github.com/repos/$Repository/git/refs/tags/$Tag"
    Assert-ExactCanonicalHttpsUrl ([string]$tagRef.url) $expectedRefUrl `
        "Pinned Kiosk Labs tag ref URL"
    if ($tagRef.ref -cne "refs/tags/$Tag") {
        throw "Pinned Kiosk Labs tag ref identity differs from the target."
    }
    $object = $tagRef.object
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    for ($depth = 0; $depth -lt 6; $depth++) {
        $type = [string]$object.type
        $sha = ([string]$object.sha).ToLowerInvariant()
        if ($type -cnotin @("tag", "commit") -or
            $sha -cnotmatch "^[0-9a-f]{40}$" -or -not $seen.Add($sha)) {
            throw "Pinned Kiosk Labs tag peel is malformed or cyclic."
        }
        $expectedObjectUrl =
            "https://api.github.com/repos/$Repository/git/${type}s/$sha"
        Assert-ExactCanonicalHttpsUrl ([string]$object.url) $expectedObjectUrl `
            "Pinned Kiosk Labs tag peel object URL"
        if ($type -ceq "commit") {
            break
        }
        $tagObject = & gh api "repos/$Repository/git/tags/$sha" |
            ConvertFrom-Json
        if ($LASTEXITCODE -ne 0 -or $null -eq $tagObject.object) {
            throw "Pinned Kiosk Labs annotated tag readback failed."
        }
        $expectedTagObjectUrl =
            "https://api.github.com/repos/$Repository/git/tags/$sha"
        Assert-ExactCanonicalHttpsUrl ([string]$tagObject.url) `
            $expectedTagObjectUrl "Pinned Kiosk Labs annotated tag URL"
        if ($depth -eq 0 -and $tagObject.tag -cne $Tag) {
            throw "Pinned Kiosk Labs annotated tag name differs from the target."
        }
        $object = $tagObject.object
    }
    $revision = ([string]$object.sha).ToLowerInvariant()
    if ([string]$object.type -cne "commit" -or
        $revision -cne $ExpectedRevision) {
        throw "Pinned Kiosk Labs tag does not resolve to the target revision."
    }
    $commit = & gh api "repos/$Repository/git/commits/$revision" |
        ConvertFrom-Json
    $expectedCommitUrl =
        "https://api.github.com/repos/$Repository/git/commits/$revision"
    if ($LASTEXITCODE -ne 0 -or $commit.sha -cne $revision -or
        ([string]$commit.tree.sha).ToLowerInvariant() -cne $ExpectedTree) {
        throw "Pinned Kiosk Labs source tree readback differs from the target."
    }
    Assert-ExactCanonicalHttpsUrl ([string]$commit.url) $expectedCommitUrl `
        "Pinned Kiosk Labs commit URL"
    $expectedTreeUrl =
        "https://api.github.com/repos/$Repository/git/trees/$ExpectedTree"
    Assert-ExactCanonicalHttpsUrl ([string]$commit.tree.url) $expectedTreeUrl `
        "Pinned Kiosk Labs source tree URL"
    [pscustomobject]@{ revision = $revision; tree = $ExpectedTree }
}

if ($LibraryOnly) {
    return
}
if ($PSVersionTable.PSEdition -ne "Core" -or
    $PSVersionTable.PSVersion -lt [version]"7.6") {
    throw "Labs Pages publication requires PowerShell 7.6 Core or newer."
}
if ([string]::IsNullOrWhiteSpace($env:GH_TOKEN)) {
    throw "GH_TOKEN is required for authoritative release readback."
}
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$sourceDirt = @(& git -C $repoRoot status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0 -or $sourceDirt.Count -ne 0) {
    throw "Labs feed publisher source must be a clean exact Git checkout."
}

$TargetPath = (Resolve-Path -LiteralPath $TargetPath).Path
$AndroidBuildToolsDirectory = (
    Resolve-Path -LiteralPath $AndroidBuildToolsDirectory
).Path
$FeedRoot = [System.IO.Path]::GetFullPath($FeedRoot)
Assert-FeedWorktreePreflight $FeedRoot
$target = Read-StrictJson $TargetPath "Labs feed target"
$null = Assert-LabsTargetContract $target

$null = Get-PinnedGitHubTagSource `
    -Repository $target.repository `
    -Tag $target.release_tag `
    -ExpectedRevision $target.source_revision `
    -ExpectedTree $target.source_tree

$releaseJson = & gh api (
    "repos/$($target.repository)/releases/tags/$($target.release_tag)"
)
if ($LASTEXITCODE -ne 0) {
    throw "Could not read the pinned Kiosk Labs release."
}
$release = $releaseJson | ConvertFrom-Json
Assert-PinnedReleaseContract $release $target

$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) (
    "rusty-quest-labs-feed-$([guid]::NewGuid().ToString('N'))"
)
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
$downloadRoot = Join-Path $temporaryRoot "downloads"
$transactionRoot = Join-Path $temporaryRoot "feed-candidate"
try {
    New-Item -ItemType Directory -Path $downloadRoot | Out-Null
    Copy-FeedToTransaction -SourceRoot $FeedRoot `
        -TransactionRoot $transactionRoot
    foreach ($asset in @($target.apk, $target.owner_metadata, $target.bundle_manifest)) {
        & gh release download $target.release_tag `
            --repo $target.repository --pattern $asset.name --dir $downloadRoot
        if ($LASTEXITCODE -ne 0) {
            throw "Could not download pinned Kiosk Labs asset $($asset.name)."
        }
        $path = Join-Path $downloadRoot $asset.name
        $hash = "sha256:" + (
            Get-FileHash -LiteralPath $path -Algorithm SHA256
        ).Hash.ToLowerInvariant()
        if ($hash -cne $asset.sha256 -or
            (Get-Item -LiteralPath $path).Length -ne [int64]$asset.bytes) {
            throw "Downloaded Kiosk Labs asset $($asset.name) differs from its pin."
        }
    }
    $owner = Read-StrictJson (
        Join-Path $downloadRoot $target.owner_metadata.name
    ) "Kiosk Labs owner metadata"
    Assert-PinnedOwnerMetadata $owner $target

    $channelDirectory = Join-Path $transactionRoot `
        "package-updates\rusty-kiosk\labs"
    $pointerPath = Join-Path $channelDirectory "current.json"
    $prior = Read-PackageUpdatePointer -Path $pointerPath
    $sequence = [uint64][DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $issuedAt = $sequence
    $expiresAt = $issuedAt + 86400000
    $arguments = @{
        ApkPath = Join-Path $downloadRoot $target.apk.name
        OutputDirectory = $transactionRoot
        AndroidBuildToolsDirectory = $AndroidBuildToolsDirectory
        HttpsOrigin = $HttpsOrigin
        SiteBasePath = $SiteBasePath
        TrustedPublicKeyBase64Url = $TrustedPublicKeyBase64Url
        KeyId = $TrustedKeyId
        PackageName = $target.package_name
        SignerSha256 = $target.signer_sha256
        VersionCode = [uint64]$target.version_code
        VersionName = $target.version_name
        Sequence = $sequence
        IssuedAtMs = $issuedAt
        ExpiresAtMs = $expiresAt
        ManifestId = "rusty-kiosk.labs.s$sequence.v$($target.version_code)"
    }
    if ($null -eq $prior) {
        $arguments.ExpectPriorAbsent = $true
    } else {
        $arguments.ExpectedPriorPointerSha256 = $prior.sha256
        $arguments.ExpectedPriorEnvelopeSha256 = $prior.value.envelope_sha256
        if ([uint64]$target.version_code -eq [uint64]$prior.value.version_code) {
            $arguments.Refresh = $true
        } elseif ([uint64]$target.version_code -lt [uint64]$prior.value.version_code) {
            throw "Pinned Kiosk Labs target is older than the live feed."
        }
    }
    $receipt = & (Join-Path $PSScriptRoot "Publish-PackageUpdateManifest.ps1") `
        @arguments
    if ($null -eq $receipt -or $receipt.schema -ne
        "rusty.quest.package_update_publication_receipt.v3") {
        throw "Labs feed publisher did not return its exact owner receipt."
    }
    $rootItems = @(Get-ChildItem -LiteralPath $transactionRoot -Force)
    $rootNames = @($rootItems.Name | Sort-Object)
    if (($rootNames -join "`n") -cne ".nojekyll`npackage-updates") {
        throw "Labs feed root contains content outside the exact Pages closure."
    }
    $packageUpdateNames = @(
        Get-ChildItem -LiteralPath (
            Join-Path $transactionRoot "package-updates"
        ) -Force |
            ForEach-Object Name | Sort-Object
    )
    $kioskNames = @(
        Get-ChildItem -LiteralPath (
            Join-Path $transactionRoot "package-updates\rusty-kiosk"
        ) -Force | ForEach-Object Name | Sort-Object
    )
    if (($packageUpdateNames -join "`n") -cne "rusty-kiosk" -or
        ($kioskNames -join "`n") -cne "labs") {
        throw "Labs feed branch contains content outside its exact Kiosk Labs subtree."
    }
    $channelRoot = Join-Path $transactionRoot `
        "package-updates\rusty-kiosk\labs"
    $channelNames = @(
        Get-ChildItem -LiteralPath $channelRoot -Force |
            ForEach-Object Name | Sort-Object
    )
    if (($channelNames -join "`n") -cne "artifacts`ncurrent.json`ngenerations") {
        throw "Kiosk Labs channel contains a file family outside its contract."
    }
    $artifactFamilies = @(
        Get-ChildItem -LiteralPath (Join-Path $channelRoot "artifacts") -Force
    )
    if ($artifactFamilies.Count -ne 1 -or
        $artifactFamilies[0].Name -cne "sha256" -or
        -not $artifactFamilies[0].PSIsContainer) {
        throw "Kiosk Labs artifacts are outside the content-addressed family."
    }
    foreach ($artifactDirectory in Get-ChildItem -LiteralPath (
            Join-Path $channelRoot "artifacts\sha256"
        ) -Force) {
        $artifactFiles = @(Get-ChildItem -LiteralPath $artifactDirectory.FullName -Force)
        if (-not $artifactDirectory.PSIsContainer -or
            $artifactDirectory.Name -cnotmatch "^[0-9a-f]{64}$" -or
            $artifactFiles.Count -ne 1 -or $artifactFiles[0].PSIsContainer -or
            $artifactFiles[0].Name -cnotmatch (
                "^rusty-kiosk-(?:0|[1-9][0-9]{0,3})\." +
                "(?:0|[1-9][0-9]?)\.(?:0|[1-9][0-9]?)-alpha\." +
                "(?:[1-9]|[1-8][0-9]|9[0-8])\.apk$"
            )) {
            throw "Kiosk Labs content-addressed artifact layout differs."
        }
    }
    foreach ($generationDirectory in Get-ChildItem -LiteralPath (
            Join-Path $channelRoot "generations"
        ) -Force) {
        $generationFiles = @(
            Get-ChildItem -LiteralPath $generationDirectory.FullName -Force |
                ForEach-Object Name | Sort-Object
        )
        if (-not $generationDirectory.PSIsContainer -or
            $generationDirectory.Name -cnotmatch (
                "^s[1-9][0-9]{0,19}-v[1-9][0-9]{0,19}-" +
                "[0-9a-f]{16}-[0-9a-f]{16}$"
            ) -or
            ($generationFiles -join "`n") -cne
                "envelope.json`npublication-receipt.json") {
            throw "Kiosk Labs generation layout differs from the immutable contract."
        }
    }
    $feedContent = @(
        Get-Item -LiteralPath (Join-Path $transactionRoot ".nojekyll") -Force
        Get-ChildItem -LiteralPath (
            Join-Path $transactionRoot "package-updates"
        ) -Recurse -Force
    )
    foreach ($item in $feedContent) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Labs feed tree contains a forbidden reparse point."
        }
    }
    Install-FeedTransaction `
        -CandidateRoot $transactionRoot `
        -DestinationRoot $FeedRoot `
        -Receipt $receipt
    [pscustomobject]$receipt
} finally {
    $systemTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if (-not $temporaryRoot.StartsWith(
            $systemTemp, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove an unexpected Labs feed temporary directory."
    }
    if ([System.IO.Directory]::Exists($temporaryRoot)) {
        [System.IO.Directory]::Delete($temporaryRoot, $true)
    }
}
