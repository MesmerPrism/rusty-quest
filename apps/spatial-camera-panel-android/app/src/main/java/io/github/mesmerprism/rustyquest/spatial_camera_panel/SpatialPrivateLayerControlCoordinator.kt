package io.github.mesmerprism.rustyquest.spatial_camera_panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class SpatialPrivateLayerControlBindings(
    val routeActive: () -> Boolean,
    val placementMode: () -> CameraHwbProjectionPlacementMode,
    val projectionTargetScale: () -> Float,
    val updatePlacement: (String, Boolean) -> Unit,
    val updateLayerOverrideNative: (Float) -> Long,
    val updateMetaPassthroughStyle: (Boolean, String) -> SpatialPassthroughLutUpdate,
    val projectionPanelEnabled: () -> Boolean,
    val refreshProjectionAfterPassthroughActivation: (String) -> Unit,
    val updateDepthLayerPolicyNative: (Int) -> Long,
    val updateDepthAlignmentNative: (PrivateLayerDepthAlignment) -> Long,
    val marker: (String) -> Unit,
)

internal class SpatialPrivateLayerControlCoordinator(
    private val bindings: SpatialPrivateLayerControlBindings,
) {
  var layerOverride: Float by mutableStateOf(PrivateLayerControls.cycleOverride)
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
    val edgeWindowSelected =
        PrivateLayerControls.metaPassthroughEdgeWindowSelected(updatedOverride)
    val enteringEdgeWindow =
        edgeWindowSelected &&
            !PrivateLayerControls.metaPassthroughEdgeWindowSelected(previousOverride)
    // The system passthrough layer and its LUT must be active before the native surface submits
    // an alpha-zero camera target. Reversing this order can leave the cutout black until the
    // projection carrier is manually stopped and restarted.
    val passthroughStyleUpdate =
        runCatching {
              bindings.updateMetaPassthroughStyle(
                  edgeWindowSelected,
                  "private-layer-${activityMarkerToken(source)}",
              )
            }
            .getOrDefault(
                SpatialPassthroughLutUpdate(
                    requested = edgeWindowSelected,
                    systemPassthroughEnabled = false,
                    lutApplied = false,
                    phase = 0.0f,
                    amplitude = 0.0f,
                )
            )
    bindings.marker(
        PrivateLayerPanelControlModule.metaPassthroughEdgeWindowSubmittedMarker(
            source = source,
            selected = edgeWindowSelected,
            passthroughStyleUpdate = passthroughStyleUpdate,
        )
    )
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
    val projectionRefreshRequested = enteringEdgeWindow && bindings.projectionPanelEnabled()
    bindings.marker(
        PrivateLayerPanelControlModule.metaPassthroughProjectionRefreshMarker(
            source = source,
            requested = projectionRefreshRequested,
            previousOverride = previousOverride,
            updatedOverride = updatedOverride,
        )
    )
    if (projectionRefreshRequested) {
      // Recreate the carrier once after passthrough is styled and the cutout is live. Spatial SDK
      // otherwise leaves the newly exposed region black until the same off/on cycle is performed
      // manually. The transition guard prevents a restart loop when raw-projection-start reapplies
      // the already-selected layer configuration.
      bindings.refreshProjectionAfterPassthroughActivation(
          "private-layer-${activityMarkerToken(source)}",
      )
    }
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
