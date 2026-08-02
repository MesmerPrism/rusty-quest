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
        if (args.length != 1) {
            throw new IllegalArgumentException("expected the absolute app root path");
        }
        Path appRoot = Path.of(args[0]).toAbsolutePath().normalize();
        assertTrue(!TrustedLocalControlPolicy.ENABLED_BY_DEFAULT, "policy must default disabled");
        assertEquals(
                java.util.Set.of(
                        "describe", "get_state", "list_videos", "select_video", "play", "pause"),
                TrustedLocalControlPolicy.COMMANDS,
                "runtime command set");
        testCanonicalEnvelopes();
        testTrustedBindAddressPolicy();
        testPrivateAddressSelection();
        testTransportResourceBounds();
        testPlayerStateProjection();
        testReadOnlyBootstrapWithAdvancedPlayerState();
        testAuthorityAndPlayerCausality();
        testPlayerEffectTimeoutDoesNotWedge();
        testAuthorityLimitsAndExpiry();
        testLoopbackHttpAndWebSocket(appRoot);
        testOpenLanAdmissionIsExplicitAndSingleController(appRoot);
        System.out.println("trusted_local_http_v1 host tests passed");
    }

    private static void testTrustedBindAddressPolicy() throws Exception {
        assertTrue(
                TrustedLocalHttpServer.isTrustedBindAddress(InetAddress.getByName("127.0.0.1")),
                "IPv4 loopback allowed");
        assertTrue(
                TrustedLocalHttpServer.isTrustedBindAddress(InetAddress.getByName("192.168.20.4")),
                "IPv4 site-local allowed");
        assertTrue(
                TrustedLocalHttpServer.isTrustedBindAddress(InetAddress.getByName("169.254.20.4")),
                "IPv4 link-local allowed");
        assertTrue(
                TrustedLocalHttpServer.isTrustedBindAddress(InetAddress.getByName("fd12:3456::1")),
                "IPv6 unique-local allowed");
        assertTrue(
                TrustedLocalHttpServer.isTrustedBindAddress(InetAddress.getByName("fe80::1")),
                "IPv6 link-local allowed");
        assertTrue(
                !TrustedLocalHttpServer.isTrustedBindAddress(InetAddress.getByName("0.0.0.0")),
                "any-local rejected");
        assertTrue(
                !TrustedLocalHttpServer.isTrustedBindAddress(InetAddress.getByName("224.0.0.1")),
                "multicast rejected");
        assertTrue(
                !TrustedLocalHttpServer.isTrustedBindAddress(InetAddress.getByName("8.8.8.8")),
                "globally routable IPv4 rejected");
        assertTrue(
                !TrustedLocalHttpServer.isTrustedBindAddress(
                        InetAddress.getByName("2001:4860:4860::8888")),
                "globally routable IPv6 rejected");
    }

    private static void testPrivateAddressSelection() throws Exception {
        PrivateAddressSelector.Candidate selected =
                PrivateAddressSelector.select(
                        List.of(
                                InetAddress.getByName("127.0.0.1"),
                                InetAddress.getByName("192.168.20.4"),
                                InetAddress.getByName("2001:4860:4860::8888")));
        assertTrue(selected.available(), "one private IPv4 candidate selected");
        assertEquals(
                "192.168.20.4",
                selected.address().getHostAddress(),
                "selected private candidate");
        assertContains(selected.displayText(), "192.168.20.4", "candidate visible to wearer");

        PrivateAddressSelector.Candidate ambiguous =
                PrivateAddressSelector.select(
                        List.of(
                                InetAddress.getByName("192.168.20.4"),
                                InetAddress.getByName("10.0.0.8")));
        assertTrue(!ambiguous.available(), "ambiguous private addresses fail closed");
        assertEquals(
                "ambiguous_private_addresses",
                ambiguous.status(),
                "ambiguous status");

        PrivateAddressSelector.Candidate absent =
                PrivateAddressSelector.select(
                        List.of(
                                InetAddress.getByName("127.0.0.1"),
                                InetAddress.getByName("8.8.8.8")));
        assertTrue(!absent.available(), "no private address fails closed");
        assertEquals("no_private_address", absent.status(), "absent status");
    }

    private static void testTransportResourceBounds() {
        FakeManifoldAuthority authority = new FakeManifoldAuthority();
        FakePlayer player = new FakePlayer();
        LocalControlCoordinator coordinator =
                new LocalControlCoordinator(authority, player, VideoCatalog.bundledSynthetic());
        try (TrustedLocalHttpServer server =
                new TrustedLocalHttpServer(coordinator, ignored -> null)) {
            for (int i = 0; i < TrustedLocalControlPolicy.MAX_CONCURRENT_CONNECTIONS; i++) {
                assertTrue(server.tryAcquireConnectionSlot(), "bounded connection slot " + i);
            }
            assertTrue(!server.tryAcquireConnectionSlot(), "excess connection rejected");
            for (int i = 0; i < TrustedLocalControlPolicy.MAX_CONCURRENT_CONNECTIONS; i++) {
                server.releaseConnectionSlot();
            }
            assertTrue(server.tryAcquireConnectionSlot(), "released connection slot reusable");
            server.releaseConnectionSlot();

            Instant now = Instant.now();
            for (int i = 0;
                    i < TrustedLocalControlPolicy.MAX_MALFORMED_ATTEMPTS_PER_MINUTE;
                    i++) {
                assertTrue(
                        server.recordMalformedAttempt(LOOPBACK, now),
                        "bounded malformed attempt " + i);
            }
            assertTrue(
                    server.malformedLimitReached(LOOPBACK, now),
                    "malformed limit visible");
            assertTrue(
                    !server.recordMalformedAttempt(LOOPBACK, now),
                    "excess malformed attempt rejected");
            assertTrue(
                    server.recordMalformedAttempt(LOOPBACK, now.plusSeconds(61)),
                    "malformed window expires");
        }
        assertTrue(
                TrustedLocalControlPolicy.WEBSOCKET_READ_TIMEOUT.compareTo(
                                TrustedLocalControlPolicy.MAX_IDLE_LIFETIME)
                        > 0,
                "websocket read timeout must not preempt authority idle expiry");
    }

    private static void testPlayerStateProjection() {
        PlayerPort.Snapshot initial =
                new PlayerPort.Snapshot(0, "synthetic-grid-1s", false, "idle", 0);
        PlayerPort.Snapshot ready =
                PlayerStateProjection.apply(
                        initial,
                        new PlayerStateProjection.Observation(
                                "synthetic-grid-1s", false, "ready", 0));
        assertEquals(1L, ready.revision(), "READY advances semantic revision");
        PlayerPort.Snapshot progressed =
                PlayerStateProjection.apply(
                        ready,
                        new PlayerStateProjection.Observation(
                                "synthetic-grid-1s", false, "ready", 750));
        assertEquals(
                ready.revision(),
                progressed.revision(),
                "position-only observation retains semantic revision");
        assertEquals(750L, progressed.positionMs(), "position observation retained");
        PlayerPort.Snapshot playing =
                PlayerStateProjection.apply(
                        progressed,
                        new PlayerStateProjection.Observation(
                                "synthetic-grid-1s", true, "ready", 760));
        assertEquals(2L, playing.revision(), "playing transition advances revision");
        PlayerPort.Snapshot failed =
                PlayerStateProjection.apply(
                        playing,
                        new PlayerStateProjection.Observation(
                                "synthetic-grid-1s", false, "media3_2001", 760));
        assertEquals(3L, failed.revision(), "error transition advances revision");
        assertEquals("media3_2001", failed.playbackState(), "error state retained");
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
                        pair.revisions().localRevision(),
                        player.snapshot().revision(),
                        null,
                        "request-describe01");
        coordinator.handleCommand(
                pair.sessionCookie(), LOOPBACK, describe, now.plusMillis(3));
        assertContains(events.get(0), "\"event\":\"command_accepted\"", "query accepted first");
        assertContains(events.get(1), "\"event\":\"command_result\"", "query result second");

        long afterDescribe = authority.snapshot().revisions().localRevision();
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
        assertContains(
                events.get(events.size() - 1),
                "\"manifold_command_receipt_id\":\"receipt.manifold.command.request-select-01\"",
                "accepted Manifold command receipt retained");
        assertTrue(
                events.stream().noneMatch(value -> value.contains("\"event\":\"command_applied\"")),
                "no applied event before player callback");
        CommandEnvelope whilePending =
                new CommandEnvelope(
                        "play",
                        authority.snapshot().revisions().localRevision(),
                        player.snapshot().revision(),
                        null,
                        "request-pending-01");
        coordinator.handleCommand(
                pair.sessionCookie(), LOOPBACK, whilePending, now.plusMillis(5));
        assertContains(
                events.get(events.size() - 1),
                "\"event\":\"command_not_submitted\"",
                "pending effect is not mislabeled as Manifold rejection");
        assertContains(
                events.get(events.size() - 1),
                "\"reason\":\"player_effect_pending\"",
                "pending effect reason");
        player.flush();
        int appliedIndex = indexOf(events, "\"event\":\"command_applied\"");
        int acceptedIndex = lastIndexOf(events, "\"event\":\"command_accepted\"");
        assertTrue(appliedIndex > acceptedIndex, "applied follows accepted");
        assertContains(events.get(appliedIndex), "\"expected_player_revision\":0", "expected player revision retained");
        assertContains(events.get(appliedIndex), "\"player_revision\":1", "effective player revision advanced");
        assertContains(
                events.get(appliedIndex),
                "\"manifold_command_receipt_id\":\"receipt.manifold.command.request-select-01\"",
                "applied event retains accepted Manifold receipt");
        assertEquals(
                "synthetic-blue-2s",
                player.snapshot().selectedVideoId(),
                "selection applied");
        assertTrue(!player.snapshot().playing(), "selection and play remain separate");

        long currentAuthority = authority.snapshot().revisions().localRevision();
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
                "\"reason\":\"already_selected\"",
                "Quest-owned no-op check runs before Manifold submission");

        ManifoldAuthorityPort.RevokeDecision revoke =
                authority.revokeByWearer(
                        new ManifoldAuthorityPort.RevokeRequest(
                                "request-revoke-0001", "wearer_revoke"));
        assertTrue(revoke.revoked(), "wearer revoke applied");
        assertEquals("wearer_revoke", revoke.cause(), "wearer revoke cause retained");
        assertContains(
                revoke.disableReceiptId(),
                "receipt.local_control.disable.",
                "wearer revoke receipt retained");
        ManifoldAuthorityPort.SessionDecision revoked =
                coordinator.inspectSession(pair.sessionCookie(), LOOPBACK, now.plusMillis(9));
        assertTrue(!revoked.active(), "wearer revoke ends controller session");
        coordinator.close();
    }

    private static void testReadOnlyBootstrapWithAdvancedPlayerState() {
        Instant now = Instant.now();
        FakeManifoldAuthority authority = new FakeManifoldAuthority();
        FakePlayer player = new FakePlayer();
        player.observeReadyPosition(42);
        LocalControlCoordinator coordinator =
                new LocalControlCoordinator(authority, player, VideoCatalog.bundledSynthetic());
        List<String> events = new ArrayList<>();
        coordinator.addEventSink(events::add);
        enable(authority, now);
        ManifoldAuthorityPort.PairDecision pair =
                coordinator.pair(
                        LOOPBACK,
                        new PairingRequest("482731", "request-pair-bootstrap"),
                        now.plusMillis(1));

        coordinator.handleCommand(
                pair.sessionCookie(),
                LOOPBACK,
                new CommandEnvelope(
                        "describe",
                        pair.revisions().localRevision(),
                        0,
                        null,
                        "request-bootstrap-describe"),
                now.plusMillis(2));
        assertTrue(
                events.stream().anyMatch(value -> value.contains("\"event\":\"command_result\"")),
                "describe succeeds even when Media3 already advanced player state");

        long nextAuthority = authority.snapshot().revisions().localRevision();
        coordinator.handleCommand(
                pair.sessionCookie(),
                LOOPBACK,
                new CommandEnvelope(
                        "get_state",
                        nextAuthority,
                        0,
                        null,
                        "request-bootstrap-state"),
                now.plusMillis(3));
        assertContains(
                events.get(events.size() - 1),
                "\"revision\":1",
                "get_state teaches browser the effective player revision");
        coordinator.close();
    }

    private static void testPlayerEffectTimeoutDoesNotWedge() throws Exception {
        Instant now = Instant.now();
        FakeManifoldAuthority authority = new FakeManifoldAuthority();
        FakePlayer player = new FakePlayer();
        LocalControlCoordinator coordinator =
                new LocalControlCoordinator(
                        authority,
                        player,
                        VideoCatalog.bundledSynthetic(),
                        Duration.ofMillis(20));
        List<String> events = new ArrayList<>();
        coordinator.addEventSink(events::add);
        enable(authority, now);
        ManifoldAuthorityPort.PairDecision pair =
                coordinator.pair(
                        LOOPBACK,
                        new PairingRequest("482731", "request-pair-timeout"),
                        now.plusMillis(1));
        coordinator.handleCommand(
                pair.sessionCookie(),
                LOOPBACK,
                new CommandEnvelope(
                        "select_video",
                        pair.revisions().localRevision(),
                        0,
                        "synthetic-blue-2s",
                        "request-timeout-select"),
                now.plusMillis(2));
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (events.stream()
                        .noneMatch(
                                value ->
                                        value.contains("\"reason\":\"player_effect_timeout\""))
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(
                events.stream()
                        .anyMatch(
                                value ->
                                        value.contains("\"reason\":\"player_effect_timeout\"")),
                "bounded effect timeout emitted");
        coordinator.handleCommand(
                pair.sessionCookie(),
                LOOPBACK,
                new CommandEnvelope(
                        "select_video",
                        authority.snapshot().revisions().localRevision(),
                        player.snapshot().revision(),
                        "synthetic-blue-2s",
                        "request-after-timeout"),
                now.plusMillis(3));
        assertContains(
                events.get(events.size() - 1),
                "\"event\":\"command_accepted\"",
                "timeout clears both coordinator and player pending state");
        coordinator.close();
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
        long revision = pair.revisions().localRevision();
        for (int i = 0; i < TrustedLocalControlPolicy.MAX_REQUESTS_PER_MINUTE; i++) {
            String id = String.format(Locale.ROOT, "request-rate-%06d", i);
            CommandEnvelope command =
                    new CommandEnvelope("get_state", revision, 0, null, id);
            ManifoldAuthorityPort.CommandDecision decision =
                    authority.review(
                            new ManifoldAuthorityPort.CommandAttempt(
                                    pair.sessionCookie(),
                                    LOOPBACK,
                                    command));
            assertTrue(decision.accepted(), "bounded request inside rate");
            revision = decision.revisions().localRevision();
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
                                excess));
        assertEquals("command_rate_limited", limited.reason(), "strict command rate");

        ManifoldAuthorityPort.SessionDecision expired =
                coordinator.inspectSession(
                        pair.sessionCookie(),
                        LOOPBACK,
                        now.plus(TrustedLocalControlPolicy.MAX_IDLE_LIFETIME).plusSeconds(2));
        assertTrue(!expired.active(), "idle expiry");
        coordinator.close();

        FakeManifoldAuthority rejectingAuthority = new FakeManifoldAuthority();
        FakePlayer rejectingPlayer = new FakePlayer();
        LocalControlCoordinator rejectingCoordinator =
                new LocalControlCoordinator(
                        rejectingAuthority,
                        rejectingPlayer,
                        VideoCatalog.bundledSynthetic());
        enable(rejectingAuthority, now);
        ManifoldAuthorityPort.PairDecision rejectingPair =
                rejectingCoordinator.pair(
                        LOOPBACK,
                        new PairingRequest("482731", "request-pair-expiry-reject"),
                        now.plusMillis(1));
        rejectingAuthority.rejectNextDueExpiry();
        expectIllegalState(
                () ->
                        rejectingCoordinator.inspectSession(
                                rejectingPair.sessionCookie(),
                                LOOPBACK,
                                now.plus(TrustedLocalControlPolicy.MAX_IDLE_LIFETIME)
                                        .plusSeconds(2)),
                "due expiry rejection must fail closed");
        rejectingCoordinator.close();
    }

    private static void testLoopbackHttpAndWebSocket(Path appRoot) throws Exception {
        Instant now = Instant.now();
        FakeManifoldAuthority authority = new FakeManifoldAuthority();
        FakePlayer player = new FakePlayer();
        LocalControlCoordinator coordinator =
                new LocalControlCoordinator(authority, player, VideoCatalog.bundledSynthetic());
        ManifoldAuthorityPort.PairingOffer offer = enable(authority, now);
        Path assets = appRoot.resolve(Path.of("app", "src", "main", "assets", "control"));
        assertTrue(Files.isDirectory(assets), "packaged asset root");
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

            try (Socket malformed = new Socket(endpoint.address(), endpoint.port())) {
                malformed.getOutputStream()
                        .write("BROKEN\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                malformed.getOutputStream().flush();
                malformed.shutdownOutput();
                malformed.getInputStream().readAllBytes();
            }
            assertEquals(
                    1,
                    server.malformedAttemptCount(LOOPBACK, Instant.now()),
                    "HTTP parser failure counted as malformed attempt");
        }
    }

    private static void testOpenLanAdmissionIsExplicitAndSingleController(Path appRoot)
            throws Exception {
        Instant now = Instant.now();
        FakeManifoldAuthority authority = new FakeManifoldAuthority();
        LocalControlCoordinator coordinator =
                new LocalControlCoordinator(
                        authority, new FakePlayer(), VideoCatalog.bundledSynthetic());
        ManifoldAuthorityPort.PairingOffer offer =
                enable(authority, now, ManifoldAuthorityPort.AccessMode.OPEN_LAN_INSECURE);
        Path assets = appRoot.resolve(Path.of("app", "src", "main", "assets", "control"));
        TrustedLocalHttpServer.AssetProvider provider =
                path -> {
                    Path file = assets.resolve(path.substring(1));
                    return Files.isRegularFile(file)
                            ? new TrustedLocalHttpServer.Asset(
                                    path.endsWith(".js")
                                            ? "text/javascript; charset=utf-8"
                                            : path.endsWith(".css")
                                                    ? "text/css; charset=utf-8"
                                                    : "text/html; charset=utf-8",
                                    Files.readAllBytes(file))
                            : null;
                };
        try (TrustedLocalHttpServer server = new TrustedLocalHttpServer(coordinator, provider)) {
            TrustedLocalHttpServer.BoundEndpoint endpoint =
                    server.start(offer, InetAddress.getByName(LOOPBACK));
            HttpResponse access =
                    http(
                            endpoint,
                            "GET /v1/access HTTP/1.1\r\nHost: "
                                    + endpoint.hostHeader()
                                    + "\r\nConnection: close\r\n\r\n");
            assertEquals(200, access.status(), "Open LAN access descriptor served");
            assertContains(
                    access.body(),
                    "\"access_mode\":\"open_lan_insecure\"",
                    "unsafe access mode explicit");
            assertContains(
                    access.body(),
                    "\"authentication_required\":false",
                    "Open LAN never claims authentication");

            String pairBody =
                    new PairingRequest("482731", "request-open-pair-denied").canonicalJson();
            HttpResponse pairRejected =
                    http(endpoint, post(endpoint, "/v1/pair", endpoint.origin(), pairBody));
            assertEquals(409, pairRejected.status(), "pair endpoint disabled in Open LAN mode");

            String openBody = new OpenLanRequest("request-open-lan-0001").canonicalJson();
            HttpResponse admitted =
                    http(
                            endpoint,
                            post(endpoint, "/v1/open-session", endpoint.origin(), openBody));
            assertEquals(200, admitted.status(), "Open LAN controller admitted");
            assertContains(admitted.body(), "\"paired\":false", "response never claims pairing");
            assertContains(
                    admitted.body(),
                    "\"session_admitted\":true",
                    "Manifold-backed session admitted");

            HttpResponse second =
                    http(
                            endpoint,
                            post(
                                    endpoint,
                                    "/v1/open-session",
                                    endpoint.origin(),
                                    new OpenLanRequest("request-open-lan-0002").canonicalJson()));
            assertEquals(401, second.status(), "second controller lease rejected");
        } finally {
            coordinator.close();
        }
    }

    private static ManifoldAuthorityPort.PairingOffer enable(
            FakeManifoldAuthority authority, Instant now) {
        return enable(authority, now, ManifoldAuthorityPort.AccessMode.PAIRED);
    }

    private static ManifoldAuthorityPort.PairingOffer enable(
            FakeManifoldAuthority authority,
            Instant now,
            ManifoldAuthorityPort.AccessMode accessMode) {
        return authority.beginWearerEnable(
                new ManifoldAuthorityPort.EnableRequest(
                        "127.0.0.1",
                        Duration.ofMinutes(2),
                        now,
                        true,
                        accessMode,
                        ManifoldAuthorityPort.EnableActor.WEARER));
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

    private static void expectIllegalState(ThrowingRunnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalStateException expected) {
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
