#!/usr/bin/env python3
"""Compile complete production media wait callers; Android/SDK resources are modeled, no device/APK."""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('sdk-command', 'json-jar', 'junit-jar', 'hamcrest-jar', 'output'):
        p.add_argument('--' + name, required=True)
    p.add_argument('--receiver-only',action='store_true')
    a = p.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    out = pathlib.Path(a.output).resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    command = json.loads(pathlib.Path(a.sdk_command).read_text())
    java = command[0]
    javac = str(pathlib.Path(java).with_name('javac.exe' if os.name == 'nt' else 'javac'))
    compiler = command[command.index('-cp') + 1]
    dependencies = command[command.index('-classpath') + 1]
    support = [x for x in dependencies.split(os.pathsep) if pathlib.Path(x).is_dir()]
    classes = out / 'classes'
    classes.mkdir()
    cp = os.pathsep.join([str(classes), a.json_jar, a.junit_jar, a.hamcrest_jar, dependencies, compiler])
    app = root / 'apps/spatial-camera-panel-android'
    production = app / 'app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel'
    fixtures = app / 'host-tests/embedded_duplex'
    test = app / 'app/src/test/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexActivationGateTest.java'
    gate = production / 'embedded_duplex/EmbeddedDuplexActivationGate.java'
    coordinator = production / 'embedded_duplex/EmbeddedDuplexDisplayCoordinator.kt'
    receiver = root / 'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media/PackedStereoMediaReceiver.java'
    def pin(path):
        path = pathlib.Path(path)
        return {'path': str(path.resolve()), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    receiver_fixture = root / 'crates/rusty-quest-media-stream-android/android/library/src/test/java/io/github/mesmerprism/rustyquest/media/PreparedReceiverWaitHost.java'
    sources = [gate, coordinator, receiver, test, receiver_fixture, fixtures / 'OwnedDisplayWaitHost.kt', pathlib.Path(__file__)]
    if a.receiver_only:sources=[receiver,receiver_fixture,pathlib.Path(__file__)]
    before = [pin(x) for x in sources]
    dependency_files = {pathlib.Path(x) for x in (dependencies + os.pathsep + compiler).split(os.pathsep) if pathlib.Path(x).is_file()}
    for directory in support:
        dependency_files.update(pathlib.Path(directory).rglob('*.class'))
    dependency_pins = [pin(x) for x in sorted(dependency_files)]
    seams = {
        'Log.java': 'package android.util; public final class Log { public static int w(String t,String m){System.err.println(m);return 0;} public static int i(String t,String m){return 0;} }',
        'Looper.java': 'package android.os; public final class Looper {private static final Looper main=new Looper();private static final ThreadLocal<Boolean> isMain=new ThreadLocal<>();public static void setMainThread(boolean v){isMain.set(v);}public static Looper getMainLooper(){return main;}public static Looper myLooper(){return Boolean.TRUE.equals(isMain.get())?main:null;} }',
        'Surface.java': 'package android.view; public final class Surface { public boolean isValid(){return true;} }',
        'SystemClock.java': 'package android.os; public final class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}public static long elapsedRealtimeNanos(){return System.nanoTime();}public static void sleep(long m){try{Thread.sleep(m);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}',
        'HandlerThread.java': '''package android.os; public final class HandlerThread extends Thread {
          public java.util.concurrent.CountDownLatch hostRelease=new java.util.concurrent.CountDownLatch(0);public volatile int hostQuitCalls;
          public HandlerThread(String n){super(n);}public Looper getLooper(){return null;}public boolean quitSafely(){hostQuitCalls++;return true;}
          public void run(){boolean done=false;while(!done)try{hostRelease.await();done=true;}catch(InterruptedException ignored){}}
        }''',
        'GateControlsMain.java': '''import org.junit.runner.*;import org.junit.runner.notification.*;
          public final class GateControlsMain {public static void main(String[] args)throws Exception{
          Class<?> c=Class.forName("io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexActivationGateTest");
          String[] names={"sameActivationWaitsPastEstimateForActualAcquiredAndEffectiveFrames","pendingFrameWaitStillObservesActualStopAndExpiry","foreignAndStaleFramesRemainRejectedWithoutGraphAttachment","interruptedPendingFrameWaitRetainsTheSameUncertainClaim","exactArmAndProofActivateOnlyAfterFreshNativeReadback","diagnosticLogFailureCannotReplaceOriginalActivationFailure","wrongProofAndWrongArmLineageRejectBeforeGraph","cleanupFencePreventsLaterActivation","uncertainActivationRetainsTargetAndCannotReenter","cleanupCallbackCanRunWhileDisplayAwaitsAndFencesActivation","reusedNativeIdentityOrReceiverHandleCannotCompleteFreshArm","wrongTerminalHandleRevisionSubjectAndGenerationCannotGrantRestart"};
          int failures=0;for(String n:names){Result r=new JUnitCore().run(Request.method(c,n));for(Failure f:r.getFailures())System.err.println(f.getTrace());failures+=r.getFailureCount();}
          System.out.println("Gate controls="+names.length+" failures="+failures);if(failures!=0)System.exit(1);}}
        ''',
    }
    generated = []
    for name, text in seams.items():
        if a.receiver_only and name=='GateControlsMain.java':continue
        path = out / name
        path.write_text(text, encoding='utf-8')
        generated.append(path)
    def run(name, argv, timeout=65):
        arguments = out / (name + '.args')
        arguments.write_text('\n'.join(json.dumps(str(x).replace('\\', '/')) for x in argv[1:]), encoding='utf-8')
        result = subprocess.run([str(argv[0]), '@' + str(arguments)], cwd=root, capture_output=True, timeout=timeout)
        (out / (name + '.stdout')).write_bytes(result.stdout)
        (out / (name + '.stderr')).write_bytes(result.stderr)
        return result.returncode
    assert run('java', [javac, '--release', '17', '-cp', cp, '-d', classes, *generated, *([receiver,receiver_fixture] if a.receiver_only else [gate,receiver,receiver_fixture,test])]) == 0, 'Java compile; see retained streams'
    gate_exit=display_exit=0
    if not a.receiver_only:
        assert run('kotlin', [java, '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-Xfriend-paths=' + ','.join(support), '-d', classes, coordinator, fixtures / 'OwnedDisplayWaitHost.kt']) == 0, 'Kotlin compile; see retained streams'
        gate_exit = run('gate-controls', [java, '-cp', cp, 'GateControlsMain'])
        display_exit = run('display-controls', [java, '-cp', cp, 'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.OwnedDisplayWaitHost'])
    receiver_exit = run('receiver-controls', [java, '-cp', cp, 'io.github.mesmerprism.rustyquest.media.PreparedReceiverWaitHost'])
    for row in before + dependency_pins:
        assert pin(row['path']) == row, 'source changed during check'
    result = {'passed': gate_exit == display_exit == receiver_exit == 0, 'receiver_only':a.receiver_only, 'gate_exit': None if a.receiver_only else gate_exit, 'display_exit': None if a.receiver_only else display_exit, 'receiver_exit': receiver_exit, 'source_pins': before,
              'input_pins': [pin(x) for x in [a.sdk_command, java, javac, a.json_jar, a.junit_jar, a.hamcrest_jar]],
              'generated_pins': [pin(x) for x in generated], 'limits': __doc__}
    result['dependency_pins'] = dependency_pins
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2))
    print(json.dumps({k: result[k] for k in ('passed', 'gate_exit', 'display_exit', 'receiver_exit')}))
    return gate_exit or display_exit or receiver_exit


if __name__ == '__main__':
    sys.exit(main())
