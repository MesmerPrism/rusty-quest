"""Run the actual Activity adapter/context sources against bounded Android test doubles.

These fixtures prove callback and view-host routing, not Android lifecycle, focus or pixels.
"""
import argparse
import json
from pathlib import Path
import subprocess

PACKAGE = "io.github.mesmerprism.rustyquest.native_renderer"
PREFIX = "package " + PACKAGE + ";\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--java", default="java")
    parser.add_argument("--damage", choices=("retired-owner", "native-surface", "physical-intent"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parents[2]
    module = root / "apps/native-renderer-android/panel-modules/breath-composition/src/main/java" / PACKAGE.replace(".", "/")
    sources = {
        "android/content/Context.java": "package android.content; public class Context {}",
        "android/content/Intent.java": "package android.content; public class Intent { public final String value; public Intent(String v){value=v;} }",
        "android/os/Bundle.java": "package android.os; public class Bundle {}",
        "android/view/KeyEvent.java": "package android.view; public class KeyEvent {}",
        "android/view/MotionEvent.java": "package android.view; public class MotionEvent {}",
        "android/view/View.java": "package android.view; public class View { public View findViewById(int id){return this;} }",
        "android/widget/LinearLayout.java": "package android.widget; public class LinearLayout extends android.view.View {}",
        "android/app/Application.java": "package android.app; public class Application {}",
        "android/view/ContextThemeWrapper.java": "package android.view; public class ContextThemeWrapper extends android.content.Context { public ContextThemeWrapper(android.content.Context c,Object theme){} }",
        "android/app/Activity.java": """package android.app;
public class Activity extends android.content.Context {
 public static final java.util.List<String> events=new java.util.ArrayList<>();
 public boolean finishing,destroyed; public int contentWrites; private android.content.Intent intent=new android.content.Intent("physical-main");
 public Object getTheme(){return this;} public Application getApplication(){return new Application();}
 public android.content.Intent getIntent(){return intent;} public void setIntent(android.content.Intent i){intent=i;}
 public boolean isFinishing(){return finishing;} public boolean isDestroyed(){return destroyed;}
 public void finish(){finishing=true;events.add("finish");} public void runOnUiThread(Runnable r){r.run();}
 public void setContentView(android.view.View v){contentWrites++;} public android.view.View findViewById(int id){return null;}
 public void startActivityForResult(android.content.Intent i,int r){events.add("request:"+r);}
 protected void onCreate(android.os.Bundle s){events.add("super-create");}
 protected void onNewIntent(android.content.Intent i){events.add("super-intent");}
 protected void onSaveInstanceState(android.os.Bundle s){events.add("super-save");}
 protected void onResume(){events.add("super-resume");} protected void onPause(){events.add("super-pause");}
 protected void onDestroy(){destroyed=true;events.add("super-destroy");}
 protected void onActivityResult(int q,int r,android.content.Intent d){events.add("super-result");}
 public void onRequestPermissionsResult(int q,String[] p,int[] r){events.add("super-permission");}
 public boolean dispatchKeyEvent(android.view.KeyEvent e){events.add("super-key");return false;}
 public boolean dispatchGenericMotionEvent(android.view.MotionEvent e){events.add("super-motion");return false;}
}
""",
        "android/app/NativeActivity.java": "package android.app; public class NativeActivity extends Activity {}",
        "PanelModule.java": PREFIX + "interface PanelModule {String panelModuleId();}",
        "ControlPanelActivity.java": PREFIX + "class ControlPanelActivity {static android.app.Activity created; static void originalPanelHostCreated(android.app.Activity a){created=a; android.app.Activity.events.add(\"prepare-owner\");}}",
        "BreathCompositionPanelModule.java": PREFIX + """
class BreathCompositionPanelModule extends BreathCompositionPanelContext {
 static final String MODULE_ID="breath-composition-controls";
 static boolean relay; static BreathCompositionPanelModule latest;
 final java.util.List<String> calls=new java.util.ArrayList<>();
 BreathCompositionPanelModule(android.app.Activity a,boolean nativeMode){super(a,nativeMode);}
 static BreathCompositionPanelModule createFor(android.app.Activity a,boolean n,android.os.Bundle state){
  latest=new BreathCompositionPanelModule(a,n);latest.onCreate(state);latest.setContentView(new android.view.View());return latest;
 }
 static boolean deliverToNativeOwner(android.content.Intent i,boolean show){return relay;}
 static AutoCloseable attachNativeMeshSessionControls(android.app.Activity a,android.widget.LinearLayout c,java.util.function.BooleanSupplier current){return null;}
 static BreathCompositionPanelModule controllerFor(android.app.Activity a){return latest!=null&&latest.hostActivity()==a&&latest.hostCurrent()?latest:null;}
 static void resumeNativeMeshImmersive(android.app.Activity a){}
 void note(String c){calls.add(c);android.app.Activity.events.add(c);}
 @Override protected void onCreate(android.os.Bundle s){note("create");}
 @Override protected void onNewIntent(android.content.Intent i){setIntent(i);note("intent");}
 @Override protected void onSaveInstanceState(android.os.Bundle s){note("save");}
 @Override protected void onResume(){note("resume");} @Override protected void onPause(){note("pause");}
 @Override protected void onDestroy(){if(!hostCurrent())return;note("destroy");retireHost();}
 @Override protected void onActivityResult(int q,int r,android.content.Intent d){note("result:"+q);}
 @Override public void onRequestPermissionsResult(int q,String[] p,int[] r){note("permission:"+q);}
 @Override public boolean dispatchKeyEvent(android.view.KeyEvent e){note("key");return true;}
 @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent e){note("motion");return true;}
}
""",
        "OriginalPanelHostTest.java": PREFIX + """
public final class OriginalPanelHostTest {
 static int checks;
 static void require(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 static void order(String first,String second){java.util.List<String> e=android.app.Activity.events;require(e.indexOf(first)>=0&&e.indexOf(first)<e.indexOf(second),first+" before "+second);e.clear();}
 public static void main(String[] unused){
  NativeProductPanelActivity a=new NativeProductPanelActivity();a.onCreate(null);
  BreathCompositionPanelModule first=BreathCompositionPanelModule.latest;
  require(first.hostActivity()==a&&ControlPanelActivity.created==a,"real native Activity retained");
  order("super-create","create");require(a.contentWrites==0&&first.hostContent()!=null,"native surface never replaced");
  android.content.Intent physical=a.getIntent();a.onNewIntent(new android.content.Intent("ui-route"));
  require(a.getIntent()==physical&&first.getIntent()!=physical,"UI Intent cannot replace physical launcher Intent");
  android.app.Activity.events.clear();a.onResume();order("super-resume","resume");a.onPause();order("pause","super-pause");
  NativeProductPanelActivity b=new NativeProductPanelActivity();b.onCreate(null);BreathCompositionPanelModule second=BreathCompositionPanelModule.latest;
  int count=second.calls.size();a.onActivityResult(17,0,null);a.onRequestPermissionsResult(18,new String[0],new int[0]);
  require(first.calls.contains("result:17")&&first.calls.contains("permission:18")&&second.calls.size()==count,"old result keeps captured owner, never latest");
  first.retireHost();count=first.calls.size();a.onResume();a.onPause();a.onNewIntent(new android.content.Intent("late"));a.onActivityResult(19,0,null);a.onRequestPermissionsResult(20,new String[0],new int[0]);a.dispatchKeyEvent(new android.view.KeyEvent());a.dispatchGenericMotionEvent(new android.view.MotionEvent());
  require(first.calls.size()==count&&second.calls.size()==1,"retired controller receives no callbacks or successor writes");
  a.onDestroy();count=first.calls.size();a.onActivityResult(21,0,null);require(first.calls.size()==count,"destroyed host drops delayed result");
  BreathCompositionPanelModule.relay=true;BreathCompositionPanelActivity duplicate=new BreathCompositionPanelActivity();duplicate.onCreate(null);duplicate.onResume();duplicate.onPause();duplicate.onSaveInstanceState(new android.os.Bundle());duplicate.onDestroy();
  require(duplicate.finishing&&BreathCompositionPanelModule.latest==second,"2D relay never constructs another controller");
  BreathCompositionPanelModule.relay=false;BreathCompositionPanelActivity old2d=new BreathCompositionPanelActivity();old2d.onCreate(null);BreathCompositionPanelModule third=BreathCompositionPanelModule.latest;
  require(old2d.contentWrites==1&&third.hostActivity()==old2d,"original2D real window hosts same content path");
  android.content.Intent route=new android.content.Intent("2d-route");old2d.onNewIntent(route);require(old2d.getIntent()==route,"2D Intent behavior preserved");
  System.out.println("original-panel-host checks="+checks+" passed");
 }
}
""",
    }
    for name in ("BreathCompositionPanelContext.java", "BreathCompositionPanelActivity.java", "NativeProductPanelActivity.java"):
        sources[name] = (module / name).read_text(encoding="utf-8")
    if args.damage == "retired-owner":
        for name in ("BreathCompositionPanelActivity.java", "NativeProductPanelActivity.java"):
            sources[name] = sources[name].replace("controller!=null&&controller.hostCurrent()", "controller!=null")
    elif args.damage == "native-surface":
        sources["BreathCompositionPanelContext.java"] = sources["BreathCompositionPanelContext.java"].replace("if (!nativePresentation) activity.setContentView(view);", "activity.setContentView(view);")
    elif args.damage == "physical-intent":
        sources["BreathCompositionPanelContext.java"] = sources["BreathCompositionPanelContext.java"].replace("if (!nativePresentation) activity.setIntent(intent);", "activity.setIntent(intent);")
    paths=[]
    for name,text in sources.items():
        path=args.output/name;path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(text,encoding="utf-8");paths.append(str(path))
    classes=args.output/"classes";classes.mkdir()
    compile_result=subprocess.run([args.javac,"--release","8","-encoding","UTF-8","-d",str(classes),*paths],capture_output=True,text=True)
    (args.output/"compile.log").write_text(compile_result.stdout+compile_result.stderr,encoding="utf-8")
    if compile_result.returncode: raise RuntimeError(compile_result.stderr)
    run=subprocess.run([args.java,"-cp",str(classes),PACKAGE+".OriginalPanelHostTest"],capture_output=True,text=True)
    (args.output/"fixture.log").write_text(run.stdout+run.stderr,encoding="utf-8")
    expected = {"retired-owner":"retired controller receives no callbacks", "native-surface":"native surface never replaced", "physical-intent":"UI Intent cannot replace physical launcher Intent"}
    passed = run.returncode == 0 if not args.damage else run.returncode != 0 and expected[args.damage] in run.stderr
    (args.output/"RESULT.json").write_text(json.dumps({"passed":passed,"negative_fixture":args.damage,"actual_adapter_sources":3,"device_effects":0,"scope":"Actual host/context callback routing with bounded Android/controller test doubles; no Android focus, native compilation or pixel claim."},indent=2),encoding="utf-8")
    if not passed: raise RuntimeError(run.stderr or "Damaged implementation escaped the callback fixture")
    if args.damage: print("negative fixture rejected: "+args.damage)
    print(run.stdout)


if __name__=="__main__": main()
