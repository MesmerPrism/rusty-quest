package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Dependency-free range/bounds contract tests; synthetic, deliberately unequal rigs. */
object WorkloadTopologyHostTest {
    private fun rejects(block:()->Unit){check(runCatching(block).isFailure)}
    private fun descriptor(left:Int=300,right:Int=401):WorkloadDescriptor {
        val particles=2562+17934+2;val corners=(left+right)*3
        val layout=WorkloadLayout(2562,17934,0,particles,particles,particles*2,corners,particles*2+corners,corners*5,2)
        return WorkloadDescriptor(layout,HandTopology(500,left,610,right),"synthetic")
    }
    private fun metadata(d:WorkloadDescriptor):ByteArray=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply{
        putInt(0x31484752);putInt(1)
        putInt(0);putInt(d.hands.leftVertices);putInt(d.hands.leftTriangles);putInt(26);putInt(21)
        putInt(1);putInt(d.hands.rightVertices);putInt(d.hands.rightTriangles);putInt(26);putInt(21)
    }.array()
    private fun records(p:TopologyPlan,g:Int)=p.chunks.filter{it.group==g}.flatMap{it.records.toList()}
    @JvmStatic fun main(args:Array<String>){
        val d=descriptor();val l=d.layout;val h=d.hands
        check(HandTopology.decode(metadata(d),l)==h)
        for(offset in listOf(0,4,8,20,24,28,40,44)){
            val bytes=metadata(d);ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset,-1)
            rejects{HandTopology.decode(bytes,l)}
        }
        rejects{HandTopology.decode(metadata(d).copyOf(47),l)}
        rejects{HandTopology.decode(metadata(d),l.copy(handsCount=l.handsCount+3))}
        val p64=WorkloadTopology.plan("p64",d)
        check(p64.vertices==256&&p64.indices==384&&records(p64,0)==(0 until 64).toList())
        val full=WorkloadTopology.plan("retained",d)
        check(records(full,0)==(0 until l.particlesCount).toList())
        check(full.vertices==l.particlesCount*4&&full.indices==l.particlesCount*6)
        val tracer=WorkloadTopology.plan("t2562",d)
        check(records(tracer,0)==(0 until 5124).toList())
        check(records(WorkloadTopology.plan("hleft",d),1)==(l.handsOffset until l.handsOffset+h.leftCorners).toList())
        check(records(WorkloadTopology.plan("hright",d),1)==(l.handsOffset+h.leftCorners until l.graftsOffset).toList())
        val subset=records(WorkloadTopology.plan("h32",d),1)
        check(subset.size==192&&subset.take(96).all{it<l.handsOffset+h.leftCorners}&&subset.drop(96).all{it>=l.handsOffset+h.leftCorners})
        check(subset.chunked(3).all{it[1]==it[0]+1&&it[2]==it[0]+2})
        for(copies in listOf(1,2,5)){
            val plan=WorkloadTopology.plan("g$copies",d)
            val expected=(l.graftsOffset until l.graftsOffset+copies*h.leftCorners).toList()+
                (l.graftsOffset+5*h.leftCorners until l.graftsOffset+5*h.leftCorners+copies*h.rightCorners).toList()
            check(records(plan,2)==expected)
            check(records(plan,1)==(l.handsOffset until l.graftsOffset).toList())
        }
        val combined=WorkloadTopology.plan("cfull",d)
        check(combined.vertices==l.particlesCount*4+l.handsCount+l.graftsCount)
        check(combined.indices==l.particlesCount*6+l.handsCount+l.graftsCount)
        for(n in listOf(1,16383,16384,32767)){
            val chunks=WorkloadTopology.chunkRecords(0,IntArray(n){it})
            check(chunks.sumOf{it.records.size}==n&&chunks.all{it.vertices<65535})
        }
        val triangles=WorkloadTopology.chunkRecords(1,IntArray(65535){it})
        check(triangles.map{it.records.size}==listOf(65532,3))
        check(WorkloadTopology.chunkRecords(2,intArrayOf()).isEmpty())
        rejects{WorkloadTopology.chunkRecords(3,intArrayOf(0))}
        rejects{WorkloadTopology.chunkRecords(0,intArrayOf(-1))}
        rejects{WorkloadTopology.chunkRecords(0,intArrayOf(AtlasPacket.CAPACITY))}
        rejects{WorkloadTopology.chunkRecords(0,intArrayOf(1,1))}
        rejects{WorkloadTopology.chunkRecords(1,intArrayOf(1,2))}
        rejects{WorkloadTopology.chunkRecords(2,intArrayOf(1,3,4))}
        rejects{WorkloadTopology.plan("h256",descriptor(40,65))}
        rejects{WorkloadTopology.plan("unbounded",d)}
        check(WorkloadTopology.ladders.values.flatten().toSet()==WorkloadTopology.profiles.toSet())
        for(profile in WorkloadTopology.profiles){
            val plan=WorkloadTopology.plan(profile,d)
            check(plan.chunks.all{it.vertices<65535&&it.indices%3==0})
        }
        val pose=BoundsProbePose.create(floatArrayOf(3f,1.7f,-2f),floatArrayOf(0f,0f,-1f))
        check(abs(pose.maximumHostDepth()+8f)<.0001f)
        check(pose.maximumHostDepth(pose.eye,floatArrayOf(0f,0f,1f))>0)
        val diagonal=BoundsProbePose.create(floatArrayOf(0f,0f,0f),floatArrayOf(.7071f,.7071f,0f))
        check(diagonal.maximumHostDepth()< -6f)
        rejects{BoundsProbePose.create(floatArrayOf(Float.NaN,0f,0f),floatArrayOf(0f,0f,-1f))}
        rejects{BoundsProbePose.create(floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,0f))}
        check(PacketExpectation.fields(1,false)==(1f to 0f))
        check(PacketExpectation.fields(1,true)==(1f to 65536f))
        check(PacketExpectation.fields(0xffffffffL,true)==(65535f to 131071f))
        check(PacketExpectation.fields(0,true)==(0f to 65536f))
        rejects{PacketExpectation.fields(-1,true)};rejects{PacketExpectation.fields(0x100000000L,true)}
        println("Workload topology: asymmetric ranges, triplets, chunk limits, bounds controls and source-generation fields passed")
    }
}
