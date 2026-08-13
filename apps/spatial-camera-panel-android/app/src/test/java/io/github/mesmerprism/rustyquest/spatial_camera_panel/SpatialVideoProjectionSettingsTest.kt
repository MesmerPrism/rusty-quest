package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialVideoProjectionSettingsTest {
  @Test
  fun cadenceModesHaveIndependentSurfaceGateAndNativeFallbackSemantics() {
    assertEquals(30, SpatialVideoCadenceMode.Fps30.surfaceGateFps)
    assertEquals(30, SpatialVideoCadenceMode.Fps30.nativeFallbackFps)
    assertEquals(60, SpatialVideoCadenceMode.Fps60.surfaceGateFps)
    assertEquals(60, SpatialVideoCadenceMode.Fps60.nativeFallbackFps)
    assertEquals(0, SpatialVideoCadenceMode.Source.surfaceGateFps)
    assertEquals(90, SpatialVideoCadenceMode.Source.nativeFallbackFps)
    assertFalse(SpatialVideoCadenceMode.Source.surfaceGateEnabled)
    assertTrue(SpatialVideoCadenceMode.Fps30.surfaceGateEnabled)
  }

  @Test
  fun defaultRemainsThirtyAndLegacyCapsMapWithoutChangingExistingLaunches() {
    val defaults = SpatialVideoProjectionSettings.disabled()
    assertEquals(SpatialVideoCadenceMode.Fps30, defaults.cadenceMode)
    assertEquals(30, defaults.fpsCap)
    assertEquals(30, defaults.surfaceOutputCadenceFps)
    assertEquals(SpatialVideoCadenceMode.Fps30, SpatialVideoCadenceMode.fromLegacyFpsCap(24))
    assertEquals(SpatialVideoCadenceMode.Fps60, SpatialVideoCadenceMode.fromLegacyFpsCap(45))
    assertEquals(SpatialVideoCadenceMode.Source, SpatialVideoCadenceMode.fromLegacyFpsCap(90))
  }

  @Test
  fun markerSeparatesRequestedModeSurfaceGateAndRetainedFallback() {
    val source =
        SpatialVideoProjectionSettings.disabled().copy(
            fpsCap = 90,
            cadenceMode = SpatialVideoCadenceMode.Source,
        )
    val marker = SpatialVideoProjectionRouteModule.markerFields(source)
    assertTrue(marker.contains("videoProjectionCadenceRequested=source"))
    assertTrue(marker.contains("videoProjectionSurfaceCadenceGateEnabled=false"))
    assertTrue(marker.contains("videoProjectionSurfaceCadenceFps=0"))
    assertTrue(marker.contains("videoProjectionNativeCadenceFallbackFps=90"))
  }

  @Test
  fun peerStereoIsFirstClassAndTlsActivationFailsClosedWithoutPrivateInputs() {
    val lan =
        SpatialVideoProjectionSettings.disabled().copy(
            enabled = true,
            source = "peer-packed-stereo",
            brokerPort = 9079,
            peerRouteKind = SpatialPeerStereoRouteKind.InfrastructureLan,
            peerSessionId = "accepted-session",
        )
    assertTrue(lan.active)
    assertTrue(SpatialVideoProjectionRouteModule.markerFields(lan).contains("peerEndpointRedacted=true"))
    assertFalse(lan.copy(peerSessionId = "").active)

    val missingSecret = lan.copy(peerRouteKind = SpatialPeerStereoRouteKind.AuthenticatedTlsRelay)
    assertFalse(missingSecret.active)
    val relay =
        missingSecret.copy(
            peerRelayChannel = "stereo-a-to-b",
            peerTlsServerName = "relay.invalid",
            peerAuthToken = "private-runtime-only",
        )
    assertTrue(relay.active)
    val marker = SpatialVideoProjectionRouteModule.markerFields(relay)
    assertTrue(marker.contains("videoProjectionPeerTransportEncrypted=true"))
    assertFalse(marker.contains("private-runtime-only"))
    assertFalse(marker.contains("relay.invalid"))
    assertFalse(marker.contains("accepted-session"))
  }
}
