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
    $lines.Add("schema=rusty.quest.apk_source_composition_identity.v1")
    foreach ($package in @($PackageName | ForEach-Object { [string]$_ } | Sort-Object -Unique)) {
        $lines.Add("package=" + (ConvertTo-SourceCompositionCanonicalField -Value $package))
    }
    foreach ($record in @($Repository | Sort-Object repository_id, role, commit, tree)) {
        $fields = @(
            (ConvertTo-SourceCompositionCanonicalField -Value ([string]$record.repository_id)),
            (ConvertTo-SourceCompositionCanonicalField -Value ([string]$record.role)),
            ([string]$record.commit).ToLowerInvariant(),
            ([string]$record.tree).ToLowerInvariant()
        )
        $lines.Add("repository=" + ($fields -join ":"))
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
    param([Parameter(Mandatory=$true)][string]$Root, [Parameter(Mandatory=$true)][string]$RepositoryId, [Parameter(Mandatory=$true)][string]$Role)
    $resolvedRoot = Get-NormalizedSourceCompositionPath -Path (([string]@(Invoke-SourceCompositionGit -Root $Root -Arguments @("rev-parse", "--show-toplevel"))[0]).Trim())
    $status = @(Invoke-SourceCompositionGit -Root $resolvedRoot -Arguments @("status", "--porcelain=v1", "--untracked-files=no"))
    if ($status.Count -gt 0) { throw "APK source-composition repository has tracked changes: $RepositoryId ($resolvedRoot)" }
    $commit = ([string]@(Invoke-SourceCompositionGit -Root $resolvedRoot -Arguments @("rev-parse", "HEAD"))[0]).Trim().ToLowerInvariant()
    $tree = ([string]@(Invoke-SourceCompositionGit -Root $resolvedRoot -Arguments @("rev-parse", "HEAD^{tree}"))[0]).Trim().ToLowerInvariant()
    if ($commit -notmatch '^[0-9a-f]{40}$' -or $tree -notmatch '^[0-9a-f]{40}$') { throw "APK source-composition repository lacks an exact commit/tree: $RepositoryId" }
    return [pscustomobject][ordered]@{
        repository_id = $RepositoryId
        role = $Role
        repository = [IO.Path]::GetFullPath($resolvedRoot)
        commit = $commit
        tree = $tree
        tracked_worktree_clean = $true
    }
}

function Get-QuestBuildSourceComposition {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string]$RepoRoot,
        [Parameter(Mandatory=$true)][string[]]$PackageName
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
        $records.Add((Get-SourceCompositionRepository -Root $gitRoot -RepositoryId $repositoryId -Role $(if ($isPrimary) { "primary" } else { "path-dependency" }))) | Out-Null
    }
    $duplicateRepositoryIds = @($records.ToArray() | Group-Object repository_id | Where-Object { $_.Count -ne 1 })
    if ($duplicateRepositoryIds.Count -gt 0) {
        throw "APK source composition contains duplicate repository identities: $(@($duplicateRepositoryIds.Name) -join ', ')"
    }
    $identityRecords = @($records.ToArray() | Sort-Object repository_id | ForEach-Object {
        [pscustomobject][ordered]@{ repository_id = [string]$_.repository_id; role = [string]$_.role; commit = [string]$_.commit; tree = [string]$_.tree }
    })
    $canonicalIdentity = Get-QuestBuildSourceCompositionIdentityCanonicalText -PackageName $PackageName -Repository $identityRecords
    $fingerprint = Get-SourceCompositionSha256 -Value $canonicalIdentity
    return [pscustomobject][ordered]@{
        schema = "rusty.quest.apk_source_composition.v1"
        fingerprint = $fingerprint
        packages = @($PackageName | Sort-Object -Unique)
        repositories = @($records.ToArray() | Sort-Object repository_id)
    }
}

Export-ModuleMember -Function Get-QuestBuildSourceComposition, Get-QuestBuildSourceCompositionIdentityCanonicalText
