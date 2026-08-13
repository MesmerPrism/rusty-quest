package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal data class SpatialCameraControlProfileEffective(
    val layerOverride: Float,
    val projectionScale: Float,
    val zoneCompositor: PrivateLayerZoneCompositor,
    val rgbChannelTransform: RgbChannelTransform,
    val projectionSurfaceDisplacement: ProjectionSurfaceDisplacement,
    val projectionSurfaceTiling: ProjectionSurfaceTiling,
    val projectionInnerAlpha: ProjectionInnerAlpha,
)

internal class SpatialCameraControlProfileHotloader(
    context: Context,
    private val routeActive: () -> Boolean,
    private val applyProfile:
        (SpatialCameraControlProfile, String) -> SpatialCameraControlProfileEffective,
    private val marker: (String) -> Unit,
) {
  private data class FileSignature(val length: Long, val modifiedMs: Long)

  private val directory =
      File(
          context.getExternalFilesDir(null) ?: context.filesDir,
          SpatialCameraControlProfileContract.PROFILE_DIRECTORY,
      )
  private val activeProfile =
      File(directory, SpatialCameraControlProfileContract.ACTIVE_PROFILE_FILE)
  private val applyReceipt =
      File(directory, SpatialCameraControlProfileContract.APPLY_RECEIPT_FILE)
  private var armed = false
  private var observedSignature: FileSignature? = null
  private var pendingSignature: FileSignature? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private var fileObserver: FileObserver? = null

  fun arm() {
    if (armed) return
    directory.mkdirs()
    observedSignature = signature()
    pendingSignature = null
    armed = true
    fileObserver =
        object :
            FileObserver(
                directory,
                CLOSE_WRITE or CREATE or DELETE or MOVED_FROM or MOVED_TO,
            ) {
          override fun onEvent(event: Int, path: String?) {
            if (path != SpatialCameraControlProfileContract.ACTIVE_PROFILE_FILE) return
            mainHandler.post { poll(force = true) }
          }
        }
    fileObserver?.startWatching()
    marker(
        "channel=control-profile-hotload status=armed " +
            "schema=${SpatialCameraControlProfileContract.SCHEMA} " +
            "profilePath=external-files/${SpatialCameraControlProfileContract.PROFILE_DIRECTORY}/${SpatialCameraControlProfileContract.ACTIVE_PROFILE_FILE} " +
            "staleProfileApplied=false changeDetection=file-observer sceneTickPolling=false"
    )
  }

  fun poll(force: Boolean = false) {
    if (!armed) return
    if (!force) return
    val current = signature()
    if (current == observedSignature) {
      pendingSignature = null
      return
    }
    if (current == null) {
      observedSignature = null
      pendingSignature = null
      marker(
          "channel=control-profile-hotload status=profile-removed " +
              "effectiveControlsRetained=true runtimeCrash=false"
      )
      return
    }
    if (!routeActive()) {
      if (pendingSignature != current) {
        pendingSignature = current
        marker(
            "channel=control-profile-hotload status=pending-route " +
                "profileChanged=true routeActive=false runtimeCrash=false"
        )
      }
      return
    }

    val bytes =
        runCatching { activeProfile.readBytes() }
            .getOrElse { throwable ->
              reject(current, null, null, "profile_read_failed", throwable)
              return
            }
    val digest = sha256(bytes)
    val profile =
        runCatching { SpatialCameraControlProfileContract.parse(bytes) }
            .getOrElse { throwable ->
              reject(current, digest, null, "profile_validation_failed", throwable)
              return
            }
    val effective =
        runCatching { applyProfile(profile, "control-profile-hotload") }
            .getOrElse { throwable ->
              reject(current, digest, profile, "profile_apply_failed", throwable)
              return
            }
    val receipt =
        JSONObject()
            .put(
                "schema",
                SpatialCameraControlProfileContract.APPLY_RECEIPT_SCHEMA,
            )
            .put("status", "applied")
            .put("profile_id", profile.profileId)
            .put("revision", profile.revision)
            .put("profile_sha256", digest)
            .put("applied_unix_ms", System.currentTimeMillis())
            .put("route_active", true)
            .put("effective", effectiveJson(effective))
    writeReceipt(receipt)
    observedSignature = current
    pendingSignature = null
    marker(
        "channel=control-profile-hotload status=applied " +
            "profileId=${activityMarkerToken(profile.profileId)} revision=${profile.revision} " +
            "profileSha256=$digest transport=file-poll-atomic-replace " +
            "layerOverride=${effective.layerOverride} projectionScale=${effective.projectionScale} " +
            "${PrivateLayerZoneCompositorModule.markerFields(effective.zoneCompositor)} " +
            "${RgbChannelTransformModule.markerFields(effective.rgbChannelTransform)} " +
            "${ProjectionSurfaceDisplacementModule.markerFields(effective.projectionSurfaceDisplacement)} " +
            "projectionSurfaceTilingRequested=${ProjectionSurfaceTilingModule.requested(effective.projectionSurfaceTiling)} " +
            "projectionInnerAlphaRequested=${ProjectionInnerAlphaModule.requested(effective.projectionInnerAlpha)} " +
            "runtimeCrash=false"
    )
  }

  fun close() {
    armed = false
    fileObserver?.stopWatching()
    fileObserver = null
  }

  private fun reject(
      signature: FileSignature,
      digest: String?,
      profile: SpatialCameraControlProfile?,
      code: String,
      throwable: Throwable,
  ) {
    val receipt =
        JSONObject()
            .put(
                "schema",
                SpatialCameraControlProfileContract.APPLY_RECEIPT_SCHEMA,
            )
            .put("status", "rejected")
            .put("profile_id", profile?.profileId ?: JSONObject.NULL)
            .put("revision", profile?.revision ?: JSONObject.NULL)
            .put("profile_sha256", digest ?: JSONObject.NULL)
            .put("rejected_unix_ms", System.currentTimeMillis())
            .put("route_active", routeActive())
            .put("error_code", code)
            .put(
                "error",
                activityMarkerToken(throwable.message ?: throwable.javaClass.simpleName),
            )
    writeReceipt(receipt)
    observedSignature = signature
    pendingSignature = null
    marker(
        "channel=control-profile-hotload status=rejected errorCode=$code " +
            "error=${activityMarkerToken(throwable.message ?: throwable.javaClass.simpleName)} " +
            "previousEffectiveControlsRetained=true runtimeCrash=false"
    )
  }

  private fun signature(): FileSignature? =
      if (activeProfile.isFile) {
        FileSignature(activeProfile.length(), activeProfile.lastModified())
      } else {
        null
      }

  private fun writeReceipt(json: JSONObject) {
    runCatching {
          directory.mkdirs()
          val pending = File(directory, "${applyReceipt.name}.pending")
          pending.writeText(json.toString(2), Charsets.UTF_8)
          if (applyReceipt.exists() && !applyReceipt.delete()) {
            error("receipt_replace_failed")
          }
          if (!pending.renameTo(applyReceipt)) {
            error("receipt_publish_failed")
          }
        }
        .onFailure { throwable ->
          marker(
              "channel=control-profile-hotload status=receipt-write-failed " +
                  "error=${activityMarkerToken(throwable.message ?: throwable.javaClass.simpleName)} " +
                  "runtimeCrash=false"
          )
        }
  }

  private fun effectiveJson(effective: SpatialCameraControlProfileEffective): JSONObject =
      JSONObject()
          .put("layer_override", effective.layerOverride)
          .put("projection_scale", effective.projectionScale)
          .put("zone_compositor", zoneJson(effective.zoneCompositor))
          .put("rgb_channel_transform", rgbJson(effective.rgbChannelTransform))
          .put(
              "projection_surface_displacement",
              surfaceDisplacementJson(effective.projectionSurfaceDisplacement),
          )
          .put(
              "projection_surface_tiling",
              surfaceTilingJson(effective.projectionSurfaceTiling),
          )
          .put(
              "projection_inner_alpha",
              innerAlphaJson(effective.projectionInnerAlpha),
          )

  private fun zoneJson(value: PrivateLayerZoneCompositor): JSONObject =
      JSONObject()
          .put(
              "coverage_mode",
              when (value.coverageMode) {
                PrivateLayerZoneCompositorControls.coverageDynamicBuffer -> "buffer"
                PrivateLayerZoneCompositorControls.coverageReplaceVideo -> "full"
                else -> "off"
              },
          )
          .put("region_contract", "v3")
          .put(
              "buffer_geometry",
              PrivateLayerZoneCompositorControls.bufferGeometryToken(value.bufferGeometryMode),
          )
          .put("buffer_static_width_uv", value.bufferStaticWidthUv)
          .put("buffer_minimum_width_uv", value.bufferMinimumWidthUv)
          .put("buffer_maximum_width_uv", value.bufferMaximumWidthUv)
          .put(
              "buffer_maximum_speed_meters_per_second",
              value.bufferMaximumSpeedMetersPerSecond,
          )
          .put(
              "buffer_fill",
              PrivateLayerZoneCompositorControls.bufferFillToken(value.bufferFillMode),
          )
          .put(
              "stretch_extent",
              PrivateLayerZoneCompositorControls.stretchExtentToken(value.stretchExtentMode),
          )
          .put(
              "stretch_source",
              when (value.stretchSource) {
                PrivateLayerZoneCompositorControls.sourceProcessed -> "processed"
                PrivateLayerZoneCompositorControls.sourceMixed -> "mix"
                else -> "raw"
              },
          )
          .put(
              "debug_mode",
              when (value.debugMode) {
                PrivateLayerZoneCompositorControls.debugRegions -> "regions"
                PrivateLayerZoneCompositorControls.debugSampleUv -> "sample-uv"
                else -> "normal"
              },
          )
          .put(
              "outer_target_mode",
              if (
                  value.outerTargetMode ==
                      PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo
              ) {
                "transparent-spatial-video"
              } else {
                "readable-color"
              },
          )
          .put("stretch_mapping", "graded-edge-trail-native")
          .put(
              "projection_effect_edge_guard_enabled",
              value.projectionEffectEdgeGuardEnabled,
          )
          .put("stretch_option_flags", value.stretchOptionFlags)
          .put("edge_inset_uv", value.edgeInsetUv)
          .put("max_inset_uv", value.maxInsetUv)
          .put("stretch_curve", value.stretchCurve)
          .put("processed_mix", value.processedMix)
          .put(
              "outer_content",
              PrivateLayerZoneCompositorControls.outerContentToken(value.outerContentMode),
          )
          .put(
              "outer_stretch_source",
              when (value.outerStretchSource) {
                PrivateLayerZoneCompositorControls.sourceProcessed -> "processed"
                PrivateLayerZoneCompositorControls.sourceMixed -> "mix"
                else -> "raw"
              },
          )
          .put("outer_stretch_option_flags", value.outerStretchOptionFlags)
          .put("outer_edge_inset_uv", value.outerEdgeInsetUv)
          .put("outer_max_inset_uv", value.outerMaxInsetUv)
          .put("outer_stretch_curve", value.outerStretchCurve)
          .put("outer_processed_mix", value.outerProcessedMix)
          .put("inner", zoneBandJson(value, true))
          .put("outer", zoneBandJson(value, false))

  private fun zoneBandJson(
      value: PrivateLayerZoneCompositor,
      inner: Boolean,
  ): JSONObject {
    val signal = if (inner) value.innerSignal else value.outerSignal
    val dynamics = if (inner) value.innerChannelDynamics else value.outerChannelDynamics
    return JSONObject()
        .put("signal", zoneSignalToken(signal))
        .put("width_uv", if (inner) value.innerWidthUv else value.outerWidthUv)
        .put("curve", if (inner) value.innerCurve else value.outerCurve)
        .put(
            "threshold_rgb",
            float3(
                if (inner) value.innerThresholdR else value.outerThresholdR,
                if (inner) value.innerThresholdG else value.outerThresholdG,
                if (inner) value.innerThresholdB else value.outerThresholdB,
            ),
        )
        .put("softness", if (inner) value.innerSoftness else value.outerSoftness)
        .put("strength", if (inner) value.innerStrength else value.outerStrength)
        .put(
            "cycle_amplitude",
            if (inner) value.innerCycleAmplitude else value.outerCycleAmplitude,
        )
        .put("cycle_hz", if (inner) value.innerCycleHz else value.outerCycleHz)
        .put("motion_gain", if (inner) value.innerMotionGain else value.outerMotionGain)
        .put("channel_dynamics", zoneChannelDynamicsJson(dynamics))
  }

  private fun zoneChannelDynamicsJson(value: PrivateLayerZoneChannelDynamics): JSONObject =
      JSONObject()
          .put(
              "application_mode",
              when (value.applicationMode) {
                PrivateLayerZoneCompositorControls.applicationComponent -> "component"
                PrivateLayerZoneCompositorControls.applicationRegion -> "region"
                else -> "legacy"
              },
          )
          .put(
              "source_choice",
              when (value.sourceChoice) {
                PrivateLayerZoneCompositorControls.blendSourceOutgoing -> "outgoing"
                PrivateLayerZoneCompositorControls.blendSourceIncoming -> "incoming"
                else -> "midpoint"
              },
          )
          .put(
              "region_driver",
              when (value.regionDriver) {
                PrivateLayerZoneCompositorControls.regionDriverRed -> "red"
                PrivateLayerZoneCompositorControls.regionDriverGreen -> "green"
                PrivateLayerZoneCompositorControls.regionDriverBlue -> "blue"
                PrivateLayerZoneCompositorControls.regionDriverMax -> "max"
                else -> "luma"
              },
          )
          .put("strength_rgb", float3(value.strengthR, value.strengthG, value.strengthB))
          .put(
              "cycle_amplitude_rgb",
              float3(value.cycleAmplitudeR, value.cycleAmplitudeG, value.cycleAmplitudeB),
          )
          .put("cycle_hz_rgb", float3(value.cycleHzR, value.cycleHzG, value.cycleHzB))
          .put(
              "cycle_phase_turns_rgb",
              float3(value.cyclePhaseR, value.cyclePhaseG, value.cyclePhaseB),
          )

  private fun rgbJson(value: RgbChannelTransform): JSONObject =
      JSONObject()
          .put(
              "mode",
              when (value.mode) {
                RgbChannelTransformControls.modeIndependent -> "independent"
                RgbChannelTransformControls.modeLinked -> "linked"
                else -> "off"
              },
          )
          .put("edge_mode", RgbChannelTransformControls.edgeToken(value.edgeMode))
          .put("red", rgbChannelJson(value.red))
          .put("green", rgbChannelJson(value.green))
          .put("blue", rgbChannelJson(value.blue))

  private fun rgbChannelJson(value: RgbChannelParameters): JSONObject =
      JSONObject()
          .put("direction_turns", value.directionTurns)
          .put("direction_rate_hz", value.directionRateHz)
          .put("displacement_strength_uv", value.displacementStrengthUv)
          .put("image_scale", value.imageScale)
          .put("coverage_scale", value.coverageScale)

  private fun surfaceDisplacementJson(value: ProjectionSurfaceDisplacement): JSONObject =
      JSONObject()
          .put("enabled", value.enabled)
          .put("max_displacement_meters", value.maxDisplacementMeters)
          .put(
              "reference_surface_distance_meters",
              value.referenceSurfaceDistanceMeters,
          )
          .put("polarity", value.polarity)
          .put("edge_taper", value.edgeTaper)

  private fun surfaceTilingJson(value: ProjectionSurfaceTiling): JSONObject =
      JSONObject()
          .put("enabled", value.enabled)
          .put("topology", ProjectionSurfaceTilingControls.topologyToken(value.topology))
          .put("gap_normalized", value.gapNormalized)
          .put("depth_flexibility", value.depthFlexibility)
          .put("scope", ProjectionSurfaceTilingControls.scopeToken(value.scope))

  private fun innerAlphaJson(value: ProjectionInnerAlpha): JSONObject =
      JSONObject()
          .put("enabled", value.enabled)
          .put("driver", ProjectionInnerAlphaControls.driverToken(value.driver))
          .put("threshold", value.threshold)
          .put("softness", value.softness)
          .put("amount", value.amount)
          .put("invert", value.invert)
          .put(
              "stretch_policy",
              ProjectionInnerAlphaControls.stretchPolicyToken(value.stretchPolicy),
          )
          .put(
              "stretch_obeys_exact_projection_mask",
              value.stretchObeysExactProjectionMask,
          )

  private fun zoneSignalToken(value: Int): String =
      when (value) {
        PrivateLayerZoneCompositorControls.signalRgb -> "rgb"
        PrivateLayerZoneCompositorControls.signalLuma -> "luma"
        PrivateLayerZoneCompositorControls.signalChroma -> "chroma"
        PrivateLayerZoneCompositorControls.signalDifference -> "difference"
        else -> "flat"
      }

  private fun float3(red: Float, green: Float, blue: Float): JSONArray =
      JSONArray().put(red).put(green).put(blue)

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256")
          .digest(bytes)
          .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
