[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
    [Parameter(Mandatory)][ValidatePattern('^io\.github\.mesmerprism\.rustyquest\.native_renderer\.[a-z0-9_.]+$')][string]$PackageName,
    [ValidateSet('status','stop_acc','stop_ecg')][string]$Operation='status',
    [ValidatePattern('^[a-f0-9]{32}$')][string]$OperationId,
    [long]$ExpectedPid=0,[long]$ExpectedRuntimeGeneration=0,
    [ValidatePattern('^[a-f0-9]{32}$')][string]$ExpectedOwnerEpoch,
    [long]$ExpectedActionRevision=-1,
    [ValidatePattern('^(none|polar-[a-f0-9]{12})$')][string]$ExpectedSelectedSensor,
    [ValidatePattern('^(none|polar-[a-f0-9]{12})$')][string]$ExpectedConnectedSensor,
    [long]$ExpectedAccRevision=-1,[long]$ExpectedEcgRevision=-1,
    [Nullable[double]]$ObservationSeconds=$null,
    [string]$CancelFile,
    [string]$Adb='adb'
)
$ErrorActionPreference='Stop'
if($null-ne$ObservationSeconds -and ($ObservationSeconds-le0 -or [double]::IsNaN($ObservationSeconds) -or [double]::IsInfinity($ObservationSeconds))){
    throw 'Explicit finite observation must be a positive finite number; default observes until cancelled.'
}
function Get-PolarProjectedObservation($Response,[string]$Token,[bool]$Cancelled,[double]$ElapsedSeconds,[Nullable[double]]$ObservationSeconds){
    if($null-ne$Response){
        $schemaProperty=$Response.PSObject.Properties['schema'];$tokenProperty=$Response.PSObject.Properties['token']
        if($schemaProperty -and $tokenProperty -and $schemaProperty.Value-ceq'rusty.quest.native_renderer.polar_projected_invocation.v1' -and $tokenProperty.Value-ceq$Token){return 'receipt-available'}
    }
    if($Cancelled){return 'cancelled-outcome-unknown'}
    if($null-ne$ObservationSeconds -and $ElapsedSeconds-ge$ObservationSeconds){return 'finite-window-ended-outcome-unknown'}
    return 'waiting'
}
function Get-PolarProjectedSupport($Response,[string]$Token){
    if($Response.schema-cne'rusty.quest.native_renderer.polar_sensor_operator_status.v2' -or
        $Response.token-cne$Token -or $Response.command_origin-cne'cli-receiver' -or
        $Response.command-cne'status' -or $Response.dispatch_status-cne'accepted' -or $Response.effect_status-cne'observed'){
        throw 'Fresh correlated read-only Polar owner status required; absence is not unsupported proof.'
    }
    $support=$Response.PSObject.Properties['projected_stop_schema']
    if(!$support){return $false}
    if($support.Value-isnot[string]){throw 'Malformed owner capability declaration.'}
    if($support.Value-cne'rusty.quest.native_renderer.polar_projected_stop.v1'){return $false}
    foreach($field in @('projected_stop_owner_pid','runtime_generation')){
        $value=$Response.$field
        if(($value-isnot[int] -and $value-isnot[long]) -or $value-lt1 -or $value-gt9007199254740991L){throw 'Malformed positive owner integer.'}
    }
    if($Response.projected_stop_owner_epoch-isnot[string] -or $Response.projected_stop_owner_epoch-cnotmatch'^[a-f0-9]{32}$'){throw 'Malformed owner lifetime.'}
    return $true
}
function Assert-PolarProjectedStopReceipt($Request,$Response){
    $dispatchStatus=if($Response-is[System.Collections.IDictionary]){$Response['dispatch_status']}else{
        $property=$Response.PSObject.Properties['dispatch_status'];if($property){$property.Value}else{$null}
    }
    if($Response.origin-cne'cli-receiver' -or $dispatchStatus-ceq'rejected'){
        throw 'Wrong owner origin or app-owned rejection.'
    }
    $receipt=$Response.action_receipt
    if($Request.operation-ceq'status'){
        if($receipt.dispatch_status-cne'observed' -or $receipt.effect_status-cne'read-only'){
            throw 'Read-only owner observation required.'
        }
    }else{
        if($receipt.schema-cne'rusty.quest.native_renderer.polar_stop_dispatch.v1' -or
            $receipt.operation_id-cne$Request.operation_id -or $receipt.operation-cne$Request.operation -or
            $receipt.dispatch_status-cne'dispatched' -or $receipt.effect_status-cne'not-confirmed'){
            throw 'Exact original dispatch receipt required; pending/unknown is not confirmation.'
        }
        foreach($key in $Request.Keys){
            $actual=$receipt.expected_state.$key
            if($key-in@('pid','runtime_generation','action_revision','acc_revision','ecg_revision')){
                if(($actual-isnot[int] -and $actual-isnot[long]) -or $actual-ne$Request[$key]){
                    throw 'Typed original numeric fence mismatch.'
                }
            }elseif($actual-isnot[string] -or $actual-cne$Request[$key]){throw 'Original request fence mismatch.'}
        }
    }
}
$request=[ordered]@{schema='rusty.quest.native_renderer.polar_projected_stop.v1';operation=$Operation}
if($Operation -ne 'status'){
    if(!$OperationId -or $ExpectedPid -lt 1 -or $ExpectedRuntimeGeneration -lt 1 -or
        !$ExpectedOwnerEpoch -or $ExpectedActionRevision-lt0 -or !$ExpectedSelectedSensor -or !$ExpectedConnectedSensor -or $ExpectedAccRevision -lt 0 -or $ExpectedEcgRevision -lt 0){
        throw 'Mutation requires explicit operation ID and exact current owner/sensor/stream revision projection.'
    }
    foreach($value in @($ExpectedPid,$ExpectedRuntimeGeneration,$ExpectedActionRevision,$ExpectedAccRevision,$ExpectedEcgRevision)){
        if($value -gt 9007199254740991L){throw 'Safe integer fence required.'}
    }
    $request.operation_id=$OperationId;$request.pid=$ExpectedPid;$request.runtime_generation=$ExpectedRuntimeGeneration
    $request.owner_epoch=$ExpectedOwnerEpoch
    $request.action_revision=$ExpectedActionRevision
    $request.selected_sensor=$ExpectedSelectedSensor;$request.connected_sensor=$ExpectedConnectedSensor
    $request.acc_revision=$ExpectedAccRevision;$request.ecg_revision=$ExpectedEcgRevision
}
if($CancelFile -and (Test-Path -LiteralPath $CancelFile)){
    @{schema='rusty.quest.native_renderer.polar_projected_observer.v1';dispatch_status='unissued';observer_status='cancelled-before-dispatch';request=$request;native_cancel_requested=$false}|ConvertTo-Json -Depth 16
    return
}
$adbExecutable=(Get-Command $Adb -ErrorAction Stop).Source
$component="$PackageName/io.github.mesmerprism.rustyquest.native_renderer.PolarSensorCommandReceiver"
$action='io.github.mesmerprism.rustyquest.native_renderer.action.POLAR_SENSOR_RUNTIME_COMMAND'
$supportToken=[guid]::NewGuid().ToString('N')
if($CancelFile -and (Test-Path -LiteralPath $CancelFile)){
    @{schema='rusty.quest.native_renderer.polar_projected_observer.v1';dispatch_status='unissued';observer_status='cancelled-before-support-preflight';request=$request;native_cancel_requested=$false}|ConvertTo-Json -Depth 16
    return
}
# Exactly one read-only request on the installed legacy status channel. No projected mutation yet.
$supportArguments=@('-s',$Serial,'shell','am','broadcast','-n',$component,'-a',$action,
    '--es','polar_sensor_runtime_command','status','--es','polar_sensor_runtime_command_token',$supportToken)
