package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;

/** Process-owned token for Activity bookkeeping after verified full physical cleanup, never admission. */
public final class EmbeddedDuplexWholeCloseRollover {
    public final String processEpochId, runtimeConfigSha256, enrollmentRecordSha256, receiptSha256;
    public final long appGeneration, nativeExecutorGeneration, attachmentGeneration;
    private EmbeddedDuplexWholeCloseRollover(String epoch, long app, long executor, long attachment,
            String config, String enrollment, String receipt) {
        processEpochId=epoch; appGeneration=app; nativeExecutorGeneration=executor;
        attachmentGeneration=attachment; runtimeConfigSha256=config;
        enrollmentRecordSha256=enrollment; receiptSha256=receipt;
    }
    // Only the process host can mint this after all physical barriers and the persisted fence join.
    static EmbeddedDuplexWholeCloseRollover verified(JSONObject proof, String epoch, long app,
            long executor, long attachment, String config, String enrollment) throws Exception {
        String schema=proof.getString("$schema");
        boolean local="rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1".equals(schema);
        boolean remote="rusty.quest.embedded_duplex.remote_owner_cleanup.v1".equals(schema);
        if (!(local || remote) || !"whole_app_close".equals(proof.getString("action"))
                || !config.equals(proof.getString("config_sha256"))
                || app!=proof.getLong("app_process_generation") || executor!=proof.getLong("native_executor_generation")
                || app<=0 || executor<=0 || attachment<=0 || epoch==null || !epoch.startsWith("app-process."+app+".")
                || config==null || !config.matches("[0-9a-f]{64}")
                || enrollment==null || !enrollment.matches("[0-9a-f]{64}")
                || !(local ? "terminal" : "not_locally_started").equals(proof.getString("peer_physical_cleanup")))
            throw new IllegalStateException("whole-close Activity owner proof differs");
        for (String key : new String[]{"native_host_physical_cleanup","java_own_capture_cleanup",
                "java_renderer_cleanup","java_control_cleanup","app_process_fence_cleanup","whole_app_physical_cleanup","status"})
            if (!"terminal".equals(proof.getString(key)))
                throw new IllegalStateException("whole-close Activity physical proof pending");
        return new EmbeddedDuplexWholeCloseRollover(epoch,app,executor,attachment,config,enrollment,
                EmbeddedDuplexProcessFence.digest(proof.toString()));
    }
    public boolean matchesRuntime(long attachment, String config, String enrollment) {
        return attachmentGeneration==attachment && runtimeConfigSha256.equals(config)
                && enrollmentRecordSha256.equals(enrollment);
    }
}
