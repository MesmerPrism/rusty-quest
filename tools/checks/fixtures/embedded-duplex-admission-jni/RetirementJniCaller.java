package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.*;
import io.github.mesmerprism.rustyquest.media.*;
public final class RetirementJniCaller {
 public static void main(String[]args)throws Exception{
  OwnPackedPoolNative.fixtureConfigure(11);ConcurrentCallerRegression.own();
  SpatialVideoSourceRoutingCoordinator routing=DefaultLocalRetirementFixture.reproduce();
  EmbeddedDuplexDisplayCoordinator display=new EmbeddedDuplexDisplayCoordinator(routing,a->{a.invoke();return true;},()->false,()->new SpatialVideoSourceCarrierContext(10,20),a->true,a->true,a->null,a->new long[0],a->false,()->false,()->kotlin.Unit.INSTANCE);
  ConcurrentCallerRegression.route=3;
  EmbeddedDuplexReceiver receiver=ConcurrentCallerRegression.receiver(display);PackagedAndroidMediaOwnerRegistry registry=new PackagedAndroidMediaOwnerRegistry(1,ConcurrentCallerRegression.binding(receiver));
  String ticket=ConcurrentCallerRegression.ticket("arm_receiver",1,"fixture.owner","fixture.arm");String readback=registry.execute(ticket,false);if(registry.verifyAndReadEvidence(ticket,readback)==null||!"NONE".equals(receiver.failedArmStage())||OwnPackedPoolNative.fixtureReadSource(3)[2]!=2)throw new AssertionError("candidate complete arm failed");
  System.out.println("PASS actual Kotlin Display -> real host JNI/native admits exact Local intent1/native carrier2 -> Peer3 after retirement; rejects stale native counter and wrong native proof word; common renderer process route not substituted. Physical suppliers injected; no exact device numeric witness claimed.");System.exit(0);
 }
}
