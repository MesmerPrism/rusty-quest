#requires -Version 7.6
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Python,[Parameter(Mandatory)][string]$OutDirectory)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($OutDirectory)
if(Test-Path -LiteralPath $root){throw 'Create-new test output required'}
New-Item -ItemType Directory -Path $root|Out-Null
$pythonBinary=(Resolve-Path -LiteralPath $Python).Path
$stub=Join-Path $root 'fake-adb.py'
@'
import sys,json,base64
from pathlib import Path
p=Path(__file__).with_name('state.json');s=json.loads(p.read_text(encoding='utf-8'))
a=sys.argv[1:];assert a[:2]==['-s','TEST-TRANSPORT'];command=a[2:]
if command==['shell','getprop','ro.serialno']:print('TEST-CANONICAL')
elif command==['shell','cat','/proc/sys/kernel/random/boot_id']:print(s['boot'])
elif command[:3]==['shell','am','broadcast']:
 s['broadcasts']+=1;p.write_text(json.dumps(s),encoding='utf-8')
 assert command[3:7]==['-a','io.github.mesmerprism.rustyquest.native_renderer.action.LSL_PANEL_COMMAND','-n','com.example.product/io.github.mesmerprism.rustyquest.native_renderer.LslPanelCommandReceiver']
 assert command[7:9]==['--es','command_b64'] and command[10:12]==['--es','persistence_scope'] and len(command)==13
 scope=command[12];request=json.loads(base64.b64decode(command[9]+'='*((-len(command[9]))%4)))
 assert request==s['request'] and scope==s['scope']
 if s['mode']=='missing':print('Broadcast completed: result=-1')
 else:
  receipt={'schema':'rusty.quest.native_renderer.lsl.status.v1','response_status':'status' if request['operation']=='status' else 'accepted','app_session_id':request.get('app_session_id','actual-session'),'generation':request.get('generation',2),'last_request_id':request.get('request_id','previous'),'persistence_scope':scope,'persisted':scope=='persistent','config':{'participant_id':'PRIVATE-FIXTURE-SECRET'}}
  mode=s['mode']
  if mode=='wrong-session':receipt['app_session_id']='another-session'
  if mode=='wrong-generation':receipt['generation']+=1
  if mode=='wrong-id':receipt['last_request_id']='another-request'
  if mode=='wrong-scope':receipt['persistence_scope']='persistent' if scope=='runtime-only' else 'runtime-only'
  if mode=='string-generation':receipt['generation']=str(receipt['generation'])
  if mode=='string-persisted':receipt['persisted']='False'
  if mode=='runtime-persisted':receipt['persisted']=True
  if mode=='rejected':receipt={'schema':'rusty.quest.native_renderer.lsl.cli_receipt.v1','response_status':'rejected','response_reason':'native-runtime-not-running'}
  body=base64.b64encode(json.dumps(receipt).encode()).decode().rstrip('=')
  print('Broadcast completed: result=-1, data="'+body+'"')
else:raise SystemExit('Unexpected fixed command')
'@|Set-Content -LiteralPath $stub -Encoding utf8NoBOM
$fakeAdb=Join-Path $root 'fake-adb.cmd'
@"
@echo off
"$pythonBinary" "$stub" %*
exit /b %errorlevel%
"@|Set-Content -LiteralPath $fakeAdb -Encoding ascii
$adbHash=(Get-FileHash $fakeAdb).Hash.ToLowerInvariant()
$cli=Join-Path $PSScriptRoot 'Invoke-NativeRendererLslPanel.ps1'
$pwshBinary=(Get-Process -Id $PID).Path
$cases=[Collections.Generic.List[object]]::new()
$names=@('status-private','runtime-accepted','persistent-accepted','boot-mismatch','missing',
    'wrong-session','wrong-generation','wrong-id','wrong-scope','string-generation','string-persisted','runtime-persisted','rejected')
