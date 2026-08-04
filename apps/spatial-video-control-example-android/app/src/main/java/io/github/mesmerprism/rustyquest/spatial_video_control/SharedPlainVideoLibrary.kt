package io.github.mesmerprism.rustyquest.spatial_video_control

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

data class SharedPlainVideoLibrarySnapshot(
    val configured: Boolean,
    val accessible: Boolean,
    val writable: Boolean,
    val taxonomyReady: Boolean,
    val folderLabel: String,
    val acceptedItems: List<VideoCatalog.Video>,
    val rejectedCount: Int,
    val probedCount: Int,
    val status: String,
)

/**
 * Persisted, read-only user-video discovery under one wearer-selected Storage Access Framework
 * tree. The optional write grant creates only the fixed directory taxonomy; video bytes are never
 * copied or written by the app.
 */
object SharedPlainVideoLibrary {
  const val EXPECTED_FOLDER_NAME = "RustySpatialMedia"
  private const val PREFERENCES_NAME = "rusty-spatial-video-user-library"
  private const val TREE_URI_KEY = "tree-uri"

  fun emptySnapshot(status: String = "folder-not-selected") =
      SharedPlainVideoLibrarySnapshot(
          configured = false,
          accessible = false,
          writable = false,
          taxonomyReady = false,
          folderLabel = EXPECTED_FOLDER_NAME,
          acceptedItems = emptyList(),
          rejectedCount = 0,
          probedCount = 0,
          status = status,
      )

