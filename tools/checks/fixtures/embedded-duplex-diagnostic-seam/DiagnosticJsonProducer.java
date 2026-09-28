package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import io.github.mesmerprism.rustyquest.media.*;
import org.json.JSONObject;

/** Actual Registry rejection/classifier/getter seam; no platform effect or JNI invocation. */
public final class DiagnosticJsonProducer {
    private DiagnosticJsonProducer() {}

    private static String ticket() throws Exception {
        return new JSONObject().put("$schema", MediaOwnerAction.SCHEMA)
                .put("capability", "fixture.arm").put("executor_generation", 1)
                .put("action_id", "fixture.action").put("authority_epoch_id", "fixture.epoch")
                .put("media_acceptance_authority_revision", 1).put("expected_runtime_revision", 1)
                .put("client_id", "fixture.client").put("lease_id", "fixture.lease").put("sequence", 1)
                .put("operation", "start").put("owner_kind", "sink").put("action_kind", "arm_receiver")
                .put("owner_id", "fixture.owner").put("provider_kind", "fixture.sink")
                .put("resource_id", "fixture.resource").toString();
    }

    private static void set(Object object, String field, Object value) throws Exception {
        java.lang.reflect.Field target = object.getClass().getDeclaredField(field);
        target.setAccessible(true);
        target.set(object, value);
    }

    private static String diagnostic(EmbeddedDuplexPlatform.ProviderReason reason) throws Exception {
        // Constructor effects are deliberately excluded. Transfer the actual classifier result
        // into the actual getter state; this is not a complete Platform dispatch invocation.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        EmbeddedDuplexPlatform platform = (EmbeddedDuplexPlatform) unsafeClass
                .getMethod("allocateInstance", Class.class).invoke(unsafeField.get(null), EmbeddedDuplexPlatform.class);
        set(platform, "failedOwnerStage", EmbeddedDuplexPlatform.OwnerStage.PROVIDER_EXECUTION);
        set(platform, "failedOwnerAction", "ARM_RECEIVER");
        set(platform, "failedSinkStage", "NONE");
        set(platform, "failedProviderReason", reason);
        return platform.ownerFailureDiagnostic();
    }

    public static void main(String[] args) throws Exception {
        MediaOwnerProvider foreign = new MediaOwnerProvider() {
            public MediaProviderReadback execute(MediaOwnerAction action, CancellationHandle cancellation) { return null; }
            public MediaProviderReadback compensate(MediaOwnerAction action, CancellationHandle cancellation) { return null; }
            public MediaRuntimeSnapshot snapshot() { return null; }
        };
        MediaProductBinding binding = new MediaProductBinding.Builder("fixture.product")
                .bind("sink", "fixture.owner", "fixture.sink", "fixture.resource", foreign).build();
        PackagedAndroidMediaOwnerRegistry registry = new PackagedAndroidMediaOwnerRegistry(1, binding);
        try {
            registry.execute(ticket(), false);
            throw new AssertionError("foreign readback must reject");
        } catch (IllegalStateException expected) {
            EmbeddedDuplexPlatform.ProviderReason reason = EmbeddedDuplexPlatform.providerReason(expected);
            if (reason != EmbeddedDuplexPlatform.ProviderReason.FOREIGN_READBACK) {
                throw new AssertionError("wrong actual Registry rejection", expected);
            }
            System.out.println(diagnostic(reason));
        }
        for (EmbeddedDuplexPlatform.ProviderReason reason : EmbeddedDuplexPlatform.ProviderReason.values()) {
            System.out.println(diagnostic(reason));
        }
    }
}
