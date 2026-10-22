Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-SourceCompositionSha256 {
    param([Parameter(Mandatory=$true)][string]$Value)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace("-", "").ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function ConvertTo-SourceCompositionCanonicalField {
    param([Parameter(Mandatory=$true)][AllowEmptyString()][string]$Value)
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
}

function Get-QuestBuildSourceCompositionIdentityCanonicalText {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string[]]$PackageName,
        [Parameter(Mandatory=$true)]$Repository
    )
    $lines = [Collections.Generic.List[string]]::new()
    $hasWorkingTreeOverlay = @($Repository | Where-Object {
        $_.PSObject.Properties.Name -contains "tracked_worktree_clean" -and $_.tracked_worktree_clean -ne $true
    }).Count -gt 0
    $lines.Add("schema=rusty.quest.apk_source_composition_identity.v$(if ($hasWorkingTreeOverlay) { '2' } else { '1' })")
    foreach ($package in @($PackageName | ForEach-Object { [string]$_ } | Sort-Object -Unique)) {
        $lines.Add("package=" + (ConvertTo-SourceCompositionCanonicalField -Value $package))
    }
    foreach ($record in @($Repository | Sort-Object repository_id, role, commit, tree)) {
        $fields = [Collections.Generic.List[string]]::new()
        @(
            (ConvertTo-SourceCompositionCanonicalField -Value ([string]$record.repository_id)),
            (ConvertTo-SourceCompositionCanonicalField -Value ([string]$record.role)),
            ([string]$record.commit).ToLowerInvariant(),
            ([string]$record.tree).ToLowerInvariant()
        ) | ForEach-Object { $fields.Add([string]$_) }
        if ($record.PSObject.Properties.Name -contains "tracked_worktree_clean" -and $record.tracked_worktree_clean -ne $true) {
            $overlaySha256 = ([string]$record.worktree_overlay_sha256).ToLowerInvariant()
            if ($overlaySha256 -notmatch '^[0-9a-f]{64}$') { throw "Dirty source composition record lacks an exact worktree overlay SHA-256." }
            $fields.Add("dirty")
            $fields.Add($overlaySha256)
        }
        $lines.Add("repository=" + ($fields.ToArray() -join ":"))
    }
    return $lines -join "`n"
}

function Invoke-SourceCompositionGit {
    param([Parameter(Mandatory=$true)][string]$Root, [Parameter(Mandatory=$true)][string[]]$Arguments)
    $output = @(& git -C $Root @Arguments 2>&1 | ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) { throw "Git source-composition query failed in '$Root': git $($Arguments -join ' ')`n$($output -join "`n")" }
    return @($output)
}

function Get-NormalizedSourceCompositionPath {
    param([Parameter(Mandatory=$true)][string]$Path)
    return [IO.Path]::GetFullPath($Path).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
}

function Get-SourceCompositionWorktreeOverlaySha256 {
    param([Parameter(Mandatory=$true)][string]$Root)
    $trackedDiff = @(Invoke-SourceCompositionGit -Root $Root -Arguments @(
        "-c", "core.quotepath=false", "diff", "--binary", "--full-index", "--no-ext-diff", "--no-color", "HEAD", "--"
    )) -join "`n"
    $untrackedPaths = @(Invoke-SourceCompositionGit -Root $Root -Arguments @(
        "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard"
    ) | Sort-Object)
    $lines = [Collections.Generic.List[string]]::new()
    $lines.Add("schema=rusty.quest.source_worktree_overlay.v1")
    $lines.Add("tracked_diff=" + (ConvertTo-SourceCompositionCanonicalField -Value $trackedDiff))
    foreach ($relativePath in $untrackedPaths) {
        $fullPath = Join-Path $Root $relativePath
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            $lines.Add("untracked=" + (ConvertTo-SourceCompositionCanonicalField -Value $relativePath) + ":missing")
            continue
        }
        $fileHash = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $lines.Add("untracked=" + (ConvertTo-SourceCompositionCanonicalField -Value $relativePath) + ":" + $fileHash)
    }
    return Get-SourceCompositionSha256 -Value ($lines -join "`n")
}

