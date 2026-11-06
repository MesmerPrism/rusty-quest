[CmdletBinding()]
param([Parameter(Mandatory)][string]$AppSpec,
      [Parameter(Mandatory)][string]$OutputRoot,
      [string]$FeatureDir = 'fixtures/native-app-features')
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $OutputRoot) { throw 'Create-new output root required.' }
$repo = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $PSScriptRoot 'lib/NativeRendererBrokerClient.psm1') -Force
$resultPath = Join-Path $OutputRoot 'resolution.json'
& (Join-Path $PSScriptRoot 'Resolve-NativeAppBuild.ps1') -AppSpec $AppSpec -FeatureDir $FeatureDir -OutputRoot $OutputRoot -ResultJsonPath $resultPath -DryRun
$locks = @(Get-ChildItem -LiteralPath $OutputRoot -Filter feature-lock.json -Recurse)
if ($locks.Count -ne 1) { throw 'Exactly one actual resolved feature lock required.' }
$lock = Get-Content -LiteralPath $locks[0].FullName -Raw | ConvertFrom-Json
$template = [IO.File]::ReadAllText((Join-Path $repo 'fixtures/broker-clients/native-renderer.client.json'))
$json = New-NativeRendererBrokerClientJson -TemplateJson $template -AppId $lock.app_id -PackageName $lock.android_manifest.package_name -SelectedFeatureIds @($lock.selected_feature_ids)
$output = Join-Path $OutputRoot 'generated-native-renderer.client.json'
$stream = [IO.File]::Open($output,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
try { $bytes = [Text.UTF8Encoding]::new($false).GetBytes($json); $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
[ordered]@{client_lock_path=$output;client_lock_sha256=(Get-FileHash $output).Hash.ToLowerInvariant();feature_lock_path=$locks[0].FullName;feature_lock_sha256=(Get-FileHash $locks[0].FullName).Hash.ToLowerInvariant();scope='Resolved packaged client input only; no signer, grant, token, APK or runtime effect'} | ConvertTo-Json
