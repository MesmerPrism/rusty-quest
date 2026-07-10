package io.github.mesmerprism.rustymanifold.broker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/** Bounded, source-timestamp-authoritative stereo frame pairer. */
final class RemoteCameraStereoFramePairer {
    static final String LEFT = "left";
    static final String RIGHT = "right";

    static final class Candidate {
        final String eye;
        final long sourceFrame;
        final long sensorTimestampNs;
        final int textureSlot;
        final long queuedElapsedNs;

        Candidate(
                String eye,
                long sourceFrame,
                long sensorTimestampNs,
                int textureSlot,
                long queuedElapsedNs) {
            this.eye = eye;
            this.sourceFrame = sourceFrame;
            this.sensorTimestampNs = sensorTimestampNs;
            this.textureSlot = textureSlot;
            this.queuedElapsedNs = queuedElapsedNs;
        }
    }

    static final class Pair {
        final long pairId;
        final Candidate left;
        final Candidate right;
        final long pairDeltaNs;
        final long pairWaitNs;

        Pair(long pairId, Candidate left, Candidate right, long pairDeltaNs, long pairWaitNs) {
            this.pairId = pairId;
            this.left = left;
            this.right = right;
            this.pairDeltaNs = pairDeltaNs;
            this.pairWaitNs = pairWaitNs;
        }
    }

    static final class Snapshot {
        final long acceptedPairs;
        final long leftFramesDroppedUnmatched;
        final long rightFramesDroppedUnmatched;
        final long skewRejected;
        final long queueOverflowDrops;
        final long staleEyeReuseCount;
        final int queueDepthLeft;
        final int queueDepthRight;
        final int queueMaxDepthLeft;
        final int queueMaxDepthRight;
        final long pairDeltaP50Ns;
        final long pairDeltaP95Ns;
        final long pairDeltaMaxNs;
        final long pairWaitP50Ns;
        final long pairWaitP95Ns;
        final long pairWaitMaxNs;

        Snapshot(
                long acceptedPairs,
                long leftFramesDroppedUnmatched,
                long rightFramesDroppedUnmatched,
                long skewRejected,
                long queueOverflowDrops,
                long staleEyeReuseCount,
                int queueDepthLeft,
                int queueDepthRight,
                int queueMaxDepthLeft,
                int queueMaxDepthRight,
                long pairDeltaP50Ns,
                long pairDeltaP95Ns,
                long pairDeltaMaxNs,
                long pairWaitP50Ns,
                long pairWaitP95Ns,
                long pairWaitMaxNs) {
            this.acceptedPairs = acceptedPairs;
            this.leftFramesDroppedUnmatched = leftFramesDroppedUnmatched;
            this.rightFramesDroppedUnmatched = rightFramesDroppedUnmatched;
            this.skewRejected = skewRejected;
            this.queueOverflowDrops = queueOverflowDrops;
            this.staleEyeReuseCount = staleEyeReuseCount;
            this.queueDepthLeft = queueDepthLeft;
            this.queueDepthRight = queueDepthRight;
            this.queueMaxDepthLeft = queueMaxDepthLeft;
            this.queueMaxDepthRight = queueMaxDepthRight;
            this.pairDeltaP50Ns = pairDeltaP50Ns;
            this.pairDeltaP95Ns = pairDeltaP95Ns;
            this.pairDeltaMaxNs = pairDeltaMaxNs;
            this.pairWaitP50Ns = pairWaitP50Ns;
            this.pairWaitP95Ns = pairWaitP95Ns;
            this.pairWaitMaxNs = pairWaitMaxNs;
        }
    }

    private final int maxQueueDepth;
    private final long maxPairDeltaNs;
    private final ArrayDeque<Candidate> leftQueue = new ArrayDeque<>();
    private final ArrayDeque<Candidate> rightQueue = new ArrayDeque<>();
    private final List<Long> pairDeltasNs = new ArrayList<>();
    private final List<Long> pairWaitsNs = new ArrayList<>();
    private long nextPairId = 1L;
    private long lastLeftSourceFrame;
    private long lastRightSourceFrame;
    private long acceptedPairs;
    private long leftFramesDroppedUnmatched;
    private long rightFramesDroppedUnmatched;
    private long skewRejected;
    private long queueOverflowDrops;
    private int queueMaxDepthLeft;
    private int queueMaxDepthRight;

    RemoteCameraStereoFramePairer(int maxQueueDepth, long maxPairDeltaNs) {
        if (maxQueueDepth < 1 || maxQueueDepth > 32) {
            throw new IllegalArgumentException("maxQueueDepth must be 1..=32");
        }
        if (maxPairDeltaNs < 1L || maxPairDeltaNs > 1_000_000_000L) {
            throw new IllegalArgumentException("maxPairDeltaNs must be 1..=1000000000");
        }
        this.maxQueueDepth = maxQueueDepth;
        this.maxPairDeltaNs = maxPairDeltaNs;
    }

