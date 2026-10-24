package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex

import android.content.Context
import android.os.Bundle
import io.github.mesmerprism.rustyquest.media.*
import io.github.mesmerprism.rustyquest.spatial_camera_panel.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.function.BooleanSupplier
import org.json.JSONObject

/** Persistent, single-role Java half of the two-role Native composition fixture.
 * Original/Future/ACK, SDK/archive, Receiver/Display/routing are production owners.
 * Android, codec, renderer and socket primitives below are explicitly modeled.
 * Native lifecycle/status/archive bodies and execution tickets arrive from the
 * persistent production Rust driver; this fixture never manufactures a Stop. */
internal object RenewalSuccessorLifecycleHost {
  private fun copy(value: JSONObject) = JSONObject(value.toString())
  private fun keys(value: JSONObject, vararg expected: String) {
    check(value.keySet() == expected.toSet()) { "closed fixture payload differs" }
  }
  private fun same(a: Any?, b: Any?) = EmbeddedDuplexCanonicalJson.sameValue(a, b)
  private fun id(value: String): String {
    check(value.matches(Regex("[0-9a-f]{32}"))) { "fixture Original ID differs" }
    return value
  }

  @JvmStatic fun main(args: Array<String>) {
    require(args.size == 3) { "root, fixed peer and modeled startup actor required" }
    val peer = args[1]
    require(peer == "Q" || peer == "W")
    val runtime = Runtime(Path.of(args[0]), JSONObject(args[2]))
    System.`in`.bufferedReader().forEachLine { line ->
      check(line.length <= 8 * 1024 * 1024) { "fixture request budget" }
      val request = JSONObject(line)
      keys(request, "step", "peer", "payload")
      val step = request.getString("step")
      val result = try {
        check(request.getString("peer") == peer) { "cross-role fixture callback" }
        runtime.call(step, request.getJSONObject("payload"))
      } catch (failure: Exception) {
        // No successful callback, Future completion or ACK is invented here.
        JSONObject().put("error", JSONObject().put("class", failure.javaClass.name)
          .put("message", failure.message ?: "fixture callback failed"))
      }
      println(JSONObject().put("step", step).put("peer", peer).put("result", result))
      System.out.flush()
    }
  }

  private class Operation(val operation: String, val challenge: String) {
    val future = CompletableFuture<Bundle>()
    lateinit var record: EmbeddedDuplexOriginals.Record
  }

  private class Prepared(val holder: EmbeddedDuplexPreparedStart, val resources: Resources)

