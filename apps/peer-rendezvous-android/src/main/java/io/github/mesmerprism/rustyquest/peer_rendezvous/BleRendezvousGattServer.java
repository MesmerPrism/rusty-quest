package io.github.mesmerprism.rustyquest.peer_rendezvous;

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
import android.os.ParcelUuid;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

final class BleRendezvousGattServer {
    private final Context context;
    private final BleRendezvousConfig config;
    private final BleRendezvousEvidence evidence;
    private final List<BluetoothDevice> connectedDevices = new ArrayList<>();
    private final Set<String> acceptedProposalNonces = new HashSet<>();

    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advertiseCallback;
    private BluetoothGattCharacteristic offerCharacteristic;
    private BluetoothGattCharacteristic statusCharacteristic;
    private byte[] offerMessage;
    private byte[] statusMessage;
    private int authenticatedProposalCount;

    BleRendezvousGattServer(
            Context context,
            BleRendezvousConfig config,
            BleRendezvousEvidence evidence) {
        this.context = context;
        this.config = config;
        this.evidence = evidence;
    }

    boolean start() {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        evidence.adapterAvailable = manager != null && manager.getAdapter() != null;
        evidence.bluetoothEnabled = evidence.adapterAvailable && manager.getAdapter().isEnabled();
        evidence.permissionsGranted = BleRendezvousPermissions.granted(context, config.mode);
        evidence.protocolSelfTestPassed = BleRendezvousProtocol.selfTest(config);
        if (!evidence.adapterAvailable
                || !evidence.bluetoothEnabled
                || !evidence.permissionsGranted
                || !evidence.protocolSelfTestPassed) {
            evidence.issue("server_preflight_blocked");
            return false;
        }
        try {
            offerMessage = BleRendezvousProtocol.buildMessage(config, "offer", 1);
            statusMessage = BleRendezvousProtocol.buildMessage(config, "status", 1);
            gattServer = manager.openGattServer(context, callback);
            evidence.gattOpened = gattServer != null;
            if (gattServer == null) {
                evidence.issue("gatt_server_open_failed");
                return false;
            }

            BluetoothGattService service = new BluetoothGattService(
                    BleRendezvousProtocol.SERVICE_UUID,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY);
            offerCharacteristic = new BluetoothGattCharacteristic(
                    BleRendezvousProtocol.OFFER_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            BluetoothGattCharacteristic control = new BluetoothGattCharacteristic(
                    BleRendezvousProtocol.CONTROL_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);
            statusCharacteristic = new BluetoothGattCharacteristic(
                    BleRendezvousProtocol.STATUS_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            offerCharacteristic.setValue(offerMessage);
            statusCharacteristic.setValue(statusMessage);
            service.addCharacteristic(offerCharacteristic);
            service.addCharacteristic(control);
            service.addCharacteristic(statusCharacteristic);
            if (!gattServer.addService(service)) {
                evidence.issue("gatt_service_add_start_failed");
                return false;
            }
            return true;
        } catch (Exception error) {
            evidence.issue("gatt_server_start_failed");
            return false;
        }
    }

    void stop() {
        if (advertiser != null && advertiseCallback != null) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (RuntimeException ignored) {
            }
            evidence.advertisingStopped = true;
        }
        if (gattServer != null) {
            for (BluetoothDevice device : new ArrayList<>(connectedDevices)) {
                try {
                    gattServer.cancelConnection(device);
                } catch (RuntimeException ignored) {
                }
            }
            try {
                gattServer.clearServices();
            } catch (RuntimeException ignored) {
            }
            try {
                gattServer.close();
            } catch (RuntimeException ignored) {
            }
            evidence.gattClosed = true;
        }
        if (evidence.connected) {
            evidence.disconnected = true;
        }
        connectedDevices.clear();
    }

    private void startAdvertising(BluetoothManager manager) {
        advertiser = manager.getAdapter().getBluetoothLeAdvertiser();
        if (advertiser == null) {
            evidence.issue("ble_advertiser_unavailable");
            return;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(BleRendezvousProtocol.SERVICE_UUID))
                .build();
        advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                evidence.advertisingStarted = true;
            }

            @Override
            public void onStartFailure(int errorCode) {
                evidence.issue("ble_advertising_start_failed_" + errorCode);
            }
        };
        advertiser.startAdvertising(settings, data, advertiseCallback);
    }

    private void sendReadResponse(
            BluetoothDevice device,
            int requestId,
            int offset,
            byte[] message) {
        if (gattServer == null || message == null || offset < 0 || offset > message.length) {
            if (gattServer != null) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            }
            return;
        }
        byte[] response = Arrays.copyOfRange(message, offset, message.length);
        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response);
        if (offset == 0) {
            evidence.messagesSent += 1;
        }
    }

    private boolean refreshOfferMessage() {
        try {
            offerMessage = BleRendezvousProtocol.buildMessage(config, "offer", 1);
            if (offerCharacteristic != null) {
                offerCharacteristic.setValue(offerMessage);
            }
            return true;
        } catch (Exception error) {
            evidence.issue("offer_refresh_failed");
            return false;
        }
    }

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                evidence.issue("gatt_service_add_failed_" + status);
                return;
            }
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            if (manager == null || manager.getAdapter() == null) {
                evidence.issue("bluetooth_manager_lost_before_advertise");
                return;
            }
            startAdvertising(manager);
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                evidence.connected = true;
                if (!connectedDevices.contains(device)) {
                    connectedDevices.add(device);
                }
                refreshOfferMessage();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                evidence.disconnected = true;
                connectedDevices.remove(device);
            }
        }

        @Override
        public void onCharacteristicReadRequest(
                BluetoothDevice device,
                int requestId,
                int offset,
                BluetoothGattCharacteristic characteristic) {
            if (BleRendezvousProtocol.OFFER_UUID.equals(characteristic.getUuid())) {
                if (offset == 0 && !refreshOfferMessage()) {
                    if (gattServer != null) {
                        gattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_FAILURE,
                                offset,
                                null);
                    }
                    return;
                }
                sendReadResponse(device, requestId, offset, offerMessage);
            } else if (BleRendezvousProtocol.STATUS_UUID.equals(characteristic.getUuid())) {
                sendReadResponse(device, requestId, offset, statusMessage);
            } else if (gattServer != null) {
                gattServer.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset,
                        null);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(
                BluetoothDevice device,
                int requestId,
                BluetoothGattCharacteristic characteristic,
                boolean preparedWrite,
                boolean responseNeeded,
                int offset,
                byte[] value) {
            int responseStatus = BluetoothGatt.GATT_SUCCESS;
            if (!BleRendezvousProtocol.CONTROL_UUID.equals(characteristic.getUuid())
                    || preparedWrite
                    || offset != 0) {
                responseStatus = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
            } else {
                evidence.messagesReceived += 1;
                try {
                    JSONObject proposal = BleRendezvousProtocol.verify(
                            value,
                            config.sharedSecret,
                            config.sessionTag);
                    String proposalNonce = proposal.optString("n");
                    if (!"proposal".equals(proposal.optString("k"))
                            || proposal.optInt("e", 0) != config.epoch
                            || proposal.optInt("q", 0) != 2
                            || config.peerTag.equals(proposal.optString("pid"))) {
                        throw new IllegalArgumentException("proposal_identity_invalid");
                    }
                    if (!acceptedProposalNonces.add(proposalNonce)) {
                        throw new IllegalArgumentException("proposal_replay_detected");
                    }
                    evidence.authenticatedMessages += 1;
                    authenticatedProposalCount += 1;
                    if (authenticatedProposalCount > 1) {
                        evidence.reconnectsCompleted = 1;
                        evidence.postReconnectMessageAuthenticated = true;
                    }
                    statusMessage = BleRendezvousProtocol.buildMessage(
                            config,
                            "accept",
                            3);
                    statusCharacteristic.setValue(statusMessage);
                } catch (SecurityException error) {
                    evidence.authenticationFailures += 1;
                    evidence.issue("proposal_authentication_failed");
                    responseStatus = BluetoothGatt.GATT_FAILURE;
                } catch (Exception error) {
                    String issue = error.getMessage();
                    evidence.issue(BleRendezvousProtocol.isSafeTag(issue, 1, 96)
                            ? issue
                            : "proposal_validation_failed");
                    responseStatus = BluetoothGatt.GATT_FAILURE;
                }
            }
            if (responseNeeded && gattServer != null) {
                gattServer.sendResponse(device, requestId, responseStatus, offset, null);
            }
        }
    };
}
