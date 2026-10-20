[CmdletBinding()]
param([string]$RepoRoot,[Parameter(Mandatory)][string]$OutputRoot)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if([string]::IsNullOrWhiteSpace($RepoRoot)){$RepoRoot=Join-Path $PSScriptRoot '../..'}
$repo=(Resolve-Path -LiteralPath $RepoRoot).Path
if(-not [IO.Path]::IsPathFullyQualified($OutputRoot) -or (Test-Path -LiteralPath $OutputRoot)){throw 'Fresh absolute output directory required.'}
$null=New-Item -ItemType Directory -Path $OutputRoot
$assertions=0
function Check([bool]$Value,[string]$Reason){$script:assertions++;if(-not $Value){throw $Reason}}
$serial='recovery-host-'+[guid]::NewGuid().ToString('N')
$package='io.github.mesmerprism.rustyquest.recovery_host'
$state=Join-Path $OutputRoot 'fake-state.json'
$log=Join-Path $OutputRoot 'fake-adb.jsonl'
$fake=Join-Path $OutputRoot 'fake-adb.ps1'
$savedState=$env:RUSTY_QUEST_RECOVERY_FAKE_STATE
$savedLog=$env:RUSTY_QUEST_RECOVERY_FAKE_LOG
@'
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
[pscustomobject]@{arguments=@($Arguments)}|ConvertTo-Json -Compress|Add-Content -LiteralPath $env:RUSTY_QUEST_RECOVERY_FAKE_LOG
if($Arguments -contains 'get-state'){'device';exit 0}
$index=[Array]::IndexOf($Arguments,'shell')
if($index -lt 0){exit 11}
$command=$Arguments[$index+1]
if($command.StartsWith('am force-stop ')){'stopped';exit 0}
$state=Get-Content -Raw -LiteralPath $env:RUSTY_QUEST_RECOVERY_FAKE_STATE|ConvertFrom-Json -AsHashtable
if($command -eq 'getprop'){
  foreach($key in @($state.Keys|Sort-Object)){'[{0}]: [{1}]' -f $key,$state[$key]}
  exit 0
}
if($command.StartsWith('setprop ')){
  foreach($operation in @($command -split ' && ')){
    $match=[regex]::Match($operation,"^setprop '([A-Za-z0-9._-]+)' '([^']*)'$")
    if(-not $match.Success){exit 13}
    $state[$match.Groups[1].Value]=$match.Groups[2].Value
  }
  $state|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $env:RUSTY_QUEST_RECOVERY_FAKE_STATE
  'restored';exit 0
}
exit 17
'@|Set-Content -LiteralPath $fake -Encoding utf8
function Invoke-Recovery($Receipt,[string]$Label,[bool]$Pass){
    $entered=Join-Path $OutputRoot ($Label+'-entered.json')
    $terminal=Join-Path $OutputRoot ($Label+'-terminal.json')
    $Receipt|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $entered -Encoding utf8
    $before=(Get-FileHash -LiteralPath $entered).Hash
    [IO.File]::WriteAllText($log,'')
    & pwsh -NoProfile -File (Join-Path $repo 'tools/Recover-QuestRunIsolation.ps1') -ExpectedSerial $serial -ExpectedPackageName $package -EnteredReceiptPath $entered -TerminalReceiptPath $terminal -Adb $fake *> (Join-Path $OutputRoot ($Label+'.log'))
    $code=$LASTEXITCODE
    Check ((Get-FileHash -LiteralPath $entered).Hash -ceq $before) "$Label changed original entered bytes"
    if($Pass){
        Check ($code -eq 0) "$Label valid recovery failed; inspect $Label.log"
        $result=Get-Content -Raw -LiteralPath $terminal|ConvertFrom-Json
        Check ($result.status -ceq 'pass') "$Label did not prove exact restoration"
        Check ($result.recovery.entered_receipt_sha256 -ceq $before.ToLowerInvariant()) "$Label terminal lost exact entered byte binding"
        Check (@($result.property_restore|Where-Object status -CNE 'matched').Count -eq 0) "$Label readback mismatched"
    }else{
        Check ($code -ne 0) "$Label unsafe recovery admitted"
        Check ((Get-Item -LiteralPath $log).Length -eq 0) "$Label touched ADB before rejecting"
        Check (-not(Test-Path -LiteralPath $terminal)) "$Label wrote a terminal receipt without recovery"
    }
}
try{
    $env:RUSTY_QUEST_RECOVERY_FAKE_STATE=$state;$env:RUSTY_QUEST_RECOVERY_FAKE_LOG=$log
    Import-Module (Join-Path $repo 'tools/lib/QuestRunIsolation.psm1') -Force
    $module=Get-Module QuestRunIsolation
    $mutex=&$module {param($Serial) Get-QuestRunIsolationMutexName -Serial $Serial} $serial
    $names=@(
        'debug.rustyquest.native_renderer.breath_composition.calibration.right_secondary_action.hold_seconds',
        'debug.rustyquest.native_renderer.private_particles.breath_composition_driver.activation.binding_sha256',
        'debug.rustyquest.native_renderer.projection.target.breath.controller_state.orientation_axis.x'
    )
    Check (@($names|Where-Object Length -GT 92).Count -eq 3) 'Fixture must reproduce legitimate names beyond 92 chars'
    $entries=@($names|ForEach-Object {[ordered]@{name=$_;value='original value'}})
    $initial=[ordered]@{};foreach($name in $names){$initial[$name]='changed'}
    $initial|ConvertTo-Json|Set-Content -LiteralPath $state
    $receipt=[ordered]@{schema='rusty.quest.run_isolation_receipt.v1';phase='entered';status='active';serial=$serial;package_name=$package;mutex_name=$mutex;property_snapshot=$entries}
    Invoke-Recovery $receipt 'long-owner-names' $true
    $restored=Get-Content -Raw -LiteralPath $state|ConvertFrom-Json -AsHashtable
    foreach($name in $names){Check ($restored[$name] -ceq 'original value') 'Long property did not restore exact value'}
    foreach($bad in @('','debug.bad;exit','debug.bad name',"debug.bad`nname","debug.bad`n",('debug.bad'+[char]0+'name'),('debug.'+('a'*3072)))){
        $copy=$receipt|ConvertTo-Json -Depth 8|ConvertFrom-Json -AsHashtable
        # A valid earlier operation must not execute before a later invalid one is found.
        $copy.property_snapshot=@($entries[0],@{name=$bad;value='unchanged'})
        Invoke-Recovery $copy ('bad-name-'+$assertions) $false
    }
    foreach($field in @('serial','package_name','mutex_name')){
        $copy=$receipt|ConvertTo-Json -Depth 8|ConvertFrom-Json -AsHashtable
        $copy[$field]='different-owner'
        Invoke-Recovery $copy ('mismatched-'+$field) $false
    }
    $copy=$receipt|ConvertTo-Json -Depth 8|ConvertFrom-Json -AsHashtable
    $copy.property_snapshot=@($entries[0],$entries[0])
    Invoke-Recovery $copy 'duplicate' $false
    $copy=$receipt|ConvertTo-Json -Depth 8|ConvertFrom-Json -AsHashtable
    $copy.property_snapshot=@(@{name='debug.safe';value="bad`nvalue"})
    Invoke-Recovery $copy 'unsafe-value' $false
    [ordered]@{passed=$true;assertions=$assertions;scope='Actual receipt-bound recovery CLI against generated fake ADB only; no device calls';module_sha256=(Get-FileHash (Join-Path $repo 'tools/lib/QuestRunIsolation.psm1')).Hash.ToLowerInvariant()}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $OutputRoot 'RESULT.json')
    Write-Output "Quest run isolation recovery PASS assertions=$assertions (fake ADB, long owner names, exact receipt/readback, pre-effect rejection)."
}finally{
    $env:RUSTY_QUEST_RECOVERY_FAKE_STATE=$savedState;$env:RUSTY_QUEST_RECOVERY_FAKE_LOG=$savedLog
}
