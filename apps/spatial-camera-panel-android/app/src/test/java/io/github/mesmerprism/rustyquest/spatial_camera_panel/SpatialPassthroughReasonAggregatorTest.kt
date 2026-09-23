package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.runtime.EnvironmentDepthMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialPassthroughReasonAggregatorTest {
  @Test
  fun blackRetainsOpaqueSelectionWhileDepthKeepsPassthroughInternally() {
    val passthroughWrites = mutableListOf<Boolean>()
    var passthroughReadback = false
    val depthWrites = mutableListOf<EnvironmentDepthMode>()
    val aggregator =
        SpatialPassthroughReasonAggregator(
            setSystemPassthrough = { enabled ->
              passthroughWrites += enabled
              passthroughReadback = enabled
            },
            readSystemPassthrough = { passthroughReadback },
            setEnvironmentDepthMode = depthWrites::add,
            marker = {},
        )

    aggregator.updateVisibleReasons(SpatialBackgroundMode.Black, false, "black")
    val enabled = aggregator.updateEnvironmentDepthRequired(true, "depth-on")

    assertFalse(enabled.reasons.visibleBackground)
    assertTrue(enabled.reasons.environmentDepth)
    assertTrue(enabled.systemPassthroughEnabled)
    assertEquals(EnvironmentDepthMode.TEXTURE_ONLY, enabled.environmentDepthMode)
    assertEquals(listOf(true), passthroughWrites)
    assertEquals(listOf(EnvironmentDepthMode.TEXTURE_ONLY), depthWrites)
  }

  @Test
  fun systemPassthroughRemainsOnWhenVisibleAndDepthReasonsClear() {
    val writes = mutableListOf<String>()
    var passthroughReadback = false
    val aggregator =
        SpatialPassthroughReasonAggregator(
            setSystemPassthrough = {
              writes += "passthrough=$it"
              passthroughReadback = it
            },
            readSystemPassthrough = { passthroughReadback },
            setEnvironmentDepthMode = { writes += "depth=${it.name}" },
            marker = {},
        )

    aggregator.updateVisibleReasons(SpatialBackgroundMode.Passthrough, false, "visible-on")
    aggregator.updateEnvironmentDepthRequired(true, "depth-on")
    val depthOff = aggregator.updateEnvironmentDepthRequired(false, "depth-off")
    assertTrue(depthOff.systemPassthroughEnabled)
    assertEquals(EnvironmentDepthMode.OFF, depthOff.environmentDepthMode)

    val allOff = aggregator.updateVisibleReasons(SpatialBackgroundMode.Black, false, "visible-off")
    assertTrue(allOff.systemPassthroughEnabled)
    assertEquals(
        listOf("passthrough=true", "depth=TEXTURE_ONLY", "depth=OFF"),
        writes,
    )
  }

  @Test
  fun projectionLifecycleReconcileRequestsTheAlwaysOnSubstrateExactlyOnce() {
    val passthroughWrites = mutableListOf<Boolean>()
    var passthroughReadback = false
    val aggregator =
        SpatialPassthroughReasonAggregator(
            setSystemPassthrough = { enabled ->
              passthroughWrites += enabled
              passthroughReadback = enabled
            },
            readSystemPassthrough = { passthroughReadback },
            setEnvironmentDepthMode = {},
            marker = {},
        )

    val black = aggregator.reconcile("projection-off-black")
    assertTrue(black.systemPassthroughEnabled)
    assertEquals(listOf(true), passthroughWrites)

    aggregator.updateEnvironmentDepthRequired(true, "depth-on")
    val depthRequired = aggregator.reconcile("projection-on-depth")
    assertTrue(depthRequired.systemPassthroughEnabled)
    assertEquals(listOf(true), passthroughWrites)
  }

  @Test
  fun falsePlatformReadbackDoesNotRepeatTheExpensiveSceneMutation() {
    val passthroughWrites = mutableListOf<Boolean>()
    val aggregator =
        SpatialPassthroughReasonAggregator(
            setSystemPassthrough = passthroughWrites::add,
            readSystemPassthrough = { false },
            setEnvironmentDepthMode = {},
            marker = {},
        )

    val initial =
        aggregator.updateVisibleReasons(SpatialBackgroundMode.Passthrough, false, "initial")
    val reconcile = aggregator.reconcile("reconcile")

    assertTrue(initial.systemPassthroughEnabled)
    assertFalse(initial.systemPassthroughObserved)
    assertTrue(reconcile.systemPassthroughEnabled)
    assertFalse(reconcile.systemPassthroughObserved)
    assertEquals(listOf(true), passthroughWrites)
  }
}
