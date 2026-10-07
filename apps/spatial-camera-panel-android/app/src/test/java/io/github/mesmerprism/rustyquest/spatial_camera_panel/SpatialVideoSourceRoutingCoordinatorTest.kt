package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SpatialVideoSourceRoutingCoordinatorTest {
  private val carrier = SpatialVideoSourceCarrierContext(launchChallenge = 31L, surfaceGeneration = 7L)

  @Test fun defaultIsLocalAndUnrelatedIntentDoesNotSelectASource() {
    val fake = FakeExecution()
    val coordinator = coordinator(fake)

    assertEquals(SpatialVideoSource.Local, coordinator.snapshot().requested)
    assertTrue(coordinator.snapshot().sourceOwnerDemand)
    assertEquals(SpatialVideoSourceIntentResult.Unspecified, SpatialVideoSourceIntentParser.parseToken(false, null))
    assertEquals(0, fake.calls.size)
  }

  @Test fun explicitIntentIsClosedAndRejectsTransportDerivedValues() {
    assertEquals(
        SpatialVideoSourceIntentResult.Selected(SpatialVideoSource.Peer),
        SpatialVideoSourceIntentParser.parseToken(true, "peer"),
    )
    assertTrue(
        SpatialVideoSourceIntentParser.parseToken(true, "transport-derived") is
            SpatialVideoSourceIntentResult.Rejected
    )
  }

  @Test fun peerSelectsNativeProviderBeforeAdoptingValidatedSettings() {
    val fake = FakeExecution()
    val coordinator = coordinator(fake)

    val state = coordinator.requestProjectionSource(SpatialVideoSource.Peer, peerSettings(), carrier, "ui")

    assertEquals(
        listOf(
            "demand:false", "peer-stage:1", "native:peer:1", "peer-ownership:1", "demand:true",
            "peer-settings:1",
        ),
        fake.calls,
    )
    assertEquals("peer-packed-stereo", fake.adoptedSettings?.source)
    assertEquals(SpatialVideoSource.Peer, state.pending)
    assertEquals(SpatialVideoSource.Disabled, state.effective)
  }

  @Test fun invalidPeerSettingsNeverSelectProviderOrAcquire() {
    val fake = FakeExecution()
    val state = coordinator(fake).requestProjectionSource(
        SpatialVideoSource.Peer,
        SpatialVideoProjectionSettings.disabled(),
        carrier,
        "ui",
    )

    assertEquals(SpatialVideoSourceReason.PeerSettingsInvalid, state.failureReason)
    assertEquals(listOf("demand:false"), fake.calls)
  }

  @Test fun disabledStopsAcquisitionWithoutRecreatingCarrierOrControls() {
    val fake = FakeExecution().apply { immediateResult = SpatialVideoSourceResult.Inactive }
    val coordinator = coordinator(fake)
    val controlsIdentity = Any()
    val carrierIdentity = Any()

    val state = coordinator.requestProjectionSource(SpatialVideoSource.Disabled, null, carrier, "ui")

    assertEquals(SpatialVideoSource.Disabled, state.effective)
    assertFalse(state.sourceOwnerDemand)
    assertEquals(listOf("demand:false", "native:disabled:1"), fake.calls)
    assertEquals(controlsIdentity, controlsIdentity)
    assertEquals(carrierIdentity, carrierIdentity)
  }

  @Test fun asynchronousDisabledStopMayRemainPendingUntilStoppedReceipt() {
    var now = 1_000L
    val fake = FakeExecution().apply {
      immediateResult = SpatialVideoSourceResult.Pending
      disabledPendingStages = 0L
    }
    val coordinator = SpatialVideoSourceRoutingCoordinator(fake) { now }

    val pending = coordinator.requestProjectionSource(
        SpatialVideoSource.Disabled, null, carrier, "disabled")
    assertEquals(SpatialVideoSource.Disabled, pending.pending)
    assertEquals(SpatialVideoSourceReason.None, pending.failureReason)
    fake.readback = fake.readbackFor(
        SpatialVideoSource.Disabled,
        pending.generation,
        SpatialVideoSourceResult.Inactive,
        SpatialVideoSourceStage.AcquisitionStopped,
    )

    val stopped = coordinator.pollActive(maxAgeNs = 2_000L)

    assertEquals(SpatialVideoSource.Disabled, stopped.effective)
    assertNull(stopped.pending)
  }

  @Test fun embeddedPeerRequiresStoppedLocalReceiptAndDoesNotStartLegacyDecoder() {
    val fake = FakeExecution().apply { immediateResult = SpatialVideoSourceResult.Inactive }
    val coordinator = coordinator(fake)
    coordinator.requestProjectionSource(SpatialVideoSource.Disabled, null, carrier, "handoff")
    fake.calls.clear()
    fake.immediateResult = SpatialVideoSourceResult.Pending

    val staged = coordinator.beginEmbeddedProjectionPeerRequest()
    val pending = coordinator.executeRequest(staged.generation, carrier, "embedded-sink")
    coordinator.resumePending(carrier, "duplicate-carrier-observation")

    assertEquals(listOf("demand:false", "native:peer:2"), fake.calls)
    assertEquals(SpatialVideoSource.Peer, pending.pending)
    assertEquals(SpatialVideoSource.Disabled, pending.effective)
    assertNull(fake.adoptedSettings)
    val active = coordinator.reportNativeReadback(fake.readbackFor(
        SpatialVideoSource.Peer, staged.generation, SpatialVideoSourceResult.Effective,
        SpatialVideoSourceStage.requiredFor(SpatialVideoSource.Peer),
    ).copy(decoderToken = 21L, readerGeneration = 22L, pairGeneration = 23L, importGeneration = 24L))
    assertEquals(SpatialVideoSource.Peer, active.effective)
    assertNull(active.pending)
  }

  @Test(expected = IllegalStateException::class)
  fun embeddedPeerCannotTreatPendingShutdownAsCameraRelease() {
    val fake = FakeExecution().apply {
      immediateResult = SpatialVideoSourceResult.Pending
      disabledPendingStages = 0L
    }
    val coordinator = coordinator(fake)
    coordinator.requestProjectionSource(SpatialVideoSource.Disabled, null, carrier, "handoff")
    coordinator.beginEmbeddedProjectionPeerRequest()
  }

  @Test fun asynchronousDisabledStopFailsClosedAfterBoundedDeadline() {
    var now = 1_000L
    val fake = FakeExecution().apply {
      immediateResult = SpatialVideoSourceResult.Pending
      disabledPendingStages = 0L
    }
    val coordinator = SpatialVideoSourceRoutingCoordinator(fake) { now }
    val pending = coordinator.requestProjectionSource(
        SpatialVideoSource.Disabled, null, carrier, "disabled")
    fake.readback = fake.readbackFor(
        SpatialVideoSource.Disabled,
        pending.generation,
        SpatialVideoSourceResult.Pending,
        0L,
    )
    now = 4_001L

    val failed = coordinator.pollActive(maxAgeNs = 2_000L)

    assertEquals(SpatialVideoSourceReason.ReceiptStale, failed.failureReason)
    assertNull(failed.pending)
  }

  @Test fun deniedLocalAdvancesGenerationAndFencesLatePeerRetirement() {
    val fake = FakeExecution()
    val coordinator = coordinator(fake)
    val peerRequest = coordinator.requestProjectionSource(SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
    val peerEffective = peerEffective(peerRequest.generation)
    coordinator.reportNativeReadback(peerEffective)
    fake.producer = SpatialProjectionProducerState.ProducerActive(81L, 4L)
    fake.cleanupResult = SpatialProjectionProducerState.CleanupPending(81L, 4L, peerRequest.generation + 1L)

    val denied = coordinator.requestProjectionSource(SpatialVideoSource.Local, null, carrier, "local")
    coordinator.reportNativeReadback(peerEffective)

    assertEquals(peerRequest.generation + 1L, denied.generation)
    assertEquals(SpatialVideoSource.Local, denied.failed)
    assertEquals(SpatialVideoSourceReason.ProducerCleanupRequired, denied.failureReason)
    assertEquals(SpatialVideoSource.Disabled, coordinator.snapshot().effective)
  }

  @Test fun localRequiresMatchingProducerIdentityBeforeNativeCameraSelection() {
    val fake = FakeExecution().apply {
      immediateResult = SpatialVideoSourceResult.Effective
      producer = SpatialProjectionProducerState.ProducerActive(91L, 6L)
      cleanupResult = SpatialProjectionProducerState.ProducerInactive(91L, 6L, 1L)
    }
    val state = coordinator(fake).requestProjectionSource(SpatialVideoSource.Local, null, carrier, "local")

    assertEquals(SpatialVideoSource.Local, state.effective)
    assertTrue(state.readback?.cameraStartRequested == true)
    assertEquals(
        listOf("demand:false", "cleanup:91:6:1", "native:local:1"),
        fake.calls,
    )
  }

  @Test fun foreignProducerInactiveReceiptCannotAuthorizeLocal() {
    val fake = FakeExecution().apply {
      producer = SpatialProjectionProducerState.ProducerActive(91L, 6L)
      cleanupResult = SpatialProjectionProducerState.ProducerInactive(92L, 6L, 1L)
    }
    val state = coordinator(fake).requestProjectionSource(SpatialVideoSource.Local, null, carrier, "local")

    assertEquals(SpatialVideoSourceReason.ProducerCleanupForeign, state.failureReason)
    assertFalse(fake.calls.any { it.startsWith("native:") })
  }

  @Test fun onlyMatchingRetiredPeerReceiptCanAdoptPeer() {
    val fake = FakeExecution()
    val coordinator = coordinator(fake)
    val requested = coordinator.requestProjectionSource(SpatialVideoSource.Peer, peerSettings(), carrier, "peer")

    coordinator.reportNativeReadback(peerEffective(requested.generation).copy(pairGeneration = 0L))
    assertEquals(SpatialVideoSource.Disabled, coordinator.snapshot().effective)
    assertEquals(SpatialVideoSourceReason.ReceiptMalformed, coordinator.snapshot().failureReason)

    val retried = coordinator.requestProjectionSource(SpatialVideoSource.Peer, peerSettings(), carrier, "retry")
    coordinator.reportNativeReadback(peerEffective(retried.generation).copy(surfaceGeneration = 99L))
    assertEquals(SpatialVideoSource.Peer, coordinator.snapshot().pending)
    coordinator.reportNativeReadback(peerEffective(retried.generation))
    assertEquals(SpatialVideoSource.Peer, coordinator.snapshot().effective)
    assertNull(coordinator.snapshot().pending)
  }

  @Test fun staleAndLostActivePeerReceiptsWithdrawEffectiveSource() {
    var now = 1_000L
    val fake = FakeExecution()
    val coordinator = SpatialVideoSourceRoutingCoordinator(fake) { now }
    val requested = coordinator.requestProjectionSource(SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
    val effective = peerEffective(requested.generation).copy(acquisitionTimeNs = 100L)
    coordinator.reportNativeReadback(effective)
    fake.readback = effective
    fake.calls.clear()

    val stale = coordinator.pollActive(maxAgeNs = 500L)
    assertEquals(SpatialVideoSource.Disabled, stale.effective)
    assertEquals(SpatialVideoSourceReason.ReceiptStale, stale.failureReason)
    assertTrue(fake.calls.contains("stop:peer:1"))

    val retried = coordinator.requestProjectionSource(SpatialVideoSource.Peer, peerSettings(), carrier, "retry")
    val secondEffective = peerEffective(retried.generation).copy(acquisitionTimeNs = now)
    coordinator.reportNativeReadback(secondEffective)
    coordinator.reportNativeReadback(
        secondEffective.copy(result = SpatialVideoSourceResult.Lost,
            reason = SpatialVideoSourceReason.ActiveSourceLost)
    )
    assertEquals(SpatialVideoSource.Disabled, coordinator.snapshot().effective)
    assertNull(coordinator.snapshot().pending)
  }

  @Test fun deferredPeerUsesSameRouteGenerationWhenCarrierArrives() {
    val fake = FakeExecution()
    val coordinator = coordinator(fake)
    val pending = coordinator.requestProjectionSource(SpatialVideoSource.Peer, peerSettings(), null, "initial")

    val resumed = coordinator.resumePending(carrier, "carrier-ready")

    assertEquals(pending.generation, resumed.generation)
    assertEquals(1, fake.calls.count { it.startsWith("native:peer:") })
  }

  @Test fun pendingPeerPollAdvancesOnlyOnMatchingEffectiveReadback() {
    val fake = FakeExecution()
    val coordinator = coordinator(fake)
    val pending = coordinator.requestProjectionSource(
        SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
    assertTrue(SpatialVideoSourcePollingPolicy.shouldPoll(pending, carrierPresent = true))
    fake.readback = peerEffective(pending.generation)

    val effective = coordinator.pollActive(maxAgeNs = 500L)

    assertEquals(SpatialVideoSource.Peer, effective.effective)
    assertNull(effective.pending)
  }

  @Test fun pendingWithoutFirstFrameTimestampIsNotDeclaredStale() {
    var now = 10_000_000_000L
    val fake = FakeExecution()
    val coordinator = SpatialVideoSourceRoutingCoordinator(fake) { now }
    val pending = coordinator.requestProjectionSource(
        SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
    fake.readback = SpatialVideoSourceNativeReadback(
        routeGeneration = pending.generation,
        source = SpatialVideoSource.Peer,
        decoderToken = 0L,
        readerGeneration = 0L,
        launchChallenge = 31L,
        surfaceGeneration = 7L,
        pairGeneration = 0L,
        importGeneration = 0L,
        acquisitionTimeNs = 0L,
        stages = SpatialVideoSourceStage.ProviderSelected,
        result = SpatialVideoSourceResult.Pending,
        reason = SpatialVideoSourceReason.None,
        cameraStartRequested = false,
        producerSession = 0L,
        producerEpoch = 0L,
    )

    val afterDelayedPoll = coordinator.pollActive(maxAgeNs = 500_000_000L)

    assertEquals(SpatialVideoSource.Peer, afterDelayedPoll.pending)
    assertEquals(SpatialVideoSource.Peer, afterDelayedPoll.ownedAcquisition)
    assertEquals(SpatialVideoSourceReason.None, afterDelayedPoll.failureReason)
  }

  @Test fun peerPendingStartupDeadlineRetiresOwnedAcquisition() {
    var now = 1_000L
    val fake = FakeExecution()
    val coordinator = SpatialVideoSourceRoutingCoordinator(fake) { now }
    val pending = coordinator.requestProjectionSource(
        SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
    fake.readback = fake.readbackFor(
        SpatialVideoSource.Peer,
        pending.generation,
        SpatialVideoSourceResult.Pending,
        SpatialVideoSourceStage.ProviderSelected,
    )
    fake.calls.clear()
    now = 4_001L

    val failed = coordinator.pollActive(maxAgeNs = 2_000L)

    assertEquals(SpatialVideoSourceReason.ReceiptStale, failed.failureReason)
    assertNull(failed.ownedAcquisition)
    assertTrue(fake.calls.contains("stop:peer:1"))
  }

  @Test fun localPendingStartupDeadlineRetiresOwnedAcquisition() {
    var now = 1_000L
    val fake = FakeExecution()
    val coordinator = SpatialVideoSourceRoutingCoordinator(fake) { now }
    val pending = coordinator.requestProjectionSource(
        SpatialVideoSource.Local, null, carrier, "local")
    fake.readback = fake.readbackFor(
        SpatialVideoSource.Local,
        pending.generation,
        SpatialVideoSourceResult.Pending,
        SpatialVideoSourceStage.ProviderSelected,
    )
    fake.calls.clear()
    now = 4_001L

    val failed = coordinator.pollActive(maxAgeNs = 2_000L)

    assertEquals(SpatialVideoSourceReason.ReceiptStale, failed.failureReason)
    assertNull(failed.ownedAcquisition)
    assertTrue(fake.calls.contains("stop:local:2"))
  }

  @Test fun missingPeerReadbackStopsOwnedAcquisition() {
    val fake = FakeExecution()
    val coordinator = coordinator(fake)
    coordinator.requestProjectionSource(
        SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
    fake.readback = null
    fake.calls.clear()

    val failed = coordinator.pollActive(maxAgeNs = 2_000L)

    assertEquals(SpatialVideoSourceReason.ReceiptUnavailable, failed.failureReason)
    assertTrue(fake.calls.contains("stop:peer:1"))
    assertNull(failed.ownedAcquisition)
  }

  @Test fun missingPeerReadbackFailedStopRetainsTruthfulOwnership() {
    val fake = FakeExecution()
    val coordinator = coordinator(fake)
    coordinator.requestProjectionSource(
        SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
    fake.readback = null
    fake.stopResult = false
    fake.calls.clear()

    val failed = coordinator.pollActive(maxAgeNs = 2_000L)

    assertEquals(SpatialVideoSourceReason.AcquisitionStopFailed, failed.failureReason)
    assertTrue(fake.calls.contains("stop:peer:1"))
    assertEquals(SpatialVideoSource.Peer, failed.ownedAcquisition)
  }

  @Test fun pendingCleanupCanAdvanceOnlyFromExactInactiveIdentity() {
    val fake = FakeExecution().apply {
      producer = SpatialProjectionProducerState.ProducerActive(91L, 6L)
      cleanupResult = SpatialProjectionProducerState.CleanupPending(91L, 6L, 1L)
    }
    val coordinator = coordinator(fake)
    val pending = coordinator.requestProjectionSource(SpatialVideoSource.Local, null, carrier, "local")
    assertEquals(SpatialVideoSource.Local, pending.pending)
    fake.cleanupReadResult = SpatialProjectionProducerState.ProducerInactive(91L, 6L, 1L)
    fake.immediateResult = SpatialVideoSourceResult.Effective

    val effective = coordinator.resumePending(carrier, "cleanup-poll")

    assertEquals(SpatialVideoSource.Local, effective.effective)
    assertTrue(fake.calls.any { it == "cleanup-read:91:6:1" })
  }

  @Test fun repeatedLocalRollsCleanupToCurrentGeneration() {
    val fake = FakeExecution().apply {
      producer = SpatialProjectionProducerState.ProducerActive(91L, 6L)
      cleanupResult = SpatialProjectionProducerState.CleanupPending(91L, 6L, 1L)
    }
    val coordinator = coordinator(fake)
    coordinator.requestProjectionSource(SpatialVideoSource.Local, null, carrier, "first")
    fake.cleanupResult = null
    fake.cleanupReadResult = null
    fake.calls.clear()

    val repeated = coordinator.requestProjectionSource(
        SpatialVideoSource.Local, null, carrier, "second")

    assertEquals(2L, repeated.generation)
    assertTrue(fake.calls.contains("cleanup:91:6:2"))
    assertTrue(fake.calls.contains("cleanup-read:91:6:2"))
    assertFalse(fake.calls.contains("cleanup-read:91:6:1"))
  }

  @Test fun foreignOrStaleCleanupReceiptNeverSelectsLocalProvider() {
    listOf(
        SpatialProjectionProducerState.ProducerInactive(92L, 6L, 1L),
        SpatialProjectionProducerState.ProducerInactive(91L, 6L, 0L),
    ).forEach { foreign ->
      val fake = FakeExecution().apply {
        producer = SpatialProjectionProducerState.ProducerActive(91L, 6L)
        cleanupResult = SpatialProjectionProducerState.CleanupPending(91L, 6L, 1L)
      }
      val coordinator = coordinator(fake)
      coordinator.requestProjectionSource(SpatialVideoSource.Local, null, carrier, "local")
      fake.cleanupReadResult = foreign

      val denied = coordinator.resumePending(carrier, "cleanup-poll")

      assertEquals(SpatialVideoSourceReason.ProducerCleanupForeign, denied.failureReason)
      assertFalse(fake.calls.any { it.startsWith("native:local:") })
    }
  }

  @Test fun foreignProviderSelectionReceiptCannotStartPeerDecoder() {
    val mutations: List<(SpatialVideoSourceNativeReadback) -> SpatialVideoSourceNativeReadback> =
        listOf(
            { it.copy(routeGeneration = it.routeGeneration + 1L) },
            { it.copy(source = SpatialVideoSource.Local) },
            { it.copy(launchChallenge = it.launchChallenge + 1L) },
            { it.copy(surfaceGeneration = it.surfaceGeneration + 1L) },
        )
    mutations.forEach { mutation ->
      val fake = FakeExecution().apply { nativeTransform = mutation }
      val denied = coordinator(fake).requestProjectionSource(
          SpatialVideoSource.Peer, peerSettings(), carrier, "peer")

      assertEquals(SpatialVideoSourceReason.ReceiptForeign, denied.failureReason)
      assertFalse(fake.calls.any { it.startsWith("peer-settings:") })
    }
  }

  @Test fun peerPendingAcquisitionIsStoppedByDisabledEvenBeforePeerBecomesEffective() {
    val fake = FakeExecution()
    val coordinator = coordinator(fake)
    val pending = coordinator.requestProjectionSource(
        SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
    assertEquals(SpatialVideoSource.Peer, pending.ownedAcquisition)
    fake.immediateResult = SpatialVideoSourceResult.Inactive
    fake.calls.clear()

    val disabled = coordinator.requestProjectionSource(
        SpatialVideoSource.Disabled, null, carrier, "disabled")

    assertEquals(SpatialVideoSource.Disabled, disabled.effective)
    assertNull(disabled.ownedAcquisition)
    assertEquals(
        listOf("demand:false", "stop:peer:2", "native:disabled:2"),
        fake.calls,
    )
  }

  @Test fun failedStopRetainsTruthfulPeerAcquisitionOwnership() {
    val fake = FakeExecution()
    val coordinator = coordinator(fake)
    coordinator.requestProjectionSource(SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
    fake.stopResult = false

    val failed = coordinator.requestProjectionSource(
        SpatialVideoSource.Disabled, null, carrier, "disabled")

    assertEquals(SpatialVideoSourceReason.AcquisitionStopFailed, failed.failureReason)
    assertEquals(SpatialVideoSource.Peer, failed.ownedAcquisition)
  }

  @Test fun peerDemandIsNeverRaisedBeforeExactProviderAdmission() {
    val fake = FakeExecution().apply {
      nativeTransform = { it.copy(surfaceGeneration = it.surfaceGeneration + 1L) }
    }

    coordinator(fake).requestProjectionSource(
        SpatialVideoSource.Peer, peerSettings(), carrier, "peer")

    assertEquals(listOf("demand:false", "peer-stage:1", "native:peer:1"), fake.calls)
  }

  @Test fun admittedPeerDispatchFailureIsRejectedAndAcquisitionIsRetired() {
    val fake = FakeExecution().apply { replaceResult = false }

    val failed = coordinator(fake).requestProjectionSource(
        SpatialVideoSource.Peer, peerSettings(), carrier, "peer")

    assertEquals(SpatialVideoSourceReason.DecoderReplacementFailed, failed.failureReason)
    assertNull(failed.ownedAcquisition)
    assertEquals(
        listOf(
            "demand:false",
            "peer-stage:1",
            "native:peer:1",
            "peer-ownership:1",
            "demand:true",
            "peer-settings:1",
            "demand:false",
            "stop:peer:1",
        ),
        fake.calls,
    )
  }

  @Test fun admittedPendingPeerAuthorityBlocksLocalBeforeFirstFrame() {
    val fake = FakeExecution()
    val coordinator = coordinator(fake)
    val pending = coordinator.requestProjectionSource(
        SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
    assertTrue(
        pending.producerState is SpatialProjectionProducerState.ProducerAuthorityUnavailable)
    fake.producer = pending.producerState
    fake.calls.clear()

    val local = coordinator.requestProjectionSource(
        SpatialVideoSource.Local, null, carrier, "local")

    assertEquals(SpatialVideoSourceReason.ProducerCleanupDenied, local.failureReason)
    assertFalse(fake.calls.any { it.startsWith("native:local:") })
  }

  @Test fun localAdmissionNeverRaisesPeerDecoderDemandForPeerShapedLaunchSettings() {
    val fake = FakeExecution().apply { immediateResult = SpatialVideoSourceResult.Effective }

    val local = coordinator(fake).requestProjectionSource(
        SpatialVideoSource.Local, peerSettings(), carrier, "cold-local")

    assertEquals(SpatialVideoSource.Local, local.effective)
    assertFalse(fake.calls.contains("demand:true"))
    assertFalse(fake.calls.any { it.startsWith("peer-settings:") })
  }

  @Test fun peerAdmissionRevokesDirectViewerBeforeStartingPeerDecoder() {
    val fake = FakeExecution().apply { directViewerActive = true }

    coordinator(fake).requestProjectionSource(
        SpatialVideoSource.Peer, peerSettings(), carrier, "hidden-peer")

    assertFalse(fake.directViewerActive)
    assertFalse(fake.decoderOverlapObserved)
    assertTrue(fake.calls.indexOf("peer-ownership:1") < fake.calls.indexOf("peer-settings:1"))
  }

  @Test fun blockedStopDoesNotBlockSnapshotOrNewRequestAndCannotCommitStaleResult() {
    val fake = FakeExecution()
    val coordinator = coordinator(fake)
    coordinator.requestProjectionSource(SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
    fake.stopEntered = CountDownLatch(1)
    fake.stopRelease = CountDownLatch(1)
    val pool = Executors.newCachedThreadPool()
    try {
      val blocked = pool.submit {
        coordinator.requestProjectionSource(SpatialVideoSource.Disabled, null, carrier, "disabled")
      }
      assertTrue(fake.stopEntered!!.await(1, TimeUnit.SECONDS))
      val snapshot = pool.submit<SpatialVideoSourceRoutingState> { coordinator.snapshot() }
          .get(200, TimeUnit.MILLISECONDS)
      assertEquals(2L, snapshot.generation)
      val newer = pool.submit<SpatialVideoSourceRoutingState> {
        coordinator.beginProjectionSourceRequest(SpatialVideoSource.Local, null)
      }.get(200, TimeUnit.MILLISECONDS)
      assertEquals(3L, newer.generation)
      fake.stopRelease!!.countDown()
      blocked.get(1, TimeUnit.SECONDS)
      assertEquals(3L, coordinator.snapshot().generation)
      assertEquals(SpatialVideoSource.Local, coordinator.snapshot().pending)
    } finally {
      fake.stopRelease?.countDown()
      pool.shutdownNow()
    }
  }

  @Test fun blockedPeerReplaceDoesNotBlockUiStateAndCannotCommitAfterNewGeneration() {
    val fake = FakeExecution().apply {
      replaceEntered = CountDownLatch(1)
      replaceRelease = CountDownLatch(1)
    }
    val coordinator = coordinator(fake)
    val pool = Executors.newCachedThreadPool()
    try {
      val blocked = pool.submit {
        coordinator.requestProjectionSource(SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
      }
      assertTrue(fake.replaceEntered!!.await(1, TimeUnit.SECONDS))
      pool.submit<SpatialVideoSourceRoutingState> { coordinator.snapshot() }
          .get(200, TimeUnit.MILLISECONDS)
      val newer = pool.submit<SpatialVideoSourceRoutingState> {
        coordinator.beginProjectionSourceRequest(SpatialVideoSource.Disabled, null)
      }.get(200, TimeUnit.MILLISECONDS)
      assertEquals(2L, newer.generation)
      fake.replaceRelease!!.countDown()
      blocked.get(1, TimeUnit.SECONDS)
      val final = coordinator.snapshot()
      assertEquals(2L, final.generation)
      assertEquals(SpatialVideoSource.Disabled, final.pending)
      assertEquals(SpatialVideoSource.Disabled, final.effective)
    } finally {
      fake.replaceRelease?.countDown()
      pool.shutdownNow()
    }
  }

  @Test fun missingPeerProducerAuthorityBlocksLaterLocalWithoutInventingIdentity() {
    val fake = FakeExecution()
    val coordinator = coordinator(fake)
    val peer = coordinator.requestProjectionSource(
        SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
    coordinator.reportNativeReadback(
        peerEffective(peer.generation).copy(producerSession = 0L, producerEpoch = 0L)
    )
    assertTrue(
        coordinator.snapshot().producerState is
            SpatialProjectionProducerState.ProducerAuthorityUnavailable
    )
    fake.producer = coordinator.snapshot().producerState
    fake.calls.clear()

    val local = coordinator.requestProjectionSource(
        SpatialVideoSource.Local, null, carrier, "local")

    assertEquals(SpatialVideoSourceReason.ProducerCleanupDenied, local.failureReason)
    assertFalse(fake.calls.any { it.startsWith("native:local:") })
  }

  @Test fun disabledPreservesUnknownExternalProducerAuthorityWhileStoppingReceiver() {
    val fake = FakeExecution().apply {
      producer = SpatialProjectionProducerState.ProducerAuthorityUnavailable(1L)
      immediateResult = SpatialVideoSourceResult.Inactive
    }
    val coordinator = coordinator(fake)
    // Seed the coordinator's truthful unknown authority via a zero-authority Peer receipt.
    val peer = coordinator.requestProjectionSource(
        SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
    coordinator.reportNativeReadback(
        peerEffective(peer.generation).copy(producerSession = 0L, producerEpoch = 0L)
    )
    fake.producer = coordinator.snapshot().producerState

    val disabled = coordinator.requestProjectionSource(
        SpatialVideoSource.Disabled, null, carrier, "disabled")

    assertEquals(SpatialVideoSource.Disabled, disabled.effective)
    assertTrue(
        disabled.producerState is SpatialProjectionProducerState.ProducerAuthorityUnavailable
    )
  }

  @Test fun monotonicFreshnessRejectsFutureOrOverAgeNativeTimestamps() {
    assertTrue(SpatialVideoSourceFreshness.isStale(100L, 101L, 50L))
    assertTrue(SpatialVideoSourceFreshness.isStale(100L, 40L, 50L))
    assertFalse(SpatialVideoSourceFreshness.isStale(100L, 50L, 50L))
  }

  @Test fun localNullReadbackRetiresWithAFreshNativeGeneration() {
    val fake = FakeExecution().apply {
      immediateResult = SpatialVideoSourceResult.Effective
      strictIncreasingNativeGeneration = true
    }
    val coordinator = coordinator(fake)
    val local = coordinator.requestProjectionSource(SpatialVideoSource.Local, null, carrier, "local")
    fake.readback = null

    val failed = coordinator.pollActive(2_000L)

    assertEquals(SpatialVideoSourceReason.ReceiptUnavailable, failed.failureReason)
    assertTrue(fake.calls.contains("stop:local:${local.generation + 1L}"))
    assertFalse(fake.strictGenerationRejected)
  }

  @Test fun localLostReadbackRetiresWithAFreshNativeGeneration() {
    val fake = FakeExecution().apply {
      immediateResult = SpatialVideoSourceResult.Effective
      strictIncreasingNativeGeneration = true
    }
    val coordinator = coordinator(fake)
    val local = coordinator.requestProjectionSource(SpatialVideoSource.Local, null, carrier, "local")
    fake.readback = fake.readbackFor(
        SpatialVideoSource.Local, local.generation, SpatialVideoSourceResult.Lost,
        SpatialVideoSourceStage.requiredFor(SpatialVideoSource.Local),
    ).copy(reason = SpatialVideoSourceReason.ActiveSourceLost)

    coordinator.pollActive(2_000L)

    assertTrue(fake.calls.contains("stop:local:${local.generation + 1L}"))
    assertFalse(fake.strictGenerationRejected)
  }

  @Test fun localPendingTimeoutGetsFullPostAdmissionIntervalAndFreshStopGeneration() {
    var now = 1_000L
    val fake = FakeExecution().apply {
      immediateResult = SpatialVideoSourceResult.Pending
      strictIncreasingNativeGeneration = true
    }
    val coordinator = SpatialVideoSourceRoutingCoordinator(fake) { now }
    val waiting = coordinator.requestProjectionSource(SpatialVideoSource.Local, null, null, "wait")
    assertEquals(0L, waiting.pendingSinceNs)
    now = 20_000L
    val admitted = coordinator.executeRequest(waiting.generation, carrier, "carrier-ready")
    assertEquals(20_000L, admitted.pendingSinceNs)
    fake.readback = fake.readbackFor(
        SpatialVideoSource.Local, admitted.generation, SpatialVideoSourceResult.Pending,
        SpatialVideoSourceStage.ProviderSelected,
    )
    now = 21_999L
    assertNull(coordinator.pollActive(2_000L).failed)
    now = 22_001L

    val failed = coordinator.pollActive(2_000L)

    assertEquals(SpatialVideoSourceReason.ReceiptStale, failed.failureReason)
    assertTrue(fake.calls.contains("stop:local:${admitted.generation + 1L}"))
    assertFalse(fake.strictGenerationRejected)
  }

  @Test fun asynchronousLocalRetirementPollsTheSameExactFreshStopGeneration() {
    val fake = FakeExecution().apply {
      immediateResult = SpatialVideoSourceResult.Effective
      strictIncreasingNativeGeneration = true
      stopResults.add(false)
      stopResults.add(true)
    }
    val coordinator = coordinator(fake)
    val local = coordinator.requestProjectionSource(SpatialVideoSource.Local, null, carrier, "local")
    fake.readback = null

    coordinator.pollActive(2_000L)
    coordinator.pollActive(2_000L)

    val stops = fake.calls.filter { it.startsWith("stop:local:") }
    assertEquals(listOf("stop:local:${local.generation + 1L}", "stop:local:${local.generation + 1L}"), stops)
    assertFalse(fake.strictGenerationRejected)
    assertNull(coordinator.snapshot().ownedAcquisition)
  }

  @Test fun oldGenerationLossCannotMutateTheNewFailClosedRequest() {
    val fake = FakeExecution()
    val coordinator = coordinator(fake)
    val peer = coordinator.requestProjectionSource(
        SpatialVideoSource.Peer, peerSettings(), carrier, "peer")
    val oldEffective = peerEffective(peer.generation)
    coordinator.reportNativeReadback(oldEffective)

    val local = coordinator.beginProjectionSourceRequest(SpatialVideoSource.Local, null)
    coordinator.reportNativeReadback(
        oldEffective.copy(
            result = SpatialVideoSourceResult.Lost,
            reason = SpatialVideoSourceReason.ActiveSourceLost,
        )
    )

    assertEquals(SpatialVideoSource.Local, coordinator.snapshot().pending)
    assertEquals(SpatialVideoSource.Disabled, coordinator.snapshot().effective)
    assertEquals(local.generation, coordinator.snapshot().generation)
    assertEquals(SpatialVideoSourceReason.None, coordinator.snapshot().failureReason)
  }

  @Test fun nativeAbiRejectsUnknownOrTruncatedReadback() {
    assertNull(SpatialVideoSourceNativeAbi.decodeReadback(longArrayOf(1L)))
    val words = SpatialVideoSourceNativeAbi.encodeRequest(
        SpatialVideoSourceNativeRequest(1L, SpatialVideoSource.Local,
            launchChallenge = 2L, surfaceGeneration = 3L)
    )
    words[2] = 99L
    assertNull(SpatialVideoSourceNativeAbi.decodeReadback(words))
  }

  private fun coordinator(fake: FakeExecution) = SpatialVideoSourceRoutingCoordinator(fake) { 1_000L }

  private fun peerSettings() = SpatialVideoProjectionSettings.disabled().copy(
      enabled = true, source = "peer-packed-stereo", brokerPort = 9079,
      peerSessionId = "accepted-session", maxImages = 4,
  )

  private fun peerEffective(generation: Long) = SpatialVideoSourceNativeReadback(
      routeGeneration = generation, source = SpatialVideoSource.Peer, decoderToken = 11L,
      readerGeneration = 12L, launchChallenge = carrier.launchChallenge,
      surfaceGeneration = carrier.surfaceGeneration, pairGeneration = 13L,
      importGeneration = 14L, acquisitionTimeNs = 900L,
      stages = SpatialVideoSourceStage.requiredFor(SpatialVideoSource.Peer),
      result = SpatialVideoSourceResult.Effective, reason = SpatialVideoSourceReason.None,
      cameraStartRequested = false, producerSession = 81L, producerEpoch = 4L,
  )

  private class FakeExecution : SpatialVideoSourceExecutionAdapter {
    val calls = ArrayList<String>()
    var producer: SpatialProjectionProducerState = SpatialProjectionProducerState.NoProducerOwned
    var cleanupResult: SpatialProjectionProducerState? = null
    var cleanupReadResult: SpatialProjectionProducerState? = null
    var immediateResult = SpatialVideoSourceResult.Pending
    var disabledPendingStages = SpatialVideoSourceStage.AcquisitionStopped
    var adoptedSettings: SpatialVideoProjectionSettings? = null
    var readback: SpatialVideoSourceNativeReadback? = null
    var nativeTransform: (SpatialVideoSourceNativeReadback) -> SpatialVideoSourceNativeReadback = { it }
    var replaceResult = true
    var peerOwnershipPrepared = true
    var stopResult = true
    var directViewerActive = false
    var decoderOverlapObserved = false
    var stopEntered: CountDownLatch? = null
    var stopRelease: CountDownLatch? = null
    var replaceEntered: CountDownLatch? = null
    var replaceRelease: CountDownLatch? = null
    var strictIncreasingNativeGeneration = false
    var strictGenerationRejected = false
    var highestNativeGeneration = 0L
    var admittedLocalStopGeneration = 0L
    val stopResults = java.util.ArrayDeque<Boolean>()

    override fun producerState() = producer

    override fun requestProducerCleanup(
        routeGeneration: Long,
        producer: SpatialProjectionProducerState.ProducerActive,
    ): SpatialProjectionProducerState {
      calls += "cleanup:${producer.session}:${producer.epoch}:$routeGeneration"
      return (cleanupResult ?: SpatialProjectionProducerState.CleanupPending(
          producer.session, producer.epoch, routeGeneration)).also { this.producer = it }
    }

    override fun readProducerCleanup(
        pending: SpatialProjectionProducerState.CleanupPending,
    ): SpatialProjectionProducerState? {
      calls += "cleanup-read:${pending.session}:${pending.epoch}:${pending.routeGeneration}"
      return cleanupReadResult?.also { producer = it }
    }

    override fun selectNativeProvider(
        request: SpatialVideoSourceNativeRequest,
        context: SpatialVideoSourceCarrierContext,
    ): SpatialVideoSourceNativeReadback {
      calls += "native:${request.source.token}:${request.routeGeneration}"
      if (strictIncreasingNativeGeneration && request.routeGeneration <= highestNativeGeneration) {
        strictGenerationRejected = true
        return readbackFor(
            request.source,
            request.routeGeneration,
            SpatialVideoSourceResult.Rejected,
            0L,
        ).copy(reason = SpatialVideoSourceReason.ReceiptForeign)
      }
      highestNativeGeneration = maxOf(highestNativeGeneration, request.routeGeneration)
      val stages = when (request.source) {
        SpatialVideoSource.Disabled -> disabledPendingStages
        SpatialVideoSource.Local -> SpatialVideoSourceStage.requiredFor(SpatialVideoSource.Local)
        SpatialVideoSource.Peer -> SpatialVideoSourceStage.ProviderSelected
      }
      return nativeTransform(SpatialVideoSourceNativeReadback(
          request.routeGeneration, request.source, 0L,
          if (request.source == SpatialVideoSource.Local) 17L else 0L,
          request.launchChallenge, request.surfaceGeneration, 0L,
          if (request.source == SpatialVideoSource.Local) 18L else 0L,
          800L, stages, immediateResult,
          SpatialVideoSourceReason.None, request.cameraStartAllowed, 0L, 0L,
      ))
    }

    fun readbackFor(
        source: SpatialVideoSource,
        generation: Long,
        result: SpatialVideoSourceResult,
        stages: Long,
    ) = SpatialVideoSourceNativeReadback(
        routeGeneration = generation,
        source = source,
        decoderToken = 0L,
        readerGeneration = 0L,
        launchChallenge = 31L,
        surfaceGeneration = 7L,
        pairGeneration = 0L,
        importGeneration = 0L,
        acquisitionTimeNs = if (result == SpatialVideoSourceResult.Pending) 0L else 1_000L,
        stages = stages,
        result = result,
        reason = SpatialVideoSourceReason.None,
        cameraStartRequested = false,
        producerSession = 0L,
        producerEpoch = 0L,
    )

    override fun readNativeSource(routeGeneration: Long) = readback

    override fun stagePeerSettings(
        settings: SpatialVideoProjectionSettings,
        routeGeneration: Long,
    ): Boolean {
      calls += "peer-stage:$routeGeneration"
      return true
    }

    override fun replacePeerSettings(
        settings: SpatialVideoProjectionSettings,
        routeGeneration: Long,
        reason: String,
    ): Boolean {
      calls += "peer-settings:$routeGeneration"
      replaceEntered?.countDown()
      replaceRelease?.await(2, TimeUnit.SECONDS)
      decoderOverlapObserved = decoderOverlapObserved || directViewerActive
      adoptedSettings = settings
      return replaceResult
    }

    override fun preparePeerDecoderOwnership(routeGeneration: Long, reason: String): Boolean {
      calls += "peer-ownership:$routeGeneration"
      directViewerActive = false
      return peerOwnershipPrepared
    }

    override fun stopSourceAcquisition(
        source: SpatialVideoSource,
        routeGeneration: Long,
        reason: String,
    ): Boolean {
      calls += "stop:${source.token}:$routeGeneration"
      if (strictIncreasingNativeGeneration && source == SpatialVideoSource.Local) {
        if (routeGeneration < highestNativeGeneration ||
            (routeGeneration == highestNativeGeneration &&
                routeGeneration != admittedLocalStopGeneration)) {
          strictGenerationRejected = true
          return false
        }
        if (routeGeneration > highestNativeGeneration) {
          highestNativeGeneration = routeGeneration
          admittedLocalStopGeneration = routeGeneration
        }
      }
      stopEntered?.countDown()
      stopRelease?.await(2, TimeUnit.SECONDS)
      return if (stopResults.isEmpty()) stopResult else stopResults.removeFirst()
    }

    override fun updateSourceOwnerDemand(required: Boolean, reason: String) {
      calls += "demand:$required"
    }
  }
}
