package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.content.res.AssetManager
import com.meta.spatial.core.Vector3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** CPU reference skinning for an app-owned hand rig driven by mapped OpenXR joint rows. */
internal class SpatialLiveSkinnedHandSurface private constructor(
    private val hands: Array<SpatialSkinningHand>,
    private val assetId: String,
) {
  private val cachedRows = Array(HAND_COUNT) { FloatArray(HAND_ROW_FLOAT_COUNT) }
  private val cachedRowsReady = BooleanArray(HAND_COUNT)
  private val cachedRowsMissCount = IntArray(HAND_COUNT)
  private var frameIndex = 0

  fun snapshot(
      jointRows: FloatArray,
      particleCount: Int,
      normalOffsetMeters: Float,
  ): SpatialLiveHandSurfaceFrame? {
    if (jointRows.size != SpatialLiveHandJointBridge.EXPECTED_FLOAT_COUNT) {
      return null
    }
    val counts = splitCounts(particleCount.coerceIn(1, MAX_TOTAL_SAMPLE_COUNT), HAND_COUNT)
    val frames = arrayOfNulls<SpatialLiveHandSurfaceHandFrame>(HAND_COUNT)
    val validJointCounts = IntArray(HAND_COUNT)
    var activeHandCount = 0
    var cachedHandCount = 0

    for (handIndex in 0 until HAND_COUNT) {
      val resolved = resolveRows(jointRows, handIndex)
      validJointCounts[handIndex] = resolved.validJointCount
      val handRows = resolved.rows ?: continue
      val skinned = hands[handIndex].skin(handRows) ?: continue
      val sampleSet = hands[handIndex].sampleSetFor(counts[handIndex])
      val positions = Array(counts[handIndex]) { Vector3(0.0f) }
      val tangents = Array(counts[handIndex]) { Vector3(1.0f, 0.0f, 0.0f) }
      val bitangents = Array(counts[handIndex]) { Vector3(0.0f, 1.0f, 0.0f) }
      val normals = Array(counts[handIndex]) { Vector3(0.0f, 0.0f, 1.0f) }
      for (localIndex in positions.indices) {
        val sampleIndex = scaledIndex(localIndex, positions.size, sampleSet.sampleCount)
        val sample = hands[handIndex].sample(skinned, sampleSet, sampleIndex)
        positions[localIndex] = sample.position + sample.normal * normalOffsetMeters
        tangents[localIndex] = sample.tangent
        bitangents[localIndex] = sample.bitangent
        normals[localIndex] = sample.normal
      }
      frames[handIndex] =
          SpatialLiveHandSurfaceHandFrame(
              handedness = hands[handIndex].handedness,
              positions = positions,
              tangents = tangents,
              bitangents = bitangents,
              normals = normals,
              meshCentroid = skinned.centroid(),
              usedCachedJointRows = resolved.cached,
          )
      activeHandCount += 1
      if (resolved.cached) cachedHandCount += 1
    }

    if (activeHandCount == 0) {
      return null
    }
    frameIndex += 1
    return SpatialLiveHandSurfaceFrame(
        hands = frames,
        activeHandCount = activeHandCount,
        frameIndex = frameIndex,
        assetId = assetId,
        validJointCounts = validJointCounts,
        cachedHandCount = cachedHandCount,
    )
  }

  fun markerFields(): String =
      "handMeshRigAssetId=${surfaceMarkerToken(assetId)} handMeshRigSource=recorded-meta-openxr-hand-rig " +
          "handMeshRigProvider=XR_EXT_hand_tracking handMeshRowOrder=openxr-left-right " +
          "handMeshPairing=asset-handedness handMeshOrientationCorrection=none " +
          "handMeshWorldAnchorCorrection=false handMeshSkinning=cpu-linear-blend-four-influences " +
          "handMeshCoordinateAnchor=triangle-index-plus-barycentric " +
          "handMeshVertexCountPerHand=${hands[0].vertexCount} " +
          "handMeshTriangleCountPerHand=${hands[0].triangleCount} " +
          "handMeshBindJointCountPerHand=${hands[0].bindJointCount}"

  private fun resolveRows(source: FloatArray, handIndex: Int): ResolvedHandRows {
    val sourceOffset = handIndex * HAND_ROW_FLOAT_COUNT
    val validJointCount = countUsableJoints(source, sourceOffset)
    if (validJointCount >= MIN_USABLE_JOINT_COUNT &&
        hasUsableJoint(source, sourceOffset, OPENXR_PALM_INDEX) &&
        hasUsableJoint(source, sourceOffset, OPENXR_WRIST_INDEX)) {
      source.copyInto(
          cachedRows[handIndex],
          destinationOffset = 0,
          startIndex = sourceOffset,
          endIndex = sourceOffset + HAND_ROW_FLOAT_COUNT,
      )
      cachedRowsReady[handIndex] = true
      cachedRowsMissCount[handIndex] = 0
      return ResolvedHandRows(cachedRows[handIndex], validJointCount, cached = false)
    }
    if (cachedRowsReady[handIndex] && cachedRowsMissCount[handIndex] < ROW_CACHE_HOLD_FRAMES) {
      cachedRowsMissCount[handIndex] += 1
      return ResolvedHandRows(cachedRows[handIndex], validJointCount, cached = true)
    }
    cachedRowsReady[handIndex] = false
    cachedRowsMissCount[handIndex] = 0
    return ResolvedHandRows(null, validJointCount, cached = false)
  }

  companion object {
    const val ASSET_ROOT = "spatial-ecs-replay"

    fun load(context: Context): SpatialLiveSkinnedHandSurface {
      val assets = context.applicationContext.assets
      val manifest =
          assets.open("$ASSET_ROOT/spatial-ecs-replay-manifest.json").use {
            it.readBytes().toString(Charsets.UTF_8)
          }
      val assetId =
          Regex("\"asset_id\"\\s*:\\s*\"([^\"]+)\"")
              .find(manifest)
              ?.groupValues
              ?.getOrNull(1)
              ?: "unknown"
      return SpatialLiveSkinnedHandSurface(
          hands =
              arrayOf(
                  SpatialSkinningHand.load(
                      assets,
                      ASSET_ROOT,
                      "left",
                      "recorded-meta-quest-hand",
                  ),
                  SpatialSkinningHand.load(
                      assets,
                      ASSET_ROOT,
                      "right",
                      "recorded-meta-quest-right-hand",
                  ),
              ),
          assetId = assetId,
      )
    }
  }
}

