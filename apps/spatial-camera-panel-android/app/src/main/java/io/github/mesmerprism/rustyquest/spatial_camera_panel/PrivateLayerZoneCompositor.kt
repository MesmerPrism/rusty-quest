package io.github.mesmerprism.rustyquest.spatial_camera_panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class PrivateLayerZoneChannelDynamics(
    val applicationMode: Int = PrivateLayerZoneCompositorControls.applicationLegacy,
    val sourceChoice: Int = PrivateLayerZoneCompositorControls.blendSourceMidpoint,
    val regionDriver: Int = PrivateLayerZoneCompositorControls.regionDriverLuma,
    val strengthR: Float = 0.0f,
    val strengthG: Float = 0.0f,
    val strengthB: Float = 0.0f,
    val cycleAmplitudeR: Float = 0.0f,
    val cycleAmplitudeG: Float = 0.0f,
    val cycleAmplitudeB: Float = 0.0f,
    val cycleHzR: Float = 0.12f,
    val cycleHzG: Float = 0.17f,
    val cycleHzB: Float = 0.23f,
    val cyclePhaseR: Float = 0.0f,
    val cyclePhaseG: Float = 0.333333f,
    val cyclePhaseB: Float = 0.666667f,
)

internal data class PrivateLayerZoneCompositor(
    val coverageMode: Int = PrivateLayerZoneCompositorControls.coverageOff,
    val regionContractVersion: Int = PrivateLayerZoneCompositorControls.regionContractLegacy,
    val bufferGeometryMode: Int = PrivateLayerZoneCompositorControls.bufferGeometryOff,
    val bufferStaticWidthUv: Float = 0.08f,
    val bufferFillMode: Int = PrivateLayerZoneCompositorControls.bufferFillOuterContinuation,
    val stretchExtentMode: Int = PrivateLayerZoneCompositorControls.stretchExtentBufferOnly,
    val stretchSource: Int = PrivateLayerZoneCompositorControls.sourceProcessed,
    val debugMode: Int = PrivateLayerZoneCompositorControls.debugOff,
    val outerTargetMode: Int = PrivateLayerZoneCompositorControls.outerTargetReadableColor,
    val stretchMapping: Int = PrivateLayerZoneCompositorControls.mappingGradedEdgeTrail,
    val projectionEffectEdgeGuardEnabled: Boolean = true,
    val stretchOptionFlags: Int = 0,
    val edgeInsetUv: Float = 0.015f,
    val maxInsetUv: Float = 0.14f,
    val stretchCurve: Float = 1.6f,
    val processedMix: Float = 1.0f,
    val innerSignal: Int = PrivateLayerZoneCompositorControls.signalFlat,
    val innerWidthUv: Float = 0.04f,
    val innerCurve: Float = 1.6f,
    val innerThresholdR: Float = 0.5f,
    val innerThresholdG: Float = 0.5f,
    val innerThresholdB: Float = 0.5f,
    val innerSoftness: Float = 0.12f,
    val innerStrength: Float = 0.0f,
    val innerCycleAmplitude: Float = 0.0f,
    val innerCycleHz: Float = 0.12f,
    val innerMotionGain: Float = 0.0f,
    val outerSignal: Int = PrivateLayerZoneCompositorControls.signalFlat,
    val outerWidthUv: Float = 0.04f,
    val outerCurve: Float = 1.6f,
    val outerThresholdR: Float = 0.5f,
    val outerThresholdG: Float = 0.5f,
    val outerThresholdB: Float = 0.5f,
    val outerSoftness: Float = 0.12f,
    val outerStrength: Float = 0.0f,
    val outerCycleAmplitude: Float = 0.0f,
    val outerCycleHz: Float = 0.10f,
    val outerMotionGain: Float = 0.0f,
    val innerChannelDynamics: PrivateLayerZoneChannelDynamics =
        PrivateLayerZoneChannelDynamics(),
    val outerChannelDynamics: PrivateLayerZoneChannelDynamics =
        PrivateLayerZoneChannelDynamics(
            cycleHzR = 0.10f,
            cycleHzG = 0.14f,
            cycleHzB = 0.19f,
        ),
)

