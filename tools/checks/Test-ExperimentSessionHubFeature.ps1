[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $OutputRoot) { throw 'Create-new host output required.' }
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$null = New-Item -ItemType Directory -Path $OutputRoot
$script:checks = 0
function Check($value,[string]$why) { if (-not $value) { throw $why }; $script:checks++ }
function Deny([scriptblock]$action,[string]$why) {
    $denied=$false; try { & $action } catch { $denied=$true }
    Check $denied $why
}
$spec = Get-Content -LiteralPath (Join-Path $repo 'fixtures/native-app-builds/native-stimulus-volume-panel.app.json') -Raw | ConvertFrom-Json
$spec.app_id='public_hub_status_host'; $spec.package_name='io.github.mesmerprism.rustyquest.native_renderer.hub_host'
$spec.requested_features=@('ui.breath_composition_control_panel','renderer.background.solid_black')
$spec.denied_features=@(); $spec.expected_render_mode='solid-black-hands-and-grafts'
$spec.settings_assertions=[ordered]@{required_values=@{};required_disabled_modules=@();required_modules=@();forbidden_modules=@()}
$spec.expected_markers=[ordered]@{required=@();forbidden=@()}
$spec | Add-Member -NotePropertyName runtime_profile -NotePropertyValue @{set=@{'debug.rustyquest.native_renderer.render.mode'='solid-black-hands-and-grafts'}} -Force
$offSpec=Join-Path $OutputRoot 'off.app.json'; $spec|ConvertTo-Json -Depth 32|Set-Content $offSpec
$spec.requested_features += 'ui.experiment_session_hub_status_provider'
$spec.declared_manifest.permissions += 'io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION'
$onSpec=Join-Path $OutputRoot 'on.app.json'; $spec|ConvertTo-Json -Depth 32|Set-Content $onSpec
$prepare=Join-Path $repo 'tools/Prepare-NativeRendererBrokerClient.ps1'
& $prepare -AppSpec $offSpec -OutputRoot (Join-Path $OutputRoot 'off')
& $prepare -AppSpec $onSpec -OutputRoot (Join-Path $OutputRoot 'on')
$off=Get-Content (Join-Path $OutputRoot 'off/generated-native-renderer.client.json') -Raw|ConvertFrom-Json
$on=Get-Content (Join-Path $OutputRoot 'on/generated-native-renderer.client.json') -Raw|ConvertFrom-Json
$baseline=Get-Content (Join-Path $repo 'fixtures/broker-clients/native-renderer.client.json') -Raw|ConvertFrom-Json
foreach($field in @('capabilities','contract_families','adapter_permissions','runtime_properties','application_defaults')) {
    Check ((ConvertTo-Json -InputObject $off.$field -Compress) -ceq (ConvertTo-Json -InputObject $baseline.$field -Compress)) "Feature-off baseline $field changed"
}
$expectedOff=Get-Content (Join-Path $repo 'fixtures/broker-clients/native-renderer.client.json') -Raw|ConvertFrom-Json
$expectedOff.client_id='client.quest.native-renderer.public-hub-status-host'
$expectedOff.package_name=$off.package_name; $expectedOff.feature_lock_id='lock.broker-client.native-renderer.public-hub-status-host.v1'
$expectedOff.marker_namespace='RUSTY_QUEST_NATIVE_BROKER_CLIENT_PUBLIC_HUB_STATUS_HOST'
Check ((Get-Content (Join-Path $OutputRoot 'off/generated-native-renderer.client.json') -Raw) -ceq ($expectedOff|ConvertTo-Json -Depth 16 -Compress)) 'Feature-off serialization differs from established specialization'
Check ($off.client_id -ceq $on.client_id -and $off.package_name -ceq $on.package_name -and $off.feature_lock_id -ceq $on.feature_lock_id) 'Second subject introduced'
Check (@($on.capabilities).Count -eq 7 -and @($on.capabilities|Where-Object{$_ -cnotin $off.capabilities})[0] -ceq 'capability.connection_hub.provider.register') 'Capability widening'
Check (@($on.contract_families).Count -eq 3 -and $on.contract_families -ccontains 'rusty.manifold.hub.surface_registration.v1') 'Exact registration contract missing'
$offLockPath=(Get-ChildItem (Join-Path $OutputRoot 'off') -Recurse -Filter feature-lock.json).FullName
$onLockPath=(Get-ChildItem (Join-Path $OutputRoot 'on') -Recurse -Filter feature-lock.json).FullName
$offLock=Get-Content $offLockPath -Raw|ConvertFrom-Json; $onLock=Get-Content $onLockPath -Raw|ConvertFrom-Json
Check (@($offLock.panel_source_closure.modules|Where-Object module_id -CEQ 'experiment-session-hub-status-provider').Count -eq 0) 'Feature off compiles Hub module'
$module=@($onLock.panel_source_closure.modules|Where-Object module_id -CEQ 'experiment-session-hub-status-provider')
Check ($module.Count -eq 1 -and @($module[0].source_files).Count -eq 4) 'Exact four-source optional closure missing'
foreach($row in $module[0].source_files) { Check ((Get-FileHash (Join-Path $repo $row.path)).Hash.ToLowerInvariant() -ceq $row.sha256) 'Actual source closure hash mismatch' }
$featureSchema=Join-Path $repo 'schemas/rusty.quest.native_app_feature.v1.schema.json'
$descriptor=Join-Path $repo 'fixtures/native-app-features/ui/experiment-session-hub-status-provider/ui.experiment_session_hub_status_provider.feature.json'
Check (Test-Json -Json (Get-Content $descriptor -Raw) -SchemaFile $featureSchema -ErrorAction Stop) 'Closed feature schema rejects actual descriptor'
$manifest=Get-Content $onLock.generated_outputs.android_manifest -Raw
Check ($manifest.Contains('<package android:name="io.github.mesmerprism.rustymanifold.broker" />')) 'Actual broker package visibility missing'
Check (-not (Get-Content $offLock.generated_outputs.android_manifest -Raw).Contains('<package android:name="io.github.mesmerprism.rustymanifold.broker" />')) 'Feature-off broker visibility changed'
Deny { & $prepare -AppSpec $onSpec -OutputRoot (Join-Path $OutputRoot 'on') } 'Occupied output accepted'
Import-Module (Join-Path $repo 'tools/lib/NativeRendererBrokerClient.psm1') -Force
$template=[IO.File]::ReadAllText((Join-Path $repo 'fixtures/broker-clients/native-renderer.client.json'))
Deny { New-NativeRendererBrokerClientJson -TemplateJson $template -AppId 'a' -PackageName $off.package_name -SelectedFeatureIds @('ui.experiment_session_hub_status_provider') } 'Missing panel accepted'
$longClient=New-NativeRendererBrokerClientJson -TemplateJson $template -AppId ('lane_' + ('a' * 200)) -PackageName $off.package_name | ConvertFrom-Json
Check ($longClient.client_id.Length -gt 200) 'Existing admitted long app identity rejected'
Deny { New-NativeRendererBrokerClientJson -TemplateJson $template -AppId 'a/../b' -PackageName $off.package_name } 'Unsafe app identity accepted'
$bad=$template|ConvertFrom-Json; $bad.adapter_permissions=@('android.permission.DUMP')
Deny { New-NativeRendererBrokerClientJson -TemplateJson ($bad|ConvertTo-Json -Depth 16) -AppId 'a' -PackageName $off.package_name } 'Non-signature client accepted'
# Actual resolver rejects a substituted shared source, not just a mirrored predicate.
$featureCopy=Join-Path $OutputRoot 'features'; Copy-Item (Join-Path $repo 'fixtures/native-app-features') $featureCopy -Recurse
$hubPath=Join-Path $featureCopy 'ui/experiment-session-hub-status-provider/ui.experiment_session_hub_status_provider.feature.json'
$hub=Get-Content $hubPath -Raw|ConvertFrom-Json
$hub.panel_source_module.source_files[3]='crates/rusty-quest-broker-admission/android/io/github/mesmerprism/rustyquest/broker_admission/NativeBrokerClient.java'
$hub|ConvertTo-Json -Depth 32|Set-Content $hubPath
Deny { & $prepare -AppSpec $onSpec -FeatureDir $featureCopy -OutputRoot (Join-Path $OutputRoot 'wrong-source') } 'Wrong shared owner source accepted'
[ordered]@{passed=$true;controls=$script:checks;feature_lock_path=$onLockPath;client_lock_path=(Join-Path $OutputRoot 'on/generated-native-renderer.client.json');scope='Actual source resolver and shared APK client producer; host fixture only, no signer/grant/runtime'}|ConvertTo-Json|Set-Content (Join-Path $OutputRoot 'RESULT.json')
Write-Host "ExperimentSessionHubFeature PASS controls=$script:checks"
