package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.runtime.SceneTexture

internal object ProbeDebugFeatures {
    fun isolationBlocksChanges():Boolean=false
    fun invalidateIsolation()=Unit
    fun isolationControl(value:String,context:String,legacyMaterialHandle:String?,setLegacyDrawEnabled:((Boolean)->Unit)?,topology:WorkloadSceneTopology?):String="blocked:debug_only"
    fun isolationStatus():String="{}"
    fun nativeCandidateControl(method:String,value:String?,handle:Long,live:String,requestId:Long):String="blocked:debug_only"
    fun nativeCandidateStatus():String="{}"
    fun nativeCandidateId():String=""
    fun workloadCandidateControl(method:String,value:String?,handle:Long,live:String,requestId:Long):String="blocked:debug_only"
    fun workloadCandidateStatus():String="{}"
    fun workloadCandidateId():String=""
    fun bindObserver(value:ProbeControlTarget,instance:Long,session:Long,epoch:Long)=Unit
    fun clearObserver(value:ProbeControlTarget)=Unit
    fun observerActive(value:ProbeControlTarget)=false
    fun observerStatus(value:ProbeControlTarget):String="{\"buildSelected\":false,\"phase\":\"unselected\"}"
    fun createDiagnostic(value:ProbeControlTarget,texture:SceneTexture)=Unit
    fun diagnosticTexture(value:ProbeControlTarget,texture:SceneTexture)=Unit
    fun diagnosticCohort(value:ProbeControlTarget,generation:Long,primaryOffset:Int)=Unit
    fun diagnosticControl(value:ProbeControlTarget,method:String,arg:String?):String="blocked:debug_only"
    fun diagnosticStatus(value:ProbeControlTarget):String="{}"
    fun createCohort(activity:AppSystemActivity)=Unit
    fun hideCohort()=Unit
    fun cohortControl(value:String):String="blocked:debug_only"
    fun cohortStatus():String="{}"
    fun particleCohortControl(value:String):String="blocked:debug_only"
    fun particleBoundsControl(value:String):String="blocked:debug_only"
    fun particleCohortStatus():String="{}"
    fun cohortComparisonsReady():Boolean=true
    fun hotload(action:String):String="blocked:debug_only"
    fun hotloadStatus():String="{}"
    fun attach(value: ProbeControlTarget) = Unit
    fun detach(value: ProbeControlTarget) = Unit
    fun features(activity: AppSystemActivity): List<SpatialFeature> = emptyList()
}
