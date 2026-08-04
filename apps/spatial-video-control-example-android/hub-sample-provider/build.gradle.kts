plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "io.github.mesmerprism.rustyquest.connection_hub_sample"
  compileSdk = 34

  defaultConfig {
    applicationId = "io.github.mesmerprism.rustyquest.connection_hub_sample"
    minSdk = 29
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
  }

  signingConfigs {
    System.getenv("RUSTY_CONNECTION_HUB_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { path ->
      getByName("debug") {
        storeFile = file(path)
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}
