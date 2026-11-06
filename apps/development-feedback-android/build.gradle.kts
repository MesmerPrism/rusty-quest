plugins {
    id("com.android.application") version "8.11.1" apply false
}
require(gradle.gradleVersion == "9.4.1") { "Select the repository-pinned Gradle9.4.1 tool" }
layout.buildDirectory.set(file("../../target/apk-i/development-feedback/warm/root-build"))
