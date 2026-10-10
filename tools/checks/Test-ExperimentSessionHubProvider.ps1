[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputRoot,
      [Parameter(Mandatory)][string]$AndroidJar,
      [Parameter(Mandatory)][string]$JsonJar,
      [string]$ResolvedJavaSourceListPath,
      [string]$ResolvedSourceRepoRoot)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputRoot){throw 'Create-new output required'}
$null=New-Item -ItemType Directory $OutputRoot
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$pkg='io/github/mesmerprism/rustyquest/native_renderer'
$panel=Join-Path $repo "apps/native-renderer-android/panel-modules/breath-composition/src/main/java/$pkg"
$sources=@('ExperimentSessionPanelState','ExperimentSessionPanelCoordinator','ExperimentSessionPanelViewPolicy','ExperimentSessionStatusObservation','ExperimentSessionHubProvider','ExperimentSessionHubLifetime')|ForEach-Object{Join-Path $panel "$_.java"}
$sources+=Join-Path $repo 'crates/rusty-quest-broker-admission/android/io/github/mesmerprism/rustyquest/broker_admission/ConnectionHubAdmissionSessionReducer.java'
function RunHost($name,$exe,[string[]]$arguments){
 & $exe @arguments 1> (Join-Path $OutputRoot "$name.stdout") 2> (Join-Path $OutputRoot "$name.stderr")
 $code=$LASTEXITCODE
 [IO.File]::WriteAllText((Join-Path $OutputRoot "$name.exit"),[string]$code)
 if($code-ne0){throw "Host control failed: $name ($code)"}
 Get-Content (Join-Path $OutputRoot "$name.stdout")
}
$hostOut=Join-Path $OutputRoot 'host';$androidOut=Join-Path $OutputRoot 'android'
$null=New-Item -ItemType Directory $hostOut,$androidOut
$test=Join-Path $repo "apps/native-renderer-android/tests/java/$pkg/ExperimentSessionHubProviderTest.java"
$hubRoot=Join-Path $repo 'apps/manifold-broker-android/src/main/java/io/github/mesmerprism/rustymanifold/broker'
$hubSources=@('ConnectionHubProtocol','HubProviderIdentity','HubSurfaceDescriptor','HubSurfaceRegistry','ConnectionHubAuthorityPort','ConnectionHubStateStore','ConnectionHubRuntime')|ForEach-Object{Join-Path $hubRoot "$_.java"}
RunHost 'host-compile' 'javac' (@('--release','8','-cp',$JsonJar,'-d',$hostOut)+$sources+$hubSources+@($test,(Join-Path $repo "apps/native-renderer-android/tests/java/$pkg/ExperimentSessionHubLifetimeTest.java")))
RunHost 'host-tests' 'java' @('-cp',"$hostOut$([IO.Path]::PathSeparator)$JsonJar",'io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionHubProviderTest')
RunHost 'lifetime-tests' 'java' @('-cp',"$hostOut$([IO.Path]::PathSeparator)$JsonJar",'io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionHubLifetimeTest')

