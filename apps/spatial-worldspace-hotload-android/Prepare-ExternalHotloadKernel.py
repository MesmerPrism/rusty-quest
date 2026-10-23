"""Prepare a fresh, host-validated public diagnostic compute candidate.

This tool has no device transport. kernel.spv and candidate.json are the only
device inputs; input.comp, host-receipt.json and kernel.spvasm remain host evidence. Explicit
tool paths are hashed before and after execution, binding this preparation to
those exact tools. A receipt is evidence, not a signature or runtime authority.
The fixed ABI does not prove arbitrary code terminates or stays within 128 bytes.
Only trusted, reviewed diagnostic source is suitable for this debug-only route.
"""
import argparse
import hashlib
import json
import pathlib
import re
import shlex
import shutil
import struct
import subprocess
import tempfile


ABI = {
    'schema': 'rusty.quest.hotload.external_abi.v1',
    'spirv': '1.3', 'target': 'vulkan1.1', 'entry': 'main',
    'executionModel': 'GLCompute', 'memoryModel': 'Logical/GLSL450',
    'localSize': [2, 1, 1],
    'output': {'allocationBytes': 128, 'set': 0, 'binding': 0,
               'storage': 'StorageBuffer', 'type': 'runtime-array<vec4<f32>>',
               'stride': 16, 'memberOffset': 0, 'writeOnly': True},
    'pushConstants': {'bytes': 32, 'members': [
        {'offset': 0, 'type': 'vec4<u32>'}, {'offset': 16, 'type': 'vec4<f32>'}]},
}
ABI_JSON = json.dumps(ABI, sort_keys=True, separators=(',', ':'))


def digest(data):
    return hashlib.sha256(data).hexdigest()


