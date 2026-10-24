"""Persistent Java owner half of the renewal-successor host composition.

Whole Original, SDK, archive, Receiver and display owners compile unchanged.
ProcessHost's physical producer and identity method are copied byte-for-byte
after newline normalization; Android/JNI, scheduling and hardware leaves are
explicit models. This module neither sequences Native effects nor creates a
Native lifecycle receipt.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def pin(path):
    path = Path(path)
    raw = path.read_bytes()
    return dict(path=str(path), bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())


def item(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth, quoted, escape = 0, False, False
    for end in range(brace, len(text)):
        char = text[end]
        if quoted:
            if escape:
                escape = False
            elif char == '\\':
                escape = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return text[start:end + 1]
    raise ValueError('Incomplete production item: ' + signature)


def build_java_support(*, quest_root, input_spec, support_classes, output, json_jar):
    """Create a fresh compile directory; return JSON-safe JVM launch metadata."""
    root, out = Path(quest_root).resolve(), Path(output).resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    generated, classes = out / 'generated', out / 'classes'
    generated.mkdir()
    classes.mkdir()
    spec = json.loads(Path(input_spec).read_text(encoding='utf-8-sig'))
    support = Path(support_classes).resolve()
    if not support.is_dir():
        raise ValueError('Missing maintained support classes')

    def bind(row):
        current = pin(row['path'])
        if current['sha256'] != row['sha256']:
            raise ValueError('Dependency drift: ' + row['path'])
        return current['path']

    compilers = [bind(row) for row in spec['jvm']['compiler_jars']]
    libs = [bind(row) for row in spec['jvm']['classpath']]
    for row in spec['support_pins']:
        bind(row)
    json_jar = str(Path(json_jar).resolve())
    if not Path(json_jar).is_file():
        raise ValueError('Missing explicit JSON jar')
    app = root / 'apps/spatial-camera-panel-android'
    java = app / 'app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel'
    j, fixtures = java / 'embedded_duplex', app / 'host-tests/embedded_duplex'
    host = (j / 'EmbeddedDuplexProcessHost.java').read_text(encoding='utf-8')
    platform = (j / 'EmbeddedDuplexPlatform.java').read_text(encoding='utf-8')
    fence = (j / 'EmbeddedDuplexProcessFence.java').read_text(encoding='utf-8')
    models = (fixtures / 'PreparedSettledCustodyPlatform.java').read_text(encoding='utf-8')
    handoff = (j / 'EmbeddedDuplexFreshHandoffStore.java').read_text(encoding='utf-8')
    models = models.replace(item(models, 'static void keys(JSONObject v,'), item(handoff, 'static void keys(JSONObject value,'))
    for name in ['EmbeddedDuplexNative', 'EmbeddedDuplexIncomingFreshPhases', 'EmbeddedDuplexFreshPhaseArchive']:
        models = models.replace(item(models, 'class ' + name + ' {'), '')
    old = item(models, 'class EmbeddedDuplexProcessHost {')
    actual_physical = item(host, 'static JSONObject freshPhysical(')
    actual_identity = item(host, 'static JSONObject freshClosureIdentity(')
    additions = '''
 enum Phase {READY,CLOSING}
 static final EmbeddedDuplexProcessHost instance=new EmbeddedDuplexProcessHost();
 final java.util.concurrent.atomic.AtomicReference<Phase> phase=new java.util.concurrent.atomic.AtomicReference<>(Phase.READY);
 Context applicationContext;EmbeddedDuplexResources resources;EmbeddedDuplexPlatform platform;
 EmbeddedDuplexRuntimeConfigurationBinding.Context nativeConfigurationContext=new EmbeddedDuplexRuntimeConfigurationBinding.Context();
 static JSONObject fixtureArm;
 EmbeddedDuplexProcessFence processFence=new EmbeddedDuplexProcessFence();
 long nativeExecutorGeneration;String runtimeConfigSha256,nativeAppRecordSha256;
 static JSONObject fixturePhysical(Context c,EmbeddedDuplexResources r,EmbeddedDuplexPreparedStart h,JSONObject leaves,JSONObject binding)throws Exception{
  EmbeddedDuplexPeerRoots.keys(leaves,"receiver","renderer_terminal","owner_callback_active","peer_effect_entered");
  for(String k:new String[]{"renderer_terminal","owner_callback_active","peer_effect_entered"})
   if(!(leaves.get(k) instanceof Boolean))throw new IllegalArgumentException("modeled physical leaf type");
  leaves.getJSONObject("receiver");
  EmbeddedDuplexProcessHost current=instance;current.applicationContext=c;current.resources=r;
  current.nativeExecutorGeneration=ACTOR.getLong("native_executor_generation");
  current.runtimeConfigSha256=ACTOR.getString("config_sha256");current.nativeAppRecordSha256=ACTOR.getString("app_record_sha256");
  if(current.platform==null)current.platform=new EmbeddedDuplexPlatform();
  current.platform.preparedStart=h;current.platform.leaves=new JSONObject(leaves.toString());
  EmbeddedDuplexRendererRetirement.leaves=current.platform.leaves;
  return freshPhysical(current.platform,binding,false);
 }
 static JSONObject fixturePrepared(Context c,EmbeddedDuplexResources r,EmbeddedDuplexPreparedStart h,JSONObject leaves,JSONObject arm,boolean targetOnly)throws Exception{
  fixturePhysical(c,r,h,leaves,null);fixtureArm=new JSONObject(arm.toString());
  EmbeddedDuplexPreparationDiagnostic.EligibilityTrace trace=new EmbeddedDuplexPreparationDiagnostic.EligibilityTrace();
  if(targetOnly)return preparedStartTarget(instance.platform,trace);
  try{return new JSONObject(trace.result(preparedStartEligibility(instance.platform,trace)));}
  catch(Exception failure){return new JSONObject(trace.result(null));}
 }
'''
    # Dynamic startup actor is the sole modeled actor source, never a fixed ID.
    old = old.replace('nativeActor?73:JSONObject.NULL', 'nativeActor?ACTOR.get("native_executor_generation"):JSONObject.NULL')
    old = old.replace('appRecord?"a".repeat(64):JSONObject.NULL', 'appRecord?ACTOR.get("app_record_sha256"):JSONObject.NULL')
    old = old.replace(item(old, 'static void requireCurrentOriginalStopActor('), item(host, 'static void requireCurrentOriginalStopActor('))
    prepared_methods = '\n'.join(item(host, signature) for signature in [
        'private static JSONObject preparedStartTarget(',
        'static JSONObject preparedStartEligibility(EmbeddedDuplexPlatform actual,'])
    models = models.replace(item(models, 'class EmbeddedDuplexProcessHost {'), old[:-1] + additions + actual_identity + '\n' + actual_physical + '\n' + prepared_methods + '\n}')
    models += '''
class EmbeddedDuplexRendererRetirement {
 static JSONObject leaves;
 static boolean currentTerminal()throws Exception{return leaves!=null&&leaves.getBoolean("renderer_terminal");}
 static boolean currentFreshEligible(String own)throws Exception{return currentTerminal();}
}
class ConcurrentStereoQualification {
 static JSONObject retirementArmObservation(String epoch,String config,long generation)throws Exception{
  JSONObject arm=EmbeddedDuplexProcessHost.fixtureArm;
  if(arm==null||!arm.getString("process_epoch").equals(epoch)||!arm.getString("runtime_config_sha256").equals(config)||arm.getLong("native_process")!=generation)
   throw new IllegalStateException("modeled ARM owner changed");
  return new JSONObject(arm.toString());
 }
}
'''

    def write(name, value):
        path = generated / name
        path.write_text(value, encoding='utf-8', newline='\n')
        return path

    modeled = [write('LifecyclePlatform.java', models)]
    package = 'package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;\n'
    physical_status = item(platform, 'JSONObject preparedPeerPhysicalStatus(')
    modeled.append(write('EmbeddedDuplexPlatform.java', package + '''import org.json.*;import java.security.MessageDigest;import java.nio.charset.StandardCharsets;
class EmbeddedDuplexPlatform {
 EmbeddedDuplexPreparedStart preparedStart;JSONObject leaves;
 boolean freshPeerEffectsNeverEntered()throws Exception{return !leaves.getBoolean("peer_effect_entered")&&!leaves.getBoolean("owner_callback_active");}
 boolean freshPeerControlQuiescent()throws Exception{return !leaves.getBoolean("owner_callback_active");}
 boolean actualControlTerminal(){return false;}boolean incomingFreshTaskJoined(String id){return false;}
''' + physical_status + '\n}\nclass EmbeddedDuplexProcessFence {\n'
        + 'long generation(){return EmbeddedDuplexProcessHost.ACTOR.getLong("app_process_generation");}\n'
        + 'String epochId(){return EmbeddedDuplexProcessHost.fixtureArm.getString("process_epoch");}\n'
        + 'void requireLive(long g){if(g!=generation())throw new IllegalStateException("modeled app fence changed");}\n'
        + item(fence, 'static String digest(') + '\n}\n'))
    modeled.append(write('OwnStereoCaptureRuntime.java', package + '''
public class OwnStereoCaptureRuntime {
 public enum Phase{Live}
 public static class Capture {public boolean fresh(){throw new AssertionError("Own not constructed");}public Throwable failure(){return null;}}
 public static OwnStereoCaptureRuntime currentForApplication(){return null;}
 public Capture retainedCapture(){return null;}public Phase phase(){throw new AssertionError("Own not constructed");}
 public java.util.concurrent.CompletableFuture<Void> whenFresh(Capture c,java.util.function.BooleanSupplier alive){throw new AssertionError("Own not constructed");}
 public JSONObject physicalRetirementObservation(){throw new AssertionError("Own not constructed");}
}
'''.replace('public JSONObject', 'public org.json.JSONObject')))
    # Maintained Android I/O leaf literals, without executing its test main.
    import ast
    owner_test = root / 'tools/checks/Test-PreparedSettledCustodyHost.py'
    tree = ast.parse(owner_test.read_text(encoding='utf-8'))
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == 'write' and len(node.args) == 2:
            if isinstance(node.args[0], ast.Constant) and node.args[0].value in ['Context.java', 'Bundle.java', 'OsConstants.java', 'Os.java']:
                leaf = ast.literal_eval(node.args[1])
                if node.args[0].value == 'Context.java':
                    leaf = leaf.replace('public Context getApplicationContext()', 'public String getPackageName(){return "host.fixture";} public Context getApplicationContext()')
                modeled.append(write(node.args[0].value, leaf))
    boundary_owner = root / 'tools/checks/Test-ConcurrentPeerRetirementHost.ps1'
    original = boundary_owner.read_text(encoding='utf-8')
    seams = {}
    for key, name in [('looper', 'Looper.java'), ('native', 'EmbeddedDuplexNative.java'), ('seams', 'SourceBoundary.kt')]:
        match = re.search(r'\$' + key + r"=WriteHost '[^']+' @'\r?\n(.*?)\r?\n'@", original, re.S)
        if match is None:
            raise ValueError('Maintained model leaf missing: ' + key)
        text = match.group(1)
        if key == 'native':
            klass = item(text, 'class EmbeddedDuplexNative {')
            text = text.replace(klass, klass[:-1] + 'static void beginOperationDiagnostics(String op,String id){}static void endOperationDiagnostics(){}\n}')
        seams[key] = write(name, text)
    modeled.extend([seams['looper'], seams['native']])
    names = ['EmbeddedDuplexOriginals', 'EmbeddedDuplexCanonicalJson', 'EmbeddedDuplexNativeOperationFailure',
             'EmbeddedDuplexPeerRoots', 'EmbeddedDuplexPreparedStart', 'EmbeddedDuplexPreparedArchive',
             'EmbeddedDuplexPreparedRenderer', 'EmbeddedDuplexFreshLifecycle', 'EmbeddedDuplexIncomingFreshPhases',
             'EmbeddedDuplexFreshPhaseArchive', 'EmbeddedDuplexPeerLifetimeArchive', 'EmbeddedDuplexPairStatus',
             'EmbeddedDuplexFreshStatus', 'EmbeddedDuplexPreparationDiagnostic', 'EmbeddedDuplexReceiver',
             'EmbeddedDuplexDisplay', 'EmbeddedDuplexOwnProjectionFailure', 'EmbeddedDuplexEstimate', 'EmbeddedDuplexDisplaySlot']
    production = [j / (name + '.java') for name in names]
    fixture_java = fixtures / 'PreparedSettledCustodyHost.java'
    kt = [seams['seams'], java / 'SpatialVideoSourceRoutingCoordinator.kt', j / 'EmbeddedDuplexDisplayCoordinator.kt',
          fixtures / 'PreparedSecondLifetimeHost.kt', fixtures / 'RenewalSuccessorLifecycleHost.kt']
    responses = (j / 'EmbeddedDuplexOperatorResponses.kt').read_text(encoding='utf-8')
    kt.append(write('EmbeddedDuplexOperatorResponses.kt', package + 'import android.os.Bundle\nobject EmbeddedDuplexOperatorResponses {\n'
                    + item(responses, 'private fun Bundle.header(') + '\n'
                    + item(responses, '@JvmStatic fun pairBundle(') + '\n}\n'))
    qualification_source = j / 'ConcurrentStereoQualification.kt'
    qualification = qualification_source.read_text(encoding='utf-8')
    # Exact production lifecycle + receipt bodies; ARM/native snapshot is a
    # declared model here, not an installed qualification/Pixel observation.
    kt.append(write('RenewalLifecycleBoundary.kt', package + '''
import org.json.JSONObject
import org.json.JSONArray
object RenewalLifecycleBoundary {
 private var challenge:String?=null;private var processEpoch:String?=null
 private var runtimeConfig:String?=null;private var featureLock:String?=null;private var installedApk:String?=null
 private var arm=1L;private var nativeProcess=1L
 private object StereoBankControls {
  fun challengeWords(value:String)=longArrayOf(0L,0L)
  fun concurrentQualification()=LongArray(160).also { it[2]=nativeProcess;it[5]=arm;it[6]=1L }
 }
 @JvmStatic fun validate(raw:String,actor:JSONObject):JSONObject {
  challenge="0".repeat(32);processEpoch="fixture.arm";runtimeConfig=actor.getString("config_sha256")
  featureLock="c".repeat(64);installedApk="d".repeat(64);nativeProcess=actor.getLong("native_executor_generation")
  val wrapped=lifecycle("peer_status",challenge!!,processEpoch!!,runtimeConfig!!,featureLock!!,installedApk!!,raw)
  EmbeddedDuplexFreshLifecycle.require(JSONObject(raw))
  return JSONObject().put("validated",true).put("native_utf8_bytes",raw.toByteArray(Charsets.UTF_8).size)
    .put("native_utf16_characters",raw.length).put("wrapped_utf8_bytes",wrapped.toByteArray(Charsets.UTF_8).size)
    .put("scope","exact production lifecycle/receipt bodies; modeled ARM/snapshot, no JNI or pixels")
 }
''' + item(qualification,'@JvmStatic fun lifecycle(') + '\n' + item(qualification,'private fun receipt(') + '\n}\n'))
    sources = [*production, fixture_java, *kt[1:], qualification_source, j / 'EmbeddedDuplexProcessHost.java', j / 'EmbeddedDuplexPlatform.java',
               j / 'EmbeddedDuplexProcessFence.java', j / 'EmbeddedDuplexFreshHandoffStore.java', j / 'EmbeddedDuplexOperatorResponses.kt', fixtures / 'PreparedSettledCustodyPlatform.java', owner_test, boundary_owner, Path(__file__)]
    before = [pin(path) for path in sources]
    cp = os.pathsep.join([str(classes), str(support), json_jar, *libs, *compilers])
    checks = []

    def run(name, argv):
        result = subprocess.run([str(x) for x in argv], cwd=root, capture_output=True)
        stdout, stderr = out / (name + '.stdout'), out / (name + '.stderr')
        stdout.write_bytes(result.stdout)
        stderr.write_bytes(result.stderr)
        checks.append(dict(name=name, argv=[str(x) for x in argv], exit=result.returncode, stdout=pin(stdout), stderr=pin(stderr)))
        if result.returncode:
            (out / 'FAILED.json').write_text(json.dumps(checks, indent=2), encoding='utf-8')
            raise RuntimeError(name + ' failed; exact compiler streams retained')

    run('whole-java', [spec['tools']['javac'], '--release', '17', '-encoding', 'UTF-8', '-cp', cp, '-d', classes,
                       *production, fixture_java, *modeled])
    run('whole-kotlin', [spec['tools']['java'], '-cp', os.pathsep.join(compilers), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                         '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', classes, *kt])
    if before != [pin(path) for path in sources]:
        raise ValueError('Source drift during Java compilation')
    result = dict(schema='test.renewal-successor.java-support.v1', classes=str(classes), classpath=cp,
                  java=spec['tools']['java'], source_refs=before, generated_refs=[pin(x) for x in [*modeled, seams['seams']]],
                  input_spec=pin(input_spec), json_jar=pin(json_jar), checks=checks,
                  exact_producers={'freshPhysical': actual_physical, 'freshClosureIdentity': actual_identity,
                                   'preparedPeerPhysicalStatus': physical_status},
                  modeled_leaves=['Android directory fsync and app fence', 'ProcessHost READY scheduling and serial owner control',
                                  'Own runtime explicitly not constructed', 'renderer/JNI values from current captured fixture facts',
                                  'codec/socket primitives; no real device/camera/GPU'],
                  lifecycle_executed=False)
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    return result


class JavaPeerRPC:
    """One persistent JVM role. No Native effects or success synthesis."""
    def __init__(self, built, *, peer, actor, root, output):
        if peer not in ['Q', 'W']:
            raise ValueError('Fixed Java role differs')
        self.peer, self.output, self.calls = peer, Path(output), 0
        self.output.mkdir(parents=True, exist_ok=False)
        self.stderr = (self.output / 'stderr.raw').open('wb')
        self.process = subprocess.Popen([built['java'], '-cp', built['classpath'],
            'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.RenewalSuccessorLifecycleHost',
            str(root), peer, json.dumps(actor, separators=(',', ':'))], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=self.stderr)

    def call(self, step, payload):
        self.calls += 1
        request = dict(step=step, peer=self.peer, payload=payload)
        raw = (json.dumps(request, separators=(',', ':')) + '\n').encode()
        (self.output / f'{self.calls:04d}.request.jsonl').write_bytes(raw)
        self.process.stdin.write(raw)
        self.process.stdin.flush()
        returned = self.process.stdout.readline(8 * 1024 * 1024 + 1)
        (self.output / f'{self.calls:04d}.response.jsonl').write_bytes(returned)
        if len(returned) > 8 * 1024 * 1024 or not returned.endswith(b'\n'):
            raise RuntimeError('Java callback stream unavailable or exceeds bound')
        reply = json.loads(returned)
        if set(reply) != {'step', 'peer', 'result'} or reply['step'] != step or reply['peer'] != self.peer:
            raise RuntimeError('Java callback envelope changed')
        return reply['result']

    def close(self):
        self.process.stdin.close()
        tail = self.process.stdout.read()
        (self.output / 'stdout.tail.raw').write_bytes(tail)
        code = self.process.wait()
        self.stderr.close()
        (self.output / 'CLOSED.json').write_text(json.dumps(dict(exit=code, reaped=True, stdout_eof=True, calls=self.calls)), encoding='utf-8')
        if tail or code:
            raise RuntimeError('Java role did not close cleanly')


class NativeCallbackBridge:
    """Closed JNI-to-fixture adapter, without Native operation sequencing.

