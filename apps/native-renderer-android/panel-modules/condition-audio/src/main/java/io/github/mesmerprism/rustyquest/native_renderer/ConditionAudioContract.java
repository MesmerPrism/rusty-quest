package io.github.mesmerprism.rustyquest.native_renderer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Generic, identifier-minimal contract for packaged condition audio. */
final class ConditionAudioContract {
    static final String SCHEMA = "rusty.quest.condition_audio.receipt.v1";
    static final String PACKAGED_PROVIDER_ORIGIN = "packaged-native-app-provider";

    enum CommandKind { PREPARE, START, PAUSE, RESUME, THRESHOLD_REACHED, STOP }
    enum StopReason { RESTART_TO_EXPERIMENTER, SAVE_AND_EXIT }
    enum TrackState { UNAVAILABLE, PENDING, READY, FAILED }
    enum Event {
        PREPARE_ACCEPTED,
        PREPARED,
        START_ACCEPTED,
        ACTUAL_START,
        PAUSE_ACCEPTED,
        PAUSED,
        RESUME_ACCEPTED,
        RESUMED,
        PROGRESS,
        THRESHOLD_CONTINUES,
        NATURAL_END,
        ERROR,
        CLEANUP_FAILED,
        STOP_ACCEPTED,
        STOPPED,
        REJECTED
    }
    enum Phase {
        UNAVAILABLE,
        IDLE,
        PREPARING,
        PREPARED,
        STARTING,
        PLAYING,
        PAUSING,
        PAUSED,
        RESUMING,
        SILENT_AFTER_NATURAL_END,
        STOPPING,
        STOPPED,
        ERROR
    }

    static final class Provider {
        final String conditionId;
        final String sourceToken;
        final String logicalDestination;
        final String sourceSha256;
        final long sourceBytes;
        final String mediaType;

        Provider(
            String conditionId,
            String sourceToken,
            String logicalDestination,
            String sourceSha256,
            long sourceBytes,
            String mediaType
        ) {
            this.conditionId = safe(conditionId);
            this.sourceToken = safe(sourceToken);
            this.logicalDestination = safe(logicalDestination);
            this.sourceSha256 = safe(sourceSha256);
            this.sourceBytes = sourceBytes;
            this.mediaType = safe(mediaType);
        }

        boolean valid() {
            return token(conditionId)
                && token(sourceToken)
                && token(logicalDestination)
                && sha256(sourceSha256)
                && sourceBytes > 0L
                && mediaType.startsWith("audio/");
        }
    }

    /**
     * This type can be constructed only inside the owning package. The future packaged-settings
     * loader must create it after validating the build-owned provider inventory; runtime callers
     * cannot submit paths, URIs, labels, or replacement assets.
     */
    static final class TrustedPackagedInventory {
        final String providerOrigin;
        final String inventorySha256;
        final Map<String, Provider> providers;
        final boolean available;
        final String reason;

        private TrustedPackagedInventory(
            String providerOrigin,
            String inventorySha256,
            Map<String, Provider> providers,
            boolean available,
            String reason
        ) {
            this.providerOrigin = safe(providerOrigin);
            this.inventorySha256 = safe(inventorySha256);
            this.providers = Collections.unmodifiableMap(
                new LinkedHashMap<String, Provider>(providers)
            );
            this.available = available;
            this.reason = safe(reason);
        }

        static TrustedPackagedInventory fromValidatedProvider(
            String providerOrigin,
            String inventorySha256,
            Provider[] entries
        ) {
            LinkedHashMap<String, Provider> mapped = new LinkedHashMap<String, Provider>();
            if (!PACKAGED_PROVIDER_ORIGIN.equals(providerOrigin)
                    || !sha256(inventorySha256)
                    || entries == null
                    || entries.length == 0) {
                return unavailable("inventory-unavailable");
            }
            for (Provider entry : entries) {
                if (entry == null || !entry.valid() || mapped.containsKey(entry.conditionId)) {
                    return unavailable("inventory-invalid");
                }
                mapped.put(entry.conditionId, entry);
            }
            return new TrustedPackagedInventory(
                providerOrigin,
                inventorySha256,
                mapped,
                true,
                "available"
            );
        }

        static TrustedPackagedInventory unavailable(String reason) {
            return new TrustedPackagedInventory(
                PACKAGED_PROVIDER_ORIGIN,
                "",
                Collections.<String, Provider>emptyMap(),
                false,
                reason
            );
        }

