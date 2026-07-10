package io.github.mesmerprism.rustyquest.spatial_camera_panel;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** One-socket RMANVID v4 packed-stereo adapter for the opt-in Spatial video route. */
final class SpatialPackedStereoBrokerPlayback {
    private static final String LOG_TAG = "RQSpatialCamera";
    private static final String MAGIC = "RMANVID1";
    private static final String MEDIA_LAYOUT = "side-by-side-left-right";
    private static final String FRAME_LAYOUT = "side_by_side_left_right";
    private static final String TIMESTAMP_SOURCE = "camera2_sensor_timestamp";
    private static final String PAIRING_POLICY = "nearest_timestamp_bounded";
    private static final int SCHEMA_VERSION = 4;
    private static final int CODEC_H264 = 1;
    private static final int MAX_METADATA_BYTES = 256 * 1024;
    private static final int MAX_PACKET_BYTES = 1024 * 1024;
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    private SpatialPackedStereoBrokerPlayback() {}

    static void run(
        String host,
        int port,
        int connectTimeoutMs,
        String mediaLayout,
        Surface surface,
        int requestedWidth,
        int requestedHeight,
        int maxImages,
        int fpsCap
    ) throws IOException {
        if (!MEDIA_LAYOUT.equals(mediaLayout) || port <= 0 || port > 65535) {
            throw new IOException("Spatial packed broker requires explicit SBS layout and one port");
        }
        Socket socket = new Socket();
        MediaCodec codec = null;
        try {
            socket.connect(
                new InetSocketAddress(normalizeHost(host), port),
                clamp(connectTimeoutMs, 100, 60000)
            );
            socket.setSoTimeout(1000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            Header header = Header.read(input, requestedWidth, requestedHeight);
            codec = createHardwareDecoder(surface, header.width, header.height);
            PairSequence sequence = new PairSequence();
            TreeMap<Long, PairRecord> queuedPairs = new TreeMap<>();
            MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
            long queuedPackets = 0L;
            long renderedFrames = 0L;
            Log.i(LOG_TAG, String.format(
                Locale.US,
                "RUSTY_QUEST_SPATIAL_CAMERA_PANEL channel=spatial-packed-broker status=started magic=RMANVID1 schema=4 brokerMediaLayout=side-by-side-left-right brokerHost=%s brokerPort=%d packedSocketCount=1 decoderInstanceCount=1 nativeImageReaderCount=1 width=%d height=%d maxImages=%d fpsCap=%d cpuPixelCopy=false",
                normalizeHost(host), port, header.width, header.height, maxImages, fpsCap));
            while (!SpatialStereoVideoPlayback.isStopRequested()) {
                Packet packet;
                try {
                    packet = Packet.read(input, header.maxPairDeltaNs);
                } catch (SocketTimeoutException timeout) {
                    renderedFrames += drain(codec, outputInfo, queuedPairs);
                    continue;
                } catch (EOFException eof) {
                    break;
                }
                sequence.validate(packet.pair, packet.codecConfig, header.maxPairDeltaNs);
                int inputIndex;
                do {
                    inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    renderedFrames += drain(codec, outputInfo, queuedPairs);
                } while (inputIndex < 0 && !SpatialStereoVideoPlayback.isStopRequested());
                if (inputIndex < 0) {
                    break;
                }
                ByteBuffer buffer = codec.getInputBuffer(inputIndex);
                if (buffer == null || packet.payload.length > buffer.capacity()) {
                    throw new IOException("Spatial packed decoder input buffer unavailable or too small");
                }
                buffer.clear();
                buffer.put(packet.payload);
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    packet.payload.length,
                    packet.ptsUs,
                    packet.flags
                );
                if (!packet.codecConfig) {
                    queuedPairs.put(packet.ptsUs, packet.pair);
                    while (queuedPairs.size() > 128) {
                        queuedPairs.pollFirstEntry();
                    }
                }
                queuedPackets++;
                renderedFrames += drain(codec, outputInfo, queuedPairs);
                if (renderedFrames == 1L || renderedFrames % 60L == 0L) {
                    Log.i(LOG_TAG, String.format(
                        Locale.US,
                        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL channel=spatial-packed-broker status=frame renderedFrames=%d queuedPackets=%d brokerPort=%d packedStereo=true nativeImageReaderCount=1 cpuPixelCopy=false",
                        renderedFrames, queuedPackets, port));
                }
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                codec.release();
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static long drain(
        MediaCodec codec,
        MediaCodec.BufferInfo info,
        TreeMap<Long, PairRecord> queuedPairs
    ) throws IOException {
        long rendered = 0L;
        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(info, 0L);
            if (outputIndex < 0) {
                return rendered;
            }
            boolean render = info.size != 0;
            if (render) {
                PairRecord pair = queuedPairs.remove(info.presentationTimeUs);
                if (pair == null) {
                    Map.Entry<Long, PairRecord> floor = queuedPairs.floorEntry(info.presentationTimeUs);
                    if (floor != null) {
                        pair = floor.getValue();
                        queuedPairs.remove(floor.getKey());
                    }
                }
                if (pair == null) {
                    throw new IOException("Spatial packed decoder output lacks pair metadata");
                }
                nativeSetPackedStereoPairMetadata(
                    pair.pairId,
                    pair.leftSourceFrame,
                    pair.rightSourceFrame,
                    pair.leftSensorTimestampNs,
                    pair.rightSensorTimestampNs,
                    pair.pairDeltaNs
                );
            }
            codec.releaseOutputBuffer(outputIndex, render);
            if (render) {
                rendered++;
            }
        }
    }

    private static MediaCodec createHardwareDecoder(Surface surface, int width, int height)
        throws IOException {
        String decoderName = null;
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : codecList.getCodecInfos()) {
            if (info.isEncoder() || isSoftware(info)) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) {
                    decoderName = info.getName();
                    break;
                }
            }
            if (decoderName != null) {
                break;
            }
        }
        if (decoderName == null) {
            throw new IOException("Spatial packed stereo requires a hardware H.264 decoder");
        }
        MediaCodec codec = MediaCodec.createByCodecName(decoderName);
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_PACKET_BYTES);
        codec.configure(format, surface, null, 0);
        codec.start();
        Log.i(LOG_TAG, String.format(
            Locale.US,
            "RUSTY_QUEST_SPATIAL_CAMERA_PANEL channel=spatial-packed-broker status=decoder-created decoderName=%s decoderSoftware=false decoderSelection=hardware-required",
            decoderName));
        return codec;
    }

    private static boolean isSoftware(MediaCodecInfo info) {
        try {
            if (info.isSoftwareOnly()) {
                return true;
            }
        } catch (NoSuchMethodError ignored) {
        }
        String name = info.getName().toLowerCase(Locale.US);
        return name.startsWith("c2.android.")
            || name.startsWith("omx.google.")
            || name.contains("google")
            || name.contains("software");
    }

    private static String normalizeHost(String host) {
        String value = host == null ? "" : host.trim();
        return value.isEmpty() ? "127.0.0.1" : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Header {
        final int width;
        final int height;
        final long maxPairDeltaNs;

        Header(int width, int height, long maxPairDeltaNs) {
            this.width = width;
            this.height = height;
            this.maxPairDeltaNs = maxPairDeltaNs;
        }

        static Header read(DataInputStream input, int requestedWidth, int requestedHeight)
            throws IOException {
            byte[] magicBytes = new byte[8];
            input.readFully(magicBytes);
            String magic = new String(magicBytes, StandardCharsets.US_ASCII);
            int version = input.readInt();
            int codec = input.readInt();
            int width = input.readInt();
            int height = input.readInt();
            int packetCount = input.readInt();
            int metadataBytes = input.readInt();
            if (!MAGIC.equals(magic)
                    || version != SCHEMA_VERSION
                    || codec != CODEC_H264
                    || packetCount < 0
                    || metadataBytes <= 0
                    || metadataBytes > MAX_METADATA_BYTES) {
                throw new IOException("invalid Spatial packed RMANVID v4 header");
            }
            byte[] metadata = new byte[metadataBytes];
            input.readFully(metadata);
            try {
                JSONObject json = new JSONObject(new String(metadata, StandardCharsets.UTF_8));
                JSONArray eyeOrder = json.getJSONArray("eye_order");
                long maxDelta = json.getLong("max_pair_delta_ns");
                boolean valid =
                    "rusty.quest.remote_camera.packed_stereo_stream_metadata.v1".equals(json.getString("schema"))
                    && json.getInt("rmanvid_schema_version") == SCHEMA_VERSION
                    && FRAME_LAYOUT.equals(json.getString("frame_layout"))
                    && eyeOrder.length() == 2
                    && "left".equals(eyeOrder.getString(0))
                    && "right".equals(eyeOrder.getString(1))
                    && json.getInt("packed_width") == width
                    && json.getInt("packed_height") == height
                    && json.getInt("per_eye_width") * 2 == width
                    && json.getInt("per_eye_height") == height
                    && !json.getString("left_camera_id").equals(json.getString("right_camera_id"))
                    && TIMESTAMP_SOURCE.equals(json.getString("pair_timestamp_source"))
                    && PAIRING_POLICY.equals(json.getString("pairing_policy"))
                    && maxDelta > 0L
                    && !json.getBoolean("cpu_pixel_copy")
                    && json.getBoolean("gpu_compositor_active")
                    && !json.optBoolean("high_rate_json_payload", true)
                    && width == requestedWidth
                    && height == requestedHeight;
                if (!valid) {
                    throw new IOException("Spatial packed metadata disagrees with active layout");
                }
                return new Header(width, height, maxDelta);
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("malformed Spatial packed metadata", error);
            }
        }
    }

    private static final class Packet {
        final long ptsUs;
        final int flags;
        final byte[] payload;
        final PairRecord pair;
        final boolean codecConfig;

        Packet(long ptsUs, int flags, byte[] payload, PairRecord pair, boolean codecConfig) {
            this.ptsUs = ptsUs;
            this.flags = flags;
            this.payload = payload;
            this.pair = pair;
            this.codecConfig = codecConfig;
        }

        static Packet read(DataInputStream input, long maxPairDeltaNs) throws IOException {
            long ptsUs = input.readLong();
            int flags = input.readInt();
            int size = input.readInt();
            if (size < 0 || size > MAX_PACKET_BYTES) {
                throw new IOException("Spatial packed RMANVID packet size out of range");
            }
            input.readLong();
            input.readLong();
            PairRecord pair = PairRecord.read(input);
            boolean codecConfig = (flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            pair.validate(codecConfig, maxPairDeltaNs);
            byte[] payload = new byte[size];
            input.readFully(payload);
            return new Packet(ptsUs, flags, payload, pair, codecConfig);
        }
    }

    private static final class PairRecord {
        final long pairId;
        final long leftSourceFrame;
        final long rightSourceFrame;
        final long leftSensorTimestampNs;
        final long rightSensorTimestampNs;
        final long pairDeltaNs;

        PairRecord(long pairId, long leftSourceFrame, long rightSourceFrame,
                   long leftSensorTimestampNs, long rightSensorTimestampNs, long pairDeltaNs) {
            this.pairId = pairId;
            this.leftSourceFrame = leftSourceFrame;
            this.rightSourceFrame = rightSourceFrame;
            this.leftSensorTimestampNs = leftSensorTimestampNs;
            this.rightSensorTimestampNs = rightSensorTimestampNs;
            this.pairDeltaNs = pairDeltaNs;
        }

        static PairRecord read(DataInputStream input) throws IOException {
            return new PairRecord(
                input.readLong(), input.readLong(), input.readLong(),
                input.readLong(), input.readLong(), input.readLong());
        }

        void validate(boolean codecConfig, long maxDeltaNs) throws IOException {
            if (codecConfig) {
                if (pairId != 0L || leftSourceFrame != 0L || rightSourceFrame != 0L
                        || leftSensorTimestampNs != 0L || rightSensorTimestampNs != 0L
                        || pairDeltaNs != 0L) {
                    throw new IOException("Spatial codec-config pair metadata must be zero");
                }
                return;
            }
            long measured = leftSensorTimestampNs >= rightSensorTimestampNs
                ? leftSensorTimestampNs - rightSensorTimestampNs
                : rightSensorTimestampNs - leftSensorTimestampNs;
            if (pairId <= 0L || leftSourceFrame <= 0L || rightSourceFrame <= 0L
                    || leftSensorTimestampNs <= 0L || rightSensorTimestampNs <= 0L
                    || pairDeltaNs != measured || pairDeltaNs > maxDeltaNs) {
                throw new IOException("invalid Spatial packed pair metadata");
            }
        }
    }

    private static final class PairSequence {
        long pairId;
        long leftFrame;
        long rightFrame;

        void validate(PairRecord pair, boolean codecConfig, long maxDeltaNs) throws IOException {
            pair.validate(codecConfig, maxDeltaNs);
            if (codecConfig) {
                return;
            }
            if (pair.pairId <= pairId
                    || pair.leftSourceFrame <= leftFrame
                    || pair.rightSourceFrame <= rightFrame) {
                throw new IOException("Spatial packed pair/source frame reuse or regression");
            }
            pairId = pair.pairId;
            leftFrame = pair.leftSourceFrame;
            rightFrame = pair.rightSourceFrame;
        }
    }

    private static native void nativeSetPackedStereoPairMetadata(
        long pairId,
        long leftSourceFrame,
        long rightSourceFrame,
        long leftSensorTimestampNs,
        long rightSensorTimestampNs,
        long pairDeltaNs
    );
}
