package io.github.mesmerprism.rustyquest.native_renderer;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/** Opt-in same-process BLE transport for low-rate experiment-session controls. */
final class ExperimentSessionBleServer {
    private static final String TAG = "RustyQuestExperimentBle";
    private static final String PREFS = "experiment-session-ble-control";
    private static final String ENABLED = "enabled";
    private static final String OPEN = "open-control";
    private static final int PERMISSION_REQUEST = 9401;
    private static final int MAX_PEER_NONCES = 128;
    private static final UUID SERVICE = UUID.fromString(ExperimentSessionBleProtocol.SERVICE);
    private static final UUID STATUS = UUID.fromString(ExperimentSessionBleProtocol.STATUS);
    private static final UUID CHALLENGE = UUID.fromString(ExperimentSessionBleProtocol.CHALLENGE);
    private static final UUID COMMAND = UUID.fromString(ExperimentSessionBleProtocol.COMMAND);
    private static final UUID RECEIPT = UUID.fromString(ExperimentSessionBleProtocol.RECEIPT);
    private static ExperimentSessionBleServer process;

    interface Delegate {
        JSONObject snapshot() throws Exception;
        default JSONObject sessionControlReadback() throws Exception { return null; }
        default JSONObject polarSetupReadback() throws Exception { return null; }
        default JSONObject polarStopReadback() throws Exception { return null; }
        default boolean openControlDefault() { return false; }
        void dispatch(CommandRequest command);
    }

    static final class CommandRequest {
        final String id;
        final String operation;
        final String condition;
        final int bias;
        final ExperimentSessionControlCapability.Fence sessionFence;
        final JSONObject polarRequest;
        final JSONObject polarNativeFence;
        JSONObject setupRequest;

        CommandRequest(String id, String operation, String condition, int bias) {
            this(id,operation,condition,bias,null);
        }
        CommandRequest(String id,String operation,String condition,int bias,ExperimentSessionControlCapability.Fence fence) {
            this(id,operation,condition,bias,fence,null,null);
        }
        CommandRequest(String id,String operation,String condition,int bias,ExperimentSessionControlCapability.Fence fence,JSONObject polarRequest,JSONObject polarNativeFence) {
            this.polarRequest=polarRequest;
            this.polarNativeFence=polarNativeFence;
            this.id = id;
            this.operation = operation;
            this.condition = condition;
            this.bias = bias;
            this.sessionFence=fence;
        }
    }

    private static final class Peer {
        final String challenge = ExperimentSessionBleProtocol.newHex(16);
        final Set<String> nonces = new HashSet<>();
        byte[] statusRead;
        byte[] receiptRead;
        byte[] receipt = jsonBytes("{\"v\":1,\"id\":\"\",\"state\":\"none\"}");
        String lastCommandId = "";
    }

    static synchronized ExperimentSessionBleServer process(Context context, Delegate delegate) {
        if (process == null) process = new ExperimentSessionBleServer(context.getApplicationContext());
        if (delegate != null) process.delegate = delegate;
        return process;
    }

