Set-StrictMode -Version Latest

function Assert-PrimaryProviderPath([string]$Root, [string]$Path, [bool]$Directory = $false) {
    if (-not [IO.Path]::IsPathFullyQualified($Root) -or -not [IO.Path]::IsPathFullyQualified($Path)) { throw 'Provider root and selected file must be absolute.' }
    $rootPath=[IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)
    $filePath=[IO.Path]::GetFullPath($Path)
    $comparison=if($IsWindows){[StringComparison]::OrdinalIgnoreCase}else{[StringComparison]::Ordinal}
    if (-not $filePath.Equals($rootPath,$comparison) -and -not $filePath.StartsWith($rootPath+[IO.Path]::DirectorySeparatorChar,$comparison)) { throw 'Primary provider path escaped the explicitly selected source root.' }
    $cursor=Get-Item -LiteralPath $filePath -ErrorAction Stop
    if($cursor.PSIsContainer -ne $Directory){throw 'Selected provider path has the wrong file type.'}
    while($null -ne $cursor){
        if($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Provider files and ancestor links are forbidden.'}
        $parent=Split-Path -Parent $cursor.FullName
        if([string]::IsNullOrEmpty($parent) -or $parent -ceq $cursor.FullName){break}
        $cursor=Get-Item -LiteralPath $parent -ErrorAction Stop
    }
    return $filePath
}

function Resolve-SourceBankBuildInputs {
    [CmdletBinding()]
    param([string]$PrivateLayerProfilePath, $ValidatedProductInputs,
        [string]$VertexShaderPath, [string]$FragmentShaderPath, [string]$ShaderProviderRoot='')
    $disabled = [ordered]@{enabled=$false; feature_id=$null; runtime_input=$null;
        feature_lock_sha256=$null; vertex_shader=$null; fragment_shader=$null;
        final_abi_version=1; primary_input_shader=$null; primary_provider_root=$null;
        final_descriptor_shape=$null; final_policy_uniform_abi_version=1; final_policy_uniform_bytes=48;
        own_capture_hold_limits=[ordered]@{slots=$null;bytes=$null;gpu_uses=$null}}
    if ([string]::IsNullOrWhiteSpace($PrivateLayerProfilePath)) { return $disabled }
    if (-not [string]::IsNullOrWhiteSpace($ShaderProviderRoot)) {
        $null=Assert-PrimaryProviderPath $ShaderProviderRoot $ShaderProviderRoot $true
        $null=Assert-PrimaryProviderPath $ShaderProviderRoot $PrivateLayerProfilePath
    }
    $profile = Get-Content -LiteralPath $PrivateLayerProfilePath -Raw | ConvertFrom-Json -Depth 64
    $bridge = $profile.PSObject.Properties['required_public_bridge']
    if ($null -eq $bridge -or $null -eq $bridge.Value) { return $disabled }
    $abi = $bridge.Value.PSObject.Properties['stereo_input_set_abi_version']
    if ($null -eq $abi) { return $disabled }
    if (($abi.Value -isnot [int] -and $abi.Value -isnot [long]) -or $abi.Value -ne 1) {
        throw 'Source-bank profile requires the supported integer ABI1.'
    }
    $finalAbi = 1
    $finalField = $bridge.Value.PSObject.Properties['source_bank_final_abi_version']
    if ($null -ne $finalField) {
        if (($finalField.Value -isnot [int] -and $finalField.Value -isnot [long]) -or $finalField.Value -notin @(1,2)) {
            throw 'Final source-bank ABI must be integer1 or2.'
        }
        $finalAbi = [int]$finalField.Value
    }
    $primaryField = $bridge.Value.PSObject.Properties['primary_input_shader']
    $primary = $null
    if ($finalAbi -eq 2) {
        if([string]::IsNullOrWhiteSpace($ShaderProviderRoot)){throw 'Final ABI2 requires an explicitly selected shader provider root.'}
        $shape='final-set1-bindings4,6,7,8-array3-plus-final-set5-binding1-std140-64bytes'
        $shapeField=$bridge.Value.PSObject.Properties['source_bank_final_descriptor_shape']
        $uniformField=$bridge.Value.PSObject.Properties['source_bank_final_policy_uniform_abi_version']
        $bytesField=$bridge.Value.PSObject.Properties['source_bank_final_policy_uniform_bytes']
        if($null -eq $shapeField -or $shapeField.Value -cne $shape -or $null -eq $uniformField -or $null -eq $bytesField -or
            ($uniformField.Value -isnot [int] -and $uniformField.Value -isnot [long]) -or $uniformField.Value -ne 2 -or
            ($bytesField.Value -isnot [int] -and $bytesField.Value -isnot [long]) -or $bytesField.Value -ne 64){throw 'Final ABI2 descriptor and uniform role closure is incomplete.'}
        if ($null -eq $primaryField -or $primaryField.Value -isnot [string] -or [string]::IsNullOrWhiteSpace($primaryField.Value)) {
            throw 'Final ABI2 requires an explicit primary input shader.'
        }
        $primaryPath = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $PrivateLayerProfilePath) $primaryField.Value))
        $primaryPath=Assert-PrimaryProviderPath $ShaderProviderRoot $primaryPath
        $primary = [ordered]@{path=$primaryPath;sha256=(Get-FileHash -LiteralPath $primaryPath).Hash.ToLowerInvariant()}
    } elseif ($null -ne $primaryField) { throw 'A primary input shader requires explicit final ABI2.' }
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
        final_abi_version=$finalAbi; primary_input_shader=$primary;
        primary_provider_root=$(if($finalAbi -eq 2){[IO.Path]::GetFullPath($ShaderProviderRoot)}else{$null});
        final_descriptor_shape=$(if($finalAbi -eq 2){$shape}else{$null});
        final_policy_uniform_abi_version=$finalAbi; final_policy_uniform_bytes=$(if($finalAbi -eq 2){64}else{48});
        own_capture_hold_limits=$limits;
        vertex_shader=[ordered]@{path=$VertexShaderPath;sha256=(Get-FileHash -LiteralPath $VertexShaderPath).Hash.ToLowerInvariant()};
        fragment_shader=[ordered]@{path=$FragmentShaderPath;sha256=(Get-FileHash -LiteralPath $FragmentShaderPath).Hash.ToLowerInvariant()}}
}
Export-ModuleMember -Function Resolve-SourceBankBuildInputs
