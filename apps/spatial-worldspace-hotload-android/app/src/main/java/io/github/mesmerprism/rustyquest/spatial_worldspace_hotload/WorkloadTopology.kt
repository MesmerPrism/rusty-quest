package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Static, admitted rig topology only. No poses or particle state cross this interface. */
data class HandTopology(val leftVertices:Int,val leftTriangles:Int,val rightVertices:Int,val rightTriangles:Int) {
    val leftCorners:Int get()=leftTriangles*3
    val rightCorners:Int get()=rightTriangles*3
    fun validate(layout:WorkloadLayout) {
        if(layout.publicDevelopment){require(listOf(leftVertices,rightVertices,leftTriangles,rightTriangles).all{it==0});require(layout.handsCount==0&&layout.graftsCount==0);return}
        require(listOf(leftVertices,rightVertices,leftTriangles,rightTriangles).all{it in 1..10000})
        require(leftCorners.toLong()+rightCorners==layout.handsCount.toLong())
        require((leftCorners.toLong()+rightCorners)*5==layout.graftsCount.toLong())
    }
    companion object {
        fun decode(bytes:ByteArray,layout:WorkloadLayout):HandTopology {
            require(bytes.size==48)
            val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(b.int==0x31484752&&b.int==1)
            fun hand(expected:Int):Pair<Int,Int> {
                require(b.int==expected);val vertices=b.int;val triangles=b.int
                require(b.int==26&&b.int==21)
                return vertices to triangles
            }
            val left=hand(0);val right=hand(1)
            return HandTopology(left.first,left.second,right.first,right.second).also{it.validate(layout)}
        }
    }
}

data class WorkloadDescriptor(val layout:WorkloadLayout,val hands:HandTopology,val metadataSha256:String)

/** Each chunk is compact submitted topology, not a visibility mask over full allocation. */
data class RecordChunk(val group:Int,val records:IntArray) {
    val billboards:Boolean get()=group==0
    val vertices:Int get()=records.size*if(billboards)4 else 1
    val indices:Int get()=records.size*if(billboards)6 else 1
}
data class TopologyPlan(val profile:String,val chunks:List<RecordChunk>) {
    val vertices:Int get()=chunks.sumOf{it.vertices}
    val indices:Int get()=chunks.sumOf{it.indices}
    fun vertices(group:Int)=chunks.filter{it.group==group}.sumOf{it.vertices}
    fun indices(group:Int)=chunks.filter{it.group==group}.sumOf{it.indices}
}

object WorkloadTopology {
    val profiles=listOf("p64","p512","pfull","t2562","t8967","tfull","retained",
        "h32","h256","hleft","hright","hfull","g1","g2","g5","csmall","cfull")
    const val MAX_CACHED_VERTICES=1000000
    const val MAX_CACHED_INDICES=1500000
    val ladders=mapOf(
        "particles" to listOf("p64","p512","pfull","t2562","t8967","tfull","retained"),
        "hands" to listOf("h32","h256","hleft","hright","hfull"),
        "grafts" to listOf("hfull","g1","g2","g5"),
        "combined" to listOf("csmall","cfull"))

