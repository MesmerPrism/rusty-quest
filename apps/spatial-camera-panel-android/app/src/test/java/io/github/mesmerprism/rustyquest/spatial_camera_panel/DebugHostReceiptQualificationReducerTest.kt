package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugHostReceiptQualificationReducerTest {
  @Test
  fun completeTypedSnapshotProducesClosedTruthfulFacts() {
    val facts = DebugHostReceiptQualificationReducer.terminalFacts(snapshot())
    requireNotNull(facts)
    assertEquals(DebugHostReceiptContract.FACT_TYPES, facts.map { it.type })
    assertEquals("encrypted-offline-pack", facts[0].value)
    assertEquals("app-private-source", facts[1].value)
    assertEquals("present-retired", facts[8].value)
    assertTrue(facts.all { it.value.matches(Regex("^[a-z0-9][a-z0-9._-]{0,95}$")) })
  }

  @Test
  fun oneFramePidStyleOrDamagedStateCannotQualify() {
    assertNull(
        DebugHostReceiptQualificationReducer.terminalFacts(
            snapshot().copy(
                native =
                    snapshot().native.copy(
                        lastDecodedFrame = 1,
                        lastAdoptedFrame = 1,
                        distinctAdoptedFrames = 1,
                    )
            )
        )
    )
    assertNull(
        DebugHostReceiptQualificationReducer.terminalFacts(
            snapshot().copy(native = snapshot().native.copy(errorCode = -1))
        )
    )
    assertNull(DebugHostReceiptQualificationReducer.terminalFacts(snapshot().copy(source = "broker-rmanvid1")))
  }

  @Test
  fun preArmOrCrossSourceFramesCannotQualify() {
    assertNull(
        DebugHostReceiptQualificationReducer.terminalFacts(
            snapshot().copy(
                native = snapshot().native.copy(firstAdoptedFrame = 0, lastAdoptedFrame = 3)
            )
        )
    )
    assertNull(
        DebugHostReceiptQualificationReducer.terminalFacts(
            snapshot().copy(
                native = snapshot().native.copy(lastAdoptedFrame = 5)
            )
        )
    )
  }

  private fun snapshot() =
      SpatialLaunchQualificationTelemetry.Snapshot(
          source = SpatialImmersiveVideoSessionPolicy.CUSTOM_PROJECTION_SOURCE,
          cadence = "30",
          native =
              SpatialLaunchQualificationTelemetry.NativeSnapshot(
                  decoderStarted = true,
                  errorCode = 0,
                  firstDecodedFrame = 1,
                  lastDecodedFrame = 4,
                  lastImportSequence = 4,
                  firstTimestampNs = 1_000,
                  lastTimestampNs = 100_000_000,
                  width = 1920,
                  height = 960,
                  maxImages = 3,
                  fpsCap = 30,
                  firstAdoptedFrame = 1,
                  lastAdoptedFrame = 4,
                  lastPresentOrdinal = 6,
                  distinctAdoptedFrames = 4,
              ),
      )
}
