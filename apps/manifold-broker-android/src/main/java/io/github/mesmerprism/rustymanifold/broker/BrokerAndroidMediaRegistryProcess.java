package io.github.mesmerprism.rustymanifold.broker;

import android.content.Context;

/** Compatibility host boundary for the optional reusable Android media executor. */
final class BrokerAndroidMediaRegistryProcess {
    private BrokerAndroidMediaRegistryProcess() { }

    static void installIfNeeded(Context context) {
        if (context == null) throw new NullPointerException("context");
        // This compatibility APK owns no receiver Surface and no complete local
        // seven-owner media product. It therefore installs no executor. Embedded
        // hosts compose exact providers from the shared AAR and install their own
        // process registry; legacy remote-camera commands retain their facade.
    }
}
