package io.github.mesmerprism.rustyquest.packageupdater;

import android.util.Base64;

import org.json.JSONObject;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

final class StrictUpdateEnvelopeVerifier implements UpdateEnvelopeVerifier {
    private static final String ENVELOPE_SCHEMA =
            UpdateManifestCanonicalizer.ENVELOPE_SCHEMA;
    private static final String MANIFEST_SCHEMA =
            UpdateManifestCanonicalizer.MANIFEST_SCHEMA;
    private static final String SIGNATURE_ALGORITHM = "Ed25519";
    private static final byte[] ED25519_X509_PREFIX = new byte[] {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };
    private static final int MAX_ENVELOPE_BYTES = 256 * 1024;
    private static final long MAX_JCS_SAFE_INTEGER = 9_007_199_254_740_991L;

    private final String trustedKeyId;
    private final String trustedPublicKeyBase64Url;
    private final String expectedHttpsOrigin;
    private final String expectedPackageName;
    private final String expectedRolloutRing;
    private final String expectedSignerSha256;
    private final long installedVersionCode;
    private final long minimumTargetVersionCode;
    private final long maximumTargetVersionCode;
    private final long maximumApkSizeBytes;
    private final long maximumManifestValidityMs;
    private final long maximumFutureIssueSkewMs;
    private final UpdateStateStore stateStore;

    StrictUpdateEnvelopeVerifier(
            String trustedKeyId,
            String trustedPublicKeyBase64Url,
            String expectedHttpsOrigin,
            String expectedPackageName,
            String expectedRolloutRing,
            String expectedSignerSha256,
            long installedVersionCode,
            long minimumTargetVersionCode,
            long maximumTargetVersionCode,
            long maximumApkSizeBytes,
            long maximumManifestValidityMs,
            long maximumFutureIssueSkewMs,
            UpdateStateStore stateStore) {
        this.trustedKeyId = trustedKeyId;
        this.trustedPublicKeyBase64Url = trustedPublicKeyBase64Url;
        this.expectedHttpsOrigin = expectedHttpsOrigin;
        this.expectedPackageName = expectedPackageName;
        this.expectedRolloutRing = expectedRolloutRing;
        this.expectedSignerSha256 = expectedSignerSha256;
        this.installedVersionCode = installedVersionCode;
        this.minimumTargetVersionCode = minimumTargetVersionCode;
        this.maximumTargetVersionCode = maximumTargetVersionCode;
        this.maximumApkSizeBytes = maximumApkSizeBytes;
        this.maximumManifestValidityMs = maximumManifestValidityMs;
        this.maximumFutureIssueSkewMs = maximumFutureIssueSkewMs;
        this.stateStore = stateStore;
    }

