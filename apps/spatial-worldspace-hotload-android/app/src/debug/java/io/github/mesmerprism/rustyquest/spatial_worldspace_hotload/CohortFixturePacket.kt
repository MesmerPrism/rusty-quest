package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import java.nio.ByteBuffer

/** Synthetic static shader inputs. The GPU source tag here is a test bit, never producer evidence. */
internal object CohortFixturePacket {
    const val HAND_RECORD=40992
    const val GRAFT_RECORD=40995
    data class Case(val name:String,val frame:Long=65,val generation:Long=65537,
        val staleFrameCorner:Int= -1,val staleGenerationCorner:Int= -1,val inactiveCorner:Int= -1,
        val allFramesStale:Boolean=false,val expectedGeneration:Long=generation,
        val headerGpu:Boolean=true,val expectedGpu:Boolean=headerGpu,val expectedVisible:Boolean=false)
    val cases=listOf(
        Case("matching",expectedVisible=true),Case("stale64alias",allFramesStale=true),
        Case("mixedFrameCorner0",staleFrameCorner=0),Case("mixedFramePeer",staleFrameCorner=1),Case("mixedFrameCorner2",staleFrameCorner=2),
        Case("mixedGenerationCorner0",staleGenerationCorner=0),Case("mixedGenerationPeer",staleGenerationCorner=1),Case("mixedGenerationCorner2",staleGenerationCorner=2),
        Case("inactiveCorner0",inactiveCorner=0),Case("inactivePeer",inactiveCorner=1),Case("inactiveCorner2",inactiveCorner=2),
        Case("highBits",frame=0xffffffffL,generation=0xffffffffL,expectedVisible=true),
        Case("signBit",frame=0x80000000L,generation=0x80000000L,expectedVisible=true),
        Case("retiredExpectedGeneration",expectedGeneration=0),Case("restartStaleGeneration",expectedGeneration=65538),
        Case("expectedHigh16Mismatch",expectedGeneration=1),
        Case("cpuGpuGenerationAlias",generation=1,expectedGpu=false),
        Case("gpuCpuGenerationAlias",generation=1,headerGpu=false,expectedGpu=true))
    val commands=listOf("off")+listOf("hand","graft").flatMap{group->cases.map{"$group:${it.name}"}}
    fun packet(case:Case):ByteBuffer {
        require(case in cases)
        val bytes=ByteBuffer.allocateDirect(AtlasPacket.WIDTH*AtlasPacket.HEIGHT*4)
        repeat(AtlasPacket.WIDTH*AtlasPacket.HEIGHT){bytes.put(it*4+3,255.toByte())}
        fun wordAt(dataByte:Int,value:Long){require(value in 0..0xffffffffL);repeat(4){bytes.put(AtlasPacket.byteOffset(dataByte+it),(value ushr (it*8)).toByte())}}
        fun header(index:Int,value:Long)=wordAt(index*4,value)
        val fields=mapOf(0 to AtlasPacket.MAGIC.toLong(),1 to 4L,2 to 1024L,3 to 1024L,
            4 to case.generation,5 to case.frame,6 to (if(case.headerGpu)8L else 0L),
            7 to 2562L,8 to 17934L,9 to 0L,10 to 20496L,11 to 20496L,12 to 20496L,
            13 to HAND_RECORD.toLong(),14 to 3L,15 to GRAFT_RECORD.toLong(),16 to 3L,17 to 1L,
            27 to 1L,28 to 1L,29 to 1L,30 to 2f.toBits().toLong(),35 to 1L,39 to 16f.toBits().toLong(),
            40 to 40998L,41 to 2L,42 to 41000L,43 to 2L,46 to 1L,47 to case.generation)
        fields.forEach{(index,value)->header(index,value)}
        val positions=arrayOf(floatArrayOf(-.05f,1.3f,-1.5f),floatArrayOf(.05f,1.3f,-1.5f),floatArrayOf(0f,1.4f,-1.5f))
        for(first in listOf(HAND_RECORD,GRAFT_RECORD))repeat(3){corner->
            val base=(AtlasPacket.HEADER_TEXELS+(first+corner)*AtlasPacket.RECORD_TEXELS)*3
            repeat(3){axis->wordAt(base+axis*4,positions[corner][axis].toRawBits().toLong() and 0xffffffffL)}
            wordAt(base+12,0)
            wordAt(base+16,if(case.allFramesStale||case.staleFrameCorner==corner)1 else case.frame)
            wordAt(base+20,when(corner){case.inactiveCorner->0L;case.staleGenerationCorner->1L;else->case.generation})
        }
        bytes.position(0);return bytes
    }
}
