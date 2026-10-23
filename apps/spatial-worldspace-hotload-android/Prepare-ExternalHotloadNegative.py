"""Derive one named, stage-only rejection fixture from a validated candidate.

No compiler, validator subprocess, device command, or activation is performed.
The two transformations deliberately invalidate actual parent SPIR-V bytes.
Never activate a negative candidate, even if staging unexpectedly accepts it.
"""
import argparse
import importlib.util
import json
import pathlib
import re
import shutil
import struct
import tempfile

SPEC = importlib.util.spec_from_file_location('external_preparer', pathlib.Path(__file__).with_name('Prepare-ExternalHotloadKernel.py'))
PREP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREP)
PRESETS = ('malformed-instruction', 'wrong-binding')
CANDIDATE_FIELDS = {'schema', 'slot', 'codeSha256', 'codeBytes', 'abiSha256', 'entry', 'target',
                    'appBuild', 'session', 'producerGeneration', 'expectedGeneration', 'expectedActiveDigest', 'requestId'}


def read_bounded(path, limit):
    PREP.require(path.is_file() and 0 < path.stat().st_size <= limit, 'missing/oversized evidence: '+path.name)
    data = path.read_bytes()
    PREP.require(0 < len(data) <= limit, 'evidence size changed')
    return data


def sha(value):
    PREP.require(isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value) is not None, 'SHA-256 format')
    return value


def strict_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            PREP.require(key not in result, 'duplicate JSON key')
            result[key] = value
        return result
    value = json.loads(data, object_pairs_hook=pairs)
    PREP.require(isinstance(value, dict), 'JSON object required')
    return value


