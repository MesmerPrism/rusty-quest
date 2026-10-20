[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$AndroidJar,
    [Parameter(Mandatory)][string]$OutputRoot
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$java=Join-Path $JavaHome 'bin/java.exe'
$javac=Join-Path $JavaHome 'bin/javac.exe'
foreach($path in @($java,$javac,$AndroidJar)){
    if(-not [IO.Path]::IsPathRooted($path) -or -not (Test-Path -LiteralPath $path -PathType Leaf)){throw 'Existing absolute Java and Android jar paths required.'}
}
if(-not [IO.Path]::IsPathRooted($OutputRoot) -or (Test-Path -LiteralPath $OutputRoot)){throw 'Fresh absolute output root required.'}
$null=New-Item -ItemType Directory -Path $OutputRoot
$classes=Join-Path $OutputRoot 'classes'
$null=New-Item -ItemType Directory -Path $classes
$package='io/github/mesmerprism/rustyquest/native_renderer'
$sourceRoot=Join-Path $repo 'apps/native-renderer-android/src/main/java'
$testRoot=Join-Path $repo 'apps/native-renderer-android/tests/java'
$sources=@('NativeMeshUiProducer','NativePageSnapshot','NativePanelFont' | ForEach-Object {Join-Path $sourceRoot ($package+'/'+$_+'.java')})
$tests=@('NativePageSnapshotTest','NativePageFontTest','NativeMeshPanelInputTest','NativePanelOwnerAttachmentTest')
$testSources=@($tests|ForEach-Object {Join-Path $testRoot ($package+'/'+$_+'.java')})
$started=[DateTime]::UtcNow
& $javac '--release' '8' '-encoding' 'UTF-8' '-cp' $AndroidJar '-sourcepath' $sourceRoot '-d' $classes @sources @testSources *> (Join-Path $OutputRoot 'android-typecheck.log')
if($LASTEXITCODE -ne 0){throw 'Native page Android API typecheck failed; inspect android-typecheck.log.'}
$classpath=$classes+[IO.Path]::PathSeparator+$AndroidJar
foreach($test in $tests){
    $arguments=@('-cp',$classpath,('io.github.mesmerprism.rustyquest.native_renderer.'+$test))
    if($test -ceq 'NativePageSnapshotTest'){$arguments+=@(Join-Path $OutputRoot 'neutral-interop.npg1')}
    & $java @arguments *> (Join-Path $OutputRoot ($test+'.log'))
    if($LASTEXITCODE -ne 0){throw "Native page fixture failed: $test"}
    Get-Content -LiteralPath (Join-Path $OutputRoot ($test+'.log'))
}
$pins=@(($sources+$testSources+@($PSCommandPath,$java,$javac,$AndroidJar))|ForEach-Object {
    [ordered]@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}
})
[ordered]@{passed=$true;test_mains=$tests;new_assertions=37;elapsed_seconds=([DateTime]::UtcNow-$started).TotalSeconds;
    scope='Actual production packet, immutable publication, lifetime/clip helpers, page font admission and SDF CPU tests, original InputConnection callback wrapper/owner helper regressions, and Android API typecheck only. No real View layout, TextShaper, system IME, Vulkan, or device claim.';
    neutral_interop_fixture='neutral-interop.npg1';inputs=$pins}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $OutputRoot 'RESULT.json') -Encoding utf8
Write-Output 'Native page focused host fixtures and Android typecheck PASS (4 test mains, 37 new assertions).'
