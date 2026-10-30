import hashlib,json,os,pathlib,subprocess,sys,time
import argparse
parser=argparse.ArgumentParser(description='Actual control-profile hotloader with explicit retained JVM recipe and modeled Android leaves; no Gradle/device effects.')
parser.add_argument('--reference-directory',type=pathlib.Path,required=True,help='Retained java.command.json, kotlin.command.json and resident-jvm.command.json supplier recipes')
parser.add_argument('--output',type=pathlib.Path,required=True)
a=parser.parse_args();root=pathlib.Path(__file__).resolve().parents[2];ref=a.reference_directory.resolve();out=a.output.resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False);classes=out/'classes';classes.mkdir()
j=json.loads((ref/'java.command.json').read_text());k=json.loads((ref/'kotlin.command.json').read_text());behavior=json.loads((ref/'resident-jvm.command.json').read_text());cp=str(classes)+os.pathsep+j[j.index('-cp')+1]
leaves={
'android/content/Context.java':'package android.content; import java.io.File; public class Context {private final File root; public Context(File root){this.root=root;} public File getExternalFilesDir(String ignored){return root;} public File getFilesDir(){return root;}}',
'android/os/Looper.java':'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'package android.os; import java.util.ArrayDeque; public class Handler {private static final ArrayDeque<Runnable> queued=new ArrayDeque<>(); public Handler(Looper ignored){} public boolean post(Runnable r){queued.add(r);return true;} public static void drain(){while(!queued.isEmpty())queued.remove().run();}}',
'android/os/FileObserver.java':'package android.os; import java.io.File; public abstract class FileObserver {public static final int CLOSE_WRITE=8,CREATE=256,DELETE=512,MOVED_FROM=64,MOVED_TO=128; public FileObserver(File ignored,int events){} public abstract void onEvent(int event,String path); public void startWatching(){} public void stopWatching(){}}',
'android/util/AtomicFile.java':'package android.util; import java.io.*; import java.nio.file.*; public class AtomicFile {private final File target,temp; public AtomicFile(File target){this.target=target;this.temp=new File(target.getPath()+".new");} public FileOutputStream startWrite()throws IOException{return new FileOutputStream(temp);} public void finishWrite(FileOutputStream stream)throws IOException{stream.close();Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);} public void failWrite(FileOutputStream stream)throws IOException{stream.close();Files.deleteIfExists(temp.toPath());}}'
}
js=[]
for name,content in leaves.items():
 f=out/'leaves'/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(content);js.append(f)
pkg='io/github/mesmerprism/rustyquest/spatial_camera_panel'
sources=[root/('apps/spatial-camera-panel-android/app/src/main/java/'+pkg+'/'+n+'.kt') for n in ('SpatialCameraControlProfile','SpatialPeerNetworkProfilePilot','SpatialDevelopmentModuleDirective','SpatialDevelopmentModuleDirectiveContract','SpatialCameraControlProfileHotloader')]+[root/'apps/spatial-camera-panel-android/host-tests/control_profile/ProfileHotloaderHost.kt']
kc=[v for v in k if not v.endswith('.kt')];kc[kc.index('-classpath')+1]=cp;kc[kc.index('-d')+1]=str(classes);kc.extend(map(str,sources))
commands=[('leaves',[j[0],'--release','17','-d',str(classes),*map(str,js)]),('kotlin',kc),('behavior',[behavior[0],'-cp',cp,'io.github.mesmerprism.rustyquest.spatial_camera_panel.ProfileHotloaderHostKt',str(out/'files')])]
start=time.monotonic();checks=[]
for name,args in commands:
 (out/(name+'.command.json')).write_text(json.dumps(args,indent=2));r=subprocess.run(args,capture_output=True,timeout=90);(out/(name+'.stdout')).write_bytes(r.stdout);(out/(name+'.stderr')).write_bytes(r.stderr);checks.append(dict(name=name,exit=r.returncode))
 if r.returncode:print((r.stdout+r.stderr).decode(errors='replace')[-4000:]);break
result=dict(passed=len(checks)==3 and all(x['exit']==0 for x in checks),checks=checks,elapsed_seconds=round(time.monotonic()-start,3),source_pins=[dict(path=str(p),sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in sources],limits='Actual hotloader with modeled Android Context/FileObserver/Handler/AtomicFile leaves; no Android event-loop, filesystem AtomicFile implementation, module or device proof.')
(out/'RESULT.json').write_text(json.dumps(result,indent=2));print(json.dumps({k:v for k,v in result.items() if k!='source_pins'}));raise SystemExit(0 if result['passed'] else 1)
