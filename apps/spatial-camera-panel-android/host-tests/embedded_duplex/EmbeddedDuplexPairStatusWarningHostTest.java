package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Actual Native status producer bytes; Host/clock/authority leaves are modeled. */
public final class EmbeddedDuplexPairStatusWarningHostTest {
    private static int checks;
    private static void need(boolean value) {
        checks++;
        if (!value) throw new AssertionError("pair warning control " + checks);
    }
    private static void refuses(JSONObject value) throws Exception {
        try {
            EmbeddedDuplexPairStatus.parse(value.toString());
            throw new AssertionError("malformed pair warning accepted");
        } catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        String raw = Files.readString(Path.of(args[0]));
        EmbeddedDuplexPairStatus status = EmbeddedDuplexPairStatus.parseFresh(raw);
        need(status.selectedFreshPolicy && "in_progress".equals(status.state)
                && !status.routeCurrent && !status.mediaEffectProven
                && raw.equals(status.exactNativeObservation));
        need(Boolean.TRUE.equals(status.ceremonyWaitWarning));
        JSONObject value = new JSONObject(raw);
        value.put("ceremony_wait_warning", false);
        need(Boolean.FALSE.equals(EmbeddedDuplexPairStatus.parseFresh(value.toString()).ceremonyWaitWarning));
        value.remove("ceremony_wait_warning");
        need(EmbeddedDuplexPairStatus.parseFresh(value.toString()).ceremonyWaitWarning == null);
        for (Object bad : new Object[] {JSONObject.NULL, "true", 1}) {
            value.put("ceremony_wait_warning", bad);
            refuses(value);
        }
        value.put("ceremony_wait_warning", true).put("caller_override", true);
        refuses(value);
        value.remove("caller_override");
        value.put("$schema", "rusty.quest.embedded_duplex.pair_status.v1");
        value.remove("last_failure_cause"); value.remove("fresh_setup");
        need(Boolean.TRUE.equals(EmbeddedDuplexPairStatus.parseLegacy(value.toString()).ceremonyWaitWarning));
        value.remove("ceremony_wait_warning");
        need(EmbeddedDuplexPairStatus.parseLegacy(value.toString()).ceremonyWaitWarning == null);
        value.put("ceremony_wait_warning", "false"); refuses(value);
        System.out.println("PASS actual Native status -> Java pair parser controls=" + checks);
    }
}
