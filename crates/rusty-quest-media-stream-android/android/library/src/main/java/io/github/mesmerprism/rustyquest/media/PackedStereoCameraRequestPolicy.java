package io.github.mesmerprism.rustyquest.media;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.util.Range;
import org.json.JSONObject;

/** Explicit packaged debug experiment. Camera2 template defaults are never rewritten here. */
final class PackedStereoCameraRequestPolicy {
    private static final String RESOURCE = "rusty_quest_packed_camera_request_template";
    final int template;
    final Integer configuredTemplate;
    final boolean debuggable;
    private PackedStereoCameraRequestPolicy(boolean debug, Integer configured) {
        debuggable = debug; configuredTemplate = configured;
        template = debug && configured != null && configured == CameraDevice.TEMPLATE_PREVIEW
                ? CameraDevice.TEMPLATE_PREVIEW : CameraDevice.TEMPLATE_RECORD;
    }
    static PackedStereoCameraRequestPolicy resolve(Context context) {
        boolean debug = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        Integer configured = null;
        if (debug) {
            try {
                int id = context.getResources().getIdentifier(RESOURCE, "integer", context.getPackageName());
                if (id != 0) configured = context.getResources().getInteger(id);
            } catch (RuntimeException unavailable) { /* An unavailable override retains RECORD. */ }
        }
        return new PackedStereoCameraRequestPolicy(debug, configured);
    }
    JSONObject snapshot() throws Exception {
        return new JSONObject().put("debuggable", debuggable)
                .put("configured_template", value(configuredTemplate))
                .put("selected_template", template);
    }
    private static Object value(Object value) { return value == null ? JSONObject.NULL : value; }
    private static Object range(Range<Integer> range) throws Exception {
        return range == null ? JSONObject.NULL : new JSONObject()
                .put("lower", range.getLower()).put("upper", range.getUpper());
    }
    private static <T> T read(CaptureRequest request, CaptureRequest.Key<T> key) {
        try { return request.get(key); } catch (RuntimeException unsupported) { return null; }
    }
    private static <T> T read(CaptureResult result, CaptureResult.Key<T> key) {
        try { return result.get(key); } catch (RuntimeException unsupported) { return null; }
    }
    /** One retained request and one immutable platform result reference per eye.
     * Result sampling uses only the existing ARM window. It changes no camera controls. */
    static final class Diagnostic {
        private volatile RequestSample request;
        private volatile Sample sample;
        private static final class RequestSample {
            final int template;
            final CaptureRequest request;
            RequestSample(int template, CaptureRequest request) { this.template = template; this.request = request; }
        }
        private static final class Sample {
            final CaptureStallDiagnostics.Window window;
            final TotalCaptureResult result;
            Sample(CaptureStallDiagnostics.Window window, TotalCaptureResult result) {
                this.window = window; this.result = result;
            }
        }
        void built(int requestedTemplate, CaptureRequest built) { request = new RequestSample(requestedTemplate, built); }
        void completed(CaptureStallDiagnostics.Window window, TotalCaptureResult result) {
            if (window != null && window.live() && result != null) sample = new Sample(window, result);
        }
        JSONObject snapshot() throws Exception {
            RequestSample built = request;
            CaptureRequest observedRequest = built == null ? null : built.request;
            Sample observed = sample;
            JSONObject output = new JSONObject().put("requested_template", built == null ? JSONObject.NULL : built.template)
                    .put("request", JSONObject.NULL).put("result", JSONObject.NULL);
            if (observedRequest != null) output.put("request", new JSONObject()
                    .put("capture_intent", value(read(observedRequest, CaptureRequest.CONTROL_CAPTURE_INTENT)))
                    .put("ae_target_fps_range", range(read(observedRequest, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)))
                    .put("noise_reduction_mode", value(read(observedRequest, CaptureRequest.NOISE_REDUCTION_MODE)))
                    .put("edge_mode", value(read(observedRequest, CaptureRequest.EDGE_MODE)))
                    .put("aberration_mode", value(read(observedRequest, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE)))
                    .put("video_stabilization_mode", value(read(observedRequest, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE))));
            if (observed != null) {
                CaptureStallDiagnostics.Window window = observed.window;
                TotalCaptureResult result = observed.result;
                output.put("result", new JSONObject().put("frame_number", result.getFrameNumber())
                        .put("capture_intent", value(read(result, CaptureResult.CONTROL_CAPTURE_INTENT)))
                        .put("ae_target_fps_range", range(read(result, CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)))
                        .put("noise_reduction_mode", value(read(result, CaptureResult.NOISE_REDUCTION_MODE)))
                        .put("edge_mode", value(read(result, CaptureResult.EDGE_MODE)))
                        .put("aberration_mode", value(read(result, CaptureResult.COLOR_CORRECTION_ABERRATION_MODE)))
                        .put("video_stabilization_mode", value(read(result, CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)))
                        .put("sensor_timestamp_ns", value(read(result, CaptureResult.SENSOR_TIMESTAMP)))
                        .put("sensor_frame_duration_ns", value(read(result, CaptureResult.SENSOR_FRAME_DURATION)))
                        .put("sensor_exposure_time_ns", value(read(result, CaptureResult.SENSOR_EXPOSURE_TIME)))
                        .put("probe", new JSONObject().put("process_epoch", window.processEpoch)
                                .put("app_generation", window.appGeneration).put("arm_generation", window.armGeneration)
                                .put("start_ns", window.startNs).put("deadline_ns", window.deadlineNs)
                                .put("active", window.live())));
            }
            return output;
        }
    }
}
