package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
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

  @Test
  fun packagedImportReplacesSamePackIdWhenCiphertextRevisionChanges() {
    val root = Files.createTempDirectory("offline-pack-revision").toFile()
    try {
      val firstChunk = ByteArray(32) { index -> index.toByte() }
      val secondChunk = ByteArray(32) { index -> (index + 17).toByte() }
      val first = assetSource("pack-a", firstChunk)
      val second = assetSource("pack-a", secondChunk)

      assertTrue(PackagedOfflineImmersiveMediaPackImporter.ensureImported(root, "pack-a", first))
      val firstManifest = root.resolve("pack-a/manifest.json").readBytes()
      assertTrue(PackagedOfflineImmersiveMediaPackImporter.ensureImported(root, "pack-a", second))

      assertFalse(root.resolve("pack-a/manifest.json").readBytes().contentEquals(firstManifest))
      assertTrue(root.resolve("pack-a/chunk-000000.bin").readBytes().contentEquals(secondChunk))
      assertTrue(root.resolve("pack-a/.packaged-import.v1").isFile)
      assertTrue(root.listFiles().orEmpty().none { it.name.contains(".importing-") })
      assertTrue(root.listFiles().orEmpty().none { it.name.contains(".retired-") })
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun failedReplacementPreservesPreviouslyVerifiedPackBytes() {
      val root = Files.createTempDirectory("offline-pack-preserve").toFile()
    try {
      val firstChunk = ByteArray(32) { index -> index.toByte() }
      val damagedChunk = ByteArray(32) { index -> (index + 29).toByte() }
      val newlyDeclaredChunk = ByteArray(32) { index -> (index + 7).toByte() }
      val first = assetSource("pack-a", firstChunk)
      val damaged = assetSource("pack-a", damagedChunk, declaredChunk = newlyDeclaredChunk)
      assertTrue(PackagedOfflineImmersiveMediaPackImporter.ensureImported(root, "pack-a", first))
      val before = snapshot(root.resolve("pack-a"))

      assertFalse(
          PackagedOfflineImmersiveMediaPackImporter.ensureImported(root, "pack-a", damaged)
      )

      assertEquals(before, snapshot(root.resolve("pack-a")))
      assertTrue(root.listFiles().orEmpty().none { it.name.contains(".importing-") })
      assertTrue(root.listFiles().orEmpty().none { it.name.contains(".retired-") })
    } finally {
      root.deleteRecursively()
    }
  }

  private fun assetSource(
      packId: String,
      chunk: ByteArray,
      declaredChunk: ByteArray = chunk,
  ): PackagedOfflineImmersiveMediaPackImporter.AssetSource {
    val manifest =
        """{"schema":"$OFFLINE_IMMERSIVE_MEDIA_PACK_SCHEMA","pack_id":"$packId","chunks":[{"index":0,"file":"chunk-000000.bin","plaintext_length":${declaredChunk.size - 16},"ciphertext_sha256":"${sha256(declaredChunk)}"}]}"""
            .toByteArray(Charsets.UTF_8)
    return object : PackagedOfflineImmersiveMediaPackImporter.AssetSource {
      override fun readManifestBytes(): ByteArray = manifest.copyOf()

      override fun openChunk(name: String) =
          if (name == "chunk-000000.bin") ByteArrayInputStream(chunk.copyOf()) else null
    }
  }

  private fun snapshot(directory: java.io.File): Map<String, String> =
      directory
          .listFiles()
          .orEmpty()
          .associate { file -> file.name to sha256(file.readBytes()) }

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
