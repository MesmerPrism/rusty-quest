package io.github.mesmerprism.rustyquest.broker_transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class Rfc6455CodecTest {
    public static void main(String[] args) throws Exception {
        testUpgradeValidation();
        testUpgradeParsing();
        testMaskedPartialRead();
        testMaskDirectionAndCanonicalLengths();
        testExtendedLengths();
        testFragmentationWithControlInterleave();
        testUtf8AndCloseValidation();
        testMessageBound();
        System.out.println("Rfc6455CodecTest PASS");
    }

    private static void testUpgradeParsing() throws Exception {
        String request = "GET /socket HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
        Rfc6455Codec.UpgradeRequest parsed = Rfc6455Codec.readUpgradeRequest(
                new OneByteInputStream(request.getBytes(StandardCharsets.ISO_8859_1)), 1024);
        assertEquals("GET", parsed.method, "upgrade method");
        assertEquals("/socket", parsed.target, "upgrade target");
        assertEquals("HTTP/1.1", parsed.httpVersion, "upgrade version");
        assertEquals("websocket", parsed.headers.get("upgrade"), "upgrade header");
        assertEquals(
                "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                Rfc6455Codec.serverAccept(
                        parsed.method, parsed.httpVersion, parsed.headers),
                "parsed upgrade accept");

        final String duplicate = "GET /socket HTTP/1.1\r\n"
                + "Upgrade: websocket\r\nUpgrade: websocket\r\n\r\n";
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                Rfc6455Codec.readUpgradeRequest(
                        new ByteArrayInputStream(duplicate.getBytes(StandardCharsets.ISO_8859_1)),
                        256);
            }
        }, "duplicate upgrade header");
        final String folded = "GET /socket HTTP/1.1\r\n Upgrade: websocket\r\n\r\n";
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                Rfc6455Codec.readUpgradeRequest(
                        new ByteArrayInputStream(folded.getBytes(StandardCharsets.ISO_8859_1)),
                        256);
            }
        }, "folded upgrade header");
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                Rfc6455Codec.readUpgradeRequest(
                        new ByteArrayInputStream(request.getBytes(StandardCharsets.ISO_8859_1)),
                        32);
            }
        }, "upgrade header byte bound");
    }

    private static void testUpgradeValidation() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("upgrade", "websocket");
        headers.put("connection", "keep-alive, Upgrade");
        headers.put("sec-websocket-version", "13");
        headers.put("sec-websocket-key", "dGhlIHNhbXBsZSBub25jZQ==");
        assertEquals(
                "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                Rfc6455Codec.serverAccept("GET", "HTTP/1.1", headers),
                "RFC6455 accept vector");
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                Rfc6455Codec.serverAccept("POST", "HTTP/1.1", headers);
            }
        }, "non-GET upgrade");
        headers.put("sec-websocket-version", "12");
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                Rfc6455Codec.serverAccept("GET", "HTTP/1.1", headers);
            }
        }, "wrong websocket version");
    }

    private static void testMaskedPartialRead() throws Exception {
        byte[] encoded = encode(true, Rfc6455Codec.OPCODE_TEXT,
                "hello".getBytes(StandardCharsets.UTF_8), new byte[] {1, 2, 3, 4});
        Rfc6455Codec.Frame frame = Rfc6455Codec.readFrame(
                new OneByteInputStream(encoded), true, 32);
        assertTrue(frame.fin, "masked text FIN");
        assertEquals(Rfc6455Codec.OPCODE_TEXT, frame.opcode, "masked text opcode");
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), frame.payload,
                "masked partial read payload");
    }

    private static void testMaskDirectionAndCanonicalLengths() throws Exception {
        final byte[] masked = encode(true, Rfc6455Codec.OPCODE_TEXT,
                new byte[] {1}, new byte[] {9, 8, 7, 6});
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                Rfc6455Codec.readFrame(new ByteArrayInputStream(masked), false, 8);
            }
        }, "masked server frame");
        final byte[] unmasked = encode(true, Rfc6455Codec.OPCODE_TEXT,
                new byte[] {1}, null);
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                Rfc6455Codec.readFrame(new ByteArrayInputStream(unmasked), true, 8);
            }
        }, "unmasked client frame");
        final byte[] noncanonical16 = new byte[] {(byte) 0x81, 126, 0, 125};
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                Rfc6455Codec.readFrame(new ByteArrayInputStream(noncanonical16), false, 256);
            }
        }, "noncanonical 16-bit length");
        final byte[] noncanonical64 = new byte[] {
                (byte) 0x81, 127, 0, 0, 0, 0, 0, 0, 0, 126
        };
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                Rfc6455Codec.readFrame(new ByteArrayInputStream(noncanonical64), false, 256);
            }
        }, "noncanonical 64-bit length");
        final byte[] negative64 = new byte[] {
                (byte) 0x81, 127, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0
        };
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                Rfc6455Codec.readFrame(new ByteArrayInputStream(negative64), false, 256);
            }
        }, "signed 64-bit overflow");
    }

    private static void testExtendedLengths() throws Exception {
        int[] lengths = new int[] {125, 126, 65535, 65536};
        for (int length : lengths) {
            byte[] payload = new byte[length];
            payload[0] = 7;
            payload[length - 1] = 11;
            byte[] encoded = encode(true, Rfc6455Codec.OPCODE_BINARY, payload, null);
            Rfc6455Codec.Frame frame = Rfc6455Codec.readFrame(
                    new OneByteInputStream(encoded), false, 65536);
            assertEquals(length, frame.payload.length, "extended payload length");
            assertEquals(7, frame.payload[0] & 0xFF, "extended payload first byte");
            assertEquals(11, frame.payload[length - 1] & 0xFF, "extended payload last byte");
        }
    }

    private static void testFragmentationWithControlInterleave() throws Exception {
        Rfc6455Codec.MessageAssembler assembler = new Rfc6455Codec.MessageAssembler(64);
        Rfc6455Codec.Event first = assembler.accept(new Rfc6455Codec.Frame(
                false, Rfc6455Codec.OPCODE_TEXT, "hello ".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Rfc6455Codec.Event.Kind.NONE, first.kind, "fragment start");
        Rfc6455Codec.Event ping = assembler.accept(new Rfc6455Codec.Frame(
                true, Rfc6455Codec.OPCODE_PING, new byte[] {4, 2}));
        assertEquals(Rfc6455Codec.Event.Kind.PING, ping.kind, "interleaved ping");
        byte[] euro = "world €".getBytes(StandardCharsets.UTF_8);
        Rfc6455Codec.Event middle = assembler.accept(new Rfc6455Codec.Frame(
                false, Rfc6455Codec.OPCODE_CONTINUATION,
                new byte[] {euro[0], euro[1], euro[2], euro[3], euro[4], euro[5], euro[6]}));
        assertEquals(Rfc6455Codec.Event.Kind.NONE, middle.kind, "fragment middle");
        Rfc6455Codec.Event complete = assembler.accept(new Rfc6455Codec.Frame(
                true, Rfc6455Codec.OPCODE_CONTINUATION,
                new byte[] {euro[7], euro[8]}));
        assertEquals(Rfc6455Codec.Event.Kind.TEXT, complete.kind, "fragment completion");
        assertEquals("hello world €", complete.text, "fragmented UTF-8 text");
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                new Rfc6455Codec.MessageAssembler(8).accept(new Rfc6455Codec.Frame(
                        true, Rfc6455Codec.OPCODE_CONTINUATION, new byte[0]));
            }
        }, "unexpected continuation");
    }

    private static void testUtf8AndCloseValidation() throws Exception {
        final Rfc6455Codec.MessageAssembler invalidText =
                new Rfc6455Codec.MessageAssembler(16);
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                invalidText.accept(new Rfc6455Codec.Frame(
                        true, Rfc6455Codec.OPCODE_TEXT,
                        new byte[] {(byte) 0xC3, 0x28}));
            }
        }, "invalid UTF-8");
        Rfc6455Codec.MessageAssembler closes = new Rfc6455Codec.MessageAssembler(16);
        Rfc6455Codec.Event close = closes.accept(new Rfc6455Codec.Frame(
                true, Rfc6455Codec.OPCODE_CLOSE,
                new byte[] {0x03, (byte) 0xE8, 'b', 'y', 'e'}));
        assertEquals(Rfc6455Codec.Event.Kind.CLOSE, close.kind, "close event");
        assertEquals(1000, close.closeCode, "close code");
        assertEquals("bye", close.text, "close reason");
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                new Rfc6455Codec.MessageAssembler(16).accept(new Rfc6455Codec.Frame(
                        true, Rfc6455Codec.OPCODE_CLOSE, new byte[] {0x03}));
            }
        }, "one-byte close payload");
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                new Rfc6455Codec.MessageAssembler(16).accept(new Rfc6455Codec.Frame(
                        true, Rfc6455Codec.OPCODE_CLOSE,
                        new byte[] {0x03, (byte) 0xEE}));
            }
        }, "reserved close code");
    }

    private static void testMessageBound() throws Exception {
        final Rfc6455Codec.MessageAssembler assembler =
                new Rfc6455Codec.MessageAssembler(5);
        assembler.accept(new Rfc6455Codec.Frame(
                false, Rfc6455Codec.OPCODE_BINARY, new byte[] {1, 2, 3}));
        expectIOException(new ThrowingRunnable() {
            @Override public void run() throws Exception {
                assembler.accept(new Rfc6455Codec.Frame(
                        true, Rfc6455Codec.OPCODE_CONTINUATION, new byte[] {4, 5, 6}));
            }
        }, "fragmented message bound");
    }

    private static byte[] encode(boolean fin, int opcode, byte[] payload, byte[] mask)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Rfc6455Codec.writeFrame(output, fin, opcode, payload, mask);
        return output.toByteArray();
    }

    private static void expectIOException(ThrowingRunnable action, String label)
            throws Exception {
        boolean rejected = false;
        try {
            action.run();
        } catch (IOException expected) {
            rejected = true;
        }
        assertTrue(rejected, label + " must reject");
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual, String label) {
        if (expected.length != actual.length) {
            throw new AssertionError(label + ": length mismatch");
        }
        for (int index = 0; index < expected.length; index += 1) {
            if (expected[index] != actual[index]) {
                throw new AssertionError(label + ": byte mismatch at " + index);
            }
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class OneByteInputStream extends InputStream {
        private final ByteArrayInputStream delegate;

        OneByteInputStream(byte[] value) {
            delegate = new ByteArrayInputStream(value);
        }

        @Override public int read() {
            return delegate.read();
        }

        @Override public int read(byte[] value, int offset, int length) {
            return delegate.read(value, offset, Math.min(length, 1));
        }
    }
}
