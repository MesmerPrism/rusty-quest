package io.github.mesmerprism.rustyquest.qcl041;

import org.json.JSONException;
import org.json.JSONObject;

final class Qcl081LslNativeBridge {
    private static LoadState loadState;

    private Qcl081LslNativeBridge() {
    }

    static synchronized LoadState runtimeState() {
        if (loadState == null) {
            loadState = loadRuntime();
        }
        return loadState;
    }

    static JSONObject publishSamples(
            String streamName,
            String streamType,
            String sourceId,
            int sampleCount,
            int warmupMs,
            int intervalMs) throws JSONException {
        LoadState state = runtimeState();
        if (!state.available) {
            JSONObject blocked = new JSONObject();
            blocked.put("status", "blocked");
            blocked.put("source", "quest-runtime");
            blocked.put("stream_name", streamName);
            blocked.put("stream_type", streamType);
            blocked.put("source_id", sourceId);
            blocked.put("samples_requested", sampleCount);
            blocked.put("samples_published", 0);
            blocked.put("source_timestamps_monotonic", false);
            blocked.put("issue_codes", new org.json.JSONArray()
                    .put("rusty.quest.issue.qcl081_lsl_native_unavailable"));
            blocked.put("notes", state.detail);
            return blocked;
        }
        return new JSONObject(nativePublishSamples(
                streamName,
                streamType,
                sourceId,
                sampleCount,
                warmupMs,
                intervalMs));
    }

    private static LoadState loadRuntime() {
        StringBuilder attempts = new StringBuilder();
        try {
            try {
                System.loadLibrary("c++_shared");
                attempts.append("Loaded libc++_shared. ");
            } catch (Throwable ignored) {
                attempts.append("libc++_shared pre-load skipped. ");
            }
            System.loadLibrary("lsl");
            attempts.append("Loaded liblsl. ");
            System.loadLibrary("qcl081_lsl_outlet_bridge");
            attempts.append("Loaded qcl081_lsl_outlet_bridge. ");
            String info = nativeLibraryInfo();
            return new LoadState(true, (info == null ? "" : info) + " " + attempts.toString().trim());
        } catch (Throwable throwable) {
            return new LoadState(
                    false,
                    "Quest liblsl outlet runtime unavailable: "
                            + (throwable.getMessage() == null
                            ? throwable.getClass().getSimpleName()
                            : throwable.getMessage())
                            + " "
                            + attempts.toString().trim());
        }
    }

    static final class LoadState {
        final boolean available;
        final String detail;

        LoadState(boolean available, String detail) {
            this.available = available;
            this.detail = detail == null ? "" : detail;
        }
    }

    private static native String nativeLibraryInfo();

    private static native String nativePublishSamples(
            String streamName,
            String streamType,
            String sourceId,
            int sampleCount,
            int warmupMs,
            int intervalMs);
}