foreach($name in $names){
    $scope=if($name -eq 'persistent-accepted'){'persistent'}else{'runtime-only'}
    $request=@{schema='rusty.quest.native_renderer.lsl.panel_command.v1';operation='apply';request_id='current-request';app_session_id='actual-session';generation=3;config=@{participant_id='PRIVATE-FIXTURE-SECRET'}}
    if($name -eq 'status-private'){$request=@{schema='rusty.quest.native_renderer.lsl.panel_command.v1';operation='status'}}
    $mode=if($name -in @('status-private','runtime-accepted','persistent-accepted','boot-mismatch')){'receipt'}else{$name}
    $state=@{boot=if($name -eq 'boot-mismatch'){'WRONG'}else{'TEST-BOOT'};mode=$mode;broadcasts=0;request=$request;scope=$scope}
    $state|ConvertTo-Json -Depth 12|Set-Content (Join-Path $root 'state.json') -Encoding utf8NoBOM
    $requestPath=Join-Path $root ($name+'.request.json')
    $request|ConvertTo-Json -Depth 12|Set-Content $requestPath -Encoding utf8NoBOM
    $resultDirectory=Join-Path $root $name
    $cliArguments=@('-Device','TEST-TRANSPORT','-CanonicalSerial','TEST-CANONICAL','-BootId','TEST-BOOT','-Package','com.example.product','-Adb',$fakeAdb,'-AdbSha256',$adbHash,'-RequestPath',$requestPath,'-OutputDirectory',$resultDirectory,'-PersistenceScope',$scope)
    $output=@(& $pwshBinary -NoProfile -File $cli @cliArguments 2>&1);$code=$LASTEXITCODE
    $wire=$output -join "`n";$wire|Set-Content (Join-Path $root ($name+'.output.txt'))
    $actual=Get-Content (Join-Path $root 'state.json') -Raw|ConvertFrom-Json
    if($wire.Contains('PRIVATE-FIXTURE-SECRET')){throw 'Private config escaped normal output'}
    if($name -eq 'boot-mismatch'){
        if($code -eq 0 -or $actual.broadcasts -ne 0){throw 'Wrong boot dispatched'}
    }elseif($name -notin @('status-private','runtime-accepted','persistent-accepted','rejected')){
        if($code -eq 0 -or $actual.broadcasts -ne 1){throw "Unknown/mismatched receipt replayed or accepted: $name"}
        $failure=Get-Content (Join-Path $resultDirectory 'host-failure.json') -Raw|ConvertFrom-Json
        if(!$failure.sent -or !$failure.retry_forbidden){throw 'Missing unknown dispatch evidence'}
    }else{
        if($code -ne 0 -or $actual.broadcasts -ne 1){throw "Supported host invocation failed: $name"}
        $acl=Get-Acl $resultDirectory;$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        if(!$acl.AreAccessRulesProtected -or @($acl.Access).Count -ne 1 -or @($acl.Access|Where-Object {$_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -cne $sid}).Count){throw 'Private receipt ACL differs'}
        if($name -ne 'rejected' -and !([IO.File]::ReadAllText((Join-Path $resultDirectory 'app-receipt.json')).Contains('PRIVATE-FIXTURE-SECRET'))){throw 'Private raw receipt missing'}
        # Reusing an output address must stop before a second dispatch.
        $again=@(& $pwshBinary -NoProfile -File $cli @cliArguments 2>&1);$againCode=$LASTEXITCODE
        $after=Get-Content (Join-Path $root 'state.json') -Raw|ConvertFrom-Json
        if($againCode -eq 0 -or $after.broadcasts -ne 1){throw 'Existing invocation replayed'}
    }
    $cases.Add(@{name=$name;exit_code=$code;broadcasts=$actual.broadcasts;passed=$true})
}
[ordered]@{schema='rusty.quest.native_renderer.lsl.cli_host_tests.v1';passed=$true;device_operations=0;cases=@($cases);cli_sha256=(Get-FileHash $cli).Hash.ToLowerInvariant();at_utc=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json -Depth 8|Set-Content (Join-Path $root 'RESULT.json')
Write-Output 'LSL CLI host checks PASS: exact scope/tuple/types, private output/ACL, boot fence and unknown receipt without replay; no device operations.'
