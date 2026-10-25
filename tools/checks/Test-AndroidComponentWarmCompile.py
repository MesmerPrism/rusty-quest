"""Real warm Kotlin Gradle source-add/change fixture using the production observer."""
import sys
sys.dont_write_bytecode = True
import argparse, importlib.util, json, os, pathlib, subprocess, time
p=argparse.ArgumentParser()
for name in ['repo-root','output-root','gradle','java-home','gradle-user-home','d8','android-jar']:
    p.add_argument('--'+name,type=pathlib.Path,required=True)
p.add_argument('--identity-only', action='store_true',
    help='Initial/warm/proof-metadata counterexample through the actual compiler; no product build')
a=p.parse_args();r=a.repo_root.resolve();o=a.output_root.resolve()
if o.exists() or not o.is_relative_to(r/'target'):raise ValueError('Fresh owner target required')
o.mkdir(parents=True);project=o/'project';project.mkdir();private=project/'private';private.mkdir()
module=r/'tools/lib/android_component_closure.py';spec=importlib.util.spec_from_file_location('closure',module)
closure=importlib.util.module_from_spec(spec);spec.loader.exec_module(closure)
(private/'Owner.kt').write_text('package fixture\nobject Owner { @JvmStatic fun status() = 1 }\n')
(private/'Panel.kt').write_text('package fixture\ninternal fun panel() = 9\n')
lock=project/'lock.json';lock.write_text(json.dumps({'selected_features':['owner'],'features':[{'feature_id':'owner','module_id':'owner','owner_lane':'private','selected':True}]}))
contract=project/'contract.json'
def definition(source,name,method):return {'root':'private','source':source,'class':'fixture.'+name,'methods':[{'name':method,'descriptor':'()I','public':True,'static':True}]}
value={'schema':closure.SCHEMA,'components':[{'component_id':'owner','module_id':'owner','owner_lane':'private','definitions':[
    definition('Owner.kt','Owner','status'),definition('Panel.kt','PanelKt','panel')]}]}
(project/'settings.gradle').write_text("pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }\nrootProject.name='component-warm-fixture'\n")
observer=(r/'tools/gradle/android-component-closure.gradle').as_posix()
source_tools={'checker_sha256':closure.file_sha(module),'observer_sha256':closure.file_sha(observer)}
(project/'build.gradle').write_text("plugins { id 'org.jetbrains.kotlin.jvm' version '2.1.0' }\nrepositories { mavenCentral() }\nsourceSets.main.java.srcDir('private')\napply from: '"+observer+"'\n")
environment=os.environ.copy();environment['JAVA_HOME']=str(a.java_home)
results=[]
steps=['initial','unchanged','proof-metadata'] if a.identity_only else [
    'initial','addition','change','method','selection','deletion','unchanged','proof-metadata']
