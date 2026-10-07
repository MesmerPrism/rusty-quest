package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex

import io.github.mesmerprism.rustyquest.spatial_camera_panel.*
import io.github.mesmerprism.rustyquest.media.*

/** Actual receiver/display/routing chain; only Android/native/media resources are modeled. */
internal object ConcurrentPeerRetirementHost {
  @JvmStatic fun main(args: Array<String>) {
    val baseline = args.singleOrNull() == "baseline"
    val f = Fixture()
    f.arm(1)
    f.display.activatePeerProjection(f.receiver.routeGeneration(), 42, 43)
    check(f.routing.snapshot().ownedAcquisition == SpatialVideoSource.Peer)
    f.stop(2)
    check(f.receiver.snapshot().terminal())
    if (baseline) {
      val error = runCatching { f.arm(3) }.exceptionOrNull() ?: error("original source unexpectedly restarted")
      check(error.message == "concurrent Peer reservation superseded")
      check(f.receiver.failedArmStage() == "PEER_PROJECTION")
      println("PASS original full receiver/display/routing chain retains PEER_PROJECTION superseded")
      return
    }
    check(f.routing.snapshot().readback == null) // No invented native Inactive receipt.
    check(f.nativeCalls.all { it.source == SpatialVideoSource.Peer }) // Never a native Disabled/Own stop.
    f.arm(3)
    f.display.activatePeerProjection(f.receiver.routeGeneration(), 42, 43)
    check(f.receiver.routeGeneration() > 1)
    check(f.routing.snapshot().ownedAcquisition == SpatialVideoSource.Peer)
    println("PASS actual receiver Stop -> second prepare/bind/activate")

    val denied = Fixture(); denied.arm(1)
    val before = denied.routing.snapshot()
    OwnPackedPoolNative.retirementAllowed = false
    check(runCatching { denied.stop(2) }.isFailure)
    check(denied.routing.snapshot() == before)
    OwnPackedPoolNative.retirementAllowed = true
    denied.stop(3)
    check(denied.receiver.snapshot().terminal())
    println("PASS denied native retirement retains reservation and exact cleanup retry")

    val stale = Fixture(); stale.arm(1)
    val oldGeneration = stale.receiver.routeGeneration()
    stale.routing.beginProjectionSourceRequest(SpatialVideoSource.Local, null)
    val newer = stale.routing.snapshot()
    check(runCatching { stale.stop(2) }.isFailure)
    check(stale.routing.snapshot() == newer)
    check(runCatching { retire(stale.routing, oldGeneration) }.isFailure)
    check(stale.routing.snapshot() == newer)
    println("PASS stale retirement cannot clear successor reservation")

    val retired = Fixture(); retired.arm(1); retired.stop(2)
    val cleared = retired.routing.snapshot()
    check(runCatching { retire(retired.routing, cleared.generation) }.isFailure)
    check(retired.routing.snapshot() == cleared)
    println("PASS repeated retirement rejected without state mutation")

    val late = f.routing.snapshot().readback!!
    f.stop(4)
    val state = f.routing.snapshot()
    f.routing.reportNativeReadback(late)
    check(f.routing.snapshot() == state)
    println("PASS retired native readback cannot resurrect Java Peer state")
  }

  private fun retire(routing: SpatialVideoSourceRoutingCoordinator, generation: Long) {
    routing.javaClass.getDeclaredMethod("retireEmbeddedConcurrentProjectionPeerRequest", java.lang.Long.TYPE).invoke(routing, generation)
  }

