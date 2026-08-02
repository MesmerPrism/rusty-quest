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
    @($matrix.cases).Count -ne 9) {
    throw "Fleet Agent key-record release damage matrix is incomplete."
}

function Write-Json([string] $LiteralPath, $Value) {
    $json = ($Value | ConvertTo-Json -Depth 30) -replace "`r`n", "`n"
    [IO.File]::WriteAllText(
        $LiteralPath,
        $json.TrimEnd("`r", "`n") + "`n",
        [Text.UTF8Encoding]::new($false))
}

function Invoke-MustReject([string] $Name, [scriptblock] $Mutate) {
    $root = Join-Path ([IO.Path]::GetTempPath()) (
        "rusty-quest-key-record-release-selftest-" + [guid]::NewGuid().ToString("N"))
    try {
        Copy-Item -LiteralPath $source -Destination $root -Recurse
        & $Mutate $root
        & pwsh -NoProfile -ExecutionPolicy Bypass -File $validator -CapsuleRoot $root *> $null
        if ($LASTEXITCODE -eq 0) { throw "damage case unexpectedly passed: $Name" }
    }
    finally {
        if (Test-Path -LiteralPath $root) {
            Remove-Item -LiteralPath $root -Recurse -Force
        }
    }
}

& pwsh -NoProfile -ExecutionPolicy Bypass -File $validator -CapsuleRoot $source *> $null
if ($LASTEXITCODE -ne 0) { throw "valid release capsule did not pass self-test preflight." }

Invoke-MustReject "artifact-substitution" {
    param($root)
    [IO.File]::AppendAllText((Join-Path $root "fleet-agent-key-record.exe"), "damage")
}
Invoke-MustReject "source-commit-drift" {
    param($root)
    $path = Join-Path $root "release-manifest.json"
    $value = Get-Content -Raw $path | ConvertFrom-Json
    $value.source.commit = "0" * 40
    Write-Json $path $value
}
Invoke-MustReject "provenance-substitution" {
    param($root)
    [IO.File]::AppendAllText((Join-Path $root "provenance.json"), " ")
}
Invoke-MustReject "secret-leakage" {
    param($root)
    $path = Join-Path $root "SOURCE-NOTICE.md"
    [IO.File]::AppendAllText($path, "`nsigning-seed.bin`n")
}
Invoke-MustReject "extra-repository" {
    param($root)
    $path = Join-Path $root "provenance.json"
    $value = Get-Content -Raw $path | ConvertFrom-Json
    $value.source.repositories = @($value.source.repositories) + $value.source.repositories[0]
    Write-Json $path $value
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
    $path = Join-Path $root "release-manifest.json"
    $value = Get-Content -Raw $path | ConvertFrom-Json
    $value.capsule_version = "0.0.0"
    Write-Json $path $value
}
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

Write-Output "Rusty Quest Fleet Agent key-record release self-test passed"
