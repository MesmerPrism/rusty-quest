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
        return resolve(context, false, null);
    }

    static EmbeddedDuplexEnrollment resolve(Context context, FrozenEffectiveInputs frozen) throws Exception {
        return resolve(context, false, frozen);
    }

    static EmbeddedDuplexPackagedInputs.InstalledRole installedRole(Context context) throws Exception {
        if (context == null || context.getApplicationContext() == null)
            throw new IllegalArgumentException("application context required");
        Class<?> registry = Class.forName(REGISTRY);
        Object role = invoke(registry.getMethod("readEmbeddedDuplexRole", Context.class), context.getApplicationContext());
        if (!(role instanceof String)) throw new IllegalStateException("private installed role unavailable");
        return EmbeddedDuplexPackagedInputs.InstalledRole.parse((String) role);
    }

    private static EmbeddedDuplexEnrollmentRequest snapshot(Context app, String role,
            boolean existingOnly, FrozenEffectiveInputs frozen) throws Exception {
        if (frozen != null) return existingOnly
                ? EmbeddedDuplexEnrollmentRequest.existingSnapshot(app, role, frozen)
                : EmbeddedDuplexEnrollmentRequest.localSnapshot(app, role, frozen);
        return existingOnly ? EmbeddedDuplexEnrollmentRequest.existingSnapshot(app, role)
                : EmbeddedDuplexEnrollmentRequest.localSnapshot(app, role);
    }

    private static EmbeddedDuplexEnrollment resolve(Context context, boolean existingOnly,
            FrozenEffectiveInputs frozen) throws Exception {
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
        EmbeddedDuplexEnrollmentRequest installed = snapshot(app, (String) selectedRole, existingOnly, frozen);
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
        return replace(context, draft, null);
    }

    static EmbeddedDuplexEnrollment replace(Context context, EmbeddedDuplexEnrollmentDraft draft,
            FrozenEffectiveInputs frozen) throws Exception {
        if (context == null || context.getApplicationContext() == null || draft == null) {
            throw new IllegalArgumentException("enrollment replacement inputs");
        }
        Context app = context.getApplicationContext();
        EmbeddedDuplexEnrollmentRequest installed = snapshot(app, draft.roleId, false, frozen);
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
        return review(context, draft, false, null);
    }

    static EmbeddedDuplexEnrollmentReview review(Context context, EmbeddedDuplexEnrollmentDraft draft,
            FrozenEffectiveInputs frozen) throws Exception { return review(context, draft, false, frozen); }

    private static EmbeddedDuplexEnrollmentReview review(Context context,
            EmbeddedDuplexEnrollmentDraft draft, boolean policyMetadataOnly, FrozenEffectiveInputs frozen) throws Exception {
        if (context == null || context.getApplicationContext() == null || draft == null) {
            throw new IllegalArgumentException("enrollment review inputs");
        }
        Context app = context.getApplicationContext();
        EmbeddedDuplexEnrollmentRequest installed = snapshot(app, draft.roleId, policyMetadataOnly, frozen);
        Class<?> registry = Class.forName(REGISTRY);
        Method method = registry.getMethod("reviewEmbeddedDuplexEnrollment",
                Context.class, Object.class, Object.class);
        Object candidate = invoke(method, app, installed, draft);
        if (!(candidate instanceof EmbeddedDuplexEnrollmentFence)) {
            throw new IllegalStateException("private enrollment review unavailable");
        }
        return new EmbeddedDuplexEnrollmentReview(installed, draft,
                (EmbeddedDuplexEnrollmentFence) candidate, policyMetadataOnly);
    }

    /** Same-key metadata change: prior enrolled intent remains effective until confirmed. */
    static EmbeddedDuplexEnrollmentReview reviewPolicyUpdate(Context context) throws Exception {
        return reviewPolicyUpdate(context, null);
    }

    static EmbeddedDuplexEnrollmentReview reviewPolicyUpdate(Context context, FrozenEffectiveInputs frozen) throws Exception {
        if (EmbeddedDuplexSetupCredentials.developmentPolicy() == null)
            throw new IllegalStateException("development lifetime policy is not selected");
        EmbeddedDuplexEnrollment current = resolve(context, true, frozen);
        if (current.localFixture) throw new IllegalStateException("real enrollment required");
        long ttl = EmbeddedDuplexSetupCredentials.selectedHorizon(current.maxTokenTtlMs);
        if (ttl == current.maxTokenTtlMs) throw new IllegalStateException("enrollment policy is already current");
        EmbeddedDuplexEnrollmentDraft draft = new EmbeddedDuplexEnrollmentDraft(
                current.installed.roleId, current.remotePublicKeyHex, current.runtimeHostId,
                current.trustedOperatorId, current.adapterId, current.mediaRevokerId,
                current.admissionAuthorityId, ttl, false);
        EmbeddedDuplexEnrollmentReview reviewed = review(context, draft, true, frozen);
        if (reviewed.fence.revision != current.revision
                || !current.recordSha256.equals(reviewed.fence.recordSha256)
                || !current.installed.sameFacts(reviewed.installed))
            throw new IllegalStateException("enrollment changed before policy review");
        return reviewed;
    }

    static EmbeddedDuplexEnrollmentStatus status(Context context, String roleId) throws Exception {
        return status(context, roleId, null);
    }

    static EmbeddedDuplexEnrollmentStatus status(Context context, String roleId, FrozenEffectiveInputs frozen) throws Exception {
        if (context == null || context.getApplicationContext() == null || roleId == null) {
            throw new IllegalArgumentException("enrollment status inputs");
        }
        Context app = context.getApplicationContext();
        EmbeddedDuplexEnrollmentRequest installed = snapshot(app, roleId, false, frozen);
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
        return replaceReviewed(context, review, null);
    }

    static EmbeddedDuplexEnrollment replaceReviewed(Context context,
            EmbeddedDuplexEnrollmentReview review, FrozenEffectiveInputs frozen) throws Exception {
        if (context == null || context.getApplicationContext() == null || review == null) {
            throw new IllegalArgumentException("enrollment confirmation inputs");
        }
        Context app = context.getApplicationContext();
        EmbeddedDuplexEnrollmentRequest current = snapshot(app, review.draft.roleId, review.policyMetadataOnly, frozen);
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
