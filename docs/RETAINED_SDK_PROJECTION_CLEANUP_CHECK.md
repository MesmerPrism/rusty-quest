# Retained SDK projection cleanup check

`tools/checks/Test-RetainedSdkProjectionCleanup.ps1` compiles the actual resource and placement owners and the exact Activity UI-cleanup and projection-launch methods. Supply `RepoRoot`, `JavaHome`, `KotlinCompilerClassPath`, `KotlinStandardLibraryClassPath`, and an absent `OutputDirectory`; dependency paths are caller inputs, not repository data.

The focused cases retain failed SDK destruction ownership, reject apparent no-op retry completion, preserve dependencies and viewer-relative placement while cleanup remains incomplete, reject a replacement launch, and fence stale or expired UI work before effects. Healthy cleanup still removes its owned state.

The fixture mocks Android and SDK observations, including the SDK's sticky destruction behavior. It does not invoke JNI or prove physical resource release, headlock, streaming, or headset cleanup. SDK managed mesh/material completion remains distinct from native/GPU retirement. Run this check for changes to this boundary; it is not a new universal build or lifecycle prerequisite.
