param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path
$resolver = Join-Path $repoRootPath "tools\Resolve-NativeAppBuild.ps1"
$featureRoot = Join-Path $repoRootPath "fixtures\native-app-features"
$appRoot = Join-Path $repoRootPath "fixtures\native-app-builds"
$runRoot = Join-Path $repoRootPath ("local-artifacts\panel-composition-static\" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null

function Read-Json([string]$Path) {
    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Write-Json($Value, [string]$Path) {
    $Value | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Invoke-Resolution {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$AppSpec,
        [string]$FeatureDir = $featureRoot
    )
    $caseRoot = Join-Path $runRoot $Name
    New-Item -ItemType Directory -Force -Path $caseRoot | Out-Null
    $resultPath = Join-Path $caseRoot "result.json"
    $messages = @(& $resolver `
        -DryRun `
        -AppSpec $AppSpec `
        -FeatureDir $FeatureDir `
        -OutputRoot $caseRoot `
        -ResultJsonPath $resultPath 2>&1)
    if (-not (Test-Path -LiteralPath $resultPath)) {
        throw "Resolver did not write structured result for $Name`: $($messages -join [Environment]::NewLine)"
    }
    $result = Read-Json $resultPath
    return [ordered]@{
        result = $result
        lock = Read-Json ([string]$result.feature_lock_path)
        messages = $messages
    }
}

function Assert-PanelClosure {
    param(
        [Parameter(Mandatory=$true)]$Resolution,
        [AllowEmptyString()][string]$ExpectedModule,
        [string[]]$RequiredSourceNeedles,
        [string[]]$ForbiddenSourceNeedles
    )
    $closure = $Resolution.lock.panel_source_closure
    if ([string]$closure.selected_module_id -cne $ExpectedModule) {
        throw "Expected panel $ExpectedModule but resolved $($closure.selected_module_id)"
    }
    if ([bool]$closure.runtime_widening_allowed) {
        throw "Panel closure unexpectedly permits runtime widening."
    }
    $sources = @($closure.source_files.path)
    foreach ($needle in @($RequiredSourceNeedles)) {
        if (@($sources | Where-Object { [string]$_ -like "*$needle*" }).Count -eq 0) {
            throw "Panel closure $ExpectedModule is missing required source needle: $needle"
        }
    }
    foreach ($needle in @($ForbiddenSourceNeedles)) {
        if (@($sources | Where-Object { [string]$_ -like "*$needle*" }).Count -ne 0) {
            throw "Panel closure $ExpectedModule contains forbidden source needle: $needle"
        }
    }
    $settingsPath = [string]$Resolution.lock.app_settings.path
    $settings = Read-Json (Join-Path $repoRootPath $settingsPath)
    if ([string]$settings.panel.selected_module_id -cne $ExpectedModule) {
        throw "Baked native-app settings do not match feature-lock panel selection."
    }
}

function Invoke-DamagedFeatureCase {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][scriptblock]$Mutate,
        [Parameter(Mandatory=$true)][string]$ExpectedMessage
    )
    $caseRoot = Join-Path $runRoot ("damage-" + $Name)
    $caseFeatures = Join-Path $caseRoot "features"
    New-Item -ItemType Directory -Force -Path $caseRoot | Out-Null
    Copy-Item -LiteralPath $featureRoot -Destination $caseFeatures -Recurse
    & $Mutate $caseFeatures
    try {
        [void](Invoke-Resolution `
            -Name ("damage-result-" + $Name) `
            -AppSpec (Join-Path $appRoot "native-breath-four-way-conformance.app.json") `
            -FeatureDir $caseFeatures)
        throw "Damaged panel case unexpectedly resolved: $Name"
    } catch {
        if ($_.Exception.Message -notlike "*$ExpectedMessage*") {
            throw "Damaged panel case $Name failed for the wrong reason: $($_.Exception.Message)"
        }
    }
}

try {
    $viscereality = Invoke-Resolution `
        -Name "viscereality" `
        -AppSpec (Join-Path $appRoot "native-breath-four-way-conformance.app.json")
    Assert-PanelClosure `
        -Resolution $viscereality `
        -ExpectedModule "breath-composition-controls" `
        -RequiredSourceNeedles @("BreathCompositionPanelModule.java", "PolarSensorPanel.java", "LslPanelConfigStore.java") `
        -ForbiddenSourceNeedles @("StimulusVolumePanelModule.java")

    $strobe = Invoke-Resolution `
        -Name "strobe" `
        -AppSpec (Join-Path $appRoot "native-stimulus-volume-panel.app.json")
    Assert-PanelClosure `
        -Resolution $strobe `
        -ExpectedModule "stimulus-volume" `
        -RequiredSourceNeedles @("StimulusVolumePanelModule.java") `
        -ForbiddenSourceNeedles @("BreathCompositionPanelModule.java", "PrivateParticlePanelModule.java", "PolarSensorPanel.java")

    $privateParticle = Invoke-Resolution `
        -Name "private-particle" `
        -AppSpec (Join-Path $appRoot "private-particle-solid-black-canary.app.json")
    Assert-PanelClosure `
        -Resolution $privateParticle `
        -ExpectedModule "private-particle-controls" `
        -RequiredSourceNeedles @("PrivateParticlePanelController.java", "PrivateParticlePanelModule.java") `
        -ForbiddenSourceNeedles @("BreathCompositionPanelModule.java", "StimulusVolumePanelModule.java", "PolarSensorPanel.java")

    $noPanel = Invoke-Resolution `
        -Name "no-panel" `
        -AppSpec (Join-Path $appRoot "native-openxr-hand-lab.app.json")
    Assert-PanelClosure -Resolution $noPanel -ExpectedModule "" -RequiredSourceNeedles @() -ForbiddenSourceNeedles @()
    if (@($noPanel.lock.panel_source_closure.modules).Count -ne 0 -or
        @($noPanel.lock.panel_source_closure.source_files).Count -ne 0) {
        throw "No-panel app did not resolve an exact empty panel closure."
    }

    Invoke-DamagedFeatureCase -Name "missing-selection" -ExpectedMessage "requires exactly one packaged panel composition" -Mutate {
        param($features)
        $path = Join-Path $features "ui\breath-mapping-panel\ui.same_apk_breath_mapping_panel.feature.json"
        $value = Read-Json $path
        $value.PSObject.Properties.Remove("panel_composition")
        Write-Json $value $path
    }
    Invoke-DamagedFeatureCase -Name "unknown-selection" -ExpectedMessage "Unknown selected panel module" -Mutate {
        param($features)
        $path = Join-Path $features "ui\breath-mapping-panel\ui.same_apk_breath_mapping_panel.feature.json"
        $value = Read-Json $path
        $value.panel_composition.selected_module_id = "unknown-panel"
        Write-Json $value $path
    }
    Invoke-DamagedFeatureCase -Name "duplicate-owner" -ExpectedMessage "requires exactly one packaged panel composition" -Mutate {
        param($features)
        $sourcePath = Join-Path $features "ui\breath-mapping-panel\ui.same_apk_breath_mapping_panel.feature.json"
        $basePath = Join-Path $features "core\quest.native.openxr_vulkan_base.feature.json"
        $composition = (Read-Json $sourcePath).panel_composition
        $base = Read-Json $basePath
        $base | Add-Member -NotePropertyName panel_composition -NotePropertyValue $composition
        Write-Json $base $basePath
    }
    Invoke-DamagedFeatureCase -Name "unknown-dependency" -ExpectedMessage "unknown module" -Mutate {
        param($features)
        $path = Join-Path $features "ui\breath-mapping-panel\ui.same_apk_breath_mapping_panel.feature.json"
        $value = Read-Json $path
        $value.panel_composition.modules[0].dependencies += "missing-controls"
        Write-Json $value $path
    }
    Invoke-DamagedFeatureCase -Name "denied-selection" -ExpectedMessage "is denied" -Mutate {
        param($features)
        $path = Join-Path $features "ui\breath-mapping-panel\ui.same_apk_breath_mapping_panel.feature.json"
        $value = Read-Json $path
        $value.panel_composition.denied_module_ids += "breath-composition-controls"
        Write-Json $value $path
    }
    Invoke-DamagedFeatureCase -Name "duplicate-module" -ExpectedMessage "duplicate module id" -Mutate {
        param($features)
        $path = Join-Path $features "ui\breath-mapping-panel\ui.same_apk_breath_mapping_panel.feature.json"
        $value = Read-Json $path
        $value.panel_composition.modules += $value.panel_composition.modules[0]
        Write-Json $value $path
    }

    # A stale but syntactically valid runtime mode cannot widen the baked Viscereality closure.
    $staleAppPath = Join-Path $runRoot "viscereality-stale-runtime.app.json"
    $staleApp = Read-Json (Join-Path $appRoot "native-breath-four-way-conformance.app.json")
    $staleApp.runtime_profile | Add-Member -NotePropertyName allow_feature_overrides -NotePropertyValue "true"
    $staleApp.runtime_profile.set | Add-Member `
        -NotePropertyName "debug.rustyquest.native_renderer.control_panel.mode" `
        -NotePropertyValue "stimulus-volume"
    $staleApp.settings_assertions.required_values."native_renderer.control_panel.mode" = "stimulus-volume"
    Write-Json $staleApp $staleAppPath
    $staleResolution = Invoke-Resolution -Name "viscereality-stale-runtime" -AppSpec $staleAppPath
    Assert-PanelClosure `
        -Resolution $staleResolution `
        -ExpectedModule "breath-composition-controls" `
        -RequiredSourceNeedles @("BreathCompositionPanelModule.java") `
        -ForbiddenSourceNeedles @("StimulusVolumePanelModule.java")

    foreach ($invalidMode in @("", "malformed-panel-id")) {
        $invalidAppPath = Join-Path $runRoot ("viscereality-invalid-" + [guid]::NewGuid().ToString("N") + ".app.json")
        $invalidApp = Read-Json (Join-Path $appRoot "native-breath-four-way-conformance.app.json")
        $invalidApp.runtime_profile | Add-Member -NotePropertyName allow_feature_overrides -NotePropertyValue "true"
        $invalidApp.runtime_profile.set | Add-Member `
            -NotePropertyName "debug.rustyquest.native_renderer.control_panel.mode" `
            -NotePropertyValue $invalidMode
        Write-Json $invalidApp $invalidAppPath
        try {
            [void](Invoke-Resolution -Name ("invalid-runtime-" + [guid]::NewGuid().ToString("N")) -AppSpec $invalidAppPath)
            throw "Invalid runtime mode unexpectedly resolved: $invalidMode"
        } catch {
            if ($_.Exception.Message -notlike "*debug.rustyquest.native_renderer.control_panel.mode value*not in manifest allowed_values*") {
                throw
            }
        }
    }

    $registrySource = Get-Content -LiteralPath (Join-Path $repoRootPath "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\PanelModuleRegistry.java") -Raw
    foreach ($needle in @("Native panel selection is absent", "does not match packaged entry module", "return false;")) {
        if ($registrySource -notlike "*$needle*") {
            throw "Typed panel registry is missing fail-closed guardrail: $needle"
        }
    }
    foreach ($forbiddenRegistryNeedle in @("System.getProperty", "Class.forName", "stimulus-volume")) {
        if ($registrySource -like "*$forbiddenRegistryNeedle*") {
            throw "Typed panel registry contains ambient activation path: $forbiddenRegistryNeedle"
        }
    }

    $viscerealitySource = Get-Content -LiteralPath (Join-Path $repoRootPath "apps\native-renderer-android\panel-modules\breath-composition\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\BreathCompositionPanelModule.java") -Raw
    if ($viscerealitySource -match '"stimulus-volume"\.equals' -or
        $viscerealitySource -notlike '*return "breath-mapping";*') {
        throw "Viscereality runtime-mode projection can still select a stimulus panel or lacks its fail-closed default."
    }
    foreach ($lifecycleNeedle in @("onNewIntent", "onActivityResult", "onRequestPermissionsResult", "onConfigurationChanged")) {
        if ((Get-Content -LiteralPath (Join-Path $repoRootPath "tools\Build-NativeRendererAndroid.ps1") -Raw) -notlike "*$lifecycleNeedle*") {
            throw "Generated panel shell is missing lifecycle/result delegation: $lifecycleNeedle"
        }
    }
    foreach ($handoffNeedle in @("Resume VR", "closePanelAndReturnToImmersive", "focused_submitted_frame_timeout_panel_retained")) {
        if ($viscerealitySource -notlike "*$handoffNeedle*") {
            throw "Viscereality lifecycle handoff behavior is missing: $handoffNeedle"
        }
    }
    $privateController = Get-Content -LiteralPath (Join-Path $repoRootPath "apps\native-renderer-android\panel-modules\private-particle\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\PrivateParticlePanelController.java") -Raw
    if ($privateController -notlike '*ControlPanelActivity.nativeSubmitLivePrivateParticleDynamics*' -or
        $privateController -notlike '*consuming Rust runtime and returns that owner*') {
        throw "Private-particle panel adapter no longer delegates request/readback authority to Rust."
    }

    Write-Output "native renderer panel composition static checks passed"
    Write-Output "viscereality panel sources: $(@($viscereality.lock.panel_source_closure.source_files).Count)"
    Write-Output "strobe panel sources: $(@($strobe.lock.panel_source_closure.source_files).Count)"
    Write-Output "private-particle panel sources: $(@($privateParticle.lock.panel_source_closure.source_files).Count)"
} finally {
    $resolvedRunRoot = [System.IO.Path]::GetFullPath($runRoot)
    $allowedRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRootPath "local-artifacts\panel-composition-static")).TrimEnd('\') + '\'
    if ($resolvedRunRoot.StartsWith($allowedRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedRunRoot)) {
        Remove-Item -LiteralPath $resolvedRunRoot -Recurse -Force
    }
}
