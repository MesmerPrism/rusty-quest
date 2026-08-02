# Copyright (C) 2026 Rusty Quest contributors
# SPDX-License-Identifier: AGPL-3.0-or-later

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $CapsuleRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$source = (Resolve-Path -LiteralPath $CapsuleRoot).Path
$validator = Join-Path $PSScriptRoot "Test-FleetAgentKeyRecordRelease.ps1"
$matrixPath = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")).Path `
    "fixtures\fleet-agent\key-record-release-scenarios.damaged.json"
$matrix = Get-Content -Raw -LiteralPath $matrixPath | ConvertFrom-Json
if ($matrix.schema -cne "rusty.quest.fleet_agent_key_record_release_damage_matrix.v1" -or
    @($matrix.cases).Count -ne 12) {
    throw "Fleet Agent key-record release damage matrix is incomplete."
}
$sourceManifest = Get-Content -Raw -LiteralPath (Join-Path $source "release-manifest.json") |
    ConvertFrom-Json

function Write-Json([string] $LiteralPath, $Value) {
    $json = ($Value | ConvertTo-Json -Depth 30) -replace "`r`n", "`n"
    [IO.File]::WriteAllText(
        $LiteralPath,
        $json.TrimEnd("`r", "`n") + "`n",
        [Text.UTF8Encoding]::new($false))
}

