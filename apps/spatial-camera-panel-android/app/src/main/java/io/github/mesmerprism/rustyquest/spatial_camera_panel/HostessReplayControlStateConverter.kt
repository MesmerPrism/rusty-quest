package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.json.JSONArray
import org.json.JSONObject

/** Quest-owned conversion from Hostess capsule-native replay buffers to the Quest profile. */
internal object HostessReplayControlStateConverter {
  const val INPUT_SCHEMA = "rusty.hostess.projection_replay_control_state.v2"
  const val INPUT_V1_SCHEMA = "rusty.hostess.projection_replay_control_state.v1"
  const val MAX_INPUT_BYTES = 64 * 1024

  fun export(bytes: ByteArray): ByteArray {
    require(bytes.isNotEmpty()) { "control_state_empty" }
    require(bytes.size <= MAX_INPUT_BYTES) { "control_state_too_large" }
    val root = StrictJsonByteIngress.parseObject(bytes)
    root.requireOnlyKeys(
        "schema",
        "state_id",
        "revision",
        "created_unix_ms",
        "replay_layer",
        "projection",
        "preview",
        "control_transport",
    )
    val inputSchema = root.requireString("schema")
    require(inputSchema == INPUT_SCHEMA || inputSchema == INPUT_V1_SCHEMA) {
      "unsupported_control_state_schema"
    }
    if (inputSchema == INPUT_SCHEMA && root.has("control_transport") && !root.isNull("control_transport")) {
      val transport = root.requireObject("control_transport")
      transport.requireOnlyKeys("transport_id", "capsule_sha256", "values")
      require(PORTABLE_ID.matches(transport.requireString("transport_id"))) {
        "invalid_control_transport_id"
      }
      require(Regex("[0-9a-fA-F]{64}").matches(transport.requireString("capsule_sha256"))) {
        "invalid_control_transport_capsule_sha256"
      }
      val values = transport.requireObject("values")
      for (key in values.keys()) {
        require(PORTABLE_ID.matches(key)) {
          "invalid_control_transport_value_key"
        }
        val value = values.get(key)
        require(value is Number && value.toDouble().isFinite()) {
          "invalid_control_transport_value"
        }
      }
    } else {
      require(!root.has("control_transport")) { "v1_control_transport_not_allowed" }
    }
    val id = root.requireString("state_id")
    require(PORTABLE_ID.matches(id)) { "invalid_state_id" }
    val revision = root.requireLong("revision", 0, Long.MAX_VALUE)
    val created = root.requireLong("created_unix_ms", 0, Long.MAX_VALUE)
    val layer = root.requireObject("replay_layer")
    layer.requireOnlyKeys("layer_token", "override_value")
    val replayLayerToken = layer.requireString("layer_token")
    require(replayLayerToken.trim().isNotEmpty() && replayLayerToken.length <= 128) {
      "invalid_layer_token"
    }
    val layerOverride = layer.requireFloat("override_value", -1.0f, 8.0f)
    val projection = root.requireObject("projection")
    if (inputSchema == INPUT_SCHEMA) {
      projection.requireOnlyKeys(
          "scale",
          "rgb_uniform_f32",
          "displacement_uniform_f32",
          "displacement_enabled",
          "zone_uniform_f32",
          "surface_feature_uniform_f32",
      )
    } else {
      projection.requireOnlyKeys(
          "scale",
          "rgb_uniform_f32",
          "displacement_uniform_f32",
          "displacement_enabled",
          "zone_uniform_f32",
      )
    }
    val scale = projection.requireFloat("scale", 0.25f, 1.8f)
    val rgb = projection.requireFloatArray("rgb_uniform_f32", 24)
    val displacement = projection.requireFloatArray("displacement_uniform_f32", 16)
    val displacementEnabled = projection.requireBoolean("displacement_enabled")
    val zone = projection.requireFloatArray("zone_uniform_f32", 92)
    val surfaceFeatures =
        if (projection.has("surface_feature_uniform_f32")) {
          projection.requireFloatArray("surface_feature_uniform_f32", 32).also { values ->
            require(
                (0 until 16).all { index ->
                  kotlin.math.abs(values[index] - displacement[index]) <= 0.000001f
                }
            ) {
              "surface_feature_uniform_prefix_mismatch"
            }
            require(token(values[30], "v0", "v1", "v2") == "v2") {
              "unsupported_surface_feature_uniform_abi"
            }
            require(kotlin.math.abs(values[31]) <= 0.000001f) {
              "surface_feature_uniform_reserved_nonzero"
            }
          }
        } else {
          null
        }
    val preview =
        if (root.has("preview") && !root.isNull("preview")) {
          root.requireObject("preview").also {
            it.requireOnlyKeys(
                "layer_token",
                "effect_clock_speed",
                "preview_target_hz",
                "preview_mode",
                "preview_eye",
                "color_effect_phase_offset_turns",
                "color_effect_rate_hz",
                "buffer_footprint_scale",
            )
            val previewLayerToken = it.requireString("layer_token")
            require(
                previewLayerToken.trim().isNotEmpty() && previewLayerToken.length <= 128
            ) {
              "invalid_preview_layer_token"
            }
            it.requireFloat("effect_clock_speed", 0.05f, 4.0f)
            it.requireFloat("preview_target_hz", 30.0f, 120.0f)
            it.requireToken("preview_mode", "stereo", "mono")
            it.requireToken("preview_eye", "left", "right")
            it.requireFloat("color_effect_phase_offset_turns", 0.0f, 1.0f)
            it.requireFloat("color_effect_rate_hz", -2.0f, 2.0f)
            it.requireFloat("buffer_footprint_scale", 0.5f, 1.5f)
          }
        } else {
          null
        }

    val controls =
        JSONObject()
            .put("layer_override", layerOverride)
            .put("projection_scale", scale)
            .put("zone_compositor", zoneJson(zone))
            .put("rgb_channel_transform", rgbJson(rgb))
            .put(
                "projection_surface_displacement",
                JSONObject()
                    .put("enabled", displacementEnabled && displacement[0] >= 0.5f)
                    .put("max_displacement_meters", displacement[4])
                    .put("reference_surface_distance_meters", displacement[5])
                    .put("polarity", displacement[1])
                    .put("edge_taper", displacement[6]),
            )
            .put("projection_surface_tiling", surfaceTilingJson(surfaceFeatures))
            .put("projection_inner_alpha", innerAlphaJson(surfaceFeatures))
    val output =
        JSONObject()
            .put("schema", SpatialCameraControlProfileContract.SCHEMA)
            .put("profile_id", id)
            .put("revision", revision)
            .put("created_unix_ms", created)
            .put("quest_controls", controls)
    if (preview != null) output.put("desktop_preview", preview)
    val encoded = output.toString(2).toByteArray(Charsets.UTF_8)
    SpatialCameraControlProfileContract.parse(encoded)
    return encoded
  }

