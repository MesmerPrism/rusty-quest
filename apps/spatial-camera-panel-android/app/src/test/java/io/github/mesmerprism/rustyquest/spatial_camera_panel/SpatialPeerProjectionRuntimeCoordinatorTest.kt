package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SpatialPeerProjectionRuntimeCoordinatorTest {
  @Test fun stagingAndNativeAckPrecedePeerAcquisition() {
    val fake = FakePeerBindings()
    val coordinator = SpatialPeerProjectionRuntimeCoordinator(fake.bindings())

    assertEquals(SpatialVideoDecoderRole.ProjectionPeer, coordinator.role)
    assertTrue(coordinator.stage(peerSettings(), 7L))
    assertTrue(fake.calls.isEmpty())
    coordinator.updateSourceDemand(true, "admitted")
    assertTrue(fake.calls.isEmpty())
    assertTrue(coordinator.startAfterNativeAck(7L, "ack"))

    assertEquals(listOf("start:7", "bind:7:11:21", "attach:7:11:21"), fake.calls)
  }

  @Test fun firstFrameBeforeIdentityReturnBecomesActiveOnlyAfterExactAttach() {
    val fake = FakePeerBindings().apply { firstFrameBeforeReturn = true }
    val coordinator = SpatialPeerProjectionRuntimeCoordinator(fake.bindings())
    coordinator.stage(peerSettings(), 7L)
    coordinator.updateSourceDemand(true, "admitted")

    assertTrue(coordinator.startAfterNativeAck(7L, "ack"))

    assertEquals(SpatialPeerProjectionRuntimeState.Active, coordinator.snapshot().state)
    assertEquals(listOf("start:7", "bind:7:11:21", "attach:7:11:21"), fake.calls)
  }

  @Test fun rejectedCommonGraphStopsOnlyExactPeerLane() {
    val fake = FakePeerBindings().apply { attachAccepted = false }
    val coordinator = SpatialPeerProjectionRuntimeCoordinator(fake.bindings())
    coordinator.stage(peerSettings(), 7L)
    coordinator.updateSourceDemand(true, "admitted")

    assertFalse(coordinator.startAfterNativeAck(7L, "ack"))

    assertEquals(listOf("start:7", "bind:7:11:21", "attach:7:11:21", "stop:7:11:21"), fake.calls)
    assertEquals(SpatialPeerProjectionRuntimeState.Rejected, coordinator.snapshot().state)
    assertNull(coordinator.snapshot().identity)
  }

  @Test fun rejectedCommonGraphRetainsPeerIdentityWhenExactStopFails() {
    val fake = FakePeerBindings().apply {
      attachAccepted = false
      stopAccepted = false
    }
    val coordinator = SpatialPeerProjectionRuntimeCoordinator(fake.bindings())
    coordinator.stage(peerSettings(), 7L)
    coordinator.updateSourceDemand(true, "admitted")

    assertFalse(coordinator.startAfterNativeAck(7L, "ack"))

    assertEquals(listOf("start:7", "bind:7:11:21", "attach:7:11:21", "stop:7:11:21"), fake.calls)
    assertEquals(SpatialPeerProjectionRuntimeState.Rejected, coordinator.snapshot().state)
    assertNotNull(coordinator.snapshot().identity)
  }

  @Test fun staleStopCannotAffectNewerPeerLane() {
    val fake = FakePeerBindings()
    val coordinator = SpatialPeerProjectionRuntimeCoordinator(fake.bindings())
    coordinator.stage(peerSettings(), 7L)
    coordinator.updateSourceDemand(true, "admitted")
    assertTrue(coordinator.startAfterNativeAck(7L, "ack"))
    val old = coordinator.snapshot().identity!!
    assertTrue(coordinator.stopExact(old, "handoff"))
    assertTrue(coordinator.stage(peerSettings().copy(peerSessionId = "new"), 8L))
    coordinator.updateSourceDemand(true, "new")
    fake.nextIdentity = SpatialPeerProjectionDecoderIdentity(8L, 12L, 22L)
    assertTrue(coordinator.startAfterNativeAck(8L, "new-ack"))
    fake.calls.clear()

    assertFalse(coordinator.stopExact(old, "stale"))

    assertTrue(fake.calls.isEmpty())
    assertEquals(8L, coordinator.snapshot().identity?.routeGeneration)
  }

  @Test fun destroyWhilePeerIsStartingFencesAndRetiresTheReturnedIdentity() {
    val fake = FakePeerBindings().apply {
      startEntered = CountDownLatch(1)
      startRelease = CountDownLatch(1)
    }
    val coordinator = SpatialPeerProjectionRuntimeCoordinator(fake.bindings())
    coordinator.stage(peerSettings(), 7L)
    coordinator.updateSourceDemand(true, "admitted")
    val worker = Executors.newSingleThreadExecutor()
    val starting = worker.submit<Boolean> { coordinator.startAfterNativeAck(7L, "ack") }
    assertTrue(fake.startEntered!!.await(1, TimeUnit.SECONDS))

    assertTrue(coordinator.retireForActivityDestroy("destroy"))
    fake.startRelease!!.countDown()

    assertFalse(starting.get(1, TimeUnit.SECONDS))
    assertTrue(fake.calls.any { it == "stop:7:11:21" })
    assertNull(coordinator.snapshot().identity)
    worker.shutdownNow()
  }

  @Test fun errorBeforeStartReturnsRetainsIdentityWhenExactCleanupFails() {
    val fake = FakePeerBindings().apply {
      errorBeforeReturn = true
      stopAccepted = false
    }
    val coordinator = SpatialPeerProjectionRuntimeCoordinator(fake.bindings())
    coordinator.stage(peerSettings(), 7L)
    coordinator.updateSourceDemand(true, "admitted")

    assertFalse(coordinator.startAfterNativeAck(7L, "ack"))

    val retained = coordinator.snapshot().identity
    assertNotNull(retained)
    assertEquals(SpatialPeerProjectionRuntimeState.Rejected, coordinator.snapshot().state)
    assertFalse(coordinator.stage(peerSettings().copy(peerSessionId = "new"), 8L))
    fake.stopAccepted = true
    assertTrue(coordinator.stopExact(retained!!, "retry-cleanup"))
    assertTrue(coordinator.stage(peerSettings().copy(peerSessionId = "new"), 8L))
  }

  @Test fun destroyBeforeStartReturnsRetainsIdentityWhenExactCleanupFails() {
    val fake = FakePeerBindings().apply {
      startEntered = CountDownLatch(1)
      startRelease = CountDownLatch(1)
      stopAccepted = false
    }
    val coordinator = SpatialPeerProjectionRuntimeCoordinator(fake.bindings())
    coordinator.stage(peerSettings(), 7L)
    coordinator.updateSourceDemand(true, "admitted")
    val worker = Executors.newSingleThreadExecutor()
    val starting = worker.submit<Boolean> { coordinator.startAfterNativeAck(7L, "ack") }
    assertTrue(fake.startEntered!!.await(1, TimeUnit.SECONDS))

    assertTrue(coordinator.retireForActivityDestroy("destroy"))
    fake.startRelease!!.countDown()

    assertFalse(starting.get(1, TimeUnit.SECONDS))
    val retained = coordinator.snapshot().identity
    assertNotNull(retained)
    assertEquals(SpatialPeerProjectionRuntimeState.Rejected, coordinator.snapshot().state)
    assertFalse(coordinator.stage(peerSettings().copy(peerSessionId = "new"), 8L))
    worker.shutdownNow()
  }

  @Test fun destroyStopsTheExactAttachedPeer() {
    val fake = FakePeerBindings()
    val coordinator = SpatialPeerProjectionRuntimeCoordinator(fake.bindings())
    coordinator.stage(peerSettings(), 7L)
    coordinator.updateSourceDemand(true, "admitted")
    assertTrue(coordinator.startAfterNativeAck(7L, "ack"))

    assertTrue(coordinator.retireForActivityDestroy("destroy"))

    assertNull(coordinator.snapshot().identity)
    assertTrue(fake.calls.any { it == "stop:7:11:21" })
  }

  @Test fun destroyRetainsActionablePeerIdentityWhenStopIsRejected() {
    val fake = FakePeerBindings().apply { stopAccepted = false }
    val coordinator = SpatialPeerProjectionRuntimeCoordinator(fake.bindings())
    coordinator.stage(peerSettings(), 7L)
    coordinator.updateSourceDemand(true, "admitted")
    assertTrue(coordinator.startAfterNativeAck(7L, "ack"))

    assertFalse(coordinator.retireForActivityDestroy("destroy"))

    assertEquals(7L, coordinator.snapshot().identity?.routeGeneration)
  }

  @Test fun hiddenAndZeroZonePoliciesNeverWithdrawProjectionPeerDemand() {
    val fake = FakePeerBindings()
    val coordinator = SpatialPeerProjectionRuntimeCoordinator(fake.bindings())
    coordinator.stage(peerSettings(), 7L)
    coordinator.updateSourceDemand(true, "source-owner")
    assertTrue(coordinator.startAfterNativeAck(7L, "ack"))

    // No readable-zone or panel-visibility API exists on this fixed lane.
    assertTrue(coordinator.snapshot().sourceDemand)
    assertFalse(fake.calls.any { it.startsWith("stop:") })
  }

  @Test fun unsupportedPeerAttachLeavesActiveCompositorVideoByteForByteUnchanged() {
    val compositorCalls = ArrayList<String>()
    val compositor = SpatialVideoProjectionRuntimeCoordinator(
        SpatialVideoProjectionRuntimeBindings(
            nativeState = { SpatialVideoProjectionRuntimeNativeState(false) },
            configureNative = { 0L },
            startPlayback = { settings, _, callbacks ->
              compositorCalls += "start:${settings.path}"
              callbacks.onFirstFrame()
              true
            },
            stopPlayback = {
              compositorCalls += "stop"
              true
            },
            stopNativeProbe = {},
            marker = {},
        )
    )
    val file = SpatialVideoProjectionSettings.disabled().copy(
        enabled = true,
        source = "shared-plain-video",
        path = "content://baseline/file",
    )
    compositor.adoptSettings(file)
    assertEquals(SpatialVideoDecoderRole.CompositorVideo, compositor.role)
    compositor.start(file, "baseline")
    val before = compositor.settings
    val peerFake = FakePeerBindings().apply { attachAccepted = false }
    val peer = SpatialPeerProjectionRuntimeCoordinator(peerFake.bindings())
    peer.stage(peerSettings(), 7L)
    peer.updateSourceDemand(true, "peer")

    assertFalse(peer.startAfterNativeAck(7L, "unsupported"))

    assertEquals(before, compositor.settings)
    assertTrue(compositor.decoderActive)
    assertEquals(listOf("start:content://baseline/file"), compositorCalls)
  }

  @Test fun commonGraphRequiresPositiveJniAndExactBoundAcknowledgement() {
    val identity = SpatialPeerProjectionDecoderIdentity(7L, 11L, 21L)
    val carrier = SpatialVideoSourceCarrierContext(31L, 41L)
    val exact = SpatialVideoSourceNativeReadback(
        7L, SpatialVideoSource.Peer, 11L, 21L, 31L, 41L, 0L, 0L, 0L,
        SpatialVideoSourceStage.ProviderSelected or SpatialVideoSourceStage.DecoderBound or
            SpatialVideoSourceStage.ReaderBound or SpatialVideoSourceStage.SurfaceBound or
            SpatialVideoSourceStage.CommonGraphAttached,
        SpatialVideoSourceResult.Pending, SpatialVideoSourceReason.None, false, 0L, 0L,
    )

    assertFalse(SpatialPeerCommonGraphAcknowledgementPolicy.accepts(0L, exact, identity, carrier))
    assertFalse(
        SpatialPeerCommonGraphAcknowledgementPolicy.accepts(
            1L,
            exact.copy(stages = exact.stages and SpatialVideoSourceStage.CommonGraphAttached.inv()),
            identity,
            carrier,
        )
    )
    assertFalse(
        SpatialPeerCommonGraphAcknowledgementPolicy.accepts(
            1L,
            exact.copy(surfaceGeneration = 42L),
            identity,
            carrier,
        )
    )
    assertTrue(SpatialPeerCommonGraphAcknowledgementPolicy.accepts(1L, exact, identity, carrier))
  }

  private fun peerSettings() = SpatialVideoProjectionSettings.disabled().copy(
      enabled = true,
      source = "peer-packed-stereo",
      brokerPort = 9079,
      peerSessionId = "peer-session",
      mediaLayout = "side-by-side-left-right",
  )

  private class FakePeerBindings {
    val calls = ArrayList<String>()
    var attachAccepted = true
    var stopAccepted = true
    var firstFrameBeforeReturn = false
    var errorBeforeReturn = false
    var startEntered: CountDownLatch? = null
    var startRelease: CountDownLatch? = null
    var nextIdentity = SpatialPeerProjectionDecoderIdentity(7L, 11L, 21L)

    fun bindings() = SpatialPeerProjectionRuntimeBindings(
        startDecoder = { _, route, callbacks ->
          calls += "start:$route"
          startEntered?.countDown()
          startRelease?.await(1, TimeUnit.SECONDS)
          if (errorBeforeReturn) callbacks.onError("before-return")
          if (firstFrameBeforeReturn) callbacks.onFirstFrame()
          nextIdentity
        },
        stopDecoder = { identity ->
          calls += "stop:${identity.routeGeneration}:${identity.decoderToken}:${identity.readerGeneration}"
          stopAccepted
        },
        bindNativeRole = { identity ->
          calls += "bind:${identity.routeGeneration}:${identity.decoderToken}:${identity.readerGeneration}"
          true
        },
        attachCommonGraph = { identity ->
          calls += "attach:${identity.routeGeneration}:${identity.decoderToken}:${identity.readerGeneration}"
          attachAccepted
        },
        marker = {},
    )
  }
}
