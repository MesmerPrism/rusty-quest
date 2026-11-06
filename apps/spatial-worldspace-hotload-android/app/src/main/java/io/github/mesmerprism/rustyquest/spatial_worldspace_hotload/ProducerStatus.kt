package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload
import org.json.JSONObject
/** Only a typed native cleanup acknowledgment permits reuse of the Surface. */
internal object ProducerStatus {
    fun teardownCompleted(status:String):Boolean = try { JSONObject(status).opt("teardownCompleted").let { it is Boolean && it } } catch(_:Exception){false}
}
