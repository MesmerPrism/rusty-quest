package io.github.mesmerprism.rustyquest.peer_rendezvous;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

final class BleRendezvousGattClient implements Runnable {
    interface Completion {
        void complete(String status);
    }

    private static final long OPERATION_TIMEOUT_MS = 12_000;

    private final Context context;
    private final BleRendezvousConfig config;
    private final BleRendezvousEvidence evidence;
    private final Completion completion;
    private final AtomicReference<BluetoothDevice> foundDevice = new AtomicReference<>();
    private final Set<String> peerNonces = new HashSet<>();

    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic offerCharacteristic;
    private BluetoothGattCharacteristic controlCharacteristic;
    private BluetoothGattCharacteristic statusCharacteristic;
    private CountDownLatch scanLatch;
    private CountDownLatch serviceLatch;
    private CountDownLatch readLatch;
    private CountDownLatch writeLatch;
    private CountDownLatch disconnectLatch;
    private volatile byte[] lastReadValue;
    private volatile int lastReadStatus = Integer.MIN_VALUE;
    private volatile int lastWriteStatus = Integer.MIN_VALUE;
    private volatile boolean readingOffer;
    private String remotePeerTag;

    BleRendezvousGattClient(
            Context context,
            BleRendezvousConfig config,
            BleRendezvousEvidence evidence,
            Completion completion) {
        this.context = context;
        this.config = config;
        this.evidence = evidence;
        this.completion = completion;
    }

    void start() {
        Thread thread = new Thread(this, "rusty-ble-rendezvous-client");
        thread.start();
    }

