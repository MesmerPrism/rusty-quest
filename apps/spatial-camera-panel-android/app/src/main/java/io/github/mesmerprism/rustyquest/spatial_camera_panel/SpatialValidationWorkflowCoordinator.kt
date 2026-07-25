package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Intent

internal data class SpatialValidationWorkflowBindings(
    val marker: (String) -> Unit,
    val scheduleParticleLayerLifecycleDiagnostics: (String) -> Unit,
    val setPrivateLayerPanelVisible: (Boolean, Boolean, String) -> Unit,
    val privateLayerPanelVisible: () -> Boolean,
    val updatePrivateLayerOverride: (Float, String) -> Unit,
    val updatePrivateLayerZoneCompositor: (PrivateLayerZoneCompositor, String) -> Unit,
    val setProjectionPanelEnabled: (Boolean, String) -> Unit,
    val changeImmersiveVideo: (String, String?, String) -> Unit,
    val currentParticleControls: () -> SurfaceParticleControlState,
    val updateSurfaceParticleControls: (SurfaceParticleControlState, String) -> Unit,
    val applyRemoteParticleLayerTargetDistance: (Intent, String) -> Unit,
    val applyRemoteParticleLayerViewYaw: (Intent, String) -> Unit,
    val recenterSurfaceParticleSphere: (String, String) -> Unit,
    val resolveSurfaceParticleAliasControl: (Intent, String) -> Unit,
    val selectSurfaceTarget: (String, String) -> String,
    val currentSurfaceTarget: () -> String,
    val panelStateToken: () -> String,
    val logError: (String, Throwable) -> Unit,
)

