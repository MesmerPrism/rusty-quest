package io.github.mesmerprism.rustyquest.media;

/** One unsubmitted packed-frame lease. Encoder owns at most one separate input.
 * No Surface, codec, EGL, camera, join, or terminal assertion runs under this lock.
 * Releasing an unsubmitted lease only removes this consumer reference; producer
 * fence and other consumer references still govern physical pool reuse.
 */
public final class PackedStereoEncoderMailbox<L extends PackedStereoEncoderMailbox.UnsubmittedLease> {
    public interface UnsubmittedLease { void releaseUnsubmitted(); }
    private final long peerGeneration;
    private L pending;
    private boolean accepting = true;

    PackedStereoEncoderMailbox(long peerGeneration) {
        if (peerGeneration <= 0) throw new IllegalArgumentException("peer generation");
        this.peerGeneration = peerGeneration;
    }

    // Ownership transfers even on rejection. Do not submit an input before offer.
    boolean offer(long generation, L frame) {
        if (frame == null) throw new NullPointerException("frame");
        L abandoned;
        boolean accepted;
        synchronized (this) {
            accepted = accepting && generation == peerGeneration;
            abandoned = accepted ? pending : frame;
            if (accepted) { pending = frame; notifyAll(); }
        }
        if (abandoned != null) abandoned.releaseUnsubmitted();
        return accepted;
    }

    // The sole encoder worker calls this only after its previous input is
    // physically GPU-retired. Returned lease leaves mailbox ownership.
    synchronized L take() throws InterruptedException {
        while (accepting && pending == null) wait();
        L frame = pending;
        pending = null;
        return frame;
    }

    void stopAccepting() {
        L abandoned;
        synchronized (this) {
            accepting = false;
            abandoned = pending;
            pending = null;
            notifyAll();
        }
        if (abandoned != null) abandoned.releaseUnsubmitted();
    }
}
