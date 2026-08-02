package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal HTTP/WebSocket transport for the fixed controller assets.
 *
 * <p>Construction does not bind. Starting requires an already-enabled Manifold
 * pairing offer and always asks the OS for port 0. The source-only test gate uses
 * loopback; no LAN listener is exercised.
 */
public final class TrustedLocalHttpServer implements Closeable {
    public interface AssetProvider {
        Asset load(String path) throws IOException;
    }

    @FunctionalInterface
    public interface DiagnosticSink {
        void record(String phase, String failureKind);
    }

    public record Asset(String contentType, byte[] bytes) {
        public Asset {
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(bytes, "bytes");
        }
    }

    public record BoundEndpoint(String hostHeader, String origin, int port, InetAddress address) {}

    private static final String SESSION_COOKIE = "rq_session";
    private static final String WEB_SOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final LocalControlCoordinator coordinator;
    private final AssetProvider assets;
    private final DiagnosticSink diagnostics;
    private final ExecutorService connections =
            Executors.newFixedThreadPool(
                    TrustedLocalControlPolicy.MAX_CONCURRENT_CONNECTIONS,
                    runnable -> {
                        Thread thread = new Thread(runnable, "trusted-local-http-connection");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final CopyOnWriteArrayList<Socket> openSockets = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final Object malformedLock = new Object();
    private final LinkedHashMap<String, ArrayDeque<Instant>> malformedByRemote =
            new LinkedHashMap<>();
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile BoundEndpoint endpoint;
    private volatile ManifoldAuthorityPort.AccessMode accessMode;

    public TrustedLocalHttpServer(LocalControlCoordinator coordinator, AssetProvider assets) {
        this(coordinator, assets, (phase, failureKind) -> {});
    }

    public TrustedLocalHttpServer(
            LocalControlCoordinator coordinator,
            AssetProvider assets,
            DiagnosticSink diagnostics) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public synchronized BoundEndpoint start(
            ManifoldAuthorityPort.PairingOffer offer, InetAddress bindAddress) throws IOException {
        if (running.get()) {
            throw new IllegalStateException("listener is already running");
        }
        if (offer == null || !offer.enabled() || !offer.expiresAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("a live wearer-enabled Manifold offer is required");
        }
        Objects.requireNonNull(bindAddress, "bindAddress");
        if (!isTrustedBindAddress(bindAddress)) {
            throw new IllegalArgumentException(
                    "bind address must be loopback, private/site-local, link-local, or IPv6 unique-local");
        }
        ServerSocket socket = new ServerSocket(0, 8, bindAddress);
        int port = socket.getLocalPort();
        String address = bindAddress.getHostAddress();
        String host = address.contains(":") ? "[" + address + "]:" + port : address + ":" + port;
        this.serverSocket = socket;
        this.endpoint = new BoundEndpoint(host, "http://" + host, port, bindAddress);
        this.accessMode = offer.accessMode();
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "trusted-local-http-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return endpoint;
    }

    static boolean isTrustedBindAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()) {
            return true;
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    public boolean isRunning() {
        return running.get();
    }

    public BoundEndpoint endpoint() {
        BoundEndpoint value = endpoint;
        if (value == null) {
            throw new IllegalStateException("listener is disabled");
        }
        return value;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(TrustedLocalControlPolicy.HTTP_READ_TIMEOUT_MS);
                if (!tryAcquireConnectionSlot()) {
                    socket.close();
                    continue;
                }
                openSockets.add(socket);
                try {
                    connections.execute(
                            () -> {
                                try {
                                    handle(socket);
                                } finally {
                                    releaseConnectionSlot();
                                }
                            });
                } catch (RuntimeException error) {
                    openSockets.remove(socket);
                    releaseConnectionSlot();
                    socket.close();
                }
            } catch (IOException error) {
                if (running.get()) {
                    close();
                }
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            String remoteAddress = socket.getInetAddress().getHostAddress();
            HttpRequest request;
            try {
                request = readRequest(input);
            } catch (IOException error) {
                recordMalformedAttempt(remoteAddress, Instant.now());
                return;
            }
            if (request == null) {
                return;
            }
            if (malformedLimitReached(remoteAddress, Instant.now())) {
                writeResponse(
                        output,
                        429,
                        "application/json; charset=utf-8",
                        error("malformed_request_rate_limited"),
                        Map.of());
                return;
            }
            BoundEndpoint bound = endpoint();
            if (!bound.hostHeader().equals(request.header("host"))) {
                writeResponse(output, 421, "text/plain; charset=utf-8", "misdirected request", Map.of());
                return;
            }
            String origin = request.header("origin");
            if (origin != null && !bound.origin().equals(origin)) {
                writeResponse(output, 403, "text/plain; charset=utf-8", "origin rejected", Map.of());
                return;
            }
            if (request.method().equals("GET")
                    && request.target().equals("/v1/events")
                    && "websocket".equalsIgnoreCase(request.header("upgrade"))) {
                if (!bound.origin().equals(origin)) {
                    writeResponse(output, 403, "text/plain; charset=utf-8", "origin required", Map.of());
                    return;
                }
                handleWebSocket(request, input, output, socket);
                return;
            }
            if (request.method().equals("POST") && request.target().equals("/v1/pair")) {
                if (!bound.origin().equals(origin)) {
                    writeResponse(output, 403, "application/json; charset=utf-8", error("origin_required"), Map.of());
                    return;
                }
                if (accessMode != ManifoldAuthorityPort.AccessMode.PAIRED) {
                    writeResponse(output, 409, "application/json; charset=utf-8", error("paired_mode_not_enabled"), Map.of());
                    return;
                }
                handlePair(request, output, socket);
                return;
            }
            if (request.method().equals("POST") && request.target().equals("/v1/open-session")) {
                if (!bound.origin().equals(origin)) {
                    writeResponse(output, 403, "application/json; charset=utf-8", error("origin_required"), Map.of());
                    return;
                }
                if (accessMode != ManifoldAuthorityPort.AccessMode.OPEN_LAN_INSECURE) {
                    writeResponse(output, 409, "application/json; charset=utf-8", error("open_lan_not_enabled"), Map.of());
                    return;
                }
                handleOpenLan(request, output, socket);
                return;
            }
            if (request.method().equals("GET") && request.target().equals("/v1/access")) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("access_mode", accessMode.protocolName());
                body.put("authentication_required", accessMode == ManifoldAuthorityPort.AccessMode.PAIRED);
                body.put("confidentiality", false);
                body.put("warning", accessMode == ManifoldAuthorityPort.AccessMode.OPEN_LAN_INSECURE
                        ? "anyone_on_this_network_can_request_control"
                        : "trusted_lan_plaintext_transport");
                writeResponse(output, 200, "application/json; charset=utf-8", JsonStrings.object(body), Map.of("Cache-Control", "no-store"));
                return;
            }
            if (request.method().equals("GET")) {
                handleAsset(request.target(), output);
                return;
            }
            writeResponse(output, 404, "text/plain; charset=utf-8", "not found", Map.of());
        } catch (SocketTimeoutException ignored) {
            // Bounded inactivity closes the connection.
            recordDiagnostic("connection_timeout", ignored);
        } catch (Exception ignored) {
            // A malformed/untrusted connection is closed without expanding the surface.
            recordDiagnostic("connection_failure", ignored);
        } finally {
            openSockets.remove(socket);
        }
    }

