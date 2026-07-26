plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.meta.spatial.plugin)
  alias(libs.plugins.compose.compiler)
}

val spatialApplicationId =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_APP_ID")
    .orElse("io.github.mesmerprism.rustyquest.spatial_camera_panel")

val spatialProductId = providers.provider { "spatial-camera-panel" }

val spatialAppLabel =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_APP_LABEL")
    .orElse("Rusty Quest Spatial Camera Panel")

val spatialParticleLayerCarrierDefault =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_PARTICLE_LAYER_CARRIER_DEFAULT")
    .orElse("manual-panel-scene-object-custom-mesh")

val spatialStartInParticleViewDefault =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_START_IN_PARTICLE_VIEW_DEFAULT")
    .orElse("false")

val spatialLockedFinalPresentation =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_LOCKED_FINAL_PRESENTATION")
    .map { raw ->
      when (raw.trim().lowercase()) {
        "1", "true", "yes", "on" -> "true"
        "0", "false", "no", "off" -> "false"
        else -> error("RUSTY_QUEST_SPATIAL_LOCKED_FINAL_PRESENTATION must be a boolean")
      }
    }
    .orElse("false")

val spatialDistortionSpeedScale =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_DISTORTION_SPEED_SCALE")
    .map { raw ->
      val value = raw.trim().toFloatOrNull()
        ?: error("RUSTY_QUEST_SPATIAL_DISTORTION_SPEED_SCALE must be numeric")
      require(value in 0.0f..4.0f) {
        "RUSTY_QUEST_SPATIAL_DISTORTION_SPEED_SCALE must be between 0.0 and 4.0"
      }
      value.toString()
    }
    .orElse("1.0")

val spatialHandAlignmentEnabledDefault =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_HAND_ALIGNMENT_ENABLED_DEFAULT")
    .orElse("false")

val spatialHandAlignmentViewerMarkersEnabledDefault =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_HAND_ALIGNMENT_VIEWER_MARKERS_ENABLED_DEFAULT")
    .orElse("false")

val spatialHandAlignmentMappingProfileDefault =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_HAND_ALIGNMENT_MAPPING_PROFILE_DEFAULT")
    .orElse("mirror-x-origin-registration")

val spatialHandBillboardFlockEnabledDefault =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_HAND_BILLBOARD_FLOCK_ENABLED_DEFAULT")
    .orElse("false")

val spatialHandBillboardSourceDefault =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_HAND_BILLBOARD_SOURCE_DEFAULT")
    .orElse("spatial-sdk-anchor-flock")

val spatialHandMeshRigAssetDir =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_HAND_MESH_RIG_ASSET_DIR")

val spatialHandMeshRigPackaged =
  spatialHandMeshRigAssetDir.map { it.isNotBlank().toString() }.orElse("false")

val spatialSigningKeystore =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE")

val offlineMediaKeyHex =
  providers.environmentVariable("RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX")
    .map { raw ->
      val value = raw.trim()
      require(value.matches(Regex("^[a-fA-F0-9]{64}$"))) {
        "RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX must be exactly 64 hexadecimal characters"
      }
      value.lowercase()
    }
    .orElse("")

val offlineMediaPackAssetDir =
  providers.environmentVariable("RUSTY_QUEST_OFFLINE_MEDIA_PACK_ASSET_DIR")

val offlineMediaPackagedAssets =
  offlineMediaPackAssetDir.map { it.isNotBlank().toString() }.orElse("false")

val spatialNdkVersion =
  providers.environmentVariable("RUSTY_QUEST_ANDROID_NDK_VERSION")
    .orElse("27.2.12479018")

val spatialClientId = providers.provider { "client.quest.spatial-camera-panel" }
val spatialFeatureLockId = providers.provider { "lock.broker-client.spatial-camera-panel.v1" }
val spatialMarkerNamespace = providers.provider { "RUSTY_QUEST_SPATIAL_BROKER_CLIENT" }
val spatialPropertyNamespace = providers.provider { "debug.rustyquest.spatial_camera_panel" }

providers.environmentVariable("RUSTY_QUEST_SPATIAL_APP_BUILD_DIR").orNull
  ?.takeIf { it.isNotBlank() }
  ?.let { layout.buildDirectory.set(file(it)) }

