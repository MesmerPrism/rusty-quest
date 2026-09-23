package io.github.mesmerprism.rustyquest.media;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded, fail-closed RMANVID v4 packed-stereo parser with no Android dependency. */
final class RmanvidPacketReader {
    static final int FLAG_KEY_FRAME = 1;
    static final int FLAG_CODEC_CONFIG = 2;
    private static final String MAGIC = "RMANVID1";
    private static final int CODEC_H264 = 1;

    private final DataInputStream input;
    private final int maxHeaderBytes;
    private final int maxPacketBytes;
    private final int maxWidth;
    private final int maxHeight;
    private Header header;
    private long lastPairId;
    private long lastLeftFrame;
    private long lastRightFrame;
    private long lastVideoPtsUs = -1L;

    RmanvidPacketReader(DataInputStream input, int maxHeaderBytes, int maxPacketBytes,
            int maxWidth, int maxHeight) {
        if (input == null) throw new NullPointerException("input");
        if (maxHeaderBytes <= 0 || maxHeaderBytes > 1024 * 1024
                || maxPacketBytes <= 0 || maxPacketBytes > 32 * 1024 * 1024
                || maxWidth <= 0 || maxHeight <= 0 || maxWidth > 16384 || maxHeight > 16384) {
            throw new IllegalArgumentException("invalid RMANVID bounds");
        }
        this.input = input;
        this.maxHeaderBytes = maxHeaderBytes;
        this.maxPacketBytes = maxPacketBytes;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
    }

    Header readHeader() throws IOException {
        if (header != null) throw new IOException("RMANVID header already read");
        byte[] magicBytes = new byte[8];
        input.readFully(magicBytes);
        String magic = new String(magicBytes, StandardCharsets.US_ASCII);
        int schemaVersion = input.readInt();
        int codec = input.readInt();
        int width = input.readInt();
        int height = input.readInt();
        int packetCount = input.readInt();
        int metadataBytes = input.readInt();
        if (!MAGIC.equals(magic) || schemaVersion != 4 || codec != CODEC_H264
                || width <= 0 || height <= 0 || width > maxWidth || height > maxHeight
                || packetCount != 0 || metadataBytes <= 0 || metadataBytes > maxHeaderBytes) {
            throw new IOException("unsupported or out-of-bounds RMANVID v4 header");
        }
        byte[] metadata = new byte[metadataBytes];
        input.readFully(metadata);
        String json = decodeUtf8(metadata);
        header = Header.parse(json, width, height);
        return header;
    }

    Packet readPacket() throws IOException {
        if (header == null) throw new IOException("RMANVID header not read");
        long ptsUs = input.readLong();
        int flags = input.readInt();
        int size = input.readInt();
        if (ptsUs < 0L || flags < 0 || (flags & ~0x0f) != 0
                || size <= 0 || size > maxPacketBytes) {
            throw new IOException("invalid or out-of-bounds RMANVID packet");
        }
        long sourceElapsedNs = input.readLong();
        long sourceUnixNs = input.readLong();
        Pair pair = new Pair(input.readLong(), input.readLong(), input.readLong(),
                input.readLong(), input.readLong(), input.readLong());
        boolean codecConfig = (flags & FLAG_CODEC_CONFIG) != 0;
        pair.validate(codecConfig, header.maxPairDeltaNs);
        if (!codecConfig) {
            if (ptsUs <= lastVideoPtsUs || sourceElapsedNs <= 0L || sourceUnixNs <= 0L
                    || pair.pairId <= lastPairId || pair.leftSourceFrame <= lastLeftFrame
                    || pair.rightSourceFrame <= lastRightFrame) {
                throw new IOException("RMANVID timestamp or pair identity was duplicated or reordered");
            }
            lastVideoPtsUs = ptsUs;
            lastPairId = pair.pairId;
            lastLeftFrame = pair.leftSourceFrame;
            lastRightFrame = pair.rightSourceFrame;
        }
        byte[] payload = new byte[size];
        input.readFully(payload);
        return new Packet(ptsUs, flags, sourceElapsedNs, sourceUnixNs, pair, payload);
    }

