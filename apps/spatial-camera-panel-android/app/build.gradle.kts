plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.meta.spatial.plugin)
  alias(libs.plugins.compose.compiler)
}

val spatialApplicationId =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_APP_ID")
    .orElse("io.github.mesmerprism.rustyquest.spatial_camera_panel")

val spatialAppLabel =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_APP_LABEL")
    .orElse("Rusty Quest Spatial Camera Panel")

val spatialParticleLayerCarrierDefault =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_PARTICLE_LAYER_CARRIER_DEFAULT")
    .orElse("manual-panel-scene-object-custom-mesh")

val spatialStartInParticleViewDefault =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_START_IN_PARTICLE_VIEW_DEFAULT")
    .orElse("false")

val spatialPanelLauncherVisibleDefault =
  providers.environmentVariable("RUSTY_QUEST_SPATIAL_PANEL_LAUNCHER_VISIBLE_DEFAULT")
    .orElse("true")

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

fun buildConfigString(value: String): String =
  "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
  namespace = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
  compileSdk = 34

  defaultConfig {
    applicationId = spatialApplicationId.get()
    minSdk = 34
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
    manifestPlaceholders["spatialAppLabel"] = spatialAppLabel.get()
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
      "String",
      "PANEL_LAUNCHER_VISIBLE_DEFAULT",
      buildConfigString(spatialPanelLauncherVisibleDefault.get()),
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

  testImplementation(kotlin("test"))
}

spatial {
  allowUsageDataCollection.set(false)
}
