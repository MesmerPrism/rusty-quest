"""Real warm Kotlin Gradle source-add/change fixture using the production observer."""
import sys
sys.dont_write_bytecode = True
import argparse, importlib.util, json, os, pathlib, subprocess
p=argparse.ArgumentParser()
for name in ['repo-root','output-root','gradle','java-home','gradle-user-home']:
    p.add_argument('--'+name,type=pathlib.Path,required=True)
a=p.parse_args();r=a.repo_root.resolve();o=a.output_root.resolve()
if o.exists() or not o.is_relative_to(r/'target'):raise ValueError('Fresh owner target required')
o.mkdir(parents=True);project=o/'project';project.mkdir();private=project/'private';private.mkdir()
module=r/'tools/lib/android_component_closure.py';spec=importlib.util.spec_from_file_location('closure',module)
closure=importlib.util.module_from_spec(spec);spec.loader.exec_module(closure)
(private/'Owner.kt').write_text('package fixture\nobject Owner { @JvmStatic fun status() = 1 }\n')
lock=project/'lock.json';lock.write_text(json.dumps({'selected_features':['owner'],'features':[{'feature_id':'owner','module_id':'owner','owner_lane':'private','selected':True}]}))
contract=project/'contract.json'
def definition(source,name,method):return {'root':'private','source':source,'class':'fixture.'+name,'methods':[{'name':method,'descriptor':'()I','public':True,'static':True}]}
value={'schema':closure.SCHEMA,'components':[{'component_id':'owner','module_id':'owner','owner_lane':'private','definitions':[definition('Owner.kt','Owner','status')]}]}
(project/'settings.gradle').write_text("pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }\nrootProject.name='component-warm-fixture'\n")
observer=(r/'tools/gradle/android-component-closure.gradle').as_posix()
(project/'build.gradle').write_text("plugins { id 'org.jetbrains.kotlin.jvm' version '2.1.0' }\nrepositories { mavenCentral() }\nsourceSets.main.java.srcDir('private')\napply from: '"+observer+"'\n")
environment=os.environ.copy();environment['JAVA_HOME']=str(a.java_home)
results=[]
for index,label in enumerate(['initial','addition','change','unchanged']):
    if label=='addition':
        (private/'Added.kt').write_text('package fixture\nobject Added { @JvmStatic fun added() = 3 }\n')
        value['components'][0]['definitions'].append(definition('Added.kt','Added','added'))
    if label=='change':
        (private/'Added.kt').write_text('package fixture\nobject Added { @JvmStatic fun changed() = 7 }\n')
        value['components'][0]['definitions'][-1]=definition('Added.kt','Added','changed')
    contract.write_text(json.dumps(value));inventory=closure.prepare(contract,lock,closure.file_sha(lock),{'private':private},'debug')
    inventory_path=project/'inventory.json';inventory_path.write_bytes(closure.canonical(inventory))
    evidence=o/label;evidence.mkdir()
    command=[str(a.gradle),'--no-daemon','--configuration-cache','--console=plain','--gradle-user-home',str(a.gradle_user_home),'-p',str(project),
        '-PrqAndroidComponentInventory='+str(inventory_path),'-PrqAndroidComponentEvidenceDir='+str(evidence),
        '-PrqAndroidComponentKotlinTask=compileKotlin','-PrqAndroidComponentJavaTask=compileJava','classes']
    result=subprocess.run(command,capture_output=True,env=environment)
    (evidence/'gradle.stdout').write_bytes(result.stdout);(evidence/'gradle.stderr').write_bytes(result.stderr)
    if result.returncode:raise RuntimeError('Actual warm Gradle fixture failed at '+label+'; bounded logs retained')
    actual=closure.load(evidence/'gradle-inputs.json');closure.verify_current_sources(inventory);closure.verify_inputs(inventory,actual)
    hashes={}
    for d in inventory['definitions']:
        files=[pathlib.Path(root)/(d['class'].replace('.','/')+'.class') for root in actual['class_roots']]
        files=[path for path in files if path.is_file()]
        if len(files)!=1:raise AssertionError('Actual selected compiled definition missing')
        found=closure.inspect_class(files[0].read_bytes());closure.required_methods(d,found)
        hashes[d['class']]=closure.file_sha(files[0])
    results.append({'step':label,'sources':len(actual['sources']),'class_sha256':hashes,'compile_up_to_date':b':compileKotlin UP-TO-DATE' in result.stdout})
assert results[1]['sources']==2 and results[0]['sources']==1
assert results[1]['class_sha256']['fixture.Added']!=results[2]['class_sha256']['fixture.Added']
assert results[3]['compile_up_to_date']
cached=subprocess.run(command,capture_output=True,env=environment)
(o/'cached.stdout').write_bytes(cached.stdout);(o/'cached.stderr').write_bytes(cached.stderr)
assert cached.returncode==0 and b'Reusing configuration cache' in cached.stdout and b':compileKotlin UP-TO-DATE' in cached.stdout
# Warm but incorrect source selection must fail at the production observer.
(project/'build.gradle').write_text("plugins { id 'org.jetbrains.kotlin.jvm' version '2.1.0' }\nrepositories { mavenCentral() }\napply from: '"+observer+"'\n")
missing=subprocess.run(command,capture_output=True,env=environment)
(o/'missing-source.stdout').write_bytes(missing.stdout);(o/'missing-source.stderr').write_bytes(missing.stderr)
assert missing.returncode!=0 and b'component-compiler-input-source-closure' in missing.stderr
(o/'RESULT.json').write_text(json.dumps({'status':'passed','steps':results,'configuration_cache_reused':True,'missing_source_selection_rejected':True,
    'source_tools':{'checker_sha256':closure.file_sha(module),'observer_sha256':closure.file_sha(r/'tools/gradle/android-component-closure.gradle')},
    'device_calls':0,'limits':'Actual Kotlin2.1 Gradle warm source inventory/class changes; JVM fixture, Android/native/device separate.'},indent=2))
print('Real warm Kotlin initial/add/change/unchanged component closure passed')
