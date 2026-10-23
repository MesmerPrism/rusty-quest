import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.meta.spatial)
}
val feedbackSelection = gradle.startParameter.projectProperties["workloadDevelopmentFeedback"] ?: "false"
require(feedbackSelection in listOf("true", "false")) { "workloadDevelopmentFeedback must be explicit true or false" }
val feedbackSelected = feedbackSelection == "true"
require(!feedbackSelected || gradle.startParameter.taskNames.none { it.contains("release", ignoreCase = true) }) { "Development feedback is debug-only" }

val observerSelection = providers.gradleProperty("workloadSubmissionObserver").getOrElse("false")
require(observerSelection == "true" || observerSelection == "false") { "workloadSubmissionObserver must be true or false" }
val observerSelected = observerSelection == "true"
val graphicsReloadSelection = providers.gradleProperty("workloadSdkGraphicsHotReload").getOrElse("false")
require(graphicsReloadSelection == "true" || graphicsReloadSelection == "false") { "workloadSdkGraphicsHotReload must be true or false" }
val graphicsReloadSelected = graphicsReloadSelection == "true"
val externalReloadSelection = providers.gradleProperty("workloadExternalWorkloadHotload").getOrElse("false")
require(externalReloadSelection == "true" || externalReloadSelection == "false") { "workloadExternalWorkloadHotload must be true or false" }
val externalReloadSelected = externalReloadSelection == "true"
require(!externalReloadSelected || gradle.startParameter.taskNames.none { it.contains("release", ignoreCase = true) }) { "External workload hotload is debug-only" }
val externalReloadMarker = layout.buildDirectory.dir("generated/external-workload-hotload-assets")
val writeExternalReloadMarker = tasks.register("writeExternalWorkloadHotloadMarker") {
    inputs.property("selected", externalReloadSelected)
    outputs.dir(externalReloadMarker)
    onlyIf { externalReloadSelected }
    doLast {
        val marker=externalReloadMarker.get().file("external-workload-hotload.json").asFile
        marker.parentFile.mkdirs()
        marker.writeText("""{"schema":"rusty.quest.hotload.external_workload_build.v1","selected":true,"debugOnly":true,"slot":"external-workload-v1","maximumCodeBytes":262144}""")
    }
}
tasks.matching { it.name == "mergeDebugAssets" }.configureEach { dependsOn(writeExternalReloadMarker) }

require(!graphicsReloadSelected || gradle.startParameter.taskNames.none { it.contains("release", ignoreCase = true) }) {
    "SDK graphics hot reload is admitted only for the isolated debug candidate"
}
val observerModule = providers.gradleProperty("workloadSubmissionObserverModule")
val observerHeaders = providers.gradleProperty("workloadSubmissionObserverOpenXrInclude")
val sessionSelection = providers.gradleProperty("workloadSessionCore").getOrElse("false")
require(sessionSelection == "true" || sessionSelection == "false") { "workloadSessionCore must be true or false" }
val sessionSelected = sessionSelection == "true"
require(!feedbackSelected || (!sessionSelected && !externalReloadSelected && !observerSelected)) { "Public feedback excludes external/session/observer owners" }
val sessionJava = providers.gradleProperty("workloadSessionJava")
val sessionLibraries = providers.gradleProperty("workloadSessionLibraries")
val sessionAssets = providers.gradleProperty("workloadSessionAssets")
val sessionInclude = providers.gradleProperty("workloadSessionInclude")
val sessionBinding = providers.gradleProperty("workloadSessionBinding")
val privateApplicationId = providers.gradleProperty("workloadPrivateApplicationId")
val privateAppLabel = providers.gradleProperty("workloadPrivateAppLabel")
val selectedSessionInputs = listOf(sessionJava, sessionLibraries, sessionAssets, sessionInclude, sessionBinding, privateApplicationId, privateAppLabel)
if (sessionSelected) {
    require(gradle.startParameter.taskNames.none { it.contains("release", ignoreCase = true) }) { "SessionCore is admitted only for the isolated debug candidate" }
    require(observerSelected && selectedSessionInputs.all { it.isPresent }) { "Selected session requires observer and complete staged inputs" }
    require(file(sessionLibraries.get()).resolve("arm64-v8a/librusty_quest_sdk_session_core.so").isFile)
    require(file(sessionInclude.get()).resolve("rq_experiment_services.h").isFile)
    require(sessionBinding.get().matches(Regex("[a-f0-9]{64}")))
    require(privateApplicationId.get().matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){2,}")))
} else {
    require(selectedSessionInputs.none { it.isPresent }) { "Session inputs require explicit workloadSessionCore selection" }
}
if (observerSelected) {
    require(observerModule.isPresent && observerHeaders.isPresent) { "Selected observer requires staged module and explicit headers" }
    require(file(observerModule.get()).resolve("CMakeLists.txt").isFile) { "Observer module is absent" }
    require(file(observerHeaders.get()).resolve("openxr/openxr_loader_negotiation.h").isFile) { "Observer negotiation headers are absent" }
}