# Test the actual codec, not a synthesized already-accepted NativeReceipt.
$panelText=[IO.File]::ReadAllText((Join-Path $panel 'BreathCompositionPanelModule.java'))
$codecStart=$panelText.IndexOf('    private static final class ExperimentSessionJsonCodec')
$codecEnd=$panelText.IndexOf('    private final class SliderControl {',$codecStart)
if($codecStart-lt0-or$codecEnd-lt0){throw 'Production codec extraction missing'}
$codec=$panelText.Substring($codecStart,$codecEnd-$codecStart)
$template=Join-Path $repo "apps/native-renderer-android/tests/java/$pkg/ExperimentSessionStatusReadCodecTest.java"
$hostCodec=Join-Path $OutputRoot 'ExperimentSessionStatusReadCodecTest.java'
$templateText=[IO.File]::ReadAllText($template)
if(([regex]::Matches($templateText,'// PRODUCTION_CODEC')).Count-ne1){throw 'Codec insertion must be unique'}
[IO.File]::WriteAllText($hostCodec,$templateText.Replace('// PRODUCTION_CODEC',$codec),[Text.UTF8Encoding]::new($false))
$shell=Join-Path $panel 'ExperimentSessionAndroidShell.java'
$exitPolicy=Join-Path $repo "apps/native-renderer-android/src/main/java/$pkg/NativeRendererWriterAcknowledgedExitPolicy.java"
RunHost 'codec-compile' 'javac' @('--release','8','-cp',"$hostOut$([IO.Path]::PathSeparator)$JsonJar",'-d',$hostOut,$shell,$exitPolicy,$hostCodec)
RunHost 'codec-tests' 'java' @('-cp',"$hostOut$([IO.Path]::PathSeparator)$JsonJar",'io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionStatusReadCodecTest')
RunHost 'android-compile'  'javac' (@('--release','8','-cp',$AndroidJar,'-d',$androidOut)+$sources+@(Join-Path $panel 'ExperimentSessionHubSurfaceClient.java'))
if ($ResolvedJavaSourceListPath -or $ResolvedSourceRepoRoot) {
    if (-not $ResolvedJavaSourceListPath -or -not $ResolvedSourceRepoRoot) { throw 'Both resolved source inputs are required.' }
    # Use the complete real owner-emitted source list, including its generated
    # shell/config. Replace only the exact generator hook with this candidate's
    # hook; all tracked sources must exist in this candidate checkout.
    function ReadHubHook($root) {
        $builder = Get-Content -Raw -LiteralPath (Join-Path $root 'tools/Build-NativeRendererAndroid.ps1')
        $matches = [regex]::Matches($builder, '(?s)\$experimentSessionHubHook = @''\r?\n(.*?)\r?\n''@')
        if ($matches.Count -ne 1) { throw 'Expected one source-owned Hub generator hook.' }
        return $matches[0].Groups[1].Value.Replace("`r`n", "`n")
    }
    $oldRoot = [IO.Path]::GetFullPath($ResolvedSourceRepoRoot).TrimEnd('\','/')
    $oldHook = ReadHubHook $oldRoot; $newHook = ReadHubHook $repo
    $resolved = @(Get-Content -LiteralPath $ResolvedJavaSourceListPath | Where-Object { $_ })
    if ($resolved.Count -eq 0) { throw 'Empty resolved Java closure.' }
    $fullSources = @(); $shellCount = 0
    foreach ($path in $resolved) {
        $relative = [IO.Path]::GetRelativePath($oldRoot, [IO.Path]::GetFullPath($path))
        if ($relative.StartsWith('..') -or [IO.Path]::IsPathRooted($relative)) { throw 'Resolved Java source escapes owner checkout.' }
        if ($relative.Replace('\','/').StartsWith('target/')) {
            $text = Get-Content -Raw -LiteralPath $path
            $text = $text.Replace("`r`n", "`n")
            if ([IO.Path]::GetFileName($path) -ceq 'ControlPanelActivity.java') {
                if ([regex]::Matches($text, [regex]::Escape($oldHook)).Count -ne 1) { throw 'Real generated shell does not contain the exact old hook.' }
                $text = $text.Replace($oldHook, $newHook); $shellCount++
            } elseif ([IO.Path]::GetFileName($path) -cne 'GeneratedEmbeddedManifoldRuntimeConfig.java') { throw 'Unknown generated Java source.' }
            $destination = Join-Path $OutputRoot ([IO.Path]::GetFileName($path))
            [IO.File]::WriteAllText($destination, $text, [Text.UTF8Encoding]::new($false))
            $fullSources += $destination
        } else {
            $candidate = Join-Path $repo $relative
            if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw 'Resolved source missing from candidate.' }
            $fullSources += $candidate
        }
    }
    if ($shellCount -ne 1) { throw 'Exactly one real generated panel shell required.' }
    $fullOut = Join-Path $OutputRoot 'resolved-android'; $null = New-Item -ItemType Directory $fullOut
    $rsp = Join-Path $OutputRoot 'resolved-sources.rsp'; $fullSources | Set-Content -Encoding ASCII -LiteralPath $rsp
    RunHost 'resolved-android-compile' 'javac' @('-encoding','UTF-8','-source','1.8','-target','1.8','-bootclasspath',$AndroidJar,'-d',$fullOut,"@$rsp")
    @{sources=$fullSources; source_list_sha256=(Get-FileHash $ResolvedJavaSourceListPath).Hash.ToLowerInvariant(); scope='Complete actual resolved Java closure with exact candidate generator hook; Android bootstrap only, no APK/device proof'} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutputRoot 'RESOLVED_ANDROID_RESULT.json')
}
@{passed=$true;scope='Actual reducer/core driver host callbacks and real Android API typecheck; no Binder/device/grant/network/packaging proof';sources=@($sources)+$hubSources+@($test,(Join-Path $panel 'ExperimentSessionHubSurfaceClient.java'));android_jar_sha256=(Get-FileHash $AndroidJar).Hash.ToLowerInvariant();json_jar_sha256=(Get-FileHash $JsonJar).Hash.ToLowerInvariant()}|ConvertTo-Json -Depth 5|Set-Content (Join-Path $OutputRoot 'RESULT.json')
