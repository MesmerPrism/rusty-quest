[CmdletBinding()]
param([string]$RepoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $RepoRoot 'tools/Invoke-NativeRendererPolarProjectedStop.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'CLI parse failure'}
$helper=@($ast.FindAll({param($node)$node-is[Management.Automation.Language.FunctionDefinitionAst]-and$node.Name-ceq'Assert-PolarProjectedStopReceipt'},$true))
if($helper.Count-ne1){throw 'Actual production receipt helper missing/ambiguous'}
. ([scriptblock]::Create($helper[0].Extent.Text))
$request=[ordered]@{schema='rusty.quest.native_renderer.polar_projected_stop.v1';operation='stop_ecg';operation_id=('1'*32);pid=10L;runtime_generation=2L;owner_epoch=('a'*32);action_revision=0L;selected_sensor='polar-111111111111';connected_sensor='polar-111111111111';acc_revision=3L;ecg_revision=5L}
function Receipt { ([ordered]@{origin='cli-receiver';action_receipt=@{schema='rusty.quest.native_renderer.polar_stop_dispatch.v1';operation_id=$request.operation_id;operation=$request.operation;dispatch_status='dispatched';effect_status='not-confirmed';expected_state=$request}}|ConvertTo-Json -Depth 8|ConvertFrom-Json -Depth 8) }
Assert-PolarProjectedStopReceipt $request (Receipt)
$denied=0
foreach($case in @('wrong-id','wrong-stream','unknown','fake-confirmed','missing-fence','bool-fence','stale-owner','wrong-origin')){
    $response=Receipt
    switch($case){
        'wrong-id' {$response.action_receipt.operation_id='2'*32}
        'wrong-stream' {$response.action_receipt.operation='stop_acc'}
        'unknown' {$response.action_receipt.dispatch_status='outcome-unknown'}
        'fake-confirmed' {$response.action_receipt.effect_status='confirmed'}
        'missing-fence' {$response.action_receipt.expected_state.PSObject.Properties.Remove('acc_revision')}
        'bool-fence' {$response.action_receipt.expected_state.pid=$true}
        'stale-owner' {$response.action_receipt.expected_state.owner_epoch='b'*32}
        'wrong-origin' {$response.origin='panel-owner'}
    }
    $rejected=$false;try{Assert-PolarProjectedStopReceipt $request $response}catch{$rejected=$true;$denied++}
    if(!$rejected){throw "Actual CLI helper accepted $case"}
}
Assert-PolarProjectedStopReceipt @{operation='status'} @{origin='cli-receiver';action_receipt=@{dispatch_status='observed';effect_status='read-only'}}
$observer=@($ast.FindAll({param($node)$node-is[Management.Automation.Language.FunctionDefinitionAst]-and$node.Name-ceq'Get-PolarProjectedObservation'},$true))
if($observer.Count-ne1){throw 'Actual production observer helper missing'}
. ([scriptblock]::Create($observer[0].Extent.Text))
if((Get-PolarProjectedObservation $null 'token' $false 100000 $null)-cne'waiting'){throw 'Default observer acquired an estimated cutoff'}
if((Get-PolarProjectedObservation $null 'token' $false 2 1)-cne'finite-window-ended-outcome-unknown'){throw 'Explicit finite observer did not retain unknown'}
if((Get-PolarProjectedObservation $null 'token' $true 2 $null)-cne'cancelled-outcome-unknown'){throw 'Cooperative cancel did not retain unknown'}
$real=[pscustomobject]@{schema='rusty.quest.native_renderer.polar_projected_invocation.v1';token='token'}
if((Get-PolarProjectedObservation $real 'token' $true 2 1)-cne'receipt-available'){throw 'Actual available receipt incorrectly lost to observer budget'}
$real.token='old-token'
if((Get-PolarProjectedObservation $real 'token' $false 2 $null)-cne'waiting'){throw 'Different old token inferred current outcome/actor'}
$signal=[IO.Path]::GetTempFileName()
$cancelledResult=& (Join-Path $RepoRoot 'tools/Invoke-NativeRendererPolarProjectedStop.ps1') -Serial fixture -PackageName io.github.mesmerprism.rustyquest.native_renderer.fixture -CancelFile $signal -Adb deliberately-unavailable-no-device-program
$cancelled=$cancelledResult|ConvertFrom-Json -DateKind String
if($cancelled.dispatch_status-cne'unissued' -or $cancelled.observer_status-cne'cancelled-before-dispatch' -or $cancelled.native_cancel_requested-ne$false){throw 'Actual pre-cancel CLI touched dispatcher or falsely cancelled native effect'}
$support=@($ast.FindAll({param($node)$node-is[Management.Automation.Language.FunctionDefinitionAst]-and$node.Name-ceq'Get-PolarProjectedSupport'},$true))
if($support.Count-ne1){throw 'Actual support helper missing'}
. ([scriptblock]::Create($support[0].Extent.Text))
function LegacyStatus { [pscustomobject]@{schema='rusty.quest.native_renderer.polar_sensor_operator_status.v2';token='support-token';command_origin='cli-receiver';command='status';dispatch_status='accepted';effect_status='observed';runtime_generation=1L} }
$old=LegacyStatus
if(Get-PolarProjectedSupport $old 'support-token'){throw 'Old correlated status falsely declared new support'}
$modern=LegacyStatus
$modern|Add-Member projected_stop_schema 'rusty.quest.native_renderer.polar_projected_stop.v1'
$modern|Add-Member projected_stop_owner_pid 10L
$modern|Add-Member projected_stop_owner_epoch ('a'*32)
if(!(Get-PolarProjectedSupport $modern 'support-token')){throw 'Actual declared capability denied'}
foreach($case in @('wrong-token','unavailable','bool-pid','bad-epoch')){
    $copy=$modern|ConvertTo-Json|ConvertFrom-Json -DateKind String
    switch($case){'wrong-token' {$copy.token='old'};'unavailable' {$copy.dispatch_status='rejected'};'bool-pid' {$copy.projected_stop_owner_pid=$true};'bad-epoch' {$copy.projected_stop_owner_epoch='old'}}
    $rejected=$false;try{$null=Get-PolarProjectedSupport $copy 'support-token'}catch{$rejected=$true}
    if(!$rejected){throw 'Invalid support preflight accepted'}
}
Write-Output "PASS actual projected Polar CLI helpers: two positive/$denied hostile receipts +6 observation/pre-dispatch +6 support controls, no ADB invocation/no sleeps."
