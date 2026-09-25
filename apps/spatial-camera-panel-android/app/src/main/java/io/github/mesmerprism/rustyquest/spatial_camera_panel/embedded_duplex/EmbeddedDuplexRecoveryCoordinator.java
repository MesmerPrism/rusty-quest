package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import org.json.JSONObject;

/** Serial command-lane gate for the two durable recovery records. No record authorizes Start. */
final class EmbeddedDuplexRecoveryCoordinator {
    interface Store {
        String readCheckpoint() throws Exception;
        String readEvidence() throws Exception;
        void persistEvidence(String exactJson) throws Exception;
        void persistCheckpoint(String exactJson) throws Exception;
    }

    private final Store store;

    EmbeddedDuplexRecoveryCoordinator(Context context) throws Exception {
        EmbeddedDuplexStartJournal checkpoint = new EmbeddedDuplexStartJournal(context);
        EmbeddedDuplexRecoveryEvidenceJournal evidence =
                new EmbeddedDuplexRecoveryEvidenceJournal(context);
        store = new Store() {
            public String readCheckpoint() throws Exception { return checkpoint.read(); }
            public String readEvidence() throws Exception { return evidence.readValidated(); }
            public void persistEvidence(String json) throws Exception { evidence.persist(json); }
            public void persistCheckpoint(String json) throws Exception { checkpoint.persist(json); }
        };
    }

    EmbeddedDuplexRecoveryCoordinator(Store store) {
        if (store == null) throw new IllegalArgumentException("recovery store absent");
        this.store = store;
    }

    /** Any incomplete pair, malformed record or retained transaction blocks fresh authority. */
    synchronized boolean freshBootstrapAllowed() {
        try {
            String checkpoint = store.readCheckpoint();
            String evidence = store.readEvidence();
            if (checkpoint == null) return evidence == null;
            String phase = EmbeddedDuplexStartJournal.phase(checkpoint);
            if ("terminal".equals(phase)) return evidence == null;
            if (evidence != null) {
                String epoch = new JSONObject(evidence).getString("provider_epoch_id");
                EmbeddedDuplexRecoveryEvidenceJournal.validateAgainstCheckpoint(
                        checkpoint, evidence, epoch);
            }
            return false;
        } catch (Exception damagedOrUncertain) {
            return false;
        }
    }

    synchronized void requireFreshBootstrap() {
        if (!freshBootstrapAllowed()) {
            throw new IllegalStateException("retained duplex transaction requires recovery");
        }
    }

    /** Internal recovery-only writer. A crash between synced writes stays blocked. */
    synchronized void persistWriteAhead(String exactCheckpoint, String exactEvidence,
            String expectedProviderEpochId) throws Exception {
        EmbeddedDuplexRecoveryEvidenceJournal.validateAgainstCheckpoint(
                exactCheckpoint, exactEvidence, expectedProviderEpochId);
        String previous = store.readCheckpoint();
        String priorEvidence = store.readEvidence();
        if (previous != null) {
            JSONObject old = new JSONObject(previous);
            JSONObject next = new JSONObject(exactCheckpoint);
            EmbeddedDuplexStartJournal.phase(previous);
            if (priorEvidence == null || "terminal".equals(old.getString("phase"))
                    || !old.getString("lineage_sha256").equals(next.getString("lineage_sha256"))
                    || !new JSONObject(priorEvidence).getString("provider_epoch_id")
                            .equals(expectedProviderEpochId)
                    || next.getLong("revision") <= old.getLong("revision")) {
                throw new IllegalStateException("recovery write-ahead lineage or revision differs");
            }
            EmbeddedDuplexRecoveryEvidenceJournal.validateAgainstCheckpoint(previous,
                    priorEvidence, expectedProviderEpochId);
        } else if (priorEvidence != null) {
            throw new IllegalStateException("orphan recovery evidence blocks write-ahead");
        }
        store.persistEvidence(exactEvidence);
        store.persistCheckpoint(exactCheckpoint);
    }
}
