param([string]$RepoRoot = '')

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
} else {
    $RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
}
$modulePath = Join-Path $RepoRoot 'tools\lib\NativeAppPrivateAssetProvider.psm1'
$schemaPath = Join-Path $RepoRoot 'schemas\rusty.quest.native_app_private_asset_provider.v1.schema.json'
$resolverPath = Join-Path $RepoRoot 'tools\Resolve-NativeAppBuild.ps1'
Import-Module $modulePath -Force
$builderText = Get-Content -Raw -LiteralPath (Join-Path $RepoRoot 'tools\Build-NativeRendererAndroid.ps1')
foreach ($requiredBuilderToken in @(
    'Copy-NativeAppPrivateAssetsFromClosure',
    'private_asset_provider =',
    'closure_sha256 = Get-NativeAppPrivateAssetTextSha256'
)) {
    if (-not $builderText.Contains($requiredBuilderToken, [StringComparison]::Ordinal)) {
        throw "Native renderer builder is missing private-asset integration: $requiredBuilderToken"
    }
}

function Write-JsonNoBom {
    param([Parameter(Mandatory = $true)]$Value, [Parameter(Mandatory = $true)][string]$Path)
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 30), [Text.UTF8Encoding]::new($false))
}

function Copy-JsonObject {
    param([Parameter(Mandatory = $true)]$Value)
    return ($Value | ConvertTo-Json -Depth 30 | ConvertFrom-Json)
}

function New-ProviderRequest {
    param([Parameter(Mandatory = $true)]$Manifest, [Parameter(Mandatory = $true)][string]$ManifestPath)
    $inventoryAssets = @($Manifest.assets | ForEach-Object {
        [ordered]@{
            asset_id = [string]$_.asset_id
            logical_destination = [string]$_.logical_destination
            staged_object = "private-assets/objects/$([string]$_.source_sha256)"
            source_sha256 = [string]$_.source_sha256
            source_bytes = [int64]$_.source_bytes
            media_type = [string]$_.media_type
        }
    })
    $projection = [ordered]@{
        schema = 'rusty.quest.native_app_private_asset_inventory.v1'
        provider_id = [string]$Manifest.provider_id
        assets = $inventoryAssets
    }
    return [pscustomobject][ordered]@{
        schema = 'rusty.quest.native_app_private_asset_provider_request.v1'
        provider_id = [string]$Manifest.provider_id
        provider_manifest_sha256 = Get-NativeAppPrivateAssetFileSha256 -Path $ManifestPath
        inventory_sha256 = Get-NativeAppPrivateAssetTextSha256 -Text ($projection | ConvertTo-Json -Depth 12 -Compress)
        asset_count = $inventoryAssets.Count
    }
}

function Assert-Rejected {
    param([Parameter(Mandatory = $true)][scriptblock]$Action, [Parameter(Mandatory = $true)][string]$Label)
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (-not $rejected) { throw "Damaged private asset provider case was accepted: $Label" }
}

function New-TestDirectoryLink {
    param(
        [Parameter(Mandatory = $true)][string]$LinkPath,
        [Parameter(Mandatory = $true)][string]$TargetPath
    )
    try {
        if ($IsWindows) {
            New-Item -ItemType Junction -Path $LinkPath -Target $TargetPath -ErrorAction Stop | Out-Null
        } else {
            New-Item -ItemType SymbolicLink -Path $LinkPath -Target $TargetPath -ErrorAction Stop | Out-Null
        }
    } catch {
        if ($IsWindows) { throw "Required Windows junction damage fixture could not be created. $($_.Exception.Message)" }
        Write-Host "Private-asset reparse damage fixture skipped because directory links are unavailable: $($_.Exception.Message)"
        return $false
    }
    $item = Get-Item -LiteralPath $LinkPath -Force -ErrorAction Stop
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) {
        throw "Directory-link damage fixture could not confirm reparse-point identity: $LinkPath"
    }
    return $true
}

