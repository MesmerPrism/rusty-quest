package io.github.mesmerprism.rustymanifold.broker;

import io.github.mesmerprism.rustyquest.broker_transport.BoundedWebSocketSession;
import io.github.mesmerprism.rustyquest.broker_transport.DeadlineInputStream;
import io.github.mesmerprism.rustyquest.broker_transport.Rfc6455Codec;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
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

    public interface DiagnosticSink {
        void onStatus(String status, String reason);
    }

    public static final class Asset {
        public final String contentType;
        public final byte[] bytes;
        public Asset(String contentType, byte[] bytes) {
            this.contentType = contentType;
            this.bytes = bytes.clone();
        }
    }

    private final ConnectionHubRuntime runtime;
    private final AssetLoader assetLoader;
    private final DiagnosticSink diagnostics;
    private final ExecutorService clients = Executors.newFixedThreadPool(
            ConnectionHubProtocol.MAX_HTTP_CLIENTS);
    private final Semaphore clientSlots = new Semaphore(ConnectionHubProtocol.MAX_HTTP_CLIENTS);
    private final Semaphore socketSlots = new Semaphore(ConnectionHubProtocol.MAX_SOCKET_SESSIONS);
    private final List<SocketSession> socketSessions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private long socketAuthWindowStartedMs;
    private int socketAuthFailures;

    public ConnectionHubHttpServer(ConnectionHubRuntime runtime, AssetLoader assetLoader) {
        this(runtime, assetLoader, new DiagnosticSink() {
            @Override public void onStatus(String status, String reason) {}
        });
    }

    public ConnectionHubHttpServer(
            ConnectionHubRuntime runtime,
            AssetLoader assetLoader,
            DiagnosticSink diagnostics) {
        this.runtime = runtime;
        this.assetLoader = assetLoader;
        this.diagnostics = diagnostics;
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
        if (!hasSameOrigin(request)) {
            writeStatus(output, 400, "Bad Request", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        final String accept;
        try {
            accept = Rfc6455Codec.serverAccept("GET", "HTTP/1.1", request.headers);
        } catch (IOException invalidUpgrade) {
            writeStatus(output, 400, "Bad Request", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + securityHeaders()
                + "\r\n";
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        Rfc6455Codec.Frame authenticationFrame = readSocketFrame(
                input, deadlineAfterMs(ConnectionHubProtocol.SOCKET_AUTH_DEADLINE_MS));
        if (!authenticationFrame.fin
                || authenticationFrame.opcode != Rfc6455Codec.OPCODE_TEXT
                || authenticationRateLimited()) {
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
                            diagnostics);
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
                final Rfc6455Codec.Frame frame;
                try {
                    frame = readSocketFrame(
                            input,
                            deadlineAfterMs(ConnectionHubProtocol.SOCKET_POLL_INTERVAL_MS));
                } catch (SocketTimeoutException timeout) {
                    String reason = timeout.getMessage();
                    if (reason != null && reason.contains("first-byte")) {
                        session.tick(System.nanoTime());
                        continue;
                    }
                    throw timeout;
                }
                Rfc6455Codec.Event event = session.receive(frame, System.nanoTime());
                if (event.kind == Rfc6455Codec.Event.Kind.NONE
                        || event.kind == Rfc6455Codec.Event.Kind.PING
                        || event.kind == Rfc6455Codec.Event.Kind.PONG) {
                    continue;
                }
                if (event.kind == Rfc6455Codec.Event.Kind.CLOSE) {
                    session.awaitClosed(ConnectionHubProtocol.SOCKET_WRITE_DEADLINE_MS);
                    return;
                }
                if (event.kind != Rfc6455Codec.Event.Kind.TEXT) {
                    throw new IOException("text messages only");
                }
                final String rawFrame = event.text;
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
                            try {
                                session.enqueue(receipt);
                                diagnostics.onStatus("command_receipt_enqueued", "none");
                            } catch (IOException failure) {
                                diagnostics.onStatus(
                                        "command_receipt_enqueue_failed",
                                        enqueueFailureReason(failure));
                                session.close();
                            }
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

    static String enqueueFailureReason(IOException failure) {
        String message = failure == null ? "" : failure.getMessage();
        if (message == null) { return "io_failure"; }
        if (message.contains("surface revision")) { return "surface_revision"; }
        if (message.contains("outbound queue")) { return "outbound_queue"; }
        if (message.contains("payload too large")) { return "payload_too_large"; }
        return "io_failure";
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

    private static Rfc6455Codec.Frame readSocketFrame(
            InputStream input,
            long firstByteDeadlineNanos) throws IOException {
        return Rfc6455Codec.readFrame(
                new DeadlineInputStream(
                        input,
                        firstByteDeadlineNanos,
                        TimeUnit.MILLISECONDS.toNanos(
                                ConnectionHubProtocol.SOCKET_FRAME_ASSEMBLY_DEADLINE_MS)),
                true,
                ConnectionHubProtocol.MAX_SOCKET_FRAME_BYTES);
    }

    /** Compatibility test seam; product sockets use the stateful shared session path above. */
    static Frame readFrame(
            InputStream input,
            long firstByteDeadlineNanos,
            boolean allowIdleBeforeFrame) throws IOException {
        long firstDeadline = allowIdleBeforeFrame ? Long.MAX_VALUE : firstByteDeadlineNanos;
        Rfc6455Codec.Frame decoded = readSocketFrame(input, firstDeadline);
        if (!decoded.fin) {
            throw new IOException("compatibility frame seam requires one complete frame");
        }
        return new Frame(decoded.opcode, decoded.payload);
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
        private final Socket socket;
        private final String logicalSessionId;
        private final long transportEpoch;
        private final DiagnosticSink diagnostics;
        private final BoundedWebSocketSession transport;
        private long lastEnqueuedSurfaceRevision = -1L;

        SocketSession(
                Socket socket,
                OutputStream output,
                String logicalSessionId,
                long transportEpoch,
                DiagnosticSink diagnostics) {
            this.socket = socket;
            this.logicalSessionId = logicalSessionId;
            this.transportEpoch = transportEpoch;
            this.diagnostics = diagnostics;
            this.transport = new BoundedWebSocketSession(
                    "rusty-connection-hub-writer",
                    new BoundedWebSocketSession.FrameWriter() {
                        @Override public void write(boolean fin, int opcode, byte[] payload)
                                throws IOException {
                            validateOutboundFrame(opcode, payload.length);
                            Rfc6455Codec.writeFrame(output, fin, opcode, payload, null);
                        }

                        @Override public void close() throws IOException {
                            socket.close();
                        }
                    },
                    new BoundedWebSocketSession.Limits(
                            ConnectionHubProtocol.MAX_SOCKET_OUTBOUND_QUEUE,
                            ConnectionHubProtocol.MAX_SOCKET_OUTBOUND_QUEUE_BYTES,
                            ConnectionHubProtocol.MAX_SOCKET_FRAME_BYTES,
                            ConnectionHubProtocol.MAX_SOCKET_OUTBOUND_FRAME_BYTES,
                            TimeUnit.MILLISECONDS.toNanos(
                                    ConnectionHubProtocol.SOCKET_PING_INTERVAL_MS),
                            TimeUnit.MILLISECONDS.toNanos(
                                    ConnectionHubProtocol.SOCKET_PONG_DEADLINE_MS),
                            TimeUnit.MILLISECONDS.toNanos(
                                    ConnectionHubProtocol.SOCKET_IDLE_DEADLINE_MS)),
                    System.nanoTime(),
                    new BoundedWebSocketSession.CloseListener() {
                        @Override public void onClosed(
                                BoundedWebSocketSession.CloseReason reason) {
                            diagnostics.onStatus("websocket_session_closed", reason.name());
                        }
                    },
                    new BoundedWebSocketSession.TelemetrySink() {
                        @Override public void onEvent(BoundedWebSocketSession.Telemetry event) {
                            if (event.code == BoundedWebSocketSession.EventCode.QUEUE_SATURATED) {
                                diagnostics.onStatus("websocket_queue_saturated", "bounded");
                            } else if (event.code
                                    == BoundedWebSocketSession.EventCode.WRITE_FAILED) {
                                diagnostics.onStatus("websocket_write_failed", "io_failure");
                            }
                        }
                    });
        }

        synchronized void enqueue(JSONObject value) throws IOException {
            try {
                JSONObject bound = new JSONObject(value.toString());
                bound.put("transport_epoch", transportEpoch);
                lastEnqueuedSurfaceRevision = bindOutboundSurfaceRevision(
                        bound, lastEnqueuedSurfaceRevision);
                if ("command_receipt".equals(bound.optString("type", ""))
                        && !bound.optBoolean("accepted", false)) {
                    String rejection = bound.optString("status", "missing");
                    if (!rejection.matches("[a-z0-9_().:-]{1,192}")) {
                        rejection = "invalid_or_unbounded_status";
                    }
                    diagnostics.onStatus("command_receipt_rejected", rejection);
                }
                transport.sendText(
                        bound.toString(),
                        "command_receipt".equals(bound.optString("type", ""))
                                ? new Runnable() {
                                    @Override public void run() {
                                        diagnostics.onStatus("command_receipt_written", "none");
                                    }
                                }
                                : null);
            } catch (org.json.JSONException error) {
                throw new IOException("invalid Hub event", error);
            }
        }

        synchronized void enqueueTerminal(JSONObject value) throws IOException {
            try {
                JSONObject bound = new JSONObject(value.toString());
                bound.put("transport_epoch", transportEpoch);
                lastEnqueuedSurfaceRevision = bindOutboundSurfaceRevision(
                        bound, lastEnqueuedSurfaceRevision);
                if (!transport.sendTextAndAwait(
                        bound.toString(), ConnectionHubProtocol.SOCKET_WRITE_DEADLINE_MS)) {
                    close();
                    throw new IOException("terminal websocket write deadline exceeded");
                }
            } catch (org.json.JSONException malformed) {
                throw new IOException("invalid Hub terminal event", malformed);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("terminal websocket write interrupted", interrupted);
            }
        }

        Rfc6455Codec.Event receive(Rfc6455Codec.Frame frame, long nowNanos)
                throws IOException {
            return transport.receive(frame, nowNanos);
        }

        void tick(long nowNanos) throws IOException {
            transport.tick(nowNanos);
        }

        boolean awaitClosed(long timeoutMillis) throws InterruptedException {
            return transport.awaitClosed(timeoutMillis);
        }

        @Override public void close() {
            transport.close();
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
