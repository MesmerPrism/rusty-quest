package io.github.mesmerprism.rustyquest.media;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pairs two eye leases by exact generation/frame number and expires unmatched frames. */
public final class StereoFramePairer implements AutoCloseable {
    public enum Eye { LEFT, RIGHT }
    public static final class Pair implements AutoCloseable {
        private final StereoFrameLease left;
        private final StereoFrameLease right;
        Pair(StereoFrameLease left, StereoFrameLease right) { this.left = left; this.right = right; }
        public StereoFrameLease left() { return left; }
        public StereoFrameLease right() { return right; }
        @Override public void close() { left.close(); right.close(); }
    }
    private static final class Pending {
        final StereoFrameLease lease;
        final long arrivalNs;
        Pending(StereoFrameLease lease, long arrivalNs) { this.lease = lease; this.arrivalNs = arrivalNs; }
    }
    private final int capacity;
    private final long expiryNs;
    private final Map<String, Pending> left = new LinkedHashMap<>();
    private final Map<String, Pending> right = new LinkedHashMap<>();

    public StereoFramePairer(int capacity, long expiryNs) {
        if (capacity <= 0 || expiryNs <= 0) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.expiryNs = expiryNs;
    }
    public synchronized Pair offer(Eye eye, StereoFrameLease lease, long nowNs) {
        if (eye == null || lease == null || lease.isReleased()) throw new IllegalArgumentException();
        expire(nowNs);
        String key = key(lease.identity());
        Map<String, Pending> own = eye == Eye.LEFT ? left : right;
        Map<String, Pending> other = eye == Eye.LEFT ? right : left;
        if (own.containsKey(key)) {
            lease.close();
            throw new IllegalStateException("duplicate eye frame");
        }
        Pending match = other.remove(key);
        if (match != null) {
            return eye == Eye.LEFT ? new Pair(lease, match.lease) : new Pair(match.lease, lease);
        }
        if (own.size() >= capacity) {
            lease.close();
            throw new IllegalStateException("stereo pair queue full");
        }
        own.put(key, new Pending(lease, nowNs));
        return null;
    }
    public synchronized int expire(long nowNs) { return expire(left, nowNs) + expire(right, nowNs); }
    private int expire(Map<String, Pending> pending, long nowNs) {
        int count = 0;
        Iterator<Pending> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Pending value = iterator.next();
            if (nowNs - value.arrivalNs >= expiryNs) {
                iterator.remove(); value.lease.close(); count++;
            }
        }
        return count;
    }
    private static String key(StereoFrameIdentity id) {
        return id.generation() + ":" + id.frameNumber();
    }
    @Override public synchronized void close() {
        for (Pending value : left.values()) value.lease.close();
        for (Pending value : right.values()) value.lease.close();
        left.clear(); right.clear();
    }
}
