"""Finite mv025 strength-phase iteration. No APK install or launch; never replay unknown dispatch.

The app must be debuggable, explicitly selected, focused and already rendering.
This tool reuses byte-pinned publication and validation from the existing owner.
"""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid

PACKAGE = 'io.github.mesmerprism.morphovision.mv025'
ROOT = 'no_backup/development-feedback-strength-phase'
DESCRIPTOR = ROOT + '/current-session.json'
COMMON_SHA = 'c2d2523ea271ee7e12e9fad668fff1cc4891a99b2740bc3a1429aa9fab35cb7e'
BUILDER_SHA = 'ebdf58f35b658af8c3658b0f0e4fe6c1485dc4f7fef38a9c956ad4a71f7adf59'
SESSION = re.compile(r'[A-Za-z0-9-]{1,96}\Z')
OWNER = re.compile(r'[A-Za-z0-9-]{1,128}\Z')


def validate_descriptor(value, pid, session, owner):
    if (not isinstance(value, dict) or set(value) != {'schema','host_kind','session_id','owner_token','pid','directory'}
        or value['schema'] != 'rusty.quest.hotload_current_session.v1'
        or value['host_kind'] != 'bilateral-strength-phase'
        or type(value['pid']) is not int or value['pid'] != pid
        or value['session_id'] != session or not SESSION.fullmatch(session)
        or value['directory'] != session or value['owner_token'] != owner or not OWNER.fullmatch(owner)):
        raise ValueError('exact bilateral process/session/owner descriptor required')
    return value


