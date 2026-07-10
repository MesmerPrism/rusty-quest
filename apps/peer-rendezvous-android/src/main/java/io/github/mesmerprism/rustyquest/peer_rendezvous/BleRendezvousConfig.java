package io.github.mesmerprism.rustyquest.peer_rendezvous;

import android.content.Intent;
import java.util.Locale;

final class BleRendezvousConfig {
    static final String MODE_SERVER = "server";
    static final String MODE_CLIENT = "client";

    final String mode;
    final String runId;
    final String sessionTag;
    final String peerTag;
    final String sharedSecret;
    final int durationMs;
    final int epoch;
    final int ttlMs;
    final String rolePreference;
    final int capabilities;
    final String wifiState;
    final String p2pIpv4;
    final int brokerPort;

    private BleRendezvousConfig(
            String mode,
            String runId,
            String sessionTag,
            String peerTag,
            String sharedSecret,
            int durationMs,
            int epoch,
            int ttlMs,
            String rolePreference,
            int capabilities,
            String wifiState,
            String p2pIpv4,
            int brokerPort) {
        this.mode = mode;
        this.runId = runId;
        this.sessionTag = sessionTag;
        this.peerTag = peerTag;
        this.sharedSecret = sharedSecret;
        this.durationMs = durationMs;
        this.epoch = epoch;
        this.ttlMs = ttlMs;
        this.rolePreference = rolePreference;
        this.capabilities = capabilities;
        this.wifiState = wifiState;
        this.p2pIpv4 = p2pIpv4;
        this.brokerPort = brokerPort;
    }

    static BleRendezvousConfig fromIntent(Intent intent) {
        if (intent == null
                || !PeerRendezvousService.ACTION_START.equals(intent.getAction())
                || !intent.getBooleanExtra("enabled", false)) {
            throw new IllegalArgumentException("explicit_opt_in_required");
        }
        String mode = text(intent, "mode", MODE_SERVER).toLowerCase(Locale.US);
        if (!MODE_SERVER.equals(mode) && !MODE_CLIENT.equals(mode)) {
            throw new IllegalArgumentException("unsupported_mode");
        }
        String runId = safeTag(intent, "run_id");
        String sessionTag = safeTag(intent, "session_tag");
        String peerTag = safeTag(intent, "peer_tag");
        String secret = text(intent, "shared_secret", "");
        if (secret.length() < 16 || secret.length() > 128) {
            throw new IllegalArgumentException("shared_secret_length_invalid");
        }
        int durationMs = bounded(intent.getIntExtra("duration_ms", 10_000), 3_000, 120_000,
                "duration_ms_invalid");
        int epoch = bounded(intent.getIntExtra("epoch", 1), 1, Integer.MAX_VALUE,
                "epoch_invalid");
        int ttlMs = bounded(intent.getIntExtra("ttl_ms", 30_000), 1_000, 120_000,
                "ttl_ms_invalid");
        String role = text(intent, "role_preference", "either").toLowerCase(Locale.US);
        if (!"group_owner".equals(role) && !"client".equals(role) && !"either".equals(role)) {
            throw new IllegalArgumentException("role_preference_invalid");
        }
        int capabilities = intent.getIntExtra("capabilities", 15);
        if ((capabilities & BleRendezvousProtocol.CAPABILITY_GATT) == 0
                || (capabilities & ~BleRendezvousProtocol.KNOWN_CAPABILITIES) != 0
                || ((capabilities & BleRendezvousProtocol.CAPABILITY_DIRECT_P2P) != 0
                    && (capabilities & BleRendezvousProtocol.CAPABILITY_WIFI_DIRECT) == 0)) {
            throw new IllegalArgumentException("capabilities_invalid");
        }
        String wifiState = text(intent, "wifi_state", "idle").toLowerCase(Locale.US);
        if (!"idle".equals(wifiState)
                && !"discovering".equals(wifiState)
                && !"grouped".equals(wifiState)
                && !"ready".equals(wifiState)
                && !"failed".equals(wifiState)) {
            throw new IllegalArgumentException("wifi_state_invalid");
        }
        String p2pIpv4 = text(intent, "p2p_ipv4", "");
        int brokerPort = intent.getIntExtra("broker_port", 0);
        if (("idle".equals(wifiState) || "discovering".equals(wifiState) || "failed".equals(wifiState))
                && (!p2pIpv4.isEmpty() || brokerPort != 0)) {
            throw new IllegalArgumentException("non_grouped_endpoint_hint_forbidden");
        }
        if ("grouped".equals(wifiState)
                && (!BleRendezvousProtocol.isSupportedP2pIpv4(p2pIpv4) || brokerPort != 0)) {
            throw new IllegalArgumentException("grouped_endpoint_hint_invalid");
        }
        if ("ready".equals(wifiState)
                && (!BleRendezvousProtocol.isSupportedP2pIpv4(p2pIpv4)
                    || brokerPort <= 0
                    || brokerPort > 65535)) {
            throw new IllegalArgumentException("ready_endpoint_hint_invalid");
        }
        return new BleRendezvousConfig(
                mode,
                runId,
                sessionTag,
                peerTag,
                secret,
                durationMs,
                epoch,
                ttlMs,
                role,
                capabilities,
                wifiState,
                p2pIpv4,
                brokerPort);
    }

    private static String safeTag(Intent intent, String name) {
        String value = text(intent, name, "");
        if (!BleRendezvousProtocol.isSafeTag(value, 4, 32)) {
            throw new IllegalArgumentException(name + "_invalid");
        }
        return value;
    }

    private static String text(Intent intent, String name, String fallback) {
        String value = intent.getStringExtra(name);
        return value == null ? fallback : value.trim();
    }

    private static int bounded(int value, int min, int max, String issue) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(issue);
        }
        return value;
    }
}
