package io.github.mesmerprism.rustymanifold.broker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class RemoteCameraSourceRuntime {
    private static final String SOURCE_EXTERNAL_H264_SOCKET = "external_h264_socket";
    private static final String SOURCE_CAMERA2_MEDIACODEC_SURFACE = "camera2_mediacodec_surface";
    private static final String SOURCE_DIAGNOSTIC_SYNTHETIC_SURFACE =
            "diagnostic_synthetic_mediacodec_surface";
    private static final String SOURCE_DISPLAY_COMPOSITE_MEDIAPROJECTION_SURFACE =
            "display_composite_mediaprojection_mediacodec_surface";
    private static final String SOURCE_SHELL_DISPLAY_MIRROR_SURFACE =
            "shell_display_mirror_mediacodec_surface";
    private static final String CAMERA_PERMISSION_REQUIRED = "camera_permission_required";
    private static final long CAMERA_OPEN_TIMEOUT_MS = 5_000L;
    private static final long CAMERA_SESSION_TIMEOUT_MS = 5_000L;
    private static final AtomicLong NEXT_SOURCE_ID = new AtomicLong(1L);

    private static final Object LOCK = new Object();
    private static final Map<String, SourceGroup> GROUPS = new LinkedHashMap<>();

    private RemoteCameraSourceRuntime() {
    }

    static JSONObject ensureStarted(
            Context context,
            String sessionId,
            String sourceKind,
            String sourceHost,
            String sourcePorts,
            String mediaProfiles,
            String cameraId,
            String cameraIds,
            String cameraFacing,
            String qualityProfile,
            String permissionPolicy) throws Exception {
        String normalizedKind = normalizeSourceKind(sourceKind);
        JSONObject permissionStatus = permissionStatus(context);
        if (SOURCE_EXTERNAL_H264_SOCKET.equals(normalizedKind)) {
            JSONObject result = baseResult(sessionId, normalizedKind, "external_source_expected");
            result.put("source_available", true);
            result.put("runtime_started", false);
            result.put("camera_permission_policy", permissionPolicy);
            result.put("camera_permission_status", permissionStatus);
            return result;
        }
        if (SOURCE_DISPLAY_COMPOSITE_MEDIAPROJECTION_SURFACE.equals(normalizedKind)) {
            return displayAdapterUnavailable(
                    sessionId,
                    normalizedKind,
                    "mediaprojection_consent_route_not_implemented_in_broker_apk",
                    "android_mediaprojection_user_consent",
                    false,
                    permissionPolicy,
                    permissionStatus);
        }
        if (SOURCE_SHELL_DISPLAY_MIRROR_SURFACE.equals(normalizedKind)) {
            return displayAdapterUnavailable(
                    sessionId,
                    normalizedKind,
                    "shell_hidden_display_sidecar_required",
                    "shell_hidden_display_adapter",
                    true,
                    permissionPolicy,
                    permissionStatus);
        }
        if (SOURCE_CAMERA2_MEDIACODEC_SURFACE.equals(normalizedKind)) {
            if (context == null) {
                return unavailable(
                        sessionId,
                        normalizedKind,
                        "android_context_unavailable",
                        permissionPolicy,
                        permissionStatus);
            }
            if (!permissionStatus.optBoolean("android_camera_granted", false)) {
                return unavailable(
                        sessionId,
                        normalizedKind,
                        "android_camera_permission_required",
                        permissionPolicy,
                        permissionStatus);
            }
            if (!CAMERA_PERMISSION_REQUIRED.equals(permissionPolicy)) {
                return unavailable(
                        sessionId,
                        normalizedKind,
                        "camera_permission_policy_not_enabled",
                        permissionPolicy,
                        permissionStatus);
            }
        }

        List<SourcePort> ports = parsePorts(sourcePorts, parseProfiles(mediaProfiles));
        if (ports.isEmpty()) {
            return unavailable(
                    sessionId,
                    normalizedKind,
                    "sender_source_ports_missing",
                    permissionPolicy,
                    permissionStatus);
        }

        Map<String, String> cameraIdsByEye = parseCameraIds(cameraIds);
        if (SOURCE_CAMERA2_MEDIACODEC_SURFACE.equals(normalizedKind) && !cameraIdsByEye.isEmpty()) {
            JSONArray sources = new JSONArray();
            boolean allAvailable = true;
            for (SourcePort port : ports) {
                String eyeCameraId = cameraIdsByEye.containsKey(port.eye)
                        ? cameraIdsByEye.get(port.eye)
                        : cleanOptional(cameraId);
                if (eyeCameraId.length() == 0) {
                    allAvailable = false;
                    sources.put(unavailable(
                            sessionId,
                            normalizedKind,
                            "sender_camera_ids_missing_for_" + port.eye,
                            permissionPolicy,
                            permissionStatus));
                    continue;
                }
                JSONObject source = ensureSourceGroup(
                        context,
                        sessionId,
                        normalizedKind,
                        sourceHost,
                        singletonPort(port),
                        eyeCameraId,
                        cameraFacing,
                        qualityProfile,
                        permissionPolicy,
                        permissionStatus);
                allAvailable = allAvailable && source.optBoolean("source_available", false);
                sources.put(source);
            }
            JSONObject result = baseResult(
                    sessionId,
                    normalizedKind,
                    allAvailable ? "source_groups_started" : "source_group_unavailable");
            result.put("source_available", allAvailable);
            result.put("runtime_started", sources.length() > 0);
            result.put("source_count", sources.length());
            result.put("sources", sources);
            result.put("camera_permission_policy", permissionPolicy);
            result.put("camera_permission_status", permissionStatus);
            return result;
        }

        return ensureSourceGroup(
                context,
                sessionId,
                normalizedKind,
                sourceHost,
                ports,
                cleanOptional(cameraId),
                cleanOptional(cameraFacing),
                cleanOptional(qualityProfile),
                permissionPolicy,
                permissionStatus);
    }

    private static JSONObject ensureSourceGroup(
            Context context,
            String sessionId,
            String normalizedKind,
            String sourceHost,
            List<SourcePort> ports,
            String cameraId,
            String cameraFacing,
            String qualityProfile,
            String permissionPolicy,
            JSONObject permissionStatus) throws Exception {
        String groupKey = sessionId + "|" + normalizedKind + "|" + sourceHost + "|"
                + formatSourcePorts(ports) + "|" + cleanOptional(cameraId);
        synchronized (LOCK) {
            SourceGroup existing = GROUPS.get(groupKey);
            if (existing != null && !existing.isTerminal()) {
                JSONObject result = existing.toJson();
                result.put("camera_permission_status", permissionStatus);
                return result;
            }
        }

        SourceGroup group = new SourceGroup(
                groupKey,
                sessionId,
                normalizedKind,
                sourceHost,
                ports,
                cleanOptional(cameraId),
                cleanOptional(cameraFacing),
                cleanOptional(qualityProfile),
                context != null ? context.getApplicationContext() : null,
                permissionPolicy,
                permissionStatus);
        try {
            group.openAndStart();
        } catch (Exception ex) {
            group.stop("source_start_failed");
            return unavailable(
                    sessionId,
                    normalizedKind,
                    "source_start_failed:" + safeMessage(ex),
                    permissionPolicy,
                    permissionStatus);
        }
        synchronized (LOCK) {
            GROUPS.put(groupKey, group);
        }
        return group.toJson();
    }

    private static List<SourcePort> singletonPort(SourcePort port) {
        List<SourcePort> ports = new ArrayList<>();
        ports.add(port);
        return ports;
    }

    static JSONObject stop(String sessionId, String reason) throws Exception {
        JSONArray stopped = new JSONArray();
        synchronized (LOCK) {
            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, SourceGroup> entry : GROUPS.entrySet()) {
                if (entry.getValue().sessionId.equals(sessionId)) {
                    keys.add(entry.getKey());
                }
            }
            for (String key : keys) {
                SourceGroup group = GROUPS.remove(key);
                if (group != null) {
                    group.stop(reason);
                    stopped.put(group.toJson());
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("session_id", sessionId);
        result.put("stopped_count", stopped.length());
        result.put("sources", stopped);
        result.put("high_rate_json_payload", false);
        return result;
    }

    static JSONObject statusForSession(String sessionId) throws Exception {
        JSONArray sources = new JSONArray();
        synchronized (LOCK) {
            for (SourceGroup group : GROUPS.values()) {
                if (group.sessionId.equals(sessionId)) {
                    sources.put(group.toJson());
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("session_id", sessionId);
        result.put("source_count", sources.length());
        result.put("sources", sources);
        result.put("high_rate_json_payload", false);
        return result;
    }

    private static JSONObject unavailable(
            String sessionId,
            String sourceKind,
            String reason,
            String permissionPolicy,
            JSONObject permissionStatus) throws Exception {
        JSONObject result = baseResult(sessionId, sourceKind, "source_unavailable");
        result.put("source_available", false);
        result.put("runtime_started", false);
        result.put("reason", reason);
        result.put("camera_permission_policy", permissionPolicy);
        result.put("camera_permission_status", permissionStatus);
        return result;
    }

    private static JSONObject displayAdapterUnavailable(
            String sessionId,
            String sourceKind,
            String reason,
            String captureAuthority,
            boolean labOnly,
            String permissionPolicy,
            JSONObject permissionStatus) throws Exception {
        JSONObject result = unavailable(sessionId, sourceKind, reason, permissionPolicy, permissionStatus);
        result.put("schema", "rusty.quest.media_stream.android_display_source_adapter.v1");
        result.put("source_family", "display");
        result.put("display_frame_source", sourceKind);
        result.put("capture_authority", captureAuthority);
        result.put("adapter_surface_only", true);
        result.put("lab_only", labOnly);
        result.put("production_allowed", !labOnly);
        return result;
    }

    private static JSONObject baseResult(String sessionId, String sourceKind, String state) throws Exception {
        JSONObject result = new JSONObject();
        result.put("schema", "rusty.quest.remote_camera.android_sender_source.v1");
        result.put("session_id", sessionId);
        result.put("source_kind", sourceKind);
        result.put("state", state);
        result.put("source_available", true);
        result.put("media_payload_plane", "binary-media");
        result.put("high_rate_json_payload", false);
        return result;
    }

    private static JSONObject permissionStatus(Context context) throws Exception {
        JSONObject result = new JSONObject();
        result.put("android_context_available", context != null);
        putPermission(result, context, "android_camera", Manifest.permission.CAMERA);
        putPermission(result, context, "headset_camera", "horizonos.permission.HEADSET_CAMERA");
        putPermission(result, context, "spatial_camera", "horizonos.permission.SPATIAL_CAMERA");
        result.put("android_camera_granted", context != null
                && context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
        return result;
    }

    private static void putPermission(
            JSONObject target,
            Context context,
            String key,
            String permission) throws Exception {
        JSONObject entry = new JSONObject();
        entry.put("permission", permission);
        entry.put("granted", context != null
                && context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED);
        target.put(key, entry);
    }

    private static String normalizeSourceKind(String value) {
        String normalized = cleanOptional(value).toLowerCase(Locale.US);
        if ("camera2".equals(normalized) || "camera2_surface".equals(normalized)
                || "quest-camera2".equals(normalized) || "android-phone-camera2".equals(normalized)) {
            return SOURCE_CAMERA2_MEDIACODEC_SURFACE;
        }
        if ("synthetic".equals(normalized) || "synthetic_surface".equals(normalized)
                || "diagnostic_synthetic".equals(normalized)) {
            return SOURCE_DIAGNOSTIC_SYNTHETIC_SURFACE;
        }
        if ("display_composite_mediaprojection".equals(normalized)
                || "display_composite_mediaprojection_h264".equals(normalized)
                || "mediaprojection".equals(normalized)
                || "android_mediaprojection".equals(normalized)
                || "android_mediaprojection_surface".equals(normalized)) {
            return SOURCE_DISPLAY_COMPOSITE_MEDIAPROJECTION_SURFACE;
        }
        if ("shell_display_mirror".equals(normalized)
                || "shell_display_mirror_h264".equals(normalized)
                || "shell_hidden_display".equals(normalized)
                || "hidden_display_shell".equals(normalized)
                || "scrcpy_display_manager".equals(normalized)) {
            return SOURCE_SHELL_DISPLAY_MIRROR_SURFACE;
        }
        if (normalized.length() == 0 || "none".equals(normalized)) {
            return SOURCE_EXTERNAL_H264_SOCKET;
        }
        return normalized;
    }

    private static String cleanOptional(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return "none".equalsIgnoreCase(trimmed) ? "" : trimmed;
    }

    private static List<SourcePort> parsePorts(String value, Map<String, MediaProfile> profiles) {
        List<SourcePort> ports = new ArrayList<>();
        if (value == null || value.trim().length() == 0 || "none".equals(value.trim())) {
            return ports;
        }
        String[] entries = value.split(",");
        for (String entry : entries) {
            String[] parts = entry.trim().split(":");
            if (parts.length != 2) {
                continue;
            }
            try {
                int port = Integer.parseInt(parts[1].trim());
                if (port > 0 && port <= 65535) {
                    String eye = parts[0].trim();
                    MediaProfile profile = profiles.containsKey(eye)
                            ? profiles.get(eye)
                            : MediaProfile.defaultFor(eye);
                    ports.add(new SourcePort(eye, port, profile));
                }
            } catch (NumberFormatException ignored) {
                // Invalid source port properties are surfaced as missing source lanes.
            }
        }
        return ports;
    }

    private static Map<String, String> parseCameraIds(String value) {
        Map<String, String> bindings = new LinkedHashMap<>();
        if (value == null || value.trim().length() == 0 || "none".equals(value.trim())) {
            return bindings;
        }
        String[] entries = value.split(",");
        for (String entry : entries) {
            String[] parts = entry.trim().split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            String eye = parts[0].trim();
            String cameraId = cleanOptional(parts[1]);
            if (eye.length() > 0 && cameraId.length() > 0) {
                bindings.put(eye, cameraId);
            }
        }
        return bindings;
    }

    private static String formatSourcePorts(List<SourcePort> ports) {
        StringBuilder builder = new StringBuilder();
        for (SourcePort port : ports) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(port.eye).append(':').append(port.port);
        }
        return builder.toString();
    }

    private static Map<String, MediaProfile> parseProfiles(String value) {
        Map<String, MediaProfile> profiles = new LinkedHashMap<>();
        if (value == null || value.trim().length() == 0 || "none".equals(value.trim())) {
            return profiles;
        }
        String[] entries = value.split(";");
        for (String entry : entries) {
            String[] parts = entry.trim().split("\\|");
            if (parts.length == 8) {
                try {
                    MediaProfile profile = new MediaProfile(
                            parts[0].trim(),
                            parts[1].trim(),
                            Integer.parseInt(parts[2].trim()),
                            Integer.parseInt(parts[3].trim()),
                            Integer.parseInt(parts[4].trim()),
                            Integer.parseInt(parts[5].trim()),
                            parts[6].trim(),
                            parts[7].trim());
                    profiles.put(profile.eye, profile);
                } catch (NumberFormatException ignored) {
                    // Invalid media profiles fall back to deterministic diagnostic defaults.
                }
                continue;
            }
            MediaProfile compact = parseCompactProfile(entry.trim());
            if (compact != null) {
                profiles.put(compact.eye, compact);
            }
        }
        return profiles;
    }

    private static MediaProfile parseCompactProfile(String entry) {
        String[] first = entry.split(":", 2);
        if (first.length != 2) {
            return null;
        }
        String eye = first[0].trim();
        String[] bitrateParts = first[1].split(":", 2);
        if (bitrateParts.length != 2) {
            return null;
        }
        String[] frameParts = bitrateParts[0].split("@", 2);
        if (frameParts.length != 2) {
            return null;
        }
        String[] sizeParts = frameParts[0].split("x", 2);
        if (sizeParts.length != 2) {
            return null;
        }
        try {
            return new MediaProfile(
                    "remote-camera-" + eye,
                    eye,
                    Integer.parseInt(sizeParts[0].trim()),
                    Integer.parseInt(sizeParts[1].trim()),
                    Integer.parseInt(frameParts[1].trim()),
                    Integer.parseInt(bitrateParts[1].trim()),
                    "diagnostic-h264-packet-stream",
                    "camera2");
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void drawSyntheticFrame(Surface surface, int frameIndex, MediaProfile profile) throws Exception {
        Canvas canvas = surface.lockCanvas(null);
        try {
            Paint paint = new Paint();
            paint.setAntiAlias(false);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawColor(Color.rgb(8, 8, 8));

            int barHeight = Math.max(profile.height / 10, 24);
            int[] colors = new int[] {
                    Color.WHITE,
                    Color.YELLOW,
                    Color.CYAN,
                    Color.GREEN,
                    Color.MAGENTA,
                    Color.RED,
                    Color.BLUE,
                    Color.BLACK
            };
            int barWidth = Math.max(profile.width / colors.length, 1);
            for (int i = 0; i < colors.length; i++) {
                paint.setColor(colors[i]);
                int right = i == colors.length - 1 ? profile.width : (i + 1) * barWidth;
                canvas.drawRect(new Rect(i * barWidth, 0, right, barHeight), paint);
            }

            int cell = Math.max(Math.min(profile.width, profile.height) / 12, 16);
            int top = barHeight + 16;
            int y = top;
            while (y < profile.height) {
                int x = 0;
                while (x < profile.width) {
                    boolean high = (((x / cell) + ((y - top) / cell) + frameIndex) & 1) == 0;
                    paint.setColor(high ? Color.rgb(224, 224, 224) : Color.rgb(32, 32, 32));
                    canvas.drawRect(new Rect(x, y, Math.min(profile.width, x + cell), Math.min(profile.height, y + cell)), paint);
                    x += cell;
                }
                y += cell;
            }

            int marker = Math.max(Math.min(profile.width, profile.height) / 8, 48);
            paint.setColor("right".equals(profile.eye) ? Color.rgb(40, 90, 240) : Color.rgb(0, 180, 90));
            canvas.drawRect(new Rect(0, 0, marker, marker), paint);
            paint.setColor(Color.BLACK);
            paint.setTextSize(marker * 0.32f);
            canvas.drawText(profile.eye.toUpperCase(Locale.US), 8.0f, marker * 0.58f, paint);
        } finally {
            surface.unlockCanvasAndPost(canvas);
        }
    }

    private static void sleepUntilCadence(long frameStartNs, int frameRateHz) throws InterruptedException {
        long frameNs = 1_000_000_000L / Math.max(1, frameRateHz);
        long remainingNs = frameStartNs + frameNs - SystemClock.elapsedRealtimeNanos();
        if (remainingNs <= 0) {
            return;
        }
        long millis = remainingNs / 1_000_000L;
        int nanos = (int) (remainingNs % 1_000_000L);
        Thread.sleep(millis, nanos);
    }

    private static CameraSelection selectCamera(
            Context context,
            String requestedCameraId,
            String requestedFacing,
            MediaProfile requestedProfile,
            String qualityProfile) throws Exception {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("CameraManager is unavailable.");
        }
        String desiredFacing = normalizeFacing(requestedFacing);
        boolean nativeMax = "camera-native-max".equals(normalizeQuality(qualityProfile))
                || requestedProfile.width <= 0
                || requestedProfile.height <= 0;
        double requestedAspect = nativeMax ? 0.0
                : (double) requestedProfile.width / Math.max(1, requestedProfile.height);
        long requestedArea = nativeMax ? 0L : (long) requestedProfile.width * (long) requestedProfile.height;

        CameraSelection best = null;
        String[] cameraIds = manager.getCameraIdList();
        for (String cameraId : cameraIds) {
            if (requestedCameraId.length() > 0 && !requestedCameraId.equals(cameraId)) {
                continue;
            }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            String lensFacing = lensFacingLabel(characteristics.get(CameraCharacteristics.LENS_FACING));
            List<Size> sizes = outputSizesForEncoder(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));
            if (sizes.isEmpty()) {
                continue;
            }
            Size selectedSize = chooseSize(sizes, nativeMax, requestedAspect, requestedArea);
            Range<Integer> fpsRange = chooseFpsRange(characteristics, requestedProfile.frameRateHz);
            int facingPenalty = requestedCameraId.length() > 0 || lensFacing.equals(desiredFacing) ? 0 : 1;
            long area = (long) selectedSize.getWidth() * (long) selectedSize.getHeight();
            long sizeScore = nativeMax ? -area : Math.abs(area - requestedArea);
            long score = facingPenalty * 1_000_000_000_000L + sizeScore;
            String reason = requestedCameraId.length() > 0
                    ? "requested_camera_id"
                    : (facingPenalty == 0 ? "requested_facing_" + desiredFacing : "fallback_first_encoder_capable_camera");
            CameraSelection candidate = new CameraSelection(cameraId, lensFacing, selectedSize, fpsRange, reason, score);
            if (best == null || candidate.score < best.score) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalStateException(requestedCameraId.length() > 0
                    ? "Requested camera was not available for MediaCodec output."
                    : "No camera supports MediaCodec output surfaces.");
        }
        return best;
    }

    private static List<Size> outputSizesForEncoder(StreamConfigurationMap map) {
        List<Size> sizes = new ArrayList<>();
        if (map == null) {
            return sizes;
        }
        try {
            Size[] mediaCodecSizes = map.getOutputSizes(MediaCodec.class);
            if (mediaCodecSizes != null) {
                for (Size size : mediaCodecSizes) {
                    sizes.add(size);
                }
            }
        } catch (Exception ignored) {
            // Some camera stacks do not advertise MediaCodec class sizes.
        }
        if (!sizes.isEmpty()) {
            return sizes;
        }
        try {
            Size[] recorderSizes = map.getOutputSizes(MediaRecorder.class);
            if (recorderSizes != null) {
                for (Size size : recorderSizes) {
                    sizes.add(size);
                }
            }
        } catch (Exception ignored) {
            // Missing MediaRecorder sizes is handled by the caller.
        }
        return sizes;
    }

    private static Size chooseSize(List<Size> sizes, boolean nativeMax, double requestedAspect, long requestedArea) {
        Size selected = null;
        long bestScore = Long.MAX_VALUE;
        for (Size size : sizes) {
            long area = (long) size.getWidth() * (long) size.getHeight();
            long score;
            if (nativeMax) {
                score = -area;
            } else {
                double aspect = (double) size.getWidth() / Math.max(1, size.getHeight());
                long aspectScore = (long) (Math.abs(aspect - requestedAspect) * 1_000_000.0);
                score = aspectScore * 1_000_000_000L + Math.abs(area - requestedArea);
            }
            if (selected == null || score < bestScore) {
                selected = size;
                bestScore = score;
            }
        }
        return selected != null ? selected : sizes.get(0);
    }

    private static Range<Integer> chooseFpsRange(CameraCharacteristics characteristics, int frameRateHz) {
        Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) {
            return null;
        }
        int requested = Math.max(1, frameRateHz);
        Range<Integer> best = ranges[0];
        long bestScore = Long.MAX_VALUE;
        for (Range<Integer> range : ranges) {
            long score = (range.contains(requested) ? 0 : 1_000_000L)
                    + Math.abs(range.getUpper() - requested) * 1_000L
                    + Math.abs(range.getLower() - requested);
            if (score < bestScore) {
                best = range;
                bestScore = score;
            }
        }
        return best;
    }

    private static String lensFacingLabel(Integer facing) {
        if (facing == null) {
            return "unknown";
        }
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            return "front";
        }
        if (facing == CameraCharacteristics.LENS_FACING_BACK) {
            return "back";
        }
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
            return "external";
        }
        return "unknown";
    }

    private static String normalizeFacing(String value) {
        String facing = cleanOptional(value).toLowerCase(Locale.US);
        if ("front".equals(facing) || "back".equals(facing) || "external".equals(facing)) {
            return facing;
        }
        return "external";
    }

    private static String normalizeQuality(String value) {
        String quality = cleanOptional(value).toLowerCase(Locale.US);
        if (quality.length() == 0 || "native".equals(quality) || "max".equals(quality)
                || "camera_native_max".equals(quality) || "camera-native".equals(quality)) {
            return "camera-native-max";
        }
        return quality;
    }

    @SuppressLint("MissingPermission")
    private static CameraDevice openCamera(CameraManager manager, String cameraId, Handler handler) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final CameraDevice[] opened = new CameraDevice[1];
        final String[] errorMessage = new String[] { "" };
        manager.openCamera(
                cameraId,
                new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        opened[0] = camera;
                        latch.countDown();
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        errorMessage[0] = "Camera disconnected while opening.";
                        camera.close();
                        latch.countDown();
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        errorMessage[0] = "Camera open error " + error + ".";
                        camera.close();
                        latch.countDown();
                    }
                },
                handler);
        if (!latch.await(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Timed out opening camera.");
        }
        if (opened[0] == null) {
            throw new IllegalStateException(errorMessage[0].length() > 0 ? errorMessage[0] : "Camera did not open.");
        }
        return opened[0];
    }

    @SuppressWarnings("deprecation")
    private static CameraCaptureSession configureCameraSession(
            CameraDevice camera,
            List<Surface> surfaces,
            Handler handler) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final CameraCaptureSession[] configured = new CameraCaptureSession[1];
        final String[] errorMessage = new String[] { "" };
        camera.createCaptureSession(
                surfaces,
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        configured[0] = session;
                        latch.countDown();
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        errorMessage[0] = "Camera capture session configure failed.";
                        session.close();
                        latch.countDown();
                    }
                },
                handler);
        if (!latch.await(CAMERA_SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Timed out configuring camera capture session.");
        }
        if (configured[0] == null) {
            throw new IllegalStateException(errorMessage[0].length() > 0
                    ? errorMessage[0]
                    : "Camera capture session was not configured.");
        }
        return configured[0];
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Closing during stop is best effort.
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable != null ? throwable.getMessage() : "";
        return message != null ? message : "";
    }

    private static final class SourceGroup {
        final String groupKey;
        final String groupId;
        final String sessionId;
        final String sourceKind;
        final String host;
        final List<SourcePort> ports;
        final String cameraId;
        final String cameraFacing;
        final String qualityProfile;
        final Context context;
        final String permissionPolicy;
        final JSONObject permissionStatus;
        volatile String state = "created";
        volatile String closeReason = "";
        volatile String error = "";
        volatile boolean stopRequested;
        volatile boolean terminal;
        volatile Thread sourceThread;
        volatile CameraSelection cameraSelection;

        SourceGroup(
                String groupKey,
                String sessionId,
                String sourceKind,
                String host,
                List<SourcePort> ports,
                String cameraId,
                String cameraFacing,
                String qualityProfile,
                Context context,
                String permissionPolicy,
                JSONObject permissionStatus) {
            this.groupKey = groupKey;
            this.groupId = "remote-camera-source-" + NEXT_SOURCE_ID.getAndIncrement();
            this.sessionId = sessionId;
            this.sourceKind = sourceKind;
            this.host = host;
            this.ports = ports;
            this.cameraId = cameraId;
            this.cameraFacing = cameraFacing;
            this.qualityProfile = qualityProfile;
            this.context = context;
            this.permissionPolicy = permissionPolicy;
            this.permissionStatus = permissionStatus;
        }

        void openAndStart() throws Exception {
            if (SOURCE_CAMERA2_MEDIACODEC_SURFACE.equals(sourceKind)) {
                cameraSelection = selectCamera(context, cameraId, cameraFacing, firstProfile(), qualityProfile);
            }
            for (SourcePort port : ports) {
                port.open(host);
            }
            state = "source_sockets_bound";
            for (SourcePort port : ports) {
                port.startAcceptor(this);
            }
            sourceThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runSource();
                }
            }, "rusty-remote-camera-source-" + groupId);
            sourceThread.start();
        }

        void runSource() {
            try {
                waitForAnyOutput();
                if (stopRequested) {
                    markStopped("stop_requested");
                    return;
                }
                if (SOURCE_DIAGNOSTIC_SYNTHETIC_SURFACE.equals(sourceKind)) {
                    runSyntheticSource();
                } else if (SOURCE_CAMERA2_MEDIACODEC_SURFACE.equals(sourceKind)) {
                    runCamera2Source();
                } else {
                    markStopped("unsupported_source_kind");
                }
            } catch (Exception ex) {
                markFailed(ex);
            } finally {
                closePorts();
            }
        }

        void waitForAnyOutput() throws InterruptedException {
            state = "waiting_for_source_consumer";
            while (!stopRequested && connectedOutputCount() == 0) {
                Thread.sleep(25L);
            }
        }

        void runSyntheticSource() throws Exception {
            MediaProfile profile = firstProfile();
            MediaCodecSurfaceEncoder encoder = null;
            Surface surface = null;
            try {
                encoder = MediaCodecSurfaceEncoder.create(
                        new Size(profile.width, profile.height),
                        profile.bitrateBps,
                        profile.frameRateHz);
                surface = encoder.inputSurface();
                encoder.requestSyncFrame();
                state = "source_streaming_synthetic";
                int frameIndex = 0;
                while (!stopRequested) {
                    long frameStartNs = SystemClock.elapsedRealtimeNanos();
                    drawSyntheticFrame(surface, frameIndex, profile);
                    drainEncoder(encoder, false);
                    sleepUntilCadence(frameStartNs, profile.frameRateHz);
                    frameIndex++;
                }
                try {
                    encoder.signalEndOfInputStream();
                    drainEncoder(encoder, true);
                } catch (Exception ignored) {
                    // Encoder shutdown packets are best effort.
                }
                markStopped("stop_requested");
            } finally {
                closeQuietly(encoder);
            }
        }

        void runCamera2Source() throws Exception {
            MediaProfile baseProfile = firstProfile();
            CameraSelection selection = cameraSelection != null
                    ? cameraSelection
                    : selectCamera(context, cameraId, cameraFacing, baseProfile, qualityProfile);
            MediaCodecSurfaceEncoder encoder = null;
            Surface surface = null;
            HandlerThread cameraThread = null;
            CameraDevice cameraDevice = null;
            CameraCaptureSession cameraSession = null;
            try {
                encoder = MediaCodecSurfaceEncoder.create(
                        selection.size,
                        baseProfile.bitrateBps,
                        baseProfile.frameRateHz);
                surface = encoder.inputSurface();
                encoder.requestSyncFrame();

                cameraThread = new HandlerThread("rusty-remote-camera-camera2");
                cameraThread.start();
                Handler handler = new Handler(cameraThread.getLooper());
                CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                if (manager == null) {
                    throw new IllegalStateException("CameraManager is unavailable.");
                }
                cameraDevice = openCamera(manager, selection.cameraId, handler);
                List<Surface> surfaces = new ArrayList<>();
                surfaces.add(surface);
                cameraSession = configureCameraSession(cameraDevice, surfaces, handler);
                CaptureRequest.Builder request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                request.addTarget(surface);
                if (selection.fpsRange != null) {
                    request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selection.fpsRange);
                }
                cameraSession.setRepeatingRequest(request.build(), null, handler);
                state = "source_streaming_camera2";
                while (!stopRequested) {
                    drainEncoder(encoder, false);
                    Thread.sleep(5L);
                }
                try {
                    cameraSession.stopRepeating();
                } catch (Exception ignored) {
                    // Stop continues even if the capture session is already closed.
                }
                try {
                    encoder.signalEndOfInputStream();
                    drainEncoder(encoder, true);
                } catch (Exception ignored) {
                    // Encoder shutdown packets are best effort.
                }
                markStopped("stop_requested");
            } finally {
                if (cameraSession != null) {
                    cameraSession.close();
                }
                if (cameraDevice != null) {
                    cameraDevice.close();
                }
                if (cameraThread != null) {
                    cameraThread.quitSafely();
                }
                closeQuietly(encoder);
            }
        }

        void drainEncoder(MediaCodecSurfaceEncoder encoder, boolean endOfStream) throws Exception {
            encoder.drain(
                    endOfStream,
                    new MediaCodecSurfaceEncoder.StopSignal() {
                        @Override
                        public boolean stopRequested() {
                            return stopRequested;
                        }
                    },
                    new MediaCodecSurfaceEncoder.PacketSink() {
                        @Override
                        public void writePacket(long presentationTimeUs, int flags, byte[] payload)
                                throws Exception {
                            writePacketToOutputs(presentationTimeUs, flags, payload);
                        }
                    });
        }

        void writePacketToOutputs(long presentationTimeUs, int flags, byte[] payload) {
            long elapsedNs = SystemClock.elapsedRealtimeNanos();
            long unixNs = System.currentTimeMillis() * 1_000_000L;
            for (SourcePort port : ports) {
                port.writePacket(presentationTimeUs, flags, payload, elapsedNs, unixNs);
            }
        }

        MediaProfile firstProfile() {
            return ports.isEmpty() ? MediaProfile.defaultFor("mono") : ports.get(0).profileForHeader(this);
        }

        int connectedOutputCount() {
            int count = 0;
            for (SourcePort port : ports) {
                if (port.hasOutput()) {
                    count++;
                }
            }
            return count;
        }

        void stop(String reason) {
            stopRequested = true;
            closeReason = reason;
            state = "stopped";
            closePorts();
            Thread thread = sourceThread;
            if (thread != null) {
                thread.interrupt();
            }
            terminal = true;
        }

        void closePorts() {
            for (SourcePort port : ports) {
                port.close();
            }
        }

        void markStopped(String reason) {
            closeReason = reason;
            state = "stopped";
            terminal = true;
        }

        void markFailed(Exception ex) {
            if (stopRequested) {
                markStopped("stop_requested");
                return;
            }
            closeReason = "exception";
            error = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
            state = "failed";
            terminal = true;
        }

        boolean isTerminal() {
            return terminal || "stopped".equals(state) || "failed".equals(state);
        }

        JSONObject toJson() throws Exception {
            JSONObject json = baseResult(sessionId, sourceKind, state);
            json.put("source_group_id", groupId);
            json.put("source_available", !isTerminal() || "stopped".equals(state));
            json.put("runtime_started", true);
            json.put("host", host);
            json.put("camera_permission_policy", permissionPolicy);
            json.put("camera_permission_status", permissionStatus);
            json.put("connected_output_count", connectedOutputCount());
            if (cameraSelection != null) {
                json.put("camera_selection", cameraSelection.toJson());
            }
            JSONArray lanes = new JSONArray();
            for (SourcePort port : ports) {
                lanes.put(port.toJson());
            }
            json.put("source_lanes", lanes);
            if (closeReason.length() > 0) {
                json.put("close_reason", closeReason);
            }
            if (error.length() > 0) {
                json.put("error", error);
            }
            return json;
        }
    }

    private static final class SourcePort {
        final String eye;
        final int port;
        final MediaProfile profile;
        volatile ServerSocket serverSocket;
        volatile Socket socket;
        volatile OutputStream output;
        volatile Thread acceptorThread;
        volatile String state = "created";
        volatile String error = "";
        volatile long headerBytes;
        volatile long bytesWritten;
        volatile long packetCount;
        volatile long videoPacketCount;
        volatile long codecConfigPacketCount;

        SourcePort(String eye, int port, MediaProfile profile) {
            this.eye = eye;
            this.port = port;
            this.profile = profile;
        }

        void open(String host) throws Exception {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName(host), port));
            serverSocket = server;
            state = "source_socket_bound";
        }

        void startAcceptor(final SourceGroup group) {
            acceptorThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    accept(group);
                }
            }, "rusty-remote-camera-source-accept-" + eye);
            acceptorThread.start();
        }

        void accept(SourceGroup group) {
            Socket client = null;
            try {
                state = "waiting_for_source_consumer";
                client = serverSocket.accept();
                client.setTcpNoDelay(true);
                socket = client;
                output = client.getOutputStream();
                MediaProfile headerProfile = profileForHeader(group);
                headerBytes = H264MediaStreamWriter.writeStreamHeader(
                        output,
                        headerProfile.width,
                        headerProfile.height,
                        metadata(group, headerProfile));
                bytesWritten += headerBytes;
                state = "source_consumer_connected";
            } catch (Exception ex) {
                if (!group.stopRequested) {
                    state = "failed";
                    error = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
                }
                closeQuietly(client);
            }
        }

        MediaProfile profileForHeader(SourceGroup group) {
            CameraSelection selection = group.cameraSelection;
            if (selection == null) {
                return profile;
            }
            return profile.withSize(selection.size.getWidth(), selection.size.getHeight());
        }

        JSONObject metadata(SourceGroup group, MediaProfile headerProfile) throws Exception {
            JSONObject metadata = new JSONObject();
            metadata.put("schema", "rusty.quest.remote_camera.stream_metadata.v1");
            metadata.put("source", group.sourceKind);
            metadata.put("source_mode", group.sourceKind);
            metadata.put("eye", eye);
            metadata.put("lane_id", profile.laneId);
            metadata.put("source_family", profile.sourceFamily);
            metadata.put("projection_metadata_ready", false);
            metadata.put("content_width", headerProfile.width);
            metadata.put("content_height", headerProfile.height);
            metadata.put("stream_framing", profile.streamFraming);
            metadata.put("raster_orientation", "top-left-origin-y-down");
            if (group.cameraSelection != null) {
                metadata.put("camera_id", group.cameraSelection.cameraId);
                metadata.put("lens_facing", group.cameraSelection.lensFacing);
                metadata.put("selected_reason", group.cameraSelection.selectedReason);
            }
            return metadata;
        }

        boolean hasOutput() {
            return output != null && socket != null && !socket.isClosed();
        }

        void writePacket(long presentationTimeUs, int flags, byte[] payload, long elapsedNs, long unixNs) {
            OutputStream target = output;
            if (target == null) {
                return;
            }
            try {
                long bytes = H264MediaStreamWriter.writeEncodedPacket(
                        target,
                        presentationTimeUs,
                        flags,
                        payload,
                        elapsedNs,
                        unixNs);
                bytesWritten += bytes;
                packetCount++;
                if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    codecConfigPacketCount++;
                } else {
                    videoPacketCount++;
                }
                state = "source_streaming";
            } catch (Exception ex) {
                state = "source_consumer_closed";
                error = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
                close();
            }
        }

        void close() {
            closeQuietly(output);
            closeQuietly(socket);
            closeQuietly(serverSocket);
            Thread thread = acceptorThread;
            if (thread != null) {
                thread.interrupt();
            }
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("eye", eye);
            json.put("port", port);
            json.put("state", state);
            json.put("connected", hasOutput());
            json.put("header_bytes", headerBytes);
            json.put("bytes_written", bytesWritten);
            json.put("packet_count", packetCount);
            json.put("video_packet_count", videoPacketCount);
            json.put("codec_config_packet_count", codecConfigPacketCount);
            json.put("media_payload_plane", "binary-media");
            json.put("high_rate_json_payload", false);
            if (error.length() > 0) {
                json.put("error", error);
            }
            return json;
        }
    }

    private static final class MediaProfile {
        final String laneId;
        final String eye;
        final int width;
        final int height;
        final int frameRateHz;
        final int bitrateBps;
        final String streamFraming;
        final String sourceFamily;

        MediaProfile(
                String laneId,
                String eye,
                int width,
                int height,
                int frameRateHz,
                int bitrateBps,
                String streamFraming,
                String sourceFamily) {
            this.laneId = laneId.length() > 0 ? laneId : "remote-camera-" + eye;
            this.eye = eye.length() > 0 ? eye : "mono";
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.frameRateHz = Math.max(1, frameRateHz);
            this.bitrateBps = Math.max(1, bitrateBps);
            this.streamFraming = streamFraming.length() > 0 ? streamFraming : "diagnostic-h264-packet-stream";
            this.sourceFamily = sourceFamily.length() > 0 ? sourceFamily : "diagnostic-synthetic";
        }

        static MediaProfile defaultFor(String eye) {
            return new MediaProfile(
                    "remote-camera-" + eye,
                    eye,
                    720,
                    720,
                    30,
                    2_500_000,
                    "diagnostic-h264-packet-stream",
                    "diagnostic-synthetic");
        }

        MediaProfile withSize(int width, int height) {
            return new MediaProfile(
                    laneId,
                    eye,
                    width,
                    height,
                    frameRateHz,
                    bitrateBps,
                    streamFraming,
                    sourceFamily);
        }
    }

    private static final class CameraSelection {
        final String cameraId;
        final String lensFacing;
        final Size size;
        final Range<Integer> fpsRange;
        final String selectedReason;
        final long score;

        CameraSelection(
                String cameraId,
                String lensFacing,
                Size size,
                Range<Integer> fpsRange,
                String selectedReason,
                long score) {
            this.cameraId = cameraId;
            this.lensFacing = lensFacing;
            this.size = size;
            this.fpsRange = fpsRange;
            this.selectedReason = selectedReason;
            this.score = score;
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("camera_id", cameraId);
            json.put("lens_facing", lensFacing);
            json.put("width", size.getWidth());
            json.put("height", size.getHeight());
            json.put("selected_reason", selectedReason);
            if (fpsRange != null) {
                json.put("fps_range", fpsRange.toString());
            }
            return json;
        }
    }
}
