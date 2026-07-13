package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EmbeddedManifoldBrokerServer {
    private static final String TAG = "RQNativeRenderer";
    private static final String MARKER_PREFIX = "RUSTY_QUEST_NATIVE_RENDERER";
    private static final String CHANNEL = "manifold-embedded-broker";
    private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final String COMMAND_SCHEMA = "rusty.manifold.command.envelope.v1";
    private static final String MUTATION_SCHEMA =
            "rusty.quest.broker.server_mutation_request.v1";
    private static final String DEFAULT_BIND_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8765;
    private static final String DEFAULT_PATH = "/manifold/v1/events";
    private static final int DEFAULT_MAX_FRAME_BYTES = 65536;
    private static final int MAX_READ_ONLY_CLIENTS = 4;
    private static final int CLIENT_IDLE_TIMEOUT_MS = 15000;
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
            socket.setSoTimeout(CLIENT_IDLE_TIMEOUT_MS);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            Handshake handshake = readHandshake(input);
            if (!settings.path.equals(handshake.path)) {
                writeHttp(output, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            String key = handshake.headers.get("sec-websocket-key");
            if (key == null || key.isEmpty()) {
                writeHttp(output, "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            if (activeClientCount() >= MAX_READ_ONLY_CLIENTS) {
                writeHttp(output, "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            writeWebSocketAccept(output, key);
            session = new BrokerSession(output);
            synchronized (sessionLock) {
                sessions.add(session);
            }
            marker("status=client-connected activeClients=" + activeClientCount() + " " + settings.markerFields());
            while (!socket.isClosed()) {
                Frame frame = readFrame(input, settings.maxFrameBytes);
                if (frame == null || frame.opcode == 0x8) {
                    return;
                }
                if (frame.opcode == 0x9) {
                    writeFrame(output, frame.payload, 0xA);
                    continue;
                }
                if (frame.opcode != 0x1) {
                    continue;
                }
                handleTextFrame(session, new String(frame.payload, StandardCharsets.UTF_8));
            }
        } catch (FrameTooLargeException ex) {
            droppedEventCount += 1;
            marker("status=frame-rejected reason=oversized maxFrameBytes=" + settings.maxFrameBytes);
        } catch (Exception ignored) {
            // Client disconnects and malformed readiness probes are expected.
        } finally {
            if (session != null) {
                synchronized (sessionLock) {
                    sessions.remove(session);
                }
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

    private static Handshake readHandshake(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.US_ASCII));
        String request = reader.readLine();
        if (request == null) {
            throw new IOException("missing HTTP request line");
        }
        String[] requestParts = request.split(" ");
        String path = requestParts.length >= 2 ? requestParts[1] : "";
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(
                        line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        return new Handshake(path, headers);
    }

    private static void writeHttp(OutputStream output, String response) throws IOException {
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void writeWebSocketAccept(OutputStream output, String key) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] sha1 = digest.digest((key + ACCEPT_GUID).getBytes(StandardCharsets.US_ASCII));
        String accept = Base64.encodeToString(sha1, Base64.NO_WRAP);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n";
        writeHttp(output, response);
    }

    private static Frame readFrame(InputStream input, int maxFrameBytes) throws IOException, FrameTooLargeException {
        int first = input.read();
        if (first < 0) {
            return null;
        }
        int second = readByte(input);
        int opcode = first & 0x0F;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        if (length == 126) {
            length = ((long) readByte(input) << 8) | readByte(input);
        } else if (length == 127) {
            length = 0;
            for (int index = 0; index < 8; index++) {
                length = (length << 8) | readByte(input);
            }
        }
        if (length > maxFrameBytes) {
            throw new FrameTooLargeException();
        }
        byte[] mask = masked ? readBytes(input, 4) : new byte[0];
        byte[] payload = readBytes(input, (int) length);
        if (masked) {
            for (int index = 0; index < payload.length; index++) {
                payload[index] = (byte) (payload[index] ^ mask[index % 4]);
            }
        }
        return new Frame(opcode, payload);
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new IOException("unexpected EOF");
        }
        return value;
    }

    private static byte[] readBytes(InputStream input, int size) throws IOException {
        byte[] bytes = new byte[size];
        int offset = 0;
        while (offset < size) {
            int read = input.read(bytes, offset, size - offset);
            if (read < 0) {
                throw new IOException("unexpected EOF");
            }
            offset += read;
        }
        return bytes;
    }

    private static void writeText(BrokerSession session, JSONObject object) throws IOException {
        synchronized (session) {
            writeFrame(session.output, object.toString().getBytes(StandardCharsets.UTF_8), 0x1);
        }
    }

    private static void writeFrame(OutputStream output, byte[] payload, int opcode) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | (opcode & 0x0F));
        int length = payload.length;
        if (length < 126) {
            frame.write(length);
        } else if (length <= 0xFFFF) {
            frame.write(126);
            frame.write((length >> 8) & 0xFF);
            frame.write(length & 0xFF);
        } else {
            frame.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame.write((length >> shift) & 0xFF);
            }
        }
        frame.write(payload);
        output.write(frame.toByteArray());
        output.flush();
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

    private static final class Handshake {
        final String path;
        final Map<String, String> headers;

        Handshake(String path, Map<String, String> headers) {
            this.path = path;
            this.headers = headers;
        }
    }

    private static final class Frame {
        final int opcode;
        final byte[] payload;

        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    private static final class FrameTooLargeException extends Exception {
    }

    private static final class BrokerSession {
        final OutputStream output;
        final Set<String> subscriptions = new HashSet<>();

        BrokerSession(OutputStream output) {
            this.output = output;
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
