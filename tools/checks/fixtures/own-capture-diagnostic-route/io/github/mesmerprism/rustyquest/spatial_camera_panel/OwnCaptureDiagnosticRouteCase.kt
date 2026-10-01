package io.github.mesmerprism.rustyquest.spatial_camera_panel

/** Actual debug contract, isolated from any provider, device, or media effect. */
fun main() {
  val challenge = "0123456789abcdef0123456789abcdef"
  val request = EmbeddedDuplexOperatorContract.parseFields(
      "own_capture_diagnostic", challenge, emptyMap())
  check(request.route == EmbeddedDuplexOperatorContract.Route.OWN_CAPTURE_DIAGNOSTIC)
  check(request.challenge == challenge && request.draft == null && request.policy == null)
  try {
    EmbeddedDuplexOperatorContract.parseFields("own_capture_diagnostic", challenge,
        mapOf("operation" to "arbitrary"))
    error("accepted a caller operation")
  } catch (_: IllegalArgumentException) { }
  try {
    EmbeddedDuplexOperatorContract.parseFields("own_capture_diagnostic", "bad", emptyMap())
    error("accepted an invalid challenge")
  } catch (_: IllegalArgumentException) { }
  println("own-capture-diagnostic-route PASS")
}