  private class Runtime(root: Path, val actor: JSONObject) {
    val context: Context
    val originals: EmbeddedDuplexOriginals
    val operations = linkedMapOf<String, Operation>()
    val prepared = linkedMapOf<String, Prepared>()
    var media: Media
    var profileConfigured = false
    var physicalResources: EmbeddedDuplexResources
    var physicalHolder: EmbeddedDuplexPreparedStart? = null
    init {
      keys(actor, "app_process_generation", "app_record_sha256", "native_executor_generation", "config_sha256")
      for (key in arrayOf("app_process_generation", "native_executor_generation")) {
        check(actor.get(key) is Int || actor.get(key) is Long)
        check(actor.getLong(key) > 0)
      }
      for (key in arrayOf("app_record_sha256", "config_sha256")) {
        check(actor.get(key) is String && actor.getString(key).matches(Regex("[0-9a-f]{64}")))
      }
      Files.createDirectory(root)
      context = Context(root.toFile())
      EmbeddedDuplexProcessHost.appGeneration = actor.getLong("app_process_generation")
      for (key in actor.keySet()) EmbeddedDuplexProcessHost.ACTOR.put(key, actor.get(key))
      EmbeddedDuplexProcessHost.nativeActor = true
      EmbeddedDuplexProcessHost.appRecord = true
      EmbeddedDuplexProcessHost.exchanges = true
      // One JVM per modeled app actor: no reset or replacement of the singleton.
      originals = EmbeddedDuplexOriginals.forApplication(context)
      media = Media(actor.getLong("native_executor_generation"))
      physicalResources = object : EmbeddedDuplexResources() {
        override fun generation() = actor.getLong("native_executor_generation")
        override fun productResourcesTerminal() = media.terminal()
      }
    }
    fun operation(payload: JSONObject) = operations.getValue(id(payload.getString("original_id")))
    fun prepared(payload: JSONObject) = prepared.getValue(id(payload.getString("original_id")))
    fun response(operation: Operation, receipt: JSONObject): Bundle = Bundle().also {
      it.putString("schema", "rusty.quest.embedded_duplex.operator_transport.v1")
      it.putString("status", "observed")
      it.putString("challenge", operation.challenge)
      it.putString("receipt", receipt.toString())
    }
    fun observe(bundle: Bundle) = JSONObject(bundle.getString("observation_json"))
    fun complete(operation: Operation, receipt: JSONObject) = completeBundle(operation, response(operation, receipt))
    fun completeBundle(operation: Operation, bundle: Bundle): JSONObject {
      check(!operation.future.isDone) { "operation callback already completed" }
      operation.future.complete(bundle)
      originals.joined(operation.record)
      return observe(originals.query(operation.record.id(), operation.challenge))
    }
    fun physicalLeaves() = JSONObject().put("receiver", media.receiver.physicalSnapshot())
      .put("renderer_terminal", media.rendererId == null || media.rendererReleased)
      .put("owner_callback_active", media.ownerCallbackActive).put("peer_effect_entered", media.peerEffectEntered)
    fun call(step: String, payload: JSONObject): JSONObject = when (step) {
      "configure_profile" -> {
        keys(payload, "width", "height", "frame_rate_hz")
        check(!profileConfigured && operations.isEmpty() && prepared.isEmpty()) { "fixture profile already entered" }
        for (name in payload.keySet()) check(payload.get(name) is Int && payload.getInt(name) > 0)
        media = Media(actor.getLong("native_executor_generation"), payload.getInt("width"), payload.getInt("height"), payload.getInt("frame_rate_hz"))
        profileConfigured = true
        copy(payload)
      }
      "operation_begin" -> {
        keys(payload, "original_id", "operation", "challenge", "request")
        val originalId = id(payload.getString("original_id"))
        check(!operations.containsKey(originalId)) { "Original dispatch replay" }
        val operation = Operation(payload.getString("operation"), id(payload.getString("challenge")))
        originals.begin(originalId, operation.operation, operation.challenge, payload.getJSONObject("request")) {
          operation.record = EmbeddedDuplexOriginals.capture()
          EmbeddedDuplexOriginals.bindActualActor(JSONObject().put("package_id", "host.fixture")
            .put("native_generation", actor.get("native_executor_generation"))
            .put("app_record_sha256", actor.get("app_record_sha256")))
          operation.future
        }
        operations[originalId] = operation
        observe(originals.query(originalId, operation.challenge))
      }
      "sdk_sender" -> {
        keys(payload, "request", "actor", "arm", "encoded_format")
        check(same(payload.getJSONObject("actor"), actor)) { "SDK startup actor changed" }
        val request = payload.getJSONObject("request")
        val originalId = id(request.getString("original_id"))
        val operation = operations.getValue(originalId)
        check(operation.operation == "prepare_peer_start" && !prepared.containsKey(originalId))
        val resources = Resources(actor, request, payload.getJSONObject("encoded_format"), media)
        val constructor = request.getJSONObject("configuration_binding")
        val holder = EmbeddedDuplexPreparedStart(resources, request, operation.record, actor,
          payload.getJSONObject("arm"), object : EmbeddedDuplexRuntimeConfigurationBinding.Context() {
            override fun require(value: JSONObject) {
              check(same(value, constructor)) { "selected constructor callback changed" }
            }
          })
        prepared[originalId] = Prepared(holder, resources)
        physicalResources = resources
        physicalHolder = holder
        holder.sender().also { media.rendererIdentity = copy(request).put("request_sha256", it.get("request_sha256")) }
      }
      "preparation_eligibility", "preparation_target" -> {
        keys(payload, "original_id", "arm", "resource_target")
        val operation = operation(payload)
        check(operation.operation == "prepare_peer_start") { "selected preparation Original absent" }
        check(EmbeddedDuplexOriginals.capture() == null) { "fixture callback scope leaked" }
        val target = copy(payload.getJSONObject("resource_target"))
        if (physicalHolder == null) physicalResources = object : EmbeddedDuplexResources() {
          override fun generation() = actor.getLong("native_executor_generation")
          override fun preparationTarget(config: String): JSONObject {
            check(config == actor.getString("config_sha256")); return copy(target)
          }
          override fun productResourcesTerminal() = media.terminal()
        }
        check(same(physicalResources.preparationTarget(actor.getString("config_sha256")), target)) {
          "actual prepared resource target changed"
        }
        val scope = EmbeddedDuplexOriginals.enter(operation.record)
        try {
          val hook = EmbeddedDuplexProcessHost::class.java.getDeclaredMethod("fixturePrepared",
            Context::class.java, EmbeddedDuplexResources::class.java, EmbeddedDuplexPreparedStart::class.java,
            JSONObject::class.java, JSONObject::class.java, Boolean::class.javaPrimitiveType)
          hook.isAccessible = true
          try { hook.invoke(null, context, physicalResources, physicalHolder, physicalLeaves(),
            payload.getJSONObject("arm"), step == "preparation_target") as JSONObject }
          catch (failure: java.lang.reflect.InvocationTargetException) { throw (failure.cause as? Exception ?: failure) }
        } finally { scope.close() }
      }
      "sdk_decoder" -> {
        keys(payload, "original_id", "request")
        prepared(payload).holder.decoder(payload.getJSONObject("request"))
      }
      "sdk_finalize" -> {
        keys(payload, "original_id")
        val holder = prepared(payload).holder
        holder.finalizeTransport()
        holder.status(holder.id())
      }
      "sdk_status" -> {
        keys(payload, "original_id")
        val holder = prepared(payload).holder
        holder.status(holder.id())
      }
      "sdk_complete" -> {
        keys(payload, "original_id", "receipt")
        val receipt = payload.getJSONObject("receipt")
        prepared(payload).holder.requireNativeStatus(receipt, true)
        complete(operation(payload), receipt)
      }
      "operation_complete" -> {
        keys(payload, "original_id", "receipt", "wrapper_action")
        val operation = operation(payload)
        check(payload.getString("wrapper_action") == operation.operation)
        if (operation.operation == "pair_session" || operation.operation == "fresh_pair_continue") {
          completeBundle(operation, EmbeddedDuplexOperatorResponses.pairBundle(operation.challenge,
            EmbeddedDuplexPairStatus.parse(payload.getJSONObject("receipt").toString())))
        } else {
        // Only the existing outer transport discriminator is supplied in Java.
        // In particular renewal_pending and the complete Stop body stay Native.
        val wrapper = JSONObject().put("schema", "rusty.quest.stereo.concurrent_qualification_receipt.v1")
          .put("action", operation.operation).put("runtime_config_sha256", actor.get("config_sha256"))
          .put("peer_lifecycle", payload.getJSONObject("receipt"))
        complete(operation, wrapper)
        }
      }
      "operation_query" -> {
        keys(payload, "original_id")
        val operation = operation(payload)
        observe(originals.query(operation.record.id(), operation.challenge))
      }
      "operation_ack" -> {
        keys(payload, "original_id", "consumer_sha256")
        val operation = operation(payload)
        val query = observe(originals.query(operation.record.id(), operation.challenge))
        check(query.getString("state") == "succeeded" && query.getString("effect_state") == "settled") {
          "only succeeded/settled actual Original may be acknowledged"
        }
        // Consumer construction/order is owned by the Rust/Python host driver.
        observe(originals.acknowledge(operation.record.id(), operation.challenge,
          query.getString("request_sha256"), query.getString("outcome_sha256"), payload.getString("consumer_sha256")))
      }
      "prepared_archive" -> {
        keys(payload, "exact_body")
        val exact = payload.getString("exact_body")
        val body = JSONObject(exact)
        val held = prepared.getValue(id(body.getString("original_id")))
        val ack = EmbeddedDuplexPreparedArchive(context).retain(exact, actor, held.holder)
        held.holder.retireArchivedMetadata(body)
        if (physicalHolder === held.holder) physicalHolder = null
        ack
      }
      "phase_archive" -> {
        keys(payload, "exact_body")
        EmbeddedDuplexFreshPhaseArchive(context).retain(payload.getString("exact_body"), actor)
      }
      "lifetime_archive" -> {
        keys(payload, "exact_body")
        JSONObject(EmbeddedDuplexPeerLifetimeArchive(context).retain(payload.getString("exact_body"), actor))
      }
      "renderer_status" -> {
        keys(payload, "preparation_id")
        val requested = id(payload.getString("preparation_id"))
        check(media.rendererId == requested) { "foreign renderer incarnation" }
        val held = prepared.values.single { it.holder.id() == requested }
        held.resources.rendererSnapshot()
      }
      "physical_snapshot" -> {
        keys(payload, "original_binding")
        val binding = payload.getJSONObject("original_binding")
        val entered = when (binding.getString("kind")) {
          "operator" -> {
            keys(binding, "kind", "original_id")
            val held = operations.getValue(id(binding.getString("original_id")))
            check(held.operation == "pair_session" || held.operation == "fresh_pair_continue") {
              "physical observation requires actual fresh-setup operator"
            }
            held.record
          }
          "incoming_fresh_setup" -> {
            keys(binding, "kind", "native_request_id")
            EmbeddedDuplexIncomingFreshPhases.id(binding.getString("native_request_id"))
            null
          }
          else -> error("physical Original binding differs")
        }
        check(EmbeddedDuplexOriginals.capture() == null) { "fixture callback scope leaked" }
        val scope = if (entered == null) null else EmbeddedDuplexOriginals.enter(entered)
        try {
          // The generated support hook installs these source-owned objects and
          // invokes the unchanged ProcessHost.freshPhysical producer. Missing
          // support refuses; there is no fallback eligibility equation here.
          val hook = EmbeddedDuplexProcessHost::class.java.getDeclaredMethod("fixturePhysical",
            Context::class.java, EmbeddedDuplexResources::class.java, EmbeddedDuplexPreparedStart::class.java,
            JSONObject::class.java, JSONObject::class.java)
          hook.isAccessible = true
          try { hook.invoke(null, context, physicalResources, physicalHolder, physicalLeaves(), binding) as JSONObject }
          catch (failure: java.lang.reflect.InvocationTargetException) {
            throw (failure.cause as? Exception ?: failure)
          }
        } finally { scope?.close() }
      }
      "receiver_start", "receiver_stop" -> {
        keys(payload, "ticket")
        val action = MediaOwnerAction.parse(payload.getJSONObject("ticket").toString())
        check(action.executorGeneration() == actor.getLong("native_executor_generation"))
        check(action.actionKind() == if (step == "receiver_start") "arm_receiver" else "stop")
        media.peerEffectEntered = true
        media.ownerCallbackActive = true
        try { JSONObject(media.receiver.execute(action, CancellationHandle(action.executorGeneration())).toJson()) }
        finally { media.ownerCallbackActive = false }
      }
      else -> error("unsupported fixture callback: $step")
    }
  }

