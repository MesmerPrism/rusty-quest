import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

plugins { id("com.android.application") }

layout.buildDirectory.set(rootProject.file("../../target/apk-i/development-feedback/warm/app-build"))
val debugKeystore = providers.environmentVariable("RM_FEEDBACK_DEBUG_KEYSTORE").orNull
    ?: throw GradleException("Explicit RM_FEEDBACK_DEBUG_KEYSTORE input is required; no key is generated")
require(file(debugKeystore).isFile) { "Explicit debug keystore is absent" }
val selectedFeatureLock = providers.environmentVariable("RM_FEEDBACK_FEATURE_LOCK").orNull
    ?: throw GradleException("Explicit RM_FEEDBACK_FEATURE_LOCK input is required")
val selectedRuntimeProfile = providers.environmentVariable("RM_FEEDBACK_RUNTIME_PROFILE").orNull
    ?: throw GradleException("Explicit RM_FEEDBACK_RUNTIME_PROFILE input is required")
val selectedInputRoot = rootProject.projectDir.parentFile.parentFile.resolve("local-artifacts/development-feedback/selected")
require(file(selectedFeatureLock).canonicalFile == selectedInputRoot.resolve("feature.lock.json").canonicalFile &&
    file(selectedRuntimeProfile).canonicalFile == selectedInputRoot.resolve("runtime-profile.json").canonicalFile) {
    "Activation bindings must select the exact files hashed by the reviewed build profile"
}
val activationAssets = rootProject.file("../../target/apk-i/development-feedback/warm/activation-assets")
val prepareFeedbackActivation = tasks.register("prepareFeedbackActivation") {
    inputs.files(file(selectedFeatureLock), file(selectedRuntimeProfile))
    outputs.dir(activationAssets)
    doLast {
        val selected = listOf(file(selectedFeatureLock) to "feedback-feature-lock.json", file(selectedRuntimeProfile) to "feedback-runtime-profile.json")
        Files.createDirectories(activationAssets.toPath())
        require(!Files.isSymbolicLink(activationAssets.toPath())) { "Generated activation directory may not be aliased" }
        for ((input, name) in selected) {
            require(Files.isRegularFile(input.toPath()) && !Files.isSymbolicLink(input.toPath()) && input.length() <= 1048576) { "Selected activation input is absent, aliased or oversized" }
            Files.copy(input.toPath(), activationAssets.toPath().resolve(name), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

android {
    namespace = "io.github.mesmerprism.rustyquest.development_feedback"
    compileSdk = 34
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "io.github.mesmerprism.rustyquest.development_feedback"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-development"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file(debugKeystore)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { isDebuggable = true; signingConfig = signingConfigs.getByName("debug") }
    }
    sourceSets {
        getByName("debug") {
            java.srcDirs("../../../android/development-feedback/src/main/java", "../../../android/development-feedback/src/android/java")
            assets.srcDir(activationAssets)
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
// This project is a debug consumer only. It cannot produce a release carrying its controls.
androidComponents { beforeVariants(selector().withBuildType("release")) { variantBuilder -> variantBuilder.enable = false } }
tasks.named("preBuild") { dependsOn(prepareFeedbackActivation) }

tasks.register("retainDebugApk") {
    group = "build"
    description = "Retain the existing AGP-signed debug APK at the canonical runner's create-new address"
    dependsOn("assembleDebug")
    doLast {
        val text = providers.environmentVariable("MORPHOSPACE_CONTENT_ADDRESSED_ARTIFACT").orNull
            ?: throw GradleException("Use the reviewed WEF build profile to derive the retained artifact address")
        val destination = Paths.get(text)
        val repository = rootProject.projectDir.parentFile.parentFile.toPath().toAbsolutePath().normalize()
        val parent = repository.resolve("local-artifacts/development-feedback/apks")
        require(destination.isAbsolute && destination == destination.normalize() && destination.parent == parent) {
            "Retained APK must be a direct child of the app's canonical artifact namespace"
        }
        require(destination.fileName.toString().matches(Regex("[a-f0-9]{64}\\.apk"))) { "Invalid retained APK filename" }
        var cursor = repository
        val realRepository = repository.toRealPath()
        for (part in repository.relativize(parent)) {
            cursor = cursor.resolve(part)
            require(!Files.isSymbolicLink(cursor) && (!Files.exists(cursor) || cursor.toRealPath() == realRepository.resolve(repository.relativize(cursor)))) {
                "Retained APK ancestor may not be a symbolic link or directory alias"
            }
        }
        Files.createDirectories(parent)
        val source = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile.toPath()
        require(Files.isRegularFile(source) && !Files.isSymbolicLink(source)) { "AGP signed debug APK is absent" }
        // Files.copy without REPLACE_EXISTING has create-new semantics. No assembly or signing here.
        Files.copy(source, destination)
        logger.lifecycle("Retained AGP debug APK")
    }
}