internal object PrivateLayerZoneCompositorControls {
  const val regionContractLegacy = 1
  const val regionContractIndependent = 2

  const val coverageOff = 0
  const val coverageDynamicBuffer = 1
  const val coverageReplaceVideo = 2

  const val bufferGeometryOff = 0
  const val bufferGeometryStatic = 1
  const val bufferGeometryDynamic = 2

  const val bufferFillOuterContinuation = 0
  const val bufferFillTransparentReveal = 1
  const val bufferFillStretch = 2

  const val stretchExtentBufferOnly = 0
  const val stretchExtentReplaceOuter = 1

  const val sourceRaw = 0
  const val sourceProcessed = 1
  const val sourceMixed = 2
  const val mappingGradedEdgeTrail = 0
  const val stretchOptionMask = 0x1d
  const val signalFlat = 0
  const val signalRgb = 1
  const val signalLuma = 2
  const val signalChroma = 3
  const val signalDifference = 4
  const val applicationLegacy = 0
  const val applicationComponent = 1
  const val applicationRegion = 2
  const val blendSourceOutgoing = 0
  const val blendSourceMidpoint = 1
  const val blendSourceIncoming = 2
  const val regionDriverRed = 0
  const val regionDriverGreen = 1
  const val regionDriverBlue = 2
  const val regionDriverLuma = 3
  const val regionDriverMax = 4
  const val debugOff = 0
  const val debugRegions = 1
  const val debugSampleUv = 2
  const val outerTargetReadableColor = 0
  const val outerTargetTransparentSpatialVideo = 1