    private static String decodeUtf8(byte[] value) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("RMANVID metadata is not valid UTF-8", invalid);
        }
    }

    static final class Header {
        final int width;
        final int height;
        final int perEyeWidth;
        final int perEyeHeight;
        final String leftCameraId;
        final String rightCameraId;
        final long maxPairDeltaNs;

        Header(int width, int height, int perEyeWidth, int perEyeHeight,
                String leftCameraId, String rightCameraId, long maxPairDeltaNs) {
            this.width = width;
            this.height = height;
            this.perEyeWidth = perEyeWidth;
            this.perEyeHeight = perEyeHeight;
            this.leftCameraId = leftCameraId;
            this.rightCameraId = rightCameraId;
            this.maxPairDeltaNs = maxPairDeltaNs;
        }

        static Header parse(String json, int width, int height) throws IOException {
            try {
                JSONObject value = new JSONObject(json);
                JSONArray eyes = value.optJSONArray("eye_order");
                int packedWidth = value.optInt("packed_width", 0);
                int packedHeight = value.optInt("packed_height", 0);
                int perEyeWidth = value.optInt("per_eye_width", 0);
                int perEyeHeight = value.optInt("per_eye_height", 0);
                String left = value.optString("left_camera_id", "");
                String right = value.optString("right_camera_id", "");
                long maxDelta = value.optLong("max_pair_delta_ns", 0L);
                boolean valid = "rusty.quest.remote_camera.packed_stereo_stream_metadata.v1"
                        .equals(value.optString("schema", ""))
                        && value.optInt("rmanvid_schema_version", 0) == 4
                        && PackedStereoStreamMetadata.FRAME_LAYOUT.equals(
                                value.optString("frame_layout", ""))
                        && eyes != null && eyes.length() == 2
                        && "left".equals(eyes.optString(0)) && "right".equals(eyes.optString(1))
                        && packedWidth == width && packedHeight == height
                        && packedWidth % 2 == 0 && perEyeWidth == packedWidth / 2
                        && packedHeight == perEyeHeight
                        && perEyeWidth > 0 && perEyeHeight > 0
                        && !left.isEmpty() && !right.isEmpty() && !left.equals(right)
                        && "camera2_sensor_timestamp".equals(
                                value.optString("pair_timestamp_source", ""))
                        && "nearest_timestamp_bounded".equals(
                                value.optString("pairing_policy", ""))
                        && maxDelta > 0L && maxDelta <= 1_000_000_000L
                        && !value.optBoolean("cpu_pixel_copy", true)
                        && value.optBoolean("gpu_compositor_active", false)
                        && !value.optBoolean("high_rate_json_payload", true);
                if (!valid) throw new IOException("RMANVID packed metadata failed closed");
                return new Header(width, height, perEyeWidth, perEyeHeight, left, right, maxDelta);
            } catch (JSONException invalid) {
                throw new IOException("malformed RMANVID metadata", invalid);
            }
        }
    }

    static final class Packet {
        final long ptsUs;
        final int flags;
        final long sourceElapsedNs;
        final long sourceUnixNs;
        final Pair pair;
        final byte[] payload;

        Packet(long ptsUs, int flags, long sourceElapsedNs, long sourceUnixNs,
                Pair pair, byte[] payload) {
            this.ptsUs = ptsUs;
            this.flags = flags;
            this.sourceElapsedNs = sourceElapsedNs;
            this.sourceUnixNs = sourceUnixNs;
            this.pair = pair;
            this.payload = payload;
        }
    }

    static final class Pair {
        final long pairId;
        final long leftSourceFrame;
        final long rightSourceFrame;
        final long leftSensorTimestampNs;
        final long rightSensorTimestampNs;
        final long pairDeltaNs;

        Pair(long pairId, long leftSourceFrame, long rightSourceFrame,
                long leftSensorTimestampNs, long rightSensorTimestampNs, long pairDeltaNs) {
            this.pairId = pairId;
            this.leftSourceFrame = leftSourceFrame;
            this.rightSourceFrame = rightSourceFrame;
            this.leftSensorTimestampNs = leftSensorTimestampNs;
            this.rightSensorTimestampNs = rightSensorTimestampNs;
            this.pairDeltaNs = pairDeltaNs;
        }

        void validate(boolean codecConfig, long maxDeltaNs) throws IOException {
            if (codecConfig) {
                if (pairId != 0L || leftSourceFrame != 0L || rightSourceFrame != 0L
                        || leftSensorTimestampNs != 0L || rightSensorTimestampNs != 0L
                        || pairDeltaNs != 0L) {
                    throw new IOException("RMANVID codec config carried frame identity");
                }
                return;
            }
            long measured = leftSensorTimestampNs >= rightSensorTimestampNs
                    ? leftSensorTimestampNs - rightSensorTimestampNs
                    : rightSensorTimestampNs - leftSensorTimestampNs;
            if (pairId <= 0L || leftSourceFrame <= 0L || rightSourceFrame <= 0L
                    || leftSensorTimestampNs <= 0L || rightSensorTimestampNs <= 0L
                    || pairDeltaNs != measured || pairDeltaNs > maxDeltaNs) {
                throw new IOException("invalid RMANVID packed pair identity");
            }
        }
    }
}
