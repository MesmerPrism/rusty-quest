[CmdletBinding()]
param(
    [string]$RepoRoot = (Join-Path $PSScriptRoot '..'),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$GradlePath = $env:RUSTY_QUEST_GRADLE_PATH,
    [string]$HostJsonJar = $env:RUSTY_QUEST_HOST_JSON_JAR,
    [string]$JvmInputSpecPath = $env:RUSTY_QUEST_FRESH_JVM_INPUT_SPEC,
    [string]$OutputRoot
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
if (-not $JavaHome -or -not $AndroidHome -or -not $GradlePath -or -not $JvmInputSpecPath) {
    throw 'Select the installed JDK, Android SDK, Gradle and pinned JVM tool spec using parameters or JAVA_HOME/ANDROID_HOME/RUSTY_QUEST_GRADLE_PATH/RUSTY_QUEST_FRESH_JVM_INPUT_SPEC.'
}
if (-not $HostJsonJar) {
    $cache = Join-Path $HOME '.gradle/caches/modules-2/files-2.1/org.json/json/20240303'
    $jars = @(Get-ChildItem -LiteralPath $cache -Filter json-20240303.jar -File -Recurse -ErrorAction SilentlyContinue)
    if ($jars.Count -eq 1) { $HostJsonJar = $jars[0].FullName }
}
if (-not $HostJsonJar -or (Get-FileHash -LiteralPath $HostJsonJar).Hash.ToLowerInvariant() -cne '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed') {
    throw 'Select pinned org.json 20240303 using -HostJsonJar or RUSTY_QUEST_HOST_JSON_JAR.'
}
if (-not $OutputRoot) { $OutputRoot = Join-Path $RepoRoot ('target/fresh-source-' + [guid]::NewGuid().ToString('N')) }
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$target = [IO.Path]::GetFullPath((Join-Path $RepoRoot target))
if (-not $OutputRoot.StartsWith($target + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $OutputRoot)) {
    throw 'Select a create-new output directory beneath the repository target directory.'
}
[void](New-Item -ItemType Directory -Path $OutputRoot)
function Run([string]$Name, [string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments *> (Join-Path $OutputRoot "$Name.log")
    if ($LASTEXITCODE -ne 0) {
        Get-Content -LiteralPath (Join-Path $OutputRoot "$Name.log") -Tail 80
        throw "Fresh source qualification failed: $Name"
    }
    Write-Output "PASS $Name"
}
$names = @('PATH','JAVA_HOME','ANDROID_HOME','ANDROID_NDK_HOME','GLSLC','RQ_FRESH_BUILD_POLICY_VECTOR_OUTPUT',
    'RUSTY_QUEST_SPATIAL_BUILD_ROOT','RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR','RUSTY_QUEST_SPATIAL_APP_BUILD_DIR',
    'RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_MEDIA_POLICY','RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_SETUP_POLICY',
    'RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_POLICY_SHA256','RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256',
    'RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256','RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64')
$old = @{}
foreach ($name in $names) { $old[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
function Env([string]$Name, [AllowNull()][string]$Value) { [Environment]::SetEnvironmentVariable($Name, $Value, 'Process') }
function Pin([string]$Path) {
    [ordered]@{ path=[IO.Path]::GetFullPath($Path); sha256=(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant() }
}
Push-Location $RepoRoot
try {
    Env JAVA_HOME $JavaHome
    Env PATH ((Join-Path $JavaHome bin) + [IO.Path]::PathSeparator + $old.PATH)
    Env ANDROID_HOME $AndroidHome
    Env ANDROID_NDK_HOME (Join-Path $AndroidHome 'ndk/27.2.12479018')
    Env GLSLC (Join-Path $env:ANDROID_NDK_HOME 'shader-tools/windows-x86_64/glslc.exe')
    Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_MEDIA_POLICY finite
    Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_SETUP_POLICY none
    foreach ($name in $names | Where-Object { $_ -match 'FRESH_(POLICY|ENROLLMENT)' }) { Env $name '' }
    $vectorPath = Join-Path $OutputRoot policy-vector.json
    Env RQ_FRESH_BUILD_POLICY_VECTOR_OUTPUT $vectorPath
    # Exercise this package's actual owners and the short retained finite path.
    # The unchanged long ongoing history campaign has its own retained evidence.
    $brokerFilters = [ordered]@{
        policy='runtime::fresh_build_policy_projection_controls::actual_typed_factory_and_canonical_projection_controls'
        construction='construction_'
        selected='selected_fresh_actual_initializer_preserves_same_owner_and_root_registration'
        rejection='actual_admission_constructor_failure_retains_control_adapter_and_peer'
        malformed='malformed_factory_source_does_not_fabricate_created_owners'
        roots='source_bound_roots'
        finite='finite_thirty_minute_setup_roots_complete_real_first_and_later_coupled_renewals'
    }
    foreach ($entry in $brokerFilters.GetEnumerator()) {
        Run ("broker-" + $entry.Key) cargo @('test','--locked','-p','rusty-quest-broker-authority','--lib',$entry.Value)
    }
    Run prepared-media cargo @('test','--locked','-p','rusty-quest-media-stream-android','--lib')
    Run native-host cargo @('test','--locked','-p','spatial-camera-panel-native-receipt','--lib')
    Run policy pwsh @('-NoProfile','-File','tools/checks/Test-EmbeddedDuplexFreshSetupPolicy.ps1',
        '-RepoRoot',$RepoRoot,'-Mode','Full','-OutputRoot',(Join-Path $OutputRoot policy),
        '-NativePolicyProjectionPath',$vectorPath,'-JavaHome',$JavaHome,'-JsonJar',$HostJsonJar)
    $vector = Get-Content -Raw -LiteralPath $vectorPath | ConvertFrom-Json -Depth 64 -DateKind String
    $selection = [Text.UTF8Encoding]::new($false).GetBytes($vector.enrollment_selection_canonical_json)
    $rawSha = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($selection)).ToLowerInvariant()
    foreach ($mode in @('finite','ongoing','fresh')) {
        Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_MEDIA_POLICY $(if ($mode -eq 'finite') { 'finite' } else { 'ongoing-v2' })
        Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_SETUP_POLICY $(if ($mode -eq 'fresh') { 'source-bound-v2' } else { 'none' })
        Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_POLICY_SHA256 $(if ($mode -eq 'fresh') { $vector.projection.policy_sha256 } else { '' })
        Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256 $(if ($mode -eq 'fresh') { $vector.projection.enrollment_selection_sha256 } else { '' })
        Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256 $(if ($mode -eq 'fresh') { $rawSha } else { '' })
        Env RUSTY_QUEST_SPATIAL_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64 $(if ($mode -eq 'fresh') { [Convert]::ToBase64String($selection) } else { '' })
        Run "native-$mode" cargo @('check','--locked','--tests','--target','aarch64-linux-android','-p','spatial-camera-panel-native-receipt')
        $buildRoot = Join-Path $OutputRoot "android-$mode"
        Env RUSTY_QUEST_SPATIAL_BUILD_ROOT $buildRoot
        Env RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR (Join-Path $buildRoot root)
        Env RUSTY_QUEST_SPATIAL_APP_BUILD_DIR (Join-Path $buildRoot app)
        Run "android-$mode" $GradlePath @('-p','apps/spatial-camera-panel-android','--offline','--no-daemon','--build-cache',':app:compileDebugKotlin',':app:testDebugUnitTest')
    }
    $finiteApp = Join-Path $OutputRoot 'android-finite/app'
    $finiteMedia = Join-Path $OutputRoot 'android-finite/media-stream-android'
    $mediaClasses = Join-Path $finiteMedia 'classes/java/main'
    $mediaJar = Join-Path $finiteMedia 'aar-staging/classes.jar'
    $jvmSpec = Get-Content -Raw -LiteralPath $JvmInputSpecPath | ConvertFrom-Json -Depth 64 -DateKind String
    $toolLibraries = @($jvmSpec.jvm.classpath | Where-Object { $_.path -match 'json-20240303\.jar$|kotlin-stdlib-[^/\\]+\.jar$|android\.jar$' })
    foreach ($row in @($jvmSpec.jvm.compiler_jars) + $toolLibraries) {
        if ((Get-FileHash -LiteralPath $row.path).Hash.ToLowerInvariant() -cne $row.sha256) { throw 'Pinned JVM tool input changed.' }
    }
    $jvmSpec.tools.java = Join-Path $JavaHome 'bin/java.exe'
    $jvmSpec.tools.javac = Join-Path $JavaHome 'bin/javac.exe'
    # Use current compiled support. Earlier app artifacts supply no campaign classes.
    $libraries = $toolLibraries
    $libraries += @(Pin (Join-Path $finiteApp 'intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar'))
    $libraries += @(Pin (Join-Path $OutputRoot 'android-finite/spatial-sdk-shared/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar'))
    $jvmSpec.jvm.classpath = $libraries
    $jvmSpec.support_pins = @(Get-ChildItem -LiteralPath $mediaClasses -Filter '*.class' -File -Recurse | ForEach-Object { Pin $_.FullName })
    $currentSpec = Join-Path $OutputRoot campaign-inputs.json
    $jvmSpec | ConvertTo-Json -Depth 64 | Set-Content -LiteralPath $currentSpec -Encoding utf8NoBOM
    $mediaReceipt = Join-Path $OutputRoot media-classes.json
    [ordered]@{ classes_jar_sha256=(Get-FileHash -LiteralPath $mediaJar).Hash.ToLowerInvariant(); scope='Actual source-compiled media classes; no APK.' } |
        ConvertTo-Json | Set-Content -LiteralPath $mediaReceipt -Encoding utf8NoBOM
    foreach ($campaign in @('PreparedSdk','PreparedCaptureReadiness','PreparedSettledCustody')) {
        Run $campaign python @("tools/checks/Test-${campaign}Host.py",'--output',(Join-Path $OutputRoot $campaign),'--json-jar',$HostJsonJar)
    }
    Run renderer-contract python @('tools/checks/Test-PreparedRendererContractHost.py','--output',(Join-Path $OutputRoot renderer),
        '--json-jar',$HostJsonJar,'--android-jar',(Join-Path $AndroidHome 'platforms/android-34/android.jar'))
    Run prepared-signing python @('tools/checks/Test-PreparedStartSigningHost.py','--output',(Join-Path $OutputRoot signing))
    Run prepared-transport python @('tools/checks/Test-PreparedTransportHost.py','--output',(Join-Path $OutputRoot transport),
        '--json-jar',$HostJsonJar,'--android-jar',(Join-Path $AndroidHome 'platforms/android-34/android.jar'),
        '--media-classes',$mediaJar,'--media-artifacts',$mediaReceipt)
    Run second-lifetime python @('tools/checks/Test-PreparedSecondLifetimeHost.py','--output',(Join-Path $OutputRoot second-lifetime),
        '--input-spec',$currentSpec,'--support-classes',$mediaClasses)
    Run fresh-lifecycle python @('tools/checks/Test-FreshPairLifecycleHost.py','--output',(Join-Path $OutputRoot fresh-lifecycle),
        '--input-spec',$currentSpec,'--support-classes',$mediaClasses,'--json-jar',$HostJsonJar)
    $support = @((Join-Path $finiteApp 'tmp/kotlin-classes/debug'),
        (Join-Path $finiteApp 'intermediates/javac/debug/compileDebugJavaWithJavac/classes'),
        (Join-Path $AndroidHome 'platforms/android-34/android.jar')) -join [IO.Path]::PathSeparator
    Run originals python @('tools/checks/Test-EmbeddedDuplexOriginalsHost.py','--root',$RepoRoot,
        '--output',(Join-Path $OutputRoot originals),'--java-home',$JavaHome,'--json-jar',$HostJsonJar,'--support-classpath',$support)
    Run process-fence pwsh @('-NoProfile','-File','tools/checks/Test-EmbeddedDuplexProcessFenceHost.ps1',
        '-RepoRoot',$RepoRoot,'-JavaHome',$JavaHome,'-HostJsonJar',$HostJsonJar,
        '-AndroidJar',(Join-Path $AndroidHome 'platforms/android-34/android.jar'),'-OutputRoot',(Join-Path $OutputRoot process-fence))
    Run boundary python @('tools/check_quest_boundaries.py')
    Run whitespace git @('diff','--check')
    Run format cargo @('fmt','--all','--check')
    $paths = @(& git ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Source inventory failed.' }
    $source = foreach ($path in $paths) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            [ordered]@{ path=$path; sha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() }
        }
    }
    $source | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutputRoot source-inputs.json) -Encoding utf8NoBOM
    [ordered]@{ schema='rusty.quest.fresh_setup_source_qualification.v1'; status='passed';
        head=(& git rev-parse HEAD).Trim(); source_inputs_sha256=(Get-FileHash (Join-Path $OutputRoot source-inputs.json)).Hash.ToLowerInvariant();
        selections=@('finite/none','ongoing-v2/none','ongoing-v2/source-bound-v2');
        enrollment='Public owning factory test vector; no Product enrollment or adoption';
        limits='Source, target typecheck and host controls with declared modeled leaves; no APK/device/physical/performance/succession/DATA-session acceptance.'
    } | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $OutputRoot RESULT.json) -Encoding utf8NoBOM
    Write-Output "Fresh source qualification: $OutputRoot"
} finally {
    Pop-Location
    foreach ($name in $names) { Env $name $old[$name] }
}
