package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal const val OFFLINE_IMMERSIVE_MEDIA_PACK_SCHEMA =
    "rusty.quest.offline_immersive_media_pack.v1"

internal interface OfflineImmersiveMediaCiphertextSource {
  val location: String
  val sizeBytes: Long

  @Throws(IOException::class) fun readBytes(): ByteArray
}

internal interface OfflineImmersiveMediaPackSource {
  val manifestLocation: String

  @Throws(IOException::class) fun readManifestBytes(): ByteArray

  fun chunkSource(name: String): OfflineImmersiveMediaCiphertextSource?
}

internal data class OfflineImmersiveMediaChunk(
    val index: Int,
    val plaintextOffset: Long,
    val plaintextLength: Int,
    val ciphertextSource: OfflineImmersiveMediaCiphertextSource,
    val nonce: ByteArray,
    val ciphertextSha256: String,
)

internal data class OfflineImmersiveMediaPack(
    val packId: String,
    val manifestFile: File?,
    val sourceSizeBytes: Long,
    val sourceSha256: String,
    val chunkSizeBytes: Int,
    val shape: SpatialImmersiveVideoShape,
    val stereoLayout: SpatialImmersiveVideoStereoLayout,
    val widthPx: Int,
    val heightPx: Int,
    val chunks: List<OfflineImmersiveMediaChunk>,
    val key: ByteArray,
    val packagedInApk: Boolean,
    val storageKind: String = if (manifestFile == null) "shared-document-tree" else "file",
    val manifestLocation: String = manifestFile?.path ?: "shared-document-tree",
) {
  val virtualUriString: String
    get() = "rusty-offline-media://$packId/video"

  val virtualUri: Uri
    get() = Uri.parse(virtualUriString)

  fun aad(chunk: OfflineImmersiveMediaChunk): ByteArray =
      listOf(
              OFFLINE_IMMERSIVE_MEDIA_PACK_SCHEMA,
              packId,
              chunk.index.toString(),
              chunk.plaintextOffset.toString(),
              chunk.plaintextLength.toString(),
              sourceSizeBytes.toString(),
              sourceSha256,
              shape.token,
              stereoLayout.token,
              widthPx.toString(),
              heightPx.toString(),
          )
          .joinToString("|")
          .toByteArray(StandardCharsets.UTF_8)
}

internal sealed class OfflineImmersiveMediaPackResolution {
  data class Ready(val pack: OfflineImmersiveMediaPack) :
      OfflineImmersiveMediaPackResolution()

  data class Rejected(val reason: String) : OfflineImmersiveMediaPackResolution()
}

internal object OfflineImmersiveMediaPackLoader {
  private val packIdPattern = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")
  private val sha256Pattern = Regex("^[a-f0-9]{64}$")
  private const val MAX_CHUNK_SIZE_BYTES = 64 * 1024 * 1024
  private const val GCM_TAG_SIZE_BYTES = 16

  fun resolve(
      mediaPackRoot: File,
      packId: String,
      keyHex: String,
      packagedInApk: Boolean = false,
  ): OfflineImmersiveMediaPackResolution =
      try {
        val packId = requestedPackId(packId)
        val canonicalRoot = mediaPackRoot.canonicalFile
        val packDirectory = File(canonicalRoot, packId).canonicalFile
        requirePack(
            packDirectory.path.startsWith(canonicalRoot.path + File.separator),
            "offline-pack-path-outside-root",
        )
        val manifestFile = File(packDirectory, "manifest.json").canonicalFile
        requirePack(
            manifestFile.path.startsWith(packDirectory.path + File.separator) &&
                manifestFile.isFile,
            "offline-pack-manifest-missing",
        )
        OfflineImmersiveMediaPackResolution.Ready(
            load(
                requestedPackId = packId,
                keyHex = keyHex,
                packagedInApk = packagedInApk,
                manifestFile = manifestFile,
                source = FileOfflineImmersiveMediaPackSource(packDirectory, manifestFile),
                storageKind = "file",
            )
        )
      } catch (error: OfflineImmersiveMediaPackException) {
        OfflineImmersiveMediaPackResolution.Rejected(error.reason)
      } catch (_: Exception) {
        OfflineImmersiveMediaPackResolution.Rejected("offline-pack-invalid")
      }

