[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InputsJson,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedInputsSha256,
    [Parameter(Mandatory)][string]$AndroidNdkRoot,
    [Parameter(Mandatory)][string]$CargoPath,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$CargoLockSha256,
    [Parameter(Mandatory)][string]$OutputRoot,
    [ValidateRange(29,35)][int]$AndroidApi = 29
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Require-File($Entry) {
    if (-not [IO.Path]::IsPathRooted([string]$Entry.path)) { throw 'Every selected input must have an absolute path.' }
    if ((Hash ([string]$Entry.path)) -cne [string]$Entry.sha256) { throw "Selected input hash mismatch: $($Entry.path)" }
}
$core = Split-Path $PSScriptRoot -Parent
$repo = [IO.Path]::GetFullPath((Join-Path $core '../../..'))
if ((Hash $InputsJson) -cne $ExpectedInputsSha256) { throw 'Core input manifest hash mismatch.' }
$inputSpec = Get-Content -LiteralPath $InputsJson -Raw | ConvertFrom-Json -AsHashtable
if ($inputSpec.schema -cne 'rusty.quest.sdk_session_core.inputs.v1') { throw 'Unsupported core input schema.' }
foreach ($name in @('feature_lock','profile','provider_manifest','provider_inventory','settings')) { Require-File $inputSpec.files[$name] }
$lock = Get-Content -LiteralPath $inputSpec.files.feature_lock.path -Raw | ConvertFrom-Json -AsHashtable
$selected = @($lock.selected_feature_ids)
$excludedFeatureToken = '(^|[._-])(polar|kiosk|ble|boot|gateway|streaming|relay)([._-]|$)'
if ($selected -notcontains 'ui.experiment_session_owner' -or @($selected | Where-Object { $_ -match $excludedFeatureToken }).Count -gt 0) { throw 'SDK standalone source selection is not closed.' }
$profile = Get-Content -LiteralPath $inputSpec.files.profile.path -Raw | ConvertFrom-Json -AsHashtable
if ($profile.schema_id -cne 'rusty.viscereality.experiment_session_profile.standalone.v2' -or $profile.kiosk_requested_default -isnot [bool] -or $profile.kiosk_requested_default) { throw 'Standalone profile v2 required.' }
$settings = Get-Content -LiteralPath $inputSpec.files.settings.path -Raw | ConvertFrom-Json -AsHashtable
if ($settings.schema -cne 'rusty.quest.sdk_session_settings.v1' -or $settings.radius_slot -ne 0 -or $settings.deformation_slot -ne 0) { throw 'Unsupported exact SDK settings layout.' }
$anchors = @{
    RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_PROFILE_SHA256 = $inputSpec.files.profile.sha256
    RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_PROVIDER_MANIFEST_SHA256 = $inputSpec.files.provider_manifest.sha256
    RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_INVENTORY_SHA256 = $inputSpec.files.provider_inventory.sha256
    RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_EXPECTED_BINDING_SHA256 = $inputSpec.composition_binding_sha256
    RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_DRIVER_EXPECTED_BINDING_SHA256 = $inputSpec.driver_binding_sha256
    RQ_SDK_SESSION_SETTINGS_SHA256 = $inputSpec.files.settings.sha256
}
foreach ($value in $anchors.Values) { if ([string]$value -cnotmatch '^[a-f0-9]{64}$' -or [string]$value -ceq ('0'*64)) { throw 'Missing or malformed core compile anchor.' } }
if ($settings.properties['debug.rustyquest.native_renderer.breath_composition.activation.binding_sha256'] -cne $inputSpec.composition_binding_sha256 -or
    $settings.properties['debug.rustyquest.native_renderer.private_particles.breath_composition_driver.activation.binding_sha256'] -cne $inputSpec.driver_binding_sha256) { throw 'Resolved settings activation binding mismatch.' }
$cargoLock = Join-Path $core 'Cargo.lock'
if ((Hash $cargoLock) -cne $CargoLockSha256) { throw 'Qualified core Cargo.lock mismatch.' }
$clang = Join-Path $AndroidNdkRoot "toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android$AndroidApi-clang.cmd"
$clangxx = Join-Path $AndroidNdkRoot "toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android$AndroidApi-clang++.cmd"
foreach ($path in @($CargoPath,$clang,$clangxx)) { if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required preinstalled tool absent: $path" } }
$out = [IO.Path]::GetFullPath($OutputRoot)
if (-not [IO.Path]::IsPathRooted($OutputRoot) -or (Test-Path -LiteralPath $out)) { throw 'OutputRoot must be a new absolute isolated directory.' }
$sourcePaths = @(
    Get-ChildItem -LiteralPath $core -Recurse -File | Where-Object { $_.FullName -notmatch '[\\/](target|\.git)[\\/]' } | ForEach-Object FullName
    Get-ChildItem -LiteralPath (Join-Path $repo 'android/openxr-submission-observer') -File | ForEach-Object FullName
)
# Resolve every shared Rust source directly from the lib root and materialization include.
$libText = Get-Content -LiteralPath (Join-Path $core 'src/lib.rs') -Raw
foreach ($match in [regex]::Matches($libText, '#\[path = "([^"]+)"\]')) {
    $sourcePaths += [IO.Path]::GetFullPath((Join-Path (Join-Path $core 'src') $match.Groups[1].Value))
}
$sourcePaths += Join-Path $repo 'apps/native-renderer-android/native/src/experiment_session_materialization.rs'
$breathDependency=Join-Path $repo 'crates/rusty-quest-breath-contract'
$sourcePaths += Join-Path $breathDependency 'Cargo.toml'
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $breathDependency 'src') -Recurse -File | ForEach-Object FullName)
$featurePath = Join-Path $repo 'fixtures/native-app-features/ui/experiment-session-owner/ui.experiment_session_owner.feature.json'
$ownerFeature = Get-Content -LiteralPath $featurePath -Raw | ConvertFrom-Json -AsHashtable
# The native Activity entry is replaced by the SDK base; helpers remain canonical.
$canonicalJava = @($ownerFeature.panel_composition.modules | ForEach-Object { $_.source_files } | Where-Object { [IO.Path]::GetFileName($_) -cne 'ExperimentSessionOwnerPanelModule.java' })
# The shared shell also depends on this pure policy outside panel-module roots.
$canonicalJava += 'apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/NativeRendererWriterAcknowledgedExitPolicy.java'
$sourcePaths += $featurePath
$facadeGenerator=Join-Path $repo 'tools/lib/NativeRendererExperimentSessionFacade.psm1'
$sourcePaths += $facadeGenerator
foreach ($relative in $canonicalJava) { $sourcePaths += Join-Path $repo $relative }
$sources = @($sourcePaths | Sort-Object -Unique | ForEach-Object { @{ path=$_; sha256=(Hash $_) } })
New-Item -ItemType Directory -Path $out | Out-Null
$environment = @{} + $anchors
$environment.RUSTY_QUEST_NATIVE_RENDERER_EXPERIMENT_KIOSK_REQUESTED = 'false'
$environment.RQ_SDK_SESSION_SETTINGS_FILE = $inputSpec.files.settings.path
$environment.CARGO_TARGET_DIR = Join-Path $out 'cargo-target'
$environment.CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $clang
$environment.CC_aarch64_linux_android = $clang
$environment.CXX_aarch64_linux_android = $clangxx
$environment.AR_aarch64_linux_android = Join-Path $AndroidNdkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-ar.exe'
$saved = @{}
try {
    foreach ($name in $environment.Keys) { $saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process'); [Environment]::SetEnvironmentVariable($name,[string]$environment[$name],'Process') }
    & $CargoPath build --manifest-path (Join-Path $core 'Cargo.toml') --target aarch64-linux-android --release --locked --offline --jobs 1 *> (Join-Path $out 'cargo-build.log')
    if ($LASTEXITCODE -ne 0) { throw "Core build failed; inspect $out/cargo-build.log" }
} finally { foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') } }
foreach ($source in $sources) { if ((Hash $source.path) -cne $source.sha256) { throw "Source changed during build: $($source.path)" } }
$librarySource = Join-Path $out 'cargo-target/aarch64-linux-android/release/librusty_quest_sdk_session_core.so'
$library = Join-Path $out 'librusty_quest_sdk_session_core.so'
Copy-Item -LiteralPath $librarySource -Destination $library
Copy-Item -LiteralPath (Join-Path $core 'java') -Destination (Join-Path $out 'java') -Recurse
$canonicalOut = Join-Path $out 'canonical-java'
New-Item -ItemType Directory -Path $canonicalOut | Out-Null
$canonicalOutputs = @()
foreach ($relative in $canonicalJava) {
    $source = Join-Path $repo $relative
    $destination = Join-Path $canonicalOut ([IO.Path]::GetFileName($source))
    if (Test-Path -LiteralPath $destination) { throw 'Duplicate canonical Java class source.' }
    Copy-Item -LiteralPath $source -Destination $destination
    $canonicalOutputs += @{ path=$destination; source=$relative; sha256=(Hash $destination) }
}
Import-Module $facadeGenerator -Force
$facadeArgs=@{
    SessionMembersOnly=$true; standaloneExperimentOwner=$true; isExperimentSessionPanel=$true
    standaloneExperimentOwnerJava='true'
    experimentSessionProfileSha256=$inputSpec.files.profile.sha256
    experimentSessionProviderManifestSha256=$inputSpec.files.provider_manifest.sha256
    experimentSessionInventorySha256=$inputSpec.files.provider_inventory.sha256
}
$facadeMembers=Join-Path $out 'ControlPanelActivity.session-members.java.txt'
[IO.File]::WriteAllText($facadeMembers,(New-NativeRendererExperimentSessionFacadeSource @facadeArgs),[Text.UTF8Encoding]::new($false))
$generatedFacade=& (Join-Path $PSScriptRoot 'New-SessionOwnerActivity.ps1') -OutputRoot (Join-Path $out 'java') -ProfileSha256 $inputSpec.files.profile.sha256 -ProviderManifestSha256 $inputSpec.files.provider_manifest.sha256 -InventorySha256 $inputSpec.files.provider_inventory.sha256
$java = @(Get-ChildItem -LiteralPath (Join-Path $out 'java') -Recurse -File | ForEach-Object { @{path=$_.FullName;sha256=(Hash $_.FullName)} })
$manifest = @{
    schema='rusty.quest.sdk_session_core.build.v1'; inputs_sha256=$ExpectedInputsSha256
    cargo_lock_sha256=$CargoLockSha256; target='aarch64-linux-android'; android_api=$AndroidApi; cargo_jobs=1
    library=@{path=$library;sha256=(Hash $library)}; java_adapter_sources=$java; canonical_owner_sources=$canonicalOutputs; compile_anchors=$anchors
    sources=$sources; ndk=@{path=$AndroidNdkRoot;clang_sha256=(Hash $clang);clangxx_sha256=(Hash $clangxx)}
    generated_control_panel_audio_closure=@{members_path=$facadeMembers;members_sha256=(Hash $facadeMembers);generator_path=$facadeGenerator;generator_sha256=(Hash $facadeGenerator);wrapper_compilation_required=$true}
    qualification=@{native_core_compiled=$true;java_closure_compiled=$false;live_loader_proven=$false;session_recording_proven=$false;sdk_pixels_proven=$false}
}
$manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $out 'session-core-build.json') -Encoding utf8NoBOM
Write-Output (Join-Path $out 'session-core-build.json')
