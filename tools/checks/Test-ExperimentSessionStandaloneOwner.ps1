[CmdletBinding()]
param([string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
      [Parameter(Mandatory)][string]$OutputRoot,
      [string]$AndroidJar,
      [string]$FeatureLockPath)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $OutputRoot) { throw 'Create-new output required.' }
$null = New-Item -ItemType Directory -Path $OutputRoot
$builder = [IO.File]::ReadAllText((Join-Path $RepoRoot 'tools/Build-NativeRendererAndroid.ps1'))
$begin = $builder.IndexOf('$standaloneExperimentOwner = ')
$end = $builder.IndexOf('$embeddedAuthorityAssetDir = ', $begin)
if ($begin -lt 0 -or $end -lt 0) { throw 'Actual generated owner source boundaries missing.' }
$generation = $builder.Substring($begin, $end-$begin)
# Resolve the owning module from the explicit repository; no candidate APK build is invoked.
$generation = $generation.Replace("Import-Module (Join-Path `$PSScriptRoot 'lib/NativeRendererAppStatusBridge.psm1') -Force", '')
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererAppStatusBridge.psm1') -Force
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererStandaloneTerminalFacade.psm1') -Force
$standaloneDescriptor = Get-Content -Raw -LiteralPath (Join-Path $RepoRoot 'fixtures/native-app-features/ui/experiment-session-owner/ui.experiment_session_owner.feature.json') | ConvertFrom-Json
if (@($standaloneDescriptor.depends_on).Count -ne 1 -or $standaloneDescriptor.depends_on[0] -cne 'ui.same_apk_control_panel') { throw 'Standalone owner dependency widened.' }
$declaredSources = @($standaloneDescriptor.panel_composition.modules | ForEach-Object { $_.source_files })
if (@($declaredSources | Where-Object { $_ -match 'Ble|Polar|Kiosk|Gateway|Relay|Lsl|BreathCompositionPanelModule' }).Count -gt 0) { throw 'Standalone descriptor contains an excluded owner.' }
$javaByVariant = @{}
foreach ($standalone in @($false,$true)) {
    $variant = if ($standalone) { 'standalone' } else { 'legacy' }
    $generatedEmbeddedPackageDir = Join-Path $OutputRoot $variant
    $null = New-Item -ItemType Directory -Path $generatedEmbeddedPackageDir
    $generatedManifestPath = Join-Path $generatedEmbeddedPackageDir 'AndroidManifest.xml'
    [IO.File]::WriteAllText($generatedManifestPath,'<manifest><application /></manifest>')
    $selectedPanelModuleId = if ($standalone) { 'experiment-session-owner' } else { 'breath-composition-controls' }
    $selectedPanelEntrySimpleName = if ($standalone) { 'ExperimentSessionOwnerPanelModule' } else { 'BreathCompositionPanelModule' }
    $selected = if ($standalone) { @('ui.experiment_session_owner') } else { @('ui.experiment_session_ble_control','ui.same_apk_soft_kiosk') }
    $appBuildLockObject = [pscustomobject]@{ selected_feature_ids = $selected }
    $appBuildEnvByName = @{ RUSTY_QUEST_NATIVE_RENDERER_EXPERIMENT_KIOSK_REQUESTED = 'false' }
    if ($standalone -and $FeatureLockPath) {
        # Explicit fixture input only; this does not qualify a successor APK lock.
        $appBuildLockObject = Get-Content -LiteralPath $FeatureLockPath -Raw | ConvertFrom-Json
        $appBuildEnvByName = @{}
        foreach ($input in $appBuildLockObject.build_inputs.env) {
            $appBuildEnvByName[[string]$input.name] = [string]$input.value
        }
    }
    Invoke-Expression $generation
    $javaByVariant[$variant] = [IO.File]::ReadAllText($generatedControlPanelActivityPath)
}
$standaloneJava = $javaByVariant['standalone']
if ($standaloneJava -match 'SelfKiosk|SoftKiosk|PolarSensorRuntime|ExperimentSessionBle|Gateway|Relay|Lsl') { throw 'Standalone generated source leaked an excluded owner.' }
if ($javaByVariant['legacy'] -notmatch 'NativeRendererSelfKioskApplication') { throw 'Legacy kiosk route unexpectedly changed.' }
function Get-OwnerCommand([string]$source) {
    $begin = $source.IndexOf('    static String applyExperimentSessionCommand(String commandJson) {')
    $end = $source.IndexOf('    private static byte[] readPackagedAsset(', $begin)
    if ($begin -lt 0 -or $end -lt 0) { throw 'Real command handler missing.' }
    return $source.Substring($begin,$end-$begin)
}
if ((Get-OwnerCommand $standaloneJava) -cne (Get-OwnerCommand $javaByVariant['legacy'])) { throw 'Standalone forked the verified command binding handler.' }
foreach ($method in @('applyConditionAudioControlReceipt','applyBreathPhaseAudioSample','initializeExperimentSessionRuntimeFromOwner','closeExperimentResourcesFromOwner')) {
    if (-not $standaloneJava.Contains($method)) { throw "Missing shared lifecycle method $method" }
}
if (-not $standaloneJava.Contains('EXPERIMENT_SESSION_ANCHORS, true)') -or -not $javaByVariant['legacy'].Contains('EXPERIMENT_SESSION_ANCHORS, false)')) { throw 'Profile route is not compile-selected.' }

# Prepare the same closed base family as the production builder; no remote or media sources.
$base = @('NativeAppSettingsReader','NativeMeshUiProducer','NativeRendererExperimentLaunchAuthority',
    'NativeRendererExperimentLauncherPolicy','NativeRendererWriterAcknowledgedExitPolicy',
    'PanelImmersiveHandoff','PanelImmersiveHandoffLifecyclePolicy','PanelImmersiveHandoffProofPolicy',
    'PanelModule','PanelModuleRegistry')
$pkg = 'apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/'
$sources = @($declaredSources | ForEach-Object { Join-Path $RepoRoot $_ }) +
    @($base | ForEach-Object { Join-Path $RepoRoot ($pkg + $_ + '.java') }) +
    @((Join-Path $OutputRoot 'standalone/ControlPanelActivity.java'),(Join-Path $OutputRoot 'standalone/NativeRendererAppStatusBridge.java'))
foreach ($source in $sources) { if (-not (Test-Path -LiteralPath $source)) { throw "Missing source $source" } }
$sourceList = Join-Path $OutputRoot 'standalone-sources.txt'
$sources | Set-Content -LiteralPath $sourceList -Encoding utf8
if ($AndroidJar) {
    if (-not (Test-Path -LiteralPath $AndroidJar)) { throw 'Exact Android platform jar required.' }
    $classes = Join-Path $OutputRoot 'classes'
    $null = New-Item -ItemType Directory $classes
    & javac '--release' '8' '-encoding' 'UTF-8' '-cp' $AndroidJar '-d' $classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'Standalone exact Java source compilation failed.' }
}
Write-Output 'PASS standalone descriptor, generated closure and shared command handler comparison; compilation only when AndroidJar supplied.'
