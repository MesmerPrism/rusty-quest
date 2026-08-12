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
    val updateEnvironmentDepthConsumerRequired: (Boolean, String) -> Unit,
    val updateMetaPassthroughStyle: (Boolean, String) -> SpatialPassthroughLutUpdate,
    val projectionPanelEnabled: () -> Boolean,
    val refreshProjectionAfterPassthroughActivation: (String) -> Unit,
    val updateDepthLayerPolicyNative: (Int) -> Long,
    val updateDepthAlignmentNative: (PrivateLayerDepthAlignment) -> Long,
    val updateGuideProcessingNative: (PrivateLayerGuideProcessing) -> Long,
    val updateZoneCompositorNative: (PrivateLayerZoneCompositor) -> Long,
    val updateReadableVideoConsumerRequired: (Boolean, String) -> Unit,
    val updateRgbChannelTransformNative: (RgbChannelTransform) -> Long,
    val updateProjectionSurfaceDisplacementNative:
        (ProjectionSurfaceDisplacement) -> Long,
    val updateProjectionSurfaceFeaturesNative:
        (ProjectionSurfaceTiling, ProjectionInnerAlpha) -> Long,
    val marker: (String) -> Unit,
)

internal class SpatialPrivateLayerControlCoordinator(
    private val bindings: SpatialPrivateLayerControlBindings,
    private val fixedLayerOverride: Float? = null,
    initialZoneCompositor: PrivateLayerZoneCompositor =
        PrivateLayerZoneCompositorControls.legacyOff,
) {
  var layerOverride: Float by
      mutableStateOf(fixedLayerOverride ?: PrivateLayerControls.cycleOverride)
    private set

  var depthLayerPolicy: Int = PrivateLayerControls.defaultDepthLayerPolicy
    private set

  private var depthLayerPolicyExplicitlyUpdated: Boolean = false

  var depthAlignment: PrivateLayerDepthAlignment = PrivateLayerDepthAlignment()
    private set

  var guideProcessing: PrivateLayerGuideProcessing = PrivateLayerControls.nativeParityGuideProcessing
    private set

  private var guideProcessingExplicitlyUpdated: Boolean = false

  var zoneCompositor: PrivateLayerZoneCompositor =
      PrivateLayerZoneCompositorModule.normalize(initialZoneCompositor)
    private set

  var rgbChannelTransform: RgbChannelTransform = RgbChannelTransformControls.bypass
    private set

  var projectionSurfaceDisplacement: ProjectionSurfaceDisplacement =
      ProjectionSurfaceDisplacementControls.off
    private set

  var projectionSurfaceTiling: ProjectionSurfaceTiling = ProjectionSurfaceTilingControls.off
    private set

  var projectionInnerAlpha: ProjectionInnerAlpha = ProjectionInnerAlphaControls.off
    private set

  fun initializeDepthLayerPolicy(policy: Int) {
    if (depthLayerPolicyExplicitlyUpdated) return
    depthLayerPolicy = PrivateLayerPanelControlModule.normalizeDepthLayerPolicy(policy)
  }

  fun initializeGuideProcessing(processing: PrivateLayerGuideProcessing) {
    if (guideProcessingExplicitlyUpdated) return
    guideProcessing = PrivateLayerPanelControlModule.normalizeGuideProcessing(processing)
  }

  fun applyCurrentConfiguration(source: String) {
    if (!bindings.routeActive()) return
    updateLayerOverride(layerOverride, source)
    updateDepthLayerPolicy(depthLayerPolicy, source)
    updateDepthAlignment(depthAlignment, source)
    updateGuideProcessing(guideProcessing, source)
    updateZoneCompositor(zoneCompositor, source)
    updateRgbChannelTransform(rgbChannelTransform, source)
    updateProjectionSurfaceDisplacement(projectionSurfaceDisplacement, source)
    updateProjectionSurfaceFeatures(projectionSurfaceTiling, projectionInnerAlpha, source)
  }

  fun updateLayerOverride(requestedLayerOverride: Float, source: String): Float {
    if (!bindings.routeActive()) return layerOverride
    val previousOverride = layerOverride
    val updatedOverride =
        fixedLayerOverride
            ?: PrivateLayerPanelControlModule.normalizeLayerOverride(requestedLayerOverride)
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
    bindings.updateEnvironmentDepthConsumerRequired(
        PrivateLayerControls.environmentDepthConsumerRequired(updatedOverride),
        "private-layer-${activityMarkerToken(source)}",
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
    depthLayerPolicyExplicitlyUpdated = true
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

  fun updateGuideProcessing(
      requestedProcessing: PrivateLayerGuideProcessing,
      source: String,
  ): PrivateLayerGuideProcessing {
    if (!bindings.routeActive()) return guideProcessing
    val previousProcessing = guideProcessing
    val updatedProcessing =
        PrivateLayerPanelControlModule.normalizeGuideProcessing(requestedProcessing)
    guideProcessing = updatedProcessing
    guideProcessingExplicitlyUpdated = true
    val updateMask =
        runCatching { bindings.updateGuideProcessingNative(updatedProcessing) }
            .getOrElse { throwable ->
              bindings.marker(
                  PrivateLayerPanelControlModule.guideProcessingUpdateFailedMarker(
                      source = source,
                      updatedProcessing = updatedProcessing,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              0L
            }
    bindings.marker(
        PrivateLayerPanelControlModule.guideProcessingSubmittedMarker(
            source = source,
            updateMask = updateMask,
            previousProcessing = previousProcessing,
            updatedProcessing = updatedProcessing,
        )
    )
    return updatedProcessing
  }

  fun updateZoneCompositor(
      requestedConfiguration: PrivateLayerZoneCompositor,
      source: String,
  ): PrivateLayerZoneCompositor {
    if (!bindings.routeActive()) return zoneCompositor
    val previous = zoneCompositor
    val updated = PrivateLayerZoneCompositorModule.normalize(requestedConfiguration)
    zoneCompositor = updated
    val updateMask =
        runCatching { bindings.updateZoneCompositorNative(updated) }
            .getOrElse { throwable ->
              bindings.marker(
                  "channel=private-layer-panel status=zone-compositor-update-failed " +
                      "source=${activityMarkerToken(source)} " +
                      "error=${activityMarkerToken(throwable.javaClass.simpleName)} " +
                      "message=${activityMarkerToken(throwable.message ?: "none")} " +
                      "${PrivateLayerZoneCompositorModule.markerFields(updated)} runtimeCrash=false"
              )
              0L
            }
    bindings.marker(
        "channel=private-layer-panel status=zone-compositor-submitted " +
            "source=${activityMarkerToken(source)} transport=jni-live-queue updateMask=$updateMask " +
            "previousProjectionZoneMode=${PrivateLayerZoneCompositorControls.coverageToken(previous.coverageMode)} " +
            "${PrivateLayerZoneCompositorModule.markerFields(updated)} runtimeCrash=false"
    )
    bindings.updateReadableVideoConsumerRequired(
        PrivateLayerZoneCompositorModule.readableVideoConsumerRequired(updated),
        "private-layer-${activityMarkerToken(source)}",
    )
    return updated
  }

  fun updateRgbChannelTransform(
      requestedConfiguration: RgbChannelTransform,
      source: String,
  ): RgbChannelTransform {
    if (!bindings.routeActive()) return rgbChannelTransform
    val previous = rgbChannelTransform
    val updated = RgbChannelTransformModule.normalize(requestedConfiguration)
    rgbChannelTransform = updated
    val updateMask =
        runCatching { bindings.updateRgbChannelTransformNative(updated) }
            .getOrElse { throwable ->
              bindings.marker(
                  "channel=private-layer-panel status=rgb-channel-transform-update-failed " +
                      "source=${activityMarkerToken(source)} " +
                      "error=${activityMarkerToken(throwable.javaClass.simpleName)} " +
                      "message=${activityMarkerToken(throwable.message ?: "none")} " +
                      "${RgbChannelTransformModule.markerFields(updated)} runtimeCrash=false"
              )
              0L
            }
    bindings.marker(
        "channel=private-layer-panel status=rgb-channel-transform-submitted " +
            "source=${activityMarkerToken(source)} transport=jni-live-queue updateMask=$updateMask " +
            "previousRgbChannelTransformMode=${RgbChannelTransformControls.modeToken(previous.mode)} " +
            "${RgbChannelTransformModule.markerFields(updated)} runtimeCrash=false"
    )
    return updated
  }

  fun updateProjectionSurfaceDisplacement(
      requestedConfiguration: ProjectionSurfaceDisplacement,
      source: String,
  ): ProjectionSurfaceDisplacement {
    if (!bindings.routeActive()) return projectionSurfaceDisplacement
    val previous = projectionSurfaceDisplacement
    val updated = ProjectionSurfaceDisplacementModule.normalize(requestedConfiguration)
    projectionSurfaceDisplacement = updated
    val updateMask =
        runCatching { bindings.updateProjectionSurfaceDisplacementNative(updated) }
            .getOrElse { throwable ->
              bindings.marker(
                  "channel=private-layer-panel status=projection-surface-displacement-update-failed " +
                      "source=${activityMarkerToken(source)} " +
                      "error=${activityMarkerToken(throwable.javaClass.simpleName)} " +
                      "message=${activityMarkerToken(throwable.message ?: "none")} " +
                      "${ProjectionSurfaceDisplacementModule.markerFields(updated)} runtimeCrash=false"
              )
              0L
            }
    bindings.marker(
        "channel=private-layer-panel status=projection-surface-displacement-submitted " +
            "source=${activityMarkerToken(source)} transport=jni-live-queue updateMask=$updateMask " +
            "previousProjectionSurfaceDisplacementPreset=${ProjectionSurfaceDisplacementControls.presetToken(previous)} " +
            "${ProjectionSurfaceDisplacementModule.markerFields(updated)} runtimeCrash=false"
    )
    return updated
  }

  fun updateProjectionSurfaceTiling(
      requestedConfiguration: ProjectionSurfaceTiling,
      source: String,
  ): ProjectionSurfaceTiling {
    updateProjectionSurfaceFeatures(
        requestedTiling = requestedConfiguration,
        requestedInnerAlpha = projectionInnerAlpha,
        source = source,
    )
    return projectionSurfaceTiling
  }

  fun updateProjectionInnerAlpha(
      requestedConfiguration: ProjectionInnerAlpha,
      source: String,
  ): ProjectionInnerAlpha {
    updateProjectionSurfaceFeatures(
        requestedTiling = projectionSurfaceTiling,
        requestedInnerAlpha = requestedConfiguration,
        source = source,
    )
    return projectionInnerAlpha
  }

  fun updateProjectionSurfaceFeatures(
      requestedTiling: ProjectionSurfaceTiling,
      requestedInnerAlpha: ProjectionInnerAlpha,
      source: String,
  ): Pair<ProjectionSurfaceTiling, ProjectionInnerAlpha> {
    if (!bindings.routeActive()) {
      return projectionSurfaceTiling to projectionInnerAlpha
    }
    val previousTiling = projectionSurfaceTiling
    val previousInnerAlpha = projectionInnerAlpha
    val updatedTiling = ProjectionSurfaceTilingModule.normalize(requestedTiling)
    val updatedInnerAlpha = ProjectionInnerAlphaModule.normalize(requestedInnerAlpha)
    projectionSurfaceTiling = updatedTiling
    projectionInnerAlpha = updatedInnerAlpha
    val updateMask =
        runCatching {
              bindings.updateProjectionSurfaceFeaturesNative(
                  updatedTiling,
                  updatedInnerAlpha,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  "channel=private-layer-panel status=projection-surface-features-update-failed " +
                      "source=${activityMarkerToken(source)} " +
                      "error=${activityMarkerToken(throwable.javaClass.simpleName)} " +
                      "message=${activityMarkerToken(throwable.message ?: "none")} " +
                      "${ProjectionSurfaceTilingModule.markerFields(updatedTiling, false, false)} " +
                      "${ProjectionInnerAlphaModule.markerFields(updatedInnerAlpha, false, false)} " +
                      "runtimeCrash=false"
              )
              0L
            }
    val tilingSupported = updateMask and (1L shl 1) != 0L
    val innerAlphaSupported = updateMask and (1L shl 2) != 0L
    val tilingEffective = updateMask and (1L shl 3) != 0L
    val innerAlphaEffective = updateMask and (1L shl 4) != 0L
    bindings.marker(
        "channel=private-layer-panel status=projection-surface-features-submitted " +
            "source=${activityMarkerToken(source)} transport=jni-live-queue updateMask=$updateMask " +
            "previousProjectionSurfaceTilingRequested=${ProjectionSurfaceTilingModule.requested(previousTiling)} " +
            "previousProjectionInnerAlphaRequested=${ProjectionInnerAlphaModule.requested(previousInnerAlpha)} " +
            "${ProjectionSurfaceTilingModule.markerFields(updatedTiling, tilingSupported, tilingEffective)} " +
            "${ProjectionInnerAlphaModule.markerFields(updatedInnerAlpha, innerAlphaSupported, innerAlphaEffective)} " +
            "runtimeCrash=false"
    )
    return updatedTiling to updatedInnerAlpha
  }

  companion object {
    const val MODULE_ID = "spatial-private-layer-control-coordinator"
  }
}
