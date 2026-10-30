"""Real finite JVM working-path/immutable-evidence controls; no APK or device."""
import sys
sys.dont_write_bytecode = True
import argparse, hashlib, importlib.util, json, os, pathlib, subprocess, time

p = argparse.ArgumentParser()
for name in ('repo-root', 'output-root', 'pwsh', 'gradle', 'java-home', 'android-home', 'gradle-user-home'):
    p.add_argument('--' + name, type=pathlib.Path, required=True)
p.add_argument('--api-only', action='store_true')
a = p.parse_args()
root, output = a.repo_root.resolve(), a.output_root.resolve()
if output.exists() or not output.is_relative_to(root / 'target'):
    raise ValueError('Fresh owner target required')
output.mkdir(parents=True)
module = root / 'tools/lib/AndroidComponentClosure.psm1'
quoted = str(module).replace("'", "''")
probe = subprocess.run([str(a.pwsh), '-NoProfile', '-NonInteractive', '-Command',
    "Import-Module '" + quoted + "'; if(-not(Get-Command Invoke-AndroidComponentJvmCompilation).Parameters.ContainsKey('WorkingEvidenceDirectory')){throw 'stable-working-path API absent'}"], capture_output=True)
(output / 'api.stdout').write_bytes(probe.stdout); (output / 'api.stderr').write_bytes(probe.stderr)
if probe.returncode:
    raise AssertionError('stable-working-path API absent; original probe retained')
if a.api_only:
    sys.exit(0)

project = output / 'project'; app = project / 'app'; private = app / 'owned'
private.mkdir(parents=True)
(project / 'settings.gradle').write_text("pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }\nrootProject.name='working-path-fixture'\ninclude(':app')\n")
(project / 'build.gradle').write_text("plugins { id 'com.android.application' version '8.11.1' apply false; id 'org.jetbrains.kotlin.android' version '2.1.0' apply false }\n")
(project / 'gradle.properties').write_text('org.gradle.jvmargs=-Xmx1024m\nandroid.useAndroidX=true\nkotlin.compiler.execution.strategy=in-process\n')
(project / 'local.properties').write_text('sdk.dir='+a.android_home.as_posix()+'\n')
(app / 'src/main').mkdir(parents=True)
(app / 'src/main/AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application/></manifest>')
observer = (root / 'tools/gradle/android-component-closure.gradle').as_posix()
(app / 'build.gradle').write_text("plugins { id 'com.android.application'; id 'org.jetbrains.kotlin.android' }\nandroid { namespace 'fixture'; compileSdk 34; defaultConfig { applicationId 'fixture.working'; minSdk 26; targetSdk 34 }; compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }; kotlinOptions { jvmTarget='17' }; sourceSets.main.java.srcDir('owned') }\napply from: '" + observer + "'\n")
owner = private / 'Owner.kt'
owner.write_text('package fixture\nobject Owner { @JvmStatic fun value() = 1 }\n')
lock = project / 'lock.json'; contract = project / 'contract.json'
lock.write_text(json.dumps(dict(selected_features=['owner'], features=[dict(feature_id='owner', module_id='owner', owner_lane='fixture', selected=True)])))
spec = importlib.util.spec_from_file_location('working_closure', root / 'tools/lib/android_component_closure.py')
closure = importlib.util.module_from_spec(spec); spec.loader.exec_module(closure)
definitions = [dict(root='private',source='Owner.kt',**{'class':'fixture.Owner'},methods=[dict(name='value',descriptor='()I',public=True,static=True)])]
driver = output / 'driver.ps1'
driver.write_text("""param([string]$Request)
$ErrorActionPreference='Stop'
$r=Get-Content -LiteralPath $Request -Raw|ConvertFrom-Json -AsHashtable
Import-Module $r.module -Force
$parameters=$r.arguments
try {
  $result=Invoke-AndroidComponentJvmCompilation @parameters
  [IO.File]::WriteAllText($r.result,($result|ConvertTo-Json -Depth 16))
}catch{
  [IO.File]::WriteAllText($r.failure,(@{error=$_.Exception.Message;execution=$_.Exception.Data['gradle_execution']}|ConvertTo-Json -Depth 16))
  exit 1
}
""", encoding='utf-8')
environment = os.environ.copy(); environment['JAVA_HOME'] = str(a.java_home)
environment['ANDROID_HOME'] = str(a.android_home)
working = output / 'working'
results = []