$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$tempRoot = Join-Path $tempBase ("rusty-quest-private-assets-" + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($tempRoot) | Out-Null
$repoFixtureRoot = Join-Path $RepoRoot ('local-artifacts\native-app-private-asset-provider-' + [guid]::NewGuid().ToString('N'))
$createdDirectoryLinks = New-Object System.Collections.Generic.List[string]
try {
    $providerRoot = Join-Path $tempRoot 'provider'
    $sourceA = Join-Path $providerRoot 'source-a\shared.ogg'
    $sourceB = Join-Path $providerRoot 'source-b\shared.ogg'
    [IO.Directory]::CreateDirectory((Split-Path -Parent $sourceA)) | Out-Null
    [IO.Directory]::CreateDirectory((Split-Path -Parent $sourceB)) | Out-Null
    [IO.File]::WriteAllBytes($sourceA, [byte[]](1, 3, 5, 7, 9))
    [IO.File]::WriteAllBytes($sourceB, [byte[]](2, 4, 6, 8, 10, 12))
    $manifestPath = Join-Path $providerRoot 'provider.json'
    $validManifest = [ordered]@{
        schema = 'rusty.quest.native_app_private_asset_provider.v1'
        provider_id = 'static-test.private-assets'
        assets = @(
            [ordered]@{
                asset_id = 'condition-a'
                source_relative_path = 'source-a/shared.ogg'
                logical_destination = 'session-audio/condition-a.ogg'
                source_sha256 = Get-NativeAppPrivateAssetFileSha256 -Path $sourceA
                source_bytes = (Get-Item -LiteralPath $sourceA).Length
                media_type = 'audio/ogg'
            },
            [ordered]@{
                asset_id = 'condition-b'
                source_relative_path = 'source-b/shared.ogg'
                logical_destination = 'session-audio/condition-b.ogg'
                source_sha256 = Get-NativeAppPrivateAssetFileSha256 -Path $sourceB
                source_bytes = (Get-Item -LiteralPath $sourceB).Length
                media_type = 'audio/ogg'
            }
        )
    }
    Write-JsonNoBom -Value $validManifest -Path $manifestPath
    $request = New-ProviderRequest -Manifest $validManifest -ManifestPath $manifestPath
    $validation = Resolve-NativeAppPrivateAssetProvider -Request $request -ManifestPath $manifestPath -SchemaPath $schemaPath
    if ([string]$validation.closure.mode -cne 'linked-provider' -or [int]$validation.closure.asset_count -ne 2) {
        throw 'Valid private asset provider did not resolve an exact two-asset closure.'
    }
    foreach ($requestDamage in @(
        @{ name = 'request-provider'; field = 'provider_id'; value = 'other.provider' },
        @{ name = 'request-manifest-hash'; field = 'provider_manifest_sha256'; value = ('0' * 64) },
        @{ name = 'request-inventory-hash'; field = 'inventory_sha256'; value = ('0' * 64) },
        @{ name = 'request-count'; field = 'asset_count'; value = 1 },
        @{ name = 'request-count-fractional'; field = 'asset_count'; value = 2.1 },
        @{ name = 'request-count-string'; field = 'asset_count'; value = '2' },
        @{ name = 'request-count-boolean'; field = 'asset_count'; value = $true }
    )) {
        $damagedRequest = Copy-JsonObject $request
        $damagedRequest.($requestDamage.field) = $requestDamage.value
        Assert-Rejected -Label ([string]$requestDamage.name) -Action {
            Resolve-NativeAppPrivateAssetProvider -Request $damagedRequest -ManifestPath $manifestPath -SchemaPath $schemaPath | Out-Null
        }
    }

    $bomManifestPath = Join-Path $providerRoot 'damaged-bom.json'
    $manifestUtf8 = [Text.UTF8Encoding]::new($false).GetBytes(($validManifest | ConvertTo-Json -Depth 30))
    $bomBytes = [byte[]]::new($manifestUtf8.Length + 3)
    $bomBytes[0] = 0xEF; $bomBytes[1] = 0xBB; $bomBytes[2] = 0xBF
    [Array]::Copy($manifestUtf8, 0, $bomBytes, 3, $manifestUtf8.Length)
    [IO.File]::WriteAllBytes($bomManifestPath, $bomBytes)
    $bomRequest = New-ProviderRequest -Manifest $validManifest -ManifestPath $bomManifestPath
    Assert-Rejected -Label 'manifest-utf8-bom' -Action {
        Resolve-NativeAppPrivateAssetProvider -Request $bomRequest -ManifestPath $bomManifestPath -SchemaPath $schemaPath | Out-Null
    }
    $invalidUtf8ManifestPath = Join-Path $providerRoot 'damaged-invalid-utf8.json'
    [IO.File]::WriteAllBytes($invalidUtf8ManifestPath, [byte[]](0x7B, 0xFF, 0x7D))
    $invalidUtf8Request = Copy-JsonObject $request
    $invalidUtf8Request.provider_manifest_sha256 = Get-NativeAppPrivateAssetFileSha256 -Path $invalidUtf8ManifestPath
    Assert-Rejected -Label 'manifest-invalid-utf8' -Action {
        Resolve-NativeAppPrivateAssetProvider -Request $invalidUtf8Request -ManifestPath $invalidUtf8ManifestPath -SchemaPath $schemaPath | Out-Null
    }

    $sourceAOriginal = [IO.File]::ReadAllBytes($sourceA)
    $validationMutationRoot = Join-Path $tempRoot 'validation-mutation-staging'
    [IO.File]::WriteAllBytes($sourceA, [byte[]](91, 92, 93, 94))
    try {
        Assert-Rejected -Label 'source-mutation-validation-to-staging' -Action {
            Publish-NativeAppPrivateAssetStaging -Validation $validation -OutputRoot $validationMutationRoot | Out-Null
        }
        $poisonedObject = Join-Path $validationMutationRoot ([string]$validation.closure.assets[0].staged_object)
        if (Test-Path -LiteralPath $poisonedObject) {
            throw 'Rejected validation-to-staging mutation left a poisoned content-addressed object.'
        }
        if ((Test-Path -LiteralPath $validationMutationRoot) -and
            @(Get-ChildItem -LiteralPath $validationMutationRoot -File -Recurse -Force -ErrorAction Stop).Count -ne 0) {
            throw 'Rejected validation-to-staging mutation left a temporary object.'
        }
    } finally {
        [IO.File]::WriteAllBytes($sourceA, $sourceAOriginal)
    }

    $resolvedRoot = Join-Path $tempRoot 'resolved'
    [IO.Directory]::CreateDirectory($resolvedRoot) | Out-Null
    $closure = Publish-NativeAppPrivateAssetStaging -Validation $validation -OutputRoot $resolvedRoot
    $lockPath = Join-Path $resolvedRoot 'feature-lock.json'
    [IO.File]::WriteAllText($lockPath, '{}', [Text.UTF8Encoding]::new($false))
    $packageAssets = Join-Path $tempRoot 'package\assets'
    [IO.Directory]::CreateDirectory($packageAssets) | Out-Null
    $packaged = @(Copy-NativeAppPrivateAssetsFromClosure -Closure $closure -FeatureLockPath $lockPath -DestinationRoot $packageAssets)
    if ($packaged.Count -ne 2 -or
        -not (Test-Path -LiteralPath (Join-Path $packageAssets 'session-audio\condition-a.ogg')) -or
        -not (Test-Path -LiteralPath (Join-Path $packageAssets 'session-audio\condition-b.ogg'))) {
        throw 'Valid private assets with identical source basenames were not packaged to distinct logical destinations.'
    }
    Assert-Rejected -Label 'package-create-new-collision' -Action {
        Copy-NativeAppPrivateAssetsFromClosure -Closure $closure -FeatureLockPath $lockPath -DestinationRoot $packageAssets | Out-Null
    }

    foreach ($damage in @(
        @{ name = 'unsorted'; mutate = { param($m) $m.assets = @($m.assets[1], $m.assets[0]) } },
        @{ name = 'duplicate-destination'; mutate = { param($m) $m.assets[1].logical_destination = $m.assets[0].logical_destination } },
        @{ name = 'prefix-destination'; mutate = { param($m) $m.assets[0].logical_destination = 'session-audio/item'; $m.assets[1].logical_destination = 'session-audio/item/nested' } },
        @{ name = 'reserved-destination'; mutate = { param($m) $m.assets[0].logical_destination = 'feature-lock.json' } },
        @{ name = 'reserved-dot-alias-destination'; mutate = { param($m) $m.assets[0].logical_destination = 'maia_spatial_questionnaire./bypass.ogg' } },
        @{ name = 'trailing-dot-destination'; mutate = { param($m) $m.assets[0].logical_destination = 'session-audio/condition-a.ogg.' } },
        @{ name = 'device-name-destination'; mutate = { param($m) $m.assets[0].logical_destination = 'session-audio/con.ogg' } },
        @{ name = 'device-name-extension-source'; mutate = { param($m) $m.assets[0].source_relative_path = 'source-a/COM1.audio' } },
        @{ name = 'trailing-space-source'; mutate = { param($m) $m.assets[0].source_relative_path = 'source-a/shared.ogg ' } },
        @{ name = 'source-traversal'; mutate = { param($m) $m.assets[0].source_relative_path = '../shared.ogg' } },
        @{ name = 'source-absolute'; mutate = { param($m) $m.assets[0].source_relative_path = $sourceA } },
        @{ name = 'wrong-hash'; mutate = { param($m) $m.assets[0].source_sha256 = '0' * 64 } },
        @{ name = 'malformed-hash'; mutate = { param($m) $m.assets[0].source_sha256 = ('a' * 64) + "`n" } },
        @{ name = 'uppercase-hash'; mutate = { param($m) $m.assets[0].source_sha256 = 'A' * 64 } },
        @{ name = 'wrong-bytes'; mutate = { param($m) $m.assets[0].source_bytes = 999 } },
        @{ name = 'malformed-media'; mutate = { param($m) $m.assets[0].media_type = 'Audio Ogg' } }
    )) {
        $damaged = Copy-JsonObject $validManifest
        & $damage.mutate $damaged
        $damagedPath = Join-Path $providerRoot ("damaged-$($damage.name).json")
        Write-JsonNoBom -Value $damaged -Path $damagedPath
        $damagedRequest = New-ProviderRequest -Manifest $damaged -ManifestPath $damagedPath
        Assert-Rejected -Label ([string]$damage.name) -Action {
            Resolve-NativeAppPrivateAssetProvider -Request $damagedRequest -ManifestPath $damagedPath -SchemaPath $schemaPath | Out-Null
        }
    }

    Assert-Rejected -Label 'public-asset-root-collision' -Action {
        Resolve-NativeAppPrivateAssetProvider -Request $request -ManifestPath $manifestPath -SchemaPath $schemaPath `
            -PublicAssetDestinationRecords @([pscustomobject]@{ path = 'session-audio'; kind = 'directory' }) | Out-Null
    }
    $publicFixture = Resolve-NativeAppPublicAssetInput `
        -AssetInput 'fixtures/native-renderer/native-hwb-blur-sdf-public.plan.json' `
        -RepoRoot $RepoRoot
    if (-not (Test-Path -LiteralPath $publicFixture -PathType Leaf)) { throw 'In-repo public asset was not accepted.' }
    Assert-Rejected -Label 'public-asset-absolute' -Action {
        Resolve-NativeAppPublicAssetInput -AssetInput $publicFixture -RepoRoot $RepoRoot | Out-Null
    }
    Assert-Rejected -Label 'public-asset-traversal' -Action {
        Resolve-NativeAppPublicAssetInput -AssetInput '../outside.bin' -RepoRoot $RepoRoot | Out-Null
    }

    $publicTree = Join-Path $repoFixtureRoot 'public-tree'
    $publicLinkTarget = Join-Path $tempRoot 'public-link-target'
    [IO.Directory]::CreateDirectory($publicTree) | Out-Null
    [IO.Directory]::CreateDirectory($publicLinkTarget) | Out-Null
    [IO.File]::WriteAllBytes((Join-Path $publicLinkTarget 'escaped.bin'), [byte[]](10, 20, 30))
    $publicLink = Join-Path $publicTree 'linked-descendant'
    if (New-TestDirectoryLink -LinkPath $publicLink -TargetPath $publicLinkTarget) {
        $createdDirectoryLinks.Add($publicLink)
        $publicTreeRelative = [IO.Path]::GetRelativePath($RepoRoot, $publicTree).Replace('\', '/')
        Assert-Rejected -Label 'public-recursive-descendant-reparse' -Action {
            Resolve-NativeAppPublicAssetInput -AssetInput $publicTreeRelative -RepoRoot $RepoRoot | Out-Null
        }
    }

    $stagingLinkRoot = Join-Path $tempRoot 'staging-link-root'
    $stagingLinkTarget = Join-Path $tempRoot 'staging-link-target'
    [IO.Directory]::CreateDirectory($stagingLinkRoot) | Out-Null
    [IO.Directory]::CreateDirectory($stagingLinkTarget) | Out-Null
    $stagingPrivateAssetsLink = Join-Path $stagingLinkRoot 'private-assets'
    if (New-TestDirectoryLink -LinkPath $stagingPrivateAssetsLink -TargetPath $stagingLinkTarget) {
        $createdDirectoryLinks.Add($stagingPrivateAssetsLink)
        Assert-Rejected -Label 'staging-output-ancestor-reparse' -Action {
            Publish-NativeAppPrivateAssetStaging -Validation $validation -OutputRoot $stagingLinkRoot | Out-Null
        }
        if (@(Get-ChildItem -LiteralPath $stagingLinkTarget -File -Recurse -Force).Count -ne 0) {
            throw 'Rejected staging reparse fixture wrote outside the output root.'
        }
    }

    $linkedStagedRoot = Join-Path $tempRoot 'linked-staged-root'
    $linkedStagedTarget = Join-Path $tempRoot 'linked-staged-target'
    $linkedStagedObjects = Join-Path $linkedStagedTarget 'objects'
    [IO.Directory]::CreateDirectory($linkedStagedRoot) | Out-Null
    [IO.Directory]::CreateDirectory($linkedStagedObjects) | Out-Null
    foreach ($asset in @($closure.assets)) {
        $sourceForAsset = if ([string]$asset.asset_id -ceq 'condition-a') { $sourceA } else { $sourceB }
        [IO.File]::Copy($sourceForAsset, (Join-Path $linkedStagedObjects ([string]$asset.source_sha256)), $false)
    }
    $linkedStagedPrivateAssets = Join-Path $linkedStagedRoot 'private-assets'
    if (New-TestDirectoryLink -LinkPath $linkedStagedPrivateAssets -TargetPath $linkedStagedTarget) {
        $createdDirectoryLinks.Add($linkedStagedPrivateAssets)
        $linkedStagedLock = Join-Path $linkedStagedRoot 'feature-lock.json'
        [IO.File]::WriteAllText($linkedStagedLock, '{}', [Text.UTF8Encoding]::new($false))
        $linkedStagedPackage = Join-Path $tempRoot 'linked-staged-package'
        [IO.Directory]::CreateDirectory($linkedStagedPackage) | Out-Null
        Assert-Rejected -Label 'staged-source-chain-reparse' -Action {
            Copy-NativeAppPrivateAssetsFromClosure -Closure $closure -FeatureLockPath $linkedStagedLock -DestinationRoot $linkedStagedPackage | Out-Null
        }
        if (@(Get-ChildItem -LiteralPath $linkedStagedPackage -File -Recurse -Force).Count -ne 0) {
            throw 'Rejected staged-source reparse fixture wrote package bytes.'
        }
    }

    $packageRootLinkTarget = Join-Path $tempRoot 'package-root-link-target'
    [IO.Directory]::CreateDirectory($packageRootLinkTarget) | Out-Null
    $packageRootLink = Join-Path $tempRoot 'package-root-link'
    if (New-TestDirectoryLink -LinkPath $packageRootLink -TargetPath $packageRootLinkTarget) {
        $createdDirectoryLinks.Add($packageRootLink)
        Assert-Rejected -Label 'package-root-reparse' -Action {
            Copy-NativeAppPrivateAssetsFromClosure -Closure $closure -FeatureLockPath $lockPath -DestinationRoot $packageRootLink | Out-Null
        }
        if (@(Get-ChildItem -LiteralPath $packageRootLinkTarget -File -Recurse -Force).Count -ne 0) {
            throw 'Rejected package-root reparse fixture wrote outside the destination root.'
        }
    }

    $packageAncestorRoot = Join-Path $tempRoot 'package-ancestor-root'
    $packageAncestorTarget = Join-Path $tempRoot 'package-ancestor-target'
    [IO.Directory]::CreateDirectory($packageAncestorRoot) | Out-Null
    [IO.Directory]::CreateDirectory($packageAncestorTarget) | Out-Null
    $packageAncestorLink = Join-Path $packageAncestorRoot 'session-audio'
    if (New-TestDirectoryLink -LinkPath $packageAncestorLink -TargetPath $packageAncestorTarget) {
        $createdDirectoryLinks.Add($packageAncestorLink)
        Assert-Rejected -Label 'package-destination-ancestor-reparse' -Action {
            Copy-NativeAppPrivateAssetsFromClosure -Closure $closure -FeatureLockPath $lockPath -DestinationRoot $packageAncestorRoot | Out-Null
        }
        if (@(Get-ChildItem -LiteralPath $packageAncestorTarget -File -Recurse -Force).Count -ne 0) {
            throw 'Rejected destination-ancestor reparse fixture wrote outside the destination root.'
        }
    }

    $integrationRoot = Join-Path $tempRoot 'integration'
    [IO.Directory]::CreateDirectory($integrationRoot) | Out-Null
    $baseSpecPath = Join-Path $RepoRoot 'fixtures\native-app-builds\native-openxr-hand-lab.app.json'
    $linkedSpec = Get-Content -Raw -LiteralPath $baseSpecPath | ConvertFrom-Json
    $linkedSpec.app_id = 'native_private_asset_provider_static_test'
    $linkedSpec | Add-Member -NotePropertyName private_asset_provider_request -NotePropertyValue $request
    $linkedSpecPath = Join-Path $integrationRoot 'linked.app.json'
    Write-JsonNoBom -Value $linkedSpec -Path $linkedSpecPath
    $linkedSpecRaw = Get-Content -Raw -LiteralPath $linkedSpecPath
    if (-not (Test-Json -Json $linkedSpecRaw -SchemaFile (Join-Path $RepoRoot 'schemas\rusty.quest.native_app_build.v1.schema.json'))) {
        throw 'Linked private asset app spec failed the production app-build schema.'
    }
    $linkedOutput = Join-Path $integrationRoot 'linked-output'
    $linkedResult = Join-Path $linkedOutput 'result.json'
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $resolverPath `
        -AppSpec $linkedSpecPath `
        -OutputRoot $linkedOutput `
        -ResultJsonPath $linkedResult `
        -PrivateAssetProviderManifest $manifestPath `
        -DryRun | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Linked private asset resolver integration failed.' }
    $result = Get-Content -Raw -LiteralPath $linkedResult | ConvertFrom-Json
    $lock = Get-Content -Raw -LiteralPath ([string]$result.feature_lock_path) | ConvertFrom-Json
    if ([string]$lock.build_inputs.private_asset_closure.mode -cne 'linked-provider' -or
        [int]$lock.build_inputs.private_asset_closure.asset_count -ne 2 -or
        (@($lock.build_inputs.private_asset_closure.assets | Where-Object { $_.PSObject.Properties.Name -contains 'source_relative_path' }).Count -ne 0)) {
        throw 'Resolved feature lock leaked source paths or omitted the linked private asset closure.'
    }
    Assert-Rejected -Label 'request-without-manifest' -Action {
        & pwsh -NoProfile -ExecutionPolicy Bypass -File $resolverPath -AppSpec $linkedSpecPath -OutputRoot (Join-Path $integrationRoot 'missing-manifest') -DryRun 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'resolver-rejected-request-without-manifest' }
    }
    $inertOutput = Join-Path $integrationRoot 'inert-output'
    $inertResult = Join-Path $inertOutput 'result.json'
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $resolverPath `
        -AppSpec $baseSpecPath -OutputRoot $inertOutput -ResultJsonPath $inertResult -DryRun | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Inert private asset resolver integration failed.' }
    $inert = Get-Content -Raw -LiteralPath $inertResult | ConvertFrom-Json
    $inertLock = Get-Content -Raw -LiteralPath ([string]$inert.feature_lock_path) | ConvertFrom-Json
    if ([string]$inertLock.build_inputs.private_asset_closure.mode -cne 'inactive' -or
        [int]$inertLock.build_inputs.private_asset_closure.asset_count -ne 0) {
        throw 'Unselected private asset provider did not remain inert.'
    }
    Assert-Rejected -Label 'manifest-without-request' -Action {
        & pwsh -NoProfile -ExecutionPolicy Bypass -File $resolverPath -AppSpec $baseSpecPath `
            -OutputRoot (Join-Path $integrationRoot 'unexpected-manifest') `
            -PrivateAssetProviderManifest $manifestPath -DryRun 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'resolver-rejected-manifest-without-request' }
    }

    $mutatedObject = Join-Path $resolvedRoot ([string]$closure.assets[0].staged_object)
    [IO.File]::WriteAllBytes($mutatedObject, [byte[]](99, 98, 97))
    $mutationPackage = Join-Path $tempRoot 'mutation-package\assets'
    [IO.Directory]::CreateDirectory($mutationPackage) | Out-Null
    Assert-Rejected -Label 'staged-object-mutation' -Action {
        Copy-NativeAppPrivateAssetsFromClosure -Closure $closure -FeatureLockPath $lockPath -DestinationRoot $mutationPackage | Out-Null
    }

    Write-Host 'Rusty Quest native app private-asset provider PASS (linked/inert resolver, package, and damage matrix).'
} finally {
    foreach ($directoryLink in @($createdDirectoryLinks)) {
        try {
            if (Test-Path -LiteralPath $directoryLink) { [IO.Directory]::Delete($directoryLink) }
        } catch {
            Write-Warning "Could not remove private-asset damage-fixture directory link: $directoryLink"
        }
    }
    $resolvedRepoFixture = [IO.Path]::GetFullPath($repoFixtureRoot)
    $resolvedLocalArtifacts = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'local-artifacts'))
    if ($resolvedRepoFixture.StartsWith($resolvedLocalArtifacts + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedRepoFixture).StartsWith('native-app-private-asset-provider-', [StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $resolvedRepoFixture -Recurse -Force -ErrorAction SilentlyContinue
    }
    $resolvedTemp = [IO.Path]::GetFullPath($tempRoot)
    if ($resolvedTemp.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedTemp).StartsWith('rusty-quest-private-assets-', [StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# Damage probes intentionally execute child processes that fail. A successful
# matrix must not leak the final expected child failure to callers that use
# LASTEXITCODE as the gate result.
$global:LASTEXITCODE = 0
