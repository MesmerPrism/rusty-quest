// SPDX-License-Identifier: AGPL-3.0-or-later
// Host test double; never packaged.
package horizonos.os.resource;
import java.util.List;
import java.util.Map;
public final class StateChange {
 private final Map<String,List<SignalReceivedValue>> signals;
 public StateChange(Map<String,List<SignalReceivedValue>> signals) { this.signals=signals; }
 public Map<String,List<SignalReceivedValue>> getSignalsReceived() { return signals; }
}
