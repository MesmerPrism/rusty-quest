package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import io.github.mesmerprism.rustyquest.media.OwnerDispatchTcpEndpoint;
import java.util.HashMap;
import java.util.HashSet;
import org.json.JSONObject;

/** Actual incoming request-task custody. Native alone verifies tickets/authority. */
final class EmbeddedDuplexIncomingFreshPhases {
    private static final class Group {
        final String nativeId,phaseId,priorId;
        final HashSet<OwnerDispatchTcpEndpoint.RequestSpan> spans=new HashSet<>();
        boolean fenced;JSONObject archive;String archiveStatus;
        Group(String n,String p,String prior){nativeId=n;phaseId=p;priorId=prior;}
        boolean terminal(){for(OwnerDispatchTcpEndpoint.RequestSpan s:spans)if(!s.terminal())return false;return true;}
    }
    private final HashMap<String,Group> groups=new HashMap<>();
    private String nativeId,currentPhase,pendingPhase,firstPreparationRoot;
    private JSONObject firstPreparationArchive;
    private OwnerDispatchTcpEndpoint endpoint;

    synchronized void bind(OwnerDispatchTcpEndpoint actualEndpoint,String n,String prior,String phase,String purpose)throws Exception{
        id(n);id(phase);if(prior!=null)id(prior);
        OwnerDispatchTcpEndpoint.RequestSpan span=OwnerDispatchTcpEndpoint.currentRequestSpan();
        if(actualEndpoint==null || !actualEndpoint.ownsRequestSpan(span))throw new IllegalStateException("actual verified phase RequestSpan absent");
        if(endpoint!=null && endpoint!=actualEndpoint)throw new IllegalStateException("incoming phase endpoint owner changed");
        if(!java.util.Arrays.asList("initial","initial_preparation","current_phase","linked_fence").contains(purpose))throw new IllegalArgumentException("closed incoming phase purpose");
        if("initial".equals(purpose)){
            if(prior!=null || !phase.equals(n))throw new IllegalArgumentException("initial phase identity");
            if(nativeId!=null && !nativeId.equals(n)){
                for(Group old:groups.values())if(!old.terminal() || old.archive==null || !java.util.Arrays.asList("completed","preparation_completed").contains(old.archiveStatus))
                    throw new IllegalStateException("old incoming setup not archived and genuinely joined");
                groups.clear();currentPhase=null;pendingPhase=null;
            }
            if(nativeId!=null && nativeId.equals(n) && !phase.equals(currentPhase))throw new IllegalStateException("incoming initial phase cannot replay into later phase");
            Group g=groups.get(phase);
            if(g==null){g=new Group(n,phase,null);groups.put(phase,g);}
            if(g.fenced || g.archive!=null)throw new IllegalStateException("fenced/archived initial phase cannot reopen");
            nativeId=n;currentPhase=phase;g.spans.removeIf(OwnerDispatchTcpEndpoint.RequestSpan::terminal);g.spans.add(span);
        }else if("initial_preparation".equals(purpose)){
            // Fixed Native verified first-preparation path: there was NO old
            // local phase task. Register only this real new handler/group.
            if(prior!=null||phase.equals(n)||nativeId!=null||!groups.isEmpty())
                throw new IllegalStateException("first preparation is not an empty local incoming custody boundary");
            Group next=new Group(n,phase,n);next.spans.add(span);groups.put(phase,next);
            nativeId=n;pendingPhase=phase;firstPreparationRoot=n;
        }else if("current_phase".equals(purpose)){
            Group g=groups.get(phase);
            if(prior!=null || !n.equals(nativeId) || !(phase.equals(currentPhase)||phase.equals(pendingPhase)) || g==null || g.fenced || g.archive!=null)
                throw new IllegalStateException("actual current incoming phase differs or is fenced");
            g.spans.removeIf(OwnerDispatchTcpEndpoint.RequestSpan::terminal);g.spans.add(span);
        }else{
            if(prior==null || prior.equals(phase) || !n.equals(nativeId) || !prior.equals(currentPhase))
                throw new IllegalStateException("linked phase signed predecessor differs");
            Group old=groups.get(prior);
            if(old==null || !old.terminal())throw new IllegalStateException("actual old incoming handlers/socket/tasks still owned");
            if(pendingPhase!=null && !phase.equals(pendingPhase))throw new IllegalStateException("different incoming phase already pending");
            Group next=groups.get(phase);
            if(next==null){
                if(groups.size()>=2)throw new IllegalStateException("prior incoming phase custody not handed off");
                next=new Group(n,phase,prior);groups.put(phase,next);
            }
            if(next.fenced || !prior.equals(next.priorId))throw new IllegalStateException("incoming successor binding changed");
            // Native has verified the actual ticket/source/Stop and fenced its
            // old phase before this fixed callback. No old task can re-register.
            old.fenced=true;pendingPhase=phase;
            next.spans.removeIf(OwnerDispatchTcpEndpoint.RequestSpan::terminal);next.spans.add(span);
        }
        endpoint=actualEndpoint;
    }

