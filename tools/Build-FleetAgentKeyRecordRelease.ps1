# Copyright (C) 2026 Rusty Quest contributors
# SPDX-License-Identifier: AGPL-3.0-or-later

[CmdletBinding()]
param(
    [ValidatePattern("^[0-9]+\.[0-9]+\.[0-9]+$")]
    [string] $CapsuleVersion = "1.0.0",

    [string] $OutputDirectory = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSEdition -ne "Core" -or
    $PSVersionTable.PSVersion -lt [version]"7.6") {
    throw "Fleet Agent key-record release builds require PowerShell 7.6 Core or newer."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Import-Module (Join-Path $PSScriptRoot "lib\SourceComposition.psm1") -Force

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

$publicRepositories = @(
    [ordered]@{
        repository_id = "rusty-fleet"
        role = "contract-dependency"
        repository_url = "https://github.com/MesmerPrism/rusty-fleet"
        commit = [string]$fleet[0].commit
        tree = [string]$fleet[0].tree
    },
    [ordered]@{
        repository_id = "rusty-manifold"
        role = "contract-dependency"
        repository_url = "https://github.com/MesmerPrism/rusty-manifold"
        commit = [string]$manifold[0].commit
        tree = [string]$manifold[0].tree
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

& cargo build --locked --release `
    --manifest-path (Join-Path $repoRoot "Cargo.toml") `
    -p rusty-quest-fleet-agent `
    --bin fleet-agent-key-record
if ($LASTEXITCODE -ne 0) {
    throw "Fleet Agent key-record release build failed."
}
$builtExecutable = Join-Path $repoRoot "target\release\fleet-agent-key-record.exe"
if (-not (Test-Path -LiteralPath $builtExecutable -PathType Leaf)) {
    throw "Fleet Agent key-record release executable is missing."
}

$sourcePaths = @(
    "Cargo.lock",
    "Cargo.toml",
    "crates/rusty-quest-fleet-agent/Cargo.toml",
    "crates/rusty-quest-fleet-agent/src/bin/fleet-agent-key-record.rs",
    "crates/rusty-quest-fleet-agent/src/lib.rs",
    "tools/Build-FleetAgentKeyRecordRelease.ps1",
    "tools/Test-FleetAgentKeyRecordRelease.ps1"
)
$sourceFiles = @($sourcePaths | ForEach-Object {
    $literal = Join-Path $repoRoot $_.Replace("/", "\")
    if (-not (Test-Path -LiteralPath $literal -PathType Leaf)) {
        throw "Fleet Agent key-record release source file is missing: $_"
    }
    [ordered]@{ path = $_; sha256 = Get-Sha256 $literal }
})

[IO.Directory]::CreateDirectory($capsuleRoot) | Out-Null
$artifactPath = Join-Path $capsuleRoot "fleet-agent-key-record.exe"
Copy-Item -LiteralPath $builtExecutable -Destination $artifactPath
Copy-Item -LiteralPath (Join-Path $repoRoot "LICENSE") -Destination (Join-Path $capsuleRoot "LICENSE")

$rustc = @(& rustc -vV)
if ($LASTEXITCODE -ne 0) { throw "rustc identity could not be resolved." }
$cargo = (& cargo -V).Trim()
if ($LASTEXITCODE -ne 0) { throw "cargo identity could not be resolved." }
$provenance = [ordered]@{
    schema = "rusty.quest.fleet_agent_key_record_release_provenance.v1"
    capsule_version = $CapsuleVersion
    source = [ordered]@{
        repository_url = "https://github.com/MesmerPrism/rusty-quest"
        commit = $sourceCommit
        tree = $sourceTree
        package = "rusty-quest-fleet-agent"
        composition_fingerprint = [string]$composition.fingerprint
        repositories = $publicRepositories
        files = $sourceFiles
    }
    build = [ordered]@{
        target = "x86_64-pc-windows-msvc"
        profile = "release"
        rustc = ($rustc -join "`n")
        cargo = $cargo
        locked_dependencies = $true
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
        target = "x86_64-pc-windows-msvc"
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
