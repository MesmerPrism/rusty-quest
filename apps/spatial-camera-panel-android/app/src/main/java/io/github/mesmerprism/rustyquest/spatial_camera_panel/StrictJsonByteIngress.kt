package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Quest-owned strict byte ingress for bounded Spatial Camera Panel JSON contracts.
 *
 * This validates the complete RFC 8259 grammar and decoded object-key uniqueness before the
 * intentionally more permissive org.json representation is constructed.
 */
internal object StrictJsonByteIngress {
  fun parseObject(bytes: ByteArray): JSONObject {
    val text = decodeUtf8(bytes)
    StrictJsonSyntaxValidator(text).validateRootObject()
    return try {
      JSONObject(text)
    } catch (error: Exception) {
      throw IllegalArgumentException("invalid_json_object", error)
    }
  }

  private fun decodeUtf8(bytes: ByteArray): String =
      try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
      } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("invalid_json_utf8", error)
      }
}

private class StrictJsonSyntaxValidator(private val text: String) {
  private var index = 0

  fun validateRootObject() {
    skipWhitespace()
    if (peek() != '{') fail("root_must_be_object")
    parseObject(depth = 1)
    skipWhitespace()
    if (index != text.length) fail("trailing_content")
  }

  private fun parseValue(depth: Int) {
    when (peek()) {
      '{' -> parseObject(depth + 1)
      '[' -> parseArray(depth + 1)
      '"' -> parseString()
      't' -> parseLiteral("true")
      'f' -> parseLiteral("false")
      'n' -> parseLiteral("null")
      '-', in '0'..'9' -> parseNumber()
      null -> fail("unexpected_end")
      else -> fail("invalid_value")
    }
  }

  private fun parseObject(depth: Int) {
    requireDepth(depth)
    expect('{')
    skipWhitespace()
    if (consumeIf('}')) return

    val keys = HashSet<String>()
    while (true) {
      if (peek() != '"') fail("object_key_must_be_string")
      val key = parseString()
      if (!keys.add(key)) fail("duplicate_object_key")
      skipWhitespace()
      expect(':')
      skipWhitespace()
      parseValue(depth)
      skipWhitespace()
      if (consumeIf('}')) return
      expect(',')
      skipWhitespace()
    }
  }

  private fun parseArray(depth: Int) {
    requireDepth(depth)
    expect('[')
    skipWhitespace()
    if (consumeIf(']')) return

    while (true) {
      parseValue(depth)
      skipWhitespace()
      if (consumeIf(']')) return
      expect(',')
      skipWhitespace()
    }
  }

  private fun parseString(): String {
    expect('"')
    val decoded = StringBuilder()
    while (true) {
      if (index >= text.length) fail("unterminated_string")
      val character = text[index++]
      when {
        character == '"' -> return decoded.toString()
        character == '\\' -> appendEscape(decoded)
        character < ' ' -> fail("unescaped_control_character")
        Character.isHighSurrogate(character) -> {
          if (index >= text.length || !Character.isLowSurrogate(text[index])) {
            fail("unpaired_surrogate")
          }
          decoded.append(character)
          decoded.append(text[index++])
        }
        Character.isLowSurrogate(character) -> fail("unpaired_surrogate")
        else -> decoded.append(character)
      }
    }
  }

  private fun appendEscape(decoded: StringBuilder) {
    if (index >= text.length) fail("unterminated_escape")
    when (val escaped = text[index++]) {
      '"' -> decoded.append('"')
      '\\' -> decoded.append('\\')
      '/' -> decoded.append('/')
      'b' -> decoded.append('\b')
      'f' -> decoded.append('\u000c')
      'n' -> decoded.append('\n')
      'r' -> decoded.append('\r')
      't' -> decoded.append('\t')
      'u' -> {
        val first = parseHexCodeUnit()
        when {
          Character.isHighSurrogate(first) -> {
            if (index + 1 >= text.length || text[index] != '\\' || text[index + 1] != 'u') {
              fail("unpaired_surrogate")
            }
            index += 2
            val second = parseHexCodeUnit()
            if (!Character.isLowSurrogate(second)) fail("unpaired_surrogate")
            decoded.append(first)
            decoded.append(second)
          }
          Character.isLowSurrogate(first) -> fail("unpaired_surrogate")
          else -> decoded.append(first)
        }
      }
      else -> fail("invalid_escape_$escaped")
    }
  }

  private fun parseHexCodeUnit(): Char {
    if (index + 4 > text.length) fail("short_unicode_escape")
    var value = 0
    repeat(4) {
      val digit = text[index++].digitToIntOrNull(16) ?: fail("invalid_unicode_escape")
      value = (value shl 4) or digit
    }
    return value.toChar()
  }

  private fun parseNumber() {
    consumeIf('-')
    when (peek()) {
      '0' -> {
        index++
        if (peek() in '0'..'9') fail("leading_zero")
      }
      in '1'..'9' -> consumeDigits()
      else -> fail("invalid_number")
    }
    if (consumeIf('.')) {
      if (peek() !in '0'..'9') fail("missing_fraction_digits")
      consumeDigits()
    }
    if (peek() == 'e' || peek() == 'E') {
      index++
      if (peek() == '+' || peek() == '-') index++
      if (peek() !in '0'..'9') fail("missing_exponent_digits")
      consumeDigits()
    }
  }

  private fun consumeDigits() {
    while (peek() in '0'..'9') index++
  }

  private fun parseLiteral(literal: String) {
    if (!text.regionMatches(index, literal, 0, literal.length)) fail("invalid_literal")
    index += literal.length
  }

  private fun skipWhitespace() {
    while (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r') index++
  }

  private fun expect(expected: Char) {
    if (!consumeIf(expected)) fail("expected_$expected")
  }

  private fun consumeIf(expected: Char): Boolean {
    if (peek() != expected) return false
    index++
    return true
  }

  private fun peek(): Char? = text.getOrNull(index)

  private fun requireDepth(depth: Int) {
    if (depth > MAX_NESTING_DEPTH) fail("nesting_too_deep")
  }

  private fun fail(reason: String): Nothing =
      throw IllegalArgumentException("invalid_json_$reason")

  private companion object {
    const val MAX_NESTING_DEPTH = 64
  }
}
