package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpatialCameraDevelopmentProfileAddonTest {
  private fun profile(): JSONObject {
    val fixture = SpatialCameraControlProfileTest::class.java.getDeclaredMethod("validProfile")
    fixture.isAccessible = true
    return fixture.invoke(SpatialCameraControlProfileTest()) as JSONObject
  }
  private fun parse(value: JSONObject) = SpatialCameraControlProfileContract.parse(value.toString().toByteArray())
  private fun directive(value: Any, getter: String) = value.javaClass.getMethod(getter).invoke(value) as SpatialDevelopmentModuleDirective?

  @Test fun observationActivateAndStatusUseTypedClosedDirective() {
    val activated = parse(profile().put("development_observation_module", JSONObject()
        .put("mode", "activate").put("generation", 7L).put("sha256", "a".repeat(64))))
    assertEquals(SpatialDevelopmentModuleDirective("activate", 7L, "a".repeat(64)),
        directive(activated, "getDevelopmentObservationModule"))
    val status = parse(profile().put("development_observation_module", JSONObject().put("mode", "status")))
    assertEquals(SpatialDevelopmentModuleDirective("status"), directive(status, "getDevelopmentObservationModule"))
  }

  @Test fun scissorPolicyIsRequiredOnlyForFreshActivation() {
    val activated = parse(profile().put("development_scissor_module", JSONObject()
        .put("mode", "activate").put("generation", 8L).put("sha256", "b".repeat(64)).put("policy", "inset_one_pixel")))
    assertEquals(SpatialDevelopmentModuleDirective("activate", 8L, "b".repeat(64), "inset_one_pixel"),
        directive(activated, "getDevelopmentScissorModule"))
    val retained = parse(profile().put("development_scissor_module", JSONObject().put("mode", "select_retained").put("generation", 7L)))
    assertEquals(SpatialDevelopmentModuleDirective("select_retained", 7L), directive(retained, "getDevelopmentScissorModule"))
  }

  @Test fun omissionIsNotAnImplicitDisable() {
    val parsed = parse(profile())
    assertNull(directive(parsed, "getDevelopmentObservationModule"))
    assertNull(directive(parsed, "getDevelopmentScissorModule"))
  }

  @Test fun pathsPolicyInjectionUnknownModesAndLooseNumbersAreRejected() {
    val invalid = listOf(
        JSONObject().put("mode", "activate").put("generation", 0).put("sha256", "a".repeat(64)),
        JSONObject().put("mode", "activate").put("generation", 1.5).put("sha256", "a".repeat(64)),
        JSONObject().put("mode", "activate").put("generation", 1).put("sha256", "A".repeat(64)),
        JSONObject().put("mode", "activate").put("generation", 1).put("sha256", "a".repeat(64)).put("path", "arbitrary.jar"),
        JSONObject().put("mode", "status").put("generation", 1),
        JSONObject().put("mode", "static").put("policy", "product"),
        JSONObject().put("mode", "unknown"),
    )
    for (value in invalid) assertThrows(IllegalArgumentException::class.java) {
      parse(profile().put("development_observation_module", value))
    }
    assertThrows(IllegalArgumentException::class.java) {
      parse(profile().put("development_scissor_module", JSONObject().put("mode", "activate")
          .put("generation", 1).put("sha256", "a".repeat(64)).put("policy", "arbitrary")))
    }
  }
}
