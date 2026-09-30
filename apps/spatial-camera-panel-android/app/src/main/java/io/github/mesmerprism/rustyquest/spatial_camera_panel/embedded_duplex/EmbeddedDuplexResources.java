package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import io.github.mesmerprism.rustyquest.media.MediaProductBinding;
import io.github.mesmerprism.rustyquest.media.PackedStereoMediaOwnerSet;
import io.github.mesmerprism.rustyquest.media.PackedStereoMediaReceiver;
import io.github.mesmerprism.rustyquest.media.PackedStereoMediaSourceRuntime;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owns the one outgoing capture graph and the independently assigned incoming sink. */
final class EmbeddedDuplexResources implements EmbeddedDuplexActivationGate.Target {
    private final long generation;
    private final OwnStereoCaptureRuntime ownCapture;
    private final EmbeddedDuplexDisplay display;
    private PackedStereoMediaSourceRuntime.Pipeline pipeline;
    private PackedStereoMediaOwnerSet outgoing;
    private final EmbeddedDuplexReceiver incoming;
    private MediaProductBinding binding;
    private final Context context;
    private final JSONObject nativeInitialization, outgoingSpec;
    private final long maxPairDeltaNs;
    private final Lane source;
    private final String leftCamera, rightCamera;
    private boolean installationAttempted;
    private final String incomingRuntimeSpecId;

    // nativeInitialization is returned directly by initializeRuntime after all
    // packaged locks have validated; it is never read from an Intent/Bundle.
    EmbeddedDuplexResources(Context context, EmbeddedDuplexDisplay display,
            JSONObject nativeInitialization, long maxPairDeltaNs) throws Exception {
        if (!"rusty.quest.embedded_duplex.runtime_initialized.v1".equals(
                nativeInitialization.getString("$schema")) || maxPairDeltaNs <= 0L || maxPairDeltaNs > 100_000_000L) {
            throw new IllegalArgumentException("embedded resource initialization");
        }
        this.context = context;
        this.nativeInitialization = nativeInitialization;
        this.maxPairDeltaNs = maxPairDeltaNs;
        generation = nativeInitialization.getLong("executor_generation");
        this.display = display;
        ownCapture = nativeInitialization.optBoolean("own_stereo_capture_enabled", false)
                ? OwnStereoCaptureRuntime.forApplication(context) : null;
        outgoingSpec = nativeInitialization.getJSONObject("outgoing_runtime_spec");
        JSONObject incomingSpec = nativeInitialization.getJSONObject("incoming_runtime_spec");
        incomingRuntimeSpecId = incomingSpec.getString("runtime_spec_id");
        source = new Lane(outgoingSpec);
        Lane sink = new Lane(incomingSpec);
        leftCamera = camera(source.source, "left");
        rightCamera = camera(source.source, "right");
        if (!"camera2_mediacodec_surface".equals(source.source.getString("source_kind"))) {
            throw new IllegalArgumentException("embedded product requires Camera2 stereo source");
        }
        incoming = new EmbeddedDuplexReceiver(generation, display,
                sink.sinkTransportHost, sink.sinkTransportPort, sink.sourceTransportHost,
                sink.width, sink.height, sink.fps,
                // Bounded headroom for codec delay and render callbacks. The
                // first-frame deadline and explicit overflow failures remain enforced.
                new PackedStereoMediaReceiver.Bounds(64 * 1024, sink.maxPacketBytes,
                        sink.width, sink.height, 32, 3000, 4000, 100, 15000, 10000, 8, 250));
    }

