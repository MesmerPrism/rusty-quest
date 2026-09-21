Set-StrictMode -Version Latest

$script:ProviderSchema = 'rusty.quest.native_app_private_asset_provider.v1'
$script:RequestSchema = 'rusty.quest.native_app_private_asset_provider_request.v1'
$script:ClosureSchema = 'rusty.quest.native_app_private_asset_closure.v1'
$script:ReservedAssetRecords = @(
    [pscustomobject]@{ path = 'feature-lock.json'; kind = 'file' },
    [pscustomobject]@{ path = 'native-app-settings.json'; kind = 'file' },
    [pscustomobject]@{ path = 'panel-source-closure.json'; kind = 'file' },
    [pscustomobject]@{ path = 'native-hwb-blur-sdf-public.plan.json'; kind = 'file' },
    [pscustomobject]@{ path = 'recorded-hand-replay-public-shape.json'; kind = 'file' },
    [pscustomobject]@{ path = 'manifold'; kind = 'directory' },
    [pscustomobject]@{ path = 'maia_spatial_questionnaire'; kind = 'directory' }
)

function Get-NativeAppPrivateAssetFileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $stream = [System.IO.File]::OpenRead($Path)
        try {
            return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
        } finally {
            $stream.Dispose()
        }
    } finally {
        $sha.Dispose()
    }
}

function Get-NativeAppPrivateAssetTextSha256 {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-NativeAppPrivateAssetBytesSha256 {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Assert-NativeAppPrivateAssetSha256 {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if ($Value.Length -ne 64 -or $Value -cnotmatch '\A[0-9a-f]{64}\z') {
        throw "$Label must be exactly 64 lowercase hexadecimal characters."
    }
}

function Test-NativeAppPathInsideRoot {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root
    )
    $full = [System.IO.Path]::GetFullPath($Path)
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $relative = [System.IO.Path]::GetRelativePath($rootFull, $full)
    return -not (
        [System.IO.Path]::IsPathRooted($relative) -or
        $relative -eq '..' -or
        $relative.StartsWith('..\', [StringComparison]::Ordinal) -or
        $relative.StartsWith('../', [StringComparison]::Ordinal)
    )
}

function Assert-NativeAppPortableRelativePath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label,
        [switch]$LogicalDestination
    )
    if ([string]::IsNullOrWhiteSpace($Path) -or
        [System.IO.Path]::IsPathRooted($Path) -or
        $Path.Contains('\') -or
        $Path.StartsWith('/', [StringComparison]::Ordinal) -or
        $Path.EndsWith('/', [StringComparison]::Ordinal) -or
        $Path.Contains('//') -or
        $Path -match '[\x00-\x1f\x7f:*?"<>|]') {
        throw "$Label must be a non-empty portable relative path: $Path"
    }
    $segments = @($Path.Split('/'))
    if ($segments.Count -eq 0 -or @($segments | Where-Object { $_ -in @('', '.', '..') }).Count -gt 0) {
        throw "$Label contains an empty, dot, or traversal segment: $Path"
    }
    foreach ($segment in $segments) {
        if ($segment.EndsWith('.', [StringComparison]::Ordinal) -or
            $segment.EndsWith(' ', [StringComparison]::Ordinal) -or
            $segment -cne $segment.Normalize([Text.NormalizationForm]::FormC)) {
            throw "$Label contains a Windows-canonicalization alias segment: $Path"
        }
        $deviceStem = $segment.Split('.')[0]
        if ($deviceStem -imatch '\A(?:con|prn|aux|nul|clock\$|conin\$|conout\$|com[1-9]|lpt[1-9])\z') {
            throw "$Label contains a reserved Windows device-name segment: $Path"
        }
    }
    if ($LogicalDestination -and $Path -cnotmatch '\A[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)*\z') {
        throw "$Label must be a canonical lowercase APK asset path: $Path"
    }
}

function Get-NativeAppInspectedItem {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )
    try {
        $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    } catch {
        throw "$Label could not be inspected: $Path. $($_.Exception.Message)"
    }
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Label is or traverses a reparse point: $Path"
    }
    return $item
}

