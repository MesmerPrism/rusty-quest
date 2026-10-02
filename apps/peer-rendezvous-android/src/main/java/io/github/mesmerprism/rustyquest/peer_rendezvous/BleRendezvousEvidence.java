package io.github.mesmerprism.rustyquest.peer_rendezvous;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class BleRendezvousEvidence {
    private static final String TAG = "RustyBleRendezvous";
    private static final String SCHEMA = "rusty.quest.ble_rendezvous_sidecar_receipt.v1";

    final BleRendezvousConfig config;
    final List<String> issueCodes = new ArrayList<>();

    boolean adapterAvailable;
    boolean bluetoothEnabled;
    boolean permissionsGranted;
    boolean protocolSelfTestPassed;
    boolean advertisingStarted;
    boolean advertisingStopped;
    boolean scanStarted;
    boolean scanStopped;
    boolean gattOpened;
    boolean gattClosed;
    boolean connected;
    boolean disconnected;
    int messagesSent;
    int messagesReceived;
    int authenticatedMessages;
    int authenticationFailures;
    int reconnectsCompleted;
    boolean postReconnectMessageAuthenticated;
    boolean cleanupComplete;
    boolean coordinationFailed;
    int negotiatedMtu;

    BleRendezvousEvidence(BleRendezvousConfig config) {
        this.config = config;
    }

    synchronized void issue(String issueCode) {
        if(config.observedCoordination)coordinationFailed=true;
        if (BleRendezvousProtocol.isSafeTag(issueCode, 1, 96)) {
            issueCodes.add(issueCode);
        } else {
            issueCodes.add("invalid_issue_code_redacted");
        }
    }

    synchronized void write(Context context, String status) {
        try {
            if(config.observedCoordination&&coordinationFailed&&"pass".equals(status))status="fail";
            JSONObject receipt = new JSONObject();
            receipt.put("schema", config.observedCoordination?"rusty.quest.ble_role_readiness_receipt.v2":SCHEMA);
            if(config.observedCoordination) {
                receipt.put("broker_ready",false);
                receipt.put("broker_ready_status","unavailable_no_owner_proof");
                receipt.put("configured_role",config.rolePreference);
                receipt.put("coordination_epoch",config.coordinationEpoch);
                receipt.put("expected_peer_tag",config.expectedPeerTag);
                receipt.put("authenticated_observed_peer",config.authenticatedObservedPeer);
                BleRoleReadiness.Observation o=config.observation;
                receipt.put("local_wifi_observation_available",o!=null);
                if(o!=null){receipt.put("boot_tag",o.boot);receipt.put("group_tag",o.group);
                    receipt.put("observed_role",o.role);receipt.put("observed_owner_ipv4",o.ownerIp);
                    receipt.put("observed_local_ipv4",o.localIp);receipt.put("observed_elapsed_ms",o.observedAt);}
                receipt.put("observation_max_age_ms",BleRoleReadiness.MAX_AGE_MS);
                receipt.put("wifi_ready_claimed",false);
            }
            receipt.put("run_id", config.runId);
            receipt.put("session_tag", config.sessionTag);
            receipt.put("peer_tag", config.peerTag);
            receipt.put("role", config.mode);
            receipt.put("status", status);
            receipt.put("explicit_opt_in", true);
            receipt.put("adapter_available", adapterAvailable);
            receipt.put("bluetooth_enabled", bluetoothEnabled);
            receipt.put("permissions_granted", permissionsGranted);
            receipt.put("protocol_self_test_passed", protocolSelfTestPassed);
            receipt.put("advertising_started", advertisingStarted);
            receipt.put("advertising_stopped", advertisingStopped);
            receipt.put("scan_started", scanStarted);
            receipt.put("scan_stopped", scanStopped);
            receipt.put("gatt_opened", gattOpened);
            receipt.put("gatt_closed", gattClosed);
            receipt.put("connected", connected);
            receipt.put("disconnected", disconnected);
            receipt.put("messages_sent", messagesSent);
            receipt.put("messages_received", messagesReceived);
            receipt.put("authenticated_messages", authenticatedMessages);
            receipt.put("authentication_failures", authenticationFailures);
            receipt.put("reconnects_completed", reconnectsCompleted);
            receipt.put("post_reconnect_message_authenticated", postReconnectMessageAuthenticated);
            receipt.put("raw_bluetooth_addresses_redacted", true);
            receipt.put("media_payload_bytes", 0);
            receipt.put("wifi_direct_mutations_executed", 0);
            receipt.put("manifold_commands_executed", 0);
            receipt.put("cleanup_complete", cleanupComplete);
            receipt.put("issue_codes", new JSONArray(issueCodes));
            receipt.put("wire_message_max_bytes", BleRendezvousProtocol.MAX_WIRE_BYTES);
            receipt.put("negotiated_mtu", negotiatedMtu);
            receipt.put("peer_evidence_claimed", "pass".equals(status));

            File output = new File(context.getFilesDir(), "ble-rendezvous-receipt.json");
            File temp = new File(context.getFilesDir(), "ble-rendezvous-receipt.json.tmp");
            try (FileOutputStream stream = new FileOutputStream(temp, false)) {
                stream.write(receipt.toString(2).getBytes(StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
            if (output.exists() && !output.delete()) {
                throw new IllegalStateException("receipt_replace_failed");
            }
            if (!temp.renameTo(output)) {
                throw new IllegalStateException("receipt_rename_failed");
            }
            Log.i(
                    TAG,
                    "RUSTY_QUEST_BLE_RENDEZVOUS"
                            + " status=" + status
                            + " role=" + config.mode
                            + " protocolSelfTest=" + protocolSelfTestPassed
                            + " connected=" + connected
                            + " authenticatedMessages=" + authenticatedMessages
                            + " cleanupComplete=" + cleanupComplete
                            + " rawBluetoothAddressesRedacted=true"
                            + " mediaPayloadBytes=0"
                            + " wifiDirectMutationsExecuted=0"
                            + " manifoldCommandsExecuted=0");
        } catch (Exception error) {
            Log.e(TAG, "RUSTY_QUEST_BLE_RENDEZVOUS_RECEIPT_WRITE_FAILED", error);
        }
    }
}
