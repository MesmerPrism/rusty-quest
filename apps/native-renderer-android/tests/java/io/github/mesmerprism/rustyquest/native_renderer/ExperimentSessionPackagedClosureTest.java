package io.github.mesmerprism.rustyquest.native_renderer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;

public final class ExperimentSessionPackagedClosureTest {
    private static final String MANIFEST_SHA = repeat('3');
    private static final String INVENTORY_SHA = repeat('4');
    private static final String AUDIO_A_SHA = repeat('a');
    private static final String AUDIO_B_SHA = repeat('b');

    public static void main(String[] args) throws Exception {
        validClosureMaterializesExactBytesAndBuildsOnlyAudio();
        inactiveClosureDoesNotMaterialize();
        rejectsWrongAssetCardinalityAndIds();
        rejectsProfileHashBytesBomAndUtf8Damage();
        rejectsOuterSchemaAliasAndDamage();
        rejectsOuterNestedClosureAndProviderDrift();
        rejectsExistingFileTamper();
        if (args.length != 0) {
            if (args.length != 7 || !"--real-closure".equals(args[0])) {
                throw new IllegalArgumentException("expected --real-closure feature-lock profile manifest-sha inventory-sha audio-a audio-b");
            }
            acceptsRealResolvedClosure(args);
        }
        System.out.println("ExperimentSessionPackagedClosureTest PASS");
    }

    private static void acceptsRealResolvedClosure(String[] args) throws Exception {
        byte[] lock = Files.readAllBytes(java.nio.file.Paths.get(args[1]));
        byte[] profile = Files.readAllBytes(java.nio.file.Paths.get(args[2]));
        Path audioA = java.nio.file.Paths.get(args[5]);
        Path audioB = java.nio.file.Paths.get(args[6]);
        Path root = Files.createTempDirectory("rq-real-profile-closure-");
        try {
            ExperimentSessionPackagedClosure.Result result =
                ExperimentSessionPackagedClosure.prepare(lock, destination -> {
                    if (ExperimentSessionPackagedClosure.PROFILE_DESTINATION.equals(destination)) {
                        return profile.clone();
                    }
                    if ("session-audio/condition-a.mp3".equals(destination)) {
                        return Files.readAllBytes(audioA);
                    }
                    if ("session-audio/condition-b.mp3".equals(destination)) {
                        return Files.readAllBytes(audioB);
                    }
                    throw new IllegalArgumentException("unexpected real asset destination");
                }, root, new ExperimentSessionPackagedClosure.Anchors(
                    sha(profile), args[3], args[4]));
            check(result.active && result.audioEntries.length == 2,
                "real resolved three-asset closure is active");
            byte[][] audioBytes = new byte[][] {
                Files.readAllBytes(audioA), Files.readAllBytes(audioB)
            };
            for (int i = 0; i < result.audioEntries.length; i++) {
                check(result.audioEntries[i].sourceBytes == audioBytes[i].length,
                    "real audio byte count is preserved");
                check(result.audioEntries[i].sourceSha256.equals(sha(audioBytes[i])),
                    "real audio hash is preserved");
            }
            check(java.util.Arrays.equals(profile, Files.readAllBytes(root.resolve(
                ExperimentSessionPackagedClosure.MATERIALIZED_DIRECTORY).resolve(
                ExperimentSessionPackagedClosure.MATERIALIZED_FILE))),
                "real profile bytes are materialized unchanged");
        } finally { deleteTree(root); }
    }

    private static void validClosureMaterializesExactBytesAndBuildsOnlyAudio() throws Exception {
        byte[] profile = profile("provider.one", audio("session-audio/condition-a.mp3",
            AUDIO_A_SHA, 101L), audio("session-audio/condition-b.mp3", AUDIO_B_SHA, 202L),
            false).getBytes(StandardCharsets.UTF_8);
        Fixture fixture = fixture(profile, "provider.one", entries(profile, "", false));
        try {
            ExperimentSessionPackagedClosure.Result result = fixture.prepare();
            check(result.active, "linked closure is active");
            check(result.audioEntries.length == 2, "profile entry is excluded from audio");
            check("condition-a".equals(result.audioEntries[0].conditionId)
                && "condition-b".equals(result.audioEntries[1].conditionId),
                "closure audio IDs map to runtime condition IDs");
            check(java.util.Arrays.equals(profile, Files.readAllBytes(fixture.target())),
                "raw profile bytes are materialized unchanged");
            fixture.prepare();
            check(java.util.Arrays.equals(profile, Files.readAllBytes(fixture.target())),
                "matching later start preserves existing bytes");
        } finally { fixture.close(); }
    }

