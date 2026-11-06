package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload
import android.content.Context
/** No shared loader linkage in an unselected or release build. */
internal object SdkFeedbackSession {
    fun filesDirectory(context:Context):java.io.File=throw IllegalStateException("Development feedback unselected")
    fun execute(context:Context,method:String,arguments:Map<String,String>):Map<String,String> =throw IllegalStateException("Development feedback unselected")
    fun snapshot(context:Context):Map<String,String> =mapOf("selection" to "disabled")
    fun visualValue(context:Context):Long?=null
}
