plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.meta.spatial.plugin)
  alias(libs.plugins.compose.compiler)
}

val strobeNdkVersion =
  providers.environmentVariable("RUSTY_QUEST_ANDROID_NDK_VERSION").orElse("27.2.12479018")
val strobeSigningKeystore = providers.environmentVariable("RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE")

providers.environmentVariable("RUSTY_QUEST_SPATIAL_STROBE_BUILD_DIR").orNull
  ?.takeIf { it.isNotBlank() }
  ?.let { layout.buildDirectory.set(file(it)) }

android {
  namespace = "io.github.mesmerprism.rustyquest.spatial_vr_strobe"
  compileSdk = 34
  ndkVersion = strobeNdkVersion.get()

  defaultConfig {
    applicationId = "io.github.mesmerprism.rustyquest.spatial_vr_strobe"
    minSdk = 34
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
  }

  strobeSigningKeystore.orNull
    ?.takeIf { it.isNotBlank() }
    ?.let { keystorePath ->
      signingConfigs.getByName("debug") {
        storeFile = file(keystorePath)
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }

  packaging {
    resources.excludes.add("META-INF/LICENSE")
    resources.excludes.add("META-INF/LICENSE.md")
    resources.excludes.add("META-INF/LICENSE-notice.md")
  }

  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  implementation(project(":spatial-sdk-shared"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.ui.tooling.preview)
  debugImplementation(libs.androidx.ui.tooling)

  implementation(libs.meta.spatial.sdk.base)
  implementation(libs.meta.spatial.sdk.compose)
  implementation(libs.meta.spatial.sdk.toolkit)
  implementation(libs.meta.spatial.sdk.vr)
  implementation(libs.meta.spatial.sdk.isdk)
  implementation(libs.gson)

  testImplementation(kotlin("test"))
}

spatial {
  allowUsageDataCollection.set(false)
  shaders { sources.add(project.layout.projectDirectory.dir("src/shaders")) }
}
