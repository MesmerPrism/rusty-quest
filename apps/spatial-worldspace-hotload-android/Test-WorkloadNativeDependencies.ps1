# Offline regression against an actual APK missing its imported core's runtime.
# Additional runtime bytes below exist only in an in-memory test map, never an APK.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RuntimeMissingApk,
    [Parameter(Mandatory)][string]$SessionCoreLibrary,
    [Parameter(Mandatory)][string]$NdkRoot
)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'SpatialNativeDependencies.psm1') -Force
function Reject([scriptblock]$Action,[string]$Message){
    $rejected=$false
    try{& $Action|Out-Null}catch{if($_.Exception.Message -cne $Message){throw};$rejected=$true}
    if(-not $rejected){throw "Expected rejection: $Message"}
    $script:checks++
}
$checks=0
$contract=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'workload-build.lock.json') -Raw|ConvertFrom-Json
$sysroot=Join-Path $NdkRoot 'toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android'
$runtime=[IO.File]::ReadAllBytes((Join-Path $sysroot 'libc++_shared.so'))
$system=@(Get-ChildItem -LiteralPath (Join-Path $sysroot ([string]$contract.android_api)) -Filter '*.so' -File|ForEach-Object Name)
$core=[IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $SessionCoreLibrary).Path)
$coreElf=Get-SpatialElfDependencies -Bytes $core
if($coreElf.needed -cnotcontains 'libc++_shared.so'){throw 'Regression requires the actual runtime-dependent core.'};$checks++
$libraries=Read-SpatialApkNativeLibraries -Apk $RuntimeMissingApk
$coreName='librusty_quest_sdk_session_core.so'
if(-not $libraries.ContainsKey($coreName) -or
   [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($core)) -cne
   [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($libraries[$coreName]))){throw 'APK core differs from supplied canonical bytes.'};$checks++
Reject {Assert-SpatialNativeDependencyClosure -Libraries $libraries -SystemLibraryNames $system} "Missing packaged native dependency: $coreName -> libc++_shared.so"
$libraries.Add('libc++_shared.so',$runtime)
$closure=@(Assert-SpatialNativeDependencyClosure -Libraries $libraries -SystemLibraryNames $system)
if($closure.Count -ne $libraries.Count){throw 'Not every packaged ELF was checked.'};$checks++
Reject {Assert-SpatialNativeDependencyClosure -Libraries $libraries -SystemLibraryNames ($system+'libc++_shared.so')} 'Shared C++ runtime must be packaged, not system-exempted.'
$badCore=$core.Clone()
$neededAt=[Text.Encoding]::ASCII.GetString($badCore).IndexOf("libc++_shared.so`0",[StringComparison]::Ordinal)
if($neededAt -lt 0){throw 'Actual core dependency string absent.'}
$badCore[$neededAt+3]=[byte][char]'x'
$libraries[$coreName]=$badCore
Reject {Assert-SpatialNativeDependencyClosure -Libraries $libraries -SystemLibraryNames $system} "Missing packaged native dependency: $coreName -> libx++_shared.so"
$libraries[$coreName]=$core
$wrongAbi=$runtime.Clone();$wrongAbi[18]=62
Reject {Get-SpatialElfDependencies -Bytes $wrongAbi} 'Expected little-endian ELF64 arm64 shared library.'
$unaligned=$runtime.Clone()
$phoff=[BitConverter]::ToUInt64($unaligned,32);$count=[BitConverter]::ToUInt16($unaligned,56)
$changed=$false
for($i=0;$i -lt $count;$i++){
    $offset=[int]($phoff+56*$i)
    if([BitConverter]::ToUInt32($unaligned,$offset) -eq 1){
        [BitConverter]::GetBytes([uint64]4096).CopyTo($unaligned,$offset+48);$changed=$true;break
    }
}
if(-not $changed){throw 'Actual runtime lacks LOAD segment.'}
Reject {Get-SpatialElfDependencies -Bytes $unaligned} 'ELF LOAD lacks 16KiB alignment.'
Reject {Get-SpatialElfDependencies -Bytes ([byte[]]$runtime[0..62])} 'ELF range outside file.'
[ordered]@{checks=$checks;actual_apk_missing_runtime_rejected=$true;actual_core_bytes_joined=$true;
    in_memory_runtime_completion_only=$true;native_libraries_checked=$closure.Count;
    apk_modified=$false;compiler_operations=0;device_evidence=$false}|ConvertTo-Json
