package io.github.mesmerprism.rustyquest.spatial_vr_strobe

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VrStrobeCarrierGeometryTest {
  @Test
  fun defaultCarrierIsTheFullViewerFacingHemisphere() {
    val shape = VrStrobeCarrierShapeState()
    val rim = VrStrobeCarrierGeometry.bendPoint(VrStrobeCarrierGeometry.RADIUS_METERS, 0f, shape)

    assertTrue(shape.curvedMode)
    assertEquals(1f, shape.concavity)
    assertEquals(VrStrobeCarrierGeometry.RADIUS_METERS, rim.z, 0.0001f)
    assertEquals(180f, VrStrobeConcavityPolicy.polarAngleRadians(shape.concavity) * 360f / kotlin.math.PI.toFloat(), 0.001f)
  }

  @Test
  fun planarDiscHasBoundedRadialSubdivisionAndCompleteTriangles() {
    val mesh = VrStrobeCarrierGeometry.planarDisc()

    assertEquals(VrStrobeCarrierGeometry.VERTEX_COUNT * 3, mesh.positions.size)
    assertEquals(VrStrobeCarrierGeometry.VERTEX_COUNT * 3, mesh.normals.size)
    assertEquals(VrStrobeCarrierGeometry.VERTEX_COUNT * 2, mesh.uvs.size)
    assertEquals(VrStrobeCarrierGeometry.VERTEX_COUNT, mesh.colors.size)
    assertEquals(VrStrobeCarrierGeometry.TRIANGLE_COUNT * 3, mesh.indices.size)
    assertTrue(mesh.indices.all { it in 0 until VrStrobeCarrierGeometry.VERTEX_COUNT })
    assertEquals(0f, mesh.positions[0])
    assertEquals(0f, mesh.positions[1])
    assertEquals(32, VrStrobeCarrierGeometry.RADIAL_RINGS)
    assertEquals(96, VrStrobeCarrierGeometry.ANGULAR_SEGMENTS)
    assertEquals(3_073, VrStrobeCarrierGeometry.VERTEX_COUNT)
    assertEquals(6_048, VrStrobeCarrierGeometry.TRIANGLE_COUNT)
  }

  @Test
  fun planarDiscFrontFaceWindingPointsTowardTheViewer() {
    val mesh = VrStrobeCarrierGeometry.planarDisc()
    val a = mesh.indices[0] * 3
    val b = mesh.indices[1] * 3
    val c = mesh.indices[2] * 3
    val abX = mesh.positions[b] - mesh.positions[a]
    val abY = mesh.positions[b + 1] - mesh.positions[a + 1]
    val acX = mesh.positions[c] - mesh.positions[a]
    val acY = mesh.positions[c + 1] - mesh.positions[a + 1]
    val crossZ = abX * acY - abY * acX

    assertTrue(crossZ > 0f)
  }

  @Test
  fun curvedCarrierBendsBothAxesIntoAViewerFacingSphericalBowl() {
    val shape = VrStrobeCarrierShapeState(curvedMode = true, concavity = 1f)
    val radius = VrStrobeCarrierGeometry.RADIUS_METERS
    val left = VrStrobeCarrierGeometry.bendPoint(-radius, 0f, shape)
    val center = VrStrobeCarrierGeometry.bendPoint(0f, 0f, shape)
    val right = VrStrobeCarrierGeometry.bendPoint(radius, 0f, shape)
    val top = VrStrobeCarrierGeometry.bendPoint(0f, radius, shape)
    val bottom = VrStrobeCarrierGeometry.bendPoint(0f, -radius, shape)

    assertEquals(0f, center.x, 0.0001f)
    assertEquals(0f, center.y, 0.0001f)
    assertEquals(0f, center.z, 0.0001f)
    assertEquals(abs(left.x), abs(right.x), 0.0001f)
    assertEquals(left.z, right.z, 0.0001f)
    assertEquals(abs(top.y), abs(bottom.y), 0.0001f)
    assertEquals(top.z, bottom.z, 0.0001f)
    assertEquals(right.z, top.z, 0.0001f)
    assertEquals(radius, right.z, 0.0001f)
    assertTrue(right.z > 0f)
  }

  @Test
  fun flatCarrierRemainsAnUnwarpedCircle() {
    val flat = VrStrobeCarrierGeometry.bendPoint(
        0.4f,
        -0.7f,
        VrStrobeCarrierShapeState(curvedMode = false, concavity = 1f),
    )

    assertEquals(0.4f, flat.x)
    assertEquals(-0.7f, flat.y)
    assertEquals(0f, flat.z)
  }

  @Test
  fun analyticNormalsFaceTheViewerForFlatAndCurvedCarriers() {
    val flatShape = VrStrobeCarrierShapeState(curvedMode = false)
    val flatPoint = VrStrobeCarrierGeometry.bendPoint(0.4f, -0.7f, flatShape)
    val flatNormal = VrStrobeCarrierGeometry.viewerFacingNormal(flatPoint, flatShape)
    assertEquals(0f, flatNormal.x)
    assertEquals(0f, flatNormal.y)
    assertEquals(1f, flatNormal.z)

    val curvedShape = VrStrobeCarrierShapeState(curvedMode = true, concavity = 1f)
    val curvedPoint =
        VrStrobeCarrierGeometry.bendPoint(VrStrobeCarrierGeometry.RADIUS_METERS, 0f, curvedShape)
    val curvedNormal = VrStrobeCarrierGeometry.viewerFacingNormal(curvedPoint, curvedShape)
    assertTrue(curvedNormal.x < -0.999f)
    assertEquals(0f, curvedNormal.y, 0.0001f)
    assertEquals(0f, curvedNormal.z, 0.0001f)
  }

  @Test
  fun joystickUpIncreasesConcavityWithinBounds() {
    assertTrue(VrStrobeConcavityPolicy.apply(0.5f, -1f, 0.05f) > 0.5f)
    assertEquals(1f, VrStrobeConcavityPolicy.apply(1f, -1f, 0.05f))
    assertEquals(0f, VrStrobeConcavityPolicy.apply(0f, 1f, 0.05f))
  }
}
