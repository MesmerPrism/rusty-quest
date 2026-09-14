package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Exact APK asset closure. Only the installed role selects a lifecycle document. */
final class EmbeddedDuplexPackagedInputs {
    enum InstalledRole {
        PEER_A("peer_a", "peer_a.media-lifecycle-lock.json", "peer_a_to_peer_b.media-binding.json"),
        PEER_B("peer_b", "peer_b.media-lifecycle-lock.json", "peer_b_to_peer_a.media-binding.json");
        final String id, lifecyclePath, bindingPath;
        InstalledRole(String id, String lifecyclePath, String bindingPath) {
            this.id = id; this.lifecyclePath = lifecyclePath; this.bindingPath = bindingPath;
        }
        static InstalledRole parse(String value) {
            for (InstalledRole role : values()) if (role.id.equals(value)) return role;
            throw new IllegalArgumentException("unsupported installed role");
        }
    }

    interface AssetReader {
        InputStream open(String name) throws Exception;
        String[] list() throws Exception;
    }

    private static final String PREFIX = "embedded-duplex/";
    private static final String MANIFEST = "product-input-manifest.json";
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;
    private static final Set<String> FILES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "product-spec.json", "accepted-product-lock.json", "client-lock.json",
            "peer_a.media-lifecycle-lock.json", "peer_b.media-lifecycle-lock.json",
            "planning-feature-lock.json", "packed-stereo-profile.json",
            "peer_a_to_peer_b.media-binding.json", "peer_b_to_peer_a.media-binding.json",
            "route-configuration.json")));
    private final Map<String, String> documents;
    private final Map<String, String> digests;
    private final InstalledRole role;
    private final String selectedRoleId;
    private final String manifestJson;

    private EmbeddedDuplexPackagedInputs(Map<String, String> documents, Map<String, String> digests,
            InstalledRole role, String selectedRoleId, String manifestJson) {
        this.documents = Collections.unmodifiableMap(documents);
        this.digests = Collections.unmodifiableMap(digests);
        this.role = role;
        this.selectedRoleId = selectedRoleId;
        this.manifestJson = manifestJson;
    }

    /** expectedManifestSha256 must come from the build-fixed manifest field. */
    static EmbeddedDuplexPackagedInputs load(Context context, String expectedManifestSha256,
            InstalledRole role) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (info.signingInfo == null) throw new IllegalStateException("APK signer unavailable");
        Signature[] signatures = info.signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length != 1) throw new IllegalStateException("APK signer cardinality");
        return load(new AssetReader() {
            @Override public InputStream open(String name) throws Exception {
                return context.getAssets().open(PREFIX + name);
            }
            @Override public String[] list() throws Exception {
                return context.getAssets().list("embedded-duplex");
            }
        }, expectedManifestSha256, context.getPackageName(), sha256(signatures[0].toByteArray()), role);
    }

    static EmbeddedDuplexPackagedInputs load(AssetReader assets, String expectedManifestSha256,
            String actualPackage, String actualSigner, InstalledRole role) throws Exception {
        if (role == null || expectedManifestSha256 == null || !expectedManifestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("packaged input selection is disabled or malformed");
        }
        Set<String> expectedFiles = new HashSet<>(FILES); expectedFiles.add(MANIFEST);
        String[] listed = assets.list();
        if (listed == null || listed.length != expectedFiles.size()
                || !new HashSet<>(Arrays.asList(listed)).equals(expectedFiles)) {
            throw new IllegalStateException("packaged asset inventory differs");
        }
        byte[] manifestBytes = read(assets, MANIFEST, 128 * 1024);
        if (!sha256(manifestBytes).equals(expectedManifestSha256)) throw new IllegalStateException("manifest bytes differ");
        String manifestText = utf8(manifestBytes);
        JSONObject manifest = new JSONObject(manifestText);
        fields(manifest, "schema", "closure_sha256", "source_authorities", "package",
                "directional_bindings", "artifacts", "runtime_identity_packaged", "device_private_key_packaged");
        if (!"rusty.quest.embedded_duplex.product_input_manifest.v1".equals(manifest.getString("schema"))
                || manifest.getBoolean("runtime_identity_packaged") || manifest.getBoolean("device_private_key_packaged")) {
            throw new IllegalStateException("packaged manifest contract");
        }
        JSONObject identity = manifest.getJSONObject("package");
        fields(identity, "application_id", "signing_certificate_sha256");
        if (!actualPackage.equals(identity.getString("application_id"))
                || !actualSigner.equals(identity.getString("signing_certificate_sha256"))) {
            throw new IllegalStateException("installed APK identity differs");
        }
        Map<String, String> docs = new HashMap<>(), hashes = new HashMap<>();
        JSONArray artifacts = manifest.getJSONArray("artifacts");
        if (artifacts.length() != FILES.size()) throw new IllegalStateException("artifact cardinality");
        int total = 0; StringBuilder closure = new StringBuilder();
        for (int i = 0; i < artifacts.length(); i++) {
            JSONObject item = artifacts.getJSONObject(i); fields(item, "path", "sha256", "size_bytes");
            String name = item.getString("path"), digest = item.getString("sha256");
            long size = item.getLong("size_bytes");
            if (!FILES.contains(name) || hashes.containsKey(name) || !digest.matches("[0-9a-f]{64}")
                    || size <= 0 || size > MAX_FILE_BYTES || total + size > MAX_TOTAL_BYTES) {
                throw new IllegalStateException("artifact bounds or identity");
            }
            byte[] data = read(assets, name, (int) size);
            if (data.length != size || !sha256(data).equals(digest)) throw new IllegalStateException("artifact bytes differ");
            total += data.length; docs.put(name, utf8(data)); hashes.put(name, digest);
            if (i != 0) closure.append('\n');
            closure.append(name).append('=').append(digest);
        }
        if (!sha256(closure.toString().getBytes(StandardCharsets.UTF_8)).equals(manifest.getString("closure_sha256"))) {
            throw new IllegalStateException("artifact closure differs");
        }
        JSONObject authorities = manifest.getJSONObject("source_authorities");
        fields(authorities, "manifold_commit", "manifold_tree", "planning_feature_lock_sha256",
                "planning_project_revision", "planning_lock_revision");
        if (!authorities.getString("manifold_commit").matches("[0-9a-f]{40}")
                || !authorities.getString("manifold_tree").matches("[0-9a-f]{40}")
                || !hashes.get("planning-feature-lock.json").equals(
                        authorities.getString("planning_feature_lock_sha256"))
                || authorities.getLong("planning_project_revision") <= 0L
                || authorities.getLong("planning_lock_revision") <= 0L) {
            throw new IllegalStateException("packaged source identity differs");
        }
        JSONArray directions = manifest.getJSONArray("directional_bindings");
        if (directions.length() != 2) throw new IllegalStateException("two installed roles required");
        Set<InstalledRole> roles = new HashSet<>();
        Set<String> roleIds = new HashSet<>();
        Map<InstalledRole, String> roleIdByRole = new HashMap<>();
        JSONObject routeConfiguration = new JSONObject(docs.get("route-configuration.json"));
        JSONArray routePeers = routeConfiguration.getJSONArray("peers");
        if (routePeers.length() != 2) throw new IllegalStateException("two route peers required");
        for (int i = 0; i < directions.length(); i++) {
            JSONObject direction = directions.getJSONObject(i);
            fields(direction, "installed_role", "installed_role_id", "lifecycle_lock_path", "media_binding_path",
                    "lifecycle_lock_sha256", "runtime_spec_canonical_sha256",
                    "manifold_descriptor_canonical_sha256", "owner_selection_count");
            InstalledRole selected = InstalledRole.parse(direction.getString("installed_role"));
            String installedRoleId = direction.getString("installed_role_id");
            JSONObject binding = new JSONObject(docs.get(selected.bindingPath));
            JSONArray lanes = binding.getJSONObject("quest").getJSONObject("spec")
                    .getJSONObject("plan").getJSONArray("lanes");
            if (lanes.length() != 1) throw new IllegalStateException("single outgoing role binding required");
            String sourceDeviceId = lanes.getJSONObject(0).getString("source_device_id");
            int matchingRolePeers = 0;
            for (int peerIndex = 0; peerIndex < routePeers.length(); peerIndex++) {
                JSONObject peer = routePeers.getJSONObject(peerIndex);
                if (sourceDeviceId.equals(peer.getString("device_id"))
                        && installedRoleId.equals(peer.getString("installed_role_id"))) {
                    matchingRolePeers++;
                }
            }
            if (!roles.add(selected) || !selected.lifecyclePath.equals(direction.getString("lifecycle_lock_path"))
                    || !installedRoleId.matches("[a-z][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)+")
                    || !roleIds.add(installedRoleId) || matchingRolePeers != 1
                    || !selected.bindingPath.equals(direction.getString("media_binding_path"))
                    || !hashes.get(selected.lifecyclePath).equals(direction.getString("lifecycle_lock_sha256"))
                    || direction.getInt("owner_selection_count") != 7
                    || !direction.getString("runtime_spec_canonical_sha256").matches("sha256:[0-9a-f]{64}")
                    || !direction.getString("manifold_descriptor_canonical_sha256").matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalStateException("installed role binding differs");
            }
            roleIdByRole.put(selected, installedRoleId);
        }
        return new EmbeddedDuplexPackagedInputs(docs, hashes, role, roleIdByRole.get(role), manifestText);
    }

    String json(String name) {
        String value = documents.get(name);
        if (value == null) throw new IllegalArgumentException("unknown packaged input");
        return value;
    }
    String digest(String name) {
        String value = digests.get(name);
        if (value == null) throw new IllegalArgumentException("unknown packaged input");
        return value;
    }
    String lifecycleJson() { return json(role.lifecyclePath); }
    String lifecycleDigest() { return digest(role.lifecyclePath); }
    InstalledRole role() { return role; }
    String selectedRoleId() { return selectedRoleId; }
    String manifestJson() { return manifestJson; }

    private static void fields(JSONObject value, String... names) {
        Set<String> wanted = new HashSet<>(Arrays.asList(names));
        if (value.length() != wanted.size()) throw new IllegalArgumentException("closed document fields");
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) if (!wanted.contains(keys.next())) throw new IllegalArgumentException("unknown document field");
    }
    private static byte[] read(AssetReader assets, String name, int maxBytes) throws Exception {
        try (InputStream input = assets.open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) throw new IllegalStateException("asset read made no progress");
                if (out.size() + count > maxBytes) throw new IllegalStateException("asset byte bounds");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
    private static String utf8(byte[] bytes) throws Exception {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder text = new StringBuilder(64);
        for (byte value : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
}