    @Override
    public void run() {
        String status = "fail";
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            evidence.adapterAvailable = manager != null && manager.getAdapter() != null;
            evidence.bluetoothEnabled = evidence.adapterAvailable && manager.getAdapter().isEnabled();
            evidence.permissionsGranted = BleRendezvousPermissions.granted(context, config.mode);
            evidence.protocolSelfTestPassed = BleRendezvousProtocol.selfTest(config);
            if (!evidence.adapterAvailable
                    || !evidence.bluetoothEnabled
                    || !evidence.permissionsGranted
                    || !evidence.protocolSelfTestPassed) {
                evidence.issue("client_preflight_blocked");
                status = "blocked";
                return;
            }
            scanner = manager.getAdapter().getBluetoothLeScanner();
            if (scanner == null || !scanForPeer()) {
                evidence.issue("peer_scan_failed");
                return;
            }
            BluetoothDevice device = foundDevice.get();
            if (device == null || !connectAndDiscover(device)) {
                evidence.issue("peer_connect_or_discovery_failed");
                return;
            }
            if (evidence.negotiatedMtu < BleRendezvousProtocol.MAX_WIRE_BYTES + 3) {
                evidence.issue("negotiated_mtu_too_small");
                return;
            }

            if (!exchange(false)) {
                return;
            }
            if (!disconnectForReconnect()) {
                evidence.issue("peer_disconnect_for_reconnect_failed");
                return;
            }
            Thread.sleep(750);
            if (!connectAndDiscover(device)) {
                evidence.issue("peer_reconnect_or_discovery_failed");
                return;
            }
            if (evidence.negotiatedMtu < BleRendezvousProtocol.MAX_WIRE_BYTES + 3) {
                evidence.issue("reconnect_negotiated_mtu_too_small");
                return;
            }
            if (!exchange(true)) {
                return;
            }
            evidence.reconnectsCompleted = 1;
            evidence.postReconnectMessageAuthenticated = true;
            status = "pass";
        } catch (Exception error) {
            evidence.issue("client_execution_failed");
        } finally {
            cleanup();
            completion.complete(status);
        }
    }

    private boolean exchange(boolean postReconnect) throws Exception {
        byte[] offerBytes = read(offerCharacteristic, true);
        JSONObject offer = verifyPeerMessage(offerBytes, "offer", 1);
        if (offer == null) {
            return false;
        }
        byte[] proposal = BleRendezvousProtocol.buildMessage(config, "proposal", 2);
        if (!write(proposal)) {
            evidence.issue(postReconnect
                    ? "post_reconnect_proposal_write_failed"
                    : "proposal_write_failed");
            return false;
        }
        byte[] acceptBytes = read(statusCharacteristic, false);
        JSONObject accept = verifyPeerMessage(acceptBytes, "accept", 3);
        if (accept == null) {
            return false;
        }
        if (postReconnect) {
            evidence.postReconnectMessageAuthenticated = true;
        }
        return true;
    }

    private boolean scanForPeer() throws InterruptedException {
        scanLatch = new CountDownLatch(1);
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (result != null && result.getDevice() != null) {
                    foundDevice.compareAndSet(null, result.getDevice());
                    scanLatch.countDown();
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                evidence.issue("ble_scan_failed_" + errorCode);
                scanLatch.countDown();
            }
        };
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(BleRendezvousProtocol.SERVICE_UUID))
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        evidence.scanStarted = true;
        boolean signaled = scanLatch.await(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        stopScan();
        return signaled && foundDevice.get() != null;
    }

    private boolean connectAndDiscover(BluetoothDevice device) throws InterruptedException {
        offerCharacteristic = null;
        controlCharacteristic = null;
        statusCharacteristic = null;
        evidence.negotiatedMtu = 23;
        serviceLatch = new CountDownLatch(1);
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) {
            return false;
        }
        evidence.gattOpened = true;
        return serviceLatch.await(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                && offerCharacteristic != null
                && controlCharacteristic != null
                && statusCharacteristic != null;
    }

    private byte[] read(BluetoothGattCharacteristic characteristic, boolean offer)
            throws InterruptedException {
        readLatch = new CountDownLatch(1);
        lastReadValue = null;
        lastReadStatus = Integer.MIN_VALUE;
        readingOffer = offer;
        if (!gatt.readCharacteristic(characteristic)
                || !readLatch.await(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                || lastReadStatus != BluetoothGatt.GATT_SUCCESS) {
            evidence.issue(offer ? "offer_read_failed" : "accept_read_failed");
            return null;
        }
        return lastReadValue;
    }

    private boolean write(byte[] payload) throws InterruptedException {
        writeLatch = new CountDownLatch(1);
        lastWriteStatus = Integer.MIN_VALUE;
        controlCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        controlCharacteristic.setValue(payload);
        if (!gatt.writeCharacteristic(controlCharacteristic)
                || !writeLatch.await(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                || lastWriteStatus != BluetoothGatt.GATT_SUCCESS) {
            return false;
        }
        evidence.messagesSent += 1;
        return true;
    }

    private JSONObject verifyPeerMessage(byte[] bytes, String expectedKind, int expectedSequence) {
        evidence.messagesReceived += 1;
        try {
            JSONObject message = BleRendezvousProtocol.verify(
                    bytes,
                    config.sharedSecret,
                    config.sessionTag);
            String peerTag = message.optString("pid");
            String nonce = message.optString("n");
            if (!expectedKind.equals(message.optString("k"))
                    || message.optInt("e", 0) != config.epoch
                    || message.optInt("q", 0) != expectedSequence
                    || config.peerTag.equals(peerTag)
                    || (remotePeerTag != null && !remotePeerTag.equals(peerTag))) {
                throw new IllegalArgumentException("peer_message_identity_invalid");
            }
            if (!peerNonces.add(nonce)) {
                throw new IllegalArgumentException("peer_message_replay_detected");
            }
            remotePeerTag = peerTag;
            evidence.authenticatedMessages += 1;
            return message;
        } catch (SecurityException error) {
            evidence.authenticationFailures += 1;
            evidence.issue("peer_message_authentication_failed");
            return null;
        } catch (Exception error) {
            String issue = error.getMessage();
            evidence.issue(BleRendezvousProtocol.isSafeTag(issue, 1, 96)
                    ? issue
                    : "peer_message_validation_failed");
            return null;
        }
    }

    private boolean disconnectForReconnect() throws InterruptedException {
        BluetoothGatt current = gatt;
        if (current == null) {
            return false;
        }
        disconnectLatch = new CountDownLatch(1);
        try {
            current.disconnect();
        } catch (RuntimeException error) {
            return false;
        }
        boolean disconnected = disconnectLatch.await(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try {
            current.close();
        } catch (RuntimeException ignored) {
        }
        if (gatt == current) {
            gatt = null;
        }
        offerCharacteristic = null;
        controlCharacteristic = null;
        statusCharacteristic = null;
        evidence.gattClosed = true;
        return disconnected;
    }

    private void stopScan() {
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (RuntimeException ignored) {
            }
            evidence.scanStopped = true;
        }
    }

    private void cleanup() {
        stopScan();
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (RuntimeException ignored) {
            }
            try {
                gatt.close();
            } catch (RuntimeException ignored) {
            }
            evidence.gattClosed = true;
        }
        if (evidence.connected) {
            evidence.disconnected = true;
        }
    }

    private void onReadResult(byte[] value, int status) {
        lastReadStatus = status;
        lastReadValue = value;
        CountDownLatch current = readLatch;
        if (current != null) {
            current.countDown();
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt callbackGatt, int status, int newState) {
            if (callbackGatt != gatt) {
                return;
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                evidence.connected = true;
                evidence.negotiatedMtu = 23;
                if (!callbackGatt.requestMtu(BleRendezvousProtocol.REQUESTED_MTU)) {
                    callbackGatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                evidence.disconnected = true;
                CountDownLatch disconnect = disconnectLatch;
                if (disconnect != null) {
                    disconnect.countDown();
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    CountDownLatch current = serviceLatch;
                    if (current != null) {
                        current.countDown();
                    }
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt callbackGatt, int mtu, int status) {
            evidence.negotiatedMtu = status == BluetoothGatt.GATT_SUCCESS ? mtu : 23;
            callbackGatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = callbackGatt.getService(BleRendezvousProtocol.SERVICE_UUID);
                if (service != null) {
                    offerCharacteristic = service.getCharacteristic(BleRendezvousProtocol.OFFER_UUID);
                    controlCharacteristic = service.getCharacteristic(BleRendezvousProtocol.CONTROL_UUID);
                    statusCharacteristic = service.getCharacteristic(BleRendezvousProtocol.STATUS_UUID);
                }
            }
            CountDownLatch current = serviceLatch;
            if (current != null) {
                current.countDown();
            }
        }

        @Override
        public void onCharacteristicRead(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                int status) {
            onReadResult(characteristic == null ? null : characteristic.getValue(), status);
        }

        @Override
        public void onCharacteristicRead(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value,
                int status) {
            onReadResult(value, status);
        }

        @Override
        public void onCharacteristicWrite(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                int status) {
            lastWriteStatus = status;
            CountDownLatch current = writeLatch;
            if (current != null) {
                current.countDown();
            }
        }
    };
}
