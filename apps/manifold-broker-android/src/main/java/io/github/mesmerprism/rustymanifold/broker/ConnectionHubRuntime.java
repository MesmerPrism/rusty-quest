package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quest lifecycle/transport coordinator. All acceptance is delegated through
 * {@link ConnectionHubAuthorityPort}; this class owns only Android state,
 * provider routing, bounded serialization, and transport credentials.
 */
public final class ConnectionHubRuntime implements HubSurfaceRegistry.Listener {
    interface Clock { long nowMs(); }

    public interface EventSink {
        void broadcast(JSONObject event);
        void closeLogicalSession(String logicalSessionId, String reason);
        void closeAllSessions(String reason);
    }

    private final ConnectionHubAuthorityPort authority;
    private final ConnectionHubStateStore store;
    private final HubSurfaceRegistry registry;
    private final SecureRandom random;
    private final Clock clock;
    private final Map<String, ConnectionHubStateStore.SessionProjection> sessions =
            new LinkedHashMap<>();
    private final List<EventSink> eventSinks = new ArrayList<>();
    private final Map<String, SurfaceLeaseProjection> surfaceLeases = new LinkedHashMap<>();
    private final Map<String, RateWindow> commandRateWindows = new LinkedHashMap<>();
    private final Map<String, RateWindow> surfaceStateRateWindows = new LinkedHashMap<>();
    private boolean desiredRunning;
    private boolean listenerEnabled;
    private String pairingCode;
    private String transportEpoch;
    private String lastStatus = "disabled";
    private long pairAttemptWindowStartedMs;
    private int pairAttempts;
    private long pairLockedUntilMs;
    private long durableGeneration;
    private boolean mutationPrepared;
    private boolean durabilityFailed;

    public ConnectionHubRuntime(
            ConnectionHubAuthorityPort authority,
            ConnectionHubStateStore store,
            HubSurfaceRegistry registry,
            SecureRandom random) {
        this(authority, store, registry, random, new Clock() {
            @Override public long nowMs() { return System.currentTimeMillis(); }
        });
    }

    ConnectionHubRuntime(
            ConnectionHubAuthorityPort authority,
            ConnectionHubStateStore store,
            HubSurfaceRegistry registry,
            SecureRandom random,
            Clock clock) {
        this.authority = authority;
        this.store = store;
        this.registry = registry;
        this.random = random;
        this.clock = clock;
        this.registry.addListener(this);
        restore();
    }

    public synchronized void addEventSink(EventSink sink) {
        eventSinks.add(sink);
    }

    public synchronized boolean desiredRunning() { return desiredRunning; }
    public synchronized boolean listenerEnabled() { return listenerEnabled; }
    public synchronized String pairingCodeForWearer() { return pairingCode; }
    public synchronized String transportEpoch() { return transportEpoch; }
    public synchronized int activeSessionCount() { return sessions.size(); }
    public HubSurfaceRegistry registry() { return registry; }

    /** Explicit wearer action. Merely launching a provider cannot call this. */
    public synchronized void startRequested() {
        desiredRunning = true;
        listenerEnabled = false;
        transportEpoch = randomHex(16);
        pairingCode = sessions.isEmpty() ? randomDigits(6) : null;
        lastStatus = "starting_paired_trusted_lan_experimental";
        persist();
    }

    public synchronized void noteListenerStarted() {
        if (!desiredRunning) {
            throw new IllegalStateException("listener cannot start without desired running state");
        }
        listenerEnabled = true;
        lastStatus = sessions.isEmpty() ? "awaiting_pairing" : "running";
    }

    public synchronized void noteListenerFailure(String status) {
        listenerEnabled = false;
        lastStatus = status;
    }

    /** Stop preserves trusted controllers/session authority; Forget clears them. */
    public synchronized void stopRequested() {
        closeAllSessions("wearer_stop");
        desiredRunning = false;
        listenerEnabled = false;
        pairingCode = null;
        transportEpoch = "";
        lastStatus = "stopped_by_wearer";
        persist();
    }

    public synchronized ConnectionHubAuthorityPort.Receipt forgetRequested() {
        prepareMutation("forget_all");
        ConnectionHubAuthorityPort.Receipt receipt = authority.forgetAll(
                randomRequestId("forget"),
                "wearer_forget",
                clock.nowMs());
        if (receipt.applied) {
            sessions.clear();
            surfaceLeases.clear();
            pairingCode = desiredRunning ? randomDigits(6) : null;
            persist();
            closeAllSessions("wearer_forget");
        } else {
            persist();
        }
        lastStatus = receipt.status;
        return receipt;
    }

