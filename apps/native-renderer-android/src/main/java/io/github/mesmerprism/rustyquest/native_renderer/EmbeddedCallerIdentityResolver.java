package io.github.mesmerprism.rustyquest.native_renderer;

import java.security.MessageDigest;
import java.util.Locale;

/** Pure fail-closed projection used by the embedded platform-authenticated path. */
final class EmbeddedCallerIdentityResolver {
    private EmbeddedCallerIdentityResolver() {}

    static Identity requireExact(
            int uid,
            String actualPackage,
            String expectedPackage,
            byte[][] signingCertificates) throws Exception {
        if (actualPackage == null
                || expectedPackage == null
                || !expectedPackage.equals(actualPackage)) {
            throw new SecurityException("embedded caller package does not match packaged client lock");
        }
        if (signingCertificates == null
                || signingCertificates.length != 1
                || signingCertificates[0] == null
                || signingCertificates[0].length == 0) {
            throw new SecurityException("exactly one embedded APK signing certificate is required");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signingCertificates[0]);
        StringBuilder fingerprint = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            fingerprint.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return new Identity(uid, actualPackage, fingerprint.toString());
    }

    static final class Identity {
        final int uid;
        final String packageName;
        final String signingCertificateSha256;

        Identity(int uid, String packageName, String signingCertificateSha256) {
            this.uid = uid;
            this.packageName = packageName;
            this.signingCertificateSha256 = signingCertificateSha256;
        }
    }
}
