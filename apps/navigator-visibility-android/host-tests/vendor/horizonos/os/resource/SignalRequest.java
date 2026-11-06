// SPDX-License-Identifier: AGPL-3.0-or-later
// Host test double; never packaged.
package horizonos.os.resource;
public final class SignalRequest {
 public final String uri;
 public final int deliveryMode;
 public final long interval;
 public SignalRequest(String uri, int deliveryMode, long interval) {
  this.uri=uri; this.deliveryMode=deliveryMode; this.interval=interval;
 }
}