    static boolean featurePackaged(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null) return false;
            for (String permission : info.requestedPermissions) {
                if (Manifest.permission.BLUETOOTH_ADVERTISE.equals(permission)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<BluetoothDevice, Peer> peers = new HashMap<>();
    private volatile Delegate delegate;
    private volatile byte[] statusBytes = jsonBytes("{\"v\":1,\"m\":\"gated\",\"f\":\"unknown\",\"p\":\"UNAVAILABLE\"}");
    private BluetoothGattServer gatt;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advertiseCallback;
    private String code = "";
    private int invalidProofs;
    private long blockedUntilMs;
    private boolean active;
    interface GatewayReceiptListener {
        void receipt(JSONObject receipt);
        boolean known(JSONObject body)throws Exception;
        boolean reserve(JSONObject body)throws Exception;
        JSONObject original(String id)throws Exception;
    }
    private static final class GatewayDuplicate extends RuntimeException { }
    private GatewayReceiptListener gatewayReceiptListener;
    private String gatewayDevice="",gatewayEpoch="0";
    synchronized void gatewayBinding(GatewayReceiptListener listener,String device,String epoch){gatewayReceiptListener=listener;gatewayDevice=device;gatewayEpoch=epoch;}
    private boolean commandGatewayAttached;
    synchronized void attachCommandGateway(boolean attached) { commandGatewayAttached=attached; if(attached&&serverGeneration==0)serverGeneration++; }
    private boolean controlAvailable(){return (active||commandGatewayAttached)&&nativeOwnerIdentity>0&&nativeOwnerEpoch>0;}
    private long serverGeneration;
    private long nativeOwnerIdentity;
    private long nativeOwnerEpoch;
    private long nativeOwnerHighWater;
    private JSONObject latestOwnerReceipt;
    private JSONObject latestTerminalReceipt;
    private ExperimentSessionTerminalCorrelation terminalObservation;
    private JSONObject interruptedArmReceipt;
    private int recoveryAttempts;
    private boolean recoveryScheduled;
    private long recoveryToken;
    private final Map<String,Long> admissionTimes=new HashMap<>();
    private final Map<String,Long> effectTimes=new HashMap<>();
    synchronized void recordEffectTimestamp(String id,long ns){if(ExperimentSessionBleProtocol.isLowerHex(id,16)&&ns>0&&effectTimes.size()<256&&!effectTimes.containsKey(id))effectTimes.put(id,ns);}
    private String shellOperationId="";
    synchronized void markShellOperation(String id) { shellOperationId=id; }
    synchronized JSONObject ownerReceipt() throws Exception {
        JSONObject observed=latestTerminalReceipt!=null?latestTerminalReceipt:latestOwnerReceipt;
        JSONObject result=observed==null?new JSONObject():new JSONObject(observed.toString());
        if(interruptedArmReceipt!=null)result.put("interrupted_operation",new JSONObject(interruptedArmReceipt.toString()));
        return result;
    }
    synchronized ExperimentSessionTerminalCorrelation terminalOrigin(String id,long runtime,long session) {
        if(!controlAvailable())throw new IllegalStateException("terminal-carrier-owner-unavailable");
        return new ExperimentSessionTerminalCorrelation(id,serverGeneration,nativeOwnerIdentity,nativeOwnerEpoch,runtime,session,id.equals(shellOperationId)?"shell-experiment-operator-v1":"");
    }
    synchronized ExperimentSessionTerminalCorrelation beginTerminalObservation(String id,long runtime) {
        if(!controlAvailable()||terminalObservation!=null)throw new IllegalStateException("terminal-observation-unavailable");
        terminalObservation=ExperimentSessionTerminalCorrelation.pending(id,serverGeneration,nativeOwnerIdentity,nativeOwnerEpoch,
            runtime,id.equals(shellOperationId)?"shell-experiment-operator-v1":"");
        return terminalObservation;
    }
    synchronized boolean terminalObservationCurrent(ExperimentSessionTerminalCorrelation origin) {
        return origin!=null&&origin==terminalObservation&&controlAvailable()&&origin.carrierGeneration==serverGeneration
            &&origin.nativeOwnerIdentity==nativeOwnerIdentity&&origin.nativeOwnerEpoch==nativeOwnerEpoch;
    }
    synchronized void abandonTerminalObservation(ExperimentSessionTerminalCorrelation origin) {
        if(terminalObservation==origin)terminalObservation=null;
    }
    synchronized void recordInterruptedArm(ExperimentSessionTerminalCorrelation origin,String id,String nativeId,
            String state,String detail,boolean checkpointObserved) {
        if(!terminalObservationCurrent(origin)||!ExperimentSessionBleProtocol.isLowerHex(id,16)
                ||nativeId==null||!nativeId.matches("panel-arm-[1-9][0-9]*")
                ||!("confirmed".equals(state)||"outcome_unknown".equals(state)))return;
        try {
            if(interruptedArmReceipt!=null) {
                if(!id.equals(interruptedArmReceipt.optString("id"))||!nativeId.equals(interruptedArmReceipt.optString("native_operation_id")))return;
                if("confirmed".equals(interruptedArmReceipt.optString("state")))return;
                checkpointObserved|=interruptedArmReceipt.optBoolean("durable_checkpoint_observed",false);
            }
            updateReceipt(id,state,detail); // Existing per-ID gateway/BLE observations remain independent.
            if(!terminalObservationCurrent(origin))return;
            interruptedArmReceipt=new JSONObject().put("id",id).put("native_operation_id",nativeId)
                .put("state",state).put("detail",safeDetail(detail)).put("durable_checkpoint_observed",checkpointObserved)
                .put("terminal_request_id",origin.requestId);
            Log.i(TAG,"channel=experiment-session-terminal-receipt status="+state+" interruptedOperatorId="+id
                +" nativeArmOperationId="+nativeId+" terminalOperatorId="+origin.requestId+" durableCheckpointObserved="+checkpointObserved);
        }catch(Exception unavailable){}
    }
    synchronized boolean publishTerminalObservation(ExperimentSessionTerminalCorrelation origin,
            ExperimentSessionAndroidShell.TerminalResult terminal) {
        if(terminal==null||!terminalObservationCurrent(origin))return false;
        ExperimentSessionTerminalCorrelation.Result joined=origin.observe(serverGeneration,nativeOwnerIdentity,nativeOwnerEpoch,
            terminal.runtimeEpoch,terminal.sessionGeneration,terminal.operationId,terminal.shutdownAckRevision,terminal.shouldFinish,terminal.saved);
        if(joined==null)return false;
        try {
            if(latestTerminalReceipt!=null)return origin.requestId.equals(latestTerminalReceipt.optString("id"))
                &&joined.operationId.equals(latestTerminalReceipt.optString("native_operation_id"))
                &&joined.shutdownAckRevision==latestTerminalReceipt.optLong("shutdown_ack_revision",0);
            updateReceipt(origin.requestId,"confirmed","Recording saved; app is closing.");
            if(!terminalObservationCurrent(origin))return false;
            latestTerminalReceipt=new JSONObject().put("v",1).put("id",origin.requestId)
                .put("state","confirmed").put("detail","Recording saved; app is closing.")
                .put("native_operation_id",joined.operationId).put("runtime_epoch",origin.runtimeEpoch)
                .put("session_generation",origin.sessionGeneration).put("shutdown_ack_revision",joined.shutdownAckRevision)
                .put("carrier_generation",origin.carrierGeneration).put("native_owner_identity",origin.nativeOwnerIdentity)
                .put("native_owner_epoch",origin.nativeOwnerEpoch).put("saved",true).put("completed",true).put("owner_retired",false);
            latestTerminalReceipt.remove("operator_origin");
            if(!origin.operatorOrigin.isEmpty())latestTerminalReceipt.put("operator_origin",origin.operatorOrigin);
            Log.i(TAG,"channel=experiment-session-terminal-receipt status=confirmed operatorId="+origin.requestId
                +" operatorOrigin="+origin.operatorOrigin+" nativeOperationId="+joined.operationId+" runtimeEpoch="+origin.runtimeEpoch+" sessionGeneration="+origin.sessionGeneration
                +" shutdownAckRevision="+joined.shutdownAckRevision+" carrierGeneration="+origin.carrierGeneration
                +" nativeOwnerIdentity="+origin.nativeOwnerIdentity+" nativeOwnerEpoch="+origin.nativeOwnerEpoch+" saved=true completed=true");
            return true;
        }catch(Exception unavailable){return false;}
    }
    private final ExperimentSessionControlCapability sessionControl = new ExperimentSessionControlCapability();
    private final PolarStopBleCapability polarControl = new PolarStopBleCapability();

    private ExperimentSessionBleServer(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        android.content.IntentFilter filter=new android.content.IntentFilter();
        filter.addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(android.content.Intent.ACTION_USER_PRESENT);
        filter.addAction(android.content.Intent.ACTION_SCREEN_ON);
        android.content.BroadcastReceiver recovery=new android.content.BroadcastReceiver(){
            @Override public void onReceive(Context ignored,android.content.Intent event) {
                synchronized(ExperimentSessionBleServer.this) {
                    if(!hasNativeOwner()||!enabled())return;
                    if(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED.equals(event.getAction())
                            &&event.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE,-1)!=android.bluetooth.BluetoothAdapter.STATE_ON) {
                        stopWithReason("radio-unavailable");return;
                    }
                    reconcileNativeOwner(nativeOwnerIdentity,nativeOwnerEpoch);
                }
            }
        };
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(recovery,filter,Context.RECEIVER_EXPORTED);
        else context.registerReceiver(recovery,filter);
    }

    boolean enabled() { return prefs.getBoolean(ENABLED, true); }
    boolean openControl() {
        Delegate current = delegate;
        return prefs.getBoolean(OPEN, current != null && current.openControlDefault());
    }
    synchronized boolean active() { return active; }
    synchronized long currentGeneration() { return controlAvailable() ? serverGeneration : 0L; }
    synchronized long currentNativeEpoch() { return controlAvailable() ? nativeOwnerEpoch : 0L; }
    synchronized boolean acceptsStatusRead(long generation, long epoch) {
        return controlAvailable() && generation > 0L && generation == serverGeneration
            && epoch > 0L && epoch == nativeOwnerEpoch;
    }
    synchronized boolean acceptsNativeEpoch(long epoch) {
        return controlAvailable() && epoch > 0L && epoch == nativeOwnerEpoch;
    }
    synchronized boolean acceptsNativeOwner(long identity,long epoch) {
        return controlAvailable()&&identity==nativeOwnerIdentity&&epoch==nativeOwnerEpoch;
    }
    synchronized boolean updateReceiptForGeneration(long generation,String id,String state,String detail) {
        if(!controlAvailable() || generation!=serverGeneration) return false;
        updateReceipt(id,state,detail);return true;
    }
    synchronized String pairingCode() { return active && !openControl() ? code : ""; }

    /** Actual initialized native owner only; preparation creates no transport. */
    synchronized boolean nativeOwnerStarted(long identity, long epoch) {
        if (identity <= 0L || epoch <= 0L || identity <= nativeOwnerHighWater) return false;
        stopWithReason("native-owner-replaced");
        latestOwnerReceipt=null;latestTerminalReceipt=null;terminalObservation=null;interruptedArmReceipt=null;admissionTimes.clear();effectTimes.clear();
        if(commandGatewayAttached)serverGeneration++;
        nativeOwnerHighWater = identity;
        nativeOwnerIdentity = identity;
        nativeOwnerEpoch = epoch;
        recoveryAttempts=0;recoveryToken++;recoveryScheduled=false;
        startFromNativeOwner();
        if(!active)scheduleRecovery();
        return true;
    }

    synchronized boolean nativeOwnerEnded(long identity, long epoch) {
        if (identity <= 0L || identity != nativeOwnerIdentity || epoch != nativeOwnerEpoch) return false;
        latestOwnerReceipt=null;terminalObservation=null;admissionTimes.clear();effectTimes.clear();
        if(latestTerminalReceipt!=null)try{latestTerminalReceipt.put("owner_retired",true);}catch(Exception ignored){}
        nativeOwnerIdentity = 0L;
        nativeOwnerEpoch = 0L;
        recoveryToken++;recoveryScheduled=false;
        stopWithReason("native-owner-ended");
        return true;
    }

    synchronized void reconcileCurrentNativeOwner(){reconcileNativeOwner(nativeOwnerIdentity,nativeOwnerEpoch);}
    synchronized void reconcileNativeOwner(long identity,long epoch) {
        if(identity<=0||identity!=nativeOwnerIdentity||epoch!=nativeOwnerEpoch||!enabled())return;
        recoveryAttempts=0;recoveryToken++;recoveryScheduled=false;
        if(active&&gatt!=null) {
            try{if(advertiser!=null&&advertiseCallback!=null)advertiser.stopAdvertising(advertiseCallback);}catch(RuntimeException ignored){ }
            advertiseCallback=null;startAdvertising(serverGeneration,gatt);
            main.removeCallbacks(statusRefresh);main.post(statusRefresh);
        }else{startFromNativeOwner();if(!active)scheduleRecovery();}
    }
    private synchronized void scheduleRecovery() {
        if(recoveryScheduled||recoveryAttempts>=6||!hasNativeOwner()||!enabled())return;
        final long token=recoveryToken,identity=nativeOwnerIdentity,epoch=nativeOwnerEpoch;
        long delay=Math.min(8000L,500L<<recoveryAttempts++);recoveryScheduled=true;
        main.postDelayed(new Runnable(){@Override public void run(){synchronized(ExperimentSessionBleServer.this){
            if(token!=recoveryToken||identity!=nativeOwnerIdentity||epoch!=nativeOwnerEpoch)return;
            recoveryScheduled=false;
            if(active&&gatt!=null)startAdvertising(serverGeneration,gatt);else startFromNativeOwner();
            if(!active)scheduleRecovery();
        }}},delay);
    }
    private synchronized void startFromNativeOwner() {
        if (nativeOwnerIdentity <= 0L || nativeOwnerEpoch <= 0L
                || !featurePackaged(context) || !enabled()) return;
        try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                    || context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED)) return;
        start();
        } catch (RuntimeException unavailable) {
            stopWithReason("native-owner-platform-unavailable");
            Log.w(TAG, "status=unavailable reason=native-owner-platform-unavailable", unavailable);
        }
    }

    void setEnabled(boolean value, Activity activity) {
        prefs.edit().putBoolean(ENABLED, value).apply();
        if (value) startFromPanel(activity);
        else stopWithReason("disabled");
    }

    synchronized void setOpenControl(boolean value, Activity activity) {
        if (openControl() == value) return;
        prefs.edit().putBoolean(OPEN, value).apply();
        // Invalidate all connection challenges and any previous pairing code.
        stopWithReason("access-mode-changed");
        if (enabled()) startFromPanel(activity);
    }

    void startFromPanel(Activity activity) {
        if (activity == null || !featurePackaged(activity) || !enabled()
                || !hasNativeOwner()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                    || activity.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[] {
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                }, PERMISSION_REQUEST);
                return;
            }
        }
        startFromNativeOwner();
    }

    void onPermissionResult(Activity activity, int requestCode) {
        if (requestCode != PERMISSION_REQUEST || activity == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                    || activity.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED)) return;
        startFromNativeOwner();
    }

    private synchronized boolean hasNativeOwner() {
        return nativeOwnerIdentity > 0L && nativeOwnerEpoch > 0L;
    }

    private synchronized void start() {
        if (active) return;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled()) {
            Log.w(TAG, "status=unavailable reason=bluetooth-off-or-unavailable");
            return;
        }
        try {
            final long generation = commandGatewayAttached ? serverGeneration : ++serverGeneration;
            GenerationCallback callback = new GenerationCallback(generation);
            gatt = manager.openGattServer(context, callback);
            if (gatt == null) throw new IllegalStateException("gatt-open-failed");
            callback.server = gatt;
            BluetoothGattService service = new BluetoothGattService(
                SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            service.addCharacteristic(readCharacteristic(STATUS));
            service.addCharacteristic(readCharacteristic(CHALLENGE));
            service.addCharacteristic(new BluetoothGattCharacteristic(COMMAND,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE));
            service.addCharacteristic(readCharacteristic(RECEIPT));
            code = ExperimentSessionBleProtocol.newCode();
            invalidProofs = 0;
            blockedUntilMs = 0L;
            active = true;
            if (!gatt.addService(service)) throw new IllegalStateException("gatt-add-service-failed");
            main.post(statusRefresh);
            Log.i(TAG, "status=starting access=" + (openControl() ? "open" : "gated")
                + " serverGeneration=" + generation + " nativeEpoch=" + nativeOwnerEpoch);
        } catch (RuntimeException error) {
            Log.w(TAG, "status=unavailable reason=gatt-start-failed", error);
            stopWithReason("gatt-start-failed");
        }
    }

    synchronized void stop() {
        stopWithReason("explicit-stop");
    }

    private synchronized void stopWithReason(String reason) {
        if(reason.startsWith("native-owner-")){gatewayReceiptListener=null;gatewayDevice="";gatewayEpoch="0";}
        long retiredGeneration = serverGeneration;
        if(!commandGatewayAttached)serverGeneration++;
        active = false;
        sessionControl.clear();
        polarControl.clear();
        main.removeCallbacks(statusRefresh);
        if (advertiser != null && advertiseCallback != null) {
            try { advertiser.stopAdvertising(advertiseCallback); }
            catch (RuntimeException ignored) { }
        }
        if (gatt != null) {
            try { gatt.clearServices(); } catch (RuntimeException ignored) { }
            try { gatt.close(); } catch (RuntimeException ignored) { }
        }
        peers.clear();
        statusBytes = jsonBytes("{\"v\":1,\"m\":\"gated\",\"f\":\"unknown\",\"p\":\"UNAVAILABLE\"}");
        advertiser = null;
        advertiseCallback = null;
        gatt = null;
        code = "";
        Log.i(TAG, "status=stopped reason=" + reason + " retiredGeneration="
            + retiredGeneration + " serverGeneration=" + serverGeneration);
    }

    synchronized void updateReceipt(String id, String state, String detail) {
        if (!ExperimentSessionBleProtocol.isLowerHex(id, 16)) return;
        if (!Arrays.asList("accepted", "pending", "confirmed", "rejected",
                "outcome_unknown", "dispatched").contains(state)) return;
        try {
            JSONObject receipt = new JSONObject().put("v", 1).put("id", id)
                .put("state", state).put("detail", safeDetail(detail));
            long now=SystemClock.elapsedRealtime();
            if("accepted".equals(state)&&!admissionTimes.containsKey(id)&&admissionTimes.size()<256)admissionTimes.put(id,now);
            if(admissionTimes.containsKey(id))receipt.put("admit_ms",admissionTimes.get(id));
            receipt.put("publish_ms",now);
            if(effectTimes.containsKey(id))receipt.put("effect_ns",effectTimes.get(id));
            boolean selected=terminalObservation==null||terminalObservation.requestId.equals(id);
            if(selected) {
                latestOwnerReceipt = new JSONObject(receipt.toString());
                String origin=terminalObservation!=null?terminalObservation.operatorOrigin:
                    (id.equals(shellOperationId)?"shell-experiment-operator-v1":"");
                if(!origin.isEmpty())latestOwnerReceipt.put("operator_origin",origin);
            }
            if(gatewayReceiptListener!=null)gatewayReceiptListener.receipt(receipt);
            byte[] bytes = jsonBytes(receipt.toString());
            for (Peer peer : peers.values()) {
                if (id.equals(peer.lastCommandId)) {
                    peer.receipt = bytes;
                    peer.receiptRead = null;
                }
            }
        } catch (Exception ignored) { }
    }

    private static String safeDetail(String detail) {
        if (detail == null) return "";
        String value = detail.replaceAll("[^A-Za-z0-9 .,:;_()!?-]", "").trim();
        return value.substring(0, Math.min(96, value.length()));
    }

    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() {
            final long generation, identity, epoch;
            final Delegate target;
            final boolean open;
            synchronized (ExperimentSessionBleServer.this) {
                if (!active) return;
                generation = serverGeneration;
                identity = nativeOwnerIdentity;
                epoch = nativeOwnerEpoch;
                target = delegate;
                open = openControl();
            }
            byte[] next = jsonBytes("{\"v\":1,\"m\":\"gated\",\"f\":\"unknown\",\"p\":\"UNAVAILABLE\"}");
            if (target != null) {
                try {
                    JSONObject status = target.snapshot();
                    status.put("v", 1).put("m", open ? "open" : "gated");
                    JSONObject nativeReadback=target.sessionControlReadback();
                    JSONObject setup=target.polarSetupReadback();
                    if(setup!=null&&"IDLE".equals(status.optString("p")))status.put("y",setup);
                    JSONObject polarOffer=polarControl.offer(target.polarStopReadback(),nativeReadback,
                        generation,identity,epoch,SystemClock.elapsedRealtime());
                    JSONObject capability = sessionControl.offer(status,nativeReadback,
                        generation,identity,epoch,SystemClock.elapsedRealtime(),polarControl.bindingTuple());
                    if(capability!=null)status.put("q",capability);
                    if(capability!=null&&polarOffer!=null) {
                        polarControl.shareReference(capability.getString("s"));polarOffer.put("s",capability.getString("s"));
                    }
                    byte[] bytes = polarControl.projectBoundedStatus(status,polarOffer);
                    if(status.has("q")&&!new JSONObject(new String(bytes,StandardCharsets.UTF_8)).has("q"))sessionControl.clear();
                    if(bytes.length>480 && status.has("q")) {
                        status.remove("q");sessionControl.clear();bytes=jsonBytes(status.toString());
                    }
                    if (bytes.length <= 480) next = bytes;
                } catch (Exception error) {
                    sessionControl.clear();polarControl.clear();
                    Log.w(TAG, "status=snapshot-unavailable", error);
                }
            }
            synchronized (ExperimentSessionBleServer.this) {
                if (!active || generation != serverGeneration
                        || identity != nativeOwnerIdentity || epoch != nativeOwnerEpoch) return;
                statusBytes = next;
                main.postDelayed(this, 750L);
            }
        }
    };

    private boolean currentServer(long generation, BluetoothGattServer server) {
        return active && server != null && gatt == server && serverGeneration == generation;
    }

    private synchronized void startAdvertising(final long generation, final BluetoothGattServer server) {
        if (!currentServer(generation, server)) return;
        if(advertiseCallback!=null){try{if(advertiser!=null)advertiser.stopAdvertising(advertiseCallback);}catch(RuntimeException ignored){ }advertiseCallback=null;}
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager == null || manager.getAdapter() == null){scheduleRecovery();return;}
        advertiser = manager.getAdapter().getBluetoothLeAdvertiser();
        if (advertiser == null){scheduleRecovery();return;}
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true).setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build();
        AdvertiseData data = new AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceUuid(new ParcelUuid(SERVICE)).build();
        String adapterName = null;
        try { adapterName = manager.getAdapter().getName(); }
        catch (RuntimeException unavailable) { /* Naming must not change service discovery. */ }
        final String nameDisposition = ExperimentSessionBleNamePolicy.disposition(adapterName);
        // Android supplies the existing adapter name. Never rename the shared adapter.
        // The scan response has only this optional field (31 bytes minus its two-byte AD header).
        AdvertiseData scanResponse = new AdvertiseData.Builder()
            .setIncludeDeviceName("included".equals(nameDisposition)).build();
        advertiseCallback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings effective) {
                synchronized (ExperimentSessionBleServer.this) {
                    if (!currentServer(generation, server) || advertiseCallback != this) return;
                    Log.i(TAG, "status=advertising scanResponseName=" + nameDisposition);
                }
            }
            @Override public void onStartFailure(int code) {
                synchronized (ExperimentSessionBleServer.this) {
                    if (!currentServer(generation, server) || advertiseCallback != this) return;
                    advertiseCallback=null;
                    scheduleRecovery();
                    Log.w(TAG, "status=advertise-failed code=" + code);
                }
            }
        };
        // A name change between sizing and dispatch may still be rejected by Android.
        // Recovery attempts are bounded and remain tied to the same native owner.
        try { advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback); }
        catch (RuntimeException error) { advertiseCallback=null;scheduleRecovery();Log.w(TAG, "status=advertise-failed", error); }
    }

    private static BluetoothGattCharacteristic readCharacteristic(UUID uuid) {
        return new BluetoothGattCharacteristic(uuid,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ);
    }

    private static byte[] jsonBytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private void read(BluetoothDevice device, int requestId, int offset, byte[] bytes) {
        BluetoothGattServer server = gatt;
        if (server == null) return;
        int status = offset >= 0 && offset <= bytes.length
            ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_INVALID_OFFSET;
        server.sendResponse(device, requestId, status, offset,
            status == BluetoothGatt.GATT_SUCCESS ? Arrays.copyOfRange(bytes, offset, bytes.length) : null);
    }

    private final class GenerationCallback extends BluetoothGattServerCallback {
        private final long generation;
        private BluetoothGattServer server;
        GenerationCallback(long generation) { this.generation = generation; }
        private boolean current() { return currentServer(generation, server); }
        @Override public void onServiceAdded(int status, BluetoothGattService service) {
            synchronized (ExperimentSessionBleServer.this) {
                if (!current()) return;
                if (status == BluetoothGatt.GATT_SUCCESS && SERVICE.equals(service.getUuid())) startAdvertising(generation, server);
                else {stopWithReason("gatt-service-failed");scheduleRecovery();Log.w(TAG, "status=gatt-service-failed code=" + status);}

            }
        }

        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int next) {
            synchronized (ExperimentSessionBleServer.this) {
                Log.i(TAG, "status=gatt-connection callbackStatus=" + status
                    + " nextState=" + next + " callbackGeneration=" + generation
                    + " serverGeneration=" + serverGeneration + " current=" + current());
                if (!current()) return;
                synchronized (ExperimentSessionBleServer.this) {
                    if (next == BluetoothProfile.STATE_CONNECTED) peers.put(device, new Peer());
                    if (next == BluetoothProfile.STATE_DISCONNECTED) peers.remove(device);
                }

            }
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattCharacteristic characteristic) {
            synchronized (ExperimentSessionBleServer.this) {
                if (!current()) return;
                Peer peer;
                synchronized (ExperimentSessionBleServer.this) {
                    peer = peers.get(device);
                    if (peer == null) { peer = new Peer(); peers.put(device, peer); }
                }
                UUID uuid = characteristic.getUuid();
                if (STATUS.equals(uuid)) {
                    if (offset == 0 || peer.statusRead == null) peer.statusRead = statusBytes;
                    read(device, requestId, offset, peer.statusRead);
                } else if (CHALLENGE.equals(uuid)) {
                    read(device, requestId, offset,
                        jsonBytes("{\"v\":1,\"n\":\"" + peer.challenge + "\""
                            +(gatewayDevice.isEmpty()?"":",\"d\":\""+gatewayDevice+"\",\"ge\":\""+gatewayEpoch+"\"")+"}"));
                } else if (RECEIPT.equals(uuid)) {
                    if (offset == 0 || peer.receiptRead == null) {
                        // Reconnection observes the original current-owner outcome without
                        // adopting command authority or dispatching the action again.
                        peer.receiptRead = peer.lastCommandId.isEmpty()&&latestOwnerReceipt!=null
                            ? jsonBytes(latestOwnerReceipt.toString()) : peer.receipt;
                    }
                    read(device, requestId, offset, peer.receiptRead);
                } else if (gatt != null) {
                    gatt.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset, null);
                }

            }
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            synchronized (ExperimentSessionBleServer.this) {
                if (!current()) return;
                int response = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                CommandRequest accepted = null;
                if (COMMAND.equals(characteristic.getUuid()) && !preparedWrite && offset == 0
                        && value != null && value.length <= ExperimentSessionBleProtocol.MAX_COMMAND_BYTES) {
                    try {
                        JSONObject body = new JSONObject(new String(value, StandardCharsets.UTF_8));
                        boolean sessionVersion = Integer.valueOf(2).equals(body.opt("v")) || Long.valueOf(2).equals(body.opt("v"));
                        boolean setupVersion = Integer.valueOf(4).equals(body.opt("v")) || Long.valueOf(4).equals(body.opt("v"));
                        JSONObject setupRequest=null;
                        boolean polarVersion = Integer.valueOf(3).equals(body.opt("v")) || Long.valueOf(3).equals(body.opt("v"));
                        JSONObject polarRequest=null;
                        JSONObject polarNativeFence=null;
                        ExperimentSessionControlCapability.Fence sessionFence = null;
                        String id = body.optString("id", "");
                        String operation = body.optString("op", "");
                        String condition = body.optString("condition", "");
                        String nonce = body.optString("nonce", "");
                        int bias = body.optInt("bias", -1);
                        String session="";long sessionGeneration=0L;
                        if(setupVersion) {
                            setupRequest=ExperimentSessionBleProtocol.parseSetup(body);
                            id=body.getString("id");operation=body.getString("op");nonce=body.getString("n");condition="";bias=0;
                        } else if(polarVersion) {
                            PolarStopBleCapability.Wire wire=PolarStopBleCapability.parse(body);
                            id=wire.id;operation=wire.operation;session=wire.reference;nonce=wire.nonce;
                            sessionGeneration=wire.revision;condition="";bias=0;
                        } else if(sessionVersion) {
                            Set<String> fields=new HashSet<>(Arrays.asList("v","id","op","s","g","n"));
                            if(body.has("mac"))fields.add("mac");
                            if("set-bias".equals(body.optString("op")))fields.add("b");
                            if(body.length()!=fields.size())throw new IllegalArgumentException("closed-session-command-required");
                            for(String field:fields)if(!body.has(field))throw new IllegalArgumentException("missing-session-command-field");
                            id=ExperimentSessionControlCapability.string(body,"id");
                            operation=ExperimentSessionControlCapability.string(body,"op");
                            session=ExperimentSessionControlCapability.string(body,"s");
                            nonce=ExperimentSessionControlCapability.string(body,"n");
                            sessionGeneration=ExperimentSessionControlCapability.positive(body,"g");
                            if(body.has("mac"))ExperimentSessionControlCapability.string(body,"mac");
                            condition="";bias=ExperimentSessionBleProtocol.sessionBias(body);
                            if(!ExperimentSessionBleProtocol.validSessionFields(id,operation,session,sessionGeneration,nonce))
                                throw new IllegalArgumentException("invalid-session-command");
                        } else if (body.optInt("v", 0) != 1
                                || !ExperimentSessionBleProtocol.validCommandFields(
                                    id, operation, condition, bias, nonce)) {
                            throw new IllegalArgumentException("invalid-command-fields");
                        }
                        synchronized (ExperimentSessionBleServer.this) {
                            Peer peer = peers.get(device);
                            if (peer == null || peer.nonces.size() >= MAX_PEER_NONCES
                                    || !peer.nonces.add(nonce)
                                    || SystemClock.elapsedRealtime() < blockedUntilMs) {
                                throw new SecurityException("peer-replay-or-rate-limit");
                            }
                            boolean authenticated=setupVersion ? ExperimentSessionBleProtocol.verifySetup(code,peer.challenge,body) : polarVersion ? ExperimentSessionBleProtocol.verifyPolar(
                                code,peer.challenge,id,operation,session,sessionGeneration,nonce,body.optString("mac","")) : sessionVersion ? ExperimentSessionBleProtocol.verifySession(
                                code,peer.challenge,id,operation,session,sessionGeneration,nonce,body.optString("mac",""),bias)
                                : ExperimentSessionBleProtocol.verify(
                                    code, peer.challenge, id, operation, condition, bias, nonce,
                                    body.optString("mac", ""));
                            if (!openControl() && !authenticated) {
                                invalidProofs++;
                                if (invalidProofs >= 5) blockedUntilMs = SystemClock.elapsedRealtime() + 60000L;
                                throw new SecurityException("authentication-failed");
                            }
                            if(commandGatewayAttached&&gatewayReceiptListener==null)throw new SecurityException("gateway-owner-rebinding");
                            if(gatewayReceiptListener!=null&&gatewayReceiptListener.known(body)) {
                                peer.lastCommandId=id;peer.receipt=jsonBytes(gatewayReceiptListener.original(id).toString());peer.receiptRead=null;
                                throw new GatewayDuplicate();
                            }
                            if(polarVersion) {
                                polarNativeFence=polarControl.nativeFence();
                                polarRequest=polarControl.admit(session,sessionGeneration,operation,
                                    SystemClock.elapsedRealtime(),serverGeneration,nativeOwnerIdentity,nativeOwnerEpoch);
                            }
                            if(sessionVersion)sessionFence=sessionControl.admit(session,sessionGeneration,operation,
                                SystemClock.elapsedRealtime(),serverGeneration,nativeOwnerIdentity,nativeOwnerEpoch);
                        }
                        synchronized (ExperimentSessionBleServer.this) {
                            Peer peer = peers.get(device);
                            if (peer == null) throw new SecurityException("peer-disconnected");
                            peer.lastCommandId = id;
                        }
                        if(setupVersion && setupRequest.getLong("g")!=nativeOwnerEpoch)throw new SecurityException("setup-native-epoch-stale");
                        if(gatewayReceiptListener!=null&&!gatewayReceiptListener.reserve(body)) {
                            Peer peer=peers.get(device);peer.receipt=jsonBytes(gatewayReceiptListener.original(id).toString());peer.receiptRead=null;
                            throw new GatewayDuplicate();
                        }
                        accepted = new CommandRequest(id, operation, condition, bias,sessionFence,polarRequest,polarNativeFence);
                        accepted.setupRequest=setupRequest;
                        updateReceipt(id, "accepted", "Command admitted by Quest BLE transport.");
                        response = BluetoothGatt.GATT_SUCCESS;
                    } catch (GatewayDuplicate original) {
                        response = BluetoothGatt.GATT_SUCCESS;
                    } catch (Exception rejected) {
                        response = BluetoothGatt.GATT_FAILURE;
                        Log.w(TAG, "status=command-rejected reason=invalid-or-unauthorized");
                    }
                }
                if (responseNeeded && gatt != null) {
                    gatt.sendResponse(device, requestId, response, offset, null);
                }
                if (accepted != null) {
                    final CommandRequest request = accepted;
                    main.post(new Runnable() {
                        @Override public void run() {
                            final Delegate target;
                            synchronized (ExperimentSessionBleServer.this) {
                                if (!current()) return;
                                target = delegate;
                            }
                            if (target == null) {
                                updateReceipt(request.id, "rejected", "App command owner unavailable.");
                                return;
                            }
                            // Main-thread owner dispatch must not hold the carrier registry lock
                            // across a callback into the session or application lifetime owner.
                            try { target.dispatch(request); }
                            catch (RuntimeException error) {
                                updateReceipt(request.id, "rejected", "App command dispatch failed.");
                                Log.w(TAG, "status=dispatch-failed", error);
                            }
                        }
                    });
                }

            }
        }
    }
}
