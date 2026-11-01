# Host-only Android boundaries for the boot/lifecycle production-body gates.
# Real Android bodies are also compiled against android.jar by the boot gate.
function New-NativeRendererBootHostFixtures {
    param([Parameter(Mandatory)][string]$Directory)
    $stubs = @{
        'Context.java' = @'
package android.content;
public class Context {
 public static final int MODE_PRIVATE=0;
 public static Context application;
 public static final java.util.Map<String,MemoryPreferences> preferences=new java.util.HashMap<>();
 public static final java.util.List<Intent> launches=new java.util.ArrayList<>();
 public static Runnable beforeStart;
 public static boolean launchFails;
 public static int serviceStarts;
 public String packageName="test.app";
 public Context getApplicationContext(){return application==null?this:application;}
 public String getPackageName(){return packageName;}
 public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
 public ContentResolver getContentResolver(){return new ContentResolver();}
 public SharedPreferences getSharedPreferences(String name,int mode){
  if(!preferences.containsKey(name))preferences.put(name,new MemoryPreferences());
  return preferences.get(name);
 }
 public void startActivity(Intent intent){if(beforeStart!=null)beforeStart.run();if(launchFails)throw new IllegalStateException("platform");launches.add(intent);}
 public ComponentName startForegroundService(Intent intent){serviceStarts++;return intent.getComponent();}
 public static class MemoryPreferences implements SharedPreferences {
  public final java.util.Map<String,Object> values=new java.util.HashMap<>();
  public boolean failCommit,throwOnAccess;
  private void check(){if(throwOnAccess)throw new IllegalStateException("storage");}
  public int getInt(String key,int fallback){check();Object v=values.get(key);return v instanceof Integer?(Integer)v:fallback;}
  public long getLong(String key,long fallback){check();Object v=values.get(key);return v instanceof Long?(Long)v:fallback;}
  public String getString(String key,String fallback){check();Object v=values.get(key);return v instanceof String?(String)v:fallback;}
  public java.util.Map<String,?> getAll(){check();return new java.util.HashMap<>(values);}
  public Editor edit(){check();return new Editor(){
   final java.util.Map<String,Object> pending=new java.util.HashMap<>();boolean cleared;
   public Editor clear(){cleared=true;return this;}
   public Editor putInt(String k,int v){pending.put(k,v);return this;}
   public Editor putLong(String k,long v){pending.put(k,v);return this;}
   public Editor putString(String k,String v){pending.put(k,v);return this;}
   public boolean commit(){check();if(failCommit)return false;if(cleared)values.clear();values.putAll(pending);return true;}
  };}
 }
}
'@
        'SharedPreferences.java' = @'
package android.content;
public interface SharedPreferences {
 int getInt(String key,int fallback);long getLong(String key,long fallback);String getString(String key,String fallback);
 java.util.Map<String,?> getAll();Editor edit();
 interface Editor {Editor clear();Editor putInt(String key,int value);Editor putLong(String key,long value);Editor putString(String key,String value);boolean commit();}
}
'@
        'ContentResolver.java' = 'package android.content; public class ContentResolver {}'
        'BroadcastReceiver.java' = 'package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}'
        'ComponentName.java' = @'
package android.content;
public class ComponentName {
 private final String packageName,className;
 public ComponentName(String p,String c){packageName=p;className=c;}
 public String getPackageName(){return packageName;}public String getClassName(){return className;}
}
'@
        'Intent.java' = @'
package android.content;
public class Intent {
 public static final String ACTION_MAIN="android.intent.action.MAIN",CATEGORY_LAUNCHER="android.intent.category.LAUNCHER";
 public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000;
 public String action=ACTION_MAIN;public int flags;
 public ComponentName component=new ComponentName("test.app","android.app.NativeActivity");
 public java.util.Set<String> categories=new java.util.HashSet<>();
 public Intent(){categories.add(CATEGORY_LAUNCHER);}public Intent(String a){action=a;categories.clear();}
 public Intent(Context c,Class<?> cls){component=new ComponentName(c.getPackageName(),cls.getName());}
 public Intent addCategory(String c){categories.add(c);return this;}
 public Intent setClassName(String p,String c){component=new ComponentName(p,c);return this;}
 public Intent addFlags(int f){flags|=f;return this;}
 public String getAction(){return action;}public ComponentName getComponent(){return component;}
 public java.util.Set<String> getCategories(){return categories;}public Object getData(){return null;}public Object getSelector(){return null;}
}
'@
        'PackageManager.java' = @'
package android.content.pm;
public class PackageManager {
 public static final int GET_META_DATA=128;
 public static android.os.Bundle metadata=new android.os.Bundle();public static boolean missing;
 public ApplicationInfo getApplicationInfo(String p,int f)throws NameNotFoundException{if(missing)throw new NameNotFoundException();ApplicationInfo i=new ApplicationInfo();i.metaData=metadata;return i;}
 public static class NameNotFoundException extends Exception {}
}
'@
        'ApplicationInfo.java' = 'package android.content.pm; public class ApplicationInfo {public android.os.Bundle metaData;}'
        'Bundle.java' = 'package android.os; public class Bundle extends java.util.HashMap<String,Object> {public boolean getBoolean(String k,boolean d){Object v=get(k);return v instanceof Boolean?(Boolean)v:d;}}'
        'Settings.java' = @'
package android.provider;
public class Settings {
 public static boolean overlay=true,overlayFails;public static boolean canDrawOverlays(android.content.Context c){if(overlayFails)throw new IllegalStateException("overlay");return overlay;}
 public static class Global {public static int bootCount=7;public static int getInt(android.content.ContentResolver c,String k,int d){return bootCount;}}
}
'@
        'SystemClock.java' = 'package android.os; public class SystemClock {public static long now=100;public static long elapsedRealtime(){return now++;}public static long elapsedRealtimeNanos(){return now++*1000000;}public static long uptimeMillis(){return now++;}}'
        'Log.java' = 'package android.util; public class Log {public static int i(String t,String m){return 0;}public static int w(String t,String m){return 0;}public static int w(String t,String m,Throwable e){return 0;}}'
        'Activity.java' = @'
package android.app;
public class Activity extends android.content.Context {
 public android.content.Intent intent=new android.content.Intent();public boolean finishing,destroyed;
 public android.content.Intent getIntent(){return intent;}public boolean isFinishing(){return finishing;}public boolean isDestroyed(){return destroyed;}
 public boolean isChangingConfigurations(){return false;}public boolean hasWindowFocus(){return true;}public int getTaskId(){return 1;}public void finishAndRemoveTask(){finishing=true;}
 public void runOnUiThread(Runnable r){new android.os.Handler(android.os.Looper.getMainLooper()).post(r);}
}
'@
        'NativeActivity.java' = 'package android.app; public class NativeActivity extends Activity {}'
        'Application.java' = @'
package android.app;
public class Application extends android.content.Context {
 public ActivityLifecycleCallbacks callbacks;public void onCreate(){}public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){callbacks=c;}
 public interface ActivityLifecycleCallbacks {
  void onActivityCreated(Activity a,android.os.Bundle b);void onActivityStarted(Activity a);void onActivityResumed(Activity a);void onActivityPaused(Activity a);void onActivityStopped(Activity a);void onActivitySaveInstanceState(Activity a,android.os.Bundle b);void onActivityDestroyed(Activity a);
 }
}
'@
        'Looper.java' = 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}'
        'Handler.java' = @'
