package io.github.mesmerprism.rustyquest.packageupdater;

interface UpdateEnvelopeVerifier {
    VerifiedUpdatePlan verify(byte[] envelopeBytes, long nowMs) throws VerificationException;

    final class VerificationException extends Exception {
        VerificationException(String message) {
            super(message);
        }

        VerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
