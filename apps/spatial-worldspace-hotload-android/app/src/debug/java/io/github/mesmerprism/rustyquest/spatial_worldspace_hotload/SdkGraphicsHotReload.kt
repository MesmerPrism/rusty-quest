package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.debugtools.HotReloadFeature
import com.meta.spatial.toolkit.AppSystemActivity

/** Existing SDK developer protocol; no app-owned completion or authority claim. */
internal fun sdkGraphicsHotReloadFeatures(activity: AppSystemActivity, selected: Boolean): List<SpatialFeature> =
    if(selected) listOf(HotReloadFeature(activity)) else emptyList()
