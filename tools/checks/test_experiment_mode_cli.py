"""Actual child PowerShell CLI/argument/request/result fixtures. No ADB or port is used."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

FAKE = r'''$ErrorActionPreference='Stop';$global:LASTEXITCODE=0
$case=$env:RQ_MODE_TEST_CASE
$log=Join-Path $PSScriptRoot 'calls.jsonl'
($args|ConvertTo-Json -Compress)|Add-Content -LiteralPath $log
if($args.Count -lt 5 -or $args[0] -cne '-s' -or $args[1] -cne 'inert-transport' -or $args[2] -cne 'shell'){throw 'Not the fixed inert target'}
if($args[3] -ceq 'getprop' -and $args[4] -ceq 'ro.serialno'){'SYNTHETIC-ONLY';return}
if($args[3] -ceq 'cat' -and $args[4] -ceq '/proc/sys/kernel/random/boot_id'){'synthetic-boot';return}
if($args[3] -ceq 'pidof' -and $args[4] -ceq 'synthetic.app'){if($case -ceq 'stopped'){$global:LASTEXITCODE=1;return};'123';return}
if($args.Count -ne 11 -or ($args[3..9] -join '|') -cne 'content|call|--uri|content://synthetic.app.debug-experiment-control|--method|experiment-control|--arg'){throw 'Unexpected inert call arguments'}
$encoded=$args[10];$encoded=$encoded.PadRight($encoded.Length+(4-$encoded.Length%4)%4,'=')
$r=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))|ConvertFrom-Json -Depth 40
($r|ConvertTo-Json -Depth 40 -Compress)|Add-Content -LiteralPath (Join-Path $PSScriptRoot 'requests.jsonl')
if($case -ceq 'old59'){'Error: operator-operation-invalid (old APK)';return}
$receipt=@{schema='rusty.quest.experiment_session.operator_receipt.v1';mode_operator_version=1;operation=$r.operation}
if($r.operation -ceq 'control-mode-status'){
 $receipt.pid=123;$receipt.uid=10191;$receipt.process_start_ticks=456;$receipt.apk_sha256=('a'*64)
 $receipt.mode_result=@{host_id='host-1';carrier_generation=1;native_owner_epoch=7;binding_sha256=('b'*64);mode='direct';mode_packed=21;mode_revision=2
  session=@{runtime_epoch=7;generation=4;session_key='recording-key';condition_id='condition-a';non_audio_profile_sha256='profile';control_state='running';recording=$true;condition_elapsed_ms=991}}
 if($case -ceq 'reverse'){$receipt.mode_result.mode='remote';$receipt.mode_result.mode_packed=30;$receipt.mode_result.mode_revision=3}
}elseif($r.operation -ceq 'set-control-mode'){
 $keys=@('schema','operation','operation_id','requested_mode','expected_pid','expected_uid','expected_process_start_ticks','expected_apk_sha256','expected_binding_sha256','expected_runtime_epoch','expected_generation','expected_session_key','expected_condition','expected_profile_sha256','expected_host_id','expected_carrier_generation','expected_native_owner_epoch','expected_mode_packed','expected_mode_revision','operator_origin')
 if(@($r.PSObject.Properties).Count -ne $keys.Count -or @($keys|Where-Object{$null -eq $r.PSObject.Properties[$_]}).Count){throw 'CLI did not construct exact closed request'}
 $requested='remote';$packed=21;$revision=2
 if($case -ceq 'reverse'){$requested='direct';$packed=30;$revision=3}
 if($r.requested_mode -cne $requested -or $r.expected_pid -ne 123 -or $r.expected_uid -ne 10191 -or $r.expected_process_start_ticks -ne 456 -or $r.expected_apk_sha256 -cne ('a'*64) -or $r.expected_binding_sha256 -cne ('b'*64) -or $r.expected_runtime_epoch -ne 7 -or $r.expected_generation -ne 4 -or $r.expected_session_key -cne 'recording-key' -or $r.expected_condition -cne 'condition-a' -or $r.expected_profile_sha256 -cne 'profile' -or $r.expected_host_id -cne 'host-1' -or $r.expected_mode_packed -ne $packed -or $r.expected_mode_revision -ne $revision -or $r.expected_carrier_generation -ne 1 -or $r.expected_native_owner_epoch -ne 7 -or $r.operator_origin -cne 'shell-experiment-operator-v1'){throw 'CLI did not bind exact fresh scope'}
 $original=@{operation_id=$r.operation_id;state='accepted';original_request=$r;native_acceptance=@{command_status='accepted';mode=$requested};presentation=@{status='pending';requested_visible=($requested -ceq 'direct')}}
 $original|ConvertTo-Json -Depth 40|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'original.json')
 if($case -ceq 'reply-lost'){'no receipt';return}
 $receipt.mode_result=@{operation_id=$r.operation_id;state='pending';original_request=$r}
}elseif($r.operation -ceq 'control-mode-receipt'){
 if(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'original.json')){$receipt.mode_result=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'original.json') -Raw|ConvertFrom-Json -Depth 40}
 else{$receipt.mode_result=@{operation_id=$r.operation_id;state='outcome_unknown';reason='original-request-unavailable-no-replay'}}
}else{throw 'Unclosed mode operation'}
$json=$receipt|ConvertTo-Json -Depth 40 -Compress
'Bundle[{receipt_b64='+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json)).TrimEnd('=')+'}]'
'''


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--pwsh',type=Path,required=True)
    a=p.parse_args()
    cli=Path(__file__).resolve().parents[1]/'Invoke-ExperimentControlMode.ps1'
    with tempfile.TemporaryDirectory(prefix='rq-mode-cli-') as t:
        root=Path(t);fake=root/'inert-adb.ps1';fake.write_text(FAKE,encoding='utf-8')
        digest=hashlib.sha256(fake.read_bytes()).hexdigest()
        base=['-Device','inert-transport','-CanonicalSerial','SYNTHETIC-ONLY','-BootId','synthetic-boot','-Package','synthetic.app','-Adb',str(fake),'-AdbSha256',digest]
        counter=0
        def run(mode='remote',case='',extra=(),accept=True,arguments=None):
            nonlocal counter
            counter+=1;out=root/('receipt-'+str(counter));env=dict(os.environ,RQ_MODE_TEST_CASE=case)
            command=[str(a.pwsh),'-NoProfile','-File',str(cli),'-Mode',mode,*((base if arguments is None else arguments)), '-OutputDirectory',str(out),*extra]
            if mode!='status' and '-ExpectedApkSha256' not in extra:command+=['-ExpectedApkSha256','a'*64]
            r=subprocess.run(command,env=env,text=True,capture_output=True)
            assert (r.returncode==0)==accept,(r.stdout,r.stderr)
            return out,r
        def requests():
            f=root/'requests.jsonl'
            return [json.loads(line) for line in f.read_text(encoding='utf-8-sig').splitlines()] if f.exists() else []
        out,r=run();result=json.loads((out/'result.json').read_text(encoding='utf-8'))
        original=json.loads((out/'set-request.json').read_text())
        assert result['mode_result']['original_request']==original
        assert result['mode_result']['presentation']['status']=='pending'
        assert [r['operation'] for r in requests()]==['control-mode-status','set-control-mode','control-mode-receipt']
        assert len([x for x in requests() if x['operation']=='set-control-mode'])==1
        # Inert status, same-selected Direct and explicit read-only original reconciliation.
        count=len(requests());run('status');run('direct');run('status',extra=['-OperationId',original['operation_id']])
        assert [r['operation'] for r in requests()[count:]]==['control-mode-status','control-mode-status','control-mode-receipt']
        reverse,_=run('direct',case='reverse');assert json.loads((reverse/'set-request.json').read_text())['requested_mode']=='direct'
        for flag,value in [('-CanonicalSerial','OTHER'),('-BootId','OTHER'),('-AdbSha256','0'*64)]:
            args=base.copy();args[args.index(flag)+1]=value;before=len(requests());run(arguments=args,accept=False);assert len(requests())==before
        before=len(requests());run(extra=['-ExpectedApkSha256','0'*64],accept=False);assert len(requests())==before+1
        before=len(requests());run(case='stopped',accept=False);assert len(requests())==before
        _,old=run(case='old59',accept=False);assert '59' in old.stderr
        # Lost mutation response keeps original request on disk. Reconcile that original once; never resend.
        out,_=run(case='reply-lost',accept=False);lost=json.loads((out/'set-request.json').read_text());before=len(requests())
        recovered,_=run('status',extra=['-OperationId',lost['operation_id']]);assert json.loads((recovered/'result.json').read_text())['mode_result']['operation_id']==lost['operation_id']
        assert [r['operation'] for r in requests()[before:]]==['control-mode-receipt']
        # Real parameter binding rejects unclosed mode/raw JSON before dispatch.
        before=len(requests());run(mode='choose',accept=False);run(extra=['-RequestPath','anything'],accept=False);assert len(requests())==before
        existing=[str(a.pwsh),'-NoProfile','-File',str(cli),'-Mode','status',*base,'-OutputDirectory',str(out)]
        assert subprocess.run(existing,text=True,capture_output=True).returncode!=0
        print('PASS child PowerShell runtime mode CLI: exact arguments/fresh typed fences/original reconciliation, inert status and same mode, stopped process, serial/boot/ADB/APK mismatch, unsupported59, lost reply and no replay; no ADB or ports')


if __name__=='__main__':main()
