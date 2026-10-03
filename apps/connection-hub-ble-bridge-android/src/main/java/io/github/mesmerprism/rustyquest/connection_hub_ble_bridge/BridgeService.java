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
    private long ownedGeneration;
    private final Handler handler=new Handler(Looper.getMainLooper());private HubGattBridge bridge;
    private final Runnable expiry=new Runnable(){public void run(){BridgeController.CURRENT.failure(ownedGeneration,"deadline_expired");stopCarrier();stopSelf();}};
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||intent.getExtras()!=null&&!intent.getExtras().isEmpty()){stopSelf(startId);return START_NOT_STICKY;}
        String action=intent.getAction();if(STOP.equals(action)){try{new BridgeController(this).disable();}catch(Exception denied){BridgeController.CURRENT.failure(ownedGeneration,"stop_dispatch_failed");}return START_NOT_STICKY;}
        if(!START.equals(action)){stopSelf(startId);return START_NOT_STICKY;}
        if(bridge!=null)return START_NOT_STICKY; // A repeated start never renews the existing scope.
        ownedGeneration=BridgeController.CURRENT.generation();
        if(!BridgeController.CURRENT.mayStart(ownedGeneration)){stopSelf(startId);return START_NOT_STICKY;}
        if(checkSelfPermission(HubReadiness.PERMISSION)!=PackageManager.PERMISSION_GRANTED||Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)){BridgeController.CURRENT.failure(ownedGeneration,"service_permissions_denied");stopSelf(startId);return START_NOT_STICKY;}
        try{NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("hub-ble-carrier","Hub BLE carrier",NotificationManager.IMPORTANCE_LOW));
        Intent stopIntent=new Intent(this,BridgeService.class).setAction(STOP);
        PendingIntent stop=PendingIntent.getService(this,301,stopIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notice=new Notification.Builder(this,"hub-ble-carrier").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("Hub BLE carrier requested").setContentText("15-minute transport bound; existing grants required").setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop carrier",stop).build()).build();
        // Foreground timing precedes the potentially blocking exact Hub provider/readiness call.
        startForeground(301,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);long deadline=SystemClock.elapsedRealtime()+900_000;if(!BridgeController.CURRENT.serviceStarted(ownedGeneration,deadline)){stopSelf(startId);return START_NOT_STICKY;}
            final long generation=ownedGeneration;
            bridge=new HubGattBridge(this,deadline,new HubGattBridge.Observer(){
                public void advertising(){BridgeController.CURRENT.advertising(generation);}
                public void failed(final String code){BridgeController.CURRENT.failure(generation,code);handler.post(new Runnable(){public void run(){if(ownedGeneration==generation&&BridgeController.CURRENT.generation()==generation){stopCarrier();stopSelf();}}});}
            });handler.postDelayed(expiry,Math.max(0,deadline-SystemClock.elapsedRealtime()));bridge.start();}catch(Exception denied){BridgeController.CURRENT.failure(ownedGeneration,"service_start_failed");stopCarrier();stopSelf();}
        return START_NOT_STICKY;
    }
    private void stopCarrier(){handler.removeCallbacks(expiry);if(bridge!=null)bridge.close();bridge=null;stopForeground(true);}
    @Override public void onDestroy(){stopCarrier();BridgeController.CURRENT.destroyed(ownedGeneration);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
