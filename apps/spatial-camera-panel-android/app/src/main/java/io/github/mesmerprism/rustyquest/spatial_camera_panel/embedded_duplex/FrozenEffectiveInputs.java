package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialPeerNetworkProfilePilot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** One immutable data snapshot; it grants no enrollment, Native or socket authority. */
final class FrozenEffectiveInputs {
    interface Deriver { String derive(String exactRequest) throws Exception; }
    final EmbeddedDuplexPackagedInputs verifiedBase;
    final long generation;
    final String lineageJson;
    private final Map<String, String> documents;
    private static final class FreezeSeal { volatile FrozenEffectiveInputs snapshot; }
    private final FreezeSeal seal;

    private FrozenEffectiveInputs(EmbeddedDuplexPackagedInputs base, long generation,
            Map<String, String> documents, String lineageJson, FreezeSeal seal) {
        this.verifiedBase = base;
        this.generation = generation;
        this.documents = Collections.unmodifiableMap(new HashMap<>(documents));
        this.lineageJson = lineageJson;
        this.seal = seal;
    }

    static FrozenEffectiveInputs packaged(EmbeddedDuplexPackagedInputs base) throws Exception {
        return packaged(base, 0, null);
    }

    private static FrozenEffectiveInputs packaged(EmbeddedDuplexPackagedInputs base, long generation, FreezeSeal seal) throws Exception {
        JSONObject lineage = new JSONObject().put("$schema", "rusty.quest.embedded_duplex.effective_input_lineage.v1")
                .put("base_manifest_sha256", sha256(base.manifestJson()))
                .put("base_route_sha256", base.digest("route-configuration.json"))
                .put("profile_sha256", JSONObject.NULL)
                .put("effective_route_sha256", base.digest("route-configuration.json"));
        return new FrozenEffectiveInputs(base, generation, base.documentSnapshot(), lineage.toString(), seal);
    }

    /** Captured by the existing runtime generation owner; freeze is irreversible for this selector. */
    static final class Selector {
        private final EmbeddedDuplexPackagedInputs base;
        private final long generation;
        private final Deriver deriver;
        private FrozenEffectiveInputs current;
        private boolean frozen;
        private final FreezeSeal seal = new FreezeSeal();

        Selector(EmbeddedDuplexPackagedInputs base, long generation) throws Exception {
            this(base, generation, EmbeddedDuplexNative::deriveEffectiveInputs);
        }

        Selector(EmbeddedDuplexPackagedInputs base, long generation, Deriver deriver) throws Exception {
            if (generation <= 0 || deriver == null) throw new IllegalArgumentException("effective input owner required");
            this.base = base; this.generation = generation; this.deriver = deriver;
            this.current = packaged(base, generation, seal);
        }

        synchronized FrozenEffectiveInputs current() { return current; }
        synchronized boolean frozen() { return frozen; }

        /** Selection observation only; the runtime owner separately reports actual adoption. */
        synchronized String selectionReadback() throws Exception {
            return new JSONObject().put("$schema", "rusty.quest.embedded_duplex.effective_input_selection.v1")
                    .put("generation", generation).put("phase", frozen ? "frozen" : "selected")
                    .put("installed_role_id", base.selectedRoleId())
                    .put("lineage", new JSONObject(current.lineageJson))
                    .put("peers", new JSONObject(current.json("route-configuration.json")).getJSONArray("peers"))
                    .toString();
        }

        synchronized FrozenEffectiveInputs select(byte[] bytes) throws Exception {
            if (frozen) throw new IllegalStateException("configuration_frozen_for_bootstrap");
            if (bytes == null || bytes.length == 0 || bytes.length > 64 * 1024)
                throw new IllegalArgumentException("profile_byte_bounds");
            byte[] snapshot = bytes.clone();
            Set<String> peers = new HashSet<>();
            JSONArray routePeers = new JSONObject(base.json("route-configuration.json")).getJSONArray("peers");
            for (int i = 0; i < routePeers.length(); i++) peers.add(routePeers.getJSONObject(i).getString("peer_id"));
            // Reuse the complete strict byte/profile owner, including duplicate-key rejection.
            new SpatialPeerNetworkProfilePilot(peers).select(snapshot);
            JSONObject request = new JSONObject().put("$schema", "rusty.quest.embedded_duplex.effective_inputs_request.v1")
                    .put("base_manifest_sha256", sha256(base.manifestJson()))
                    .put("installed_role_id", base.selectedRoleId())
                    .put("documents", new JSONObject(base.documentSnapshot()))
                    .put("profile_json", new String(snapshot, StandardCharsets.UTF_8));
            // Publish exactly once after all typed, native and response checks succeed.
            FrozenEffectiveInputs accepted = fromNative(base, generation, snapshot, deriver.derive(request.toString()), seal);
            current = accepted;
            return accepted;
        }