  private class Fixture {
    val nativeCalls = mutableListOf<SpatialVideoSourceNativeRequest>()
    val adapter = object : SpatialVideoSourceExecutionAdapter {
      override fun producerState() = SpatialProjectionProducerState.NoProducerOwned
      override fun requestProducerCleanup(routeGeneration: Long, producer: SpatialProjectionProducerState.ProducerActive) = error("unexpected Own cleanup")
      override fun readProducerCleanup(pending: SpatialProjectionProducerState.CleanupPending) = error("unexpected Own cleanup read")
      override fun selectNativeProvider(request: SpatialVideoSourceNativeRequest, context: SpatialVideoSourceCarrierContext): SpatialVideoSourceNativeReadback {
        nativeCalls += request
        return words(request.routeGeneration, SpatialVideoSourceResult.Pending, SpatialVideoSourceStage.ProviderSelected)
      }
      override fun readNativeSource(routeGeneration: Long) = words(routeGeneration, SpatialVideoSourceResult.Effective, SpatialVideoSourceStage.requiredFor(SpatialVideoSource.Peer))
      override fun stagePeerSettings(settings: SpatialVideoProjectionSettings, routeGeneration: Long) = error("unexpected generic Peer settings")
      override fun replacePeerSettings(settings: SpatialVideoProjectionSettings, routeGeneration: Long, reason: String) = error("unexpected generic Peer replacement")
      override fun preparePeerDecoderOwnership(routeGeneration: Long, reason: String) = true
      override fun stopSourceAcquisition(source: SpatialVideoSource, routeGeneration: Long, reason: String) = error("unexpected acquisition stop")
      override fun updateSourceOwnerDemand(required: Boolean, reason: String) {}
    }
    val routing = SpatialVideoSourceRoutingCoordinator(adapter) { 1000L }
    val display: EmbeddedDuplexDisplayCoordinator
    val receiver: EmbeddedDuplexReceiver
    init {
      OwnPackedPoolNative.retirementAllowed = true
      routing.beginProjectionSourceRequest(SpatialVideoSource.Local, null)
      display = EmbeddedDuplexDisplayCoordinator(routing, { it(); true }, { true },
          { SpatialVideoSourceCarrierContext(11, 12) }, { true }, { true },
          { words(it, SpatialVideoSourceResult.Effective, SpatialVideoSourceStage.requiredFor(SpatialVideoSource.Peer)) },
          { longArrayOf() }, { error("unexpected Own restart") })
      receiver = EmbeddedDuplexReceiver(17, display, "127.0.0.1", 7777, 64, 64, 30,
          PackedStereoMediaReceiver.Bounds(65536, 65536, 64, 64, 2, 3000, 4000, 100, 15000, 10000, 8, 250),
          object : EmbeddedDuplexReceiver.RuntimeFactory {
            override fun stage(w: Int, h: Int, n: Int, fps: Int, route: Long) = object : EmbeddedDuplexReceiver.ProjectionResource {
              override fun routeGeneration() = route
              override fun decoderToken() = 42L
              override fun readerGeneration() = 43L
              override fun release() = true
            }
            override fun create(p: EmbeddedDuplexReceiver.ProjectionResource, host: String, port: Int, gen: Long,
                bounds: PackedStereoMediaReceiver.Bounds, listener: PackedStereoMediaReceiver.FrameLifecycleListener): EmbeddedDuplexReceiver.ReceiverRuntime {
              val owner = object : MediaOwnerProvider {
                var revision = 0L; var state = "prepared"
                override fun execute(a: MediaOwnerAction, c: CancellationHandle): MediaProviderReadback {
                  state = if (a.actionKind() == "stop") "stopped" else "receiver_armed"
                  return MediaProviderReadback(a, "modeled.receiver", ++revision, state, "receipt.$revision")
                }
                override fun compensate(a: MediaOwnerAction, c: CancellationHandle) = execute(a, c)
                override fun snapshot() = MediaRuntimeSnapshot(gen, revision, state, state == "stopped", "modeled resource", "modeled.receiver")
              }
              return object : EmbeddedDuplexReceiver.ReceiverRuntime {
                override fun provider() = owner
                override fun start() {}
                override fun snapshot() = owner.snapshot()
              }
            }
          })
    }
    fun arm(seq: Int) = receiver.execute(action("arm_receiver", "start", seq), CancellationHandle(17))
    fun stop(seq: Int) = receiver.execute(action("stop", "stop", seq), CancellationHandle(17))
    fun words(route: Long, result: SpatialVideoSourceResult, stages: Long) = SpatialVideoSourceNativeReadback(
        route, SpatialVideoSource.Peer, 42, 43, 11, 12, 1, 1, 1000, stages, result, SpatialVideoSourceReason.None, false, 0, 0)
  }
  private fun action(kind: String, operation: String, sequence: Int) = MediaOwnerAction.parse(
      """{"${'$'}schema":"rusty.quest.android.media.execution-ticket.v1","capability":"capability.test","executor_generation":17,"action_id":"action.$sequence","authority_epoch_id":"epoch.test","media_acceptance_authority_revision":1,"expected_runtime_revision":0,"client_id":"client.test","lease_id":"lease.test","sequence":$sequence,"operation":"$operation","owner_kind":"sink","action_kind":"$kind","owner_id":"owner.test","provider_kind":"receiver","resource_id":"resource.test"}""")
}
