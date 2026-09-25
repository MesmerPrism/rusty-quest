package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class EmbeddedDuplexDiagnosticReceiptTest {
    private static final String CHALLENGE = "1234567890abcdef1234567890abcdef";

    @Test public void failedReceiptCarriesOnlyTypedStageAndCode() throws Exception {
        JSONObject receipt = EmbeddedDuplexDiagnosticService.receiptDocument(CHALLENGE,
                "bootstrap_unavailable_closed", "a".repeat(64), null,
                EmbeddedDuplexBootstrap.Failure.NATIVE_ASSEMBLE);
        assertEquals("rusty.quest.embedded_duplex.local_diagnostic.v1", receipt.getString("$schema"));
        assertEquals("native_assemble", receipt.getString("failure_stage"));
        assertEquals("rejected_or_unavailable", receipt.getString("failure_code"));
        assertTrue(receipt.isNull("runtime_config_sha256"));
        assertFalse(receipt.has("exception"));
        assertFalse(receipt.has("message"));
        receipt.put("failure_code", "private_error_text");
        assertThrows(IllegalStateException.class,
                () -> EmbeddedDuplexDiagnosticService.validateFailureProjection(receipt));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddedDuplexDiagnosticService.receiptDocument(CHALLENGE,
                        "bootstrap_unavailable_closed", null, null, null));
    }

    @Test public void successfulReceiptHasNoFailureAssertion() throws Exception {
        JSONObject receipt = EmbeddedDuplexDiagnosticService.receiptDocument(CHALLENGE,
                "bootstrap_closed", "a".repeat(64), "b".repeat(64), null);
        assertTrue(receipt.isNull("failure_stage"));
        assertTrue(receipt.isNull("failure_code"));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddedDuplexDiagnosticService.receiptDocument(CHALLENGE,
                        "bootstrap_closed", null, null, EmbeddedDuplexBootstrap.Failure.UNKNOWN));
    }
}
