package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.util.Log;

import io.github.mesmerprism.rustyquest.broker_transport.BoundedWebSocketSession;
import io.github.mesmerprism.rustyquest.broker_transport.DeadlineInputStream;
import io.github.mesmerprism.rustyquest.broker_transport.Rfc6455Codec;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class EmbeddedManifoldBrokerServer {
    private static final String TAG = "RQNativeRenderer";
    private static final String MARKER_PREFIX = "RUSTY_QUEST_NATIVE_RENDERER";
    private static final String CHANNEL = "manifold-embedded-broker";
    private static final String COMMAND_SCHEMA = "rusty.manifold.command.envelope.v1";
    private static final String MUTATION_SCHEMA =
            "rusty.quest.broker.server_mutation_request.v1";
    private static final String DEFAULT_BIND_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8765;
    private static final String DEFAULT_PATH = "/manifold/v1/events";
    private static final int DEFAULT_MAX_FRAME_BYTES = 65536;
    private static final int MAX_READ_ONLY_CLIENTS = 4;
    private static final int CLIENT_POLL_TIMEOUT_MS = 250;
    private static final int MAX_UPGRADE_HEADER_BYTES = 16 * 1024;
    private static final long UPGRADE_DEADLINE_MS = 10_000L;
    private static final long FRAME_ASSEMBLY_DEADLINE_MS = 5_000L;
    private static final int MAX_OUTBOUND_MESSAGES = 64;
    private static final int MAX_OUTBOUND_BYTES = 1024 * 1024;
    private static final long PING_INTERVAL_MS = 15_000L;
    private static final long PONG_DEADLINE_MS = 5_000L;
    private static final long IDLE_DEADLINE_MS = 60_000L;
    private static final EmbeddedManifoldBrokerServer INSTANCE = new EmbeddedManifoldBrokerServer();

    private final Object lifecycleLock = new Object();
    private final Object sessionLock = new Object();
    private final List<BrokerSession> sessions = new ArrayList<>();
    private volatile boolean started;
    private volatile Settings settings = Settings.defaults();
    private ServerSocket serverSocket;
    private long commandCount;
    private long streamEventCount;
    private long droppedEventCount;

    private EmbeddedManifoldBrokerServer() {
    }

    public static void startFromNative(Activity activity, String settingsJson) {
        INSTANCE.start(activity, settingsJson);
    }

    public static void stop() {
        INSTANCE.stopServer();
    }

    private void start(Activity activity, String settingsJson) {
        Settings parsed;
        try {
            parsed = Settings.fromJson(settingsJson);
        } catch (Exception ex) {
            marker("status=error reason=settings-json " + Settings.defaults().markerFields());
            return;
        }

        if (!parsed.enabled) {
            marker("status=disabled reason=feature-disabled " + parsed.markerFields());
            return;
        }
        // This embedded WebSocket is deliberately a loopback-only, read-only
        // readiness surface. Network callers cannot be projected as the
        // renderer process and therefore cannot inherit its Binder grants.
        String networkPolicyRejection = EmbeddedWebSocketAuthorityPolicy.startRejection(
                parsed.lanEnabled,
                parsed.bindHost,
                parsed.sessionTokenRequired,
                parsed.sessionToken);
        if (networkPolicyRejection != null) {
            marker("status=error reason=" + networkPolicyRejection + " " + parsed.markerFields());
            return;
        }
        try {
            EmbeddedManifoldRuntimeAuthorityBridge.initialize();
        } catch (Exception ex) {
            marker("status=error reason=authority-runtime-initialize " + parsed.markerFields());
            return;
        }

        synchronized (lifecycleLock) {
            if (started) {
                marker("status=already-running " + settings.markerFields());
                return;
            }
            settings = parsed;
            started = true;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runServer();
                }
            }, "rusty-quest-embedded-manifold-broker");
            thread.start();
        }
    }

    private void stopServer() {
        ServerSocket socket;
        synchronized (lifecycleLock) {
            started = false;
            socket = serverSocket;
            serverSocket = null;
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing an already-dead server socket is expected during process teardown.
            }
        }
        List<BrokerSession> copy;
        synchronized (sessionLock) {
            copy = new ArrayList<>(sessions);
            sessions.clear();
        }
        for (BrokerSession session : copy) {
            session.close();
        }
        marker("status=stopped " + settings.markerFields());
    }

    private void runServer() {
        Settings active = settings;
        marker("status=starting " + active.markerFields());
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName(active.bindHost), active.port));
            synchronized (lifecycleLock) {
                serverSocket = socket;
            }
            marker("status=started embeddedManifoldBrokerStarted=true " + active.markerFields());
            while (!socket.isClosed()) {
                Socket client = socket.accept();
                Thread session = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(client);
                    }
                }, "rusty-quest-embedded-manifold-client");
                session.start();
            }
        } catch (Exception ex) {
            synchronized (lifecycleLock) {
                started = false;
            }
            marker("status=error reason=" + markerToken(ex.getClass().getSimpleName()) + " " + active.markerFields());
        }
    }

    private void handleClient(Socket client) {
        BrokerSession session = null;
        try (Socket socket = client) {
            socket.setSoTimeout(CLIENT_POLL_TIMEOUT_MS);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            Rfc6455Codec.UpgradeRequest handshake = Rfc6455Codec.readUpgradeRequest(
                    new DeadlineInputStream(
                            input,
                            deadlineAfterMs(UPGRADE_DEADLINE_MS),
                            TimeUnit.MILLISECONDS.toNanos(FRAME_ASSEMBLY_DEADLINE_MS)),
                    MAX_UPGRADE_HEADER_BYTES);
            if (!settings.path.equals(handshake.target)) {
                writeHttp(output, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            final String accept;
            try {
                accept = Rfc6455Codec.serverAccept(
                        handshake.method, handshake.httpVersion, handshake.headers);
            } catch (IOException invalidUpgrade) {
                writeHttp(output, "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            if (activeClientCount() >= MAX_READ_ONLY_CLIENTS) {
                writeHttp(output, "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            writeWebSocketAccept(output, accept);
            session = new BrokerSession(socket, output, settings.maxFrameBytes);
            synchronized (sessionLock) {
                sessions.add(session);
            }
            marker("status=client-connected activeClients=" + activeClientCount() + " " + settings.markerFields());
            while (!socket.isClosed()) {
                final Rfc6455Codec.Frame frame;
                try {
                    frame = Rfc6455Codec.readFrame(
                            new DeadlineInputStream(
                                    input,
                                    deadlineAfterMs(CLIENT_POLL_TIMEOUT_MS),
                                    TimeUnit.MILLISECONDS.toNanos(
                                            FRAME_ASSEMBLY_DEADLINE_MS)),
                            true,
                            settings.maxFrameBytes);
                } catch (SocketTimeoutException timeout) {
                    String timeoutMessage = timeout.getMessage();
                    if (timeoutMessage == null || !timeoutMessage.contains("first-byte")) {
                        throw timeout;
                    }
                    session.tick(System.nanoTime());
                    continue;
                }
                Rfc6455Codec.Event event = session.receive(frame, System.nanoTime());
                if (event.kind == Rfc6455Codec.Event.Kind.CLOSE) {
                    session.awaitClosed(1_000L);
                    return;
                }
                if (event.kind == Rfc6455Codec.Event.Kind.TEXT) {
                    handleTextFrame(session, event.text);
                }
            }
        } catch (IOException ex) {
            droppedEventCount += 1;
            marker("status=transport-rejected reason=" + markerToken(ex.getMessage())
                    + " maxFrameBytes=" + settings.maxFrameBytes);
        } catch (Exception ignored) {
            // Client disconnects and malformed readiness probes are expected.
        } finally {
            if (session != null) {
                synchronized (sessionLock) {
                    sessions.remove(session);
                }
                session.close();
                marker("status=client-disconnected activeClients=" + activeClientCount());
            }
        }
    }

    private void handleTextFrame(BrokerSession session, String text) throws Exception {
        JSONObject message = new JSONObject(text);
        String type = message.optString("type", "");
        if ("hello".equals(type)) {
            JSONObject reply = new JSONObject();
            reply.put("type", "hello_transport_status");
            reply.put("schema", "rusty.quest.broker.transport_status.v1");
            reply.put("transport_ready", true);
            reply.put("server_id", "rusty.quest.native_renderer.embedded_manifold_broker");
            reply.put("endpoint_path", settings.path);
            reply.put("embedded", true);
            reply.put("read_only", true);
            reply.put("mutation_transport", false);
            reply.put("mutation_route", "signature_scoped_binder_or_direct_in_process");
            reply.put("active_clients", activeClientCount());
            reply.put("time_utc", Instant.now().toString());
            writeText(session, reply);
            return;
        }

        if ("command".equals(type)
                || COMMAND_SCHEMA.equals(message.optString("schema", ""))
                || MUTATION_SCHEMA.equals(message.optString("$schema", ""))) {
            commandCount += 1;
            JSONObject reply = new JSONObject();
            reply.put("type", "transport_rejection");
            reply.put("schema", "rusty.quest.broker.transport_rejection.v1");
            reply.put("reason", EmbeddedWebSocketAuthorityPolicy.MUTATION_REJECTION);
            reply.put("mutation_route", "signature_scoped_binder_or_direct_in_process");
            reply.put("network_identity_delegated", false);
            writeText(session, reply);
            return;
        }

        JSONObject reply = new JSONObject();
        reply.put("type", "transport_rejection");
        reply.put("schema", "rusty.quest.broker.transport_rejection.v1");
        reply.put("reason", EmbeddedWebSocketAuthorityPolicy.MUTATION_REJECTION);
        writeText(session, reply);
    }

    private JSONObject buildStreamEvent(JSONObject authorityResponse, JSONObject params) throws Exception {
        JSONObject payload = params.optJSONObject("payload");
        if (payload == null) {
            payload = new JSONObject();
        }
        String stream = streamFrom(params, payload);
        if (!payload.has("stream_id")) {
            payload.put("stream_id", stream);
        }
        if (!payload.has("stream")) {
            payload.put("stream", stream);
        }
        if (!payload.has("value01")) {
            if (params.has("value01")) {
                payload.put("value01", params.optDouble("value01", 0.0));
            }
        }
        long sequenceId = params.has("sequence_id")
                ? params.optLong("sequence_id", 0L)
                : payload.optLong("sequence_id", 0L);
        long brokerTimeUnixNs = System.currentTimeMillis() * 1000000L;
        JSONObject event = new JSONObject();
        event.put("type", "stream_event");
        event.put("schema", "rusty.manifold.stream.event.v1");
        event.put("stream", stream);
        event.put("stream_id", stream);
        event.put("sequence_id", sequenceId);
        event.put("payload", payload);
        event.put("source_request_id", requestId(authorityResponse));
        event.put("transport_time_unix_ns", brokerTimeUnixNs);
        event.put("transport_receive_time_unix_ns", brokerTimeUnixNs);
        event.put("time_utc", Instant.now().toString());
        return event;
    }

    private int publishStreamEvent(JSONObject event) {
        String stream = event.optString("stream", "");
        if (stream.isEmpty()) {
            droppedEventCount += 1;
            return 0;
        }
        List<BrokerSession> snapshot;
        synchronized (sessionLock) {
            snapshot = new ArrayList<>(sessions);
        }
        int delivered = 0;
        for (BrokerSession session : snapshot) {
            if (!session.isSubscribedTo(stream)) {
                continue;
            }
            try {
                writeText(session, event);
                delivered += 1;
            } catch (IOException ex) {
                synchronized (sessionLock) {
                    sessions.remove(session);
                }
            }
        }
        streamEventCount += 1;
        marker("status=stream-event-published stream=" + markerToken(stream)
                + " deliveredCount=" + delivered
                + " streamEventsPublished=" + streamEventCount
                + " droppedEvents=" + droppedEventCount);
        return delivered;
    }

    private int activeClientCount() {
        synchronized (sessionLock) {
            return sessions.size();
        }
    }


    private static String requestId(JSONObject authorityResponse) {
        return authorityResponse.optString("request_id", "");
    }

    private static String streamFrom(JSONObject params, JSONObject payload) {
        if (payload == null) {
            payload = new JSONObject();
        }
        return firstNonEmpty(
                params.optString("stream", ""),
                params.optString("stream_id", ""),
                firstNonEmpty(payload.optString("stream", ""), payload.optString("stream_id", ""), ""));
    }

    private static String firstNonEmpty(String first, String second, String third) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        if (third != null && !third.trim().isEmpty()) {
            return third.trim();
        }
        return "";
    }

    private static void writeHttp(OutputStream output, String response) throws IOException {
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void writeWebSocketAccept(OutputStream output, String accept) throws IOException {
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n";
        writeHttp(output, response);
    }

    private static void writeText(BrokerSession session, JSONObject object) throws IOException {
        session.sendText(object.toString());
    }

    private static long deadlineAfterMs(long durationMs) {
        long now = System.nanoTime();
        long delta = TimeUnit.MILLISECONDS.toNanos(durationMs);
        return now > Long.MAX_VALUE - delta ? Long.MAX_VALUE : now + delta;
    }

    private static void marker(String detail) {
        Log.i(TAG, MARKER_PREFIX + " channel=" + CHANNEL + " " + sanitize(detail));
    }

    private static String markerToken(String value) {
        String sanitized = sanitize(value == null ? "" : value.trim())
                .replace(' ', '_')
                .replace(',', '_')
                .replace(';', '_');
        return sanitized.isEmpty() ? "none" : sanitized;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\0', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('"', '\'');
    }

    private static final class Settings {
        final boolean enabled;
        final String bindHost;
        final int port;
        final String path;
        final int maxFrameBytes;
        final boolean lanEnabled;
        final boolean sessionTokenRequired;
        final String sessionToken;

        Settings(
                boolean enabled,
                String bindHost,
                int port,
                String path,
                int maxFrameBytes,
                boolean lanEnabled,
                boolean sessionTokenRequired,
                String sessionToken) {
            this.enabled = enabled;
            this.bindHost = bindHost;
            this.port = port;
            this.path = path;
            this.maxFrameBytes = maxFrameBytes;
            this.lanEnabled = lanEnabled;
            this.sessionTokenRequired = sessionTokenRequired;
            this.sessionToken = sessionToken;
        }

        static Settings defaults() {
            return new Settings(
                    false,
                    DEFAULT_BIND_HOST,
                    DEFAULT_PORT,
                    DEFAULT_PATH,
                    DEFAULT_MAX_FRAME_BYTES,
                    false,
                    false,
                    "");
        }

        static Settings fromJson(String settingsJson) throws Exception {
            JSONObject object = new JSONObject(settingsJson == null ? "{}" : settingsJson);
            if (object.has("authority_runtime_config_json")) {
                throw new IllegalArgumentException("settings-supplied authority config is forbidden");
            }
            boolean lanEnabled = object.optBoolean("lan_enabled", false);
            return new Settings(
                    object.optBoolean("enabled", false),
                    nonEmpty(object.optString("bind_host", DEFAULT_BIND_HOST), DEFAULT_BIND_HOST),
                    clampInt(object.optInt("port", DEFAULT_PORT), 1, 65535),
                    nonEmpty(object.optString("path", DEFAULT_PATH), DEFAULT_PATH),
                    clampInt(object.optInt("max_frame_bytes", DEFAULT_MAX_FRAME_BYTES), 1024, 1024 * 1024),
                    lanEnabled,
                    object.has("session_token_required")
                            ? object.optBoolean("session_token_required", lanEnabled)
                            : lanEnabled,
                    object.optString("session_token", "").trim());
        }

        String markerFields() {
            return "embeddedManifoldBrokerEnabled=" + enabled
                    + " bindHost=" + markerToken(bindHost)
                    + " port=" + port
                    + " path=" + markerToken(path)
                    + " maxFrameBytes=" + maxFrameBytes
                    + " lanEnabled=" + lanEnabled
                    + " sessionTokenRequired=" + sessionTokenRequired
                    + " authorityConfigSource=packaged";
        }

        private static String nonEmpty(String value, String fallback) {
            if (value == null || value.trim().isEmpty()) {
                return fallback;
            }
            return value.trim();
        }

        private static int clampInt(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final class BrokerSession {
        final Set<String> subscriptions = new HashSet<>();
        final BoundedWebSocketSession transport;

        BrokerSession(final Socket socket, final OutputStream output, int maxFrameBytes) {
            BoundedWebSocketSession.Limits limits = new BoundedWebSocketSession.Limits(
                    MAX_OUTBOUND_MESSAGES,
                    Math.max(maxFrameBytes, MAX_OUTBOUND_BYTES),
                    maxFrameBytes,
                    TimeUnit.MILLISECONDS.toNanos(PING_INTERVAL_MS),
                    TimeUnit.MILLISECONDS.toNanos(PONG_DEADLINE_MS),
                    TimeUnit.MILLISECONDS.toNanos(IDLE_DEADLINE_MS));
            transport = new BoundedWebSocketSession(
                    "rusty-quest-embedded-websocket-writer",
                    new BoundedWebSocketSession.FrameWriter() {
                        @Override public void write(
                                boolean fin, int opcode, byte[] payload) throws IOException {
                            Rfc6455Codec.writeFrame(output, fin, opcode, payload, null);
                        }

                        @Override public void close() throws IOException {
                            socket.close();
                        }
                    },
                    limits,
                    System.nanoTime(),
                    null,
                    new BoundedWebSocketSession.TelemetrySink() {
                        @Override public void onEvent(BoundedWebSocketSession.Telemetry event) {
                            if (event.code == BoundedWebSocketSession.EventCode.QUEUE_SATURATED
                                    || event.code == BoundedWebSocketSession.EventCode.WRITE_FAILED
                                    || event.code == BoundedWebSocketSession.EventCode.CLOSED) {
                                marker("status=transport-lifecycle event=" + event.code.name()
                                        + " closeReason="
                                        + (event.closeReason == null
                                                ? "none"
                                                : event.closeReason.name())
                                        + " queuedMessages=" + event.queuedMessages
                                        + " queuedBytes=" + event.queuedBytes);
                            }
                        }
                    });
        }

        void sendText(String value) throws IOException {
            transport.sendText(value);
        }

        Rfc6455Codec.Event receive(Rfc6455Codec.Frame frame, long nowNanos)
                throws IOException {
            return transport.receive(frame, nowNanos);
        }

        void tick(long nowNanos) throws IOException {
            transport.tick(nowNanos);
        }

        boolean awaitClosed(long timeoutMs) throws InterruptedException {
            return transport.awaitClosed(timeoutMs);
        }

        void close() {
            transport.close();
        }

        void subscribe(String stream) {
            synchronized (subscriptions) {
                subscriptions.add(stream);
            }
        }

        int subscriptionCount() {
            synchronized (subscriptions) {
                return subscriptions.size();
            }
        }

        boolean isSubscribedTo(String stream) {
            synchronized (subscriptions) {
                return subscriptions.contains(stream);
            }
        }
    }
}
