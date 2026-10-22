param([string]$RepoRoot = "")

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}
$app = Join-Path $RepoRoot "apps\display-stream-android"
$manifest = Get-Content -Raw -LiteralPath (Join-Path $app "AndroidManifest.xml")
$sourceRoot = Join-Path $app "src\main\java\io\github\mesmerprism\rustyquest\displaystream"

foreach ($required in @(
    "DisplayStreamActivity.java",
    "DisplayStreamStimulusActivity.java",
    "DisplayStreamService.java",
    "DisplayStreamControlReceiver.java",
    "ShellDisplayMirrorMain.java",
    "HiddenDisplayMirror.java",
    "H264SurfaceEncoder.java",
    "Rmanvid1Writer.java")) {
    if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot $required) -PathType Leaf)) {
        throw "Missing display-stream source: $required"
    }
}
foreach ($token in @(
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    'android:foregroundServiceType="mediaProjection"',
    'android:exported="false"',
    'android:name=".DisplayStreamStimulusActivity"',
    'io.github.mesmerprism.rustyquest.displaystream.action.STOP')) {
    if (-not $manifest.Contains($token)) {
        throw "Display-stream manifest is missing: $token"
    }
}
$allSource = Get-ChildItem -LiteralPath $sourceRoot -Filter *.java |
    ForEach-Object { Get-Content -Raw -LiteralPath $_.FullName } |
    Out-String
foreach ($token in @(
    "RMANVID1",
    '"source_kind", "media_projection"',
    '"source_kind", "shell_display_mirror"',
    "android.view.SurfaceControl",
    "MediaProjectionManager",
    "SocketChannel",
    "GatheringByteChannel",
    "ByteBuffer payload",
    "MediaFormat.KEY_MAX_FPS_TO_ENCODER",
    "MediaFormat.KEY_PRIORITY",
    "MediaFormat.KEY_LATENCY",
    '"packet_io", "gathered_bytebuffer"')) {
    if (-not $allSource.Contains($token)) {
        throw "Display-stream source contract is missing: $token"
    }
}
foreach ($token in @(
    "RUSTY_DISPLAY_STREAM_STIMULUS",
    "rusty.quest.display_stream.stimulus.v1",
    "System.currentTimeMillis()",
    "handler.postDelayed(this, periodMs)")) {
    if (-not $allSource.Contains($token)) {
        throw "Display-stream freshness stimulus contract is missing: $token"
    }
}
if ($allSource.Contains("com.genymobile.scrcpy")) {
    throw "Display-stream sources must not embed or copy the scrcpy server package"
}
foreach ($forbidden in @(
    "byte[] payload",
    "output.flush()",
    "output.write((value >>>")) {
    if ($allSource.Contains($forbidden)) {
        throw "Display-stream hot path contains a forbidden allocation/write pattern: $forbidden"
    }
}
Write-Output "Display-stream Android static checks passed."
