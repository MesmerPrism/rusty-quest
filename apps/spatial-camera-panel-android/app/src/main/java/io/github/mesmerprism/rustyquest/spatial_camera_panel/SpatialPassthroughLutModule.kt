package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.Lut
import com.meta.spatial.runtime.Scene
import java.util.Locale
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class SpatialPassthroughLutUpdate(
    val requested: Boolean,
    val systemPassthroughEnabled: Boolean,
    val lutApplied: Boolean,
    val phase: Float,
    val amplitude: Float,
)

/**
 * Public, effect-agnostic diagnostic styling for the passthrough layer owned by Spatial SDK.
 *
 * Spatial SDK submits the visible passthrough composition layer, so its Scene LUT API is the
 * correct authority. A separately-created XR_FB_passthrough layer cannot provide visual evidence
 * unless that layer is also submitted by the SDK's xrEndFrame owner.
 */
internal object SpatialPassthroughLutModule {
  const val MODULE_ID = "spatial-passthrough-lut"
  const val LUT_DIMENSION = 16
  const val UPDATE_HZ = 5.0f
  const val COLOR_PHASE_HZ = 0.125f
  const val AMPLITUDE_OSCILLATOR_HZ = 1.35f
  const val MIN_COLOR_AMPLITUDE = 0.90f
  const val MAX_COLOR_AMPLITUDE = 1.00f
  const val BLACK_LEVEL_CUTOFF = 0.055f
  const val SATURATED_COLOR_BAND_COUNT = 6
  const val UPDATE_PERIOD_MS = 200L
  const val MARKER_INTERVAL_MS = 2_000L

  private val saturatedColorStops =
      arrayOf(
          intArrayOf(0, 255, 13),
          intArrayOf(255, 255, 0),
          intArrayOf(255, 0, 0),
          intArrayOf(255, 0, 220),
          intArrayOf(25, 35, 255),
          intArrayOf(0, 235, 255),
      )

  internal data class Snapshot(val phase: Float, val amplitude: Float)

  fun snapshot(elapsedMs: Long): Snapshot {
    val elapsedSeconds = elapsedMs.coerceAtLeast(0L).toDouble() / 1_000.0
    val phase = ((elapsedSeconds * COLOR_PHASE_HZ) % 1.0).toFloat()
    val oscillator =
        (0.5 + 0.5 * sin(elapsedSeconds * AMPLITUDE_OSCILLATOR_HZ * 2.0 * PI)).toFloat()
    val amplitude = MIN_COLOR_AMPLITUDE + (MAX_COLOR_AMPLITUDE - MIN_COLOR_AMPLITUDE) * oscillator
    return Snapshot(phase = phase, amplitude = amplitude)
  }

  /**
   * Reproduces the generic visual structure of the older mono-to-RGBA diagnostic:
   * a fixed black floor followed by hard, saturated, phase-shifted luminance bands.
   *
   * A point LUT cannot perform neighborhood edge detection, but these hard band boundaries make
   * scene contours visually obvious and are deliberately unlike normal camera passthrough.
   */
  fun createPosterizedColorLut(snapshot: Snapshot): Lut {
    val lut = Lut(LUT_DIMENSION)
    val denominator = (LUT_DIMENSION - 1).toFloat()
    for (sourceBlue in 0 until LUT_DIMENSION) {
      for (sourceGreen in 0 until LUT_DIMENSION) {
        for (sourceRed in 0 until LUT_DIMENSION) {
          val red = sourceRed / denominator
          val green = sourceGreen / denominator
          val blue = sourceBlue / denominator
          val luma = (0.2126f * red + 0.7152f * green + 0.0722f * blue).coerceIn(0.0f, 1.0f)
          val output = posterizedColor(luma, snapshot)
          lut.setMapping(
              sourceRed,
              sourceGreen,
              sourceBlue,
              output[0],
              output[1],
              output[2],
          )
        }
      }
    }
    return lut
  }