def load_common(path):
    if hashlib.sha256(path.read_bytes()).hexdigest() != COMMON_SHA:
        raise ValueError('existing hotload operator source hash changed')
    spec = importlib.util.spec_from_file_location('quest_hotload_owner', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_command(common, value):
    common.validate(value)
    if value['method'] not in ('native_activate','native_status'):
        raise ValueError('only bilateral activation/status supported')
    return value


def unsigned(value):
    if type(value) is int and 0 <= value < 2**64:
        return value
    if isinstance(value, str) and re.fullmatch(r'0|[1-9][0-9]{0,19}', value) and int(value) < 2**64:
        return int(value)
    raise ValueError('canonical unsigned phase evidence required')


def validate_observation(value, request, pid, session, owner):
    if (not isinstance(value, dict) or set(value) != {'schema','request_id','session_id','owner_token','pid','status','error','host','phase'}
        or value['schema'] != 'rusty.quest.bilateral_strength_phase_observation.v1'
        or value['request_id'] != request or value['session_id'] != session or value['owner_token'] != owner
        or type(value['pid']) is not int or value['pid'] != pid or value['status'] not in ('ok','error')
        or not (value['error'] is None or isinstance(value['error'],str))
        or not (value['host'] is None or isinstance(value['host'],dict))):
        raise ValueError('bilateral observation identity/envelope')
    phase = value['phase']
    if (not isinstance(phase,dict) or set(phase) != {'source','effective_phase','adopted_module_generation','phase_call_count'}
        or phase['source'] not in ('module','no_host','invalid_input','host_error','invalid_output')
        or type(phase['effective_phase']) not in (float,int)
        or not math.isfinite(phase['effective_phase']) or not 0 <= phase['effective_phase'] < 1):
        raise ValueError('effective phase evidence')
    adopted = unsigned(phase['adopted_module_generation'])
    unsigned(phase['phase_call_count'])
    if (phase['source'] == 'module') != (adopted > 0):
        raise ValueError('semantic adoption differs from phase source')
    return value


class Client:
    def __init__(self, args, common):
        self.args, self.common = args, common
        # Reuse only the existing transport and no-clobber publisher. Its package
        # map/identity/command methods remain unchanged and are never called.
        self.transport = common.Operator.__new__(common.Operator)
        self.transport.args, self.transport.package = args, PACKAGE

    def identity(self):
        pid = self.transport.shell('pidof ' + shlex.quote(PACKAGE)).stdout.decode().strip()
        if pid != str(self.args.pid):
            raise ValueError('process changed; rediscover, do not replay')
        value = self.common.decode(self.transport.app('cat '+shlex.quote(DESCRIPTOR)).stdout)
        return validate_descriptor(value, self.args.pid, self.args.session, self.args.owner)

    def directory(self):
        # identity() validates these bounded path components before every effect.
        return ROOT + '/' + self.args.session

    def observe(self, request):
        if not self.common.ID.fullmatch(request):
            raise ValueError('request identity')
        self.identity()
        result = self.transport.app('cat '+shlex.quote(self.directory()+'/observation-'+request+'.json'), check=False)
        if result.returncode:
            return None
        value = validate_observation(self.common.decode(result.stdout), request, self.args.pid, self.args.session, self.args.owner)
        self.identity()
        return value

    def stage(self, source, generation, journal):
        validate_command(self.common, dict(schema='rusty.quest.hotload_command.v1', request_id='stage', method='native_activate',
            arguments=dict(generation=generation,sha256='0'*64,probe_result='0')))
        self.identity()
        if source.is_symlink() or not source.is_file() or not 0 < source.stat().st_size <= 4*1024*1024:
            raise ValueError('ordinary native image within 4MiB required')
        data = source.read_bytes()
        if not 0 < len(data) <= 4*1024*1024:
            raise ValueError('native image size changed')
        sha = hashlib.sha256(data).hexdigest()
        record = dict(schema='rusty.quest.bilateral_strength_phase_stage.v1',state='execution_result_unknown',
            serial=self.args.serial,package=PACKAGE,pid=self.args.pid,session=self.args.session,owner=self.args.owner,
            generation=generation,sha256=sha,bytes=len(data))
        self.common.create_json(journal/('stage-'+generation+'.pending.json'),record)
        self.transport.publish(self.directory()+'/generation-'+generation+'.so',data)
        self.identity()
        record['state']='staged'
        self.common.create_json(journal/('stage-'+generation+'.confirmed.json'),record)
        return record

    def send(self, command, journal):
        validate_command(self.common, command)
        self.identity()
        request = command['request_id']
        if self.observe(request) is not None:
            raise ValueError('request already observed; use observe')
        self.common.create_json(journal/(request+'.pending.json'),dict(
            schema='rusty.quest.bilateral_strength_phase_dispatch.v1',state='execution_result_unknown',
            serial=self.args.serial,package=PACKAGE,pid=self.args.pid,session=self.args.session,owner=self.args.owner,command=command))
        self.transport.publish(self.directory()+'/command-'+request+'.json',json.dumps(command,separators=(',',':')).encode())
        for _ in range(40):
            value = self.observe(request)
            if value is not None:
                self.common.create_json(journal/(request+'.observation.json'),value)
                return value
            time.sleep(.1)
        return dict(request_id=request,state='execution_result_unknown',next='observe this exact request; do not resend')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--common-source',type=Path,required=True,help='Exact published quest_hotload.py source; SHA256 is checked')
    parser.add_argument('--adb',required=True)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--pid',type=int,required=True)
    parser.add_argument('--session',required=True)
    parser.add_argument('--owner',required=True)
    sub=parser.add_subparsers(dest='operation',required=True)
    stage=sub.add_parser('stage'); stage.add_argument('source',type=Path); stage.add_argument('--generation',required=True); stage.add_argument('--journal',type=Path,required=True)
    send=sub.add_parser('send'); send.add_argument('command',type=Path); send.add_argument('--journal',type=Path,required=True)
    observe=sub.add_parser('observe'); observe.add_argument('request_id')
    iterate=sub.add_parser('iterate')
    for name in ('pwsh','rustc','android-ndk','plugin-source','generation','request-id','output'):
        iterate.add_argument('--'+name,required=True)
    iterate.add_argument('--variant',choices=('A','B'),required=True)
    iterate.add_argument('--probe-result')
    args=parser.parse_args()
    common=load_common(args.common_source)
    client=Client(args,common)
    if args.operation == 'observe':
        result=client.observe(args.request_id)
    elif args.operation in ('stage','send'):
        args.journal.mkdir(parents=True,exist_ok=True)
        result=client.stage(args.source,args.generation,args.journal) if args.operation=='stage' else client.send(common.decode(args.command.read_bytes()),args.journal)
    else:
        # This is module compilation plus app-owned reload, never an APK build.
        probe=args.probe_result or ('1048576005' if args.variant=='A' else '1056964613')
        command=dict(schema='rusty.quest.hotload_command.v1',request_id=args.request_id,method='native_activate',
            arguments=dict(generation=args.generation,sha256='0'*64,probe_result=probe))
        validate_command(common,command)
        client.identity()
        output=Path(args.output)
        output.mkdir(parents=True,exist_ok=False)
        journal=output/'journal'; journal.mkdir()
        builder=args.common_source.with_name('Build-NativeGeneration.ps1')
        if hashlib.sha256(builder.read_bytes()).hexdigest() != BUILDER_SHA:
            raise ValueError('existing generation builder source hash changed')
        argv=[args.pwsh,'-NoProfile','-File',str(builder),'-Rustc',args.rustc,'-AndroidNdk',args.android_ndk,
              '-PluginSource',args.plugin_source,'-Generation',args.generation,'-Variant',args.variant,'-OutputRoot',str(output/'build')]
        common.create_json(output/'build-intent.json',dict(schema='rusty.quest.bilateral_strength_phase_build_intent.v1',argv=argv))
        compiled=subprocess.run(argv,capture_output=True)
        (output/'build.stdout').write_bytes(compiled.stdout)
        (output/'build.stderr').write_bytes(compiled.stderr)
        common.create_json(output/'build-execution.json',dict(exit_code=compiled.returncode,stdout='build.stdout',stderr='build.stderr'))
        compiled.check_returncode()
        receipt=common.decode((output/'build/result.json').read_bytes())
        artifact=Path(receipt['artifact']['path'])
        if receipt['generation'] != args.generation or hashlib.sha256(artifact.read_bytes()).hexdigest() != receipt['artifact']['sha256']:
            raise ValueError('built generation identity drifted')
        staged=client.stage(artifact,args.generation,journal)
        command['arguments']['sha256']=staged['sha256']
        result=client.send(command,journal)
        # Activation is not evidence of a later phase call or GPU pixels.
        common.create_json(output/'activation-result.json',result)
        if result.get('status')=='ok':
            observation=client.send(dict(schema='rusty.quest.hotload_command.v1',request_id='phase-'+uuid.uuid4().hex,
                method='native_status',arguments={}),journal)
            phase=observation.get('phase',{})
            adopted=(observation.get('status')=='ok' and phase.get('source')=='module'
                and unsigned(phase.get('adopted_module_generation',0))==int(args.generation)
                and unsigned(phase.get('phase_call_count',0))>unsigned(result['phase']['phase_call_count']))
            result=dict(activation=result,phase_observation=observation,phase_adopted=adopted,pixels_proved=False)
            common.create_json(output/'iteration-result.json',result)
    print(json.dumps(result,indent=2,allow_nan=False))


if __name__=='__main__':
    main()
