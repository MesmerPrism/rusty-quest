package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import kotlin.math.abs

internal object VrStrobeDistancePolicy {
  const val DEFAULT_METERS = 4.00f
  const val MIN_METERS = 1.05f
  const val MAX_METERS = 4.00f
  const val RATE_METERS_PER_SECOND = 0.55f

  fun apply(currentMeters: Float, stickY: Float, deltaSeconds: Float): Float =
      (currentMeters -
              stickY.coerceIn(-1f, 1f) *
                  RATE_METERS_PER_SECOND *
                  deltaSeconds.coerceIn(0f, 0.05f))
          .coerceIn(MIN_METERS, MAX_METERS)
}

internal object VrStrobeRightControllerSamplePolicy {
  fun isValid(
      localControllerType: String,
      localAttachmentType: String,
      avatarControllerType: String,
  ): Boolean =
      (localControllerType == "CONTROLLER" && localAttachmentType == "right_controller") ||
          avatarControllerType == "CONTROLLER"
}

internal object VrStrobeLeftControllerSamplePolicy {
  fun isValid(
      localControllerType: String,
      localAttachmentType: String,
      avatarControllerType: String,
  ): Boolean =
      (localControllerType == "CONTROLLER" && localAttachmentType == "left_controller") ||
          avatarControllerType == "CONTROLLER"
}

internal data class VrStrobeControllerAxes(
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
)

internal data class VrStrobeControllerInputBindings(
    val featureEnabled: () -> Boolean,
    val stimulusSelected: () -> Boolean,
    val randomizeActive: (String) -> VrStrobeSafetySnapshot,
    val storeActive: (String) -> Unit,
    val togglePanel: (String) -> Unit,
    val cyclePreset: (Int, String) -> Unit,
    val curvedMode: () -> Boolean,
    val adjustDistance: (Float, Float, String) -> Unit,
    val adjustConcavity: (Float, Float, String) -> Unit,
    val monotonicNowMs: () -> Long,
    val marker: (String) -> Unit,
)

