package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialPeerStereoTransportTest {
  @Test
  fun routeAliasesNormalizeIntoThreeTransportNeutralAdapters() {
    assertEquals(
        SpatialPeerStereoTransport.DIRECT_TCP_CONNECT,
        SpatialPeerStereoTransport.normalizeRouteKind("lan-tcp"),
    )
    assertEquals(
        SpatialPeerStereoTransport.DIRECT_P2P_TCP,
        SpatialPeerStereoTransport.normalizeRouteKind("wifi-direct"),
    )
    assertEquals(
        SpatialPeerStereoTransport.RELAY_TLS_CLIENT,
        SpatialPeerStereoTransport.normalizeRouteKind("authenticated-tls-relay"),
    )
  }

  @Test
  fun relayAuthenticationIsBinaryBoundedAndNeverAppearsInSafeStatus() {
    val secret = "private-test-bearer"
    val bytes = ByteArrayOutputStream()
    SpatialPeerStereoTransport.writeRelayAuthentication(
        DataOutputStream(bytes),
        2,
        "accepted-session",
        "stereo-a-to-b",
        secret,
    )
    assertTrue(bytes.toByteArray().copyOfRange(0, 8).toString(Charsets.US_ASCII) == "RQPRLY1\n")

    val marker =
        SpatialPeerStereoTransport.safeRouteMarker(
            SpatialPeerStereoTransport.RELAY_TLS_CLIENT,
            "accepted-session",
            true,
        )
    assertTrue(marker.contains("peerTransportEncrypted=true"))
    assertTrue(marker.contains("peerSessionAccepted=true"))
    assertTrue(marker.contains("peerEndpointRedacted=true"))
    assertFalse(marker.contains(secret))
    assertFalse(marker.contains("accepted-session"))
  }

  @Test
  fun statusRequiresAdvancingPairsTimestampsAndDecoderOutputIndependently() {
    SpatialPeerStereoStatus.starting("direct_p2p_tcp", "accepted", false)
    SpatialPeerStereoStatus.connected()
    SpatialPeerStereoStatus.packet(100, 1, 1_000, 1_100, 100, false)
    assertFalse(SpatialPeerStereoStatus.snapshot().timestampsAdvancing)
    assertFalse(SpatialPeerStereoStatus.snapshot().pairSequenceAdvancing)
    SpatialPeerStereoStatus.packet(120, 2, 2_000, 2_050, 50, false)
    SpatialPeerStereoStatus.rendered(2)

    val status = SpatialPeerStereoStatus.snapshot()
    assertEquals(2, status.packets)
    assertEquals(220, status.bytes)
    assertEquals(2, status.lastPairId)
    assertTrue(status.timestampsAdvancing)
    assertTrue(status.pairSequenceAdvancing)
    assertTrue(status.decoderOutputObserved)
    assertTrue(status.marker().contains("peerSecretSerialized=false"))
  }
}
