package io.github.mesmerprism.rustyquest.media;
/** Warning publication cannot turn an app-owned producer into a failure. */
final class PackedStereoWait {
    private PackedStereoWait() {}
    static void warn(String message) { try { android.util.Log.w("RustyMediaWait",message); } catch(Throwable publication) { } }
}
