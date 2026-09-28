Set-StrictMode -Version Latest

function Resolve-SourceBankBuildInputs {
    [CmdletBinding()]
    param([string]$PrivateLayerProfilePath, $ValidatedProductInputs,
        [string]$VertexShaderPath, [string]$FragmentShaderPath)
    $disabled = [ordered]@{enabled=$false; feature_id=$null; runtime_input=$null;
        feature_lock_sha256=$null; vertex_shader=$null; fragment_shader=$null;
        own_capture_hold_limits=[ordered]@{slots=$null;bytes=$null;gpu_uses=$null}}
    if ([string]::IsNullOrWhiteSpace($PrivateLayerProfilePath)) { return $disabled }
    $profile = Get-Content -LiteralPath $PrivateLayerProfilePath -Raw | ConvertFrom-Json -Depth 64
    $bridge = $profile.PSObject.Properties['required_public_bridge']
    if ($null -eq $bridge -or $null -eq $bridge.Value) { return $disabled }
    $abi = $bridge.Value.PSObject.Properties['stereo_input_set_abi_version']
    if ($null -eq $abi) { return $disabled }
    if (($abi.Value -isnot [int] -and $abi.Value -isnot [long]) -or $abi.Value -ne 1) {
        throw 'Source-bank profile requires the supported integer ABI1.'
    }
    if ($null -eq $ValidatedProductInputs) { throw 'Source-bank activation requires authenticated product inputs.' }
    $root = [string]$ValidatedProductInputs.root
    $lockRows = @($ValidatedProductInputs.artifacts | Where-Object path -CEQ 'planning-feature-lock.json')
    if ($lockRows.Count -ne 1) { throw 'Exactly one authenticated planning feature lock is required.' }
    $lockPath = Join-Path $root 'planning-feature-lock.json'
    $raw = (Get-FileHash -LiteralPath $lockPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($raw -cne [string]$lockRows[0].sha256) { throw 'Source-bank feature lock changed after authentication.' }
    $lock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json -Depth 64
    $features = @($lock.features | Where-Object module_id -CEQ 'quest-stereo-input-set')
    if ($features.Count -ne 1) { throw 'Exactly one locked neutral stereo input module is required.' }
    $feature = $features[0]
    $runtimeInput = 'quest.stereo.concurrent-inputs'
    if ($feature.selected -isnot [bool] -or $feature.selected -ne $true -or $feature.run_activation_default -cne 'disabled' -or
        [string]$feature.feature_id -cnotin @($lock.selected_features) -or
        $feature.owner_lane -cne 'quest-adapter' -or
        $feature.activation.rule -cne 'selected-lock-and-runtime-input' -or
        $feature.activation.receipt_schema -cne 'rusty.quest.stereo_input_set.activation_receipt.v1' -or
        $runtimeInput -cnotin @($feature.activation.runtime_inputs) -or
        $runtimeInput -cnotin @($feature.effects.inputs)) {
        throw 'Source-bank feature activation and effect closure are incomplete.'
    }
    foreach ($path in @($VertexShaderPath, $FragmentShaderPath)) {
        if ([string]::IsNullOrWhiteSpace($path) -or -not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw 'Both exact provider shader sources are required for source-bank activation.'
        }
    }
    $holds = $bridge.Value.PSObject.Properties['own_capture_hold_limits']
    if ($null -eq $holds -or $null -eq $holds.Value) { throw 'Source-bank activation requires an explicit finite capture hold contract.' }
    $limits = [ordered]@{}
    foreach ($name in @('slots','bytes','gpu_uses')) {
        $field = $holds.Value.PSObject.Properties[$name]
        if ($null -eq $field -or ($field.Value -isnot [int] -and $field.Value -isnot [long]) -or $field.Value -le 0) {
            throw "Capture hold contract field $name must be a positive integer."
        }
        $limits[$name] = [long]$field.Value
    }
    if (@($holds.Value.PSObject.Properties.Name).Count -ne 3) { throw 'Capture hold contract must contain exactly slots, bytes and gpu_uses.' }
    [ordered]@{enabled=$true;feature_id=[string]$feature.feature_id;runtime_input=$runtimeInput;
        feature_lock_sha256=$raw;
        own_capture_hold_limits=$limits;
        vertex_shader=[ordered]@{path=$VertexShaderPath;sha256=(Get-FileHash -LiteralPath $VertexShaderPath).Hash.ToLowerInvariant()};
        fragment_shader=[ordered]@{path=$FragmentShaderPath;sha256=(Get-FileHash -LiteralPath $FragmentShaderPath).Hash.ToLowerInvariant()}}
}
Export-ModuleMember -Function Resolve-SourceBankBuildInputs
