Set-StrictMode -Version Latest

function Assert-SpatialWorkloadPermissions {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$ContractPackageName,
        [Parameter(Mandatory)][string]$SelectedPackageName,
        [Parameter(Mandatory)][string[]]$PermissionAllowlist,
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$ObservedPermissions
    )
    # The caller has admitted the selected application ID. AndroidX generates
    # this one permission from that ID; every other contract entry stays literal.
    $suffix='.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
    $sentinel=$ContractPackageName+$suffix
    $resolved=@($PermissionAllowlist | ForEach-Object {
        if($_ -ceq $sentinel){$SelectedPackageName+$suffix}else{$_}
    })
    foreach($permission in $ObservedPermissions){
        if($resolved -cnotcontains $permission){throw 'APK permission exceeds explicit probe allowlist.'}
    }
}

Export-ModuleMember -Function Assert-SpatialWorkloadPermissions