ABI_SHA256 = digest(ABI_JSON.encode('utf-8'))


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def reflect(disassembly):
    """Reflect actual spirv-dis graph; names and numeric IDs are immaterial."""
    definitions, decorations, members, operations = {}, {}, {}, []
    globals_, function_depth = [], 0
    for line in disassembly.splitlines():
        tokens = shlex.split(line, comments=True) if not line.lstrip().startswith(';') else []
        if not tokens:
            continue
        if len(tokens) > 2 and tokens[1] == '=':
            result, op = tokens[0], tokens[2:]
            require(result not in definitions, 'duplicate result ID')
            definitions[result] = op
        else:
            result, op = None, tokens
        operations.append(op)
        if op[0] == 'OpFunction':
            require(function_depth == 0, 'nested function')
            function_depth = 1
        elif op[0] == 'OpFunctionEnd':
            require(function_depth == 1, 'unmatched function end')
            function_depth = 0
        elif op[0] == 'OpVariable':
            require(len(op) in (3, 4), 'variable shape')
            if function_depth:
                require(op[2] == 'Function', 'non-local function variable')
            else:
                globals_.append((result, op))
        elif op[0] == 'OpDecorate':
            require(len(op) >= 3, 'decoration shape')
            key = (op[1], op[2])
            require(key not in decorations, 'duplicate decoration')
            decorations[key] = op[3:]
        elif op[0] == 'OpMemberDecorate':
            require(len(op) >= 4, 'member decoration shape')
            key = (op[1], op[2], op[3])
            require(key not in members, 'duplicate member decoration')
            members[key] = op[4:]
    require(function_depth == 0, 'unterminated function')
    def ops(name):
        return [op[1:] for op in operations if op[0] == name]
    require(ops('OpCapability') == [['Shader']], 'only Shader capability is allowed')
    require(ops('OpMemoryModel') == [['Logical', 'GLSL450']], 'memory model')
    require(ops('OpExtInstImport') in ([], [['GLSL.std.450']]), 'extended instruction import')
    forbidden = {'OpExtension', 'OpDecorationGroup', 'OpGroupDecorate', 'OpGroupMemberDecorate',
                 'OpExecutionModeId', 'OpTypeImage', 'OpTypeSampler', 'OpTypeSampledImage', 'OpTypeOpaque'}
    require(not any(op[0] in forbidden or op[0].startswith('OpSpecConstant') for op in operations), 'unsupported feature')
    entries = ops('OpEntryPoint')
    require(len(entries) == 1 and len(entries[0]) >= 3 and entries[0][0] == 'GLCompute' and entries[0][2] == 'main', 'entry point')
    entry = entries[0][1]
    require(definitions.get(entry, [None])[0] == 'OpFunction', 'entry function missing')
    require(ops('OpExecutionMode') == [[entry, 'LocalSize', '2', '1', '1']], 'execution mode')
    interfaces = entries[0][3:]
    require(len(interfaces) == len(set(interfaces)), 'duplicate interface')
    allowed_flags = {'RelaxedPrecision', 'Block', 'Restrict', 'Aliased', 'Coherent', 'NonWritable', 'NonReadable', 'NoContraction'}
    allowed_values = {'ArrayStride', 'BuiltIn', 'Binding', 'DescriptorSet', 'Offset'}
    for key, values in list(decorations.items()) + list(members.items()):
        require((key[-1] in allowed_flags and not values) or (key[-1] in allowed_values and len(values) == 1), 'unsupported decoration')
    for op in operations:
        if op[0] == 'OpTypeInt':
            require(op[1:] in (['32', '0'], ['32', '1']), 'integer type')
        elif op[0] == 'OpTypeFloat':
            require(op[1:] == ['32'], 'float type')
    def definition(name, opcode):
        value = definitions.get(name, [])
        require(value and value[0] == opcode, 'expected ' + opcode)
        return value[1:]
    def vector(name, integer=False, width='4'):
        v = definition(name, 'OpTypeVector')
        require(len(v) == 2 and v[1] == width, 'vector width')
        require(definitions.get(v[0]) == (['OpTypeInt', '32', '0'] if integer else ['OpTypeFloat', '32']), 'vector scalar')
    def dec(name, kind, value=()):
        require(decorations.get((name, kind)) == list(value), 'missing/wrong ' + kind)
    def member(name, index, kind, value=()):
        require(members.get((name, str(index), kind)) == list(value), 'missing/wrong member ' + kind)
    outputs, pushes, inputs = [], [], []
    for name, op in globals_:
        storage = op[2]
        require(storage in {'StorageBuffer', 'PushConstant', 'Input', 'Private'}, 'extra resource global')
        require(storage == 'Private' or len(op) == 3, 'resource initializer')
        pointer = definition(op[1], 'OpTypePointer')
        require(len(pointer) == 2 and pointer[0] == storage, 'pointer storage')
        type_id = pointer[1]
        if storage == 'StorageBuffer':
            dec(name, 'DescriptorSet', ['0']); dec(name, 'Binding', ['0'])
            dec(type_id, 'Block'); member(type_id, 0, 'Offset', ['0']); member(type_id, 0, 'NonReadable')
            fields = definition(type_id, 'OpTypeStruct')
            require(len(fields) == 1, 'output member count')
            array = definition(fields[0], 'OpTypeRuntimeArray')
            require(len(array) == 1, 'runtime array type')
            vector(array[0]); dec(fields[0], 'ArrayStride', ['16'])
            outputs.append((name, type_id, fields[0]))
        elif storage == 'PushConstant':
            dec(type_id, 'Block'); member(type_id, 0, 'Offset', ['0']); member(type_id, 1, 'Offset', ['16'])
            fields = definition(type_id, 'OpTypeStruct')
            require(len(fields) == 2, 'push member count')
            vector(fields[0], True); vector(fields[1])
            pushes.append(type_id)
        elif storage == 'Input':
            builtin = decorations.get((name, 'BuiltIn'))
            require(builtin in ([x] for x in ('NumWorkgroups', 'WorkgroupId', 'LocalInvocationId', 'GlobalInvocationId', 'LocalInvocationIndex')), 'compute input builtin')
            if builtin == ['LocalInvocationIndex']:
                require(definitions.get(type_id) == ['OpTypeInt', '32', '0'], 'input scalar')
            else:
                vector(type_id, True, '3')
            inputs.append(name)
    require(len(outputs) == 1 and len(pushes) == 1, 'exactly one output and push block required')
    require(set(inputs) == set(interfaces), 'entry interfaces differ from inputs')
    output, output_struct, array = outputs[0]
    for (target, kind), value in decorations.items():
        if kind in {'DescriptorSet', 'Binding'}:
            require(target == output, 'extra descriptor decoration')
        if kind == 'Block':
            require(target in {output_struct, pushes[0]}, 'extra block decoration')
        if kind == 'ArrayStride':
            require(target == array, 'extra array layout')
        require(kind != 'Offset', 'offset must be a member decoration')
    for (target, index, kind), value in members.items():
        require(kind not in {'DescriptorSet', 'Binding', 'Block', 'ArrayStride'}, 'misplaced layout')
        if kind == 'Offset':
            require((target == output_struct and index == '0') or (target == pushes[0] and index in {'0', '1'}), 'extra member offset')
    return ABI


