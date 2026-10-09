package io.github.mesmerprism.rustyquest.native_renderer;

import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer;
import io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** One explicitly selected read-only provider. No issuer, command or listener authority. */
final class ExperimentSessionHubProvider {
    static final String SURFACE = "surface.experimenter.status";
    static final String LABEL = "Experimenter status";
    static final String DESCRIPTION = "Read-only application status observation; no physical readiness claim";
    static final String CAPABILITY = "capability.connection_hub.provider.register";
    static final int EVIDENCE = 6, ISSUE = 1, USE = 2, REGISTER = 20, UPDATE = 21, UNREGISTER = 22;
    interface Platform {
        long now();
        void bind(long generation);
        void link(long generation);
        void unlink(long generation);
        void unbind(long generation);
        boolean send(long generation, int what, Map<String, Object> data);
        void schedule(String key, long deadline, Runnable task);
        void cancel(String key);
    }
    private final Platform platform;
    private final ExperimentSessionStatusObservation projection;
    private final Supplier<ExperimentSessionPanelCoordinator.NativeStatusSnapshot> source;
    private State state;
    private long admissionRevision;
    private String token = "", authorizationCorrelation = "", registrationId = "", registration = "";
    private boolean started;
    private static final String POLL = "observation_poll";

    ExperimentSessionHubProvider(Platform platform, long processGeneration, String channel, long epoch,
            Supplier<ExperimentSessionPanelCoordinator.NativeStatusSnapshot> source) {
        this.platform = java.util.Objects.requireNonNull(platform);
        this.source = java.util.Objects.requireNonNull(source);
        this.projection = new ExperimentSessionStatusObservation(channel, epoch);
        this.state = ConnectionHubAdmissionSessionReducer.initial(processGeneration);
    }
    State state() { return state; }
    void start() { if (!started) { started = true; dispatch(Event.start(platform.now())); } }
    void close() { started = true; dispatch(Event.close(state.getBindingGeneration(), platform.now())); }
    void event(Event event) { dispatch(event); }
    private void dispatch(Event event) {
        boolean wasRegistered = state.isRegistered();
        PendingOperation old = state.getPending();
        Result result = ConnectionHubAdmissionSessionReducer.reduce(state, event);
        state = result.getState();
        if (old != null && old != state.getPending()) platform.cancel(old.getCorrelationId());
        if (!state.isRegistered()) platform.cancel(POLL);
        for (Effect effect : result.getEffects()) {
            try { execute(effect); }
            catch (Exception failure) { dispatch(Event.disconnected(effect.getBindingGeneration(), platform.now())); }
        }
        if (!wasRegistered && state.isRegistered()) schedulePoll();
    }
    private void execute(Effect e) throws Exception {
        switch (e.getType()) {
            case BIND_SERVICE: platform.bind(e.getBindingGeneration()); return;
            case LINK_DEATH: platform.link(e.getBindingGeneration()); return;
            case UNLINK_DEATH: platform.unlink(e.getBindingGeneration()); return;
            case UNBIND_SERVICE: platform.unbind(e.getBindingGeneration()); clear(); return;
            case SEND_UNREGISTER_SURFACE:
                Map<String, Object> cleanup = data(e);
                cleanup.put("surface_id", SURFACE);
                platform.send(e.getBindingGeneration(), UNREGISTER, cleanup); return;
            case MARKER: return;
            default: break;
        }
        Map<String, Object> fields = data(e);
        int what;
        switch (e.getOperation()) {
            case RUNTIME_EVIDENCE: what = EVIDENCE; break;
            case ISSUE_TOKEN:
                what = ISSUE; fields.put("request_id", e.getCorrelationId());
                fields.put("expected_authority_revision", admissionRevision);
                fields.put("capabilities", CAPABILITY); fields.put("token_ttl_ms", 30000L); break;
            case AUTHORIZE_USE:
                what = USE; fields.put("request_id", e.getCorrelationId());
                fields.put("expected_authority_revision", admissionRevision);
                fields.put("token_id", token); fields.put("capability_id", CAPABILITY); break;
            case REGISTER_SURFACE:
                what = REGISTER;
                if (!registrationId.equals(e.getRegistrationId())) {
                    registrationId = e.getRegistrationId(); registration = registration().toString();
                }
                fields.put("registration_id", registrationId);
                fields.put("registration_fingerprint_sha256", sha(registration));
                fields.put("authorization_correlation_id", authorizationCorrelation);
                fields.put("surface_registration_json", registration); break;
            default: throw new IllegalStateException("unsupported operation");
        }
        platform.schedule(e.getCorrelationId(), e.getDeadlineAtMs(), () ->
            dispatch(Event.deadline(e.getBindingGeneration(), e.getCorrelationId(), platform.now())));
        if (!platform.send(e.getBindingGeneration(), what, fields))
            dispatch(Event.disconnected(e.getBindingGeneration(), platform.now()));
    }
    void reply(long generation, int what, long session, String correlation, String epoch,
            String error, String json) {
        PendingOperation pending = state.getPending();
        if (generation != state.getBindingGeneration() || session != state.getSessionGeneration()
                || pending == null || !pending.getCorrelationId().equals(correlation)) return;
        if (platform.now() >= pending.getDeadlineAtMs()) {
            dispatch(Event.deadline(generation, correlation, platform.now())); return;
        }
        int expected = pending.getKind() == OperationKind.RUNTIME_EVIDENCE ? EVIDENCE
            : pending.getKind() == OperationKind.ISSUE_TOKEN ? ISSUE
            : pending.getKind() == OperationKind.AUTHORIZE_USE ? USE : REGISTER;
        if (what != expected) return;
        boolean applied = false;
        long decidedAt = platform.now();
        try {
            if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 65536
                    || epoch == null || !epoch.matches("[A-Za-z0-9_.-]{1,128}")
                    || (!state.getBrokerEpochId().isEmpty() && !state.getBrokerEpochId().equals(epoch)))
                throw new IllegalArgumentException("bounded same-epoch response required");
            JSONObject response = new JSONObject(json);
            if (error == null || error.isEmpty()) {
                long nextRevision = admissionRevision;
                String nextToken = token;
                if (pending.getKind() == OperationKind.RUNTIME_EVIDENCE) {
                    nextRevision = response.getJSONObject("runtime").getJSONObject("admission_snapshot")
                        .getLong("authority_revision");
                    applied = nextRevision > 0;
                } else if (pending.getKind() == OperationKind.REGISTER_SURFACE) {
                    applied = response.getBoolean("applied");
                } else {
                    JSONObject receipt = response.getJSONObject("receipt");
                    applied = receipt.getBoolean("applied");
                    if (applied) {
                        nextRevision = receipt.getLong("resulting_authority_revision");
                        if (nextRevision <= admissionRevision) throw new IllegalArgumentException("revision regression");
                        if (pending.getKind() == OperationKind.ISSUE_TOKEN) {
                            nextToken = receipt.getJSONObject("token").getString("token_id");
                            if (!nextToken.matches("[A-Za-z0-9_.-]{1,160}")) throw new IllegalArgumentException("token identity");
                        }
                    }
                }
                if (applied) {
                    decidedAt = platform.now();
                    if (decidedAt >= pending.getDeadlineAtMs()) {
                        dispatch(Event.deadline(generation, correlation, decidedAt)); return;
                    }
                    admissionRevision = nextRevision; token = nextToken;
                    if (pending.getKind() == OperationKind.AUTHORIZE_USE) authorizationCorrelation = correlation;
                }
            }
        } catch (Exception invalid) { applied = false; }
        dispatch(Event.reply(generation, correlation, applied, "provider_reply_rejected", epoch, decidedAt));
    }
    private Map<String, Object> data(Effect e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("correlation_id", e.getCorrelationId()); map.put("session_generation", e.getSessionGeneration());
        return map;
    }
    private JSONObject observationState() throws org.json.JSONException {
        String observation;
        try { observation = projection.project(source.get()); }
        catch (RuntimeException unavailable) { observation = projection.project(null); }
        return new JSONObject().put("experiment_status_observation", observation);
    }
    private JSONObject registration() throws org.json.JSONException {
        return new JSONObject().put("$schema", "rusty.quest.connection_hub.surface_registration.v1")
            .put("schema_version", 1).put("surface_id", SURFACE).put("display_label", LABEL)
            .put("description", DESCRIPTION).put("commands", new JSONArray())
            .put("surface_contract_sha256", contractSha()).put("state", observationState());
    }
    private void schedulePoll() {
        long generation = state.getBindingGeneration();
        platform.schedule(POLL, platform.now() + 1000L, () -> {
            if (!state.isRegistered() || generation != state.getBindingGeneration()) return;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("correlation_id", "state.s" + state.getSessionGeneration());
            data.put("session_generation", state.getSessionGeneration()); data.put("surface_id", SURFACE);
            try { data.put("state_json", observationState().toString()); }
            catch (Exception failure) { dispatch(Event.disconnected(generation, platform.now())); return; }
            if (!platform.send(generation, UPDATE, data)) dispatch(Event.disconnected(generation, platform.now()));
            else schedulePoll();
        });
    }
    private void clear() { admissionRevision = 0; token = ""; authorizationCorrelation = ""; registrationId = ""; registration = ""; }
    static String contractSha() { return sha("v1\n" + SURFACE + "\n" + LABEL + "\n" + DESCRIPTION + "\n"); }
    private static String sha(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("sha256:");
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
