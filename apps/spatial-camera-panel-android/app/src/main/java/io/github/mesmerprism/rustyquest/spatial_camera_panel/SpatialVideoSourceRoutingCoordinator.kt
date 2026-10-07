package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Intent

internal const val EXTRA_VIDEO_SOURCE_SELECTION = "rustyquest.spatial.video.source_selection"

/** Source selection is Activity-owned and deliberately absent from profile/effect persistence. */
internal enum class SpatialVideoSource(val token: String, val nativeCode: Long) {
  Disabled("disabled", 0L),
  Local("local", 1L),
  Peer("peer", 2L),
  ;

  companion object {
    fun fromNativeCode(value: Long): SpatialVideoSource? = entries.firstOrNull { it.nativeCode == value }
  }
}

internal sealed interface SpatialProjectionProducerState {
  data object NoProducerOwned : SpatialProjectionProducerState
  data class ProducerAuthorityUnavailable(val routeGeneration: Long) :
      SpatialProjectionProducerState
  data class ProducerActive(val session: Long, val epoch: Long) : SpatialProjectionProducerState
  data class CleanupPending(val session: Long, val epoch: Long, val routeGeneration: Long) :
      SpatialProjectionProducerState
  data class ProducerInactive(val session: Long, val epoch: Long, val routeGeneration: Long) :
      SpatialProjectionProducerState
}

internal enum class SpatialVideoSourceResult(val nativeCode: Long) {
  Pending(0L), Effective(1L), Inactive(2L), Rejected(3L), Lost(4L), Unavailable(5L);

  companion object {
    fun fromNativeCode(value: Long): SpatialVideoSourceResult? = entries.firstOrNull { it.nativeCode == value }
  }
}

internal enum class SpatialVideoSourceReason(val token: String, val nativeCode: Long) {
  None("none", 0L),
  CarrierUnavailable("carrier-unavailable", 1L),
  ProviderSelectionRejected("provider-selection-rejected", 2L),
  PeerSettingsInvalid("peer-settings-invalid", 3L),
  ProducerCleanupRequired("producer-cleanup-required", 4L),
  ProducerCleanupForeign("producer-cleanup-foreign", 5L),
  ProducerCleanupDenied("producer-cleanup-denied", 6L),
  DecoderReplacementFailed("decoder-replacement-failed", 7L),
  AcquisitionStopFailed("acquisition-stop-failed", 8L),
  ReceiptUnavailable("receipt-unavailable", 9L),
  ReceiptForeign("receipt-foreign", 10L),
  ReceiptStale("receipt-stale", 11L),
  ActiveSourceLost("active-source-lost", 12L),
  ReceiptMalformed("receipt-malformed", 13L),
  ;

  companion object {
    fun fromNativeCode(value: Long): SpatialVideoSourceReason? = entries.firstOrNull { it.nativeCode == value }
  }
}

internal object SpatialVideoSourceStage {
  const val ProviderSelected = 1L shl 0
  const val DecoderBound = 1L shl 1
  const val ReaderBound = 1L shl 2
  const val SurfaceBound = 1L shl 3
  const val PairRetired = 1L shl 4
  const val HardwareBufferImported = 1L shl 5
  const val AcquisitionActive = 1L shl 6
  const val AcquisitionStopped = 1L shl 7
  const val ProducerInactive = 1L shl 8
  const val CommonGraphAttached = 1L shl 9

  fun requiredFor(source: SpatialVideoSource): Long = when (source) {
    SpatialVideoSource.Disabled -> AcquisitionStopped
    SpatialVideoSource.Local ->
        ProviderSelected or ReaderBound or SurfaceBound or HardwareBufferImported or AcquisitionActive
    SpatialVideoSource.Peer -> ProviderSelected or DecoderBound or ReaderBound or SurfaceBound or
        PairRetired or HardwareBufferImported or AcquisitionActive or CommonGraphAttached
  }
}

/** Exact native source-owner receipt; acquisitionTimeNs uses CLOCK_MONOTONIC. */
internal data class SpatialVideoSourceNativeReadback(
    val routeGeneration: Long,
    val source: SpatialVideoSource,
    val decoderToken: Long,
    val readerGeneration: Long,
    val launchChallenge: Long,
    val surfaceGeneration: Long,
    val pairGeneration: Long,
    val importGeneration: Long,
    val acquisitionTimeNs: Long,
    val stages: Long,
    val result: SpatialVideoSourceResult,
    val reason: SpatialVideoSourceReason,
    val cameraStartRequested: Boolean,
    val producerSession: Long,
    val producerEpoch: Long,
) {
  fun hasStages(required: Long): Boolean = stages and required == required
}

internal data class SpatialVideoSourceNativeRequest(
    val routeGeneration: Long,
    val source: SpatialVideoSource,
    val decoderToken: Long = 0L,
    val readerGeneration: Long = 0L,
    val launchChallenge: Long,
    val surfaceGeneration: Long,
    val pairGeneration: Long = 0L,
    val importGeneration: Long = 0L,
    val acquisitionTimeNs: Long = 0L,
    val requiredStages: Long = SpatialVideoSourceStage.requiredFor(source),
    val cameraStartAllowed: Boolean = source == SpatialVideoSource.Local,
)

