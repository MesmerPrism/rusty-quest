package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload
import android.content.res.AssetManager
import android.view.Surface
/** One independent GPU producer; it borrows SDK XR context and never runs XR frames. */
object GpuWorkloadProducer {
    init { System.loadLibrary("spatial_gpu_workload") }
    external fun nativeStart(surface: Surface, assets: AssetManager): Long
    external fun nativeConfigure(handle: Long, config: String): Boolean
    external fun nativeStatus(handle: Long): String
    external fun nativeHotload(handle:Long, action:String, expectedGen:Long, requestId:Long):String
    external fun nativeHotloadStage(handle:Long,code:ByteArray,digest:String,abi:String,expectedProducer:Long,expectedGeneration:Long,expectedDigest:String,requestId:Long):String
    external fun nativeHotloadExternal(handle:Long,action:String,candidateId:String,expectedProducer:Long,expectedGeneration:Long,expectedDigest:String,requestId:Long):String
    external fun nativeStop(handle: Long)
    external fun nativeAttachXr(handle: Long, instance: Long, session: Long, proc: Long,
        generation: Long, referenceSpaceType: Int, focused: Boolean): Boolean
    external fun nativeSetFocused(handle: Long, focused: Boolean)
    external fun nativeDetachXr(handle: Long)
}
