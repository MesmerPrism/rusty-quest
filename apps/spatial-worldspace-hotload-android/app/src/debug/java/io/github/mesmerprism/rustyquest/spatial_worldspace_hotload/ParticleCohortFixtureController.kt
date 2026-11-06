package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import com.meta.spatial.core.*
import com.meta.spatial.runtime.*
import com.meta.spatial.toolkit.*
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import org.json.JSONObject

/** Actual particle shader path, isolated immutable textures and one four-vertex billboard. */
@OptIn(SpatialSDKExperimentalAPI::class)
internal class ParticleCohortFixtureController(private val activity:AppSystemActivity) {
    private data class PacketTexture(val packet:ByteBuffer,val texture:SceneTexture)
    private val packets=linkedMapOf<String,PacketTexture>()
    private val retainedTextures=mutableListOf<SceneTexture>()
    private val retainedBuffers=mutableListOf<ByteBuffer>()
    private val sampler=SamplerConfig(Filter.NEAREST,Filter.NEAREST,Filter.NEAREST,AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE)
    private var attempted=false
    private var failed=false
    private var material:SceneMaterial?=null
    private var mesh:SceneMesh?=null
    private var entity:Entity?=null
    private var sceneObject:SceneObject?=null
    private var selected="off"
    private var revision=0L
    private var requestedCase:ParticleCohortFixturePacket.Case?=null
    private var boundsMode="off"
    private var boundsPose:BoundsProbePose?=null
    private var boundsOutcome="unselected"
    private var hostTranslation=floatArrayOf(0f,0f,0f)
    private var hostBranch=false
    private var cullingRequested=false
    private fun upload(bytes:ByteBuffer):SceneTexture {
        retainedBuffers.add(bytes)
        return SceneTexture(1024,1024,1,sampler,null).also{texture->
            retainedTextures.add(texture);texture.update(bytes,1024,1024,4096)
        }
    }
    private fun packetTexture(case:ParticleCohortFixturePacket.Case):PacketTexture = packets[case.name]?:run{
        check(packets.size<ParticleCohortFixturePacket.cases.size)
        val packet=ParticleCohortFixturePacket.packet(case)
        PacketTexture(packet,upload(packet)).also{packets[case.name]=it}
    }
    private fun initialize(atlas:SceneTexture){
        check(!attempted);attempted=true
        val whiteMask=upload(ParticleCohortFixturePacket.whiteMask())
        val m=SceneMaterial.custom("workload",arrayOf(
            SceneMaterialAttribute("atlas",SceneMaterialDataType.Texture2D),SceneMaterialAttribute("maskAtlas",SceneMaterialDataType.Texture2D),
            SceneMaterialAttribute("transportFlags",SceneMaterialDataType.Vector4),SceneMaterialAttribute("meshFlags",SceneMaterialDataType.Vector4),
            SceneMaterialAttribute("renderStyle0",SceneMaterialDataType.Vector4),SceneMaterialAttribute("renderStyle1",SceneMaterialDataType.Vector4),
            SceneMaterialAttribute("renderStyle2",SceneMaterialDataType.Vector4)))
        material=m
        m.setTexture("atlas",atlas);m.setTexture("maskAtlas",whiteMask)
        m.setAttribute("meshFlags",Vector4(0f,0f,0f,0f));m.setAttribute("transportFlags",Vector4(1f,0f,0f,0f))
        for(row in 0..2)m.setAttribute("renderStyle$row",Vector4(0f,0f,0f,0f))
        m.setDepthTest(DepthTest.LESS_OR_EQUAL);m.setDepthWrite(DepthWrite.DISABLE)
        m.setBlendMode(BlendMode.ADDITIVE);m.setSidedness(MaterialSidedness.DOUBLE_SIDED)
        val builtMesh=WorkloadGeometry.records(m,intArrayOf(ParticleCohortFixturePacket.PRIMARY_RECORD),true);mesh=builtMesh
        val builtEntity=Entity.create(listOf(Transform(Pose(Vector3(0f,0f,0f)))));entity=builtEntity
        val obj=SceneObject(activity.scene,builtMesh,"particle-cohort-static",builtEntity);sceneObject=obj
        obj.setIsVisible(false);obj.shouldFrustumCull(false)
        activity.systemManager.findSystem<SceneObjectSystem>().addSceneObject(builtEntity,CompletableFuture.completedFuture(obj))
    }
    fun hide(){
        sceneObject?.let{if(runCatching{it.setIsVisible(false)}.isFailure)failed=true}
        // Hiding is attempted first; failed reset is retained as quarantine.
        if(runCatching{placement(floatArrayOf(0f,0f,0f),false,false)}.isFailure)failed=true
        selected="off";boundsMode="off";boundsPose=null
    }
    private fun placement(translation:FloatArray,host:Boolean,cull:Boolean){
        require(translation.size==3&&translation.all{it.isFinite()})
        val t=Vector3(translation[0],translation[1],translation[2])
        entity?.setComponent(Transform(Pose(t)))
        sceneObject?.setPosition(t)
        sceneObject?.shouldFrustumCull(cull)
        requestedCase?.let{case->
            val expected=PacketExpectation.fields(case.expectedGeneration,case.expectedGpu)
            material?.setAttribute("transportFlags",Vector4(1f,0f,if(host)1f else 0f,expected.second))
        }
        hostTranslation=translation.copyOf();hostBranch=host;cullingRequested=cull
    }
    fun comparisonsReady():Boolean=selected=="off"&&!failed
    fun apply(command:String):String {
        if(command !in ParticleCohortFixturePacket.commands)return "rejected:particle_cohort"
        if(command=="off"){hide();return "particle_fixture_hidden_requested"}
        if(boundsMode!="off")return "blocked:particle_bounds_active"
        if(failed)return "blocked:particle_fixture_quarantined"
        if(revision==Long.MAX_VALUE)return "blocked:particle_fixture_revision_exhausted"
        return try{
            hide();check(!failed)
            val case=ParticleCohortFixturePacket.cases.single{it.name==command};requestedCase=case
            val input=packetTexture(case)
            if(!attempted)initialize(input.texture)
            val expectation=PacketExpectation.fields(case.expectedGeneration,case.expectedGpu)
            requireNotNull(material).apply{
                setTexture("atlas",input.texture)
                setAttribute("meshFlags",Vector4(0f,0f,expectation.first,0f))
                setAttribute("transportFlags",Vector4(1f,0f,0f,expectation.second))
            }
            requireNotNull(sceneObject).setIsVisible(true)
            selected=command;revision++;"static_particle_fixture_setters_invoked:no_shader_execution_proof"
        }catch(_:Throwable){failed=true;hide();"failed:particle_fixture_quarantined"}
    }
    fun applyBounds(command:String):String {
        if(command !in ParticleBoundsCalibration.commands)return "rejected:particle_bounds"
        if(command=="off"){hide();boundsOutcome="hidden_and_identity_requested";return "particle_bounds_hidden_requested"}
        if(failed)return "blocked:particle_fixture_quarantined"
        if(revision==Long.MAX_VALUE)return "blocked:particle_fixture_revision_exhausted"
        if(!ParticleBoundsCalibration.permits(boundsMode,command))return "blocked:particle_bounds_sequence"
        return try{
            if(command=="packet-origin"){
                if(selected!="off")return "blocked:hide_particle_cohort_first"
                val result=apply("matching")
                if(failed||selected!="matching")return result
            }else check(selected=="matching"&&requestedCase?.name=="matching")
            if(command=="host-behind"){
                val viewer=activity.scene.getViewerPose();val f=viewer.forward()
                boundsPose=BoundsProbePose.create(floatArrayOf(viewer.t.x,viewer.t.y,viewer.t.z),floatArrayOf(f.x,f.y,f.z))
            }
            val behind=command in listOf("host-behind","packet-behind-off","packet-behind-on")
            if(behind){
                val viewer=activity.scene.getViewerPose();val f=viewer.forward()
                check(ParticleBoundsCalibration.behindRemainsValid(requireNotNull(boundsPose),
                    floatArrayOf(viewer.t.x,viewer.t.y,viewer.t.z),floatArrayOf(f.x,f.y,f.z)))
            }
            val translation=when(command){"packet-origin"->floatArrayOf(0f,0f,0f)
                "host-front"->ParticleBoundsCalibration.frontTranslation();else->requireNotNull(boundsPose).translation}
            placement(translation,command in listOf("host-front","host-behind"),command=="packet-behind-on")
            boundsMode=command;if(command!="packet-origin")revision++
            boundsOutcome="placement_and_culling_setters_invoked:no_pixel_or_cull_observation";boundsOutcome
        }catch(_:Throwable){failed=true;hide();boundsOutcome="failed:particle_bounds_quarantined";boundsOutcome}
    }
    private fun validateBounds(){
        val pose=boundsPose?:return
        val valid=runCatching{
            val viewer=activity.scene.getViewerPose();val f=viewer.forward()
            ParticleBoundsCalibration.behindRemainsValid(pose,floatArrayOf(viewer.t.x,viewer.t.y,viewer.t.z),floatArrayOf(f.x,f.y,f.z))
        }.getOrDefault(false)
        if(!valid){hide();boundsOutcome="invalidated:viewer_or_pose_changed"}
    }
    fun status():String {
        validateBounds()
        return JSONObject().put("schema","rusty.quest.particle_cohort_fixture.state.v1")
        .put("source","static_uploaded_synthetic").put("selected",selected).put("revision",revision)
        .put("allocationAttempted",attempted||retainedBuffers.isNotEmpty()).put("quarantined",failed).put("comparisonsReady",comparisonsReady())
        .put("expectedVisibleWhenSelected",requestedCase?.expectedVisible?:false).put("case",requestedCase?.name?:"none")
        .put("ordinaryCohortVisibilityMetadataApplies",boundsMode=="off")
        .put("headerFrame",requestedCase?.frame).put("headerGeneration",requestedCase?.generation)
        .put("auxFrame",requestedCase?.auxFrame).put("auxGeneration",requestedCase?.auxGeneration)
        .put("expectedMaterialGeneration",requestedCase?.expectedGeneration).put("headerGpuTestBit",requestedCase?.headerGpu)
        .put("expectedGpuTestBit",requestedCase?.expectedGpu).put("primaryRecord",0).put("auxRecord",20496)
        .put("materialHandle",material?.handle?.toString()).put("meshHandle",mesh?.handle?.toString())
        .put("entityId",entity?.id?.toString()).put("objectHandle",sceneObject?.handle?.toString())
        .put("retainedTextures",retainedTextures.size).put("retainedBuffers",retainedBuffers.size)
        .put("maxTexturesAndBuffers",ParticleCohortFixturePacket.cases.size+1).put("immutableTextureUploads",true)
        .put("selectedVertices",if(selected=="off")0 else 4).put("selectedIndices",if(selected=="off")0 else 6)
        .put("handOrGraftGeometry",false).put("ownerRenderStyleSelected",false)
        .put("boundsCalibration",JSONObject().put("mode",boundsMode).put("outcome",boundsOutcome)
            .put("packetPointOwner","immutable_matching_fixture").put("hostBranchRequested",hostBranch)
            .put("hostTranslationRequested",org.json.JSONArray(hostTranslation.toList())).put("cullingRequested",cullingRequested)
            .put("hostMeshBounds",mesh?.let{runCatching{it.computeCombinedBounds().toString()}.getOrDefault("unavailable")})
            .put("capturedEye",boundsPose?.let{org.json.JSONArray(it.eye.toList())})
            .put("capturedForward",boundsPose?.let{org.json.JSONArray(it.forward.toList())})
            .put("maximumCapturedHostDepth",boundsPose?.maximumHostDepth())
            .put("maximumCapturedHostBranchDepth",boundsPose?.let{ParticleBoundsCalibration.maximumHostBranchDepth(it)})
            .put("effectiveWorldBoundsObserved",false).put("effectiveCullingObserved",false).put("transformPixelsObserved",false))
        .put("liveProducerDataMutated",false).put("shaderExecutionObserved",false).put("sdkSampledProof",false).toString()
    }
}
