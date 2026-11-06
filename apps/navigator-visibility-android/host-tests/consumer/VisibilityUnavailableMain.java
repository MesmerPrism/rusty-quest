// SPDX-License-Identifier: AGPL-3.0-or-later
import android.content.Context;
import io.github.mesmerprism.rustyquest.navigatorvisibility.AndroidNavigatorVisibility;
import io.github.mesmerprism.rustyquest.navigatorvisibility.NavigatorVisibilityObserver.*;

/** Run without vendor classes to verify the optional linkage boundary. */
public final class VisibilityUnavailableMain {
    public static void main(String[] args) {
        Context context = new Context() {
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String name) { return new Object(); }
        };
        AndroidNavigatorVisibility.Candidate candidate = AndroidNavigatorVisibility.create(context,
                new Listener() {
                    @Override public void observed(Observation sample) { throw new AssertionError(); }
                    @Override public void fault(long generation, String category) { throw new AssertionError(); }
                });
        if (candidate.available || candidate.observer != null
                || !candidate.reason.equals("vendor-linkage-unavailable")) throw new AssertionError();
        System.out.println("Navigator visibility missing-vendor linkage: passed.");
    }
}