function Assert-NativeAppNoReparsePath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $rootFull = [System.IO.Path]::GetFullPath($Root)
    $pathFull = [System.IO.Path]::GetFullPath($Path)
    if (-not (Test-NativeAppPathInsideRoot -Path $pathFull -Root $rootFull)) {
        throw "$Label escapes its provider root."
    }
    $current = $rootFull
    Get-NativeAppInspectedItem -Path $current -Label "$Label root" | Out-Null
    $relative = [IO.Path]::GetRelativePath($rootFull, $pathFull)
    foreach ($segment in $relative.Split([IO.Path]::DirectorySeparatorChar, [StringSplitOptions]::RemoveEmptyEntries)) {
        $current = Join-Path $current $segment
        Get-NativeAppInspectedItem -Path $current -Label $Label | Out-Null
    }
}

function Assert-NativeAppNoReparseDescendants {
    param(
        [Parameter(Mandatory = $true)][string]$DirectoryPath,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $rootItem = Get-NativeAppInspectedItem -Path $DirectoryPath -Label $Label
    if (-not $rootItem.PSIsContainer) { return }
    $pending = [Collections.Generic.Queue[string]]::new()
    $pending.Enqueue($rootItem.FullName)
    while ($pending.Count -gt 0) {
        $current = $pending.Dequeue()
        try {
            $children = @([IO.Directory]::EnumerateFileSystemEntries($current))
        } catch {
            throw "$Label descendant inspection failed closed at $current. $($_.Exception.Message)"
        }
        foreach ($child in $children) {
            $item = Get-NativeAppInspectedItem -Path $child -Label "$Label descendant"
            if ($item.PSIsContainer) { $pending.Enqueue($item.FullName) }
        }
    }
}

function Ensure-NativeAppSafeDirectoryChain {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$DirectoryPath,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $rootFull = [IO.Path]::GetFullPath($Root)
    $directoryFull = [IO.Path]::GetFullPath($DirectoryPath)
    if (-not (Test-NativeAppPathInsideRoot -Path $directoryFull -Root $rootFull)) {
        throw "$Label directory escapes its root."
    }
    $rootItem = Get-NativeAppInspectedItem -Path $rootFull -Label "$Label root"
    if (-not $rootItem.PSIsContainer) { throw "$Label root is not a directory: $rootFull" }
    $current = $rootFull
    $relative = [IO.Path]::GetRelativePath($rootFull, $directoryFull)
    foreach ($segment in $relative.Split([IO.Path]::DirectorySeparatorChar, [StringSplitOptions]::RemoveEmptyEntries)) {
        $current = Join-Path $current $segment
        if (-not [IO.Directory]::Exists($current) -and -not [IO.File]::Exists($current)) {
            try {
                [IO.Directory]::CreateDirectory($current) | Out-Null
            } catch {
                throw "$Label could not create a contained directory: $current. $($_.Exception.Message)"
            }
        }
        $item = Get-NativeAppInspectedItem -Path $current -Label $Label
        if (-not $item.PSIsContainer) { throw "$Label ancestor is not a directory: $current" }
    }
}

function Assert-NativeAppPrivateAssetDestinationAvailable {
    param(
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)]$BlockedRecords,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$AcceptedDestinations
    )
    $candidateFolded = $Destination.ToLowerInvariant()
    foreach ($existing in $AcceptedDestinations) {
        $existingFolded = ([string]$existing).ToLowerInvariant()
        if ($candidateFolded -eq $existingFolded -or
            $candidateFolded.StartsWith($existingFolded + '/', [StringComparison]::Ordinal) -or
            $existingFolded.StartsWith($candidateFolded + '/', [StringComparison]::Ordinal)) {
            throw "Private asset logical destinations collide: $Destination and $existing"
        }
    }
    foreach ($record in $BlockedRecords) {
        $blocked = ([string]$record.path).ToLowerInvariant().TrimEnd('/')
        $kind = [string]$record.kind
        if ($candidateFolded -eq $blocked -or
            $blocked.StartsWith($candidateFolded + '/', [StringComparison]::Ordinal) -or
            ($kind -eq 'directory' -and $candidateFolded.StartsWith($blocked + '/', [StringComparison]::Ordinal)) -or
            ($kind -eq 'file' -and $candidateFolded.StartsWith($blocked + '/', [StringComparison]::Ordinal))) {
            throw "Private asset destination collides with reserved or public APK asset path: $Destination versus $($record.path)"
        }
    }
}

