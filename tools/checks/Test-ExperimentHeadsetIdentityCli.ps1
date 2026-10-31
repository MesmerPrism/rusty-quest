[CmdletBinding()]
param()
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('rq-identity-cli-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temporary)
$fake=Join-Path $temporary 'inert-adb.ps1';$request=Join-Path $temporary 'request.json'
# Entire CLI executes with a local inert executable boundary, never ADB.
@'
$global:LASTEXITCODE=0
if($args -contains 'getprop'){'SYNTHETIC-ONLY';return}
if($args -contains 'cat'){'synthetic-boot';return}
if($args -contains 'content'){
 $global:IdentityCliCalls++
 $json='{"schema":"rusty.quest.experiment_session.operator_receipt.v1","operation":"provision-headset-identity","native_response":{"command_status":"provisioned"}}'
 'Bundle[{receipt_b64='+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))+'}]';return
}
throw 'Unexpected inert executable invocation'
'@ | Set-Content -LiteralPath $fake
$hash=(Get-FileHash -LiteralPath $fake -Algorithm SHA256).Hash.ToLowerInvariant()
$global:IdentityCliCalls=0
$arguments=@{Device='synthetic-transport';CanonicalSerial='SYNTHETIC-ONLY';BootId='synthetic-boot';Package='synthetic.app';Adb=$fake;AdbSha256=$hash;RequestPath=$request}
function Invoke-Case([string]$Serial,[bool]$Accept){
 @{schema='rusty.quest.experiment_session.operator_command.v1';operation='provision-headset-identity';hardware_serial=$Serial}|ConvertTo-Json|Set-Content -LiteralPath $request
 $before=$global:IdentityCliCalls;$denied=$false
 try{& (Join-Path $repo 'tools/Invoke-ExperimentSessionControl.ps1') @arguments|Out-Null}catch{$denied=$true}
 if($denied-eq$Accept){throw "Unexpected CLI serial guard result (expected acceptance: $Accept)"}
 if($global:IdentityCliCalls-ne($before+[int]$Accept)){throw 'Serial mismatch crossed closed operator boundary'}
}
Invoke-Case 'SYNTHETIC-ONLY' $true
Invoke-Case 'OTHER' $false
Invoke-Case '' $false
$arguments.CanonicalSerial='OTHER';Invoke-Case 'OTHER' $false
'PASS actual typed identity CLI: requested/live/canonical equality and mismatch denial before operator dispatch; inert executable, no ADB'
