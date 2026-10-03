package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import java.util.concurrent.CompletableFuture;

/** Shared app-owned handler for the panel and the debug typed operator transport. */
public final class EmbeddedDuplexRuntimeService {
    private EmbeddedDuplexRuntimeService() {}

    public static CompletableFuture<String> armConcurrentQualification(Context context, String challenge) {
        return EmbeddedDuplexProcessHost.forApplication(context).concurrentQualification(true, challenge);
    }
    public static CompletableFuture<String> concurrentQualificationStatus(Context context, String challenge) {
        return EmbeddedDuplexProcessHost.forApplication(context).concurrentQualification(false, challenge);
    }

    /** Debug shell observation only; does not advance an owner or media readiness state. */
    public static CompletableFuture<String> ownCaptureDiagnostic(Context context, String challenge) {
        return EmbeddedDuplexProcessHost.forApplication(context).ownCaptureDiagnostic(challenge);
    }
    /** Read-only bounded statistics; never admission, activation or cleanup proof. */
    public static CompletableFuture<String> streamDropoutDiagnostic(Context context, String challenge) {
        return EmbeddedDuplexProcessHost.forApplication(context).streamDropoutDiagnostic(challenge);
    }

    public static CompletableFuture<String> concurrentHubObservation(Context context) {
        return EmbeddedDuplexProcessHost.forApplication(context).concurrentHubObservation();
    }
    public static CompletableFuture<String> concurrentHubPolicy(Context context, long[] policy, java.util.concurrent.atomic.AtomicBoolean cancelled) {
        return EmbeddedDuplexProcessHost.forApplication(context).concurrentHubPolicy(policy,cancelled);
    }
    public static CompletableFuture<String> concurrentMask(Context context, String challenge, long[] mask) {
        return EmbeddedDuplexProcessHost.forApplication(context).concurrentMask(challenge,mask);
    }
    public static CompletableFuture<String> concurrentPolicy(Context context, String challenge, long[] policy) {
        return EmbeddedDuplexProcessHost.forApplication(context).concurrentPolicy(challenge, policy);
    }

    public static CompletableFuture<String> peerLifecycle(Context context, EmbeddedDuplexPeerAction action, String challenge) {
        return EmbeddedDuplexProcessHost.forApplication(context).peerLifecycle(action, challenge);
    }

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
