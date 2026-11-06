# Focused host verification

`Test-WorkloadContract.ps1` takes explicit Kotlin compiler/runtime JAR paths, a JDK17 executable and an output directory. It executes the actual `AtlasPacket.kt` plus `WorkloadContractHostTest.kt` standalone main; it is not a zero-test Gradle task. Output retains assertions, compilation and paths.

Custom SDK shaders must be compiled with genuine SDK0.14 headers under `data/shaders/`, `--target-env=vulkan1.1 -Werror`, both `VERTEX_FORMAT_TWOUV=1` and `VERTEX_FORMAT_COMPACT_BATCH=1`, with and without `GRAPHICS_HSR=1`. Validate emitted SPIR-V; reflect set3 material uniform binding0, atlas1 and mask2. Actual Kotlin frontend checks use the0.14 AAR classes, Android34 jar, Kotlin2.2.0 and its Compose compiler plugin. Debug and release sources are compiled separately.

These checks are source/ABI evidence only; new workload appearance, panel inputs and GPU/SDK sampling remain hardware-pending.
