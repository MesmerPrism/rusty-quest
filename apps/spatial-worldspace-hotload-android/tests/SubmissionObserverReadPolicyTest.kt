package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

/** Runs the real pure consumer policy; no Android, SDK or mocked layer implementation. */
fun main() {
    var assertions=0
    fun verify(value:Boolean,message:String){assertions++;check(value){message}}
    val policy=SubmissionObserverReadPolicy()
    val good=ObserverSample(true,0,11,22,1,true,10,20,"process:module")
    verify(!policy.observe(good).freshAdvancingSubmissionAtThisRead,"unbound reader admitted")
    policy.bind(1,2,1)
    verify(policy.observe(good).freshAdvancingSubmissionAtThisRead,"fresh exact sample rejected")
    verify(!policy.observe(good).newSincePreviousRead,"repeat promoted")
    verify(!policy.observe(good.copy(acceptedSequence=2,frameAgeNs=1_000_000_001)).freshAdvancingSubmissionAtThisRead,"old return admitted")
    verify(!policy.observe(good.copy(acceptedSequence=3,pollAgeNs=null)).freshAdvancingSubmissionAtThisRead,"missing poll admitted")
    verify(!policy.observe(good.copy(acceptedSequence=4,pollAgeNs=-1)).freshAdvancingSubmissionAtThisRead,"negative age admitted")
    verify(!policy.observe(good.copy(acceptedSequence=5,eligible=false)).freshAdvancingSubmissionAtThisRead,"non-witness metadata admitted")
    verify(policy.observe(good.copy(acceptedSequence=6)).freshAdvancingSubmissionAtThisRead,"advancing recovery rejected")
    verify(!policy.observe(good.copy(available=false)).freshAdvancingSubmissionAtThisRead,"absent library admitted")
    verify(!policy.observe(good.copy(contextState=5)).freshAdvancingSubmissionAtThisRead,"stale context admitted")
    verify(policy.observe(good.copy(acceptedSequence=7)).binding=="await_next_sdk_epoch","stale context silently rebound")
    policy.bind(1,2,2)
    verify(policy.observe(good).freshAdvancingSubmissionAtThisRead,"explicit new epoch rejected")
    verify(policy.observe(good.copy(libraryIdentity="different-module")).binding=="identity_changed","module drift accepted")
    policy.bind(1,2,3)
    policy.observe(good)
    verify(policy.observe(good.copy(sessionGeneration=23)).binding=="identity_changed","session reuse accepted")
    policy.bind(1,2,4)
    policy.observe(good.copy(acceptedSequence=9))
    verify(policy.observe(good).binding=="sequence_invalid","regressed sequence accepted")
    policy.bind(1,2,5)
    verify(policy.observe(good.copy(instanceGeneration=null)).binding=="identity_not_representable","overflow/unrepresentable generation accepted")
    policy.clear()
    verify(policy.instance==0L&&policy.session==0L&&policy.appEpoch==0L&&!policy.observe(good).freshAdvancingSubmissionAtThisRead,"pause retained binding")
    println("actual-reader-policy assertions=$assertions")
}