  fun appliedMarker(source: String, snapshot: Snapshot, systemPassthroughEnabled: Boolean): String =
      "channel=spatial-passthrough-lut status=applied " +
          "source=${activityMarkerToken(source)} " +
          "passthroughStyleOwner=spatial-sdk-system-passthrough " +
          "passthroughStyleApi=Scene.setPassthroughLUT " +
          "passthroughStyleMode=animated-posterized-mono-to-rgba-gradient " +
          "passthroughLutDimension=$LUT_DIMENSION passthroughLutEntryCount=${LUT_DIMENSION * LUT_DIMENSION * LUT_DIMENSION} " +
          "passthroughColorMapStops=black-green-yellow-red-magenta-blue-cyan " +
          "passthroughBlackLevelCutoff=${markerFloat(BLACK_LEVEL_CUTOFF)} " +
          "passthroughSaturatedColorBandCount=$SATURATED_COLOR_BAND_COUNT " +
          "passthroughColorPhase=${markerFloat(snapshot.phase)} " +
          "passthroughColorAmplitude=${markerFloat(snapshot.amplitude)} " +
          "passthroughColorPhaseHz=${markerFloat(COLOR_PHASE_HZ)} " +
          "passthroughAmplitudeOscillatorHz=${markerFloat(AMPLITUDE_OSCILLATOR_HZ)} " +
          "passthroughLutUpdateHz=${markerFloat(UPDATE_HZ)} " +
          "metaSystemPassthroughEnabled=$systemPassthroughEnabled " +
          "passthroughLutRetainedByCoordinator=true initialLutClearBeforeApply=true " +
          "nativeFbEdgeLayerVisualAuthority=false runtimeCrash=false"

  fun clearedMarker(source: String, systemPassthroughEnabled: Boolean): String =
      "channel=spatial-passthrough-lut status=cleared " +
          "source=${activityMarkerToken(source)} passthroughStyleOwner=spatial-sdk-system-passthrough " +
          "passthroughStyleApi=Scene.setPassthroughLUT passthroughStyleMode=identity-system-default " +
          "metaSystemPassthroughEnabled=$systemPassthroughEnabled runtimeCrash=false"

  fun failedMarker(source: String, error: Throwable): String =
      "channel=spatial-passthrough-lut status=apply-failed " +
          "source=${activityMarkerToken(source)} passthroughStyleOwner=spatial-sdk-system-passthrough " +
          "passthroughStyleApi=Scene.setPassthroughLUT " +
          "error=${activityMarkerToken(error.javaClass.simpleName)} " +
          "message=${activityMarkerToken(error.message ?: "none")} runtimeCrash=false"

  private fun posterizedColor(luma: Float, snapshot: Snapshot): IntArray {
    if (luma <= BLACK_LEVEL_CUTOFF) return intArrayOf(0, 0, 0)

    val normalized =
        ((luma - BLACK_LEVEL_CUTOFF) / (1.0f - BLACK_LEVEL_CUTOFF)).coerceIn(0.0f, 1.0f)
    val shifted = (normalized + snapshot.phase).mod(1.0f)
    val bandIndex =
        floor(shifted * SATURATED_COLOR_BAND_COUNT)
            .toInt()
            .coerceIn(0, SATURATED_COLOR_BAND_COUNT - 1)
    val stop = saturatedColorStops[bandIndex]
    val brightness = snapshot.amplitude * (0.88f + 0.12f * normalized)
    return intArrayOf(
        (stop[0] * brightness).roundToInt().coerceIn(0, 255),
        (stop[1] * brightness).roundToInt().coerceIn(0, 255),
        (stop[2] * brightness).roundToInt().coerceIn(0, 255),
    )
  }

  private fun markerFloat(value: Float): String = String.format(Locale.US, "%.3f", value)
}

