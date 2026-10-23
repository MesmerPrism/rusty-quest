package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import java.nio.ByteBuffer

/** Public synthetic particle transport vectors; never private simulation data. */
internal object ParticleCohortFixturePacket {
    const val PRIMARY_RECORD=0
    const val AUX_RECORD=20496
    const val RADIUS_WORD=1475 // Approximately .045 metres with the public two-metre encoding.
    data class Case(val name:String,val frame:Long=65,val generation:Long=65537,
        val auxFrame:Long=frame,val auxGeneration:Long=generation,
        val tagMismatch:Boolean=false,val zeroPrimary:Boolean=false,val active:Boolean=true,val invalid:Boolean=false,
        val rotationBits:Long=0,val animationBits:Long=0,
        val expectedGeneration:Long=generation,val headerGpu:Boolean=true,val expectedGpu:Boolean=headerGpu,
        val expectedVisible:Boolean=false)
    val cases=listOf(
        Case("matching",expectedVisible=true),
        Case("auxFrame64Alias",auxFrame=1),Case("auxGenerationLow16Alias",auxGeneration=1),Case("auxGenerationZero",auxGeneration=0),
        Case("primaryTagMismatch",tagMismatch=true),Case("primaryZero",zeroPrimary=true),
        Case("primaryInactive",active=false),Case("primaryInvalid",invalid=true),
        Case("auxRotationNaN",rotationBits=0x7fc00000),Case("auxAnimationInfinity",animationBits=0x7f800000),
        Case("retiredExpectedGeneration",expectedGeneration=0),Case("restartStaleGeneration",expectedGeneration=65538),
        Case("expectedHigh16Mismatch",expectedGeneration=1),
        Case("cpuGpuGenerationAlias",generation=1,expectedGpu=false),
        Case("gpuCpuGenerationAlias",generation=1,headerGpu=false,expectedGpu=true),
        Case("highBits",frame=0xffffffffL,generation=0xffffffffL,expectedVisible=true),
        Case("signBit",frame=0x80000000L,generation=0x80000000L,expectedVisible=true))
    val commands=listOf("off")+cases.map{it.name}
    fun packet(case:Case):ByteBuffer {
        require(case in cases)
        val bytes=ByteBuffer.allocateDirect(1024*1024*4)
        repeat(1024*1024){bytes.put(it*4+3,255.toByte())}
        fun wordAt(dataByte:Int,value:Long){require(value in 0..0xffffffffL);repeat(4){bytes.put(AtlasPacket.byteOffset(dataByte+it),(value ushr (8*it)).toByte())}}
        val headers=mapOf(0 to AtlasPacket.MAGIC.toLong(),1 to 4L,2 to 1024L,3 to 1024L,
            4 to case.generation,5 to case.frame,6 to (if(case.headerGpu)8L else 0L),
            7 to 2562L,8 to 17934L,9 to 0L,10 to 20496L,11 to 20496L,12 to 20496L,
            13 to 40992L,14 to 0L,15 to 40992L,16 to 0L,17 to 1L,25 to 1L,
            29 to 1L,30 to 2f.toBits().toLong(),35 to 1L,39 to 16f.toBits().toLong(),
            40 to 40992L,41 to 2L,42 to 40994L,43 to 2L,46 to 1L,47 to case.generation)
        headers.forEach{(index,value)->wordAt(index*4,value)}
        val primary=AtlasPacket.HEADER_TEXELS*3
        if(!case.zeroPrimary){
            val position=floatArrayOf(.65f,1.35f,-1f)
            repeat(3){wordAt(primary+it*4,position[it].toRawBits().toLong() and 0xffffffffL)}
            wordAt(primary+12,0)
            wordAt(primary+16,0xff40e8ffL) // Warm yellow, fully opaque, in byte RGBA order.
            val tag=(case.frame+(if(case.tagMismatch)1 else 0)) and 63L
            val flags=(tag shl 10) or (if(case.active)1L else 0L) or (if(case.invalid)2L else 0L)
            wordAt(primary+20,(flags shl 16) or RADIUS_WORD.toLong())
        }
        val auxiliary=(AtlasPacket.HEADER_TEXELS+AUX_RECORD*AtlasPacket.RECORD_TEXELS)*3
        wordAt(auxiliary,case.rotationBits);wordAt(auxiliary+4,case.animationBits)
        wordAt(auxiliary+16,case.auxFrame);wordAt(auxiliary+20,case.auxGeneration)
        bytes.position(0);return bytes
    }
    fun whiteMask():ByteBuffer=ByteBuffer.allocateDirect(1024*1024*4).apply{
        repeat(capacity()){put(it,255.toByte())};position(0)
    }
}