package android.os;
public class Handler {
 public static java.util.List<Runnable> queue=new java.util.ArrayList<>();public Handler(Looper l){}
 public boolean post(Runnable r){queue.add(r);return true;}public boolean postDelayed(Runnable r,long ms){return true;}
 public static void drain(){java.util.List<Runnable> pending=new java.util.ArrayList<>(queue);queue.clear();for(Runnable r:pending)r.run();}
}
'@
        'NativeProductPanelActivity.java' = 'package io.github.mesmerprism.rustyquest.native_renderer; public class NativeProductPanelActivity extends android.app.NativeActivity {}'
        'ControlPanelActivity.java' = 'package io.github.mesmerprism.rustyquest.native_renderer; public class ControlPanelActivity extends android.app.Activity {}'
        'PanelImmersiveHandoff.java' = 'package io.github.mesmerprism.rustyquest.native_renderer; class PanelImmersiveHandoff {interface ImmersiveForegroundProbe {boolean hasImmersiveWindowFocus();}static void setImmersiveForegroundProbe(ImmersiveForegroundProbe p){}}'
        'NativeRendererSelfKioskService.java' = @'
package io.github.mesmerprism.rustyquest.native_renderer;
class NativeRendererSelfKioskService {
 static boolean resumePendingTerminal(android.content.Context c){return false;}
 static void dispatchTerminal(android.content.Context c,NativeRendererSoftKioskCoordinator.Action a){}
}
'@
    }
    $sources = foreach ($name in $stubs.Keys) {
        $path = Join-Path $Directory $name
        [IO.File]::WriteAllText($path, $stubs[$name], [Text.UTF8Encoding]::new($false))
        $path
    }
    return $sources
}
Export-ModuleMember -Function New-NativeRendererBootHostFixtures
