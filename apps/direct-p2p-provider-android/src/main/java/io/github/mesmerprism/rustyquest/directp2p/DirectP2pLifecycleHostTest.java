package io.github.mesmerprism.rustyquest.directp2p;

public final class DirectP2pLifecycleHostTest {
    private static final String NETWORK = "DIRECT-rp-RustyP2P";
    private static final String OWNER = "02:11:22:33:44:55";
    private static int passed;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++;
    }
    private static DirectP2pLifecycle forming(boolean owner) {
        DirectP2pLifecycle c = new DirectP2pLifecycle(100, NETWORK, OWNER, owner);
        if (!c.baseline(false, 101) || !c.beginFormation(102) || !c.request(103)) throw new AssertionError("fixture");
        return c;
    }
    private static DirectP2pLifecycle exchanging() {
        DirectP2pLifecycle c = forming(true);
        c.requestAcknowledged(true);
        if (!c.exchange(NETWORK, OWNER, true, 105)) throw new AssertionError("exchange fixture");
        return c;
    }
    private static void observedCleanup(DirectP2pLifecycle c) {
        c.discoveryAcknowledged(true); c.discoveryReadback(c.requestDiscoveryReadback(), true); c.groupReadback(c.requestGroupReadback(), true);
    }
    public static void main(String[] args) {
        DirectP2pLifecycle.AuthorizationWindow window=new DirectP2pLifecycle.AuthorizationWindow(1000,100,61000,20000);
        check(window.permits(1001,101,50000), "signed total budgets initially fit");
        check(!window.permits(11000,101,50000), "actual wall remaining checked");
        check(!window.permits(1001,60100,0), "monotonic expiry despite slow wall");
        check(!window.permits(999,101,0), "wall rollback denied");
        check(!window.permits(1001,99,0), "elapsed rollback denied");
        check(!window.permits(61000,101,0), "hard expiry denied");
        for(long cap:new long[]{0,20001}) {
            boolean rejected=false;try{new DirectP2pLifecycle.AuthorizationWindow(1000,100,61000,cap);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"closed echo cap");
        }
        DirectP2pLifecycle c = new DirectP2pLifecycle(100, NETWORK, OWNER, true);
        check(!c.request(101), "no effect before baseline");
        check(!c.baseline(true, 101), "foreign baseline rejected");
        check(!c.beginFormation(102), "foreign baseline cannot form");
        check(!c.requested(), "foreign baseline has zero topology effects");
        check(!c.mayRemove(NETWORK, OWNER, true), "foreign baseline not removed");
        c = forming(true);
        check(!c.request(104), "no repeated creation attempt");
        check(!c.exchange(NETWORK, OWNER, true, 105), "native cannot precede request callback");
        c.requestAcknowledged(true);
        check(!c.exchange("DIRECT-foreign", OWNER, true, 105), "network mismatch");
        check(!c.exchange(NETWORK, "02:aa:bb:cc:dd:ee", true, 105), "owner mismatch");
        check(!c.exchange(NETWORK, OWNER, false, 105), "role mismatch");
        check(!c.expired(20_099), "formation deadline not early");
        check(c.expired(20_100), "finite formation deadline");
        check(!c.exchange(NETWORK, OWNER, true, 20_100), "expired formation cannot start native");
        c.beginCleanup(); observedCleanup(c);
        check(!c.cleanupConfirmed(), "accepted creation still unsettled absent group cannot claim clean");
        check(c.mayRemove(NETWORK, OWNER, true), "late exact owned group can be compensated");
        check(!c.cleanupConfirmed(), "remove callback required despite absent readback");
        c.removalAcknowledged(true);
        c.groupReadback(c.requestGroupReadback(), true);
        check(c.cleanupConfirmed(), "late owned group callback and fresh absence join");
        check(c.finish(), "terminal transition");
        check(!c.beginCleanup() && !c.request(200), "terminal cannot replay");
        c = exchanging();
        check(!c.expired(90_099) && c.expired(90_100), "finite whole run deadline");
        c.beginCleanup();
        check(!c.mayRemove("DIRECT-foreign", OWNER, true), "foreign cleanup network not removed");
        check(!c.mayRemove(NETWORK, "02:aa:bb:cc:dd:ee", true), "foreign cleanup owner not removed");
        check(!c.mayRemove(NETWORK, OWNER, false), "foreign cleanup role not removed");
        c = exchanging(); c.beginCleanup(); c.mayRemove(NETWORK, OWNER, true);
        c.removalAcknowledged(false); observedCleanup(c); c.nativeComplete();
        check(!c.cleanupConfirmed() && !c.groupRemoved(), "failed remove callback never fabricates cleanup");
        c.removalAcknowledged(true);
        check(!c.cleanupConfirmed(), "later callback cannot erase failed callback evidence");
        c = exchanging(); c.discoveryStarted(); c.beginCleanup(); c.mayRemove(NETWORK, OWNER, true); c.removalAcknowledged(true);
        c.discoveryAcknowledged(false); c.discoveryReadback(c.requestDiscoveryReadback(), true); c.groupReadback(c.requestGroupReadback(), true); c.nativeComplete();
        check(!c.cleanupConfirmed() && !c.discoveryStopped(), "stop discovery callback failure retained");
        c = exchanging(); c.beginCleanup(); c.mayRemove(NETWORK, OWNER, true); c.removalAcknowledged(true);
        c.discoveryAcknowledged(true); c.discoveryReadback(c.requestDiscoveryReadback(), false); c.groupReadback(c.requestGroupReadback(), true); c.nativeComplete();
        check(!c.cleanupConfirmed(), "effective discovery readback required");
        c.discoveryReadback(c.requestDiscoveryReadback(), true); c.groupReadback(c.requestGroupReadback(), false);
        check(!c.cleanupConfirmed() && !c.groupRemoved(), "effective group absence required");
        c.groupReadback(c.requestGroupReadback(), true);
        check(c.cleanupConfirmed() && c.discoveryStopped() && c.groupRemoved() && c.socketClosed(), "actual successful cleanup joins");
        c = exchanging(); c.fail("activity_destroyed"); c.beginCleanup(); c.mayRemove(NETWORK, OWNER, true);
        c.removalAcknowledged(true); observedCleanup(c);
        check(!c.cleanupConfirmed() && !c.socketClosed(), "destroy cannot claim live native socket closed");
        c.nativeComplete();
        check(c.cleanupConfirmed() && "activity_destroyed".equals(c.failure()), "destroy compensated but remains failure");
        c = forming(false); c.requestAcknowledged(false); c.beginCleanup(); observedCleanup(c);
        check(c.cleanupConfirmed() && !c.ownedGroupObserved(), "rejected request cleanup does not qualify topology");
        c = forming(false); c.requestAcknowledged(true);
        check(c.exchange(NETWORK, OWNER.toUpperCase(), false, 105), "client exact owner accepted");
        c.nativeComplete(); c.beginCleanup(); observedCleanup(c);
        check(c.cleanupConfirmed(), "naturally absent owned group does not invent remove request");
        c.readbackFailed();
        check(!c.groupRemoved() && !c.discoveryStopped(), "readback exception invalidates prior observations");
        c = exchanging(); c.nativeComplete(); c.beginCleanup();
        c.discoveryReadback(c.requestDiscoveryReadback(), true); c.groupReadback(c.requestGroupReadback(), true);
        check(c.cleanupConfirmed(), "no foreign discovery stop when app never started discovery");
        c = forming(false); c.discoveryStarted(); c.requestAcknowledged(false); c.beginCleanup();
        c.discoveryReadback(c.requestDiscoveryReadback(), true); c.groupReadback(c.requestGroupReadback(), true);
        check(!c.cleanupConfirmed(), "own discovery needs actual stop callback");
        c = new DirectP2pLifecycle(100, NETWORK, OWNER, true);
        check(!c.baseline(false, 20_100), "late baseline rejected");
        boolean invalid = false;
        try { new DirectP2pLifecycle(Long.MAX_VALUE, NETWORK, OWNER, true); } catch (IllegalArgumentException expected) { invalid = true; }
        check(invalid, "clock overflow rejected");
        check(DirectP2pLifecycle.selectTargetPeer(OWNER, new String[] { "02:aa:bb:cc:dd:ee" }) == -1, "foreign first peer never selected");
        check(DirectP2pLifecycle.selectTargetPeer(OWNER, new String[] { "02:aa:bb:cc:dd:ee", OWNER.toUpperCase() }) == 1, "exact target selected after foreign peer");
        check(DirectP2pLifecycle.selectTargetPeer(OWNER, new String[] { OWNER, OWNER }) == -1, "duplicate target ambiguous");
        check(DirectP2pLifecycle.selectTargetPeer(OWNER, null) == -1, "null peer callback has no selection");
        check(DirectP2pLifecycle.selectTargetPeer(OWNER, new String[] { null }) == -1, "null peer ignored");
        check(DirectP2pLifecycle.selectTargetPeer("invalid", new String[] { "invalid" }) == -1, "malformed target never selected");
        c = forming(true); c.fail("synchronous_request_exception"); c.beginCleanup(); observedCleanup(c);
        check(!c.cleanupConfirmed(), "ambiguous synchronous request failure retains pending obligation");
        check(!c.exchange(NETWORK, OWNER, true, 105), "stale connection after failure cannot start exchange");
        c = exchanging(); c.nativeComplete(); c.beginCleanup(); c.mayRemove(NETWORK, OWNER, true); observedCleanup(c);
        check(!c.groupRemoved(), "effective absence without remove callback cannot claim group removed");
        c = exchanging(); c.nativeComplete(); c.beginCleanup();
        long beforeRemoval = c.requestGroupReadback(); c.mayRemove(NETWORK, OWNER, true); c.removalAcknowledged(true);
        c.groupReadback(beforeRemoval, true); c.discoveryReadback(c.requestDiscoveryReadback(), true);
        check(!c.cleanupConfirmed(), "pre-removal request cannot confirm post-removal absence");
        long beforeAck = c.requestGroupReadback(); c.removalAcknowledged(true); c.groupReadback(beforeAck, true);
        check(!c.cleanupConfirmed(), "pre-ack request cannot confirm post-ack absence");
        c.groupReadback(c.requestGroupReadback(), true); check(c.cleanupConfirmed(), "fresh post-ack absence confirms cleanup");
        c = exchanging(); c.nativeComplete(); c.discoveryStarted(); c.beginCleanup();
        long beforeStop = c.requestDiscoveryReadback(); c.discoveryAcknowledged(true); c.discoveryReadback(beforeStop, true);
        c.groupReadback(c.requestGroupReadback(), true); check(!c.cleanupConfirmed(), "pre-stop request cannot confirm post-stop readback");
        c.discoveryReadback(c.requestDiscoveryReadback(), true); check(c.cleanupConfirmed(), "fresh post-stop readback required");
        c = exchanging(); c.nativeComplete(); c.beginCleanup();
        long older = c.requestGroupReadback(); long newest = c.requestGroupReadback();
        c.groupReadback(newest, false); c.groupReadback(older, true); c.discoveryReadback(c.requestDiscoveryReadback(), true);
        check(!c.cleanupConfirmed(), "out-of-order old absence cannot override newer presence");
        check(DirectP2pLifecycle.networkForGuardToken("0123456789abcdef0123456789abcdef").equals("DIRECT-rp-0123456789abcdef0123"), "closed fresh group name");
        for (String token : new String[] { null, "", "0123456789abcdef0123456789abcdeF", "DIRECT-foreign" }) {
            boolean rejected = false;
            try { DirectP2pLifecycle.networkForGuardToken(token); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid or absent guardian token has zero topology effects");
        }
        System.out.println("direct_p2p_lifecycle_host=pass cases=" + passed + " device_calls=0");
    }
}