for index,label in enumerate(steps):
    if label=='addition':
        (private/'Added.kt').write_text('package fixture\nobject Added { @JvmStatic fun added() = 3 }\n')
        value['components'][0]['definitions'].append(definition('Added.kt','Added','added'))
    if label=='change':
        (private/'Added.kt').write_text('package fixture\nobject Added { @JvmStatic fun changed() = 7 }\n')
        value['components'][0]['definitions'][-1]=definition('Added.kt','Added','changed')
    if label=='method':
        (private/'Owner.kt').write_text('package fixture\nobject Owner { @JvmStatic fun renamed() = 1 }\n')
        value['components'][0]['definitions'][0]=definition('Owner.kt','Owner','renamed')
    if label=='selection':
        # An actual selected contract identity change remains a dependency even
        # when the currently selected physical compiler source set is identical.
        value['components'][0]['component_id']='owner-reselected'
    if label=='deletion':
        (private/'Added.kt').unlink()
        value['components'][0]['definitions'].pop()
    if label=='proof-metadata':
        # The real producer reauthenticates new raw proof bytes. Selected source,
        # definitions and actual compiler inputs remain unchanged in this fixture.
        revised=json.loads(lock.read_text());revised['revision']=revised.get('revision',0)+1
        lock.write_text(json.dumps(revised))
    contract.write_text(json.dumps(value));inventory=closure.prepare(contract,lock,closure.file_sha(lock),{'private':private},'debug')
    inventory_path=project/'inventory.json';inventory_path.write_bytes(closure.canonical(inventory))
    evidence=o/label;evidence.mkdir()
    command=[str(a.gradle),'--no-daemon','--configuration-cache','--console=plain','--info',
        '-Pkotlin.compiler.execution.strategy=in-process','--gradle-user-home',str(a.gradle_user_home),'-p',str(project),
        '-PrqAndroidComponentInventory='+str(inventory_path),'-PrqAndroidComponentEvidenceDir='+str(project/'compiler-observation'),
        '-PrqAndroidComponentKotlinTask=compileKotlin','-PrqAndroidComponentJavaTask=compileJava','classes']
    started=time.perf_counter();result=subprocess.run(command,capture_output=True,env=environment);elapsed=time.perf_counter()-started
    (evidence/'gradle.stdout').write_bytes(result.stdout);(evidence/'gradle.stderr').write_bytes(result.stderr)
    (evidence/'process.json').write_text(json.dumps({'argv':command,'exit_code':result.returncode,'elapsed_seconds':elapsed,
        'java_home':str(a.java_home),'gradle_user_home':str(a.gradle_user_home),'source_tools':source_tools},indent=2))
    if result.returncode:raise RuntimeError('Actual warm Gradle fixture failed at '+label+'; bounded logs retained')
    if source_tools!={'checker_sha256':closure.file_sha(module),'observer_sha256':closure.file_sha(observer)}:
        raise AssertionError('Actual source tools changed during the compiler experiment')
    observed=project/'compiler-observation/gradle-inputs.json'
    (evidence/'gradle-inputs.json').write_bytes(observed.read_bytes())
    actual=closure.load(evidence/'gradle-inputs.json');closure.verify_current_sources(inventory);closure.verify_inputs(inventory,actual)
    hashes={}
    compiled=[]
    for d in inventory['definitions']:
        files=[pathlib.Path(root)/(d['class'].replace('.','/')+'.class') for root in actual['class_roots']]
        files=[path for path in files if path.is_file()]
        if len(files)!=1:raise AssertionError('Actual selected compiled definition missing')
        found=closure.inspect_class(files[0].read_bytes());closure.required_methods(d,found)
        hashes[d['class']]=closure.file_sha(files[0])
        compiled.append(files[0])
    dex=evidence/'dex';dex.mkdir()
    generated=subprocess.run([str(a.d8),'--min-api','34','--lib',str(a.android_jar),'--output',str(dex),*[str(path) for path in compiled]],capture_output=True,env=environment)
    (evidence/'d8.stdout').write_bytes(generated.stdout);(evidence/'d8.stderr').write_bytes(generated.stderr)
    if generated.returncode:raise RuntimeError('Actual Kotlin fixture D8 failed')
    import zipfile
    apk=evidence/'fixture.apk'
    with zipfile.ZipFile(apk,'w') as archive:archive.write(dex/'classes.dex','classes.dex')
    result_closure=closure.verify(inventory,actual,apk,closure.file_sha(inventory_path))
    (evidence/'closure.json').write_bytes(closure.canonical(result_closure))
    facade=next(d for d in inventory['definitions'] if d['class']=='fixture.PanelKt')
    wrong=dict(inventory,definitions=[dict(facade,**{'class':'fixture.Panel'})])
    try:closure.verify_dex(wrong,apk)
    except ValueError as error:
        if str(error)!='required-dex-class-absent':raise
    else:raise AssertionError('Top-level function must not satisfy a non-facade class requirement')
    results.append({'step':label,'sources':len(actual['sources']),'class_sha256':hashes,'inventory_sha256':closure.file_sha(inventory_path),
        'feature_lock_sha256':closure.file_sha(lock),'compile_up_to_date':b':compileKotlin UP-TO-DATE' in result.stdout,'elapsed_seconds':elapsed})
    # Preserve measured counterexamples even if the later expected reuse fails.
    (o/'STEPS.json').write_text(json.dumps({'steps':results,'source_tools':source_tools},indent=2))
if not a.identity_only:
    by_step={row['step']:row for row in results}
    assert by_step['addition']['sources']==3 and by_step['initial']['sources']==2
    assert by_step['addition']['class_sha256']['fixture.Added']!=by_step['change']['class_sha256']['fixture.Added']
    assert by_step['method']['class_sha256']['fixture.Owner']!=by_step['change']['class_sha256']['fixture.Owner']
    assert by_step['selection']['class_sha256']==by_step['method']['class_sha256']
    assert by_step['deletion']['sources']==2
    assert all(not by_step[name]['compile_up_to_date'] for name in ['addition','change','method','selection','deletion'])
    assert not any((pathlib.Path(root)/'fixture/Added.class').exists() for root in actual['class_roots'])
    assert by_step['unchanged']['compile_up_to_date']
else:
    assert results[1]['compile_up_to_date'], 'Unchanged-control did not actually reuse compilation'
