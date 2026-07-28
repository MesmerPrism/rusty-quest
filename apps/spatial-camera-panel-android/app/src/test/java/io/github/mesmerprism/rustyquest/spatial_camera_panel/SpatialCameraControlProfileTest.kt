package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialCameraControlProfileTest {
  @Test
  fun malformedUtf8InOtherwiseValidStringFailsClosed() {
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraControlProfileContract.parse(
          malformedUtf8Inside(validProfile().toString(), "final")
      )
    }
  }

  @Test
  fun duplicateRootSchemaIncludingEscapeEquivalentFailsClosed() {
    val profile = validProfile().toString()
    val damaged =
        "{\"\\u0073chema\":\"${SpatialCameraControlProfileContract.SCHEMA}\"," +
            profile.drop(1)
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraControlProfileContract.parse(damaged.toByteArray())
    }
  }

  @Test
  fun duplicateNumericControlFailsClosed() {
    val profile = validProfile()
    val damaged =
        prependObjectMember(
            profile.toString(),
            profile.getJSONObject("quest_controls"),
            "\"projection_scale\":1.0",
        )
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraControlProfileContract.parse(damaged)
    }
  }

  @Test
  fun duplicateNestedObjectKeyIncludingEscapeEquivalentFailsClosed() {
    val profile = validProfile()
    val zone =
        profile.getJSONObject("quest_controls").getJSONObject("zone_compositor")
    val damaged =
        prependObjectMember(
            profile.toString(),
            zone,
            "\"\\u0065dge_inset_uv\":0.02",
        )
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraControlProfileContract.parse(damaged)
    }
  }

  @Test
  fun trailingContentFailsClosed() {
    val damaged = validProfile().toString().toByteArray() + byteArrayOf('x'.code.toByte())
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraControlProfileContract.parse(damaged)
    }
  }

  @Test
  fun excessiveNestingFailsAtStrictByteIngress() {
    val profile = validProfile().toString()
    val nestedArray = "[".repeat(65) + "0" + "]".repeat(65)
    val damaged = "{\"depth_probe\":$nestedArray,${profile.drop(1)}".toByteArray()

    val error =
        assertThrows(IllegalArgumentException::class.java) {
          SpatialCameraControlProfileContract.parse(damaged)
        }

    assertTrue(error.message?.contains("nesting_too_deep") == true)
  }

  @Test
  fun integralMetadataUsesExactLongConversionAndRangeChecks() {
    val profile = validProfile().toString()
    listOf(
            "0",
            "7e0",
            "9223372036854775807",
            "9.223372036854775807e18",
        )
        .forEach { literal ->
          SpatialCameraControlProfileContract.parse(
              replaceNumberField(profile, "revision", literal)
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
            SpatialCameraControlProfileContract.parse(
                replaceNumberField(profile, "revision", literal)
            )
          }
        }

    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraControlProfileContract.parse(
          replaceNumberField(profile, "created_unix_ms", "9223372036854775808")
      )
    }
  }

  @Test
  fun authoritativeByteBoundsAcceptExactlyLimitAndRejectEmptyOrLimitPlusOne() {
    val valid = validProfile().toString().toByteArray(Charsets.UTF_8)
    val atLimit =
        valid + ByteArray(SpatialCameraControlProfileContract.MAX_PROFILE_BYTES - valid.size) { ' '.code.toByte() }
    SpatialCameraControlProfileContract.parse(atLimit)

    val emptyError =
        assertThrows(IllegalArgumentException::class.java) {
          SpatialCameraControlProfileContract.parse(byteArrayOf())
        }
    assertEquals("profile_empty", emptyError.message)

    val oversizedError =
        assertThrows(IllegalArgumentException::class.java) {
          SpatialCameraControlProfileContract.parse(atLimit + ' '.code.toByte())
        }
    assertEquals("profile_too_large", oversizedError.message)
  }

  @Test
  fun validProfileCarriesStructuredDesktopControlsIntoQuestTypes() {
    val profile =
        SpatialCameraControlProfileContract.parse(validProfile().toString().toByteArray())

    assertEquals("artifact-test", profile.profileId)
    assertEquals(7L, profile.revision)
    assertEquals(0.0f, profile.layerOverride)
    assertEquals(1.15f, profile.projectionScale)
    assertEquals(
        PrivateLayerZoneCompositorControls.coverageDynamicBuffer,
        profile.zoneCompositor.coverageMode,
    )
    assertFalse(profile.zoneCompositor.projectionEffectEdgeGuardEnabled)
    assertEquals(
        RgbChannelTransformControls.modeIndependent,
        profile.rgbChannelTransform.mode,
    )
    assertTrue(profile.projectionSurfaceDisplacement.enabled)
    assertEquals(0.18f, profile.projectionSurfaceDisplacement.maxDisplacementMeters)
  }

  @Test
  fun damagedAndExpandedProfilesFailClosed() {
    val unknown = validProfile().put("ambient_command", "adb shell anything")
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraControlProfileContract.parse(unknown.toString().toByteArray())
    }

    val invalidProfile = validProfile()
    invalidProfile
        .getJSONObject("quest_controls")
        .getJSONObject("rgb_channel_transform")
        .getJSONObject("red")
        .put("displacement_strength_uv", 8.0)
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraControlProfileContract.parse(invalidProfile.toString().toByteArray())
    }
  }

  @Test
  fun desktopPreviewMustBeAnObjectWhenPresent() {
    val invalid = validProfile().put("desktop_preview", "not-an-object")
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraControlProfileContract.parse(invalid.toString().toByteArray())
    }
    val nullPreview = validProfile().put("desktop_preview", JSONObject.NULL)
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraControlProfileContract.parse(nullPreview.toString().toByteArray())
    }
  }

  @Test
  fun desktopPreviewRejectsUnknownMalformedAndOutOfRangeFields() {
    val unknown = validProfile()
    unknown.getJSONObject("desktop_preview").put("android_path", "/sdcard/x")
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraControlProfileContract.parse(unknown.toString().toByteArray())
    }

    val malformed = validProfile()
    malformed.getJSONObject("desktop_preview").put("preview_mode", "quest")
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraControlProfileContract.parse(malformed.toString().toByteArray())
    }

    val outOfRange = validProfile()
    outOfRange.getJSONObject("desktop_preview").put("preview_target_hz", 500.0)
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraControlProfileContract.parse(outOfRange.toString().toByteArray())
    }
  }

  @Test
  fun desktopPreviewRejectsEmptyAndWhitespaceLayerTokens() {
    for (token in listOf("", "   ")) {
      val invalid = validProfile()
      invalid.getJSONObject("desktop_preview").put("layer_token", token)
      assertThrows(IllegalArgumentException::class.java) {
        SpatialCameraControlProfileContract.parse(invalid.toString().toByteArray())
      }
    }
  }

  @Test
  fun unsampledOuterVideoRouteRejectsUnsupportedIncomingColor() {
    val invalid = validProfile()
    val zone = invalid.getJSONObject("quest_controls").getJSONObject("zone_compositor")
    zone.put("outer_target_mode", "transparent-spatial-video")
    zone
        .getJSONObject("outer")
        .getJSONObject("channel_dynamics")
        .put("source_choice", "incoming")
    assertThrows(IllegalArgumentException::class.java) {
      SpatialCameraControlProfileContract.parse(invalid.toString().toByteArray())
    }
  }

  private fun validProfile(): JSONObject {
    val inner = band(signal = "rgb", source = "outgoing", application = "component")
    val outer = band(signal = "luma", source = "outgoing", application = "region")
    val zone =
        JSONObject()
            .put("coverage_mode", "buffer")
            .put("stretch_source", "processed")
            .put("debug_mode", "normal")
            .put("outer_target_mode", "readable-color")
            .put("stretch_mapping", "graded-edge-trail-native")
            .put("projection_effect_edge_guard_enabled", false)
            .put("edge_inset_uv", 0.015)
            .put("max_inset_uv", 0.14)
            .put("stretch_curve", 1.6)
            .put("processed_mix", 0.68)
            .put("inner", inner)
            .put("outer", outer)
    val rgb =
        JSONObject()
            .put("mode", "independent")
            .put("edge_mode", "mirror")
            .put("red", rgbChannel(0.0, 0.11, 0.018, 1.0, 1.0))
            .put("green", rgbChannel(0.333333, 0.17, 0.014, 1.05, 0.92))
            .put("blue", rgbChannel(0.666667, -0.13, 0.022, 0.95, 0.84))
    val displacement =
        JSONObject()
            .put("enabled", true)
            .put("max_displacement_meters", 0.18)
            .put("reference_surface_distance_meters", 2.0)
            .put("polarity", 1.0)
            .put("edge_taper", 0.18)
    val controls =
        JSONObject()
            .put("layer_override", 0.0)
            .put("projection_scale", 1.15)
            .put("zone_compositor", zone)
            .put("rgb_channel_transform", rgb)
            .put("projection_surface_displacement", displacement)
    return JSONObject()
        .put("schema", SpatialCameraControlProfileContract.SCHEMA)
        .put("profile_id", "artifact-test")
        .put("revision", 7)
        .put("created_unix_ms", 12)
        .put("quest_controls", controls)
        .put(
            "desktop_preview",
            JSONObject()
                .put("layer_token", "final")
                .put("effect_clock_speed", 1.0)
                .put("preview_target_hz", 90.0)
                .put("preview_mode", "stereo")
                .put("preview_eye", "left")
                .put("color_effect_phase_offset_turns", 0.0)
                .put("color_effect_rate_hz", 0.0)
                .put("buffer_footprint_scale", 1.0),
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

  private fun band(signal: String, source: String, application: String): JSONObject =
      JSONObject()
          .put("signal", signal)
          .put("width_uv", 0.14)
          .put("curve", 1.6)
          .put("threshold_rgb", JSONArray(listOf(0.5, 0.5, 0.5)))
          .put("softness", 0.12)
          .put("strength", 0.38)
          .put("cycle_amplitude", 0.09)
          .put("cycle_hz", 0.11)
          .put("motion_gain", 0.08)
          .put(
              "channel_dynamics",
              JSONObject()
                  .put("application_mode", application)
                  .put("source_choice", source)
                  .put("region_driver", "luma")
                  .put("strength_rgb", JSONArray(listOf(0.8, 0.5, 0.2)))
                  .put("cycle_amplitude_rgb", JSONArray(listOf(0.14, 0.1, 0.07)))
                  .put("cycle_hz_rgb", JSONArray(listOf(0.1, 0.17, 0.26)))
                  .put("cycle_phase_turns_rgb", JSONArray(listOf(0.0, 0.333333, 0.666667))),
          )

  private fun rgbChannel(
      phase: Double,
      rate: Double,
      strength: Double,
      scale: Double,
      coverage: Double,
  ): JSONObject =
      JSONObject()
          .put("direction_turns", phase)
          .put("direction_rate_hz", rate)
          .put("displacement_strength_uv", strength)
          .put("image_scale", scale)
          .put("coverage_scale", coverage)
}
