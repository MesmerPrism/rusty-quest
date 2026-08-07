package io.github.mesmerprism.rustyquest.broker_transport;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Strict, allocation-bounded RFC6455 framing shared by Android broker placements. */
public final class Rfc6455Codec {
    public static final int OPCODE_CONTINUATION = 0x0;
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;

    private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private Rfc6455Codec() {}

    public static Frame readFrame(InputStream input, boolean expectMasked, int maxFrameBytes)
            throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("input is required");
        }
        if (maxFrameBytes < 0) {
            throw new IllegalArgumentException("maxFrameBytes must be non-negative");
        }
        int first = readByte(input);
        int second = readByte(input);
        boolean fin = (first & 0x80) != 0;
        if ((first & 0x70) != 0) {
            throw new IOException("reserved websocket bits are unsupported");
        }
        int opcode = first & 0x0F;
        requireKnownOpcode(opcode);
        boolean masked = (second & 0x80) != 0;
        if (masked != expectMasked) {
            throw new IOException(expectMasked
                    ? "client websocket frame must be masked"
                    : "server websocket frame must not be masked");
        }

        long length = second & 0x7F;
        if (length == 126) {
            length = ((long) readByte(input) << 8) | (long) readByte(input);
            if (length < 126) {
                throw new IOException("noncanonical 16-bit websocket length");
            }
        } else if (length == 127) {
            int firstLengthByte = readByte(input);
            if ((firstLengthByte & 0x80) != 0) {
                throw new IOException("websocket length exceeds signed 63-bit range");
            }
            length = firstLengthByte;
            for (int index = 1; index < 8; index += 1) {
                if (length > ((long) Integer.MAX_VALUE >>> 8)) {
                    throw new IOException("websocket length exceeds host allocation range");
                }
                length = (length << 8) | (long) readByte(input);
            }
            if (length <= 0xFFFFL) {
                throw new IOException("noncanonical 64-bit websocket length");
            }
        }

        boolean control = isControl(opcode);
        if (control && (!fin || length > 125)) {
            throw new IOException("invalid websocket control frame");
        }
        if (length > maxFrameBytes || length > Integer.MAX_VALUE) {
            throw new IOException("websocket frame exceeds configured bound");
        }

        byte[] maskingKey = masked ? readExact(input, 4) : null;
        byte[] payload = readExact(input, (int) length);
        if (maskingKey != null) {
            for (int index = 0; index < payload.length; index += 1) {
                payload[index] = (byte) (payload[index] ^ maskingKey[index & 3]);
            }
        }
        return new Frame(fin, opcode, payload);
    }

    public static void writeFrame(
            OutputStream output,
            boolean fin,
            int opcode,
            byte[] payload,
            byte[] maskingKey) throws IOException {
        if (output == null || payload == null) {
            throw new IllegalArgumentException("output and payload are required");
        }
        requireKnownOpcode(opcode);
        if (isControl(opcode) && (!fin || payload.length > 125)) {
            throw new IOException("invalid websocket control frame");
        }
        if (maskingKey != null && maskingKey.length != 4) {
            throw new IllegalArgumentException("masking key must contain four bytes");
        }

        output.write((fin ? 0x80 : 0) | opcode);
        int maskBit = maskingKey == null ? 0 : 0x80;
        if (payload.length < 126) {
            output.write(maskBit | payload.length);
        } else if (payload.length <= 0xFFFF) {
            output.write(maskBit | 126);
            output.write((payload.length >>> 8) & 0xFF);
            output.write(payload.length & 0xFF);
        } else {
            output.write(maskBit | 127);
            long length = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                output.write((int) ((length >>> shift) & 0xFF));
            }
        }
        if (maskingKey != null) {
            output.write(maskingKey);
            for (int index = 0; index < payload.length; index += 1) {
                output.write(payload[index] ^ maskingKey[index & 3]);
            }
        } else {
            output.write(payload);
        }
        output.flush();
    }

    public static String serverAccept(
            String method,
            String httpVersion,
            Map<String, String> lowerCaseHeaders) throws IOException {
        if (!"GET".equals(method) || !"HTTP/1.1".equals(httpVersion)) {
            throw new IOException("websocket upgrade requires GET HTTP/1.1");
        }
        if (lowerCaseHeaders == null
                || !"websocket".equalsIgnoreCase(lowerCaseHeaders.get("upgrade"))
                || !headerContainsToken(lowerCaseHeaders.get("connection"), "upgrade")
                || !"13".equals(lowerCaseHeaders.get("sec-websocket-version"))
                || lowerCaseHeaders.containsKey("transfer-encoding")
                || (lowerCaseHeaders.containsKey("content-length")
                        && !"0".equals(lowerCaseHeaders.get("content-length")))) {
            throw new IOException("incomplete websocket upgrade headers");
        }
        String key = lowerCaseHeaders.get("sec-websocket-key");
        final byte[] decoded;
        try {
            decoded = key == null ? new byte[0] : Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("invalid websocket key", malformed);
        }
        if (decoded.length != 16) {
            throw new IOException("websocket key must decode to 16 bytes");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(
                    (key + ACCEPT_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IOException("SHA-1 is unavailable for RFC6455 upgrade", unavailable);
        }
    }

    public static UpgradeRequest readUpgradeRequest(InputStream input, int maxHeaderBytes)
            throws IOException {
        if (input == null || maxHeaderBytes < 16) {
            throw new IllegalArgumentException("input and a positive header bound are required");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int state = 0;
        while (bytes.size() < maxHeaderBytes) {
            int next = readByte(input);
            bytes.write(next);
            if ((state == 0 || state == 2) && next == '\r') {
                state += 1;
            } else if ((state == 1 || state == 3) && next == '\n') {
                state += 1;
            } else {
                state = next == '\r' ? 1 : 0;
            }
            if (state == 4) {
                break;
            }
        }
        if (state != 4) {
            throw new IOException("websocket upgrade header exceeds configured bound");
        }
        String raw = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
        String[] lines = raw.split("\\r\\n", -1);
        if (lines.length < 3) {
            throw new IOException("incomplete websocket upgrade request");
        }
        String[] request = lines[0].split(" ", -1);
        if (request.length != 3
                || request[0].isEmpty()
                || request[1].isEmpty()
                || request[2].isEmpty()) {
            throw new IOException("invalid websocket upgrade request line");
        }
        Map<String, String> headers = new HashMap<>();
        for (int index = 1; index < lines.length - 2; index += 1) {
            String line = lines[index];
            if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new IOException("invalid folded websocket upgrade header");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("invalid websocket upgrade header");
            }
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")) {
                throw new IOException("invalid websocket upgrade header name");
            }
            String value = line.substring(colon + 1).trim();
            if (headers.put(name, value) != null) {
                throw new IOException("duplicate websocket upgrade header");
            }
        }
        return new UpgradeRequest(request[0], request[1], request[2], headers);
    }

    private static boolean headerContainsToken(String value, String expected) {
        if (value == null) {
            return false;
        }
        for (String token : value.split(",")) {
            if (expected.equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private static void requireKnownOpcode(int opcode) throws IOException {
        if (opcode != OPCODE_CONTINUATION
                && opcode != OPCODE_TEXT
                && opcode != OPCODE_BINARY
                && opcode != OPCODE_CLOSE
                && opcode != OPCODE_PING
                && opcode != OPCODE_PONG) {
            throw new IOException("reserved websocket opcode");
        }
    }

    private static boolean isControl(int opcode) {
        return (opcode & 0x8) != 0;
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("unexpected end of websocket frame");
        }
        return value;
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] output = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(output, offset, length - offset);
            if (count < 0) {
                throw new EOFException("unexpected end of websocket frame");
            }
            if (count == 0) {
                continue;
            }
            offset += count;
        }
        return output;
    }

    private static String decodeUtf8(byte[] payload) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("invalid websocket UTF-8", invalid);
        }
    }

    private static boolean validCloseCode(int code) {
        if (code == 1000 || code == 1001 || code == 1002 || code == 1003
                || code == 1007 || code == 1008 || code == 1009 || code == 1010
                || code == 1011) {
            return true;
        }
        return code >= 3000 && code <= 4999;
    }

    public static final class Frame {
        public final boolean fin;
        public final int opcode;
        public final byte[] payload;

        public Frame(boolean fin, int opcode, byte[] payload) {
            if (payload == null) {
                throw new IllegalArgumentException("payload is required");
            }
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload.clone();
        }
    }

    public static final class UpgradeRequest {
        public final String method;
        public final String target;
        public final String httpVersion;
        public final Map<String, String> headers;

        private UpgradeRequest(
                String method,
                String target,
                String httpVersion,
                Map<String, String> headers) {
            this.method = method;
            this.target = target;
            this.httpVersion = httpVersion;
            this.headers = new HashMap<>(headers);
        }
    }

    public static final class Event {
        public enum Kind { NONE, TEXT, BINARY, CLOSE, PING, PONG }

        public final Kind kind;
        public final byte[] payload;
        public final String text;
        public final int closeCode;

        private Event(Kind kind, byte[] payload, String text, int closeCode) {
            this.kind = kind;
            this.payload = payload.clone();
            this.text = text;
            this.closeCode = closeCode;
        }

        private static Event none() {
            return new Event(Kind.NONE, new byte[0], null, -1);
        }
    }

    /** Stateful message assembly; control frames may interleave fragmented data messages. */
    public static final class MessageAssembler {
        private final int maxMessageBytes;
        private int fragmentedOpcode = -1;
        private ByteArrayOutputStream fragments;

        public MessageAssembler(int maxMessageBytes) {
            if (maxMessageBytes < 0) {
                throw new IllegalArgumentException("maxMessageBytes must be non-negative");
            }
            this.maxMessageBytes = maxMessageBytes;
        }

        public Event accept(Frame frame) throws IOException {
            requireKnownOpcode(frame.opcode);
            if (isControl(frame.opcode)) {
                if (!frame.fin || frame.payload.length > 125) {
                    throw new IOException("invalid websocket control frame");
                }
                if (frame.opcode == OPCODE_CLOSE) {
                    return closeEvent(frame.payload);
                }
                return new Event(
                        frame.opcode == OPCODE_PING ? Event.Kind.PING : Event.Kind.PONG,
                        frame.payload,
                        null,
                        -1);
            }

            if (frame.opcode == OPCODE_CONTINUATION) {
                if (fragmentedOpcode < 0 || fragments == null) {
                    throw new IOException("unexpected websocket continuation");
                }
                appendBounded(fragments, frame.payload);
                if (!frame.fin) {
                    return Event.none();
                }
                byte[] payload = fragments.toByteArray();
                int opcode = fragmentedOpcode;
                fragmentedOpcode = -1;
                fragments = null;
                return dataEvent(opcode, payload);
            }

            if (fragmentedOpcode >= 0) {
                throw new IOException("new websocket data frame during fragmented message");
            }
            if (frame.payload.length > maxMessageBytes) {
                throw new IOException("websocket message exceeds configured bound");
            }
            if (frame.fin) {
                return dataEvent(frame.opcode, frame.payload);
            }
            fragmentedOpcode = frame.opcode;
            fragments = new ByteArrayOutputStream(Math.min(frame.payload.length, 8192));
            appendBounded(fragments, frame.payload);
            return Event.none();
        }

        private void appendBounded(ByteArrayOutputStream destination, byte[] payload)
                throws IOException {
            if (payload.length > maxMessageBytes - destination.size()) {
                fragmentedOpcode = -1;
                fragments = null;
                throw new IOException("websocket message exceeds configured bound");
            }
            destination.write(payload, 0, payload.length);
        }

        private static Event dataEvent(int opcode, byte[] payload) throws IOException {
            if (opcode == OPCODE_TEXT) {
                return new Event(Event.Kind.TEXT, payload, decodeUtf8(payload), -1);
            }
            if (opcode == OPCODE_BINARY) {
                return new Event(Event.Kind.BINARY, payload, null, -1);
            }
            throw new IOException("invalid websocket data opcode");
        }

        private static Event closeEvent(byte[] payload) throws IOException {
            if (payload.length == 1) {
                throw new IOException("websocket close payload cannot contain one byte");
            }
            if (payload.length == 0) {
                return new Event(Event.Kind.CLOSE, payload, "", -1);
            }
            int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            if (!validCloseCode(code)) {
                throw new IOException("invalid websocket close code");
            }
            byte[] reasonBytes = new byte[payload.length - 2];
            System.arraycopy(payload, 2, reasonBytes, 0, reasonBytes.length);
            return new Event(Event.Kind.CLOSE, payload, decodeUtf8(reasonBytes), code);
        }
    }
}
