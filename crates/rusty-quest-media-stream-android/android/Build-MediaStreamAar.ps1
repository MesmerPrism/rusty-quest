param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [Parameter(Mandatory)][string]$OutDir,
    [string]$GradleHome = $env:GRADLE_HOME,
    [int]$CompileSdkVersion = 36
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
$moduleRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$repoRoot = [IO.Path]::GetFullPath((Join-Path $moduleRoot '../../..'))
$outputRoot = [IO.Path]::GetFullPath($OutDir)
$separator = [IO.Path]::DirectorySeparatorChar
foreach ($inputRoot in @($moduleRoot, (Join-Path $repoRoot 'Cargo.lock'))) {
    if ($inputRoot.Equals($outputRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $inputRoot.StartsWith($outputRoot.TrimEnd('\','/') + $separator, [StringComparison]::OrdinalIgnoreCase) -or
        $outputRoot.StartsWith($inputRoot.TrimEnd('\','/') + $separator, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'AAR output must be disjoint from retained module inputs.'
    }
}
$cursor = $outputRoot
while ($cursor) {
    if (Test-Path -LiteralPath $cursor) {
        if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'AAR output must not traverse a reparse point.'
        }
    }
    $cursor = [IO.Path]::GetDirectoryName($cursor)
}
if (Test-Path -LiteralPath $outputRoot) { throw 'AAR output must be a new capsule; prior outputs are preserved.' }
$androidJar = Join-Path $AndroidHome "platforms/android-$CompileSdkVersion/android.jar"
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
foreach ($path in @($androidJar, $java, $javac)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required tool is missing: $path" }
}
if ($GradleHome) { $gradle = Join-Path $GradleHome 'bin/gradle.bat' }
else {
    $distribution = Join-Path $env:USERPROFILE '.gradle/wrapper/dists/gradle-8.7-bin'
    $matches = @(Get-ChildItem -LiteralPath $distribution -Recurse -File -Filter gradle.bat -ErrorAction SilentlyContinue)
    if ($matches.Count -ne 1) { throw 'Supply GradleHome for the already installed Gradle 8.7 distribution.' }
    $gradle = $matches[0].FullName
}
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) { throw 'Installed Gradle executable is missing.' }
[void][IO.Directory]::CreateDirectory($outputRoot)
function Hash-File([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Module-Inventory {
    $rows = @(& git -C $repoRoot ls-files --cached --others --exclude-standard -- 'crates/rusty-quest-media-stream-android/android')
    if ($LASTEXITCODE -ne 0) { throw 'Module Git inventory failed.' }
    @($rows | Sort-Object -Unique | ForEach-Object { [ordered]@{path=$_;sha256=(Hash-File (Join-Path $repoRoot $_))} })
}
$before = @(Module-Inventory)
$beforeText = $before | ConvertTo-Json -Compress -Depth 8
$previousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = [IO.Path]::GetFullPath($JavaHome)
    $version = @(& $gradle --offline --no-daemon --version 2>&1)
    if ($LASTEXITCODE -ne 0 -or ($version -join "`n") -notmatch '(?m)^Gradle 8\.7\s*$') { throw 'The AAR build requires installed Gradle 8.7.' }
    $version | Set-Content -LiteralPath (Join-Path $outputRoot 'gradle-version.txt') -Encoding utf8NoBOM
    $buildDir = Join-Path $outputRoot 'gradle-build'
    & $gradle --offline --no-daemon --console=plain --project-cache-dir (Join-Path $outputRoot 'gradle-cache') -p $moduleRoot "-PandroidJar=$androidJar" "-PmediaBuildDir=$buildDir" ':rusty-quest-media-stream-android:assembleRelease' *> (Join-Path $outputRoot 'gradle-build.log')
    if ($LASTEXITCODE -ne 0) { throw "AAR compilation failed; inspect $outputRoot/gradle-build.log" }
} finally { $env:JAVA_HOME = $previousJava }
$builtAar = Join-Path $buildDir 'outputs/aar/rusty-quest-media-stream-android-release.aar'
if (-not (Test-Path -LiteralPath $builtAar -PathType Leaf)) { throw 'Gradle did not produce the declared AAR.' }
$aarPath = Join-Path $outputRoot 'rusty-quest-media-stream-android.aar'
[IO.File]::Copy($builtAar, $aarPath, $false)
if ((Get-Item -LiteralPath $aarPath).Length -gt 64MB) { throw 'AAR exceeds its bounded library artifact size.' }
$archive = [IO.Compression.ZipFile]::OpenRead($aarPath)
try {
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in $archive.Entries) {
        if (-not $names.Add($entry.FullName) -or $entry.FullName -notin @('AndroidManifest.xml','classes.jar','R.txt')) {
            throw "Unexpected or duplicate media AAR entry: $($entry.FullName)"
        }
    }
    if (-not $names.Contains('AndroidManifest.xml') -or -not $names.Contains('classes.jar')) { throw 'Incomplete media AAR.' }
    $reader = [IO.StreamReader]::new($archive.GetEntry('AndroidManifest.xml').Open())
    try { [xml]$manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($manifest.SelectNodes('//uses-permission | //uses-permission-sdk-23 | //permission | //activity | //activity-alias | //service | //receiver | //provider').Count) {
        throw 'AAR contains host permissions or components.'
    }
    $sdk=$manifest.SelectSingleNode('/manifest/uses-sdk')
    if ($null -eq $sdk -or $sdk.GetAttribute('minSdkVersion','http://schemas.android.com/apk/res/android') -cne '29') { throw 'AAR must declare minSdk 29.' }
    $classesJar = Join-Path $outputRoot 'classes.jar'
    [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'), $classesJar, $false)
} finally { $archive.Dispose() }
$classes = [IO.Compression.ZipFile]::OpenRead($classesJar)
try {
    if ($null -eq $classes.GetEntry('io/github/mesmerprism/rustyquest/media/AndroidMediaOwnerRegistry.class')) { throw 'AAR omits its public registry.' }
    foreach ($entry in $classes.Entries) {
        if ($entry.FullName -match '(?i)(\.so$|rustymanifold/broker/|media/conformance/)') { throw 'AAR contains host or native implementation.' }
        if ($entry.FullName.EndsWith('.class')) {
            $stream = $entry.Open()
            try { $header = [byte[]]::new(8); $stream.ReadExactly($header) } finally { $stream.Dispose() }
            if (($header[6] * 256 + $header[7]) -ne 52) { throw 'AAR must contain Java 8 bytecode.' }
        }
    }
} finally { $classes.Dispose() }
if ((@(Module-Inventory) | ConvertTo-Json -Compress -Depth 8) -cne $beforeText) { throw 'Module source changed during AAR compilation.' }
$receipt = [ordered]@{
    schema='rusty.quest.android.media.aar_build.v1'; status='pass'; timestamp=[DateTimeOffset]::UtcNow.ToString('o')
    source_revision=(& git -C $repoRoot rev-parse HEAD).Trim(); source_files=$before
    aar_path=$aarPath; aar_sha256=(Hash-File $aarPath); classes_jar_path=$classesJar; classes_jar_sha256=(Hash-File $classesJar)
    tools=@(@{name='gradle-8.7';path=$gradle;sha256=(Hash-File $gradle)},@{name='java';path=$java;sha256=(Hash-File $java)},@{name='javac';path=$javac;sha256=(Hash-File $javac)},@{name="android-$CompileSdkVersion";path=$androidJar;sha256=(Hash-File $androidJar)})
    permissions=@(); host_components=@(); native_libraries=@(); java_class_version=52; device_execution=$false
}
$receiptPath = Join-Path $outputRoot 'aar-build.json'
$receipt | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $receiptPath -Encoding utf8NoBOM
[pscustomobject]$receipt