  fun resolve(
      packId: String,
      keyHex: String,
      source: OfflineImmersiveMediaPackSource,
      storageKind: String = "shared-document-tree",
  ): OfflineImmersiveMediaPackResolution =
      try {
        OfflineImmersiveMediaPackResolution.Ready(
            load(
                requestedPackId = requestedPackId(packId),
                keyHex = keyHex,
                packagedInApk = false,
                manifestFile = null,
                source = source,
                storageKind = storageKind,
            )
        )
      } catch (error: OfflineImmersiveMediaPackException) {
        OfflineImmersiveMediaPackResolution.Rejected(error.reason)
      } catch (_: Exception) {
        OfflineImmersiveMediaPackResolution.Rejected("offline-pack-invalid")
      }

  private fun load(
      requestedPackId: String,
      keyHex: String,
      packagedInApk: Boolean,
      manifestFile: File?,
      source: OfflineImmersiveMediaPackSource,
      storageKind: String,
  ): OfflineImmersiveMediaPack {
    val packId = requestedPackId(packId = requestedPackId)
    requirePack(keyHex.matches(Regex("^[a-fA-F0-9]{64}$")), "embedded-key-missing")
    val key = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val manifest = JSONObject(String(source.readManifestBytes(), StandardCharsets.UTF_8))
    requirePack(
        manifest.optString("schema") == OFFLINE_IMMERSIVE_MEDIA_PACK_SCHEMA,
        "offline-pack-schema-unsupported",
    )
    requirePack(manifest.optString("pack_id") == packId, "offline-pack-id-mismatch")

    val encryption = manifest.getJSONObject("encryption")
    requirePack(
        encryption.optString("algorithm") == "AES-256-GCM" &&
            encryption.optInt("key_bits") == 256 &&
            encryption.optInt("nonce_bytes") == 12 &&
            encryption.optInt("tag_bytes") == GCM_TAG_SIZE_BYTES,
        "offline-pack-encryption-unsupported",
    )
    val sourceMetadata = manifest.getJSONObject("source")
    val sourceSizeBytes = sourceMetadata.getLong("size_bytes")
    val sourceSha256 = sourceMetadata.getString("sha256")
    val widthPx = sourceMetadata.getInt("width_px")
    val heightPx = sourceMetadata.getInt("height_px")
    val shape =
        SpatialImmersiveVideoShape.fromToken(sourceMetadata.getString("projection_shape"))
            ?: throw OfflineImmersiveMediaPackException("projection-shape-unknown")
    val stereoLayout =
        SpatialImmersiveVideoStereoLayout.fromToken(sourceMetadata.getString("stereo_layout"))
            ?: throw OfflineImmersiveMediaPackException("stereo-layout-unknown")
    requirePack(sourceSizeBytes > 0L, "offline-pack-source-size-invalid")
    requirePack(sha256Pattern.matches(sourceSha256), "offline-pack-source-sha256-invalid")
    requirePack(widthPx in 1..16_384, "source-width-invalid")
    requirePack(heightPx in 1..16_384, "source-height-invalid")
    requirePack(
        stereoLayout != SpatialImmersiveVideoStereoLayout.SideBySideLeftRight ||
            widthPx % 2 == 0,
        "side-by-side-width-not-even",
    )
    requirePack(
        stereoLayout != SpatialImmersiveVideoStereoLayout.TopBottom ||
            heightPx % 2 == 0,
        "top-bottom-height-not-even",
    )

    val chunkSizeBytes = manifest.getInt("chunk_size_bytes")
    requirePack(
        chunkSizeBytes in 1..MAX_CHUNK_SIZE_BYTES,
        "offline-pack-chunk-size-invalid",
    )
    val chunkArray = manifest.getJSONArray("chunks")
    requirePack(chunkArray.length() > 0, "offline-pack-chunks-missing")
    val chunks = ArrayList<OfflineImmersiveMediaChunk>(chunkArray.length())
    var expectedOffset = 0L
    for (index in 0 until chunkArray.length()) {
      val item = chunkArray.getJSONObject(index)
      requirePack(item.getInt("index") == index, "offline-pack-chunk-index-invalid")
      val plaintextOffset = item.getLong("plaintext_offset")
      val plaintextLength = item.getInt("plaintext_length")
      requirePack(
          plaintextOffset == expectedOffset,
          "offline-pack-chunk-offset-invalid",
      )
      requirePack(
          plaintextLength in 1..chunkSizeBytes,
          "offline-pack-chunk-length-invalid",
      )
      if (index < chunkArray.length() - 1) {
        requirePack(
            plaintextLength == chunkSizeBytes,
            "offline-pack-nonfinal-chunk-short",
        )
      }
      val expectedFileName = "chunk-${index.toString().padStart(6, '0')}.bin"
      requirePack(
          item.getString("file") == expectedFileName,
          "offline-pack-chunk-name-invalid",
      )
      val ciphertextSource = source.chunkSource(expectedFileName)
      requirePack(
          ciphertextSource != null &&
              ciphertextSource.sizeBytes == plaintextLength.toLong() + GCM_TAG_SIZE_BYTES,
          "offline-pack-chunk-file-invalid",
      )
      val nonce = Base64.decode(item.getString("nonce_base64"), Base64.NO_WRAP)
      requirePack(nonce.size == 12, "offline-pack-chunk-nonce-invalid")
      val ciphertextSha256 = item.getString("ciphertext_sha256")
      requirePack(
          sha256Pattern.matches(ciphertextSha256),
          "offline-pack-chunk-sha256-invalid",
      )
      chunks +=
          OfflineImmersiveMediaChunk(
              index = index,
              plaintextOffset = plaintextOffset,
              plaintextLength = plaintextLength,
              ciphertextSource = ciphertextSource!!,
              nonce = nonce,
              ciphertextSha256 = ciphertextSha256,
          )
      expectedOffset += plaintextLength
    }
    requirePack(expectedOffset == sourceSizeBytes, "offline-pack-source-size-mismatch")

    return OfflineImmersiveMediaPack(
        packId = packId,
        manifestFile = manifestFile,
        sourceSizeBytes = sourceSizeBytes,
        sourceSha256 = sourceSha256,
        chunkSizeBytes = chunkSizeBytes,
        shape = shape,
        stereoLayout = stereoLayout,
        widthPx = widthPx,
        heightPx = heightPx,
        chunks = chunks,
        key = key,
        packagedInApk = packagedInApk,
        storageKind = storageKind,
        manifestLocation = source.manifestLocation,
    )
  }

