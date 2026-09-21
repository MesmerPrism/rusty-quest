package io.github.mesmerprism.rustyquest.native_renderer;

/** Pure ordering policy for terminal kiosk exit and recorder shutdown. */
final class NativeRendererWriterAcknowledgedExitPolicy {
    enum Action {
        NONE,
        REQUEST_WRITER_STOP,
        WAIT_FOR_WRITER_ACK,
        FINISH_AND_REMOVE_TASK
    }

    private enum State {
        RUNNING,
        STOP_REQUESTED,
        WRITER_ACKNOWLEDGED,
        FINISH_DISPATCHED
    }

    private State state = State.RUNNING;
    private boolean guardDisarmed;
    private boolean recoveryAllowed = true;
    private long sessionGeneration;
    private String stopOperationId = "";
    private String writerReceiptId = "";
    private boolean writerCompletedCleanly;

    Action beginExit(long expectedSessionGeneration, String expectedStopOperationId) {
        if (state != State.RUNNING) {
            return Action.NONE;
        }
        // Generation zero is the authoritative idle state. Triple-Home still has to
        // close an app that has not started a recording, and the native owner emits
        // its shutdown acknowledgement against that exact generation.
        if (expectedSessionGeneration < 0L
            || expectedStopOperationId == null
            || expectedStopOperationId.trim().isEmpty()) {
            throw new IllegalArgumentException("session generation and stop operation are required");
        }
        // The caller must apply these guard effects before dispatching writer shutdown.
        guardDisarmed = true;
        recoveryAllowed = false;
        sessionGeneration = expectedSessionGeneration;
        stopOperationId = expectedStopOperationId;
        state = State.STOP_REQUESTED;
        return Action.REQUEST_WRITER_STOP;
    }

    boolean acknowledgeWriter(
            long acknowledgedSessionGeneration,
            String acknowledgedStopOperationId,
            String receiptId,
            boolean completedCleanly) {
        if (state != State.STOP_REQUESTED
            || acknowledgedSessionGeneration != sessionGeneration
            || !stopOperationId.equals(acknowledgedStopOperationId)
            || receiptId == null
            || receiptId.trim().isEmpty()) {
            return false;
        }
        writerReceiptId = receiptId;
        writerCompletedCleanly = completedCleanly;
        state = State.WRITER_ACKNOWLEDGED;
        return true;
    }

    Action requestFinish(long expectedSessionGeneration, String expectedStopOperationId) {
        if (expectedSessionGeneration != sessionGeneration
            || !stopOperationId.equals(expectedStopOperationId)) {
            return Action.NONE;
        }
        if (state == State.STOP_REQUESTED) {
            return Action.WAIT_FOR_WRITER_ACK;
        }
        if (state != State.WRITER_ACKNOWLEDGED) {
            return Action.NONE;
        }
        state = State.FINISH_DISPATCHED;
        return Action.FINISH_AND_REMOVE_TASK;
    }

    boolean isGuardDisarmed() {
        return guardDisarmed;
    }

    boolean isRecoveryAllowed() {
        return recoveryAllowed;
    }

    boolean isWriterAcknowledged() {
        return state == State.WRITER_ACKNOWLEDGED || state == State.FINISH_DISPATCHED;
    }

    String writerReceiptId() {
        return writerReceiptId;
    }

    boolean writerCompletedCleanly() {
        return writerCompletedCleanly;
    }

    long sessionGeneration() {
        return sessionGeneration;
    }

    String stopOperationId() {
        return stopOperationId;
    }
}
