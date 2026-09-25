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

    public static CompletableFuture<EmbeddedDuplexPairStatus> pairStatus(Context context) {
        return EmbeddedDuplexProcessHost.forApplication(context).pairStatus();
    }

    /** The lower packaged peer ID initiates; the other Quest answers on its process endpoint. */
    public static CompletableFuture<EmbeddedDuplexPairStatus> pairSession(Context context) {
        return EmbeddedDuplexProcessHost.forApplication(context).pairSession();
    }

    /** Retains a current signed session and process lineage before any Start. */
    public static CompletableFuture<EmbeddedDuplexStartPreflight> prepareStartPreflight(
            Context context, long displayGeneration) {
        return EmbeddedDuplexProcessHost.forApplication(context)
                .prepareStartPreflight(displayGeneration);
    }

    public static boolean preflightLive(Context context, EmbeddedDuplexStartPreflight observed) {
        return EmbeddedDuplexProcessHost.forApplication(context).preflightLive(observed);
    }
}
