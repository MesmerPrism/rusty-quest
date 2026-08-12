package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal data class PlainImmersiveMediaProbe(
    val widthPx: Int,
    val heightPx: Int,
    val rotationDegrees: Int,
    val durationMs: Long,
    val containerMimeType: String,
    val sampledFrameWidthPx: Int,
    val sampledFrameHeightPx: Int,
)

internal data class PlainImmersiveMediaDeclaration(
    val shape: SpatialImmersiveVideoShape,
    val stereoLayout: SpatialImmersiveVideoStereoLayout,
)

internal sealed class PlainImmersiveMediaValidation {
  data class Accepted(
      val displayWidthPx: Int,
      val displayHeightPx: Int,
      val perEyeAspectRatio: Float,
  ) : PlainImmersiveMediaValidation()

  data class Rejected(val reason: String) : PlainImmersiveMediaValidation()
}

/** Pure classification oracle shared by host tests and the SAF document-tree adapter. */
internal object PlainImmersiveMediaPolicy {
  const val ROOT_DIRECTORY_NAME = "plain-videos"
  val SHAPE_DIRECTORY_NAMES = listOf("flat", "equirect-180", "equirect-360")
  val STEREO_DIRECTORY_NAMES =
      listOf("mono", "side-by-side-left-right", "top-bottom")
  const val MAX_ACCEPTED_ITEMS = 32
  const val MAX_PROBED_DOCUMENTS = 64
  private const val MAX_SOURCE_DIMENSION_PX = 16_384

  fun declaration(shapeDirectory: String, stereoDirectory: String):
      PlainImmersiveMediaDeclaration? {
    val shape = SpatialImmersiveVideoShape.fromToken(shapeDirectory) ?: return null
    val stereo = SpatialImmersiveVideoStereoLayout.fromToken(stereoDirectory) ?: return null
    return PlainImmersiveMediaDeclaration(shape, stereo)
  }

  /** Fixed app-owned directory plan. No caller-supplied path enters the SAF write route. */
  fun canonicalDirectoryChains(): List<List<String>> =
      buildList {
        add(listOf(ROOT_DIRECTORY_NAME))
        for (shape in SHAPE_DIRECTORY_NAMES) {
          add(listOf(ROOT_DIRECTORY_NAME, shape))
          for (stereo in STEREO_DIRECTORY_NAMES) {
            add(listOf(ROOT_DIRECTORY_NAME, shape, stereo))
          }
        }
      }

  fun validate(
      declaration: PlainImmersiveMediaDeclaration,
      probe: PlainImmersiveMediaProbe,
  ): PlainImmersiveMediaValidation {
    if (probe.widthPx !in 1..MAX_SOURCE_DIMENSION_PX ||
        probe.heightPx !in 1..MAX_SOURCE_DIMENSION_PX) {
      return PlainImmersiveMediaValidation.Rejected("plain-video-container-dimensions-invalid")
    }
    if (probe.rotationDegrees != 0) {
      return PlainImmersiveMediaValidation.Rejected("plain-video-rotation-unsupported")
    }
    if (probe.durationMs <= 0L) {
      return PlainImmersiveMediaValidation.Rejected("plain-video-duration-invalid")
    }
    if (probe.sampledFrameWidthPx <= 0 || probe.sampledFrameHeightPx <= 0) {
      return PlainImmersiveMediaValidation.Rejected("plain-video-sampled-frame-missing")
    }
    val containerAspect = probe.widthPx.toFloat() / probe.heightPx.toFloat()
    val sampledAspect =
        probe.sampledFrameWidthPx.toFloat() / probe.sampledFrameHeightPx.toFloat()
    if (abs(containerAspect - sampledAspect) / containerAspect > 0.04f) {
      return PlainImmersiveMediaValidation.Rejected(
          "plain-video-container-sample-geometry-mismatch"
      )
    }
    when (declaration.stereoLayout) {
      SpatialImmersiveVideoStereoLayout.SideBySideLeftRight ->
          if (probe.widthPx % 2 != 0) {
            return PlainImmersiveMediaValidation.Rejected(
                "plain-video-side-by-side-width-not-even"
            )
          }
      SpatialImmersiveVideoStereoLayout.TopBottom ->
          if (probe.heightPx % 2 != 0) {
            return PlainImmersiveMediaValidation.Rejected(
                "plain-video-top-bottom-height-not-even"
            )
          }
      SpatialImmersiveVideoStereoLayout.Mono -> Unit
    }
    val perEyeWidth =
        if (declaration.stereoLayout ==
            SpatialImmersiveVideoStereoLayout.SideBySideLeftRight) {
          probe.widthPx / 2
        } else {
          probe.widthPx
        }
    val perEyeHeight =
        if (declaration.stereoLayout == SpatialImmersiveVideoStereoLayout.TopBottom) {
          probe.heightPx / 2
        } else {
          probe.heightPx
        }
    val perEyeAspect = perEyeWidth.toFloat() / perEyeHeight.toFloat()
    val aspectAccepted =
        when (declaration.shape) {
          SpatialImmersiveVideoShape.Flat -> perEyeAspect in 0.25f..4.0f
          SpatialImmersiveVideoShape.Equirect180 -> perEyeAspect in 0.80f..1.25f
          SpatialImmersiveVideoShape.Equirect360 -> perEyeAspect in 1.75f..2.25f
        }
    if (!aspectAccepted) {
      return PlainImmersiveMediaValidation.Rejected(
          "plain-video-declared-shape-geometry-mismatch"
      )
    }
    return PlainImmersiveMediaValidation.Accepted(
        displayWidthPx = probe.widthPx,
        displayHeightPx = probe.heightPx,
        perEyeAspectRatio = perEyeAspect,
    )
  }

