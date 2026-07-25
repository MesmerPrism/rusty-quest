plugins {
  id("com.android.application")
}

fun buildConfigString(value: String): String =
  "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val updateManifestUrl =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_MANIFEST_URL")
    .orElse("https://mesmerprism.com/package-updates/rusty-kiosk/alpha/envelope.json")
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
val expectedPackageName =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_PACKAGE_NAME")
    .orElse("io.github.mesmerprism.rustykiosk")
    .get()
val expectedRolloutRing =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_ROLLOUT_RING")
    .orElse("alpha")
    .get()
val expectedSignerSha256 =
  providers.environmentVariable("RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_SIGNER_SHA256")
    .orElse("unconfigured")
    .get()

require(updateManifestUrl.startsWith("https://")) {
  "RUSTY_QUEST_PACKAGE_UPDATER_MANIFEST_URL must be an HTTPS URL"
}
require(expectedHttpsOrigin.matches(Regex("https://[a-z0-9.-]+(?::[1-9][0-9]{0,4})?"))) {
  "RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_HTTPS_ORIGIN must be a canonical HTTPS origin"
}
require(expectedPackageName.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+"))) {
  "RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_PACKAGE_NAME is invalid"
}
require(expectedRolloutRing.matches(Regex("[A-Za-z0-9._-]{1,32}"))) {
  "RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_ROLLOUT_RING is invalid"
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

android {
  namespace = "io.github.mesmerprism.rustyquest.packageupdater"
  compileSdk = 34

  defaultConfig {
    applicationId = "io.github.mesmerprism.rustyquest.packageupdater"
    minSdk = 34
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"

    buildConfigField("String", "UPDATE_MANIFEST_URL", buildConfigString(updateManifestUrl))
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
      "9007199254740991L",
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

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    abortOnError = true
    checkReleaseBuilds = true
  }
}

dependencies {
}
