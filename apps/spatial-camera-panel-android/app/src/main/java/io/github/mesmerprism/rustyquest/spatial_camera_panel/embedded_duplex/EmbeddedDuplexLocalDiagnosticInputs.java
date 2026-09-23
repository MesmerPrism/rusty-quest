package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONArray;
import org.json.JSONObject;
import java.security.SecureRandom;

/** Fresh, effect-free local fixture inputs. These values do not grant peer authority. */
final class EmbeddedDuplexLocalDiagnosticInputs {
    final EmbeddedDuplexPackagedInputs.InstalledRole role;
    final JSONObject runtimeBindings;
    final JSONObject startup;

    private EmbeddedDuplexLocalDiagnosticInputs(EmbeddedDuplexPackagedInputs.InstalledRole role,
            JSONObject runtimeBindings, JSONObject startup) {
        this.role = role;
        this.runtimeBindings = runtimeBindings;
        this.startup = startup;
    }

    static EmbeddedDuplexLocalDiagnosticInputs create(EmbeddedDuplexEnrollment enrollment)
            throws Exception {
        if (enrollment == null || !enrollment.localFixture) {
            throw new IllegalStateException("local diagnostic requires labeled fixture enrollment");
        }
        SecureRandom random = new SecureRandom();
        byte[] entropy = new byte[32], nonce = new byte[12];
        random.nextBytes(entropy);
        random.nextBytes(nonce);
        long generation = random.nextLong() & Long.MAX_VALUE;
        if (generation == 0L) generation = 1L;
        long wallMs = System.currentTimeMillis();
        long monotonicNs = System.nanoTime();
        if (wallMs <= 0L || monotonicNs <= 0L || wallMs > Long.MAX_VALUE - 300_000L) {
            throw new IllegalStateException("fresh diagnostic clock unavailable");
        }
        String grantId = "grant.localdiagnostic." + hex(nonce);
        JSONObject embedded = new JSONObject()
                .put("$schema", "rusty.quest.embedded_duplex.authority_config.v1")
                .put("runtime_host_id", enrollment.runtimeHostId)
                .put("trusted_operator_ids", new JSONArray().put(enrollment.trustedOperatorId))
                .put("trusted_key_fingerprints", new JSONArray().put(enrollment.remoteKeyId))
                .put("trusted_adapter_ids", new JSONArray().put(enrollment.adapterId))
                .put("trusted_media_revoker_ids", new JSONArray().put(enrollment.mediaRevokerId));
        JSONObject runtime = new JSONObject()
                .put("adapter_id", enrollment.adapterId)
                .put("admission_authority_id", enrollment.admissionAuthorityId)
                .put("grant_id", grantId)
                .put("grant_expires_at_ms", wallMs + 300_000L)
                .put("lease_expires_at_ms", wallMs + 300_000L)
                .put("max_token_ttl_ms", Math.min(enrollment.maxTokenTtlMs, 60_000L))
                .put("embedded_duplex", embedded)
                .put("validation_epoch_entropy_hex", hex(entropy))
                .put("validation_wall_unix_ms", wallMs)
                .put("validation_monotonic_elapsed_ns", monotonicNs);
        return new EmbeddedDuplexLocalDiagnosticInputs(
                EmbeddedDuplexPackagedInputs.InstalledRole.parse(enrollment.installed.roleId),
                runtime, enrollment.startupJson(generation, grantId));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            result.append(Character.forDigit((item >>> 4) & 15, 16));
            result.append(Character.forDigit(item & 15, 16));
        }
        return result.toString();
    }
}
