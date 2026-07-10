package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;

/** RMANVID v4 packed-stereo header and fixed per-packet pair extension. */
final class RemoteCameraPackedStreamMetadata {
    static final int RMANVID_SCHEMA_VERSION = 4;
    static final int PAIR_EXTENSION_BYTES = 48;
    static final String MEDIA_LAYOUT = "side-by-side-left-right";
    static final String FRAME_LAYOUT = "side_by_side_left_right";
    static final String TIMESTAMP_AUTHORITY = "camera2_sensor_timestamp";
    static final String PAIRING_POLICY = "nearest_timestamp_bounded";

    static final class Layout {
        final int packedWidth;
        final int packedHeight;
        final int perEyeWidth;
        final int perEyeHeight;
        final long maxPairDeltaNs;

        Layout(
                int packedWidth,
                int packedHeight,
                int perEyeWidth,
                int perEyeHeight,
                long maxPairDeltaNs) {
            if (packedWidth != perEyeWidth * 2
                    || packedHeight != perEyeHeight
                    || perEyeWidth <= 0
                    || perEyeHeight <= 0
                    || maxPairDeltaNs <= 0L
                    || maxPairDeltaNs > 1_000_000_000L) {
                throw new IllegalArgumentException("invalid packed stereo frame layout");
            }
            this.packedWidth = packedWidth;
            this.packedHeight = packedHeight;
            this.perEyeWidth = perEyeWidth;
            this.perEyeHeight = perEyeHeight;
            this.maxPairDeltaNs = maxPairDeltaNs;
        }

        static Layout parse(String mediaLayout, String compact) {
            if (!MEDIA_LAYOUT.equals(clean(mediaLayout))) {
                throw new IllegalArgumentException("packed media layout is not explicitly enabled");
            }
            String[] fields = clean(compact).split("\\|");
            if (fields.length != 8
                    || !"sbs-lr".equals(fields[0])
                    || !"c2sensor".equals(fields[3])
                    || !"nearest".equals(fields[4])
                    || !"gpu".equals(fields[6])
                    || !"nostale".equals(fields[7])) {
                throw new IllegalArgumentException("unsupported packed frame layout property");
            }
            int[] packed = parseSize(fields[1]);
            int[] perEye = parseSize(fields[2]);
            long maxDelta = Long.parseLong(fields[5]);
            return new Layout(packed[0], packed[1], perEye[0], perEye[1], maxDelta);
        }

        JSONObject toHeaderJson(String leftCameraId, String rightCameraId, String sourceKind)
                throws Exception {
            if (clean(leftCameraId).isEmpty()
                    || clean(rightCameraId).isEmpty()
                    || leftCameraId.equals(rightCameraId)) {
                throw new IllegalArgumentException("packed stream requires distinct camera ids");
            }
            JSONObject metadata = new JSONObject();
            metadata.put("schema", "rusty.quest.remote_camera.packed_stereo_stream_metadata.v1");
            metadata.put("rmanvid_schema_version", RMANVID_SCHEMA_VERSION);
            metadata.put("frame_layout", FRAME_LAYOUT);
            JSONArray eyeOrder = new JSONArray();
            eyeOrder.put("left");
            eyeOrder.put("right");
            metadata.put("eye_order", eyeOrder);
            metadata.put("packed_width", packedWidth);
            metadata.put("packed_height", packedHeight);
            metadata.put("per_eye_width", perEyeWidth);
            metadata.put("per_eye_height", perEyeHeight);
            metadata.put("left_camera_id", leftCameraId);
            metadata.put("right_camera_id", rightCameraId);
            metadata.put("pair_timestamp_source", TIMESTAMP_AUTHORITY);
            metadata.put("pairing_policy", PAIRING_POLICY);
            metadata.put("max_pair_delta_ns", maxPairDeltaNs);
            metadata.put("cpu_pixel_copy", false);
            metadata.put("gpu_compositor_active", true);
            metadata.put("source", sourceKind);
            metadata.put("source_mode", sourceKind);
            metadata.put("eye", "stereo");
            metadata.put("projection_metadata_ready", true);
            metadata.put("projectionMetadataReady", true);
            metadata.put("contentWidth", packedWidth);
            metadata.put("contentHeight", packedHeight);
            metadata.put("deliveredWidth", packedWidth);
            metadata.put("deliveredHeight", packedHeight);
            metadata.put("contentOrigin", "top-left");
            metadata.put("raster_orientation", "top-left-origin-y-down");
            metadata.put("high_rate_json_payload", false);
            return metadata;
        }
    }

    static final class PairRecord {
        static final PairRecord CODEC_CONFIG = new PairRecord(0L, 0L, 0L, 0L, 0L, 0L);

        final long pairId;
        final long leftSourceFrame;
        final long rightSourceFrame;
        final long leftSensorTimestampNs;
        final long rightSensorTimestampNs;
        final long pairDeltaNs;

        PairRecord(
                long pairId,
                long leftSourceFrame,
                long rightSourceFrame,
                long leftSensorTimestampNs,
                long rightSensorTimestampNs,
                long pairDeltaNs) {
            this.pairId = pairId;
            this.leftSourceFrame = leftSourceFrame;
            this.rightSourceFrame = rightSourceFrame;
            this.leftSensorTimestampNs = leftSensorTimestampNs;
            this.rightSensorTimestampNs = rightSensorTimestampNs;
            this.pairDeltaNs = pairDeltaNs;
        }

        static PairRecord fromPair(RemoteCameraStereoFramePairer.Pair pair) {
            return new PairRecord(
                    pair.pairId,
                    pair.left.sourceFrame,
                    pair.right.sourceFrame,
                    pair.left.sensorTimestampNs,
                    pair.right.sensorTimestampNs,
                    pair.pairDeltaNs);
        }

        void validate(boolean codecConfig, long maxPairDeltaNs) {
            if (codecConfig) {
                if (pairId != 0L
                        || leftSourceFrame != 0L
                        || rightSourceFrame != 0L
                        || leftSensorTimestampNs != 0L
                        || rightSensorTimestampNs != 0L
                        || pairDeltaNs != 0L) {
                    throw new IllegalArgumentException("codec config pair extension must be zero");
                }
                return;
            }
            long measured = absoluteDelta(leftSensorTimestampNs, rightSensorTimestampNs);
            if (pairId <= 0L
                    || leftSourceFrame <= 0L
                    || rightSourceFrame <= 0L
                    || leftSensorTimestampNs <= 0L
                    || rightSensorTimestampNs <= 0L
                    || pairDeltaNs != measured
                    || pairDeltaNs > maxPairDeltaNs) {
                throw new IllegalArgumentException("invalid packed video pair extension");
            }
        }

        void write(OutputStream output) throws IOException {
            writeU64(output, pairId);
            writeU64(output, leftSourceFrame);
            writeU64(output, rightSourceFrame);
            writeU64(output, leftSensorTimestampNs);
            writeU64(output, rightSensorTimestampNs);
            writeU64(output, pairDeltaNs);
        }
    }

    private RemoteCameraPackedStreamMetadata() {
    }

    private static int[] parseSize(String value) {
        String[] fields = clean(value).split("x");
        if (fields.length != 2) {
            throw new IllegalArgumentException("invalid packed size");
        }
        return new int[] {Integer.parseInt(fields[0]), Integer.parseInt(fields[1])};
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static long absoluteDelta(long left, long right) {
        return left >= right ? left - right : right - left;
    }

    private static void writeU64(OutputStream output, long value) throws IOException {
        for (int shift = 56; shift >= 0; shift -= 8) {
            output.write((int) ((value >>> shift) & 0xFF));
        }
    }
}
