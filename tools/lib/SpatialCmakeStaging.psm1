function Get-SpatialCmakeStaging {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$BuildCacheRoot,
          [Parameter(Mandatory)][string]$ProductId,
          [Parameter(Mandatory)][ValidateSet('debug','release')][string]$BuildType)
    if ($ProductId -cnotmatch '^[a-z0-9][a-z0-9.-]*$') { throw 'Invalid CMake cache product identity.' }
    if (-not [IO.Path]::IsPathFullyQualified($BuildCacheRoot)) { throw 'CMake cache root must be absolute.' }
    $cache = [IO.Path]::GetFullPath($BuildCacheRoot)
    $path = Join-Path $cache "g/$ProductId-$BuildType/x"
    $cursor = $path
    while ($cursor) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'CMake cache ancestors cannot be links.'
        }
        $cursor = Split-Path -Parent $cursor
    }
    # Same serialized product/type lane as Gradle; a sibling of temporary a/r
    # build directories, so Gradle clean cannot delete its persistent metadata.
    $variant = if ($BuildType -ceq 'debug') { 'Debug' } else { 'Release' }
    $objectDirectory = Join-Path $path "$variant/12345678/arm64-v8a/CMakeFiles/XR_APILAYER_MESMERPRISM_spatial_sdk_depth_handoff.dir"
    $object = Join-Path $objectDirectory 'spatial_depth_queue_broker.cpp.o.d'
    # CMake's default Windows object-path maximum is 250. This owner-known
    # directory and longest source/object suffix must fit before any compiler.
    if ($IsWindows -and $object.Length -ge 250) {
        throw "Spatial CMake object path would be $($object.Length) characters (CMake limit 250). Select a shorter absolute -BuildCacheRoot; source/output relocation is unnecessary."
    }
    [pscustomobject]@{path=$path;object_directory=$objectDirectory;projected_object_path_chars=$object.Length;object_path_limit=250;policy='serialized-product-type-cache-sibling'}
}
function Get-SpatialCmakeStagingReceipt {
    param([Parameter(Mandatory)]$Staging)
    $digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Staging.path))).ToLowerInvariant()
    [ordered]@{policy=$Staging.policy;projected_object_path_chars=$Staging.projected_object_path_chars;
        object_path_limit=$Staging.object_path_limit;selected_path_sha256=$digest}
}
Export-ModuleMember -Function Get-SpatialCmakeStaging,Get-SpatialCmakeStagingReceipt