        synchronized FrozenEffectiveInputs freeze() { frozen = true; seal.snapshot = current; return current; }
    }

    private static FrozenEffectiveInputs fromNative(EmbeddedDuplexPackagedInputs base, long generation,
            byte[] profile, String response, FreezeSeal seal) throws Exception {
        JSONObject result = new JSONObject(response);
        fields(result, "$schema", "documents", "lineage");
        if (!"rusty.quest.embedded_duplex.effective_inputs_result.v1".equals(result.getString("$schema")))
            throw new IllegalStateException("effective input result schema differs");
        JSONObject encoded = result.getJSONObject("documents");
        if (!encoded.keySet().equals(base.documentSnapshot().keySet()))
            throw new IllegalStateException("effective input result inventory differs");
        Map<String, String> docs = new HashMap<>();
        for (String name : encoded.keySet()) docs.put(name, encoded.getString(name));
        for (String name : new String[]{"product-input-manifest.json", "product-spec.json", "accepted-product-lock.json",
                "client-lock.json", "planning-feature-lock.json", "packed-stereo-profile.json"}) {
            if (!base.documentSnapshot().get(name).equals(docs.get(name)))
                throw new IllegalStateException("effective immutable input differs");
        }
        JSONObject lineage = result.getJSONObject("lineage");
        fields(lineage, "$schema", "base_manifest_sha256", "base_route_sha256", "profile_sha256", "effective_route_sha256");
        if (!"rusty.quest.embedded_duplex.effective_input_lineage.v1".equals(lineage.getString("$schema"))
                || !sha256(base.manifestJson()).equals(lineage.getString("base_manifest_sha256"))
                || !base.digest("route-configuration.json").equals(lineage.getString("base_route_sha256"))
                || !sha256(profile).equals(lineage.getString("profile_sha256"))
                || !sha256(docs.get("route-configuration.json")).equals(lineage.getString("effective_route_sha256")))
            throw new IllegalStateException("effective input lineage differs");
        return new FrozenEffectiveInputs(base, generation, docs, lineage.toString(), seal);
    }

    String json(String name) {
        String value = documents.get(name);
        if (value == null) throw new IllegalArgumentException("unknown effective input");
        return value;
    }
    String digest(String name) throws Exception { return sha256(json(name)); }
    EmbeddedDuplexPackagedInputs.InstalledRole role() { return verifiedBase.role(); }
    String selectedRoleId() { return verifiedBase.selectedRoleId(); }
    String lifecycleDigest() throws Exception { return digest(role().lifecyclePath); }

    void requireConsumable() {
        if (seal != null && seal.snapshot != this)
            throw new IllegalStateException("effective_inputs_not_current_frozen_snapshot");
    }

    JSONObject packagedConfigRequest(JSONObject bindings) throws Exception {
        requireConsumable();
        JSONObject request = verifiedBase.packagedConfigRequest(bindings);
        request.put("media_lifecycle_lock", exact(role().lifecyclePath));
        request.put("route_configuration", exact("route-configuration.json"));
        request.put("media_bindings", new JSONArray().put(exact("peer_a_to_peer_b.media-binding.json"))
                .put(exact("peer_b_to_peer_a.media-binding.json")));
        return request;
    }
    private JSONObject exact(String name) throws Exception {
        return new JSONObject().put("json", json(name)).put("sha256", digest(name));
    }
    private static void fields(JSONObject value, String... expected) {
        if (!value.keySet().equals(new HashSet<>(Arrays.asList(expected))))
            throw new IllegalArgumentException("effective input fields differ");
    }
    private static String sha256(String raw) throws Exception { return sha256(raw.getBytes(StandardCharsets.UTF_8)); }
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) text.append(String.format("%02x", value & 255));
        return text.toString();
    }
}
