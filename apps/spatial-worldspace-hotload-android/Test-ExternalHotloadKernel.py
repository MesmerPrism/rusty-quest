"""Pure host tests; no compiler, APK, device, or process execution."""
import importlib.util
import json
import pathlib
import re
import struct
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('external_preparer', ROOT/'Prepare-ExternalHotloadKernel.py')
PREP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREP)


def instruction(opcode, *operands):
    return [(len(operands)+1) << 16 | opcode, *operands]


def module():
    """Real SPIR-V word stream with independent arithmetic and branch blocks.

    Deliberately hand-authored: these tests exercise interface parsing, and do
    not claim a spirv-val pass or substitute for live tool preparation.
    """
    instructions = [
        (17, 1), (14, 0, 1), (15, 5, 15, 0x6e69616d, 0), (16, 15, 17, 2, 1, 1),
        (71, 7, 6, 16), (71, 8, 2), (72, 8, 0, 35, 0), (72, 8, 0, 25),
        (71, 10, 34, 0), (71, 10, 33, 0), (71, 11, 2),
        (72, 11, 0, 35, 0), (72, 11, 1, 35, 16),
        (19, 1), (20, 2), (21, 3, 32, 0), (22, 4, 32), (23, 5, 4, 4), (23, 6, 3, 4),
        (29, 7, 5), (30, 8, 7), (32, 9, 12, 8), (59, 9, 10, 12),
        (30, 11, 6, 5), (32, 12, 9, 11), (59, 12, 13, 9), (33, 14, 1),
        (43, 3, 17, 1), (43, 3, 18, 2), (41, 2, 19),
        (54, 1, 15, 0, 14), (248, 16), (247, 23, 0), (250, 19, 21, 22),
        (248, 21), (128, 3, 20, 17, 18), (249, 23), (248, 22), (249, 23),
        (248, 23), (253,), (56,),
    ]
    return [0x07230203, 0x00010300, 0, 64, 0] + [word for ins in instructions for word in instruction(*ins)]


def packed(words):
    return struct.pack('<'+'I'*len(words), *words)


def replace(words, old, new):
    old, new = instruction(*old), instruction(*new)
    at = 5
    while at < len(words):
        count = words[at] >> 16
        if words[at:at+count] == old:
            return words[:at]+new+words[at+count:]
        at += count
    raise AssertionError('fixture instruction missing')


DISASSEMBLY = '''; SPIR-V
OpCapability Shader
OpMemoryModel Logical GLSL450
OpEntryPoint GLCompute %main "main"
OpExecutionMode %main LocalSize 2 1 1
OpDecorate %array ArrayStride 16
OpDecorate %output_type Block
OpMemberDecorate %output_type 0 Offset 0
OpMemberDecorate %output_type 0 NonReadable
OpDecorate %output DescriptorSet 0
OpDecorate %output Binding 0
OpDecorate %params_type Block
OpMemberDecorate %params_type 0 Offset 0
OpMemberDecorate %params_type 1 Offset 16
%void = OpTypeVoid
%u32 = OpTypeInt 32 0
%f32 = OpTypeFloat 32
%vec4 = OpTypeVector %f32 4
%uvec4 = OpTypeVector %u32 4
%array = OpTypeRuntimeArray %vec4
%output_type = OpTypeStruct %array
%output_ptr = OpTypePointer StorageBuffer %output_type
%output = OpVariable %output_ptr StorageBuffer
%params_type = OpTypeStruct %uvec4 %vec4
%params_ptr = OpTypePointer PushConstant %params_type
%params = OpVariable %params_ptr PushConstant
%function_type = OpTypeFunction %void
%main = OpFunction %void None %function_type
%label = OpLabel
OpReturn
OpFunctionEnd
'''


