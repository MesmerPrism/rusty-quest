package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Source-owned mutation custody. Observation and ACK never dispatch another effect. */
public final class EmbeddedDuplexOriginals {
    public static final String TRANSPORT = "rusty.quest.embedded_duplex.operator_transport.v2";
    private static final String RECORD = "rusty.quest.embedded_duplex.operator_original.v1";
    private static final String CONTINUATION_RECORD = "rusty.quest.embedded_duplex.operator_original.v2";
    private static final Set<String> FRESH_OPERATIONS = Set.of("bootstrap_real_peer", "pair_session", "fresh_pair_continue",
            "prepare_peer_start", "abort_prepared_peer_start", "reestablish_peer_roots", "close_no_media");
    private static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;
    private static final Set<String> OPERATIONS = Set.of("start", "renew_authority", "peer_stop",
            "peer_revoke", "whole_app_close", "concurrent_arm", "policy_update", "blend_update", "bootstrap_real_peer", "pair_session", "fresh_pair_continue",
            "prepare_peer_start", "abort_prepared_peer_start", "reestablish_peer_roots", "close_no_media");
    private static final Set<String> FIELDS = Set.of("schema", "original_id", "operation", "request_sha256",
            "original_challenge", "state", "effect_state", "actor_binding", "native_setup_binding",
            "response", "response_sha256", "failure", "outcome_sha256", "retention", "consumer_receipt_sha256");
    private static final ThreadLocal<Record> CURRENT = new ThreadLocal<>();
    private static EmbeddedDuplexOriginals singleton;
    private EmbeddedDuplexRunContext capturedRun;
    private boolean readOnly;
    public static final String RUN_TRANSPORT="rusty.quest.embedded_duplex.operator_transport.v3";
    private final File root, directory;
    private final ActorSource actors;
    private final DirectorySync sync;
    private final HashMap<String, Record> live = new HashMap<>();

    interface ActorSource {
        JSONObject snapshot() throws Exception;
        long appGeneration();
        boolean settled(JSONObject actor, long generation) throws Exception;
    }
    interface DirectorySync { void run() throws Exception; }
    static final class Record {
        final EmbeddedDuplexOriginals owner;
        final JSONObject row;
        CompletableFuture<Bundle> future;
        Throwable persistenceFailure;
        int commands;
        long appGeneration;
        boolean boundActor, callbackJoined, restored;

        final CompletableFuture<Void> callbackTerminal = new CompletableFuture<>();
        Throwable retainedFailure, joinPublicationFailure;
        Object retainedCleanupOwner;
        JSONObject physicalJoinProof, continuationHandoff;
        long physicalJoinAppGeneration, physicalJoinNativeGeneration;
        String physicalJoinAppRecord;
        final AtomicBoolean desiredStop = new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger enteredCommandStages = new java.util.concurrent.atomic.AtomicInteger();
        boolean operationJoined;
        volatile CompletableFuture<?> actualServiceDeliveredFuture;
        volatile boolean serviceCompletionCallbackRegistered, serviceDeliveryAccepted;
        boolean continuationWorkerJoined() { return future != null && future.isDone() && !future.isCancelled()
                && callbackTerminal.isDone() && !callbackTerminal.isCompletedExceptionally()
                && enteredCommandStages.get() == 0 && commands == 0; }
        Record(EmbeddedDuplexOriginals owner, JSONObject row) { this.owner=owner; this.row=row; }
        String id() { return row.optString("original_id"); }
    }

    public static synchronized EmbeddedDuplexOriginals forApplication(Context context) throws Exception {
        Context app=Objects.requireNonNull(context).getApplicationContext();
        EmbeddedDuplexRunContext run=EmbeddedDuplexProcessHost.operationalContext(app);
        if(singleton==null || !singleton.root.equals(run.root)) {
            singleton=new EmbeddedDuplexOriginals(run.root,new ActorSource() {
                public JSONObject snapshot()throws Exception{return EmbeddedDuplexProcessHost.originalActor();}
                public long appGeneration(){return EmbeddedDuplexProcessHost.currentAppGeneration();}
                public boolean settled(JSONObject actor,long generation)throws Exception{return EmbeddedDuplexProcessHost.originalActorSettled(actor,generation);}
            },()->{
                // Sync the parent too: the first directory entry must survive a crash.
                for (File directory : new File[]{run.root,
                        new File(run.root, "operator-originals-v1")}) {
                    java.io.FileDescriptor fd=Os.open(directory.getAbsolutePath(),
                            OsConstants.O_RDONLY|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);
                    try{Os.fsync(fd);}finally{Os.close(fd);}
                }
            });
            singleton.capturedRun=run;
        }
        return singleton;
    }

