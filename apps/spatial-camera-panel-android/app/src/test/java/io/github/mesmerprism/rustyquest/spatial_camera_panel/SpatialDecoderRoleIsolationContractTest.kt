package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-role contract checks: Peer lifecycle failures must remain incapable of mutating CompositorVideo. */
class SpatialDecoderRoleIsolationContractTest {
  @Test fun samePtsRemainScopedToTheirOwnRoleAndGenerationQueues() {
    // Pending metadata is decoder-instance local. Reusing a PTS is therefore valid across
    // independent CompositorVideo/ProjectionPeer generations, but not within either queue.
    val compositorGenerationOne = SpatialPackedStereoBrokerPlayback.PendingPairIdentities()
    val peerGenerationSeven = SpatialPackedStereoBrokerPlayback.PendingPairIdentities()
    val presentationTimeUs = 42_000L

    compositorGenerationOne.register(presentationTimeUs, pair(101L))
    peerGenerationSeven.register(presentationTimeUs, pair(701L))

    assertEquals(101L, compositorGenerationOne.takeExact(presentationTimeUs).pairId)
    assertEquals(701L, peerGenerationSeven.takeExact(presentationTimeUs).pairId)
  }

  @Test fun preBindPeerFirstFrameCannotBecomeActiveUntilBothBindingsAcceptIt() {
    val events = ArrayList<String>()
    lateinit var coordinator: SpatialPeerProjectionRuntimeCoordinator
    val bindings = PeerBindings(events).bindings(
        firstFrameBeforeStartReturns = true,
        bindAccepted = true,
        attachAccepted = true,
        onBind = { events += "bind-state:${coordinator.snapshot().state}" },
        onAttach = { events += "attach-state:${coordinator.snapshot().state}" },
    )
    coordinator = SpatialPeerProjectionRuntimeCoordinator(bindings)

    assertTrue(coordinator.stage(peerSettings(), 7L))
    coordinator.updateSourceDemand(true, "peer-demand")
    assertTrue(coordinator.startAfterNativeAck(7L, "native-ack"))

    assertEquals(
        listOf(
            "start:7",
            "bind:7:11:21",
            "bind-state:Starting",
            "attach:7:11:21",
            "attach-state:Starting",
        ),
        events,
    )
    assertEquals(SpatialPeerProjectionRuntimeState.Active, coordinator.snapshot().state)
  }

  @Test fun stalePeerStopCannotStopNewPeerOrMutateActiveCompositor() {
    val compositorCalls = ArrayList<String>()
    val compositor = activeCompositor(compositorCalls)
    val peerEvents = ArrayList<String>()
    val peerBindings = PeerBindings(peerEvents)
    val peer = SpatialPeerProjectionRuntimeCoordinator(peerBindings.bindings())
    assertTrue(peer.stage(peerSettings(), 7L))
    peer.updateSourceDemand(true, "first")
    assertTrue(peer.startAfterNativeAck(7L, "first-ack"))
    val stale = peer.snapshot().identity!!
    assertTrue(peer.stopExact(stale, "handoff"))
    peerBindings.identity = SpatialPeerProjectionDecoderIdentity(8L, 12L, 22L)
    assertTrue(peer.stage(peerSettings().copy(peerSessionId = "new-peer"), 8L))
    peer.updateSourceDemand(true, "second")
    assertTrue(peer.startAfterNativeAck(8L, "second-ack"))
    peerEvents.clear()
    compositorCalls.clear()

    assertFalse(peer.stopExact(stale, "stale-stop"))

    assertTrue(peerEvents.isEmpty())
    assertEquals(8L, peer.snapshot().identity!!.routeGeneration)
    assertTrue(compositor.decoderActive)
    assertTrue(compositor.started)
    assertTrue(compositorCalls.isEmpty())
  }

  @Test fun failedExactPeerStopRetainsItsCompleteIdentityAndCompositor() {
    val compositorCalls = ArrayList<String>()
    val compositor = activeCompositor(compositorCalls)
    val peerEvents = ArrayList<String>()
    val peerBindings = PeerBindings(peerEvents).apply { stopAccepted = false }
    val peer = SpatialPeerProjectionRuntimeCoordinator(peerBindings.bindings())
    assertTrue(peer.stage(peerSettings(), 7L))
    peer.updateSourceDemand(true, "peer-demand")
    assertTrue(peer.startAfterNativeAck(7L, "native-ack"))
    val identity = peer.snapshot().identity!!
    compositorCalls.clear()

    assertFalse(peer.stopExact(identity, "exact-stop"))

    assertSame(identity, peer.snapshot().identity)
    assertEquals(SpatialPeerProjectionRuntimeState.Starting, peer.snapshot().state)
    assertEquals(
        listOf("start:7", "bind:7:11:21", "attach:7:11:21", "stop:7:11:21"),
        peerEvents,
    )
    assertTrue(compositor.decoderActive)
    assertTrue(compositor.started)
    assertTrue(compositorCalls.isEmpty())
  }

