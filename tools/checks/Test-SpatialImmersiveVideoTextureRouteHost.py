"""Actual opt-in route policy/Activity binding; SDK configuration leaves modeled."""
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
base = root / "apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel"
source = base / "SpatialImmersiveVideoPanelTextureRoute.kt"
activity = base / "SpatialCameraPanelActivity.kt"
if not source.exists():
    raise SystemExit("Missing production texture-route policy: default/diagnostic routing cannot be exercised")
text = activity.read_text()
start = text.index("  private fun immersiveVideoDiagnosticTextureRoute()")
brace = text.index("{", start)
depth, end = 1, brace + 1
while depth:
    depth += (text[end] == "{") - (text[end] == "}")
    end += 1
binding = text[start:end]
(out / source.name).write_bytes(source.read_bytes())
(out / "Runtime.kt").write_text('''package com.meta.spatial.runtime
enum class StereoMode{None,UpDown}
enum class PanelShapeType{QUAD,EQUIRECT}
data class LayerConfig(val zIndex:Int)
class PanelConfigOptions {
 var forceSceneTexture=false;var enableLayer=false;var layerConfig:LayerConfig?=null
 var radiusForCylinderOrSphere=0f;var stereoMode=StereoMode.None;var panelShapeType=PanelShapeType.QUAD
 var alphaMode="OPAQUE";var widthPx=3840;var heightPx=4320
}
''')
(out / "Toolkit.kt").write_text('''package com.meta.spatial.toolkit
import com.meta.spatial.runtime.PanelConfigOptions
interface MediaPanelShapeOptions {fun applyTo(options:PanelConfigOptions)}
''')
(out / "Controls.kt").write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel
import com.meta.spatial.runtime.*
import com.meta.spatial.toolkit.MediaPanelShapeOptions
object BuildConfig{var DEBUG=true}
class ActivityControl{var raw="";var reads=0
 fun activityReadSystemProperty(name:String):String{check(name==IMMERSIVE_VIDEO_TEXTURE_ROUTE_PROPERTY);reads++;return raw}
 fun select()=immersiveVideoDiagnosticTextureRoute()
''' + binding + '''
}
fun main(){
 val activity=ActivityControl()
 for((raw,want) in listOf("" to 0,"0" to 0,"1" to 1,"2" to 0,"invalid" to 0)){
  activity.raw=raw;check(activity.select()==want){"unexpected diagnostic selection for $raw"}
 }
 BuildConfig.DEBUG=false;activity.raw="1";val reads=activity.reads
 check(activity.select()==0&&activity.reads==reads){"release selection read or enabled diagnostic property"}
 val layer=LayerConfig(-41)
 val shape=object:MediaPanelShapeOptions{
  override fun applyTo(options:PanelConfigOptions){
   options.layerConfig=layer;options.radiusForCylinderOrSphere=2.025f
   options.stereoMode=StereoMode.UpDown;options.panelShapeType=PanelShapeType.EQUIRECT
  }
 }
 check(SpatialImmersiveVideoPanelTextureRoute.fromCode(2).code==0){"unsupported scene-mesh route was admitted"}
 for((code,wantTexture) in listOf(0 to false,1 to true)){
  val options=PanelConfigOptions()
  var observed:PanelConfigOptions?=null
  SpatialImmersiveVideoPanelTextureRoute.fromCode(code).wrapShape(shape){configured->
   check(configured.forceSceneTexture==wantTexture&&configured.layerConfig===layer){"route observed before configuration"}
   observed=configured
  }.applyTo(options)
  check(observed===options){"effective route observation did not identify configured options"}
  check(options.forceSceneTexture==wantTexture){"wrong SceneTexture route"}
  check(options.layerConfig===layer&&options.layerConfig!!.zIndex == -41){"opaque compositor layer was lost or reordered"}
  check(options.radiusForCylinderOrSphere==2.025f&&options.panelShapeType==PanelShapeType.EQUIRECT){"declared panoramic geometry changed"}
  check(options.stereoMode==StereoMode.UpDown){"top-bottom stereo changed"}
  check(options.alphaMode=="OPAQUE"&&options.widthPx==3840&&options.heightPx==4320){"occlusion or media extent changed"}
 }
 println("Actual texture-route policy and Activity binding: unchanged default/unknown input, debug-only selection, release inertness, retained layer/zIndex/geometry/UpDown/opacity/extent passed; modeled SDK config, no device proof.")
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
(out / "sources.json").write_text(json.dumps({"source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "activity_binding_sha256": hashlib.sha256(binding.encode()).hexdigest()}, indent=2))
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