/** Primitive JNI wire shape shared by request and readback. Do not reorder words. */
internal object SpatialVideoSourceNativeAbi {
  const val VERSION = 1L
  const val WORD_COUNT = 16

  fun encodeRequest(value: SpatialVideoSourceNativeRequest): LongArray = longArrayOf(
      VERSION, value.routeGeneration, value.source.nativeCode, value.decoderToken,
      value.readerGeneration, value.launchChallenge, value.surfaceGeneration,
      value.pairGeneration, value.importGeneration, value.acquisitionTimeNs,
      value.requiredStages, SpatialVideoSourceResult.Pending.nativeCode,
      SpatialVideoSourceReason.None.nativeCode, if (value.cameraStartAllowed) 1L else 0L,
      0L, 0L,
  )

  fun decodeReadback(words: LongArray?): SpatialVideoSourceNativeReadback? {
    if (words == null || words.size != WORD_COUNT || words[0] != VERSION) return null
    val source = SpatialVideoSource.fromNativeCode(words[2]) ?: return null
    val result = SpatialVideoSourceResult.fromNativeCode(words[11]) ?: return null
    val reason = SpatialVideoSourceReason.fromNativeCode(words[12]) ?: return null
    if (words[13] !in 0L..1L) return null
    return SpatialVideoSourceNativeReadback(
        routeGeneration = words[1], source = source, decoderToken = words[3],
        readerGeneration = words[4], launchChallenge = words[5], surfaceGeneration = words[6],
        pairGeneration = words[7], importGeneration = words[8], acquisitionTimeNs = words[9],
        stages = words[10], result = result, reason = reason,
        cameraStartRequested = words[13] == 1L, producerSession = words[14],
        producerEpoch = words[15],
    )
  }
}

internal data class SpatialVideoSourceCarrierContext(
    val launchChallenge: Long,
    val surfaceGeneration: Long,
)

internal interface SpatialVideoSourceExecutionAdapter {
  fun producerState(): SpatialProjectionProducerState
  fun requestProducerCleanup(
      routeGeneration: Long,
      producer: SpatialProjectionProducerState.ProducerActive,
  ): SpatialProjectionProducerState
  fun readProducerCleanup(
      pending: SpatialProjectionProducerState.CleanupPending,
  ): SpatialProjectionProducerState?
  fun selectNativeProvider(
      request: SpatialVideoSourceNativeRequest,
      context: SpatialVideoSourceCarrierContext,
  ): SpatialVideoSourceNativeReadback
  fun readNativeSource(routeGeneration: Long): SpatialVideoSourceNativeReadback?
  fun stagePeerSettings(settings: SpatialVideoProjectionSettings, routeGeneration: Long): Boolean
  fun replacePeerSettings(
      settings: SpatialVideoProjectionSettings,
      routeGeneration: Long,
      reason: String,
  ): Boolean
  fun preparePeerDecoderOwnership(routeGeneration: Long, reason: String): Boolean
  fun stopSourceAcquisition(source: SpatialVideoSource, routeGeneration: Long, reason: String): Boolean
  fun updateSourceOwnerDemand(required: Boolean, reason: String)
}

internal data class SpatialVideoSourceRoutingState(
    val requested: SpatialVideoSource = SpatialVideoSource.Local,
    val pending: SpatialVideoSource? = SpatialVideoSource.Local,
    val effective: SpatialVideoSource = SpatialVideoSource.Disabled,
    val failed: SpatialVideoSource? = null,
    val failureReason: SpatialVideoSourceReason = SpatialVideoSourceReason.None,
    val generation: Long = 0L,
    val pendingSinceNs: Long = 0L,
    val sourceOwnerDemand: Boolean = true,
    val producerState: SpatialProjectionProducerState = SpatialProjectionProducerState.NoProducerOwned,
    val ownedAcquisition: SpatialVideoSource? = null,
    val ownedAcquisitionGeneration: Long = 0L,
    val readback: SpatialVideoSourceNativeReadback? = null,
) {
  val projectionInputRequired: Boolean get() = sourceOwnerDemand
}

internal object SpatialVideoSourcePollingPolicy {
  fun shouldPoll(state: SpatialVideoSourceRoutingState, carrierPresent: Boolean): Boolean =
      carrierPresent &&
          (state.pending != null || state.effective != SpatialVideoSource.Disabled ||
              state.ownedAcquisition != null)
}

/** Low-rate Activity observability: acquisition timestamps alone are not route transitions. */
internal class SpatialVideoSourceRouteTransitionMarker {
  private var lastSemanticKey: String? = null

