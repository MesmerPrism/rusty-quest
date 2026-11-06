package io.github.mesmerprism.rustyquest.spatial_camera_panel
import android.os.Bundle
import java.util.concurrent.TimeUnit
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexOriginals
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexRuntimeService

class GuardedProviderHarness {
  // ACTUAL_PROVIDER_METHOD
  // ACTUAL_PROVIDER_HEADER
  fun invoke(context:android.content.Context,method:String,fields:Bundle):Bundle =
      guardedMode(context,EmbeddedDuplexOperatorContract.parseCall("guarded_"+method,"a".repeat(32),fields),fields,method)
}

fun main() {
  val context=android.content.Context();val provider=GuardedProviderHarness()
  fun fields()=Bundle().apply {
    putString("run_id","b".repeat(32));putLong("guard_app_generation",69L)
    putString("guard_process_epoch","app-process.69.00000000-0000-0000-0000-000000000001")
    putString("guard_apk_sha256","c".repeat(64));putLong("guard_native_generation",25L)
    putString("guard_app_record_sha256","d".repeat(64));putString("guard_runtime_config_sha256","e".repeat(64))
  }
  val policy=fields().apply { putString("original_id","f".repeat(32));for(key in listOf("center","middle","outer","geometry","brightness","strength"))putInt(key,0) }
  val mask=fields().apply { putString("original_id","2".repeat(32));putInt("version",1);putBoolean("enabled",true)
    putFloat("threshold",0.25f);putFloat("softness",0.125f);putFloat("amount",0.75f);putBoolean("invert",false);putBoolean("diagnostic",true) }
  val ack=fields().apply {putString("original_id","f".repeat(32));for(key in listOf("request_sha256","outcome_sha256","consumer_receipt_sha256"))putString(key,"1".repeat(64))}
  try {
    for((method,request) in mapOf("policy_update" to policy,"blend_update" to mask,"blend_read" to fields(),"operation_ack" to ack)) {
      if(method=="operation_ack") {
        val original=org.json.JSONObject(EmbeddedDuplexOriginals.forApplication(context).query("f".repeat(32),"a".repeat(32)).getString("observation_json"))
        request.putString("request_sha256",original.getString("request_sha256"));request.putString("outcome_sha256",original.getString("outcome_sha256"))
      }
      val actual=org.json.JSONObject(provider.invoke(context,method,request).getString("guarded_json"))
      check(actual.getString("method")==method)
      check(actual.getJSONObject("response").getString("run_id")=="b".repeat(32))
      if(method.endsWith("update")) {
        check(actual.getJSONObject("response").getString("status")=="observed") { "Guarded update closed on registration rather than persisted completion" }
        val row=org.json.JSONObject(actual.getJSONObject("response").getString("observation_json"))
        check(row.getString("state")=="succeeded"&&row.getString("retention")=="owned")
        val disk=org.json.JSONObject(java.io.File(context.root,"operator-originals-v1/"+request.getString("original_id")+".json").readText())
        check(disk.toString()==row.toString())
        // Independently derived canonical JSON digests: guard fields and ID
        // are absent; the selected Float32 mask values use their exact bits.
        check(row.getString("request_sha256")==if(method=="policy_update")
          "500d718a1fa219b824ce70b9b78cdc778aafeb608b6e0b067004fd706505d6b3"
          else "bdd4465f285ac04560a1bf5e13f7f55fd38eacba5cd115faebb30d909cc57544")
      }
    }
    check(EmbeddedDuplexRuntimeService.effects==2&&EmbeddedDuplexRuntimeService.acks==1&&EmbeddedDuplexRuntimeService.reads==1)
    check(EmbeddedDuplexOriginals.forApplication(context).query("f".repeat(32),"a".repeat(32)).getString("status")=="compacted")
    // This uses the unchanged actual provider get(30, SECONDS), not a shortened
    // fixture deadline. Caller timeout never cancels or replaces the Original.
    val delayed=android.content.Context();val held=Bundle(policy).apply {putString("original_id","3".repeat(32))}
    val beforeTimeout=EmbeddedDuplexRuntimeService.effects
    EmbeddedDuplexRuntimeService.defer=true
    val timeoutStarted=System.nanoTime()
    try { provider.invoke(delayed,"policy_update",held);error("pending work returned success") }
    catch(expected:java.util.concurrent.TimeoutException) {}
    println("Observed unchanged provider timeout after "+((System.nanoTime()-timeoutStarted)/1_000_000)+" ms; Original still pending")
    check(!EmbeddedDuplexRuntimeService.pending.isDone&&!EmbeddedDuplexRuntimeService.pending.isCancelled)
    check(EmbeddedDuplexOriginals.forApplication(delayed).query("3".repeat(32),"a".repeat(32)).getString("status")=="pending")
    EmbeddedDuplexRuntimeService.defer=false;EmbeddedDuplexRuntimeService.release.run();EmbeddedDuplexRuntimeService.flushForTest()
    check(EmbeddedDuplexOriginals.forApplication(delayed).query("3".repeat(32),"a".repeat(32)).getString("status")=="observed")
    check(EmbeddedDuplexRuntimeService.effects==beforeTimeout+1)

    val changed=android.content.Context();val beforeDrift=EmbeddedDuplexRuntimeService.effects
    EmbeddedDuplexRuntimeService.drift=true
    try { provider.invoke(changed,"policy_update",held);error("actor drift returned success") }
    catch(expected:java.util.concurrent.ExecutionException) {}
    EmbeddedDuplexRuntimeService.resetApkForTest()
    check(EmbeddedDuplexOriginals.forApplication(changed).query("3".repeat(32),"a".repeat(32)).getString("status")=="observed")
    check(EmbeddedDuplexRuntimeService.effects==beforeDrift+1)

    val broken=android.content.Context();val beforeFailure=EmbeddedDuplexRuntimeService.effects
    EmbeddedDuplexRuntimeService.failPublication=true
    try { provider.invoke(broken,"policy_update",held);error("failed publication returned success") }
    catch(expected:java.util.concurrent.ExecutionException) {}
    EmbeddedDuplexRuntimeService.failPublication=false
    val retained=java.io.File(broken.root,"operator-originals-v1/"+"3".repeat(32)+".json")
    check(org.json.JSONObject(retained.readText()).getString("state")=="pending")
    check(java.io.File(retained.path+".tmp").isFile)
    check(EmbeddedDuplexRuntimeService.effects==beforeFailure+1)
    println("PASS actual connected provider + Original store: persisted policy/mask outcomes, canonical requests, separate ACK/query, actual 30s timeout/late completion, post-actor drift, publication failure, no duplicate effects; Android/Native leaves modeled")
  } finally { EmbeddedDuplexRuntimeService.closeForTest() }
}
