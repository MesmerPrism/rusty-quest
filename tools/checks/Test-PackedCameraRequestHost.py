#!/usr/bin/env python3
"""Real selector, Camera2 metadata projection and extracted production request setup.

Android resources/camera leaves are modeled; all shared Java sources compile separately
against Android API 36. No APK, camera/HAL behavior, timing or device acceptance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--json-jar', required=True)
    parser.add_argument('--android-jar', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = Path(args.output).resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    package = 'io/github/mesmerprism/rustyquest/media'
    source = root / 'crates/rusty-quest-media-stream-android/android/library/src/main/java'
    fixture = Path(__file__).with_name('fixtures') / 'PackedCameraRequestHost.java.in'
    owner = source / package / 'PackedStereoCaptureOwner.java'
    policy = source / package / 'PackedStereoCameraRequestPolicy.java'
    production = owner.read_text()
    setup = production[production.index('CaptureRequest.Builder request = device.createCaptureRequest('):]
    setup = setup[:setup.index('CaptureFrameTrace trace =')]
    generated = out / 'PackedCameraRequestHost.java'
    submission = production[production.index('int requestSequence = session.setRepeatingRequest('):]
    submission = submission[:submission.index('new CameraCaptureSession.CaptureCallback()')] + 'null, handler);'
    generated.write_text(fixture.read_text().replace('PRODUCTION_REQUEST_SETUP', setup)
                         .replace('PRODUCTION_SUBMISSION_CALL', submission))
    stubs = {
        'android/content/pm/ApplicationInfo.java': 'package android.content.pm; public class ApplicationInfo { public static final int FLAG_DEBUGGABLE=2; public int flags; }',
        'android/content/res/Resources.java': '''package android.content.res; public class Resources {
            public int value=3, id=1, reads; public boolean fail;
            public int getIdentifier(String name,String type,String pkg){reads++;if(fail)throw new IllegalStateException();
                if(!name.equals("rusty_quest_packed_camera_request_template")||!type.equals("integer")||!pkg.equals("host"))throw new AssertionError("resource route");return id;}
            public int getInteger(int id){if(fail)throw new IllegalStateException();return value;} }''',
        'android/content/Context.java': '''package android.content; public class Context {
            public final android.content.pm.ApplicationInfo info=new android.content.pm.ApplicationInfo();
            public final android.content.res.Resources resources=new android.content.res.Resources();
            public android.content.pm.ApplicationInfo getApplicationInfo(){return info;}
            public android.content.res.Resources getResources(){return resources;}
            public String getPackageName(){return "host";} }''',
        'android/util/Range.java': '''package android.util; public final class Range<T extends Comparable<T>> {
            private final T lo,hi; public Range(T l,T h){lo=l;hi=h;}public T getLower(){return lo;}public T getUpper(){return hi;}
            public boolean contains(T x){return lo.compareTo(x)<=0&&hi.compareTo(x)>=0;} }''',
        'android/hardware/camera2/CameraDevice.java': '''package android.hardware.camera2; public class CameraDevice {
            public static final int TEMPLATE_PREVIEW=1,TEMPLATE_RECORD=3;public int actual;
            public CaptureRequest.Builder createCaptureRequest(int template){actual=template;return new CaptureRequest.Builder(template);} }''',
        'android/hardware/camera2/CameraCharacteristics.java': '''package android.hardware.camera2;
            public class CameraCharacteristics {public static final Object CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES=new Object();
            public android.util.Range<Integer>[] ranges;public android.util.Range<Integer>[] get(Object k){return ranges;} }''',
        'android/hardware/camera2/CaptureRequest.java': '''package android.hardware.camera2;
            import android.util.Range;import java.util.HashMap;import java.util.Map;
            public class CaptureRequest {
            public static class Key<T>{}
            public static final Key<Integer> CONTROL_CAPTURE_INTENT=new Key<>(),NOISE_REDUCTION_MODE=new Key<>(),EDGE_MODE=new Key<>(),COLOR_CORRECTION_ABERRATION_MODE=new Key<>(),CONTROL_VIDEO_STABILIZATION_MODE=new Key<>();
            public static final Key<Range<Integer>> CONTROL_AE_TARGET_FPS_RANGE=new Key<>();
            final Map<Key<?>,Object> values=new HashMap<>();public Object target;public int setters;public boolean fail;
            @SuppressWarnings("unchecked") public <T>T get(Key<T> k){if(fail)throw new IllegalStateException();return (T)values.get(k);}
            public static class Builder {final CaptureRequest request=new CaptureRequest();
            public Builder(int t){request.values.put(CONTROL_CAPTURE_INTENT,t==1?1:3);}
            public void addTarget(Object s){request.target=s;}public <T>void set(Key<T> k,T v){request.setters++;request.values.put(k,v);}
            public CaptureRequest build(){CaptureRequest copy=new CaptureRequest();copy.values.putAll(request.values);copy.target=request.target;copy.setters=request.setters;return copy;} } }''',
        'android/hardware/camera2/CaptureResult.java': '''package android.hardware.camera2;
            import android.util.Range;import java.util.HashMap;import java.util.Map;
            public class CaptureResult {public static class Key<T>{}
            public static final Key<Integer> CONTROL_CAPTURE_INTENT=new Key<>(),NOISE_REDUCTION_MODE=new Key<>(),EDGE_MODE=new Key<>(),COLOR_CORRECTION_ABERRATION_MODE=new Key<>(),CONTROL_VIDEO_STABILIZATION_MODE=new Key<>();
            public static final Key<Range<Integer>> CONTROL_AE_TARGET_FPS_RANGE=new Key<>();
            public static final Key<Long> SENSOR_TIMESTAMP=new Key<>(),SENSOR_FRAME_DURATION=new Key<>(),SENSOR_EXPOSURE_TIME=new Key<>();
            public final Map<Key<?>,Object> values=new HashMap<>();public boolean fail;public long frame;
            @SuppressWarnings("unchecked") public <T>T get(Key<T> k){if(fail)throw new IllegalStateException();return (T)values.get(k);}
            public long getFrameNumber(){return frame;} }''',
        'android/hardware/camera2/TotalCaptureResult.java': 'package android.hardware.camera2; public class TotalCaptureResult extends CaptureResult {}',
    }
    files = []
    for relative, content in stubs.items():
        path = out / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        files.append(path)
    checks = []
    def ref(path):
        return dict(path=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest())
    def run(name, command):
        with (out / (name + '.stdout')).open('xb') as stdout, (out / (name + '.stderr')).open('xb') as stderr:
            result = subprocess.run(command, cwd=root, stdout=stdout, stderr=stderr, timeout=90)
        checks.append(dict(name=name, argv=command, exit=result.returncode,
                           stdout=ref(out / (name + '.stdout')), stderr=ref(out / (name + '.stderr'))))
        return result.returncode
    classes = out / 'classes'
    classes.mkdir()
    status = run('host-compile', ['javac', '--release', '8', '-encoding', 'UTF-8', '-Xlint:all', '-Werror',
                 '-cp', args.json_jar, '-d', str(classes), str(policy),
                 str(source / package / 'CaptureStallDiagnostics.java'), str(generated), *map(str, files)])
    if status == 0:
        status = run('request-controls', ['java', '-cp', str(classes) + os.pathsep + args.json_jar,
                     'io.github.mesmerprism.rustyquest.media.PackedCameraRequestHost', str(out / 'populated-camera2.json')])
    pins = [Path(__file__), fixture, owner]
    if policy.exists(): pins.append(policy)
    if status == 0:
        android_classes = out / 'android-classes'
        android_classes.mkdir()
        sources = sorted(source.rglob('*.java'))
        argfile = out / 'android-sources.args'
        argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in sources) + '\n')
        status = run('android-api-compile', ['javac', '--release', '8', '-encoding', 'UTF-8', '-cp', args.android_jar,
                     '-d', str(android_classes), '@' + str(argfile)])
        pins.extend(sources)
    receipt = out / 'RESULT.json'
    receipt.write_text(json.dumps(dict(passed=status == 0, checks=checks, source_pins=[ref(p) for p in pins],
                       dependencies=[ref(Path(args.json_jar)), ref(Path(args.android_jar))], limits=__doc__), indent=2) + '\n')
    print(json.dumps(dict(passed=status == 0, receipt=ref(receipt))))
    return status


if __name__ == '__main__':
    sys.exit(main())
