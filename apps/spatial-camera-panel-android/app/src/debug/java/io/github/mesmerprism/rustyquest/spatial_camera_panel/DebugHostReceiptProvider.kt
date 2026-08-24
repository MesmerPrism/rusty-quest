package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process

/** Package-scoped DUMP-plus-shell-only provider with no URI, intent, component, or generic route. */
class DebugHostReceiptProvider : ContentProvider() {
  override fun onCreate(): Boolean = context != null

  override fun call(method: String, argument: String?, extras: Bundle?): Bundle {
    if (!BuildConfig.DEBUG || Binder.getCallingUid() != Process.SHELL_UID) closed()
    val request =
        try {
          DebugHostReceiptContract.parseCall(method, argument, extras)
        } catch (_: Exception) {
          closed()
        }
    val store = DebugHostReceiptRuntime.store(requireNotNull(context).applicationContext)
    return try {
      when (request.route) {
        DebugHostReceiptContract.Route.ARM -> {
          val expiresAt =
              DebugHostReceiptRuntime.arm(
                  requireNotNull(context).applicationContext,
                  requireNotNull(request.nonce),
              )
          response("armed").apply { putLong(DebugHostReceiptContract.KEY_EXPIRES_AT_MS, expiresAt) }
        }
        DebugHostReceiptContract.Route.STATUS -> {
          DebugHostReceiptRuntime.finalizeIfQualified(requireNotNull(context).applicationContext)
          val status = store.status()
          response(status.value).apply {
            status.receiptHash?.let { putString(DebugHostReceiptContract.KEY_RECEIPT_HASH, it) }
            status.expiresAtMs?.let { putLong(DebugHostReceiptContract.KEY_EXPIRES_AT_MS, it) }
          }
        }
        DebugHostReceiptContract.Route.READ -> {
          val hash = requireNotNull(request.receiptHash)
          response("terminal").apply {
            putString(DebugHostReceiptContract.KEY_RECEIPT_HASH, hash)
            putString(DebugHostReceiptContract.KEY_RECEIPT_JSON, store.read(hash))
          }
        }
        DebugHostReceiptContract.Route.CLEANUP -> {
          store.cleanup(requireNotNull(request.receiptHash))
          response("cleaned")
        }
      }
    } catch (_: Exception) {
      closed()
    }
  }

  override fun query(
      uri: Uri,
      projection: Array<out String>?,
      selection: String?,
      selectionArgs: Array<out String>?,
      sortOrder: String?,
  ): Cursor = closed()

  override fun getType(uri: Uri): String = closed()

  override fun insert(uri: Uri, values: ContentValues?): Uri? = closed()

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = closed()

  override fun update(
      uri: Uri,
      values: ContentValues?,
      selection: String?,
      selectionArgs: Array<out String>?,
  ): Int = closed()

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = closed()

  override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor = closed()

  override fun openTypedAssetFile(
      uri: Uri,
      mimeTypeFilter: String,
      opts: Bundle?,
  ): AssetFileDescriptor = closed()

  private fun response(status: String): Bundle =
      Bundle().apply {
        putString("schema", DebugHostReceiptContract.SCHEMA)
        putString(DebugHostReceiptContract.KEY_STATUS, status)
      }

  private fun closed(): Nothing = throw SecurityException("debug_host_receipt_request_rejected")
}
