package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialVideoDecoderLifecycleTest {
  @Test
  fun replacementIsForbiddenWhilePreviousDecoderThreadIsAlive() {
    assertFalse(SpatialStereoVideoPlayback.replacementAllowedAfterStop(true))
    assertTrue(SpatialStereoVideoPlayback.replacementAllowedAfterStop(false))
    assertTrue(SpatialStereoVideoPlayback.decoderStopJoinTimeoutMs() >= 1_000L)
  }

  @Test
  fun codecOutputCadenceKeepsMicrosecondQuantizedThirtyFpsFrames() {
    assertTrue(SpatialStereoVideoPlayback.shouldRenderSurfaceOutput(-1L, 0L, 30))
    assertTrue(SpatialStereoVideoPlayback.shouldRenderSurfaceOutput(0L, 33_333L, 30))
    assertTrue(SpatialStereoVideoPlayback.shouldRenderSurfaceOutput(33_333L, 66_667L, 30))
  }

  @Test
  fun codecOutputCadenceSkipsIntermediateSixtyFpsSurfaceFrames() {
    assertTrue(SpatialStereoVideoPlayback.shouldRenderSurfaceOutput(-1L, 0L, 30))
    assertFalse(SpatialStereoVideoPlayback.shouldRenderSurfaceOutput(0L, 16_667L, 30))
    assertTrue(SpatialStereoVideoPlayback.shouldRenderSurfaceOutput(0L, 33_333L, 30))
    assertFalse(SpatialStereoVideoPlayback.shouldRenderSurfaceOutput(33_333L, 50_000L, 30))
    assertTrue(SpatialStereoVideoPlayback.shouldRenderSurfaceOutput(33_333L, 66_667L, 30))
  }

  @Test
  fun codecOutputCadenceRestartsSafelyForANonMonotonicTimeline() {
    assertTrue(SpatialStereoVideoPlayback.shouldRenderSurfaceOutput(90_000L, 0L, 30))
  }

  @Test
  fun codecOutputCadenceMarkerNamesThePreSurfaceBoundaryAndFallback() {
    val marker = SpatialStereoVideoPlayback.codecOutputCadenceMarker(120L, 60L, 60L, 30, false)

    assertTrue(marker.contains("decodedOutputFrames=120"))
    assertTrue(marker.contains("surfaceRenderedFrames=60"))
    assertTrue(marker.contains("surfaceSkippedFrames=60"))
    assertTrue(marker.contains("cadenceBoundary=mediacodec-output-before-surface"))
    assertTrue(marker.contains("compressedReferenceFramesPreserved=true"))
    assertTrue(marker.contains("nativeCadenceFallbackRetained=true"))
  }

  @Test
  fun decoderBecomesEffectiveOnlyAfterFirstDecodedFrameAndErrorClearsIt() {
    val calls = ArrayList<String>()
    val states = ArrayList<SpatialVideoProjectionDecoderState>()
    var callbacks: SpatialVideoProjectionPlaybackCallbacks? = null
    val coordinator =
        SpatialVideoProjectionRuntimeCoordinator(
            bindings(calls = calls, stopResult = true, startResult = true).copy(
                startPlayback = { settings, _, receivedCallbacks ->
                  calls += "start:${settings.path}"
                  callbacks = receivedCallbacks
                  true
                },
                onDecoderStateChanged = { state, _ -> states += state },
            )
        )
    val settings = activeSettings("content://plain/lifecycle")
    coordinator.adoptSettings(settings)

    coordinator.start(settings, "lifecycle")

    assertEquals(SpatialVideoProjectionDecoderState.Starting, coordinator.decoderState)
    assertFalse(coordinator.started)
    callbacks!!.onFirstFrame()
    assertEquals(SpatialVideoProjectionDecoderState.Effective, coordinator.decoderState)
    assertTrue(coordinator.started)
    callbacks!!.onError("offline-pack-chunk-authentication-failed")
    assertEquals(SpatialVideoProjectionDecoderState.Failed, coordinator.decoderState)
    assertFalse(coordinator.started)
    assertEquals(
        listOf(
            SpatialVideoProjectionDecoderState.Starting,
            SpatialVideoProjectionDecoderState.Effective,
            SpatialVideoProjectionDecoderState.Failed,
        ),
        states,
    )
  }

  @Test
  fun failedDecoderCanBeRetriedWithoutAFalseStartedState() {
    val calls = ArrayList<String>()
    val callbacks = ArrayList<SpatialVideoProjectionPlaybackCallbacks>()
    val coordinator =
        SpatialVideoProjectionRuntimeCoordinator(
            bindings(calls = calls, stopResult = true, startResult = true).copy(
                startPlayback = { settings, _, receivedCallbacks ->
                  calls += "start:${settings.path}"
                  callbacks += receivedCallbacks
                  true
                }
            )
        )
    val settings = activeSettings("content://plain/retry")
    coordinator.adoptSettings(settings)
    coordinator.start(settings, "initial")
    callbacks.single().onError("decoder-error")

    coordinator.updateReadableVideoConsumer(true, "retry")

    assertEquals(2, callbacks.size)
    assertEquals(SpatialVideoProjectionDecoderState.Starting, coordinator.decoderState)
    assertFalse(coordinator.started)
    callbacks.last().onFirstFrame()
    assertTrue(coordinator.started)
  }

  @Test
  fun customProjectionStopsOldDecoderBeforeConfiguringAndStartingNewSource() {
    val calls = ArrayList<String>()
    val coordinator =
        SpatialVideoProjectionRuntimeCoordinator(
            bindings(
                calls = calls,
                stopResult = true,
                startResult = true,
            )
        )
    val first = activeSettings("content://plain/first")
    val second = activeSettings("content://plain/second")
    coordinator.adoptSettings(first)
    coordinator.start(first, "initial")
    calls.clear()

    val replaced = coordinator.replaceMediaSource(second, null, "selection")

    assertTrue(replaced.applied)
    assertTrue(replaced.decoderStarted)
    assertEquals(listOf("stop", "configure:content://plain/second", "start:content://plain/second"), calls)
    assertEquals(second, coordinator.settings)
    assertTrue(coordinator.started)
  }

  @Test
  fun failedOldDecoderStopBlocksReplacementInsteadOfOverlapping() {
    val calls = ArrayList<String>()
    val coordinator =
        SpatialVideoProjectionRuntimeCoordinator(
            bindings(
                calls = calls,
                stopResult = false,
                startResult = true,
            )
        )
    val first = activeSettings("content://plain/first")
    val second = activeSettings("content://plain/second")
    coordinator.adoptSettings(first)
    coordinator.start(first, "initial")
    calls.clear()

    val replaced = coordinator.replaceMediaSource(second, null, "selection")

    assertFalse(replaced.applied)
    assertFalse(replaced.decoderStarted)
    assertEquals(listOf("stop"), calls)
    assertEquals(first, coordinator.settings)
    assertTrue(coordinator.started)
  }

  @Test
  fun transparentUnderlayNeverStartsTheZeroContributionCustomDecoder() {
    val calls = ArrayList<String>()
    val coordinator =
        SpatialVideoProjectionRuntimeCoordinator(
            bindings(calls = calls, stopResult = true, startResult = true)
        )
    val settings = activeSettings("content://plain/underlay")
    coordinator.adoptSettings(settings)

    coordinator.updateReadableVideoConsumer(false, "transparent-underlay")
    coordinator.start(settings, "initial")

    assertFalse(coordinator.started)
    assertTrue(calls.isEmpty())
  }

  @Test
  fun composedStartupOwnershipKeepsZeroDemandSeparateFromFailureAndHiddenDirectOwnership() {
    val zeroDemandCalls = ArrayList<String>()
    val zeroDemand =
        SpatialVideoProjectionRuntimeCoordinator(
            bindings(calls = zeroDemandCalls, stopResult = true, startResult = true)
        )
    val settings = activeSettings("content://plain/composed-ownership")
    zeroDemand.adoptSettings(settings)
    zeroDemand.updateReadableVideoConsumer(false, "transparent-underlay")

    val skipped =
        zeroDemand.startForComposedOwnership(
            settings,
            projectionPanelVisible = true,
            reason = "startup",
        )

    assertEquals(SpatialVideoProjectionStartupDisposition.ZeroDemandSkipped, skipped.disposition)
    assertFalse(skipped.directVideoConsumerRequired)
    assertTrue(zeroDemandCalls.isEmpty())

    val hiddenCalls = ArrayList<String>()
    val hidden =
        SpatialVideoProjectionRuntimeCoordinator(
            bindings(calls = hiddenCalls, stopResult = true, startResult = true)
        )
    hidden.adoptSettings(settings)
    val direct =
        hidden.startForComposedOwnership(
            settings,
            projectionPanelVisible = false,
            reason = "startup",
        )

    assertEquals(SpatialVideoProjectionStartupDisposition.ProjectionHiddenDirect, direct.disposition)
    assertTrue(direct.directVideoConsumerRequired)
    assertTrue(hiddenCalls.isEmpty())

    val failedCalls = ArrayList<String>()
    val failed =
        SpatialVideoProjectionRuntimeCoordinator(
            bindings(calls = failedCalls, stopResult = true, startResult = false)
        )
    failed.adoptSettings(settings)
    val fallback =
        failed.startForComposedOwnership(
            settings,
            projectionPanelVisible = true,
            reason = "startup",
        )

    assertEquals(SpatialVideoProjectionStartupDisposition.DecoderFailed, fallback.disposition)
    assertTrue(fallback.directVideoConsumerRequired)
    assertEquals(listOf("start:${settings.path}"), failedCalls)
  }

  @Test
  fun losingTheReadableConsumerStopsBeforeLaterSourceChanges() {
    val calls = ArrayList<String>()
    val coordinator =
        SpatialVideoProjectionRuntimeCoordinator(
            bindings(calls = calls, stopResult = true, startResult = true)
        )
    val first = activeSettings("content://plain/first")
    val second = activeSettings("content://plain/second")
    coordinator.adoptSettings(first)
    coordinator.start(first, "initial")
    calls.clear()

    coordinator.updateReadableVideoConsumer(false, "transparent-underlay")
    val replaced = coordinator.replaceMediaSource(second, null, "selection")

    assertTrue(replaced.applied)
    assertFalse(replaced.decoderStarted)
    assertEquals(listOf("stop", "configure:content://plain/second"), calls)
    assertFalse(coordinator.started)
    assertEquals(second, coordinator.settings)
  }

  @Test
  fun regionVisibilityChangeNeverRunsTheBoundedDecoderJoinOnTheCallerThread() {
    val calls = ArrayList<String>()
    val pending = ArrayList<() -> Unit>()
    val coordinator =
        SpatialVideoProjectionRuntimeCoordinator(
            bindings(calls = calls, stopResult = true, startResult = true).copy(
                dispatchDecoderLifecycle = { action -> pending += action }
            )
        )
    val settings = activeSettings("content://plain/nonblocking")
    coordinator.adoptSettings(settings)
    coordinator.start(settings, "initial")
    calls.clear()

    coordinator.updateReadableVideoConsumer(false, "outer-transparent")

    assertTrue(calls.isEmpty())
    assertEquals(1, pending.size)
    pending.removeAt(0).invoke()
    assertEquals(listOf("stop"), calls)
    assertFalse(coordinator.started)
  }

  @Test
  fun rapidVideoOffOnSkipsTheStaleStopAndKeepsTheExistingDecoder() {
    val calls = ArrayList<String>()
    val pending = ArrayList<() -> Unit>()
    val coordinator =
        SpatialVideoProjectionRuntimeCoordinator(
            bindings(calls = calls, stopResult = true, startResult = true).copy(
                dispatchDecoderLifecycle = { action -> pending += action }
            )
        )
    val settings = activeSettings("content://plain/retained")
    coordinator.adoptSettings(settings)
    coordinator.start(settings, "initial")
    calls.clear()

    coordinator.updateReadableVideoConsumer(false, "outer-transparent")
    coordinator.updateReadableVideoConsumer(true, "outer-video")
    pending.forEach { it.invoke() }

    assertTrue(calls.isEmpty())
    assertTrue(coordinator.started)
  }

  @Test
  fun coldStereoLayoutSwitchConfiguresCompleteGenerationBeforeDecoderStart() {
    val calls = ArrayList<String>()
    val coordinator =
        SpatialVideoProjectionRuntimeCoordinator(
            bindings(calls = calls, stopResult = true, startResult = true)
        )
    val sideBySide =
        activeSettings("content://plain/side-by-side").copy(
            stereoLayout = "side-by-side-left-right",
            mediaLayout = "side-by-side-left-right",
            width = 4096,
            height = 2048,
        )

    val switched = coordinator.replaceMediaSource(sideBySide, null, "cold-selection")

    assertTrue(switched.applied)
    assertTrue(switched.decoderStarted)
    assertEquals(1L, switched.sourceGeneration)
    assertEquals(
        listOf("configure:content://plain/side-by-side", "start:content://plain/side-by-side"),
        calls,
    )
    assertEquals("side-by-side-left-right", coordinator.settings.stereoLayout)
  }

  @Test
  fun hotTopBottomToSideBySideSwitchStopsAndReconfiguresBeforeRestart() {
    val calls = ArrayList<String>()
    val coordinator =
        SpatialVideoProjectionRuntimeCoordinator(
            bindings(calls = calls, stopResult = true, startResult = true)
        )
    val topBottom = activeSettings("content://plain/top-bottom")
    val sideBySide =
        activeSettings("content://plain/side-by-side").copy(
            stereoLayout = "side-by-side-left-right",
            mediaLayout = "side-by-side-left-right",
            width = 4096,
            height = 2048,
        )
    coordinator.adoptSettings(topBottom)
    coordinator.start(topBottom, "initial-top-bottom")
    calls.clear()

    val switched = coordinator.replaceMediaSource(sideBySide, null, "hot-stereo-layout-switch")

    assertTrue(switched.applied)
    assertTrue(switched.decoderStarted)
    assertEquals(1L, switched.sourceGeneration)
    assertEquals(
        listOf(
            "stop",
            "configure:content://plain/side-by-side",
            "start:content://plain/side-by-side",
        ),
        calls,
    )
    assertEquals("side-by-side-left-right", coordinator.settings.stereoLayout)
    assertEquals("side-by-side-left-right", coordinator.settings.mediaLayout)
    assertEquals(4096, coordinator.settings.width)
    assertEquals(2048, coordinator.settings.height)
    assertTrue(coordinator.started)
  }

  private fun bindings(
      calls: MutableList<String>,
      stopResult: Boolean,
      startResult: Boolean,
  ): SpatialVideoProjectionRuntimeBindings =
      SpatialVideoProjectionRuntimeBindings(
          nativeState = { SpatialVideoProjectionRuntimeNativeState(receiptLibraryLoaded = true) },
          configureNative = {
            calls += "configure:${it.path}"
            1L
          },
          startPlayback = { settings, _, callbacks ->
            calls += "start:${settings.path}"
            if (startResult) callbacks.onFirstFrame()
            startResult
          },
          stopPlayback = {
            calls += "stop"
            stopResult
          },
          stopNativeProbe = {},
          marker = {},
      )

  private fun activeSettings(path: String): SpatialVideoProjectionSettings =
      SpatialVideoProjectionSettings.disabled().copy(
          enabled = true,
          source = SpatialImmersiveVideoSessionPolicy.PLAIN_CUSTOM_PROJECTION_SOURCE,
          path = path,
          width = 2048,
          height = 2048,
          stereoLayout = "top-bottom-left-right",
          mediaLayout = "top-bottom-left-right",
      )
}