  fun markerFor(
      state: SpatialVideoSourceRoutingState,
      reason: String,
  ): String? {
    val receipt = state.readback
    val semanticKey = listOf(
        state.requested.token, state.generation, state.pending?.token, state.effective.token,
        state.failed?.token, state.failureReason.token, receipt?.routeGeneration, receipt?.source?.token,
        receipt?.result?.name, receipt?.reason?.token, receipt?.stages, receipt?.launchChallenge,
        receipt?.surfaceGeneration, receipt?.decoderToken, receipt?.readerGeneration,
    ).joinToString("|")
    if (semanticKey == lastSemanticKey) return null
    lastSemanticKey = semanticKey
    val receiptFields = receipt?.let {
      " receiptResult=${it.result.name.lowercase()} acquisitionTimeNs=${it.acquisitionTimeNs} " +
          "decoderToken=${it.decoderToken} readerGeneration=${it.readerGeneration} " +
          "importGeneration=${it.importGeneration} pairGeneration=${it.pairGeneration} " +
          "stages=${it.stages} cameraStartRequested=${it.cameraStartRequested}"
    } ?: " receiptResult=none"
    return "channel=spatial-video-source status=routing-transition " +
        "reason=${activityMarkerToken(reason)} source=${state.requested.token} " +
        "generation=${state.generation} pending=${state.pending?.token ?: "none"} " +
        "effective=${state.effective.token} failed=${state.failed?.token ?: "none"} " +
        "failureReason=${state.failureReason.token}$receiptFields"
  }
}

/** Native timestamps and this clock must both use CLOCK_MONOTONIC. */
internal object SpatialVideoSourceFreshness {
  fun isStale(nowNs: Long, acquisitionTimeNs: Long, maxAgeNs: Long): Boolean =
      acquisitionTimeNs <= 0L || nowNs < acquisitionTimeNs ||
          nowNs - acquisitionTimeNs > maxAgeNs
}

internal sealed class SpatialVideoSourceIntentResult {
  data object Unspecified : SpatialVideoSourceIntentResult()
  data class Selected(val source: SpatialVideoSource) : SpatialVideoSourceIntentResult()
  data class Rejected(val token: String?) : SpatialVideoSourceIntentResult()
}

internal object SpatialVideoSourceIntentParser {
  fun parse(intent: Intent?): SpatialVideoSourceIntentResult {
    if (intent == null || !intent.hasExtra(EXTRA_VIDEO_SOURCE_SELECTION)) {
      return SpatialVideoSourceIntentResult.Unspecified
    }
    return parseToken(true, intent.getStringExtra(EXTRA_VIDEO_SOURCE_SELECTION))
  }

  fun parseToken(hasExplicitExtra: Boolean, token: String?): SpatialVideoSourceIntentResult {
    if (!hasExplicitExtra) return SpatialVideoSourceIntentResult.Unspecified
    return when (token?.trim()?.lowercase()) {
      "local" -> SpatialVideoSourceIntentResult.Selected(SpatialVideoSource.Local)
      "peer" -> SpatialVideoSourceIntentResult.Selected(SpatialVideoSource.Peer)
      "disabled" -> SpatialVideoSourceIntentResult.Selected(SpatialVideoSource.Disabled)
      else -> SpatialVideoSourceIntentResult.Rejected(token)
    }
  }
}

/** Intent and latest native request share one coordinator lock but distinct generations. */
internal data class SpatialConcurrentPeerAdmissionRequest(val routingGeneration: Long, val nativeGeneration: Long)

/** Native-observed current Own actor and old-local quiescence, bound to exact intent/native route/carrier. */
internal class SpatialConcurrentPeerAdmission private constructor(
    val routingGeneration: Long, val nativeGeneration: Long, val carrier: SpatialVideoSourceCarrierContext,
    val processGeneration: Long, val sourceGeneration: Long,
) {
  companion object {
    fun observed(request: SpatialConcurrentPeerAdmissionRequest, carrier: SpatialVideoSourceCarrierContext, words: LongArray): SpatialConcurrentPeerAdmission {
      check(words.size == 5 && request.routingGeneration > 0L && request.nativeGeneration >= request.routingGeneration && carrier.launchChallenge > 0L && carrier.surfaceGeneration > 0L &&
          words[0] == request.nativeGeneration && words[1] == carrier.launchChallenge && words[2] == carrier.surfaceGeneration &&
          words[3] > 0L && words[4] > 0L) { "concurrent Own native admission unavailable" }
      return SpatialConcurrentPeerAdmission(request.routingGeneration, request.nativeGeneration, carrier, words[3], words[4])
    }
  }
}