  private val PORTABLE_ID = Regex("[a-z0-9][a-z0-9._-]{1,63}")

  private fun rgbJson(values: FloatArray): JSONObject =
      JSONObject()
          .put("mode", token(values[0], "off", "independent", "linked"))
          .put("edge_mode", token(values[1], "clamp", "mirror", "fade"))
          .put("red", rgbChannel(values, 0))
          .put("green", rgbChannel(values, 1))
          .put("blue", rgbChannel(values, 2))

  private fun rgbChannel(values: FloatArray, channel: Int): JSONObject =
      JSONObject()
          .put("direction_turns", values[4 + channel].mod(1.0f))
          .put("direction_rate_hz", values[8 + channel])
          .put("displacement_strength_uv", values[12 + channel])
          .put("image_scale", values[16 + channel])
          .put("coverage_scale", values[20 + channel])

  private fun surfaceTilingJson(values: FloatArray?): JSONObject =
      if (values == null) {
        JSONObject()
            .put("enabled", false)
            .put("topology", "continuous")
            .put("gap_normalized", 0.0)
            .put("depth_flexibility", 1.0)
            .put("scope", "inner-and-buffer")
      } else {
        JSONObject()
            .put("enabled", values[16] >= 0.5f)
            .put("topology", token(values[17], "continuous", "tiled", "triangle-tiles"))
            .put("gap_normalized", values[18])
            .put("depth_flexibility", values[19])
            .put("scope", token(values[20], "inner-and-buffer", "core-only"))
      }

  private fun innerAlphaJson(values: FloatArray?): JSONObject =
      if (values == null) {
        JSONObject()
            .put("enabled", false)
            .put("driver", "luma")
            .put("threshold", 0.5)
            .put("softness", 0.1)
            .put("amount", 0.0)
            .put("invert", false)
            .put("stretch_policy", "follow-projection")
            .put("stretch_obeys_exact_projection_mask", false)
      } else {
        JSONObject()
            .put("enabled", values[21] >= 0.5f)
            .put("driver", token(values[22], "red", "green", "blue", "luma", "max"))
            .put("threshold", values[24])
            .put("softness", values[25])
            .put("amount", values[26])
            .put("invert", values[27] >= 0.5f)
            .put(
                "stretch_policy",
                token(values[23], "follow-projection", "opaque-independent"),
            )
            .put("stretch_obeys_exact_projection_mask", values[28] >= 0.5f)
      }