    private void recordDiagnostic(String phase, Exception failure) {
        recordDiagnostic(phase, failure.getClass().getSimpleName());
    }

    private void recordDiagnostic(String phase, String failureKind) {
        try {
            diagnostics.record(phase, failureKind);
        } catch (RuntimeException ignored) {
            // Diagnostics never affect the listener authority or connection lifecycle.
        }
    }

    private void handlePair(HttpRequest request, OutputStream output, Socket socket)
            throws IOException {
        if (!"application/json".equals(request.header("content-type"))) {
            writeResponse(
                    output,
                    400,
                    "application/json; charset=utf-8",
                    error("application_json_required"),
                    Map.of());
            return;
        }
        PairingRequest pairing;
        try {
            pairing = PairingRequest.parseCanonical(request.body());
        } catch (IllegalArgumentException error) {
            boolean withinLimit =
                    recordMalformedAttempt(
                            socket.getInetAddress().getHostAddress(), Instant.now());
            writeResponse(
                    output,
                    withinLimit ? 400 : 429,
                    "application/json; charset=utf-8",
                    error(
                            withinLimit
                                    ? "invalid_canonical_pairing_request"
                                    : "malformed_request_rate_limited"),
                    Map.of());
            return;
        }
        ManifoldAuthorityPort.PairDecision decision =
                coordinator.pair(socket.getInetAddress().getHostAddress(), pairing, Instant.now());
        writeAdmissionResponse(output, decision, true);
    }

