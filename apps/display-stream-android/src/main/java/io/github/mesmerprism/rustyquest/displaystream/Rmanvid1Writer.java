package io.github.mesmerprism.rustyquest.displaystream;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.channels.GatheringByteChannel;

final class Rmanvid1Writer {
    static final String MAGIC = "RMANVID1";
    private static final int SCHEMA_VERSION = 1;
    private static final int CODEC_H264 = 1;
    private static final int STREAM_HEADER_BYTES = 32;
    private static final int PACKET_HEADER_BYTES = 32;

    private final GatheringByteChannel channel;
    private final ByteBuffer packetHeader =
        ByteBuffer.allocateDirect(PACKET_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);

    Rmanvid1Writer(GatheringByteChannel channel) {
        this.channel = channel;
    }

    void writeHeader(int width, int height, JSONObject metadata)
            throws IOException {
        byte[] metadataBytes = metadata.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer header = ByteBuffer.allocate(STREAM_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        header.put(MAGIC.getBytes(StandardCharsets.US_ASCII));
        header.putInt(SCHEMA_VERSION);
        header.putInt(CODEC_H264);
        header.putInt(width);
        header.putInt(height);
        header.putInt(0);
        header.putInt(metadataBytes.length);
        header.flip();
        writeFully(header, ByteBuffer.wrap(metadataBytes));
    }

    void writePacket(
            long presentationTimeUs,
            int flags,
            ByteBuffer payload,
            long sourceElapsedNs,
            long sourceUnixNs) throws IOException {
        packetHeader.clear();
        packetHeader.putLong(presentationTimeUs);
        packetHeader.putInt(flags);
        packetHeader.putInt(payload.remaining());
        packetHeader.putLong(sourceElapsedNs);
        packetHeader.putLong(sourceUnixNs);
        packetHeader.flip();
        writeFully(packetHeader, payload);
    }

    private void writeFully(ByteBuffer... buffers) throws IOException {
        long remaining = 0;
        for (ByteBuffer buffer : buffers) {
            remaining += buffer.remaining();
        }
        while (remaining > 0) {
            long written = channel.write(buffers);
            if (written < 0) {
                throw new IOException("RMANVID1 socket closed while writing");
            }
            if (written == 0) {
                Thread.yield();
                continue;
            }
            remaining -= written;
        }
    }
}
