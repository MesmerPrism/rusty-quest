package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Optional private capsule boundary; absent or mismatched enrollment fails closed. */
final class EmbeddedDuplexEnrollmentResolver {
    private static final String REGISTRY =
            "io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialPrivateFeatureRegistry";

    private EmbeddedDuplexEnrollmentResolver() {}

    static EmbeddedDuplexEnrollment resolve(Context context) throws Exception {
        if (context == null || context.getApplicationContext() == null) {
            throw new IllegalArgumentException("application context required");
        }
        Context app = context.getApplicationContext();
        Class<?> registry = Class.forName(REGISTRY);
        Method roleMethod = registry.getMethod("readEmbeddedDuplexRole", Context.class);
        Object selectedRole = invoke(roleMethod, app);
        if (!(selectedRole instanceof String)) {
            throw new IllegalStateException("private installed role unavailable");
        }
        EmbeddedDuplexEnrollmentRequest installed =
                EmbeddedDuplexEnrollmentRequest.localSnapshot(app, (String) selectedRole);
        Method resolveMethod = registry.getMethod("resolveEmbeddedDuplexEnrollment",
                Context.class, Object.class);
        Object candidate = invoke(resolveMethod, app, installed);
        if (!(candidate instanceof EmbeddedDuplexEnrollment)) {
            throw new IllegalStateException("private enrollment unavailable");
        }
        EmbeddedDuplexEnrollment resolved = (EmbeddedDuplexEnrollment) candidate;
        if (resolved.installed != installed) {
            throw new IllegalStateException("private enrollment changed verified package facts");
        }
        return resolved;
    }

    static EmbeddedDuplexEnrollment replace(Context context, EmbeddedDuplexEnrollmentDraft draft)
            throws Exception {
        if (context == null || context.getApplicationContext() == null || draft == null) {
            throw new IllegalArgumentException("enrollment replacement inputs");
        }
        Context app = context.getApplicationContext();
        EmbeddedDuplexEnrollmentRequest installed =
                EmbeddedDuplexEnrollmentRequest.localSnapshot(app, draft.roleId);
        Class<?> registry = Class.forName(REGISTRY);
        Method replaceMethod = registry.getMethod("replaceEmbeddedDuplexEnrollment",
                Context.class, Object.class, Object.class);
        Object candidate = invoke(replaceMethod, app, installed, draft);
        if (!(candidate instanceof EmbeddedDuplexEnrollment)) {
            throw new IllegalStateException("private enrollment replacement unavailable");
        }
        EmbeddedDuplexEnrollment resolved = (EmbeddedDuplexEnrollment) candidate;
        if (resolved.installed != installed || !draft.roleId.equals(resolved.installed.roleId)
                || !draft.remotePublicKeyHex.equals(resolved.remotePublicKeyHex)
                || !draft.runtimeHostId.equals(resolved.runtimeHostId)
                || !draft.trustedOperatorId.equals(resolved.trustedOperatorId)
                || !draft.adapterId.equals(resolved.adapterId)
                || !draft.mediaRevokerId.equals(resolved.mediaRevokerId)
                || !draft.admissionAuthorityId.equals(resolved.admissionAuthorityId)
                || draft.maxTokenTtlMs != resolved.maxTokenTtlMs
                || draft.localFixture != resolved.localFixture) {
            throw new IllegalStateException("private enrollment replacement changed reviewed draft");
        }
        return resolved;
    }

    static EmbeddedDuplexEnrollmentReview review(Context context,
            EmbeddedDuplexEnrollmentDraft draft) throws Exception {
        if (context == null || context.getApplicationContext() == null || draft == null) {
            throw new IllegalArgumentException("enrollment review inputs");
        }
        Context app = context.getApplicationContext();
        EmbeddedDuplexEnrollmentRequest installed =
                EmbeddedDuplexEnrollmentRequest.localSnapshot(app, draft.roleId);
        Class<?> registry = Class.forName(REGISTRY);
        Method method = registry.getMethod("reviewEmbeddedDuplexEnrollment",
                Context.class, Object.class, Object.class);
        Object candidate = invoke(method, app, installed, draft);
        if (!(candidate instanceof EmbeddedDuplexEnrollmentFence)) {
            throw new IllegalStateException("private enrollment review unavailable");
        }
        return new EmbeddedDuplexEnrollmentReview(installed, draft,
                (EmbeddedDuplexEnrollmentFence) candidate);
    }

