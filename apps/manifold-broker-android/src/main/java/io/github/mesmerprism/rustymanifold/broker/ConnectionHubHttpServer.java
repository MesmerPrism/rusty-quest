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
import java.net.SocketTimeoutException;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
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
    private final Semaphore socketSlots = new Semaphore(ConnectionHubProtocol.MAX_SOCKET_SESSIONS);
    private final ScheduledExecutorService writeWatchdog =
            Executors.newSingleThreadScheduledExecutor();
    private final List<SocketSession> socketSessions = new CopyOnWriteArrayList<>();
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
                socket.setSoTimeout(500);
                try {
                    clients.execute(new Runnable() {
                        @Override public void run() {
                            try { handle(socket); }
                            finally { clientSlots.release(); }
                        }
                    });
                } catch (RejectedExecutionException rejected) {
                    clientSlots.release();
                    socket.close();
                }
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
            HttpRequest request = readRequest(
                    input,
                    deadlineAfterMs(ConnectionHubProtocol.HTTP_HEADER_DEADLINE_MS));
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
        if (!socketSlots.tryAcquire()) {
            writeStatus(output, 503, "Busy", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        try {
            handleAdmittedSocket(socket, request, input, output);
        } finally {
            socketSlots.release();
        }
    }

    private void handleAdmittedSocket(
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
        Frame authenticationFrame = readFrame(
                input,
                deadlineAfterMs(ConnectionHubProtocol.SOCKET_AUTH_DEADLINE_MS),
                false);
        if (authenticationFrame.opcode != 1 || authenticationRateLimited()) {
            noteAuthenticationFailure();
            throw new SecurityException("socket_authentication_required");
        }
        JSONObject authentication = new JSONObject(
                new String(authenticationFrame.payload, StandardCharsets.UTF_8));
        String authenticationSchema = authentication.optString("$schema", "");
        final boolean protocolV2 =
                ConnectionHubProtocol.SOCKET_AUTHENTICATE_SCHEMA_V2.equals(authenticationSchema);
        if (!(protocolV2 || ConnectionHubProtocol.SOCKET_AUTHENTICATE_SCHEMA.equals(authenticationSchema))
                || !"authenticate".equals(authentication.optString("type", ""))) {
            noteAuthenticationFailure();
            throw new SecurityException("socket_authentication_invalid");
        }
        ConnectionHubRuntime.requireExactKeys(authentication,
                new String[] {"$schema", "type", "session"},
                new String[0]);
        String cookie = authentication.optString("session", "");
        final ConnectionHubStateStore.SessionProjection sessionProjection;
        final SocketSession session;
        try {
            /*
             * Runtime authority, epoch replacement, and socket installation
             * share one ordered critical section. Otherwise an older handshake
             * can finish after a newer handshake, or a concurrent revoke can
             * miss a not-yet-subscribed replacement socket.
             */
            synchronized (runtime) {
                synchronized (socketSessions) {
                    sessionProjection = runtime.replaceTransport(cookie);
                    for (SocketSession existing : socketSessions) {
                        if (existing.logicalSessionId.equals(sessionProjection.logicalSessionId)
                                && !isNewerTransportEpoch(
                                        sessionProjection.transportEpoch,
                                        existing.transportEpoch)) {
                            throw new SecurityException("stale_transport_install_rejected");
                        }
                    }
                    session = new SocketSession(
                            socket,
                            output,
                            sessionProjection.logicalSessionId,
                            sessionProjection.transportEpoch,
                            writeWatchdog);
                    for (SocketSession existing : new ArrayList<>(socketSessions)) {
                        if (existing.logicalSessionId.equals(sessionProjection.logicalSessionId)) {
                            existing.close();
                            socketSessions.remove(existing);
                        }
                    }
                    /*
                     * Queue authentication and the baseline before exposing the
                     * socket. The registry boundary makes a concurrent provider
                     * mutation part of the snapshot or a later broadcast.
                     */
                    session.enqueue(protocolV2
                            ? ConnectionHubProtocol.socketAuthenticationReceiptV2(
                                    sessionProjection.transportEpoch,
                                    sessionProjection.nextExternalRequestSequence,
                                    sessionProjection.expiresAtMs)
                            : ConnectionHubProtocol.socketAuthenticationReceipt(
                                    sessionProjection.transportEpoch));
                    installBaselineAndSubscribe(
                            runtime.registryLock(),
                            new BaselineSubscription() {
                                @Override public void enqueueBaseline() throws IOException {
                                    session.enqueue(runtime.snapshotEvent());
                                }
                                @Override public void subscribe() {
                                    socketSessions.add(session);
                                }
                            });
                }
            }
        } catch (RuntimeException rejected) {
            noteAuthenticationFailure();
            throw rejected;
        }
        try {
            while (!socket.isClosed()) {
                Frame frame = readFrame(input, Long.MAX_VALUE, true);
                if (frame.opcode == 8) { return; }
                if (frame.opcode == 9) { session.enqueueFrame(10, frame.payload); continue; }
                if (frame.opcode == 10) { continue; }
                if (frame.opcode != 1) { throw new IOException("text frames only"); }
                final String rawFrame = new String(frame.payload, StandardCharsets.UTF_8);
                final JSONObject message;
                try {
                    message = new JSONObject(rawFrame);
                } catch (Exception malformed) {
                    if (protocolV2) {
                        session.enqueueTerminal(runtime.protocolErrorV2(cookie, "invalid_json"));
                        return;
                    }
                    throw malformed;
                }
                String messageSchema = message.optString("$schema", "");
                if (protocolV2 && ConnectionHubProtocol.KEEPALIVE_SCHEMA_V2.equals(messageSchema)) {
                    try {
                        ConnectionHubRuntime.validateV2KeepaliveFrame(message, rawFrame);
                    } catch (Exception invalid) {
                        session.enqueueTerminal(runtime.protocolErrorV2(cookie, "invalid_keepalive"));
                        return;
                    }
                    session.enqueue(runtime.handleKeepalive(
                            cookie, session.transportEpoch, message, rawFrame));
                } else if ((protocolV2
                                && ConnectionHubProtocol.SURFACE_COMMAND_SCHEMA_V2.equals(messageSchema))
                        || (!protocolV2
                                && ConnectionHubProtocol.SURFACE_COMMAND_SCHEMA.equals(messageSchema))) {
                    if (protocolV2) {
                        try {
                            ConnectionHubRuntime.validateV2CommandFrame(message, rawFrame);
                        } catch (Exception invalid) {
                            session.enqueueTerminal(runtime.protocolErrorV2(cookie, "invalid_command"));
                            return;
                        }
                    }
                    runtime.handleCommand(
                            cookie,
                            session.transportEpoch,
                            message,
                            rawFrame,
                            protocolV2,
                            new ConnectionHubRuntime.CommandReceiptSink() {
                        @Override public void onReceipt(JSONObject receipt) {
                            try { session.enqueue(receipt); }
                            catch (IOException ignored) { session.close(); }
                        }
                    });
                } else if (protocolV2) {
                    session.enqueueTerminal(runtime.protocolErrorV2(cookie, "unsupported_message"));
                    return;
                } else {
                    throw new SecurityException("legacy_socket_message_invalid");
                }
            }
        } finally {
            synchronized (socketSessions) { socketSessions.remove(session); }
            session.close();
        }
    }

    static boolean isNewerTransportEpoch(long candidate, long installed) {
        return candidate > installed;
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
        /*
         * Provider callbacks enter here while the registry mutation lock is
         * still held. A copy-on-write session set avoids taking the handshake
         * lock in that direction; handshakes may safely take the session lock
         * and then build one registry-consistent baseline snapshot.
         */
        for (SocketSession session : socketSessions) {
            try { session.enqueue(event); }
            catch (IOException failure) {
                session.close();
                socketSessions.remove(session);
            }
        }
    }

    static long bindOutboundSurfaceRevision(JSONObject value, long previousRevision)
            throws IOException {
        if (!value.has("surface_revision")) {
            return previousRevision;
        }
        final long revision;
        try {
            revision = value.getLong("surface_revision");
        } catch (org.json.JSONException malformed) {
            throw new IOException("invalid Hub surface revision", malformed);
        }
        if (revision < 0) {
            throw new IOException("invalid Hub surface revision");
        }
        String type = value.optString("type", "");
        boolean projectionEvent = "surface_snapshot".equals(type)
                || "surface_available".equals(type)
                || "surface_removed".equals(type)
                || "surface_state".equals(type);
        if (projectionEvent) {
            if (previousRevision >= 0 && revision < previousRevision) {
                throw new IOException("Hub lifecycle surface revision regressed");
            }
            return revision;
        }
        if (!("command_receipt".equals(type)
                || "keepalive_receipt".equals(type)
                || "protocol_error".equals(type))
                || previousRevision < 0) {
            throw new IOException("Hub lifecycle surface revision regressed");
        }
        if (revision > previousRevision) {
            throw new IOException("Hub control receipt advanced beyond queued projection");
        }
        if (revision == previousRevision) {
            return revision;
        }
        try {
            /*
             * Control receipts sample the registry before they enter the
             * per-socket queue. A provider lifecycle delta may reach that
             * queue first. Bind a delayed non-projecting receipt to the
             * already queued watermark; never rewrite a lifecycle delta.
             */
            value.put("surface_revision", previousRevision);
        } catch (org.json.JSONException impossible) {
            throw new IOException("could not bind Hub surface revision", impossible);
        }
        return previousRevision;
    }

    interface BaselineSubscription {
        void enqueueBaseline() throws IOException;
        void subscribe();
    }

    static void installBaselineAndSubscribe(
            Object registryLock,
            BaselineSubscription subscription) throws IOException {
        synchronized (registryLock) {
            /*
             * A mutation is either included in the baseline or broadcasts
             * after the COW subscription becomes visible. There is no gap.
             */
            subscription.enqueueBaseline();
            subscription.subscribe();
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
        writeWatchdog.shutdownNow();
    }

    static HttpRequest readRequest(InputStream input, long deadlineNanos) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int state = 0;
        while (header.size() < ConnectionHubProtocol.MAX_HTTP_HEADER_BYTES) {
            int next = readByte(input, deadlineNanos);
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
        byte[] body = readExact(
                input,
                length,
                deadlineAfterMs(ConnectionHubProtocol.HTTP_HEADER_DEADLINE_MS));
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

    static Frame readFrame(
            InputStream input,
            long firstByteDeadlineNanos,
            boolean allowIdleBeforeFrame) throws IOException {
        int first = allowIdleBeforeFrame
                ? readIdleByte(input)
                : readByte(input, firstByteDeadlineNanos);
        long frameDeadline = deadlineAfterMs(
                ConnectionHubProtocol.SOCKET_FRAME_ASSEMBLY_DEADLINE_MS);
        if (firstByteDeadlineNanos != Long.MAX_VALUE) {
            frameDeadline = Math.min(frameDeadline, firstByteDeadlineNanos);
        }
        int second = readByte(input, frameDeadline);
        if (first < 0 || second < 0) { throw new EOFException(); }
        if ((first & 0x80) == 0 || (first & 0x70) != 0 || (second & 0x80) == 0) {
            throw new IOException("invalid websocket framing");
        }
        long length = second & 0x7f;
        if (length == 126) {
            byte[] extended = readExact(input, 2, frameDeadline);
            length = ByteBuffer.wrap(new byte[] {0, 0, extended[0], extended[1]}).getInt() & 0xffffffffL;
            if (length < 126) throw new IOException("non-minimal websocket length");
        } else if (length == 127) {
            byte[] extended = readExact(input, 8, frameDeadline);
            length = ByteBuffer.wrap(extended).getLong();
            if (length <= 0xffff) throw new IOException("non-minimal websocket length");
        }
        int opcode = first & 0xf;
        if ((opcode & 0x8) != 0 && length > 125) {
            throw new IOException("websocket control payload too large");
        }
        if (length < 0 || length > ConnectionHubProtocol.MAX_SOCKET_FRAME_BYTES) {
            throw new IOException("websocket payload too large");
        }
        byte[] mask = readExact(input, 4, frameDeadline);
        byte[] payload = readExact(input, (int) length, frameDeadline);
        for (int index = 0; index < payload.length; index += 1) {
            payload[index] = (byte) (payload[index] ^ mask[index % 4]);
        }
        return new Frame(opcode, payload);
    }

    private static byte[] readExact(InputStream input, int length, long deadlineNanos)
            throws IOException {
        byte[] output = new byte[length];
        int offset = 0;
        while (offset < length) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new SocketTimeoutException("absolute read deadline exceeded");
            }
            int count;
            try {
                count = input.read(output, offset, length - offset);
            } catch (SocketTimeoutException timeout) {
                continue;
            }
            if (count < 0) { throw new EOFException(); }
            offset += count;
        }
        return output;
    }

    private static int readByte(InputStream input, long deadlineNanos) throws IOException {
        while (System.nanoTime() < deadlineNanos) {
            try { return input.read(); }
            catch (SocketTimeoutException timeout) { /* absolute deadline remains authoritative */ }
        }
        throw new SocketTimeoutException("absolute read deadline exceeded");
    }

    private static int readIdleByte(InputStream input) throws IOException {
        while (true) {
            try { return input.read(); }
            catch (SocketTimeoutException timeout) { /* authenticated sockets may remain idle */ }
        }
    }

    static long deadlineAfterMs(long durationMs) {
        long now = System.nanoTime();
        long delta = TimeUnit.MILLISECONDS.toNanos(durationMs);
        return now > Long.MAX_VALUE - delta ? Long.MAX_VALUE : now + delta;
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
        private final ScheduledExecutorService watchdog;
        private final ArrayBlockingQueue<OutboundFrame> outbound =
                new ArrayBlockingQueue<>(ConnectionHubProtocol.MAX_SOCKET_OUTBOUND_QUEUE);
        private final AtomicBoolean sessionClosed = new AtomicBoolean();
        private final Thread writerThread;
        private long lastEnqueuedSurfaceRevision = -1L;
        SocketSession(
                Socket socket,
                OutputStream output,
                String logicalSessionId,
                long transportEpoch,
                ScheduledExecutorService watchdog) {
            this.socket = socket;
            this.output = output;
            this.logicalSessionId = logicalSessionId;
            this.transportEpoch = transportEpoch;
            this.watchdog = watchdog;
            this.writerThread = new Thread(new Runnable() {
                @Override public void run() { writeLoop(); }
            }, "rusty-connection-hub-writer");
            this.writerThread.start();
        }
        synchronized void enqueue(JSONObject value) throws IOException {
            try {
                JSONObject bound = new JSONObject(value.toString());
                bound.put("transport_epoch", transportEpoch);
                lastEnqueuedSurfaceRevision = bindOutboundSurfaceRevision(
                        bound, lastEnqueuedSurfaceRevision);
                enqueueFrame(1, bound.toString().getBytes(StandardCharsets.UTF_8));
            } catch (org.json.JSONException error) {
                throw new IOException("invalid Hub event", error);
            }
        }
        synchronized void enqueueTerminal(JSONObject value) throws IOException {
            java.util.concurrent.CountDownLatch completion =
                    new java.util.concurrent.CountDownLatch(1);
            try {
                JSONObject bound = new JSONObject(value.toString());
                bound.put("transport_epoch", transportEpoch);
                lastEnqueuedSurfaceRevision = bindOutboundSurfaceRevision(
                        bound, lastEnqueuedSurfaceRevision);
                byte[] payload = bound.toString().getBytes(StandardCharsets.UTF_8);
                validateOutboundFrame(1, payload.length);
                if (sessionClosed.get()
                        || !outbound.offer(new OutboundFrame(1, payload, completion))) {
                    throw new IOException("bounded outbound queue unavailable");
                }
                completion.await(
                        ConnectionHubProtocol.SOCKET_WRITE_DEADLINE_MS,
                        TimeUnit.MILLISECONDS);
            } catch (org.json.JSONException malformed) {
                throw new IOException("invalid Hub terminal event", malformed);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("terminal websocket write interrupted", interrupted);
            }
        }
        void enqueueFrame(int opcode, byte[] payload) throws IOException {
            validateOutboundFrame(opcode, payload.length);
            if (sessionClosed.get() || !outbound.offer(new OutboundFrame(opcode, payload))) {
                close();
                throw new IOException("bounded outbound queue unavailable");
            }
        }
        private void writeLoop() {
            try {
                while (!sessionClosed.get()) {
                    OutboundFrame frame = outbound.take();
                    ScheduledFuture<?> deadline = watchdog.schedule(new Runnable() {
                        @Override public void run() { close(); }
                    }, ConnectionHubProtocol.SOCKET_WRITE_DEADLINE_MS, TimeUnit.MILLISECONDS);
                    try { writeFrameDirect(frame.opcode, frame.payload); }
                    finally {
                        deadline.cancel(false);
                        if (frame.completion != null) { frame.completion.countDown(); }
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException failure) {
                close();
            } catch (RuntimeException failure) {
                close();
            }
        }
        private void writeFrameDirect(int opcode, byte[] payload) throws IOException {
            validateOutboundFrame(opcode, payload.length);
            output.write(0x80 | opcode);
            if (payload.length < 126) { output.write(payload.length); }
            else if (payload.length <= 0xffff) {
                output.write(126); output.write((payload.length >>> 8) & 0xff); output.write(payload.length & 0xff);
            } else {
                output.write(127);
                long length = payload.length;
                for (int shift = 56; shift >= 0; shift -= 8) {
                    output.write((int) ((length >>> shift) & 0xff));
                }
            }
            output.write(payload); output.flush();
        }
        @Override public void close() {
            if (!sessionClosed.compareAndSet(false, true)) return;
            writerThread.interrupt();
            outbound.clear();
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    static void validateOutboundFrame(int opcode, int payloadLength) throws IOException {
        if (payloadLength < 0
                || payloadLength > ConnectionHubProtocol.MAX_SOCKET_OUTBOUND_FRAME_BYTES) {
            throw new IOException("outbound websocket payload too large");
        }
        if ((opcode & 0x8) != 0 && payloadLength > 125) {
            throw new IOException("outbound websocket control payload too large");
        }
    }

    private static final class OutboundFrame {
        final int opcode;
        final byte[] payload;
        OutboundFrame(int opcode, byte[] payload) {
            this(opcode, payload, null);
        }
        OutboundFrame(
                int opcode,
                byte[] payload,
                java.util.concurrent.CountDownLatch completion) {
            this.opcode = opcode;
            this.payload = payload.clone();
            this.completion = completion;
        }
        final java.util.concurrent.CountDownLatch completion;
    }

    static final class Frame {
        final int opcode; final byte[] payload;
        Frame(int opcode, byte[] payload) { this.opcode = opcode; this.payload = payload; }
    }

    static final class HttpRequest {
        final String method; final String path; final Map<String, String> headers;
        final Map<String, String> query; final String body;
        HttpRequest(String method, String path, Map<String, String> headers,
                Map<String, String> query, String body) {
            this.method = method; this.path = path; this.headers = headers;
            this.query = query; this.body = body;
        }
    }
}
