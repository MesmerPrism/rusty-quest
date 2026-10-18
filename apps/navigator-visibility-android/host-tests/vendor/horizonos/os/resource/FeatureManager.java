// SPDX-License-Identifier: AGPL-3.0-or-later
// Host test double; never packaged.
package horizonos.os.resource;
public final class FeatureManager {
 public FeatureController controller;
 public FeatureControllerRegistrationInfo info;
 public int registrations, unregistrations;
 public boolean registerController(FeatureController controller, FeatureControllerRegistrationInfo info) {
  this.controller=controller; this.info=info; registrations++; return true;
 }
 public boolean unregisterController(FeatureController controller) {
  if (this.controller != controller) throw new AssertionError("wrong cleanup handle");
  unregistrations++; return true;
 }
}
