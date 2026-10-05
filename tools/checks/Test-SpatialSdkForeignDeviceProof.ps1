param(
    [string]$RepoRoot=(Join-Path $PSScriptRoot '../..'),
    [Parameter(Mandatory)][string]$CppCompiler,
    [string]$OutDir=(Join-Path ([IO.Path]::GetTempPath()) ('spatial-sdk-device-proof-'+[guid]::NewGuid().ToString('N')))
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$owner=Join-Path $RepoRoot 'apps/spatial-camera-panel-android/app/src/main/cpp/spatial_depth_layer/spatial_depth_api_layer.cpp'
$raw=[IO.File]::ReadAllText($owner)
$match=[regex]::Match($raw,'(?s)      // A later auxiliary device must not replace the bound SDK device''s proof\..*?      }\r?\n(?=      gState.vulkanDeviceSwapchainRequested =)')
if(-not$match.Success){throw 'Missing bound SDK-device observation guard'}
$new=$match.Value
$predicate=[regex]::Match($new,'(?s)        gState.vulkanDeviceForeignQueueOwnershipEnabled =.*?\? \*vulkanDevice : VK_NULL_HANDLE;')
if(-not$predicate.Success){throw 'Missing exact foreign device proof assignment'}
$body=$predicate.Value
if(-not$raw.Contains('gState.foreignQueueOwnershipDevice == gState.sdkVulkanBinding.device')){throw 'Missing exact SDK-device capability binding'}
if(Test-Path -LiteralPath $OutDir){throw 'Test output directory must be create-new'}
[void](New-Item -ItemType Directory -Path $OutDir)
$common=@'
using VkDevice=int*;using VkResult=int;using XrResult=int;
constexpr int XR_SUCCESS=0,VK_SUCCESS=0;
constexpr VkDevice VK_NULL_HANDLE=nullptr;
constexpr const char* VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME="VK_EXT_queue_family_foreign";
struct State {struct Binding {VkDevice device=nullptr;} sdkVulkanBinding;bool vulkanDeviceForeignQueueOwnershipEnabled=false;VkDevice foreignQueueOwnershipDevice=nullptr;};
'@
function Function([string]$name,[string]$code){@"
constexpr void $name(State& gState,bool supported,bool forwarded,XrResult result,VkResult* vulkanResult,VkDevice* vulkanDevice){
 bool foreignOwnershipRequested=true,foreignOwnershipSupported=supported,foreignOwnershipEnumerationCallable=true;
 const auto forwardedHasExtension=[forwarded](const char*){return forwarded;};
$code
}
"@}
$tests=@'
constexpr bool matches(const State& s){return s.vulkanDeviceForeignQueueOwnershipEnabled && s.foreignQueueOwnershipDevice==s.sdkVulkanBinding.device;}
constexpr bool caseSequence(int scenario,bool repair){
 int sdk=1,aux=2;VkDevice d1=&sdk,d2=&aux;VkResult ok=0,bad=-1;State s;
 auto create=[&](bool support,bool forwarded,int xr,VkResult* vk,VkDevice* d){if(repair)fixed(s,support,forwarded,xr,vk,d);else original(s,support,forwarded,xr,vk,d);};
 create(scenario!=4,scenario!=5,0,&ok,&d1);
 s.sdkVulkanBinding.device=d1;
 if(scenario==1){create(true,true,0,&ok,&d1);} // Same bound device remains authenticated.
 else if(scenario==2){create(true,true,0,&bad,&d1);} // Failed same-device proof is rejected.
 else if(scenario==3){create(true,true,0,&bad,&d2);} // Unrelated failed device preserves SDK proof.
 else if(scenario==6){s.sdkVulkanBinding.device=d2;} // Wrong binding never passes.
 else if(scenario==7){create(true,true,0,nullptr,&d1);} // Missing result for bound device rejects.
 else {create(true,true,0,&ok,&d2);} // Actual successful auxiliary creation after session bind.
 return matches(s);
}
static_assert(!caseSequence(0,false)); // Actual old last-observation overwrite reproduces.
static_assert(caseSequence(0,true));   // SDK then auxiliary remains exact SDK proof.
static_assert(caseSequence(1,true));
static_assert(!caseSequence(2,true));
static_assert(caseSequence(3,true));
static_assert(!caseSequence(4,true)); // Unsupported SDK cannot borrow auxiliary support.
static_assert(!caseSequence(5,true)); // Unforwarded SDK cannot borrow auxiliary support.
static_assert(!caseSequence(6,true)); // Wrong/unproved binding remains rejected.
static_assert(!caseSequence(7,true));
'@
$test=Join-Path $OutDir 'bound_sdk_device_sequence.cpp'
[IO.File]::WriteAllText($test,$common+"`n"+(Function original $body)+(Function fixed $new)+$tests,[Text.UTF8Encoding]::new($false))
& $CppCompiler -std=c++20 -fsyntax-only $test
if($LASTEXITCODE-ne0){throw 'Bound SDK proof sequence regression failed'}
[ordered]@{status='passed';cases=9;source_sha256=(Get-FileHash $owner).Hash.ToLowerInvariant();compiler_sha256=(Get-FileHash $CppCompiler).Hash.ToLowerInvariant();devices_invoked=$false;apk_built=$false}|ConvertTo-Json|Set-Content (Join-Path $OutDir 'result.json')
Write-Host 'Bound SDK device proof: 9 sequence cases PASS'