function Find-SourceCompositionGitRoot {
    param([Parameter(Mandatory=$true)][string]$Path)
    $previousPreference = $ErrorActionPreference
    try {
        # Registry/cache packages are normally outside Git. Windows PowerShell
        # promotes git's expected stderr to an ErrorRecord while Stop is active.
        $ErrorActionPreference = "Continue"
        $output = @(& git -C $Path rev-parse --show-toplevel 2>$null)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0 -or $output.Count -eq 0) { return "" }
    return Get-NormalizedSourceCompositionPath -Path (([string]$output[0]).Trim())
}

function Get-SourceCompositionRepository {
    param(
        [Parameter(Mandatory=$true)][string]$Root,
        [Parameter(Mandatory=$true)][string]$RepositoryId,
        [Parameter(Mandatory=$true)][string]$Role,
        [switch]$AllowWorkingTreeChanges
    )
    $resolvedRoot = Get-NormalizedSourceCompositionPath -Path (([string]@(Invoke-SourceCompositionGit -Root $Root -Arguments @("rev-parse", "--show-toplevel"))[0]).Trim())
    $status = @(Invoke-SourceCompositionGit -Root $resolvedRoot -Arguments @("status", "--porcelain=v1", "--untracked-files=all"))
    $worktreeClean = $status.Count -eq 0
    if (-not $worktreeClean -and -not $AllowWorkingTreeChanges) {
        throw "Publication APK source-composition repository has working-tree changes: $RepositoryId ($resolvedRoot)"
    }
    $worktreeOverlaySha256 = if ($worktreeClean) { "" } else { Get-SourceCompositionWorktreeOverlaySha256 -Root $resolvedRoot }
    $commit = ([string]@(Invoke-SourceCompositionGit -Root $resolvedRoot -Arguments @("rev-parse", "HEAD"))[0]).Trim().ToLowerInvariant()
    $tree = ([string]@(Invoke-SourceCompositionGit -Root $resolvedRoot -Arguments @("rev-parse", "HEAD^{tree}"))[0]).Trim().ToLowerInvariant()
    if ($commit -notmatch '^[0-9a-f]{40}$' -or $tree -notmatch '^[0-9a-f]{40}$') { throw "APK source-composition repository lacks an exact commit/tree: $RepositoryId" }
    return [pscustomobject][ordered]@{
        repository_id = $RepositoryId
        role = $Role
        repository = [IO.Path]::GetFullPath($resolvedRoot)
        commit = $commit
        tree = $tree
        tracked_worktree_clean = $worktreeClean
        worktree_overlay_sha256 = $worktreeOverlaySha256
    }
}

