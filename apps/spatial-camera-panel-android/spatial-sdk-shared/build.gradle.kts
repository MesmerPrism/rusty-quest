plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.jetbrains.kotlin.android)
}

android {
  namespace = "io.github.mesmerprism.rustyquest.spatial_sdk_shared"
  compileSdk = 34

  defaultConfig { minSdk = 34 }

  buildFeatures { buildConfig = false }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  api(libs.meta.spatial.sdk.base)
  testImplementation(kotlin("test"))
}
