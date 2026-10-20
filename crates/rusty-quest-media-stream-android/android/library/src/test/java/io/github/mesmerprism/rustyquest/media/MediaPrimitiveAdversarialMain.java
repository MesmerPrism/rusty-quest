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
        retainedPumpCloseControls();
        warningRetainsActualWriter();
        blockedWriterClosesAndTerminates();
        queueAndByteLimitsRejectWithoutRetainingCallerBuffer();
        trackerReleasesRejectedAndOutOfOrderExpiryExactlyOnce();
        rmanvidBoundsAndIdentityFailClosed();
        System.out.println("rusty.quest.android.media.primitive-adversarial.v1:pass");
    }

    private static Thread pumpWorker(BoundedPacketPump pump) throws Exception {
        java.lang.reflect.Field field=BoundedPacketPump.class.getDeclaredField("worker");field.setAccessible(true);
        return (Thread)field.get(pump);
    }
    private static void retainedPumpCloseControls() throws Exception {
        for (int mode=0;mode<4;mode++) {
            GatedOutputStream output=new GatedOutputStream(mode==3);
            BoundedPacketPump original=new BoundedPacketPump(output,1,4,4,"retained-close-control");
            Thread worker=pumpWorker(original);AtomicInteger warnings=new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<Throwable> invalid=new java.util.concurrent.atomic.AtomicReference<>();
            final int selected=mode;
            try {
                require(original.offer(new byte[]{1}),"actual packet rejected");output.entered.await();
                original.close(0,()->{
                    if(original.isTerminated() || output.closeCalls.get()!=1)invalid.set(new AssertionError("warning fabricated terminal/close"));
                    warnings.incrementAndGet();output.release.countDown();
                    if(selected==2)Thread.currentThread().interrupt();
                    if(selected==1)throw new IllegalStateException("deliberate warning publisher failure after release");
                });
                boolean interrupted=Thread.interrupted();
                require(warnings.get()==1 && invalid.get()==null && original.isTerminated() && !worker.isAlive(),"same writer not actually joined");
                require(interrupted==(mode==2),"actual interruption was not preserved after joining");
                require(original.failure()==(mode==3?output.closeFailure:null),"real output close failure was lost/invented");
            } finally { output.release.countDown();original.close();worker.join();Thread.interrupted(); }
        }
        GatedOutputStream failed=new GatedOutputStream(false);failed.failWrite=true;
        BoundedPacketPump original=new BoundedPacketPump(failed,1,4,4,"actual-write-failure");
        require(original.offer(new byte[]{1}),"failure packet rejected");failed.entered.await();failed.release.countDown();pumpWorker(original).join();
        require(original.failure()==failed.writeFailure,"actual writer failure was not retained");original.close();
        require(original.isTerminated() && original.failure()==failed.writeFailure && failed.closeCalls.get()==1,"close changed real failure/custody");
        java.util.concurrent.atomic.AtomicReference<BoundedPacketPump> self=new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger closes=new AtomicInteger();CountDownLatch returned=new CountDownLatch(1);
        BoundedPacketPump selfOwned=new BoundedPacketPump(new OutputStream(){
            public void write(int value){self.get().close(0,()->{throw new AssertionError("self worker must not join/warn itself");});returned.countDown();}
            public void close(){closes.incrementAndGet();}
        },1,4,4,"self-close-guard");self.set(selfOwned);
        require(selfOwned.offer(new byte[]{1}),"self packet rejected");returned.await();pumpWorker(selfOwned).join();selfOwned.close();
        require(selfOwned.isTerminated() && closes.get()==1,"self-close invented terminal or repeated output close");
    }
    private static final class GatedOutputStream extends OutputStream {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger closeCalls=new AtomicInteger();
        final IOException closeFailure=new IOException("actual modeled close failure"),writeFailure=new IOException("actual modeled writer failure");
        final boolean failClose;boolean failWrite;
        GatedOutputStream(boolean failed){failClose=failed;}
        public void write(int value)throws IOException {
            entered.countDown();try{release.await();}catch(InterruptedException actual){Thread.currentThread().interrupt();throw new IOException(actual);}
            if(failWrite)throw writeFailure;
        }
        public void close()throws IOException {closeCalls.incrementAndGet();if(failClose)throw closeFailure;}
    }

    private static void warningRetainsActualWriter() throws Exception {
        BlockingOutputStream output = new BlockingOutputStream();
        BoundedPacketPump original = new BoundedPacketPump(output, 1, 4, 4, "warning-owned-writer");
        long priorEstimate = estimateMilliseconds;
        java.util.function.Consumer<String> priorWarning = warning;
        AtomicInteger publications = new AtomicInteger();
        try {
            estimateMilliseconds = 0;
            warning = message -> {
                publications.incrementAndGet();
                require(original.offer(new byte[] {7}), "warning could not release actual writer");
                throw new IllegalStateException("deliberate warning sink failure after release");
            };
            awaitWriter(output.writeEntered, original);
            require(publications.get() == 1 && output.firstByte == 7 && !original.isTerminated(),
                    "warning must retain the same live writer until real entry");
        } finally { warning = priorWarning; estimateMilliseconds = priorEstimate; original.close(); }
        require(original.isTerminated() && output.closeCalls.get() == 1, "original writer not actually joined");
    }

    private static void blockedWriterClosesAndTerminates() throws Exception {
        BlockingOutputStream output = new BlockingOutputStream();
        BoundedPacketPump pump = new BoundedPacketPump(output, 2, 8, 16,
                "blocked-writer-test");
        try {
        require(pump.offer(new byte[] {1}), "initial packet rejected");
        awaitWriter(output.writeEntered, pump);
        long startedNs = System.nanoTime();
        pump.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
        if (elapsedMs >= 1500L) warn("pump close exceeded its estimate; actual termination still required");
        require(output.closeCalls.get() == 1, "output was not closed exactly once");
        require(pump.isTerminated(), "blocked writer survived close");
        require(pump.failure() == null, "orderly close reported a write failure");
        require(!pump.offer(new byte[] {2}), "closed pump accepted a packet");
        } finally { pump.close(); }
    }

    private static void queueAndByteLimitsRejectWithoutRetainingCallerBuffer() throws Exception {
        BlockingOutputStream output = new BlockingOutputStream();
        BoundedPacketPump pump = new BoundedPacketPump(output, 1, 4, 4,
                "queue-overflow-test");
        try {
        byte[] first = new byte[] {1};
        require(pump.offer(first), "first packet rejected");
        first[0] = 9;
        awaitWriter(output.writeEntered, pump);
        require(pump.offer(new byte[] {2, 3, 4, 5}), "bounded queued packet rejected");
        require(!pump.offer(new byte[] {6}), "full queue accepted another packet");
        require(!pump.offer(new byte[] {1, 2, 3, 4, 5}), "oversize packet accepted");
        pump.close();
        require(output.firstByte == 1, "pump retained the caller's mutable packet");
        } finally { pump.close(); }

        BlockingOutputStream byteOutput = new BlockingOutputStream();
        BoundedPacketPump bytePump = new BoundedPacketPump(byteOutput, 2, 4, 4,
                "queued-byte-limit-test");
        try {
        require(bytePump.offer(new byte[] {1}), "byte-limit seed packet rejected");
        awaitWriter(byteOutput.writeEntered, bytePump);
        require(bytePump.offer(new byte[] {2, 3, 4, 5}), "byte-limit packet rejected");
        require(!bytePump.offer(new byte[] {6}), "queued byte limit accepted another packet");
        bytePump.close();
        } finally { bytePump.close(); }
    }

    private static long estimateMilliseconds = 2000;
    private static java.util.function.Consumer<String> warning = message -> System.err.println("WARNING: " + message);
    private static void warn(String message) {
        try { warning.accept(message); } catch (Throwable publication) { }
    }
    private static void awaitWriter(CountDownLatch entered, BoundedPacketPump original) throws Exception {
        long began = System.nanoTime(); boolean warned = false;
        while (!entered.await(10, TimeUnit.MILLISECONDS)) {
            if (original.failure() != null || original.isTerminated())
                throw new AssertionError("actual writer terminated before entering", original.failure());
            if (!warned && System.nanoTime() - began >= TimeUnit.MILLISECONDS.toNanos(estimateMilliseconds)) {
                warned = true; warn("same actual writer has not entered yet");
            }
        }
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