function Get-QuestBuildSourceComposition {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string]$RepoRoot,
        [Parameter(Mandatory=$true)][string[]]$PackageName,
        [switch]$AllowWorkingTreeChanges
    )

    $root = Get-NormalizedSourceCompositionPath -Path (Resolve-Path -LiteralPath $RepoRoot).Path
    $metadataErrorPath = [IO.Path]::GetTempFileName()
    Push-Location $root
    try {
        $metadataText = @(& cargo metadata --format-version 1 --locked 2> $metadataErrorPath | ForEach-Object { [string]$_ })
        $metadataExitCode = $LASTEXITCODE
        $metadataError = if (Test-Path -LiteralPath $metadataErrorPath) { Get-Content -LiteralPath $metadataErrorPath -Raw } else { "" }
        if ($metadataExitCode -ne 0) { throw "Cargo metadata failed while resolving APK source composition:`n$metadataError" }
    } finally {
        Pop-Location
        Remove-Item -LiteralPath $metadataErrorPath -Force -ErrorAction SilentlyContinue
    }
    $metadata = ($metadataText -join "`n") | ConvertFrom-Json
    $packageById = @{}
    foreach ($package in @($metadata.packages)) { $packageById[[string]$package.id] = $package }
    $nodeById = @{}
    foreach ($node in @($metadata.resolve.nodes)) { $nodeById[[string]$node.id] = $node }

    $queue = [Collections.Generic.Queue[string]]::new()
    foreach ($name in @($PackageName | Sort-Object -Unique)) {
        $matches = @($metadata.packages | Where-Object { [string]$_.name -eq $name })
        if ($matches.Count -ne 1) { throw "APK source-composition package '$name' was found $($matches.Count) times." }
        $queue.Enqueue([string]$matches[0].id)
    }
    $visited = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    while ($queue.Count -gt 0) {
        $id = $queue.Dequeue()
        if (-not $visited.Add($id)) { continue }
        if (-not $nodeById.ContainsKey($id)) { continue }
        foreach ($dependency in @($nodeById[$id].deps)) { $queue.Enqueue([string]$dependency.pkg) }
    }

    $gitRoots = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    [void]$gitRoots.Add($root)
    foreach ($id in $visited) {
        if (-not $packageById.ContainsKey($id)) { continue }
        $manifestPath = [string]$packageById[$id].manifest_path
        if ([string]::IsNullOrWhiteSpace($manifestPath)) { continue }
        $packageRoot = Split-Path -Parent $manifestPath
        $candidateGitRoot = Find-SourceCompositionGitRoot -Path $packageRoot
        if (-not [string]::IsNullOrWhiteSpace($candidateGitRoot)) { [void]$gitRoots.Add($candidateGitRoot) }
    }

    $records = [Collections.Generic.List[object]]::new()
    foreach ($gitRoot in @($gitRoots | Sort-Object)) {
        $isPrimary = $gitRoot -ieq $root
        $repositoryId = if ($isPrimary) { "rusty-quest" } else { Split-Path -Leaf $gitRoot }
        $records.Add((Get-SourceCompositionRepository -Root $gitRoot -RepositoryId $repositoryId -Role $(if ($isPrimary) { "primary" } else { "path-dependency" }) -AllowWorkingTreeChanges:$AllowWorkingTreeChanges)) | Out-Null
    }
    $duplicateRepositoryIds = @($records.ToArray() | Group-Object repository_id | Where-Object { $_.Count -ne 1 })
    if ($duplicateRepositoryIds.Count -gt 0) {
        throw "APK source composition contains duplicate repository identities: $(@($duplicateRepositoryIds.Name) -join ', ')"
    }
    $identityRecords = @($records.ToArray() | Sort-Object repository_id | ForEach-Object {
        [pscustomobject][ordered]@{
            repository_id = [string]$_.repository_id
            role = [string]$_.role
            commit = [string]$_.commit
            tree = [string]$_.tree
            tracked_worktree_clean = [bool]$_.tracked_worktree_clean
            worktree_overlay_sha256 = [string]$_.worktree_overlay_sha256
        }
    })
    $canonicalIdentity = Get-QuestBuildSourceCompositionIdentityCanonicalText -PackageName $PackageName -Repository $identityRecords
    $fingerprint = Get-SourceCompositionSha256 -Value $canonicalIdentity
    return [pscustomobject][ordered]@{
        schema = "rusty.quest.apk_source_composition.v$(if (@($records.ToArray() | Where-Object { $_.tracked_worktree_clean -ne $true }).Count -gt 0) { '2' } else { '1' })"
        fingerprint = $fingerprint
        packages = @($PackageName | Sort-Object -Unique)
        repositories = @($records.ToArray() | Sort-Object repository_id)
    }
}

