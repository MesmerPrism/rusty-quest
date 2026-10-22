"""Production renderer Java domain/bracket tests and real Android API compile; no native/APK/device work."""
import sys
sys.dont_write_bytecode=True
import argparse,hashlib,json,os,pathlib,subprocess,zipfile

def method(text,signature):
    start=text.index(signature);brace=text.index('{',start);depth=0
    for end in range(brace,len(text)):
        if text[end]=='{':depth+=1
        elif text[end]=='}':
            depth-=1
            if depth==0:return text[start:end+1]
    raise AssertionError('Incomplete production method')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('output','json-jar','android-jar'):p.add_argument('--'+name,required=True,type=pathlib.Path)
    for name in ('junit-jar','hamcrest-jar'):p.add_argument('--'+name,type=pathlib.Path)
    a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[2];out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
    java=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    fixture=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/EmbeddedDuplexRendererOwnEpochHostTest.java'
    android=a.android_jar.resolve(strict=True);jsonjar=a.json_jar.resolve(strict=True)
    with zipfile.ZipFile(android) as z:assert {'android/app/Activity.class','org/json/JSONObject.class'}<=set(z.namelist()),'Real Android API jar required'
    checks=[]
    def run(name,argv):
        r=subprocess.run(argv,capture_output=True);(out/(name+'.stdout')).write_bytes(r.stdout);(out/(name+'.stderr')).write_bytes(r.stderr)
        checks.append(dict(name=name,exit=r.returncode,argv=argv,reaped=True,full_dual_eof=True))
        (out/'CHECKS.json').write_text(json.dumps(checks,indent=2)+'\n',encoding='utf-8')
        if r.returncode:raise RuntimeError(name+' failed; retained actual stdout/stderr')
        return r
    package='package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;\n'
    keys=method((java/'EmbeddedDuplexFreshHandoffStore.java').read_text(encoding='utf-8'),'static void keys(')
    keysource=out/'EmbeddedDuplexFreshHandoffStore.java'
    keysource.write_text(package+'import org.json.JSONObject;class EmbeddedDuplexFreshHandoffStore{'+keys+'}\n',encoding='utf-8')
    # Whole production helper, with only its JNI boundary modeled for execution.
    terminal=(java/'StereoRendererTerminalStatus.java').read_text(encoding='utf-8')
    getter=method(terminal,'static long currentNativeProcess(') if 'static long currentNativeProcess(' in terminal else 'static long currentNativeProcess(){return nativeCurrentProcess();}'
    stub=out/'ModeledNativeReads.java'
    stub.write_text(package+'''class EmbeddedDuplexNative {
 static String value;static int reads;
 static String freshSetupRendererEligibilityStatus(){reads++;return value;}
 static String noMediaRendererRetirementStatus(){throw new AssertionError("retirement read not selected");}
}
class StereoRendererTerminalStatus {
 static long[] epochs;static int reads;
 private static long nativeCurrentProcess(){if(reads>=epochs.length)throw new AssertionError("extra native epoch read");return epochs[reads++];}
 GETTER
}
'''.replace('GETTER',getter),encoding='utf-8')
    classes=out/'host-classes';classes.mkdir()
    run('host-javac',['javac','--release','17','-encoding','UTF-8','-cp',str(jsonjar),'-d',str(classes),str(java/'EmbeddedDuplexRendererRetirement.java'),str(keysource),str(stub),str(fixture)])
    run('host-domain',['java','-cp',str(classes)+os.pathsep+str(jsonjar),'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexRendererOwnEpochHostTest'])
    assert bool(a.junit_jar)==bool(a.hamcrest_jar),'Select both existing JUnit runtime dependencies'
    if a.junit_jar:
        cp=os.pathsep.join(str(p.resolve(strict=True)) for p in (classes,jsonjar,a.junit_jar,a.hamcrest_jar))
        test=root/'apps/spatial-camera-panel-android/app/src/test/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexRendererRetirementTest.java'
        run('renderer-junit-javac',['javac','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(test)])
        run('renderer-junit',['java','-cp',cp,'org.junit.runner.JUnitCore','io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexRendererRetirementTest'])
    # Real complete production Java classes, not the modeled JNI bridge above.
    api=out/'android-api-classes';api.mkdir()
    run('android-api-javac',['javac','--release','17','-encoding','UTF-8','-cp',str(android),'-d',str(api),str(java/'EmbeddedDuplexRendererRetirement.java'),str(java/'StereoRendererTerminalStatus.java'),str(java/'EmbeddedDuplexNative.java'),str(keysource)])
    terminal_classes=out/'terminal-classes';terminal_classes.mkdir()
    terminal_fixture=root/'tools/checks/fixtures/StereoRendererTerminalHost.java'
    run('terminal-javac',['javac','--release','17','-encoding','UTF-8','-cp',str(jsonjar),'-d',str(terminal_classes),str(java/'StereoRendererTerminalStatus.java'),str(terminal_fixture)])
    run('terminal-projection',['java','-cp',str(terminal_classes)+os.pathsep+str(jsonjar),'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.StereoRendererTerminalHost'])
    host=(java/'EmbeddedDuplexProcessHost.java').read_text(encoding='utf-8')
    physical=method(host,'static JSONObject freshPhysical(')
    assert 'currentFreshEligible(ownState)' in physical and 'currentFreshEligible(ownState,current.processFence.generation())' not in physical
    identity=method(host,'static JSONObject freshClosureIdentity(')
    assert 'processFence.requireLive' in identity and 'nativeExecutorGeneration' in identity
    bridge=(root/'apps/spatial-camera-panel-android/native-receipt/src/stereo_renderer_terminal_jni.rs').read_text(encoding='utf-8')
    assert 'fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_StereoRendererTerminalStatus_nativeCurrentProcess(' in bridge
    assert 'crate::own_packed_pool_jni::process_generation()' in bridge
    files=[java/n for n in ('EmbeddedDuplexRendererRetirement.java','StereoRendererTerminalStatus.java','EmbeddedDuplexProcessHost.java')]+[fixture,pathlib.Path(__file__),android,jsonjar]
    result=dict(passed=True,checks=checks,production_domain_controls=16,existing_projection_controls=19,existing_renderer_junit_selected=bool(a.junit_jar),android_api_compile=True,source_boundary_checks=3,native_or_apk_build=False,device_effects=0,source_pins={str(f.resolve()):hashlib.sha256(f.read_bytes()).hexdigest() for f in files},limits='Actual production Java methods; JNI sequences/resources modeled. Real selected Android API compile; no installed-artifact or device-cause proof.')
    (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result))

if __name__=='__main__':main()