    @Override
    public VerifiedUpdatePlan verify(byte[] envelopeBytes, long nowMs)
            throws VerificationException {
        if (envelopeBytes == null
                || envelopeBytes.length == 0
                || envelopeBytes.length > MAX_ENVELOPE_BYTES) {
            throw new VerificationException("invalid_envelope_size");
        }
        requireConfiguredPolicy();
        if (nowMs < 0L || nowMs > MAX_JCS_SAFE_INTEGER) {
            throw new VerificationException("observation_time_out_of_range");
        }

        try {
            String envelopeText = decodeUtf8Strict(envelopeBytes);
            StrictJsonPreflight.requireNoDuplicateObjectKeys(envelopeText);
            JSONObject envelope = new JSONObject(envelopeText);
            requireExactKeys(
                    envelope,
                    Set.of("schema", "key_id", "algorithm", "signature", "signed"),
                    "envelope");
            requireEquals(ENVELOPE_SCHEMA, envelope.getString("schema"), "envelope_schema");
            requireEquals(SIGNATURE_ALGORITHM, envelope.getString("algorithm"), "algorithm");
            requireEquals(trustedKeyId, envelope.getString("key_id"), "key_id");
            requireToken(trustedKeyId, 1, 96, "key_id");

            JSONObject signed = envelope.getJSONObject("signed");
            requireExactKeys(
                    signed,
                    Set.of(
                            "schema",
                            "manifest_id",
                            "sequence",
                            "issued_at_ms",
                            "expires_at_ms",
                            "rollout_ring",
                            "artifact"),
                    "signed_manifest");
            requireEquals(MANIFEST_SCHEMA, signed.getString("schema"), "manifest_schema");
            String manifestId = signed.getString("manifest_id");
            String rolloutRing = signed.getString("rollout_ring");
            requireToken(manifestId, 1, 128, "manifest_id");
            requireToken(rolloutRing, 1, 32, "rollout_ring");
            requireEquals(expectedRolloutRing, rolloutRing, "rollout_ring");

            long sequence = getSafeInteger(signed, "sequence", false);
            long issuedAtMs = getSafeInteger(signed, "issued_at_ms", true);
            long expiresAtMs = getSafeInteger(signed, "expires_at_ms", false);
            long latestIssueMs = nowMs > MAX_JCS_SAFE_INTEGER - maximumFutureIssueSkewMs
                    ? MAX_JCS_SAFE_INTEGER
                    : nowMs + maximumFutureIssueSkewMs;
            if (issuedAtMs > latestIssueMs) {
                throw new VerificationException("manifest_from_future");
            }
            if (expiresAtMs <= nowMs) {
                throw new VerificationException("manifest_expired");
            }
            if (expiresAtMs <= issuedAtMs
                    || expiresAtMs - issuedAtMs > maximumManifestValidityMs) {
                throw new VerificationException("invalid_validity_window");
            }

            JSONObject artifactJson = signed.getJSONObject("artifact");
            requireExactKeys(
                    artifactJson,
                    Set.of(
                            "package_name",
                            "version_code",
                            "version_name",
                            "apk_url",
                            "apk_sha256",
                            "apk_size_bytes",
                            "signer_sha256"),
                    "artifact");
            String packageName = artifactJson.getString("package_name");
            String versionName = artifactJson.getString("version_name");
            requirePackageName(packageName);
            requireToken(versionName, 1, 64, "version_name");
            requireEquals(expectedPackageName, packageName, "package");
            long versionCode = getSafeInteger(artifactJson, "version_code", false);
            long apkSizeBytes = getSafeInteger(artifactJson, "apk_size_bytes", false);
            if (versionCode <= installedVersionCode
                    || versionCode < minimumTargetVersionCode
                    || versionCode > maximumTargetVersionCode) {
                throw new VerificationException("version_policy_rejected");
            }
            if (apkSizeBytes > maximumApkSizeBytes) {
                throw new VerificationException("apk_size_policy_rejected");
            }
            String apkSha256 =
                    requireSha256Identity(artifactJson.getString("apk_sha256"), "apk_sha256");
            String signerSha256 = requireSha256Identity(
                    artifactJson.getString("signer_sha256"), "signer_sha256");
            requireEquals(expectedSignerSha256, signerSha256, "signer");
            URI apkUri = requirePolicyHttpsUri(artifactJson.getString("apk_url"));

            UpdateArtifact artifact = new UpdateArtifact(
                    packageName,
                    versionCode,
                    versionName,
                    apkUri,
                    apkSizeBytes,
                    apkSha256,
                    signerSha256);
            byte[] canonicalSigned = UpdateManifestCanonicalizer.canonicalSignedManifest(
                    manifestId,
                    sequence,
                    issuedAtMs,
                    expiresAtMs,
                    rolloutRing,
                    artifact).getBytes(StandardCharsets.UTF_8);
            byte[] signatureBytes = decodeCanonicalBase64Url(
                    envelope.getString("signature"), 64, "signature");
            if (signatureBytes.length != 64) {
                throw new VerificationException("invalid_signature_encoding");
            }
            verifySignature(canonicalSigned, signatureBytes);

            String signedManifestSha256 = sha256Identity(canonicalSigned);
            stateStore.requireAdvances(
                    packageName,
                    rolloutRing,
                    sequence,
                    versionCode);
            return new VerifiedUpdatePlan(
                    manifestId,
                    sequence,
                    issuedAtMs,
                    expiresAtMs,
                    rolloutRing,
                    sha256Identity(envelopeBytes),
                    signedManifestSha256,
                    artifact);
        } catch (VerificationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new VerificationException("malformed_envelope", exception);
        }
    }

