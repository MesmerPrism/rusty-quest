"""Extract unchanged actual app items and compile their real provider/step join.

This is a CPU host proof, not an APK, Android execution, GPU or pixel claim.
"""
import argparse, hashlib, json, os, subprocess, sys, time
from pathlib import Path


def item(text, marker):
    start = text.index(marker)
    pos = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[pos] == '{') - (text[pos] == '}')
        pos += 1
    return text[start:pos]


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--rustc', default='rustc')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--cargo', default='cargo')
    p.add_argument('--cargo-target-dir', type=Path)
    p.add_argument('--android-check', action='store_true')
    a = p.parse_args()
    root = Path(__file__).resolve().parents[2]
    native = root / 'apps/spatial-camera-panel-android/native-receipt/src'
    provider = root / 'crates/rusty-quest-development-feedback-native/src/lib.rs'
    abi = root / 'examples/development-feedback-native/src/abi.rs'
    fixture = root / 'tools/checks/fixtures'
    inputs = [provider, *abi.parent.glob('*.rs'), native/'spatial_public_multistack_runtime.rs', native/'strength_cycle_step.rs', native/'strength_cycle_adapter.rs', native/'strength_cycle_hotload.rs', native/'lib.rs', root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/SpatialCameraPanelActivity.kt', fixture/'strength_cycle_transport_tests.rs', fixture/'strength_cycle_phase_plugin.rs', fixture/'strength_cycle_phase_host.rs', Path(__file__).resolve()]
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    inputs += [root/'tools/development_feedback/quest_hotload.py', root/'tools/development_feedback/Build-NativeGeneration.ps1', root/'apps/spatial-camera-panel-android/tools/strength_phase_hotload.py', root/'apps/spatial-camera-panel-android/tools/test_strength_phase_hotload.py']
    pins = {str(x): digest(x) for x in inputs}
    a.output.mkdir(parents=True, exist_ok=False)
    commands = []
    def run(argv, name, env=None):
        started = time.monotonic()
        r = subprocess.run(argv, capture_output=True, timeout=90, env=env)
        (a.output/f'{name}.stdout').write_bytes(r.stdout)
        (a.output/f'{name}.stderr').write_bytes(r.stderr)
        commands.append(dict(argv=argv, exit=r.returncode, seconds=time.monotonic()-started))
        (a.output/'COMMANDS.json').write_text(json.dumps(commands, indent=2))
        assert r.returncode == 0, (name, r.stderr.decode(errors='replace'), r.stdout.decode(errors='replace'))
        return r.stdout.decode(errors='replace')
    libraries = []
    for generation, variant, cfg in [(1,0,None),(2,0,'phase_b'),(3,0,None),(4,1,None),(5,0,'semantic_bad')]:
        src = a.output/f'g{generation}.rs'
        src.write_text(f'#![allow(dead_code)]\nconst GENERATION:u64={generation};\nconst VARIANT:u32={variant};\n#[path="{abi.as_posix()}"] mod abi;\ninclude!("{(fixture/"strength_cycle_phase_plugin.rs").as_posix()}");\n')
        lib = a.output/f'phase-g{generation}.dll'
        argv = [a.rustc,'--edition=2021','--crate-type=cdylib','-C','panic=abort','-C','opt-level=2','-D','warnings',str(src),'-o',str(lib)]
        if cfg: argv += ['--cfg',cfg]
        run(argv,f'compile-g{generation}')
        os.chmod(lib,0o444)
        libraries.append(str(lib))
    text = (native/'spatial_public_multistack_runtime.rs').read_text()
    actual = '\n'.join(['#[derive(Default)]',item(text,'struct SpatialPublicStrengthCyclePhase {'),item(text,'fn normalize_strength_cycle_speed_hz('),item(text,'fn advance_strength_cycle_phase(')])
    (a.output/'ACTUAL_APP_ITEMS.rs').write_text(actual)
    source = '#![allow(dead_code)]\n'+f'#[path="{provider.as_posix()}"] mod feedback;\npub use feedback::{{abi,digest,loader,runtime}};\n#[path="{(native/"strength_cycle_adapter.rs").as_posix()}"] mod strength_cycle_adapter;\n#[path="{(native/"strength_cycle_step.rs").as_posix()}"] mod strength_cycle_step;\n'+'const SPATIAL_PUBLIC_STRENGTH_CYCLE_SPEED_HZ_MAX:f32=2.0;\nconst SPATIAL_PUBLIC_STRENGTH_CYCLE_SPEED_HZ_DEFAULT:f32=0.25;\n'+actual+f'\ninclude!("{(fixture/"strength_cycle_phase_host.rs").as_posix()}");\n'
    src = a.output/'PROOF.rs'; src.write_text(source)
    executable = a.output/'proof.exe'
    run([a.rustc,'--edition=2021','-D','warnings',str(src),'-o',str(executable)],'compile-actual-app')
    stdout = run([str(executable),*libraries],'actual-app-proof')
    transport = a.output/'transport'; (transport/'src').mkdir(parents=True)
    (transport/'Cargo.toml').write_text('[workspace]\n[package]\nname="bilateral-strength-transport-host"\nversion="0.0.0"\nedition="2021"\n[dependencies]\nserde_json="=1.0.151"\n[target.\'cfg(target_os="android")\'.dependencies]\njni="=0.21.1"\n')
    (transport/'src/lib.rs').write_text('#![allow(dead_code)]\n'+f'#[path="{provider.as_posix()}"] mod feedback;\npub use feedback::{{abi,digest,loader,runtime}};\n#[path="{(native/"strength_cycle_adapter.rs").as_posix()}"] mod strength_cycle_adapter;\n#[path="{(native/"strength_cycle_step.rs").as_posix()}"] mod strength_cycle_step;\n#[path="{(native/"strength_cycle_hotload.rs").as_posix()}"] mod strength_cycle_hotload;\n')
    env = dict(os.environ); env['RQ_STRENGTH_PHASE_TEST_LIBRARIES'] = os.pathsep.join(libraries)
    env['RQ_STRENGTH_PHASE_TEST_EVIDENCE'] = str(a.output/'actual-queue-evidence')
    env['PHASE_COMMON_SOURCE'] = str(root/'tools/development_feedback/quest_hotload.py')
    env['PYTHONDONTWRITEBYTECODE'] = '1'
    cargo_common = [a.cargo, '--offline', '--manifest-path', str(transport/'Cargo.toml')]
    target_args = ['--target-dir',str(a.cargo_target_dir or a.output/'cargo-target')]
    run([cargo_common[0],'test',*cargo_common[1:],*target_args,'--','--test-threads=1'],'transport-tests',env)
    if a.android_check:
        run([cargo_common[0],'check',*cargo_common[1:],*target_args,'--target','aarch64-linux-android'],'android-jni-typecheck',env)
    run([sys.executable,'-B','-m','unittest','discover','-s',str(root/'apps/spatial-camera-panel-android/tools'),'-p','test_strength_phase_hotload.py','-v'],'cli-contract-tests',env)
    cli_source = root/'apps/spatial-camera-panel-android/tools/strength_phase_hotload.py'
    producer_cli = a.output/'PRODUCER_CLI.py'
    producer_cli.write_text('import importlib.util,json\nfrom pathlib import Path\n'+f'p=Path({str(cli_source)!r})\ns=importlib.util.spec_from_file_location("phase_cli",p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)\nd=Path({str(a.output/"actual-queue-evidence")!r})\n'+'descriptor=json.loads((d/"DESCRIPTOR.json").read_text())\nobserved=json.loads((d/"OBSERVED.json").read_text())\nm.validate_descriptor(descriptor,descriptor["pid"],descriptor["session_id"],descriptor["owner_token"])\nm.validate_observation(observed,observed["request_id"],descriptor["pid"],descriptor["session_id"],descriptor["owner_token"])\nassert observed["phase"]["source"]=="module" and observed["phase"]["adopted_module_generation"]==1 and observed["phase"]["phase_call_count"]==1\nprint("PASS actual app queue producer to pinned CLI validator")\n')
    run([sys.executable,'-B',str(producer_cli)],'actual-producer-cli-seam',env)
    assert pins == {str(x):digest(x) for x in inputs}, 'source drift'
    result = dict(schema='rusty.quest.bilateral_strength_phase_host.v1',passed=True,scope='actual unchanged extracted app function + real provider/adapter/step; Windows CPU only',inputs=pins,actual_function_sha256=hashlib.sha256(item(text,'fn advance_strength_cycle_phase(').encode()).hexdigest(),commands=commands,stdout=stdout)
    (a.output/'RESULT.json').write_text(json.dumps(result,indent=2)); print(stdout,end='')

if __name__ == '__main__': main()
