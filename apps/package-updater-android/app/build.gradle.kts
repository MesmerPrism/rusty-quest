import org.gradle.api.tasks.Exec
import java.net.URI

plugins {
  id("com.android.application")
}

fun buildConfigString(value: String): String =
  "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val updateManifestUrl =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_MANIFEST_URL")
    .orElse("https://mesmerprism.com/rusty-quest/package-updates/rusty-kiosk/labs/current.json")
    .get()
val trustedKeyId =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_TRUSTED_KEY_ID")
    .orElse("unconfigured")
    .get()
val trustedPublicKeyBase64 =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_TRUSTED_PUBLIC_KEY_BASE64")
    .orElse("")
    .get()
val expectedHttpsOrigin =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_HTTPS_ORIGIN")
    .orElse("https://mesmerprism.com")
    .get()
val expectedSiteBasePath =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_SITE_BASE_PATH")
    .orElse("rusty-quest")
    .get()
val expectedPackageName =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_PACKAGE_NAME")
    .orElse("io.github.mesmerprism.rustykiosk.labs")
    .get()
val expectedRolloutRing =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_ROLLOUT_RING")
    .orElse("labs")
    .get()
val expectedSignerSha256 =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_SIGNER_SHA256")
    .orElse("unconfigured")
    .get()
val packageUpdaterVersionCodeText =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_VERSION_CODE")
    .orElse("1")
    .get()
val packageUpdaterVersionCode = packageUpdaterVersionCodeText.toIntOrNull()
val packageUpdaterVersionName =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_VERSION_NAME")
    .orElse("0.1.0")
    .get()
val updateChannel = "labs"

require(expectedHttpsOrigin.matches(Regex("https://[a-z0-9.-]+"))) {
  "RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_HTTPS_ORIGIN must be a canonical HTTPS origin without an explicit port"
}
require(expectedSiteBasePath == "rusty-quest") {
  "RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_SITE_BASE_PATH must be the exact project site"
}
val parsedManifestUri = URI(updateManifestUrl)
require(
  updateManifestUrl ==
    "$expectedHttpsOrigin/$expectedSiteBasePath/package-updates/rusty-kiosk/labs/current.json" &&
    parsedManifestUri.scheme == "https" &&
    parsedManifestUri.rawUserInfo == null &&
    parsedManifestUri.rawQuery == null &&
    parsedManifestUri.rawFragment == null &&
    parsedManifestUri.rawPath ==
      "/$expectedSiteBasePath/package-updates/rusty-kiosk/labs/current.json" &&
    !updateManifestUrl.contains("%") &&
    !parsedManifestUri.rawPath.contains("..") &&
    !parsedManifestUri.rawPath.contains("//"),
) {
  "RUSTY_QUEST_PACKAGE_UPDATER_MANIFEST_URL must be the exact canonical Labs pointer"
}
require(expectedPackageName.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+"))) {
  "RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_PACKAGE_NAME is invalid"
}
require(expectedRolloutRing.matches(Regex("[A-Za-z0-9._-]{1,32}"))) {
  "RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_ROLLOUT_RING is invalid"
}
require(expectedPackageName == "io.github.mesmerprism.rustykiosk.labs") {
  "Rusty Package Updater Labs may target only the co-installable Kiosk Labs package"
}
require(expectedRolloutRing == "labs") {
  "Rusty Package Updater Labs requires the exact labs rollout ring"
}
require(packageUpdaterVersionCode != null && packageUpdaterVersionCode > 0) {
  "RUSTY_QUEST_PACKAGE_UPDATER_VERSION_CODE must be a positive Android version code"
}
require(packageUpdaterVersionName.matches(Regex("0\\.1\\.0(?:-alpha\\.[1-9][0-9]*)?"))) {
  "RUSTY_QUEST_PACKAGE_UPDATER_VERSION_NAME is outside the updater product line"
}

val releaseKeystorePath =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_KEYSTORE_PATH").orNull
val releaseKeystorePassword =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_KEY_ALIAS").orNull
val releaseKeyPassword =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(
  releaseKeystorePath,
  releaseKeystorePassword,
  releaseKeyAlias,
  releaseKeyPassword,
)
val releaseSigningConfigured =
  releaseSigningValues.all { !it.isNullOrBlank() }
require(releaseSigningValues.all { it.isNullOrBlank() } || releaseSigningConfigured) {
  "Package Updater release signing variables must be supplied together"
}
val releaseBuildRequested = gradle.startParameter.taskNames.any {
  it.substringAfterLast(':').contains("release", ignoreCase = true)
}
require(!releaseBuildRequested || releaseSigningConfigured) {
  "Package Updater release builds require all release signing variables"
}

val repoRoot = layout.projectDirectory.dir("../../..")
val nativeCrate = layout.projectDirectory.dir("../native")
val nativeTarget = layout.buildDirectory.dir("rustNativeTarget")
val nativeArtifact = nativeTarget.map {
  it.file(
    "aarch64-linux-android/release/" +
      "librusty_quest_package_updater_android.so",
  )
}
val generatedNativeDirectory =
  layout.buildDirectory.dir("generated/rustJniLibs/arm64-v8a")
