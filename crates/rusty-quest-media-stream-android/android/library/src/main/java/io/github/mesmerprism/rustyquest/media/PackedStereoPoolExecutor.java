// Host implementation owns real JNI registry, allocation, generation and fences.
package io.github.mesmerprism.rustyquest.media;

public interface PackedStereoPoolExecutor {
    // Capacity/byte budget come from this executor's accepted runtime contract.
    // Caller supplies geometry, never arbitrary slot count/terminal assertions.
    Pool createForCurrentContext(int packedWidth, int height) throws Exception;
    // Control worker only, before opening cameras: waits for the previous physical capture owner.
    void awaitCameraOwnership() throws Exception;
    // Actual native producer-ready publication observed in the local monotonic domain.
    boolean ownImageFresh();
    interface Pool {
        Write beginWrite() throws Exception; // null: no free capacity, drop unsubmitted pair
        void finishWrite(Write write, PairIdentity pair) throws Exception;
        void quarantineWrite(Write write, String reason);
        // Stops new writes, not a physical close. Actual shutdown is registry
        // Pending until producer/consumer fences, callbacks and workers retire.
        void stopAccepting();
        // Capture actor polls actual producer fences; callbacks execute outside native registry borrows.
        void pollReady() throws Exception;
        // Capture context only. False retains resources through actual outstanding consumer fences.
        boolean retireStopped() throws Exception;
    }
    final class Write {
        public final long poolGeneration, slotSerial;
        public final int framebuffer;
        public Write(long poolGeneration, long slotSerial, int framebuffer) {
            if (poolGeneration <= 0 || slotSerial <= 0 || framebuffer <= 0)
                throw new IllegalArgumentException("invalid native write ticket");
            this.poolGeneration = poolGeneration;
            this.slotSerial = slotSerial;
            this.framebuffer = framebuffer;
        }
    }
    final class PairIdentity {
        public final long pairId, leftFrame, rightFrame, leftSensorNs, rightSensorNs, packedPtsNs;
        public PairIdentity(long pairId, long leftFrame, long rightFrame, long leftSensorNs, long rightSensorNs) {
            if (pairId <= 0 || leftFrame < 0 || rightFrame < 0 || leftSensorNs <= 0 || rightSensorNs <= 0)
                throw new IllegalArgumentException("invalid accepted stereo pair");
            this.pairId = pairId; this.leftFrame = leftFrame; this.rightFrame = rightFrame;
            this.leftSensorNs = leftSensorNs; this.rightSensorNs = rightSensorNs;
            this.packedPtsNs = Math.max(leftSensorNs, rightSensorNs);
        }
    }
}