The selected resource metadata comes from the actual Native constructor.
Camera IDs/CSD, Own-not-created and ARM scheduling below are test primitives,
not observations of an Android camera, renderer timer or authority extension.
"""
    def __init__(self, peer_rpc, *, actor, initialized):
        self.rpc, self.actor = peer_rpc, dict(actor)
        self.original = None
        self.preparations = {}
        if (initialized['executor_generation'] != actor['native_executor_generation']
                or initialized['app_process_generation'] != actor['app_process_generation']
                or initialized['app_record_sha256'] != actor['app_record_sha256']
                or initialized['runtime']['config_sha256'] != actor['config_sha256']):
            raise ValueError('Java modeled actor differs from actual Native constructor')
        outgoing, incoming = initialized['outgoing_runtime_spec'], initialized['incoming_runtime_spec']
        lanes = outgoing['plan']['lanes']
        if len(lanes) != 1:
            raise ValueError('Fixture requires one selected packed media lane')
        media = lanes[0]['media']
        width, height, fps = media['width'], media['height'], media['frame_rate_hz']
        if any(type(x) is not int or x <= 0 for x in [width, height, fps]) or width % 2:
            raise ValueError('Selected packed media dimensions differ')
        providers = [{key: row[key] for key in ['owner_kind', 'owner_id', 'provider_kind', 'resource_id']}
                     for row in outgoing['owner_selections']]
        providers.sort(key=lambda row: '\0'.join(row[key] for key in ['owner_kind', 'owner_id', 'provider_kind', 'resource_id']))
        if len(providers) != 7:
            raise ValueError('Selected fixture product requires seven owners')
        self.target = {'$schema': 'rusty.quest.embedded_duplex.peer_start_resource_target.v1',
            'runtime_config_sha256': actor['config_sha256'], 'executor_generation': actor['native_executor_generation'],
            'product_binding': {'$schema': 'rusty.quest.android.media.preparation_target.v1',
                                'product_id': outgoing['runtime_spec_id'], 'providers': providers},
            'source_dimensions': {'packed_width': width, 'packed_height': height, 'per_eye_width': width // 2,
                'per_eye_height': height, 'frame_rate_hz': fps, 'bitrate_bps': media['bitrate_bps'],
                'left_camera_id': 'modeled.left', 'right_camera_id': 'modeled.right'},
            'own_capture_selected': False, 'max_pair_delta_ns': 10_000_000,
            'outgoing_runtime_spec_id': outgoing['runtime_spec_id'], 'incoming_runtime_spec_id': incoming['runtime_spec_id']}
        self.arm = {'binding': 'established', 'challenge': 'c' * 32, 'arm_generation': 1,
                    'native_process': actor['native_executor_generation'],
                    'process_epoch': 'modeled.jvm.' + str(actor['app_process_generation']),
                    'runtime_config_sha256': actor['config_sha256']}
        self.encoded_format = {'$schema': 'rusty.quest.android.media.prepared_encoded_format.v1',
            'mime': 'video/avc', 'width': width, 'height': height, 'frame_rate_hz': fps,
            'csd_0_hex': '000000016742001e', 'csd_1_hex': None, 'profile': None, 'level': None}
        self._call('configure_profile', dict(width=width, height=height, frame_rate_hz=fps))

    def _call(self, step, payload):
        result = self.rpc.call(step, payload)
        if 'error' in result:
            raise RuntimeError('Actual Java callback refused: ' + json.dumps(result['error'], sort_keys=True))
        return result

    def set_original(self, original_id):
        if not isinstance(original_id, str) or re.fullmatch('[0-9a-f]{32}', original_id) is None:
            raise ValueError('Actual fixture Original ID required')
        # Query the actual production row; never manufacture a Java operation.
        self._call('operation_query', dict(original_id=original_id))
        self.original = original_id

    def _preparation(self, preparation_id):
        if preparation_id not in self.preparations:
            raise ValueError('Unknown actual Java preparation incarnation')
        return self.preparations[preparation_id]

    def call(self, method, payload):
        if method in ['receiver_start', 'receiver_stop', 'renderer_status']:
            return self._call(method, payload)
        if method in ['peerStartPreparationEligibilityObservationJson', 'peerStartResourceTargetJson']:
            if payload != {} or self.original is None:
                raise ValueError('Preparation callback requires entered actual operation')
            step = 'preparation_eligibility' if method.endswith('ObservationJson') else 'preparation_target'
            return self._call(step, dict(original_id=self.original, arm=self.arm, resource_target=self.target))
        if method == 'freshSetupPhysicalEligibilityJson':
            if payload != {} or self.original is None:
                raise ValueError('Physical callback requires actual entered operation')
            return self._call('physical_snapshot', {'original_binding': {'kind': 'operator', 'original_id': self.original}})
        if method in ['freshSetupInitialPhysicalEligibilityJson', 'freshSetupPhysicalEligibilityForIncomingJson',
                      'freshSetupPreparationPhysicalEligibilityJson']:
            if set(payload) != {'native', 'phase', 'preparation'}:
                raise ValueError('Incoming physical callback shape differs')
            if any(not isinstance(payload[k], str) or re.fullmatch('[a-z][a-z0-9_.-]{0,255}', payload[k]) is None for k in ['native', 'phase']):
                raise ValueError('Incoming physical callback identity differs')
            # RequestSpan/bind scheduling is explicitly modeled by the Native
            # driver; this does not claim that the separate Java epoch guard ran.
            return self._call('physical_snapshot', {'original_binding': {'kind': 'incoming_fresh_setup', 'native_request_id': payload['native']}})
        if set(payload) != {'input'} or not isinstance(payload['input'], str):
            raise ValueError('Exact string JNI callback payload required')
        exact = payload['input']
        if method == 'preparePeerStartResources':
            request = json.loads(exact)
            if request['original_id'] != self.original or request['resource_target'] != self.target or request['arm_binding'] != self.arm:
                raise ValueError('Native preparation changed selected Java source inputs')
            original = request['original_id']
            result = self._call('sdk_sender', dict(request=request, actor=self.actor, arm=self.arm, encoded_format=self.encoded_format))
            self.preparations[request['preparation_id']] = original
            return result
        if method == 'preparePeerStartDecoder':
            body = json.loads(exact)
            if set(body) != {'$schema', 'request', 'decoder'} or body['$schema'] != 'rusty.quest.android.media.prepared_decoder_execution.v1':
                raise ValueError('Native decoder wrapper differs')
            request = body['request']
            original = self._preparation(request['preparation_id'])
            return self._call('sdk_decoder', dict(original_id=original, request=body['decoder']))
        if method == 'preparedPeerStartStatusJson':
            return self._call('sdk_status', dict(original_id=self._preparation(exact)))
        if method == 'finalizePeerStartTransport':
            request = json.loads(exact)
            return self._call('sdk_finalize', dict(original_id=self._preparation(request['preparation_id'])))
        archives = {'archiveCompletedPreparedPeerStart': 'prepared_archive',
                    'archiveFreshSetupPhase': 'phase_archive', 'archiveCompletedPeerLifetime': 'lifetime_archive'}
        if method in archives:
            return self._call(archives[method], dict(exact_body=exact))
        raise ValueError('Unconfigured JNI callback: ' + method)