val generatedNativeArtifact = generatedNativeDirectory.map {
  it.file("librusty_quest_package_updater_android.so")
}
val buildRustNativeVerifier =
  tasks.register<Exec>("buildRustNativeVerifier") {
    group = "build"
    description =
      "Builds the exact arm64 Rust Ed25519 verifier packaged in every APK variant."
    inputs.file(repoRoot.file("Cargo.toml"))
    inputs.file(repoRoot.file("Cargo.lock"))
    inputs.file(nativeCrate.file("Cargo.toml"))
    inputs.dir(nativeCrate.dir("src"))
    outputs.file(generatedNativeArtifact)

    doFirst {
      val configuredNdk =
        providers.environmentVariable("ANDROID_NDK_HOME").orNull
      val sdkRoot =
        providers.environmentVariable("ANDROID_SDK_ROOT")
          .orElse(providers.environmentVariable("ANDROID_HOME"))
          .orNull
      val ndkRoot = if (!configuredNdk.isNullOrBlank()) {
        file(configuredNdk)
      } else {
        require(!sdkRoot.isNullOrBlank()) {
          "ANDROID_NDK_HOME or an Android SDK root is required for the native verifier"
        }
        file("$sdkRoot/ndk").listFiles()
          ?.filter { it.isDirectory }
          ?.maxByOrNull { it.name }
          ?: error("No Android NDK is installed under $sdkRoot/ndk")
      }
      val linker =
        ndkRoot.resolve(
          "toolchains/llvm/prebuilt/windows-x86_64/bin/" +
            "aarch64-linux-android34-clang.cmd",
        )
      require(linker.isFile) {
        "Package Updater Android linker is missing: $linker"
      }
      environment(
        "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER",
        linker.absolutePath,
      )
      commandLine(
        "cargo",
        "build",
        "--manifest-path",
        nativeCrate.file("Cargo.toml").asFile.absolutePath,
        "--target",
        "aarch64-linux-android",
        "--release",
        "--target-dir",
        nativeTarget.get().asFile.absolutePath,
        "--locked",
      )
    }

    doLast {
      val built = nativeArtifact.get().asFile
      require(built.isFile) {
        "Package Updater native verifier output is missing: $built"
      }
      copy {
        from(built)
        into(generatedNativeDirectory)
      }
      require(generatedNativeArtifact.get().asFile.isFile) {
        "Package Updater generated JNI verifier was not copied"
      }
    }
  }

android {
  namespace = "io.github.mesmerprism.rustyquest.packageupdater"
  compileSdk = 34

  defaultConfig {
    applicationId = "io.github.mesmerprism.rustyquest.packageupdater.labs"
    minSdk = 34
    targetSdk = 34
    versionCode = packageUpdaterVersionCode
    versionName = packageUpdaterVersionName
    ndk {
      abiFilters += "arm64-v8a"
    }

    buildConfigField("String", "UPDATE_MANIFEST_URL", buildConfigString(updateManifestUrl))
    buildConfigField("String", "UPDATE_CHANNEL", buildConfigString(updateChannel))
    buildConfigField("String", "TRUSTED_KEY_ID", buildConfigString(trustedKeyId))
    buildConfigField(
      "String",
      "TRUSTED_PUBLIC_KEY_BASE64",
      buildConfigString(trustedPublicKeyBase64),
    )
    buildConfigField(
      "String",
      "EXPECTED_HTTPS_ORIGIN",
      buildConfigString(expectedHttpsOrigin),
    )
    buildConfigField(
      "String",
      "EXPECTED_SITE_BASE_PATH",
      buildConfigString(expectedSiteBasePath),
    )
    buildConfigField(
      "String",
      "EXPECTED_PACKAGE_NAME",
      buildConfigString(expectedPackageName),
    )
    buildConfigField(
      "String",
      "EXPECTED_ROLLOUT_RING",
      buildConfigString(expectedRolloutRing),
    )
    buildConfigField(
      "String",
      "EXPECTED_SIGNER_SHA256",
      buildConfigString(expectedSignerSha256),
    )
    buildConfigField("long", "MAXIMUM_APK_SIZE_BYTES", "104857600L")
    buildConfigField("long", "MINIMUM_TARGET_VERSION_CODE", "1L")
    buildConfigField(
      "long",
      "MAXIMUM_TARGET_VERSION_CODE",
      "2147483647L",
    )
    buildConfigField("long", "MAXIMUM_MANIFEST_VALIDITY_MS", "86400000L")
    buildConfigField("long", "MAXIMUM_FUTURE_ISSUE_SKEW_MS", "300000L")
    manifestPlaceholders["expectedPackageName"] = expectedPackageName
  }

  signingConfigs {
    if (releaseSigningConfigured) {
      create("release") {
        storeFile = file(requireNotNull(releaseKeystorePath))
        storePassword = releaseKeystorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    create("e2e") {
      initWith(getByName("debug"))
      applicationIdSuffix = ".e2ecli"
      versionNameSuffix = "-e2ecli"
      isDebuggable = true
      matchingFallbacks += listOf("debug")
    }
    release {
      isMinifyEnabled = false
      signingConfig = if (releaseSigningConfigured) {
        signingConfigs.getByName("release")
      } else {
        null
      }
    }
  }

  buildFeatures {
    buildConfig = true
  }

  sourceSets {
    getByName("main") {
      jniLibs.srcDir(layout.buildDirectory.dir("generated/rustJniLibs"))
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    abortOnError = true
    checkReleaseBuilds = true
  }
}

tasks.configureEach {
  if (name.matches(Regex("merge[A-Z].*JniLibFolders"))) {
    dependsOn(buildRustNativeVerifier)
  }
}

dependencies {
}
