package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

/** A debug reader's consumption state; no recording, SDK or XR authority. */
internal data class ObserverSample(val available:Boolean, val contextState:Int,
    val instanceGeneration:Long?, val sessionGeneration:Long?, val acceptedSequence:Long?,
    val eligible:Boolean, val frameAgeNs:Long?, val pollAgeNs:Long?, val libraryIdentity:String?)
internal data class ObserverDecision(val binding:String, val newSincePreviousRead:Boolean,
    val freshAdvancingSubmissionAtThisRead:Boolean)
internal class SubmissionObserverReadPolicy {
    var instance=0L; private set
    var session=0L; private set
    var appEpoch=0L; private set
    var expectedInstance=0L; private set
    var expectedSession=0L; private set
    private var previousAccepted=0L
    private var identity:String?=null
    private var blocked=false
    fun bind(instance:Long,session:Long,epoch:Long) {
        require(instance!=0L&&session!=0L&&epoch>0L)
        this.instance=instance;this.session=session;appEpoch=epoch
        expectedInstance=0;expectedSession=0;previousAccepted=0;identity=null;blocked=false
    }
    fun clear(){instance=0;session=0;appEpoch=0;expectedInstance=0;expectedSession=0;previousAccepted=0;identity=null;blocked=false}
    fun observe(sample:ObserverSample):ObserverDecision {
        fun reject(reason:String)=ObserverDecision(reason,false,false)
        if(instance==0L||session==0L)return reject("no_sdk_context")
        if(blocked)return reject("await_next_sdk_epoch")
        if(!sample.available)return reject("readback_unavailable")
        if(sample.contextState!=0){if(expectedInstance!=0L){blocked=true;previousAccepted=0};return reject("context_${sample.contextState}")}
        val parent=sample.instanceGeneration;val child=sample.sessionGeneration
        if(parent==null||child==null||parent<=0||child<=0||sample.libraryIdentity.isNullOrEmpty()) {
            blocked=true;return reject("identity_not_representable")
        }
        if(expectedInstance==0L){expectedInstance=parent;expectedSession=child;identity=sample.libraryIdentity}
        else if(parent!=expectedInstance||child!=expectedSession||sample.libraryIdentity!=identity){blocked=true;previousAccepted=0;return reject("identity_changed")}
        val accepted=sample.acceptedSequence
        if(accepted==null||accepted<0||accepted<previousAccepted){blocked=true;previousAccepted=0;return reject("sequence_invalid")}
        val advancing=accepted>previousAccepted;previousAccepted=accepted
        val age=sample.frameAgeNs;val poll=sample.pollAgeNs
        val fresh=sample.eligible&&advancing&&age!=null&&poll!=null&&age in 0..1_000_000_000L&&poll in 0..1_000_000_000L
        return ObserverDecision("bound_exact_context",advancing,fresh)
    }
}
