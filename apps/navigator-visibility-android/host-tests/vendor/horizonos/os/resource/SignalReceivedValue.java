// SPDX-License-Identifier: AGPL-3.0-or-later
// Host test double; never packaged.
package horizonos.os.resource;
public final class SignalReceivedValue {
 private final boolean value;
 private final long time;
 public SignalReceivedValue(boolean value, long time) { this.value=value; this.time=time; }
 public boolean getBoolean() { return value; }
 public long getTimeSinceBoot() { return time; }
}
