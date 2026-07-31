package io.github.mesmerprism.rustyquest.packageupdater;

public final class PostInstallCheckpointPolicyTest {
    public static void main(String[] arguments) {
        long expiry = 2_000_000_500_000L;
        if (!PostInstallCheckpointPolicy.mayAdvance(expiry - 1L, expiry)) {
            throw new AssertionError("pre-expiry installed readback must remain committable");
        }
        if (PostInstallCheckpointPolicy.mayAdvance(expiry, expiry)) {
            throw new AssertionError("expiry boundary must reject checkpoint advancement");
        }
        if (PostInstallCheckpointPolicy.mayAdvance(expiry + 1L, expiry)) {
            throw new AssertionError("post-expiry success callback must reject checkpoint");
        }
        System.out.println("Post-install checkpoint expiry policy passed.");
    }
}
