package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StrictJsonByteIngressTest {
  @Test
  fun strictGrammarAcceptsCanonicalValuesAndExactlyMaximumDepth() {
    val documents =
        listOf(
            "{}",
            " \r\n\t {\"text\":\"quote:\\\" slash:\\/ tab:\\t\",\"emoji\":\"🚀\"} \n",
            "{\"numbers\":[0,-0,1,-1,0.1,1e3,1E-3]}",
            "{\"depth\":${nestedArray(63)}}",
        )

    documents.forEach { document ->
      StrictJsonByteIngress.parseObject(document.toByteArray(Charsets.UTF_8))
    }

    assertEquals(
        "🚀",
        StrictJsonByteIngress.parseObject("{\"emoji\":\"\\uD83D\\uDE80\"}".toByteArray())
            .getString("emoji"),
    )
  }

  @Test
  fun malformedUtf8VariantsFailBeforeJsonConstruction() {
    val variants =
        listOf(
            byteArrayOf(0x7b, 0x22, 0x78, 0x22, 0x3a, 0x22, 0x80.toByte(), 0x22, 0x7d),
            byteArrayOf(
                0x7b,
                0x22,
                0x78,
                0x22,
                0x3a,
                0x22,
                0xc0.toByte(),
                0xaf.toByte(),
                0x22,
                0x7d,
            ),
            byteArrayOf(
                0x7b,
                0x22,
                0x78,
                0x22,
                0x3a,
                0x22,
                0xe2.toByte(),
                0x82.toByte(),
            ),
            byteArrayOf(
                0x7b,
                0x22,
                0x78,
                0x22,
                0x3a,
                0x22,
                0xed.toByte(),
                0xa0.toByte(),
                0x80.toByte(),
                0x22,
                0x7d,
            ),
        )

    variants.forEach { bytes ->
      val error =
          assertThrows(IllegalArgumentException::class.java) {
            StrictJsonByteIngress.parseObject(bytes)
          }
      assertEquals("invalid_json_utf8", error.message)
    }
  }

  @Test
  fun damagedStringsRootsNumbersAndTrailingValuesFailClosed() {
    val invalidDocuments =
        listOf(
            "[]" to "invalid_json_root_must_be_object",
            "\"scalar\"" to "invalid_json_root_must_be_object",
            "true" to "invalid_json_root_must_be_object",
            "{" to "invalid_json_object_key_must_be_string",
            "{\"x\"" to "invalid_json_expected_:",
            "{\"x\":" to "invalid_json_unexpected_end",
            "{\"x\":\"\\x\"}" to "invalid_json_invalid_escape_x",
            "{\"x\":\"unterminated}" to "invalid_json_unterminated_string",
            "{\"x\":\"\\uD800\"}" to "invalid_json_unpaired_surrogate",
            "{\"x\":\"\\uDC00\"}" to "invalid_json_unpaired_surrogate",
            "{\"x\":\"\\uD800\\u0041\"}" to "invalid_json_unpaired_surrogate",
            "{\"x\":\"raw\u0001control\"}" to "invalid_json_unescaped_control_character",
            "{\"n\":+1}" to "invalid_json_invalid_value",
            "{\"n\":01}" to "invalid_json_leading_zero",
            "{\"n\":1.}" to "invalid_json_missing_fraction_digits",
            "{\"n\":.1}" to "invalid_json_invalid_value",
            "{\"n\":1e}" to "invalid_json_missing_exponent_digits",
            "{\"n\":1e+}" to "invalid_json_missing_exponent_digits",
            "{\"n\":--1}" to "invalid_json_invalid_number",
            "{}{}" to "invalid_json_trailing_content",
            "{} true" to "invalid_json_trailing_content",
            "{\"depth\":${nestedArray(64)}}" to "invalid_json_nesting_too_deep",
        )

    invalidDocuments.forEach { (document, reason) ->
      val error =
          assertThrows("should reject $document", IllegalArgumentException::class.java) {
            StrictJsonByteIngress.parseObject(document.toByteArray(Charsets.UTF_8))
          }
      assertEquals(reason, error.message)
    }
  }

  @Test
  fun decodedDuplicateKeysFailAtEveryObjectLocation() {
    val invalidDocuments =
        listOf(
            "{\"schema\":1,\"\\u0073chema\":2}",
            "{\"outer\":{\"key\":1,\"\\u006bey\":2}}",
            "{\"array\":[{\"key\":1,\"k\\u0065y\":2}]}",
            "{\"slash/key\":1,\"slash\\/key\":2}",
            "{\"emoji🚀\":1,\"emoji\\uD83D\\uDE80\":2}",
        )

    invalidDocuments.forEach { document ->
      val error =
          assertThrows("should reject $document", IllegalArgumentException::class.java) {
            StrictJsonByteIngress.parseObject(document.toByteArray(Charsets.UTF_8))
          }
      assertEquals("invalid_json_duplicate_object_key", error.message)
    }
  }

  private fun nestedArray(levels: Int): String =
      "[".repeat(levels) + "0" + "]".repeat(levels)
}
