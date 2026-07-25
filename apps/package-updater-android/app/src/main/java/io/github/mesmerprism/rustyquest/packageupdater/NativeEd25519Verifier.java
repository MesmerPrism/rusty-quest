package io.github.mesmerprism.rustyquest.packageupdater;

/**
 * JNI boundary to the repository's pinned Rust Ed25519 implementation.
 *
 * Android 14 exposes an AndroidKeyStore-only Ed25519 Signature service but no
 * public Ed25519 KeyFactory for importing a build-fixed raw release key. The
 * native verifier accepts only raw key, message, and signature bytes and
 * returns no authority-bearing object.
 */
final class NativeEd25519Verifier {
    static {
        System.loadLibrary("rusty_quest_package_updater_android");
    }

    private NativeEd25519Verifier() {
    }

    static boolean verify(
            byte[] publicKey, byte[] message, byte[] signature) {
        return nativeVerify(publicKey, message, signature);
    }

    private static native boolean nativeVerify(
            byte[] publicKey, byte[] message, byte[] signature);
}
