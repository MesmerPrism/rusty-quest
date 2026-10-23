package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Paths

/** Actual producer/consumer and transport-byte checks, independent of SDK pixels. */
object DevelopmentSceneHostTest {
    @JvmStatic fun main(args:Array<String>) {
        val assets=Paths.get(args[0]).resolve("assets")
        val raw=Files.readString(assets.resolve("workload/manifest.json"))
        val descriptor=DevelopmentSceneManifest.parse(JSONObject(raw)){Files.readAllBytes(assets.resolve(it))}
        val layout=descriptor.layout;check(layout.publicDevelopment&&layout.mainCapacity==512&&layout.tracerCapacity==0)
        descriptor.hands.validate(layout);check(descriptor.hands.leftCorners==0&&descriptor.hands.rightCorners==0)
        check(WorkloadTopology.plan("p64",descriptor).vertices==256)
        check(WorkloadTopology.plan("p512",descriptor).vertices==2048)
        try{WorkloadTopology.plan("hfull",descriptor);error("Public packet admitted hand topology")}catch(_:IllegalArgumentException){}
        val bytes=AtlasPacket.reference(layout);val originalHeader=ByteArray(256);bytes.duplicate().get(originalHeader)
        Files.write(assets.parent.parent.resolve("development-atlas-header.json"),org.json.JSONArray((0..47).map{AtlasPacket.readHeader(originalHeader,it)}).toString().toByteArray(Charsets.UTF_8))
        fun point():Float {
            var bits=0;repeat(4){bits=bits or ((bytes.get(AtlasPacket.byteOffset(AtlasPacket.HEADER_TEXELS*3+it)).toInt() and 255) shl (it*8))}
            return Float.fromBits(bits)
        }
        val addressOwner=bytes
        val nativeDeltas=listOf(CpuFeedbackProjection.nativeDelta(0,5),CpuFeedbackProjection.nativeDelta(5,15),CpuFeedbackProjection.nativeDelta(15,20))
        check(nativeDeltas==listOf(5L,10L,5L))
        check(CpuFeedbackProjection.nativeDelta(Long.MAX_VALUE,Long.MIN_VALUE)==1L)
        check(CpuFeedbackProjection.nativeDelta(Long.MIN_VALUE,Long.MAX_VALUE)==-1L)
        check(CpuFeedbackProjection.nativeDelta(Long.MIN_VALUE,0)==Long.MIN_VALUE)
        check(CpuFeedbackProjection.dexDelta(Long.MAX_VALUE,1,Long.MIN_VALUE+5)==5L)
        check(CpuFeedbackProjection.dexDelta(Long.MIN_VALUE,Long.MIN_VALUE,Long.MIN_VALUE)==Long.MIN_VALUE)
        for(edge in listOf(Long.MIN_VALUE,Long.MAX_VALUE,-1L,0L,1L)){
            check(CpuFeedbackProjection.spacing(edge) in .12f.. .20f)
            CpuFeedbackProjection.apply(bytes,layout,edge)
            check(point().isFinite())
        }
        check(CpuFeedbackProjection.color(5).contentEquals(intArrayOf(40,150,245)))
        check(CpuFeedbackProjection.color(10).contentEquals(intArrayOf(245,140,35)))
        check(listOf(CpuFeedbackProjection.dexDelta(4,64,73),CpuFeedbackProjection.dexDelta(4,65,79),CpuFeedbackProjection.dexDelta(4,66,75))==nativeDeltas)
        CpuFeedbackProjection.apply(bytes,layout,nativeDeltas[0]);val first=point()
        val firstBytes=ByteArray(bytes.capacity());bytes.duplicate().get(firstBytes)
        check(kotlin.math.abs(first-(-3.5f*CpuFeedbackProjection.spacing(5)))<.00001f)
        CpuFeedbackProjection.apply(bytes,layout,nativeDeltas[1]);check(point()!=first)
        CpuFeedbackProjection.apply(bytes,layout,nativeDeltas[2]);check(addressOwner===bytes&&point()==first)
        val restored=ByteArray(bytes.capacity());bytes.duplicate().get(restored);check(firstBytes.contentEquals(restored))
        val after=ByteArray(256);bytes.duplicate().get(after);check(originalHeader.contentEquals(after))
        check(CpuFeedbackProjection.spacing(Long.MIN_VALUE).isFinite())
        try{DevelopmentSceneManifest.parse(JSONObject(raw)){ByteArray(4)};error("Damaged public mask accepted")}catch(_:IllegalArgumentException){}
        val privatePacket=JSONObject(raw).put("source","private");try{DevelopmentSceneManifest.parse(privatePacket){Files.readAllBytes(assets.resolve(it))};error("Private source accepted")}catch(_:IllegalArgumentException){}
        try{layout.copy(publicDevelopment=false).validate();error("Public layout reinterpreted as genuine workload")}catch(_:IllegalArgumentException){}
        println("PASS public development producer/consumer, no-rig topology, retained atlas A/B/A projection and refusals")
    }
}
