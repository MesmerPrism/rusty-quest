package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedOfflineImmersiveMediaLibraryTest {
  @Test
  fun questDocumentDirectoriesAcceptCanonicalMimeProviderMimeAndCreateFlag() {
    assertTrue(
        sharedDocumentRepresentsDirectory(
            "vnd.android.document/directory",
            0L,
        )
    )
    assertTrue(sharedDocumentRepresentsDirectory("inode/directory", 0L))
    assertTrue(sharedDocumentRepresentsDirectory("", 1L shl 3))
    assertFalse(sharedDocumentRepresentsDirectory("video/mp4", 0L))
  }

  @Test
  fun localInitialStatusRequiresExplicitRefreshWithoutClaimingProviderAccess() {
    val configured = sharedOfflineImmersiveMediaInitialSnapshot(configured = true)
    assertTrue(configured.configured)
    assertFalse(configured.accessible)
    assertEquals("refresh-required", configured.status)
    assertEquals(0, configured.packCount)
    assertEquals(0, configured.plainVideoCount)

    val unconfigured = sharedOfflineImmersiveMediaInitialSnapshot(configured = false)
    assertFalse(unconfigured.configured)
    assertEquals("folder-not-selected", unconfigured.status)
  }
}