    public synchronized JSONObject pair(JSONObject request, String wearerEvidenceId) {
        try {
            long now = clock.nowMs();
            requireSchema(request, ConnectionHubProtocol.PAIR_REQUEST_SCHEMA);
            requireExactKeys(request,
                    new String[] {"$schema", "pairing_code", "controller_identity_sha256"},
                    new String[0]);
            if (!listenerEnabled || pairingCode == null) {
                return pairReceipt(false, "pairing_not_available", null, 0);
            }
            if (now < pairLockedUntilMs) {
                return pairReceipt(false, "pairing_rate_limited", null, 0);
            }
            if (pairAttemptWindowStartedMs == 0
                    || now - pairAttemptWindowStartedMs > ConnectionHubProtocol.AUTH_RATE_WINDOW_MS) {
                pairAttemptWindowStartedMs = now;
                pairAttempts = 0;
            }
            String supplied = request.getString("pairing_code");
            if (!constantTimeEquals(pairingCode, supplied)) {
                pairAttempts += 1;
                if (pairAttempts >= ConnectionHubProtocol.MAX_PAIR_ATTEMPTS_PER_WINDOW) {
                    pairLockedUntilMs = now + ConnectionHubProtocol.AUTH_RATE_WINDOW_MS;
                    pairingCode = randomDigits(6);
                }
                return pairReceipt(false, "pairing_code_rejected", null, 0);
            }
            String controllerIdentitySha256 = request.getString("controller_identity_sha256");
            if (!controllerIdentitySha256.matches("[0-9a-f]{64}")) {
                return pairReceipt(false, "controller_identity_invalid", null, 0);
            }
            prepareMutation("trust_and_open_session");
            ConnectionHubAuthorityPort.Receipt authorityReceipt = authority.trustAndOpenSession(
                    randomRequestId("pair"),
                    controllerIdentitySha256,
                    wearerEvidenceId,
                    clock.nowMs());
            if (!authorityReceipt.applied
                    || authorityReceipt.logicalSessionId == null
                    || authorityReceipt.transportEpoch < 1) {
                persist();
                return pairReceipt(false, authorityReceipt.status, null, 0);
            }
            String cookie = randomBase64Url(32);
            long expiresAtMs = authorityReceipt.expiresAtMs;
            if (expiresAtMs <= now) {
                persist();
                return pairReceipt(false, "authority_session_expiry_invalid", null, 0);
            }
            sessions.put(cookie, new ConnectionHubStateStore.SessionProjection(
                    authorityReceipt.logicalSessionId,
                    authorityReceipt.transportEpoch,
                    expiresAtMs));
            pairingCode = null;
            pairAttempts = 0;
            pairLockedUntilMs = 0;
            lastStatus = "controller_paired";
            persist();
            return pairReceipt(true, "paired", cookie, expiresAtMs)
                    .put("transport_epoch", authorityReceipt.transportEpoch)
                    .put("authority_receipt", new JSONObject(authorityReceipt.authorityReceiptJson));
        } catch (Exception error) {
            settlePreparedMutation();
            return pairReceipt(false, "invalid_pair_request", null, 0);
        }
    }

    public synchronized JSONObject revoke(JSONObject request) {
        try {
            requireSchema(request, ConnectionHubProtocol.REVOKE_REQUEST_SCHEMA);
            requireExactKeys(request,
                    new String[] {"$schema", "session"},
                    new String[] {"reason"});
            String cookie = request.getString("session");
            ConnectionHubStateStore.SessionProjection session = sessions.get(cookie);
            if (session == null) {
                return revokeReceipt(false, "session_not_found");
            }
            prepareMutation("revoke_session");
            ConnectionHubAuthorityPort.Receipt authorityReceipt = authority.revokeSession(
                    randomRequestId("revoke"),
                    session.logicalSessionId,
                    request.optString("reason", "user_request"),
                    clock.nowMs());
            long revokedTransportEpoch = session.transportEpoch;
            if (authorityReceipt.applied) {
                sessions.remove(cookie);
                commandRateWindows.remove(session.logicalSessionId);
                removeSessionLeases(session.logicalSessionId);
                if (sessions.isEmpty() && desiredRunning) {
                    pairingCode = randomDigits(6);
                }
            }
            persist();
            if (authorityReceipt.applied) {
                closeLogicalSession(session.logicalSessionId, "session_revoked");
            }
            return revokeReceipt(authorityReceipt.applied, authorityReceipt.status)
                    .put("transport_epoch", revokedTransportEpoch)
                    .put("authority_receipt", new JSONObject(authorityReceipt.authorityReceiptJson));
        } catch (Exception error) {
            settlePreparedMutation();
            return revokeReceipt(false, "invalid_revoke_request");
        }
    }

    public synchronized ConnectionHubStateStore.SessionProjection requireSession(String cookie) {
        ConnectionHubStateStore.SessionProjection projection = sessions.get(cookie);
        if (projection == null || projection.expiresAtMs <= clock.nowMs()) {
            throw new SecurityException("session_invalid_or_expired");
        }
        return projection;
    }

    /** Every physical WebSocket connection is a new Manifold transport epoch. */
    public synchronized ConnectionHubStateStore.SessionProjection replaceTransport(String cookie) {
        ConnectionHubStateStore.SessionProjection current = requireSession(cookie);
        prepareMutation("replace_transport");
        ConnectionHubAuthorityPort.Receipt receipt = authority.replaceTransport(
                randomRequestId("transport"),
                current.logicalSessionId,
                current.transportEpoch,
                clock.nowMs());
        if (!receipt.applied || receipt.transportEpoch != current.transportEpoch + 1) {
            persist();
            throw new SecurityException("transport_replacement_rejected");
        }
        ConnectionHubStateStore.SessionProjection next =
                new ConnectionHubStateStore.SessionProjection(
                        current.logicalSessionId,
                        receipt.transportEpoch,
                        current.expiresAtMs);
        sessions.put(cookie, next);
        persist();
        closeLogicalSession(current.logicalSessionId, "transport_replaced");
        return next;
    }

