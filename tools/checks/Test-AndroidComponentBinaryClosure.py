"""Real javac/D8 definitions and closed failure controls; no device actions."""
import sys
sys.dont_write_bytecode = True
import argparse, importlib.util, json, os, pathlib, subprocess, zipfile
p=argparse.ArgumentParser()
for name in ['repo-root','output-root','java-home','d8','android-jar']:
    p.add_argument('--'+name,type=pathlib.Path,required=True)
a=p.parse_args();r=a.repo_root.resolve();o=a.output_root.resolve()
if o.exists() or not o.is_relative_to(r/'target'):raise ValueError('Fresh owner target required')
o.mkdir(parents=True);s=o/'sources';s.mkdir();classes=o/'classes';classes.mkdir();dex=o/'dex';dex.mkdir()
spec=importlib.util.spec_from_file_location('closure',r/'tools/lib/android_component_closure.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
source=s/'Owner.java';source.write_text('package fixture; public class Owner { public static int status() { return 7; } public int instance() { return 9; } }')
environment=os.environ.copy();environment['JAVA_HOME']=str(a.java_home)
def run(command):
    result=subprocess.run([str(x) for x in command],capture_output=True,env=environment)
    if result.returncode:raise RuntimeError('Binary fixture tool failed')
run([a.java_home/'bin/javac.exe','-g:source','-d',classes,source])
class_file=classes/'fixture/Owner.class'
def apk(name,entries):
    path=o/name
    with zipfile.ZipFile(path,'w') as z:
        for key,data in entries:z.writestr(key,data)
    return path
lock=o/'lock.json';lock.write_text(json.dumps({'selected_features':['owner'],'features':[{'feature_id':'owner','module_id':'owner','owner_lane':'fixture','selected':True}]}))
definition={'root':'private','source':'Owner.java','class':'fixture.Owner','methods':[{'name':'status','descriptor':'()I','public':True,'static':True}]}
contract=o/'contract.json';contract.write_text(json.dumps({'schema':c.SCHEMA,'components':[{'component_id':'owner','module_id':'owner','owner_lane':'fixture','definitions':[definition]}]}))
inventory=c.prepare(contract,lock,c.file_sha(lock),{'private':s},'debug')
actual={'schema':'rusty.quest.android_component_gradle_inputs.v1','inventory_sha256':c.json_sha(inventory),'sources':inventory['sources'],'class_roots':[str(classes),str(o/'no-java-output')]}
expected=c.json_sha(inventory)
compiled=c.verify_compiled(inventory,actual,expected)
assert compiled['status']=='passed' and compiled['runtime_authority'] is False
assert compiled['schema']=='rusty.quest.android_component_jvm_compilation.v1'
assert 'apk_sha256' not in compiled and 'dex' not in compiled
assert compiled['class_outputs'][0]['name']=='fixture.Owner'
assert not list(dex.iterdir()),'Compiled-only proof unexpectedly needed DEX/APK output'
controls=[]
def refuses(label,action,code):
    try:action()
    except (ValueError,IndexError) as error:
        if code and code not in str(error):raise
        controls.append(label);return
    raise AssertionError('Expected failure: '+label)
refuses('compiled-missing-output',lambda:c.verify_compiled(inventory,dict(actual,class_roots=[]),expected),'required-class-output')
refuses('compiled-duplicate-output',lambda:c.verify_compiled(inventory,dict(actual,class_roots=[str(classes),str(classes)]),expected),'required-class-output')
refuses('compiled-stale-input',lambda:c.verify_compiled(inventory,dict(actual,sources=[]),expected),'compiler-input')
refuses('compiled-original-inventory-binding',lambda:c.verify_compiled(inventory,actual,'0'*64),'prepared-inventory-changed')
inventory_file=o/'inventory.json';inventory_file.write_bytes(c.canonical(inventory))
actual_file=o/'gradle-inputs.json';actual_file.write_bytes(c.canonical(actual))
run([sys.executable,r/'tools/lib/android_component_closure.py','verify-compiled','--inventory',inventory_file,
     '--expected-inventory-sha256',expected,'--gradle-receipt',actual_file,'--output',o/'compiled.json'])
inventory_file.write_bytes(c.canonical(inventory)+b'\n')
raw_damage=subprocess.run([sys.executable,str(r/'tools/lib/android_component_closure.py'),'verify-compiled',
    '--inventory',str(inventory_file),'--expected-inventory-sha256',expected,'--gradle-receipt',str(actual_file),
    '--output',str(o/'must-not-exist.json')],capture_output=True,env=environment)
assert raw_damage.returncode!=0 and b'prepared-inventory-changed' in raw_damage.stderr
assert not (o/'must-not-exist.json').exists();controls.append('compiled-raw-inventory-whitespace-drift')
run([a.d8,'--min-api','34','--lib',a.android_jar,'--output',dex,class_file])
good=apk('good.apk',[('classes10.dex',(dex/'classes.dex').read_bytes())])
result=c.verify(inventory,actual,good,expected)
refuses('truncated-class',lambda:c.inspect_class(class_file.read_bytes()[:20]),'binary-bounds')
refuses('truncated-dex',lambda:c.inspect_dex((dex/'classes.dex').read_bytes()[:100]),'dex-header')
wrong=dict(definition,methods=[dict(definition['methods'][0],descriptor='()V')])
refuses('missing-method-descriptor',lambda:c.required_methods(wrong,c.inspect_class(class_file.read_bytes())),'required-method-absent')
wrong=dict(definition,methods=[dict(definition['methods'][0],name='instance')])
refuses('instance-is-not-static',lambda:c.required_methods(wrong,c.inspect_class(class_file.read_bytes())),'required-method-access')
bad=apk('duplicate.apk',[('classes.dex',(dex/'classes.dex').read_bytes()),('classes2.dex',(dex/'classes.dex').read_bytes())])
refuses('duplicate-dex-definition',lambda:c.verify_dex(inventory,bad),'apk-duplicate-class')
missing=dict(inventory,definitions=[dict(definition,**{'class':'fixture.Missing'})])
refuses('missing-dex-definition',lambda:c.verify_dex(missing,good),'required-dex-class-absent')
refuses('missing-class-output',lambda:c.verify(inventory,dict(actual,class_roots=[]),good,expected),'required-class-output')
refuses('stale-actual-input',lambda:c.verify(inventory,dict(actual,sources=[]),good,expected),'compiler-input')
omitted=dict(inventory,definitions=[])
refuses('omitted-requirement-retained-claims',lambda:c.verify(omitted,actual,good,expected),'prepared-inventory-changed')
refuses('omitted-requirement-fingerprint',lambda:c.verify_current_sources(omitted),'inventory-fingerprint')
omitted['fingerprint']=c.json_sha({key:value for key,value in omitted.items() if key!='fingerprint'})
refuses('rewritten-requirement-and-compiler-receipt',lambda:c.verify(omitted,dict(actual,inventory_sha256=c.json_sha(omitted)),good,expected),'prepared-inventory-changed')
(o/'RESULT.json').write_text(json.dumps({'status':'passed','compiled_only':compiled,'positive':result,'negative_controls':controls,
    'checker_sha256':c.file_sha(r/'tools/lib/android_component_closure.py'),'device_calls':0},indent=2))
print('Actual compiled-only javac and final all-DEX closure controls passed')
