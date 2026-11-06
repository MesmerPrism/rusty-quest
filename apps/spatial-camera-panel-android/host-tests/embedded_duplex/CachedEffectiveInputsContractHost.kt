package io.github.mesmerprism.rustyquest.spatial_camera_panel

fun main() {
  val fields = mapOf<String, Any?>("run_id" to "b".repeat(32), "expected_pid" to 42,
      "expected_uid" to 10001, "expected_start_ticks" to "2345",
      "expected_boot_id" to "11111111-2222-3333-4444-555555555555")
  val request = EmbeddedDuplexOperatorContract.parseFields("cached_effective_inputs", "a".repeat(32), fields)
  check(request.route.name == "CACHED_EFFECTIVE_INPUTS")
  check(request.runId == fields["run_id"])
  for (bad in listOf(fields - "run_id", fields + ("original_id" to "c".repeat(32)),
      fields + ("capture" to true), fields + ("expected_pid" to 42L),
      fields + ("expected_start_ticks" to "0"), fields + ("expected_boot_id" to "bad"))) {
    var rejected = false
    try { EmbeddedDuplexOperatorContract.parseFields("cached_effective_inputs", "a".repeat(32), bad) }
    catch (_: IllegalArgumentException) { rejected = true }
    check(rejected)
  }
  println("PASS cached-input parser: explicit run/process binding; closed fields; no Original/capture option")
}
