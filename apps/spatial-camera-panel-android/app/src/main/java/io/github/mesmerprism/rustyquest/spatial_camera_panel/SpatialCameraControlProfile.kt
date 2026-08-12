package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

internal data class SpatialCameraControlProfile(
    val profileId: String,
    val revision: Long,
    val layerOverride: Float,
    val projectionScale: Float,
    val zoneCompositor: PrivateLayerZoneCompositor,
    val rgbChannelTransform: RgbChannelTransform,
    val projectionSurfaceDisplacement: ProjectionSurfaceDisplacement,
    val projectionSurfaceTiling: ProjectionSurfaceTiling,
    val projectionInnerAlpha: ProjectionInnerAlpha,
)

internal object SpatialCameraControlProfileContract {
  const val SCHEMA = "rusty.quest.spatial_camera_panel.control_profile.v1"
  const val APPLY_RECEIPT_SCHEMA =
      "rusty.quest.spatial_camera_panel.control_profile_apply_receipt.v1"
  const val PROFILE_DIRECTORY = "control-profiles"
  const val ACTIVE_PROFILE_FILE = "active.profile.json"
  const val APPLY_RECEIPT_FILE = "last-apply-receipt.json"
  const val MAX_PROFILE_BYTES = 64 * 1024
  const val POLL_INTERVAL_MS = 250L

  fun parse(bytes: ByteArray): SpatialCameraControlProfile {
    require(bytes.isNotEmpty()) { "profile_empty" }
    require(bytes.size <= MAX_PROFILE_BYTES) { "profile_too_large" }
    val root = StrictJsonByteIngress.parseObject(bytes)
    root.requireOnlyKeys(
        "schema",
        "profile_id",
        "revision",
        "created_unix_ms",
        "quest_controls",
        "desktop_preview",
    )
    require(root.requireString("schema") == SCHEMA) { "unsupported_profile_schema" }
    val profileId = root.requireString("profile_id")
    require(PROFILE_ID.matches(profileId)) { "invalid_profile_id" }
    val revision = root.requireLong("revision", 0L, Long.MAX_VALUE)
    root.requireLong("created_unix_ms", 0L, Long.MAX_VALUE)
    if (root.has("desktop_preview")) {
      require(!root.isNull("desktop_preview")) { "desktop_preview_must_be_object" }
      validateDesktopPreview(root.requireObject("desktop_preview"))
    }

    val controls = root.requireObject("quest_controls")
    controls.requireOnlyKeys(
        "layer_override",
        "projection_scale",
        "zone_compositor",
        "rgb_channel_transform",
        "projection_surface_displacement",
        "projection_surface_tiling",
        "projection_inner_alpha",
    )
    return SpatialCameraControlProfile(
        profileId = profileId,
        revision = revision,
        layerOverride = controls.requireFloat("layer_override", -1.0f, 8.0f),
        projectionScale = controls.requireFloat("projection_scale", 0.25f, 1.8f),
        zoneCompositor = parseZone(controls.requireObject("zone_compositor")),
        rgbChannelTransform =
            parseRgbTransform(controls.requireObject("rgb_channel_transform")),
        projectionSurfaceDisplacement =
            parseSurfaceDisplacement(
                controls.requireObject("projection_surface_displacement")
            ),
        projectionSurfaceTiling =
            if (controls.has("projection_surface_tiling")) {
              parseSurfaceTiling(controls.requireObject("projection_surface_tiling"))
            } else {
              ProjectionSurfaceTilingControls.off
            },
        projectionInnerAlpha =
            if (controls.has("projection_inner_alpha")) {
              parseInnerAlpha(controls.requireObject("projection_inner_alpha"))
            } else {
              ProjectionInnerAlphaControls.off
            },
    )
  }