    private void handleOpenLan(HttpRequest request, OutputStream output, Socket socket)
            throws IOException {
        if (!"application/json".equals(request.header("content-type"))) {
            writeResponse(
                    output,
                    400,
                    "application/json; charset=utf-8",
                    error("application_json_required"),
                    Map.of());
            return;
        }
        OpenLanRequest openRequest;
        try {
            openRequest = OpenLanRequest.parseCanonical(request.body());
        } catch (IllegalArgumentException error) {
            boolean withinLimit =
                    recordMalformedAttempt(
                            socket.getInetAddress().getHostAddress(), Instant.now());
            writeResponse(
                    output,
                    withinLimit ? 400 : 429,
                    "application/json; charset=utf-8",
                    error(
                            withinLimit
                                    ? "invalid_canonical_open_lan_request"
                                    : "malformed_request_rate_limited"),
                    Map.of());
            return;
        }
        ManifoldAuthorityPort.PairDecision decision =
                coordinator.admitOpenLan(
                        socket.getInetAddress().getHostAddress(), openRequest, Instant.now());
        writeAdmissionResponse(output, decision, false);
    }

    private static void writeAdmissionResponse(
            OutputStream output, ManifoldAuthorityPort.PairDecision decision, boolean paired)
            throws IOException {
        if (!decision.accepted()) {
            writeResponse(
                    output,
                    401,
                    "application/json; charset=utf-8",
                    error(decision.reason()),
                    Map.of());
            return;
        }
        if (decision.sessionCookie() == null
                || !decision.sessionCookie().matches("^[A-Za-z0-9_-]{32,128}$")) {
            writeResponse(
                    output,
                    500,
                    "application/json; charset=utf-8",
                    error("invalid_authority_session"),
                    Map.of());
            return;
        }
        long maxAge =
                Math.max(
                        1,
                        Math.min(
                                TrustedLocalControlPolicy.MAX_SESSION_LIFETIME.toSeconds(),
                                decision.sessionExpiresAt().getEpochSecond()
                                        - Instant.now().getEpochSecond()));
        Map<String, String> headers =
                Map.of(
                        "Set-Cookie",
                        SESSION_COOKIE
                                + "="
                                + decision.sessionCookie()
                                + "; HttpOnly; SameSite=Strict; Path=/; Max-Age="
                                + maxAge);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("admission_receipt_id", decision.admissionReceiptId());
        body.put("admission_revision", decision.revisions().admissionRevision());
        body.put("authority_revision", decision.revisions().localRevision());
        body.put("controller_lease_id", decision.controllerLeaseId());
        body.put("controller_label", decision.controllerLabel());
        body.put("host_revision", decision.revisions().hostRevision());
        body.put(
                "lease_authority_revision",
                decision.revisions().leaseAuthorityRevision());
        body.put("local_revision", decision.revisions().localRevision());
        body.put("access_mode", paired ? "paired" : "open_lan_insecure");
        body.put("paired", paired);
        body.put("session_admitted", true);
        body.put("session_expires_at", decision.sessionExpiresAt().toString());
        writeResponse(
                output,
                200,
                "application/json; charset=utf-8",
                JsonStrings.object(body),
                headers);
    }

    private void handleAsset(String target, OutputStream output) throws IOException {
        String path =
                switch (target) {
                    case "/" -> "/index.html";
                    case "/app.js", "/styles.css", "/favicon.svg" -> target;
                    default -> null;
                };
        if (path == null) {
            writeResponse(output, 404, "text/plain; charset=utf-8", "not found", Map.of());
            return;
        }
        Asset asset = assets.load(path);
        if (asset == null) {
            writeResponse(output, 404, "text/plain; charset=utf-8", "not found", Map.of());
            return;
        }
        writeResponse(output, 200, asset.contentType(), asset.bytes(), Map.of());
    }

