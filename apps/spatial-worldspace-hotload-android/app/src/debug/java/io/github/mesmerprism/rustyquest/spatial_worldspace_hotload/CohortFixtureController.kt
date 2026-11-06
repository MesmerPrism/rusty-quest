package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import com.meta.spatial.core.*
import com.meta.spatial.runtime.*
import com.meta.spatial.toolkit.*
import java.util.concurrent.CompletableFuture
import org.json.JSONArray
import org.json.JSONObject

/** Dedicated real workload shader draws; all buffers are synthetic and never bind live data. */
@OptIn(SpatialSDKExperimentalAPI::class)
internal class CohortFixtureController(private val activity:AppSystemActivity) {
    private var attempted=false
    private var failed=false
    private var texture:SceneTexture?=null
    private val materials=mutableListOf<SceneMaterial>()
    private val meshes=mutableListOf<SceneMesh>()
    private val entities=mutableListOf<Entity>()
    private val objects=mutableListOf<SceneObject>()
    private val packets=linkedMapOf<String,java.nio.ByteBuffer>()
    private var selected="off"
    private var revision=0L
    private var expectedVisible=false
    private var requestedCase:CohortFixturePacket.Case?=null
    private fun initialize(){
        check(!attempted);attempted=true
        val sampler=SamplerConfig(Filter.NEAREST,Filter.NEAREST,Filter.NEAREST,AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE)
        texture=SceneTexture(1024,1024,1,sampler,null)
        for(group in 1..2){
            val material=SceneMaterial.custom("workload",arrayOf(
                SceneMaterialAttribute("atlas",SceneMaterialDataType.Texture2D),SceneMaterialAttribute("maskAtlas",SceneMaterialDataType.Texture2D),
                SceneMaterialAttribute("transportFlags",SceneMaterialDataType.Vector4),SceneMaterialAttribute("meshFlags",SceneMaterialDataType.Vector4),
                SceneMaterialAttribute("renderStyle0",SceneMaterialDataType.Vector4),SceneMaterialAttribute("renderStyle1",SceneMaterialDataType.Vector4),
                SceneMaterialAttribute("renderStyle2",SceneMaterialDataType.Vector4)))
            materials.add(material)
            material.setTexture("atlas",requireNotNull(texture));material.setTexture("maskAtlas",requireNotNull(texture))
            material.setAttribute("meshFlags",Vector4(1f,group.toFloat(),0f,0f))
            material.setAttribute("transportFlags",Vector4(1f,0f,0f,0f))
            for(row in 0..2)material.setAttribute("renderStyle$row",Vector4(0f,0f,0f,0f))
            material.setDepthTest(DepthTest.LESS_OR_EQUAL);material.setDepthWrite(DepthWrite.ENABLE)
            material.setBlendMode(BlendMode.OPAQUE);material.setSidedness(MaterialSidedness.DOUBLE_SIDED)
            val first=if(group==1)CohortFixturePacket.HAND_RECORD else CohortFixturePacket.GRAFT_RECORD
            val mesh=WorkloadGeometry.records(material,intArrayOf(first,first+1,first+2),false);meshes.add(mesh)
            val entity=Entity.create(listOf(Transform(Pose(Vector3(0f,0f,0f)))));entities.add(entity)
            val obj=SceneObject(activity.scene,mesh,"cohort-static-$group",entity);objects.add(obj)
            obj.setIsVisible(false);obj.shouldFrustumCull(false)
            activity.systemManager.findSystem<SceneObjectSystem>().addSceneObject(entity,CompletableFuture.completedFuture(obj))
        }
    }
    fun hide(){objects.forEach{if(runCatching{it.setIsVisible(false)}.isFailure)failed=true};selected="off"}
    fun comparisonsReady():Boolean=selected=="off"&&!failed
    fun apply(command:String):String {
        if(command !in CohortFixturePacket.commands)return "rejected:cohort_fixture"
        if(command=="off"){hide();return "fixture_hidden_requested"}
        if(failed)return "blocked:fixture_quarantined"
        if(revision==Long.MAX_VALUE)return "blocked:fixture_revision_exhausted"
        return try{
            hide();check(!failed);if(!attempted)initialize()
            val (groupName,name)=command.split(':');val case=CohortFixturePacket.cases.single{it.name==name}
            requestedCase=case;expectedVisible=case.expectedVisible
            val packet=packets[case.name]?:CohortFixturePacket.packet(case).also{packets[case.name]=it}
            packet.position(0);requireNotNull(texture).update(packet,1024,1024,4096)
            val expectation=PacketExpectation.fields(case.expectedGeneration,case.expectedGpu)
            materials.forEachIndexed{index,material->
                material.setAttribute("meshFlags",Vector4(1f,(index+1).toFloat(),expectation.first,0f))
                material.setAttribute("transportFlags",Vector4(1f,0f,0f,expectation.second))
            }
            objects[if(groupName=="hand")0 else 1].setIsVisible(true)
            selected=command;revision++;"static_fixture_setters_invoked:no_shader_execution_proof"
        }catch(_:Throwable){failed=true;hide();"failed:fixture_quarantined"}
    }
    fun status():String {
        val handles=JSONArray()
        objects.forEachIndexed{i,obj->handles.put(JSONObject().put("group",i+1).put("entityId",entities[i].id.toString())
            .put("objectHandle",obj.handle.toString()).put("meshHandle",meshes[i].handle.toString()).put("materialHandle",materials[i].handle.toString()))}
        return JSONObject().put("schema","rusty.quest.cohort_fixture.state.v1").put("source","static_uploaded_synthetic")
            .put("selected",selected).put("revision",revision).put("allocationAttempted",attempted).put("quarantined",failed)
            .put("comparisonsReady",comparisonsReady())
            .put("retainedPacketBuffers",packets.size).put("maxPacketBuffers",CohortFixturePacket.cases.size)
            .put("expectedVisibleWhenSelected",expectedVisible).put("case",requestedCase?.name?:"none")
            .put("headerFrame",requestedCase?.frame).put("headerGeneration",requestedCase?.generation)
            .put("expectedMaterialGeneration",requestedCase?.expectedGeneration).put("headerGpuTestBit",requestedCase?.headerGpu)
            .put("expectedGpuTestBit",requestedCase?.expectedGpu).put("handles",handles)
            .put("selectedVertices",if(selected=="off")0 else 3).put("selectedIndices",if(selected=="off")0 else 3)
            .put("liveProducerDataMutated",false).put("shaderExecutionObserved",false).put("sdkSampledProof",false).toString()
    }
}
