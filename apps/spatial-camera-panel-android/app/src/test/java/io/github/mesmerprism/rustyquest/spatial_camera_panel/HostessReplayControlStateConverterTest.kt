package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostessReplayControlStateConverterTest {
  @Test
  fun malformedUtf8InOtherwiseValidStringFailsClosed() {
    assertThrows(IllegalArgumentException::class.java) {
      HostessReplayControlStateConverter.export(
          malformedUtf8Inside(validState().toString(), "final")
      )
    }
  }

  @Test
  fun duplicateRootSchemaIncludingEscapeEquivalentFailsClosed() {
    val state = validState().toString()
    val damaged =
        "{\"\\u0073chema\":\"${HostessReplayControlStateConverter.INPUT_SCHEMA}\"," +
            state.drop(1)
    assertThrows(IllegalArgumentException::class.java) {
      HostessReplayControlStateConverter.export(damaged.toByteArray())
    }
  }

  @Test
  fun duplicateNumericControlFailsClosed() {
    val state = validState()
    val damaged =
        prependObjectMember(
            state.toString(),
            state.getJSONObject("projection"),
            "\"scale\":1.0",
        )
    assertThrows(IllegalArgumentException::class.java) {
      HostessReplayControlStateConverter.export(damaged)
    }
  }

  @Test
  fun duplicateNestedObjectKeyIncludingEscapeEquivalentFailsClosed() {
    val state = validState()
    val values =
        state.getJSONObject("control_transport").getJSONObject("values")
    val damaged =
        prependObjectMember(
            state.toString(),
            values,
            "\"\\u006fpaque-control\":0.5",
        )
    assertThrows(IllegalArgumentException::class.java) {
      HostessReplayControlStateConverter.export(damaged)
    }
  }

  @Test
  fun trailingContentFailsClosed() {
    val damaged = validState().toString().toByteArray() + byteArrayOf('x'.code.toByte())
    assertThrows(IllegalArgumentException::class.java) {
      HostessReplayControlStateConverter.export(damaged)
    }
  }

  @Test
  fun excessiveNestingFailsAtStrictByteIngress() {
    val state = validState().toString()
    val nestedArray = "[".repeat(65) + "0" + "]".repeat(65)
    val damaged = "{\"depth_probe\":$nestedArray,${state.drop(1)}".toByteArray()

    val error =
        assertThrows(IllegalArgumentException::class.java) {
          HostessReplayControlStateConverter.export(damaged)
        }

    assertTrue(error.message?.contains("nesting_too_deep") == true)
  }

  @Test
  fun integralMetadataUsesExactLongConversionAndRangeChecks() {
    val state = validState().toString()
    listOf(
            "0",
            "7e0",
            "9223372036854775807",
            "9.223372036854775807e18",
        )
        .forEach { literal ->
          HostessReplayControlStateConverter.export(
              replaceNumberField(state, "revision", literal)
          )
        }

    listOf(
            "9223372036854775808",
            "18446744073709551615",
            "18446744073709551616",
            "99999999999999999999999999999999999999999999999999",
            "7.0000000000000000001",
            "9.223372036854775808e18",
        )
        .forEach { literal ->
          assertThrows("should reject revision=$literal", IllegalArgumentException::class.java) {
            HostessReplayControlStateConverter.export(
                replaceNumberField(state, "revision", literal)
            )
          }
        }

    assertThrows(IllegalArgumentException::class.java) {
      HostessReplayControlStateConverter.export(
          replaceNumberField(state, "created_unix_ms", "9223372036854775808")
      )
    }
  }

  @Test
  fun authoritativeByteBoundsAcceptExactlyLimitAndRejectEmptyOrLimitPlusOne() {
    val valid = validState().toString().toByteArray(Charsets.UTF_8)
    val atLimit =
        valid + ByteArray(HostessReplayControlStateConverter.MAX_INPUT_BYTES - valid.size) { ' '.code.toByte() }
    HostessReplayControlStateConverter.export(atLimit)

    val emptyError =
        assertThrows(IllegalArgumentException::class.java) {
          HostessReplayControlStateConverter.export(byteArrayOf())
        }
    assertEquals("control_state_empty", emptyError.message)

    val oversizedError =
        assertThrows(IllegalArgumentException::class.java) {
          HostessReplayControlStateConverter.export(atLimit + ' '.code.toByte())
        }
    assertEquals("control_state_too_large", oversizedError.message)
  }

  @Test
  fun goldenHostessStateExportsSameEffectiveQuestControls() {
    val output = HostessReplayControlStateConverter.export(validState().toString().toByteArray())
    val profile = SpatialCameraControlProfileContract.parse(output)
    assertEquals("artifact-test", profile.profileId)
    assertEquals(7L, profile.revision)
    assertEquals(0.0f, profile.layerOverride)
    assertEquals(1.15f, profile.projectionScale)
    assertEquals(0.015f, profile.zoneCompositor.edgeInsetUv)
    assertEquals(0.14f, profile.zoneCompositor.maxInsetUv)
    assertEquals(0.68f, profile.zoneCompositor.processedMix)
    assertEquals(0.018f, profile.rgbChannelTransform.red.displacementStrengthUv)
    assertEquals(-0.13f, profile.rgbChannelTransform.blue.directionRateHz)
    assertEquals(true, profile.projectionSurfaceDisplacement.enabled)
    assertEquals(0.18f, profile.projectionSurfaceDisplacement.maxDisplacementMeters)
    assertEquals(ProjectionSurfaceTilingControls.off, profile.projectionSurfaceTiling)
    assertEquals(ProjectionInnerAlphaControls.off, profile.projectionInnerAlpha)
  }

  @Test
  fun additiveSurfaceFeatureUniformExportsTilingAndInnerAlphaControls() {
    val output =
        HostessReplayControlStateConverter.export(
            stateWithSurfaceFeatures().toString().toByteArray()
        )
    val profile = SpatialCameraControlProfileContract.parse(output)

    assertTrue(profile.projectionSurfaceTiling.enabled)
    assertEquals(
        ProjectionSurfaceTilingControls.topologyTiled,
        profile.projectionSurfaceTiling.topology,
    )
    assertEquals(0.08f, profile.projectionSurfaceTiling.gapNormalized)
    assertEquals(0.25f, profile.projectionSurfaceTiling.depthFlexibility)
    assertEquals(
        ProjectionSurfaceTilingControls.scopeCoreOnly,
        profile.projectionSurfaceTiling.scope,
    )
    assertTrue(profile.projectionInnerAlpha.enabled)
    assertEquals(ProjectionInnerAlphaControls.driverMax, profile.projectionInnerAlpha.driver)
    assertEquals(0.65f, profile.projectionInnerAlpha.amount)
    assertEquals(
        ProjectionInnerAlphaControls.stretchFollowProjection,
        profile.projectionInnerAlpha.stretchPolicy,
    )
    assertTrue(profile.projectionInnerAlpha.stretchObeysExactProjectionMask)
  }

  @Test
  fun additiveSurfaceFeatureUniformRejectsPrefixDriftAndUnsupportedAbi() {
    val prefixDrift = stateWithSurfaceFeatures()
    prefixDrift
        .getJSONObject("projection")
        .getJSONArray("surface_feature_uniform_f32")
        .put(4, 0.19)
    assertThrows(IllegalArgumentException::class.java) {
      HostessReplayControlStateConverter.export(prefixDrift.toString().toByteArray())
    }

    val unsupportedAbi = stateWithSurfaceFeatures()
    unsupportedAbi
        .getJSONObject("projection")
        .getJSONArray("surface_feature_uniform_f32")
        .put(30, 1.0)
    assertThrows(IllegalArgumentException::class.java) {
      HostessReplayControlStateConverter.export(unsupportedAbi.toString().toByteArray())
    }
  }

  @Test
  fun genuineV1FixtureMatchesEveryQuestFieldAndRejectsV2Envelope() {
    val v2Output =
        HostessReplayControlStateConverter.export(validState().toString().toByteArray())
    val v1 = validV1State()
    val v1Output = HostessReplayControlStateConverter.export(v1.toString().toByteArray())

    assertEquals(
        SpatialCameraControlProfileContract.parse(v2Output),
        SpatialCameraControlProfileContract.parse(v1Output),
    )
    assertEquals(
        JSONObject(v2Output.toString(Charsets.UTF_8)).getJSONObject("quest_controls").toString(),
        JSONObject(v1Output.toString(Charsets.UTF_8)).getJSONObject("quest_controls").toString(),
    )

    assertThrows(IllegalArgumentException::class.java) {
      HostessReplayControlStateConverter.export(
          validV1State()
              .put(
                  "control_transport",
                  JSONObject()
                      .put("transport_id", "forbidden-v1-envelope")
                      .put("capsule_sha256", "a".repeat(64))
                      .put("values", JSONObject()),
              )
              .toString()
              .toByteArray()
      )
    }
  }

  @Test
  fun damagedExpandedNonFiniteAndUnsupportedStatesFailClosed() {
    assertThrows(IllegalArgumentException::class.java) {
      HostessReplayControlStateConverter.export(
          validState().put("android_path", "/sdcard/x").toString().toByteArray()
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      HostessReplayControlStateConverter.export(
          validState()
              .getJSONObject("projection")
              .put("rgb_uniform_f32", JSONArray(List(23) { 0.0 }))
              .let { validState().put("projection", it).toString().toByteArray() }
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      val state = validState()
      state.getJSONObject("projection").getJSONArray("zone_uniform_f32").put(24, 9.0)
      HostessReplayControlStateConverter.export(state.toString().toByteArray())
    }
    for ((block, index, value) in
        listOf(
            Triple("zone_uniform_f32", 25, 0.5),
            Triple("zone_uniform_f32", 44, -1.0),
            Triple("zone_uniform_f32", 63, 3.0),
            Triple("zone_uniform_f32", 71, 5.0),
            Triple("rgb_uniform_f32", 0, 0.5),
            Triple("rgb_uniform_f32", 1, 3.0),
        )) {
      assertThrows(IllegalArgumentException::class.java) {
        val state = validState()
        state.getJSONObject("projection").getJSONArray(block).put(index, value)
        HostessReplayControlStateConverter.export(state.toString().toByteArray())
      }
    }
  }

  @Test
  fun portableIdsUseSharedTwoThroughSixtyFourLowercaseDomain() {
    for (id in listOf("a0", "a" + "0".repeat(63))) {
      val state = validState().put("state_id", id)
      state.getJSONObject("control_transport").put("transport_id", id)
      state.getJSONObject("control_transport").put("values", JSONObject().put(id, 0.25))
      HostessReplayControlStateConverter.export(state.toString().toByteArray())
    }
    for (id in listOf("a", "Mixed-case", "a" + "0".repeat(64))) {
      for (field in listOf("state", "transport", "control")) {
        assertThrows(IllegalArgumentException::class.java) {
          val state = validState()
          when (field) {
            "state" -> state.put("state_id", id)
            "transport" -> state.getJSONObject("control_transport").put("transport_id", id)
            else ->
                state
                    .getJSONObject("control_transport")
                    .put("values", JSONObject().put(id, 0.25))
          }
          HostessReplayControlStateConverter.export(state.toString().toByteArray())
        }
      }
    }
  }

  @Test
  fun cliBoundedReaderRejectsMetadataAndPostReadGrowth() {
    val directory = Files.createTempDirectory("quest-control-state-bounds")
    val path = directory.resolve("input.json")
    Files.write(path, ByteArray(HostessReplayControlStateConverter.MAX_INPUT_BYTES + 1))
    assertThrows(IllegalArgumentException::class.java) {
      HostessReplayControlStateCli.readBounded(path)
    }
    Files.write(path, byteArrayOf(1))
    assertThrows(IllegalArgumentException::class.java) {
      HostessReplayControlStateCli.readBounded(path) {
        ByteArray(HostessReplayControlStateConverter.MAX_INPUT_BYTES + 1)
      }
    }
  }

  @Test
  fun emptyAndWhitespaceLayerTokensFailAtHostessStateBoundary() {
    for (token in listOf("", "   ")) {
      val replayState = validState()
      replayState.getJSONObject("replay_layer").put("layer_token", token)
      assertThrows(IllegalArgumentException::class.java) {
        HostessReplayControlStateConverter.export(replayState.toString().toByteArray())
      }

      val previewState = validState().put("preview", validPreview().put("layer_token", token))
      assertThrows(IllegalArgumentException::class.java) {
        HostessReplayControlStateConverter.export(previewState.toString().toByteArray())
      }
    }
  }

  @Test
  fun productionCliWritesValidatedOutputWithoutLeavingPendingSibling() {
    val directory = Files.createTempDirectory("quest-control-profile-cli")
    val input = directory.resolve("input.replay-control-state.json")
    val output = directory.resolve("output.profile.json")
    Files.write(input, validState().toString().toByteArray())
    HostessReplayControlStateCli.main(arrayOf(input.toString(), output.toString()))
    assertEquals(
        "artifact-test",
        SpatialCameraControlProfileContract.parse(Files.readAllBytes(output)).profileId,
    )
    assertEquals(
        emptyList<String>(),
        Files.list(directory).use { paths ->
          paths
              .filter { it.fileName.toString().endsWith(".pending") }
              .map { it.fileName.toString() }
              .toList()
        },
    )
  }

  private fun validState(): JSONObject {
    val rgb = FloatArray(24)
    rgb[0] = 1f
    rgb[1] = 1f
    floatArrayOf(0f, 0.333333f, 0.666667f).copyInto(rgb, 4)
    floatArrayOf(0.11f, 0.17f, -0.13f).copyInto(rgb, 8)
    floatArrayOf(0.018f, 0.014f, 0.022f).copyInto(rgb, 12)
    floatArrayOf(1f, 1.05f, 0.95f).copyInto(rgb, 16)
    floatArrayOf(1f, 0.92f, 0.84f).copyInto(rgb, 20)
    val displacement = FloatArray(16)
    displacement[0] = 1f
    displacement[1] = 1f
    displacement[4] = 0.18f
    displacement[5] = 2f
    displacement[6] = 0.18f
    val zone = FloatArray(92)
    zone[24] = 1f
    zone[25] = 1f
    zone[27] = 0.68f
    zone[28] = 0.015f
    zone[29] = 0.14f
    zone[30] = 1.6f
    zone[31] = 2f
    fillBand(zone, true, 1f, 1f, 0f)
    fillBand(zone, false, 2f, 2f, 0f)
    return JSONObject()
        .put("schema", HostessReplayControlStateConverter.INPUT_SCHEMA)
        .put("state_id", "artifact-test")
        .put("revision", 7)
        .put("created_unix_ms", 12)
        .put(
            "control_transport",
            JSONObject()
                .put("transport_id", "opaque-transport")
                .put("capsule_sha256", "a".repeat(64))
                .put("values", JSONObject().put("opaque-control", 0.25)),
        )
        .put(
            "replay_layer",
            JSONObject().put("layer_token", "final").put("override_value", 0.0),
        )
        .put(
            "projection",
            JSONObject()
                .put("scale", 1.15)
                .put("rgb_uniform_f32", JSONArray(rgb.toList()))
                .put("displacement_uniform_f32", JSONArray(displacement.toList()))
                .put("displacement_enabled", true)
                .put("zone_uniform_f32", JSONArray(zone.toList())),
        )
  }

  private fun malformedUtf8Inside(document: String, marker: String): ByteArray {
    val bytes = document.toByteArray(Charsets.UTF_8)
    val offset = document.indexOf(marker)
    require(offset >= 0 && marker.length >= 2)
    bytes[offset] = 0xc3.toByte()
    bytes[offset + 1] = 0x28
    return bytes
  }

  private fun prependObjectMember(
      document: String,
      target: JSONObject,
      member: String,
  ): ByteArray {
    val original = target.toString()
    require(document.contains(original))
    return document
        .replaceFirst(original, "{$member,${original.drop(1)}")
        .toByteArray(Charsets.UTF_8)
  }

  private fun replaceNumberField(document: String, name: String, literal: String): ByteArray {
    val originalValue = JSONObject(document).get(name)
    val marker = "\"$name\":$originalValue"
    require(document.contains(marker))
    return document.replaceFirst(marker, "\"$name\":$literal").toByteArray(Charsets.UTF_8)
  }

  private fun validV1State(): JSONObject =
      JSONObject(validState().toString())
          .apply {
            put("schema", HostessReplayControlStateConverter.INPUT_V1_SCHEMA)
            remove("control_transport")
          }

  private fun stateWithSurfaceFeatures(): JSONObject {
    val state = validState()
    val projection = state.getJSONObject("projection")
    val displacement = projection.getJSONArray("displacement_uniform_f32")
    val values = FloatArray(32)
    for (index in 0 until 16) {
      values[index] = displacement.getDouble(index).toFloat()
    }
    values[16] = 1.0f
    values[17] = 1.0f
    values[18] = 0.08f
    values[19] = 0.25f
    values[20] = 1.0f
    values[21] = 1.0f
    values[22] = 4.0f
    values[23] = 0.0f
    values[24] = 0.55f
    values[25] = 0.1f
    values[26] = 0.65f
    values[27] = 0.0f
    values[28] = 1.0f
    values[29] = 7.0f
    values[30] = 2.0f
    values[31] = 0.0f
    projection.put("surface_feature_uniform_f32", JSONArray(values.toList()))
    return state
  }

  private fun validPreview(): JSONObject =
      JSONObject()
          .put("layer_token", "final")
          .put("effect_clock_speed", 1.0)
          .put("preview_target_hz", 90.0)
          .put("preview_mode", "stereo")
          .put("preview_eye", "left")
          .put("color_effect_phase_offset_turns", 0.0)
          .put("color_effect_rate_hz", 0.0)
          .put("buffer_footprint_scale", 1.0)

  private fun fillBand(
      zone: FloatArray,
      inner: Boolean,
      signal: Float,
      application: Float,
      source: Float,
  ) {
    val threshold = if (inner) 36 else 48
    val dynamics = if (inner) 40 else 52
    val shape = if (inner) 44 else 56
    val strength = if (inner) 60 else 76
    val amplitude = if (inner) 64 else 80
    val hz = if (inner) 68 else 84
    val phase = if (inner) 72 else 88
    floatArrayOf(0.5f, 0.5f, 0.5f).copyInto(zone, threshold)
    zone[threshold + 3] = 0.12f
    zone[dynamics] = 0.38f
    zone[dynamics + 1] = 0.09f
    zone[dynamics + 2] = 0.11f
    zone[dynamics + 3] = 0.08f
    zone[shape] = signal
    zone[shape + 1] = 0.14f
    zone[shape + 2] = 1.6f
    floatArrayOf(0.8f, 0.5f, 0.2f).copyInto(zone, strength)
    zone[strength + 3] = application
    floatArrayOf(0.14f, 0.1f, 0.07f).copyInto(zone, amplitude)
    zone[amplitude + 3] = source
    floatArrayOf(0.1f, 0.17f, 0.26f).copyInto(zone, hz)
    zone[hz + 3] = 3f
    floatArrayOf(0f, 0.333333f, 0.666667f).copyInto(zone, phase)
  }
}
