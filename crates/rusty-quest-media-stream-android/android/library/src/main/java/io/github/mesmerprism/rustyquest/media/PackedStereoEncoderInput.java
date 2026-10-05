package io.github.mesmerprism.rustyquest.media;

/** A second content lease, separate from the renderer's Own image lease. */
public interface PackedStereoEncoderInput extends PackedStereoEncoderMailbox.UnsubmittedLease {
    PackedStereoPoolExecutor.PairIdentity pair();
    Imported importOnCurrentContext() throws Exception;
    void finishAfterSubmission() throws Exception;
    boolean pollRetired() throws Exception;

    final class Imported {
        public final int texture, width, height;
        public Imported(int texture, int width, int height) {
            if (texture <= 0 || width <= 0 || height <= 0)
                throw new IllegalArgumentException("invalid encoder import");
            this.texture = texture; this.width = width; this.height = height;
        }
    }
}
