package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;

/** Process-owned one-shot intent; an uncertain native Start retains cleanup. */
final class EmbeddedDuplexStartIntentSlot {
    private EmbeddedDuplexStartPreflight pending;
    private EmbeddedDuplexStartPreflight dispatched;

    synchronized EmbeddedDuplexStartPreflight pending() { return pending; }

    synchronized EmbeddedDuplexStartPreflight prepare(EmbeddedDuplexStartPreflight next) {
        if (dispatched != null) throw new IllegalStateException("previous Start requires process cleanup");
        if (next == null || pending != null && !pending.sameLineage(next))
            throw new IllegalStateException("pre-Start lineage changed");
        pending = next;
        return next;
    }

    synchronized void dispatch(EmbeddedDuplexStartPreflight observed) {
        if (observed == null || pending != observed || dispatched != null)
            throw new IllegalStateException("current paired Start intent unavailable");
        // Consume before entering native code, including a throwing/Pending call.
        dispatched = observed;
        pending = null;
    }

    synchronized void acknowledge(EmbeddedDuplexStartPreflight observed, String nativeReceipt) throws Exception {
        if (observed == null || dispatched != observed)
            throw new IllegalStateException("Start intent acknowledgement differs");
        JSONObject result = new JSONObject(nativeReceipt);
        if (!"rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1".equals(result.getString("$schema"))
                || !"start".equals(result.getString("action"))
                || !observed.runtimeConfigSha256.equals(result.getString("config_sha256"))
                || result.getLong("native_executor_generation") <= 0L
                || result.getLong("app_process_generation") <= 0L)
            throw new IllegalStateException("Start acknowledgement lineage differs");
        if ("active".equals(result.getString("status")) && result.has("last_failure") && result.isNull("last_failure")
                && !result.getBoolean("renewal_pending")) {
            dispatched = null;
        }
    }

    synchronized void afterVerifiedCleanup() { pending = null; dispatched = null; }
}
