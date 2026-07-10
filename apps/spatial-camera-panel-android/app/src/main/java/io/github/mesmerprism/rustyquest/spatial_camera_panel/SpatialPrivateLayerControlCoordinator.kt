package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialPrivateLayerControlBindings(
    val routeActive: () -> Boolean,
    val placementMode: () -> CameraHwbProjectionPlacementMode,
    val projectionTargetScale: () -> Float,
    val updatePlacement: (String, Boolean) -> Unit,
    val updateLayerOverrideNative: (Float) -> Long,
    val updateDepthLayerPolicyNative: (Int) -> Long,
    val updateDepthAlignmentNative: (PrivateLayerDepthAlignment) -> Long,
    val marker: (String) -> Unit,
)

internal class SpatialPrivateLayerControlCoordinator(
    private val bindings: SpatialPrivateLayerControlBindings,
) {
  var layerOverride: Float = PrivateLayerControls.cycleOverride
    private set

  var depthLayerPolicy: Int = PrivateLayerControls.defaultDepthLayerPolicy
    private set

  var depthAlignment: PrivateLayerDepthAlignment = PrivateLayerDepthAlignment()
    private set

  fun initializeDepthLayerPolicy(policy: Int) {
    depthLayerPolicy = policy
  }

  fun applyCurrentConfiguration(source: String) {
    if (!bindings.routeActive()) return
    updateLayerOverride(layerOverride, source)
    updateDepthLayerPolicy(depthLayerPolicy, source)
    updateDepthAlignment(depthAlignment, source)
  }

  fun updateLayerOverride(requestedLayerOverride: Float, source: String): Float {
    if (!bindings.routeActive()) return layerOverride
    val previousOverride = layerOverride
    val updatedOverride =
        PrivateLayerPanelControlModule.normalizeLayerOverride(requestedLayerOverride)
    bindings.marker(
        PrivateLayerPanelControlModule.layerButtonSelectedMarker(
            source = source,
            requestedLayerOverride = requestedLayerOverride,
            previousOverride = previousOverride,
            updatedOverride = updatedOverride,
            placementMode = bindings.placementMode(),
        )
    )
    layerOverride = updatedOverride
    val updateMask =
        runCatching { bindings.updateLayerOverrideNative(updatedOverride) }
            .getOrElse { throwable ->
              bindings.marker(
                  PrivateLayerPanelControlModule.layerOverrideUpdateFailedMarker(
                      source = source,
                      requestedLayerOverride = requestedLayerOverride,
                      updatedOverride = updatedOverride,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              0L
            }
    bindings.marker(
        PrivateLayerPanelControlModule.layerOverrideSubmittedMarker(
            source = source,
            updateMask = updateMask,
            previousOverride = previousOverride,
            updatedOverride = updatedOverride,
            placementMode = bindings.placementMode(),
            projectionTargetScale = bindings.projectionTargetScale(),
        )
    )
    bindings.updatePlacement("private-layer-override-panel", true)
    return updatedOverride
  }

  fun updateDepthLayerPolicy(requestedPolicy: Int, source: String): Int {
    if (!bindings.routeActive()) return depthLayerPolicy
    val previousPolicy = depthLayerPolicy
    val updatedPolicy = PrivateLayerPanelControlModule.normalizeDepthLayerPolicy(requestedPolicy)
    depthLayerPolicy = updatedPolicy
    bindings.marker(
        PrivateLayerPanelControlModule.depthLayerPolicySelectedMarker(
            source = source,
            requestedPolicy = requestedPolicy,
            previousPolicy = previousPolicy,
            updatedPolicy = updatedPolicy,
        )
    )
    val updateMask =
        runCatching { bindings.updateDepthLayerPolicyNative(updatedPolicy) }
            .getOrElse { throwable ->
              bindings.marker(
                  PrivateLayerPanelControlModule.depthLayerPolicyUpdateFailedMarker(
                      source = source,
                      updatedPolicy = updatedPolicy,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              0L
            }
    bindings.marker(
        PrivateLayerPanelControlModule.depthLayerPolicySubmittedMarker(
            source = source,
            updateMask = updateMask,
            previousPolicy = previousPolicy,
            updatedPolicy = updatedPolicy,
        )
    )
    return updatedPolicy
  }

  fun updateDepthAlignment(
      requestedAlignment: PrivateLayerDepthAlignment,
      source: String,
  ): PrivateLayerDepthAlignment {
    if (!bindings.routeActive()) return depthAlignment
    val previousAlignment = depthAlignment
    val updatedAlignment =
        PrivateLayerPanelControlModule.coerceDepthAlignment(requestedAlignment)
    depthAlignment = updatedAlignment
    val updateMask =
        runCatching { bindings.updateDepthAlignmentNative(updatedAlignment) }
            .getOrElse { throwable ->
              bindings.marker(
                  PrivateLayerPanelControlModule.depthAlignmentUpdateFailedMarker(
                      source = source,
                      updatedAlignment = updatedAlignment,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              0L
            }
    bindings.marker(
        PrivateLayerPanelControlModule.depthAlignmentSubmittedMarker(
            source = source,
            updateMask = updateMask,
            previousAlignment = previousAlignment,
            updatedAlignment = updatedAlignment,
        )
    )
    return updatedAlignment
  }

  companion object {
    const val MODULE_ID = "spatial-private-layer-control-coordinator"
  }
}
