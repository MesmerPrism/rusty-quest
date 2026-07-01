package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import com.meta.spatial.core.SpatialFeature
import java.util.Locale

internal object SpatialPrivateFeatureLoader {
  private const val REGISTRY_CLASS =
      "io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialPrivateFeatureRegistry"

  fun load(marker: (String) -> Unit, context: Context): List<SpatialFeature> {
    val registryClass =
        runCatching { Class.forName(REGISTRY_CLASS) }
            .getOrElse {
              marker(
                  "channel=spatial-private-feature-loader status=not-present " +
                      "privateFeatureSlot=true privateFeatureLoaded=false"
              )
              return emptyList()
            }

    return runCatching {
          val create =
              runCatching {
                    registryClass.getMethod("create", Function1::class.java, Context::class.java)
                  }
                  .getOrElse { registryClass.getMethod("create", Function1::class.java) }
          val features =
              if (create.parameterTypes.size == 2) {
                create.invoke(null, marker, context.applicationContext) as? List<*>
              } else {
                create.invoke(null, marker) as? List<*>
              } ?: emptyList<Any>()
          features.filterIsInstance<SpatialFeature>()
        }
        .onSuccess { features ->
          marker(
              "channel=spatial-private-feature-loader status=loaded " +
                  "privateFeatureSlot=true privateFeatureLoaded=true " +
                  "privateFeatureContext=true featureCount=${features.size}"
          )
        }
        .getOrElse { throwable ->
          marker(
              "channel=spatial-private-feature-loader status=load-failed " +
                  "privateFeatureSlot=true privateFeatureLoaded=false " +
                  "error=${markerToken(throwable.javaClass.simpleName)}"
          )
          emptyList()
        }
  }

  private fun markerToken(value: String): String =
      value.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9_.:-]+"), "-").ifBlank { "none" }
}
