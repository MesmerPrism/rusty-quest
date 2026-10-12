"""Actual Kotlin configuration producer with modeled native actor/control sources."""
import argparse, pathlib, subprocess, json, hashlib

def block(s, marker):
    start=s.index(marker);i=s.index('{',start)+1;depth=1
    while depth:
        depth+=(s[i]=='{')-(s[i]=='}');i+=1
    return s[start:i]

def main():
    p=argparse.ArgumentParser()
    for n in ['repo-root','output-root']:p.add_argument('--'+n,type=pathlib.Path,required=True)
    for n in ['java','compiler-classpath','runtime-classpath']:p.add_argument('--'+n,required=True)
    a=p.parse_args();r=a.repo_root.resolve();o=a.output_root.resolve()
    if o.exists() or not o.is_relative_to(r/'target'):raise ValueError('Unused owned target required')
    o.mkdir(parents=True)
    j=r/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel'
    producer=j/'embedded_duplex/ConcurrentStereoQualification.kt';control=j/'StereoBankControls.kt'
    text=producer.read_text()
    methods=block(text,'    @JvmStatic fun maskConfiguration(')+'\n'+block(text,'    private fun receipt(')
    accessor=block(control.read_text(),'    fun maskConfigurationSnapshot()')
    fixture=(r/'tools/checks/fixtures/NeutralMaskConfigurationHost.kt').read_text()
    generated=fixture.replace('// PRODUCTION_METHODS',methods).replace('// PRODUCTION_ACCESSOR',accessor)
    (o/'Control.kt').write_text(generated)
    contract=r/'apps/spatial-camera-panel-android/app/src/debug/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/EmbeddedDuplexOperatorContract.kt'
    (o/'Contract.kt').write_bytes(contract.read_bytes())
    (o/'Mask.kt').write_text('package io.github.mesmerprism.rustyquest.spatial_camera_panel\n'+block(control.read_text(),'data class NeutralMaskPolicy('))
    (o/'Android.kt').write_text('package android.os\nobject Process {const val SHELL_UID=2000}\nclass Bundle {fun keySet():Set<String> = emptySet(); fun get(key:String):Any? = null}\n')
    (o/'Draft.kt').write_text('package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex\nobject Construction {var effects=0}\nclass EmbeddedDuplexEnrollmentDraft(vararg args:Any) {init {Construction.effects++}}\nclass EmbeddedDuplexAbandonmentRequest(val raw:String) {init {Construction.effects++}}\n')

    def run(name,args):
        z=subprocess.run(args,capture_output=True);(o/(name+'.stdout')).write_bytes(z.stdout);(o/(name+'.stderr')).write_bytes(z.stderr)
        if z.returncode:raise RuntimeError(z.stderr.decode(errors='replace'))
    run('kotlin',[a.java,'-cp',a.compiler_classpath,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','1.8','-classpath',a.runtime_classpath,'-d',str(o/'classes'),str(o/'Control.kt'),str(o/'Contract.kt'),str(o/'Android.kt'),str(o/'Draft.kt'),str(o/'Mask.kt')])
    run('controls',[a.java,'-cp',str(o/'classes')+';'+a.runtime_classpath,'ControlKt'])
    (o/'RESULT.json').write_text(json.dumps({'status':'passed','device_effects':0,'source_pins':{str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in [producer,control,contract,r/'tools/checks/fixtures/NeutralMaskConfigurationHost.kt']},'limits':'Actual extracted Kotlin producer/accessor/lineage validation. Native controls and qualification sources modeled; Android target compilation separate.'},indent=2))
    print((o/'controls.stdout').read_text())
if __name__=='__main__':main()
