[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "lib\SourceComposition.psm1") -Force

if ($PSVersionTable.PSEdition -ne "Core" -or
    $PSVersionTable.PSVersion -lt [version]"7.6") {
    throw "Fleet Agent key-record builds require PowerShell 7.6 Core or newer."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$composition = Get-QuestBuildSourceComposition `
    -RepoRoot $repoRoot `
    -PackageName @("rusty-quest-fleet-agent")
$primary = @($composition.repositories |
    Where-Object { $_.repository_id -eq "rusty-quest" })
if ($primary.Count -ne 1) {
    throw "Fleet Agent key-record build requires one Rusty Quest primary source."
}
$outputDirectory = Join-Path $repoRoot (
    "target\fleet-agent-key-record\" +
    $composition.fingerprint.Substring(0, 16) + "\" +
    $primary[0].commit.Substring(0, 12))
$executable = Join-Path $outputDirectory "fleet-agent-key-record.exe"
$manifestPath = Join-Path $outputDirectory "key-record-manifest.json"

if (Test-Path -LiteralPath $outputDirectory) {
    if ((Test-Path -LiteralPath $executable -PathType Leaf) -and
        (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        $existing = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
        $existingHash = (Get-FileHash -LiteralPath $executable -Algorithm SHA256).
            Hash.ToLowerInvariant()
        if ([string]$existing.schema -eq "rusty.quest.fleet_agent_key_record_tool.v1" -and
            [string]$existing.source_commit -eq [string]$primary[0].commit -and
            [string]$existing.source_tree -eq [string]$primary[0].tree -and
            [string]$existing.source_composition_fingerprint -eq
                [string]$composition.fingerprint -and
            [string]$existing.executable_sha256 -eq $existingHash) {
            Write-Output $manifestPath
            return
        }
    }
    throw "Fleet Agent key-record content address exists without a reusable exact manifest."
}

& cargo build --locked `
    --manifest-path (Join-Path $repoRoot "Cargo.toml") `
    -p rusty-quest-fleet-agent `
    --bin fleet-agent-key-record
if ($LASTEXITCODE -ne 0) {
    throw "Fleet Agent key-record host build failed."
}
$builtExecutable = Join-Path $repoRoot "target\debug\fleet-agent-key-record.exe"
if (-not (Test-Path -LiteralPath $builtExecutable -PathType Leaf)) {
    throw "Fleet Agent key-record host executable is missing."
}

New-Item -ItemType Directory -Path $outputDirectory | Out-Null
Copy-Item -LiteralPath $builtExecutable -Destination $executable
$manifest = [ordered]@{
    schema = "rusty.quest.fleet_agent_key_record_tool.v1"
    source_commit = [string]$primary[0].commit
    source_tree = [string]$primary[0].tree
    source_composition_fingerprint = [string]$composition.fingerprint
    source_packages = @($composition.packages)
    source_repositories = @($composition.repositories)
    executable_path = $executable
    executable_sha256 = (Get-FileHash -LiteralPath $executable -Algorithm SHA256).
        Hash.ToLowerInvariant()
}
$manifest |
    ConvertTo-Json -Depth 12 |
    Set-Content -Encoding UTF8 -LiteralPath $manifestPath
Write-Output $manifestPath
