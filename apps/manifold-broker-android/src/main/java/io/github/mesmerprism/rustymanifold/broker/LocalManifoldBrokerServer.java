package io.github.mesmerprism.rustymanifold.broker;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
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

public final class LocalManifoldBrokerServer {
    public static final int PORT = 8765;
    public static final String EVENTS_PATH = "/manifold/v1/events";
    private static final String COMMAND_SCHEMA = "rusty.manifold.command.envelope.v1";
    private static final String BRIDGE_PROBE_SET_MARKER_COMMAND =
            "hostess.makepad.bridge_probe.set_marker";
    private static final String BRIDGE_COMMAND_REQUEST_SCHEMA =
            "rusty.hostess.bridge_command.request.v1";
    private static final String BRIDGE_COMMAND_REQUEST_STREAM =
            "stream.hostess.makepad.bridge_command";
    private static final String BRIDGE_COMMAND_RECEIPT_STREAM =
            "stream.hostess.makepad.bridge_command.receipt";
    private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final LocalManifoldBrokerServer INSTANCE = new LocalManifoldBrokerServer();

    private final Object lock = new Object();
    private final Object streamLock = new Object();
    private final List<BrokerSession> sessions = new ArrayList<>();
    private boolean started;
    private ServerSocket serverSocket;
    private volatile Context applicationContext;

    private LocalManifoldBrokerServer() {
    }

    public static LocalManifoldBrokerServer get() {
        return INSTANCE;
    }

    public boolean isRunning() {
        synchronized (lock) {
            return started && serverSocket != null && !serverSocket.isClosed();
        }
    }

    public void start(Context context) {
        synchronized (lock) {
            if (context != null) {
                applicationContext = context.getApplicationContext();
            }
        }
        start();
    }

