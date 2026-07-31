package io.github.mesmerprism.rustyquest.packageupdater;

import java.util.HashSet;
import java.util.Set;

final class StrictJsonPreflight {
    private static final int MAX_DEPTH = 32;

    private final String input;
    private int index;

    private StrictJsonPreflight(String input) {
        this.input = input;
    }

    static void requireNoDuplicateObjectKeys(String input)
            throws UpdateEnvelopeVerifier.VerificationException {
        StrictJsonPreflight parser = new StrictJsonPreflight(input);
        parser.skipWhitespace();
        parser.parseValue(0);
        parser.skipWhitespace();
        if (parser.index != input.length()) {
            throw new UpdateEnvelopeVerifier.VerificationException(
                    "json_trailing_content");
        }
    }

    private void parseValue(int depth)
            throws UpdateEnvelopeVerifier.VerificationException {
        if (depth > MAX_DEPTH || index >= input.length()) {
            reject("json_depth_or_value_invalid");
        }
        switch (input.charAt(index)) {
            case '{':
                parseObject(depth + 1);
                return;
            case '[':
                parseArray(depth + 1);
                return;
            case '"':
                parseString();
                return;
            case 't':
                consumeLiteral("true");
                return;
            case 'f':
                consumeLiteral("false");
                return;
            case 'n':
                consumeLiteral("null");
                return;
            default:
                parseNumber();
        }
    }

    private void parseObject(int depth)
            throws UpdateEnvelopeVerifier.VerificationException {
        index++;
        skipWhitespace();
        if (consumeIf('}')) {
            return;
        }
        Set<String> keys = new HashSet<>();
        while (true) {
            if (index >= input.length() || input.charAt(index) != '"') {
                reject("json_object_key_invalid");
            }
            String key = parseString();
            if (!keys.add(key)) {
                reject("json_duplicate_object_key");
            }
            skipWhitespace();
            require(':');
            skipWhitespace();
            parseValue(depth);
            skipWhitespace();
            if (consumeIf('}')) {
                return;
            }
            require(',');
            skipWhitespace();
        }
    }

    private void parseArray(int depth)
            throws UpdateEnvelopeVerifier.VerificationException {
        index++;
        skipWhitespace();
        if (consumeIf(']')) {
            return;
        }
        while (true) {
            parseValue(depth);
            skipWhitespace();
            if (consumeIf(']')) {
                return;
            }
            require(',');
            skipWhitespace();
        }
    }

    private String parseString()
            throws UpdateEnvelopeVerifier.VerificationException {
        require('"');
        StringBuilder value = new StringBuilder();
        while (index < input.length()) {
            char character = input.charAt(index++);
            if (character == '"') {
                return value.toString();
            }
            if (character < 0x20) {
                reject("json_control_character");
            }
            if (character != '\\') {
                value.append(character);
                continue;
            }
            if (index >= input.length()) {
                reject("json_escape_invalid");
            }
            char escape = input.charAt(index++);
            switch (escape) {
                case '"':
                case '\\':
                case '/':
                    value.append(escape);
                    break;
                case 'b':
                    value.append('\b');
                    break;
                case 'f':
                    value.append('\f');
                    break;
                case 'n':
                    value.append('\n');
                    break;
                case 'r':
                    value.append('\r');
                    break;
                case 't':
                    value.append('\t');
                    break;
                case 'u':
                    value.append(parseUnicodeEscape());
                    break;
                default:
                    reject("json_escape_invalid");
            }
        }
        reject("json_string_unterminated");
        return "";
    }

    private char parseUnicodeEscape()
            throws UpdateEnvelopeVerifier.VerificationException {
        if (index + 4 > input.length()) {
            reject("json_unicode_escape_invalid");
        }
        int value = 0;
        for (int count = 0; count < 4; count++) {
            int digit = Character.digit(input.charAt(index++), 16);
            if (digit < 0) {
                reject("json_unicode_escape_invalid");
            }
            value = (value << 4) | digit;
        }
        return (char) value;
    }

    private void parseNumber()
            throws UpdateEnvelopeVerifier.VerificationException {
        int start = index;
        consumeIf('-');
        if (consumeIf('0')) {
            if (index < input.length() && Character.isDigit(input.charAt(index))) {
                reject("json_number_leading_zero");
            }
        } else {
            requireDigit('1', '9');
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }
        }
        if (consumeIf('.')) {
            requireDigit('0', '9');
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }
        }
        if (index < input.length()
                && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
            index++;
            if (index < input.length()
                    && (input.charAt(index) == '+' || input.charAt(index) == '-')) {
                index++;
            }
            requireDigit('0', '9');
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }
        }
        if (index == start) {
            reject("json_value_invalid");
        }
    }

    private void requireDigit(char minimum, char maximum)
            throws UpdateEnvelopeVerifier.VerificationException {
        if (index >= input.length()
                || input.charAt(index) < minimum
                || input.charAt(index) > maximum) {
            reject("json_number_invalid");
        }
        index++;
    }

    private void consumeLiteral(String literal)
            throws UpdateEnvelopeVerifier.VerificationException {
        if (!input.regionMatches(index, literal, 0, literal.length())) {
            reject("json_literal_invalid");
        }
        index += literal.length();
    }

    private void skipWhitespace() {
        while (index < input.length()) {
            char character = input.charAt(index);
            if (character != ' '
                    && character != '\t'
                    && character != '\r'
                    && character != '\n') {
                return;
            }
            index++;
        }
    }

    private boolean consumeIf(char expected) {
        if (index < input.length() && input.charAt(index) == expected) {
            index++;
            return true;
        }
        return false;
    }

    private void require(char expected)
            throws UpdateEnvelopeVerifier.VerificationException {
        if (!consumeIf(expected)) {
            reject("json_expected_" + expected);
        }
    }

    private static void reject(String code)
            throws UpdateEnvelopeVerifier.VerificationException {
        throw new UpdateEnvelopeVerifier.VerificationException(code);
    }
}