    static EmbeddedDuplexEnrollmentStatus status(Context context, String roleId) throws Exception {
        if (context == null || context.getApplicationContext() == null || roleId == null) {
            throw new IllegalArgumentException("enrollment status inputs");
        }
        Context app = context.getApplicationContext();
        EmbeddedDuplexEnrollmentRequest installed =
                EmbeddedDuplexEnrollmentRequest.localSnapshot(app, roleId);
        Class<?> registry = Class.forName(REGISTRY);
        Method method = registry.getMethod("statusEmbeddedDuplexEnrollment",
                Context.class, Object.class);
        Object candidate = invoke(method, app, installed);
        if (!(candidate instanceof EmbeddedDuplexEnrollmentStatus)
                || ((EmbeddedDuplexEnrollmentStatus) candidate).installed != installed) {
            throw new IllegalStateException("private enrollment status unavailable");
        }
        return (EmbeddedDuplexEnrollmentStatus) candidate;
    }

    static EmbeddedDuplexEnrollment replaceReviewed(Context context,
            EmbeddedDuplexEnrollmentReview review) throws Exception {
        if (context == null || context.getApplicationContext() == null || review == null) {
            throw new IllegalArgumentException("enrollment confirmation inputs");
        }
        Context app = context.getApplicationContext();
        EmbeddedDuplexEnrollmentRequest current =
                EmbeddedDuplexEnrollmentRequest.localSnapshot(app, review.draft.roleId);
        if (!review.installed.sameFacts(current)) {
            throw new IllegalStateException("installed package or identity changed after review");
        }
        Class<?> registry = Class.forName(REGISTRY);
        Method method = registry.getMethod("replaceReviewedEmbeddedDuplexEnrollment",
                Context.class, Object.class, Object.class, Object.class);
        Object candidate = invoke(method, app, current, review.draft, review.fence);
        if (!(candidate instanceof EmbeddedDuplexEnrollment)) {
            throw new IllegalStateException("private reviewed replacement unavailable");
        }
        EmbeddedDuplexEnrollment result = (EmbeddedDuplexEnrollment) candidate;
        if (result.installed != current || !review.draft.remotePublicKeyHex.equals(result.remotePublicKeyHex)
                || !review.draft.runtimeHostId.equals(result.runtimeHostId)
                || !review.draft.trustedOperatorId.equals(result.trustedOperatorId)
                || !review.draft.adapterId.equals(result.adapterId)
                || !review.draft.mediaRevokerId.equals(result.mediaRevokerId)
                || !review.draft.admissionAuthorityId.equals(result.admissionAuthorityId)
                || review.draft.maxTokenTtlMs != result.maxTokenTtlMs
                || review.draft.localFixture != result.localFixture
                || result.revision != review.fence.revision + 1L) {
            throw new IllegalStateException("private enrollment changed reviewed transaction");
        }
        return result;
    }

    /** Private app authors the fixed debug fixture; shell contributes no enrollment fields. */
    static EmbeddedDuplexEnrollmentDraft localDiagnosticDraft(Context context) throws Exception {
        if (context == null || context.getApplicationContext() == null) {
            throw new IllegalArgumentException("application context required");
        }
        Class<?> registry = Class.forName(REGISTRY);
        Method create = registry.getMethod("createEmbeddedDuplexLocalDiagnosticDraft", Context.class);
        Object candidate = invoke(create, context.getApplicationContext());
        if (!(candidate instanceof EmbeddedDuplexEnrollmentDraft)) {
            throw new IllegalStateException("private local fixture unavailable");
        }
        EmbeddedDuplexEnrollmentDraft draft = (EmbeddedDuplexEnrollmentDraft) candidate;
        if (!"peer_a".equals(draft.roleId) || !draft.localFixture) {
            throw new IllegalStateException("private local fixture scope invalid");
        }
        return draft;
    }

    private static Object invoke(Method method, Object... arguments) throws Exception {
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("private enrollment failed", cause);
        }
    }
}
