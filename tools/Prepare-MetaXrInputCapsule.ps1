#Requires -Version 7.6
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SelectionArgumentsPath,
    [Parameter(Mandatory)][string]$ApkPath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ApkSha256,
    [Parameter(Mandatory)][string]$ManifestDumpPath,
    [Parameter(Mandatory)][string]$ProxyPath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ProxySha256,
    [Parameter(Mandatory)][string]$RegistryPath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$RegistrySha256,
    [Parameter(Mandatory)][string]$OutputPath
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'lib/MetaXrOperatorPackaging.psm1') -Force
function Assert-Hash([string]$Path,[string]$Expected) {
    if((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Expected){throw 'Input bytes changed.'}
}
Assert-Hash $ApkPath $ApkSha256
Assert-Hash $ProxyPath $ProxySha256
Assert-Hash $RegistryPath $RegistrySha256
$arguments=Get-Content -LiteralPath $SelectionArgumentsPath -Raw | ConvertFrom-Json -AsHashtable
$selection=Get-QuestMetaXrOperatorSelection @arguments
if(-not $selection.enabled){throw 'Explicit debug Operator selection required.'}
Assert-QuestMetaXrOperatorArchive -Selection $selection -ApkPath $ApkPath
# Consumer must retain successful aapt2 xmltree provenance with this dump. Its
# producer exit status must be zero; a text file alone is not installed proof.
$dump=Get-Content -LiteralPath $ManifestDumpPath -Raw | ConvertFrom-Json -AsHashtable
if($dump.schema -cne 'rusty.quest.aapt2_manifest_dump.v1' -or $dump.apk_sha256 -cne $ApkSha256 -or $dump.exit_code -isnot [long] -and $dump.exit_code -isnot [int]){throw 'Exact successful APK manifest dump receipt required.'}
Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines $dump.lines -ExitCode $dump.exit_code
$sourceCommit=(& git -C (Split-Path $PSScriptRoot -Parent) rev-parse HEAD).Trim()
if($LASTEXITCODE -ne 0 -or $sourceCommit -cnotmatch '^[0-9a-f]{40}$'){throw 'Source commit unavailable.'}
$prepared=[ordered]@{
    schema='rusty.quest.meta_xr_input.prepared.v1'; enabled=$true
    selection=[ordered]@{package=$selection.package;apk_sha256=$ApkSha256;layer_fingerprint=$selection.fingerprint;
        feature_lock_sha256=$selection.feature_lock_sha256;consumer=$selection.consumer;source_commit=$sourceCommit}
    proxy=[ordered]@{path=(Get-Item -LiteralPath $ProxyPath).FullName;sha256=$ProxySha256}
    registry=[ordered]@{path=(Get-Item -LiteralPath $RegistryPath).FullName;sha256=$RegistrySha256}
    preparation=[ordered]@{selection_arguments_sha256=(Get-FileHash -LiteralPath $SelectionArgumentsPath).Hash.ToLowerInvariant();
        manifest_dump_sha256=(Get-FileHash -LiteralPath $ManifestDumpPath).Hash.ToLowerInvariant();
        adapter_sha256=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'lib/meta-xr-operator-backend.mjs')).Hash.ToLowerInvariant()}
    runtime_bound=$false;device_verified=$false
}
$bytes=[Text.UTF8Encoding]::new($false).GetBytes(($prepared | ConvertTo-Json -Depth 30))
$stream=[IO.File]::Open($OutputPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
try{$stream.Write($bytes);$stream.Flush($true)}finally{$stream.Dispose()}
$prepared
