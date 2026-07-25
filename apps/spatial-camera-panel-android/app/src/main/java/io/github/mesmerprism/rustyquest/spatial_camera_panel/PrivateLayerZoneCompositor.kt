package io.github.mesmerprism.rustyquest.spatial_camera_panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class PrivateLayerZoneCompositor(
    val coverageMode: Int = PrivateLayerZoneCompositorControls.coverageOff,
    val stretchSource: Int = PrivateLayerZoneCompositorControls.sourceProcessed,
    val debugMode: Int = PrivateLayerZoneCompositorControls.debugOff,
    val stretchMapping: Int = PrivateLayerZoneCompositorControls.mappingMirroredLens,
    val edgeInsetUv: Float = 0.14f,
    val maxInsetUv: Float = 0.58f,
    val stretchCurve: Float = 0.22f,
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
)

internal object PrivateLayerZoneCompositorControls {
  const val coverageOff = 0
  const val coverageDynamicBuffer = 1
  const val coverageReplaceVideo = 2
  const val sourceRaw = 0
  const val sourceProcessed = 1
  const val sourceMixed = 2
  const val mappingRectangularLinear = 0
  const val mappingMirroredLens = 1
  const val signalFlat = 0
  const val signalRgb = 1
  const val signalLuma = 2
  const val signalChroma = 3
  const val signalDifference = 4
  const val debugOff = 0
  const val debugRegions = 1
  const val debugSampleUv = 2

  val legacyOff = PrivateLayerZoneCompositor()
  val nativeBuffer =
      PrivateLayerZoneCompositor(
          coverageMode = coverageDynamicBuffer,
          stretchSource = sourceProcessed,
          stretchMapping = mappingMirroredLens,
      )
  val linearBuffer =
      nativeBuffer.copy(
          stretchMapping = mappingRectangularLinear,
          edgeInsetUv = 0.015f,
          maxInsetUv = 0.14f,
          stretchCurve = 1.6f,
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
          outerSignal = signalFlat,
          outerStrength = 0.0f,
          outerCycleAmplitude = 0.0f,
          outerMotionGain = 0.0f,
      )

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

  fun mappingToken(mapping: Int): String =
      when (mapping) {
        mappingMirroredLens -> "mirrored-lens-native"
        else -> "rectangular-linear"
      }

  fun withMapping(
      configuration: PrivateLayerZoneCompositor,
      mapping: Int,
  ): PrivateLayerZoneCompositor =
      if (mapping == mappingMirroredLens) {
        configuration.copy(
            stretchMapping = mappingMirroredLens,
            edgeInsetUv = 0.14f,
            maxInsetUv = 0.58f,
            stretchCurve = 0.22f,
        )
      } else {
        configuration.copy(
            stretchMapping = mappingRectangularLinear,
            edgeInsetUv = 0.015f,
            maxInsetUv = 0.14f,
            stretchCurve = 1.6f,
        )
      }

  fun signalToken(signal: Int): String =
      when (signal) {
        signalRgb -> "rgb"
        signalLuma -> "luma"
        signalChroma -> "chroma"
        signalDifference -> "difference"
        else -> "flat"
      }
}

internal object PrivateLayerZoneCompositorModule {
  fun normalize(requested: PrivateLayerZoneCompositor): PrivateLayerZoneCompositor {
    val stretchMapping = requested.stretchMapping.coerceIn(0, 1)
    val parameterA =
        if (stretchMapping == PrivateLayerZoneCompositorControls.mappingMirroredLens) {
          requested.edgeInsetUv.finiteOr(0.14f).coerceIn(0.0f, 0.55f)
        } else {
          requested.edgeInsetUv.finiteOr(0.015f).coerceIn(0.0f, 0.49f)
        }
    val parameterB =
        if (stretchMapping == PrivateLayerZoneCompositorControls.mappingMirroredLens) {
          requested.maxInsetUv.finiteOr(0.58f).coerceIn(0.0f, 1.50f)
        } else {
          requested.maxInsetUv.finiteOr(0.14f).coerceIn(parameterA, 0.49f)
        }
    val parameterC =
        if (stretchMapping == PrivateLayerZoneCompositorControls.mappingMirroredLens) {
          requested.stretchCurve.finiteOr(0.22f).coerceIn(0.0f, 0.75f)
        } else {
          requested.stretchCurve.finiteOr(1.6f).coerceIn(0.25f, 6.0f)
        }
    return requested.copy(
        coverageMode = requested.coverageMode.coerceIn(0, 2),
        stretchSource = requested.stretchSource.coerceIn(0, 2),
        debugMode = requested.debugMode.coerceIn(0, 2),
        stretchMapping = stretchMapping,
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
    )
  }

  fun markerFields(configuration: PrivateLayerZoneCompositor): String {
    val value = normalize(configuration)
    return "projectionZoneCompositorMode=${PrivateLayerZoneCompositorControls.coverageToken(value.coverageMode)} " +
        "projectionZoneStretchSource=${PrivateLayerZoneCompositorControls.sourceToken(value.stretchSource)} " +
        "projectionZoneStretchMapping=${PrivateLayerZoneCompositorControls.mappingToken(value.stretchMapping)} " +
        "projectionZoneInnerSignal=${PrivateLayerZoneCompositorControls.signalToken(value.innerSignal)} " +
        "projectionZoneOuterSignal=${PrivateLayerZoneCompositorControls.signalToken(value.outerSignal)} " +
        "projectionZoneDynamicGuardAware=true projectionZoneProjectionScaleAware=true " +
        "projectionZoneGeometryOrder=user-scale-then-dynamic-core"
  }

  private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

  private fun Float.unit(): Float = finiteOr(0.5f).coerceIn(0.0f, 1.0f)
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
