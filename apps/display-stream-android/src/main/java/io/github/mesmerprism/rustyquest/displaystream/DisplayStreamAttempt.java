package io.github.mesmerprism.rustyquest.displaystream;

import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;

/** One retained worker incarnation. Observation timeouts never retire an owner. */
final class DisplayStreamAttempt {
    static final class UncertainReleaseException extends java.io.IOException {
        UncertainReleaseException(Throwable cause) { super("partial resource retirement unconfirmed", cause); }
    }
    interface Body { void run(DisplayStreamAttempt attempt) throws Exception; }
    interface Resource { void close() throws Exception; }
    final String invocationId, generation, stopNonce;
    private final Object lock = new Object();
    private final List<Resource> resources = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private Channel channel;
    private Thread worker;
    private boolean stopped, bodyFinished, released;

    DisplayStreamAttempt(String invocationId, String generation, String stopNonce) {
        if (!hex(invocationId) || !hex(stopNonce) || generation == null
                || !generation.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("invalid display attempt identity");
        }
        this.invocationId = invocationId;
        this.generation = generation;
        this.stopNonce = stopNonce;
    }

    static boolean hex(String value) { return value != null && value.matches("[0-9a-f]{32}"); }

    void start(Body body) {
        synchronized (lock) {
            if (worker != null) throw new IllegalStateException("attempt already started");
            worker = new Thread(() -> {
                try {
                    if (!stopRequested()) body.run(this);
                } catch (Throwable error) {
                    if (error instanceof UncertainReleaseException) fail("RESOURCE_RELEASE_FAILED");
                    // Closing this exact registered channel is the cancellation mechanism.
                    if (!(stopRequested() && error instanceof ClosedChannelException)) fail("BODY_FAILED");
                } finally {
                    List<Resource> owned;
                    synchronized (lock) { owned = new ArrayList<>(resources); }
                    boolean clean = true;
                    for (int i = owned.size() - 1; i >= 0; i--) {
                        try { owned.get(i).close(); }
                        catch (Throwable error) { clean = false; fail("RESOURCE_RELEASE_FAILED"); }
                    }
                    if (!closeChannel()) clean = false;
                    synchronized (lock) { released = clean; bodyFinished = true; lock.notifyAll(); }
                }
            }, "RustyQuestDisplayAttempt");
            worker.start();
        }
    }

    void ownChannel(Channel value) throws Exception {
        boolean reject;
        synchronized (lock) {
            if (channel != null) throw new IllegalStateException("channel already owned");
            channel = value;
            reject = stopped;
        }
        if (reject) {
            closeChannel();
            throw new ClosedChannelException();
        }
    }

    void ownResource(Resource value) throws Exception {
        boolean reject;
        synchronized (lock) {
            reject = stopped || bodyFinished;
            if (!reject) resources.add(value);
        }
        if (reject) {
            try { value.close(); }
            catch (Exception error) { fail("RESOURCE_RELEASE_FAILED"); throw error; }
            throw new ClosedChannelException();
        }
    }

    boolean requestStop(String invocation, String selectedGeneration, String nonce) {
        if (!invocationId.equals(invocation) || !generation.equals(selectedGeneration)
                || !stopNonce.equals(nonce)) return false;
        stopOwned();
        return true;
    }

    /** Used only by this attempt's host-loss/Android lifetime, never another owner. */
    void stopOwned() {
        synchronized (lock) { if (stopped) return; stopped = true; }
        closeChannel(); // Never hold the owner monitor across a platform callback.
    }

    boolean stopRequested() { synchronized (lock) { return stopped; } }

    private boolean closeChannel() {
        Channel owned;
        synchronized (lock) { owned = channel; }
        try {
            if (owned != null) owned.close();
            return true;
        } catch (Exception error) { fail("CHANNEL_CLOSE_FAILED"); return false; }
    }

    private void fail(String code) { synchronized (lock) { failures.add(code); } }

    Snapshot snapshot() {
        synchronized (lock) {
            boolean exited = worker != null && bodyFinished && !worker.isAlive();
            return new Snapshot(exited, exited && released && !failures.contains("RESOURCE_RELEASE_FAILED")
                    && !failures.contains("CHANNEL_CLOSE_FAILED"), stopped, new ArrayList<>(failures));
        }
    }

    Snapshot awaitRetirement(long observationMillis) throws InterruptedException {
        Thread owned;
        synchronized (lock) { owned = worker; }
        if (owned == Thread.currentThread()) throw new IllegalStateException("worker cannot attest its own exit");
        if (owned != null) {
            if (observationMillis == 0) owned.join();
            else owned.join(Math.max(1, observationMillis));
        }
        return snapshot();
    }

    static final class Snapshot {
        final boolean workerRetired, resourcesRetired, stopRequested;
        final List<String> failures;
        Snapshot(boolean worker, boolean resources, boolean stop, List<String> failures) {
            workerRetired = worker; resourcesRetired = resources; stopRequested = stop;
            this.failures = java.util.Collections.unmodifiableList(failures);
        }
        boolean retired() { return workerRetired && resourcesRetired; }
        boolean successful() { return retired() && failures.isEmpty(); }
    }
}
