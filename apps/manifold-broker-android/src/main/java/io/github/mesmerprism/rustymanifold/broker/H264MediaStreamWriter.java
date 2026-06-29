package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class H264MediaStreamWriter {
    static final String STREAM_MAGIC = "RMANVID1";
    private static final int STREAM_SCHEMA_VERSION = 1;
    private static final int CODEC_H264 = 1;

    private H264MediaStreamWriter() {
    }

    static long writeStreamHeader(
            OutputStream output,
            int width,
            int height,
            JSONObject metadata) throws IOException {
        byte[] metadataBytes = metadata.toString().getBytes(StandardCharsets.UTF_8);
        output.write(STREAM_MAGIC.getBytes(StandardCharsets.US_ASCII));
        writeU32(output, STREAM_SCHEMA_VERSION);
        writeU32(output, CODEC_H264);
        writeU32(output, width);
        writeU32(output, height);
        writeU32(output, 0);
        writeU32(output, metadataBytes.length);
        output.write(metadataBytes);
        output.flush();
        return 32L + metadataBytes.length;
    }

    static long writeEncodedPacket(
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
}
