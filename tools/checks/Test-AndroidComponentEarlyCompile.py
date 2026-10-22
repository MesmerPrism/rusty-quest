"""Actual Android SDK/AGP/Kotlin early compilation; isolated targets, no APK/device."""
import sys
sys.dont_write_bytecode = True
import argparse, importlib.util, json, os, pathlib, subprocess

parser = argparse.ArgumentParser()
for name in ('repo-root', 'output-root', 'gradle', 'java-home', 'android-home', 'gradle-user-home'):
    parser.add_argument('--' + name, type=pathlib.Path, required=True)
parser.add_argument('--scenario', choices=('smoke', 'graph', 'all'), default='all')
args = parser.parse_args()
root, output = args.repo_root.resolve(), args.output_root.resolve()
if output.exists() or not output.is_relative_to(root / 'target'):
    raise ValueError('Fresh owner target required')
output.mkdir(parents=True)
project = output / 'project'; project.mkdir()
app = project / 'app'; app.mkdir()
private = app / 'owned'; private.mkdir()
java = app / 'src/main/java/fixture'; java.mkdir(parents=True)
resources = app / 'src/main/res/values'; resources.mkdir(parents=True)
support = project / 'support/src/main/java/support'; support.mkdir(parents=True)
spec = importlib.util.spec_from_file_location('closure', root / 'tools/lib/android_component_closure.py')
closure = importlib.util.module_from_spec(spec); spec.loader.exec_module(closure)

(project / 'settings.gradle').write_text("""pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name='selected-android-jvm-fixture'
include(':app', ':support')
""", encoding='utf-8')
(project / 'build.gradle').write_text("""plugins {
    id 'com.android.application' version '8.11.1' apply false
    id 'com.android.library' version '8.11.1' apply false
    id 'org.jetbrains.kotlin.android' version '2.1.0' apply false
    id 'org.jetbrains.kotlin.plugin.compose' version '2.1.0' apply false
}
""", encoding='utf-8')
(project / 'gradle.properties').write_text('org.gradle.jvmargs=-Xmx1536m\nandroid.useAndroidX=true\nkotlin.compiler.execution.strategy=in-process\n', encoding='utf-8')
(project / 'local.properties').write_text('sdk.dir=' + args.android_home.as_posix() + '\n', encoding='utf-8')
(app / 'src/main/AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application/></manifest>', encoding='utf-8')
(resources / 'strings.xml').write_text('<resources><string name="fixture">Fixture</string></resources>', encoding='utf-8')
(support.parent.parent / 'AndroidManifest.xml').write_text('<manifest/>', encoding='utf-8')
(support / 'Api.java').write_text('package support; public class Api { public static int value() { return android.os.Build.VERSION.SDK_INT; } }', encoding='utf-8')
(project / 'support/build.gradle').write_text("""plugins { id 'com.android.library' }
android { namespace 'support'; compileSdk 34; defaultConfig { minSdk 26 }; compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 } }
""", encoding='utf-8')
observer = (root / 'tools/gradle/android-component-closure.gradle').as_posix()
app_build = """plugins { id 'com.android.application'; id 'org.jetbrains.kotlin.android'; id 'org.jetbrains.kotlin.plugin.compose' }
android {
    namespace 'fixture'; compileSdk 34
    defaultConfig { applicationId 'fixture.preflight'; minSdk 26; targetSdk 34; buildConfigField 'int','VALUE','7' }
    buildFeatures { buildConfig true; compose true }
    compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget='17' }
    sourceSets.main.java.srcDir('owned')
}
dependencies { implementation project(':support'); implementation platform('androidx.compose:compose-bom:2024.09.03'); implementation 'androidx.compose.runtime:runtime' }
apply from: '""" + observer + "'\n"
(app / 'build.gradle').write_text(app_build, encoding='utf-8')
owner_text = 'package fixture\nobject Owner { @JvmStatic fun status(): Int = JavaBridge.value() }\n@androidx.compose.runtime.Composable fun Panel() {}\n'
java_text = 'package fixture; public class JavaBridge { public static int value() { return BuildConfig.VALUE + R.string.fixture + support.Api.value(); } public static int keys(org.json.JSONObject object) { return object.keys().hasNext() ? 1 : 0; } }'
(private / 'Owner.kt').write_text(owner_text, encoding='utf-8')
(java / 'JavaBridge.java').write_text(java_text, encoding='utf-8')
lock = project / 'feature-lock.json'
lock.write_text(json.dumps({'selected_features':['owner'], 'features':[{'feature_id':'owner','module_id':'owner','owner_lane':'fixture','selected':True}]}), encoding='utf-8')
contract = project / 'contract.json'
definitions = [{'root':'private','source':'Owner.kt','class':'fixture.Owner','methods':[{'name':'status','descriptor':'()I','public':True,'static':True}]}]
inventory_path = project / 'inventory.json'
evidence = project / 'compiler-evidence'
environment = os.environ.copy(); environment['JAVA_HOME'] = str(args.java_home)
environment['ANDROID_HOME'] = str(args.android_home); environment['ANDROID_SDK_ROOT'] = str(args.android_home)
environment['GRADLE_USER_HOME'] = str(args.gradle_user_home)
results = []