    synchronized void requireArchive(JSONObject body)throws Exception{
        String phase=body.getString("phase_request_id");Group old=groups.get(phase);
        OwnerDispatchTcpEndpoint.RequestSpan current=OwnerDispatchTcpEndpoint.currentRequestSpan();
        if(old==null && firstPreparationRoot!=null && phase.equals(firstPreparationRoot)){
            requireFirstPreparationArchive(body,current);return;
        }
        if(old==null || !old.nativeId.equals(body.getString("native_request_id")) || endpoint==null
                || !endpoint.ownsRequestSpan(current) || !old.terminal())throw new IllegalStateException("actual old incoming phase/tasks differ");
        if(java.util.Arrays.asList("fenced_unknown","preparation_fenced_unknown","reservation_fenced_unknown").contains(body.getString("phase_status"))){
            Group next=pendingPhase==null?null:groups.get(pendingPhase);
            if(!old.fenced || next==null || !phase.equals(next.priorId) || !next.spans.contains(current))
                throw new IllegalStateException("fenced incoming archive lacks actual new fence handler");
        }else if(!java.util.Arrays.asList("completed","preparation_completed").contains(body.getString("phase_status")))throw new IllegalArgumentException("incoming archive phase status");
    }
    synchronized void archived(JSONObject body,JSONObject ack)throws Exception{
        requireArchive(body);Group g=groups.get(body.getString("phase_request_id"));
        if(g==null){
            if(firstPreparationArchive!=null&&!EmbeddedDuplexCanonicalJson.encode(firstPreparationArchive).equals(EmbeddedDuplexCanonicalJson.encode(ack)))
                throw new IllegalStateException("first preparation archive ACK changed");
            firstPreparationArchive=new JSONObject(ack.toString());return;
        }
        if(g.archive!=null && !EmbeddedDuplexCanonicalJson.encode(g.archive).equals(EmbeddedDuplexCanonicalJson.encode(ack)))
            throw new IllegalStateException("incoming phase archive ACK changed");
        g.archive=new JSONObject(ack.toString());g.archiveStatus=body.getString("phase_status");
    }
    synchronized void incomingHandoff(JSONObject body)throws Exception{
        Group old=groups.get(body.getString("prior_phase_id"));Group next=groups.get(body.getString("phase_request_id"));
        OwnerDispatchTcpEndpoint.RequestSpan current=OwnerDispatchTcpEndpoint.currentRequestSpan();
        if(old==null && firstPreparationRoot!=null && firstPreparationRoot.equals(body.getString("prior_phase_id"))){
            if(next==null||!firstPreparationRoot.equals(next.priorId)||!next.phaseId.equals(pendingPhase)
                ||!next.nativeId.equals(body.getString("native_request_id"))||firstPreparationArchive==null
                ||endpoint==null||!endpoint.ownsRequestSpan(current)||!next.spans.contains(current)
                ||!EmbeddedDuplexCanonicalJson.encode(firstPreparationArchive).equals(EmbeddedDuplexCanonicalJson.encode(body.getJSONObject("local_archive"))))
                throw new IllegalStateException("actual first-preparation adoption/archive/new-handler differs");
            return;
        }
        if(old==null || next==null || !old.fenced || !old.terminal() || old.archive==null
                || !java.util.Arrays.asList("fenced_unknown","preparation_fenced_unknown","reservation_fenced_unknown").contains(old.archiveStatus) || !next.priorId.equals(old.phaseId)
                || !next.nativeId.equals(body.getString("native_request_id")) || !next.phaseId.equals(pendingPhase)
                || endpoint==null || !endpoint.ownsRequestSpan(current) || !next.spans.contains(current)
                || !EmbeddedDuplexCanonicalJson.encode(old.archive).equals(EmbeddedDuplexCanonicalJson.encode(body.getJSONObject("local_archive"))))
            throw new IllegalStateException("actual incoming Native adoption/archive/task join differs");
    }
    synchronized void adopted(JSONObject body)throws Exception{
        incomingHandoff(body);String old=body.getString("prior_phase_id");currentPhase=body.getString("phase_request_id");pendingPhase=null;
        groups.remove(old); // Only genuinely terminal old spans; first preparation had no old task.
        if(old.equals(firstPreparationRoot)){firstPreparationRoot=null;firstPreparationArchive=null;}
    }
    private void requireFirstPreparationArchive(JSONObject body,OwnerDispatchTcpEndpoint.RequestSpan current)throws Exception{
        Group next=pendingPhase==null?null:groups.get(pendingPhase);
        if(!"reservation_fenced_unknown".equals(body.getString("phase_status"))||!body.isNull("phase_state")||!body.isNull("preparation")
            ||next==null||!firstPreparationRoot.equals(next.priorId)||!firstPreparationRoot.equals(body.getString("native_request_id"))
            ||endpoint==null||!endpoint.ownsRequestSpan(current)||!next.spans.contains(current))
            throw new IllegalStateException("actual first preparation/root reservation archive boundary absent");
        JSONObject reservation=body.getJSONObject("native_reservation");
        EmbeddedDuplexFreshHandoffStore.keys(reservation,"id","session","original_binding","initial_original_binding","authority_or_signature_entered",
            "declined_before_dispatch","failure","prime_pending","prime_progress","prime_origin","prime_joined");
        if(!firstPreparationRoot.equals(reservation.getString("id"))||reservation.getBoolean("authority_or_signature_entered")
            ||!reservation.isNull("prime_pending")||reservation.getJSONArray("prime_progress").length()!=0||reservation.getJSONArray("prime_joined").length()!=0)
            throw new IllegalStateException("actual old reservation entered work; cannot invent absent task");
        JSONObject incoming=new JSONObject().put("kind","incoming_fresh_setup").put("native_request_id",firstPreparationRoot);
        if(!EmbeddedDuplexCanonicalJson.encode(incoming).equals(EmbeddedDuplexCanonicalJson.encode(reservation.getJSONObject("initial_original_binding")))
            ||!EmbeddedDuplexCanonicalJson.encode(incoming).equals(EmbeddedDuplexCanonicalJson.encode(body.getJSONObject("original_binding"))))
            throw new IllegalStateException("actual first preparation binding differs");
        JSONObject origin=reservation.getJSONObject("prime_origin"),first=origin.getJSONObject("first_preparation");
        EmbeddedDuplexFreshHandoffStore.keys(first,"$schema","native_request_id","phase_request_id","app_process_generation","app_record_sha256",
            "native_executor_generation","config_sha256","observed_at_ms","source_snapshot_raw_sha256","source_snapshot");
        if(!"rusty.quest.embedded_duplex.fresh_first_preparation_origin.v1".equals(first.getString("$schema"))
            ||!firstPreparationRoot.equals(first.getString("native_request_id"))||!next.phaseId.equals(first.getString("phase_request_id"))
            ||!first.getString("source_snapshot_raw_sha256").matches("sha256:[0-9a-f]{64}")
            ||!first.getString("source_snapshot_raw_sha256").equals(origin.getString("source_snapshot_raw_sha256"))
            ||!EmbeddedDuplexCanonicalJson.encode(first.getJSONObject("source_snapshot")).equals(EmbeddedDuplexCanonicalJson.encode(origin.getJSONObject("source_snapshot"))))
            throw new IllegalStateException("actual pre-reservation first source proof differs");
        Object observed=first.get("observed_at_ms");if(!(observed instanceof Long||observed instanceof Integer)||((Number)observed).longValue()<0)
            throw new IllegalArgumentException("actual first preparation observation clock");
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})
            if(!EmbeddedDuplexCanonicalJson.sameValue(body.get(key),first.get(key))||!EmbeddedDuplexCanonicalJson.sameValue(body.get(key),origin.get(key)))throw new IllegalStateException("actual first source actor/config differs");
        JSONObject source=first.getJSONObject("source_snapshot"),fresh=source.getJSONObject("fresh_pair_setup");
        if(source.getJSONObject("enrollment").getJSONArray("credentials").length()!=0
            ||source.getJSONObject("accepted_peers").getJSONArray("peers").length()!=0
            ||fresh.getJSONArray("prime_enrollments").length()!=0||fresh.getJSONArray("receipts").length()!=0
            ||fresh.getJSONArray("phase_preparations").length()!=0||!fresh.isNull("archive"))
            throw new IllegalStateException("actual pre-reservation first source was not unentered");
        // The OLD Native raw-source SHA is retained producer evidence, not
        // recomputed from a reordered parsed object. authority_snapshot may
        // truthfully include the NEW preparation's effects; it is not oldsource.
    }
    synchronized void assertNotArchivedOrFenced(String n,String phase)throws Exception{
        Group held=groups.get(phase);
        if(held!=null && n.equals(held.nativeId) && (held.fenced || held.archive!=null))
            throw new IllegalStateException("actual retained incoming phase cannot reopen before history mutation");
    }
    synchronized JSONObject currentBinding(String n,String phase)throws Exception{
        Group g=groups.get(phase);OwnerDispatchTcpEndpoint.RequestSpan actual=OwnerDispatchTcpEndpoint.currentRequestSpan();
        boolean current=g!=null && !g.fenced && (phase.equals(currentPhase) || phase.equals(pendingPhase));
        if(!current || !n.equals(g.nativeId) || endpoint==null || !endpoint.ownsRequestSpan(actual) || !g.spans.contains(actual))
            throw new IllegalStateException("actual registered incoming phase context absent");
        return new JSONObject().put("kind","incoming_fresh_setup").put("native_request_id",n);
    }
    /** Read-only preflight for the already authenticated preparation frame.
     * No new span joins the old group and no phase is fenced/adopted here. */
    synchronized JSONObject preparationObservationBinding(OwnerDispatchTcpEndpoint actualEndpoint,String n,String prior,String phase)throws Exception{
        id(n);id(phase);if(prior!=null)id(prior);
        OwnerDispatchTcpEndpoint.RequestSpan span=OwnerDispatchTcpEndpoint.currentRequestSpan();
        if(actualEndpoint==null || !actualEndpoint.ownsRequestSpan(span) || (endpoint!=null && endpoint!=actualEndpoint))
            throw new IllegalStateException("actual preparation observation RequestSpan absent");
        if(prior==null){
            if(phase.equals(n)||nativeId!=null||!groups.isEmpty())
                throw new IllegalStateException("first preparation observation requires empty incoming custody");
        }else{
            if(prior.equals(phase)||!n.equals(nativeId)||!prior.equals(currentPhase))
                throw new IllegalStateException("preparation observation predecessor differs");
            Group old=groups.get(prior),next=groups.get(phase);
            if(old==null||!old.terminal()||(pendingPhase!=null&&!phase.equals(pendingPhase))
                ||(next==null&&groups.size()>=2)||(next!=null&&(next.fenced||!prior.equals(next.priorId))))
                throw new IllegalStateException("preparation observation old tasks or successor differ");
        }
        return new JSONObject().put("kind","incoming_fresh_setup").put("native_request_id",n);
    }
    synchronized boolean admissionFor(String n){
        if(nativeId==null)return groups.isEmpty();
        if(nativeId.equals(n))return allTerminal(n);
        for(Group g:groups.values())if(!g.terminal() || g.archive==null
                || !java.util.Arrays.asList("completed","preparation_completed").contains(g.archiveStatus))return false;
        return true;
    }
    synchronized boolean allTerminal(String n){
        if(nativeId==null)return groups.isEmpty();
        if(!nativeId.equals(n))return false;
        for(Group g:groups.values())if(!g.terminal())return false;return true;
    }
    synchronized boolean contains(OwnerDispatchTcpEndpoint.RequestSpan actual){
        for(Group g:groups.values())if(g.spans.contains(actual))return true;return false;
    }
    synchronized String currentNativeId(){return nativeId;}
    static void id(String value){if(value==null || !value.matches("[a-z][a-z0-9_.-]{0,255}"))throw new IllegalArgumentException("actual native phase identity");}
}
