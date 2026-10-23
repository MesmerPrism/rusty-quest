package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mirrors atlas_packet.h. These are generic GPU transport bytes, not simulation policy. */
object AtlasPacket {
    const val WIDTH=1024; const val HEIGHT=1024; const val HEADER_TEXELS=64
    const val RECORD_TEXELS=8; const val RECORD_BYTES=24; const val CAPACITY=131064
    const val MAGIC=0x34415752; const val VERSION=4; const val HAND_RECORD_LAYOUT=1
    const val ACTIVE=1; const val INVALID=2; const val TRACER=4; const val POSE_INVALID=8
    const val PARTICLES_OFFSET=9; const val PARTICLES_COUNT=10; const val AUX_OFFSET=11; const val AUX_COUNT=12
    const val HANDS_OFFSET=13; const val HANDS_COUNT=14; const val GRAFTS_OFFSET=15; const val GRAFTS_COUNT=16
    fun texel(record:Int):Pair<Int,Int> {
        require(record in 0 until CAPACITY)
        val index=HEADER_TEXELS+record*RECORD_TEXELS
        return Pair(index%WIDTH,index/WIDTH)
    }
    fun uv(record:Int):Pair<Float,Float> { val (x,y)=texel(record);return Pair((x+.5f)/WIDTH,(y+.5f)/HEIGHT) }
    fun chunks(first:Int,count:Int,verticesPerRecord:Int):List<IntRange> {
        require(first>=0 && count>=0 && first.toLong()+count<=CAPACITY)
        require(verticesPerRecord in listOf(1,4))
        val perChunk=if(verticesPerRecord==1) 65532 else 16383
        val result=mutableListOf<IntRange>();var offset=0
        while(offset<count){val n=minOf(count-offset,perChunk);if(verticesPerRecord==1)require(n%3==0)
            result.add((first+offset) until (first+offset+n));offset+=n}
        return result
    }
    fun byteOffset(dataByte:Int):Int = (dataByte/3)*4+dataByte%3
    fun readHeader(bytes:ByteArray,word:Int):Int {
        require(word in 0..47);require(bytes.size>=HEADER_TEXELS*4)
        var value=0;repeat(4){value=value or ((bytes[byteOffset(word*4+it)].toInt() and 255) shl (it*8))};return value
    }
    /** Small fixed CPU reference, deliberately not a private workload/simulation. */
    fun reference(manifest:WorkloadLayout):ByteBuffer {
        val bytes=ByteBuffer.allocateDirect(WIDTH*HEIGHT*4).order(ByteOrder.LITTLE_ENDIAN)
        for(i in 0 until WIDTH*HEIGHT)bytes.put(i*4+3,255.toByte())
        fun putData(index:Int,value:Int){bytes.put(byteOffset(index),value.toByte())}
        fun word(i:Int,v:Int){repeat(4){putData(i*4+it,v ushr (it*8))}}
        word(0,MAGIC);word(1,VERSION);word(2,WIDTH);word(3,HEIGHT);word(4,1)
        word(7,manifest.mainCapacity);word(8,manifest.tracerCapacity)
        word(9,manifest.particlesOffset);word(10,manifest.particlesCount);word(11,manifest.auxiliaryOffset);word(12,manifest.particlesCount)
        word(13,manifest.handsOffset);word(14,manifest.handsCount);word(15,manifest.graftsOffset);word(16,manifest.graftsCount)
        word(40,manifest.graftsOffset+manifest.graftsCount);word(41,2);word(42,manifest.graftsOffset+manifest.graftsCount+2);word(43,2)
        word(25,64);word(29,1);word(30,2f.toBits());word(35,1);word(39,16f.toBits())
        word(46,HAND_RECORD_LAYOUT);word(47,1)
        repeat(64){id->
            val record=manifest.particlesOffset+id
            val base=(HEADER_TEXELS+record*RECORD_TEXELS)*3
            val p=floatArrayOf((id%8-3.5f)*.15f,1.2f+(id/8)*.08f,-2f)
            repeat(3){axis->repeat(4){b->putData(base+axis*4+b,p[axis].toBits() ushr (b*8))}}
            putData(base+16,40);putData(base+17,190);putData(base+18,220);putData(base+19,255)
            val rad=(.035f/2f*65535).toInt();putData(base+20,rad);putData(base+21,rad ushr 8);putData(base+22,ACTIVE)
            val auxBase=(HEADER_TEXELS+(manifest.auxiliaryOffset+id)*RECORD_TEXELS)*3
            repeat(4){putData(auxBase+16+it,0);putData(auxBase+20+it,1 ushr (it*8))}
        }
        bytes.position(0);return bytes
    }
}
data class WorkloadLayout(val mainCapacity:Int,val tracerCapacity:Int,val particlesOffset:Int,val particlesCount:Int,
    val auxiliaryOffset:Int,val handsOffset:Int,val handsCount:Int,val graftsOffset:Int,val graftsCount:Int,
    val retainedParticleCapacity:Int=0,val publicDevelopment:Boolean=false) {
    fun validate(){
        if(publicDevelopment)require(mainCapacity==512&&tracerCapacity==0&&handsCount==0&&graftsCount==0&&retainedParticleCapacity==0)
        else require(mainCapacity==2562 && tracerCapacity==17934)
        require(retainedParticleCapacity in listOf(0,2))
        require(particlesCount==mainCapacity+tracerCapacity+retainedParticleCapacity && particlesOffset==0 && auxiliaryOffset==particlesCount)
        require(handsOffset==auxiliaryOffset+particlesCount && graftsOffset==handsOffset+handsCount)
        require(handsCount>=0 && graftsCount>=0 && handsOffset>=0 && graftsOffset>=0)
        require(handsCount%3==0 && graftsCount%3==0 && graftsOffset.toLong()+graftsCount+4<=AtlasPacket.CAPACITY)
    }
}
