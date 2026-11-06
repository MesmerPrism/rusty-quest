package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload
import com.meta.spatial.runtime.*
/** Static primitive topology and record addresses; dynamic positions stay on GPU. */
object WorkloadGeometry {
    fun records(material: SceneMaterial, range: IntRange, billboards: Boolean): SceneMesh {
        return records(material,range.toList().toIntArray(),billboards)
    }
    fun records(material:SceneMaterial,records:IntArray,billboards:Boolean):SceneMesh {
        val count=records.size
        require(count>0&&count<=(if(billboards)16383 else 65532))
        require(records.all{it in 0 until AtlasPacket.CAPACITY}&&records.toSet().size==count)
        val vertices=count*if(billboards)4 else 1
        require(vertices<65535 && (billboards || vertices%3==0))
        if(!billboards)for(i in records.indices step 3)require(records[i+1]==records[i]+1&&records[i+2]==records[i]+2)
        val positions=FloatArray(vertices*3); val normals=FloatArray(vertices*3)
        val uv=FloatArray(vertices*2)
        val indices=IntArray(if(billboards) count*6 else count)
        val signs=arrayOf(floatArrayOf(-1f,-1f),floatArrayOf(1f,-1f),floatArrayOf(1f,1f),floatArrayOf(-1f,1f))
        for(i in 0 until count) {
            val address=AtlasPacket.uv(records[i])
            for(c in 0 until if(billboards) 4 else 1) {
                val v=if(billboards) i*4+c else i
                // Small finite host comparison grid. These static values never impersonate GPU vertices.
                positions[v*3]=if(billboards) signs[c][0] else ((i%3)-1)*.02f
                positions[v*3+1]=if(billboards) signs[c][1] else if(i%3==1) .02f else 0f
                normals[v*3+2]=1f; uv[v*2]=address.first; uv[v*2+1]=address.second
            }
            if(billboards) for(k in 0..5) indices[i*6+k]=i*4+intArrayOf(0,1,2,0,2,3)[k]
            else indices[i]=i
        }
        val mesh=TriangleMesh(vertices,indices.size,intArrayOf(0,indices.size),arrayOf(material))
        mesh.updateGeometry(0,positions,normals,uv,IntArray(vertices){-1});mesh.updatePrimitives(0,indices)
        return SceneMesh.fromTriangleMesh(mesh,false)
    }
    fun occluder(material:SceneMaterial):SceneMesh {
        val positions=floatArrayOf(-.5f,1.2f,1.15f,-.2f,1.2f,1.15f,-.2f,1.7f,1.15f,-.5f,1.7f,1.15f)
        val m=TriangleMesh(4,6,intArrayOf(0,6),arrayOf(material))
        m.updateGeometry(0,positions,FloatArray(12),FloatArray(8),IntArray(4){-1})
        m.updatePrimitives(0,intArrayOf(0,1,2,0,2,3));return SceneMesh.fromTriangleMesh(m,false)
    }
    fun diagnostic(material: SceneMaterial): SceneMesh {
        // 64 cells: low32 submitted frame, high32 generation. Positions are known host geometry.
        val p=FloatArray(64*12);val n=FloatArray(64*12);val u=FloatArray(64*8);val ids=IntArray(64*6)
        val signs=arrayOf(floatArrayOf(-1f,-1f),floatArrayOf(1f,-1f),floatArrayOf(1f,1f),floatArrayOf(-1f,1f))
        repeat(64){q->repeat(4){c->val v=q*4+c;p[v*3]=-.8f+(q%32)*.05f+signs[c][0]*.023f
            p[v*3+1]=2.0f+(q/32)*.06f+signs[c][1]*.026f;p[v*3+2]=1.6f
            n[v*3+2]=-1f;u[v*2]=q.toFloat();u[v*2+1]=c.toFloat()}
            repeat(6){k->ids[q*6+k]=q*4+intArrayOf(0,1,2,0,2,3)[k]}}
        val m=TriangleMesh(256,384,intArrayOf(0,384),arrayOf(material));m.updateGeometry(0,p,n,u,IntArray(256){-1});m.updatePrimitives(0,ids)
        return SceneMesh.fromTriangleMesh(m,false)
    }
}
