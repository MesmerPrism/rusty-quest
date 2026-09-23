package io.github.mesmerprism.rustyquest.media;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Executes only providers declared by the exact packaged product binding. */
public final class PackagedAndroidMediaOwnerRegistry implements AndroidMediaOwnerRegistry, AutoCloseable {
    private static final int MAX_IN_FLIGHT_EXECUTIONS = 256;
    private final long generation;
    private final MediaProductBinding binding;
    private final CancellationHandle cancellation;
    private final ConcurrentHashMap<String, Execution> executions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> issuedReceipts = new ConcurrentHashMap<>();
    private final Semaphore executionSlots = new Semaphore(MAX_IN_FLIGHT_EXECUTIONS);

    public PackagedAndroidMediaOwnerRegistry(long generation, MediaProductBinding binding) {
        if (generation <= 0 || binding == null) throw new IllegalArgumentException("registry binding");
        this.generation = generation;
        this.binding = binding;
        this.cancellation = new CancellationHandle(generation);
    }

    @Override public String execute(String ticketJson, boolean compensate) {
        MediaOwnerAction action = MediaOwnerAction.parse(ticketJson);
        cancellation.requireCurrent(action.executorGeneration());
        if (action.executorGeneration() != generation) {
            throw new IllegalStateException("stale registry generation");
        }
        MediaOwnerProvider provider = binding.provider(action);
        if (provider == null) throw new IllegalStateException("undeclared media provider binding");
        String key = action.executionKey(compensate);
        Execution mine = new Execution();
        Execution existing = executions.putIfAbsent(key, mine);
        if (existing != null) {
            String completed = existing.completed;
            if (completed != null) return completed;
            throw new IllegalStateException("ProviderBusy");
        }
        if (!executionSlots.tryAcquire()) {
            executions.remove(key, mine);
            throw new IllegalStateException("media execution registry full");
        }
        try {
            // No registry/provider monitor is held across this platform callback.
            MediaProviderReadback readback = compensate
                    ? provider.compensate(action, cancellation)
                    : provider.execute(action, cancellation);
            cancellation.requireCurrent(action.executorGeneration());
            if (readback == null || readback.action() != action) {
                throw new IllegalStateException("provider returned foreign readback");
            }
            String json = readback.toJson();
            String old = issuedReceipts.putIfAbsent(readback.receiptId(), json);
            if (old != null && !old.equals(json)) throw new IllegalStateException("receipt collision");
            mine.completed = json;
            return json;
        } catch (RuntimeException failure) {
            if (executions.remove(key, mine)) executionSlots.release();
            throw failure;
        } catch (Exception failure) {
            if (executions.remove(key, mine)) executionSlots.release();
            throw new IllegalStateException("media provider execution failed", failure);
        }
    }

    @Override public boolean verify(String ticketJson, String readbackJson) {
        if (readbackJson == null || readbackJson.length() > 64 * 1024) return false;
        try {
            MediaOwnerAction action = MediaOwnerAction.parse(ticketJson);
            JSONObject readback = new JSONObject(readbackJson);
            if (!MediaProviderReadback.SCHEMA.equals(readback.optString("$schema", ""))
                    || action.executorGeneration() != generation || !action.matches(readback)) return false;
            String receiptId = readback.optString("receipt_id", "");
            String issued = issuedReceipts.get(receiptId);
            if (issued == null || !MessageDigest.isEqual(
                    issued.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    readbackJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))) return false;
            MediaOwnerProvider provider = binding.provider(action);
            if (provider == null) return false;
            // Provider snapshot is queried without holding registry state.
            MediaRuntimeSnapshot snapshot = provider.snapshot();
            cancellation.requireCurrent(generation);
            boolean current = snapshot.generation() == generation
                    && snapshot.revision() == readback.optLong("provider_state_revision", -1L)
                    && snapshot.state().equals(readback.optString("observed_state", ""))
                    && !"unbound".equals(snapshot.providerHandleId())
                    && snapshot.providerHandleId().equals(readback.optString("provider_handle_id", ""));
            if (current) {
                issuedReceipts.remove(receiptId, issued);
                if (executions.remove(action.executionKey(false)) != null) executionSlots.release();
                if (executions.remove(action.executionKey(true)) != null) executionSlots.release();
            }
            return current;
        } catch (Exception invalid) {
            return false;
        }
    }

    @Override public void close() { cancellation.cancel(); }
    private static final class Execution { volatile String completed; }
}
