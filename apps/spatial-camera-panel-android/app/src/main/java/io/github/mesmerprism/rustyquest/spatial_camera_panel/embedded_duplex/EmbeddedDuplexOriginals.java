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
    private static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;
    private static final Set<String> OPERATIONS = Set.of("start", "renew_authority", "peer_stop",
            "peer_revoke", "whole_app_close", "concurrent_arm", "policy_update", "blend_update");
    private static final Set<String> FIELDS = Set.of("schema", "original_id", "operation", "request_sha256",
            "original_challenge", "state", "effect_state", "actor_binding", "native_setup_binding",
            "response", "response_sha256", "failure", "outcome_sha256", "retention", "consumer_receipt_sha256");
    private static final ThreadLocal<Record> CURRENT = new ThreadLocal<>();
    private static EmbeddedDuplexOriginals singleton;
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
        Record(EmbeddedDuplexOriginals owner, JSONObject row) { this.owner=owner; this.row=row; }
        String id() { return row.optString("original_id"); }
    }

    public static synchronized EmbeddedDuplexOriginals forApplication(Context context) throws Exception {
        Context app=Objects.requireNonNull(context).getApplicationContext();
        if(singleton==null) {
            EmbeddedDuplexProcessHost.forApplication(app);
            singleton=new EmbeddedDuplexOriginals(app.getNoBackupFilesDir(),new ActorSource() {
                public JSONObject snapshot()throws Exception{return EmbeddedDuplexProcessHost.originalActor();}
                public long appGeneration(){return EmbeddedDuplexProcessHost.currentAppGeneration();}
                public boolean settled(JSONObject actor,long generation)throws Exception{return EmbeddedDuplexProcessHost.originalActorSettled(actor,generation);}
            },()->{
                // Sync the parent too: the first directory entry must survive a crash.
                for (File directory : new File[]{app.getNoBackupFilesDir(),
                        new File(app.getNoBackupFilesDir(), "operator-originals-v1")}) {
                    java.io.FileDescriptor fd=Os.open(directory.getAbsolutePath(),
                            OsConstants.O_RDONLY|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);
                    try{Os.fsync(fd);}finally{Os.close(fd);}
                }
            });
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
    private void requireWritable()throws Exception {
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
        return new JSONObject().put("schema",TRANSPORT).put("method",operation).put("challenge",challenge).put("extras",fields);
    }

    public Bundle begin(String originalId,String operation,String challenge,JSONObject request,
            Supplier<CompletableFuture<Bundle>> action)throws Exception {
        id(originalId);id(challenge);Objects.requireNonNull(action);
        if(!OPERATIONS.contains(operation))throw new IllegalArgumentException("unsupported Original operation");
        if (request == null || !keys(request).equals(Set.of("schema", "method", "challenge", "extras"))
                || !TRANSPORT.equals(request.getString("schema")) || !operation.equals(request.getString("method"))
                || !challenge.equals(request.getString("challenge")) || !(request.get("extras") instanceof JSONObject))
            throw new IllegalArgumentException("Original canonical request differs");
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
    private static AutoCloseable enter(Record actual) {
        Record previous=CURRENT.get();if(actual==null)CURRENT.remove();else CURRENT.set(actual);
        return ()->{if(previous==null)CURRENT.remove();else CURRENT.set(previous);};
    }
    /** Capture the actual source record before enqueue; neither IDs nor records enter from transport here. */
    static boolean hasCurrent() { return CURRENT.get() != null; }

    public static Runnable carry(Runnable action) {
        Record actual=CURRENT.get();if(actual==null)return action;
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
            if(actual.boundActor&&(!EmbeddedDuplexCanonicalJson.sameValue(actual.row.getJSONObject("actor_binding"),actor)
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
                JSONObject actual=validateResponse(record,response);
                record.row.put("response",actual).put("response_sha256",EmbeddedDuplexCanonicalJson.sha256(actual));
                boolean pending=nativePending(record.row.getString("operation"), actual);
                record.row.put("state",pending?"unknown":"succeeded").put("effect_state",pending?"uncertain":"observed");
            }
            record.row.put("outcome_sha256",EmbeddedDuplexCanonicalJson.sha256(outcome(record.row)));persist(record);
        } catch(Throwable unavailable){record.persistenceFailure=unavailable;}
        finally{record.callbackJoined=true;}
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
                && nativeSchema.equals("rusty.quest.embedded_duplex.remote_owner_cleanup.v1"))
            return !"terminal".equals(nativeReceipt.optString("whole_app_physical_cleanup"));
        if (!Set.of("rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1",
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
        record.row.put("consumer_receipt_sha256",consumerHash).put("retention","compacted");persist(record);live.remove(originalId);
        return envelope(record,challenge);
    }
    private Bundle envelope(Record record,String challenge)throws Exception {
        if(record.persistenceFailure!=null)throw new IllegalStateException("original publication unavailable",record.persistenceFailure);
        JSONObject observation=copy(record.row);String state=observation.getString("state");
        if(record.restored&&(state.equals("registered")||state.equals("pending")))observation.put("state","unknown");
        String status="compacted".equals(observation.getString("retention"))?"compacted":
                "succeeded".equals(observation.getString("state"))?"observed":"unknown".equals(observation.getString("state"))?"unknown":"pending";
        Bundle out=new Bundle();out.putString("schema",TRANSPORT);out.putString("status",status);out.putString("challenge",challenge);
        out.putString("original_id",record.id());out.putString("observation_json",EmbeddedDuplexCanonicalJson.encode(observation));return out;
    }
    private static JSONObject outcome(JSONObject row)throws Exception {
        JSONObject out=new JSONObject();for(String key:new String[]{"original_id","operation","request_sha256","original_challenge",
                "state","effect_state","actor_binding","native_setup_binding","response_sha256","failure"})out.put(key,row.get(key));return out;
    }
    private Record find(String originalId)throws Exception {
        Record current=live.get(originalId);if(current!=null)return current;
        File file=new File(directory,originalId+".json");
        if(Files.exists(new File(file.getPath()+".tmp").toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("original pending publication retained");
        if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file.toPath())||Files.size(file.toPath())>MAX_RECORD_BYTES)
            throw new IllegalStateException("original checkpoint type/bounds");
        JSONObject row=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        Set<String> keys=new HashSet<>();row.keys().forEachRemaining(keys::add);
        if(!keys.equals(FIELDS)||!RECORD.equals(row.getString("schema"))||!originalId.equals(row.getString("original_id"))
                ||!OPERATIONS.contains(row.getString("operation")))throw new IllegalStateException("original checkpoint closed schema");
        id(row.getString("original_id"));id(row.getString("original_challenge"));hash(row.getString("request_sha256"));
        if(!Set.of("registered","pending","unknown","succeeded").contains(row.getString("state"))
                ||!Set.of("not_entered","entered","uncertain","observed").contains(row.getString("effect_state"))
                ||!Set.of("owned","compacted").contains(row.getString("retention")))throw new IllegalStateException("original checkpoint state");
        if(!row.isNull("response_sha256")&&!row.getString("response_sha256").equals(EmbeddedDuplexCanonicalJson.sha256(row.getJSONObject("response"))))
            throw new IllegalStateException("original response digest differs");
        if(!row.isNull("outcome_sha256")&&!row.getString("outcome_sha256").equals(EmbeddedDuplexCanonicalJson.sha256(outcome(row))))
            throw new IllegalStateException("original outcome digest differs");
        JSONObject actor = row.getJSONObject("actor_binding");
        if (!keys(actor).equals(Set.of("package_id", "app_record_sha256", "native_generation"))
                || !(actor.get("package_id") instanceof String) || actor.getString("package_id").isEmpty()
                || !row.isNull("native_setup_binding")) throw new IllegalStateException("Original actor shape");
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
        if (row.getString("retention").equals("compacted")) {
            if (!state.equals("succeeded")) throw new IllegalStateException("Original compaction state");
            hash(row.getString("consumer_receipt_sha256"));
        } else if (!row.isNull("consumer_receipt_sha256")) throw new IllegalStateException("Original consumer state");
        Record restored=new Record(this,row);restored.restored=true;return restored;
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
}
