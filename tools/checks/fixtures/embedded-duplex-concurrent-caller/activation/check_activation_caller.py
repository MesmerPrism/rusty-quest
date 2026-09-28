"""Compile owning classes; codec/transport/native callbacks are explicit mocks."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--repo-root', required=True)
parser.add_argument('--java-home', required=True)
parser.add_argument('--class-path', required=True)
parser.add_argument('--output-directory', required=True)
args = parser.parse_args()
root = Path(args.repo_root).resolve()
out = Path(args.output_directory).resolve()
out.mkdir(exist_ok=False)
fixture = Path(__file__).parent
app = root / 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
gate = app / 'EmbeddedDuplexActivationGate.java'
anchor = 'long[] frame = awaitCurrentIncomingFrame(current, claimedRevision, readinessDeadline);'
source = gate.read_text()
assert source.count(anchor) == 1, 'current owning readiness call changed'
previous = out / 'one-read-counterexample' / gate.name
previous.parent.mkdir()
previous.write_text(source.replace(anchor, 'long[] frame = target.currentIncomingFrame(MAX_FRAME_AGE_NS);'))
suffix = '.exe' if os.name == 'nt' else ''
java = Path(args.java_home) / 'bin' / ('java' + suffix)
javac = Path(args.java_home) / 'bin' / ('javac' + suffix)
records = []
for variant, owning_gate in [('baseline', previous), ('candidate', gate)]:
    classes = out / variant
    classes.mkdir()
    inputs = [owning_gate, app / 'EmbeddedDuplexReceiver.java', app / 'EmbeddedDuplexResources.java']
    inputs += [fixture / name for name in ['CompleteActivationCaller.java', 'EmbeddedDuplexNative.java', 'Log.java']]
    compiled = subprocess.run([str(javac), '--release', '8', '-Xlint:all', '-Werror', '-cp', args.class_path, '-d', str(classes)] + [str(p) for p in inputs], capture_output=True, text=True)
    (out / (variant + '-javac.txt')).write_text(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, compiled.stderr
    ran = subprocess.run([str(java), '-cp', str(classes) + os.pathsep + args.class_path, 'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.CompleteActivationCaller', variant], capture_output=True, text=True, timeout=40)
    (out / (variant + '-stdout.txt')).write_text(ran.stdout)
    (out / (variant + '-stderr.txt')).write_text(ran.stderr)
    assert ran.returncode == 0, (variant, ran.stdout, ran.stderr)
    assert 'PRIVATE_SENTINEL' not in ran.stdout, 'raw receiver failure detail leaked'
    if variant == 'candidate':
        for stage in ['ARM_PROOF', 'FIRST_RENDER', 'NATIVE_ACQUISITION', 'NATIVE_EFFECTIVE']:
            assert 'stage=' + stage in ran.stdout, 'closed stage missing: ' + stage
        assert 'packets=0 frames=0 reconnects=0' in ran.stdout
    records.append({'variant': variant, 'status': 'passed', 'stdout': ran.stdout})
result = {
    'status': 'passed',
    'scope': 'actual Registry -> Receiver preparation/provider -> exact arm/proof Gate -> actual Receiver await/currentFrame; one-read counterexample vs bounded join',
    'sources': [{'path': str(p.relative_to(root)).replace('\\', '/'), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in [gate, app / 'EmbeddedDuplexReceiver.java', app / 'EmbeddedDuplexResources.java']],
    'cases': records,
    'limits': 'codec/transport/native callbacks and physical observations mocked; real 15-second no-acquisition deadline tested, no JNI/device/runtime cause or terminal cleanup claim; historical counterexample changes only the actual readiness call to a single observation',
}
(out / 'result.json').write_text(json.dumps(result, indent=2))
print('PASS complete compiled activation caller and closed diagnostic regression')
