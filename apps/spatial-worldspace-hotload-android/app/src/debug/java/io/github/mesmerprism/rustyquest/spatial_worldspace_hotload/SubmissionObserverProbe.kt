package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import org.json.JSONObject

internal object SubmissionObserverNative {
    init { System.loadLibrary("rq_xr_submission_observer_probe") }
    external fun read(instance:Long,session:Long,expectedInstance:Long,expectedSession:Long):String
}
/** Main-thread-only owner of the debug reader, independent of GPU producer. */
internal class SubmissionObserverProbe(private val selected:Boolean,private val ownerEpoch:Long) {
    private val policy=SubmissionObserverReadPolicy()
    fun bind(instance:Long,session:Long,epoch:Long){if(selected)policy.bind(instance,session,epoch)}
    fun clear(){policy.clear()}
    fun active()=selected&&policy.instance!=0L&&policy.session!=0L
    fun status():String {
        if(!selected)return JSONObject().put("buildSelected",false).put("phase","unselected").toString()
        val raw=try {JSONObject(SubmissionObserverNative.read(policy.instance,policy.session,policy.expectedInstance,policy.expectedSession))}
            catch(_:Throwable){JSONObject().put("buildSelected",true).put("readbackAvailable",false).put("phase","native_probe_unavailable")}
        fun number(value:JSONObject,name:String)=value.optString(name).toLongOrNull()
        val frame=raw.optJSONObject("frame")?:JSONObject()
        val identity=if(raw.optBoolean("libraryIdentityAvailable",false))
            "${raw.optString("processId")}:${raw.optString("libraryBase")}:${raw.optString("libraryPath")}" else null
        val decision=policy.observe(ObserverSample(raw.optBoolean("readbackAvailable",false),raw.optInt("contextState",-1),
            number(raw,"instanceGeneration"),number(raw,"sessionGeneration"),number(frame,"acceptedSequence"),
            frame.optBoolean("eligibleAtRead",false),number(frame,"ageNs"),number(frame,"pollAgeNs"),identity))
        return raw.put("activityOwnerEpoch",ownerEpoch.toString()).put("appAttachmentEpoch",policy.appEpoch.toString()).put("binding",decision.binding)
            .put("newSincePreviousRead",decision.newSincePreviousRead)
            .put("freshAdvancingSubmissionAtThisRead",decision.freshAdvancingSubmissionAtThisRead)
            .put("freshnessPolicyNs","1000000000").put("gpuCompletionProof",false).put("sdkPixelProof",false).put("presentationProof",false).toString()
    }
}
