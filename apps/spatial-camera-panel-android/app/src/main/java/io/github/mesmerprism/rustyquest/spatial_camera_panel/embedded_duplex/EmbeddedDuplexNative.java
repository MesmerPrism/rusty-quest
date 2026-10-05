package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

/** Narrow process-local bridge into the app's single authority/native library. */
public final class EmbeddedDuplexNative {
    public static final int FRAME_IDENTITY_WORDS = 14;
    public static final int FRAME_OBSERVATION_WORDS = 17;
    public static final int FRAME_TIMED_OBSERVATION_WORDS = 19;
    public static final int FRAME_EVIDENCE_VERSION = 2;
    public static final int ACQUIRED_TIMED_OBSERVATION_WORDS = 19;
    public static final int EFFECTIVE_TIMED_OBSERVATION_WORDS = 21;
    static { System.loadLibrary("spatial_camera_panel_native_receipt"); }
    private EmbeddedDuplexNative() {}
    static native String selectLocalAfterTerminal(String expectedConfigSha256);

    // Identity: receiver, connection, route, decoder, reader, PTS ns, source elapsed,
    // source Unix, pair, left frame, right frame, left sensor, right sensor, pair delta.
    public static native boolean registerReceiverFrame(long[] identity);
    public static native boolean recordReceiverFrameRendered(long[] identity);
    public static native long[] currentReceiverFrame(long receiverGeneration,
            long connectionGeneration, long routeGeneration, long decoderToken,
            long readerGeneration, long maxAgeNs);
    // Same 17 words, followed by native CLOCK_MONOTONIC observed-at and
    // observed-at minus the oldest register/render/acquire witness.
    public static native long[] currentReceiverFrameTimed(long receiverGeneration,
            long connectionGeneration, long routeGeneration, long decoderToken,
            long readerGeneration, long maxAgeNs);

    // New v2 evidence, deliberately separate from the legacy 17/19-word join.
    // [0]=version; [1..14]=exact frame identity; [15..18]=register,
    // acquire, observed, oldest age. No MediaCodec callback is implied.
    public static native long[] currentReceiverAcquiredFrameTimed(long receiverGeneration,
            long connectionGeneration, long routeGeneration, long decoderToken,
            long readerGeneration, long maxAgeNs);

    // [0]=version; [1..14]=identity; [15..20]=register, acquire,
    // GPU-retired, observed, oldest age, exact import sequence.
    public static native long[] currentReceiverEffectiveFrameTimed(long receiverGeneration,
            long connectionGeneration, long routeGeneration, long decoderToken,
            long readerGeneration, long maxAgeNs);
    public static native void retireReceiverGeneration(long receiverGeneration);
    public static native void retireReceiverConnection(long receiverGeneration, long connectionGeneration);
    public static native boolean localCameraQuiescent();

    static native String claimNativeProcessFence(Object heldAppFence);
    static native void finishNativeNoMediaCleanup(long executorGeneration, String expectedConfigSha256);
    static native String assemblePackagedConfig(String exactRequestJson);
    static native String initializeRuntime(String runtimeConfig, String expectedConfigSha256,
            String providerEpochEntropyHex, String bootstrap, Object platformCallbacks);
    static native String runtimeCommand(String operation, String input);
    static native String peerLifecycle(int action);
    static native String closeNoMediaRuntime(String expectedConfigSha256);
    static native boolean processIdleForEnrollment();
    static native byte[] handleOwnerFrame(byte[] exactFrame);
    static native byte[] ed25519PublicFromSeed(byte[] seed);
    static native byte[] ed25519SignAuthorityBytes(byte[] seed, byte[] exactSigningBytes);
}
