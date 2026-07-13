package io.github.mesmerprism.rustyquest.broker_client;

import java.security.SecureRandom;
import java.util.Locale;

/** Per-launch unpredictable request namespace; one id may be reused only deliberately. */
final class BrokerRequestNamespace {
    private final String nonce;
    private long sequence;

    private BrokerRequestNamespace(String nonce) {
        this.nonce = nonce;
    }

    static BrokerRequestNamespace create(SecureRandom secureRandom) {
        byte[] entropy = new byte[16];
        secureRandom.nextBytes(entropy);
        return fromEntropy(entropy);
    }

    static BrokerRequestNamespace fromEntropy(byte[] entropy) {
        if (entropy == null || entropy.length != 16) {
            throw new IllegalArgumentException("request namespace needs 128 bits");
        }
        StringBuilder value = new StringBuilder(32);
        for (byte item : entropy) {
            value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return new BrokerRequestNamespace(value.toString());
    }

    synchronized String requestId(String clientId, String suffix) {
        String normalizedClient = clientId == null ? "" : clientId.replace('-', '_').trim();
        String normalizedSuffix = suffix == null ? "" : suffix.replace('-', '_').trim();
        if (normalizedClient.isEmpty() || normalizedSuffix.isEmpty()) {
            throw new IllegalArgumentException("client and suffix are required");
        }
        sequence += 1;
        return "request." + normalizedClient + ".launch_" + nonce
                + ".n" + sequence + "." + normalizedSuffix;
    }
}
