package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.Locale

internal const val SPATIAL_FRAGMENT_PROBE_ENABLED_PROPERTY =
    "debug.rustyquest.spatial.fragment_probe.enabled"
internal const val SPATIAL_FRAGMENT_PROBE_MODE_PROPERTY =
    "debug.rustyquest.spatial.fragment_probe.mode"
internal const val SPATIAL_FRAGMENT_PROBE_DEPTH_PROPERTY =
    "debug.rustyquest.spatial.fragment_probe.fragment_depth"
internal const val SPATIAL_FRAGMENT_PROBE_HOLD_MS_PROPERTY =
    "debug.rustyquest.spatial.fragment_probe.hold_ms"

internal const val SPATIAL_FRAGMENT_PROBE_DEFAULT_HOLD_MS = 12_000L
internal const val SPATIAL_FRAGMENT_PROBE_MIN_HOLD_MS = 2_000L
internal const val SPATIAL_FRAGMENT_PROBE_MAX_HOLD_MS = 60_000L
internal const val SPATIAL_FRAGMENT_PROBE_RAYMARCH_STEPS = 12

internal enum class SpatialFragmentProbeMode(
    val propertyValue: String,
    val shaderValue: Float,
) {
  FLAT_2D("flat-2d", 0.0f),
  RAYMARCH("raymarch", 1.0f),
  ;

  companion object {
    fun parse(raw: String): SpatialFragmentProbeMode? =
        entries.firstOrNull { it.propertyValue == raw.trim().lowercase(Locale.US) }
  }
}

internal data class SpatialFragmentProbeConfig(
    val enabled: Boolean,
    val mode: SpatialFragmentProbeMode?,
    val fragmentDepth: Boolean,
    val holdMs: Long,
    val rejectionReason: String,
) {
  val shaderName: String
    get() =
        if (fragmentDepth) {
          "spatial_fragment_probe_depth"
        } else {
          "spatial_fragment_probe_nodepth"
        }
}

internal object SpatialFragmentProbeRoute {
  const val MODULE_ID = "spatial-fragment-probe-route"

  fun resolve(
      readProperty: (String) -> String = ::activityReadSystemProperty,
  ): SpatialFragmentProbeConfig {
    val enabled = parseBoolean(readProperty(SPATIAL_FRAGMENT_PROBE_ENABLED_PROPERTY))
    if (enabled != true) {
      return SpatialFragmentProbeConfig(
          enabled = false,
          mode = null,
          fragmentDepth = false,
          holdMs = SPATIAL_FRAGMENT_PROBE_DEFAULT_HOLD_MS,
          rejectionReason = if (enabled == false) "disabled" else "missing-or-invalid-enable",
      )
    }

    val mode = SpatialFragmentProbeMode.parse(readProperty(SPATIAL_FRAGMENT_PROBE_MODE_PROPERTY))
    if (mode == null) {
      return SpatialFragmentProbeConfig(
          enabled = false,
          mode = null,
          fragmentDepth = false,
          holdMs = SPATIAL_FRAGMENT_PROBE_DEFAULT_HOLD_MS,
          rejectionReason = "missing-or-invalid-mode",
      )
    }

    val fragmentDepth = parseBoolean(readProperty(SPATIAL_FRAGMENT_PROBE_DEPTH_PROPERTY))
    if (fragmentDepth == null) {
      return SpatialFragmentProbeConfig(
          enabled = false,
          mode = mode,
          fragmentDepth = false,
          holdMs = SPATIAL_FRAGMENT_PROBE_DEFAULT_HOLD_MS,
          rejectionReason = "missing-or-invalid-fragment-depth",
      )
    }

    val holdMs =
        readProperty(SPATIAL_FRAGMENT_PROBE_HOLD_MS_PROPERTY)
            .toLongOrNull()
            ?.coerceIn(SPATIAL_FRAGMENT_PROBE_MIN_HOLD_MS, SPATIAL_FRAGMENT_PROBE_MAX_HOLD_MS)
            ?: SPATIAL_FRAGMENT_PROBE_DEFAULT_HOLD_MS
    return SpatialFragmentProbeConfig(
        enabled = true,
        mode = mode,
        fragmentDepth = fragmentDepth,
        holdMs = holdMs,
        rejectionReason = "none",
    )
  }

