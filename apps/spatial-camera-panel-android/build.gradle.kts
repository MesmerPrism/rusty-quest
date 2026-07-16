plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.jetbrains.kotlin.android) apply false
}

val isolatedBuildRoot = providers.environmentVariable("RUSTY_QUEST_SPATIAL_BUILD_ROOT").orNull
subprojects {
  isolatedBuildRoot?.takeIf { it.isNotBlank() }?.let { root ->
    layout.buildDirectory.set(file("$root/${project.name}"))
  }
}