internal data class SpatialLiveHandSurfaceFrame(
    val hands: Array<SpatialLiveHandSurfaceHandFrame?>,
    val activeHandCount: Int,
    val frameIndex: Int,
    val assetId: String,
    val validJointCounts: IntArray,
    val cachedHandCount: Int,
) {
  val particleCount: Int
    get() = hands.sumOf { it?.positions?.size ?: 0 }

  fun markerFields(): String =
      "liveCpuSkinnedMesh=true replayFallbackActive=false customGpuSkinning=false " +
          "surfaceAnchors=triangle-barycentric activeHandCount=$activeHandCount " +
          "liveSurfaceParticleCount=$particleCount liveSurfaceFrameIndex=$frameIndex " +
          "leftValidJointCount=${validJointCounts.getOrElse(0) { 0 }} " +
          "rightValidJointCount=${validJointCounts.getOrElse(1) { 0 }} " +
          "cachedHandCount=$cachedHandCount assetId=${surfaceMarkerToken(assetId)}"
}

internal data class SpatialLiveHandSurfaceHandFrame(
    val handedness: String,
    val positions: Array<Vector3>,
    val tangents: Array<Vector3>,
    val bitangents: Array<Vector3>,
    val normals: Array<Vector3>,
    val meshCentroid: Vector3?,
    val usedCachedJointRows: Boolean,
)

private data class ResolvedHandRows(
    val rows: FloatArray?,
    val validJointCount: Int,
    val cached: Boolean,
)

