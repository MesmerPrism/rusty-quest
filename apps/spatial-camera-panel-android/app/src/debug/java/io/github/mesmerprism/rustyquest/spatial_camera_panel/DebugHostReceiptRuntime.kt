package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import java.io.File
import java.io.FileInputStream

/** App-owned bridge from an armed shell nonce to a complete typed renderer snapshot. */
internal object DebugHostReceiptRuntime {
  private val processEpoch = DebugHostReceiptContract.freshEpoch()
  private val monitor = Any()
  private data class PendingNonce(val value: String, val expiresAtMs: Long)
  private var pendingNonce: PendingNonce? = null

  fun epoch(): String = processEpoch

  fun store(context: Context): DebugHostReceiptStore =
      DebugHostReceiptStore(File(context.filesDir, "debug-host-receipt"))

  fun arm(context: Context, nonce: String): Long = synchronized(monitor) {
    val validatedNonce = DebugHostReceiptContract.requireNonce(nonce)
    SpatialLaunchQualificationTelemetry.arm()
    try {
      val expiry = store(context).arm(validatedNonce, processEpoch)
      pendingNonce = PendingNonce(validatedNonce, expiry)
      expiry
    } catch (failure: Throwable) {
      SpatialLaunchQualificationTelemetry.disarm()
      throw failure
    }
  }

  fun finalizeIfQualified(context: Context): String? = synchronized(monitor) {
    val pending = pendingNonce ?: return null
    if (pending.expiresAtMs <= System.currentTimeMillis()) {
      pendingNonce = null
      SpatialLaunchQualificationTelemetry.disarm()
      return null
    }
    val snapshot = SpatialLaunchQualificationTelemetry.snapshot() ?: return null
    val facts = DebugHostReceiptQualificationReducer.terminalFacts(snapshot) ?: return null
    try {
      finalizeTerminalReceipt(context, pending.value, facts)
    } finally {
      pendingNonce = null
      SpatialLaunchQualificationTelemetry.disarm()
    }
  }

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
