param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

& cargo test --manifest-path (Join-Path $RepoRoot "Cargo.toml") -p rusty-quest-fleet-agent
if ($LASTEXITCODE -ne 0) {
    throw "Rusty Quest Fleet Agent contract tests failed."
}

& pwsh -NoProfile -ExecutionPolicy Bypass -File `
    (Join-Path $RepoRoot "tools\checks\Test-FleetAgentAndroidStatic.ps1") `
    -RepoRoot $RepoRoot
if ($LASTEXITCODE -ne 0) {
    throw "Rusty Quest Fleet Agent static checks failed."
}

Write-Output "Rusty Quest Fleet Agent source validation passed"
