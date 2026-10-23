"""Real product-shaped managed AAR graph; no APK/native/shared-cache execution.

Runs the complete production media Gradle script with a tiny host Java payload,
then applies the unchanged production task classifier to actual resolved tasks.
The test hook inspects before any task executes; it is not an AGP/compiler proof.
"""
import sys
sys.dont_write_bytecode = True
import argparse, hashlib, json, os, pathlib, subprocess, zipfile

parser = argparse.ArgumentParser()
for name in ('repo-root', 'output-root', 'gradle', 'java-home', 'android-jar'):
    parser.add_argument('--' + name, type=pathlib.Path, required=True)
parser.add_argument('--gradle-user-home', type=pathlib.Path)
a = parser.parse_args()
root, out = a.repo_root.resolve(), a.output_root.resolve()
if out.exists() or not out.is_relative_to(root / 'target'):
    raise ValueError('Fresh owner target output required')
for path in (a.gradle, a.java_home / 'bin/java.exe', a.android_jar):
    if not path.is_file(): raise ValueError('Selected host tool/input absent')
user_home=(a.gradle_user_home or (out/'gradle-user-home')).resolve()
if not user_home.is_relative_to(root/'target'): raise ValueError('Fixture-owned target Gradle home required')
out.mkdir(parents=True)
project = out / 'project'; project.mkdir()
media = project / 'media'; media.mkdir()
app = project / 'app'; app.mkdir()
production = root / 'crates/rusty-quest-media-stream-android/android/library'
observer = root / 'tools/gradle/android-component-closure.gradle'
media_source = production / 'build.gradle'
(media / 'build.gradle').write_bytes(media_source.read_bytes())
for name in ('AndroidManifest.xml', 'R.txt'):
    dest = media / 'src/main' / name; dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes((production / 'src/main' / name).read_bytes())
source = media / 'src/main/java/fixture/Media.java'; source.parent.mkdir(parents=True)
source.write_text('package fixture; public final class Media { public static int value() { return 7; } }\n')
source = app / 'src/main/java/fixture/App.java'; source.parent.mkdir(parents=True)
source.write_text('package fixture; public final class App { public static int value() { return 7; } }\n')
(project / 'settings.gradle').write_text("rootProject.name='selected-media-aar-graph-fixture'\ninclude(':app', ':media-stream-android')\nproject(':media-stream-android').projectDir=file('media')\n")
(project / 'gradle.properties').write_text('org.gradle.jvmargs=-Xmx512m\norg.gradle.daemon=false\n')
# Same owner policy implementation, byte-for-byte, plus a test-only resolved-graph
# inspection boundary. No production guard or task is replaced by a test model.
hook = out / 'owner-policy-and-test-hook.gradle'
metadata = out / 'selected-task-metadata.json'
hook.write_bytes(observer.read_bytes() + ("\n" + r"""
gradle.taskGraph.whenReady { graph ->
    def aar = rootProject.project(':media-stream-android').tasks.named('bundleReleaseAar').get()
    def observed = [path: aar.path, type: aar.class.name,
        archive_extension: aar.archiveExtension.get(), archive_filename: aar.archiveFileName.get(),
        archive_path: aar.archiveFile.get().asFile.canonicalPath,
        tasks: graph.allTasks.collect { [path: it.path, type: it.class.name] }]
    new File('__METADATA__').text = groovy.json.JsonOutput.toJson(observed)
}
// Match the production configuration-time dependency supplier. The ready hook
// above records metadata only; querying dependencies there is too late in Gradle.
def inspecting = new java.util.concurrent.ConcurrentHashMap<String, Boolean>()
rootProject.allprojects { owner ->
    owner.tasks.configureEach { selected ->
        selected.dependsOn {
            if (inspecting.putIfAbsent(selected.path, true) == null) {
                try { AndroidJvmTaskClosure.inspect(selected) }
                finally { inspecting.remove(selected.path) }
            }
            []
        }
    }
}
""").replace('__METADATA__', metadata.as_posix()).encode())
(project / 'build.gradle').write_text("apply from: '" + hook.as_posix() + "'\n")
app_base = """plugins { id 'java-library' }
dependencies { implementation files(project(':media-stream-android').layout.buildDirectory.file('outputs/aar/rusty-quest-media-stream-android-release.aar')) }
tasks.named('compileJava') { dependsOn(':media-stream-android:bundleReleaseAar') }
"""
(app / 'build.gradle').write_text(app_base)
env = os.environ.copy(); env['JAVA_HOME'] = str(a.java_home)
env['GRADLE_USER_HOME'] = str(user_home)
for key in list(env):
    if key.startswith('ORG_GRADLE_PROJECT_rqAndroidComponent'): del env[key]
command = [str(a.gradle), '--no-daemon', '--offline', '--no-configuration-cache', '--console=plain',
    '--gradle-user-home', str(user_home), '--project-cache-dir', str(out / 'project-cache'),
    '-p', str(project), '-PandroidJar=' + str(a.android_jar.resolve()), ':app:compileJava']
