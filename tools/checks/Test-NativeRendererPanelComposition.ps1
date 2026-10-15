param([string]$RepoRoot=(Join-Path $PSScriptRoot '../..'),[string]$OutputDirectory='',[switch]$SourceOnly)
$ErrorActionPreference='Stop'
$RepoRoot=(Resolve-Path -LiteralPath $RepoRoot).Path
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory=Join-Path $RepoRoot ('local-artifacts/panel-composition/'+[guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Fresh test output required.' }
[void][IO.Directory]::CreateDirectory($OutputDirectory)
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererPanelCompositionSelection.psm1') -Force
$black='RUSTY_QUEST_NATIVE_RENDERER_BACKGROUND_MODE';$neutral='RUSTY_QUEST_NATIVE_MESH_UI_NEUTRAL_CONTROLS'
$previousBlack=[Environment]::GetEnvironmentVariable($black,'Process');$previousNeutral=[Environment]::GetEnvironmentVariable($neutral,'Process')
$originalScene='RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ORIGINAL_SCENE';$mesh='RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_ENABLED'
$previousOriginalScene=[Environment]::GetEnvironmentVariable($originalScene,'Process');$previousMesh=[Environment]::GetEnvironmentVariable($mesh,'Process')
$passed=[Collections.Generic.List[string]]::new()
function Deny([string]$Label,[scriptblock]$Action) { $failed=$false;try { &$Action|Out-Null } catch { $failed=$true };if(-not $failed){throw "Expected rejection: $Label"};$passed.Add($Label) }
function Read-LockPackages([string]$Path) {
    $text=[IO.File]::ReadAllText($Path)
    foreach($match in [regex]::Matches($text,'(?ms)^\[\[package\]\]\r?\n.*?(?=^\[\[package\]\]|\z)')) {
        $fields=@{}
        foreach($field in @('name','version','source','checksum')) {
            $value=[regex]::Match($match.Value,'(?m)^'+$field+' = "([^"\r\n]+)"\r?$')
            $fields[$field]=if($value.Success){$value.Groups[1].Value}else{''}
        }
        if(-not $fields.name -or -not $fields.version){throw 'Malformed owner Cargo.lock package identity.'}
        [pscustomobject]$fields
    }
}
try {
    [Environment]::SetEnvironmentVariable($black,$null,'Process');[Environment]::SetEnvironmentVariable($neutral,$null,'Process')
    [Environment]::SetEnvironmentVariable($originalScene,$null,'Process');[Environment]::SetEnvironmentVariable($mesh,$null,'Process')
    $off=Get-NativeRendererPanelCompositionSelection -FeatureLock $null -BuildEnvByName @{}
    if($off.solid_black_worldspace_selected -or -not $off.neutral_mesh_controls_enabled){throw 'Ordinary mesh defaults changed.'};$passed.Add('ordinary-mesh-defaults')
    $ids=@('renderer.native_scene','ui.native_mesh_panel','renderer.private_particles.original_composable','renderer.background.solid_black.composable')
    $lock=@{selected_feature_ids=$ids};$envValues=@{};$envValues[$black]='solid-black';$envValues[$neutral]='false'
    $selected=Get-NativeRendererPanelCompositionSelection -FeatureLock $lock -BuildEnvByName $envValues
    if(-not $selected.solid_black_worldspace_selected -or $selected.neutral_mesh_controls_enabled){throw 'Original composition not selected.'};$passed.Add('original-composition')
    Deny 'unselected-build-env' { Get-NativeRendererPanelCompositionSelection -FeatureLock $null -BuildEnvByName $envValues }
    Deny 'missing-closed-input' { Get-NativeRendererPanelCompositionSelection -FeatureLock $lock -BuildEnvByName @{} }
    $wrong=$envValues.Clone();$wrong[$neutral]='true'
    Deny 'wrong-neutral-selection' { Get-NativeRendererPanelCompositionSelection -FeatureLock $lock -BuildEnvByName $wrong }
    $missing=@{selected_feature_ids=@($ids|Where-Object {$_ -cne 'ui.native_mesh_panel'})}
    Deny 'missing-mesh-feature' { Get-NativeRendererPanelCompositionSelection -FeatureLock $missing -BuildEnvByName $envValues }
    [Environment]::SetEnvironmentVariable($neutral,'false','Process')
    Deny 'unlocked-ambient-bypass' { Get-NativeRendererPanelCompositionSelection -FeatureLock $null -BuildEnvByName @{} }
    [Environment]::SetEnvironmentVariable($neutral,$null,'Process')
    $originalIds=@('ui.native_mesh_panel.original_product','renderer.private_particles','renderer.background.solid_black','ui.breath_composition_control_panel','input.simultaneous_hands_and_controllers')
    $originalLock=@{selected_feature_ids=$originalIds};$originalEnv=@{};$originalEnv[$neutral]='false';$originalEnv[$mesh]='true';$originalEnv[$originalScene]='true'
    $overlay=Get-NativeRendererPanelCompositionSelection -FeatureLock $originalLock -BuildEnvByName $originalEnv
    if(-not $overlay.original_scene_mesh_selected -or $overlay.neutral_mesh_controls_enabled -or $overlay.solid_black_worldspace_selected){throw 'Original render route was replaced.'};$passed.Add('original-scene-overlay')
    foreach($required in $originalIds){$bad=@{selected_feature_ids=@($originalIds|Where-Object {$_ -cne $required})};Deny ('original-missing-'+$required) {Get-NativeRendererPanelCompositionSelection -FeatureLock $bad -BuildEnvByName $originalEnv}}
    foreach($name in @($neutral,$mesh,$originalScene)){$bad=$originalEnv.Clone();$bad[$name]='invalid';Deny ('original-invalid-'+$name) {Get-NativeRendererPanelCompositionSelection -FeatureLock $originalLock -BuildEnvByName $bad}}
    $mixed=@{selected_feature_ids=$originalIds+@('renderer.native_scene')}
    Deny 'original-scene-depth-hybrid' {Get-NativeRendererPanelCompositionSelection -FeatureLock $mixed -BuildEnvByName $originalEnv}
    [Environment]::SetEnvironmentVariable($originalScene,'true','Process')
    Deny 'original-scene-ambient-bypass' {Get-NativeRendererPanelCompositionSelection -FeatureLock $null -BuildEnvByName @{}}
    [Environment]::SetEnvironmentVariable($originalScene,$null,'Process')
    # Compile the real owner modules with real serde_json, not copied logic or JSON
    # stubs. The native workspace lock pins its crate bytes and transitive versions.
    # Cargo resolves offline; this host gate neither links Android nor proves pixels.
    $src=Join-Path $RepoRoot 'apps/native-renderer-android/native/src'
    $harness=Join-Path $OutputDirectory 'owner-modules.rs'
    $modules=@('native_renderer_properties','native_renderer_property_values','native_renderer_visual_options','native_mesh_ui','native_scene_composition')
    ($modules|ForEach-Object {'#[path = "'+(Join-Path $src ($_+'.rs')).Replace('\','/')+'"] mod '+$_+';'})|Set-Content -LiteralPath $harness
    $ownerLock=Join-Path $RepoRoot 'Cargo.lock'
    $nativeManifest=Join-Path $RepoRoot 'apps/native-renderer-android/native/Cargo.toml'
    $ownerLockHash=(Get-FileHash -LiteralPath $ownerLock).Hash.ToLowerInvariant()
    $nativeManifestHash=(Get-FileHash -LiteralPath $nativeManifest).Hash.ToLowerInvariant()
    $ownerPackages=@(Read-LockPackages $ownerLock)
    $jsonPackage=@($ownerPackages|Where-Object name -ceq 'serde_json')
    if($jsonPackage.Count -ne 1 -or $jsonPackage[0].source -cne 'registry+https://github.com/rust-lang/crates.io-index' -or $jsonPackage[0].checksum -cnotmatch '^[0-9a-f]{64}$'){throw 'Native Cargo.lock must pin one registry serde_json package.'}
    if([IO.File]::ReadAllText($nativeManifest) -cnotmatch '(?m)^serde_json\s*=\s*"1"\s*$'){throw 'Native serde_json dependency contract changed; review the focused harness dependency.'}
    $cargoManifest=Join-Path $OutputDirectory 'Cargo.toml'
    @"
[package]
name = "rq-native-panel-owner-tests"
version = "0.0.0"
edition = "2021"
publish = false

[workspace]

[lib]
path = "owner-modules.rs"

[dependencies]
serde_json = "=$($jsonPackage[0].version)"
"@|Set-Content -LiteralPath $cargoManifest
    $harnessLock=Join-Path $OutputDirectory 'Cargo.lock'
    Copy-Item -LiteralPath $ownerLock -Destination $harnessLock
    $cargoTarget=Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) 'target'
    $dependencyAuthority=@{native_manifest_sha256=$nativeManifestHash;owner_lock_sha256=$ownerLockHash;serde_json=$jsonPackage[0];offline=$true;resolved=$false;target_directory=$cargoTarget}
    if(-not $SourceOnly) {
        # Retain owner pins while Cargo removes unrelated workspace packages and
        # records this isolated test package. Reject any dependency identity drift.
        & cargo update --offline --workspace --manifest-path $cargoManifest *> (Join-Path $OutputDirectory 'cargo-lock.log')
        if($LASTEXITCODE -ne 0){throw 'Offline owner-pinned test lock resolution failed.'}
        $resolved=@(Read-LockPackages $harnessLock)
        foreach($package in $resolved) {
            if($package.name -ceq 'rq-native-panel-owner-tests' -and $package.version -ceq '0.0.0' -and -not $package.source){continue}
            $pin=@($ownerPackages|Where-Object {$_.name -ceq $package.name -and $_.version -ceq $package.version -and $_.source -ceq $package.source -and $_.checksum -ceq $package.checksum})
            if($pin.Count -ne 1 -or -not $package.source -or -not $package.checksum){throw "Test dependency differs from native Cargo.lock: $($package.name) $($package.version)"}
        }
        $resolvedJson=@($resolved|Where-Object name -ceq 'serde_json')
        if($resolvedJson.Count -ne 1 -or $resolvedJson[0].version -cne $jsonPackage[0].version){throw 'Resolved serde_json does not match the native owner pin.'}
        if((Get-FileHash -LiteralPath $ownerLock).Hash.ToLowerInvariant() -cne $ownerLockHash -or (Get-FileHash -LiteralPath $nativeManifest).Hash.ToLowerInvariant() -cne $nativeManifestHash){throw 'Native dependency authority changed during harness preparation.'}
        $dependencyAuthority.resolved=$true
        $dependencyAuthority.harness_lock_sha256=(Get-FileHash -LiteralPath $harnessLock).Hash.ToLowerInvariant()
        $dependencyAuthority.packages=$resolved
        $rustVersion=@(& rustc -vV)
        if($LASTEXITCODE -ne 0){throw 'Unable to observe actual host Rust target.'}
        $hostTarget=@($rustVersion|Where-Object{$_ -cmatch '^host: '}|ForEach-Object{$_.Substring(6)})
        if($hostTarget.Count -ne 1 -or $hostTarget[0] -cnotmatch '^[A-Za-z0-9_-]+$'){throw 'Actual host Rust target unavailable.'}
        $dependencyAuthority.host_target=$hostTarget[0]
    }
    foreach($mode in $(if($SourceOnly){@()}else{@('ordinary','original','original-scene')})) {
        [Environment]::SetEnvironmentVariable($black,$(if($mode -eq 'original'){'solid-black'}else{$null}),'Process')
        [Environment]::SetEnvironmentVariable($neutral,$(if($mode -ne 'ordinary'){'false'}else{$null}),'Process')
        [Environment]::SetEnvironmentVariable($originalScene,$(if($mode -eq 'original-scene'){'true'}else{$null}),'Process')
        [Environment]::SetEnvironmentVariable($mesh,$(if($mode -eq 'original-scene'){'true'}else{$null}),'Process')
        $started=[DateTime]::UtcNow
        & cargo test --offline --locked --manifest-path $cargoManifest --target-dir $cargoTarget --target $hostTarget[0] --lib --no-run *> (Join-Path $OutputDirectory ($mode+'.compile.log'))
        if($LASTEXITCODE -ne 0){throw "Owner module compile failed: $mode"}
        & cargo test --offline --locked --manifest-path $cargoManifest --target-dir $cargoTarget --target $hostTarget[0] --lib *> (Join-Path $OutputDirectory ($mode+'.tests.log'))
        if($LASTEXITCODE -ne 0){throw "Owner module tests failed: $mode"}
        @{mode=$mode;started_utc=$started.ToString('o');finished_utc=[DateTime]::UtcNow.ToString('o');outer_seconds=([DateTime]::UtcNow-$started).TotalSeconds}|ConvertTo-Json|Set-Content (Join-Path $OutputDirectory ($mode+'.process.json'))
        $passed.Add('actual-owner-modules-'+$mode)
    }
} finally {
    [Environment]::SetEnvironmentVariable($black,$previousBlack,'Process');[Environment]::SetEnvironmentVariable($neutral,$previousNeutral,'Process')
    [Environment]::SetEnvironmentVariable($originalScene,$previousOriginalScene,'Process');[Environment]::SetEnvironmentVariable($mesh,$previousMesh,'Process')
}
@{passed=@($passed);count=$passed.Count;source_only=[bool]$SourceOnly;dependency_authority=$dependencyAuthority;apk_builds=0;device_operations=0}|ConvertTo-Json -Depth 7|Set-Content (Join-Path $OutputDirectory 'result.json')
Write-Output "Native panel composition host checks passed $($passed.Count) cases."
