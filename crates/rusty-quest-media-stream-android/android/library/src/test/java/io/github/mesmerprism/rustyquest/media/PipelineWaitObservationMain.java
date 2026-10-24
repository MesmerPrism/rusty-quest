package io.github.mesmerprism.rustyquest.media;

import java.io.OutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import org.json.JSONObject;
import org.json.JSONArray;

/** Runs the actual bounded writer against a blocked output leaf. No Android execution. */
public final class PipelineWaitObservationMain {
    public static void main(String[] args) throws Exception {
        completeCounts();
        decoderControls();
        overflowControls();
        writerFailures();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        byte[][] delivered = {null};
        OutputStream output = new OutputStream() {
            @Override public void write(int value) { }
            @Override public void write(byte[] value) throws IOException {
                delivered[0] = value;
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test leaf timeout"); }
                catch (InterruptedException error) { throw new IOException(error); }
            }
        };
        BoundedPacketPump pump = new BoundedPacketPump(output, 4, "pipeline-wait-host");
        try {
            byte[] offered = {1,2,3};
            if (!pump.offer(offered, 73, 84, 95) || !entered.await(5, TimeUnit.SECONDS))
                throw new AssertionError("actual writer did not enter output leaf");
            Method snapshot;
            try { snapshot = BoundedPacketPump.class.getDeclaredMethod("waitSnapshot"); }
            catch (NoSuchMethodException missing) {
                throw new AssertionError("blocked actual writer has no operation-entry diagnostic", missing);
            }
            JSONObject live = (JSONObject) snapshot.invoke(pump);
            if (!live.toString().contains("packet_write"))
                throw new AssertionError("actual writer phase unavailable");
            JSONArray row = live.getJSONArray("rows").getJSONArray(0);
            if (number(row,11) != 84 || number(row,12) != 95 || number(row,13) != 73)
                throw new AssertionError("actual offered packet identity lost");
            offered[0] = 9;
            if (!Arrays.equals(delivered[0], new byte[]{1,2,3})) throw new AssertionError("packet copy changed");
        } finally { release.countDown(); pump.close(); }
        System.out.println("PASS actual writer exposes blocked leaf without changing packet delivery");
    }
    static void completeCounts() throws Exception {
        long[] clock = {1};
        PipelineWaitObservation observation = new PipelineWaitObservation("control", () -> clock[0], "leaf");
        for (long duration : new long[] {1_000_000_000L, 600_000_000L, 500_000_000L}) {
            long token = observation.begin(0, 9, 10, 11, PipelineWaitObservation.WORK);
            clock[0] += duration; observation.end(0, token, true); clock[0]++;
        }
        JSONObject snapshot = observation.snapshot(); JSONArray row = snapshot.getJSONArray("rows").getJSONArray(0);
        if (!snapshot.getBoolean("available") || number(row,1) != 3 || number(row,3) != 2
                || number(row,4) != 1_000_000_000L || number(row,7) != 10)
            throw new AssertionError("smaller later stall hidden by old maximum or exact-bound rejected");
        long token = observation.begin(0, 9, 10, 11, PipelineWaitObservation.IMPORT);
        clock[0] += 750_000_000L;
        snapshot = observation.snapshot(); row = snapshot.getJSONArray("rows").getJSONArray(0);
        if (number(row,10) == 0 || number(row,14) != PipelineWaitObservation.IMPORT
                || snapshot.getLong("sample_ns")-number(row,10) != 750_000_000L) throw new AssertionError("inflight operation lost");
        observation.end(0, token, false);
        row = observation.snapshot().getJSONArray("rows").getJSONArray(0);
        if (number(row,2) != 1 || number(row,3) != 3 || number(row,10) != 0)
            throw new AssertionError("throwing leaf completion lost");
        token = observation.begin(0, 9, 10, 11, 1); clock[0]--;
        observation.end(0, token, true);
        if (observation.snapshot().getBoolean("available")) throw new AssertionError("regressed clock accepted");
    }
    static long number(JSONArray row,int column) {
        Object value = row.get(column);
        if (!(value instanceof String) || !((String)value).matches("0|[1-9a-z][0-9a-z]*"))
            throw new IllegalArgumentException("base36 nonnegative i64 string required");
        return Long.parseLong((String)value,36);
    }
    static void decoderControls() {
        for (long value : new long[]{0,1,500_000_000L,Long.MAX_VALUE})
            if (number(new JSONArray().put(Long.toString(value,36)),0) != value) throw new AssertionError("lossy counter encoding");
        for (Object value : new Object[]{true,false,1,1.5,"-1","+1","1.0","01","1Y","1y2p0ij32e8e8"}) {
            try { number(new JSONArray().put(value),0); throw new AssertionError("unsupported value accepted: "+value); }
            catch (IllegalArgumentException expected) { }
        }
    }
    static void overflowControls() throws Exception {
        PipelineWaitObservation observation = new PipelineWaitObservation("overflow", () -> 100, "leaf");
        java.lang.reflect.Field field = PipelineWaitObservation.class.getDeclaredField("rows");field.setAccessible(true);
        ((long[][])field.get(observation))[0][0] = Long.MAX_VALUE;
        if (observation.begin(0,1,1,1,1) != 0 || observation.snapshot().getBoolean("available"))
            throw new AssertionError("counter overflow not unavailable");
        PipelineWaitObservation clock = new PipelineWaitObservation("clock", () -> {throw new IllegalStateException("clock leaf");}, "leaf");
        if (clock.begin(0,1,1,1,1) != 0 || clock.snapshot() != null) throw new AssertionError("observer error propagated");
        for (long value : new long[]{-1,Long.MAX_VALUE}) {
            PipelineWaitObservation pts = new PipelineWaitObservation("pts", () -> 100, "leaf");
            if (pts.ptsNs(value) != 0 || pts.snapshot().getBoolean("available")) throw new AssertionError("invalid PTS relabelled");
        }
    }
    static void writerFailures() throws Exception {
        for (boolean flush : new boolean[]{false,true}) {
            IOException sentinel = new IOException("leaf failure");
            OutputStream output = new OutputStream() {
                @Override public void write(int value) { }
                @Override public void write(byte[] value) throws IOException { if (!flush) throw sentinel; }
                @Override public void flush() throws IOException { if (flush) throw sentinel; }
            };
            BoundedPacketPump pump = new BoundedPacketPump(output,4,"pipeline-error-host");
            try {
                if (!pump.offer(new byte[]{1},73,84,95)) throw new AssertionError("offer changed");
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while (!pump.isTerminated() && System.nanoTime()<deadline) Thread.yield();
                if (!pump.isTerminated() || pump.failure()!=sentinel) throw new AssertionError("writer failure result changed");
                JSONArray row = pump.waitSnapshot().getJSONArray("rows").getJSONArray(flush?1:0);
                if (number(row,1)!=1 || number(row,2)!=1 || number(row,10)!=0) throw new AssertionError("throwing writer observation lost");
            } finally { pump.close(); }
        }
    }
}