  private fun validateDesktopPreview(json: JSONObject) {
    json.requireOnlyKeys(
        "layer_token",
        "effect_clock_speed",
        "preview_target_hz",
        "preview_mode",
        "preview_eye",
        "color_effect_phase_offset_turns",
        "color_effect_rate_hz",
        "buffer_footprint_scale",
    )
    val layerToken = json.requireString("layer_token")
    require(layerToken.trim().isNotEmpty() && layerToken.length <= 128) {
      "invalid_desktop_preview_layer_token"
    }
    json.requireFloat("effect_clock_speed", 0.05f, 4.0f)
    json.requireFloat("preview_target_hz", 30.0f, 120.0f)
    json.requireToken("preview_mode", "stereo", "mono")
    json.requireToken("preview_eye", "left", "right")
    json.requireFloat("color_effect_phase_offset_turns", 0.0f, 1.0f)
    json.requireFloat("color_effect_rate_hz", -2.0f, 2.0f)
    json.requireFloat("buffer_footprint_scale", 0.5f, 1.5f)
  }

  private fun parseZone(json: JSONObject): PrivateLayerZoneCompositor {
    json.requireOnlyKeys(
        "coverage_mode",
        "region_contract",
        "buffer_geometry",
        "buffer_static_width_uv",
        "buffer_fill",
        "stretch_extent",
        "stretch_source",
        "debug_mode",
        "outer_target_mode",
        "stretch_mapping",
        "projection_effect_edge_guard_enabled",
        "stretch_option_flags",
        "edge_inset_uv",
        "max_inset_uv",
        "stretch_curve",
        "processed_mix",
        "inner",
        "outer",
    )
    val edgeInset = json.requireFloat("edge_inset_uv", 0.0f, 0.49f)
    val inner = parseZoneBand(json.requireObject("inner"))
    val outer = parseZoneBand(json.requireObject("outer"))
    val normalized =
        PrivateLayerZoneCompositorModule.normalize(
            PrivateLayerZoneCompositor(
            coverageMode =
                when (json.requireToken("coverage_mode", "off", "buffer", "full")) {
                  "buffer" -> PrivateLayerZoneCompositorControls.coverageDynamicBuffer
                  "full" -> PrivateLayerZoneCompositorControls.coverageReplaceVideo
                  else -> PrivateLayerZoneCompositorControls.coverageOff
                },
            regionContractVersion =
                if (json.has("region_contract")) {
                  when (json.requireToken("region_contract", "v2")) {
                    else -> PrivateLayerZoneCompositorControls.regionContractIndependent
                  }
                } else {
                  PrivateLayerZoneCompositorControls.regionContractLegacy
                },
            bufferGeometryMode =
                if (json.has("buffer_geometry")) {
                  when (json.requireToken("buffer_geometry", "off", "static", "dynamic")) {
                    "static" -> PrivateLayerZoneCompositorControls.bufferGeometryStatic
                    "dynamic" -> PrivateLayerZoneCompositorControls.bufferGeometryDynamic
                    else -> PrivateLayerZoneCompositorControls.bufferGeometryOff
                  }
                } else {
                  PrivateLayerZoneCompositorControls.bufferGeometryOff
                },
            bufferStaticWidthUv =
                if (json.has("buffer_static_width_uv")) {
                  json.requireFloat("buffer_static_width_uv", 0.0f, 0.5f)
                } else {
                  0.08f
                },
            bufferFillMode =
                if (json.has("buffer_fill")) {
                  when (
                      json.requireToken(
                          "buffer_fill",
                          "outer-continuation",
                          "transparent-reveal",
                          "stretch",
                      )
                  ) {
                    "transparent-reveal" ->
                        PrivateLayerZoneCompositorControls.bufferFillTransparentReveal
                    "stretch" -> PrivateLayerZoneCompositorControls.bufferFillStretch
                    else -> PrivateLayerZoneCompositorControls.bufferFillOuterContinuation
                  }
                } else {
                  PrivateLayerZoneCompositorControls.bufferFillOuterContinuation
                },
            stretchExtentMode =
                if (json.has("stretch_extent")) {
                  when (json.requireToken("stretch_extent", "buffer-only", "replace-outer")) {
                    "replace-outer" ->
                        PrivateLayerZoneCompositorControls.stretchExtentReplaceOuter
                    else -> PrivateLayerZoneCompositorControls.stretchExtentBufferOnly
                  }
                } else {
                  PrivateLayerZoneCompositorControls.stretchExtentBufferOnly
                },
            stretchSource =
                when (json.requireToken("stretch_source", "raw", "processed", "mix")) {
                  "processed" -> PrivateLayerZoneCompositorControls.sourceProcessed
                  "mix" -> PrivateLayerZoneCompositorControls.sourceMixed
                  else -> PrivateLayerZoneCompositorControls.sourceRaw
                },
            debugMode =
                when (json.requireToken("debug_mode", "normal", "regions", "sample-uv")) {
                  "regions" -> PrivateLayerZoneCompositorControls.debugRegions
                  "sample-uv" -> PrivateLayerZoneCompositorControls.debugSampleUv
                  else -> PrivateLayerZoneCompositorControls.debugOff
                },
            outerTargetMode =
                when (
                    json.requireToken(
                        "outer_target_mode",
                        "readable-color",
                        "transparent-spatial-video",
                    )
                ) {
                  "transparent-spatial-video" ->
                      PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo
                  else -> PrivateLayerZoneCompositorControls.outerTargetReadableColor
                },
            stretchMapping =
                when (
                    json.requireToken(
                        "stretch_mapping",
                        "graded-edge-trail-native",
                    )
                ) {
                  else -> PrivateLayerZoneCompositorControls.mappingGradedEdgeTrail
                },
            projectionEffectEdgeGuardEnabled =
                json.requireBoolean("projection_effect_edge_guard_enabled"),
            stretchOptionFlags =
                if (json.has("stretch_option_flags")) {
                  json.requireLong("stretch_option_flags", 0L, 31L).toInt()
                } else {
                  0
                },
            edgeInsetUv = edgeInset,
            maxInsetUv = json.requireFloat("max_inset_uv", edgeInset, 0.49f),
            stretchCurve = json.requireFloat("stretch_curve", 0.25f, 6.0f),
            processedMix = json.requireFloat("processed_mix", 0.0f, 1.0f),
            innerSignal = inner.signal,
            innerWidthUv = inner.widthUv,
            innerCurve = inner.curve,
            innerThresholdR = inner.thresholdRgb[0],
            innerThresholdG = inner.thresholdRgb[1],
            innerThresholdB = inner.thresholdRgb[2],
            innerSoftness = inner.softness,
            innerStrength = inner.strength,
            innerCycleAmplitude = inner.cycleAmplitude,
            innerCycleHz = inner.cycleHz,
            innerMotionGain = inner.motionGain,
            outerSignal = outer.signal,
            outerWidthUv = outer.widthUv,
            outerCurve = outer.curve,
            outerThresholdR = outer.thresholdRgb[0],
            outerThresholdG = outer.thresholdRgb[1],
            outerThresholdB = outer.thresholdRgb[2],
            outerSoftness = outer.softness,
            outerStrength = outer.strength,
            outerCycleAmplitude = outer.cycleAmplitude,
            outerCycleHz = outer.cycleHz,
            outerMotionGain = outer.motionGain,
            innerChannelDynamics = inner.channelDynamics,
            outerChannelDynamics = outer.channelDynamics,
            )
        )
    if (
        normalized.outerTargetMode ==
            PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo
    ) {
      require(PrivateLayerZoneCompositorControls.transparentSpatialVideoSupported(normalized)) {
        "unsupported_transparent_spatial_video_blend"
      }
    }
    return normalized
  }

