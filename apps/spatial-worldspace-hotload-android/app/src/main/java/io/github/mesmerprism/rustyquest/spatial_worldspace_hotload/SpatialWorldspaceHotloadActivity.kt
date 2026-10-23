package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.*
import com.meta.spatial.runtime.*
import com.meta.spatial.toolkit.*
import com.meta.spatial.vr.VRFeature
import com.meta.spatial.vr.VrInputSystemType
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import io.github.mesmerprism.rustyquest.native_renderer.SessionOwnerActivity
import org.json.JSONObject

/** Static SDK geometry driven by one coherent GPU data texture; no CPU position readback. */
@OptIn(SpatialSDKExperimentalAPI::class)
class SpatialWorldspaceHotloadActivity : SessionOwnerActivity(), ProbeControlTarget {
    companion object {
        private val sdkOwnerEpochs=AtomicLong(0L)
        // Unknown external SessionCore revocation outlives an Activity recreation.
        // Only a real process restart clears this admission latch; it proves no cleanup.
        @Volatile private var ownerRevocationFailed=false
        @Volatile private var failedOwnerRevocationEpoch=0L
        @Volatile private var failedOwnerRevocationTerminal=false
    }
    private val activityInstanceId=java.util.UUID.randomUUID().toString()
    private val activityCreatedElapsedMs=android.os.SystemClock.elapsedRealtime()
    private var sceneReadyCount=0L
    private val fixedReferences=mutableListOf<Pair<String,SceneObject>>()
    private val sampler=SamplerConfig(Filter.NEAREST,Filter.NEAREST,Filter.NEAREST,AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE)
    private val maskSampler=SamplerConfig(Filter.LINEAR,Filter.LINEAR,Filter.NEAREST,AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE)
    private val materials=mutableListOf<SceneMaterial>()
    private val materialKinds=mutableMapOf<SceneMaterial,Pair<Float,Float>>()
    private var surfaceTexture: SceneTexture?=null
    private var cpuTexture: SceneTexture?=null
    private var cpuReferenceBytes:ByteBuffer?=null
    private var feedbackState by mutableStateOf("{}")
    private val operatorGeometry=if(BuildConfig.META_XR_OPERATOR)OperatorPanelObserver(activityInstanceId)else null
    private var feedbackVisualRevision=0L
    private var feedbackVisualValue:Long?=null
    private val feedbackObservations by lazy{FeedbackObservationStore(SdkFeedbackSession.filesDirectory(this))}
    private var maskTexture: SceneTexture?=null
    private var replacementMaskTexture:SceneTexture?=null
    private var textureProbe="baseline"
    private var legacyDrawSuppressed=false
    private var workloadLayout: WorkloadLayout?=null
    private var workloadDescriptor:WorkloadDescriptor?=null
    private var sceneTopology:WorkloadSceneTopology?=null
    private var requestedTopology by mutableStateOf("p64")
    private var requestedVertices by mutableStateOf(0)
    private var requestedIndices by mutableStateOf(0)
    private var topologyStatus by mutableStateOf("{}")
    private var topologyOutcome by mutableStateOf("not_ready")
    private var scaleLadder by mutableStateOf("unprepared")
    private var scaleConfigurationRevision by mutableStateOf(-1)
    private var boundsProbe:BoundsProbePose?=null
    private var boundsStatus by mutableStateOf("identity")
    private var renderStyle: WorkloadRenderStyle?=null
    private var carrier: PanelSceneObject?=null
    private var retiringCarrier: PanelSceneObject?=null
    private var producerHandle by mutableStateOf(0L)
    private var consumerGenerationRevocationFailed=false
    private var retiringHandle by mutableStateOf(0L)
    private var resumed by mutableStateOf(false)
    private var destroyed=false
    private var hotloadRequest=0L
    private var hotloadReceipt by mutableStateOf("No hotload request")
    private var diagnosticState by mutableStateOf("{}")
    private var nativeCandidateState by mutableStateOf("{}")
    @Volatile private var xrReady=false
    private var xrGeneration=0L
    private var observerEpoch=0L
    private val observerLifecycleRevision=AtomicLong(0L)
    private var observerStatus by mutableStateOf("{}")
    @Volatile private var sceneOwnerEpoch=0L
    @Volatile private var xrFocusRequestFailed=false
    @Volatile private var xrDetachFailed=false
    @Volatile private var producerStopRequestFailed=false
    private var controllerSystemRegistered=false
    private var sessionStatus by mutableStateOf("Preparing session owner")
    private var guidanceBias by mutableStateOf(0)
    @Volatile private var lastController:SdkControllerAcquisition?=null
    private var source by mutableStateOf("cpu")
    private var status by mutableStateOf("Startup asset verification pending")
    private var controls by mutableStateOf(if(BuildConfig.DEVELOPMENT_FEEDBACK)WorkloadControls(drawMainCount=64,drawTracerCount=0)else WorkloadControls())
    private var restoreSrgb by mutableStateOf(true)
    private var flipRows by mutableStateOf(false)
    private var hostGeometry by mutableStateOf(false)
    private var occluder: SceneObject?=null
    private var occluderVisible by mutableStateOf(true)
    private var culling by mutableStateOf(false)
    private var benchmarkStartNs by mutableStateOf(0L)
    private var benchmarkReceipt by mutableStateOf("No benchmark interval recorded")
    private var detailsExpanded by mutableStateOf(false)
    private var panelClicks by mutableStateOf(0)
    private val handler=Handler(Looper.getMainLooper())
    private val poll=object:Runnable {
        override fun run() {
            if(retiringHandle!=0L) {
                val receipt=try{GpuWorkloadProducer.nativeStatus(retiringHandle)}catch(_:Throwable){"{}"}
                if(ProducerStatus.teardownCompleted(receipt)){retiringHandle=0;retiringCarrier=null}
            }
            if(!destroyed && producerHandle!=0L)status=try{GpuWorkloadProducer.nativeStatus(producerHandle)}catch(_:Throwable){"Native status unavailable"}
            if(!destroyed&&resumed&&xrReady)validateBoundsProbe()
            if(!destroyed&&resumed&&xrReady&&ProbeDebugFeatures.observerActive(this@SpatialWorldspaceHotloadActivity))observerStatus=currentObserverStatus()
            if(BuildConfig.SESSION_CORE&&!destroyed&&resumed&&xrReady){
                bindSessionOwner()
                if(sceneOwnerEpoch!=0L){ownerPoll();sessionStatus=ownerStatus()}
            }
            if(retiringHandle!=0L || (!destroyed&&(producerHandle!=0L||(resumed&&(BuildConfig.SESSION_CORE||ProbeDebugFeatures.observerActive(this@SpatialWorldspaceHotloadActivity))))))handler.postDelayed(this,if(BuildConfig.SESSION_CORE)100 else 250)
        }
    }
    private fun schedulePoll(){handler.removeCallbacks(poll);handler.post(poll)}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);ProbeDebugFeatures.attach(this)}
    override fun registerFeatures():List<SpatialFeature> = listOf(VRFeature(this,inputSystemType=VrInputSystemType.INTERACTION_SDK),ComposeFeature(),object:SpatialFeature {
        override fun registerRequiredOpenXRExtensions()=listOf("XR_EXT_hand_tracking","XR_KHR_convert_timespec_time","XR_EXT_local_floor")
        override fun onVRReady(){xrReady=true;xrGeneration++;attachXr();queueObserverLifecycle(false)}
        override fun onVRPause(){sdkXrUnavailable(false)}
        override fun onSpatialShutdown(){sdkXrUnavailable(true)}
    })+ProbeDebugFeatures.features(this)
    // Only reader policy is marshalled. Preserve the existing synchronous SDK
    // producer detach before the runtime's pause/shutdown callback returns.
    private fun sdkXrUnavailable(terminal:Boolean){
        try{revokeSessionOwner(terminal)}finally{
            try{releaseXr()}finally{
                xrReady=false
                handler.post{if(!destroyed)endBindingComparison("xr_unavailable")}
                queueObserverLifecycle(true)
                if(ownerRevocationFailed||xrFocusRequestFailed||xrDetachFailed)handler.post{stopProducer()}
            }
        }
    }
    private fun queueObserverLifecycle(clearOnly:Boolean){
        val revision=observerLifecycleRevision.updateAndGet{if(it==Long.MAX_VALUE)it else it+1}
        handler.post{if(!destroyed&&revision==observerLifecycleRevision.get()){
            ProbeDebugFeatures.clearObserver(this)
            if(!clearOnly&&revision!=Long.MAX_VALUE)bindObserver()
        }}
    }
    private fun currentObserverStatus():String{
        if(!resumed||!xrReady||destroyed)ProbeDebugFeatures.clearObserver(this)
        return ProbeDebugFeatures.observerStatus(this)
    }
    private fun bindObserver(){
        if(!BuildConfig.SUBMISSION_OBSERVER||!xrReady||!resumed||destroyed||ownerRevocationFailed||xrFocusRequestFailed||xrDetachFailed||producerStopRequestFailed||ProbeDebugFeatures.observerActive(this)||observerEpoch==Long.MAX_VALUE)return
        try {
            val instance=scene.getOpenXrInstanceHandle();val session=scene.getOpenXrSessionHandle()
            if(instance!=0L&&session!=0L){ProbeDebugFeatures.bindObserver(this,instance,session,++observerEpoch);schedulePoll()}
        }catch(_:Throwable){ProbeDebugFeatures.clearObserver(this)}
    }
    private fun attachXr(){
        if(producerHandle==0L || !xrReady || !resumed||ownerRevocationFailed||xrFocusRequestFailed||xrDetachFailed||producerStopRequestFailed)return
        val okay=GpuWorkloadProducer.nativeAttachXr(producerHandle,scene.getOpenXrInstanceHandle(),
            scene.getOpenXrSessionHandle(),scene.getOpenXrGetInstanceProcAddrHandle(),xrGeneration,1000426000,true)
        if(!okay)status="Live hand bridge inactive; particles remain available"
    }
    private fun bindSessionOwner(){
        if(!BuildConfig.SESSION_CORE||sceneOwnerEpoch!=0L||!xrReady||!resumed||destroyed||producerHandle==0L||ownerRevocationFailed||xrFocusRequestFailed||xrDetachFailed||producerStopRequestFailed)return
        val native=try{JSONObject(GpuWorkloadProducer.nativeStatus(producerHandle))}catch(_:Exception){return}
        if(native.optString("phase")!="ready"||native.optBoolean("stopRequested",true)||native.optBoolean("teardownCompleted",true))return
        val instance=scene.getOpenXrInstanceHandle();val session=scene.getOpenXrSessionHandle()
        if(instance==0L||session==0L)return
        val epoch=sdkOwnerEpochs.updateAndGet{if(it==Long.MAX_VALUE)it else it+1}
        if(epoch==Long.MAX_VALUE){sessionStatus="Scene owner identity exhausted";return}
        sceneOwnerEpoch=epoch
        ownerBind(epoch,instance,session)
    }
    private fun revokeSessionOwner(terminal:Boolean){
        val old=sceneOwnerEpoch;sceneOwnerEpoch=0
        if(BuildConfig.SESSION_CORE&&old!=0L)try{ownerRevoke(old,terminal)}catch(_:Throwable){
            // Local input is revoked, but external SessionCore cleanup is unknown.
            // Persist across Activity recreation; never infer a fresh owner is safe.
            ownerRevocationFailed=true;failedOwnerRevocationEpoch=old;failedOwnerRevocationTerminal=terminal
        }
    }
    private fun deliverController(sample:SdkControllerAcquisition){
        if(sample.sdkEpoch!=sceneOwnerEpoch||!xrReady||destroyed)return
        lastController=sample
        ownerController(sample.sdkEpoch,sample.entityId,sample.sequence,sample.requestedPoseTimeElapsedNs,
            sample.acquiredAtElapsedNs,sample.rawFlags.toLong(),sample.active,sample.tracked,sample.rightSecondaryPressed,
            sample.x,sample.y,sample.z,sample.qx,sample.qy,sample.qz,sample.qw)
    }
    private fun sessionAction(action:String){
        if(!BuildConfig.SESSION_CORE||sceneOwnerEpoch==0L||!resumed||destroyed){sessionStatus="Start GPU and wait for the session owner";return}
        ownerAction(action,guidanceBias)
    }
    override fun onOwnerPresentationRequested(route:String){
        // The single SDK Activity owns both world geometry and its controls.
        // The base queues this callback under the captured canonical owner token.
        if(destroyed)return
        if(route=="finished")finish()
        else if(resumed&&sceneOwnerEpoch!=0L&&route=="immersive")selectGpu()
    }
    private fun releaseXr(handle:Long=producerHandle){
        if(handle==0L)return
        try{GpuWorkloadProducer.nativeSetFocused(handle,false)}catch(_:Throwable){xrFocusRequestFailed=true}
        finally{try{GpuWorkloadProducer.nativeDetachXr(handle)}catch(_:Throwable){xrDetachFailed=true}}
    }
    override fun onSceneReady(){
        sceneReadyCount++
        super.onSceneReady();scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR);scene.setViewOrigin(0f,0f,0f,0f)
        if(BuildConfig.SESSION_CORE&&!controllerSystemRegistered){
            controllerSystemRegistered=true
            systemManager.registerSystem(SdkControllerSamplingSystem({scene},{if(xrReady&&!destroyed)sceneOwnerEpoch else 0L},::deliverController))
        }
        try {
            val descriptor=WorkloadManifest.loadDescriptor(assets,BuildConfig.DEVELOPMENT_FEEDBACK);workloadDescriptor=descriptor
            val layout=descriptor.layout;workloadLayout=layout
            if(BuildConfig.SESSION_CORE)renderStyle=WorkloadRenderStyle.load(assets)
            cpuReferenceBytes=AtlasPacket.reference(layout)
            cpuTexture=SceneTexture(1024,1024,1,sampler,null).also{it.update(requireNotNull(cpuReferenceBytes),1024,1024,4096)}
            if(BuildConfig.DEVELOPMENT_FEEDBACK)publishFeedbackVisual()
            val bitmap=assets.open("workload/mask.png").use{BitmapFactory.decodeStream(it)}?:error("Mask unavailable")
            require(bitmap.width==1024&&bitmap.height==1024)
            val pixels=IntArray(1024*1024);bitmap.getPixels(pixels,0,1024,0,0,1024,1024);bitmap.recycle()
            val bytes=ByteBuffer.allocateDirect(pixels.size*4)
            for(pixel in pixels){val r=(pixel ushr 16) and 255;val g=(pixel ushr 8) and 255;val b=pixel and 255
                require(r==g&&g==b&&(pixel ushr 24)==255);bytes.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(255.toByte())}
            bytes.flip();maskTexture=SceneTexture(1024,1024,1,if(BuildConfig.SESSION_CORE)maskSampler else sampler,null).also{it.update(bytes,1024,1024,4096)}
            val entity=Entity.create(listOf(Transform(Pose(Vector3(0f,0f,0f)))))
            val options=PanelConfigOptions(layoutWidthInPx=1024,layoutHeightInPx=1024,width=1f,height=1f,mips=1,
                samplerConfig=sampler,forceSceneTexture=true,includeGlass=false,enableLayer=false,stereoMode=StereoMode.None).apply {
                sceneMeshCreator={texture->surfaceTexture=texture
                    val diagnostic=material(2f,0f)
                    addRange(layout.graftsOffset+layout.graftsCount,2,true,material(0f,3f))
                    sceneTopology=WorkloadSceneTopology(scene,descriptor,listOf(material(0f,0f),material(1f,1f),material(1f,2f)),::addObject)
                    check(applyTopology("p64",false).startsWith("mesh_setters_invoked"))
                    val obstructionEntity=Entity.create(listOf(Transform(Pose(Vector3(0f,0f,0f)))))
                    occluder=SceneObject(scene,WorkloadGeometry.occluder(material(3f,0f)),"depth-reference",obstructionEntity).also{addFixedObject("depth-reference",obstructionEntity,it)}
                    WorkloadGeometry.diagnostic(diagnostic)}
            }
            carrier=PanelSceneObject(scene,entity,options).also{it.shouldFrustumCull(false);addFixedObject("barcode-carrier",entity,it)}
            if(!BuildConfig.DEVELOPMENT_FEEDBACK)ProbeDebugFeatures.createDiagnostic(this,requireNotNull(cpuTexture))
            ProbeDebugFeatures.createCohort(this)
            diagnosticState=ProbeDebugFeatures.diagnosticStatus(this)
            status=if(BuildConfig.DEVELOPMENT_FEEDBACK)"Public 64-marker CPU scene; rig and external workload absent. Feedback code/state is resident."
                else "CPU fixed 64 markers. GPU source not started; visible barcode is vertex-sampled frame/generation."
        }catch(e:Exception){status="Startup refused: ${e.javaClass.simpleName}: ${e.message}"}
        Entity.create(listOf(Panel(R.id.probe_controls),Transform(Pose(Vector3(-1.05f,1.4f,1.3f)))))
    }
    private val objects=mutableListOf<SceneObject>()
    private fun addObject(entity:Entity,obj:SceneObject){objects.add(obj);systemManager.findSystem<SceneObjectSystem>()
        .addSceneObject(entity,CompletableFuture.completedFuture(obj))}
    private fun addFixedObject(name:String,entity:Entity,obj:SceneObject){addObject(entity,obj);fixedReferences.add(name to obj)}
    private fun addRange(first:Int,count:Int,billboards:Boolean,material:SceneMaterial,group:Int=0){
        for(range in AtlasPacket.chunks(first,count,if(billboards)4 else 1)){
            val entity=Entity.create(listOf(Transform(Pose(Vector3(0f,0f,0f)))))
            val obj=SceneObject(scene,WorkloadGeometry.records(material,range,billboards),"workload",entity)
            obj.shouldFrustumCull(false)
            if(group!=0)obj.setIsVisible(if(group==1)controls.drawHands else controls.drawGrafts)
            addFixedObject("diagnostic-quads",entity,obj)
        }
    }
    private fun material(kind:Float,group:Float):SceneMaterial {
        val attributes=arrayOf(SceneMaterialAttribute("atlas",SceneMaterialDataType.Texture2D),
            SceneMaterialAttribute("maskAtlas",SceneMaterialDataType.Texture2D),
            SceneMaterialAttribute("transportFlags",SceneMaterialDataType.Vector4),SceneMaterialAttribute("meshFlags",SceneMaterialDataType.Vector4),
            SceneMaterialAttribute("renderStyle0",SceneMaterialDataType.Vector4),SceneMaterialAttribute("renderStyle1",SceneMaterialDataType.Vector4),
            SceneMaterialAttribute("renderStyle2",SceneMaterialDataType.Vector4))
        val premultiplied=BuildConfig.SESSION_CORE&&kind==0f&&group==0f
        val m=if(premultiplied)SceneMaterial.custom(shader="workload",params=attributes,alphaMode=AlphaMode.PREMULTIPLIED,vertexLayout=VertexLayout.COMPACT)
            else SceneMaterial.custom("workload",attributes)
        materialKinds[m]=kind to group
        m.setTexture("atlas",cpuTexture!!);m.setTexture("maskAtlas",maskTexture!!)
        val style=renderStyle
        if(BuildConfig.SESSION_CORE)requireNotNull(style)
        m.setAttribute("renderStyle0",style?.row0?:Vector4(0f,0f,0f,0f))
        m.setAttribute("renderStyle1",style?.row1?:Vector4(0f,0f,0f,0f))
        m.setAttribute("renderStyle2",style?.row2?:Vector4(0f,0f,0f,0f))
        m.setDepthTest(DepthTest.LESS_OR_EQUAL);m.setDepthWrite(if(kind==0f)DepthWrite.DISABLE else DepthWrite.ENABLE)
        if(!premultiplied)m.setBlendMode(if(kind==0f)BlendMode.ADDITIVE else BlendMode.OPAQUE)
        m.setSidedness(MaterialSidedness.DOUBLE_SIDED)
        materials.add(m);updateFlags();return m
    }
    private fun transportFlags(kind:Float):Vector4 {
        val expected=PacketExpectation.fields(if(source=="gpu")producerHandle else 1L,source=="gpu")
        return Vector4(if(restoreSrgb)1f else 0f,if(flipRows)1f else 0f,
            if(BuildConfig.DEBUG&&kind==2f&&legacyDrawSuppressed)2f else if(hostGeometry)1f else 0f,expected.second)
    }
    private fun updateFlags(){if(!destroyed){
        val gpu=source=="gpu"
        val layout=workloadLayout
        ProbeDebugFeatures.diagnosticCohort(this,if(gpu&&layout!=null)producerHandle else 0L,
            if(layout!=null)layout.graftsOffset+layout.graftsCount else 0)
        val expected=PacketExpectation.fields(if(gpu)producerHandle else 1L,gpu)
        materials.forEach{m->
            val (kind,group)=requireNotNull(materialKinds[m])
            m.setAttribute("meshFlags",Vector4(kind,group,expected.first,if(BuildConfig.SESSION_CORE&&kind==0f&&group==0f)1f else 0f))
            // High generation half + GPU-source bit; culling is exclusively SceneObject state.
            m.setAttribute("transportFlags",transportFlags(kind))
        }
    }}
    private fun stopProducer(){
        // Revoke public payload reads even during destruction or a failed native stop.
        try{ProbeDebugFeatures.diagnosticCohort(this,0L,0)}
        catch(_:Throwable){consumerGenerationRevocationFailed=true;status="Diagnostic cohort revocation uncertain; scene recreation required"}
        if(benchmarkStartNs!=0L){benchmarkStartNs=0;benchmarkReceipt="invalidated:producer_stopped"}
        revokeSessionOwner(false)
        val stopping=producerHandle
        if(stopping==0L){if(retiringHandle!=0L)schedulePoll();return}
        try{
            releaseXr(stopping)
            check(retiringHandle==0L)
            retiringHandle=stopping;producerHandle=0;retiringCarrier=carrier
            try{updateFlags()}catch(_:Throwable){consumerGenerationRevocationFailed=true;sceneTopology?.quarantine();status="Consumer generation revocation uncertain; scene recreation required"}
        }catch(_:Throwable){producerStopRequestFailed=true;status="Producer retirement state uncertain; restart remains blocked"}
        finally{
            try{GpuWorkloadProducer.nativeStop(stopping)}catch(_:Throwable){producerStopRequestFailed=true;status="Stop request uncertain; restart remains blocked"}
            finally{schedulePoll()}
        }
    }
    private fun selectCpu(){if(ProbeDebugFeatures.isolationBlocksChanges()||sceneTopology?.bindingComparisonActive==true)return;if(!resumed||destroyed)return;restoreBounds();stopProducer();source="cpu";cpuTexture?.let{t->materials.forEach{it.setTexture("atlas",t)};ProbeDebugFeatures.diagnosticTexture(this,t)};updateFlags()}
    private fun selectGpu(){
        if(BuildConfig.DEVELOPMENT_FEEDBACK){status="Public development scene has no external GPU backend";return}
        if(ProbeDebugFeatures.isolationBlocksChanges())return
        if(sceneTopology?.bindingComparisonActive==true)return
        if(!resumed||destroyed||producerHandle!=0L||retiringHandle!=0L||surfaceTexture==null||consumerGenerationRevocationFailed||ownerRevocationFailed||xrFocusRequestFailed||xrDetachFailed||producerStopRequestFailed)return
        try{producerHandle=GpuWorkloadProducer.nativeStart(requireNotNull(carrier).surface,assets);check(producerHandle!=0L)
            check(GpuWorkloadProducer.nativeConfigure(producerHandle,controls.json()));attachXr();source="gpu"
            materials.forEach{it.setTexture("atlas",surfaceTexture!!)};ProbeDebugFeatures.diagnosticTexture(this,surfaceTexture!!);updateFlags();schedulePoll()
        }catch(e:Throwable){stopProducer();status="GPU start refused: ${e.javaClass.simpleName}"}
    }
    private fun configure(next:WorkloadControls,reset:Boolean=false):Boolean{
        if(ProbeDebugFeatures.isolationBlocksChanges())return false
        if(sceneTopology?.bindingComparisonActive==true)return false
        if(controls.controlRevision==Int.MAX_VALUE)return false
        next.controlRevision=controls.controlRevision+1;next.validate()
        if(producerHandle!=0L&&!GpuWorkloadProducer.nativeConfigure(producerHandle,next.json(reset))){status="Control rejected";return false}
        controls=next
        try{sceneTopology?.visibility(next.drawHands,next.drawGrafts)}catch(_:Exception){status="Producer configured; scene topology quarantined";return false}
        topologyStatus=sceneTopology?.status()?.toString()?:"{}";return true
    }
    private fun applyTopology(profile:String,enforcePreparation:Boolean=true):String {
        if(ProbeDebugFeatures.isolationBlocksChanges())return "blocked:diagnostic_isolation"
        if(sceneTopology?.bindingComparisonActive==true)return "blocked:binding_comparison_active"
        if(boundsProbe!=null||benchmarkStartNs!=0L)return "blocked:probe_or_measurement_active"
        if(enforcePreparation&&profile !in (WorkloadTopology.ladders[scaleLadder]?:emptyList()))return "blocked:prepare_matching_ladder"
        return try{
            val plan=WorkloadTopology.plan(profile,requireNotNull(workloadDescriptor))
            requestedTopology=profile;requestedVertices=plan.vertices;requestedIndices=plan.indices
            requireNotNull(sceneTopology).apply(profile)
            topologyOutcome="mesh_setters_invoked:no_pixel_proof";topologyStatus=requireNotNull(sceneTopology).status().toString();topologyOutcome
        }catch(e:Exception){topologyOutcome="rejected:${e.javaClass.simpleName}";topologyStatus=sceneTopology?.status()?.toString()?:"{}";topologyOutcome}
    }
    private fun prepareScale(ladder:String):String {
        if(ProbeDebugFeatures.isolationBlocksChanges())return "blocked:diagnostic_isolation"
        if(sceneTopology?.bindingComparisonActive==true)return "blocked:binding_comparison_active"
        if(boundsProbe!=null||benchmarkStartNs!=0L)return "blocked:probe_or_measurement_active"
        if(!ProbeDebugFeatures.cohortComparisonsReady())return "blocked:fixtures_active_or_uncertain"
        if(ladder=="release"){scaleLadder="unprepared";scaleConfigurationRevision=-1;return "released:producer_configuration_unchanged"}
        val profiles=WorkloadTopology.ladders[ladder]?:return "rejected:ladder"
        val previous=controls.copy();val previousLadder=scaleLadder;val previousRevision=scaleConfigurationRevision
        val next=controls.copy(drawMainCount=2562,drawTracerCount=17934,drawHands=ladder!="particles",drawGrafts=ladder in listOf("grafts","combined"))
        if(!configure(next))return "rejected:native_configuration"
        val outcome=applyTopology(profiles.first(),false)
        if(!outcome.startsWith("mesh_setters_invoked")){
            if(!configure(previous)){scaleLadder="unprepared";scaleConfigurationRevision=-1;return "failed:topology_and_configuration_rollback"}
            scaleLadder=previousLadder;scaleConfigurationRevision=if(previousRevision<0)-1 else controls.controlRevision;return outcome
        }
        scaleLadder=ladder;scaleConfigurationRevision=controls.controlRevision;return "prepared:$ladder"
    }
    private fun applyBindingComparison(route:String):String {
        if(!BuildConfig.DEBUG)return "blocked:debug_only"
        if(route !in listOf("retained","fresh","off"))return "rejected:binding_route"
        val topology=sceneTopology?:return "blocked:no_topology"
        if(destroyed||topology.uncertain)return "blocked:topology_unavailable"
        // Off restores the original visibility without rebinding, including during pause.
        if(route!="off"&&(!resumed||!xrReady||materials.isEmpty()||lifecycleUncertain()||
            source!="cpu"||producerHandle!=0L||retiringHandle!=0L||scaleLadder!="particles"||
            scaleConfigurationRevision!=controls.controlRevision||topology.active?.profile!="p512"||
            boundsProbe!=null||benchmarkStartNs!=0L||hostGeometry||!restoreSrgb||flipRows||
            ProbeDebugFeatures.isolationBlocksChanges()||!ProbeDebugFeatures.cohortComparisonsReady()))
            return "blocked:binding_comparison_prerequisites"
        return try {
            topologyOutcome=topology.compareBinding(route);topologyStatus=topology.status().toString();topologyOutcome
        }catch(error:Exception){topologyOutcome="failed:binding_comparison:${error.javaClass.simpleName}";topologyStatus=topology.status().toString();topologyOutcome}
    }
    private fun endBindingComparison(reason:String){
        val topology=sceneTopology?:return
        if(!topology.bindingComparisonActive)return
        try{topology.compareBinding("off");topologyOutcome="binding_comparison_invalidated:$reason:no_pixel_proof"}
        catch(_:Exception){topology.quarantine();topologyOutcome="failed:binding_comparison_invalidation:$reason"}
        topologyStatus=topology.status().toString()
    }
    private fun restoreBounds(){
        try{sceneTopology?.culling(false);sceneTopology?.translateHost(0f,0f,0f);boundsStatus="identity"}
        catch(_:Exception){sceneTopology?.quarantine();ProbeDebugFeatures.invalidateIsolation();boundsStatus="uncertain:record_objects_quarantined"}
        culling=false;boundsProbe=null
    }
    private fun validateBoundsProbe(){
        val probe=boundsProbe?:return
        try{
            val viewer=scene.getViewerPose();val f=viewer.forward()
            if(probe.maximumHostDepth(floatArrayOf(viewer.t.x,viewer.t.y,viewer.t.z),floatArrayOf(f.x,f.y,f.z))>= -1f){
                restoreBounds();boundsStatus="invalidated:viewer_left_behind_bounds_condition"
            }
        }catch(_:Exception){restoreBounds();boundsStatus="invalidated:viewer_pose_unavailable"}
    }
    private fun applyBoundsProbe(mode:String):String {
        if(ProbeDebugFeatures.isolationBlocksChanges())return "blocked:diagnostic_isolation"
        if(mode=="restore"){restoreBounds();return if(sceneTopology?.uncertain==true)"failed:bounds_restore" else "restored:identity_culling_off"}
        if(mode !in listOf("behind-off","behind-on"))return "rejected:bounds_mode"
        if(benchmarkStartNs!=0L||hostGeometry||source!="gpu"||producerHandle==0L||!xrReady||lifecycleUncertain()||sceneTopology?.uncertain!=false||!ProbeDebugFeatures.cohortComparisonsReady())return "blocked:bounds_prerequisites"
        if(sceneTopology?.active?.profile !in listOf("p64","h32"))return "blocked:bounds_requires_p64_or_h32"
        if(mode=="behind-on"&&boundsProbe==null)return "blocked:arm_behind_off_first"
        try{
        if(boundsProbe==null){
            val viewer=scene.getViewerPose();val f=viewer.forward()
            boundsProbe=BoundsProbePose.create(floatArrayOf(viewer.t.x,viewer.t.y,viewer.t.z),floatArrayOf(f.x,f.y,f.z))
            val t=requireNotNull(boundsProbe).translation;sceneTopology?.translateHost(t[0],t[1],t[2])
        }
        validateBoundsProbe();val probe=boundsProbe?:return "rejected:behind_condition_invalid"
        culling=mode=="behind-on";sceneTopology?.culling(culling)
        boundsStatus=JSONObject().put("mode",mode).put("capturedEye",org.json.JSONArray(probe.eye.toList()))
            .put("capturedForward",org.json.JSONArray(probe.forward.toList())).put("hostTranslation",org.json.JSONArray(probe.translation.toList()))
            .put("maximumCapturedHostDepth",probe.maximumHostDepth()).put("decodedWorldPositionsChanged",false)
            .put("fixedReferencesChanged",false).put("pixelsObserved",false).toString()
        return "bounds_setters_invoked:no_pixel_proof"
        }catch(_:Exception){restoreBounds();return "failed:bounds_setter"}
    }
    override fun registerPanels():List<PanelRegistration> {
        val creator:(Entity,android.content.Context)->ComposeView={_,ctx->
        ComposeView(ctx).apply{setContent{MaterialTheme{Surface(modifier=Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.onSurface){Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)){
            Text(if(BuildConfig.DEVELOPMENT_FEEDBACK)"SDK development feedback" else if(BuildConfig.SESSION_CORE)"Session" else "GPU workload")
            Text("$source Â· ${if(resumed)"ready" else "paused"}")
            if(!BuildConfig.DEVELOPMENT_FEEDBACK)Button(onClick={selectGpu()},enabled=resumed&&producerHandle==0L&&retiringHandle==0L){Text("Start GPU")}
            Button(onClick={selectCpu()}){Text("CPU reference / stop GPU")}
            if(BuildConfig.DEVELOPMENT_FEEDBACK){
                var digest by remember{mutableStateOf("")};var generation by remember{mutableStateOf("1")};var probe by remember{mutableStateOf("13")}
                if(BuildConfig.META_XR_OPERATOR)Button(onClick={feedbackUi("native_compute",mapOf("input" to "4"))},modifier=requireNotNull(operatorGeometry).widgetModifier()){Text("Native step / update scene")}
                Text("64 public CPU markers; resident code/state is independent of this panel and scene.")
                OutlinedTextField(value=digest,onValueChange={digest=it},label={Text("Trusted SHA256")},singleLine=true)
                Button(onClick={feedbackUi("native_init",mapOf("sha256" to digest))}){Text("Initialize received bridge")}
                OutlinedTextField(value=generation,onValueChange={generation=it},label={Text("Native generation")},singleLine=true)
                OutlinedTextField(value=probe,onValueChange={probe=it},label={Text("Independent probe result")},singleLine=true)
                Button(onClick={feedbackUi("native_activate",mapOf("generation" to generation,"sha256" to digest,"probe_result" to probe))}){Text("Activate received native code")}
                if(!BuildConfig.META_XR_OPERATOR)Button(onClick={feedbackUi("native_compute",mapOf("input" to "4"))}){Text("Native step / update scene")}
                Button(onClick={feedbackUi("compute",mapOf("input" to "4"))}){Text("DEX step / update scene")}
                Button(onClick={feedbackUi("status",emptyMap())}){Text("Refresh module state")}
                Text(feedbackState)
            }
            if(BuildConfig.SESSION_CORE){
                val session=try{JSONObject(sessionStatus)}catch(_:Exception){JSONObject()}
                val readback=session.optJSONObject("readback")?:session.optJSONObject("session")?:session
                Text("Session: ${session.optString("panel_phase",readback.optString("phase","preparing")).lowercase().replace('_',' ')}")
                Text(readback.optString("detail","").take(180))
                Text("Hold right B to calibrate breath")
                for(action in listOf("arm-a","arm-b","start","pause","resume","save-next")){
                    Button(onClick={sessionAction(action)},enabled=resumed&&sceneOwnerEpoch!=0L){
                        Text(when(action){"arm-a"->"Arm condition A";"arm-b"->"Arm condition B";"start"->"Start session";"pause"->"Pause";"resume"->"Resume";else->"Finish and save"})
                    }
                }
                Button(onClick={guidanceBias=if(guidanceBias>=100)0 else guidanceBias+25}){Text("Breath guidance: $guidanceBias%")}
            }
            Button(onClick={panelClicks++}){Text("Test panel click: $panelClicks")}
            Button(onClick={detailsExpanded=!detailsExpanded}){Text(if(detailsExpanded)"Hide diagnostics" else "Show diagnostics")}
            if(detailsExpanded&&!BuildConfig.DEVELOPMENT_FEEDBACK){
            Text("Resident simulation: 2562 main / 17934 tracer slots. Producer masks: ${controls.drawMainCount} / ${controls.drawTracerCount}")
            Text("Topology requested: $requestedTopology Â· $requestedVertices vertices / $requestedIndices indices")
            Text("Topology applied: ${sceneTopology?.active?.vertices?:0} vertices / ${sceneTopology?.active?.indices?:0} indices Â· $topologyOutcome")
            Text("Capability ladder: $scaleLadder Â· producer configuration $scaleConfigurationRevision")
            Text("GPU triangles: cyan hands / orange grafts. SDK interaction visuals are separate.")
            Text("$source Â· retiring $retiringHandle Â· hands ${controls.drawHands} Â· grafts ${controls.drawGrafts}")
            Text(status.take(800))
            if(BuildConfig.DEBUG){
                for(ladder in WorkloadTopology.ladders.keys)Button(onClick={applyWorkloadControl("prepare-scale",ladder)}){Text("Prepare $ladder ladder")}
                Button(onClick={applyWorkloadControl("prepare-scale","release")}){Text("Release fixed scale configuration")}
                for(profile in WorkloadTopology.ladders[scaleLadder]?:emptyList())Button(onClick={applyWorkloadControl("topology",profile)}){Text("Submit topology $profile")}
                for(route in listOf("retained","fresh","off"))Button(onClick={applyWorkloadControl("topology-binding",route)}){Text("Compare topology binding: $route")}
                for(mode in listOf("behind-off","behind-on","restore"))Button(onClick={applyWorkloadControl("bounds-probe",mode)}){Text("Host bounds: $mode")}
                Text("Bounds: $boundsStatus")
                Button(onClick={applyWorkloadControl("publicationPaused",(!controls.publicationPaused).toString())}){Text("Freeze producer publication: ${controls.publicationPaused}")}
                Text("Submission observer (last poll): ${observerStatus.take(800)}")
                Text("Atlas diagnostic: ${diagnosticState.take(800)}")
                for(page in 0 until AtlasDiagnosticProtocol.PAGES)Button(onClick={applyWorkloadControl("diag-page",page.toString())}){
                    Text(when(page){6->"Public diagnostic AUX";7->"Public positions + kernel";else->"Diagnostic headers ${page*8}â€“${page*8+7}"})}
                for(profile in AtlasDiagnosticProtocol.profiles)Button(onClick={applyWorkloadControl("diag-profile",profile)}){Text("Diagnostic $profile")}
                for(action in listOf("sdk-baseline","sdk-tint","sdk-offset","sdk-revert","sdk-stale","texture-rebind","texture-revert","compute-a","compute-b","compute-revert","compute-bad-abi","compute-malformed","compute-stale","compute-rapid")) Button(onClick={applyWorkloadControl(action,null)}){Text(action)}
                for(action in listOf("compute-info","compute-stage","compute-rollback"))Button(onClick={applyWorkloadControl(action,null)}){Text(action)}
                for(action in listOf("compute-activate","compute-discard"))Button(onClick={applyWorkloadControl(action,ProbeDebugFeatures.nativeCandidateId())},enabled=ProbeDebugFeatures.nativeCandidateId().isNotEmpty()){Text(action)}
                if(BuildConfig.EXTERNAL_WORKLOAD_HOTLOAD){
                    for(action in listOf("workload-info","workload-stage","workload-revert"))Button(onClick={applyWorkloadControl(action,null)}){Text(action)}
                    for(action in listOf("workload-activate","workload-discard"))Button(onClick={applyWorkloadControl(action,ProbeDebugFeatures.workloadCandidateId())},enabled=ProbeDebugFeatures.workloadCandidateId().isNotEmpty()){Text(action)}
                }
                Text("External candidate/context: $nativeCandidateState")
                Text("Active compute and last native rejection: ${try{JSONObject(status).optJSONObject("hotload")}catch(_:Exception){null}}")
                Text(hotloadReceipt)
            }
            Button(onClick={selectCpu()}){Text("CPU fixed reference / stop GPU")}
            Button(onClick={selectGpu()},enabled=resumed&&producerHandle==0L&&retiringHandle==0L){Text("Start genuine GPU workload")}
            for(n in listOf(0,64,512,2562))Button(onClick={applyWorkloadControl("mainCount",n.toString())}){Text("Mask $n main")}
            for(n in listOf(0,2562,8967,17934))Button(onClick={applyWorkloadControl("tracerCount",n.toString())}){Text("Mask $n tracers")}
            Button(onClick={applyWorkloadControl("hands",(!controls.drawHands).toString())}){Text("Hands: ${controls.drawHands}")}
            Button(onClick={applyWorkloadControl("grafts",(!controls.drawGrafts).toString())}){Text("Grafts: ${controls.drawGrafts}")}
            Button(onClick={applyWorkloadControl("visualScale",(if(controls.visualScale<.5f).70f else if(controls.visualScale<1f)1.4f else .35f).toString())}){Text("Particle radius scale: ${controls.visualScale}")}
            Button(onClick={applyWorkloadControl("graftScale",(if(controls.graftScale<.7f).85f else if(controls.graftScale<1f)1.3f else .5f).toString())}){Text("Graft scale: ${controls.graftScale}")}
            Button(onClick={applyWorkloadControl("occluder",(!occluderVisible).toString())}){Text("Opaque depth reference: $occluderVisible")}
            Button(onClick={applyWorkloadControl("simulate",(!controls.simulate).toString())}){Text("Simulation: ${controls.simulate}")}
            Button(onClick={applyWorkloadControl("reset",null)}){Text("Reset simulation")}
            Button(onClick={applyProbeControl(ProbeControl.RESTORE_SRGB,!restoreSrgb)}){Text("Restore byte sRGB: $restoreSrgb")}
            Button(onClick={applyProbeControl(ProbeControl.FLIP_ROWS,!flipRows)}){Text("Negative row flip: $flipRows")}
            Button(onClick={applyProbeControl(ProbeControl.HOST_GEOMETRY,!hostGeometry)}){Text("Negative host geometry: $hostGeometry")}
            Button(onClick={applyWorkloadControl("culling",(!culling).toString())}){Text("Static-bounds culling: $culling")}
            Button(onClick={applyWorkloadControl(if(benchmarkStartNs==0L)"measurementStart" else "measurementStop",null)}){Text("Start / finish measurement interval")}
            Text(benchmarkReceipt)
            Text("Barcode rows: frame32 then generation32, left-to-right low bits. Red = invalid packet. Culling disabled by default; no automatic displaced bounds.")
            }
        }}}}}}
        val settings:(Entity)->PanelSettings={UIPanelSettings(shape=QuadShapeOptions(.7f,1.0f),style=PanelStyleOptions(themeResourceId=R.style.ProbeTheme),display=DpPerMeterDisplayOptions())}
        return listOf(if(BuildConfig.META_XR_OPERATOR)ComposeViewPanelRegistration(R.id.probe_controls,composeViewCreator=creator,settingsCreator=settings,
            panelSetupWithComposeView={view,panel,entity->requireNotNull(operatorGeometry).attach(view,panel,entity)})
            else ComposeViewPanelRegistration(R.id.probe_controls,composeViewCreator=creator,settingsCreator=settings))
    }
    override fun applyProbeControl(control:ProbeControl,value:Boolean?):String {
        if(control==ProbeControl.STATUS)return "status"
        if(destroyed)return "blocked:destroyed"
        if(control==ProbeControl.STOP){endBindingComparison("stop");restoreBounds();stopProducer();return "retirement_requested"}
        if(sceneTopology?.bindingComparisonActive==true)return "blocked:binding_comparison_active"
        if(ProbeDebugFeatures.isolationBlocksChanges())return "blocked:diagnostic_isolation"
        if(!resumed||materials.isEmpty())return "blocked:not_ready"
        if(benchmarkStartNs!=0L)return "blocked:measurement_active"
        if(boundsProbe!=null&&control in listOf(ProbeControl.RESTORE_SRGB,ProbeControl.FLIP_ROWS,ProbeControl.HOST_GEOMETRY))return "blocked:bounds_active"
        when(control){ProbeControl.CPU->selectCpu();ProbeControl.GPU->{if(producerHandle!=0L||retiringHandle!=0L)return "blocked:owned";selectGpu();if(producerHandle==0L)return "failed:start"}
            ProbeControl.RESTORE_SRGB->{restoreSrgb=requireNotNull(value);updateFlags()}
            ProbeControl.FLIP_ROWS->{flipRows=requireNotNull(value);updateFlags()}
            ProbeControl.HOST_GEOMETRY->{hostGeometry=requireNotNull(value);updateFlags()};else->Unit};return "applied"
    }
    override fun applyWorkloadControl(method:String,value:String?):String {
        if(BuildConfig.DEVELOPMENT_FEEDBACK&&method !in setOf("sdk-baseline","sdk-tint","sdk-offset","sdk-revert","sdk-stale","texture-rebind","texture-revert","occluder","culling"))return "blocked:public_development_scene"
        if(method=="topology-binding")return applyBindingComparison(requireNotNull(value))
        if(sceneTopology?.bindingComparisonActive==true)return "blocked:binding_comparison_active"
        if(method=="diagnostic-isolation"){
            if(!BuildConfig.DEBUG)return "blocked:debug_only"
            if(value !in setOf("on","off"))return "rejected:isolation_value"
            if(destroyed)return "blocked:destroyed"
            // Explicit restoration is available during pause/uncertainty; normal teardown never invokes it.
            if(value=="off"){
                if(ProbeDebugFeatures.isolationBlocksChanges()&&sceneTopology?.uncertain!=false){
                    ProbeDebugFeatures.invalidateIsolation();return "blocked:topology_quarantined"
                }
                return ProbeDebugFeatures.isolationControl("off","",null,null,null)
            }
            if(!resumed||!xrReady||(BuildConfig.SESSION_CORE&&sceneOwnerEpoch==0L)||lifecycleUncertain()||source!="gpu"||producerHandle==0L||retiringHandle!=0L||
                carrier==null||materials.isEmpty()||sceneTopology?.uncertain!=false||sceneTopology?.active==null||
                boundsProbe!=null||boundsStatus!="identity"||culling||benchmarkStartNs!=0L||
                !ProbeDebugFeatures.cohortComparisonsReady())return "blocked:diagnostic_isolation_prerequisites"
            val legacy=materialKinds.entries.singleOrNull{it.value.first==2f}?.key?:return "blocked:legacy_material_missing"
            return ProbeDebugFeatures.isolationControl("on","$activityInstanceId:$sceneOwnerEpoch:$producerHandle:${sceneTopology?.revision}",
                legacy.handle.toString(),{enabled->
                    legacyDrawSuppressed=!enabled
                    legacy.setAttribute("transportFlags",transportFlags(2f))
                },sceneTopology)
        }
        // Hiding remains available even during a comparison, pause or uncertainty.
        if(method=="cohort-fixture"&&value=="off")return ProbeDebugFeatures.cohortControl("off")
        if(method=="particle-cohort"&&value=="off")return ProbeDebugFeatures.particleCohortControl("off")
        if(method=="particle-bounds"&&value=="off")return ProbeDebugFeatures.particleBoundsControl("off")
        if(ProbeDebugFeatures.isolationBlocksChanges()&&method !in setOf("diag-page","diag-profile","occluder",
            "compute-info","compute-stage","compute-activate","compute-discard","compute-rollback",
            "workload-info","workload-stage","workload-activate","workload-discard","workload-revert",
            "compute-a","compute-b","compute-revert","compute-bad-abi","compute-malformed","compute-stale","compute-rapid"))
            return "blocked:diagnostic_isolation"
        if(destroyed||!resumed||materials.isEmpty())return "blocked:not_ready"
        val producerEdits=setOf("mainCount","tracerCount","hands","grafts","simulate","reset","visualScale","graftScale")
        if(method in producerEdits&&(scaleLadder!="unprepared"||boundsProbe!=null))return "blocked:release_scale_configuration_first"
        if(benchmarkStartNs!=0L&&method!="measurementStop")return "blocked:measurement_active"
        if(boundsProbe!=null&&method !in listOf("bounds-probe","culling","diag-page","diag-profile"))return "blocked:bounds_active"
        when(method){
            "cohort-fixture"->{if(!BuildConfig.DEBUG)return "blocked:debug_only";return ProbeDebugFeatures.cohortControl(requireNotNull(value))}
            "particle-cohort"->{
                if(!BuildConfig.DEBUG)return "blocked:debug_only"
                if(scaleLadder!="particles"||controls.drawHands||controls.drawGrafts)return "blocked:prepare_particle_only_ladder"
                return ProbeDebugFeatures.particleCohortControl(requireNotNull(value))
            }
            "particle-bounds"->{
                if(!BuildConfig.DEBUG)return "blocked:debug_only"
                if(scaleLadder!="particles"||controls.drawHands||controls.drawGrafts)return "blocked:prepare_particle_only_ladder"
                return ProbeDebugFeatures.particleBoundsControl(requireNotNull(value))
            }
            "prepare-scale"->{if(!BuildConfig.DEBUG)return "blocked:debug_only";return prepareScale(requireNotNull(value))}
            "topology"->{if(!BuildConfig.DEBUG)return "blocked:debug_only";return applyTopology(requireNotNull(value))}
            "bounds-probe"->{if(!BuildConfig.DEBUG)return "blocked:debug_only";return applyBoundsProbe(requireNotNull(value))}
            "publicationPaused"->{
                if(!BuildConfig.DEBUG)return "blocked:debug_only"
                if(value !in listOf("true","false"))return "rejected:boolean"
                if(boundsProbe!=null)return "blocked:bounds_active"
                if(!configure(controls.copy(publicationPaused=value=="true")))return "rejected:native_configuration"
                if(scaleLadder!="unprepared")scaleConfigurationRevision=controls.controlRevision
            }
            "diag-page","diag-profile"->{if(!BuildConfig.DEBUG)return "blocked:debug_only";val outcome=ProbeDebugFeatures.diagnosticControl(this,method,value);diagnosticState=ProbeDebugFeatures.diagnosticStatus(this);return outcome}
            "sdk-baseline","sdk-tint","sdk-offset","sdk-revert","sdk-stale"->{if(!BuildConfig.DEBUG)return "blocked:debug_only";if(!xrReady)return "blocked:xr_not_ready";hotloadReceipt=ProbeDebugFeatures.hotload(method);return hotloadReceipt}
            "texture-rebind","texture-revert"->{
                if(!BuildConfig.DEBUG)return "blocked:debug_only"
                if(method=="texture-rebind"&&replacementMaskTexture==null){
                    val bytes=ByteBuffer.allocateDirect(1024*1024*4)
                    repeat(1024*1024){i->val value=if((i%128) in 32..95 && ((i/1024)%128) in 32..95)255 else 0;bytes.put(value.toByte()).put(value.toByte()).put(value.toByte()).put(255.toByte())};bytes.flip()
                    replacementMaskTexture=SceneTexture(1024,1024,1,if(BuildConfig.SESSION_CORE)maskSampler else sampler,null).also{it.update(bytes,1024,1024,4096)}
                }
                val texture=if(method=="texture-rebind")replacementMaskTexture else maskTexture;materials.forEach{it.setTexture("maskAtlas",requireNotNull(texture))};textureProbe=method;hotloadReceipt="texture_setters_invoked:no_pixel_proof";return hotloadReceipt
            }
            "workload-info","workload-stage","workload-activate","workload-discard","workload-revert"->{hotloadReceipt=externalWorkload(method,value);return hotloadReceipt}
            "compute-info","compute-stage","compute-activate","compute-discard","compute-rollback"->{
                hotloadReceipt=externalCompute(method,value)
                nativeCandidateState=ProbeDebugFeatures.nativeCandidateStatus()
                return hotloadReceipt
            }
            "compute-a","compute-b","compute-revert","compute-bad-abi","compute-malformed","compute-stale","compute-rapid"->{
                if(!BuildConfig.DEBUG)return "blocked:debug_only"
                if(source!="gpu"||producerHandle==0L)return "blocked:gpu_inactive"
                if(!xrReady){hotloadReceipt="blocked:xr_not_ready";return hotloadReceipt}
                fun invoke(action:String):String {
                    val native=try {JSONObject(GpuWorkloadProducer.nativeStatus(producerHandle))}
                        catch(_:Exception){return "blocked:native_status_unavailable"}
                    val kernel=native.optJSONObject("hotload")
                    val generation=kernel?.optLong("kernel_generation",0L)?:0L
                    if(native.optString("phase")!="ready"||native.optLong("generation")!=producerHandle||
                        native.optInt("sourceReady")!=1||native.optLong("submitted")<=0||
                        native.optLong("gpuFenceCompleted")<=0||native.optBoolean("stopRequested",true)||
                        native.optBoolean("teardownCompleted",true)||native.optBoolean("resourcesRetained",true)||generation<=0)
                        return "blocked:producer_not_ready"
                    return GpuWorkloadProducer.nativeHotload(producerHandle,action,generation,++hotloadRequest)
                }
                hotloadReceipt=if(method=="compute-rapid")listOf("a","b","revert").joinToString("\n"){invoke(it)} else invoke(method.removePrefix("compute-"));return hotloadReceipt
            }
            "mainCount"->if(!configure(controls.copy(drawMainCount=requireNotNull(value).toInt())))return "rejected:native_configuration"
            "tracerCount"->if(!configure(controls.copy(drawTracerCount=requireNotNull(value).toInt())))return "rejected:native_configuration"
            "hands"->if(!configure(controls.copy(drawHands=value=="true")))return "rejected:native_configuration"
            "grafts"->if(!configure(controls.copy(drawGrafts=value=="true")))return "rejected:native_configuration"
            "simulate"->if(!configure(controls.copy(simulate=value=="true")))return "rejected:native_configuration"
            "visualScale"->if(!configure(controls.copy(visualScale=requireNotNull(value).toFloat())))return "rejected:native_configuration"
            "graftScale"->if(!configure(controls.copy(graftScale=requireNotNull(value).toFloat())))return "rejected:native_configuration"
            "reset"->if(!configure(controls.copy(),true))return "rejected:native_configuration"
            "culling"->{
                if(value !in listOf("true","false"))return "rejected:boolean"
                if(value=="true")return applyBoundsProbe("behind-on")
                culling=false;sceneTopology?.culling(false)
            }
            "occluder"->{occluderVisible=value=="true";occluder?.setIsVisible(occluderVisible)}
            "measurementStart"->{
                if(scaleLadder=="unprepared"||scaleConfigurationRevision!=controls.controlRevision||boundsProbe!=null||culling||hostGeometry||!restoreSrgb||flipRows||controls.publicationPaused||source!="gpu"||producerHandle==0L||!xrReady||lifecycleUncertain()||sceneTopology?.uncertain!=false||!ProbeDebugFeatures.cohortComparisonsReady())return "blocked:scale_measurement_prerequisites"
                benchmarkStartNs=System.nanoTime();benchmarkReceipt="Interval started: ${sceneTopology?.active?.profile}, topology revision ${sceneTopology?.revision}, producer configuration ${controls.controlRevision}; no pixel/timing proof"
            }
            "measurementStop"->{if(benchmarkStartNs==0L)return "blocked:no_measurement";benchmarkReceipt="User interval ${(System.nanoTime()-benchmarkStartNs)/1000000} ms; no GPU/SDK timing proof";benchmarkStartNs=0}
            else->return "blocked:unknown"
        };return "applied"
    }
    private fun externalCompute(method:String,value:String?):String {
        if(!BuildConfig.DEBUG)return "blocked:debug_only"
        if(source!="gpu"||producerHandle==0L)return "blocked:gpu_inactive"
        if(!xrReady||lifecycleUncertain())return "blocked:xr_or_lifecycle_not_ready"
        if(method in setOf("compute-activate","compute-discard")){
            if(value==null||!Regex("[a-f0-9]{64}").matches(value))return "rejected:candidate_id"
        }else if(value!=null)return "rejected:argument"
        val native=try{JSONObject(GpuWorkloadProducer.nativeStatus(producerHandle))}catch(_:Exception){return "blocked:native_status_unavailable"}
        val kernel=native.optJSONObject("hotload")?:return "blocked:kernel_status_unavailable"
        val generation=kernel.optLong("kernel_generation",0L)
        val digest=kernel.optString("active_digest")
        if(native.optString("phase")!="ready"||native.optLong("generation")!=producerHandle||
            native.optInt("sourceReady")!=1||native.optLong("submitted")<=0||native.optLong("gpuFenceCompleted")<=0||
            native.optBoolean("stopRequested",true)||native.optBoolean("teardownCompleted",true)||
            native.optBoolean("resourcesRetained",true)||generation<=0||!Regex("[a-f0-9]{64}").matches(digest))return "blocked:producer_not_ready"
        if(hotloadRequest==Long.MAX_VALUE)return "blocked:request_id_exhausted"
        val requestId=if(method=="compute-info")hotloadRequest+1 else ++hotloadRequest
        val live=JSONObject().put("producerGeneration",producerHandle.toString())
            .put("expectedGeneration",generation.toString()).put("expectedActiveDigest",digest)
        // An unknown dispatch result consumes this identity; never automatically retry it.
        return ProbeDebugFeatures.nativeCandidateControl(method,value,producerHandle,live.toString(),requestId)
    }
    private fun externalWorkload(method:String,value:String?):String {
        if(!BuildConfig.DEBUG||!BuildConfig.EXTERNAL_WORKLOAD_HOTLOAD)return "blocked:debug_only"
        if(source!="gpu"||producerHandle==0L)return "blocked:gpu_inactive"
        if(!xrReady||lifecycleUncertain())return "blocked:xr_or_lifecycle_not_ready"
        if(method in setOf("workload-activate","workload-discard")){
            if(value==null||!Regex("[a-f0-9]{64}").matches(value))return "rejected:candidate_id"
        }else if(value!=null)return "rejected:argument"
        val native=try{JSONObject(GpuWorkloadProducer.nativeStatus(producerHandle))}catch(_:Exception){return "blocked:native_status_unavailable"}
        val kernel=native.optJSONObject("workloadReload")?:return "blocked:kernel_status_unavailable"
        val generation=kernel.optLong("kernel_generation",0L)
        val digest=kernel.optString("active_digest")
        val abi=kernel.optString("abi_sha256")
        if(native.optString("phase")!="ready"||native.optLong("generation")!=producerHandle||
            native.optInt("sourceReady")!=1||native.optLong("submitted")<=0||native.optLong("gpuFenceCompleted")<=0||
            native.optBoolean("stopRequested",true)||native.optBoolean("teardownCompleted",true)||
            native.optBoolean("resourcesRetained",true)||!kernel.optBoolean("available",false)||generation<=0||!Regex("[a-f0-9]{64}").matches(digest)||!Regex("[a-f0-9]{64}").matches(abi))return "blocked:producer_not_ready"
        if(hotloadRequest==Long.MAX_VALUE)return "blocked:request_id_exhausted"
        val requestId=if(method=="workload-info")hotloadRequest+1 else ++hotloadRequest
        val live=JSONObject().put("producerGeneration",producerHandle.toString())
            .put("expectedGeneration",generation.toString()).put("expectedActiveDigest",digest).put("abiSha256",abi).put("workloadReload",JSONObject(kernel.toString()))
        // An unknown dispatch result consumes this identity; never automatically retry it.
        return ProbeDebugFeatures.workloadCandidateControl(method,value,producerHandle,live.toString(),requestId)
    }
    private fun lifecycleUncertain():Boolean=ownerRevocationFailed||xrFocusRequestFailed||xrDetachFailed||producerStopRequestFailed||consumerGenerationRevocationFailed
    private fun runtimeIdentity():JSONObject {
        val materialHandles=org.json.JSONArray()
        materials.forEach{m->val kind=materialKinds[m];materialHandles.put(JSONObject()
            .put("handle",m.handle.toString()).put("kind",kind?.first).put("group",kind?.second))}
        val references=org.json.JSONArray()
        fixedReferences.forEach{(name,obj)->references.put(JSONObject().put("name",name)
            .put("entityId",obj.entity?.id?.toString()).put("objectHandle",obj.handle.toString())
            .put("meshHandle",obj.mesh?.handle?.toString()))}
        return JSONObject().put("packageName",packageName).put("pid",android.os.Process.myPid())
            .put("processStartElapsedMs",android.os.Process.getStartElapsedRealtime())
            .put("activityInstanceId",activityInstanceId).put("activityCreatedElapsedMs",activityCreatedElapsedMs)
            .put("sceneReadyCount",sceneReadyCount).put("sceneHandle",if(sceneReadyCount>0)scene.handle.toString() else "unavailable")
            .put("ownerEpoch",sceneOwnerEpoch).put("materialCount",materials.size).put("sceneObjectCount",objects.size)
            .put("materialHandles",materialHandles).put("fixedReferences",references)
            .put("topologyMeshReplacementRevision",sceneTopology?.revision?:0).put("observationalOnly",true)
    }
    override fun probeControlStatus():String {
        val owned=if(producerHandle!=0L)producerHandle else retiringHandle
        val native=if(owned==0L)JSONObject()else try{JSONObject(GpuWorkloadProducer.nativeStatus(owned))}catch(_:Throwable){JSONObject().put("unavailable",true)}
        return JSONObject().put("source",source).put("producerHandle",producerHandle).put("retiringHandle",retiringHandle)
            .put("capacityParticleVertices",workloadLayout?.particlesCount?.times(4)?:0).put("capacityHandVertices",workloadLayout?.handsCount?:0).put("capacityGraftVertices",workloadLayout?.graftsCount?:0)
            .put("topology",(sceneTopology?.status()?:JSONObject()).put("requestedProfile",requestedTopology)
                .put("requestedVertices",requestedVertices).put("requestedIndices",requestedIndices).put("outcome",topologyOutcome)
                .put("simulationMainCapacity",workloadLayout?.mainCapacity?:0).put("simulationTracerCapacity",workloadLayout?.tracerCapacity?:0)
                .put("retainedParticleCapacity",workloadLayout?.retainedParticleCapacity?:0).put("metadataSha256",workloadDescriptor?.metadataSha256?:"")
                .put("ladder",scaleLadder).put("producerConfigurationRevision",scaleConfigurationRevision)
                .put("fixedDiagnosticVertices",268).put("fixedDiagnosticIndices",402).put("additionalAtlasDiagnosticAndUiExcluded",true))
            .put("boundsProbe",boundsStatus)
            .put("resumed",resumed).put("materialReady",materials.isNotEmpty()).put("native",native)
            .put("culling",culling).put("occluder",occluderVisible).put("measurementActive",benchmarkStartNs!=0L).put("measurementReceipt",benchmarkReceipt)
            .put("restoreSrgb",restoreSrgb).put("flipRows",flipRows).put("hostGeometry",hostGeometry)
            .put("atlasDiagnostic",JSONObject(ProbeDebugFeatures.diagnosticStatus(this)))
            .put("diagnosticIsolation",JSONObject(ProbeDebugFeatures.isolationStatus()).put("legacyTransportZRequested",if(legacyDrawSuppressed)2 else if(hostGeometry)1 else 0))
            .put("cohortFixture",JSONObject(ProbeDebugFeatures.cohortStatus())).put("consumerGenerationRevocationFailed",consumerGenerationRevocationFailed)
            .put("particleCohortFixture",JSONObject(ProbeDebugFeatures.particleCohortStatus())).put("allFixturesComparisonsReady",ProbeDebugFeatures.cohortComparisonsReady())
            .put("sessionOwner",if(BuildConfig.SESSION_CORE)try{JSONObject(ownerStatus())}catch(_:Throwable){JSONObject().put("statusUnavailable",true)}else JSONObject().put("selected",false))
            .put("lifecycleUncertainty",JSONObject().put("ownerRevocationFailed",ownerRevocationFailed)
                .put("failedOwnerEpoch",failedOwnerRevocationEpoch).put("failedOwnerTerminal",failedOwnerRevocationTerminal)
                .put("ownerFailureScope","process").put("ownerFailureRecovery","actual_process_restart_required_not_cleanup_proof")
                .put("xrFocusRequestFailed",xrFocusRequestFailed).put("xrDetachFailed",xrDetachFailed)
                .put("producerStopRequestFailed",producerStopRequestFailed).put("externalSessionCoreCleanupProven",false))
            .put("controllerAcquisition",lastController?.let{sample->JSONObject().put("sdkEpoch",sample.sdkEpoch)
                .put("entityId",sample.entityId.toString()).put("sequence",sample.sequence).put("rawFlags",sample.rawFlags)
                .put("active",sample.active).put("tracked",sample.tracked).put("rejection",sample.rejection)
                .put("requestedPoseTimeElapsedNs",sample.requestedPoseTimeElapsedNs).put("acquiredAtElapsedNs",sample.acquiredAtElapsedNs)
                .put("sourceTimestampAvailable",false).put("rightSecondaryPressed",sample.rightSecondaryPressed)}?:JSONObject())
            .put("submissionObserver",JSONObject(currentObserverStatus()))
            .put("textureProbe",textureProbe).put("retainedMaskTextures",if(replacementMaskTexture==null)1 else 2).put("externalWorkloadHotload",JSONObject(ProbeDebugFeatures.workloadCandidateStatus())).put("sdkHotload",JSONObject(ProbeDebugFeatures.hotloadStatus())).put("hotloadReceipt",hotloadReceipt)
            .put("nativeExternalHotload",JSONObject(ProbeDebugFeatures.nativeCandidateStatus()))
            .put("developmentFeedback",if(BuildConfig.DEVELOPMENT_FEEDBACK)JSONObject(SdkFeedbackSession.snapshot(this)).put("visualRevision",feedbackVisualRevision).put("visualValue",feedbackVisualValue).put("visualRequestedValue",SdkFeedbackSession.visualValue(this)).put("pixelsProven",false)else JSONObject())
            .put("identity",runtimeIdentity())
            .put("controls",JSONObject(controls.json())).put("sdkSampledProof",false).put("controllerInputProof",false).toString()
    }
    override fun applyFeedbackControl(method:String,arguments:Map<String,String>):String {
        val id="ui-${java.util.UUID.randomUUID()}"
        if(method=="native_compute")operatorGeometry?.requested(id)
        val observation=applyFeedbackRequest(id,method,arguments)
        if(method=="native_compute")operatorGeometry?.completed(id,JSONObject(observation).optString("status","error"))
        return observation
    }
    override fun applyFeedbackRequest(requestId:String,method:String,arguments:Map<String,String>):String {
        check(Looper.myLooper()==Looper.getMainLooper())
        check(BuildConfig.DEBUG&&BuildConfig.DEVELOPMENT_FEEDBACK){"Development feedback unselected"}
        feedbackState=feedbackObservations.execute(requestId,{operatorGeometry?.state(SdkFeedbackSession.snapshot(this))?:SdkFeedbackSession.snapshot(this)},{
            JSONObject().put("activity_instance_id",activityInstanceId).put("scene_ready_count",sceneReadyCount)
                .put("visualRevision",feedbackVisualRevision).put("visualValue",feedbackVisualValue?:JSONObject.NULL)
                .put("textureUpdateCompleted",feedbackVisualValue!=null).put("pixelsProven",false).also{sceneObservation->
                    if(BuildConfig.META_XR_OPERATOR){
                        sceneObservation.put("input_geometry",requireNotNull(operatorGeometry).snapshot(JSONObject(SdkFeedbackSession.snapshot(this)),!destroyed&&resumed&&xrReady,runCatching{scene.getViewerPose()}.getOrNull()))
                        sceneObservation.put("operator_layer_loaded",requireNotNull(operatorGeometry).layerLoaded())
                    }
                }
        }){
            check(!destroyed&&ProbeDebugFeatures.target()===this){"Retired SDK scene owner"}
            if(method !in setOf("status","native_status"))check(resumed&&xrReady&&source=="cpu"&&producerHandle==0L&&retiringHandle==0L&&cpuTexture!=null){"CPU scene is not ready"}
            SdkFeedbackSession.execute(this,method,arguments)
            if(method=="native_compute"||method=="compute")publishFeedbackVisual()
        }
        return feedbackState
    }
    private fun feedbackUi(method:String,arguments:Map<String,String>) {
        try{applyFeedbackControl(method,arguments)}catch(failure:Exception){feedbackState="Refused: ${failure.message}"}
    }
    private fun publishFeedbackVisual() {
        check(Looper.myLooper()==Looper.getMainLooper()&&!destroyed&&ProbeDebugFeatures.target()===this){"Retired SDK scene owner"}
        val value=SdkFeedbackSession.visualValue(this)?:return
        val bytes=requireNotNull(cpuReferenceBytes);val layout=requireNotNull(workloadLayout)
        CpuFeedbackProjection.apply(bytes,layout,value)
        requireNotNull(cpuTexture).update(bytes,1024,1024,4096)
        feedbackVisualValue=value;feedbackVisualRevision++
    }
    override fun onResume(){super.onResume();resumed=true;bindObserver();attachXr()}
    override fun onWindowFocusChanged(hasFocus:Boolean){
        super.onWindowFocusChanged(hasFocus)
        if(producerHandle!=0L)try{GpuWorkloadProducer.nativeSetFocused(producerHandle,hasFocus&&resumed&&xrReady)}
        catch(_:Throwable){xrFocusRequestFailed=true;stopProducer()}
    }
    override fun onPause(){
        resumed=false
        try{
            endBindingComparison("pause");revokeSessionOwner(false);ProbeDebugFeatures.hideCohort();restoreBounds()
            if(benchmarkStartNs!=0L){benchmarkStartNs=0;benchmarkReceipt="invalidated:lifecycle_pause"}
            ProbeDebugFeatures.clearObserver(this)
        }finally{try{stopProducer()}finally{super.onPause()}}
    }
    override fun onDestroy(){
        operatorGeometry?.clear()
        endBindingComparison("destroy")
        destroyed=true;resumed=false
        try{revokeSessionOwner(true);ProbeDebugFeatures.detach(this)}
        finally{try{stopProducer()}finally{try{if(retiringHandle==0L)handler.removeCallbacks(poll)}finally{super.onDestroy()}}}
    }
}
