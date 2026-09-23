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

    @Test public void nativeAssemblyRequestContainsOnlyVerifiedRoleAndExactInputBytes()
            throws Exception {
        EmbeddedDuplexPackagedInputs inputs = Fixture.valid().load(
                EmbeddedDuplexPackagedInputs.InstalledRole.PEER_B);
        JSONObject runtime = new JSONObject()
                .put("adapter_id", "adapter.neutral")
                .put("admission_authority_id", "admission.neutral")
                .put("grant_id", "grant.neutral")
                .put("grant_expires_at_ms", 3000)
                .put("lease_expires_at_ms", 3000)
                .put("max_token_ttl_ms", 1000)
                .put("embedded_duplex", new JSONObject())
                .put("validation_epoch_entropy_hex", repeat('a', 64))
                .put("validation_wall_unix_ms", 1000)
                .put("validation_monotonic_elapsed_ns", 1000);
        JSONObject request = inputs.packagedConfigRequest(runtime);
        assertEquals(PACKAGE, request.getString("package_name"));
        assertEquals("neutral-project", request.getString("expected_project_id"));
        assertEquals("rusty.quest.neutral.effective",
                request.getString("expected_activation_marker"));
        assertEquals("role.fixture.b", request.getString("installed_role_id"));
        assertEquals(inputs.digest("route-configuration.json"),
                request.getJSONObject("route_configuration").getString("sha256"));
        assertEquals(inputs.digest("packed-stereo-profile.json"),
                request.getJSONObject("packed_profile").getString("sha256"));
        assertEquals(inputs.lifecycleJson(),
                request.getJSONObject("media_lifecycle_lock").getString("json"));
        assertEquals(inputs.lifecycleDigest(),
                request.getJSONObject("media_lifecycle_lock").getString("sha256"));
        assertEquals(inputs.digest("peer_a_to_peer_b.media-binding.json"),
                request.getJSONArray("media_bindings").getJSONObject(0).getString("sha256"));
        assertEquals(inputs.digest("peer_b_to_peer_a.media-binding.json"),
                request.getJSONArray("media_bindings").getJSONObject(1).getString("sha256"));
        runtime.put("unexpected", true);
        assertThrows(IllegalArgumentException.class, () -> inputs.packagedConfigRequest(runtime));
    }

    @Test public void preparedBootstrapRejectsChangedNativeRouteAndInputProjection() throws Exception {
        EmbeddedDuplexPackagedInputs inputs = Fixture.valid().load(
                EmbeddedDuplexPackagedInputs.InstalledRole.PEER_B);
        JSONObject runtime = new JSONObject()
                .put("adapter_id", "adapter.neutral")
                .put("admission_authority_id", "admission.neutral")
                .put("grant_id", "grant.neutral")
                .put("grant_expires_at_ms", 3000)
                .put("lease_expires_at_ms", 3000)
                .put("max_token_ttl_ms", 1000)
                .put("embedded_duplex", new JSONObject())
                .put("validation_epoch_entropy_hex", repeat('a', 64))
                .put("validation_wall_unix_ms", 1000)
                .put("validation_monotonic_elapsed_ns", 1000);
        EmbeddedDuplexBootstrap.Prepared prepared = EmbeddedDuplexBootstrap.prepare(inputs, runtime,
                request -> fakeAssembly(new JSONObject(request), null).toString());
        assertEquals("peer.fixture.b", prepared.localPeerId);
        assertEquals("peer.fixture.a", prepared.remotePeerId);
        assertEquals("runtime.device.fixture.b", prepared.outgoingRuntimeSpecId);
        assertEquals(20_000_000L, prepared.maxPairDeltaNs);
        byte[] remotePublic = new byte[32];
        Arrays.fill(remotePublic, (byte) 0x11);
        JSONObject startup = new JSONObject()
                .put("remote_key_id", "ed25519." + sha256(remotePublic))
                .put("remote_public_key_hex", repeat('1', 64))
                .put("route_grant_id", "grant.route")
                .put("executor_generation", 9)
                .put("device_peers", new JSONArray()
                        .put(new JSONObject().put("device_id", "device.fixture.a")
                                .put("peer_id", "peer.fixture.a"))
                        .put(new JSONObject().put("device_id", "device.fixture.b")
                                .put("peer_id", "peer.fixture.b")));
        JSONObject replay = new JSONObject().put("pending_request_sha256", new JSONObject())
                .put("terminal", new JSONObject());
        JSONObject bootstrap = EmbeddedDuplexBootstrap.runtimeBootstrap(prepared,
                "ed25519." + repeat('2', 64), startup, replay, replay);
        assertEquals("peer.fixture.b", bootstrap.getString("local_peer_id"));
        assertEquals("runtime.device.fixture.a", bootstrap.getString("incoming_runtime_spec_id"));
        startup.getJSONArray("device_peers").getJSONObject(0).put("peer_id", "peer.fixture.b");
        assertThrows(IllegalStateException.class, () -> EmbeddedDuplexBootstrap.runtimeBootstrap(
                prepared, "ed25519." + repeat('2', 64), startup, replay, replay));
        for (String changed : new String[] {"local_peer_id", "remote_control", "exact_input_sha256"}) {
            assertThrows(IllegalStateException.class, () -> EmbeddedDuplexBootstrap.prepare(inputs,
                    runtime, request -> fakeAssembly(new JSONObject(request), changed).toString()));
        }
    }

    private static JSONObject fakeAssembly(JSONObject request, String changed) throws Exception {
        JSONArray mediaBindings = request.getJSONArray("media_bindings");
        JSONObject digests = new JSONObject()
                .put("product_spec", request.getJSONObject("product_spec").getString("sha256"))
                .put("product_lock", request.getJSONObject("product_lock").getString("sha256"))
                .put("client_lock", request.getJSONObject("client_lock").getString("sha256"))
                .put("media_lifecycle_lock", request.getJSONObject("media_lifecycle_lock").getString("sha256"))
                .put("app_feature_lock", request.getJSONObject("app_feature_lock").getString("sha256"))
                .put("media_bindings", new JSONArray().put(mediaBindings.getJSONObject(0).getString("sha256"))
                        .put(mediaBindings.getJSONObject(1).getString("sha256")));
        if ("exact_input_sha256".equals(changed)) digests.put("client_lock", repeat('0', 64));
        JSONObject route = new JSONObject()
                .put("route_configuration_sha256", "sha256:" + request.getJSONObject("route_configuration").getString("sha256"))
                .put("local_peer_id", "local_peer_id".equals(changed) ? "peer.foreign" : "peer.fixture.b")
                .put("remote_peer_id", "peer.fixture.a")
                .put("outgoing_runtime_spec_id", "runtime.device.fixture.b")
                .put("incoming_runtime_spec_id", "runtime.device.fixture.a")
                .put("max_pair_delta_ns", 20_000_000L)
                .put("local_control", new JSONObject().put("host", "192.0.2.2").put("port", 20002))
                .put("remote_control", new JSONObject().put("host", "192.0.2.1")
                        .put("port", "remote_control".equals(changed) ? 20999 : 20001));
        return new JSONObject().put("$schema", "rusty.quest.embedded_duplex.packaged_config_result.v1")
                .put("runtime_config_json", "{}")
                .put("runtime_config_sha256", sha256(bytes("{}")))
                .put("exact_input_sha256", digests)
                .put("packaged_route", route);
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
            fixture.files.put("packed-stereo-profile.json", bytes("{\"max_pair_delta_ns\":20000000}"));
            fixture.files.put("peer_a_to_peer_b.media-binding.json", binding("device.fixture.a"));
            fixture.files.put("peer_b_to_peer_a.media-binding.json", binding("device.fixture.b"));
            fixture.files.put("route-configuration.json", bytes(new JSONObject().put("peers", new JSONArray()
                    .put(new JSONObject().put("installed_role_id", "role.fixture.a").put("device_id", "device.fixture.a")
                            .put("peer_id", "peer.fixture.a").put("control_endpoint",
                                    new JSONObject().put("host", "192.0.2.1").put("port", 20001)))
                    .put(new JSONObject().put("installed_role_id", "role.fixture.b").put("device_id", "device.fixture.b")
                            .put("peer_id", "peer.fixture.b").put("control_endpoint",
                                    new JSONObject().put("host", "192.0.2.2").put("port", 20002)))).toString()));
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
                    .put("runtime_spec_id", "runtime." + sourceDeviceId)
                    .put("plan", new JSONObject().put("lanes", new JSONArray()
                            .put(new JSONObject().put("source_device_id", sourceDeviceId)))))).toString());
        }

        private static byte[] lifecycle(String id, String rawFeatureSha256) throws Exception {
            return bytes(new JSONObject().put("id", id)
                    .put("project_id", "neutral-project")
                    .put("activation_effective_marker", "rusty.quest.neutral.effective")
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
