package io.github.mesmerprism.rustymanifold.broker;

import java.util.Locale;
import java.util.Objects;

/** Android-derived identity evidence. Provider payloads never set these fields. */
public final class HubProviderIdentity {
    private final int uid;
    private final String packageName;
    private final String signerSha256;

    public HubProviderIdentity(int uid, String packageName, String signerSha256) {
        if (uid < 0) {
            throw new SecurityException("provider UID must be non-negative");
        }
        this.packageName = requirePackage(packageName);
        this.signerSha256 = requireSha256(signerSha256);
        this.uid = uid;
    }

    public int uid() { return uid; }
    public String packageName() { return packageName; }
    public String signerSha256() { return signerSha256; }

    public String stableKey() {
        return uid + ":" + packageName + ":" + signerSha256;
    }

    private static String requirePackage(String value) {
        String candidate = Objects.requireNonNull(value, "packageName").trim();
        if (candidate.length() < 3 || candidate.length() > 192
                || !candidate.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new SecurityException("invalid provider package");
        }
        return candidate;
    }

    private static String requireSha256(String value) {
        String candidate = Objects.requireNonNull(value, "signerSha256")
                .trim().toLowerCase(Locale.ROOT);
        if (!candidate.matches("[0-9a-f]{64}")) {
            throw new SecurityException("invalid provider signer SHA-256");
        }
        return candidate;
    }
}
