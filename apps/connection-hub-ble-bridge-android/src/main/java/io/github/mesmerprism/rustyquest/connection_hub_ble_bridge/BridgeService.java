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
    static final String SHELL_START="io.github.mesmerprism.rustyquest.connection_hub_ble_bridge.SHELL_START";
    static final String STOP="io.github.mesmerprism.rustyquest.connection_hub_ble_bridge.STOP";
    private long ownedGeneration;
    private final String notificationStopToken=java.util.UUID.randomUUID().toString();
    private final Handler handler=new Handler(Looper.getMainLooper());private HubGattBridge bridge;
    private final Runnable expiry=new Runnable(){public void run(){BridgeController.CURRENT.failure(ownedGeneration,"deadline_expired");stopCarrier();stopSelf();}};
    private int rejectStart(int startId){
        // Only the latest cold start can be removed. False means a newer request remains;
        // this is never evidence of effective service closure.
        if(ownedGeneration==0&&bridge==null)stopSelfResult(startId);
        return START_NOT_STICKY;
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return rejectStart(startId);
        String action=intent.getAction();
        // Notification stop is process-owned; no exported arbitrary STOP dispatch.
        if(STOP.equals(action)){
            if(intent.getExtras()==null||intent.getExtras().size()!=3
                || !BridgeController.CURRENT.processInstance.equals(intent.getStringExtra("process_instance_id"))
                || !notificationStopToken.equals(intent.getStringExtra("notification_stop_token"))
                || intent.getLongExtra("generation",-1)!=ownedGeneration || ownedGeneration<=0)return rejectStart(startId);
            try{new BridgeController(this).disable();}catch(Exception denied){BridgeController.CURRENT.failure(ownedGeneration,"stop_dispatch_failed");}
            return START_NOT_STICKY;
        }
        boolean shell=SHELL_START.equals(action);
        if(!shell&&!START.equals(action))return rejectStart(startId);
        if(bridge!=null)return rejectStart(startId);
        Bundle extra=intent.getExtras();
        if(extra==null||!BridgeController.acceptStartFields(BridgeController.CURRENT,shell,extra.size(),
            extra.get("process_instance_id"),extra.get("generation"),extra.get("internal_token"),SystemClock.elapsedRealtime()))return rejectStart(startId);
        long expected=extra.getLong("generation");
        ownedGeneration=expected;
        if(checkSelfPermission(HubReadiness.PERMISSION)!=PackageManager.PERMISSION_GRANTED||Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)){BridgeController.CURRENT.failure(ownedGeneration,"service_permissions_denied");stopSelf(startId);return START_NOT_STICKY;}
        try{NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("hub-ble-carrier","Hub BLE carrier",NotificationManager.IMPORTANCE_LOW));
        Intent stopIntent=new Intent(this,BridgeService.class).setAction(STOP).putExtra("process_instance_id",BridgeController.CURRENT.processInstance).putExtra("generation",ownedGeneration).putExtra("notification_stop_token",notificationStopToken);
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
    @Override public void onDestroy(){stopCarrier();if(ownedGeneration>0)BridgeController.CURRENT.destroyed(ownedGeneration);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