    /** Safe unauthenticated posture only; no surfaces, state, packages, or signers. */
    public synchronized JSONObject status() {
        JSONObject value = new JSONObject();
        try {
            value.put("$schema", ConnectionHubProtocol.STATUS_SCHEMA);
            value.put("listener_enabled", listenerEnabled);
            value.put("desired_connection_state", desiredRunning ? "running" : "stopped");
            value.put("pairing_available", pairingCode != null
                    && clock.nowMs() >= pairLockedUntilMs);
            value.put("status", lastStatus);
            value.put("transport_classification", "trusted_lan_experimental");
            value.put("confidentiality", ConnectionHubProtocol.CONFIDENTIALITY);
            value.put("production_eligible", ConnectionHubProtocol.PRODUCTION_ELIGIBLE);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        return value;
    }

    /** Trusted-clock expiry reconciliation; safe to call periodically. */
    public synchronized void expireNow() {
        long now = clock.nowMs();
        List<String> expiredCookies = new ArrayList<>();
        List<String> expiredLogicalSessions = new ArrayList<>();
        for (Map.Entry<String, ConnectionHubStateStore.SessionProjection> item : sessions.entrySet()) {
            if (item.getValue().expiresAtMs <= now) {
                expiredCookies.add(item.getKey());
                expiredLogicalSessions.add(item.getValue().logicalSessionId);
                removeSessionLeases(item.getValue().logicalSessionId);
            }
        }
        for (String cookie : expiredCookies) { sessions.remove(cookie); }
        List<String> expiredLeaseKeys = new ArrayList<>();
        for (Map.Entry<String, SurfaceLeaseProjection> item : surfaceLeases.entrySet()) {
            if (item.getValue().expiresAtMs <= now) { expiredLeaseKeys.add(item.getKey()); }
        }
        for (String key : expiredLeaseKeys) { surfaceLeases.remove(key); }
        prepareMutation("expire");
        ConnectionHubAuthorityPort.Receipt receipt = authority.expire(
                randomRequestId("expire"), now);
        persist();
        for (String logicalSessionId : expiredLogicalSessions) {
            closeLogicalSession(logicalSessionId, "session_expired");
        }
        if (sessions.isEmpty() && desiredRunning && pairingCode == null) {
            pairingCode = randomDigits(6);
        }
    }

    public ConnectionHubAuthorityPort.Receipt registerSurface(
            HubProviderIdentity identity,
            String providerInstanceId,
            String admissionUseRequestId,
            JSONObject registration,
            HubSurfaceRegistry.Endpoint endpoint) throws Exception {
        requireSchema(registration, ConnectionHubProtocol.SURFACE_REGISTRATION_SCHEMA);
        HubSurfaceDescriptor descriptor = parseDescriptor(registration, identity);
        prepareMutation("register_provider_and_surface");
        ConnectionHubAuthorityPort.Receipt providerReceipt = authority.registerProvider(
                randomRequestId("provider"),
                identity,
                providerInstanceId,
                admissionUseRequestId,
                clock.nowMs());
        if (!providerReceipt.applied) {
            persist();
            return providerReceipt;
        }
        ConnectionHubAuthorityPort.Receipt surfaceReceipt = authority.registerSurface(
                randomRequestId("surface"),
                providerInstanceId,
                descriptor,
                clock.nowMs());
        if (!surfaceReceipt.applied) {
            authority.unregisterProvider(
                    randomRequestId("provider-rollback"),
                    providerInstanceId,
                    "surface_registration_rejected",
                    clock.nowMs());
            persist();
            return surfaceReceipt;
        }
        persist();
        JSONObject state = registration.optJSONObject("state");
        try {
            registry.register(
                    descriptor,
                    state == null ? "{}" : state.toString(),
                    providerInstanceId,
                    endpoint);
        } catch (RuntimeException localFailure) {
            prepareMutation("rollback_local_provider_registration");
            authority.unregisterSurface(
                    randomRequestId("surface-rollback"),
                    providerInstanceId,
                    descriptor.surfaceId(),
                    clock.nowMs());
            authority.unregisterProvider(
                    randomRequestId("provider-rollback"),
                    providerInstanceId,
                    "local_registration_failed",
                    clock.nowMs());
            persist();
            throw localFailure;
        }
        return surfaceReceipt;
    }

    public boolean unregisterSurface(
            HubProviderIdentity identity,
            String surfaceId,
            String reason) {
        HubSurfaceRegistry.Entry owned = registry.requireOwnedEntry(identity, surfaceId);
        prepareMutation("unregister_surface");
        ConnectionHubAuthorityPort.Receipt receipt = authority.unregisterSurface(
                randomRequestId("surface-remove"),
                owned.providerInstanceId,
                surfaceId,
                clock.nowMs());
        persist();
        boolean removed = receipt.applied && registry.unregister(identity, surfaceId, reason);
        if (removed) { removeSurfaceLeases(surfaceId); }
        return removed;
    }

    public int unregisterProvider(
            HubProviderIdentity identity,
            String providerInstanceId,
            String reason) {
        int removed = 0;
        List<HubSurfaceRegistry.Entry> snapshot = registry.snapshot();
        for (HubSurfaceRegistry.Entry entry : snapshot) {
            if (entry.descriptor.providerIdentity().stableKey().equals(identity.stableKey())
                    && unregisterSurface(
                            identity,
                            entry.descriptor.surfaceId(),
                            reason)) {
                removed += 1;
            }
        }
        prepareMutation("unregister_provider");
        ConnectionHubAuthorityPort.Receipt providerReceipt = authority.unregisterProvider(
                randomRequestId("provider-remove"),
                providerInstanceId,
                reason,
                clock.nowMs());
        persist();
        if (!providerReceipt.applied && removed > 0) {
            throw new IllegalStateException("provider descendant cleanup applied without provider cleanup");
        }
        return removed;
    }

    public void updateSurfaceState(
            HubProviderIdentity identity,
            String surfaceId,
            JSONObject state) {
        requireBoundedFlatObject(state, "state");
        String nextStateJson = state.toString();
        if (registry.requireOwnedEntry(identity, surfaceId).stateJson.equals(nextStateJson)) {
            return;
        }
        synchronized (this) {
            if (!consumeRate(
                    surfaceStateRateWindows,
                    identity.stableKey() + "\n" + surfaceId,
                    ConnectionHubProtocol.MAX_SURFACE_STATE_UPDATES_PER_WINDOW,
                    ConnectionHubProtocol.SURFACE_STATE_RATE_WINDOW_MS,
                    clock.nowMs())) {
                throw new IllegalStateException("surface_state_rate_limited");
            }
        }
        registry.updateState(identity, surfaceId, nextStateJson);
    }

    public void handleCommand(
            String cookie,
            long socketTransportEpoch,
            JSONObject request,
            final CommandReceiptSink sink) {
        try {
            requireSchema(request, ConnectionHubProtocol.SURFACE_COMMAND_SCHEMA);
            requireExactKeys(request,
                    new String[] {"$schema", "type", "request_id", "surface_id", "command", "args"},
                    new String[0]);
            if (!"surface.command".equals(request.getString("type"))) {
                throw new IllegalArgumentException("invalid command type");
            }
            final String requestId = HubSurfaceDescriptor.requireToken(
                    request.getString("request_id"),
                    ConnectionHubProtocol.MAX_REQUEST_ID_CHARS,
                    "request_id");
            final String surfaceId = HubSurfaceDescriptor.requireToken(
                    request.getString("surface_id"),
                    ConnectionHubProtocol.MAX_SURFACE_ID_CHARS,
                    "surface_id");
            final String command = HubSurfaceDescriptor.requireToken(
                    request.getString("command"),
                    ConnectionHubProtocol.MAX_COMMAND_ID_CHARS,
                    "command");
            JSONObject args = request.optJSONObject("args");
            if (args == null) {
                args = new JSONObject();
            }
            requireBoundedFlatObject(args, "args");
            ConnectionHubStateStore.SessionProjection session;
            HubSurfaceRegistry.Entry entry;
            synchronized (this) {
                session = requireSession(cookie);
                if (session.transportEpoch != socketTransportEpoch) {
                    throw new SecurityException("stale_socket_transport_epoch");
                }
                if (!consumeRate(
                        commandRateWindows,
                        session.logicalSessionId,
                        ConnectionHubProtocol.MAX_COMMANDS_PER_SESSION_PER_WINDOW,
                        ConnectionHubProtocol.COMMAND_RATE_WINDOW_MS,
                        clock.nowMs())) {
                    sink.onReceipt(commandReceipt(
                            requestId, surfaceId, command, false,
                            "command_rate_limited", false, "{}"));
                    return;
                }
                entry = registry.require(surfaceId);
            }
            String leaseKey = leaseKey(session.logicalSessionId, surfaceId);
            long authorizationNow = clock.nowMs();
            SurfaceLeaseProjection lease;
            boolean expiredLeaseObserved = false;
            synchronized (this) {
                lease = surfaceLeases.get(leaseKey);
                if (lease != null && lease.expiresAtMs <= authorizationNow) {
                    surfaceLeases.remove(leaseKey);
                    lease = null;
                    expiredLeaseObserved = true;
                }
            }
            if (expiredLeaseObserved) { reconcileAuthorityExpiry(authorizationNow); }
            if (lease == null) {
                ConnectionHubAuthorityPort.Receipt leaseReceipt = acquireSurfaceLease(
                        leaseKey, session, surfaceId, authorizationNow);
                if (!leaseReceipt.applied) {
                    sink.onReceipt(commandReceipt(
                            requestId, surfaceId, command, false,
                            leaseReceipt.status, false, leaseReceipt.authorityReceiptJson));
                    return;
                }
                synchronized (this) { lease = surfaceLeases.get(leaseKey); }
            }
            String commandParamsSha256 = canonicalParamsSha256(args);
            ConnectionHubAuthorityPort.Receipt authorityReceipt = authorizeSurfaceCommand(
                    requestId, session, lease.leaseId, surfaceId, command,
                    commandParamsSha256, authorizationNow);
            if (!authorityReceipt.applied
                    && "rejected_surfaceleasenotactive".equals(authorityReceipt.status)) {
                synchronized (this) {
                    if (surfaceLeases.get(leaseKey) == lease) { surfaceLeases.remove(leaseKey); }
                }
                ConnectionHubAuthorityPort.Receipt reacquired = acquireSurfaceLease(
                        leaseKey, session, surfaceId, clock.nowMs());
                if (!reacquired.applied) {
                    sink.onReceipt(commandReceipt(
                            requestId, surfaceId, command, false,
                            reacquired.status, false, reacquired.authorityReceiptJson));
                    return;
                }
                authorityReceipt = authorizeSurfaceCommand(
                        requestId, session, reacquired.surfaceLeaseId, surfaceId, command,
                        commandParamsSha256, clock.nowMs());
            }
            if (!authorityReceipt.applied) {
                sink.onReceipt(commandReceipt(
                        requestId, surfaceId, command, false,
                        authorityReceipt.status, false, authorityReceipt.authorityReceiptJson));
                return;
            }
            final ConnectionHubAuthorityPort.Receipt authorizedReceipt = authorityReceipt;
            final HubSurfaceRegistry.Entry authorizedEntry = entry;
            final long authorizedEpoch = session.transportEpoch;
            final java.util.concurrent.atomic.AtomicBoolean effectCompleted =
                    new java.util.concurrent.atomic.AtomicBoolean();
            registry.dispatch(
                    new HubSurfaceRegistry.CommandDispatch(
                            requestId,
                            surfaceId,
                            command,
                            args.toString(),
                            authorizedReceipt.authorityReceiptJson,
                            authorizedEntry.providerInstanceId,
                            authorizedEpoch,
                            authorizedEntry.stateRevision,
                            sha256Utf8(authorizedReceipt.authorityReceiptJson)),
                    new HubSurfaceRegistry.CommandResultCallback() {
                        @Override
                        public void onResult(boolean applied, String status, String stateJson) {
                            if (!effectCompleted.compareAndSet(false, true)) return;
                            boolean observed = applied;
                            String finalStatus = status;
                            if (observed) {
                                try {
                                    JSONObject observedState = new JSONObject(stateJson);
                                    requireBoundedFlatObject(observedState, "provider_effect_state");
                                    registry.updateState(
                                            authorizedEntry.descriptor.providerIdentity(),
                                            surfaceId,
                                            observedState.toString());
                                    finalStatus = "provider_effect_observed";
                                } catch (Exception invalidEffect) {
                                    observed = false;
                                    finalStatus = "provider_effect_receipt_invalid";
                                }
                            } else if ("provider_effect_queued".equals(status)) {
                                finalStatus = "provider_effect_queued";
                            }
                            sink.onReceipt(commandReceipt(
                                    requestId, surfaceId, command, true,
                                    finalStatus, observed, authorizedReceipt.authorityReceiptJson));
                        }
                    });
        } catch (Exception error) {
            settlePreparedMutation();
            sink.onReceipt(commandReceipt(
                    request.optString("request_id", "request.invalid"),
                    request.optString("surface_id", "surface.invalid"),
                    request.optString("command", "command.invalid"),
                    false,
                    "invalid_surface_command",
                    false,
                    "{}"));
        }
    }

    private ConnectionHubAuthorityPort.Receipt acquireSurfaceLease(
            String leaseKey,
            ConnectionHubStateStore.SessionProjection session,
            String surfaceId,
            long nowMs) {
        prepareMutation("acquire_surface_lease");
        ConnectionHubAuthorityPort.Receipt receipt = authority.acquireSurfaceLease(
                randomRequestId("lease"),
                session.logicalSessionId,
                session.transportEpoch,
                surfaceId,
                nowMs);
        if (receipt.applied && receipt.surfaceLeaseId != null && receipt.expiresAtMs > nowMs) {
            synchronized (this) {
                surfaceLeases.put(leaseKey, new SurfaceLeaseProjection(
                        receipt.surfaceLeaseId, receipt.expiresAtMs));
            }
        } else if (receipt.applied) {
            persist();
            throw new SecurityException("authority_surface_lease_projection_invalid");
        }
        persist();
        return receipt;
    }

    private void reconcileAuthorityExpiry(long nowMs) {
        prepareMutation("expire_before_lease_reacquire");
        authority.expire(randomRequestId("expire-lease"), nowMs);
        persist();
    }

    private ConnectionHubAuthorityPort.Receipt authorizeSurfaceCommand(
            String requestId,
            ConnectionHubStateStore.SessionProjection session,
            String leaseId,
            String surfaceId,
            String command,
            String commandParamsSha256,
            long nowMs) {
        prepareMutation("authorize_surface_command");
        ConnectionHubAuthorityPort.Receipt receipt = authority.authorizeCommand(
                requestId,
                session.logicalSessionId,
                session.transportEpoch,
                leaseId,
                surfaceId,
                command,
                commandParamsSha256,
                nowMs);
        persist();
        return receipt;
    }

    public interface CommandReceiptSink { void onReceipt(JSONObject receipt); }

    public JSONObject snapshotEvent() {
        JSONObject event = eventBase(ConnectionHubProtocol.SURFACE_SNAPSHOT_SCHEMA, "surface_snapshot");
        try {
            event.put("surfaces", surfaceArray(registry.snapshot()));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        return event;
    }

    @Override
    public void onAvailable(long revision, HubSurfaceDescriptor descriptor, String stateJson) {
        JSONObject event = eventBase(ConnectionHubProtocol.SURFACE_AVAILABLE_SCHEMA, "surface_available");
        try {
            event.put("surface_revision", revision);
            event.put("surface", surfaceJson(descriptor, stateJson, 1));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        broadcast(event);
    }

    @Override
    public void onRemoved(long revision, String surfaceId, String reason) {
        JSONObject event = eventBase(ConnectionHubProtocol.SURFACE_REMOVED_SCHEMA, "surface_removed");
        try {
            event.put("surface_revision", revision);
            event.put("surface_id", surfaceId);
            event.put("reason", reason);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        broadcast(event);
    }

    @Override
    public void onState(long revision, String surfaceId, long stateRevision, String stateJson) {
        JSONObject event = eventBase(ConnectionHubProtocol.SURFACE_STATE_SCHEMA, "surface_state");
        try {
            event.put("surface_revision", revision);
            event.put("surface_id", surfaceId);
            event.put("state_revision", stateRevision);
            event.put("state", new JSONObject(stateJson));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        broadcast(event);
    }

    private synchronized void restore() {
        ConnectionHubStateStore.State state = store.load();
        durableGeneration = state.generation;
        desiredRunning = state.desiredRunning;
        sessions.putAll(state.sessionProjections);
        transportEpoch = desiredRunning ? randomHex(16) : "";
        if (!state.authorityEnvelope.isEmpty()) {
            ConnectionHubAuthorityPort.Receipt restored = authority.restoreOpaqueState(
                    state.authorityEnvelope,
                    clock.nowMs());
            if (!restored.applied) {
                desiredRunning = false;
                sessions.clear();
                lastStatus = "manifold_state_restore_rejected";
                persist();
                return;
            }
            prepareMutation("restart_reconcile");
            ConnectionHubAuthorityPort.Receipt reconciled = authority.reconcileAfterRestart(
                    randomRequestId("restart-reconcile"),
                    clock.nowMs());
            if (!reconciled.applied) {
                desiredRunning = false;
                sessions.clear();
                lastStatus = "manifold_restart_reconcile_rejected";
                persist();
                return;
            }
            persist();
            expireNow();
            rotateSessionTransports();
            persist();
        }
        pairingCode = desiredRunning && sessions.isEmpty() ? randomDigits(6) : null;
        lastStatus = !state.pendingOperation.isEmpty()
                ? "restart_reconciled_pending_" + state.pendingOperation
                : (desiredRunning ? "restart_pending_listener" : "disabled");
    }

    private void rotateSessionTransports() {
        Map<String, ConnectionHubStateStore.SessionProjection> existing =
                new LinkedHashMap<>(sessions);
        for (Map.Entry<String, ConnectionHubStateStore.SessionProjection> item : existing.entrySet()) {
            ConnectionHubStateStore.SessionProjection old = item.getValue();
            prepareMutation("restart_rotate_transport");
            ConnectionHubAuthorityPort.Receipt receipt = authority.replaceTransport(
                    randomRequestId("transport"),
                    old.logicalSessionId,
                    old.transportEpoch,
                    clock.nowMs());
            if (receipt.applied && receipt.transportEpoch == old.transportEpoch + 1) {
                sessions.put(item.getKey(), new ConnectionHubStateStore.SessionProjection(
                        old.logicalSessionId,
                        receipt.transportEpoch,
                        old.expiresAtMs));
            } else {
                sessions.remove(item.getKey());
            }
            persist();
        }
    }

    private synchronized void prepareMutation(String operation) {
        if (durabilityFailed) throw new IllegalStateException("durability_fail_stop");
        if (mutationPrepared) throw new IllegalStateException("nested_authority_mutation");
        durableGeneration += 1;
        try {
            store.save(new ConnectionHubStateStore.State(
                    desiredRunning,
                    authority.exportOpaqueState(),
                    sessions,
                    durableGeneration,
                    HubSurfaceDescriptor.requireToken(operation, 96, "pending_operation")));
            mutationPrepared = true;
        } catch (RuntimeException failure) {
            durableGeneration -= 1;
            throw failure;
        }
    }

    private synchronized void settlePreparedMutation() {
        if (mutationPrepared && !durabilityFailed) persist();
    }

    private synchronized void persist() {
        if (durabilityFailed) throw new IllegalStateException("durability_fail_stop");
        if (!mutationPrepared) durableGeneration += 1;
        try {
            store.save(new ConnectionHubStateStore.State(
                    desiredRunning,
                    authority.exportOpaqueState(),
                    sessions,
                    durableGeneration,
                    ""));
            mutationPrepared = false;
        } catch (RuntimeException failure) {
            durabilityFailed = true;
            listenerEnabled = false;
            desiredRunning = false;
            lastStatus = "durability_commit_failed_fail_stop";
            closeAllSessions("durability_commit_failed");
            throw new IllegalStateException("durability_commit_failed_fail_stop", failure);
        }
    }

    private void closeLogicalSession(String logicalSessionId, String reason) {
        List<EventSink> sinks;
        synchronized (this) { sinks = new ArrayList<>(eventSinks); }
        for (EventSink sink : sinks) { sink.closeLogicalSession(logicalSessionId, reason); }
    }

    private void closeAllSessions(String reason) {
        List<EventSink> sinks;
        synchronized (this) { sinks = new ArrayList<>(eventSinks); }
        for (EventSink sink : sinks) { sink.closeAllSessions(reason); }
    }

    private synchronized void removeSessionLeases(String logicalSessionId) {
        List<String> remove = new ArrayList<>();
        for (String key : surfaceLeases.keySet()) {
            if (key.startsWith(logicalSessionId + "|")) { remove.add(key); }
        }
        for (String key : remove) { surfaceLeases.remove(key); }
    }

    private synchronized void removeSurfaceLeases(String surfaceId) {
        List<String> remove = new ArrayList<>();
        for (String key : surfaceLeases.keySet()) {
            if (key.endsWith("|" + surfaceId)) { remove.add(key); }
        }
        for (String key : remove) { surfaceLeases.remove(key); }
    }

    private static String leaseKey(String logicalSessionId, String surfaceId) {
        return logicalSessionId + "|" + surfaceId;
    }

    private static String canonicalParamsSha256(JSONObject args) {
        try {
            List<String> keys = new ArrayList<>();
            JSONArray names = args.names();
            if (names != null) {
                for (int index = 0; index < names.length(); index += 1) {
                    keys.add(names.getString(index));
                }
            }
            java.util.Collections.sort(keys);
            StringBuilder canonical = new StringBuilder("{");
            for (int index = 0; index < keys.size(); index += 1) {
                if (index > 0) { canonical.append(','); }
                String key = keys.get(index);
                canonical.append(JSONObject.quote(key)).append(':')
                        .append(canonicalScalar(args.get(key)));
            }
            canonical.append('}');
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder("sha256:");
            for (byte item : digest) {
                output.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            }
            return output.toString();
        } catch (Exception error) { throw new IllegalArgumentException("args canonicalization failed", error); }
    }

    private static String sha256Utf8(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder("sha256:");
            for (byte item : digest) {
                output.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            }
            return output.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String canonicalScalar(Object value) {
        if (value == null || value == JSONObject.NULL) { return "null"; }
        if (value instanceof String) { return JSONObject.quote((String) value); }
        if (value instanceof Boolean) { return ((Boolean) value) ? "true" : "false"; }
        if (value instanceof Number) {
            String encoded = String.valueOf(value);
            if (encoded.equals("NaN") || encoded.equals("Infinity")
                    || encoded.equals("-Infinity")) {
                throw new IllegalArgumentException("non-finite command parameter");
            }
            return encoded;
        }
        throw new IllegalArgumentException("unsupported command parameter scalar");
    }

    private HubSurfaceDescriptor parseDescriptor(
            JSONObject registration,
            HubProviderIdentity identity) throws Exception {
        JSONArray commandsJson = registration.getJSONArray("commands");
        List<HubSurfaceDescriptor.Command> commands = new ArrayList<>();
        for (int index = 0; index < commandsJson.length(); index += 1) {
            JSONObject command = commandsJson.getJSONObject(index);
            commands.add(new HubSurfaceDescriptor.Command(
                    command.getString("command"),
                    command.getString("display_label"),
                    command.getString("required_controller_capability")));
            if (command.length() != 3) {
                throw new IllegalArgumentException("unknown command descriptor field");
            }
        }
        return new HubSurfaceDescriptor(
                registration.getInt("schema_version"),
                registration.getString("surface_id"),
                registration.getString("display_label"),
                registration.getString("description"),
                identity,
                commands,
                registration.getString("surface_contract_sha256"));
    }

    private JSONArray surfaceArray(List<HubSurfaceRegistry.Entry> entries) throws Exception {
        JSONArray output = new JSONArray();
        for (HubSurfaceRegistry.Entry entry : entries) {
            output.put(surfaceJson(
                    entry.descriptor,
                    entry.stateJson,
                    entry.stateRevision));
        }
        return output;
    }

    private JSONObject surfaceJson(
            HubSurfaceDescriptor descriptor,
            String stateJson,
            long stateRevision) throws Exception {
        JSONObject value = new JSONObject();
        value.put("schema_version", descriptor.schemaVersion());
        value.put("surface_id", descriptor.surfaceId());
        value.put("display_label", descriptor.displayLabel());
        value.put("description", descriptor.description());
        value.put("surface_contract_sha256", descriptor.surfaceContractSha256());
        value.put("provider_package", descriptor.providerIdentity().packageName());
        value.put("provider_signer_sha256", descriptor.providerIdentity().signerSha256());
        JSONArray commands = new JSONArray();
        for (HubSurfaceDescriptor.Command command : descriptor.commands()) {
            commands.put(new JSONObject()
                    .put("command", command.commandId())
                    .put("display_label", command.displayLabel())
                    .put("required_controller_capability", command.requiredControllerCapability()));
        }
        value.put("commands", commands);
        value.put("state", new JSONObject(stateJson));
        value.put("state_revision", stateRevision);
        return value;
    }

    private JSONObject pairReceipt(boolean accepted, String status, String cookie, long expiresAtMs) {
        JSONObject value = eventBase(ConnectionHubProtocol.PAIR_RECEIPT_SCHEMA, "pair_receipt");
        try {
            value.put("accepted", accepted);
            value.put("status", status);
            if (accepted) {
                value.put("session", cookie);
                value.put("expires_at_utc", Instant.ofEpochMilli(expiresAtMs).toString());
            }
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return value;
    }

    private JSONObject revokeReceipt(boolean applied, String status) {
        JSONObject value = eventBase(ConnectionHubProtocol.REVOKE_RECEIPT_SCHEMA, "revoke_receipt");
        try {
            value.put("applied", applied);
            value.put("status", status);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return value;
    }

    private JSONObject commandReceipt(
            String requestId,
            String surfaceId,
            String command,
            boolean accepted,
            String status,
            boolean providerApplied,
            String authorityReceiptJson) {
        JSONObject value = eventBase(ConnectionHubProtocol.COMMAND_RECEIPT_SCHEMA, "command_receipt");
        try {
            value.put("request_id", requestId);
            value.put("surface_id", surfaceId);
            value.put("command", command);
            value.put("accepted", accepted);
            value.put("provider_applied", providerApplied);
            value.put("status", status);
            value.put("authority_receipt", new JSONObject(authorityReceiptJson));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return value;
    }

    private JSONObject eventBase(String schema, String type) {
        JSONObject value = new JSONObject();
        try {
            value.put("$schema", schema);
            value.put("type", type);
            value.put("transport_epoch", 0);
            value.put("listener_instance_id", transportEpoch == null ? "" : transportEpoch);
            value.put("surface_revision", registry.revision());
            value.put("transport_classification", "trusted_lan_experimental");
            value.put("confidentiality", ConnectionHubProtocol.CONFIDENTIALITY);
            value.put("production_eligible", ConnectionHubProtocol.PRODUCTION_ELIGIBLE);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return value;
    }

    private void broadcast(JSONObject event) {
        List<EventSink> sinks;
        synchronized (this) { sinks = new ArrayList<>(eventSinks); }
        for (EventSink sink : sinks) { sink.broadcast(event); }
    }

    static void requireSchema(JSONObject value, String expected) throws Exception {
        if (!expected.equals(value.getString("$schema"))) {
            throw new IllegalArgumentException("schema mismatch");
        }
    }

    static void requireExactKeys(
            JSONObject value,
            String[] required,
            String[] optional) throws Exception {
        java.util.LinkedHashSet<String> allowed = new java.util.LinkedHashSet<>();
        java.util.Collections.addAll(allowed, required);
        java.util.Collections.addAll(allowed, optional);
        for (String key : required) {
            if (!value.has(key)) {
                throw new IllegalArgumentException("missing required field: " + key);
            }
        }
        java.util.Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("unknown field: " + key);
            }
        }
    }

    static void requireBoundedFlatObject(JSONObject value, String name) {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ConnectionHubProtocol.MAX_JSON_UTF8_BYTES
                || value.length() > ConnectionHubProtocol.MAX_OBJECT_KEYS) {
            throw new IllegalArgumentException(name + " is out of bounds");
        }
        JSONArray names = value.names();
        if (names == null) { return; }
        try {
            for (int index = 0; index < names.length(); index += 1) {
                Object item = value.get(names.getString(index));
                if (item instanceof JSONObject || item instanceof JSONArray) {
                    throw new IllegalArgumentException(name + " must be scalar-only");
                }
                if (item instanceof String
                        && ((String) item).length() > ConnectionHubProtocol.MAX_SCALAR_STRING_CHARS) {
                    throw new IllegalArgumentException(name + " string is too long");
                }
            }
        } catch (org.json.JSONException error) {
            throw new IllegalArgumentException(name + " is malformed", error);
        }
    }

    private static boolean consumeRate(
            Map<String, RateWindow> windows,
            String key,
            int maximum,
            long windowMs,
            long nowMs) {
        RateWindow window = windows.get(key);
        if (window == null || nowMs - window.startedAtMs >= windowMs) {
            windows.put(key, new RateWindow(nowMs, 1));
            return true;
        }
        if (window.count >= maximum) return false;
        window.count += 1;
        return true;
    }

    private static final class RateWindow {
        final long startedAtMs;
        int count;
        RateWindow(long startedAtMs, int count) {
            this.startedAtMs = startedAtMs;
            this.count = count;
        }
    }

    private static final class SurfaceLeaseProjection {
        final String leaseId;
        final long expiresAtMs;
        SurfaceLeaseProjection(String leaseId, long expiresAtMs) {
            this.leaseId = leaseId;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] left = expected == null ? new byte[0] : expected.getBytes(StandardCharsets.US_ASCII);
        byte[] right = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(left, right);
    }

    private String randomRequestId(String prefix) { return prefix + "." + randomHex(12); }
    private String randomHex(int bytes) {
        byte[] value = new byte[bytes]; random.nextBytes(value);
        StringBuilder output = new StringBuilder(bytes * 2);
        for (byte item : value) { output.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff)); }
        java.util.Arrays.fill(value, (byte) 0);
        return output.toString();
    }
    private String randomBase64Url(int bytes) {
        byte[] value = new byte[bytes]; random.nextBytes(value);
        String output = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        java.util.Arrays.fill(value, (byte) 0);
        return output;
    }
    private String randomDigits(int count) {
        StringBuilder output = new StringBuilder(count);
        for (int index = 0; index < count; index += 1) { output.append(random.nextInt(10)); }
        return output.toString();
    }
}
