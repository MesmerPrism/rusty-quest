package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineImmersiveMediaPackDiscoveryTest {
  @Test
  fun discoversOnlyBoundedValidInstalledPackDirectories() {
    val root = Files.createTempDirectory("offline-pack-discovery").toFile()
    try {
      repeat(35) { index ->
        root.resolve("pack-${index.toString().padStart(2, '0')}").apply {
          mkdirs()
          resolve("manifest.json").writeText("{}")
        }
      }
      root.resolve(".pack-importing").apply {
        mkdirs()
        resolve("manifest.json").writeText("{}")
      }
      root.resolve("missing-manifest").mkdirs()
      root.resolve("not-a-directory").writeText("ignored")

      val packIds = PackagedOfflineImmersiveMediaPackImporter.installedPackIds(root)

      assertEquals(32, packIds.size)
      assertEquals(packIds.sorted(), packIds)
      assertEquals("pack-00", packIds.first())
      assertEquals("pack-31", packIds.last())
      assertFalse(packIds.contains(".pack-importing"))
      assertFalse(packIds.contains("missing-manifest"))
      assertTrue(packIds.all { it.matches(Regex("^[a-z0-9][a-z0-9._-]{0,95}$")) })
    } finally {
      root.deleteRecursively()
    }
  }
}
