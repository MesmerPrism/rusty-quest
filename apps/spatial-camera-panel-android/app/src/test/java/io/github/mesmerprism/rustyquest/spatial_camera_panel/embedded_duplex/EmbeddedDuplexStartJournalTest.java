package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class EmbeddedDuplexStartJournalTest {
    private static String checkpoint(String phase, String revision, String receipt) {
        return "{\"$schema\":\"" + EmbeddedDuplexStartJournal.SCHEMA + "\","
                + "\"phase\":\"" + phase + "\",\"revision\":" + revision + ","
                + "\"lineage_sha256\":\"" + "a".repeat(64) + "\","
                + "\"last_verified_receipt_sha256\":" + receipt + "}";
    }

    @Test public void ambiguousOwnerAttemptRemainsUnresolvedAfterReload() throws Exception {
        assertEquals("owner_attempted", EmbeddedDuplexStartJournal.phase(
                checkpoint("owner_attempted", "9", "\"" + "b".repeat(64) + "\"")));
        assertEquals("terminal", EmbeddedDuplexStartJournal.phase(
                checkpoint("terminal", "12", "\"" + "c".repeat(64) + "\"")));
    }

    @Test public void malformedOrUnrecognizedCheckpointCannotBypassRecoveryBarrier() {
        assertThrows(Exception.class, () -> EmbeddedDuplexStartJournal.phase(
                checkpoint("owner_attempted", "\"9\"", "null")));
        assertThrows(Exception.class, () -> EmbeddedDuplexStartJournal.phase(
                checkpoint("terminal", "0", "null")));
        assertThrows(Exception.class, () -> EmbeddedDuplexStartJournal.phase(
                checkpoint("terminal", "3", "null")));
        assertThrows(Exception.class, () -> EmbeddedDuplexStartJournal.phase(
                checkpoint("unknown", "3", "null")));
        assertThrows(Exception.class, () -> EmbeddedDuplexStartJournal.phase(
                checkpoint("terminal", "3", "\"not-a-hash\"")));
        assertThrows(Exception.class, () -> EmbeddedDuplexStartJournal.phase(
                checkpoint("terminal", "3", "null").replace("}", ",\"extra\":true}")));
    }
}
