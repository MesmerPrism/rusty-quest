"""Actual no-render interop coordinator with modeled SDK/JNI leaves; no APK/device."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True
parser = argparse.ArgumentParser()
for name in ("repo-root", "output-root", "java-home", "kotlin-lib"):
    parser.add_argument("--" + name, type=Path, required=True)
a = parser.parse_args()
root, out = a.repo_root.resolve(), a.output_root.resolve()
if out.exists() or not out.is_relative_to(root / "target"):
    raise ValueError("Fresh fixture-owned target required")
out.mkdir(parents=True)
source = root / "apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/SpatialNativeInteropCoordinator.kt"
(out / source.name).write_bytes(source.read_bytes())
(out / "Sdk.kt").write_text('''package com.meta.spatial.runtime
import io.github.mesmerprism.rustyquest.spatial_camera_panel.Events
class Scene
class SamplerConfig
class Surface(val isValid:Boolean=true)
class PanelSurface(scene:Scene,w:Int,h:Int,images:Int,sampler:SamplerConfig,a:Boolean,b:Boolean,name:String,c:Boolean){
  val surface=Surface()
  init{Events.add("sdk-surface-create")}
  fun destroy(){Events.add("sdk-surface-destroy")}
}
''')
(out / "Controls.kt").write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel
import com.meta.spatial.runtime.Scene
object Events{val values=ArrayList<String>();fun add(s:String){values+=s}}
const val NATIVE_RECEIPT_LIBRARY="fixture-not-loaded"
data class NativeInteropSurfaceProbeResult(val capability:String,val status:String,val surfaceValid:Boolean,val error:String)
class NativeInteropReceiptResult
class SpatialNativeInteropProbe{
 val openXrInstanceHandle=1L;val openXrSessionHandle=2L;val openXrGetInstanceProcAddrHandle=3L
 companion object{fun capture(scene:Scene)=SpatialNativeInteropProbe().also{Events.add("capture")}}
}
object SpatialOpenXrRouteModule{
 fun nativeReceiptLibraryLoadMarker(library:String,loaded:Boolean,error:String)="library"
 fun nativeInteropProbeMarker(phase:String,probe:SpatialNativeInteropProbe,surface:NativeInteropSurfaceProbeResult)="probe-result"
 fun nativeInteropReceiptMarker(phase:String,loaded:Boolean,receipt:NativeInteropReceiptResult)="receipt-result"
 fun nativeInteropReceiptUnavailable(error:String)=NativeInteropReceiptResult()
 fun nativeInteropReceiptReceived(mask:Long)=NativeInteropReceiptResult()
 fun nativeInteropReceiptCallFailed(error:String)=NativeInteropReceiptResult()
}
fun activityMarkerToken(s:String)=s
fun main(){
 val bindings=SpatialNativeInteropBindings(Scene(),{_,_,_,_->Events.add("jni-receipt");1L},
   {_,_->Events.add("multimodal")},{_,_->Events.add("controller")},{Events.add(it)})
 val c=SpatialNativeInteropCoordinator(bindings)
 // No production loading hook: simulate the already-loaded library boundary only.
 val loaded=c.javaClass.getDeclaredField("receiptLibraryLoaded");loaded.isAccessible=true;loaded.setBoolean(c,true)
 c.logProbe("vr-ready",true)
 val events=Events.values
 fun trace(stage:String)=events.indexOfFirst{it.contains("channel=native-interop-lifecycle")&&it.contains("stage=$stage ")}
 fun before(first:Int,last:Int,message:String){check(first>=0&&last>=0&&first<last){message+": "+events}}
 before(trace("probe-begin"),events.indexOf("capture"),"probe entry was not observable before capture")
 before(trace("surface-create-begin"),events.indexOf("sdk-surface-create"),"surface entry was not observable before SDK creation")
 before(events.indexOf("sdk-surface-create"),trace("surface-create-end"),"surface creation completion preceded SDK work")
 before(trace("surface-destroy-begin"),events.indexOf("sdk-surface-destroy"),"surface destruction entry was not observable")
 before(events.indexOf("sdk-surface-destroy"),trace("surface-destroy-end"),"surface destruction completion preceded SDK work")
 before(trace("receipt-begin"),events.indexOf("jni-receipt"),"JNI entry was not observable before Vulkan probe")
 before(events.indexOf("jni-receipt"),trace("receipt-end"),"JNI completion preceded Vulkan probe")
 before(events.indexOf("controller"),trace("probe-end"),"probe completion preceded controller input handoff")
 check(events.filter{it.contains("channel=native-interop-lifecycle")}.all{it.contains("probeGeneration=1 ")})
 Events.values.clear();c.logProbe("scene-ready",false)
 check(Events.values.none{it.startsWith("sdk-surface-")}){"scene-ready acquired a Surface"}
 check(Events.values.filter{it.contains("channel=native-interop-lifecycle")}.all{it.contains("probeGeneration=2 ")})
 println("Actual interop coordinator: observable entry/completion around SDK Surface and JNI receipt; per-probe epochs and deferred Surface passed; modeled leaves, no device proof.")
}
''')
libs = sorted(a.kotlin_lib.glob("*.jar"))
stdlib = next(p for p in libs if p.name.startswith("kotlin-stdlib-"))
classes = out / "classes"
classes.mkdir()
commands = [
    ("compile", [str(a.java_home / "bin/java.exe"), "-cp", os.pathsep.join(map(str, libs)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", str(stdlib), "-d", str(classes), *map(str, out.glob("*.kt"))]),
    ("controls", [str(a.java_home / "bin/java.exe"), "-cp", os.pathsep.join(map(str, [classes, stdlib])), "io.github.mesmerprism.rustyquest.spatial_camera_panel.ControlsKt"]),
]
(out / "source.json").write_text(json.dumps(dict(source=str(source.relative_to(root)), sha256=hashlib.sha256(source.read_bytes()).hexdigest()), indent=2))
for name, command in commands:
    child = subprocess.run(command, capture_output=True, timeout=60)
    (out / (name + ".stdout")).write_bytes(child.stdout)
    (out / (name + ".stderr")).write_bytes(child.stderr)
    (out / (name + ".process.json")).write_text(json.dumps(dict(argv=command, exit_code=child.returncode), indent=2))
    if child.returncode:
        sys.stdout.buffer.write(child.stdout)
        sys.stderr.buffer.write(child.stderr)
        raise SystemExit(child.returncode)
    if name == "controls":
        sys.stdout.buffer.write(child.stdout)
