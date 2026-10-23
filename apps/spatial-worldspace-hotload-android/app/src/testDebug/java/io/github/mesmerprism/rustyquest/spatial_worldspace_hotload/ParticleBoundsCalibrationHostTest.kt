package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import kotlin.math.abs

/** Probe ordering and geometric preconditions only; no SDK cull or image emulation. */
object ParticleBoundsCalibrationHostTest {
    @JvmStatic fun main(args:Array<String>){
        val modes=ParticleBoundsCalibration.commands
        check(modes.size==6&&modes.toSet().size==6)
        var previous="off"
        for(next in listOf("packet-origin","host-front","host-behind","packet-behind-off","packet-behind-on","packet-behind-off","off")){
            check(ParticleBoundsCalibration.permits(previous,next));previous=next
        }
        check(!ParticleBoundsCalibration.permits("off","packet-behind-on"))
        check(!ParticleBoundsCalibration.permits("host-front","packet-behind-on"))
        check(!ParticleBoundsCalibration.permits("packet-origin","packet-origin"))
        for(mode in modes){check(ParticleBoundsCalibration.permits(mode,"off"));check(!ParticleBoundsCalibration.permits(mode,"arbitrary"))}

        // Decode actual immutable matching bytes, independently of front constants.
        val packet=ParticleCohortFixturePacket.packet(ParticleCohortFixturePacket.cases.single{it.name=="matching"})
        fun floatAt(byte:Int):Float {
            var value=0
            repeat(4){value=value or ((packet.get(AtlasPacket.byteOffset(byte+it)).toInt() and 255) shl(8*it))}
            return Float.fromBits(value)
        }
        val point=floatArrayOf(floatAt(192),floatAt(196),-floatAt(200))
        val translation=ParticleBoundsCalibration.frontTranslation()
        val hostCentre=floatArrayOf(0f,1.4f,1.5f)
        repeat(3){check(abs(translation[it]+hostCentre[it]-point[it])<.000001f)}

        // Both the uploaded mesh envelope and the existing host-branch vertices
        // must be behind for arbitrary viewer directions, not just default +Z.
        for(x in -1..1)for(y in -1..1)for(z in -1..1){
            if(x==0&&y==0&&z==0)continue
            val length=kotlin.math.sqrt((x*x+y*y+z*z).toFloat())
            val forward=floatArrayOf(x/length,y/length,z/length)
            val eye=floatArrayOf(.5f,1.65f,-.25f)
            val pose=BoundsProbePose.create(eye,forward)
            check(ParticleBoundsCalibration.behindRemainsValid(pose,eye,forward))
            check(pose.maximumHostDepth()< -4f)
            check(ParticleBoundsCalibration.maximumHostBranchDepth(pose)< -4f)
            check(!ParticleBoundsCalibration.behindRemainsValid(pose,eye,FloatArray(3){-forward[it]}))
        }
        println("Particle bounds calibration contract assertions passed; SDK execution and pixels unobserved")
    }
}
