// SPDX-License-Identifier: AGPL-3.0-or-later
// Host test double; never packaged.
package horizonos.os.resource;
public final class FeatureControllerRegistrationInfo {
 public String uri;
 public SignalRequest request;
 public static final class Builder {
  private final FeatureControllerRegistrationInfo info=new FeatureControllerRegistrationInfo();
  public Builder(String uri) { info.uri=uri; }
  public Builder addSignalRequest(SignalRequest request) { info.request=request; return this; }
  public FeatureControllerRegistrationInfo build() { return info; }
 }
}
