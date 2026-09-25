package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import java.util.concurrent.CompletableFuture;

/** Reviewable private enrollment enters only the process-owned serialized gate. */
public final class EmbeddedDuplexEnrollmentService {
    private EmbeddedDuplexEnrollmentService() {}

    public static CompletableFuture<EmbeddedDuplexEnrollmentStatus> status(
            Context context, String roleId) {
        return EmbeddedDuplexProcessHost.forApplication(context).enrollmentStatus(roleId);
    }

    public static CompletableFuture<EmbeddedDuplexEnrollmentReview> review(
            Context context, EmbeddedDuplexEnrollmentDraft draft) {
        return EmbeddedDuplexProcessHost.forApplication(context).reviewEnrollment(draft);
    }

    public static CompletableFuture<EmbeddedDuplexEnrollment> confirm(
            Context context, EmbeddedDuplexEnrollmentReview review) {
        return EmbeddedDuplexProcessHost.forApplication(context).confirmEnrollment(review);
    }
}