    public void start() {
        synchronized (lock) {
            if (started) {
                return;
            }
            started = true;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runServer();
                }
            }, "rusty-manifold-broker");
            thread.start();
        }
    }

    private void runServer() {
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
            synchronized (lock) {
                serverSocket = socket;
            }
            while (!socket.isClosed()) {
                Socket client = socket.accept();
                Thread session = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(client);
                    }
                }, "rusty-manifold-broker-client");
                session.start();
            }
        } catch (IOException ex) {
            synchronized (lock) {
                started = false;
            }
        }
    }

    private void handleClient(Socket client) {
        BrokerSession session = null;
        try (Socket socket = client) {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            Handshake handshake = readHandshake(input);
            if (!EVENTS_PATH.equals(handshake.path)) {
                writeHttp(output, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            String key = handshake.headers.get("sec-websocket-key");
            if (key == null || key.isEmpty()) {
                writeHttp(output, "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            writeWebSocketAccept(output, key);
            session = new BrokerSession(output);
            synchronized (streamLock) {
                sessions.add(session);
            }
            while (!socket.isClosed()) {
                Frame frame = readFrame(input);
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
        } catch (Exception ignored) {
            // Client disconnects and malformed probes are expected during readiness polling.
        } finally {
            if (session != null) {
                synchronized (streamLock) {
                    sessions.remove(session);
                }
            }
        }
    }

    private void handleTextFrame(BrokerSession session, String text) throws Exception {
        JSONObject message = new JSONObject(text);
        String type = message.optString("type", "");
        if ("hello".equals(type)) {
            JSONObject reply = new JSONObject();
            reply.put("type", "hello_ack");
            reply.put("schema", "rusty.manifold.broker.hello_ack.v1");
            reply.put("accepted", true);
            reply.put("authority", "rusty.manifold");
            reply.put("server_id", "rusty.quest.manifold_broker_android");
            reply.put("endpoint_path", EVENTS_PATH);
            reply.put("time_utc", Instant.now().toString());
            writeText(session, reply);
            return;
        }

        if ("command".equals(type) || COMMAND_SCHEMA.equals(message.optString("schema", ""))) {
            JSONObject reply = new JSONObject();
            String command = message.optString("command_id", message.optString("command", "unknown"));
            JSONObject params = message.optJSONObject("params");
            if (params == null) {
                params = new JSONObject();
            }
            if (isSubscribeCommand(command)) {
                String stream = firstNonEmpty(
                        params.optString("stream", ""),
                        params.optString("stream_id", ""),
                        params.optString("value", ""));
                if (!stream.isEmpty()) {
                    session.subscribe(stream);
                }
                reply.put("type", "command_ack");
                reply.put("schema", "rusty.manifold.command.ack.v1");
                reply.put("request_id", message.optString("request_id", ""));
                reply.put("command", command);
                reply.put("accepted", !stream.isEmpty());
                reply.put("status", stream.isEmpty() ? "missing_stream" : "subscribed");
                reply.put("authority", "rusty.manifold");
                reply.put("stream", stream);
                reply.put("time_utc", Instant.now().toString());
                writeText(session, reply);
                return;
            }
            if (isPublishStreamEventCommand(command)) {
                JSONObject event = buildStreamEvent(message, params);
                reply.put("type", "command_ack");
                reply.put("schema", "rusty.manifold.command.ack.v1");
                reply.put("request_id", message.optString("request_id", ""));
                reply.put("command", command);
                reply.put("accepted", true);
                reply.put("status", "published");
                reply.put("authority", "rusty.manifold");
                reply.put("stream", event.optString("stream", ""));
                reply.put("stream_event_delivered_count", 0);
                reply.put("live_stream_events_synthesized", false);
                reply.put("time_utc", Instant.now().toString());
                writeText(session, reply);
                int delivered = publishStreamEvent(event);
                reply.put("stream_event_delivered_count", delivered);
                return;
            }
            if (isBridgeProbeSetMarkerCommand(command)) {
                JSONObject event = buildBridgeCommandRequestEvent(message, params);
                int delivered = publishStreamEvent(event);
                reply.put("type", "command_ack");
                reply.put("schema", "rusty.manifold.command.ack.v1");
                reply.put("request_id", message.optString("request_id", ""));
                reply.put("command", command);
                reply.put("accepted", true);
                reply.put("status", delivered > 0 ? "dispatched" : "accepted_no_runtime_subscriber");
                reply.put("authority", "rusty.manifold");
                reply.put("runtime_dispatch_stream", BRIDGE_COMMAND_REQUEST_STREAM);
                reply.put("runtime_receipt_stream", BRIDGE_COMMAND_RECEIPT_STREAM);
                reply.put("runtime_dispatch_delivered_count", delivered);
                reply.put("runtime_receipt_required", true);
                reply.put("live_stream_events_synthesized", false);
                reply.put("high_rate_json_payload", false);
                reply.put("time_utc", Instant.now().toString());
                writeText(session, reply);
                return;
            }
            boolean mediaStreamCommand = RemoteCameraSessionRuntime.isMediaStreamCommand(message);
            JSONObject remoteCameraRuntime = RemoteCameraSessionRuntime.handleCommand(applicationContext, message);
            reply.put("type", "command_ack");
            reply.put("schema", "rusty.manifold.command.ack.v1");
            reply.put("request_id", message.optString("request_id", ""));
            reply.put("command", command);
            reply.put("accepted", true);
            reply.put("status", remoteCameraRuntime != null
                    ? remoteCameraRuntime.optString("status", "accepted")
                    : "accepted");
            reply.put("authority", "rusty.manifold");
            reply.put("live_stream_events_synthesized", false);
            if (remoteCameraRuntime != null) {
                if (mediaStreamCommand) {
                    reply.put("media_stream_runtime", remoteCameraRuntime);
                } else {
                    reply.put("remote_camera_runtime", remoteCameraRuntime);
                }
                reply.put(
                        "media_socket_runtime_started",
                        remoteCameraRuntime.optBoolean("media_socket_runtime_started", false));
            }
            reply.put("time_utc", Instant.now().toString());
            writeText(session, reply);
            return;
        }

        JSONObject reply = new JSONObject();
        reply.put("type", "message_ack");
        reply.put("accepted", true);
        reply.put("authority", "rusty.manifold");
        writeText(session, reply);
    }

    private static boolean isSubscribeCommand(String command) {
        return "subscribe".equals(command)
                || "stream.subscribe".equals(command)
                || "manifold.stream.subscribe".equals(command);
    }

    private static boolean isPublishStreamEventCommand(String command) {
        return "publish_stream_event".equals(command)
                || "stream.publish".equals(command)
                || "manifold.stream.publish".equals(command);
    }

    private static boolean isBridgeProbeSetMarkerCommand(String command) {
        return BRIDGE_PROBE_SET_MARKER_COMMAND.equals(command);
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

    private static JSONObject buildStreamEvent(JSONObject message, JSONObject params) throws Exception {
        JSONObject payload = params.optJSONObject("payload");
        if (payload == null) {
            payload = new JSONObject();
        }
        String stream = firstNonEmpty(
                params.optString("stream", ""),
                params.optString("stream_id", ""),
                firstNonEmpty(payload.optString("stream", ""), payload.optString("stream_id", ""), ""));
        long sequenceId = params.has("sequence_id")
                ? params.optLong("sequence_id", 0L)
                : payload.optLong("sequence_id", 0L);
        long brokerTimeUnixNs = System.currentTimeMillis() * 1_000_000L;
        JSONObject event = new JSONObject();
        event.put("type", "stream_event");
        event.put("schema", "rusty.manifold.stream.event.v1");
        event.put("stream", stream);
        event.put("stream_id", stream);
        event.put("sequence_id", sequenceId);
        event.put("payload", payload);
        event.put("source_request_id", message.optString("request_id", ""));
        event.put("transport_time_unix_ns", brokerTimeUnixNs);
        event.put("transport_receive_time_unix_ns", brokerTimeUnixNs);
        event.put("time_utc", Instant.now().toString());
        return event;
    }

    private static JSONObject buildBridgeCommandRequestEvent(JSONObject message, JSONObject params) throws Exception {
        JSONObject requestParams = new JSONObject(params.toString());
        String requestId = message.optString("request_id", "");
        if (firstNonEmpty(
                requestParams.optString("probe_token", ""),
                requestParams.optString("marker", ""),
                "").isEmpty() && !requestId.isEmpty()) {
            requestParams.put("probe_token", requestId);
        }
        if (!requestParams.has("source")) {
            requestParams.put("source", "manifold-broker-stream");
        }
        requestParams.put("command_transport", "manifold-broker-stream");
        requestParams.put("receipt_stream", BRIDGE_COMMAND_RECEIPT_STREAM);

        JSONObject payload = new JSONObject();
        payload.put("$schema", BRIDGE_COMMAND_REQUEST_SCHEMA);
        payload.put("request_id", requestId);
        payload.put("route_id", firstNonEmpty(
                message.optString("route_id", ""),
                params.optString("route_id", ""),
                "bridge_route.command.websocket.applied"));
        payload.put("command", BRIDGE_PROBE_SET_MARKER_COMMAND);
        payload.put("params", requestParams);
        payload.put("required_evidence_stages", new JSONArray()
                .put("runtime_accepted")
                .put("applied"));
        payload.put("runtime_receipt_stream", BRIDGE_COMMAND_RECEIPT_STREAM);
        payload.put("high_rate_json_payload", false);

        long brokerTimeUnixNs = System.currentTimeMillis() * 1_000_000L;
        JSONObject event = new JSONObject();
        event.put("type", "stream_event");
        event.put("schema", "rusty.manifold.stream.event.v1");
        event.put("stream", BRIDGE_COMMAND_REQUEST_STREAM);
        event.put("stream_id", BRIDGE_COMMAND_REQUEST_STREAM);
        event.put("sequence_id", brokerTimeUnixNs);
        event.put("payload", payload);
        event.put("source_request_id", requestId);
        event.put("runtime_receipt_stream", BRIDGE_COMMAND_RECEIPT_STREAM);
        event.put("transport_time_unix_ns", brokerTimeUnixNs);
        event.put("transport_receive_time_unix_ns", brokerTimeUnixNs);
        event.put("high_rate_json_payload", false);
        event.put("time_utc", Instant.now().toString());
        return event;
    }

    private int publishStreamEvent(JSONObject event) {
        String stream = event.optString("stream", "");
        if (stream.isEmpty()) {
            return 0;
        }
        List<BrokerSession> snapshot;
        synchronized (streamLock) {
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
                synchronized (streamLock) {
                    sessions.remove(session);
                }
            }
        }
        return delivered;
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

    private static Frame readFrame(InputStream input) throws IOException {
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
        if (length > 1024 * 1024) {
            throw new IOException("websocket frame too large");
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

        boolean isSubscribedTo(String stream) {
            synchronized (subscriptions) {
                return subscriptions.contains(stream);
            }
        }
    }
}
