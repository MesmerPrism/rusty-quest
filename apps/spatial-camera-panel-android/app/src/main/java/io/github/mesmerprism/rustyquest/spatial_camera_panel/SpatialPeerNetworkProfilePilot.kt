package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.security.MessageDigest
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject

internal data class SpatialPeerNetworkEndpoint(val host: String, val port: Int)

internal data class SpatialPeerNetworkSelection(
    val peerId: String,
    val media: SpatialPeerNetworkEndpoint,
    val control: SpatialPeerNetworkEndpoint,
)

internal data class SpatialPeerNetworkProfileSelection(
    val profile: SpatialCameraControlProfile,
    val profileSha256: String,
    val peers: List<SpatialPeerNetworkSelection>,
)

/** Inert data pilot. No Activity, enrollment, native route or socket consumer yet. */
internal class SpatialPeerNetworkProfilePilot(enrolledPeerIds: Set<String>) {
  private val peers = enrolledPeerIds.toSet()

  init {
    require(peers.size == 2 && peers.none(String::isBlank)) { "two_enrolled_peers_required" }
  }

  @Volatile
  var current: SpatialPeerNetworkProfileSelection? = null
    private set

  @Synchronized
  fun select(bytes: ByteArray): SpatialPeerNetworkProfileSelection {
    require(bytes.isNotEmpty() && bytes.size <= SpatialCameraControlProfileContract.MAX_PROFILE_BYTES) {
      "profile_byte_bounds"
    }
    val snapshot = bytes.copyOf()
    val root = StrictJsonByteIngress.parseObject(snapshot)
    val endpoints = root.remove("peer_network_endpoints")
    require(endpoints is JSONArray && endpoints.length() == 2) { "two_peer_endpoints_required" }
    // Delegate every existing schema/control/metadata check to its existing owner.
    // The ordinary Activity parser continues rejecting this pilot-only extension.
    val profile = SpatialCameraControlProfileContract.parse(root.toString().toByteArray(Charsets.UTF_8))
    val selected = (0 until endpoints.length()).map { index ->
      val peer = endpoints.getJSONObject(index)
      peer.requireOnlyKeys("peer_id", "media_endpoint", "control_endpoint")
      val id = peer.requireString("peer_id")
      require(id in peers) { "peer_not_enrolled" }
      val media = endpoint(peer.requireObject("media_endpoint"), "rmanvid-v4-packed-stereo")
      val control = endpoint(peer.requireObject("control_endpoint"), "rqod1")
      require(media != control) { "media_control_endpoint_collision" }
      SpatialPeerNetworkSelection(id, media, control)
    }
    require(selected.map { it.peerId }.toSet() == peers) { "enrolled_peer_coverage" }
    require(selected[0].media != selected[1].media && selected[0].control != selected[1].control) {
      "peer_endpoint_collision"
    }
    val accepted = SpatialPeerNetworkProfileSelection(
        profile,
        MessageDigest.getInstance("SHA-256").digest(snapshot).joinToString("") { "%02x".format(it) },
        Collections.unmodifiableList(selected),
    )
    // One publication after complete validation; failure leaves the previous object intact.
    current = accepted
    return accepted
  }

  private fun endpoint(json: JSONObject, protocol: String): SpatialPeerNetworkEndpoint {
    json.requireOnlyKeys("protocol", "host", "port")
    json.requireToken("protocol", protocol)
    val host = json.requireString("host")
    val octets = host.split('.')
    require(octets.size == 4 && octets.all {
      it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255
    }) { "canonical_numeric_ipv4_required" }
    // This pilot deliberately supports unicast IPv4 only; it performs no DNS/network I/O.
    require(octets[0].toInt() in 1..223 && octets[0] != "127") { "unicast_ipv4_required" }
    return SpatialPeerNetworkEndpoint(host, json.requireLong("port", 1, 65535).toInt())
  }
}
