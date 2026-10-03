package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.json.JSONObject
import java.util.concurrent.CompletableFuture

/** Private products supply presets; this neutral carrier accepts only exact Hub-authorized calls. */
internal abstract class ConcurrentStereoHubTarget : ConnectionHubSurfaceTarget {
  final override val hubSurfaceProfile = ConnectionHubSurfaceProfile.ConcurrentStereo
  final override fun applyHubAuthorizedCommand(requestId:String,surfaceId:String,command:String,args:JSONObject,authorityReceipt:JSONObject):Long =
      throw IllegalStateException("current_duplex_effect_is_asynchronous")
  final override fun applyHubAuthorizedCommandAsync(requestId:String,surfaceId:String,command:String,args:JSONObject,authorityReceipt:JSONObject,cancelled:java.util.concurrent.atomic.AtomicBoolean):CompletableFuture<Long> {
    requireConnectionHubProfileCommandAuthorization(requestId,surfaceId,command,args,authorityReceipt,hubSurfaceProfile)
    check(!cancelled.get() && hubSurfaceAvailable()) { "current_duplex_surface_unavailable" }
    return submitCurrentPolicy(command,cancelled)
  }
  protected abstract fun submitCurrentPolicy(command:String,cancelled:java.util.concurrent.atomic.AtomicBoolean):CompletableFuture<Long>
}

/** Dispatch may have happened; this never asserts an application effect. */
internal class ConnectionHubOutcomeUnknownException(cause: Throwable) : RuntimeException("current effect outcome unavailable", cause)
