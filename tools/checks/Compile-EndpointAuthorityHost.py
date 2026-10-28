#!/usr/bin/env python3
"""Compile the fifteen endpoint authority owner crates directly against warm external rlibs, without Cargo/cache writes."""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time
import tomllib


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('manifold-root', 'rust-deps', 'output'):
        p.add_argument('--' + name, type=pathlib.Path, required=True)
    p.add_argument('--rustc', default=shutil.which('rustc'))
    a = p.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    out = a.output.resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    selected = [
        'rusty-manifold-model', 'rusty-manifold-runtime-host', 'rusty-manifold-admission',
        'rusty-manifold-broker-product', 'rusty-manifold-broker-adapter', 'rusty-manifold-media-session',
        'rusty-manifold-peer', 'rusty-manifold-peer-runtime-host', 'rusty-quest-broker-admission',
        'rusty-quest-device-link', 'rusty-quest-media-stream', 'rusty-quest-feature-activation',
        'rusty-quest-broker-contracts', 'rusty-quest-media-stream-android', 'rusty-quest-broker-authority',
    ]
    compiled = {}
    rows = []
    started = time.monotonic()
    for name in selected:
        crate = (a.manifold_root if name.startswith('rusty-manifold-') else root) / 'crates' / name
        manifest = tomllib.loads((crate / 'Cargo.toml').read_text(encoding='utf-8'))
        args = [a.rustc, '--edition=2021', '--crate-type=rlib', '--crate-name', name.replace('-', '_'),
                str(crate / 'src/lib.rs'), '-L', 'dependency=' + str(out),
                '-L', 'dependency=' + str(a.rust_deps), '-A', 'warnings', '-C', 'debuginfo=0']
        dependencies = []
        for dependency, definition in manifest.get('dependencies', {}).items():
            if dependency in compiled:
                library = compiled[dependency]
            else:
                matches = list(a.rust_deps.glob('lib' + dependency.replace('-', '_') + '-*.rlib'))
                if len(matches) != 1:
                    raise ValueError('Select one warm external dependency: ' + dependency)
                library = matches[0]
            dependencies.append(library)
            args += ['--extern', dependency.replace('-', '_') + '=' + str(library)]
        if name == 'rusty-manifold-model':
            args += ['--cfg', 'feature="serde"']
        library = out / ('lib' + name.replace('-', '_') + '.rlib')
        args += ['-o', str(library)]
        env = dict(os.environ, CARGO_MANIFEST_DIR=str(crate.resolve()), CARGO_PKG_NAME=name,
                   CARGO_PKG_VERSION='0.1.0', CARGO_PKG_AUTHORS='Till Holzapfel',
                   CARGO_PKG_DESCRIPTION=manifest['package'].get('description', ''))
        (out / (name + '.command.json')).write_text(json.dumps(args, indent=2), encoding='utf-8')
        result = subprocess.run(args, capture_output=True, timeout=90, env=env, cwd=root)
        (out / (name + '.stdout')).write_bytes(result.stdout)
        (out / (name + '.stderr')).write_bytes(result.stderr)
        sources = [crate / 'Cargo.toml', *sorted((crate / 'src').rglob('*.rs'))]
        rows.append({'crate': name, 'exit': result.returncode, 'sources': [
            {'path': str(path.resolve()), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()} for path in sources]})
        print(name + ': ' + str(result.returncode), flush=True)
        if result.returncode:
            print(result.stderr.decode(errors='replace')[-5000:], file=sys.stderr)
            break
        compiled[name] = library
    success = len(compiled) == len(selected)
    record = {'passed': success, 'elapsed_seconds': round(time.monotonic() - started, 3),
              'rustc': subprocess.check_output([a.rustc, '--version'], text=True).strip(), 'crates': rows,
              'limits': __doc__, 'external_cache': str(a.rust_deps),
              'outputs': [{'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
                          for path in compiled.values()]}
    (out / 'BUILD.json').write_text(json.dumps(record, indent=2), encoding='utf-8')
    return 0 if success else 1


if __name__ == '__main__':
    sys.exit(main())
