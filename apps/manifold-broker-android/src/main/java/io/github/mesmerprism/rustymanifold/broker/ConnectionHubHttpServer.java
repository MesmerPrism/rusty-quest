package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed HTTP/WebSocket transport for low-rate Hub control surfaces. */
public final class ConnectionHubHttpServer
        implements Closeable, ConnectionHubRuntime.EventSink {
    public interface AssetLoader {
        Asset load(String path) throws IOException;
    }

    public static final class Asset {
        public final String contentType;
        public final byte[] bytes;
        public Asset(String contentType, byte[] bytes) {
            this.contentType = contentType;
            this.bytes = bytes.clone();
        }
    }

    private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final ConnectionHubRuntime runtime;
    private final AssetLoader assetLoader;
    private final ExecutorService clients = Executors.newFixedThreadPool(
            ConnectionHubProtocol.MAX_HTTP_CLIENTS);
    private final Semaphore clientSlots = new Semaphore(ConnectionHubProtocol.MAX_HTTP_CLIENTS);
    private final List<SocketSession> socketSessions = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private long socketAuthWindowStartedMs;
    private int socketAuthFailures;

    public ConnectionHubHttpServer(ConnectionHubRuntime runtime, AssetLoader assetLoader) {
        this.runtime = runtime;
        this.assetLoader = assetLoader;
        runtime.addEventSink(this);
    }

    public synchronized int start(InetAddress bindAddress, int requestedPort) throws IOException {
        if (serverSocket != null) {
            return serverSocket.getLocalPort();
        }
        ServerSocket next = new ServerSocket();
        next.setReuseAddress(true);
        next.bind(new InetSocketAddress(bindAddress, requestedPort));
        serverSocket = next;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "rusty-connection-hub-accept");
        acceptThread.start();
        return next.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                final Socket socket = serverSocket.accept();
                if (!clientSlots.tryAcquire()) {
                    socket.close();
                    continue;
                }
                socket.setSoTimeout(30_000);
                clients.execute(new Runnable() {
                    @Override public void run() {
                        try { handle(socket); }
                        finally { clientSlots.release(); }
                    }
                });
            } catch (IOException error) {
                if (!closed.get()) {
                    runtime.noteListenerFailure("listener_accept_failed");
                }
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            HttpRequest request = readRequest(input);
            String path = request.path;
            if ("GET".equals(request.method) && ConnectionHubProtocol.STATUS_PATH.equals(path)) {
                writeJson(output, 200, runtime.status());
                return;
            }
            if ("POST".equals(request.method) && ConnectionHubProtocol.PAIR_PATH.equals(path)) {
                if (!hasSameOrigin(request)) {
                    writeStatus(output, 403, "Forbidden", "text/plain; charset=utf-8", new byte[0]);
                    return;
                }
                JSONObject receipt = runtime.pair(
                        new JSONObject(request.body),
                        "evidence.operator.wearer-action");
                writeJson(output, receipt.optBoolean("accepted", false) ? 200 : 403, receipt);
                return;
            }
            if ("POST".equals(request.method) && ConnectionHubProtocol.REVOKE_PATH.equals(path)) {
                if (!hasSameOrigin(request)) {
                    writeStatus(output, 403, "Forbidden", "text/plain; charset=utf-8", new byte[0]);
                    return;
                }
                JSONObject receipt = runtime.revoke(new JSONObject(request.body));
                writeJson(output, receipt.optBoolean("applied", false) ? 200 : 403, receipt);
                return;
            }
            if ("GET".equals(request.method)
                    && ConnectionHubProtocol.SOCKET_PATH.equals(path)
                    && request.query.isEmpty()
                    && "websocket".equalsIgnoreCase(request.headers.get("upgrade"))) {
                handleSocket(client, request, input, output);
                return;
            }
            if ("GET".equals(request.method)) {
                handleAsset(path, output);
                return;
            }
            writeStatus(output, 404, "Not Found", "text/plain; charset=utf-8", new byte[0]);
        } catch (Exception ignored) {
            // Bounded malformed inputs and disconnects are transport rejections.
        }
    }

    private void handleAsset(String path, OutputStream output) throws IOException {
        String assetPath;
        if ("/".equals(path) || "/index.html".equals(path)) {
            assetPath = "connection-hub/index.html";
        } else if ("/assets/app.js".equals(path)) {
            assetPath = "connection-hub/app.js";
        } else if ("/assets/protocol.js".equals(path)) {
            assetPath = "connection-hub/protocol.js";
        } else if ("/assets/styles.css".equals(path)) {
            assetPath = "connection-hub/styles.css";
        } else {
            writeStatus(output, 404, "Not Found", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        Asset asset = assetLoader.load(assetPath);
        if (asset == null) {
            writeStatus(output, 404, "Not Found", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        writeStatus(output, 200, "OK", asset.contentType, asset.bytes);
    }

    private void handleSocket(
            Socket socket,
            HttpRequest request,
            InputStream input,
            OutputStream output) throws Exception {
        String key = request.headers.get("sec-websocket-key");
        if (!hasSameOrigin(request)
                || !"13".equals(request.headers.get("sec-websocket-version"))
                || !headerContainsToken(request.headers.get("connection"), "upgrade")
                || key == null
                || Base64.getDecoder().decode(key).length != 16) {
            writeStatus(output, 400, "Bad Request", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        synchronized (socketSessions) {
            if (socketSessions.size() >= ConnectionHubProtocol.MAX_SOCKET_SESSIONS) {
                writeStatus(output, 503, "Busy", "text/plain; charset=utf-8", new byte[0]);
                return;
            }
        }
        String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                        .digest((key + ACCEPT_GUID).getBytes(StandardCharsets.US_ASCII)));
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + securityHeaders()
                + "\r\n";
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        socket.setSoTimeout(10_000);
        Frame authenticationFrame = readFrame(input);
        if (authenticationFrame.opcode != 1 || authenticationRateLimited()) {
            noteAuthenticationFailure();
            throw new SecurityException("socket_authentication_required");
        }
        JSONObject authentication = new JSONObject(
                new String(authenticationFrame.payload, StandardCharsets.UTF_8));
        if (!ConnectionHubProtocol.SOCKET_AUTHENTICATE_SCHEMA.equals(
                    authentication.optString("$schema", ""))
                || !"authenticate".equals(authentication.optString("type", ""))) {
            noteAuthenticationFailure();
            throw new SecurityException("socket_authentication_invalid");
        }
        ConnectionHubRuntime.requireExactKeys(authentication,
                new String[] {"$schema", "type", "session"},
                new String[0]);
        String cookie = authentication.optString("session", "");
        ConnectionHubStateStore.SessionProjection sessionProjection;
        try {
            sessionProjection = runtime.replaceTransport(cookie);
        } catch (RuntimeException rejected) {
            noteAuthenticationFailure();
            throw rejected;
        }
        socket.setSoTimeout(30_000);
        final SocketSession session = new SocketSession(
                socket,
                output,
                sessionProjection.logicalSessionId,
                sessionProjection.transportEpoch);
        synchronized (socketSessions) { socketSessions.add(session); }
        try {
            session.write(ConnectionHubProtocol.socketAuthenticationReceipt(
                    sessionProjection.transportEpoch));
            session.write(runtime.snapshotEvent());
            while (!socket.isClosed()) {
                Frame frame = readFrame(input);
                if (frame.opcode == 8) { return; }
                if (frame.opcode == 9) { session.writeFrame(10, frame.payload); continue; }
                if (frame.opcode != 1) { throw new IOException("text frames only"); }
                final JSONObject command = new JSONObject(new String(frame.payload, StandardCharsets.UTF_8));
                runtime.handleCommand(
                        cookie,
                        session.transportEpoch,
                        command,
                        new ConnectionHubRuntime.CommandReceiptSink() {
                    @Override public void onReceipt(JSONObject receipt) {
                        try { session.write(receipt); } catch (IOException ignored) { session.close(); }
                    }
                });
            }
        } finally {
            synchronized (socketSessions) { socketSessions.remove(session); }
            session.close();
        }
    }

    private static boolean hasSameOrigin(HttpRequest request) {
        String host = request.headers.get("host");
        String origin = request.headers.get("origin");
        if (host == null || origin == null || host.indexOf('/') >= 0 || host.indexOf('\\') >= 0) {
            return false;
        }
        return origin.equals("http://" + host);
    }

    private static boolean headerContainsToken(String value, String expected) {
        if (value == null) { return false; }
        for (String token : value.split(",")) {
            if (expected.equalsIgnoreCase(token.trim())) { return true; }
        }
        return false;
    }

    private synchronized boolean authenticationRateLimited() {
        long now = System.currentTimeMillis();
        if (socketAuthWindowStartedMs == 0
                || now - socketAuthWindowStartedMs > ConnectionHubProtocol.AUTH_RATE_WINDOW_MS) {
            socketAuthWindowStartedMs = now;
            socketAuthFailures = 0;
        }
        return socketAuthFailures >= ConnectionHubProtocol.MAX_SOCKET_AUTH_FAILURES_PER_WINDOW;
    }

    private synchronized void noteAuthenticationFailure() {
        authenticationRateLimited();
        socketAuthFailures += 1;
    }

    @Override
    public void broadcast(JSONObject event) {
        List<SocketSession> copy;
        synchronized (socketSessions) { copy = new ArrayList<>(socketSessions); }
        for (SocketSession session : copy) {
            try { session.write(event); }
            catch (IOException failure) {
                session.close();
                synchronized (socketSessions) { socketSessions.remove(session); }
            }
        }
    }

    @Override
    public void closeLogicalSession(String logicalSessionId, String reason) {
        List<SocketSession> copy;
        synchronized (socketSessions) { copy = new ArrayList<>(socketSessions); }
        for (SocketSession session : copy) {
            if (session.logicalSessionId.equals(logicalSessionId)) {
                session.close();
                synchronized (socketSessions) { socketSessions.remove(session); }
            }
        }
    }

    @Override
    public void closeAllSessions(String reason) {
        List<SocketSession> copy;
        synchronized (socketSessions) { copy = new ArrayList<>(socketSessions); }
        for (SocketSession session : copy) { session.close(); }
        synchronized (socketSessions) { socketSessions.clear(); }
    }

    public synchronized int localPort() {
        return serverSocket == null ? 0 : serverSocket.getLocalPort();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        try { if (serverSocket != null) { serverSocket.close(); } } catch (IOException ignored) {}
        synchronized (socketSessions) {
            for (SocketSession session : socketSessions) { session.close(); }
            socketSessions.clear();
        }
        clients.shutdownNow();
    }

    private static HttpRequest readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int state = 0;
        while (header.size() < ConnectionHubProtocol.MAX_HTTP_HEADER_BYTES) {
            int next = input.read();
            if (next < 0) { throw new EOFException(); }
            header.write(next);
            state = next == (state == 0 || state == 2 ? '\r' : '\n') ? state + 1 : (next == '\r' ? 1 : 0);
            if (state == 4) { break; }
        }
        if (state != 4) { throw new IOException("header too large"); }
        String raw = new String(header.toByteArray(), StandardCharsets.ISO_8859_1);
        String[] lines = raw.split("\\r\\n");
        String[] first = lines[0].split(" ");
        if (first.length != 3 || !"HTTP/1.1".equals(first[2])) { throw new IOException("bad request line"); }
        Map<String, String> headers = new HashMap<>();
        for (int index = 1; index < lines.length; index += 1) {
            int colon = lines[index].indexOf(':');
            if (colon > 0) {
                String name = lines[index].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                if (headers.put(name, lines[index].substring(colon + 1).trim()) != null) {
                    throw new IOException("duplicate header");
                }
            }
        }
        int length = 0;
        if (headers.containsKey("content-length")) {
            length = Integer.parseInt(headers.get("content-length"));
        }
        if (length < 0 || length > ConnectionHubProtocol.MAX_HTTP_BODY_BYTES) {
            throw new IOException("body too large");
        }
        byte[] body = readExact(input, length);
        String target = first[1];
        int question = target.indexOf('?');
        String path = question < 0 ? target : target.substring(0, question);
        Map<String, String> query = parseQuery(question < 0 ? "" : target.substring(question + 1));
        return new HttpRequest(first[0], path, headers, query, new String(body, StandardCharsets.UTF_8));
    }

    private static Map<String, String> parseQuery(String raw) throws IOException {
        Map<String, String> output = new HashMap<>();
        if (raw.isEmpty()) { return output; }
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2) { throw new IOException("bad query"); }
            String name = URLDecoder.decode(pair[0], "UTF-8");
            String value = URLDecoder.decode(pair[1], "UTF-8");
            if (output.put(name, value) != null) { throw new IOException("duplicate query"); }
        }
        return output;
    }

    private static Frame readFrame(InputStream input) throws IOException {
        int first = input.read(); int second = input.read();
        if (first < 0 || second < 0) { throw new EOFException(); }
        if ((first & 0x80) == 0 || (first & 0x70) != 0 || (second & 0x80) == 0) {
            throw new IOException("invalid websocket framing");
        }
        long length = second & 0x7f;
        if (length == 126) {
            byte[] extended = readExact(input, 2);
            length = ByteBuffer.wrap(new byte[] {0, 0, extended[0], extended[1]}).getInt() & 0xffffffffL;
        } else if (length == 127) {
            byte[] extended = readExact(input, 8);
            length = ByteBuffer.wrap(extended).getLong();
        }
        if (length < 0 || length > ConnectionHubProtocol.MAX_SOCKET_FRAME_BYTES) {
            throw new IOException("websocket payload too large");
        }
        byte[] mask = readExact(input, 4);
        byte[] payload = readExact(input, (int) length);
        for (int index = 0; index < payload.length; index += 1) {
            payload[index] = (byte) (payload[index] ^ mask[index % 4]);
        }
        return new Frame(first & 0xf, payload);
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] output = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(output, offset, length - offset);
            if (count < 0) { throw new EOFException(); }
            offset += count;
        }
        return output;
    }

    private static void writeJson(OutputStream output, int code, JSONObject value) throws IOException {
        writeStatus(output, code, code == 200 ? "OK" : "Forbidden",
                "application/json; charset=utf-8",
                value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeStatus(
            OutputStream output, int code, String reason, String contentType, byte[] body)
            throws IOException {
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + securityHeaders()
                + "\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static String securityHeaders() {
        return "Cache-Control: no-store\r\n"
                + "Content-Security-Policy: default-src 'self'; connect-src 'self'; "
                + "script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "X-Frame-Options: DENY\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Permissions-Policy: camera=(), microphone=(), geolocation=()\r\n"
                + "X-Rusty-Transport-Classification: trusted_lan_experimental\r\n"
                + "X-Rusty-Confidentiality: none\r\n";
    }

    private static final class SocketSession implements Closeable {
        private final OutputStream output;
        private final Socket socket;
        private final String logicalSessionId;
        private final long transportEpoch;
        private boolean closed;
        SocketSession(Socket socket, OutputStream output, String logicalSessionId, long transportEpoch) {
            this.socket = socket;
            this.output = output;
            this.logicalSessionId = logicalSessionId;
            this.transportEpoch = transportEpoch;
        }
        synchronized void write(JSONObject value) throws IOException {
            try {
                JSONObject bound = new JSONObject(value.toString());
                bound.put("transport_epoch", transportEpoch);
                writeFrame(1, bound.toString().getBytes(StandardCharsets.UTF_8));
            } catch (org.json.JSONException error) {
                throw new IOException("invalid Hub event", error);
            }
        }
        synchronized void writeFrame(int opcode, byte[] payload) throws IOException {
            if (closed) { throw new IOException("session closed"); }
            output.write(0x80 | opcode);
            if (payload.length < 126) { output.write(payload.length); }
            else {
                output.write(126); output.write((payload.length >>> 8) & 0xff); output.write(payload.length & 0xff);
            }
            output.write(payload); output.flush();
        }
        @Override public synchronized void close() {
            closed = true;
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static final class Frame {
        final int opcode; final byte[] payload;
        Frame(int opcode, byte[] payload) { this.opcode = opcode; this.payload = payload; }
    }

    private static final class HttpRequest {
        final String method; final String path; final Map<String, String> headers;
        final Map<String, String> query; final String body;
        HttpRequest(String method, String path, Map<String, String> headers,
                Map<String, String> query, String body) {
            this.method = method; this.path = path; this.headers = headers;
            this.query = query; this.body = body;
        }
    }
}
