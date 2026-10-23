[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SessionCompositionManifest,
    [Parameter(Mandatory)][string]$PrivateAppIdentityManifest,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion -lt [version]'7.6'){throw 'Requires pwsh 7.6 or newer.'}
Import-Module (Join-Path $PSScriptRoot 'SpatialSessionComposition.psm1') -Force
New-SpatialSessionClosureInputs -Manifest $SessionCompositionManifest -IdentityManifest $PrivateAppIdentityManifest -OutputDirectory $OutputDirectory | ConvertTo-Json -Depth 8
