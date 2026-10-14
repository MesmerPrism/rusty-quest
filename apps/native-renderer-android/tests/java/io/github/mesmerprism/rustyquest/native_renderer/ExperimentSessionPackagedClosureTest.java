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
        ExperimentSessionPackagedClosure.requireOwnerProfilePolicy(
            "rusty.viscereality.experiment_session_profile.standalone.v2", Boolean.FALSE, true);
        for (Object kiosk : new Object[]{null, Boolean.TRUE, "false", Integer.valueOf(0)}) {
            boolean denied=false;
            try { ExperimentSessionPackagedClosure.requireOwnerProfilePolicy(
                "rusty.viscereality.experiment_session_profile.standalone.v2", kiosk, true); }
            catch (IllegalArgumentException expected) { denied=true; }
            if (!denied) throw new AssertionError("standalone rejects ambiguous or requested kiosk");
        }
        for (boolean standalone : new boolean[]{false,true}) {
            boolean denied=false;
            try { ExperimentSessionPackagedClosure.requireOwnerProfilePolicy(standalone
                ? "rusty.viscereality.experiment_session_profile.v1"
                : "rusty.viscereality.experiment_session_profile.standalone.v2", Boolean.FALSE, standalone); }
            catch (IllegalArgumentException expected) { denied=true; }
            if (!denied) throw new AssertionError("profile schema cannot cross owner routes");
        }

        validClosureMaterializesExactBytesAndBuildsOnlyAudio();
        validatedOpenControlDefaults();
        validGuidanceClosureMaterializesAndBindsAudio();
        phaseAudioClosedOptInControls();
        inactiveClosureDoesNotMaterialize();
        rejectsWrongAssetCardinalityAndIds();
        rejectsProfileHashBytesBomAndUtf8Damage();
        rejectsOuterSchemaAliasAndDamage();
        rejectsOuterNestedClosureAndProviderDrift();
        rejectsExistingFileTamper();
        retainedUpgradePreservesLegacyAndSessionBytes();
        partialClosureAndPathControls();
        concurrentExactMaterialization();
        linkedSelectedDirectoryIsDenied();
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
            check(java.util.Arrays.equals(profile, Files.readAllBytes(
                ExperimentSessionPackagedClosure.materializedDirectory(root, sha(profile)).resolve(
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
            check(!result.openControlDefault, "older absent policy remains gated");
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

    private static void validatedOpenControlDefaults() throws Exception {
        String baseline = profile("provider.one", audio("session-audio/condition-a.mp3",
            AUDIO_A_SHA, 101L), audio("session-audio/condition-b.mp3", AUDIO_B_SHA, 202L), false);
        for (String policy : new String[] {"true", "false", "null", "\"true\"", "1"}) {
            byte[] bytes = baseline.replace("\"recording_default\":true,",
                "\"open_control_default\":" + policy + ",\"recording_default\":true,")
                .getBytes(StandardCharsets.UTF_8);
            Fixture fixture = fixture(bytes, "provider.one", entries(bytes, "", false));
            try {
                if ("true".equals(policy) || "false".equals(policy)) {
                    check(fixture.prepare().openControlDefault == "true".equals(policy),
                        "validated profile policy is projected exactly");
                } else {
                    expectRejected(fixture, "nonboolean default policy");
                }
            } finally { fixture.close(); }
        }
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

    private static void validGuidanceClosureMaterializesAndBindsAudio() throws Exception {
        byte[] guidance = ("{\"schema\":\"rusty.quest.breath_guidance_timeline.v1\","
            + "\"timebase\":\"official-active-time-ms\",\"audio_source_sha256\":\""
            + AUDIO_A_SHA + "\",\"segments\":["
            + "{\"start_ms\":0,\"end_ms\":4000,\"phase\":\"inhale\"},"
            + "{\"start_ms\":4000,\"end_ms\":8000,\"phase\":\"exhale\"}]}")
            .getBytes(StandardCharsets.UTF_8);
        String guidanceIdentity = "{\"asset\":{"
            + "\"logical_destination\":\"breath-guidance/condition-a.json\","
            + "\"source_sha256\":\"" + sha(guidance) + "\","
            + "\"source_bytes\":" + guidance.length + ","
            + "\"media_type\":\"application/json\"},\"default_bias_percent\":35}";
        String audioA = audio("session-audio/condition-a.mp3", AUDIO_A_SHA, 101L);
        String audioB = audio("session-audio/condition-b.mp3", AUDIO_B_SHA, 202L);
        String base = profile("provider.one", audioA, audioB, false);
        String outer = outerCondition("condition-a", "Condition 1", audioA);
        String projected = projectedCondition(audioA);
        byte[] profile = base
            .replace(outer, outer.substring(0, outer.length() - 1)
                + ",\"breath_guidance\":" + guidanceIdentity + "}")
            .replace("\"condition-a\":" + projected,
                "\"condition-a\":" + projected.substring(0, projected.length() - 1)
                    + ",\"breath_guidance\":" + guidanceIdentity + "}")
            .getBytes(StandardCharsets.UTF_8);
        String encodedEntries = entries(profile, "", false) + ","
            + entry("breath-guidance-a", "breath-guidance/condition-a.json",
                sha(guidance), guidance.length, "application/json");
        Fixture fixture = fixture(profile, "provider.one", encodedEntries);
        try {
            ExperimentSessionPackagedClosure.Result result =
                ExperimentSessionPackagedClosure.prepare(fixture.lock, destination -> {
                    if (ExperimentSessionPackagedClosure.PROFILE_DESTINATION.equals(destination)) {
                        return profile.clone();
                    }
                    if ("breath-guidance/condition-a.json".equals(destination)) {
                        return guidance.clone();
                    }
                    return new byte[0];
                }, fixture.root, fixture.anchors);
            check(result.guidanceEntries.length == 1
                    && result.guidanceEntries[0].defaultBiasPercent == 35,
                "guided condition identity and default bias are exposed");
            check(java.util.Arrays.equals(guidance, Files.readAllBytes(
                ExperimentSessionPackagedClosure.materializedDirectory(fixture.root, sha(profile))
                .resolve("breath-guidance").resolve("condition-a.json"))),
                "exact breathing pattern bytes are materialized");
        } finally { fixture.close(); }
    }


    private static void phaseAudioClosedOptInControls() throws Exception {
        for (int mode=0;mode<8;mode++) {
            java.util.Map<String,byte[]> assetBytes = new java.util.HashMap<String,byte[]>();
            String a=audio("session-audio/condition-a.mp3",AUDIO_A_SHA,101);
            String b=audio("session-audio/condition-b.mp3",AUDIO_B_SHA,202);
            String text=profile("provider.one",a,b,false);
            String extra="";
            for(String condition:new String[]{"condition-a","condition-b"}) {
                String hash="condition-a".equals(condition)?AUDIO_A_SHA:AUDIO_B_SHA;
                byte[] raw=("{\"schema\":\"rusty.quest.breath_guidance_timeline.v1\",\"timebase\":\"official-active-time-ms\",\"audio_source_sha256\":\""+hash+"\",\"segments\":[{\"start_ms\":0,\"end_ms\":4000,\"phase\":\"inhale\"}]}").getBytes(StandardCharsets.UTF_8);
                String dest="breath-guidance/"+condition+".json";assetBytes.put(dest,raw);
                String identity="{\"asset\":{\"logical_destination\":\""+dest+"\",\"source_sha256\":\""+sha(raw)+"\",\"source_bytes\":"+raw.length+",\"media_type\":\"application/json\"},\"default_bias_percent\":100}";
                String audio="condition-a".equals(condition)?a:b;
                String outer=outerCondition(condition,"condition-a".equals(condition)?"Condition 1":"Condition 2",audio);
                String projected=projectedCondition(audio);
                text=text.replace(outer,outer.substring(0,outer.length()-1)+",\"breath_guidance\":"+identity+"}")
                    .replace("\""+condition+"\":"+projected,"\""+condition+"\":"+projected.substring(0,projected.length()-1)+",\"breath_guidance\":"+identity+"}");
                extra+=","+entry("breath-guidance-"+("condition-a".equals(condition)?"a":"b"),dest,sha(raw),raw.length,"application/json");
            }
            String config="{\"enabled\":"+(mode==1?"false":mode==2?"1":"true");
            for(String role:new String[]{"inhale","retention","exhale"}) {
                byte[] raw=("synthetic-mp3-"+role).getBytes(StandardCharsets.UTF_8);
                String dest="phase-audio/"+role+".mp3";
                config+=",\""+role+"\":"+audio(mode==4&&role.equals("inhale")?"phase-audio/retention.mp3":dest,sha(raw),raw.length);
                assetBytes.put(dest,mode==6?new byte[]{1}:raw);
                if(!(mode==5&&role.equals("exhale")))extra+=","+entry("phase-audio-"+role,dest,sha(raw),raw.length,"audio/mpeg");
            }
            config+=mode==3?",\"unknown\":true}":"}";
            if(mode!=7)text=text.substring(0,text.length()-1)+",\"phase_audio\":"+config+"}";
            final byte[] profile=text.getBytes(StandardCharsets.UTF_8);
            Fixture fixture=fixture(profile,"provider.one",entries(profile,"",false)+extra);
            assetBytes.put(ExperimentSessionPackagedClosure.PROFILE_DESTINATION,profile);
            try {
                try {
                    ExperimentSessionPackagedClosure.Result result=ExperimentSessionPackagedClosure.prepare(fixture.lock,destination->assetBytes.get(destination),fixture.root,fixture.anchors);
                    check(mode<=1,"hostile optional audio must deny mode="+mode);
                    check(result.phaseAudioEntries.length==(mode==0?3:0),"exact opt-in controls looping/cues");
                }catch(IllegalArgumentException error){if(mode<=1)throw error;check(mode>=2,"positive closure rejected mode="+mode);}
            }finally{fixture.close();}
        }
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

    private static void retainedUpgradePreservesLegacyAndSessionBytes() throws Exception {
        byte[] profile = profile("provider.one", audio("session-audio/condition-a.mp3",
            AUDIO_A_SHA, 101L), audio("session-audio/condition-b.mp3", AUDIO_B_SHA, 202L), false)
            .getBytes(StandardCharsets.UTF_8);
        Fixture fixture = fixture(profile, "provider.one", entries(profile, "", false));
        try {
            Path legacy=fixture.root.resolve(ExperimentSessionPackagedClosure.MATERIALIZED_DIRECTORY);
            Files.createDirectories(legacy.resolve("breath-guidance"));
            Files.write(legacy.resolve(ExperimentSessionPackagedClosure.MATERIALIZED_FILE), "retained old profile".getBytes(StandardCharsets.UTF_8));
            Files.write(legacy.resolve("breath-guidance/condition-a.json"), "retained old guidance".getBytes(StandardCharsets.UTF_8));
            Path session=legacy.resolve("20261011T120000000Z");Files.createDirectory(session);
            Files.write(session.resolve("events.jsonl"), "retained journal".getBytes(StandardCharsets.UTF_8));
            fixture.prepare();
            check(java.util.Arrays.equals(profile,Files.readAllBytes(fixture.target())),"selected new profile exact");
            check("retained old profile".equals(new String(Files.readAllBytes(legacy.resolve(ExperimentSessionPackagedClosure.MATERIALIZED_FILE)),StandardCharsets.UTF_8)),"old fixed profile untouched");
            check("retained old guidance".equals(new String(Files.readAllBytes(legacy.resolve("breath-guidance/condition-a.json")),StandardCharsets.UTF_8)),"old fixed guidance untouched");
            check("retained journal".equals(new String(Files.readAllBytes(session.resolve("events.jsonl")),StandardCharsets.UTF_8)),"session history untouched");
        } finally { fixture.close(); }
    }

    private static void partialClosureAndPathControls() throws Exception {
        byte[] profile=profile("provider.one", audio("session-audio/condition-a.mp3",AUDIO_A_SHA,101L),audio("session-audio/condition-b.mp3",AUDIO_B_SHA,202L),false).getBytes(StandardCharsets.UTF_8);
        Fixture fixture=fixture(profile,"provider.one",entries(profile,"",false));
        try {
            Files.createDirectories(fixture.target().getParent());Files.write(fixture.target(),new byte[]{1,2});
            boolean rejected=false;try {fixture.prepare();}catch(IllegalArgumentException e){rejected=true;}
            check(rejected && java.util.Arrays.equals(new byte[]{1,2},Files.readAllBytes(fixture.target())),"partial selected profile denied without overwrite");
            Files.delete(fixture.target());Files.createDirectory(fixture.target());
            rejected=false;try {fixture.prepare();}catch(IllegalArgumentException e){rejected=true;}
            check(rejected && Files.isDirectory(fixture.target()),"non-file selected target denied");
            for(String digest:new String[]{"../escape",repeat('A'),repeat('g')}) {
                rejected=false;try {ExperimentSessionPackagedClosure.materializedDirectory(fixture.root,digest);}catch(IllegalArgumentException e){rejected=true;}
                check(rejected,"invalid closure key denied");
            }
            rejected=false;try {ExperimentSessionPackagedClosure.materializedDirectory(java.nio.file.Paths.get("relative"),repeat('a'));}catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"relative root denied");
            rejected=false;try {ExperimentSessionPackagedClosure.materializedDirectory(fixture.root.resolve("child/.."),repeat('a'));}catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"root parent traversal denied");
        } finally { fixture.close(); }
    }

    private static void concurrentExactMaterialization() throws Exception {
        byte[] profile=profile("provider.one",audio("session-audio/condition-a.mp3",AUDIO_A_SHA,101L),audio("session-audio/condition-b.mp3",AUDIO_B_SHA,202L),false).getBytes(StandardCharsets.UTF_8);
        Fixture fixture=fixture(profile,"provider.one",entries(profile,"",false));
        try {
            java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> action=()->{start.await();try {fixture.prepare();return true;}catch(IllegalArgumentException racedPartial){return false;}};
            try {
                java.util.concurrent.Future<Boolean> a=pool.submit(action),b=pool.submit(action);start.countDown();
                check(a.get()|b.get(),"one concurrent CreateNew publisher completes");
                check(java.util.Arrays.equals(profile,Files.readAllBytes(fixture.target())),"raced selected bytes exact");
                fixture.prepare(); // Only exact completed bytes are accepted; no raced partial success.
            } finally {pool.shutdownNow();}
        } finally {fixture.close();}
    }

    private static void linkedSelectedDirectoryIsDenied() throws Exception {
        byte[] profile=profile("provider.one",audio("session-audio/condition-a.mp3",AUDIO_A_SHA,101L),audio("session-audio/condition-b.mp3",AUDIO_B_SHA,202L),false).getBytes(StandardCharsets.UTF_8);
        Fixture fixture=fixture(profile,"provider.one",entries(profile,"",false));
        Path link=fixture.target().getParent();
        try {
            Path target=fixture.root.resolve("other-owner-directory");Files.createDirectory(target);
            Files.createDirectories(fixture.target().getParent().getParent());
            if(System.getProperty("os.name").startsWith("Windows")) {
                Process p=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),target.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
                check(p.waitFor()==0,"actual task-owned junction created");
            } else Files.createSymbolicLink(link,target);
            boolean rejected=false;try {fixture.prepare();}catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"linked selected directory denied before write");
            check(!Files.exists(target.resolve(ExperimentSessionPackagedClosure.MATERIALIZED_FILE)),"linked target unchanged");
        } finally {Files.deleteIfExists(link);fixture.close();}
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
            return ExperimentSessionPackagedClosure.materializedDirectory(root, anchors.profileSha256)
                .resolve(ExperimentSessionPackagedClosure.MATERIALIZED_FILE);
        }

        @Override public void close() throws Exception { deleteTree(root); }
    }
}
