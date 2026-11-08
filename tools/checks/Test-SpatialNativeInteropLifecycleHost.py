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
policy = source.parent / 'SpatialNativeInteropDiagnosticPolicy.kt'
(out / policy.name).write_bytes(policy.read_bytes())
activity_text = (source.parent / 'SpatialCameraPanelActivity.kt').read_text()
activity_binding = activity_text[activity_text.index('  private val nativeInteropCoordinator:'):activity_text.index('  private val surfaceParticleParameterCoordinator')]
declarations = []
for name in ('nativeRecordNoRenderInteropReceipt', 'nativeRecordSelectedNoRenderInteropReceipt'):
    start = activity_text.index('  private external fun ' + name + '(')
    end = activity_text.index('  ): Long', start) + len('  ): Long')
    declarations.append(activity_text[start:end])
(out / 'ActivityJni.kt').write_text('package io.github.mesmerprism.rustyquest.spatial_camera_panel\nclass SpatialCameraPanelActivity {\n' + '\n'.join(declarations) + '\n}\n')
(out / 'Properties.kt').write_text('''package android.os
import io.github.mesmerprism.rustyquest.spatial_camera_panel.readRawProperty
object SystemProperties{@JvmStatic fun get(name:String,fallback:String)=readRawProperty(name)}
''')
(out / "Sdk.kt").write_text('''package com.meta.spatial.runtime
import io.github.mesmerprism.rustyquest.spatial_camera_panel.Events
class Scene
class SamplerConfig
class Surface(val isValid:Boolean=Events.surfaceValid)
class PanelSurface(scene:Scene,w:Int,h:Int,images:Int,sampler:SamplerConfig,a:Boolean,b:Boolean,name:String,c:Boolean){
  val surface=Surface()
  init{Events.add("sdk-surface-create");check(!Events.failCreate){"modeled SDK constructor failure"}}
  fun destroy(){Events.add("sdk-surface-destroy");check(!Events.failDestroy){"modeled SDK destroy failure"}}
}
''')
(out / "Controls.kt").write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel
import com.meta.spatial.runtime.Scene
object Events{val values=ArrayList<String>();var failCreate=false;var failDestroy=false;var surfaceValid=true;fun add(s:String){values+=s}}
const val NATIVE_RECEIPT_LIBRARY="fixture-not-loaded"
data class NativeInteropSurfaceProbeResult(val capability:String,val status:String,val surfaceValid:Boolean,val error:String)
class NativeInteropReceiptResult(val status:String,val mask:Long=0,val error:String="none")
class SpatialNativeInteropProbe{
 val openXrInstanceHandle=1L;val openXrSessionHandle=2L;val openXrGetInstanceProcAddrHandle=3L
 companion object{fun capture(scene:Scene)=SpatialNativeInteropProbe().also{Events.add("capture")}}
}
object SpatialOpenXrRouteModule{
 fun nativeReceiptLibraryLoadMarker(library:String,loaded:Boolean,error:String)="library"
 fun nativeInteropProbeMarker(phase:String,probe:SpatialNativeInteropProbe,surface:NativeInteropSurfaceProbeResult)="probe-result status=${surface.status} surfaceValid=${surface.surfaceValid} error=${surface.error}"
 fun nativeInteropReceiptMarker(phase:String,loaded:Boolean,receipt:NativeInteropReceiptResult)="receipt-result status=${receipt.status} mask=${receipt.mask} error=${receipt.error}"
 fun nativeInteropReceiptUnavailable(error:String)=NativeInteropReceiptResult("unavailable",error=error)
 fun nativeInteropReceiptReceived(mask:Long)=NativeInteropReceiptResult("received",mask)
 fun nativeInteropReceiptCallFailed(error:String)=NativeInteropReceiptResult("call-failed",error=error)
}
fun activityMarkerToken(s:String)=s
object BuildConfig{var DEBUG=true}
var diagnosticRaw="1"
var propertyReads=0;var propertyThrows=false
fun readRawProperty(name:String):String{check(name=="debug.rustyquest.spatial.native_interop_probe.mode");propertyReads++;check(!propertyThrows);return diagnosticRaw}
fun activityReadSystemProperty(name:String)=readRawProperty(name).trim()
class InputLeaf {fun requestMultimodalInputIfReady(p:SpatialNativeInteropProbe,s:String){Events.add("activity-multimodal")};fun startControllerActionsIfReady(p:SpatialNativeInteropProbe,s:String){Events.add("activity-controller")}}
class ActualActivityBinding {
 val scene=Scene();val nativeInputBootstrapCoordinator=InputLeaf()
 var failSelectedJni=false;var legacyCalls=0;val selectedCalls=ArrayList<Pair<Boolean,Boolean>>()
 fun marker(s:String){Events.add(s)}
 fun nativeRecordNoRenderInteropReceipt(i:Long,s:Long,p:Long,valid:Boolean):Long{legacyCalls++;Events.add("legacy-jni");return 0L}
 fun nativeRecordSelectedNoRenderInteropReceipt(i:Long,s:Long,p:Long,valid:Boolean,allowVulkan:Boolean):Long{check(i==1L&&s==2L&&p==3L);selectedCalls+=valid to allowVulkan;Events.add("selected-jni");if(failSelectedJni)throw UnsatisfiedLinkError("modeled missing selected JNI");return if(valid)1048576L else 0L}
''' + activity_binding + '''
 fun probe(phase:String="vr-ready",surface:Boolean=true,loaded:Boolean=true){val field=nativeInteropCoordinator.javaClass.getDeclaredField("receiptLibraryLoaded");field.isAccessible=true;field.setBoolean(nativeInteropCoordinator,loaded);nativeInteropCoordinator.logProbe(phase,surface)}
}
fun main(){
 // Literal expectations independently specify the four policy combinations.
 val cases=listOf(Triple("0",true,true),Triple("1",false,false),Triple("2",true,false),Triple("3",false,true))
 val tokens=listOf("full","borrowed-only","surface-only","vulkan-only")
 for((index,case)in cases.withIndex()){
   val(raw,surface,vulkan)=case;diagnosticRaw=raw;Events.values.clear();val a=ActualActivityBinding();val reads=propertyReads
   a.probe();check(propertyReads==reads+1&&a.selectedCalls==listOf(surface to vulkan)&&a.legacyCalls==0){"selected JNI arguments or construction-time read wrong for $raw"}
   check(Events.values.count{it=="capture"}==1&&Events.values.count{it=="sdk-surface-create"}==(if(surface)1 else 0)&&Events.values.count{it=="sdk-surface-destroy"}==(if(surface)1 else 0)){"mode $raw changed handle capture or Surface allocation"}
   val marker=Events.values.single{it.startsWith("channel=native-interop-allocation-mode ")}
   check(marker.contains("probeMode=$index ")&&marker.contains("probeModeToken=${tokens[index]} ")&&marker.contains("requestedSurface=$surface ")&&marker.contains("requestedVulkan=$vulkan ")&&marker.contains("surfaceProbeAttempted=$surface ")&&marker.contains("surfaceProbeSkippedByMode=${!surface} ")){"allocation marker did not reflect actual selected mode: $marker"}
   check(Events.values.contains("activity-multimodal")&&Events.values.contains("activity-controller")){"mode $raw suppressed later input callbacks"}
   diagnosticRaw=if(raw=="0")"1" else "0";Events.values.clear();a.probe("scene-ready",false)
   check(propertyReads==reads+1&&a.selectedCalls.last()==(false to vulkan)&&Events.values.none{it.startsWith("sdk-surface-")}){"mode was not frozen or scene-ready constructed a Surface"}
   check(Events.values.any{it.contains(if(surface)"status=deferred-until-vr-ready" else "status=skipped-by-diagnostic-mode")})
   check(Events.values.filter{it.contains("channel=native-interop-lifecycle")}.all{it.contains("probeGeneration=2 ")})
   Events.values.clear();a.probe("later-run",true);check(a.selectedCalls.last()==(surface to vulkan)&&a.legacyCalls==0&&Events.values.contains("activity-controller")){"later callback changed frozen mode or lost input handoff"}
 }
 for(raw in listOf("","0","4","-1","01"," 1","1 ","+1","true","1.0")){
   diagnosticRaw=raw;Events.values.clear();val a=ActualActivityBinding();a.probe();check(a.selectedCalls.single()==(true to true)&&a.legacyCalls==0){"invalid/absent raw value did not preserve Full: $raw"}
 }
 propertyThrows=true;val throwingRead=ActualActivityBinding();throwingRead.probe();check(throwingRead.selectedCalls.single()==(true to true));propertyThrows=false
 check(SpatialNativeInteropDiagnosticPolicy.select(true,null)==SpatialNativeInteropDiagnosticMode.Full)
 BuildConfig.DEBUG=false;diagnosticRaw="1";val readsBeforeRelease=propertyReads;val release=ActualActivityBinding();release.probe();check(propertyReads==readsBeforeRelease&&release.selectedCalls.single()==(true to true)){"release read or adopted debug property"};BuildConfig.DEBUG=true
 diagnosticRaw="1";Events.values.clear();val missing=ActualActivityBinding();missing.failSelectedJni=true;missing.probe();check(missing.selectedCalls.single()==(false to false)&&missing.legacyCalls==0&&Events.values.any{it.contains("status=call-failed")&&it.contains("UnsatisfiedLinkError")}&&Events.values.contains("activity-controller")){"missing selected JNI fell back to legacy or suppressed callbacks"}
 Events.values.clear();val unloaded=ActualActivityBinding();unloaded.probe(loaded=false);check(unloaded.selectedCalls.isEmpty()&&unloaded.legacyCalls==0&&Events.values.contains("activity-controller"))
 diagnosticRaw="0";Events.values.clear();Events.failCreate=true;val failedSurface=ActualActivityBinding();failedSurface.probe();Events.failCreate=false
 check(failedSurface.selectedCalls.single()==(false to true)&&Events.values.count{it=="sdk-surface-create"}==1&&Events.values.none{it=="sdk-surface-destroy"||it.contains("status=created-destroyed-no-render")||it.contains("stage=surface-create-end ")}){"constructor failure fabricated valid/destroyed Surface"}
 check(Events.values.any{it.contains("surfaceProbeAttempted=true")&&it.contains("surfaceProbeStatus=unavailable")}&&Events.values.contains("activity-controller"))
 Events.values.clear();Events.surfaceValid=false;val invalidSurface=ActualActivityBinding();invalidSurface.probe();Events.surfaceValid=true;check(invalidSurface.selectedCalls.single()==(false to true)&&Events.values.count{it=="sdk-surface-destroy"}==1){"invalid SDK Surface was reported valid"}
 Events.values.clear();Events.failDestroy=true;val failedDestroy=ActualActivityBinding();check(runCatching{failedDestroy.probe()}.isFailure);Events.failDestroy=false
 check(failedDestroy.selectedCalls.isEmpty()&&Events.values.none{it.contains("stage=surface-destroy-end ")||it.contains("status=created-destroyed-no-render")}){"destroy failure fabricated successful destruction"}
 Events.values.clear()
 val bindings=SpatialNativeInteropBindings(Scene(),{_,_,_,_,allowVk->check(allowVk);Events.add("jni-receipt");1L},
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
 println("Actual interop policy/Activity/coordinator: four frozen exact modes, invalid/release Full, ordered handle/Surface/JNI stages, policy skips, truthful Surface failures, preserved input callbacks and no missing-JNI legacy fallback passed; modeled leaves, no device proof.")
}
''')
libs = sorted(a.kotlin_lib.glob("*.jar"))
stdlib = next(p for p in libs if p.name.startswith("kotlin-stdlib-"))
classes = out / "classes"
classes.mkdir()
commands = [
    ("compile", [str(a.java_home / "bin/java.exe"), "-cp", os.pathsep.join(map(str, libs)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", str(stdlib), "-d", str(classes), *map(str, out.glob("*.kt"))]),
    ("controls", [str(a.java_home / "bin/java.exe"), "-cp", os.pathsep.join(map(str, [classes, stdlib])), "io.github.mesmerprism.rustyquest.spatial_camera_panel.ControlsKt"]),
    ("jni-descriptors", [str(a.java_home / 'bin/javap.exe'), '-p', '-s', '-classpath', str(classes), 'io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraPanelActivity', 'io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialNativeInteropDiagnosticPolicy']),
]
(out / "source.json").write_text(json.dumps(dict(source=str(source.relative_to(root)), sha256=hashlib.sha256(source.read_bytes()).hexdigest(), sources=[dict(source=str(p.relative_to(root)), sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in (source, policy)], activity_binding_sha256=hashlib.sha256(activity_binding.encode()).hexdigest(), jni_declarations_sha256=hashlib.sha256('\n'.join(declarations).encode()).hexdigest()), indent=2))
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
    if name == 'jni-descriptors':
        import re
        signatures = child.stdout.decode()
        for method, descriptor in [('nativeRecordNoRenderInteropReceipt', '(JJJZ)J'), ('nativeRecordSelectedNoRenderInteropReceipt', '(JJJZZ)J')]:
            match = re.search(r'private final native long ' + method + r'\([^\n]*\);\s*descriptor: (\S+)', signatures)
            assert match and match.group(1) == descriptor, 'Compiled JNI descriptor mismatch: ' + method
        assert re.search(r'public static final .* select\([^\n]*\);', signatures), 'Policy select lacks stable static JVM entry'
        print('Actual Activity JNI declarations inspected: legacy (JJJZ)J and selected (JJJZZ)J; policy select static JVM entry present.')
