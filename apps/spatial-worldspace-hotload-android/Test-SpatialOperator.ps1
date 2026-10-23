[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'SpatialOperatorPackaging.psm1') -Force
$count=0
function Check([bool]$value,[string]$name){if(-not$value){throw "Operator check failed: $name"};$script:count++}
function Deny([scriptblock]$action,[string]$name){$failed=$false;try{& $action}catch{$failed=$true};Check $failed $name}
$base='io.github.mesmerprism.rustyquest.spatial_worldspace_hotload'
Check ((Get-SpatialOperatorPackage -Enabled $false -DevelopmentFeedback $false) -ceq $base) 'default-package'
Check ((Get-SpatialOperatorPackage -Enabled $true -DevelopmentFeedback $true) -ceq ($base+'.operator')) 'coinstalled-package'
Deny {Get-SpatialOperatorPackage -Enabled $true -DevelopmentFeedback $false} 'requires-public-feedback'
$selected=Read-SpatialOperatorManifest -Enabled $true
Check ($selected.package -ceq ($base+'.operator')) 'manifest-package'
Check ($selected.extract_native_libs -and $selected.internet -and $selected.experimental_optional) 'physical-layer-manifest'
$plain=Read-SpatialOperatorManifest -Enabled $false
Check (-not$plain.internet -and -not$plain.extract_native_libs) 'unselected-inert'
$now=[DateTimeOffset]::UtcNow
$build=[pscustomobject]@{id='build';resource='apk-build:rusty-quest-sdk-hotload';status='active';lease_until=$now.AddMinutes(10).ToString('o');owner='actor'}
$fixture=Join-Path ([IO.Path]::GetTempPath()) 'sdk-lease-test'
$cache=[pscustomobject]@{id='cache';resource=('build-cache:'+([IO.Path]::GetFullPath((Join-Path $fixture 'gradle'))));status='active';lease_until=$now.AddMinutes(10).ToString('o');owner='actor'}
$leaseArguments=@{Board=[pscustomobject]@{active_leases=@($build,$cache)};BuildLeaseId='build';CacheLeaseId='cache';GradleUserHome=(Join-Path $fixture 'gradle');IntermediateRoot=(Join-Path $fixture 'i');OutputRoot=(Join-Path $fixture 'o');SourceRoot=(Split-Path (Split-Path $PSScriptRoot))}
Check ((Assert-SpatialOperatorBuildLease @leaseArguments).id -ceq 'build') 'isolated-lease'
$build.resource='apk-build:foreign';Deny {Assert-SpatialOperatorBuildLease @leaseArguments} 'wrong-resource';$build.resource='apk-build:rusty-quest-sdk-hotload'
$build.lease_until=$now.AddMinutes(-1).ToString('o');Deny {Assert-SpatialOperatorBuildLease @leaseArguments} 'expired';$build.lease_until=$now.AddMinutes(10).ToString('o')
$cache.owner='foreign';Deny {Assert-SpatialOperatorBuildLease @leaseArguments} 'foreign-cache-owner';$cache.owner='actor'
$cache.resource='build-cache:foreign';Deny {Assert-SpatialOperatorBuildLease @leaseArguments} 'wrong-cache';$cache.resource='build-cache:'+([IO.Path]::GetFullPath($leaseArguments.GradleUserHome))
$cache.lease_until=$now.AddMinutes(-1).ToString('o');Deny {Assert-SpatialOperatorBuildLease @leaseArguments} 'expired-cache';$cache.lease_until=$now.AddMinutes(10).ToString('o')
$leaseArguments.BuildLeaseId='foreign';Deny {Assert-SpatialOperatorBuildLease @leaseArguments} 'unowned-build';$leaseArguments.BuildLeaseId='build'
$cache.resource=$cache.resource.Replace('\','/');Check ((Assert-SpatialOperatorBuildLease @leaseArguments).id-ceq'build') 'cache-path-spelling'
$build.resource='apk-build:rusty-quest';Check ((Assert-SpatialOperatorBuildLease @leaseArguments).id-ceq'build') 'legacy-resource';$build.resource='apk-build:rusty-quest-sdk-hotload'
$leaseArguments.OutputRoot=$leaseArguments.IntermediateRoot;Deny {Assert-SpatialOperatorBuildLease @leaseArguments} 'overlapping-build-roots'
Write-Output "Spatial Operator checks passed: $count"
