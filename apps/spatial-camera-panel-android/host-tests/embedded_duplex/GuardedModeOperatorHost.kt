package io.github.mesmerprism.rustyquest.spatial_camera_panel

fun main() {
  val challenge="a".repeat(32);val run="b".repeat(32)
  val guards=mapOf("guard_app_generation" to 69L,"guard_process_epoch" to "app-process.69.00000000-0000-0000-0000-000000000001",
      "guard_apk_sha256" to "c".repeat(64),"guard_native_generation" to 25L,
      "guard_app_record_sha256" to "d".repeat(64),"guard_runtime_config_sha256" to "e".repeat(64))
  val policy=mapOf("center" to 0,"middle" to 0,"outer" to 0,"geometry" to 0,"brightness" to 2,"strength" to 2)
  val mask=mapOf("version" to 1,"enabled" to true,"threshold" to 0.25f,"softness" to 0.125f,
      "amount" to 0.75f,"invert" to false,"diagnostic" to true)
  val original=mapOf("original_id" to "f".repeat(32))
  val ack=original+mapOf("request_sha256" to "1".repeat(64),"outcome_sha256" to "2".repeat(64),"consumer_receipt_sha256" to "3".repeat(64))
  for((method,body) in mapOf("policy_update" to policy+original,"blend_update" to mask+original,"blend_read" to emptyMap(),"operation_ack" to ack)) {
    val fields=body+guards+("run_id" to run)
    val parsed=EmbeddedDuplexOperatorContract.parseFields("guarded_"+method,challenge,fields)
    check(parsed.runId==run && parsed.route==EmbeddedDuplexOperatorContract.parseFields(method,challenge,body+("run_id" to run)).route)
    fun refuses(next:Map<String,Any?>) { check(runCatching { EmbeddedDuplexOperatorContract.parseFields("guarded_"+method,challenge,next) }.isFailure) }
    guards.keys.forEach { refuses(fields-it);refuses(fields+(it to true)) }
    refuses(fields-"run_id");refuses(fields+("program" to "x"))
    if(method.endsWith("update"))refuses(fields-"original_id")
  }
  for(method in listOf("start","peer_stop","whole_app_close","operation_query","run_status","recover_cold_pending"))
    check(runCatching { EmbeddedDuplexOperatorContract.parseFields("guarded_"+method,challenge,guards+("run_id" to run)) }.isFailure)
  check(EmbeddedDuplexOperatorContract.parseFields("blend_read",challenge,mapOf("run_id" to run)).runId==run)
  println("PASS actual additive parser: four closed typed methods, required guard types, Original ownership, legacy unchanged")
}
