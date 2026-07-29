import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.meta.spatial.plugin)
}

val generatedMediaRoot = layout.buildDirectory.dir("generated/synthetic-media/res")
val mediaSourceRoot = layout.projectDirectory.dir("src/main/media-source")

val decodeSyntheticMedia by tasks.registering {
  group = "build setup"
  description = "Decode the two deterministic CC0 synthetic MP4 source blobs."
  inputs.files(
    mediaSourceRoot.file("synthetic_grid_1s.mp4.base64"),
    mediaSourceRoot.file("synthetic_blue_2s.mp4.base64"),
  )
  outputs.files(
    generatedMediaRoot.map { it.file("raw/synthetic_grid_1s.mp4") },
    generatedMediaRoot.map { it.file("raw/synthetic_blue_2s.mp4") },
  )
  doLast {
    val raw = generatedMediaRoot.get().dir("raw").asFile
    raw.mkdirs()
    listOf("synthetic_grid_1s", "synthetic_blue_2s").forEach { name ->
      val encoded = mediaSourceRoot.file("$name.mp4.base64").asFile.readText(Charsets.US_ASCII)
      raw.resolve("$name.mp4").writeBytes(Base64.getMimeDecoder().decode(encoded))
    }
  }
}

android {
  namespace = "io.github.mesmerprism.rustyquest.spatial_video_control"
  compileSdk = 34

  defaultConfig {
    applicationId = "io.github.mesmerprism.rustyquest.spatial_video_control_example"
    minSdk = 34
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
    buildConfigField("boolean", "TRUSTED_LOCAL_HTTP_ENABLED_DEFAULT", "false")
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }

  sourceSets {
    getByName("main") {
      java.srcDir("../host/src/main/java")
      res.srcDir(generatedMediaRoot)
    }
  }
}

tasks.named("preBuild").configure { dependsOn(decodeSyntheticMedia) }

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.material3)
  implementation(libs.meta.spatial.sdk.base)
  implementation(libs.meta.spatial.sdk.compose)
  implementation(libs.meta.spatial.sdk.toolkit)
  implementation(libs.meta.spatial.sdk.vr)
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.exoplayer)
}

spatial {
  allowUsageDataCollection.set(false)
}
