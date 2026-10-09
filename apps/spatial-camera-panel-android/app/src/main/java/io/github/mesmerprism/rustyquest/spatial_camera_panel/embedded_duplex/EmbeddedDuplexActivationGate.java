package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

/** Serializes the authenticated incoming Sink arm, graph activation, and cleanup. */
final class EmbeddedDuplexActivationGate {
    private static final String PROJECTION_SCHEMA = "rusty.quest.c1.owner_projection.v1";
    private static final String READBACK_SCHEMA =
            "rusty.quest.android.media.product_activation_readback.v1";
    private static final long MAX_FRAME_AGE_NS = 500_000_000L;

    interface Target {
        long generation();
        String incomingRuntimeSpecId();
        void awaitFirstRenderedFrame() throws Exception;
        long[] currentIncomingFrame(long maxAgeNs);
        /** New v2 Surface evidence. Defaults fail closed for older test adapters. */
        default void awaitFirstSurfaceImage() throws Exception { awaitFirstRenderedFrame(); }
        default long[] currentIncomingAcquiredFrame(long maxAgeNs) { return null; }
        default long[] currentIncomingEffectiveFrame(long maxAgeNs) { return null; }
        long routeGeneration();
        long decoderToken();
        long readerGeneration();
        void activateIncomingProjection();
        long[] currentProjection();
        default String incomingDiagnostic() { return "receiverState=UNAVAILABLE connection=UNAVAILABLE packets=-1 frames=-1 reconnects=-1"; }
        /**
         * Persists one closed activation-failure record on the executor before the
         * failure is returned to Rust (which maps it to the signed, schema-unchanged
         * activation_effect_uncertain response). Must not throw; must not block long.
         */
        default void recordActivationFailure(String closedRecord) { }
        /** Diagnostic only; production logs the same closed record. */
        default void logActivationFailure(String closedRecord) {
            android.util.Log.i("RQSpatialCameraPanel", "channel=embedded-duplex status=activation-rejected " + closedRecord);
        }
    }

    interface Clock { long wallTimeMillis(); }

    private final ReentrantLock lock = new ReentrantLock();
    private final Target target;
    private final Clock clock;
    private ArmEvidence armed;
    private boolean cleanupStarted;
    private MediaTicket pendingTerminal;
    private MediaTicket pendingFreshArm;
    private ArmEvidence terminalArm;
    private long terminalProviderRevision;
    private boolean activationUncertain;
    private boolean activated;
    private long stateRevision;

    EmbeddedDuplexActivationGate(Target target) {
        this(target, System::currentTimeMillis);
    }

    EmbeddedDuplexActivationGate(Target target, Clock clock) {
        if (target == null || clock == null || target.generation() <= 0L
                || empty(target.incomingRuntimeSpecId())) {
            throw new IllegalArgumentException("activation target");
        }
        this.target = target;
        this.clock = clock;
    }

    void beforeOwnerEffect(JSONObject authority, MediaTicket ticket, boolean compensate) {
        if (!ticket.incomingSink()) return;
        lock.lock();
        try {
            if (compensate || ticket.terminal()) {
                cleanupStarted = true;
                pendingTerminal = compensate ? null : ticket;
                terminalArm = null;
                pendingFreshArm = null;
                stateRevision++;
            } else if (ticket.armReceiver() && cleanupStarted) {
                if (pendingFreshArm != null) throw new IllegalStateException("incoming Sink activation order");
                requireFreshArm(authority, ticket);
                pendingFreshArm = ticket;
                stateRevision++;
            }
        } finally { lock.unlock(); }
    }

