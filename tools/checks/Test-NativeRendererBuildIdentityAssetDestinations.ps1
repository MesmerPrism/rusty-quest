param([string]$RepoRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference='Stop'
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeAppPrivateAssetProvider.psm1') -Force
$tokens=$null;$errors=$null;$builder=Join-Path $RepoRoot 'tools/Build-NativeRendererAndroid.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($builder,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Builder parse failed'}
$copy=$ast.Find({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Copy-AssetInput'},$true)
. ([scriptblock]::Create($copy.Extent.Text))
function Deny($label,[scriptblock]$action){$denied=$false;try{& $action | Out-Null}catch{$denied=$true};if(-not $denied){throw "Collision accepted: $label"}}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('quest-identity-destinations-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $temp | Out-Null
try {
 foreach($kind in @('file','directory')){
  $sourceRoot=Join-Path $temp $kind;New-Item -ItemType Directory $sourceRoot | Out-Null
  $source=Join-Path $sourceRoot 'Android-Build-Identity.json'
  if($kind -eq 'file'){[IO.File]::WriteAllText($source,'admitted public bytes')}else{New-Item -ItemType Directory $source|Out-Null;[IO.File]::WriteAllText((Join-Path $source 'child'),'admitted public bytes')}
  Deny "public preflight $kind" {Get-NativeAppPublicAssetDestinationRecords -AssetInputs @("$kind/Android-Build-Identity.json") -RepoRoot $temp}
  $out=Join-Path $temp ('copy-'+$kind)
  Deny "public materialization $kind" {Copy-AssetInput -Source "$kind/Android-Build-Identity.json" -DestinationRoot $out -RepoRoot $temp}
  if(Test-Path $out){throw 'Public collision mutated output'}
 }
 $ordinary=Join-Path $temp 'ordinary.bin';[IO.File]::WriteAllText($ordinary,'private bytes');$sha=(Get-FileHash $ordinary).Hash.ToLowerInvariant()
 $lockRoot=Join-Path $temp 'lock';$objects=Join-Path $lockRoot 'private-assets/objects';New-Item -ItemType Directory $objects -Force|Out-Null;Copy-Item $ordinary (Join-Path $objects $sha)
 $target=Join-Path $temp 'private-target';New-Item -ItemType Directory $target|Out-Null
 foreach($destination in @('android-build-identity.json','ANDROID-BUILD-IDENTITY.JSON','android-build-identity.json/child','ANDROID-BUILD-IDENTITY.JSON/child','.','..')){
  $asset=[ordered]@{asset_id='asset';logical_destination=$destination;staged_object="private-assets/objects/$sha";source_sha256=$sha;source_bytes=(Get-Item $ordinary).Length;media_type='application/octet-stream'}
  $inventory=[ordered]@{schema='rusty.quest.native_app_private_asset_inventory.v1';provider_id='test';assets=@($asset)}
  $closure=[pscustomobject]@{schema='rusty.quest.native_app_private_asset_closure.v1';mode='linked-provider';provider_id='test';provider_manifest_sha256=('a'*64);inventory_sha256=(Get-NativeAppPrivateAssetTextSha256 -Text ($inventory|ConvertTo-Json -Depth 12 -Compress));asset_count=1;assets=@($asset)}
  Deny "private direct closure $destination" {Copy-NativeAppPrivateAssetsFromClosure -Closure $closure -FeatureLockPath (Join-Path $lockRoot 'feature-lock.json') -DestinationRoot $target}
  if(@(Get-ChildItem $target -Force).Count){throw 'Private collision mutated output'}
  Deny "private closure preflight $destination" {Assert-NativeAppPrivateAssetClosureDestinations -Closure $closure}
 }
 foreach($destination in @('android-build-identity.json','ANDROID-BUILD-IDENTITY.JSON','android-build-identity.json/child','.','..')){
  Deny "public explicit destination $destination" {Copy-AssetInput -Source 'ordinary.bin' -DestinationRoot (Join-Path $temp 'explicit-output') -RepoRoot $temp -DestinationName $destination}
  if(Test-Path (Join-Path $temp 'explicit-output')){throw 'Explicit public collision mutated output'}
 }
 # A normal declared input still materializes through the same production route.
 $ok=Join-Path $temp 'ordinary-output';Copy-AssetInput -Source 'ordinary.bin' -DestinationRoot $ok -RepoRoot $temp|Out-Null
 if((Get-Content -Raw (Join-Path $ok 'ordinary.bin')) -cne 'private bytes'){throw 'Ordinary public asset changed'}
 $source=Get-Content -Raw $builder
 foreach($call in @('Get-NativeAppPublicAssetDestinationRecords -AssetInputs','Assert-NativeAppPrivateAssetClosureDestinations -Closure')){
  $at=$source.IndexOf($call);if($at -lt 0 -or $at -gt $source.IndexOf('$sourceComposition = Get-QuestBuildSourceComposition')){throw "Missing early build preflight: $call"}
 }
 'PASS generated identity public/private preflight and materialization collision matrix, unchanged outputs and ordinary positive control'
}finally{Remove-Item -LiteralPath $temp -Recurse -Force}
