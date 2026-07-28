pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "RustyQuestSpatialApps"

include(":app")
include(":spatial-sdk-shared")
include(":strobe-app")

project(":strobe-app").projectDir = file("../spatial-vr-strobe-android/app")
