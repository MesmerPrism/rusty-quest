package io.github.mesmerprism.rustyquest.packageupdater;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class UpdateManifestCanonicalizer {
    static final String ENVELOPE_SCHEMA =
            "rusty.quest.package_update_manifest_envelope.v1";
    static final String MANIFEST_SCHEMA =
            "rusty.quest.package_update_manifest.v1";
    private static final byte[] SIGNATURE_DOMAIN =
            (MANIFEST_SCHEMA + "\0").getBytes(StandardCharsets.US_ASCII);

    private UpdateManifestCanonicalizer() {
    }

    static byte[] signatureDomain() {
        return SIGNATURE_DOMAIN.clone();
    }

    static String canonicalSignedManifest(
            String manifestId,
            long sequence,
            long issuedAtMs,
            long expiresAtMs,
            String rolloutRing,
            UpdateArtifact artifact) {
        return "{\"artifact\":{\"apk_sha256\":" + quote(artifact.apkSha256)
                + ",\"apk_size_bytes\":" + artifact.apkSizeBytes
                + ",\"apk_url\":" + quote(artifact.apkUri.toASCIIString())
                + ",\"package_name\":" + quote(artifact.packageName)
                + ",\"signer_sha256\":" + quote(artifact.signerSha256)
                + ",\"version_code\":" + artifact.versionCode
                + ",\"version_name\":" + quote(artifact.versionName)
                + "},\"expires_at_ms\":" + expiresAtMs
                + ",\"issued_at_ms\":" + issuedAtMs
                + ",\"manifest_id\":" + quote(manifestId)
                + ",\"rollout_ring\":" + quote(rolloutRing)
                + ",\"schema\":" + quote(MANIFEST_SCHEMA)
                + ",\"sequence\":" + sequence
                + "}";
    }

    private static String quote(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                builder.append('\\').append(character);
            } else if (character == '\b') {
                builder.append("\\b");
            } else if (character == '\t') {
                builder.append("\\t");
            } else if (character == '\n') {
                builder.append("\\n");
            } else if (character == '\f') {
                builder.append("\\f");
            } else if (character == '\r') {
                builder.append("\\r");
            } else if (character < 0x20) {
                builder.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            } else {
                builder.append(character);
            }
        }
        return builder.append('"').toString();
    }
}
