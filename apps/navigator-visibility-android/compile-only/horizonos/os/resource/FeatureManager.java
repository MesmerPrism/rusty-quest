// SPDX-License-Identifier: AGPL-3.0-or-later
package horizonos.os.resource;
public final class FeatureManager {
    private FeatureManager() { }
    public boolean registerController(FeatureController controller,
            FeatureControllerRegistrationInfo info) { throw new UnsupportedOperationException(); }
    public boolean unregisterController(FeatureController controller) {
        throw new UnsupportedOperationException();
    }
}
