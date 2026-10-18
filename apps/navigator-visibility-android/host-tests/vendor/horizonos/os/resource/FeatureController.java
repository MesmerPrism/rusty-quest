// SPDX-License-Identifier: AGPL-3.0-or-later
// Host test double; never packaged.
package horizonos.os.resource;
public abstract class FeatureController {
 public abstract CurrentControllerState onStateChanged(StateChange change);
 public abstract void onError(Error error);
}