  val legacyOff =
      PrivateLayerZoneCompositor(
          regionContractVersion = regionContractLegacy,
      )
  val bufferOff =
      PrivateLayerZoneCompositor(
          regionContractVersion = regionContractIndependent,
      )
  val nativeBuffer =
      PrivateLayerZoneCompositor(
          coverageMode = coverageDynamicBuffer,
          regionContractVersion = regionContractIndependent,
          bufferGeometryMode = bufferGeometryDynamic,
          bufferFillMode = bufferFillStretch,
          stretchSource = sourceProcessed,
          stretchMapping = mappingGradedEdgeTrail,
      )
  val organicBuffer =
      nativeBuffer.copy(
          stretchSource = sourceMixed,
          processedMix = 0.68f,
          innerSignal = signalRgb,
          innerStrength = 0.38f,
          innerCycleAmplitude = 0.09f,
          innerCycleHz = 0.11f,
          innerMotionGain = 0.08f,
          outerSignal = signalDifference,
          outerStrength = 0.32f,
          outerCycleAmplitude = 0.07f,
          outerCycleHz = 0.08f,
          outerMotionGain = 0.10f,
      )
  val fullStretch =
      nativeBuffer.copy(
          coverageMode = coverageReplaceVideo,
          stretchExtentMode = stretchExtentReplaceOuter,
          outerSignal = signalFlat,
          outerStrength = 0.0f,
          outerCycleAmplitude = 0.0f,
          outerMotionGain = 0.0f,
      )
  val componentBlendTest =
      organicBuffer.copy(
          debugMode = debugRegions,
          innerWidthUv = 0.14f,
          outerWidthUv = 0.14f,
          innerSignal = signalRgb,
          outerSignal = signalRgb,
          innerChannelDynamics =
              PrivateLayerZoneChannelDynamics(
                  applicationMode = applicationComponent,
                  sourceChoice = blendSourceOutgoing,
                  strengthR = 0.85f,
                  strengthG = 0.50f,
                  strengthB = 0.20f,
                  cycleAmplitudeR = 0.14f,
                  cycleAmplitudeG = 0.10f,
                  cycleAmplitudeB = 0.07f,
                  cycleHzR = 0.10f,
                  cycleHzG = 0.17f,
                  cycleHzB = 0.26f,
              ),
          outerChannelDynamics =
              PrivateLayerZoneChannelDynamics(
                  applicationMode = applicationComponent,
                  sourceChoice = blendSourceIncoming,
                  strengthR = 0.20f,
                  strengthG = 0.50f,
                  strengthB = 0.85f,
                  cycleAmplitudeR = 0.07f,
                  cycleAmplitudeG = 0.10f,
                  cycleAmplitudeB = 0.14f,
                  cycleHzR = 0.13f,
                  cycleHzG = 0.20f,
                  cycleHzB = 0.31f,
              ),
      )
  val regionBlendTest =
      componentBlendTest.copy(
          innerChannelDynamics =
              componentBlendTest.innerChannelDynamics.copy(
                  applicationMode = applicationRegion,
                  sourceChoice = blendSourceMidpoint,
                  regionDriver = regionDriverRed,
              ),
          outerChannelDynamics =
              componentBlendTest.outerChannelDynamics.copy(
                  applicationMode = applicationRegion,
                  sourceChoice = blendSourceMidpoint,
                  regionDriver = regionDriverLuma,
              ),
      )
  val spatialVideoUnderlayBlendTest =
      organicBuffer.copy(
          coverageMode = coverageDynamicBuffer,
          debugMode = debugOff,
          outerTargetMode = outerTargetTransparentSpatialVideo,
          outerSignal = signalRgb,
          innerWidthUv = 0.14f,
          outerWidthUv = 0.14f,
          outerChannelDynamics =
              PrivateLayerZoneChannelDynamics(
                  applicationMode = applicationRegion,
                  sourceChoice = blendSourceOutgoing,
                  regionDriver = regionDriverLuma,
                  strengthR = 0.85f,
                  strengthG = 0.85f,
                  strengthB = 0.85f,
                  cycleAmplitudeR = 0.10f,
                  cycleAmplitudeG = 0.08f,
                  cycleAmplitudeB = 0.06f,
                  cycleHzR = 0.10f,
                  cycleHzG = 0.17f,
                  cycleHzB = 0.26f,
              ),
      )

  fun presetForToken(token: String): PrivateLayerZoneCompositor =
      when (token.trim().lowercase()) {
        "native-buffer" -> nativeBuffer
        "organic-buffer" -> organicBuffer
        "full-stretch" -> fullStretch
        "spatial-video-underlay" -> spatialVideoUnderlayBlendTest
        else -> bufferOff
      }

  fun presetToken(configuration: PrivateLayerZoneCompositor): String =
      when (PrivateLayerZoneCompositorModule.normalize(configuration)) {
        nativeBuffer -> "native-buffer"
        organicBuffer -> "organic-buffer"
        fullStretch -> "full-stretch"
        spatialVideoUnderlayBlendTest -> "spatial-video-underlay"
        bufferOff -> "off"
        else -> "custom"
      }

  fun bufferGeometryToken(mode: Int): String =
      when (mode) {
        bufferGeometryStatic -> "static"
        bufferGeometryDynamic -> "dynamic"
        else -> "off"
      }

  fun bufferFillToken(mode: Int): String =
      when (mode) {
        bufferFillTransparentReveal -> "transparent-reveal"
        bufferFillStretch -> "stretch"
        else -> "outer-continuation"
      }

  fun stretchExtentToken(mode: Int): String =
      when (mode) {
        stretchExtentReplaceOuter -> "replace-outer"
        else -> "buffer-only"
      }

  fun bufferContentLabel(configuration: PrivateLayerZoneCompositor): String =
      when (configuration.bufferFillMode) {
        bufferFillTransparentReveal -> "Transparent reveal"
        bufferFillStretch -> "Stretch"
        else ->
            if (configuration.outerTargetMode == outerTargetTransparentSpatialVideo) {
              "World-video reveal"
            } else {
              "Outer continuation"
            }
      }