  private data class ParsedZoneBand(
      val signal: Int,
      val widthUv: Float,
      val curve: Float,
      val thresholdRgb: FloatArray,
      val softness: Float,
      val strength: Float,
      val cycleAmplitude: Float,
      val cycleHz: Float,
      val motionGain: Float,
      val channelDynamics: PrivateLayerZoneChannelDynamics,
  )

  private fun parseZoneBand(json: JSONObject): ParsedZoneBand {
    json.requireOnlyKeys(
        "signal",
        "width_uv",
        "curve",
        "threshold_rgb",
        "softness",
        "strength",
        "cycle_amplitude",
        "cycle_hz",
        "motion_gain",
        "channel_dynamics",
    )
    return ParsedZoneBand(
        signal =
            when (
                json.requireToken(
                    "signal",
                    "flat",
                    "rgb",
                    "luma",
                    "chroma",
                    "difference",
                )
            ) {
              "rgb" -> PrivateLayerZoneCompositorControls.signalRgb
              "luma" -> PrivateLayerZoneCompositorControls.signalLuma
              "chroma" -> PrivateLayerZoneCompositorControls.signalChroma
              "difference" -> PrivateLayerZoneCompositorControls.signalDifference
              else -> PrivateLayerZoneCompositorControls.signalFlat
            },
        widthUv = json.requireFloat("width_uv", 0.0f, 0.25f),
        curve = json.requireFloat("curve", 0.25f, 6.0f),
        thresholdRgb = json.requireFloat3("threshold_rgb", 0.0f, 1.0f),
        softness = json.requireFloat("softness", 0.001f, 0.5f),
        strength = json.requireFloat("strength", 0.0f, 1.0f),
        cycleAmplitude = json.requireFloat("cycle_amplitude", 0.0f, 0.5f),
        cycleHz = json.requireFloat("cycle_hz", 0.0f, 4.0f),
        motionGain = json.requireFloat("motion_gain", -0.5f, 0.5f),
        channelDynamics =
            parseChannelDynamics(json.requireObject("channel_dynamics")),
    )
  }