    void afterVerifiedOwnerEffect(JSONObject authority, MediaTicket ticket, JSONObject readback,
            JSONObject verified, boolean compensate) {
        if (!ticket.incomingSink()) return;
        lock.lock();
        try {
            if (compensate) return;
            if (ticket.terminal()) {
                recordVerifiedTerminal(authority, ticket, readback, verified);
                return;
            }
            if (!ticket.armReceiver() || (!cleanupStarted && activationUncertain)) {
                throw new IllegalStateException("incoming Sink activation order");
            }
            requireProjection(authority);
            requireExactFields(verified, "$schema", "receipt_id", "readback_sha256",
                    "executor_generation", "provider_state_revision", "observed_state", "terminal",
                    "provider_handle_id", "detail_sha256");
            if (!"rusty.quest.android.media.verified_owner_effect.v1".equals(
                    verified.getString("$schema"))
                    || verified.getLong("executor_generation") != target.generation()
                    || verified.getBoolean("terminal")
                    || !verified.getString("receipt_id").equals(readback.getString("receipt_id"))
                    || !"receiver_armed".equals(verified.getString("observed_state"))) {
                throw new IllegalStateException("incoming Sink arm evidence");
            }
            ArmEvidence next = new ArmEvidence(ticket, authority, verified);
            if (cleanupStarted) {
                if (pendingFreshArm != ticket) throw new IllegalStateException("incoming Sink activation order");
                requireFreshArm(authority, ticket);
                if (next.providerHandleId.equals(terminalArm.providerHandleId)
                        || next.routeGeneration == terminalArm.routeGeneration
                        || next.decoderToken == terminalArm.decoderToken
                        || next.readerGeneration == terminalArm.readerGeneration) {
                    throw new IllegalStateException("incoming Sink arm evidence");
                }
                cleanupStarted = false;
                activationUncertain = false;
                activated = false;
                pendingTerminal = null;
                pendingFreshArm = null;
                terminalArm = null;
            }
            armed = next;
            stateRevision++;
        } catch (Exception invalid) {
            throw invalid instanceof IllegalStateException
                    ? (IllegalStateException) invalid
                    : new IllegalStateException("incoming Sink arm evidence", invalid);
        } finally { lock.unlock(); }
    }

    // The same callback's verified terminal effect is the only reopening fence.
    // A pending/failed/compensating Stop cannot manufacture a fresh arm scope.
    private void recordVerifiedTerminal(JSONObject authority, MediaTicket ticket,
            JSONObject readback, JSONObject verified) throws Exception {
        if (armed == null) return; // A valid unarmed cleanup never grants a restart scope.
        if (!cleanupStarted || pendingTerminal != ticket
                || !"stop".equals(ticket.operation) || !"stop".equals(ticket.actionKind)
                || ticket.generation != target.generation()
                || !ticket.authorityEpochId.equals(armed.providerEpochId)
                || !ticket.clientId.equals(armed.clientId) || !ticket.leaseId.equals(armed.leaseId)) {
            return; // Verified physical cleanup is not a grant to reopen this arm lineage.
        }
        boolean retained = "rusty.quest.android.media.retained_cleanup_projection.v2".equals(
                authority.getString("$schema"));
        String epoch = authority.getString(retained ? "provider_epoch_id" : "authority_provider_epoch_id");
        String client = authority.getString(retained ? "target_client_id" : "authority_client_id");
        String lease = authority.getString(retained ? "target_runtime_lease_id" : "authority_runtime_lease_id");
        if (!retained) requireProjection(authority, true);
        requireExactFields(verified, "$schema", "receipt_id", "readback_sha256",
                "executor_generation", "provider_state_revision", "observed_state", "terminal",
                "provider_handle_id", "detail_sha256");
        if (!epoch.equals(armed.providerEpochId) || !client.equals(armed.clientId)
                || !lease.equals(armed.leaseId)
                || !target.incomingRuntimeSpecId().equals(authority.getString("platform_runtime_spec_id"))
                || authority.getLong("expires_at_ms") <= clock.wallTimeMillis()
                || !"rusty.quest.android.media.verified_owner_effect.v1".equals(verified.getString("$schema"))
                || verified.getLong("executor_generation") != target.generation()
                || !verified.getBoolean("terminal") || !"stopped".equals(verified.getString("observed_state"))
                || !verified.getString("receipt_id").equals(readback.getString("receipt_id"))
                || !verified.getString("provider_handle_id").equals(armed.providerHandleId)
                || verified.getLong("provider_state_revision") <= armed.providerStateRevision) {
            return; // Leave the cleanup fence closed; do not veto registry-verified cleanup.
        }
        terminalArm = armed;
        terminalProviderRevision = verified.getLong("provider_state_revision");
        stateRevision++;
    }

