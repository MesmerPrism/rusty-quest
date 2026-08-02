package io.github.mesmerprism.rustyquest.spatial_video_control

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Base64
import org.json.JSONObject

/** Closed debug-only control surface for Android's DUMP-authorized shell UID. */
class DebugShellControlProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    check(Binder.getCallingUid() == SHELL_UID) { "shell_uid_required" }
    check(arg == null && (extras == null || extras.isEmpty)) { "arguments_not_supported" }
    val receipt =
        runCatching {
              when (method) {
                "status" -> DebugShellControlBridge.status()
                "enable_paired" -> DebugShellControlBridge.enablePaired()
                "enable_open_lan" -> DebugShellControlBridge.enableOpenLan()
                "revoke" -> DebugShellControlBridge.revoke()
                else -> error("method_not_registered")
              }
            }
            .getOrElse { error ->
              JSONObject()
                  .put("schema", "rusty.quest.debug_local_control_receipt.v1")
                  .put("action", method.take(32))
                  .put("phase", "rejected")
                  .put("confirmed", false)
                  .put("reason", error.message?.take(96) ?: error.javaClass.simpleName)
                  .toString()
            }
    val encoded =
        Base64.encodeToString(receipt.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return Bundle().apply { putString(RECEIPT_KEY, encoded) }
  }

  override fun query(
      uri: Uri,
      projection: Array<out String>?,
      selection: String?,
      selectionArgs: Array<out String>?,
      sortOrder: String?,
  ): Cursor? = error("query_not_supported")

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = error("insert_not_supported")

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
      error("delete_not_supported")

  override fun update(
      uri: Uri,
      values: ContentValues?,
      selection: String?,
      selectionArgs: Array<out String>?,
  ): Int = error("update_not_supported")

  private companion object {
    const val SHELL_UID = 2000
    const val RECEIPT_KEY = "receipt_b64"
  }
}