  fun mediaLabel(
      storageKind: String,
      shape: SpatialImmersiveVideoShape,
      stereoLayout: SpatialImmersiveVideoStereoLayout,
  ): String =
      "${if (storageKind == "shared-plain-video") "Plain" else "Encrypted"} · " +
          "${when (shape) {
            SpatialImmersiveVideoShape.Flat -> "Flat"
            SpatialImmersiveVideoShape.Equirect180 -> "180°"
            SpatialImmersiveVideoShape.Equirect360 -> "360°"
          }} · " +
          when (stereoLayout) {
            SpatialImmersiveVideoStereoLayout.Mono -> "Mono"
            SpatialImmersiveVideoStereoLayout.SideBySideLeftRight -> "Side-by-side"
            SpatialImmersiveVideoStereoLayout.TopBottom -> "Top/bottom"
          }
}

internal data class SharedPlainImmersiveMediaItem(
    val mediaId: String,
    val contentUri: String,
    val shape: SpatialImmersiveVideoShape,
    val stereoLayout: SpatialImmersiveVideoStereoLayout,
    val widthPx: Int,
    val heightPx: Int,
    val containerMimeType: String,
) {
  val catalogLabel: String
    get() = PlainImmersiveMediaPolicy.mediaLabel("shared-plain-video", shape, stereoLayout)
}

internal data class SharedPlainImmersiveMediaDiscovery(
    val items: List<SharedPlainImmersiveMediaItem>,
    val rejectedCount: Int,
    val probedCount: Int,
)

/**
 * Read-only discovery for unencrypted videos below
 * `RustySpatialMedia/plain-videos/<shape>/<stereo>/`. Folder names declare operator intent;
 * container metadata and a decoded sample must independently validate that declaration.
 */
internal object SharedPlainImmersiveMediaLibrary {
  @Volatile private var cachedTreeUri: String? = null
  @Volatile private var cachedDiscovery: SharedPlainImmersiveMediaDiscovery? = null

  fun invalidate() {
    cachedTreeUri = null
    cachedDiscovery = null
  }

  fun discover(
      context: Context,
      forceRefresh: Boolean = false,
  ): SharedPlainImmersiveMediaDiscovery {
    val treeUri = SharedOfflineImmersiveMediaLibrary.persistedTreeUri(context)
        ?: return SharedPlainImmersiveMediaDiscovery(emptyList(), 0, 0)
    val key = treeUri.toString()
    if (!forceRefresh && cachedTreeUri == key) {
      cachedDiscovery?.let { return it }
    }
    val discovery =
        runCatching { PlainSharedDocumentTree(context, treeUri).discover() }
            .getOrElse { SharedPlainImmersiveMediaDiscovery(emptyList(), 1, 0) }
    cachedTreeUri = key
    cachedDiscovery = discovery
    return discovery
  }

  private class PlainSharedDocumentTree(
      private val context: Context,
      private val treeUri: Uri,
  ) {
    private val resolver = context.contentResolver
    private val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    private val root =
        document(
            treeDocumentId,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId),
        )

