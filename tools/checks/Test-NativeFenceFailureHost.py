"""Execute native fence first-failure methods with modeled OS/app/logging leaves."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def block(text, marker):
    start = text.index(marker)
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end].replace('pub(super) ', '')


def pin(path):
    return dict(path=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--rustc', type=Path, required=True)
    parser.add_argument('--host-deps', type=Path, required=True)
    args = parser.parse_args()
    root, out = args.root.resolve(), args.output.resolve()
    out.relative_to(root/'target')
    out.mkdir(parents=True, exist_ok=False)
    source = root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/process_fence.rs'
    fixture = root/'tools/checks/fixtures/native_fence_failure_host.rs'
    text = source.read_text(encoding='utf-8')
    libraries = {}
    for name in ['sha2', 'serde_json']:
        found = list(args.host_deps.glob(f'lib{name}-*.rlib'))
        if len(found) != 1:
            raise ValueError(f'Exactly one provisioned host {name} rlib required')
        libraries[name] = found[0]
    inputs = [source, fixture, Path(__file__).resolve(), args.rustc.resolve(), *libraries.values()]
    before = [pin(path) for path in inputs]
    selected = {
        'PRODUCTION_CONSTANTS': '\n'.join(re.findall(r'(?m)^const (?:APP_SCHEMA|APP_RUN_SCHEMA|NATIVE_SCHEMA|NATIVE_OWNER_SCHEMA|LOCK_NAME|WITNESS_NAME):[^\n]+',text)),
        'PRODUCTION_BINDING': '#[derive(Clone,Debug,Eq,PartialEq)]\n'+block(text,'pub(super) struct AppBinding'),
        'PRODUCTION_BINDING_IMPL': block(text,'impl AppBinding {'),
        'PRODUCTION_APP_SOURCE': block(text,'pub(super) trait AppFenceSource'),
        'PRODUCTION_CANONICAL': block(text,'fn canonical_handoff_json('),
        'PRODUCTION_COUNTER': block(text,'fn parse_counter('),
        'PRODUCTION_OWNER': block(text,'fn parse_owner_record('),
        'PRODUCTION_FIELDS': block(text,'fn value_fields('),
        'PRODUCTION_READ': block(text,'fn read_bounded('),
        'PRODUCTION_WRITER': block(text,'struct Writer {'),
        'PRODUCTION_OBSERVE': block(text,'fn observe(&self)'),
        'PRODUCTION_ALLOCATE': block(text,'fn allocate('),
        'PRODUCTION_POISON': block(text,'fn poison(') if '    fn poison(' in text else '',
        'PRODUCTION_CAPABILITY': block(text,'pub(super) struct NativeCapability'),
        'PRODUCTION_LIVE': block(text,'pub fn require_live('),
        'PRODUCTION_DIAGNOSTICS': '\n'.join(block(text, marker).replace('#[cfg(target_os = "android")]','') for marker in ['fn fence_failure_category(', 'fn emit_fence_failure('] if marker in text),
    }
    causes = ['native incarnation journal corrupt','native fence state missing or corrupt','native incarnation journal differs',
              'native generation write failed','native app channel attach failed','private callback é',
              'native app fence binding invalid or recovery unsupported','native admission requires pending app fence',
              'app generation changed','app operational run changed','app checkpoint changed','app evidence changed','app record digest changed']
    hashes = 'fn expected_hash(cause:&str)->&\'static str{match cause{'+''.join(json.dumps(c,ensure_ascii=False)+'=>"'+hashlib.sha256(c.encode()).hexdigest()+'",' for c in causes)+'_=>panic!("unlisted expected cause")}}'
    selected['EXPECTED_HASHES'] = hashes
    code = fixture.read_text(encoding='utf-8')
    for key in sorted(selected,key=len,reverse=True):
        code = code.replace(key,selected[key])
    generated = out/'native-fence.rs'
    generated.write_text(code,encoding='utf-8')
    binary = out/'native-fence.exe'
    command = [str(args.rustc),'--edition=2021','--test',str(generated),'-L','dependency='+str(args.host_deps),'-o',str(binary)]
    for name, library in libraries.items():
        command += ['--extern',name+'='+str(library)]
    checks = []
    for name, argv in [('compile',command),('controls',[str(binary),'--test-threads=1','--nocapture'])]:
        child = subprocess.run(argv,capture_output=True,timeout=60)
        (out/(name+'.stdout')).write_bytes(child.stdout)
        (out/(name+'.stderr')).write_bytes(child.stderr)
        checks.append(dict(name=name,exit_code=child.returncode))
        if child.returncode:
            break
    unchanged = before == [pin(path) for path in inputs]
    result = dict(passed=unchanged and all(c['exit_code']==0 for c in checks),source_unchanged=unchanged,checks=checks,source_pins=before,
                  generated_sha256=hashlib.sha256(generated.read_bytes()).hexdigest(),
                  extracted_body_sha256={key:hashlib.sha256(value.encode()).hexdigest() for key,value in selected.items()},
                  limits='Actual Writer observe/allocate, NativeCapability require_live, AppBinding/parser/canonical record logic and available first-failure helper bodies; only the helper Android log cfg is enabled for a modeled sink. File/fs/flock, process identity, app callback and logging are modeled leaves. Real host SHA256 and JSON libraries, no Android/JNI/device/physical-fence claim.',device_calls=0)
    (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(passed=result['passed'],checks=checks,source_unchanged=unchanged)))
    raise SystemExit(0 if result['passed'] else 1)


if __name__ == '__main__':
    main()
