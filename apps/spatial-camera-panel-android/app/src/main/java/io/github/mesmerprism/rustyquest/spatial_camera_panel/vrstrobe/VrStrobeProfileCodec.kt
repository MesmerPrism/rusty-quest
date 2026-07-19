package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import java.util.Base64

internal object VrStrobeProfileCodec {
  fun decodeInterferencePayload(
      id: String,
      title: String,
      sourceLabel: String,
      sourcePayload: String,
      durationSeconds: Float = VR_STROBE_DEFAULT_DURATION_SECONDS,
  ): VrStrobeInterferenceProfile {
    val decoded = Base64.getDecoder().decode(sourcePayload).toString(Charsets.UTF_8)
    val root = JsonReader(decoded).read() as? Map<*, *> ?: error("profile-root-not-object")
    val colors = root.objectValue("c")
    val animation = root.objectValue("a")
    val global = root.objectValue("g")
    val post = root.objectValue("p")
    val effects = root.objectValue("e")
    val patterns = buildList {
      addAll(root.patterns("s", VrStrobePatternKind.STRIPE))
      addAll(root.patterns("r", VrStrobePatternKind.RIPPLE))
      addAll(root.patterns("y", VrStrobePatternKind.RAY))
      addAll(root.patterns("n", VrStrobePatternKind.PERLIN))
    }
    return VrStrobeInterferenceProfile(
            id = id,
            title = title,
            sourceLabel = sourceLabel,
            sourcePayload = sourcePayload,
            durationSeconds = durationSeconds,
            colorCount = colors.intValue("colorCount", 2),
            color1 = VrStrobeColor.of(colors.stringValue("col1", "#000000")),
            color2 = VrStrobeColor.of(colors.stringValue("col2", "#ff0000")),
            color3 = VrStrobeColor.of(colors.stringValue("col3", "#00aaff")),
            oscillatorActive = animation.booleanValue("oscActive", false),
            oscillatorFrequencyHz = animation.floatValue("oscFreq", 0.5f),
            oscillatorShape = animation.floatValue("oscShape", 1f),
            scale = global.floatValue("scale", 2f),
            shearX = global.floatValue("shearX", 0f),
            shearY = global.floatValue("shearY", 0f),
            offsetX = global.floatValue("offsetX", 0f),
            offsetY = global.floatValue("offsetY", 0f),
            shakeAmplitude = global.floatValue("shakeAmp", 0f),
            shakeFrequencyHz = global.floatValue("shakeFreq", 5f),
            rotationSpeed = global.floatValue("rotSpeed", 0f),
            stepFactor = global.floatValue("stepFactor", 0f),
            trailAmount = post.floatValue("trailAmount", 0f),
            blurRadius = post.floatValue("blurRadius", 0f),
            glowStrength = post.floatValue("glowStrength", 0f),
            brightness = post.floatValue("brightness", 0f),
            contrast = post.floatValue("contrast", 1f),
            noiseFrequency = effects.floatValue("noiseFreq", 1f),
            noiseStrength = effects.floatValue("noiseStrength", 0f),
            noiseBias = effects.floatValue("noiseBias", 0.5f),
            vignetteCenter = effects.floatValue("vigCenter", 0f),
            vignetteEdge = effects.floatValue("vigEdge", 2f),
            vignetteBias = effects.floatValue("vigBias", 0f),
            patterns = patterns,
        )
        .sanitized()
  }

  private fun Map<*, *>.patterns(key: String, kind: VrStrobePatternKind): List<VrStrobePattern> =
      (this[key] as? List<*>)
          .orEmpty()
          .mapNotNull { it as? Map<*, *> }
          .take(VR_STROBE_MAX_PATTERN_INSTANCES)
          .map { values ->
            VrStrobePattern(
                    kind = kind,
                    active = values.booleanValue("active", true),
                    strength = values.floatValue("strength", 1f),
                    period = values.floatValue("period", 10f),
                    speed = values.floatValue("speed", 2f),
                    pivotX = values.floatValue("pivotX", 0f),
                    pivotY = values.floatValue("pivotY", 0f),
                    distortFreq = values.floatValue("distortFreq", 1f),
                    distortAmp = values.floatValue("distortAmp", 0f),
                    distortSpeed = values.floatValue("distortSpeed", 1f),
                    distMultPar = values.floatValue("distMultPar", 1f),
                    distMultOrth = values.floatValue("distMultOrth", 1f),
                    waveFreq = values.floatValue("waveFreq", 2f),
                    waveAmp = values.floatValue("waveAmp", 0f),
                    waveShape = values.floatValue("waveShape", 0f),
                    angle = values.floatValue("angle", 0f),
                    rotationPivotX = values.floatValue("rotPivX", 0f),
                    rotationPivotY = values.floatValue("rotPivY", 0f),
                    rotationSpeed = values.floatValue("rotSpeed", 0f),
                    extent = values.floatValue("extent", 0f),
                    noiseMove = values.floatValue("noiseMove", 0f),
                    perlinScale = values.floatValue("scale", 5f),
                    perlinZSpeed = values.floatValue("zSpeed", 1f),
                    perlinZOffset = values.floatValue("zOffset", 0f),
                )
                .sanitized()
          }

