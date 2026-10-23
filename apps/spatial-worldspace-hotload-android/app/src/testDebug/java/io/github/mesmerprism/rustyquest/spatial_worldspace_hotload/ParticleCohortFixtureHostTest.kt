package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

/** Actual synthetic packet bytes and fixture coverage, never an emulated shader result. */
object ParticleCohortFixtureHostTest {
    @JvmStatic fun main(args:Array<String>){
        check(ParticleCohortFixturePacket.cases.size==17)
        check(ParticleCohortFixturePacket.commands.size==18&&ParticleCohortFixturePacket.commands.toSet().size==18)
        val observed=linkedMapOf<String,List<Long>>()
        for(case in ParticleCohortFixturePacket.cases){
            val packet=ParticleCohortFixturePacket.packet(case)
            fun word(dataByte:Int):Long {
                var value=0L
                repeat(4){value=value or ((packet.get(AtlasPacket.byteOffset(dataByte+it)).toLong() and 255) shl (8*it))}
                return value
            }
            fun header(index:Int)=word(index*4)
            check(packet.capacity()==4194304&&packet.position()==0)
            check(header(0)==0x34415752L&&header(1)==4L&&header(2)==1024L&&header(3)==1024L)
            check(header(4)==case.generation&&header(5)==case.frame&&header(47)==case.generation)
            check(header(7)==2562L&&header(8)==17934L&&header(9)==0L)
            check(header(10)==20496L&&header(11)==20496L&&header(12)==20496L)
            check(header(13)==40992L&&header(14)==0L&&header(15)==40992L&&header(16)==0L)
            check(header(25)==1L&&header(26)==0L&&header(27)==0L&&header(28)==0L)
            check(header(29)==1L&&header(35)==1L&&header(46)==1L)
            check(header(30)==0x40000000L&&header(39)==0x41800000L)
            check(header(40)==40992L&&header(41)==2L&&header(42)==40994L&&header(43)==2L)
            check(header(6)==(if(case.headerGpu)8L else 0L))
            val p=192;val a=492096
            if(case.zeroPrimary){repeat(6){check(word(p+it*4)==0L)}}else{
                check(Float.fromBits(word(p).toInt())==.65f&&Float.fromBits(word(p+4).toInt())==1.35f&&Float.fromBits(word(p+8).toInt())== -1f)
                check(word(p+12)==0L&&word(p+16)==0xff40e8ffL)
                check((word(p+20) and 65535)==1475L)
            }
            check(word(a)==case.rotationBits&&word(a+4)==case.animationBits&&word(a+8)==0L&&word(a+12)==0L)
            check(word(a+16)==case.auxFrame&&word(a+20)==case.auxGeneration)
            observed[case.name]=listOf(word(p+20),word(a),word(a+4),word(a+16),word(a+20))
            val fields=PacketExpectation.fields(case.expectedGeneration,case.expectedGpu)
            check(fields.first.toLong()+((fields.second.toLong() and 65535) shl 16)==case.expectedGeneration)
            check(((fields.second.toLong() and 65536)!=0L)==case.expectedGpu)
            for(offset in listOf(3,255,4095,4194303))check((packet.get(offset).toInt() and 255)==255)
        }
        check(observed.getValue("matching")==listOf(0x040105c3L,0L,0L,65L,65537L))
        check(observed.getValue("auxFrame64Alias")==listOf(0x040105c3L,0L,0L,1L,65537L))
        check(observed.getValue("auxGenerationLow16Alias")==listOf(0x040105c3L,0L,0L,65L,1L))
        check(observed.getValue("auxGenerationZero").last()==0L)
        check(observed.getValue("primaryTagMismatch").first()==0x080105c3L)
        check(observed.getValue("primaryInactive").first()==0x040005c3L)
        check(observed.getValue("primaryInvalid").first()==0x040305c3L)
        check(observed.getValue("auxRotationNaN")[1]==0x7fc00000L)
        check(observed.getValue("auxAnimationInfinity")[2]==0x7f800000L)
        check(observed.getValue("highBits")==listOf(0xfc0105c3L,0L,0L,0xffffffffL,0xffffffffL))
        check(observed.getValue("signBit")==listOf(0x000105c3L,0L,0L,0x80000000L,0x80000000L))
        val mask=ParticleCohortFixturePacket.whiteMask()
        check(mask.capacity()==4194304&&mask.position()==0)
        repeat(mask.capacity()){check((mask.get(it).toInt() and 255)==255)}
        check(ParticleCohortFixturePacket.cases.filter{it.expectedVisible}.map{it.name}==listOf("matching","highBits","signBit"))
        check(runCatching{ParticleCohortFixturePacket.packet(ParticleCohortFixturePacket.Case("unknown"))}.isFailure)
        println("Particle cohort wire bytes, AUX/primary coverage and white mask passed; shader execution remains untested")
    }
}
