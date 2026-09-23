package io.github.mesmerprism.rustyquest.media;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Adversarial host checks for bounded writer and exact frame ownership primitives. */
public final class MediaPrimitiveAdversarialMain {
    public static void main(String[] args) throws Exception {
        blockedWriterClosesAndTerminates();
        queueAndByteLimitsRejectWithoutRetainingCallerBuffer();
        trackerReleasesRejectedAndOutOfOrderExpiryExactlyOnce();
        rmanvidBoundsAndIdentityFailClosed();
        System.out.println("rusty.quest.android.media.primitive-adversarial.v1:pass");
    }

    private static void blockedWriterClosesAndTerminates() throws Exception {
        BlockingOutputStream output = new BlockingOutputStream();
        BoundedPacketPump pump = new BoundedPacketPump(output, 2, 8, 16,
                "blocked-writer-test");
        require(pump.offer(new byte[] {1}), "initial packet rejected");
        require(output.writeEntered.await(2, TimeUnit.SECONDS), "writer never blocked");
        long startedNs = System.nanoTime();
        pump.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
        require(elapsedMs < 1500L, "close waited for its full timeout");
        require(output.closeCalls.get() == 1, "output was not closed exactly once");
        require(pump.isTerminated(), "blocked writer survived close");
        require(pump.failure() == null, "orderly close reported a write failure");
        require(!pump.offer(new byte[] {2}), "closed pump accepted a packet");
    }

    private static void queueAndByteLimitsRejectWithoutRetainingCallerBuffer() throws Exception {
        BlockingOutputStream output = new BlockingOutputStream();
        BoundedPacketPump pump = new BoundedPacketPump(output, 1, 4, 4,
                "queue-overflow-test");
        byte[] first = new byte[] {1};
        require(pump.offer(first), "first packet rejected");
        first[0] = 9;
        require(output.writeEntered.await(2, TimeUnit.SECONDS), "first packet was not dequeued");
        require(pump.offer(new byte[] {2, 3, 4, 5}), "bounded queued packet rejected");
        require(!pump.offer(new byte[] {6}), "full queue accepted another packet");
        require(!pump.offer(new byte[] {1, 2, 3, 4, 5}), "oversize packet accepted");
        pump.close();
        require(output.firstByte == 1, "pump retained the caller's mutable packet");

        BlockingOutputStream byteOutput = new BlockingOutputStream();
        BoundedPacketPump bytePump = new BoundedPacketPump(byteOutput, 2, 4, 4,
                "queued-byte-limit-test");
        require(bytePump.offer(new byte[] {1}), "byte-limit seed packet rejected");
        require(byteOutput.writeEntered.await(2, TimeUnit.SECONDS),
                "byte-limit seed packet was not dequeued");
        require(bytePump.offer(new byte[] {2, 3, 4, 5}), "byte-limit packet rejected");
        require(!bytePump.offer(new byte[] {6}), "queued byte limit accepted another packet");
        bytePump.close();
    }

    private static void trackerReleasesRejectedAndOutOfOrderExpiryExactlyOnce() {
        ExactPresentationTracker tracker = new ExactPresentationTracker(2);
        AtomicInteger release20 = new AtomicInteger();
        AtomicInteger release10 = new AtomicInteger();
        StereoFrameLease twenty = lease(20, release20);
        StereoFrameLease ten = lease(10, release10);
        tracker.submit(twenty);
        tracker.submit(ten);
        require(tracker.expireBefore(15) == 1, "out-of-order earlier PTS was not expired");
        require(release10.get() == 1 && release20.get() == 0,
                "expiry released the wrong lease");
        StereoFrameLease matched = tracker.take(20);
        matched.close();
        matched.close();
        require(release20.get() == 1, "matched lease was not released exactly once");

        AtomicInteger retainedRelease = new AtomicInteger();
        AtomicInteger duplicateRelease = new AtomicInteger();
        tracker.submit(lease(30, retainedRelease));
        expectFailure(new Runnable() {
            @Override public void run() { tracker.submit(lease(30, duplicateRelease)); }
        });
        require(duplicateRelease.get() == 1, "rejected duplicate lease was not closed");
        AtomicInteger secondRetainedRelease = new AtomicInteger();
        AtomicInteger fullRelease = new AtomicInteger();
        tracker.submit(lease(31, secondRetainedRelease));
        expectFailure(new Runnable() {
            @Override public void run() { tracker.submit(lease(32, fullRelease)); }
        });
        require(fullRelease.get() == 1, "full tracker retained an incoming lease");
        tracker.close();
        tracker.close();
        require(retainedRelease.get() == 1 && secondRetainedRelease.get() == 1,
                "retained leases were not closed exactly once");

        AtomicInteger afterCloseRelease = new AtomicInteger();
        expectFailure(new Runnable() {
            @Override public void run() { tracker.submit(lease(40, afterCloseRelease)); }
        });
        require(afterCloseRelease.get() == 1, "closed tracker retained an incoming lease");
    }

