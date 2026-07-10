package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Intent
import android.os.Handler
import android.os.Looper

internal data class SpatialValidationWorkflowBindings(
    val store: () -> SpatialCameraPanelStore,
    val marker: (String) -> Unit,
    val scheduleParticleLayerLifecycleDiagnostics: (String) -> Unit,
    val logParticleLayerLifecycleStatus: (String) -> Unit,
    val setWorkflowPanelVisible: (Boolean, Boolean, String) -> Unit,
    val setPrivateLayerPanelVisible: (Boolean, Boolean, String) -> Unit,
    val resetWorkflowPanelPlacement: () -> Unit,
    val setPanelHeadlocked: (Boolean, String) -> Unit,
    val panelHeadlocked: () -> Boolean,
    val adjustPanelPlacement: (Float, Float, Float, Float) -> Unit,
    val resizeWorkflowPanel: (Float, Float) -> Unit,
    val currentParticleControls: () -> SurfaceParticleControlState,
    val updateSurfaceParticleControls: (SurfaceParticleControlState, String) -> Unit,
    val applyRemoteParticleLayerTargetDistance: (Intent, String) -> Unit,
    val applyRemoteParticleLayerViewYaw: (Intent, String) -> Unit,
    val recenterSurfaceParticleSphere: (String, String) -> Unit,
    val resolveSurfaceParticleAliasControl: (Intent, String) -> Unit,
    val applyDriverProfileToParticleControls: (ActiveBlockSnapshot, String) -> Unit,
    val setQuestionnaireDueReopensPanel: (Boolean, String) -> Unit,
    val panelStateToken: () -> String,
    val workflowPanelVisible: () -> Boolean,
    val ensurePolarSensorPanel: () -> PolarSensorPanel,
    val logError: (String, Throwable) -> Unit,
)

