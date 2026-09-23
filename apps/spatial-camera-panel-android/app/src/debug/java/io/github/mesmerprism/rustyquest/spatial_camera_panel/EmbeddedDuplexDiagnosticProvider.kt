package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexDiagnosticService

/** Debug-only DUMP-plus-shell entry point; the process service owns all diagnostic state. */
class EmbeddedDuplexDiagnosticProvider : ContentProvider() {
  override fun onCreate(): Boolean = context != null

  override fun call(method: String, argument: String?, extras: Bundle?): Bundle {
    if (!BuildConfig.DEBUG || !EmbeddedDuplexDiagnosticContract.callerIsShell(Binder.getCallingUid())) {
      closed()
    }
    val request =
        try {
          EmbeddedDuplexDiagnosticContract.parseCall(method, argument, extras)
        } catch (_: IllegalArgumentException) {
          closed()
        }
    val appContext = requireNotNull(context).applicationContext
    return when (request.route) {
      EmbeddedDuplexDiagnosticContract.Route.ARM -> {
        EmbeddedDuplexDiagnosticService.arm(appContext, request.nonce)
        response("armed")
      }
      EmbeddedDuplexDiagnosticContract.Route.READ ->
          response("terminal").apply {
            putString(
                EmbeddedDuplexDiagnosticContract.KEY_RECEIPT_BASE64,
                Base64.encodeToString(
                    EmbeddedDuplexDiagnosticService.read(appContext, request.nonce).toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                ),
            )
          }
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
        putString("schema", EmbeddedDuplexDiagnosticContract.SCHEMA)
        putString(EmbeddedDuplexDiagnosticContract.KEY_STATUS, status)
      }

  private fun closed(): Nothing = throw SecurityException("embedded_duplex_diagnostic_request_rejected")
}
