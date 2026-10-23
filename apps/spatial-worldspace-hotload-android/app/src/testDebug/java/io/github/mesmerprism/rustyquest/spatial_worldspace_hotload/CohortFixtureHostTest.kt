package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

/** Wire construction assertions only; no CPU stand-in for workload.vert execution. */
object CohortFixtureHostTest {
    @JvmStatic fun main(args:Array<String>){
        check(CohortFixturePacket.cases.size==18)
        check(CohortFixturePacket.commands.size==37&&CohortFixturePacket.commands.toSet().size==37)
        for(case in CohortFixturePacket.cases){
            val packet=CohortFixturePacket.packet(case)
            fun word(dataByte:Int):Long {var value=0L;repeat(4){value=value or ((packet.get(AtlasPacket.byteOffset(dataByte+it)).toLong() and 255) shl (8*it))};return value}
            fun header(index:Int)=word(index*4)
            check(packet.capacity()==1024*1024*4&&packet.position()==0)
            check(header(0)==AtlasPacket.MAGIC.toLong()&&header(1)==4L&&header(46)==1L)
            check(header(4)==case.generation&&header(5)==case.frame&&header(47)==case.generation)
            check(header(6)==(if(case.headerGpu)8L else 0L))
            check(header(13)==40992L&&header(14)==3L&&header(15)==40995L&&header(16)==3L)
            check(header(40)==40998L&&header(42)+header(43)<=AtlasPacket.CAPACITY)
            for(first in listOf(CohortFixturePacket.HAND_RECORD,CohortFixturePacket.GRAFT_RECORD))repeat(3){corner->
                val base=(64+(first+corner)*8)*3
                check(word(base+12)==0L)
                check(word(base+16)==(if(case.allFramesStale||case.staleFrameCorner==corner)1L else case.frame))
                check(word(base+20)==when(corner){case.inactiveCorner->0L;case.staleGenerationCorner->1L;else->case.generation})
                check(Float.fromBits(word(base+8).toInt())== -1.5f)
            }
            for(offset in listOf(3,255,4095,1024*1024*4-1))check((packet.get(offset).toInt() and 255)==255)
        }
        check(CohortFixturePacket.cases.filter{it.expectedVisible}.map{it.name}==listOf("matching","highBits","signBit"))
        check(CohortFixturePacket.cases.filter{it.staleFrameCorner>=0}.map{it.staleFrameCorner}.toSet()==setOf(0,1,2))
        check(CohortFixturePacket.cases.filter{it.staleGenerationCorner>=0}.map{it.staleGenerationCorner}.toSet()==setOf(0,1,2))
        check(CohortFixturePacket.cases.filter{it.inactiveCorner>=0}.map{it.inactiveCorner}.toSet()==setOf(0,1,2))
        println("Cohort fixture byte construction passed; shader execution remains untested")
    }
}
