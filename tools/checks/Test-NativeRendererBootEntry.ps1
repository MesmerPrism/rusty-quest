param([string]$RepoRoot = '.', [string]$JavaHome = $env:JAVA_HOME, [string]$AndroidHome = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$javaRoot = Join-Path $repo 'apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
$testRoot = Join-Path $repo 'apps/native-renderer-android/tests/java/io/github/mesmerprism/rustyquest/native_renderer'
$javac = Join-Path $JavaHome 'bin/javac.exe'
$java = Join-Path $JavaHome 'bin/java.exe'
$platform = Get-ChildItem -LiteralPath (Join-Path $AndroidHome 'platforms') -Directory -Filter 'android-*' | Sort-Object Name -Descending | Select-Object -First 1
if ($null -eq $platform) { throw 'Installed Android platform required.' }
$output = Join-Path ([IO.Path]::GetTempPath()) ('quest-boot-entry-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output | Out-Null
try {
    & $javac -encoding UTF-8 -cp (Join-Path $platform.FullName 'android.jar') -d $output `
        (Join-Path $javaRoot 'NativeRendererBootLaunchPolicy.java') `
        (Join-Path $javaRoot 'NativeRendererBootReceiver.java') `
        (Join-Path $testRoot 'NativeRendererBootLaunchPolicyTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'Actual Android boot receiver compilation failed.' }
    & $java -cp $output io.github.mesmerprism.rustyquest.native_renderer.NativeRendererBootLaunchPolicyTest
    if ($LASTEXITCODE -ne 0) { throw 'Boot admission controls failed.' }
    $feature = Get-Content -LiteralPath (Join-Path $repo 'fixtures/native-app-features/ui/experiment-boot-autostart/ui.experiment_boot_autostart.feature.json') -Raw | ConvertFrom-Json
    if ($feature.feature_id -cne 'ui.experiment_boot_autostart' -or
        @($feature.android_manifest.receivers).Count -ne 1 -or $feature.android_manifest.receivers[0] -cne 'NativeRendererBootReceiver' -or
        (@($feature.android_manifest.permissions | Sort-Object) -join '|') -cne 'android.permission.RECEIVE_BOOT_COMPLETED|android.permission.SYSTEM_ALERT_WINDOW' -or
        $feature.runtime_profile.set.'debug.rustyquest.native_renderer.experiment.preparation_mode' -cne 'remote-vr') {
        throw 'Selected boot feature closure differs.'
    }
    'Android receiver and exact selected boot feature passed; physical boot/foreground not claimed.'
} finally {
    # Only the exact task-owned temporary directory is disposable.
    $resolved = [IO.Path]::GetFullPath($output)
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $resolved.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path -Leaf $resolved) -notmatch '^quest-boot-entry-[0-9a-f]{32}$') { throw 'Temporary cleanup scope differs.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
