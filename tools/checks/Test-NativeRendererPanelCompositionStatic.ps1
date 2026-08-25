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
        -ForbiddenSourceNeedles @("StimulusVolumePanelModule.java", "DriverProfileSession.java", "DriverProfilePanelModule.java", "PrivateParticlePanelModule.java", "PolarPanelModule.java")

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

    $driverAppPath = Join-Path $runRoot "driver-profile.app.json"
    $driverApp = Read-Json (Join-Path $appRoot "private-particle-solid-black-canary.app.json")
    $driverApp.app_id = "driver_profile_panel_static"
    $driverApp.package_name = "io.github.mesmerprism.rustyquest.native_renderer.driver_profile_static"
    $driverApp.requested_features = @($driverApp.requested_features | ForEach-Object {
        if ([string]$_ -ceq "ui.private_particle_control_panel") {
            "ui.driver_profile_control_panel"
        } else {
            [string]$_
        }
    })
    Write-Json $driverApp $driverAppPath
    $driver = Invoke-Resolution -Name "driver-profile" -AppSpec $driverAppPath
    Assert-PanelClosure `
        -Resolution $driver `
        -ExpectedModule "driver-profile-controls" `
        -RequiredSourceNeedles @("DriverProfilePanelModule.java", "PrivateParticlePanelController.java") `
        -ForbiddenSourceNeedles @("BreathCompositionPanelModule.java", "StimulusVolumePanelModule.java", "PolarPanelModule.java")

    $polarAppPath = Join-Path $runRoot "polar.app.json"
    $polarApp = Read-Json (Join-Path $appRoot "private-particle-solid-black-canary.app.json")
    $polarApp.app_id = "polar_panel_static"
    $polarApp.package_name = "io.github.mesmerprism.rustyquest.native_renderer.polar_static"
    $polarApp.requested_features = @($polarApp.requested_features | ForEach-Object {
        if ([string]$_ -ceq "ui.private_particle_control_panel") {
            "ui.polar_control_panel"
        } else {
            [string]$_
        }
    })
    $polarPermissions = @(
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.BLUETOOTH",
        "android.permission.BLUETOOTH_ADMIN",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_SCAN"
    )
    $polarApp.permission_allowlist = @($polarApp.permission_allowlist) + $polarPermissions
    $polarApp.declared_manifest.permissions = @($polarApp.declared_manifest.permissions) + $polarPermissions
    $polarApp.declared_manifest.uses_features = @($polarApp.declared_manifest.uses_features) + "android.hardware.bluetooth_le"
    $polarApp.declared_manifest | Add-Member -NotePropertyName receivers -NotePropertyValue @("PolarSensorCommandReceiver")
    Write-Json $polarApp $polarAppPath
    $polar = Invoke-Resolution -Name "polar" -AppSpec $polarAppPath
    Assert-PanelClosure `
        -Resolution $polar `
        -ExpectedModule "polar-controls" `
        -RequiredSourceNeedles @("PolarPanelModule.java", "PolarSensorPanel.java", "PolarSensorRuntime.java") `
        -ForbiddenSourceNeedles @("BreathCompositionPanelModule.java", "StimulusVolumePanelModule.java", "PrivateParticlePanelModule.java")

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
        $path = Join-Path $features "ui\breath-composition-panel\ui.breath_composition_control_panel.feature.json"
        $value = Read-Json $path
        $value.PSObject.Properties.Remove("panel_composition")
        Write-Json $value $path
    }
    Invoke-DamagedFeatureCase -Name "unknown-selection" -ExpectedMessage "Unknown selected panel module" -Mutate {
        param($features)
        $path = Join-Path $features "ui\breath-composition-panel\ui.breath_composition_control_panel.feature.json"
        $value = Read-Json $path
        $value.panel_composition.selected_module_id = "unknown-panel"
        Write-Json $value $path
    }
    Invoke-DamagedFeatureCase -Name "duplicate-owner" -ExpectedMessage "requires exactly one packaged panel composition" -Mutate {
        param($features)
        $sourcePath = Join-Path $features "ui\breath-composition-panel\ui.breath_composition_control_panel.feature.json"
        $basePath = Join-Path $features "core\quest.native.openxr_vulkan_base.feature.json"
        $composition = (Read-Json $sourcePath).panel_composition
        $base = Read-Json $basePath
        $base | Add-Member -NotePropertyName panel_composition -NotePropertyValue $composition
        Write-Json $base $basePath
    }
    Invoke-DamagedFeatureCase -Name "unknown-dependency" -ExpectedMessage "unknown module" -Mutate {
        param($features)
        $path = Join-Path $features "ui\breath-composition-panel\ui.breath_composition_control_panel.feature.json"
        $value = Read-Json $path
        $value.panel_composition.modules[0].dependencies += "missing-controls"
        Write-Json $value $path
    }
    Invoke-DamagedFeatureCase -Name "denied-selection" -ExpectedMessage "is denied" -Mutate {
        param($features)
        $path = Join-Path $features "ui\breath-composition-panel\ui.breath_composition_control_panel.feature.json"
        $value = Read-Json $path
        $value.panel_composition.denied_module_ids += "breath-composition-controls"
        Write-Json $value $path
    }
    Invoke-DamagedFeatureCase -Name "duplicate-module" -ExpectedMessage "duplicate module id" -Mutate {
        param($features)
        $path = Join-Path $features "ui\breath-composition-panel\ui.breath_composition_control_panel.feature.json"
        $value = Read-Json $path
        $value.panel_composition.modules += $value.panel_composition.modules[0]
        Write-Json $value $path
    }

    # Every legacy/foreign runtime mode remains a renderer hint and cannot widen the baked
    # Viscereality Java closure or activate another entry module.
    foreach ($staleMode in @(
        "stimulus-volume",
        "private-layer-selector",
        "private-particle-dynamics",
        "private-particle-depth-wave",
        "private-particle-config",
        "driver-profile-panel",
        "driver-profile-session",
        "polar-sensor"
    )) {
        $staleAppPath = Join-Path $runRoot ("viscereality-stale-" + $staleMode + ".app.json")
        $staleApp = Read-Json (Join-Path $appRoot "native-breath-four-way-conformance.app.json")
        $staleApp.runtime_profile | Add-Member -NotePropertyName allow_feature_overrides -NotePropertyValue "true"
        $staleApp.runtime_profile.set | Add-Member `
            -NotePropertyName "debug.rustyquest.native_renderer.control_panel.mode" `
            -NotePropertyValue $staleMode
        $staleApp.settings_assertions.required_values."native_renderer.control_panel.mode" = $staleMode
        Write-Json $staleApp $staleAppPath
        $staleResolution = Invoke-Resolution -Name ("viscereality-stale-" + $staleMode) -AppSpec $staleAppPath
        Assert-PanelClosure `
            -Resolution $staleResolution `
            -ExpectedModule "breath-composition-controls" `
            -RequiredSourceNeedles @("BreathCompositionPanelModule.java") `
            -ForbiddenSourceNeedles @("StimulusVolumePanelModule.java", "PrivateParticlePanelModule.java", "PolarPanelModule.java")
    }

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
    if ($viscerealitySource -match 'readSystemProperty\(PROP_CONTROL_PANEL_MODE\)' -or
        $viscerealitySource -match 'private-layer-selector' -or
        $viscerealitySource -match 'nativeSubmitLivePrivateLayerSelection' -or
        $viscerealitySource -notlike '*return "breath-mapping";*') {
        throw "Viscereality panel still contains an ambient/denied entry-mode activation path."
    }
    foreach ($foreignPageNeedle in @(
        "buildDriverProfileMeshPanelView",
        "buildPrivateParticleDynamicsView",
        "buildPrivateParticleDepthWaveView",
        "buildPolarSensorPanelView",
        "DriverProfileSession"
    )) {
        if ($viscerealitySource -like "*$foreignPageNeedle*") {
            throw "Viscereality source physically retains a foreign product page: $foreignPageNeedle"
        }
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
    foreach ($readbackNeedle in @(
        "private_particle_dynamics_status.v1",
        "candidate_revision",
        "effective_revision",
        "privateParticleStatusIsEffective",
        "Request rejected by consuming runtime (not effective)"
    )) {
        if ($viscerealitySource -notlike "*$readbackNeedle*") {
            throw "Viscereality effective readback is missing owner-revision fencing: $readbackNeedle"
        }
    }
    $privateController = Get-Content -LiteralPath (Join-Path $repoRootPath "apps\native-renderer-android\panel-modules\private-particle\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\PrivateParticlePanelController.java") -Raw
    if ($privateController -notlike '*ControlPanelActivity.nativeSubmitLivePrivateParticleDynamics*' -or
        $privateController -notlike '*consuming Rust runtime and returns that owner*') {
        throw "Private-particle panel adapter no longer delegates request/readback authority to Rust."
    }
    $privateModule = Get-Content -LiteralPath (Join-Path $repoRootPath "apps\native-renderer-android\panel-modules\private-particle\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\PrivateParticlePanelModule.java") -Raw
    foreach ($needle in @(
        'rusty.quest.native_renderer.private_particle_dynamics.v1',
        'private_particles',
        'driver_values01',
        'draw_slots_per_oscillator',
        'apply-on-next-safe-frame',
        'private_particle_dynamics_status.json',
        'private_particle_dynamics_status.v1',
        '"queued"\.equals',
        '"rejected"\.equals',
        'effectiveRevision != candidateRevision',
        'Request receipt \(not effective state\)',
        'Native-effective readback \(consuming runtime\)'
    )) {
        if ($privateModule -notmatch $needle) {
            throw "Standalone private-particle module does not implement the consuming Rust contract: $needle"
        }
    }
    if ($privateModule -match 'rusty.quest.private_particle.panel_request.v1') {
        throw "Standalone private-particle module still emits the rejected legacy panel schema."
    }
    $driverModule = Get-Content -LiteralPath (Join-Path $repoRootPath "apps\native-renderer-android\panel-modules\driver-profile\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\DriverProfilePanelModule.java") -Raw
    foreach ($needle in @(
        'profile-a', 'profile-b', 'profile-c', 'profile-d',
        'real-hands', 'gpu-replay-hands', 'icosphere',
        'rusty\.driver_profile\.mesh\.native_panel_selection\.v1',
        'runtime did not queue the exact candidate revision',
        'effectiveRevision != candidateRevision',
        'Native-effective readback \(consuming runtime\)'
    )) {
        if ($driverModule -notmatch $needle) {
            throw "Standalone driver-profile module lost the existing request/readback contract: $needle"
        }
    }
    $buildScriptSource = Get-Content -LiteralPath (Join-Path $repoRootPath "tools\Build-NativeRendererAndroid.ps1") -Raw
    foreach ($chromeNeedle in @("panelBackgroundColor", "panelForegroundColor", "panelMutedColor", "panelButton", "panelText")) {
        if ($buildScriptSource -notlike "*$chromeNeedle*") {
            throw "Generated Android shell no longer owns shared panel chrome primitive: $chromeNeedle"
        }
    }

    Write-Output "native renderer panel composition static checks passed"
    Write-Output "viscereality panel sources: $(@($viscereality.lock.panel_source_closure.source_files).Count)"
    Write-Output "strobe panel sources: $(@($strobe.lock.panel_source_closure.source_files).Count)"
    Write-Output "private-particle panel sources: $(@($privateParticle.lock.panel_source_closure.source_files).Count)"
    Write-Output "driver-profile panel sources: $(@($driver.lock.panel_source_closure.source_files).Count)"
    Write-Output "polar panel sources: $(@($polar.lock.panel_source_closure.source_files).Count)"
} finally {
    $resolvedRunRoot = [System.IO.Path]::GetFullPath($runRoot)
    $allowedRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRootPath "local-artifacts\panel-composition-static")).TrimEnd('\') + '\'
    if ($resolvedRunRoot.StartsWith($allowedRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedRunRoot)) {
        Remove-Item -LiteralPath $resolvedRunRoot -Recurse -Force
    }
}
