package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.json.JSONArray
import org.json.JSONObject

/** A current app control surface, never a streaming qualification or paired atomic transaction. */
internal object ConcurrentStereoHubContract {
  const val PROVIDER_ID = "provider.quest.concurrent-stereo"
  const val SURFACE_ID = "surface.concurrent_stereo.controls"
  const val OWN = "command.concurrent_stereo.own"
  const val PEER = "command.concurrent_stereo.peer"
  val commands = setOf(OWN, PEER)
  // Existing Hub v1 canonical descriptor bytes; no new Broker schema.
  const val CONTRACT_SHA = "sha256:f2b08d2e1424b501f411a57bb47cbb5ea933016a31e8a2a39885238e4cdedfa3"
  fun registration(state: JSONObject): JSONObject = JSONObject()
      .put("\$schema","rusty.quest.connection_hub.surface_registration.v1").put("schema_version",1)
      .put("surface_id",SURFACE_ID).put("display_label","Current stereo source")
      .put("description","Select Own or Peer on this headset; two headset outcomes remain independent.")
      .put("surface_contract_sha256",CONTRACT_SHA)
      .put("commands",JSONArray().put(descriptor(OWN,"Own","capability.concurrent_stereo.own"))
        .put(descriptor(PEER,"Peer","capability.concurrent_stereo.peer")))
      .put("state",state)
  private fun descriptor(id:String,label:String,capability:String)=JSONObject().put("command",id).put("display_label",label).put("required_controller_capability",capability)
  /** Existing v1 retired sample lineage. A newer acquired sequence may exist; it is not the retired image. */
  fun currentPolicyPixels(snapshot: LongArray, policy: LongArray, revision: Long, nativeProcess: Long, arm: Long, maskEnabled: Boolean): Boolean {
    if (snapshot.size != 160 || policy.size != 6 || revision <= 0 || nativeProcess == 0L || arm == 0L || maskEnabled) return false
    if (snapshot[0] != 1L || snapshot[1] != 160L || snapshot[2] != nativeProcess || snapshot[5] != arm || snapshot[6] != 1L) return false
    if (snapshot[159] != 1L || snapshot[144] != revision || snapshot[27] != revision || snapshot[41] <= 0 || snapshot[54] < snapshot[41] || snapshot[54]-snapshot[41] > 1_000_000_000L) return false
    if (!(0..5).all { snapshot[28+it] == policy[it] && snapshot[138+it] == policy[it] } || snapshot[34] != policy[3] || snapshot[145] != policy[3] || snapshot[35] != 1L || snapshot[146] != 1L || snapshot[38] != 6L) return false
    return (0..1).filter { policy.take(4).contains(it.toLong()) }.all { origin ->
      val b=64+origin*32;val pixel=128+origin*5
      snapshot[b] == 1L && snapshot[b+2] == nativeProcess && snapshot[pixel] == 1L && snapshot[pixel+1] == nativeProcess && snapshot[pixel+2] == snapshot[b+3] && snapshot[pixel+3] > 0L && snapshot[pixel+3] <= snapshot[b+4] && snapshot[pixel+4] == 6L
    }
  }
  fun effectObserved(command:String,expectedRevision:Long,state:JSONObject,now:Long=android.os.SystemClock.elapsedRealtime()):Boolean {
    val at=state.optLong("sample_elapsed_ms",-1)
    return command in commands && expectedRevision>0 && state.optLong("revision",-1)==expectedRevision
      && state.optBoolean("running",false) && state.optBoolean("pair_current",false)
      && state.optBoolean("policy_pixel_current",false) && state.optString("policy")==(if(command==OWN)"own" else "peer")
      && at>=0 && now>=at && now-at<=1000
  }
}