class ExternalContractTests(unittest.TestCase):
    def check(self, words):
        return PREP.binary_contract(packed(words))

    def test_fixed_interface_and_changed_arithmetic(self):
        self.assertEqual(self.check(module()), PREP.ABI)
        self.assertEqual(self.check(replace(module(), (128, 3, 20, 17, 18), (132, 3, 20, 17, 18))), PREP.ABI)

    def test_runtime_and_host_canonical_abi_match(self):
        header = (ROOT/'app/src/main/cpp/hotload_spirv_contract.h').read_text()
        declared = re.search(r'R"abi\((.*?)\)abi"', header).group(1)
        declared_hash = re.search(r'externalHotloadAbiSha256 = "([0-9a-f]{64})"', header).group(1)
        self.assertEqual(declared, PREP.ABI_JSON)
        self.assertEqual(json.loads(declared), PREP.ABI)
        self.assertEqual(declared_hash, PREP.ABI_SHA256)

    def test_expected_glslc_import_and_compute_builtin_decorations(self):
        # Source-only expectation fixture, not a claim about an executed compiler.
        # GLSL sin/cos use GLSL.std.450; ordinary glslc also decorates the global
        # invocation input and a constant workgroup size.
        original = module(); words = original[:5]; at = 5
        while at < len(original):
            count, opcode = original[at] >> 16, original[at] & 65535
            ins = original[at:at+count]
            if opcode == 15:
                ins = instruction(15, 5, 15, 0x6e69616d, 0, 26)
            if opcode == 54:
                words += instruction(23, 24, 3, 3) + instruction(32, 25, 1, 24) + instruction(59, 25, 26, 1)
                words += instruction(44, 24, 27, 18, 17, 17) + instruction(43, 4, 30, 0)
            words += ins
            if opcode == 17:
                words += instruction(11, 28, 0x4c534c47, 0x6474732e, 0x3035342e, 0)
            if opcode == 16:
                words += instruction(71, 26, 11, 28) + instruction(71, 27, 11, 25)
            if opcode == 248 and original[at+1] == 16:
                words += instruction(12, 4, 29, 28, 13, 30)
            at += count
        self.assertEqual(self.check(words), PREP.ABI)
        with self.assertRaises(ValueError):
            self.check(replace(words, (71, 26, 11, 28), (71, 26, 11, 0)))

    def test_binary_negative_fixtures(self):
        cases = [
            ((17, 1), (17, 2)),
            ((14, 0, 1), (14, 1, 1)),
            ((15, 5, 15, 0x6e69616d, 0), (15, 4, 15, 0x6e69616d, 0)),
            ((15, 5, 15, 0x6e69616d, 0), (15, 5, 15, 0x6e696162, 0)),
            ((15, 5, 15, 0x6e69616d, 0), (15, 5, 15, 0x6e69616d)),
            ((16, 15, 17, 2, 1, 1), (16, 15, 17, 1, 1, 1)),
            ((16, 15, 17, 2, 1, 1), (16, 15, 17, 2, 2, 1)),
            ((16, 15, 17, 2, 1, 1), (16, 16, 17, 2, 1, 1)),
            ((71, 7, 6, 16), (71, 7, 6, 32)),
            ((71, 10, 34, 0), (71, 10, 34, 1)),
            ((71, 10, 33, 0), (71, 10, 33, 1)),
            ((72, 8, 0, 35, 0), (72, 8, 0, 35, 16)),
            ((72, 8, 0, 25), (72, 8, 0, 24)),
            ((72, 11, 1, 35, 16), (72, 11, 1, 35, 32)),
            ((23, 5, 4, 4), (23, 5, 4, 3)),
            ((22, 4, 32), (22, 4, 64)),
            ((30, 11, 6, 5), (30, 11, 5, 6)),
            ((59, 9, 10, 12), (59, 9, 10, 2)),
            ((59, 12, 13, 9), (59, 12, 13, 4)),
            ((32, 9, 12, 8), (32, 9, 12, 63)),
            ((29, 7, 5), (29, 7, 64)),
            ((71, 8, 2), (71, 8, 3)),
        ]
        for old, new in cases:
            with self.subTest(old=old, new=new), self.assertRaises(ValueError):
                self.check(replace(module(), old, new))

    def test_extra_resource_and_duplicate_modes(self):
        for extra in [(59, 9, 24, 12), (59, 12, 24, 9), (59, 9, 24, 0),
                      (16, 15, 17, 2, 1, 1), (17, 1), (71, 24, 34, 0),
                      (71, 11, 2), (71, 24, 2), (72, 11, 2, 35, 32),
                      (73, 24), (48, 3, 24), (331, 15, 38, 17, 17, 17)]:
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                self.check(module()[:5]+instruction(*extra)+module()[5:])

    def test_headers_and_instruction_framing(self):
        for index, value in [(0, 0), (1, 0x00010400), (3, 0), (3, 65537), (4, 1), (5, 0), (5, 0xffff0011)]:
            words = module(); words[index] = value
            with self.subTest(index=index, value=value), self.assertRaises(ValueError):
                self.check(words)
        for data in [b'', packed(module())[:-1], packed(module())[:-4], b'\0'*65540]:
            with self.assertRaises(ValueError):
                PREP.binary_contract(data)

    def test_metadata_rejects_ambiguous_or_overflow_numbers(self):
        for value in ['0', '-1', '+1', '01', '1.0', '9223372036854775808', '18446744073709551616', '']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                PREP.decimal(value)
        self.assertEqual(PREP.decimal('9223372036854775807'), '9223372036854775807')

    def test_disassembly_reflection_uses_types_not_names(self):
        self.assertEqual(PREP.reflect(DISASSEMBLY), PREP.ABI)
        renamed = DISASSEMBLY.replace('%output_type', '%anything').replace('%main', '%f_99')
        self.assertEqual(PREP.reflect(renamed), PREP.ABI)
        for old, new in [('StorageBuffer', 'Uniform'), ('LocalSize 2 1 1', 'LocalSize 4 1 1'),
                         ('%uvec4 %vec4', '%vec4 %uvec4'), ('Offset 16', 'Offset 32'),
                         ('"main"', '"other"'), ('NonReadable', 'NonWritable')]:
            with self.subTest(old=old), self.assertRaises(ValueError):
                PREP.reflect(DISASSEMBLY.replace(old, new))

    def test_source_dependencies_are_rejected(self):
        PREP.check_source(b'#version 450\nvoid main() {}')
        for data in [b'', b'x'*262145, b'#include "unreceipted.glsl"',
                     b'/*leading comment*/ #include "hidden.glsl"',
                     b'#inc\\\nlude "continued.glsl"']:
            with self.subTest(data=data[:60]), self.assertRaises(ValueError):
                PREP.check_source(data)

    def test_preparation_manifest_receipt_and_no_overwrite_with_mock_tools(self):
        # Mock subprocesses: this checks orchestration only, and makes no compiler
        # or semantic-validation claim. No external executable is run.
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root/'source.comp'; source.write_bytes(b'#version 450\r\n// exact CRLF snapshot\r\nvoid main() {}\r\n')
            for name in ('glslc', 'spirv-val', 'spirv-dis'):
                (root/name).write_bytes(('inert test tool '+name).encode())
            a = types.SimpleNamespace(source=str(source), glslc=str(root/'glslc'),
                spirv_val=str(root/'spirv-val'), spirv_dis=str(root/'spirv-dis'), output=str(root/'candidate'),
                app_build='a'*64, session='12345678-1234-1234-1234-123456789abc',
                producer_generation='1', expected_generation='2', expected_active_digest='b'*64, request_id='3')
            def fake_run(command, **kwargs):
                if pathlib.Path(command[0]).name == 'glslc':
                    pathlib.Path(command[command.index('-o')+1]).write_bytes(packed(module()))
                return types.SimpleNamespace(returncode=0, stdout=DISASSEMBLY if pathlib.Path(command[0]).name == 'spirv-dis' else '', stderr='')
            with patch.object(PREP.subprocess, 'run', side_effect=fake_run) as mocked:
                candidate = PREP.prepare(a)
                self.assertEqual(mocked.call_count, 3)
                calls = [call.args[0] for call in mocked.call_args_list]
                self.assertIn('--target-env=vulkan1.1', calls[0])
                self.assertEqual(calls[1][1:3], ['--target-env', 'vulkan1.1'])
                self.assertEqual(set(candidate), {'schema', 'slot', 'codeSha256', 'codeBytes', 'abiSha256',
                    'appBuild', 'session', 'producerGeneration', 'expectedGeneration', 'expectedActiveDigest', 'requestId', 'entry', 'target'})
                out = root/'candidate'
                self.assertEqual(set(p.name for p in out.iterdir()), {'kernel.spv', 'candidate.json', 'kernel.spvasm', 'input.comp', 'host-receipt.json'})
                receipt = json.loads((out/'host-receipt.json').read_text())
                self.assertEqual((out/'input.comp').read_bytes(), source.read_bytes())
                self.assertEqual(receipt['sourceSnapshot'], {'file': 'input.comp', 'sha256': PREP.digest(source.read_bytes()), 'bytes': len(source.read_bytes())})
                self.assertEqual(receipt['sourceSha256'], receipt['sourceSnapshot']['sha256'])
                self.assertEqual(receipt['codeSha256'], PREP.digest((out/'kernel.spv').read_bytes()))
                self.assertEqual(receipt['candidateSha256'], PREP.digest((out/'candidate.json').read_bytes()))
                self.assertEqual(receipt['disassemblySha256'], PREP.digest((out/'kernel.spvasm').read_bytes()))
                self.assertNotIn(str(root), (out/'candidate.json').read_text())
                with self.assertRaises(ValueError):
                    PREP.prepare(a)
                self.assertEqual(mocked.call_count, 3)

    def test_failed_semantic_tool_publishes_no_candidate_with_mock_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root/'source.comp'; source.write_text('#version 450\nvoid main() {}')
            tool = root/'tool'; tool.write_bytes(b'inert test tool')
            a = types.SimpleNamespace(source=str(source), glslc=str(tool), spirv_val=str(tool), spirv_dis=str(tool),
                output=str(root/'candidate'), app_build='a'*64, session='12345678-1234-1234-1234-123456789abc',
                producer_generation='1', expected_generation='1', expected_active_digest='b'*64, request_id='1')
            def fake_run(command, **kwargs):
                if '-o' in command:
                    pathlib.Path(command[command.index('-o')+1]).write_bytes(packed(module()))
                    return types.SimpleNamespace(returncode=0, stdout='', stderr='')
                return types.SimpleNamespace(returncode=1, stdout='', stderr='mock semantic failure')
            with patch.object(PREP.subprocess, 'run', side_effect=fake_run):
                with self.assertRaises(ValueError):
                    PREP.prepare(a)
            self.assertFalse((root/'candidate').exists())
            self.assertFalse(list(root.glob('candidate-prepare-*')))


if __name__ == '__main__':
    if '--print-abi' in sys.argv:
        print(PREP.ABI_SHA256)
        print(PREP.ABI_JSON)
    else:
        unittest.main()