  private fun parseChannelDynamics(json: JSONObject): PrivateLayerZoneChannelDynamics {
    json.requireOnlyKeys(
        "application_mode",
        "source_choice",
        "region_driver",
        "strength_rgb",
        "cycle_amplitude_rgb",
        "cycle_hz_rgb",
        "cycle_phase_turns_rgb",
    )
    val strength = json.requireFloat3("strength_rgb", 0.0f, 1.0f)
    val amplitude = json.requireFloat3("cycle_amplitude_rgb", 0.0f, 0.5f)
    val hz = json.requireFloat3("cycle_hz_rgb", 0.0f, 4.0f)
    val phase = json.requireFloat3("cycle_phase_turns_rgb", -4.0f, 4.0f)
    return PrivateLayerZoneChannelDynamics(
        applicationMode =
            when (json.requireToken("application_mode", "legacy", "component", "region")) {
              "component" -> PrivateLayerZoneCompositorControls.applicationComponent
              "region" -> PrivateLayerZoneCompositorControls.applicationRegion
              else -> PrivateLayerZoneCompositorControls.applicationLegacy
            },
        sourceChoice =
            when (json.requireToken("source_choice", "outgoing", "midpoint", "incoming")) {
              "outgoing" -> PrivateLayerZoneCompositorControls.blendSourceOutgoing
              "incoming" -> PrivateLayerZoneCompositorControls.blendSourceIncoming
              else -> PrivateLayerZoneCompositorControls.blendSourceMidpoint
            },
        regionDriver =
            when (json.requireToken("region_driver", "red", "green", "blue", "luma", "max")) {
              "red" -> PrivateLayerZoneCompositorControls.regionDriverRed
              "green" -> PrivateLayerZoneCompositorControls.regionDriverGreen
              "blue" -> PrivateLayerZoneCompositorControls.regionDriverBlue
              "max" -> PrivateLayerZoneCompositorControls.regionDriverMax
              else -> PrivateLayerZoneCompositorControls.regionDriverLuma
            },
        strengthR = strength[0],
        strengthG = strength[1],
        strengthB = strength[2],
        cycleAmplitudeR = amplitude[0],
        cycleAmplitudeG = amplitude[1],
        cycleAmplitudeB = amplitude[2],
        cycleHzR = hz[0],
        cycleHzG = hz[1],
        cycleHzB = hz[2],
        cyclePhaseR = phase[0],
        cyclePhaseG = phase[1],
        cyclePhaseB = phase[2],
    )
  }

