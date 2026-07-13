package io.github.mesmerprism.rustyquest.native_renderer;

/** Host-Java damaged-case test for embedded package/signer authentication. */
public final class EmbeddedCallerIdentityResolverTest {
    public static void main(String[] args) throws Exception {
        byte[] certificate = new byte[] {1, 2, 3, 4};
        EmbeddedCallerIdentityResolver.Identity identity =
                EmbeddedCallerIdentityResolver.requireExact(
                        10123,
                        "io.github.mesmerprism.rustyquest.native_renderer",
                        "io.github.mesmerprism.rustyquest.native_renderer",
                        new byte[][] {certificate});
        require(identity.uid == 10123, "uid projection");
        require(identity.signingCertificateSha256.length() == 64, "fingerprint projection");
        rejects("wrong package", () -> EmbeddedCallerIdentityResolver.requireExact(
                10123, "package.attacker", "package.expected", new byte[][] {certificate}));
        rejects("ambiguous signer", () -> EmbeddedCallerIdentityResolver.requireExact(
                10123, "package.expected", "package.expected",
                new byte[][] {certificate, new byte[] {5}}));
        rejects("missing signer", () -> EmbeddedCallerIdentityResolver.requireExact(
                10123, "package.expected", "package.expected", new byte[0][]));
    }

    private static void rejects(String label, ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            throw new AssertionError(label + " accepted");
        } catch (SecurityException expected) {
            // expected
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
