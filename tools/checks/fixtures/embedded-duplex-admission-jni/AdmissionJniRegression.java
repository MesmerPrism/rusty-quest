package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.OwnPackedPoolNative;
public final class AdmissionJniRegression {
 public static void main(String[] args)throws Exception {
  OwnPackedPoolNative.fixtureConfigure(0);ConcurrentCallerRegression.run("happy",null);
  OwnPackedPoolNative.fixtureConfigure(0);ConcurrentCallerRegression.run("false-own","DISPLAY_OWN_CAPTURE_STATE");
  OwnPackedPoolNative.fixtureConfigure(0);ConcurrentCallerRegression.run("not-fresh","DISPLAY_OWN_CAPTURE_FRESH");
  OwnPackedPoolNative.fixtureConfigure(1);ConcurrentCallerRegression.run("happy",null);
  OwnPackedPoolNative.fixtureConfigure(2);ConcurrentCallerRegression.run("native-actor","DISPLAY_NATIVE_CAPTURE");
  OwnPackedPoolNative.fixtureConfigure(3);ConcurrentCallerRegression.run("native-actor","DISPLAY_NATIVE_CAPTURE");
  OwnPackedPoolNative.fixtureConfigure(4);ConcurrentCallerRegression.run("native-actor","DISPLAY_NATIVE_FRAME_EPOCH");
  OwnPackedPoolNative.fixtureConfigure(5);ConcurrentCallerRegression.run("native-actor","DISPLAY_NATIVE_LOCAL");
  OwnPackedPoolNative.fixtureConfigure(7);ConcurrentCallerRegression.run("native-actor","DISPLAY_NATIVE_PROCESS_EPOCH");
  OwnPackedPoolNative.fixtureConfigure(9);ConcurrentCallerRegression.run("native-actor","DISPLAY_NATIVE_FRAME_FUTURE");
  OwnPackedPoolNative.fixtureConfigure(10);ConcurrentCallerRegression.run("native-actor","DISPLAY_NATIVE_FRAME_STALE");
  OwnPackedPoolNative.fixtureConfigure(0);try{OwnPackedPoolNative.concurrentPeerAdmission(1,11,20);throw new AssertionError("foreign carrier accepted");}catch(IllegalStateException expected){if(EmbeddedDuplexPlatform.providerReason(expected)!=EmbeddedDuplexPlatform.ProviderReason.DISPLAY_NATIVE_CARRIER)throw expected;}
  try{OwnPackedPoolNative.concurrentPeerAdmission(0,10,20);throw new AssertionError("invalid JNI input accepted");}
  catch(IllegalStateException expected){if(EmbeddedDuplexPlatform.providerReason(expected)!=EmbeddedDuplexPlatform.ProviderReason.DISPLAY_NATIVE_INPUT)throw expected;}
  System.out.println("PASS actual compiled Kotlin/Receiver/Registry -> host JVM actual JNI/native/source-set: stable and clock-race admission, claim/config/frame/local/epoch/input/carrier and future/stale-frame rejection categories. Native physical actors/clock/Looper are injected; no device-cause claim.");
  System.exit(0); // Host fixture owns process; actual supplier executor has no test shutdown route.
 }
}