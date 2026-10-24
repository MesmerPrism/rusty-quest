package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex

import io.github.mesmerprism.rustyquest.spatial_camera_panel.*
import io.github.mesmerprism.rustyquest.media.*

/** Actual receiver/process display slot/display/routing chain; only Android/native/media resources are modeled. */
internal object PreparedSecondLifetimeHost {
  @JvmStatic fun main(args:Array<String>){
    val f=Fixture();val first="1".repeat(32);val second="2".repeat(32)
    f.dormant(first);f.arm(1);f.stop(2);check(f.receiver.snapshot().terminal())
    f.requireRendererTerminal(first)
    if(args.singleOrNull()=="baseline") { f.dormant(second);error("old source unexpectedly rolled over without archived ownership") }
    val old=f.receiver.routeGeneration()
    check(runCatching { f.dormant(second) }.isFailure) // Stop alone never releases metadata.
    check(runCatching { f.archive(second) }.isFailure)
    f.archive(first);f.archive(first) // Exact already-archived retry is idempotent.
    f.dormant(second);check(f.receiver.routeGeneration()>old);f.arm(3);f.stop(4)
    check(runCatching { f.archive(first) }.isFailure);f.archive(second)
    val pending=Fixture();pending.dormant(first);pending.arm(1)
    check(runCatching { pending.archive(first) }.isFailure)
    OwnPackedPoolNative.retirementAllowed=false;check(runCatching { pending.stop(2) }.isFailure)
    check(pending.abortIds.isEmpty())
    check(runCatching { pending.archive(first) }.isFailure)
    OwnPackedPoolNative.retirementAllowed=true;pending.stop(3);pending.archive(first);pending.dormant(second)
    val failedAbort=Fixture();failedAbort.dormant(first);failedAbort.arm(1)
    failedAbort.abortFails=true;check(runCatching { failedAbort.stop(2) }.isFailure)
    check(failedAbort.abortIds==listOf(first));check(!failedAbort.rendererReleased)
    check(runCatching { failedAbort.display.abortDormantPeerRenderer(second) }.isFailure)
    check(failedAbort.abortIds==listOf(first)) // Foreign ID never reaches the native owner.
    failedAbort.abortFails=false;failedAbort.stop(3);failedAbort.requireRendererTerminal(first)
    check(failedAbort.abortIds==listOf(first,first));failedAbort.archive(first);failedAbort.dormant(second)
    val superseded=Fixture();superseded.dormant(first);superseded.arm(1)
    superseded.routing.beginProjectionSourceRequest(SpatialVideoSource.Disabled,null)
    check(runCatching { superseded.stop(2) }.isFailure);check(superseded.abortIds.isEmpty())
    println("PASS whole Receiver/Display/routing prepare -> entered receiver -> terminal Peer teardown -> exact renderer release -> archive -> second prepare; native/routing failure, same-ID cleanup retry, foreign/current/Pending refusals; Android/native/media leaves modeled")
  }

  private fun retire(routing: SpatialVideoSourceRoutingCoordinator, generation: Long) {
    routing.javaClass.getDeclaredMethod("retireEmbeddedConcurrentProjectionPeerRequest", java.lang.Long.TYPE).invoke(routing, generation)
  }

