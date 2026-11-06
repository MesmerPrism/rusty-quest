[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'SpatialWorkloadPermissions.psm1') -Force
$contract=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'workload-build.lock.json') -Raw | ConvertFrom-Json
$neutral=[string]$contract.package_name
$selected='org.example.private_workload'
$suffix='.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
$capabilities=@('com.oculus.permission.HAND_TRACKING')
$checks=0
function Check([string]$package,[string[]]$permissions,[bool]$accepted,[string[]]$allowlist=$contract.permission_allowlist){
    $rejected=$false
    try{
        Assert-SpatialWorkloadPermissions -ContractPackageName $neutral -SelectedPackageName $package `
            -PermissionAllowlist $allowlist -ObservedPermissions $permissions
    }catch{
        if($_.Exception.Message -cne 'APK permission exceeds explicit probe allowlist.'){throw}
        $rejected=$true
    }
    if($rejected -eq $accepted){throw "Unexpected permission admission for $package : $($permissions -join ',')"}
    $script:checks++
}
Check $neutral ($capabilities+($neutral+$suffix)) $true
Check $selected ($capabilities+($selected+$suffix)) $true
Check $selected ($capabilities+($neutral+$suffix)) $false
Check $neutral ($capabilities+($selected+$suffix)) $false
Check $selected ($capabilities+('org.example.foreign'+$suffix)) $false
Check $selected ($capabilities+($selected+$suffix)+('org.example.foreign'+$suffix)) $false
foreach($extra in @('android.permission.INTERNET','android.permission.CAMERA','android.permission.BLUETOOTH_CONNECT','android.permission.RECORD_AUDIO')){
    Check $selected ($capabilities+($selected+$suffix)+$extra) $false
}
Check $selected ($capabilities+($selected+$suffix.ToLowerInvariant())) $false
# Absence of the exact sentinel must not grant a generated permission.
Check $selected ($capabilities+($selected+$suffix)) $false $capabilities
# A similarly prefixed contract permission remains literal, never remapped.
$literal=$neutral+'.OTHER_PERMISSION'
Check $selected ($capabilities+$literal) $true ($capabilities+$literal)
Check $selected ($capabilities+($selected+'.OTHER_PERMISSION')) $false ($capabilities+$literal)
Write-Output "Workload permission checks passed: $checks; no compiler or device operations."
