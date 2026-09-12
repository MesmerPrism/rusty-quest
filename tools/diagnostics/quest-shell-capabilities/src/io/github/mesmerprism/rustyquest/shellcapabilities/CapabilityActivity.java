package io.github.mesmerprism.rustyquest.shellcapabilities;

import android.app.Activity;import android.os.Bundle;
public final class CapabilityActivity extends Activity{
  protected void onCreate(Bundle b){super.onCreate(b);handle(getIntent());}
  protected void onNewIntent(android.content.Intent i){super.onNewIntent(i);setIntent(i);handle(i);}
  protected void onResume(){super.onResume();CapabilityRuntime.resumed=true;android.util.Log.i(CapabilityRuntime.TAG,"ACTIVITY resumed=true");}
  protected void onPause(){CapabilityRuntime.resumed=false;android.util.Log.i(CapabilityRuntime.TAG,"ACTIVITY resumed=false");super.onPause();}
  private void handle(android.content.Intent i){try{String action=i.getAction();if(action.endsWith(".POST_DROP_PROBE")){CapabilityProvider.startPostDropProbe(this,i.getStringExtra("run_token"),i.getLongExtra("post_drop_delay_ms",0));finish();}else if(action.endsWith(".BLE_STOP")){BleRuntime.stop(i.getStringExtra("run_token"));finish();}else if(action.endsWith(".BLE_START"))BleRuntime.start(this,i);else if(action.endsWith(".STOP")){CapabilityRuntime.stop(i.getStringExtra("run_token"));finish();}else CapabilityRuntime.start(this,i);}catch(Throwable e){CapabilityRuntime.log("activity_error",e);finish();}}
}