function Resolve-NativeAppPublicAssetInput {
    param(
        [Parameter(Mandatory = $true)][string]$AssetInput,
        [Parameter(Mandatory = $true)][string]$RepoRoot
    )
    Assert-NativeAppPortableRelativePath -Path $AssetInput -Label 'Public build_inputs.assets entry'
    $root = [IO.Path]::GetFullPath($RepoRoot)
    $resolved = [IO.Path]::GetFullPath((Join-Path $root $AssetInput))
    if (-not (Test-NativeAppPathInsideRoot -Path $resolved -Root $root)) {
        throw "Public build_inputs.assets entry must resolve inside the public repository: $AssetInput"
    }
    if (-not (Test-Path -LiteralPath $resolved)) {
        throw "Public build_inputs.assets entry is missing: $AssetInput"
    }
    Assert-NativeAppNoReparsePath -Path $resolved -Root $root -Label 'Public build_inputs.assets entry'
    if ((Get-Item -LiteralPath $resolved -Force).PSIsContainer) {
        Assert-NativeAppNoReparseDescendants -DirectoryPath $resolved -Label 'Public build_inputs.assets entry'
    }
    return $resolved
}

function Get-NativeAppPublicAssetDestinationRecords {
    param(
        [string[]]$AssetInputs,
        [Parameter(Mandatory = $true)][string]$RepoRoot
    )
    $records = New-Object System.Collections.Generic.List[object]
    foreach ($assetInput in @($AssetInputs)) {
        if ([string]::IsNullOrWhiteSpace([string]$assetInput)) { continue }
        $source = Resolve-NativeAppPublicAssetInput -AssetInput ([string]$assetInput) -RepoRoot $RepoRoot
        $item = Get-Item -LiteralPath $source -Force
        $leaf = $item.Name.Replace('\', '/')
        if ($item.PSIsContainer) {
            $records.Add([pscustomobject]@{ path = $leaf; kind = 'directory' })
        } else {
            $records.Add([pscustomobject]@{ path = $leaf; kind = 'file' })
        }
    }
    return $records.ToArray()
}

function New-NativeAppInactivePrivateAssetClosure {
    return [ordered]@{
        schema = $script:ClosureSchema
        mode = 'inactive'
        provider_id = ''
        provider_manifest_sha256 = ''
        inventory_sha256 = ''
        asset_count = 0
        assets = @()
    }
}

function Assert-NativeAppPrivateAssetRequest {
    param([Parameter(Mandatory = $true)]$Request)
    $required = @('schema', 'provider_id', 'provider_manifest_sha256', 'inventory_sha256', 'asset_count')
    $names = @($Request.PSObject.Properties.Name)
    foreach ($field in $required) {
        if ($names -cnotcontains $field) { throw "Private asset provider request is missing $field." }
    }
    foreach ($name in $names) {
        if ($required -cnotcontains [string]$name) { throw "Private asset provider request contains unsupported field: $name" }
    }
    if ([string]$Request.schema -cne $script:RequestSchema) { throw "Unsupported private asset request schema: $($Request.schema)" }
    if ([string]$Request.provider_id -cnotmatch '\A[a-z0-9]+(?:[._-][a-z0-9]+)*\z') { throw 'Private asset request provider_id is not canonical.' }
    Assert-NativeAppPrivateAssetSha256 -Value ([string]$Request.provider_manifest_sha256) -Label 'provider_manifest_sha256'
    Assert-NativeAppPrivateAssetSha256 -Value ([string]$Request.inventory_sha256) -Label 'inventory_sha256'
    $integralTypes = @([byte], [sbyte], [int16], [uint16], [int32], [uint32], [int64], [uint64])
    if ($null -eq $Request.asset_count -or @($integralTypes | Where-Object { $Request.asset_count -is $_ }).Count -ne 1) {
        throw 'Private asset request asset_count must be an actual integral JSON number.'
    }
    if ([int64]$Request.asset_count -lt 1 -or [int64]$Request.asset_count -gt 32) { throw 'Private asset request asset_count must be 1..32.' }
}

function Resolve-NativeAppPrivateAssetProvider {
    param(
        [Parameter(Mandatory = $true)]$Request,
        [Parameter(Mandatory = $true)][string]$ManifestPath,
        [Parameter(Mandatory = $true)][string]$SchemaPath,
        [object[]]$PublicAssetDestinationRecords = @()
    )
    Assert-NativeAppPrivateAssetRequest -Request $Request
    if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) { throw "Private asset provider manifest is missing: $ManifestPath" }
    $manifestFull = (Resolve-Path -LiteralPath $ManifestPath).Path
    $providerRoot = Split-Path -Parent $manifestFull
    Assert-NativeAppNoReparsePath -Path $manifestFull -Root $providerRoot -Label 'Private asset provider manifest'
    $manifestBytes = [IO.File]::ReadAllBytes($manifestFull)
    if ($manifestBytes.Length -ge 3 -and $manifestBytes[0] -eq 0xEF -and $manifestBytes[1] -eq 0xBB -and $manifestBytes[2] -eq 0xBF) {
        throw 'Private asset provider manifest must be strict UTF-8 without a BOM.'
    }
    try {
        $raw = [Text.UTF8Encoding]::new($false, $true).GetString($manifestBytes)
    } catch {
        throw "Private asset provider manifest is not strict UTF-8. $($_.Exception.Message)"
    }
    if (-not (Test-Json -Json $raw -SchemaFile $SchemaPath -ErrorAction SilentlyContinue)) { throw 'Private asset provider manifest failed schema validation.' }
    $manifestSha = Get-NativeAppPrivateAssetBytesSha256 -Bytes $manifestBytes
    if ($manifestSha -cne [string]$Request.provider_manifest_sha256) { throw 'Private asset provider manifest SHA-256 does not match the app request.' }
    $manifest = $raw | ConvertFrom-Json
    if ([string]$manifest.schema -cne $script:ProviderSchema -or [string]$manifest.provider_id -cne [string]$Request.provider_id) {
        throw 'Private asset provider manifest identity does not match the app request.'
    }
    $blocked = @($script:ReservedAssetRecords) + @($PublicAssetDestinationRecords)
    $acceptedDestinations = New-Object System.Collections.Generic.List[string]
    $assetIds = @{}
    $inventoryAssets = @()
    $sources = @()
    $previousId = $null
    foreach ($asset in @($manifest.assets)) {
        $assetId = [string]$asset.asset_id
        if ($assetId -cnotmatch '\A[a-z0-9]+(?:[._-][a-z0-9]+)*\z') {
            throw "Private asset id is not canonical: $assetId"
        }
        if ($null -ne $previousId -and [string]::CompareOrdinal($previousId, $assetId) -ge 0) {
            throw 'Private asset provider assets must be unique and ordinal-sorted by asset_id.'
        }
        $previousId = $assetId
        if ($assetIds.ContainsKey($assetId.ToLowerInvariant())) { throw "Duplicate private asset id: $assetId" }
        $assetIds[$assetId.ToLowerInvariant()] = $true
        $sourceRelative = [string]$asset.source_relative_path
        Assert-NativeAppPortableRelativePath -Path $sourceRelative -Label "$assetId source_relative_path"
        $sourcePath = [IO.Path]::GetFullPath((Join-Path $providerRoot ($sourceRelative.Replace('/', [IO.Path]::DirectorySeparatorChar))))
        if (-not (Test-NativeAppPathInsideRoot -Path $sourcePath -Root $providerRoot) -or -not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "Private asset source is missing or outside provider root: $sourceRelative"
        }
        Assert-NativeAppNoReparsePath -Path $sourcePath -Root $providerRoot -Label "$assetId source"
        $destination = [string]$asset.logical_destination
        Assert-NativeAppPortableRelativePath -Path $destination -Label "$assetId logical_destination" -LogicalDestination
        Assert-NativeAppPrivateAssetDestinationAvailable -Destination $destination -BlockedRecords $blocked -AcceptedDestinations @($acceptedDestinations)
        Assert-NativeAppPrivateAssetSha256 -Value ([string]$asset.source_sha256) -Label "$assetId source_sha256"
        $actualBytes = (Get-Item -LiteralPath $sourcePath).Length
        if ($actualBytes -ne [int64]$asset.source_bytes) { throw "$assetId source byte count mismatch." }
        $actualSha = Get-NativeAppPrivateAssetFileSha256 -Path $sourcePath
        if ($actualSha -cne [string]$asset.source_sha256) { throw "$assetId source SHA-256 mismatch." }
        $mediaType = [string]$asset.media_type
        if ($mediaType -cnotmatch '\A[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*\z') { throw "$assetId media_type is malformed." }
        $stagedObject = "private-assets/objects/$actualSha"
        $inventoryAssets += [ordered]@{
            asset_id = $assetId
            logical_destination = $destination
            staged_object = $stagedObject
            source_sha256 = $actualSha
            source_bytes = $actualBytes
            media_type = $mediaType
        }
        $sources += [pscustomobject]@{ asset_id = $assetId; source_path = $sourcePath; staged_object = $stagedObject }
        $acceptedDestinations.Add($destination)
    }
    if ($inventoryAssets.Count -ne [int]$Request.asset_count) { throw 'Private asset provider asset count does not match the app request.' }
    $inventoryProjection = [ordered]@{
        schema = 'rusty.quest.native_app_private_asset_inventory.v1'
        provider_id = [string]$manifest.provider_id
        assets = $inventoryAssets
    }
    $inventorySha = Get-NativeAppPrivateAssetTextSha256 -Text ($inventoryProjection | ConvertTo-Json -Depth 12 -Compress)
    if ($inventorySha -cne [string]$Request.inventory_sha256) { throw 'Private asset inventory SHA-256 does not match the app request.' }
    $closure = [ordered]@{
        schema = $script:ClosureSchema
        mode = 'linked-provider'
        provider_id = [string]$manifest.provider_id
        provider_manifest_sha256 = $manifestSha
        inventory_sha256 = $inventorySha
        asset_count = $inventoryAssets.Count
        assets = $inventoryAssets
    }
    return [pscustomobject]@{ closure = $closure; sources = $sources; manifest_path = $manifestFull; provider_root = $providerRoot }
}

