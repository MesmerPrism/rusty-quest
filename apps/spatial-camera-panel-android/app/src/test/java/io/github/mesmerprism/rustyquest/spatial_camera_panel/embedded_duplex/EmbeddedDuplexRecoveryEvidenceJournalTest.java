package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.assertThrows;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class EmbeddedDuplexRecoveryEvidenceJournalTest {
    private static final String[] FIELDS = {
            "broker_adapter", "broker_runtime", "peer_runtime", "media_product",
            "owner_progress", "owner_dispatch_replay", "product_activation_replay",
            "request_receipt_ledger"
    };

    private static String sha256(String value) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))) {
            text.append(Character.forDigit((item >>> 4) & 15, 16));
            text.append(Character.forDigit(item & 15, 16));
        }
        return text.toString();
    }

    private static JSONObject evidence() throws Exception {
        JSONObject snapshots = new JSONObject();
        for (String name : FIELDS) {
            String exact = "{\"source\":\"" + name + "\"}";
            snapshots.put(name, new JSONObject().put("json", exact).put("sha256", sha256(exact)));
        }
        return new JSONObject()
                .put("$schema", EmbeddedDuplexRecoveryEvidenceJournal.SCHEMA)
                .put("revision", 3)
                .put("provider_epoch_id", "epoch.original")
                .put("lineage_sha256", "a".repeat(64))
                .put("snapshots", snapshots);
    }

    private static String checkpoint(String lineage, long revision, String phase) {
        return "{\"$schema\":\"" + EmbeddedDuplexStartJournal.SCHEMA + "\","
                + "\"phase\":\"" + phase + "\",\"revision\":" + revision + ","
                + "\"lineage_sha256\":\"" + lineage + "\","
                + "\"last_verified_receipt_sha256\":null}";
    }

    @Test public void exactSnapshotsValidateAndTamperingFailsClosed() throws Exception {
        JSONObject accepted = evidence();
        EmbeddedDuplexRecoveryEvidenceJournal.validate(accepted.toString());
        EmbeddedDuplexRecoveryEvidenceJournal.validateAgainstCheckpoint(
                checkpoint("a".repeat(64), 3, "owner_attempted"), accepted.toString(), "epoch.original");
        assertThrows(Exception.class, () -> EmbeddedDuplexRecoveryEvidenceJournal
                .validateAgainstCheckpoint(checkpoint("b".repeat(64), 3, "owner_attempted"),
                        accepted.toString(), "epoch.original"));
        assertThrows(Exception.class, () -> EmbeddedDuplexRecoveryEvidenceJournal
                .validateAgainstCheckpoint(checkpoint("a".repeat(64), 4, "owner_attempted"),
                        accepted.toString(), "epoch.original"));
        assertThrows(Exception.class, () -> EmbeddedDuplexRecoveryEvidenceJournal
                .validateAgainstCheckpoint(checkpoint("a".repeat(64), 2, "owner_attempted"),
                        accepted.toString(), "epoch.original"));
        assertThrows(Exception.class, () -> EmbeddedDuplexRecoveryEvidenceJournal
                .validateAgainstCheckpoint(checkpoint("a".repeat(64), 3, "owner_attempted"),
                        accepted.toString(), "epoch.other"));
        JSONObject changed = evidence();
        changed.getJSONObject("snapshots").getJSONObject("media_product")
                .put("json", "{\"source\":\"other\"}");
        assertThrows(Exception.class,
                () -> EmbeddedDuplexRecoveryEvidenceJournal.validate(changed.toString()));
        JSONObject absent = evidence();
        absent.getJSONObject("snapshots").remove("owner_progress");
        assertThrows(Exception.class,
                () -> EmbeddedDuplexRecoveryEvidenceJournal.validate(absent.toString()));
        JSONObject extra = evidence();
        extra.put("caller_ticket", "not authority");
        assertThrows(Exception.class,
                () -> EmbeddedDuplexRecoveryEvidenceJournal.validate(extra.toString()));
        assertThrows(Exception.class,
                () -> EmbeddedDuplexRecoveryEvidenceJournal.decodeExact(new byte[] {(byte) 0xc3, 0x28}));
    }
}