  fun innerBoundaryLabel(configuration: PrivateLayerZoneCompositor): String =
      if (configuration.bufferGeometryMode == bufferGeometryOff) {
        "Inner ↔ Outer"
      } else {
        "Inner ↔ ${bufferContentLabel(configuration)}"
      }

  fun outerBoundaryLabel(configuration: PrivateLayerZoneCompositor): String =
      "${bufferContentLabel(configuration)} ↔ Outer"

  fun outerBoundaryActive(configuration: PrivateLayerZoneCompositor): Boolean =
      configuration.bufferGeometryMode != bufferGeometryOff &&
          !(configuration.bufferFillMode == bufferFillStretch &&
              configuration.stretchExtentMode == stretchExtentReplaceOuter)

  fun coverageToken(mode: Int): String =
      when (mode) {
        coverageDynamicBuffer -> "dynamic-buffer"
        coverageReplaceVideo -> "replace-video"
        else -> "off"
      }

  fun sourceToken(source: Int): String =
      when (source) {
        sourceRaw -> "raw-camera"
        sourceMixed -> "mixed"
        else -> "processed-layer"
      }

  fun mappingToken(@Suppress("UNUSED_PARAMETER") mapping: Int): String =
      "graded-edge-trail-native"

  fun signalToken(signal: Int): String =
      when (signal) {
        signalRgb -> "rgb"
        signalLuma -> "luma"
        signalChroma -> "chroma"
        signalDifference -> "difference"
        else -> "flat"
      }

  fun applicationToken(mode: Int): String =
      when (mode) {
        applicationComponent -> "component"
        applicationRegion -> "region"
        else -> "legacy"
      }

  fun blendSourceToken(source: Int): String =
      when (source) {
        blendSourceOutgoing -> "outgoing"
        blendSourceIncoming -> "incoming"
        else -> "midpoint"
      }

  fun regionDriverToken(driver: Int): String =
      when (driver) {
        regionDriverRed -> "red"
        regionDriverGreen -> "green"
        regionDriverBlue -> "blue"
        regionDriverMax -> "max"
        else -> "luma"
      }

  fun outerTargetToken(target: Int): String =
      when (target) {
        outerTargetTransparentSpatialVideo -> "transparent-spatial-video"
        else -> "readable-color"
      }

  fun transparentSpatialVideoSupported(configuration: PrivateLayerZoneCompositor): Boolean =
      configuration.outerTargetMode == outerTargetTransparentSpatialVideo &&
          (configuration.regionContractVersion >= regionContractIndependent ||
              configuration.coverageMode == coverageOff ||
              (configuration.coverageMode == coverageDynamicBuffer &&
                  configuration.debugMode == debugOff &&
                  configuration.outerSignal != signalDifference &&
                  configuration.outerChannelDynamics.applicationMode == applicationRegion &&
                  configuration.outerChannelDynamics.sourceChoice == blendSourceOutgoing))

  fun withOuterTarget(
      configuration: PrivateLayerZoneCompositor,
      target: Int,
  ): PrivateLayerZoneCompositor =
      if (configuration.regionContractVersion >= regionContractIndependent) {
        configuration.copy(outerTargetMode = target.coerceIn(0, 1))
      } else if (target == outerTargetTransparentSpatialVideo) {
        configuration.copy(
            debugMode = debugOff,
            outerTargetMode = outerTargetTransparentSpatialVideo,
            outerSignal =
                if (configuration.outerSignal == signalDifference) signalRgb
                else configuration.outerSignal,
            outerChannelDynamics =
                configuration.outerChannelDynamics.copy(
                    applicationMode = applicationRegion,
                    sourceChoice = blendSourceOutgoing,
                ),
        )
      } else {
        configuration.copy(outerTargetMode = outerTargetReadableColor)
      }