private class SpatialSkinningHand(
    val handedness: String,
    val vertexCount: Int,
    val triangleCount: Int,
    val bindJointCount: Int,
    private val bindVertices: FloatArray,
    private val bindNormals: FloatArray,
    private val triangles: IntArray,
    private val jointIndices: IntArray,
    private val jointWeights: FloatArray,
    private val bindJointPoses: Array<SpatialSkinningPose>,
    private val bindJointSources: IntArray,
    private val sampleSets: Map<Int, SpatialSkinningSampleSet>,
) {
  fun sampleSetFor(requestedCount: Int): SpatialSkinningSampleSet =
      if (requestedCount <= 512) sampleSets.getValue(512) else sampleSets.getValue(1024)

  fun skin(jointRows: FloatArray): SpatialSkinnedHandFrame? {
    val currentPoses = currentBindJointPoses(jointRows) ?: return null
    val positions = Array(vertexCount) { Vector3(0.0f) }
    val normals = Array(vertexCount) { Vector3(0.0f, 0.0f, 1.0f) }
    for (vertexIndex in 0 until vertexCount) {
      val bindPosition = bindVertex(vertexIndex)
      val bindNormal = bindNormal(vertexIndex)
      var position = Vector3(0.0f)
      var normal = Vector3(0.0f)
      var totalWeight = 0.0f
      val jointBase = vertexIndex * MAX_VERTEX_INFLUENCES
      for (influence in 0 until MAX_VERTEX_INFLUENCES) {
        val weight = jointWeights[jointBase + influence]
        if (weight <= 0.0f) continue
        val jointIndex = jointIndices[jointBase + influence]
        if (jointIndex !in bindJointPoses.indices || jointIndex !in currentPoses.indices) continue
        val bindPose = bindJointPoses[jointIndex]
        val currentPose = currentPoses[jointIndex]
        position += currentPose.transformPoint(bindPose.inverseTransformPoint(bindPosition)) * weight
        normal += currentPose.transformVector(bindPose.inverseTransformVector(bindNormal)) * weight
        totalWeight += weight
      }
      if (totalWeight > 0.0f) {
        positions[vertexIndex] = position * (1.0f / totalWeight)
        normals[vertexIndex] =
            (normal * (1.0f / totalWeight))
                .normalizedOr(bindNormal.normalizedOr(Vector3(0.0f, 0.0f, 1.0f)))
      } else {
        positions[vertexIndex] = bindPosition
        normals[vertexIndex] = bindNormal.normalizedOr(Vector3(0.0f, 0.0f, 1.0f))
      }
    }
    return SpatialSkinnedHandFrame(positions, normals)
  }

  fun sample(
      skinned: SpatialSkinnedHandFrame,
      sampleSet: SpatialSkinningSampleSet,
      sampleIndex: Int,
  ): SpatialSkinningSample {
    val triangleIndex = sampleSet.coordinateTriangles[sampleIndex]
    val triangleBase = triangleIndex * 3
    val ia = triangles[triangleBase]
    val ib = triangles[triangleBase + 1]
    val ic = triangles[triangleBase + 2]
    val a = skinned.positions[ia]
    val b = skinned.positions[ib]
    val c = skinned.positions[ic]
    val baryBase = sampleIndex * 3
    val ba = sampleSet.barycentric[baryBase]
    val bb = sampleSet.barycentric[baryBase + 1]
    val bc = sampleSet.barycentric[baryBase + 2]
    val position = a * ba + b * bb + c * bc
    val tangent = (b - a).normalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val triangleNormal =
        (b - a).cross(c - a).normalizedOr(Vector3(0.0f, 0.0f, 1.0f))
    val normal =
        (skinned.normals[ia] * ba + skinned.normals[ib] * bb + skinned.normals[ic] * bc)
            .normalizedOr(triangleNormal)
    val bitangent = normal.cross(tangent).normalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    return SpatialSkinningSample(position, tangent, bitangent, normal)
  }

  private fun currentBindJointPoses(jointRows: FloatArray): Array<SpatialSkinningPose>? {
    val current = arrayOfNulls<SpatialSkinningPose>(bindJointCount)
    for (bindJointIndex in 0 until bindJointCount) {
      val sourceBase = bindJointIndex * 4
      val kind = bindJointSources[sourceBase]
      current[bindJointIndex] =
          if (kind == 0) {
            runtimePose(jointRows, bindJointSources[sourceBase + 1])
          } else {
            val tipLength = tipLength(jointRows, bindJointSources[sourceBase + 2])
            val parent = runtimePose(jointRows, bindJointSources[sourceBase + 3])
            SpatialSkinningPose(
                parent.t + parent.q.rotate(Vector3(0.0f, 0.0f, -tipLength)),
                parent.q,
            )
          }
    }
    @Suppress("UNCHECKED_CAST")
    return current as Array<SpatialSkinningPose>
  }

  private fun runtimePose(jointRows: FloatArray, runtimeIndex: Int): SpatialSkinningPose =
      openXrPose(jointRows, openXrJointForRuntime(runtimeIndex))

  private fun tipLength(jointRows: FloatArray, tipIndex: Int): Float {
    val pair = TIP_OPENXR_PAIRS.getOrNull(tipIndex) ?: return 0.0f
    return (openXrPosition(jointRows, pair.second) - openXrPosition(jointRows, pair.first))
        .length()
        .coerceAtLeast(0.0f)
  }

  private fun openXrPose(jointRows: FloatArray, openXrIndex: Int): SpatialSkinningPose {
    val base = openXrIndex * SpatialLiveHandJointBridge.FLOATS_PER_ROW
    return SpatialSkinningPose(
        Vector3(jointRows[base], jointRows[base + 1], jointRows[base + 2]),
        SpatialSkinningQuat(
                jointRows[base + 8],
                jointRows[base + 9],
                jointRows[base + 10],
                jointRows[base + 11],
            )
            .normalized(),
    )
  }

  private fun openXrPosition(jointRows: FloatArray, openXrIndex: Int): Vector3 {
    val base = openXrIndex * SpatialLiveHandJointBridge.FLOATS_PER_ROW
    return Vector3(jointRows[base], jointRows[base + 1], jointRows[base + 2])
  }

  private fun bindVertex(index: Int): Vector3 {
    val base = index * 3
    return Vector3(bindVertices[base], bindVertices[base + 1], bindVertices[base + 2])
  }

  private fun bindNormal(index: Int): Vector3 {
    val base = index * 3
    return Vector3(bindNormals[base], bindNormals[base + 1], bindNormals[base + 2])
  }

  companion object {
    fun load(
        assets: AssetManager,
        root: String,
        handedness: String,
        prefix: String,
    ): SpatialSkinningHand {
      val bindVertices = assets.readSpatialFloatArray("$root/$prefix-skinning-bind-vertices.f32.bin")
      val bindNormals = assets.readSpatialFloatArray("$root/$prefix-skinning-bind-normals.f32.bin")
      val triangles = assets.readSpatialIntArray("$root/$prefix-mesh-triangles.u32.bin")
      val jointIndices =
          assets.readSpatialIntArray("$root/$prefix-skinning-vertex-joint-indices.u32.bin")
      val jointWeights =
          assets.readSpatialFloatArray("$root/$prefix-skinning-vertex-joint-weights.f32.bin")
      val bindJointPoseRows =
          assets.readSpatialFloatArray("$root/$prefix-skinning-bind-joint-poses.f32.bin")
      val bindJointSources =
          assets.readSpatialIntArray("$root/$prefix-skinning-bind-joint-sources.u32.bin")
      val vertexCount = bindVertices.size / 3
      val bindJointCount = bindJointPoseRows.size / 7
      require(bindNormals.size == bindVertices.size) { "bind normals do not match vertices: $prefix" }
      require(jointIndices.size == vertexCount * MAX_VERTEX_INFLUENCES) {
        "joint indices do not match vertices: $prefix"
      }
      require(jointWeights.size == vertexCount * MAX_VERTEX_INFLUENCES) {
        "joint weights do not match vertices: $prefix"
      }
      require(bindJointSources.size == bindJointCount * 4) {
        "bind joint sources do not match bind poses: $prefix"
      }
      return SpatialSkinningHand(
          handedness = handedness,
          vertexCount = vertexCount,
          triangleCount = triangles.size / 3,
          bindJointCount = bindJointCount,
          bindVertices = bindVertices,
          bindNormals = bindNormals,
          triangles = triangles,
          jointIndices = jointIndices,
          jointWeights = jointWeights,
          bindJointPoses = parseBindJointPoses(bindJointPoseRows),
          bindJointSources = bindJointSources,
          sampleSets =
              mapOf(
                  512 to SpatialSkinningSampleSet.load(assets, root, prefix, 512),
                  1024 to SpatialSkinningSampleSet.load(assets, root, prefix, 1024),
              ),
      )
    }

    private fun parseBindJointPoses(rows: FloatArray): Array<SpatialSkinningPose> =
        Array(rows.size / 7) { index ->
          val base = index * 7
          SpatialSkinningPose(
              Vector3(rows[base], rows[base + 1], rows[base + 2]),
              SpatialSkinningQuat(
                      rows[base + 3],
                      rows[base + 4],
                      rows[base + 5],
                      rows[base + 6],
                  )
                  .normalized(),
          )
        }
  }
}