    /** Host retains this owner before any Own capture or display effect runs. */
    void install() throws Exception {
        if (installationAttempted) throw new IllegalStateException("resource installation already attempted");
        installationAttempted = true;
        try {
        if (ownCapture != null) {
            io.github.mesmerprism.rustyquest.media.PackedStereoCaptureOwner capture = ownCapture.startAccepted(
                    new OwnStereoCaptureRuntime.Configuration(source.width / 2, source.height,
                            source.fps, leftCamera, rightCamera, maxPairDeltaNs));
            display.activateOwnProjection();
            pipeline = PackedStereoMediaSourceRuntime.createSharedCapturePipeline(context, source.plan.getString("session_id"),
                    source.source.getString("source_kind"), source.endpoint.getString("source_host"),
                    source.endpoint.getInt("source_port"), source.width, source.height,
                    source.width / 2, source.height, source.fps, source.bitrate,
                    leftCamera, rightCamera, maxPairDeltaNs, capture, generation);
        } else {
        pipeline = PackedStereoMediaSourceRuntime.createPipeline(context, source.plan.getString("session_id"),
                source.source.getString("source_kind"), source.endpoint.getString("source_host"),
                source.endpoint.getInt("source_port"), source.width, source.height,
                source.width / 2, source.height, source.fps, source.bitrate,
                leftCamera, rightCamera, maxPairDeltaNs);
        }
        pipeline.configureAcceptedLanRoute(source.sourceTransportHost,
                source.sinkTransportHost, source.sinkTransportPort);
        } catch (Exception invalid) {
            try { incoming.closeUnstartedAndVerify(); }
            catch (Exception cleanup) { invalid.addSuppressed(cleanup); }
            throw invalid;
        }
        try {
            outgoing = new PackedStereoMediaOwnerSet(generation, pipeline);
            MediaProductBinding.Builder builder = new MediaProductBinding.Builder(outgoingSpec.getString("runtime_spec_id"));
            JSONArray outgoingPlacements = nativeInitialization.getJSONArray("owner_placements");
            JSONArray incomingPlacements = nativeInitialization.getJSONArray("incoming_owner_placements");
            int localOutgoing = 0;
            int localIncoming = 0;
            for (int i = 0; i < outgoingPlacements.length(); i++) {
                JSONObject placement = outgoingPlacements.getJSONObject(i);
                if (!"local".equals(placement.getJSONObject("target").getString("placement"))) continue;
                String kind = placement.getString("owner_kind");
                if ("sink".equals(kind)) throw new IllegalArgumentException("outgoing sink must be on peer");
                builder.bind(kind, placement.getString("owner_id"), placement.getString("provider_kind"),
                        placement.getString("resource_id"), outgoing.provider(kind));
                localOutgoing++;
            }
            for (int i = 0; i < incomingPlacements.length(); i++) {
                JSONObject placement = incomingPlacements.getJSONObject(i);
                if (!"local".equals(placement.getJSONObject("target").getString("placement"))) continue;
                if (!"sink".equals(placement.getString("owner_kind"))) {
                    throw new IllegalArgumentException("incoming product may assign only this sink locally");
                }
                builder.bind("sink", placement.getString("owner_id"), placement.getString("provider_kind"),
                        placement.getString("resource_id"), incoming);
                localIncoming++;
            }
            if (outgoingPlacements.length() != 7 || incomingPlacements.length() != 7
                    || localOutgoing != 6 || localIncoming != 1) {
                throw new IllegalArgumentException("embedded seven-family placement shape");
            }
            binding = builder.build();
        } catch (Exception invalid) {
            // Peer Start has not run. Own capture may already be Live; it remains
            // process-owned. The Host retains this partial object and its exact
            // created Peer owners for no-media cleanup and the later Own stop.
            try { incoming.closeUnstartedAndVerify(); }
            catch (Exception cleanup) { invalid.addSuppressed(cleanup); }
            try { if (pipeline != null) pipeline.close(); }
            catch (Exception cleanup) { invalid.addSuppressed(cleanup); }
            throw invalid;
        }
    }

    @Override public long generation() { return generation; }
    MediaProductBinding binding() { return binding; }
    EmbeddedDuplexReceiver incoming() { return incoming; }
    JSONObject sourceSnapshot() throws Exception {
        JSONObject snapshot = pipeline.snapshot();
        if (ownCapture != null) {
            snapshot.put("capture_scope", "peer_subscription_only");
            snapshot.put("own_app_capture", ownCapture.phase().name());
            io.github.mesmerprism.rustyquest.media.PackedStereoCaptureOwner retained = ownCapture.retainedCapture();
            snapshot.put("own_app_capture_fresh", retained != null && retained.fresh());
        }
        return snapshot;
    }
    boolean ownAppCaptureEnabled() { return ownCapture != null; }
    String ownAppCaptureState() { return ownCapture == null ? "disabled" : ownCapture.phase().name(); }

