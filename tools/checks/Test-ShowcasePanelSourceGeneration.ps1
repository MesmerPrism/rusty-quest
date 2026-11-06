param(
    [string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$FeatureLock
)
$ErrorActionPreference = 'Stop'
$BuilderToolsRoot = Join-Path $RepoRoot 'tools'
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $BuilderToolsRoot 'Build-NativeRendererAndroid.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count -ne 0) { throw 'Builder AST contains syntax errors.' }
$blocks = @($ast.EndBlock.Statements | Where-Object {
    $_ -is [System.Management.Automation.Language.IfStatementAst] -and
    $_.Extent.Text.StartsWith('if (-not [string]::IsNullOrWhiteSpace($selectedPanelModuleId)) {')
})
if ($blocks.Count -ne 1) { throw 'Expected one complete production panel source-generation block.' }
# Only redirect the module-directory variable; execute the complete production
# branch and template, including preliminary JNI admission and final facade.
# No builder setup, compiler, signer, Cargo, device or APK entrypoint executes.
$generation = [scriptblock]::Create($blocks[0].Extent.Text.Replace('$PSScriptRoot', '$BuilderToolsRoot'))
Import-Module (Join-Path $BuilderToolsRoot 'lib/NativeRendererAppStatusBridge.psm1') -Force
Import-Module (Join-Path $BuilderToolsRoot 'lib/NativeRendererShowcaseFacade.psm1') -Force
Import-Module (Join-Path $BuilderToolsRoot 'lib/NativeRendererStandaloneTerminalFacade.psm1') -Force
$lock = if ($FeatureLock) { Get-Content -LiteralPath $FeatureLock -Raw | ConvertFrom-Json } else {
    [pscustomobject]@{ selected_feature_ids = @('ui.visual_showcase'); panel_source_closure = [pscustomobject]@{ selected_module_id = 'visual-showcase-controls'; entry_class = 'io.github.mesmerprism.rustyquest.native_renderer.ShowcasePanelModule' } }
}
if (@($lock.selected_feature_ids) -cnotcontains 'ui.visual_showcase') { throw 'Test fixture must select the exact showcase feature.' }
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('showcase-panel-source-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null
$script:cases = 0
function Invoke-SourceCase {
    param([string]$Module, [bool]$Selected, [bool]$Accept, [string]$ExpectedJni, [bool]$Standalone = $false, [bool]$Vulkan = $false)
    $selectedPanelModuleId = $Module
    $selectedPanelEntrySimpleName = if ($Module -ceq 'visual-showcase-controls') { 'ShowcasePanelModule' } else { 'TestPanelModule' }
    $showcaseSelected = $Selected
    $showcaseVulkanSelected = $Vulkan
    $standaloneExperimentOwner = $Standalone
    $appBuildLockObject = $lock
    $appBuildEnvByName = @{}
    $generatedEmbeddedPackageDir = Join-Path $temporary ([guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $generatedEmbeddedPackageDir | Out-Null
    $generatedAppStatusBridgePath = Join-Path $generatedEmbeddedPackageDir 'NativeRendererAppStatusBridge.java'
    $generatedControlPanelActivityPath = ''
    $panelNativeMethods = $null
    [IO.File]::WriteAllText($generatedAppStatusBridgePath, (New-NativeRendererAppStatusBridgeSource))
    $rejected = $false
    try { . $generation } catch { $rejected = $true; if ($Accept) { throw } }
    if ($rejected -eq $Accept) { throw "Unexpected source-generation outcome: module=$Module selected=$Selected" }
    if ($Accept) {
        if (-not $Module -and (Test-Path -LiteralPath (Join-Path $generatedEmbeddedPackageDir 'ControlPanelActivity.java'))) { throw 'No-panel selection unexpectedly generated a panel.' }
        if ($ExpectedJni -and $panelNativeMethods -notmatch $ExpectedJni) { throw "Existing JNI adapter changed: $Module" }
        if ($Selected) {
            if (-not [string]::IsNullOrEmpty($panelNativeMethods)) { throw 'Showcase facade inherited ControlPanel JNI declarations.' }
            $facade = [IO.File]::ReadAllText($generatedControlPanelActivityPath)
            if ($facade -cne (New-NativeRendererShowcaseFacadeSource -Vulkan:$Vulkan)) { throw 'Builder did not select the exact independent showcase facade.' }
            $combined = $facade + [IO.File]::ReadAllText($generatedAppStatusBridgePath)
            if ($combined -match 'ExperimentSession|ConditionAudio|Questionnaire|nativeInitializeExperiment|nativeApplyBreathComposition|NativeRendererSelfKiosk') { throw 'Showcase generated an excluded adapter.' }
            if ($isExperimentSessionPanel -or $usesPolarSessionRuntime -or $appHubSelected -or $appBleSelected -or $sharedOriginalPanel) { throw 'Showcase inherited an original composition branch.' }
        }
    }
    $script:cases++
}
Invoke-SourceCase 'visual-showcase-controls' $true $true ''
Invoke-SourceCase 'visual-showcase-controls' $true $true '' $false $true
Invoke-SourceCase 'visual-showcase-controls' $false $false ''
Invoke-SourceCase 'unknown-controls' $false $false ''
Invoke-SourceCase 'unknown-controls' $true $false ''
Invoke-SourceCase 'stimulus-volume' $false $true 'nativeSubmitLiveStimulusCandidate'
Invoke-SourceCase 'private-particle-controls' $false $true 'nativeSubmitLivePrivateParticleDynamics'
Invoke-SourceCase 'driver-profile-controls' $false $true 'nativeSubmitLivePrivateParticleDynamics'
Invoke-SourceCase 'polar-controls' $false $true ''
Invoke-SourceCase 'breath-composition-controls' $false $true 'nativeInitializeExperimentSessionRuntime'
Invoke-SourceCase 'experiment-session-owner' $false $true 'nativeInitializeExperimentSessionRuntime' $true
Invoke-SourceCase '' $false $true ''
Write-Output "PASS $script:cases complete production source-generation cases; showcase JNI isolated; unknown/unselected modules rejected; existing adapters retained. Generated test files: $temporary"
