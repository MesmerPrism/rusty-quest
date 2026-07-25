package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialProductBuildPolicy(
    val propertyNamespace: String,
) {
  val productId: String = CAMERA_PANEL_PRODUCT_ID
  val cameraPanelRoutesEnabled: Boolean = true

  fun markerFields(): String =
      "spatialProductId=${activityMarkerToken(productId)} " +
          "cameraPanelRoutesEnabled=$cameraPanelRoutesEnabled " +
          "applicationModule=:app " +
          "propertyNamespace=${activityMarkerToken(propertyNamespace)}"

  companion object {
    const val CAMERA_PANEL_PRODUCT_ID = "spatial-camera-panel"

    fun resolve(
        productId: String,
        propertyNamespace: String,
    ): SpatialProductBuildPolicy {
      check(productId.trim() == CAMERA_PANEL_PRODUCT_ID) {
        "Camera module cannot resolve another product: $productId"
      }
      return SpatialProductBuildPolicy(propertyNamespace = propertyNamespace)
    }

    val current: SpatialProductBuildPolicy by lazy(LazyThreadSafetyMode.PUBLICATION) {
      resolve(BuildConfig.SPATIAL_PRODUCT_ID, BuildConfig.SPATIAL_PROPERTY_NAMESPACE)
    }
  }
}
