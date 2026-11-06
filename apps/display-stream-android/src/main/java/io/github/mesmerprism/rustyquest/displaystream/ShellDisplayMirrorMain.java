package io.github.mesmerprism.rustyquest.displaystream;

import android.os.SystemClock;
import org.json.JSONObject;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independently authored shell display producer. Control stdout is not media TCP. */
public final class ShellDisplayMirrorMain {
    private ShellDisplayMirrorMain() { }
    public static void main(String[] args) {
        try { System.exit(run(args)); }
        catch (Exception error) { System.err.println("display_stream_start_rejected"); System.exit(2); }
    }

    private static int run(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new IllegalStateException("shell UID required");
        Map<String, String> options = parse(args);
        String host = options.getOrDefault("host", "127.0.0.1");
        if (!"127.0.0.1".equals(host)) throw new IllegalArgumentException("loopback source required");
        int port = number(options, "port", 9079, 1, 65535);
        int displayId = number(options, "display-id", 0, 0, 32);
        int width = number(options, "width", 1920, 320, 4096);
        int height = number(options, "height", 1080, 240, 4096);
        if ((width & 1) != 0 || (height & 1) != 0) throw new IllegalArgumentException("even dimensions required");
        int fps = number(options, "fps", 30, 1, 90);
        int bitrate = number(options, "bitrate-bps", 8000000, 100000, 80000000);
        int duration = number(options, "duration-seconds", 0, 0, 3600);
        boolean controlled = options.containsKey("invocation-id");
        if (controlled != options.containsKey("generation") || controlled != options.containsKey("stop-nonce"))
            throw new IllegalArgumentException("complete control identity required");
        String invocation = options.getOrDefault("invocation-id", java.util.UUID.randomUUID().toString().replace("-", ""));
        String generation = options.getOrDefault("generation", "legacy");
        String nonce = options.getOrDefault("stop-nonce", java.util.UUID.randomUUID().toString().replace("-", ""));
        DisplayStreamAttempt attempt = new DisplayStreamAttempt(invocation, generation, nonce);
        AtomicBoolean acceptedStop = new AtomicBoolean();
        AtomicBoolean controlFailed = new AtomicBoolean();
        long startTicks = processStartTicks();
        System.out.println(identity(attempt, startTicks).put("schema", "rusty.quest.display_stream.hello.v1"));
        System.out.flush();
        attempt.start(a -> {
            SocketChannel channel = SocketChannel.open();
            a.ownChannel(channel);
            channel.configureBlocking(true);
            channel.socket().connect(new InetSocketAddress(host, port), 5000);
            channel.socket().setTcpNoDelay(true);
            H264SurfaceEncoder encoder = H264SurfaceEncoder.create(width, height, bitrate, fps);
            a.ownResource(encoder::close);
            HiddenDisplayMirror.DisplayInfo display = HiddenDisplayMirror.displayInfo(displayId);
            HiddenDisplayMirror mirror = HiddenDisplayMirror.start(displayId, width, height, encoder.inputSurface());
            a.ownResource(mirror::close);
            JSONObject metadata = new JSONObject().put("schema", "rusty.quest.display_stream.metadata.v1")
                    .put("source_kind", "shell_display_mirror").put("capture_authority", "android_shell_uid")
                    .put("transport", "adb_reverse_tcp").put("encoder", "android_mediacodec_surface")
                    .put("encoder_name", encoder.encoderName()).put("low_latency_requested", true)
                    .put("packet_io", "gathered_bytebuffer").put("display_id", displayId)
                    .put("source_width", display.width).put("source_height", display.height)
                    .put("width", width).put("height", height).put("fps", fps).put("bitrate_bps", bitrate)
                    .put("invocation_id", invocation).put("generation", generation)
                    .put("source_elapsed_clock", "android_elapsedRealtimeNanos_at_emission");
            Rmanvid1Writer writer = new Rmanvid1Writer(channel);
            writer.writeHeader(width, height, metadata);
            H264SurfaceEncoder.PacketSink sink = (pts, flags, payload) -> writer.writePacket(pts,
                    flags, payload, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis() * 1000000L);
            encoder.requestSyncFrame();
            long deadline = duration > 0 ? SystemClock.elapsedRealtime() + duration * 1000L : Long.MAX_VALUE;
            while (!a.stopRequested() && SystemClock.elapsedRealtime() < deadline) encoder.drainAvailable(10000, sink);
            if (!a.stopRequested()) {
                encoder.signalEndOfInputStream();
                for (int i = 0; i < 50 && !encoder.drainAvailable(20000, sink); i++) { }
            }
        });
        if (controlled) {
            Thread control = new Thread(() -> {
                try {
                    byte[] line = DisplayStreamControl.line(System.in);
                    if (line == null || !DisplayStreamControl.matches(line, attempt)) controlFailed.set(true);
                    else {
                        acceptedStop.set(true); // Publish the accepted control before waking the writer.
                        attempt.requestStop(invocation, generation, nonce);
                    }
                } catch (Exception error) { controlFailed.set(true); }
                finally { attempt.stopOwned(); }
            }, "RustyQuestDisplayControl");
            control.setDaemon(true);
            control.start();
        }
        // Only this coordinator can observe real worker death and attest retirement.
        DisplayStreamAttempt.Snapshot result = attempt.awaitRetirement(0);
        boolean success = result.successful() && (!controlled || acceptedStop.get()) && !controlFailed.get();
        JSONObject terminal = identity(attempt, startTicks)
                .put("schema", "rusty.quest.display_stream.terminal.v1")
                .put("stop_observed", acceptedStop.get()).put("worker_retired", result.workerRetired)
                .put("resources_retired", result.resourcesRetired)
                .put("state", success ? "retired" : "failed")
                .put("failure_code", success ? "NONE" : (controlFailed.get() ? "CONTROL_LOST" : "RETIREMENT_UNCONFIRMED"))
                .put("failures", new org.json.JSONArray(result.failures));
        System.out.println(terminal); System.out.flush();
        return success ? 0 : 2;
    }

    private static JSONObject identity(DisplayStreamAttempt a, long start) throws Exception {
        return new JSONObject().put("invocation_id", a.invocationId).put("generation", a.generation)
                .put("stop_nonce", a.stopNonce).put("pid", android.os.Process.myPid())
                .put("uid", android.os.Process.myUid()).put("start_ticks", start);
    }
    private static long processStartTicks() throws Exception {
        String stat = new String(Files.readAllBytes(Paths.get("/proc/self/stat")), java.nio.charset.StandardCharsets.US_ASCII);
        return Long.parseLong(stat.substring(stat.lastIndexOf(") ") + 2).trim().split(" +")[19]);
    }
    private static Map<String, String> parse(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        String allowed = "|host|port|display-id|width|height|fps|bitrate-bps|duration-seconds|invocation-id|generation|stop-nonce|";
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length || !args[i].startsWith("--")) throw new IllegalArgumentException("closed options required");
            String key = args[i].substring(2);
            if (!allowed.contains("|" + key + "|") || result.put(key, args[i + 1]) != null)
                throw new IllegalArgumentException("unknown or duplicate option");
        }
        return result;
    }
    private static int number(Map<String, String> values, String key, int fallback, int min, int max) {
        int value = Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException("option out of range");
        return value;
    }
}
