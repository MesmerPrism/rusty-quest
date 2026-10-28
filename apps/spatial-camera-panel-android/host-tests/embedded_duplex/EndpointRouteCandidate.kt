package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import org.json.JSONObject

/** Unadopted host prototype: derive candidate documents; never substitutes installed APK inputs. */
internal class EndpointRouteCandidate(
    baseline: Map<String, String>,
    private val native: NativeReader,
) {
  interface NativeReader {
    fun canonicalBinding(json: String): String
    fun validate(route: String, profile: String, bindings: List<String>): String
  }

  data class Selection(
      val profile: SpatialPeerNetworkProfileSelection,
      val documents: Map<String, String>,
      val nativeReadback: String,
  )

  private val base = baseline.toMap()
  private val route = JSONObject(base.getValue("route-configuration.json"))
  private val peerIds = (0 until route.getJSONArray("peers").length()).map {
    route.getJSONArray("peers").getJSONObject(it).getString("peer_id")
  }.toSet()
  private var frozen = false
  @Volatile
  var current: Selection? = null
    private set

  @Synchronized
  fun select(activeProfile: Path): Selection {
    check(!frozen) { "configuration_frozen_for_bootstrap" }
    val bytes = Files.newInputStream(activeProfile).use {
      it.readNBytes(SpatialCameraControlProfileContract.MAX_PROFILE_BYTES + 1)
    }
    // A temporary parser has no retained accepted state. Publish only the fully derived snapshot.
    val selected = SpatialPeerNetworkProfilePilot(peerIds).select(bytes)
    val byPeer = selected.peers.associateBy { it.peerId }
    val derivedRoute = JSONObject(base.getValue("route-configuration.json"))
    val byDevice = mutableMapOf<String, SpatialPeerNetworkSelection>()
    val originalPeers = derivedRoute.getJSONArray("peers")
    for (i in 0 until originalPeers.length()) {
      val peer = originalPeers.getJSONObject(i)
      val endpoints = byPeer.getValue(peer.getString("peer_id"))
      peer.getJSONObject("media_endpoint").put("host", endpoints.media.host).put("port", endpoints.media.port)
      peer.getJSONObject("control_endpoint").put("host", endpoints.control.host).put("port", endpoints.control.port)
      byDevice[peer.getString("device_id")] = endpoints
    }
    val documents = base.toMutableMap()
    documents["route-configuration.json"] = derivedRoute.toString()
    val bindings = listOf("peer_a_to_peer_b.media-binding.json", "peer_b_to_peer_a.media-binding.json")
    for (name in bindings) {
      val binding = JSONObject(base.getValue(name))
      val plan = binding.getJSONObject("quest").getJSONObject("spec").getJSONObject("plan")
      val lane = plan.getJSONArray("lanes").getJSONObject(0)
      val sourceDevice = lane.getString("source_device_id")
      val sinkDevice = lane.getString("sink_device_id")
      val sink = byDevice.getValue(sinkDevice)
      val endpoints = plan.getJSONArray("runtime_endpoints")
      for (i in 0 until endpoints.length()) {
        val endpoint = endpoints.getJSONObject(i)
        val device = endpoint.getString("device_id")
        val selectedEndpoint = byDevice.getValue(device).media
        endpoint.put("transport_bind_host", selectedEndpoint.host)
        if (device == sourceDevice) {
          endpoint.getJSONArray("source_bindings").getJSONObject(0).put("source_port", selectedEndpoint.port)
        } else {
          require(device == sinkDevice) { "foreign_binding_endpoint" }
          endpoint.getJSONArray("receiver_ports").getJSONObject(0).put("port", selectedEndpoint.port)
          endpoint.getJSONArray("transport_receive_ports").getJSONObject(0).put("port", selectedEndpoint.port)
        }
      }
      plan.getJSONArray("transport_routes").getJSONObject(0)
          .put("connect_host", sink.media.host).put("connect_port", sink.media.port)
      documents[name] = native.canonicalBinding(binding.toString())
    }
    // This is the actual production route/binding reader, not a duplicated validator.
    val readback = native.validate(documents.getValue("route-configuration.json"),
        base.getValue("packed-stereo-profile.json"), bindings.map(documents::getValue))

    // Regenerate the host candidate's closure coherently. Production still pins the original manifest.
    val manifest = JSONObject(base.getValue("product-input-manifest.json"))
    val directions = manifest.getJSONArray("directional_bindings")
    for (i in 0 until directions.length()) {
      val direction = directions.getJSONObject(i)
      val binding = JSONObject(documents.getValue(direction.getString("media_binding_path")))
      val runtimeDigest = binding.getJSONObject("quest").getString("runtime_spec_canonical_sha256")
      val lifecycleName = direction.getString("lifecycle_lock_path")
      documents[lifecycleName] = JSONObject(base.getValue(lifecycleName))
          .put("runtime_spec_canonical_sha256", runtimeDigest).toString()
      direction.put("runtime_spec_canonical_sha256", runtimeDigest)
          .put("lifecycle_lock_sha256", sha256(documents.getValue(lifecycleName)))
    }
    val artifacts = manifest.getJSONArray("artifacts")
    val closure = mutableListOf<String>()
    for (i in 0 until artifacts.length()) {
      val artifact = artifacts.getJSONObject(i)
      val name = artifact.getString("path")
      val raw = documents.getValue(name)
      val digest = sha256(raw)
      artifact.put("sha256", digest).put("size_bytes", raw.toByteArray(Charsets.UTF_8).size)
      closure += "$name=$digest"
    }
    manifest.put("closure_sha256", sha256(closure.joinToString("\n")))
    documents["product-input-manifest.json"] = manifest.toString()
    val accepted = Selection(selected, Collections.unmodifiableMap(documents), readback)
    current = accepted
    return accepted
  }

  @Synchronized
  fun freezeForBootstrap(): Selection {
    val selection = requireNotNull(current) { "endpoint_selection_required" }
    frozen = true
    return selection
  }

  companion object {
    fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }
}
