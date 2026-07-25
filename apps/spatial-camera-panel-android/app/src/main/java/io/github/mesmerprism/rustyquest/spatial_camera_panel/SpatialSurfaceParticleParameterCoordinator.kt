package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialSurfaceParticleParameterBindings(
    val featureEnabled: () -> Boolean,
    val receiptLibraryLoaded: () -> Boolean,
    val submitNativeParameters: (SurfaceParticleControlState) -> Long,
    val resolveNativeAlias: (String, Float, String) -> Long,
    val marker: (String) -> Unit,
)

internal class SpatialSurfaceParticleParameterCoordinator(
    private val bindings: SpatialSurfaceParticleParameterBindings,
) {
  var controls = SurfaceParticleControlState()
    private set

  fun updateControls(
      driver0Value01: Float,
      driver1Value01: Float,
      pointScale: Float,
      source: String = "panel",
  ): SurfaceParticleControlState =
      updateControls(
          controls.copy(
              driver0Value01 = driver0Value01,
              driver1Value01 = driver1Value01,
              pointScale = pointScale,
          ),
          source,
      )

  fun updateControls(
      updatedControls: SurfaceParticleControlState,
      source: String = "panel",
  ): SurfaceParticleControlState {
    controls =
        updatedControls.copy(
            driver0Value01 = updatedControls.driver0Value01.coerceIn(0.0f, 1.0f),
            driver1Value01 = updatedControls.driver1Value01.coerceIn(0.0f, 1.0f),
            driver2Value01 = updatedControls.driver2Value01.coerceIn(0.0f, 1.0f),
            driver3Value01 = updatedControls.driver3Value01.coerceIn(0.0f, 1.0f),
            driver4Value01 = updatedControls.driver4Value01.coerceIn(0.0f, 1.0f),
            driver5Value01 = updatedControls.driver5Value01.coerceIn(0.0f, 1.0f),
            driver6Value01 = updatedControls.driver6Value01.coerceIn(0.0f, 1.0f),
            driver7Value01 = updatedControls.driver7Value01.coerceIn(0.0f, 1.0f),
            pointScale = updatedControls.pointScale.coerceIn(0.35f, 2.25f),
            tracerDrawSlotsPerOscillator =
                updatedControls.tracerDrawSlotsPerOscillator.coerceIn(0.0f, 7.0f),
            tracerLifetimeSeconds = updatedControls.tracerLifetimeSeconds.coerceIn(0.0f, 0.5f),
            tracerCopiesPerSecond = updatedControls.tracerCopiesPerSecond.coerceIn(0.0f, 14.0f),
            transparencyOpacity = updatedControls.transparencyOpacity.coerceIn(0.0f, 1.0f),
            projectionWorldScale = updatedControls.projectionWorldScale.coerceIn(0.5f, 2.0f),
        )
    submit(source)
    return controls
  }

  fun submit(source: String) {
    if (!bindings.featureEnabled()) {
      bindings.marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleEffectSuppressedMarker(
              "parameter-submit",
              source,
          )
      )
      return
    }
    if (!bindings.receiptLibraryLoaded()) {
      bindings.marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleParameterSubmitSkippedMarker(
              source
          )
      )
      return
    }
    runCatching { bindings.submitNativeParameters(controls) }
        .onSuccess { mask ->
          bindings.marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleParametersSubmittedMarker(
                  source,
                  mask,
                  controls,
              )
          )
        }
        .onFailure { throwable ->
          bindings.marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleParameterSubmitFailedMarker(
                  source,
                  throwable.javaClass.simpleName,
              )
          )
        }
  }

  fun resolveAlias(
      source: String,
      parameterId: String,
      requestedValue: Float,
      activationProfile: String,
  ) {
    if (!bindings.featureEnabled()) {
      bindings.marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleEffectSuppressedMarker(
              "alias-parameter-submit",
              source,
          )
      )
      return
    }
    if (!bindings.receiptLibraryLoaded()) {
      bindings.marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleAliasSubmitSkippedMarker(
              source,
              parameterId,
              activationProfile,
          )
      )
      return
    }
    runCatching { bindings.resolveNativeAlias(parameterId, requestedValue, activationProfile) }
        .onSuccess { mask ->
          bindings.marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleAliasSubmittedMarker(
                  source,
                  parameterId,
                  activationProfile,
                  requestedValue,
                  mask,
              )
          )
        }
        .onFailure { throwable ->
          bindings.marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleAliasSubmitFailedMarker(
                  source,
                  parameterId,
                  throwable.javaClass.simpleName,
              )
          )
        }
  }

  companion object {
    const val MODULE_ID = "spatial-surface-particle-parameter-coordinator"
  }
}