internal class SpatialVideoSourceRoutingCoordinator(
    private val execution: SpatialVideoSourceExecutionAdapter,
    private val monotonicNowNs: () -> Long,
) {
  private val stateLock = Any()
  private var state = SpatialVideoSourceRoutingState()
  private var peerSettings: SpatialVideoProjectionSettings? = null
  private var expectedRequest: SpatialVideoSourceNativeRequest? = null
  private var admittedGeneration: Long? = null
  private var embeddedPeerGeneration: Long? = null
  private var lastNativeGeneration = 0L
  private var localRetirementGeneration = 0L
  private var localRetirementOwnerGeneration = 0L

  fun snapshot(): SpatialVideoSourceRoutingState = synchronized(stateLock) { state }

  /** Records and fences the request without running any potentially blocking source lifecycle work. */
  fun beginProjectionSourceRequest(
      source: SpatialVideoSource,
      validatedPeerSettings: SpatialVideoProjectionSettings?,
  ): SpatialVideoSourceRoutingState = synchronized(stateLock) {
    val generation = maxOf(state.generation, lastNativeGeneration) + 1L
    lastNativeGeneration = generation
    peerSettings = validatedPeerSettings
    expectedRequest = null
    admittedGeneration = null
    embeddedPeerGeneration = null
    state = state.copy(
        requested = source, pending = source, effective = SpatialVideoSource.Disabled,
        failed = null, failureReason = SpatialVideoSourceReason.None,
        generation = generation, sourceOwnerDemand = source != SpatialVideoSource.Disabled,
        pendingSinceNs = 0L, readback = null,
    )
    state
  }

  /** Reserves a native Peer route for the embedded Sink; it owns decoder creation separately. */
  fun beginEmbeddedProjectionPeerRequest(): SpatialVideoSourceRoutingState = synchronized(stateLock) {
    val stopped = state.readback
    check(state.requested == SpatialVideoSource.Disabled && state.pending == null &&
        state.effective == SpatialVideoSource.Disabled && state.ownedAcquisition == null &&
        stopped != null && stopped.routeGeneration == state.generation &&
        stopped.source == SpatialVideoSource.Disabled && stopped.result == SpatialVideoSourceResult.Inactive &&
        stopped.hasStages(SpatialVideoSourceStage.AcquisitionStopped)) {
      "embedded receiver requires actual local acquisition shutdown"
    }
    beginProjectionSourceRequest(SpatialVideoSource.Peer, null).also {
      embeddedPeerGeneration = it.generation
    }
  }

  /** Snapshot both counters: failed Local cleanup can advance native generation without changing intent. */
  fun concurrentPeerAdmissionRequest(): SpatialConcurrentPeerAdmissionRequest = synchronized(stateLock) {
    check(state.generation > 0L && lastNativeGeneration >= state.generation &&
        state.requested != SpatialVideoSource.Peer && state.ownedAcquisition != SpatialVideoSource.Peer) {
      "concurrent Peer reservation superseded"
    }
    SpatialConcurrentPeerAdmissionRequest(state.generation, lastNativeGeneration)
  }

  /** Negative-only Java reservation retirement after exact native Peer source removal.
   * No native Disabled request or Inactive readback is manufactured: Own remains independent.
   */
  fun retireEmbeddedConcurrentProjectionPeerRequest(generation: Long) = synchronized(stateLock) {
    check(generation > 0L && state.generation == generation && lastNativeGeneration == generation &&
        embeddedPeerGeneration == generation && state.requested == SpatialVideoSource.Peer) {
      "concurrent Peer retirement superseded"
    }
    peerSettings = null
    expectedRequest = null
    admittedGeneration = null
    embeddedPeerGeneration = null
    state = state.copy(requested = SpatialVideoSource.Disabled, pending = null,
        effective = SpatialVideoSource.Disabled, ownedAcquisition = null, ownedAcquisitionGeneration = 0L,
        sourceOwnerDemand = false, failed = null, failureReason = SpatialVideoSourceReason.None,
        pendingSinceNs = 0L, readback = null)
  }
  /** Separate concurrent source-set reservation; the exclusive Local shutdown guard is unchanged. */
  fun beginEmbeddedConcurrentProjectionPeerRequest(proof: SpatialConcurrentPeerAdmission): SpatialVideoSourceRoutingState = synchronized(stateLock) {
    check(state.generation == proof.routingGeneration && lastNativeGeneration == proof.nativeGeneration &&
        state.requested != SpatialVideoSource.Peer &&
        state.ownedAcquisition != SpatialVideoSource.Peer) { "concurrent Peer reservation superseded" }
    // Native proof observed old-local quiescence; this does not stop the independent Own actor.
    state = state.copy(ownedAcquisition = null, ownedAcquisitionGeneration = 0L)
    beginProjectionSourceRequest(SpatialVideoSource.Peer, null).also { embeddedPeerGeneration = it.generation }
  }

  fun requestProjectionSource(
      source: SpatialVideoSource,
      validatedPeerSettings: SpatialVideoProjectionSettings?,
      carrier: SpatialVideoSourceCarrierContext?,
      reason: String,
  ): SpatialVideoSourceRoutingState {
    val begun = beginProjectionSourceRequest(source, validatedPeerSettings)
    return executeRequest(begun.generation, carrier, reason)
  }

  fun executeRequest(
      generation: Long,
      carrier: SpatialVideoSourceCarrierContext?,
      reason: String,
  ): SpatialVideoSourceRoutingState = executePending(generation, carrier, reason)

  fun resumePending(
      carrier: SpatialVideoSourceCarrierContext,
      reason: String,
  ): SpatialVideoSourceRoutingState {
    val generation = snapshot().generation
    return executePending(generation, carrier, reason)
  }

  private fun executePending(
      generation: Long,
      carrier: SpatialVideoSourceCarrierContext?,
      reason: String,
  ): SpatialVideoSourceRoutingState {
    val transition = synchronized(stateLock) {
      if (state.generation != generation || state.pending == null) return@synchronized null
      Transition(state.pending!!, peerSettings, state.ownedAcquisition, state.producerState)
    } ?: return snapshot()
    val source = transition.source
    val embeddedPeer = synchronized(stateLock) { embeddedPeerGeneration == generation }
    if (embeddedPeer && synchronized(stateLock) { admittedGeneration == generation }) return snapshot()

    // This is Peer-decoder demand, not generic source-input demand. Local never starts Peer media.
    execution.updateSourceOwnerDemand(false, "$reason-route-fence")
    if (!isCurrent(generation)) return snapshot()
    if (transition.ownedAcquisition == SpatialVideoSource.Peer) {
      val stopped = execution.stopSourceAcquisition(SpatialVideoSource.Peer, generation, reason)
      if (!isCurrent(generation)) return snapshot()
      if (!stopped) {
        return commitFailure(
            generation, source, SpatialVideoSourceReason.AcquisitionStopFailed,
            retainOwnedAcquisition = true,
        )
      }
      commitIfCurrent(generation) {
        it.copy(ownedAcquisition = null, ownedAcquisitionGeneration = 0L)
      }
    }
    if (source == SpatialVideoSource.Peer && !embeddedPeer) {
      val settings = transition.peerSettings
      if (settings == null || !settings.active || settings.source != "peer-packed-stereo") {
        return commitFailure(generation, source, SpatialVideoSourceReason.PeerSettingsInvalid)
      }
    }
    if (source == SpatialVideoSource.Local) {
      val producer = execution.producerState()
      if (!isCurrent(generation)) return snapshot()
      commitIfCurrent(generation) { it.copy(producerState = producer) }
      if (producer is SpatialProjectionProducerState.ProducerActive) {
        val cleanup = execution.requestProducerCleanup(generation, producer)
        if (!isCurrent(generation)) return snapshot()
        commitIfCurrent(generation) { it.copy(producerState = cleanup) }
        val matching = cleanup as? SpatialProjectionProducerState.ProducerInactive
        if (cleanup is SpatialProjectionProducerState.CleanupPending) {
          return commitDeferred(generation, source, SpatialVideoSourceReason.ProducerCleanupRequired)
        }
        if (matching == null || matching.session != producer.session ||
            matching.epoch != producer.epoch || matching.routeGeneration != generation) {
          return commitFailure(generation, source, SpatialVideoSourceReason.ProducerCleanupForeign)
        }
      } else if (producer is SpatialProjectionProducerState.CleanupPending) {
        // A newer explicit Local request rolls a fresh cleanup attempt at its own generation.
        val currentAttempt = if (producer.routeGeneration == generation) {
          producer
        } else {
          val rolled = execution.requestProducerCleanup(
              generation,
              SpatialProjectionProducerState.ProducerActive(producer.session, producer.epoch),
          )
          if (!isCurrent(generation)) return snapshot()
          commitIfCurrent(generation) { it.copy(producerState = rolled) }
          if (rolled is SpatialProjectionProducerState.CleanupPending) rolled else {
            val inactive = rolled as? SpatialProjectionProducerState.ProducerInactive
            if (inactive == null || inactive.session != producer.session ||
                inactive.epoch != producer.epoch || inactive.routeGeneration != generation) {
              return commitFailure(
                  generation, source, SpatialVideoSourceReason.ProducerCleanupForeign)
            }
            null
          }
        }
        if (currentAttempt == null) {
          // The freshly rolled cleanup completed immediately.
        } else {
          val cleanup = execution.readProducerCleanup(currentAttempt)
          if (!isCurrent(generation)) return snapshot()
          val matching = cleanup as? SpatialProjectionProducerState.ProducerInactive
          if (matching == null) {
            return commitDeferred(generation, source, SpatialVideoSourceReason.ProducerCleanupRequired)
          }
          if (matching.session != currentAttempt.session || matching.epoch != currentAttempt.epoch ||
              matching.routeGeneration != generation) {
            return commitFailure(generation, source, SpatialVideoSourceReason.ProducerCleanupForeign)
          }
          commitIfCurrent(generation) { it.copy(producerState = matching) }
        }
      } else if (producer is SpatialProjectionProducerState.ProducerAuthorityUnavailable) {
        return commitFailure(generation, source, SpatialVideoSourceReason.ProducerCleanupDenied)
      }
    }
    if (source == SpatialVideoSource.Peer && !embeddedPeer &&
        !execution.stagePeerSettings(checkNotNull(transition.peerSettings), generation)) {
      return commitFailure(generation, source, SpatialVideoSourceReason.PeerSettingsInvalid)
    }
    if (carrier == null) {
      return commitIfCurrent(generation) {
        it.copy(failureReason = SpatialVideoSourceReason.CarrierUnavailable)
      }
    }
    val request = SpatialVideoSourceNativeRequest(
        routeGeneration = generation, source = source,
        launchChallenge = carrier.launchChallenge, surfaceGeneration = carrier.surfaceGeneration,
    )
    synchronized(stateLock) {
      if (state.generation != generation) return snapshot()
      expectedRequest = request
    }
    val selected = execution.selectNativeProvider(request, carrier)
    if (!isCurrent(generation)) {
      retireStalePeerSelection(source, generation, reason, selected)
      return snapshot()
    }
    if (!matchesExpectedIdentity(selected, request, generation, source)) {
      return commitFailure(generation, source, SpatialVideoSourceReason.ReceiptForeign)
    }
    if (selected.result == SpatialVideoSourceResult.Unavailable ||
        selected.result == SpatialVideoSourceResult.Rejected ||
        selected.result == SpatialVideoSourceResult.Lost) {
      return applyReadback(selected)
    }
    val admissionStage = if (source == SpatialVideoSource.Disabled) {
      SpatialVideoSourceStage.AcquisitionStopped
    } else {
      SpatialVideoSourceStage.ProviderSelected
    }
    val asynchronousDisabledStop =
        source == SpatialVideoSource.Disabled &&
            selected.result == SpatialVideoSourceResult.Pending
    if (!asynchronousDisabledStop && !selected.hasStages(admissionStage)) {
      return commitFailure(generation, source, SpatialVideoSourceReason.ReceiptMalformed)
    }
    commitIfCurrent(generation) { current ->
      if (current.pendingSinceNs == 0L) current.copy(pendingSinceNs = monotonicNowNs()) else current
    }
    if (source == SpatialVideoSource.Peer) {
      commitIfCurrent(generation) {
        it.copy(
            ownedAcquisition = source,
            ownedAcquisitionGeneration = generation,
            producerState =
                if (selected.producerSession > 0L && selected.producerEpoch > 0L) {
                  SpatialProjectionProducerState.ProducerActive(
                      selected.producerSession,
                      selected.producerEpoch,
                  )
                } else {
                  SpatialProjectionProducerState.ProducerAuthorityUnavailable(generation)
                },
        )
      }
      if (!embeddedPeer && !execution.preparePeerDecoderOwnership(generation, reason)) {
        return retireAfterFailure(
            generation, source, SpatialVideoSourceReason.DecoderReplacementFailed, reason)
      }
      if (!isCurrent(generation)) {
        retireStaleOwnedPeer(generation, reason)
        return snapshot()
      }
      if (!embeddedPeer) execution.updateSourceOwnerDemand(true, "$reason-admitted")
      if (!embeddedPeer && !execution.replacePeerSettings(checkNotNull(transition.peerSettings), generation, reason)) {
        execution.updateSourceOwnerDemand(false, "$reason-decoder-rejected")
        return retireAfterFailure(
            generation, source, SpatialVideoSourceReason.DecoderReplacementFailed, reason)
      }
      if (!isCurrent(generation)) {
        retireStaleOwnedPeer(generation, reason)
        return snapshot()
      }
    } else if (source == SpatialVideoSource.Local) {
      commitIfCurrent(generation) {
        it.copy(ownedAcquisition = source, ownedAcquisitionGeneration = generation)
      }
    }
    synchronized(stateLock) {
      if (state.generation != generation) return snapshot()
      admittedGeneration = generation
    }
    return applyReadback(selected)
  }

  fun reportNativeReadback(readback: SpatialVideoSourceNativeReadback): SpatialVideoSourceRoutingState {
    val updated = applyReadback(readback)
    return retireFailedOwnedAcquisition(
        readback.routeGeneration, updated, "reported-readback-failure")
  }

  private fun applyReadback(readback: SpatialVideoSourceNativeReadback): SpatialVideoSourceRoutingState =
      synchronized(stateLock) {
    val expected = expectedRequest ?: return state
    if (!matchesExpectedIdentity(
            readback, expected, state.generation, state.requested)) return state
    if (readback.result == SpatialVideoSourceResult.Unavailable ||
        readback.reason == SpatialVideoSourceReason.ReceiptUnavailable) {
      return commitFailureLocked(readback.source, SpatialVideoSourceReason.ReceiptUnavailable)
    }
    if (readback.reason == SpatialVideoSourceReason.ReceiptForeign) {
      return commitFailureLocked(readback.source, SpatialVideoSourceReason.ReceiptForeign)
    }
    if (readback.reason == SpatialVideoSourceReason.ReceiptStale) {
      return commitFailureLocked(readback.source, SpatialVideoSourceReason.ReceiptStale)
    }
    if (readback.result == SpatialVideoSourceResult.Rejected) {
      return commitFailureLocked(readback.source, readback.reason)
    }
    if (readback.result == SpatialVideoSourceResult.Lost) {
      return commitFailureLocked(readback.source, SpatialVideoSourceReason.ActiveSourceLost)
    }
    val finalResult = readback.result == SpatialVideoSourceResult.Effective ||
        (readback.source == SpatialVideoSource.Disabled && readback.result == SpatialVideoSourceResult.Inactive)
    if (!finalResult) {
      state = state.copy(readback = readback)
      return state
    }
    if (admittedGeneration != readback.routeGeneration) {
      return commitFailureLocked(readback.source, SpatialVideoSourceReason.ReceiptForeign)
    }
    val required = SpatialVideoSourceStage.requiredFor(readback.source)
    if (!readback.hasStages(required) ||
        readback.cameraStartRequested != (readback.source == SpatialVideoSource.Local) ||
        readback.acquisitionTimeNs <= 0L ||
        (readback.source == SpatialVideoSource.Local &&
            (readback.readerGeneration <= 0L || readback.importGeneration <= 0L)) ||
        (readback.source == SpatialVideoSource.Peer &&
            (readback.decoderToken <= 0L || readback.readerGeneration <= 0L ||
                readback.pairGeneration <= 0L || readback.importGeneration <= 0L))) {
      return commitFailureLocked(readback.source, SpatialVideoSourceReason.ReceiptMalformed)
    }
    val producer = if (readback.source == SpatialVideoSource.Peer &&
        readback.producerSession > 0L && readback.producerEpoch > 0L) {
      SpatialProjectionProducerState.ProducerActive(readback.producerSession, readback.producerEpoch)
    } else if (readback.source == SpatialVideoSource.Peer) {
      SpatialProjectionProducerState.ProducerAuthorityUnavailable(readback.routeGeneration)
    } else state.producerState
    state = state.copy(
        pending = null, effective = readback.source, failed = null,
        failureReason = SpatialVideoSourceReason.None, producerState = producer, readback = readback,
        pendingSinceNs = 0L,
        ownedAcquisition = readback.source.takeIf { it != SpatialVideoSource.Disabled },
        ownedAcquisitionGeneration = readback.routeGeneration.takeIf {
          readback.source != SpatialVideoSource.Disabled
        } ?: 0L,
    )
    return state
  }

  fun pollActive(maxAgeNs: Long): SpatialVideoSourceRoutingState {
    val before = snapshot()
    if (before.failed != null && before.ownedAcquisition != null) {
      return retireFailedOwnedAcquisition(
          before.generation, before, "failed-acquisition-retry")
    }
    val readback = execution.readNativeSource(before.generation)
    if (!isCurrent(before.generation)) return snapshot()
    if (readback == null) {
      val failed = commitFailure(
          before.generation, before.pending ?: before.effective,
          SpatialVideoSourceReason.ReceiptUnavailable,
          retainOwnedAcquisition = before.ownedAcquisition != null,
      )
      return retireFailedOwnedAcquisition(before.generation, failed, "receipt-unavailable")
    }
    if (readback.routeGeneration != before.generation || readback.source != before.requested) {
      return snapshot()
    }
    var updated = applyReadback(readback)
    val nowNs = monotonicNowNs()
    if (readback.result == SpatialVideoSourceResult.Pending &&
        before.pendingSinceNs > 0L && nowNs >= before.pendingSinceNs &&
        nowNs - before.pendingSinceNs > maxAgeNs) {
      updated = commitFailure(
          before.generation, readback.source, SpatialVideoSourceReason.ReceiptStale,
          retainOwnedAcquisition = before.ownedAcquisition != null,
      )
    }
    // Pending receipts legitimately carry acquisitionTimeNs=0 until the first frame.
    if (readback.result == SpatialVideoSourceResult.Effective && readback.acquisitionTimeNs > 0L &&
        SpatialVideoSourceFreshness.isStale(nowNs, readback.acquisitionTimeNs, maxAgeNs)) {
      updated = commitFailure(
          before.generation, readback.source, SpatialVideoSourceReason.ReceiptStale,
          retainOwnedAcquisition = before.ownedAcquisition != null,
      )
    }
    return retireFailedOwnedAcquisition(before.generation, updated, "poll-failure")
  }

  private fun retireFailedOwnedAcquisition(
      generation: Long,
      failed: SpatialVideoSourceRoutingState,
      reason: String,
  ): SpatialVideoSourceRoutingState {
    val owned = failed.ownedAcquisition ?: return failed
    if (failed.generation != generation || failed.failed == null) return failed
    if (owned == SpatialVideoSource.Peer) execution.updateSourceOwnerDemand(false, reason)
    val stopGeneration = retirementGeneration(owned, generation)
    val stopped = execution.stopSourceAcquisition(owned, stopGeneration, reason)
    if (!isCurrent(generation)) return snapshot()
    return if (stopped) {
      if (owned == SpatialVideoSource.Local) synchronized(stateLock) {
        if (localRetirementGeneration == stopGeneration) {
          localRetirementGeneration = 0L
          localRetirementOwnerGeneration = 0L
        }
      }
      commitIfCurrent(generation) {
        it.copy(ownedAcquisition = null, ownedAcquisitionGeneration = 0L)
      }
    } else {
      commitFailure(
          generation, owned, SpatialVideoSourceReason.AcquisitionStopFailed,
          retainOwnedAcquisition = true,
      )
    }
  }

  private fun retirementGeneration(source: SpatialVideoSource, ownerGeneration: Long): Long =
      synchronized(stateLock) {
        if (source != SpatialVideoSource.Local) return@synchronized ownerGeneration
        if (
            localRetirementGeneration == 0L ||
                localRetirementOwnerGeneration != ownerGeneration
        ) {
          lastNativeGeneration += 1L
          localRetirementGeneration = lastNativeGeneration
          localRetirementOwnerGeneration = ownerGeneration
        }
        localRetirementGeneration
      }

  private fun retireAfterFailure(
      generation: Long,
      source: SpatialVideoSource,
      reason: SpatialVideoSourceReason,
      detail: String,
  ): SpatialVideoSourceRoutingState {
    val stopped = execution.stopSourceAcquisition(source, generation, "$detail-retire")
    if (!isCurrent(generation)) return snapshot()
    return commitFailure(
        generation,
        source,
        if (stopped) reason else SpatialVideoSourceReason.AcquisitionStopFailed,
        retainOwnedAcquisition = !stopped,
    )
  }

  private fun retireStalePeerSelection(
      source: SpatialVideoSource,
      generation: Long,
      reason: String,
      selected: SpatialVideoSourceNativeReadback,
  ) {
    if (source == SpatialVideoSource.Peer &&
        selected.hasStages(SpatialVideoSourceStage.ProviderSelected)) {
      execution.updateSourceOwnerDemand(false, "$reason-stale")
      val stopped = execution.stopSourceAcquisition(source, generation, "$reason-stale")
      if (!stopped) synchronized(stateLock) {
        state = state.copy(
            ownedAcquisition = SpatialVideoSource.Peer,
            ownedAcquisitionGeneration = generation,
            failed = SpatialVideoSource.Peer,
            failureReason = SpatialVideoSourceReason.AcquisitionStopFailed,
        )
      }
    }
  }

  private fun retireStaleOwnedPeer(generation: Long, reason: String) {
    execution.updateSourceOwnerDemand(false, "$reason-stale")
    execution.stopSourceAcquisition(SpatialVideoSource.Peer, generation, "$reason-stale")
  }

  private fun commitDeferred(
      generation: Long,
      source: SpatialVideoSource,
      reason: SpatialVideoSourceReason,
  ): SpatialVideoSourceRoutingState = commitIfCurrent(generation) {
    it.copy(pending = source, failed = source, failureReason = reason)
  }

  private fun commitFailure(
      generation: Long,
      source: SpatialVideoSource,
      reason: SpatialVideoSourceReason,
      retainOwnedAcquisition: Boolean = false,
  ): SpatialVideoSourceRoutingState = commitIfCurrent(generation) {
    it.copy(
        pending = null, effective = SpatialVideoSource.Disabled, failed = source,
        failureReason = reason, pendingSinceNs = 0L,
        ownedAcquisition = if (retainOwnedAcquisition) it.ownedAcquisition else null,
        ownedAcquisitionGeneration = if (retainOwnedAcquisition) it.ownedAcquisitionGeneration else 0L,
    )
  }

  private fun commitFailureLocked(
      source: SpatialVideoSource,
      reason: SpatialVideoSourceReason,
  ): SpatialVideoSourceRoutingState {
    state = state.copy(
        pending = null, effective = SpatialVideoSource.Disabled,
        failed = source, failureReason = reason, pendingSinceNs = 0L,
    )
    return state
  }

  private fun commitIfCurrent(
      generation: Long,
      transform: (SpatialVideoSourceRoutingState) -> SpatialVideoSourceRoutingState,
  ): SpatialVideoSourceRoutingState = synchronized(stateLock) {
    if (state.generation == generation) state = transform(state)
    state
  }

  private fun isCurrent(generation: Long): Boolean =
      synchronized(stateLock) { state.generation == generation }

  private fun matchesExpectedIdentity(
      readback: SpatialVideoSourceNativeReadback,
      expected: SpatialVideoSourceNativeRequest,
      currentGeneration: Long,
      currentSource: SpatialVideoSource,
  ): Boolean =
      readback.routeGeneration == currentGeneration &&
          readback.routeGeneration == expected.routeGeneration &&
          readback.source == currentSource &&
          readback.source == expected.source &&
          readback.launchChallenge == expected.launchChallenge &&
          readback.surfaceGeneration == expected.surfaceGeneration

  private data class Transition(
      val source: SpatialVideoSource,
      val peerSettings: SpatialVideoProjectionSettings?,
      val ownedAcquisition: SpatialVideoSource?,
      val producerState: SpatialProjectionProducerState,
  )
}
