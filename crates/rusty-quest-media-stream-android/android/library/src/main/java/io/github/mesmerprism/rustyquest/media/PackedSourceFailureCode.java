package io.github.mesmerprism.rustyquest.media;

import java.io.IOException;

/** Closed, message-free source failure labels for bounded device diagnostics. */
final class PackedSourceFailureCode {
    enum Operation {
        START, CAMERA_OPEN, SYNTHETIC_REQUEST, SYNC_REQUEST, ENCODER_DEQUEUE, ENCODER_GET_OUTPUT,
        PAIR_VALIDATE, PACKET_OFFER, ENCODER_RELEASE, FRESHNESS_CHECK, SLEEP,
        STOP_DRAIN, ENCODER_INPUT
    }

    static final class FreshnessExpired extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        FreshnessExpired() { super("packed source freshness deadline expired"); }
    }

    private PackedSourceFailureCode() { }

    static String cause(Throwable failure) {
        String category = "OTHER";
        Throwable current = failure;
        for (int i = 0; current != null && i < 8; i++, current = current.getCause()) {
            if ("android.media.MediaCodec$CodecException".equals(current.getClass().getName()))
                return "CODEC";
            if (current instanceof IOException) category = "IO";
            else if (current instanceof InterruptedException) category = "INTERRUPTED";
            else if (current instanceof IllegalStateException && "OTHER".equals(category))
                category = "STATE";
        }
        return category;
    }

    static String detail(Operation operation, Throwable failure) {
        if (failure instanceof FreshnessExpired) return "FRESHNESS_EXPIRED";
        String category = cause(failure);
        if ("CODEC".equals(category)) return "CODEC_ERROR";
        if ("STATE".equals(category)) {
            switch (operation) {
                case ENCODER_DEQUEUE:
                case ENCODER_GET_OUTPUT:
                case ENCODER_RELEASE:
                    return "ENCODER_STATE";
                default:
                    return "STATE_OTHER";
            }
        }
        return category;
    }

    static long ageMs(long nowMs, long lastMs) {
        return nowMs < 0L || lastMs < 0L || nowMs < lastMs ? -1L : nowMs - lastMs;
    }
}