assert results[-1]['class_sha256']==results[-2]['class_sha256'], 'Proof-only change altered actual compiled classes'
assert results[-1]['inventory_sha256']!=results[-2]['inventory_sha256'], 'Proof-only counterexample did not change raw authority inventory'
assert results[-1]['compile_up_to_date'], 'Proof-metadata-only inventory change forced Kotlin task execution; inspect retained --info input reasons (frontend work remains separate)'
cached=subprocess.run(command,capture_output=True,env=environment)
(o/'cached.stdout').write_bytes(cached.stdout);(o/'cached.stderr').write_bytes(cached.stderr)
assert cached.returncode==0 and b'Reusing configuration cache' in cached.stdout and b':compileKotlin UP-TO-DATE' in cached.stdout
# Real final class/DEX bytes must still satisfy the unchanged exact method and
# raw inventory joins, regardless of whether Gradle reused compilation.
wrong_method=json.loads(json.dumps(inventory));wrong_method['definitions'][0]['methods'][0]['name']='absentMethod'
try:closure.verify_dex(wrong_method,apk)
except ValueError as error:
    assert str(error)=='required-method-absent'
else:raise AssertionError('Missing actual DEX method accepted')
changed_inventory=dict(inventory,feature_lock_sha256='0'*64)
try:closure.verify(changed_inventory,actual,apk,closure.file_sha(inventory_path))
except ValueError as error:
    assert str(error)=='prepared-inventory-changed'
else:raise AssertionError('Rewritten raw authority inventory accepted')
wrong_receipt=dict(actual,inventory_sha256='0'*64)
try:closure.verify_inputs(inventory,wrong_receipt)
except ValueError as error:
    assert str(error)=='compiler-input-inventory'
else:raise AssertionError('Wrong raw compiler receipt inventory SHA accepted')
# New unknown authority fields must not be silently omitted by the compile
# identity projection. This is the real applied Gradle consumer, not a mirror.
inventory_path.write_bytes(closure.canonical(dict(inventory,future_compile_input='unknown')))
unknown=subprocess.run(command,capture_output=True,env=environment)
(o/'unknown-field.stdout').write_bytes(unknown.stdout);(o/'unknown-field.stderr').write_bytes(unknown.stderr)
assert unknown.returncode!=0 and b'component-compile-inventory-shape' in unknown.stderr
inventory_path.write_bytes(closure.canonical(inventory))
# The actual observer must still reject changed physical bytes against the
# original inventory, even when task configuration/compiled outputs are warm.
owner_source=private/'Owner.kt';owner_bytes=owner_source.read_bytes()
try:
    owner_source.write_bytes(owner_bytes+b'\n// changed after inventory preparation\n')
    drift=subprocess.run(command,capture_output=True,env=environment)
    (o/'source-drift.stdout').write_bytes(drift.stdout);(o/'source-drift.stderr').write_bytes(drift.stderr)
    assert drift.returncode!=0 and b'component-compiler-input-source-closure' in drift.stderr
finally:
    owner_source.write_bytes(owner_bytes)
assert closure.file_sha(owner_source)==next(row['sha256'] for row in inventory['sources'] if row['path']=='Owner.kt')
# Warm but incorrect source selection must fail at the production observer.
(project/'build.gradle').write_text("plugins { id 'org.jetbrains.kotlin.jvm' version '2.1.0' }\nrepositories { mavenCentral() }\napply from: '"+observer+"'\n")
missing=subprocess.run(command,capture_output=True,env=environment)
(o/'missing-source.stdout').write_bytes(missing.stdout);(o/'missing-source.stderr').write_bytes(missing.stderr)
assert missing.returncode!=0 and b'component-compiler-input-source-closure' in missing.stderr
(o/'RESULT.json').write_text(json.dumps({'status':'passed','steps':results,'configuration_cache_reused':True,'missing_source_selection_rejected':True,
    'actual_kotlin_source_compiler_class_dex_closure':True,'file_facade_sourcefile_and_method_verified':True,'without_kt_suffix_rejected':True,
    'source_tools':source_tools,'proof_metadata_only_compile_reused':True,'identity_only':a.identity_only,
    'unknown_inventory_field_rejected':True,'raw_inventory_rewrite_rejected':True,'missing_dex_method_rejected':True,
    'wrong_raw_compiler_receipt_sha_rejected':True,'actual_post_inventory_source_drift_rejected':True,
    'device_calls':0,'limits':'Actual Kotlin2.1 Gradle warm source inventory/class changes; JVM fixture, Android/native/device separate.'},indent=2))
print('Real warm Kotlin component identity/source closure passed')
