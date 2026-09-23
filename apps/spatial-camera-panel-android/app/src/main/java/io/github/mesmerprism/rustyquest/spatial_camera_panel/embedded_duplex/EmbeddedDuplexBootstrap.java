package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Process-only preparation. No Activity, endpoint, or camera is retained here. */
final class EmbeddedDuplexBootstrap {
    interface ConfigAssembler { String assemble(String exactRequestJson) throws Exception; }

    static final class Prepared {
        final String runtimeConfigJson;
        final String runtimeConfigSha256;
        final String localPeerId;
        final String remotePeerId;
        final String localDeviceId;
        final String remoteDeviceId;
        final String outgoingRuntimeSpecId;
        final String incomingRuntimeSpecId;
        final String routeConfigurationSha256;
        final String localControlHost;
        final int localControlPort;
        final String remoteControlHost;
        final int remoteControlPort;
        final long maxPairDeltaNs;

        private Prepared(JSONObject result, JSONObject local, JSONObject remote) throws Exception {
            runtimeConfigJson = result.getString("runtime_config_json");
            runtimeConfigSha256 = result.getString("runtime_config_sha256");
            JSONObject route = result.getJSONObject("packaged_route");
            localPeerId = route.getString("local_peer_id");
            remotePeerId = route.getString("remote_peer_id");
            localDeviceId = local.getString("device_id");
            remoteDeviceId = remote.getString("device_id");
            outgoingRuntimeSpecId = route.getString("outgoing_runtime_spec_id");
            incomingRuntimeSpecId = route.getString("incoming_runtime_spec_id");
            routeConfigurationSha256 = route.getString("route_configuration_sha256");
            localControlHost = route.getJSONObject("local_control").getString("host");
            localControlPort = route.getJSONObject("local_control").getInt("port");
            remoteControlHost = route.getJSONObject("remote_control").getString("host");
            remoteControlPort = route.getJSONObject("remote_control").getInt("port");
            maxPairDeltaNs = route.getLong("max_pair_delta_ns");
        }
    }

    private EmbeddedDuplexBootstrap() {}

    static Prepared prepare(Context context, EmbeddedDuplexPackagedInputs.InstalledRole role,
            JSONObject runtimeBindings) throws Exception {
        if (!BuildConfig.EMBEDDED_DUPLEX_PRODUCT_INPUTS_ENABLED) {
            throw new IllegalStateException("embedded product inputs are disabled");
        }
        Context app = context.getApplicationContext();
        if (app == null) throw new IllegalStateException("application context unavailable");
        EmbeddedDuplexPackagedInputs inputs = EmbeddedDuplexPackagedInputs.load(app,
                BuildConfig.EMBEDDED_DUPLEX_PRODUCT_MANIFEST_SHA256, role);
        return prepare(inputs, runtimeBindings, EmbeddedDuplexNative::assemblePackagedConfig);
    }

