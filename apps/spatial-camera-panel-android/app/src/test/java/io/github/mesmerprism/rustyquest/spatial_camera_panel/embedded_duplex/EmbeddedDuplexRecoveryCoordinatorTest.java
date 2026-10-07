package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;
import org.junit.Test;

public final class EmbeddedDuplexRecoveryCoordinatorTest {
    private static final String EPOCH = "epoch.original";
    private static final String LINEAGE = "a".repeat(64);
    private static final String[] SNAPSHOTS = {"broker_adapter", "broker_runtime", "peer_runtime",
            "media_product", "owner_progress", "owner_dispatch_replay",
            "product_activation_replay", "request_receipt_ledger"};

    private static final class MemoryStore implements EmbeddedDuplexRecoveryCoordinator.Store {
        String checkpoint, evidence;
        boolean failCheckpointWrite;
        public String readCheckpoint() { return checkpoint; }
        public String readEvidence() { return evidence; }
        public void persistEvidence(String value) { evidence = value; }
        public void persistCheckpoint(String value) {
            if (failCheckpointWrite) throw new IllegalStateException("simulated crash");
            checkpoint = value;
        }
    }

    private static String digest(String value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))) {
            result.append(Character.forDigit((b >>> 4) & 15, 16));
            result.append(Character.forDigit(b & 15, 16));
        }
        return result.toString();
    }

    private static String checkpoint(int revision) {
        return "{\"$schema\":\"" + EmbeddedDuplexStartJournal.SCHEMA + "\","
                + "\"phase\":\"owner_attempted\",\"revision\":" + revision + ","
                + "\"lineage_sha256\":\"" + LINEAGE + "\","
                + "\"last_verified_receipt_sha256\":null}";
    }

    private static String evidence(int revision, String epoch) throws Exception {
        JSONObject snapshots = new JSONObject();
        for (String field : SNAPSHOTS) {
            String exact = "{\"source\":\"" + field + "\"}";
            snapshots.put(field, new JSONObject().put("json", exact).put("sha256", digest(exact)));
        }
        return new JSONObject().put("$schema", EmbeddedDuplexRecoveryEvidenceJournal.SCHEMA)
                .put("revision", revision).put("provider_epoch_id", epoch)
                .put("lineage_sha256", LINEAGE).put("snapshots", snapshots).toString();
    }

    @Test public void crashSkewRemainsBlockedAcrossCoordinatorRestart() throws Exception {
        MemoryStore store = new MemoryStore();
        EmbeddedDuplexRecoveryCoordinator first = new EmbeddedDuplexRecoveryCoordinator(store);
        assertTrue(first.freshBootstrapAllowed());
        store.failCheckpointWrite = true;
        assertThrows(Exception.class, () -> first.persistWriteAhead(
                checkpoint(1), evidence(1, EPOCH), EPOCH));
        assertFalse(new EmbeddedDuplexRecoveryCoordinator(store).freshBootstrapAllowed());
        assertThrows(IllegalStateException.class,
                () -> new EmbeddedDuplexRecoveryCoordinator(store).requireFreshBootstrap());
        assertThrows(Exception.class, () -> first.persistWriteAhead(
                checkpoint(1), evidence(1, EPOCH), EPOCH));
    }

    @Test public void retainedPairRejectsReplayAndCrossEpochAdvance() throws Exception {
        MemoryStore store = new MemoryStore();
        EmbeddedDuplexRecoveryCoordinator coordinator = new EmbeddedDuplexRecoveryCoordinator(store);
        coordinator.persistWriteAhead(checkpoint(1), evidence(1, EPOCH), EPOCH);
        assertFalse(new EmbeddedDuplexRecoveryCoordinator(store).freshBootstrapAllowed());
        assertThrows(Exception.class, () -> coordinator.persistWriteAhead(
                checkpoint(1), evidence(1, EPOCH), EPOCH));
        assertThrows(Exception.class, () -> coordinator.persistWriteAhead(
                checkpoint(2), evidence(2, "epoch.other"), "epoch.other"));
        coordinator.persistWriteAhead(checkpoint(2), evidence(2, EPOCH), EPOCH);
        assertFalse(new EmbeddedDuplexRecoveryCoordinator(store).freshBootstrapAllowed());
        store.evidence = evidence(3, EPOCH);
        assertFalse(new EmbeddedDuplexRecoveryCoordinator(store).freshBootstrapAllowed());
        store.evidence = null; // A marker-ahead crash is equally unresolved.
        assertFalse(new EmbeddedDuplexRecoveryCoordinator(store).freshBootstrapAllowed());
    }
}