    @Override public String incomingDiagnostic() { return incoming.activationDiagnostic(); }
    private static final int MAX_ACTIVATION_RECORDS = 16;
    private static final int MAX_ACTIVATION_RECORD_CHARS = 2048;
    private static final int MAX_ACTIVATION_JOURNAL_BYTES = MAX_ACTIVATION_RECORDS * (MAX_ACTIVATION_RECORD_CHARS + 1);
    private static final long PROCESS_STARTED_WALL_MS = System.currentTimeMillis();
    private static final long PROCESS_STARTED_ELAPSED_NS = android.os.SystemClock.elapsedRealtimeNanos();
    private static long activationRecordSequence;
    /** Bounded app-private journal; closed fields only. Pulled with run-as before any reset. */
    @Override public void recordActivationFailure(String closedRecord) {
        if (!closedActivationRecord(closedRecord, false)) return;
        synchronized (EmbeddedDuplexResources.class) {
            java.io.File file = new java.io.File(context.getNoBackupFilesDir(),
                    "embedded-duplex-activation-diagnostics.v1.txt");
            android.util.AtomicFile atomic = new android.util.AtomicFile(file);
            java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
            try {
                try (java.io.FileInputStream input = atomic.openRead()) {
                    byte[] bytes = new byte[MAX_ACTIVATION_JOURNAL_BYTES + 1];
                    int used = 0, count;
                    while (used < bytes.length && (count = input.read(bytes, used, bytes.length - used)) > 0) used += count;
                    if (used <= MAX_ACTIVATION_JOURNAL_BYTES) {
                        for (String line : new String(bytes, 0, used, java.nio.charset.StandardCharsets.US_ASCII).split("\n")) {
                            if (closedActivationRecord(line, true)) lines.addLast(line);
                        }
                    }
                }
            } catch (java.io.IOException unreadable) { lines.clear(); }
            String attributed = "schema=rusty.quest.embedded_duplex.activation_failure.v1 wallMs="
                    + System.currentTimeMillis() + " processStartedWallMs=" + PROCESS_STARTED_WALL_MS
                    + " processStartedElapsedNs=" + PROCESS_STARTED_ELAPSED_NS
                    + " processPid=" + android.os.Process.myPid()
                    + " recordSequence=" + (++activationRecordSequence)
                    + " executorGeneration=" + generation + " " + closedRecord;
            if (!closedActivationRecord(attributed, true)) return;
            lines.addLast(attributed);
            while (lines.size() > MAX_ACTIVATION_RECORDS) lines.removeFirst();
            java.io.FileOutputStream out = null;
            try {
                out = atomic.startWrite();
                out.write((String.join("\n", lines) + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                atomic.finishWrite(out);
            } catch (java.io.IOException failed) {
                if (out != null) atomic.failWrite(out);
            }
        }
    }
    @Override public String incomingRuntimeSpecId() { return incomingRuntimeSpecId; }
    @Override public void awaitFirstRenderedFrame() {
        throw new UnsupportedOperationException("legacy rendered-frame startup is not the embedded Surface-image path");
    }
    @Override public long[] currentIncomingFrame(long maxAgeNs) { return incoming.currentFrame(maxAgeNs); }
    @Override public void awaitFirstSurfaceImage() throws Exception { incoming.awaitFirstSurfaceImage(); }
    @Override public long[] currentIncomingAcquiredFrame(long maxAgeNs) {
        return incoming.currentAcquiredFrameTimed(maxAgeNs);
    }
    @Override public long[] currentIncomingEffectiveFrame(long maxAgeNs) {
        return incoming.currentEffectiveFrameTimed(maxAgeNs);
    }
    @Override public long routeGeneration() { return incoming.routeGeneration(); }
    @Override public long decoderToken() { return incoming.decoderToken(); }
    @Override public long readerGeneration() { return incoming.readerGeneration(); }
    @Override public void activateIncomingProjection() {
        display.activatePeerProjection(routeGeneration(), decoderToken(), readerGeneration());
    }
    @Override public long[] currentProjection() { return display.currentProjection(routeGeneration()); }

    boolean productResourcesTerminal() {
        if (!incoming.snapshot().terminal()) return false;
        // A pipeline is retained immediately on return from its factory. Its
        // real physical barrier is required even if owner-set construction failed.
        if (pipeline != null) {
            try { pipeline.requireStopped(); }
            catch (IllegalStateException pending) { return false; }
        }
        // No outgoing registry exists when creation was never completed. This
        // is installation-owned absence, never a substitute for Own/renderer proof.
        if (outgoing == null) return true;
        for (String kind : new String[] {"source", "processor", "route", "socket", "codec", "cleanup"}) {
            if (!outgoing.provider(kind).snapshot().terminal()) return false;
        }
        return true;
    }

    void closeUnstartedAndVerify() {
        incoming.closeUnstartedAndVerify();
        if (pipeline != null) pipeline.close();
        if (!productResourcesTerminal()) {
            throw new IllegalStateException("unstarted product resources remain live");
        }
    }

    private static String camera(JSONObject source, String role) throws Exception {
        JSONArray cameras = source.getJSONObject("camera").getJSONArray("camera_ids");
        String selected = null;
        for (int i = 0; i < cameras.length(); i++) {
            JSONObject camera = cameras.getJSONObject(i);
            if (role.equals(camera.getString("track_role"))) {
                if (selected != null) throw new IllegalArgumentException("duplicate camera role");
                selected = camera.getString("camera_id");
            }
        }
        if (selected == null || selected.isEmpty()) throw new IllegalArgumentException("camera role absent");
        return selected;
    }

    private static final class Lane {
        final JSONObject plan, source, endpoint;
        final String sourceTransportHost, sinkTransportHost;
        final int sinkTransportPort;
        final int width, height, fps, bitrate, maxPacketBytes;
        Lane(JSONObject spec) throws Exception {
            plan = spec.getJSONObject("plan");
            JSONArray lanes = plan.getJSONArray("lanes");
            if (lanes.length() != 1) throw new IllegalArgumentException("one directional packed lane required");
            JSONObject lane = lanes.getJSONObject(0);
            JSONObject media = lane.getJSONObject("media");
            if (!"h264".equals(media.getString("codec"))
                    || !"rmanvid-v4-packed-stereo".equals(media.getString("stream_framing"))
                    || !"lan_tcp".equals(lane.getJSONObject("transport").getString("transport_kind"))) {
                throw new IllegalArgumentException("embedded media contract");
            }
            width = media.getInt("width"); height = media.getInt("height");
            fps = media.getInt("frame_rate_hz"); bitrate = media.getInt("bitrate_bps");
            maxPacketBytes = media.getInt("max_packet_bytes");
            if (width < 320 || width > 4096 || width % 2 != 0 || height < 240 || height > 4096
                    || fps <= 0 || fps > 120 || bitrate <= 0 || maxPacketBytes <= 0 || maxPacketBytes > 8 * 1024 * 1024) {
                throw new IllegalArgumentException("embedded media bounds");
            }
            source = unique(plan.getJSONArray("sources"), "source_id", lane.getString("source_id"));
            JSONObject device = unique(plan.getJSONArray("runtime_endpoints"), "device_id", lane.getString("source_device_id"));
            endpoint = unique(device.getJSONArray("source_bindings"), "source_id", source.getString("source_id"));
            String role = media.getString("track_role");
            JSONObject sinkDevice = unique(plan.getJSONArray("runtime_endpoints"), "device_id", lane.getString("sink_device_id"));
            JSONObject sinkPort = unique(sinkDevice.getJSONArray("transport_receive_ports"), "track_role", role);
            JSONObject route = unique(plan.getJSONArray("transport_routes"), "lane_id", lane.getString("lane_id"));
            sourceTransportHost = numericIpv4(device.getString("transport_bind_host"));
            sinkTransportHost = numericIpv4(sinkDevice.getString("transport_bind_host"));
            sinkTransportPort = sinkPort.getInt("port");
            if (!"stereo".equals(role) || !role.equals(endpoint.getString("track_role"))
                    || !source.getString("device_id").equals(lane.getString("source_device_id"))
                    || lane.getString("source_device_id").equals(lane.getString("sink_device_id"))
                    || !"127.0.0.1".equals(endpoint.getString("source_host"))
                    || endpoint.getInt("source_port") <= 0 || endpoint.getInt("source_port") > 65535
                    || sinkTransportPort <= 0 || sinkTransportPort > 65535
                    || !"direct_tcp_connect".equals(route.getString("route_kind"))
                    || !role.equals(route.getString("track_role"))
                    || !lane.getString("source_device_id").equals(route.getString("source_device_id"))
                    || !lane.getString("sink_device_id").equals(route.getString("sink_device_id"))
                    || !sinkTransportHost.equals(route.getString("connect_host"))
                    || sinkTransportPort != route.getInt("connect_port")) {
                throw new IllegalArgumentException("accepted directional LAN route closure");
            }
        }
    }

    /** Exact closed vocabulary; arbitrary existing lines never enter a rewritten journal. */
    private static boolean closedActivationRecord(String record, boolean attributed) {
        if (record == null || record.length() > MAX_ACTIVATION_RECORD_CHARS || record.isEmpty()) return false;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String field : record.split(" ", -1)) {
            int equals = field.indexOf('=');
            if (equals <= 0 || equals == field.length() - 1) return false;
            String key = field.substring(0, equals), value = field.substring(equals + 1);
            if (!seen.add(key)) return false;
            switch (key) {
                case "stage": if (!value.matches("ARM_PROOF|FIRST_RENDER|NATIVE_ACQUISITION|FIRST_SURFACE_IMAGE|GRAPH_ATTACH|NATIVE_EFFECTIVE")) return false; break;
                case "cause": if (!value.matches("OTHER|CODEC|INTERRUPTED|TIMEOUT|IO|STATE")) return false; break;
                case "code": if (!"ACTIVATION_EFFECT_UNCERTAIN".equals(value)) return false; break;
                case "receiverState": case "connection":
                    if (!value.matches("UNAVAILABLE|UNPREPARED|NEW|ARMED|CONNECTING|LISTENING|DECODER_CONFIGURED|RECEIVING|WAITING_RECONNECT|STOPPING|STOPPED|FAILED")) return false; break;
                case "firstFailure": case "finalFailure":
                    if (!value.matches("NONE|DECODER_CREATE|DECODER_CONFIGURE|DECODER_START|DECODER_INPUT_TIMEOUT|DECODER_INPUT_CAPACITY|IDENTITY_PTS_COLLISION|IDENTITY_WINDOW_OVERFLOW_DECODE|IDENTITY_WINDOW_OVERFLOW_RENDER|OUTPUT_IDENTITY_MISSING|PRE_RENDER_IDENTITY_REJECTED|DECODER_CODEC_EXCEPTION|(?:NO_INCOMING_BYTES|NO_CODEC_CONFIG|NO_KEYFRAME|STREAM)_(?:TIMEOUT|EOF|IO)|REFUSED|CONNECT_TIMEOUT|HEADER_IO|STATE|OTHER")) return false; break;
                case "decoder": if (!value.matches("NONE|HARDWARE|SOFTWARE|UNKNOWN")) return false; break;
                case "counters": if (!"UNAVAILABLE".equals(value)) return false; break;
                case "receiverFirstStage": case "receiverFinalStage":
                    if (!value.matches("NONE|IDLE|LISTENER_BIND|CONNECT|ACCEPT|HEADER|DECODER_CONFIG|PACKET_READ|DECODE")) return false; break;
                case "receiverFirstCause": case "receiverFinalCause":
                    if (!value.matches("NONE|CODEC|REFUSED|CONNECT_TIMEOUT|READ_TIMEOUT|EOF|HEADER_IO|IO|STATE|OTHER")) return false; break;
                case "firstRenderReject":
                    if (!value.matches("NONE|CODEC|STATE|PTS_MISSING|CONNECTION|NOT_READY|TIMESTAMP|AFTER_WITNESS")) return false; break;
                case "firstRenderRejectPendingPtsUs":
                    if (!"-1".equals(value) && !nonnegativeLong(value)) return false; break;
                case "firstRenderRejectMediaTimeUs":
                    if (!canonicalSignedLong(value)) return false; break;
                case "schema": if (!attributed || !"rusty.quest.embedded_duplex.activation_failure.v1".equals(value)) return false; break;
                case "wallMs": case "processStartedWallMs": case "processStartedElapsedNs":
                case "processPid": case "recordSequence": case "executorGeneration":
                    if (!attributed || !nonnegativeLong(value)) return false; break;
                case "packets": case "frames": case "reconnects":
                    if (!"-1".equals(value) && !nonnegativeLong(value)) return false; break;
                case "elapsedMs": case "accepts": case "bytes": case "packetsRead": case "configPackets":
                case "keyframePackets": case "preBootstrapDropped": case "inputs": case "outputs":
                case "releasedForRender": case "renderCallbacks": case "rawRenderCallbacks":
                case "acquiredFrames": case "acquiredSuperseded": case "acquisitionFeedbackRejected":
                case "renderRejectCodec": case "renderRejectState": case "renderRejectMissingPts":
                case "renderRejectConnection": case "renderRejectNotReady": case "renderRejectTimestamp":
                case "renderRejectAfterWitness":
                case "firstRenderRejectPendingCount":
                case "renderSuperseded": case "lateCallbacks":
                case "renderHistoryEvicted": case "preRenderRejected": case "windowOverflows":
                case "maxQueuedWindow": case "maxRenderWindow": case "queuedWindow": case "renderWindow":
                case "renderHistory": case "windowBound":
                    if (!nonnegativeLong(value)) return false; break;
                default: return false;
            }
        }
        String[] required = {"stage", "cause", "elapsedMs", "code", "receiverState", "connection", "packets", "frames", "reconnects"};
        for (String key : required) if (!seen.contains(key)) return false;
        if (attributed) {
            String[] attribution = {"schema", "wallMs", "processStartedWallMs", "processStartedElapsedNs", "processPid", "recordSequence", "executorGeneration"};
            for (String key : attribution) if (!seen.contains(key)) return false;
        }
        return true;
    }

    private static boolean nonnegativeLong(String value) {
        if (!value.matches("[0-9]{1,19}")) return false;
        try { return Long.parseLong(value) >= 0L; }
        catch (NumberFormatException overflow) { return false; }
    }

    private static boolean canonicalSignedLong(String value) {
        if (!value.matches("0|-?[1-9][0-9]{0,18}")) return false;
        try { Long.parseLong(value); return true; }
        catch (NumberFormatException overflow) { return false; }
    }

    private static String numericIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("numeric LAN host required");
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) {
                throw new IllegalArgumentException("numeric LAN host required");
            }
        }
        if ("0".equals(parts[0]) || "127".equals(parts[0]) || Integer.parseInt(parts[0]) >= 224) {
            throw new IllegalArgumentException("unicast LAN host required");
        }
        return host;
    }

    private static JSONObject unique(JSONArray candidates, String field, String expected) throws Exception {
        JSONObject selected = null;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject candidate = candidates.getJSONObject(i);
            if (expected.equals(candidate.getString(field))) {
                if (selected != null) throw new IllegalArgumentException("ambiguous resource binding");
                selected = candidate;
            }
        }
        if (selected == null) throw new IllegalArgumentException("resource binding absent");
        return selected;
    }
}