private data class SpatialSkinnedHandFrame(
    val positions: Array<Vector3>,
    val normals: Array<Vector3>,
) {
  fun centroid(): Vector3? {
    if (positions.isEmpty()) return null
    var sum = Vector3(0.0f)
    positions.forEach { sum += it }
    return sum * (1.0f / positions.size.toFloat())
  }
}

private data class SpatialSkinningSample(
    val position: Vector3,
    val tangent: Vector3,
    val bitangent: Vector3,
    val normal: Vector3,
)

private data class SpatialSkinningSampleSet(
    val sampleCount: Int,
    val coordinateTriangles: IntArray,
    val barycentric: FloatArray,
) {
  companion object {
    fun load(
        assets: AssetManager,
        root: String,
        prefix: String,
        sampleCount: Int,
    ): SpatialSkinningSampleSet {
      val triangles =
          assets.readSpatialIntArray(
              "$root/$prefix-samples-$sampleCount-coordinate-triangles.u32.bin"
          )
      val barycentric =
          assets.readSpatialFloatArray(
              "$root/$prefix-samples-$sampleCount-coordinate-barycentric.f32.bin"
          )
      require(triangles.size == sampleCount) { "sample triangle count mismatch: $prefix" }
      require(barycentric.size == sampleCount * 3) { "sample barycentric count mismatch: $prefix" }
      return SpatialSkinningSampleSet(sampleCount, triangles, barycentric)
    }
  }
}

