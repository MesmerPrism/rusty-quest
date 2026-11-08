package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal const val IMMERSIVE_VIDEO_RETAIN_BACKINGS_PROPERTY =
    "debug.rustyquest.spatial.immersive_video.retain_backings"

/** Explicit construction-time diagnostic, never an ambient release policy. */
internal object SpatialImmersiveVideoBackingRetentionPolicy {
  fun selected(debugBuild: Boolean, readProperty: (String) -> String): Boolean =
      debugBuild && runCatching { readProperty(IMMERSIVE_VIDEO_RETAIN_BACKINGS_PROPERTY) }
          .getOrNull() == "1"
}

/** A scene owner serializes calls; keys are the fixed, already-declared panel registrations. */
internal class SpatialImmersiveVideoBackingRegistry<K : Any, V : Any>(allowedKeys: Set<K>) {
  data class Entry<K, V>(val key: K, val value: V, val creationEpoch: Long, val geometryKey: String)
  data class Acquisition<K, V>(val entry: Entry<K, V>, val reused: Boolean)

  private val allowedKeys = allowedKeys.toSet()
  private val entries = LinkedHashMap<K, Entry<K, V>>()
  private var active: Entry<K, V>? = null
  private var nextCreationEpoch = 0L
  private var retired = false
  val size: Int get() = entries.size

  fun acquire(
      key: K,
      geometryKey: String,
      hide: (Entry<K, V>) -> Unit,
      releaseAborted: (Entry<K, V>) -> Unit,
      create: (Long) -> V,
  ): Acquisition<K, V> {
    check(!retired) { "backing-registry-scene-retired" }
    require(key in allowedKeys) { "backing-registration-not-declared" }
    val existing = entries[key]
    check(existing == null || existing.geometryKey == geometryKey) {
      "backing-registration-geometry-changed-requires-fresh-scene"
    }
    if (active != null && active !== existing) hideActive(hide)
    check(!retired) { "backing-registry-retired-during-hide" }
    val entry = existing ?: run {
      val epoch = ++nextCreationEpoch
      val created = Entry(key, create(epoch), epoch, geometryKey)
      if (retired) {
        // Creation may re-enter scene retirement before its value can be registered.
        // Never publish it into the retired registry, even if release itself fails.
        releaseAborted(created)
        error("backing-registry-retired-during-create")
      }
      created.also { entries[key] = it }
    }
    active = entry
    return Acquisition(entry, existing != null)
  }

  // SDK callbacks can carry an equivalent Entity wrapper for the same SDK identity.
  fun find(key: K, value: V): Entry<K, V>? =
      entries[key]?.takeIf { !retired && it.value == value }

  fun hideActive(hide: (Entry<K, V>) -> Unit) {
    val entry = active ?: return
    active = null
    hide(entry)
  }

  fun hasGeometryConflict(proposed: Map<K, String>): Boolean =
      entries.any { (key, entry) -> proposed[key] != entry.geometryKey }

  fun retire(release: (Entry<K, V>) -> Unit) {
    if (retired) return
    retired = true
    active = null
    val pending = entries.values.toList()
    entries.clear() // Take every handle before a re-entrant SDK deletion callback.
    var firstFailure: Throwable? = null
    for (entry in pending) {
      try {
        release(entry)
      } catch (failure: Throwable) {
        if (firstFailure == null) firstFailure = failure
      }
    }
    firstFailure?.let { throw it }
  }
}