function Assert-QuestBuildSourceCompositionCurrent {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)]$Composition,
        [switch]$AllowWorkingTreeChanges
    )
    if ($Composition.schema -cnotin @('rusty.quest.apk_source_composition.v1', 'rusty.quest.apk_source_composition.v2') -or
            [string]$Composition.fingerprint -cnotmatch '^[0-9a-f]{64}$') {
        throw 'Unsupported or malformed APK source composition.'
    }
    $packages = @($Composition.packages)
    if ($packages.Count -eq 0 -or @($packages | Where-Object { $_ -isnot [string] -or [string]::IsNullOrWhiteSpace($_) }).Count -gt 0 -or
            @($packages | Sort-Object -Unique).Count -ne $packages.Count) {
        throw 'APK source composition package selection is invalid.'
    }
    $records = @($Composition.repositories)
    if ($records.Count -eq 0 -or @($records | Where-Object role -CEQ 'primary').Count -ne 1) {
        throw 'APK source composition requires exactly one primary repository.'
    }
    if (@($records | Where-Object role -CEQ 'primary')[0].repository_id -cne 'rusty-quest') {
        throw 'APK source composition primary identity differs.'
    }
    $ids = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $roots = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($record in $records) {
        if ([string]::IsNullOrWhiteSpace([string]$record.repository_id) -or
                -not $ids.Add([string]$record.repository_id) -or
                $record.role -cnotin @('primary', 'path-dependency', 'app-owned-input') -or
                [string]$record.commit -cnotmatch '^[0-9a-f]{40}$' -or
                [string]$record.tree -cnotmatch '^[0-9a-f]{40}$' -or
                $record.tracked_worktree_clean -isnot [bool] -or
                -not [IO.Path]::IsPathFullyQualified([string]$record.repository)) {
            throw 'APK source composition repository identity is invalid or duplicated.'
        }
        if ($record.tracked_worktree_clean) {
            if ([string]$record.worktree_overlay_sha256 -cne '') { throw 'Clean source composition record cannot carry an overlay.' }
        } elseif ([string]$record.worktree_overlay_sha256 -cnotmatch '^[0-9a-f]{64}$') {
            throw 'Dirty source composition record lacks an exact overlay.'
        }
        $root = Get-NormalizedSourceCompositionPath -Path (Resolve-Path -LiteralPath $record.repository).Path
        if (-not $roots.Add($root) -or (Find-SourceCompositionGitRoot -Path $root) -ine $root) {
            throw 'APK source composition repository path is duplicated or is not a Git root.'
        }
    }
    $dirty = @($records | Where-Object tracked_worktree_clean -NE $true).Count -gt 0
    $expectedSchema = 'rusty.quest.apk_source_composition.v' + $(if ($dirty) { '2' } else { '1' })
    if ($Composition.schema -cne $expectedSchema) { throw 'APK source composition schema does not match its overlays.' }
    $canonical = Get-QuestBuildSourceCompositionIdentityCanonicalText -PackageName $packages -Repository $records
    if ((Get-SourceCompositionSha256 -Value $canonical) -cne $Composition.fingerprint) {
        throw 'APK source composition fingerprint differs from its document.'
    }
    foreach ($record in $records) {
        # Never trust a caller-supplied overlay, even when its document checksum is self-consistent.
        $actual = Get-SourceCompositionRepository -Root $record.repository -RepositoryId $record.repository_id `
            -Role $record.role -AllowWorkingTreeChanges:$AllowWorkingTreeChanges
        if ($actual.commit -cne $record.commit -or $actual.tree -cne $record.tree -or
                $actual.tracked_worktree_clean -ne $record.tracked_worktree_clean -or
                $actual.worktree_overlay_sha256 -cne $record.worktree_overlay_sha256) {
            throw "APK source composition repository changed: $($record.repository_id)"
        }
    }
}

function Add-QuestBuildOwnedRepository {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)]$Composition,
        [Parameter(Mandatory=$true)][string]$Root,
        [Parameter(Mandatory=$true)][ValidatePattern('^[a-z][a-z0-9-]*$')][string]$RepositoryId,
        [switch]$AllowWorkingTreeChanges
    )
    Assert-QuestBuildSourceCompositionCurrent -Composition $Composition -AllowWorkingTreeChanges:$AllowWorkingTreeChanges
    $resolved = Get-NormalizedSourceCompositionPath -Path (Resolve-Path -LiteralPath $Root).Path
    if ((Find-SourceCompositionGitRoot -Path $resolved) -ine $resolved) {
        throw 'Additional owned input must select its exact Git repository root.'
    }
    foreach ($record in @($Composition.repositories)) {
        $existing = Get-NormalizedSourceCompositionPath -Path $record.repository
        if ($record.repository_id -ieq $RepositoryId) { throw 'Additional owned repository identity already exists.' }
        if ($existing -ieq $resolved -or
                $existing.StartsWith($resolved + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
                $resolved.StartsWith($existing + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Additional owned repository path conflicts with an existing source owner; retain its existing record.'
        }
    }
    $owned = Get-SourceCompositionRepository -Root $resolved -RepositoryId $RepositoryId -Role 'app-owned-input' `
        -AllowWorkingTreeChanges:$AllowWorkingTreeChanges
    $records = @(@($Composition.repositories) + $owned | Sort-Object repository_id)
    $canonical = Get-QuestBuildSourceCompositionIdentityCanonicalText -PackageName $Composition.packages -Repository $records
    return [pscustomobject][ordered]@{
        schema = 'rusty.quest.apk_source_composition.v' + $(if (@($records | Where-Object tracked_worktree_clean -NE $true).Count -gt 0) { '2' } else { '1' })
        fingerprint = Get-SourceCompositionSha256 -Value $canonical
        packages = @($Composition.packages)
        repositories = $records
    }
}

