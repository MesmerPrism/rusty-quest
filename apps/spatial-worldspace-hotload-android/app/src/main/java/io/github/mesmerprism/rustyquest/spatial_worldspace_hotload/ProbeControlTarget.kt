package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

enum class ProbeControl { CPU, GPU, STOP, RESTORE_SRGB, FLIP_ROWS, HOST_GEOMETRY, STATUS }
interface ProbeControlTarget {
    fun applyProbeControl(control: ProbeControl, value: Boolean?): String
    fun applyWorkloadControl(method:String,value:String?):String
    fun probeControlStatus(): String
    fun applyFeedbackControl(method:String,arguments:Map<String,String>):String="blocked:feedback_unselected"
    fun applyFeedbackRequest(requestId:String,method:String,arguments:Map<String,String>):String="blocked:feedback_unselected"
}
