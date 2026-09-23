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
        void dispatch(CommandRequest command);
    }

    static final class CommandRequest {
        final String id;
        final String operation;
        final String condition;
        final int bias;

        CommandRequest(String id, String operation, String condition, int bias) {
            this.id = id;
            this.operation = operation;
            this.condition = condition;
            this.bias = bias;
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

    private ExperimentSessionBleServer(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean enabled() { return prefs.getBoolean(ENABLED, true); }
    boolean openControl() { return prefs.getBoolean(OPEN, false); }
    synchronized boolean active() { return active; }
    synchronized String pairingCode() { return active && !openControl() ? code : ""; }

    void setEnabled(boolean value, Activity activity) {
        prefs.edit().putBoolean(ENABLED, value).apply();
        if (value) startFromPanel(activity);
        else stop();
    }

    void setOpenControl(boolean value, Activity activity) {
        prefs.edit().putBoolean(OPEN, value).apply();
        // Invalidate all connection challenges and any previous pairing code.
        stop();
        if (enabled()) startFromPanel(activity);
    }

    void startFromPanel(Activity activity) {
        if (activity == null || !featurePackaged(activity) || !enabled()) return;
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
        start();
    }

    void onPermissionResult(Activity activity, int requestCode) {
        if (requestCode != PERMISSION_REQUEST || activity == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                    || activity.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED)) return;
        start();
    }

    private synchronized void start() {
        if (active) return;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled()) {
            Log.w(TAG, "status=unavailable reason=bluetooth-off-or-unavailable");
            return;
        }
        try {
            gatt = manager.openGattServer(context, callback);
            if (gatt == null) throw new IllegalStateException("gatt-open-failed");
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
            Log.i(TAG, "status=starting access=" + (openControl() ? "open" : "gated"));
        } catch (RuntimeException error) {
            Log.w(TAG, "status=unavailable reason=gatt-start-failed", error);
            stop();
        }
    }

    synchronized void stop() {
        active = false;
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
        advertiser = null;
        advertiseCallback = null;
        gatt = null;
        code = "";
        Log.i(TAG, "status=stopped");
    }

    synchronized void updateReceipt(String id, String state, String detail) {
        if (!ExperimentSessionBleProtocol.isLowerHex(id, 16)) return;
        if (!Arrays.asList("accepted", "pending", "confirmed", "rejected",
                "outcome_unknown").contains(state)) return;
        try {
            JSONObject receipt = new JSONObject().put("v", 1).put("id", id)
                .put("state", state).put("detail", safeDetail(detail));
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
            if (!active()) return;
            Delegate target = delegate;
            if (target != null) {
                try {
                    JSONObject status = target.snapshot();
                    status.put("v", 1).put("m", openControl() ? "open" : "gated");
                    byte[] bytes = jsonBytes(status.toString());
                    if (bytes.length <= 480) statusBytes = bytes;
                    else statusBytes = jsonBytes("{\"v\":1,\"m\":\"gated\",\"f\":\"unknown\",\"p\":\"UNAVAILABLE\"}");
                } catch (Exception error) {
                    Log.w(TAG, "status=snapshot-unavailable", error);
                }
            }
            main.postDelayed(this, 750L);
        }
    };

    private void startAdvertising() {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager == null || manager.getAdapter() == null) return;
        advertiser = manager.getAdapter().getBluetoothLeAdvertiser();
        if (advertiser == null) return;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true).setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build();
        AdvertiseData data = new AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceUuid(new ParcelUuid(SERVICE)).build();
        advertiseCallback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings effective) {
                Log.i(TAG, "status=advertising");
            }
            @Override public void onStartFailure(int code) {
                Log.w(TAG, "status=advertise-failed code=" + code);
            }
        };
        try { advertiser.startAdvertising(settings, data, advertiseCallback); }
        catch (RuntimeException error) { Log.w(TAG, "status=advertise-failed", error); }
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

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int status, BluetoothGattService service) {
            if (status == BluetoothGatt.GATT_SUCCESS && active()) startAdvertising();
            else Log.w(TAG, "status=gatt-service-failed code=" + status);
        }

        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int next) {
            synchronized (ExperimentSessionBleServer.this) {
                if (next == BluetoothProfile.STATE_CONNECTED) peers.put(device, new Peer());
                if (next == BluetoothProfile.STATE_DISCONNECTED) peers.remove(device);
            }
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattCharacteristic characteristic) {
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
                    jsonBytes("{\"v\":1,\"n\":\"" + peer.challenge + "\"}"));
            } else if (RECEIPT.equals(uuid)) {
                if (offset == 0 || peer.receiptRead == null) peer.receiptRead = peer.receipt;
                read(device, requestId, offset, peer.receiptRead);
            } else if (gatt != null) {
                gatt.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset, null);
            }
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            int response = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
            CommandRequest accepted = null;
            if (COMMAND.equals(characteristic.getUuid()) && !preparedWrite && offset == 0
                    && value != null && value.length <= ExperimentSessionBleProtocol.MAX_COMMAND_BYTES) {
                try {
                    JSONObject body = new JSONObject(new String(value, StandardCharsets.UTF_8));
                    String id = body.optString("id", "");
                    String operation = body.optString("op", "");
                    String condition = body.optString("condition", "");
                    String nonce = body.optString("nonce", "");
                    int bias = body.optInt("bias", -1);
                    if (body.optInt("v", 0) != 1
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
                        if (!openControl() && !ExperimentSessionBleProtocol.verify(
                                code, peer.challenge, id, operation, condition, bias, nonce,
                                body.optString("mac", ""))) {
                            invalidProofs++;
                            if (invalidProofs >= 5) blockedUntilMs = SystemClock.elapsedRealtime() + 60000L;
                            throw new SecurityException("authentication-failed");
                        }
                    }
                    synchronized (ExperimentSessionBleServer.this) {
                        Peer peer = peers.get(device);
                        if (peer == null) throw new SecurityException("peer-disconnected");
                        peer.lastCommandId = id;
                    }
                    accepted = new CommandRequest(id, operation, condition, bias);
                    updateReceipt(id, "accepted", "Command admitted by Quest BLE transport.");
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
                        Delegate target = delegate;
                        if (target == null) {
                            updateReceipt(request.id, "rejected", "App command owner unavailable.");
                            return;
                        }
                        try { target.dispatch(request); }
                        catch (RuntimeException error) {
                            updateReceipt(request.id, "rejected", "App command dispatch failed.");
                            Log.w(TAG, "status=dispatch-failed", error);
                        }
                    }
                });
            }
        }
    };
}
