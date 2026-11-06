// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.navigatorvisibility;

import horizonos.os.resource.CurrentControllerState;
import horizonos.os.resource.FeatureController;
import horizonos.os.resource.FeatureControllerRegistrationInfo;
import horizonos.os.resource.FeatureManager;
import horizonos.os.resource.SignalReceivedValue;
import horizonos.os.resource.SignalRequest;
import horizonos.os.resource.StateChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Firmware-specific binding; its compile-only vendor signatures are never packaged. */
final class CrcsVisibilityBackend extends FeatureController
        implements NavigatorVisibilityObserver.Backend {
    private static final String SIGNAL = "signal://shell/navigator/visibility";
    private final FeatureManager manager;
    private final FeatureControllerRegistrationInfo registration;
    private volatile Callback callback;

    CrcsVisibilityBackend(Object service) {
        manager = (FeatureManager) service;
        registration = new FeatureControllerRegistrationInfo.Builder(
                "controller://io.github.mesmerprism.rustyquest/navigator-visibility/" + UUID.randomUUID())
                .addSignalRequest(new SignalRequest(SIGNAL, 2, 0L)).build();
    }

    @Override public void attach(Callback callback) { this.callback = callback; }
    @Override public boolean register() { return manager.registerController(this, registration); }
    @Override public boolean unregister() { return manager.unregisterController(this); }

    @Override public void onError(horizonos.os.resource.Error error) {
        Callback current = callback;
        if (current != null) current.fault("vendor-error");
    }

    @Override public CurrentControllerState onStateChanged(StateChange change) {
        Callback current = callback;
        if (current != null) {
            try {
                Map<String, List<SignalReceivedValue>> signals = change.getSignalsReceived();
                List<SignalReceivedValue> values = signals == null ? null : signals.get(SIGNAL);
                if (values != null) {
                    // Preserve hidden edges in a batch, in vendor-delivered order.
                    for (SignalReceivedValue value : values) {
                        current.sample(value.getBoolean(), value.getTimeSinceBoot());
                    }
                }
            } catch (RuntimeException | LinkageError failure) {
                current.fault("malformed-vendor-callback");
            }
        }
        return new CurrentControllerState(new ArrayList<>(), Optional.empty());
    }
}
