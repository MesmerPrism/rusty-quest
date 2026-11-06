package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

/** Distinct debug-only copied-byte intake; never a native path or generic action. */
internal object ExternalWorkloadNative {
    external fun stage(handle:Long,code:ByteArray,digest:String,abi:String,expectedProducer:Long,expectedGeneration:Long,expectedDigest:String,requestId:Long):String
    external fun request(handle:Long,action:String,candidateId:String,expectedProducer:Long,expectedGeneration:Long,expectedDigest:String,requestId:Long):String
}
