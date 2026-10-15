#requires -Version 7.6
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Python,
    [Parameter(Mandatory)][string]$OutDirectory
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($OutDirectory)
if(Test-Path -LiteralPath $root){throw 'Create-new test output required.'}
New-Item -ItemType Directory -Path $root | Out-Null
$pythonBinary=(Resolve-Path -LiteralPath $Python).Path
$stub=Join-Path $root 'fake-adb.py'
@'
import sys,json,base64
from pathlib import Path
p=Path(__file__).with_name('state.json')
s=json.loads(p.read_text(encoding='utf-8'))
a=sys.argv[1:]
assert a[:2]==['-s','TEST-TRANSPORT']
command=a[2:]
if command==['shell','getprop','ro.serialno']: print('TEST-CANONICAL')
elif command==['shell','cat','/proc/sys/kernel/random/boot_id']: print(s['boot'])
elif command[:3]==['shell','am','broadcast']:
    s['broadcasts']+=1
    p.write_text(json.dumps(s),encoding='utf-8')
    assert command[3:7]==['-a','io.github.mesmerprism.rustyquest.native_renderer.action.BREATH_COMPOSITION_COMMAND','-n','com.example.product/io.github.mesmerprism.rustyquest.native_renderer.BreathCompositionCommandReceiver']
    assert command[7:9]==['--es','command_b64'] and len(command)==10
    raw=base64.b64decode(command[9]+'='*((-len(command[9]))%4)).decode()
    request=json.loads(raw)
    assert request['schema']=='rusty.quest.product_panel.command.v1'
    if s['mode']=='missing': print('Broadcast completed: result=-1')
    else:
        receipt={'schema':'rusty.quest.product_panel.receipt.v1','status':'current' if request['operation']=='status' else 'dispatched','owner_token':'a'*32,'request_id':request.get('request_id',''),'action':request.get('action',''),'result':{'key':'PRIVATE-FIXTURE-SECRET'}}
        if s['mode']=='wrong-owner': receipt['owner_token']='b'*32
        if s['mode']=='wrong-id': receipt['request_id']='ffffffffffffffff'
        if s['mode']=='wrong-action': receipt['action']='network-stop'
        if s['mode']=='missing-correlation': del receipt['owner_token']
        body=base64.b64encode(json.dumps(receipt).encode()).decode().rstrip('=')
        print('Broadcast completed: result=-1, data="'+body+'"')
else: raise SystemExit('Unexpected fixed command')
'@ | Set-Content -LiteralPath $stub -Encoding utf8NoBOM
$fakeAdb=Join-Path $root 'fake-adb.cmd'
@"
@echo off
"$pythonBinary" "$stub" %*
exit /b %errorlevel%
"@ | Set-Content -LiteralPath $fakeAdb -Encoding ascii
$adbHash=(Get-FileHash -LiteralPath $fakeAdb).Hash.ToLowerInvariant()
$cli=Join-Path $PSScriptRoot 'Invoke-ProductPanelControl.ps1'
$pwshBinary=(Get-Process -Id $PID).Path
$cases=[Collections.Generic.List[object]]::new()
foreach($caseName in @('status-private-receipt','boot-mismatch-no-dispatch','missing-ack-no-replay','enrollment-private-output','wrong-owner','wrong-id','wrong-action','missing-correlation')){
    $hostile=$caseName -in @('wrong-owner','wrong-id','wrong-action','missing-correlation')
    $state=@{boot=if($caseName -eq 'boot-mismatch-no-dispatch'){'WRONG-BOOT'}else{'TEST-BOOT'};mode=if($caseName -eq 'missing-ack-no-replay'){'missing'}elseif($hostile){$caseName}else{'receipt'};broadcasts=0}
    $state | ConvertTo-Json | Set-Content (Join-Path $root 'state.json') -Encoding utf8NoBOM
    $request=@{schema='rusty.quest.product_panel.command.v1';operation='status'}
    if($caseName -eq 'enrollment-private-output' -or $hostile){
        $request=@{schema='rusty.quest.product_panel.command.v1';operation='invoke';owner_token=('a'*32);request_id='0000000000000001';action='network-enroll';parameters=@{trusted_lan=$true;consent=$true}}
    }
    $requestPath=Join-Path $root ($caseName+'.request.json')
    $request | ConvertTo-Json -Depth 8 | Set-Content $requestPath -Encoding utf8NoBOM
    $resultDirectory=Join-Path $root $caseName
    $output=@(& $pwshBinary -NoProfile -File $cli -Device TEST-TRANSPORT -CanonicalSerial TEST-CANONICAL -BootId TEST-BOOT -Package com.example.product -Adb $fakeAdb -AdbSha256 $adbHash -RequestPath $requestPath -OutputDirectory $resultDirectory 2>&1)
    $code=$LASTEXITCODE
    $wire=$output -join "`n"
    $wire | Set-Content (Join-Path $root ($caseName+'.output.txt'))
    $actual=Get-Content (Join-Path $root 'state.json') | ConvertFrom-Json
    if($wire.Contains('PRIVATE-FIXTURE-SECRET')){throw 'Secret escaped normal CLI output.'}
    if($caseName -eq 'boot-mismatch-no-dispatch'){
        if($code -eq 0 -or $actual.broadcasts -ne 0){throw 'Wrong-boot action dispatched.'}
    }elseif($caseName -eq 'missing-ack-no-replay' -or $hostile){
        if($code -eq 0 -or $actual.broadcasts -ne 1){throw 'Missing/mismatched acknowledgement was replayed or claimed.'}
        $failure=Get-Content (Join-Path $resultDirectory 'host-failure.json') | ConvertFrom-Json
        if(!$failure.sent -or !$failure.retry_forbidden){throw 'Unknown dispatch evidence missing.'}
    }else{
        if($code -ne 0 -or $actual.broadcasts -ne 1){throw 'Typed fixture invocation failed.'}
        $privateReceipt=[IO.File]::ReadAllText((Join-Path $resultDirectory 'app-receipt.json'))
        if(!$privateReceipt.Contains('PRIVATE-FIXTURE-SECRET')){throw 'Actual private receipt missing.'}
        $acl=Get-Acl -LiteralPath $resultDirectory
        $ownerSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        if(!$acl.AreAccessRulesProtected -or @($acl.Access).Count -ne 1 -or @($acl.Access | Where-Object {$_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -cne $ownerSid}).Count){throw 'Private receipt ACL changed.'}
    }
    $cases.Add(@{name=$caseName;exit_code=$code;broadcasts=$actual.broadcasts;passed=$true})
}
$result=@{schema='rusty.quest.product_panel.cli_host_test.v1';passed=$true;device_operations=0;cases=@($cases);cli_sha256=(Get-FileHash $cli).Hash.ToLowerInvariant();at_utc=[DateTime]::UtcNow.ToString('o')}
$result | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $root 'RESULT.json')
Write-Output 'Product panel CLI PASS: private output/ACL, exact fixed target, boot rejection and unknown acknowledgement without replay. No device operations.'