  /** Disables stretch without silently changing the independently selected outer target. */
  fun disableStretch(current: PrivateLayerZoneCompositor): PrivateLayerZoneCompositor =
      withOuterTarget(
          current.copy(
              bufferFillMode = bufferFillOuterContinuation,
              stretchExtentMode = stretchExtentBufferOnly,
              debugMode = debugOff,
          ),
          current.outerTargetMode,
      )

  /** Applies a stretch style without silently changing which layer owns the outer region. */
  fun applyStretchStyle(
      current: PrivateLayerZoneCompositor,
      style: PrivateLayerZoneCompositor,
  ): PrivateLayerZoneCompositor =
      current.copy(
          bufferFillMode = bufferFillStretch,
          stretchSource = style.stretchSource,
          stretchMapping = style.stretchMapping,
          projectionEffectEdgeGuardEnabled = style.projectionEffectEdgeGuardEnabled,
          stretchOptionFlags = style.stretchOptionFlags,
          edgeInsetUv = style.edgeInsetUv,
          maxInsetUv = style.maxInsetUv,
          stretchCurve = style.stretchCurve,
          processedMix = style.processedMix,
      )

  fun matchesStretchStyle(
      configuration: PrivateLayerZoneCompositor,
      style: PrivateLayerZoneCompositor,
  ): Boolean {
    val normalized = PrivateLayerZoneCompositorModule.normalize(configuration)
    return normalized.bufferFillMode == bufferFillStretch &&
        normalized.stretchSource == style.stretchSource &&
        normalized.stretchMapping == style.stretchMapping &&
        normalized.projectionEffectEdgeGuardEnabled == style.projectionEffectEdgeGuardEnabled &&
        normalized.stretchOptionFlags == style.stretchOptionFlags &&
        normalized.edgeInsetUv == style.edgeInsetUv &&
        normalized.maxInsetUv == style.maxInsetUv &&
        normalized.stretchCurve == style.stretchCurve &&
        normalized.processedMix == style.processedMix
  }
}
internal object PrivateLayerZoneCompositorModule {
  /**
   * A transparent Spatial-video underlay never samples or draws the custom decoder surface.
   * Other routes keep the readable consumer because they either sample it or retain it as the
   * native fallback when a replacement pipeline is unavailable.
   */
  fun readableVideoConsumerRequired(configuration: PrivateLayerZoneCompositor): Boolean =
      normalize(configuration).outerTargetMode != PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo

  fun normalize(requested: PrivateLayerZoneCompositor): PrivateLayerZoneCompositor {
    val migratingLegacy =
        requested.regionContractVersion <
            PrivateLayerZoneCompositorControls.regionContractIndependent
    if (migratingLegacy &&
        requested.outerTargetMode ==
            PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo &&
        !PrivateLayerZoneCompositorControls.transparentSpatialVideoSupported(requested)) {
      throw IllegalArgumentException("unsupported_transparent_spatial_video")
    }
    val bufferGeometryMode =
        if (migratingLegacy) {
          if (requested.coverageMode == PrivateLayerZoneCompositorControls.coverageOff) {
            PrivateLayerZoneCompositorControls.bufferGeometryOff
          } else {
            PrivateLayerZoneCompositorControls.bufferGeometryDynamic
          }
        } else {
          requested.bufferGeometryMode.coerceIn(0, 2)
        }
    val bufferFillMode =
        if (migratingLegacy) {
          if (requested.coverageMode == PrivateLayerZoneCompositorControls.coverageOff) {
            PrivateLayerZoneCompositorControls.bufferFillOuterContinuation
          } else {
            PrivateLayerZoneCompositorControls.bufferFillStretch
          }
        } else {
          requested.bufferFillMode.coerceIn(0, 2)
        }
    val stretchExtentMode =
        if (migratingLegacy &&
            requested.coverageMode == PrivateLayerZoneCompositorControls.coverageReplaceVideo) {
          PrivateLayerZoneCompositorControls.stretchExtentReplaceOuter
        } else {
          requested.stretchExtentMode.coerceIn(0, 1)
        }
    val compatibilityCoverage =
        when {
          bufferGeometryMode == PrivateLayerZoneCompositorControls.bufferGeometryOff ->
              PrivateLayerZoneCompositorControls.coverageOff
          bufferFillMode == PrivateLayerZoneCompositorControls.bufferFillStretch &&
              stretchExtentMode ==
                  PrivateLayerZoneCompositorControls.stretchExtentReplaceOuter ->
              PrivateLayerZoneCompositorControls.coverageReplaceVideo
          else -> PrivateLayerZoneCompositorControls.coverageDynamicBuffer
        }
    val legacyMappingRequest =
        requested.stretchMapping != PrivateLayerZoneCompositorControls.mappingGradedEdgeTrail
    val parameterA =
        if (legacyMappingRequest) 0.015f
        else requested.edgeInsetUv.finiteOr(0.015f).coerceIn(0.0f, 0.49f)
    val parameterB =
        if (legacyMappingRequest) 0.14f
        else requested.maxInsetUv.finiteOr(0.14f).coerceIn(parameterA, 0.49f)
    val parameterC =
        if (legacyMappingRequest) 1.6f
        else requested.stretchCurve.finiteOr(1.6f).coerceIn(0.25f, 6.0f)
    return requested.copy(
        coverageMode = compatibilityCoverage,
        regionContractVersion = PrivateLayerZoneCompositorControls.regionContractIndependent,
        bufferGeometryMode = bufferGeometryMode,
        bufferStaticWidthUv =
            requested.bufferStaticWidthUv.finiteOr(0.08f).coerceIn(0.0f, 0.5f),
        bufferFillMode = bufferFillMode,
        stretchExtentMode = stretchExtentMode,
        stretchSource = requested.stretchSource.coerceIn(0, 2),
        debugMode = requested.debugMode.coerceIn(0, 2),
        outerTargetMode = requested.outerTargetMode.coerceIn(0, 1),
        stretchMapping = PrivateLayerZoneCompositorControls.mappingGradedEdgeTrail,
        stretchOptionFlags =
            requested.stretchOptionFlags and PrivateLayerZoneCompositorControls.stretchOptionMask,
        edgeInsetUv = parameterA,
        maxInsetUv = parameterB,
        stretchCurve = parameterC,
        processedMix = requested.processedMix.finiteOr(1.0f).coerceIn(0.0f, 1.0f),
        innerSignal = requested.innerSignal.coerceIn(0, 4),
        innerWidthUv = requested.innerWidthUv.finiteOr(0.04f).coerceIn(0.0f, 0.25f),
        innerCurve = requested.innerCurve.finiteOr(1.6f).coerceIn(0.25f, 6.0f),
        innerThresholdR = requested.innerThresholdR.unit(),
        innerThresholdG = requested.innerThresholdG.unit(),
        innerThresholdB = requested.innerThresholdB.unit(),
        innerSoftness = requested.innerSoftness.finiteOr(0.12f).coerceIn(0.001f, 0.5f),
        innerStrength = requested.innerStrength.unit(),
        innerCycleAmplitude = requested.innerCycleAmplitude.finiteOr(0.0f).coerceIn(0.0f, 0.5f),
        innerCycleHz = requested.innerCycleHz.finiteOr(0.12f).coerceIn(0.0f, 4.0f),
        innerMotionGain = requested.innerMotionGain.finiteOr(0.0f).coerceIn(-0.5f, 0.5f),
        outerSignal = requested.outerSignal.coerceIn(0, 4),
        outerWidthUv = requested.outerWidthUv.finiteOr(0.04f).coerceIn(0.0f, 0.25f),
        outerCurve = requested.outerCurve.finiteOr(1.6f).coerceIn(0.25f, 6.0f),
        outerThresholdR = requested.outerThresholdR.unit(),
        outerThresholdG = requested.outerThresholdG.unit(),
        outerThresholdB = requested.outerThresholdB.unit(),
        outerSoftness = requested.outerSoftness.finiteOr(0.12f).coerceIn(0.001f, 0.5f),
        outerStrength = requested.outerStrength.unit(),
        outerCycleAmplitude = requested.outerCycleAmplitude.finiteOr(0.0f).coerceIn(0.0f, 0.5f),
        outerCycleHz = requested.outerCycleHz.finiteOr(0.10f).coerceIn(0.0f, 4.0f),
        outerMotionGain = requested.outerMotionGain.finiteOr(0.0f).coerceIn(-0.5f, 0.5f),
        innerChannelDynamics = normalizeChannelDynamics(requested.innerChannelDynamics),
        outerChannelDynamics = normalizeChannelDynamics(requested.outerChannelDynamics),
    )
  }

