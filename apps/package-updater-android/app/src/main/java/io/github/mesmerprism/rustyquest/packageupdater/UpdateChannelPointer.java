package io.github.mesmerprism.rustyquest.packageupdater;

import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

final class UpdateChannelPointer {
    private static final long MAX_JCS_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final String SCHEMA =
            "rusty.quest.package_update_channel_pointer.v2";
    private static final Set<String> KEYS = Set.of(
            "schema",
            "generation",
            "envelope_sha256",
            "sequence",
            "version_code",
            "channel",
            "package_name",
            "rollout_ring",
            "signer_sha256",
            "key_id",
            "public_key",
            "https_origin",
            "site_base_path");

    final URI envelopeUri;
    final String generation;
    final String envelopeSha256;
    final long sequence;
    final long versionCode;

    private UpdateChannelPointer(
            URI envelopeUri,
            String generation,
            String envelopeSha256,
            long sequence,
            long versionCode) {
        this.envelopeUri = envelopeUri;
        this.generation = generation;
        this.envelopeSha256 = envelopeSha256;
        this.sequence = sequence;
        this.versionCode = versionCode;
    }

    static UpdateChannelPointer verify(byte[] bytes) throws Exception {
        String text = new String(bytes, StandardCharsets.UTF_8);
        StrictJsonPreflight.requireNoDuplicateObjectKeys(text);
        JSONObject value = new JSONObject(text);
        TreeSet<String> actual = new TreeSet<>();
        value.keys().forEachRemaining(actual::add);
        if (!actual.equals(new TreeSet<>(KEYS))
                || !SCHEMA.equals(value.getString("schema"))
                || !BuildConfig.UPDATE_CHANNEL.equals(value.getString("channel"))
                || !BuildConfig.EXPECTED_PACKAGE_NAME.equals(value.getString("package_name"))
                || !BuildConfig.EXPECTED_ROLLOUT_RING.equals(value.getString("rollout_ring"))
                || !BuildConfig.EXPECTED_SIGNER_SHA256.equals(value.getString("signer_sha256"))
                || !BuildConfig.TRUSTED_KEY_ID.equals(value.getString("key_id"))
                || !BuildConfig.TRUSTED_PUBLIC_KEY_BASE64.equals(value.getString("public_key"))
                || !BuildConfig.EXPECTED_HTTPS_ORIGIN.equals(value.getString("https_origin"))) {
            throw new IllegalArgumentException("channel_pointer_tuple_mismatch");
        }
        if (!BuildConfig.EXPECTED_SITE_BASE_PATH.equals(
                value.getString("site_base_path"))) {
            throw new IllegalArgumentException("channel_pointer_tuple_mismatch");
        }
        String generation = value.getString("generation");
        String digest = value.getString("envelope_sha256");
        Object sequenceValue = value.get("sequence");
        Object versionCodeValue = value.get("version_code");
        if (!(sequenceValue instanceof Integer || sequenceValue instanceof Long)
                || !(versionCodeValue instanceof Integer || versionCodeValue instanceof Long)) {
            throw new IllegalArgumentException("channel_pointer_identity_invalid");
        }
        long sequence = ((Number) sequenceValue).longValue();
        long versionCode = ((Number) versionCodeValue).longValue();
        if (!generation.matches(
                        "s[1-9][0-9]{0,19}-v[1-9][0-9]{0,19}-[0-9a-f]{16}-[0-9a-f]{16}")
                || !digest.matches("sha256:[0-9a-f]{64}")
                || sequence <= 0L
                || sequence > MAX_JCS_SAFE_INTEGER
                || versionCode <= 0L
                || versionCode > MAX_JCS_SAFE_INTEGER
                || !generation.startsWith("s" + sequence + "-v" + versionCode + "-")
                || !generation.endsWith("-" + digest.substring(7, 23))) {
            throw new IllegalArgumentException("channel_pointer_identity_invalid");
        }
        URI envelopeUri = URI.create(
                BuildConfig.EXPECTED_HTTPS_ORIGIN
                        + "/"
                        + BuildConfig.EXPECTED_SITE_BASE_PATH
                        + "/package-updates/rusty-kiosk/labs/generations/"
                        + generation
                        + "/envelope.json");
        return new UpdateChannelPointer(
                UpdateManifestClient.requireFixedHttpsUri(envelopeUri),
                generation,
                digest,
                sequence,
                versionCode);
    }

    void verifyEnvelopeBytes(byte[] envelope) throws Exception {
        String actual = "sha256:"
                + HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(envelope));
        if (!envelopeSha256.equals(actual)) {
            throw new IllegalArgumentException("channel_pointer_envelope_hash_mismatch");
        }
    }

    void verifyPlan(VerifiedUpdatePlan plan) {
        String expectedGeneration = "s"
                + sequence
                + "-v"
                + versionCode
                + "-"
                + plan.artifact.apkSha256.substring(7, 23)
                + "-"
                + envelopeSha256.substring(7, 23);
        if (sequence != plan.sequence
                || versionCode != plan.artifact.versionCode
                || !generation.equals(expectedGeneration)) {
            throw new IllegalArgumentException("channel_pointer_plan_mismatch");
        }
    }
}
