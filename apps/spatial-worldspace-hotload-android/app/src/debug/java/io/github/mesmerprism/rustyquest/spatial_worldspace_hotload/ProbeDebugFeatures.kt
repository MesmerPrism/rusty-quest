package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.runtime.SceneTexture
import java.lang.ref.WeakReference
import android.os.Looper

internal object ProbeDebugFeatures {
    private var target = WeakReference<ProbeControlTarget>(null)
    private var controller:SdkHotloadController?=null
    private var nativeCandidates:NativeHotloadCandidateStore?=null
    private var workloadCandidates:ExternalWorkloadCandidateStore?=null
    fun workloadCandidateControl(method:String,value:String?,handle:Long,live:String,requestId:Long):String=
        if(!BuildConfig.EXTERNAL_WORKLOAD_HOTLOAD)"blocked:external_workload_unselected" else workloadCandidates?.apply(method,value,handle,org.json.JSONObject(live),requestId)?:"blocked:no_activity"
    fun workloadCandidateStatus():String=if(BuildConfig.EXTERNAL_WORKLOAD_HOTLOAD)workloadCandidates?.status()?:"{}" else "{}"
    fun workloadCandidateId():String=if(BuildConfig.EXTERNAL_WORKLOAD_HOTLOAD)workloadCandidates?.candidateId()?:"" else ""

