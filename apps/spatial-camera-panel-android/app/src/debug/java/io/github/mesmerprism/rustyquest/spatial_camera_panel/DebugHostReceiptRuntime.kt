package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import java.io.File
import java.io.FileInputStream

/** App-internal future integration point. This slice deliberately does not invoke it from launch code. */
internal object DebugHostReceiptRuntime {
  private val processEpoch = DebugHostReceiptContract.freshEpoch()

  fun epoch(): String = processEpoch

  fun store(context: Context): DebugHostReceiptStore =
      DebugHostReceiptStore(File(context.filesDir, "debug-host-receipt"))

  fun finalizeTerminalReceipt(
      context: Context,
      nonce: String,
      facts: List<DebugHostReceiptStore.Fact>,
  ): String = store(context).finalizeReceipt(nonce, buildIdentity(context), facts)

  private fun buildIdentity(context: Context): DebugHostReceiptStore.Identity {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val apkHash =
        FileInputStream(context.applicationInfo.sourceDir).use { input ->
          val digest = java.security.MessageDigest.getInstance("SHA-256")
          val buffer = ByteArray(16 * 1024)
          while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
          }
          digest.digest().joinToString("") { "%02x".format(it) }
        }
    return DebugHostReceiptStore.Identity(
        applicationId = context.packageName,
        apkSha256 = apkHash,
        versionCode = packageInfo.longVersionCode,
        versionName = packageInfo.versionName ?: "unknown",
        variant = "debug",
        pid = android.os.Process.myPid(),
        epoch = processEpoch,
    )
  }
}
