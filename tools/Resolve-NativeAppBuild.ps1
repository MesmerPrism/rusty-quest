param(
    [Parameter(Mandatory=$true)]
    [string]$AppSpec,
    [string]$FeatureDir = "fixtures\native-app-features",
    [string]$OutputRoot = "local-artifacts\native-app-builds",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$ResolverVersion = "native-app-build-resolver.ps1.v1"
$FeatureSchema = "rusty.quest.native_app_feature.v1"
$AppBuildSchema = "rusty.quest.native_app_build.v1"
$FeatureLockSchema = "rusty.quest.native_app_feature_lock.v1"
$NativeAppSettingsSchema = "rusty.quest.native_app_settings.v1"
$RuntimeProfileSchema = "rusty.quest.runtime_profile.v1"
$NativeRendererPropertyManifestSchema = "rusty.quest.native_renderer_property_manifest.v2"
$NativeRendererPropertyManifestRelativePath = "fixtures\native-renderer\native-renderer-property-manifest.json"
$NativeRendererPropertyPrefix = "debug.rustyquest.native_renderer."
$RenderModeProperty = "debug.rustyquest.native_renderer.render.mode"
$EnvironmentDepthModeProperty = "debug.rustyquest.native_renderer.environment_depth.mode"
$EnvironmentDepthSourceProperty = "debug.rustyquest.native_renderer.environment_depth.source"
$EnvironmentDepthNativePassthroughRequiredProperty = "debug.rustyquest.native_renderer.environment_depth.native_passthrough.required"
$UseScenePermission = "horizonos.permission.USE_SCENE"
$PassthroughFeature = "com.oculus.feature.PASSTHROUGH"
$UseSceneDataAppOp = "USE_SCENE_DATA"
$RuntimeDangerousPermissionNames = @(
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.CAMERA",
    "com.oculus.permission.HAND_TRACKING",
    "horizonos.permission.HEADSET_CAMERA",
    "horizonos.permission.SPATIAL_CAMERA",
    "horizonos.permission.USE_SCENE"
)
$MediaProjectionForegroundServicePermission = "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"

if (-not $DryRun) {
    throw "Resolve-NativeAppBuild.ps1 currently supports source-only -DryRun resolution. APK/package generation is intentionally out of scope."
}

function Resolve-RepoPath {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$RepoRoot
    )
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $Path))
}

