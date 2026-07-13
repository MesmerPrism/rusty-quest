package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialSurfaceParticleRuntimeCoordinatorTest {
  private val probe = SpatialNativeInteropProbe("test", 1L, 2L, 3L)

  private fun request(
      startMask: Long,
      onNativeStart: () -> Unit = {},
      onParameterSubmit: () -> Unit = {},
  ): SpatialSurfaceParticleStartRequest =
      SpatialSurfaceParticleStartRequest(
          surfaceValid = { true },
          captureOpenXrProbe = { probe },
          startNative = {
            onNativeStart()
            startMask
          },
          carrier = { "test-carrier" },
          placementMarkerFields = { "placement=test" },
          stereoMarkerFields = { "stereo=test" },
          submitParameters = onParameterSubmit,
      )

  @Test
  fun nativeRejectedStartMaskCannotBecomeKotlinAppliedState() {
    var nativeStarts = 0
    var parameterSubmits = 0
    val markers = mutableListOf<String>()
    val coordinator =
        SpatialSurfaceParticleRuntimeCoordinator(
            SpatialSurfaceParticleRuntimeBindings(
                nativeSurfaceParticleLayerEnabled = { true },
                suppressionSource = { "adapter-lock-rejected" },
                privateRendererEnabled = { false },
                receiptLibraryLoaded = { true },
                receiptLibraryError = { "none" },
                launcherPanelVisible = { false },
                stopNative = {},
                marker = markers::add,
            )
        )

    coordinator.start(
        request(
            startMask = 1L,
            onNativeStart = { nativeStarts += 1 },
            onParameterSubmit = { parameterSubmits += 1 },
        )
    )

    assertEquals(1, nativeStarts)
    assertEquals(0, parameterSubmits)
    assertFalse(coordinator.particleLayerStarted)
    assertFalse(coordinator.nativeSurfaceStartRequested)
    assertEquals(1L, coordinator.lastNativeSurfaceStartMask)
    assertTrue(markers.any { it.contains("status=start-rejected") })
  }

  @Test
  fun appliedNativeStartIsRevokedWhenTheRuntimeLockDrifts() {
    var enabled = true
    var nativeStops = 0
    var parameterSubmits = 0
    val coordinator =
        SpatialSurfaceParticleRuntimeCoordinator(
            SpatialSurfaceParticleRuntimeBindings(
                nativeSurfaceParticleLayerEnabled = { enabled },
                suppressionSource = { "adapter-lock-rejected" },
                privateRendererEnabled = { false },
                receiptLibraryLoaded = { true },
                receiptLibraryError = { "none" },
                launcherPanelVisible = { false },
                stopNative = { nativeStops += 1 },
                marker = {},
            )
        )

    coordinator.start(
        request(
            startMask = SURFACE_PARTICLE_RENDER_THREAD_STARTED_BIT,
            onParameterSubmit = { parameterSubmits += 1 },
        )
    )
    assertTrue(coordinator.particleLayerStarted)
    assertEquals(1, parameterSubmits)

    enabled = false
    assertFalse(coordinator.reconcileAdapterAdmission("lock-drift-test"))
    assertEquals(1, nativeStops)
    assertFalse(coordinator.particleLayerStarted)
    assertFalse(coordinator.nativeSurfaceStartRequested)
  }

  @Test
  fun disabledAdmissionSuppressesParameterAndAliasEffects() {
    var nativeSubmits = 0
    var aliasSubmits = 0
    val markers = mutableListOf<String>()
    val coordinator =
        SpatialSurfaceParticleParameterCoordinator(
            SpatialSurfaceParticleParameterBindings(
                featureEnabled = { false },
                receiptLibraryLoaded = { true },
                workflowPanelVisible = { false },
                submitNativeParameters = {
                  nativeSubmits += 1
                  1L
                },
                resolveNativeAlias = { _, _, _ ->
                  aliasSubmits += 1
                  1L
                },
                marker = markers::add,
            )
        )

    coordinator.submit("test")
    coordinator.resolveAlias("test", "driver0", 0.5f, "profile")

    assertEquals(0, nativeSubmits)
    assertEquals(0, aliasSubmits)
    assertEquals(2, markers.count { it.contains("status=effect-suppressed") })
  }
}
