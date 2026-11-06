package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig;

/** Build-fixed qualified TARGET keys; Native still joins actual keystore/bootstrap. */
final class EmbeddedDuplexFreshSetupPolicy {
    static final String POLICY_DOMAIN="rusty.quest.embedded_duplex.fresh_setup_build_policy.v1";
    static final String TARGET_DOMAIN="rusty.quest.embedded_duplex.fresh_setup_build_enrollment.v1";
    static JSONObject policy() throws Exception {
        return new JSONObject().put("$schema","rusty.manifold.peer.fresh_pair_setup_policy.v2")
            .put("policy_id","policy.quest.embedded-duplex.fresh-setup.v2").put("revision",1L).put("authorization","source_bound")
            .put("initial_status",new JSONObject().put("$schema","rusty.manifold.peer.fresh_pair_setup_initial_status_template.v1")
                .put("trust_domain","trust.morphospace.peer").put("roles",new JSONArray().put("observer").put("rendezvous"))
                .put("capability_ids",new JSONArray().put("capability.rendezvous.ble").put("capability.route.rust-direct-p2p").put("capability.topology.wifi-direct")))
            .put("credential_validity_ms",EmbeddedDuplexSetupCredentials.selectedHorizon(300000L));
    }
    static String digest(String domain,Object value) throws Exception {
        return "sha256:"+EmbeddedDuplexCanonicalJson.sha256Bytes((domain+'\0'+EmbeddedDuplexCanonicalJson.encode(value)).getBytes(StandardCharsets.UTF_8));
    }
    private static void fields(JSONObject value,String...expected) throws Exception {
        TreeSet<String> actual=new TreeSet<>();Iterator<String> it=value.keys();while(it.hasNext())actual.add(it.next());
        TreeSet<String> want=new TreeSet<>();for(String key:expected)want.add(key);
        if(!actual.equals(want))throw new IllegalStateException("fresh build closed fields differ");
    }
    static void apply(String selection,String mediaSchema,boolean fixture,boolean concurrent,
            EmbeddedDuplexEnrollment enrollment,JSONObject embedded) throws Exception {
        if("none".equals(selection)){
            if(!BuildConfig.EMBEDDED_DUPLEX_FRESH_POLICY_SHA256.isEmpty()
                ||!BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256.isEmpty()
                ||!BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256.isEmpty()
                ||!BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64.isEmpty())
                throw new IllegalStateException("unselected fresh artifact carries target policy");
            return; // Byte-identical old runtime JSON omission.
        }
        if(!"source-bound-v2".equals(selection)||fixture||!concurrent
            ||!"rusty.manifold.peer.ongoing_media_policy.v2".equals(mediaSchema))
            throw new IllegalStateException("fresh setup requires selected concurrent real ongoing owners");
        if(!digest(POLICY_DOMAIN,policy()).equals(BuildConfig.EMBEDDED_DUPLEX_FRESH_POLICY_SHA256)
            ||!BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("fresh packaged policy/target pin differs");
        byte[] bytes=Base64.getDecoder().decode(BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64);
        if(!EmbeddedDuplexCanonicalJson.sha256Bytes(bytes).equals(BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256))
            throw new IllegalStateException("fresh packaged raw target pin differs");
        String raw=new String(bytes,StandardCharsets.UTF_8);
        if(!java.util.Arrays.equals(bytes,raw.getBytes(StandardCharsets.UTF_8)))throw new IllegalStateException("fresh target UTF8 differs");
        JSONObject target=new JSONObject(raw);
        if(!raw.equals(EmbeddedDuplexCanonicalJson.encode(target))
            ||!digest(TARGET_DOMAIN,target).equals(BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256))
            throw new IllegalStateException("fresh packaged canonical target differs");
        fields(target,"$schema","operator_id","keys");
        if(!"rusty.manifold.peer.fresh_setup_enrollment_selection.v1".equals(target.getString("$schema"))
            ||!enrollment.trustedOperatorId.equals(target.getString("operator_id")))throw new IllegalStateException("fresh target operator differs");
        JSONArray keys=target.getJSONArray("keys");if(keys.length()!=2)throw new IllegalStateException("fresh target key cardinality");
        TreeSet<String> fingerprints=new TreeSet<>();String previous=null;
        boolean local=false,remote=false;
        for(int i=0;i<2;i++){
            JSONObject key=keys.getJSONObject(i);
            fields(key,"$schema","peer_id","credential_id","trust_domain","key_id","key_generation","algorithm","public_key_hex","public_key_sha256","key_fingerprint");
            String peer=key.getString("peer_id"),publicHex=key.getString("public_key_hex");
            if(previous!=null&&previous.compareTo(peer)>=0)throw new IllegalStateException("fresh keys not canonical");previous=peer;
            String expectedKey,expectedPublic;
            if(peer.equals(enrollment.installed.localPeerId)){local=true;expectedKey=enrollment.installed.localKeyId;expectedPublic=enrollment.installed.localPublicKeyHex;}
            else if(peer.equals(enrollment.installed.remotePeerId)){remote=true;expectedKey=enrollment.remoteKeyId;expectedPublic=enrollment.remotePublicKeyHex;}
            else throw new IllegalStateException("fresh target foreign peer");
            if(!publicHex.matches("[0-9a-f]{64}"))throw new IllegalStateException("fresh target public key shape");
            byte[] publicBytes=new byte[32];for(int n=0;n<32;n++)publicBytes[n]=(byte)Integer.parseInt(publicHex.substring(n*2,n*2+2),16);
            String hash=EmbeddedDuplexCanonicalJson.sha256Bytes(publicBytes);
            if(!"rusty.manifold.peer.fresh_setup_enrollment_key.v1".equals(key.getString("$schema"))
                ||!("credential.duplex."+peer+".1").equals(key.getString("credential_id"))
                ||!"trust.morphospace.peer".equals(key.getString("trust_domain"))
                ||!Long.valueOf(1L).equals(Long.valueOf(key.getLong("key_generation")))||!"ed25519".equals(key.getString("algorithm"))
                ||!expectedKey.equals(key.getString("key_id"))||!expectedPublic.equals(publicHex)
                ||!("ed25519."+hash).equals(expectedKey)||!("sha256:"+hash).equals(key.getString("public_key_sha256"))
                ||!("fingerprint."+hash).equals(key.getString("key_fingerprint"))||!fingerprints.add("fingerprint."+hash))
                throw new IllegalStateException("fresh target differs from actual retained enrollment keys");
        }
        if(!local||!remote||fingerprints.size()!=2)throw new IllegalStateException("fresh target identity cardinality");
        JSONArray trusted=new JSONArray();for(String fingerprint:fingerprints)trusted.put(fingerprint);
        embedded.put("trusted_key_fingerprints",trusted).put("fresh_setup_local_peer_id",enrollment.installed.localPeerId)
            .put("fresh_setup_enrollment_selection",target);
    }
    private EmbeddedDuplexFreshSetupPolicy(){}
}
