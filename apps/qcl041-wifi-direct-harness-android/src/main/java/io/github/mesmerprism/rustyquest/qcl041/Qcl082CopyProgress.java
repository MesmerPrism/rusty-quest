package io.github.mesmerprism.rustyquest.qcl041;

import android.os.SystemClock;

final class Qcl082CopyProgress {
    long totalBytes = 0L;
    long firstByteElapsedMs = -1L;
    long lastByteElapsedMs = -1L;
    long lastByteUnixMs = -1L;
    long lastByteAgeMs = -1L;
    long currentElapsedMs = 0L;
    long bytesReadFromSource = 0L;
    long bytesWrittenToReceiver = 0L;
    long readOperations = 0L;
    long writeOperations = 0L;
    long ackWaits = 0L;
    long ackReceives = 0L;
    long ackSends = 0L;
    long ackTimeouts = 0L;
    long consecutiveAckTimeouts = 0L;
    long ackSoftTimeoutContinues = 0L;
    long receiverProgressSends = 0L;
    long receiverProgressReceives = 0L;
    long receiverProgressTimeouts = 0L;
    long receiverProgressSocketCloses = 0L;
    long copySegments = 0L;
    long sourceReconnects = 0L;
    long receiverReconnects = 0L;
    long receiveProxyAcceptedPeers = 0L;
    long receiveProxyPeerReconnects = 0L;
    long bytesSinceAck = 0L;
    long lastAckElapsedMs = -1L;
    long lastAckAgeMs = -1L;
    long lastReceiverProgressElapsedMs = -1L;
    long lastReceiverProgressAgeMs = -1L;
    long receiverProgressTimeoutMs = 0L;
    long receiverProgressWatchdogStartElapsedMs = -1L;
    long receiverProgressSegmentStartBytes = 0L;
    long lastReadElapsedMs = -1L;
    long lastWriteElapsedMs = -1L;
    long lastReadAgeMs = -1L;
    long lastWriteAgeMs = -1L;
    long writeStartElapsedMs = -1L;
    long writeInFlightAgeMs = -1L;
    long writeStallTimeoutMs = 0L;
    long writeStallSocketCloses = 0L;
    int lastReadSize = 0;
    int lastWriteSize = 0;
    int writeInFlightSize = 0;
    long timeoutPolls = 0L;
    int maxBytes = 0;
    boolean writeInFlight = false;
    String completedReason = "running";

    synchronized void noteRead(int read, long startedMs) {
        bytesReadFromSource += read;
        readOperations++;
        long elapsed = SystemClock.elapsedRealtime() - startedMs;
        currentElapsedMs = Math.max(0L, elapsed);
        lastReadElapsedMs = currentElapsedMs;
        lastReadAgeMs = 0L;
        lastReadSize = read;
    }

    synchronized void noteWriteStarted(int writeSize, long startedMs) {
        long elapsed = SystemClock.elapsedRealtime() - startedMs;
        currentElapsedMs = Math.max(0L, elapsed);
        writeInFlight = true;
        writeStartElapsedMs = currentElapsedMs;
        writeInFlightAgeMs = 0L;
        writeInFlightSize = writeSize;
    }

    synchronized void noteWrite(long total, int written, long startedMs) {
        totalBytes = total;
        bytesWrittenToReceiver += written;
        writeOperations++;
        long elapsed = SystemClock.elapsedRealtime() - startedMs;
        currentElapsedMs = Math.max(0L, elapsed);
        if (firstByteElapsedMs < 0L) {
            firstByteElapsedMs = currentElapsedMs;
        }
        lastWriteElapsedMs = currentElapsedMs;
        lastWriteAgeMs = 0L;
        lastWriteSize = written;
        writeInFlight = false;
        writeInFlightAgeMs = 0L;
        writeInFlightSize = 0;
        lastByteElapsedMs = currentElapsedMs;
        lastByteUnixMs = System.currentTimeMillis();
        lastByteAgeMs = 0L;
    }

    synchronized void noteWriteFailed(long startedMs) {
        long elapsed = SystemClock.elapsedRealtime() - startedMs;
        currentElapsedMs = Math.max(0L, elapsed);
        writeInFlight = false;
        writeInFlightAgeMs = 0L;
        writeInFlightSize = 0;
    }

    synchronized void noteWriteStallClose(long startedMs, long timeoutMs) {
        updateIdle(startedMs);
        writeStallTimeoutMs = timeoutMs;
        writeStallSocketCloses++;
        completedReason = "receiver_write_stall_timeout";
    }

