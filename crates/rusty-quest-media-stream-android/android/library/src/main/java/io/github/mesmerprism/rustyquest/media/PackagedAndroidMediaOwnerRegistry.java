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
    private final Object lifecycleGate = new Object();
    private boolean noMediaClosed;
    private boolean anyOwnerEffectAttempted;

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
        synchronized (lifecycleGate) {
            if (noMediaClosed) throw new IllegalStateException("no-media registry closed");
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
            // Set before entering a provider: an empty registry after a failed or
            // verified effect is never evidence that no owner effect was tried.
            anyOwnerEffectAttempted = true;
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
        return verifyAndReadEvidence(ticketJson, readbackJson) != null;
    }

    @Override public String verifyAndReadEvidence(String ticketJson, String readbackJson) {
        if (readbackJson == null || readbackJson.length() > 64 * 1024
                || readbackJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64 * 1024) {
            return null;
        }
        try {
            MediaOwnerAction action = MediaOwnerAction.parse(ticketJson);
            JSONObject readback = new JSONObject(readbackJson);
            if (!MediaProviderReadback.SCHEMA.equals(readback.optString("$schema", ""))
                    || action.executorGeneration() != generation || !action.matches(readback)) return null;
            String receiptId = readback.optString("receipt_id", "");
            String issued = issuedReceipts.get(receiptId);
            if (issued == null || !MessageDigest.isEqual(
                    issued.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    readbackJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))) return null;
            MediaOwnerProvider provider = binding.provider(action);
            if (provider == null) return null;
            // Provider snapshot is queried without holding registry state.
            MediaRuntimeSnapshot snapshot = provider.snapshot();
            cancellation.requireCurrent(generation);
            boolean current = snapshot.generation() == generation
                    && snapshot.revision() == readback.optLong("provider_state_revision", -1L)
                    && snapshot.state().equals(readback.optString("observed_state", ""))
                    && !"unbound".equals(snapshot.providerHandleId())
                    && snapshot.providerHandleId().equals(readback.optString("provider_handle_id", ""));
            if (!current) return null;
            boolean stopping = "stop".equals(action.actionKind())
                    || "cleanup".equals(action.actionKind())
                    || "stopped".equals(snapshot.state())
                    || "cleaned".equals(snapshot.state());
            if (stopping && !snapshot.terminal()) return null;
            JSONObject evidence = new JSONObject();
            evidence.put("$schema", "rusty.quest.android.media.verified_owner_effect.v1");
            evidence.put("receipt_id", receiptId);
            evidence.put("readback_sha256", sha256(readbackJson));
            evidence.put("executor_generation", generation);
            evidence.put("provider_state_revision", snapshot.revision());
            evidence.put("observed_state", snapshot.state());
            evidence.put("terminal", snapshot.terminal());
            evidence.put("provider_handle_id", snapshot.providerHandleId());
            evidence.put("detail_sha256", sha256(snapshot.detail()));
            // One caller wins consumption. A remote dispatcher caches this exact
            // evidence with its signed response; a retry must not re-execute it.
            if (!issuedReceipts.remove(receiptId, issued)) return null;
            if (executions.remove(action.executionKey(false)) != null) executionSlots.release();
            if (executions.remove(action.executionKey(true)) != null) executionSlots.release();
            return evidence.toString();
        } catch (Exception invalid) {
            return null;
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder("sha256:");
        for (byte item : digest) {
            hex.append(Character.forDigit((item >>> 4) & 15, 16));
            hex.append(Character.forDigit(item & 15, 16));
        }
        return hex.toString();
    }

    @Override public void close() { cancellation.cancel(); }
    /** One-way no-effect barrier. A provider callback cannot start after this succeeds. */
    public void closeIfNeverAttempted() {
        synchronized (lifecycleGate) {
            if (anyOwnerEffectAttempted || !executions.isEmpty() || !issuedReceipts.isEmpty()) {
                throw new IllegalStateException("owner effect was attempted or remains in flight");
            }
            noMediaClosed = true;
            cancellation.cancel();
        }
    }
    private static final class Execution { volatile String completed; }
}
