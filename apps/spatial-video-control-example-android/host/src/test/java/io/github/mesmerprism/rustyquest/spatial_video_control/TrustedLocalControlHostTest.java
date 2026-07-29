package io.github.mesmerprism.rustyquest.spatial_video_control;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TrustedLocalControlHostTest {
    private static final String LOOPBACK = "127.0.0.1";

    public static void main(String[] args) throws Exception {
        assertTrue(!TrustedLocalControlPolicy.ENABLED_BY_DEFAULT, "policy must default disabled");
        assertEquals(
                java.util.Set.of(
                        "describe", "get_state", "list_videos", "select_video", "play", "pause"),
                TrustedLocalControlPolicy.COMMANDS,
                "runtime command set");
        testCanonicalEnvelopes();
        testAuthorityAndPlayerCausality();
        testAuthorityLimitsAndExpiry();
        testLoopbackHttpAndWebSocket();
        System.out.println("trusted_local_http_v1 host tests passed");
    }

    private static void testCanonicalEnvelopes() {
        CommandEnvelope play =
                new CommandEnvelope("play", 4, 7, null, "request-play-0001");
        assertEquals(play, CommandEnvelope.parseCanonical(play.canonicalJson()), "play canonical roundtrip");
        CommandEnvelope select =
                new CommandEnvelope(
                        "select_video",
                        8,
                        3,
                        "synthetic-blue-2s",
                        "request-select-01");
        assertEquals(
                select,
                CommandEnvelope.parseCanonical(select.canonicalJson()),
                "select canonical roundtrip");
        expectFailure(
                () -> CommandEnvelope.parseCanonical(" " + play.canonicalJson()),
                "leading whitespace must fail");
        expectFailure(
                () ->
                        CommandEnvelope.parseCanonical(
                                play.canonicalJson().replace("\"play\"", "\"seek\"")),
                "unknown command must fail");
        expectFailure(
                () ->
                        CommandEnvelope.parseCanonical(
                                "{\"command\":\"play\",\"expected_player_revision\":7,"
                                        + "\"expected_authority_revision\":4,\"payload\":{},"
                                        + "\"request_id\":\"request-play-0001\"}"),
                "reordered fields must fail");
        expectFailure(
                () ->
                        CommandEnvelope.parseCanonical(
                                play.canonicalJson().replace("\"payload\":{}", "\"payload\":{\"url\":\"x\"}")),
                "unregistered payload must fail");
        PairingRequest pair = PairingRequest.parseCanonical(
                "{\"pairing_code\":\"482731\",\"request_id\":\"request-pair-0001\"}");
        assertEquals("482731", pair.pairingCode(), "pair code");
    }

    private static void testAuthorityAndPlayerCausality() {
        Instant now = Instant.now();
        FakeManifoldAuthority authority = new FakeManifoldAuthority();
        FakePlayer player = new FakePlayer();
        LocalControlCoordinator coordinator =
                new LocalControlCoordinator(authority, player, VideoCatalog.bundledSynthetic());
        List<String> events = new ArrayList<>();
        coordinator.addEventSink(events::add);

        ManifoldAuthorityPort.PairingOffer offer = enable(authority, now);
        assertTrue(offer.enabled(), "wearer enable");
        ManifoldAuthorityPort.PairDecision pair =
                coordinator.pair(
                        LOOPBACK,
                        new PairingRequest("482731", "request-pair-0001"),
                        now.plusMillis(1));
        assertTrue(pair.accepted(), "first pair");
        assertTrue(
                !coordinator
                        .pair(
                                "127.0.0.2",
                                new PairingRequest("482731", "request-pair-0002"),
                                now.plusMillis(2))
                        .accepted(),
                "single controller lease");

        CommandEnvelope describe =
                new CommandEnvelope(
                        "describe",
                        pair.authorityRevision(),
                        player.snapshot().revision(),
                        null,
                        "request-describe01");
        coordinator.handleCommand(
                pair.sessionCookie(), LOOPBACK, describe, now.plusMillis(3));
        assertContains(events.get(0), "\"event\":\"command_accepted\"", "query accepted first");
        assertContains(events.get(1), "\"event\":\"command_result\"", "query result second");

        long afterDescribe = authority.snapshot(now.plusMillis(4)).authorityRevision();
        CommandEnvelope select =
                new CommandEnvelope(
                        "select_video",
                        afterDescribe,
                        player.snapshot().revision(),
                        "synthetic-blue-2s",
                        "request-select-01");
        coordinator.handleCommand(pair.sessionCookie(), LOOPBACK, select, now.plusMillis(5));
        assertContains(
                events.get(events.size() - 1),
                "\"event\":\"command_accepted\"",
                "effect accepted before invocation callback");
        assertTrue(
                events.stream().noneMatch(value -> value.contains("\"event\":\"command_applied\"")),
                "no applied event before player callback");
        player.flush();
        int appliedIndex = indexOf(events, "\"event\":\"command_applied\"");
        int acceptedIndex = lastIndexOf(events, "\"event\":\"command_accepted\"");
        assertTrue(appliedIndex > acceptedIndex, "applied follows accepted");
        assertContains(events.get(appliedIndex), "\"expected_player_revision\":0", "expected player revision retained");
        assertContains(events.get(appliedIndex), "\"player_revision\":1", "effective player revision advanced");
        assertEquals(
                "synthetic-blue-2s",
                player.snapshot().selectedVideoId(),
                "selection applied");
        assertTrue(!player.snapshot().playing(), "selection and play remain separate");

        long currentAuthority = authority.snapshot(now.plusMillis(6)).authorityRevision();
        CommandEnvelope replay =
                new CommandEnvelope(
                        "select_video",
                        currentAuthority,
                        player.snapshot().revision(),
                        "synthetic-blue-2s",
                        "request-select-01");
        coordinator.handleCommand(pair.sessionCookie(), LOOPBACK, replay, now.plusMillis(7));
        assertContains(
                events.get(events.size() - 1),
                "\"reason\":\"request_replayed\"",
                "one-use request ids");

        authority.revokeByWearer(now.plusMillis(8));
        ManifoldAuthorityPort.SessionDecision revoked =
                coordinator.inspectSession(pair.sessionCookie(), LOOPBACK, now.plusMillis(9));
        assertTrue(!revoked.active(), "wearer revoke ends controller session");
    }

    private static void testAuthorityLimitsAndExpiry() {
        Instant now = Instant.now();
        FakeManifoldAuthority authority = new FakeManifoldAuthority();
        FakePlayer player = new FakePlayer();
        LocalControlCoordinator coordinator =
                new LocalControlCoordinator(authority, player, VideoCatalog.bundledSynthetic());
        enable(authority, now);
        ManifoldAuthorityPort.PairDecision pair =
                coordinator.pair(
                        LOOPBACK,
                        new PairingRequest("482731", "request-pair-limit"),
                        now.plusMillis(1));
        assertTrue(pair.accepted(), "rate test pair");
        long revision = pair.authorityRevision();
        for (int i = 0; i < TrustedLocalControlPolicy.MAX_REQUESTS_PER_MINUTE; i++) {
            String id = String.format(Locale.ROOT, "request-rate-%06d", i);
            CommandEnvelope command =
                    new CommandEnvelope("get_state", revision, 0, null, id);
            ManifoldAuthorityPort.CommandDecision decision =
                    authority.review(
                            new ManifoldAuthorityPort.CommandAttempt(
                                    pair.sessionCookie(),
                                    LOOPBACK,
                                    command,
                                    player.snapshot(),
                                    now.plusSeconds(1)));
            assertTrue(decision.accepted(), "bounded request inside rate");
            revision = decision.authorityRevision();
        }
        CommandEnvelope excess =
                new CommandEnvelope(
                        "get_state",
                        revision,
                        0,
                        null,
                        "request-rate-excess");
        ManifoldAuthorityPort.CommandDecision limited =
                authority.review(
                        new ManifoldAuthorityPort.CommandAttempt(
                                pair.sessionCookie(),
                                LOOPBACK,
                                excess,
                                player.snapshot(),
                                now.plusSeconds(1)));
        assertEquals("command_rate_limited", limited.reason(), "strict command rate");

        ManifoldAuthorityPort.SessionDecision expired =
                coordinator.inspectSession(
                        pair.sessionCookie(),
                        LOOPBACK,
                        now.plus(TrustedLocalControlPolicy.MAX_IDLE_LIFETIME).plusSeconds(2));
        assertTrue(!expired.active(), "idle expiry");
    }

    private static void testLoopbackHttpAndWebSocket() throws Exception {
        Instant now = Instant.now();
        FakeManifoldAuthority authority = new FakeManifoldAuthority();
        FakePlayer player = new FakePlayer();
        LocalControlCoordinator coordinator =
                new LocalControlCoordinator(authority, player, VideoCatalog.bundledSynthetic());
        ManifoldAuthorityPort.PairingOffer offer = enable(authority, now);
        Path assets = Path.of("app", "src", "main", "assets", "control");
        TrustedLocalHttpServer.AssetProvider provider =
                path -> {
                    Path file = assets.resolve(path.substring(1));
                    if (!Files.isRegularFile(file)) {
                        return null;
                    }
                    String type =
                            path.endsWith(".js")
                                    ? "text/javascript; charset=utf-8"
                                    : path.endsWith(".css")
                                            ? "text/css; charset=utf-8"
                                            : "text/html; charset=utf-8";
                    return new TrustedLocalHttpServer.Asset(type, Files.readAllBytes(file));
                };
        try (TrustedLocalHttpServer server = new TrustedLocalHttpServer(coordinator, provider)) {
            TrustedLocalHttpServer.BoundEndpoint endpoint =
                    server.start(offer, InetAddress.getByName(LOOPBACK));
            assertTrue(endpoint.address().isLoopbackAddress(), "test listener loopback only");
            assertTrue(endpoint.port() > 0, "OS assigned ephemeral port");

            HttpResponse wrongHost =
                    http(
                            endpoint,
                            "GET / HTTP/1.1\r\nHost: attacker.invalid\r\nConnection: close\r\n\r\n");
            assertEquals(421, wrongHost.status(), "exact Host enforced");

            HttpResponse index =
                    http(
                            endpoint,
                            "GET / HTTP/1.1\r\nHost: "
                                    + endpoint.hostHeader()
                                    + "\r\nConnection: close\r\n\r\n");
            assertEquals(200, index.status(), "packaged index served");
            assertContains(index.headers(), "Content-Security-Policy:", "CSP present");
            assertTrue(
                    !index.headers().toLowerCase(Locale.ROOT).contains("access-control-allow-origin"),
                    "no CORS response");
            assertContains(index.body(), "<script src=\"/app.js\"", "same-origin script");

            String pairBody =
                    new PairingRequest("482731", "request-http-pair").canonicalJson();
            HttpResponse badOrigin =
                    http(
                            endpoint,
                            post(
                                    endpoint,
                                    "/v1/pair",
                                    "http://attacker.invalid",
                                    pairBody));
            assertEquals(403, badOrigin.status(), "exact Origin enforced");

            HttpResponse paired =
                    http(endpoint, post(endpoint, "/v1/pair", endpoint.origin(), pairBody));
            assertEquals(200, paired.status(), "pair accepted");
            Matcher cookieMatcher =
                    Pattern.compile("(?im)^Set-Cookie: rq_session=([^;]+);").matcher(paired.headers());
            assertTrue(cookieMatcher.find(), "HttpOnly session cookie issued");
            String cookie = cookieMatcher.group(1);
            long pairedRevision = numberField(paired.body(), "authority_revision");

            HttpResponse rejectedUpgrade =
                    websocketHandshake(endpoint, cookie, "http://attacker.invalid", false);
            assertEquals(403, rejectedUpgrade.status(), "websocket Origin enforced");

            try (Socket socket = new Socket(endpoint.address(), endpoint.port())) {
                socket.setSoTimeout(5_000);
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                OutputStream output = socket.getOutputStream();
                String key = Base64.getEncoder().encodeToString(new byte[16]);
                output.write(
                        websocketRequest(endpoint, cookie, endpoint.origin(), key)
                                .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                String responseHead = readHead(input);
                assertContains(responseHead, "HTTP/1.1 101", "websocket upgraded");

                CommandEnvelope describe =
                        new CommandEnvelope(
                                "describe",
                                pairedRevision,
                                player.snapshot().revision(),
                                null,
                                "request-ws-describe");
                writeMaskedText(output, describe.canonicalJson());
                String accepted = readServerText(input);
                String result = readServerText(input);
                assertContains(accepted, "\"event\":\"command_accepted\"", "WS accepted receipt");
                assertContains(result, "\"event\":\"command_result\"", "WS query result");
                assertContains(result, "\"protocol\":\"trusted_local_http_v1\"", "protocol described");
            }
        }
    }

    private static ManifoldAuthorityPort.PairingOffer enable(
            FakeManifoldAuthority authority, Instant now) {
        return authority.beginWearerEnable(
                new ManifoldAuthorityPort.EnableRequest(
                        "127.0.0.1",
                        Duration.ofMinutes(2),
                        now,
                        true));
    }

    private static String post(
            TrustedLocalHttpServer.BoundEndpoint endpoint,
            String path,
            String origin,
            String body) {
        int length = body.getBytes(StandardCharsets.UTF_8).length;
        return "POST "
                + path
                + " HTTP/1.1\r\nHost: "
                + endpoint.hostHeader()
                + "\r\nOrigin: "
                + origin
                + "\r\nContent-Type: application/json\r\nContent-Length: "
                + length
                + "\r\nConnection: close\r\n\r\n"
                + body;
    }

    private static HttpResponse websocketHandshake(
            TrustedLocalHttpServer.BoundEndpoint endpoint,
            String cookie,
            String origin,
            boolean expectUpgrade)
            throws IOException {
        try (Socket socket = new Socket(endpoint.address(), endpoint.port())) {
            socket.setSoTimeout(5_000);
            OutputStream output = socket.getOutputStream();
            output.write(
                    websocketRequest(
                                    endpoint,
                                    cookie,
                                    origin,
                                    Base64.getEncoder().encodeToString(new byte[16]))
                            .getBytes(StandardCharsets.US_ASCII));
            output.flush();
            if (expectUpgrade) {
                return new HttpResponse(101, readHead(socket.getInputStream()), "");
            }
            return readHttpResponse(socket.getInputStream());
        }
    }

    private static String websocketRequest(
            TrustedLocalHttpServer.BoundEndpoint endpoint,
            String cookie,
            String origin,
            String key) {
        return "GET /v1/events HTTP/1.1\r\n"
                + "Host: "
                + endpoint.hostHeader()
                + "\r\nOrigin: "
                + origin
                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "
                + key
                + "\r\nCookie: rq_session="
                + cookie
                + "\r\n\r\n";
    }

    private static HttpResponse http(
            TrustedLocalHttpServer.BoundEndpoint endpoint, String request) throws IOException {
        try (Socket socket = new Socket(endpoint.address(), endpoint.port())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return readHttpResponse(socket.getInputStream());
        }
    }

    private static HttpResponse readHttpResponse(InputStream raw) throws IOException {
        BufferedInputStream input =
                raw instanceof BufferedInputStream buffered ? buffered : new BufferedInputStream(raw);
        String headers = readHead(input);
        Matcher status = Pattern.compile("^HTTP/1\\.1 ([0-9]{3})").matcher(headers);
        assertTrue(status.find(), "HTTP status");
        Matcher length = Pattern.compile("(?im)^Content-Length: ([0-9]+)$").matcher(headers);
        int bodyLength = length.find() ? Integer.parseInt(length.group(1)) : 0;
        byte[] body = input.readNBytes(bodyLength);
        return new HttpResponse(
                Integer.parseInt(status.group(1)),
                headers,
                new String(body, StandardCharsets.UTF_8));
    }

    private static String readHead(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < 16_384) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("truncated HTTP head");
            }
            bytes.write(value);
            int expected = matched % 2 == 0 ? '\r' : '\n';
            matched = value == expected ? matched + 1 : (value == '\r' ? 1 : 0);
            if (matched == 4) {
                return bytes.toString(StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("HTTP head too large");
    }

    private static void writeMaskedText(OutputStream output, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask = new byte[] {1, 2, 3, 4};
        output.write(0x81);
        if (payload.length < 126) {
            output.write(0x80 | payload.length);
        } else {
            output.write(0x80 | 126);
            output.write((payload.length >>> 8) & 0xff);
            output.write(payload.length & 0xff);
        }
        output.write(mask);
        for (int i = 0; i < payload.length; i++) {
            output.write(payload[i] ^ mask[i % 4]);
        }
        output.flush();
    }

    private static String readServerText(InputStream input) throws IOException {
        int first = input.read();
        int second = input.read();
        if (first < 0 || second < 0 || (first & 0x0f) != 1 || (second & 0x80) != 0) {
            throw new IOException("invalid server text frame");
        }
        int length = second & 0x7f;
        if (length == 126) {
            length = (input.read() << 8) | input.read();
        }
        byte[] payload = input.readNBytes(length);
        if (payload.length != length) {
            throw new EOFException("truncated server frame");
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static long numberField(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":([0-9]+)").matcher(json);
        assertTrue(matcher.find(), "missing field " + field);
        return Long.parseLong(matcher.group(1));
    }

    private static int indexOf(List<String> values, String needle) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(List<String> values, String needle) {
        for (int i = values.size() - 1; i >= 0; i--) {
            if (values.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static void expectFailure(ThrowingRunnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected.
        } catch (Exception error) {
            throw new AssertionError(message, error);
        }
    }

    private static void assertContains(String actual, String expected, String message) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(message + ": expected " + expected + " in " + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record HttpResponse(int status, String headers, String body) {}

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
