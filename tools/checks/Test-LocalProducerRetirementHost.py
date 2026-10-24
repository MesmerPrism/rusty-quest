"""Focused actual Local frontdoor/routing retirement test; no Android or native build."""
import sys
sys.dont_write_bytecode = True
import argparse, hashlib, json, os, pathlib, subprocess, time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--kotlin-cache', type=pathlib.Path, required=True)
    parser.add_argument('--json-jar', type=pathlib.Path, required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--expect-red', action='store_true')
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    app = root / 'apps/spatial-camera-panel-android'
    source = app / 'app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel'
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    classes = output / 'classes'
    classes.mkdir()

    def pin(path):
        path = pathlib.Path(path).resolve(strict=True)
        raw = path.read_bytes()
        return dict(path=str(path), bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())

    def write(name, text):
        path = output / name
        with path.open('x', encoding='utf-8', newline='\n') as stream:
            stream.write(text)
        return path

    activity = source / 'SpatialCameraPanelActivity.kt'
    text = activity.read_text()
    first = text.index('  private fun requestProjectionSource(')
    last = text.index('  private fun requestInitialProjectionSource(', first)
    request = text[first:last]
    display = source / 'embedded_duplex/EmbeddedDuplexDisplayCoordinator.kt'
    text = display.read_text()
    first = text.index('  override fun retirePeerProjection()')
    last = text.index('  override fun restoreLocalAfterProductCleanup()', first)
    retirement = text[first:last].replace('override fun', 'fun', 1)
    fixture = app / 'host-tests/embedded_duplex/LocalProducerRetirementHost.kt'
    host = write('Host.kt', fixture.read_text().replace('  /* ACTUAL_ACTIVITY_REQUEST_METHOD */', request)
                 .replace(' /* ACTUAL_DISPLAY_RETIRE_METHOD */', retirement))
    android = write('Android.kt', '''package android.os
class Looper { companion object { fun getMainLooper()=Looper() } }
class Handler(looper:Looper) {
 fun post(action:()->Unit):Boolean { queue.add(action);return true }
 companion object { val queue=mutableListOf<()->Unit>();fun drain(){while(queue.isNotEmpty())queue.removeAt(0)()} }
}
''')
    intent = write('Intent.kt', '''package android.content
class Intent { fun hasExtra(key:String)=false;fun getStringExtra(key:String):String?=null }
''')
    bridge = write('Bridge.kt', '''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex
import io.github.mesmerprism.rustyquest.spatial_camera_panel.*
import java.util.concurrent.CompletableFuture
object EmbeddedDuplexActivityAttachment {
 lateinit var pending:CompletableFuture<String>;lateinit var commit:()->String
 fun selectLocalAfterTerminal(context:Any,fence:LocalRollbackRequestFence,ticket:LocalRollbackRequestFence.Ticket,current:()->Long,alive:()->Boolean):CompletableFuture<String>{
  pending=CompletableFuture();commit={fence.commitIfCurrent(ticket,java.util.function.LongSupplier(current),java.util.function.BooleanSupplier(alive)) {
   OwnPackedPoolNative.selected=false
   "{\\"schema\\":\\"rusty.quest.local_rollback_native.v1\\",\\"config_sha256\\":\\"${"a".repeat(64)}\\",\\"app_verified_config_sha256\\":\\"${"a".repeat(64)}\\",\\"feature_enabled\\":false,\\"physical_cleanup\\":\\"terminal\\",\\"scope\\":\\"route-selection-only\\"}"
  }};return pending
 }
 fun finish(){try{pending.complete(commit())}catch(t:Throwable){pending.completeExceptionally(t)}}
}
''')
    jars = []
    for group, name, version in [('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.0'),
                                 ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.0'),
                                 ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.0'),
                                 ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                                 ('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.2.0'),
                                 ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                                 ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
                                 ('org.jetbrains', 'annotations', '13.0')]:
        jars.extend((args.kotlin_cache / group / name / version).glob('*/*.jar'))
    stdlib = next(path for path in jars if path.name.startswith('kotlin-stdlib-'))
    classpath = os.pathsep.join(map(str, [classes, stdlib, args.json_jar.resolve(strict=True)]))
    production = [source / name for name in ('LocalRollbackRequestFence.java',
                  'SpatialCameraHwbProjectionLaunchCoordinator.kt', 'SpatialVideoSourceRoutingCoordinator.kt')]
    sources = [pin(path) for path in [activity, display, fixture, pathlib.Path(__file__), *production]]
    dependencies = [pin(path) for path in [*jars, args.json_jar]]
    commands = []

    def run(name, argv, expected=0):
        started = time.monotonic()
        child = subprocess.Popen(list(map(str, argv)), stdin=subprocess.DEVNULL,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                 creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        stdout, stderr = child.communicate()
        (output / (name + '.stdout')).write_bytes(stdout)
        (output / (name + '.stderr')).write_bytes(stderr)
        row = dict(name=name, argv=list(map(str, argv)), pid=child.pid, exit=child.returncode,
                   expected_exit=expected, reaped=True, stdout_eof=True, stderr_eof=True,
                   elapsed_seconds=time.monotonic()-started, stdout=pin(output / (name+'.stdout')),
                   stderr=pin(output / (name+'.stderr')))
        commands.append(row)
        with (output / 'COMMANDS.json').open('w', encoding='utf-8') as stream:
            json.dump(commands, stream, indent=2)
        if child.returncode != expected:
            raise AssertionError(f'{name}: expected {expected}, actual {child.returncode}; retained streams')
        return stdout, stderr

    run('javac', [args.javac, '-d', classes, production[0]])
    run('kotlin', [args.java, '-cp', os.pathsep.join(map(str, jars)),
                  'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                  '-classpath', classpath, '-d', classes, android, intent, bridge, host, *production[1:]])
    cases = ('qualified', 'live-unknown', 'generic-unknown', 'native-denied', 'stale-retirement',
             'foreign-unknown', 'active-producer', 'cleanup-pending')
    for case in cases:
        stdout, stderr = run(case, [args.java, '-cp', classpath,
                                   'io.github.mesmerprism.rustyquest.spatial_camera_panel.LocalProducerRetirementHost', case],
                             1 if args.expect_red and case == 'qualified' else 0)
        if args.expect_red and case == 'qualified':
            assert b'retired embedded producer cache blocks qualified Local before Native provider admission' in stderr
        else:
            assert ('PASS ' + case).encode() in stdout
    for row in sources + dependencies:
        assert pin(row['path']) == row, 'Source/dependency drift'
    result = dict(status='expected-red' if args.expect_red else 'passed', cases=len(cases),
                  commands=commands, source_pins=sources, dependency_pins=dependencies,
                  request_method_sha256=hashlib.sha256(request.encode()).hexdigest(),
                  retirement_method_sha256=hashlib.sha256(retirement.encode()).hexdigest(),
                  modeled_leaves=['JNI concurrent/provider/removal readbacks and qualified Local owner proof',
                                  'Reader/decoder resources already terminal at extracted Display retirement call',
                                  'Android Handler/Intent, lifecycle dispatch and scene/camera/SDK launch callback'],
                  actual_bodies=['Activity requestProjectionSource', 'Display retirePeerProjection',
                                 'whole source routing coordinator', 'launch coordinator', 'Local rollback fence'],
                  limits='Host provider admission is not physical camera start, moving pixels, or whole-device acceptance.',
                  apk_built=False, device_calls=0)
    with (output / 'RESULT.json').open('x', encoding='utf-8') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(json.dumps(dict(status=result['status'], cases=len(cases), result=pin(output / 'RESULT.json'))))


if __name__ == '__main__':
    main()
