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
          startPlayback = { settings, _ ->
            calls += "start:${settings.path}"
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