# Decode interface instructions from real binary words for independent framing
# and contract checks. spirv-dis reflection is still required by prepare().
OP_NAMES = {10: 'OpExtension', 11: 'OpExtInstImport', 14: 'OpMemoryModel', 15: 'OpEntryPoint',
            16: 'OpExecutionMode', 17: 'OpCapability', 19: 'OpTypeVoid', 20: 'OpTypeBool',
            21: 'OpTypeInt', 22: 'OpTypeFloat', 23: 'OpTypeVector', 24: 'OpTypeMatrix',
            25: 'OpTypeImage', 26: 'OpTypeSampler', 27: 'OpTypeSampledImage', 28: 'OpTypeArray',
            29: 'OpTypeRuntimeArray', 30: 'OpTypeStruct', 31: 'OpTypeOpaque', 32: 'OpTypePointer',
            33: 'OpTypeFunction', 54: 'OpFunction', 56: 'OpFunctionEnd', 59: 'OpVariable',
            71: 'OpDecorate', 72: 'OpMemberDecorate', 73: 'OpDecorationGroup',
            74: 'OpGroupDecorate', 75: 'OpGroupMemberDecorate', 331: 'OpExecutionModeId'}
STORAGE = {1: 'Input', 6: 'Private', 7: 'Function', 9: 'PushConstant', 12: 'StorageBuffer'}
DECORATIONS = {0: 'RelaxedPrecision', 2: 'Block', 6: 'ArrayStride', 11: 'BuiltIn',
               19: 'Restrict', 20: 'Aliased', 23: 'Coherent', 24: 'NonWritable',
               25: 'NonReadable', 33: 'Binding', 34: 'DescriptorSet', 35: 'Offset', 42: 'NoContraction'}
BUILTINS = {24: 'NumWorkgroups', 25: 'WorkgroupSize', 26: 'WorkgroupId',
            27: 'LocalInvocationId', 28: 'GlobalInvocationId', 29: 'LocalInvocationIndex'}


