"""Finite real AGP source-version generation/packaging fixture; no product/device."""
import sys
sys.dont_write_bytecode = True
import argparse, hashlib, importlib.util, json, os, pathlib, shutil, subprocess, zipfile

p = argparse.ArgumentParser()
for name in ('repo-root', 'output-root', 'gradle', 'java-home', 'android-home', 'gradle-user-home'):
    p.add_argument('--' + name, type=pathlib.Path, required=True)
a = p.parse_args(); root = a.repo_root.resolve(); out = a.output_root.resolve()
if out.exists() or not out.is_relative_to(root / 'target'):
    raise ValueError('Fresh owner target required')
out.mkdir(parents=True)
script = root / 'tools/gradle/spatial-source-version.gradle'
reader = root / 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/SpatialSourceVersion.java'
assert script.is_file() and reader.is_file(), 'Runtime source-version generation/reader absent'
project = out / 'project'; app = project / 'app'; src = app / 'src/main/java/fixture'
src.mkdir(parents=True)
(project / 'settings.gradle').write_text("pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }\nrootProject.name='source-version-fixture'\ninclude(':app')\n")
(project / 'build.gradle').write_text("plugins { id 'com.android.application' version '8.11.1' apply false; id 'org.jetbrains.kotlin.android' version '2.1.0' apply false }\n")
(project / 'gradle.properties').write_text('org.gradle.jvmargs=-Xmx1024m\nandroid.useAndroidX=true\nkotlin.compiler.execution.strategy=in-process\n')
(project / 'local.properties').write_text('sdk.dir=' + a.android_home.as_posix() + '\n')
(app / 'src/main/AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application/></manifest>')
# Use the actual production configuration section, not a parallel generator.
text = (root / 'apps/spatial-camera-panel-android/app/build.gradle.kts').read_text()
start = text.index('fun sourceCompositionVersionName(')
end = text.index('\nandroid {', start)
version_configuration = text[start:end]
(app / 'build.gradle.kts').write_text('plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }\n' + version_configuration + '''
android {
  namespace = "fixture"
  compileSdk = 34
  defaultConfig { applicationId = "fixture.sourceversion"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = spatialSourceVersionName }
  buildFeatures { buildConfig = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}
''' + 'apply(from = "' + script.as_posix() + '")\n')
destination = app / 'src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/SpatialSourceVersion.java'
destination.parent.mkdir(parents=True); destination.write_bytes(reader.read_bytes())
owner = src / 'Owner.java'; original = 'package fixture; public class Owner { public static int value() { return 1; } }\n'
owner.write_text(original)
(src / 'Consumer.kt').write_text('package fixture\nobject Consumer { @JvmStatic fun value() = Owner.value() }\n')
(src / 'Check.java').write_text('package fixture; public class Check { public static void main(String[] args) { System.out.print(BuildConfig.VERSION_NAME); } }\n')
env = os.environ.copy(); env['JAVA_HOME'] = str(a.java_home); env['ANDROID_HOME'] = str(a.android_home)
sha = lambda data: hashlib.sha256(data).hexdigest()
resource = 'META-INF/rusty-quest/source-version.v1.txt'; rows = []
spec = importlib.util.spec_from_file_location('version_closure', root / 'tools/lib/android_component_closure.py')
closure = importlib.util.module_from_spec(spec); spec.loader.exec_module(closure)
reader_definition = dict(root='application', source='SpatialSourceVersion.java', **{'class': 'io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialSourceVersion'},
    methods=[dict(name='read', descriptor='(Ljava/lang/ClassLoader;)Ljava/lang/String;', public=True, static=True)])

