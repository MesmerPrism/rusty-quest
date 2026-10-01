"""Compile real media against both APIs; inject only Android/native effects for Stop ordering."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess

parser = argparse.ArgumentParser()
for name in ("repo-root", "java-home", "android-api33-jar", "android-api34-jar", "host-json-jar", "output-directory"):
    parser.add_argument("--" + name, required=True)
parser.add_argument("--preimage-commit", required=True)
args = parser.parse_args()
root = Path(args.repo_root).resolve()
out = Path(args.output_directory).resolve()
out.mkdir(exist_ok=False)
media = root / "crates/rusty-quest-media-stream-android/android/library/src/main/java"
runtime_path = media / "io/github/mesmerprism/rustyquest/media/PackedStereoMediaSourceRuntime.java"
java = Path(args.java_home) / "bin"
suffix = ".exe" if os.name == "nt" else ""

def run(label, argv, timeout=60):
    process = subprocess.run([str(a) for a in argv], capture_output=True, text=True, timeout=timeout)
    (out / (label + ".stdout")).write_text(process.stdout, encoding="utf-8")
    (out / (label + ".stderr")).write_text(process.stderr, encoding="utf-8")
    if process.returncode:
        raise AssertionError((label, process.returncode, process.stdout, process.stderr))

sources = sorted(media.rglob("*.java"))
for api in (33, 34):
    jar = getattr(args, "android_api" + str(api) + "_jar")
    run("api" + str(api), [java / ("javac" + suffix), "--release", "8", "-Xlint:all", "-Werror",
                            "-cp", jar, "-d", out / ("api" + str(api)), *sources])
stubs = out / "effects"
stubs.mkdir()

def write(name, text):
    path = stubs / (name + ".java")
    path.write_text(text, encoding="utf-8")

write("Context", "package android.content; public class Context { public Context getApplicationContext(){return this;} }")
write("SystemClock", "package android.os; public final class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000;} public static long elapsedRealtimeNanos(){return System.nanoTime();} }")
write("Log", "package android.util; public final class Log { public static int i(String tag,String text){System.out.println(text);return 0;} }")
write("Surface", "package android.view; public class Surface { public int releases; public void release(){if(++releases!=1)throw new AssertionError(\"duplicate Surface release\");} }")
for name in ("EGLDisplay", "EGLSurface", "EGLContext", "EGLConfig"):
    write(name, "package android.opengl; public final class " + name + " {}")
write("EGL14", """package android.opengl; public final class EGL14 {
public static final EGLDisplay EGL_NO_DISPLAY=new EGLDisplay(); public static final EGLContext EGL_NO_CONTEXT=new EGLContext(); public static final EGLSurface EGL_NO_SURFACE=new EGLSurface();
public static EGLDisplay eglGetDisplay(int value){return new EGLDisplay();}
public static boolean eglInitialize(EGLDisplay d,int[] a,int b,int[] c,int e){return true;}
public static boolean eglChooseConfig(EGLDisplay d,int[] a,int b,EGLConfig[] c,int e,int f,int[] count,int h){c[0]=new EGLConfig();count[0]=1;return true;}
public static EGLContext eglCreateContext(EGLDisplay d,EGLConfig c,EGLContext share,int[] a,int b){return new EGLContext();}
public static EGLSurface eglCreateWindowSurface(EGLDisplay d,EGLConfig c,Object window,int[] a,int b){return new EGLSurface();}
public static boolean eglMakeCurrent(EGLDisplay d,EGLSurface a,EGLSurface b,EGLContext c){return true;}
public static boolean eglDestroySurface(EGLDisplay d,EGLSurface s){return true;} public static boolean eglDestroyContext(EGLDisplay d,EGLContext c){return true;} public static boolean eglReleaseThread(){return true;}
public static boolean eglSwapBuffers(EGLDisplay d,EGLSurface s){io.github.mesmerprism.rustyquest.media.EncoderStopDrainCase.submitBlocked();return true;}
}""")
write("EGLExt", "package android.opengl; public final class EGLExt {public static boolean eglPresentationTimeANDROID(EGLDisplay d,EGLSurface s,long pts){return true;} }")
write("GLES20", """package android.opengl; import java.nio.Buffer; public final class GLES20 {
public static int glCreateShader(int kind){return 1;} public static void glShaderSource(int shader,String text){} public static void glCompileShader(int shader){} public static void glGetShaderiv(int shader,int name,int[] values,int offset){values[offset]=1;}
public static int glCreateProgram(){return 1;} public static void glAttachShader(int program,int shader){} public static void glLinkProgram(int program){} public static void glGetProgramiv(int program,int name,int[] values,int offset){values[offset]=1;}
public static void glDeleteShader(int shader){} public static void glDeleteProgram(int program){} public static void glBindFramebuffer(int a,int b){} public static void glViewport(int a,int b,int c,int d){} public static void glDisable(int value){} public static void glUseProgram(int value){}
public static int glGetAttribLocation(int program,String name){return 1;} public static void glVertexAttribPointer(int a,int b,int c,boolean d,int e,Buffer f){} public static void glEnableVertexAttribArray(int value){} public static void glActiveTexture(int value){} public static void glBindTexture(int a,int b){}
public static int glGetUniformLocation(int program,String name){return 1;} public static void glUniform1i(int a,int b){} public static void glDrawArrays(int a,int b,int c){} public static int glGetError(){return 0;}
}""")
write("MediaCodec", """package android.media;
import java.nio.ByteBuffer; import java.util.concurrent.CountDownLatch; import io.github.mesmerprism.rustyquest.media.EncoderStopDrainCase;
public final class MediaCodec {
public static final CountDownLatch normalAttempt=new CountDownLatch(1);
public static final class BufferInfo { public int offset,size,flags; public long presentationTimeUs; }
public int releases,stops,destroyed,dequeueFailures,outputFailures,releaseFailures,releaseAttempts,stopDequeueFailures; public boolean eosBeforeRetirement,concurrentConsumer; private boolean taken,eos,eosTaken,outstanding; private int outstandingIndex;
public int dequeueOutputBuffer(BufferInfo info,long timeout){
if(outstanding)throw new AssertionError("successor dequeue before acquired output released");
EncoderStopDrainCase.drainObserved(); boolean stopping=EncoderStopDrainCase.stopping(); if(!stopping){normalAttempt.countDown();
if("dequeue-failure".equals(EncoderStopDrainCase.mode)&&dequeueFailures++==0)throw new IllegalStateException("injected dequeue failure");}
if(stopping&&"stop-dequeue-failure".equals(EncoderStopDrainCase.mode)&&stopDequeueFailures==0){stopDequeueFailures++;throw new IllegalStateException("injected stop dequeue failure");}
if(eos){if("eos-release-failure".equals(EncoderStopDrainCase.mode)&&!eosTaken){eosTaken=true;outstanding=true;outstandingIndex=1;info.size=0;info.flags=4;return 1;}return -1;}
if(!taken&&(stopping||EncoderStopDrainCase.mode.startsWith("output-"))){taken=true;outstanding=true;outstandingIndex=0;info.size=0;info.flags=0;return 0;} return -1;}
public ByteBuffer getOutputBuffer(int index){if(EncoderStopDrainCase.mode.startsWith("output-")&&outputFailures++==0)throw new IllegalStateException("injected output failure");return null;}
public void releaseOutputBuffer(int index,boolean render){EncoderStopDrainCase.drainObserved();releaseAttempts++;if(("release-failure".equals(EncoderStopDrainCase.mode)||"output-and-release-failure".equals(EncoderStopDrainCase.mode)||"eos-release-failure".equals(EncoderStopDrainCase.mode)&&index==1)&&releaseFailures==0){releaseFailures++;throw new IllegalStateException("injected output release failure");}if(index!=outstandingIndex||!outstanding)throw new AssertionError("duplicate/foreign output release");releases++;outstanding=false;EncoderStopDrainCase.outputReleased.countDown();}
public void signalEndOfInputStream(){eosBeforeRetirement=!EncoderStopDrainCase.inputRetired();if(eosBeforeRetirement||outstanding)throw new AssertionError("EOS before positive input/output retirement");eos=true;}
public void stop(){if(!EncoderStopDrainCase.inputRetired()||outstanding)throw new AssertionError("codec stopped before input/output retirement");if(++stops!=1)throw new AssertionError("duplicate codec stop");}
public void release(){if(++destroyed!=1)throw new AssertionError("duplicate codec release");}
}""")

fixture = Path(__file__).with_name("EncoderStopDrainCase.java")
cp = os.pathsep.join((args.host_json_jar, str(out / "api34"), args.android_api34_jar))
host = out / "host"
run("host-compile", [java / ("javac" + suffix), "--release", "8", "-Xlint:all", "-Werror",
                     "-cp", cp, "-d", host, *sorted(stubs.glob("*.java")), fixture])
preimage = out / "preimage" / runtime_path.name
preimage.parent.mkdir()
blob = subprocess.check_output(["git", "-C", str(root), "show", args.preimage_commit + ":" + runtime_path.relative_to(root).as_posix()])
preimage.write_bytes(blob)
# Android effect types are compiled only for fixture execution. Real production
# compilation above uses the two actual platform jars without these effects.
run("preimage-compile", [java / ("javac" + suffix), "--release", "8", "-Xlint:all", "-Werror",
                         "-cp", cp, "-d", out / "preimage-classes", preimage])
main = "io.github.mesmerprism.rustyquest.media.EncoderStopDrainCase"
run("preimage-regression", [java / ("java" + suffix), "-cp", os.pathsep.join((str(host), str(out / "preimage-classes"), cp)), main, "baseline"], 30)
cases = ("normal", "permanent", "interrupted", "dequeue-failure", "output-failure", "stop-dequeue-failure", "release-failure", "startup-no-worker", "partial-source", "dead-source", "concurrent-stop", "output-and-release-failure", "eos-release-failure")
for case in cases:
    run(case, [java / ("java" + suffix), "-cp", str(host) + os.pathsep + cp, main, case], 30)
for case in ("output-failure", "output-and-release-failure"):
    assert "operation=ENCODER_GET_OUTPUT" in (out / (case + ".stdout")).read_text(), "primary processing operation was masked by output release"
result = {"status": "passed", "preimage_commit": args.preimage_commit,
          "preimage_source_sha256": hashlib.sha256(blob).hexdigest(),
          "source_hashes": [{"path": p.relative_to(root).as_posix(), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources],
          "fixture_hashes": [{"path": p.relative_to(root).as_posix(), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in (Path(__file__).resolve(), fixture)],
          "jar_sha256": {name: hashlib.sha256(Path(getattr(args, name)).read_bytes()).hexdigest() for name in ("android_api33_jar", "android_api34_jar", "host_json_jar")},
          "cases": ["preimage-regression", *cases],
          "scope": "actual Runtime/Pipeline/OwnerSet/EncoderWorker/CaptureOwner with injected codec backpressure and native input fence; API33/API34 --release8 -Xlint:all -Werror",
          "limits": "Android codec/EGL/GLES/Surface and native input effects are synthetic; no device, GPU, camera, physical completion or current Pending cause claim"}
(out / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"status": result["status"], "cases": result["cases"]}))