internal class SpatialValidationWorkflowCoordinator(
    private val bindings: SpatialValidationWorkflowBindings,
) {
  fun dispatchIfRequested(intent: Intent?): Boolean =
      when (intent?.action) {
        ACTION_RUN_UI_COMMAND -> {
          runUiCommand(intent)
          true
        }
        ACTION_RUN_SURFACE_TARGET -> {
          runSurfaceTargetActivation(intent)
          true
        }
        else -> false
      }

  private fun runUiCommand(intent: Intent) {
    val uiAction =
        intent.getStringExtra(EXTRA_UI_ACTION)?.trim()?.takeIf { it.isNotBlank() }
            ?: "private-layer-panel-open"
    val source = SpatialValidationCommandModule.remoteUiCommandSource(uiAction)
    bindings.marker(SpatialValidationCommandModule.uiCommandStartMarker(uiAction))
    try {
      when (uiAction) {
        "panel-open", "private-layer-panel-open" ->
            bindings.setPrivateLayerPanelVisible(true, true, source)
        "panel-close", "private-layer-panel-close" ->
            bindings.setPrivateLayerPanelVisible(false, false, source)
        "private-layer-select" ->
            bindings.updatePrivateLayerOverride(
                intent.getFloatExtra(EXTRA_PRIVATE_LAYER_OVERRIDE, 0.0f),
                source,
            )
        "private-layer-zone-off" ->
            bindings.updatePrivateLayerZoneCompositor(
                PrivateLayerZoneCompositorControls.legacyOff,
                source,
            )
        "private-layer-zone-native-buffer" ->
            bindings.updatePrivateLayerZoneCompositor(
                PrivateLayerZoneCompositorControls.nativeBuffer,
                source,
            )
        "private-layer-zone-linear-buffer" ->
            bindings.updatePrivateLayerZoneCompositor(
                PrivateLayerZoneCompositorControls.linearBuffer,
                source,
            )
        "private-layer-zone-organic-buffer" ->
            bindings.updatePrivateLayerZoneCompositor(
                PrivateLayerZoneCompositorControls.organicBuffer,
                source,
            )
        "private-layer-zone-full-stretch" ->
            bindings.updatePrivateLayerZoneCompositor(
                PrivateLayerZoneCompositorControls.fullStretch,
                source,
            )
        "projection-panel-off" -> bindings.setProjectionPanelEnabled(false, source)
        "projection-panel-on" -> bindings.setProjectionPanelEnabled(true, source)
        "video-previous" -> bindings.changeImmersiveVideo("previous", null, source)
        "video-next" -> bindings.changeImmersiveVideo("next", null, source)
        "video-select" ->
            bindings.changeImmersiveVideo(
                "select",
                intent.getStringExtra(EXTRA_IMMERSIVE_VIDEO_PACK_ID),
                source,
            )
        "particle-controls" -> updateParticleControls(intent, source)
        "particle-panel-distance" ->
            bindings.applyRemoteParticleLayerTargetDistance(intent, source)
        "particle-panel-view-yaw" -> bindings.applyRemoteParticleLayerViewYaw(intent, source)
        "particle-recenter" ->
            bindings.recenterSurfaceParticleSphere(
                source,
                "remoteUiAction=particle-recenter controllerInputRequired=false",
            )
        "particle-alias-control" -> bindings.resolveSurfaceParticleAliasControl(intent, source)
        "surface-target-activate" -> selectSurfaceTarget(intent, source)
        else -> error("unknown_ui_action_$uiAction")
      }
      bindings.marker(
          SpatialValidationCommandModule.uiCommandCompleteMarker(
              uiAction,
              bindings.panelStateToken(),
              bindings.privateLayerPanelVisible(),
              bindings.currentSurfaceTarget(),
          )
      )
    } catch (throwable: Throwable) {
      bindings.marker(
          SpatialValidationCommandModule.uiCommandFailedMarker(
              uiAction,
              SpatialValidationCommandModule.throwableErrorToken(throwable),
          )
      )
      bindings.logError(UI_COMMAND_FAILURE_MESSAGE, throwable)
    }
  }

  private fun updateParticleControls(intent: Intent, source: String) {
    val current = bindings.currentParticleControls()
    bindings.updateSurfaceParticleControls(
        current.copy(
            driver0Value01 = intent.getFloatExtra(EXTRA_DRIVER0, current.driver0Value01),
            driver1Value01 = intent.getFloatExtra(EXTRA_DRIVER1, current.driver1Value01),
            driver2Value01 = intent.getFloatExtra(EXTRA_DRIVER2, current.driver2Value01),
            driver3Value01 = intent.getFloatExtra(EXTRA_DRIVER3, current.driver3Value01),
            driver4Value01 = intent.getFloatExtra(EXTRA_DRIVER4, current.driver4Value01),
            driver5Value01 = intent.getFloatExtra(EXTRA_DRIVER5, current.driver5Value01),
            driver6Value01 = intent.getFloatExtra(EXTRA_DRIVER6, current.driver6Value01),
            driver7Value01 = intent.getFloatExtra(EXTRA_DRIVER7, current.driver7Value01),
            pointScale = intent.getFloatExtra(EXTRA_POINT_SCALE, current.pointScale),
            tracerDrawSlotsPerOscillator =
                intent.getFloatExtra(
                    EXTRA_TRACER_DRAW_SLOTS,
                    current.tracerDrawSlotsPerOscillator,
                ),
            tracerLifetimeSeconds =
                intent.getFloatExtra(EXTRA_TRACER_LIFETIME_SECONDS, current.tracerLifetimeSeconds),
            tracerCopiesPerSecond =
                intent.getFloatExtra(
                    EXTRA_TRACER_COPIES_PER_SECOND,
                    current.tracerCopiesPerSecond,
                ),
            transparencyOpacity =
                intent.getFloatExtra(EXTRA_TRANSPARENCY_OPACITY, current.transparencyOpacity),
            projectionWorldScale =
                intent.getFloatExtra(EXTRA_PROJECTION_WORLD_SCALE, current.projectionWorldScale),
        ),
        source,
    )
  }

  private fun runSurfaceTargetActivation(intent: Intent) {
    val surfaceTargetId = requestedSurfaceTarget(intent)
    try {
      selectSurfaceTarget(intent, "surface-target-activation")
      bindings.marker(
          SpatialValidationCommandModule.surfaceTargetActivatedMarker(
              surfaceTargetId,
              bindings.panelStateToken(),
          )
      )
    } catch (throwable: Throwable) {
      bindings.marker(
          SpatialValidationCommandModule.surfaceTargetActivationFailedMarker(
              surfaceTargetId,
              SpatialValidationCommandModule.throwableErrorToken(throwable),
          )
      )
      bindings.logError(SURFACE_TARGET_FAILURE_MESSAGE, throwable)
    }
  }

  private fun selectSurfaceTarget(intent: Intent, source: String): String {
    val surfaceTargetId = requestedSurfaceTarget(intent)
    bindings.marker(
        SpatialValidationCommandModule.surfaceTargetActivationStartMarker(
            surfaceTargetId,
            source,
        )
    )
    bindings.scheduleParticleLayerLifecycleDiagnostics(source)
    return bindings.selectSurfaceTarget(surfaceTargetId, source)
  }

  private fun requestedSurfaceTarget(intent: Intent): String =
      intent.getStringExtra(EXTRA_SURFACE_TARGET_ID)?.trim()?.takeIf { it.isNotBlank() }
          ?: SpatialValidationCommandModule.DEFAULT_SURFACE_TARGET_ID

  companion object {
    const val MODULE_ID = "spatial-validation-workflow-coordinator"

    const val EXTRA_PARTICLE_ALIAS_PARAMETER_ID = "parameter_id"
    const val EXTRA_PARTICLE_ALIAS_VALUE = "value"
    const val EXTRA_PARTICLE_ALIAS_VISUAL_DRIVER_ACTIVATION_PROFILE =
        "visual_driver_activation_profile"
    const val EXTRA_PARTICLE_LAYER_TARGET_DISTANCE_METERS =
        "particle_layer_target_distance_meters"
    const val EXTRA_PARTICLE_LAYER_VIEW_YAW_DEGREES = "particle_layer_view_yaw_degrees"

    private const val ACTION_RUN_UI_COMMAND =
        "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_UI_COMMAND"
    private const val ACTION_RUN_SURFACE_TARGET =
        "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_SURFACE_TARGET"
    private const val EXTRA_SURFACE_TARGET_ID = "surface_target_id"
    private const val EXTRA_UI_ACTION = "ui_action"
    private const val EXTRA_PRIVATE_LAYER_OVERRIDE = "private_layer_override"
    private const val EXTRA_IMMERSIVE_VIDEO_PACK_ID = "video_pack_id"
    private const val EXTRA_DRIVER0 = "driver0"
    private const val EXTRA_DRIVER1 = "driver1"
    private const val EXTRA_DRIVER2 = "driver2"
    private const val EXTRA_DRIVER3 = "driver3"
    private const val EXTRA_DRIVER4 = "driver4"
    private const val EXTRA_DRIVER5 = "driver5"
    private const val EXTRA_DRIVER6 = "driver6"
    private const val EXTRA_DRIVER7 = "driver7"
    private const val EXTRA_POINT_SCALE = "point_scale"
    private const val EXTRA_TRACER_DRAW_SLOTS = "tracer_draw_slots_per_oscillator"
    private const val EXTRA_TRACER_LIFETIME_SECONDS = "tracer_lifetime_seconds"
    private const val EXTRA_TRACER_COPIES_PER_SECOND = "tracer_copies_per_second"
    private const val EXTRA_TRANSPARENCY_OPACITY = "transparency_opacity"
    private const val EXTRA_PROJECTION_WORLD_SCALE = "projection_world_scale"

    private const val UI_COMMAND_FAILURE_MESSAGE = "Spatial layer control command failed"
    private const val SURFACE_TARGET_FAILURE_MESSAGE =
        "Spatial surface target activation failed"
  }
}