    fun discover(): SharedPlainImmersiveMediaDiscovery {
      val plainRoot =
          if (root.name.normalizedDirectoryToken() == PlainImmersiveMediaPolicy.ROOT_DIRECTORY_NAME) {
            root
          } else {
            child(root, PlainImmersiveMediaPolicy.ROOT_DIRECTORY_NAME)
          }
              ?.takeIf { it.isDirectory }
              ?: return SharedPlainImmersiveMediaDiscovery(emptyList(), 0, 0)
      val candidates = ArrayList<Pair<PlainImmersiveMediaDeclaration, SharedPlainDocument>>()
      var rejectedCount = 0
      for (shapeDirectory in children(plainRoot).filter(SharedPlainDocument::isDirectory)) {
        val shapeToken = shapeDirectory.name.normalizedDirectoryToken()
        for (stereoDirectory in children(shapeDirectory).filter(SharedPlainDocument::isDirectory)) {
          val declaration =
              PlainImmersiveMediaPolicy.declaration(
                  shapeToken,
                  stereoDirectory.name.normalizedDirectoryToken(),
              )
          if (declaration == null) {
            rejectedCount += children(stereoDirectory).count { !it.isDirectory }
            continue
          }
          for (candidate in children(stereoDirectory).filterNot(SharedPlainDocument::isDirectory)) {
            candidates += declaration to candidate
          }
        }
      }
      val bounded =
          candidates
              .sortedBy { it.second.documentId }
              .take(PlainImmersiveMediaPolicy.MAX_PROBED_DOCUMENTS)
      rejectedCount += max(0, candidates.size - bounded.size)
      val accepted = ArrayList<SharedPlainImmersiveMediaItem>()
      for ((declaration, candidate) in bounded) {
        val probe = probe(candidate.uri)
        if (probe == null) {
          rejectedCount += 1
          continue
        }
        val validation = PlainImmersiveMediaPolicy.validate(declaration, probe)
        if (validation !is PlainImmersiveMediaValidation.Accepted ||
            accepted.size >= PlainImmersiveMediaPolicy.MAX_ACCEPTED_ITEMS) {
          rejectedCount += 1
          continue
        }
        accepted +=
            SharedPlainImmersiveMediaItem(
                mediaId = stableMediaId(candidate.uri),
                contentUri = candidate.uri.toString(),
                shape = declaration.shape,
                stereoLayout = declaration.stereoLayout,
                widthPx = validation.displayWidthPx,
                heightPx = validation.displayHeightPx,
                containerMimeType = probe.containerMimeType,
            )
      }
      return SharedPlainImmersiveMediaDiscovery(
          items = accepted,
          rejectedCount = rejectedCount,
          probedCount = bounded.size,
      )
    }

    private fun probe(uri: Uri): PlainImmersiveMediaProbe? =
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
                val durationMs =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: return@runCatching null
                val mime =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                        .orEmpty()
                val longest = max(width, height).coerceAtLeast(1)
                val sampleWidth = max(1, (width * (192.0 / longest)).roundToInt())
                val sampleHeight = max(1, (height * (192.0 / longest)).roundToInt())
                val sampleTimeUs = (durationMs.coerceAtLeast(1L) * 500L)
                val frame: Bitmap =
                    retriever.getScaledFrameAtTime(
                        sampleTimeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        sampleWidth,
                        sampleHeight,
                    ) ?: return@runCatching null
                try {
                  PlainImmersiveMediaProbe(
                      widthPx = width,
                      heightPx = height,
                      rotationDegrees = rotation,
                      durationMs = durationMs,
                      containerMimeType = mime,
                      sampledFrameWidthPx = frame.width,
                      sampledFrameHeightPx = frame.height,
                  )
                } finally {
                  frame.recycle()
                }
              } finally {
                retriever.release()
              }
            }
            .getOrNull()

    private fun stableMediaId(uri: Uri): String =
        MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private fun child(parent: SharedPlainDocument, name: String): SharedPlainDocument? =
        children(parent).firstOrNull {
          it.name.normalizedDirectoryToken() == name && it.isDirectory
        }

    private fun children(parent: SharedPlainDocument): List<SharedPlainDocument> {
      val childrenUri =
          DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.documentId)
      val projection =
          arrayOf(
              DocumentsContract.Document.COLUMN_DOCUMENT_ID,
              DocumentsContract.Document.COLUMN_DISPLAY_NAME,
              DocumentsContract.Document.COLUMN_MIME_TYPE,
              DocumentsContract.Document.COLUMN_SIZE,
          )
      return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(projection[0])
        val nameColumn = cursor.getColumnIndexOrThrow(projection[1])
        val typeColumn = cursor.getColumnIndexOrThrow(projection[2])
        val sizeColumn = cursor.getColumnIndexOrThrow(projection[3])
        buildList {
          while (cursor.moveToNext()) {
            val documentId = cursor.getString(idColumn)
            add(
                SharedPlainDocument(
                    documentId = documentId,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                    name = cursor.getString(nameColumn).orEmpty(),
                    mimeType = cursor.getString(typeColumn).orEmpty(),
                    sizeBytes = if (cursor.isNull(sizeColumn)) -1L else cursor.getLong(sizeColumn),
                )
            )
          }
        }
      } ?: emptyList()
    }

    private fun document(documentId: String, uri: Uri): SharedPlainDocument {
      val projection =
          arrayOf(
              DocumentsContract.Document.COLUMN_DISPLAY_NAME,
              DocumentsContract.Document.COLUMN_MIME_TYPE,
              DocumentsContract.Document.COLUMN_SIZE,
          )
      return resolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) error("shared-plain-video-root-unreadable")
        SharedPlainDocument(
            documentId = documentId,
            uri = uri,
            name = cursor.getString(0).orEmpty(),
            mimeType = cursor.getString(1).orEmpty(),
            sizeBytes = if (cursor.isNull(2)) -1L else cursor.getLong(2),
        )
      } ?: error("shared-plain-video-root-unreadable")
    }
  }
}

private fun String.normalizedDirectoryToken(): String = trim().lowercase()

private data class SharedPlainDocument(
    val documentId: String,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
) {
  val isDirectory: Boolean
    get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}
