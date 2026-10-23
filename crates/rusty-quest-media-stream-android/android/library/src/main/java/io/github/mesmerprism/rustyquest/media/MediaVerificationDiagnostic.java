package io.github.mesmerprism.rustyquest.media;

/** Thread-confined optional verifier observation; never an alternate acceptance result. */
public final class MediaVerificationDiagnostic {
    public final String boundary;
    public final long expectedGeneration,observedGeneration;
    public final boolean receiptKnown;
    public final Throwable failure;
    MediaVerificationDiagnostic(String boundary,long expected,long observed,boolean known,Throwable failure){
        this.boundary=boundary;expectedGeneration=expected;observedGeneration=observed;receiptKnown=known;this.failure=failure;
    }
}
