package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex

import android.os.Looper
import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialConcurrentPeerAdmission
import io.github.mesmerprism.rustyquest.spatial_camera_panel.OwnPackedPoolNative
import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialPeerProjectionDecoderIdentity
import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialVideoSource
import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialVideoSourceCarrierContext
import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialVideoSourceNativeReadback
import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialVideoSourceResult
import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialVideoSourceRoutingCoordinator
import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialVideoSourceStage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal class EmbeddedDuplexDisplayCoordinator(
    private val routing: SpatialVideoSourceRoutingCoordinator,
    private val dispatch: (() -> Unit) -> Boolean,
    private val onLifecycleThread: () -> Boolean,
    private val carrier: () -> SpatialVideoSourceCarrierContext?,
    private val bind: (SpatialPeerProjectionDecoderIdentity) -> Boolean,
    private val attach: (SpatialPeerProjectionDecoderIdentity) -> Boolean,
    private val read: (Long) -> SpatialVideoSourceNativeReadback?,
    private val readWords: (Long) -> LongArray,
    private val restartLocal: (Long) -> Boolean,
    private val startOwn: (() -> Boolean)? = null,
    private val stopWholeProjection: (() -> Unit)? = null,
) : EmbeddedDuplexDisplay {
  private var ownedPeerGeneration = 0L
  private var retirementGeneration = 0L
  private var ownedDecoderToken = 0L
  private var ownedReaderGeneration = 0L
  private fun concurrentOwn(): Boolean = OwnPackedPoolNative.captureRouteSelected() && OwnStereoCaptureRuntime.currentForApplication() != null
  override fun resumeOwnProjection(ownerAlive: java.util.function.BooleanSupplier) = serialized {
    activateOwnProjectionCurrent(ownerAlive)
  }
  override fun activateOwnProjection() = serialized { activateOwnProjectionCurrent(null) }
  private fun activateOwnProjectionCurrent(ownerAlive: java.util.function.BooleanSupplier?) {
    check(ownerAlive?.asBoolean != false) { "Own resume superseded" }
    val capture = checkNotNull(OwnStereoCaptureRuntime.currentForApplication()?.retainedCapture()) {
      "accepted Own capture unavailable"
    }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!capture.fresh()) {
      check(ownerAlive?.asBoolean != false) { "Own resume superseded" }
      check(System.nanoTime() < deadline) { "Own producer readiness remains Pending" }
      Thread.sleep(10)
    }
    check(ownerAlive?.asBoolean != false) { "Own resume superseded" }
    check(startOwn?.invoke() == true) { "retained Own source carrier unavailable" }
  }

  override fun requestWholeProjectionStop() = serialized {
    checkNotNull(stopWholeProjection) { "whole projection stop unavailable" }.invoke()
    Unit
  }

  override fun ensureLocalCaptureStopped(): Long = serialized {
    val current = routing.snapshot()
    if (concurrentOwn()) {
      check(EmbeddedDuplexNative.localCameraQuiescent()) { "old native local acquisition remains Pending" }
      return@serialized current.generation
    }
    if (current.requested == SpatialVideoSource.Peer) {
      check(EmbeddedDuplexNative.localCameraQuiescent()) { "local capture still owns camera resources" }
      current.generation
    } else {
      val generation = if (current.requested == SpatialVideoSource.Disabled && current.failed == null) {
        current.generation
      } else request(SpatialVideoSource.Disabled)
      awaitSource(generation, SpatialVideoSource.Disabled)
      check(EmbeddedDuplexNative.localCameraQuiescent()) { "local camera shutdown incomplete" }
      generation
    }
  }

  override fun preparePeerProjection(): Long = serialized {
    ensureLocalCaptureStopped()
    val staged = if (concurrentOwn()) {
      val own = checkNotNull(OwnStereoCaptureRuntime.currentForApplication()) { "concurrent Own capture unavailable" }
      val capture = checkNotNull(own.retainedCapture()) { "concurrent Own capture not Live" }
      check(own.phase() == OwnStereoCaptureRuntime.Phase.Live) { "concurrent Own capture not Live" }
      check(capture.fresh()) { "concurrent Own capture not fresh" }
      val admissionRequest = routing.concurrentPeerAdmissionRequest()
      val context = checkNotNull(carrier()) { "projection carrier unavailable" }
      val proof = SpatialConcurrentPeerAdmission.observed(admissionRequest, context,
          OwnPackedPoolNative.concurrentPeerAdmission(admissionRequest.nativeGeneration, context.launchChallenge, context.surfaceGeneration))
      check(OwnStereoCaptureRuntime.currentForApplication() === own && own.retainedCapture() === capture &&
          own.phase() == OwnStereoCaptureRuntime.Phase.Live) { "concurrent Own capture superseded" }
      check(capture.fresh()) { "concurrent Own capture not fresh" }
      check(carrier() == context) { "concurrent Own carrier superseded" }
      routing.beginEmbeddedConcurrentProjectionPeerRequest(proof)
    } else routing.beginEmbeddedProjectionPeerRequest()
    ownedPeerGeneration = staged.generation
    retirementGeneration = 0L
    ownedDecoderToken = 0L
    ownedReaderGeneration = 0L
    val context = checkNotNull(carrier()) { "projection carrier unavailable" }
    val selected = routing.executeRequest(staged.generation, context, "embedded-sink-arm")
    check(selected.failed == null && selected.readback?.hasStages(SpatialVideoSourceStage.ProviderSelected) == true) {
      "native Peer preparation rejected"
    }
    staged.generation
  }

  override fun bindPeerProjection(routeGeneration: Long, decoderToken: Long, readerGeneration: Long) = serialized {
    requireCurrentPeer(routeGeneration)
    check(bind(SpatialPeerProjectionDecoderIdentity(routeGeneration, decoderToken, readerGeneration))) {
      "native embedded decoder binding rejected"
    }
    ownedDecoderToken = decoderToken
    ownedReaderGeneration = readerGeneration
  }

  override fun activatePeerProjection(routeGeneration: Long, decoderToken: Long, readerGeneration: Long) = serialized {
    requireCurrentPeer(routeGeneration)
    check(attach(SpatialPeerProjectionDecoderIdentity(routeGeneration, decoderToken, readerGeneration))) {
      "embedded common graph attachment failed"
    }
    awaitSource(routeGeneration, SpatialVideoSource.Peer)
    Unit
  }

  override fun currentProjection(routeGeneration: Long): LongArray = serialized {
    check(routing.snapshot().generation == routeGeneration) { "projection generation changed" }
    readWords(routeGeneration)
  }

  override fun retirePeerProjection() = serialized {
    if (concurrentOwn()) {
      if (ownedPeerGeneration != 0L && ownedDecoderToken != 0L && ownedReaderGeneration != 0L) {
        check(OwnPackedPoolNative.nativeRetirePeerSource(ownedPeerGeneration, ownedDecoderToken, ownedReaderGeneration)) {
          "exact Peer source retirement remains Pending"
        }
      }
      if (ownedPeerGeneration != 0L) {
        check(ownedDecoderToken != 0L && ownedReaderGeneration != 0L) {
          "exact Peer source retirement identity unavailable"
        }
        routing.retireEmbeddedConcurrentProjectionPeerRequest(ownedPeerGeneration)
      }
      // Removal is source-only. Incoming decoder and submitted GPU fences own terminal proof.
      ownedPeerGeneration = 0L; ownedDecoderToken = 0L; ownedReaderGeneration = 0L
      retirementGeneration = 0L
      return@serialized Unit
    }
    if (ownedPeerGeneration != 0L) {
      val current = routing.snapshot()
      if (retirementGeneration == 0L) {
        check(current.generation == ownedPeerGeneration && current.requested == SpatialVideoSource.Peer) {
          "embedded projection retirement was superseded"
        }
        // Record the new generation before execution, so a failed native stop
        // retains exactly the same retry target rather than requesting another.
        val begun = routing.beginProjectionSourceRequest(SpatialVideoSource.Disabled, null)
        retirementGeneration = begun.generation
      }
      check(routing.snapshot().generation == retirementGeneration) { "embedded retirement superseded" }
      val context = checkNotNull(carrier()) { "projection carrier unavailable" }
      routing.executeRequest(retirementGeneration, context, "embedded-sink-retirement")
      awaitSource(retirementGeneration, SpatialVideoSource.Disabled)
      ownedPeerGeneration = 0L
      retirementGeneration = 0L
    }
    Unit
  }

  override fun restoreLocalAfterProductCleanup() = serialized {
    retirePeerProjection()
    if (concurrentOwn()) return@serialized Unit
    val disabled = request(SpatialVideoSource.Disabled)
    awaitSource(disabled, SpatialVideoSource.Disabled)
    check(EmbeddedDuplexNative.localCameraQuiescent()) { "native capture cleanup incomplete" }
    val local = request(SpatialVideoSource.Local)
    check(restartLocal(local)) { "retained local capture restart failed" }
    awaitSource(local, SpatialVideoSource.Local)
    Unit
  }

  private fun requireCurrentPeer(generation: Long) {
    val current = routing.snapshot()
    check(current.generation == generation && current.requested == SpatialVideoSource.Peer && current.failed == null) {
      "embedded projection request is no longer current"
    }
  }

  private fun request(source: SpatialVideoSource): Long {
    val context = checkNotNull(carrier()) { "projection carrier unavailable" }
    val begun = routing.beginProjectionSourceRequest(source, null)
    val selected = routing.executeRequest(begun.generation, context, "embedded-camera-transfer")
    check(selected.failed == null) { "native source transition rejected" }
    return begun.generation
  }

  private fun awaitSource(generation: Long, source: SpatialVideoSource) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
      check(routing.snapshot().generation == generation) { "source transition superseded" }
      val receipt = read(generation)
      if (receipt != null) {
        val state = routing.reportNativeReadback(receipt)
        check(state.failed == null) { "native source transition failed" }
        val terminal = if (source == SpatialVideoSource.Disabled) SpatialVideoSourceResult.Inactive
            else SpatialVideoSourceResult.Effective
        if (receipt.routeGeneration == generation && receipt.source == source && receipt.result == terminal &&
            receipt.hasStages(SpatialVideoSourceStage.requiredFor(source))) return
      }
      Thread.sleep(10)
    }
    error("native source transition deadline")
  }

  private fun <T> serialized(action: () -> T): T {
    check(Looper.myLooper() != Looper.getMainLooper()) { "embedded lifecycle must not block the UI thread" }
    if (onLifecycleThread()) return action()
    val result = CompletableFuture<T>()
    check(dispatch {
      try { result.complete(action()) }
      catch (failure: Throwable) { result.completeExceptionally(failure) }
    }) { "Activity lifecycle is already fenced" }
    return result.get(30, TimeUnit.SECONDS)
  }
}