  private fun parseRgbTransform(json: JSONObject): RgbChannelTransform {
    json.requireOnlyKeys("mode", "edge_mode", "red", "green", "blue")
    return RgbChannelTransformModule.normalize(
        RgbChannelTransform(
            mode =
                when (json.requireToken("mode", "off", "independent", "linked")) {
                  "independent" -> RgbChannelTransformControls.modeIndependent
                  "linked" -> RgbChannelTransformControls.modeLinked
                  else -> RgbChannelTransformControls.modeBypass
                },
            edgeMode =
                when (json.requireToken("edge_mode", "clamp", "mirror", "fade")) {
                  "mirror" -> RgbChannelTransformControls.edgeMirror
                  "fade" -> RgbChannelTransformControls.edgeFade
                  else -> RgbChannelTransformControls.edgeClamp
                },
            red = parseRgbChannel(json.requireObject("red")),
            green = parseRgbChannel(json.requireObject("green")),
            blue = parseRgbChannel(json.requireObject("blue")),
        )
    )
  }

  private fun parseRgbChannel(json: JSONObject): RgbChannelParameters {
    json.requireOnlyKeys(
        "direction_turns",
        "direction_rate_hz",
        "displacement_strength_uv",
        "image_scale",
        "coverage_scale",
    )
    return RgbChannelParameters(
        directionTurns = json.requireFloat("direction_turns", 0.0f, 1.0f),
        directionRateHz = json.requireFloat("direction_rate_hz", -2.0f, 2.0f),
        displacementStrengthUv =
            json.requireFloat("displacement_strength_uv", 0.0f, 0.08f),
        imageScale = json.requireFloat("image_scale", 0.5f, 2.0f),
        coverageScale = json.requireFloat("coverage_scale", 0.5f, 1.0f),
    )
  }

  private fun parseSurfaceDisplacement(json: JSONObject): ProjectionSurfaceDisplacement {
    json.requireOnlyKeys(
        "enabled",
        "max_displacement_meters",
        "reference_surface_distance_meters",
        "polarity",
        "edge_taper",
    )
    return ProjectionSurfaceDisplacementModule.normalize(
        ProjectionSurfaceDisplacement(
            enabled = json.requireBoolean("enabled"),
            maxDisplacementMeters =
                json.requireFloat("max_displacement_meters", 0.0f, 0.35f),
            referenceSurfaceDistanceMeters =
                json.requireFloat("reference_surface_distance_meters", 1.0f, 4.0f),
            polarity = json.requireFloat("polarity", -1.0f, 1.0f),
            edgeTaper = json.requireFloat("edge_taper", 0.02f, 0.45f),
        )
    )
  }

  private fun parseSurfaceTiling(json: JSONObject): ProjectionSurfaceTiling {
    json.requireOnlyKeys(
        "enabled",
        "topology",
        "gap_normalized",
        "depth_flexibility",
        "scope",
    )
    return ProjectionSurfaceTilingModule.normalize(
        ProjectionSurfaceTiling(
            enabled = json.requireBoolean("enabled"),
            topology =
                when (json.requireToken("topology", "continuous", "tiled", "triangle-tiles")) {
                  "tiled" -> ProjectionSurfaceTilingControls.topologyTiled
                  "triangle-tiles" -> ProjectionSurfaceTilingControls.topologyTriangleTiles
                  else -> ProjectionSurfaceTilingControls.topologyContinuous
                },
            gapNormalized = json.requireFloat("gap_normalized", 0.0f, 0.45f),
            depthFlexibility = json.requireFloat("depth_flexibility", 0.0f, 1.0f),
            scope =
                when (
                    json.requireToken(
                        "scope",
                        "inner-and-buffer",
                        "core-and-stretch",
                        "core-only",
                    )
                ) {
                  "core-only" -> ProjectionSurfaceTilingControls.scopeCoreOnly
                  else -> ProjectionSurfaceTilingControls.scopeInnerAndBuffer
                },
        )
    )
  }

