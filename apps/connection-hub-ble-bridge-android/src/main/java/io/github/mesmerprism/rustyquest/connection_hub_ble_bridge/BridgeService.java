package io.github.mesmerprism.rustyquest.connection_hub_ble_bridge;
import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Package-private wearer-started transport lifetime, independent of the resumed streaming Activity. */
public final class BridgeService extends Service {
    static final String START="io.github.mesmerprism.rustyquest.connection_hub_ble_bridge.START";
    static final String STOP="io.github.mesmerprism.rustyquest.connection_hub_ble_bridge.STOP";
    private final Handler handler=new Handler(Looper.getMainLooper());private HubGattBridge bridge;
    private final Runnable expiry=new Runnable(){public void run(){stopCarrier();stopSelf();}};
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||intent.getExtras()!=null&&!intent.getExtras().isEmpty()){stopSelf(startId);return START_NOT_STICKY;}
        String action=intent.getAction();if(STOP.equals(action)){stopCarrier();stopSelf();return START_NOT_STICKY;}
        if(!START.equals(action)){stopSelf(startId);return START_NOT_STICKY;}
        if(bridge!=null)return START_NOT_STICKY; // A repeated start never renews the existing scope.
        if(checkSelfPermission(HubReadiness.PERMISSION)!=PackageManager.PERMISSION_GRANTED||Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)){stopSelf(startId);return START_NOT_STICKY;}
        NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("hub-ble-carrier","Hub BLE carrier",NotificationManager.IMPORTANCE_LOW));
        Intent stopIntent=new Intent(this,BridgeService.class).setAction(STOP);
        PendingIntent stop=PendingIntent.getService(this,301,stopIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notice=new Notification.Builder(this,"hub-ble-carrier").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("Hub BLE carrier requested").setContentText("15-minute transport bound; existing grants required").setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop carrier",stop).build()).build();
        // Foreground timing precedes the potentially blocking exact Hub provider/readiness call.
        try{startForeground(301,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);long deadline=SystemClock.elapsedRealtime()+900_000;bridge=new HubGattBridge(this,deadline);handler.postDelayed(expiry,Math.max(0,deadline-SystemClock.elapsedRealtime()));bridge.start();}catch(Exception denied){stopCarrier();stopSelf();}
        return START_NOT_STICKY;
    }
    private void stopCarrier(){handler.removeCallbacks(expiry);if(bridge!=null)bridge.close();bridge=null;stopForeground(true);}
    @Override public void onDestroy(){stopCarrier();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
