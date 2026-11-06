package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import com.meta.spatial.core.*
import com.meta.spatial.runtime.*
import com.meta.spatial.toolkit.*
import java.util.concurrent.CompletableFuture
import org.json.JSONObject

/** Independent fixed host geometry; never reads workload positions back to the CPU. */
@OptIn(SpatialSDKExperimentalAPI::class)
internal class AtlasDiagnosticController(activity:AppSystemActivity,texture:SceneTexture) {
    private val material=SceneMaterial.custom("atlas_diagnostic",arrayOf(
        SceneMaterialAttribute("atlas",SceneMaterialDataType.Texture2D),
        SceneMaterialAttribute("diagnosticFlags",SceneMaterialDataType.Vector4),
        SceneMaterialAttribute("publicCohort",SceneMaterialDataType.Vector4)))
    private var page=0
    private var profile="restored-normal"
    private var revision=0L
    private var producerGeneration=0L
    private var publicPrimaryOffset=0
    private val mesh:SceneMesh
    private val entity:Entity
    private val sceneObject:SceneObject
    init {
        material.setTexture("atlas",texture)
        material.setDepthTest(DepthTest.LESS_OR_EQUAL);material.setDepthWrite(DepthWrite.ENABLE)
        material.setBlendMode(BlendMode.OPAQUE);material.setSidedness(MaterialSidedness.DOUBLE_SIDED)
        flags()
        cohort(0L,0)
        val vertices=AtlasDiagnosticProtocol.FIXED_VERTICES
        val positions=FloatArray(vertices*3);val normals=FloatArray(vertices*3);val uv=FloatArray(vertices*2)
        val indices=IntArray(vertices/4*6)
        val signs=arrayOf(floatArrayOf(-1f,-1f),floatArrayOf(1f,-1f),floatArrayOf(1f,1f),floatArrayOf(-1f,1f))
        fun quad(cell:Int,x:Float,y:Float,halfWidth:Float,halfHeight:Float,column:Float,row:Float){
            repeat(4){corner->val v=cell*4+corner
                positions[v*3]=x+signs[corner][0]*halfWidth
                positions[v*3+1]=y+signs[corner][1]*halfHeight
                positions[v*3+2]=1.6f
                normals[v*3+2]=-1f;uv[v*2]=column;uv[v*2+1]=row}
            repeat(6){k->indices[cell*6+k]=cell*4+intArrayOf(0,1,2,0,2,3)[k]}
        }
        repeat(AtlasDiagnosticProtocol.ROWS){row->repeat(AtlasDiagnosticProtocol.COLUMNS){column->
            val cell=row*32+column
            quad(cell,-.465f+column*.030f,2.105f-row*.022f,.013f,.009f,column.toFloat(),row.toFloat())
        }}
        // Packet-independent colored centers and black/white guards. Nonoverlapping
        // planar quads avoid depth-order assumptions; exact geometry is in the decoder contract.
        var markerCell=AtlasDiagnosticProtocol.COLUMNS*AtlasDiagnosticProtocol.ROWS
        repeat(4){id->
            val x=if(id%2==0)-.60f else .60f;val y=if(id<2)2.24f else .76f;val row=100f+id
            fun ring(outer:Float,inner:Float,zone:Float){
                val mid=(outer+inner)*.5f;val half=(outer-inner)*.5f
                quad(markerCell++,x,y+mid,outer,half,zone,row);quad(markerCell++,x,y-mid,outer,half,zone,row)
                quad(markerCell++,x-mid,y,half,inner,zone,row);quad(markerCell++,x+mid,y,half,inner,zone,row)
            }
            ring(.074f,.062f,0f);ring(.062f,.050f,1f)
            quad(markerCell++,x,y,.050f,.050f,2f,row)
        }
        val triangle=TriangleMesh(vertices,indices.size,intArrayOf(0,indices.size),arrayOf(material))
        triangle.updateGeometry(0,positions,normals,uv,IntArray(vertices){-1});triangle.updatePrimitives(0,indices)
        mesh=SceneMesh.fromTriangleMesh(triangle,false)
        entity=Entity.create(listOf(Transform(Pose(Vector3(0f,0f,0f)))))
        sceneObject=SceneObject(activity.scene,mesh,"atlas-byte-diagnostic",entity).also{it.shouldFrustumCull(false)}
        activity.systemManager.findSystem<SceneObjectSystem>().addSceneObject(entity,CompletableFuture.completedFuture(sceneObject))
    }
    private fun flags(){
        material.setAttribute("diagnosticFlags",Vector4(if(profile.startsWith("restored"))1f else 0f,
            if(profile.endsWith("flipped"))1f else 0f,page.toFloat(),(revision and 255L).toFloat()))
    }
    fun texture(value:SceneTexture){material.setTexture("atlas",value)}
    fun cohort(generation:Long,primaryOffset:Int){
        val enabled=generation in 1L..0xffffffffL && primaryOffset in 0..AtlasPacket.CAPACITY-4
        producerGeneration=if(enabled)generation else 0L;publicPrimaryOffset=if(enabled)primaryOffset else 0
        material.setAttribute("publicCohort",Vector4((producerGeneration and 65535L).toFloat(),
            (producerGeneration ushr 16).toFloat(),publicPrimaryOffset.toFloat(),if(enabled)1f else 0f))
    }
    fun apply(method:String,value:String?):String {
        when(method){
            "diag-page"->{val next=value?.toIntOrNull();if(next==null||next !in 0 until AtlasDiagnosticProtocol.PAGES)return "rejected:diagnostic_page";page=next}
            "diag-profile"->{if(value !in AtlasDiagnosticProtocol.profiles)return "rejected:diagnostic_profile";profile=requireNotNull(value)}
            else->return "rejected:diagnostic_method"
        }
        revision++;flags();return "diagnostic_material_setters_invoked"
    }
    fun status():String=JSONObject().put("schema","rusty.quest.atlas_diagnostic.state.v1").put("page",page).put("profile",profile)
        .put("revision",revision).put("displayRevision8",revision and 255L).put("profileCode",AtlasDiagnosticProtocol.profiles.indexOf(profile))
        .put("expectedPublicProducerGeneration",producerGeneration).put("expectedPublicPrimaryOffset",publicPrimaryOffset)
        .put("publicCohortRequestedEnabled",producerGeneration!=0L).put("pages",AtlasDiagnosticProtocol.PAGES)
        .put("fixedHostVertices",AtlasDiagnosticProtocol.FIXED_VERTICES).put("calibrationFiducials",4)
        .put("materialIdentity",System.identityHashCode(material)).put("meshIdentity",System.identityHashCode(mesh))
        .put("entityIdentity",System.identityHashCode(entity)).put("objectIdentity",System.identityHashCode(sceneObject))
        .put("cpuHeaderReadbackBytes",0).put("sdkSampledProof",false).toString()
}
