package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.runtime.PanelSceneObject

internal data class SpatialSurfaceParticlePresentationSnapshot(
    val panelRegistrationCount: Int,
    val panelReady: Boolean,
    val surfaceConsumerCalled: Boolean,
    val surfaceConsumerSurfaceValid: Boolean,
)

internal data class SpatialSurfaceParticlePresentationStateBindings(
    val marker: (String) -> Unit,
)

internal class SpatialSurfaceParticlePresentationStateCoordinator(
    private val bindings: SpatialSurfaceParticlePresentationStateBindings,
) {
  var panelSceneObject: PanelSceneObject? = null
    private set

  private var panelRegistrationCount = 0
  private var panelReady = false
  private var surfaceConsumerCalled = false
  private var surfaceConsumerSurfaceValid = false

  fun recordPanelRegistrations(
      registrationCount: Int,
      particlePanelRegistrationId: String,
      carrier: String,
      nativeSurfaceParticleLayerEnabled: Boolean,
  ) {
    panelRegistrationCount = registrationCount
    bindings.marker(
        SpatialSurfaceParticleRouteModule.nativeSurfaceParticlePanelRegistrationsCreatedMarker(
            panelRegistrationCount = panelRegistrationCount,
            particlePanelRegistrationId = particlePanelRegistrationId,
            carrier = carrier,
            nativeSurfaceParticleLayerEnabled = nativeSurfaceParticleLayerEnabled,
        )
    )
  }

  fun recordSurfaceConsumer(surfaceValid: Boolean) {
    surfaceConsumerCalled = true
    surfaceConsumerSurfaceValid = surfaceValid
  }

  fun adoptPanel(panel: PanelSceneObject) {
    panelSceneObject = panel
    panelReady = true
  }

  fun adoptManualCarrier(
      panel: PanelSceneObject,
      surfaceValid: Boolean,
  ) {
    adoptPanel(panel)
    recordSurfaceConsumer(surfaceValid)
  }

  fun snapshot(): SpatialSurfaceParticlePresentationSnapshot =
      SpatialSurfaceParticlePresentationSnapshot(
          panelRegistrationCount = panelRegistrationCount,
          panelReady = panelReady,
          surfaceConsumerCalled = surfaceConsumerCalled,
          surfaceConsumerSurfaceValid = surfaceConsumerSurfaceValid,
      )

  companion object {
    const val MODULE_ID = "spatial-surface-particle-presentation-state-coordinator"
  }
}
