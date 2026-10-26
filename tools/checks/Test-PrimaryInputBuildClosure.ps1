param([string]$RepoRoot, [string]$OutputRoot)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if (Test-Path -LiteralPath $OutputRoot) { throw 'Fresh owned control output required.' }
$null=New-Item -ItemType Directory -Path $OutputRoot
Import-Module (Join-Path $RepoRoot 'tools/src/SourceBankBuildInputs.psm1') -Force
$utf8=[Text.UTF8Encoding]::new($false)
function Write-Json($Path,$Object) { [IO.File]::WriteAllText($Path,($Object|ConvertTo-Json -Depth 30),$utf8) }
$runtime='quest.stereo.concurrent-inputs';$feature='synthetic-neutral-stereo'
$lock=[ordered]@{selected_features=@($feature);features=@([ordered]@{
 module_id='quest-stereo-input-set';feature_id=$feature;selected=$true;run_activation_default='disabled';owner_lane='quest-adapter';
 activation=@{rule='selected-lock-and-runtime-input';receipt_schema='rusty.quest.stereo_input_set.activation_receipt.v1';runtime_inputs=@($runtime)};
 effects=@{inputs=@($runtime)} })}
$lockPath=Join-Path $OutputRoot 'planning-feature-lock.json';Write-Json $lockPath $lock
$inputs=@{root=$OutputRoot;artifacts=@(@{path='planning-feature-lock.json';sha256=(Get-FileHash $lockPath).Hash.ToLowerInvariant()})}
$shader=Join-Path $OutputRoot 'primary.frag.glsl';[IO.File]::WriteAllText($shader,'synthetic source role only, no GPU compile',$utf8)
$profilePath=Join-Path $OutputRoot 'profile.json'
function Profile($Version,$Shader) {
 $bridge=[ordered]@{stereo_input_set_abi_version=1;own_capture_hold_limits=@{slots=4;bytes=33554432;gpu_uses=16}}
 if ($null -ne $Version) { $bridge.source_bank_final_abi_version=$Version }
 if ($Version -eq 2) {
  $bridge.source_bank_final_descriptor_shape='final-set1-bindings4,6,7,8-array3-plus-final-set5-binding1-std140-64bytes'
  $bridge.source_bank_final_policy_uniform_abi_version=2;$bridge.source_bank_final_policy_uniform_bytes=64
 }
 if ($null -ne $Shader) { $bridge.primary_input_shader=$Shader }
 Write-Json $profilePath @{required_public_bridge=$bridge}
}
function Resolve { Resolve-SourceBankBuildInputs -PrivateLayerProfilePath $profilePath -ValidatedProductInputs $inputs -VertexShaderPath $shader -FragmentShaderPath $shader -ShaderProviderRoot $OutputRoot }
function Deny([scriptblock]$Prepare) { & $Prepare; $denied=$false;try{$null=Resolve}catch{$denied=$true};if(-not $denied){throw 'Expected production closure denial'} }
Profile $null $null;$v1=Resolve
if($v1.final_abi_version -ne 1 -or $null -ne $v1.primary_input_shader){throw 'Original ABI1 changed'}
Profile 2 'primary.frag.glsl';$v2=Resolve
if($v2.final_abi_version -ne 2 -or $v2.primary_input_shader.path -cne $shader -or $v2.primary_input_shader.sha256 -cne (Get-FileHash $shader).Hash.ToLowerInvariant()){throw 'ABI2 shader role/hash missing'}
Deny {Profile 2 $null};Deny {Profile 1 'primary.frag.glsl'};Deny {Profile 3 'primary.frag.glsl'}
Deny {Profile $true 'primary.frag.glsl'};Deny {Profile 2 'missing.frag.glsl'}
Deny {Profile 2 '../outside.frag.glsl'}
Deny {Profile 2 'primary.frag.glsl';$p=Get-Content $profilePath -Raw|ConvertFrom-Json;$p.required_public_bridge.source_bank_final_policy_uniform_bytes=48;Write-Json $profilePath $p}
Deny {Profile 2 'primary.frag.glsl';$p=Get-Content $profilePath -Raw|ConvertFrom-Json;$p.required_public_bridge.source_bank_final_descriptor_shape='array2';Write-Json $profilePath $p}
if($IsWindows){
 $linked=Join-Path $OutputRoot 'linked';$null=New-Item -ItemType Junction -Path $linked -Target $OutputRoot
 try{Deny {Profile 2 'linked/primary.frag.glsl'}}finally{[IO.Directory]::Delete($linked)}
}
Profile 2 'primary.frag.glsl';$before=Resolve
[IO.File]::WriteAllText($shader,'changed actual selected shader bytes',$utf8);$after=Resolve
if($after.primary_input_shader.sha256 -ceq $before.primary_input_shader.sha256){throw 'Shader drift omitted from closure'}
Write-Host 'Production ABI1 unchanged/ABI2 exact primary-role identity, missing/extra/version/Boolean/missing-path denials and actual hash drift PASS'