  private fun parseInnerAlpha(json: JSONObject): ProjectionInnerAlpha {
    json.requireOnlyKeys(
        "enabled",
        "driver",
        "threshold",
        "softness",
        "amount",
        "invert",
        "stretch_policy",
        "stretch_obeys_exact_projection_mask",
    )
    return ProjectionInnerAlphaModule.normalize(
        ProjectionInnerAlpha(
            enabled = json.requireBoolean("enabled"),
            driver =
                when (json.requireToken("driver", "red", "green", "blue", "luma", "max")) {
                  "red" -> ProjectionInnerAlphaControls.driverRed
                  "green" -> ProjectionInnerAlphaControls.driverGreen
                  "blue" -> ProjectionInnerAlphaControls.driverBlue
                  "max" -> ProjectionInnerAlphaControls.driverMax
                  else -> ProjectionInnerAlphaControls.driverLuma
                },
            threshold = json.requireFloat("threshold", 0.0f, 1.0f),
            softness = json.requireFloat("softness", 0.001f, 0.5f),
            amount = json.requireFloat("amount", 0.0f, 1.0f),
            invert = json.requireBoolean("invert"),
            stretchPolicy =
                when (
                    json.requireToken(
                        "stretch_policy",
                        "follow-projection",
                        "opaque-independent",
                    )
                ) {
                  "opaque-independent" ->
                      ProjectionInnerAlphaControls.stretchOpaqueIndependent
                  else -> ProjectionInnerAlphaControls.stretchFollowProjection
                },
            stretchObeysExactProjectionMask =
                json.requireBoolean("stretch_obeys_exact_projection_mask"),
        )
    )
  }

  private val PROFILE_ID = Regex("[a-z0-9][a-z0-9._-]{1,63}")
}

internal fun JSONObject.requireOnlyKeys(vararg allowed: String) {
  val allowedSet = allowed.toSet()
  val unknown = keys().asSequence().filterNot(allowedSet::contains).toList()
  require(unknown.isEmpty()) { "unknown_fields_${unknown.sorted().joinToString("_")}" }
}

internal fun JSONObject.requireObject(name: String): JSONObject {
  val value = get(name)
  require(value is JSONObject) { "${name}_must_be_object" }
  return value
}

internal fun JSONObject.requireString(name: String): String {
  val value = get(name)
  require(value is String && value.isNotBlank()) { "${name}_must_be_string" }
  return value
}

internal fun JSONObject.requireBoolean(name: String): Boolean {
  val value = get(name)
  require(value is Boolean) { "${name}_must_be_boolean" }
  return value
}

internal fun JSONObject.requireLong(name: String, minimum: Long, maximum: Long): Long {
  val value = get(name)
  require(value is Number) { "${name}_must_be_integer" }
  val result =
      when (value) {
        is Byte, is Short, is Int, is Long -> value.toLong()
        is BigInteger -> runCatching { value.longValueExact() }.getOrNull()
        is BigDecimal -> runCatching { value.longValueExact() }.getOrNull()
        is Float ->
            if (value.isFinite()) {
              runCatching { BigDecimal(value.toString()).longValueExact() }.getOrNull()
            } else {
              null
            }
        is Double ->
            if (value.isFinite()) {
              runCatching { BigDecimal.valueOf(value).longValueExact() }.getOrNull()
            } else {
              null
            }
        else -> null
      }
  require(result != null) { "${name}_must_be_integer" }
  require(result in minimum..maximum) { "${name}_out_of_range" }
  return result
}

internal fun JSONObject.requireFloat(
    name: String,
    minimum: Float,
    maximum: Float,
): Float {
  val value = get(name)
  require(value is Number) { "${name}_must_be_number" }
  val result = value.toFloat()
  require(result.isFinite() && result in minimum..maximum) { "${name}_out_of_range" }
  return result
}

private fun JSONObject.requireFloat3(
    name: String,
    minimum: Float,
    maximum: Float,
): FloatArray {
  val value = get(name)
  require(value is JSONArray && value.length() == 3) { "${name}_must_be_float3" }
  return FloatArray(3) { index ->
    val element = value.get(index)
    require(element is Number) { "${name}_${index}_must_be_number" }
    val result = element.toFloat()
    require(result.isFinite() && result in minimum..maximum) {
      "${name}_${index}_out_of_range"
    }
    result
  }
}

internal fun JSONObject.requireToken(name: String, vararg allowed: String): String {
  val value = requireString(name)
  require(allowed.contains(value)) { "${name}_unsupported_token" }
  return value
}