function Assert-QuestNativeDependencyComposition {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)]$ShellComposition,
        [Parameter(Mandatory=$true)][string]$NativeRepoRoot
    )
    # Moving the primary manifest also relocates its sibling path dependencies.
    $native = Get-QuestBuildSourceComposition -RepoRoot $NativeRepoRoot -PackageName $ShellComposition.packages
    if ($native.fingerprint -cne $ShellComposition.fingerprint) {
        throw 'Native/shell dependency composition differs.'
    }
    foreach ($supplier in @($native.repositories | Where-Object role -CEQ 'path-dependency')) {
        $shell = @($ShellComposition.repositories | Where-Object repository_id -CEQ $supplier.repository_id)
        if ($shell.Count -ne 1) { throw 'Native supplier has no unique shell source binding.' }
        # Clean Git identity can still hide different raw bytes through checkout filters.
        $paths = (Invoke-SourceCompositionGit -Root $supplier.repository -Arguments @('ls-files', '-z')) -join "`n"
        foreach ($relative in $paths.Split([char]0, [StringSplitOptions]::RemoveEmptyEntries)) {
            $actual = Join-Path $supplier.repository $relative
            $expected = Join-Path $shell[0].repository $relative
            if (-not (Test-Path -LiteralPath $actual -PathType Leaf) -or
                -not (Test-Path -LiteralPath $expected -PathType Leaf) -or
                ((Get-Item -LiteralPath $actual).Attributes -band [IO.FileAttributes]::ReparsePoint) -or
                ((Get-Item -LiteralPath $expected).Attributes -band [IO.FileAttributes]::ReparsePoint) -or
                (Get-FileHash -LiteralPath $actual -Algorithm SHA256).Hash -cne
                    (Get-FileHash -LiteralPath $expected -Algorithm SHA256).Hash) {
                throw 'Native/shell supplier raw source bytes differ.'
            }
        }
    }
}

Export-ModuleMember -Function Get-QuestBuildSourceComposition, Get-QuestBuildSourceCompositionIdentityCanonicalText, Add-QuestBuildOwnedRepository, Assert-QuestBuildSourceCompositionCurrent, Assert-QuestNativeDependencyComposition
