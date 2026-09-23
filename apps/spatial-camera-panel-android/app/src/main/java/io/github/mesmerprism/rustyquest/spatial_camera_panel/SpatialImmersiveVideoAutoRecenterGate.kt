package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialImmersiveVideoAutoRecenterRequest(
    val selectionSource: String,
    val detail: String,
)

/**
 * Arms only for an actual selection change and consumes at most once when that exact media path
 * reaches a route-specific ready point. Direct and custom projection decoders can race during a
 * handoff; the first successful route owns recenter and the second becomes a no-op.
 */
internal class SpatialImmersiveVideoAutoRecenterGate {
  private var pendingPath: String? = null
  private var pendingSelectionSource: String = ""

  fun arm(path: String, selectionSource: String) {
    pendingPath = path
    pendingSelectionSource = selectionSource
  }

  fun consume(
      loadedPath: String,
      readyRoute: String,
  ): SpatialImmersiveVideoAutoRecenterRequest? {
    if (loadedPath != pendingPath) {
      return null
    }
    val selectionSource = pendingSelectionSource
    pendingPath = null
    pendingSelectionSource = ""
    return SpatialImmersiveVideoAutoRecenterRequest(
        selectionSource = selectionSource,
        detail = "selection-$selectionSource-ready-$readyRoute",
    )
  }
}
