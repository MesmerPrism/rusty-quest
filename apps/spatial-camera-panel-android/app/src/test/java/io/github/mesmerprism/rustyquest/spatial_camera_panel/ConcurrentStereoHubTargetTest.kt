package io.github.mesmerprism.rustyquest.spatial_camera_panel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
class ConcurrentStereoHubTargetTest {
  @Test fun actualReducerRetirementCancelsOnlyCurrentBindingCommands() {
    val current = modeledRegisteredHubSession()
    val lifetimes = ConnectionHubCommandLifetimes()
    val token = lifetimes.issue(current.bindingGeneration)
    val stale = io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.reduce(current, io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Event.binderDied(current.bindingGeneration-1,20)).state
    lifetimes.transition(current,stale); lifetimes.retireBinding(current.bindingGeneration-1)
    assertFalse(token.get())
    val retired = io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.reduce(current, io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Event.binderDied(current.bindingGeneration,21)).state
    assertFalse(retired.isRegistered); lifetimes.transition(current,retired); assertTrue(token.get())
    assertTrue(lifetimes.issue(current.bindingGeneration).get())
    val successor = lifetimes.issue(current.bindingGeneration+1)
    lifetimes.retireBinding(current.bindingGeneration); assertFalse(successor.get())
    lifetimes.close(); assertTrue(successor.get()); assertTrue(lifetimes.issue(current.bindingGeneration+2).get())
  }
  @Test fun exactExistingDescriptorCanonicalizationAndClosedCommands() {
    val r=ConcurrentStereoHubContract.registration(JSONObject())
    val commands=r.getJSONArray("commands")
    val canonical="v1\n"+r.getString("surface_id")+"\n"+r.getString("display_label")+"\n"+r.getString("description")+"\n"+(0 until commands.length()).joinToString("") { val c=commands.getJSONObject(it);c.getString("command")+"|"+c.getString("display_label")+"|"+c.getString("required_controller_capability")+"\n" }
    val sha="sha256:"+MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    assertEquals(sha,r.getString("surface_contract_sha256")); assertEquals(2,commands.length())
  }
  @Test fun realAuthorizationRejectsCrossProfileProviderAndArguments() {
    val command=ConcurrentStereoHubContract.OWN; val receipt=modeledConcurrentAuthorization(command)
    requireConnectionHubProfileCommandAuthorization("hub-request",ConcurrentStereoHubContract.SURFACE_ID,command,JSONObject(),receipt,ConnectionHubSurfaceProfile.ConcurrentStereo)
    assertThrows(IllegalArgumentException::class.java) { requireConnectionHubCommandAuthorization("hub-request",ConcurrentStereoHubContract.SURFACE_ID,command,JSONObject(),receipt) }
    assertThrows(IllegalArgumentException::class.java) { requireConnectionHubProfileCommandAuthorization("hub-request",ConcurrentStereoHubContract.SURFACE_ID,command,JSONObject().put("policy",0),receipt,ConnectionHubSurfaceProfile.ConcurrentStereo) }
    receipt.getJSONObject("command_authorization").put("provider_id",ConnectionHubLockedPlaylistContract.PROVIDER_ID)
    assertThrows(IllegalArgumentException::class.java) { requireConnectionHubProfileCommandAuthorization("hub-request",ConcurrentStereoHubContract.SURFACE_ID,command,JSONObject(),receipt,ConnectionHubSurfaceProfile.ConcurrentStereo) }
  }
  @Test fun retiredEffectRequiresExactRevisionPolicyCurrentPairAndFreshSample() {
    val s=JSONObject().put("revision",8).put("running",true).put("pair_current",true).put("policy_pixel_current",true).put("policy","own").put("sample_elapsed_ms",1000)
    assertTrue(ConcurrentStereoHubContract.effectObserved(ConcurrentStereoHubContract.OWN,8,s,1500))
    assertFalse(ConcurrentStereoHubContract.effectObserved(ConcurrentStereoHubContract.PEER,8,s,1500))
    assertFalse(ConcurrentStereoHubContract.effectObserved(ConcurrentStereoHubContract.OWN,7,s,1500))
    assertFalse(ConcurrentStereoHubContract.effectObserved(ConcurrentStereoHubContract.OWN,8,s,2001))
    assertFalse(ConcurrentStereoHubContract.effectObserved(ConcurrentStereoHubContract.OWN,8,s,999))
    for(key in listOf("running","pair_current","policy_pixel_current")){val damaged=JSONObject(s.toString()).put(key,false);assertFalse(ConcurrentStereoHubContract.effectObserved(ConcurrentStereoHubContract.OWN,8,damaged,1500))}
  }
  @Test fun actualRetainedStaleSampleDeniesAndModeledCurrentLineageDamageDenies() {
    val actual=JSONObject(java.io.File(System.getProperty("duplex.receipt")).readText()).getJSONObject("receipt").getJSONArray("native_snapshot")
    val retained=LongArray(actual.length()){actual.getLong(it)};val policy=LongArray(6){retained[138+it]};val process=retained[2];val revision=retained[144]
    assertFalse(ConcurrentStereoHubContract.currentPolicyPixels(retained,policy,revision,process,retained[5],false))
    // Explicit future modeled current sample, never device qualification.
    val modeled=retained.clone();modeled[27]=revision;modeled[41]=modeled[54]-500_000_000;modeled[35]=1;modeled[146]=1;modeled[38]=6
    for(i in 0..5){modeled[28+i]=policy[i];modeled[138+i]=policy[i]}
    for(o in 0..1){val b=64+32*o;val pixel=128+5*o;modeled[b]=1;modeled[b+2]=process;modeled[b+3]=o+1L;modeled[b+4]=10;modeled[pixel]=1;modeled[pixel+1]=process;modeled[pixel+2]=o+1L;modeled[pixel+3]=9;modeled[pixel+4]=6}
    assertTrue(ConcurrentStereoHubContract.currentPolicyPixels(modeled,policy,revision,process,retained[5],false))
    assertFalse(ConcurrentStereoHubContract.currentPolicyPixels(modeled,policy,revision,process,retained[5],true))
    for(index in listOf(2,5,6,27,34,35,38,138,144,145,146,159)){val d=modeled.clone();d[index]=d[index]+1;assertFalse("word $index",ConcurrentStereoHubContract.currentPolicyPixels(d,policy,revision,process,retained[5],false))}
    for(o in (0..1).filter {policy.take(4).contains(it.toLong())}){for(index in listOf(64+o*32,66+o*32,128+o*5,129+o*5,130+o*5,132+o*5)){val d=modeled.clone();d[index]=0;assertFalse("bank word $index",ConcurrentStereoHubContract.currentPolicyPixels(d,policy,revision,process,retained[5],false))}}
  }
}

