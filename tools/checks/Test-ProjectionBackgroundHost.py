"""Native background configuration/clear controls and exact bridge wiring; no GPU/device claim."""
import argparse, hashlib, json, os, pathlib, subprocess

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=pathlib.Path,required=True)
    parser.add_argument('--kotlin-lib',type=pathlib.Path)
    parser.add_argument('--java-home',type=pathlib.Path,default=pathlib.Path(os.environ['JAVA_HOME']) if 'JAVA_HOME' in os.environ else None)
    args=parser.parse_args();root=pathlib.Path(__file__).resolve().parents[2];out=args.output.resolve()
    if not out.is_relative_to(root/'target') or out.exists():raise ValueError('Fresh repository target output required')
    out.mkdir(parents=True)
    native=root/'apps/spatial-camera-panel-android/native-receipt/src'
    app=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/SpatialCameraPanelActivity.kt'
    paths=[native/'spatial_projection_background.rs',native/'camera_hwb_wsi.rs',native/'lib.rs',app,pathlib.Path(__file__).resolve()]
    checks=[]
    try:
        # This is the actual final render-pass initializer, not an alternate test renderer.
        wsi=(native/'camera_hwb_wsi.rs').read_text()
        assert 'float32: crate::spatial_projection_background::clear_color(opaque_clear)' in wsi, 'Final render-pass clear does not consume independent background configuration'
        assert 'let opaque_camera_only = latency_settings.enabled\n        && latency_settings.isolation_mode == CameraLatencyIsolationMode::OpaqueCameraOnly;' in wsi, 'Diagnostic isolation gate changed'
        assert 'let projected_by_public_stack = if opaque_camera_only {' in wsi, 'Processing route must remain controlled by diagnostic isolation alone'
        assert 'mod spatial_projection_background;' in (native/'lib.rs').read_text(), 'Native background module not registered'
        bridge=(native/'spatial_projection_background.rs').read_text()
        for method in ('nativeSetProjectionBackgroundBlack', 'nativeProjectionBackgroundBlack'):
            assert 'Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_'+method in bridge, 'Native Activity JNI export differs: '+method
        assert '.src_color_blend_factor(vk::BlendFactor::ONE)' in wsi and '.dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)' in wsi, 'Existing premultiplied projection blending required'
        activity=app.read_text()
        effects=activity[activity.index('  private fun applySpatialBackgroundEffects('):activity.index('  private fun setImmersiveVideoPresentationMode(',activity.index('  private fun applySpatialBackgroundEffects('))]
        assert 'configureNativeProjectionBackground(mode, source)' in effects, 'Existing Background setter does not configure Native background'
        assert 'nativeSetProjectionBackgroundBlack(black)' in activity and 'nativeProjectionBackgroundBlack()' in activity, 'Setter and independent Native flag readback required'
        assert 'nativeBackgroundGpuAdoptionProven=false' in activity, 'Configuration marker must not claim GPU adoption'
        checks.append(dict(name='actual_final_clear_and_independent_processing_bridge',passed=True))
        for name,argv in [('compile',[str(pathlib.Path.home()/'.cargo/bin/rustc.exe'),'--edition=2021','--test',str(paths[0]),'-o',str(out/'background-controls.exe')]),('controls',[str(out/'background-controls.exe'),'--nocapture'])]:
            result=subprocess.run(argv,capture_output=True,timeout=30)
            (out/(name+'.stdout')).write_bytes(result.stdout);(out/(name+'.stderr')).write_bytes(result.stderr)
            checks.append(dict(name=name,argv=argv,exit_code=result.returncode))
            if result.returncode:raise AssertionError(name+' failed')
        if args.kotlin_lib:
            assert args.java_home is not None, 'Select Java home for Kotlin controls'
            begin=activity.index('  private fun configureNativeProjectionBackground(')
            method=activity[begin:activity.index('  private fun setImmersiveVideoPresentationMode(',begin)]
            declarations='\n'.join(line.strip() for line in activity.splitlines() if line.strip().startswith(('private external fun nativeSetProjectionBackgroundBlack(', 'private external fun nativeProjectionBackgroundBlack(')))
            assert len(declarations.splitlines())==2, 'Exact Kotlin Native declarations required'
            enum=app.with_name('SpatialBackgroundMode.kt');paths.append(enum)
            fixture=out/'BackgroundControls.kt'
            fixture.write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel
fun activityMarkerToken(value:String)=value
private class NativeApi { ACTUAL_DECLARATIONS }
private class Host {
  val lines=mutableListOf<String>()
  var loaded=true;var configured=false;var setCalls=0;var getCalls=0;var getterFails=false
  private val nativeInteropCoordinator=object { val receiptLibraryLoaded:Boolean get()=loaded }
  private fun marker(value:String){lines.add(value)}
  private fun nativeSetProjectionBackgroundBlack(black:Boolean){setCalls++;configured=black}
  private fun nativeProjectionBackgroundBlack():Boolean {getCalls++;check(!getterFails);return configured}
  fun apply(mode:SpatialBackgroundMode){configureNativeProjectionBackground(mode,"host-control")}
ACTUAL_METHOD
}
fun main(){
 val host=Host()
 for(mode in listOf(SpatialBackgroundMode.Black,SpatialBackgroundMode.Passthrough,SpatialBackgroundMode.LutPassthrough,SpatialBackgroundMode.Video)){
  host.apply(mode);val black=mode==SpatialBackgroundMode.Black
  check(host.configured==black&&host.lines.last().contains("status=configured"))
  check(host.lines.last().contains("configuredBlack=$black"))
  check(host.lines.last().contains("nativeBackgroundGpuAdoptionProven=false"))
 }
 check(host.setCalls==4&&host.getCalls==4)
 host.loaded=false;host.apply(SpatialBackgroundMode.Black)
 check(host.setCalls==4&&host.getCalls==4&&host.lines.last().contains("status=unavailable"))
 host.loaded=true;host.getterFails=true;host.apply(SpatialBackgroundMode.Black)
 check(host.setCalls==5&&host.getCalls==5&&host.lines.last().contains("status=failed"))
 check(host.lines.last().contains("configuredBlack=unavailable"))
 println("PASS actual Kotlin setter/readback method: Black vs all other modes, missing library and failed readback; Native calls modeled, no GPU claim")
}
'''.replace('ACTUAL_METHOD',method).replace('ACTUAL_DECLARATIONS',declarations),encoding='utf-8')
            classes=out/'kotlin-classes';libs=list(args.kotlin_lib.glob('*.jar'))
            stdlib=next(path for path in libs if path.name.startswith('kotlin-stdlib-') and 'jdk' not in path.name)
            java=args.java_home/'bin/java.exe'
            operations=[('kotlin-compile',[str(java),'-cp',str(args.kotlin_lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',str(stdlib),'-d',str(classes),str(enum),str(fixture)]),('kotlin-controls',[str(java),'-cp',os.pathsep.join((str(classes),str(stdlib))),'io.github.mesmerprism.rustyquest.spatial_camera_panel.BackgroundControlsKt']),('native-signatures',[str(args.java_home/'bin/javap.exe'),'-classpath',str(classes),'-s','-p','io.github.mesmerprism.rustyquest.spatial_camera_panel.NativeApi'])]
            for name,argv in operations:
                result=subprocess.run(argv,capture_output=True,timeout=60)
                (out/(name+'.stdout')).write_bytes(result.stdout);(out/(name+'.stderr')).write_bytes(result.stderr)
                checks.append(dict(name=name,argv=argv,exit_code=result.returncode))
                if result.returncode:raise AssertionError(name+' failed')
                if name=='native-signatures':
                    assert b'descriptor: (Z)V' in result.stdout and b'descriptor: ()Z' in result.stdout, 'Actual Kotlin Native ABI differs'
        result=dict(passed=True,checks=checks,source_refs=[dict(path=str(p),sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in paths],scope=__doc__,device_calls=0,APK_builds=0)
        (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps(result,indent=2));return 0
    except (AssertionError,OSError) as error:
        (out/'FAILURE.json').write_text(json.dumps(dict(passed=False,error=str(error),checks=checks,scope=__doc__),indent=2)+'\n')
        print(str(error));return 1

if __name__=='__main__':raise SystemExit(main())
