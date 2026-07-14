package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal object SpatialAdapterNativeAuthority {
  private const val NATIVE_RECEIPT_LIBRARY = "spatial_camera_panel_native_receipt"
  private var loadAttempted = false
  private var loaded = false

  @Synchronized
  private fun ensureLoaded(): Boolean {
    if (!loadAttempted) {
      loadAttempted = true
      loaded = runCatching { System.loadLibrary(NATIVE_RECEIPT_LIBRARY) }.isSuccess
    }
    return loaded
  }

  fun resolveHand(input: SpatialAdapterRuntimeInput): SpatialAdapterLockDecision =
      resolve(input) {
        nativeResolveHandAdapterActivation(
            input.enabled,
            input.profileId,
            input.projectId,
            input.featureId,
            input.lockRevision,
            input.lockSha256,
        )
      }

  fun resolveParticle(input: SpatialAdapterRuntimeInput): SpatialAdapterLockDecision =
      resolve(input) {
        nativeResolveParticleAdapterActivation(
            input.enabled,
            input.profileId,
            input.projectId,
            input.featureId,
            input.lockRevision,
            input.lockSha256,
        )
      }

  fun resolveAsset(input: SpatialAdapterRuntimeInput): SpatialAdapterLockDecision =
      resolve(input) {
        nativeResolveAssetModelActivation(
            input.enabled,
            input.profileId,
            input.projectId,
            input.featureId,
            input.lockRevision,
            input.lockSha256,
        )
      }

  private fun resolve(
      input: SpatialAdapterRuntimeInput,
      nativeResolver: () -> String?,
  ): SpatialAdapterLockDecision {
    if (!ensureLoaded()) {
      return SpatialAdapterLockBinding.rejected(input, "native-authority-library-unavailable")
    }
    val receipt = runCatching(nativeResolver).getOrNull()
    return SpatialAdapterLockBinding.parseAuthorityReceipt(receipt, input)
  }

  @JvmStatic
  private external fun nativeResolveHandAdapterActivation(
      runtimeEnabled: Boolean,
      runtimeProfileId: String,
      runtimeProjectId: String,
      runtimeFeatureId: String,
      runtimeLockRevision: Long,
      runtimeLockSha256: String,
  ): String?

  @JvmStatic
  private external fun nativeResolveParticleAdapterActivation(
      runtimeEnabled: Boolean,
      runtimeProfileId: String,
      runtimeProjectId: String,
      runtimeFeatureId: String,
      runtimeLockRevision: Long,
      runtimeLockSha256: String,
  ): String?

  @JvmStatic
  private external fun nativeResolveAssetModelActivation(
      runtimeEnabled: Boolean,
      runtimeProfileId: String,
      runtimeProjectId: String,
      runtimeFeatureId: String,
      runtimeLockRevision: Long,
      runtimeLockSha256: String,
  ): String?
}
