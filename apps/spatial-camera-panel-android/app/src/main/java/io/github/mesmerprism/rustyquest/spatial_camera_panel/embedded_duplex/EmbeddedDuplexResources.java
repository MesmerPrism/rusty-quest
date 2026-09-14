package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import io.github.mesmerprism.rustyquest.media.MediaProductBinding;
import io.github.mesmerprism.rustyquest.media.PackedStereoMediaOwnerSet;
import io.github.mesmerprism.rustyquest.media.PackedStereoMediaReceiver;
import io.github.mesmerprism.rustyquest.media.PackedStereoMediaSourceRuntime;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owns the one outgoing capture graph and the independently assigned incoming sink. */
final class EmbeddedDuplexResources {
    private final long generation;
    private final PackedStereoMediaSourceRuntime.Pipeline pipeline;
    private final PackedStereoMediaOwnerSet outgoing;
    private final EmbeddedDuplexReceiver incoming;
    private final MediaProductBinding binding;

    // nativeInitialization is returned directly by initializeRuntime after all
    // packaged locks have validated; it is never read from an Intent/Bundle.
    EmbeddedDuplexResources(Context context, EmbeddedDuplexDisplay display,
            JSONObject nativeInitialization, long maxPairDeltaNs) throws Exception {
        if (!"rusty.quest.embedded_duplex.runtime_initialized.v1".equals(
                nativeInitialization.getString("$schema")) || maxPairDeltaNs <= 0L || maxPairDeltaNs > 100_000_000L) {
            throw new IllegalArgumentException("embedded resource initialization");
        }
        generation = nativeInitialization.getLong("executor_generation");
        JSONObject outgoingSpec = nativeInitialization.getJSONObject("outgoing_runtime_spec");
        JSONObject incomingSpec = nativeInitialization.getJSONObject("incoming_runtime_spec");
        Lane source = new Lane(outgoingSpec);
        Lane sink = new Lane(incomingSpec);
        String leftCamera = camera(source.source, "left");
        String rightCamera = camera(source.source, "right");
        if (!"camera2_mediacodec_surface".equals(source.source.getString("source_kind"))) {
            throw new IllegalArgumentException("embedded product requires Camera2 stereo source");
        }
        incoming = new EmbeddedDuplexReceiver(generation, display,
                sink.endpoint.getString("source_host"), sink.endpoint.getInt("source_port"),
                sink.width, sink.height, sink.fps,
                new PackedStereoMediaReceiver.Bounds(64 * 1024, sink.maxPacketBytes,
                        sink.width, sink.height, 12, 3000, 4000, 100, 15000, 10000, 8, 250));
        pipeline = PackedStereoMediaSourceRuntime.createPipeline(context, source.plan.getString("session_id"),
                source.source.getString("source_kind"), source.endpoint.getString("source_host"),
                source.endpoint.getInt("source_port"), source.width, source.height,
                source.width / 2, source.height, source.fps, source.bitrate,
                leftCamera, rightCamera, maxPairDeltaNs);
        outgoing = new PackedStereoMediaOwnerSet(generation, pipeline);
        try {
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
            // No owner action has run yet; pipeline construction creates no camera,
            // codec or network worker. Remove its unstarted runtime registration.
            pipeline.close();
            throw invalid;
        }
    }

    long generation() { return generation; }
    MediaProductBinding binding() { return binding; }
    EmbeddedDuplexReceiver incoming() { return incoming; }
    JSONObject sourceSnapshot() throws Exception { return pipeline.snapshot(); }

    boolean productResourcesTerminal() {
        if (!incoming.snapshot().terminal()) return false;
        for (String kind : new String[] {"source", "processor", "route", "socket", "codec", "cleanup"}) {
            if (!outgoing.provider(kind).snapshot().terminal()) return false;
        }
        return true;
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
        }
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