  /** Codec/transport leaves expose the real configured request; ownership and
   * archive transitions remain in the whole Java PreparedStart/Receiver owners. */
  private class Resources(val actor: JSONObject, val request: JSONObject, val format: JSONObject, val media: Media) : EmbeddedDuplexResources() {
    fun rendererSnapshot() = renderer(false)
    override fun preparationTarget(config: String): JSONObject {
      check(config == actor.getString("config_sha256")); return copy(request.getJSONObject("resource_target"))
    }
    override fun preparationTargetSha256(config: String): String {
      check(config == actor.getString("config_sha256")); return request.getString("resource_target_sha256")
    }
    override fun generation() = actor.getLong("native_executor_generation")
    override fun prepareSender(stop: BooleanSupplier): JSONObject {
      check(!stop.asBoolean); return copy(format)
    }
    override fun preparedSenderClosed() = !preparedDeliveryOpened()
    override fun preparedDecoderClosed() = !preparedDeliveryOpened()
    override fun preparedSenderHandle() = "modeled.sender." + request.getString("preparation_id")
    override fun preparedDecoderHandle() = "modeled.decoder." + request.getString("preparation_id")
    override fun preparedSenderOwned() = !media.terminal()
    override fun preparedDecoderOwned() = !media.terminal()
    override fun preparedTransportConnected() = true
    override fun finalizePreparedTransport() {}
    override fun preparedDeliveryOpened() = media.receiver.preparedDeliveryOpened()
    override fun preparedPeerDeliveryCount() = if (preparedDeliveryOpened()) 1L else 0L
    override fun productResourcesTerminal() = media.terminal()
    override fun prepareDecoder(id: String, format: JSONObject, stop: BooleanSupplier): JSONObject {
      check(!stop.asBoolean); return media.receiver.prepareDormantDecoder(id, format, stop)
    }
    override fun renderer(released: Boolean): JSONObject {
      check(!released || media.rendererReleased); return media.rendererSnapshot()
    }
    override fun retireArchivedPreparedMetadata(id: String) {
      check(media.terminal() && id == media.rendererId)
      try { media.receiver.javaClass.getDeclaredMethod("retireArchivedPreparedMetadata", String::class.java).invoke(media.receiver, id) }
      catch (failure: java.lang.reflect.InvocationTargetException) { throw (failure.cause as? Exception ?: failure) }
    }
  }

