package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import java.nio.ByteBuffer

/** App-owned visual projection of a scalar result; no code loading or computational state. */
object CpuFeedbackProjection {
    /** Signed representative modulo2^64; successful host work cannot fail in visual arithmetic. */
    fun nativeDelta(before:Long,after:Long):Long=after-before
    /** The trusted module's input + counter + delta and this projection both wrap modulo2^64. */
    fun dexDelta(input:Long,counterBefore:Long,result:Long):Long=result-input-counterBefore
    fun spacing(value:Long):Float=.12f+Math.floorMod(value,11L).toFloat()*.007f
    fun color(value:Long):IntArray=when(Math.floorMod(value,3L).toInt()) {
        0->intArrayOf(40,190,220);1->intArrayOf(245,140,35);else->intArrayOf(40,150,245)
    }
    fun apply(bytes:ByteBuffer,layout:WorkloadLayout,value:Long) {
        require(layout.publicDevelopment);layout.validate()
        require(bytes.capacity()==AtlasPacket.WIDTH*AtlasPacket.HEIGHT*4)
        val gap=spacing(value);val rgb=color(value)
        fun putData(index:Int,v:Int){bytes.put(AtlasPacket.byteOffset(index),v.toByte())}
        repeat(64){id->
            val base=(AtlasPacket.HEADER_TEXELS+(layout.particlesOffset+id)*AtlasPacket.RECORD_TEXELS)*3
            val position=floatArrayOf((id%8-3.5f)*gap,1.2f+(id/8)*.08f,-2f)
            repeat(3){axis->repeat(4){b->putData(base+axis*4+b,position[axis].toBits() ushr(b*8))}}
            repeat(3){putData(base+16+it,rgb[it])}
        }
        bytes.position(0)
    }
}
