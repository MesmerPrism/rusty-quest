"""Pure fixture tests for closed negative preparation; no subprocess/device use."""
import importlib.util
import json
import pathlib
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


NEG = load('negative_preparer', ROOT/'Prepare-ExternalHotloadNegative.py')
FIXTURE = load('kernel_test_fixture', ROOT/'Test-ExternalHotloadKernel.py')
PREP = NEG.PREP


def write_json(path, value):
    path.write_bytes((json.dumps(value, sort_keys=True, indent=2)+'\n').encode())


def parent_fixture(root):
    """Synthetic successful receipt for tests only, never production evidence."""
    parent = root/'valid'; parent.mkdir()
    code = FIXTURE.packed(FIXTURE.module())
    source = b'#version 450\r\n// synthetic unit fixture\r\nvoid main() {}\r\n'
    assembly = FIXTURE.DISASSEMBLY.encode()
    candidate = {'schema': 'rusty.quest.hotload.external_candidate.v1', 'slot': 'public-diagnostic-v1',
        'codeSha256': PREP.digest(code), 'codeBytes': len(code), 'abiSha256': PREP.ABI_SHA256,
        'entry': 'main', 'target': 'vulkan1.1', 'appBuild': 'a'*64,
        'session': '12345678-1234-1234-1234-123456789abc', 'producerGeneration': '1',
        'expectedGeneration': '2', 'expectedActiveDigest': 'b'*64, 'requestId': '3'}
    (parent/'kernel.spv').write_bytes(code); (parent/'input.comp').write_bytes(source)
    (parent/'kernel.spvasm').write_bytes(assembly); write_json(parent/'candidate.json', candidate)
    receipt = {'schema': 'rusty.quest.hotload.external_host_receipt.v1', 'sourcePath': str(parent/'input.comp'),
        'sourceSha256': PREP.digest(source), 'sourceSnapshot': {'file': 'input.comp', 'sha256': PREP.digest(source), 'bytes': len(source)},
        'codeSha256': PREP.digest(code), 'codeBytes': len(code), 'candidateSha256': PREP.digest((parent/'candidate.json').read_bytes()),
        'disassemblySha256': PREP.digest(assembly), 'abi': PREP.ABI, 'abiSha256': PREP.ABI_SHA256,
        'tools': {name: {'path': str(root/name), 'sha256': 'c'*64} for name in ('glslc', 'spirv-val', 'spirv-dis')},
        'commands': [{'tool': name, 'arguments': arguments, 'exitCode': 0, 'stdout': '', 'stderr': ''} for name, arguments in (
            ('glslc', ['--target-env=vulkan1.1', '-o', 'kernel.spv']),
            ('spirv-val', ['--target-env', 'vulkan1.1', 'kernel.spv']), ('spirv-dis', ['kernel.spv']))],
        'semanticValidation': 'spirv-val --target-env vulkan1.1 passed', 'limitations': ['Synthetic test fixture only']}
    write_json(parent/'host-receipt.json', receipt)
    return parent


def arguments(root, parent, preset='malformed-instruction'):
    return types.SimpleNamespace(candidate_dir=str(parent), host_receipt_sha=PREP.digest((parent/'host-receipt.json').read_bytes()),
        output=str(root/'negative'), preset=preset, app_build='a'*64,
        session='12345678-1234-1234-1234-123456789abc', producer_generation='1',
        expected_generation='2', expected_active_digest='d'*64, request_id='4')


