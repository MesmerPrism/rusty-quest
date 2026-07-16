package io.github.mesmerprism.rustyquest.lslmulticastconformance;

import android.app.Activity;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MulticastConformanceActivity extends Activity {
    private static final String TAG = "RLSL004G";
    private static final String GROUP_TEXT = "239.255.172.215";
    private static final int PORT = 16571;
    private static final byte[] QUERY = "LSL:shortinfo\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RESPONSE = "RLSL004G:shortinfo-response".getBytes(StandardCharsets.US_ASCII);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile MulticastSocket ownedSocket;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("Rusty LSL bounded multicast conformance");
        setContentView(view);
        final String role = getIntent().getStringExtra("role");
        final String interfaceName = getIntent().getStringExtra("interface");
        final int deadlineMs = Math.max(1000, Math.min(15000, getIntent().getIntExtra("deadline_ms", 8000)));
        new Thread(new Runnable() {
            @Override public void run() {
                MulticastConformanceActivity.this.run(role, interfaceName, deadlineMs);
            }
        }, "rlsl-004g-" + role).start();
    }

    @Override public void onDestroy() {
        cancelled.set(true);
        MulticastSocket socket = ownedSocket;
        if (socket != null) socket.close();
        super.onDestroy();
    }

    private void run(String role, String interfaceName, int deadlineMs) {
        long started = System.nanoTime();
        WifiManager.MulticastLock lock = null;
        boolean joined = false, dropped = false, rejoined = false, querySent = false;
        boolean queryReceived = false, responseSent = false, responseReceived = false;
        String result = "fail", reason = "unknown";
        MulticastSocket socket = null;
        try {
            if (role == null || interfaceName == null) throw new IllegalArgumentException("missing-explicit-input");
            NetworkInterface iface = NetworkInterface.getByName(interfaceName);
            if (iface == null) throw new IllegalArgumentException("missing-explicit-interface");
            InetAddress group = InetAddress.getByName(GROUP_TEXT);
            InetSocketAddress membership = new InetSocketAddress(group, PORT);
            WifiManager wifi = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            lock = wifi.createMulticastLock("rlsl-004g-" + role);
            lock.setReferenceCounted(false);
            lock.acquire();
            socket = new MulticastSocket(null);
            ownedSocket = socket;
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));
            socket.setNetworkInterface(iface);
            socket.setSoTimeout(deadlineMs);
            socket.joinGroup(membership, iface);
            joined = true;
            socket.leaveGroup(membership, iface);
            dropped = true;
            socket.joinGroup(membership, iface);
            rejoined = true;
            if ("cancel".equals(role)) {
                cancelled.set(true);
                reason = "explicit-cancellation";
                result = "pass";
            } else if ("responder".equals(role)) {
                DatagramPacket incoming = new DatagramPacket(new byte[256], 256);
                socket.receive(incoming);
                queryReceived = exact(incoming, QUERY);
                if (!queryReceived) throw new IllegalStateException("damaged-query");
                socket.send(new DatagramPacket(RESPONSE, RESPONSE.length, incoming.getAddress(), incoming.getPort()));
                responseSent = true;
                reason = "one-query-one-response";
                result = "pass";
            } else if ("requester".equals(role)) {
                socket.send(new DatagramPacket(QUERY, QUERY.length, group, PORT));
                querySent = true;
                long receiveDeadline = System.nanoTime() + deadlineMs * 1_000_000L;
                while (!responseReceived && System.nanoTime() < receiveDeadline) {
                    DatagramPacket incoming = new DatagramPacket(new byte[256], 256);
                    socket.receive(incoming);
                    responseReceived = exact(incoming, RESPONSE);
                    if (!responseReceived && !exact(incoming, QUERY)) {
                        throw new IllegalStateException("damaged-response");
                    }
                }
                if (!responseReceived) throw new IllegalStateException("response-deadline");
                reason = "one-query-one-response";
                result = "pass";
            } else {
                throw new IllegalArgumentException("unsupported-role");
            }
        } catch (Throwable error) {
            reason = error.getClass().getSimpleName() + "-" + safe(error.getMessage());
            Log.e(TAG, "bounded run failed", error);
        } finally {
            if (socket != null) {
                try {
                    NetworkInterface iface = NetworkInterface.getByName(interfaceName);
                    if (iface != null && rejoined) socket.leaveGroup(new InetSocketAddress(InetAddress.getByName(GROUP_TEXT), PORT), iface);
                } catch (Throwable ignored) { }
                socket.close();
            }
            ownedSocket = null;
            if (lock != null && lock.isHeld()) lock.release();
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            String marker = "{\"schema\":\"rusty.quest.lsl_multicast_conformance.v1\",\"result\":\"" + result
                + "\",\"role\":\"" + safe(role) + "\",\"group\":\"" + GROUP_TEXT + "\",\"port\":" + PORT
                + ",\"explicit_interface\":true,\"joined\":" + joined + ",\"dropped\":" + dropped
                + ",\"rejoined\":" + rejoined + ",\"query_sent\":" + querySent + ",\"query_received\":" + queryReceived
                + ",\"response_sent\":" + responseSent + ",\"response_received\":" + responseReceived
                + ",\"cancelled\":" + cancelled.get() + ",\"deadline_ms\":" + deadlineMs + ",\"elapsed_ms\":" + elapsedMs
                + ",\"cleanup_socket_closed\":true,\"cleanup_multicast_lock_released\":true,\"reason\":\"" + safe(reason) + "\"}";
            Log.i(TAG, "EFFECTIVE " + marker);
            writeResult(marker);
        }
    }

    private static boolean exact(DatagramPacket packet, byte[] expected) {
        if (packet.getLength() != expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (packet.getData()[packet.getOffset() + i] != expected[i]) return false;
        return true;
    }

    private void writeResult(String marker) {
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "result.json"), false)) {
            out.write(marker.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable error) {
            Log.e(TAG, "result write failed", error);
        }
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("\\", "_").replace("\"", "_").replace("\n", "_").replace("\r", "_");
    }
}