$supportDispatch=@(& $adbExecutable @supportArguments 2>&1)
if($LASTEXITCODE-ne0){throw 'Read-only support preflight dispatch failed; projected action unissued.'}
$supportClock=[Diagnostics.Stopwatch]::StartNew();$supportResponse=$null
while($true){
    $raw=@(& $adbExecutable -s $Serial exec-out run-as $PackageName cat files/polar_sensor_operator_status.json 2>$null)-join "`n"
    if($LASTEXITCODE-eq0){try{$candidate=$raw|ConvertFrom-Json -Depth 16 -DateKind String}catch{$candidate=$null}
        if($candidate -and $candidate.PSObject.Properties['schema'] -and $candidate.PSObject.Properties['token'] -and
            $candidate.schema-ceq'rusty.quest.native_renderer.polar_sensor_operator_status.v2' -and $candidate.token-ceq$supportToken){$supportResponse=$candidate;break}}
    $cancelled=$CancelFile -and (Test-Path -LiteralPath $CancelFile)
    $decision=Get-PolarProjectedObservation $null $supportToken $cancelled $supportClock.Elapsed.TotalSeconds $ObservationSeconds
    if($decision-cne'waiting'){
        @{schema='rusty.quest.native_renderer.polar_projected_observer.v1';dispatch_status='projected-action-unissued';observer_status=$decision;support_request_token=$supportToken;request=$request;native_cancel_requested=$false;command_replayed=$false}|ConvertTo-Json -Depth 16
        throw 'Support preflight observation ended unknown; projected action unissued.'
    }
    Start-Sleep -Milliseconds 100
}
if(!(Get-PolarProjectedSupport $supportResponse $supportToken)){
    @{schema='rusty.quest.native_renderer.polar_projected_observer.v1';dispatch_status='projected-action-unissued';observer_status='unsupported-by-correlated-owner-status';request=$request;support_receipt=$supportResponse}|ConvertTo-Json -Depth 16
    throw 'Installed owner status does not declare projected stop support; no new action sent.'
}
if($Operation-cne'status' -and ($supportResponse.projected_stop_owner_pid-ne$ExpectedPid -or
    $supportResponse.projected_stop_owner_epoch-cne$ExpectedOwnerEpoch -or $supportResponse.runtime_generation-ne$ExpectedRuntimeGeneration)){
    @{schema='rusty.quest.native_renderer.polar_projected_observer.v1';dispatch_status='projected-action-unissued';observer_status='fresh-correlated-owner-scope-changed';request=$request;support_receipt=$supportResponse}|ConvertTo-Json -Depth 16
    throw 'Fresh correlated owner scope differs; projected action unissued.'
}
$encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($request|ConvertTo-Json -Compress)))
$token=[guid]::NewGuid().ToString('N')
$dispatchArguments=@('-s',$Serial,'shell','am','broadcast','-n',$component,
    '-a','io.github.mesmerprism.rustyquest.native_renderer.action.POLAR_SENSOR_RUNTIME_COMMAND',
    '--es','polar_sensor_projected_request_b64',$encoded,'--es','polar_sensor_runtime_command_token',$token)
