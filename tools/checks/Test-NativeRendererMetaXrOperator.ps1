[CmdletBinding()]
param([string]$RepoRoot=(Join-Path $PSScriptRoot '../..'),[string]$OutputDirectory='',
    [string]$LibraryPath='',[string]$LibrarySha256='',[string]$LayerManifestPath='',[string]$LayerManifestSha256='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$RepoRoot=(Resolve-Path -LiteralPath $RepoRoot).Path
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory=Join-Path $RepoRoot ('local-artifacts/operator-fixture/'+[guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Fresh fixture output directory required.' }
[void][IO.Directory]::CreateDirectory($OutputDirectory)
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererMetaXrOperator.psm1') -Force
$selected=@{selected_feature_ids=@('debug.meta_xr_operator','renderer.native_scene')}
$off=@{selected_feature_ids=@('renderer.native_scene')}
$android=Join-Path $OutputDirectory 'AndroidManifest.xml'
$androidText='<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.INTERNET"/><uses-feature android:name="com.oculus.experimental.enabled" android:required="false"/><application android:debuggable="true"/></manifest>'
[IO.File]::WriteAllText($android,$androidText)
$manifest=@{file_format_version='1.0.0';api_layer=@{name='XR_APILAYER_METAX_operator';library_path='./libXrApiLayer_METAX_operator.so';api_version='1.1';implementation_version='1';description='Synthetic contract fixture';disable_environment='METAX_operator_disabled';instance_extensions=@(@{name='XR_METAX1_agentic_external_tool';extension_version='1'})}}
$manifestPath=Join-Path $OutputDirectory 'layer.json'
[IO.File]::WriteAllText($manifestPath,($manifest|ConvertTo-Json -Depth 6))
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function New-Elf([string]$FileName,[int]$Machine=183,[string[]]$Dependencies=@('libandroid.so','libc.so','libdl.so','liblog.so','libm.so','libmediandk.so')) {
    $bytes=[byte[]]::new(4096)
    function W16([int]$At,[uint16]$Value) { [BitConverter]::GetBytes($Value).CopyTo($bytes,$At) }
    function W32([int]$At,[uint32]$Value) { [BitConverter]::GetBytes($Value).CopyTo($bytes,$At) }
    function W64([int]$At,[ulong]$Value) { [BitConverter]::GetBytes($Value).CopyTo($bytes,$At) }
    $magic=[byte[]]@(127,69,76,70,2,1,1);$magic.CopyTo($bytes,0)
    W16 16 3; W16 18 $Machine; W32 20 1; W64 32 64; W16 52 64; W16 54 56; W16 56 2
    W32 64 1; W32 68 5; W64 72 0; W64 80 65536; W64 96 4096; W64 104 4096; W64 112 4096
    $names=@($Dependencies)+@('libXrApiLayer_METAX_operator.so')
    $strings=[Collections.Generic.List[byte]]::new(); $strings.Add(0); $indices=@()
    foreach($dependencyName in $names) { $indices+=$strings.Count; $strings.AddRange([Text.Encoding]::UTF8.GetBytes($dependencyName));$strings.Add(0) }
    $strings.ToArray().CopyTo($bytes,512)
    $cursor=256
    foreach($index in $indices[0..($Dependencies.Count-1)]) { W64 $cursor 1;W64 ($cursor+8) $index;$cursor+=16 }
    foreach($pair in @(@(14,$indices[-1]),@(5,66048),@(10,$strings.Count),@(0,0))) { W64 $cursor $pair[0];W64 ($cursor+8) $pair[1];$cursor+=16 }
    W32 120 2; W32 124 6; W64 128 256; W64 136 65792; W64 152 ($cursor-256); W64 160 ($cursor-256); W64 168 8
    $path=Join-Path $OutputDirectory $FileName;[IO.File]::WriteAllBytes($path,$bytes);$path
}
$library=New-Elf 'layer.so'
$options=@{FeatureLock=$selected;AndroidManifestPath=$android;Enable=$true;LibraryPath=$library;LibrarySha256=(Hash $library);LayerManifestPath=$manifestPath;LayerManifestSha256=(Hash $manifestPath)}
$passed=[Collections.Generic.List[string]]::new()
function Deny([string]$Label,[scriptblock]$Action) {
    $rejected=$false;try { &$Action|Out-Null } catch { $rejected=$true }
    if(-not $rejected){throw "Expected rejection: $Label"};$passed.Add($Label)
}
$selection=Get-NativeRendererMetaXrOperatorSelection @options
$originalIds=@('debug.meta_xr_operator','ui.native_mesh_panel.original_product','renderer.private_particles','renderer.background.solid_black','ui.breath_composition_control_panel','input.simultaneous_hands_and_controllers')
$originalOptions=$options.Clone();$originalOptions.FeatureLock=@{selected_feature_ids=$originalIds}
[void](Get-NativeRendererMetaXrOperatorSelection @originalOptions);$passed.Add('original-render-mesh-closed-selection')
foreach($required in @($originalIds | Where-Object { $_ -cne 'debug.meta_xr_operator' })) {
    Deny ('original-missing-'+$required) { $x=$originalOptions.Clone();$x.FeatureLock=@{selected_feature_ids=@($originalIds | Where-Object { $_ -cne $required })};Get-NativeRendererMetaXrOperatorSelection @x }
}
Deny 'original-mixed-render-route' { $x=$originalOptions.Clone();$x.FeatureLock=@{selected_feature_ids=$originalIds+@('renderer.native_scene')};Get-NativeRendererMetaXrOperatorSelection @x }
Deny 'original-2d-without-mesh' { $x=$originalOptions.Clone();$x.FeatureLock=@{selected_feature_ids=@('debug.meta_xr_operator','renderer.private_particles','renderer.background.solid_black')};Get-NativeRendererMetaXrOperatorSelection @x }
$app=Get-Content -Raw -LiteralPath (Join-Path $RepoRoot 'fixtures/native-app-builds/native-worldspace-panel-test.app.json')|ConvertFrom-Json
$app.requested_features+=@('debug.meta_xr_operator')
$app.declared_manifest.permissions+=@('android.permission.INTERNET')
$app.declared_manifest.uses_features+=@('com.oculus.experimental.enabled')
$app.settings_assertions.required_modules+=@('debug/meta-xr-operator')
$spec=Join-Path $OutputDirectory 'selected.app.json';$resolution=Join-Path $OutputDirectory 'resolved/resolution.json'
$app|ConvertTo-Json -Depth 20|Set-Content -LiteralPath $spec
& pwsh -NoProfile -File (Join-Path $RepoRoot 'tools/Resolve-NativeAppBuild.ps1') -AppSpec $spec -OutputRoot (Join-Path $OutputDirectory 'resolved') -ResultJsonPath $resolution -DryRun | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Production app resolver rejected the Operator fixture.' }
$resolved=Get-Content -Raw -LiteralPath $resolution|ConvertFrom-Json
$lock=Get-Content -Raw -LiteralPath $resolved.feature_lock_path|ConvertFrom-Json
$generatedManifest=[string]$lock.generated_outputs.android_manifest
if (-not [IO.Path]::IsPathRooted($generatedManifest)) { $generatedManifest=Join-Path $RepoRoot $generatedManifest }
$joined=$options.Clone();$joined.FeatureLock=$lock;$joined.AndroidManifestPath=$generatedManifest
[void](Get-NativeRendererMetaXrOperatorSelection @joined);$passed.Add('production-descriptor-resolver-join')
if(-not $selection.enabled -or $selection.elf.machine -cne 'AArch64' -or $selection.elf.load_alignments_bytes[0] -ne 4096){throw 'Valid synthetic ELF failed.'};$passed.Add('valid-closed-elf')
if((Get-NativeRendererMetaXrOperatorSelection -FeatureLock $off -AndroidManifestPath $android).enabled){throw 'Default unexpectedly enabled.'};$passed.Add('default-off')
Deny 'unselected-inputs' { $x=$options.Clone();$x.FeatureLock=$off;Get-NativeRendererMetaXrOperatorSelection @x }
Deny 'selected-without-opt-in' { $x=$options.Clone();$x.Enable=$false;Get-NativeRendererMetaXrOperatorSelection @x }
Deny 'hash-mismatch' { $x=$options.Clone();$x.LibrarySha256='0'*64;Get-NativeRendererMetaXrOperatorSelection @x }
Deny 'missing-file' { $x=$options.Clone();$x.LibraryPath=Join-Path $OutputDirectory 'missing.so';Get-NativeRendererMetaXrOperatorSelection @x }
$wrongArch=New-Elf 'wrong-arch.so' 62
Deny 'wrong-architecture' { $x=$options.Clone();$x.LibraryPath=$wrongArch;$x.LibrarySha256=Hash $wrongArch;Get-NativeRendererMetaXrOperatorSelection @x }
$wrongDeps=New-Elf 'wrong-deps.so' 183 @('libandroid.so','libc.so','libdl.so','liblog.so','libm.so','libunexpected.so')
Deny 'dependency-drift' { $x=$options.Clone();$x.LibraryPath=$wrongDeps;$x.LibrarySha256=Hash $wrongDeps;Get-NativeRendererMetaXrOperatorSelection @x }
$truncated=Join-Path $OutputDirectory 'truncated.so';[IO.File]::WriteAllBytes($truncated,[byte[]]@(127,69,76,70))
Deny 'truncated-elf' { $x=$options.Clone();$x.LibraryPath=$truncated;$x.LibrarySha256=Hash $truncated;Get-NativeRendererMetaXrOperatorSelection @x }
$unaligned=Join-Path $OutputDirectory 'unaligned.so';$bytes=[IO.File]::ReadAllBytes($library);[BitConverter]::GetBytes([ulong]3).CopyTo($bytes,112);[IO.File]::WriteAllBytes($unaligned,$bytes)
Deny 'invalid-load-alignment' { $x=$options.Clone();$x.LibraryPath=$unaligned;$x.LibrarySha256=Hash $unaligned;Get-NativeRendererMetaXrOperatorSelection @x }
foreach($case in @(@('release-manifest',$androidText.Replace('debuggable="true"','debuggable="false"')),@('experimental-required',$androidText.Replace('required="false"','required="true"')),@('missing-internet',$androidText.Replace('<uses-permission android:name="android.permission.INTERNET"/>','')))) {
    $path=Join-Path $OutputDirectory ($case[0]+'.xml');[IO.File]::WriteAllText($path,$case[1])
    Deny $case[0] { $x=$options.Clone();$x.AndroidManifestPath=$path;Get-NativeRendererMetaXrOperatorSelection @x }
}
$invalidManifest=Join-Path $OutputDirectory 'wrong-layer.json';[IO.File]::WriteAllText($invalidManifest,[IO.File]::ReadAllText($manifestPath).Replace('./libXrApiLayer_METAX_operator.so','../escape.so'))
Deny 'manifest-library-traversal' { $x=$options.Clone();$x.LayerManifestPath=$invalidManifest;$x.LayerManifestSha256=Hash $invalidManifest;Get-NativeRendererMetaXrOperatorSelection @x }
$duplicate=Join-Path $OutputDirectory 'duplicate.json';[IO.File]::WriteAllText($duplicate,[IO.File]::ReadAllText($manifestPath).Replace('"file_format_version": "1.0.0"','"file_format_version": "1.0.0", "file_format_version": "1.0.0"'))
Deny 'duplicate-json-key' { $x=$options.Clone();$x.LayerManifestPath=$duplicate;$x.LayerManifestSha256=Hash $duplicate;Get-NativeRendererMetaXrOperatorSelection @x }
$staged=Join-Path $OutputDirectory 'stage';$native=Join-Path $staged 'native'
Copy-NativeRendererMetaXrOperatorLayer -Selection $selection -OutputRoot $staged -NativeStageRoot $native
if((Hash (Join-Path $native $selection.library.apk_path)) -cne $selection.library.sha256 -or (Hash (Join-Path $staged $selection.layer_manifest.apk_path)) -cne $selection.layer_manifest.sha256){throw 'Staged bytes differ.'};$passed.Add('exact-byte-staging')
Deny 'existing-stage-no-overwrite' { Copy-NativeRendererMetaXrOperatorLayer -Selection $selection -OutputRoot $staged -NativeStageRoot $native }
function Archive([string]$Name,[bool]$Extra=$false) {
    $path=Join-Path $OutputDirectory $Name;$zip=[IO.Compression.ZipFile]::Open($path,[IO.Compression.ZipArchiveMode]::Create)
    try { foreach($record in @($selection.library,$selection.layer_manifest)) { $entry=$zip.CreateEntry($record.apk_path);$stream=$entry.Open();try{$stream.Write([IO.File]::ReadAllBytes($record.source_path))}finally{$stream.Dispose()} };if($Extra){[void]$zip.CreateEntry('assets/extra-metax_operator.json')} }finally{$zip.Dispose()};$path
}
$zip=Archive 'valid-fixture.zip';Assert-NativeRendererMetaXrOperatorArchive -Selection $selection -ApkPath $zip;$passed.Add('exact-archive-closure')
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeAppPrivateAssetProvider.psm1') -Force
$assetRecords=@(Assert-NativeAppApkAssetArchive -ApkPath $zip -AssetRoot (Join-Path $staged 'assets'))
if($assetRecords.Count -ne 1 -or $assetRecords[0].sha256 -cne $selection.layer_manifest.sha256){throw 'Operator manifest failed the ordinary APK asset closure.'};$passed.Add('ordinary-apk-asset-closure')
Deny 'default-archive-leak' { Assert-NativeRendererMetaXrOperatorArchive -Selection @{enabled=$false} -ApkPath $zip }
$extra=Archive 'extra-fixture.zip' $true
Deny 'extra-layer-entry' { Assert-NativeRendererMetaXrOperatorArchive -Selection $selection -ApkPath $extra }
$actual=$null
if(-not [string]::IsNullOrWhiteSpace($LibraryPath)) {
    $actual=Get-NativeRendererMetaXrOperatorSelection -FeatureLock $selected -AndroidManifestPath $android -Enable -LibraryPath $LibraryPath -LibrarySha256 $LibrarySha256 -LayerManifestPath $LayerManifestPath -LayerManifestSha256 $LayerManifestSha256
    $passed.Add('actual-external-input-validation')
}
$result=[ordered]@{schema='rusty.quest.meta_xr_operator.host_fixture.v1';passed=@($passed);count=$passed.Count;actual_input=$actual;apk_builds=0;device_operations=0;runtime_verified=$false}
$result|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $OutputDirectory 'result.json')
Write-Output "Meta XR Operator host fixture passed $($passed.Count) cases; no APK/device/runtime effects."