        Provider providerFor(String conditionId) {
            return providers.get(safe(conditionId));
        }

        Provider[] providersInPackagedOrder() {
            return providers.values().toArray(new Provider[providers.size()]);
        }
    }

    static final class Command {
        final CommandKind kind;
        final long sessionGeneration;
        final String operationId;
        final String conditionId;
        final StopReason stopReason;

        Command(
            CommandKind kind,
            long sessionGeneration,
            String operationId,
            String conditionId,
            StopReason stopReason
        ) {
            this.kind = kind;
            this.sessionGeneration = sessionGeneration;
            this.operationId = safe(operationId);
            this.conditionId = safe(conditionId);
            this.stopReason = stopReason;
        }

        static Command prepare(long generation, String operationId, String conditionId) {
            return new Command(CommandKind.PREPARE, generation, operationId, conditionId, null);
        }

        static Command start(long generation, String operationId) {
            return new Command(CommandKind.START, generation, operationId, "", null);
        }

        static Command pause(long generation, String operationId) {
            return new Command(CommandKind.PAUSE, generation, operationId, "", null);
        }

        static Command resume(long generation, String operationId) {
            return new Command(CommandKind.RESUME, generation, operationId, "", null);
        }

        static Command thresholdReached(long generation, String operationId) {
            return new Command(CommandKind.THRESHOLD_REACHED, generation, operationId, "", null);
        }

        static Command stop(long generation, String operationId, StopReason reason) {
            return new Command(CommandKind.STOP, generation, operationId, "", reason);
        }
    }

    static final class Submission {
        final boolean accepted;
        final boolean pending;
        final boolean duplicate;
        final String reason;
        final long sessionGeneration;
        final String operationId;

        Submission(
            boolean accepted,
            boolean pending,
            boolean duplicate,
            String reason,
            long sessionGeneration,
            String operationId
        ) {
            this.accepted = accepted;
            this.pending = pending;
            this.duplicate = duplicate;
            this.reason = safe(reason);
            this.sessionGeneration = sessionGeneration;
            this.operationId = safe(operationId);
        }
    }

    /** Immutable, nonblocking startup-readiness view for the packaged condition inventory. */
    static final class TrackReadiness {
        final boolean inventoryAvailable;
        final String conditionId;
        final TrackState state;
        final String reason;

        TrackReadiness(boolean inventoryAvailable, String conditionId, TrackState state, String reason) {
            this.inventoryAvailable = inventoryAvailable;
            this.conditionId = safe(conditionId);
            this.state = state == null ? TrackState.UNAVAILABLE : state;
            this.reason = safe(reason);
        }
    }

    static final class Receipt {
        final String schema = SCHEMA;
        final Event event;
        final long receiptRevision;
        final long sessionGeneration;
        final String operationId;
        final String conditionId;
        final String sourceSha256;
        final long positionMs;
        final String reason;
        final StopReason stopReason;

        Receipt(
            Event event,
            long receiptRevision,
            long sessionGeneration,
            String operationId,
            String conditionId,
            String sourceSha256,
            long positionMs,
            String reason,
            StopReason stopReason
        ) {
            this.event = event;
            this.receiptRevision = receiptRevision;
            this.sessionGeneration = sessionGeneration;
            this.operationId = safe(operationId);
            this.conditionId = safe(conditionId);
            this.sourceSha256 = safe(sourceSha256);
            this.positionMs = Math.max(0L, positionMs);
            this.reason = safe(reason);
            this.stopReason = stopReason;
        }
    }

    static final class Snapshot {
        final Phase phase;
        final long sessionGeneration;
        final long receiptRevision;
        final String conditionId;
        final String operationId;
        final long positionMs;
        final String reason;

        Snapshot(
            Phase phase,
            long sessionGeneration,
            long receiptRevision,
            String conditionId,
            String operationId,
            long positionMs,
            String reason
        ) {
            this.phase = phase;
            this.sessionGeneration = sessionGeneration;
            this.receiptRevision = receiptRevision;
            this.conditionId = safe(conditionId);
            this.operationId = safe(operationId);
            this.positionMs = Math.max(0L, positionMs);
            this.reason = safe(reason);
        }
    }

    interface ReceiptSink { void onReceipt(Receipt receipt); }

    private ConditionAudioContract() {}

    private static boolean token(String value) {
        return value != null && value.matches("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,127}");
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