def select():
    contract.write_text(json.dumps({'schema':closure.SCHEMA,'components':[{'component_id':'owner','module_id':'owner','owner_lane':'fixture','definitions':definitions}]}), encoding='utf-8')
    value = closure.prepare(contract, lock, closure.file_sha(lock), {'application':app/'src', 'private':private}, 'debug')
    inventory_path.write_bytes(closure.canonical(value))
    return value

command = [str(args.gradle), '--no-daemon', '--configuration-cache', '--console=plain',
    '--gradle-user-home', str(args.gradle_user_home), '-p', str(project),
    '-PrqAndroidComponentInventory=' + str(inventory_path), '-PrqAndroidComponentEvidenceDir=' + str(evidence),
    '-PrqAndroidComponentKotlinTask=compileDebugKotlin', '-PrqAndroidComponentJavaTask=compileDebugJavaWithJavac',
    ':app:verifySelectedAndroidJvmCompilation']

def invoke(label, expected_error=None):
    inventory = select(); attempt = output / label; attempt.mkdir()
    (attempt / 'inventory.json').write_bytes(inventory_path.read_bytes())
    child = subprocess.run(command, capture_output=True, env=environment)
    (attempt / 'stdout.bin').write_bytes(child.stdout); (attempt / 'stderr.bin').write_bytes(child.stderr)
    (attempt / 'process.json').write_text(json.dumps({'exit_code':child.returncode,'argv':command,'java_home':str(args.java_home),'gradle_user_home':str(args.gradle_user_home)},indent=2),encoding='utf-8')
    if expected_error:
        if child.returncode == 0 or expected_error.lower().encode() not in (child.stdout + child.stderr).lower():
            raise AssertionError('Actual expected compiler/graph refusal absent at ' + label)
        if (project / 'native-sentinel').exists():
            raise AssertionError('Unexpected producer executed before early refusal')
        results.append({'step':label,'exit_code':child.returncode,'expected_error':expected_error,'native_sentinel':False})
        return None, child
    if child.returncode:
        raise AssertionError('Actual selected early Gradle compilation failed at ' + label + '; original logs retained')
    actual = closure.load(evidence / 'gradle-inputs.json')
    compiled = closure.verify_compiled(inventory, actual, closure.file_sha(inventory_path))
    (attempt / 'gradle-inputs.json').write_bytes((evidence / 'gradle-inputs.json').read_bytes())
    (attempt / 'compiled.json').write_bytes(closure.canonical(compiled))
    if any(output.rglob('*.apk')) or (project / 'native-sentinel').exists():
        raise AssertionError('Early JVM fixture produced an APK/native effect')
    results.append({'step':label,'exit_code':0,'sources':len(actual['sources']),'class_outputs':compiled['class_outputs'],
                    'kotlin_up_to_date':b':app:compileDebugKotlin UP-TO-DATE' in child.stdout,
                    'configuration_cache_reused':b'Reusing configuration cache' in child.stdout})
    return actual, child