internal fun modeledRegisteredHubSession(): io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.State {
  var state=io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.initial(7)
  fun step(event:io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Event) { state=io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.reduce(state,event).state }
  step(io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Event.start(0))
  val binding=state.bindingGeneration
  step(io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Event.bindReturned(binding,true,1))
  step(io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Event.connected(binding,2))
  step(io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Event.deathLinked(binding,3))
  repeat(4) { step(io.github.mesmerprism.rustyquest.broker_admission.ConnectionHubAdmissionSessionReducer.Event.reply(binding,state.pending.correlationId,true,"","epoch.test",10L+it)) }
  assertTrue(state.isRegistered)
  return state
}

internal fun modeledConcurrentAuthorization(command: String): JSONObject {
    val requestId = "epoch-7.request.hub-request"
    val instanceId = "provider.instance-1"
    val details =
        JSONObject()
            .put("command_id", command)
            .put("lease_id", "lease-1")
            .put("session_id", "session-1")
            .put("expected_transport_epoch", 4L)
            .put(
                "typed_params_sha256",
                "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
            )
            .put(
                "typed_params_schema_id",
                "rusty.manifold.connection_hub.typed_params.empty.v1",
            )
            .put(
                "typed_params_schema_sha256",
                "sha256:7eedc1ccca80b83dbd121d1e4bae4f6a6c9c1561e1a08d6d5919c668d5406a51",
            )
            .put(
                "external_request_sha256",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            )
    val authorization =
        JSONObject()
            .put("\$schema", "rusty.manifold.connection_hub.command_authorization.v2")
            .put("proves_application_effect", false)
            .put("request_id", requestId)
            .put("provider_instance_id", instanceId)
            .put(
                "surface_id",
                "$instanceId.surface-instance.${ConcurrentStereoHubContract.SURFACE_ID}",
            )
            .put("provider_id", ConcurrentStereoHubContract.PROVIDER_ID)
            .put("command_id", command)
            .put("typed_params_sha256", details.getString("typed_params_sha256"))
            .put("typed_params_schema_id", details.getString("typed_params_schema_id"))
            .put("typed_params_schema_sha256", details.getString("typed_params_schema_sha256"))
            .put("lease_id", "lease-1")
            .put("session_id", "session-1")
            .put("transport_epoch", 4L)
    return JSONObject()
        .put("\$schema", "rusty.manifold.connection_hub.receipt.v3")
        .put("applied", true)
        .put("operation", "authorize_surface_command")
        .put("request_id", requestId)
        .put("command_authorization", authorization)
        .put(
            "audit_event",
            JSONObject()
                .put("authority_epoch", 7L)
                .put(
                    "request",
                    JSONObject()
                        .put("request_id", requestId)
                        .put(
                            "operation",
                            JSONObject()
                                .put("type", "authorize_surface_command")
                                .put("details", details),
                        ),
                ),
        )
  }
