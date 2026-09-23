package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Assume;

public final class EmbeddedDuplexPackagedInputsTest {
    private static final String PACKAGE = "io.github.mesmerprism.test.embedded";
    private static final String SIGNER = repeat('a', 64);
    private static final String FEATURE_ID = "neutral-peer-input";
    private static final String MODULE_ID = "neutral-peer-module";
    private static final String RECEIPT_SCHEMA = "rusty.quest.neutral_peer_input.receipt.v1";
    private static final String RESOLVER = repeat('5', 64);

    @Test public void exactClosureKeepsBothBindingsAndSelectsOnlyInstalledLifecycle()
            throws Exception {
        Fixture fixture = Fixture.valid();
        EmbeddedDuplexPackagedInputs peerA = fixture.load(
                EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A);
        EmbeddedDuplexPackagedInputs peerB = fixture.load(
                EmbeddedDuplexPackagedInputs.InstalledRole.PEER_B);

        assertEquals("lifecycle.a", new JSONObject(peerA.lifecycleJson()).getString("id"));
        assertEquals("lifecycle.b", new JSONObject(peerB.lifecycleJson()).getString("id"));
        assertEquals("device.fixture.a", new JSONObject(
                peerA.json("peer_a_to_peer_b.media-binding.json"))
                .getJSONObject("quest").getJSONObject("spec").getJSONObject("plan")
                .getJSONArray("lanes").getJSONObject(0).getString("source_device_id"));
        assertEquals("device.fixture.b", new JSONObject(
                peerA.json("peer_b_to_peer_a.media-binding.json"))
                .getJSONObject("quest").getJSONObject("spec").getJSONObject("plan")
                .getJSONArray("lanes").getJSONObject(0).getString("source_device_id"));
        assertEquals(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A, peerA.role());
        assertEquals(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_B, peerB.role());
        assertEquals("role.fixture.a", peerA.selectedRoleId());
        assertEquals("role.fixture.b", peerB.selectedRoleId());
    }

    @Test public void installedIdentityAndBuildFixedManifestHashAreMandatory() throws Exception {
        Fixture fixture = Fixture.valid();
        assertThrows(IllegalStateException.class, () -> EmbeddedDuplexPackagedInputs.load(
                fixture, repeat('0', 64), PACKAGE, SIGNER,
                EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));
        assertThrows(IllegalStateException.class, () -> fixture.loadAs(
                "io.github.mesmerprism.foreign", SIGNER));
        assertThrows(IllegalStateException.class, () -> fixture.loadAs(PACKAGE, repeat('b', 64)));
    }

    @Test public void changedExtraAndMissingAssetsReject() throws Exception {
        Fixture changed = Fixture.valid();
        changed.files.put("client-lock.json", bytes("{\"changed\":true}"));
        assertThrows(IllegalStateException.class,
                () -> changed.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));

