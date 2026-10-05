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
include(":media-stream-android")

project(":strobe-app").projectDir = file("../spatial-vr-strobe-android/app")
project(":media-stream-android").projectDir =
    file("../../crates/rusty-quest-media-stream-android/android/library")