    synchronized Pair add(Candidate candidate, long nowElapsedNs) {
        if (candidate == null
                || (!LEFT.equals(candidate.eye) && !RIGHT.equals(candidate.eye))
                || candidate.sourceFrame <= 0L
                || candidate.sensorTimestampNs <= 0L) {
            return null;
        }
        if ((LEFT.equals(candidate.eye) && candidate.sourceFrame <= lastLeftSourceFrame)
                || (RIGHT.equals(candidate.eye) && candidate.sourceFrame <= lastRightSourceFrame)) {
            drop(candidate.eye, false);
            return null;
        }
        ArrayDeque<Candidate> queue = queue(candidate.eye);
        removeTextureSlot(queue, candidate.textureSlot, candidate.eye);
        queue.addLast(candidate);
        while (queue.size() > maxQueueDepth) {
            queue.removeFirst();
            drop(candidate.eye, true);
        }
        queueMaxDepthLeft = Math.max(queueMaxDepthLeft, leftQueue.size());
        queueMaxDepthRight = Math.max(queueMaxDepthRight, rightQueue.size());

        Pair pair = chooseNearest(nowElapsedNs);
        if (pair != null) {
            return pair;
        }
        expireImpossibleCandidates();
        return null;
    }

    synchronized void discardTextureSlot(String eye, int textureSlot) {
        removeTextureSlot(queue(eye), textureSlot, eye);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                acceptedPairs,
                leftFramesDroppedUnmatched,
                rightFramesDroppedUnmatched,
                skewRejected,
                queueOverflowDrops,
                0L,
                leftQueue.size(),
                rightQueue.size(),
                queueMaxDepthLeft,
                queueMaxDepthRight,
                percentile(pairDeltasNs, 50),
                percentile(pairDeltasNs, 95),
                maximum(pairDeltasNs),
                percentile(pairWaitsNs, 50),
                percentile(pairWaitsNs, 95),
                maximum(pairWaitsNs));
    }

    synchronized void clear() {
        leftFramesDroppedUnmatched += leftQueue.size();
        rightFramesDroppedUnmatched += rightQueue.size();
        leftQueue.clear();
        rightQueue.clear();
    }

    private Pair chooseNearest(long nowElapsedNs) {
        Candidate bestLeft = null;
        Candidate bestRight = null;
        long bestDelta = Long.MAX_VALUE;
        for (Candidate left : leftQueue) {
            for (Candidate right : rightQueue) {
                long delta = absoluteDelta(left.sensorTimestampNs, right.sensorTimestampNs);
                if (delta < bestDelta) {
                    bestDelta = delta;
                    bestLeft = left;
                    bestRight = right;
                }
            }
        }
        if (bestLeft == null || bestRight == null || bestDelta > maxPairDeltaNs) {
            if (bestLeft != null && bestRight != null) {
                skewRejected++;
            }
            return null;
        }
        leftQueue.remove(bestLeft);
        rightQueue.remove(bestRight);
        lastLeftSourceFrame = bestLeft.sourceFrame;
        lastRightSourceFrame = bestRight.sourceFrame;
        long waitNs = Math.max(0L, nowElapsedNs - Math.min(
                bestLeft.queuedElapsedNs,
                bestRight.queuedElapsedNs));
        Pair pair = new Pair(nextPairId++, bestLeft, bestRight, bestDelta, waitNs);
        acceptedPairs++;
        pairDeltasNs.add(bestDelta);
        pairWaitsNs.add(waitNs);
        return pair;
    }

    private void expireImpossibleCandidates() {
        if (leftQueue.isEmpty() || rightQueue.isEmpty()) {
            return;
        }
        long newestLeft = leftQueue.peekLast().sensorTimestampNs;
        long newestRight = rightQueue.peekLast().sensorTimestampNs;
        while (!leftQueue.isEmpty()
                && leftQueue.peekFirst().sensorTimestampNs + maxPairDeltaNs < newestRight) {
            leftQueue.removeFirst();
            leftFramesDroppedUnmatched++;
        }
        while (!rightQueue.isEmpty()
                && rightQueue.peekFirst().sensorTimestampNs + maxPairDeltaNs < newestLeft) {
            rightQueue.removeFirst();
            rightFramesDroppedUnmatched++;
        }
    }

    private void removeTextureSlot(ArrayDeque<Candidate> queue, int textureSlot, String eye) {
        Iterator<Candidate> iterator = queue.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().textureSlot == textureSlot) {
                iterator.remove();
                drop(eye, true);
            }
        }
    }

    private ArrayDeque<Candidate> queue(String eye) {
        if (LEFT.equals(eye)) {
            return leftQueue;
        }
        if (RIGHT.equals(eye)) {
            return rightQueue;
        }
        throw new IllegalArgumentException("unsupported eye " + eye);
    }

    private void drop(String eye, boolean overflow) {
        if (LEFT.equals(eye)) {
            leftFramesDroppedUnmatched++;
        } else {
            rightFramesDroppedUnmatched++;
        }
        if (overflow) {
            queueOverflowDrops++;
        }
    }

    private static long absoluteDelta(long left, long right) {
        return left >= right ? left - right : right - left;
    }

    private static long percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) {
            return 0L;
        }
        long[] sorted = new long[values.size()];
        for (int index = 0; index < values.size(); index++) {
            sorted[index] = values.get(index);
        }
        Arrays.sort(sorted);
        int rank = (int) Math.ceil((percentile / 100.0) * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank))];
    }

    private static long maximum(List<Long> values) {
        long max = 0L;
        for (Long value : values) {
            max = Math.max(max, value);
        }
        return max;
    }
}