    private static void inactiveClosureDoesNotMaterialize() throws Exception {
        Path root = Files.createTempDirectory("rq-profile-inactive-");
        String lock = "{\"build_inputs\":{\"private_asset_closure\":{"
            + "\"schema\":\"rusty.quest.native_app_private_asset_closure.v1\","
            + "\"mode\":\"inactive\",\"provider_id\":\"\","
            + "\"provider_manifest_sha256\":\"\",\"inventory_sha256\":\"\","
            + "\"asset_count\":0,\"assets\":[]}}}";
        try {
            ExperimentSessionPackagedClosure.Result result =
                ExperimentSessionPackagedClosure.prepare(lock.getBytes(StandardCharsets.UTF_8),
                    destination -> { throw new AssertionError("inactive closure read asset"); },
                    root, new ExperimentSessionPackagedClosure.Anchors("", "", ""));
            check(!result.active && !Files.exists(root.resolve(
                ExperimentSessionPackagedClosure.MATERIALIZED_DIRECTORY)),
                "inactive closure remains unavailable and does not materialize");
        } finally { deleteTree(root); }
    }

    private static void rejectsWrongAssetCardinalityAndIds() throws Exception {
        byte[] profile = validProfile();
        expectRejected(fixture(profile, "provider.one", twoEntries(profile)), "two assets");
        expectRejected(fixture(profile, "provider.one", fourEntries(profile)), "four assets");
        expectRejected(fixture(profile, "provider.one", entries(profile,
            "wrong-audio-a", false)), "missing asset id");
        expectRejected(fixture(profile, "provider.one", entries(profile,
            "condition-audio-a", true)), "duplicate asset id");
    }

    private static void rejectsProfileHashBytesBomAndUtf8Damage() throws Exception {
        byte[] valid = validProfile();
        String damagedEntry = entries(valid, "", false).replace(sha(valid), repeat('9'));
        expectRejected(fixture(valid, "provider.one", damagedEntry, repeat('9')),
            "profile hash mismatch");

        byte[] bom = new byte[valid.length + 3];
        bom[0] = (byte) 0xef; bom[1] = (byte) 0xbb; bom[2] = (byte) 0xbf;
        System.arraycopy(valid, 0, bom, 3, valid.length);
        expectRejected(fixture(bom, "provider.one", entries(bom, "", false)),
            "profile BOM");

        byte[] invalid = new byte[] {'{', (byte) 0xff, '}'};
        expectRejected(fixture(invalid, "provider.one", entries(invalid, "", false)),
            "profile invalid UTF-8");
    }

    private static void rejectsOuterNestedClosureAndProviderDrift() throws Exception {
        String baseA = audio("session-audio/condition-a.mp3", AUDIO_A_SHA, 101L);
        String baseB = audio("session-audio/condition-b.mp3", AUDIO_B_SHA, 202L);
        byte[] nestedDrift = profile("provider.one", baseA, baseB, true)
            .getBytes(StandardCharsets.UTF_8);
        expectRejected(fixture(nestedDrift, "provider.one", entries(nestedDrift, "", false)),
            "outer nested audio drift");

        byte[] valid = profile("provider.one", baseA, baseB, false)
            .getBytes(StandardCharsets.UTF_8);
        String closureDrift = entries(valid, "", false)
            .replace("session-audio/condition-a.mp3", "session-audio/other-a.mp3");
        expectRejected(fixture(valid, "provider.one", closureDrift), "profile closure audio drift");

        byte[] providerDrift = profile("provider.other", baseA, baseB, false)
            .getBytes(StandardCharsets.UTF_8);
        expectRejected(fixture(providerDrift, "provider.one",
            entries(providerDrift, "", false)), "provider drift");
    }

    private static void rejectsOuterSchemaAliasAndDamage() throws Exception {
        String valid = new String(validProfile(), StandardCharsets.UTF_8);
        byte[] aliasOnly = valid.replace(
            "\"schema_id\":\"rusty.viscereality.experiment_session_profile.v1\"",
            "\"schema\":\"rusty.viscereality.experiment_session_profile.v1\"")
            .getBytes(StandardCharsets.UTF_8);
        expectRejected(fixture(aliasOnly, "provider.one", entries(aliasOnly, "", false)),
            "outer schema alias");

        byte[] aliasAlongsideAuthoritative = valid.replace(
            "\"schema_id\":\"rusty.viscereality.experiment_session_profile.v1\"",
            "\"schema_id\":\"rusty.viscereality.experiment_session_profile.v1\","
                + "\"schema\":\"rusty.viscereality.experiment_session_profile.v1\"")
            .getBytes(StandardCharsets.UTF_8);
        expectRejected(fixture(aliasAlongsideAuthoritative, "provider.one",
            entries(aliasAlongsideAuthoritative, "", false)), "outer schema duplicate alias");

        byte[] wrongSchema = valid.replace(
            "rusty.viscereality.experiment_session_profile.v1",
            "rusty.viscereality.experiment_session_profile.v2")
            .getBytes(StandardCharsets.UTF_8);
        expectRejected(fixture(wrongSchema, "provider.one", entries(wrongSchema, "", false)),
            "outer schema identity damage");
    }

