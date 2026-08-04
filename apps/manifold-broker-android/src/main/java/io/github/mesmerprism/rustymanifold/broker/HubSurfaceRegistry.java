package io.github.mesmerprism.rustymanifold.broker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Process-local provider registry. It owns no admission or command decision. */
public final class HubSurfaceRegistry {
    public interface Endpoint {
        void dispatch(CommandDispatch dispatch, CommandResultCallback callback);
    }

    public interface CommandResultCallback {
        void onResult(boolean applied, String status, String stateJson);
    }

    public interface Listener {
        void onAvailable(long revision, HubSurfaceDescriptor descriptor, String stateJson);
        void onRemoved(long revision, String surfaceId, String reason);
        void onState(long revision, String surfaceId, long stateRevision, String stateJson);
    }

    public static final class CommandDispatch {
        public final String requestId;
        public final String surfaceId;
        public final String command;
        public final String argsJson;
        public final String authorityReceiptJson;
        public final String providerInstanceId;
        public final long transportEpoch;
        public final long authorizedStateRevision;
        public final String authorityReceiptSha256;

        public CommandDispatch(
                String requestId,
                String surfaceId,
                String command,
                String argsJson,
                String authorityReceiptJson,
                String providerInstanceId,
                long transportEpoch,
                long authorizedStateRevision,
                String authorityReceiptSha256) {
            this.requestId = requestId;
            this.surfaceId = surfaceId;
            this.command = command;
            this.argsJson = argsJson;
            this.authorityReceiptJson = authorityReceiptJson;
            this.providerInstanceId = providerInstanceId;
            this.transportEpoch = transportEpoch;
            this.authorizedStateRevision = authorizedStateRevision;
            this.authorityReceiptSha256 = authorityReceiptSha256;
        }
    }

    public static final class Entry {
        public final HubSurfaceDescriptor descriptor;
        public final String stateJson;
        public final long stateRevision;
        public final String providerInstanceId;
        private final Endpoint endpoint;

        Entry(HubSurfaceDescriptor descriptor, String stateJson, long stateRevision,
                String providerInstanceId, Endpoint endpoint) {
            this.descriptor = descriptor;
            this.stateJson = stateJson;
            this.stateRevision = stateRevision;
            this.providerInstanceId = providerInstanceId;
            this.endpoint = endpoint;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private long revision;

    public synchronized long revision() { return revision; }

    public synchronized void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public synchronized List<Entry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    public synchronized long register(
            HubSurfaceDescriptor descriptor,
            String stateJson,
            String providerInstanceId,
            Endpoint endpoint) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(endpoint, "endpoint");
        requireBoundedJson(stateJson, "state");
        Entry existing = entries.get(descriptor.surfaceId());
        if (existing == null && entries.size() >= ConnectionHubProtocol.MAX_SURFACES) {
            throw new IllegalStateException("surface registry is full");
        }
        if (existing != null
                && !existing.descriptor.providerIdentity().stableKey().equals(
                        descriptor.providerIdentity().stableKey())) {
            throw new SecurityException("surface id is already owned by another provider identity");
        }
        String checkedProviderInstance = HubSurfaceDescriptor.requireToken(
                providerInstanceId, 96, "provider_instance_id");
        Entry next = new Entry(descriptor, stateJson, 1, checkedProviderInstance, endpoint);
        entries.put(descriptor.surfaceId(), next);
        revision += 1;
        for (Listener listener : listeners) {
            listener.onAvailable(revision, descriptor, stateJson);
        }
        return revision;
    }

    public synchronized boolean unregister(
            HubProviderIdentity providerIdentity,
            String surfaceId,
            String reason) {
        Entry existing = entries.get(surfaceId);
        if (existing == null) {
            return false;
        }
        if (!existing.descriptor.providerIdentity().stableKey().equals(providerIdentity.stableKey())) {
            throw new SecurityException("provider cannot remove another identity's surface");
        }
        entries.remove(surfaceId);
        revision += 1;
        for (Listener listener : listeners) {
            listener.onRemoved(revision, surfaceId, reason);
        }
        return true;
    }

    public synchronized int unregisterProvider(HubProviderIdentity identity, String reason) {
        List<String> owned = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.descriptor.providerIdentity().stableKey().equals(identity.stableKey())) {
                owned.add(entry.descriptor.surfaceId());
            }
        }
        for (String surfaceId : owned) {
            unregister(identity, surfaceId, reason);
        }
        return owned.size();
    }

    public synchronized long updateState(
            HubProviderIdentity identity,
            String surfaceId,
            String stateJson) {
        requireBoundedJson(stateJson, "state");
        Entry existing = requireOwned(identity, surfaceId);
        if (existing.stateJson.equals(stateJson)) {
            return revision;
        }
        Entry next = new Entry(
                existing.descriptor,
                stateJson,
                existing.stateRevision + 1,
                existing.providerInstanceId,
                existing.endpoint);
        entries.put(surfaceId, next);
        revision += 1;
        for (Listener listener : listeners) {
            listener.onState(revision, surfaceId, next.stateRevision, stateJson);
        }
        return revision;
    }

    public void dispatch(CommandDispatch dispatch, CommandResultCallback callback) {
        Entry entry;
        synchronized (this) {
            entry = entries.get(dispatch.surfaceId);
            if (entry == null) {
                callback.onResult(false, "surface_not_available", "{}");
                return;
            }
            if (!entry.descriptor.permits(dispatch.command)) {
                callback.onResult(false, "command_not_registered", entry.stateJson);
                return;
            }
        }
        entry.endpoint.dispatch(dispatch, callback);
    }

    public synchronized Entry require(String surfaceId) {
        Entry entry = entries.get(surfaceId);
        if (entry == null) {
            throw new IllegalArgumentException("surface_not_available");
        }
        return entry;
    }

    public synchronized Entry requireOwnedEntry(
            HubProviderIdentity identity,
            String surfaceId) {
        return requireOwned(identity, surfaceId);
    }

    private Entry requireOwned(HubProviderIdentity identity, String surfaceId) {
        Entry entry = require(surfaceId);
        if (!entry.descriptor.providerIdentity().stableKey().equals(identity.stableKey())) {
            throw new SecurityException("provider identity substitution rejected");
        }
        return entry;
    }

    static void requireBoundedJson(String value, String name) {
        Objects.requireNonNull(value, name);
        int bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes < 2 || bytes > ConnectionHubProtocol.MAX_JSON_UTF8_BYTES) {
            throw new IllegalArgumentException(name + " JSON is out of bounds");
        }
    }
}
