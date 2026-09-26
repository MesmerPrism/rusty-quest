[CmdletBinding()]
param(
    [string]$RepoRoot = (Join-Path $PSScriptRoot '../..'),
    [Parameter(Mandatory)][string]$SupplierMapPath,
    [Parameter(Mandatory)][string]$OutputRoot,
    [Parameter(Mandatory)][string]$AndroidHome,
    [Parameter(Mandatory)][string]$JavaHome,
    [string]$TargetCacheRoot,
    [ValidateSet('Prepare', 'Native', 'JavaApi34')][string]$Mode = 'Prepare',
    [switch]$AllowDiagnosticLockUpdate
)
$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$SupplierMapPath = (Resolve-Path -LiteralPath $SupplierMapPath).Path
$AndroidHome = (Resolve-Path -LiteralPath $AndroidHome).Path
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$ignoredRoot = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'target')) + [IO.Path]::DirectorySeparatorChar
if (-not $OutputRoot.StartsWith($ignoredRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Output must be inside the caller repository ignored target directory.'
}
& git -C $RepoRoot check-ignore $OutputRoot | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Output must be Git ignored.' }
$composition = Join-Path $OutputRoot 'composition'
$snapshot = Join-Path $composition 'rusty-quest'
if ([string]::IsNullOrWhiteSpace($TargetCacheRoot)) { $TargetCacheRoot = Join-Path $OutputRoot 'cargo-target' }
$TargetCacheRoot = [IO.Path]::GetFullPath($TargetCacheRoot)
if (-not $TargetCacheRoot.StartsWith($ignoredRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Shared target cache must remain inside caller ignored target storage.' }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Json([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 30), [Text.UTF8Encoding]::new($false))
}
function Copy-Source([string]$Root, [string]$Destination) {
    $files = @(& git -C $Root ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Git source inventory failed.' }
    $inventory = foreach ($relative in $files) {
        $source = Join-Path $Root $relative
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }
        $destinationFile = Join-Path $Destination $relative
        [void](New-Item -ItemType Directory -Force -Path (Split-Path $destinationFile -Parent))
        $before = Hash $source
        Copy-Item -LiteralPath $source -Destination $destinationFile
        if ((Hash $destinationFile) -cne $before -or (Hash $source) -cne $before) { throw "Source changed during snapshot: $relative" }
        [ordered]@{ path = $relative; sha256 = $before }
    }
    return @($inventory)
}
function Verify-Snapshot {
    foreach ($name in @('quest', 'rusty-manifold', 'rusty-lattice', 'rusty-matter', 'rusty-optics')) {
        $inventory = Get-Content -Raw -LiteralPath (Join-Path $OutputRoot "$name-inventory.json") | ConvertFrom-Json
        $root = if ($name -eq 'quest') { $snapshot } else { Join-Path $composition $name }
        foreach ($file in $inventory) {
            if ($name -eq 'quest' -and $file.path -ceq 'Cargo.lock') { continue }
            if ((Hash (Join-Path $root $file.path)) -cne $file.sha256) { throw "Snapshot changed: $name/$($file.path)" }
        }
        if ((Hash (Join-Path $OutputRoot "$name-inventory.json")) -cne $binding.inventory_hashes.$name) { throw "Inventory changed: $name" }
    }
    if ((Hash (Join-Path $snapshot 'Cargo.lock')) -cne $snapshotLockHash) { throw 'Snapshot lock changed without a recorded diagnostic update.' }
    if ((Hash $SupplierMapPath) -cne $binding.supplier_map_sha256 -or (Hash $PSCommandPath) -cne $binding.runner_sha256) { throw 'Supplier map or runner changed since Prepare.' }
    foreach ($tool in $binding.tools) { if ((Hash $tool.path) -cne $tool.sha256) { throw "Validation tool changed: $($tool.path)" } }
}
if ($Mode -eq 'Prepare') {
    if (Test-Path -LiteralPath $OutputRoot) { throw 'Prepare requires an absent output directory; retained diagnostics are never overwritten.' }
    [void](New-Item -ItemType Directory -Path $OutputRoot)
    $map = Get-Content -Raw -LiteralPath $SupplierMapPath | ConvertFrom-Json
    $identities = foreach ($name in @('rusty-manifold', 'rusty-lattice', 'rusty-matter', 'rusty-optics')) {
        $entry = $map.$name
        if ($null -eq $entry -or $entry.commit -notmatch '^[a-f0-9]{40}$' -or $entry.tree -notmatch '^[a-f0-9]{40}$') { throw "Explicit commit/tree supplier binding required: $name" }
        $root = (Resolve-Path -LiteralPath $entry.root).Path
        $head = (& git -C $root rev-parse "$($entry.commit)^{commit}").Trim()
        $tree = (& git -C $root rev-parse "$($entry.commit)^{tree}").Trim()
        if ($head -cne $entry.commit -or $tree -cne $entry.tree) { throw "Supplier Git object is not exact: $name" }
        $supplierOutput = Join-Path $composition $name
        [void](New-Item -ItemType Directory -Force -Path $supplierOutput)
        $archive = Join-Path $OutputRoot "$name.tar"
        & git -C $root archive --format=tar "--output=$archive" $head
        if ($LASTEXITCODE -ne 0) { throw "Supplier archive failed: $name" }
        & tar -xf $archive -C $supplierOutput
        if ($LASTEXITCODE -ne 0) { throw "Supplier materialization failed: $name" }
        $treeEntries = @(& git -C $root ls-tree -r '--format=%(objectmode) %(objectname) %(path)' $head)
        $paths = @($treeEntries | ForEach-Object { if ($_ -notmatch '^100(644|755) ([a-f0-9]{40}) (.+)$') { throw "Unsupported supplier tree entry: $_" }; Join-Path $supplierOutput $Matches[3] })
        $blobs = @($paths | & git -C $root hash-object --no-filters --stdin-paths)
        if ($LASTEXITCODE -ne 0 -or $blobs.Count -ne $treeEntries.Count) { throw "Supplier Git blob verification failed: $name" }
        $inventory = @(for ($i = 0; $i -lt $treeEntries.Count; $i++) {
            if ($treeEntries[$i] -notmatch '^100(644|755) ([a-f0-9]{40}) (.+)$') { throw 'Invalid tree entry' }
            if ($blobs[$i] -cne $Matches[2]) { throw "Archive bytes differ from committed blob: $name/$($Matches[3])" }
            [ordered]@{ path = $Matches[3]; sha256 = Hash $paths[$i]; git_blob = $blobs[$i] }
        })
        if (@(Get-ChildItem -LiteralPath $supplierOutput -File -Recurse).Count -ne $inventory.Count) { throw "Supplier file inventory mismatch: $name" }
        Json (Join-Path $OutputRoot "$name-inventory.json") $inventory
        [ordered]@{ name = $name; root = $root; commit = $head; tree = $tree; inventory_count = $inventory.Count; materialization = 'exact committed Git archive; concurrent worktree changes excluded' }
    }
    $questInventory = Copy-Source $RepoRoot $snapshot
    Json (Join-Path $OutputRoot 'quest-inventory.json') $questInventory
    $inventoryHashes = [ordered]@{}
    foreach ($name in @('quest', 'rusty-manifold', 'rusty-lattice', 'rusty-matter', 'rusty-optics')) { $inventoryHashes[$name] = Hash (Join-Path $OutputRoot "$name-inventory.json") }
    $toolIdentities = @((Get-Command cargo).Source, (Get-Command rustc).Source,
        (Join-Path $JavaHome 'bin/javac.exe'),
        (Join-Path $AndroidHome 'platforms/android-34/android.jar'),
        (Join-Path $AndroidHome 'ndk/27.2.12479018/shader-tools/windows-x86_64/glslc.exe')) | ForEach-Object { [ordered]@{ path = $_; sha256 = Hash $_ } }
    Json (Join-Path $OutputRoot 'source-bindings.json') ([ordered]@{
        quest_head = (& git -C $RepoRoot rev-parse HEAD).Trim()
        quest_dirty = @(& git -C $RepoRoot status --porcelain=v1 --untracked-files=all)
        quest_inventory_count = $questInventory.Count
        original_lock_sha256 = Hash (Join-Path $RepoRoot 'Cargo.lock')
        suppliers = @($identities)
        inventory_hashes = $inventoryHashes
        supplier_map_sha256 = Hash $SupplierMapPath
        runner_sha256 = Hash $PSCommandPath
        tools = @($toolIdentities)
        qualification = 'complete-source composition; Native result records locked resolution outcome; no APK acceptance'
    })
    Write-Output "Prepared byte-preserving source composition: $snapshot"
    return
}
if (-not (Test-Path -LiteralPath (Join-Path $OutputRoot 'source-bindings.json'))) { throw 'Run Prepare first.' }
$binding = Get-Content -Raw -LiteralPath (Join-Path $OutputRoot 'source-bindings.json') | ConvertFrom-Json
$nativeResultPath = Join-Path $OutputRoot 'native-result.json'
$snapshotLockHash = if (Test-Path -LiteralPath $nativeResultPath) { (Get-Content -Raw -LiteralPath $nativeResultPath | ConvertFrom-Json).diagnostic_lock_sha256 } else { $binding.original_lock_sha256 }
Verify-Snapshot
if ((Hash (Join-Path $RepoRoot 'Cargo.lock')) -cne $binding.original_lock_sha256) { throw 'Original Cargo.lock changed.' }
if ($Mode -eq 'Native') {
    $ndk = Join-Path $AndroidHome 'ndk/27.2.12479018'
    $glslc = Join-Path $ndk 'shader-tools/windows-x86_64/glslc.exe'
    if (-not (Test-Path -LiteralPath $glslc)) { throw 'NDK shader compiler unavailable.' }
    $oldNdk = $env:ANDROID_NDK_HOME
    $oldGlslc = $env:GLSLC
    $oldTarget = $env:CARGO_TARGET_DIR
    try {
        $env:ANDROID_NDK_HOME = $ndk
        $env:GLSLC = $glslc
        $env:CARGO_TARGET_DIR = $TargetCacheRoot
        Push-Location $snapshot
        try {
            & cargo metadata --format-version 1 --locked --offline 1> (Join-Path $OutputRoot 'metadata.json') 2> (Join-Path $OutputRoot 'baseline-lock.log')
            $baselineExit = $LASTEXITCODE
            if ($baselineExit -ne 0) {
                if (-not $AllowDiagnosticLockUpdate) { throw 'Snapshot baseline locked resolution failed; inspect baseline-lock.log.' }
                Copy-Item -LiteralPath Cargo.lock -Destination (Join-Path $OutputRoot 'Cargo.baseline.lock')
                & cargo metadata --format-version 1 --offline 1> (Join-Path $OutputRoot 'metadata.json') 2> (Join-Path $OutputRoot 'diagnostic-lock.log')
                if ($LASTEXITCODE -ne 0) { throw 'Diagnostic complete graph resolution failed.' }
                & git diff --no-index -- (Join-Path $OutputRoot 'Cargo.baseline.lock') (Join-Path $snapshot 'Cargo.lock') 1> (Join-Path $OutputRoot 'lock-delta.diff')
            }
            $metadata = Get-Content -Raw -LiteralPath (Join-Path $OutputRoot 'metadata.json') | ConvertFrom-Json
            $snapshotLockHash = Hash (Join-Path $snapshot 'Cargo.lock')
            foreach ($package in $metadata.packages) {
                $manifestPath = [IO.Path]::GetFullPath($package.manifest_path)
                $compositionBoundary = [IO.Path]::GetFullPath($composition) + [IO.Path]::DirectorySeparatorChar
                if ($null -eq $package.source -and -not $manifestPath.StartsWith($compositionBoundary, [StringComparison]::OrdinalIgnoreCase)) {
                    throw "Undeclared source binding: $($package.manifest_path)"
                }
            }
            & cargo check --locked --offline -p spatial-camera-panel-native-receipt --target aarch64-linux-android 2>&1 | Tee-Object -FilePath (Join-Path $OutputRoot 'native-check.log')
            $checkExit = $LASTEXITCODE
            Verify-Snapshot
            Json (Join-Path $OutputRoot 'native-result.json') ([ordered]@{ baseline_locked_exit = $baselineExit; check_exit = $checkExit; diagnostic_lock_update = ($baselineExit -ne 0); diagnostic_lock_sha256 = Hash (Join-Path $snapshot 'Cargo.lock'); original_lock_unchanged = ((Hash (Join-Path $RepoRoot 'Cargo.lock')) -ceq $binding.original_lock_sha256); target = 'aarch64-linux-android'; target_cache_root = $TargetCacheRoot; package = 'spatial-camera-panel-native-receipt'; scope = 'complete production package including frame_jni; no cfg substitutions or module copies'; qualification = 'typecheck only, no link/APK/device/acceptance' })
            if ($checkExit -ne 0) { throw 'Full native Android typecheck failed; inspect native-check.log.' }
        } finally { Pop-Location }
    } finally {
        $env:ANDROID_NDK_HOME = $oldNdk
        $env:GLSLC = $oldGlslc
        $env:CARGO_TARGET_DIR = $oldTarget
    }
} else {
    $jar = Join-Path $AndroidHome 'platforms/android-34/android.jar'
    $javac = Join-Path $JavaHome 'bin/javac.exe'
    $javaOutput = Join-Path $OutputRoot 'java-api34'
    [void](New-Item -ItemType Directory -Force -Path $javaOutput)
    $source = Join-Path $snapshot 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexProcessFence.java'
    & $javac '-source' '8' '-target' '8' '-bootclasspath' $jar '-encoding' 'UTF-8' '-d' $javaOutput $source 2>&1 | Tee-Object -FilePath (Join-Path $OutputRoot 'java-api34.log')
    $compileExit = $LASTEXITCODE
    $negative = Join-Path $javaOutput 'Api34Negative.java'
    [IO.File]::WriteAllText($negative, 'final class Api34Negative { String x(java.nio.file.Path p) throws Exception { return java.nio.file.Files.readString(p); } }')
    & $javac '-source' '8' '-target' '8' '-bootclasspath' $jar '-d' $javaOutput $negative 2>&1 | Tee-Object -FilePath (Join-Path $OutputRoot 'java-api34-negative.log')
    $negativeExit = $LASTEXITCODE
    Verify-Snapshot
    Json (Join-Path $OutputRoot 'java-api34-result.json') ([ordered]@{ compile_exit = $compileExit; negative_exit = $negativeExit; sdk_jar_sha256 = Hash $jar; source_sha256 = Hash $source; scope = 'production ProcessFence Java source against genuine SDK34 bootclasspath; no desktop system modules'; lint = 'not run'; full_app_java_compile = 'not run'; language_target = 8 })
    if ($compileExit -ne 0 -or $negativeExit -eq 0) { throw 'API34 compile or missing-API negative control failed.' }
}
