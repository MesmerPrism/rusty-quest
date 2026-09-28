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
        long routeGeneration();
        long decoderToken();
        long readerGeneration();
        void activateIncomingProjection();
        long[] currentProjection();
        default String incomingDiagnostic() { return "receiverState=UNAVAILABLE connection=UNAVAILABLE packets=-1 frames=-1 reconnects=-1"; }
    }

    interface Clock { long wallTimeMillis(); }

    private final ReentrantLock lock = new ReentrantLock();
    private final Target target;
    private final Clock clock;
    private ArmEvidence armed;
    private boolean cleanupStarted;
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
                stateRevision++;
            }
        } finally { lock.unlock(); }
    }

    void afterVerifiedOwnerEffect(JSONObject authority, MediaTicket ticket, JSONObject readback,
            JSONObject verified, boolean compensate) {
        if (!ticket.incomingSink()) return;
        lock.lock();
        try {
            if (compensate || ticket.terminal()) return;
            if (!ticket.armReceiver() || cleanupStarted || activationUncertain) {
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
            armed = new ArmEvidence(ticket, authority, verified.getString("receipt_id"));
            stateRevision++;
        } catch (Exception invalid) {
            throw invalid instanceof IllegalStateException
                    ? (IllegalStateException) invalid
                    : new IllegalStateException("incoming Sink arm evidence", invalid);
        } finally { lock.unlock(); }
    }

    private enum ActivationStage { ARM_PROOF, FIRST_RENDER, NATIVE_ACQUISITION, GRAPH_ATTACH, NATIVE_EFFECTIVE }
    private static final class ActivationAttempt { ActivationStage stage = ActivationStage.ARM_PROOF; }

    String activate(String activationId, String authorityJson, String proofJson) throws Exception {
        ActivationAttempt attempt = new ActivationAttempt();
        try { return activateObserved(activationId, authorityJson, proofJson, attempt); }
        catch (Exception failure) {
            String category = closedActivationCause(failure);
            String receiverDiagnostic;
            try { receiverDiagnostic = target.incomingDiagnostic(); }
            catch (RuntimeException unavailable) { receiverDiagnostic = "receiverState=UNAVAILABLE connection=UNAVAILABLE packets=-1 frames=-1 reconnects=-1"; }
            android.util.Log.i("RQSpatialCameraPanel", "channel=embedded-duplex status=activation-rejected stage="
                    + attempt.stage.name() + " cause=" + category + " code=ACTIVATION_EFFECT_UNCERTAIN " + receiverDiagnostic);
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
        attempt.stage = ActivationStage.FIRST_RENDER;
        target.awaitFirstRenderedFrame();
        attempt.stage = ActivationStage.NATIVE_ACQUISITION;
        long[] frame = awaitCurrentIncomingFrame(current, claimedRevision, readinessDeadline);
        if (!currentFrame(frame) || clock.wallTimeMillis() >= current.expiresAtMs) {
            throw new IllegalStateException("fresh incoming frame unavailable");
        }
        requireCurrentClaim(current, claimedRevision);
        attempt.stage = ActivationStage.GRAPH_ATTACH;
        target.activateIncomingProjection();
        attempt.stage = ActivationStage.NATIVE_EFFECTIVE;
        long[] projection = target.currentProjection();
        if (!effectiveProjection(projection) || clock.wallTimeMillis() >= current.expiresAtMs) {
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
            long deadlineNs) throws Exception {
        while (true) {
            requireCurrentClaim(current, claimedRevision);
            if (clock.wallTimeMillis() >= current.expiresAtMs) {
                throw new IllegalStateException("fresh incoming frame unavailable");
            }
            long[] frame = target.currentIncomingFrame(MAX_FRAME_AGE_NS);
            // Native null can mean that the independent AImageReader acquisition
            // callback has not joined the decoder render callback yet. A present
            // identity mismatch remains an immediate rejection.
            if (frame != null) return frame;
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

    private boolean currentFrame(long[] frame) {
        return frame != null && frame.length == EmbeddedDuplexNative.FRAME_OBSERVATION_WORDS
                && frame[0] == target.generation() && frame[2] == target.routeGeneration()
                && frame[3] == target.decoderToken() && frame[4] == target.readerGeneration();
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
        final long expectedRuntimeRevision, expiresAtMs;

        ArmEvidence(MediaTicket ticket, JSONObject authority, String receiptId) throws Exception {
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
            this.receiptId = receiptId;
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
        requireExactFields(value, "$schema", "authority_peer_id", "executor_peer_id",
                "peer_session_id", "route_grant_id", "route_authority_revision",
                "authority_runtime_host_id", "authority_provider_epoch_id",
                "platform_runtime_spec_id", "authority_client_id", "authority_runtime_lease_id",
                "signed_topology_sha256", "route_configuration_sha256",
                "route_authority_evidence_sha256", "expires_at_ms", "authorization_kind");
        if (!PROJECTION_SCHEMA.equals(value.getString("$schema"))
                || !"current_route".equals(value.getString("authorization_kind"))
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