def metadata(values):
    result = {key: values[key] for key in ('appBuild', 'session', 'producerGeneration', 'expectedGeneration', 'expectedActiveDigest', 'requestId')}
    sha(result['appBuild']); sha(result['expectedActiveDigest'])
    PREP.require(isinstance(result['session'], str) and re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', result['session']) is not None, 'session UUID format')
    for key in ('producerGeneration', 'expectedGeneration', 'requestId'):
        PREP.require(isinstance(result[key], str), 'decimal strings required')
        PREP.decimal(result[key])
    return result


def valid_parent(directory, receipt_sha):
    """Check exact bytes against the explicitly pinned successful host receipt."""
    files = {
        'host-receipt.json': read_bounded(directory/'host-receipt.json', 4194304),
        'candidate.json': read_bounded(directory/'candidate.json', 16384),
        'kernel.spv': read_bounded(directory/'kernel.spv', 65536),
        'kernel.spvasm': read_bounded(directory/'kernel.spvasm', 2097152),
        'input.comp': read_bounded(directory/'input.comp', 262144),
    }
    PREP.require(PREP.digest(files['host-receipt.json']) == sha(receipt_sha), 'parent receipt digest mismatch')
    receipt = strict_json(files['host-receipt.json']); candidate = strict_json(files['candidate.json'])
    PREP.require(receipt.get('schema') == 'rusty.quest.hotload.external_host_receipt.v1', 'validated parent receipt required')
    PREP.require(receipt.get('semanticValidation') == 'spirv-val --target-env vulkan1.1 passed', 'parent semantic validation missing')
    PREP.require(not receipt.get('intentionalInvalidCandidate', False), 'negative parent forbidden')
    PREP.require(set(candidate) == CANDIDATE_FIELDS, 'parent candidate fields')
    PREP.require(candidate['schema'] == 'rusty.quest.hotload.external_candidate.v1' and candidate['slot'] == 'public-diagnostic-v1'
                 and candidate['entry'] == 'main' and candidate['target'] == 'vulkan1.1', 'parent candidate contract')
    metadata(candidate)
    PREP.require(candidate['abiSha256'] == receipt.get('abiSha256') == PREP.ABI_SHA256 and receipt.get('abi') == PREP.ABI, 'parent ABI mismatch')
    PREP.require(type(candidate['codeBytes']) is int and candidate['codeBytes'] == receipt.get('codeBytes') == len(files['kernel.spv']), 'parent code size mismatch')
    PREP.require(candidate['codeSha256'] == receipt.get('codeSha256') == PREP.digest(files['kernel.spv']), 'parent code digest mismatch')
    PREP.require(receipt.get('candidateSha256') == PREP.digest(files['candidate.json']), 'parent manifest digest mismatch')
    PREP.require(receipt.get('disassemblySha256') == PREP.digest(files['kernel.spvasm']), 'parent disassembly digest mismatch')
    source_sha = PREP.digest(files['input.comp'])
    PREP.require(receipt.get('sourceSha256') == source_sha and receipt.get('sourceSnapshot') ==
                 {'file': 'input.comp', 'sha256': source_sha, 'bytes': len(files['input.comp'])}, 'parent source snapshot mismatch')
    PREP.check_source(files['input.comp'])
    PREP.require(PREP.binary_contract(files['kernel.spv']) == PREP.reflect(files['kernel.spvasm'].decode('utf-8')) == PREP.ABI, 'parent reflected contract mismatch')
    tools = receipt.get('tools')
    PREP.require(isinstance(tools, dict) and set(tools) == {'glslc', 'spirv-val', 'spirv-dis'}, 'parent tool provenance missing')
    for evidence in tools.values():
        PREP.require(isinstance(evidence, dict) and isinstance(evidence.get('path'), str) and evidence['path'], 'parent tool path missing')
        sha(evidence.get('sha256'))
    commands = receipt.get('commands')
    PREP.require(isinstance(commands, list) and len(commands) == 3, 'parent command provenance missing')
    for command, tool in zip(commands, ('glslc', 'spirv-val', 'spirv-dis')):
        PREP.require(isinstance(command, dict) and command.get('tool') == tool and type(command.get('exitCode')) is int
                     and command['exitCode'] == 0 and isinstance(command.get('arguments'), list), 'parent command did not pass')
    PREP.require('--target-env=vulkan1.1' in commands[0]['arguments'] and commands[1]['arguments'][:2] == ['--target-env', 'vulkan1.1'], 'parent Vulkan validation target')
    return files, receipt, candidate


def mutate(data, preset):
    PREP.require(preset in PRESETS, 'unknown negative preset')
    PREP.binary_contract(data)  # Only transform an interface-valid parent.
    words = list(struct.unpack('<'+'I'*(len(data)//4), data))
    if preset == 'malformed-instruction':
        offset = 5
        before = words[offset]
        words[offset] &= 65535  # Zero instruction word count, preserve opcode.
    else:
        hits, at = [], 5
        while at < len(words):
            count, opcode = words[at] >> 16, words[at] & 65535
            if opcode == 71 and count == 4 and words[at+2] == 33:
                hits.append(at+3)
            at += count
        PREP.require(len(hits) == 1 and words[hits[0]] == 0, 'one original binding zero required')
        offset = hits[0]; before = words[offset]; words[offset] = 1
    changed = struct.pack('<'+'I'*len(words), *words)
    PREP.require(changed != data, 'negative mutation must change bytes')
    try:
        PREP.binary_contract(changed)
    except ValueError as error:
        rejection = str(error)
    else:
        raise ValueError('negative mutation unexpectedly passed host contract; no candidate emitted')
    return changed, {'wordOffset': offset, 'before': before, 'after': words[offset]}, rejection


def prepare(a):
    directory = pathlib.Path(a.candidate_dir).resolve(strict=True)
    files, parent_receipt, parent_candidate = valid_parent(directory, a.host_receipt_sha)
    fresh = metadata({key: getattr(a, arg) for key, arg in (
        ('appBuild', 'app_build'), ('session', 'session'), ('producerGeneration', 'producer_generation'),
        ('expectedGeneration', 'expected_generation'), ('expectedActiveDigest', 'expected_active_digest'), ('requestId', 'request_id'))})
    PREP.require(fresh['appBuild'] == parent_candidate['appBuild'], 'negative fixture must target the validated parent app build')
    code, mutation, rejection = mutate(files['kernel.spv'], a.preset)
    output = pathlib.Path(a.output).resolve()
    PREP.require(not output.exists(), 'output already exists; choose a fresh negative directory')
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = pathlib.Path(tempfile.mkdtemp(prefix=output.name+'-prepare-', dir=output.parent))
    try:
        candidate = {**parent_candidate, **fresh, 'codeSha256': PREP.digest(code), 'codeBytes': len(code)}
        candidate_bytes = (json.dumps(candidate, sort_keys=True, indent=2)+'\n').encode('utf-8')
        (temp/'kernel.spv').write_bytes(code); (temp/'candidate.json').write_bytes(candidate_bytes)
        for original, copied in (('host-receipt.json', 'parent-host-receipt.json'), ('candidate.json', 'parent-candidate.json'),
                                 ('kernel.spv', 'parent-kernel.spv'), ('kernel.spvasm', 'parent-kernel.spvasm'), ('input.comp', 'input.comp')):
            (temp/copied).write_bytes(files[original])
        receipt = {
            'schema': 'rusty.quest.hotload.external_negative_receipt.v1', 'preset': a.preset,
            'intentionalInvalidCandidate': True, 'stageOnly': True, 'expectedOutcome': 'native_rejection',
            'semanticValidation': 'not_run_intentional_rejection',
            'codeSha256': PREP.digest(code), 'codeBytes': len(code), 'candidateSha256': PREP.digest(candidate_bytes),
            'abi': PREP.ABI, 'abiSha256': PREP.ABI_SHA256,
            'sourceSha256': parent_receipt['sourceSha256'], 'sourceSnapshot': parent_receipt['sourceSnapshot'],
            'sourceRelationship': 'valid_parent_source_with_intentional_binary_mutation', 'tools': parent_receipt['tools'],
            'parentHostReceiptFile': 'parent-host-receipt.json', 'parentHostReceiptSha256': PREP.digest(files['host-receipt.json']),
            'parentCodeFile': 'parent-kernel.spv', 'parentCodeSha256': parent_candidate['codeSha256'],
            'parentCandidateFile': 'parent-candidate.json', 'parentCandidateSha256': PREP.digest(files['candidate.json']),
            'parentDisassemblyFile': 'parent-kernel.spvasm', 'parentDisassemblySha256': PREP.digest(files['kernel.spvasm']),
            'mutation': mutation, 'hostContractRejection': rejection, 'commands': [],
        }
        (temp/'host-receipt.json').write_bytes((json.dumps(receipt, sort_keys=True, indent=2)+'\n').encode('utf-8'))
        temp.rename(output)
        return receipt
    finally:
        if temp.exists():
            shutil.rmtree(temp)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--preset', choices=PRESETS, required=True)
    for name in ('candidate-dir', 'host-receipt-sha', 'output', 'app-build', 'session',
                 'producer-generation', 'expected-generation', 'expected-active-digest', 'request-id'):
        parser.add_argument('--'+name, required=True)
    print(json.dumps(prepare(parser.parse_args()), indent=2))


if __name__ == '__main__':
    main()