    private void handleWebSocket(
            HttpRequest request,
            BufferedInputStream input,
            OutputStream output,
            Socket socket)
            throws Exception {
        String key = request.header("sec-websocket-key");
        if (key == null
                || !isValidWebSocketKey(key)
                || !"13".equals(request.header("sec-websocket-version"))
                || !"upgrade".equalsIgnoreCase(request.header("connection"))) {
            writeResponse(output, 400, "text/plain; charset=utf-8", "invalid websocket key", Map.of());
            return;
        }
        String session = cookie(request.header("cookie"), SESSION_COOKIE);
        if (session == null) {
            writeResponse(output, 401, "text/plain; charset=utf-8", "pairing required", Map.of());
            return;
        }
        ManifoldAuthorityPort.SessionDecision sessionDecision =
                coordinator.inspectSession(
                        session, socket.getInetAddress().getHostAddress(), Instant.now());
        if (!sessionDecision.active()) {
            writeResponse(output, 401, "text/plain; charset=utf-8", "session rejected", Map.of());
            return;
        }
        String accept =
                Base64.getEncoder()
                        .encodeToString(
                                MessageDigest.getInstance("SHA-1")
                                        .digest(
                                                (key + WEB_SOCKET_GUID)
                                                        .getBytes(StandardCharsets.US_ASCII)));
        String response =
                "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Sec-WebSocket-Accept: "
                        + accept
                        + "\r\n"
                        + securityHeaders()
                        + "\r\n";
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        socket.setSoTimeout(
                Math.toIntExact(TrustedLocalControlPolicy.WEBSOCKET_READ_TIMEOUT.toMillis()));

        Object writeLock = new Object();
        ArrayBlockingQueue<String> outbound =
                new ArrayBlockingQueue<>(TrustedLocalControlPolicy.MAX_PENDING_WEBSOCKET_EVENTS);
        AtomicBoolean websocketOpen = new AtomicBoolean(true);
        Thread writerThread =
                new Thread(
                        () -> {
                            while (websocketOpen.get() && !socket.isClosed()) {
                                try {
                                    String event = outbound.take();
                                    synchronized (writeLock) {
                                        writeTextFrame(output, event);
                                    }
                                } catch (InterruptedException ignored) {
                                    Thread.currentThread().interrupt();
                                    return;
                                } catch (IOException failure) {
                                    recordDiagnostic("websocket_write_failure", failure);
                                    try {
                                        socket.close();
                                    } catch (IOException ignored) {
                                        // Already closing.
                                    }
                                    return;
                                }
                            }
                        },
                        "trusted-local-http-websocket-writer");
        writerThread.setDaemon(true);
        LocalControlCoordinator.EventSink sink =
                event -> {
                    if (!outbound.offer(event)) {
                        recordDiagnostic("websocket_event_queue", "capacity_reached");
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                            // Already closing.
                        }
                    }
                };
        coordinator.addEventSink(sink);
        writerThread.start();
        try {
            while (running.get() && !socket.isClosed()) {
                WebSocketFrame frame = readFrame(input);
                if (frame.opcode() == 8) {
                    synchronized (writeLock) {
                        writeCloseFrame(output, 1000);
                    }
                    return;
                }
                if (frame.opcode() == 9) {
                    synchronized (writeLock) {
                        writeFrame(output, 10, frame.payload());
                    }
                    continue;
                }
                if (frame.opcode() != 1) {
                    synchronized (writeLock) {
                        writeCloseFrame(output, 1003);
                    }
                    return;
                }
                String text = new String(frame.payload(), StandardCharsets.UTF_8);
                CommandEnvelope command;
                try {
                    command = CommandEnvelope.parseCanonical(text);
                } catch (IllegalArgumentException error) {
                    boolean withinLimit =
                            recordMalformedAttempt(
                                    socket.getInetAddress().getHostAddress(), Instant.now());
                    synchronized (writeLock) {
                        if (!withinLimit) {
                            writeCloseFrame(output, 1008);
                            return;
                        }
                        writeTextFrame(output, error("invalid_canonical_command"));
                    }
                    continue;
                }
                coordinator.handleCommand(
                        session, socket.getInetAddress().getHostAddress(), command, Instant.now());
            }
        } finally {
            websocketOpen.set(false);
            coordinator.removeEventSink(sink);
            writerThread.interrupt();
        }
    }

    private static HttpRequest readRequest(BufferedInputStream input) throws IOException {
        String head = readHeaderBlock(input);
        if (head == null) {
            return null;
        }
        String[] lines = head.split("\\r\\n", -1);
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !first[2].equals("HTTP/1.1")) {
            throw new IOException("invalid request line");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                throw new IOException("invalid header");
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if (headers.putIfAbsent(name, value) != null) {
                throw new IOException("duplicate header");
            }
        }
        int length = 0;
        if (headers.containsKey("content-length")) {
            try {
                length = Integer.parseInt(headers.get("content-length"));
            } catch (NumberFormatException error) {
                throw new IOException("invalid content length", error);
            }
        }
        if (length < 0 || length > TrustedLocalControlPolicy.MAX_BODY_BYTES) {
            throw new IOException("body too large");
        }
        byte[] body = input.readNBytes(length);
        if (body.length != length) {
            throw new EOFException("truncated body");
        }
        return new HttpRequest(
                first[0],
                first[1],
                headers,
                new String(body, StandardCharsets.UTF_8));
    }

    private static String readHeaderBlock(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() <= TrustedLocalControlPolicy.MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) {
                return bytes.size() == 0 ? null : throwEof();
            }
            bytes.write(value);
            int expected = switch (matched) {
                case 0, 2 -> '\r';
                case 1, 3 -> '\n';
                default -> throw new IllegalStateException();
            };
            if (value == expected) {
                matched++;
                if (matched == 4) {
                    byte[] all = bytes.toByteArray();
                    return new String(all, 0, all.length - 4, StandardCharsets.US_ASCII);
                }
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
        throw new IOException("headers too large");
    }

    private static String throwEof() throws EOFException {
        throw new EOFException("truncated headers");
    }

    private static boolean isValidWebSocketKey(String key) {
        try {
            return Base64.getDecoder().decode(key).length == 16;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static WebSocketFrame readFrame(InputStream input) throws IOException {
        int first = input.read();
        int second = input.read();
        if (first < 0 || second < 0) {
            throw new EOFException("websocket closed");
        }
        boolean fin = (first & 0x80) != 0;
        int opcode = first & 0x0f;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7f;
        if (!fin || !masked) {
            throw new IOException("fragmented or unmasked frame rejected");
        }
        if (length == 126) {
            length = ((long) readRequired(input) << 8) | readRequired(input);
        } else if (length == 127) {
            throw new IOException("64-bit websocket frame length rejected");
        }
        if (length > TrustedLocalControlPolicy.MAX_WEBSOCKET_MESSAGE_BYTES) {
            throw new IOException("websocket message too large");
        }
        byte[] mask = readExactly(input, 4);
        byte[] payload = readExactly(input, (int) length);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i % 4];
        }
        return new WebSocketFrame(opcode, payload);
    }

    private static int readRequired(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("truncated frame");
        }
        return value;
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("truncated frame");
        }
        return value;
    }

    private static void writeTextFrame(OutputStream output, String text) throws IOException {
        writeFrame(output, 1, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeCloseFrame(OutputStream output, int code) throws IOException {
        writeFrame(output, 8, new byte[] {(byte) (code >>> 8), (byte) code});
    }

    private static void writeFrame(OutputStream output, int opcode, byte[] payload)
            throws IOException {
        output.write(0x80 | opcode);
        if (payload.length < 126) {
            output.write(payload.length);
        } else {
            output.write(126);
            output.write((payload.length >>> 8) & 0xff);
            output.write(payload.length & 0xff);
        }
        output.write(payload);
        output.flush();
    }

    private static String cookie(String raw, String name) {
        if (raw == null) {
            return null;
        }
        for (String item : raw.split(";")) {
            String[] parts = item.trim().split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) {
                return parts[1];
            }
        }
        return null;
    }

    private static void writeResponse(
            OutputStream output,
            int status,
            String contentType,
            String body,
            Map<String, String> extraHeaders)
            throws IOException {
        writeResponse(
                output,
                status,
                contentType,
                body.getBytes(StandardCharsets.UTF_8),
                extraHeaders);
    }

    private static void writeResponse(
            OutputStream output,
            int status,
            String contentType,
            byte[] body,
            Map<String, String> extraHeaders)
            throws IOException {
        String reason =
                switch (status) {
                    case 200 -> "OK";
                    case 400 -> "Bad Request";
                    case 401 -> "Unauthorized";
                    case 403 -> "Forbidden";
                    case 404 -> "Not Found";
                    case 421 -> "Misdirected Request";
                    case 429 -> "Too Many Requests";
                    case 500 -> "Internal Server Error";
                    default -> "Response";
                };
        StringBuilder head =
                new StringBuilder("HTTP/1.1 ")
                        .append(status)
                        .append(' ')
                        .append(reason)
                        .append("\r\n")
                        .append("Connection: close\r\n")
                        .append("Content-Length: ")
                        .append(body.length)
                        .append("\r\n")
                        .append("Content-Type: ")
                        .append(contentType)
                        .append("\r\n")
                        .append(securityHeaders());
        for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
            head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        head.append("\r\n");
        output.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static String securityHeaders() {
        return "Cache-Control: no-store\r\n"
                + "Content-Security-Policy: default-src 'self'; script-src 'self'; "
                + "style-src 'self'; connect-src 'self'; object-src 'none'; "
                + "base-uri 'none'; frame-ancestors 'none'; form-action 'self'\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "X-Frame-Options: DENY\r\n";
    }

    private static String error(String reason) {
        return JsonStrings.object(Map.of("error", reason));
    }

    @Override
    public synchronized void close() {
        running.set(false);
        ServerSocket listener = serverSocket;
        serverSocket = null;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
        for (Socket socket : openSockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
        openSockets.clear();
        connections.shutdownNow();
        synchronized (malformedLock) {
            malformedByRemote.clear();
        }
        endpoint = null;
        accessMode = null;
    }

    boolean tryAcquireConnectionSlot() {
        while (true) {
            int current = activeConnections.get();
            if (current >= TrustedLocalControlPolicy.MAX_CONCURRENT_CONNECTIONS) {
                return false;
            }
            if (activeConnections.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void releaseConnectionSlot() {
        int remaining = activeConnections.decrementAndGet();
        if (remaining < 0) {
            activeConnections.set(0);
            throw new IllegalStateException("connection slot released without acquisition");
        }
    }

    boolean recordMalformedAttempt(String remoteAddress, Instant now) {
        synchronized (malformedLock) {
            ArrayDeque<Instant> attempts = malformedAttempts(remoteAddress, now);
            if (attempts.size()
                    >= TrustedLocalControlPolicy.MAX_MALFORMED_ATTEMPTS_PER_MINUTE) {
                return false;
            }
            attempts.addLast(now);
            return true;
        }
    }

    boolean malformedLimitReached(String remoteAddress, Instant now) {
        synchronized (malformedLock) {
            return malformedAttempts(remoteAddress, now).size()
                    >= TrustedLocalControlPolicy.MAX_MALFORMED_ATTEMPTS_PER_MINUTE;
        }
    }

    int malformedAttemptCount(String remoteAddress, Instant now) {
        synchronized (malformedLock) {
            return malformedAttempts(remoteAddress, now).size();
        }
    }

    private ArrayDeque<Instant> malformedAttempts(String remoteAddress, Instant now) {
        ArrayDeque<Instant> attempts = malformedByRemote.get(remoteAddress);
        if (attempts == null) {
            if (malformedByRemote.size()
                    >= TrustedLocalControlPolicy.MAX_TRACKED_REMOTE_ADDRESSES) {
                String oldest = malformedByRemote.keySet().iterator().next();
                malformedByRemote.remove(oldest);
            }
            attempts = new ArrayDeque<>();
            malformedByRemote.put(remoteAddress, attempts);
        }
        Instant threshold = now.minusSeconds(60);
        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(threshold)) {
            attempts.removeFirst();
        }
        return attempts;
    }

    private record HttpRequest(
            String method, String target, Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name);
        }
    }

    private record WebSocketFrame(int opcode, byte[] payload) {}
}
