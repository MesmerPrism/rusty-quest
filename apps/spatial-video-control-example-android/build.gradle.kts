plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.jetbrains.kotlin.android) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.meta.spatial.plugin) apply false
}

providers.environmentVariable("RUSTY_QUEST_SPATIAL_VIDEO_CONTROL_BUILD_ROOT").orNull
  ?.takeIf { it.isNotBlank() }
  ?.let { layout.buildDirectory.set(file(it)) }
