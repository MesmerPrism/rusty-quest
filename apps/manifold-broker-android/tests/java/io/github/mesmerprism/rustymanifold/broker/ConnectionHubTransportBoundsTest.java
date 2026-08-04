package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Host conformance for absolute deadlines and RFC6455 frame/output bounds. */
public final class ConnectionHubTransportBoundsTest {
    public static void main(String[] args) throws Exception {
        ConnectionHubHttpServer.validateOutboundFrame(
                1, ConnectionHubProtocol.MAX_SOCKET_OUTBOUND_FRAME_BYTES);
        expectIo(new IoAction() {
            @Override public void run() throws Exception {
                ConnectionHubHttpServer.validateOutboundFrame(
                        1, ConnectionHubProtocol.MAX_SOCKET_OUTBOUND_FRAME_BYTES + 1);
            }
        });
        expectIo(new IoAction() {
            @Override public void run() throws Exception {
                ConnectionHubHttpServer.validateOutboundFrame(9, 126);
            }
        });

        ConnectionHubHttpServer.Frame ping = ConnectionHubHttpServer.readFrame(
                new ByteArrayInputStream(maskedFrame(9, 125)),
                ConnectionHubHttpServer.deadlineAfterMs(1000),
                false);
        require(ping.opcode == 9 && ping.payload.length == 125, "valid ping rejected");

        expectFrameRejected(maskedFrame(9, 126));
        expectFrameRejected(maskedFrame(
                1, ConnectionHubProtocol.MAX_SOCKET_FRAME_BYTES + 1));
        byte[] fragmented = maskedFrame(1, 0);
        fragmented[0] = 0x01;
        expectFrameRejected(fragmented);

        require(ConnectionHubHttpServer.isNewerTransportEpoch(3, 2),
                "new transport epoch was rejected");
        require(!ConnectionHubHttpServer.isNewerTransportEpoch(2, 3),
                "out-of-order older handshake displaced the newer socket");
        require(!ConnectionHubHttpServer.isNewerTransportEpoch(3, 3),
                "duplicate transport epoch displaced the installed socket");

        JSONObject authentication = new JSONObject()
                .put("type", "authentication_receipt");
        require(ConnectionHubHttpServer.bindOutboundSurfaceRevision(authentication, -1) == -1,
                "authentication receipt unexpectedly changed the surface watermark");
        JSONObject lifecycle = new JSONObject()
                .put("type", "surface_state")
                .put("surface_revision", 8);
        require(ConnectionHubHttpServer.bindOutboundSurfaceRevision(lifecycle, 7) == 8,
                "increasing lifecycle revision was rejected");
        JSONObject equal = new JSONObject()
                .put("type", "surface_available")
                .put("surface_revision", 8);
        require(ConnectionHubHttpServer.bindOutboundSurfaceRevision(equal, 8) == 8,
                "equal lifecycle watermark was rejected");
        for (String controlType : new String[] {
                "keepalive_receipt", "command_receipt", "protocol_error"}) {
            JSONObject delayedControl = new JSONObject()
                    .put("type", controlType)
                    .put("surface_revision", 7);
            require(ConnectionHubHttpServer.bindOutboundSurfaceRevision(delayedControl, 8) == 8,
                    "delayed control receipt did not retain the queued watermark");
            require(delayedControl.getLong("surface_revision") == 8,
                    "delayed control receipt serialized a regressed revision");
            JSONObject futureSampledControl = new JSONObject()
                    .put("type", controlType)
                    .put("surface_revision", 11);
            expectIo(new IoAction() {
                @Override public void run() throws Exception {
                    ConnectionHubHttpServer.bindOutboundSurfaceRevision(
                            futureSampledControl, 8);
                }
            });
        }
        expectIo(new IoAction() {
            @Override public void run() throws Exception {
                ConnectionHubHttpServer.bindOutboundSurfaceRevision(
                        new JSONObject()
                                .put("type", "surface_removed")
                                .put("surface_revision", 7),
                        8);
            }
        });

        final Object registryLock = new Object();
        final CountDownLatch baselineEntered = new CountDownLatch(1);
        final CountDownLatch allowSubscription = new CountDownLatch(1);
        final CountDownLatch mutationStarted = new CountDownLatch(1);
        final CountDownLatch mutationEntered = new CountDownLatch(1);
        final AtomicBoolean subscribed = new AtomicBoolean();
        final AtomicBoolean mutationBeatSubscription = new AtomicBoolean();
        final AtomicReference<Throwable> installFailure = new AtomicReference<>();
        Thread installer = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ConnectionHubHttpServer.installBaselineAndSubscribe(
                            registryLock,
                            new ConnectionHubHttpServer.BaselineSubscription() {
                                @Override public void enqueueBaseline() throws IOException {
                                    baselineEntered.countDown();
                                    try {
                                        if (!allowSubscription.await(2, TimeUnit.SECONDS)) {
                                            throw new IOException("subscription test timed out");
                                        }
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                        throw new IOException("subscription test interrupted", interrupted);
                                    }
                                }
                                @Override public void subscribe() {
                                    subscribed.set(true);
                                }
                            });
                } catch (Throwable failure) {
                    installFailure.set(failure);
                }
            }
        }, "hub-baseline-install-test");
        installer.start();
        require(baselineEntered.await(1, TimeUnit.SECONDS),
                "baseline installation did not enter its registry boundary");
        Thread mutation = new Thread(new Runnable() {
            @Override public void run() {
                mutationStarted.countDown();
                synchronized (registryLock) {
                    mutationBeatSubscription.set(!subscribed.get());
                    mutationEntered.countDown();
                }
            }
        }, "hub-concurrent-mutation-test");
        mutation.start();
        require(mutationStarted.await(1, TimeUnit.SECONDS),
                "concurrent mutation did not start");
        require(!mutationEntered.await(100, TimeUnit.MILLISECONDS),
                "mutation entered between baseline and subscription");
        allowSubscription.countDown();
        installer.join(2000);
        mutation.join(2000);
        require(!installer.isAlive() && !mutation.isAlive(),
                "baseline subscription concurrency test did not terminate");
        require(installFailure.get() == null,
                "baseline subscription failed: " + installFailure.get());
        require(subscribed.get() && !mutationBeatSubscription.get(),
                "mutation was not serialized after subscription");

        long started = System.nanoTime();
        try {
            ConnectionHubHttpServer.readRequest(
                    new AlwaysTimeoutInputStream(),
                    ConnectionHubHttpServer.deadlineAfterMs(5));
            throw new AssertionError("stalled header accepted");
        } catch (SocketTimeoutException expected) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            require(elapsedMs < 1000, "absolute header deadline did not terminate");
        }
        System.out.println("Connection Hub transport bounds passed");
    }

    private static void expectFrameRejected(final byte[] bytes) throws Exception {
        expectIo(new IoAction() {
            @Override public void run() throws Exception {
                ConnectionHubHttpServer.readFrame(
                        new ByteArrayInputStream(bytes),
                        ConnectionHubHttpServer.deadlineAfterMs(1000),
                        false);
            }
        });
    }

    private static byte[] maskedFrame(int opcode, int length) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x80 | opcode);
        if (length < 126) {
            output.write(0x80 | length);
        } else if (length <= 0xffff) {
            output.write(0x80 | 126);
            output.write((length >>> 8) & 0xff);
            output.write(length & 0xff);
        } else {
            output.write(0x80 | 127);
            long extended = length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                output.write((int) ((extended >>> shift) & 0xff));
            }
        }
        byte[] mask = new byte[] {1, 2, 3, 4};
        output.write(mask);
        for (int index = 0; index < length; index += 1) {
            output.write(mask[index % mask.length]);
        }
        return output.toByteArray();
    }

    private static void expectIo(IoAction action) throws Exception {
        try { action.run(); throw new AssertionError("expected IOException"); }
        catch (IOException expected) { }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private interface IoAction { void run() throws Exception; }

    private static final class AlwaysTimeoutInputStream extends InputStream {
        @Override public int read() throws IOException { throw new SocketTimeoutException("poll"); }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            throw new SocketTimeoutException("poll");
        }
    }
}
