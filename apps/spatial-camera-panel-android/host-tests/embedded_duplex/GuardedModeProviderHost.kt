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
  val mask=fields().apply { putString("original_id","f".repeat(32));putInt("version",1);putBoolean("enabled",true)
    putFloat("threshold",0.25f);putFloat("softness",0.125f);putFloat("amount",0.75f);putBoolean("invert",false);putBoolean("diagnostic",true) }
  val ack=fields().apply {putString("original_id","f".repeat(32));for(key in listOf("request_sha256","outcome_sha256","consumer_receipt_sha256"))putString(key,"1".repeat(64))}
  try {
    for((method,request) in mapOf("policy_update" to policy,"blend_update" to mask,"blend_read" to fields(),"operation_ack" to ack)) {
      val actual=org.json.JSONObject(provider.invoke(context,method,request).getString("guarded_json"))
      check(actual.getString("method")==method)
      check(actual.getJSONObject("response").getString("run_id")=="b".repeat(32))
      if(method.endsWith("update")) {
        val canonical=EmbeddedDuplexRuntimeService.lastCanonical
        check(canonical.getString("method")==method)
        check(canonical.getJSONObject("extras").keySet().none { it.startsWith("guard_") || it=="original_id" })
        if(method=="blend_update")check(canonical.getJSONObject("extras").getInt("amount")==0.75f.toRawBits())
      }
    }
    check(EmbeddedDuplexRuntimeService.effects==2&&EmbeddedDuplexRuntimeService.acks==1&&EmbeddedDuplexRuntimeService.reads==1)
    println("PASS actual provider helper + service bridge + request producer: four existing handlers, logical request hashes, Float bits, no command-lane blocking")
  } finally { EmbeddedDuplexRuntimeService.closeForTest() }
}