  @Test fun rejectedPeerAttachIsolatedFromColdAndHotCompositorState() {
    listOf(false, true).forEach { compositorWasHot ->
      val compositorCalls = ArrayList<String>()
      val compositor = compositor(compositorCalls)
      val file = compositorSettings(if (compositorWasHot) "hot" else "cold")
      compositor.adoptSettings(file)
      if (compositorWasHot) compositor.start(file, "baseline")
      val beforeSettings = compositor.settings
      val beforeActive = compositor.decoderActive
      val peerEvents = ArrayList<String>()
      val peer = SpatialPeerProjectionRuntimeCoordinator(
          PeerBindings(peerEvents).bindings(attachAccepted = false))
      assertTrue(peer.stage(peerSettings(), 7L))
      peer.updateSourceDemand(true, "peer-demand")

      assertFalse(peer.startAfterNativeAck(7L, "unsupported-attach"))

      assertEquals(beforeSettings, compositor.settings)
      assertEquals(beforeActive, compositor.decoderActive)
      assertEquals(if (compositorWasHot) true else false, compositor.started)
      assertEquals(if (compositorWasHot) listOf("start:$file") else emptyList(), compositorCalls)
      assertEquals(listOf("start:7", "bind:7:11:21", "attach:7:11:21", "stop:7:11:21"), peerEvents)
    }
  }

  @Test fun hiddenAndZeroZoneCompositorPoliciesDoNotWithdrawPeerDemand() {
    val peerEvents = ArrayList<String>()
    val peer = SpatialPeerProjectionRuntimeCoordinator(PeerBindings(peerEvents).bindings())
    assertTrue(peer.stage(peerSettings(), 7L))
    peer.updateSourceDemand(true, "peer-demand")
    assertTrue(peer.startAfterNativeAck(7L, "native-ack"))
    val compositorCalls = ArrayList<String>()
    val compositor = compositor(compositorCalls)
    val file = compositorSettings("visibility")
    compositor.adoptSettings(file)
    compositor.updateReadableVideoConsumer(false, "zero-readable-zones")

    val hidden = compositor.startForComposedOwnership(file, false, "hidden")

    assertEquals(SpatialVideoProjectionStartupDisposition.ProjectionHiddenDirect, hidden.disposition)
    assertTrue(hidden.directVideoConsumerRequired)
    assertTrue(peer.snapshot().sourceDemand)
    assertEquals(SpatialPeerProjectionRuntimeState.Starting, peer.snapshot().state)
    assertFalse(peerEvents.any { it.startsWith("stop:") })
    assertTrue(compositorCalls.isEmpty())
  }

  private fun activeCompositor(calls: MutableList<String>): SpatialVideoProjectionRuntimeCoordinator =
      compositor(calls).also { coordinator ->
        val file = compositorSettings("active")
        coordinator.adoptSettings(file)
        coordinator.start(file, "baseline")
      }

  private fun compositor(calls: MutableList<String>) = SpatialVideoProjectionRuntimeCoordinator(
      SpatialVideoProjectionRuntimeBindings(
          nativeState = { SpatialVideoProjectionRuntimeNativeState(receiptLibraryLoaded = true) },
          configureNative = { 1L },
          startPlayback = { settings, _, callbacks ->
            calls += "start:$settings"
            callbacks.onFirstFrame()
            true
          },
          stopPlayback = { calls += "stop"; true },
          stopNativeProbe = {},
          marker = {},
      )
  )

  private fun compositorSettings(pathSuffix: String) = SpatialVideoProjectionSettings.disabled().copy(
      enabled = true,
      source = SpatialImmersiveVideoSessionPolicy.PLAIN_CUSTOM_PROJECTION_SOURCE,
      path = "content://compositor/$pathSuffix",
      width = 2048,
      height = 2048,
      stereoLayout = "top-bottom-left-right",
      mediaLayout = "top-bottom-left-right",
  )

  private fun peerSettings() = SpatialVideoProjectionSettings.disabled().copy(
      enabled = true,
      source = "peer-packed-stereo",
      brokerPort = 9079,
      peerSessionId = "peer-session",
      mediaLayout = "side-by-side-left-right",
  )

  private fun pair(pairId: Long) = SpatialPackedStereoBrokerPlayback.PairRecord(
      pairId, pairId * 2, pairId * 2 + 1, 10_000 + pairId, 10_002 + pairId, 2L)

  private class PeerBindings(private val events: MutableList<String>) {
    var identity = SpatialPeerProjectionDecoderIdentity(7L, 11L, 21L)
    var stopAccepted = true

    fun bindings(
        firstFrameBeforeStartReturns: Boolean = false,
        bindAccepted: Boolean = true,
        attachAccepted: Boolean = true,
        onBind: () -> Unit = {},
        onAttach: () -> Unit = {},
    ) = SpatialPeerProjectionRuntimeBindings(
        startDecoder = { _, route, callbacks ->
          events += "start:$route"
          if (firstFrameBeforeStartReturns) callbacks.onFirstFrame()
          identity
        },
        stopDecoder = { value ->
          events += "stop:${value.routeGeneration}:${value.decoderToken}:${value.readerGeneration}"
          stopAccepted
        },
        bindNativeRole = { value ->
          events += "bind:${value.routeGeneration}:${value.decoderToken}:${value.readerGeneration}"
          onBind()
          bindAccepted
        },
        attachCommonGraph = { value ->
          events += "attach:${value.routeGeneration}:${value.decoderToken}:${value.readerGeneration}"
          onAttach()
          attachAccepted
        },
        marker = {},
    )
  }
}