  private fun requestedPackId(packId: String): String {
    val normalized = packId.trim().lowercase()
    requirePack(packIdPattern.matches(normalized), "offline-pack-id-invalid")
    return normalized
  }

  private fun requirePack(condition: Boolean, reason: String) {
    if (!condition) {
      throw OfflineImmersiveMediaPackException(reason)
    }
  }
}

private class FileOfflineImmersiveMediaPackSource(
    private val packDirectory: File,
    private val manifestFile: File,
) : OfflineImmersiveMediaPackSource {
  override val manifestLocation: String = manifestFile.path

  override fun readManifestBytes(): ByteArray = manifestFile.readBytes()

  override fun chunkSource(name: String): OfflineImmersiveMediaCiphertextSource? {
    val candidate = File(packDirectory, name).canonicalFile
    if (!candidate.path.startsWith(packDirectory.path + File.separator) || !candidate.isFile) {
      return null
    }
    return object : OfflineImmersiveMediaCiphertextSource {
      override val location: String = candidate.path
      override val sizeBytes: Long = candidate.length()

      override fun readBytes(): ByteArray = candidate.readBytes()
    }
  }
}

internal object PackagedOfflineImmersiveMediaPackImporter {
  private val packIdPattern = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")
  private val chunkNamePattern = Regex("^chunk-[0-9]{6}\\.bin$")
  private const val MAX_DISCOVERED_PACKS = 32

  fun packagedPackIds(context: Context): List<String> =
      if (!BuildConfig.OFFLINE_MEDIA_PACKAGED_ASSETS) {
        emptyList()
      } else {
        runCatching {
              context.assets
                  .list("offline-media-packs")
                  .orEmpty()
                  .asSequence()
                  .map(String::trim)
                  .map(String::lowercase)
                  .filter(packIdPattern::matches)
                  .distinct()
                  .sorted()
                  .take(MAX_DISCOVERED_PACKS)
                  .toList()
            }
            .getOrDefault(emptyList())
      }

  fun installedPackIds(context: Context): List<String> =
      installedPackIds(File(context.filesDir, "offline-media-packs"))

  internal fun installedPackIds(mediaPackRoot: File): List<String> =
      runCatching {
            mediaPackRoot
                .listFiles()
                .orEmpty()
                .asSequence()
                .filter(File::isDirectory)
                .map(File::getName)
                .filter(packIdPattern::matches)
                .filter { packId -> File(mediaPackRoot, "$packId/manifest.json").isFile }
                .distinct()
                .sorted()
                .take(MAX_DISCOVERED_PACKS)
                .toList()
          }
          .getOrDefault(emptyList())

