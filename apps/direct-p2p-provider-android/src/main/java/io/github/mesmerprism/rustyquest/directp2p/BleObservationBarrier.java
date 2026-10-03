package io.github.mesmerprism.rustyquest.directp2p;

/** Closed fresh observation predicate; the independent owner authorization stays mandatory. */
public final class BleObservationBarrier {
    static final long MAX_WAIT=10_000L, MAX_AGE=5_000L;
    public static long launchReserve(boolean enabled,long echo){return 20_000L+(enabled?MAX_WAIT:0L)+echo+10_000L;}
    public static long remaining(long start,long now){return start<0||now<start||now-start>=MAX_WAIT?0:MAX_WAIT-(now-start);}
    public static boolean waiting(long start,long now){return remaining(start,now)>0;}
    public static void require(String[] proof,String run,String session,long epoch,String peer,String expected,
            String boot,String remoteBoot,String group,String role,String ownerIp,String localIp,long now) {
        if(proof==null||proof.length!=14||!run.equals(proof[0])||!session.equals(proof[1])||!Long.toString(epoch).equals(proof[2])
                ||!peer.equals(proof[3])||!expected.equals(proof[4])||!boot.equals(proof[5])||!group.equals(proof[6])
                ||!role.equals(proof[7])||!ownerIp.equals(proof[8])||!localIp.equals(proof[9])||!remoteBoot.equals(proof[10])
                ||role.equals(proof[11])||!("client".equals(proof[11])||"group_owner".equals(proof[11])))throw new IllegalArgumentException("ble_live_identity");
        for(int i=12;i<14;i++) {
            if(!proof[i].matches("0|[1-9][0-9]{0,17}"))throw new IllegalArgumentException("ble_live_time");
            long at=Long.parseLong(proof[i]);if(now<at||now-at>MAX_AGE)throw new IllegalArgumentException("ble_live_stale");
        }
    }
}