    private void requireFreshArm(JSONObject authority, MediaTicket ticket) {
        try {
            requireProjection(authority);
            if (terminalArm == null || terminalProviderRevision <= terminalArm.providerStateRevision
                    || ticket.generation != target.generation()
                    || empty(ticket.actionId) || ticket.actionId.equals(terminalArm.actionId)
                    || !ticket.authorityEpochId.equals(terminalArm.providerEpochId)
                    || !ticket.authorityEpochId.equals(authority.getString("authority_provider_epoch_id"))
                    || !ticket.clientId.equals(authority.getString("authority_client_id"))
                    || !ticket.leaseId.equals(authority.getString("authority_runtime_lease_id"))
                    || !target.incomingRuntimeSpecId().equals(authority.getString("platform_runtime_spec_id"))
                    || authority.getLong("expires_at_ms") <= clock.wallTimeMillis()
                    || terminalArm.routeGrantId.equals(authority.getString("route_grant_id"))) {
                throw new IllegalStateException("incoming Sink activation order");
            }
        } catch (Exception invalid) {
            throw new IllegalStateException("incoming Sink activation order", invalid);
        }
    }

    private enum ActivationStage { ARM_PROOF, FIRST_SURFACE_IMAGE, GRAPH_ATTACH, NATIVE_EFFECTIVE }
    private static final class ActivationAttempt {
        ActivationStage stage = ActivationStage.ARM_PROOF;
        final long startedNs = System.nanoTime();
        long elapsedMs() { return Math.max(0L, (System.nanoTime() - startedNs) / 1_000_000L); }
    }

    String activate(String activationId, String authorityJson, String proofJson) throws Exception {
        ActivationAttempt attempt = new ActivationAttempt();
        try { return activateObserved(activationId, authorityJson, proofJson, attempt); }
        catch (Exception failure) {
            String category = closedActivationCause(failure);
            String receiverDiagnostic;
            try { receiverDiagnostic = target.incomingDiagnostic(); }
            catch (RuntimeException unavailable) { receiverDiagnostic = "receiverState=UNAVAILABLE connection=UNAVAILABLE packets=-1 frames=-1 reconnects=-1"; }
            String record = "stage=" + attempt.stage.name() + " cause=" + category
                    + " elapsedMs=" + attempt.elapsedMs()
                    + " code=ACTIVATION_EFFECT_UNCERTAIN " + receiverDiagnostic;
            // Persist first: logcat capture has already missed this line once.
            try { target.recordActivationFailure(record); } catch (RuntimeException ignored) { }
            try { target.logActivationFailure(record); } catch (RuntimeException ignored) { }
            throw failure;
        }
    }

    private static String closedActivationCause(Throwable failure) {
        String category = "OTHER";
        Throwable current = failure;
        for (int i = 0; current != null && i < 8; i++, current = current.getCause()) {
            if ("android.media.MediaCodec$CodecException".equals(current.getClass().getName())) return "CODEC";
            if (current instanceof InterruptedException) return "INTERRUPTED";
            if (current instanceof java.util.concurrent.TimeoutException) return "TIMEOUT";
            if (current instanceof java.io.IOException) category = "IO";
            else if (current instanceof IllegalStateException && "OTHER".equals(category)) category = "STATE";
        }
        return category;
    }

