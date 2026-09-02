package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugHostReceiptReleaseAbsenceTest {
  @Test
  fun receiptProviderIsDebugOnlyAndAbsentFromReleaseSources() {
    val appRoot = File(System.getProperty("user.dir") ?: ".")
    val debugManifest = File(appRoot, "src/debug/AndroidManifest.xml")
    assertTrue(debugManifest.isFile)
    assertTrue(debugManifest.readText().contains("DebugHostReceiptProvider"))
    assertTrue(
        debugManifest.readText().contains("${'$'}{applicationId}${DebugHostReceiptContract.AUTHORITY_SUFFIX}"))

    val firstAuthority = "io.github.mesmerprism.first${DebugHostReceiptContract.AUTHORITY_SUFFIX}"
    val secondAuthority = "io.github.mesmerprism.second${DebugHostReceiptContract.AUTHORITY_SUFFIX}"
    assertFalse(firstAuthority == secondAuthority)

    val releaseRoot = File(appRoot, "src/release")
    val mainManifest = File(appRoot, "src/main/AndroidManifest.xml")
    assertFalse(mainManifest.readText().contains("DebugHostReceiptProvider"))
    assertFalse(mainManifest.readText().contains(DebugHostReceiptContract.AUTHORITY_SUFFIX))
    if (releaseRoot.exists()) {
      releaseRoot.walkTopDown().filter { it.isFile }.forEach { file ->
        val text = file.readText()
        assertFalse(file.path, text.contains("DebugHostReceiptProvider"))
        assertFalse(file.path, text.contains(DebugHostReceiptContract.AUTHORITY_SUFFIX))
      }
    }
  }
}