internal class SpatialPassthroughLutCoordinator(
    private val scene: Scene,
    private val scope: CoroutineScope,
    private val elapsedRealtimeMs: () -> Long,
    private val marker: (String) -> Unit,
) {
  private var updateJob: Job? = null
  private var enabledAtMs: Long = 0L
  private var lastMarkerAtMs: Long = Long.MIN_VALUE
  private var lastSnapshot = SpatialPassthroughLutModule.Snapshot(phase = 0.0f, amplitude = 1.0f)
  private var activeLut: Lut? = null
  private var lutApplied = false

  fun update(enabled: Boolean, source: String): SpatialPassthroughLutUpdate {
    if (!enabled) {
      updateJob?.cancel()
      updateJob = null
      lutApplied = false
      runCatching { scene.setPassthroughLUT(null) }
          .onFailure { marker(SpatialPassthroughLutModule.failedMarker("$source-clear", it)) }
      activeLut = null
      val systemPassthroughEnabled = scene.isSystemPassthroughEnabled()
      marker(SpatialPassthroughLutModule.clearedMarker(source, systemPassthroughEnabled))
      return SpatialPassthroughLutUpdate(
          requested = false,
          systemPassthroughEnabled = systemPassthroughEnabled,
          lutApplied = false,
          phase = lastSnapshot.phase,
          amplitude = lastSnapshot.amplitude,
      )
    }

    scene.enablePassthrough(true)
    val existingJob = updateJob
    if (existingJob != null && existingJob.isActive && lutApplied) {
      return currentUpdate(requested = true)
    }

    existingJob?.cancel()
    enabledAtMs = elapsedRealtimeMs()
    lastMarkerAtMs = Long.MIN_VALUE
    runCatching { scene.setPassthroughLUT(null) }
        .onFailure { marker(SpatialPassthroughLutModule.failedMarker("$source-initial-clear", it)) }
    lutApplied = applyCurrentLut(source, forceMarker = true)
    if (lutApplied) {
      updateJob =
          scope.launch {
            while (isActive) {
              delay(SpatialPassthroughLutModule.UPDATE_PERIOD_MS)
              applyCurrentLut("$source-oscillator", forceMarker = false)
            }
          }
    }
    return currentUpdate(requested = true)
  }

  fun stop(source: String) {
    updateJob?.cancel()
    updateJob = null
    lutApplied = false
    runCatching { scene.setPassthroughLUT(null) }
        .onFailure { marker(SpatialPassthroughLutModule.failedMarker("$source-clear", it)) }
    activeLut = null
  }

  private fun applyCurrentLut(source: String, forceMarker: Boolean): Boolean {
    val nowMs = elapsedRealtimeMs()
    lastSnapshot = SpatialPassthroughLutModule.snapshot(nowMs - enabledAtMs)
    return runCatching {
          val lut = SpatialPassthroughLutModule.createPosterizedColorLut(lastSnapshot)
          activeLut = lut
          scene.setPassthroughLUT(lut)
          val shouldMark =
              forceMarker ||
                  lastMarkerAtMs == Long.MIN_VALUE ||
                  nowMs - lastMarkerAtMs >= SpatialPassthroughLutModule.MARKER_INTERVAL_MS
          if (shouldMark) {
            lastMarkerAtMs = nowMs
            marker(
                SpatialPassthroughLutModule.appliedMarker(
                    source,
                    lastSnapshot,
                    scene.isSystemPassthroughEnabled(),
                )
            )
          }
          true
        }
        .getOrElse {
          marker(SpatialPassthroughLutModule.failedMarker(source, it))
          false
        }
  }

  private fun currentUpdate(requested: Boolean): SpatialPassthroughLutUpdate =
      SpatialPassthroughLutUpdate(
          requested = requested,
          systemPassthroughEnabled = scene.isSystemPassthroughEnabled(),
          lutApplied = lutApplied,
          phase = lastSnapshot.phase,
          amplitude = lastSnapshot.amplitude,
      )
}
