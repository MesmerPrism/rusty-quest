package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.toolkit.AppSystemActivity
import org.json.JSONObject

/** Test-only unrelated collaborators; ownership decisions remain in the actual production facade. */
object BuildConfig { const val EXTERNAL_WORKLOAD_HOTLOAD=false;const val SUBMISSION_OBSERVER=false;const val SDK_GRAPHICS_HOT_RELOAD=false }
class SdkHotloadController(value:AppSystemActivity) { fun apply(value:String)="unused";fun status()="{}" }
class NativeHotloadCandidateStore(value:ProbeControlTarget) {
    fun apply(method:String,value:String?,handle:Long,live:JSONObject,requestId:Long)="unused"
    fun status()="{}";fun candidateId()=""
}
class ExternalWorkloadCandidateStore(value:ProbeControlTarget) {
    fun apply(method:String,value:String?,handle:Long,live:JSONObject,requestId:Long)="unused"
    fun status()="{}";fun candidateId()=""
}
class WorkloadSceneTopology { fun diagnosticVisibilityTargets():List<Pair<SceneObject,Boolean>> = emptyList() }
class DiagnosticIsolationController {
    class Target(val name:String,val handle:String,val restoreEnabled:Boolean,val operation:String="visibility",val write:(Boolean)->Unit) { val requestedEnabled=true;val setterReturned:Boolean?=null }
    val blocksChanges=false;val phase="off";val requestedIsolated=false;val revision=0;val owner=""
    fun objects():List<Target> = emptyList()
    fun invalidate()=Unit;fun close()=Unit;fun off()="unused"
    fun on(context:String,targets:()->List<Target>)="unused"
}
class SubmissionObserverProbe(selected:Boolean,epoch:Long) {
    fun bind(instance:Long,session:Long,epoch:Long)=Unit;fun clear()=Unit;fun active()=false;fun status()="{}"
}
class CohortFixtureController(value:AppSystemActivity) {
    fun comparisonsReady()=true;fun hide()=Unit;fun apply(value:String)="unused";fun status()="{}"
}
class ParticleCohortFixtureController(value:AppSystemActivity) {
    fun comparisonsReady()=true;fun hide()=Unit;fun apply(value:String)="unused";fun applyBounds(value:String)="unused";fun status()="{}"
}
fun sdkGraphicsHotReloadFeatures(activity:AppSystemActivity,selected:Boolean):List<SpatialFeature> = emptyList()

/** Native material call recorder: touching a destroyed owner's controller throws, as in the observed failure. */
class AtlasDiagnosticController(val owner:AppSystemActivity,val initialTexture:SceneTexture) {
    companion object {
        val created=mutableListOf<AtlasDiagnosticController>()
        var constructing:((AppSystemActivity)->Unit)?=null
    }
    val calls=mutableListOf<String>()
    var invalid=false
    init { created.add(this);constructing?.invoke(owner) }
    private fun record(value:String){check(!invalid){"Destroyed owner material was accessed"};calls.add(value)}
    fun texture(value:SceneTexture){record("texture:${value.label}")}
    fun cohort(generation:Long,primaryOffset:Int){record("cohort:$generation:$primaryOffset")}
    fun apply(method:String,value:String?):String {record("control:$method:$value");return "applied:$value"}
    fun status():String {record("status");return "diagnostic:${(owner as DiagnosticOwnerHostTest.Owner).name}"}
}