fun buildConfigString(value: String): String =
  "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
  namespace = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
  compileSdk = 34
  ndkVersion = spatialNdkVersion.get()

  defaultConfig {
    applicationId = spatialApplicationId.get()
    minSdk = 34
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
    manifestPlaceholders["spatialAppLabel"] = spatialAppLabel.get()
    manifestPlaceholders["spatialClientId"] = spatialClientId.get()
    manifestPlaceholders["spatialFeatureLockId"] = spatialFeatureLockId.get()
    manifestPlaceholders["spatialMarkerNamespace"] = spatialMarkerNamespace.get()
    buildConfigField(
      "String",
      "SPATIAL_PRODUCT_ID",
      buildConfigString(spatialProductId.get()),
    )
    buildConfigField(
      "String",
      "SPATIAL_PROPERTY_NAMESPACE",
      buildConfigString(spatialPropertyNamespace.get()),
    )
    buildConfigField(
      "String",
      "PARTICLE_LAYER_CARRIER_DEFAULT",
      buildConfigString(spatialParticleLayerCarrierDefault.get()),
    )
    buildConfigField(
      "String",
      "START_IN_PARTICLE_VIEW_DEFAULT",
      buildConfigString(spatialStartInParticleViewDefault.get()),
    )
    buildConfigField(
      "boolean",
      "LOCKED_FINAL_PRESENTATION",
      spatialLockedFinalPresentation.get(),
    )
    buildConfigField(
      "float",
      "DISTORTION_SPEED_SCALE",
      "${spatialDistortionSpeedScale.get()}f",
    )
    buildConfigField(
      "boolean",
      "HAND_ALIGNMENT_ENABLED_DEFAULT",
      spatialHandAlignmentEnabledDefault.get(),
    )
    buildConfigField(
      "boolean",
      "HAND_ALIGNMENT_VIEWER_MARKERS_ENABLED_DEFAULT",
      spatialHandAlignmentViewerMarkersEnabledDefault.get(),
    )
    buildConfigField(
      "String",
      "HAND_ALIGNMENT_MAPPING_PROFILE_DEFAULT",
      buildConfigString(spatialHandAlignmentMappingProfileDefault.get()),
    )
    buildConfigField(
      "boolean",
      "HAND_BILLBOARD_FLOCK_ENABLED_DEFAULT",
      spatialHandBillboardFlockEnabledDefault.get(),
    )
    buildConfigField(
      "String",
      "HAND_BILLBOARD_SOURCE_DEFAULT",
      buildConfigString(spatialHandBillboardSourceDefault.get()),
    )
    buildConfigField(
      "boolean",
      "HAND_MESH_RIG_PACKAGED",
      spatialHandMeshRigPackaged.get(),
    )
    buildConfigField(
      "String",
      "OFFLINE_MEDIA_KEY_HEX",
      buildConfigString(offlineMediaKeyHex.get()),
    )
    buildConfigField(
      "boolean",
      "OFFLINE_MEDIA_PACKAGED_ASSETS",
      offlineMediaPackagedAssets.get(),
    )
  }

  spatialSigningKeystore.orNull
    ?.takeIf { it.isNotBlank() }
    ?.let { keystorePath ->
      signingConfigs.getByName("debug") {
        storeFile = file(keystorePath)
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }

  sourceSets.getByName("main").java.srcDir(
    rootProject.file("../../crates/rusty-quest-broker-client/android"),
  )

  packaging {
    resources.excludes.add("META-INF/LICENSE")
    resources.excludes.add("META-INF/LICENSE.md")
    resources.excludes.add("META-INF/LICENSE-notice.md")
  }

  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }

  buildTypes {
    getByName("release") {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }

  sourceSets {
    getByName("main") {
      jniLibs.srcDir(layout.buildDirectory.dir("generated/rustJniLibs"))
      providers.environmentVariable("RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_SRC_DIR").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { java.srcDir(it) }
      providers.environmentVariable("RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_ASSET_DIR").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { assets.srcDir(it) }
      spatialHandMeshRigAssetDir.orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { assets.srcDir(it) }
      offlineMediaPackAssetDir.orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { assetRoot ->
          val packagedMediaRoot = file(assetRoot).resolve("offline-media-packs")
          require(packagedMediaRoot.isDirectory) {
            "RUSTY_QUEST_OFFLINE_MEDIA_PACK_ASSET_DIR must point to an asset root " +
              "containing offline-media-packs/"
          }
          assets.srcDir(assetRoot)
        }
      providers.environmentVariable("RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_RES_DIR").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { res.srcDir(it) }
    }
  }

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
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.datasource)
  implementation(libs.androidx.media3.exoplayer)

  testImplementation(kotlin("test"))
}

spatial {
  allowUsageDataCollection.set(false)
  shaders {
    sources.add(project.layout.projectDirectory.dir("src/shaders"))
  }
}
