package io.github.mesmerprism.rustyquest.media;

import android.os.Trace;

/** Fixed sender APP-tag sections; the external tracing session owns enablement and lifetime. */
final class SenderTraceSections {
    enum Phase {
        PUMP_CAPACITY_WAIT("rusty.media.sender.capacity_wait"),
        PUMP_WRITE("rusty.media.sender.pump_write"),
        PUMP_FLUSH("rusty.media.sender.pump_flush"),
        LAN_READ("rusty.media.sender.source_read"),
        LAN_WRITE("rusty.media.sender.sink_write"),
        LAN_FLUSH("rusty.media.sender.sink_flush");

        private final String section;
        Phase(String section) { this.section = section; }
    }

    private SenderTraceSections() {}

    static boolean begin(boolean debuggable, Phase phase) {
        if (!debuggable || phase == null) return false;
        try {
            if (!Trace.isEnabled()) return false;
            Trace.beginSection(phase.section);
            return true;
        } catch (RuntimeException | LinkageError unavailable) {
            return false; // Optional observation cannot replace a media result or exception.
        }
    }

    static void end(boolean began) {
        if (!began) return;
        try { Trace.endSection(); }
        catch (RuntimeException | LinkageError unavailable) {
            // Preserve the original media result or exception.
        }
    }
}