def binary_contract(data):
    require(20 <= len(data) <= 65536 and len(data) % 4 == 0, 'SPIR-V size')
    words = struct.unpack('<' + 'I' * (len(data)//4), data)
    require(words[0] == 0x07230203 and words[1] == 0x00010300 and words[4] == 0, 'SPIR-V header')
    require(2 <= words[3] <= 65536, 'SPIR-V ID bound')
    def ident(v):
        require(0 < v < words[3], 'ID outside bound')
        return '%' + str(v)
    def literal(values):
        b = struct.pack('<' + 'I'*len(values), *values)
        require(b'\0' in b, 'unterminated SPIR-V string')
        end = b.index(0); used = end//4 + 1
        require(not any(b[end:used*4]), 'nonzero string padding')
        return json.dumps(b[:end].decode('ascii')), used
    lines, at = [], 5
    while at < len(words):
        count, code = words[at] >> 16, words[at] & 65535
        require(count > 0 and at + count <= len(words), 'instruction framing')
        p = list(words[at+1:at+count]); at += count
        require(not 48 <= code <= 52, 'specialization constants unsupported')
        op, result, args = OP_NAMES.get(code), None, []
        if op is None:
            continue  # Full opcode/semantic validation belongs to spirv-val.
        if code in (10, 73, 74, 75, 331) or 25 <= code <= 27 or code == 31:
            raise ValueError('unsupported feature')
        if code == 17:
            require(len(p) == 1, 'capability shape'); args = ['Shader' if p[0] == 1 else str(p[0])]
        elif code == 14:
            require(len(p) == 2, 'memory shape'); args = ['Logical' if p[0] == 0 else str(p[0]), 'GLSL450' if p[1] == 1 else str(p[1])]
        elif code == 15:
            require(len(p) >= 3, 'entry shape'); name, used = literal(p[2:])
            args = ['GLCompute' if p[0] == 5 else str(p[0]), ident(p[1]), name] + [ident(v) for v in p[2+used:]]
        elif code == 16:
            require(len(p) == 5, 'mode shape'); args = [ident(p[0]), 'LocalSize' if p[1] == 17 else str(p[1])] + list(map(str, p[2:]))
        elif code == 11:
            require(len(p) >= 2, 'import shape'); result = ident(p[0]); name, used = literal(p[1:])
            require(used == len(p)-1, 'import trailing operands'); args = [name]
        elif code in (71, 72):
            fixed = 2 if code == 71 else 3
            require(len(p) >= fixed, 'decoration shape')
            kind = p[fixed-1]
            args = [ident(p[0])] + ([str(p[1])] if code == 72 else []) + [DECORATIONS.get(kind, str(kind))]
            args += [BUILTINS.get(v, str(v)) if kind == 11 else str(v) for v in p[fixed:]]
        elif 19 <= code <= 33:
            require(len(p) >= 1, 'type shape'); result = ident(p[0])
            exact = {19:1, 20:1, 21:3, 22:2, 23:3, 24:3, 28:3, 29:2, 32:3}
            require(code not in exact or len(p) == exact[code], 'type operands')
            if code in (21, 22): args = list(map(str, p[1:]))
            elif code in (23, 24): args = [ident(p[1]), str(p[2])]
            elif code == 32: args = [STORAGE.get(p[1], str(p[1])), ident(p[2])]
            else: args = [ident(v) for v in p[1:]]
        elif code == 59:
            require(len(p) in (3, 4), 'variable shape'); result = ident(p[1])
            args = [ident(p[0]), STORAGE.get(p[2], str(p[2]))] + [ident(v) for v in p[3:]]
        elif code == 54:
            require(len(p) == 4, 'function shape'); result = ident(p[1]); args = [ident(p[0]), str(p[2]), ident(p[3])]
        elif code == 56:
            require(not p, 'function end shape')
        lines.append(((result + ' = ') if result else '') + op + ' ' + ' '.join(args))
    return reflect('\n'.join(lines))


def decimal(value):
    require(re.fullmatch(r'[1-9][0-9]{0,18}', value) is not None and int(value) <= 2**63-1, 'metadata must be positive canonical signed-Long decimal')
    return value


def check_source(source_bytes):
    require(0 < len(source_bytes) <= 262144, 'source must contain 1..262144 bytes')
    # Remove preprocessor line splicing before comments, so no unreceipted
    # dependency can hide behind a comment or continued include directive.
    plain = re.sub(rb'\\\r?\n', b'', source_bytes)
    plain = re.sub(rb'/\*.*?\*/|//[^\r\n]*', b' ', plain, flags=re.DOTALL)
    require(not re.search(rb'^\s*#\s*include\b', plain, re.MULTILINE), 'source must be self-contained; includes are not receipted')


def prepare(a):
    source = pathlib.Path(a.source).resolve(strict=True)
    tools = {key: pathlib.Path(getattr(a, key.replace('-', '_'))).resolve(strict=True)
             for key in ('glslc', 'spirv-val', 'spirv-dis')}
    for path in (source, *tools.values()):
        require(path.is_file(), 'source/tool must be a file')
    source_bytes = source.read_bytes()
    check_source(source_bytes)
    require(re.fullmatch(r'[0-9a-f]{64}', a.app_build) is not None, 'app build SHA-256 format')
    require(re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', a.session) is not None, 'session UUID format')
    metadata = {'appBuild': a.app_build, 'session': a.session}
    metadata.update({key: decimal(getattr(a, arg)) for key, arg in
                     (('producerGeneration', 'producer_generation'), ('expectedGeneration', 'expected_generation'), ('requestId', 'request_id'))})
    require(re.fullmatch(r'[0-9a-f]{64}', a.expected_active_digest) is not None, 'expected active SHA-256 format')
    metadata['expectedActiveDigest'] = a.expected_active_digest
    output = pathlib.Path(a.output).resolve()
    require(not output.exists(), 'output already exists; choose a fresh immutable candidate directory')
    output.parent.mkdir(parents=True, exist_ok=True)
    tool_hashes = {key: digest(path.read_bytes()) for key, path in tools.items()}
    temp = pathlib.Path(tempfile.mkdtemp(prefix=output.name+'-prepare-', dir=output.parent))
    commands = []
    def run(key, args):
        command = [str(tools[key]), *map(str, args)]
        result = subprocess.run(command, capture_output=True, text=True, timeout=120)
        commands.append({'tool': key, 'arguments': list(map(str, args)), 'exitCode': result.returncode,
                         'stdout': result.stdout, 'stderr': result.stderr})
        require(result.returncode == 0, key + ' failed: ' + result.stderr)
        return result.stdout
    try:
        input_path = temp/'input.comp'; input_path.write_bytes(source_bytes)
        kernel = temp/'kernel.spv'
        run('glslc', ['--target-env=vulkan1.1', '-fshader-stage=compute', '-fentry-point=main', input_path, '-o', kernel])
        run('spirv-val', ['--target-env', 'vulkan1.1', kernel])
        disassembly = run('spirv-dis', [kernel])
        data = kernel.read_bytes()
        require(reflect(disassembly) == binary_contract(data) == ABI, 'reflected ABI mismatch')
        require(all(digest(path.read_bytes()) == tool_hashes[key] for key, path in tools.items()), 'tool changed during preparation')
        require(input_path.read_bytes() == source_bytes, 'source snapshot changed during preparation')
        candidate = {'schema': 'rusty.quest.hotload.external_candidate.v1', 'slot': 'public-diagnostic-v1',
                     'codeSha256': digest(data), 'codeBytes': len(data), 'abiSha256': ABI_SHA256,
                     'entry': 'main', 'target': 'vulkan1.1', **metadata}
        candidate_bytes = (json.dumps(candidate, sort_keys=True, indent=2)+'\n').encode('utf-8')
        (temp/'candidate.json').write_bytes(candidate_bytes)
        (temp/'kernel.spvasm').write_bytes(disassembly.encode('utf-8'))
        receipt = {'schema': 'rusty.quest.hotload.external_host_receipt.v1', 'sourcePath': str(source),
                   'sourceSha256': digest(source_bytes),
                   'sourceSnapshot': {'file': 'input.comp', 'sha256': digest(source_bytes), 'bytes': len(source_bytes)},
                   'tools': {key: {'path': str(path), 'sha256': tool_hashes[key]} for key, path in tools.items()},
                   'codeSha256': digest(data), 'codeBytes': len(data), 'candidateSha256': digest(candidate_bytes),
                   'disassemblySha256': digest(disassembly.encode('utf-8')), 'abi': ABI, 'abiSha256': ABI_SHA256,
                   'commands': commands, 'semanticValidation': 'spirv-val --target-env vulkan1.1 passed',
                   'limitations': ['No proof of termination or output bounds', 'No device execution or presentation proof']}
        (temp/'host-receipt.json').write_text(json.dumps(receipt, indent=2)+'\n', encoding='utf-8')
        temp.rename(output)  # Never replace a previously prepared candidate.
        return candidate
    finally:
        if temp.exists():
            shutil.rmtree(temp)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source', 'glslc', 'spirv-val', 'spirv-dis', 'output', 'app-build', 'session',
                 'producer-generation', 'expected-generation', 'expected-active-digest', 'request-id'):
        parser.add_argument('--'+name, required=True)
    print(json.dumps(prepare(parser.parse_args()), indent=2))


if __name__ == '__main__':
    main()
