package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex

import io.github.mesmerprism.rustyquest.media.OwnerDispatchTcpEndpoint
import io.github.mesmerprism.rustyquest.spatial_camera_panel.OwnPackedPoolNative
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import org.json.JSONObject

/** One process, real Java SDK/Original/archive/phase and Receiver/Display/routing owners.
 * JNI authority/setup receipts, Android directory fsync and codec resources are modeled leaves.
 * The Native completed-successor function is executed separately, not claimed as JNI here. */
internal object FreshPairLifecycleHost {
  @JvmStatic fun main(args:Array<String>) {
    val sdk=PreparedSettledCustodyHost.Fixture(Path.of(args[0]),false)
    val media=PreparedSecondLifetimeHost.Fixture()
    val platform=EmbeddedDuplexPlatform()
    EmbeddedDuplexProcessHost.instance.platform=platform
    val firstSpan=OwnerDispatchTcpEndpoint.RequestSpan(platform.endpoint)
    OwnerDispatchTcpEndpoint.CURRENT.set(firstSpan)
    platform.bindIncomingFreshSetupPhase("ceremony.first",null,"ceremony.first","initial")
    firstSpan.done=true // Modeled endpoint primitive reports actual handler joined.
    val events=mutableListOf("first_setup_bound_and_joined")
    EmbeddedDuplexResources.lifecycle=object:EmbeddedDuplexResources.LifecycleHooks {
      override fun prepare(id:String):JSONObject {
        media.rendererIdentity=JSONObject(EmbeddedDuplexProcessHost.ACTOR.toString())
          .put("request_sha256",PreparedSettledCustodyHost.requestSha)
          .put("resource_target_sha256","sha256:"+"c".repeat(64))
        return media.dormant(id)
      }
      override fun renderer(id:String,released:Boolean):JSONObject {
        check(id==media.rendererId)
        if(released)media.requireRendererTerminal(id)
        return JSONObject(PreparedSecondLifetimeHost.ready(id,media.receiver.routeGeneration(),media.rendererIdentity))
          .put("state",if(media.rendererReleased)"released" else "ready")
          .put("peer_reservation_released",media.rendererReleased)
      }
      override fun opened()=media.receiver.preparedDeliveryOpened()
      override fun terminal()=media.receiver.snapshot().terminal()&&media.rendererReleased
      override fun retire(id:String)=media.archive(id)
    }
    sdk.owner.begin(PreparedSettledCustodyHost.PREP,"prepare_peer_start",PreparedSettledCustodyHost.CHALLENGE,
      JSONObject().put("actual_call","prepare_peer_start"),{sdk.actual=EmbeddedDuplexOriginals.capture();sdk.sdk})
    sdk.complete();sdk.joined();check(sdk.ack().getString("retention")=="compacted")
    val firstProof=Files.readAllBytes(sdk.settled());check(firstProof.isNotEmpty())
    events+="first_sdk_actual_future_ack_settled"
    media.arm(1);check(media.receiver.preparedDeliveryOpened());events+="first_start_actual_receiver"
    platform.requestIncomingFreshStop(false)
    if(args.getOrNull(2)=="failed-stop") {
      OwnPackedPoolNative.retirementAllowed=false
      check(runCatching{media.stop(2)}.isFailure)
      check(media.abortIds.isEmpty()&&!sdk.resources.productResourcesTerminal())
      val absentStop=JSONObject().put("action","peer_stop").put("status","terminal").put("peer_physical_cleanup","terminal").put("renewal_pending",false)
      check(runCatching{EmbeddedDuplexPreparedArchive(sdk.context).retain(EmbeddedDuplexCanonicalJson.encode(sdk.archiveBody(absentStop)),EmbeddedDuplexProcessHost.ACTOR,sdk.holder)}.isFailure)
      check(Files.readAllBytes(sdk.settled()).contentEquals(firstProof))
      Files.write(Path.of(args[1]),JSONObject().put("passed",true).put("mode","failed-stop")
        .put("second_sdk_entered",false).put("second_start_entered",false).put("old_stop_retained",platform.incomingFreshDesiredStop).toString().toByteArray())
      println("PASS actual failed Receiver teardown cannot archive or construct next SDK/Pair/Start; first custody unchanged")
      return
    }
    media.stop(2);media.requireRendererTerminal(sdk.holder.id())
    val stop=sdk.stop();check(media.receiver.snapshot().terminal())
    events+="ordinary_stop_actual_receiver_renderer_and_original_ack"
    val body=sdk.archiveBody(stop)
    val archive=EmbeddedDuplexPreparedArchive(sdk.context)
    val retained=archive.retain(EmbeddedDuplexCanonicalJson.encode(body),EmbeddedDuplexProcessHost.ACTOR,sdk.holder)
    sdk.holder.retireArchivedMetadata(body)
    check(media.rendererReleased&&sdk.resources.retired)
    check(Files.readAllBytes(sdk.settled()).contentEquals(firstProof))
    events+="first_sdk_durable_archive_real_resource_retirement"

    val secondRequest=JSONObject(PreparedSettledCustodyHost.request.toString())
      .put("preparation_id","5".repeat(32)).put("original_id","6".repeat(32))
    val next=CompletableFuture<android.os.Bundle>()
    val record=arrayOfNulls<EmbeddedDuplexOriginals.Record>(1)
    sdk.owner.begin("6".repeat(32),"prepare_peer_start",PreparedSettledCustodyHost.CHALLENGE,
      JSONObject().put("actual_call","second_prepare"),{record[0]=EmbeddedDuplexOriginals.capture();next})
    val resources=EmbeddedDuplexResources()
    val holder=EmbeddedDuplexPreparedStart(resources,secondRequest,record[0],EmbeddedDuplexProcessHost.ACTOR,
      PreparedSettledCustodyHost.arm(),EmbeddedDuplexRuntimeConfigurationBinding.Context())
    val sender=holder.sender();PreparedSettledCustodyHost.requestSha=sender.getString("request_sha256")
    val decode=JSONObject().put("${'$'}schema","rusty.quest.android.media.prepared_decoder_request.v1")
      .put("preparation_id",holder.id()).put("request_sha256",PreparedSettledCustodyHost.requestSha)
      .put("peer_preparation_id","4".repeat(32)).put("peer_sender_receipt_sha256","sha256:"+"e".repeat(64))
      .put("peer_format",JSONObject().put("modeled_format",true))
    val decoder=holder.decoder(decode);holder.finalizeTransport();check(holder.ready())
    val status=JSONObject(sdk.status.toString()).put("original_id","6".repeat(32)).put("preparation_id",holder.id())
      .put("request_sha256",PreparedSettledCustodyHost.requestSha)
      .put("owner",JSONObject().put("${'$'}schema","rusty.quest.android.media.prepared_start_owner.v1")
        .put("request",secondRequest).put("sender_entered",true).put("decoder_entered",true)
        .put("sender",sender).put("decoder_request",decode).put("decoder",decoder)).put("remote_decoder",decoder)
    holder.requireNativeStatus(status,true);next.complete(PreparedSettledCustodyHost.response(status))
    sdk.owner.joined(record[0]);val q=PreparedSettledCustodyHost.observation(sdk.owner.query("6".repeat(32),PreparedSettledCustodyHost.CHALLENGE))
    val ack=PreparedSettledCustodyHost.observation(sdk.owner.acknowledge("6".repeat(32),PreparedSettledCustodyHost.CHALLENGE,
      q.getString("request_sha256"),q.getString("outcome_sha256"),"9".repeat(64)))
    check(ack.getString("retention")=="compacted"&&holder.id()!=sdk.holder.id())
    events+="second_sdk_distinct_holder_actual_future_ack"

    val nextSpan=OwnerDispatchTcpEndpoint.RequestSpan(platform.endpoint);OwnerDispatchTcpEndpoint.CURRENT.set(nextSpan)
    // JNI primitive here represents the Native completed-prior gate. Its actual
    // production body is separately exercised by the focused Native controls.
    platform.stageIncomingCompletedFreshSetupSuccessor("ceremony.first","ceremony.second")
    platform.assertIncomingFreshSetupInitialAllowed("ceremony.second","ceremony.second")
    platform.freshSetupInitialPhysicalEligibilityJson("ceremony.second","ceremony.second")
    check(platform.incomingFreshDesiredStop)
    val phase=JSONObject(EmbeddedDuplexProcessHost.ACTOR.toString())
      .put("${'$'}schema","rusty.quest.embedded_duplex.fresh_setup_phase_archive.v2")
      .put("native_request_id","ceremony.first").put("phase_request_id","ceremony.first")
      .put("original_binding",JSONObject().put("kind","incoming_fresh_setup").put("native_request_id","ceremony.first"))
      .put("phase_status","completed").put("phase_state",JSONObject().put("completed",true))
      .put("authority_snapshot",JSONObject()).put("prior_archive_projection",JSONObject())
      .put("native_reservation",JSONObject()).put("preparation",JSONObject.NULL)
    val phaseRaw=EmbeddedDuplexCanonicalJson.encode(phase)
    if(args.getOrNull(2)=="archive-failure") {
      android.system.Os.failPhase=true
      check(runCatching{platform.archiveFreshSetupPhase(phaseRaw)}.isFailure)
      check(runCatching{platform.bindIncomingFreshSetupPhase("ceremony.second",null,"ceremony.second","initial")}.isFailure)
      check(platform.incomingFreshDesiredStop&&platform.incomingFreshRequestId=="ceremony.first")
      check(!media.receiver.preparedDeliveryOpened()&&Files.readAllBytes(sdk.settled()).contentEquals(firstProof))
      Files.write(Path.of(args[1]),JSONObject().put("passed",true).put("mode","archive-failure")
        .put("phase_archive_ack_earned",false).put("old_stop_retained",true).put("second_start_entered",false).toString().toByteArray())
      println("PASS actual phase archive fsync failure retains old Stop and refuses successor bind/second Start")
      return
    }
    val phaseAck=JSONObject(platform.archiveFreshSetupPhase(phaseRaw))
    if(args.getOrNull(2)=="concurrent-stop") {
      platform.requestIncomingFreshStop(false)
      check(runCatching{platform.bindIncomingFreshSetupPhase("ceremony.second",null,"ceremony.second","initial")}.isFailure)
      check(platform.incomingFreshDesiredStop&&platform.incomingFreshRequestId=="ceremony.first")
      check(!media.receiver.preparedDeliveryOpened()&&holder.ready())
      check(Files.readAllBytes(sdk.settled()).contentEquals(firstProof))
      Files.write(Path.of(args[1]),JSONObject().put("passed",true).put("mode","concurrent-stop")
        .put("old_stop_retained",true).put("phase_archive",phaseAck).put("second_sdk_earned",true)
        .put("second_start_entered",false).toString().toByteArray())
      println("PASS Stop after staging/archive invalidates new lifetime bind; second SDK custody earned, no second Start")
      return
    }
    platform.bindIncomingFreshSetupPhase("ceremony.second",null,"ceremony.second","initial")
    check(!platform.incomingFreshDesiredStop&&platform.incomingFreshRequestId=="ceremony.second")
    check(runCatching{platform.assertIncomingFreshSetupInitialAllowed("ceremony.first","ceremony.first")}.isFailure)
    check(runCatching{platform.freshSetupPhysicalEligibilityForIncomingJson("ceremony.first","ceremony.first")}.isFailure)
    check(runCatching{platform.bindIncomingFreshSetupPhase("ceremony.third",null,"ceremony.third","initial")}.isFailure)
    platform.freshSetupPhysicalEligibilityForIncomingJson("ceremony.second","ceremony.second")
    val request=byteArrayOf(1,2,3);check(platform.exchangeOwnerFrame("peer.other",request).contentEquals(request))
    check(OwnerDispatchTcpEndpoint.ownedCalls==1&&platform.ownedExchanges.isEmpty())
    check(EmbeddedDuplexFreshPhaseArchive(sdk.context).archivedIncoming("ceremony.first","ceremony.first",EmbeddedDuplexProcessHost.ACTOR))
    events+="fresh_pair_actual_phase_archive_bind_registered_and_owned_exchange"
    media.arm(3);check(media.receiver.preparedDeliveryOpened());events+="second_start_actual_receiver"
    check(media.receiver.routeGeneration()>1&&media.abortIds==listOf(sdk.holder.id()))
    // Later Stop targets only the new registered group and still cancels its
    // real owned-exchange gate. A rejected old phase is never re-adopted.
    platform.requestIncomingFreshStop(false)
    check(runCatching{platform.bindIncomingFreshSetupPhase("ceremony.second",null,"ceremony.second","initial")}.isFailure)
    check(runCatching{platform.exchangeOwnerFrame("peer.other",request)}.isFailure)
    check(runCatching{platform.freshSetupPhysicalEligibilityForIncomingJson("ceremony.first","ceremony.first")}.isFailure)
    media.stop(4);media.requireRendererTerminal(holder.id())
    Files.write(Path.of(args[1]),JSONObject().put("passed",true).put("events",events)
      .put("first_sdk",PreparedSettledCustodyHost.PREP).put("second_sdk","6".repeat(32))
      .put("first_preparation",sdk.holder.id()).put("second_preparation",holder.id())
      .put("initial_settled_sha256",EmbeddedDuplexCanonicalJson.sha256Bytes(firstProof))
      .put("prepared_archive",retained).put("fresh_phase_archive",phaseAck)
      .put("jni_authority_codec_android_primitives_modeled",true).toString().toByteArray())
    println("PASS one-process real Original/ACK/SDK/archive/phase/Receiver/display/routing first setup -> first SDK -> Start -> ordinary Stop -> second SDK -> fresh Pair -> second Start; JNI authority/codec/network primitives modeled")
  }
}
