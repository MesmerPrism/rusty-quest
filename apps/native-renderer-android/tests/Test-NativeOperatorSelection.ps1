#Requires -Version 7.6
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$LibraryPath,
    [Parameter(Mandatory)][string]$LibrarySha256,
    [Parameter(Mandatory)][string]$LayerManifestPath,
    [Parameter(Mandatory)][string]$LayerManifestSha256,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if(Test-Path -LiteralPath $OutputDirectory){throw 'Fresh test output directory required'}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$OutputDirectory=(Resolve-Path -LiteralPath $OutputDirectory).Path
Import-Module (Join-Path $repo 'tools/lib/MetaXrOperatorPackaging.psm1') -Force
$checks=[Collections.Generic.List[string]]::new()
function Check([bool]$Value,[string]$Label){if(-not $Value){throw $Label};$checks.Add($Label)}
function Refuse([string]$Label,[scriptblock]$Action){$rejected=$false;try{&$Action|Out-Null}catch{$rejected=$true};Check $rejected $Label}
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Resolve([string]$Fixture,[string]$Name){
    $root=Join-Path $OutputDirectory $Name
    & pwsh -NoProfile -File (Join-Path $repo 'tools/Resolve-NativeAppBuild.ps1') -AppSpec (Join-Path $repo $Fixture) -DryRun -OutputRoot $root -ResultJsonPath (Join-Path $root 'resolution.json') *> (Join-Path $OutputDirectory ($Name+'.txt'))
    if($LASTEXITCODE -ne 0){throw 'Actual native fixture resolution failed'}
    Get-Content -LiteralPath (Join-Path $root 'resolution.json') -Raw | ConvertFrom-Json
}
$resolved=Resolve 'fixtures/native-app-builds/native-development-hotload-operator.app.json' 'selected'
$lock=Get-Content -LiteralPath $resolved.feature_lock_path -Raw | ConvertFrom-Json
Check (@($lock.selected_feature_ids).Count -eq 7 -and @($lock.selected_feature_ids) -ccontains 'debug.meta_xr_operator') 'actual seven-feature selection'
$manifest=Join-Path $repo $lock.generated_outputs.android_manifest
if([IO.Path]::IsPathRooted([string]$lock.generated_outputs.android_manifest)){$manifest=[string]$lock.generated_outputs.android_manifest}
$options=@{Enable=$true;Consumer='NativeOpenXr';FeatureLockPath=$resolved.feature_lock_path;FeatureLockSha256=$resolved.feature_lock_sha256;AndroidManifestPath=$manifest;PackageName='io.github.mesmerprism.rustyquest.native_renderer.development_hotload_operator';LibraryPath=$LibraryPath;LibrarySha256=$LibrarySha256;LayerManifestPath=$LayerManifestPath;LayerManifestSha256=$LayerManifestSha256}
$selection=Get-QuestMetaXrOperatorSelection @options
Check $selection.enabled 'actual external vendor selection admitted'
Check (-not (Get-QuestMetaXrOperatorSelection).enabled) 'unselected selection inert'
Refuse 'inputs require explicit switch' { $bad=$options.Clone();$bad.Enable=$false;Get-QuestMetaXrOperatorSelection @bad }
Refuse 'exact package required' { $bad=$options.Clone();$bad.PackageName='io.example.other';Get-QuestMetaXrOperatorSelection @bad }
Refuse 'vendor library digest mismatch refused' { $bad=$options.Clone();$bad.LibrarySha256='0'*64;Get-QuestMetaXrOperatorSelection @bad }
$original=Resolve 'fixtures/native-app-builds/native-development-hotload.app.json' 'original'
$originalLock=Get-Content -LiteralPath $original.feature_lock_path -Raw | ConvertFrom-Json
Check (@($originalLock.selected_feature_ids).Count -eq 6 -and @($originalLock.android_manifest.permissions) -cnotcontains 'android.permission.INTERNET') 'original six-feature permission envelope preserved'
Refuse 'original fixture cannot admit Operator' { $bad=$options.Clone();$bad.FeatureLockPath=$original.feature_lock_path;$bad.FeatureLockSha256=$original.feature_lock_sha256;Get-QuestMetaXrOperatorSelection @bad }
foreach($remove in @('android.permission.INTERNET','com.oculus.experimental.enabled')){
    $xml=[xml](Get-Content -LiteralPath $manifest -Raw)
    $ns=[Xml.XmlNamespaceManager]::new($xml.NameTable);$ns.AddNamespace('a','http://schemas.android.com/apk/res/android')
    $node=$xml.SelectSingleNode("//*[@a:name='$remove']",$ns);[void]$node.ParentNode.RemoveChild($node)
    $badPath=Join-Path $OutputDirectory ($remove+'.xml');$xml.Save($badPath)
    Refuse ('missing '+$remove+' refused') {$bad=$options.Clone();$bad.AndroidManifestPath=$badPath;Get-QuestMetaXrOperatorSelection @bad}
}
$builder=Join-Path $repo 'tools/Build-NativeRendererAndroid.ps1'
$parameters=(Get-Command $builder).Parameters
foreach($name in @('EnableMetaXrOperator','MetaXrOperatorLibrary','MetaXrOperatorLibrarySha256','MetaXrOperatorManifest','MetaXrOperatorManifestSha256')){Check $parameters.ContainsKey($name) ('canonical builder exposes explicit '+$name)}
$stage=Join-Path $OutputDirectory 'stage';$native=Join-Path $OutputDirectory 'native'
Copy-QuestMetaXrOperatorLayer -Selection $selection -OutputRoot $stage -NativeStageRoot $native
Check ((Hash (Join-Path $stage $selection.layer_manifest.apk_path)) -eq $LayerManifestSha256) 'staged actual layer manifest bytes'
Check ((Hash (Join-Path $native $selection.library.apk_path)) -eq $LibrarySha256) 'staged actual vendor ELF bytes'
Refuse 'immutable staging roots cannot be overwritten' {Copy-QuestMetaXrOperatorLayer -Selection $selection -OutputRoot $stage -NativeStageRoot $native}
$result=[ordered]@{schema='rusty.quest.native_operator_selection_test.v1';passed=$true;checks=@($checks);selection=$selection;feature_lock_sha256=$resolved.feature_lock_sha256;scope='Host selection, fixture, byte staging and refusal tests; not canonical APK, installed layer or input evidence'}
$result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'result.json') -Encoding utf8NoBOM
Write-Output ('Native Operator selection PASS '+$checks.Count+' checks')
