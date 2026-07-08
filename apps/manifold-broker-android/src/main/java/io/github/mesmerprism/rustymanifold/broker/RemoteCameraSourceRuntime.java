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
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
    private static final String CAMERA_PERMISSION_REQUIRED = "camera_permission_required";
    private static final String MIME_H264 = "video/avc";
    private static final String STREAM_MAGIC = "RMANVID1";
    private static final int STREAM_SCHEMA_VERSION = 1;
    private static final int CODEC_H264 = 1;
    private static final int ENCODER_DRAIN_TIMEOUT_US = 10_000;
    private static final long CAMERA_OPEN_TIMEOUT_MS = 5_000L;
    private static final long CAMERA_SESSION_TIMEOUT_MS = 5_000L;
    private static final long SYNC_FRAME_REQUEST_INTERVAL_MS = 1_000L;
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

    private static void configureEncoder(MediaCodec encoder, Size size, int bitrateBps, int frameRateHz) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_H264, size.getWidth(), size.getHeight());
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(1, bitrateBps));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, frameRateHz));
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        try {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
        } catch (Exception ignored) {
            // Some platform encoders reject optional tuning keys; the base H.264 surface profile is enough.
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private static void requestSyncFrame(MediaCodec encoder) {
        try {
            Bundle parameters = new Bundle();
            parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(parameters);
        } catch (Exception ignored) {
            // Sync-frame requests are best effort.
        }
    }

    private static void writeU32(OutputStream output, int value) throws IOException {
        output.write((value >>> 24) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static void writeU64(OutputStream output, long value) throws IOException {
        output.write((int) ((value >>> 56) & 0xFF));
        output.write((int) ((value >>> 48) & 0xFF));
        output.write((int) ((value >>> 40) & 0xFF));
        output.write((int) ((value >>> 32) & 0xFF));
        output.write((int) ((value >>> 24) & 0xFF));
        output.write((int) ((value >>> 16) & 0xFF));
        output.write((int) ((value >>> 8) & 0xFF));
        output.write((int) (value & 0xFF));
    }

    private static long writeStreamHeader(OutputStream output, MediaProfile profile, JSONObject metadata)
            throws IOException {
        byte[] metadataBytes = metadata.toString().getBytes(StandardCharsets.UTF_8);
        output.write(STREAM_MAGIC.getBytes(StandardCharsets.US_ASCII));
        writeU32(output, STREAM_SCHEMA_VERSION);
        writeU32(output, CODEC_H264);
        writeU32(output, profile.width);
        writeU32(output, profile.height);
        writeU32(output, 0);
        writeU32(output, metadataBytes.length);
        output.write(metadataBytes);
        output.flush();
        return 32L + metadataBytes.length;
    }

    private static long writeEncodedPacket(
            OutputStream output,
            long presentationTimeUs,
            int flags,
            byte[] payload,
            long sourceElapsedNs,
            long sourceUnixNs) throws IOException {
        writeU64(output, presentationTimeUs);
        writeU32(output, flags);
        writeU32(output, payload.length);
        writeU64(output, sourceElapsedNs);
        writeU64(output, sourceUnixNs);
        output.write(payload);
        output.flush();
        return 32L + payload.length;
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

    private static void releaseQuietly(Surface surface) {
        if (surface == null) {
            return;
        }
        try {
            surface.release();
        } catch (Exception ignored) {
            // Surface release during stop is best effort.
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable != null ? throwable.getMessage() : "";
        return message != null ? message : "";
    }

    private static final class Camera2FrameTracker {
        private long captureCount;
        private long firstFrameNumber = -1L;
        private long lastFrameNumber = -1L;
        private long firstSensorTimestampNs = -1L;
        private long lastSensorTimestampNs = -1L;
        private long firstCaptureElapsedMs = -1L;
        private long lastCaptureElapsedMs = -1L;
        private long lastCaptureUnixMs = -1L;

        synchronized void record(TotalCaptureResult result) {
            if (result == null) {
                return;
            }
            long frameNumber = result.getFrameNumber();
            Long sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            long nowElapsedMs = SystemClock.elapsedRealtime();
            long nowUnixMs = System.currentTimeMillis();
            captureCount++;
            if (firstFrameNumber < 0L) {
                firstFrameNumber = frameNumber;
                firstCaptureElapsedMs = nowElapsedMs;
            }
            lastFrameNumber = frameNumber;
            lastCaptureElapsedMs = nowElapsedMs;
            lastCaptureUnixMs = nowUnixMs;
            if (sensorTimestamp != null) {
                if (firstSensorTimestampNs < 0L) {
                    firstSensorTimestampNs = sensorTimestamp;
                }
                lastSensorTimestampNs = sensorTimestamp;
            }
        }

        synchronized JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            long nowElapsedMs = SystemClock.elapsedRealtime();
            json.put("capture_callback_count", captureCount);
            json.put("first_frame_number", firstFrameNumber);
            json.put("last_frame_number", lastFrameNumber);
            json.put(
                    "frame_number_delta",
                    firstFrameNumber >= 0L && lastFrameNumber >= firstFrameNumber
                            ? lastFrameNumber - firstFrameNumber
                            : -1L);
            json.put("first_sensor_timestamp_ns", firstSensorTimestampNs);
            json.put("last_sensor_timestamp_ns", lastSensorTimestampNs);
            json.put(
                    "sensor_timestamp_delta_ns",
                    firstSensorTimestampNs >= 0L && lastSensorTimestampNs >= firstSensorTimestampNs
                            ? lastSensorTimestampNs - firstSensorTimestampNs
                            : -1L);
            json.put("first_capture_elapsed_ms", firstCaptureElapsedMs);
            json.put("last_capture_elapsed_ms", lastCaptureElapsedMs);
            json.put("last_capture_unix_ms", lastCaptureUnixMs);
            json.put(
                    "last_capture_age_ms",
                    lastCaptureElapsedMs >= 0L
                            ? Math.max(0L, nowElapsedMs - lastCaptureElapsedMs)
                            : -1L);
            json.put("fresh_camera2_frames_observed", captureCount >= 2L && lastFrameNumber > firstFrameNumber);
            return json;
        }
    }

    private static final class EncodedPacketSnapshot {
        final long presentationTimeUs;
        final int flags;
        final byte[] payload;
        final long sourceElapsedNs;
        final long sourceUnixNs;

        EncodedPacketSnapshot(
                long presentationTimeUs,
                int flags,
                byte[] payload,
                long sourceElapsedNs,
                long sourceUnixNs) {
            this.presentationTimeUs = presentationTimeUs;
            this.flags = flags;
            this.payload = payload.clone();
            this.sourceElapsedNs = sourceElapsedNs;
            this.sourceUnixNs = sourceUnixNs;
        }
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
        final Camera2FrameTracker camera2FrameTracker = new Camera2FrameTracker();
        final Object codecConfigLock = new Object();
        final AtomicLong codecConfigCacheUpdateCount = new AtomicLong(0L);
        final AtomicLong codecConfigReplayCount = new AtomicLong(0L);
        final AtomicLong consumerSyncFrameRequestCount = new AtomicLong(0L);
        final AtomicLong consumerSyncFrameAppliedCount = new AtomicLong(0L);
        volatile EncodedPacketSnapshot cachedCodecConfigPacket;
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
            MediaCodec encoder = null;
            Surface surface = null;
            try {
                encoder = MediaCodec.createEncoderByType(MIME_H264);
                configureEncoder(encoder, new Size(profile.width, profile.height), profile.bitrateBps, profile.frameRateHz);
                surface = encoder.createInputSurface();
                encoder.start();
                requestSyncFrame(encoder);
                state = "source_streaming_synthetic";
                int frameIndex = 0;
                long nextSyncRequestMs = SystemClock.elapsedRealtime() + SYNC_FRAME_REQUEST_INTERVAL_MS;
                while (!stopRequested) {
                    long nowMs = SystemClock.elapsedRealtime();
                    if (requestPendingConsumerSyncFrame(encoder)) {
                        nextSyncRequestMs = nowMs + SYNC_FRAME_REQUEST_INTERVAL_MS;
                    } else if (nowMs >= nextSyncRequestMs) {
                        requestSyncFrame(encoder);
                        nextSyncRequestMs = nowMs + SYNC_FRAME_REQUEST_INTERVAL_MS;
                    }
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
                if (encoder != null) {
                    try {
                        encoder.stop();
                    } catch (Exception ignored) {
                        // Encoder may already be stopped after EOS.
                    }
                    encoder.release();
                }
                releaseQuietly(surface);
            }
        }

        void runCamera2Source() throws Exception {
            MediaProfile baseProfile = firstProfile();
            CameraSelection selection = cameraSelection != null
                    ? cameraSelection
                    : selectCamera(context, cameraId, cameraFacing, baseProfile, qualityProfile);
            MediaCodec encoder = null;
            Surface surface = null;
            HandlerThread cameraThread = null;
            CameraDevice cameraDevice = null;
            CameraCaptureSession cameraSession = null;
            try {
                encoder = MediaCodec.createEncoderByType(MIME_H264);
                configureEncoder(
                        encoder,
                        selection.size,
                        baseProfile.bitrateBps,
                        baseProfile.frameRateHz);
                surface = encoder.createInputSurface();
                encoder.start();
                requestSyncFrame(encoder);

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
                cameraSession.setRepeatingRequest(
                        request.build(),
                        new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(
                                    CameraCaptureSession session,
                                    CaptureRequest request,
                                    TotalCaptureResult result) {
                                camera2FrameTracker.record(result);
                            }
                        },
                        handler);
                state = "source_streaming_camera2";
                long nextSyncRequestMs = SystemClock.elapsedRealtime() + SYNC_FRAME_REQUEST_INTERVAL_MS;
                while (!stopRequested) {
                    long nowMs = SystemClock.elapsedRealtime();
                    if (requestPendingConsumerSyncFrame(encoder)) {
                        nextSyncRequestMs = nowMs + SYNC_FRAME_REQUEST_INTERVAL_MS;
                    } else if (nowMs >= nextSyncRequestMs) {
                        requestSyncFrame(encoder);
                        nextSyncRequestMs = nowMs + SYNC_FRAME_REQUEST_INTERVAL_MS;
                    }
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
                if (encoder != null) {
                    try {
                        encoder.stop();
                    } catch (Exception ignored) {
                        // Encoder may already be stopped after EOS.
                    }
                    encoder.release();
                }
                releaseQuietly(surface);
            }
        }

        void drainEncoder(MediaCodec encoder, boolean endOfStream) throws Exception {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int emptyPolls = 0;
            while (!stopRequested || endOfStream) {
                int status = encoder.dequeueOutputBuffer(info, ENCODER_DRAIN_TIMEOUT_US);
                if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream || emptyPolls++ > 50) {
                        break;
                    }
                    continue;
                }
                if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || status < 0) {
                    continue;
                }
                ByteBuffer outputBuffer = encoder.getOutputBuffer(status);
                if (outputBuffer != null && info.size > 0) {
                    byte[] payload = new byte[info.size];
                    outputBuffer.position(info.offset);
                    outputBuffer.limit(info.offset + info.size);
                    outputBuffer.get(payload);
                    long elapsedNs = SystemClock.elapsedRealtimeNanos();
                    long unixNs = System.currentTimeMillis() * 1_000_000L;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        cacheCodecConfig(info.presentationTimeUs, info.flags, payload, elapsedNs, unixNs);
                    }
                    writePacketToOutputs(info.presentationTimeUs, info.flags, payload, elapsedNs, unixNs);
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                encoder.releaseOutputBuffer(status, false);
                if (eos) {
                    break;
                }
            }
        }

        void writePacketToOutputs(
                long presentationTimeUs,
                int flags,
                byte[] payload,
                long elapsedNs,
                long unixNs) {
            for (SourcePort port : ports) {
                port.writePacket(presentationTimeUs, flags, payload, elapsedNs, unixNs);
            }
        }

        void cacheCodecConfig(
                long presentationTimeUs,
                int flags,
                byte[] payload,
                long elapsedNs,
                long unixNs) {
            synchronized (codecConfigLock) {
                cachedCodecConfigPacket = new EncodedPacketSnapshot(
                        presentationTimeUs,
                        flags,
                        payload,
                        elapsedNs,
                        unixNs);
                codecConfigCacheUpdateCount.incrementAndGet();
            }
        }

        EncodedPacketSnapshot cachedCodecConfigPacket() {
            synchronized (codecConfigLock) {
                return cachedCodecConfigPacket;
            }
        }

        void noteCodecConfigReplay() {
            codecConfigReplayCount.incrementAndGet();
        }

        void requestConsumerSyncFrame() {
            consumerSyncFrameRequestCount.incrementAndGet();
        }

        boolean requestPendingConsumerSyncFrame(MediaCodec encoder) {
            long requested = consumerSyncFrameRequestCount.get();
            long applied = consumerSyncFrameAppliedCount.get();
            if (requested <= applied) {
                return false;
            }
            requestSyncFrame(encoder);
            consumerSyncFrameAppliedCount.set(requested);
            return true;
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
            if (SOURCE_CAMERA2_MEDIACODEC_SURFACE.equals(sourceKind)) {
                json.put("camera2_capture_freshness", camera2FrameTracker.toJson());
            }
            json.put("codec_config_cache_ready", cachedCodecConfigPacket() != null);
            json.put("codec_config_cache_update_count", codecConfigCacheUpdateCount.get());
            json.put("codec_config_replay_count", codecConfigReplayCount.get());
            json.put("consumer_sync_frame_request_count", consumerSyncFrameRequestCount.get());
            json.put("consumer_sync_frame_applied_count", consumerSyncFrameAppliedCount.get());
            json.put(
                    "consumer_sync_frame_pending_count",
                    Math.max(0L, consumerSyncFrameRequestCount.get() - consumerSyncFrameAppliedCount.get()));
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
        volatile boolean streamReady;
        volatile Thread acceptorThread;
        volatile String state = "created";
        volatile String error = "";
        volatile long headerBytes;
        volatile long headerWriteCount;
        volatile long lastHeaderElapsedMs = -1L;
        volatile long consumerAcceptCount;
        volatile long consumerReconnectCount;
        volatile long bytesWritten;
        volatile long packetCount;
        volatile long videoPacketCount;
        volatile long codecConfigPacketCount;
        volatile long firstPacketElapsedMs = -1L;
        volatile long lastPacketElapsedMs = -1L;
        volatile long lastPacketUnixMs = -1L;

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
            while (!group.stopRequested) {
                Socket client = null;
                try {
                    state = "waiting_for_source_consumer";
                    client = serverSocket.accept();
                    if (group.stopRequested) {
                        closeQuietly(client);
                        break;
                    }
                    client.setTcpNoDelay(true);
                    OutputStream clientOutput = client.getOutputStream();
                    MediaProfile headerProfile = profileForHeader(group);
                    long writtenHeaderBytes = writeStreamHeader(
                            clientOutput,
                            headerProfile,
                            metadata(group, headerProfile));
                    headerBytes += writtenHeaderBytes;
                    headerWriteCount++;
                    lastHeaderElapsedMs = SystemClock.elapsedRealtime();
                    consumerAcceptCount++;
                    consumerReconnectCount = Math.max(0L, consumerAcceptCount - 1L);
                    bytesWritten += writtenHeaderBytes;
                    replayCachedCodecConfig(group, clientOutput);
                    group.requestConsumerSyncFrame();
                    socket = client;
                    output = clientOutput;
                    streamReady = true;
                    state = "source_consumer_connected";
                    while (!group.stopRequested && socket == client && !client.isClosed()) {
                        Thread.sleep(25L);
                    }
                } catch (Exception ex) {
                    if (!group.stopRequested) {
                        state = "failed";
                        error = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
                    }
                } finally {
                    if (socket == client) {
                        closeCurrentConsumer(client);
                    } else {
                        closeQuietly(client);
                    }
                }
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
            JSONArray sourceValidUvRect = new JSONArray();
            sourceValidUvRect.put(0.0);
            sourceValidUvRect.put(0.0);
            sourceValidUvRect.put(1.0);
            sourceValidUvRect.put(1.0);
            double aspectRatio = headerProfile.height > 0
                    ? (double) headerProfile.width / (double) headerProfile.height
                    : 1.0;
            metadata.put("schema", "rusty.quest.remote_camera.stream_metadata.v1");
            metadata.put("source", group.sourceKind);
            metadata.put("source_mode", group.sourceKind);
            metadata.put("eye", eye);
            metadata.put("lane_id", profile.laneId);
            metadata.put("source_family", profile.sourceFamily);
            metadata.put("projection_metadata_ready", true);
            metadata.put("projectionMetadataReady", true);
            metadata.put("projectionGeometryProfile", "full-frame-diagnostic");
            metadata.put("syntheticProjectionProfile", "full-frame-diagnostic");
            metadata.put("poseSource", "stream-header-full-frame-projection-contract");
            metadata.put("poseCoordinateConvention", "runtime-openxr-view");
            metadata.put("content_width", headerProfile.width);
            metadata.put("content_height", headerProfile.height);
            metadata.put("contentKind", "camera-frame");
            metadata.put("contentWidth", headerProfile.width);
            metadata.put("contentHeight", headerProfile.height);
            metadata.put("contentAspectRatio", aspectRatio);
            metadata.put("desiredDisplayAspectRatio", aspectRatio);
            metadata.put("desiredProjectionAspectRatio", aspectRatio);
            metadata.put("contentCoordinateSpace", "normalized-uv");
            metadata.put("contentOrigin", "top-left");
            metadata.put("contentXAxis", "right");
            metadata.put("contentYAxis", "down");
            metadata.put("contentMappingIntent", "map-full-frame-content-to-projection-area");
            metadata.put("sourceSamplingMode", "target-local-raster");
            metadata.put("contentGeometryMetadataSource", "stream-header");
            metadata.put("contentGeometryDefault", false);
            metadata.put("sourceValidUvRect", sourceValidUvRect);
            metadata.put("deliveredWidth", headerProfile.width);
            metadata.put("deliveredHeight", headerProfile.height);
            metadata.put("stream_framing", profile.streamFraming);
            metadata.put("raster_orientation", "top-left-origin-y-down");
            metadata.put("orientationKind", "standard-stimulus-default");
            metadata.put("rasterOrientation", "top-left-origin-y-down");
            metadata.put("uprightMarker", "unspecified");
            metadata.put("orientationMetadataSource", "stream-header");
            metadata.put("orientationDefault", false);
            metadata.put("stimulusRasterOrientation", "top-left-origin-y-down");
            metadata.put("stimulusUprightMarker", "unspecified");
            metadata.put("stimulusOrientationMetadataSource", "stream-header");
            metadata.put("stimulusOrientationDefault", false);
            if (group.cameraSelection != null) {
                metadata.put("camera_id", group.cameraSelection.cameraId);
                metadata.put("cameraId", group.cameraSelection.cameraId);
                metadata.put("lens_facing", group.cameraSelection.lensFacing);
                metadata.put("selected_reason", group.cameraSelection.selectedReason);
            }
            return metadata;
        }

        boolean hasOutput() {
            return streamReady && output != null && socket != null && !socket.isClosed();
        }

        void writePacket(long presentationTimeUs, int flags, byte[] payload, long elapsedNs, long unixNs) {
            Socket targetSocket = socket;
            OutputStream target = output;
            if (target == null || !streamReady) {
                return;
            }
            try {
                long bytes = writeEncodedPacket(target, presentationTimeUs, flags, payload, elapsedNs, unixNs);
                notePacketWrite(bytes, flags);
                state = "source_streaming";
            } catch (Exception ex) {
                state = "source_consumer_closed";
                error = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
                closeCurrentConsumer(targetSocket);
            }
        }

        void replayCachedCodecConfig(SourceGroup group, OutputStream clientOutput) throws IOException {
            EncodedPacketSnapshot snapshot = group.cachedCodecConfigPacket();
            if (snapshot == null) {
                return;
            }
            long bytes = writeEncodedPacket(
                    clientOutput,
                    snapshot.presentationTimeUs,
                    snapshot.flags,
                    snapshot.payload,
                    SystemClock.elapsedRealtimeNanos(),
                    System.currentTimeMillis() * 1_000_000L);
            notePacketWrite(bytes, snapshot.flags);
            group.noteCodecConfigReplay();
        }

        void notePacketWrite(long bytes, int flags) {
            bytesWritten += bytes;
            packetCount++;
            long nowElapsedMs = SystemClock.elapsedRealtime();
            if (firstPacketElapsedMs < 0L) {
                firstPacketElapsedMs = nowElapsedMs;
            }
            lastPacketElapsedMs = nowElapsedMs;
            lastPacketUnixMs = System.currentTimeMillis();
            if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                codecConfigPacketCount++;
            } else {
                videoPacketCount++;
            }
        }

        void closeCurrentConsumer(Socket expectedSocket) {
            if (expectedSocket != null && socket != expectedSocket) {
                closeQuietly(expectedSocket);
                return;
            }
            streamReady = false;
            OutputStream currentOutput = output;
            Socket currentSocket = socket;
            output = null;
            socket = null;
            closeQuietly(currentOutput);
            closeQuietly(currentSocket);
        }

        void close() {
            closeCurrentConsumer(socket);
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
            json.put("header_write_count", headerWriteCount);
            json.put("last_header_elapsed_ms", lastHeaderElapsedMs);
            json.put(
                    "last_header_age_ms",
                    lastHeaderElapsedMs >= 0L
                            ? Math.max(0L, SystemClock.elapsedRealtime() - lastHeaderElapsedMs)
                            : -1L);
            json.put("consumer_accept_count", consumerAcceptCount);
            json.put("consumer_reconnect_count", consumerReconnectCount);
            json.put("bytes_written", bytesWritten);
            json.put("packet_count", packetCount);
            json.put("video_packet_count", videoPacketCount);
            json.put("codec_config_packet_count", codecConfigPacketCount);
            json.put("first_packet_elapsed_ms", firstPacketElapsedMs);
            json.put("last_packet_elapsed_ms", lastPacketElapsedMs);
            json.put("last_packet_unix_ms", lastPacketUnixMs);
            json.put(
                    "last_packet_age_ms",
                    lastPacketElapsedMs >= 0L
                            ? Math.max(0L, SystemClock.elapsedRealtime() - lastPacketElapsedMs)
                            : -1L);
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
