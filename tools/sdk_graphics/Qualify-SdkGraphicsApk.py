"""Join fixed override names to actual APK primary/manifest/baseline bytes."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import zipfile
import zlib

spec = importlib.util.spec_from_file_location('fresh', Path(__file__).with_name('Prepare-FreshSdkHotload.py'))
fresh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fresh)


def apk_record(path):
    path = Path(path).absolute()
    if any(p.is_symlink() or p.is_junction() for p in (path, *path.parents)) or not path.is_file():
        raise ValueError('Regular non-reparse APK required')
    if not 0 < path.stat().st_size <= 536870912:
        raise ValueError('Bounded APK required')
    sha, size = hashlib.sha256(), 0
    with path.open('rb') as stream:
        while block := stream.read(1048576):
            sha.update(block)
            size += len(block)
    if size > 536870912:
        raise ValueError('APK grew beyond bound')
    return dict(bytes=size, sha256=sha.hexdigest())


def qualify(apk_path, catalog_path):
    catalog_path = Path(catalog_path)
    catalog = json.loads(catalog_path.read_text())
    fresh.catalog.selected_sdk_primary_profile(catalog.get('compileProfile'), True)
    if catalog.get('materialUniformBytes') != 160 or catalog.get('sdkVersion') != '0.14.0':
        raise ValueError('Selected optimized160 consumer required')
    baseline, = [b for b in catalog['batches'] if b['name'] == 'baseline']
    if baseline['vertexPaths'] != fresh.VERTEX or baseline['fragmentPaths'] != fresh.FRAGMENT:
        raise ValueError('Exact variant closure required')
    entries = {row['path']: row for row in baseline['files']}
    if len(entries) != 12 or len(baseline['files']) != 12 or set(entries) != set(fresh.VERTEX+fresh.FRAGMENT):
        raise ValueError('Complete unique baseline required')
    pins = apk_record(apk_path), fresh.record(catalog_path)
    rows = []
    with zipfile.ZipFile(apk_path) as apk:
        if len(apk.namelist()) != len(set(apk.namelist())):
            raise ValueError('Duplicate APK members refused')
        def read(name):
            info = apk.getinfo(name)
            if not 0 < info.file_size <= 2097152:
                raise ValueError('Bounded shader/manifest member required')
            return apk.read(name)
        data = read('assets/shader_manifest.bin')
        if len(data) < 12 or struct.unpack_from('<III', data)[:2] != (1397247302, 2):
            raise ValueError('SDK shader manifest v2 required')
        count, = struct.unpack_from('<I', data, 8)
        if count > 4096:
            raise ValueError('Bounded alias count required')
        offset = 12
        def text():
            nonlocal offset
            size, = struct.unpack_from('<I', data, offset)
            offset += 4
            if not 0 < size < 4096 or offset+size > len(data):
                raise ValueError('Invalid alias string')
            value = data[offset:offset+size].decode('utf-8')
            offset += size
            return value
        aliases = {}
        for _ in range(count):
            source, target = text(), text()
            if source in aliases:
                raise ValueError('Duplicate aliases refused')
            aliases[source] = target
        if offset != len(data):
            raise ValueError('Trailing manifest bytes refused')
        for name in fresh.VERTEX+fresh.FRAGMENT:
            target = aliases.get(name, name)
            if target not in fresh.VERTEX+fresh.FRAGMENT or (name in fresh.VERTEX and target != name):
                raise ValueError('Closed canonical mapping required')
            wrapped = read('assets/'+target)
            if wrapped.startswith(b'comp'):
                decoder = zlib.decompressobj()
                raw = decoder.decompress(wrapped[4:], 1048577)
                if len(raw) > 1048576 or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
                    raise ValueError('Invalid bounded compressed shader')
            else:
                raw = wrapped
            entry = entries[name]
            local = catalog_path.parent/'baseline'/name
            expected = {key:entry[key] for key in ('bytes','sha256')}
            if (fresh.record(local) != expected or len(raw) != entry['bytes'] or
                    hashlib.sha256(raw).hexdigest() != entry['sha256'] or
                    read('assets/hotload/baseline/'+name) != raw):
                raise ValueError('Actual primary/alias/packaged baseline differs')
            rows.append(dict(name=name, canonical_member='assets/'+target, **expected))
    if (apk_record(apk_path), fresh.record(catalog_path)) != pins:
        raise ValueError('APK/catalog drift during qualification')
    return dict(schema='rusty.quest.sdk_graphics.apk_join.v1', status='bytes-joined',
                apk=pins[0], catalog=pins[1], files=rows, receiver_enabled_proof=False,
                device_admission=False, gpu_completion=False, pixels_proven=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('apk','catalog','out'):
        parser.add_argument('--'+name, required=True, type=Path)
    args = parser.parse_args()
    result = qualify(args.apk, args.catalog)
    with args.out.open('x', encoding='utf-8') as output:
        output.write(json.dumps(result, indent=2)+'\n')


if __name__ == '__main__':
    main()
