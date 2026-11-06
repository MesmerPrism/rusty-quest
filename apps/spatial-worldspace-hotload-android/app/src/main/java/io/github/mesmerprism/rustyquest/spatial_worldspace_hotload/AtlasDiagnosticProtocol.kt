package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

/** Fixed diagnostic geometry protocol. All rows use low bit first, left to right. */
@OptIn(ExperimentalUnsignedTypes::class)
object AtlasDiagnosticProtocol {
    const val COLUMNS=32
    const val ROWS=56
    const val PAGES=8
    const val FIDUCIAL_QUADS=36
    const val FIXED_VERTICES=(COLUMNS*ROWS+FIDUCIAL_QUADS)*4
    val profiles=listOf("raw-normal","restored-normal","raw-flipped","restored-flipped")
    val predicates=listOf("texture1024x1024","magicRWA4","version4","width1024","height1024","coordinateMapping1","sourceReady1",
        "mainCapacity2562","tracerCapacity17934","particlesOffset0","particlesCountAccepted","auxOffsetJoin","auxCountJoin",
        "handsOffsetJoin","handsBound","graftsBound","graftsJoin","totalBound","handsTriangles","graftsTriangles",
        "mainBudget","tracerBudget","producerGenerationNonzero","diagnosticJoin","diagnosticCount2","diagnosticAuxJoin",
        "diagnosticAuxCount2","radius2","positionBound16","handRecordLayout1","sourceStateGenerationJoin")
    /** Actual payload token address, independent of header validity admission. Null is unavailable. */
    fun payloadTokenByteOffset(h:UIntArray,row:Int):Int? {
        require(h.size==48 && row in 44..55)
        val endpoint=(row-44)/2;val section=endpoint/2
        val offset=h[intArrayOf(11,13,15)[section]].toLong()
        val count=h[intArrayOf(12,14,16)[section]].toLong()
        if(count==0L || offset>=AtlasPacket.CAPACITY || offset+count>AtlasPacket.CAPACITY)return null
        val record=offset+if(endpoint%2==0)0L else count-1
        return ((64+record*8)*3+16+((row-44)%2)*4).toInt()
    }
    fun headerIndex(page:Int,row:Int):Int {
        require(page in 0 until PAGES && row in 4..16)
        require(page<6 || row>=12 || (page==7 && row>=10))
        if(page==7 && row in 10..11)return 44+row-10
        return if(row<12)page*8+row-4 else intArrayOf(4,5,35,17,47)[row-12]
    }
    /** New pages read only the live, manifest-bound public diagnostic cohort. */
    fun publicWordByteOffset(h:UIntArray,width:Int,height:Int,generation:Long,primaryOffset:Int,page:Int,row:Int):Int? {
        require(h.size==48 && page in 6..7 && row in 4..11)
        if(generation !in 1L..0xffffffffL || primaryOffset !in 0..AtlasPacket.CAPACITY-4)return null
        if(!checks(h,width,height).all{it} || h[4].toLong()!=generation || (h[6] and 8u)==0u ||
            h[40].toLong()!=primaryOffset.toLong() || h[40]>131060u || h[42]!=h[40]+2u || h[44]==0u || h[45]>2u)return null
        val slot=row-4
        return if(page==6)192+24*(h[42].toInt()+slot/4)+4*(slot%4)
            else if(slot<6)192+24*(h[40].toInt()+slot/3)+4*(slot%3)
            else 4*(44+slot-6)
    }
    fun bit(value:UInt,column:Int):Boolean {require(column in 0 until COLUMNS);return ((value shr column) and 1u)!=0u}
    fun anchorBit(page:Int,profile:Int,revision:Int,column:Int):Boolean {
        require(page in 0 until PAGES && profile in 0..3 && revision in 0..255 && column in 0 until COLUMNS)
        return when(column){in 0..7->true;in 8..15->false;in 16..18->bit(page.toUInt(),column-16)
            in 19..20->bit(profile.toUInt(),column-19);in 21..28->bit(revision.toUInt(),column-21);else->column!=30}
    }
    /** Host contract mirror of each individually rendered GLSL predicate. */
    fun checks(h:UIntArray,width:Int,height:Int):BooleanArray {
        require(h.size==48)
        return booleanArrayOf(width==1024&&height==1024,h[0]==0x34415752u,h[1]==4u,h[2]==1024u,h[3]==1024u,
            h[29]==1u,h[35]==1u,h[7]==2562u,h[8]==17934u,h[9]==0u,h[10]==20496u||h[10]==20498u,h[11]==h[10],h[12]==h[10],
            h[13]==h[10]+h[10],h[14]<131064u,h[16]<131064u,h[15]==h[13]+h[14],h[15]+h[16]<=131064u,h[14]%3u==0u,h[16]%3u==0u,
            h[25]<=2562u,h[26]<=17934u,h[4]!=0u,h[40]==h[15]+h[16],h[41]==2u,h[42]==h[40]+2u,h[43]==2u,
            h[30]==2f.toBits().toUInt(),h[39]==16f.toBits().toUInt(),h[46]==1u,h[47]==h[4])
    }
}
