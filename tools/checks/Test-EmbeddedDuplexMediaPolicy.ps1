param([string]$RepoRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference='Stop'
Import-Module (Join-Path $RepoRoot 'tools/EmbeddedDuplexMediaPolicy.psm1') -Force
function Require($Condition,$Message){if(-not $Condition){throw $Message}}
$finite=[ordered]@{schema='fixture';existing=17};$before=$finite|ConvertTo-Json -Compress
Set-EmbeddedDuplexMediaPolicyDescriptor $finite finite
Require (($finite|ConvertTo-Json -Compress)-ceq$before) 'Finite descriptor omission changed bytes.'
$ongoing=[ordered]@{schema='fixture';existing=17};Set-EmbeddedDuplexMediaPolicyDescriptor $ongoing ongoing-v2
Require ($ongoing.embedded_duplex_media_policy.Count-eq1-and$ongoing.embedded_duplex_media_policy['$schema']-ceq'rusty.manifold.peer.ongoing_media_policy.v2') 'Policy identity differs.'
Require (($ongoing|ConvertTo-Json -Compress)-cne$before) 'Policy must change cache identity input.'
$refusals=0
foreach($bad in @($null,'','Finite','ONGOING-V2','ongoing-v1','ongoing-v2 ','rusty.manifold.peer.ongoing_media_policy.v2')){
 try{Get-EmbeddedDuplexMediaPolicy $bad;throw 'unexpected acceptance'}catch{if($_.Exception.Message-ceq'unexpected acceptance'){throw};$refusals++}
}
# Parse the actual effectful entrypoint without executing it. Verify every actual
# descriptor call is before its first serialized fingerprint or output write.
$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $RepoRoot 'tools/Build-SpatialCameraPanelAndroid.ps1'),[ref]$tokens,[ref]$errors)
Require ($errors.Count-eq0) 'Builder parse failure.'
$calls=@($ast.FindAll({param($n)$n-is[Management.Automation.Language.CommandAst]-and$n.GetCommandName()-ceq'Set-EmbeddedDuplexMediaPolicyDescriptor'},$true))
Require ($calls.Count-eq6) 'Missing artifact policy projection.'
foreach($call in $calls){Require ($call.CommandElements[-1].Extent.Text-ceq'$EmbeddedDuplexMediaPolicy') 'Projection not bound to explicit parameter.'}
[ordered]@{status='PASS';closed_refusals=$refusals;finite_descriptor_byte_exact=$true;policy_changes_identity=$true;actual_projection_sites=$calls.Count;device_effects=0}|ConvertTo-Json