function Get-RepoRelativePath {
    param(
        [Parameter(Mandatory=$true)][string]$RepoRoot,
        [Parameter(Mandatory=$true)][string]$Path
    )
    $root = [System.IO.Path]::GetFullPath($RepoRoot).TrimEnd("\", "/")
    $full = [System.IO.Path]::GetFullPath($Path)
    if ($full.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($root.Length).TrimStart("\", "/").Replace("\", "/")
    }
    return $full.Replace("\", "/")
}

function Read-JsonFile {
    param([Parameter(Mandatory=$true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing JSON file: $Path"
    }
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Get-FileSha256 {
    param([Parameter(Mandatory=$true)][string]$Path)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path))
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-StringArray {
    param($Value)
    if ($null -eq $Value) {
        return @()
    }
    return @($Value | ForEach-Object {
        $item = [string]$_
        if (-not [string]::IsNullOrWhiteSpace($item)) {
            $item
        }
    })
}

function Get-SortedSet {
    param([Parameter(Mandatory=$true)]$Set)
    return @($Set.Keys | Sort-Object)
}

function Add-StringsToSet {
    param(
        [Parameter(Mandatory=$true)]$Set,
        $Values
    )
    foreach ($value in Get-StringArray $Values) {
        $Set[$value] = $true
    }
}

function Assert-RequiredProperty {
    param(
        [Parameter(Mandatory=$true)]$Object,
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Label
    )
    if ($null -eq $Object.PSObject.Properties[$Name]) {
        throw "$Label is missing required property: $Name"
    }
}

function Assert-SetEquals {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [string[]]$Expected,
        [string[]]$Actual
    )
    $expectedSorted = @($Expected | Sort-Object)
    $actualSorted = @($Actual | Sort-Object)
    $expectedText = $expectedSorted -join "`n"
    $actualText = $actualSorted -join "`n"
    if ($expectedText -ne $actualText) {
        throw "$Label mismatch. Expected [$($expectedSorted -join ', ')] but resolved [$($actualSorted -join ', ')]."
    }
}

function Get-NativeRendererPropertyManifest {
    param([Parameter(Mandatory=$true)][string]$Path)
    $manifest = Read-JsonFile -Path $Path
    if ($manifest.schema -ne $NativeRendererPropertyManifestSchema) {
        throw "Unsupported native renderer property manifest schema: $($manifest.schema)"
    }
    $byName = @{}
    $byFamily = @{}
    foreach ($entry in @($manifest.properties)) {
        $name = [string]$entry.name
        if ([string]::IsNullOrWhiteSpace($name)) {
            throw "Native renderer property manifest contains an empty property name."
        }
        if ($byName.ContainsKey($name)) {
            throw "Native renderer property manifest contains duplicate property: $name"
        }
        $byName[$name] = $entry
        $family = [string]$entry.family
        if (-not $byFamily.ContainsKey($family)) {
            $byFamily[$family] = New-Object System.Collections.ArrayList
        }
        [void]$byFamily[$family].Add($name)
    }
    return [ordered]@{
        raw = $manifest
        by_name = $byName
        by_family = $byFamily
    }
}

function Assert-NativeRendererPropertyValue {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][AllowEmptyString()][string]$Value,
        [Parameter(Mandatory=$true)]$ManifestByName
    )
    if (-not $Name.StartsWith($NativeRendererPropertyPrefix)) {
        throw "Native app-build runtime properties must use the native renderer property namespace in this workflow slice: $Name"
    }
    if (-not $ManifestByName.ContainsKey($Name)) {
        throw "Native app-build property is not in native renderer property manifest: $Name"
    }
    $entry = $ManifestByName[$Name]
    $kind = [string]$entry.value_kind
    $trimmed = $Value.Trim()
    switch ($kind) {
        "bool" {
            if (@("true", "false") -cnotcontains $trimmed.ToLowerInvariant()) {
                throw "$Name value $Value must be manifest bool true/false"
            }
        }
        "token" {
            $allowed = @($entry.allowed_values | ForEach-Object { [string]$_ })
            if ($allowed -cnotcontains $Value) {
                throw "$Name value $Value is not in manifest allowed_values: $($allowed -join ', ')"
            }
        }
        "string" {
            if ($entry.non_empty -eq $true -and [string]::IsNullOrWhiteSpace($Value)) {
                throw "$Name value must not be empty"
            }
        }
        { $_ -in @("u16", "u32", "u64") } {
            if ($trimmed -notmatch '^\d+$') {
                throw "$Name value $Value must be an unsigned integer"
            }
        }
        "f32" {
            $parsed = 0.0
            if (-not [double]::TryParse($trimmed, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) {
                throw "$Name value $Value must be a finite float"
            }
            if ([double]::IsNaN($parsed) -or [double]::IsInfinity($parsed)) {
                throw "$Name value $Value must be a finite float"
            }
        }
        "f32_pair" {
            $parts = @($Value.Split(",") | ForEach-Object { $_.Trim() })
            if ($parts.Count -ne 2) {
                throw "$Name value $Value must be two comma-separated floats"
            }
            foreach ($part in $parts) {
                $parsed = 0.0
                if (-not [double]::TryParse($part, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) {
                    throw "$Name value $Value must be two comma-separated floats"
                }
            }
        }
        default {
            throw "$Name has unsupported manifest value_kind for app-build resolver: $kind"
        }
    }
    if ($Name -like "*.high_rate_json_payload" -and $Value.Trim().ToLowerInvariant() -ne "false") {
        throw "High-rate payload transport through JSON/properties is forbidden in native app-build specs: $Name=$Value"
    }
}

function Assert-FeatureDescriptorShape {
    param(
        [Parameter(Mandatory=$true)]$Feature,
        [Parameter(Mandatory=$true)][string]$Path
    )
    $label = "Feature descriptor $Path"
    foreach ($field in @("schema", "feature_id", "module_path", "module_kind", "settings_surface", "owner_lane", "status", "description", "provides", "depends_on", "incompatible_with", "exclusive_groups", "android_manifest", "runtime_profile", "build_inputs", "markers", "validation", "public_private_boundary")) {
        Assert-RequiredProperty -Object $Feature -Name $field -Label $label
    }
    if ([string]$Feature.schema -ne $FeatureSchema) {
        throw "$label has unsupported schema: $($Feature.schema)"
    }
    if ([string]::IsNullOrWhiteSpace([string]$Feature.feature_id)) {
        throw "$label has an empty feature_id"
    }
    if ([string]::IsNullOrWhiteSpace([string]$Feature.module_path)) {
        throw "$label has an empty module_path"
    }
    if ([string]$Feature.module_path -notmatch '^[a-z0-9_]+([-/][a-z0-9_]+)*$') {
        throw "$label module_path must be a stable lowercase module path: $($Feature.module_path)"
    }
    if ([string]::IsNullOrWhiteSpace([string]$Feature.module_kind)) {
        throw "$label has an empty module_kind"
    }
    Assert-RequiredProperty -Object $Feature.settings_surface -Name "authority" -Label "$label settings_surface"
    Assert-RequiredProperty -Object $Feature.settings_surface -Name "adapter" -Label "$label settings_surface"
    if ([string]$Feature.settings_surface.authority -ne $NativeAppSettingsSchema) {
        throw "$label settings_surface authority must be $NativeAppSettingsSchema"
    }
    foreach ($section in @("android_manifest", "runtime_profile", "build_inputs", "markers", "validation")) {
        if ($null -eq $Feature.$section) {
            throw "$label has null section: $section"
        }
    }
    foreach ($field in @("permissions", "uses_features", "activities", "services", "queries")) {
        Assert-RequiredProperty -Object $Feature.android_manifest -Name $field -Label "$label android_manifest"
    }
    foreach ($field in @("set", "clear_families", "expected_render_modes")) {
        Assert-RequiredProperty -Object $Feature.runtime_profile -Name $field -Label "$label runtime_profile"
    }
    foreach ($field in @("env", "assets", "shaders")) {
        Assert-RequiredProperty -Object $Feature.build_inputs -Name $field -Label "$label build_inputs"
    }
    foreach ($field in @("required", "forbidden")) {
        Assert-RequiredProperty -Object $Feature.markers -Name $field -Label "$label markers"
    }
}

function Assert-AppSpecShape {
    param(
        [Parameter(Mandatory=$true)]$Spec,
        [Parameter(Mandatory=$true)][string]$Path
    )
    $label = "App build spec $Path"
    foreach ($field in @("schema", "app_id", "owner_repo", "package_policy", "package_name", "requested_features", "denied_features", "payloads", "permission_allowlist", "declared_manifest", "expected_render_mode", "settings_assertions", "expected_markers", "validation_tier")) {
        Assert-RequiredProperty -Object $Spec -Name $field -Label $label
    }
    if ([string]$Spec.schema -ne $AppBuildSchema) {
        throw "$label has unsupported schema: $($Spec.schema)"
    }
    if ([string]::IsNullOrWhiteSpace([string]$Spec.app_id)) {
        throw "$label app_id must not be empty"
    }
    if ([string]$Spec.app_id -notmatch '^[a-z0-9_]+(\.[a-z0-9_]+)*$') {
        throw "$label app_id must be stable snake_case/dotted lowercase: $($Spec.app_id)"
    }
    foreach ($field in @("permissions", "uses_features", "activities", "services")) {
        Assert-RequiredProperty -Object $Spec.declared_manifest -Name $field -Label "$label declared_manifest"
    }
    foreach ($field in @("required", "forbidden")) {
        Assert-RequiredProperty -Object $Spec.expected_markers -Name $field -Label "$label expected_markers"
    }
    foreach ($field in @("required_values", "required_disabled_modules", "required_modules", "forbidden_modules")) {
        Assert-RequiredProperty -Object $Spec.settings_assertions -Name $field -Label "$label settings_assertions"
    }
    if ($null -ne $Spec.PSObject.Properties["runtime_profile"]) {
        Assert-RequiredProperty -Object $Spec.runtime_profile -Name "set" -Label "$label runtime_profile"
    }
}

function Read-FeatureLibrary {
    param(
        [Parameter(Mandatory=$true)][string]$FeatureDirPath,
        [Parameter(Mandatory=$true)][string]$RepoRoot,
        [Parameter(Mandatory=$true)]$ManifestByName
    )
    if (-not (Test-Path -LiteralPath $FeatureDirPath)) {
        throw "Feature descriptor directory is missing: $FeatureDirPath"
    }
    $features = @{}
    $featureFiles = @(Get-ChildItem -LiteralPath $FeatureDirPath -Filter "*.feature.json" -File -Recurse |
        Where-Object {
            $relative = Get-RepoRelativePath -RepoRoot $RepoRoot -Path $_.FullName
            $relative -notmatch '(^|/)damaged/'
        } |
        Sort-Object FullName)
    if ($featureFiles.Count -eq 0) {
        throw "Feature descriptor directory contains no *.feature.json files: $FeatureDirPath"
    }
    foreach ($file in $featureFiles) {
        $feature = Read-JsonFile -Path $file.FullName
        Assert-FeatureDescriptorShape -Feature $feature -Path (Get-RepoRelativePath -RepoRoot $RepoRoot -Path $file.FullName)
        $featureId = [string]$feature.feature_id
        if ($features.ContainsKey($featureId)) {
            throw "Duplicate native app feature descriptor id: $featureId"
        }
        foreach ($property in @($feature.runtime_profile.set.PSObject.Properties)) {
            Assert-NativeRendererPropertyValue -Name ([string]$property.Name) -Value ([string]$property.Value) -ManifestByName $ManifestByName
        }
        $features[$featureId] = [ordered]@{
            path = $file.FullName
            descriptor = $feature
            sha256 = Get-FileSha256 -Path $file.FullName
        }
    }
    return $features
}

function New-FeatureResolverState {
    return [ordered]@{
        selected = @{}
        reasons = @{}
        visiting = @{}
    }
}

function Resolve-FeatureClosure {
    param(
        [Parameter(Mandatory=$true)][string]$FeatureId,
        [Parameter(Mandatory=$true)][string]$Reason,
        [Parameter(Mandatory=$true)]$Features,
        [Parameter(Mandatory=$true)]$Denied,
        [Parameter(Mandatory=$true)]$State
    )
    if ($Denied.ContainsKey($FeatureId)) {
        throw "Denied feature entered app-build closure: $FeatureId ($Reason)"
    }
    if (-not $Features.ContainsKey($FeatureId)) {
        throw "Requested or dependent feature is not in native app feature library: $FeatureId"
    }
    if ($State.selected.ContainsKey($FeatureId)) {
        return
    }
    if ($State.visiting.ContainsKey($FeatureId)) {
        throw "Feature dependency cycle detected at $FeatureId"
    }
    $State.visiting[$FeatureId] = $true
    $feature = $Features[$FeatureId].descriptor
    foreach ($dependency in Get-StringArray $feature.depends_on) {
        Resolve-FeatureClosure -FeatureId $dependency -Reason "dependency of $FeatureId" -Features $Features -Denied $Denied -State $State
    }
    $State.visiting.Remove($FeatureId)
    $State.selected[$FeatureId] = $true
    $State.reasons[$FeatureId] = $Reason
}

function Add-FeatureRuntimeSet {
    param(
        [Parameter(Mandatory=$true)]$RuntimeSet,
        [Parameter(Mandatory=$true)]$RuntimeSources,
        [Parameter(Mandatory=$true)][string]$FeatureId,
        [Parameter(Mandatory=$true)]$Feature
    )
    foreach ($property in @($Feature.runtime_profile.set.PSObject.Properties | Sort-Object Name)) {
        $name = [string]$property.Name
        $value = [string]$property.Value
        if ($RuntimeSet.Contains($name) -and [string]$RuntimeSet[$name] -ne $value) {
            throw "Runtime property $name is set to conflicting values by selected features. Existing=$($RuntimeSet[$name]) feature=$FeatureId value=$value"
        }
        $RuntimeSet[$name] = $value
        if (-not $RuntimeSources.Contains($name)) {
            $RuntimeSources[$name] = $FeatureId
        }
    }
}

function Add-AppRuntimeSet {
    param(
        [Parameter(Mandatory=$true)]$RuntimeSet,
        [Parameter(Mandatory=$true)]$RuntimeSources,
        [Parameter(Mandatory=$true)][string]$AppId,
        $AppRuntimeProfile,
        [Parameter(Mandatory=$true)]$ManifestByName
    )
    if ($null -eq $AppRuntimeProfile -or $null -eq $AppRuntimeProfile.PSObject.Properties["set"]) {
        return
    }
    $allowFeatureOverrides =
        $null -ne $AppRuntimeProfile.PSObject.Properties["allow_feature_overrides"] -and
        [string]$AppRuntimeProfile.allow_feature_overrides -eq "true"
    foreach ($property in @($AppRuntimeProfile.set.PSObject.Properties | Sort-Object Name)) {
        $name = [string]$property.Name
        $value = [string]$property.Value
        Assert-NativeRendererPropertyValue -Name $name -Value $value -ManifestByName $ManifestByName
        if ($RuntimeSet.Contains($name) -and [string]$RuntimeSet[$name] -ne $value) {
            if (-not $allowFeatureOverrides) {
                throw "Runtime property $name is set to conflicting values by selected features and app spec. Existing=$($RuntimeSet[$name]) app=$AppId value=$value"
            }
        }
        $RuntimeSet[$name] = $value
        $RuntimeSources[$name] = if ($allowFeatureOverrides) {
            "app-spec:$AppId:override"
        } elseif (-not $RuntimeSources.Contains($name)) {
            "app-spec:$AppId"
        } else {
            $RuntimeSources[$name]
        }
    }
}

function ConvertTo-NativeRendererSettingId {
    param([Parameter(Mandatory=$true)][string]$PropertyName)
    if ($PropertyName.StartsWith($NativeRendererPropertyPrefix)) {
        return "native_renderer." + $PropertyName.Substring($NativeRendererPropertyPrefix.Length)
    }
    return $PropertyName
}

function Assert-NativeAppSettingsAssertions {
    param(
        [Parameter(Mandatory=$true)]$AppSettings,
        [Parameter(Mandatory=$true)]$Assertions
    )

    foreach ($property in @($Assertions.required_values.PSObject.Properties | Sort-Object Name)) {
        $settingId = [string]$property.Name
        $expected = [string]$property.Value
        if (-not $AppSettings.values.Contains($settingId)) {
            throw "App settings assertion requires missing setting: $settingId"
        }
        $actual = [string]$AppSettings.values[$settingId].value
        if ($actual -ne $expected) {
            throw "App settings assertion mismatch for ${settingId}: expected $expected but resolved $actual"
        }
    }

    $disabledModules = @{}
    foreach ($module in @($AppSettings.disabled_modules)) {
        $disabledModules[[string]$module] = $true
    }
    foreach ($required in Get-StringArray $Assertions.required_disabled_modules) {
        if (-not $disabledModules.ContainsKey($required)) {
            throw "App settings assertion requires disabled module family that was not disabled: $required"
        }
    }

    $modulePaths = @{}
    foreach ($module in @($AppSettings.modules)) {
        $modulePaths[[string]$module.module_path] = $true
    }
    foreach ($required in Get-StringArray $Assertions.required_modules) {
        if (-not $modulePaths.ContainsKey($required)) {
            throw "App settings assertion requires missing module: $required"
        }
    }
    foreach ($forbidden in Get-StringArray $Assertions.forbidden_modules) {
        if ($modulePaths.ContainsKey($forbidden)) {
            throw "App settings assertion forbids selected module: $forbidden"
        }
    }
}

function New-GeneratedAndroidManifestText {
    param(
        [Parameter(Mandatory=$true)][string]$PackageName,
        [string[]]$Permissions,
        [string[]]$UsesFeatures,
        [string[]]$Activities,
        [string[]]$Services,
        [string[]]$Queries
    )
    $lines = New-Object System.Collections.ArrayList
    [void]$lines.Add('<manifest xmlns:android="http://schemas.android.com/apk/res/android"')
    [void]$lines.Add("    package=""$([System.Security.SecurityElement]::Escape($PackageName))"">")
    [void]$lines.Add("")
    [void]$lines.Add('    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="35" />')
    foreach ($feature in $UsesFeatures) {
        if ($feature -eq "android.opengl.gles.3.1") {
            [void]$lines.Add('    <uses-feature android:glEsVersion="0x00030001" android:required="true" />')
        } elseif ($feature -eq "android.hardware.vr.headtracking") {
            [void]$lines.Add('    <uses-feature android:name="android.hardware.vr.headtracking" android:version="1" android:required="true" />')
        } elseif ($feature -eq "com.oculus.feature.PASSTHROUGH") {
            [void]$lines.Add('    <uses-feature android:name="com.oculus.feature.PASSTHROUGH" android:required="true" />')
        } else {
            [void]$lines.Add("    <uses-feature android:name=""$([System.Security.SecurityElement]::Escape($feature))"" android:required=""false"" />")
        }
    }
    foreach ($permission in $Permissions) {
        [void]$lines.Add("    <uses-permission android:name=""$([System.Security.SecurityElement]::Escape($permission))"" />")
    }
    [void]$lines.Add("")
    [void]$lines.Add('    <application')
    [void]$lines.Add('        android:allowBackup="false"')
    [void]$lines.Add('        android:debuggable="true"')
    [void]$lines.Add('        android:extractNativeLibs="true"')
    [void]$lines.Add('        android:hasCode="true"')
    [void]$lines.Add('        android:label="Rusty Quest Generated Native App"')
    [void]$lines.Add('        android:theme="@android:style/Theme.Material.NoActionBar">')
    [void]$lines.Add('        <meta-data android:name="com.samsung.android.vr.application.mode" android:value="vr_only" />')
    if ($Activities -contains "android.app.NativeActivity") {
        [void]$lines.Add('        <activity')
        [void]$lines.Add('            android:name="android.app.NativeActivity"')
        [void]$lines.Add('            android:configChanges="screenSize|screenLayout|orientation|keyboardHidden|keyboard|navigation|uiMode"')
        [void]$lines.Add('            android:excludeFromRecents="true"')
        [void]$lines.Add('            android:exported="true"')
        [void]$lines.Add('            android:hardwareAccelerated="false"')
        [void]$lines.Add('            android:launchMode="singleTask"')
        [void]$lines.Add('            android:resizeableActivity="false"')
        [void]$lines.Add('            android:screenOrientation="landscape"')
        [void]$lines.Add('            android:windowSoftInputMode="adjustNothing|stateUnchanged">')
        [void]$lines.Add('            <meta-data android:name="com.oculus.vr.focusaware" android:value="true" />')
        [void]$lines.Add('            <meta-data android:name="com.oculus.intent.category.VR" android:value="vr_only" />')
        [void]$lines.Add('            <meta-data android:name="android.app.lib_name" android:value="rusty_quest_native_renderer" />')
        [void]$lines.Add('            <intent-filter>')
        [void]$lines.Add('                <action android:name="android.intent.action.MAIN" />')
        [void]$lines.Add('                <category android:name="com.oculus.intent.category.VR" />')
        [void]$lines.Add('                <category android:name="android.intent.category.LAUNCHER" />')
        [void]$lines.Add('            </intent-filter>')
        [void]$lines.Add('        </activity>')
    }
    if ($Activities -contains "ControlPanelActivity") {
        [void]$lines.Add('        <activity')
        [void]$lines.Add('            android:name=".ControlPanelActivity"')
        [void]$lines.Add('            android:configChanges="screenSize|screenLayout|orientation|keyboardHidden|keyboard|navigation|uiMode"')
        [void]$lines.Add('            android:exported="true"')
        [void]$lines.Add('            android:hardwareAccelerated="true"')
        [void]$lines.Add('            android:label="Rusty Quest Stimulus Panel"')
        [void]$lines.Add('            android:launchMode="singleTask"')
        [void]$lines.Add('            android:resizeableActivity="true"')
        [void]$lines.Add('            android:screenOrientation="landscape"')
        [void]$lines.Add('            android:windowSoftInputMode="adjustResize">')
        [void]$lines.Add('            <layout')
        [void]$lines.Add('                android:defaultHeight="720dp"')
        [void]$lines.Add('                android:defaultWidth="960dp"')
        [void]$lines.Add('                android:minHeight="480dp"')
        [void]$lines.Add('                android:minWidth="640dp" />')
        [void]$lines.Add('            <intent-filter>')
        [void]$lines.Add('                <action android:name="android.intent.action.MAIN" />')
        [void]$lines.Add('                <category android:name="com.oculus.intent.category.2D" />')
        [void]$lines.Add('            </intent-filter>')
        [void]$lines.Add('        </activity>')
    }
    if ($Activities -contains "QuestionnairePanelActivity") {
        [void]$lines.Add('        <activity')
        [void]$lines.Add('            android:name=".QuestionnairePanelActivity"')
        [void]$lines.Add('            android:configChanges="screenSize|screenLayout|orientation|keyboardHidden|keyboard|navigation|uiMode"')
        [void]$lines.Add('            android:exported="true"')
        [void]$lines.Add('            android:hardwareAccelerated="true"')
        [void]$lines.Add('            android:label="Rusty Quest Questionnaire"')
        [void]$lines.Add('            android:launchMode="singleTask"')
        [void]$lines.Add('            android:resizeableActivity="true"')
        [void]$lines.Add('            android:screenOrientation="landscape"')
        [void]$lines.Add('            android:windowSoftInputMode="adjustResize">')
        [void]$lines.Add('            <layout')
        [void]$lines.Add('                android:defaultHeight="720dp"')
        [void]$lines.Add('                android:defaultWidth="1040dp"')
        [void]$lines.Add('                android:minHeight="480dp"')
        [void]$lines.Add('                android:minWidth="720dp" />')
        [void]$lines.Add('            <intent-filter>')
        [void]$lines.Add('                <action android:name="android.intent.action.MAIN" />')
        [void]$lines.Add('                <action android:name="io.github.mesmerprism.rustyquest.native_renderer.action.OPEN_QUESTIONNAIRE_BLOCK" />')
        [void]$lines.Add('                <action android:name="io.github.mesmerprism.rustyquest.native_renderer.action.APPLY_QUESTIONNAIRE_COMMAND" />')
        [void]$lines.Add('                <category android:name="com.oculus.intent.category.2D" />')
        [void]$lines.Add('            </intent-filter>')
        [void]$lines.Add('        </activity>')
    }
    if ($Services -contains "DisplayCompositeProjectionService") {
        [void]$lines.Add('        <service android:name=".DisplayCompositeProjectionService" android:exported="false" android:foregroundServiceType="mediaProjection" />')
    }
    [void]$lines.Add('    </application>')
    if ($Queries.Count -gt 0) {
        [void]$lines.Add("")
        [void]$lines.Add("    <queries>")
        if ($Queries -contains "org.khronos.openxr.runtime_broker" -or $Queries -contains "org.khronos.openxr.system_runtime_broker") {
            [void]$lines.Add('        <provider android:name="x" android:authorities="org.khronos.openxr.runtime_broker;org.khronos.openxr.system_runtime_broker" />')
        }
        foreach ($query in $Queries) {
            if ($query -in @("org.khronos.openxr.runtime_broker", "org.khronos.openxr.system_runtime_broker")) {
                continue
            }
            [void]$lines.Add("        <intent><action android:name=""$([System.Security.SecurityElement]::Escape($query))"" /></intent>")
        }
        [void]$lines.Add("    </queries>")
    }
    [void]$lines.Add("</manifest>")
    return ($lines -join "`r`n") + "`r`n"
}

function Write-JsonArtifact {
    param(
        [Parameter(Mandatory=$true)]$Value,
        [Parameter(Mandatory=$true)][string]$Path
    )
    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    $Value | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath $Path -Encoding UTF8
}

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$repoRootText = [string]$RepoRoot
$appSpecPath = Resolve-RepoPath -Path $AppSpec -RepoRoot $repoRootText
$featureDirPath = Resolve-RepoPath -Path $FeatureDir -RepoRoot $repoRootText
$outputRootPath = Resolve-RepoPath -Path $OutputRoot -RepoRoot $repoRootText
$manifestPath = Resolve-RepoPath -Path $NativeRendererPropertyManifestRelativePath -RepoRoot $repoRootText

$propertyManifest = Get-NativeRendererPropertyManifest -Path $manifestPath
$nativeRendererPropertyByName = $propertyManifest.by_name
$nativeRendererPropertiesByFamily = $propertyManifest.by_family
$app = Read-JsonFile -Path $appSpecPath
Assert-AppSpecShape -Spec $app -Path (Get-RepoRelativePath -RepoRoot $repoRootText -Path $appSpecPath)
$features = Read-FeatureLibrary -FeatureDirPath $featureDirPath -RepoRoot $repoRootText -ManifestByName $nativeRendererPropertyByName

$denied = @{}
Add-StringsToSet -Set $denied -Values $app.denied_features
$state = New-FeatureResolverState
foreach ($requestedFeature in Get-StringArray $app.requested_features) {
    Resolve-FeatureClosure -FeatureId $requestedFeature -Reason "requested by app spec" -Features $features -Denied $denied -State $state
}
$selectedFeatureIds = @(Get-SortedSet -Set $state.selected)

foreach ($featureId in $selectedFeatureIds) {
    $feature = $features[$featureId].descriptor
    foreach ($other in Get-StringArray $feature.incompatible_with) {
        if ($state.selected.ContainsKey($other)) {
            throw "Selected feature $featureId is incompatible with selected feature $other"
        }
    }
}

$permissionsSet = @{}
$usesFeaturesSet = @{}
$activitiesSet = @{}
$servicesSet = @{}
$queriesSet = @{}
$clearFamiliesSet = @{}
$expectedRenderModesSet = @{}
$requiredMarkerSet = @{}
$forbiddenMarkerSet = @{}
$assetSet = @{}
$shaderSet = @{}
$runtimeSet = [ordered]@{}
$runtimeSources = [ordered]@{}
$exclusiveGroups = [ordered]@{}
$envByName = [ordered]@{}

function Add-BuildEnvValue {
    param(
        [System.Collections.Specialized.OrderedDictionary]$EnvByName,
        [string]$Name,
        [string]$Value,
        [string]$Source
    )

    if ([string]::IsNullOrWhiteSpace($Name)) {
        throw "Build env entry is missing name"
    }
    $envValue = if ($null -ne $Value) { [string]$Value } else { "" }
    if ($EnvByName.Contains($Name) -and [string]$EnvByName[$Name].value -ne $envValue) {
        throw "Build env $Name is set to conflicting values by selected features or app payloads"
    }
    $EnvByName[$Name] = [ordered]@{
        name = $Name
        value = $envValue
        source = if ([string]::IsNullOrWhiteSpace($Source)) { "resolver" } else { $Source }
    }
}

function Resolve-AppPayloadPath {
    param(
        [string]$AppSpecPath,
        [string]$Path,
        [string]$Label
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "App payload $Label path is empty"
    }
    $resolved = if ([System.IO.Path]::IsPathRooted($Path)) {
        [System.IO.Path]::GetFullPath($Path)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $AppSpecPath) $Path))
    }
    return $resolved
}

function Add-AppPrivateParticlePayloadBuildEnv {
    param(
        [object]$App,
        [string]$AppSpecPath,
        [System.Collections.Specialized.OrderedDictionary]$EnvByName
    )

    $privateParticlePayloads = @($App.payloads | Where-Object {
        $null -ne $_.PSObject.Properties["kind"] -and [string]$_.kind -eq "private_particle"
    })
    if ($privateParticlePayloads.Count -gt 1) {
        throw "App $($App.app_id) may declare at most one private_particle payload"
    }
    if ($privateParticlePayloads.Count -eq 0) {
        return
    }

    $payload = $privateParticlePayloads[0]
    $payloadId = if ($null -ne $payload.PSObject.Properties["payload_id"]) {
        [string]$payload.payload_id
    } elseif ($null -ne $payload.PSObject.Properties["id"]) {
        [string]$payload.id
    } else {
        "private_particle"
    }
    $source = "app-payload:$($App.app_id):$payloadId"
    $dataDir = Resolve-AppPayloadPath -AppSpecPath $AppSpecPath -Path ([string]$payload.data_dir) -Label "$payloadId data_dir"
    $shaderPath = Resolve-AppPayloadPath -AppSpecPath $AppSpecPath -Path ([string]$payload.shader) -Label "$payloadId shader"
    if (-not (Test-Path -LiteralPath $dataDir -PathType Container)) {
        throw "App payload $payloadId data_dir does not exist: $dataDir"
    }
    if (-not (Test-Path -LiteralPath $shaderPath -PathType Leaf)) {
        throw "App payload $payloadId shader does not exist: $shaderPath"
    }

    Add-BuildEnvValue -EnvByName $EnvByName -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_DATA_DIR" -Value $dataDir -Source $source
    Add-BuildEnvValue -EnvByName $EnvByName -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_SHADER" -Value $shaderPath -Source $source
    Add-BuildEnvValue -EnvByName $EnvByName -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_KIND" -Value ([string]$payload.particle_kind) -Source $source
    Add-BuildEnvValue -EnvByName $EnvByName -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MARKER_PREFIX" -Value ([string]$payload.marker_prefix) -Source $source
    Add-BuildEnvValue -EnvByName $EnvByName -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MARKER_FIELDS" -Value ([string]$payload.marker_fields) -Source $source

    if ($null -ne $payload.PSObject.Properties["mask_texture"]) {
        $mask = $payload.mask_texture
        $maskPath = if ($null -ne $mask.PSObject.Properties["path"] -and -not [string]::IsNullOrWhiteSpace([string]$mask.path)) {
            Resolve-AppPayloadPath -AppSpecPath $AppSpecPath -Path ([string]$mask.path) -Label "$payloadId mask_texture.path"
        } else {
            Join-Path $dataDir "private_particle_mask_texture.r8.bin"
        }
        if (-not (Test-Path -LiteralPath $maskPath -PathType Leaf)) {
            throw "App payload $payloadId mask texture does not exist: $maskPath"
        }

        foreach ($field in @("width", "height", "layers")) {
            if ($null -eq $mask.PSObject.Properties[$field] -or [string]::IsNullOrWhiteSpace([string]$mask.$field)) {
                throw "App payload $payloadId mask_texture is missing required $field"
            }
        }

        Add-BuildEnvValue -EnvByName $EnvByName -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_R8" -Value $maskPath -Source $source
        Add-BuildEnvValue -EnvByName $EnvByName -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH" -Value ([string]$mask.width) -Source $source
        Add-BuildEnvValue -EnvByName $EnvByName -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT" -Value ([string]$mask.height) -Source $source
        Add-BuildEnvValue -EnvByName $EnvByName -Name "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS" -Value ([string]$mask.layers) -Source $source

        foreach ($optional in @(
            @{ field = "mode"; name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_MODE" },
            @{ field = "mip_mode"; name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_MIP_MODE" },
            @{ field = "discard_mode"; name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_DISCARD_MODE" },
            @{ field = "alpha_cutoff"; name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_ALPHA_CUTOFF" }
        )) {
            $field = [string]$optional.field
            if ($null -ne $mask.PSObject.Properties[$field] -and -not [string]::IsNullOrWhiteSpace([string]$mask.$field)) {
                Add-BuildEnvValue -EnvByName $EnvByName -Name ([string]$optional.name) -Value ([string]$mask.$field) -Source $source
            }
        }
    }
}

foreach ($featureId in $selectedFeatureIds) {
    $feature = $features[$featureId].descriptor
    Add-StringsToSet -Set $permissionsSet -Values $feature.android_manifest.permissions
    Add-StringsToSet -Set $usesFeaturesSet -Values $feature.android_manifest.uses_features
    Add-StringsToSet -Set $activitiesSet -Values $feature.android_manifest.activities
    Add-StringsToSet -Set $servicesSet -Values $feature.android_manifest.services
    Add-StringsToSet -Set $queriesSet -Values $feature.android_manifest.queries
    Add-StringsToSet -Set $clearFamiliesSet -Values $feature.runtime_profile.clear_families
    Add-StringsToSet -Set $expectedRenderModesSet -Values $feature.runtime_profile.expected_render_modes
    Add-StringsToSet -Set $requiredMarkerSet -Values $feature.markers.required
    Add-StringsToSet -Set $forbiddenMarkerSet -Values $feature.markers.forbidden
    Add-StringsToSet -Set $assetSet -Values $feature.build_inputs.assets
    Add-StringsToSet -Set $shaderSet -Values $feature.build_inputs.shaders
    Add-FeatureRuntimeSet -RuntimeSet $runtimeSet -RuntimeSources $runtimeSources -FeatureId $featureId -Feature $feature
    foreach ($groupProperty in @($feature.exclusive_groups.PSObject.Properties | Sort-Object Name)) {
        $group = [string]$groupProperty.Name
        $value = [string]$groupProperty.Value
        if ($exclusiveGroups.Contains($group) -and [string]$exclusiveGroups[$group] -ne $value) {
            throw "Exclusive feature group $group selected multiple values: $($exclusiveGroups[$group]) and $value"
        }
        $exclusiveGroups[$group] = $value
    }
    foreach ($envEntry in @($feature.build_inputs.env)) {
        if ($null -eq $envEntry.PSObject.Properties["name"]) {
            throw "Feature $featureId build_inputs.env entry is missing name"
        }
        $hasDeclaredValue = $null -ne $envEntry.PSObject.Properties["value"]
        $envEntryValue = if ($hasDeclaredValue) {
            [string]$envEntry.value
        } else {
            [Environment]::GetEnvironmentVariable([string]$envEntry.name)
        }
        if (-not $hasDeclaredValue -and [string]::IsNullOrWhiteSpace($envEntryValue)) {
            throw "Feature $featureId requires build environment value $($envEntry.name), but it was not supplied."
        }
        $envEntrySource = if ($null -ne $envEntry.PSObject.Properties["source"]) {
            [string]$envEntry.source
        } elseif (-not $hasDeclaredValue) {
            "feature:${featureId}:inherited-environment"
        } else {
            "feature:$featureId"
        }
        Add-BuildEnvValue `
            -EnvByName $envByName `
            -Name ([string]$envEntry.name) `
            -Value $envEntryValue `
            -Source $envEntrySource
    }
}
Add-AppPrivateParticlePayloadBuildEnv -App $app -AppSpecPath $appSpecPath -EnvByName $envByName
Add-AppRuntimeSet `
    -RuntimeSet $runtimeSet `
    -RuntimeSources $runtimeSources `
    -AppId ([string]$app.app_id) `
    -AppRuntimeProfile $app.runtime_profile `
    -ManifestByName $nativeRendererPropertyByName
Add-StringsToSet -Set $permissionsSet -Values $app.permission_allowlist
Add-StringsToSet -Set $requiredMarkerSet -Values $app.expected_markers.required
Add-StringsToSet -Set $forbiddenMarkerSet -Values $app.expected_markers.forbidden

$permissions = @(Get-SortedSet -Set $permissionsSet)
$usesFeatures = @(Get-SortedSet -Set $usesFeaturesSet)
$activities = @(Get-SortedSet -Set $activitiesSet)
$services = @(Get-SortedSet -Set $servicesSet)
$queries = @(Get-SortedSet -Set $queriesSet)
$clearFamilies = @(Get-SortedSet -Set $clearFamiliesSet)
$expectedRenderModes = @(Get-SortedSet -Set $expectedRenderModesSet)
$requiredMarkers = @(Get-SortedSet -Set $requiredMarkerSet)
$forbiddenMarkers = @(Get-SortedSet -Set $forbiddenMarkerSet)

Assert-SetEquals -Label "Android permission surface" -Expected (Get-StringArray $app.declared_manifest.permissions) -Actual $permissions
Assert-SetEquals -Label "Android uses-feature surface" -Expected (Get-StringArray $app.declared_manifest.uses_features) -Actual $usesFeatures
Assert-SetEquals -Label "Android activity surface" -Expected (Get-StringArray $app.declared_manifest.activities) -Actual $activities
Assert-SetEquals -Label "Android service surface" -Expected (Get-StringArray $app.declared_manifest.services) -Actual $services

$environmentDepthMode = if ($runtimeSet.Contains($EnvironmentDepthModeProperty)) { [string]$runtimeSet[$EnvironmentDepthModeProperty] } else { "" }
$environmentDepthSource = if ($runtimeSet.Contains($EnvironmentDepthSourceProperty)) { [string]$runtimeSet[$EnvironmentDepthSourceProperty] } else { "" }
$environmentDepthNativePassthroughRequired =
    $runtimeSet.Contains($EnvironmentDepthNativePassthroughRequiredProperty) -and
    [string]$runtimeSet[$EnvironmentDepthNativePassthroughRequiredProperty] -eq "true"
$environmentDepthEnabled =
    -not [string]::IsNullOrWhiteSpace($environmentDepthMode) -and
    $environmentDepthMode -ne "disabled"
$environmentDepthUsesRuntimeProvider =
    $environmentDepthEnabled -and
    $environmentDepthSource -ne "synthetic-gpu-proof"
$environmentDepthProviderMarkers = @(
    "environmentDepthProviderState=provider-running",
    "environmentDepthRealProviderBound=true",
    "environmentDepthAcquireStatus=acquired",
    "privateLayerEnvironmentDepthBound=true",
    "privateLayerEnvironmentDepthFallbackActive=false"
)
$expectsUsableEnvironmentDepth = $false
foreach ($marker in $environmentDepthProviderMarkers) {
    if ($requiredMarkers -contains $marker) {
        $expectsUsableEnvironmentDepth = $true
    }
}
if ($environmentDepthUsesRuntimeProvider -and -not ($permissions -contains $UseScenePermission)) {
    throw "Runtime-provider environment depth requires $UseScenePermission in the resolved Android manifest."
}
if ($expectsUsableEnvironmentDepth -and -not ($usesFeatures -contains $PassthroughFeature)) {
    throw "Usable runtime-provider environment depth requires $PassthroughFeature in the resolved Android uses-feature surface."
}
if ($expectsUsableEnvironmentDepth -and -not ($environmentDepthNativePassthroughRequired -or ($requiredMarkers -contains "nativePassthroughRequested=true"))) {
    throw "Usable runtime-provider environment depth must require native passthrough through $EnvironmentDepthNativePassthroughRequiredProperty=true or a nativePassthroughRequested=true marker."
}
if ($expectsUsableEnvironmentDepth -and ($forbiddenMarkers -contains "nativePassthroughRequested=true")) {
    throw "App spec expects usable runtime-provider environment depth but forbids nativePassthroughRequested=true."
}

if (-not $runtimeSet.Contains($RenderModeProperty)) {
    throw "Resolved app build did not set native renderer render mode: $RenderModeProperty"
}
$renderMode = [string]$runtimeSet[$RenderModeProperty]
if ([string]$app.expected_render_mode -ne $renderMode) {
    throw "App spec expected render mode $($app.expected_render_mode) but resolved $renderMode"
}
if ($expectedRenderModes.Count -gt 0 -and $expectedRenderModes -cnotcontains $renderMode) {
    throw "Resolved render mode $renderMode is not allowed by selected feature expected_render_modes: $($expectedRenderModes -join ', ')"
}

$ownedPropertiesSet = @{}
foreach ($propertyName in $runtimeSet.Keys) {
    $ownedPropertiesSet[[string]$propertyName] = $true
}
foreach ($family in $clearFamilies) {
    if (-not $nativeRendererPropertiesByFamily.ContainsKey($family)) {
        throw "Selected feature declared unknown native renderer property family for clearing: $family"
    }
    foreach ($propertyName in @($nativeRendererPropertiesByFamily[$family])) {
        $ownedPropertiesSet[[string]$propertyName] = $true
    }
}
$ownedProperties = @(Get-SortedSet -Set $ownedPropertiesSet)

$setProperties = @()
foreach ($propertyName in @($runtimeSet.Keys | Sort-Object)) {
    $settingId = ConvertTo-NativeRendererSettingId -PropertyName $propertyName
    $setProperties += [ordered]@{
        name = [string]$propertyName
        value = [string]$runtimeSet[$propertyName]
        source_setting_id = $settingId
    }
}

$appOutputDir = Join-Path $outputRootPath ([string]$app.app_id)
$featureLockPath = Join-Path $appOutputDir "feature-lock.json"
$runtimeProfilePath = Join-Path $appOutputDir "runtime-profile.json"
$nativeAppSettingsPath = Join-Path $appOutputDir "native-app-settings.json"
$propertyWritePlanPath = Join-Path $appOutputDir "property-write-plan.json"
$androidManifestPath = Join-Path $appOutputDir "AndroidManifest.xml"
$buildEnvPath = Join-Path $appOutputDir "build-env.json"
$buildManifestPath = Join-Path $appOutputDir "build-manifest.json"
$auditPath = Join-Path $appOutputDir "app-build-audit.json"
$permissionPregrantPath = Join-Path $appOutputDir "permission-pregrant.json"

$featureDescriptorRecords = @()
foreach ($featureId in $selectedFeatureIds) {
    $featureDescriptorRecords += [ordered]@{
        feature_id = $featureId
        module_path = [string]$features[$featureId].descriptor.module_path
        module_kind = [string]$features[$featureId].descriptor.module_kind
        path = Get-RepoRelativePath -RepoRoot $repoRootText -Path $features[$featureId].path
        sha256 = [string]$features[$featureId].sha256
    }
}

$dependencyReasons = [ordered]@{}
foreach ($featureId in $selectedFeatureIds) {
    $dependencyReasons[$featureId] = [string]$state.reasons[$featureId]
}

$generatedOutputs = [ordered]@{
    feature_lock = Get-RepoRelativePath -RepoRoot $repoRootText -Path $featureLockPath
    runtime_profile = Get-RepoRelativePath -RepoRoot $repoRootText -Path $runtimeProfilePath
    native_app_settings = Get-RepoRelativePath -RepoRoot $repoRootText -Path $nativeAppSettingsPath
    property_write_plan = Get-RepoRelativePath -RepoRoot $repoRootText -Path $propertyWritePlanPath
    android_manifest = Get-RepoRelativePath -RepoRoot $repoRootText -Path $androidManifestPath
    build_env = Get-RepoRelativePath -RepoRoot $repoRootText -Path $buildEnvPath
    build_manifest = Get-RepoRelativePath -RepoRoot $repoRootText -Path $buildManifestPath
    app_build_audit = Get-RepoRelativePath -RepoRoot $repoRootText -Path $auditPath
}

$runtimePolledPropertyFamilies = @()
$privateParticleHotloadProperties = @()
$settingsHotloadPollingContract = @()
$settingsHotloadRuntimeMarkerContract = @()
$settingsHotloadLiveUpdateScope = @()
$settingsHotloadRestartRequiredScope = @()
if ($selectedFeatureIds -contains "renderer.private_particles") {
    $runtimePolledPropertyFamilies += "private_particles"
    $privateParticleHotloadProperties = @(
        "debug.rustyquest.native_renderer.private_particles.visual.scale",
        "debug.rustyquest.native_renderer.private_particles.world_anchor.scale_m",
        "debug.rustyquest.native_renderer.private_particles.driver0.value01",
        "debug.rustyquest.native_renderer.private_particles.driver1.value01",
        "debug.rustyquest.native_renderer.private_particles.driver2.value01",
        "debug.rustyquest.native_renderer.private_particles.driver3.value01",
        "debug.rustyquest.native_renderer.private_particles.driver4.value01",
        "debug.rustyquest.native_renderer.private_particles.driver5.value01",
        "debug.rustyquest.native_renderer.private_particles.driver6.value01",
        "debug.rustyquest.native_renderer.private_particles.driver7.value01",
        "debug.rustyquest.native_renderer.private_particles.tracer.draw_slots_per_oscillator",
        "debug.rustyquest.native_renderer.private_particles.tracer.lifetime_seconds",
        "debug.rustyquest.native_renderer.private_particles.tracer.copies_per_second",
        "debug.rustyquest.native_renderer.private_particles.transparency.opacity",
        "debug.rustyquest.native_renderer.private_particles.transparency.output_alpha_scale",
        "debug.rustyquest.native_renderer.private_particles.transparency.depth_suppression_strength",
        "debug.rustyquest.native_renderer.private_particles.transparency.rgb_alpha_coupling",
        "debug.rustyquest.native_renderer.private_particles.color.facing_attenuation_strength",
        "debug.rustyquest.native_renderer.private_particles.offscreen.half_res"
    )
    $settingsHotloadPollingContract = @(
        "private-particle scalar properties are polled by the runtime owner",
        "accepted values are reported by privateParticleSettingsHotload markers",
        "buffer capacities, shader payloads, texture payloads, render modes, and fixed-function blend factors are rebuild/relaunch scope"
    )
    $settingsHotloadRuntimeMarkerContract = @(
        "RUSTY_QUEST_NATIVE_RENDERER channel=private-particle-slot status=hotload-applied",
        "privateParticleSettingsHotload=true",
        "privateParticleWorldAnchorScaleParameterSource=runtime-hotload-android-property",
        "privateParticleVisualParameterSource=runtime-hotload-android-property",
        "privateParticleDriverParameterSource=runtime-hotload-android-property",
        "privateParticleDriverBankSlotCount=8",
        "privateParticleTracerParameterSource=runtime-hotload-android-property",
        "privateParticleTransparencyParameterSource=runtime-hotload-android-property",
        "privateParticleColorParameterSource=runtime-hotload-android-property"
    )
    $settingsHotloadLiveUpdateScope = @(
        "serial-scoped adb setprop for named runtime-polled diagnostic properties"
    )
    $settingsHotloadRestartRequiredScope = @(
        "private-particle buffer capacities, texture dimensions, shader payloads, and fixed-function graphics pipeline blend factors"
    )
}

$settingsHotload = [ordered]@{
    policy = "hotloadable-low-rate-settings-with-explicit-restart-boundaries"
    master_surface = $generatedOutputs.native_app_settings
    low_rate_only = $true
    default_transport = "startup-runtime-profile"
    allowed_transports = @(
        "startup-runtime-profile",
        "same-process-jni-live-queue",
        "app-private-revision-sidecar",
        "serial-scoped-adb-setprop"
    )
    runtime_polled_property_families = $runtimePolledPropertyFamilies
    accepted_scalar_properties = $privateParticleHotloadProperties
    polling_contract = $settingsHotloadPollingContract
    runtime_marker_contract = $settingsHotloadRuntimeMarkerContract
    live_update_scope = @(
        "scalar settings explicitly accepted by the runtime owner",
        "control-panel Apply Live changes routed through the native app settings layer",
        "app-private revision sidecar changes with applied-or-rejected runtime receipts"
    ) + $settingsHotloadLiveUpdateScope
    restart_required_scope = @(
        "Android manifest permissions, activities, services, queries, or uses-feature declarations",
        "build inputs, assets, shaders, native library contents, or APK package identity",
        "render modes or render targets whose runtime owner does not advertise live adoption",
        "OpenXR provider/session setup and media-projection capture token acquisition"
    ) + $settingsHotloadRestartRequiredScope
    evidence = @(
        "runtime effective-settings marker or status payload reports the accepted value",
        "revision sidecar reports applied or rejected revision instead of only file write success",
        "raw adb getprop readback is transport evidence only"
    )
    high_rate_payloads_forbidden = $true
}

$runtimeDangerousPermissions = @($permissions | Where-Object { $RuntimeDangerousPermissionNames -contains $_ })
$mediaProjectionAppOps = @()
if ($permissions -contains $MediaProjectionForegroundServicePermission -or $services -contains "DisplayCompositeProjectionService") {
    $mediaProjectionAppOps += "PROJECT_MEDIA"
}
$sceneDataAppOps = @()
if ($permissions -contains $UseScenePermission) {
    $sceneDataAppOps += $UseSceneDataAppOp
}
$appOps = @($mediaProjectionAppOps + $sceneDataAppOps)
$permissionPregrantRequired = ($runtimeDangerousPermissions.Count -gt 0 -or $appOps.Count -gt 0)
$permissionPregrantSummaryPath = Get-RepoRelativePath -RepoRoot $repoRootText -Path $permissionPregrantPath
$permissionArgumentText = if ($permissions.Count -gt 0) { " -Permissions $($permissions -join ',')" } else { "" }
$mediaProjectionArgumentText = if ($mediaProjectionAppOps -contains "PROJECT_MEDIA") { " -GrantMediaProjectionAppOp" } else { "" }
$sceneDataArgumentText = if ($sceneDataAppOps -contains $UseSceneDataAppOp) { " -GrantUseSceneDataAppOp" } else { "" }
$permissionPregrantCommand = if ($permissionPregrantRequired) {
    "powershell -NoProfile -ExecutionPolicy Bypass -File tools/Grant-NativeRendererPermissions.ps1 -PackageName $([string]$app.package_name) -Serial <quest-serial>$permissionArgumentText$mediaProjectionArgumentText$sceneDataArgumentText -Out $permissionPregrantSummaryPath"
} else {
    ""
}
$permissionPregrant = [ordered]@{
    policy = "pregrant-declared-permissions-before-first-launch"
    package_name = [string]$app.package_name
    declared_permissions = $permissions
    runtime_dangerous_permissions = $runtimeDangerousPermissions
    app_ops = $appOps
    required_before_first_launch = $permissionPregrantRequired
    tool = "tools/Grant-NativeRendererPermissions.ps1"
    command = $permissionPregrantCommand
    summary_path = $permissionPregrantSummaryPath
    notes = @(
        "Only permissions declared by the resolved app manifest should be requested for this app.",
        "pm grant can fail for normal or signature permissions; dangerous permission and app-op evidence is acceptance-critical.",
        "USE_SCENE_DATA app-op is prepared when the manifest declares horizonos.permission.USE_SCENE for Meta environment-depth routes.",
        "PROJECT_MEDIA app-op reduces lab prompt friction but does not replace fresh MediaProjection resultData from createScreenCaptureIntent."
    )
}

$validationCommands = @(
    "powershell -NoProfile -ExecutionPolicy Bypass -File tools/Resolve-NativeAppBuild.ps1 -AppSpec $(Get-RepoRelativePath -RepoRoot $repoRootText -Path $appSpecPath) -DryRun",
    "powershell -NoProfile -ExecutionPolicy Bypass -File tools/Apply-RuntimeProfile.ps1 -ProfilePath $($generatedOutputs.runtime_profile) -DryRun -Out $($generatedOutputs.property_write_plan)",
    "powershell -NoProfile -ExecutionPolicy Bypass -File tools/Test-NativeAppBuildProfile.ps1"
)

$moduleRecords = @()
foreach ($featureId in $selectedFeatureIds) {
    $feature = $features[$featureId].descriptor
    $moduleRecords += [ordered]@{
        feature_id = $featureId
        module_path = [string]$feature.module_path
        module_kind = [string]$feature.module_kind
        owner_lane = [string]$feature.owner_lane
        status = [string]$feature.status
        settings_adapter = [string]$feature.settings_surface.adapter
    }
}

$settingsValues = [ordered]@{}
foreach ($propertyName in @($runtimeSet.Keys | Sort-Object)) {
    $settingId = ConvertTo-NativeRendererSettingId -PropertyName $propertyName
    $settingsValues[$settingId] = [ordered]@{
        value = [string]$runtimeSet[$propertyName]
        android_property = [string]$propertyName
        source_feature_id = if ($runtimeSources.Contains($propertyName)) { [string]$runtimeSources[$propertyName] } else { "" }
        source = "feature-runtime-profile"
    }
}

$nativeAppSettings = [ordered]@{
    schema = $NativeAppSettingsSchema
    app_id = [string]$app.app_id
    authority = $NativeAppSettingsSchema
    resolver_version = $ResolverVersion
    selected_feature_ids = $selectedFeatureIds
    modules = $moduleRecords
    values = $settingsValues
    disabled_modules = $clearFamilies
    settings_hotload = $settingsHotload
    adapters = [ordered]@{
        android_properties = $setProperties
        clear_families = $clearFamilies
        runtime_profile = $generatedOutputs.runtime_profile
        property_write_plan = $generatedOutputs.property_write_plan
        android_manifest = $generatedOutputs.android_manifest
        build_env = $generatedOutputs.build_env
    }
}
Assert-NativeAppSettingsAssertions -AppSettings $nativeAppSettings -Assertions $app.settings_assertions
Write-JsonArtifact -Value $nativeAppSettings -Path $nativeAppSettingsPath

$featureLock = [ordered]@{
    schema = $FeatureLockSchema
    app_id = [string]$app.app_id
    resolver_version = $ResolverVersion
    app_spec_path = Get-RepoRelativePath -RepoRoot $repoRootText -Path $appSpecPath
    app_spec_sha256 = Get-FileSha256 -Path $appSpecPath
    selected_feature_ids = $selectedFeatureIds
    denied_feature_ids = @(Get-StringArray $app.denied_features | Sort-Object)
    feature_descriptors = $featureDescriptorRecords
    dependency_reasons = $dependencyReasons
    exclusive_groups = $exclusiveGroups
    android_manifest = [ordered]@{
        permissions = $permissions
        uses_features = $usesFeatures
        activities = $activities
        services = $services
        queries = $queries
        package_name = [string]$app.package_name
        package_policy = [string]$app.package_policy
    }
    runtime_profile = [ordered]@{
        profile_id = "profile.quest.native_app.$($app.app_id)"
        render_mode = $renderMode
        owned_android_properties = $ownedProperties
        set_properties = $setProperties
        clear_families = $clearFamilies
        expected_render_modes = $expectedRenderModes
    }
    app_settings = [ordered]@{
        schema = $NativeAppSettingsSchema
        path = $generatedOutputs.native_app_settings
        sha256 = Get-FileSha256 -Path $nativeAppSettingsPath
        authority = $NativeAppSettingsSchema
    }
    settings_hotload = $settingsHotload
    permission_pregrant = $permissionPregrant
    build_inputs = [ordered]@{
        env = @($envByName.Keys | Sort-Object | ForEach-Object { $envByName[$_] })
        assets = @(Get-SortedSet -Set $assetSet)
        shaders = @(Get-SortedSet -Set $shaderSet)
        payloads = @($app.payloads)
    }
    expected_markers = [ordered]@{
        required = $requiredMarkers
        forbidden = $forbiddenMarkers
    }
    validation_commands = $validationCommands
    generated_outputs = $generatedOutputs
}
Write-JsonArtifact -Value $featureLock -Path $featureLockPath

$runtimeProfile = [ordered]@{
    schema = $RuntimeProfileSchema
    profile_id = "profile.quest.native_app.$($app.app_id)"
    target_platform = "quest"
    owned_android_properties = $ownedProperties
    set_properties = $setProperties
    expected_markers = $requiredMarkers
    validation_commands = @(
        "powershell -NoProfile -ExecutionPolicy Bypass -File tools/Apply-RuntimeProfile.ps1 -ProfilePath $($generatedOutputs.runtime_profile) -DryRun -Out $($generatedOutputs.property_write_plan)"
    )
}
Write-JsonArtifact -Value $runtimeProfile -Path $runtimeProfilePath

& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRootText "tools\Apply-RuntimeProfile.ps1") -ProfilePath $runtimeProfilePath -DryRun -Out $propertyWritePlanPath | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "Generated runtime profile failed Apply-RuntimeProfile.ps1 dry-run"
}

$manifestText = New-GeneratedAndroidManifestText -PackageName ([string]$app.package_name) -Permissions $permissions -UsesFeatures $usesFeatures -Activities $activities -Services $services -Queries $queries
New-Item -ItemType Directory -Path (Split-Path -Parent $androidManifestPath) -Force | Out-Null
Set-Content -LiteralPath $androidManifestPath -Value $manifestText -Encoding UTF8

$buildEnv = [ordered]@{
    schema = "rusty.quest.native_app_build_env.v1"
    app_id = [string]$app.app_id
    env = @($envByName.Keys | Sort-Object | ForEach-Object { $envByName[$_] })
    assets = @(Get-SortedSet -Set $assetSet)
    shaders = @(Get-SortedSet -Set $shaderSet)
    payloads = @($app.payloads)
}
Write-JsonArtifact -Value $buildEnv -Path $buildEnvPath

$buildManifest = [ordered]@{
    schema = "rusty.quest.native_app_build_manifest.v1"
    app_id = [string]$app.app_id
    package_name = [string]$app.package_name
    package_policy = [string]$app.package_policy
    feature_lock_sha256 = Get-FileSha256 -Path $featureLockPath
    runtime_profile_sha256 = Get-FileSha256 -Path $runtimeProfilePath
    native_app_settings_sha256 = Get-FileSha256 -Path $nativeAppSettingsPath
    property_write_plan_sha256 = Get-FileSha256 -Path $propertyWritePlanPath
    android_manifest_sha256 = Get-FileSha256 -Path $androidManifestPath
    build_env_sha256 = Get-FileSha256 -Path $buildEnvPath
}
Write-JsonArtifact -Value $buildManifest -Path $buildManifestPath

$audit = [ordered]@{
    schema = "rusty.quest.native_app_build_audit.v1"
    app_id = [string]$app.app_id
    resolver_version = $ResolverVersion
    dry_run = $true
    source_app_spec = Get-RepoRelativePath -RepoRoot $repoRootText -Path $appSpecPath
    selected_feature_ids = $selectedFeatureIds
    denied_feature_ids = @(Get-StringArray $app.denied_features | Sort-Object)
    android_permissions = $permissions
    android_uses_features = $usesFeatures
    render_mode = $renderMode
    runtime_property_count = $ownedProperties.Count
    set_property_count = $setProperties.Count
    settings_authority = $NativeAppSettingsSchema
    settings_hotload = $settingsHotload
    permission_pregrant = $permissionPregrant
    generated_outputs = $generatedOutputs
    artifact_hashes = $buildManifest
    result = "accepted"
}
Write-JsonArtifact -Value $audit -Path $auditPath

Write-Output "native app-build dry-run accepted: $($app.app_id)"
Write-Output "feature lock: $featureLockPath"
Write-Output "audit report: $auditPath"
