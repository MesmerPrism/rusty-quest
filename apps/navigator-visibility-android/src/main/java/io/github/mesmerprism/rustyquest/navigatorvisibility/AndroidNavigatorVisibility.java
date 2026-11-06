// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.navigatorvisibility;

import android.content.Context;
import android.os.SystemClock;
import java.util.Objects;

/** Optional vendor linkage. Creating a candidate never subscribes or adds permissions. */
public final class AndroidNavigatorVisibility {
    private AndroidNavigatorVisibility() { }

    public static final class Candidate {
        /** Presence and linkage only; registration and delivered samples are separate evidence. */
        public final boolean available;
        public final String reason;
        public final NavigatorVisibilityObserver observer;
        private Candidate(String reason, NavigatorVisibilityObserver observer) {
            this.available = observer != null;
            this.reason = reason;
            this.observer = observer;
        }
    }

    public static Candidate create(Context context, NavigatorVisibilityObserver.Listener listener) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(listener, "listener");
        try {
            Context application = context.getApplicationContext();
            if (application == null) return new Candidate("application-context-unavailable", null);
            Object service = application.getSystemService("crcs");
            if (service == null) return new Candidate("service-unavailable", null);
            // The separate class keeps vendor types out of the portable observer API.
            NavigatorVisibilityObserver.Backend backend = new CrcsVisibilityBackend(service);
            return new Candidate("vendor-linkage-present", new NavigatorVisibilityObserver(
                    backend, SystemClock::uptimeMillis, listener));
        } catch (RuntimeException | LinkageError failure) {
            return new Candidate("vendor-linkage-unavailable", null);
        }
    }
}
