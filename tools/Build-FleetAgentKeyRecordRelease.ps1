# Copyright (C) 2026 Rusty Quest contributors
# SPDX-License-Identifier: AGPL-3.0-or-later

[CmdletBinding()]
param(
    [ValidateSet("1.0.0")]
    [string] $CapsuleVersion = "1.0.0",

    [string] $OutputDirectory = "",

    [switch] $EnvironmentSelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSEdition -ne "Core" -or
    $PSVersionTable.PSVersion -lt [version]"7.6") {
    throw "Fleet Agent key-record release builds require PowerShell 7.6 Core or newer."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Import-Module (Join-Path $PSScriptRoot "lib\SourceComposition.psm1") -Force
$supportedCapsuleVersion = "1.0.0"
$targetTriple = "x86_64-pc-windows-msvc"
$nullEnvironmentValue = [System.Management.Automation.Language.NullString]::Value

if ($CapsuleVersion -cne $supportedCapsuleVersion) {
    throw "Fleet Agent key-record release capsule version is unsupported."
}

function Get-Sha256([string] $LiteralPath) {
    return (Get-FileHash -LiteralPath $LiteralPath -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-CanonicalJson([string] $LiteralPath, $Value) {
    $json = ($Value | ConvertTo-Json -Depth 20) -replace "`r`n", "`n"
    [IO.File]::WriteAllText(
        $LiteralPath,
        $json.TrimEnd("`r", "`n") + "`n",
        [Text.UTF8Encoding]::new($false))
}

function Write-CanonicalText([string] $LiteralPath, [string] $Value) {
    $text = ($Value -replace "`r`n", "`n").TrimEnd("`r", "`n") + "`n"
    [IO.File]::WriteAllText($LiteralPath, $text, [Text.UTF8Encoding]::new($false))
}

function Invoke-GitChecked {
    param(
        [Parameter(Mandatory)]
        [string] $Root,
        [Parameter(Mandatory)]
        [string[]] $Arguments,
        [string] $Failure = "Git command failed."
    )
    $output = @(& git -C $Root @Arguments 2>&1 | ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) {
        throw "$Failure`n$($output -join "`n")"
    }
    return @($output)
}

function Assert-ExactGitMaterialization {
    param(
        [Parameter(Mandatory)]
        [string] $Root,
        [Parameter(Mandatory)]
        [string] $ExpectedCommit,
        [Parameter(Mandatory)]
        [string] $ExpectedTree,
        [Parameter(Mandatory)]
        [string] $Label
    )
    $commit = ([string]@(Invoke-GitChecked -Root $Root -Arguments @("rev-parse", "HEAD") `
        -Failure "$Label materialization commit could not be resolved.")[0]).Trim().ToLowerInvariant()
    $tree = ([string]@(Invoke-GitChecked -Root $Root -Arguments @("rev-parse", "HEAD^{tree}") `
        -Failure "$Label materialization tree could not be resolved.")[0]).Trim().ToLowerInvariant()
    $dirt = @(Invoke-GitChecked -Root $Root `
        -Arguments @("status", "--porcelain=v1", "--untracked-files=all") `
        -Failure "$Label materialization status could not be resolved.")
    if ($commit -cne $ExpectedCommit -or $tree -cne $ExpectedTree -or $dirt.Count -ne 0) {
        throw "$Label materialization is not the exact clean Git object set."
    }
}

function Initialize-ExactGitMaterialization {
    param(
        [Parameter(Mandatory)]
        [string] $SourceRoot,
        [Parameter(Mandatory)]
        [string] $Destination,
        [Parameter(Mandatory)]
        [string] $ExpectedCommit,
        [Parameter(Mandatory)]
        [string] $ExpectedTree,
        [Parameter(Mandatory)]
        [string] $Label
    )
    $cloneOutput = @(& git -c core.autocrlf=false clone --quiet --no-hardlinks `
        --no-checkout --no-tags -- $SourceRoot $Destination 2>&1 | ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) {
        throw "$Label exact Git-object materialization failed.`n$($cloneOutput -join "`n")"
    }
    [void](Invoke-GitChecked -Root $Destination `
        -Arguments @("-c", "core.autocrlf=false", "checkout", "--detach", $ExpectedCommit) `
        -Failure "$Label exact commit checkout failed.")
    Assert-ExactGitMaterialization -Root $Destination -ExpectedCommit $ExpectedCommit `
        -ExpectedTree $ExpectedTree -Label $Label
}

function Assert-ExactComposition {
    param(
        [Parameter(Mandatory)]
        $Composition,
        [Parameter(Mandatory)]
        [string] $QuestCommit,
        [Parameter(Mandatory)]
        [string] $QuestTree,
        [Parameter(Mandatory)]
        [string] $FleetCommit,
        [Parameter(Mandatory)]
        [string] $FleetTree,
        [Parameter(Mandatory)]
        [string] $ManifoldCommit,
        [Parameter(Mandatory)]
        [string] $ManifoldTree
    )
    $repositories = @($Composition.repositories)
    $questMatches = @($repositories | Where-Object {
        $_.repository_id -eq "rusty-quest" -and $_.role -eq "primary" -and
        $_.commit -ceq $QuestCommit -and $_.tree -ceq $QuestTree
    })
    $fleetMatches = @($repositories | Where-Object {
        $_.role -eq "path-dependency" -and
        $_.commit -ceq $FleetCommit -and $_.tree -ceq $FleetTree
    })
    $manifoldMatches = @($repositories | Where-Object {
        $_.repository_id -eq "rusty-manifold" -and $_.role -eq "path-dependency" -and
        $_.commit -ceq $ManifoldCommit -and $_.tree -ceq $ManifoldTree
    })
    if ($repositories.Count -ne 3 -or $questMatches.Count -ne 1 -or
        $fleetMatches.Count -ne 1 -or $manifoldMatches.Count -ne 1) {
        throw "Fleet Agent key-record release source composition is not the exact closed owner set."
    }
}

function Get-ExactGitSourceRecord {
    param(
        [Parameter(Mandatory)]
        [string] $Root,
        [Parameter(Mandatory)]
        [string] $RepositoryId,
        [Parameter(Mandatory)]
        [string] $RepositoryUrl
    )
    $resolvedInput = (Resolve-Path -LiteralPath $Root).Path
    $gitRoot = ([string]@(Invoke-GitChecked -Root $resolvedInput `
        -Arguments @("rev-parse", "--show-toplevel") `
        -Failure "$RepositoryId parse-only source root could not be resolved.")[0]).Trim()
    if (-not [string]::Equals(
        [IO.Path]::GetFullPath($gitRoot),
        [IO.Path]::GetFullPath($resolvedInput),
        [StringComparison]::OrdinalIgnoreCase)) {
        throw "$RepositoryId parse-only source path is not an exact repository root."
    }
    $commit = ([string]@(Invoke-GitChecked -Root $gitRoot -Arguments @("rev-parse", "HEAD") `
        -Failure "$RepositoryId parse-only source commit could not be resolved.")[0]).Trim().ToLowerInvariant()
    $tree = ([string]@(Invoke-GitChecked -Root $gitRoot -Arguments @("rev-parse", "HEAD^{tree}") `
        -Failure "$RepositoryId parse-only source tree could not be resolved.")[0]).Trim().ToLowerInvariant()
    $dirt = @(Invoke-GitChecked -Root $gitRoot `
        -Arguments @("status", "--porcelain=v1", "--untracked-files=all") `
        -Failure "$RepositoryId parse-only source status could not be resolved.")
    if ($commit -cnotmatch '^[0-9a-f]{40}$' -or $tree -cnotmatch '^[0-9a-f]{40}$' -or
        $dirt.Count -ne 0) {
        throw "$RepositoryId parse-only source is not an exact clean Git object set."
    }
    return [pscustomobject][ordered]@{
        repository_id = $RepositoryId
        role = "workspace-parse-only"
        repository_url = $RepositoryUrl
        repository = [IO.Path]::GetFullPath($gitRoot)
        commit = $commit
        tree = $tree
    }
}

function Assert-ClosedWorkspaceSiblingPathSet([string] $QuestRoot) {
    $manifestPaths = @(Invoke-GitChecked -Root $QuestRoot `
        -Arguments @("ls-files", "--", "Cargo.toml", "crates/**/Cargo.toml", "apps/**/Cargo.toml") `
        -Failure "Fleet Agent key-record release workspace manifest set could not be resolved.")
    if ($manifestPaths.Count -eq 0) {
        throw "Fleet Agent key-record release workspace manifest set is empty."
    }
    $repositoryIds = @($manifestPaths | ForEach-Object {
        $relativePath = ([string]$_).Replace("/", "\")
        $literal = Join-Path $QuestRoot $relativePath
        $text = Get-Content -Raw -LiteralPath $literal
        [regex]::Matches($text, 'path\s*=\s*"(?:(?:\.\.)[\\/])+(rusty-[a-z0-9-]+)[\\/]') |
            ForEach-Object { $_.Groups[1].Value }
    } | Sort-Object -Unique)
    $expected = @("rusty-lattice", "rusty-manifold", "rusty-matter", "rusty-optics")
    if (@(Compare-Object $repositoryIds $expected -SyncWindow 0).Count -ne 0 -or
        $repositoryIds.Count -ne $expected.Count) {
        throw "Fleet Agent key-record release workspace sibling parse closure changed."
    }
}

function Invoke-ProcessEnvironmentRemoval([string] $Name) {
    [Environment]::SetEnvironmentVariable($Name, $nullEnvironmentValue, "Process")
    if ((Test-Path -LiteralPath "Env:$Name") -or
        $null -ne [Environment]::GetEnvironmentVariable($Name, "Process")) {
        throw "Fleet Agent key-record release failed to remove a process environment variable."
    }
}

function Assert-NullEnvironmentRemoval {
    $probeName = "RUSTY_QUEST_RELEASE_NULL_ENVIRONMENT_PROBE"
    $priorExists = Test-Path -LiteralPath "Env:$probeName"
    $priorValue = [Environment]::GetEnvironmentVariable($probeName, "Process")
    try {
        [Environment]::SetEnvironmentVariable($probeName, "present", "Process")
        Invoke-ProcessEnvironmentRemoval -Name $probeName
    }
    finally {
        if ($priorExists) {
            [Environment]::SetEnvironmentVariable($probeName, $priorValue, "Process")
        }
        else {
            Invoke-ProcessEnvironmentRemoval -Name $probeName
        }
    }
}

if ($EnvironmentSelfTest) {
    Assert-NullEnvironmentRemoval
    Write-Output "Rusty Quest Fleet Agent key-record release environment self-test passed"
    return
}

$dirt = @(& git -C $repoRoot status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0 -or $dirt.Count -ne 0) {
    throw "Fleet Agent key-record release requires a clean exact Rusty Quest source tree."
}
$sourceCommit = (& git -C $repoRoot rev-parse HEAD).Trim().ToLowerInvariant()
$sourceTree = (& git -C $repoRoot rev-parse 'HEAD^{tree}').Trim().ToLowerInvariant()
if ($sourceCommit -notmatch '^[0-9a-f]{40}$' -or $sourceTree -notmatch '^[0-9a-f]{40}$') {
    throw "Fleet Agent key-record release could not resolve an exact source commit and tree."
}

$composition = Get-QuestBuildSourceComposition `
    -RepoRoot $repoRoot `
    -PackageName @("rusty-quest-fleet-agent")
$repositories = @($composition.repositories)
if ($repositories.Count -ne 3) {
    throw "Fleet Agent key-record release requires exactly three source repositories."
}
$quest = @($repositories | Where-Object { $_.repository_id -eq "rusty-quest" })
$fleet = @($repositories | Where-Object {
    $_.commit -eq "8181683be4a3abbc5daa0c4497c7aeb9e76316a8"
})
$manifold = @($repositories | Where-Object { $_.repository_id -eq "rusty-manifold" })
if ($quest.Count -ne 1 -or $fleet.Count -ne 1 -or $manifold.Count -ne 1 -or
    $quest[0].role -ne "primary" -or $fleet[0].role -ne "path-dependency" -or
    $manifold[0].role -ne "path-dependency" -or
    $quest[0].commit -ne $sourceCommit -or $quest[0].tree -ne $sourceTree) {
    throw "Fleet Agent key-record release source composition is not the closed owner set."
}
$fleetCommit = ([string]$fleet[0].commit).ToLowerInvariant()
$fleetTree = ([string]$fleet[0].tree).ToLowerInvariant()
$manifoldCommit = ([string]$manifold[0].commit).ToLowerInvariant()
$manifoldTree = ([string]$manifold[0].tree).ToLowerInvariant()
Assert-ExactComposition -Composition $composition -QuestCommit $sourceCommit -QuestTree $sourceTree `
    -FleetCommit $fleetCommit -FleetTree $fleetTree `
    -ManifoldCommit $manifoldCommit -ManifoldTree $manifoldTree
Assert-ClosedWorkspaceSiblingPathSet -QuestRoot $repoRoot

$workspaceParent = Split-Path -Parent $repoRoot
$workspaceParseOnlySourceSpecifications = @(
    [ordered]@{
        repository_id = "rusty-lattice"
        repository_url = "https://github.com/MesmerPrism/rusty-lattice"
    },
    [ordered]@{
        repository_id = "rusty-matter"
        repository_url = "https://github.com/MesmerPrism/rusty-matter"
    },
    [ordered]@{
        repository_id = "rusty-optics"
        repository_url = "https://github.com/MesmerPrism/rusty-optics"
    }
)
$workspaceParseOnlySources = @($workspaceParseOnlySourceSpecifications | ForEach-Object {
    Get-ExactGitSourceRecord `
        -Root (Join-Path $workspaceParent ([string]$_.repository_id)) `
        -RepositoryId ([string]$_.repository_id) `
        -RepositoryUrl ([string]$_.repository_url)
})
$workspaceParseOnlyRepositories = @($workspaceParseOnlySources | ForEach-Object {
    [ordered]@{
        repository_id = [string]$_.repository_id
        role = "workspace-parse-only"
        repository_url = [string]$_.repository_url
        commit = [string]$_.commit
        tree = [string]$_.tree
    }
})

$publicRepositories = @(
    [ordered]@{
        repository_id = "rusty-fleet"
        role = "contract-dependency"
        repository_url = "https://github.com/MesmerPrism/rusty-fleet"
        commit = $fleetCommit
        tree = $fleetTree
    },
    [ordered]@{
        repository_id = "rusty-manifold"
        role = "contract-dependency"
        repository_url = "https://github.com/MesmerPrism/rusty-manifold"
        commit = $manifoldCommit
        tree = $manifoldTree
    },
    [ordered]@{
        repository_id = "rusty-quest"
        role = "release-owner"
        repository_url = "https://github.com/MesmerPrism/rusty-quest"
        commit = $sourceCommit
        tree = $sourceTree
    }
)

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot (
        "target\fleet-agent-key-record-release\$CapsuleVersion\$sourceCommit")
}
$capsuleRoot = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $capsuleRoot) {
    throw "Fleet Agent key-record release output already exists."
}

$sourcePaths = @(
    "Cargo.lock",
    "Cargo.toml",
    "crates/rusty-quest-fleet-agent/Cargo.toml",
    "crates/rusty-quest-fleet-agent/src/bin/fleet-agent-key-record.rs",
    "crates/rusty-quest-fleet-agent/src/lib.rs",
    "tools/Build-FleetAgentKeyRecordRelease.ps1",
    "tools/Test-FleetAgentKeyRecordRelease.ps1",
    "tools/lib/SourceComposition.psm1"
)
$materializationBase = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) "rusty-fkr"))
$cleanRoot = Join-Path $materializationBase "clean-v1"
if ($cleanRoot.Length -gt 96) {
    throw "Fleet Agent key-record release clean-room root is too long for a portable Windows checkout."
}
if (Test-Path -LiteralPath $cleanRoot) {
    throw "Fleet Agent key-record release clean-room path already exists."
}
$cleanQuest = Join-Path $cleanRoot "workspace\rusty-quest"
$cleanFleet = Join-Path $cleanRoot "workspace\rusty-fleet"
$cleanManifold = Join-Path $cleanRoot "workspace\rusty-manifold"
$cargoHome = Join-Path $cleanRoot "cargo-home"
$buildTarget = Join-Path $cleanRoot "cargo-target"
$cargoConfigPath = Join-Path $cargoHome "config.toml"
$environmentNames = @(@(
    "CARGO_HOME", "CARGO_TARGET_DIR", "CARGO_BUILD_TARGET", "CARGO_ENCODED_RUSTFLAGS",
    "CARGO_NET_GIT_FETCH_WITH_CLI", "RUSTFLAGS", "RUSTC", "RUSTC_WRAPPER",
    "RUSTC_WORKSPACE_WRAPPER", "GIT_CONFIG_COUNT", "GIT_CONFIG_KEY_0",
    "GIT_CONFIG_VALUE_0", "GIT_CONFIG_KEY_1", "GIT_CONFIG_VALUE_1") +
    @(Get-ChildItem Env: | Where-Object {
        $_.Name -match '^(?:RUSTFLAGS|RUSTDOCFLAGS|RUSTC|RUSTC_WRAPPER|RUSTC_WORKSPACE_WRAPPER|CARGO_HOME|CARGO_TARGET_DIR|CARGO_BUILD_TARGET|CARGO_ENCODED_RUSTFLAGS|CARGO_ENCODED_RUSTDOCFLAGS|CARGO_PROFILE_.+|CARGO_TARGET_.+_(?:LINKER|RUSTFLAGS|RUNNER))$'
    } | ForEach-Object { $_.Name }) | Sort-Object -Unique)
$savedEnvironment = @{}
foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

$sourceFiles = @()
$rustc = @()
$cargo = ""
$buildCompositionFingerprint = ""
$cargoConfigSha256 = ""
try {
    [IO.Directory]::CreateDirectory($cleanRoot) | Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $cleanRoot "workspace")) | Out-Null
    Initialize-ExactGitMaterialization -SourceRoot ([string]$quest[0].repository) `
        -Destination $cleanQuest -ExpectedCommit $sourceCommit -ExpectedTree $sourceTree `
        -Label "Rusty Quest"
    Initialize-ExactGitMaterialization -SourceRoot ([string]$fleet[0].repository) `
        -Destination $cleanFleet -ExpectedCommit $fleetCommit -ExpectedTree $fleetTree `
        -Label "Rusty Fleet"
    Initialize-ExactGitMaterialization -SourceRoot ([string]$manifold[0].repository) `
        -Destination $cleanManifold -ExpectedCommit $manifoldCommit -ExpectedTree $manifoldTree `
        -Label "Rusty Manifold"
    foreach ($source in $workspaceParseOnlySources) {
        Initialize-ExactGitMaterialization -SourceRoot ([string]$source.repository) `
            -Destination (Join-Path $cleanRoot "workspace\$([string]$source.repository_id)") `
            -ExpectedCommit ([string]$source.commit) -ExpectedTree ([string]$source.tree) `
            -Label ([string]$source.repository_id)
    }
    Assert-ClosedWorkspaceSiblingPathSet -QuestRoot $cleanQuest

    [IO.Directory]::CreateDirectory($cargoHome) | Out-Null
    Write-CanonicalText $cargoConfigPath @"
[net]
git-fetch-with-cli = true
"@
    $cargoConfigSha256 = Get-Sha256 $cargoConfigPath

    $localFleetUrl = [Uri]::new($cleanFleet + [IO.Path]::DirectorySeparatorChar).AbsoluteUri
    foreach ($name in $environmentNames) {
        Invoke-ProcessEnvironmentRemoval -Name $name
    }
    [Environment]::SetEnvironmentVariable("CARGO_HOME", $cargoHome, "Process")
    [Environment]::SetEnvironmentVariable("CARGO_TARGET_DIR", $buildTarget, "Process")
    Invoke-ProcessEnvironmentRemoval -Name "CARGO_BUILD_TARGET"
    [Environment]::SetEnvironmentVariable("CARGO_NET_GIT_FETCH_WITH_CLI", "true", "Process")
    Invoke-ProcessEnvironmentRemoval -Name "RUSTFLAGS"
    Invoke-ProcessEnvironmentRemoval -Name "RUSTC"
    Invoke-ProcessEnvironmentRemoval -Name "RUSTC_WRAPPER"
    Invoke-ProcessEnvironmentRemoval -Name "RUSTC_WORKSPACE_WRAPPER"
    [Environment]::SetEnvironmentVariable("GIT_CONFIG_COUNT", "2", "Process")
    [Environment]::SetEnvironmentVariable("GIT_CONFIG_KEY_0", "url.$localFleetUrl.insteadOf", "Process")
    [Environment]::SetEnvironmentVariable("GIT_CONFIG_VALUE_0", "https://github.com/MesmerPrism/rusty-fleet", "Process")
    [Environment]::SetEnvironmentVariable("GIT_CONFIG_KEY_1", "protocol.file.allow", "Process")
    [Environment]::SetEnvironmentVariable("GIT_CONFIG_VALUE_1", "always", "Process")

    $rustcCommand = (Get-Command rustc -ErrorAction Stop).Source
    $cargoCommand = (Get-Command cargo -ErrorAction Stop).Source
    $rustSysroot = (& $rustcCommand --print sysroot).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($rustSysroot)) {
        throw "rustc sysroot could not be resolved."
    }
    $remapArguments = [Collections.Generic.List[string]]::new()
    $remapArguments.Add("--remap-path-prefix=$cleanRoot=/rusty-build")
    $remapArguments.Add("--remap-path-prefix=$rustSysroot=/rusty-toolchain")
    if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        $remapArguments.Add("--remap-path-prefix=$env:USERPROFILE=/user-profile")
    }
    $remapArguments.Add("-C")
    $remapArguments.Add("strip=symbols")
    [Environment]::SetEnvironmentVariable(
        "CARGO_ENCODED_RUSTFLAGS", ($remapArguments -join [char]0x1f), "Process")

    $buildComposition = Get-QuestBuildSourceComposition `
        -RepoRoot $cleanQuest `
        -PackageName @("rusty-quest-fleet-agent")
    Assert-ExactComposition -Composition $buildComposition `
        -QuestCommit $sourceCommit -QuestTree $sourceTree `
        -FleetCommit $fleetCommit -FleetTree $fleetTree `
        -ManifoldCommit $manifoldCommit -ManifoldTree $manifoldTree
    $buildCompositionFingerprint = [string]$buildComposition.fingerprint

    & $cargoCommand build --locked --release --target $targetTriple `
        --manifest-path (Join-Path $cleanQuest "Cargo.toml") `
        -p rusty-quest-fleet-agent `
        --bin fleet-agent-key-record
    if ($LASTEXITCODE -ne 0) {
        throw "Fleet Agent key-record release build failed."
    }
    $builtExecutable = Join-Path $buildTarget "$targetTriple\release\fleet-agent-key-record.exe"
    if (-not (Test-Path -LiteralPath $builtExecutable -PathType Leaf)) {
        throw "Fleet Agent key-record release executable is missing."
    }

    Assert-ExactGitMaterialization -Root $cleanQuest -ExpectedCommit $sourceCommit `
        -ExpectedTree $sourceTree -Label "Rusty Quest"
    Assert-ExactGitMaterialization -Root $cleanFleet -ExpectedCommit $fleetCommit `
        -ExpectedTree $fleetTree -Label "Rusty Fleet"
    Assert-ExactGitMaterialization -Root $cleanManifold -ExpectedCommit $manifoldCommit `
        -ExpectedTree $manifoldTree -Label "Rusty Manifold"
    foreach ($source in $workspaceParseOnlySources) {
        Assert-ExactGitMaterialization `
            -Root (Join-Path $cleanRoot "workspace\$([string]$source.repository_id)") `
            -ExpectedCommit ([string]$source.commit) -ExpectedTree ([string]$source.tree) `
            -Label ([string]$source.repository_id)
    }
    Assert-ClosedWorkspaceSiblingPathSet -QuestRoot $cleanQuest
    $postBuildComposition = Get-QuestBuildSourceComposition `
        -RepoRoot $cleanQuest `
        -PackageName @("rusty-quest-fleet-agent")
    Assert-ExactComposition -Composition $postBuildComposition `
        -QuestCommit $sourceCommit -QuestTree $sourceTree `
        -FleetCommit $fleetCommit -FleetTree $fleetTree `
        -ManifoldCommit $manifoldCommit -ManifoldTree $manifoldTree
    if ([string]$postBuildComposition.fingerprint -cne $buildCompositionFingerprint) {
        throw "Fleet Agent key-record release source composition drifted during the build."
    }

    $sourceFiles = @($sourcePaths | ForEach-Object {
        $literal = Join-Path $cleanQuest $_.Replace("/", "\")
        if (-not (Test-Path -LiteralPath $literal -PathType Leaf)) {
            throw "Fleet Agent key-record release source file is missing: $_"
        }
        [ordered]@{ path = $_; sha256 = Get-Sha256 $literal }
    })
    $rustc = @(& $rustcCommand -vV)
    if ($LASTEXITCODE -ne 0) { throw "rustc identity could not be resolved." }
    $cargo = (& $cargoCommand -V).Trim()
    if ($LASTEXITCODE -ne 0) { throw "cargo identity could not be resolved." }

    [IO.Directory]::CreateDirectory($capsuleRoot) | Out-Null
    $artifactPath = Join-Path $capsuleRoot "fleet-agent-key-record.exe"
    Copy-Item -LiteralPath $builtExecutable -Destination $artifactPath
    Copy-Item -LiteralPath (Join-Path $cleanQuest "LICENSE") `
        -Destination (Join-Path $capsuleRoot "LICENSE")
}
finally {
    foreach ($name in $environmentNames) {
        $savedValue = $savedEnvironment[$name]
        if ($null -eq $savedValue) {
            Invoke-ProcessEnvironmentRemoval -Name $name
        }
        else {
            [Environment]::SetEnvironmentVariable($name, $savedValue, "Process")
        }
    }
    if (Test-Path -LiteralPath $cleanRoot) {
        $resolvedCleanRoot = [IO.Path]::GetFullPath($cleanRoot)
        $expectedPrefix = $materializationBase.TrimEnd("\", "/") + [IO.Path]::DirectorySeparatorChar
        if (-not $resolvedCleanRoot.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Fleet Agent key-record release clean-room cleanup target escaped its owner root."
        }
        Remove-Item -LiteralPath $resolvedCleanRoot -Recurse -Force
    }
}

$artifactPath = Join-Path $capsuleRoot "fleet-agent-key-record.exe"
$provenance = [ordered]@{
    schema = "rusty.quest.fleet_agent_key_record_release_provenance.v1"
    capsule_version = $CapsuleVersion
    source = [ordered]@{
        repository_url = "https://github.com/MesmerPrism/rusty-quest"
        commit = $sourceCommit
        tree = $sourceTree
        package = "rusty-quest-fleet-agent"
        composition_fingerprint = $buildCompositionFingerprint
        repositories = $publicRepositories
        workspace_parse_only_repositories = $workspaceParseOnlyRepositories
        files = $sourceFiles
    }
    build = [ordered]@{
        target = $targetTriple
        profile = "release"
        rustc = ($rustc -join "`n")
        cargo = $cargo
        locked_dependencies = $true
        isolated_git_materializations = $true
        post_build_identity_verified = $true
        path_remap_root = "/rusty-build"
        symbols_stripped = $true
        cargo_config_sha256 = $cargoConfigSha256
    }
    claims = [ordered]@{
        owner = "rusty-quest"
        helper_only = $true
        runtime_activation = "explicit_fleet_onboard_invocation"
        enrollment_authority = $false
        device_authority = $false
        private_seed_included = $false
        profile_included = $false
        hub_configuration_included = $false
    }
}
$provenancePath = Join-Path $capsuleRoot "provenance.json"
Write-CanonicalJson $provenancePath $provenance

$notice = @"
# Rusty Quest Fleet Agent key-record helper source notice

This capsule contains only the public key-record derivation helper. It does not
contain a private seed, a Fleet Agent profile, Hub configuration, enrollment,
or device authority.

Source: https://github.com/MesmerPrism/rusty-quest
Commit: $sourceCommit
Tree: $sourceTree
License: AGPL-3.0-or-later (see LICENSE)
"@
$noticePath = Join-Path $capsuleRoot "SOURCE-NOTICE.md"
Write-CanonicalText $noticePath $notice

$payload = @(
    [ordered]@{ path = "fleet-agent-key-record.exe"; sha256 = Get-Sha256 $artifactPath; size_bytes = [long](Get-Item -LiteralPath $artifactPath).Length },
    [ordered]@{ path = "provenance.json"; sha256 = Get-Sha256 $provenancePath; size_bytes = [long](Get-Item -LiteralPath $provenancePath).Length },
    [ordered]@{ path = "LICENSE"; sha256 = Get-Sha256 (Join-Path $capsuleRoot "LICENSE"); size_bytes = [long](Get-Item -LiteralPath (Join-Path $capsuleRoot "LICENSE")).Length },
    [ordered]@{ path = "SOURCE-NOTICE.md"; sha256 = Get-Sha256 $noticePath; size_bytes = [long](Get-Item -LiteralPath $noticePath).Length }
)
$manifest = [ordered]@{
    schema = "rusty.quest.fleet_agent_key_record_release_capsule.v1"
    capsule_version = $CapsuleVersion
    tool_contract = [ordered]@{
        schema = "rusty.quest.fleet_agent_key_record_tool_contract.v1"
        executable = "fleet-agent-key-record.exe"
        argument_contract = "--key-id <dotted-id> --seed-file <private-seed-file>"
        output_schema = "rusty.quest.fleet_agent_key_record.v1"
    }
    source = [ordered]@{
        repository_url = "https://github.com/MesmerPrism/rusty-quest"
        commit = $sourceCommit
        tree = $sourceTree
        provenance_path = "provenance.json"
        provenance_sha256 = Get-Sha256 $provenancePath
    }
    artifact = [ordered]@{
        path = "fleet-agent-key-record.exe"
        sha256 = Get-Sha256 $artifactPath
        size_bytes = [long](Get-Item -LiteralPath $artifactPath).Length
        target = $targetTriple
        profile = "release"
    }
    distribution = [ordered]@{
        portable = $true
        supported = $true
        inert_until_invoked = $true
        install_contract = "copy_capsule_byte_for_byte"
        private_material_included = $false
        live_onboarding_claim = $false
    }
    payload = $payload
}
$manifestPath = Join-Path $capsuleRoot "release-manifest.json"
Write-CanonicalJson $manifestPath $manifest

$checksumRows = @(
    "$(Get-Sha256 $artifactPath)  fleet-agent-key-record.exe",
    "$(Get-Sha256 $provenancePath)  provenance.json",
    "$(Get-Sha256 (Join-Path $capsuleRoot 'LICENSE'))  LICENSE",
    "$(Get-Sha256 $noticePath)  SOURCE-NOTICE.md",
    "$(Get-Sha256 $manifestPath)  release-manifest.json"
)
Write-CanonicalText (Join-Path $capsuleRoot "checksums.sha256") ($checksumRows -join "`n")

& pwsh -NoProfile -ExecutionPolicy Bypass -File `
    (Join-Path $PSScriptRoot "Test-FleetAgentKeyRecordRelease.ps1") `
    -CapsuleRoot $capsuleRoot `
    -ExpectedCapsuleVersion $CapsuleVersion `
    -ExpectedSourceCommit $sourceCommit `
    -ExpectedSourceTree $sourceTree
if ($LASTEXITCODE -ne 0) {
    throw "Fleet Agent key-record release capsule validation failed."
}

[pscustomobject][ordered]@{
    schema = "rusty.quest.fleet_agent_key_record_release_build.v1"
    capsule_root = $capsuleRoot
    capsule_version = $CapsuleVersion
    source_commit = $sourceCommit
    source_tree = $sourceTree
    manifest_path = $manifestPath
    manifest_sha256 = Get-Sha256 $manifestPath
    executable_sha256 = Get-Sha256 $artifactPath
    executable_size_bytes = [long](Get-Item -LiteralPath $artifactPath).Length
} | ConvertTo-Json -Depth 6
