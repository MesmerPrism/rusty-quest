package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

/** Actual protocol source tests. They do not claim GPU shader execution. */
@OptIn(ExperimentalUnsignedTypes::class)
object AtlasDiagnosticHostTest {
    @JvmStatic fun main(args:Array<String>){
        val h=UIntArray(48)
        h[0]=0x34415752u;h[1]=4u;h[2]=1024u;h[3]=1024u;h[4]=1u
        h[7]=2562u;h[8]=17934u;h[10]=20496u;h[11]=20496u;h[12]=20496u;h[13]=40992u
        h[14]=13884u;h[15]=54876u;h[16]=69420u;h[25]=2562u;h[26]=17934u;h[29]=1u
        h[30]=2f.toBits().toUInt();h[35]=1u;h[39]=16f.toBits().toUInt();h[40]=124296u;h[41]=2u;h[42]=124298u;h[43]=2u;h[46]=1u;h[47]=1u
        check(AtlasDiagnosticProtocol.checks(h,1024,1024).all{it})
        val retained=h.copyOf();retained[10]=20498u;retained[11]=20498u;retained[12]=20498u
        for(i in listOf(13,15,40,42))retained[i]+=4u
        check(AtlasDiagnosticProtocol.checks(retained,1024,1024).all{it})
        val endpoints=listOf(20496,40991,40992,54875,54876,124295)
        for((index,record) in endpoints.withIndex())for(field in 0..1)
            check(AtlasDiagnosticProtocol.payloadTokenByteOffset(h,44+index*2+field)==(64+record*8)*3+16+field*4)
        val empty=h.copyOf();empty[14]=0u
        check((48..51).all{AtlasDiagnosticProtocol.payloadTokenByteOffset(empty,it)==null})
        val overflow=h.copyOf();overflow[13]=UInt.MAX_VALUE
        check(AtlasDiagnosticProtocol.payloadTokenByteOffset(overflow,48)==null)
        val failures=mapOf(0 to 1,1 to 2,2 to 3,3 to 4,29 to 5,35 to 6,7 to 7,8 to 8,9 to 9,10 to 10,11 to 11,12 to 12,13 to 13,4 to 22,40 to 23,41 to 24,42 to 25,43 to 26,30 to 27,39 to 28,46 to 29,47 to 30)
        failures.forEach{(word,predicate)->val wrong=h.copyOf();wrong[word]=if(word==4)0u else h[word]+1u
            check(!AtlasDiagnosticProtocol.checks(wrong,1024,1024)[predicate])}
        for((word,value,predicate) in listOf(Triple(14,131064u,14),Triple(16,131064u,15),Triple(15,54877u,16),
            Triple(16,80000u,17),Triple(14,13885u,18),Triple(16,69421u,19),Triple(25,2563u,20),Triple(26,17935u,21))){
            val wrong=h.copyOf();wrong[word]=value;check(!AtlasDiagnosticProtocol.checks(wrong,1024,1024)[predicate])
        }
        val wrongDimensions=AtlasDiagnosticProtocol.checks(h,16,4);check(!wrongDimensions[0]&&wrongDimensions.drop(1).all{it})
        for(page in 0..5)for(row in 4..11)check(AtlasDiagnosticProtocol.headerIndex(page,row)==page*8+row-4)
        for(page in 0..7)check((12..16).map{AtlasDiagnosticProtocol.headerIndex(page,it)}==listOf(4,5,35,17,47))
        check(AtlasDiagnosticProtocol.headerIndex(7,10)==44&&AtlasDiagnosticProtocol.headerIndex(7,11)==45)
        val publicHeader=h.copyOf();publicHeader[6]=8u;publicHeader[44]=1u
        fun address(packet:UIntArray=publicHeader,page:Int=6,row:Int=4,generation:Long=1L,offset:Int=124296,width:Int=1024)=
            AtlasDiagnosticProtocol.publicWordByteOffset(packet,width,1024,generation,offset,page,row)
        for(slot in 0..7)check(address(row=slot+4)==192+24*(124298+slot/4)+4*(slot%4))
        for(slot in 0..5)check(address(page=7,row=slot+4)==192+24*(124296+slot/3)+4*(slot%3))
        check(address(page=7,row=10)==176&&address(page=7,row=11)==180)
        for(word in listOf(0,1,4,6,10,13,14,15,16,35,40,41,42,43,44,47)){
            val wrong=publicHeader.copyOf();wrong[word]=if(word==6||word==35||word==44)0u else publicHeader[word]+1u
            check(address(packet=wrong)==null)
        }
        check(address(generation=0L)==null&&address(generation=2L)==null&&address(offset=124295)==null&&address(width=16)==null)
        val alias=publicHeader.copyOf();alias[14]=0u;alias[15]=40992u;alias[16]=0u;alias[40]=40992u;alias[42]=40994u
        check(address(packet=alias)==null) // self-consistent forged ranges cannot alias private records
        for(word in listOf(13,14,15,16,40,42)){val wrong=publicHeader.copyOf();wrong[word]=UInt.MAX_VALUE;check(address(packet=wrong)==null)}
        val high=publicHeader.copyOf();high[4]=0x80001234u;high[47]=high[4]
        check(address(packet=high,generation=0x80001234L)!=null)
        val shifted=retained.copyOf();shifted[6]=8u;shifted[44]=2u;shifted[45]=2u
        check(address(packet=shifted,offset=124300)==192+24*124302)
        for(column in 0..31){val value=1u shl column;check(AtlasDiagnosticProtocol.bit(value,column));check((0..31).filter{it!=column}.none{AtlasDiagnosticProtocol.bit(value,it)})}
        for(page in 0..7)for(profile in 0..3)for(revision in listOf(0,1,127,128,255)){
            fun decode(first:Int,bits:Int):Int=(0 until bits).sumOf{if(AtlasDiagnosticProtocol.anchorBit(page,profile,revision,first+it))1 shl it else 0}
            check(decode(16,3)==page&&decode(19,2)==profile&&decode(21,8)==revision)
            check((0..7).all{AtlasDiagnosticProtocol.anchorBit(page,profile,revision,it)})
            check((8..15).none{AtlasDiagnosticProtocol.anchorBit(page,profile,revision,it)})
            check((29..31).map{AtlasDiagnosticProtocol.anchorBit(page,profile,revision,it)}==listOf(true,false,true))
        }
        check(runCatching{AtlasDiagnosticProtocol.headerIndex(6,4)}.isFailure)
        check(runCatching{AtlasDiagnosticProtocol.headerIndex(0,17)}.isFailure)
        check(runCatching{AtlasDiagnosticProtocol.bit(0u,32)}.isFailure)
        println("PASS atlas diagnostic: valid header / wrong fields / independent dimensions / 48 page words / persistent words / all32 bit positions / bounds")
    }
}
