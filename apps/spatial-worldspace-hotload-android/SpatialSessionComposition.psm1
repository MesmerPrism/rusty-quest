Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
function Get-SelectedFileRecord($Row) {
    if (-not [IO.Path]::IsPathRooted([string]$Row.path) -or [string]$Row.sha256 -cnotmatch '^[a-f0-9]{64}$') { throw 'Selected session input requires absolute path and exact digest.' }
    $file=Get-Item -LiteralPath $Row.path
    if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $file.Length -ne $Row.bytes -or
        (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Row.sha256) { throw "Session composition byte mismatch: $($Row.path)" }
    [ordered]@{path=$file.FullName;bytes=$file.Length;sha256=[string]$Row.sha256}
}
function Read-SpatialSessionCompositionInputs {
    param([Parameter(Mandatory)][string]$Manifest,[Parameter(Mandatory)][string]$IdentityManifest)
    $c=Get-Content -Raw -LiteralPath $Manifest|ConvertFrom-Json
    $i=Get-Content -Raw -LiteralPath $IdentityManifest|ConvertFrom-Json
    $identityHash=(Get-FileHash -LiteralPath $IdentityManifest -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($c.schema -cne 'rusty.quest.spatial_session_composition.v1' -or $c.status -cne 'qualified-host-inputs' -or
        $c.service_abi_version -ne 1 -or $c.private_app_identity_sha256 -cne $identityHash -or
        $i.schema -cne 'rusty.quest.spatial_private_app_identity.v1' -or
        $i.application_id -cnotmatch '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){2,}$' -or
        $i.runtime_identity_overrides -isnot [bool] -or $i.runtime_identity_overrides -or
        $i.app_private_recording_subdirectory -cnotmatch '^[a-z][a-z0-9_-]{0,63}$' -or
        $i.app_label -match '[\x00-\x1f]' -or [string]::IsNullOrWhiteSpace($i.app_label)) { throw 'Closed session composition/app identity rejected.' }
    foreach ($name in @('camera','environment_depth','boot','kiosk','ble','polar','gateway','relay','streaming')) {
        if ($i.optional_features.$name -isnot [bool] -or $i.optional_features.$name) { throw "Unsupported session optional feature: $name" }
    }
    foreach ($name in @('activation_binding_sha256','profile_sha256','provider_manifest_sha256','inventory_sha256','session_settings_sha256')) {
        if ([string]$c.$name -cnotmatch '^[a-f0-9]{64}$' -or [string]$c.$name -ceq ('0'*64)) { throw "Missing session anchor: $name" }
    }
    if ($c.topology.main_count -ne 2562 -or $c.topology.tracer_capacity -ne 17934 -or $c.topology.anchor_draw_count -ne 2 -or
        $c.topology.instance_capacity -ne 20498 -or $c.topology.total_records -ne 124304 -or
        $c.material.blend_mode -cne 'src-one-one-minus-src-alpha' -or $c.material.api_verified -isnot [bool] -or -not $c.material.api_verified) { throw 'Selected topology/material API contract rejected.' }
    $inventories=@{}
    foreach ($name in @('core_source_files','core_libraries','java_source_files','inputs')) {
        if (@($c.$name).Count -eq 0) { throw "Actual selected session inventory missing: $name" }
        $inventories[$name]=@($c.$name|ForEach-Object { Get-SelectedFileRecord $_ })
        if (@($inventories[$name].path|Sort-Object -Unique).Count -ne $inventories[$name].Count) { throw "Duplicate session input: $name" }
    }
    $libraries=$inventories.core_libraries
    if ($libraries.Count -ne 1 -or [IO.Path]::GetFileName($libraries[0].path) -cne 'librusty_quest_sdk_session_core.so') { throw 'Exactly one canonical session core library is required.' }
    $headers=@($inventories.core_source_files|Where-Object { [IO.Path]::GetFileName($_.path) -ceq 'rq_experiment_services.h' })
    if ($headers.Count -ne 1) { throw 'One exact canonical experiment service header required.' }
    foreach ($row in $inventories.java_source_files) { if ([IO.Path]::GetExtension($row.path) -cne '.java') { throw 'Session Java closure must contain actual Java source files.' } }
    if (@($inventories.java_source_files|ForEach-Object { [IO.Path]::GetFileName($_.path) }|Sort-Object -Unique).Count -ne $inventories.java_source_files.Count) { throw 'Duplicate session Java class file name.' }
    if (@($inventories.java_source_files|Where-Object { [IO.Path]::GetFileName($_.path) -ceq 'NativeRendererWriterAcknowledgedExitPolicy.java' }).Count -ne 1) { throw 'Canonical session shell requires its actual writer-acknowledged exit policy source.' }
    $profile=@($inventories.inputs|Where-Object sha256 -CEQ $c.profile_sha256)
    $provider=@($inventories.inputs|Where-Object sha256 -CEQ $c.provider_manifest_sha256)
    $settings=@($inventories.inputs|Where-Object sha256 -CEQ $c.session_settings_sha256)
    if ($profile.Count -ne 1 -or $provider.Count -ne 1 -or $settings.Count -ne 1) { throw 'Compiled profile/provider/settings anchors must address exact single inputs.' }
    $builds=@()
    foreach ($row in $inventories.inputs|Where-Object { [IO.Path]::GetExtension($_.path) -ceq '.json' }) {
        $j=Get-Content -LiteralPath $row.path -Raw|ConvertFrom-Json
        if ($null -ne $j.PSObject.Properties['schema'] -and $j.schema -ceq 'rusty.quest.sdk_session_core.build.v1') { $builds+=,$j }
    }
    if ($builds.Count -ne 1) { throw 'Selected composition requires one actual canonical core build receipt.' }
    $b=$builds[0]
    if ($b.target -cne 'aarch64-linux-android' -or $b.qualification.native_core_compiled -isnot [bool] -or -not $b.qualification.native_core_compiled -or
        $b.library.path -cne $libraries[0].path -or $b.library.sha256 -cne $libraries[0].sha256) { throw 'Core build receipt/library binding rejected.' }
    $anchors=@{
        RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_PROFILE_SHA256=$c.profile_sha256
        RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_PROVIDER_MANIFEST_SHA256=$c.provider_manifest_sha256
        RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_INVENTORY_SHA256=$c.inventory_sha256
        RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_EXPECTED_BINDING_SHA256=$c.activation_binding_sha256
        RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_DRIVER_EXPECTED_BINDING_SHA256=$c.activation_binding_sha256
        RQ_SDK_SESSION_SETTINGS_SHA256=$c.session_settings_sha256
    }
    foreach ($name in $anchors.Keys) { if ($b.compile_anchors.$name -cne $anchors[$name]) { throw "Canonical core compiled anchor mismatch: $name" } }
    foreach ($row in $b.sources) {
        if (@($inventories.core_source_files|Where-Object { $_.path -ceq $row.path -and $_.sha256 -ceq $row.sha256 }).Count -ne 1) { throw 'Core source inventory is not closed against the actual build receipt.' }
    }
    foreach ($row in @($b.java_adapter_sources)+@($b.canonical_owner_sources)) {
        if (@($inventories.java_source_files|Where-Object { $_.path -ceq $row.path -and $_.sha256 -ceq $row.sha256 }).Count -ne 1) { throw 'Canonical Java output omitted from selected APK closure.' }
    }
    $p=Get-Content -LiteralPath $provider[0].path -Raw|ConvertFrom-Json
    if ($p.schema -cne 'rusty.quest.native_app_private_asset_provider.v1' -or @($p.assets).Count -eq 0) { throw 'Closed provider asset inventory required.' }
    $assets=@()
    foreach ($asset in $p.assets) {
        if ($asset.logical_destination -cnotmatch '^[a-zA-Z0-9_-]+/[a-zA-Z0-9_.-]+$' -or $asset.source_relative_path -match '(^|[\\/])\.\.([\\/]|$)' -or [IO.Path]::IsPathRooted($asset.source_relative_path)) { throw 'Unsafe provider asset destination/source.' }
        $file=Get-SelectedFileRecord ([pscustomobject]@{path=(Join-Path (Split-Path $provider[0].path) $asset.source_relative_path);bytes=$asset.source_bytes;sha256=$asset.source_sha256})
        $assets+=[ordered]@{path='session-assets/'+$asset.logical_destination;source=$file.path;bytes=$file.bytes;sha256=$file.sha256}
    }
    if (@($assets.path|Sort-Object -Unique).Count -ne $assets.Count) { throw 'Duplicate provider asset destination.' }
    [ordered]@{composition=$c;identity=$i;inventories=$inventories;service_header=$headers[0];assets=$assets;core_build=$b;provider=$p}
}
function Get-SessionJsonBytes($Value) {
    [Text.UTF8Encoding]::new($false).GetBytes(($Value|ConvertTo-Json -Depth 40)+"`n")
}
function Get-SessionInventoryDigest($Rows) {
    # Packaged provenance contains byte identities, never host source paths.
    $values=@($Rows|ForEach-Object {[ordered]@{bytes=$_.bytes;sha256=$_.sha256}}|Sort-Object sha256,bytes)
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData((Get-SessionJsonBytes $values))).ToLowerInvariant()
}
function Get-SpatialSessionFeatureLock($Session) {
    $c=$Session.composition;$p=$Session.provider
    $inventory=@($Session.inventories.inputs|Where-Object sha256 -CEQ $c.inventory_sha256)
    if($inventory.Count -ne 1){throw 'Compiled inventory anchor must address one exact selected input.'}
    $document=Get-Content -LiteralPath $inventory[0].path -Raw|ConvertFrom-Json
    if($document.schema -cne 'rusty.quest.native_app_private_asset_inventory.v1' -or $document.provider_id -cne $p.provider_id){throw 'Selected canonical asset inventory/provider mismatch.'}
    $assets=@($document.assets)
    if($assets.Count -lt 3 -or $assets.Count -gt 8){throw 'Canonical session closure requires three to eight provider assets.'}
    if($assets.Count -ne @($p.assets).Count -or @($assets.asset_id|Sort-Object -Unique).Count -ne $assets.Count){throw 'Canonical inventory asset cardinality/identity mismatch.'}
    foreach($entry in $assets){
        if((@($entry.PSObject.Properties.Name|Sort-Object) -join ',') -cne 'asset_id,logical_destination,media_type,source_bytes,source_sha256,staged_object' -or [string]::IsNullOrWhiteSpace($entry.staged_object)){throw 'Canonical inventory asset fields rejected.'}
        $providerEntry=@($p.assets|Where-Object asset_id -CEQ $entry.asset_id)
        if($providerEntry.Count -ne 1){throw 'Canonical inventory asset absent from selected provider.'}
        foreach($key in @('logical_destination','source_bytes','source_sha256','media_type')){
            if([string]$providerEntry[0].$key -cne [string]$entry.$key){throw 'Canonical inventory asset differs from selected provider bytes.'}
        }
    }
    [ordered]@{
        schema='rusty.quest.spatial_session_packaged_feature_lock.v1'
        selected_feature_ids=@('ui.experiment_session_owner')
        build_inputs=[ordered]@{private_asset_closure=[ordered]@{
            schema='rusty.quest.native_app_private_asset_closure.v1';mode='linked-provider'
            provider_id=[string]$p.provider_id;provider_manifest_sha256=[string]$c.provider_manifest_sha256
            inventory_sha256=[string]$c.inventory_sha256;asset_count=$assets.Count;assets=$assets
        }}
        source_binding=[ordered]@{
            schema='rusty.quest.spatial_session_packaged_source_binding.v1'
            profile_sha256=[string]$c.profile_sha256;private_app_identity_sha256=[string]$c.private_app_identity_sha256
            activation_binding_sha256=[string]$c.activation_binding_sha256;session_settings_sha256=[string]$c.session_settings_sha256
            core_libraries_sha256=(Get-SessionInventoryDigest $Session.inventories.core_libraries)
            core_source_files_sha256=(Get-SessionInventoryDigest $Session.inventories.core_source_files)
            java_source_files_sha256=(Get-SessionInventoryDigest $Session.inventories.java_source_files)
            selected_inputs_sha256=(Get-SessionInventoryDigest $Session.inventories.inputs)
        }
    }
}
function Read-SpatialSessionComposition {
    param([Parameter(Mandatory)][string]$Manifest,[Parameter(Mandatory)][string]$IdentityManifest)
    $session=Read-SpatialSessionCompositionInputs -Manifest $Manifest -IdentityManifest $IdentityManifest
    if($null -eq $session.composition.PSObject.Properties['packaged_feature_lock']){throw 'Explicit prepared SDK packaged feature lock is required.'}
    $lock=Get-SelectedFileRecord $session.composition.packaged_feature_lock
    $expected=Get-SessionJsonBytes (Get-SpatialSessionFeatureLock $session)
    $expectedHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($expected)).ToLowerInvariant()
    if($lock.bytes -ne $expected.Length -or $lock.sha256 -cne $expectedHash){throw 'SDK packaged feature lock differs from the exact selected closure/source binding.'}
    $session.assets+=,[ordered]@{path='session-assets/feature-lock.json';source=$lock.path;bytes=$lock.bytes;sha256=$lock.sha256}
    $session.feature_lock=$lock
    $session
}
function New-SpatialSessionClosureInputs {
    param([Parameter(Mandatory)][string]$Manifest,[Parameter(Mandatory)][string]$IdentityManifest,[Parameter(Mandatory)][string]$OutputDirectory)
    if(-not [IO.Path]::IsPathRooted($OutputDirectory) -or (Test-Path -LiteralPath $OutputDirectory)){throw 'Fresh absolute closure output directory required.'}
    $session=Read-SpatialSessionCompositionInputs -Manifest $Manifest -IdentityManifest $IdentityManifest
    if($null -ne $session.composition.PSObject.Properties['packaged_feature_lock']){throw 'Prepare from an original composition; never replace a previous closure.'}
    $bytes=Get-SessionJsonBytes (Get-SpatialSessionFeatureLock $session)
    $null=New-Item -ItemType Directory -Path (Join-Path $OutputDirectory 'assets')
    foreach($row in $session.assets){
        $destination=Join-Path $OutputDirectory ('assets/'+$row.path.Substring('session-assets/'.Length))
        $null=New-Item -ItemType Directory -Force -Path (Split-Path $destination)
        Copy-Item -LiteralPath $row.source -Destination $destination
        $null=Get-SelectedFileRecord ([pscustomobject]@{path=$destination;bytes=$row.bytes;sha256=$row.sha256})
    }
    $path=Join-Path $OutputDirectory 'assets/feature-lock.json'
    [IO.File]::WriteAllBytes($path,$bytes)
    $pin=[ordered]@{path=[IO.Path]::GetFullPath($path);bytes=$bytes.Length;sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()}
    $session.composition|Add-Member -NotePropertyName packaged_feature_lock -NotePropertyValue $pin
    $composition=Join-Path $OutputDirectory 'session-composition.json'
    [IO.File]::WriteAllBytes($composition,(Get-SessionJsonBytes $session.composition))
    $null=Read-SpatialSessionComposition -Manifest $composition -IdentityManifest $IdentityManifest
    [ordered]@{composition=$composition;feature_lock=$pin;assets_directory=(Join-Path $OutputDirectory 'assets');device_evidence=$false}
}
function Read-NativeHotloadAssetClosure {
    param([Parameter(Mandatory)][string]$Root)
    $header=Get-Content -LiteralPath (Join-Path $Root 'native-catalog/hotload_catalog.h') -Raw
    $pattern='\{"(hotload/native/[ab]\.spv)",\s*(\d+),\s*"([a-f0-9]{64})",\s*1\}'
    $matches=[regex]::Matches($header,$pattern)
    if ($matches.Count -ne 2 -or @($matches|ForEach-Object { $_.Groups[1].Value }|Sort-Object -Unique).Count -ne 2) { throw 'Native A/B hotload catalog must contain its exact two closed blob entries.' }
    @($matches|ForEach-Object {
        $record=Get-SelectedFileRecord ([pscustomobject]@{path=(Join-Path $Root ('assets/'+$_.Groups[1].Value));bytes=[long]$_.Groups[2].Value;sha256=$_.Groups[3].Value})
        [ordered]@{path=('assets/'+$_.Groups[1].Value);source=$record.path;bytes=$record.bytes;sha256=$record.sha256}
    })
}
Export-ModuleMember -Function Read-SpatialSessionComposition,Read-NativeHotloadAssetClosure,New-SpatialSessionClosureInputs