  fun ensureImported(context: Context, requestedPackId: String): Boolean {
    val packId = requestedPackId.trim().lowercase()
    if (!packIdPattern.matches(packId)) {
      return false
    }
    val root = File(context.filesDir, "offline-media-packs")
    val finalDirectory = File(root, packId)
    if (File(finalDirectory, "manifest.json").isFile) {
      return true
    }
    val assetPrefix = "offline-media-packs/$packId"
    val manifestBytes =
        runCatching {
              context.assets.open("$assetPrefix/manifest.json").use { it.readBytes() }
            }
            .getOrNull() ?: return false
    val manifest =
        runCatching { JSONObject(String(manifestBytes, StandardCharsets.UTF_8)) }.getOrNull()
            ?: return false
    if (manifest.optString("schema") != OFFLINE_IMMERSIVE_MEDIA_PACK_SCHEMA ||
        manifest.optString("pack_id") != packId) {
      return false
    }
    val chunks = runCatching { manifest.getJSONArray("chunks") }.getOrNull() ?: return false
    val expectedNames = ArrayList<String>(chunks.length())
    for (index in 0 until chunks.length()) {
      val item = runCatching { chunks.getJSONObject(index) }.getOrNull() ?: return false
      val name = item.optString("file")
      if (item.optInt("index", -1) != index ||
          name != "chunk-${index.toString().padStart(6, '0')}.bin" ||
          !chunkNamePattern.matches(name)) {
        return false
      }
      expectedNames += name
    }

    root.mkdirs()
    val stagingDirectory =
        File(root, ".$packId.importing-${System.nanoTime().toString(16)}")
    if (!stagingDirectory.mkdir()) {
      return false
    }
    return runCatching {
          File(stagingDirectory, "manifest.json").writeBytes(manifestBytes)
          for (name in expectedNames) {
            val target = File(stagingDirectory, name)
            context.assets.open("$assetPrefix/$name").use { input ->
              target.outputStream().use(input::copyTo)
            }
          }
          if (finalDirectory.exists()) {
            File(finalDirectory, "manifest.json").isFile
          } else {
            stagingDirectory.renameTo(finalDirectory) &&
                File(finalDirectory, "manifest.json").isFile
          }
        }
        .getOrDefault(false)
  }
}

internal class OfflineImmersiveMediaPackException(val reason: String) : IOException(reason)

internal class OfflineImmersiveMediaChunkReader(
    private val pack: OfflineImmersiveMediaPack,
    private val onChunkDecrypted: (Int) -> Unit = {},
) {
  private var cachedChunkIndex = -1
  private var cachedPlaintext: ByteArray? = null

  fun readAt(position: Long, target: ByteArray, targetOffset: Int, requestedLength: Int): Int {
    if (position >= pack.sourceSizeBytes) {
      return C.RESULT_END_OF_INPUT
    }
    require(position >= 0L)
    require(targetOffset >= 0 && requestedLength >= 0 && targetOffset + requestedLength <= target.size)
    if (requestedLength == 0) {
      return 0
    }
    val chunkIndex = (position / pack.chunkSizeBytes).toInt()
    val chunk = pack.chunks.getOrNull(chunkIndex) ?: throw IOException("offline-pack-chunk-missing")
    if (position !in chunk.plaintextOffset until
        (chunk.plaintextOffset + chunk.plaintextLength)) {
      throw IOException("offline-pack-chunk-map-invalid")
    }
    val plaintext = plaintextFor(chunk)
    val withinChunk = (position - chunk.plaintextOffset).toInt()
    val count =
        minOf(
            requestedLength,
            chunk.plaintextLength - withinChunk,
            (pack.sourceSizeBytes - position).toInt(),
        )
    plaintext.copyInto(target, targetOffset, withinChunk, withinChunk + count)
    return count
  }

  fun close() {
    cachedPlaintext?.fill(0)
    cachedPlaintext = null
    cachedChunkIndex = -1
  }

  private fun plaintextFor(chunk: OfflineImmersiveMediaChunk): ByteArray {
    if (cachedChunkIndex == chunk.index) {
      return cachedPlaintext ?: throw IOException("offline-pack-cache-invalid")
    }
    cachedPlaintext?.fill(0)
    val encrypted = chunk.ciphertextSource.readBytes()
    val actualSha256 = MessageDigest.getInstance("SHA-256").digest(encrypted).toHex()
    if (actualSha256 != chunk.ciphertextSha256) {
      throw IOException("offline-pack-ciphertext-sha256-mismatch")
    }
    val plaintext =
        try {
          Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(pack.key, "AES"),
                GCMParameterSpec(128, chunk.nonce),
            )
            updateAAD(pack.aad(chunk))
            doFinal(encrypted)
          }
        } catch (_: AEADBadTagException) {
          throw IOException("offline-pack-chunk-authentication-failed")
        }
    if (plaintext.size != chunk.plaintextLength) {
      plaintext.fill(0)
      throw IOException("offline-pack-plaintext-length-mismatch")
    }
    cachedChunkIndex = chunk.index
    cachedPlaintext = plaintext
    onChunkDecrypted(chunk.index)
    return plaintext
  }
}

