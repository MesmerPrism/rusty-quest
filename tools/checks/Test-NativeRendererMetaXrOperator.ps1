[CmdletBinding()]
param([string]$RepoRoot=(Join-Path $PSScriptRoot '../..'),[string]$OutputDirectory='',
    [string]$LibraryPath='',[string]$LibrarySha256='',[string]$LayerManifestPath='',[string]$LayerManifestSha256='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$RepoRoot=(Resolve-Path -LiteralPath $RepoRoot).Path
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory=Join-Path $RepoRoot ('local-artifacts/operator-fixture/'+[guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Fresh fixture output directory required.' }
[void][IO.Directory]::CreateDirectory($OutputDirectory)
Import-Module (Join-Path $RepoRoot 'tools/lib/MetaXrOperatorPackaging.psm1') -Force
$selected=@{schema='rusty.quest.native_app_feature_lock.v1';selected_feature_ids=@('debug.meta_xr_operator','renderer.native_scene','native.development_feedback')}
$off=@{schema='rusty.quest.native_app_feature_lock.v1';selected_feature_ids=@('renderer.native_scene')}
$android=Join-Path $OutputDirectory 'AndroidManifest.xml'
$androidText='<manifest package="io.example.native" xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.INTERNET"/><uses-feature android:name="com.oculus.experimental.enabled" android:required="false"/><application android:debuggable="true"/></manifest>'
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
$selectedPath=Join-Path $OutputDirectory 'selected.json';$offPath=Join-Path $OutputDirectory 'off.json'
$selected|ConvertTo-Json|Set-Content -LiteralPath $selectedPath;$off|ConvertTo-Json|Set-Content -LiteralPath $offPath
$options=@{FeatureLockPath=$selectedPath;FeatureLockSha256=(Hash $selectedPath);AndroidManifestPath=$android;PackageName='io.example.native';Consumer='NativeOpenXr';Enable=$true;LibraryPath=$library;LibrarySha256=(Hash $library);LayerManifestPath=$manifestPath;LayerManifestSha256=(Hash $manifestPath)}
$passed=[Collections.Generic.List[string]]::new()
function Deny([string]$Label,[scriptblock]$Action) {
    $rejected=$false;try { &$Action|Out-Null } catch { $rejected=$true }
    if(-not $rejected){throw "Expected rejection: $Label"};$passed.Add($Label)
}
$selection=Get-QuestMetaXrOperatorSelection @options
# Join the actual canonical development-host fixture and its descriptor dependencies.
$spec=Join-Path $RepoRoot 'fixtures/native-app-builds/native-development-hotload-operator.app.json'
$resolution=Join-Path $OutputDirectory 'resolved/resolution.json'
& pwsh -NoProfile -File (Join-Path $RepoRoot 'tools/Resolve-NativeAppBuild.ps1') -AppSpec $spec -OutputRoot (Join-Path $OutputDirectory 'resolved') -ResultJsonPath $resolution -DryRun | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Production app resolver rejected the canonical Operator fixture.' }
$resolved=Get-Content -Raw -LiteralPath $resolution|ConvertFrom-Json
$lock=Get-Content -Raw -LiteralPath $resolved.feature_lock_path|ConvertFrom-Json
$generatedManifest=[string]$lock.generated_outputs.android_manifest
if (-not [IO.Path]::IsPathRooted($generatedManifest)) { $generatedManifest=Join-Path $RepoRoot $generatedManifest }
if (@($lock.selected_feature_ids).Count -ne 7 -or @($lock.selected_feature_ids) -cnotcontains 'native.development_feedback') { throw 'Canonical Operator dependency closure differs.' }
$descriptor=Get-Content -Raw -LiteralPath (Join-Path $RepoRoot 'fixtures/native-app-features/development/meta-xr-operator/debug.meta_xr_operator.feature.json')|ConvertFrom-Json
if ($descriptor.module_path -cne 'development/meta-xr-operator' -or (@($descriptor.depends_on) -join '|') -cne 'native.development_feedback') { throw 'Canonical Operator module/dependency differs.' }
$joined=$options.Clone();$joined.FeatureLockPath=$resolved.feature_lock_path;$joined.FeatureLockSha256=$resolved.feature_lock_sha256;$joined.AndroidManifestPath=$generatedManifest;$joined.PackageName=$lock.android_manifest.package_name
[void](Get-QuestMetaXrOperatorSelection @joined);$passed.Add('production-descriptor-resolver-join')
if(-not $selection.enabled -or $selection.elf.machine -cne 'AArch64' -or $selection.elf.load_alignments_bytes[0] -ne 4096){throw 'Valid synthetic ELF failed.'};$passed.Add('valid-closed-elf')
if((Get-QuestMetaXrOperatorSelection).enabled){throw 'Default unexpectedly enabled.'};$passed.Add('default-off')
Deny 'unselected-inputs' { $x=$options.Clone();$x.FeatureLockPath=$offPath;$x.FeatureLockSha256=Hash $offPath;Get-QuestMetaXrOperatorSelection @x }
Deny 'selected-without-opt-in' { $x=$options.Clone();$x.Enable=$false;Get-QuestMetaXrOperatorSelection @x }
Deny 'hash-mismatch' { $x=$options.Clone();$x.LibrarySha256='0'*64;Get-QuestMetaXrOperatorSelection @x }
Deny 'missing-file' { $x=$options.Clone();$x.LibraryPath=Join-Path $OutputDirectory 'missing.so';Get-QuestMetaXrOperatorSelection @x }
$wrongArch=New-Elf 'wrong-arch.so' 62
Deny 'wrong-architecture' { $x=$options.Clone();$x.LibraryPath=$wrongArch;$x.LibrarySha256=Hash $wrongArch;Get-QuestMetaXrOperatorSelection @x }
$wrongDeps=New-Elf 'wrong-deps.so' 183 @('libandroid.so','libc.so','libdl.so','liblog.so','libm.so','libunexpected.so')
Deny 'dependency-drift' { $x=$options.Clone();$x.LibraryPath=$wrongDeps;$x.LibrarySha256=Hash $wrongDeps;Get-QuestMetaXrOperatorSelection @x }
$truncated=Join-Path $OutputDirectory 'truncated.so';[IO.File]::WriteAllBytes($truncated,[byte[]]@(127,69,76,70))
Deny 'truncated-elf' { $x=$options.Clone();$x.LibraryPath=$truncated;$x.LibrarySha256=Hash $truncated;Get-QuestMetaXrOperatorSelection @x }
$unaligned=Join-Path $OutputDirectory 'unaligned.so';$bytes=[IO.File]::ReadAllBytes($library);[BitConverter]::GetBytes([ulong]3).CopyTo($bytes,112);[IO.File]::WriteAllBytes($unaligned,$bytes)
Deny 'invalid-load-alignment' { $x=$options.Clone();$x.LibraryPath=$unaligned;$x.LibrarySha256=Hash $unaligned;Get-QuestMetaXrOperatorSelection @x }
foreach($case in @(@('release-manifest',$androidText.Replace('debuggable="true"','debuggable="false"')),@('experimental-required',$androidText.Replace('required="false"','required="true"')),@('missing-internet',$androidText.Replace('<uses-permission android:name="android.permission.INTERNET"/>','')))) {
    $path=Join-Path $OutputDirectory ($case[0]+'.xml');[IO.File]::WriteAllText($path,$case[1])
    Deny $case[0] { $x=$options.Clone();$x.AndroidManifestPath=$path;Get-QuestMetaXrOperatorSelection @x }
}
$invalidManifest=Join-Path $OutputDirectory 'wrong-layer.json';[IO.File]::WriteAllText($invalidManifest,[IO.File]::ReadAllText($manifestPath).Replace('./libXrApiLayer_METAX_operator.so','../escape.so'))
Deny 'manifest-library-traversal' { $x=$options.Clone();$x.LayerManifestPath=$invalidManifest;$x.LayerManifestSha256=Hash $invalidManifest;Get-QuestMetaXrOperatorSelection @x }
$duplicate=Join-Path $OutputDirectory 'duplicate.json';[IO.File]::WriteAllText($duplicate,[IO.File]::ReadAllText($manifestPath).Replace('"file_format_version": "1.0.0"','"file_format_version": "1.0.0", "file_format_version": "1.0.0"'))
Deny 'duplicate-json-key' { $x=$options.Clone();$x.LayerManifestPath=$duplicate;$x.LayerManifestSha256=Hash $duplicate;Get-QuestMetaXrOperatorSelection @x }
$staged=Join-Path $OutputDirectory 'stage';$native=Join-Path $staged 'native'
Copy-QuestMetaXrOperatorLayer -Selection $selection -OutputRoot $staged -NativeStageRoot $native
if((Hash (Join-Path $native $selection.library.apk_path)) -cne $selection.library.sha256 -or (Hash (Join-Path $staged $selection.layer_manifest.apk_path)) -cne $selection.layer_manifest.sha256){throw 'Staged bytes differ.'};$passed.Add('exact-byte-staging')
Deny 'existing-stage-no-overwrite' { Copy-QuestMetaXrOperatorLayer -Selection $selection -OutputRoot $staged -NativeStageRoot $native }
function Archive([string]$Name,[bool]$Extra=$false,[string]$Damage='') {
    $path=Join-Path $OutputDirectory $Name;$zip=[IO.Compression.ZipFile]::Open($path,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach($record in @($selection.library,$selection.layer_manifest)) {
            if($Damage -ceq 'missing' -and $record -eq $selection.layer_manifest){continue}
            $bytes=[IO.File]::ReadAllBytes($record.source_path)
            if($record -eq $selection.library){
                if($Damage -ceq 'bytes'){$bytes[0]=$bytes[0] -bxor 1}
                elseif($Damage -ceq 'truncated'){$bytes=[byte[]]$bytes[0..($bytes.Length-2)]}
            }
            $entry=$zip.CreateEntry($record.apk_path);$stream=$entry.Open()
            try{$stream.Write($bytes)}finally{$stream.Dispose()}
        }
        if($Extra){[void]$zip.CreateEntry('assets/extra-metax_operator.json')}
        if($Damage -ceq 'duplicate'){[void]$zip.CreateEntry($selection.library.apk_path)}
    } finally{$zip.Dispose()}
    $path
}
$zip=Archive 'valid-fixture.zip';Assert-QuestMetaXrOperatorArchive -Selection $selection -ApkPath $zip;$passed.Add('exact-archive-closure')
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeAppPrivateAssetProvider.psm1') -Force
$assetRecords=@(Assert-NativeAppApkAssetArchive -ApkPath $zip -AssetRoot (Join-Path $staged 'assets'))
if($assetRecords.Count -ne 1 -or $assetRecords[0].sha256 -cne $selection.layer_manifest.sha256){throw 'Operator manifest failed the ordinary APK asset closure.'};$passed.Add('ordinary-apk-asset-closure')
Deny 'default-archive-leak' { Assert-QuestMetaXrOperatorArchive -Selection @{enabled=$false} -ApkPath $zip }
$extra=Archive 'extra-fixture.zip' $true
Deny 'extra-layer-entry' { Assert-QuestMetaXrOperatorArchive -Selection $selection -ApkPath $extra }
foreach($damage in @('bytes','truncated','missing','duplicate')) {
    $damaged=Archive ($damage+'-fixture.zip') $false $damage
    Deny ('archive-'+$damage) { Assert-QuestMetaXrOperatorArchive -Selection $selection -ApkPath $damaged }
}
$actual=$null
if(-not [string]::IsNullOrWhiteSpace($LibraryPath)) {
    $actualOptions=$joined.Clone();$actualOptions.LibraryPath=$LibraryPath;$actualOptions.LibrarySha256=$LibrarySha256;$actualOptions.LayerManifestPath=$LayerManifestPath;$actualOptions.LayerManifestSha256=$LayerManifestSha256
    $actual=Get-QuestMetaXrOperatorSelection @actualOptions
    $passed.Add('actual-external-input-validation')
}
$result=[ordered]@{schema='rusty.quest.meta_xr_operator.host_fixture.v1';passed=@($passed);count=$passed.Count;actual_input=$actual;apk_builds=0;device_operations=0;runtime_verified=$false}
$result|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $OutputDirectory 'result.json')
Write-Output "Meta XR Operator host fixture passed $($passed.Count) cases; no APK/device/runtime effects."
