package io.github.mesmerprism.rustyquest.native_renderer;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/** Optional outbound transport only. The endpoint retains all command/auth authority. */
public final class ExperimentSessionRelayClient implements AutoCloseable {
    public interface Endpoint {
        JSONObject identity() throws Exception;
        JSONObject exchange(JSONObject request) throws Exception;
    }
    public static final class Config {
        final String origin, session, token;
        final long expiresAtMs;
        /** expiresAtMs is the operator-provisioned host scope expiry, never a locally minted extension. */
        public Config(String origin, String approvedOrigin, String session, String token, long expiresAtMs) {
            URI u = URI.create(origin);
            if (!origin.equals(approvedOrigin) || !"https".equals(u.getScheme()) || u.getHost() == null
                    || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null
                    || !(u.getPath().isEmpty()) || (u.getPort() != -1 && u.getPort() < 1))
                throw new IllegalArgumentException("approved_https_origin_required");
            if (!session.matches("[A-Za-z0-9_-]{16,64}") || !token.matches("[A-Za-z0-9_-]{32,128}"))
                throw new IllegalArgumentException("invalid_relay_scope");
            long remaining = expiresAtMs - System.currentTimeMillis();
            if (remaining <= 0 || remaining > 3600000L) throw new IllegalArgumentException("relay_expiry_out_of_bounds");
            this.origin = origin; this.session = session; this.token = token; this.expiresAtMs = expiresAtMs;
        }
    }
    private final Config config;
    private final Endpoint endpoint;
    private final long deadlineNanos;
    private volatile boolean closed;
    private volatile HttpsURLConnection connection;
    private volatile String state = "inactive";
    private Thread worker;
    public ExperimentSessionRelayClient(Config config, Endpoint endpoint) {
        if (config == null || endpoint == null) throw new IllegalArgumentException("missing_relay_binding");
        this.config = config; this.endpoint = endpoint;
        this.deadlineNanos = System.nanoTime() + Math.max(0L, config.expiresAtMs - System.currentTimeMillis()) * 1000000L;
    }
    public synchronized void start() {
        if (closed || worker != null) return;
        worker = new Thread(new Runnable() { @Override public void run() { ExperimentSessionRelayClient.this.run(); } }, "experiment-control-relay");
        worker.setDaemon(true); worker.start();
    }
    private void run() {
        while (!closed && System.currentTimeMillis() < config.expiresAtMs && System.nanoTime() < deadlineNanos) {
            try {
                state = "connecting";
                JSONObject poll = new JSONObject(); poll.put("identity", endpoint.identity());
                JSONObject job = post("poll", poll);
                if (closed) break;
                state = "connected";
                if (job.has("request")) {
                    String id = job.getString("id");
                    if (!id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("invalid_job_id");
                    // Only gateway-signed envelopes may have effects. No transport acknowledgement is acceptance.
                    if (System.currentTimeMillis() >= config.expiresAtMs || System.nanoTime() >= deadlineNanos) break;
                    JSONObject result = endpoint.exchange(job.getJSONObject("request"));
                    if (closed) break;
                    JSONObject reply = new JSONObject(); reply.put("id", id); reply.put("response", result);
                    post("reply", reply);
                } else Thread.sleep(500L);
            } catch (Exception error) {
                state = "unavailable";
                if (!closed) try { Thread.sleep(1500L); } catch (InterruptedException ignored) { break; }
            }
        }
        state = closed ? "closed" : "expired";
    }
    private JSONObject post(String operation, JSONObject body) throws Exception {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65536) throw new IllegalArgumentException("relay_body_too_large");
        HttpsURLConnection c = (HttpsURLConnection) new URI(config.origin + "/v1/relay/" + config.session + "/" + operation).toURL().openConnection();
        connection = c;
        try {
            if (closed) throw new IllegalStateException("closed");
            // Platform trust manager and hostname verification remain intact. Redirects cannot move credentials.
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(5000); c.setReadTimeout(10000);
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setFixedLengthStreamingMode(bytes.length);
            c.setRequestProperty("Authorization", "Bearer " + config.token);
            c.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int code = c.getResponseCode();
            if (code == 401 || code == 403 || code == 410) { closed = true; throw new IllegalStateException("relay_scope_retired"); }
            if (code != 200) throw new IllegalStateException("relay_unavailable");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 65536) throw new IllegalArgumentException("relay_body_too_large");
                    out.write(buffer, 0, n);
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { c.disconnect(); connection = null; }
    }
    public JSONObject snapshot() throws Exception {
        String effectiveState = !closed && (System.currentTimeMillis() >= config.expiresAtMs || System.nanoTime() >= deadlineNanos)
                ? "expired" : state;
        return new JSONObject().put("route", "relay").put("state", effectiveState)
                .put("expires_at_ms", config.expiresAtMs).put("command_authority", "app_gateway");
    }
    public synchronized void close() {
        closed = true; state = "closed";
        HttpsURLConnection c = connection; if (c != null) c.disconnect();
        if (worker != null) worker.interrupt();
    }
}
