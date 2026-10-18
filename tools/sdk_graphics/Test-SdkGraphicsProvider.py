"""Generic provider publication/transaction checks; no APK or device effects."""
import importlib.util
import json
from pathlib import Path
import tempfile
import struct
import unittest
import zipfile
import zlib
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('fresh_tests', Path(__file__).with_name('Test-FreshSdkHotload.py'))
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
fresh = fixtures.fresh
catalog = fresh.catalog
spec = importlib.util.spec_from_file_location('apk_join', Path(__file__).with_name('Qualify-SdkGraphicsApk.py'))
apk_join = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apk_join)


class ProviderTests(unittest.TestCase):
    def make_apk(self, root, catalog_path, fault=None):
        apk = root/'consumer.apk'
        redirects = [(name, 'workload.frag.spv') for name in fresh.FRAGMENT[1:]]
        if fault == 'alias':
            redirects[0] = (redirects[0][0], '../escape.spv')
        manifest = struct.pack('<III', 1397247302, 2, len(redirects))
        for row in redirects:
            for value in row:
                data = value.encode()
                manifest += struct.pack('<I', len(data))+data
        with zipfile.ZipFile(apk, 'w') as output:
            output.writestr('assets/shader_manifest.bin', manifest)
            for name in fresh.VERTEX+[fresh.FRAGMENT[0]]:
                data = (catalog_path.parent/'baseline'/name).read_bytes()
                if fault == 'bytes' and name == fresh.VERTEX[0]:
                    data += b'drift'
                if fault == 'bomb' and name == fresh.VERTEX[0]:
                    data = b'x'*2097153
                output.writestr('assets/'+name, b'comp'+zlib.compress(data))
            for name in fresh.VERTEX+fresh.FRAGMENT:
                # Fixture fragment baseline uses distinct bytes; aliases must agree.
                data = (catalog_path.parent/'baseline'/name).read_bytes()
                output.writestr('assets/hotload/baseline/'+name, data)
        return apk

    def test_actual_zip_alias_join_and_damaged_members(self):
        for fault in (None, 'alias', 'bytes', 'bomb'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                values = fixtures.FreshTests().fixture(root)
                apk = self.make_apk(root, values[1], fault)
                if fault:
                    with self.assertRaises(ValueError):
                        apk_join.qualify(apk, values[1])
                else:
                    result = apk_join.qualify(apk, values[1])
                    self.assertEqual(len(result['files']), 12)
                    self.assertFalse(result['receiver_enabled_proof'])
                    self.assertFalse(result['device_admission'])

    def test_explicit_baseline_compiles_then_fresh_and_exact_revert_consume_it(self):
        with tempfile.TemporaryDirectory() as directory:
            source, old, candidate, tools, headers, config = fixtures.FreshTests().fixture(Path(directory))
            output = Path(directory)/'catalog'
            with patch.object(catalog, 'run', side_effect=fixtures.FreshTests().compiler()):
                result = catalog.prepare_baseline(source, output, tools, headers, config)
            self.assertEqual([b['name'] for b in result['batches']], ['baseline'])
            self.assertEqual(result['compileProfile'], catalog.SDK_PRIMARY_COMPILE_PROFILE)
            self.assertEqual(len(result['batches'][0]['files']), 12)
            with patch.object(catalog, 'run', side_effect=AssertionError('no compile on revert')):
                reverted = fresh.prepare(None, output/'catalog.json', candidate, tools, headers, config, revert=True)
            self.assertEqual(reverted['changed'], [])
            for name in fresh.VERTEX+fresh.FRAGMENT:
                self.assertEqual((candidate/name).read_bytes(), (output/'baseline'/name).read_bytes())

    def test_compiler_failure_never_publishes_or_changes_previous_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            source, old, candidate, tools, headers, config = fixtures.FreshTests().fixture(Path(directory))
            before = old.read_bytes()
            with patch.object(catalog, 'run', side_effect=RuntimeError('compiler')):
                with self.assertRaises(RuntimeError):
                    catalog.prepare_baseline(source, candidate, tools, headers, config)
            self.assertEqual(old.read_bytes(), before)
            self.assertFalse(candidate.exists())
            self.assertEqual(list(candidate.parent.glob(candidate.name+'-prepare-*')), [])

    def test_variant_substitution_and_abi_change_refuse_publication(self):
        for fault in ('variant', 'abi'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                source, old, output, tools, headers, config = fixtures.FreshTests().fixture(Path(directory))
                if fault == 'variant':
                    value = json.loads(config.read_text())
                    value['vertex_flags'][-1] = value['vertex_flags'][0]
                    config.write_text(json.dumps(value))
                dis = fixtures.DIS.replace('Binding 0', 'Binding 4') if fault == 'abi' else fixtures.DIS
                with patch.object(catalog, 'run', side_effect=fixtures.FreshTests().compiler(dis)):
                    with self.assertRaises(ValueError):
                        catalog.prepare_baseline(source, output, tools, headers, config)
                self.assertFalse(output.exists())

    def test_old_material_sizes_are_unavailable_for_this_selected_consumer(self):
        for size in (80, 96):
            with self.subTest(size=size), tempfile.TemporaryDirectory() as directory:
                values = fixtures.FreshTests().fixture(Path(directory))
                value = json.loads(values[1].read_text())
                value['materialUniformBytes'] = size
                values[1].write_text(json.dumps(value))
                with patch.object(catalog, 'run', side_effect=AssertionError('no dispatch')):
                    with self.assertRaises(ValueError):
                        fresh.prepare(*values)


if __name__ == '__main__':
    unittest.main()
