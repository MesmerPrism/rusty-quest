// SPDX-License-Identifier: AGPL-3.0-or-later
// Host test double; never packaged.
package horizonos.os.resource;
import java.util.List;
import java.util.Optional;
public final class CurrentControllerState {
 public final List<?> states;
 public final Optional<Float> priority;
 public CurrentControllerState(List<?> states, Optional<Float> priority) {
  this.states=states; this.priority=priority;
 }
}
