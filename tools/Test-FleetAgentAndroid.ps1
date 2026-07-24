[CmdletBinding()]
param(
    [string]$RepoRoot = "",

    [ValidateSet("Host")]
    [string]$Tier = "Host",

    [switch]$Build
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSEdition -ne "Core" -or
    $PSVersionTable.PSVersion -lt [version]"7.6") {
    throw "Fleet Agent validation requires PowerShell 7.6 Core or newer."
}
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

& cargo test --manifest-path (Join-Path $RepoRoot "Cargo.toml") `
    -p rusty-quest-fleet-agent `
    -p rusty-quest-fleet-agent-android-native
if ($LASTEXITCODE -ne 0) {
    throw "Rusty Quest Fleet Agent contract tests failed."
}

& pwsh -NoProfile -ExecutionPolicy Bypass -File `
    (Join-Path $RepoRoot "tools\checks\Test-FleetAgentAndroidStatic.ps1") `
    -RepoRoot $RepoRoot
if ($LASTEXITCODE -ne 0) {
    throw "Rusty Quest Fleet Agent static checks failed."
}

if ($Build) {
    & pwsh -NoProfile -ExecutionPolicy Bypass -File `
        (Join-Path $RepoRoot "tools\Build-FleetAgentAndroid.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "Rusty Quest Fleet Agent Android build failed."
    }
}

Write-Output "Rusty Quest Fleet Agent Android $Tier validation passed"