    private void requireConfiguredPolicy() throws VerificationException {
        if (trustedKeyId == null
                || trustedKeyId.isBlank()
                || "unconfigured".equals(trustedKeyId)
                || trustedPublicKeyBase64Url == null
                || trustedPublicKeyBase64Url.isBlank()) {
            throw new VerificationException("trusted_key_not_configured");
        }
        if (expectedSignerSha256 == null || "unconfigured".equals(expectedSignerSha256)) {
            throw new VerificationException("trusted_signer_not_configured");
        }
        requireSha256Identity(expectedSignerSha256, "expected_signer_sha256");
        if (expectedPackageName == null
                || expectedRolloutRing == null
                || installedVersionCode < 0L
                || installedVersionCode > MAX_JCS_SAFE_INTEGER
                || minimumTargetVersionCode <= 0L
                || minimumTargetVersionCode > maximumTargetVersionCode
                || maximumTargetVersionCode > MAX_JCS_SAFE_INTEGER
                || maximumApkSizeBytes <= 0L
                || maximumApkSizeBytes > MAX_JCS_SAFE_INTEGER
                || maximumManifestValidityMs <= 0L
                || maximumManifestValidityMs > MAX_JCS_SAFE_INTEGER
                || maximumFutureIssueSkewMs < 0L
                || maximumFutureIssueSkewMs > MAX_JCS_SAFE_INTEGER) {
            throw new VerificationException("update_policy_not_configured");
        }
        requirePackageName(expectedPackageName);
        requireToken(expectedRolloutRing, 1, 32, "expected_rollout_ring");
        requireCanonicalOrigin(expectedHttpsOrigin);
    }

    private void verifySignature(byte[] canonicalSigned, byte[] signatureBytes)
            throws VerificationException {
        try {
            byte[] rawKey = decodeCanonicalBase64Url(
                    trustedPublicKeyBase64Url, 32, "trusted_public_key");
            if (rawKey.length != 32) {
                throw new VerificationException("invalid_release_key");
            }
            byte[] x509Key = new byte[ED25519_X509_PREFIX.length + rawKey.length];
            System.arraycopy(
                    ED25519_X509_PREFIX, 0, x509Key, 0, ED25519_X509_PREFIX.length);
            System.arraycopy(
                    rawKey, 0, x509Key, ED25519_X509_PREFIX.length, rawKey.length);
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(
                    KeyFactory.getInstance(SIGNATURE_ALGORITHM)
                            .generatePublic(new X509EncodedKeySpec(x509Key)));
            verifier.update(UpdateManifestCanonicalizer.signatureDomain());
            verifier.update(canonicalSigned);
            if (!verifier.verify(signatureBytes)) {
                throw new VerificationException("signature_verification_failed");
            }
        } catch (VerificationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new VerificationException("signature_verifier_failed_closed", exception);
        }
    }