class NegativePreparationTests(unittest.TestCase):
    def test_named_actual_word_mutations(self):
        original = FIXTURE.packed(FIXTURE.module())
        for preset in NEG.PRESETS:
            with self.subTest(preset=preset):
                changed, mutation, rejection = NEG.mutate(original, preset)
                self.assertEqual(len(changed), len(original))
                before = [original[i:i+4] for i in range(0, len(original), 4)]
                after = [changed[i:i+4] for i in range(0, len(changed), 4)]
                self.assertEqual([i for i, pair in enumerate(zip(before, after)) if pair[0] != pair[1]], [mutation['wordOffset']])
                self.assertEqual(int.from_bytes(before[mutation['wordOffset']], 'little'), mutation['before'])
                self.assertEqual(int.from_bytes(after[mutation['wordOffset']], 'little'), mutation['after'])
                if preset == 'malformed-instruction':
                    self.assertEqual(mutation['wordOffset'], 5)
                    self.assertEqual(mutation['after'], mutation['before'] & 65535)
                else:
                    self.assertEqual((mutation['before'], mutation['after']), (0, 1))
                    self.assertEqual(int.from_bytes(before[mutation['wordOffset']-1], 'little'), 33)
                self.assertTrue(rejection)
                with self.assertRaises(ValueError):
                    PREP.binary_contract(changed)

    def test_emit_receipt_manifest_parent_evidence_and_no_overwrite(self):
        for preset in NEG.PRESETS:
            with self.subTest(preset=preset), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory); parent = parent_fixture(root)
                original = {p.name: p.read_bytes() for p in parent.iterdir()}
                a = arguments(root, parent, preset)
                with patch.object(PREP.subprocess, 'run', side_effect=AssertionError('no subprocess permitted')):
                    receipt = NEG.prepare(a)
                output = root/'negative'; candidate = json.loads((output/'candidate.json').read_bytes())
                self.assertEqual(set(candidate), NEG.CANDIDATE_FIELDS)
                self.assertEqual(candidate['requestId'], '4')
                self.assertEqual(candidate['expectedActiveDigest'], 'd'*64)
                self.assertEqual(receipt['schema'], 'rusty.quest.hotload.external_negative_receipt.v1')
                self.assertEqual(receipt['preset'], preset)
                self.assertIs(receipt['intentionalInvalidCandidate'], True)
                self.assertIs(receipt['stageOnly'], True)
                self.assertEqual(receipt['expectedOutcome'], 'native_rejection')
                self.assertEqual(receipt['semanticValidation'], 'not_run_intentional_rejection')
                self.assertEqual(receipt['commands'], [])
                self.assertEqual(receipt['codeSha256'], PREP.digest((output/'kernel.spv').read_bytes()))
                self.assertEqual(candidate['codeSha256'], receipt['codeSha256'])
                self.assertNotEqual(receipt['codeSha256'], receipt['parentCodeSha256'])
                self.assertEqual(receipt['candidateSha256'], PREP.digest((output/'candidate.json').read_bytes()))
                for file_key, hash_key in [('parentHostReceiptFile', 'parentHostReceiptSha256'), ('parentCodeFile', 'parentCodeSha256'),
                                           ('parentCandidateFile', 'parentCandidateSha256'), ('parentDisassemblyFile', 'parentDisassemblySha256')]:
                    self.assertEqual(PREP.digest((output/receipt[file_key]).read_bytes()), receipt[hash_key])
                self.assertEqual((output/'input.comp').read_bytes(), original['input.comp'])
                self.assertNotIn(str(root), (output/'candidate.json').read_text())
                self.assertEqual({p.name: p.read_bytes() for p in parent.iterdir()}, original)
                with self.assertRaises(ValueError):
                    NEG.prepare(a)

    def test_parent_file_tampering_fails_closed(self):
        for filename in ('host-receipt.json', 'candidate.json', 'kernel.spv', 'kernel.spvasm', 'input.comp'):
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory); parent = parent_fixture(root); a = arguments(root, parent)
                (parent/filename).write_bytes((parent/filename).read_bytes()+b' ')
                with self.assertRaises(ValueError):
                    NEG.prepare(a)
                self.assertFalse((root/'negative').exists())

    def test_parent_receipt_must_attest_exact_successful_tool_chain(self):
        for change in ('semantic', 'schema', 'abi', 'tool', 'exit', 'target', 'source'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory); parent = parent_fixture(root)
                receipt = json.loads((parent/'host-receipt.json').read_bytes())
                if change == 'semantic': receipt['semanticValidation'] = 'not_run_intentional_rejection'
                elif change == 'schema': receipt['schema'] = 'rusty.quest.hotload.external_negative_receipt.v1'
                elif change == 'abi': receipt['abiSha256'] = '0'*64
                elif change == 'tool': del receipt['tools']['spirv-val']
                elif change == 'exit': receipt['commands'][1]['exitCode'] = 1
                elif change == 'target': receipt['commands'][1]['arguments'][1] = 'vulkan1.0'
                elif change == 'source': receipt['sourceSnapshot']['file'] = '../input.comp'
                write_json(parent/'host-receipt.json', receipt)
                with self.assertRaises(ValueError):
                    NEG.prepare(arguments(root, parent))
                self.assertFalse((root/'negative').exists())

    def test_closed_presets_app_build_and_metadata(self):
        for name, value in [('preset', 'generic-corruption'), ('app_build', 'f'*64), ('request_id', '04'),
                            ('request_id', '9223372036854775808'), ('host_receipt_sha', '0'*64)]:
            with self.subTest(name=name, value=value), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory); parent = parent_fixture(root); a = arguments(root, parent)
                setattr(a, name, value)
                with self.assertRaises(ValueError):
                    NEG.prepare(a)
                self.assertFalse((root/'negative').exists())


if __name__ == '__main__':
    unittest.main()