    private static void rmanvidBoundsAndIdentityFailClosed() throws Exception {
        byte[] valid = stream(1280, 720, 4096, true);
        RmanvidPacketReader reader = reader(valid);
        RmanvidPacketReader.Header header = reader.readHeader();
        require(header.width == 1280 && header.perEyeWidth == 640,
                "valid packed header dimensions changed");
        RmanvidPacketReader.Packet packet = reader.readPacket();
        require(packet.ptsUs == 55L && packet.pair.pairId == 1L
                        && packet.pair.leftSourceFrame == 10L,
                "valid packet identity changed");
        expectIo(new IoCall() {
            @Override public void run() throws Exception { reader.readPacket(); }
        });

        ByteArrayOutputStream oversizedBytes = new ByteArrayOutputStream();
        DataOutputStream oversized = new DataOutputStream(oversizedBytes);
        writeHeaderPrefix(oversized, 1280, 720, 4097);
        RmanvidPacketReader oversizedReader = reader(oversizedBytes.toByteArray());
        expectIo(new IoCall() {
            @Override public void run() throws Exception { oversizedReader.readHeader(); }
        });

        RmanvidPacketReader duplicateReader = reader(stream(1280, 720, 4096, false));
        duplicateReader.readHeader();
        duplicateReader.readPacket();
        expectIo(new IoCall() {
            @Override public void run() throws Exception { duplicateReader.readPacket(); }
        });
    }

    private static RmanvidPacketReader reader(byte[] bytes) {
        return new RmanvidPacketReader(new DataInputStream(new ByteArrayInputStream(bytes)),
                4096, 1024, 4096, 4096);
    }

    private static byte[] stream(int width, int height, int metadataBound,
            boolean onePacket) throws Exception {
        String metadata = "{\"schema\":\"rusty.quest.remote_camera.packed_stereo_stream_metadata.v1\","+
                "\"rmanvid_schema_version\":4,\"frame_layout\":\"side_by_side_left_right\","+
                "\"eye_order\":[\"left\",\"right\"],\"packed_width\":"+width+","+
                "\"packed_height\":"+height+",\"per_eye_width\":"+(width/2)+","+
                "\"per_eye_height\":"+height+",\"left_camera_id\":\"left.1\","+
                "\"right_camera_id\":\"right.1\",\"pair_timestamp_source\":"+
                "\"camera2_sensor_timestamp\",\"pairing_policy\":\"nearest_timestamp_bounded\","+
                "\"max_pair_delta_ns\":10,\"cpu_pixel_copy\":false,"+
                "\"gpu_compositor_active\":true,\"high_rate_json_payload\":false}";
        byte[] metadataBytes = metadata.getBytes("UTF-8");
        require(metadataBytes.length <= metadataBound, "test metadata exceeds declared bound");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        writeHeaderPrefix(output, width, height, metadataBytes.length);
        output.write(metadataBytes);
        writePacket(output);
        if (!onePacket) writePacket(output);
        return bytes.toByteArray();
    }

    private static void writeHeaderPrefix(DataOutputStream output, int width, int height,
            int metadataBytes) throws Exception {
        output.write("RMANVID1".getBytes("US-ASCII"));
        output.writeInt(4);
        output.writeInt(1);
        output.writeInt(width);
        output.writeInt(height);
        output.writeInt(0);
        output.writeInt(metadataBytes);
    }

    private static void writePacket(DataOutputStream output) throws Exception {
        output.writeLong(55L);
        output.writeInt(RmanvidPacketReader.FLAG_KEY_FRAME);
        output.writeInt(1);
        output.writeLong(200L);
        output.writeLong(300L);
        output.writeLong(1L);
        output.writeLong(10L);
        output.writeLong(11L);
        output.writeLong(100L);
        output.writeLong(101L);
        output.writeLong(1L);
        output.writeByte(7);
    }

    private static void expectIo(IoCall call) {
        try {
            call.run();
            throw new AssertionError("expected IOException");
        } catch (IOException expected) {
            // Expected.
        } catch (Exception unexpected) {
            throw new AssertionError("unexpected failure", unexpected);
        }
    }

    private static StereoFrameLease lease(long ptsUs, AtomicInteger releases) {
        return new StereoFrameLease(new StereoFrameIdentity(1, ptsUs, ptsUs * 1000,
                ptsUs * 1000 + 1, ptsUs), new Runnable() {
                    @Override public void run() { releases.incrementAndGet(); }
                });
    }

    private static void expectFailure(Runnable call) {
        try {
            call.run();
            throw new AssertionError("expected failure");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class BlockingOutputStream extends OutputStream {
        private final Object lock = new Object();
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final AtomicInteger closeCalls = new AtomicInteger();
        volatile int firstByte = -1;
        private boolean closed;

        @Override public void write(int value) throws IOException {
            write(new byte[] {(byte) value});
        }

        @Override public void write(byte[] value, int offset, int length) throws IOException {
            synchronized (lock) {
                if (firstByte < 0 && length > 0) firstByte = value[offset] & 0xff;
                writeEntered.countDown();
                while (!closed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", interrupted);
                    }
                }
            }
        }

        @Override public void close() {
            if (closeCalls.incrementAndGet() != 1) {
                throw new AssertionError("output closed more than once");
            }
            synchronized (lock) {
                closed = true;
                lock.notifyAll();
            }
        }
    }

    private interface IoCall { void run() throws Exception; }
}
