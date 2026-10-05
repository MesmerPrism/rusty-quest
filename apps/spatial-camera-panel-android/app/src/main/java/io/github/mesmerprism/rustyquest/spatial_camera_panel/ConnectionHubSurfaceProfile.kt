package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.json.JSONObject

/** Closed app-owned profiles. Callers cannot add descriptors, capabilities or acceptance rules. */
internal enum class ConnectionHubSurfaceProfile {
  LockedPlaylist, ConcurrentStereo;
  val surfaceId: String get() = when(this) { LockedPlaylist -> ConnectionHubLockedPlaylistContract.SURFACE_ID; ConcurrentStereo -> ConcurrentStereoHubContract.SURFACE_ID }
  val providerId: String get() = when(this) { LockedPlaylist -> ConnectionHubLockedPlaylistContract.PROVIDER_ID; ConcurrentStereo -> ConcurrentStereoHubContract.PROVIDER_ID }
  val commands: Set<String> get() = when(this) { LockedPlaylist -> ConnectionHubLockedPlaylistContract.commands; ConcurrentStereo -> ConcurrentStereoHubContract.commands }
  fun registration(state: JSONObject): JSONObject = when(this) { LockedPlaylist -> connectionHubSurfaceRegistration(state); ConcurrentStereo -> ConcurrentStereoHubContract.registration(state) }
  fun effectObserved(command: String, revision: Long, state: JSONObject): Boolean = when(this) { LockedPlaylist -> connectionHubCommandEffectObserved(command, revision, state); ConcurrentStereo -> ConcurrentStereoHubContract.effectObserved(command, revision, state) }
  fun publishDelay(state: JSONObject): Long? = when(this) { LockedPlaylist -> connectionHubSurfaceStatePublishDelayMs(true,state); ConcurrentStereo -> if(state.optBoolean("running",false)) 1000L else null }
}