    private String activateObserved(String activationId, String authorityJson, String proofJson,
            ActivationAttempt attempt) throws Exception {
        ArmEvidence current;
        long claimedRevision;
        lock.lock();
        try {
            if (empty(activationId) || activationId.length() > 4096 || cleanupStarted
                    || activationUncertain || activated || armed == null) {
                throw new IllegalStateException("activation unavailable");
            }
            JSONObject authority = new JSONObject(authorityJson);
            JSONObject proof = new JSONObject(proofJson);
            requireProjection(authority);
            requireExactFields(proof, "action_id", "provider_epoch_id", "client_id", "lease_id",
                    "runtime_spec_id", "resulting_runtime_revision", "owner_receipt_ids",
                    "completion_sha256");
            current = armed;
            long before = clock.wallTimeMillis();
            if (!current.matches(authority, proof, before)
                    || !containsExactSeven(proof.getJSONArray("owner_receipt_ids"), current.receiptId)
                    || !sha256(proof.getString("completion_sha256"))) {
                throw new IllegalStateException("activation proof binding");
            }
            activationUncertain = true;
            stateRevision++;
            claimedRevision = stateRevision;
        } finally { lock.unlock(); }

        // Display and native callbacks can reenter the process. Keep the claim
        // uncertain until readback succeeds, without holding the state lock.
        long readinessDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        attempt.stage = ActivationStage.FIRST_SURFACE_IMAGE;
        target.awaitFirstSurfaceImage();
        long[] frame = awaitCurrentIncomingFrame(current, claimedRevision, readinessDeadline,
                false, 0L);
        if (!acquiredFrame(frame) || clock.wallTimeMillis() >= current.expiresAtMs) {
            throw new IllegalStateException("fresh incoming frame unavailable");
        }
        requireCurrentClaim(current, claimedRevision);
        attempt.stage = ActivationStage.GRAPH_ATTACH;
        target.activateIncomingProjection();
        attempt.stage = ActivationStage.NATIVE_EFFECTIVE;
        long[] effectiveFrame = awaitCurrentIncomingFrame(current, claimedRevision,
                readinessDeadline, true, frame[17]);
        long[] projection = target.currentProjection();
        if (!effectiveFrame(effectiveFrame, frame) || !effectiveProjection(projection)
                || clock.wallTimeMillis() >= current.expiresAtMs) {
            throw new IllegalStateException("native Peer projection not effective");
        }

        lock.lock();
        try {
            assertCurrentClaim(current, claimedRevision);
            activationUncertain = false;
            activated = true;
            stateRevision++;
            JSONObject result = new JSONObject();
            result.put("$schema", READBACK_SCHEMA);
            result.put("activation_id", activationId);
            result.put("route_grant_id", current.routeGrantId);
            result.put("resulting_state_revision", stateRevision);
            result.put("activated", true);
            return result.toString();
        } finally { lock.unlock(); }
    }

    private long[] awaitCurrentIncomingFrame(ArmEvidence current, long claimedRevision,
            long deadlineNs, boolean effective, long minimumGpuRetiredNs) throws Exception {
        while (true) {
            requireCurrentClaim(current, claimedRevision);
            if (clock.wallTimeMillis() >= current.expiresAtMs) {
                throw new IllegalStateException("fresh incoming frame unavailable");
            }
            long[] frame = effective ? target.currentIncomingEffectiveFrame(MAX_FRAME_AGE_NS)
                    : target.currentIncomingAcquiredFrame(MAX_FRAME_AGE_NS);
            // Native null means the exact Surface/GPU witness has not appeared.
            // A present malformed or foreign identity fails immediately below.
            if (frame != null) {
                // The GPU may still be retiring an older import. Require a real
                // retirement after the first observed Surface acquisition;
                // PTS remains an exact key, never an ordering promise.
                if (!effective || frame.length != EmbeddedDuplexNative.EFFECTIVE_TIMED_OBSERVATION_WORDS
                        || frame[0] != EmbeddedDuplexNative.FRAME_EVIDENCE_VERSION
                        || frame[1] != target.generation() || frame[3] != target.routeGeneration()
                        || frame[4] != target.decoderToken() || frame[5] != target.readerGeneration()
                        || frame[17] <= 0L || frame[17] >= minimumGpuRetiredNs) return frame;
            }
            if (System.nanoTime() >= deadlineNs) {
                throw new IllegalStateException("fresh incoming frame unavailable");
            }
            Thread.sleep(5L);
        }
    }

    private void requireCurrentClaim(ArmEvidence current, long claimedRevision) {
        lock.lock();
        try { assertCurrentClaim(current, claimedRevision); }
        finally { lock.unlock(); }
    }

    private void assertCurrentClaim(ArmEvidence current, long claimedRevision) {
        if (cleanupStarted || armed != current || !activationUncertain || activated
                || stateRevision != claimedRevision) {
            throw new IllegalStateException("activation changed during display work");
        }
    }

