"""Exercise the actual queuePacket and pressure logger with modeled codec/clock resources."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def method(source, name):
    start = source.find("    private void " + name + "(")
    if start < 0:
        return ""
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


HEADER = r"""
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
public final class ReceiverInputPressureHost {
    final Object lock = new Object();
    final Bounds bounds = new Bounds();
    final long generation = 7;
    final Map<Long, PendingFrame> pendingFrames = new HashMap<>(), retiredRenderFrames = new HashMap<>();
    final StageTrace stageTrace = new StageTrace();
    boolean stopRequested, firstInputPressureReported, firstInputRecoveryReported, firstInputTimeoutReported, firstInputTerminalReported;
    boolean firstExtendedInputRecoveryReported;
    static final long EXTENDED_INPUT_PRESSURE_MS = 100L;
    long outputsDequeued, outputsReleasedForRender, acquiredFrames;
    long identityWindowOverflows, receivedPackets, inputsQueued;
    int maxQueuedWindow, drains;
    boolean stopOnDrain, failOnDrain;
    final CodecFailure codecFailure = new CodecFailure();
    static final class CodecFailure extends RuntimeException { private static final long serialVersionUID = 1L; }
    static final class Bounds { int codecInputTimeoutMs = 100, maxPendingFrames = 32; }
    static final class SystemClock {
        static long now;
        static long elapsedRealtime() { return now; }
        static long elapsedRealtimeNanos() { return now * 1_000_000L; }
    }
    static final class StageTrace { void progress(int stage, long connection, long now, long pts, long pair) {} }
    static final class ReceiverStageTrace { static final int INPUT = 1; }
    static final class RmanvidPacketReader {
        static final int FLAG_CODEC_CONFIG = 2;
        static final class Packet {
            final byte[] payload = {3, 9, 27}; final long ptsUs = 41; final int flags = 0;
            final Pair pair = new Pair();
        }
        static final class Pair { long pairId = 11; }
    }
    static final class FrameIdentity { static FrameIdentity from(RmanvidPacketReader.Packet packet) { return new FrameIdentity(); } }
    static final class PendingFrame { PendingFrame(long connection, FrameIdentity identity) {} }
    final class MediaCodec {
        int unavailable, calls, queued;
        boolean alwaysUnavailable, fail;
        RmanvidPacketReader.Packet expected;
        final ByteBuffer buffer = ByteBuffer.allocate(32);
        int dequeueInputBuffer(long timeoutUs) {
            require(timeoutUs == 10_000L, "per-call dequeue bound changed");
            calls++;
            if (fail) throw codecFailure;
            if (alwaysUnavailable || unavailable-- > 0) { SystemClock.now += 10; return -1; }
            return 0;
        }
        ByteBuffer getInputBuffer(int index) { require(index == 0, "foreign input index"); return buffer; }
        void queueInputBuffer(int index, int offset, int size, long pts, int flags) {
            require(index == 0 && offset == 0 && size == expected.payload.length && pts == expected.ptsUs,
                    "same packet identity changed");
            require(flags == expected.flags, "codec flags changed");
            require(Arrays.equals(Arrays.copyOf(buffer.array(), size), expected.payload), "payload changed");
            queued++;
        }
    }
    void drain(MediaCodec codec, long connection) {
        require(connection == 13, "connection changed"); drains++;
        if (failOnDrain) throw codecFailure;
        outputsDequeued++; outputsReleasedForRender++;
        // Models independently observed acquisition; does not unlock this codec.
        acquiredFrames++;
        if (stopOnDrain) stopRequested = true;
    }
    void awaitIdentityCapacity(MediaCodec codec, long connection) {}
    int queuedWindowLocked() { return pendingFrames.size(); }
    int renderWindowLocked() { return 0; }
    static int codecInputFlags(int flags) { return flags; }
    static IOException closed(String code, Throwable cause) { return new IOException(code, cause); }
    static void require(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
"""

TAIL = r"""
    static MediaCodec codec(ReceiverInputPressureHost r, int unavailable) {
        MediaCodec c = r.new MediaCodec(); c.unavailable = unavailable; return c;
    }
    static void queue(ReceiverInputPressureHost r, MediaCodec c) throws Exception {
        c.expected = new RmanvidPacketReader.Packet(); r.queuePacket(c, 13, c.expected);
    }
    static void resetClockAndLog() { SystemClock.now = 0; android.util.Log.lines.clear(); android.util.Log.fail = false; }
    static String outcomeLog() {
        require(android.util.Log.lines.size() == 2, "first pressure and outcome must retain bounded diagnostics");
        contains(android.util.Log.lines.get(0), "outcome=waiting");
        contains(android.util.Log.lines.get(0), "waitElapsedMs=10");
        contains(android.util.Log.lines.get(0), "outputsDrained=0");
        return android.util.Log.lines.get(1);
    }
    static void contains(String log, String expected) { require(log.contains(expected), "missing diagnostic: " + expected + " in " + log); }
    static void timeout(ReceiverInputPressureHost r, MediaCodec c) throws Exception {
        try { queue(r, c); throw new AssertionError("true deadline did not expire"); }
        catch (IOException expected) { require(expected.getMessage().equals("DECODER_INPUT_TIMEOUT"), "failure code changed"); }
    }
    public static void main(String[] args) throws Exception {
        resetClockAndLog(); ReceiverInputPressureHost direct = new ReceiverInputPressureHost();
        MediaCodec immediate = codec(direct, 0); queue(direct, immediate);
        require(immediate.queued == 1 && direct.receivedPackets == 1 && android.util.Log.lines.isEmpty(), "ordinary packet changed");

        resetClockAndLog(); ReceiverInputPressureHost recovered = new ReceiverInputPressureHost();
        MediaCodec delayed = codec(recovered, 2); queue(recovered, delayed);
        String log = outcomeLog(); contains(log, "outcome=recovered"); contains(log, "waitElapsedMs=20");
        contains(log, "inputBudgetMs=100"); contains(log, "inputAttempts=3"); contains(log, "lastInputCode=-1");
        contains(log, "outputsDrained=2"); contains(log, "outputsReleased=2"); contains(log, "hostAcquiredObserved=2");
        contains(log, "waitStartedElapsedMs=0"); contains(log, "receiverGeneration=7"); contains(log, "connectionGeneration=13");
        require(delayed.queued == 1 && delayed.calls == 3 && recovered.drains == 2, "same input wait changed");
        recovered.pendingFrames.clear(); queue(recovered, codec(recovered, 1));
        require(android.util.Log.lines.size() == 2, "pressure diagnostic floods repeated packets");

        recovered.pendingFrames.clear(); MediaCodec laterTimeout = codec(recovered, 0); laterTimeout.alwaysUnavailable = true;
        timeout(recovered, laterTimeout);
        require(android.util.Log.lines.size() == 3, "first timeout lost after recovered pressure");
        contains(android.util.Log.lines.get(2), "outcome=timeout"); contains(android.util.Log.lines.get(2), "waitElapsedMs=100");
        contains(android.util.Log.lines.get(2), "inputAttempts=10");
        timeout(recovered, laterTimeout); require(android.util.Log.lines.size() == 3, "timeout diagnostic floods");

        resetClockAndLog(); ReceiverInputPressureHost expired = new ReceiverInputPressureHost();
        MediaCodec busy = codec(expired, 0); busy.alwaysUnavailable = true; timeout(expired, busy);
        contains(outcomeLog(), "outcome=timeout");
        require(SystemClock.now == 100 && busy.calls == 10 && busy.queued == 0 && expired.inputsQueued == 0,
                "total deadline or unqueued packet changed");

        resetClockAndLog(); ReceiverInputPressureHost stopped = new ReceiverInputPressureHost(); stopped.stopOnDrain = true;
        MediaCodec stopCodec = codec(stopped, 0); stopCodec.alwaysUnavailable = true; timeout(stopped, stopCodec);
        contains(outcomeLog(), "outcome=stopped"); require(stopCodec.calls == 1 && SystemClock.now == 10, "Stop response changed");

        resetClockAndLog(); ReceiverInputPressureHost failed = new ReceiverInputPressureHost(); failed.failOnDrain = true;
        try { queue(failed, codec(failed, 1)); throw new AssertionError("codec failure swallowed"); }
        catch (CodecFailure actual) { require(actual == failed.codecFailure, "codec failure replaced"); }
        contains(outcomeLog(), "outcome=failed");

        resetClockAndLog(); ReceiverInputPressureHost initialError = new ReceiverInputPressureHost();
        MediaCodec broken = codec(initialError, 0); broken.fail = true;
        try { queue(initialError, broken); throw new AssertionError("codec failure swallowed"); }
        catch (CodecFailure actual) { require(actual == initialError.codecFailure, "codec failure replaced"); }
        require(android.util.Log.lines.isEmpty(), "invented pressure before input result");

        resetClockAndLog(); android.util.Log.fail = true; ReceiverInputPressureHost loggingError = new ReceiverInputPressureHost();
        MediaCodec loggingCodec = codec(loggingError, 1); queue(loggingError, loggingCodec);
        require(loggingCodec.queued == 1, "diagnostic failure changed packet outcome");

        resetClockAndLog(); ReceiverInputPressureHost extended = new ReceiverInputPressureHost();
        extended.bounds.codecInputTimeoutMs = 1000;
        queue(extended, codec(extended, 1));
        require(android.util.Log.lines.size() == 2, "short recovery lost ordinary diagnostics");
        extended.pendingFrames.clear(); queue(extended, codec(extended, 10));
        require(android.util.Log.lines.size() == 2, "100ms boundary incorrectly classified extended");
        extended.pendingFrames.clear(); queue(extended, codec(extended, 30));
        require(android.util.Log.lines.size() == 3, "earlier short recovery suppressed first extended recovery");
        String extendedLog = android.util.Log.lines.get(2);
        contains(extendedLog, "outcome=extended-recovered"); contains(extendedLog, "waitElapsedMs=300");
        contains(extendedLog, "inputBudgetMs=1000"); contains(extendedLog, "inputAttempts=31");
        contains(extendedLog, "outputsDrained=30");
        extended.pendingFrames.clear(); queue(extended, codec(extended, 40));
        require(android.util.Log.lines.size() == 3, "repeated extended recovery floods diagnostics");
        require(extended.inputsQueued == 4 && SystemClock.now == 810, "extended observation changed wait/packet semantics");
        System.out.println("PASS 10 actual queuePacket pressure schedules: unchanged packet/codec/connection, deadline100ms, Stop, codec errors, bounded first pressure/timeout/extended recovery and diagnostic-failure isolation. Codec, drain, clock and acquisition counters modeled; no Surface pump or headset fix proven.");
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    out = args.out_dir.resolve()
    if not out.is_relative_to(repo / "target") or out.exists():
        raise ValueError("Create-new repository target output required")
    source_path = repo / "crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media/PackedStereoMediaReceiver.java"
    source = source_path.read_text(encoding="utf-8")
    queue = method(source, "queuePacket")
    if not queue:
        raise ValueError("Actual queuePacket missing")
    out.mkdir(parents=True)
    harness = out / "ReceiverInputPressureHost.java"
    harness.write_text(HEADER + queue + "\n" + method(source, "logInputPressure") + TAIL, encoding="utf-8")
    log_stub = out / "android/util/Log.java"
    log_stub.parent.mkdir(parents=True)
    log_stub.write_text("package android.util; public final class Log { public static final java.util.List<String> lines = new java.util.ArrayList<>(); public static boolean fail; public static int i(String tag, String text) { if(fail)throw new IllegalStateException(\"modeled logger\"); lines.add(text); return 0; } }", encoding="utf-8")
    javac, java = args.java_home / "bin/javac.exe", args.java_home / "bin/java.exe"
    for name, command in [
        ("compile", [str(javac), "--release", "8", "-Xlint:all", "-Werror", "-d", str(out), str(log_stub), str(harness)]),
        ("test", [str(java), "-cp", str(out), "ReceiverInputPressureHost"]),
    ]:
        result = subprocess.run(command, capture_output=True, text=True)
        (out / (name + ".log")).write_text(result.stdout + result.stderr, encoding="utf-8")
        print(result.stdout + result.stderr, end="")
        if result.returncode:
            raise SystemExit(result.returncode)
    (out / "RESULT.json").write_text(json.dumps({
        "status": "passed", "source_sha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
        "test_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "scope": "Actual extracted queuePacket/logger; modeled Android codec, clock, drain and acquisition counters",
        "android_execution": False, "device_calls": 0,
    }, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
