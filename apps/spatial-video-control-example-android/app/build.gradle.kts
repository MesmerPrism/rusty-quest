import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.meta.spatial.plugin)
}

val generatedMediaRoot = layout.buildDirectory.dir("generated/synthetic-media/res")
val generatedNativeRoot = layout.buildDirectory.dir("generated/native-jniLibs")
val mediaSourceRoot = layout.projectDirectory.dir("src/main/media-source")
val syntheticMediaNames =
    listOf(
        "synthetic_grid_1s",
        "synthetic_blue_2s",
        "synthetic_180_mono_1s",
        "synthetic_180_sbs_lr_1s",
        "synthetic_180_top_bottom_1s",
        "synthetic_360_mono_1s",
        "synthetic_360_sbs_lr_1s",
        "synthetic_360_top_bottom_1s",
    )

val decodeSyntheticMedia by tasks.registering {
  group = "build setup"
  description = "Decode the deterministic flat/180/360 CC0 synthetic MP4 source blobs."
  inputs.files(syntheticMediaNames.map { mediaSourceRoot.file("$it.mp4.base64") })
  outputs.files(
      syntheticMediaNames.map { name ->
        generatedMediaRoot.map { it.file("raw/$name.mp4") }
      }
  )
  doLast {
    val raw = generatedMediaRoot.get().dir("raw").asFile
    raw.mkdirs()
    syntheticMediaNames.forEach { name ->
      val encoded = mediaSourceRoot.file("$name.mp4.base64").asFile.readText(Charsets.US_ASCII)
      raw.resolve("$name.mp4").writeBytes(Base64.getMimeDecoder().decode(encoded))
    }
  }
}

val buildNativeLocalControl by tasks.registering(Exec::class) {
  group = "build setup"
  description = "Build the exact pinned Rust/Manifold JNI adapter for Quest arm64."
  inputs.files(fileTree("../native") { exclude("target/**") })
  inputs.files(fileTree("../tools") { include("Build-NativeLocalControl.ps1", "build-native-local-control.sh") })
  outputs.file(
      generatedNativeRoot.map {
        it.file("arm64-v8a/librusty_quest_spatial_video_local_control.so")
      }
  )
  outputs.upToDateWhen { false }
  doFirst {
    check(!System.getenv("RUSTY_MANIFOLD_SOURCE_ROOT").isNullOrBlank()) {
      "RUSTY_MANIFOLD_SOURCE_ROOT must identify the clean source pinned by native/manifold-source.lock.json"
    }
    val output = generatedNativeRoot.get().asFile.absolutePath
    if (System.getProperty("os.name").lowercase().contains("windows")) {
      commandLine(
          "pwsh",
          "-NoProfile",
          "-File",
          file("../tools/Build-NativeLocalControl.ps1").absolutePath,
          "-Profile",
          "release",
          "-OutputRoot",
          output,
      )
    } else {
      commandLine(
          "bash",
          file("../tools/build-native-local-control.sh").absolutePath,
          output,
      )
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
      jniLibs.srcDir(generatedNativeRoot)
      res.srcDir(generatedMediaRoot)
    }
  }
}

tasks.named("preBuild").configure {
  dependsOn(decodeSyntheticMedia)
  dependsOn(buildNativeLocalControl)
}

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
