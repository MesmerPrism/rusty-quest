"""Run the actual source payload/freshness owner tests without Android or Vulkan."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo-root', type=Path, required=True)
    parser.add_argument('--output-root', type=Path, required=True)
    parser.add_argument('--rustc', required=True)
    args = parser.parse_args()
    root = args.repo_root.resolve(strict=True)
    out = args.output_root.resolve()
    if out.exists() or not out.is_relative_to(root / 'target'):
        raise ValueError('Fresh output beneath the selected repository target required')
    source = root / 'apps/spatial-camera-panel-android/native-receipt/src'
    files = [source / name for name in ('stereo_input_set.rs', 'stereo_source_payload.rs')]
    before = {path: path.read_bytes() for path in files}
    out.mkdir(parents=True)
    harness = out / 'source_snapshot.rs'
    harness.write_text(
        '#![allow(dead_code)]\n' + ''.join(
            '#[path=' + json.dumps(path.as_posix()) + '] mod ' + path.stem + ';\n'
            for path in files), encoding='utf-8', newline='\n')
    executable = out / 'source_snapshot.exe'
    runs = []
    for label, argv in (
        ('compile', [args.rustc, '--edition=2021', '--test', str(harness), '-o', str(executable)]),
        ('controls', [str(executable), '--nocapture']),
    ):
        result = subprocess.run(argv, capture_output=True, timeout=60)
        (out / (label + '.stdout')).write_bytes(result.stdout)
        (out / (label + '.stderr')).write_bytes(result.stderr)
        runs.append(dict(name=label, exit_code=result.returncode))
        if result.returncode:
            break
    unchanged = all(path.read_bytes() == contents for path, contents in before.items())
    passed = unchanged and len(runs) == 2 and all(run['exit_code'] == 0 for run in runs)
    report = dict(
        passed=passed, runs=runs, sources_unchanged=unchanged,
        source_pins=[dict(path=str(path), sha256=hashlib.sha256(contents).hexdigest())
                     for path, contents in before.items()],
        scope='Actual complete source-slot and retained payload modules, including clock/lock '
              'ordering, freshness boundaries and payload retirement; no Android/GPU/device claim',
    )
    (out / 'RESULT.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
