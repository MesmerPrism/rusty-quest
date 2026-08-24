package io.github.mesmerprism.rustyquest.spatial_camera_panel

/** Converts only a complete app-owned typed snapshot into the closed receipt vocabulary. */
internal object DebugHostReceiptQualificationReducer {
  fun terminalFacts(
      snapshot: SpatialLaunchQualificationTelemetry.Snapshot,
  ): List<DebugHostReceiptStore.Fact>? {
    val native = snapshot.native
    if (snapshot.source !in supportedSources || snapshot.cadence !in supportedCadences) return null
    if (!native.decoderStarted || native.errorCode != 0) return null
    if (native.firstDecodedFrame <= 0 || native.lastDecodedFrame <= native.firstDecodedFrame) return null
    if (native.lastImportSequence <= 0) return null
    if (native.firstTimestampNs <= 0 || native.lastTimestampNs <= native.firstTimestampNs) return null
    if (native.width !in 320..4096 || native.height !in 240..4096) return null
    if (native.maxImages !in 2..6 || native.fpsCap !in 1..90) return null
    if (native.firstAdoptedFrame < native.firstDecodedFrame) return null
    if (native.lastAdoptedFrame <= native.firstAdoptedFrame) return null
    if (native.lastAdoptedFrame > native.lastDecodedFrame) return null
    if (native.distinctAdoptedFrames < 2 || native.lastPresentOrdinal < 2) return null

    val grant =
        when (snapshot.source) {
          SpatialImmersiveVideoSessionPolicy.CUSTOM_PROJECTION_SOURCE -> "app-private-source"
          SpatialImmersiveVideoSessionPolicy.PLAIN_CUSTOM_PROJECTION_SOURCE ->
              "content-read-established"
          "app-private-file" -> "app-private-source"
          else -> return null
        }
    val cadence =
        when (snapshot.cadence) {
          "30" -> "fps-30-gate-timestamps-advance"
          "60" -> "fps-60-gate-timestamps-advance"
          "source" -> "source-mode-timestamps-advance"
          else -> return null
        }
    val values =
        listOf(
            snapshot.source,
            grant,
            "mediacodec-output-established",
            "bounded-${native.maxImages}",
            "${native.width}x${native.height}",
            "gpu-import-ready",
            "decoded-and-adopted",
            cadence,
            "present-retired",
            "none",
            "qualified",
        )
    return DebugHostReceiptContract.FACT_TYPES.zip(values) { type, value ->
      DebugHostReceiptStore.Fact(type, value)
    }
  }

  private val supportedSources =
      setOf(
          "app-private-file",
          SpatialImmersiveVideoSessionPolicy.CUSTOM_PROJECTION_SOURCE,
          SpatialImmersiveVideoSessionPolicy.PLAIN_CUSTOM_PROJECTION_SOURCE,
      )
  private val supportedCadences = setOf("30", "60", "source")
}