actual, _ = invoke('initial')
assert {row['path'] for row in actual['compiler_tasks']} == {':app:compileDebugKotlin', ':app:compileDebugJavaWithJavac'}
assert any(pathlib.Path(row['path']).name == 'android.jar' and row['sha256'] == closure.file_sha(args.android_home/'platforms/android-34/android.jar') for row in actual['boot_classpath'])
assert any(pathlib.Path(row['path']).name == 'BuildConfig.java' for row in actual['generated_sources'])
assert actual['early_task_graph']
if args.scenario == 'all':
    (java/'JavaBridge.java').write_text(java_text.replace('object.keys().hasNext()', 'object.keySet().isEmpty()'), encoding='utf-8')
    invoke('java-sdk-refusal','cannot find symbol')
    (java/'JavaBridge.java').write_text(java_text, encoding='utf-8'); invoke('java-sdk-corrected')
    (private/'Owner.kt').write_text(owner_text.replace('JavaBridge.value()', 'android.os.Build.VERSION.NO_SUCH_ANDROID_MEMBER'), encoding='utf-8')
    invoke('kotlin-sdk-refusal','Unresolved reference')
    (private/'Owner.kt').write_text(owner_text, encoding='utf-8'); invoke('kotlin-sdk-corrected')
    (java/'JavaBridge.java').write_text(java_text.replace('static int value()', 'static String value()').replace('return BuildConfig.VALUE + R.string.fixture + support.Api.value();','return "wrong";'), encoding='utf-8')
    invoke('bridge-signature-refusal','type mismatch')
    (java/'JavaBridge.java').write_text(java_text, encoding='utf-8'); invoke('bridge-corrected')
    added = private/'Added.kt'; added.write_text('package fixture\nobject Added { @JvmStatic fun added() = JavaBridge.value() }\n', encoding='utf-8')
    definitions.append({'root':'private','source':'Added.kt','class':'fixture.Added','methods':[{'name':'added','descriptor':'()I','public':True,'static':True}]})
    invoke('warm-source-add')
    added.write_text('package fixture\nobject Added { @JvmStatic fun changed() = JavaBridge.value() + 1 }\n', encoding='utf-8')
    definitions[-1]['methods'][0]['name'] = 'changed'; invoke('warm-source-change')
    invoke('warm-unchanged'); _, cached = invoke('configuration-cache-repeat')
    assert b'Reusing configuration cache' in cached.stdout and b':app:compileDebugKotlin UP-TO-DATE' in cached.stdout
if args.scenario in ('graph', 'all'):
    sentinel = "tasks.register('innocentName') { doLast { file(rootProject.file('native-sentinel')).text='unexpected' } }\n"
    for label, edge in [('unexpected-dependency',"tasks.configureEach { if (name == 'compileDebugKotlin') dependsOn('innocentName') }"),
                        ('unexpected-finalizer',"tasks.configureEach { if (name == 'compileDebugJavaWithJavac') finalizedBy('innocentName') }")]:
        (app/'build.gradle').write_text(app_build + sentinel + edge + '\n', encoding='utf-8')
        invoke(label,'component-early-task-graph')
    (app/'build.gradle').write_text(app_build, encoding='utf-8'); invoke('restored-graph')
    (private/'Owner.kt').write_text(owner_text + '\n// stale selected bytes\n', encoding='utf-8')
    stale = subprocess.run(command,capture_output=True,env=environment)
    (output/'stale-source.stdout').write_bytes(stale.stdout);(output/'stale-source.stderr').write_bytes(stale.stderr)
    assert stale.returncode != 0 and b'component-compiler-input-source-closure' in stale.stdout + stale.stderr
    (private/'Owner.kt').write_text(owner_text, encoding='utf-8')
(output/'RESULT.json').write_text(json.dumps({'status':'passed','steps':results,'actual_android_sdk_agp_kotlin':True,
    'native_or_apk_execution':False,'device_calls':0,'observer_sha256':closure.file_sha(root/'tools/gradle/android-component-closure.gradle'),
    'inspector_sha256':closure.file_sha(root/'tools/lib/android_component_closure.py'),
    'limits':'Real isolated compiler/task graph fixture; canonical app adoption and APK/runtime verification are separate.'},indent=2), encoding='utf-8')
print('Actual Android early JVM compilation and selected graph controls passed')