  private class Media(val generation: Long, val width: Int = 64, val height: Int = 64, val fps: Int = 30) {
    var rendererIdentity: JSONObject? = null
    var rendererId: String? = null
    var rendererRoute = 0L
    var rendererReleased = false
    var receiverStopped = false
    var readerReleased = false
    var peerEffectEntered = false
    var ownerCallbackActive = false
    var nativeSource = SpatialVideoSource.Disabled
    val routing: SpatialVideoSourceRoutingCoordinator
    val receiver: EmbeddedDuplexReceiver
    fun terminal() = receiver.snapshot().terminal() && rendererReleased
    fun rendererSnapshot(): JSONObject {
      val identity = checkNotNull(rendererIdentity)
      val out = JSONObject().put("\$schema", "rusty.quest.spatial.prepared_peer_renderer.v2")
        .put("preparation_id", checkNotNull(rendererId)).put("route_generation", rendererRoute)
        .put("launch_challenge", 11L).put("surface_generation", 12L).put("layer_switch_count", 1L)
        .put("requested_width", width).put("requested_height", height).put("actual_width", width).put("actual_height", height)
        .put("worker_ordinal", 1L).put("state", if (rendererReleased) "terminal" else "ready")
        .put("wsi_ready", !rendererReleased).put("frame_work_admitted", false)
        .put("frame_import_count", 0L).put("present_count", 0L).put("cleanup_terminal", rendererReleased)
        .put("worker_joined", rendererReleased).put("failure", JSONObject.NULL)
        .put("source_hold_acquired", true).put("source_hold_released", rendererReleased).put("prepare_calls_joined", true)
      for (key in arrayOf("app_process_generation", "app_record_sha256", "native_executor_generation", "config_sha256", "request_sha256", "resource_target_sha256"))
        out.put(key, identity.get(key))
      return out
    }
    fun words(route: Long, result: SpatialVideoSourceResult, stages: Long, source: SpatialVideoSource = SpatialVideoSource.Peer) = SpatialVideoSourceNativeReadback(
      route, source, 42, 43, 11, 12, 1, 1, 1000, stages, result, SpatialVideoSourceReason.None, false, 0, 0)
    fun nativeWords(route: Long) = if (nativeSource == SpatialVideoSource.Disabled)
      words(route, SpatialVideoSourceResult.Inactive, SpatialVideoSourceStage.AcquisitionStopped, nativeSource)
      else words(route, SpatialVideoSourceResult.Effective, SpatialVideoSourceStage.requiredFor(nativeSource), nativeSource)
    init {
      OwnPackedPoolNative.retirementAllowed = true
      val adapter = object : SpatialVideoSourceExecutionAdapter {
        override fun producerState() = SpatialProjectionProducerState.NoProducerOwned
        override fun requestProducerCleanup(routeGeneration: Long, producer: SpatialProjectionProducerState.ProducerActive) = error("unexpected Own cleanup")
        override fun readProducerCleanup(pending: SpatialProjectionProducerState.CleanupPending) = error("unexpected Own cleanup read")
        override fun selectNativeProvider(request: SpatialVideoSourceNativeRequest, context: SpatialVideoSourceCarrierContext): SpatialVideoSourceNativeReadback {
          nativeSource = request.source
          return if (request.source == SpatialVideoSource.Disabled) words(request.routeGeneration,
            SpatialVideoSourceResult.Inactive, SpatialVideoSourceStage.AcquisitionStopped, request.source)
          else words(request.routeGeneration, SpatialVideoSourceResult.Pending, SpatialVideoSourceStage.ProviderSelected, request.source)
        }
        override fun readNativeSource(routeGeneration: Long) = nativeWords(routeGeneration)
        override fun stagePeerSettings(settings: SpatialVideoProjectionSettings, routeGeneration: Long) = error("unexpected Peer settings")
        override fun replacePeerSettings(settings: SpatialVideoProjectionSettings, routeGeneration: Long, reason: String) = error("unexpected Peer replacement")
        override fun preparePeerDecoderOwnership(routeGeneration: Long, reason: String) = true
        override fun stopSourceAcquisition(source: SpatialVideoSource, routeGeneration: Long, reason: String): Boolean {
          check(source == SpatialVideoSource.Peer) { "unexpected independent Own acquisition stop" }
          val current = routing.snapshot()
          check(current.generation == routeGeneration && current.requested == SpatialVideoSource.Disabled) {
            "modeled Peer acquisition stop route differs"
          }
          // Real Receiver has already stopped its provider and released its
          // staged reader. This models only Activity's exact Peer acquisition
          // primitive; renderer reservation remains held until real display
          // retirement invokes abortPreparedRenderer below.
          if (!receiverStopped || !readerReleased) return false
          nativeSource = SpatialVideoSource.Disabled
          return true
        }
        override fun updateSourceOwnerDemand(required: Boolean, reason: String) {}
      }
      routing = SpatialVideoSourceRoutingCoordinator(adapter) { 1000L }
      routing.beginProjectionSourceRequest(SpatialVideoSource.Local, null)
      val display = EmbeddedDuplexDisplayCoordinator(routing, { it(); true }, { true },
        { SpatialVideoSourceCarrierContext(11, 12) }, { true }, { true },
        { nativeWords(it) },
        { longArrayOf() }, { error("unexpected Own restart") }, prepareRenderer = { id, route ->
          check(rendererId == null || rendererReleased); rendererId = id; rendererRoute = route; rendererReleased = false
          receiverStopped = false; readerReleased = false
          rendererSnapshot().toString()
        }, readPreparedRenderer = { error("unexpected pending renderer") }, abortPreparedRenderer = { id ->
          check(id == rendererId && routing.snapshot().requested == SpatialVideoSource.Disabled)
          check(receiverStopped && readerReleased)
          rendererReleased = true
          rendererSnapshot().toString()
        })
      val slot = EmbeddedDuplexDisplaySlot(); slot.attach(display)
      receiver = EmbeddedDuplexReceiver(generation, slot, "127.0.0.1", 7777, width, height, fps,
        PackedStereoMediaReceiver.Bounds(65536, 4 * 1024 * 1024, width, height, 2, 3000, 4000, 100, 15000, 10000, 8, 250),
        object : EmbeddedDuplexReceiver.RuntimeFactory {
          override fun stage(w: Int, h: Int, n: Int, fps: Int, route: Long) = object : EmbeddedDuplexReceiver.ProjectionResource {
            override fun routeGeneration() = route
            override fun decoderToken() = 42L
            override fun readerGeneration() = 43L
            override fun release(): Boolean { readerReleased = true; return true }
          }
          override fun create(p: EmbeddedDuplexReceiver.ProjectionResource, host: String, port: Int, gen: Long,
            bounds: PackedStereoMediaReceiver.Bounds, listener: PackedStereoMediaReceiver.FrameLifecycleListener): EmbeddedDuplexReceiver.ReceiverRuntime {
            val provider = object : MediaOwnerProvider {
              var revision = 0L; var state = "prepared"
              override fun execute(action: MediaOwnerAction, cancellation: CancellationHandle): MediaProviderReadback {
                state = if (action.actionKind() == "stop") "stopped" else "receiver_armed"
                receiverStopped = state == "stopped"
                return MediaProviderReadback(action, "modeled.receiver", ++revision, state, "modeled.receipt.$revision")
              }
              override fun compensate(action: MediaOwnerAction, cancellation: CancellationHandle) = execute(action, cancellation)
              override fun snapshot() = MediaRuntimeSnapshot(gen, revision, state, state == "stopped", "modeled codec resource", "modeled.receiver")
            }
            return object : EmbeddedDuplexReceiver.ReceiverRuntime {
              override fun provider() = provider
              override fun start() {}
              override fun snapshot() = provider.snapshot()
              override fun prepareDormantDecoder(format: JSONObject) {}
              override fun preparedDecoderHeld() = provider.snapshot().state() == "prepared"
              override fun preparedDecoderOwned() = !provider.snapshot().terminal()
              override fun preparedDeliveryOpened() = provider.snapshot().state() == "receiver_armed"
            }
          }
        })
    }
  }
}