    private URI requirePolicyHttpsUri(String value) throws VerificationException {
        try {
            URI uri = URI.create(value);
            if (!value.isEmpty()
                    && value.equals(uri.toASCIIString())
                    && "https".equals(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getHost().equals(uri.getHost().toLowerCase(Locale.ROOT))
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    && uri.getRawPath() != null
                    && !uri.getRawPath().equals("/")
                    && !uri.getRawPath().startsWith("//")
                    && !uri.getRawPath().contains("/../")
                    && !uri.getRawPath().endsWith("/..")
                    && canonicalOrigin(uri).equals(expectedHttpsOrigin)) {
                return uri;
            }
            throw new VerificationException("origin_mismatch");
        } catch (IllegalArgumentException exception) {
            throw new VerificationException("invalid_apk_url", exception);
        }
    }

    private static void requireCanonicalOrigin(String value) throws VerificationException {
        try {
            URI uri = URI.create(value);
            if (!value.equals(canonicalOrigin(uri))
                    || !"https".equals(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getPath() != null && !uri.getPath().isEmpty()
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new VerificationException("invalid_expected_https_origin");
            }
        } catch (IllegalArgumentException exception) {
            throw new VerificationException("invalid_expected_https_origin", exception);
        }
    }

    private static String canonicalOrigin(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return "";
        }
        int port = uri.getPort();
        if (port == 443) {
            return "";
        }
        return "https://" + host + (port == -1 ? "" : ":" + port);
    }

    private static long getSafeInteger(JSONObject object, String key, boolean allowZero)
            throws VerificationException {
        try {
            Object value = object.get(key);
            if (!(value instanceof Integer) && !(value instanceof Long)) {
                throw new VerificationException("non_integer_" + key);
            }
            long parsed = ((Number) value).longValue();
            if (parsed < (allowZero ? 0L : 1L) || parsed > MAX_JCS_SAFE_INTEGER) {
                throw new VerificationException("invalid_" + key);
            }
            return parsed;
        } catch (VerificationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new VerificationException("invalid_" + key, exception);
        }
    }

    private static byte[] decodeCanonicalBase64Url(String value, int maxBytes, String label)
            throws VerificationException {
        try {
            if (value == null
                    || value.isEmpty()
                    || value.contains("=")
                    || !value.matches("[A-Za-z0-9_-]+")
                    || value.length() % 4 == 1) {
                throw new VerificationException("noncanonical_base64url_" + label);
            }
            byte[] decoded = Base64.decode(
                    value, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
            String encoded = Base64.encodeToString(
                    decoded, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
            if (decoded.length > maxBytes || !encoded.equals(value)) {
                throw new VerificationException("noncanonical_base64url_" + label);
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new VerificationException("noncanonical_base64url_" + label, exception);
        }
    }

    private static String decodeUtf8Strict(byte[] bytes) throws VerificationException {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new VerificationException("invalid_utf8", exception);
        }
    }

    private static void requireExactKeys(
            JSONObject object, Set<String> expected, String label)
            throws VerificationException {
        Set<String> actual = new TreeSet<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            actual.add(keys.next());
        }
        if (!actual.equals(new TreeSet<>(expected))) {
            throw new VerificationException(label + "_key_closure_changed");
        }
    }

    private static void requireEquals(String expected, String actual, String label)
            throws VerificationException {
        if (expected == null || !expected.equals(actual)) {
            throw new VerificationException(label + "_mismatch");
        }
    }

    private static void requireToken(String value, int minimum, int maximum, String label)
            throws VerificationException {
        if (value == null
                || value.length() < minimum
                || value.length() > maximum
                || !value.matches("[A-Za-z0-9._-]+")) {
            throw new VerificationException("invalid_" + label);
        }
    }

    private static void requirePackageName(String value) throws VerificationException {
        if (value == null
                || value.length() < 3
                || value.length() > 255
                || !value.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) {
            throw new VerificationException("invalid_package_name");
        }
    }

    private static String requireSha256Identity(String value, String label)
            throws VerificationException {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new VerificationException("invalid_" + label);
        }
        return value;
    }

    static String sha256Identity(byte[] bytes) throws VerificationException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder("sha256:");
            for (byte value : digest) {
                builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new VerificationException("sha256_unavailable", exception);
        }
    }
}