internal class VrStrobeControllerInputCoordinator(
    private val bindings: VrStrobeControllerInputBindings,
) {
  private val primaryLatch = StableButtonPressLatch()
  private val secondaryLatch = StableButtonPressLatch()
  private val storeLatch = StableButtonPressLatch()
  private var horizontalFlickArmed = true
  private var lastHorizontalActionMs = Long.MIN_VALUE
  private var lastPrimaryActionMs = Long.MIN_VALUE
  private var lastSecondaryActionMs = Long.MIN_VALUE
  private var lastStoreActionMs = Long.MIN_VALUE
  private var lastAxesSampleMs = 0L
  private var primaryPressId = 0L

  fun handleSnapshot(
      axes: VrStrobeControllerAxes,
      primaryDown: Boolean,
      secondaryDown: Boolean,
      storeDown: Boolean,
      rightControllerSampleValid: Boolean,
      storeControllerSampleValid: Boolean,
      inputSource: String,
      storeInputSource: String,
  ): Boolean {
    if (!bindings.featureEnabled()) return false
    val nowMs = bindings.monotonicNowMs()
    if (secondaryLatch.update(secondaryDown, rightControllerSampleValid, nowMs)) {
      dispatchSecondary(inputSource, nowMs)
    }
    if (primaryLatch.update(primaryDown, rightControllerSampleValid, nowMs)) {
      dispatchPrimary(inputSource, nowMs, "spatial-sdk-controller-snapshot")
    }
    if (storeLatch.update(storeDown, storeControllerSampleValid, nowMs)) {
      dispatchStore(storeInputSource, nowMs, "spatial-sdk-controller-snapshot")
    }
    handleAxes(axes, inputSource)
    return true
  }

  fun handlePrimary(inputSource: String, detail: String): Boolean {
    if (!bindings.featureEnabled()) return false
    val nowMs = bindings.monotonicNowMs()
    primaryLatch.consumeExternalPress()
    bindings.marker(
        "channel=spatial-vr-strobe status=right-a-ingress-observed " +
            "inputSource=${inputSource.replace(' ', '_')} " +
            "detail=${detail.replace(' ', '_')} " +
            "inputObservation=android-controller-event " +
            "actionAuthority=vr-strobe-controller-input-coordinator"
    )
    return dispatchPrimary(inputSource, nowMs, "android-controller-event")
  }

  fun handleSecondary(inputSource: String): Boolean {
    if (!bindings.featureEnabled()) return false
    val nowMs = bindings.monotonicNowMs()
    secondaryLatch.consumeExternalPress()
    return dispatchSecondary(inputSource, nowMs)
  }

  fun handleStore(inputSource: String, detail: String): Boolean {
    if (!bindings.featureEnabled() || !bindings.stimulusSelected()) return false
    val nowMs = bindings.monotonicNowMs()
    storeLatch.consumeExternalPress()
    bindings.marker(
        "channel=spatial-vr-strobe status=left-primary-store-ingress-observed " +
            "inputSource=${inputSource.replace(' ', '_')} " +
            "detail=${detail.replace(' ', '_')} " +
            "inputObservation=android-controller-event " +
            "actionAuthority=vr-strobe-controller-input-coordinator"
    )
    return dispatchStore(inputSource, nowMs, "android-controller-event")
  }

  fun handleAxes(axes: VrStrobeControllerAxes, inputSource: String): Boolean {
    if (!bindings.featureEnabled()) return false
    val nowMs = bindings.monotonicNowMs()
    val deltaSeconds =
        if (lastAxesSampleMs == 0L || nowMs - lastAxesSampleMs > AXES_IDLE_RESET_MS) {
          DEFAULT_FRAME_SECONDS
        } else {
          ((nowMs - lastAxesSampleMs).coerceAtLeast(0L) / 1_000f).coerceAtMost(MAX_FRAME_SECONDS)
        }
    lastAxesSampleMs = nowMs

    val allHorizontalReleased =
        abs(axes.leftX) <= HORIZONTAL_RELEASE_THRESHOLD &&
            abs(axes.rightX) <= HORIZONTAL_RELEASE_THRESHOLD
    if (allHorizontalReleased) horizontalFlickArmed = true

    if (!bindings.stimulusSelected()) return true

    val horizontal = horizontalCandidate(axes)
    if (horizontal != null) {
      if (
          horizontalFlickArmed &&
              debounced(nowMs, lastHorizontalActionMs)
      ) {
        horizontalFlickArmed = false
        lastHorizontalActionMs = nowMs
        bindings.cyclePreset(if (horizontal < 0f) -1 else 1, inputSource)
      }
      return true
    }

    if (bindings.curvedMode()) {
      if (verticalDominant(axes.leftX, axes.leftY)) {
        bindings.adjustConcavity(axes.leftY, deltaSeconds, inputSource)
      }
      if (verticalDominant(axes.rightX, axes.rightY)) {
        bindings.adjustDistance(axes.rightY, deltaSeconds, inputSource)
      }
    } else {
      val selected = dominantStick(axes)
      if (verticalDominant(selected.first, selected.second)) {
        bindings.adjustDistance(selected.second, deltaSeconds, inputSource)
      }
    }
    return true
  }

  private fun horizontalCandidate(axes: VrStrobeControllerAxes): Float? =
      listOf(axes.leftX to axes.leftY, axes.rightX to axes.rightY)
          .filter { (x, y) ->
            abs(x) >= HORIZONTAL_FLICK_THRESHOLD && abs(x) > abs(y) + AXIS_DOMINANCE_BIAS
          }
          .maxByOrNull { (x, _) -> abs(x) }
          ?.first

  private fun verticalDominant(x: Float, y: Float): Boolean =
      abs(y) >= VERTICAL_DEADZONE && abs(y) >= abs(x)

  private fun dominantStick(axes: VrStrobeControllerAxes): Pair<Float, Float> {
    val leftMagnitude = maxOf(abs(axes.leftX), abs(axes.leftY))
    val rightMagnitude = maxOf(abs(axes.rightX), abs(axes.rightY))
    return if (rightMagnitude > leftMagnitude) {
      axes.rightX to axes.rightY
    } else {
      axes.leftX to axes.leftY
    }
  }

  private fun debounced(nowMs: Long, previousMs: Long): Boolean =
      previousMs == Long.MIN_VALUE || nowMs - previousMs >= ACTION_DEBOUNCE_MS

  private fun dispatchPrimary(inputSource: String, nowMs: Long, inputObservation: String): Boolean {
    if (!bindings.stimulusSelected()) {
      bindings.marker(
          "channel=spatial-vr-strobe status=right-a-press-rejected " +
              "inputSource=${inputSource.replace(' ', '_')} reason=stimulus-not-selected"
      )
      return false
    }
    if (routeDuplicateSuppressed(nowMs, lastPrimaryActionMs)) {
      bindings.marker(
          "channel=spatial-vr-strobe status=right-a-press-suppressed " +
              "inputSource=${inputSource.replace(' ', '_')} " +
              "inputObservation=$inputObservation reason=duplicate-edge " +
              "actionAuthority=vr-strobe-controller-input-coordinator"
      )
      return true
    }
    lastPrimaryActionMs = nowMs
    val pressId = ++primaryPressId
    val transactionSource = "$inputSource-right-a-$pressId"
    bindings.marker(
        "channel=spatial-vr-strobe status=right-a-press-dispatched " +
            "pressId=$pressId inputSource=${inputSource.replace(' ', '_')} " +
            "inputObservation=$inputObservation action=randomize " +
            "actionAuthority=vr-strobe-controller-input-coordinator"
    )
    val result = bindings.randomizeActive(transactionSource)
    bindings.marker(
        "channel=spatial-vr-strobe status=right-a-press-complete " +
            "pressId=$pressId inputSource=${inputSource.replace(' ', '_')} " +
            "safetyState=${result.state.name.lowercase()} " +
            "stimulusRevision=${result.stimulusRevision} rejectionReason=${result.rejectionReason}"
    )
    return true
  }

  private fun dispatchSecondary(inputSource: String, nowMs: Long): Boolean {
    if (!routeDuplicateSuppressed(nowMs, lastSecondaryActionMs)) {
      lastSecondaryActionMs = nowMs
      bindings.togglePanel(inputSource)
    }
    return true
  }

  private fun dispatchStore(inputSource: String, nowMs: Long, inputObservation: String): Boolean {
    if (!bindings.stimulusSelected()) return false
    if (!routeDuplicateSuppressed(nowMs, lastStoreActionMs)) {
      lastStoreActionMs = nowMs
      bindings.marker(
          "channel=spatial-vr-strobe status=left-primary-store-dispatched " +
              "inputSource=${inputSource.replace(' ', '_')} " +
              "inputObservation=$inputObservation action=store-profile " +
              "actionAuthority=vr-strobe-controller-input-coordinator"
      )
      bindings.storeActive(inputSource)
    }
    return true
  }

  private fun routeDuplicateSuppressed(nowMs: Long, previousMs: Long): Boolean =
      previousMs != Long.MIN_VALUE && nowMs - previousMs < DUPLICATE_ROUTE_WINDOW_MS

  private class StableButtonPressLatch {
    private var armed = true
    private var releaseCandidateSinceMs = Long.MIN_VALUE

    fun update(down: Boolean, sampleValid: Boolean, nowMs: Long): Boolean {
      if (!sampleValid) return false
      if (down) {
        releaseCandidateSinceMs = Long.MIN_VALUE
        if (!armed) return false
        armed = false
        return true
      }
      if (armed) return false
      if (releaseCandidateSinceMs == Long.MIN_VALUE) {
        releaseCandidateSinceMs = nowMs
      } else if (nowMs - releaseCandidateSinceMs >= RELEASE_CONFIRM_MS) {
        armed = true
        releaseCandidateSinceMs = Long.MIN_VALUE
      }
      return false
    }

    fun consumeExternalPress() {
      armed = false
      releaseCandidateSinceMs = Long.MIN_VALUE
    }
  }

  companion object {
    const val MODULE_ID = "vr-strobe-controller-input-coordinator"
    const val HORIZONTAL_FLICK_THRESHOLD = 0.72f
    const val HORIZONTAL_RELEASE_THRESHOLD = 0.25f
    const val VERTICAL_DEADZONE = 0.25f
    const val ACTION_DEBOUNCE_MS = 250L
    const val RELEASE_CONFIRM_MS = 60L
    const val DUPLICATE_ROUTE_WINDOW_MS = 120L
    private const val AXIS_DOMINANCE_BIAS = 0.08f
    private const val AXES_IDLE_RESET_MS = 100L
    private const val DEFAULT_FRAME_SECONDS = 1f / 72f
    private const val MAX_FRAME_SECONDS = 0.05f
  }
}
