package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;

/** Actual native renderer owner classification, separate from diagnostic counters. */
final class EmbeddedDuplexRendererRetirement {
    static boolean currentTerminal()throws Exception{
        return terminal(new JSONObject(EmbeddedDuplexNative.noMediaRendererRetirementStatus()));
    }
    static boolean terminal(JSONObject actual)throws Exception{
        EmbeddedDuplexFreshHandoffStore.keys(actual,"$schema","state","construction_attempts","active_workers","claimed_route_generation",
            "worker_state","cleanup_code","carrier_live","pending_frame");
        if(!"rusty.quest.embedded_duplex.renderer_retirement_status.v1".equals(actual.getString("$schema")))
            throw new IllegalArgumentException("actual renderer retirement schema");
        long attempts=number(actual,"construction_attempts"),workers=number(actual,"active_workers"),cleanup=number(actual,"cleanup_code");
        if(!actual.isNull("claimed_route_generation"))number(actual,"claimed_route_generation");
        if(!actual.isNull("worker_state") && !java.util.Arrays.asList("running","finished_unjoined").contains(actual.getString("worker_state")))
            throw new IllegalArgumentException("actual renderer worker state");
        if(!(actual.get("carrier_live") instanceof Boolean) || !(actual.get("pending_frame") instanceof Boolean))
            throw new IllegalArgumentException("actual renderer physical Boolean");
        String state=actual.getString("state");
        if(!java.util.Arrays.asList("never_created","retired","cleanup_rejected","pending_or_unproven").contains(state))
            throw new IllegalArgumentException("actual renderer closed state");
        if(cleanup==3){if(!"cleanup_rejected".equals(state))throw new IllegalStateException("actual renderer rejection misclassified");return false;}
        if("cleanup_rejected".equals(state))throw new IllegalStateException("actual renderer rejected state/code differs");
        boolean absent=workers==0 && actual.isNull("claimed_route_generation") && actual.isNull("worker_state")
            && !actual.getBoolean("carrier_live") && !actual.getBoolean("pending_frame");
        if("never_created".equals(state)){
            if(!absent || attempts!=0 || cleanup!=0)throw new IllegalStateException("actual renderer NeverCreated not earned");return true;
        }
        if("retired".equals(state)){
            if(!absent || attempts==0 || cleanup!=2)throw new IllegalStateException("actual renderer joined retirement not earned");return true;
        }
        return false;
    }
    static boolean currentFreshEligible(String actualOwnState,long actualAppGeneration)throws Exception{
        return freshEligible(new JSONObject(EmbeddedDuplexNative.freshSetupRendererEligibilityStatus()),actualOwnState,actualAppGeneration);
    }
    static boolean freshEligible(JSONObject actual,String actualOwnState,long actualAppGeneration)throws Exception{
        EmbeddedDuplexFreshHandoffStore.keys(actual,"$schema","state","own_process_generation","own_source_generation",
            "construction_attempts","active_workers","peer_source_inactive","retained_peer_payloads","cleanup_code","carrier_live");
        if(!"rusty.quest.embedded_duplex.renderer_fresh_setup_eligibility.v1".equals(actual.getString("$schema")))
            throw new IllegalArgumentException("actual fresh renderer schema");
        long attempts=number(actual,"construction_attempts"),workers=number(actual,"active_workers"),cleanup=number(actual,"cleanup_code");
        long retained=number(actual,"retained_peer_payloads");
        if(!actual.isNull("own_process_generation"))number(actual,"own_process_generation");
        if(!actual.isNull("own_source_generation"))number(actual,"own_source_generation");
        if(!(actual.get("peer_source_inactive") instanceof Boolean)||!(actual.get("carrier_live") instanceof Boolean))
            throw new IllegalArgumentException("actual fresh renderer physical Boolean");
        String state=actual.getString("state");
        if(!java.util.Arrays.asList("terminal","own_live_independent","pending_or_unproven","cleanup_rejected").contains(state))
            throw new IllegalArgumentException("actual fresh renderer closed state");
        if(cleanup==3){if(!"cleanup_rejected".equals(state))throw new IllegalStateException("fresh renderer rejection misclassified");return false;}
        if("cleanup_rejected".equals(state))throw new IllegalStateException("fresh renderer rejected state/code differs");
        if("pending_or_unproven".equals(state))return false;
        if(!actual.getBoolean("peer_source_inactive")||retained!=0)
            throw new IllegalStateException("actual retained Peer renderer/source payloads remain");
        if("terminal".equals(state)){
            if(workers!=0||actual.getBoolean("carrier_live")||!((attempts==0&&cleanup==0)||(attempts>0&&cleanup==2)))
                throw new IllegalStateException("actual fresh renderer terminal not earned");
            return java.util.Arrays.asList("retired","not_created").contains(actualOwnState);
        }
        // Native owns exact renderer claim/window/current Own epoch and real
        // Peer frame-root accounting. Java independently joins its actual Own
        // runtime classification and actual held app generation.
        if(actual.isNull("own_process_generation")||actual.isNull("own_source_generation")
            ||actual.getLong("own_process_generation")!=actualAppGeneration||actual.getLong("own_source_generation")<=0
            ||workers!=1||attempts==0||!actual.getBoolean("carrier_live"))
            throw new IllegalStateException("actual independent Own renderer witness incomplete");
        return "live_independent".equals(actualOwnState);
    }
    private static long number(JSONObject value,String key)throws Exception{
        Object n=value.get(key);if(!(n instanceof Integer||n instanceof Long)||((Number)n).longValue()<0)
            throw new IllegalArgumentException("actual renderer nonnegative integer");return ((Number)n).longValue();
    }
}
