package io.github.mesmerprism.rustyquest.peer_rendezvous;

/** Pure observation/coordination predicate. It grants no topology or socket authority. */
final class BleRoleReadiness {
    static final long MAX_AGE_MS = 5_000;
    static final class Observation {
        final String boot, group, role, ownerIp, localIp;
        final long observedAt;
        Observation(String boot, String group, String role, String ownerIp, String localIp, long at) {
            this.boot=boot; this.group=group; this.role=role; this.ownerIp=ownerIp;
            this.localIp=localIp; this.observedAt=at;
        }
    }
    private String peer, peerBoot, peerGroup, peerRole;
    private final String boot;
    BleRoleReadiness(String boot) { this.boot=boot; }
    static void requireFresh(Observation o, String boot, String configuredRole, long now) {
        if (o == null || !boot.equals(o.boot) || o.observedAt < 0
                || now < o.observedAt || now-o.observedAt > MAX_AGE_MS)
            throw new IllegalArgumentException("wifi_observation_unavailable_or_stale");
        if (!o.group.matches("[a-f0-9]{16}")
                || !("group_owner".equals(o.role) || "client".equals(o.role))
                || !o.role.equals(configuredRole)
                || !address(o.ownerIp) || !address(o.localIp)
                || ("group_owner".equals(o.role) != o.ownerIp.equals(o.localIp)))
            throw new IllegalArgumentException("wifi_observation_role_or_group_invalid");
    }
    synchronized void requirePeer(Observation local, String configuredRole, long now,
            String expectedPeer, String remotePeer, String remoteBoot, String remoteGroup,
            String remoteRole, String remoteOwnerIp, String remoteLocalIp) {
        requireFresh(local,boot,configuredRole,now);
        if (!expectedPeer.equals(remotePeer) || !remoteBoot.matches("[a-f0-9]{16}")
                || !local.group.equals(remoteGroup) || !local.ownerIp.equals(remoteOwnerIp)
                || local.role.equals(remoteRole)
                || !("group_owner".equals(remoteRole) || "client".equals(remoteRole))
                || !address(remoteLocalIp)
                || ("group_owner".equals(remoteRole) != remoteOwnerIp.equals(remoteLocalIp)))
            throw new IllegalArgumentException("wifi_peer_role_or_group_conflict");
        if (peer != null && (!peer.equals(remotePeer) || !peerBoot.equals(remoteBoot)
                || !peerGroup.equals(remoteGroup) || !peerRole.equals(remoteRole)))
            throw new IllegalArgumentException("wifi_peer_changed_across_reconnect");
        peer=remotePeer;peerBoot=remoteBoot;peerGroup=remoteGroup;peerRole=remoteRole;
    }
    static String resolveRole(String local,String remote,String localPeer,String remotePeer) {
        if(localPeer.equals(remotePeer))throw new IllegalArgumentException("role_peer_identity_invalid");
        if(!("group_owner".equals(local)||"client".equals(local)||"either".equals(local))
                ||!("group_owner".equals(remote)||"client".equals(remote)||"either".equals(remote)))
            throw new IllegalArgumentException("role_preference_invalid");
        if(!"either".equals(local)&&local.equals(remote))
            throw new IllegalArgumentException("role_preferences_conflict");
        if(!"either".equals(local))return local;
        if("group_owner".equals(remote))return "client";
        if("client".equals(remote))return "group_owner";
        return localPeer.compareTo(remotePeer)<0?"group_owner":"client";
    }
    static void requireChallenge(String expected,String actual) {
        if(expected==null||!expected.matches("[a-f0-9]{16}")||!expected.equals(actual))
            throw new IllegalArgumentException("v2_peer_challenge_invalid");
    }
    static void requirePayload(int bytes,int negotiatedMtu) {
        if(bytes<1||bytes>244||bytes>negotiatedMtu-3)
            throw new IllegalArgumentException("v2_payload_exceeds_negotiated_mtu");
    }
    static boolean address(String ip) {
        if(ip==null)return false;
        String[] p=ip.split("\\.",-1);if(p.length!=4)return false;
        try { for(String part:p)if(!Integer.toString(Integer.parseInt(part)).equals(part))return false;
            return p[0].equals("192")&&p[1].equals("168")&&(p[2].equals("49")||p[2].equals("137"))
                && Integer.parseInt(p[3])>0&&Integer.parseInt(p[3])<255;
        } catch(NumberFormatException e){return false;}
    }
}
