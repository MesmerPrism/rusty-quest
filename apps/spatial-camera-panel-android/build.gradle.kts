plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.jetbrains.kotlin.android) apply false
}

providers.environmentVariable("RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR").orNull
  ?.takeIf { it.isNotBlank() }
  ?.let { layout.buildDirectory.set(file(it)) }