android {
    sourceSets.getByName("debug").java.srcDir(if(feedbackSelected) "src/developmentFeedback/java" else "src/feedbackDisabled/java")
    sourceSets.getByName("release").java.srcDir("src/feedbackDisabled/java")
    if(feedbackSelected) {
        val core = providers.gradleProperty("workloadFeedbackCore").get()
        val host = providers.gradleProperty("workloadFeedbackHost").get()
        sourceSets.getByName("debug").java.srcDirs(file(core).resolve("src/main/java"), file(core).resolve("src/android/java"), file(host).resolve("src/main/java"))
    }
    if (externalReloadSelected) sourceSets.getByName("debug").assets.srcDir(externalReloadMarker)
    namespace = "io.github.mesmerprism.rustyquest.spatial_worldspace_hotload"
    compileSdk = 34
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.2.12479018"
    val externalSource = providers.gradleProperty("workloadExternalSource")
    require(!feedbackSelected || !externalSource.isPresent) { "Public development feedback has no external GPU source" }
    val workloadInputs = providers.gradleProperty("workloadInputs")
    val xrHeaders = providers.gradleProperty("workloadOpenXrInclude")
    val handShader = providers.gradleProperty("workloadHandShader")
    val hotloadAssets = providers.gradleProperty("workloadHotloadAssets")
    if (hotloadAssets.isPresent) sourceSets.getByName("debug").assets.srcDir(file(hotloadAssets.get()))
    if (observerSelected) sourceSets.getByName("debug").assets.srcDir(file(observerModule.get()).resolve("assets"))
    if (workloadInputs.isPresent) sourceSets.getByName("main").assets.srcDir(file(workloadInputs.get()).resolve("assets"))
    sourceSets.getByName("main").java.srcDir(if (sessionSelected) "src/sessionCore/java" else "src/sessionDisabled/java")
    if (sessionSelected) {
        sourceSets.getByName("main").java.srcDir(file(sessionJava.get()))
        sourceSets.getByName("debug").jniLibs.srcDir(file(sessionLibraries.get()))
        sourceSets.getByName("main").assets.srcDir(file(sessionAssets.get()))
        packaging.jniLibs.keepDebugSymbols.add("**/librusty_quest_sdk_session_core.so")
        // Preserve the exact inventoried NDK runtime required by the imported core.
        packaging.jniLibs.keepDebugSymbols.add("**/libc++_shared.so")
    }
    defaultConfig {
        applicationId = if (sessionSelected) privateApplicationId.get() else "io.github.mesmerprism.rustyquest.spatial_worldspace_hotload"
        manifestPlaceholders["workloadAppLabel"] = if (sessionSelected) privateAppLabel.get() else "@string/app_name"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-workload"
        ndk { abiFilters.add("arm64-v8a") }
        externalNativeBuild.cmake {
            if (externalSource.isPresent) arguments.add("-DRQ_WORKLOAD_EXTERNAL_SOURCE_DIR=" + file(externalSource.get()).absolutePath)
            if (workloadInputs.isPresent) arguments.add("-DRQ_WORKLOAD_INPUTS_DIR=" + file(workloadInputs.get()).absolutePath)
            if (xrHeaders.isPresent) arguments.add("-DRQ_WORKLOAD_OPENXR_INCLUDE=" + file(xrHeaders.get()).absolutePath)
            if (handShader.isPresent) arguments.add("-DRQ_WORKLOAD_HAND_SHADER=" + file(handShader.get()).absolutePath)
            arguments.add("-DRQ_SESSION_CORE=" + if (sessionSelected) "ON" else "OFF")
            if (sessionSelected) {
                arguments.add("-DANDROID_STL=c++_shared")
                arguments.add("-DRQ_EXPERIMENT_SERVICE_INCLUDE=" + file(sessionInclude.get()).absolutePath)
                arguments.add("-DRQ_SESSION_COMPOSITION_BINDING=" + sessionBinding.get())
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        prefab = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        debug {
            buildConfigField("boolean", "DEVELOPMENT_FEEDBACK", feedbackSelected.toString())
            buildConfigField("boolean", "SDK_GRAPHICS_HOT_RELOAD", graphicsReloadSelected.toString())
            buildConfigField("boolean", "EXTERNAL_WORKLOAD_HOTLOAD", externalReloadSelected.toString())
            externalNativeBuild.cmake.arguments.add("-DRQ_EXTERNAL_WORKLOAD_HOTLOAD=" + if (externalReloadSelected) "ON" else "OFF")
            buildConfigField("boolean", "SESSION_CORE", sessionSelected.toString())
            buildConfigField("boolean", "SUBMISSION_OBSERVER", observerSelected.toString())
            externalNativeBuild.cmake.arguments.add("-DRQ_XR_SUBMISSION_OBSERVER=" + if (observerSelected) "ON" else "OFF")
            if (observerSelected) {
                externalNativeBuild.cmake.arguments.add("-DRQ_XR_SUBMISSION_OBSERVER_MODULE=" + file(observerModule.get()).absolutePath)
                externalNativeBuild.cmake.arguments.add("-DRQ_OPENXR_INCLUDE_DIR=" + file(observerHeaders.get()).absolutePath)
            }
            externalNativeBuild.cmake.arguments.add("-DRQ_HOTLOAD_TESTS=ON")
            val nativeCatalog = providers.gradleProperty("workloadHotloadNativeCatalog")
            if (nativeCatalog.isPresent) externalNativeBuild.cmake.arguments.add("-DRQ_HOTLOAD_CATALOG_INCLUDE=" + file(nativeCatalog.get()).absolutePath)
        }
        release {
            buildConfigField("boolean", "DEVELOPMENT_FEEDBACK", "false")
            buildConfigField("boolean", "SDK_GRAPHICS_HOT_RELOAD", "false")
            buildConfigField("boolean", "EXTERNAL_WORKLOAD_HOTLOAD", "false")
            externalNativeBuild.cmake.arguments.add("-DRQ_EXTERNAL_WORKLOAD_HOTLOAD=OFF")
            buildConfigField("boolean", "SESSION_CORE", "false")
            buildConfigField("boolean", "SUBMISSION_OBSERVER", "false")
            externalNativeBuild.cmake.arguments.add("-DRQ_XR_SUBMISSION_OBSERVER=OFF")
            isMinifyEnabled = false
            externalNativeBuild.cmake.arguments.add("-DRQ_HOTLOAD_TESTS=OFF")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    val probeDebugKeystore = providers.gradleProperty("probeDebugKeystore")
    if (probeDebugKeystore.isPresent) {
        signingConfigs.getByName("debug") {
            storeFile = file(probeDebugKeystore.get())
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
}

tasks.register("writeProbeDependencyInventory") {
    doLast {
        val artifacts = (configurations.getByName("debugRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts +
            rootProject.buildscript.configurations.getByName("classpath").resolvedConfiguration.resolvedArtifacts +
            buildscript.configurations.getByName("classpath").resolvedConfiguration.resolvedArtifacts)
            .distinctBy { it.file.absolutePath }
            .sortedBy { it.moduleVersion.id.toString() + it.name }
        val records = artifacts.map { artifact ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it) }
            mapOf("coordinate" to artifact.moduleVersion.id.toString(),
                "artifact" to artifact.file.name, "path" to artifact.file.absolutePath,
                "sha256" to digest, "bytes" to artifact.file.length())
        }
        val output = layout.buildDirectory.file("probe/dependencies.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(records)))
    }
}

dependencies {
    implementation(libs.meta.spatial.base)
    implementation(libs.meta.spatial.vr)
    implementation(libs.meta.spatial.toolkit)
    implementation(libs.meta.spatial.compose)
    implementation(libs.meta.spatial.isdk)
    debugImplementation(libs.meta.spatial.hotreload)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation(libs.compose.tooling)
}

// Scene export needs Spatial Editor. The independently authored dynamic probe
// can be built without it; enable export explicitly for the unchanged MIT fixture.
val exportEditorScene = providers.gradleProperty("spatialEditorExport")
    .map(String::toBoolean).getOrElse(false)
val editorCli = providers.gradleProperty("spatialEditorCli")

spatial {
    allowUsageDataCollection.set(false)
    shaders { sources.add(layout.projectDirectory.dir("src/shaders")) }
    scenes {
        if (exportEditorScene) {
            if (editorCli.isPresent) cliPath.set(editorCli.get())
            exportItems {
                item {
                    projectPath.set(layout.projectDirectory.file("scenes/Main.metaspatial"))
                    outputPath.set(layout.projectDirectory.dir("src/main/assets/scenes"))
                }
            }
        }
        hotReload {
            appPackage.set(if (sessionSelected) privateApplicationId.get() else "io.github.mesmerprism.rustyquest.spatial_worldspace_hotload")
            appMainActivity.set(".SpatialWorldspaceHotloadActivity")
            assetsDir.set(layout.projectDirectory.dir("src/main/assets").asFile)
            enableShaderCompilation.set(true)
        }
    }
}
