// SPDX-License-Identifier: AGPL-3.0-or-later
package horizonos.os.resource;
/** Compile-only ABI declaration. Never ship this package. */
public abstract class FeatureController {
    public abstract CurrentControllerState onStateChanged(StateChange change);
    public abstract void onError(Error error);
}
