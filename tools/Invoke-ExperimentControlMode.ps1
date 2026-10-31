[CmdletBinding()]
param(
 [Parameter(Mandatory)][ValidateSet('status','direct','remote')][string]$Mode,
 [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$Device,
 [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$CanonicalSerial,
 [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$BootId,
 [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$')][string]$Package,
 [Parameter(Mandatory)][string]$Adb,
 [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$AdbSha256,
 [ValidatePattern('^$|^[0-9a-f]{64}$')][string]$ExpectedApkSha256='',
 [Parameter(Mandatory)][string]$OutputDirectory,
 [ValidatePattern('^$|^[0-9a-f]{16}$')][string]$OperationId=''
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if($PSVersionTable.PSVersion.Major -lt 7){throw 'PowerShell 7 required'}
$Mode=$Mode.ToLowerInvariant()
if($Mode -ne 'status' -and $ExpectedApkSha256 -eq ''){throw 'Mutation requires explicit ExpectedApkSha256'}
if($Mode -ne 'status' -and $OperationId -ne ''){throw 'OperationId is read-only reconciliation; mutations always use a new original ID'}
$directory=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $directory){throw 'OutputDirectory must be create-new'}
$parent=[IO.DirectoryInfo]::new([IO.Path]::GetDirectoryName($directory))
while($null -ne $parent){if(-not $parent.Exists -or ($parent.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Output parent must exist without reparse points'};$parent=$parent.Parent}
$null=New-Item -ItemType Directory -Path $directory -ErrorAction Stop
function Save-Json([string]$Name,$Value){
 $bytes=[Text.UTF8Encoding]::new($false).GetBytes(($Value|ConvertTo-Json -Depth 40))
 $stream=[IO.File]::Open((Join-Path $directory $Name),'CreateNew','Write','None')
 try{$stream.Write($bytes);$stream.Flush($true)}finally{$stream.Dispose()}
}
$target=@{Device=$Device;CanonicalSerial=$CanonicalSerial;BootId=$BootId;Package=$Package;Adb=$Adb;AdbSha256=$AdbSha256}
Save-Json 'target.json' @{device=$Device;canonical_serial=$CanonicalSerial;boot_id=$BootId;package=$Package;adb_sha256=$AdbSha256;expected_apk_sha256=$ExpectedApkSha256;mode=$Mode}
function Invoke-ModeReadOrSet([string]$Name,$Request){
 Save-Json ($Name+'-request.json') $Request
 try{
  $raw=& (Join-Path $PSScriptRoot 'Invoke-ExperimentSessionControl.ps1') @target -RequestPath (Join-Path $directory ($Name+'-request.json'))
  $receipt=$raw|ConvertFrom-Json -Depth 40
 }catch{throw "Runtime mode operator unavailable (installed59 is unsupported); no mutation retry. $($_.Exception.Message)"}
 Save-Json ($Name+'-receipt.json') $receipt
 if($receipt.schema -cne 'rusty.quest.experiment_session.operator_receipt.v1' -or $null -eq $receipt.PSObject.Properties['mode_operator_version'] -or $receipt.mode_operator_version -ne 1 -or $receipt.operation -cne $Request.operation){throw 'Unsupported APK runtime mode operator (requires mode operator v1; installed59 is unsupported)'}
 return $receipt
}
function Assert-OriginalRequest($Receipt,$Request){
 if($Receipt.mode_result.state -ceq 'outcome_unknown' -and $null -eq $Receipt.mode_result.PSObject.Properties['original_request']){return}
 if($null -eq $Receipt.mode_result.PSObject.Properties['original_request']){throw 'Original mode request unavailable; do not replay'}
 $original=$Receipt.mode_result.original_request
 if(@($original.PSObject.Properties).Count -ne $Request.Count){throw 'Original mode request shape mismatch; do not replay'}
 foreach($key in $Request.Keys){
  if($null -eq $original.PSObject.Properties[$key] -or $original.$key -cne $Request[$key]){throw 'Original mode request fence mismatch; do not replay'}
 }
}
$schema='rusty.quest.experiment_session.operator_command.v1'
try{
 if($OperationId -ne ''){
  $result=Invoke-ModeReadOrSet 'original' @{schema=$schema;operation='control-mode-receipt';operation_id=$OperationId}
 }else{
  $read=Invoke-ModeReadOrSet 'read' @{schema=$schema;operation='control-mode-status'}
  if($ExpectedApkSha256 -ne '' -and $read.apk_sha256 -cne $ExpectedApkSha256){throw 'Installed APK digest mismatch; no mutation sent'}
  if($Mode -eq 'status' -or $read.mode_result.mode -ceq $Mode){$result=$read}
  else{
   $scope=$read.mode_result;$session=$scope.session
   $id=[Guid]::NewGuid().ToString('N').Substring(0,16)
   $request=[ordered]@{schema=$schema;operation='set-control-mode';operation_id=$id;requested_mode=$Mode
    expected_pid=$read.pid;expected_uid=$read.uid;expected_process_start_ticks=$read.process_start_ticks
    expected_apk_sha256=$ExpectedApkSha256;expected_binding_sha256=$scope.binding_sha256
    expected_runtime_epoch=$session.runtime_epoch;expected_generation=$session.generation
    expected_session_key=$(if($null -eq $session.session_key){'-'}else{$session.session_key})
    expected_condition=$(if($null -eq $session.condition_id){''}else{$session.condition_id})
    expected_profile_sha256=$(if($null -eq $session.non_audio_profile_sha256){''}else{$session.non_audio_profile_sha256})
    expected_host_id=$scope.host_id;expected_carrier_generation=$scope.carrier_generation;expected_native_owner_epoch=$scope.native_owner_epoch
    expected_mode_packed=$scope.mode_packed;expected_mode_revision=$scope.mode_revision;operator_origin='shell-experiment-operator-v1'}
   $sent=Invoke-ModeReadOrSet 'set' $request
   if($sent.mode_result.operation_id -cne $id){throw 'Original mode request receipt mismatch; do not replay'}
   Assert-OriginalRequest $sent $request
   $result=Invoke-ModeReadOrSet 'original' @{schema=$schema;operation='control-mode-receipt';operation_id=$id}
   if($result.mode_result.operation_id -cne $id){throw 'Original mode reconciliation mismatch; do not replay'}
   Assert-OriginalRequest $result $request
  }
 }
 Save-Json 'result.json' $result
 $result|ConvertTo-Json -Depth 40
}catch{
 Save-Json 'failure.json' @{error=$_.Exception.Message;no_retry=$true}
 throw
}
