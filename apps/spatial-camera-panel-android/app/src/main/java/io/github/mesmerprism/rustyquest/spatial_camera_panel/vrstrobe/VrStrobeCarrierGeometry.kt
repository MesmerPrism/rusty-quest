package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class VrStrobeCarrierShapeState(
    val curvedMode: Boolean = true,
    val concavity: Float = VrStrobeConcavityPolicy.DEFAULT,
) {
  fun sanitized(): VrStrobeCarrierShapeState =
      copy(concavity = concavity.coerceIn(VrStrobeConcavityPolicy.MIN, VrStrobeConcavityPolicy.MAX))
}

internal object VrStrobeConcavityPolicy {
  const val DEFAULT = 1.0f
  const val MIN = 0f
  const val MAX = 1f
  const val RATE_PER_SECOND = 0.65f
  const val MAX_POLAR_ANGLE_RADIANS = 1.5707964f

  fun apply(current: Float, stickY: Float, deltaSeconds: Float): Float =
      (current -
              stickY.coerceIn(-1f, 1f) *
                  RATE_PER_SECOND *
                  deltaSeconds.coerceIn(0f, 0.05f))
          .coerceIn(MIN, MAX)

  fun polarAngleRadians(concavity: Float): Float =
      MAX_POLAR_ANGLE_RADIANS * concavity.coerceIn(MIN, MAX)
}

internal data class VrStrobeCarrierPoint(val x: Float, val y: Float, val z: Float)

internal data class VrStrobeCarrierMeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val colors: IntArray,
    val indices: IntArray,
)

/** A radial disc whose original XY coordinates are bent by the vertex shader. */
internal object VrStrobeCarrierGeometry {
  // Enlarged from the original 1.42 m reference carrier. Runtime distance is
  // independently controller-adjustable and now begins at its 4.00 m maximum.
  const val RADIUS_METERS = 2.84f
  const val RADIAL_RINGS = 16
  const val ANGULAR_SEGMENTS = 48
  const val VERTEX_COUNT = 1 + RADIAL_RINGS * ANGULAR_SEGMENTS
  const val TRIANGLE_COUNT =
      ANGULAR_SEGMENTS + (RADIAL_RINGS - 1) * ANGULAR_SEGMENTS * 2

  fun planarDisc(): VrStrobeCarrierMeshData {
    val positions = FloatArray(VERTEX_COUNT * 3)
    val normals = FloatArray(VERTEX_COUNT * 3)
    val uvs = FloatArray(VERTEX_COUNT * 2)
    val colors = IntArray(VERTEX_COUNT) { -1 }
    val indices = IntArray(TRIANGLE_COUNT * 3)

    normals[2] = 1f
    uvs[0] = 0.5f
    uvs[1] = 0.5f
    for (ring in 1..RADIAL_RINGS) {
      val radialFraction = ring.toFloat() / RADIAL_RINGS.toFloat()
      val radius = RADIUS_METERS * radialFraction
      repeat(ANGULAR_SEGMENTS) { segment ->
        val angle = 2f * PI.toFloat() * segment.toFloat() / ANGULAR_SEGMENTS.toFloat()
        val vertex = 1 + (ring - 1) * ANGULAR_SEGMENTS + segment
        val x = cos(angle) * radius
        val y = sin(angle) * radius
        val positionOffset = vertex * 3
        positions[positionOffset] = x
        positions[positionOffset + 1] = y
        normals[positionOffset + 2] = 1f
        val uvOffset = vertex * 2
        uvs[uvOffset] = x / (RADIUS_METERS * 2f) + 0.5f
        uvs[uvOffset + 1] = y / (RADIUS_METERS * 2f) + 0.5f
      }
    }

    repeat(ANGULAR_SEGMENTS) { segment ->
      val next = (segment + 1) % ANGULAR_SEGMENTS
      val index = segment * 3
      indices[index] = 0
      indices[index + 1] = 1 + segment
      indices[index + 2] = 1 + next
    }
    var index = ANGULAR_SEGMENTS * 3
    for (ring in 1 until RADIAL_RINGS) {
      val innerBase = 1 + (ring - 1) * ANGULAR_SEGMENTS
      val outerBase = 1 + ring * ANGULAR_SEGMENTS
      repeat(ANGULAR_SEGMENTS) { segment ->
        val next = (segment + 1) % ANGULAR_SEGMENTS
        val inner = innerBase + segment
        val innerNext = innerBase + next
        val outer = outerBase + segment
        val outerNext = outerBase + next
        indices[index++] = inner
        indices[index++] = outer
        indices[index++] = outerNext
        indices[index++] = inner
        indices[index++] = outerNext
        indices[index++] = innerNext
      }
    }
    check(index == indices.size)
    return VrStrobeCarrierMeshData(positions, normals, uvs, colors, indices)
  }

  /** CPU oracle for the shader's flat-disc to user-facing hemispherical-bowl mapping. */
  fun bendPoint(
      x: Float,
      y: Float,
      shape: VrStrobeCarrierShapeState,
  ): VrStrobeCarrierPoint {
    val sanitized = shape.sanitized()
    val polarAngle =
        if (sanitized.curvedMode) {
          VrStrobeConcavityPolicy.polarAngleRadians(sanitized.concavity)
        } else {
          0f
        }
    val flatRadius = sqrt(x * x + y * y)
    if (polarAngle <= 0.0001f || flatRadius <= 0.0001f) {
      return VrStrobeCarrierPoint(x, y, 0f)
    }
    val normalizedRadius = (flatRadius / RADIUS_METERS).coerceIn(0f, 1f)
    val sphereRadius = RADIUS_METERS / sin(polarAngle)
    val pointAngle = normalizedRadius * polarAngle
    val mappedRadius = sphereRadius * sin(pointAngle)
    val radialScale = mappedRadius / flatRadius
    return VrStrobeCarrierPoint(
        x = x * radialScale,
        y = y * radialScale,
        z = sphereRadius * (1f - cos(pointAngle)),
    )
  }
}