function Get-Sha256([string] $LiteralPath) {
    return (Get-FileHash -LiteralPath $LiteralPath -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Sync-ChecksumProjection([string] $Root) {
    $rows = @(
        "$(Get-Sha256 (Join-Path $Root 'fleet-agent-key-record.exe'))  fleet-agent-key-record.exe",
        "$(Get-Sha256 (Join-Path $Root 'provenance.json'))  provenance.json",
        "$(Get-Sha256 (Join-Path $Root 'LICENSE'))  LICENSE",
        "$(Get-Sha256 (Join-Path $Root 'SOURCE-NOTICE.md'))  SOURCE-NOTICE.md",
        "$(Get-Sha256 (Join-Path $Root 'release-manifest.json'))  release-manifest.json"
    )
    [IO.File]::WriteAllText(
        (Join-Path $Root "checksums.sha256"),
        ($rows -join "`n") + "`n",
        [Text.UTF8Encoding]::new($false))
}

function Sync-PayloadBinding([string] $Root, [string] $PayloadPath) {
    $manifestPath = Join-Path $Root "release-manifest.json"
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $entry = @($manifest.payload | Where-Object { $_.path -ceq $PayloadPath })
    if ($entry.Count -ne 1) { throw "self-test payload entry is not unique: $PayloadPath" }
    $literal = Join-Path $Root $PayloadPath
    $entry[0].sha256 = Get-Sha256 $literal
    $entry[0].size_bytes = [long](Get-Item -LiteralPath $literal).Length
    if ($PayloadPath -ceq "fleet-agent-key-record.exe") {
        $manifest.artifact.sha256 = $entry[0].sha256
        $manifest.artifact.size_bytes = $entry[0].size_bytes
    }
    Write-Json $manifestPath $manifest
    Sync-ChecksumProjection $Root
}

function Sync-ProvenanceBinding([string] $Root) {
    $manifestPath = Join-Path $Root "release-manifest.json"
    $provenancePath = Join-Path $Root "provenance.json"
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $entry = @($manifest.payload | Where-Object { $_.path -ceq "provenance.json" })
    if ($entry.Count -ne 1) { throw "self-test provenance payload entry is not unique." }
    $entry[0].sha256 = Get-Sha256 $provenancePath
    $entry[0].size_bytes = [long](Get-Item -LiteralPath $provenancePath).Length
    $manifest.source.provenance_sha256 = $entry[0].sha256
    Write-Json $manifestPath $manifest
    Sync-ChecksumProjection $Root
}

function Invoke-MustReject(
    [string] $Name,
    [scriptblock] $Mutate,
    [switch] $WithoutExecutablePin,
    [switch] $WithoutVersionPin
) {
    $root = Join-Path ([IO.Path]::GetTempPath()) (
        "rusty-quest-key-record-release-selftest-" + [guid]::NewGuid().ToString("N"))
    try {
        Copy-Item -LiteralPath $source -Destination $root -Recurse
        & $Mutate $root
        $validatorArguments = @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $validator,
            "-CapsuleRoot", $root,
            "-ExpectedSourceCommit", [string]$sourceManifest.source.commit,
            "-ExpectedSourceTree", [string]$sourceManifest.source.tree)
        if (-not $WithoutVersionPin) {
            $validatorArguments += @(
                "-ExpectedCapsuleVersion", [string]$sourceManifest.capsule_version)
        }
        if (-not $WithoutExecutablePin) {
            $validatorArguments += @(
                "-ExpectedExecutableSha256", [string]$sourceManifest.artifact.sha256)
        }
        & pwsh @validatorArguments *> $null
        if ($LASTEXITCODE -eq 0) { throw "damage case unexpectedly passed: $Name" }
    }
    finally {
        if (Test-Path -LiteralPath $root) {
            Remove-Item -LiteralPath $root -Recurse -Force
        }
    }
}

& pwsh -NoProfile -ExecutionPolicy Bypass -File $validator -CapsuleRoot $source `
    -ExpectedCapsuleVersion ([string]$sourceManifest.capsule_version) `
    -ExpectedManifestSha256 (Get-Sha256 (Join-Path $source "release-manifest.json")) `
    -ExpectedExecutableSha256 ([string]$sourceManifest.artifact.sha256) `
    -ExpectedSourceCommit ([string]$sourceManifest.source.commit) `
    -ExpectedSourceTree ([string]$sourceManifest.source.tree) *> $null
if ($LASTEXITCODE -ne 0) { throw "valid release capsule did not pass self-test preflight." }

Invoke-MustReject "artifact-substitution" {
    param($root)
    [IO.File]::AppendAllText((Join-Path $root "fleet-agent-key-record.exe"), "damage")
}
Invoke-MustReject "source-commit-drift" {
    param($root)
    $provenancePath = Join-Path $root "provenance.json"
    $provenance = Get-Content -Raw $provenancePath | ConvertFrom-Json
    $provenance.source.commit = "0" * 40
    $owner = @($provenance.source.repositories | Where-Object { $_.repository_id -ceq "rusty-quest" })
    $owner[0].commit = "0" * 40
    Write-Json $provenancePath $provenance
    Sync-ProvenanceBinding $root
    $manifestPath = Join-Path $root "release-manifest.json"
    $manifest = Get-Content -Raw $manifestPath | ConvertFrom-Json
    $manifest.source.commit = "0" * 40
    Write-Json $manifestPath $manifest
    Sync-ChecksumProjection $root
}
Invoke-MustReject "provenance-substitution" {
    param($root)
    [IO.File]::AppendAllText((Join-Path $root "provenance.json"), " ")
}
Invoke-MustReject "secret-leakage" {
    param($root)
    $path = Join-Path $root "SOURCE-NOTICE.md"
    [IO.File]::AppendAllText($path, "`nsigning-seed.bin`n")
    Sync-PayloadBinding $root "SOURCE-NOTICE.md"
}
Invoke-MustReject "extra-repository" {
    param($root)
    $path = Join-Path $root "provenance.json"
    $value = Get-Content -Raw $path | ConvertFrom-Json
    $value.source.repositories = @($value.source.repositories) + $value.source.repositories[0]
    Write-Json $path $value
    Sync-ProvenanceBinding $root
}
Invoke-MustReject "parse-only-role-escalation" {
    param($root)
    $path = Join-Path $root "provenance.json"
    $value = Get-Content -Raw $path | ConvertFrom-Json
    $value.source.workspace_parse_only_repositories[0].role = "contract-dependency"
    Write-Json $path $value
    Sync-ProvenanceBinding $root
}
Invoke-MustReject "extra-field" {
    param($root)
    $path = Join-Path $root "release-manifest.json"
    $value = Get-Content -Raw $path | ConvertFrom-Json
    $value | Add-Member -NotePropertyName private_path -NotePropertyValue "forbidden"
    Write-Json $path $value
}
Invoke-MustReject "unsupported-version" {
    param($root)
    $provenancePath = Join-Path $root "provenance.json"
    $provenance = Get-Content -Raw $provenancePath | ConvertFrom-Json
    $provenance.capsule_version = "0.0.0"
    Write-Json $provenancePath $provenance
    Sync-ProvenanceBinding $root
    $manifestPath = Join-Path $root "release-manifest.json"
    $manifest = Get-Content -Raw $manifestPath | ConvertFrom-Json
    $manifest.capsule_version = "0.0.0"
    Write-Json $manifestPath $manifest
    Sync-ChecksumProjection $root
} -WithoutVersionPin
Invoke-MustReject "unsupported-target" {
    param($root)
    $path = Join-Path $root "release-manifest.json"
    $value = Get-Content -Raw $path | ConvertFrom-Json
    $value.artifact.target = "unsupported-target"
    Write-Json $path $value
}
Invoke-MustReject "extra-package-file" {
    param($root)
    [IO.File]::WriteAllText((Join-Path $root "unexpected.bin"), "damage")
}
Invoke-MustReject "binary-ascii-path-leakage" {
    param($root)
    [IO.File]::AppendAllText(
        (Join-Path $root "fleet-agent-key-record.exe"),
        "Z:\synthetic\artifact.obj",
        [Text.Encoding]::ASCII)
    Sync-PayloadBinding $root "fleet-agent-key-record.exe"
} -WithoutExecutablePin
Invoke-MustReject "binary-utf16-path-leakage" {
    param($root)
    $path = Join-Path $root "fleet-agent-key-record.exe"
    $stream = [IO.File]::Open($path, [IO.FileMode]::Append, [IO.FileAccess]::Write)
    try {
        $bytes = [Text.Encoding]::Unicode.GetBytes("Y:\synthetic\artifact.pdb")
        $stream.Write($bytes, 0, $bytes.Length)
    }
    finally {
        $stream.Dispose()
    }
    Sync-PayloadBinding $root "fleet-agent-key-record.exe"
} -WithoutExecutablePin

Write-Output "Rusty Quest Fleet Agent key-record release self-test passed"
