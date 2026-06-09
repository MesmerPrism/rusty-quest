package io.github.mesmerprism.rustymanifold.broker;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class LocalManifoldBrokerServer {
    public static final int PORT = 8765;
    public static final String EVENTS_PATH = "/manifold/v1/events";
    private static final String COMMAND_SCHEMA = "rusty.manifold.command.envelope.v1";
    private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final LocalManifoldBrokerServer INSTANCE = new LocalManifoldBrokerServer();

    private final Object lock = new Object();
    private boolean started;
    private ServerSocket serverSocket;

    private LocalManifoldBrokerServer() {
    }

    public static LocalManifoldBrokerServer get() {
        return INSTANCE;
    }

    public boolean isRunning() {
        synchronized (lock) {
            return started && serverSocket != null && !serverSocket.isClosed();
        }
    }

    public void start() {
        synchronized (lock) {
            if (started) {
                return;
            }
            started = true;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runServer();
                }
            }, "rusty-manifold-broker");
            thread.start();
        }
    }

    private void runServer() {
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
            synchronized (lock) {
                serverSocket = socket;
            }
            while (!socket.isClosed()) {
                Socket client = socket.accept();
                Thread session = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(client);
                    }
                }, "rusty-manifold-broker-client");
                session.start();
            }
        } catch (IOException ex) {
            synchronized (lock) {
                started = false;
            }
        }
    }

    private void handleClient(Socket client) {
        try (Socket socket = client) {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            Handshake handshake = readHandshake(input);
            if (!EVENTS_PATH.equals(handshake.path)) {
                writeHttp(output, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            String key = handshake.headers.get("sec-websocket-key");
            if (key == null || key.isEmpty()) {
                writeHttp(output, "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            writeWebSocketAccept(output, key);
            while (!socket.isClosed()) {
                Frame frame = readFrame(input);
                if (frame == null || frame.opcode == 0x8) {
                    return;
                }
                if (frame.opcode == 0x9) {
                    writeFrame(output, frame.payload, 0xA);
                    continue;
                }
                if (frame.opcode != 0x1) {
                    continue;
                }
                handleTextFrame(output, new String(frame.payload, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Client disconnects and malformed probes are expected during readiness polling.
        }
    }

    private void handleTextFrame(OutputStream output, String text) throws IOException, JSONException {
        JSONObject message = new JSONObject(text);
        String type = message.optString("type", "");
        if ("hello".equals(type)) {
            JSONObject reply = new JSONObject();
            reply.put("type", "hello_ack");
            reply.put("schema", "rusty.manifold.broker.hello_ack.v1");
            reply.put("accepted", true);
            reply.put("authority", "rusty.manifold");
            reply.put("server_id", "rusty.quest.manifold_broker_android");
            reply.put("endpoint_path", EVENTS_PATH);
            reply.put("time_utc", Instant.now().toString());
            writeText(output, reply);
            return;
        }

        if ("command".equals(type) || COMMAND_SCHEMA.equals(message.optString("schema", ""))) {
            JSONObject reply = new JSONObject();
            String command = message.optString("command", "unknown");
            reply.put("type", "command_ack");
            reply.put("schema", "rusty.manifold.command.ack.v1");
            reply.put("request_id", message.optString("request_id", ""));
            reply.put("command", command);
            reply.put("accepted", true);
            reply.put("status", "accepted");
            reply.put("authority", "rusty.manifold");
            reply.put("live_stream_events_synthesized", false);
            reply.put("time_utc", Instant.now().toString());
            writeText(output, reply);
            return;
        }

        JSONObject reply = new JSONObject();
        reply.put("type", "message_ack");
        reply.put("accepted", true);
        reply.put("authority", "rusty.manifold");
        writeText(output, reply);
    }

    private static Handshake readHandshake(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.US_ASCII));
        String request = reader.readLine();
        if (request == null) {
            throw new IOException("missing HTTP request line");
        }
        String[] requestParts = request.split(" ");
        String path = requestParts.length >= 2 ? requestParts[1] : "";
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(
                        line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        return new Handshake(path, headers);
    }

    private static void writeHttp(OutputStream output, String response) throws IOException {
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void writeWebSocketAccept(OutputStream output, String key) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] sha1 = digest.digest((key + ACCEPT_GUID).getBytes(StandardCharsets.US_ASCII));
        String accept = Base64.encodeToString(sha1, Base64.NO_WRAP);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n";
        writeHttp(output, response);
    }

    private static Frame readFrame(InputStream input) throws IOException {
        int first = input.read();
        if (first < 0) {
            return null;
        }
        int second = readByte(input);
        int opcode = first & 0x0F;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        if (length == 126) {
            length = ((long) readByte(input) << 8) | readByte(input);
        } else if (length == 127) {
            length = 0;
            for (int index = 0; index < 8; index++) {
                length = (length << 8) | readByte(input);
            }
        }
        if (length > 1024 * 1024) {
            throw new IOException("websocket frame too large");
        }
        byte[] mask = masked ? readBytes(input, 4) : new byte[0];
        byte[] payload = readBytes(input, (int) length);
        if (masked) {
            for (int index = 0; index < payload.length; index++) {
                payload[index] = (byte) (payload[index] ^ mask[index % 4]);
            }
        }
        return new Frame(opcode, payload);
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new IOException("unexpected EOF");
        }
        return value;
    }

    private static byte[] readBytes(InputStream input, int size) throws IOException {
        byte[] bytes = new byte[size];
        int offset = 0;
        while (offset < size) {
            int read = input.read(bytes, offset, size - offset);
            if (read < 0) {
                throw new IOException("unexpected EOF");
            }
            offset += read;
        }
        return bytes;
    }

    private static void writeText(OutputStream output, JSONObject object) throws IOException {
        writeFrame(output, object.toString().getBytes(StandardCharsets.UTF_8), 0x1);
    }

    private static void writeFrame(OutputStream output, byte[] payload, int opcode) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | (opcode & 0x0F));
        int length = payload.length;
        if (length < 126) {
            frame.write(length);
        } else if (length <= 0xFFFF) {
            frame.write(126);
            frame.write((length >> 8) & 0xFF);
            frame.write(length & 0xFF);
        } else {
            frame.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame.write((length >> shift) & 0xFF);
            }
        }
        frame.write(payload);
        output.write(frame.toByteArray());
        output.flush();
    }

    private static final class Handshake {
        final String path;
        final Map<String, String> headers;

        Handshake(String path, Map<String, String> headers) {
            this.path = path;
            this.headers = headers;
        }
    }

    private static final class Frame {
        final int opcode;
        final byte[] payload;

        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }
}
