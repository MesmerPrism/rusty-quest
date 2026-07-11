package io.github.mesmerprism.rustyquest.directp2p;

final class RustDirectSocketProvider {
    static {
        System.loadLibrary("rusty_quest_direct_p2p_provider");
    }

    private RustDirectSocketProvider() {}

    static String runServer(String localHost, int port, long networkHandle, long timeoutMs) {
        return nativeRunServer(localHost, port, networkHandle, timeoutMs);
    }

    static String runClient(String localHost, String peerHost, int port, String runId, long networkHandle, long timeoutMs) {
        return nativeRunClient(localHost, peerHost, port, runId, networkHandle, timeoutMs);
    }

    private static native String nativeRunServer(String localHost, int port, long networkHandle, long timeoutMs);
    private static native String nativeRunClient(String localHost, String peerHost, int port, String runId, long networkHandle, long timeoutMs);
}
