[CmdletBinding()]
param(
 [Parameter(Mandatory)][string]$Device,
 [Parameter(Mandatory)][string]$CanonicalSerial,
 [Parameter(Mandatory)][string]$BootId,
 [Parameter(Mandatory)][string]$Package,
 [Parameter(Mandatory)][string]$Adb,
 [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$AdbSha256,
 [Parameter(Mandatory)][string]$RequestPath
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
if($shape.schema -cne 'rusty.quest.experiment_session.operator_command.v1' -or $shape.operation -cnotin @('status','official-start','pause','resume','arm','save-next','save-exit','return-vr','open-polar','ping')){throw 'Unsupported closed owner request'}
$serial=(& $binary '-s' $Device 'shell' 'getprop' 'ro.serialno') -join "`n"
if($LASTEXITCODE -ne 0 -or $serial.Trim() -cne $CanonicalSerial){throw 'Exact canonical target mismatch'}
$boot=(& $binary '-s' $Device 'shell' 'cat' '/proc/sys/kernel/random/boot_id') -join "`n"
if($LASTEXITCODE -ne 0 -or $boot.Trim() -cne $BootId){throw 'Exact boot target mismatch'}
$encoded=[Convert]::ToBase64String($raw).TrimEnd('=')
$output=(& $binary '-s' $Device 'shell' 'content' 'call' '--uri' ('content://'+$Package+'.debug-experiment-control') '--method' 'experiment-control' '--arg' $encoded) -join "`n"
if($LASTEXITCODE -ne 0){throw 'Provider call failed; no retry selected'}
$match=[regex]::Match($output,'receipt_b64=([A-Za-z0-9+/]+={0,2})')
if(-not $match.Success){throw 'Provider receipt unavailable; effect remains unknown'}
$value=$match.Groups[1].Value
$value=$value.PadRight($value.Length+(4-$value.Length%4)%4,'=')
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value))
