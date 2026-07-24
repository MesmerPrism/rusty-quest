param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$AndroidNdkHome = $env:ANDROID_NDK_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutDir = "",
    [string]$Keystore = "",
    [switch]$ReplaceExistingOutput
)

$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "lib\SourceComposition.psm1") -Force
if ($PSVersionTable.PSEdition -ne "Core" -or
    $PSVersionTable.PSVersion -lt [version]"7.6") {
    throw "Fleet Agent APK builds require PowerShell 7.6 Core or newer."
}

function Get-LatestDirectory([string]$Parent, [string]$Pattern) {
    $item = Get-ChildItem -LiteralPath $Parent -Directory -Filter $Pattern |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $item) {
        throw "No directory matching $Pattern under $Parent"
    }
    return $item.FullName
}

function Invoke-Checked([string]$Name, [string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

if ([string]::IsNullOrWhiteSpace($AndroidHome)) {
    throw "ANDROID_HOME or -AndroidHome is required."
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "JAVA_HOME or -JavaHome is required."
}
if ([string]::IsNullOrWhiteSpace($AndroidNdkHome)) {
    $AndroidNdkHome = Join-Path $AndroidHome "ndk\27.2.12479018"
}
if (-not (Test-Path -LiteralPath $AndroidNdkHome -PathType Container)) {
    throw "Android NDK not found: $AndroidNdkHome"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$appRoot = Join-Path $repoRoot "apps\fleet-agent-android"
$targetRoot = Join-Path $repoRoot "target"
$sourceComposition = Get-QuestBuildSourceComposition `
    -RepoRoot $repoRoot `
    -PackageName @("rusty-quest-fleet-agent-android-native")
$primarySource = @($sourceComposition.repositories |
    Where-Object { $_.repository_id -eq "rusty-quest" })
if ($primarySource.Count -ne 1) {
    throw "Fleet Agent source composition must contain one Rusty Quest primary repository."
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $targetRoot (
        "fleet-agent-android\builds\fleet-agent\" +
        $sourceComposition.fingerprint.Substring(0, 16) + "\" +
        $primarySource[0].commit.Substring(0, 12))
}
$targetFull = [System.IO.Path]::GetFullPath($targetRoot).TrimEnd("\")
$outFull = [System.IO.Path]::GetFullPath($OutDir).TrimEnd("\")
if (-not $outFull.StartsWith($targetFull + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutDir must remain under the repo target directory: $outFull"
}

$buildTools = Get-LatestDirectory (Join-Path $AndroidHome "build-tools") "*"
$platformRoot = Get-LatestDirectory (Join-Path $AndroidHome "platforms") "android-*"
$platformJar = Join-Path $platformRoot "android.jar"
$aapt2 = Join-Path $buildTools "aapt2.exe"
$d8 = Join-Path $buildTools "d8.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$javac = Join-Path $JavaHome "bin\javac.exe"
$jar = Join-Path $JavaHome "bin\jar.exe"
$keytool = Join-Path $JavaHome "bin\keytool.exe"
$androidClang = Join-Path $AndroidNdkHome "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android29-clang.cmd"
$androidAr = Join-Path $AndroidNdkHome "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-ar.exe"
foreach ($tool in @(
    $platformJar,
    $aapt2,
    $d8,
    $zipalign,
    $apksigner,
    $javac,
    $jar,
    $keytool,
    $androidClang,
    $androidAr)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required tool not found: $tool"
    }
}

if ((Test-Path -LiteralPath $outFull) -and -not $ReplaceExistingOutput) {
    $existingCapsule = Join-Path $outFull "run-capsule.json"
    $existingApk = Join-Path $outFull "rusty-quest-fleet-agent.apk"
    if ((Test-Path -LiteralPath $existingCapsule -PathType Leaf) -and
        (Test-Path -LiteralPath $existingApk -PathType Leaf)) {
        & pwsh -NoProfile -ExecutionPolicy Bypass -File `
            (Join-Path $repoRoot "tools\Test-ApkRunCapsule.ps1") `
            -Path $existingCapsule | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Existing Fleet Agent run capsule is invalid."
        }
        Write-Output $existingApk
        return
    }
    throw "Fleet Agent content address already exists without a valid reusable capsule: $outFull"
}
if (Test-Path -LiteralPath $outFull) {
    $resolvedOut = (Resolve-Path -LiteralPath $outFull).Path
    if (-not $resolvedOut.StartsWith($targetFull + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove output outside target: $resolvedOut"
    }
    Remove-Item -LiteralPath $resolvedOut -Recurse -Force
}

$classesDir = Join-Path $outFull "classes"
$dexDir = Join-Path $outFull "dex"
$nativeRoot = Join-Path $outFull "native-package"
$nativeAbi = Join-Path $nativeRoot "lib\arm64-v8a"
New-Item -ItemType Directory -Force -Path $classesDir, $dexDir, $nativeAbi | Out-Null

$buildLockPath = Join-Path $outFull "build-lock.json"
$buildLock = [ordered]@{
    schema = "rusty.quest.fleet_agent_android.build_lock.v1"
    app_id = "fleet_agent"
    package_name = "io.github.mesmerprism.rustyquest.fleetagent"
    source_composition_fingerprint = $sourceComposition.fingerprint
    source_packages = @($sourceComposition.packages)
    source_repositories = @($sourceComposition.repositories)
    fleet_contract_revision = "8181683be4a3abbc5daa0c4497c7aeb9e76316a8"
    default_activation = "inert"
    foreground_service_type = "dataSync"
    permissions = @(
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.INTERNET",
        "android.permission.POST_NOTIFICATIONS"
    )
    property_manifest = $null
    runtime_profile = $null
}
$buildLock |
    ConvertTo-Json -Depth 12 |
    Set-Content -Encoding UTF8 -LiteralPath $buildLockPath

$sources = @(Get-ChildItem -LiteralPath (Join-Path $appRoot "src\main\java") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) {
    throw "No Fleet Agent Java sources found."
}
$sourceList = Join-Path $outFull "sources.rsp"
$sources | Set-Content -Encoding ASCII -LiteralPath $sourceList
$classesJar = Join-Path $outFull "classes.jar"
Invoke-Checked "javac" $javac @(
    "-encoding", "UTF-8",
    "-source", "1.8",
    "-target", "1.8",
    "-bootclasspath", $platformJar,
    "-d", $classesDir,
    "@$sourceList")
Invoke-Checked "jar" $jar @("cf", $classesJar, "-C", $classesDir, ".")
Invoke-Checked "d8" $d8 @("--lib", $platformJar, "--output", $dexDir, $classesJar)

$oldLinker = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
$oldCc = $env:CC_aarch64_linux_android
$oldAr = $env:AR_aarch64_linux_android
try {
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $androidClang
    $env:CC_aarch64_linux_android = $androidClang
    $env:AR_aarch64_linux_android = $androidAr
    Push-Location $repoRoot
    try {
        Invoke-Checked "Fleet Agent Android native bridge" "cargo" @(
            "build",
            "--locked",
            "--target", "aarch64-linux-android",
            "-p", "rusty-quest-fleet-agent-android-native")
    } finally {
        Pop-Location
    }
} finally {
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $oldLinker
    $env:CC_aarch64_linux_android = $oldCc
    $env:AR_aarch64_linux_android = $oldAr
}

$nativeSource = Join-Path $repoRoot "target\aarch64-linux-android\debug\librusty_quest_fleet_agent_android.so"
if (-not (Test-Path -LiteralPath $nativeSource -PathType Leaf)) {
    throw "Fleet Agent native library missing: $nativeSource"
}
$nativePackaged = Join-Path $nativeAbi "librusty_quest_fleet_agent_android.so"
Copy-Item -LiteralPath $nativeSource -Destination $nativePackaged

$unsignedApk = Join-Path $outFull "rusty-quest-fleet-agent-unsigned.apk"
$unalignedApk = Join-Path $outFull "rusty-quest-fleet-agent-unaligned.apk"
$alignedApk = Join-Path $outFull "rusty-quest-fleet-agent-aligned.apk"
$signedApk = Join-Path $outFull "rusty-quest-fleet-agent.apk"
Invoke-Checked "aapt2" $aapt2 @(
    "link",
    "-o", $unsignedApk,
    "--debug-mode",
    "--manifest", (Join-Path $appRoot "AndroidManifest.xml"),
    "-I", $platformJar,
    "--min-sdk-version", "29",
    "--target-sdk-version", "34",
    "--version-code", "1",
    "--version-name", "0.1.0")
Copy-Item -LiteralPath $unsignedApk -Destination $unalignedApk
Invoke-Checked "dex package" $jar @("uf", $unalignedApk, "-C", $dexDir, "classes.dex")
Invoke-Checked "native package" $jar @("uf", $unalignedApk, "-C", $nativeRoot, "lib")
Invoke-Checked "zipalign" $zipalign @("-f", "4", $unalignedApk, $alignedApk)

if ([string]::IsNullOrWhiteSpace($Keystore)) {
    $Keystore = Join-Path $targetRoot "rusty-quest-fleet-agent-debug.keystore"
}
if (-not (Test-Path -LiteralPath $Keystore -PathType Leaf)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Keystore) | Out-Null
    Invoke-Checked "keytool" $keytool @(
        "-genkeypair",
        "-keystore", $Keystore,
        "-storepass", "android",
        "-keypass", "android",
        "-alias", "androiddebugkey",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=Rusty Quest Fleet Agent,O=Rusty Quest,C=US")
}
Invoke-Checked "apksigner" $apksigner @(
    "sign",
    "--ks", $Keystore,
    "--ks-pass", "pass:android",
    "--key-pass", "pass:android",
    "--out", $signedApk,
    $alignedApk)

$buildManifest = [ordered]@{
    schema = "rusty.quest.fleet_agent_android.build.v1"
    package_name = "io.github.mesmerprism.rustyquest.fleetagent"
    activity = "io.github.mesmerprism.rustyquest.fleetagent/.FleetAgentActivity"
    service = "io.github.mesmerprism.rustyquest.fleetagent/.FleetAgentService"
    explicit_start_action = "io.github.mesmerprism.rustyquest.fleetagent.START"
    explicit_stop_action = "io.github.mesmerprism.rustyquest.fleetagent.STOP"
    fleet_contract_revision = "8181683be4a3abbc5daa0c4497c7aeb9e76316a8"
    default_activation = "inert"
    foreground_service_type = "dataSync"
    permissions = @(
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.INTERNET",
        "android.permission.POST_NOTIFICATIONS"
    )
    cleartext_runtime_scope = "loopback-link-local-rfc1918-only"
    adb_runtime_dependency = $false
    package_visibility_requested = $false
    media_enabled = $false
    command_listener_enabled = $false
    offline_queue_enabled = $false
    apk_path = $signedApk
    apk_sha256 = (Get-FileHash -LiteralPath $signedApk -Algorithm SHA256).Hash.ToLowerInvariant()
    native_library_sha256 = (Get-FileHash -LiteralPath $nativePackaged -Algorithm SHA256).Hash.ToLowerInvariant()
    source_composition_fingerprint = $sourceComposition.fingerprint
    source_commit = $primarySource[0].commit
    source_tree = $primarySource[0].tree
    build_lock_sha256 = (Get-FileHash -LiteralPath $buildLockPath -Algorithm SHA256).Hash.ToLowerInvariant()
}
$buildManifestPath = Join-Path $outFull "build-manifest.json"
$buildManifest |
    ConvertTo-Json -Depth 8 |
    Set-Content -Encoding UTF8 -LiteralPath $buildManifestPath

$sourceDependencies = @($sourceComposition.repositories |
    Where-Object { $_.repository_id -ne "rusty-quest" })
$runCapsule = [ordered]@{
    schema = "rusty.quest.apk_run_capsule.v1"
    capsule_id = "capsule.fleet-agent.$($sourceComposition.fingerprint.Substring(0, 16))"
    app_id = "fleet_agent"
    app_lane = "fleet-agent-android"
    source = [ordered]@{
        repository_id = $primarySource[0].repository_id
        role = $primarySource[0].role
        repository = $primarySource[0].repository
        commit = $primarySource[0].commit
        tree = $primarySource[0].tree
        tracked_worktree_clean = $true
        composition_fingerprint = $sourceComposition.fingerprint
        packages = @($sourceComposition.packages)
        dependencies = $sourceDependencies
    }
    build_lock = [ordered]@{
        path = $buildLockPath
        sha256 = (Get-FileHash -LiteralPath $buildLockPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    build_manifest = [ordered]@{
        path = $buildManifestPath
        sha256 = (Get-FileHash -LiteralPath $buildManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    apk = [ordered]@{
        path = $signedApk
        sha256 = (Get-FileHash -LiteralPath $signedApk -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    runtime_profile = $null
    property_manifest = $null
    android = [ordered]@{
        package_name = "io.github.mesmerprism.rustyquest.fleetagent"
        activity = "io.github.mesmerprism.rustyquest.fleetagent/.FleetAgentActivity"
        service = "io.github.mesmerprism.rustyquest.fleetagent/.FleetAgentService"
    }
    cleanup = [ordered]@{
        policy = "always-force-stop-and-restore-exact-property-snapshot"
        serial_exclusive_mutex = $true
        restore_on_failure = $true
        declared_property_count = 0
        remove_app_private_test_inputs = $true
    }
}
$runCapsulePath = Join-Path $outFull "run-capsule.json"
$runCapsule |
    ConvertTo-Json -Depth 12 |
    Set-Content -Encoding UTF8 -LiteralPath $runCapsulePath
& pwsh -NoProfile -ExecutionPolicy Bypass -File `
    (Join-Path $repoRoot "tools\Test-ApkRunCapsule.ps1") `
    -Path $runCapsulePath | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Generated Fleet Agent run capsule failed validation."
}

Write-Output $signedApk