    fun plan(profile:String,descriptor:WorkloadDescriptor):TopologyPlan {
        if(descriptor.layout.publicDevelopment)require(profile in setOf("p64","p512","pfull")){"Public development scene has no hand, graft, tracer or retained simulation topology"}
        require(profile in profiles)
        val l=descriptor.layout;l.validate();descriptor.hands.validate(l)
        var main=0;var tracers=0;var retained=false;var triangles=0;var handSide=0;var copies=0
        when(profile) {
            "p64"->main=64;"p512"->main=512;"pfull"->main=l.mainCapacity
            "t2562"->{main=l.mainCapacity;tracers=2562}
            "t8967"->{main=l.mainCapacity;tracers=8967}
            "tfull"->{main=l.mainCapacity;tracers=l.tracerCapacity}
            "retained"->{main=l.mainCapacity;tracers=l.tracerCapacity;retained=true}
            "h32"->triangles=32;"h256"->triangles=256
            "hleft"->{triangles=-1;handSide=1};"hright"->{triangles=-1;handSide=2}
            "hfull"->triangles=-1
            "g1"->{triangles=-1;copies=1};"g2"->{triangles=-1;copies=2};"g5"->{triangles=-1;copies=5}
            "csmall"->{main=64;triangles=-1}
            "cfull"->{main=l.mainCapacity;tracers=l.tracerCapacity;retained=true;triangles=-1;copies=5}
        }
        require(main<=l.mainCapacity&&tracers<=l.tracerCapacity)
        if(retained)require(l.retainedParticleCapacity>0)
        val particles=ArrayList<Int>()
        repeat(main){particles.add(l.particlesOffset+it)}
        repeat(tracers){particles.add(l.particlesOffset+l.mainCapacity+it)}
        if(retained)repeat(l.retainedParticleCapacity){particles.add(l.particlesOffset+l.mainCapacity+l.tracerCapacity+it)}
        val hands=ArrayList<Int>();val grafts=ArrayList<Int>()
        fun addTriangles(out:MutableList<Int>,start:Int,total:Int,selected:Int) {
            require(total>0&&selected in 0..total)
            repeat(selected){i->val t=(i.toLong()*total/selected).toInt();repeat(3){out.add(start+t*3+it)}}
        }
        val h=descriptor.hands
        if(triangles!=0){
            if(handSide!=2)addTriangles(hands,l.handsOffset,h.leftTriangles,if(triangles<0)h.leftTriangles else triangles)
            if(handSide!=1)addTriangles(hands,l.handsOffset+h.leftCorners,h.rightTriangles,if(triangles<0)h.rightTriangles else triangles)
        }
        repeat(copies){k->addTriangles(grafts,l.graftsOffset+k*h.leftCorners,h.leftTriangles,h.leftTriangles)}
        repeat(copies){k->addTriangles(grafts,l.graftsOffset+5*h.leftCorners+k*h.rightCorners,h.rightTriangles,h.rightTriangles)}
        val chunks=chunkRecords(0,particles.toIntArray())+chunkRecords(1,hands.toIntArray())+chunkRecords(2,grafts.toIntArray())
        return TopologyPlan(profile,chunks)
    }

    fun chunkRecords(group:Int,records:IntArray):List<RecordChunk> {
        require(group in 0..2&&records.all{it in 0 until AtlasPacket.CAPACITY})
        require(records.toSet().size==records.size)
        if(group!=0){require(records.size%3==0);for(i in records.indices step 3)require(records[i+1]==records[i]+1&&records[i+2]==records[i]+2)}
        val limit=if(group==0)16383 else 65532
        return (records.indices step limit).map{first->RecordChunk(group,records.copyOfRange(first,minOf(first+limit,records.size)))}
    }
}

/** A conservative envelope of the actual uploaded placeholder vertices, not decoded GPU positions. */
data class BoundsProbePose(val eye:FloatArray,val forward:FloatArray,val translation:FloatArray) {
    fun maximumHostDepth(currentEye:FloatArray=eye,currentForward:FloatArray=forward):Float {
        require(currentEye.size==3&&currentForward.size==3&&currentEye.all{it.isFinite()}&&currentForward.all{it.isFinite()})
        var maximum=Float.NEGATIVE_INFINITY
        for(x in listOf(-1f,1f))for(y in listOf(-1f,1f)) {
            val corner=floatArrayOf(x,y,0f)
            val depth=(0..2).sumOf{((translation[it]+corner[it]-currentEye[it])*currentForward[it]).toDouble()}.toFloat()
            maximum=maxOf(maximum,depth)
        }
        return maximum
    }
    companion object {
        fun create(eye:FloatArray,forward:FloatArray):BoundsProbePose {
            require(eye.size==3&&forward.size==3&&eye.all{it.isFinite()}&&forward.all{it.isFinite()})
            val length=sqrt(forward.sumOf{(it*it).toDouble()}).toFloat();require(length in .5f..1.5f)
            val f=FloatArray(3){forward[it]/length}
            val pose=BoundsProbePose(eye.copyOf(),f,FloatArray(3){eye[it]-8f*f[it]})
            require(pose.maximumHostDepth()< -4f)
            return pose
        }
    }
}

/** Two exact uint16 halves plus source tag fit the existing material ABI. */
object PacketExpectation {
    fun fields(generation:Long,gpu:Boolean):Pair<Float,Float> {
        require(generation in 0..0xffffffffL)
        return (generation and 65535).toFloat() to ((generation ushr 16)+(if(gpu)65536 else 0)).toFloat()
    }
}