  private fun Map<*, *>.objectValue(key: String): Map<*, *> = this[key] as? Map<*, *> ?: emptyMap<Any, Any>()

  private fun Map<*, *>.stringValue(key: String, default: String): String = this[key] as? String ?: default

  private fun Map<*, *>.floatValue(key: String, default: Float): Float =
      (this[key] as? Number)?.toFloat() ?: default

  private fun Map<*, *>.intValue(key: String, default: Int): Int =
      (this[key] as? Number)?.toInt() ?: default

  private fun Map<*, *>.booleanValue(key: String, default: Boolean): Boolean =
      when (val value = this[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> default
      }
}

private class JsonReader(private val source: String) {
  private var index = 0

  fun read(): Any? {
    val value = value()
    whitespace()
    require(index == source.length) { "profile-json-trailing-data" }
    return value
  }

  private fun value(): Any? {
    whitespace()
    require(index < source.length) { "profile-json-unexpected-end" }
    return when (source[index]) {
      '{' -> objectValue()
      '[' -> arrayValue()
      '"' -> stringValue()
      't' -> literal("true", true)
      'f' -> literal("false", false)
      'n' -> literal("null", null)
      else -> numberValue()
    }
  }

  private fun objectValue(): Map<String, Any?> {
    expect('{')
    val result = linkedMapOf<String, Any?>()
    whitespace()
    if (peek('}')) {
      index += 1
      return result
    }
    while (true) {
      whitespace()
      val key = stringValue()
      whitespace()
      expect(':')
      result[key] = value()
      whitespace()
      if (peek('}')) {
        index += 1
        return result
      }
      expect(',')
    }
  }

  private fun arrayValue(): List<Any?> {
    expect('[')
    val result = mutableListOf<Any?>()
    whitespace()
    if (peek(']')) {
      index += 1
      return result
    }
    while (true) {
      result += value()
      whitespace()
      if (peek(']')) {
        index += 1
        return result
      }
      expect(',')
    }
  }

  private fun stringValue(): String {
    expect('"')
    val result = StringBuilder()
    while (index < source.length) {
      val char = source[index++]
      when (char) {
        '"' -> return result.toString()
        '\\' -> {
          require(index < source.length) { "profile-json-invalid-escape" }
          when (val escaped = source[index++]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000c')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
              require(index + 4 <= source.length) { "profile-json-invalid-unicode" }
              result.append(source.substring(index, index + 4).toInt(16).toChar())
              index += 4
            }
            else -> error("profile-json-unknown-escape:$escaped")
          }
        }
        else -> result.append(char)
      }
    }
    error("profile-json-unterminated-string")
  }

  private fun numberValue(): Number {
    val start = index
    while (index < source.length && source[index] in "-+0123456789.eE") index += 1
    require(index > start) { "profile-json-number-required" }
    val token = source.substring(start, index)
    return token.toLongOrNull() ?: token.toDoubleOrNull() ?: error("profile-json-invalid-number:$token")
  }

  private fun <T> literal(token: String, value: T): T {
    require(source.startsWith(token, index)) { "profile-json-invalid-literal" }
    index += token.length
    return value
  }

  private fun expect(char: Char) {
    whitespace()
    require(index < source.length && source[index] == char) { "profile-json-expected-$char" }
    index += 1
  }

  private fun peek(char: Char): Boolean = index < source.length && source[index] == char

  private fun whitespace() {
    while (index < source.length && source[index].isWhitespace()) index += 1
  }
}