internal class OfflineImmersiveMediaExtractorDataSource(
    private val pack: OfflineImmersiveMediaPack,
    private val emitMarker: (String) -> Unit,
) : MediaDataSource() {
  private val reader =
      OfflineImmersiveMediaChunkReader(pack) { chunkIndex ->
        emitMarker(
            "channel=spatial-immersive-video status=encrypted-chunk-decrypted " +
                "packId=${activityMarkerToken(pack.packId)} chunkIndex=$chunkIndex " +
                "consumer=android-media-extractor chunkAuthentication=aes-256-gcm " +
                "plaintextFileWritten=false"
        )
      }
  private var closed = false

  @Synchronized
  override fun readAt(position: Long, target: ByteArray, offset: Int, size: Int): Int {
    if (closed) {
      throw IOException("offline-pack-data-source-closed")
    }
    return try {
      val count = reader.readAt(position, target, offset, size)
      if (count == C.RESULT_END_OF_INPUT) -1 else count
    } catch (error: IOException) {
      emitMarker(
          "channel=spatial-immersive-video status=encrypted-chunk-error " +
              "packId=${activityMarkerToken(pack.packId)} " +
              "consumer=android-media-extractor " +
              "reason=${activityMarkerToken(error.message ?: "offline-pack-read-failed")} " +
              "failClosed=true plaintextFileWritten=false"
      )
      throw error
    }
  }

  override fun getSize(): Long = pack.sourceSizeBytes

  @Synchronized
  override fun close() {
    if (!closed) {
      reader.close()
      closed = true
    }
  }
}

internal class EncryptedOfflineImmersiveMediaDataSource(
    private val pack: OfflineImmersiveMediaPack,
    private val emitMarker: (String) -> Unit,
) : BaseDataSource(false) {
  private val reader =
      OfflineImmersiveMediaChunkReader(pack) { chunkIndex ->
        emitMarker(
            "channel=spatial-immersive-video status=encrypted-chunk-decrypted " +
                "packId=${activityMarkerToken(pack.packId)} chunkIndex=$chunkIndex " +
                "chunkAuthentication=aes-256-gcm plaintextFileWritten=false"
        )
      }
  private var openedDataSpec: DataSpec? = null
  private var readPosition = 0L
  private var bytesRemaining = 0L

  override fun open(dataSpec: DataSpec): Long {
    if (dataSpec.uri != pack.virtualUri) {
      throw IOException("offline-pack-uri-invalid")
    }
    if (dataSpec.position < 0L || dataSpec.position > pack.sourceSizeBytes) {
      throw IOException("offline-pack-read-position-invalid")
    }
    transferInitializing(dataSpec)
    readPosition = dataSpec.position
    val available = pack.sourceSizeBytes - readPosition
    bytesRemaining =
        if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
          available
        } else {
          minOf(dataSpec.length, available)
        }
    openedDataSpec = dataSpec
    transferStarted(dataSpec)
    return bytesRemaining
  }

  override fun read(target: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) {
      return 0
    }
    if (bytesRemaining == 0L) {
      return C.RESULT_END_OF_INPUT
    }
    val count =
        try {
          reader.readAt(readPosition, target, offset, minOf(length.toLong(), bytesRemaining).toInt())
        } catch (error: IOException) {
          emitMarker(
              "channel=spatial-immersive-video status=encrypted-chunk-error " +
                  "packId=${activityMarkerToken(pack.packId)} " +
                  "reason=${activityMarkerToken(error.message ?: "offline-pack-read-failed")} " +
                  "failClosed=true plaintextFileWritten=false"
          )
          throw error
        }
    if (count == C.RESULT_END_OF_INPUT) {
      return count
    }
    readPosition += count
    bytesRemaining -= count
    bytesTransferred(count)
    return count
  }

  override fun getUri(): Uri? = openedDataSpec?.uri

  override fun close() {
    if (openedDataSpec != null) {
      openedDataSpec = null
      transferEnded()
    }
    reader.close()
  }

  class Factory(
      private val pack: OfflineImmersiveMediaPack,
      private val emitMarker: (String) -> Unit,
  ) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        EncryptedOfflineImmersiveMediaDataSource(pack, emitMarker)
  }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
