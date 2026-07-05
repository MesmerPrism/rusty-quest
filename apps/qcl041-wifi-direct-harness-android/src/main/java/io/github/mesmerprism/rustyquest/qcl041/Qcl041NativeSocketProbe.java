package io.github.mesmerprism.rustyquest.qcl041;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Qcl041NativeSocketProbe {
    private static LoadState loadState;

    private Qcl041NativeSocketProbe() {
    }

    static synchronized LoadState runtimeState() {
        if (loadState == null) {
            loadState = loadRuntime();
        }
        return loadState;
    }

    static JSONObject sendUdp(
            String mode,
            long networkHandle,
            String targetAddress,
            int targetPort,
            String runId,
            int sends) throws JSONException {
        LoadState state = runtimeState();
        if (!state.available) {
            return blocked(mode, networkHandle, targetAddress, targetPort, state);
        }
        String payloadPrefix = mode + ";run_id=" + (runId == null ? "" : runId);
        return new JSONObject(nativeSendUdp(
                networkHandle,
                targetAddress == null ? "" : targetAddress,
                targetPort,
                payloadPrefix,
                sends));
    }

    static JSONObject connectTcp(
            String mode,
            long networkHandle,
            String targetAddress,
            int targetPort,
            String runId,
            int timeoutMs) throws JSONException {
        LoadState state = runtimeState();
        if (!state.available) {
            return blocked(mode, networkHandle, targetAddress, targetPort, state);
        }
        String payload = mode + ";run_id=" + (runId == null ? "" : runId);
        return new JSONObject(nativeConnectTcp(
                networkHandle,
                targetAddress == null ? "" : targetAddress,
                targetPort,
                payload,
                timeoutMs));
    }

    private static JSONObject blocked(
            String mode,
            long networkHandle,
            String targetAddress,
            int targetPort,
            LoadState state) throws JSONException {
        JSONObject blocked = new JSONObject();
        blocked.put("schema", "rusty.quest.qcl041_native_socket_probe.v1");
        blocked.put("mode", mode);
        blocked.put("status", "blocked");
        blocked.put("network_handle", networkHandle);
        blocked.put("target_address", targetAddress == null ? "" : targetAddress);
        blocked.put("target_port", targetPort);
        blocked.put("issue_codes", new JSONArray()
                .put("rusty.quest.issue.qcl041_native_socket_probe_unavailable"));
        blocked.put("notes", state.detail);
        return blocked;
    }

    private static LoadState loadRuntime() {
        try {
            System.loadLibrary("qcl041_socket_probe");
            String info = nativeLibraryInfo();
            return new LoadState(true, info == null ? "" : info);
        } catch (Throwable throwable) {
            return new LoadState(
                    false,
                    "QCL041 native fd android_setsocknetwork runtime unavailable: "
                            + (throwable.getMessage() == null
                            ? throwable.getClass().getSimpleName()
                            : throwable.getMessage()));
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

    private static native String nativeSendUdp(
            long networkHandle,
            String targetAddress,
            int targetPort,
            String payloadPrefix,
            int sends);

    private static native String nativeConnectTcp(
            long networkHandle,
            String targetAddress,
            int targetPort,
            String payload,
            int timeoutMs);
}