    private static void rejectsExistingFileTamper() throws Exception {
        byte[] profile = validProfile();
        Fixture fixture = fixture(profile, "provider.one", entries(profile, "", false));
        try {
            fixture.prepare();
            Files.write(fixture.target(), "tampered".getBytes(StandardCharsets.UTF_8));
            boolean rejected = false;
            try { fixture.prepare(); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "existing profile byte drift is rejected");
            check("tampered".equals(new String(Files.readAllBytes(fixture.target()),
                StandardCharsets.UTF_8)), "tampered existing profile is never overwritten");
        } finally { fixture.close(); }
    }

    private static byte[] validProfile() {
        return profile("provider.one", audio("session-audio/condition-a.mp3", AUDIO_A_SHA, 101L),
            audio("session-audio/condition-b.mp3", AUDIO_B_SHA, 202L), false)
            .getBytes(StandardCharsets.UTF_8);
    }

    private static String profile(String providerId, String audioA, String audioB,
            boolean nestedDrift) {
        String projectedA = nestedDrift
            ? audio("session-audio/condition-a-drift.mp3", AUDIO_A_SHA, 101L) : audioA;
        return "{\"schema_id\":\"rusty.viscereality.experiment_session_profile.v1\","
            + "\"profile_id\":\"viscereality.experiment.synthetic-test.v1\","
            + "\"visibility\":\"private\","
            + "\"recording_default\":true,\"kiosk_requested_default\":true,"
            + "\"non_audio_profile\":{"
            + "\"relative_path\":\"fixtures/native-gpu/viscereality-akd-parameter-envelope.profile.json\","
            + "\"authority\":\"private-authoritative-akd-parameter-envelope\"},"
            + "\"non_audio_profile_sha256\":\"" + repeat('1') + "\","
            + "\"radius_observation\":{"
            + "\"schema_id\":\"rusty.viscereality.radius_observation_profile.v1\","
            + "\"radius_limits_m\":[1,2],"
            + "\"actual_radius\":{"
            + "\"metric_id\":\"effective_world_anchor_scale_m\","
            + "\"runtime_field\":\"privateParticleWorldAnchorScaleM\","
            + "\"settings_key\":\"native_renderer.private_particles.world_anchor.scale_m\","
            + "\"unit\":\"m\"},"
            + "\"progress\":{"
            + "\"runtime_field\":\"privateParticleDriver0Value01\","
            + "\"settings_key\":\"native_renderer.private_particles.driver0.value01\","
            + "\"range\":[0,1]},"
            + "\"deformation_envelopes\":["
            + "{\"name\":\"oblateness\",\"endpoints\":[0.25,0.5],"
            + "\"progress_source\":\"privateParticleDriver0Value01\"},"
            + "{\"name\":\"axis_profile\",\"endpoints\":[2,1],"
            + "\"progress_source\":\"privateParticleDriver0Value01\"}],"
            + "\"provenance\":{"
            + "\"frame\":\"current-submitted-frame-id\","
            + "\"settings_revision\":\"app-effective-settings-revision\","
            + "\"render_session\":\"current-render-session-generation\"}},"
            + "\"conditions\":["
            + outerCondition("condition-a", "Condition 1", audioA) + ","
            + outerCondition("condition-b", "Condition 2", audioB) + "],"
            + "\"runtime_projection\":{"
            + "\"schema\":\"rusty.quest.experiment_session.runtime_projection.v1\","
            + "\"provider_id\":\"" + providerId + "\","
            + "\"effective_radius_profile\":{\"configured_radius_min_m\":1,"
            + "\"configured_radius_max_m\":2,"
            + "\"oblateness\":{\"at_radius_min\":0.25,\"at_radius_max\":0.5},"
            + "\"axis_profile\":{\"at_radius_min\":2,\"at_radius_max\":1}},"
            + "\"conditions\":{"
            + "\"condition-a\":" + projectedCondition(projectedA) + ","
            + "\"condition-b\":" + projectedCondition(audioB) + "}}}";
    }

    private static String outerCondition(String id, String label, String audio) {
        return "{\"condition_id\":\"" + id + "\",\"ui_label\":\"" + label
            + "\",\"completion_threshold_ms\":30000,\"audio\":" + audio + "}";
    }