private data class SpatialSkinningPose(val t: Vector3, val q: SpatialSkinningQuat) {
  fun transformPoint(point: Vector3): Vector3 = q.rotate(point) + t
  fun inverseTransformPoint(point: Vector3): Vector3 = q.inverse().rotate(point - t)
  fun transformVector(vector: Vector3): Vector3 = q.rotate(vector)
  fun inverseTransformVector(vector: Vector3): Vector3 = q.inverse().rotate(vector)
}

private data class SpatialSkinningQuat(val x: Float, val y: Float, val z: Float, val w: Float) {
  fun normalized(): SpatialSkinningQuat {
    val lengthSquared = x * x + y * y + z * z + w * w
    if (lengthSquared <= 1.0e-12f || !lengthSquared.isFinite()) {
      return SpatialSkinningQuat(0.0f, 0.0f, 0.0f, 1.0f)
    }
    val inverseLength = 1.0f / sqrt(lengthSquared)
    return SpatialSkinningQuat(
        x * inverseLength,
        y * inverseLength,
        z * inverseLength,
        w * inverseLength,
    )
  }

  fun inverse(): SpatialSkinningQuat {
    val q = normalized()
    return SpatialSkinningQuat(-q.x, -q.y, -q.z, q.w)
  }

  fun rotate(vector: Vector3): Vector3 {
    val q = normalized()
    val qVector = Vector3(q.x, q.y, q.z)
    val uv = qVector.cross(vector)
    val uuv = qVector.cross(uv)
    return vector + uv * (2.0f * q.w) + uuv * 2.0f
  }
}

private fun countUsableJoints(rows: FloatArray, handOffset: Int): Int {
  var count = 0
  for (jointIndex in 0 until JOINT_COUNT_PER_HAND) {
    if (hasUsableJoint(rows, handOffset, jointIndex)) count += 1
  }
  return count
}

