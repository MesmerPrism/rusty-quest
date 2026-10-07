package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SpatialVideoSourceRouteTransitionActivityContractTest {
  @Test fun activityRecordsEverySourceLifecycleReturnPath() {
    val source = File("src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/SpatialCameraPanelActivity.kt")
        .readText()
    listOf(
        "val executed =\n          spatialVideoSourceRoutingCoordinator.executeRequest",
        "recordProjectionSourceRouteTransition(executed, \"${'$'}reason-execute\")",
        "val resumed = spatialVideoSourceRoutingCoordinator.resumePending",
        "recordProjectionSourceRouteTransition(resumed, \"${'$'}reason-cleanup-poll\")",
        "val polled = spatialVideoSourceRoutingCoordinator.pollActive",
        "recordProjectionSourceRouteTransition(polled, \"${'$'}reason-active-poll\")",
        "recordProjectionSourceRouteTransition(it, \"${'$'}reason-resume\")",
        "recordProjectionSourceRouteTransition(it, \"native-readback\")",
    ).forEach { expected -> assertTrue(source.contains(expected), expected) }
  }
}
