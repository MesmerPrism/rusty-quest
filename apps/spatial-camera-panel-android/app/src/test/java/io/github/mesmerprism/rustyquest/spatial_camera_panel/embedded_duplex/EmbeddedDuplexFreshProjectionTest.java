package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import static org.junit.Assert.*;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;

/** Producer-version and ownership controls. These modeled facts grant no native authority. */
public final class EmbeddedDuplexFreshProjectionTest {
    static final String SESSION="session.duplex."+"a".repeat(64),CONFIG="b".repeat(64),APP="c".repeat(64);
    static JSONObject current(boolean yes)throws Exception{return new JSONObject().put("$schema","rusty.manifold.peer.session_current_receipt.v2")
        .put("session_id",SESSION).put("current",yes).put("rejection_reason",yes?JSONObject.NULL:"expired")
        .put("decision_id",JSONObject.NULL).put("peer_ids",new JSONArray().put("peer.q").put("peer.w"))
        .put("topology_contract_id",JSONObject.NULL).put("validated_at_ms",0L).put("expires_at_ms",yes?100000L:JSONObject.NULL);}
    static JSONObject cursor()throws Exception{return new JSONObject().put("total",0L).put("first",JSONObject.NULL).put("last",JSONObject.NULL)
        .put("recent",new JSONArray()).put("history_sha256",JSONObject.NULL);}
    static JSONObject lifecycle()throws Exception{
        JSONObject value=new JSONObject();
        for(String key:new String[]{"action","config_sha256","native_executor_generation","app_process_generation","observed_at_ms",
            "status","authority_projection","broker_evidence","start_mutation","stop_mutation","route_receipt","route_termination",
            "route_cleanup","termination_action","revoker_adoption","media_completion","media_stop_effect_receipt","owner_failure_diagnostic",
            "owner_failure_diagnostic_status","owner_transport_attempt","authority_clock_read","native_owner_dispatch_failure","activation",
            "renewal_receipts","renewal_total_completed","renewal_first_request_id","renewal_last_request_id","renewal_pending",
            "peer_physical_cleanup","native_host_physical_cleanup","whole_app_physical_cleanup","last_failure","last_failure_action",
            "ongoing_media_policy","current_lifetime_renewal_count","current_owner_validation","last_failure_cause","retired_lifetimes"})value.put(key,JSONObject.NULL);
        return value.put("$schema",EmbeddedDuplexFreshLifecycle.SCHEMA).put("action","start").put("status","active")
            .put("config_sha256",CONFIG).put("native_executor_generation",7L).put("app_process_generation",3L).put("observed_at_ms",0L)
            .put("ongoing_media_policy",new JSONObject().put("$schema","rusty.manifold.peer.ongoing_media_policy.v2"))
            .put("current_lifetime_renewal_count",0L).put("renewal_pending",false).put("retired_lifetimes",cursor());
    }
    static JSONObject ack(String id)throws Exception{return new JSONObject().put("$schema","rusty.quest.embedded_duplex.peer_lifetime_archive_retained.v1")
        .put("body_sha256","sha256:"+"d".repeat(64)).put("app_process_generation",3L).put("app_record_sha256",APP)
        .put("native_executor_generation",7L).put("config_sha256",CONFIG).put("prior_stop_original_id",id)
        .put("storage_identity","modeled-owned-storage:"+id);}
    @Test public void exactV4RequiresActualV2PolicyAndClosedCauseCursor()throws Exception{
        JSONObject value=lifecycle();EmbeddedDuplexFreshLifecycle.require(value);
        JSONObject legacy=new JSONObject().put("$schema","rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1");
        assertTrue(EmbeddedDuplexWholeCloseRollover.localLifecycleProof(legacy));
        assertTrue(EmbeddedDuplexWholeCloseRollover.localLifecycleProof(value));
        value.put("ongoing_media_policy",new JSONObject().put("$schema","rusty.manifold.peer.ongoing_media_policy.v1"));
        assertThrows(IllegalArgumentException.class,()->EmbeddedDuplexFreshLifecycle.require(value));
        JSONObject unknown=lifecycle().put("future_unreviewed",false);
        assertThrows(IllegalArgumentException.class,()->EmbeddedDuplexFreshLifecycle.require(unknown));
        JSONObject missing=lifecycle();missing.remove("retired_lifetimes");
        assertThrows(IllegalArgumentException.class,()->EmbeddedDuplexFreshLifecycle.require(missing));
    }
    @Test public void pendingStartCannotClearOneShotIntent()throws Exception{
        JSONObject pair=new JSONObject().put("$schema","rusty.quest.embedded_duplex.pair_status.v1")
            .put("state","peer_session_current_route_unverified").put("session_id",SESSION).put("native_current_session",current(true))
            .put("route_current",false).put("media_effect_proven",false);
        EmbeddedDuplexStartPreflight intent=EmbeddedDuplexStartPreflight.prepare(EmbeddedDuplexPairStatus.parse(pair.toString()),CONFIG,APP,4L);
        EmbeddedDuplexStartIntentSlot slot=new EmbeddedDuplexStartIntentSlot();slot.prepare(intent);slot.dispatch(intent);
        slot.acknowledge(intent,lifecycle().put("status","pending").toString());
        assertThrows(IllegalStateException.class,()->slot.prepare(intent));
        slot.acknowledge(intent,lifecycle().toString());slot.prepare(intent);
    }
    @Test public void compactArchiveHasExactEndpointActorJoins()throws Exception{
        JSONObject value=lifecycle(),first=ack("1".repeat(32)),last=ack("2".repeat(32));
        value.put("retired_lifetimes",cursor().put("total",2L).put("first",first).put("last",last)
            .put("recent",new JSONArray().put(first).put(last)).put("history_sha256","sha256:"+"e".repeat(64)));
        EmbeddedDuplexFreshLifecycle.require(value);
        last.put("native_executor_generation",8L);
        assertThrows(IllegalArgumentException.class,()->EmbeddedDuplexFreshLifecycle.require(value));
        last.put("native_executor_generation",7L).put("app_record_sha256","f".repeat(64));
        assertThrows(IllegalArgumentException.class,()->EmbeddedDuplexFreshLifecycle.require(value));
    }
    @Test public void typedNoncurrentCausePreservesZeroTimeAndNullOptions()throws Exception{
        JSONObject cause=new JSONObject().put("$schema","rusty.quest.embedded_duplex.operation_failure.v1")
            .put("code","pair_session_not_current").put("operation","renew_authority").put("session_id",SESSION)
            .put("observed_at_ms",0L).put("current_session",current(false));
        JSONObject value=lifecycle().put("last_failure_cause",cause);EmbeddedDuplexFreshLifecycle.require(value);
        cause.put("observed_at_ms",1L);
        assertThrows(IllegalArgumentException.class,()->EmbeddedDuplexFreshLifecycle.require(value));
    }
    @Test public void freshResultUsesFinalWitnessAndDoesNotInventMediaRoute()throws Exception{
        JSONObject result=new JSONObject().put("$schema","rusty.quest.embedded_duplex.fresh_pair_setup_result.v1")
            .put("request_id","ceremony.duplex.1234").put("session_id",SESSION).put("state","peer_session_current_route_unverified")
            .put("local_current_session",current(true)).put("remote_current_session",current(true))
            .put("final_local_current_session",current(false)).put("final_remote_current_session",current(true))
            .put("route_current",false).put("media_effect_proven",false);
        EmbeddedDuplexPairStatus parsed=EmbeddedDuplexPairStatus.parse(result.toString());
        assertTrue(parsed.selectedFreshPolicy);assertFalse(parsed.localSessionCurrent);assertTrue(parsed.remoteSessionCurrent);
        assertEquals("expired",parsed.localSessionRejectionReason);assertEquals(0L,parsed.localSessionValidatedAtMs);
        assertFalse(parsed.routeCurrent);assertFalse(parsed.mediaEffectProven);
        result.remove("final_local_current_session");
        assertThrows(IllegalArgumentException.class,()->EmbeddedDuplexPairStatus.parse(result.toString()));
    }
    @Test public void canonicalHashesDoNotUseJSONObjectOrderOrSlashEscaping()throws Exception{
        JSONObject value=new JSONObject().put("z",true).put("a","é/\n").put("n",JSONObject.NULL);
        assertEquals("{\"a\":\"é/\\n\",\"n\":null,\"z\":true}",EmbeddedDuplexCanonicalJson.encode(value));
        assertThrows(IllegalArgumentException.class,()->EmbeddedDuplexCanonicalJson.encode(new JSONObject().put("x",1.5)));
        assertThrows(IllegalArgumentException.class,()->EmbeddedDuplexCanonicalJson.encode("\ud800"));
    }
}
