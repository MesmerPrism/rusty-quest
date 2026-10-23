package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import kotlin.math.abs

object OperatorGeometryHostTest {
    @JvmStatic fun main(args:Array<String>) {
        var checks=0
        fun near(actual:Double,expected:Double){check(abs(actual-expected)<1e-5){"$actual != $expected"};checks++}
        fun deny(action:()->Unit){var failed=false;try{action()}catch(_:IllegalArgumentException){failed=true};check(failed);checks++}
        val pose=SdkOperatorGeometry.project(doubleArrayOf(-1.05,1.4,1.3),doubleArrayOf(0.0,0.0,0.0,1.0))
        near(pose.center[0],-1.05);near(pose.center[1],1.4);near(pose.center[2],-1.3)
        near(pose.right[0],1.0);near(pose.up[1],1.0);near(pose.normal[2],1.0)
        val q=Math.sqrt(.5)
        val turned=SdkOperatorGeometry.project(doubleArrayOf(0.0,0.0,2.0),doubleArrayOf(0.0,q,0.0,q))
        near(turned.right[0],0.0);near(turned.right[2],1.0);near(turned.normal[0],-1.0)
        near(turned.orientation[1],-q);near(turned.orientation[3],q)
        val widget=SdkOperatorGeometry.widget(700,1000,doubleArrayOf(10.0,20.0,110.0,70.0),doubleArrayOf(10.0,25.0,110.0,70.0),.7,1.0)
        near(widget[0],-.29);near(widget[1],.4525)
        deny{SdkOperatorGeometry.widget(0,1000,doubleArrayOf(0.0,0.0,1.0,1.0),doubleArrayOf(0.0,0.0,1.0,1.0),.7,1.0)}
        deny{SdkOperatorGeometry.widget(700,1000,doubleArrayOf(10.0,20.0,110.0,70.0),doubleArrayOf(0.0,20.0,110.0,70.0),.7,1.0)}
        deny{SdkOperatorGeometry.widget(700,1000,doubleArrayOf(10.0,20.0,110.0,70.0),doubleArrayOf(10.0,70.0,110.0,70.0),.7,1.0)}
        deny{SdkOperatorGeometry.project(doubleArrayOf(Double.NaN,0.0,0.0),doubleArrayOf(0.0,0.0,0.0,1.0))}
        deny{SdkOperatorGeometry.project(doubleArrayOf(0.0,0.0,0.0),doubleArrayOf(0.0,0.0,0.0,0.0))}
        check(SdkOperatorGeometry.operatorLayerMapped("123-456 r-xp 0 00:00 1 /data/app/own/lib/arm64/libXrApiLayer_METAX_operator.so\n".toByteArray()));checks++
        check(!SdkOperatorGeometry.operatorLayerMapped("123-456 r-xp 0 00:00 1 /data/app/libXrApiLayer_METAX_operator.so.bak\n".toByteArray()));checks++
        check(!SdkOperatorGeometry.operatorLayerMapped(ByteArray(2*1024*1024+1)));checks++
        val revisions=OperatorLayoutRevision();val owner=Any();val root=Any()
        val bounds=doubleArrayOf(10.0,20.0,110.0,70.0);val clipped=doubleArrayOf(10.0,25.0,110.0,70.0)
        fun revision(expected:Long,o:Any=owner,r:Any=root,w:Int=700,h:Int=1000,b:DoubleArray=bounds,c:DoubleArray=clipped,enabled:Boolean=true){
            check(revisions.observe(o,r,w,h,b,c,enabled)==expected){"Identical observations must retain their layout revision"};checks++
        }
        revision(1);revision(1,b=bounds.clone(),c=clipped.clone())
        bounds[0]=9.0;revision(2);revision(2)
        val root2=Any();revision(3,r=root2);revision(3,r=root2)
        val owner2=Any();revision(4,o=owner2,r=root2)
        revision(5,o=owner2,r=root2,w=701);revision(6,o=owner2,r=root2,w=701,h=1001)
        clipped[1]=26.0;revision(7,o=owner2,r=root2,w=701,h=1001)
        revision(8,o=owner2,r=root2,w=701,h=1001,enabled=false);revision(8,o=owner2,r=root2,w=701,h=1001,enabled=false)
        val activityA=OperatorPoseEvidence("activity-A");val activityB=OperatorPoseEvidence("activity-B")
        val t=doubleArrayOf(0.0,1.4,1.3);val orientation=doubleArrayOf(0.0,0.0,0.0,1.0)
        val a1=activityA.observe(t,orientation,.7,1.0);val a2=activityA.observe(t.clone(),orientation.clone(),.7,1.0)
        check(a1.ownerToken=="activity-A"&&a1.poseRevision==1L&&a1.effectivePoseRevision==1L);checks++
        check(a2==a1);checks++
        val b1=activityB.observe(t,orientation,.7,1.0)
        check(b1.ownerToken=="activity-B"&&b1.ownerToken!=a1.ownerToken&&b1.poseRevision==1L);checks++
        t[0]=.1;val moved=activityA.observe(t,orientation,.7,1.0)
        check(moved.poseRevision==2L&&moved.effectivePoseRevision==2L&&moved.ownerToken=="activity-A");checks++
        check(activityA.observe(t,orientation,.7,1.0)==moved);checks++
        orientation[1]=q;orientation[3]=q;check(activityA.observe(t,orientation,.7,1.0).poseRevision==3L);checks++
        check(activityA.observe(t,orientation,.8,1.0).poseRevision==4L);checks++
        println("Operator geometry checks passed: $checks")
    }
}
