package io.github.mesmerprism.rustyquest.media;

import android.os.Trace;

/** Fixed Android APP-tag sections. The external tracing session owns enablement and lifetime. */
final class ReceiverTraceSections {
    static final String READ_PACKET = "rusty.media.receiver.read_packet";
    static final String SOCKET_READ = "rusty.media.receiver.socket_read";
    static final String QUEUE_PACKET = "rusty.media.receiver.queue_packet";
    static final String DRAIN = "rusty.media.receiver.drain";
    static final String ACQUIRE_PROBE = "rusty.media.receiver.acquire_probe";
    static final String PRE_RENDER = "rusty.media.receiver.pre_render";
    static final String RELEASE_OUTPUT = "rusty.media.receiver.release_output";

    private ReceiverTraceSections() {}

    static boolean begin(String name) {
        try {
            if (!Trace.isEnabled()) return false; // API 29, matching the library minimum.
            Trace.beginSection(name);
            return true;
        } catch (RuntimeException unavailable) {
            return false; // Optional observation cannot replace a media result or exception.
        }
    }

    static void end(boolean began) {
        if (!began) return;
        try { Trace.endSection(); }
        catch (RuntimeException unavailable) { /* Preserve the original media result or exception. */ }
    }
}