internal class SpatialValidationWorkflowCoordinator(
    private val bindings: SpatialValidationWorkflowBindings,
) {
  fun dispatchIfRequested(intent: Intent?): Boolean =
      when (intent?.action) {
        ACTION_RUN_WORKFLOW_SELF_TEST -> {
          runWorkflowSelfTest(intent)
          true
        }
        ACTION_RUN_POLAR_LIVE_VALIDATION -> {
          runPolarLiveValidation(intent)
          true
        }
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

  private fun runWorkflowSelfTest(intent: Intent) {
    val participantId =
        intent.getStringExtra(EXTRA_PARTICIPANT_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: SpatialValidationCommandModule.DEFAULT_SELF_TEST_PARTICIPANT_ID
    val surfaceTargetId =
        intent.getStringExtra(EXTRA_SURFACE_TARGET_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: SpatialValidationCommandModule.DEFAULT_SURFACE_TARGET_ID

    bindings.marker(
        SpatialValidationCommandModule.selfTestStartMarker(participantId, surfaceTargetId)
    )
    bindings.scheduleParticleLayerLifecycleDiagnostics("self-test-start")
    try {
      val store = bindings.store()
      store.resetForNewParticipant()
      store.beginParticipant(participantId)
      store.savePolarSetup(
          runLabel = "headset-self-test",
          operatorId = "codex",
          notes = "Meta Spatial SDK validation intent",
      )
      store.selectSurface(surfaceTargetId)
      store.prioritizeConditionForValidation(VALIDATION_DRIVER_PROFILE_ID)
      bindings.setWorkflowPanelVisible(false, false, "self-test-particle-view")
      val block = store.startNextBlock()
      if (block != null) {
        bindings.applyDriverProfileToParticleControls(block, "self-test-driver-profile-start")
      }
      mainHandler()
          .postDelayed(
              { bindings.setWorkflowPanelVisible(true, true, "self-test-workflow-panel") },
              1500L,
          )
      bindings.marker(
          SpatialValidationCommandModule.selfTestBlockStartedMarker(
              participantId,
              surfaceTargetId,
              VALIDATION_DRIVER_PROFILE_ID,
          )
      )
      mainHandler()
          .postDelayed(
              {
                try {
                  bindings.logParticleLayerLifecycleStatus("self-test-before-questionnaire")
                  store.syncElapsedBlock()
                  store.submitQuestionnaire(
                      comfortRating = 4,
                      intensityRating = 4,
                      engagementRating = 4,
                      notes = "Codex headset validation self-test",
                      signature = emptySignatureJson(),
                  )
                  bindings.marker(
                      SpatialValidationCommandModule.selfTestCompleteMarker(
                          participantId,
                          surfaceTargetId,
                          VALIDATION_DRIVER_PROFILE_ID,
                      )
                  )
                } catch (throwable: Throwable) {
                  bindings.marker(
                      SpatialValidationCommandModule.selfTestFailedMarker(
                          SpatialValidationCommandModule.throwableErrorToken(throwable)
                      )
                  )
                  bindings.logError(VALIDATION_WORKFLOW_FAILURE_MESSAGE, throwable)
                }
              },
              SpatialCameraPanelStore.DEFAULT_BLOCK_DURATION_MS + 750L,
          )
    } catch (throwable: Throwable) {
      bindings.marker(
          SpatialValidationCommandModule.selfTestFailedMarker(
              SpatialValidationCommandModule.throwableErrorToken(throwable)
          )
      )
      bindings.logError(VALIDATION_WORKFLOW_FAILURE_MESSAGE, throwable)
    }
  }

  private fun runUiCommand(intent: Intent) {
    val uiAction =
        intent.getStringExtra(EXTRA_UI_ACTION)?.trim()?.takeIf { it.isNotBlank() }
            ?: "panel-open"
    val source = SpatialValidationCommandModule.remoteUiCommandSource(uiAction)
    bindings.marker(SpatialValidationCommandModule.uiCommandStartMarker(uiAction))
    try {
      val store = bindings.store()
      when (uiAction) {
        "panel-open" -> bindings.setWorkflowPanelVisible(true, true, source)
        "panel-close" -> bindings.setWorkflowPanelVisible(false, false, source)
        "private-layer-panel-open" -> bindings.setPrivateLayerPanelVisible(true, true, source)
        "private-layer-panel-close" -> bindings.setPrivateLayerPanelVisible(false, false, source)
        "panel-reset" -> bindings.resetWorkflowPanelPlacement()
        "panel-headlock-on" -> bindings.setPanelHeadlocked(true, source)
        "panel-headlock-off" -> bindings.setPanelHeadlocked(false, source)
        "panel-headlock-toggle" -> bindings.setPanelHeadlocked(!bindings.panelHeadlocked(), source)
        "panel-adjust" ->
            bindings.adjustPanelPlacement(
                intent.getFloatExtra(EXTRA_DELTA_X, 0.0f),
                intent.getFloatExtra(EXTRA_DELTA_Y, 0.0f),
                intent.getFloatExtra(EXTRA_DELTA_Z, 0.0f),
                intent.getFloatExtra(EXTRA_DELTA_SCALE, 0.0f),
            )
        "panel-resize" ->
            bindings.resizeWorkflowPanel(
                intent.getFloatExtra(EXTRA_DELTA_WIDTH, 0.0f),
                intent.getFloatExtra(EXTRA_DELTA_HEIGHT, 0.0f),
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
        "participant-reset" -> {
          store.resetForNewParticipant()
          bindings.setWorkflowPanelVisible(true, true, source)
        }
        "participant-begin" -> {
          store.beginParticipant(remoteParticipantId(intent))
          bindings.setWorkflowPanelVisible(true, true, source)
        }
        "polar-setup-save" -> {
          ensureRemoteParticipant(intent, source)
          store.savePolarSetup(
              runLabel = intent.getStringExtra(EXTRA_RUN_LABEL) ?: "remote-ui-command",
              operatorId = intent.getStringExtra(EXTRA_OPERATOR_ID) ?: "codex",
              notes = intent.getStringExtra(EXTRA_NOTES) ?: "Remote UI command",
          )
          bindings.setWorkflowPanelVisible(true, true, source)
        }
        "surface-select" -> {
          ensureRemoteParticipantAndPolarSetup(intent, source)
          store.selectSurface(remoteSurfaceTargetId(intent))
          bindings.setWorkflowPanelVisible(true, true, source)
        }
        "start-block" -> startRemoteSurfaceBlock(intent, source, resetSession = false)
        "surface-target-activate" -> startRemoteSurfaceBlock(intent, source, resetSession = true)
        "questionnaire-submit" -> {
          store.submitQuestionnaire(
              comfortRating = intent.getIntExtra(EXTRA_COMFORT_RATING, 4),
              intensityRating = intent.getIntExtra(EXTRA_INTENSITY_RATING, 4),
              engagementRating = intent.getIntExtra(EXTRA_ENGAGEMENT_RATING, 4),
              notes = intent.getStringExtra(EXTRA_NOTES) ?: "Remote UI command questionnaire",
              signature = emptySignatureJson(),
          )
          bindings.setWorkflowPanelVisible(true, true, source)
        }
        else -> error("unknown_ui_action_$uiAction")
      }
      bindings.marker(
          SpatialValidationCommandModule.uiCommandCompleteMarker(
              uiAction,
              bindings.panelStateToken(),
              bindings.workflowPanelVisible(),
              store.snapshot().surfaceTargetId,
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
    val participantId =
        intent.getStringExtra(EXTRA_PARTICIPANT_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: SpatialValidationCommandModule.DEFAULT_SURFACE_TARGET_PARTICIPANT_ID
    val surfaceTargetId =
        intent.getStringExtra(EXTRA_SURFACE_TARGET_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: SpatialValidationCommandModule.DEFAULT_SURFACE_TARGET_ID

    try {
      startRemoteSurfaceBlock(intent, "surface-target-activation", resetSession = true)
      bindings.marker(
          SpatialValidationCommandModule.surfaceTargetActivatedMarker(
              participantId,
              surfaceTargetId,
              VALIDATION_DRIVER_PROFILE_ID,
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

  private fun startRemoteSurfaceBlock(
      intent: Intent,
      source: String,
      resetSession: Boolean,
  ): ActiveBlockSnapshot? {
    bindings.marker(
        SpatialValidationCommandModule.surfaceTargetActivationStartMarker(
            remoteParticipantId(intent),
            remoteSurfaceTargetId(intent),
            source,
        )
    )
    bindings.scheduleParticleLayerLifecycleDiagnostics(source)
    val store = bindings.store()
    if (resetSession) {
      store.resetForNewParticipant()
    }
    ensureRemoteParticipantAndPolarSetup(intent, source)
    store.selectSurface(remoteSurfaceTargetId(intent))
    store.prioritizeConditionForValidation(VALIDATION_DRIVER_PROFILE_ID)
    bindings.setQuestionnaireDueReopensPanel(false, source)
    bindings.setWorkflowPanelVisible(false, false, "$source-particle-view")
    val block = store.startNextBlock()
    if (block != null) {
      bindings.applyDriverProfileToParticleControls(block, "$source-block-start")
    }
    return block
  }

  private fun ensureRemoteParticipantAndPolarSetup(intent: Intent, source: String) {
    ensureRemoteParticipant(intent, source)
    val store = bindings.store()
    if (store.snapshot().stage == "polar_setup") {
      store.savePolarSetup(
          runLabel = intent.getStringExtra(EXTRA_RUN_LABEL) ?: source,
          operatorId = intent.getStringExtra(EXTRA_OPERATOR_ID) ?: "codex",
          notes = intent.getStringExtra(EXTRA_NOTES) ?: "Remote UI command",
      )
    }
  }

  private fun ensureRemoteParticipant(intent: Intent, source: String) {
    val store = bindings.store()
    val snapshot = store.snapshot()
    if (snapshot.sessionId.isBlank() || snapshot.stage == "participant") {
      val participantId = remoteParticipantId(intent)
      store.beginParticipant(participantId)
      bindings.marker(
          SpatialValidationCommandModule.remoteParticipantCreatedMarker(source, participantId)
      )
    }
  }

  private fun remoteParticipantId(intent: Intent): String =
      intent.getStringExtra(EXTRA_PARTICIPANT_ID)?.trim()?.takeIf { it.isNotBlank() }
          ?: SpatialValidationCommandModule.DEFAULT_UI_COMMAND_PARTICIPANT_ID

  private fun remoteSurfaceTargetId(intent: Intent): String =
      intent.getStringExtra(EXTRA_SURFACE_TARGET_ID)?.trim()?.takeIf { it.isNotBlank() }
          ?: SpatialValidationCommandModule.DEFAULT_SURFACE_TARGET_ID

  private fun runPolarLiveValidation(intent: Intent) {
    val participantId =
        intent.getStringExtra(EXTRA_PARTICIPANT_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: SpatialValidationCommandModule.DEFAULT_POLAR_LIVE_PARTICIPANT_ID
    val surfaceTargetId =
        intent.getStringExtra(EXTRA_SURFACE_TARGET_ID)?.trim()?.takeIf { it.isNotBlank() }
            ?: SpatialValidationCommandModule.DEFAULT_SURFACE_TARGET_ID
    val scanDelayMs =
        intent.getIntExtra(EXTRA_POLAR_SCAN_SECONDS, 16).coerceIn(3, 60) * 1000L
    val connectDelayMs =
        intent.getIntExtra(EXTRA_POLAR_CONNECT_DELAY_SECONDS, 10).coerceIn(3, 60) * 1000L
    val ecgRunMs = intent.getIntExtra(EXTRA_POLAR_ECG_SECONDS, 14).coerceIn(3, 180) * 1000L
    val mainHandler = mainHandler()

    bindings.marker(
        SpatialValidationCommandModule.polarLiveStartMarker(
            participantId,
            surfaceTargetId,
            scanDelayMs / 1000L,
            connectDelayMs / 1000L,
            ecgRunMs / 1000L,
        )
    )
    bindings.scheduleParticleLayerLifecycleDiagnostics("polar-live-validation-start")
    try {
      val store = bindings.store()
      store.resetForNewParticipant()
      store.beginParticipant(participantId)
      store.savePolarSetup(
          runLabel = "polar-live-validation",
          operatorId = "codex",
          notes = "Meta Spatial SDK Polar H10 live validation intent",
      )
      store.selectSurface(surfaceTargetId)
      bindings.setWorkflowPanelVisible(true, true, "polar-live-validation")
      val panel = bindings.ensurePolarSensorPanel()
      panel.buildView()
      bindings.marker(SpatialValidationCommandModule.polarPanelAutomationReadyMarker(participantId))
      panel.handleCommand("select_ecg")
      panel.handleCommand("scan")
      bindings.marker(SpatialValidationCommandModule.polarScanCommandIssuedMarker(participantId))
      mainHandler.postDelayed(
          {
            bindings.marker(
                SpatialValidationCommandModule.polarConnectRequestedMarker(
                    panel.discoveredDeviceCount()
                )
            )
            panel.connectBestDiscovered("ecg")
          },
          scanDelayMs,
      )
      mainHandler.postDelayed(
          {
            bindings.marker(
                SpatialValidationCommandModule.polarStartEcgRequestedMarker(
                    panel.discoveredDeviceCount()
                )
            )
            panel.handleCommand("start_ecg")
          },
          scanDelayMs + connectDelayMs,
      )
      mainHandler.postDelayed(
          {
            bindings.marker(
                SpatialValidationCommandModule.polarCompleteMarker(
                    panel.isEcgReceiving(),
                    panel.discoveredDeviceCount(),
                    panel.ecgExperimentStatusLine(true),
                )
            )
          },
          scanDelayMs + connectDelayMs + ecgRunMs,
      )
    } catch (throwable: Throwable) {
      bindings.marker(
          SpatialValidationCommandModule.polarFailedMarker(
              SpatialValidationCommandModule.throwableErrorToken(throwable)
          )
      )
      bindings.logError(POLAR_LIVE_FAILURE_MESSAGE, throwable)
    }
  }

  private fun mainHandler(): Handler = Handler(Looper.getMainLooper())

  companion object {
    const val MODULE_ID = "spatial-validation-workflow-coordinator"

    const val EXTRA_PARTICLE_ALIAS_PARAMETER_ID = "parameter_id"
    const val EXTRA_PARTICLE_ALIAS_VALUE = "value"
    const val EXTRA_PARTICLE_ALIAS_VISUAL_DRIVER_ACTIVATION_PROFILE =
        "visual_driver_activation_profile"
    const val EXTRA_PARTICLE_LAYER_TARGET_DISTANCE_METERS =
        "particle_layer_target_distance_meters"
    const val EXTRA_PARTICLE_LAYER_VIEW_YAW_DEGREES = "particle_layer_view_yaw_degrees"

    private const val VALIDATION_DRIVER_PROFILE_ID = "profile-b"
    private const val ACTION_RUN_WORKFLOW_SELF_TEST =
        "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_WORKFLOW_SELF_TEST"
    private const val ACTION_RUN_POLAR_LIVE_VALIDATION =
        "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_POLAR_LIVE_VALIDATION"
    private const val ACTION_RUN_UI_COMMAND =
        "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_UI_COMMAND"
    private const val ACTION_RUN_SURFACE_TARGET =
        "io.github.mesmerprism.rustyquest.spatial_camera_panel.action.RUN_SURFACE_TARGET"
    private const val EXTRA_PARTICIPANT_ID = "participant_id"
    private const val EXTRA_SURFACE_TARGET_ID = "surface_target_id"
    private const val EXTRA_UI_ACTION = "ui_action"
    private const val EXTRA_DELTA_X = "delta_x"
    private const val EXTRA_DELTA_Y = "delta_y"
    private const val EXTRA_DELTA_Z = "delta_z"
    private const val EXTRA_DELTA_SCALE = "delta_scale"
    private const val EXTRA_DELTA_WIDTH = "delta_width"
    private const val EXTRA_DELTA_HEIGHT = "delta_height"
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
    private const val EXTRA_RUN_LABEL = "run_label"
    private const val EXTRA_OPERATOR_ID = "operator_id"
    private const val EXTRA_NOTES = "notes"
    private const val EXTRA_COMFORT_RATING = "comfort_rating"
    private const val EXTRA_INTENSITY_RATING = "intensity_rating"
    private const val EXTRA_ENGAGEMENT_RATING = "engagement_rating"
    private const val EXTRA_POLAR_SCAN_SECONDS = "polar_scan_seconds"
    private const val EXTRA_POLAR_CONNECT_DELAY_SECONDS = "polar_connect_delay_seconds"
    private const val EXTRA_POLAR_ECG_SECONDS = "polar_ecg_seconds"

    private const val VALIDATION_WORKFLOW_FAILURE_MESSAGE =
        "Spatial Camera Panel validation workflow failed"
    private const val UI_COMMAND_FAILURE_MESSAGE = "Spatial Camera Panel UI command failed"
    private const val SURFACE_TARGET_FAILURE_MESSAGE =
        "Spatial Camera Panel surface target activation failed"
    private const val POLAR_LIVE_FAILURE_MESSAGE =
        "Spatial Camera Panel Polar live validation failed"
  }
}
