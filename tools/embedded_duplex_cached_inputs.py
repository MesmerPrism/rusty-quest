"""One fixed cached-input observation through an existing owned serial-scoped reader.

The supplied read(argv) must own current actor/care checks and capture/EOF/reap.
No subprocess, discovery, retry, launch, store, enrollment or mutation is performed here.
"""
import base64
import json
import re

METHOD = 'cached_effective_inputs'
SCHEMA = 'rusty.quest.embedded_duplex.cached_effective_inputs.v1'
MAX_BYTES = 65536


def need(ok):
    if not ok:
        raise ValueError('cached_input_observation_invalid')


def token(value, pattern):
    need(type(value) is str and re.fullmatch(pattern, value) is not None)
    return value


def actor_fields(actor):
    need(type(actor) is dict and set(actor) == {'pid', 'uid', 'start_ticks', 'boot_id'})
    need(type(actor['pid']) is int and 0 < actor['pid'] < 2**31)
    need(type(actor['uid']) is int and 0 <= actor['uid'] < 2**31)
    token(actor['start_ticks'], r'[1-9][0-9]{0,19}')
    token(actor['boot_id'], r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}')
    return actor


def request_args(package_id, run_id, actor, challenge):
    token(package_id, r'[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+')
    token(run_id, r'[0-9a-f]{32}'); token(challenge, r'[0-9a-f]{32}'); actor_fields(actor)
    args = ['shell', 'content', 'call', '--uri', 'content://' + package_id + '.embedded-duplex-operator',
            '--method', METHOD, '--arg', challenge, '--extra', 'run_id:s:' + run_id]
    for key, kind in [('pid', 'i'), ('uid', 'i'), ('start_ticks', 's'), ('boot_id', 's')]:
        args.extend(['--extra', 'expected_' + key + ':' + kind + ':' + str(actor[key])])
    return args


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result); result[key] = value
    return result


def keys(value, fields):
    need(type(value) is dict and set(value) == set(fields.split()))


def digest(value):
    token(value, r'[0-9a-f]{64}')


def endpoint(value, protocol):
    keys(value, 'host port protocol'); need(value['protocol'] == protocol)
    parts = token(value['host'], r'[0-9.]{7,15}').split('.')
    need(len(parts) == 4 and all(re.fullmatch(r'0|[1-9][0-9]{0,2}', p) and int(p) <= 255 for p in parts))
    need(type(value['port']) is int and 0 < value['port'] <= 65535)


def decode(stdout, run_id, actor, challenge):
    need(type(stdout) is bytes and len(stdout) <= MAX_BYTES * 2)
    matches = re.findall(rb'\breceipt_base64=([A-Za-z0-9+/]+={0,2})(?=[,}\]\s])', stdout)
    need(len(matches) == 1)
    raw = base64.b64decode(matches[0], validate=True); need(len(raw) <= MAX_BYTES)
    value = json.loads(raw.decode('utf-8'), object_pairs_hook=unique_object)
    keys(value, '$schema challenge requested_run_id observation_scope process selected_run_id run_selection_generation phase selection native_configuration socket_liveness_observed')
    need(value['$schema'] == SCHEMA and value['challenge'] == challenge and value['requested_run_id'] == run_id)
    need(value['observation_scope'] == 'cached_only' and value['socket_liveness_observed'] is False)
    actor_fields(value['process']); need(value['process'] == actor)
    need(value['selected_run_id'] in (None, run_id))
    need(type(value['run_selection_generation']) is int and value['run_selection_generation'] >= 0)
    if value['phase'] == 'uninitialized':
        need(value['selection'] is None and value['native_configuration'] is None)
        if value['selected_run_id'] is None:
            need(value['run_selection_generation'] == 0)
    else:
        need(value['phase'] in ('frozen', 'native_configured') and value['selected_run_id'] == run_id)
        selected = value['selection']; keys(selected, 'generation installed_role_id source lineage peers')
        need(type(selected['generation']) is int and selected['generation'] > 0)
        token(selected['installed_role_id'], r'[a-zA-Z0-9_.-]{1,128}')
        lineage = selected['lineage']; keys(lineage, 'base_manifest_sha256 base_route_sha256 profile_sha256 effective_route_sha256')
        for field in ('base_manifest_sha256', 'base_route_sha256', 'effective_route_sha256'): digest(lineage[field])
        if lineage['profile_sha256'] is None:
            need(selected['source'] == 'packaged')
        else:
            digest(lineage['profile_sha256']); need(selected['source'] == 'control_profile')
        peers = selected['peers']; need(type(peers) is list and len(peers) == 2)
        for peer in peers:
            keys(peer, 'peer_id media_endpoint control_endpoint'); token(peer['peer_id'], r'[a-zA-Z0-9_.-]{1,128}')
            endpoint(peer['media_endpoint'], 'rmanvid-v4-packed-stereo'); endpoint(peer['control_endpoint'], 'rqod1')
        need(len({p['peer_id'] for p in peers}) == 2)
        native = value['native_configuration']
        if value['phase'] == 'frozen':
            need(native is None)
        else:
            keys(native, 'runtime_config_sha256 executor_generation app_record_sha256')
            digest(native['runtime_config_sha256']); digest(native['app_record_sha256'])
            need(type(native['executor_generation']) is int and native['executor_generation'] > 0)
    return value


def observe(package_id, run_id, actor, challenge, read):
    """Exactly one owned read. Uninitialized is an observation, never a reason to initialize."""
    args = request_args(package_id, run_id, actor, challenge)
    return decode(read(args), run_id, actor, challenge)
