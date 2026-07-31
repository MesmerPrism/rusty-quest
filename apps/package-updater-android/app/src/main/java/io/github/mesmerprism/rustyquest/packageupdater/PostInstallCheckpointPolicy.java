package io.github.mesmerprism.rustyquest.packageupdater;

final class PostInstallCheckpointPolicy {
    private PostInstallCheckpointPolicy() {
    }

    static boolean mayAdvance(long observedAtMs, long manifestExpiresAtMs) {
        return observedAtMs >= 0L && observedAtMs < manifestExpiresAtMs;
    }
}
