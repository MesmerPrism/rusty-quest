package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import java.util.concurrent.CompletableFuture;

/** Reviewable private enrollment enters only the process-owned serialized gate. */
public final class EmbeddedDuplexEnrollmentService {
    private EmbeddedDuplexEnrollmentService() {}

    public static CompletableFuture<EmbeddedDuplexEnrollment> replaceAfterTerminalClose(
            Context context, EmbeddedDuplexEnrollmentDraft reviewedDraft) {
        return EmbeddedDuplexProcessHost.forApplication(context).replaceEnrollment(reviewedDraft);
    }
}
