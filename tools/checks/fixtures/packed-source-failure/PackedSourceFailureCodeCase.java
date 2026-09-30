package io.github.mesmerprism.rustyquest.media;

import java.io.IOException;

public final class PackedSourceFailureCodeCase {
    private static void eq(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }

    public static void main(String[] ignored) {
        RuntimeException freshness = new PackedSourceFailureCode.FreshnessExpired();
        eq(PackedSourceFailureCode.cause(freshness), "STATE");
        eq(PackedSourceFailureCode.detail(PackedSourceFailureCode.Operation.FRESHNESS_CHECK,
                freshness), "FRESHNESS_EXPIRED");
        eq(PackedSourceFailureCode.detail(PackedSourceFailureCode.Operation.ENCODER_DEQUEUE,
                new IllegalStateException("secret platform detail")), "ENCODER_STATE");
        eq(PackedSourceFailureCode.detail(PackedSourceFailureCode.Operation.ENCODER_GET_OUTPUT,
                new IllegalStateException("another platform detail")), "ENCODER_STATE");
        eq(PackedSourceFailureCode.detail(PackedSourceFailureCode.Operation.ENCODER_RELEASE,
                new IllegalStateException("unbounded platform detail")), "ENCODER_STATE");
        eq(PackedSourceFailureCode.detail(PackedSourceFailureCode.Operation.SLEEP,
                new IllegalStateException("unknown state")), "STATE_OTHER");
        RuntimeException codec = new RuntimeException("wrapper", new android.media.MediaCodec.CodecException("private codec detail"));
        eq(PackedSourceFailureCode.cause(codec), "CODEC");
        eq(PackedSourceFailureCode.detail(PackedSourceFailureCode.Operation.ENCODER_DEQUEUE,
                codec), "CODEC_ERROR");
        eq(PackedSourceFailureCode.cause(new IOException("network detail")), "IO");
        eq(PackedSourceFailureCode.ageMs(100, 90), 10L);
        eq(PackedSourceFailureCode.ageMs(100, -1), -1L);
        eq(PackedSourceFailureCode.ageMs(90, 100), -1L);
        eq(PackedSourceFailureCode.ageMs(Long.MAX_VALUE, 0), Long.MAX_VALUE);
        System.out.println("packed source closed failure diagnostics: pass");
    }
}
