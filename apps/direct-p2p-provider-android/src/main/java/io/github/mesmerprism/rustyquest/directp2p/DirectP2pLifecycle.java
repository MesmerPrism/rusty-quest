package io.github.mesmerprism.rustyquest.directp2p;

/** One diagnostic's topology ownership and effective cleanup joins. No platform effects. */
final class DirectP2pLifecycle {
    enum Phase { BASELINE, FORMING, EXCHANGING, CLEANING, TERMINAL }
    private final long formationDeadline;
    private final long runDeadline;
    private final String expectedNetwork;
    private final String expectedOwner;
    private final boolean expectedLocalOwner;
    private Phase phase = Phase.BASELINE;
    private boolean baselineAbsent;
    private boolean requested;
    private boolean requestPending;
    private boolean formationSettled = true;
    private boolean ownedGroupObserved;
    private boolean nativePending;
    private boolean stopAcknowledged;
    private boolean discoveryOwned;
    private boolean discoveryStopped;
    private boolean removeAcknowledged;
    private boolean removeRequested;
    private boolean groupAbsent;
    private boolean cleanupCallbackFailure;
    private String failure;
    private long groupObservation, discoveryObservation;
    private boolean groupObservationEligible, discoveryObservationEligible;

    DirectP2pLifecycle(long now, String network, String owner, boolean localOwner) {
        if (now < 0 || now > Long.MAX_VALUE - 90_000L || network == null || network.isEmpty()
                || owner == null || !owner.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
            throw new IllegalArgumentException("lifecycle_identity");
        }
        formationDeadline = now + 20_000L;
        runDeadline = now + 90_000L;
        expectedNetwork = network;
        expectedOwner = owner;
        expectedLocalOwner = localOwner;
    }

    static int selectTargetPeer(String target, String[] addresses) {
        if (target == null || !target.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}") || addresses == null) return -1;
        int selected = -1;
        for (int index = 0; index < addresses.length; index++) {
            if (target.equalsIgnoreCase(addresses[index])) {
                if (selected >= 0) return -1;
                selected = index;
            }
        }
        return selected;
    }

    synchronized boolean baseline(boolean present, long now) {
        if (phase != Phase.BASELINE || now >= formationDeadline || present) {
            fail(present ? "preexisting_group" : "baseline_expired");
            return false;
        }
        baselineAbsent = true;
        return true;
    }

    synchronized boolean beginFormation(long now) {
        if (phase != Phase.BASELINE || !baselineAbsent || now >= formationDeadline) return false;
        phase = Phase.FORMING;
        return true;
    }

    synchronized boolean request(long now) {
        if (phase != Phase.FORMING || requested || now >= formationDeadline) return false;
        requested = true;
        requestPending = true;
        formationSettled = false;
        return true;
    }

    synchronized void requestAcknowledged(boolean accepted) {
        if (!requested || !requestPending) return;
        requestPending = false;
        if (!accepted) { formationSettled = true; fail("topology_request_rejected"); }
    }

    synchronized boolean owned(String network, String owner, boolean localOwner) {
        return requested && expectedNetwork.equals(network) && owner != null
                && expectedOwner.equalsIgnoreCase(owner) && expectedLocalOwner == localOwner;
    }

    synchronized boolean exchange(String network, String owner, boolean localOwner, long now) {
        if (phase != Phase.FORMING || failure != null || requestPending || expired(now)
                || !owned(network, owner, localOwner)) return false;
        ownedGroupObserved = true;
        formationSettled = true;
        nativePending = true;
        phase = Phase.EXCHANGING;
        return true;
    }

    synchronized boolean expired(long now) {
        return now >= runDeadline || (phase == Phase.BASELINE || phase == Phase.FORMING) && now >= formationDeadline;
    }

    synchronized void nativeComplete() { nativePending = false; }
    synchronized void fail(String reason) { if (failure == null) failure = reason; }
    synchronized String failure() { return failure; }
    synchronized boolean active() { return failure == null && (phase == Phase.BASELINE || phase == Phase.FORMING || phase == Phase.EXCHANGING); }
    synchronized boolean requested() { return requested; }
    synchronized boolean requestPending() { return requestPending; }
    synchronized void discoveryStarted() { discoveryOwned = true; }
    synchronized boolean discoveryOwned() { return discoveryOwned; }

    synchronized boolean beginCleanup() {
        if (phase == Phase.CLEANING || phase == Phase.TERMINAL) return false;
        phase = Phase.CLEANING;
        return true;
    }

    synchronized boolean mayRemove(String network, String owner, boolean localOwner) {
        if (phase != Phase.CLEANING || !owned(network, owner, localOwner)) {
            fail("foreign_or_unknown_cleanup_group");
            return false;
        }
        formationSettled = true;
        removeRequested = true;
        groupAbsent = false;
        groupObservation++;
        return true;
    }

    synchronized void discoveryAcknowledged(boolean success) {
        stopAcknowledged = success;
        discoveryStopped = false; discoveryObservation++;
        if (!success) { cleanupCallbackFailure = true; fail("stop_discovery_rejected"); }
    }
    synchronized long requestDiscoveryReadback() {
        discoveryObservationEligible = !discoveryOwned || stopAcknowledged;
        return ++discoveryObservation;
    }
    synchronized void discoveryReadback(long request, boolean stopped) {
        if (request == discoveryObservation && discoveryObservationEligible) discoveryStopped = stopped;
    }
    synchronized void removalAcknowledged(boolean success) {
        removeAcknowledged = success;
        groupAbsent = false; groupObservation++;
        if (!success) { cleanupCallbackFailure = true; fail("remove_group_rejected"); }
    }
    synchronized long requestGroupReadback() {
        groupObservationEligible = !removeRequested || removeAcknowledged;
        return ++groupObservation;
    }
    synchronized boolean groupReadback(long request, boolean absent) {
        if (request != groupObservation || !groupObservationEligible) return false;
        groupAbsent = absent; return true;
    }
    synchronized void readbackFailed() {
        groupAbsent = false;
        discoveryStopped = false;
        cleanupCallbackFailure = true;
        fail("cleanup_readback_failed");
    }

    synchronized boolean cleanupConfirmed() {
        return phase == Phase.CLEANING && !requestPending && formationSettled && !nativePending
                && (!discoveryOwned || stopAcknowledged) && discoveryStopped && groupAbsent
                && (!removeRequested || removeAcknowledged) && !cleanupCallbackFailure;
    }
    synchronized boolean finish() {
        if (!cleanupConfirmed()) return false;
        phase = Phase.TERMINAL;
        return true;
    }
    synchronized boolean discoveryStopped() { return (!discoveryOwned || stopAcknowledged) && discoveryStopped; }
    synchronized boolean groupRemoved() { return groupAbsent && !requestPending && formationSettled && (!removeRequested || removeAcknowledged) && !cleanupCallbackFailure; }
    synchronized boolean removalAcknowledged() { return removeAcknowledged; }
    synchronized boolean socketClosed() { return !nativePending; }
    synchronized boolean ownedGroupObserved() { return ownedGroupObserved; }
}
