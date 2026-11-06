package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.json.JSONObject

internal object SpatialDevelopmentModuleDirectiveContract {
  fun parse(value: JSONObject, scissor: Boolean): SpatialDevelopmentModuleDirective {
    val mode = value.requireString("mode")
    when (mode) {
      "static", "status" -> {
        value.requireOnlyKeys("mode")
        return SpatialDevelopmentModuleDirective(mode)
      }
      "activate", "select_retained" -> {
        val fields = mutableListOf("mode", "generation")
        if (mode == "activate") fields += if (scissor) listOf("sha256", "policy") else listOf("sha256")
        value.requireOnlyKeys(*fields.toTypedArray())
        val rawGeneration = value.get("generation")
        require(rawGeneration is Int || rawGeneration is Long) { "module_generation_integer_required" }
        val generation = value.requireLong("generation", 1L, Long.MAX_VALUE)
        val sha = if (mode == "activate") value.requireString("sha256").also {
          require(it.matches(Regex("[0-9a-f]{64}"))) { "module_sha256_invalid" }
        } else null
        val policy = if (scissor && mode == "activate") value.requireString("policy").also {
          require(it == "product" || it == "inset_one_pixel") { "scissor_policy_invalid" }
        } else null
        return SpatialDevelopmentModuleDirective(mode, generation, sha, policy)
      }
      else -> throw IllegalArgumentException("module_mode_unsupported")
    }
  }
}