    // Package-private host seam replaces only actual app/OS observations.
    EmbeddedDuplexOriginals(File noBackup,ActorSource actors,DirectorySync sync)throws Exception {
        this.root=noBackup;this.actors=Objects.requireNonNull(actors);this.sync=Objects.requireNonNull(sync);
        regularDirectory(root);directory=new File(root,"operator-originals-v1");
        if(!directory.exists()&&!directory.mkdir())throw new IllegalStateException("original directory create");
        regularDirectory(directory);Os.chmod(directory.getAbsolutePath(),0700);sync.run();
    }
    private static void regularDirectory(File file)throws Exception {
        if(!Files.isDirectory(file.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file.toPath()))
            throw new IllegalStateException("original directory type");
    }
    private static void validateNativeSetupBinding(JSONObject row)throws Exception{
        if(row.isNull("native_setup_binding"))return;
        JSONObject binding=row.getJSONObject("native_setup_binding");
        if(!Set.of("pair_session","fresh_pair_continue").contains(row.getString("operation"))
            ||!keys(binding).equals(Set.of("setup_request_id","context_sha256"))
            ||!binding.getString("setup_request_id").matches("[a-z][a-z0-9_.-]{0,255}")
            ||!binding.isNull("context_sha256")&&!binding.getString("context_sha256").matches("sha256:[0-9a-f]{64}"))
            throw new IllegalStateException("retained Native setup binding differs");
    }
    private void requireSelected(){if(capturedRun!=null&&!readOnly)capturedRun.requireSame(EmbeddedDuplexProcessHost.selectedContext());}
    private boolean validRequestScope(JSONObject request)throws Exception{
        if(capturedRun==null||capturedRun.legacy())return TRANSPORT.equals(request.getString("schema"))&&!request.getJSONObject("extras").has("run_id");
        requireSelected();return RUN_TRANSPORT.equals(request.getString("schema"))&&capturedRun.runId.equals(request.getJSONObject("extras").getString("run_id"));
    }
    static synchronized boolean selectionIdle(){
        if(singleton==null)return true;
        synchronized(singleton){for(Record row:singleton.live.values())
            if(row.commands!=0||row.enteredCommandStages.get()!=0||row.future!=null&&!row.future.isDone()
                ||row.serviceCompletionCallbackRegistered&&!row.callbackTerminal.isDone())return false;}
        return true; // Deliberately no historical row/UNKNOWN/ACK census.
    }
    static synchronized void selectedRunChanged(){if(!selectionIdle())throw new IllegalStateException("Original work entered");singleton=null;}
    private EmbeddedDuplexOriginals(File root)throws Exception{
        this.root=root;directory=new File(root,"operator-originals-v1");regularDirectory(root);regularDirectory(directory);
        actors=null;sync=()->{throw new IllegalStateException("query-only run sync");};readOnly=true;
    }
    static EmbeddedDuplexOriginals retained(EmbeddedDuplexRunContext run)throws Exception{
        EmbeddedDuplexOriginals owner=new EmbeddedDuplexOriginals(run.root);owner.capturedRun=run;return owner;
    }
    /** Exact one-row compatibility witness. No mkdir/chmod, all-row scan, ACK or owner rebind. */
    static JSONObject closedWholeWitness(File root,String originalId)throws Exception{return closedWholeHandoff(root,originalId).getJSONObject("physical_retirement");}
    static JSONObject closedWholeHandoff(File root,String originalId)throws Exception{return closedWholeHandoff(root,originalId,null);}
    static JSONObject closedWholeHandoff(File root,String originalId,String packageId)throws Exception{
        requireId(originalId);EmbeddedDuplexOriginals owner=new EmbeddedDuplexOriginals(root);Record record=owner.find(originalId);
        if(record==null||!"whole_app_close".equals(record.row.getString("operation"))||record.row.isNull("response")||record.row.isNull("outcome_sha256"))
            throw new IllegalStateException("named retained Whole witness unavailable");
        JSONObject response=record.row.getJSONObject("response");
        validateResponse("whole_app_close",record.row.getString("original_challenge"),response);
        if(!"observed".equals(response.getString("status"))||nativePending("whole_app_close",response))throw new IllegalStateException("retained Whole observation not terminal");
        JSONObject envelope=new JSONObject(response.getString("receipt"));
        JSONObject physical=envelope.has("peer_lifecycle")?envelope.getJSONObject("peer_lifecycle"):envelope;
        EmbeddedDuplexWholeCloseRollover.requireTerminalPhysical(physical);
        File file=new File(owner.directory,originalId+".joined.json");
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file.toPath())||Files.size(file.toPath())>MAX_RECORD_BYTES)
            throw new IllegalStateException("named Whole join unavailable");
        JSONObject join=EmbeddedDuplexCanonicalJson.readOwned(Files.readAllBytes(file.toPath()));
        Set<String> fields=Set.of("schema","original_id","operation","request_sha256","outcome_sha256","actor_binding","original_worker_terminal",
            "original_callback_terminal","outbound_exchanges_joined","physical_retirement_schema","physical_retirement_sha256","app_process_generation",
            "native_executor_generation","app_record_sha256","remote_outcome_resolved","join_basis");
        if(!keys(join).equals(fields)||!"rusty.quest.embedded_duplex.original_join.v2".equals(join.getString("schema"))
            ||!originalId.equals(join.getString("original_id"))||!"whole_app_close".equals(join.getString("operation"))
            ||!record.row.getString("request_sha256").equals(join.getString("request_sha256"))
            ||!record.row.getString("outcome_sha256").equals(join.getString("outcome_sha256"))
            ||!EmbeddedDuplexCanonicalJson.sameValue(record.row.getJSONObject("actor_binding"),join.getJSONObject("actor_binding"))
            ||!join.getBoolean("original_worker_terminal")||!join.getBoolean("original_callback_terminal")||!join.getBoolean("outbound_exchanges_joined")
            ||join.getBoolean("remote_outcome_resolved")||!physical.getString("$schema").equals(join.getString("physical_retirement_schema"))
            ||!EmbeddedDuplexCanonicalJson.sha256(physical).equals(join.getString("physical_retirement_sha256"))
            ||physical.getLong("app_process_generation")!=join.getLong("app_process_generation")
            ||physical.getLong("native_executor_generation")!=join.getLong("native_executor_generation"))throw new IllegalStateException("named Whole join differs");
        JSONObject actor=record.row.getJSONObject("actor_binding");
        if(packageId!=null&&!packageId.equals(actor.getString("package_id")))throw new IllegalStateException("retained Whole package differs");
        if(actor.isNull("native_generation")||actor.getLong("native_generation")!=join.getLong("native_executor_generation")
            ||!actor.getString("app_record_sha256").equals(join.getString("app_record_sha256")))throw new IllegalStateException("named Whole actor differs");
        validateJoinBasis(join.getJSONObject("join_basis"),record.row,join);
        return new JSONObject().put("whole_original_id",originalId).put("request_sha256",join.getString("request_sha256"))
            .put("outcome_sha256",join.getString("outcome_sha256")).put("physical_retirement_sha256",join.getString("physical_retirement_sha256"))
            .put("prior_native_generation",join.getLong("native_executor_generation")).put("physical_retirement",physical);
    }
    private void requireWritable()throws Exception {
        if(readOnly)throw new IllegalStateException("retained run is query-only");
        requireSelected();
        regularDirectory(root);regularDirectory(directory);
        if(Files.exists(new File(root,"embedded-duplex-owner-succession-v1").toPath(),LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("archived custody requires its actual succession owner");
    }
    private static void id(String value) {
        if(value==null||!value.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("original id");
    }
    private static void hash(String value) {
        if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("original digest");
    }
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static Set<String> keys(JSONObject value) {
        Set<String> result = new HashSet<>(); value.keys().forEachRemaining(result::add); return result;
    }

    /** Same canonical request producer as the selected transport; Float inputs bind raw bits. */
    public static JSONObject request(String operation,String challenge,Bundle extras)throws Exception {
        id(challenge);JSONObject fields=new JSONObject();
        if(extras!=null)for(String key:extras.keySet()) {
            if(key.equals("original_id"))continue;
            Object value=extras.get(key);if(value instanceof Float)value=Float.floatToRawIntBits((Float)value);
            if(!(value instanceof String||value instanceof Boolean||value instanceof Integer||value instanceof Long))
                throw new IllegalArgumentException("operator request hash type");
            fields.put(key,value);
        }
        EmbeddedDuplexRunContext run=EmbeddedDuplexProcessHost.selectedContext();
        if(run!=null&&run.runId!=null&&!fields.has("run_id"))fields.put("run_id",run.runId);
        return new JSONObject().put("schema",fields.has("run_id")?RUN_TRANSPORT:TRANSPORT).put("method",operation).put("challenge",challenge).put("extras",fields);
    }

    public Bundle begin(String originalId,String operation,String challenge,JSONObject request,
            Supplier<CompletableFuture<Bundle>> action)throws Exception {
        id(originalId);id(challenge);Objects.requireNonNull(action);
        if(!OPERATIONS.contains(operation))throw new IllegalArgumentException("unsupported Original operation");
        if (request == null || !keys(request).equals(Set.of("schema", "method", "challenge", "extras"))
                || !validRequestScope(request) || !operation.equals(request.getString("method"))
                || !challenge.equals(request.getString("challenge")) || !(request.get("extras") instanceof JSONObject))
            throw new IllegalArgumentException("Original canonical request differs");
        if (FRESH_OPERATIONS.contains(operation)) return beginBound(originalId, operation, challenge, request, null, null, action);
        String requestHash=EmbeddedDuplexCanonicalJson.sha256(request);Record held;
        synchronized(this) {
            requireWritable();Record prior=find(originalId);
            if(prior!=null) {
                if(!operation.equals(prior.row.getString("operation"))||!requestHash.equals(prior.row.getString("request_sha256")))
                    throw new IllegalStateException("original input changed");
                return envelope(prior,challenge);
            }
            JSONObject row=new JSONObject().put("schema",RECORD).put("original_id",originalId).put("operation",operation)
                    .put("request_sha256",requestHash).put("original_challenge",challenge).put("state","registered")
                    .put("effect_state","not_entered").put("actor_binding",copy(actors.snapshot()))
                    .put("native_setup_binding",JSONObject.NULL).put("response",JSONObject.NULL)
                    .put("response_sha256",JSONObject.NULL).put("failure",JSONObject.NULL).put("outcome_sha256",JSONObject.NULL)
                    .put("retention","owned").put("consumer_receipt_sha256",JSONObject.NULL);
            held=new Record(this,row);persist(held);live.put(originalId,held);
            row.put("state","pending").put("effect_state","entered");persist(held);
        }
        try(AutoCloseable ignored=enter(held)) { held.future=Objects.requireNonNull(action.get(),"actual Original Future"); }
        catch(Throwable failure) { held.future=new CompletableFuture<>();held.future.completeExceptionally(failure); }
        held.future.whenComplete((response,failure)->complete(held,response,failure));
        synchronized(this){return envelope(held,challenge);}
    }
    static AutoCloseable enter(Record actual) {
        if(actual!=null)actual.owner.requireSelected();
        Record previous=CURRENT.get();if(actual==null)CURRENT.remove();else CURRENT.set(actual);
        return ()->{if(previous==null)CURRENT.remove();else CURRENT.set(previous);};
    }
    /** Capture the actual source record before enqueue; neither IDs nor records enter from transport here. */
    static boolean hasCurrent() { return CURRENT.get() != null; }

    public static Runnable carry(Runnable action) {
        return carryActual(CURRENT.get(), action);
    }
    static Runnable carryActual(Record actual, Runnable action) {
        if(actual==null)return action;
        synchronized(actual.owner){actual.commands++;}
        AtomicBoolean entered=new AtomicBoolean();
        return ()->{
            if(!entered.compareAndSet(false,true))throw new IllegalStateException("Original command entered twice");
            try(AutoCloseable ignored=enter(actual)){action.run();}
            catch(RuntimeException|Error failure){throw failure;}
            catch(Exception impossible){throw new IllegalStateException(impossible);}
            finally{synchronized(actual.owner){actual.commands--;}}
        };
    }
    static void bindActualActor(JSONObject actor,long generation)throws Exception {
        Record actual=CURRENT.get();if(actual==null)return;
        synchronized(actual.owner) {
            if(generation<=0||actual.callbackJoined||actual.restored)throw new IllegalStateException("Original actor unavailable");
            JSONObject priorActor=actual.row.getJSONObject("actor_binding");
            boolean constructorPublication="bootstrap_real_peer".equals(actual.row.getString("operation"))
                    && actual.appGeneration==generation && priorActor.isNull("native_generation") && priorActor.isNull("app_record_sha256")
                    && actor.optLong("native_generation",0)>0 && actor.opt("app_record_sha256") instanceof String;
            if(actual.boundActor && !constructorPublication && (!EmbeddedDuplexCanonicalJson.sameValue(priorActor,actor)
                    ||actual.appGeneration!=generation))throw new IllegalStateException("Original actor changed");
            actual.appGeneration=generation;actual.boundActor=true;actual.row.put("actor_binding",copy(actor));actual.owner.persist(actual);
        }
    }
    private synchronized void complete(Record record,Bundle response,Throwable failure) {
        try {
            if(failure!=null) {
                record.row.put("state","unknown").put("effect_state","uncertain")
                        .put("failure",new JSONObject().put("schema","rusty.quest.embedded_duplex.original_failure.v1").put("code","actual_future_failed"));
            } else {
                JSONObject actual;
                String operation = record.row.getString("operation");
                boolean pending;
                if (FRESH_OPERATIONS.contains(operation)) {
                    actual = bundleObject(response);
                    validateResponse(operation, record.row.getString("original_challenge"), actual);
                    JSONObject retained = nativePending(record, operation, actual);
                    pending = retained != null;
                } else {
                    actual = validateResponse(record, response);
                    pending = nativePending(operation, actual);
                }
                record.row.put("response",actual).put("response_sha256",EmbeddedDuplexCanonicalJson.sha256(actual));
                record.row.put("state",pending?"unknown":"succeeded").put("effect_state",pending?"uncertain":"observed");
            }
            record.row.put("outcome_sha256",EmbeddedDuplexCanonicalJson.sha256(outcome(record.row)));persist(record);
        } catch(Throwable unavailable){record.persistenceFailure=unavailable;}
        finally{record.callbackJoined=true;record.callbackTerminal.complete(null);}
    }
    private static JSONObject validateResponse(Record record,Bundle response)throws Exception {
        if(response==null||!response.keySet().equals(Set.of("schema","status","challenge","receipt")))
            throw new IllegalStateException("Original response closed fields");
        JSONObject actual=new JSONObject();for(String key:response.keySet()) {
            Object value=response.get(key);if(!(value instanceof String))throw new IllegalStateException("Original response scalar type");
            actual.put(key,value);
        }
        if(!"rusty.quest.embedded_duplex.operator_transport.v1".equals(actual.getString("schema"))
                ||!"observed".equals(actual.getString("status"))
                ||!record.row.getString("original_challenge").equals(actual.getString("challenge")))
            throw new IllegalStateException("Original response source differs");
        JSONObject receipt=new JSONObject(actual.getString("receipt"));
        String schema=receipt.optString("$schema",receipt.optString("schema"));
        if(!schema.startsWith("rusty.quest."))throw new IllegalStateException("Original native receipt schema");
        return actual;
    }
    /** Inspect the actual current native wrapper. Java Future completion is not native settlement. */
    private static boolean nativePending(String operation, JSONObject response) throws Exception {
        JSONObject receipt = new JSONObject(response.getString("receipt"));
        String schema = receipt.getString("schema");
        String expected = operation.equals("blend_update") ? "rusty.quest.stereo.neutral_mask_receipt.v1"
                : "rusty.quest.stereo.concurrent_qualification_receipt.v1";
        String expectedAction = operation.equals("concurrent_arm") ? "arm" : operation;
        if (!expected.equals(schema) || !expectedAction.equals(receipt.getString("action"))
                || !response.getString("challenge").equals(receipt.getString("challenge")))
            throw new IllegalStateException("Original receipt operation differs");
        if (Set.of("concurrent_arm", "policy_update", "blend_update").contains(operation)) return false;
        JSONObject nativeReceipt = receipt.getJSONObject("peer_lifecycle");
        String nativeSchema = nativeReceipt.getString("$schema");
        if (operation.equals("whole_app_close")
                && nativeSchema.equals("rusty.quest.embedded_duplex.concurrent_no_media_own_cleanup.v1"))
            return true; // Retain the current real result; this slice adds no no-media ACK join.
        if (operation.equals("whole_app_close")
                && nativeSchema.equals("rusty.quest.embedded_duplex.remote_owner_cleanup.v1"))
            return !"terminal".equals(nativeReceipt.optString("whole_app_physical_cleanup"));
        if (EmbeddedDuplexFreshLifecycle.SCHEMA.equals(nativeSchema)) EmbeddedDuplexFreshLifecycle.require(nativeReceipt);
        if (!Set.of(EmbeddedDuplexFreshLifecycle.SCHEMA, "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1",
                "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v2",
                "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v3").contains(nativeSchema)
                || !operation.equals(nativeReceipt.getString("action")))
            throw new IllegalStateException("Original native lifecycle differs");
        String status = nativeReceipt.getString("status");
        if (!Set.of("active", "terminal", "pending").contains(status))
            throw new IllegalStateException("Original native lifecycle status");
        return status.equals("pending") || nativeReceipt.getBoolean("renewal_pending")
                || (operation.equals("whole_app_close")
                    && !"terminal".equals(nativeReceipt.optString("whole_app_physical_cleanup")));
    }
    public synchronized Bundle query(String originalId,String challenge)throws Exception {
        id(originalId);id(challenge);Record record=find(originalId);if(record==null)throw new IllegalStateException("original absent");
        return envelope(record,challenge);
    }
    public synchronized Bundle acknowledge(String originalId,String challenge,String requestHash,String outcomeHash,String consumerHash)throws Exception {
        id(originalId);id(challenge);hash(requestHash);hash(outcomeHash);hash(consumerHash);requireWritable();
        Record record=find(originalId);
        if(record==null||!requestHash.equals(record.row.getString("request_sha256"))||!outcomeHash.equals(record.row.optString("outcome_sha256")))
            throw new IllegalStateException("original acknowledgement mismatch");
        if(!record.row.isNull("consumer_receipt_sha256")&&!consumerHash.equals(record.row.getString("consumer_receipt_sha256")))
            throw new IllegalStateException("original acknowledgement changed");
        if("compacted".equals(record.row.getString("retention")))return envelope(record,challenge);
        if(record.restored||!record.boundActor||record.future==null||!record.future.isDone()||record.future.isCancelled()
                ||!record.callbackJoined||record.commands!=0||record.persistenceFailure!=null
                ||!"succeeded".equals(record.row.getString("state"))
                ||!actors.settled(record.row.getJSONObject("actor_binding"),record.appGeneration))
            throw new IllegalStateException("actual Original owner has not settled");
        if (FRESH_OPERATIONS.contains(record.row.getString("operation")) || (!record.row.isNull("response")
                && record.row.getJSONObject("response").optString("receipt").contains(EmbeddedDuplexFreshLifecycle.SCHEMA))) {
            if (!record.continuationWorkerJoined() || !EmbeddedDuplexProcessHost.originalExchangesJoined(record))
                throw new IllegalStateException("fresh Original command/exchange owner has not joined");
            retainSettledCustody(record);
        }
        record.row.put("consumer_receipt_sha256",consumerHash).put("retention","compacted");persist(record);live.remove(originalId);
        return envelope(record,challenge);
    }
    private Bundle envelope(Record record,String challenge)throws Exception {
        if(record.persistenceFailure!=null)throw new IllegalStateException("original publication unavailable",record.persistenceFailure);
        JSONObject observation=copy(record.row);String state=observation.getString("state");
        if(record.restored&&(state.equals("registered")||state.equals("pending")))observation.put("state","unknown");
        String status="unknown".equals(observation.getString("state"))?"unknown":
                "compacted".equals(observation.getString("retention"))?"compacted":"succeeded".equals(observation.getString("state"))?"observed":"pending";
        Bundle out=new Bundle();out.putString("schema",capturedRun!=null&&!capturedRun.legacy()?RUN_TRANSPORT:TRANSPORT);if(capturedRun!=null&&!capturedRun.legacy())out.putString("run_id",capturedRun.runId);out.putString("status",status);out.putString("challenge",challenge);
        out.putString("original_id",record.id());out.putString("observation_json",EmbeddedDuplexCanonicalJson.encode(observation));return out;
    }
    private static JSONObject outcome(JSONObject row)throws Exception {
        JSONObject out=new JSONObject();for(String key:new String[]{"original_id","operation","request_sha256","original_challenge",
                "state","effect_state","actor_binding","native_setup_binding","response_sha256","failure"})out.put(key,row.get(key));if (CONTINUATION_RECORD.equals(row.getString("schema"))) out.put("predecessor_binding", row.getJSONObject("predecessor_binding")); return out;
    }
    private Record find(String originalId)throws Exception {
        Record current=live.get(originalId);if(current!=null)return current;
        File file=new File(directory,originalId+".json");
        if(Files.exists(new File(file.getPath()+".tmp").toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("original pending publication retained");
        if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file.toPath())||Files.size(file.toPath())>MAX_RECORD_BYTES)
            throw new IllegalStateException("original checkpoint type/bounds");
        JSONObject row=EmbeddedDuplexCanonicalJson.readOwned(Files.readAllBytes(file.toPath()));
        Set<String> keys=new HashSet<>();row.keys().forEachRemaining(keys::add);
        Set<String> expectedFields = new HashSet<>(FIELDS);
        boolean continuation = CONTINUATION_RECORD.equals(row.getString("schema"));
        if (continuation) expectedFields.add("predecessor_binding");
        if(!keys.equals(expectedFields)||!(RECORD.equals(row.getString("schema")) || continuation)||!originalId.equals(row.getString("original_id"))
                ||!OPERATIONS.contains(row.getString("operation")))throw new IllegalStateException("original checkpoint closed schema");
        id(row.getString("original_id"));id(row.getString("original_challenge"));hash(row.getString("request_sha256"));
        if(!Set.of("registered","pending","unknown","succeeded").contains(row.getString("state"))
                ||!Set.of("not_entered","entered","uncertain","observed").contains(row.getString("effect_state"))
                ||!(Set.of("owned","compacted").contains(row.getString("retention"))
                    || FRESH_OPERATIONS.contains(row.getString("operation")) && "acknowledged_join_pending".equals(row.getString("retention"))))throw new IllegalStateException("original checkpoint state");
        if(!row.isNull("response_sha256")&&!row.getString("response_sha256").equals(EmbeddedDuplexCanonicalJson.sha256(row.getJSONObject("response"))))
            throw new IllegalStateException("original response digest differs");
        if(!row.isNull("outcome_sha256")&&!row.getString("outcome_sha256").equals(EmbeddedDuplexCanonicalJson.sha256(outcome(row))))
            throw new IllegalStateException("original outcome digest differs");
        JSONObject actor = row.getJSONObject("actor_binding");
        if (!keys(actor).equals(Set.of("package_id", "app_record_sha256", "native_generation"))
                || !(actor.get("package_id") instanceof String) || actor.getString("package_id").isEmpty()
                ) throw new IllegalStateException("Original actor shape");
        validateNativeSetupBinding(row);
        if (!actor.isNull("app_record_sha256")) hash(actor.getString("app_record_sha256"));
        Object nativeGeneration = actor.get("native_generation");
        if (nativeGeneration != JSONObject.NULL && (!(nativeGeneration instanceof Integer || nativeGeneration instanceof Long)
                || ((Number) nativeGeneration).longValue() <= 0L)) throw new IllegalStateException("Original native actor type");
        String state = row.getString("state");
        String effect = row.getString("effect_state");
        if (!(state.equals("registered") ? effect.equals("not_entered") : state.equals("pending") ? effect.equals("entered")
                : state.equals("succeeded") ? effect.equals("observed") : effect.equals("uncertain")))
            throw new IllegalStateException("Original state join");
        if (row.isNull("response") != row.isNull("response_sha256")
                || (state.equals("succeeded") && (row.isNull("response") || row.isNull("outcome_sha256") || !row.isNull("failure")))
                || ((state.equals("registered") || state.equals("pending"))
                    && (!row.isNull("response") || !row.isNull("outcome_sha256") || !row.isNull("failure"))))
            throw new IllegalStateException("Original outcome join");
        boolean compactedUnknown=row.getString("retention").equals("compacted")&&state.equals("unknown");
        if (row.getString("retention").equals("compacted")) {
            if(compactedUnknown){if(!row.isNull("consumer_receipt_sha256"))throw new IllegalStateException("UNKNOWN is not consumer ACK");}
            else {if (!state.equals("succeeded")) throw new IllegalStateException("Original compaction state");hash(row.getString("consumer_receipt_sha256"));}
        } else if (!row.isNull("consumer_receipt_sha256")) throw new IllegalStateException("Original consumer state");
        Record restored=new Record(this,row);restored.restored=true;
        if(compactedUnknown){
            // Physical-owner compaction is not outcome settlement. Reuse the exact retained join validator, then return UNKNOWN.
            synchronized(this){live.put(originalId,restored);try{
                if(!"joined".equals(queryJoin(originalId,row.getString("original_challenge")).getString("status")))
                    throw new IllegalStateException("UNKNOWN compaction join absent");
            }finally{live.remove(originalId);}}
        }
        return restored;
    }
    private void persist(Record record)throws Exception {
        try {
            requireWritable();byte[] raw=EmbeddedDuplexCanonicalJson.encode(record.row).getBytes(StandardCharsets.UTF_8);
            if(raw.length>MAX_RECORD_BYTES)throw new IllegalStateException("original checkpoint bounds");
            File target=new File(directory,record.id()+".json"),temporary=new File(target.getPath()+".tmp");
            if(Files.exists(temporary.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("original pending publication retained");
            if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(target.toPath())))
                throw new IllegalStateException("original target type");
            Files.createFile(temporary.toPath());Os.chmod(temporary.getAbsolutePath(),0600);
            try(FileOutputStream output=new FileOutputStream(temporary)){output.write(raw);output.getFD().sync();}
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync.run();
        }catch(Exception unavailable){record.persistenceFailure=unavailable;throw unavailable;}
    }


    /** The actual command/UI continuation captures this owner, never a caller-selected exclusion ID. */
    static Record capture() { return CURRENT.get(); }
    public Bundle beginContinuation(String id,String challenge,JSONObject request,String priorId,String priorOutcome,
            Supplier<CompletableFuture<Bundle>> factory)throws Exception{
        requireId(priorId);requireHash(priorOutcome);
        if (request == null || !keys(request).equals(Set.of("schema","method","challenge","extras"))
                || !validRequestScope(request) || !"fresh_pair_continue".equals(request.getString("method"))
                || !challenge.equals(request.getString("challenge")) || !(request.get("extras") instanceof JSONObject))
            throw new IllegalArgumentException("fresh continuation canonical request differs");
        JSONObject extras=request.getJSONObject("extras");
        Set<String> expectedExtras=new HashSet<>(Set.of("prior_original_id","prior_outcome_sha256"));
        if(capturedRun!=null&&!capturedRun.legacy())expectedExtras.add("run_id");
        if (!keys(extras).equals(expectedExtras)
                || !priorId.equals(extras.getString("prior_original_id")) || !priorOutcome.equals(extras.getString("prior_outcome_sha256")))
            throw new IllegalArgumentException("fresh continuation source predecessor differs");
        if(id.equals(priorId))throw new IllegalArgumentException("continuation requires new wrapper original");
        return beginBound(id,"fresh_pair_continue",challenge,request,priorId,priorOutcome,factory);
    }
    private Bundle beginBound(String id,String operation,String challenge,JSONObject request,String priorId,String priorOutcome,
            Supplier<CompletableFuture<Bundle>> factory)throws Exception{
        requireId(id); requireId(challenge);
        String requestHash = EmbeddedDuplexCanonicalJson.sha256(request);
        Record record;
        synchronized (this) {
            requireWritable(); record = find(id);
            if (record != null) {
                if (!operation.equals(record.row.getString("operation"))
                        || !requestHash.equals(record.row.getString("request_sha256")))
                    throw new IllegalStateException("original input changed");
                return envelope(record, challenge);
            }
            Record prior=priorId==null?null:requireContinuationPrior(priorId,priorOutcome);
            if(prior!=null && !EmbeddedDuplexProcessHost.continuationIngressQuiescent(prior))
                throw new IllegalStateException("continuation accepted incoming task remains owned");
            JSONObject row = new JSONObject().put("schema", prior==null?RECORD:CONTINUATION_RECORD).put("original_id", id)
                .put("operation", operation).put("request_sha256", requestHash)
                .put("original_challenge", challenge).put("state", "registered")
                .put("effect_state", "not_entered").put("actor_binding", EmbeddedDuplexProcessHost.originalActor())
                .put("native_setup_binding", JSONObject.NULL).put("response", JSONObject.NULL)
                .put("response_sha256", JSONObject.NULL).put("failure", JSONObject.NULL)
                .put("outcome_sha256", JSONObject.NULL).put("retention", "owned")
                .put("consumer_receipt_sha256", JSONObject.NULL);
            if(prior!=null){
                JSONObject oldBinding=prior.row.getJSONObject("native_setup_binding");
                row.put("predecessor_binding",new JSONObject().put("original_id",prior.id())
                    .put("outcome_sha256",priorOutcome).put("native_setup_request_id",oldBinding.getString("setup_request_id"))
                    .put("context_sha256",oldBinding.get("context_sha256")));
                // This ID is already actual producer evidence; the new phase context is not known yet.
                row.put("native_setup_binding",new JSONObject().put("setup_request_id",oldBinding.getString("setup_request_id"))
                    .put("context_sha256",JSONObject.NULL));
            }
            record = new Record(this,row);
            record.appGeneration=EmbeddedDuplexProcessHost.currentAppGeneration();
            // A crash at either boundary is retained uncertainty, never permission to call factory again.
            persist(record); live.put(id, record);
            row.put("state", "pending").put("effect_state", "entered"); persist(record);
        }
        final Record held = record;
        try (AutoCloseable ignored = enter(held)) {
            held.future = java.util.Objects.requireNonNull(factory.get(), "original Future");
        } catch (Throwable failure) {
            // The actual factory threw; retain that same actual failure as the wrapper's terminal Future.
            held.future=new CompletableFuture<>();held.future.completeExceptionally(failure);
        }
        held.future.whenComplete((response, failure) -> complete(held, response, failure))
            .handle((value,failure)->{afterOriginalCallback(held);return null;});
        synchronized (this) { return envelope(held, challenge); }
    }
    <T> CompletableFuture<T> beginInternal(String operation,String actualChallenge,JSONObject actualRequest,
            Supplier<CompletableFuture<T>> factory,java.util.function.Function<T,CompletableFuture<Bundle>> projection) {
        CompletableFuture<T> actualResult=new CompletableFuture<>();
        String id=java.util.UUID.randomUUID().toString().replace("-","");
        try{
            begin(id,operation,actualChallenge,actualRequest,()->{
                CompletableFuture<T> actual;
                try{actual=java.util.Objects.requireNonNull(factory.get());}
                catch(Throwable failure){actualResult.completeExceptionally(failure);throw new java.util.concurrent.CompletionException(failure);}
                Record current=capture();
                if(current==null)throw new IllegalStateException("app-service actual Original missing");
                current.actualServiceDeliveredFuture=actualResult;
                current.serviceCompletionCallbackRegistered=true;
                actual.whenComplete((value,failure)->{
                    if(failure==null)current.serviceDeliveryAccepted=actualResult.complete(value);
                    else actualResult.completeExceptionally(failure);
                    try{retainAppServiceDelivery(current);}
                    catch(Exception unavailable){synchronized(this){current.persistenceFailure=unavailable;}}
                });
                return actual.thenCompose(projection);
            });
        }catch(Throwable failure){actualResult.completeExceptionally(failure);}
        return actualResult;
    }
    private void afterOriginalCallback(Record record){
        // The registered result publication callback's dependent stage is now genuinely terminal.
        record.callbackTerminal.complete(null);
        try{retainPhysicalJoin(record);}
        catch(Exception unavailable){synchronized(this){record.joinPublicationFailure=unavailable;}
            }
        try{retainAppServiceDelivery(record);}
        catch(Exception unavailable){synchronized(this){record.persistenceFailure=unavailable;}
            }
        EmbeddedDuplexProcessHost.originalCompletion(record);
    }

    /** Actual app-service value delivery custody; never a UI/human acceptance or a cleanup receipt. */
    private synchronized void retainAppServiceDelivery(Record record)throws Exception{
        if(!record.serviceCompletionCallbackRegistered || !record.serviceDeliveryAccepted
                || record.actualServiceDeliveredFuture==null || !record.actualServiceDeliveredFuture.isDone()
                || record.actualServiceDeliveredFuture.isCompletedExceptionally() || !record.callbackTerminal.isDone()
                || record.persistenceFailure!=null || !"succeeded".equals(record.row.getString("state"))
                || record.row.isNull("outcome_sha256") || record.appGeneration<=0)return;
        JSONObject receipt=new JSONObject().put("schema","rusty.quest.embedded_duplex.app_service_delivery_custody.v1")
            .put("original_id",record.id()).put("request_sha256",record.row.getString("request_sha256"))
            .put("outcome_sha256",record.row.getString("outcome_sha256"))
            .put("response_sha256",record.row.getString("response_sha256"))
            .put("app_process_generation",record.appGeneration).put("typed_future_delivered",true)
            .put("registered_completion_callback_joined",true).put("physical_cleanup_claimed",false);
        String exact=EmbeddedDuplexCanonicalJson.encode(receipt);
        File target=new File(directory,record.id()+".delivery.json");
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS)
                    || !exact.equals(new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8)))
                throw new IllegalStateException("app-service custody receipt changed");
        }else{
            File temporary=new File(directory,record.id()+".delivery.tmp");
            if(!temporary.createNewFile())throw new IllegalStateException("app-service custody pending write");
            Os.chmod(temporary.getAbsolutePath(),0600);
            try(FileOutputStream output=new FileOutputStream(temporary)){
                output.write(exact.getBytes(StandardCharsets.UTF_8));output.getFD().sync();
            }
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);syncDirectory();
        }
        String digest=EmbeddedDuplexCanonicalJson.sha256(receipt);
        if(!record.row.isNull("consumer_receipt_sha256") && !digest.equals(record.row.getString("consumer_receipt_sha256")))
            throw new IllegalStateException("app-service custody ACK conflicts");
        record.row.put("consumer_receipt_sha256",digest).put("retention","acknowledged_join_pending");persist(record);
        compactIfJoined(record);
    }

    /** Explicit desired Stop for an existing original, not a new media command or a cleanup assertion. */
    public Bundle requestStop(String id,String challenge,String requestHash,String stopIntentHash)throws Exception{
        requireId(id);requireId(challenge);requireHash(requestHash);requireHash(stopIntentHash);
        Record actual;JSONObject stop;boolean first;
        synchronized(this){
            actual=live.get(id);
            if(actual==null || actual.restored || !requestHash.equals(actual.row.getString("request_sha256"))
                    || !java.util.Arrays.asList("pending","unknown").contains(actual.row.getString("state"))
                    || "compacted".equals(actual.row.getString("retention")))
                throw new IllegalStateException("desired Stop original is not live/uncertain");
            EmbeddedDuplexProcessHost.requireCurrentOriginalStopActor(actual);
            File retained=new File(directory,id+".stop.json");
            first=!Files.exists(retained.toPath(),LinkOption.NOFOLLOW_LINKS);
            if(first){
                stop=new JSONObject().put("schema","rusty.quest.embedded_duplex.original_stop_observation.v1")
                    .put("original_id",id).put("operation",actual.row.get("operation")).put("request_sha256",requestHash)
                    .put("stop_intent_sha256",stopIntentHash).put("actor_binding",new JSONObject(actual.row.getJSONObject("actor_binding").toString()))
                    .put("native_setup_binding",actual.row.isNull("native_setup_binding")?JSONObject.NULL:new JSONObject(actual.row.getJSONObject("native_setup_binding").toString()))
                    .put("disposition","desired_stop_recorded").put("native_stop_disposition","signal_pending")
                    .put("native_stop_response",JSONObject.NULL);
                persistStop(id,stop);
            }else{
                if(!Files.isRegularFile(retained.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("desired Stop retained file type");
                stop=new JSONObject(new String(Files.readAllBytes(retained.toPath()),StandardCharsets.UTF_8));
                if(!id.equals(stop.getString("original_id")) || !requestHash.equals(stop.getString("request_sha256"))
                        || !stopIntentHash.equals(stop.getString("stop_intent_sha256")))
                    throw new IllegalStateException("desired Stop intent changed");
            }
            actual.desiredStop.set(true);
        }
        // Native may call Java while it unwinds. Never hold the original registry monitor across that JNI call.
        if(first){
            String response=null;String disposition;
            try{response=EmbeddedDuplexProcessHost.signalOriginalStop(actual);disposition=response==null?"latched_before_setup_binding":"native_stop_observed";}
            catch(Throwable unavailable){disposition="native_stop_unavailable";
                }
            synchronized(this){stop.put("native_stop_disposition",disposition)
                .put("native_stop_response",response==null?JSONObject.NULL:response);persistStop(id,stop);}
        }
        synchronized(this){
            JSONObject observation=new JSONObject(stop.toString()).put("original_state",actual.row.get("state"))
                .put("original_effect_state",actual.row.get("effect_state"))
                .put("original_worker_terminal",actual.future!=null && actual.future.isDone() && actual.callbackTerminal.isDone())
                .put("outbound_exchanges_joined",EmbeddedDuplexProcessHost.originalExchangesJoined(actual))
                .put("physical_cleanup_claimed",false).put("remote_outcome_resolved",false);
            Bundle out=new Bundle();out.putString("schema","rusty.quest.embedded_duplex.operator_original_stop.v1");
            out.putString("status","stop_requested");out.putString("challenge",challenge);out.putString("original_id",id);
            out.putString("stop_observation_json",EmbeddedDuplexCanonicalJson.encode(observation));return out;
        }
    }
    private void persistStop(String id,JSONObject value)throws Exception{
        File target=new File(directory,id+".stop.json"),temporary=new File(directory,id+".stop.tmp");
        if(!temporary.createNewFile())throw new IllegalStateException("desired Stop retained pending write");
        Os.chmod(temporary.getAbsolutePath(),0600);
        try(FileOutputStream output=new FileOutputStream(temporary)){
            output.write(EmbeddedDuplexCanonicalJson.encode(value).getBytes(StandardCharsets.UTF_8));output.getFD().sync();
        }
        Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);syncDirectory();
    }
    public synchronized Bundle queryJoin(String id,String challenge)throws Exception{
        requireId(id);requireId(challenge);Record record=find(id);
        if(record==null)throw new IllegalStateException("original join target absent");
        if(record.physicalJoinProof!=null)retainPhysicalJoin(record);
        if(record.joinPublicationFailure!=null)throw new IllegalStateException("actual original joins publication unavailable",record.joinPublicationFailure);
        File file=new File(directory,id+".joined.json");JSONObject proof=null;
        if(Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("original join receipt type");
            proof=EmbeddedDuplexCanonicalJson.readOwned(Files.readAllBytes(file.toPath()));
            java.util.HashSet<String> keys=new java.util.HashSet<>();proof.keys().forEachRemaining(keys::add);
            boolean v2="rusty.quest.embedded_duplex.original_join.v2".equals(proof.getString("schema"));
            java.util.HashSet<String> expected=new java.util.HashSet<>(java.util.Arrays.asList("schema","original_id","operation","request_sha256",
                    "outcome_sha256","actor_binding","original_worker_terminal","original_callback_terminal","outbound_exchanges_joined",
                    "physical_retirement_schema","physical_retirement_sha256","app_process_generation","native_executor_generation",
                    "app_record_sha256","remote_outcome_resolved"));
            if(v2)expected.add("join_basis");
            if(!keys.equals(expected)
                    || !v2 && !"rusty.quest.embedded_duplex.original_join.v1".equals(proof.getString("schema"))
                    || !id.equals(proof.getString("original_id")) || !record.row.getString("operation").equals(proof.getString("operation"))
                    || !record.row.getString("request_sha256").equals(proof.getString("request_sha256"))
                    || !record.row.getString("outcome_sha256").equals(proof.getString("outcome_sha256"))
                    || !EmbeddedDuplexCanonicalJson.encode(record.row.getJSONObject("actor_binding"))
                        .equals(EmbeddedDuplexCanonicalJson.encode(proof.getJSONObject("actor_binding")))
                    || !proof.getBoolean("original_worker_terminal") || !proof.getBoolean("original_callback_terminal")
                    || !proof.getBoolean("outbound_exchanges_joined") || proof.getBoolean("remote_outcome_resolved")
                    || proof.getLong("app_process_generation")<=0
                    || !proof.getString("app_record_sha256").matches("[0-9a-f]{64}")
                    || !proof.getString("physical_retirement_sha256").matches("[0-9a-f]{64}")
                    || !proof.isNull("native_executor_generation") && proof.getLong("native_executor_generation")<=0)
                throw new IllegalStateException("original retained join binding differs");
            if(v2)validateJoinBasis(proof.getJSONObject("join_basis"),record.row,proof);
        }
        Bundle result=new Bundle();result.putString("schema","rusty.quest.embedded_duplex.operator_original_join.v1");
        result.putString("status",proof==null?"pending":"joined");result.putString("challenge",challenge);result.putString("original_id",id);
        result.putString("join_observation_json",proof==null?"null":EmbeddedDuplexCanonicalJson.encode(proof));return result;
    }
    /** Readonly actual prior-worker joins, deliberately separate from whole retirement. */
    public synchronized Bundle queryContinuationJoin(String id,String challenge)throws Exception{
        requireId(id);requireId(challenge);Record prior=find(id);
        if(prior==null)throw new IllegalStateException("continuation join target absent");
        JSONObject proof=null;
        if(!prior.restored && java.util.Arrays.asList("pair_session","fresh_pair_continue").contains(prior.row.getString("operation"))
                && "unknown".equals(prior.row.getString("state")) && !prior.row.isNull("outcome_sha256")
                && prior.continuationWorkerJoined()
                && prior.persistenceFailure==null && !prior.row.isNull("native_setup_binding")){
            // Identity/source failures are strict. A live task is pending; it is
            // never turned into terminal by elapsed time or an outer capture.
            EmbeddedDuplexProcessHost.requireCurrentOriginalStopActor(prior);
            JSONObject identity=EmbeddedDuplexProcessHost.continuationJoinIdentity(prior);
            boolean outbound=EmbeddedDuplexProcessHost.originalExchangesJoined(prior);
            boolean incoming=EmbeddedDuplexProcessHost.originalIncomingExchangesJoined(prior)
                && EmbeddedDuplexProcessHost.continuationIngressQuiescent(prior);
            int unrelated=unrelatedUnresolved(prior,null);
            JSONObject durable=read(path(id));
            if(!EmbeddedDuplexCanonicalJson.encode(durable).equals(EmbeddedDuplexCanonicalJson.encode(prior.row))
                    || !EmbeddedDuplexCanonicalJson.sha256(outcome(durable)).equals(prior.row.getString("outcome_sha256")))
                throw new IllegalStateException("continuation join immutable outcome differs");
            if(outbound && incoming && unrelated==0){
                proof=new JSONObject(identity.toString())
                    .put("schema","rusty.quest.embedded_duplex.original_continuation_join.v1")
                    .put("original_id",id).put("operation",prior.row.getString("operation"))
                    .put("request_sha256",prior.row.getString("request_sha256"))
                    .put("outcome_sha256",prior.row.getString("outcome_sha256"))
                    .put("actor_binding",new JSONObject(prior.row.getJSONObject("actor_binding").toString()))
                    .put("native_setup_binding",new JSONObject(prior.row.getJSONObject("native_setup_binding").toString()))
                    .put("original_state","unknown").put("original_effect_state",prior.row.getString("effect_state"))
                    .put("original_worker_terminal",true).put("original_callback_terminal",true)
                    .put("outbound_exchanges_joined",true).put("incoming_request_tasks_joined",true)
                    .put("unrelated_unresolved_original_count",0).put("physical_cleanup_claimed",false)
                    .put("remote_outcome_resolved",false);
            }
        }
        Bundle result=new Bundle();result.putString("schema","rusty.quest.embedded_duplex.operator_original_continuation_join.v1");
        result.putString("status",proof==null?"pending":"joined");result.putString("challenge",challenge);result.putString("original_id",id);
        result.putString("continuation_join_json",proof==null?"null":EmbeddedDuplexCanonicalJson.encode(proof));return result;
    }
    public synchronized Bundle queryCompletedSetupJoin(String id,String challenge)throws Exception{
        requireId(id);requireId(challenge);Record prior=find(id);
        if(prior==null)throw new IllegalStateException("continuation join target absent");
        JSONObject proof=null;JSONObject settled=prior.restored?settledCustody(prior.row):null;
        if(settled!=null)prior.appGeneration=settled.getLong("app_process_generation"); // Past actual custody, no Future/owner restore.
        if((!prior.restored||settled!=null) && java.util.Arrays.asList("pair_session","fresh_pair_continue").contains(prior.row.getString("operation"))
                && "succeeded".equals(prior.row.getString("state")) && !prior.row.isNull("outcome_sha256")
                && (settled!=null||prior.continuationWorkerJoined())
                && prior.persistenceFailure==null && !prior.row.isNull("native_setup_binding")){
            // Identity/source failures are strict. A live task is pending; it is
            // never turned into terminal by elapsed time or an outer capture.
            EmbeddedDuplexProcessHost.requireCurrentOriginalStopActor(prior);
            JSONObject identity=EmbeddedDuplexProcessHost.continuationJoinIdentity(prior);
            boolean outbound=settled!=null||EmbeddedDuplexProcessHost.originalExchangesJoined(prior);
            boolean incoming=EmbeddedDuplexProcessHost.originalIncomingExchangesJoined(prior)
                && EmbeddedDuplexProcessHost.continuationIngressQuiescent(prior);
            int unrelated=unrelatedUnresolved(prior,null);
            JSONObject durable=read(path(id));
            if(!EmbeddedDuplexCanonicalJson.encode(durable).equals(EmbeddedDuplexCanonicalJson.encode(prior.row))
                    || !EmbeddedDuplexCanonicalJson.sha256(outcome(durable)).equals(prior.row.getString("outcome_sha256")))
                throw new IllegalStateException("continuation join immutable outcome differs");
            if(outbound && incoming && unrelated==0){
                proof=new JSONObject(identity.toString())
                    .put("schema","rusty.quest.embedded_duplex.original_completed_setup_join.v1")
                    .put("original_id",id).put("operation",prior.row.getString("operation"))
                    .put("request_sha256",prior.row.getString("request_sha256"))
                    .put("outcome_sha256",prior.row.getString("outcome_sha256"))
                    .put("actor_binding",new JSONObject(prior.row.getJSONObject("actor_binding").toString()))
                    .put("native_setup_binding",new JSONObject(prior.row.getJSONObject("native_setup_binding").toString()))
                    .put("original_state","succeeded").put("original_effect_state",prior.row.getString("effect_state"))
                    .put("original_worker_terminal",true).put("original_callback_terminal",true)
                    .put("outbound_exchanges_joined",true).put("incoming_request_tasks_joined",true)
                    .put("unrelated_unresolved_original_count",0).put("physical_cleanup_claimed",false)
                    .put("remote_outcome_resolved",false);
            }
        }
        Bundle result=new Bundle();result.putString("schema","rusty.quest.embedded_duplex.operator_original_completed_setup_join.v1");
        result.putString("status",proof==null?"pending":"joined");result.putString("challenge",challenge);result.putString("original_id",id);
        result.putString("continuation_join_json",proof==null?"null":EmbeddedDuplexCanonicalJson.encode(proof));return result;
    }
    synchronized JSONObject completedSetupJoinForNative(String nativeId,String challenge)throws Exception{
        Record found=null;
        java.util.ArrayList<Record> candidates=new java.util.ArrayList<>(live.values());
        File[] retained=directory.listFiles((dir,name)->name.matches("[0-9a-f]{32}\\.json"));
        if(retained==null)throw new IllegalStateException("completed setup durable Original listing unavailable");
        for(File file:retained){String id=file.getName().substring(0,32);if(!live.containsKey(id))candidates.add(find(id));}
        for(Record candidate:candidates){
            if(!java.util.Arrays.asList("pair_session","fresh_pair_continue").contains(candidate.row.getString("operation"))
                ||!"succeeded".equals(candidate.row.getString("state"))||candidate.row.isNull("native_setup_binding")
                ||!nativeId.equals(candidate.row.getJSONObject("native_setup_binding").getString("setup_request_id")))continue;
            if(found!=null)throw new IllegalStateException("ambiguous completed setup Original");found=candidate;
        }
        if(found==null)return null;
        Bundle result=queryCompletedSetupJoin(found.id(),challenge);
        if(!"joined".equals(result.getString("status")))throw new IllegalStateException("completed setup Original still joining");
        return new JSONObject(result.getString("continuation_join_json"));
    }
    private int unrelatedUnresolved(Record prior,Record current)throws Exception{
        File[] files=directory.listFiles((dir,name)->name.matches("[0-9a-f]{32}\\.json"));
        if(files==null)throw new IllegalStateException("continuation original listing unavailable");
        int count=0;
        for(File file:files){
            String id=file.getName().substring(0,32);Record actual=live.get(id);
            if(actual!=null && (actual==prior || actual==current))continue;
            JSONObject row=actual==null?read(file):actual.row;
            if(!"compacted".equals(row.getString("retention"))
                    && java.util.Arrays.asList("registered","pending","unknown").contains(row.getString("state"))){

                if(prior!=null && earnedPriorCustody(row,prior))continue;
                count++;
            }
        }
        return count;
    }
    private boolean earnedPriorCustody(JSONObject old,Record actualCurrentSource)throws Exception{
        if(!"unknown".equals(old.getString("state")))return false;
        String raw=new EmbeddedDuplexFreshHandoffStore(EmbeddedDuplexProcessHost.application()).priorBody(old.getString("original_id"));
        if(raw==null)return false;JSONObject body=new JSONObject(raw);
        JSONObject actor=EmbeddedDuplexProcessHost.continuationJoinIdentity(actualCurrentSource);
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})
            if(!EmbeddedDuplexCanonicalJson.sameValue(body.get(key),actor.get(key)))throw new IllegalStateException("old phase custody current source differs");
        if(!body.getString("prior_outcome_sha256").equals(old.getString("outcome_sha256"))
                || !EmbeddedDuplexCanonicalJson.sha256(outcome(old)).equals(old.getString("outcome_sha256")))
            throw new IllegalStateException("old phase custody immutable outcome changed");
        return true; // Blocking custody transferred; UNKNOWN/outcome remains unchanged on disk.
    }
    static void commandStageEntered(Record actual){
        if(actual!=null)actual.enteredCommandStages.incrementAndGet();
    }
    static void commandStageReturned(Record actual){
        if(actual!=null && actual.enteredCommandStages.decrementAndGet()<0)
            throw new IllegalStateException("actual command stage join underflow");
    }
    private synchronized void retainPhysicalJoin(Record record)throws Exception{
        if(record.physicalJoinProof==null || record.persistenceFailure!=null || record.row.isNull("outcome_sha256"))return;
        JSONObject basis;
        if(!record.restored && record.continuationWorkerJoined() && EmbeddedDuplexProcessHost.originalExchangesJoined(record))
            basis=new JSONObject().put("kind","actual_future_callback");
        else if(settledCustody(record.row)!=null){
            JSONObject held=settledCustody(record.row);
            if(held.getLong("app_process_generation")!=record.physicalJoinAppGeneration)
                throw new IllegalStateException("actual settled custody/whole actor differs");
            basis=new JSONObject().put("kind","actual_future_callback");
        }else{
            String raw=new EmbeddedDuplexFreshHandoffStore(EmbeddedDuplexProcessHost.application()).priorBody(record.id());
            if(raw==null)return;
            JSONObject identity=new JSONObject().put("app_process_generation",record.physicalJoinAppGeneration)
                .put("app_record_sha256",record.physicalJoinAppRecord).put("native_executor_generation",record.physicalJoinNativeGeneration)
                .put("config_sha256",record.physicalJoinProof.getString("config_sha256"));
            JSONObject ack=new EmbeddedDuplexFreshHandoffStore(EmbeddedDuplexProcessHost.application()).retainOperator(raw,identity);
            basis=new JSONObject().put("kind","retained_phase_handoff").put("native_handoff_json",raw).put("custody_ack",ack);
        }
        JSONObject proof=new JSONObject().put("schema","rusty.quest.embedded_duplex.original_join.v2")
            .put("original_id",record.id()).put("operation",record.row.getString("operation"))
            .put("request_sha256",record.row.getString("request_sha256")).put("outcome_sha256",record.row.getString("outcome_sha256"))
            .put("actor_binding",record.row.getJSONObject("actor_binding")).put("original_worker_terminal",true)
            .put("original_callback_terminal",true).put("outbound_exchanges_joined",true)
            .put("physical_retirement_schema",record.physicalJoinProof.getString("$schema"))
            .put("physical_retirement_sha256",EmbeddedDuplexCanonicalJson.sha256(record.physicalJoinProof))
            .put("app_process_generation",record.physicalJoinAppGeneration)
            .put("native_executor_generation",record.physicalJoinNativeGeneration==0?JSONObject.NULL:record.physicalJoinNativeGeneration)
            .put("app_record_sha256",record.physicalJoinAppRecord).put("remote_outcome_resolved",false).put("join_basis",basis);
        validateJoinBasis(basis,record.row,proof);
        String exact=EmbeddedDuplexCanonicalJson.encode(proof);File target=new File(directory,record.id()+".joined.json");
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS)
                    || !exact.equals(new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8)))
                throw new IllegalStateException("original joined receipt changed");
        }else{
            File temporary=new File(directory,record.id()+".joined.tmp");
            if(!temporary.createNewFile())throw new IllegalStateException("original join receipt pending write");
            Os.chmod(temporary.getAbsolutePath(),0600);
            try(FileOutputStream output=new FileOutputStream(temporary)){output.write(exact.getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);syncDirectory();
        }
        record.physicalJoinProof=null;record.operationJoined=true;
        if(!record.restored)compactIfJoined(record);
    }
    synchronized void actualClosedAppOwnerJoined(long appGeneration,long nativeGeneration,String appRecord,
            JSONObject actualPhysicalProof,Object retainedActualOwner)throws Exception{
        if(appGeneration<=0 || nativeGeneration<0 || appRecord==null || !appRecord.matches("[0-9a-f]{64}")
                || !"terminal".equals(actualPhysicalProof.getString("whole_app_physical_cleanup"))
                || !"terminal".equals(actualPhysicalProof.getString("app_process_fence_cleanup"))
                || actualPhysicalProof.getLong("app_process_generation")!=appGeneration)
            throw new IllegalStateException("actual app whole-retirement proof differs");
        java.util.ArrayList<Record> actualRows=new java.util.ArrayList<>(live.values());
        File[] retainedRows=directory.listFiles((dir,name)->name.matches("[0-9a-f]{32}\\.json"));
        if(retainedRows==null)throw new IllegalStateException("whole join durable Original listing unavailable");
        for(File rowFile:retainedRows){
            String id=rowFile.getName().substring(0,32);if(live.containsKey(id))continue;
            String raw=new EmbeddedDuplexFreshHandoffStore(EmbeddedDuplexProcessHost.application()).priorBody(id);
            Record metadata=find(id);
            if(raw==null){JSONObject settled=settledCustody(metadata.row);if(settled==null || settled.getLong("app_process_generation")!=appGeneration)continue;}
            else{
                JSONObject handoff=new JSONObject(raw);
                if(handoff.getLong("app_process_generation")!=appGeneration || handoff.getLong("native_executor_generation")!=nativeGeneration
                        || !handoff.getString("app_record_sha256").equals(appRecord))continue;
            }
            metadata.appGeneration=appGeneration;actualRows.add(metadata);
        }
        for(Record record:actualRows){
            if(record.appGeneration!=appGeneration)continue;
            JSONObject actor=record.row.getJSONObject("actor_binding");
            if(!actor.isNull("native_generation") && (actor.getLong("native_generation")!=nativeGeneration
                    || !actor.getString("app_record_sha256").equals(appRecord)))continue;
            record.physicalJoinProof=new JSONObject(actualPhysicalProof.toString());
            record.physicalJoinAppGeneration=appGeneration;record.physicalJoinNativeGeneration=nativeGeneration;
            record.physicalJoinAppRecord=appRecord;record.retainedCleanupOwner=retainedActualOwner;
            try{retainPhysicalJoin(record);}
            catch(Exception unavailable){record.joinPublicationFailure=unavailable;}
        }
    }

    private static void validateJoinBasis(JSONObject basis,JSONObject row,JSONObject proof)throws Exception{
        String kind=basis.getString("kind");
        if("actual_future_callback".equals(kind)){EmbeddedDuplexFreshHandoffStore.keys(basis,"kind");return;}
        EmbeddedDuplexFreshHandoffStore.keys(basis,"kind","native_handoff_json","custody_ack");
        if(!"retained_phase_handoff".equals(kind))throw new IllegalArgumentException("closed Original join basis");
        String raw=basis.getString("native_handoff_json");JSONObject body=new JSONObject(raw),ack=basis.getJSONObject("custody_ack");
        if(!row.getString("original_id").equals(body.getString("prior_original_id"))
                || !row.getString("outcome_sha256").equals(body.getString("prior_outcome_sha256"))
                || !"unknown".equals(row.getString("state"))
                || !ack.getString("body_sha256").equals("sha256:"+EmbeddedDuplexCanonicalJson.sha256Bytes(raw.getBytes(StandardCharsets.UTF_8)))
                || !EmbeddedDuplexCanonicalJson.sameValue(body.get("app_process_generation"),proof.get("app_process_generation"))
                || !EmbeddedDuplexCanonicalJson.sameValue(body.get("native_executor_generation"),proof.get("native_executor_generation"))
                || !body.get("app_record_sha256").equals(proof.get("app_record_sha256")))
            throw new IllegalStateException("actual retained phase custody/whole actor differs");
        // Re-read source-owned handoff bytes; disk metadata is not an invented Future.
        String retained=new EmbeddedDuplexFreshHandoffStore(EmbeddedDuplexProcessHost.application()).priorBody(row.getString("original_id"));
        if(!raw.equals(retained))throw new IllegalStateException("actual retained phase custody bytes changed");
    }

    /** Called only by the same app owner after its actual operation-specific joins/handoff. */
    synchronized void joined(Record actual) throws Exception {
        if (actual == null || live.get(actual.id()) != actual || actual.restored)
            throw new IllegalStateException("original join owner mismatch");
        if (!actual.callbackTerminal.isDone()) throw new IllegalStateException("original completion callback not joined");
        actual.operationJoined = true;
        compactIfJoined(actual);
    }
    private void retainSettledCustody(Record record)throws Exception{
        JSONObject actor=record.row.getJSONObject("actor_binding");Object nativeGeneration=actor.get("native_generation");
        if(record.appGeneration<=0 || record.row.isNull("outcome_sha256")
                || !(nativeGeneration instanceof Integer || nativeGeneration instanceof Long)
                || ((Number)nativeGeneration).longValue()<=0 || !(actor.opt("app_record_sha256") instanceof String)
                || !actor.getString("app_record_sha256").matches("[0-9a-f]{64}"))
            throw new IllegalStateException("actual settled operation actor absent");
        JSONObject proof=new JSONObject().put("schema","rusty.quest.embedded_duplex.settled_original_custody.v1")
            .put("original_id",record.id()).put("request_sha256",record.row.getString("request_sha256"))
            .put("outcome_sha256",record.row.getString("outcome_sha256")).put("actor_binding",record.row.getJSONObject("actor_binding"))
            .put("app_process_generation",record.appGeneration).put("original_worker_terminal",true)
            .put("original_callback_terminal",true).put("outbound_exchanges_joined",true)
            .put("physical_cleanup_claimed",false).put("remote_outcome_resolved",false);
        byte[] raw=EmbeddedDuplexCanonicalJson.encode(proof).getBytes(StandardCharsets.UTF_8);
        File target=new File(directory,record.id()+".settled.json");
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS)||!java.util.Arrays.equals(raw,Files.readAllBytes(target.toPath())))
                throw new IllegalStateException("settled custody immutable bytes differ");
        }else{
            File tmp=new File(directory,record.id()+".settled.tmp");if(!tmp.createNewFile())throw new IllegalStateException("settled custody pending write");
            Os.chmod(tmp.getAbsolutePath(),0600);try(FileOutputStream output=new FileOutputStream(tmp)){output.write(raw);output.getFD().sync();}
            Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
        }
        java.io.FileDescriptor fd=Os.open(target.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);try{Os.fsync(fd);}finally{Os.close(fd);}syncDirectory();
        if(!java.util.Arrays.equals(raw,Files.readAllBytes(target.toPath())))throw new IllegalStateException("settled custody durable readback differs");
    }
    private JSONObject settledCustody(JSONObject row)throws Exception{
        File target=new File(directory,row.getString("original_id")+".settled.json");if(!Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("settled custody retained type");
        JSONObject p=new JSONObject(new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));
        EmbeddedDuplexFreshHandoffStore.keys(p,"schema","original_id","request_sha256","outcome_sha256","actor_binding","app_process_generation",
            "original_worker_terminal","original_callback_terminal","outbound_exchanges_joined","physical_cleanup_claimed","remote_outcome_resolved");
        if(!"rusty.quest.embedded_duplex.settled_original_custody.v1".equals(p.getString("schema")) || !"succeeded".equals(row.getString("state"))
                || !p.getString("original_id").equals(row.getString("original_id")) || !p.getString("request_sha256").equals(row.getString("request_sha256"))
                || !p.getString("outcome_sha256").equals(row.getString("outcome_sha256"))
                || !EmbeddedDuplexCanonicalJson.encode(p.getJSONObject("actor_binding")).equals(EmbeddedDuplexCanonicalJson.encode(row.getJSONObject("actor_binding")))
                || !p.getBoolean("original_worker_terminal")||!p.getBoolean("original_callback_terminal")||!p.getBoolean("outbound_exchanges_joined")
                || p.getBoolean("physical_cleanup_claimed")||p.getBoolean("remote_outcome_resolved"))throw new IllegalStateException("actual settled custody tuple differs");
        return p;
    }
    private void compactIfJoined(Record record) throws Exception {
        if (!record.operationJoined || !record.callbackTerminal.isDone()
                || record.row.isNull("consumer_receipt_sha256") || record.row.isNull("outcome_sha256")) return;
        if ("succeeded".equals(record.row.getString("state"))) {
            if (!record.boundActor || record.persistenceFailure != null || !actors.settled(record.row.getJSONObject("actor_binding"), record.appGeneration)) return;
            // Preserve actual joined custody before releasing the Future which
            // later prepared-lifetime archive readers cannot reconstruct.
            if (record.persistenceFailure != null || !record.continuationWorkerJoined()
                    || !EmbeddedDuplexProcessHost.originalExchangesJoined(record)) return;
            // Enrollment and never-native app closure have no native actor.
            // Prepared SDK success, however, must never shed that binding.
            if ("prepare_peer_start".equals(record.row.getString("operation"))
                    || !record.row.getJSONObject("actor_binding").isNull("native_generation"))
                retainSettledCustody(record);
        }
        // Immutable UNKNOWN state/cause/identity/hash survives; only heavy in-process owners are released.
        record.row.put("retention", "compacted"); persist(record);
        Object retiredOwner=record.retainedCleanupOwner;
        record.future = null; record.retainedFailure = null;
        record.retainedCleanupOwner = null;
        record.actualServiceDeliveredFuture=null;
        live.remove(record.id());
        if(retiredOwner!=null)EmbeddedDuplexProcessHost.releaseClosedOwnerAfterCustody(retiredOwner);
    }
    synchronized boolean hasLiveCleanupOwner(Object actual){
        for(Record record:live.values())if(record.retainedCleanupOwner==actual)return true;
        return false;
    }
    private Record requireContinuationPrior(String id,String outcomeHash)throws Exception{
        Record prior=live.get(id);
        if(prior==null || prior.restored || !java.util.Arrays.asList("pair_session","fresh_pair_continue").contains(prior.row.getString("operation"))
                || !"unknown".equals(prior.row.getString("state")) || !outcomeHash.equals(prior.row.optString("outcome_sha256"))
                || !prior.continuationWorkerJoined()
                || prior.persistenceFailure!=null || !EmbeddedDuplexProcessHost.originalExchangesJoined(prior)
                || prior.row.isNull("native_setup_binding"))throw new IllegalStateException("continuation predecessor not joined actual UNKNOWN");
        EmbeddedDuplexProcessHost.requireCurrentOriginalStopActor(prior);
        EmbeddedDuplexProcessHost.continuationJoinIdentity(prior);
        if(!EmbeddedDuplexProcessHost.originalIncomingExchangesJoined(prior)
                || unrelatedUnresolved(prior,capture())!=0 || capture()!=null && capture().desiredStop.get())
            throw new IllegalStateException("continuation predecessor task/other original/desired Stop remains owned");
        JSONObject durable=read(path(id));
        if(!EmbeddedDuplexCanonicalJson.encode(durable).equals(EmbeddedDuplexCanonicalJson.encode(prior.row))
                || !EmbeddedDuplexCanonicalJson.sha256(outcome(durable)).equals(outcomeHash))
            throw new IllegalStateException("continuation predecessor not durable immutable outcome");
        return prior;
    }
    private Record continuationPredecessor(Record current)throws Exception{
        if(current==null || !"fresh_pair_continue".equals(current.row.getString("operation")))return null;
        if(current.restored || live.get(current.id())!=current || !CONTINUATION_RECORD.equals(current.row.getString("schema")))
            throw new IllegalStateException("continuation current owner unavailable");
        JSONObject binding=current.row.getJSONObject("predecessor_binding");
        Record prior;
        if(current.continuationHandoff==null)prior=requireContinuationPrior(binding.getString("original_id"),binding.getString("outcome_sha256"));
        else{
            prior=find(binding.getString("original_id"));JSONObject handoff=current.continuationHandoff;
            if(prior==null || !"unknown".equals(prior.row.getString("state"))
                    || !binding.getString("outcome_sha256").equals(prior.row.getString("outcome_sha256"))
                    || !binding.getString("original_id").equals(handoff.getString("prior_original_id"))
                    || !current.id().equals(handoff.getString("current_original_id")))throw new IllegalStateException("earned prior custody changed");
            JSONObject durable=read(path(prior.id()));
            if(!EmbeddedDuplexCanonicalJson.sha256(outcome(durable)).equals(binding.getString("outcome_sha256")))
                throw new IllegalStateException("earned prior immutable outcome changed");
            EmbeddedDuplexProcessHost.requireCurrentOriginalStopActor(current);
        }
        JSONObject old=prior.row.getJSONObject("native_setup_binding");
        if(!binding.getString("native_setup_request_id").equals(old.getString("setup_request_id"))
                || !binding.get("context_sha256").equals(old.get("context_sha256"))
                || !EmbeddedDuplexCanonicalJson.encode(prior.row.getJSONObject("actor_binding"))
                    .equals(EmbeddedDuplexCanonicalJson.encode(current.row.getJSONObject("actor_binding"))))
            throw new IllegalStateException("continuation exact predecessor binding changed");
        return prior;
    }
    static JSONObject freshSetupContinuationBinding(JSONObject actualIdentity)throws Exception{
        EmbeddedDuplexOriginals owner=singleton;Record current=capture();
        if(owner==null)throw new IllegalStateException("continuation registry absent");
        synchronized(owner){
            Record prior=owner.continuationPredecessor(current);
            if(prior==null)throw new IllegalStateException("actual continuation original absent");
            JSONObject old=prior.row.getJSONObject("native_setup_binding");
            JSONObject result=new JSONObject().put("$schema","rusty.quest.embedded_duplex.fresh_setup_continuation_binding.v1")
                .put("prior_original_id",prior.id()).put("prior_outcome_sha256",prior.row.getString("outcome_sha256"))
                .put("native_request_id",old.getString("setup_request_id")).put("prior_context_sha256",old.get("context_sha256"))
                .put("current_original_id",current.id());
            for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})
                result.put(key,actualIdentity.get(key));
            return result;
        }
    }
    static JSONObject bindOperatorPhase(String nativeId,String phaseId,JSONObject identity)throws Exception{
        EmbeddedDuplexIncomingFreshPhases.id(nativeId);EmbeddedDuplexIncomingFreshPhases.id(phaseId);
        EmbeddedDuplexOriginals owner=singleton;Record actual=capture();
        if(owner==null || actual==null)throw new IllegalStateException("actual operator phase absent");
        synchronized(owner){
            if(actual.restored || owner.live.get(actual.id())!=actual || actual.row.isNull("native_setup_binding")
                    || !nativeId.equals(actual.row.getJSONObject("native_setup_binding").getString("setup_request_id"))
                    || actual.desiredStop.get())throw new IllegalStateException("actual native operator phase differs/Stop");
            EmbeddedDuplexProcessHost.requireCurrentOriginalStopActor(actual);
            JSONObject binding=new JSONObject().put("kind","operator").put("original_id",actual.id());
            new EmbeddedDuplexFreshPhaseContexts(EmbeddedDuplexProcessHost.application()).bindOperator(nativeId,phaseId,binding,identity);
            return binding;
        }
    }
    synchronized JSONObject requireOperatorContext(String nativeId,String phaseId,JSONObject identity)throws Exception{
        Record actual=capture();
        if(actual==null || live.get(actual.id())!=actual || actual.restored || actual.desiredStop.get()
                || !nativeId.equals(nativeSetupRequestId(actual)))throw new IllegalStateException("actual context operator absent or stopped");
        EmbeddedDuplexProcessHost.requireCurrentOriginalStopActor(actual);
        JSONObject binding=new JSONObject().put("kind","operator").put("original_id",actual.id());
        new EmbeddedDuplexFreshPhaseContexts(EmbeddedDuplexProcessHost.application()).requireOperator(nativeId,phaseId,binding,identity);
        return binding;
    }
    synchronized JSONObject retainContinuationHandoff(String raw,JSONObject identity)throws Exception{
        Record current=capture();Record prior=continuationPredecessor(current);JSONObject body=new JSONObject(raw);
        if(prior==null || !current.id().equals(body.getString("current_original_id"))
                || !prior.id().equals(body.getString("prior_original_id"))
                || !prior.row.getString("outcome_sha256").equals(body.getString("prior_outcome_sha256"))
                || !body.getString("native_request_id").equals(nativeSetupRequestId(prior)))
            throw new IllegalStateException("actual continuation native handoff predecessor differs");
        EmbeddedDuplexFreshPhaseContexts contexts=new EmbeddedDuplexFreshPhaseContexts(EmbeddedDuplexProcessHost.application());
        contexts.requireOperator(body.getString("native_request_id"),body.getString("phase_request_id"),
            new JSONObject().put("kind","operator").put("original_id",current.id()),identity);
        JSONObject archived=new EmbeddedDuplexFreshPhaseArchive(EmbeddedDuplexProcessHost.application())
            .requireRetained(body.getJSONObject("local_archive"),identity);
        if(!body.getString("native_request_id").equals(archived.getString("native_request_id"))
                || !body.getString("prior_phase_id").equals(archived.getString("phase_request_id"))
                || !java.util.Arrays.asList("fenced_unknown","preparation_fenced_unknown","reservation_fenced_unknown").contains(archived.getString("phase_status"))
                || !EmbeddedDuplexCanonicalJson.encode(archived.getJSONObject("original_binding")).equals(
                    EmbeddedDuplexCanonicalJson.encode(new JSONObject().put("kind","operator").put("original_id",prior.id()))))
            throw new IllegalStateException("actual old native phase archive differs");
        contexts.requireOperator(body.getString("native_request_id"),body.getString("prior_phase_id"),archived.getJSONObject("original_binding"),identity);
        JSONObject peer=body.getJSONObject("peer_archive");
        EmbeddedDuplexFreshHandoffStore.keys(peer,"$schema","body_sha256","app_process_generation","app_record_sha256","native_executor_generation",
            "config_sha256","native_request_id","phase_request_id","original_binding","storage_identity");
        if(!"rusty.quest.embedded_duplex.fresh_setup_phase_archive_retained.v2".equals(peer.getString("$schema"))
                || !peer.getString("body_sha256").matches("sha256:[0-9a-f]{64}")
                || !body.getString("native_request_id").equals(peer.getString("native_request_id"))
                || !body.getString("prior_phase_id").equals(peer.getString("phase_request_id")))
            throw new IllegalStateException("actual authenticated peer archive projection differs");
        // Native supplies this callback only after both verified phase fences
        // and actual same-owner adoption. Java adds real prior task/disk custody.
        JSONObject ack=new EmbeddedDuplexFreshHandoffStore(EmbeddedDuplexProcessHost.application()).retainOperator(raw,identity);
        current.continuationHandoff=new JSONObject(body.toString());
        prior.operationJoined=true;compactIfJoined(prior);
        return ack;
    }
    public synchronized Bundle queryContinuationHandoff(String id,String challenge)throws Exception{
        requireId(id);requireId(challenge);Record current=find(id);JSONObject proof=null;
        if(current==null || !CONTINUATION_RECORD.equals(current.row.getString("schema")))throw new IllegalStateException("continuation handoff target absent");
        String raw=new EmbeddedDuplexFreshHandoffStore(EmbeddedDuplexProcessHost.application()).operatorBody(id);
        if(raw!=null && !current.row.isNull("outcome_sha256")){
            EmbeddedDuplexProcessHost.requireCurrentOriginalStopActor(current);
            JSONObject identity=EmbeddedDuplexProcessHost.continuationJoinIdentity(current),body=new JSONObject(raw);
            JSONObject ack=new EmbeddedDuplexFreshHandoffStore(EmbeddedDuplexProcessHost.application()).retainOperator(raw,identity);
            JSONObject predecessor=current.row.getJSONObject("predecessor_binding");
            if(!id.equals(body.getString("current_original_id")) || !predecessor.getString("original_id").equals(body.getString("prior_original_id"))
                    || !predecessor.getString("outcome_sha256").equals(body.getString("prior_outcome_sha256")))
                throw new IllegalStateException("retained handoff immutable original differs");
            proof=new JSONObject().put("schema","rusty.quest.embedded_duplex.original_continuation_handoff.v1")
                .put("original_id",id).put("request_sha256",current.row.getString("request_sha256"))
                .put("outcome_sha256",current.row.getString("outcome_sha256")).put("actor_binding",current.row.getJSONObject("actor_binding"))
                .put("predecessor_binding",predecessor).put("native_handoff_json",raw).put("custody_ack",ack);
        }
        Bundle result=new Bundle();result.putString("schema","rusty.quest.embedded_duplex.operator_original_continuation_handoff.v1");
        result.putString("status",proof==null?"pending":"retained");result.putString("challenge",challenge);result.putString("original_id",id);
        result.putString("handoff_json",proof==null?"null":EmbeddedDuplexCanonicalJson.encode(proof));return result;
    }
    static String nativeSetupRequestId(Record actual)throws Exception{
        EmbeddedDuplexOriginals owner=singleton;if(owner==null || actual==null)return null;
        synchronized(owner){return actual.row.isNull("native_setup_binding")?null:actual.row.getJSONObject("native_setup_binding").getString("setup_request_id");}
    }
    static void bindFreshSetupOriginal(String requestId)throws Exception{
        if(requestId==null || !requestId.matches("[a-z][a-z0-9_.-]{0,255}"))throw new IllegalArgumentException("native setup request identity");
        Record actual=capture();if(actual==null || !java.util.Arrays.asList("pair_session","fresh_pair_continue").contains(actual.row.getString("operation")))
            throw new IllegalStateException("actual fresh operator original absent");
        EmbeddedDuplexOriginals owner=singleton;
        synchronized(owner){
            if(actual.row.isNull("native_setup_binding"))actual.row.put("native_setup_binding",new JSONObject()
                .put("setup_request_id",requestId).put("context_sha256",JSONObject.NULL));
            else if(!requestId.equals(actual.row.getJSONObject("native_setup_binding").getString("setup_request_id")))
                throw new IllegalStateException("native setup original rebind");
            owner.persist(actual);
            if(actual.desiredStop.get())throw new IllegalStateException("actual fresh original desired Stop before prime");
        }
    }
    static void bindFreshSetupContext(String requestId,String digest)throws Exception{
        if(digest==null || !digest.matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("native setup context digest");
        Record actual=capture();if(actual==null || actual.row.isNull("native_setup_binding"))
            throw new IllegalStateException("native setup original not bound");
        EmbeddedDuplexOriginals owner=singleton;
        synchronized(owner){
            JSONObject binding=actual.row.getJSONObject("native_setup_binding");
            if(!requestId.equals(binding.getString("setup_request_id")) || (!binding.isNull("context_sha256")
                    && !digest.equals(binding.getString("context_sha256"))))throw new IllegalStateException("native setup context rebind");
            binding.put("context_sha256",digest);owner.persist(actual);
            if(actual.desiredStop.get())throw new IllegalStateException("actual fresh original desired Stop before authorization");
        }
    }
    static void bindActualAppGeneration(long generation){
        Record actual=capture();if(actual!=null){
            if(actual.appGeneration!=0 && actual.appGeneration!=generation)
                throw new IllegalStateException("original app owner changed");
            actual.appGeneration=generation;
        }
    }
    synchronized int unresolvedExceptCurrent() throws Exception {
        Record current = capture(); Record predecessor=continuationPredecessor(current); int count = 0;
        File[] rows = directory.listFiles((dir, name) -> name.matches("[0-9a-f]{32}\\.json"));
        if (rows == null) throw new IllegalStateException("original listing unavailable");
        for (File file : rows) {
            String id = file.getName().substring(0, 32);
            Record actual = live.get(id); if (actual!=null && (actual == current || actual==predecessor)) continue;
            JSONObject row = actual == null ? read(file) : actual.row;
            String state = row.getString("state");
            if (!"compacted".equals(row.getString("retention"))
                    && (state.equals("registered") || state.equals("pending") || state.equals("unknown"))){

                if(current!=null && earnedPriorCustody(row,current))continue;
                count++;
            }
        }
        return count;
    }
    static String currentOriginalId() {
        Record actual = capture(); return actual == null ? null : actual.id();
    }
    synchronized JSONObject requireTerminalUnknown(String id) throws Exception {
        Record record = find(id);
        if (record == null || record.restored || !"unknown".equals(record.row.getString("state"))
                || !record.callbackTerminal.isDone() || record.future == null || !record.future.isDone()
                || !java.util.Arrays.asList("pair_session","fresh_pair_continue").contains(record.row.getString("operation")))
            throw new IllegalStateException("fresh unknown original is not the current terminal worker");
        return new JSONObject(record.row.toString());
    }
    synchronized void requirePhaseArchiveOperator(JSONObject body)throws Exception{
        JSONObject binding=body.getJSONObject("original_binding");
        if(binding.length()!=2 || !"operator".equals(binding.getString("kind")))throw new IllegalArgumentException("fresh phase operator binding");
        String id=binding.getString("original_id");requireId(id);Record record=find(id);
        if(record==null || !java.util.Arrays.asList("pair_session","fresh_pair_continue").contains(record.row.getString("operation"))
                || record.row.isNull("native_setup_binding")
                || !body.getString("native_request_id").equals(record.row.getJSONObject("native_setup_binding").getString("setup_request_id")))
            throw new IllegalStateException("fresh phase actual operator differs");
        JSONObject settled=record.restored?settledCustody(record.row):null;
        if(settled!=null){
            record.appGeneration=settled.getLong("app_process_generation");
            if(record.appGeneration!=body.getLong("app_process_generation"))throw new IllegalStateException("actual phase archive settled app differs");
            EmbeddedDuplexProcessHost.requireCurrentOriginalStopActor(record);
        }
        JSONObject actor=record.row.getJSONObject("actor_binding");
        if(!EmbeddedDuplexCanonicalJson.sameValue(actor.get("native_generation"),body.get("native_executor_generation"))
                || !actor.get("app_record_sha256").equals(body.get("app_record_sha256")))throw new IllegalStateException("fresh phase operator actor differs");
        if(java.util.Arrays.asList("fenced_unknown","preparation_fenced_unknown","reservation_fenced_unknown").contains(body.getString("phase_status"))){
            requireTerminalUnknown(id);
            if(!EmbeddedDuplexProcessHost.originalExchangesJoined(record))throw new IllegalStateException("fresh phase old socket not joined");
            Record current=capture();
            if(current==record || continuationPredecessor(current)!=record)throw new IllegalStateException("fresh phase lacks actual continuation handoff");
        }else if(!java.util.Arrays.asList("completed","preparation_completed").contains(body.getString("phase_status"))
                || !"succeeded".equals(record.row.getString("state")) || !(settled!=null||record.continuationWorkerJoined())
                || !(settled!=null||EmbeddedDuplexProcessHost.originalExchangesJoined(record))
                || !EmbeddedDuplexProcessHost.originalIncomingExchangesJoined(record))
            throw new IllegalStateException("fresh completed phase original not genuinely joined");
    }
    synchronized String requireUnknownPeerRootTerminal(JSONObject body,JSONObject actor)throws Exception {
        Record current=capture();EmbeddedDuplexProcessHost.requireUnknownPeerRootCustodySpan(current,actor);
        String id=body.getString("original_id");requireId(id);Record prior=live.get(id);
        if(prior==null || prior==current || prior.restored || !"reestablish_peer_roots".equals(prior.row.getString("operation"))
            ||!"unknown".equals(prior.row.getString("state"))||!"uncertain".equals(prior.row.getString("effect_state"))
            ||!prior.continuationWorkerJoined()||!EmbeddedDuplexProcessHost.originalExchangesJoined(prior)
            ||prior.persistenceFailure!=null||prior.row.isNull("outcome_sha256"))
            throw new IllegalStateException("unknown root actual worker/callback/exchange/command stages not joined");
        JSONObject durable=read(path(id));String hash=durable.getString("outcome_sha256");
        if(!"unknown".equals(durable.getString("state"))||!"uncertain".equals(durable.getString("effect_state"))
            ||!hash.matches("[0-9a-f]{64}")||!hash.equals(prior.row.getString("outcome_sha256"))
            ||!hash.equals(EmbeddedDuplexCanonicalJson.sha256(outcome(durable))))
            throw new IllegalStateException("unknown root immutable durable outcome differs");
        JSONObject binding=durable.getJSONObject("actor_binding");
        if(!EmbeddedDuplexCanonicalJson.sameValue(binding.get("native_generation"),actor.get("native_executor_generation"))
            ||!binding.getString("app_record_sha256").equals(actor.getString("app_record_sha256")))
            throw new IllegalStateException("unknown root actual original actor differs");
        return hash; // Never rewrite UNKNOWN or mint a success/cleanup outcome.
    }
    synchronized void requireOldUnknownWholeCleanup(String id)throws Exception {
        requireId(id);Record prior=find(id);
        if(prior==null||!"whole_app_close".equals(prior.row.getString("operation")))throw new IllegalStateException("actual old whole cleanup Original absent");
        requireTerminalUnknown(id);
        if(!EmbeddedDuplexProcessHost.originalExchangesJoined(prior))throw new IllegalStateException("actual old whole cleanup exchanges remain live");
    }
    synchronized void requirePreparedWholeClosure(JSONObject now,JSONObject actor)throws Exception {
        String id=now.getString("original_id");requireId(id);Record prior=find(id);JSONObject durable=read(path(id));
        JSONObject settled=settledCustody(durable);
        Object appGeneration=settled==null?null:settled.opt("app_process_generation");
        if(prior==null||!"prepare_peer_start".equals(durable.getString("operation"))||!"succeeded".equals(durable.getString("state"))
            ||!"observed".equals(durable.getString("effect_state"))||durable.isNull("consumer_receipt_sha256")||settled==null
            ||!(appGeneration instanceof Integer||appGeneration instanceof Long)||((Number)appGeneration).longValue()<=0
            ||((Number)appGeneration).longValue()!=actor.getLong("app_process_generation")
            ||!prior.restored&&(!prior.operationJoined||!EmbeddedDuplexProcessHost.originalExchangesJoined(prior))
            ||!EmbeddedDuplexCanonicalJson.sha256(outcome(durable)).equals(durable.getString("outcome_sha256"))
            ||!durable.getString("outcome_sha256").equals(prior.row.getString("outcome_sha256")))
            throw new IllegalStateException("actual completed preparation custody absent from whole close");
        // Compaction releases the live Future. Recover only its already earned
        // current-app identity; the settled proof carries the historical joins.
        if(prior.restored)prior.appGeneration=((Number)appGeneration).longValue();
        EmbeddedDuplexProcessHost.requireCurrentOriginalStopActor(prior);
        JSONObject binding=durable.getJSONObject("actor_binding");
        if(binding.getLong("native_generation")!=actor.getLong("native_executor_generation")
            ||!binding.getString("app_record_sha256").equals(actor.getString("app_record_sha256")))throw new IllegalStateException("whole-close prepared actor differs");
        JSONObject old=new JSONObject(durable.getJSONObject("response").getString("receipt"));
        for(String key:new String[]{"preparation_id","original_id","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","request_sha256","resource_target_sha256","owner","remote_decoder"})
            if(!EmbeddedDuplexCanonicalJson.sameValue(old.get(key),now.get(key)))throw new IllegalStateException("whole-close prepared immutable readback differs");
    }
    synchronized void requirePreparedArchive(JSONObject body,JSONObject actor)throws Exception {
        String id=body.getString("original_id");requireId(id);Record prior=find(id);JSONObject durable=read(path(id));
        if(prior==null||!"prepare_peer_start".equals(durable.getString("operation"))
            ||!EmbeddedDuplexCanonicalJson.sha256(outcome(durable)).equals(durable.getString("outcome_sha256"))
            ||!durable.getString("outcome_sha256").equals(prior.row.getString("outcome_sha256")))throw new IllegalStateException("actual durable prepared Original differs");
        JSONObject binding=durable.getJSONObject("actor_binding");
        if(binding.getLong("native_generation")!=actor.getLong("native_executor_generation")||!binding.getString("app_record_sha256").equals(actor.getString("app_record_sha256")))
            throw new IllegalStateException("actual archived prepared actor differs");
        JSONObject terminal=body.getJSONObject("terminal_lifetime");String kind=terminal.getString("kind"),cleanupId=terminal.getString("original_id");
        if("normal_peer_stop".equals(kind)) {
            if(!"succeeded".equals(durable.getString("state"))||durable.isNull("consumer_receipt_sha256")||settledCustody(durable)==null)
                throw new IllegalStateException("actual succeeded acknowledged preparation absent");
            requireArchivedStop(cleanupId,terminal.getJSONObject("receipt"),actor);
        } else if("explicit_prepared_abort".equals(kind)) {
            JSONObject abort=read(path(cleanupId));
            if(!"abort_prepared_peer_start".equals(abort.getString("operation"))||!"succeeded".equals(abort.getString("state"))||settledCustody(abort)==null
                ||abort.isNull("consumer_receipt_sha256")||!EmbeddedDuplexCanonicalJson.sameValue(new JSONObject(abort.getJSONObject("response").getString("receipt")),terminal.getJSONObject("receipt")))
                throw new IllegalStateException("actual settled prepared abort custody differs");
        } else if("current_cleanup_abort".equals(kind))EmbeddedDuplexProcessHost.requirePreparedCleanupArchive(cleanupId);
        else throw new IllegalStateException("closed prepared archive lifetime kind differs");
        if("unknown".equals(durable.getString("state")))requireTerminalUnknown(id);
        else if(!"succeeded".equals(durable.getString("state")))throw new IllegalStateException("actual prepared Original is not terminal");
        if(!durable.isNull("response")) {
            JSONObject old=new JSONObject(durable.getJSONObject("response").getString("receipt")),now=body.getJSONObject("prepared_status");
            for(String key:new String[]{"preparation_id","original_id","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","request_sha256","resource_target_sha256","owner","remote_decoder"})
                if(!EmbeddedDuplexCanonicalJson.sameValue(old.get(key),now.get(key)))throw new IllegalStateException("archived prepared immutable outcome differs");
        }
    }
    synchronized void requireArchivedPeerRoots(String exact,JSONObject body,JSONObject actor)throws Exception {
        String id=body.getString("original_id");requireId(id);Record prior=find(id);
        if(prior==null || !"reestablish_peer_roots".equals(prior.row.getString("operation"))
                || !"succeeded".equals(prior.row.getString("state")) || !"observed".equals(prior.row.getString("effect_state")))
            throw new IllegalStateException("root custody lacks actual succeeded Original");
        JSONObject durable=read(path(id));
        requireArchivedSettledCustody(durable,actor);
        if(!"succeeded".equals(durable.getString("state")) || !"observed".equals(durable.getString("effect_state"))
                || !EmbeddedDuplexCanonicalJson.sha256(outcome(durable)).equals(durable.getString("outcome_sha256"))
                || !durable.getString("outcome_sha256").equals(prior.row.getString("outcome_sha256"))
                || !EmbeddedDuplexCanonicalJson.sha256(durable.getJSONObject("response")).equals(durable.getString("response_sha256")))
            throw new IllegalStateException("root custody actual durable outcome differs");
        JSONObject binding=durable.getJSONObject("actor_binding");
        if(!EmbeddedDuplexCanonicalJson.sameValue(binding.get("native_generation"),actor.get("native_executor_generation"))
                || !binding.getString("app_record_sha256").equals(actor.getString("app_record_sha256")))
            throw new IllegalStateException("root custody actual Original actor differs");
        JSONObject response=durable.getJSONObject("response");
        if(!exact.equals(response.getString("receipt")))throw new IllegalStateException("root custody exact Native result changed");
        EmbeddedDuplexPeerRoots.result(exact,actor,id,body.getJSONObject("arm_binding"));
    }
    synchronized void requireArchivedStop(String id,JSONObject actualNativeProjection,JSONObject actor)throws Exception{
        requireId(id);Record prior=find(id);
        if(prior==null || !"peer_stop".equals(prior.row.getString("operation"))
                || !"succeeded".equals(prior.row.getString("state")) || !"observed".equals(prior.row.getString("effect_state")))
            throw new IllegalStateException("archive lacks genuine settled Stop original");
        // Always re-read fsynced row, not merely an in-memory result waiting for publication.
        JSONObject durable=read(path(id));
        requireArchivedSettledCustody(durable,actor);
        if(!EmbeddedDuplexCanonicalJson.sha256(outcome(durable)).equals(prior.row.getString("outcome_sha256")))
            throw new IllegalStateException("archive Stop original not durably joined");
        JSONObject binding=durable.getJSONObject("actor_binding");
        if(binding.getLong("native_generation")!=actor.getLong("native_executor_generation")
                || !binding.getString("app_record_sha256").equals(actor.getString("app_record_sha256")))
            throw new IllegalStateException("archive Stop actor differs");
        JSONObject wrapper=new JSONObject(durable.getJSONObject("response").getString("receipt"));
        if(!"rusty.quest.stereo.concurrent_qualification_receipt.v1".equals(wrapper.getString("schema"))
                || !"peer_stop".equals(wrapper.getString("action"))
                || !actor.getString("config_sha256").equals(wrapper.getString("runtime_config_sha256")))
            throw new IllegalStateException("archive Stop wrapper differs");
        JSONObject nativeStop=wrapper.getJSONObject("peer_lifecycle");
        if(!"peer_stop".equals(nativeStop.getString("action")) || !"terminal".equals(nativeStop.getString("status"))
                || !"terminal".equals(nativeStop.getString("peer_physical_cleanup"))
                || !EmbeddedDuplexCanonicalJson.sha256(nativeStop).equals(EmbeddedDuplexCanonicalJson.sha256(actualNativeProjection)))
            throw new IllegalStateException("archive native Stop projection differs");
    }
    private void requireArchivedSettledCustody(JSONObject durable,JSONObject actor)throws Exception {
        JSONObject proof=settledCustody(durable);
        Object generation=proof==null?null:proof.opt("app_process_generation");
        if(!"succeeded".equals(durable.getString("state"))||!"observed".equals(durable.getString("effect_state"))
            ||!"compacted".equals(durable.getString("retention"))||durable.isNull("consumer_receipt_sha256")||proof==null
            ||!(generation instanceof Integer||generation instanceof Long)||((Number)generation).longValue()<=0
            ||((Number)generation).longValue()!=actor.getLong("app_process_generation"))
            throw new IllegalStateException("archive lacks acknowledged settled custody");
    }
    synchronized int unresolvedForRetirement(JSONObject binding) throws Exception {
        Record excluded=null;
        if(binding!=null && "operator".equals(binding.getString("kind"))){
            String id=binding.getString("original_id");requireTerminalUnknown(id);excluded=live.get(id);
        }
        Record current=capture();Record predecessor=continuationPredecessor(excluded);int count=0;
        File[] files=directory.listFiles((dir,name)->name.matches("[0-9a-f]{32}\\.json"));
        if(files==null)throw new IllegalStateException("retirement original listing");
        for(File file:files){
            String id=file.getName().substring(0,32);Record actual=live.get(id);
            if(actual!=null && (actual==current || actual==excluded || actual==predecessor))continue;
            JSONObject row=actual==null?read(file):actual.row;String state=row.getString("state");
            if(!"compacted".equals(row.getString("retention")) && java.util.Arrays.asList("registered","pending","unknown").contains(state)
                   )count++;
        }
        return count;
    }
    synchronized void actualClosedActorJoined(long generation,String appRecord,Object retainedActualOwner) throws Exception {
        for(Record record:new java.util.ArrayList<>(live.values())) {
            JSONObject actor=record.row.getJSONObject("actor_binding");
            if(record.callbackTerminal.isDone() && !actor.isNull("native_generation")
                    && actor.getLong("native_generation")==generation && appRecord.equals(actor.optString("app_record_sha256"))) {
                record.retainedCleanupOwner=retainedActualOwner;
                joined(record);
            }
        }
    }
    public static JSONObject bundleObject(Bundle bundle) throws Exception {
        if (bundle == null) throw new IllegalArgumentException("actual response absent");
        JSONObject object = new JSONObject();
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if (!(value == null || value instanceof String || value instanceof Boolean
                    || value instanceof Integer || value instanceof Long))
                throw new IllegalArgumentException("operator response type");
            object.put(key, value == null ? JSONObject.NULL : value);
        }
        return object;
    }
    private File path(String id) { requireId(id); return new File(directory, id + ".json"); }
    private JSONObject read(File file) throws Exception {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file.toPath())
                || Files.size(file.toPath()) > MAX_RECORD_BYTES) throw new IllegalStateException("original checkpoint type/bounds");
        JSONObject row=new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        java.util.HashSet<String> keys=new java.util.HashSet<>();row.keys().forEachRemaining(keys::add);
        java.util.HashSet<String> expected=new java.util.HashSet<>(java.util.Arrays.asList("schema","original_id","operation","request_sha256",
            "original_challenge","state","effect_state","actor_binding","native_setup_binding","response","response_sha256",
            "failure","outcome_sha256","retention","consumer_receipt_sha256"));
        boolean continuation=CONTINUATION_RECORD.equals(row.getString("schema"));
        if(continuation)expected.add("predecessor_binding");
        if(!keys.equals(expected) || !(continuation || RECORD.equals(row.getString("schema")))
                || continuation!=row.getString("operation").equals("fresh_pair_continue"))throw new IllegalStateException("original checkpoint closed schema");
        if(continuation){
            JSONObject predecessor=row.getJSONObject("predecessor_binding");java.util.HashSet<String> priorKeys=new java.util.HashSet<>();predecessor.keys().forEachRemaining(priorKeys::add);
            if(!priorKeys.equals(new java.util.HashSet<>(java.util.Arrays.asList("original_id","outcome_sha256","native_setup_request_id","context_sha256"))))
                throw new IllegalStateException("continuation checkpoint closed predecessor");
            requireId(predecessor.getString("original_id"));requireHash(predecessor.getString("outcome_sha256"));
            if(!predecessor.getString("native_setup_request_id").matches("[a-z][a-z0-9_.-]{0,255}")
                    || !predecessor.isNull("context_sha256") && !predecessor.getString("context_sha256").matches("sha256:[0-9a-f]{64}"))
                throw new IllegalStateException("continuation checkpoint native predecessor");
        }
        requireId(row.getString("original_id"));requireId(row.getString("original_challenge"));requireHash(row.getString("request_sha256"));
        if(!row.isNull("response")){
            validateResponse(row.getString("operation"),row.getString("original_challenge"),row.getJSONObject("response"));
            if(!EmbeddedDuplexCanonicalJson.sha256(row.getJSONObject("response")).equals(row.getString("response_sha256")))
                throw new IllegalStateException("original response bytes changed");
        }
        if(!row.isNull("outcome_sha256") && !EmbeddedDuplexCanonicalJson.sha256(outcome(row)).equals(row.getString("outcome_sha256")))
            throw new IllegalStateException("original outcome binding changed");
        return row;
    }
    private void syncDirectory() throws Exception {
        java.io.FileDescriptor fd = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
        try { Os.fsync(fd); } finally { Os.close(fd); }
    }
    private static void requireId(String value) {
        if (value == null || !value.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("original identity");
    }
    private static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("original digest");
    }
    /** A completed Java call can return actual native Pending; retain its response without declaring settlement. */
    private static JSONObject nativePending(Record original,String operation,JSONObject response)throws Exception{
        if(operation.equals("prepare_peer_start")) {
            JSONObject nativeReceipt=new JSONObject(response.getString("receipt"));
            if(!"ready".equals(nativeReceipt.optString("state")))return nativeReceipt;
            EmbeddedDuplexProcessHost.requirePreparedOriginal(original,nativeReceipt);return null;
        }
        if(operation.equals("pair_session") || operation.equals("fresh_pair_continue")){
            if(!response.getString("pair_state").equals("peer_session_current_route_unverified")
                    || !response.getBoolean("local_session_current") || !response.getBoolean("remote_session_current"))
                return new JSONObject();
            return null;
        }
        if(!java.util.Arrays.asList("start","renew_authority","peer_stop","peer_revoke","whole_app_close").contains(operation))return null;
        JSONObject receipt=new JSONObject(response.getString("receipt"));
        if(EmbeddedDuplexRuntimeConfigurationBinding.WHOLE_V2.equals(receipt.optString("$schema"))
                ||EmbeddedDuplexConstructionClosure.WHOLE.equals(receipt.optString("$schema"))){
            if(!operation.equals("whole_app_close"))throw new IllegalArgumentException("configured no-media operation differs");
            EmbeddedDuplexProcessHost.requireCompletedNoMediaOriginal(original,receipt);
            return null; // Actual stored constructor AND actual same retained physical owner joined.
        }
        if(receipt.optString("$schema").equals("rusty.quest.embedded_duplex.no_media_whole_retirement.v1")){
            if(!operation.equals("whole_app_close") || !receipt.getString("whole_app_physical_cleanup").equals("terminal"))
                throw new IllegalArgumentException("no-media whole original differs");
            return null;
        }
        if(!receipt.getString("schema").equals("rusty.quest.stereo.concurrent_qualification_receipt.v1"))
            throw new IllegalArgumentException("native lifecycle wrapper schema");
        JSONObject nativeReceipt=receipt.getJSONObject("peer_lifecycle");
        String schema=nativeReceipt.getString("$schema");
        if(EmbeddedDuplexFreshLifecycle.SCHEMA.equals(schema))EmbeddedDuplexFreshLifecycle.require(nativeReceipt);
        if(java.util.Arrays.asList("rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1",
                "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v2","rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v3",
                "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v4").contains(schema)){
            String status=nativeReceipt.getString("status");
            if(!java.util.Arrays.asList("active","terminal","pending").contains(status))throw new IllegalArgumentException("native lifecycle status");
            if(!operation.equals(nativeReceipt.getString("action")))throw new IllegalArgumentException("native lifecycle action differs");
            if(!schema.endsWith(".v4") && nativeReceipt.has("last_failure_cause"))throw new IllegalArgumentException("cause added to old lifecycle schema");
            return status.equals("pending") || nativeReceipt.getBoolean("renewal_pending") ? nativeReceipt:null;
        }
        if(operation.equals("whole_app_close") && schema.equals("rusty.quest.embedded_duplex.remote_owner_cleanup.v1"))
            return "terminal".equals(nativeReceipt.optString("whole_app_physical_cleanup"))?null:nativeReceipt;
        throw new IllegalArgumentException("native lifecycle schema");
    }
    private static void validateResponse(String operation,String challenge,JSONObject actual)throws Exception{
        String[] required,optional;
        switch(operation){
            case "prepare_peer_start":
            case "reestablish_peer_roots": required=new String[]{"schema","status","challenge","receipt"}; optional=new String[]{}; break;
            case "concurrent_arm": required=new String[]{"schema","status","challenge","receipt"}; optional=new String[]{}; break;
            case "start": required=new String[]{"schema","status","challenge","receipt"}; optional=new String[]{}; break;
            case "renew_authority": required=new String[]{"schema","status","challenge","receipt"}; optional=new String[]{}; break;
            case "peer_stop": required=new String[]{"schema","status","challenge","receipt"}; optional=new String[]{}; break;
            case "peer_revoke": required=new String[]{"schema","status","challenge","receipt"}; optional=new String[]{}; break;
            case "whole_app_close": required=new String[]{"schema","status","challenge","receipt"}; optional=new String[]{}; break;
            case "policy_update": required=new String[]{"schema","status","challenge","receipt"}; optional=new String[]{}; break;
            case "blend_update": required=new String[]{"schema","status","challenge","receipt"}; optional=new String[]{}; break;
            case "bootstrap_real_peer": required=new String[]{"schema","status","challenge","runtime_state","display_attached","peer_route_proven","media_effect_proven"}; optional=new String[]{"cleanup_scope","own_app_capture_state","own_stop_requested","own_startup_settled","own_camera_thread_alive","own_compositor_thread_alive","own_compositor_physically_retired","own_compositor_cleanup_rejected","own_left_callback_barrier","own_right_callback_barrier","own_compositor_barrier","last_bootstrap_failure_stage","last_bootstrap_failure_code","setup_credential_policy","setup_credential_horizon_ms","setup_credentials_issued_at_ms","setup_credentials_expires_at_ms","runtime_config_sha256","enrollment_record_sha256"}; break;
            case "close_no_media": required=new String[]{"schema","status","challenge","runtime_state","display_attached","peer_route_proven","media_effect_proven","close_disposition"}; optional=new String[]{"cleanup_scope","own_app_capture_state","own_stop_requested","own_startup_settled","own_camera_thread_alive","own_compositor_thread_alive","own_compositor_physically_retired","own_compositor_cleanup_rejected","own_left_callback_barrier","own_right_callback_barrier","own_compositor_barrier","last_bootstrap_failure_stage","last_bootstrap_failure_code","setup_credential_policy","setup_credential_horizon_ms","setup_credentials_issued_at_ms","setup_credentials_expires_at_ms","runtime_config_sha256","enrollment_record_sha256"}; break;
            case "fresh_pair_continue":
            case "pair_session": required=new String[]{"schema","status","challenge","pair_state","local_session_current","remote_session_current","local_session_expires_at_ms","peer_route_proven","media_effect_proven"}; optional=new String[]{"session_id","last_step","last_failure_code","local_session_rejection_reason","remote_session_rejection_reason","local_session_validated_at_ms","remote_session_validated_at_ms"}; break;
            case "confirm": required=new String[]{"schema","status","challenge","review_sha256","role_id","revision","record_sha256","local_fixture"}; optional=new String[]{}; break;
            default:throw new IllegalArgumentException("operator response operation");
        }
        java.util.HashSet<String> allowed=new java.util.HashSet<>(java.util.Arrays.asList(required));
        allowed.addAll(java.util.Arrays.asList(optional));
        for(String key:required)if(!actual.has(key))throw new IllegalArgumentException("operator response missing field");
        java.util.Iterator<String> keys=actual.keys();
        while(keys.hasNext()){
            String key=keys.next();if(!allowed.contains(key))throw new IllegalArgumentException("operator response extra field");
            Object value=actual.get(key);
            boolean flag=java.util.Arrays.asList("display_attached","peer_route_proven","media_effect_proven","own_stop_requested",
                "own_startup_settled","own_camera_thread_alive","own_compositor_thread_alive","own_compositor_physically_retired",
                "own_compositor_cleanup_rejected","local_session_current","remote_session_current","local_fixture").contains(key);
            boolean number=key.endsWith("_at_ms") || key.endsWith("_horizon_ms") || key.equals("revision");
            if(flag?!(value instanceof Boolean):number?!(value instanceof Integer || value instanceof Long):!(value instanceof String))
                throw new IllegalArgumentException("operator response field type");
        }
        if(!"rusty.quest.embedded_duplex.operator_transport.v1".equals(actual.getString("schema"))
                || !challenge.equals(actual.getString("challenge")))throw new IllegalArgumentException("operator response original identity");
        if(!java.util.Arrays.asList("observed","committed").contains(actual.getString("status")))
            throw new IllegalArgumentException("operator response status");
    }
    static void bindActualActor(JSONObject actor) throws Exception { bindActualActor(actor, EmbeddedDuplexProcessHost.currentAppGeneration()); }
}
