#Requires -Version 7.6
[CmdletBinding()]
param([string]$RepoRoot=(Join-Path $PSScriptRoot '..'),[string]$OutputDirectory='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$RepoRoot=(Resolve-Path -LiteralPath $RepoRoot).Path
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory=Join-Path $RepoRoot ('local-artifacts/operator-fixture/'+[guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Fresh fixture output directory required.' }
[void][IO.Directory]::CreateDirectory($OutputDirectory)
& (Join-Path $RepoRoot 'tools/Test-PowerShellHost.ps1') -Quiet
Import-Module (Join-Path $RepoRoot 'tools/lib/MetaXrOperatorPackaging.psm1') -Force
Import-Module (Join-Path $RepoRoot 'tools/lib/MetaXrInputAutomation.psm1') -Force
$selected=@{schema='rusty.quest.native_app_feature_lock.v1';selected_feature_ids=@('debug.meta_xr_operator','renderer.native_scene')}
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
$options=@{FeatureLockPath=$selectedPath;FeatureLockSha256=(Hash $selectedPath);PackageName='io.example.native';AndroidManifestPath=$android;Enable=$true;LibraryPath=$library;LibrarySha256=(Hash $library);LayerManifestPath=$manifestPath;LayerManifestSha256=(Hash $manifestPath)}
$passed=[Collections.Generic.List[string]]::new()

function Deny([string]$Label,[scriptblock]$Action) {
    $rejected=$false;try { &$Action|Out-Null } catch { $rejected=$true }
    if(-not $rejected){throw "Expected rejection: $Label"};$passed.Add($Label)
}
$selection=Get-QuestMetaXrOperatorSelection @options
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
function Archive([string]$Name,[bool]$Extra=$false) {
    $path=Join-Path $OutputDirectory $Name;$zip=[IO.Compression.ZipFile]::Open($path,[IO.Compression.ZipArchiveMode]::Create)
    try { foreach($record in @($selection.library,$selection.layer_manifest)) { $entry=$zip.CreateEntry($record.apk_path);$stream=$entry.Open();try{$stream.Write([IO.File]::ReadAllBytes($record.source_path))}finally{$stream.Dispose()} };if($Extra){[void]$zip.CreateEntry('assets/extra-metax_operator.json')} }finally{$zip.Dispose()};$path
}
$zip=Archive 'valid-fixture.zip';Assert-QuestMetaXrOperatorArchive -Selection $selection -ApkPath $zip;$passed.Add('exact-archive-closure')
Deny 'default-archive-leak' { Assert-QuestMetaXrOperatorArchive -Selection @{enabled=$false} -ApkPath $zip }
$extra=Archive 'extra-fixture.zip' $true
Deny 'extra-layer-entry' { Assert-QuestMetaXrOperatorArchive -Selection $selection -ApkPath $extra }
$missingDebug=Join-Path $OutputDirectory 'missing-debug.xml';[IO.File]::WriteAllText($missingDebug,$androidText.Replace(' android:debuggable="true"',''))
Deny 'missing-debug-attribute-fails-closed' { $x=$options.Clone();$x.AndroidManifestPath=$missingDebug;Get-QuestMetaXrOperatorSelection @x }
$dump=@('E: manifest (line=1)','  A: package="io.example.native" (Raw: "io.example.native")','  E: application (line=2)','    A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=true')
Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines $dump -ExitCode 0;$passed.Add('packaged-current-aapt2-application-true')
$namespacedDump=@('N: android=http://schemas.android.com/apk/res/android (line=1)','  N: horizonos=http://schemas.horizonos/sdk (line=1)','    E: manifest (line=1)','      A: package="io.example.native" (Raw: "io.example.native")','        E: application (line=2)','          A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=true')
Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines $namespacedDump -ExitCode 0;$passed.Add('packaged-current-aapt2-namespaces-element-indentation')
$legacy=@('E: manifest (line=1)','  A: package="io.example.native" (Raw: "io.example.native")','  E: application (line=2)','    A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff')
Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines $legacy -ExitCode 0;$passed.Add('packaged-legacy-aapt2-application-typed-true')
Deny 'packaged-inspection-command-error' { Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines $dump -ExitCode 1 }
Deny 'packaged-debug-false' { Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines @($dump -replace '=true','=false') -ExitCode 0 }
Deny 'packaged-debug-typed-false' { Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines @($legacy -replace '0xffffffff','0x0') -ExitCode 0 }
Deny 'packaged-debug-missing' { Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines $dump[0..1] -ExitCode 0 }
Deny 'packaged-debug-duplicate' { Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines @($dump+$dump[2]) -ExitCode 0 }
Deny 'packaged-debug-nested' { Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines @($dump[0..1]+'    E: activity (line=3)'+('  '+$dump[2])) -ExitCode 0 }
Deny 'packaged-debug-unrelated-namespace' { Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines @($dump -replace 'http://schemas.android.com/apk/res/android:','urn:unrelated:') -ExitCode 0 }
Deny 'packaged-debug-wrong-resource' { Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines @($dump -replace '0x0101000f','0x01010000') -ExitCode 0 }
Deny 'packaged-debug-string-value' { Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines @($dump -replace '=true','=\"true\"') -ExitCode 0 }
Deny 'packaged-debug-multiple-applications' { Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines @($dump+'  E: application (line=4)') -ExitCode 0 }

function Check([bool]$Condition,[string]$Label) { if(-not $Condition){throw "Failed: $Label"};$passed.Add($Label) }
Deny 'wrong-target-package' { $x=$options.Clone();$x.PackageName='io.example.other';Get-QuestMetaXrOperatorSelection @x }
Deny 'source-lock-drift' { $x=$options.Clone();$x.FeatureLockSha256='1'*64;Get-QuestMetaXrOperatorSelection @x }
$xmlSdk=Join-Path $OutputDirectory 'sdk.xml';[IO.File]::WriteAllText($xmlSdk,$androidText.Replace('io.example.native','io.example.spatial').Replace('android:debuggable="true"','android:debuggable="true" android:extractNativeLibs="true"'))
$sdk=$options.Clone();$sdk.PackageName='io.example.spatial';$sdk.Consumer='SpatialSdk';$sdk.AndroidManifestPath=$xmlSdk
Deny 'sdk-relative-library-needs-16k' { Get-QuestMetaXrOperatorSelection @sdk }
$library16=New-Elf 'sdk-layer.so';$bytes=[IO.File]::ReadAllBytes($library16);[BitConverter]::GetBytes([ulong]16384).CopyTo($bytes,112);[IO.File]::WriteAllBytes($library16,$bytes)
$sdk.LibraryPath=$library16;$sdk.LibrarySha256=Hash $library16
$sdkSelection=Get-QuestMetaXrOperatorSelection @sdk
Check ($sdkSelection.enabled -and $sdkSelection.fingerprint -cne $selection.fingerprint) 'independent-spatial-package-closure'
Deny 'sdk-extraction-disabled' { $f=Join-Path $OutputDirectory 'sdk-no-extract.xml';[IO.File]::WriteAllText($f,[IO.File]::ReadAllText($xmlSdk).Replace('extractNativeLibs="true"','extractNativeLibs="false"'));$x=$sdk.Clone();$x.AndroidManifestPath=$f;Get-QuestMetaXrOperatorSelection @x }
$extractDump=@($dump+'    A: http://schemas.android.com/apk/res/android:extractNativeLibs(0x010104ea)=true')
Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines $extractDump -ExitCode 0 -RequireNativeExtraction;$passed.Add('packaged-extraction-true')
Deny 'packaged-extraction-missing' { Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines $dump -ExitCode 0 -RequireNativeExtraction }
Deny 'packaged-extraction-nested' { Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines @($dump+'    E: activity (line=3)'+'      A: android:extractNativeLibs(0x010104ea)=true') -ExitCode 0 -RequireNativeExtraction }

# Independent app state machines: native advances its original page; SDK reports
# delivery only. Neither stand-in makes a device/pixel claim.
function New-Consumer($Selected,[string]$Behavior='native') {
    $identity=[ordered]@{package=$Selected.package;apk_sha256=('a'*64);process_birth='birth-101';layer_fingerprint=$Selected.fingerprint;instance_id='instance-7';session_id='session-9'}
    $fixture=@{frame=0;dispatches=0;releases=0;terminals=0;page=1;behavior=$Behavior;releaseTargets=@();identity=$identity}
    $read={param($ctx);$fixture.frame++;@{identity=$ctx.identity;backend_ready=$true;layer_loaded=$true;focused=$true;frame_index=$fixture.frame;source_timestamp_ns=([long]$fixture.frame*1000);clock_domain='xr'}}.GetNewClosure()
    $dispatch={param($ctx);$fixture.dispatches++;if($fixture.behavior -ceq 'mutate-context'){$ctx.identity.session_id='other'};if($fixture.behavior -ceq 'lost'){throw 'Original dispatch result lost'};if($fixture.behavior -ceq 'native'){$fixture.page++};@{identity=$ctx.identity;correlation_id=$ctx.correlation_id;status='delivered'}}.GetNewClosure()
    $effect={param($ctx);@{identity=$ctx.identity;correlation_id=$ctx.correlation_id;status=$(if($fixture.page -eq 2){'confirmed'}else{'pending'});evidence_ref=$(if($fixture.page -eq 2){'fixture:original-panel-page-2'}else{''})}}.GetNewClosure()
    $release={param($ctx);$fixture.releases++;$fixture.releaseTargets+=,$ctx.identity.session_id;if($fixture.behavior -ceq 'release-fails'){throw 'Release acknowledgement lost'};@{identity=$ctx.identity;correlation_id=$ctx.correlation_id;status='released';overrides_active=$false;buttons_down=$false;evidence_ref='fixture:overrides-disabled-buttons-up'}}.GetNewClosure()
    $terminal={param($ctx);$fixture.terminals++;@{identity=$ctx.identity;correlation_id=$ctx.correlation_id;status='confirmed';evidence_ref='fixture:writer-closed-producer-ack-xr-retired'}}.GetNewClosure()
    @{fixture=$fixture;args=@{Selection=$Selected;Identity=$identity;ProxyOrigin='http://127.0.0.1:9710';InputKind='hand_pinch';ReadFrame=$read;DispatchInput=$dispatch;ObserveOriginalEffect=$effect;ReleaseInput=$release;ObserveTerminal=$terminal}}
}
$c=New-Consumer $selection;$cycleArgs=$c.args;$nativeResult=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($nativeResult.completed -and $c.fixture.page -eq 2 -and $c.fixture.dispatches -eq 1 -and $c.fixture.releases -eq 1 -and $c.fixture.terminals -eq 1) 'native-original-action-release-terminal'
Check (-not $nativeResult.device_verified -and -not $nativeResult.capture_confirmed -and -not $nativeResult.rendered_hands_confirmed) 'rehearsal-never-promotes-device-or-capture'
$c=New-Consumer $sdkSelection 'sdk';$c.args.InputKind='controller_select';$cycleArgs=$c.args;$sdkResult=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($sdkResult.dispatch_state -ceq 'delivered' -and $sdkResult.original_effect_state -ceq 'pending' -and -not $sdkResult.completed -and $c.fixture.releases -eq 1) 'sdk-delivery-does-not-imply-widget-effect'
$c=New-Consumer $selection 'lost';$cycleArgs=$c.args;$lost=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($lost.dispatch_state -ceq 'outcome_unknown' -and -not $lost.completed -and $c.fixture.dispatches -eq 1 -and $c.fixture.releases -eq 1 -and $c.fixture.terminals -eq 1) 'lost-original-dispatch-released-without-replay'
$c=New-Consumer $selection 'release-fails';$cycleArgs=$c.args;$unknown=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($unknown.release_state -ceq 'outcome_unknown' -and -not $unknown.completed -and $c.fixture.terminals -eq 1) 'terminal-does-not-clear-unknown-release'
$c=New-Consumer $selection 'mutate-context';$cycleArgs=$c.args;$mutated=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($mutated.dispatch_state -ceq 'outcome_unknown' -and $c.fixture.releaseTargets[0] -ceq 'session-9') 'callback-cannot-retarget-original-release'
$c=New-Consumer $selection;$c.args.ProxyOrigin='http://127.0.0.1:9710/sse';$cycleArgs=$c.args;$suffix=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($suffix.dispatch_state -ceq 'not_sent' -and $c.fixture.frame -eq 0 -and $c.fixture.dispatches -eq 0 -and $c.fixture.releases -eq 0 -and $c.fixture.terminals -eq 0) 'proxy-suffix-refused-before-input'
$c=New-Consumer $selection;$c.args.Identity.package='io.example.wrong';$cycleArgs=$c.args;$cross=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($cross.dispatch_state -ceq 'not_sent' -and $c.fixture.frame -eq 0 -and $c.fixture.dispatches -eq 0 -and $c.fixture.releases -eq 0 -and $c.fixture.terminals -eq 0) 'cross-app-readiness-denies-input'
$c=New-Consumer $selection;$fixedId=$c.args.Identity
$repeated={param($ctx);@{identity=$ctx.identity;backend_ready=$true;layer_loaded=$true;focused=$true;frame_index=1;source_timestamp_ns=[long]1000;clock_domain='xr'}}
$c.args.ReadFrame=$repeated;$cycleArgs=$c.args;$stale=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($stale.dispatch_state -ceq 'not_sent' -and $c.fixture.dispatches -eq 0) 'repeated-latest-frame-not-advancement'
$c=New-Consumer $selection
$c.args.ReadFrame={param($ctx);throw 'Read timeout is unknown, not a repeatable successful observation'}
$cycleArgs=$c.args;$timeout=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($timeout.dispatch_state -ceq 'not_sent' -and $timeout.errors.Count -eq 1) 'unknown-frame-read-not-retried'
# Regression through the imported module in this exact pwsh runtime, not a child
# stand-in; imports must not remove a consumer's independent command binding.
function Test-IndependentConsumerBinding { 'alive' }
Import-Module (Join-Path $RepoRoot 'tools/lib/MetaXrOperatorPackaging.psm1') -Force
Check ((Test-IndependentConsumerBinding) -ceq 'alive') 'consumer-runtime-binding-preserved'
$c=New-Consumer $selection;$c.args.Selection=@{enabled=$false};$cycleArgs=$c.args;$disabled=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($disabled.dispatch_state -ceq 'not_sent' -and $c.fixture.frame -eq 0 -and $c.fixture.dispatches -eq 0 -and $c.fixture.releases -eq 0 -and $c.fixture.terminals -eq 0) 'disabled-selection-zero-callbacks'
$c=New-Consumer $selection;$c.args.Identity.apk_sha256='bad';$cycleArgs=$c.args;$badId=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($badId.dispatch_state -ceq 'not_sent' -and $c.fixture.frame -eq 0 -and $c.fixture.dispatches -eq 0 -and $c.fixture.releases -eq 0 -and $c.fixture.terminals -eq 0) 'malformed-identity-zero-callbacks'
$c=New-Consumer $selection;$id=$c.args.Identity;$seq=@{count=0}
$c.args.ReadFrame={param($ctx);$seq.count++;$n=if($seq.count -le 2){1}else{2};@{identity=$ctx.identity;backend_ready=$true;layer_loaded=$true;focused=$true;frame_index=$n;source_timestamp_ns=([long]$n*1000);clock_domain='xr'}}.GetNewClosure()
$cycleArgs=$c.args;$slow=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($slow.completed -and $seq.count -eq 3) 'successful-repeated-snapshot-then-advance'
$c=New-Consumer $selection;$c.args.ReleaseInput={param($ctx);@{identity=$ctx.identity;correlation_id=$ctx.correlation_id;status='released';overrides_active=$true;buttons_down=$false;evidence_ref='fixture:open-hand-only'}}
$cycleArgs=$c.args;$openHand=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($openHand.release_state -ceq 'outcome_unknown' -and -not $openHand.completed) 'open-hand-not-disabled-override'
$c=New-Consumer $selection;$c.args.ObserveTerminal={param($ctx);@{identity=$ctx.identity;correlation_id=$ctx.correlation_id;status='confirmed';evidence_ref=''}}
$cycleArgs=$c.args;$noTerminal=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($noTerminal.terminal_state -ceq 'unknown' -and -not $noTerminal.completed) 'process-absence-not-terminal-owner-evidence'
Deny 'packaged-cross-app' { Assert-QuestMetaXrOperatorManifestDump -Selection $selection -Lines @($dump -replace 'io.example.native','io.example.other') -ExitCode 0 }
Deny 'selection-path-mutation' { $x=$selection|ConvertTo-Json -Depth 8|ConvertFrom-Json;$x.library.apk_path='../other.so';Copy-QuestMetaXrOperatorLayer -Selection $x -OutputRoot (Join-Path $OutputDirectory 'mutated') -NativeStageRoot (Join-Path $OutputDirectory 'mutated-native') }
Check (-not (Test-Path -LiteralPath (Join-Path $OutputDirectory 'mutated')) -and -not (Test-Path -LiteralPath (Join-Path $OutputDirectory 'mutated-native'))) 'path-tamper-zero-writes'
$copyPath=Join-Path $OutputDirectory 'other-layer.json';Copy-Item -LiteralPath $manifestPath -Destination $copyPath
$swapped=$selection|ConvertTo-Json -Depth 8|ConvertFrom-Json;$swapped.layer_manifest.source_path=$copyPath
Deny 'source-path-swap' { Copy-QuestMetaXrOperatorLayer -Selection $swapped -OutputRoot (Join-Path $OutputDirectory 'swapped') -NativeStageRoot (Join-Path $OutputDirectory 'swapped-native') }
Check (-not (Test-Path -LiteralPath (Join-Path $OutputDirectory 'swapped')) -and -not (Test-Path -LiteralPath (Join-Path $OutputDirectory 'swapped-native'))) 'source-swap-zero-writes'
$originalManifest=[IO.File]::ReadAllBytes($manifestPath);[IO.File]::AppendAllText($manifestPath,' ')
Deny 'second-artifact-drift' { Copy-QuestMetaXrOperatorLayer -Selection $selection -OutputRoot (Join-Path $OutputDirectory 'drift') -NativeStageRoot (Join-Path $OutputDirectory 'drift-native') }
Check (-not (Test-Path -LiteralPath (Join-Path $OutputDirectory 'drift')) -and -not (Test-Path -LiteralPath (Join-Path $OutputDirectory 'drift-native'))) 'second-artifact-drift-zero-writes'
[IO.File]::WriteAllBytes($manifestPath,$originalManifest)
Deny 'forged-archive-selection' { $x=$selection|ConvertTo-Json -Depth 8|ConvertFrom-Json;$x.library.sha256='f'*64;Assert-QuestMetaXrOperatorArchive -Selection $x -ApkPath $zip }
$c=New-Consumer $selection;$reads=@{count=0}
$c.args.ReadFrame={param($ctx);$reads.count++;@{identity=$ctx.identity;backend_ready=$true;layer_loaded=$true;focused=$false;frame_index=1;source_timestamp_ns=[long]1000;clock_domain='xr'}}.GetNewClosure()
$cycleArgs=$c.args;$unfocused=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($unfocused.dispatch_state -ceq 'not_sent' -and $reads.count -eq 1 -and $c.fixture.releases -eq 0) 'unfocused-observation-stops-before-more-callbacks'
$c=New-Consumer $selection;$reads=@{count=0}
$c.args.ReadFrame={param($ctx);$reads.count++;$ctx.identity.session_id='replacement';@{identity=$ctx.identity;backend_ready=$true;layer_loaded=$true;focused=$true;frame_index=$reads.count;source_timestamp_ns=([long]$reads.count*1000);clock_domain='xr'}}.GetNewClosure()
$cycleArgs=$c.args;$replacement=Invoke-QuestMetaXrInputRehearsal @cycleArgs
Check ($replacement.dispatch_state -ceq 'not_sent' -and $reads.count -eq 1 -and $c.fixture.dispatches -eq 0) 'replacement-session-never-adopted'
$result=[ordered]@{schema='rusty.quest.meta_xr_input.host_fixture.v1'    ;passed=@($passed);count=$passed.Count;native=$nativeResult;sdk=$sdkResult;unknown_dispatch=$lost;unknown_release=$unknown;apk_builds=0;device_operations=0;runtime_verified=$false}
$result|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $OutputDirectory 'result.json')
Write-Output "Meta XR input adapter: $($passed.Count) host conformance cases passed; no APK/device/network effects."
