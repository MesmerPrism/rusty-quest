param([string]$RepoRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference='Stop'
Import-Module (Join-Path $RepoRoot 'tools/lib/AndroidNativeSourcePaths.psm1') -Force
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('quest-cmake-paths-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
try {
    [IO.File]::WriteAllText((Join-Path $scratch 'source.cpp'),'int nominal;')
    $objectRoot=Join-Path $scratch 'obj'
    $result=Assert-AndroidNativeSourcePaths $scratch $objectRoot
    if($result.status -cne 'passed' -or $result.source_count -ne 1){throw 'Short actual source rejected.'}
    try{$null=Assert-AndroidNativeSourcePaths $scratch ($objectRoot+('x'*250));throw 'Long object accepted.'}
    catch{if($_.Exception.Message -notlike '*source-derived object*'){throw}}
    $long=Join-Path $scratch ('x'*80)
    $long=Join-Path $long ('y'*80)
    New-Item -ItemType Directory -Path $long -Force | Out-Null
    $length=261-$long.Length-1-4
    [IO.File]::WriteAllText((Join-Path $long (('z'*$length)+'.cpp')),'int too_long;')
    try{$null=Assert-AndroidNativeSourcePaths $scratch $objectRoot;throw 'Long source accepted.'}
    catch{if($_.Exception.Message -notlike '*Ninja 260-character*'){throw}}
    'Actual selected CMake source short/261-character refusal controls passed'
} finally {
    $resolved=[IO.Path]::GetFullPath($scratch)
    if($resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolved) -match '^quest-cmake-paths-[a-f0-9]{32}$'){
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
