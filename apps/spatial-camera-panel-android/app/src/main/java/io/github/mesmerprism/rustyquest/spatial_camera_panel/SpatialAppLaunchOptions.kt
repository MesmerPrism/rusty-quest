package io.github.mesmerprism.rustyquest.spatial_camera_panel

/**
 * Optional private-owner bridge for public Connection Hub transport.
 *
 * The public app reflects only a neutral [ConnectionHubSurfaceTarget]. Playlist
 * identities, profile bindings, state production, and effect execution remain
 * in the private feature capsule.
 */
internal object SpatialConnectionHubSurfaceTargetLoader {
  private const val REGISTRY_CLASS =
      "io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialPrivateFeatureRegistry"
  private const val FACTORY_METHOD = "connectionHubSurfaceTarget"

  fun load(marker: (String) -> Unit): ConnectionHubSurfaceTarget? =
      runCatching {
            val registry = Class.forName(REGISTRY_CLASS)
            val create = registry.getMethod(FACTORY_METHOD)
            create.invoke(null) as? ConnectionHubSurfaceTarget
          }
          .onSuccess { target ->
            marker(
                "channel=spatial-connection-hub-target status=" +
                    if (target == null) "not-present" else "loaded"
            )
          }
          .onFailure { error ->
            marker(
                "channel=spatial-connection-hub-target status=not-present " +
                    "error=" + markerToken(error.javaClass.simpleName)
            )
          }
          .getOrNull()

  private fun markerToken(value: String): String =
      value.lowercase(java.util.Locale.ROOT)
          .replace(Regex("[^a-z0-9_.:-]+"), "-")
          .take(80)
          .ifBlank { "none" }
}

