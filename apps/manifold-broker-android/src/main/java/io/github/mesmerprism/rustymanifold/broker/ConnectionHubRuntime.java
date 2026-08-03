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
    public interface EventSink {
        void broadcast(JSONObject event);
        void closeLogicalSession(String logicalSessionId, String reason);
        void closeAllSessions(String reason);
    }

    private final ConnectionHubAuthorityPort authority;
    private final ConnectionHubStateStore store;
    private final HubSurfaceRegistry registry;
    private final SecureRandom random;
    private final Map<String, ConnectionHubStateStore.SessionProjection> sessions =
            new LinkedHashMap<>();
    private final List<EventSink> eventSinks = new ArrayList<>();
    private final Map<String, String> surfaceLeases = new LinkedHashMap<>();
    private boolean desiredRunning;
    private boolean listenerEnabled;
    private String pairingCode;
    private String transportEpoch;
    private String lastStatus = "disabled";
    private long pairAttemptWindowStartedMs;
    private int pairAttempts;
    private long pairLockedUntilMs;

    public ConnectionHubRuntime(
            ConnectionHubAuthorityPort authority,
            ConnectionHubStateStore store,
            HubSurfaceRegistry registry,
            SecureRandom random) {
        this.authority = authority;
        this.store = store;
        this.registry = registry;
        this.random = random;
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
        ConnectionHubAuthorityPort.Receipt receipt = authority.forgetAll(
                randomRequestId("forget"),
                "wearer_forget",
                System.currentTimeMillis());
        if (receipt.applied) {
            closeAllSessions("wearer_forget");
            sessions.clear();
            surfaceLeases.clear();
            pairingCode = desiredRunning ? randomDigits(6) : null;
            persist();
        }
        lastStatus = receipt.status;
        return receipt;
    }

    public synchronized JSONObject pair(JSONObject request, String wearerEvidenceId) {
        try {
            long now = System.currentTimeMillis();
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
            ConnectionHubAuthorityPort.Receipt authorityReceipt = authority.trustAndOpenSession(
                    randomRequestId("pair"),
                    controllerIdentitySha256,
                    wearerEvidenceId,
                    System.currentTimeMillis());
            if (!authorityReceipt.applied
                    || authorityReceipt.logicalSessionId == null
                    || authorityReceipt.transportEpoch < 1) {
                return pairReceipt(false, authorityReceipt.status, null, 0);
            }
            String cookie = randomBase64Url(32);
            long expiresAtMs = authorityReceipt.expiresAtMs;
            if (expiresAtMs <= now) {
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
            ConnectionHubAuthorityPort.Receipt authorityReceipt = authority.revokeSession(
                    randomRequestId("revoke"),
                    session.logicalSessionId,
                    request.optString("reason", "user_request"),
                    System.currentTimeMillis());
            long revokedTransportEpoch = session.transportEpoch;
            if (authorityReceipt.applied) {
                closeLogicalSession(session.logicalSessionId, "session_revoked");
                sessions.remove(cookie);
                removeSessionLeases(session.logicalSessionId);
                if (sessions.isEmpty() && desiredRunning) {
                    pairingCode = randomDigits(6);
                }
                persist();
            }
            return revokeReceipt(authorityReceipt.applied, authorityReceipt.status)
                    .put("transport_epoch", revokedTransportEpoch)
                    .put("authority_receipt", new JSONObject(authorityReceipt.authorityReceiptJson));
        } catch (Exception error) {
            return revokeReceipt(false, "invalid_revoke_request");
        }
    }

    public synchronized ConnectionHubStateStore.SessionProjection requireSession(String cookie) {
        ConnectionHubStateStore.SessionProjection projection = sessions.get(cookie);
        if (projection == null || projection.expiresAtMs <= System.currentTimeMillis()) {
            throw new SecurityException("session_invalid_or_expired");
        }
        return projection;
    }

    /** Every physical WebSocket connection is a new Manifold transport epoch. */
    public synchronized ConnectionHubStateStore.SessionProjection replaceTransport(String cookie) {
        ConnectionHubStateStore.SessionProjection current = requireSession(cookie);
        ConnectionHubAuthorityPort.Receipt receipt = authority.replaceTransport(
                randomRequestId("transport"),
                current.logicalSessionId,
                current.transportEpoch,
                System.currentTimeMillis());
        if (!receipt.applied || receipt.transportEpoch != current.transportEpoch + 1) {
            throw new SecurityException("transport_replacement_rejected");
        }
        closeLogicalSession(current.logicalSessionId, "transport_replaced");
        ConnectionHubStateStore.SessionProjection next =
                new ConnectionHubStateStore.SessionProjection(
                        current.logicalSessionId,
                        receipt.transportEpoch,
                        current.expiresAtMs);
        sessions.put(cookie, next);
        persist();
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
                    && System.currentTimeMillis() >= pairLockedUntilMs);
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
        long now = System.currentTimeMillis();
        List<String> expiredCookies = new ArrayList<>();
        for (Map.Entry<String, ConnectionHubStateStore.SessionProjection> item : sessions.entrySet()) {
            if (item.getValue().expiresAtMs <= now) {
                closeLogicalSession(item.getValue().logicalSessionId, "session_expired");
                expiredCookies.add(item.getKey());
                removeSessionLeases(item.getValue().logicalSessionId);
            }
        }
        for (String cookie : expiredCookies) { sessions.remove(cookie); }
        ConnectionHubAuthorityPort.Receipt receipt = authority.expire(
                randomRequestId("expire"), now);
        if (receipt.applied || !expiredCookies.isEmpty()) { persist(); }
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
        ConnectionHubAuthorityPort.Receipt providerReceipt = authority.registerProvider(
                randomRequestId("provider"),
                identity,
                providerInstanceId,
                admissionUseRequestId,
                System.currentTimeMillis());
        if (!providerReceipt.applied) {
            return providerReceipt;
        }
        ConnectionHubAuthorityPort.Receipt surfaceReceipt = authority.registerSurface(
                randomRequestId("surface"),
                providerInstanceId,
                descriptor,
                System.currentTimeMillis());
        if (!surfaceReceipt.applied) {
            authority.unregisterProvider(
                    randomRequestId("provider-rollback"),
                    providerInstanceId,
                    "surface_registration_rejected",
                    System.currentTimeMillis());
            persist();
            return surfaceReceipt;
        }
        JSONObject state = registration.optJSONObject("state");
        try {
            registry.register(
                    descriptor,
                    state == null ? "{}" : state.toString(),
                    providerInstanceId,
                    endpoint);
        } catch (RuntimeException localFailure) {
            authority.unregisterSurface(
                    randomRequestId("surface-rollback"),
                    providerInstanceId,
                    descriptor.surfaceId(),
                    System.currentTimeMillis());
            authority.unregisterProvider(
                    randomRequestId("provider-rollback"),
                    providerInstanceId,
                    "local_registration_failed",
                    System.currentTimeMillis());
            persist();
            throw localFailure;
        }
        persist();
        return surfaceReceipt;
    }

    public boolean unregisterSurface(
            HubProviderIdentity identity,
            String surfaceId,
            String reason) {
        HubSurfaceRegistry.Entry owned = registry.requireOwnedEntry(identity, surfaceId);
        ConnectionHubAuthorityPort.Receipt receipt = authority.unregisterSurface(
                randomRequestId("surface-remove"),
                owned.providerInstanceId,
                surfaceId,
                System.currentTimeMillis());
        boolean removed = receipt.applied && registry.unregister(identity, surfaceId, reason);
        if (removed) { removeSurfaceLeases(surfaceId); }
        persist();
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
        ConnectionHubAuthorityPort.Receipt providerReceipt = authority.unregisterProvider(
                randomRequestId("provider-remove"),
                providerInstanceId,
                reason,
                System.currentTimeMillis());
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
        registry.updateState(identity, surfaceId, state.toString());
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
                entry = registry.require(surfaceId);
            }
            String leaseKey = leaseKey(session.logicalSessionId, surfaceId);
            String leaseId;
            synchronized (this) { leaseId = surfaceLeases.get(leaseKey); }
            if (leaseId == null) {
                ConnectionHubAuthorityPort.Receipt leaseReceipt = authority.acquireSurfaceLease(
                        randomRequestId("lease"),
                        session.logicalSessionId,
                        session.transportEpoch,
                        surfaceId,
                        System.currentTimeMillis());
                if (!leaseReceipt.applied || leaseReceipt.surfaceLeaseId == null) {
                    sink.onReceipt(commandReceipt(
                            requestId, surfaceId, command, false,
                            leaseReceipt.status, false, leaseReceipt.authorityReceiptJson));
                    persist();
                    return;
                }
                leaseId = leaseReceipt.surfaceLeaseId;
                synchronized (this) { surfaceLeases.put(leaseKey, leaseId); }
                persist();
            }
            String commandParamsSha256 = canonicalParamsSha256(args);
            ConnectionHubAuthorityPort.Receipt authorityReceipt = authority.authorizeCommand(
                    requestId,
                    session.logicalSessionId,
                    session.transportEpoch,
                    leaseId,
                    surfaceId,
                    command,
                    commandParamsSha256,
                    System.currentTimeMillis());
            persist();
            if (!authorityReceipt.applied) {
                sink.onReceipt(commandReceipt(
                        requestId, surfaceId, command, false,
                        authorityReceipt.status, false, authorityReceipt.authorityReceiptJson));
                return;
            }
            registry.dispatch(
                    new HubSurfaceRegistry.CommandDispatch(
                            requestId,
                            surfaceId,
                            command,
                            args.toString(),
                            authorityReceipt.authorityReceiptJson),
                    new HubSurfaceRegistry.CommandResultCallback() {
                        @Override
                        public void onResult(boolean applied, String status, String stateJson) {
                            sink.onReceipt(commandReceipt(
                                    requestId, surfaceId, command, true,
                                    status, applied, authorityReceipt.authorityReceiptJson));
                        }
                    });
        } catch (Exception error) {
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
        desiredRunning = state.desiredRunning;
        sessions.putAll(state.sessionProjections);
        transportEpoch = desiredRunning ? randomHex(16) : "";
        if (!state.authorityEnvelope.isEmpty()) {
            ConnectionHubAuthorityPort.Receipt restored = authority.restoreOpaqueState(
                    state.authorityEnvelope,
                    System.currentTimeMillis());
            if (!restored.applied) {
                desiredRunning = false;
                sessions.clear();
                lastStatus = "manifold_state_restore_rejected";
                persist();
                return;
            }
            authority.reconcileAfterRestart(
                    randomRequestId("restart-reconcile"),
                    System.currentTimeMillis());
            expireNow();
            rotateSessionTransports();
            persist();
        }
        pairingCode = desiredRunning && sessions.isEmpty() ? randomDigits(6) : null;
        lastStatus = desiredRunning ? "restart_pending_listener" : "disabled";
    }

    private void rotateSessionTransports() {
        Map<String, ConnectionHubStateStore.SessionProjection> rotated = new LinkedHashMap<>();
        for (Map.Entry<String, ConnectionHubStateStore.SessionProjection> item : sessions.entrySet()) {
            ConnectionHubStateStore.SessionProjection old = item.getValue();
            ConnectionHubAuthorityPort.Receipt receipt = authority.replaceTransport(
                    randomRequestId("transport"),
                    old.logicalSessionId,
                    old.transportEpoch,
                    System.currentTimeMillis());
            if (receipt.applied && receipt.transportEpoch == old.transportEpoch + 1) {
                rotated.put(item.getKey(), new ConnectionHubStateStore.SessionProjection(
                        old.logicalSessionId,
                        receipt.transportEpoch,
                        old.expiresAtMs));
            }
        }
        sessions.clear();
        sessions.putAll(rotated);
    }

    private synchronized void persist() {
        store.save(new ConnectionHubStateStore.State(
                desiredRunning,
                authority.exportOpaqueState(),
                sessions));
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
