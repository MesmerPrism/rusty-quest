package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

/** Closed, static matching-particle calibration; no simulation or shader execution here. */
internal object ParticleBoundsCalibration {
    val commands=listOf("off","packet-origin","host-front","host-behind","packet-behind-off","packet-behind-on")
    fun permits(previous:String,next:String):Boolean = next=="off" || when(previous){
        "off"->next=="packet-origin"
        "packet-origin"->next=="host-front"
        "host-front"->next=="host-behind"
        "host-behind","packet-behind-on"->next=="packet-behind-off"
        "packet-behind-off"->next=="packet-behind-on"
        else->false
    }
    // workload.vert's existing host branch is (corner.xy*.025 + (0,1.4),1.5).
    // Translate its centre to the matching packet's SDK point (.65,1.35,1).
    fun frontTranslation()=floatArrayOf(.65f,1.35f-1.4f,1f-1.5f)
    fun maximumHostBranchDepth(pose:BoundsProbePose,eye:FloatArray=pose.eye,forward:FloatArray=pose.forward):Float {
        require(eye.size==3&&forward.size==3&&eye.all{it.isFinite()}&&forward.all{it.isFinite()})
        return listOf(-.025f,.025f).flatMap{x->listOf(-.025f,.025f).map{y->
            val corner=floatArrayOf(x,1.4f+y,1.5f)
            (0..2).sumOf{((pose.translation[it]+corner[it]-eye[it])*forward[it]).toDouble()}.toFloat()
        }}.max()
    }
    fun behindRemainsValid(pose:BoundsProbePose,eye:FloatArray,forward:FloatArray)=
        pose.maximumHostDepth(eye,forward)< -1f && maximumHostBranchDepth(pose,eye,forward)< -1f
}
