[CmdletBinding()]
param([string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path)
$ErrorActionPreference = 'Stop'
# Exercise the production manifest emitter without executing APK/build effects.
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $RepoRoot 'tools/Resolve-NativeAppBuild.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Resolver parsing failed.' }
$function = $ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'New-GeneratedAndroidManifestText'}, $true)
. ([scriptblock]::Create($function.Extent.Text))
$ns = 'http://schemas.android.com/apk/res/android'
$gateway = 'ui.experiment_session_command_gateway'
$direct = 'ui.experiment_session_direct_network'
$relay = 'ui.experiment_session_relay'
function Manifest($selected, $permissions) {
    [xml](New-GeneratedAndroidManifestText -PackageName 'test.gateway' -ApplicationLabel 'Gateway test' -Activities @('android.app.NativeActivity') -SelectedFeatureIds $selected -Permissions $permissions)
}
function Meta($xml) {
    $result = @{}
    foreach ($node in $xml.SelectNodes('/manifest/application/meta-data')) {
        $name = $node.GetAttribute('name', $ns)
        if ($result.ContainsKey($name)) { throw 'Duplicate metadata.' }
        $result[$name] = $node.GetAttribute('value', $ns)
    }
    $result
}
function Require($condition, $message) { if (-not $condition) { throw $message } }
$off = Meta (Manifest @() @('android.permission.INTERNET'))
Require (@($off.Keys | Where-Object { $_ -like 'rusty.quest.*' }).Count -eq 0) 'Unselected network effects leaked into manifest.'
$lan = Meta (Manifest @($gateway) @('android.permission.INTERNET'))
Require ($lan['rusty.quest.experiment.command_gateway.enabled'] -ceq 'true') 'Gateway gate absent.'
Require ($lan['rusty.quest.experiment.command_gateway.origins'] -ceq 'https://mesmerprism.com') 'Gateway Origin policy differs.'
Require (-not $lan.ContainsKey('rusty.quest.experiment.command_gateway.relay_origin')) 'Unselected relay Origin leaked.'
$permissions = @('android.permission.INTERNET','android.permission.ACCESS_FINE_LOCATION','android.permission.NEARBY_WIFI_DEVICES')
$all = Manifest @($gateway,$direct,$relay) $permissions
$metadata = Meta $all
Require ($metadata['rusty.quest.feature.experiment_direct_network'] -ceq 'true') 'Direct gate absent.'
Require ($metadata['rusty.quest.feature.experiment_control_relay'] -ceq 'true') 'Relay gate absent.'
Require ($metadata['rusty.quest.experiment.command_gateway.relay_origin'] -ceq 'https://control-relay.mesmerprism.com') 'Relay exact trusted origin differs.'
$nearby = @($all.SelectNodes('/manifest/uses-permission') | Where-Object { $_.GetAttribute('name',$ns) -ceq 'android.permission.NEARBY_WIFI_DEVICES' })
$fine = @($all.SelectNodes('/manifest/uses-permission') | Where-Object { $_.GetAttribute('name',$ns) -ceq 'android.permission.ACCESS_FINE_LOCATION' })
Require ($nearby.Count -eq 1 -and $nearby[0].GetAttribute('usesPermissionFlags',$ns) -ceq 'neverForLocation') 'Nearby Wi-Fi permission attributes differ.'
Require ($fine.Count -eq 1 -and $fine[0].GetAttribute('maxSdkVersion',$ns) -ceq '32') 'Direct-only old Android location scope differs.'
$polar = Manifest @($gateway,$direct,$relay,'sensor.polar_h10_ble') $permissions
$fine = @($polar.SelectNodes('/manifest/uses-permission') | Where-Object { $_.GetAttribute('name',$ns) -ceq 'android.permission.ACCESS_FINE_LOCATION' })
Require (-not $fine[0].HasAttribute('maxSdkVersion',$ns)) 'Optional Wi-Fi feature broke selected Polar location closure.'
Write-Output 'Experiment gateway manifest selection and permission coexistence: PASS'
