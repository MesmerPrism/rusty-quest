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

/** Typed endpoint data for the frozen input candidate; this parser opens no resources. */
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
    val profile = SpatialCameraControlProfileContract.parse(root.toString().toByteArray(Charsets.UTF_8))
    val selected = parseEndpoints(endpoints)
    require(selected.all { it.peerId in peers }) { "peer_not_enrolled" }
    require(selected.map { it.peerId }.toSet() == peers) { "enrolled_peer_coverage" }
    require(selected[0].media != selected[1].media && selected[0].control != selected[1].control) {
      "peer_endpoint_collision"
    }
    val accepted = SpatialPeerNetworkProfileSelection(
        profile.copy(peerNetworkEndpoints = selected),
        MessageDigest.getInstance("SHA-256").digest(snapshot).joinToString("") { "%02x".format(it) },
        Collections.unmodifiableList(selected),
    )
    // One publication after complete validation; failure leaves the previous object intact.
    current = accepted
    return accepted
  }

  companion object {
    internal fun parseEndpoints(endpoints: JSONArray): List<SpatialPeerNetworkSelection> {
      require(endpoints.length() == 2) { "two_peer_endpoints_required" }
      val selected = (0 until endpoints.length()).map { index ->
        val peer = endpoints.getJSONObject(index)
        peer.requireOnlyKeys("peer_id", "media_endpoint", "control_endpoint")
        val id = peer.requireString("peer_id")
        require(id.isNotBlank() && id.length <= 128) { "peer_identifier_invalid" }
        SpatialPeerNetworkSelection(id,
            endpoint(peer.requireObject("media_endpoint"), "rmanvid-v4-packed-stereo"),
            endpoint(peer.requireObject("control_endpoint"), "rqod1"))
      }
      require(selected.map { it.peerId }.toSet().size == 2) { "peer_identifier_duplicate" }
      require(selected.flatMap { listOf(it.media, it.control) }.toSet().size == 4) { "peer_endpoint_collision" }
      return Collections.unmodifiableList(selected)
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
    require(json.get("port") is Int || json.get("port") is Long) { "endpoint_port_integer_required" }
    return SpatialPeerNetworkEndpoint(host, json.requireLong("port", 1, 65535).toInt())
  }
  }
}