    private boolean acquiredFrame(long[] frame) {
        return frame != null && frame.length == EmbeddedDuplexNative.ACQUIRED_TIMED_OBSERVATION_WORDS
                && frame[0] == EmbeddedDuplexNative.FRAME_EVIDENCE_VERSION
                && frame[1] == target.generation() && frame[2] > 0L
                && frame[3] == target.routeGeneration() && frame[4] == target.decoderToken()
                && frame[5] == target.readerGeneration() && frame[6] > 0L
                && frame[15] > 0L && frame[16] >= frame[15] && frame[17] >= frame[16]
                && frame[18] >= 0L && frame[18] <= MAX_FRAME_AGE_NS
                && frame[18] == frame[17] - frame[15];
    }

    private boolean effectiveFrame(long[] effective, long[] acquired) {
        return effective != null
                && effective.length == EmbeddedDuplexNative.EFFECTIVE_TIMED_OBSERVATION_WORDS
                && effective[0] == EmbeddedDuplexNative.FRAME_EVIDENCE_VERSION
                && effective[1] == target.generation() && effective[2] == acquired[2]
                && effective[3] == target.routeGeneration()
                && effective[4] == target.decoderToken()
                && effective[5] == target.readerGeneration()
                && effective[6] > 0L && effective[15] > 0L
                && effective[16] >= effective[15]
                && effective[17] >= effective[16]
                && effective[17] >= acquired[17]
                && effective[18] >= effective[17]
                && effective[19] >= 0L && effective[19] <= MAX_FRAME_AGE_NS
                && effective[19] == effective[18] - effective[15]
                && effective[20] > 0L;
    }

    private boolean effectiveProjection(long[] words) {
        return words != null && words.length == 16 && words[0] == 1L
                && words[1] == target.routeGeneration() && words[2] == 2L
                && words[3] == target.decoderToken() && words[4] == target.readerGeneration()
                && words[9] > 0L && words[11] == 1L && words[12] == 0L && words[13] == 0L;
    }

    static final class MediaTicket {
        final long generation, expectedRuntimeRevision;
        final String actionId, authorityEpochId, clientId, leaseId, operation, ownerKind, actionKind;

        MediaTicket(long generation, long expectedRuntimeRevision, String actionId,
                String authorityEpochId, String clientId, String leaseId, String operation,
                String ownerKind, String actionKind) {
            this.generation = generation;
            this.expectedRuntimeRevision = expectedRuntimeRevision;
            this.actionId = actionId;
            this.authorityEpochId = authorityEpochId;
            this.clientId = clientId;
            this.leaseId = leaseId;
            this.operation = operation;
            this.ownerKind = ownerKind;
            this.actionKind = actionKind;
        }

        boolean incomingSink() { return "sink".equals(ownerKind); }
        boolean armReceiver() { return "start".equals(operation) && "arm_receiver".equals(actionKind); }
        boolean terminal() { return "stop".equals(operation)
                || "stop".equals(actionKind) || "cleanup".equals(actionKind); }
    }

    private final class ArmEvidence {
        final String actionId, providerEpochId, clientId, leaseId, runtimeSpecId;
        final String routeGrantId, routeConfigurationSha256, receiptId;
        final long expectedRuntimeRevision, expiresAtMs, providerStateRevision;
        final long routeGeneration, decoderToken, readerGeneration;
        final String providerHandleId;