$cancelled=$CancelFile -and (Test-Path -LiteralPath $CancelFile)
$decision=Get-PolarProjectedObservation $null $token $cancelled $supportClock.Elapsed.TotalSeconds $ObservationSeconds
if($decision-cne'waiting'){
    @{schema='rusty.quest.native_renderer.polar_projected_observer.v1';dispatch_status='projected-action-unissued';observer_status=$decision;request=$request;support_receipt=$supportResponse;native_cancel_requested=$false}|ConvertTo-Json -Depth 16
    return
}
$dispatch=@(& $adbExecutable @dispatchArguments 2>&1)
if($LASTEXITCODE-ne0){throw 'Fixed DUMP-protected Polar receiver dispatch failed; no retry.'}
$observation=$supportClock
while($true){
    $response=$null
    $raw=@(& $adbExecutable -s $Serial exec-out run-as $PackageName cat files/polar_sensor_projected_action_status.json 2>$null)-join "`n"
    if($LASTEXITCODE-eq0){
        try{$response=$raw|ConvertFrom-Json -Depth 16 -DateKind String}catch{$response=$null}
    }
    $cancelled=$CancelFile -and (Test-Path -LiteralPath $CancelFile)
    $decision=Get-PolarProjectedObservation $response $token $cancelled $observation.Elapsed.TotalSeconds $ObservationSeconds
    if($decision-ceq'receipt-available'){
        $response|ConvertTo-Json -Depth 16
        Assert-PolarProjectedStopReceipt $request $response
        return
    }
    if($decision-cne'waiting'){
        @{schema='rusty.quest.native_renderer.polar_projected_observer.v1';dispatch_status='broadcast-returned';observer_status=$decision;token=$token;request=$request;elapsed_observation_seconds=$observation.Elapsed.TotalSeconds;selected_observation_seconds=$ObservationSeconds;native_cancel_requested=$false;command_replayed=$false}|ConvertTo-Json -Depth 16
        throw 'Observer ended with original command outcome unknown; no native cancellation or retry.'
    }
    Start-Sleep -Milliseconds 100
}
