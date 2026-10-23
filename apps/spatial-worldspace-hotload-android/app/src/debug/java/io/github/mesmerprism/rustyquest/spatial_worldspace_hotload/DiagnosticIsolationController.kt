package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

/** Main-thread adapter state. A returned presentation setter is never pixel/readback proof. */
internal class DiagnosticIsolationController {
    data class Target(val name:String,val handle:String,val restoreEnabled:Boolean,val operation:String="scene_visibility",val setEnabled:(Boolean)->Unit)
    data class Observation(val name:String,val handle:String,val restoreEnabled:Boolean,val operation:String,
        var requestedEnabled:Boolean,var setterReturned:Boolean?=null)
    private var retained:List<Target>?=null
    private var observations:List<Observation> = emptyList()
    var phase="off";private set
    var requestedIsolated=false;private set
    var revision=0L;private set
    var owner="";private set
    val blocksChanges:Boolean get()=retained!=null
    fun objects():List<Observation> = observations.map{it.copy()}
    fun on(context:String,targets:()->List<Target>):String {
        if(phase=="detached")return "blocked:isolation_detached"
        if(blocksChanges)return if(phase=="on"&&owner==context)"already_isolated:no_pixel_proof" else "blocked:isolation_active_or_uncertain"
        if(revision==Long.MAX_VALUE)return "blocked:isolation_revision_exhausted"
        val selected=targets().toList()
        require(selected.isNotEmpty()&&selected.map{it.operation+":"+it.handle}.toSet().size==selected.size)
        require(selected.all{it.handle.isNotBlank()&&it.handle!="0"})
        retained=selected;owner=context;requestedIsolated=true;revision++
        observations=selected.map{Observation(it.name,it.handle,it.restoreEnabled,it.operation,false)}
        return setters(false)
    }
    fun off():String {
        if(phase=="detached")return "blocked:isolation_detached"
        if(!blocksChanges)return "already_restored:no_pixel_proof"
        if(revision==Long.MAX_VALUE)return "blocked:isolation_revision_exhausted"
        requestedIsolated=false;revision++
        return setters(true)
    }
    private fun setters(restore:Boolean):String {
        val selected=requireNotNull(retained)
        var failed=false
        selected.forEachIndexed{i,target->
            val observation=observations[i]
            observation.requestedEnabled=if(restore)target.restoreEnabled else false
            observation.setterReturned=null
            try{target.setEnabled(observation.requestedEnabled);observation.setterReturned=true}
            catch(_:Exception){failed=true} // A throwing setter may already have changed visibility.
        }
        phase=if(failed)"uncertain" else if(restore)"off" else "on"
        if(!failed&&restore)retained=null
        return if(failed)"uncertain:presentation_setter" else "presentation_setters_invoked:no_pixel_proof"
    }
    fun invalidate(){if(blocksChanges){phase="uncertain";observations.forEach{it.setterReturned=null}}}
    fun close(){
        // Normal scene teardown owns destruction; never restore/call stale SDK objects here.
        retained=null;phase="detached";observations.forEach{it.setterReturned=null}
    }
}
