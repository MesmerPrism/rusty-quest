package io.github.mesmerprism.rustyquest.media;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, collision-rejecting association between submitted frames and exact codec PTS. */
public final class ExactPresentationTracker {
    private final int capacity;
    private final Map<Long, StereoFrameLease> submitted = new LinkedHashMap<>();
    private long lastTakenPtsUs = -1L;
    private boolean closed;

    public ExactPresentationTracker(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }
    public synchronized void submit(StereoFrameLease lease) {
        if (lease == null || lease.isReleased()) throw new IllegalArgumentException("lease");
        long pts = lease.identity().presentationTimeUs();
        if (closed || pts <= lastTakenPtsUs || submitted.containsKey(pts)) {
            lease.close();
            throw new IllegalStateException("duplicate or reordered submitted PTS");
        }
        if (submitted.size() >= capacity) {
            lease.close();
            throw new IllegalStateException("submitted PTS queue full");
        }
        submitted.put(pts, lease);
    }
    public synchronized StereoFrameLease take(long exactPresentationTimeUs) {
        if (closed) throw new IllegalStateException("presentation tracker closed");
        if (exactPresentationTimeUs <= lastTakenPtsUs) {
            throw new IllegalStateException("duplicate or reordered encoded PTS");
        }
        StereoFrameLease lease = submitted.remove(exactPresentationTimeUs);
        if (lease == null) throw new IllegalStateException("encoded PTS has no exact submitted frame");
        lastTakenPtsUs = exactPresentationTimeUs;
        return lease;
    }
    public synchronized int expireBefore(long presentationTimeUsExclusive) {
        int expired = 0;
        java.util.Iterator<Map.Entry<Long, StereoFrameLease>> iterator = submitted.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, StereoFrameLease> entry = iterator.next();
            if (entry.getKey() < presentationTimeUsExclusive) {
                iterator.remove();
                entry.getValue().close();
                expired++;
            }
        }
        return expired;
    }
    public synchronized void close() {
        closed = true;
        for (StereoFrameLease lease : submitted.values()) lease.close();
        submitted.clear();
    }
    public synchronized int size() { return submitted.size(); }
}
