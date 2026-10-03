package io.github.mesmerprism.rustyquest.connection_hub_ble_bridge;

import android.Manifest;
import android.app.Activity;
import android.os.*;
import android.content.pm.PackageManager;
import android.widget.*;

/** Inert launcher; one explicit foreground opt-in bounded to fifteen minutes. */
public final class BridgeActivity extends Activity {
    private TextView state;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);state=new TextView(this);state.setText("BLE carrier stopped. Start the actual Hub and use a genuine controller session. This helper grants no control authority.");layout.addView(state);
        Button start=new Button(this);start.setText("Enable foreground BLE carrier");start.setOnClickListener(new android.view.View.OnClickListener(){public void onClick(android.view.View v){enable();}});layout.addView(start);Button close=new Button(this);close.setText("Stop carrier");close.setOnClickListener(new android.view.View.OnClickListener(){public void onClick(android.view.View v){stopBridge();}});layout.addView(close);setContentView(layout);}
    private void enable(){stopBridge();if(Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE},301);state.setText("Permission requested. Enable again after permission is granted.");return;}
        try{new HubReadiness(this).requireCurrent();startForegroundService(new android.content.Intent(this,BridgeService.class).setAction(BridgeService.START));state.setText("Foreground carrier requested; actual advertising may still be unavailable. The notification owns Stop and the15-minute scope while the streaming Activity resumes.");}catch(Exception denied){state.setText("Carrier unavailable: "+denied.getClass().getSimpleName()+". Actual Hub listener, matching signature permission and Bluetooth permissions are required.");}}
    private void stopBridge(){stopService(new android.content.Intent(this,BridgeService.class));if(state!=null)state.setText("Carrier stopped; no retained session or authority claim.");}
}