function Publish-NativeAppPrivateAssetStaging {
    param(
        [Parameter(Mandatory = $true)]$Validation,
        [Parameter(Mandatory = $true)][string]$OutputRoot
    )
    $outputRootFull = [IO.Path]::GetFullPath($OutputRoot)
    $volumeRoot = [IO.Path]::GetPathRoot($outputRootFull)
    Ensure-NativeAppSafeDirectoryChain -Root $volumeRoot -DirectoryPath $outputRootFull -Label 'Private asset staging output root'
    $outputRootItem = Get-NativeAppInspectedItem -Path $outputRootFull -Label 'Private asset staging output root'
    if (-not $outputRootItem.PSIsContainer) { throw "Private asset staging output root is not a directory: $outputRootFull" }
    foreach ($source in @($Validation.sources)) {
        $record = @($Validation.closure.assets | Where-Object { [string]$_.asset_id -ceq [string]$source.asset_id })[0]
        Assert-NativeAppNoReparsePath -Path ([string]$source.source_path) -Root ([string]$Validation.provider_root) -Label "$($source.asset_id) staging source"
        $destination = [IO.Path]::GetFullPath((Join-Path $OutputRoot ([string]$source.staged_object)))
        if (-not (Test-NativeAppPathInsideRoot -Path $destination -Root $OutputRoot)) { throw 'Private asset staging path escaped output root.' }
        $objectRoot = Split-Path -Parent $destination
        Ensure-NativeAppSafeDirectoryChain -Root $outputRootFull -DirectoryPath $objectRoot -Label 'Private asset staging'
        $temporary = Join-Path $objectRoot ('.' + [string]$record.source_sha256 + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
        $temporaryPublished = $false
        try {
            $input = [IO.FileStream]::new([string]$source.source_path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
            try {
                $output = [IO.FileStream]::new($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
                try {
                    $input.CopyTo($output)
                    $output.Flush($true)
                } finally { $output.Dispose() }
            } finally { $input.Dispose() }
            Get-NativeAppInspectedItem -Path $temporary -Label 'Private asset staging temporary object' | Out-Null
            if ((Get-Item -LiteralPath $temporary).Length -ne [int64]$record.source_bytes -or
                (Get-NativeAppPrivateAssetFileSha256 -Path $temporary) -cne [string]$record.source_sha256) {
                throw "Private asset source changed between validation and staging: $($source.asset_id)"
            }
            if (Test-Path -LiteralPath $destination) {
                Get-NativeAppInspectedItem -Path $destination -Label 'Private asset staging object' | Out-Null
                if ((Get-Item -LiteralPath $destination).Length -ne [int64]$record.source_bytes -or
                    (Get-NativeAppPrivateAssetFileSha256 -Path $destination) -cne [string]$record.source_sha256) {
                    throw "Existing content-addressed private asset staging object has different bytes: $destination"
                }
                continue
            }
            [IO.File]::Move($temporary, $destination, $false)
            $temporaryPublished = $true
            Get-NativeAppInspectedItem -Path $destination -Label 'Private asset staging object' | Out-Null
            if ((Get-Item -LiteralPath $destination).Length -ne [int64]$record.source_bytes -or
                (Get-NativeAppPrivateAssetFileSha256 -Path $destination) -cne [string]$record.source_sha256) {
                [IO.File]::Delete($destination)
                throw "Published private asset staging object failed verification: $destination"
            }
        } finally {
            if (-not $temporaryPublished -and [IO.File]::Exists($temporary)) {
                [IO.File]::Delete($temporary)
            }
        }
    }
    return $Validation.closure
}

function Copy-NativeAppPrivateAssetsFromClosure {
    param(
        [Parameter(Mandatory = $true)]$Closure,
        [Parameter(Mandatory = $true)][string]$FeatureLockPath,
        [Parameter(Mandatory = $true)][string]$DestinationRoot
    )
    if ([string]$Closure.schema -cne $script:ClosureSchema) { throw "Unsupported private asset closure schema: $($Closure.schema)" }
    if ([string]$Closure.mode -ceq 'inactive') {
        if ([int]$Closure.asset_count -ne 0 -or @($Closure.assets).Count -ne 0) { throw 'Inactive private asset closure must be empty.' }
        return @()
    }
    if ([string]$Closure.mode -cne 'linked-provider' -or [int]$Closure.asset_count -ne @($Closure.assets).Count) {
        throw 'Private asset closure is incomplete or malformed.'
    }
    Assert-NativeAppPrivateAssetSha256 -Value ([string]$Closure.provider_manifest_sha256) -Label 'closure provider_manifest_sha256'
    Assert-NativeAppPrivateAssetSha256 -Value ([string]$Closure.inventory_sha256) -Label 'closure inventory_sha256'
    if ([string]$Closure.provider_id -cnotmatch '\A[a-z0-9]+(?:[._-][a-z0-9]+)*\z') {
        throw 'Private asset closure provider_id is not canonical.'
    }
    $lockRoot = Split-Path -Parent ([IO.Path]::GetFullPath($FeatureLockPath))
    Assert-NativeAppNoReparsePath -Path $lockRoot -Root ([IO.Path]::GetPathRoot($lockRoot)) -Label 'Private asset feature-lock absolute chain'
    $lockRootItem = Get-NativeAppInspectedItem -Path $lockRoot -Label 'Private asset feature-lock root'
    if (-not $lockRootItem.PSIsContainer) { throw "Private asset feature-lock root is not a directory: $lockRoot" }
    $destinationRootFull = [IO.Path]::GetFullPath($DestinationRoot)
    Assert-NativeAppNoReparsePath -Path $destinationRootFull -Root ([IO.Path]::GetPathRoot($destinationRootFull)) -Label 'Private asset package destination absolute chain'
    $destinationRootItem = Get-NativeAppInspectedItem -Path $destinationRootFull -Label 'Private asset package destination root'
    if (-not $destinationRootItem.PSIsContainer) { throw "Private asset package destination root is not a directory: $destinationRootFull" }
    $accepted = New-Object System.Collections.Generic.List[string]
    $prepared = @()
    $inventoryAssets = @()
    $previousId = $null
    foreach ($asset in @($Closure.assets)) {
        $assetId = [string]$asset.asset_id
        if ($assetId -cnotmatch '\A[a-z0-9]+(?:[._-][a-z0-9]+)*\z' -or
            ($null -ne $previousId -and [string]::CompareOrdinal($previousId, $assetId) -ge 0)) {
            throw 'Private asset closure assets must have canonical unique ordinal-sorted asset ids.'
        }
        $previousId = $assetId
        $destination = [string]$asset.logical_destination
        Assert-NativeAppPortableRelativePath -Path $destination -Label 'closure logical_destination' -LogicalDestination
        Assert-NativeAppPrivateAssetDestinationAvailable -Destination $destination -BlockedRecords @() -AcceptedDestinations @($accepted)
        Assert-NativeAppPrivateAssetSha256 -Value ([string]$asset.source_sha256) -Label "$assetId source_sha256"
        if ([int64]$asset.source_bytes -lt 1) { throw "$assetId source_bytes must be positive." }
        if ([string]$asset.media_type -cnotmatch '\A[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*\z') {
            throw "$assetId media_type is malformed."
        }
        $expectedStaged = "private-assets/objects/$($asset.source_sha256)"
        if ([string]$asset.staged_object -cne $expectedStaged) { throw 'Private asset closure staged object is not content-addressed.' }
        $source = [IO.Path]::GetFullPath((Join-Path $lockRoot $expectedStaged))
        if (-not (Test-NativeAppPathInsideRoot -Path $source -Root $lockRoot) -or -not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Private asset staged object is missing or escaped lock root: $expectedStaged"
        }
        Assert-NativeAppNoReparsePath -Path $source -Root $lockRoot -Label 'Private asset staged object'
        if ((Get-Item -LiteralPath $source).Length -ne [int64]$asset.source_bytes -or
            (Get-NativeAppPrivateAssetFileSha256 -Path $source) -cne [string]$asset.source_sha256) {
            throw "Private asset staged object changed after resolution: $expectedStaged"
        }
        $target = [IO.Path]::GetFullPath((Join-Path $destinationRootFull ($destination.Replace('/', [IO.Path]::DirectorySeparatorChar))))
        if (-not (Test-NativeAppPathInsideRoot -Path $target -Root $destinationRootFull)) { throw 'Private asset package destination escaped assets root.' }
        $inventoryAssets += [ordered]@{
            asset_id = $assetId
            logical_destination = $destination
            staged_object = $expectedStaged
            source_sha256 = [string]$asset.source_sha256
            source_bytes = [int64]$asset.source_bytes
            media_type = [string]$asset.media_type
        }
        $prepared += [pscustomobject]@{ asset = $asset; source = $source; target = $target }
        $accepted.Add($destination)
    }
    $inventoryProjection = [ordered]@{
        schema = 'rusty.quest.native_app_private_asset_inventory.v1'
        provider_id = [string]$Closure.provider_id
        assets = $inventoryAssets
    }
    $actualInventorySha = Get-NativeAppPrivateAssetTextSha256 -Text ($inventoryProjection | ConvertTo-Json -Depth 12 -Compress)
    if ($actualInventorySha -cne [string]$Closure.inventory_sha256) {
        throw 'Private asset closure inventory digest does not match its canonical assets.'
    }

    $packaged = @()
    foreach ($entry in $prepared) {
        $asset = $entry.asset
        $source = [string]$entry.source
        $target = [string]$entry.target
        $destination = [string]$asset.logical_destination
        Ensure-NativeAppSafeDirectoryChain -Root $destinationRootFull -DirectoryPath (Split-Path -Parent $target) -Label 'Private asset package destination'
        if (Test-Path -LiteralPath $target) {
            Get-NativeAppInspectedItem -Path $target -Label 'Private asset package target' | Out-Null
        }
        $input = [IO.File]::OpenRead($source)
        try {
            $output = [IO.FileStream]::new($target, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
            try { $input.CopyTo($output) } finally { $output.Dispose() }
        } finally { $input.Dispose() }
        $packagedSha = Get-NativeAppPrivateAssetFileSha256 -Path $target
        $packagedBytes = (Get-Item -LiteralPath $target).Length
        if ($packagedSha -cne [string]$asset.source_sha256 -or $packagedBytes -ne [int64]$asset.source_bytes) {
            throw "Packaged private asset does not match its closure: $destination"
        }
        $packaged += [ordered]@{
            asset_id = [string]$asset.asset_id
            logical_destination = $destination
            apk_asset_path = "assets/$destination"
            expected_sha256 = [string]$asset.source_sha256
            packaged_sha256 = $packagedSha
            expected_bytes = [int64]$asset.source_bytes
            packaged_bytes = $packagedBytes
            media_type = [string]$asset.media_type
        }
    }
    return $packaged
}

Export-ModuleMember -Function @(
    'Get-NativeAppPrivateAssetFileSha256',
    'Get-NativeAppPrivateAssetTextSha256',
    'Resolve-NativeAppPublicAssetInput',
    'Get-NativeAppPublicAssetDestinationRecords',
    'New-NativeAppInactivePrivateAssetClosure',
    'Assert-NativeAppPrivateAssetRequest',
    'Resolve-NativeAppPrivateAssetProvider',
    'Publish-NativeAppPrivateAssetStaging',
    'Copy-NativeAppPrivateAssetsFromClosure'
)