  internal class Fixture {
    var rendererIdentity:org.json.JSONObject?=null
    val abortIds=mutableListOf<String>()
    var rendererId:String?=null
    var rendererReleased=false
    var abortFails=false
    var receiverStopped=false
    var readerReleased=false
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
    val slot = EmbeddedDuplexDisplaySlot()
    val receiver: EmbeddedDuplexReceiver
    init {
      OwnPackedPoolNative.retirementAllowed = true
      routing.beginProjectionSourceRequest(SpatialVideoSource.Local, null)
      display = EmbeddedDuplexDisplayCoordinator(routing, { it(); true }, { true },
          { SpatialVideoSourceCarrierContext(11, 12) }, { true }, { true },
          { words(it, SpatialVideoSourceResult.Effective, SpatialVideoSourceStage.requiredFor(SpatialVideoSource.Peer)) },
          { longArrayOf() }, { error("unexpected Own restart") }, prepareRenderer={id,route->
            check(rendererId==null||rendererReleased);rendererId=id;rendererReleased=false
            receiverStopped=false;readerReleased=false;ready(id,route,rendererIdentity)
          }, readPreparedRenderer={ error("unexpected pending read") }, abortPreparedRenderer={id->
            check(id==rendererId);abortIds+=id
            check(routing.snapshot().requested==SpatialVideoSource.Disabled) // Real routing teardown first.
            check(receiverStopped&&readerReleased) // Real receiver/reader terminal callbacks first.
            check(!abortFails){"modeled renderer abort failure"}
            rendererReleased=true
            org.json.JSONObject(ready(id,1,rendererIdentity)).put("state","released").put("peer_reservation_released",true).toString()
          })
      slot.attach(display)
      receiver = EmbeddedDuplexReceiver(17, slot, "127.0.0.1", 7777, 64, 64, 30,
          PackedStereoMediaReceiver.Bounds(65536, 65536, 64, 64, 2, 3000, 4000, 100, 15000, 10000, 8, 250),
          object : EmbeddedDuplexReceiver.RuntimeFactory {
            override fun stage(w: Int, h: Int, n: Int, fps: Int, route: Long) = object : EmbeddedDuplexReceiver.ProjectionResource {
              override fun routeGeneration() = route
              override fun decoderToken() = 42L
              override fun readerGeneration() = 43L
              override fun release():Boolean {readerReleased=true;return true}
            }
            override fun create(p: EmbeddedDuplexReceiver.ProjectionResource, host: String, port: Int, gen: Long,
                bounds: PackedStereoMediaReceiver.Bounds, listener: PackedStereoMediaReceiver.FrameLifecycleListener): EmbeddedDuplexReceiver.ReceiverRuntime {
              val owner = object : MediaOwnerProvider {
                var revision = 0L; var state = "prepared"
                override fun execute(a: MediaOwnerAction, c: CancellationHandle): MediaProviderReadback {
                  state = if (a.actionKind() == "stop") "stopped" else "receiver_armed"
                  receiverStopped=state=="stopped"
                  return MediaProviderReadback(a, "modeled.receiver", ++revision, state, "receipt.$revision")
                }
                override fun compensate(a: MediaOwnerAction, c: CancellationHandle) = execute(a, c)
                override fun snapshot() = MediaRuntimeSnapshot(gen, revision, state, state == "stopped", "modeled resource", "modeled.receiver")
              }
              return object : EmbeddedDuplexReceiver.ReceiverRuntime {
                override fun provider() = owner
                override fun start() {}
                override fun snapshot() = owner.snapshot()
                override fun prepareDormantDecoder(format:org.json.JSONObject) {}
                override fun preparedDecoderHeld() = owner.snapshot().state()=="prepared"
                override fun preparedDecoderOwned() = !owner.snapshot().terminal()
                override fun preparedDeliveryOpened() = owner.snapshot().state()=="receiver_armed"
              }
            }
          })
    }
    fun dormant(id:String)=receiver.prepareDormantDecoder(id,org.json.JSONObject(),java.util.function.BooleanSupplier{false})
    fun requireRendererTerminal(id:String){
      check(rendererId==id)
      check(rendererReleased){"actual Peer Own-reuse reservation remains active"}
    }
    fun archive(id:String){requireRendererTerminal(id);receiver.javaClass.getDeclaredMethod("retireArchivedPreparedMetadata",String::class.java).invoke(receiver,id)}
    fun arm(seq: Int) = receiver.execute(action("arm_receiver", "start", seq), CancellationHandle(17))
    fun stop(seq: Int) = receiver.execute(action("stop", "stop", seq), CancellationHandle(17))
    fun words(route: Long, result: SpatialVideoSourceResult, stages: Long) = SpatialVideoSourceNativeReadback(
        route, SpatialVideoSource.Peer, 42, 43, 11, 12, 1, 1, 1000, stages, result, SpatialVideoSourceReason.None, false, 0, 0)
  }
  internal fun ready(id:String,route:Long,identity:org.json.JSONObject?=null):String {
    val v=org.json.JSONObject().put("${'$'}schema","rusty.quest.spatial.prepared_peer_renderer_own_reuse.v2").put("preparation_id",id)
    for(key in arrayOf("app_process_generation","native_executor_generation","launch_challenge","surface_generation","layer_switch_count","own_claim_generation","own_process_generation","own_source_generation","own_construction_attempts"))v.put(key,1)
    for(key in arrayOf("app_record_sha256","config_sha256"))v.put(key,"a".repeat(64))
    for(key in arrayOf("request_sha256","resource_target_sha256"))v.put(key,"sha256:"+"b".repeat(64))
    v.put("peer_route_generation",route).put("state","ready")
    for(key in arrayOf("peer_gpu_worker_created","peer_reservation_released","own_resources_retired"))v.put(key,false)
    for(key in arrayOf("source_hold_acquired","source_hold_released","prepare_calls_joined"))v.put(key,true)
    for(key in arrayOf("peer_frame_import_count","peer_present_count"))v.put(key,0)
    if(identity!=null)for(key in arrayOf("app_process_generation","app_record_sha256","native_executor_generation","config_sha256","request_sha256","resource_target_sha256"))v.put(key,identity.get(key))
    return v.toString()
  }
  private fun action(kind: String, operation: String, sequence: Int) = MediaOwnerAction.parse(
      """{"${'$'}schema":"rusty.quest.android.media.execution-ticket.v1","capability":"capability.test","executor_generation":17,"action_id":"action.$sequence","authority_epoch_id":"epoch.test","media_acceptance_authority_revision":1,"expected_runtime_revision":0,"client_id":"client.test","lease_id":"lease.test","sequence":$sequence,"operation":"$operation","owner_kind":"sink","action_kind":"$kind","owner_id":"owner.test","provider_kind":"receiver","resource_id":"resource.test"}""")
}