  fun markerFields(configuration: PrivateLayerZoneCompositor): String {
    val value = normalize(configuration)
    val transparentUnderlayRequested =
        value.outerTargetMode ==
            PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo
    return "projectionZoneCompositorMode=${PrivateLayerZoneCompositorControls.coverageToken(value.coverageMode)} " +
        "projectionRegionContract=v${value.regionContractVersion} " +
        "projectionBufferGeometry=${PrivateLayerZoneCompositorControls.bufferGeometryToken(value.bufferGeometryMode)} " +
        "projectionBufferStaticWidthUv=${value.bufferStaticWidthUv} " +
        "projectionBufferFill=${PrivateLayerZoneCompositorControls.bufferFillToken(value.bufferFillMode)} " +
        "projectionStretchExtent=${PrivateLayerZoneCompositorControls.stretchExtentToken(value.stretchExtentMode)} " +
        "projectionZoneStretchSource=${PrivateLayerZoneCompositorControls.sourceToken(value.stretchSource)} " +
        "projectionZoneStretchMapping=${PrivateLayerZoneCompositorControls.mappingToken(value.stretchMapping)} " +
        "projectionZoneEffectEdgeGuardEnabled=${value.projectionEffectEdgeGuardEnabled} " +
        "projectionZoneStretchOptionFlags=${value.stretchOptionFlags} " +
        "projectionZoneInnerSignal=${PrivateLayerZoneCompositorControls.signalToken(value.innerSignal)} " +
        "projectionZoneOuterSignal=${PrivateLayerZoneCompositorControls.signalToken(value.outerSignal)} " +
        "projectionZoneOuterTarget=${PrivateLayerZoneCompositorControls.outerTargetToken(value.outerTargetMode)} " +
        "projectionZoneOuterUnderlaySupported=${PrivateLayerZoneCompositorControls.transparentSpatialVideoSupported(value)} " +
        "projectionZoneOuterAlphaDriver=${PrivateLayerZoneCompositorControls.regionDriverToken(value.outerChannelDynamics.regionDriver)} " +
        channelMarkerFields("Inner", value.innerChannelDynamics) +
        channelMarkerFields("Outer", value.outerChannelDynamics) +
        "projectionZoneDynamicGuardAware=true projectionZoneProjectionScaleAware=true " +
        "projectionZoneGeometryOrder=user-scale-then-dynamic-core " +
        "projectionZoneSyntheticSourceIsolation=${value.debugMode == PrivateLayerZoneCompositorControls.debugRegions} " +
        "projectionZoneSyntheticDisplacementSuppressed=${value.debugMode == PrivateLayerZoneCompositorControls.debugRegions} " +
        "projectionZoneUnsampledOuterData=${transparentUnderlayRequested}"
  }