  fun startMarker(reason: String, config: SpatialFragmentProbeConfig): String =
      "channel=spatial-fragment-probe status=start reason=${activityMarkerToken(reason)} " +
          "mode=${config.mode?.propertyValue ?: "none"} fragmentDepth=${config.fragmentDepth} " +
          "shader=${config.shaderName} raymarchSteps=$SPATIAL_FRAGMENT_PROBE_RAYMARCH_STEPS " +
          "holdMs=${config.holdMs} rendererAuthority=meta-spatial-sdk-custom-material " +
          "activationAdapter=android-system-property " +
          "activationEffectiveMarker=rusty.quest.spatial_fragment_probe.effective " +
          "temporalModulation=false photosensitiveSafetyMode=static-only " +
          "visibleEvidenceRequired=true " +
          "featureOptInProperty=$SPATIAL_FRAGMENT_PROBE_ENABLED_PROPERTY"

  fun effectiveMarker(config: SpatialFragmentProbeConfig): String =
      "channel=spatial-fragment-probe status=effective " +
          "effectiveMarker=rusty.quest.spatial_fragment_probe.effective " +
          "mode=${config.mode?.propertyValue ?: "none"} fragmentDepth=${config.fragmentDepth} " +
          "shader=${config.shaderName} raymarchSteps=$SPATIAL_FRAGMENT_PROBE_RAYMARCH_STEPS " +
          "depthTest=less-or-equal depthWrite=enabled " +
          "temporalModulation=false photosensitiveSafetyMode=static-only"

  fun renderReadyMarker(config: SpatialFragmentProbeConfig): String =
      "channel=spatial-fragment-probe status=render-ready " +
          "mode=${config.mode?.propertyValue ?: "none"} fragmentDepth=${config.fragmentDepth} " +
          "proxyVolumeCreated=true foregroundOccluderCreated=true " +
          "depthDiscriminatorCreated=true screenshotRequired=true " +
          "gpuFragmentExecutionConfirmed=false"

  fun renderWindowMarker(config: SpatialFragmentProbeConfig, sceneTicks: Long): String =
      "channel=spatial-fragment-probe status=render-window " +
          "mode=${config.mode?.propertyValue ?: "none"} fragmentDepth=${config.fragmentDepth} " +
          "sceneTicks=$sceneTicks screenshotRequired=true gpuFragmentExecutionConfirmed=false"

  fun completeMarker(config: SpatialFragmentProbeConfig, sceneTicks: Long): String =
      "channel=spatial-fragment-probe status=complete " +
          "mode=${config.mode?.propertyValue ?: "none"} fragmentDepth=${config.fragmentDepth} " +
          "sceneTicks=$sceneTicks visiblePatternConfirmed=false humanVisibleCheckRequired=true"

  fun failureMarker(config: SpatialFragmentProbeConfig, error: Throwable): String =
      "channel=spatial-fragment-probe status=failed " +
          "mode=${config.mode?.propertyValue ?: "none"} fragmentDepth=${config.fragmentDepth} " +
          "error=${activityMarkerToken(error::class.java.simpleName)} " +
          "message=${activityMarkerToken(error.message ?: "none")}"

  fun cleanupMarker(reason: String, complete: Boolean): String =
      "channel=spatial-fragment-probe status=cleanup reason=${activityMarkerToken(reason)} " +
          "cleanupComplete=$complete"

  private fun parseBoolean(raw: String): Boolean? =
      when (raw.trim().lowercase(Locale.US)) {
        "1", "true", "yes", "on", "enabled" -> true
        "0", "false", "no", "off", "disabled" -> false
        else -> null
      }
}
