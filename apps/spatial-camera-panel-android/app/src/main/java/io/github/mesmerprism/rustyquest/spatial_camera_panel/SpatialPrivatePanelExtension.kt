package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import androidx.compose.runtime.Composable

internal data class SpatialPrivatePanelExtensionHost(
    val context: Context,
    val elapsedRealtimeMs: () -> Long,
    val wallClockNowMs: () -> Long,
    val profileLibrary: () -> SpatialCameraPanelProfileLibrarySnapshot,
    val applyProfile:
        (SpatialCameraPanelProfileEntry, String) -> SpatialCameraPanelControlSnapshot,
    val captureControls: () -> SpatialCameraPanelControlSnapshot,
    val applyControls:
        (SpatialCameraPanelControlSnapshot, String) -> SpatialCameraPanelControlSnapshot,
    val recenterVideo: (String, String) -> Boolean,
    val notifyLaunchOptionsChanged: () -> Unit,
    val marker: (String) -> Unit,
)

internal enum class SpatialPrivatePanelLockedInputAction {
  RightPrimary,
  Previous,
  Next,
}

internal data class SpatialPrivatePanelLockedInput(
    val action: SpatialPrivatePanelLockedInputAction,
    val source: String,
    val detail: String,
)

internal data class SpatialPrivatePanelLaunchResult(
    val status: String,
    val inputLocked: Boolean,
    val optionAccepted: Boolean = false,
)

internal interface SpatialPrivatePanelExtension {
  val pageTitle: String
  val pageSubtitle: String
  fun homeSummary(): String
  fun inputLocked(): Boolean
  fun lockedInputEnabled(): Boolean = false
  fun handleLockedInput(input: SpatialPrivatePanelLockedInput): Boolean = false
  fun connectionHubSurfaceTarget(): ConnectionHubSurfaceTarget? = null
  fun handleLaunchOption(optionPresent: Boolean, optionId: String?, source: String):
      SpatialPrivatePanelLaunchResult
  fun tick(sceneReady: Boolean)
  fun referencesProfile(profileId: String): Boolean
  fun shutdown()

  @Composable
  fun PanelContent(
      profileLibrary: () -> SpatialCameraPanelProfileLibrarySnapshot,
      onControlsApplied: (SpatialCameraPanelControlSnapshot) -> Unit,
  )
}

internal object SpatialPrivatePanelExtensionLoader {
  private const val REGISTRY_CLASS =
      "io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialPrivateFeatureRegistry"

  fun load(
      host: SpatialPrivatePanelExtensionHost,
  ): SpatialPrivatePanelExtension? =
      runCatching {
            val registry = Class.forName(REGISTRY_CLASS)
            val create =
                registry.getMethod("createPanelExtension", Any::class.java)
            create.invoke(null, host) as? SpatialPrivatePanelExtension
          }
          .onFailure {
            host.marker(
                "channel=spatial-private-panel-extension status=not-present " +
                    "extensionLoaded=false"
            )
          }
          .getOrNull()

  fun launchOptions(context: Context): List<SpatialAppLaunchOption> =
      runCatching {
            val registry = Class.forName(REGISTRY_CLASS)
            val query = registry.getMethod("launchOptions", Context::class.java)
            val raw = query.invoke(null, context.applicationContext) as? List<*> ?: emptyList<Any>()
            SpatialAppLaunchOptionsContract.validate(raw.filterIsInstance<SpatialAppLaunchOption>())
          }
          .getOrDefault(emptyList())
}

/** Resolves the app-owned target without reflecting a second private singleton. */
internal object SpatialConnectionHubSurfaceTargetLoader {
  fun load(
      marker: (String) -> Unit,
      extension: SpatialPrivatePanelExtension? = null,
  ): ConnectionHubSurfaceTarget? =
      extension?.connectionHubSurfaceTarget().also { target ->
        marker(
            "channel=spatial-connection-hub-target status=" +
                if (target == null) "not-present" else "loaded"
        )
      }
}
