package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Handler
import android.os.Looper

internal data class SpatialSurfaceParticleLifecycleDiagnosticSnapshot(
    val panelRegistrationCount: Int,
    val panelMode: String,
    val workflowPanelVisible: Boolean,
    val launcherPanelVisible: Boolean,
    val legacyLauncherPanelSuppressed: Boolean,
    val particleLayerEntityCreated: Boolean,
    val particleSurfacePanelReady: Boolean,
    val particleSurfaceConsumerCalled: Boolean,
    val particleSurfaceConsumerSurfaceValid: Boolean,
    val nativeSurfaceParticleLayerEnabled: Boolean,
    val particleLayerStarted: Boolean,
    val nativeSurfaceStartRequested: Boolean,
    val lastNativeSurfaceStartMask: Long,
    val nativeReceiptLibraryLoaded: Boolean,
    val nativeReceiptLibraryError: String,
    val openXrInstanceHandleNonZero: Boolean,
    val openXrSessionHandleNonZero: Boolean,
    val openXrGetInstanceProcAddrHandleNonZero: Boolean,
    val currentDriverProfileId: String,
    val currentProfileId: String,
    val placementMarkerFields: String,
    val stereoMarkerFields: String,
)

internal data class SpatialSurfaceParticleLifecycleDiagnosticsBindings(
    val featureEnabled: () -> Boolean,
    val activityMarkersFile: String,
    val snapshot: () -> SpatialSurfaceParticleLifecycleDiagnosticSnapshot,
    val marker: (String) -> Unit,
)

internal class SpatialSurfaceParticleLifecycleDiagnosticsCoordinator(
    private val bindings: SpatialSurfaceParticleLifecycleDiagnosticsBindings,
) {
  fun schedule(reason: String, explicitRequest: Boolean = false): Boolean {
    if (!enabled(explicitRequest)) return false
    val mainHandler = Handler(Looper.getMainLooper())
    DIAGNOSTIC_DELAYS_MS.forEach { delayMs ->
      mainHandler.postDelayed(
          { log("$reason-$delayMs", explicitRequest = explicitRequest) },
          delayMs,
      )
    }
    return true
  }

  fun log(phase: String, explicitRequest: Boolean = false): Boolean {
    if (!enabled(explicitRequest)) return false
    val snapshot = bindings.snapshot()
    bindings.marker(
        SpatialSurfaceParticleRouteModule.nativeSurfaceParticleLifecycleCheckMarker(
            phase = phase,
            activityMarkersFile = bindings.activityMarkersFile,
            panelRegistrationCount = snapshot.panelRegistrationCount,
            panelMode = snapshot.panelMode,
            workflowPanelVisible = snapshot.workflowPanelVisible,
            launcherPanelVisible = snapshot.launcherPanelVisible,
            legacyLauncherPanelSuppressed = snapshot.legacyLauncherPanelSuppressed,
            particleLayerEntityCreated = snapshot.particleLayerEntityCreated,
            particleSurfacePanelReady = snapshot.particleSurfacePanelReady,
            particleSurfaceConsumerCalled = snapshot.particleSurfaceConsumerCalled,
            particleSurfaceConsumerSurfaceValid = snapshot.particleSurfaceConsumerSurfaceValid,
            nativeSurfaceParticleLayerEnabled = snapshot.nativeSurfaceParticleLayerEnabled,
            particleLayerStarted = snapshot.particleLayerStarted,
            nativeSurfaceStartRequested = snapshot.nativeSurfaceStartRequested,
            lastNativeSurfaceStartMask = snapshot.lastNativeSurfaceStartMask,
            nativeReceiptLibraryLoaded = snapshot.nativeReceiptLibraryLoaded,
            nativeReceiptLibraryError = snapshot.nativeReceiptLibraryError,
            openXrInstanceHandleNonZero = snapshot.openXrInstanceHandleNonZero,
            openXrSessionHandleNonZero = snapshot.openXrSessionHandleNonZero,
            openXrGetInstanceProcAddrHandleNonZero =
                snapshot.openXrGetInstanceProcAddrHandleNonZero,
            currentDriverProfileId = snapshot.currentDriverProfileId,
            currentProfileId = snapshot.currentProfileId,
            placementMarkerFields = snapshot.placementMarkerFields,
            stereoMarkerFields = snapshot.stereoMarkerFields,
        )
    )
    return true
  }

  private fun enabled(explicitRequest: Boolean): Boolean =
      explicitRequest || bindings.featureEnabled()

  companion object {
    const val MODULE_ID = "spatial-surface-particle-lifecycle-diagnostics-coordinator"

    private val DIAGNOSTIC_DELAYS_MS = longArrayOf(750L, 2500L, 6500L, 14000L)
  }
}
