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
    private val prepareRenderer:((String,Long)->String)?=null,
    private val readPreparedRenderer:((String)->String)?=null,
    private val abortPreparedRenderer:((String)->String)?=null,
) : EmbeddedDuplexDisplay {
  private var dormantPeerRoute: SpatialVideoSourceRoutingCoordinator.PreparedPeerRoute? = null
  override fun reserveDormantPeerProjection(): Long = serialized {
    check(dormantPeerRoute == null && ownedPeerGeneration == 0L) { "dormant reader route already held" }
    ensureLocalCaptureStopped()
    val candidate=routing.prepareEmbeddedPeerRoute()
    dormantPeerRoute=candidate
    candidate.generation
  }
  private var preparedRendererId:String?=null
  override fun prepareDormantPeerRenderer(id:String,generation:Long,desiredStop:java.util.function.BooleanSupplier):String = serialized {
    val candidate=checkNotNull(dormantPeerRoute){"actual dormant route candidate absent"}
    check(candidate.generation==generation &&preparedRendererId==null){"actual dormant renderer candidate differs"}
    routing.requirePreparedEmbeddedPeerRoute(candidate);val before=checkNotNull(carrier())
    preparedRendererId=id // Retain before entering the actual JNI callback.
    var raw=checkNotNull(prepareRenderer){"actual renderer callback absent"}(id,generation)
    var warned=false;val estimate=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
    while(true){
      routing.requirePreparedEmbeddedPeerRoute(candidate);check(carrier()==before){"actual dormant carrier superseded"}
      if(desiredStop.asBoolean)throw IllegalStateException("actual desired Stop during renderer preparation")
      if(EmbeddedDuplexPreparedRenderer.dormant(raw,id,generation))break
      if(!warned &&System.nanoTime()>=estimate){warned=true;EmbeddedDuplexEstimate.warn("same renderer preparation remains Pending")}
      Thread.sleep(10);raw=checkNotNull(readPreparedRenderer){"actual renderer read callback absent"}(id)
    }
    raw
  }
  override fun abortDormantPeerRenderer(id:String):String = serialized {
    check(preparedRendererId==null||preparedRendererId==id){"actual dormant renderer abort ID differs"}
    var warned=false;val estimate=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
    var raw=checkNotNull(abortPreparedRenderer){"actual renderer abort callback absent"}(id)
    while(!EmbeddedDuplexPreparedRenderer.terminal(raw,id)){
      if(!warned&&System.nanoTime()>=estimate){warned=true;EmbeddedDuplexEstimate.warn("same renderer cleanup remains Pending")}
      Thread.sleep(10);raw=checkNotNull(abortPreparedRenderer){"actual renderer abort callback absent"}(id)
    }
    preparedRendererId=null;raw
  }
  private var ownedPeerGeneration = 0L
  private var retirementGeneration = 0L
  private var ownedDecoderToken = 0L
  private var ownedReaderGeneration = 0L
  override fun activateOwnProjectionAsync(ownerAlive: java.util.function.BooleanSupplier): CompletableFuture<Void> {
    val own = OwnStereoCaptureRuntime.currentForApplication()
        ?: return CompletableFuture<Void>().also { it.completeExceptionally(IllegalStateException("Own capture unavailable")) }
    val capture = own.retainedCapture()
        ?: return CompletableFuture<Void>().also { it.completeExceptionally(IllegalStateException("Own capture unavailable")) }
    val result = CompletableFuture<Void>()
    own.whenFresh(capture,ownerAlive).whenComplete { _, failure ->
      if (failure != null) { result.completeExceptionally(failure) }
      else if (!dispatch {
        try {
          check(ownerAlive.asBoolean) { "Own activation superseded" }
          check(own.retainedCapture() === capture && capture.fresh()) { "Own activation no longer fresh" }
          check(startOwn?.invoke() == true) { "Own carrier unavailable" }
          result.complete(null)
        } catch (error: Throwable) { result.completeExceptionally(error) }
      }) { result.completeExceptionally(IllegalStateException("Activity lifecycle is fenced")) }
    }
    return result
  }
  private fun concurrentOwn(): Boolean = OwnPackedPoolNative.captureRouteSelected() && OwnStereoCaptureRuntime.currentForApplication() != null
  override fun resumeOwnProjection(ownerAlive: java.util.function.BooleanSupplier) = serialized {
    activateOwnProjectionCurrent(ownerAlive)
  }
  override fun activateOwnProjection() = serialized { activateOwnProjectionCurrent(null) }
  private fun activateOwnProjectionCurrent(ownerAlive: java.util.function.BooleanSupplier?) {
    fun reject(reason: EmbeddedDuplexOwnProjectionFailure.Reason): Nothing =
      throw EmbeddedDuplexOwnProjectionFailure(reason)
    if (ownerAlive?.asBoolean == false) reject(EmbeddedDuplexOwnProjectionFailure.Reason.SUPERSEDED)
    val capture = OwnStereoCaptureRuntime.currentForApplication()?.retainedCapture()
      ?: reject(EmbeddedDuplexOwnProjectionFailure.Reason.CAPTURE_UNAVAILABLE)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    var warned = false
    while (!capture.fresh()) {
      if (ownerAlive?.asBoolean == false) reject(EmbeddedDuplexOwnProjectionFailure.Reason.SUPERSEDED)
      check(OwnStereoCaptureRuntime.currentForApplication()?.phase() == OwnStereoCaptureRuntime.Phase.Live) { "Own capture stopped" }
      capture.failure()?.let { throw IllegalStateException("Own camera/compositor failed",it) }
      if (!warned && System.nanoTime() >= deadline) {
        warned = true; EmbeddedDuplexEstimate.warn("Own projection freshness remains Pending")
      }
      try { Thread.sleep(10) }
      catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        reject(EmbeddedDuplexOwnProjectionFailure.Reason.INTERRUPTED)
      }
    }
    if (ownerAlive?.asBoolean == false) reject(EmbeddedDuplexOwnProjectionFailure.Reason.SUPERSEDED)
    if (startOwn?.invoke() != true) reject(EmbeddedDuplexOwnProjectionFailure.Reason.CARRIER_NOT_READY)
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
      val prepared=dormantPeerRoute
      if(prepared == null)routing.beginEmbeddedConcurrentProjectionPeerRequest(proof)
      else routing.beginPreparedEmbeddedPeerRequest(prepared,proof)
    } else {
      val prepared=dormantPeerRoute
      if(prepared == null)routing.beginEmbeddedProjectionPeerRequest()
      else routing.beginPreparedEmbeddedPeerRequest(prepared,null)
    }
    dormantPeerRoute=null
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
    // Reader/decoder owner has already earned its actual terminal join before this metadata is released.
    if(ownedPeerGeneration == 0L)dormantPeerRoute=null
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
    var warned = false
    while (true) {
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
      if (!warned && System.nanoTime() >= deadline) {
        warned = true; EmbeddedDuplexEstimate.warn("same source transition remains Pending")
      }
      Thread.sleep(10)
    }
  }

  private fun <T> serialized(action: () -> T): T {
    check(Looper.myLooper() != Looper.getMainLooper()) { "embedded lifecycle must not block the UI thread" }
    if (onLifecycleThread()) return action()
    val result = CompletableFuture<T>()
    check(dispatch {
      try { result.complete(action()) }
      catch (failure: Throwable) { result.completeExceptionally(failure) }
    }) { "Activity lifecycle is already fenced" }
    return EmbeddedDuplexEstimate.await(result,30,TimeUnit.SECONDS,"display lifecycle")
  }
}
