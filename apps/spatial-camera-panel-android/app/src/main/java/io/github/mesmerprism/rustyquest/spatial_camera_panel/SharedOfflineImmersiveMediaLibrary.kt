package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

internal data class SharedOfflineImmersiveMediaLibrarySnapshot(
    val configured: Boolean,
    val accessible: Boolean,
    val writable: Boolean,
    val plainVideoTaxonomyReady: Boolean,
    val folderLabel: String,
    val packCount: Int,
    val plainVideoCount: Int,
    val rejectedPlainVideoCount: Int,
    val status: String,
)

internal fun sharedOfflineImmersiveMediaInitialSnapshot(
    configured: Boolean,
): SharedOfflineImmersiveMediaLibrarySnapshot =
    SharedOfflineImmersiveMediaLibrarySnapshot(
        configured = configured,
        accessible = false,
        writable = false,
        plainVideoTaxonomyReady = false,
        folderLabel = SharedOfflineImmersiveMediaLibrary.EXPECTED_FOLDER_NAME,
        packCount = 0,
        plainVideoCount = 0,
        rejectedPlainVideoCount = 0,
        status = if (configured) "refresh-required" else "folder-not-selected",
    )

/**
 * Persisted access to a media library chosen with Android's Storage Access Framework. Media reads
 * remain direct and read-only. An optional write grant is used only to create the fixed plain-video
 * directory taxonomy; the app never writes or copies video bytes.
 */
internal object SharedOfflineImmersiveMediaLibrary {
  const val EXPECTED_FOLDER_NAME = "RustySpatialMedia"
  const val PACKS_DIRECTORY_NAME = "offline-media-packs"
  private const val PREFERENCES_NAME = "shared-offline-immersive-media-library"
  private const val TREE_URI_KEY = "tree-uri"
  private const val MAX_DISCOVERED_PACKS = 32
  private val packIdPattern = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")

