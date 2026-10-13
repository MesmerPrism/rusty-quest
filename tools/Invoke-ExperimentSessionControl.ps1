[CmdletBinding()]
param(
 [Parameter(Mandatory)][string]$Device,
 [Parameter(Mandatory)][string]$CanonicalSerial,
 [Parameter(Mandatory)][string]$BootId,
 [Parameter(Mandatory)][string]$Package,
 [Parameter(Mandatory)][string]$Adb,
 [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$AdbSha256,
 [Parameter(Mandatory)][string]$RequestPath,
 [string]$SecretOutputDirectory = ''
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
# Caller owns the exact headset lease and retains selected request/artifact bytes.
# One provider call only. No launch, transport recovery, retry, or status-as-applied claim.
if($Package -cnotmatch '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$'){throw 'Exact application package required'}
$binary=(Resolve-Path -LiteralPath $Adb).Path
if((Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash.ToLowerInvariant() -cne $AdbSha256){throw 'Selected ADB byte mismatch'}
$raw=[IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $RequestPath).Path)
if($raw.Length -eq 0 -or $raw.Length -gt 4096){throw 'Request exceeds closed provider bound'}
$shape=[Text.Encoding]::UTF8.GetString($raw)|ConvertFrom-Json -Depth 30
if($shape.schema -cne 'rusty.quest.experiment_session.operator_command.v1' -or $shape.operation -cnotin @('status','official-start','pause','resume','arm','save-next','save-exit','return-vr','open-polar','ping','enroll-trusted-lan')){throw 'Unsupported closed owner request'}
$enrollment=$shape.operation -ceq 'enroll-trusted-lan'
$secretPath=$null
if($enrollment){
 if(-not $IsWindows -or [string]::IsNullOrWhiteSpace($SecretOutputDirectory)){throw 'Enrollment requires a create-new Windows protected output directory'}
 if($shape.trusted_lan_consent -isnot [bool] -or $shape.trusted_lan_consent -ne $true){throw 'Explicit trusted LAN consent required'}
 $directory=[IO.Path]::GetFullPath($SecretOutputDirectory)
 if(Test-Path -LiteralPath $directory){throw 'Credential directory must be create-new'}
 $parent=[IO.DirectoryInfo]::new([IO.Path]::GetDirectoryName($directory))
 while($null-ne$parent){if(-not$parent.Exists-or($parent.Attributes-band[IO.FileAttributes]::ReparsePoint)){throw 'Credential parent must exist and contain no reparse points'};$parent=$parent.Parent}
 $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
 $security=[Security.AccessControl.DirectorySecurity]::new()
 $security.SetOwner($sid);$security.SetAccessRuleProtection($true,$false)
 $security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
 [IO.FileSystemAclExtensions]::Create([IO.DirectoryInfo]::new($directory),$security)
 $actual=Get-Acl -LiteralPath $directory
 if(-not$actual.AreAccessRulesProtected-or@($actual.Access).Count-ne1-or@($actual.Access|Where-Object{$_.AccessControlType-ne'Allow'-or$_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value-cne$sid.Value}).Count){throw 'Credential directory ACL verification failed'}
 $secretPath=Join-Path $directory 'enrollment.json'
}elseif(-not[string]::IsNullOrEmpty($SecretOutputDirectory)){throw 'Credential output is only valid for enrollment'}
$serial=(& $binary '-s' $Device 'shell' 'getprop' 'ro.serialno') -join "`n"
if($LASTEXITCODE -ne 0 -or $serial.Trim() -cne $CanonicalSerial){throw 'Exact canonical target mismatch'}
$boot=(& $binary '-s' $Device 'shell' 'cat' '/proc/sys/kernel/random/boot_id') -join "`n"
if($LASTEXITCODE -ne 0 -or $boot.Trim() -cne $BootId){throw 'Exact boot target mismatch'}
$encoded=[Convert]::ToBase64String($raw).TrimEnd('=')
$callArguments=@('-s',$Device,'shell','content','call','--uri',('content://'+$Package+'.debug-experiment-control'),'--method','experiment-control','--arg',$encoded)
if($enrollment){
 # Capture both streams privately. Never emit the secret-bearing Binder response.
 $start=[Diagnostics.ProcessStartInfo]::new($binary)
 $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
 foreach($item in $callArguments){$start.ArgumentList.Add($item)}
 $child=[Diagnostics.Process]::new();$child.StartInfo=$start
 try{
  if(-not$child.Start()){throw 'Provider dispatch unavailable; no retry selected'}
  $stdout=$child.StandardOutput.ReadToEndAsync();$stderr=$child.StandardError.ReadToEndAsync();$child.WaitForExit()
  $output=$stdout.GetAwaiter().GetResult();$privateError=$stderr.GetAwaiter().GetResult()
  if($child.ExitCode-ne0-or-not[string]::IsNullOrWhiteSpace($privateError)){throw 'Private provider call failed; enrollment outcome may be unknown; do not replay'}
 }finally{$child.Dispose()}
}else{
 $output=(& $binary @callArguments) -join "`n"
 if($LASTEXITCODE -ne 0){throw 'Provider call failed; no retry selected'}
}
$match=[regex]::Match($output,'receipt_b64=([A-Za-z0-9+/]+={0,2})')
if(-not $match.Success){throw 'Provider receipt unavailable; effect remains unknown'}
$value=$match.Groups[1].Value
$value=$value.PadRight($value.Length+(4-$value.Length%4)%4,'=')
$decoded=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value))
if($enrollment){
 try{$receipt=$decoded|ConvertFrom-Json -Depth 30}catch{throw 'Private enrollment response malformed; do not replay'}
 if($receipt.schema-cne'rusty.quest.experiment_session.operator_receipt.v1'-or$receipt.operation-cne'enroll-trusted-lan'-or$receipt.native_response.command_status-cne'enrolled'-or$receipt.native_response.operation_id-cne$shape.operation_id){throw 'Enrollment result unavailable; do not replay'}
 $stream=[IO.File]::Open($secretPath,'CreateNew','Write','None')
 try{$bytes=[Text.Encoding]::UTF8.GetBytes($decoded);$stream.Write($bytes);$stream.Flush($true)}finally{$stream.Dispose()}
 # No credential, response or secret-file bytes are written to normal output.
 [pscustomobject]@{operation='enroll-trusted-lan';result='saved-private-response';secret_output_path=$secretPath}
}else{$decoded}
