param(
    [Parameter(Mandatory)][string]$RepoRoot,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$CompiledOwnerClassPath,
    [Parameter(Mandatory)][string]$HostJsonJar,
    [Parameter(Mandatory)][string]$RustCompiler,
    [Parameter(Mandatory)][string]$RustDependencyDirectory,
    [Parameter(Mandatory)][string]$SerdeJsonLibrary,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw 'output directory must be absent' }
$null = New-Item -ItemType Directory -Path $output
$suffix = if ($IsWindows) { '.exe' } else { '' }
$java = Join-Path $JavaHome "bin/java$suffix"
$javac = Join-Path $JavaHome "bin/javac$suffix"
$fixture = Join-Path $PSScriptRoot 'fixtures/embedded-duplex-diagnostic-seam'
$platform = Join-Path $repo 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexPlatform.java'
$bridge = Join-Path $repo 'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/java_bridge.rs'
$media = Join-Path $repo 'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media'
$sources = @($platform)
foreach ($name in @('PackagedAndroidMediaOwnerRegistry', 'MediaOwnerAction', 'MediaProductBinding', 'MediaProviderReadback', 'MediaOwnerProvider', 'CancellationHandle', 'MediaRuntimeSnapshot', 'AndroidMediaOwnerRegistry')) {
    $sources += Join-Path $media "$name.java"
}
$classes = Join-Path $output 'classes'
$null = New-Item -ItemType Directory -Path $classes
# Put the working host JSON implementation before Android's stub classes.
$classPath = "$HostJsonJar$([IO.Path]::PathSeparator)$CompiledOwnerClassPath"
& $javac --release 8 '-Xlint:all' -Werror -cp $classPath -d $classes @sources (Join-Path $fixture 'DiagnosticJsonProducer.java') 1> (Join-Path $output 'java-compile.stdout') 2> (Join-Path $output 'java-compile.stderr')
if ($LASTEXITCODE -ne 0) { throw 'actual diagnostic producer compilation failed' }
$payload = Join-Path $output 'producer.ndjson'
& $java -cp "$classes$([IO.Path]::PathSeparator)$classPath" 'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.DiagnosticJsonProducer' 1> $payload 2> (Join-Path $output 'java-test.stderr')
if ($LASTEXITCODE -ne 0) { throw 'actual Registry/classifier/getter producer failed' }
$bridgeText = [IO.File]::ReadAllText($bridge)
$consumer = [regex]::Matches($bridgeText, '(?ms)^    pub\(crate\) fn owner_failure_diagnostic\(&self\)[^\r\n]*\{.*?^    \}')
if ($consumer.Count -ne 1 -or $consumer[0].Value -notmatch 'parse_owner_failure_diagnostic\(&text\)') {
    throw 'actual JNI string consumer must delegate to the extracted parser'
}
# The named pure parser ends at a column-zero brace. Extract owner code, never a test mirror.
$matches = [regex]::Matches($bridgeText, '(?ms)^fn parse_owner_failure_diagnostic\(text: &str\)[^\r\n]*\{.*?^\}')
if ($matches.Count -ne 1) { throw 'expected exactly one actual named diagnostic parser' }
$parser = $matches[0].Value
$rustMain = Join-Path $output 'diagnostic-seam.rs'
[IO.File]::WriteAllText($rustMain, $parser + "`n" + [IO.File]::ReadAllText((Join-Path $fixture 'diagnostic_parser_main.rs')), [Text.UTF8Encoding]::new($false))
$executable = Join-Path $output "diagnostic-seam$suffix"
& $RustCompiler --edition=2021 -L "dependency=$RustDependencyDirectory" --extern "serde_json=$SerdeJsonLibrary" -o $executable $rustMain 1> (Join-Path $output 'rust-compile.stdout') 2> (Join-Path $output 'rust-compile.stderr')
if ($LASTEXITCODE -ne 0) { throw 'actual extracted diagnostic parser compilation failed' }
& $executable $payload 1> (Join-Path $output 'seam-test.stdout') 2> (Join-Path $output 'seam-test.stderr')
if ($LASTEXITCODE -ne 0) { throw 'actual diagnostic producer/consumer seam failed' }
$sourceHashes = @($sources + $bridge | ForEach-Object {
    [ordered]@{ path=[IO.Path]::GetRelativePath($repo, $_).Replace('\', '/'); sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() }
})
[ordered]@{
    status='passed'; scope='actual selected Java Registry/Action/Binding/Platform sources and extracted actual Rust closed parser'
    sources=$sourceHashes
    parser_sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($parser))).ToLowerInvariant()
    host_json_sha256=(Get-FileHash -LiteralPath $HostJsonJar -Algorithm SHA256).Hash.ToLowerInvariant()
    serde_json_sha256=(Get-FileHash -LiteralPath $SerdeJsonLibrary -Algorithm SHA256).Hash.ToLowerInvariant()
    producer_sha256=(Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash.ToLowerInvariant()
    physical_claim='none; mock foreign provider, reflection transfer into actual getter; no JNI/device/platform effects'
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