  fun adoptTreeUri(
      context: Context,
      treeUri: Uri,
      returnedGrantFlags: Int,
  ): SharedOfflineImmersiveMediaLibrarySnapshot {
    val takeFlags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (returnedGrantFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    context.contentResolver.takePersistableUriPermission(
        treeUri,
        takeFlags,
    )
    preferences(context).edit().putString(TREE_URI_KEY, treeUri.toString()).apply()
    if ((takeFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
      runCatching { SharedDocumentTree(context, treeUri).ensurePlainVideoTaxonomy() }
    }
    SharedPlainImmersiveMediaLibrary.invalidate()
    return snapshot(context)
  }

  fun clear(context: Context) {
    val uri = persistedTreeUri(context)
    if (uri != null) {
      runCatching {
        val flags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                if (hasPersistedWritePermission(context, uri)) {
                  Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                } else {
                  0
                }
        context.contentResolver.releasePersistableUriPermission(uri, flags)
      }
    }
    preferences(context).edit().remove(TREE_URI_KEY).apply()
    SharedPlainImmersiveMediaLibrary.invalidate()
  }

  fun persistedTreeUri(context: Context): Uri? =
      preferences(context)
          .getString(TREE_URI_KEY, null)
          ?.takeIf(String::isNotBlank)
          ?.let(Uri::parse)

  /** Local-only status readback. This never contacts a document provider. */
  fun statusWithoutScan(context: Context): SharedOfflineImmersiveMediaLibrarySnapshot =
      sharedOfflineImmersiveMediaInitialSnapshot(persistedTreeUri(context) != null)

  fun snapshot(
      context: Context,
      forceRefresh: Boolean = false,
  ): SharedOfflineImmersiveMediaLibrarySnapshot {
    val treeUri = persistedTreeUri(context)
        ?: return sharedOfflineImmersiveMediaInitialSnapshot(configured = false)
    return runCatching {
          val tree = SharedDocumentTree(context, treeUri)
          val writable = hasPersistedWritePermission(context, treeUri)
          if (forceRefresh && writable) {
            // Refresh is the explicit user-owned repair route for an older or partially-created
            // shared library. Only the fixed app taxonomy is created; media bytes remain untouched.
            runCatching { tree.ensurePlainVideoTaxonomy() }
          }
          val packIds = tree.packIds()
          val plain =
              SharedPlainImmersiveMediaLibrary.discover(
                  context,
                  forceRefresh = forceRefresh,
              )
          SharedOfflineImmersiveMediaLibrarySnapshot(
              configured = true,
              accessible = true,
              writable = writable,
              plainVideoTaxonomyReady = tree.plainVideoTaxonomyReady(),
              folderLabel = tree.rootName.ifBlank { EXPECTED_FOLDER_NAME },
              packCount = packIds.size,
              plainVideoCount = plain.items.size,
              rejectedPlainVideoCount = plain.rejectedCount,
              status =
                  if (packIds.isEmpty() && plain.items.isEmpty()) {
                    "folder-readable-no-media"
                  } else {
                    "ready"
                  },
          )
        }
        .getOrElse {
          SharedOfflineImmersiveMediaLibrarySnapshot(
              configured = true,
              accessible = false,
              writable = false,
              plainVideoTaxonomyReady = false,
              folderLabel = EXPECTED_FOLDER_NAME,
              packCount = 0,
              plainVideoCount = 0,
              rejectedPlainVideoCount = 0,
              status = "persisted-folder-unavailable",
          )
        }
  }

  fun packIds(context: Context): List<String> {
    val treeUri = persistedTreeUri(context) ?: return emptyList()
    return runCatching { SharedDocumentTree(context, treeUri).packIds() }
        .getOrDefault(emptyList())
  }

  fun resolve(
      context: Context,
      packId: String,
      keyHex: String,
  ): OfflineImmersiveMediaPackResolution? {
    val treeUri = persistedTreeUri(context) ?: return null
    return try {
      val source = SharedDocumentTree(context, treeUri).packSource(packId)
          ?: return OfflineImmersiveMediaPackResolution.Rejected(
              "shared-offline-pack-missing"
          )
      OfflineImmersiveMediaPackLoader.resolve(
          packId = packId,
          keyHex = keyHex,
          source = source,
      )
    } catch (_: SecurityException) {
      OfflineImmersiveMediaPackResolution.Rejected(
          "shared-offline-media-permission-unavailable"
      )
    } catch (_: Exception) {
      OfflineImmersiveMediaPackResolution.Rejected("shared-offline-media-invalid")
    }
  }

  private fun preferences(context: Context) =
      context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  private fun hasPersistedWritePermission(context: Context, treeUri: Uri): Boolean =
      context.contentResolver.persistedUriPermissions.any { permission ->
        permission.uri == treeUri && permission.isWritePermission
      }

  private class SharedDocumentTree(
      private val context: Context,
      private val treeUri: Uri,
  ) {
    private val resolver = context.contentResolver
    private val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    private val root =
        SharedDocument(
            documentId = treeDocumentId,
            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId),
            name = queryName(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
            ),
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            flags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong(),
            sizeBytes = 0L,
        )

    val rootName: String
      get() = root.name

    fun packIds(): List<String> {
      val packsRoot = packsRoot() ?: return emptyList()
      return children(packsRoot)
          .asSequence()
          .filter(SharedDocument::isDirectory)
          .map { it.name.trim().lowercase() }
          .filter(packIdPattern::matches)
          .filter { packId ->
            val packDirectory = child(packsRoot, packId) ?: return@filter false
            child(packDirectory, "manifest.json") != null
          }
          .distinct()
          .sorted()
          .take(MAX_DISCOVERED_PACKS)
          .toList()
    }

    fun plainVideoTaxonomyReady(): Boolean {
      val plainRoot = plainRoot() ?: return false
      return PlainImmersiveMediaPolicy.SHAPE_DIRECTORY_NAMES.all { shapeName ->
        val shape = child(plainRoot, shapeName) ?: return@all false
        shape.isDirectory &&
            PlainImmersiveMediaPolicy.STEREO_DIRECTORY_NAMES.all { stereoName ->
              child(shape, stereoName)?.isDirectory == true
            }
      }
    }

    fun ensurePlainVideoTaxonomy() {
      val plainRoot =
          if (root.name == PlainImmersiveMediaPolicy.ROOT_DIRECTORY_NAME) {
            root
          } else {
            ensureDirectory(root, PlainImmersiveMediaPolicy.ROOT_DIRECTORY_NAME)
          }
      for (shapeName in PlainImmersiveMediaPolicy.SHAPE_DIRECTORY_NAMES) {
        val shape = ensureDirectory(plainRoot, shapeName)
        for (stereoName in PlainImmersiveMediaPolicy.STEREO_DIRECTORY_NAMES) {
          ensureDirectory(shape, stereoName)
        }
      }
    }

    fun packSource(requestedPackId: String): OfflineImmersiveMediaPackSource? {
      val packId = requestedPackId.trim().lowercase()
      if (!packIdPattern.matches(packId)) return null
      val packsRoot = packsRoot() ?: return null
      val packDirectory = child(packsRoot, packId)
          ?.takeIf(SharedDocument::isDirectory)
          ?: return null
      val manifest = child(packDirectory, "manifest.json") ?: return null
      return SharedDocumentPackSource(packDirectory, manifest)
    }

    private fun packsRoot(): SharedDocument? {
      if (root.name == PACKS_DIRECTORY_NAME) return root
      return child(root, PACKS_DIRECTORY_NAME)
          ?.takeIf(SharedDocument::isDirectory)
          ?: fixedExternalStorageDirectory(listOf(PACKS_DIRECTORY_NAME))
    }

    private fun plainRoot(): SharedDocument? {
      if (root.name == PlainImmersiveMediaPolicy.ROOT_DIRECTORY_NAME) return root
      return child(root, PlainImmersiveMediaPolicy.ROOT_DIRECTORY_NAME)
          ?.takeIf(SharedDocument::isDirectory)
          ?: fixedExternalStorageDirectory(
              listOf(PlainImmersiveMediaPolicy.ROOT_DIRECTORY_NAME)
          )
    }

    private fun ensureDirectory(parent: SharedDocument, name: String): SharedDocument {
      child(parent, name)?.let { existing ->
        if (!existing.isDirectory) {
          throw IOException("plain-video-taxonomy-name-conflict")
        }
        return existing
      }
      val createdUri =
          DocumentsContract.createDocument(
              resolver,
              parent.uri,
              DocumentsContract.Document.MIME_TYPE_DIR,
              name,
          ) ?: throw IOException("plain-video-taxonomy-create-failed")
      val documentId = DocumentsContract.getDocumentId(createdUri)
      return SharedDocument(
          documentId = documentId,
          uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
          name = name,
          mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
          flags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong(),
          sizeBytes = 0L,
      )
    }

    private fun child(parent: SharedDocument, name: String): SharedDocument? =
        children(parent).firstOrNull {
          it.name.trim().equals(name.trim(), ignoreCase = true)
        }
            ?: fixedExternalStorageChild(parent, name)

    private fun fixedExternalStorageChild(
        parent: SharedDocument,
        name: String,
    ): SharedDocument? {
      if (treeUri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY ||
          name.isBlank() ||
          '/' in name ||
          '\\' in name) {
        return null
      }
      val documentId = "${parent.documentId.trimEnd('/')}/$name"
      val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
      return runCatching { queryDocument(documentId, uri) }.getOrNull()
    }

    private fun fixedExternalStorageDirectory(relativeSegments: List<String>): SharedDocument? {
      if (treeUri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY ||
          relativeSegments.any { it.isBlank() || '/' in it || '\\' in it }) {
        return null
      }
      val documentId =
          (listOf(treeDocumentId.trimEnd('/')) + relativeSegments).joinToString("/")
      val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
      return runCatching { queryDocument(documentId, uri) }
          .getOrNull()
          ?.takeIf(SharedDocument::isDirectory)
    }

    private fun children(parent: SharedDocument): List<SharedDocument> {
      val childrenUri =
          DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.documentId)
      val projection =
          arrayOf(
              DocumentsContract.Document.COLUMN_DOCUMENT_ID,
              DocumentsContract.Document.COLUMN_DISPLAY_NAME,
              DocumentsContract.Document.COLUMN_MIME_TYPE,
              DocumentsContract.Document.COLUMN_FLAGS,
              DocumentsContract.Document.COLUMN_SIZE,
          )
      return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(projection[0])
        val nameColumn = cursor.getColumnIndexOrThrow(projection[1])
        val typeColumn = cursor.getColumnIndexOrThrow(projection[2])
        val flagsColumn = cursor.getColumnIndexOrThrow(projection[3])
        val sizeColumn = cursor.getColumnIndexOrThrow(projection[4])
        buildList {
          while (cursor.`moveToNext`()) {
            val documentId = cursor.getString(idColumn)
            add(
                SharedDocument(
                    documentId = documentId,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                    name = cursor.getString(nameColumn).orEmpty(),
                    mimeType = cursor.getString(typeColumn).orEmpty(),
                    flags = if (cursor.isNull(flagsColumn)) 0L else cursor.getLong(flagsColumn),
                    sizeBytes = if (cursor.isNull(sizeColumn)) -1L else cursor.getLong(sizeColumn),
                )
            )
          }
        }
      } ?: emptyList()
    }