    synchronized void noteAckWait() {
        ackWaits++;
    }

    synchronized void noteAckReceived(long startedMs) {
        ackReceives++;
        consecutiveAckTimeouts = 0L;
        bytesSinceAck = 0L;
        long elapsed = SystemClock.elapsedRealtime() - startedMs;
        currentElapsedMs = Math.max(0L, elapsed);
        lastAckElapsedMs = currentElapsedMs;
        lastAckAgeMs = 0L;
    }

    synchronized void noteAckSent(long startedMs) {
        ackSends++;
        bytesSinceAck = 0L;
        long elapsed = SystemClock.elapsedRealtime() - startedMs;
        currentElapsedMs = Math.max(0L, elapsed);
        lastAckElapsedMs = currentElapsedMs;
        lastAckAgeMs = 0L;
    }

    synchronized void noteReceiverProgressWatchdogStarted(long startedMs, long timeoutMs) {
        long elapsed = SystemClock.elapsedRealtime() - startedMs;
        currentElapsedMs = Math.max(0L, elapsed);
        receiverProgressTimeoutMs = timeoutMs;
        receiverProgressWatchdogStartElapsedMs = currentElapsedMs;
        receiverProgressSegmentStartBytes = totalBytes;
    }

    synchronized void noteReceiverProgressSent(long startedMs) {
        receiverProgressSends++;
        long elapsed = SystemClock.elapsedRealtime() - startedMs;
        currentElapsedMs = Math.max(0L, elapsed);
        lastReceiverProgressElapsedMs = currentElapsedMs;
        lastReceiverProgressAgeMs = 0L;
    }

    synchronized void noteReceiverProgressReceived(long startedMs) {
        receiverProgressReceives++;
        long elapsed = SystemClock.elapsedRealtime() - startedMs;
        currentElapsedMs = Math.max(0L, elapsed);
        lastReceiverProgressElapsedMs = currentElapsedMs;
        lastReceiverProgressAgeMs = 0L;
    }

    synchronized void noteReceiverProgressTimeoutClose(long startedMs, long timeoutMs, String reason) {
        updateIdle(startedMs);
        receiverProgressTimeoutMs = timeoutMs;
        receiverProgressTimeouts++;
        receiverProgressSocketCloses++;
        completedReason = reason;
    }

    synchronized void noteAckTimeout() {
        ackTimeouts++;
        consecutiveAckTimeouts++;
    }

    synchronized void updateIdle(long startedMs) {
        long elapsed = SystemClock.elapsedRealtime() - startedMs;
        currentElapsedMs = Math.max(0L, elapsed);
        lastByteAgeMs = lastByteElapsedMs >= 0L
                ? Math.max(0L, currentElapsedMs - lastByteElapsedMs)
                : -1L;
        lastReadAgeMs = lastReadElapsedMs >= 0L
                ? Math.max(0L, currentElapsedMs - lastReadElapsedMs)
                : -1L;
        lastWriteAgeMs = lastWriteElapsedMs >= 0L
                ? Math.max(0L, currentElapsedMs - lastWriteElapsedMs)
                : -1L;
        lastAckAgeMs = lastAckElapsedMs >= 0L
                ? Math.max(0L, currentElapsedMs - lastAckElapsedMs)
                : -1L;
        lastReceiverProgressAgeMs = lastReceiverProgressElapsedMs >= 0L
                ? Math.max(0L, currentElapsedMs - lastReceiverProgressElapsedMs)
                : -1L;
        writeInFlightAgeMs = writeInFlight && writeStartElapsedMs >= 0L
                ? Math.max(0L, currentElapsedMs - writeStartElapsedMs)
                : -1L;
    }

    synchronized boolean isWriteStalled(long startedMs, long timeoutMs) {
        updateIdle(startedMs);
        return timeoutMs > 0L && writeInFlight && writeInFlightAgeMs >= timeoutMs;
    }

    synchronized boolean isReceiverProgressStalled(long startedMs, long timeoutMs) {
        updateIdle(startedMs);
        if (timeoutMs <= 0L || receiverProgressWatchdogStartElapsedMs < 0L) {
            return false;
        }
        if (totalBytes <= receiverProgressSegmentStartBytes) {
            return false;
        }
        long referenceElapsedMs = receiverProgressWatchdogStartElapsedMs;
        if (lastReceiverProgressElapsedMs >= receiverProgressWatchdogStartElapsedMs) {
            referenceElapsedMs = lastReceiverProgressElapsedMs;
        }
        return currentElapsedMs - referenceElapsedMs >= timeoutMs;
    }
}