    fun nativeCandidateControl(method:String,value:String?,handle:Long,live:String,requestId:Long):String=
        nativeCandidates?.apply(method,value,handle,org.json.JSONObject(live),requestId)?:"blocked:no_activity"
    fun nativeCandidateStatus():String=nativeCandidates?.status()?:"{}"
    fun nativeCandidateId():String=nativeCandidates?.candidateId()?:""
    private var isolation=DiagnosticIsolationController()
    fun isolationBlocksChanges():Boolean=isolation.blocksChanges
    fun invalidateIsolation(){isolation.invalidate()}
    fun isolationControl(value:String,context:String,legacyMaterialHandle:String?,setLegacyDrawEnabled:((Boolean)->Unit)?,topology:WorkloadSceneTopology?):String {
        check(Looper.myLooper()==Looper.getMainLooper())
        return when(value){
            "off"->isolation.off()
            "on"->isolation.on(context){
                listOf(DiagnosticIsolationController.Target("legacy-barcode-draw",requireNotNull(legacyMaterialHandle),true,
                    "legacy_material_draw_flag",requireNotNull(setLegacyDrawEnabled)))+
                    requireNotNull(topology).diagnosticVisibilityTargets().mapIndexed{i,(obj,visible)->
                        DiagnosticIsolationController.Target("ordinary-workload-$i",obj.handle.toString(),visible){obj.setIsVisible(it)}}
            }
            else->"rejected:isolation_value"
        }
    }
    fun isolationStatus():String=org.json.JSONObject().put("schema","rusty.quest.diagnostic_isolation.v1")
        .put("phase",isolation.phase).put("requestedIsolated",isolation.requestedIsolated)
        .put("blocksSceneChanges",isolation.blocksChanges).put("revision",isolation.revision).put("owner",isolation.owner)
        .put("targets",org.json.JSONArray().also{a->isolation.objects().forEach{o->a.put(org.json.JSONObject()
            .put("name",o.name).put("targetHandle",o.handle).put("operation",o.operation).put("restoreEnabledRequested",o.restoreEnabled)
            .put("enabledRequested",o.requestedEnabled).put("setterReturned",o.setterReturned?:org.json.JSONObject.NULL))}})
        .put("visibilityReadbackAvailable",false).put("sdkSampledProof",false).put("presentationProof",false)
        .put("nativeConfigurationChanged",false).put("carrierVisibilityChanged",false).toString()
    private var diagnostic:AtlasDiagnosticController?=null
    private fun diagnosticOwner(value:ProbeControlTarget):Boolean =
        Looper.myLooper()==Looper.getMainLooper()&&target.get()===value
    private fun diagnosticFor(value:ProbeControlTarget):AtlasDiagnosticController? =
        if(diagnosticOwner(value))diagnostic else null
    private var cohort:CohortFixtureController?=null
    private var particleCohort:ParticleCohortFixtureController?=null
    private var ownerEpoch=0L
    private var observer:SubmissionObserverProbe?=null
    private fun observerFor(value:ProbeControlTarget):SubmissionObserverProbe? =
        if(Looper.myLooper()==Looper.getMainLooper()&&target.get()===value)observer else null
    fun bindObserver(value:ProbeControlTarget,instance:Long,session:Long,epoch:Long){observerFor(value)?.bind(instance,session,epoch)}
    fun clearObserver(value:ProbeControlTarget){observerFor(value)?.clear()}
    fun observerActive(value:ProbeControlTarget)=observerFor(value)?.active()?:false
    fun observerStatus(value:ProbeControlTarget):String=observerFor(value)?.status()?:"{\"buildSelected\":${BuildConfig.SUBMISSION_OBSERVER},\"phase\":\"inactive_owner_or_thread\",\"gpuCompletionProof\":false,\"sdkPixelProof\":false,\"presentationProof\":false,\"freshAdvancingSubmissionAtThisRead\":false}"
    fun attach(value: ProbeControlTarget) {
        check(Looper.myLooper()==Looper.getMainLooper())
        // Old native materials are invalid after scene teardown, even before old onDestroy.
        if(target.get()!==value)diagnostic=null
        isolation.close();isolation=DiagnosticIsolationController()
        observer?.clear();observer=null;hideCohort();cohort=null;particleCohort=null
        if(ownerEpoch!=Long.MAX_VALUE)observer=SubmissionObserverProbe(BuildConfig.SUBMISSION_OBSERVER,++ownerEpoch)
        target = WeakReference(value);controller=SdkHotloadController(value as AppSystemActivity);nativeCandidates=NativeHotloadCandidateStore(value);workloadCandidates=if(BuildConfig.EXTERNAL_WORKLOAD_HOTLOAD)ExternalWorkloadCandidateStore(value) else null
    }
    fun detach(value: ProbeControlTarget) { check(Looper.myLooper()==Looper.getMainLooper());if(target.get() === value) {isolation.close();hideCohort();cohort=null;particleCohort=null;target.clear();controller=null;nativeCandidates=null;workloadCandidates=null;diagnostic=null;observer?.clear();observer=null} }
    fun createCohort(activity:AppSystemActivity){
        if(cohort==null)cohort=CohortFixtureController(activity)
        if(particleCohort==null)particleCohort=ParticleCohortFixtureController(activity)
    }
    fun hideCohort(){cohort?.hide();particleCohort?.hide()}
    fun cohortControl(value:String):String {
        if(value!="off"&&particleCohort?.comparisonsReady()==false)return "blocked:particle_fixture_active_or_uncertain"
        return cohort?.apply(value)?:"blocked:no_fixture_scene"
    }
    fun particleCohortControl(value:String):String {
        if(value!="off"&&cohort?.comparisonsReady()==false)return "blocked:hand_graft_fixture_active_or_uncertain"
        return particleCohort?.apply(value)?:"blocked:no_fixture_scene"
    }
    fun particleBoundsControl(value:String):String {
        if(value!="off"&&cohort?.comparisonsReady()==false)return "blocked:hand_graft_fixture_active_or_uncertain"
        return particleCohort?.applyBounds(value)?:"blocked:no_fixture_scene"
    }
    fun cohortStatus():String=cohort?.status()?:"{}"
    fun particleCohortStatus():String=particleCohort?.status()?:"{}"
    // Both independent fixture scenes must be off and certain for a live comparison.
    fun cohortComparisonsReady():Boolean=(cohort?.comparisonsReady()?:true)&&(particleCohort?.comparisonsReady()?:true)
    fun createDiagnostic(value:ProbeControlTarget,texture:SceneTexture){
        if(!diagnosticOwner(value))return
        val created=AtlasDiagnosticController(value as AppSystemActivity,texture)
        // Keep publication owner-bound even if a constructor callback replaced the Activity.
        if(diagnosticOwner(value))diagnostic=created
    }
    fun diagnosticTexture(value:ProbeControlTarget,texture:SceneTexture){diagnosticFor(value)?.texture(texture)}
    fun diagnosticCohort(value:ProbeControlTarget,generation:Long,primaryOffset:Int){diagnosticFor(value)?.cohort(generation,primaryOffset)}
    fun diagnosticControl(value:ProbeControlTarget,method:String,arg:String?):String=diagnosticFor(value)?.apply(method,arg)?:"blocked:no_diagnostic"
    fun diagnosticStatus(value:ProbeControlTarget):String=diagnosticFor(value)?.status()?:"{}"
    fun hotload(action:String):String=controller?.apply(action)?:"blocked:no_activity"
    fun hotloadStatus():String=controller?.status()?:"{}"
    fun target(): ProbeControlTarget? = target.get()
    fun features(activity: AppSystemActivity): List<SpatialFeature> =
        sdkGraphicsHotReloadFeatures(activity, BuildConfig.SDK_GRAPHICS_HOT_RELOAD)
}