def invoke(name, stable=False, failed=False, tool=None):
    attempt = output / name; attempt.mkdir()
    contract.write_text(json.dumps(dict(schema=closure.SCHEMA, components=[dict(component_id='owner',module_id='owner',owner_lane='fixture',definitions=definitions)])))
    inventory = closure.prepare(contract, lock, closure.file_sha(lock), {'private':private}, 'debug')
    inventory_path = attempt / 'inventory.json'; inventory_path.write_bytes(closure.canonical(inventory))
    tool = tool or a.gradle
    arguments = dict(InventoryPath=str(inventory_path), ExpectedInventorySha256=closure.file_sha(inventory_path),
        EvidenceDirectory=str(attempt/'evidence'), GradlePath=str(tool), ExpectedGradleSha256=closure.file_sha(tool),
        ProjectDirectory=str(project), GradleUserHome=str(a.gradle_user_home), BuildType='Debug',
        Arguments=['--no-daemon','--configuration-cache','--console=plain','-Pkotlin.compiler.execution.strategy=in-process'])
    if stable: arguments['WorkingEvidenceDirectory'] = str(working)
    request = attempt/'request.json'
    request.write_text(json.dumps(dict(module=str(module),arguments=arguments,result=str(attempt/'result.json'),failure=str(attempt/'failure.json'))))
    start = time.monotonic()
    child = subprocess.run([str(a.pwsh),'-NoProfile','-NonInteractive','-File',str(driver),'-Request',str(request)],capture_output=True,env=environment)
    (attempt/'stdout.bin').write_bytes(child.stdout); (attempt/'stderr.bin').write_bytes(child.stderr)
    row = dict(step=name,exit_code=child.returncode,elapsed_seconds=time.monotonic()-start,source_inventory_sha256=closure.file_sha(inventory_path))
    if failed:
        assert child.returncode != 0 and not (attempt/'evidence/compiled.json').exists()
        row['failure'] = json.loads((attempt/'failure.json').read_text(encoding='utf-8-sig'))
    else:
        assert child.returncode == 0, 'Actual working-path fixture failed at '+name+'; logs retained'
        text = child.stdout.decode(errors='replace')
        row.update(configuration_cache_reused='Reusing configuration cache' in text,
                   configuration_inventory_miss="Gradle property 'rqAndroidComponentInventory' has changed" in text,
                   kotlin_up_to_date=':app:compileDebugKotlin UP-TO-DATE' in text)
        compiled = closure.load(attempt/'evidence/compiled.json')
        assert compiled['inventory_sha256'] == closure.file_sha(inventory_path)
        row['compiled_sha256'] = closure.file_sha(attempt/'evidence/compiled.json')
        row['receipt_sha256'] = closure.file_sha(attempt/'evidence/gradle-inputs.json')
        row['classes'] = {str(f.relative_to(project)):closure.file_sha(f) for f in app.rglob('*.class')}
    assert not list(output.rglob('*.apk')), 'JVM fixture must not produce an APK'
    results.append(row)
    print(json.dumps(dict(step=name,exit_code=child.returncode,seconds=round(row['elapsed_seconds'],3))),flush=True)
    return row

invoke('baseline-initial')
miss = invoke('baseline-fresh-attempt')
assert miss['configuration_inventory_miss'] and not miss['configuration_cache_reused']
invoke('stable-initial', stable=True)
reused = invoke('stable-fresh-attempt', stable=True)
assert reused['configuration_cache_reused'] and reused['kotlin_up_to_date']
assert results[-1]['compiled_sha256'] == results[-2]['compiled_sha256']
(private/'Added.kt').write_text('package fixture\nobject Added { @JvmStatic fun added() = 3 }\n')
definitions.append(dict(root='private',source='Added.kt',**{'class':'fixture.Added'},methods=[dict(name='added',descriptor='()I',public=True,static=True)]))
added = invoke('source-addition', stable=True)
(private/'Added.kt').write_text('package fixture\nobject Added { @JvmStatic fun added() = 7 }\n')
changed = invoke('source-change', stable=True)
assert added['classes'] != changed['classes']
unchanged = invoke('source-unchanged', stable=True)
assert unchanged['configuration_cache_reused'] and unchanged['kotlin_up_to_date']
prior_receipt = (working/'gradle-inputs.json').read_bytes()
owner.write_text('package fixture\nobject Owner { @JvmStatic fun value() = missingSymbol }\n')
failed = invoke('real-compiler-failure', stable=True, failed=True)
assert failed['failure']['execution']['exit_code'] != 0
owner.write_text('package fixture\nobject Owner { @JvmStatic fun value() = 1 }\n')
# An exit0 tool that emits nothing must not adopt a retained working receipt.
# The real compiler failure above also must not publish compiled.json.
(working/'gradle-inputs.json').write_bytes(prior_receipt)
fake = output/'no-receipt.bat'; fake.write_text('@echo off\r\nexit /b 0\r\n')
stale = invoke('stale-working-receipt', stable=True, failed=True, tool=fake)
assert 'component-working-receipt-missing' in stale['failure']['error']
assert not (working/'gradle-inputs.json').exists()
(output/'RESULT.json').write_text(json.dumps(dict(status='passed',steps=results,
    source_pins={str(f.relative_to(root)):closure.file_sha(f) for f in (module,root/'tools/gradle/android-component-closure.gradle')},
    actual_android_jvm_compilation=True,fresh_immutable_receipts=True,configuration_cache_reused=True,
    source_addition_and_change_verified=True,failed_child_and_stale_working_receipt_rejected=True,
    apk_or_device_calls=0,limits='Small AGP/Kotlin JVM fixture; full product/native/outer wall comparison separate.'),indent=2))
