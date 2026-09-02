package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

internal data class SpatialAppLaunchOption(
    val optionId: String,
    val displayLabel: String,
    val description: String,
)

internal object SpatialAppLaunchOptionsContract {
  const val SCHEMA = "rusty.quest.app_launch_options.v1"
  const val SCHEMA_VERSION = 1
  const val EXTRA_LAUNCH_OPTION_ID =
      "io.github.mesmerprism.rustyquest.spatial_camera_panel.extra.LAUNCH_OPTION_ID"
  const val PROVIDER_PATH = "options"
  const val PROVIDER_AUTHORITY_SUFFIX = ".app-launch-options"
  const val MAX_OPTION_COUNT = 64
  const val MAX_OPTION_ID_LENGTH = 160
  const val MAX_LABEL_LENGTH = 96
  const val MAX_DESCRIPTION_LENGTH = 160
  const val COLUMN_SCHEMA_VERSION = "schema_version"
  const val COLUMN_OPTION_ID = "option_id"
  const val COLUMN_DISPLAY_LABEL = "display_label"
  const val COLUMN_DESCRIPTION = "description"
  val COLUMNS =
      arrayOf(
          COLUMN_SCHEMA_VERSION,
          COLUMN_OPTION_ID,
          COLUMN_DISPLAY_LABEL,
          COLUMN_DESCRIPTION,
      )

  fun contentUri(applicationId: String): Uri =
      Uri.Builder()
          .scheme("content")
          .authority(applicationId + PROVIDER_AUTHORITY_SUFFIX)
          .appendPath(PROVIDER_PATH)
          .build()

  fun hasLaunchOption(intent: Intent): Boolean = intent.hasExtra(EXTRA_LAUNCH_OPTION_ID)

  fun requestedOptionId(intent: Intent): String? =
      intent.getStringExtra(EXTRA_LAUNCH_OPTION_ID)
          ?.takeIf { it.isNotBlank() && it.length <= MAX_OPTION_ID_LENGTH }

  fun validate(options: List<SpatialAppLaunchOption>): List<SpatialAppLaunchOption> {
    require(options.size <= MAX_OPTION_COUNT) { "launch-option-count-invalid" }
    require(options.map { it.optionId }.distinct().size == options.size) {
      "launch-option-id-duplicate"
    }
    return options.onEach { option ->
      require(
          option.optionId.isNotBlank() && option.optionId.length <= MAX_OPTION_ID_LENGTH
      ) {
        "launch-option-id-invalid"
      }
      require(
          option.displayLabel.isNotBlank() && option.displayLabel.length <= MAX_LABEL_LENGTH
      ) {
        "launch-option-label-invalid"
      }
      require(option.description.length <= MAX_DESCRIPTION_LENGTH) {
        "launch-option-description-invalid"
      }
    }
  }
}

/** Read-only bounded transport. The optional private owner supplies the rows. */
class SpatialAppLaunchOptionsProvider : ContentProvider() {
  override fun onCreate(): Boolean = context != null

  override fun query(
      uri: Uri,
      projection: Array<out String>?,
      selection: String?,
      selectionArgs: Array<out String>?,
      sortOrder: String?,
  ): Cursor {
    require(uri == SpatialAppLaunchOptionsContract.contentUri(BuildConfig.APPLICATION_ID)) {
      "unsupported-launch-options-uri"
    }
    require(selection == null && selectionArgs == null && sortOrder == null) {
      "launch-options-query-clauses-unsupported"
    }
    val columns = projection?.map { it }?.toTypedArray() ?: SpatialAppLaunchOptionsContract.COLUMNS
    require(
        columns.isNotEmpty() &&
            columns.distinct().size == columns.size &&
            columns.all { it in SpatialAppLaunchOptionsContract.COLUMNS }
    ) {
      "launch-options-projection-invalid"
    }
    val appContext = requireNotNull(context).applicationContext
    return MatrixCursor(columns).apply {
      SpatialPrivatePanelExtensionLoader.launchOptions(appContext).forEach { option ->
        val values =
            mapOf(
                SpatialAppLaunchOptionsContract.COLUMN_SCHEMA_VERSION to
                    SpatialAppLaunchOptionsContract.SCHEMA_VERSION,
                SpatialAppLaunchOptionsContract.COLUMN_OPTION_ID to option.optionId,
                SpatialAppLaunchOptionsContract.COLUMN_DISPLAY_LABEL to option.displayLabel,
                SpatialAppLaunchOptionsContract.COLUMN_DESCRIPTION to option.description,
            )
        addRow(columns.map(values::get))
      }
      setNotificationUri(appContext.contentResolver, uri)
    }
  }

  override fun getType(uri: Uri): String {
    require(uri == SpatialAppLaunchOptionsContract.contentUri(BuildConfig.APPLICATION_ID)) {
      "unsupported-launch-options-uri"
    }
    return "vnd.android.cursor.dir/vnd.rusty.quest.app-launch-option"
  }

  override fun insert(uri: Uri, values: ContentValues?): Uri? =
      throw UnsupportedOperationException("launch-options-provider-read-only")

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
      throw UnsupportedOperationException("launch-options-provider-read-only")

  override fun update(
      uri: Uri,
      values: ContentValues?,
      selection: String?,
      selectionArgs: Array<out String>?,
  ): Int = throw UnsupportedOperationException("launch-options-provider-read-only")
}