    static Prepared prepare(EmbeddedDuplexPackagedInputs inputs, JSONObject runtimeBindings,
            ConfigAssembler assembler) throws Exception {
        if (inputs == null || runtimeBindings == null || assembler == null) {
            throw new IllegalArgumentException("embedded bootstrap inputs");
        }
        JSONObject request = inputs.packagedConfigRequest(runtimeBindings);
        String response = assembler.assemble(request.toString());
        if (response == null || response.length() == 0 || response.length() > 2 * 1024 * 1024) {
            throw new IllegalStateException("native packaged configuration unavailable");
        }
        JSONObject result = new JSONObject(response);
        fields(result, "$schema", "runtime_config_json", "runtime_config_sha256",
                "exact_input_sha256", "packaged_route");
        if (!"rusty.quest.embedded_duplex.packaged_config_result.v1".equals(result.getString("$schema"))
                || !result.getString("runtime_config_sha256").matches("[0-9a-f]{64}")
                || !result.getString("runtime_config_sha256").equals(sha256(
                        result.getString("runtime_config_json").getBytes(StandardCharsets.UTF_8)))) {
            throw new IllegalStateException("native packaged configuration shape");
        }
        JSONObject exact = result.getJSONObject("exact_input_sha256");
        fields(exact, "product_spec", "product_lock", "client_lock", "media_lifecycle_lock",
                "app_feature_lock", "media_bindings");
        JSONArray bindings = exact.getJSONArray("media_bindings");
        if (bindings.length() != 2
                || !inputs.digest("product-spec.json").equals(exact.getString("product_spec"))
                || !inputs.digest("accepted-product-lock.json").equals(exact.getString("product_lock"))
                || !inputs.digest("client-lock.json").equals(exact.getString("client_lock"))
                || !inputs.lifecycleDigest().equals(exact.getString("media_lifecycle_lock"))
                || !inputs.digest("planning-feature-lock.json").equals(exact.getString("app_feature_lock"))
                || !inputs.digest("peer_a_to_peer_b.media-binding.json").equals(bindings.getString(0))
                || !inputs.digest("peer_b_to_peer_a.media-binding.json").equals(bindings.getString(1))) {
            throw new IllegalStateException("native packaged input closure differs");
        }
        JSONObject route = result.getJSONObject("packaged_route");
        fields(route, "route_configuration_sha256", "local_peer_id", "remote_peer_id",
                "outgoing_runtime_spec_id", "incoming_runtime_spec_id", "max_pair_delta_ns",
                "local_control", "remote_control");
        JSONObject localControl = route.getJSONObject("local_control");
        JSONObject remoteControl = route.getJSONObject("remote_control");
        fields(localControl, "host", "port");
        fields(remoteControl, "host", "port");
        JSONObject packagedRoute = new JSONObject(inputs.json("route-configuration.json"));
        JSONArray peers = packagedRoute.getJSONArray("peers");
        if (peers.length() != 2) throw new IllegalStateException("packaged peer cardinality");
        JSONObject local = null, remote = null;
        for (int i = 0; i < 2; i++) {
            JSONObject peer = peers.getJSONObject(i);
            if (inputs.selectedRoleId().equals(peer.getString("installed_role_id"))) local = peer;
            else remote = peer;
        }
        if (local == null || remote == null
                || !route.getString("route_configuration_sha256")
                        .equals("sha256:" + inputs.digest("route-configuration.json"))
                || !route.getString("local_peer_id").equals(local.getString("peer_id"))
                || !route.getString("remote_peer_id").equals(remote.getString("peer_id"))
                || !sameEndpoint(localControl, local.getJSONObject("control_endpoint"))
                || !sameEndpoint(remoteControl, remote.getJSONObject("control_endpoint"))
                || route.getLong("max_pair_delta_ns") != new JSONObject(inputs.json("packed-stereo-profile.json"))
                        .getLong("max_pair_delta_ns")) {
            throw new IllegalStateException("native route projection differs from installed role");
        }
        String outgoing = new JSONObject(inputs.json(inputs.role().bindingPath))
                .getJSONObject("quest").getJSONObject("spec").getString("runtime_spec_id");
        EmbeddedDuplexPackagedInputs.InstalledRole opposite =
                inputs.role() == EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A
                        ? EmbeddedDuplexPackagedInputs.InstalledRole.PEER_B
                        : EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A;
        String incoming = new JSONObject(inputs.json(opposite.bindingPath))
                .getJSONObject("quest").getJSONObject("spec").getString("runtime_spec_id");
        if (!outgoing.equals(route.getString("outgoing_runtime_spec_id"))
                || !incoming.equals(route.getString("incoming_runtime_spec_id"))) {
            throw new IllegalStateException("native runtime direction differs from installed role");
        }
        return new Prepared(result, local, remote);
    }