    private fun queryDocument(documentId: String, uri: Uri): SharedDocument {
      val projection =
          arrayOf(
              DocumentsContract.Document.COLUMN_DISPLAY_NAME,
              DocumentsContract.Document.COLUMN_MIME_TYPE,
              DocumentsContract.Document.COLUMN_FLAGS,
              DocumentsContract.Document.COLUMN_SIZE,
          )
      return resolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) throw IOException("shared-media-document-unreadable")
        SharedDocument(
            documentId = documentId,
            uri = uri,
            name = cursor.getString(0).orEmpty(),
            mimeType = cursor.getString(1).orEmpty(),
            flags = if (cursor.isNull(2)) 0L else cursor.getLong(2),
            sizeBytes = if (cursor.isNull(3)) -1L else cursor.getLong(3),
        )
      } ?: throw IOException("shared-media-document-unreadable")
    }

    private fun queryName(uri: Uri): String =
        resolver
            .query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )
            ?.use { cursor -> if (cursor.`moveToFirst`()) cursor.getString(0).orEmpty() else "" }
            .orEmpty()

    private inner class SharedDocumentPackSource(
        private val packDirectory: SharedDocument,
        private val manifest: SharedDocument,
    ) : OfflineImmersiveMediaPackSource {
      override val manifestLocation: String = "persisted-document-tree/manifest.json"

      override fun readManifestBytes(): ByteArray = readDocument(manifest, 1024 * 1024)

      override fun chunkSource(name: String): OfflineImmersiveMediaCiphertextSource? {
        val document = child(packDirectory, name) ?: return null
        if (document.isDirectory || document.sizeBytes < 0L) {
          return null
        }
        return object : OfflineImmersiveMediaCiphertextSource {
          override val location: String = "persisted-document-tree/$name"
          override val sizeBytes: Long = document.sizeBytes

          override fun readBytes(): ByteArray =
              readDocument(document, 64 * 1024 * 1024 + 16)
        }
      }
    }

    private fun readDocument(document: SharedDocument, maxBytes: Int): ByteArray {
      return resolver.openInputStream(document.uri)?.use { input ->
        val bytes = input.readNBytes(maxBytes + 1)
        if (bytes.size > maxBytes) throw IOException("offline-pack-document-too-large")
        bytes
      } ?: throw IOException("offline-pack-document-unreadable")
    }
  }

  private data class SharedDocument(
      val documentId: String,
      val uri: Uri,
      val name: String,
      val mimeType: String,
      val flags: Long,
      val sizeBytes: Long,
  ) {
    val isDirectory: Boolean
      get() = sharedDocumentRepresentsDirectory(mimeType, flags)
  }

  private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY =
      "com.android.externalstorage.documents"
}
