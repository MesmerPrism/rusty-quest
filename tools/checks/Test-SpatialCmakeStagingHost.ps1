$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '../lib/SpatialCmakeStaging.psm1') -Force
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('rq-cmake-'+[Guid]::NewGuid().ToString('N'))
$compiled=$false
$good=Get-SpatialCmakeStaging -BuildCacheRoot $scratch -ProductId 'spatial-camera-panel' -BuildType debug
if(Test-Path $scratch){throw 'Preflight mutated fresh cache'}
if($good.path -cne (Join-Path $scratch 'g/spatial-camera-panel-debug/x')){throw 'Wrong cache lane'}
if($good.projected_object_path_chars-ge250){throw 'Fixture cache unexpectedly long'}
$receipt=Get-SpatialCmakeStagingReceipt -Staging $good
$expected=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($good.path))).ToLowerInvariant()
if($receipt.selected_path_sha256-cne$expected-or($receipt|ConvertTo-Json -Compress).Contains($scratch)-or$receipt.Contains('path')){throw 'Receipt leaked path or lost exact selection hash'}
foreach($input in @(@{BuildCacheRoot='relative';ProductId='spatial-camera-panel';BuildType='debug'},
    @{BuildCacheRoot=$scratch;ProductId='../escape';BuildType='debug'})) {
    $denied=$false;try{$null=Get-SpatialCmakeStaging @input;$compiled=$true}catch{$denied=$true}
    if(-not$denied-or$compiled-or(Test-Path $scratch)){throw 'Unsafe geometry reached compiler or mutated cache'}
}
if($IsWindows){
    $deep=Join-Path $scratch ('a'*180)
    $denied=$false;try{$null=Get-SpatialCmakeStaging -BuildCacheRoot $deep -ProductId 'spatial-camera-panel' -BuildType debug;$compiled=$true}catch{$denied=$true}
    if(-not$denied-or$compiled-or(Test-Path $scratch)){throw 'Deep cache reached compiler or mutated disk'}
    $link=Join-Path $scratch 'g';$target=Join-Path $scratch 'target'
    try {
        New-Item -ItemType Directory -Path $target -Force|Out-Null
        New-Item -ItemType Junction -Path $link -Target $target|Out-Null
        $denied=$false;try{$null=Get-SpatialCmakeStaging -BuildCacheRoot $scratch -ProductId 'spatial-camera-panel' -BuildType debug;$compiled=$true}catch{$denied=$true}
        if(-not$denied-or$compiled-or@(Get-ChildItem -LiteralPath $target -Force).Count){throw 'Linked staging reached compiler or wrote target'}
    } finally {
        if(Test-Path -LiteralPath $link){Remove-Item -LiteralPath $link -Force}
        if(Test-Path -LiteralPath $target){Remove-Item -LiteralPath $target -Force}
        if(Test-Path -LiteralPath $scratch){Remove-Item -LiteralPath $scratch -Force}
    }
}
$builder=Get-Content -Raw (Join-Path $PSScriptRoot '../Build-SpatialCameraPanelAndroid.ps1')
if($builder.IndexOf('Get-SpatialCmakeStaging -BuildCacheRoot')-gt$builder.IndexOf('New-Item -ItemType Directory -Force -Path $BuildCacheRoot')){throw 'Preflight follows cache writes'}
if(-not$builder.Contains('"-PrqCmakeStagingDirectory=$($cmakeStaging.path)"')){throw 'Selected staging not passed explicitly'}
'PASS production CMake staging geometry: short cache, relative/traversal/deep denials, no writes/compiler on refusal'
