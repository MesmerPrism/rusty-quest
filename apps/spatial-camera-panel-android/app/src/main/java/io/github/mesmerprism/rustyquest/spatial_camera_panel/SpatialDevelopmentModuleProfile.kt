package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialDevelopmentModuleProfile(
    val profileId: String,
    val revision: Long,
    val observation: SpatialDevelopmentModuleDirective?,
    val scissor: SpatialDevelopmentModuleDirective?,
)

/** Separate ingress for optional consumers; deliberately has no visual or endpoint fields. */
internal object SpatialDevelopmentModuleProfileContract {
  const val SCHEMA = "rusty.quest.spatial_camera_panel.development_modules.v1"
  const val ACTIVE_FILE = "active.modules.json"
  const val RECEIPT_FILE = "last-modules-apply-receipt.json"
  const val RECEIPT_SCHEMA = "rusty.quest.spatial_camera_panel.development_modules_apply_receipt.v1"

  fun parse(bytes: ByteArray): SpatialDevelopmentModuleProfile {
    require(bytes.isNotEmpty() && bytes.size <= SpatialCameraControlProfileContract.MAX_PROFILE_BYTES) {
      "module_profile_bounds"
    }
    val root = StrictJsonByteIngress.parseObject(bytes)
    root.requireOnlyKeys("schema", "profile_id", "revision", "development_observation_module", "development_scissor_module")
    require(root.requireString("schema") == SCHEMA) { "module_profile_schema" }
    val id = root.requireString("profile_id")
    require(id.matches(Regex("[a-z0-9][a-z0-9._-]{1,63}"))) { "module_profile_id" }
    val rawRevision = root.get("revision")
    require(rawRevision is Int || rawRevision is Long) { "module_profile_revision_integer" }
    val revision = root.requireLong("revision", 0L, Long.MAX_VALUE)
    fun directive(key: String, scissor: Boolean) = if (root.has(key)) {
      SpatialDevelopmentModuleDirectiveContract.parse(root.requireObject(key), scissor)
    } else null
    val observation = directive("development_observation_module", false)
    val scissor = directive("development_scissor_module", true)
    require(observation != null || scissor != null) { "module_profile_empty" }
    return SpatialDevelopmentModuleProfile(id, revision, observation, scissor)
  }
}