  private fun zoneJson(v: FloatArray): JSONObject {
    val flags = v[31].toInt()
    val independent = (flags and (1 shl 8)) != 0
    val result =
        JSONObject()
          .put("coverage_mode", token(v[24], "off", "buffer", "full"))
          .put("stretch_source", token(v[25], "raw", "processed", "mix"))
          .put("debug_mode", token(v[26], "normal", "regions", "sample-uv"))
          .put(
              "outer_target_mode",
              token(v[59], "readable-color", "transparent-spatial-video"),
          )
          .put("stretch_mapping", "graded-edge-trail-native")
          .put(
              "projection_effect_edge_guard_enabled",
              (v[31].toInt() and (1 shl 1)) == 0,
          )
          .put("stretch_option_flags", flags and 0x1d)
          .put("edge_inset_uv", v[28])
          .put("max_inset_uv", v[29])
          .put("stretch_curve", v[30])
          .put("processed_mix", v[27])
          .put("inner", band(v, true))
          .put("outer", band(v, false))
    if (independent) {
      result
          .put("region_contract", "v2")
          .put(
              "buffer_geometry",
              token(((flags shr 9) and 0x3).toFloat(), "off", "static", "dynamic"),
          )
          .put("buffer_static_width_uv", v[47])
          .put(
              "buffer_fill",
              token(
                  ((flags shr 11) and 0x3).toFloat(),
                  "outer-continuation",
                  "transparent-reveal",
                  "stretch",
              ),
          )
          .put(
              "stretch_extent",
              token(((flags shr 13) and 0x1).toFloat(), "buffer-only", "replace-outer"),
          )
    }
    return result
  }

  private fun band(v: FloatArray, inner: Boolean): JSONObject {
    val threshold = if (inner) 36 else 48
    val dynamics = if (inner) 40 else 52
    val shape = if (inner) 44 else 56
    val strength = if (inner) 60 else 76
    val amplitude = if (inner) 64 else 80
    val hz = if (inner) 68 else 84
    val phase = if (inner) 72 else 88
    return JSONObject()
        .put("signal", token(v[shape], "flat", "rgb", "luma", "chroma", "difference"))
        .put("width_uv", v[shape + 1])
        .put("curve", v[shape + 2])
        .put("threshold_rgb", floatArray(v, threshold, 3))
        .put("softness", v[threshold + 3])
        .put("strength", v[dynamics])
        .put("cycle_amplitude", v[dynamics + 1])
        .put("cycle_hz", v[dynamics + 2])
        .put("motion_gain", v[dynamics + 3])
        .put(
            "channel_dynamics",
            JSONObject()
                .put("application_mode", token(v[strength + 3], "legacy", "component", "region"))
                .put("source_choice", token(v[amplitude + 3], "outgoing", "midpoint", "incoming"))
                .put("region_driver", token(v[hz + 3], "red", "green", "blue", "luma", "max"))
                .put("strength_rgb", floatArray(v, strength, 3))
                .put("cycle_amplitude_rgb", floatArray(v, amplitude, 3))
                .put("cycle_hz_rgb", floatArray(v, hz, 3))
                .put("cycle_phase_turns_rgb", floatArray(v, phase, 3)),
        )
  }

  private fun token(value: Float, vararg tokens: String): String {
    require(value.isFinite()) { "non_finite_token" }
    val index = value.toInt()
    require(kotlin.math.abs(value - index.toFloat()) < 0.001f && index in tokens.indices) {
      "unsupported_control_token"
    }
    return tokens[index]
  }

  private fun floatArray(values: FloatArray, start: Int, count: Int): JSONArray =
      JSONArray((start until start + count).map { values[it] })
}

private fun JSONObject.requireFloatArray(name: String, length: Int): FloatArray {
  val value = get(name)
  require(value is JSONArray && value.length() == length) { "${name}_wrong_length" }
  return FloatArray(length) { index ->
    val item = value.get(index)
    require(item is Number) { "${name}_${index}_must_be_number" }
    item.toFloat().also { require(it.isFinite()) { "${name}_${index}_non_finite" } }
  }
}
