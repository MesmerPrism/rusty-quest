"""Compile current endpoint derivation against explicit retained owner libraries; no Cargo or Android effects."""
import argparse, hashlib, json, os, pathlib, subprocess, time


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--reference-native-command',type=pathlib.Path,required=True)
    p.add_argument('--assets',type=pathlib.Path,required=True)
    p.add_argument('--output',type=pathlib.Path,required=True)
    p.add_argument('--reference-java-command',type=pathlib.Path)
    p.add_argument('--reference-behavior-command',type=pathlib.Path)
    p.add_argument('--jni-library',type=pathlib.Path,help='Optional exact retained host JNI library for bridge API compilation')
    a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[2]
    out=a.output.resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
    production=root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex'
    fixture=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/EffectiveInputsHost.rs'
    modules=[production/(name+'.rs') for name in ('packaged_route','packaged_config','development_lifetime_policy','effective_inputs')]
    if a.jni_library:modules.append(production/'effective_inputs_jni.rs')
    generated=out/'probe.rs'
    generated.write_text('\n'.join('#[path='+json.dumps(x.as_posix())+'] mod '+x.stem+';' for x in modules)+'\n'+fixture.read_text(),encoding='utf-8')
    command=json.loads(a.reference_native_command.read_text(encoding='utf-8'))
    command[command.index('--crate-name')+1]='effective_inputs_host'
    command=[str(generated) if x.endswith('.rs') else x for x in command]
    binary=out/'effective-inputs-host.exe';command[command.index('-o')+1]=str(binary)
    if a.jni_library:command.extend(['--extern','jni='+str(a.jni_library.resolve())])
    checks=[];started=time.monotonic()
    for name,argv in [('compile',command),('behavior',[str(binary),str(a.assets.resolve())])]:
        result=subprocess.run(argv,cwd=root,capture_output=True,timeout=90,
                              env=dict(os.environ,RUSTY_QUEST_COMPILED_EMBEDDED_DUPLEX_LIFETIME_POLICY='development-v1'))
        (out/(name+'.command.json')).write_text(json.dumps(argv,indent=2),encoding='utf-8')
        (out/(name+'.stdout')).write_bytes(result.stdout);(out/(name+'.stderr')).write_bytes(result.stderr)
        checks.append(dict(name=name,exit=result.returncode))
        if result.returncode:
            print(result.stderr.decode(errors='replace')[-4000:]);break
    java_sources=[]
    generated_java=[]
    kotlin_sources=[]
    kotlin_recipe=None
    if all(x['exit']==0 for x in checks) and a.reference_java_command:
        if not a.reference_behavior_command:raise ValueError('Explicit JVM behavior recipe required')
        classes=out/'classes';classes.mkdir()
        java_command=json.loads(a.reference_java_command.read_text())
        java_command[java_command.index('-d')+1]=str(classes)
        # Explicit host-only BuildConfig, matching the source-owned development
        # policy used by this fixture. Context/signer/identity leaves are not entered.
        build_config=out/'BuildConfig.java'
        policy=json.dumps({'$schema':'rusty.manifold.development.lifetime_policy.v1',
            'policy_id':'policy.manifold.development-lifetime.v1','delegated_authority_horizon_ms':2592000000},separators=(',',':'))
        build_config.write_text('package io.github.mesmerprism.rustyquest.spatial_camera_panel;\npublic final class BuildConfig {\n'
            'public static final boolean EMBEDDED_DUPLEX_PRODUCT_INPUTS_ENABLED=false;\n'
            'public static final String EMBEDDED_DUPLEX_PRODUCT_MANIFEST_SHA256="";\n'
            'public static final String EMBEDDED_DUPLEX_DEVELOPMENT_LIFETIME_JSON='+json.dumps(policy)+';\n'
            'public static final long EMBEDDED_DUPLEX_SETUP_HORIZON_MS=2592000000L;\n}\n',encoding='utf-8')
        java_command=[str(build_config) if x.endswith('BuildConfig.java') else x for x in java_command]
        generated_java=[build_config]
        java_root=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
        java_sources=[java_root/(name+'.java') for name in ('FrozenEffectiveInputs','EmbeddedDuplexNative',
            'EmbeddedDuplexEffectiveInputOwner','EmbeddedDuplexRunContext','EmbeddedDuplexEnrollment','EmbeddedDuplexEnrollmentDraft',
            'EmbeddedDuplexEnrollmentFence','EmbeddedDuplexEnrollmentReview','EmbeddedDuplexEnrollmentStatus','EmbeddedDuplexEnrollmentResolver')]
        java_sources.append(root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/FrozenEffectiveInputsHost.java')
        old_behavior=json.loads(a.reference_behavior_command.read_text())
        kotlin_classes=out/'kotlin-classes';kotlin_classes.mkdir()
        cp=os.pathsep.join([str(classes),str(kotlin_classes),old_behavior[old_behavior.index('-cp')+1],java_command[java_command.index('-cp')+1]])
        kotlin_recipe=a.reference_behavior_command.with_name('kotlin.command.json')
        kotlin_command=[x for x in json.loads(kotlin_recipe.read_text()) if not x.endswith('.kt')]
        kotlin_command[kotlin_command.index('-d')+1]=str(kotlin_classes)
        kotlin_command[kotlin_command.index('-classpath')+1]=cp
        kotlin_sources=[java_root.parent/(name+'.kt') for name in ('SpatialPeerNetworkProfilePilot',
            'SpatialCameraControlProfile','SpatialDevelopmentModuleDirective','SpatialDevelopmentModuleDirectiveContract')]
        kotlin_command.extend(str(x) for x in kotlin_sources)
        java_command[java_command.index('-cp')+1]=cp
        java_command.extend(str(x) for x in java_sources)
        java_sources=[pathlib.Path(x) for x in java_command if x.endswith('.java')]
        for name,argv in [('kotlin',kotlin_command),('java',java_command),('resident-jvm',[old_behavior[0],'-cp',cp,'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.FrozenEffectiveInputsHost',str(a.assets.resolve()),str(binary)])]:
            result=subprocess.run(argv,cwd=root,capture_output=True,timeout=90)
            (out/(name+'.command.json')).write_text(json.dumps(argv,indent=2))
            (out/(name+'.stdout')).write_bytes(result.stdout);(out/(name+'.stderr')).write_bytes(result.stderr)
            checks.append(dict(name=name,exit=result.returncode))
            if result.returncode:print(result.stderr.decode(errors='replace')[-4000:]);break
    def pin(path):
        return dict(path=str(path),sha256=hashlib.sha256(path.read_bytes()).hexdigest())
    dependencies=[pathlib.Path(x.split('=',1)[1]) for x in command if '=' in x and x.split('=',1)[1].endswith('.rlib')]
    passed=all(x['exit']==0 for x in checks) and len(checks)==(5 if a.reference_java_command else 2)
    receipt=dict(passed=passed,checks=checks,elapsed_seconds=round(time.monotonic()-started,3),
        source_pins=[pin(x) for x in [*modules,fixture,*java_sources,*kotlin_sources,pathlib.Path(__file__)]],
        assets=[pin(x) for x in sorted(a.assets.glob('*.json'))],
        reference_command=pin(a.reference_native_command),
        generated_pins=[pin(x) for x in [generated,*generated_java]],
        retained_owner_dependency_pins=[pin(x) for x in dependencies],
        reference_java_command=pin(a.reference_java_command) if a.reference_java_command else None,
        reference_behavior_command=pin(a.reference_behavior_command) if a.reference_behavior_command else None,
        reference_kotlin_command=pin(kotlin_recipe) if kotlin_recipe else None,
        limits='Current native derivation/JNI API compiled on host; actual resident JVM loader/selector/Bootstrap/public-enrollment projection. Explicit retained owner-library recipe. Android Context/key store, private provider, process-fenced JNI dispatch and sockets/media/device not entered; no current full Cargo/APK proof.')
    (out/'RESULT.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
    print(json.dumps(dict(passed=passed,checks=checks,elapsed_seconds=receipt['elapsed_seconds'],result=str(out/'RESULT.json'))));return 0 if passed else 1


if __name__=='__main__':raise SystemExit(main())
