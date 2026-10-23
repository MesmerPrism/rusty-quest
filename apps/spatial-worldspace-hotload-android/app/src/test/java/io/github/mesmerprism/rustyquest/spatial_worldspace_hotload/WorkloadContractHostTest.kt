package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload
/** Actual dependency-free atlas/layout sources, run by Test-WorkloadContract.ps1. */
object WorkloadContractHostTest {
    @JvmStatic fun main(args:Array<String>){
        val layout=WorkloadLayout(2562,17934,0,20496,20496,40992,13884,54876,69420);layout.validate()
        val retained=WorkloadLayout(2562,17934,0,20498,20498,40996,13884,54880,69420,2);retained.validate()
        check(retained.graftsOffset+retained.graftsCount+4==124304)
        check(AtlasPacket.chunks(0,retained.particlesCount,4).sumOf{it.count()}==20498)
        check(AtlasPacket.CAPACITY==((1024*1024-64)/8))
        check(AtlasPacket.texel(0)==Pair(64,0));check(AtlasPacket.texel(120)==Pair(0,1))
        val last=AtlasPacket.texel(AtlasPacket.CAPACITY-1);check(last==Pair(1016,1023))
        for(n in listOf(0,1,120,20495,20496,40992,124295,131063)){
            val uv=AtlasPacket.uv(n);val x=kotlin.math.round(uv.first*1024-.5f).toInt()
            val y=kotlin.math.round(uv.second*1024-.5f).toInt();check((y*1024+x-64)/8==n)
        }
        val particleChunks=AtlasPacket.chunks(0,20496,4);check(particleChunks.sumOf{it.count()}==20496)
        check(particleChunks.all{it.count()*4<65535})
        val triangles=AtlasPacket.chunks(54876,69420,1);check(triangles.size==2)
        check(triangles.all{it.count()<65535&&it.count()%3==0});check(triangles.sumOf{it.count()}==69420)
        val buffer=AtlasPacket.reference(layout);val bytes=ByteArray(buffer.remaining());buffer.get(bytes)
        check(AtlasPacket.readHeader(bytes,0)==AtlasPacket.MAGIC);check(AtlasPacket.readHeader(bytes,29)==1)
        check(AtlasPacket.readHeader(bytes,25)==64);check(AtlasPacket.readHeader(bytes,35)==1)
        check(AtlasPacket.readHeader(bytes,30)==2f.toBits());check(AtlasPacket.readHeader(bytes,39)==16f.toBits())
        check((3 until bytes.size step 4).all{(bytes[it].toInt() and 255)==255})
        fun word(record:Int,offset:Int):Int {val base=(64+record*8)*3+offset;var v=0
            repeat(4){v=v or ((bytes[AtlasPacket.byteOffset(base+it)].toInt() and 255) shl (it*8))};return v}
        repeat(64){id->check(Float.fromBits(word(id,8))==-2f)
            check(word(id,20) ushr 16==AtlasPacket.ACTIVE)
            check(word(20496+id,16)==0&&word(20496+id,20)==1)}
        check(AtlasPacket.readHeader(bytes,1)==4);check(AtlasPacket.MAGIC==0x34415752)
        check(AtlasPacket.readHeader(bytes,46)==1);check(AtlasPacket.readHeader(bytes,47)==1)
        check(AtlasPacket.readHeader(bytes,40)==124296);check(AtlasPacket.readHeader(bytes,41)==2)
        check(AtlasPacket.readHeader(bytes,42)==124298);check(AtlasPacket.readHeader(bytes,43)==2)
        repeat(4){check(word(124296+it,20)==0)} // diagnostic records remain inactive on CPU reference
        check(word(64,20)==0);check(word(40992,20)==0) // no fake hand geometry in CPU reference
        // Wire roundtrip including sign/high bits and the old six-bit alias. This
        // tests byte transport only; actual shader rejection needs GPU fixtures.
        for(token in listOf(0,1,64,65,Int.MAX_VALUE,Int.MIN_VALUE,-1)){
            for(headerWord in listOf(4,5,47)){
                repeat(4){bytes[AtlasPacket.byteOffset(headerWord*4+it)]=(token ushr (8*it)).toByte()}
                check(AtlasPacket.readHeader(bytes,headerWord)==token)
            }
        }
        fun rejected(block:()->Unit){check(runCatching(block).isFailure)}
        rejected{AtlasPacket.texel(-1)};rejected{AtlasPacket.texel(AtlasPacket.CAPACITY)}
        rejected{AtlasPacket.chunks(0,4,1)};rejected{layout.copy(handsCount=-3).validate()}
        rejected{layout.copy(graftsCount=100000).validate()};rejected{layout.copy(auxiliaryOffset=0).validate()}
        rejected{retained.copy(retainedParticleCapacity=0).validate()}
        rejected{retained.copy(retainedParticleCapacity=1).validate()}
        rejected{retained.copy(handsOffset=40992).validate()}
        println("PASS atlas-v4: address boundaries/UV roundtrip/RGB header/reference float32+alpha/aux frame-generation/static chunk budgets/invalid layouts")
    }
}
