package io.github.mesmerprism.rustyquest.native_renderer;

import java.nio.charset.StandardCharsets;

/** Optional adapter name in a legacy scan response; primary service discovery stays independent. */
final class ExperimentSessionBleNamePolicy {
    private static final int LEGACY_SCAN_RESPONSE_BYTES = 31;
    private static final int NAME_FIELD_OVERHEAD_BYTES = 2;

    private ExperimentSessionBleNamePolicy() { }

    static String disposition(String adapterName) {
        if (adapterName == null || adapterName.trim().isEmpty()) return "unavailable";
        return adapterName.getBytes(StandardCharsets.UTF_8).length
                <= LEGACY_SCAN_RESPONSE_BYTES - NAME_FIELD_OVERHEAD_BYTES
            ? "included" : "exceeds-scan-response-budget";
    }
}