private fun hasUsableJoint(rows: FloatArray, handOffset: Int, jointIndex: Int): Boolean {
  val base = handOffset + jointIndex * SpatialLiveHandJointBridge.FLOATS_PER_ROW
  val x = rows.getOrNull(base) ?: return false
  val y = rows.getOrNull(base + 1) ?: return false
  val z = rows.getOrNull(base + 2) ?: return false
  val qx = rows.getOrNull(base + 8) ?: return false
  val qy = rows.getOrNull(base + 9) ?: return false
  val qz = rows.getOrNull(base + 10) ?: return false
  val qw = rows.getOrNull(base + 11) ?: return false
  return x.isFinite() &&
      y.isFinite() &&
      z.isFinite() &&
      qx.isFinite() &&
      qy.isFinite() &&
      qz.isFinite() &&
      qw.isFinite() &&
      x * x + y * y + z * z > 1.0e-8f &&
      qx * qx + qy * qy + qz * qz + qw * qw > 1.0e-8f
}

private fun openXrJointForRuntime(runtimeIndex: Int): Int =
    when (runtimeIndex) {
      0 -> 0
      1 -> 1
      2 -> 2
      3 -> 3
      4 -> 4
      5 -> 6
      6 -> 7
      7 -> 8
      8 -> 9
      9 -> 11
      10 -> 12
      11 -> 13
      12 -> 14
      13 -> 16
      14 -> 17
      15 -> 18
      16 -> 19
      17 -> 21
      18 -> 22
      19 -> 23
      else -> 24
    }

private fun AssetManager.readSpatialFloatArray(path: String): FloatArray {
  val bytes = open(path).use { it.readBytes() }
  require(bytes.size % 4 == 0) { "float asset length is not divisible by four: $path" }
  val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  return FloatArray(bytes.size / 4) { buffer.getFloat() }
}

private fun AssetManager.readSpatialIntArray(path: String): IntArray {
  val bytes = open(path).use { it.readBytes() }
  require(bytes.size % 4 == 0) { "int asset length is not divisible by four: $path" }
  val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  return IntArray(bytes.size / 4) { buffer.getInt() }
}

private fun splitCounts(totalCount: Int, handCount: Int): IntArray {
  val base = totalCount / handCount
  val remainder = totalCount % handCount
  return IntArray(handCount) { index -> base + if (index < remainder) 1 else 0 }
}

private fun scaledIndex(index: Int, count: Int, sampleCount: Int): Int =
    if (count <= 1) 0
    else
        ((index.toLong() * sampleCount.toLong()) / count.toLong())
            .toInt()
            .coerceIn(0, sampleCount - 1)

private fun Vector3.normalizedOrSurface(fallback: Vector3): Vector3 {
  val lengthSquared = x * x + y * y + z * z
  if (lengthSquared <= 1.0e-8f) return fallback
  val inverseLength = 1.0f / sqrt(lengthSquared)
  return Vector3(x * inverseLength, y * inverseLength, z * inverseLength)
}

private fun Vector3.normalizedOr(fallback: Vector3): Vector3 = normalizedOrSurface(fallback)

private fun surfaceMarkerToken(value: String): String =
    value.trim().lowercase().replace(Regex("[^a-z0-9_.:-]+"), "-").ifBlank { "none" }

private const val HAND_COUNT = 2
private const val JOINT_COUNT_PER_HAND = 26
private const val HAND_ROW_FLOAT_COUNT =
    JOINT_COUNT_PER_HAND * SpatialLiveHandJointBridge.FLOATS_PER_ROW
private const val MIN_USABLE_JOINT_COUNT = 21
private const val ROW_CACHE_HOLD_FRAMES = 12
private const val MAX_TOTAL_SAMPLE_COUNT = 2048
private const val MAX_VERTEX_INFLUENCES = 4
private const val OPENXR_PALM_INDEX = 0
private const val OPENXR_WRIST_INDEX = 1
private val TIP_OPENXR_PAIRS = arrayOf(4 to 5, 9 to 10, 14 to 15, 19 to 20, 24 to 25)