    /** Adds only fresh enrollment inputs to the immutable packaged route. */
    static JSONObject runtimeBootstrap(Prepared prepared, String localKeyId, JSONObject startup,
            JSONObject replay, JSONObject activationReplay) throws Exception {
        if (prepared == null || startup == null || replay == null || activationReplay == null) {
            throw new IllegalArgumentException("runtime bootstrap inputs");
        }
        fields(startup, "remote_key_id", "remote_public_key_hex", "route_grant_id",
                "executor_generation", "device_peers");
        fields(replay, "pending_request_sha256", "terminal");
        fields(activationReplay, "pending_request_sha256", "terminal");
        String remotePublic = startup.getString("remote_public_key_hex");
        String remoteKeyId = startup.getString("remote_key_id");
        if (!remotePublic.matches("[0-9a-f]{64}")
                || !remoteKeyId.equals("ed25519." + sha256(hexBytes(remotePublic)))
                || localKeyId == null || !localKeyId.matches("ed25519\\.[0-9a-f]{64}")
                || localKeyId.equals(remoteKeyId)
                || !startup.getString("route_grant_id")
                        .matches("[a-z][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)+")) {
            throw new IllegalStateException("fresh enrolled signing identity differs");
        }
        long generation = startup.getLong("executor_generation");
        if (generation <= 0L) throw new IllegalStateException("executor generation");
        JSONArray peers = startup.getJSONArray("device_peers");
        if (peers.length() != 2) throw new IllegalStateException("device/peer placement cardinality");
        Set<String> seenDevices = new HashSet<>(), seenPeers = new HashSet<>();
        for (int i = 0; i < peers.length(); i++) {
            JSONObject peer = peers.getJSONObject(i);
            fields(peer, "device_id", "peer_id");
            if (!seenDevices.add(peer.getString("device_id"))
                    || !seenPeers.add(peer.getString("peer_id"))) {
                throw new IllegalStateException("ambiguous device/peer placement");
            }
        }
        if (!seenDevices.contains(prepared.localDeviceId)
                || !seenDevices.contains(prepared.remoteDeviceId)
                || !seenPeers.contains(prepared.localPeerId)
                || !seenPeers.contains(prepared.remotePeerId)) {
            throw new IllegalStateException("foreign device/peer placement");
        }
        for (int i = 0; i < peers.length(); i++) {
            JSONObject peer = peers.getJSONObject(i);
            if ((prepared.localDeviceId.equals(peer.getString("device_id"))
                    && !prepared.localPeerId.equals(peer.getString("peer_id")))
                    || (prepared.remoteDeviceId.equals(peer.getString("device_id"))
                    && !prepared.remotePeerId.equals(peer.getString("peer_id")))) {
                throw new IllegalStateException("crossed device/peer placement");
            }
        }
        return new JSONObject()
                .put("local_peer_id", prepared.localPeerId)
                .put("remote_peer_id", prepared.remotePeerId)
                .put("local_key_id", localKeyId)
                .put("remote_key_id", remoteKeyId)
                .put("remote_public_key_hex", remotePublic)
                .put("runtime_spec_id", prepared.outgoingRuntimeSpecId)
                .put("incoming_runtime_spec_id", prepared.incomingRuntimeSpecId)
                .put("route_grant_id", startup.getString("route_grant_id"))
                .put("route_configuration_sha256", prepared.routeConfigurationSha256)
                .put("executor_generation", generation)
                .put("device_peers", new JSONArray(peers.toString()))
                .put("replay", new JSONObject(replay.toString()))
                .put("activation_replay", new JSONObject(activationReplay.toString()));
    }

    private static byte[] hexBytes(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static boolean sameEndpoint(JSONObject result, JSONObject packaged) throws Exception {
        return result.getString("host").equals(packaged.getString("host"))
                && result.getInt("port") == packaged.getInt("port");
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(64);
        for (byte item : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }

    private static void fields(JSONObject value, String... names) {
        Set<String> expected = new HashSet<>(Arrays.asList(names));
        if (value.length() != expected.size()) throw new IllegalStateException("bootstrap field closure");
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) if (!expected.contains(keys.next())) {
            throw new IllegalStateException("bootstrap unknown field");
        }
    }
}