  private fun normalizeChannelDynamics(
      requested: PrivateLayerZoneChannelDynamics
  ): PrivateLayerZoneChannelDynamics =
      requested.copy(
          applicationMode = requested.applicationMode.coerceIn(0, 2),
          sourceChoice = requested.sourceChoice.coerceIn(0, 2),
          regionDriver = requested.regionDriver.coerceIn(0, 4),
          strengthR = requested.strengthR.unitZero(),
          strengthG = requested.strengthG.unitZero(),
          strengthB = requested.strengthB.unitZero(),
          cycleAmplitudeR = requested.cycleAmplitudeR.amplitude(),
          cycleAmplitudeG = requested.cycleAmplitudeG.amplitude(),
          cycleAmplitudeB = requested.cycleAmplitudeB.amplitude(),
          cycleHzR = requested.cycleHzR.frequency(0.12f),
          cycleHzG = requested.cycleHzG.frequency(0.17f),
          cycleHzB = requested.cycleHzB.frequency(0.23f),
          cyclePhaseR = requested.cyclePhaseR.phase(),
          cyclePhaseG = requested.cyclePhaseG.phase(),
          cyclePhaseB = requested.cyclePhaseB.phase(),
      )

  private fun channelMarkerFields(
      seam: String,
      dynamics: PrivateLayerZoneChannelDynamics,
  ): String =
      "projectionZone${seam}Application=${PrivateLayerZoneCompositorControls.applicationToken(dynamics.applicationMode)} " +
          "projectionZone${seam}ColorSource=${PrivateLayerZoneCompositorControls.blendSourceToken(dynamics.sourceChoice)} " +
          "projectionZone${seam}RegionDriver=${PrivateLayerZoneCompositorControls.regionDriverToken(dynamics.regionDriver)} " +
          "projectionZone${seam}StrengthRgb=${dynamics.strengthR},${dynamics.strengthG},${dynamics.strengthB} " +
          "projectionZone${seam}CycleAmplitudeRgb=${dynamics.cycleAmplitudeR},${dynamics.cycleAmplitudeG},${dynamics.cycleAmplitudeB} " +
          "projectionZone${seam}CycleHzRgb=${dynamics.cycleHzR},${dynamics.cycleHzG},${dynamics.cycleHzB} " +
          "projectionZone${seam}CyclePhaseTurnsRgb=${dynamics.cyclePhaseR},${dynamics.cyclePhaseG},${dynamics.cyclePhaseB} "

  private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

  private fun Float.unit(): Float = finiteOr(0.5f).coerceIn(0.0f, 1.0f)

  private fun Float.unitZero(): Float = finiteOr(0.0f).coerceIn(0.0f, 1.0f)

  private fun Float.amplitude(): Float = finiteOr(0.0f).coerceIn(0.0f, 0.5f)

  private fun Float.frequency(fallback: Float): Float = finiteOr(fallback).coerceIn(0.0f, 4.0f)

  private fun Float.phase(): Float = finiteOr(0.0f).coerceIn(-4.0f, 4.0f)
}

internal object PrivateLayerZoneCompositorPanelBridge {
  var configuration: PrivateLayerZoneCompositor by
      mutableStateOf(PrivateLayerZoneCompositorControls.legacyOff)
    private set

  private var submitter:
      ((PrivateLayerZoneCompositor, String) -> PrivateLayerZoneCompositor)? = null

  fun bind(
      initial: PrivateLayerZoneCompositor,
      submit: (PrivateLayerZoneCompositor, String) -> PrivateLayerZoneCompositor,
  ) {
    configuration = PrivateLayerZoneCompositorModule.normalize(initial)
    submitter = submit
  }

  fun submit(requested: PrivateLayerZoneCompositor, source: String): PrivateLayerZoneCompositor {
    val normalized = PrivateLayerZoneCompositorModule.normalize(requested)
    configuration = submitter?.invoke(normalized, source) ?: normalized
    return configuration
  }
}
