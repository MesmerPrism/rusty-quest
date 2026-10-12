"""Portable native latch and actual Java projection contracts; no device effects."""
import argparse
import hashlib
import json
import pathlib
import subprocess


def main():
    p=argparse.ArgumentParser()
    for name in ('repo-root','output-root'):
        p.add_argument('--'+name,type=pathlib.Path,required=True)
    p.add_argument('--rustc',required=True)
    for name in ('javac','java','json-jar'):p.add_argument('--'+name)
    p.add_argument('--native-only',action='store_true')
    a=p.parse_args();root=a.repo_root.resolve();out=a.output_root.resolve()
    if out.exists() or not out.is_relative_to(root/'target'):raise ValueError('Unused owned target required')
    out.mkdir(parents=True);b=root/'apps/spatial-camera-panel-android/native-receipt/src';j=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    checks=[]
    def run(name,argv):
        r=subprocess.run(argv,capture_output=True);(out/(name+'.stdout')).write_bytes(r.stdout);(out/(name+'.stderr')).write_bytes(r.stderr);checks.append({'name':name,'exit':r.returncode})
        if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace'))
    run('rustc',[a.rustc,'--edition=2021','--test',str(b/'stereo_renderer_terminal.rs'),'-o',str(out/'latch.exe')]);run('native-controls',[str(out/'latch.exe')])
    fixture=root/'tools/checks/fixtures/StereoRendererTerminalHost.java'
    if not a.native_only:
        if not all([a.javac,a.java,a.json_jar]):raise ValueError('Explicit Java/JSON dependencies required unless native-only selected')
        run('javac',[a.javac,'-encoding','UTF-8','-source','8','-target','8','-cp',a.json_jar,'-d',str(out),str(j/'StereoRendererTerminalStatus.java'),str(fixture)])
        run('java',[a.java,'-cp',str(out)+';'+a.json_jar,'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.StereoRendererTerminalHost'])
    source=(b/'camera_hwb_source_set.rs').read_text()
    claim=source.index('if !owner.claim.claim(route_generation)')
    begin=source.index('begin_after_owner_claim(')
    assert claim<begin<source.index('let spawn =',begin)
    assert source.index('failure(diagnostic, error')<source.index('let cleanup_result = owned.teardown()')
    assert source.index('record_cleanup(diagnostic, cleanup_result.is_ok()')<source.index('carrier_cleanup(cleanup_result.is_ok()')
    assert source.index('finish(diagnostic, result.as_ref()')<source.index('if let Err(error) = result')
    provider=root/'apps/spatial-camera-panel-android/app/src/debug/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/EmbeddedDuplexOperatorProvider.kt'
    contract=provider.with_name('EmbeddedDuplexOperatorContract.kt')
    assert 'BuildConfig.DEBUG' in provider.read_text() and 'callerIsShell(Binder.getCallingUid())' in provider.read_text()
    assert '"renderer_terminal_status" -> Route.RENDERER_TERMINAL_STATUS' in contract.read_text()
    assert 'Route.RENDERER_TERMINAL_STATUS' in provider.read_text() and 'putString("receipt",receipt)' in provider.read_text()
    manifest=root/'apps/spatial-camera-panel-android/app/src/debug/AndroidManifest.xml'
    assert 'android.permission.DUMP' in manifest.read_text()
    cpu=j/'ConcurrentStereoQualification.kt'
    controls=j.parent/'StereoBankControls.kt'
    body=cpu.read_text().split('@JvmStatic fun maskConfiguration(',1)[1].split('@JvmStatic fun mask(',1)[0]
    assert body.count('receipt("blend_config_read"')==2
    assert 'maskConfigurationSnapshot()' in body and 'maskReadback' not in body and 'requestConcurrentQualificationReadback' not in body
    assert 'rusty.quest.stereo.neutral_mask_config_receipt.v1' in body
    atomic=(b/'spatial_stereo_banks.rs').read_text().split('pub(crate) fn read_mask_policy()',1)[1].split('pub(crate) fn update_mask_policy',1)[0]
    assert '*CONTROL_POLICY.lock()' in atomic
    assert '"blend_config_read" -> Route.BLEND_CONFIG_READ' in contract.read_text()
    assert 'Route.BLEND_CONFIG_READ' in provider.read_text()
    assert 'route == Route.BLEND_CONFIG_READ' in contract.read_text()
    assert 'it[1] in 0L..1L' in controls.read_text()
    files=[b/'stereo_renderer_terminal.rs',b/'stereo_renderer_terminal_jni.rs',b/'camera_hwb_source_set.rs',j/'StereoRendererTerminalStatus.java',fixture,provider,contract,cpu,controls]
    (out/'RESULT.json').write_text(json.dumps({'status':'passed','java_projection_selected':not a.native_only,'checks':checks,'source_pins':{str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in files},'limits':'Production native latch and Java projection with source joins; native resources, actor context and provider callers modeled. Android JNI/provider target compilation and artifact/device adoption are separate.'},indent=2))
    print((out/'native-controls.stdout').read_text());
    if not a.native_only:print((out/'java.stdout').read_text())
    print('Owner claim/first-failure/cleanup/caller-gate source joins PASS')


if __name__=='__main__':main()
