// SPDX-License-Identifier: AGPL-3.0-or-later
package horizonos.os.resource;
import java.util.List;
import java.util.Map;
public final class StateChange {
    private StateChange() { }
    public Map<String, List<SignalReceivedValue>> getSignalsReceived() {
        throw new UnsupportedOperationException();
    }
}
