package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import java.util.concurrent.CompletableFuture;

/** Shared app-owned handler for the panel and the debug typed operator transport. */
public final class EmbeddedDuplexRuntimeService {
    private EmbeddedDuplexRuntimeService() {}

    public static CompletableFuture<EmbeddedDuplexRuntimeStatus> status(Context context) {
        return EmbeddedDuplexProcessHost.forApplication(context).runtimeStatus();
    }

    public static CompletableFuture<EmbeddedDuplexRuntimeStatus> bootstrapRealPeer(
            Context context, long displayGeneration) {
        return EmbeddedDuplexProcessHost.forApplication(context)
                .bootstrapRealPeer(displayGeneration);
    }

    public static CompletableFuture<String> closeNoMedia(Context context,
            long displayGeneration) {
        return EmbeddedDuplexProcessHost.forApplication(context)
                .closeRealPeerNoMedia(displayGeneration);
    }
}