results = []
def pin(path):
    data = pathlib.Path(path).read_bytes()
    return dict(path=str(pathlib.Path(path).resolve()), bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
def run(name, failure=None):
    attempt=out/name; attempt.mkdir()
    metadata.unlink(missing_ok=True)
    child=subprocess.run(command, capture_output=True, env=env)
    (attempt/'stdout.bin').write_bytes(child.stdout); (attempt/'stderr.bin').write_bytes(child.stderr)
    (attempt/'process.json').write_text(json.dumps(dict(argv=command, exit_code=child.returncode,
        stdout=pin(attempt/'stdout.bin'), stderr=pin(attempt/'stderr.bin')), indent=2)+'\n')
    combined=child.stdout+child.stderr
    if failure:
        if child.returncode == 0 or b'component-early-task-graph' not in combined or failure.encode() not in combined:
            raise AssertionError('Required strict graph refusal absent: '+name)
    elif child.returncode:
        raise AssertionError('Production managed AAR graph rejected; actual logs retained: '+name)
    record=dict(name=name, exit_code=child.returncode, expected_refusal=failure, process=pin(attempt/'process.json'))
    if metadata.exists():
        (attempt/'task-metadata.json').write_bytes(metadata.read_bytes()); record['metadata']=pin(attempt/'task-metadata.json')
    results.append(record); print(name+': observed expected '+('refusal' if failure else 'success'), flush=True)
    return json.loads(metadata.read_text()) if metadata.exists() else None

observed=run('managed-aar')
assert observed['archive_extension']=='aar'
assert observed['archive_filename']=='rusty-quest-media-stream-android-release.aar'
assert observed['path']==':media-stream-android:bundleReleaseAar'
paths={r['path'] for r in observed['tasks']}
assert {':media-stream-android:compileJava',':media-stream-android:aarClassesJar',
    ':media-stream-android:bundleReleaseAar',':app:compileJava'} <= paths
artifact=pathlib.Path(observed['archive_path']); assert artifact.name=='rusty-quest-media-stream-android-release.aar'
with zipfile.ZipFile(artifact) as archive:
    assert sorted(archive.namelist())==['AndroidManifest.xml','R.txt','classes.jar']
    contents={name:hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist()}
# Explicit filename was already .aar. Compare actual bytes against the previous
# default-extension producer, with the guard disabled only in this TEST fixture.
# Production graph policy remains unchanged and is restored before negatives.
original=(media/'build.gradle').read_text(); old=original.replace('    archiveExtension = "aar"\n','')
assert old!=original
(project/'build.gradle').write_text('')
(media/'build.gradle').write_text(old)
baseline=out/'baseline-archive';baseline.mkdir()
child=subprocess.run(command[:-1]+[':media-stream-android:bundleReleaseAar','--rerun-tasks'],capture_output=True,env=env)
(baseline/'stdout.bin').write_bytes(child.stdout);(baseline/'stderr.bin').write_bytes(child.stderr)
assert child.returncode==0
with zipfile.ZipFile(artifact) as archive:
    assert {name:hashlib.sha256(archive.read(name)).hexdigest()for name in archive.namelist()}==contents
(baseline/'RESULT.json').write_text(json.dumps(dict(exit_code=0,entries_payload_identical=True,
    filename=artifact.name,scope='TEST-only original producer comparison; not early graph acceptance'))+'\n')
(media/'build.gradle').write_text(original)
(project/'build.gradle').write_text("apply from: '"+hook.as_posix()+"'\n")
for label,task in [
    ('extra-zip', "tasks.register('extraZip', Zip) { from('src/main'); destinationDirectory=layout.buildDirectory.dir('forbidden'); archiveFileName='extra.zip' }\ntasks.named('compileJava') { dependsOn('extraZip') }\n"),
    ('apk', "tasks.register('packageApk', Zip) { from('src/main'); destinationDirectory=layout.buildDirectory.dir('forbidden'); archiveExtension='apk'; archiveFileName='forbidden.apk' }\ntasks.named('compileJava') { dependsOn('packageApk') }\n"),
    ('native-finalizer', "tasks.register('nativeCompile', Exec) { commandLine '"+(a.java_home/'bin/java.exe').as_posix()+"','-version'; doFirst { file('native-sentinel').text='unexpected' } }\ntasks.named('compileJava') { finalizedBy('nativeCompile') }\n")]:
    (app/'build.gradle').write_text(app_base+task)
    run(label, {'extra-zip':':app:extraZip','apk':':app:packageApk','native-finalizer':':app:nativeCompile'}[label])
    assert not (app/'build/forbidden').exists() and not (app/'native-sentinel').exists()
assert not list(project.rglob('*.apk'))
(out/'RESULT.json').write_text(json.dumps(dict(status='passed',results=results,
    inputs=dict(media_gradle=pin(media_source),owner_policy=pin(observer),gradle=pin(a.gradle),java=pin(a.java_home/'bin/java.exe'),android_jar=pin(a.android_jar)),
    actual_managed_aar_tasks=True,archive_filename_unchanged=True,archive_entry_payloads_identical=True,
    strict_extra_zip_apk_native_refused=True,shared_cache_used=False,apk_or_native_compilation=False,device_calls=0,
    limits='Real Gradle task metadata/classifier and managed archive chain; tiny host Java payload, no actual app/AGP/Kotlin/media runtime proof.'),indent=2)+'\n')
print('Managed media AAR metadata/graph and strict refusal controls passed', flush=True)