        Fixture extra = Fixture.valid();
        extra.files.put("extra.json", bytes("{}"));
        assertThrows(IllegalStateException.class,
                () -> extra.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));

        Fixture missing = Fixture.valid();
        missing.files.remove("product-spec.json");
        assertThrows(IllegalStateException.class,
                () -> missing.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));
    }

    @Test public void pathTraversalAndDuplicateArtifactIdentityReject() throws Exception {
        Fixture traversal = Fixture.valid();
        traversal.artifacts().getJSONObject(0).put("path", "../product-spec.json");
        traversal.resealManifest(false);
        assertThrows(IllegalStateException.class,
                () -> traversal.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));

        Fixture duplicate = Fixture.valid();
        JSONArray artifacts = duplicate.artifacts();
        artifacts.put(1, new JSONObject(artifacts.getJSONObject(0).toString()));
        duplicate.resealManifest(false);
        assertThrows(IllegalStateException.class,
                () -> duplicate.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));
    }

    @Test public void roleCollisionAndDirectionalClosureDamageReject() throws Exception {
        Fixture collision = Fixture.valid();
        JSONArray directions = collision.manifest.getJSONArray("directional_bindings");
        directions.put(1, new JSONObject(directions.getJSONObject(0).toString()));
        collision.resealManifest(false);
        assertThrows(IllegalStateException.class,
                () -> collision.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));

        Fixture wrongLifecycle = Fixture.valid();
        wrongLifecycle.manifest.getJSONArray("directional_bindings").getJSONObject(0)
                .put("lifecycle_lock_path", "peer_b.media-lifecycle-lock.json");
        wrongLifecycle.resealManifest(false);
        assertThrows(IllegalStateException.class,
                () -> wrongLifecycle.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));

        Fixture wrongClosure = Fixture.valid();
        wrongClosure.manifest.put("closure_sha256", repeat('c', 64));
        wrongClosure.resealManifest(false);
        assertThrows(IllegalStateException.class,
                () -> wrongClosure.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));

        Fixture wrongAuthority = Fixture.valid();
        wrongAuthority.manifest.getJSONObject("source_authorities")
                .put("planning_feature_lock_sha256", repeat('e', 64));
        wrongAuthority.resealManifest(false);
        assertThrows(IllegalStateException.class,
                () -> wrongAuthority.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));

        Fixture wrongResolver = Fixture.valid();
        wrongResolver.manifest.getJSONObject("source_authorities")
                .put("planning_feature_resolver_fingerprint", repeat('e', 64));
        wrongResolver.resealManifest(false);
        assertThrows(IllegalStateException.class,
                () -> wrongResolver.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));

        Fixture wrongSelectedFeature = Fixture.valid();
        wrongSelectedFeature.manifest.getJSONObject("source_authorities")
                .put("planning_feature_id", "foreign-feature");
        wrongSelectedFeature.resealManifest(false);
        assertThrows(IllegalStateException.class,
                () -> wrongSelectedFeature.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));

        Fixture wrongRoleId = Fixture.valid();
        wrongRoleId.manifest.getJSONArray("directional_bindings").getJSONObject(0)
                .put("installed_role_id", "role.fixture.b");
        wrongRoleId.resealManifest(false);
        assertThrows(IllegalStateException.class,
                () -> wrongRoleId.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));
    }

    @Test public void hashSizeAndArtifactBoundsRejectBeforeUse() throws Exception {
        Fixture hash = Fixture.valid();
        hash.artifacts().getJSONObject(0).put("sha256", repeat('d', 64));
        hash.resealManifest(false);
        assertThrows(IllegalStateException.class,
                () -> hash.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));

        Fixture size = Fixture.valid();
        size.artifacts().getJSONObject(0).put("size_bytes", 1);
        size.resealManifest(false);
        assertThrows(IllegalStateException.class,
                () -> size.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));

        Fixture oversized = Fixture.valid();
        oversized.artifacts().getJSONObject(0).put("size_bytes", 2 * 1024 * 1024 + 1L);
        oversized.resealManifest(false);
        assertThrows(IllegalStateException.class,
                () -> oversized.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));
    }

    @Test public void malformedUtf8RejectsEvenWhenHashAndClosureMatch() throws Exception {
        Fixture fixture = Fixture.valid();
        fixture.files.put("product-spec.json", new byte[] {(byte) 0xc3, (byte) 0x28});
        fixture.rebuildArtifactsAndManifest();
        assertThrows(Exception.class,
                () -> fixture.load(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A));
    }

    @Test public void actualGeneratedProducerClosureLoadsWhenProvided() throws Exception {
        String rootValue = System.getenv("RUSTY_QUEST_EMBEDDED_DUPLEX_GENERATED_INPUT_ROOT");
        Assume.assumeTrue("actual generated closure not supplied", rootValue != null && !rootValue.isBlank());
        Path root = Path.of(rootValue);
        byte[] manifestBytes = Files.readAllBytes(root.resolve("product-input-manifest.json"));
        JSONObject generatedManifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        EmbeddedDuplexPackagedInputs.AssetReader reader = new EmbeddedDuplexPackagedInputs.AssetReader() {
            @Override public InputStream open(String name) throws Exception {
                return Files.newInputStream(root.resolve(name));
            }
            @Override public String[] list() throws Exception {
                try (java.util.stream.Stream<Path> paths = Files.list(root)) {
                    return paths.map(path -> path.getFileName().toString()).toArray(String[]::new);
                }
            }
        };
        String packageName = generatedManifest.getJSONObject("package").getString("application_id");
        String signer = generatedManifest.getJSONObject("package").getString("signing_certificate_sha256");
        EmbeddedDuplexPackagedInputs peerA = EmbeddedDuplexPackagedInputs.load(
                reader, sha256(manifestBytes), packageName, signer,
                EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A);
        EmbeddedDuplexPackagedInputs peerB = EmbeddedDuplexPackagedInputs.load(
                reader, sha256(manifestBytes), packageName, signer,
                EmbeddedDuplexPackagedInputs.InstalledRole.PEER_B);
        JSONArray generatedDirections = generatedManifest.getJSONArray("directional_bindings");
        assertEquals(generatedDirections.getJSONObject(0).getString("installed_role_id"),
                peerA.selectedRoleId());
        assertEquals(generatedDirections.getJSONObject(1).getString("installed_role_id"),
                peerB.selectedRoleId());
    }

    private static final class Fixture implements EmbeddedDuplexPackagedInputs.AssetReader {
        final LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        JSONObject manifest;
        String manifestSha256;

        static Fixture valid() throws Exception {
            Fixture fixture = new Fixture();
            fixture.files.put("product-spec.json", bytes("{\"id\":\"product\"}"));
            fixture.files.put("accepted-product-lock.json", bytes("{\"id\":\"lock\"}"));
            fixture.files.put("client-lock.json", bytes("{\"id\":\"client\"}"));
            fixture.files.put("planning-feature-lock.json", bytes("{\"id\":\"feature\"}"));
            String rawFeatureSha256 = sha256(fixture.files.get("planning-feature-lock.json"));
            fixture.files.put("peer_a.media-lifecycle-lock.json", lifecycle("lifecycle.a", rawFeatureSha256));
            fixture.files.put("peer_b.media-lifecycle-lock.json", lifecycle("lifecycle.b", rawFeatureSha256));
            fixture.files.put("packed-stereo-profile.json", bytes("{\"id\":\"stereo\"}"));
            fixture.files.put("peer_a_to_peer_b.media-binding.json", binding("device.fixture.a"));
            fixture.files.put("peer_b_to_peer_a.media-binding.json", binding("device.fixture.b"));
            fixture.files.put("route-configuration.json", bytes(new JSONObject().put("peers", new JSONArray()
                    .put(new JSONObject().put("installed_role_id", "role.fixture.a").put("device_id", "device.fixture.a"))
                    .put(new JSONObject().put("installed_role_id", "role.fixture.b").put("device_id", "device.fixture.b"))).toString()));
            fixture.rebuildArtifactsAndManifest();
            return fixture;
        }

        void rebuildArtifactsAndManifest() throws Exception {
            files.remove("product-input-manifest.json");
            JSONArray artifacts = new JSONArray();
            Map<String, String> hashes = new LinkedHashMap<>();
            StringBuilder closure = new StringBuilder();
            int index = 0;
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                String digest = sha256(entry.getValue());
                hashes.put(entry.getKey(), digest);
                artifacts.put(new JSONObject().put("path", entry.getKey()).put("sha256", digest)
                        .put("size_bytes", entry.getValue().length));
                if (index++ != 0) closure.append('\n');
                closure.append(entry.getKey()).append('=').append(digest);
            }
            manifest = new JSONObject().put("schema",
                    "rusty.quest.embedded_duplex.product_input_manifest.v1")
                    .put("closure_sha256", sha256(bytes(closure.toString())))
                    .put("source_authorities", new JSONObject()
                            .put("manifold_commit", repeat('3', 40))
                            .put("manifold_tree", repeat('4', 40))
                            .put("planning_feature_lock_sha256",
                                    hashes.get("planning-feature-lock.json"))
                            .put("planning_feature_id", FEATURE_ID)
                            .put("planning_feature_module_id", MODULE_ID)
                            .put("planning_feature_activation_receipt_schema", RECEIPT_SCHEMA)
                            .put("planning_feature_resolver_fingerprint", RESOLVER)
                            .put("planning_project_revision", 7)
                            .put("planning_lock_revision", 11))
                    .put("package", new JSONObject().put("application_id", PACKAGE)
                            .put("signing_certificate_sha256", SIGNER))
                    .put("directional_bindings", directions(hashes))
                    .put("artifacts", artifacts)
                    .put("runtime_identity_packaged", false)
                    .put("device_private_key_packaged", false);
            resealManifest(true);
        }

        JSONArray artifacts() throws Exception { return manifest.getJSONArray("artifacts"); }

        void resealManifest(boolean preserveClosure) throws Exception {
            if (!preserveClosure) {
                // Keep the deliberately modified closure fields; only authenticate exact manifest bytes.
            }
            byte[] encoded = bytes(manifest.toString());
            files.put("product-input-manifest.json", encoded);
            manifestSha256 = sha256(encoded);
        }

        EmbeddedDuplexPackagedInputs load(EmbeddedDuplexPackagedInputs.InstalledRole role)
                throws Exception {
            return EmbeddedDuplexPackagedInputs.load(this, manifestSha256, PACKAGE, SIGNER, role);
        }

        EmbeddedDuplexPackagedInputs loadAs(String packageName, String signer) throws Exception {
            return EmbeddedDuplexPackagedInputs.load(this, manifestSha256, packageName, signer,
                    EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A);
        }

        @Override public InputStream open(String name) throws Exception {
            byte[] value = files.get(name);
            if (value == null) throw new FileNotFoundException(name);
            return new ByteArrayInputStream(value);
        }

        @Override public String[] list() {
            List<String> names = new ArrayList<>(files.keySet());
            return names.toArray(new String[0]);
        }

        private static JSONArray directions(Map<String, String> hashes) throws Exception {
            JSONArray result = new JSONArray();
            result.put(direction(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A, hashes));
            result.put(direction(EmbeddedDuplexPackagedInputs.InstalledRole.PEER_B, hashes));
            return result;
        }

        private static JSONObject direction(EmbeddedDuplexPackagedInputs.InstalledRole role,
                Map<String, String> hashes) throws Exception {
            return new JSONObject().put("installed_role", role.id)
                    .put("installed_role_id", role == EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A
                            ? "role.fixture.a" : "role.fixture.b")
                    .put("lifecycle_lock_path", role.lifecyclePath)
                    .put("media_binding_path", role.bindingPath)
                    .put("lifecycle_lock_sha256", hashes.get(role.lifecyclePath))
                    .put("runtime_spec_canonical_sha256", "sha256:" + repeat('1', 64))
                    .put("manifold_descriptor_canonical_sha256", "sha256:" + repeat('2', 64))
                    .put("owner_selection_count", 7);
        }

        private static byte[] binding(String sourceDeviceId) throws Exception {
            return bytes(new JSONObject().put("quest", new JSONObject().put("spec", new JSONObject()
                    .put("plan", new JSONObject().put("lanes", new JSONArray()
                            .put(new JSONObject().put("source_device_id", sourceDeviceId)))))).toString());
        }

        private static byte[] lifecycle(String id, String rawFeatureSha256) throws Exception {
            return bytes(new JSONObject().put("id", id)
                    .put("app_feature_id", FEATURE_ID)
                    .put("app_feature_module_id", MODULE_ID)
                    .put("app_feature_activation_receipt_schema", RECEIPT_SCHEMA)
                    .put("app_feature_project_revision", 7)
                    .put("app_feature_lock_revision", 11)
                    .put("app_feature_lock_sha256", "sha256:" + rawFeatureSha256)
                    .put("app_feature_lock_fingerprint", "sha256:" + rawFeatureSha256)
                    .put("app_feature_resolver_fingerprint", "sha256:" + RESOLVER).toString());
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder text = new StringBuilder(64);
        for (byte item : digest) text.append(String.format(Locale.ROOT, "%02x", item & 255));
        return text.toString();
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        Arrays.fill(result, value);
        return new String(result);
    }
}
