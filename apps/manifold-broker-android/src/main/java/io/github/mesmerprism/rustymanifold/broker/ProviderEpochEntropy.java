package io.github.mesmerprism.rustymanifold.broker;

import java.security.SecureRandom;
import java.util.Locale;

/** Process-local entropy transport; Rust derives and owns the provider epoch id. */
final class ProviderEpochEntropy {
    private static final String HEX = generate();

    private ProviderEpochEntropy() {
    }

    static String hex() {
        return HEX;
    }

    private static String generate() {
        byte[] entropy = new byte[32];
        new SecureRandom().nextBytes(entropy);
        StringBuilder builder = new StringBuilder(64);
        for (byte value : entropy) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
    }
}
