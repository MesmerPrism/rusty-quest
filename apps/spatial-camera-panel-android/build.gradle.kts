plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.jetbrains.kotlin.android) apply false
}

providers.environmentVariable("RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR").orNull
  ?.takeIf { it.isNotBlank() }
  ?.let { layout.buildDirectory.set(file(it)) }

val isolatedBuildRoot = providers.environmentVariable("RUSTY_QUEST_SPATIAL_BUILD_ROOT").orNull
subprojects {
  isolatedBuildRoot?.takeIf { it.isNotBlank() }?.let { root ->
    layout.buildDirectory.set(file("$root/${project.name}"))
  }
}