def run(label, fingerprint, enabled=True):
    evidence = out / label; evidence.mkdir()
    command = [str(a.gradle), '--no-daemon', '--configuration-cache', '--build-cache', '--console=plain',
               '--gradle-user-home', str(a.gradle_user_home), '-p', str(project),
               '-PrqDeveloperSourceRuntimeStamp=' + str(enabled).lower(),
               '-PrqDeveloperSourceCompositionFingerprint=' + fingerprint, ':app:assembleDebug']
    child = subprocess.run(command, capture_output=True, env=env)
    (evidence / 'stdout').write_bytes(child.stdout); (evidence / 'stderr').write_bytes(child.stderr)
    assert child.returncode == 0, 'Actual AGP fixture failed at ' + label
    apk = app / 'build/outputs/apk/debug/app-debug.apk'
    with zipfile.ZipFile(apk) as z:
        entries = [x for x in z.infolist() if x.filename == resource]
        assert len(entries) == (1 if enabled else 0)
        stamp = z.read(entries[0]) if enabled else None
    expected = '0.1.0+src.' + fingerprint if fingerprint else '0.1.0'
    if enabled: assert stamp == (expected + '\n').encode()
    generated = next((app / 'build/generated/source/buildConfig/debug').rglob('BuildConfig.java'))
    raw = generated.read_bytes(); (evidence / 'BuildConfig.java').write_bytes(raw)
    classes = app / 'build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
    cp = str(classes) + os.pathsep + str(apk)
    java = a.java_home / 'bin/java.exe'
    check = subprocess.run([str(java), '-cp', cp, 'fixture.Check'], capture_output=True)
    (evidence / 'field.stdout').write_bytes(check.stdout); (evidence / 'field.stderr').write_bytes(check.stderr)
    assert check.returncode == 0 and check.stdout.decode() == expected, 'Field value differs from source stamp'
    # Ordinary Android package readback through the selected SDK tool.
    aapt = a.android_home / 'build-tools/36.0.0/aapt2.exe'
    badging = subprocess.run([str(aapt), 'dump', 'badging', str(apk)], capture_output=True)
    (evidence / 'badging.stdout').write_bytes(badging.stdout); (evidence / 'badging.stderr').write_bytes(badging.stderr)
    assert badging.returncode == 0 and ("versionName='" + expected + "'").encode() in badging.stdout
    # Reuse ordinary source-version badging/resource joins, not a second acceptance rule.
    quoted = lambda value: "'" + str(value).replace("'", "''") + "'"
    ps = 'Import-Module ' + quoted(root / 'tools/lib/SpatialSourceVersion.psm1') + ' -Force; '
    ps += "$m=New-SpatialSourceVersionMetadata " + quoted(fingerprint or 'f' * 64) + ' -Enabled:$' + str(bool(fingerprint)).lower() + '; '
    ps += '$v=Assert-SpatialSourceVersionBadging ([IO.File]::ReadAllText(' + quoted(evidence / 'badging.stdout') + ")) 'fixture.sourceversion' $m; "
    ps += '$stamp=Assert-SpatialSourceVersionResource ' + quoted(apk) + ' $m $' + str(enabled).lower() + '; '
    ps += '@{version=$v;runtime_stamp=$stamp}|ConvertTo-Json -Depth 8'
    inspection = subprocess.run(['pwsh', '-NoProfile', '-Command', ps], capture_output=True)
    (evidence / 'inspection.stdout').write_bytes(inspection.stdout); (evidence / 'inspection.stderr').write_bytes(inspection.stderr)
    assert inspection.returncode == 0, 'Ordinary source-version inspection failed'
    observed_reader = closure.inspect_class((classes / (reader_definition['class'].replace('.', '/') + '.class')).read_bytes())
    closure.required_methods(reader_definition, observed_reader)
    closure.verify_dex(dict(definitions=[reader_definition]), apk)
    row = dict(step=label, enabled=enabled, fingerprint=fingerprint, build_config_sha256=sha(raw),
               owner_class_sha256=sha((classes / 'fixture/Owner.class').read_bytes()),
               kotlin_up_to_date=b':app:compileDebugKotlin UP-TO-DATE' in child.stdout,
               javac_up_to_date=b':app:compileDebugJavaWithJavac UP-TO-DATE' in child.stdout,
               configuration_reused=b'Reusing configuration cache' in child.stdout,
               resource=stamp.decode() if stamp else None, field_package_equal=True,
               ordinary_inspection_passed=True, reader_class_dex_abi_verified=True, apk_sha256=sha(apk.read_bytes()),
               child_exit_code=child.returncode, process_reaped=True)
    rows.append(row); print(json.dumps(row), flush=True)
    return row, classes, apk

first, classes, apk = run('initial', 'a' * 64)
changed, _, _ = run('metadata-change', 'b' * 64)
assert first['build_config_sha256'] == changed['build_config_sha256'], 'Metadata must not change generated compiler source'
assert changed['kotlin_up_to_date'] and changed['javac_up_to_date'], 'Metadata changed compiler tasks'
# AGP may read output versionName while configuring other tasks. Preserve and
# report that truthful miss; unchanged compiler inputs are the primary claim.
same, _, _ = run('metadata-unchanged', 'b' * 64)
assert same['configuration_reused'] and same['kotlin_up_to_date'] and same['javac_up_to_date']
owner.write_text(original.replace('return 1', 'return 2'))
edited, _, _ = run('code-edit', 'c' * 64)
assert edited['owner_class_sha256'] != changed['owner_class_sha256'] and not edited['javac_up_to_date']
owner.write_text(original)
reverted, _, _ = run('code-revert', 'a' * 64)
assert reverted['owner_class_sha256'] == first['owner_class_sha256']
# Real source API boundary: VERSION_NAME is no longer an annotation constant.
constant = out / 'ConstantUse.java'
constant.write_text('@interface Version { String value(); } @Version(fixture.BuildConfig.VERSION_NAME) class ConstantUse {}')
compile_result = subprocess.run([str(a.java_home / 'bin/javac.exe'), '-cp', str(classes), '-d', str(out / 'constant'), str(constant)], capture_output=True)
(out / 'constant.stderr').write_bytes(compile_result.stderr)
assert compile_result.returncode != 0 and b'constant' in compile_result.stderr
# Same real reader/BuildConfig class initialization; missing/malformed/shadow collisions fail.
for label, payload, include_apk in [('missing', None, False), ('malformed', b'wrong\n', False), ('shadowed', b'0.1.0+src.' + b'd' * 64 + b'\n', True)]:
    paths = [str(classes)]
    if payload is not None:
        jar = out / (label + '.jar')
        with zipfile.ZipFile(jar, 'w') as z: z.writestr(resource, payload)
        paths.append(str(jar))
    if include_apk: paths.append(str(apk))
    check = subprocess.run([str(a.java_home / 'bin/java.exe'), '-cp', os.pathsep.join(paths), 'fixture.Check'], capture_output=True)
    (out / (label + '.stderr')).write_bytes(check.stderr)
    assert check.returncode != 0, label + ' resource incorrectly accepted'
shutil.copyfile(apk, out / 'runtime-fixture.apk')
default, _, _ = run('normal-unstamped', '', False)
assert default['resource'] is None
legacy, _, _ = run('normal-stamped', 'e' * 64, False)
assert legacy['resource'] is None and legacy['build_config_sha256'] != default['build_config_sha256']
(out / 'RESULT.json').write_text(json.dumps(dict(status='passed', steps=rows,
    compile_time_constant_boundary_verified=True, missing_malformed_shadowed_rejected=True,
    source_pins={str(q.relative_to(root)): sha(q.read_bytes()) for q in [script, reader, root / 'apps/spatial-camera-panel-android/app/build.gradle.kts']},
    limits='Small AGP fixture APK + host JVM classloader only; no product APK, Android/ART classloader, install or device qualification.'), indent=2))