    private static String projectedCondition(String audio) {
        return "{\"completion_threshold_ms\":30000,\"audio\":" + audio + "}";
    }

    private static String audio(String destination, String hash, long bytes) {
        return "{\"logical_destination\":\"" + destination + "\","
            + "\"source_sha256\":\"" + hash + "\",\"source_bytes\":" + bytes
            + ",\"media_type\":\"audio/mpeg\"}";
    }

    private static String entries(byte[] profile, String replacementAId, boolean duplicateB) {
        String idA = replacementAId.isEmpty() ? "condition-audio-a" : replacementAId;
        String idB = duplicateB ? idA : "condition-audio-b";
        return audioEntry(idA, "session-audio/condition-a.mp3", AUDIO_A_SHA, 101L) + ","
            + audioEntry(idB, "session-audio/condition-b.mp3", AUDIO_B_SHA, 202L) + ","
            + profileEntry(profile, sha(profile));
    }

    private static String twoEntries(byte[] profile) {
        return audioEntry("condition-audio-a", "session-audio/condition-a.mp3",
            AUDIO_A_SHA, 101L) + "," + profileEntry(profile, sha(profile));
    }

    private static String fourEntries(byte[] profile) {
        return entries(profile, "", false) + ","
            + audioEntry("condition-audio-c", "session-audio/condition-c.mp3",
                repeat('c'), 303L);
    }

    private static String audioEntry(String id, String destination, String hash, long bytes) {
        return entry(id, destination, hash, bytes, "audio/mpeg");
    }

    private static String profileEntry(byte[] profile, String hash) {
        return entry("experiment-session-profile", "session-config/experiment-session.json",
            hash, profile.length, "application/json");
    }

    private static String entry(String id, String destination, String hash, long bytes,
            String mediaType) {
        return "{\"asset_id\":\"" + id + "\",\"staged_object\":\"private-assets/objects/"
            + hash + "\",\"logical_destination\":\"" + destination + "\","
            + "\"source_sha256\":\"" + hash + "\",\"source_bytes\":" + bytes
            + ",\"media_type\":\"" + mediaType + "\"}";
    }

    private static Fixture fixture(byte[] profile, String providerId, String entries)
            throws Exception {
        return fixture(profile, providerId, entries, sha(profile));
    }

    private static Fixture fixture(byte[] profile, String providerId, String entries,
            String profileAnchor) throws Exception {
        int count = entries.isEmpty() ? 0 : entries.split("\\},\\{").length;
        String lock = "{\"build_inputs\":{\"private_asset_closure\":{"
            + "\"schema\":\"rusty.quest.native_app_private_asset_closure.v1\","
            + "\"mode\":\"linked-provider\",\"provider_id\":\"" + providerId + "\","
            + "\"provider_manifest_sha256\":\"" + MANIFEST_SHA + "\","
            + "\"inventory_sha256\":\"" + INVENTORY_SHA + "\","
            + "\"asset_count\":" + count + ",\"assets\":[" + entries + "]}}}";
        return new Fixture(lock.getBytes(StandardCharsets.UTF_8), profile,
            new ExperimentSessionPackagedClosure.Anchors(
                profileAnchor, MANIFEST_SHA, INVENTORY_SHA));
    }

    private static void expectRejected(Fixture fixture, String label) throws Exception {
        try {
            boolean rejected = false;
            try { fixture.prepare(); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, label + " is rejected");
        } finally { fixture.close(); }
    }

    private static String sha(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static String repeat(char value) {
        char[] values = new char[64];
        java.util.Arrays.fill(values, value);
        return new String(values);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(path -> {
            try { Files.delete(path); } catch (Exception error) { throw new RuntimeException(error); }
        });
    }

    private static final class Fixture implements AutoCloseable {
        final byte[] lock;
        final byte[] profile;
        final ExperimentSessionPackagedClosure.Anchors anchors;
        final Path root;

        Fixture(byte[] lock, byte[] profile, ExperimentSessionPackagedClosure.Anchors anchors)
                throws Exception {
            this.lock = lock;
            this.profile = profile;
            this.anchors = anchors;
            this.root = Files.createTempDirectory("rq-profile-closure-");
        }

        ExperimentSessionPackagedClosure.Result prepare() throws Exception {
            return ExperimentSessionPackagedClosure.prepare(lock,
                destination -> profile.clone(), root, anchors);
        }

        Path target() {
            return root.resolve(ExperimentSessionPackagedClosure.MATERIALIZED_DIRECTORY)
                .resolve(ExperimentSessionPackagedClosure.MATERIALIZED_FILE);
        }

        @Override public void close() throws Exception { deleteTree(root); }
    }
}
