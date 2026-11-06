package io.github.mesmerprism.rustyquest.native_renderer;

public final class PolarRecoveryIntentTest {
    public static void main(String[] args) {
        PolarRecoveryIntent intent = new PolarRecoveryIntent();
        check(!intent.connectionWanted(), "cold owner has no continued connection intent");
        intent.stopStream("acc");
        intent.connected("sensor-A", "acc");
        check(!intent.streamWanted("acc"), "explicit stop before first join defeats automatic default");
        intent.disconnect();
        intent.connected("sensor-A", "acc");
        check(intent.connectionWanted() && intent.streamWanted("acc"), "successful manual join arms ACC and connection");
        check(!intent.permitsCandidate("sensor-B"), "other unique peripheral cannot replace the selected sensor");
        intent.startStream("ecg");
        intent.connected("sensor-A", "ecg");
        check(intent.streamWanted("acc") && intent.streamWanted("ecg"), "same-sensor reconnect preserves parallel intent");
        intent.stopStream("acc");
        intent.connected("sensor-A", "acc");
        check(!intent.streamWanted("acc") && intent.streamWanted("ecg") && intent.connectionWanted(), "stopped ACC stays stopped while ECG/HR connection recovers");
        intent.stopStreams();
        intent.connected("sensor-A", "acc");
        check(!intent.streamWanted("acc") && !intent.streamWanted("ecg") && intent.connectionWanted(), "stopped PMD does not restart on HR reconnect");
        intent.connected("sensor-B", "ecg");
        check(intent.permitsCandidate("sensor-B") && !intent.permitsCandidate("sensor-A"), "new explicit selection replaces only the exact connection identity");
        intent.disconnect();
        check(!intent.connectionWanted() && !intent.streamWanted("ecg"), "intentional disconnect or owner stop disables all recovery");
        check(!intent.automaticStartupAllowed(), "panel recreation cannot rearm an intentional disconnect");
        intent.connected("sensor-A", "acc");
        check(intent.streamWanted("acc") && !intent.streamWanted("ecg"), "new session does not inherit stopped historical streams");
        try { intent.connected("none", "acc"); throw new AssertionError("missing identity accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("Polar recovery intent controls passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
