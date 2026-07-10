package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialSurfaceParticleRecenterBindings(
    val featureEnabled: () -> Boolean,
    val surfaceTargetId: () -> String,
    val particleLayerVisible: () -> Boolean,
    val workflowPanelVisible: () -> Boolean,
    val privateLayerPanelVisible: () -> Boolean,
    val receiptLibraryLoaded: () -> Boolean,
    val recenterNative: () -> Long,
    val marker: (String) -> Unit,
)

internal data class SpatialSurfaceParticleRecenterRequest(
    val inputSource: String,
    val detail: String,
    val requireParticleView: Boolean,
)

internal class SpatialSurfaceParticleRecenterCoordinator(
    private val bindings: SpatialSurfaceParticleRecenterBindings,
) {
  fun recenter(request: SpatialSurfaceParticleRecenterRequest): Boolean {
    val featureEnabled = bindings.featureEnabled()
    val surfaceTargetId = bindings.surfaceTargetId()
    val particleViewVisible = bindings.particleLayerVisible()
    if (
        !featureEnabled ||
            surfaceTargetId != "icosphere" ||
            (request.requireParticleView && !particleViewVisible)
    ) {
      bindings.marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleRecenterIgnoredMarker(
              inputSource = request.inputSource,
              detail = request.detail,
              surfaceTargetId = surfaceTargetId,
              particleLayerVisible = particleViewVisible,
              requireParticleView = request.requireParticleView,
              workflowPanelVisible = bindings.workflowPanelVisible(),
              privateLayerPanelVisible = bindings.privateLayerPanelVisible(),
          )
      )
      return false
    }
    if (!bindings.receiptLibraryLoaded()) {
      bindings.marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleRecenterNativeUnavailableMarker(
              inputSource = request.inputSource,
              detail = request.detail,
              surfaceTargetId = surfaceTargetId,
          )
      )
      return true
    }
    return runCatching {
          val mask = bindings.recenterNative()
          val accepted = (mask and SURFACE_PARTICLE_RECENTER_ACCEPTED_BIT) != 0L
          bindings.marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleRecenterRequestedMarker(
                  inputSource = request.inputSource,
                  detail = request.detail,
                  surfaceTargetId = surfaceTargetId,
                  particleLayerVisible = particleViewVisible,
                  requireParticleView = request.requireParticleView,
                  nativeRecenterMask = mask,
                  nativeRecenterAccepted = accepted,
              )
          )
          true
        }
        .getOrElse { throwable ->
          bindings.marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleRecenterFailedMarker(
                  inputSource = request.inputSource,
                  detail = request.detail,
                  surfaceTargetId = surfaceTargetId,
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
          true
        }
  }

  companion object {
    const val MODULE_ID = "spatial-surface-particle-recenter-coordinator"
  }
}