  fun adoptTreeUri(
      context: Context,
      treeUri: Uri,
      returnedGrantFlags: Int,
  ): SharedPlainVideoLibrarySnapshot {
    val tree = SharedDocumentTree(context, treeUri)
    require(tree.rootName == EXPECTED_FOLDER_NAME) {
      "select-the-exact-$EXPECTED_FOLDER_NAME-folder"
    }
    val takeFlags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (returnedGrantFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
    preferences(context).edit().putString(TREE_URI_KEY, treeUri.toString()).apply()
    if ((takeFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
      tree.ensureTaxonomy()
    }
    return snapshot(context)
  }

  fun clear(context: Context) {
    persistedTreeUri(context)?.let { uri ->
      runCatching {
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (hasPersistedWritePermission(context, uri)) {
          flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        context.contentResolver.releasePersistableUriPermission(uri, flags)
      }
    }
    preferences(context).edit().remove(TREE_URI_KEY).apply()
  }

  fun persistedTreeUri(context: Context): Uri? =
      preferences(context)
          .getString(TREE_URI_KEY, null)
          ?.takeIf(String::isNotBlank)
          ?.let(Uri::parse)

  fun snapshot(context: Context): SharedPlainVideoLibrarySnapshot {
    val treeUri = persistedTreeUri(context) ?: return emptySnapshot()
    return runCatching {
          val tree = SharedDocumentTree(context, treeUri)
          val discovery = tree.discover()
          SharedPlainVideoLibrarySnapshot(
              configured = true,
              accessible = true,
              writable = hasPersistedWritePermission(context, treeUri),
              taxonomyReady = tree.taxonomyReady(),
              folderLabel = tree.rootName.ifBlank { EXPECTED_FOLDER_NAME },
              acceptedItems = discovery.accepted,
              rejectedCount = discovery.rejectedCount,
              probedCount = discovery.probedCount,
              status =
                  when {
                    discovery.accepted.isNotEmpty() -> "ready"
                    discovery.rejectedCount > 0 -> "no-valid-videos"
                    else -> "folder-readable-no-videos"
                  },
          )
        }
        .getOrElse {
          SharedPlainVideoLibrarySnapshot(
              configured = true,
              accessible = false,
              writable = false,
              taxonomyReady = false,
              folderLabel = EXPECTED_FOLDER_NAME,
              acceptedItems = emptyList(),
              rejectedCount = 0,
              probedCount = 0,
              status = "persisted-folder-unavailable",
          )
        }
  }

  private fun preferences(context: Context) =
      context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  private fun hasPersistedWritePermission(context: Context, uri: Uri): Boolean =
      context.contentResolver.persistedUriPermissions.any { permission ->
        permission.uri == uri && permission.isWritePermission
      }

  private data class Discovery(
      val accepted: List<VideoCatalog.Video>,
      val rejectedCount: Int,
      val probedCount: Int,
  )

  private data class Document(
      val documentId: String,
      val uri: Uri,
      val name: String,
      val mimeType: String,
      val sizeBytes: Long,
  ) {
    val isDirectory: Boolean
      get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
  }

  private class SharedDocumentTree(
      private val context: Context,
      private val treeUri: Uri,
  ) {
    private val resolver = context.contentResolver
    private val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    private val root =
        document(
            rootDocumentId,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId),
        )

    val rootName: String
      get() = root.name

    fun ensureTaxonomy() {
      val plain = ensureDirectory(root, PlainVideoPolicy.ROOT_DIRECTORY_NAME)
      for (shapeName in PlainVideoPolicy.SHAPE_DIRECTORY_NAMES) {
        val shape = ensureDirectory(plain, shapeName)
        for (stereoName in PlainVideoPolicy.STEREO_DIRECTORY_NAMES) {
          ensureDirectory(shape, stereoName)
        }
      }
    }

    fun taxonomyReady(): Boolean {
      val plain = child(root, PlainVideoPolicy.ROOT_DIRECTORY_NAME) ?: return false
      return PlainVideoPolicy.SHAPE_DIRECTORY_NAMES.all { shapeName ->
        val shape = child(plain, shapeName) ?: return@all false
        PlainVideoPolicy.STEREO_DIRECTORY_NAMES.all { stereoName ->
          child(shape, stereoName)?.isDirectory == true
        }
      }
    }

    fun discover(): Discovery {
      val plain =
          child(root, PlainVideoPolicy.ROOT_DIRECTORY_NAME)
              ?.takeIf(Document::isDirectory)
              ?: return Discovery(emptyList(), 0, 0)
      val candidates = ArrayList<Pair<PlainVideoPolicy.Declaration, Document>>()
      var rejected = 0
      for (shapeDirectory in children(plain).filter(Document::isDirectory)) {
        for (stereoDirectory in children(shapeDirectory).filter(Document::isDirectory)) {
          val declaration =
              PlainVideoPolicy.declaration(
                  shapeDirectory.name.normalizedToken(),
                  stereoDirectory.name.normalizedToken(),
              )
          if (declaration == null) {
            rejected += children(stereoDirectory).count { !it.isDirectory }
            continue
          }
          children(stereoDirectory)
              .filterNot(Document::isDirectory)
              .forEach { candidates += declaration to it }
        }
      }
      val bounded =
          candidates.sortedBy { it.second.documentId }.take(PlainVideoPolicy.MAX_PROBED_DOCUMENTS)
      rejected += max(0, candidates.size - bounded.size)
      val accepted = ArrayList<VideoCatalog.Video>()
      for ((declaration, candidate) in bounded) {
        val probe = probe(candidate.uri)
        val validation = probe?.let { PlainVideoPolicy.validate(declaration, it) }
        if (validation == null || !validation.accepted() ||
            accepted.size >= PlainVideoPolicy.MAX_ACCEPTED_ITEMS) {
          rejected += 1
          continue
        }
        val mediaId = stableMediaId(candidate.uri)
        accepted +=
            VideoCatalog.userDocument(
                "user-video-$mediaId",
                title(candidate.name, declaration),
                candidate.uri.toString(),
                probe.durationMs(),
                validation.displayWidthPx(),
                validation.displayHeightPx(),
                declaration.projectionShape(),
                declaration.stereoLayout(),
            )
      }
      return Discovery(accepted, rejected, bounded.size)
    }

    private fun probe(uri: Uri): PlainVideoPolicy.Probe? =
        runCatching {
              val retriever = MediaMetadataRetriever()
              try {
                retriever.setDataSource(context, uri)
                val width =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: return@runCatching null
                val height =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: return@runCatching null
                val rotation =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                val duration =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: return@runCatching null
                val mime =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
                val longest = max(width, height).coerceAtLeast(1)
                val sampleWidth = max(1, (width * (192.0 / longest)).roundToInt())
                val sampleHeight = max(1, (height * (192.0 / longest)).roundToInt())
                val frame: Bitmap =
                    retriever.getScaledFrameAtTime(
                        duration.coerceAtLeast(1L) * 500L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        sampleWidth,
                        sampleHeight,
                    ) ?: return@runCatching null
                try {
                  PlainVideoPolicy.Probe(
                      width,
                      height,
                      rotation,
                      duration,
                      mime,
                      frame.width,
                      frame.height,
                  )
                } finally {
                  frame.recycle()
                }
              } finally {
                retriever.release()
              }
            }
            .getOrNull()

    private fun ensureDirectory(parent: Document, name: String): Document {
      child(parent, name)?.let { existing ->
        require(existing.isDirectory) { "user-media-taxonomy-name-conflict" }
        return existing
      }
      val uri =
          requireNotNull(
              DocumentsContract.createDocument(
                  resolver,
                  parent.uri,
                  DocumentsContract.Document.MIME_TYPE_DIR,
                  name,
              )
          ) { "user-media-taxonomy-create-failed" }
      return document(DocumentsContract.getDocumentId(uri), uri)
    }

    private fun child(parent: Document, name: String): Document? =
        children(parent).firstOrNull { it.isDirectory && it.name.normalizedToken() == name }

    private fun children(parent: Document): List<Document> {
      val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.documentId)
      val projection =
          arrayOf(
              DocumentsContract.Document.COLUMN_DOCUMENT_ID,
              DocumentsContract.Document.COLUMN_DISPLAY_NAME,
              DocumentsContract.Document.COLUMN_MIME_TYPE,
              DocumentsContract.Document.COLUMN_SIZE,
          )
      return resolver.query(uri, projection, null, null, null)?.use { cursor ->
        val id = cursor.getColumnIndexOrThrow(projection[0])
        val name = cursor.getColumnIndexOrThrow(projection[1])
        val type = cursor.getColumnIndexOrThrow(projection[2])
        val size = cursor.getColumnIndexOrThrow(projection[3])
        buildList {
          while (cursor.moveToNext()) {
            val documentId = cursor.getString(id)
            add(
                Document(
                    documentId,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                    cursor.getString(name).orEmpty(),
                    cursor.getString(type).orEmpty(),
                    if (cursor.isNull(size)) -1L else cursor.getLong(size),
                )
            )
          }
        }
      } ?: emptyList()
    }

    private fun document(documentId: String, uri: Uri): Document {
      val projection =
          arrayOf(
              DocumentsContract.Document.COLUMN_DISPLAY_NAME,
              DocumentsContract.Document.COLUMN_MIME_TYPE,
              DocumentsContract.Document.COLUMN_SIZE,
          )
      return resolver.query(uri, projection, null, null, null)?.use { cursor ->
        check(cursor.moveToFirst()) { "user-media-root-unreadable" }
        Document(
            documentId,
            uri,
            cursor.getString(0).orEmpty(),
            cursor.getString(1).orEmpty(),
            if (cursor.isNull(2)) -1L else cursor.getLong(2),
        )
      } ?: error("user-media-root-unreadable")
    }
  }

  private fun stableMediaId(uri: Uri): String =
      MessageDigest.getInstance("SHA-256")
          .digest(uri.toString().toByteArray(StandardCharsets.UTF_8))
          .joinToString("") { "%02x".format(it) }
          .take(16)

  private fun title(name: String, declaration: PlainVideoPolicy.Declaration): String {
    val base = name.substringBeforeLast('.').trim().ifBlank { "User video" }.take(48)
    val shape =
        when (declaration.projectionShape()) {
          VideoCatalog.ProjectionShape.FLAT -> "Flat"
          VideoCatalog.ProjectionShape.EQUIRECT_180 -> "180°"
          VideoCatalog.ProjectionShape.EQUIRECT_360 -> "360°"
        }
    val stereo =
        when (declaration.stereoLayout()) {
          VideoCatalog.StereoLayout.MONO -> "Mono"
          VideoCatalog.StereoLayout.SIDE_BY_SIDE_LEFT_RIGHT -> "SBS"
          VideoCatalog.StereoLayout.TOP_BOTTOM -> "Top/bottom"
        }
    return "$base · $shape · $stereo".take(80)
  }
}

private fun String.normalizedToken(): String = trim().lowercase()