        ArmEvidence(MediaTicket ticket, JSONObject authority, JSONObject verified) throws Exception {
            if (ticket.generation != target.generation()
                    || !ticket.authorityEpochId.equals(authority.getString("authority_provider_epoch_id"))
                    || !ticket.clientId.equals(authority.getString("authority_client_id"))
                    || !ticket.leaseId.equals(authority.getString("authority_runtime_lease_id"))
                    || !target.incomingRuntimeSpecId().equals(
                            authority.getString("platform_runtime_spec_id"))) {
                throw new IllegalStateException("incoming Sink ticket lineage");
            }
            actionId = ticket.actionId;
            providerEpochId = ticket.authorityEpochId;
            clientId = ticket.clientId;
            leaseId = ticket.leaseId;
            runtimeSpecId = target.incomingRuntimeSpecId();
            expectedRuntimeRevision = ticket.expectedRuntimeRevision;
            routeGrantId = authority.getString("route_grant_id");
            routeConfigurationSha256 = authority.getString("route_configuration_sha256");
            expiresAtMs = authority.getLong("expires_at_ms");
            receiptId = verified.getString("receipt_id");
            providerHandleId = verified.getString("provider_handle_id");
            providerStateRevision = verified.getLong("provider_state_revision");
            routeGeneration = target.routeGeneration();
            decoderToken = target.decoderToken();
            readerGeneration = target.readerGeneration();
            if (expiresAtMs <= clock.wallTimeMillis() || empty(providerHandleId)
                    || providerStateRevision <= 0L || routeGeneration <= 0L
                    || decoderToken <= 0L || readerGeneration <= 0L) {
                throw new IllegalStateException("incoming Sink arm evidence");
            }
        }

        boolean matches(JSONObject authority, JSONObject proof, long now) throws Exception {
            return now > 0L && now < expiresAtMs && expiresAtMs == authority.getLong("expires_at_ms")
                    && routeGrantId.equals(authority.getString("route_grant_id"))
                    && routeConfigurationSha256.equals(authority.getString("route_configuration_sha256"))
                    && providerEpochId.equals(authority.getString("authority_provider_epoch_id"))
                    && clientId.equals(authority.getString("authority_client_id"))
                    && leaseId.equals(authority.getString("authority_runtime_lease_id"))
                    && runtimeSpecId.equals(authority.getString("platform_runtime_spec_id"))
                    && actionId.equals(proof.getString("action_id"))
                    && providerEpochId.equals(proof.getString("provider_epoch_id"))
                    && clientId.equals(proof.getString("client_id"))
                    && leaseId.equals(proof.getString("lease_id"))
                    && runtimeSpecId.equals(proof.getString("runtime_spec_id"))
                    && proof.getLong("resulting_runtime_revision") > expectedRuntimeRevision;
        }
    }

    private static void requireProjection(JSONObject value) throws Exception {
        requireProjection(value, false);
    }

    // A verified terminal Stop may carry the C1 cleanup projection. Arm and
    // activation still require current_route; cleanup never grants either.
    private static void requireProjection(JSONObject value, boolean terminalStop) throws Exception {
        requireExactFields(value, "$schema", "authority_peer_id", "executor_peer_id",
                "peer_session_id", "route_grant_id", "route_authority_revision",
                "authority_runtime_host_id", "authority_provider_epoch_id",
                "platform_runtime_spec_id", "authority_client_id", "authority_runtime_lease_id",
                "signed_topology_sha256", "route_configuration_sha256",
                "route_authority_evidence_sha256", "expires_at_ms", "authorization_kind");
        if (!PROJECTION_SCHEMA.equals(value.getString("$schema"))
                || !("current_route".equals(value.getString("authorization_kind"))
                    || (terminalStop && "retained_cleanup".equals(value.getString("authorization_kind"))))
                || value.getLong("route_authority_revision") <= 0L
                || !sha256(value.getString("signed_topology_sha256"))
                || !sha256(value.getString("route_configuration_sha256"))
                || !sha256(value.getString("route_authority_evidence_sha256"))) {
            throw new IllegalStateException("activation authority projection");
        }
    }

    private static boolean containsExactSeven(JSONArray values, String required) throws Exception {
        if (values.length() != 7) return false;
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            String value = values.getString(i);
            if (empty(value) || !unique.add(value)) return false;
        }
        return unique.contains(required);
    }

    private static void requireExactFields(JSONObject value, String... expected) {
        Set<String> actual = new HashSet<>();
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) actual.add(keys.next());
        if (!actual.equals(new HashSet<>(Arrays.asList(expected)))) {
            throw new IllegalArgumentException("closed JSON shape");
        }
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    private static boolean empty(String value) { return value == null || value.isEmpty(); }
}
